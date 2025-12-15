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

package sbf.fixpoint

import sbf.analysis.LiveRegisters
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.AbstractDomain
import sbf.domains.MemorySummaries
import sbf.sbfLogger
import datastructures.stdcollections.*
import sbf.domains.InstructionListener

/**
 * This solver supports posets (partial ordered sets) with infinite ascending chains.
 * The processing order of the nodes follows the Weak Topological Ordering of the CFG.
 * The WTO also identifies the nodes where widening should be applied (WTO cycle heads).
 */

data class WtoBasedFixpointOptions(
    // Number of fixpoint ascending iterations until widening is called
    val wideningDelay: UInt,
    // Number of descending iterations after post-fixpoint has been computed
    val descendingIterations: UInt
    )

class WtoBasedFixpointSolver<T: AbstractDomain<T>>(
    bot: T,
    top: T,
    val options: WtoBasedFixpointOptions,
    val globals: GlobalVariables,
    val memSummaries: MemorySummaries):
        FixpointSolver<T>,
        FixpointSolverOperations<T>(bot, top, globals, memSummaries){

    init {
        check(bot.isBottom()) {"bot argument is not bottom!"}
        check(top.isTop()) {"top argument is not top!"}
    }

    private fun solveWtoVertex(node: WtoVertex, wto: Wto,
                               inMap: MutableMap<Label, T>,
                               outMap: MutableMap<Label, T>,
                               deadMap: Map<Label, LiveRegisters>?) {
        if (debugFixpo) {
            sbfLogger.info { "Analyzed ${node.label}" }
        }
        val label = node.label
        val b = wto.cfg.getBlock(label)
        check(b != null) {
            "Cannot find basic block for $label in CFG ${wto.cfg.getName()}"
        }

        // Join all predecessors
        val inState = getInState(b, inMap, outMap)
        // Compute transfer functions for the whole basic block. outMap is updated
        analyzeBlock(b, inState, outMap, deadMap)
    }

    private fun processWtoVertex(node: WtoVertex,
                                 wto: Wto,
                                 inMap: MutableMap<Label, T>,
                                 processor: InstructionListener<T>) {
        val label = node.label
        val b = wto.cfg.getBlock(label)
        check(b != null) {
            "Cannot find basic block for $label in CFG ${wto.cfg.getName()}"
        }
        val inState = inMap[label]
        check(inState != null) {
            "Cannot find abstract state for $label in CFG ${wto.cfg.getName()}"
        }
        inState.analyze(b, globals, memSummaries, processor)
    }

    private fun extrapolate(b: SbfBasicBlock, numAscendingIterations: UInt,
                            oldState: T, newState: T): T {
        return if (numAscendingIterations < options.wideningDelay) {
            oldState.pseudoCanonicalize(newState)
            oldState.join(newState, b.getLabel(), b.getLabel())
        } else {
            if (debugFixpo) {
                sbfLogger.info { "Widening at block ${b.getLabel()} (iter=$numAscendingIterations)" }
            }
            oldState.widen(newState, b.getLabel())
        }
    }

    private fun refine(@Suppress("UNUSED_PARAMETER") b:SbfBasicBlock,
                       @Suppress("UNUSED_PARAMETER") oldState: T,
                       newState: T): T {
        /** AbstractDomain does not provide a *narrowing* operator.
         *  The narrowing operator would ensure termination without the need of a fixed limit of descending iterations.
         *  However, narrowing operators are not very common in abstract domains.
         *  As a result, a typical solution is to use the meet operator with a fixed limit of descending iterations.
         *  Currently, AbstractDomain does not provide a *meet* operator either, but it is always sound to return either
         *  oldState or newState.
         **/
        return newState
    }

    private fun solveWtoCycle(node: WtoCycle, wto: Wto,
                              inMap: MutableMap<Label, T>,
                              outMap: MutableMap<Label, T>,
                              deadMap: Map<Label, LiveRegisters>?) {
        if (debugFixpo) {
            sbfLogger.info { "Starting analysis (ascending phase) of loop ${node.head().label}" }
        }
        val cfg = wto.cfg
        val label = node.head().label
        val b = cfg.getBlock(label)
        check(b != null) {"cannot find block for $label"}
        val entry = cfg.getEntry()
        var inState = bot
        if (entry.getLabel() == b.getLabel()) {
            inState = inMap.getOrDefault(b.getLabel(), top)
        } else {
            for (pred in b.getPreds()) {
                val predAbsVal = outMap[pred.getLabel()]
                if (predAbsVal != null) {
                    // We join all the predecessors except those that are back-edges
                    // because the back-edges haven't been visited yet.
                    if (!wto.nesting(pred.getLabel()).contains(wto.nesting(label))) {
                        inState.pseudoCanonicalize(predAbsVal)
                        predAbsVal.pseudoCanonicalize(inState)
                        inState = inState.join(predAbsVal, b.getLabel(), pred.getLabel())
                    }
                }
            }
        }

        var ascendingIterations = 0U
        while (true) {
            inMap[b.getLabel()] = inState
            analyzeBlock(b, inState, outMap, deadMap)
            for (c in node.getComponents()) {
                if (c !is WtoVertex || c.label != b.getLabel()) { // don't analyze twice the head
                    solveWtoComponent(c, wto, inMap, outMap, deadMap)
                }
            }

            // Join all predecessors
            val newInState = getInState(b, inMap, outMap)
            if (newInState.lessOrEqual(inState, b.getLabel(), b.getLabel())) {
                // fixpoint reached
                if (debugFixpo) {
                    sbfLogger.info { "OLD=$inState NEW=$newInState" }
                }
                inMap[b.getLabel()] = newInState
                inState = newInState
                break
            } else {
                inState = extrapolate(b, ascendingIterations, inState, newInState)
            }
            ascendingIterations++
        }
        if (debugFixpo) {
            sbfLogger.info { "Finished ascending phase of loop ${node.head().label} in " +
                             "${ascendingIterations+1U} iterations" }
        }

        var descendingIterations = 0U
        if (debugFixpo) {
            if (options.descendingIterations > 0U) {
                sbfLogger.info { "Starting analysis (descending phase) of loop ${node.head().label}" }
            }
        }
        while (descendingIterations < options.descendingIterations) {
            inMap[b.getLabel()] = inState
            analyzeBlock(b, inState, outMap, deadMap)
            for (c in node.getComponents()) {
                if (c !is WtoVertex || c.label != b.getLabel()) { // don't analyze twice the head
                    solveWtoComponent(c, wto, inMap, outMap, deadMap)
                }
            }
            // Join all predecessors
            val newInState = getInState(b, inMap, outMap)
            if (inState.lessOrEqual(newInState, b.getLabel(), b.getLabel())) {
                // cannot refine more
                break
            } else {
                inState = refine(b, inState, newInState)
                inMap[b.getLabel()] = inState
            }
            descendingIterations++
        }
        if (debugFixpo) {
            if (options.descendingIterations > 0U) {
                sbfLogger.info { "Finished analysis (descending phase) of loop ${node.head().label}" }
            }
        }
    }

    private fun processWtoCycle(node: WtoCycle,
                                wto: Wto,
                                inMap: MutableMap<Label, T>,
                                processor: InstructionListener<T>) {
        val cfg = wto.cfg
        val label = node.head().label
        val b = cfg.getBlock(label)
        check(b != null) {
            "Cannot find block for $label in CFG ${wto.cfg.getName()}"
        }
        val inState = inMap[label]
        check(inState != null) {
            "Cannot find abstract state for $label in CFG ${wto.cfg.getName()}"
        }
        // process the head
        inState.analyze(b, globals, memSummaries, processor)
        // process recursively the rest of the component
        for (c in node.getComponents()) {
            if (c !is WtoVertex || c.label != b.getLabel()) { // don't process twice the head
                processWtoComponent(c, wto, inMap, processor)
            }
        }
    }

    private fun solveWtoComponent(c: WtoComponent, wto: Wto,
                                  inMap: MutableMap<Label, T>,
                                  outMap: MutableMap<Label, T>,
                                  deadMap: Map<Label, LiveRegisters>?) {
        when (c) {
            is WtoVertex -> solveWtoVertex(c, wto, inMap, outMap, deadMap)
            is WtoCycle -> solveWtoCycle(c, wto, inMap, outMap, deadMap)
        }
    }

    private fun processWtoComponent(c: WtoComponent,
                                    wto: Wto,
                                    inMap: MutableMap<Label, T>,
                                    processor: InstructionListener<T>) {
        when (c) {
            is WtoVertex -> processWtoVertex(c, wto, inMap, processor)
            is WtoCycle -> processWtoCycle(c, wto, inMap, processor)
        }
    }

    override fun solve(cfg: SbfCFG,
                       inMap: MutableMap<Label, T>,
                       outMap: MutableMap<Label, T>,
                       liveMapAtExit: Map<Label, LiveRegisters>?,
                       processor: InstructionListener<T>?) {

        val entry = cfg.getEntry()

        if (debugFixpo) {
            sbfLogger.info {"### Started fixpoint computation ###\n"}
            val init = inMap[entry.getLabel()]
            if (init != null) {
                sbfLogger.info {"Initial abstract state=$init\n"}
            }
        }

        val start = System.currentTimeMillis()
        val wto = Wto(cfg)
        val end = System.currentTimeMillis()
        if (debugFixpo) {
            sbfLogger.info { "Computed WTO in ${(end - start) / 1000}s" }
            sbfLogger.info { "WTO=$wto" }
        }

        val deadMap = if (liveMapAtExit != null) {
            val allRegs = SbfRegister.values().filter {it != SbfRegister.R10_STACK_POINTER}.map{ Value.Reg(it)}.toSet()
            val deadMap = mutableMapOf<Label, LiveRegisters>()
            for ((b,live) in liveMapAtExit) {
                deadMap[b] = allRegs - live
            }
            deadMap
        } else {
            null
        }

        for (c in wto.getComponents()) {
            solveWtoComponent(c, wto, inMap, outMap, deadMap)
            if (processor != null) {
                processWtoComponent(c, wto, inMap, processor)
            }
        }

        if (debugFixpo) {
            sbfLogger.info {"### Finished fixpoint computation ###\n"}
        }
    }
}

