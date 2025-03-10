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

package sbf.callgraph

import sbf.cfg.Value
import sbf.disassembler.SbfRegister
import datastructures.stdcollections.*
import sbf.domains.MemSummaryArgument
import sbf.domains.MemSummaryArgumentType
import sbf.domains.MemorySummaries

private const val CVT_nondet_u8 = "CVT_nondet_u8"
private const val CVT_nondet_u16 = "CVT_nondet_u16"
private const val CVT_nondet_u32 = "CVT_nondet_u32"
private const val CVT_nondet_u64 = "CVT_nondet_u64"
private const val CVT_nondet_u128 = "CVT_nondet_u128"
private const val CVT_nondet_usize = "CVT_nondet_usize"
private const val CVT_nondet_i8 = "CVT_nondet_i8"
private const val CVT_nondet_i16 = "CVT_nondet_i16"
private const val CVT_nondet_i32 = "CVT_nondet_i32"
private const val CVT_nondet_i64 = "CVT_nondet_i64"
private const val CVT_assume = "CVT_assume"
private const val CVT_assert = "CVT_assert"
private const val CVT_satisfy = "CVT_satisfy"
private const val CVT_sanity = "CVT_sanity"
private const val CVT_save_scratch_registers = "CVT_save_scratch_registers"
private const val CVT_restore_scratch_registers = "CVT_restore_scratch_registers"
private const val CVT_nondet_account_info = "CVT_nondet_account_info"
private const val CVT_calltrace_print_u64_1 = "CVT_calltrace_print_u64_1"
private const val CVT_calltrace_print_u64_2 = "CVT_calltrace_print_u64_2"
private const val CVT_calltrace_print_u64_3 = "CVT_calltrace_print_u64_3"
private const val CVT_calltrace_print_u64_as_fixed = "CVT_calltrace_print_u64_as_fixed"
private const val CVT_calltrace_print_i64_1 = "CVT_calltrace_print_i64_1"
private const val CVT_calltrace_print_i64_2 = "CVT_calltrace_print_i64_2"
private const val CVT_calltrace_print_i64_3 = "CVT_calltrace_print_i64_3"
private const val CVT_calltrace_print_string = "CVT_calltrace_print_string"
private const val CVT_calltrace_print_tag = "CVT_calltrace_print_tag"
private const val CVT_calltrace_print_location = "CVT_calltrace_print_location"
private const val CVT_calltrace_attach_location = "CVT_calltrace_attach_location"
private const val CVT_u128_leq = "CVT_u128_leq"
private const val CVT_u128_gt0 = "CVT_u128_gt0"
private const val CVT_u128_ceil_div = "CVT_u128_ceil_div"
private const val CVT_nativeint_u64_eq = "CVT_nativeint_u64_eq"
private const val CVT_nativeint_u64_lt = "CVT_nativeint_u64_lt"
private const val CVT_nativeint_u64_le = "CVT_nativeint_u64_le"
private const val CVT_nativeint_u64_add = "CVT_nativeint_u64_add"
private const val CVT_nativeint_u64_sub = "CVT_nativeint_u64_sub"
private const val CVT_nativeint_u64_mul = "CVT_nativeint_u64_mul"
private const val CVT_nativeint_u64_div = "CVT_nativeint_u64_div"
private const val CVT_nativeint_u64_div_ceil = "CVT_nativeint_u64_div_ceil"
private const val CVT_nativeint_u64_muldiv = "CVT_nativeint_u64_muldiv"
private const val CVT_nativeint_u64_muldiv_ceil = "CVT_nativeint_u64_muldiv_ceil"
private const val CVT_nativeint_u64_nondet = "CVT_nativeint_u64_nondet"
private const val CVT_nativeint_u64_from_u128 = "CVT_nativeint_u64_from_u128"
private const val CVT_nativeint_u64_from_u256 = "CVT_nativeint_u64_from_u256"
private const val CVT_nativeint_u64_u64_max = "CVT_nativeint_u64_u64_max"
private const val CVT_nativeint_u64_u128_max = "CVT_nativeint_u64_u128_max"
private const val CVT_nativeint_u64_u256_max = "CVT_nativeint_u64_u256_max"
private const val CVT_nondet_solana_account_space = "CVT_nondet_solana_account_space"
private const val CVT_alloc_slice = "CVT_alloc_slice"

sealed class CVTFunction(val function: ExternalFunction) {
    data class Core(val value: CVTCore): CVTFunction(value.function)
    data class Calltrace(val value: CVTCalltrace): CVTFunction(value.function)
    data class Nondet(val value: CVTNondet): CVTFunction(value.function)
    data class NativeInt(val value: CVTNativeInt): CVTFunction(value.function)
    data class U128Intrinsics(val value: CVTU128Intrinsics): CVTFunction(value.function)

    companion object: ExternalLibrary<CVTFunction> {

        override fun from(name: String): CVTFunction? {
            CVTCore.from(name)?.run {
                return Core(this)
            }
            CVTCalltrace.from(name)?.run {
                return Calltrace(this)
            }
            CVTNondet.from(name)?.run {
                return Nondet(this)
            }
            CVTNativeInt.from(name)?.run {
                return NativeInt(this)
            }
            CVTU128Intrinsics.from(name)?.run {
                return U128Intrinsics(this)
            }

            return null
        }

        override fun addSummaries(memSummaries: MemorySummaries) {
            CVTCore.addSummaries(memSummaries)
            CVTCalltrace.addSummaries(memSummaries)
            CVTNondet.addSummaries(memSummaries)
            CVTNativeInt.addSummaries(memSummaries)
            CVTU128Intrinsics.addSummaries(memSummaries)
        }
    }
}

enum class CVTCore(val function: ExternalFunction) {
    ASSUME(ExternalFunction(CVT_assume, setOf(), setOf(Value.Reg(SbfRegister.R1_ARG)))),
    ASSERT(ExternalFunction(CVT_assert, setOf(), setOf(Value.Reg(SbfRegister.R1_ARG)))),
    SATISFY(ExternalFunction(CVT_satisfy, setOf(), setOf(Value.Reg(SbfRegister.R1_ARG)))),
    SANITY(ExternalFunction(CVT_sanity, setOf(), setOf(Value.Reg(SbfRegister.R1_ARG)))),
    SAVE_SCRATCH_REGISTERS(ExternalFunction(CVT_save_scratch_registers, writeRegisters = setOf(), readRegisters = setOf())),
    RESTORE_SCRATCH_REGISTERS(ExternalFunction(CVT_restore_scratch_registers, writeRegisters = setOf(), readRegisters = setOf())),
    NONDET_SOLANA_ACCOUNT_SPACE(ExternalFunction(CVT_nondet_solana_account_space,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG).map{ Value.Reg(it)}.toSet())
    ),
    ALLOC_SLICE(ExternalFunction(CVT_alloc_slice,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet())
    ),
    /** Deprecated **/
    NONDET_ACCOUNT_INFO(ExternalFunction(CVT_nondet_account_info, setOf(),setOf(Value.Reg(SbfRegister.R1_ARG))));

    companion object: ExternalLibrary<CVTCore>  {
        private val nameMap = values().associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    // No summaries
                    ASSERT, ASSUME, SATISFY, SANITY,
                    RESTORE_SCRATCH_REGISTERS, SAVE_SCRATCH_REGISTERS -> {}
                    // Summaries
                    NONDET_SOLANA_ACCOUNT_SPACE -> {
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0_RETURN_VALUE, type = MemSummaryArgumentType.PTR_INPUT))
                        memSummaries.addSummary(f.function.name, summaryArgs)
                    }
                    ALLOC_SLICE -> {
                        // This summary is sound, but it will case PTA errors (because of the type `ANY`). Thus, it should NOT be used by the pointer domain.
                        // The reason why the argument type is `ANY` is that the memory region is not fixed.
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0_RETURN_VALUE, type = MemSummaryArgumentType.ANY))
                        memSummaries.addSummary(f.function.name, summaryArgs)
                    }
                    // Summary currently provided by configuration file
                    NONDET_ACCOUNT_INFO -> {}
                }
            }
        }
    }
}

enum class CVTCalltrace(val function: ExternalFunction,
                        // From all registers (r1-r5), which registers contain the string or strings passed to the function
                        val strings: Set<CalltraceStr>) {
    CEX_PRINT_u64_1(CexPrintValue(CVT_calltrace_print_u64_1, 3),
                    setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_u64_2(CexPrintValue(CVT_calltrace_print_u64_2, 4),
                    setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_u64_3(CexPrintValue(CVT_calltrace_print_u64_3, 5),
                    setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_i64_1(CexPrintValue(CVT_calltrace_print_i64_1, 3),
                    setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_i64_2(CexPrintValue(CVT_calltrace_print_i64_2, 4),
                    setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_i64_3(CexPrintValue(CVT_calltrace_print_i64_3, 5),
                    setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_u64_AS_FIXED(CexPrintValue(CVT_calltrace_print_u64_as_fixed, 4),
                    setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_TAG(ExternalFunction(CVT_calltrace_print_tag, setOf(),
                                    listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet()),
                    setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_LOCATION(ExternalFunction(CVT_calltrace_print_location, setOf(),
                                        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet()),
                        setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_ATTACH_LOCATION(ExternalFunction(CVT_calltrace_attach_location, setOf(),
                                         listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet()),
                        setOf(CalltraceStr(SbfRegister.R1_ARG))),
    CEX_PRINT_STRING(CexPrintValue(CVT_calltrace_print_string, 4),
                    setOf(CalltraceStr(SbfRegister.R1_ARG), CalltraceStr(SbfRegister.R3_ARG)));

    companion object: ExternalLibrary<CVTCalltrace>  {
        private val nameMap = values().associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            // No summaries
        }
    }
}

private data class CexPrintValue(override val name: String, val numArgs: Byte):
    ExternalFunction(name,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG,
            SbfRegister.R5_ARG).filter { it.value <= numArgs }.map{ Value.Reg(it)}.toSet())

/**
 * A literal string is represented by two registers:
 * [string] pointing to the address that contains the string and [len] the number of bytes.
 **/
data class CalltraceStr(val string: Value.Reg, val len: Value.Reg) {
    constructor(reg: SbfRegister): this(Value.Reg(reg), Value.Reg(SbfRegister.getByValue((reg.ordinal+1).toByte())))
}

enum class CVTU128Intrinsics(val function: ExternalFunction) {
    U128_NONDET(ExternalFunction(CVT_nondet_u128, setOf(), setOf(Value.Reg(SbfRegister.R1_ARG)))),
    U128_LEQ(ExternalFunction(CVT_u128_leq,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG).map{ Value.Reg(it)}.toSet())),
    U128_GT0(ExternalFunction(CVT_u128_gt0,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())),
    U128_CEIL_DIV(ExternalFunction(CVT_u128_ceil_div,
        setOf(),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet()));


    companion object: ExternalLibrary<CVTU128Intrinsics>  {
        private val nameMap = values().associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]

        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    U128_LEQ, U128_GT0 -> {
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0_RETURN_VALUE, type = MemSummaryArgumentType.NUM))
                        memSummaries.addSummary(f.function.name, summaryArgs)
                    }
                    U128_CEIL_DIV, U128_NONDET  -> {
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 0 , width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 8 , width = 8, type = MemSummaryArgumentType.NUM))
                        memSummaries.addSummary(f.function.name, summaryArgs)
                    }
                }
            }
        }
    }
}

enum class CVTNativeInt(val function: ExternalFunction) {
    NATIVEINT_EQ(ExternalFunction(CVT_nativeint_u64_eq,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_LT(ExternalFunction(CVT_nativeint_u64_lt,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_LE(ExternalFunction(CVT_nativeint_u64_le,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_ADD(ExternalFunction(CVT_nativeint_u64_add,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_SUB(ExternalFunction(CVT_nativeint_u64_sub,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_MUL(ExternalFunction(CVT_nativeint_u64_mul,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_DIV(ExternalFunction(CVT_nativeint_u64_div,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_CEIL_DIV(ExternalFunction(CVT_nativeint_u64_div_ceil,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_MULDIV(ExternalFunction(CVT_nativeint_u64_muldiv,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_MULDIV_CEIL(ExternalFunction(CVT_nativeint_u64_muldiv_ceil,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_NONDET(ExternalFunction(CVT_nativeint_u64_nondet,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_FROM_u128(ExternalFunction(CVT_nativeint_u64_from_u128,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_FROM_u256(ExternalFunction(CVT_nativeint_u64_from_u256,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG, SbfRegister.R4_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_u64_MAX(ExternalFunction(CVT_nativeint_u64_u64_max,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        setOf())
    ),
    NATIVEINT_u128_MAX(ExternalFunction(CVT_nativeint_u64_u128_max,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        setOf())
    ),
    NATIVEINT_u256_MAX(ExternalFunction(CVT_nativeint_u64_u256_max,
        setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        setOf())
    );


    companion object: ExternalLibrary<CVTNativeInt>  {
        private val nameMap = values().associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0_RETURN_VALUE, type = MemSummaryArgumentType.NUM))
                memSummaries.addSummary(f.function.name, summaryArgs)
            }
        }
    }
}

enum class CVTNondet(val function: ExternalFunction) {
    NONDET_u8(ExternalFunction(CVT_nondet_u8,setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf())),
    NONDET_u16(ExternalFunction(CVT_nondet_u16, setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf())),
    NONDET_u32(ExternalFunction(CVT_nondet_u32, setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf())),
    NONDET_u64(ExternalFunction(CVT_nondet_u64, setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf())),
    NONDET_usize(ExternalFunction(CVT_nondet_usize, setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf())),
    NONDET_i8(ExternalFunction(CVT_nondet_i8, setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf())),
    NONDET_i16(ExternalFunction(CVT_nondet_i16, setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf())),
    NONDET_i32(ExternalFunction(CVT_nondet_i32, setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf())),
    NONDET_i64(ExternalFunction(CVT_nondet_i64, setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)), setOf()));

    companion object: ExternalLibrary<CVTNondet>  {
        private val nameMap = values().associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0_RETURN_VALUE, type = MemSummaryArgumentType.NUM))
                memSummaries.addSummary(f.function.name, summaryArgs)
            }
        }
    }
}
