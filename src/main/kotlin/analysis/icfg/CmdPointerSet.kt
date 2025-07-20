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

package analysis.icfg

import analysis.CmdPointer
import analysis.LTACSymbol
import analysis.numeric.LatticeElem
import utils.uniqueOrNull
import vc.data.TACSymbol
import java.math.BigInteger

@Suppress("FunctionName")
/**
 * Despite the name, this is really tracking the provenance of values within memory; each [analysis.icfg.CallGraphBuilder.HeapInt]
 * tracks where its value came from.
 */
sealed class CmdPointerSet : LatticeElem<CmdPointerSet, CmdPointerSet> {

    companion object {
        val fullWord = CallGraphBuilder.ByteRange(BigInteger.ZERO, 31.toBigInteger())
        fun Singleton(cmdPtr : CmdPointer, what: TACSymbol) : CmdPointerSet = CSet(setOf(CSet.BufferSymbol.WrittenAt(cmdPtr, what) to fullWord))
    }

    /**
     * No idea where this value came from
     */
    object Nondet : CmdPointerSet() {
        override fun widen(next: CmdPointerSet): CmdPointerSet = this

        override fun join(other: CmdPointerSet): CmdPointerSet = this
    }


    /**
     * Indicates that the value in memory came from one of (src,r) \in [cs].
     * `src` is a [BufferSymbol] describing where the value was originally written into memory,
     * or some value which a priori is known to exist in a buffer. `r` describes "how much" of that original
     * value remains; for example if we have:
     * ```
     *  L: mem[x] = v
     *  mem[x + 5] = ...
     * ```
     *
     * then the [analysis.icfg.CallGraphBuilder.HeapInt] associated with the location `x` will have
     * `{ (x@L, [0,4]) }`
     * indicating that that segment of memory contains the first 5 bytes of the value of `v` which was
     * written at L.
     */
    data class CSet(val cs: Set<Pair<BufferSymbol, CallGraphBuilder.ByteRange>>) : CmdPointerSet() {

        sealed interface BufferSymbol {
            /**
             * A value [v] which is known to just exist within a buffer without a previous write;
             * used to model calldata scalars that exist at constant offsets calldata by fiat.
             */
            data class Global(val v: TACSymbol.Var) : BufferSymbol

            /**
             * A symbol [where] which was written at [where]
             */
            data class WrittenAt(val where: CmdPointer, val what: TACSymbol) : BufferSymbol {
                fun toLSym() = LTACSymbol(where, what)
            }
        }

        init {
            /**
             * Q: How could this be non-singleton
             * A: It's unlikely, but in principle it's possible to join two buffers that look like this:
             *       k
             *   ... | [5,8] of write@L  |
             *   ... | [6,9] of write@L' |
             *
             *   It's hard (but not impossible) to construct this scenario. Thus, we need per write location tracking of windows.
             */
            assert(cs.isNotEmpty() && this.cs.map { it.second.size }.uniqueOrNull() != null)
        }

        fun valueWidth() = this.cs.first().second.size

        override fun widen(next: CmdPointerSet): CmdPointerSet {
            if (next !is CSet) {
                return Nondet
            }
            if (!this.cs.containsAll(next.cs)) {
                return Nondet
            }
            return this
        }

        override fun join(other: CmdPointerSet): CmdPointerSet {
            if (other !is CSet || other.valueWidth() != this.valueWidth()) {
                return Nondet
            }
            return CSet(this.cs.union(other.cs))
        }

    }


}
