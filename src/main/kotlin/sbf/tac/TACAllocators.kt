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

package sbf.tac

import sbf.cfg.*
import vc.data.*
import datastructures.stdcollections.*
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags

sealed class TACAllocator {
    /**
     * Emit TAC
     *   1) [ptr]:= [address]  if [useTACAssume] = false
     *   2) assume([ptr] == [address]) if [useTACAssume] = true
     */

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> mkEq(
        ptr: TACSymbol.Var, address: ULong, useTACAssume:Boolean
    ): List<TACCmd.Simple> {
        return if (useTACAssume) {
            val b = vFac.mkFreshBoolVar()
            listOf(
                assign(b, exprBuilder.mkBinRelExp(CondOp.EQ, ptr.asSym() , address.toLong())),
                TACCmd.Simple.AssumeCmd(b, "mkEq")
            )
        } else {
            listOf(assign(ptr, exprBuilder.mkConst(Value.Imm(address)).asSym()))
        }
    }
}

/** Symbolic bump allocator **/
class TACBumpAllocator(val name:String, val start: ULong, val end: ULong): TACAllocator() {
    private var freePtr: ULong = start

    init {
        if (start >= end) {
            throw TACTranslationError("Start address $start must be smaller than end address $end in $name")
        }
    }
    private fun next(size: ULong): ULong {
        val curFreePtr = freePtr
        freePtr += size
        if (freePtr >= end) {
            throw TACTranslationError("$name ran out of memory of ${end - start} bytes!")
        }
        return curFreePtr
    }

    /**
     *   Emit TAC code that ensures [ptr] is equal to the bump pointer.
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> alloc(
        ptr: TACSymbol.Var, size: ULong, useTACAssume:Boolean = false
    ): List<TACCmd.Simple> {
        val nextAddress = next(size)
        return mkEq(ptr, nextAddress, useTACAssume)
    }
}


/** Symbolic fixed-size block allocator **/
class TACFixedSizeBlockAllocator(val name:String, val start:ULong, private val maxBlocks:UShort, val blockSize: ULong): TACAllocator() {
    private var freeBlock: ULong = start
    private var usedBlocks: UShort = 0U

    private fun getBlock(): ULong {
        if (usedBlocks >= maxBlocks) {
            throw TACTranslationError("$name ran out of memory blocks ($maxBlocks)")
        }
        val curBlock = freeBlock
        freeBlock += blockSize
        usedBlocks = (usedBlocks + 1U).toUShort()
        return curBlock
    }

    /**
     *   Emit TAC code that ensures [ptr] is equal to the start address of a new allocated block.
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> alloc(
        ptr: TACSymbol.Var, size: Long, useTACAssume:Boolean = false
    ): List<TACCmd.Simple> {
        if (size <= 0) {
            throw TACTranslationError("$name: expects non-zero, positive sizes but given $size")
        }
        if (size.toULong() > blockSize) {
            throw TACTranslationError("$name expects allocation size $size <= $blockSize")
        }
        val address = getBlock()
        return mkEq(ptr, address, useTACAssume)
    }
}
