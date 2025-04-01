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
import analysis.icfg.*
import analysis.ip.*
import com.certora.collect.*
import config.*
import datastructures.stdcollections.*
import log.*
import tac.*
import utils.*
import vc.data.*
import vc.data.TACMeta.CVL_LABEL_END
import vc.data.TACMeta.CVL_LABEL_START
import vc.data.TACExprFactUntyped as txf
import verifier.BlockMerger

private val logger = Logger(LoggerTypes.OPTIMIZE)

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
fun simplifyDiamonds(
    origCode: CoreTACProgram,
    /**
        If true, applies the transform repeatedly until there are no more eligible diamonds.  This takes more time, but
        may save time later due to the more simplified graph.
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

private fun computeDiamondSimplifications(code: CoreTACProgram, allowAssumes: Boolean): List<DiamondSimplification> {
    val graph = code.analysisCache.graph

    return graph.blocks.mapNotNull diamond@{ block ->
        // Does the block end in a conditional jump?
        val jump = block.commands.lastOrNull()?.maybeNarrow<TACCmd.Simple.JumpiCmd>() ?: return@diamond null
        val dst = graph.elab(jump.cmd.dst)
        val elseDst = graph.elab(jump.cmd.elseDst)

        // Is this block the only predecessor of both of its successors?
        val succ = listOf(dst, elseDst)
        if (succ.any { graph.pred(it).size != 1 }) { return@diamond null }

        // Is there a common successor for both branches?
        val join = succ.flatMap { graph.succ(it.id) }.uniqueOrNull()?.let(graph::elab) ?: return@diamond null

        // Each branch should have only simple assign/assume effects, or "noise" commands, and the set of live variables
        // assigned must be the same for each branch.

        fun TACCmd.Simple.isIrrelevant() =
            when (this) {
                is TACCmd.Simple.AnnotationCmd -> when {
                    code.destructiveOptimizations -> true
                    annot.v is InternalFuncStartAnnotation -> false
                    annot.v is InternalFuncExitAnnotation -> false
                    annot.v is Inliner.CallStack.PushRecord -> false
                    annot.v is Inliner.CallStack.PopRecord -> false
                    annot.v is SnippetCmd -> false
                    annot.v is SummaryStack.SummaryStart -> false
                    annot.v is SummaryStack.SummaryEnd -> false
                    annot.k == CVL_LABEL_END -> false
                    annot.k == CVL_LABEL_START -> false
                    else -> true
                }
                is TACCmd.Simple.NopCmd -> true
                is TACCmd.Simple.LabelCmd -> true
                is TACCmd.Simple.JumpdestCmd -> true
                is TACCmd.Simple.JumpCmd -> true
                is TACCmd.Simple.JumpiCmd -> true
                else -> false
            }

        // We don't want to reduce every possible diamond, because doing so might result in a single very complex block
        // which we will have a hard time solving.  Best to leave such as separate blocks so we can split the solving.
        val relevantCommandCount =
            listOf(block, dst, elseDst, join)
            .sumOf { it.commands.count { !it.cmd.isIrrelevant() }}

        if (relevantCommandCount > Config.MaxMergedBranchSize.get()) {
            logger.info {
                "Not merging diamond at ${block.id} because it would result in a block with too many commands."
            }
            return@diamond null
        }

        val liveEffects = succ.map { (_, commands) ->
            commands.mapNotNullToSet { (ptr, cmd) ->
                when {
                    cmd.isIrrelevant() -> null

                    cmd is TACCmd.Simple.AssigningCmd -> when {
                        cmd.lhs.tag.isPrimitive() -> cmd.lhs
                        else -> return@diamond null
                    }

                    // We'll rewrite these later
                    cmd is TACCmd.Simple.Assume ->
                        if (allowAssumes) {
                            null
                        } else {
                            return@diamond null
                        }

                    // Assume any other command has a visible side-effect
                    else -> return@diamond null.also {
                        logger.trace { "Unexpected command in diamond: $ptr: $cmd" }
                    }
                }?.takeIf { lhs ->
                    val lva = code.analysisCache.lva
                    if (lva.isLiveBefore(ptr, lhs)) {
                        // This assignment is overwriting an old variable, so this might be a visible side-effect
                        logger.trace {
                            "Cannot rewrite diamond because live variable $lhs is being overwritten at $ptr: $cmd"
                        }
                        return@diamond null
                    }
                    // Is this variable live at the join point?
                    lva.isLiveBefore(join.id, lhs)
                }
            }
        }.let {
            // The live variables must be the same for each branch
            it.uniqueOrNull() ?: return@diamond null
        }

        // We found a diamond that qualifies for reduction!

        // We will rewrite references to the live variables.  Var foo -> foo!0 and foo!1.
        val branchReplacementVars = succ.indices.flatMap { i ->
            liveEffects.map { v ->
                (v to i) to v.withSuffix(i, "!").toUnique("!")
            }
        }.toMap()

        val branchReplacements = succ.mapIndexed { i, (_, commands) ->
            // Rewrite the variables as above and rewrite assumes to include the branch condition
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
            commands.map { mapper.map(it.cmd) }.filter { it !is TACCmd.Simple.JumpCmd }
        }

        // Concatenate the branch replacements, and add an ITE command for each effect
        val replacements = branchReplacements.flatten() + liveEffects.map {
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
        } + listOf(
            TACCmd.Simple.JumpCmd(join.id)
        )

        DiamondSimplification(
            jumpPtr = jump.ptr,
            replacement = replacements,
            successor = join.id,
            blocksToRemove = succ.mapToSet { it.id },
            extraVars = branchReplacementVars.values.toSet()
        )
    }
}
