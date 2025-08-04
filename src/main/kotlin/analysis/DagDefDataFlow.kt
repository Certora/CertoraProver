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

import analysis.dataflow.StrictDefAnalysis
import analysis.worklist.volatileDagDataFlow
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.NBId
import utils.Color.Companion.blue
import vc.data.*
import vc.data.tacexprutil.getFreeVars
import java.math.BigInteger


/**
 * A class for calculating a mapping of lhs variables to some abstract domain [T]. Given a [join] method for this
 * domain, and overriding the functions here that for each command (especially [TACCmd.Simple.AssigningCmd]s), say
 * what is the value of the lhs as a function of the values at the rhs, this will calculate the value of all lhs
 * and rhs variables.
 *
 * Don't use in a concurrent setting because of the internal caches.
 */
abstract class DagDefDataFlow<T : Any>(val code: CoreTACProgram) {

    abstract fun join(t1: T, t2: T): T
    abstract fun uninitialized(v: TACSymbol.Var): T
    abstract fun handleAssign(ptr: CmdPointer, cmd: TACCmd.Simple.AssigningCmd, varValues: Map<TACSymbol.Var, T>): T

    /** applied in two places before assigning calculated value [t] to the lhs at [ptr] */
    open fun normalizeLhs(ptr: CmdPointer, t: T) = t

    /** applied after [join]ing all values of def sites of [v] to get the current value of [v] at an rhs. */
    open fun normalizeRhs(ptr: CmdPointer, v: TACSymbol.Var, t: T) = t

    /**
     * non-assigns can also save an lhs. That's strange, but it makes sense especially because of
     * [TACCmd.Simple.ByteLongCopy], which for some reasons is not considered an assigning command.
     */
    open fun handleNonAssigns(ptr: CmdPointer, cmd: TACCmd.Simple, varValues: Map<TACSymbol.Var, T>): T? = null

    val g: TACCommandGraph = code.analysisCache.graph
    val def: StrictDefAnalysis = code.analysisCache.strictDef

    private val lhsMap = mutableMapOf<CmdPointer, T>()
    private val rhsMap = mutableMapOf<LTACVar, T>()

    private fun putLhs(ptr: CmdPointer, t: T) {
        lhsMap[ptr] = normalizeLhs(ptr, t)
    }

    open fun go() {
        g.lcmdSequence(code.topoSortFw).forEach { (ptr, cmd) ->
            val varValues = cmd.getFreeVarsOfRhs().associateWith {
                rhsVal(ptr, it)
            }
            when (cmd) {
                is TACCmd.Simple.AssigningCmd ->
                    putLhs(ptr, handleAssign(ptr, cmd, varValues))

                else ->
                    handleNonAssigns(ptr, cmd, varValues)?.let {
                        putLhs(ptr, it)
                    }
            }
        }
    }

    fun rhsVal(ptr: CmdPointer, v: TACSymbol.Var): T =
        rhsMap.getOrPut(LTACVar(ptr, v)) {
            def.defSitesOf(v, ptr)
                .toTreapSet() // it's already a TreapSet
                .mapReduce(
                    map = { lhsMap[it] ?: uninitialized(v) },
                    reduce = ::join
                )!!.let {
                    normalizeRhs(ptr, v, it)
                }
        }

    fun lhsVal(ptr: CmdPointer) =
        lhsMap[ptr]

    open fun debugPrinter() =
        TACProgramPrinter.standard()
            .extraLines { (ptr, _) ->
                lhsVal(ptr)
                    ?.takeIf { g.getLhs(ptr)?.let(::uninitialized) != it }
                    ?.let { listOf("   ${it.blue}") }
                    ?: emptyList()
            }

}


/**
 * A version of [DagDefDataFlow] that is given [handleExpr], which calculates the parallel of an expression in the
 * abstract domain world.
 *
 * As a simple example, constant propagation can be implemented easily by giving [TACExpr]'s `eval` method as
 * [handleExpr], and all the rest is automatic.
 */
abstract class DagDefExprDataFlow<T : Any>(code: CoreTACProgram) : DagDefDataFlow<T>(code) {
    abstract fun handleConst(i: BigInteger): T

    /** this will never be called with [e] as an atomic [TACExpr.Sym] */
    abstract fun handleExpr(ptr: CmdPointer, e: TACExpr, values: List<T>): T

    open fun handleOtherAssigns(ptr: CmdPointer, cmd: TACCmd.Simple.AssigningCmd, varValues: Map<TACSymbol.Var, T>): T =
        uninitialized(cmd.lhs)

    /** Recursively call [handleExpr] on [exp], use the appropriate special cases for constants and variables  */
    private fun calcExpr(ptr: CmdPointer, e: TACExpr, varValues: Map<TACSymbol.Var, T>): T =
        when (e) {
            is TACExpr.Sym.Const -> handleConst(e.s.value)
            is TACExpr.Sym.Var -> varValues[e.s] ?: uninitialized(e.s)
            else -> handleExpr(ptr, e, e.getOperands().map { calcExpr(ptr, it, varValues) })
        }

    /** The values of non trivial rhs expressions are not cached, but are recalculated if queried here. */
    fun rhsVal(ptr: CmdPointer, exp: TACExpr): T =
        calcExpr(ptr, exp, exp.getFreeVars().associateWith { rhsVal(ptr, it) })

    override fun handleAssign(ptr: CmdPointer, cmd: TACCmd.Simple.AssigningCmd, varValues: Map<TACSymbol.Var, T>): T {
        if (cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            return handleOtherAssigns(ptr, cmd, varValues)
        }
        return calcExpr(ptr, cmd.rhs, varValues)
    }

    fun rhsVal(ptr: CmdPointer, v: TACSymbol): T =
        when (v) {
            is TACSymbol.Const -> handleConst(v.value)
            is TACSymbol.Var -> rhsVal(ptr, v)
        }

}



/**
 * A shorthand for [volatileDagDataFlow] where we are working on a tac program and we want a forward calculation
 */
fun <D> forwardVolatileDagDataFlow(code : CoreTACProgram, calc: (NBId, List<D>) -> D) =
    volatileDagDataFlow(code.analysisCache.graph.blockPred, calc)

/**
 * A shorthand for [volatileDagDataFlow] where we are working on a tac program and we want a backward calculation
 */
fun <D> backwardVolatileDagDataFlow(code : CoreTACProgram, calc: (NBId, List<D>) -> D) =
    volatileDagDataFlow(code.analysisCache.graph.blockSucc, calc)
