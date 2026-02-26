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

@Suppress("DataClassPrivateConstructor")
data class ConstantSet private constructor(
    private val values: Set<Constant>,
    private val maxNumDisjuncts: ULong
    ): INumValue<ConstantSet>, IOffset<ConstantSet> {

    init {
        check(maxNumDisjuncts >= 1UL) { "ConstantSet expects maxNumDisjuncts >= 1" }
        check(values.isNotEmpty()) { "ConstantSet must have a non-empty list" }
    }

    companion object {
        private fun normalize(xs: Set<Constant>, maxNumDisjuncts: ULong): ConstantSet {
            return if (xs.any { it.isTop() }) {
                mkTop(maxNumDisjuncts)
            } else{
                val ys = xs.filter { !it.isBottom() }
                if (ys.isEmpty()) {
                    mkBottom(maxNumDisjuncts)
                } else {
                    val res = ConstantSet(ys.toSet(), maxNumDisjuncts)
                    if (res.values.size.toULong() > maxNumDisjuncts) {
                        res.smash()
                    } else {
                        res
                    }
                }
            }
        }

        operator fun invoke(values: Set<Constant>, maxNumDisjuncts: ULong) = normalize(values, maxNumDisjuncts)
        fun mkTop(maxNumDisjuncts: ULong) = ConstantSet(Constant.makeTop(), maxNumDisjuncts)
        fun mkBottom(maxNumDisjuncts: ULong) = ConstantSet(Constant.makeBottom(), maxNumDisjuncts)
    }

    constructor(c: Constant, maxNumDisjuncts: ULong) : this(setOf(c), maxNumDisjuncts)
    constructor(c: Long, maxNumDisjuncts: ULong) : this(Constant(c), maxNumDisjuncts)

    /** Reduce `values` to a singleton set **/
    private fun smash(): ConstantSet {
        return if (isBottom() || isTop()) {
            this
        } else {
            if (values.size == 1) {
                this
            } else {
                // values is a set so it cannot have duplicated constants.
                // Thus, if values is not a singleton then the result will be top
                mkTop(maxNumDisjuncts)
            }
        }
    }

    private fun single(): Constant {
        val res =  values.singleOrNull()
        check(res != null) {"single() called on a non-singleton list $values"}
        return res
    }

    private fun binOp(other: ConstantSet, op: (Constant, Constant) -> Constant) : ConstantSet {
        return if (this.isBottom() || other.isBottom()) {
            mkBottom(maxNumDisjuncts)
        } else if (this.isTop() || other.isTop()) {
            mkTop(maxNumDisjuncts)
        } else {
            this.values.flatMap { o1 ->
                other.values.map { o2 ->
                    val res = op(o1, o2)
                    if (res.isTop()) {
                        return mkTop(maxNumDisjuncts)
                    }
                    res
                }
            }.toSet().let { ConstantSet(it, maxNumDisjuncts) }
        }
    }

    private fun binOp(n: Long, op: (Constant, Constant) -> Constant) =
        binOp(ConstantSet(Constant(n), maxNumDisjuncts), op)

    override fun toLongOrNull(): Long? {
        return if (isBottom() || isTop()) {
            null
        } else {
            smash().single().toLongOrNull()
        }
    }

    override fun toLongList(): List<Long> {
        val normalizedSet = normalize(values, maxNumDisjuncts)
        return if (normalizedSet.isTop() || normalizedSet.isBottom()) {
            listOf()
        } else {
            normalizedSet.values.map {
                val x = it.toLongOrNull()
                check(x != null){"A normalized ConstantSet cannot have top or bottom elements"}
                x
            }
        }
    }

    override fun isBottom() = values.all { it.isBottom() }

    override fun isTop() = values.any {it.isTop() }

    override fun join(other: ConstantSet): ConstantSet {
        return if (isTop() || other.isBottom()) {
            this
        } else if (isBottom()) {
            other
        } else if (other.isTop()) {
            other
        } else {
            normalize(values + other.values, maxNumDisjuncts)
        }
    }

    override fun widen(other: ConstantSet): ConstantSet {
        return if (isTop() || other.isBottom()) {
            this
        } else if (isBottom()) {
            other
        } else if (other.isTop()) {
            other
        } else {
            check(this.maxNumDisjuncts == other.maxNumDisjuncts) {"ConstantSet widening with different maxNumDisjuncts"}
            if (other.values.size.toULong() > other.maxNumDisjuncts) {
                val smashedLeft  = smash().single()
                val smashedRight = other.smash().single()
                ConstantSet(setOf(smashedLeft.widen(smashedRight)), maxNumDisjuncts)
            } else {
                join(other)
            }

        }
    }

    override fun meet(other: ConstantSet): ConstantSet {
        return if (isTop() || other.isBottom()) {
            other
        } else if (isBottom() || other.isTop()) {
            this
        } else {
            val res = this.values.intersect(other.values)
            if (res.isEmpty()) {
                mkBottom(maxNumDisjuncts)
            } else {
                ConstantSet(res, maxNumDisjuncts)
            }
        }
    }

    override fun lessOrEqual(other: ConstantSet): Boolean {
        return if (other.isTop() || isBottom()) {
            true
        } else if (isTop() || other.isBottom()) {
            false
        } else {
            other.values.containsAll(this.values)
        }
    }

    override fun toString(): String {
        return if (isTop()) {
            "top"
        } else if (isBottom()) {
            "bottom"
        } else {
            if (values.size==1) {
                values.first().toString()
            } else {
                values.toString()
            }
        }
    }

    override fun add(other: ConstantSet) = binOp(other, Constant::add)

    override fun add(n: Long) = binOp(n, Constant::add)

    override fun sub(other: ConstantSet) = binOp(other, Constant::sub)

    override fun sub(n: Long) = binOp(n, Constant::sub)

    override fun mul(other: ConstantSet) = binOp(other, Constant::mul)

    override fun mul(n: Long) = binOp(n, Constant::mul)

    override fun and(other: ConstantSet) = binOp(other, Constant::and)

    override fun or(other: ConstantSet) = binOp(other, Constant::or)

    override fun xor(other: ConstantSet) = binOp(other, Constant::xor)

    override fun udiv(other: ConstantSet) = binOp(other, Constant::udiv)

    override fun sdiv(other: ConstantSet) = binOp(other, Constant::sdiv)

    override fun urem(other: ConstantSet) = binOp(other, Constant::urem)

    override fun srem(other: ConstantSet) = binOp(other, Constant::srem)

    override fun arsh(other: ConstantSet) = binOp(other, Constant::arsh)

    override fun rsh(other: ConstantSet) = binOp(other, Constant::rsh)

    override fun lsh(other: ConstantSet) = binOp(other, Constant::lsh)

    override fun zext8() =
        if (isBottom() || isTop()) {
            this
        } else {
            ConstantSet(values.map { c -> c.zext8() }.toSet(), maxNumDisjuncts)
        }

    override fun zext16() =
        if (isBottom() || isTop()) {
            this
        } else {
            ConstantSet(values.map { c -> c.zext16() }.toSet(), maxNumDisjuncts)
        }

    override fun zext32() =
        if (isBottom() || isTop()) {
            this
        } else {
            ConstantSet(values.map { c -> c.zext32() }.toSet(), maxNumDisjuncts)
        }

    override fun assume(op: CondOp, other: ConstantSet): TriBoolean {
        return if (isBottom() || isTop() || other.isBottom() || other.isTop()) {
            TriBoolean.makeTop()
        } else {
            val res = this.values.flatMap { o1 ->
                other.values.map { o2 ->
                    o1.assume(op, o2)
                }
            }
            if (res.all { it.isFalse() }) {
                TriBoolean(false)
            } else if (res.all { it.isTrue() }) {
                TriBoolean(true)
            } else {
                TriBoolean.makeTop()
            }
        }
    }

    override fun filter(op: CondOp, other: ConstantSet): ConstantSet {
        return if (isBottom() || other.isBottom()) {
            this
        } else if (isTop() || other.isTop()){
            if(op == CondOp.EQ) {
                this.meet(other)
            } else {
                this
            }
        } else {
            this.values.filter { o1 ->
                other.values.any { o2 ->
                    !o1.assume(op, o2).isFalse()
                }
            }.toSet().let {
                if (it.isEmpty()) {
                    mkBottom(maxNumDisjuncts)
                } else {
                    ConstantSet(it, maxNumDisjuncts)
                }
            }
        }
    }
}
