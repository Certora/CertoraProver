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

package analysis.controlflow

import allocator.Allocator
import analysis.CmdPointer
import analysis.PathCondition
import analysis.narrow
import com.certora.collect.*
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import tac.MetaKey
import tac.NBId
import tac.Tag
import utils.AmbiSerializable
import vc.data.*

private val logger = Logger(LoggerTypes.PRUNING)

object InfeasibleBranchPruning {
    val PRUNED_BRANCH_KEY = MetaKey<BranchCondition>("optimization.pruning.branch")

    @kotlinx.serialization.Serializable
    data class BranchCondition(
        val conditionVar: TACSymbol.Var,
        val takenBranch: Boolean
    ) : AmbiSerializable, TransformableVarEntity<BranchCondition> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): BranchCondition =
            copy(conditionVar = f(conditionVar))
    }

    private sealed class PruneRewrite

    private data class BranchRewrite(
        val takenBranch: Boolean,
        val assumeExp: TACExpr,
        val where: CmdPointer,
        val newSucc: NBId,
        val pruneStart: NBId
    ) : PruneRewrite()

    private data class KillBlock(
        val id: NBId
    ) : PruneRewrite()

    fun pruneBranches(p: CoreTACProgram, pruned: List<InfeasiblePathAnalysis.PruneNode>): CoreTACProgram {
        val g = p.analysisCache.graph
        val patching = p.toPatchingProgram()
        // if we apply pruning on an all-paths-are-reverting program, we have an error in the transformation.
        // to make sure this doesn't happen here, we allow one choice per conditional command
        val conditionalsPruned = mutableSetOf<CmdPointer>()
        /*
         * Currently, the only PrunedBlock that we actually operate on is one where the entry block has been
         * pruned. In that case, we replace the whole program with `assume false`.
         */
        if(pruned.any {
            it is InfeasiblePathAnalysis.PrunedBlock && it.prunedBlock == p.entryBlockId
            }) {
            val entry = p.entryBlockId
            return CoreTACProgram(
                blockgraph = BlockGraph(entry to treapSetOf()),
                code = mapOf(entry to listOf(
                    TACCmd.Simple.AssumeCmd(TACSymbol.False, "pruneBranches")
                )),
                name = p.name,
                check = true,
                entryBlock = entry,
                procedures = setOf(),
                symbolTable = TACSymbolTable.empty(),
                ufAxioms = UfAxioms.empty()
            )
        }
        pruned.parallelStream().map {
            when(it) {
                is InfeasiblePathAnalysis.PrunedBranch -> {
                    logger.debug { "Working on pruned branch ${it}: ${g.elab(it.conditionPtr).cmd.metaSrcInfo?.getSourceCode()}" }
                    when (it.infeasibleCondition) {
                        is PathCondition.EqZero -> {
                            val last = g.elab(it.conditionPtr).narrow<TACCmd.Simple.JumpiCmd>()
                            check(last.cmd.elseDst == it.targetBranch)
                            val assumeExp = if (it.infeasibleCondition.v.tag == Tag.Bool) {
                                it.infeasibleCondition.v.asSym()
                            } else {
                                TACExpr.UnaryExp.LNot(
                                    TACExpr.BinRel.Eq(
                                        it.infeasibleCondition.v.asSym(),
                                        0.toBigInteger().asTACSymbol().asSym()
                                    )
                                )
                            }
                            BranchRewrite(
                                where = it.conditionPtr,
                                assumeExp = assumeExp,
                                newSucc = last.cmd.dst,
                                pruneStart = last.cmd.elseDst,
                                takenBranch = true
                            )
                        }

                        is PathCondition.NonZero -> {
                            val last = g.elab(it.conditionPtr).narrow<TACCmd.Simple.JumpiCmd>()
                            check(last.cmd.dst == it.targetBranch)
                            val assumeExp = if (it.infeasibleCondition.v.tag == Tag.Bool) {
                                TACExpr.UnaryExp.LNot(it.infeasibleCondition.v.asSym())
                            } else {
                                TACExpr.BinRel.Eq(
                                    it.infeasibleCondition.v.asSym(),
                                    0.toBigInteger().asTACSymbol().asSym()
                                )
                            }
                            BranchRewrite(
                                where = it.conditionPtr,
                                assumeExp = assumeExp,
                                newSucc = last.cmd.elseDst,
                                pruneStart = last.cmd.dst,
                                takenBranch = false
                            )
                        }

                        else -> throw UnsupportedOperationException("Do not know to prune condition ${it.infeasibleCondition}")
                    }
                }
                is InfeasiblePathAnalysis.PrunedBlock -> {
                    KillBlock(it.prunedBlock)
                }
            }
        }.sequential().forEach {
            // prune path only if not pruned same jump before, and if a previous prune did not lead to deletion of this block
            if (it is BranchRewrite && it.where !in conditionalsPruned && patching.isBlockStillInGraph(it.where.block)) {
                conditionalsPruned.add(it.where)
                val pruneCond = Allocator.getFreshNumber()
                val conditionalCmd = g.elab(it.where)
                val assumeVar = TACSymbol.Var(
                    "pruneAssume!$pruneCond",
                    tag = Tag.Bool
                )
                patching.addVarDecl(assumeVar)
                val jumpiCmd = conditionalCmd.narrow<TACCmd.Simple.JumpiCmd>()
                patching.selectConditionalEdge(jumpiCmd, it.takenBranch)
                patching.insertAlongEdge(conditionalCmd.ptr.block, it.newSucc, listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        assumeVar,
                        it.assumeExp
                    ),
                    TACCmd.Simple.AssumeCmd(assumeVar, "pruneBranches", meta = (jumpiCmd.cmd.cond as? TACSymbol.Var)?.let { cond ->
                        conditionalCmd.cmd.meta.plus(
                            PRUNED_BRANCH_KEY to BranchCondition(
                                takenBranch = it.takenBranch,
                                conditionVar = cond
                            )
                        )
                    } ?: jumpiCmd.cmd.meta
                    )
                ))
            } else if(it is KillBlock && patching.isBlockStillInGraph(it.id)) {
                val newBlock = patching.addBlock(it.id, listOf(
                    TACCmd.Simple.AssumeCmd(TACSymbol.False, "pruneBranches")
                ))
                patching.consolidateEdges(newBlock, listOf(it.id))
            }
        }
        return patching.toCode(p)
    }
}
