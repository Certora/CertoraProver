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

enum class CompilerRtFunction(val function: ExternalFunction) {
    /**
     * Represent a 128-bit arithmetic operation `res := x op y` using 64-bit registers
     *  ```
     *  r2: low(x),
     *  r3: high(x),
     *  r4: low(y),
     *  r5: high(y)
     *  *(r1): low(res)
     *  *(r1+8): high(res)
     *  ```
     * **/
    MULTI3(ExternalFunction(
        name = "__multi3",
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

    companion object: ExternalLibrary<CompilerRtFunction>  {
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
                }
            }
        }
    }
}
