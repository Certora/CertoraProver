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
     * Eliminates every unique-successor block of [core]: a block `b` whose only predecessor `a` has `b` as its
     * only successor. Such `a -> b` edges form disjoint chains, and each chain is contracted into its head
     * block: the code of the chain's blocks is concatenated (dropping the jumps between them) and the head
     * takes over the tail's successors. The reachability variables of the eliminated blocks are renamed to
     * their chain head's, and the returned [SinglePostprocessor]s restore them when reading a model.
     *
     * Returns null if there is nothing to merge.
     */
    private fun eliminateAll(core: CoreTACProgram): Pair<CoreTACProgram, List<SinglePostprocessor>>? {
        val g = core.analysisCache.graph

        // an eliminated block -> its unique predecessor, i.e., the edge to contract
        val chainPrev = mutableMapOf<NBId, NBId>()
        // and the reverse map
        val chainSucc = mutableMapOf<NBId, NBId>()
        for (b in g.blockIds) {
            val pred = g.pred(b).singleOrNull()
                ?: continue
            if (pred == b || g.succ(pred).singleOrNull() != b) {
                continue
            }
            chainPrev[b] = pred
            chainSucc[pred] = b
        }
        if (chainPrev.isEmpty()) {
            return null
        }

        fun forwardSeq(b : NBId) = sequence {
            var n : NBId? = b
            while (n != null) {
                yield(n)
                n = chainSucc[n]
            }
        }

        // chain head of an eliminated block.
        val headOf = buildMap {
            for (b in chainSucc.keys) {
                if (b !in chainPrev.keys) {
                    // b is head of a chain.
                    for (n in forwardSeq(b)) {
                        this[n] = b
                    }
                }
            }
        }

        val bg = MutableBlockGraph(core.blockgraph)
        val code = core.code.toMutableMap()
        for (chainHead in headOf.values.toSet()) {
            val chain = forwardSeq(chainHead).toList()
            val tail = chain.last()
            val size = chain.sumOf { code[it]!!.size }
            val cmds = ArrayList<TACCmd.Simple>(size)
            for ((ptr, cmd) in g.lcmdSequence(chain)) {
                // add all cmds except jumps of intermediate blocks.
                if (ptr.block == tail || cmd !is TACCmd.Simple.JumpCmd) {
                    cmds += cmd
                }
            }
            code[chainHead] = cmds
            bg[chainHead] = bg[tail]!!
        }
        chainPrev.keys.forEach {
            code -= it
            bg -= it
        }
        val sigma = chainPrev.keys.associate {
            LeinoWP.genReachabilityVar(it) to LeinoWP.genReachabilityVar(headOf[it]!!)
        }
        val subst = TACVariableSubstitutor(sigma)
        code.forEachEntry { (k, cmds) -> code[k] = cmds.map { subst.map(it) } }

        val ts = core.symbolTable.mergeDecls(
            chainPrev.keys.map { LeinoWP.genReachabilityVar(headOf[it]!!) }.toSet()
        )

        logger.info { "Merged ${chainPrev.size} unique-successor block(s) in one pass" }
        return core.copy(
            code = code,
            blockgraph = bg,
            symbolTable = ts,
        ) to chainPrev.keys.map { SinglePostprocessor(it, headOf[it]!!) }
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
     * Eliminates all unique successor blocks. One pass suffices: contracting `a -> b` hands `b`'s only
     * successor edge to `a` unchanged, so no edge becomes mergeable that wasn't already.
     */
    fun removeUniqueSuccessors(core: CoreTACProgram): Pair<CoreTACProgram, ModelPostprocessor> {
        dump(core, ReportTypes.UNIQUE_SUCCESSOR_REMOVER, DumpTime.PRE_TRANSFORM)
        val (newCore, pps) = eliminateAll(core) ?: (core to listOf())
        dump(newCore, ReportTypes.UNIQUE_SUCCESSOR_REMOVER, DumpTime.POST_TRANSFORM)
        return newCore to ModelPostprocessor(pps)
    }

}
