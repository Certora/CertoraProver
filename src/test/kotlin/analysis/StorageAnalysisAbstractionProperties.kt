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

package analysis

import analysis.numeric.IntValue
import analysis.numeric.SimpleIntQualifier
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.storage.*
import analysis.storage.StorageAnalysis.SValue.I
import analysis.storage.StorageAnalysis.Storage
import analysis.storage.StorageAnalysis.Value
import annotations.TestTags.EXPENSIVE
import com.certora.collect.*
import datastructures.stdcollections.*
import net.jqwik.api.*
import net.jqwik.kotlin.api.*
import utils.foldFirst
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.*
import vc.data.asTACSymbol

@Tag(EXPENSIVE)
class StorageAnalysisAbstractionProperties {

    // Just because `sumOfTerms` as written can index a range of 130 words
    private val sz = 150

    // Fixed storage environment, which makes generating random pointers into it much easier
    private val storageEnv: Map<Storage, Value> = (0..10).associate {
        val k = Storage.Root(((it * sz) + 1).toBigInteger())
        val v = Value.StaticArray(0, sz.toBigInteger(), 1.toBigInteger())
        k to v
    }

    @Property(tries = 1000)
    fun sumOfTermsJoinsCorrect(
        @ForAll("sumOfTerms") s1: Stride.SumOfTerms,
        @ForAll("sumOfTerms") s2: Stride.SumOfTerms,
    ) {
        val j = s1.joinOp(s2)

        // Make sure we're not testing exclusively trivial cases
        Assume.that(j !is Stride.Top)

        assertTrue(
                j is Stride.SumOfTerms && j.concretize().let {
                    it.containsAll(s1.concretize()) && it.containsAll(s2.concretize())
                }
        ) {
            "${j} is not a correct join of ${s1} and ${s2}"
        }
    }

    @Property(tries = 1000)
    fun storageIdxJoinCorrect(
        @ForAll("idx") i1: SimpleQualifiedInt,
        @ForAll("idx") i2: SimpleQualifiedInt,
    )  {
        val j = i1.joinOp(i2, mapOf(), mapOf(), widen=false)

        // Make sure we're not testing exclusively trivial cases
        Assume.that(j != SimpleQualifiedInt.nondet)

        assertTrue(
            j.concretize().let {
                it.containsAll(i1.concretize()) && it.containsAll(i2.concretize())
            }
        ) {
            "${j} is not a correct join of ${i1} and ${i2}"
        }
    }

    @Property(tries = 1000)
    fun sValueIJoinCorrect(
        @ForAll("storagePtrIdx") idx1: I,
        @ForAll("storagePtrIdx") idx2: I,
    ) {
        val idxJoin = try {
            val idxJoin = idx1.join(idx2, treapMapOf(), treapMapOf(), mapOf(), storageEnv)
            // Make sure we're not testing exclusively trivial cases
            Assume.that(idxJoin is I && idxJoin.i != SimpleQualifiedInt.nondet && Stride.Top !in idxJoin.stride)
            idxJoin
        } catch (_: StorageAnalysis.InfeasibleState) {
            null
        }

        val concrete = if (idxJoin != null) {
            (idxJoin as I).concretize()
        } else {
            setOf()
        }

        assertTrue(concrete.containsAll(idx1.concretize()) && concrete.containsAll(idx2.concretize())
        ) {
            "${idxJoin ?: "bottom"} is not a correct join of ${idx1} and ${idx2}"
        }
    }


    @Property(tries = 1000)
    fun sValueIWidenCorrect(
        @ForAll("storagePtrIdx") idx1: I,
        @ForAll("storagePtrIdx") idx2: I,
    ) {
        fun Stride.SumOfTerms.hasBadFactor(): Boolean {
            return factored.any { (_, v) ->
                v.v.ub == IntValue.Nondet.ub
            }
        }

        try {
            val idxWiden = idx1.widen(idx2, treapMapOf(), treapMapOf(), mapOf(), storageEnv)
            Assume.that(idxWiden is I)
            assertTrue(
                idxWiden is I && (
                    idxWiden.i.x.ub == IntValue.Nondet.ub ||
                        Stride.Top in idxWiden.stride ||
                        idxWiden.stride.any { (it as? Stride.SumOfTerms)?.hasBadFactor() ?: false } ||
                        idxWiden.concretize().let { cs ->
                            (cs.containsAll(idx1.concretize()) && cs.containsAll(idx2.concretize()))
                        }
                    )
            ) {
                "${idxWiden} is not a correct widening of ${idx1} and ${idx2}"
            }
        } catch (_: StorageAnalysis.InfeasibleState) {
            assertTrue(idx1.concretize().isEmpty() && idx2.concretize().isEmpty()) {
                "bottom is not a correct widening of ${idx1} and ${idx2}"
            }
        }

    }

    /**
     * Generates (an abstraction of) a pointer into [storageEnv]
     */
    @Provide
    fun storagePtrIdx(): Arbitrary<I> {
        val root = Int.any(0..10).map { (it*sz) + 1 }.set().ofMinSize(1)
        val idx: Arbitrary<SimpleQualifiedInt> = root.flatMapEach { _, y ->
            intValue(y, y+sz).map {
                SimpleQualifiedInt(it, setOf())
            }
        }.map {
            it.foldFirst { i1, i2 -> i1.joinOp(i2, mapOf(), mapOf(), false)}
        }
        val strides: Arbitrary<Set<Stride.SumOfTerms>> = root.flatMapEach { _, it ->
            sumOfTerms(it, it+10)
        }
        return combine(idx, strides) { i, s -> I(null, i, s.toTreapSet()) }
    }


    @Provide
    fun i(): Arbitrary<I> {
        val idx = intValue(0, 200).map { SimpleQualifiedInt(it, setOf())}
        val sum = sumOfTerms(1,10).set().ofSize(1..3)
        return combine(idx, sum) { b, c ->
            I(null, b, c.toTreapSet())
        }.filter {
            it.concretize().isNotEmpty()
        }
    }

    @Provide
    fun idx(): Arbitrary<SimpleQualifiedInt> {
        val qs = Int.any(0..100).flatMap { i ->
            Boolean.any().map { strong ->
                SimpleIntQualifier.ModularUpperBound(
                    i.asTACSymbol(), BigInteger.ONE, strong
                )
            }
        }.set().ofMaxSize(3)
        val range = intValue(0, 100)
        return combine(range, qs) { r, q -> SimpleQualifiedInt(r, q) }
    }

    @Provide
    fun intValue(lo: Int, hi: Int) : Arbitrary<IntValue> {
        val n = (hi - lo)/2
        val lb = Int.any(lo..lo + n)
        val ub = Int.any(lo + n..hi)
        return combine(lb, ub) { a:Int, b: Int ->
            IntValue(BigInteger.valueOf(a.toLong()), BigInteger.valueOf(b.toLong()))
        }
    }

    @Provide
    fun symValue(low: Int, hi: Int) : Arbitrary<Stride.SymValue> {
        return intValue(low, hi).map {
            Stride.SymValue(null, it)
        }
    }

    /* Generates strides:
           ki*xi + off with unique ki, ki \in [1..5], xi \in [0..10]
    */
    @Provide
    fun sumOfTerms(): Arbitrary<Stride.SumOfTerms> {
        val off = Int.any(0..10)
        val factors = arbFactor(1, 5, 0, 10).list().uniqueElements {
            it.first
        }.ofSize(1..3).map { it.toTreapMap() }
        return combine(factors, off) { f, o ->
            Stride.SumOfTerms(f, o.toBigInteger())
        }
    }

    /**
     * Returns a term [minOff, maxOff] + ki*xi where distinct ki \in [1,5], xi \in [0..10]
     * i.e. spanning [minOff, maxOff + 120]
     */
    private fun sumOfTerms(minOff: Int, maxOff: Int): Arbitrary<Stride.SumOfTerms> {
        val off = Int.any(minOff..maxOff)
        val factors = arbFactor(1, 5, 0, 10).list().uniqueElements {
            it.first
        }.ofSize(1..3).map { it.toTreapMap() }
        return combine(factors, off) { f, o ->
            Stride.SumOfTerms(f, o.toBigInteger())
        }
    }

    private fun arbFactor(fLow: Int, fHigh: Int, xLow: Int, xHigh: Int): Arbitrary<Pair<BigInteger, Stride.SymValue>> {
        return Int.any(fLow..fHigh) .flatMap { f ->
            symValue(xLow, xHigh).map { BigInteger.valueOf(f.toLong()) to it }
        }
    }
}
