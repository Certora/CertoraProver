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

import log.Logger
import log.LoggerTypes
import sbf.SolanaConfig
import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.GlobalVariableMap
import sbf.disassembler.SbfRegister
import sbf.domains.InstructionListener
import sbf.domains.MemorySummaries
import sbf.domains.PTAField
import sbf.analysis.MemoryAnalysis
import sbf.analysis.WholeProgramMemoryAnalysis
import sbf.domains.ConstantSet
import sbf.domains.MemoryDomain
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

private val cpiLog = Logger(LoggerTypes.CPI)

/** Type of the memory domain. */
private typealias MemoryDomainT = MemoryDomain<ConstantSet, ConstantSet>

/**
 * Returns the list of CPI calls that the analysis was able to infer.
 * This list is in principle not complete as we rely on static analysis to infer the program ids and the instructions
 * being called.
 * Observe that `invoke` should *not* be inlined, as this analysis detects `call solana_program::program::invoke` and
 * `call solana_program::program::invoke_signed`.
 */
fun getCpiCalls(analysis: WholeProgramMemoryAnalysis): List<CpiCall> {
    return CpiAnalysis(analysis.program, analysis.getResults(), analysis.memSummaries).getCpiCalls()
}

/** A call to `invoke` in a specific location. */
data class LocatedInvoke(val cfg: SbfCFG, val inst: LocatedSbfInstruction, val type: InvokeType)

/**
 * Returns a list of instructions in the program that are calls to `invoke` instructions.
 * This includes both `invoke` and `invoke_signed`.
 * Note that this method does *not* consider calls to `invoke` that have been inlined.
 * Therefore, it is important to avoid inlining calls to `invoke`.
 * However, if this function detects that a call to `invoke` has been inlined, it emits a warning.
 */
fun getInvokes(analyzedProg: SbfCallGraph): List<LocatedInvoke> {
    val invokes = mutableListOf<LocatedInvoke>()
    for (cfg in analyzedProg.getCFGs()) {
        for ((_, block) in cfg.getBlocks()) {
            val locatedInstructions = block.getLocatedInstructions()
            for (locatedInstruction in locatedInstructions) {
                val instruction = locatedInstruction.inst

                if (instruction is SbfInstruction.Call) {
                    if (instruction.name == INVOKE_FUNCTION_NAME) {
                        invokes.add(LocatedInvoke(cfg, locatedInstruction, InvokeType.Invoke))
                    }
                    if (instruction.name == INVOKE_SIGNED_FUNCTION_NAME) {
                        invokes.add(LocatedInvoke(cfg, locatedInstruction, InvokeType.InvokeSigned))
                    }
                }

                // We also check that `invoke` has *not* been inlined, and if this is the case, we emit a warning.
                val inlinedFunctionName: String? = instruction.metaData.getVal(SbfMeta.INLINED_FUNCTION_NAME)
                val invokeHasBeenInlined =
                    (inlinedFunctionName == INVOKE_FUNCTION_NAME || inlinedFunctionName == INVOKE_SIGNED_FUNCTION_NAME) &&
                        instruction is SbfInstruction.Call &&
                        CVTFunction.from(instruction.name) == CVTFunction.Core(CVTCore.SAVE_SCRATCH_REGISTERS)
                if (invokeHasBeenInlined) {
                    cpiLog.warn {
                        "SBF instruction `$locatedInstruction` is an inlined call to invoke. " +
                            "For the CPIs analysis to work correctly, calls to invoke should *not* be inlined. " +
                            "Modify the inlining file (${SolanaConfig.InlineFilePath.get()}) to never inline calls to `invoke`, and provide a summary " +
                            "in the summaries file (${SolanaConfig.SummariesFilePath.get()})."
                    }
                }
            }
        }
    }
    return invokes
}


const val INVOKE_FUNCTION_NAME = "solana_program::program::invoke"
const val INVOKE_SIGNED_FUNCTION_NAME = "solana_program::program::invoke_signed"

private data class CpiAnalysis(
    val analyzedProg: SbfCallGraph,
    val analysisResults: Map<String, MemoryAnalysis<ConstantSet, ConstantSet>>,
    val memSummaries: MemorySummaries
) {

    fun getCpiCalls(): List<CpiCall> {
        val invokes = getInvokes(analyzedProg)
        cpiLog.info { "List of calls to invoke: ${invokes.map { "${it.type} : ${it.inst}" }}" }
        val cpiCalls = mutableListOf<CpiCall>()
        for ((invokeCfg, locatedInvoke, invokeType) in invokes) {
            val basicBlock = invokeCfg.getBlock(locatedInvoke.label)
            check(basicBlock != null) { "Instruction `$locatedInvoke` points to a block that is not in CFG `${invokeCfg.getName()}`" }
            val abstractStateAtEntrypoint = analysisResults[invokeCfg.getName()]
            // Get the pre-state at the beginning of the block that calls `invoke`.
            val preAtInvokeBlock = abstractStateAtEntrypoint?.getPre(basicBlock.getLabel())
            check(preAtInvokeBlock != null) { "Expected pre-state before basic block with invoke" }
            val listener = InvokeInstructionListener(locatedInvoke, analyzedProg.getGlobals())
            // Repeat the analysis so that the listener can detect what is the pre-state before the call to `invoke`
            // and potentially infer a called CPI instruction.
            preAtInvokeBlock.analyze(
                b = basicBlock,
                globals = analyzedProg.getGlobals(),
                memSummaries = memSummaries,
                listener = listener
            )
            val inferredInstruction = listener.getInferredInstruction()
            if (inferredInstruction != null) {
                cpiCalls.add(CpiCall(invokeCfg, locatedInvoke, inferredInstruction, invokeType))
            }
        }
        cpiLog.info { "Inferred CPI calls: $cpiCalls" }
        return cpiCalls
    }


    /**
     * Listens to the analysis, and once [locatedInvoke] is found, analyzes the memory domain trying to determine which
     * instruction is being called.
     */
    private class InvokeInstructionListener(
        val locatedInvoke: LocatedSbfInstruction,
        val globals: GlobalVariableMap
    ) : InstructionListener<MemoryDomainT> {

        /** If the listener can detect which instruction is called, it will store the instruction in this variable. */
        private var cpiInstruction: CpiInstruction? = null

        /**
         * After the analysis, returns the inferred instruction. If we were not able to infer the instruction that is
         * being called, returns `null`.
         */
        fun getInferredInstruction(): CpiInstruction? = cpiInstruction

        override fun instructionEventBefore(
            locInst: LocatedSbfInstruction,
            pre: MemoryDomainT
        ) {
            if (locInst.label == this.locatedInvoke.label && locInst.pos == this.locatedInvoke.pos) {
                if (cpiInstruction != null) {
                    cpiLog.warn { "Possibly overwriting already-inferred CPI instruction" }
                }
                // Found the call to `invoke` that we were looking for.
                val programId = getProgramIdBeforeInvoke(pre, globals)
                if (programId == null) {
                    cpiLog.warn {
                        "It was not possible to infer the program id before the call to invoke. " +
                            "This is usually due a loss of precision of the static analysis. " +
                            "A possible fix is adding `#[cvlr::early_panic]` to functions that call invoke."
                    }
                    return
                }
                cpiLog.info { "Found program id: $programId" }

                val program = Program.from(programId)
                if (program == null) {
                    cpiLog.warn { "Program id `$programId` does not correspond to any known Solana program" }
                    return
                }
                cpiLog.info { "Inferred a CPI call to the following program: $program" }

                when (program) {
                    Program.SystemProgram -> {
                        cpiLog.warn { "Found system program: no instruction is supported yet" }
                    }

                    Program.Token -> {
                        cpiLog.info { "Found token program: trying to infer called instruction" }
                        val instruction = getTokenProgramInstruction(pre, globals)
                        if (instruction == null) {
                            cpiLog.warn { "Could not infer Token program instruction: analysis is not precise enough" }
                        } else {
                            cpiInstruction = instruction
                        }
                    }
                }
            }
        }

        override fun instructionEventAfter(
            locInst: LocatedSbfInstruction,
            post: MemoryDomainT
        ) {
        }

        override fun instructionEvent(
            locInst: LocatedSbfInstruction,
            pre: MemoryDomainT,
            post: MemoryDomainT
        ) {
        }
    }

    companion object {

        /** Offset from the start of an `Instruction` to the program id. */
        const val OFFSET_FROM_INSTRUCTION_TO_PROGRAM_ID = 48

        /** Offset from the start of an `Instruction` to the data vector. */
        const val OFFSET_FROM_INSTRUCTION_TO_DATA_VECTOR = 24

        /** Offset from the start of the data vector to the discriminant of the instruction that is being called for Token. */
        const val OFFSET_FROM_DATA_VECTOR_TO_TOKEN_INSTRUCTION_DISCRIMINANT = 0

        /** Size (in bytes) of the discriminant of a Token instruction. */
        const val TOKEN_INSTRUCTION_DISCRIMINANT_SIZE: Short = 1

        /**
         * Explores the abstract state before a CPI call trying to infer the program id.
         * If the memory analysis is not precise enough to infer the program id, returns `null`.
         * We know that before calling `invoke` register R2 points to the `Instruction`, and 48 bytes after the program
         * id is encoded in 32 bytes.
         */
        private fun getProgramIdBeforeInvoke(
            memoryDomain: MemoryDomainT,
            globals: GlobalVariableMap
        ): Program.ProgramId? {
            val r2 = memoryDomain.getRegCell(Value.Reg(SbfRegister.R2_ARG), globals)
            if (r2 == null) {
                cpiLog.warn { "Could not infer register cell associated with R2" }
                return null
            }

            val pointerToInstruction = r2.concretize()
            if (!pointerToInstruction.getNode().isExactNode()) {
                cpiLog.warn { "The PTA node pointed by register R2 is not exact" }
                return null
            }
            val offsetToProgramId = pointerToInstruction.getOffset() + OFFSET_FROM_INSTRUCTION_TO_PROGRAM_ID

            // We know collect the chunks composing the program id.
            // Currently, we expect that the four u64s that compose the program id (overall, 32 bytes) are explicitly
            // written to memory. This will change in principle in the future once the value analysis becomes more
            // precise.
            // For now, we then expect to collect four chunks of the program id (each one is a u64).
            val chunks = mutableListOf<ULong>()
            for (i in 0 until 4) {
                val field = PTAField(offsetToProgramId + (8 * i), 8)
                val instructionIdChunk = pointerToInstruction.getNode().getSucc(field)
                if (instructionIdChunk == null) {
                    cpiLog.warn { "Cannot infer program id chunk" }
                    return null
                }
                if (!instructionIdChunk.getNode().mustBeInteger()) {
                    // We want only precise values.
                    cpiLog.warn { "PTA node pointing to chunk of program id is not precise enough (could be something that is not an integer)" }
                    return null
                }
                val chunk = instructionIdChunk.getNode().integerValue.toLongOrNull()?.toULong()
                if (chunk == null) {
                    cpiLog.warn { "PTA node pointing to chunk of program id is not precise enough (numeric value is not precise enough)" }
                    return null
                }
                chunks.add(chunk)
            }

            return if (chunks.size == 4) {
                Program.ProgramId(chunks[0], chunks[1], chunks[2], chunks[3])
            } else {
                null
            }
        }

        /**
         * If R2 points to an `Instruction` for which we can follow the pointer to the `data` vector, and in that vector
         * the discriminant is known and can be converted into a [TokenInstruction], return such instruction.
         * Otherwise, return `null`.
         */
        private fun getTokenProgramInstruction(
            memoryDomain: MemoryDomainT,
            globals: GlobalVariableMap
        ): TokenInstruction? {
            val r2 = memoryDomain.getRegCell(Value.Reg(SbfRegister.R2_ARG), globals)
            if (r2 == null) {
                cpiLog.warn { "Could not infer register cell associated with R2" }
                return null
            }

            val pointerToInstruction = r2.concretize()
            if (!pointerToInstruction.getNode().isExactNode()) {
                cpiLog.warn { "The PTA node pointed by register R2 is not exact" }
                return null
            }
            val offsetToDataVector = pointerToInstruction.getOffset() + OFFSET_FROM_INSTRUCTION_TO_DATA_VECTOR

            // We now try to follow the pointer to the data vector.
            val pointerToDataField = PTAField(offsetToDataVector, size = 8)
            val dataArray = pointerToInstruction.getNode().getSucc(pointerToDataField)
            if (dataArray == null) {
                cpiLog.warn { "Cannot resolve pointer to data vector in the heap for instruction " }
                return null
            }

            // We now try to resolve the instruction that is being called.
            // To do this, we read the discriminant of the instruction, which is encoded as a byte at the beginning
            // of the data vector (offset 0).
            val instructionDiscriminantOffset =
                dataArray.getOffset() + OFFSET_FROM_DATA_VECTOR_TO_TOKEN_INSTRUCTION_DISCRIMINANT
            val instructionDiscriminantPointer =
                PTAField(instructionDiscriminantOffset, size = TOKEN_INSTRUCTION_DISCRIMINANT_SIZE)
            val pointedInstructionDiscriminant = dataArray.getNode().getSucc(instructionDiscriminantPointer)
            if (pointedInstructionDiscriminant == null) {
                cpiLog.warn { "Cannot resolve pointer to Token instruction discriminant" }
                return null
            }
            if (!pointedInstructionDiscriminant.getNode().mustBeInteger()) {
                cpiLog.warn { "PTA node pointing to Token instruction discriminant is not precise enough (could be something that is not an integer)" }
                return null
            }

            val instructionDiscriminant = pointedInstructionDiscriminant.getNode().integerValue.toLongOrNull()
            if (instructionDiscriminant == null) {
                cpiLog.warn { "PTA node pointing to Token instruction discriminant is not precise enough (numeric value is not precise enough)" }
                return null
            }

            val tokenProgramInstruction = TokenInstruction.from(instructionDiscriminant)
            if (tokenProgramInstruction == null) {
                cpiLog.info { "Could not convert discriminant `$instructionDiscriminant` to Token program instruction" }
                return null
            }
            return tokenProgramInstruction
        }
    }
}
