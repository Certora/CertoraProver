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

package analysis.storage

import datastructures.stdcollections.*
import analysis.narrow
import config.ReportTypes
import log.*
import scene.IContractClass
import utils.parallelStream
import utils.tryAs
import vc.data.CoreTACProgram
import vc.data.MethodRef
import vc.data.TACCmd
import vc.data.toRef
import verifier.ChainedMethodTransformers
import verifier.ContractUtils
import verifier.MethodToCoreTACTransformer
import java.util.stream.Collectors
import java.util.stream.Stream

private val logger = Logger(LoggerTypes.STORAGE_ANALYSIS)

object StoragePathPruner {

    /**
     * Prune unreachable paths as discovered by the storage analysis
     */
    fun prunePaths(c: IContractClass, res: Map<MethodRef, StorageAnalysisResults>) {
        val transformer = ChainedMethodTransformers(
            listOf(
                MethodToCoreTACTransformer(ReportTypes.STORAGE_ANALYSIS_PATH_PRUNING) { m ->
                    res[m.toRef()]?.tryAs<StorageAnalysisResults.MergedResults>()?.let {
                        val code = m.code as CoreTACProgram
                        pruneInfeasible(code, it)
                    } ?: m.code as CoreTACProgram
                }
            )
        )

        for (m in c.getDeclaredMethods()) {
            if (m.toRef() in res) {
                ContractUtils.transformMethodInPlace(method = m, transformers = transformer)
            }
        }
    }

    private fun pruneInfeasible(ctp: CoreTACProgram, result: StorageAnalysisResults.MergedResults): CoreTACProgram {
        val patching = ctp.toPatchingProgram()
        val g = ctp.analysisCache.graph

        val edges = g.commands.parallelStream().filter { it.cmd is TACCmd.Simple.JumpiCmd }.map {
            it.narrow<TACCmd.Simple.JumpiCmd>()
        }.flatMap { jumpiCmd ->
            if (jumpiCmd.cmd.dst in result.unreachable) {
                Stream.of(jumpiCmd to false)
            } else if (jumpiCmd.cmd.elseDst in result.unreachable) {
                Stream.of(jumpiCmd to true)
            } else {
                Stream.of()
            }
        }.collect(Collectors.toList())

        if (edges.isNotEmpty()) {
            logger.info {
                "Pruning infeasible edges in ${ctp.name} after storage analysis: $edges"
            }
        }

        patching.selectConditionalEdges(edges)

        return patching.toCode(ctp)
    }
}
