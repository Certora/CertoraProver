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
import sbf.disassembler.*
import datastructures.stdcollections.*
import sbf.cfg.SbfInstruction
import sbf.cfg.MetaData
import sbf.domains.MemSummaryArgument
import sbf.domains.MemSummaryArgumentType
import sbf.domains.MemorySummaries
import sbf.domains.MemorySummary

/**
 *  Solana syscalls
 *
 *  All functions are defined here
 *  https://github.com/solana-labs/solana/blob/master/sdk/program/src/syscalls/definitions.rs#L39
 * **/

// To avoid clashes with user-defined functions
const val MAX_SYSCALL_FUNCTIONS = 1000

@Suppress("ForbiddenComment")
/*
 * TODO (this list keeps growing):
 *  sol_log_pubkey
 *  sol_try_find_program_address
 *  sol_sha256
 *  sol_keccak256
 *  sol_secp256k1_recover
 *  sol_blake3
 *  sol_zk_token_elgamal_op
 *  sol_zk_token_elgamal_op_with_lo_hi
 *  sol_zk_token_elgamal_op_with_scalar
 *  sol_get_epoch_schedule_sysvar
 *  sol_log_data
 */
enum class SolanaFunction(val syscall: ExternalFunction) {
    ABORT(ExternalFunction(
        name = "abort")),
    SOL_LOG(ExternalFunction(
        name = "sol_log_",
        readRegisters = setOf(Value.Reg(SbfRegister.R1_ARG), Value.Reg(SbfRegister.R2_ARG)))),
    SOL_LOG_64(ExternalFunction(
        name = "sol_log_64_",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_LOG_COMPUTE_UNITS(ExternalFunction(
        name = "sol_log_compute_units_")),
    SOL_ALLOC_FREE(ExternalFunction(
        name = "sol_alloc_free_",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = setOf(Value.Reg(SbfRegister.R1_ARG), Value.Reg(SbfRegister.R2_ARG)))),
    SOL_PANIC(ExternalFunction(
        name = "sol_panic_",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_CREATE_PROGRAM_ADDRESS(ExternalFunction(
        name = "sol_create_program_address",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_INVOKE_SIGNED_C(ExternalFunction(
        name = "sol_invoke_signed_c",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_INVOKE_SIGNED_RUST(ExternalFunction(
        name = "sol_invoke_signed_rust",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)),
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_MEMCPY(ExternalFunction(
        name = "sol_memcpy_",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_MEMMOVE(ExternalFunction(
        name = "sol_memmove_",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_MEMSET(ExternalFunction(
        name = "sol_memset_",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_MEMCMP(ExternalFunction(
        name = "sol_memcmp_",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_GET_CLOCK_SYSVAR(ExternalFunction(
        name = "sol_get_clock_sysvar",
        readRegisters = setOf(Value.Reg(SbfRegister.R1_ARG)))),
    SOL_CURVE_VALIDATE_POINT(ExternalFunction(
        name = "sol_curve_validate_point",
        readRegisters = setOf(Value.Reg(SbfRegister.R1_ARG), Value.Reg(SbfRegister.R2_ARG)))),
    SOL_CURVE_GROUP_OP(ExternalFunction(
        name = "sol_curve_group_op",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_GET_STACK_HEIGHT(ExternalFunction(
        name = "sol_get_stack_height",
        writeRegisters = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)))),
    SOL_GET_PROCESSED_SIBLING_INSTRUCTION(ExternalFunction(
        name = "sol_get_processed_sibling_instruction",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG,
            SbfRegister.R3_ARG, SbfRegister.R4_ARG, SbfRegister.R5_ARG).map{ Value.Reg(it)}.toSet())),
    SOL_GET_RENT_SYSVAR(ExternalFunction(
        name = "sol_get_rent_sysvar",
        readRegisters = setOf(Value.Reg(SbfRegister.R1_ARG)))),
    SOL_GET_FEES_SYSVAR(ExternalFunction(
        name = "sol_get_fees_sysvar",
        readRegisters = setOf(Value.Reg(SbfRegister.R1_ARG)))),
    SOL_SET_RETURN_DATA(ExternalFunction(
        name = "sol_set_return_data",
        readRegisters = setOf(Value.Reg(SbfRegister.R1_ARG), Value.Reg(SbfRegister.R2_ARG)))),
    SOL_GET_RETURN_DATA(ExternalFunction(
        name = "sol_get_return_data",
        readRegisters = listOf(
            SbfRegister.R1_ARG, SbfRegister.R2_ARG, SbfRegister.R3_ARG).map{ Value.Reg(it)}.toSet()));

    companion object: ExternalLibrary<SolanaFunction> {
        init {
            check(values().size < MAX_SYSCALL_FUNCTIONS) {"Exceeded maximum number of Solana syscalls"}
        }

        private val nameMap = values().associateBy { it.syscall.name }
        private val valueMap = values().associateBy { it.ordinal }
        override fun from(name: String) = nameMap[name]
        fun from(value: Int) = valueMap[value]

        fun toCallInst(function: SolanaFunction, metadata: MetaData = MetaData()) =
            SbfInstruction.Call(name = function.syscall.name, metaData = metadata)

        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    // These are already natively understood by the prover
                    ABORT, SOL_PANIC -> {}
                    SOL_MEMCMP, SOL_MEMCPY, SOL_MEMMOVE, SOL_MEMSET -> {}
                    // No summaries
                    SOL_LOG, SOL_LOG_64, SOL_LOG_COMPUTE_UNITS -> {}
                    // These syscalls doesn't need to be summarized because either they are always called by wrappers that are
                    // already summarized or the default summary is enough.
                    SOL_ALLOC_FREE -> {}
                    SOL_CREATE_PROGRAM_ADDRESS, SOL_INVOKE_SIGNED_C, SOL_INVOKE_SIGNED_RUST -> {}
                    SOL_CURVE_VALIDATE_POINT, SOL_CURVE_GROUP_OP,
                    SOL_GET_STACK_HEIGHT, SOL_GET_PROCESSED_SIBLING_INSTRUCTION,
                    SOL_GET_RENT_SYSVAR, SOL_GET_FEES_SYSVAR, SOL_SET_RETURN_DATA, SOL_GET_RETURN_DATA -> {}
                    // Syscalls that require summaries
                    SOL_GET_CLOCK_SYSVAR-> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 0, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 8, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 16, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 24, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1_ARG, offset = 32, width = 8, type = MemSummaryArgumentType.NUM))
                        memSummaries.addSummary(f.syscall.name, MemorySummary(summaryArgs))
                    }

                }
            }
        }
    }

}
