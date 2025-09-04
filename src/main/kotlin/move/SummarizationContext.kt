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
    private var blockIdAllocator = 0
    fun newBlockId() =
        BlockIdentifier(
            ++blockIdAllocator,
            stkTop = 1, // avoids conflicts with Allocator.getNBId()
            0, 0, 0, 0
        )

    private var satisfyCount = 0
    /** Allocate a new SATISFY_ID */
    fun allocSatisfyId() = satisfyCount++

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

    private val initializers = mutableSetOf<Initializer>()

    /** Run `initializer.initialization` once at the start of the TAC program. */
    fun ensureInit(initializer: Initializer) {
        initializers += initializer
    }

    /** Havoc the given symbol once at the start of the TAC program.  */
    fun TACSymbol.Var.ensureHavocInit(type: MoveType? = null): TACSymbol.Var =
        apply { ensureInit(HavocInitializer(this, type)) }

    fun getAndResetInitialization() = mergeMany(initializers.map { it.initialize() } ).also { initializers.clear() }

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
