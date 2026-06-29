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

package sbf.analysis

import datastructures.stdcollections.*
import sbf.cfg.*
import sbf.domains.*

/**
 *  Finds all register uses where the value is definitely a pointer. This is used to
 *  optimize the TAC encoding of math operations: we can assume that pointer addition
 *  does not overflow.
 *
 *  [analysis] can be any [IAnalysis] whose domain implements [ScalarValueProvider].
 */
class IsPointerAnalysis<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, D>(
    analysis: IAnalysis<D>?
) where D : AbstractDomain<D>, D : ScalarValueProvider<TNum, TOffset> {

    private val pointerOps = mutableSetOf<LocatedSbfInstruction>()

    fun isPointerOp(locInst: LocatedSbfInstruction) = locInst in pointerOps

    init {
        analysis?.getCFG()?.getBlocks()?.forEachEntry { (label, block) ->
            analysis.getPre(label)?.analyze(
                block,
                object : InstructionListener<D> {
                    override fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: D) {
                        if (locInst.inst.readRegisters.any { pre.isSurelyPointer(locInst.inst, it) }) {
                            pointerOps += locInst
                        }
                    }
                    override fun instructionEventAfter(locInst: LocatedSbfInstruction, post: D) {}
                    override fun instructionEvent(locInst: LocatedSbfInstruction, pre: D, post: D) {}
                }
            )
        }
    }
}
