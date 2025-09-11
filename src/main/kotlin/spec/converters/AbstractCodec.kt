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

package spec.converters

import analysis.CommandWithRequiredDecls
import spec.SafeMathCodeGen
import tac.Tag
import vc.data.TACCmd
import vc.data.TACKeyword
import vc.data.TACSymbol
import vc.data.asTACSymbol
import java.math.BigInteger

/**
 * Common super class for [LowLevelDecoder] and [LowLevelEncoder] implementations, which includes some common functionality
 * between those two cases.
 */
abstract class AbstractCodec : SafeMathCodeGen {

    companion object {
        fun initializePointer(
            offset: TACSymbol
        ) : LowLevelSetup<BufferPointer> {
            return when(offset) {
                is TACSymbol.Const -> {
                    val pointer = TACKeyword.TMP(Tag.Bit256, "!offsetPointer").toUnique("!")
                    BufferPointer.Constant(offset.value, pointer) to CommandWithRequiredDecls(
                        datastructures.stdcollections.listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = pointer,
                                rhs = offset.asSym()
                            )
                        ),
                        pointer
                    )
                }
                is TACSymbol.Var -> {
                    BufferPointer.Symbol(offset, BigInteger.ZERO) to CommandWithRequiredDecls(datastructures.stdcollections.listOf(TACCmd.Simple.NopCmd))
                }
            }
        }
    }


    abstract val currPointer: BufferPointer
    abstract val buffer: TACSymbol.Var
    abstract val scalars: Map<BigInteger, TACSymbol.Var>
    abstract val relativeScalars: Map<BigInteger, TACSymbol.Var>

    protected fun getScalarLoc(): TACSymbol.Var? {
        return when(val cp = this.currPointer) {
            is BufferPointer.Constant -> scalars[cp.x]
            is BufferPointer.Symbol -> cp.relativeOffset?.let(relativeScalars::get)
        }
    }


    protected infix operator fun BufferPointer.plus(amt: BigInteger) : Pair<BufferPointer, CommandWithRequiredDecls<TACCmd.Spec>> {
        val result = TACKeyword.TMP(Tag.Bit256, "!PtrAdvanced").toUnique("!")
        val newPointer = when(this) {
            is BufferPointer.Constant -> {
                BufferPointer.Constant(
                    x = amt + this.x,
                    outputPointer = result
                )
            }
            is BufferPointer.Symbol -> {
                BufferPointer.Symbol(result, relativeOffset = this.relativeOffset?.plus(amt))
            }
        }
        return newPointer to safeAdd(
            result, op1 = this.outputPointer, op2 = amt.asTACSymbol()
        )
    }

    protected infix operator fun BufferPointer.plus(amt: TACSymbol.Var) : Pair<BufferPointer, CommandWithRequiredDecls<TACCmd.Spec>> {
        val result = TACKeyword.TMP(Tag.Bit256, "!PtrAdvanced").toUnique("!")
        val newPointer = BufferPointer.Symbol(result, null)
        return newPointer to safeAdd(
            result, this.outputPointer, amt
        )
    }
}
