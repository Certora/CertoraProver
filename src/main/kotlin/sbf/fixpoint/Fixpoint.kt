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
import sbf.cfg.Value
import sbf.domains.AbstractDomain
import sbf.domains.InstructionListener
import sbf.domains.MutableAbstractDomain
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
open class FixpointSolverOperations<T>(
    protected val bot: T,
    protected val top: T
) where T: AbstractDomain<T>  {

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

                    val leftState = inState.pseudoCanonicalize(predAbsVal)
                    val rightState = predAbsVal.pseudoCanonicalize(inState)

                    inState = leftState.join(rightState, block.getLabel(), pred.getLabel())
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
     * Analyze [block] starting with abstract state [inState] and store its effects in [outMap].
     *
     * [deadMap] is used as an optimization to forget from abstract states facts about dead variables.
     **/
    fun analyzeBlock(
        block: SbfBasicBlock,
        inState: T,
        outMap: MutableMap<Label, T>,
        deadMap: Map<Label,LiveRegisters>?
    ) {

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

        val outState = deadVariablePruner(
            inState.analyze(block),
            block.getLabel(),
            deadMap
        )

        if (debugFixpo && debugFixpoPrintStates) {
            sbfLogger.info { "AFTER ${outState}\n" }
        }

        outMap[block.getLabel()] = outState
    }
}

fun<T : AbstractDomain<T>> deadVariablePruner(
    state: T,
    blockLabel: Label,
    deadMap: Map<Label, Iterable<Value.Reg>>?
): T {
    val deadVars = deadMap?.get(blockLabel) ?: return state

    return if (state is MutableAbstractDomain<*>) {
        // We assume that the cost of checking that state is MutableAbstractDomain at run-time
        // is negligible.
        //
        // If the abstract domain is mutable then we prefer to call in-place forget because
        // it's faster (i.e., avoids copies).
        deadVars.forEach { v -> state.forget(v) }
        state
    } else {
        state.forget(deadVars)
    }
}
