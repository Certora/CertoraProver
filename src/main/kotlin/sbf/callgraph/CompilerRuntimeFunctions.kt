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
import sbf.domains.MemSummaryArgument
import sbf.domains.MemSummaryArgumentType
import sbf.domains.MemorySummaries
import datastructures.stdcollections.*
import sbf.domains.MemorySummary

/** compiler-rt library used by Clang/LLVM **/

sealed class CompilerRtFunction(val function: ExternalFunction) {
    data class SignedInteger64(val value: SignedInteger64CompilerRtFunction): CompilerRtFunction(value.function)
    data class IntegerU128(val value: IntegerU128CompilerRtFunction) : CompilerRtFunction(value.function)
    data class FP(val value: FPCompilerRtFunction) : CompilerRtFunction(value.function)

    companion object : ExternalLibrary<CompilerRtFunction> {
        override fun from(name: String): CompilerRtFunction? {
            SignedInteger64CompilerRtFunction.from(name)?.run {
                return SignedInteger64(this)
            }
            IntegerU128CompilerRtFunction.from(name)?.run {
                return IntegerU128(this)
            }
            FPCompilerRtFunction.from(name)?.run {
                return FP(this)
            }

            return null
        }

        override fun addSummaries(memSummaries: MemorySummaries) {
            SignedInteger64CompilerRtFunction.addSummaries(memSummaries)
            IntegerU128CompilerRtFunction.addSummaries(memSummaries)
            FPCompilerRtFunction.addSummaries(memSummaries)
        }
    }
}

enum class SignedInteger64CompilerRtFunction(val function: ExternalFunction) {
    /** Signed division for 64-bits **/
    DIVDI3(ExternalFunction(
        name = "__divdi3",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())),

    /** Signed modulus (remainder) for 64 bits **/
    MODDI3(ExternalFunction(
        name = "__moddi3",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet()));

    companion object: ExternalLibrary<SignedInteger64CompilerRtFunction>  {
        private val nameMap = values().associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    DIVDI3, MODDI3 -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0_RETURN_VALUE, type = MemSummaryArgumentType.NUM)
                        )
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                }
            }
        }
    }
}

/**
 * A 128-bit arithmetic operation `res := x op y` using 64-bit registers
 *  ```
 *  r2: low(x),
 *  r3: high(x),
 *  r4: low(y),
 *  r5: high(y)
 *  *(r1): low(res)
 *  *(r1+8): high(res)
 *  ```
 * **/
enum class IntegerU128CompilerRtFunction(val function: ExternalFunction) {
    MULTI3(ExternalFunction(
        name = "__multi3",
        writeRegisters = setOf(),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet())),
    MULOTI4(ExternalFunction(
        name = "__muloti4",
        writeRegisters = setOf(),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet())),
    UDIVTI3(ExternalFunction(
        name = "__udivti3",
        writeRegisters = setOf(),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet())),
    DIVTI3(ExternalFunction(
        name = "__divti3",
        writeRegisters = setOf(),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet()));

    companion object: ExternalLibrary<IntegerU128CompilerRtFunction>  {
        private val nameMap = values().associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    MULTI3, UDIVTI3, DIVTI3 -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 0, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 8, width = 8, type = MemSummaryArgumentType.NUM)
                        )
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }

                    MULOTI4 -> {
                        /** [MemSummaryArgument] is not expressive enough to express the summary for `__muloti4`
                         *  ```
                         *  ti_int __muloti4(ti_int a, ti_int b, int* overflow);
                         *  ```
                         *   In SBF, we have `__muloti4(r1,r2,r3,r4,r5)` where:
                         *   - `*(r1+0)` and `*(r1+8)` contains the low and high 64 bits of the result
                         *   - `r2` and `r3` contains the low and high 64 bits of `a`
                         *   - `r4` and `*(r5-(4096 - 0))` contains the low and high 64 bits of `b`
                         *   - `*(r5-(4096 - 8))` contains `overflow` which is a pointer.
                         *      Thus, `**(r5-(4096 - 8))` is where the actual overflow Boolean flag is stored
                         *
                         *  For now, we can only describe `*(r1+0)` and `*(r1+8)` but not `**(r5-4088)`.
                         *  Thus, some warning or error should be reported if `__muloti4` is executed.
                         **/
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 0, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 8, width = 8, type = MemSummaryArgumentType.NUM)
                        )
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                }
            }
        }
    }
}

/**
 * Floating point operations
 *
 * https://gcc.gnu.org/onlinedocs/gccint/Soft-float-library-routines.html
 *
 * In Rust, we can have `f32` and `f64` which refer to `float` and `double`, respectively in the above link.
 * In SBF, both `f32` and `f64` are stored in 64-bit registers.
 *
 * Moreover, there are also many conversion operations between floating points and integral numbers.
 * The most common ones are signed 32/64-bits and unsigned 32/64-bits.
 *
 * For now, we include the most common f64 operations and the conversion from/to **unsigned 64-bit** numbers.
 **/
enum class FPCompilerRtFunction(val function: ExternalFunction) {
    ADDDF3(ExternalFunction(
        name = "__adddf3",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    SUBDF3(ExternalFunction(
        name = "__subdf3",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    MULDF3(ExternalFunction(
        name = "__muldf3",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    DIVDF3(ExternalFunction(
        name = "__divdf3",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    NEGDF2(ExternalFunction(
        name = "__negdf2",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // Convert a f64 to u64
    FIXUNSDFDI(ExternalFunction(
        name = "__fixunsdfdi",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // Convert a u64 to f64
    FLOATUNDIDF(ExternalFunction(
        name = "__floatundidf",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // Return a nonzero value if either argument is NaN, otherwise 0.
    UNORDDF2(ExternalFunction(
        name = "__unorddf2",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // return zero if neither argument is NaN, and a and b are equal.
    EQDF2(ExternalFunction(
        name = "__eqdf2",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // return a nonzero value if either argument is NaN, or if a and b are unequal.
    NEDF2(ExternalFunction(
        name = "__nedf2",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // return a value greater than or equal to zero if neither argument is NaN, and a is greater than or equal to b.
    GEDF2(ExternalFunction(
        name = "__gedf2",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // return a value less than zero if neither argument is NaN, and a is strictly less than b.
    LTDF2(ExternalFunction(
        name = "__ltdf2",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // return a value less than or equal to zero if neither argument is NaN, and a is less than or equal to b.
    LEDF2(ExternalFunction(
        name = "__ledf2",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    ),
    // return a value greater than zero if neither argument is NaN, and a is strictly greater than b.
    GTDF2(ExternalFunction(
        name = "__gtdf2",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(SbfRegister.R1_ARG, SbfRegister.R2_ARG).map{ Value.Reg(it)}.toSet())
    );


    companion object: ExternalLibrary<FPCompilerRtFunction>  {
        private val nameMap = values().associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]

        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    ADDDF3,
                    SUBDF3,
                    MULDF3,
                    DIVDF3,
                    NEGDF2,
                    FIXUNSDFDI,
                    FLOATUNDIDF,
                    UNORDDF2,
                    EQDF2,
                    NEDF2,
                    GEDF2,
                    LTDF2,
                    LEDF2,
                    GTDF2 -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0_RETURN_VALUE, type = MemSummaryArgumentType.NUM)
                        )
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                }
            }
        }
    }
}
