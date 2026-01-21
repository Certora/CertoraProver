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
import java.math.BigInteger

/**
 * [SbfLinearConstraint] represents [e1] [op] [e2]
 *
 * [op] can be a signed or unsigned comparison operator
*/
data class SbfLinearConstraint(val op: CondOp, val e1: LinearExpression, val e2: LinearExpression)
    : Comparable<SbfLinearConstraint>{

    constructor(op: CondOp, v: ExpressionVar, n: ExpressionNum): this(op, LinearExpression(v), LinearExpression(n))

    override fun toString() = "$e1 $op $e2"

    override fun compareTo(other: SbfLinearConstraint): Int {
        return if (op < other.op) {
            -1
        } else if (op > other.op) {
            1
        } else {
            val x = e1.compareTo(other.e1)
            if (x == 0) {
                e2.compareTo(other.e2)
            } else {
                x
            }
        }
    }

    // Whether v1 op v2 evaluates to true or false
    private fun eval(v1: ExpressionNum, v2: ExpressionNum, op: CondOp): Boolean {
        val x = v1.n
        val y = v2.n
        return when (op) {
            CondOp.EQ -> x == y
            CondOp.NE -> x != y
            CondOp.SGE -> x >= y
            CondOp.SGT -> x > y
            CondOp.SLE -> x <= y
            CondOp.SLT -> x < y
            CondOp.GE, CondOp.GT -> {
                // If both operands are positive or negative then we can use signed interpretation.
                // Otherwise, we need to look at case-by-case.
                if (x >= BigInteger.ZERO) {
                    if (y >= BigInteger.ZERO) {
                        if (op == CondOp.GE) {x >= y} else {x > y}
                    } else {
                        false
                    }
                } else {
                    if (y >= BigInteger.ZERO) {
                        true
                    } else {
                        if (op == CondOp.GE) {x >= y} else {x > y}
                    }
                }
            }
            CondOp.LE, CondOp.LT -> {
                // This is a recursive call but the next call will match CondOp.GE, CondOp.GT case
                val newOp = op.swap()
                check(newOp == CondOp.GE || newOp == CondOp.GT)
                eval(v2, v1, newOp)
            }
        }
    }

    fun negate() = SbfLinearConstraint(op.negate(), e1, e2)

    fun swap() = SbfLinearConstraint(op.swap(), e2, e1)

    /**
     * Rewrite `this` to an equivalent form that is simpler for reasoning
     **/
    fun normalize(): SbfLinearConstraint {
        return run {
            // If e1 is constant then swap: we want the constant expression in e2
            if (this.e1.getConstant() != null) {
                this.swap()
            } else {
                this
            }
        }.run {
            // Remove strict inequalities
            when (this.op) {
                CondOp.EQ, CondOp.NE, CondOp.LE, CondOp.GE, CondOp.SLE, CondOp.SGE -> {
                    this
                }
                CondOp.LT -> { // x < y -> x <= y -1
                    SbfLinearConstraint(CondOp.LE, this.e1, this.e2.sub(ExpressionNum(1)))
                }
                CondOp.SLT -> {
                    SbfLinearConstraint(CondOp.SLE, this.e1, this.e2.sub(ExpressionNum(1)))
                }
                CondOp.GT -> { // x > y -> x >= y + 1
                    SbfLinearConstraint(CondOp.GE, this.e1, this.e2.add(ExpressionNum(1)))
                }
                CondOp.SGT -> {
                    SbfLinearConstraint(CondOp.SGE, this.e1, this.e2.add(ExpressionNum(1)))
                }
            }
        }
    }

    fun isContradiction(): Boolean {
        val v1 = e1.getConstant()
        val v2 = e2.getConstant()
        return if (v1 != null && v2 != null) {
            !eval(v1, v2, op)
        } else {
            false
        }
    }

    fun isTautology(): Boolean {
        val v1 = e1.getConstant()
        val v2 = e2.getConstant()
        return if (v1 != null && v2 != null) {
            eval(v1, v2, op)
        } else {
            false
        }
    }

    // Check that + does not perform extra allocations
    fun getVariables(): List<Variable> = e1.getVariables() + e2.getVariables()

    fun contains(v: ExpressionVar): Boolean {
        return e1.contains(v) || e2.contains(v)
    }

    fun substitute(oldV: ExpressionVar, newE: LinearExpression): SbfLinearConstraint {
        return SbfLinearConstraint(op, e1.substitute(oldV, newE), e2.substitute(oldV, newE))
    }

    fun eval(v: ExpressionVar, n: ExpressionNum): SbfLinearConstraint {
        return SbfLinearConstraint(op, e1.eval(v, n), e2.eval(v, n))
    }
}

/**
 * [SbfIntervalConstraint] represents an interval constraint of the form [v] [op] [n]
 */
data class SbfIntervalConstraint(val op: CondOp, val v: ExpressionVar, val n: ExpressionNum) {
    companion object {
        fun isIntervalConstraint(c: SbfLinearConstraint): Boolean {
            // c is already oriented so that the variable is on the left and the constant on the right
            return (c.e1.getVariable() != null && c.e2.getConstant() != null)
        }
    }
    constructor(c: SbfLinearConstraint):
        this(c.op,
            c.e1.getVariable() ?: error("Unexpected interval constraint $c: ${c.e1} is expected to be VAR"),
            c.e2.getConstant() ?: error("Unexpected interval constraint $c: ${c.e2} is expected to be NUM"))

    override fun toString() = "$v $op $n"
}
