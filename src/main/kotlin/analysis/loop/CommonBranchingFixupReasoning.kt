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

package analysis.loop

import analysis.*
import analysis.dataflow.IMustEqualsAnalysis
import analysis.smtblaster.*
import instrumentation.transformers.*
import parallel.*
import smtlibutils.data.SmtExp
import vc.data.*

/**
 * Finds fixup blocks by checking that:
 *   1. the successor of the copy loop ends in a branch
 *   2. one of the destination blocks of said branch is the fixup
 */
abstract class CommonBranchingFixupReasoning(zb: IBlaster, builder: AbstractSmtExpTermBuilder, me: IMustEqualsAnalysis) : CommonFixupReasoning(zb, builder, me) {

    override fun findPostWriteFixup(g: TACCommandGraph, smtCommandGen: Generator, soleSuccBlock: TACBlock, loopConds: LoopConditions, loopInstantiations: LoopInstantiations): Parallel<PostWriteFixup?>? {
        // Our assumption is that we'll jump to a fixup block conditionally,
        val i = soleSuccBlock.commands.last().cmd as? TACCmd.Simple.JumpiCmd ?: return null

        val state = mutableMapOf<TACSymbol.Var, TACExpr>()
        val mapper = makeStateMapper(state, loopInstantiations)
        for (c in soleSuccBlock.commands) {
            if (!stepSymbolic(mapper, state, c)) {
                return null
            }
        }

        // Enumerate the successors of the [soleSuccBlock], and for each check to see if
        // one of them is the fixup block...
        val pc = state[i.cond] ?: return null
        val blaster = ExpressionTranslator(builder, List<SmtExp>::toTypedArray)
        val encodedPc = blaster.blastExpr(pc) {
            it.toSMTRep()
        } ?: return null
        val cleanupBlocks = g.pathConditionsOf(soleSuccBlock.id).entries.forkEveryOrNull entryFork@{ (dst, cond) ->
            if (!plasusibleFixupBlock(g.elab(dst))) {
                return@entryFork null
            }
            val smtCommandsPath =
                when (cond) {
                    is PathCondition.EqZero -> {
                        smtCommandGen.bind { smtCommandsPath ->
                            smtCommandsPath.assert {
                                eq(encodedPc, const(0))
                            }
                        }
                    }

                    is PathCondition.NonZero -> {
                        smtCommandGen.bind { smtCommandsPath ->
                            smtCommandsPath.assert {
                                lnot(eq(encodedPc, const(0)))
                            }
                        }
                    }

                    else -> return@entryFork null
                }

            whenPreExitWriteInexact(smtCommandsPath, loopConds) bindFalseAsNull@{
                /* We're here because we might have overwritten on the currently-asserted path */
                val destBlock = g.elab(dst)/* make sure we join with the target of our other branch */
                val dstSucc = g.succ(dst).singleOrNull() ?: return@bindFalseAsNull Scheduler.complete(null)
                val otherBranch = g.succ(soleSuccBlock.id).filter { it != dst }.map {
                    g.elab(it)
                }.mapNotNull {
                    if (TrivialBlockClassifier.isTrivialBlockTo(it, g) || (it.commands.first().cmd.let {
                                it as? TACCmd.Simple.AnnotationCmd
                            }?.annot?.k == DSA_BLOCK_START && it.commands.last().cmd.let {
                                it as? TACCmd.Simple.AnnotationCmd
                            }?.annot?.k == DSA_BLOCK_END)) {
                        g.succ(it.id).singleOrNull()
                    } else {
                        it.id
                    }
                }.toSet()
                if (setOf(dstSucc) != otherBranch) {
                    return@bindFalseAsNull Scheduler.complete(null)
                }
                val localState = state.toMutableMap()
                val localMapper = makeStateMapper(localState, loopInstantiations)
                checkFixupBlock(smtCommandsPath, destBlock, localState, localMapper).maybeBind {
                    Scheduler.complete(Triple(dst, dstSucc, it))
                }
            }
        }

        return cleanupBlocks.pcompute().map { res ->
            res.mapNotNull { it }.singleOrNull()?.let { (cleanup, succ, fixupTags) ->
                val diamond = g.succ(soleSuccBlock.id).filter {
                    it in g.pred(succ) && it != cleanup
                }
                PostWriteFixup.ConditionalFixup(
                    fixupBlocks = setOf(cleanup, soleSuccBlock.id) + diamond,
                    succBlock = succ,
                    inputAccess = fixupTags.inputAccess,
                    outputAccess = fixupTags.outputAccess
                )
            }
        }
    }
}
