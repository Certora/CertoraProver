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

package analysis.split

import algorithms.topologicalOrderOrNull
import analysis.CmdPointer
import analysis.CommandDataflowAnalysis
import analysis.TACBlock
import analysis.split.Ternary.Bottom
import analysis.split.Ternary.Companion.allXs
import analysis.split.Ternary.Companion.boolX
import analysis.split.Ternary.Companion.highOnes
import config.Config
import datastructures.UniqueCache
import evm.EVM_BITWIDTH256
import evm.EVM_BYTE_SIZE
import tac.Tag
import utils.*
import vc.data.*
import java.math.BigInteger

/**
 * Calculates a ternary over-approximation for each lhs of an assignment. This can be used to calculate the value of
 * any [TACExpr] at any point in the given code.
 *
 * At instantiation time the input is analyzed and the internal datastructre populated. Then there are two query
 * functions:
 * [getLhs] returns [Ternary] that over-approximates the lhs at the requested assignments command.
 * [getRhs] return the [Ternary] over-approximation of the given expression at the requested ptr (ignoring the current
 *          command.
 *
 *  @param isForbiddenVar indicates which variables should be given the [Ternary.allXs] no matter what.
 *  @param ternaryCache As repetitions of resulting [Ternary]s are highly expected, this will save memory. If this
 *      parameter is not supplied, a new one is created.
 *  @param highPrecision When set to true, all relevant definition sites of a variable are consulted when evaluting an
 *      expression containing that variable. When it is false, it is only consluted if it has a single definition site.
 *
 *  Currently, [highPrecision] can only be activated when [code]'s graph is acyclic. It's possible to fix, but will
 *  require a fixed point computation as in [CommandDataflowAnalysis]
 */
class TernaryCalculator(
    private val code: CoreTACProgram,
    private val isForbiddenVar: (TACSymbol.Var) -> Boolean,
    private val ternaryCache: UniqueCache<Ternary> = UniqueCache(),
    highPrecision: Boolean = false
) {
    private val highPrecision = highPrecision && Config.ternaryCalculatorHighPrecision.get()

    /** This is not private so that it can be reused... a bit hacky but simple */
    val singleDef by lazy { DefiniteDefSites(code) }
    private val multiDefs by lazy { code.analysisCache.strictDef }

    /**
     * Holds Ternary Values for each lhs of an assign command.
     *
     * For efficiency, it does not save any value we don't have any interesting information about.
     * That is, [Ternary.allXs], or such variables that are known to have small width.
     */
    private val map = mutableMapOf<CmdPointer, Ternary>()

    private fun default(v: TACSymbol.Var) =
        v.meta.find(TACMeta.ENV_BIT_WIDTH)
            ?.let { width -> // (256 - width) most significant bits are 0. Rest are unknown (so no 1s).
                Ternary(highOnes(EVM_BITWIDTH256 - width), BigInteger.ZERO)
            }
            ?: when (v.tag) {
                is Tag.Bits -> allXs
                Tag.Bool -> boolX
                else -> Bottom
            }


    /** Retrieves the ternary of the lhs at this ptr */
    fun getLhs(ptr: CmdPointer): Ternary {
        val v = code.analysisCache.graph.toCommand(ptr).getModifiedVar()!!
        return if (isForbiddenVar(v)) {
            default(v)
        } else {
            (map[ptr] ?: default(v))
        }
    }

    /** Calculates the Ternary of an expression used at this location (ignoring the current cmd) */
    fun getRhs(ptr: CmdPointer, e: TACExpr): Ternary =
        evalExp(e, ptr)

    /** sets the value for the lhs at this location */
    private fun setVal(ptr: CmdPointer, value: Ternary) {
        if (value != allXs) { // don't save total unknowns, they are the default.
            map[ptr] = ternaryCache(value)
        }
    }

    init {
        val order = if (highPrecision) {
            topologicalOrderOrNull(code.analysisCache.graph.blockSucc)?.reversed()
                ?: error("Can't run ${javaClass.simpleName} on a graph that contains cycles : ${code.name}")
        } else {
            // Since we may have loops in the graph, we run over a topological order of the domination tree.
            // This is good enough because to calculate values we only need the values in dominating blocks.
            code.analysisCache.domination.topologicalOrder
        }

        for (nbid in order) {
            calcBlock(code.analysisCache.graph.elab(nbid))
        }
    }

    private fun calcBlock(block: TACBlock) {
        for ((ptr, cmd) in block.commands) {
            if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                setVal(ptr, evalExp(cmd.rhs, ptr))
            }
        }
    }

    private fun <T> ifThenElseNull(cond: Boolean?, then: () -> T) =
        if (cond == true) then() else null


    /** Returns the [Ternary] of [v] at [ptr], ignoring the current cmd */
    private fun evalVar(v: TACSymbol.Var, ptr: CmdPointer): Ternary {
        return if (highPrecision) {
            multiDefs.defSitesOf(v, ptr)
                .mapToSet { it?.let(::getLhs) ?: return default(v) }
                .foldFirst(Ternary::join)
        } else {
            singleDef.getDefinitiveDef(ptr, v)?.let {
                getLhs(it)
            } ?: default(v)
        }
    }


    /** Returns the [Ternary] of [exp] at [ptr], ignoring the current cmd */
    private fun evalExp(exp: TACExpr, ptr: CmdPointer): Ternary {
        return when (exp) {
            is TACExpr.Sym.Const ->
                Ternary(exp.s.value)

            is TACExpr.Sym.Var ->
                if (isForbiddenVar(exp.s)) {
                    default(exp.s)
                } else {
                    evalVar(exp.s, ptr)
                }

            is TACExpr.UnaryExp -> {
                val t = evalExp(exp.o, ptr)
                when (exp) {
                    is TACExpr.UnaryExp.LNot ->
                        t.lnot()

                    is TACExpr.UnaryExp.BWNot ->
                        t.not()
                }
            }

            is TACExpr.Vec -> {
                val ts = exp.ls.map { evalExp(it, ptr) }
                if (ts.all { it.isConstant() }) {
                    return Ternary(exp.eval(ts.map { it.asConstant() }))
                }
                when (exp) {
                    is TACExpr.Vec.Add ->
                        ts.reduce { a, b -> a plus b }

                    is TACExpr.Vec.Mul -> {
                        if (ts.size != 2) {
                            allXs // TODO: collect constants?
                        } else {
                            when {
                                ts[1].isPowOfTwo() ->
                                    ts[0] shiftLeft ts[1].asConstant().lowestSetBit

                                ts[0].isPowOfTwo() ->
                                    ts[1] shiftLeft ts[0].asConstant().lowestSetBit

                                else ->
                                    allXs
                            }
                        }
                    }

                    else -> Bottom
                }
            }

            is TACExpr.BinBoolOp -> {
                val ts = exp.ls.map { evalExp(it, ptr) }
                when (exp) {
                    is TACExpr.BinBoolOp.LAnd ->
                        ts.reduce { t1, t2 -> t1 land t2 }

                    is TACExpr.BinBoolOp.LOr ->
                        ts.reduce { t1, t2 -> t1 lor t2 }
                }
            }

            is TACExpr.BinExp -> {
                val t1 = evalExp(exp.o1, ptr)
                val t2 = evalExp(exp.o2, ptr)
                if (t1.isConstant() && t2.isConstant()) {
                    return Ternary(exp.eval(t1.asConstant(), t2.asConstant()))
                }
                when (exp) {
                    is TACExpr.BinOp -> when (exp) {
                        is TACExpr.BinOp.BWAnd ->
                            t1 and t2

                        is TACExpr.BinOp.BWOr ->
                            t1 or t2

                        is TACExpr.BinOp.Div ->
                            ifThenElseNull(t2.isPowOfTwo()) {
                                t1 shiftRight t2.asConstant().lowestSetBit
                            } ?: allXs

                        is TACExpr.BinOp.Sub ->
                            t1 sub t2

                        is TACExpr.BinOp.Mod ->
                            ifThenElseNull(t2.isPowOfTwo()) {
                                t1 and Ternary(t2.asConstant() - BigInteger.ONE)
                            } ?: allXs

                        is TACExpr.BinOp.ShiftLeft ->
                            t2.asIntOrNull()?.let { by -> t1 shiftLeft by } ?: allXs

                        is TACExpr.BinOp.ShiftRightLogical ->
                            t2.asIntOrNull()?.let { by -> t1 shiftRight by } ?: allXs

                        is TACExpr.BinOp.ShiftRightArithmetical ->
                            t2.asIntOrNull()?.let { by -> (t1 shiftRight by) signExtend (256 - by) } ?: allXs

                        is TACExpr.BinOp.SignExtend ->
                            t1.asIntOrNull()?.let { b ->
                                val topBit = (b + 1) * EVM_BYTE_SIZE.intValueExact()
                                t2 signExtend topBit
                            } ?: allXs

                        else -> Bottom
                    }


                    is TACExpr.BinRel -> when (exp) {
                        is TACExpr.BinRel.Eq ->
                            t1 eq t2

                        is TACExpr.BinRel.Ge ->
                            t1 ge t2

                        is TACExpr.BinRel.Gt ->
                            t1 gt t2

                        is TACExpr.BinRel.Lt ->
                            t1 lt t2

                        is TACExpr.BinRel.Le ->
                            t1 le t2

                        else ->
                            boolX
                        // TODO: Signed comparisons
                    }

                    else ->
                        Bottom
                }
            }

            is TACExpr.TernaryExp.Ite ->
                when (evalExp(exp.i, ptr)) {
                    Ternary.one ->
                        evalExp(exp.t, ptr)

                    Ternary.zero ->
                        evalExp(exp.e, ptr)

                    else ->
                        evalExp(exp.t, ptr) join evalExp(exp.e, ptr)
                }

            else -> Bottom

        }
    }
}


