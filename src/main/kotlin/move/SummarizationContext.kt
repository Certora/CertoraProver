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

package move

import analysis.*
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.*
import tac.generation.*
import vc.data.*

/**
    Methods and state to help with summarization of Move functions.
 */
class SummarizationContext(
    val scene: MoveScene,
    private val moveToTAC: MoveToTAC,
    /** Maps MoveType.Function values (which are integers) to their corresponding function instantiations */
    val parametricTargets: Map<Int, MoveFunction>
) {
    private var bodyIdxAllocator = 1 // Start at 1 to avoid conflict with Allocator.getNBId()
    private var currentBodyIdx = bodyIdxAllocator++

    fun <T> withNewBodyIdx(block: () -> T): T {
        val prev = currentBodyIdx
        currentBodyIdx = bodyIdxAllocator++
        try {
            return block()
        } finally {
            currentBodyIdx = prev
        }
    }

    private val blockIdAllocator = mutableMapOf<NBId, Int>()

    fun newBlockId(
        origStartPc: Int,
        bodyIdx: Int = currentBodyIdx
    ): NBId =
        BlockIdentifier(
            origStartPc = origStartPc,
            stkTop = bodyIdx, // we repurpose this to distinguish between different function bodies
            decompCopy = 0,
            calleeIdx = 0,
            topOfStackValue = 0,
            freshCopy = 0
        ).let {
            val copy = blockIdAllocator[it] ?: 0
            blockIdAllocator[it] = copy + 1
            it.copy(decompCopy = copy)
        }

    val NBId.bodyIdx get() = stkTop // we repurpose this to distinguish between different function bodies

    fun newBlockId(template: NBId) = newBlockId(template.origStartPc, template.bodyIdx)

    // Placeholders for assert/satisfy IDs.  We will fill these in after the Move TAC is fully explanded
    val SATISFY_ID_PLACEHOLDER get() = -1
    val ASSERT_ID_PLACEHOLDER get() = -2

    /**
        Construct a single-block summary for a Move function call.
     */
    inline fun singleBlockSummary(
        call: MoveCall,
        commands: () -> MoveCmdsWithDecls,
    ): MoveBlocks = treapMapOf(
        call.entryBlock to mergeMany(
            commands(),
            TACCmd.Simple.JumpCmd(call.returnBlock).withDecls()
        )
    )

    /**
        Call back into MoveToTAC to compile a function call
     */
    fun compileFunctionCall(call: MoveCall) = moveToTAC.compileFunctionCall(call)

    /**
        Call back into MoveToTAC to compile a function as a subprogram
     */
    fun compileSubprogram(
        entryFunc: MoveFunction,
        args: List<TACSymbol.Var>,
        returns: List<TACSymbol.Var>
    ) = moveToTAC.compileSubprogram(entryFunc, args, returns)

    private val previouslyInitialized = mutableSetOf<Initializer>()
    private val initializers = mutableSetOf<Initializer>()

    /** Run `initializer.initialization` once at the start of the TAC program. */
    fun ensureInit(initializer: Initializer) {
        if (initializer !in previouslyInitialized) {
            initializers += initializer
        }
    }

    /** Havoc the given symbol once at the start of the TAC program.  */
    fun TACSymbol.Var.ensureHavocInit(type: MoveType? = null): TACSymbol.Var =
        apply { ensureInit(HavocInitializer(this, type)) }

    fun getAndResetInitialization() =
        mergeMany(initializers.map { it.initialize() } ).also {
            previouslyInitialized += initializers
            initializers.clear()
        }

    /**
        Produces a sequence of TAC commands to be executed at the start of the TAC program.  Subclasses must implement
        `equals` and `hashCode` appropriately to ensure that the same initialization is not added multiple times.
     */
    abstract class Initializer {
        abstract override fun equals(other: Any?): Boolean
        abstract override fun hashCode(): Int
        abstract fun initialize(): MoveCmdsWithDecls
    }

    private data class HavocInitializer(val sym: TACSymbol.Var, val type: MoveType?) : Initializer() {
        override fun initialize() = type?.assignHavoc(sym) ?: assignHavoc(sym)
    }
}
