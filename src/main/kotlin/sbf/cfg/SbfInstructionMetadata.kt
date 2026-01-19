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

package sbf.cfg

import utils.*
import datastructures.stdcollections.*
import sbf.dwarf.DWARFCfgEdgeLabel

class MetaData private constructor(private val meta: Map<MetaKey<*>, Any>) {
    constructor(): this(mutableMapOf())

    fun<T> getVal(key: MetaKey<T>): T? = meta[key]?.uncheckedAs()

    internal val entries get() = meta.entries

    operator fun<T> plus(entry: Pair<MetaKey<T>, T>): MetaData {
        return MetaData(meta.plus(entry.uncheckedAs<Pair<MetaKey<*>, Any>>()))
    }

    operator fun<T> minus(key: MetaKey<T>): MetaData {
        return MetaData(meta.minus(key))
    }

    companion object {
        operator fun<T> invoke(entry: Pair<MetaKey<T>, T>): MetaData {
            return MetaData(mutableMapOf(entry.uncheckedAs()))
        }
    }
}

object SbfMeta {
    // These keys have relevant values
    val COMMENT = MetaKey<String>("comment")
    // unique identified to the inlined call
    val CALL_ID = MetaKey<ULong>("call_id")
    // name of the inlined function
    val INLINED_FUNCTION_NAME = MetaKey<String>("inlined_function_name")
    // number of instructions of the inlined function before any slicing/optimization
    val INLINED_FUNCTION_SIZE = MetaKey<ULong>("inlined_function_size")
    // mangled name of called function
    val MANGLED_NAME = MetaKey<String>("mangled_name")
    // number of registers used by the call
    val KNOWN_ARITY = MetaKey<Int>("external_function_arity")
    // keep track of some equalities
    val EQUALITY_REG_AND_STACK = MetaKey<Pair<Value.Reg, StackContentMeta>>("equality_reg_and_stack")
    // type of a register (used by the pointer analysis)
    val REG_TYPE =  MetaKey<Pair<Value.Reg, SbfRegisterType>>("reg_type")
    // Address of the instruction
    val SBF_ADDRESS = MetaKey<ULong>("sbf_bytecode_address")

    val SBF_DWARF_DEBUG_ANNOTATIONS = MetaKey<List<DWARFCfgEdgeLabel>>("sbf_debug_edge_annotation")
    // The value is true if the loaded register affects the control flow of the program
    val LOADED_AS_NUM_FOR_PTA = MetaKey<Boolean>("loaded_as_number_for_pta")
    //  Promoted overflow check condition
    val PROMOTED_OVERFLOW_CHECK = MetaKey<Condition>("promoted_overflow_check")
    // The MOV instruction sets the address of a global variable to a register
    val SET_GLOBAL = MetaKey<String>("set_global")
    // If a call to a function is mocking a call to another function, this is the original function
    val MOCK_FOR = MetaKey<String>("mock_for")

    // These keys have empty strings as values. The values are irrelevant
    val HINT_OPTIMIZED_WIDE_STORE =  MetaKey<String>("hint_optimized_wide_store")
    val MEMCPY_PROMOTION = MetaKey<String>("promoted_stores_to_memcpy")
    val UNHOISTED_STORE = MetaKey<String>("unhoisted_store")
    val UNHOISTED_LOAD = MetaKey<String>("unhoisted_load")
    val UNHOISTED_MEMCPY = MetaKey<String>("unhoisted_memcpy")
    val UNHOISTED_MEMCMP = MetaKey<String>("unhoisted_memcmp")
    val UNHOISTED_STACK_POP = MetaKey<String>("unhoisted_stack_pop")
    val LOWERED_SELECT = MetaKey<String>("lowered_select")
    val REMOVED_MEMMOVE = MetaKey<String>("sol_memmove_")
    val LOWERED_ASSUME = MetaKey<String>("lowered_assume")
    val LOWERED_OR = MetaKey<String>("lowered_or")
    val UNREACHABLE_FROM_COI = MetaKey<String>("unreachable_from_coi")
    val SAFE_MATH = MetaKey<String>("safe_math")
    val RANGE = MetaKey<Range.Range>("range")
}

data class MetaKey<T>(val name: String)

data class StackContentMeta(val offset: Long, val width: Short)

fun toString(metaData: MetaData): String {
    val strB = StringBuilder()

    metaData.getVal(SbfMeta.COMMENT)?.let {
        strB.append(" /*$it*/")
    }
    for ((k, v) in metaData.entries) {
        when (k) {
            SbfMeta.HINT_OPTIMIZED_WIDE_STORE, SbfMeta.MEMCPY_PROMOTION,
            SbfMeta.UNHOISTED_STORE, SbfMeta.UNHOISTED_LOAD,
            SbfMeta.UNHOISTED_MEMCPY, SbfMeta.UNHOISTED_MEMCMP, SbfMeta.UNHOISTED_STACK_POP,
            SbfMeta.LOWERED_SELECT, SbfMeta.LOWERED_OR, SbfMeta.LOADED_AS_NUM_FOR_PTA, SbfMeta.REMOVED_MEMMOVE,
            SbfMeta.SAFE_MATH, SbfMeta.SET_GLOBAL -> {
                strB.append(" /*${k.name}*/")
            }
            SbfMeta.CALL_ID, SbfMeta.INLINED_FUNCTION_NAME, SbfMeta.INLINED_FUNCTION_SIZE -> {
                strB.append(" /*${k.name}=${v}*/")
            }
            SbfMeta.SBF_ADDRESS -> {
                val address: ULong = v.uncheckedAs()
                strB.append(" /* 0x${address.toString(16)} */")
            }
            SbfMeta.LOWERED_ASSUME -> {}
            SbfMeta.KNOWN_ARITY, SbfMeta.EQUALITY_REG_AND_STACK -> {}
            SbfMeta.UNREACHABLE_FROM_COI -> {}
            SbfMeta.COMMENT -> {}
            SbfMeta.PROMOTED_OVERFLOW_CHECK -> {
                val cond: Condition = v.uncheckedAs()
                strB.append(" /*${k.name}: $cond*/")
            }
            SbfMeta.REG_TYPE -> {
                val (reg, type) = v.uncheckedAs<Pair<Value.Reg, SbfRegisterType>>()
                strB.append(" /* type($reg)=$type */")
            }
            SbfMeta.SBF_DWARF_DEBUG_ANNOTATIONS -> {
                metaData.getVal(SbfMeta.SBF_DWARF_DEBUG_ANNOTATIONS)?.let { scopeEnds ->
                    strB.append(scopeEnds.joinToString("\n"))
                }
            }
            SbfMeta.MANGLED_NAME -> {}
            SbfMeta.RANGE -> {}
        }
    }
    return strB.toString()
}
