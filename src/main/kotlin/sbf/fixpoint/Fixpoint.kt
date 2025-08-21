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
import sbf.cfg.SbfBasicBlock
import sbf.disassembler.Label
import sbf.cfg.SbfCFG
import sbf.disassembler.GlobalVariableMap
import sbf.domains.AbstractDomain
import sbf.domains.InstructionListener
import sbf.domains.MemorySummaries
import sbf.sbfLogger

const val debugFixpo = false
const val debugFixpoPrintStates = false
const val debugFixpoPrintStatements = false

/**
 * Generic API for a fixpoint solver
 **/
interface FixpointSolver<T: AbstractDomain<T>> {
    /**
     * Compute the fixpoint over [cfg] with the abstraction domain `T`.
     * @param inMap output of the fixpoint. It contains the invariants at the entry of each block.
     * @param outMap output of the fixpoint. It contains the invariants at the exit of each block.
     * @param liveMapAtExit is set of live registers at the end of each basic block.
     * @param processor after each WTO component is solved, [processor] is called. If a WTO component is
     * nested, [processor] is called only after the outermost WTO cycle has been solved to ensure each block
     * is processed only once with post-fixpoint facts.
     */
    fun solve(cfg: SbfCFG,
              inMap: MutableMap<Label, T>,
              outMap: MutableMap<Label, T>,
              liveMapAtExit: Map<Label, LiveRegisters>?,
              processor: InstructionListener<T>?)
}

/**
 * Common operations independent of the fixpoint solving strategy
 */
open class FixpointSolverOperations<T: AbstractDomain<T>>(protected val bot: T,
                                                          protected val top: T,
                                                          private val globals: GlobalVariableMap,
                                                          private val memSummaries: MemorySummaries) {

    /** Produce the initial abstract state for a given block **/
    fun getInState(
        block: SbfBasicBlock,
        inMap: MutableMap<Label, T>,
        outMap: Map<Label, T>
    ): T {
        if (block.getPreds().isEmpty()) {
            if (debugFixpo) {
                sbfLogger.info {"Fixpoint: no predecessors"}
            }
            var inState = inMap[block.getLabel()]
            return if (inState != null) {
                inState
            } else {
                inState = top
                inMap[block.getLabel()] = inState
                inState
            }
        } else {
            var inState = bot
            if (debugFixpo) {
                sbfLogger.info {"Fixpoint: started joining the predecessors of ${block.getLabel()}"}
            }
            for (pred in block.getPreds()) {
                val predAbsVal = outMap[pred.getLabel()]
                if (predAbsVal != null) {
                    if (debugFixpo) {
                        sbfLogger.info { "\tStarted merging with predecessor ${pred.getLabel()}\n" }
                    }

                    inState.pseudoCanonicalize(predAbsVal)
                    predAbsVal.pseudoCanonicalize(inState)

                    inState = inState.join(predAbsVal, block.getLabel(), pred.getLabel())
                    if (debugFixpo) {
                        sbfLogger.info { "\tFinished merging with predecessor ${pred.getLabel()}\n" }
                    }
                }
            }
            inMap[block.getLabel()] = inState
            if (debugFixpo) {
                if (debugFixpoPrintStates) {
                    sbfLogger.info { "Fixpoint: joined the predecessors of ${block.getLabel()}\n\t$inState" }
                } else {
                    sbfLogger.info { "Fixpoint: joined the predecessors of ${block.getLabel()}"}
                }
            }
            return inState
        }
    }

    /**
     * Return a pair where the first element is the old abstract state recorded for block and
     * the second element is true if the new abstract state is different from the old one.
     **/
    fun analyzeBlock(
        block: SbfBasicBlock,
        inState: T,
        outMap: MutableMap<Label, T>,
        deadMap: Map<Label,LiveRegisters>?,
        checkChange: Boolean = true
    ): Pair<T?, Boolean> {

        if (debugFixpo) {
            if (debugFixpoPrintStatements) {
                sbfLogger.info { "$block\n" }
            } else {
                sbfLogger.info { "Analysis of block ${block.getLabel()}\n" }
            }
        }

        if (debugFixpo && debugFixpoPrintStates) {
            sbfLogger.info { "BEFORE ${inState}\n" }
        }
        val oldOutState = outMap[block.getLabel()]
        val outState = inState.analyze(block, globals, memSummaries)

        if (deadMap != null) {
            // Remove all registers that are dead at the end of this block.
            // This can improve the precision of the pointer analysis because it can avoid useless unifications.

            val deadVars = deadMap[block.getLabel()]
            if (deadVars != null) {
                for (v in deadVars) {
                    outState.forget(v)
                }
            }
        }

        if (debugFixpo && debugFixpoPrintStates) {
            sbfLogger.info { "AFTER ${outState}\n" }
        }

        val change = ((!checkChange ||
            ((oldOutState == null) || !(outState.lessOrEqual(oldOutState, block.getLabel(), block.getLabel())))))

        outMap[block.getLabel()] = outState

        if (debugFixpo && change) {
            if (debugFixpoPrintStates) {
                sbfLogger.info { "Block ${block.getLabel()} must be re-analyzed\nOLD=$oldOutState\nNEW=$outState\n" }
            } else {
                sbfLogger.info { "Block ${block.getLabel()} must be re-analyzed"}
            }
        }
        return Pair(oldOutState, change)
    }
}
