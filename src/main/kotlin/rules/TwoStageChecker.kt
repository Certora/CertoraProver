/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package rules

import analysis.opt.DiamondSimplifier.registerMergeableAnnot
import analysis.opt.intervals.Intervals
import analysis.opt.intervals.IntervalsCalculator
import config.Config
import config.DestructiveOptimizationsModeEnum
import config.ReportTypes
import datastructures.stdcollections.*
import log.*
import report.*
import rules.RuleChecker.CmdPointerList
import scene.IScene
import solver.CounterexampleModel
import spec.rules.IRule
import spec.rules.SingleRule
import tac.DumpTime
import tac.MetaKey
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.state.TACValue
import verifier.Verifier

private val logger = Logger(LoggerTypes.TWOSTAGE)

enum class TwoStageRound{
    ROUNDLESS,
    FIRST_ROUND,
    SECOND_ROUND
}

/**
 * This meta stores the original command pointer to an assigning command. This allows to do easily translate the CEX
 * model (that relates to the pre-solver tac) back to the pre-optimized tac that we are dealing with here. As metas
 * might be merged in the pipeline, we provide for a list of origins.
 */
val TWOSTAGE_META_VARORIGIN = MetaKey<CmdPointerList>("twostage.varorigin")

/**
 * The original block id in the unoptimized program.
 */
val TWOSTAGE_META_BLOCKORIGIN = MetaKey<NBId>("twostage.blockorigin").registerMergeableAnnot()

/**
 * This meta stores a fixed assignment to an assigning command. It is picked up during the Leino encoding to enforce
 * the stored value.
 */
val TWOSTAGE_META_MODEL = MetaKey<TACValue>("twostage.model")

/** Indicate that we got a violation, but it's missing the actual model */
class ViolationWithoutModelException : Exception("Got a violation, but the counter example model is missing")

/** Simple helper to dump TAC programs here */
private fun dumpTAC(tac: CoreTACProgram) {
    ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
        tac,
        ReportTypes.TWOSTAGE_PATCHED,
        StaticArtifactLocation.Outputs,
        DumpTime.AGNOSTIC
    )
}

/** Simple helper to send an alert here */
private fun sendAlert(rule: IRule, msg: String) {
    CVTAlertReporter.reportAlert(
        CVTAlertType.GENERAL,
        CVTAlertSeverity.WARNING,
        rule.range as? TreeViewLocation,
        msg,
        null
    )
}

/**
 * Maintains the [blocks] in the program by eliminating the block in the sets complement. Elimination is done
 * by assuming that they are unreachable.
 * We do not really remove those blocks from the program, but force them to be vacuous by adding an `assume(false)` and
 * relying on subsequent static analysis to actually remove them.
 */
private fun CoreTACProgram.maintainBlocks(blocks: Set<NBId>): CoreTACProgram {
    val newCode = code.map { (blk, cmds) ->
        blk to if (blk !in blocks) {
            logger.debug { "assuming false in ${blk}" }
            listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, "eliminateBlocks")) + cmds
        } else {
            cmds
        }
    }.toMap()
    return copy(code = newCode)
}
/**
 * Perform the first check with destructive optimizations enabled. Annotates the program beforehand, so that the model
 * can be used to fix variables to values in the rerun.
 */
private suspend fun doFirstCheck(tac: CoreTACProgram, check: suspend (CoreTACProgram, TwoStageRound) -> CompiledRule.CompileRuleCheckResult): CompiledRule.CompileRuleCheckResult {
    // annotate each assigning command with its CmdPointer
    val annotatedTac = tac.annotateWithTwoStageMeta().withDestructiveOptimizations(true).copy(name = "${tac.name}-firstrun")
    // dump tac of first run
    dumpTAC(annotatedTac)
    // do the first check
    return check(annotatedTac, TwoStageRound.FIRST_ROUND)
}


/**
 * Add the model to assigning commands in the tac program to fix variables to the values of the given [model]
 * which corresponds to the violation of the first run. Only those variables are fixed, for which [filter] returns
 * true. NB: we add meta to assigning, assert and more commands (see [fixedVariable]). The values are not "fixed"
 * in a strict TAC sense, i.e. by adding assume commands or using assigns directly - instead specific meta
 * [TWOSTAGE_META_MODEL] is used by the Leino encoding.
 *
 * [visitableBlocks] is the set of blocks that remained in the program post-optimizations - in the second round,
 * the search space can be limited to these blocks as the CEX .
 */
private fun patchTAC(
    tac: CoreTACProgram,
    model: CounterexampleModel,
    visitableBlocks: Set<NBId>,
    filter: (TACSymbol.Var, TACValue) -> Boolean
): CoreTACProgram {
    // maps CmdPointer from the original tac program to the assignment of the respective lhs
    val fixed = model.tacAssignments
        .flatMap { (sym, v) -> sym.meta[TWOSTAGE_META_VARORIGIN]?.ptrs?.map { it to v } ?: listOf() }
        .toMap()
    // go through tac and attach the model to every suitable assignment, assert and assume
    return tac.patching { p ->
        tac.analysisCache.graph.commands
            .filter { (ptr, _) -> ptr in fixed }
            .forEach { (ptr, cmd) ->
                cmd.fixedVariable()?.let { variable ->
                    val value = fixed[ptr]!!
                    if (filter(variable, value)) {
                        logger.debug { "fix $ptr -> $variable = $value" }
                        p.update(ptr, cmd.plusMeta(TWOSTAGE_META_MODEL, value))
                    }
                }
            }
    }.maintainBlocks(visitableBlocks)
}

/**
 * Use the [IntervalsCalculator] to check whether all blocks from the [model] are still reachable. We assume that the
 * [patchedTac] was already extended with the model values from the initial violation, making the check much more
 * powerful.
 */
private fun passesSanityCheck(patchedTac: CoreTACProgram, model: CounterexampleModel) =
    IntervalsCalculator(
        patchedTac,
        preserve = { false },
        seed = patchedTac.analysisCache.graph.commands
            .filter { TWOSTAGE_META_MODEL in it.cmd.meta }
            .mapNotNull { ltacmd ->
                val spot = when(val c = ltacmd.cmd){
                    is TACCmd.Simple.AssertCmd -> IntervalsCalculator.Spot.Expr(ltacmd.ptr, c.o)
                    is TACCmd.Simple.AssumeCmd -> IntervalsCalculator.Spot.Expr(ltacmd.ptr, c.cond)
                    is TACCmd.Simple.AssigningCmd ->  IntervalsCalculator.Spot.Lhs(ltacmd.ptr)
                    else -> `impossible!`
                }

                when (val v = ltacmd.cmd.meta[TWOSTAGE_META_MODEL]) {
                    is TACValue.PrimitiveValue -> spot to Intervals(v.asBigInt)
                    is TACValue.SKey.Basic -> spot to Intervals(v.offset.asBigInt)
                    else -> null
                }
            }
            .toList(),
    ).g.vertices.containsAll(model.reachableNBIds)

/**
 * Perform the second check without destructive optimizations. First annotates the program, adding the model assignments
 * to the corresponding commands meta, subject to the filter. Furthermore, we disable destructive optimizations and dump
 * the resulting patched tac program. We then first call out to [passesSanityCheck] and, if successful, do a full check.
 * If the violation is found to be spurious, either from the sanity check or the full check, we return null.
 */
private suspend fun doSecondCheck(
    tac: CoreTACProgram,
    model: CounterexampleModel,
    visitableBlocks: Set<NBId>,
    subname: String,
    check: suspend (CoreTACProgram, TwoStageRound) -> CompiledRule.CompileRuleCheckResult,
    filter: (TACSymbol.Var, TACValue) -> Boolean
): CompiledRule.CompileRuleCheckResult? {
    val name = "${tac.name}-${subname}"
    // do the second check without destructive optimizations
    val patchedTac = patchTAC(tac, model, visitableBlocks, filter).withDestructiveOptimizations(false).copy(name = name)
    // dump tac of the rerun
    dumpTAC(patchedTac)

    // do a sanity check on the model. It might just be a result of imprecise axioms
    if (!passesSanityCheck(patchedTac, model)) {
        logger.info { "${name}: violation failed sanity check, it is spurious" }
        Logger.regression { "${name}: violation failed sanity check" }
    }

    val secondResult = check(patchedTac, TwoStageRound.SECOND_ROUND)

    return if (secondResult.result.getOrThrow().result is Verifier.JoinedResult.Failure) {
        logger.info { "${name}: violation was confirmed, returning the second result" }
        Logger.regression { "${name}: violation was confirmed" }
        secondResult
    } else {
        logger.info { "${name}: violation could not be confirmed, it is spurious" }
        Logger.regression { "${name}: violation was not confirmed" }
        null
    }
}

/**
 * Returns the variable for a command that can be fixed, i.e., a variable
 * for which the value (if available) from the first run will be used in the second run.
 */
fun TACCmd.Simple.fixedVariable(): TACSymbol.Var? =
    when (this) {
        is TACCmd.Simple.AssigningCmd -> lhs
        is TACCmd.Simple.AssertCmd -> o as? TACSymbol.Var
        is TACCmd.Simple.AssumeCmd -> cond as? TACSymbol.Var
        else -> null
    }


/**
 * Returns a modified CoreTACProgram where each command that should be fixed according to [fixedVariable]
 * receives the meta [TWOSTAGE_META_VARORIGIN].
 */
fun CoreTACProgram.annotateWithTwoStageMeta(): CoreTACProgram {
    return ConcurrentPatchingProgram(this).let { p ->
        this.analysisCache.graph.blocks.parallelStream().forEach { b ->
            if (b.commands.isNotEmpty()) {
                val annotationCmd = TACCmd.Simple.AnnotationCmd(
                    TACCmd.Simple.AnnotationCmd.Annotation(TWOSTAGE_META_BLOCKORIGIN, b.id)
                )
                p.prependBefore(b.commands.first().ptr, listOf(annotationCmd))
                b.commands
                    .filter { it.cmd.fixedVariable() != null }
                    .forEach { (ptr, cmd) ->
                        p.replace(ptr, cmd.plusMeta(
                            key = TWOSTAGE_META_VARORIGIN,
                            v = CmdPointerList(ptr))
                        )
                    }
            }
        }
        p.toCode()
    }
}

/**
 * Implements a solving strategy that aims to safely employ destructive optimizations. While generally sound and
 * beneficial performance-wise, these optimizations are more likely to produce spurious violations and make calltrace
 * generation impossible. This function does the following:
 * - first run with destructive optimizations
 * - if a violation is found, patch the original tac program to fix variables to the violation's model
 * - do a quick sanity check (using interval analysis)
 * - do a rerun on that patched tac program without destructive optimizations
 * The last three steps are first done with the full model, if this find the violation is spurious we do them again but
 * only consider the Boolean variables from the violation.
 *
 * Check https://www.notion.so/certora/1b305a84c4fa4a90b73f63e1e1c4f9d6 for more details.
 */
@OptIn(Config.DestructiveOptimizationsOption::class)
suspend fun twoStageDestructiveOptimizationsCheck(
    rule: IRule,
    ruleDescription: String,
    tac: CoreTACProgram,
    check: suspend (CoreTACProgram, TwoStageRound) -> CompiledRule.CompileRuleCheckResult
): CompiledRule.CompileRuleCheckResult {
    // dump original tac
    dumpTAC(tac)
    val firstResult = doFirstCheck(tac, check)
    // if we have a violation, do the two-stage dance
    return if (firstResult.result.getOrNull()?.result is Verifier.JoinedResult.Failure) {
        logger.info { "Doing the two-stage dance" }
        // extract the violation and patch the tac program
        val result = firstResult.result.getOrThrow()
        var totalVerifyTime = result.verifyTime
        fun CompiledRule.CompileRuleCheckResult?.addToVerifyTime() = this?.also { totalVerifyTime = totalVerifyTime.join(result.verifyTime) }
        val model =
            result.result.examplesInfo?.head?.model
                ?: throw ViolationWithoutModelException()

        val visitableBlocks = result.result.simpleSimpleSSATAC.code.flatMap { (_, cmds) ->
            cmds.mapNotNull { it.maybeAnnotation(TWOSTAGE_META_BLOCKORIGIN) }
        }.toSet()

        val checkResult = (doSecondCheck(tac, model, visitableBlocks, "rerun-allvars", check) { _, _ -> true }).addToVerifyTime()
            ?: (doSecondCheck(tac, model, visitableBlocks, "rerun-numvars", check) { v, _ -> v.tag != Tag.Bool }).addToVerifyTime()
            ?: (doSecondCheck(tac, model, visitableBlocks, "rerun-boolvars", check) { v, _ -> v.tag == Tag.Bool }).addToVerifyTime()
            ?: run {
                val msg = "The violation for $ruleDescription could not be confirmed without destructive optimizations. It is probably spurious and the call trace generation likely fails. Please run again with `-destructiveOptimizations disable`."
                check(Config.DestructiveOptimizationsMode.get() != DestructiveOptimizationsModeEnum.TWOSTAGE_CHECKED) { msg }
                logger.warn { msg }
                sendAlert(rule, msg)
                firstResult
            }
        checkResult.copy(
            result = checkResult.result.getOrThrow().let { Result.success(ResultAndTime(it.result, totalVerifyTime)) }
        )
    } else {
        firstResult
    }
}

suspend fun <T : SingleRule> twoStageDestructiveOptimizationsCheck(
    scene: IScene,
    rule: CompiledRule<T>,
): CompiledRule.CompileRuleCheckResult {
    return twoStageDestructiveOptimizationsCheck(rule.rule, rule.rule.declarationId, rule.tac) { tac, _ ->
        CompiledRule.create(rule.rule, tac, rule.liveStatsReporter).check(scene.toIdentifiers())
    }
}
