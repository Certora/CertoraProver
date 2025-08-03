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

import datastructures.stdcollections.*
import analysis.numeric.IntValue
import analysis.numeric.SimpleIntQualifier
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.storage.StorageAnalysis.SValue.I
import utils.flatMapToSet
import utils.mapToSet
import vc.data.TACSymbol
import java.math.BigInteger

/**
 * The values denoted by an SValue.I, which are the intersection of the concretization
 * its three components
 */
fun I.concretize(): Set<BigInteger> {
    return this.i.x.concretize().intersect(stride.concretize()).let {
        this.cs?.intersect(it) ?: it
    }
}

/**
 * The concretization of a StorageIdx is the values denoted by its interval,
 * modulo any MultipleOf qualifiers.
 *
 * As this is state-independent, we ignore other relational qualifiers.
 */
fun SimpleQualifiedInt.concretize(): Set<BigInteger> {
    return x.concretize().filterTo(mutableSetOf()) { i ->
        qual.all {
            when(it) {
                is SimpleIntQualifier.ModularUpperBound ->
                    (it.other as? TACSymbol.Const)?.value?.let { v ->
                        if (it.strong) { i < v } else { i <= v }
                            && (v - i).mod(it.modulus) == BigInteger.ZERO
                    } ?: true

                is SimpleIntQualifier.MultipleOf ->
                    i.mod(it.factor) == BigInteger.ZERO

                is SimpleIntQualifier.Condition -> true
                is SimpleIntQualifier.LogicalConnective -> true
                is SimpleIntQualifier.MustEqual -> true
            }
        }
    }
}

/**
 * The concretization of an IntValue is just the set of integers
 * i s.t. lb <= i <= ub
 */
fun IntValue.concretize(): Set<BigInteger> {
    var i = lb
    val g = mutableSetOf<BigInteger>()
    while (i <= ub) {
        g.add(i)
        i++
    }
    return g
}

/**
 * The concretization of a set of strides is just the union
 * of the concretization of each stride
 *
 * @throws Exception if Top is a member of the set, because we don't want
 * to actually enumerate the entire range!
 */
fun Set<Stride>.concretize(): Set<BigInteger> {
    return flatMapToSet { (it as Stride.SumOfTerms).concretize() }
}

/**
 * The concretization of a SumOfTerms is the union of the sums of all the possible
 * concretizations of its components
 */
fun Stride.SumOfTerms.concretize(): Set<BigInteger> {
    return factored.concretize().mapToSet { off + it }
}

/**
 * For each (k, f) in this, concretize(k,f) = {k*i | i \in concretize(f)}. Thus
 * the concretization of the map [k0:=f0, k1:=f1, ..., kn:=fn] is
 * { c0 + c1 + ... + cn | c0 \in concretize(k0,f0), c1 \in concretize(k1,f1), ... cn \in concretize(kn,fn) }
 */
fun Map<BigInteger, Stride.SymValue>.concretize(): Set<BigInteger> {
    return this.entries.fold(setOf(BigInteger.ZERO)) { a, b ->
        a.flatMapToSet { p ->
            b.value.v.concretize().mapToSet { p + (b.key * it) }
        }
    }
}
