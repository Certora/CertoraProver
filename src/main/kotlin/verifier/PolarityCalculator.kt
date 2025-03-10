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

package verifier

import analysis.ExprView
import analysis.enarrow
import com.certora.collect.*
import tac.MetaKey
import utils.HasKSerializable
import utils.KSerializable
import utils.hashObject
import vc.data.*
import verifier.PolarityCalculator.Polarity.Companion.joinNull


class PolarityCalculator(val prog: CoreTACProgram) {

    /** This should really be an enum class, but enums don't have a stable hash in kotlin */
    @KSerializable
    @Treapable
    sealed class Polarity : HasKSerializable, java.io.Serializable {
        @KSerializable object POS : Polarity() { private fun readResolve(): Any = POS }
        @KSerializable object NEG : Polarity() { private fun readResolve(): Any = NEG }
        @KSerializable object BOTH : Polarity() { private fun readResolve(): Any = BOTH }

        override fun hashCode() = hashObject(this) // <-- need this to get hash code stability

        val neg
            get() = when (this) {
                POS -> NEG
                NEG -> POS
                BOTH -> BOTH
            }

        infix fun join(other: Polarity) = when {
            this == other -> this
            else -> BOTH
        }

        infix operator fun times(other: Polarity) = when {
            this == BOTH || other == BOTH -> BOTH
            this == other -> POS
            else -> NEG
        }

        companion object {
            /** nulls are ignored */
            infix fun Polarity?.joinNull(other: Polarity?) = when {
                this == null -> other
                other == null -> this
                else -> this join other
            }

            fun Iterable<Polarity?>.joinNull() =
                fold<Polarity?, Polarity?>(null) { p1, p2 -> p1 joinNull p2 }
        }
    }

    companion object {
        val POLARITY = MetaKey<Polarity>("tac.polarity")

        /**
         * Reconstructs [expr] by traversing in post-order its sub-expressions, and transforming each according to
         * [transformation] which takes not only the sub-expression but also its polarity. Where [startWith] is the
         * polarity of [expr].
         *
         * This is a little expensive, so better use this only when sure something will happen
         */
        fun transform(
            expr: TACExpr,
            startWith: Polarity,
            transformation: (TACExpr, Polarity) -> TACExpr
        ): TACExpr {
            fun rec(e: TACExpr, polarity: Polarity): TACExpr {

                fun onOps(p: Polarity) = e.getOperands().map { rec(it, p) }

                return when (e) {
                    is TACExpr.BinBoolOp.LOr -> e.copy(ls = onOps(polarity))
                    is TACExpr.BinBoolOp.LAnd -> e.copy(ls = onOps(polarity))
                    is TACExpr.QuantifiedFormula -> e.copy(body = rec(e.body, polarity))
                    is TACExpr.UnaryExp.LNot -> e.copy(o = rec(e.o, polarity.neg))
                    is TACExpr.TernaryExp.Ite ->
                        e.copy(i = rec(e.i, Polarity.BOTH), t = rec(e.t, polarity), e = rec(e.e, polarity))
                    is TACExpr.AnnotationExp<*> -> e.copy(o = rec(e.o, polarity))
                    else -> e
                }.let { newE ->
                    transformation(newE, polarity)
                }
            }
            return rec(expr, startWith)
        }


        /**
         * Calculates the polarity of [v] within [expr]. If [v] has a few occurrences then their polarity is joined.
         */
        fun polarityWithinExpr(v: TACSymbol.Var, expr: TACExpr): Polarity {
            val vSym = v.asSym()
            fun rec(e: TACExpr, polarity: Polarity): Polarity? = when (e) {
                vSym -> polarity
                is TACExpr.Sym -> null

                is TACExpr.BinBoolOp.LOr, is TACExpr.BinBoolOp.LAnd ->
                    e.getOperands().map { rec(it, polarity) }.joinNull()

                is TACExpr.QuantifiedFormula -> rec(e.body, polarity)
                is TACExpr.UnaryExp.LNot -> rec(e.o, polarity.neg)
                is TACExpr.TernaryExp.Ite ->
                    rec(e.i, Polarity.BOTH) joinNull rec(e.t, polarity) joinNull rec(e.e, polarity)

                else -> e.getOperands().map { rec(it, Polarity.BOTH) }.joinNull()
            }
            return rec(expr, Polarity.POS) ?: error("$v ${v.smtRep} does not appear in $expr")
        }
    }

    private val use = prog.analysisCache.use
    private val graph = prog.analysisCache.graph

    /**
     * Calculates the polarity of the variable at the lhs of the given assignment command. The base cases are that if
     * it appears in an assert then it is negative, and if in an assume then it is positive.
     *
     * If it's not used at all, then this will return null.
     */
    fun polarityOfLhs(lcmd: ExprView<TACExpr>): Polarity? =
        use.useSitesAfter(lcmd.lhs, lcmd.ptr).mapNotNull { site ->
            val useCmd = graph.elab(site)
            when (useCmd.cmd) {
                is TACCmd.Simple.AssumeNotCmd, is TACCmd.Simple.AssertCmd -> Polarity.NEG
                is TACCmd.Simple.AssumeCmd -> Polarity.POS
                is TACCmd.Simple.AssumeExpCmd-> polarityWithinExpr(lcmd.lhs, useCmd.cmd.cond)
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    polarityOfLhs(useCmd.enarrow())?.let {
                        it * polarityWithinExpr(lcmd.lhs, useCmd.cmd.rhs)
                    }
                }

                is TACCmd.Simple.AnnotationCmd -> null
                is TACCmd.Simple.JumpiCmd -> Polarity.BOTH
                else -> error("Polarity not supported yet: $useCmd.cmd")
            }
        }.fold<Polarity?, Polarity?>(null) { p1, p2 -> p1 joinNull p2 }
}
