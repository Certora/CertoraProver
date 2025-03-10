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

package analysis.split

import evm.EVM_BITWIDTH256
import utils.ModZm.Companion.lowOnes
import java.math.BigInteger


/**
 * Represents a consecutive range of bits. Either an [Empty] one, or a [NonEmpty] one.
 */
sealed class BitRange {

    abstract val width: Int

    object Empty : BitRange() {
        override val width = 0

        override fun toString() = "Empty"
    }

    /** A bit range from [lowBit] (inclusive) upto [highBit] (exclusive) */
    data class NonEmpty(val lowBit: Int, val highBit: Int) : BitRange() {
        override val width get() = highBit - lowBit

        init {
            check(0 <= lowBit && lowBit < highBit && highBit <= EVM_BITWIDTH256) {
                "Illegal bit range $this"
            }
        }

        override fun toString() = when {
            lowBit == highBit - 1 -> "B$lowBit"
            this == all -> "Bfull"
            else -> "B${lowBit}_${highBit - 1}"
        }
    }

    companion object {
        val all = NonEmpty(0, EVM_BITWIDTH256)

        operator fun invoke(lowBit: Int, highBit: Int): BitRange {
            val l = maxOf(0, lowBit)
            val h = minOf(EVM_BITWIDTH256, highBit)
            return if (l >= h) {
                Empty
            } else {
                NonEmpty(l, h)
            }
        }
    }

    /** A positive [by] signifies shift-left, and a negative [by] is a shift-right */
    infix fun shift(by: Int) = when (this) {
        is Empty -> Empty
        is NonEmpty -> BitRange(lowBit + by, highBit + by)
    }

    infix fun intersect(o: BitRange) =
        when {
            this is NonEmpty && o is NonEmpty ->
                BitRange(maxOf(lowBit, o.lowBit), minOf(highBit, o.highBit))
            else -> Empty
        }

    infix fun merge(o: BitRange) =
        when {
            this is NonEmpty && o is NonEmpty ->
                BitRange(minOf(lowBit, o.lowBit), maxOf(highBit, o.highBit))
            else -> Empty
        }

    infix fun contains(bit: Int) =
        this is NonEmpty && bit >= lowBit && bit < highBit

    infix fun intersects(o: BitRange) =
        this is NonEmpty && o is NonEmpty &&
                (this contains o.lowBit || o contains this.lowBit)

    fun toMask(): BigInteger = when (this) {
        is Empty -> BigInteger.ZERO
        is NonEmpty -> lowOnes(width) shl lowBit
    }
}


/**
 * A list of non-intersecting non-empty bit ranges in ascending order.
 */
data class Split(val backing: List<BitRange.NonEmpty>) : List<BitRange.NonEmpty> by backing {
    companion object {
        val all = Split(BitRange.all)
        val none = Split(emptyList())
        val just0thBit = Split(BitRange(0, 1))
        val allBits = Split((0 until EVM_BITWIDTH256).map { BitRange(it, it + 1) })

        fun repeated(width: Int) =
            Split((width..EVM_BITWIDTH256 step width).map { BitRange(it - width, it) })

        operator fun invoke(backing: List<BitRange>) =
            Split(backing.filterIsInstance<BitRange.NonEmpty>())

        operator fun invoke(vararg backing: BitRange) =
            invoke(backing.toList())
    }

    init {
        backing.zipWithNext { range, next ->
            check(range.highBit <= next.lowBit) { "Illegal split $this" }
        }
    }

    override fun toString() =
        if (backing.isEmpty()) {
            "none"
        } else {
            backing.joinToString(",")
        }


    infix fun join(other: Split): Split {
        when {
            this == other -> this
            this == none -> other
            other == none -> this
            this == all || other == all -> all
            else -> null
        }?.let { return it }

        val ranges = (backing + other.backing).sortedBy { it.lowBit }
        val result = mutableListOf<BitRange>()
        var (lowBit, highBit) = ranges[0]
        ranges.subList(1, ranges.size).forEach { current ->
            if (current.lowBit < highBit) {
                highBit = maxOf(highBit, current.highBit)
            } else {
                result.add(BitRange(lowBit, highBit))
                lowBit = current.lowBit
                highBit = current.highBit
            }
        }
        result.add(BitRange(lowBit, highBit))
        return Split(result)
    }

    infix fun shift(by: Int) =
        Split(map { it shift by })

    /** returns the single range that intersects the given one, or null if none */
    infix fun fitsTo(range: BitRange.NonEmpty) =
        filter { it intersects range }
            .also {
                check(it.size != 2) {
                    "Two ranges fit one range? $this, $range"
                }
            }
            .singleOrNull()
}
