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

package analysis.opt.inliner

import analysis.numeric.linear.ReducedLinearTerm
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.Intervals
import com.certora.collect.*
import config.Config.exactByteMaps
import datastructures.stdcollections.*
import evm.EVM_MOD_GROUP256
import evm.MAX_EVM_INT256
import tac.Tag
import utils.*
import vc.data.TACExpr
import vc.data.TACExprFactUntyped
import vc.data.TACSymbol
import vc.data.asTACExpr
import java.math.BigInteger


private typealias LTerm = ReducedLinearTerm<TACSymbol.Var>

/**
 * Information attached to variables saying with what we can inline them.
 * For primitive variables this is [Term], which is linear combination of variables ([LTerm]).
 * For mappings (currently only byte-maps), it's whatever key-value pairs we know about the map. Basically keys are
 * [LTerm] and so are values.
 *
 * If we want to support word-maps as well, we should keep track of equalities of more complex expressions, specifically
 * involving hashing.
 */
sealed interface Inlinee {

    data object None : Inlinee

    /**
     * Essentially a wrapper around LTerm, but coefficients are constants are always mod [EVM_MOD_GROUP256].
     * This can't be a dataclass because the constructor parameter must go through mod 2^256 first.
     */
    class Term(lterm: LTerm) : Inlinee {
        val term = lterm.mod(EVM_MOD_GROUP256)
        override fun hashCode() = term.hashCode()
        override fun equals(other: Any?) = term == (other as? Term)?.term

        constructor(n: BigInteger) : this(LTerm(n))
        constructor(n: Int) : this(LTerm(n))
        constructor(base: TACSymbol) : this(
            when (base) {
                is TACSymbol.Const -> LTerm(base.value)
                is TACSymbol.Var -> LTerm(base)
            }
        )

        val isConst get() = term.isConst
        val c get() = term.c
        fun stripConstant() = Term(term.stripConstant())
        val asVarOrNull get() = term.asVarOrNull
        val asConstOrNull get() = term.asConstOrNull

        operator fun plus(other: Term) = Term(term + other.term)
        operator fun plus(other: BigInteger) = Term(term + other)
        operator fun plus(other: Int) = Term(term + other)

        operator fun minus(other: Term) = Term(term - other.term)
        operator fun minus(other: Int) = this - Term(other)

        operator fun times(other: Term) = when {
            this.isConst -> Term(other.term * c)
            other.isConst -> Term(term * other.c)
            else -> None
        }

        operator fun times(c: Int) =
            Term(term * c.toBigInteger())

        infix fun join(other: Term) =
            if (term == other.term) {
                this
            } else {
                None
            }

        /**
         * Returns null when the there is more than one variable in the term, so that we don't inline
         * complex terms.
         */
        fun toTACExpr(): TACExpr? {
            if (term.isConst) {
                return c.asTACExpr
            }
            val (v, coef) = term.literals.entries.singleOrNull()
                ?: return null
            with(TACExprFactUntyped) {
                return v.asSym()
                    .letIf(coef != BigInteger.ONE) {
                        Mul(coef.asTACExpr, it)
                    }
                    .letIf(c != BigInteger.ZERO) {
                        Add(it, c.asTACExpr)
                    }
            }
        }

        fun evaluate(eval: (TACSymbol.Var) -> Intervals) =
            term.literals.entries.fold(Intervals(term.c)) { acc, t ->
                (eval(t.key) * Intervals(t.value) + acc).mod(Tag.Bit256)
            }

        override fun toString() = term.toString()

        companion object {
            val ZeroTerm = Term(BigInteger.ZERO)
        }
    }


    data class Mapping(val map: TreapMap<Term, Term>) : Inlinee {

        constructor(key: Term, value: Term) : this(treapMapOf(key to value))

        companion object {
            val NoInfoMap = Mapping(treapMapOf())
        }

        fun isEmpty() = map.isEmpty()

        operator fun get(index: Term) = map[index]

        /** Keeps only pairs that are the same in the two mappings */
        infix fun join(other: Mapping) = Mapping(
            map.merge(other.map) { _, v1, v2 ->
                v1.takeIf { v1 == v2 }
            }
        )


        /**
         * the result of adding a pair to the map.
         * if [value] is null, then it just erases the keys that might be overwritten by it, but does not record
         * the new [key]-[value] pair.
         */
        fun write(key: Term, value: Term?, onlyOneByte: Boolean = false, eval: (TACSymbol.Var) -> Intervals): Mapping {
            // remove anything that the new write can overwrite (even partially)
            val newMap =
                map.removeAllKeys { k ->
                    if (exactByteMaps.get()) {
                        if (onlyOneByte) {
                            k.isMaybeInside(key, key + 32, eval)
                        } else {
                            k.isMaybeInside(key - 31, key + 32, eval)
                        }
                    } else {
                        0 in (key - k).evaluate(eval)
                    }
                }.letIf(value != null) {
                    it.put(key, value!!)
                }

            return Mapping(newMap)
        }

        /** Adds [t] to all the keys in the map */
        fun addToKeys(t: Term) = Mapping(
            map.mapKeys { (k, _) -> k + t }
        )

        override fun toString() =
            map.entries
                .groupBy { (k, _) -> k.stripConstant() }
                .map { (base, list) -> base to list.map { it.key.c to it.value } }
                .joinToString("\n") { (base, list) ->
                    "$base : ${list.joinToString { "${it.first} -> ${it.second}" }}"
                }
    }

    infix fun join(other: Inlinee): Inlinee =
        when {
            this is Term && other is Term -> this join other
            this is Mapping && other is Mapping -> this join other
            else -> None
        }


    companion object {
        private val Intervals.isNonNeg
            get() = isNotEmpty() && this isLe MAX_EVM_INT256.asExtBig

        private val Intervals.isPos
            get() = 0 !in this && isNonNeg

        private fun Term.isGe(other : Term, eval: (TACSymbol.Var) -> Intervals) =
            (this - other).evaluate(eval).isNonNeg

        private fun Term.isGt(other : Term, eval: (TACSymbol.Var) -> Intervals) =
            (this - other).evaluate(eval).isPos

        /** is [this] surely in [[low], [high]) */
        fun Term.isSurelyInside(low: Term, high: Term, eval: (TACSymbol.Var) -> Intervals) =
            this.isGe(low, eval) && high.isGt(this, eval)

        /** is [this] surely outside [[low], [high]) */
        private fun Term.isSurelyOutside(low: Term, high: Term, eval: (TACSymbol.Var) -> Intervals) =
            low.isGt(this, eval) || this.isGe(high, eval)

        /** is [this] maybe in [[low], [high]) */
        fun Term.isMaybeInside(low: Term, high: Term, eval: (TACSymbol.Var) -> Intervals) =
            !isSurelyOutside(low, high, eval)
    }

}
