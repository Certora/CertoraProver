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

package analysis

import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import log.Logger
import log.LoggerTypes
import tac.MetaKey
import tac.NBId
import utils.mapToSet
import vc.data.AnalysisCache
import vc.data.TACCmd
import vc.data.TACMeta

private val logger = Logger(LoggerTypes.COMMON)


// What does this match exactly?
object RevertBlockAnalysis : AnalysisCache.Key<Set<NBId>> {
    override fun createCached(graph: TACCommandGraph) = findRevertBlocks(graph)

    fun findRevertBlocks(graph: TACCommandGraph) : Set<NBId> =
        graph.blocks.asSequence().filter {
            it.commands.any { it.cmd is TACCmd.Simple.RevertCmd }
            || graph.pred(it).any { pred ->
                pred.commands.last().let { lst ->
                    lst.cmd is TACCmd.Simple.RevertCmd || lst.cmd is TACCmd.Simple.ReturnCmd || lst.cmd is TACCmd.Simple.ReturnSymCmd
                }
            }
            || graph.succ(it).singleOrNull()?.let { succ ->
                succ.commands.any { it.cmd is TACCmd.Simple.RevertCmd }
            } == true
        }
        .mapToSet { it.id }

    /**
     * Checks if the node [node] must reach a revert annotation in [graph]
     */
    fun mustReachRevert(node: NBId, graph: TACCommandGraph): Boolean =
        (object : VisitingWorklistIteration<NBId, Boolean, Boolean>() {
            val relevantAnnotations = setOf(TACMeta.REVERT_PATH, TACMeta.RETURN_PATH)
            fun findRelevantAnnotation(n: NBId): List<MetaKey<*>> {
                val cmds = graph.elab(n)
                return cmds.commands.mapNotNull {
                    (it.cmd as? TACCmd.Simple.AnnotationCmd)?.annot?.k?.takeIf { it in relevantAnnotations }
                }
            }

            override fun process(it: NBId): StepResult<NBId, Boolean, Boolean> {
                // if contains a revert path annotation, stop with true. if return path - stop because it is not a definite revert path
                val relevantAnnotations = findRelevantAnnotation(it)
                return if (relevantAnnotations.size > 1) {
                    logger.warn { "Block $it has more than a single relevant annotations: $relevantAnnotations" }
                    cont(emptyList())
                } else if (relevantAnnotations.isEmpty()) {
                    // continue with successors
                    val succ = graph.blockSucc[it] ?: emptySet()
                    cont(succ)
                } else {
                    when (relevantAnnotations.single()) {
                        TACMeta.RETURN_PATH -> {
                            halt(false)
                        }
                        else -> { // reverts
                            result(listOf(true))
                        }
                    }
                }
            }

            override fun reduce(results: List<Boolean>): Boolean {
                return results.all { it } // all paths must reach a revert for it to be considered a revert
            }

        }).let {
            it.submit(listOf(node))
        }
}
