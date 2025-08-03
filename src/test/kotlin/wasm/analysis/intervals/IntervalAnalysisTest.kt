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

package wasm.analysis.intervals

import analysis.numeric.*
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntValueSemantics
import evm.*
import net.jqwik.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigInteger

class IntervalAnalysisTest {

    fun bit256Intervals(minValue: BigInteger, maxWidth: BigInteger) =
        Arbitraries.bigIntegers().between(minValue, MAX_EVM_UINT256).flatMap { lowerBound ->
            val maxUpperBound = (lowerBound + maxWidth - BigInteger.ONE).min(MAX_EVM_UINT256)
            Arbitraries.bigIntegers().between(lowerBound, maxUpperBound).map { upperBound ->
                SimpleQualifiedInt(IntValue(lb = lowerBound, ub = upperBound), setOf())
            }
        }

    companion object {
        fun intValBetween(lo: Int, hi: Int) =
            Arbitraries.integers().between(lo, hi).flatMap { lower ->
                Arbitraries.integers().between(lower, hi).map {
                    IntValue(lower.toBigInteger(), it.toBigInteger())
                }
            }
    }

    @Provide
    fun numeratorIntervals() = bit256Intervals(0.toBigInteger(), 100.toBigInteger())

    @Provide
    fun denominatorIntervals() = bit256Intervals(1.toBigInteger(), 100.toBigInteger())

    operator fun ClosedRange<BigInteger>.iterator() = object : Iterator<BigInteger> {
        private var current = start
        override fun hasNext() = current <= endInclusive
        override fun next() = current++
    }

    @Property
    fun mod(
        @ForAll("numeratorIntervals") numerator: SimpleQualifiedInt,
        @ForAll("denominatorIntervals") denominator: SimpleQualifiedInt
    ) {
        val result = SimpleQualifiedIntValueSemantics.mod(numerator, denominator)

        for (n in numerator.x.lb..numerator.x.ub) {
            for (d in denominator.x.lb..denominator.x.ub) {
                val concreteResult = n % d
                assertTrue(concreteResult in result.x.lb..result.x.ub, "$n mod $d = $concreteResult not in $result")
            }
        }
    }
}
