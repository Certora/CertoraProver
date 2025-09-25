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
import tac.DumpTime
import tac.MetaKey
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.state.TACValue
import verifier.Verifier

private val logger = Logger(LoggerTypes.TWOSTAGE)

/**
 * This meta stores the original command pointer to an assigning command. This allows to do easily translate the CEX
 * model (that relates to the pre-solver tac) back to the pre-optimized tac that we are dealing with here. As metas
 * might be merged in the pipeline, we provide for a list of origins.
 */
val TWOSTAGE_META_VARORIGIN = MetaKey<CmdPointerList>("twostage.varorigin")

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
 * Eliminates [blocks] from the program, assuming that they are unreachable (here: because we also fix the model).
 * We do not really remove those blocks from the program, but force them to be vacuous by adding an `assert(false)` and
 * relying on subsequent static analysis to actually remove them. To make things easy for us, we keep the graph
 * structure intact by injecting artificial (conditional) jump commands based on the blocks successors.
 *
 * Important note: this only works with `-canonicalizeTAC false`, as canonicalization renames all blocks.
 */
private fun CoreTACProgram.eliminateBlocks(blocks: Set<NBId>): CoreTACProgram {
    val newCode = code.map { (blk, cmds) ->
        blk to if (blk in blocks) {
            logger.debug { "eliminating ${blk}" }
            val succs = blockgraph[blk]
            listOfNotNull(
                TACCmd.Simple.AssumeCmd(TACSymbol.False, "eliminateBlocks"),
                when {
                    succs.isNullOrEmpty() -> null
                    succs.size == 1 -> TACCmd.Simple.JumpCmd(succs.single())
                    succs.size == 2 -> TACCmd.Simple.JumpiCmd(succs.first(), succs.last(), TACSymbol.True)
                    else -> null
                }
            )
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
private suspend fun doFirstCheck(tac: CoreTACProgram, check: suspend (CoreTACProgram) -> CompiledRule.CompileRuleCheckResult): CompiledRule.CompileRuleCheckResult {
    // annotate each assigning command with its CmdPointer
    val annotatedTac = tac.patching { p ->
        tac.analysisCache.graph.commands
            .filter { it.cmd is TACCmd.Simple.AssigningCmd }
            .forEach { (ptr, cmd) -> p.update(ptr, cmd.plusMeta(TWOSTAGE_META_VARORIGIN, CmdPointerList(ptr))) }
    }.withDestructiveOptimizations(true).copy(name = "${tac.name}-firstrun")
    // dump tac of first run
    dumpTAC(annotatedTac)
    // do the first check
    return check(annotatedTac)
}


/**
 * Add the model to assigning commands in the tac program to fix variables to the values of the given [model]
 * which corresponds to the violation of the first run. Only those variables are fixed, for which [filter] returns
 * true. NB: we only add some meta to assigning commands, but do not "fix" values in a strict TAC sense, i.e. by adding
 * assume commands.
 */
private fun patchTAC(
    tac: CoreTACProgram,
    model: CounterexampleModel,
    filter: (TACSymbol.Var, TACValue) -> Boolean
): CoreTACProgram {
    // maps CmdPointer from the original tac program to the assignment of the respective lhs
    val fixed = model.tacAssignments
        .flatMap { (sym, v) -> sym.meta[TWOSTAGE_META_VARORIGIN]?.ptrs?.map { it to v } ?: listOf() }
        .toMap()
    // go through tac and attach the model to every suitable assignment
    return tac.patching { p ->
        tac.analysisCache.graph.commands
            .filter { (ptr, _) -> ptr in fixed }
            .filter { (_, cmd) -> cmd is TACCmd.Simple.AssigningCmd }
            .forEach { (ptr, cmd) ->
                val variable = (cmd as TACCmd.Simple.AssigningCmd).lhs
                val value = fixed[ptr]!!
                if (filter(variable, value)) {
                    logger.debug { "fix ${ptr} -> ${variable} = ${value}" }
                    p.update(ptr, cmd.plusMeta(TWOSTAGE_META_MODEL, value))
                }
            }
    }.eliminateBlocks(model.unreachableNBIds).also {
        logger.debug { "Eliminated ${model.unreachableNBIds.size} / ${tac.code.size} blocks from ${tac.name}" }
    }
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
            .mapNotNull {
                it.ptr `to?` when (val v = it.cmd.meta[TWOSTAGE_META_MODEL]) {
                    is TACValue.PrimitiveValue -> v.asBigInt
                    is TACValue.SKey.Basic -> v.offset.asBigInt
                    else -> null
                }
            }
            .map { (ptr, v) -> IntervalsCalculator.Spot.Lhs(ptr) to Intervals(v) }
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
    subname: String,
    check: suspend (CoreTACProgram) -> CompiledRule.CompileRuleCheckResult,
    filter: (TACSymbol.Var, TACValue) -> Boolean
): CompiledRule.CompileRuleCheckResult? {
    val name = "${tac.name}-${subname}"
    // do the second check without destructive optimizations
    val patchedTac = patchTAC(tac, model, filter).withDestructiveOptimizations(false).copy(name = name)
    // dump tac of the rerun
    dumpTAC(patchedTac)

    // do a sanity check on the model. It might just be a result of imprecise axioms
    if (!passesSanityCheck(patchedTac, model)) {
        logger.info { "${name}: violation failed sanity check, it is spurious" }
        Logger.regression { "${name}: violation failed sanity check" }
    }

    val secondResult = check(patchedTac)

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
    check: suspend (CoreTACProgram) -> CompiledRule.CompileRuleCheckResult
): CompiledRule.CompileRuleCheckResult {
    // dump original tac
    dumpTAC(tac)
    val firstResult = doFirstCheck(tac, check)
    // if we have a violation, do the two-stage dance
    return if (firstResult.result.getOrNull()?.result is Verifier.JoinedResult.Failure) {
        logger.info { "Doing the two-stage dance" }
        // extract the violation and patch the tac program
        var totalVerifyTime = firstResult.result.getOrThrow().verifyTime
        fun CompiledRule.CompileRuleCheckResult?.addToVerifyTime() = this?.also { totalVerifyTime = totalVerifyTime.join(result.getOrThrow().verifyTime) }
        val model =
            firstResult.result.getOrNull()?.result?.examplesInfo?.head?.model
                ?: throw ViolationWithoutModelException()
        val checkResult = (doSecondCheck(tac, model, "rerun-allvars", check) { _, _ -> true }).addToVerifyTime()
            ?: (doSecondCheck(tac, model, "rerun-boolvars", check) { v, _ -> v.tag == Tag.Bool }).addToVerifyTime()
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

suspend fun twoStageDestructiveOptimizationsCheck(
    scene: IScene,
    rule: CompiledRule,
): CompiledRule.CompileRuleCheckResult {
    return twoStageDestructiveOptimizationsCheck(rule.rule, rule.rule.description, rule.tac) { tac ->
        CompiledRule.create(rule.rule, tac, rule.liveStatsReporter).check(scene.toIdentifiers())
    }
}
