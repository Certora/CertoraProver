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

package analysis.storage

import analysis.numeric.IntValue
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.inEVMRange
import utils.*
import vc.data.TACSymbol
import java.math.BigInteger
import kotlin.math.absoluteValue

/**
 * A [Stride] is an abstract value that denotes the access pattern of some integral value used as a pointer.
 *
 * The main purpose is to be able to reconstruct the (static) array indices and struct offsets used to access a particular
 * memory location. This is possible because solidity seems to generate fairly reasonable code. For example, for
 *
 * struct { uint x; uint y; }[5][3] arr;
 * ...
 * arr[i][j].y
 * ...
 *
 * we'll see something like:
 *
 * p := 10*i + 0 // the 0 is the root slot
 * q := p + 2*j
 * r := q + 2
 *
 * The Stride.SumOfTerms corresponding to r will look something like:
 * 10*i + 2*j + 2
 *
 * Crucially, while we may not be able to track the exact index symbols, we _do_ keep track of the _intervals_
 * of those values so that we can perform bounds checking later.
 */
sealed class Stride {
    abstract fun joinOp(s: Stride, widen: Boolean = false): Stride
    abstract fun multiply(i: TACSymbol.Const): Stride
    abstract fun divide(i: TACSymbol.Const): Stride
    abstract fun killVar(x: TACSymbol.Var): Stride

    /**
     * Refines this [Stride] by interpreting [i] as bounds on its possible value.
     * It is always "safe" for an implementation to do nothing here.
     *
     * @param i the bounds on this Stride's value
     */
    abstract fun refineTotal(i: IntValue): Stride?

    /**
     * Refine this stride with the information that it is equal to [x]
     */
    abstract fun withName(x: TACSymbol.Var): Stride

    /**
     * Returns the range of values denoted by this Stride
     */
    abstract val range: IntValue

    object Top: Stride() {
        override fun joinOp(s: Stride, widen: Boolean) = Top
        override fun multiply(i: TACSymbol.Const) = Top
        override fun divide(i: TACSymbol.Const) = Top
        override fun killVar(x: TACSymbol.Var): Stride = Top
        override fun refineTotal(i: IntValue): Stride = SumOfTerms(SymValue(null, i))
        override fun withName(x: TACSymbol.Var): Stride = Top
        override val range: IntValue get() = IntValue.Nondet

        // Cached singleton set for efficiency
        val asSet = treapSetOf(Top)
    }

    /**
     * Terms are the ranges of values of the variables in 5*x + 25*y + ...
     *
     * @property x the variable whose value produced this SymValue
     * @property v the range of values of this term
     */
    data class SymValue(val x: TACSymbol.Var?, val v: IntValue) {
        constructor(v: IntValue): this(null, v)

        fun joinOp(f: SymValue, widen: Boolean): SymValue =
                SymValue(
                        x = x.takeIf { x == f.x },
                        v = if (widen) { v.widen(f.v) } else { v.join(f.v) },
                )

        fun add(i: IntValue): SymValue {
            return add(SymValue(null, i))
        }

        fun add(o: SymValue): SymValue {
            return if (v.isConstant && v.c == BigInteger.ZERO) {
                o
            } else if (o.v.isConstant && o.v.c == BigInteger.ZERO) {
                this
            } else {
                SymValue(null, v.add(o.v).first)
            }
        }

        override fun toString(): String {
            val int = if (v == IntValue.Nondet) { "T" } else { "[${v.lb},${v.ub}]"}
            val xStr = if (x != null) { "$x=" } else { "" }
            return "${xStr}${int}"
        }

    }

    /**
     * A Stride comprising a summation of SymValues multiplied by coefficients.
     *
     * NOTE that there is no canonical representation for a given sum of terms.
     * It's expected that this may cause spurious failures, in which case we will need to
     * devise one. For example a constant offset can be "factored" into the factored map
     * in a number of ways, or the factored map may contain mappings whose gcd is not 1.
     *
     * @property factored is interpreted as a sum, i.e. Sum { k*v | factored[k] = v }
     * @property off a constant offset
     */
    data class SumOfTerms(val factored: TreapMap<BigInteger, SymValue>, val off: BigInteger): Stride() {

        init {
            check(off.inEVMRange) {
                "Offset overflow"
            }
            for ((f, v) in factored) {
                check(f > BigInteger.ZERO) {
                    "Representation violation: zero factor"
                }
                check(v.v != IntValue.Constant(BigInteger.ZERO))  {
                    "Representation violation: zero term"
                }
            }
        }

        companion object {
            fun sumOf(factored: TreapMap<BigInteger, SymValue>, off: BigInteger): Stride =
                if (!off.inEVMRange)  {
                    Top
                } else {
                    SumOfTerms(
                        factored.retainAll { (f, v) ->
                            f != BigInteger.ZERO && BigInteger.ZERO < v.v.ub
                        },
                        off
                    )
                }

            operator fun invoke(o: SymValue): Stride =
                if (o.v == IntValue.Nondet) {
                    Top
                } else if (o.v != IntValue.Constant(BigInteger.ZERO))  {
                    SumOfTerms(treapMapOf(BigInteger.ONE to o), BigInteger.ZERO)
                } else {
                    SumOfTerms(treapMapOf(), BigInteger.ZERO)
                }

            operator fun invoke(o: BigInteger) =
                if (o.inEVMRange) {
                    SumOfTerms(treapMapOf(), o)
                } else {
                    Top
                }
        }

        override val range: IntValue = run {
            var range = IntValue.Constant(off)
            factored.forEachEntry { (k, f) ->
                range = range.add(IntValue.Constant(k).mult(f.v).first).first
            }
            range
        }

        /**
         * Joins two SumOfTerm strides by pointwise merging the terms (taking the interval [0,0] for missing
         * factors). If the constant offsets differ, then we add the difference as a new factor to the map
         * (or join it in if it already exists)
         */
        override fun joinOp(s: Stride, widen: Boolean): Stride {
            val c = s as? SumOfTerms ?: return Top

            if (c == this) {
                return c
            }

            /* First, effectively union the factored part, widening the interval to include 0 if
               a factor was missing from either map, i.e.:

                join(4*[5,6], 2*[3,4]) => {4*[0,6], 2*[0,4]}

               maybe this needs to be more precise somehow, but it seems adequate
             */
            val joinedFactored = factored.pointwiseMerge(c.factored) { lhs, rhs ->
                lhs.joinOp(rhs, widen)
            }.updateValues { k, f ->
                if (k !in factored || k !in c.factored) {
                    f.copy(x = null, v = f.v.copy(lb = BigInteger.ZERO))
                } else {
                    f
                }
            }

            val (smaller, bigger) = if (this.off < c.off) {
                this.off to c.off
            } else {
                c.off to this.off
            }
            val diff = bigger - smaller
            if (diff == BigInteger.ZERO) {
                return sumOf(
                        factored = joinedFactored,
                        off = smaller
                )
            } else {
                val oldRange = joinedFactored[diff]?.v ?: IntValue.Constant(BigInteger.ZERO)
                val range = IntValue(BigInteger.ZERO, BigInteger.ONE)
                val newSymValue = SymValue(
                        null,
                        oldRange.add(range).first
                )
                return sumOf(
                        factored = joinedFactored.update(diff, newSymValue) { f ->
                            f.joinOp(newSymValue, widen)
                        },
                        off = smaller
                )
            }
        }

        private fun BigInteger.ceilDiv(x: BigInteger): BigInteger =
            divideAndRemainder(x).let { (q, r) ->
                if (r.signum() == 0 || signum()*x.signum() >= 0) {
                    q + r.signum().absoluteValue
                } else {
                    q
                }
            }

        /**
         * Refines this SumOfTerms if it contains exactly one factor
         */
        override fun refineTotal(i: IntValue): Stride? {
            // Let's make this easy. Imagine if we have { 3 => [1, 5], 10 => [0, 10] }
            // and we refine with [ 30, 90 ]. Then of course 3*[1, 5] = [3,6..15] does
            // not even overlap with i...
            if (i.isConstant) {
                return SumOfTerms(i.c)
            }

            if (range.mustNotOverlap(i))  {
                return null
            }

            // If we have (k0*x0 + k1*x1 + ... + off),
            // And i = [lb, ub], then if we think of each xi as a 'switch' for ki,
            // then it is infeasible for the switch for ki to be on if (off + ki) > ub
            val maxFactor = i.ub - off
            val feasibleFactors = factored.retainAllKeys { it <= maxFactor }

            if (feasibleFactors.size != 1) {
                return this
            }

            return sumOf(
                    feasibleFactors.mapValues { (coeff, range) ->
                        // The (single) range denotes "may" values, so this is where we can do some refinement.

                        val lb = (i.lb - off).ceilDiv(coeff).max(range.v.lb)
                        val ub = ((i.ub - off) / coeff).min(range.v.ub)

                        if (lb > ub) {
                            return this
                        }

                        SymValue(x = range.x, v = IntValue(lb, ub))
                    },
                    // The offset is a "must" value
                    off
            ) as? SumOfTerms
        }

        override fun withName(x: TACSymbol.Var): Stride {
            return if (factored.size == 1 && off == BigInteger.ZERO) {
                this.copy(factored = factored.mapValues {(_, sv) ->
                    if (sv.x == null) {
                        sv.copy(x = x)
                    } else {
                        sv
                    }
                })
            } else {
                this
            }
        }

        /**
         * Add another [SumOfTerms] to this.
         * @param o the operand
         * @return a new [SumOfTerms] representing the sum just when the set of factors in [o]
         *         is disjoint from this. Otherwise, return [Top].
         */
        fun add(o: SumOfTerms): Stride {
            if (!factored.keys.containsAny(o.factored.keys)) {
                val newFactors = factored + o.factored
                val offSum = off + o.off
                return sumOf(newFactors, offSum)
            }
            return Top
        }

        /**
         * Subtracts a constant offset.
         * Since there may be multiple ways to subtract a given value from this SumOfTerms,
         * we follow this strategy:
         * 1) If [off] > [o], then include the sum with [off] - [o] as the new offset
         * 2) Additionally, for each coefficient in [factored], if the set of values could possibly
         *    contain [o], then include the resulting sum after subtracting [o] from it
         *
         * Note this does not include sums resulting from combining different terms
         *
         * @param o the value to subtract
         * @return a set of SumOfTerms, whose denotations are equal to this - [o]
         */
        fun subConst(o: BigInteger): Set<SumOfTerms> {
            val newSums = mutableSetOf<SumOfTerms>()

            var i = o
            if (off >= i) {
                // We can completely take o from off, so we're done
                return setOf(SumOfTerms(factored, off.subtract(i)))
            } else {
                // Otherwise, we need to subtract (off - o) from the map of factors
                i = i.subtract(off)
            }

            for ((k, range) in factored) {

                // This term represents the set T = {k*range.lb, k*(range.lb + 1), ..., k*(range.ub) }.
                // This term is possibly larger than i when there is some j such that
                // k*j \in T and i <= k*j
                // Or, equivalently,
                // j \in range s.t. i/k <= j
                //
                // Why _ceilDiv_?
                // Consider i = 11, k = 5, range = [0,3].
                // i/k = 2, so subtracting k*(i/k) is equivalent to subtracting 10.
                // Instead, we subtract 15 and then add back 4 later (see below "as a possibly new factor").
                val quotient = i.ceilDiv(k)
                if (range.v.ub >= quotient) {
                    val diff = quotient*k - i

                    // _assume_ that we have at least i/k.
                    // so subtract i/k from [lb, ub]
                    val newUb = range.v.ub - quotient
                    val newLb = if (range.v.lb > quotient) {
                        range.v.lb - quotient
                    } else {
                        // clamp lb to zero since we're _assuming_ we have at least
                        // i/k in the interval
                        BigInteger.ZERO
                    }

                    // Update k in our map, maintaining the invariant
                    // that we do not store the value k*[0,0]
                    var newFactored = factored
                    if (newUb != BigInteger.ZERO) {
                        newFactored += k to range.copy(v = IntValue(newLb, newUb), x = null)
                    } else {
                        newFactored -= k
                    }

                    // In the quotient computation, we did not require that the remainder
                    // be zero. If we have a remainder, then, we should add it back as a (possibly new) factor
                    if (diff != BigInteger.ZERO) {
                        newFactored = newFactored.update(diff, SymValue(null, IntValue.Constant(BigInteger.ZERO))) { f: SymValue ->
                            SymValue(null, f.v.add(IntValue.Constant(BigInteger.ONE)).first)
                        }
                    }
                    newSums.add(SumOfTerms(off = BigInteger.ZERO, factored = newFactored))
                }
            }

            if (newSums.isEmpty())
                return setOf()

            return newSums
        }

        override fun multiply(i: TACSymbol.Const): Stride =
                sumOf(
                        factored = factored.mapKeys(transform = { (c, _) -> c.multiply(i.value) }),
                        off = off.multiply(i.value)
                )

        override fun divide(i: TACSymbol.Const): Stride {
            return if (i.value == BigInteger.ZERO) {
                SumOfTerms(BigInteger.ZERO)
            } else {
                /* Consider:
                   a) 1*[lb, ub] + off
                      this is equivalent to [off + lb, off+lb+1, off+lb+2, ..., off+ub] and we can just
                      divide by k
                   b) (m*i)*[lb, ub] + off
                      then dividing by i gives us m*[lb, ub] + off/i
                   c) something weird like
                      7*[0, 5] and divide by 3
                      then we have {0, 7, 14, 21, 28, 35 } divided by 3
                      which is     {0, 2, 4,   7,  9, 11 }
                      and has irregular intervals between successive elements,
                      i.e. can not be approximated as a single factor/SymVal.
                 */
                factored.entries.singleOrNull()?.let { (k, symVal) ->
                    if (k == BigInteger.ONE) {
                        // case a) above
                        val range = IntValue(
                            (symVal.v.lb + off).divide(i.value),
                            (symVal.v.ub + off).divide(i.value)
                        )
                        sumOf(
                            factored = treapMapOf(
                                k to SymValue(null, range)
                            ),
                            off = BigInteger.ZERO
                        )
                    } else {
                        val (newK, rem) = k.divideAndRemainder(i.value)
                        if (rem != BigInteger.ZERO) {
                            return Top
                        }
                        // case b)
                        val range = IntValue(
                            symVal.v.lb,
                            symVal.v.ub
                        )
                        sumOf(
                            factored = treapMapOf(newK to SymValue(null, range)),
                            off = off / i.value
                        )
                    }
                } ?: Top
            }
        }

        override fun killVar(x: TACSymbol.Var): Stride =
                SumOfTerms(
                        factored = factored.mapValues { (_, symValue: SymValue) ->
                            if (symValue.x == x) {
                                symValue.copy(x = null)
                            } else {
                                symValue
                            }
                        },
                        off = off
                )

        override fun toString(): String {
            val termStrs = factored.keys.sorted().map { f ->
                val x = factored[f]!!
                "$f*$x"
            } + "$off"
            return termStrs.joinToString(" + ")
        }
    }
}

/**
 * Assuming a domain of equivalence classes, do a pointwise join of strides indexed by K.
 * For example, `K` could be abstract storage locations, so the mapping k => strides would be a set of
 * strides that point into k somehow.
 *
 * @param other the collection with which to join
 * @param widen whether to perform a widening operation instead of a more precise join when joining intervals
 */
fun <@Treapable K> Map<K, Collection<Stride>>.joinByEqClass(other: Map<K, Collection<Stride>>, widen: Boolean): TreapSet<Stride> {
    if (this.values.any { it.contains(Stride.Top) } || other.values.any { it.contains(Stride.Top) } ) {
        return Stride.Top.asSet
    }

    // Now, join all singleton stride equivalence classes. If a class isn't singleton,
    // then we're just going to return Top
    val joined = treapSetBuilderOf<Stride>()
    for (k in keys + other.keys) {
        if (k in keys && k in other) {
            this[k]!!.singleOrNull()?.let { l ->
                other[k]!!.singleOrNull()?.let { r ->
                    (l.joinOp(r, widen) as? Stride.SumOfTerms)?.let { it ->
                        joined += it
                    }
                }
            } ?: return Stride.Top.asSet

        } else {
            this[k]?.let { joined += it}
            other[k]?.let { joined += it}
        }
    }

    return joined.build()
}
