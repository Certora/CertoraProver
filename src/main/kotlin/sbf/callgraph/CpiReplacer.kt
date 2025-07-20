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

import log.Logger
import log.LoggerTypes
import sbf.cfg.MetaData
import sbf.cfg.MutableSbfBasicBlock
import sbf.cfg.MutableSbfCFG
import sbf.cfg.SbfInstruction
import sbf.cfg.SbfMeta
import datastructures.stdcollections.*
import sbf.analysis.cpis.CpiCall
import sbf.analysis.cpis.CpiInstruction
import sbf.analysis.cpis.INVOKE_FUNCTION_NAME
import sbf.analysis.cpis.TokenInstruction

private val cpiLog = Logger(LoggerTypes.CPI)

/**
 * Returns [prog] where the CPI calls have been replaced with calls to their respective mocks.
 * For example, `call solana_program::program::invoke` could be substituted to
 * `call cvlr_solana::cpis::cvlr_invoke_transfer` if the invoked instruction is Token's transfer.
 */
fun replaceCpis(prog: SbfCallGraph, cpiCalls: Iterable<CpiCall>): SbfCallGraph {
    return CpiReplacer.replaceCpis(prog, cpiCalls)
}

/**
 * A set of demangled function names that we use to replace calls to `invoke`.
 * These names correspond to high-level Rust function identifiers (demangled from their mangled symbol names).
 *
 * Example: "cvlr_solana::cpis::cvlr_invoke_transfer"
 */
val CPIS_MOCK_FUNCTION_NAMES: Set<String> = CpiReplacer.mockFunctionsNames

private object CpiReplacer {
    val mockFunctionsNames: Set<String>

    /** A mock for a known CPI call. */
    data class InvokeMock(val demangledName: String, val mangledName: String) {
        fun getReplacementInstructions(): List<SbfInstruction> {
            val metaData =
                MetaData()
                    .plus(SbfMeta.MANGLED_NAME to mangledName)
                    .plus(SbfMeta.MOCK_FOR to INVOKE_FUNCTION_NAME)
            return listOf(SbfInstruction.Call(demangledName, metaData = metaData))
        }
    }

    val InvokeTransferMock = InvokeMock(
        demangledName = "cvlr_solana::cpis::cvlr_invoke_transfer",
        mangledName = "_ZN11cvlr_solana4cpis20cvlr_invoke_transfer17hf63e9306c568c048E"
    )

    val InvokeMintToMock = InvokeMock(
        demangledName = "cvlr_solana::cpis::cvlr_invoke_mint_to",
        mangledName = "_ZN11cvlr_solana4cpis19cvlr_invoke_mint_to17hc448a2e751290a6cE"
    )

    val InvokeBurnMock = InvokeMock(
        demangledName = "cvlr_solana::cpis::cvlr_invoke_burn",
        mangledName = "_ZN11cvlr_solana4cpis16cvlr_invoke_burn17hbacfe5fffe9a3668E"
    )

    val InvokeCloseAccount = InvokeMock(
        demangledName = "cvlr_solana::cpis::cvlr_invoke_close_account",
        mangledName = "_ZN11cvlr_solana4cpis21process_close_account17h2ec09c6c4e5fb81bE"
    )

    val InvokeTransferCheckedMock = InvokeMock(
        demangledName = "cvlr_solana::cpis::cvlr_invoke_transfer_checked",
        mangledName = "_ZN11cvlr_solana4cpis28cvlr_invoke_transfer_checked17hd0e646d73ad81589E"
    )

    private val invokeMocks =
        setOf(
            InvokeTransferMock,
            InvokeMintToMock,
            InvokeBurnMock,
            InvokeCloseAccount,
            InvokeTransferCheckedMock
        )

    init {
        mockFunctionsNames = invokeMocks.map { it.demangledName }.toSet()
    }

    fun replaceCpis(prog: SbfCallGraph, cpiCalls: Iterable<CpiCall>): SbfCallGraph {
        warnIfMocksAreMissing(prog)
        return prog.transformSingleEntry { cfg ->
            val cfgAfterReplacingCpiCalls = cfg.clone(cfg.getName())
            for (cpiCall in cpiCalls) {
                if (cfg.getName() == cpiCall.cfg.getName()) {
                    replaceCpi(cfgAfterReplacingCpiCalls, cpiCall)
                }
            }
            cfgAfterReplacingCpiCalls
        }
    }

    private fun warnIfMocksAreMissing(prog: SbfCallGraph) {
        mockFunctionsNames.forEach { mockFunctionName ->
            if (prog.getCFG(mockFunctionName) == null) {
                cpiLog.warn {
                    "Mock function `$mockFunctionName` is not available in the SBF call graph." +
                        "This is likely be due to the fact that the function is not available in the compiled SBF code." +
                        "A possible fix includes calling `cvlr_solana_init!()` in the analyzed code."
                }
            }
        }
    }

    private fun replaceCpi(cfg: MutableSbfCFG, cpiCall: CpiCall) {
        val block = cfg.getMutableBlock(cpiCall.invokeInstruction.label)
        if (block == null) {
            cpiLog.warn { "Could not find block with label '${cpiCall.invokeInstruction.label}' in CFG ${cfg.getName()}" }
        } else {
            replaceCpi(block, cpiCall)
        }
    }

    private fun replaceCpi(block: MutableSbfBasicBlock, cpiCall: CpiCall) {
        val instructionToBeReplaced = cpiCall.invokeInstruction
        val replacementInstructions = getReplacementInstructions(cpiCall.cpiInstruction)
        val replacementMap = mapOf(instructionToBeReplaced to replacementInstructions)
        block.replaceInstructions(replacementMap)
    }

    private fun getReplacementInstructions(cpiInstruction: CpiInstruction): List<SbfInstruction> {
        val invokeMock = when (cpiInstruction) {
            TokenInstruction.Transfer -> InvokeTransferMock
            TokenInstruction.MintTo -> InvokeMintToMock
            TokenInstruction.Burn -> InvokeBurnMock
            TokenInstruction.CloseAccount -> InvokeCloseAccount
            TokenInstruction.TransferChecked -> InvokeTransferCheckedMock
        }
        return invokeMock.getReplacementInstructions()
    }
}
