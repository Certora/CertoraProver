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

@file:kotlinx.serialization.UseSerializers(BigIntegerSerializer::class)
package analysis.storage

import analysis.CmdPointer
import analysis.numeric.IntValue
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.NBId
import utils.*
import vc.data.ConditionalBlockSummary
import vc.data.TACExpr
import vc.data.TACSummary
import vc.data.TACSymbol
import vc.data.tacexprutil.DefaultTACExprTransformer
import java.math.BigInteger

/**
 * This class summarizes solidity-generated loops that are used to copy data between memory and storage.
 *
 * For non-packed values, this is straightforward:
 *
 * while (memptr < K) {
 *   vm = M[memptr]
 *   S[storageptr] = vm
 *   memptr += 32
 *   storageptr++
 * }
 *
 * or
 *
 * do {
 *   vs = S[storageptr]
 *   M[memptr] = vs
 *   memptr += 32
 *   storageptr++
 * }
 *
 * Packed values are copied via a sequence of loops, for a value that's B <= EVM_WORD_LENGTH/2 bytes wide:
 *
 * // Packed copy loop:
 * byteOffset := 0
 * while (memptr < K) {
 *   vm = M[memptr]
 *   vs = S[storageptr]
 *   // bit ops nonsense to set the proper byte
 *   S[storageptr] = vs'
 *   memptr += 32
 *   nextByteOffset = byteOffset + B
 *   slotOverflow = (nextByteOffset + B-1)/32
 *   storageptr = storageptr + slotOverflow
 *   byteOffset = (1 - slotOverflow)*nextByteOffset
 * }
 *
 * // Packed fixup loop (this zeroes out any remaining logical elements if we didn't
 * // land on a word boundary after the previous loop)
 * while(byteOffset != 0) {
 *   vs = S[storageptr]
 *   // bit ops nonsense to set the proper byte
 *   S[storageptr] = vs'
 *   nextByteOffset = byteOffset + B
 *   slotOverflow = (nextByteOffset + B-1)/32
 *   storageptr = storageptr + slotOverflow
 *   byteOffset = (1 - slotOverflow)*nextByteOffset
 * }
 *
 * The slotOverflow assignment in both loops above is either 0 or 1 (when B <= EVM_WORD_LENGTH/2), and hence
 * byteOffset only has values modulo B (assuming its value is 0 initially[1]]). The fixup loop's job is to iterate over the remaining values modulo B
 * starting from where the previous loop left off: hence, the storageptr should never increase until the final iteration.
 * This depends on the input value of byteOffset being some value that B divides[2]].
 *
 * Condition 1 can be checked by whoever creates this summary and 2 is recorded in [preconditions], to be
 * checked by the analysis that consumes this summary.
 *
 * @property effects maps variables x to the LoopEffect describing its in-loop and final values
 * @property numIterations the number of loop iterations, if known
 * @property preconditions a set of (x,k) such that x mod k == 0 must hold for the summary to be valid
 * @property loopInfeasible if true, then the summarized loop isn't feasible and the summary should be skipped
 * @property storageCmds pointers to the storage commands in the summarized loop
 */
@KSerializable
data class StorageCopyLoopSummary(
        val effects: Map<TACSymbol.Var, LoopEffect>,
        val numIterations: BigInteger?,
        val preconditions: Set<Pair<TACSymbol.Var, BigInteger>>,
        val loopInfeasible: TACExpr?,
        val storageCmds: List<CmdPointer>,
        override val modifiedVars: Set<TACSymbol.Var>,
        override val summarizedBlocks: Set<NBId>,
        override val originalBlockStart: NBId,
        override val skipTarget: NBId,
) : ConditionalBlockSummary {
    override val variables: Set<TACSymbol.Var>
        get() = effects.keys + preconditions.mapToSet { it.first } + loopInfeasible?.usedVars.orEmpty()
    override val mayWriteVars: Collection<TACSymbol.Var>
        get() = modifiedVars
    override val mustWriteVars: Collection<TACSymbol.Var>
        get() = modifiedVars

    override fun remapBlocks(f: (NBId) -> NBId?): ConditionalBlockSummary {
        return this.copy(
                summarizedBlocks = summarizedBlocks.monadicMap(f)?.toSet() ?: emptySet(),
                originalBlockStart = f(originalBlockStart)!!,
                skipTarget = f(skipTarget)!!,
        )
    }

    override val annotationDesc: String
        get() = "Storage/Memory copy loop at $originalBlockStart" +
                "[cmds: $storageCmds, numIterations: $numIterations, effects: $effects, skipTarget:$skipTarget]"

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): TACSummary {
        val subst = object : DefaultTACExprTransformer() {
            override fun transformVar(exp: TACExpr.Sym.Var): TACExpr = exp.copy(s = f(exp.s))
        }
        return this.copy(
            preconditions = preconditions.mapToSet { it.copy(first = f(it.first)) },
            effects = effects.mapKeys { (k, _) -> f(k) },
            loopInfeasible = loopInfeasible?.let { subst.transform(it) },
            modifiedVars = modifiedVars.mapToSet(f),
        )
    }
}

/**
 * Denotes an update scheme for a variable, say `x`, whose initial value is [initial]
 */
@Treapable
sealed interface LoopEffect: AmbiSerializable {
    val initial: TACSymbol
}

/** x = initial; while (...) { ... x += k ... } */
@KSerializable
data class ConstantEffect(override val initial: TACSymbol, val k: BigInteger): LoopEffect

/** x = initial; while (...) { assume(x \in [initial + loopBodyInvariant] ... }; assume(x \in [initial + loopExit]); */
@KSerializable
data class SummarizedEffect(override val initial: TACSymbol, val loopBodyInvariant: IntValue, val loopExit: IntValue): LoopEffect

/*
 * x = initial;
 * while (...) {
 *    x = (1 - (x + 2[k]-1)/32)*(x + [k]) (this is the byteOffset value mentioned in the documentation for [StorageCopyLoopSummary]
 * }
 */
@KSerializable
data class BytePtrUpdate(override val initial: TACSymbol.Const, val k: BigInteger): LoopEffect
