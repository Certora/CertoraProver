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

package verifier

import analysis.PathCondition
import config.ReportTypes
import datastructures.stdcollections.*
import log.*
import tac.DumpTime
import tac.NBId
import vc.data.*
import vc.gen.LeinoWP

private val logger = Logger(LoggerTypes.UNIQUE_SUCCESSOR_REMOVER)

/**
 * Utility to eliminate unique successor blocks: blocks that have a single predecessor and also are the single successor
 * of their predecessor. In this case their edge has no path condition and the blocks can be merged easily. We call the
 * predecessor block `a` and the successor block `b` and want to eliminate `b`.
 * Additionally, [ModelPostprocessor] can be used to easily augment a model (counter example) with the state variables
 * that correspond to the eliminated blocks. If applied, the model should be as if no elimination was done in the first
 * place.
 */
object UniqueSuccessorRemover {

    /**
     * Adds state variables for a single block based on another block to a model.
     */
    class SinglePostprocessor(
        val old: NBId,
        val new: NBId
    ) {
        operator fun <T> invoke(m: MutableMap<TACSymbol.Var, T>) {
            m[LeinoWP.genOkIdentOf(new)]?.let { m[LeinoWP.genOkIdentOf(old)] = it }
            (m[LeinoWP.genReachabilityVar(new)]
                ?: error("failed to find reach var for ${LeinoWP.genReachabilityVar(new)}"))
                .let { m[LeinoWP.genReachabilityVar(old)] = it }
        }
    }

    /**
     * Augments a given model using the [SinglePostprocessor] objects to restore state variables that correspond to
     * eliminated blocks.
     */
    class ModelPostprocessor(private val pps: List<SinglePostprocessor>) {
        operator fun <T> invoke(m: Map<TACSymbol.Var, T>): Map<TACSymbol.Var, T> =
            m.toMutableMap().also { mm -> pps.reversed().forEach { it(mm) } }
    }

    /**
     * Prepares the commands of the predecessor block `a` for merging by removing trailing jumps to `b`.
     */
    private fun cleanCommands(bid: NBId, ac: List<TACCmd.Simple>): List<TACCmd.Simple> {
        if (ac.isEmpty()) {
            return ac
        }
        val l = ac.last()
        if (l is TACCmd.Simple.JumpCmd && l.dst == bid) {
            return ac.dropLast(1)
        }
        if (l is TACCmd.Simple.JumpiCmd && l.dst == bid) {
            return ac.dropLast(1) + listOf(TACCmd.Simple.AssumeCmd(l.cond, "cleanCommands"))
        }
        return ac
    }

    /**
     * Eliminate a single block [bid] from [core]. First identifies the predecessor block `aid` and checks whether all
     * necessary conditions apply. Then merges the two blocks by
     * - setting `succ(aid) := succ(bid)`
     * - merging the commands of the two blocks into `aid`
     * - making sure that the reachability variable of `aid` is properly defined
     * - eliminating the reachability variable of [bid] using the one of `aid`
     * - build a new [CoreTACProgram] from these updated data
     */
    private fun eliminate(
        core: CoreTACProgram,
        bid: NBId
    ): Pair<CoreTACProgram, SinglePostprocessor>? {
        val graph = core.analysisCache.graph

        val aid = graph.pred(bid).singleOrNull() ?: return null
        if (graph.succ(aid).singleOrNull() != bid) {
            return null
        }
        if (graph.pathConditionsOf(aid)[bid] != PathCondition.TRUE) {
            return null
        }

        logger.info { "Merging ${bid} into ${aid} with PC ${graph.pathConditionsOf(aid)[bid]}" }

        val bg = MutableBlockGraph(core.blockgraph)
        bg[aid] = bg[bid]!!
        bg.remove(bid)

        val code = core.code.toMutableMap()
        code[bid]?.let {
            code[aid] = (cleanCommands(bid, code[aid] ?: listOf())) + it
        }
        code.remove(bid)

        var ts = core.symbolTable
        ts = ts.mergeDecls(setOf(LeinoWP.genReachabilityVar(aid)))

        val subst =
            TACVariableSubstitutor(mapOf(LeinoWP.genReachabilityVar(bid) to LeinoWP.genReachabilityVar(aid)))
        code.forEachEntry { (bid, cmds) -> code[bid] = cmds.map { subst.map(it) } }

        return core.copy(
            code = code,
            blockgraph = bg,
            symbolTable = ts,
        ) to SinglePostprocessor(bid, aid)
    }

    /**
     * Dump artifacts for this simplication step.
     */
    private fun dump(ctp: CoreTACProgram, rpt: ReportTypes, dt: DumpTime) {
        if (ArtifactManagerFactory.isEnabled()) {
            ArtifactManagerFactory().let {
                it.dumpMandatoryCodeArtifacts(ctp, rpt, StaticArtifactLocation.Outputs, dt)
                it.dumpCodeArtifacts(ctp, rpt, dt)
            }
        }
    }

    /**
     * Eliminate unique successor blocks in a fixed-point loop.
     */
    fun removeUniqueSuccessors(core: CoreTACProgram): Pair<CoreTACProgram, ModelPostprocessor> {
        dump(core, ReportTypes.UNIQUE_SUCCESSOR_REMOVER, DumpTime.PRE_TRANSFORM)

        val pps: MutableList<SinglePostprocessor> = mutableListOf()
        var curCore = core
        var changed = true
        while (changed) {
            changed = false
            for (b in curCore.analysisCache.graph.blocks) {
                eliminate(curCore, b.id)?.let {
                    curCore = it.first
                    pps.add(it.second)
                    changed = true
                }
                if (changed) break
            }
        }

        dump(curCore, ReportTypes.UNIQUE_SUCCESSOR_REMOVER, DumpTime.POST_TRANSFORM)
        return curCore to ModelPostprocessor(pps)
    }

}
