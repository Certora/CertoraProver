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
import sbf.disassembler.GlobalVariables
import sbf.disassembler.SbfRegister
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import datastructures.stdcollections.*
import sbf.domains.*

private val cpiLog = Logger(LoggerTypes.SBF_CPI)

/** Demangled name of `invoke`. */
const val INVOKE_FUNCTION_NAME = "solana_program::program::invoke"

/** Demangled name of `invoke_signed`. */
const val INVOKE_SIGNED_FUNCTION_NAME = "solana_program::program::invoke_signed"

/** Offset from the start of an `Instruction` to the program id. */
private const val OFFSET_FROM_INSTRUCTION_TO_PROGRAM_ID = 48

/** Offset from the start of an `Instruction` to the data vector. */
private const val OFFSET_FROM_INSTRUCTION_TO_DATA_VEC = 24

/** Offset from the start of the data vector to the discriminant of the instruction that is being called for Token. */
private const val OFFSET_FROM_DATA_VECTOR_TO_TOKEN_INSTRUCTION_DISCRIMINANT = 0


/** A call to `invoke` or `invoke_signed` in a specific location. */
data class LocatedInvoke(val cfg: SbfCFG, val inst: LocatedSbfInstruction, val type: InvokeType)


/** The type of the `invoke` instruction, which can be either a simple `invoke`, or an `invoke_signed` */
sealed interface InvokeType {
    data object Invoke : InvokeType
    data object InvokeSigned : InvokeType
}


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


/**
 * Listens to the analysis, and once an invoke that is in [invokes] is found, analyzes the memory domain trying to
 * determine which program and which instruction is being called.
 * Can return the inferred instructions with the [getCpis] method.
 * The parameter [cpisSubstitutionMap] describes how to associate program IDs to specific mocking functions.
 */
class InvokeInstructionListener<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>(
    private val cpisSubstitutionMap: CpisSubstitutionMap, val invokes: Iterable<LocatedInvoke>, val globals: GlobalVariables
) : InstructionListener<MemoryDomain<TNum, TOffset, TFlags>> {

    private val cpiInstructions: MutableMap<LocatedInvoke, InvokeMock?> = mutableMapOf()

    /**
     * Returns a map that associates each `invoke` instruction with a resolution result (which is resolved or unresolved).
     * If the result for an `invoke` is `null`, the CPI has not been resolved.
     * If the result for an `invoke` is not `null`, the CPI has been resolved.
     * Observe that `invoke` should *not* be inlined, as this analysis detects `call solana_program::program::invoke`
     * and `call solana_program::program::invoke_signed`.
     */
    fun getCpis(): Map<LocatedInvoke, InvokeMock?> {
        return cpiInstructions
    }

    override fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: MemoryDomain<TNum, TOffset, TFlags>) {
        for (invoke in invokes) {
            val inst = invoke.inst
            if (locInst.label == inst.label && locInst.pos == inst.pos) {
                cpiLog.info { "Analyzing located instruction `$inst`" }

                // Found the call to `invoke` that we were looking for.
                val programId = getProgramIdBeforeInvoke(pre)
                if (programId == null) {
                    cpiLog.warn {
                        "It was not possible to infer the program id before the call to invoke. " +
                            "This is usually due a loss of precision of the static analysis. " +
                            "A possible fix is adding `#[cvlr::early_panic]` to functions that call invoke."
                    }
                    cpiInstructions[invoke] = null
                    return
                }
                cpiLog.info { "Found program id: $programId" }


                val discriminants = cpisSubstitutionMap.map[programId]
                if (discriminants == null) {
                    cpiLog.warn { "Program id `$programId` does not correspond to any known Solana program" }
                    cpiInstructions[invoke] = null
                    return
                }

                val instruction = getInstruction(discriminants, pre)
                if (instruction == null) {
                    cpiLog.warn { "Could not infer program instruction: analysis is not precise enough" }
                } else {
                    cpiLog.info { "Success: invoke `${invoke.inst}` must be substituted to instruction `$instruction`" }
                }
                cpiInstructions[invoke] = instruction
            }
        }
    }

    override fun instructionEventAfter(locInst: LocatedSbfInstruction, post: MemoryDomain<TNum, TOffset, TFlags>) {}

    override fun instructionEvent(
        locInst: LocatedSbfInstruction,
        pre: MemoryDomain<TNum, TOffset, TFlags>,
        post: MemoryDomain<TNum, TOffset, TFlags>
    ) {
    }
}


/**
 * Explores the abstract state before a CPI call trying to infer the program id.
 * If the memory analysis is not precise enough to infer the program id, returns `null`.
 * We know that before calling `invoke` register R2 points to the `Instruction`, and 48 bytes after the program
 * id is encoded in 32 bytes.
 */
private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> getProgramIdBeforeInvoke(
    memoryDomain: MemoryDomain<TNum, TOffset, TFlags>
): ProgramId? {
    val r2 = Value.Reg(SbfRegister.R2_ARG)
    val pubkey = memoryDomain.getPubkey(r2, OFFSET_FROM_INSTRUCTION_TO_PROGRAM_ID.toLong()) ?: return null
    return ProgramId(pubkey.word0, pubkey.word1, pubkey.word2, pubkey.word3)
}


/**
 * If R2 points to an `Instruction` for which we can follow the pointer to the `data` vector, and in that vector
 * the discriminant is known and can be converted into a [InvokeMock], return such mock.
 * Otherwise, return `null`.
 */
private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> getInstruction(
    discriminants: ProgramDiscriminants,
    memoryDomain: MemoryDomain<TNum, TOffset, TFlags>
): InvokeMock? {
    val r2 = memoryDomain.getRegCell(Value.Reg(SbfRegister.R2_ARG))
    if (r2 == null) {
        cpiLog.warn { "Could not infer register cell associated with R2" }
        return null
    }

    val pointerToInstruction = r2.concretize()
    if (!pointerToInstruction.getNode().isExactNode()) {
        cpiLog.warn { "The PTA node pointed by register R2 is not exact" }
        return null
    }
    val offsetToDataVector =
        pointerToInstruction.getOffset() + OFFSET_FROM_INSTRUCTION_TO_DATA_VEC +
            SolanaConfig.RustVecLayout.get().getDataOffset()

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
        PTAField(instructionDiscriminantOffset, size = discriminants.numBytesDiscriminant)
    val pointedInstructionDiscriminant = dataArray.getNode().getSucc(instructionDiscriminantPointer)
    if (pointedInstructionDiscriminant == null) {
        cpiLog.warn { "Cannot resolve pointer to instruction discriminant" }
        return null
    }
    if (!pointedInstructionDiscriminant.getNode().mustBeInteger()) {
        cpiLog.warn { "PTA node pointing to instruction discriminant is not precise enough (could be something that is not an integer)" }
        return null
    }

    val instructionDiscriminant = pointedInstructionDiscriminant.getNode().flags.getInteger().toLongOrNull()?.toULong()
    if (instructionDiscriminant == null) {
        cpiLog.warn { "PTA node pointing to instruction discriminant is not precise enough (numeric value is not precise enough)" }
        return null
    }

    val mockFunction: InvokeMock? = discriminants.discriminants[instructionDiscriminant]
    if (mockFunction == null) {
        cpiLog.info { "Could not convert discriminant `$instructionDiscriminant` to program instruction" }
        return null
    }
    return mockFunction
}
