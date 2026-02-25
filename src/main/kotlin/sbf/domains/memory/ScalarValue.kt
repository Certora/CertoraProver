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

package sbf.domains

import sbf.SolanaConfig
import sbf.cfg.*
import sbf.disassembler.*
import datastructures.stdcollections.*

/**
 * An "abstract" version of [SbfRegisterType], that extends it with top/bot elements and
 * lattice operations such as join and inclusion
 *
 *             ----------------- Top
 *           /                    |
 *          /         -------- Pointer ---------
 *        /         /          |         |      \
 *      Num       Stack     Input    Heap   Global
 *        \            \      |        /       /
 *          -------------- Bottom -------------
 *  where
 *  - Top: any type
 *  - Bottom: type error
 *  - Num: number
 *  - Stack: pointer to the stack (i.e., it contains a stack offset)
 *  - Input: pointer to the input region
 *  - Heap: pointer to the heap (i.e, an integer between [0x300000000, 0x3000077f8])
 *  - Global: pointer to a global variable
 **/
sealed class SbfType<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> {
    // To represent type errors
    object Bottom : SbfType<Nothing, Nothing>() {
        override fun toString(): String {
            return "bottom"
        }
    }

    // Any type
    object Top : SbfType<Nothing, Nothing>() {
        override fun toString(): String {
            return "top"
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> top(): SbfType<TNum, TOffset> = Top as SbfType<TNum, TOffset>
        @Suppress("UNCHECKED_CAST")
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> bottom(): SbfType<TNum, TOffset> = Bottom as SbfType<TNum, TOffset>
        @Suppress("UNCHECKED_CAST")
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> nonStack(): SbfType<TNum, TOffset> = NonStack as SbfType<TNum, TOffset>
    }

    fun isTop() = this === Top
    fun isBottom() = this === Bottom


    // Numerical type
    data class NumType<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(val value: TNum): SbfType<TNum, TOffset>() {
        init {
            check(!value.isBottom()) {"Cannot create a NumType with a bottom value"}
        }

        /**
         *  Cast a number to a pointer only if the number is a valid heap address or the address of a global variable.
         *  We don't try to cast a number to a pointer if the number can be a valid address in the stack or input regions.
         *  In that case, we will return null.
         **/
        fun castToPtr(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>, globals: GlobalVariables): PointerType<TNum, TOffset>? {
            val addr = value.toLongOrNull() ?: return null

            // Heap pointer case
            if (addr in SBF_HEAP_START until SBF_HEAP_END) {
                return sbfTypeFac.toHeapPtr(addr - SBF_HEAP_START)
            }

            // Global variable case
            globals.findGlobalThatContains(addr)?.let { gv ->
                return sbfTypeFac.toGlobalPtr(addr, gv)
            }

            return null
        }

        override fun toString() = if (value.isTop()) { "num" } else { "num($value)" }
    }

    // Pointer type
    sealed class PointerType<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>: SbfType<TNum, TOffset>() {
        abstract val offset : TOffset
        abstract fun withOffset(newOffset: TOffset): PointerType<TNum, TOffset>
        abstract fun withTopOffset(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>): PointerType<TNum, TOffset>

        fun samePointerType(other: PointerType<TNum, TOffset>) = this::class == other::class

        data class Stack<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(override val offset: TOffset): PointerType<TNum, TOffset>() {
            init { check(!offset.isBottom()) {"Cannot create a PointerType with a bottom offset"} }

            override fun toString() = "sp($offset)"
            override fun withOffset(newOffset: TOffset) = Stack<TNum, TOffset>(newOffset)
            override fun withTopOffset(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>) = sbfTypeFac.anyStackPtr()
        }

        data class Input<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(override val offset: TOffset): PointerType<TNum, TOffset>() {
            init { check(!offset.isBottom()) {"Cannot create a PointerType with a bottom offset"} }

            override fun toString() = if (offset.isTop()) { "input" } else { "input($offset)" }
            override fun withOffset(newOffset: TOffset) = Input<TNum, TOffset>(newOffset)
            override fun withTopOffset(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>) = sbfTypeFac.anyInputPtr()
        }

        data class Heap<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(override val offset: TOffset): PointerType<TNum, TOffset>() {
            init { check(!offset.isBottom()) {"Cannot create a PointerType with a bottom offset"} }

            override fun toString() = if (offset.isTop()) { "heap" } else { "heap($offset)" }
            override fun withOffset(newOffset: TOffset) = Heap<TNum, TOffset>(newOffset)
            override fun withTopOffset(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>) = sbfTypeFac.anyHeapPtr()
        }

        // global.address is the start address of the global variable.
        // offset is actually an absolute address between [global.address, global.address+size)
        data class Global<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(override val offset: TOffset, val global: SbfGlobalVariable?): PointerType<TNum, TOffset>() {
            init {
                check(!offset.isBottom()) {"Cannot create a PointerType with a bottom offset"}
            }

            override fun toString(): String {
                return if (global != null) {
                    val str = global.strValue
                    if (str != null) {
                        "global($str)"
                    } else {
                        "global(${global.name}, $offset)"
                    }
                } else {
                    "global($offset)"
                }
            }
            override fun withOffset(newOffset: TOffset) = Global<TNum, TOffset>(newOffset, global)
            override fun withTopOffset(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>) = sbfTypeFac.anyGlobalPtr(global)
        }
    }

    object NonStack : SbfType<Nothing, Nothing>() {
        override fun toString(): String = "nonStack"
    }


    /*fun join(other: SbfType<TNum, TOffset>): SbfType<TNum, TOffset> {
        if (this is Bottom) {
            return other
        } else if (other is Bottom) {
            return this
        } else if (this is Top || other is Top) {
            return top()
        }

        return if (this is NumType && other is NumType) {
            NumType(value.join(other.value))
        } else if (this is PointerType.Stack && other is PointerType.Stack) {
            PointerType.Stack(offset.join(other.offset))
        } else if (this is PointerType.Input && other is PointerType.Input) {
            PointerType.Input(offset.join(other.offset))
        } else if (this is PointerType.Heap && other is PointerType.Heap) {
            PointerType.Heap(offset.join(other.offset))
        } else if (this is PointerType.Global && other is PointerType.Global) {
            PointerType.Global(offset.join(other.offset),
                if (global == other.global) {
                    global
                } else {
                    null
                })
        } else if (this is PointerType.Stack || other is PointerType.Stack) {
            numJoinStack++
            top()
        } else {
            top()
        }
    }*/

    private fun joinDifferentTypes(other: SbfType<TNum, TOffset>): SbfType<TNum, TOffset> {
        check(this::class != other::class)

        // Helper to check if type is a non-stack pointer
        fun isNonStackPtr(t: SbfType<TNum, TOffset>) =
            t is PointerType.Input || t is PointerType.Global || t is PointerType.Heap

        return when {
            // Num with any non-stack pointer -> NonStack
            (this is NumType && isNonStackPtr(other)) || (other is NumType && isNonStackPtr(this)) -> nonStack()

            // Two different non-stack pointers -> NonStack
            isNonStackPtr(this) && isNonStackPtr(other) -> nonStack()

            // Num with NonStack -> NonStack
            (this is NumType && other is NonStack) || (other is NumType && this is NonStack) -> nonStack()

            // Non-stack pointer with NonStack -> NonStack
            (isNonStackPtr(this) && other is NonStack) || (isNonStackPtr(other) && this is NonStack) -> nonStack()

            // Everything else (involves Stack) -> Top
            else -> top()
        }
    }

    fun join(other: SbfType<TNum, TOffset>): SbfType<TNum, TOffset> {
        if (this is Bottom) {
            return other
        } else if (other is Bottom) {
            return this
        } else if (this is Top || other is Top) {
            return top()
        }


        // both operands have same type
        return if (this::class == other::class) {
            when (this) {
                is NumType -> NumType(value.join((other as NumType).value))
                is PointerType.Stack -> PointerType.Stack(offset.join((other as PointerType.Stack).offset))
                is PointerType.Input -> PointerType.Input(offset.join((other as PointerType.Input).offset))
                is PointerType.Heap -> PointerType.Heap(offset.join((other as PointerType.Heap).offset))
                is PointerType.Global -> PointerType.Global(
                    offset.join((other as PointerType.Global).offset),
                    if (global == other.global) { global } else { null }
                )
                is NonStack -> this
                else -> top()
            }
        } else {
            joinDifferentTypes(other)
        }
    }

    fun widen(other: SbfType<TNum, TOffset>): SbfType<TNum, TOffset> {
        if (this is Bottom) {
            return other
        } else if (other is Bottom) {
            return this
        } else if (this is Top || other is Top) {
            return top()
        }

        return if (this::class == other::class) {
            when (this) {
                is NumType -> NumType(value.widen((other as NumType).value))
                is PointerType.Stack -> PointerType.Stack(offset.widen((other as PointerType.Stack).offset))
                is PointerType.Input -> PointerType.Input(offset.widen((other as PointerType.Input).offset))
                is PointerType.Heap -> PointerType.Heap(offset.widen((other as PointerType.Heap).offset))
                is PointerType.Global -> PointerType.Global(
                    offset.widen((other as PointerType.Global).offset),
                    if (global == other.global) { global } else { null }
                )
                is NonStack -> this
                else -> top()
            }
        } else {
            joinDifferentTypes(other)
        }
    }

    fun lessOrEqual(other: SbfType<TNum, TOffset>): Boolean {
        if (other is Top || this is Bottom) {
            return true
        } else if (this is Top || other is Bottom) {
            return false
        }

        // Same type - check values/offsets
        if (this::class == other::class) {
            return when (this) {
                is NumType -> value.lessOrEqual((other as NumType).value)
                is PointerType.Stack -> offset.lessOrEqual((other as PointerType.Stack).offset)
                is PointerType.Input -> offset.lessOrEqual((other as PointerType.Input).offset)
                is PointerType.Heap -> offset.lessOrEqual((other as PointerType.Heap).offset)
                is PointerType.Global -> offset.lessOrEqual((other as PointerType.Global).offset)
                is NonStack -> true
                else -> false
            }
        }

        if (other is NonStack) {
            return this is NumType ||
                this is PointerType.Input ||
                this is PointerType.Global ||
                this is PointerType.Heap
        }

        // Different concrete types are incomparable
        return false
    }

    fun concretize(): SbfRegisterType? {
        fun toConstantSet(xs: List<Long>): ConstantSet {
            val maxSize = SolanaConfig.ScalarMaxVals.get().toULong()
            return if (xs.isEmpty()) {
                ConstantSet.mkTop(maxSize)
            } else{
                ConstantSet(xs.map{Constant(it)}.toSet(), maxSize)
            }
        }

        return when (this) {
            is Top,
            is Bottom -> null
            is NumType -> this.value.toLongList().let { SbfRegisterType.NumType(toConstantSet(it)) }
            is PointerType -> {
                when (this) {
                    is PointerType.Stack -> this.offset.toLongList().let { SbfRegisterType.PointerType.Stack(toConstantSet(it)) }
                    is PointerType.Input -> this.offset.toLongList().let { SbfRegisterType.PointerType.Input(toConstantSet(it)) }
                    is PointerType.Heap -> this.offset.toLongList().let { SbfRegisterType.PointerType.Heap(toConstantSet(it)) }
                    is PointerType.Global -> this.offset.toLongList().let { SbfRegisterType.PointerType.Global(toConstantSet(it), this.global) }
                }
            }
            is NonStack -> null
        }
    }
}


/**
 * This class wraps [SbfType] inside [StackEnvironmentValue] which is an interface.
 * It represents the value stored in a register or stack offset.
 **/
class ScalarValue<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(private val type: SbfType<TNum, TOffset>)
    : StackEnvironmentValue<ScalarValue<TNum, TOffset>> {

    companion object {
        fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> mkBottom() = ScalarValue(SbfType.bottom<TNum, TOffset>())
    }

    fun type() = type
    override fun isBottom() = type.isBottom()
    override fun isTop() = type.isTop()
    override fun mkTop() = ScalarValue(SbfType.top<TNum, TOffset>())
    override fun join(other: ScalarValue<TNum, TOffset>) = ScalarValue(type.join(other.type))
    override fun widen(other: ScalarValue<TNum, TOffset>)= ScalarValue(type.widen(other.type))
    override fun lessOrEqual(other: ScalarValue<TNum, TOffset>) = type.lessOrEqual(other.type)
    override fun toString() = type.toString()
}
