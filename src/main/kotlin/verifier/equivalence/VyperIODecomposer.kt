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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier.equivalence

import analysis.ip.VyperArgument
import analysis.ip.VyperReturnValue
import vc.data.TACSymbol
import vc.data.asTACSymbol
import verifier.equivalence.summarization.VyperCallingConvention
import datastructures.stdcollections.*

/**
 * Mixin for decomposing [VyperArgument] and [VyperReturnValue] into shape information, and in the case
 * of [recomposeRet], recomposing that information.
 */
interface VyperIODecomposer {
    fun List<VyperArgument>.decompose() = map { arg ->
        when(arg) {
            is VyperArgument.MemoryArgument -> arg.where.asTACSymbol() to VyperCallingConvention.ValueShape.InMemory(arg.size)
            is VyperArgument.StackArgument -> arg.s to VyperCallingConvention.ValueShape.OnStack
        }
    }.unzip()

    fun VyperReturnValue?.decompose(): Pair<List<TACSymbol.Var>, VyperCallingConvention.ValueShape?> {
        return when (this) {
            is VyperReturnValue.MemoryReturnValue -> listOf(this.s) to VyperCallingConvention.ValueShape.InMemory(this.size)
            is VyperReturnValue.StackVariable -> listOf(this.s) to VyperCallingConvention.ValueShape.OnStack
            null -> listOf<TACSymbol.Var>() to null
        }
    }

    fun VyperCallingConvention.ValueShape?.recomposeRet(l: List<TACSymbol.Var>): VyperReturnValue? {
        return listOfNotNull(this).zip(l) { shape, s ->
            when (shape) {
                is VyperCallingConvention.ValueShape.InMemory -> VyperReturnValue.MemoryReturnValue(
                    s = s, size = shape.size
                )

                VyperCallingConvention.ValueShape.OnStack -> VyperReturnValue.StackVariable(
                    s = s
                )
            }
        }.singleOrNull()

    }

}
