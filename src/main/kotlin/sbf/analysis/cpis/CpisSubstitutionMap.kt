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

package sbf.analysis.cpis

import datastructures.stdcollections.*
import sbf.cfg.MetaData
import sbf.cfg.SbfInstruction
import sbf.cfg.SbfMeta

/**
 * Associates [ProgramId]s to [ProgramDiscriminants], which describes how to substitute calls to `invoke` with
 * mocking functions.
 */
data class CpisSubstitutionMap(val map: Map<ProgramId, ProgramDiscriminants>) {

    /**
     * A set of demangled function names that we use to replace calls to `invoke`.
     * These names correspond to high-level Rust function identifiers (demangled from their mangled symbol names).
     * It is important that these functions are preserved across different program transformations, otherwise it would
     * not be possible to inline them after substituting the calls to invoke with calls to the mocks.
     *
     * Example: `cvlr_spl_token::cpis::cvlr_invoke_transfer`
     */
    val mockFunctionsNames: Set<String> = buildSet {
        for (discriminants in map.values) {
            for (mockFunction in discriminants.discriminants.values) {
                add(mockFunction.invokeMock.demangledName)
                add(mockFunction.invokeSignedMock.demangledName)
            }
        }
    }
}


/** Representation of a `Pubkey` as four u64s. */
data class ProgramId(val chunk0: ULong, val chunk1: ULong, val chunk2: ULong, val chunk3: ULong) {
    companion object {
        val SystemProgramId =
            ProgramId(0UL, 0UL, 0UL, 0UL)

        val TokenProgramId =
            ProgramId(10637895772709248262UL, 12428223917890587609UL, 10463932726783620124UL, 12178014311288245306UL)
    }
}


/**
 * Describes the substitution mechanism for a specific Solana program.
 * Since each program has a specific encoding mechanism, specifies how many bytes are used to encode program
 * instructions with [numBytesDiscriminant].
 * Furthermore, [discriminants] associates a set of discriminants to the functions that they have to be replaced with.
 */
data class ProgramDiscriminants(
    val numBytesDiscriminant: Short,
    val discriminants: Map<ULong, InvokeMock>
)


/**
 * A mock function for a specific program instruction.
 * Can be used to get replacement instructions either for `invoke` or `invoke_signed`.
 */
data class InvokeMock(val invokeMock: RustFunction, val invokeSignedMock: RustFunction) {
    fun getInvokeReplacementInstructions(): List<SbfInstruction> {
        return invokeMock.getCallInstructionToThis(INVOKE_FUNCTION_NAME)
    }

    fun getInvokeSignedReplacementInstructions(): List<SbfInstruction> {
        return invokeSignedMock.getCallInstructionToThis(INVOKE_SIGNED_FUNCTION_NAME)
    }
}


data class RustFunction(val demangledName: String, val mangledName: String) {
    fun getCallInstructionToThis(mockFor: String): List<SbfInstruction> {
        val metaData =
            MetaData()
                .plus(SbfMeta.MANGLED_NAME to mangledName)
                .plus(SbfMeta.MOCK_FOR to mockFor)
        return listOf(SbfInstruction.Call(demangledName, metaData = metaData))
    }
}


/**
 * Map that describes how to substitute CPIs with mocking functions.
 */
val cpisSubstitutionMap: CpisSubstitutionMap = CpisSubstitutionMap(
    mapOf(
        ProgramId.TokenProgramId to ProgramDiscriminants(
            numBytesDiscriminant = 1,
            discriminants = mapOf(
                3UL to InvokeMock(
                    invokeMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_transfer",
                        mangledName = "_ZN14cvlr_spl_token4cpis20cvlr_invoke_transfer17he353c34d6704623eE"
                    ),
                    invokeSignedMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_transfer",
                        mangledName = "_ZN14cvlr_spl_token4cpis27cvlr_invoke_signed_transfer17he64c95924a0edeb9E"
                    ),
                ),
                7UL to InvokeMock(
                    invokeMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_mint_to",
                        mangledName = "_ZN14cvlr_spl_token4cpis19cvlr_invoke_mint_to17h45137cbd4fb649fcE"
                    ),
                    invokeSignedMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_mint_to",
                        mangledName = "_ZN14cvlr_spl_token4cpis26cvlr_invoke_signed_mint_to17h068ceaeb3cda8e5dE"
                    ),
                ),
                8UL to InvokeMock(
                    invokeMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_burn",
                        mangledName = "_ZN14cvlr_spl_token4cpis16cvlr_invoke_burn17h28e983b636cdf3a6E"
                    ),
                    invokeSignedMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_burn",
                        mangledName = "_ZN14cvlr_spl_token4cpis23cvlr_invoke_signed_burn17hebd98860ed7e7bf3E"
                    ),
                ),
                9UL to InvokeMock(
                    invokeMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_close_account",
                        mangledName = "_ZN14cvlr_spl_token4cpis25cvlr_invoke_close_account17he504bd1732d0deabE"
                    ),
                    invokeSignedMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_close_account",
                        mangledName = "_ZN14cvlr_spl_token4cpis32cvlr_invoke_signed_close_account17h05c0342d0d9be031E"
                    ),
                ),
                12UL to InvokeMock(
                    invokeMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_transfer_checked",
                        mangledName = "_ZN14cvlr_spl_token4cpis28cvlr_invoke_transfer_checked17heaf97f5b1dbcef45E"
                    ),
                    invokeSignedMock = RustFunction(
                        demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_transfer_checked",
                        mangledName = "_ZN14cvlr_spl_token4cpis35cvlr_invoke_signed_transfer_checked17h3a6ca2c40c5fa1a5E"
                    ),
                )
            )
        )
    )
)
