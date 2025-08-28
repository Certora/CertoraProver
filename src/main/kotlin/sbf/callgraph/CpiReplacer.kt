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
import sbf.analysis.cpis.INVOKE_FUNCTION_NAME
import sbf.analysis.cpis.INVOKE_SIGNED_FUNCTION_NAME
import sbf.analysis.cpis.InvokeType
import sbf.analysis.cpis.TokenInstruction
import log.*
import sbf.SolanaConfig
import sbf.analysis.WholeProgramMemoryAnalysis
import sbf.analysis.cpis.CpiInstruction
import sbf.analysis.cpis.LocatedInvoke
import sbf.cfg.BinOp
import sbf.cfg.CondOp
import sbf.cfg.Condition
import sbf.cfg.Value
import sbf.disassembler.SbfRegister
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.inliner.InlinerConfig
import sbf.inliner.inline
import sbf.output.annotateWithTypes
import sbf.slicer.sliceAndPTAOptLoop

private val cpiLog = Logger(LoggerTypes.CPI)

fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>> substituteCpiCalls(
    analysis: WholeProgramMemoryAnalysis<TNum, TOffset>,
    target: String,
    cpiCalls: Map<LocatedInvoke, CpiInstruction?>,
    inliningConfig: InlinerConfig,
    startTime: Long
): SbfCallGraph {
    val p0 = analysis.program
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
 * `call cvlr_spl_token::cpis::cvlr_invoke_transfer` if the invoked instruction is Token's transfer.
 */
private fun replaceCpis(prog: SbfCallGraph, cpiCalls: Map<LocatedInvoke, CpiInstruction?>): SbfCallGraph {
    return CpiReplacer.replaceCpis(prog, cpiCalls)
}

/**
 * A set of demangled function names that we use to replace calls to `invoke`.
 * These names correspond to high-level Rust function identifiers (demangled from their mangled symbol names).
 * It is important that these functions are preserved across different program transformations, otherwise it would not
 * be possible to inline them after substituting the calls to invoke with calls to the mocks.
 *
 * Example: `cvlr_spl_token::cpis::cvlr_invoke_transfer`
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
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_transfer",
            mangledName = "_ZN11cvlr_solana4cpis20cvlr_invoke_transfer17hf63e9306c568c048E"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_transfer",
            mangledName = "_ZN11cvlr_solana4cpis27cvlr_invoke_signed_transfer17h9cf75576870ddcb5E"
        )
    )

    val InvokeMintToMock = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_mint_to",
            mangledName = "_ZN11cvlr_solana4cpis19cvlr_invoke_mint_to17hc448a2e751290a6cE"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_mint_to",
            mangledName = "_ZN11cvlr_solana4cpis26cvlr_invoke_signed_mint_to17hb0f540d1263633eeE"
        )
    )

    val InvokeBurnMock = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_burn",
            mangledName = "_ZN11cvlr_solana4cpis16cvlr_invoke_burn17hbacfe5fffe9a3668E"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_burn",
            mangledName = "_ZN11cvlr_solana4cpis23cvlr_invoke_signed_burn17h738b62d009b94b13E"
        )
    )

    val InvokeCloseAccount = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_close_account",
            mangledName = "_ZN11cvlr_solana4cpis21process_close_account17h2ec09c6c4e5fb81bE"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_close_account",
            mangledName = "_ZN11cvlr_solana4cpis32cvlr_invoke_signed_close_account17h150c21d4b60508e6E"
        )
    )

    val InvokeTransferCheckedMock = InvokeMock(
        invokeMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_transfer_checked",
            mangledName = "_ZN11cvlr_solana4cpis28cvlr_invoke_transfer_checked17hd0e646d73ad81589E"
        ),
        invokeSignedMock = FunctionIdentifier(
            demangledName = "cvlr_spl_token::cpis::cvlr_invoke_signed_transfer_checked",
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

    /** The message of the asserts that are injected by the replacer when a CPI call cannot be resolved. */
    const val UNRESOLVED_CPI_CALL_ASSERT_COMMENT = "Unresolved CPI call"

    init {
        mockFunctionsNames =
            invokeMocks.flatMap { listOf(it.invokeMock.demangledName, it.invokeSignedMock.demangledName) }.toSet()
    }

    fun replaceCpis(prog: SbfCallGraph, cpiCalls: Map<LocatedInvoke, CpiInstruction?>): SbfCallGraph {
        warnIfMocksAreMissing(prog)
        return prog.transformSingleEntry { cfg ->
            val cfgAfterReplacingCpiCalls = cfg.clone(cfg.getName())
            for ((locatedInvoke, cpiResolutionResult) in cpiCalls) {
                if (cfg.getName() == locatedInvoke.cfg.getName()) {
                    replaceCpi(cfgAfterReplacingCpiCalls, locatedInvoke, cpiResolutionResult)
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

    private fun replaceCpi(cfg: MutableSbfCFG, locatedInvoke: LocatedInvoke, cpiResolutionResult: CpiInstruction?) {
        val block = cfg.getMutableBlock(locatedInvoke.inst.label)
        if (block == null) {
            cpiLog.warn { "Could not find block with label '${locatedInvoke.inst.label}' in CFG ${cfg.getName()}" }
        } else {
            replaceCpi(block, locatedInvoke, cpiResolutionResult)
        }
    }

    private fun replaceCpi(
        block: MutableSbfBasicBlock,
        locatedInvoke: LocatedInvoke,
        cpiResolutionResult: CpiInstruction?
    ) {
        val instructionToBeReplaced = locatedInvoke.inst
        val replacementInstructions = getReplacementInstructions(locatedInvoke, cpiResolutionResult)
        val replacementMap = mapOf(instructionToBeReplaced to replacementInstructions)
        block.replaceInstructions(replacementMap)
    }

    private fun getReplacementInstructions(
        locatedInvoke: LocatedInvoke,
        cpiResolutionResult: CpiInstruction?
    ): List<SbfInstruction> {
        if (cpiResolutionResult != null) {
            val invokeMock = when (cpiResolutionResult) {
                TokenInstruction.Transfer -> InvokeTransferMock
                TokenInstruction.MintTo -> InvokeMintToMock
                TokenInstruction.Burn -> InvokeBurnMock
                TokenInstruction.CloseAccount -> InvokeCloseAccount
                TokenInstruction.TransferChecked -> InvokeTransferCheckedMock
            }
            return when (locatedInvoke.type) {
                InvokeType.Invoke -> invokeMock.getInvokeReplacementInstructions()
                InvokeType.InvokeSigned -> invokeMock.getInvokeSignedReplacementInstructions()
            }
        } else {
            // To prevent unsoundness, unresolved CPI calls are substituted with `assert(false)`.
            return assertFalse(UNRESOLVED_CPI_CALL_ASSERT_COMMENT)
        }
    }

    /**
     * Returns the list of SBF instructions that encode `assert(false)`.
     */
    private fun assertFalse(msg: String): List<SbfInstruction> {
        return listOf(
            // R1 = 1
            SbfInstruction.Bin(BinOp.MOV, Value.Reg(SbfRegister.R1_ARG), Value.Imm(1UL), is64 = true),
            // assert R1 == 0
            SbfInstruction.Assert(
                Condition(CondOp.EQ, Value.Reg(SbfRegister.R1_ARG), right = Value.Imm(0UL)),
                MetaData(SbfMeta.COMMENT to msg)
            )
        )
    }
}
