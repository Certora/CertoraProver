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

package vc.data.tacexprutil

import analysis.CommandWithRequiredDecls
import analysis.TACTypeChecker
import datastructures.stdcollections.*
import tac.*
import vc.data.*


/**
 * A class for transforming a TACExpr to 3-Address-Form, i.e., to define new vars for nested expressions.
 * [varGenerator] should create a fresh variable each time its called.
 *
 * There are a few variants of the public functions, some result in an expression and the preceding commands
 * (defining the new variables), some result in only a single variable and the preceding command.
 */
class ExprUnfolder private constructor(val meta: MetaMap, val varGenerator: (Tag) -> TACSymbol.Var) {

    companion object {

        data class UnfolderResult<E : TACExpr>(val e: E, val cmds: List<TACCmd.Simple.AssigningCmd>) {
            val newVars = cmds.map { it.lhs }
        }

        fun type(e: TACExpr) =
            TACTypeChecker(TACSymbolTable(e)).typeCheck(e).let {
                it.resultOrNull() ?: error("Could not type $e in Expression Unfolder: $it")
            }


        /** Returns the final [TACExpr] and the list of new commands that should precede it. */
        fun unfold(e: TACExpr, meta: MetaMap = MetaMap(), varGenerator: (Tag) -> TACSymbol.Var) =
            ExprUnfolder(meta, varGenerator).let {
                UnfolderResult(it.unfold(type(e)), it.list)
            }


        /** Returns a single var corresponding to [e] and the list of new commands that should precede it. */
        fun unfoldToSingleVar(e: TACExpr, meta: MetaMap = MetaMap(), varGenerator: (Tag) -> TACSymbol.Var) =
            ExprUnfolder(meta, varGenerator).let {
                UnfolderResult(it.flat(type(e)), it.list)
            }

        fun unfold(tempVarPrefix: String, expr: TACExpr, meta: MetaMap = MetaMap()) =
            unfold(expr, meta) { tag ->
                tempVar(tempVarPrefix, tag)
            }


        fun unfoldToSingleVar(tempVarPrefix: String, expr: TACExpr, meta: MetaMap = MetaMap()) =
            unfoldToSingleVar(expr, meta) { tag ->
                tempVar(tempVarPrefix, tag)
            }

        /** unfolds, creates one variable that represents [expr], and puts it to use as the last command using [last]*/
        fun unfoldPlusOneCmd(
            tempVarPrefix: String,
            expr: TACExpr,
            meta: MetaMap = MetaMap(),
            last: (TACExpr.Sym) -> TACCmd.Simple
        ): CommandWithRequiredDecls<TACCmd.Simple> {
            val unfoldRes = unfoldToSingleVar(tempVarPrefix, expr, meta)
            return CommandWithRequiredDecls(
                unfoldRes.cmds + last(unfoldRes.e),
                unfoldRes.newVars.toSet()
            )
        }

        /**
         * unfolds, creates a last simple expression that represents [expr], and puts it to use as the last command
         * using [last]
         */
        fun unfoldToExprPlusOneCmd(
            tempVarPrefix: String,
            expr: TACExpr,
            meta: MetaMap = MetaMap(),
            last: (TACExpr) -> TACCmd.Simple
        ): CommandWithRequiredDecls<TACCmd.Simple> {
            val unfoldRes = unfold(tempVarPrefix, expr, meta)
            return CommandWithRequiredDecls(
                unfoldRes.cmds + last(unfoldRes.e),
                unfoldRes.newVars.toSet()
            )
        }

        fun unfoldTo(expr: TACExpr, resultVar: TACSymbol.Var, meta: MetaMap = MetaMap()): CommandWithRequiredDecls<TACCmd.Simple> {
            val unfoldRes = unfold("t", expr, meta)
            return CommandWithRequiredDecls(
                unfoldRes.cmds + TACCmd.Simple.AssigningCmd.AssignExpCmd(resultVar, unfoldRes.e, meta),
                unfoldRes.newVars.toSet()
            )
        }

        fun unfoldAll(
            code: CoreTACProgram,
            toUnfold: (TACCmd.Simple.AssigningCmd.AssignExpCmd) -> Boolean
        ): CoreTACProgram {
            val patcher = ConcurrentPatchingProgram(code)
            code.parallelLtacStream().forEach { (ptr, cmd) ->
                if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && toUnfold(cmd)) {
                    patcher.replace(
                        ptr,
                        unfoldToExprPlusOneCmd("unfold", cmd.rhs) {
                            cmd.copy(rhs = it)
                        }
                    )
                }
            }
            return patcher.toCode()
        }

    }

    private val list = mutableListOf<TACCmd.Simple.AssigningCmd>()

    /**
     * flattens all subexpressions (i.e., gets a new variable for each of them), and then returns [e] with the
     * subexpressions replaced by the new variables.
     */
    private fun unfold(e: TACExpr): TACExpr = when (e) {
        // Don't flatten the map defintiion lambda
        is TACExpr.MapDefinition -> e
        // Don't flatten quantified formulas
        is TACExpr.QuantifiedFormula -> e
        // Don't flatten multi-dimensional selects
        is TACExpr.Select -> e.rebuild(
            listOf(
                if (e.base is TACExpr.Select) {
                    unfold(e.base)
                } else {
                    flat(e.base)
                },
                flat(e.loc)
            )
        )

        is TACExpr.Unconstrained -> {
            val v = varGenerator(e.tagAssumeChecked)
            list += TACCmd.Simple.AssigningCmd.AssignHavocCmd(v, meta)
            v.asSym()
        }

        else -> e.rebuild(e.getOperands().map(::flat))
    }


    private val flatCache = mutableMapOf<TACExpr, TACSymbol.Var>()

    /**
     * Creates a new variable and adds a command to [list] that assigns it the expression [e] after
     * recursively flattening it.
     */
    private fun flat(e: TACExpr): TACExpr.Sym {
        flatCache[e]?.let {
            return it.asSym()
        }
        val r = unfold(e)
        if (r is TACExpr.Sym) {
            return r
        }
        val tag = r.tagAssumeChecked
        val v = varGenerator(tag)
        list += TACCmd.Simple.AssigningCmd.AssignExpCmd(v, r, meta = meta)
        flatCache[e] = v
        return v.asSym()
    }

}
