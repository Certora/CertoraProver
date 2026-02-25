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

import com.certora.collect.*
import analysis.numeric.linear.LinearEquality
import datastructures.stdcollections.*
import java.math.BigInteger

typealias VariableIndex = ULong

/** Only VariableFactory should create Variable instances **/
@Suppress("NAME_SHADOWING")
class VariableFactory {
    private var index: VariableIndex = 0U
    private val map: MutableMap<String, VariableIndex> = mutableMapOf()

    fun get(name:String): Variable {
        val idx = map[name]
        return if (idx != null) {
            Variable(name, idx)
        } else {
            val idx = index
            map[name] = idx
            index++
            Variable(name, idx)
        }
    }
}

@Treapable
open class Variable(val name: String, val index: VariableIndex): Comparable<Variable> {

    constructor(other: Variable): this(other.name, other.index)

    override fun compareTo(other: Variable): Int {
        return index.compareTo(other.index)
    }

    override fun hashCode(): Int {
        return index.hashCode()
    }

    override fun toString(): String {
        return name
    }

    override fun equals(other: Any?): Boolean {
        return when (other) {
            null -> {
                false
            }
            !is Variable -> {
                false
            }
            else -> {
                index == other.index
            }
        }
    }
}

/** A class to represent linear expressions **/

typealias ExpressionVar = Variable


private val MASK_8  = BigInteger.ONE.shiftLeft(8).subtract(BigInteger.ONE)
private val MASK_16 = BigInteger.ONE.shiftLeft(16).subtract(BigInteger.ONE)
private val MASK_32 = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)

/**
 * Zero-extends the low 8 bits of this [BigInteger].
 *
 * Semantics:
 *   - The value is truncated to 8 bits (mod 2^8)
 *   - The resulting bit-pattern is interpreted as unsigned
 *   - The result is represented as a non-negative [BigInteger]
 * Examples:
 *   -1        -> 255
 *   0x123     -> 0x23
 *   0xFF      -> 255
 */
private fun BigInteger.zext8(): BigInteger = this.and(MASK_8)
/**
 * Zero-extends the low 16 bits of this [BigInteger].
 *
 * Semantics:
 *   - The value is truncated to 16 bits (mod 2^16)
 *   - The resulting bit-pattern is interpreted as unsigned
 *   - The result is represented as a non-negative [BigInteger]
 * Examples:
 *   -1        -> 65535
 *   0x12345  -> 0x2345
 */
private fun BigInteger.zext16(): BigInteger = this.and(MASK_16)
/**
 * Zero-extends the low 32 bits of this [BigInteger].
 *
 * Semantics:
 *   - The value is truncated to 32 bits (mod 2^32)
 *   - The resulting bit-pattern is interpreted as unsigned
 *   - The result is represented as a non-negative [BigInteger]
 * Examples:
 *   -1              -> 4294967295
 *   0x1_0000_0001   -> 1
 */
private fun BigInteger.zext32(): BigInteger = this.and(MASK_32)


data class ExpressionNum(val n: BigInteger) {
    constructor(v: Long): this(BigInteger.valueOf(v))

    fun add(other: ExpressionNum): ExpressionNum {
        return ExpressionNum(n + other.n)
    }
    fun sub(other: ExpressionNum): ExpressionNum {
        return ExpressionNum(n - other.n)
    }
    fun mul(other: ExpressionNum): ExpressionNum {
        return ExpressionNum(n * other.n)
    }
    fun isPositive(): Boolean {
        return n > BigInteger.ZERO
    }
    fun isZero(): Boolean {
        return n == BigInteger.ZERO
    }
    fun isOne(): Boolean {
        return n == BigInteger.ONE
    }
    fun isMinusOne(): Boolean {
        return n == - BigInteger.ONE
    }
    fun zext(n: Int): ExpressionNum =
        when (n) {
            1 -> ExpressionNum(this.n.zext8())
            2 -> ExpressionNum(this.n.zext16())
            4 -> ExpressionNum(this.n.zext32())
            8 -> this
            else -> error("ExpressionNum::zext(n) only supports n={1,2,4,8} but given n=$n")
        }

    override fun toString(): String {
        return n.toString()
    }
}

/**
 * This class is similar to analysis.numeric.linear.LinearEquality.
 * [LinearExpression] represents c1 * v1 + c2 * v2 + ... + cn * vn + cst.
 * [LinearExpression] (similar to [LinearEquality]) has semantics unlike analysis.numeric.linear.LinearTerm.
 * The semantics of [LinearExpression] is defined over signed mathematical integers.
 *
 * We don't use [LinearEquality] because this class uses TAC variables and expressions,
 * and we don't have those at the time [LinearExpression] is used.
 */
data class LinearExpression(private val map: TreapMap<ExpressionVar, ExpressionNum>,
                            private val cst: ExpressionNum): Comparable<LinearExpression> {

    // Class invariant: for each (v,k) in map :: k != 0
    // that is, we keep map normalized so that we don't keep terms of the form variable multiplied by zero
    init {
        for (coef in map.values) {
            check(!(coef.isZero())) {"Cannot create a linear term with zero coefficient"}
        }
    }

    // Return new expression "cst"
    constructor(cst: ExpressionNum):
        this(treapMapOf(), cst)

    // Return new expression "v"
    constructor(v: ExpressionVar):
        this(treapMapOf<ExpressionVar, ExpressionNum>().put(v, ExpressionNum(1)),
             ExpressionNum(0))

    // Return new expression "coef*v"
    constructor(v: ExpressionVar, coef: ExpressionNum):
        this(treapMapOf<ExpressionVar, ExpressionNum>().let {
            if (!coef.isZero()) { it + (v to coef) } else { it }
        }, ExpressionNum(0))

    // Return new expression "this" + k*v
    private fun addTerm(v: ExpressionVar, k: ExpressionNum): LinearExpression {
        return if (k.isZero()) {
            this
        } else {
            val coef = map[v]
            val resMap =
                if (coef != null) {
                    val newCoef = coef.add(k)
                    if (newCoef.isZero()) {
                        map.remove(v)
                    } else {
                        map.put(v, newCoef)
                    }
                } else {
                    check(!k.isZero()) {"addTerm expects k value different from zero"}
                    map.put(v, k)
                }
            LinearExpression(resMap, cst)
        }
    }

    // Return null if the expression is not a constant
    fun getConstant(): ExpressionNum? {
        return if (map.isEmpty()) {
            cst
        } else {
            null
        }
    }

    // Return null if the expression is not a variable
    fun getVariable(): ExpressionVar? {
        if (!cst.isZero()) {
            return null
        }
        return if (map.size == 1) {
            val firstEntry = map.firstEntry()
            check(firstEntry != null ) {"LinearExpression map is not empty"}
            val v = firstEntry.key
            val k = firstEntry.value
            if (k.isOne()) {
                v
            } else {
                null
            }
        } else {
            null
        }
    }

    fun getVariables() = map.keys.toList()

    // Return new expression "this" + cst
    infix fun add(cst: ExpressionNum) = LinearExpression(map, this.cst.add(cst))

    // Return new expression "this" + v
    infix fun add(v: ExpressionVar) = addTerm(v, ExpressionNum(1))

    // Return new expression "this" + e
    infix fun add(e: LinearExpression): LinearExpression {
        val c = e.getConstant()
        if (c != null) {
            return add(c)
        }

        var res = add(e.cst)
        for (kv in e.map) {
            res = res.addTerm(kv.key, kv.value)
        }
        return res
    }

    // Return new expression "this" - cst
    infix fun sub(cst: ExpressionNum): LinearExpression {
        return LinearExpression(map, this.cst.sub(cst))
    }

    // Return new expression "this" - v
    infix fun sub(v: ExpressionVar): LinearExpression {
        return addTerm(v, ExpressionNum(-1))
    }

    // Return new expression "this" - e
    infix fun sub(e: LinearExpression): LinearExpression {
        val c = e.getConstant()
        if (c != null) {
            return sub(c)
        }

        var res = add(e.cst)
        for (kv in e.map) {
            res = res.addTerm(kv.key, ExpressionNum(0).sub(kv.value))
        }
        return res
    }

    // Return new expression e * n
    infix fun mul(n: ExpressionNum): LinearExpression {
        return if (n.isZero()) {
            LinearExpression(n)
        } else if (n.isOne()) {
            this
        } else {
            var resMap = treapMapOf<ExpressionVar, ExpressionNum>()
            for (kv in map) {
                val newCoef = kv.value.mul(n)
                if (!newCoef.isZero()) {
                    resMap = resMap.put(kv.key, newCoef)
                }
            }
            LinearExpression(resMap, cst.mul(n))
        }
    }

    fun contains(v: ExpressionVar): Boolean {
        return map.contains(v)
    }

    fun substitute(oldV: ExpressionVar, newV: ExpressionVar): LinearExpression {
        val coef = map[oldV]
        return if (coef == null) {
            this
        } else {
            LinearExpression(map.remove(oldV).put(newV, coef), cst)
        }
    }

    fun substitute(oldV: ExpressionVar, e: LinearExpression): LinearExpression {
        val coef = map[oldV]
        return if (coef == null) {
            this
        } else {
           this.sub(LinearExpression(oldV, coef)).add(e.mul(coef))
        }
    }

    fun eval(v: ExpressionVar, n: ExpressionNum): LinearExpression {
        val coef = map[v]
        return if (coef == null) {
            this
        } else {
            val newCst = cst.add(coef.mul(n))
            LinearExpression(map.remove(v), newCst)
        }
    }

    override fun compareTo(other: LinearExpression): Int {
        // zero if equal
        // < 0 if less
        // > 0 if greater
        // Based on https://en.cppreference.com/w/cpp/algorithm/lexicographical_compare

        val e1 = this
        val e2 = other
        fun lex(v1: ExpressionVar, c1: ExpressionNum, v2: ExpressionVar, c2: ExpressionNum): Boolean {
            return (v1.index < v2.index || (v1.index == v2.index && c1.n < c2.n))
        }
        if (e1.cst.n < e2.cst.n) {
            return -1
        } else if (e1.cst.n > e2.cst.n) {
            return 1
        }
        val it1 = e1.map.iterator()
        val it2 = e2.map.iterator()
        while (it1.hasNext() && it2.hasNext()) {
            val x1 = it1.next()
            val x2 = it2.next()
            if (lex(x1.key, x1.value, x2.key, x2.value)) {
                return -1
            }
            if (lex(x2.key, x2.value, x1.key, x1.value)) {
                return 1
            }
        }
        return if (!it1.hasNext()) {
                    if (!it2.hasNext()) {
                        0
                    } else {
                        -1
                    }
                } else {
                    1
                }
    }

    override fun toString(): String {
        var str = ""
        map.entries.forEachIndexed { index, kv ->
            val v = kv.key
            val coef = kv.value
            if (coef.isPositive() && index > 0) {
                str += "+"
            }
            if (coef.isMinusOne()) {
                str += "-"
            } else if (!coef.isOne()) {
                str += "$coef*"
            }
            str += "$v"
        }
        if (cst.isPositive() && map.isNotEmpty()) {
            str += "+"
        }
        if (!cst.isZero() || map.isEmpty()) {
            str += "$cst"
        }
        return str
    }
}

