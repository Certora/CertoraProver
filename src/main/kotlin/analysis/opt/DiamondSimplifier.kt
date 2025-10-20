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

package analysis.opt

import analysis.*
import analysis.opt.DiamondSimplifier.CommandDisposition.*
import com.certora.collect.*
import config.*
import datastructures.stdcollections.*
import log.*
import tac.*
import utils.*
import vc.data.*
import vc.data.tacexprutil.*
import vc.data.TACExprFactUntyped as txf
import verifier.BlockMerger
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

private val logger = Logger(LoggerTypes.DIAMOND_SIMPLIFIER)

/**
    Attempts to reduce diamond control flow to a single ITE expression, to simplify the CFG.

    Say you have the following:

    ```
          A
         / \
        B   C
         \ /
          D

    A:
        ...
        if (cond) goto B else goto C
    B:
        v := (some computation)
        assume(assumption)
        goto D
    C:
        v := (some other computation)
        goto D
    D:
        (use v)

    ```

    If we can prove that the assignment to `v` is the only visible effect of both `B` and `C`, then we can reduce this
    to:

    ```
           A
           |
           D

    A:
        // ...
        v1 := (some computation)
        assume(!cond || assumption)
        v2 := (some other computation)
        v := ite(cond, v1, v2)
        goto D

    D:
        (use v)
 */
object DiamondSimplifier {
    fun simplifyDiamonds(
        origCode: CoreTACProgram,
        /**
            If true, applies the transform repeatedly until there are no more eligible diamonds.  This takes more time,
            but may save time later due to the more simplified graph.
         */
        iterative: Boolean,
        /** If true, will fold in the presence of assumes in the side blocks */
        allowAssumes : Boolean = true
    ): CoreTACProgram {
        var code = origCode
        do {
            code = BlockMerger.mergeBlocks(code)
            val diamonds = computeDiamondSimplifications(code, allowAssumes)
            if (diamonds.isEmpty()) {
                break
            }
            code = code.patching { p ->
                diamonds.forEach { d ->
                    p.replaceCommand(d.jumpPtr, d.replacement, treapSetOf(d.successor))
                    d.blocksToRemove.forEach { p.removeBlock(it) }
                    p.addVarDecls(d.extraVars)
                }
            }
        } while (iterative)
        return code
    }

    private data class DiamondSimplification(
        val jumpPtr: CmdPointer,
        val replacement: List<TACCmd.Simple>,
        val successor: NBId,
        val blocksToRemove: Set<NBId>,
        val extraVars: Set<TACSymbol.Var>
    )

    private fun computeDiamondSimplifications(
        code: CoreTACProgram,
        allowAssumes: Boolean
    ): List<DiamondSimplification> {
        val graph = code.analysisCache.graph
        val lva = code.analysisCache.lva
        val def = code.analysisCache.strictDef

        return graph.blocks.mapNotNull diamond@{ block ->
            // Does the block end in a conditional jump?
            val jump = block.commands.lastOrNull()?.maybeNarrow<TACCmd.Simple.JumpiCmd>() ?: return@diamond null
            val dst = graph.elab(jump.cmd.dst)
            val elseDst = graph.elab(jump.cmd.elseDst)

            // Is this block the only predecessor of both of its successors?
            val succ = listOf(dst, elseDst)
            if (succ.any { graph.pred(it).size != 1 }) { return@diamond null }

            // Is there a common successor for both branches?
            val join = succ.map { graph.succ(it.id) }.uniqueOrNull()?.uniqueOrNull()?.let(graph::elab) ?: return@diamond null

            // We don't want to reduce every possible diamond, because doing so might result in a single very complex
            // block which we will have a hard time solving.  Best to leave such as separate blocks so we can split the
            // solving.
            val destructive = code.destructiveOptimizations
            val relevantCommandCount =
                listOf(block, dst, elseDst, join)
                .sumOf {
                    it.commands.count {
                        when (it.cmd.disposition()) {
                            NON_MERGEABLE -> true
                            DESTRUCTIVELY_MERGEABLE_ANNOT -> !destructive
                            MERGEABLE -> true
                            IRRELEVANT -> false
                        }
                    }
                }

            if (relevantCommandCount > Config.MaxMergedBranchSize.get()) {
                logger.info {
                    "Not merging diamond at ${block.id} because it would result in a block with too many commands."
                }
                return@diamond null
            }

            // Check the effects of the commands in each branch.  We can only merge branches whose effects are only
            // assignments to variables (or, optionally, assumes), and whose operations are still valid/meaningful after
            // the merge.
            val nonMergeableAnnotations = mutableSetOf<LTACCmd>()
            val assigned = buildSet {
                succ.forEach { (_, commands) ->
                    commands.forEach command@{ lcmd ->
                        when (lcmd.cmd.disposition()) {
                            NON_MERGEABLE -> when (lcmd.cmd) {
                                is TACCmd.Simple.AnnotationCmd -> nonMergeableAnnotations.add(lcmd)
                                else -> {
                                    logger.debug {
                                        "Not merging diamond at ${block.id} because it contains a non-mergeable command: $lcmd"
                                    }
                                    return@diamond null
                                }
                            }
                            DESTRUCTIVELY_MERGEABLE_ANNOT -> if (!destructive) { nonMergeableAnnotations.add(lcmd) }
                            MERGEABLE -> {}
                            IRRELEVANT -> return@command
                        }
                        when (lcmd.cmd) {
                            is TACCmd.Simple.AssigningCmd -> add(lcmd.cmd.lhs)
                            is TACCmd.Simple.AnnotationCmd -> {}
                            is TACCmd.Simple.Assume -> if (!allowAssumes) { return@diamond null }
                            else -> error("Mergeable command with unknown effects: $lcmd")
                        }
                    }
                }
            }

            // If a live assigned variable might be undefined at the join point, we cannot merge this diamond, because
            // we would be introducing a definite definition of the variable.
            val liveAtJoin = assigned.filterToSet { lva.isLiveBefore(join.id, it) }.toSet()
            val maybeUndefined = liveAtJoin.filter { null in def.defSitesOf(it, join.commands[0].ptr) }
            if (maybeUndefined.isNotEmpty()) {
                logger.info {
                    "Not merging diamond at ${block.id} because it would definitely define $maybeUndefined"
                }
                return@diamond null
            }

            // If we reach this point, the diamond qualifies for reduction, except it may contain some non-mergeable
            // annotations.  If that's the only reason we can't merge, then write the annotations to the log to help
            // find annotation types we want to allow in the future.
            if (nonMergeableAnnotations.isNotEmpty()) {
                logger.info {
                    "In ${code.name}: Not merging diamond at ${block.id} because of non-mergeable annotations:\n${nonMergeableAnnotations.joinToString("\n")}"
                }
                return@diamond null
            }

            // We found a diamond that qualifies for reduction!

            // We will rewrite references to all of the variables that are assigned in either branch.  Each branch gets
            // a separate variable.
            val branchReplacementVars = succ.indices.flatMap { i ->
                assigned.map { v ->
                    (v to i) to v.withSuffix(i, "!").toUnique("!")
                }
            }.toMap()

            if (branchReplacementVars.size > Config.MaxMergedBranchExprs.get()) {
                logger.info {
                    "Not merging diamond at ${block.id} because it would result in too many ITE expressions: " +
                        "${branchReplacementVars.size} > ${Config.MaxMergedBranchExprs.get()}"
                }
                return@diamond null
            }

            // Generate the replacement commands for the conditional jump in this diamond.
            val replacements = buildList {
                // Add each branch's commands
                succ.forEachIndexed { i, (succId, commands) ->
                    // For each of the variables that we will rewrite in this branch, if the variable already has a
                    // value at the start of the block, then we need to assign that value to the replacement variable
                    // for this branch.
                    assigned.forEach { v ->
                        if (lva.isLiveBefore(succId, v)) {
                            add(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = branchReplacementVars[v to i]!!,
                                    rhs = v.asSym()
                                )
                            )
                        }
                    }

                    // For each command in this branch, rewrite the "effect" variables as above, and rewrite assumes to
                    // include the branch condition
                    val mapper = object : DefaultTACCmdMapper() {
                        override fun mapVar(t: TACSymbol.Var) =
                            branchReplacementVars[t to i] ?: t

                        override fun mapAssumeCmd(t: TACCmd.Simple.AssumeCmd) =
                            super.mapAssumeCmd(t).let {
                                makeAssume((it as TACCmd.Simple.Assume).condExpr, it.meta)
                            }

                        override fun mapAssumeNotCmd(t: TACCmd.Simple.AssumeNotCmd) =
                            super.mapAssumeNotCmd(t).let {
                                makeAssume((it as TACCmd.Simple.Assume).condExpr, it.meta)
                            }

                        override fun mapAssumeExpCmd(t: TACCmd.Simple.AssumeExpCmd) =
                            super.mapAssumeExpCmd(t).let {
                                makeAssume((it as TACCmd.Simple.Assume).condExpr, it.meta)
                            }

                        private fun makeAssume(cond: TACExpr, meta: MetaMap) =
                            if (i == 0) {
                                // this is the "true" path
                                TACCmd.Simple.AssumeExpCmd(
                                    txf { not(jump.cmd.cond.asSym()) or cond },
                                    meta
                                )
                            } else {
                                // the "false" path
                                TACCmd.Simple.AssumeExpCmd(
                                    txf { jump.cmd.cond.asSym() or cond },
                                    meta
                                )
                            }
                    }
                    addAll(commands.map { mapper.map(it.cmd) }.filter { it !is TACCmd.Simple.JumpCmd })
                }

                // For any assigned variables that are live at the join point, add an ITE assignment to select the value
                // of the correct branch-specific variable.
                assigned.forEach {
                    if (lva.isLiveBefore(join.id, it)) {
                        add(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = it,
                                rhs = txf {
                                    ite(
                                        jump.cmd.cond.asSym(),
                                        branchReplacementVars[it to 0]!!.asSym(),
                                        branchReplacementVars[it to 1]!!.asSym()
                                    )
                                }
                            )
                        )
                    }
                }

                add(TACCmd.Simple.JumpCmd(join.id))
            }

            DiamondSimplification(
                jumpPtr = jump.ptr,
                replacement = replacements,
                successor = join.id,
                blocksToRemove = succ.mapToSet { it.id },
                extraVars = branchReplacementVars.values.toSet()
            )
        }
    }

    /**
        Indicates how a command should be treated when attempting to simplify a diamond.
     */
    private enum class CommandDisposition(val precedence: Int) {
        /** May have effects that cannot be merged */
        NON_MERGEABLE(0),
        /** May have effects that cannot be merged, unless desrtuctive optimizations are enabled */
        DESTRUCTIVELY_MERGEABLE_ANNOT(1),
        /** Has effects that we know how to merge */
        MERGEABLE(2),
        /** Does not have any interesting effects; may be merged, and doesn't count toward overall command count. */
        IRRELEVANT(3),
    }

    /**
        Checks if the command might be invalid for some possible inputs.  I.e., if the command's validity in the program
        might depend on the result of a previous conditional branch.  In those cases we will avoid eliminating the
        branch.
     */
    private fun TACCmd.Simple.disposition(): CommandDisposition = when (this) {
        is TACCmd.Simple.AnnotationCmd -> annot.disposition()
        is TACCmd.Simple.AssertCmd -> NON_MERGEABLE
        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> rhs.disposition()
        is TACCmd.Simple.AssigningCmd.AssignGasCmd -> MERGEABLE
        is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> MERGEABLE
        is TACCmd.Simple.AssigningCmd.AssignMsizeCmd -> MERGEABLE
        is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> MERGEABLE
        is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> MERGEABLE
        is TACCmd.Simple.AssigningCmd.ByteLoad -> MERGEABLE
        is TACCmd.Simple.AssigningCmd.ByteStore -> MERGEABLE
        is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> MERGEABLE
        is TACCmd.Simple.AssigningCmd.WordLoad -> MERGEABLE
        is TACCmd.Simple.AssumeCmd -> MERGEABLE
        is TACCmd.Simple.AssumeExpCmd -> cond.disposition()
        is TACCmd.Simple.AssumeNotCmd -> MERGEABLE
        is TACCmd.Simple.ByteLongCopy -> NON_MERGEABLE
        is TACCmd.Simple.CallCore -> NON_MERGEABLE
        is TACCmd.Simple.JumpCmd -> IRRELEVANT
        is TACCmd.Simple.JumpdestCmd -> IRRELEVANT
        is TACCmd.Simple.JumpiCmd -> IRRELEVANT
        is TACCmd.Simple.LabelCmd -> IRRELEVANT
        is TACCmd.Simple.LogCmd -> NON_MERGEABLE
        is TACCmd.Simple.ReturnCmd -> NON_MERGEABLE
        is TACCmd.Simple.ReturnSymCmd -> NON_MERGEABLE
        is TACCmd.Simple.RevertCmd -> NON_MERGEABLE
        is TACCmd.Simple.SummaryCmd -> NON_MERGEABLE
        is TACCmd.Simple.WordStore -> NON_MERGEABLE
        TACCmd.Simple.NopCmd -> IRRELEVANT
    }

    private fun TACExpr.disposition(): CommandDisposition {
        val shallowDisposition = when (this) {
            is TACExpr.Apply -> when (val f = f) {
                is TACExpr.TACFunctionSym.BuiltIn -> when (f.bif) {
                    is TACBuiltInFunction.TwosComplement.Wrap -> NON_MERGEABLE
                    is TACBuiltInFunction.TwosComplement.Unwrap -> MERGEABLE
                    is TACBuiltInFunction.SafeMathPromotion -> MERGEABLE
                    is TACBuiltInFunction.SafeMathNarrow.Implicit -> NON_MERGEABLE
                    is TACBuiltInFunction.SafeMathNarrow.Assuming -> MERGEABLE
                    is TACBuiltInFunction.SignedPromotion -> MERGEABLE
                    is TACBuiltInFunction.UnsignedPromotion -> MERGEABLE
                    is TACBuiltInFunction.SafeSignedNarrow -> NON_MERGEABLE
                    is TACBuiltInFunction.SafeUnsignedNarrow -> NON_MERGEABLE
                    is TACBuiltInFunction.NoSMulOverAndUnderflowCheck -> MERGEABLE
                    is TACBuiltInFunction.NoSAddOverAndUnderflowCheck -> MERGEABLE
                    is TACBuiltInFunction.NoSSubOverAndUnderflowCheck -> MERGEABLE
                    is TACBuiltInFunction.DisjointSighashes -> MERGEABLE
                    is TACBuiltInFunction.LinkContractAddress -> MERGEABLE
                    is TACBuiltInFunction.NoAddOverflowCheck -> MERGEABLE
                    is TACBuiltInFunction.NoMulOverflowCheck -> MERGEABLE
                    is TACBuiltInFunction.OpaqueIdentity -> MERGEABLE
                    is TACBuiltInFunction.PrecompiledECRecover -> MERGEABLE
                    is TACBuiltInFunction.ToStorageKey -> MERGEABLE
                    is TACBuiltInFunction.Hash.Basic -> MERGEABLE
                    is TACBuiltInFunction.Hash.SimpleHashApplication -> MERGEABLE
                    is TACBuiltInFunction.Hash.Addition -> MERGEABLE
                    is TACBuiltInFunction.Hash.ToSkey -> MERGEABLE
                    is TACBuiltInFunction.Hash.FromSkey -> MERGEABLE
                    is TACBuiltInFunction.PartitionInitialize -> MERGEABLE
                    is TACBuiltInFunction.ReadTransientPartition -> MERGEABLE
                    is TACBuiltInFunction.NondetFunction -> MERGEABLE
                }
                is TACExpr.TACFunctionSym.Adhoc -> NON_MERGEABLE
            }
            is TACExpr.Sym -> MERGEABLE
            is TACExpr.Unconstrained -> MERGEABLE
            is TACExpr.BinExp -> MERGEABLE
            is TACExpr.Vec -> MERGEABLE
            is TACExpr.TernaryExp -> MERGEABLE
            is TACExpr.UnaryExp -> MERGEABLE
            is TACExpr.BinOp -> MERGEABLE
            is TACExpr.BinRel -> MERGEABLE
            is TACExpr.Select -> MERGEABLE
            is TACExpr.StoreExpr -> MERGEABLE
            is TACExpr.LongStore -> MERGEABLE
            is TACExpr.SimpleHash -> MERGEABLE
            is TACExpr.StructConstant -> MERGEABLE
            is TACExpr.StructAccess -> MERGEABLE
            is TACExpr.MapDefinition -> MERGEABLE
            is TACExpr.QuantifiedFormula -> MERGEABLE
            is TACExpr.AnnotationExp<*> -> MERGEABLE
        }
        return getOperands().map { it.disposition() }.plus(shallowDisposition).minBy { it.precedence }
    }

    private val annotationDispositions = ConcurrentHashMap<MetaKey<*>, CommandDisposition>()
    private fun TACCmd.Simple.AnnotationCmd.Annotation<*>.disposition() = annotationDispositions[k] ?: NON_MERGEABLE

    private fun <T : Serializable> MetaKey<T>.registerAnnotationDisposition(d: CommandDisposition) = apply {
        val prev = annotationDispositions.put(this, d)
        check(prev == null || prev == d) {
            "Redefining disposition of annotation $this from $prev to $d"
        }
    }

    /**
        Indicates that annotations with the given key should not prevent blocks in different branches from being merged.
     */
    fun <T : Serializable> MetaKey<T>.registerMergeableAnnot() =
        registerAnnotationDisposition(MERGEABLE)

    /**
        Indicates that annotations with the given key should prevent blocks in different branches from being merged,
        unless destructive optimizations are enabled.
     */
    fun <T : Serializable> MetaKey<T>.registerDestructivelyMergeableAnnot() =
        registerAnnotationDisposition(DESTRUCTIVELY_MERGEABLE_ANNOT)

    /**
        Indicates that annotations with the given key should always prevent blocks in different branches from being
        merged. (This is the default disposition for annotations.)
     */
    fun <T : Serializable> MetaKey<T>.registerNonMergeableAnnot() =
        registerAnnotationDisposition(NON_MERGEABLE)
}
