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
        fun castToPtr(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>, globalsMap: GlobalVariableMap): PointerType<TNum, TOffset>? {
            fun findLowerBound(key: Long): Pair<Long, SbfGlobalVariable>? {
                // globalsMap is sorted
                var lb: Pair<Long, SbfGlobalVariable> ? = null
                for (kv in globalsMap) {
                    if (kv.key <= key) {
                        lb = Pair(kv.key, kv.value)
                    } else {
                        break
                    }
                }
                return lb
            }

            val n = value.toLongOrNull()
            if (n != null) {
                if (n in SBF_HEAP_START until SBF_HEAP_END) {
                    val offset = n - SBF_HEAP_START
                    return sbfTypeFac.toHeapPtr(offset)
                } else {
                    // We check if n can be a valid address assigned to a global variable.
                    val lb = findLowerBound(n)
                    if (lb != null) {
                        val globalVar = lb.second
                        if (globalVar.size == 0L) {
                            // The global might have been inferred by GlobalInferenceAnalysis.
                            // We assume that offset is the start of the global
                            return sbfTypeFac.toGlobalPtr(n, globalVar)
                        }
                        else if (n >= globalVar.address && (n < (globalVar.address + globalVar.size))) {
                            // Note that in case of an array, `offset` might not be the start address of the global.
                            // That is, it's not always true that offset == globalVar.address
                            return sbfTypeFac.toGlobalPtr(n, globalVar)
                        }
                    }
                }
            }
            /// We are here if the number cannot be either the address of a global or a valid address
            /// in the heap.
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
            override fun toString(): String {
                return if (global != null) {
                    if (global is SbfConstantStringGlobalVariable) {
                        "global($global)"
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

    fun join(other: SbfType<TNum, TOffset>): SbfType<TNum, TOffset> {
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
        } else {
            top()
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

        return if (this is NumType && other is NumType) {
            NumType(value.widen(other.value))
        } else if (this is PointerType.Stack && other is PointerType.Stack) {
            PointerType.Stack(offset.widen(other.offset))
        } else if (this is PointerType.Input && other is PointerType.Input) {
            PointerType.Input(offset.widen(other.offset))
        } else if (this is PointerType.Heap && other is PointerType.Heap) {
            PointerType.Heap(offset.widen(other.offset))
        } else if (this is PointerType.Global && other is PointerType.Global) {
            PointerType.Global(offset.widen(other.offset),
                if (global == other.global) {
                    global
                } else {
                    null
                })
        } else {
            top()
        }
    }

    fun lessOrEqual(other: SbfType<TNum, TOffset>): Boolean {
        if (other is Top || this is Bottom) {
            return true
        } else if (this is Top || other is Bottom) {
            return false
        }

        return if (this is NumType && other is NumType) {
            value.lessOrEqual(other.value)
        } else if (this is PointerType.Stack && other is PointerType.Stack) {
            offset.lessOrEqual(other.offset)
        } else if (this is PointerType.Input && other is PointerType.Input) {
            offset.lessOrEqual(other.offset)
        } else if (this is PointerType.Heap && other is PointerType.Heap) {
            offset.lessOrEqual(other.offset)
        } else if (this is PointerType.Global && other is PointerType.Global) {
            offset.lessOrEqual(other.offset)
        } else {
            false
        }
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
            is Top, is Bottom -> null
            is NumType -> this.value.toLongList().let { SbfRegisterType.NumType(toConstantSet(it)) }
            is PointerType -> {
                when (this) {
                    is PointerType.Stack -> this.offset.toLongList().let { SbfRegisterType.PointerType.Stack(toConstantSet(it)) }
                    is PointerType.Input -> this.offset.toLongList().let { SbfRegisterType.PointerType.Input(toConstantSet(it)) }
                    is PointerType.Heap -> this.offset.toLongList().let { SbfRegisterType.PointerType.Heap(toConstantSet(it)) }
                    is PointerType.Global -> this.offset.toLongList().let { SbfRegisterType.PointerType.Global(toConstantSet(it), this.global) }
                }
            }
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
