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
import log.*
import sbf.SolanaConfig
import sbf.analysis.WholeProgramMemoryAnalysis
import sbf.analysis.cpis.InvokeMock
import sbf.analysis.cpis.InvokeType
import sbf.analysis.cpis.LocatedInvoke
import sbf.cfg.BinOp
import sbf.cfg.CondOp
import sbf.cfg.Condition
import sbf.cfg.Value
import sbf.disassembler.SbfRegister
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import sbf.inliner.InlinerConfig
import sbf.inliner.inline
import sbf.output.annotateWithTypes
import sbf.slicer.sliceAndPTAOptLoop

private val cpiLog = Logger(LoggerTypes.SBF_CPI)

fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> substituteCpiCalls(
    analysis: WholeProgramMemoryAnalysis<TNum, TOffset, TFlags>,
    target: String,
    cpiCalls: Map<LocatedInvoke, InvokeMock?>,
    inliningConfig: InlinerConfig
): SbfCallGraph {
    val p0 = analysis.program
    if (cpiLog.isEnabled && SolanaConfig.PrintAnalyzedToDot.get()) {
        annotateWithTypes(p0, analysis.memSummaries).also {
            it.toDot(ArtifactManagerFactory().outputDir, onlyEntryPoint = true, ".before.cpi.sbf.dot")
        }
    }
    val p1 = replaceCpis(p0, cpiCalls)
    // We need to inline the code of the mocks for the CPI calls
    val p2 = inline(target, target, p1, analysis.memSummaries, inliningConfig)
    // Since we injected new code, this might create new unreachable blocks
    val p3 = sliceAndPTAOptLoop(target, p2, analysis.memSummaries)
    if (cpiLog.isEnabled && SolanaConfig.PrintAnalyzedToDot.get()) {
        annotateWithTypes(p3, analysis.memSummaries).also {
           it.toDot(ArtifactManagerFactory().outputDir, onlyEntryPoint = true, ".after.cpi.sbf.dot")
        }
    }
    return p3
}

/**
 * Returns [prog] where the CPI calls have been replaced with calls to their respective mocks.
 * For example, `call solana_program::program::invoke` could be substituted to
 * `call cvlr_spl_token::cpis::cvlr_invoke_transfer` if the invoked instruction is Token's transfer.
 */
private fun replaceCpis(prog: SbfCallGraph, cpiCalls: Map<LocatedInvoke, InvokeMock?>): SbfCallGraph {
    return CpiReplacer.replaceCpis(prog, cpiCalls)
}

private object CpiReplacer {

    /** The message of the asserts that are injected by the replacer when a CPI call cannot be resolved. */
    const val UNRESOLVED_CPI_CALL_ASSERT_COMMENT = "Unresolved CPI call"

    fun replaceCpis(prog: SbfCallGraph, cpiCalls: Map<LocatedInvoke, InvokeMock?>): SbfCallGraph {
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

    private fun replaceCpi(cfg: MutableSbfCFG, locatedInvoke: LocatedInvoke, cpiResolutionResult: InvokeMock?) {
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
        cpiResolutionResult: InvokeMock?
    ) {
        val instructionToBeReplaced = locatedInvoke.inst
        val replacementInstructions = getReplacementInstructions(cpiResolutionResult, locatedInvoke.type)
        val replacementMap = mapOf(instructionToBeReplaced to replacementInstructions)
        block.replaceInstructions(replacementMap)
    }


    private fun getReplacementInstructions(
        cpiResolutionResult: InvokeMock?,
        invokeType: InvokeType
    ): List<SbfInstruction> {
        return cpiResolutionResult?.let {
            when (invokeType) {
                InvokeType.Invoke -> it.getInvokeReplacementInstructions()
                InvokeType.InvokeSigned -> it.getInvokeSignedReplacementInstructions()
            }
        } ?: assertFalse(UNRESOLVED_CPI_CALL_ASSERT_COMMENT)
        // To prevent unsoundness, unresolved CPI calls are substituted with `assert(false)`.
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
