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

package report.cexanalysis

import analysis.CmdPointer
import datastructures.stdcollections.*
import tac.Tag
import utils.*
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.getOperands
import vc.data.tacexprutil.asVarOrNull
import vc.data.tacexprutil.getFreeVars
import java.math.BigInteger

class CounterExampleConeOfInf(private val cex: CounterExampleContext) {

    /**
     * Returns the a set of [CmdPointer] that are not necessary for the [cex]. For example:
     * ```
     *   1: havoc b
     *   2: havoc i
     *   3: j := i * 5
     *   4: k := ite(b, i, j)
     *   5: assert k > 100
     * ```
     * if in the counter example `b = true`, then line 3 is not needed.
     */
    fun unneededCmdPointers(): Set<CmdPointer> {
        val reversed = cex.cexCmdSequence().toList().reversed()
        val violatedCond = (reversed.first().cmd as TACCmd.Simple.AssertCmd).o
            .asVarOrNull ?: return emptySet()
        val neededVars = mutableSetOf(violatedCond)
        val unneededPtrs = mutableSetOf<CmdPointer>()
        for ((ptr, cmd) in reversed.subList(1, reversed.size)) {
            cmd.getLhs()
                ?.let { lhs ->
                    if (lhs in neededVars) {
                        neededVars += neededAtoms(cmd)
                    } else {
                        unneededPtrs += ptr
                    }
                    neededVars -= lhs
                }
                ?: run { neededVars += neededAtoms(cmd) }
        }
        return unneededPtrs
    }

    private fun neededAtoms(cmd: TACCmd.Simple): Set<TACSymbol.Var> =
        when (cmd) {
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> neededAtoms(cmd.rhs)
            is TACCmd.Simple.JumpiCmd -> cmd.cond.asVarOrNull?.let(::setOf).orEmpty()

            is TACCmd.Simple.Assume -> neededAtoms(cmd.condExpr)

            is TACCmd.Simple.JumpCmd,
            is TACCmd.Simple.AssigningCmd.AssignHavocCmd,
            is TACCmd.Simple.LogCmd,
            is TACCmd.Simple.NopCmd,
            is TACCmd.Simple.LabelCmd,
            is TACCmd.Simple.JumpdestCmd,
            is TACCmd.Simple.AssertCmd -> // we care only for the failed asserts, and no others.
                emptySet()

            is TACCmd.Simple.AnnotationCmd -> {
                // should we talk of mentioned vars? doesn't seem to make sense.
                emptySet()
            }

            else -> error("Should we support ${cmd.javaClass.simpleName}?")
        }


    private fun neededAtoms(e: TACExpr): Set<TACSymbol.Var> {
        fun default() = e.getOperands().map(::neededAtoms).flatMapToSet { it }

        return when (e) {
            is TACExpr.Sym.Const ->
                emptySet()

            is TACExpr.Sym.Var ->
                setOf(e.s)

            is TACExpr.BinBoolOp.LAnd ->
                if (cex(e) == BigInteger.ZERO) {
                    neededAtoms(e.ls.first { cex(it) == BigInteger.ZERO })
                } else {
                    default()
                }

            is TACExpr.BinBoolOp.LOr ->
                if (cex(e) == BigInteger.ONE) {
                    neededAtoms(e.ls.first { cex(it) == BigInteger.ONE })
                } else {
                    default()
                }

            is TACExpr.TernaryExp.Ite -> when (cex(e.i)) {
                BigInteger.ONE -> neededAtoms(e.t) + neededAtoms(e.i)
                BigInteger.ZERO -> neededAtoms(e.e) + neededAtoms(e.i)
                else -> default()
            }

            is TACExpr.TernaryExp.MulMod ->
                default() // we can do this as well...

            is TACExpr.BinOp.SDiv,
            is TACExpr.BinOp.SMod,
            is TACExpr.BinOp.Mod,
            is TACExpr.BinOp.Div,
            is TACExpr.BinOp.BWAnd,
            is TACExpr.Vec.Mul,
            is TACExpr.Vec.IntMul ->
                if (cex(e) == BigInteger.ZERO) {
                    e.getOperands().firstOrNull { cex(it) == BigInteger.ZERO }
                        ?.let(::neededAtoms)
                        ?: default()
                } else {
                    default()
                }

            is TACExpr.BinOp.IntMod,
            is TACExpr.BinOp.IntDiv ->
                e.getOperands()[0].let { firstOp ->
                    val value = cex(firstOp)
                    if (value == BigInteger.ZERO) {
                        neededAtoms(firstOp)
                    } else {
                        default()
                    }
                }

            is TACExpr.BinOp.ShiftLeft,
            is TACExpr.BinOp.ShiftRightArithmetical,
            is TACExpr.BinOp.ShiftRightLogical ->
                e.getOperands()[0].let { firstOp ->
                    val value = cex(firstOp)
                    if (value == BigInteger.ZERO || value == (e.tag as? Tag.Bits)?.maxUnsigned) {
                        neededAtoms(firstOp)
                    } else {
                        default()
                    }
                }

            is TACExpr.MapDefinition,
            is TACExpr.QuantifiedFormula -> e.getFreeVars()

            is TACExpr.LongStore,
            is TACExpr.MultiDimStore,
            is TACExpr.Select,
            is TACExpr.Store ->
                default() // handle these if we want to work with maps.

            else ->
                default()
        }
    }

}
