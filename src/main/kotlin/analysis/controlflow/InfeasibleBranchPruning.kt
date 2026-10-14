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

import analysis.CmdPointer
import analysis.PathCondition
import analysis.narrow
import com.certora.collect.*
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import tac.MetaKey
import tac.NBId
import utils.AmbiSerializable
import vc.data.*
import verifier.PatchingProgramWrapper

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

    /** The conditional jump at [where] becomes an assumption of its branch to [newSucc], and a jump there. */
    private data class BranchRewrite(
        val where: CmdPointer,
        val newSucc: NBId
    ) : PruneRewrite()

    private data class KillBlock(
        val id: NBId
    ) : PruneRewrite()

    fun pruneBranches(p: CoreTACProgram, pruned: List<InfeasiblePathAnalysis.PruneNode>): CoreTACProgram {
        val g = p.analysisCache.graph
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
        return pruned.parallelStream().map {
            when(it) {
                is InfeasiblePathAnalysis.PrunedBranch -> {
                    logger.debug { "Working on pruned branch ${it}: ${g.elab(it.conditionPtr).cmd.metaSrcInfo?.getSourceCode()}" }
                    val last = g.elab(it.conditionPtr).narrow<TACCmd.Simple.JumpiCmd>()
                    when (it.infeasibleCondition) {
                        is PathCondition.EqZero -> {
                            check(last.cmd.elseDst == it.targetBranch)
                            BranchRewrite(where = it.conditionPtr, newSucc = last.cmd.dst)
                        }

                        is PathCondition.NonZero -> {
                            check(last.cmd.dst == it.targetBranch)
                            BranchRewrite(where = it.conditionPtr, newSucc = last.cmd.elseDst)
                        }

                        else -> throw UnsupportedOperationException("Do not know to prune condition ${it.infeasibleCondition}")
                    }
                }
                is InfeasiblePathAnalysis.PrunedBlock -> {
                    KillBlock(it.prunedBlock)
                }
            }
        }.sequential().toList().let { rewrites ->
            // All the rewrites are recorded first and the code they disconnect is erased once, when the wrapper builds
            // the program (removing it per rewrite swept the whole graph each time: 24 minutes on a program with
            // thousands of pruned branches). A killed block wins over a branch rewrite inside it, as before.
            val patching = PatchingProgramWrapper(p)
            val killed = rewrites.mapNotNullTo(mutableSetOf()) { (it as? KillBlock)?.id }
            // if we apply pruning on an all-paths-are-reverting program, we have an error in the transformation.
            // to make sure this doesn't happen here, we allow one choice per conditional command
            val conditionalsPruned = mutableSetOf<CmdPointer>()
            for (rewrite in rewrites) {
                if (rewrite is BranchRewrite && rewrite.where.block !in killed && conditionalsPruned.add(rewrite.where)) {
                    patching.jumpiTojump(src = rewrite.where.block, dst = rewrite.newSucc)
                } else if (rewrite is KillBlock) {
                    patching.replaceBlockWithSink(rewrite.id, listOf(
                        TACCmd.Simple.AssumeCmd(TACSymbol.False, "pruneBranches")
                    ))
                }
            }
            patching.toCode()
        }
    }
}
