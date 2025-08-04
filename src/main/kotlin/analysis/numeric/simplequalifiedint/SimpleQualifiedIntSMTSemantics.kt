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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.numeric.simplequalifiedint

import analysis.numeric.ConditionQualifier
import analysis.numeric.LogicalConnectiveQualifier
import analysis.numeric.SimpleIntQualifier
import datastructures.stdcollections.*
import analysis.smtblaster.ISMTTermBuilder
import smtlibutils.data.SmtExp
import smtlibutils.data.Sort
import vc.data.TACSymbol

class SimpleQualifiedIntSMTSemantics(private val builder: ISMTTermBuilder<Sort, SmtExp>) {

    fun ConditionQualifier.Condition.smtCond(o1: SmtExp, o2: SmtExp): SmtExp = with(builder) {
        when (this@smtCond) {
            ConditionQualifier.Condition.EQ -> eq(o1, o2)
            ConditionQualifier.Condition.NEQ -> lnot(eq(o1, o2))
            ConditionQualifier.Condition.LT -> lt(o1, o2)
            ConditionQualifier.Condition.LE -> le(o1, o2)
            ConditionQualifier.Condition.SLT -> slt(o1, o2)
            ConditionQualifier.Condition.SLE -> sle(o1, o2)
        }
    }

    val TACSymbol.asSMT
        get() = when (this) {
                is TACSymbol.Const -> builder.const(this.value)
                is TACSymbol.Var -> builder.toIdent(this.toSMTRep())
            }

    fun SimpleIntQualifier.smtRep(me: SmtExp): SmtExp = with(builder) {
        when (this@smtRep) {
            is SimpleIntQualifier.Condition ->
                ite(
                    me.bvPosBool,
                    condition.smtCond(op1.asSMT, op2.asSMT),
                    lnot(condition.smtCond(op1.asSMT, op2.asSMT))
                )

            is SimpleIntQualifier.LogicalConnective ->
                connective.smtCond(applyNot, op1.asSMT, op2.asSMT).let { q ->
                    ite(me.bvPosBool, q, lnot(q))
                }

            is SimpleIntQualifier.ModularUpperBound ->
                land(
                    eq(
                        mult(const(modulus), div(minus(other.asSMT, me), const(modulus))),
                        minus(other.asSMT, me)
                    ),
                    if (strong) {
                        lt(me, other.asSMT)
                    } else {
                        le(me, other.asSMT)
                    }
                )

            is SimpleIntQualifier.MultipleOf ->
                eq(mult(div(me, const(factor)), const(factor)), me)

            is SimpleIntQualifier.MustEqual ->
                eq(me, other.asSMT)
        }
    }

    val SmtExp.bvPosBool get() = builder.lnot(builder.eq(this, builder.const(0)))

    val SmtExp.bvNegBool get() = builder.eq(this, builder.const(0))

    private fun LogicalConnectiveQualifier.LBinOp.smtCond(negate: Boolean, o1: SmtExp, o2: SmtExp): SmtExp = with(builder) {
        val v1 = if (negate) {
            o1.bvNegBool
        } else {
            o1.bvPosBool
        }
        val v2 = if (negate) {
            o2.bvNegBool
        } else {
            o2.bvPosBool
        }
        when (this@smtCond) {
            LogicalConnectiveQualifier.LBinOp.OR -> lor(v1, v2)
            LogicalConnectiveQualifier.LBinOp.AND -> land(v1, v2)
        }
    }

    fun SimpleQualifiedInt.smtRep(me: SmtExp): SmtExp = with(builder) {
        return land(
            listOf(le(const(x.lb), me), le(me, const(x.ub))) +
                qual.map { it.smtRep(me) }
        )
    }

}
