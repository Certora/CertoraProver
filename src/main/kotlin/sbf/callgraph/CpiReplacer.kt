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

import sbf.cfg.MetaData
import sbf.cfg.MutableSbfBasicBlock
import sbf.cfg.MutableSbfCFG
import sbf.cfg.SbfInstruction
import sbf.cfg.SbfMeta
import datastructures.stdcollections.*
import log.*
import sbf.SolanaConfig
import sbf.analysis.WholeProgramMemoryAnalysis
import sbf.analysis.cpis.*
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.inliner.InlinerConfig
import sbf.inliner.inline
import sbf.output.annotateWithTypes
import sbf.slicer.sliceAndPTAOptLoop

private val cpiLog = Logger(LoggerTypes.CPI)

fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>  substituteCpiCalls(
    analysis: WholeProgramMemoryAnalysis<TNum, TOffset>,
    target: String,
    inliningConfig: InlinerConfig,
    startTime: Long
): SbfCallGraph {
    val p0 = analysis.program
    val cpiCalls = getCpiCalls(analysis)
    val p1 = replaceCpis(p0, cpiCalls)
    // We need to inline the code of the mocks for the CPI calls
    val p2 = inline(target, target, p1, analysis.memSummaries, inliningConfig)
    val p3 = annotateWithTypes(p2, analysis.memSummaries)
    // Since we injected new code, this might create new unreachable blocks
    val p4 = sliceAndPTAOptLoop(target, p3, analysis.memSummaries, startTime)
    if (SolanaConfig.PrintAnalyzedToDot.get()) {
        p4.toDot(ArtifactManagerFactory().outputDir, true)
    }
    return p4
}

/**
 * Returns [prog] where the CPI calls have been replaced with calls to their respective mocks.
 * For example, `call solana_program::program::invoke` could be substituted to
 * `call cvlr_solana::cpis::cvlr_invoke_transfer` if the invoked instruction is Token's transfer.
 */
private fun replaceCpis(prog: SbfCallGraph, cpiCalls: Iterable<CpiCall>): SbfCallGraph {
    return CpiReplacer.replaceCpis(prog, cpiCalls)
}

/**
 * A set of demangled function names that we use to replace calls to `invoke`.
 * These names correspond to high-level Rust function identifiers (demangled from their mangled symbol names).
 * It is important that these functions are preserved across different program transformations, otherwise it would not
 * be possible to inline them after substituting the calls to invoke with calls to the mocks.
 *
 * Example: `cvlr_solana::cpis::cvlr_invoke_transfer`
 */
val CPIS_MOCK_FUNCTION_NAMES: Set<String> = CpiReplacer.mockFunctionsNames

private object CpiReplacer {
    val mockFunctionsNames: Set<String>

    data class FunctionIdentifier(val demangledName: String, val mangledName: String) {
        fun getCallInstructionToThis(mockFor: String): List<SbfInstruction> {
            val metaData =
                MetaData()
                    .plus(SbfMeta.MANGLED_NAME to mangledName)
                    .plus(SbfMeta.MOCK_FOR to mockFor)
            return listOf(SbfInstruction.Call(demangledName, metaData = metaData))
        }
    }

    /**
     * A mock for a known [sbf.analysis.cpis.CpiInstruction].
     * [invokeMock] is the function that mocks the instruction when it is called with
     * `solana_program::instruction::invoke`, while [invokeSignedMock] mocks the instruction when
     * it is called with `solana_program::instruction::invoke_signed`.
     */
    data class InvokeMock(val invokeMock: FunctionIdentifier, val invokeSignedMock: FunctionIdentifier) {
        fun getInvokeReplacementInstructions(): List<SbfInstruction> {
            return invokeMock.getCallInstructionToThis(INVOKE_FUNCTION_NAME)
        }

        fun getInvokeSignedReplacementInstructions(): List<SbfInstruction> {
            return invokeSignedMock.getCallInstructionToThis(INVOKE_SIGNED_FUNCTION_NAME)
        }
    }

    val InvokeTransferMock = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_transfer",
            mangledName = "_ZN11cvlr_solana4cpis20cvlr_invoke_transfer17hf63e9306c568c048E"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_signed_transfer",
            mangledName = "_ZN11cvlr_solana4cpis27cvlr_invoke_signed_transfer17h9cf75576870ddcb5E"
        )
    )

    val InvokeMintToMock = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_mint_to",
            mangledName = "_ZN11cvlr_solana4cpis19cvlr_invoke_mint_to17hc448a2e751290a6cE"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_signed_mint_to",
            mangledName = "_ZN11cvlr_solana4cpis26cvlr_invoke_signed_mint_to17hb0f540d1263633eeE"
        )
    )

    val InvokeBurnMock = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_burn",
            mangledName = "_ZN11cvlr_solana4cpis16cvlr_invoke_burn17hbacfe5fffe9a3668E"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_signed_burn",
            mangledName = "_ZN11cvlr_solana4cpis23cvlr_invoke_signed_burn17h738b62d009b94b13E"
        )
    )

    val InvokeCloseAccount = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_close_account",
            mangledName = "_ZN11cvlr_solana4cpis21process_close_account17h2ec09c6c4e5fb81bE"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_signed_close_account",
            mangledName = "_ZN11cvlr_solana4cpis32cvlr_invoke_signed_close_account17h150c21d4b60508e6E"
        )
    )

    val InvokeTransferCheckedMock = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_transfer_checked",
            mangledName = "_ZN11cvlr_solana4cpis28cvlr_invoke_transfer_checked17hd0e646d73ad81589E"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_solana::cpis::cvlr_invoke_signed_transfer_checked",
            mangledName = "_ZN11cvlr_solana4cpis35cvlr_invoke_signed_transfer_checked17hb03a0e94805d7838E"
        )
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
        mockFunctionsNames =
            invokeMocks.flatMap { listOf(it.invokeMock.demangledName, it.invokeSignedMock.demangledName) }.toSet()
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
                    "Mock function `$mockFunctionName` is not available in the SBF call graph. " +
                        "This is likely be due to the fact that the function is not available in the compiled SBF code. " +
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
        val replacementInstructions = getReplacementInstructions(cpiCall)
        val replacementMap = mapOf(instructionToBeReplaced to replacementInstructions)
        block.replaceInstructions(replacementMap)
    }

    private fun getReplacementInstructions(cpiCall: CpiCall): List<SbfInstruction> {
        val invokeMock = when (cpiCall.cpiInstruction) {
            TokenInstruction.Transfer -> InvokeTransferMock
            TokenInstruction.MintTo -> InvokeMintToMock
            TokenInstruction.Burn -> InvokeBurnMock
            TokenInstruction.CloseAccount -> InvokeCloseAccount
            TokenInstruction.TransferChecked -> InvokeTransferCheckedMock
        }
        return when (cpiCall.invokeType) {
            InvokeType.Invoke -> invokeMock.getInvokeReplacementInstructions()
            InvokeType.InvokeSigned -> invokeMock.getInvokeSignedReplacementInstructions()
        }
    }
}
