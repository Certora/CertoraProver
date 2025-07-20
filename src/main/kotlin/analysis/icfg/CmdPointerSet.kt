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
import analysis.numeric.LatticeElem
import utils.mapToSet
import utils.uniqueOrNull
import java.math.BigInteger

@Suppress("FunctionName")
sealed class CmdPointerSet : LatticeElem<CmdPointerSet, CmdPointerSet> {

    companion object {
        val fullWord = CallGraphBuilder.ByteRange(BigInteger.ZERO, 31.toBigInteger())
        fun Singleton(cmdPtr : CmdPointer) : CmdPointerSet = CSet(setOf(cmdPtr), fullWord)
        fun CmdPointer.toSet() = CSet(setOf(this), fullWord)
    }

    object Nondet : CmdPointerSet() {
        override fun widen(next: CmdPointerSet): CmdPointerSet = this

        override fun join(other: CmdPointerSet): CmdPointerSet = this
    }


    data class CSet(val cs: Set<Pair<CmdPointer, CallGraphBuilder.ByteRange>>) : CmdPointerSet() {
        constructor(cs: Set<CmdPointer>, range: CallGraphBuilder.ByteRange) : this(cs.mapToSet {
            it to range
        })

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

        fun toSet(): Set<CmdPointer> = cs.mapToSet { it.first }

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
