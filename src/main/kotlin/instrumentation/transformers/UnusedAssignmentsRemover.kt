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

package instrumentation.transformers

import algorithms.SCCGraphInfo
import algorithms.TopologicalOrderException
import algorithms.getReachable
import algorithms.topologicalOrder
import analysis.CmdPointer
import analysis.worklist.volatileDagDataFlow
import com.certora.collect.*
import datastructures.stdcollections.*
import instrumentation.transformers.AssignmentInliner.Companion.inlineAssignments
import log.*
import tac.NBId
import utils.MutableDiffSet
import utils.foldFirstOrNull
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACSymbol
import vc.data.freeVars
import java.math.BigInteger
import kotlin.math.max

private val logger = Logger(LoggerTypes.UNUSED_ASSIGNMENTS_OPT)


/**
 * Removes assignments that are never read.
 *
 * Will only remove assignments that are [isErasable], and others are also considered cone-of-influence (cif) sinks.
 * [expensive] makes it run a more expensive and exact algorithm. The expensive version does not work for graphs
 * with cycles, so in such a case the cheap algorithm is run instead.
 *
 * Also removes tautological assumes (assume true, assumeNot false, assumeExp true)
 */
private class AssignmentRemover(
    private val code: CoreTACProgram,
    private val expensive: Boolean,
    private val isErasable: (TACCmd.Simple.AssigningCmd) -> Boolean,
    private val isTypechecked: Boolean = true
) {
    private val g = code.analysisCache.graph
    private val patcher = code.toPatchingProgram()
    private var deleted = 0

    private fun delete(ptr: CmdPointer) {
        deleted++
        logger.trace { "Deleting unused assignment: ${g.toCommand(ptr)} @ $ptr" }
        patcher.delete(ptr)
    }

    /**
     * Takes the set of vars that are known to be read by commands in successors of the block,
     * removes unused assignments in the block, and changes the set, so that eventually it holds
     * the vars that are known to be read in the block or in its successors.
     */
    private fun processBlock(b: NBId, neededVars: MutableSet<TACSymbol.Var>) {
        val cmds = g.elab(b).commands
        for ((ptr, cmd) in cmds.reversed()) {
            if (cmd is TACCmd.Simple.LogCmd) {
                continue
            }
            val lhs = cmd.getLhs()
            if (cmd is TACCmd.Simple.AssigningCmd && lhs !in neededVars && isErasable(cmd)) {
                delete(ptr)
            } else {
                lhs?.let(neededVars::remove)
                neededVars += cmd.getFreeVarsOfRhsExtended()
            }
        }
    }

    /**
     * Goes backwards in the DAG, calculating for each block which variables are known to be read after
     * the block. This information lets us remove all assignments that are never read.
     *
     * These "live variable" sets should be pretty small, because variables are in them only between their
     * assignment and usage location. so if a variable is assigned and then used after a couple of blocks, it
     * will only appear in two blocks.
     *
     * The usage of [volatileDagDataFlow] helps reduce the memory cost by dropping these sets once they
     * are not needed anymore, i.e, once all the predecessors of a block are processed, we can drop the block's set.
     */
    private fun expensive() {
        var maxLiveVariableSetSize = 0
        volatileDagDataFlow<NBId, TreapSet<TACSymbol.Var>>(g.blockSucc) { b, neededVarsSets ->
            val needed = neededVarsSets
                .foldFirstOrNull { set1, set2 -> set1 union set2 }
                .orEmpty()
                .builder()
            processBlock(b, needed)
            maxLiveVariableSetSize = max(needed.size, maxLiveVariableSetSize)
            needed.build()
        }
        logger.info {
            "Max live variable set size = $maxLiveVariableSetSize"
        }
    }


    /**
     * A cheaper version that also works for non-dags. Maintains one set of `neededVars` which is accumulated as
     * we go backwards in the graph.
     */
    private fun cheap() {
        val sccGraph = SCCGraphInfo(g.blockSucc)
        val neededVars = mutableSetOf<TACSymbol.Var>()

        topologicalOrder(sccGraph.sccGraph) // sinks first
            .map { sccGraph.sccs[it]!! }
            .forEach { blocks ->
                if (sccGraph.isInLoop(blocks.first<NBId>())) {
                    // The current SCC has cycles, so we overapproximate which variables are needed.
                    neededVars += simpleCIF(blocks, neededVars)
                    for (b in blocks) {
                        // We still go through each block backwards, to do actual erasing, and to possibly detect
                        // assignments that are overwritten, and thus are unneeded, even if their lhs is.
                        // We use a temporary set on top of `neededVars` to keep `neededVars` itself unchanged.
                        val diffSet = MutableDiffSet(neededVars)
                        processBlock(b, diffSet)
                    }
                } else {
                    // only one block in the SCC, and no loop.
                    // process the block as usual, but we can't eventually remove variables from `neededVars`,
                    // only add them. That's because they may still be needed in other branches of the program.
                    val diffSet = MutableDiffSet(neededVars)
                    processBlock(blocks.single(), diffSet)
                    neededVars += diffSet.adds
                    return@forEach
                }
            }
    }


    /**
     * Starting with the all the variables that are being read in successor blocks, we gather all other variables
     * that they need when assigned, and do so recursively. This ignores assignment order, because its difficult
     * to consider it when there are loops.
     */
    private fun simpleCIF(blocks: Set<NBId>, globalNeededVars: Set<TACSymbol.Var>): Set<TACSymbol.Var> {
        val assignSites = mutableMapOf<TACSymbol.Var, MutableList<CmdPointer>>()
        val neededVars = g.lcmdSequence(blocks).flatMap { (ptr, cmd) ->
            when {
                cmd is TACCmd.Simple.LogCmd ->
                    emptyList()

                cmd is TACCmd.Simple.AssigningCmd && isErasable(cmd) -> {
                    assignSites.getOrPut(cmd.lhs) { mutableListOf() } += ptr
                    cmd.lhs.takeIf { it in globalNeededVars }?.let(::listOf).orEmpty()
                }

                else ->
                    cmd.freeVars()
            }
        }.toSet()

        return getReachable(neededVars) { lhs ->
            assignSites[lhs]?.flatMap {
                g.elab(it).cmd.getFreeVarsOfRhs()
            } ?: emptyList()
        }
    }


    private fun removeAssumeTrue() {
        g.commands.forEach { (ptr, cmd) ->
            if (cmd is TACCmd.Simple.Assume && cmd.condExpr.evalAsConst() == BigInteger.ONE) {
                logger.trace { "Deleting trivial assume: $cmd @ $ptr" }
                patcher.delete(ptr)
            }
        }
    }


    fun go(): CoreTACProgram {
        if (expensive) {
            try {
                logger.info { "Starting the expensive version on ${code.name}" }
                expensive()
            } catch (_: TopologicalOrderException) {
                logger.info { "Graph has loops, so starting the cheap version on ${code.name}" }
                cheap()
            }
        } else {
            logger.info { "Starting the cheap version on ${code.name}" }
            cheap()
        }
        removeAssumeTrue()
        val result =
            if (!isTypechecked) {
                val (newCode, blocks) = patcher.toCode(TACCmd.Simple.NopCmd)
                code.copy(
                    code = newCode,
                    blockgraph = blocks,
                    check = false
                )
            } else {
                patcher.toCode(code)
            }

        logger.info { "Done on ${code.name}, removed $deleted assignments" }
        return result
    }

}


/** See [AssignmentRemover] docs */
fun removeUnusedAssignments(
    code: CoreTACProgram,
    expensive: Boolean,
    isErasable: (TACCmd.Simple.AssigningCmd) -> Boolean,
    isTypechecked: Boolean
) =
    AssignmentRemover(code, expensive, isErasable, isTypechecked).go()

fun removeUnusedAssignments(
    code: CoreTACProgram,
    expensive: Boolean,
    isTypeChecked: Boolean = true
) =
    AssignmentRemover(code, expensive, FilteringFunctions.default(code, bmcAware = true)::isErasable, isTypeChecked).go()


/** transforms via [inlineAssignments] and then via [removeUnusedAssignments] */
fun optimizeAssignments(
    code: CoreTACProgram,
    filtering: FilteringFunctions,
    strict : Boolean = false,
) =
    removeUnusedAssignments(code, expensive = false, filtering::isErasable, isTypechecked = true)
        .let { inlineAssignments(it, filtering, strict) }
        .let { removeUnusedAssignments(it, expensive = true, filtering::isErasable, isTypechecked = true) }
