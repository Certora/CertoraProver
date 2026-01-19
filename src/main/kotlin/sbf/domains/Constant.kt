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

package sbf.domains

import datastructures.stdcollections.*
import sbf.cfg.CondOp

/** An immutable class that extends a Long with bottom/top values **/
data class Constant(private val value: Long?, private val isBot: Boolean = false): INumValue<Constant>, IOffset<Constant> {

    companion object {
        private val topC = Constant(null, false)
        private val botC = Constant(null, true)
        fun makeTop() = topC
        fun makeBottom() = botC
    }

    override fun toLongOrNull(): Long? {
        return if (isBottom() || isTop()) {
            null
        } else {
            value
        }
    }

    override fun toLongList(): List<Long> {
        return if (isBottom() || isTop()) {
            listOf()
        } else {
            listOf(value!!)
        }
    }

    override fun isTop() = !isBot && value == null

    override fun isBottom() = isBot

    private fun apply(other: Constant,
                      op: (Long, Long) -> Long,
                      overflow: (Long, Long, Long) -> Boolean): Constant {
        return if (isBottom() || other.isBottom()) {
            makeBottom()
        } else if (isTop() || other.isTop()) {
            makeTop()
        } else {
            check(value != null)
            check(other.value != null)
            val res = op(value, other.value)
            if (overflow(value, other.value, res)) {
                makeTop()
            } else {
                Constant(res)
            }
        }
    }

    private fun apply(other: Constant, op: (Long, Long) -> Long) =
        apply(other, op) { _, _, _ -> false }

    private fun applyDivOrRem(other: Constant,
                              op: (Long, Long) -> Long,
                              checkSDivOverflow: Boolean): Constant {
        return if (isBottom() || other.isBottom()) {
            makeBottom()
        } else if (isTop() || other.isTop()) {
            makeTop()
        } else {
            check(value != null)
            check(other.value != null)
            if (other.value == 0L) {
                /** in SBF, division by zero returns zero **/
                Constant(0L)
            } else if (checkSDivOverflow && (value == Long.MIN_VALUE && other.value == -1L)) {
                makeTop()
            } else {
                Constant(op(value, other.value))
            }
        }
    }

    override fun add(other: Constant): Constant {
        // res = a + b
        // Note that overflow is well-defined in Kotlin, so we can do a posteriori analysis.
        fun overflow(a: Long, b: Long, res: Long): Boolean {
            return ((a > 0 && b > 0 && res < 0) || (a < 0 && b < 0 && res > 0))
        }
        return apply(other, {x:Long,y:Long -> x+y }, ::overflow)
    }

    override fun add(n: Long): Constant {
        return add(Constant(n))
    }

    override fun sub(other: Constant): Constant {
        // res = a - b
        fun overflow(a: Long, b: Long, res: Long): Boolean {
            // a - b overflows iff a + (-b) overflows
            return ((a > 0 && b < 0 && res < 0) || (a < 0 && b > 0 && res > 0))
        }
        return apply(other, {x:Long,y:Long -> x-y }, ::overflow)
    }

    override fun sub(n: Long): Constant {
        return sub(Constant(n))
    }

    override fun mul(other: Constant): Constant {
        @Suppress("ForbiddenComment")
        // TODO: multiplication can overflow
        return apply(other) { x: Long, y: Long -> x * y }
    }

    override fun mul(n: Long): Constant {
        return mul(Constant(n))
    }

    override fun and(other: Constant): Constant {
        return apply(other) { x: Long, y: Long -> x.and(y) }
    }

    override fun or(other: Constant): Constant {
        return apply(other) { x: Long, y: Long -> x.or(y) }
    }

    override fun xor(other: Constant): Constant {
        return apply(other) { x: Long, y: Long -> x.xor(y) }
    }

    override fun sdiv(other: Constant): Constant {
        return applyDivOrRem(other, {x, y -> x.div(y)}, true)
    }

    override fun udiv(other: Constant): Constant {
        return applyDivOrRem(other, {x, y-> (x.toULong().div(y.toULong())).toLong() }, false)
    }

    override fun srem(other: Constant): Constant {
        return applyDivOrRem(other, {x, y -> x.mod(y)}, false)
    }

    override fun urem(other: Constant): Constant {
        return applyDivOrRem(other, {x, y-> (x.toULong().mod(y.toULong())).toLong() }, false)
    }

    override fun arsh(other: Constant): Constant {
        @Suppress("ForbiddenComment")
        // TODO: cast from Long to Int so potential overflow
        return apply(other) { x: Long, y: Long -> x.shr(y.toInt()) }
    }

    override fun rsh(other: Constant): Constant {
        @Suppress("ForbiddenComment")
        // TODO: cast from Long to Int so potential overflow
        return apply(other) { x: Long, y: Long -> x.ushr(y.toInt()) }
    }

    override fun lsh(other: Constant): Constant {
        @Suppress("ForbiddenComment")
        // TODO: lhs can overflow
        return apply(other) { x: Long, y: Long -> x.shl(y.toInt()) }
    }


    /**
     * Zero-extend the low 8 bits of this constant to 64 bits.
     *
     * Semantics:
     *   - If the constant is TOP or BOTTOM, it is returned unchanged.
     *   - Otherwise, the value is truncated to 8 bits (mod 2^8),
     *     interpreted as an unsigned byte, and zero-extended to Long.
     *
     * Examples:
     *   -1  -> 255
     *   291 -> 35
     */
    override fun zext8(): Constant{
        return if (isTop() || isBottom()) {
            this
        } else {
            Constant(value!!.toUByte().toLong())
        }
    }

    /**
     * Zero-extend the low 16 bits of this constant to 64 bits.
     *
     * Semantics:
     *   - If the constant is TOP or BOTTOM, it is returned unchanged.
     *   - Otherwise, the value is truncated to 16 bits (mod 2^16),
     *     interpreted as an unsigned short, and zero-extended to Long.
     *
     * Examples:
     *   -1   -> 65535
     *   74565 -> 9029
     */
    override fun zext16(): Constant{
        return if (isTop() || isBottom()) {
            this
        } else {
            Constant(value!!.toUShort().toLong())
        }
    }

    /**
     * Zero-extend the low 32 bits of this constant to 64 bits.
     *
     * Semantics:
     *   - If the constant is TOP or BOTTOM, it is returned unchanged.
     *   - Otherwise, the value is truncated to 32 bits (mod 2^32),
     *     interpreted as an unsigned int, and zero-extended to Long.
     *
     * Examples:
     *   -1 -> 4294967295
     *   4294967297 -> 1
     */
    override fun zext32(): Constant{
        return if (isTop() || isBottom()) {
            this
        } else {
            Constant(value!!.toUInt().toLong())
        }
    }

    override fun join(other: Constant): Constant {
        return if (isBottom()) {
            other
        } else if (other.isBottom()) {
            this
        } else if (isTop() || other.isTop()) {
            makeTop()
        } else{
            check(value != null)
            check(other.value != null)
            if (value == other.value) {
                this
            } else {
                makeTop()
            }
        }
    }

    override fun widen(other: Constant): Constant = join(other)

    override fun meet(other: Constant): Constant {
        return if (isBottom() || other.isBottom()) {
            makeBottom()
        } else if (isTop()) {
            other
        } else if (other.isTop()) {
            this
        } else {
            check(value != null)
            check(other.value != null)
            if (value != other.value) {
                makeBottom()
            } else {
                Constant(value)
            }
        }
    }

    override fun lessOrEqual(other: Constant): Boolean {
        return if (other.isTop() || isBottom()) {
            true
        } else if (isTop() || other.isBottom()) {
            false
        } else {
            check(value != null)
            check(other.value != null)
            (value == other.value)
        }
    }

    override fun filter(op: CondOp, other:Constant): Constant {
        val res = assume(op, other)
        return if (res.isFalse()) {
            makeBottom()
        } else {
            this
        }
    }
    override fun assume(op: CondOp, other:Constant): TriBoolean {
        val left = this
        val right = other

        if (left.isBottom() || left.isTop() || right.isBottom() || right.isTop()) {
            return TriBoolean.makeTop()
        }

        check(left.value != null)
        check(right.value != null)

        val x = left.value
        val y = right.value

        return when (op) {
            CondOp.EQ -> {
                if (x == y) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.NE -> {
                if (x != y) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.GE -> {
                if (x.toULong() >= y.toULong()) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.GT -> {
                if (x.toULong() > y.toULong()) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.LE -> {
                if (x.toULong() <= y.toULong()) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.LT -> {
                if (x.toULong() < y.toULong()) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.SGE -> {
                if (x >= y) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.SGT -> {
                if (x > y) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.SLE -> {
                if (x <= y) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
            CondOp.SLT -> {
                if (x < y) {
                    TriBoolean(true)
                } else {
                    TriBoolean(false)
                }
            }
        }
    }

    override fun toString(): String {
        return if (isBottom()) {
            "bot"
        } else if (isTop()) {
            "top"
        } else {
            value.toString()
        }
    }

}
