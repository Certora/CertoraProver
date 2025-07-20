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

import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.SbfCFG

/** A call to a known [CpiInstruction] at a specific location in the code. */
data class CpiCall(
    val cfg: SbfCFG,
    val invokeInstruction: LocatedSbfInstruction,
    val cpiInstruction: CpiInstruction,
    val invokeType: InvokeType
) {
    override fun toString(): String {
        return "$invokeType: $cpiInstruction @ $invokeInstruction"
    }
}

/** A known instruction for a specific Solana program. */
sealed interface CpiInstruction

/** Instructions for the Token program. */
sealed interface TokenInstruction : CpiInstruction {

    data object Transfer : TokenInstruction
    data object MintTo : TokenInstruction
    data object Burn : TokenInstruction
    data object CloseAccount : TokenInstruction
    data object TransferChecked : TokenInstruction

    companion object {
        /**
         * Converts an instruction discriminant into a Token instruction.
         * In `spl_token::instruction` there is a function `pack` that encodes `TokenInstruction`s into the `data` field
         * passed to `Instruction`.
         * That function is the one that determines the mapping between numbers an instructions, and this function
         * [from] decodes numbers into instructions.
         */
        fun from(discriminant: Long): TokenInstruction? {
            return when (discriminant) {
                3L -> Transfer
                7L -> MintTo
                8L -> Burn
                9L -> CloseAccount
                12L -> TransferChecked
                else -> null
            }
        }
    }
}

/** The type of the `invoke` instruction, which can be either a simple `invoke`, or an `invoke_signed` */
sealed interface InvokeType {
    data object Invoke : InvokeType
    data object InvokeSigned : InvokeType
}

/** A known Solana program. */
sealed interface Program {

    data object SystemProgram : Program
    data object Token : Program

    /** Representation of a `Pubkey` as four u64s. */
    data class ProgramId(val chunk0: ULong, val chunk1: ULong, val chunk2: ULong, val chunk3: ULong)

    companion object {
        val SystemProgramId =
            ProgramId(0UL, 0UL, 0UL, 0UL)

        val TokenProgramId =
            ProgramId(10637895772709248262UL, 12428223917890587609UL, 10463932726783620124UL, 12178014311288245306UL)

        /**
         * Converts a program id (i.e., a `Pubkey`) into a known program.
         * If the program id does not correspond to any known program, returns `null`.
         */
        fun from(programId: ProgramId): Program? {
            return if (programId == SystemProgramId) {
                SystemProgram
            } else if (programId == TokenProgramId) {
                Token
            } else {
                null
            }
        }
    }
}
