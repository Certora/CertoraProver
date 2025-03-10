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

package spec

import analysis.CommandWithRequiredDecls
import datastructures.stdcollections.*
import tac.Tag
import vc.data.*

interface SafeMathCodeGen {

    /**
     * Performs a math operation defined by [mk] where the operands are [op1] and [op2] and the
     * result is placed in [lhs]. In addition, the math operation is assumed to not overflow using
     * the bif command [bif]. The commands produced for this operation are placed into [output],
     * and three temporary variables are placed in [vars]
     */
    private fun safeMathOp(
        output: MutableList<in TACCmd.Simple>,
        vars: MutableCollection<TACSymbol.Var>,
        lhs: TACSymbol.Var,
        op1: TACSymbol,
        op2: TACSymbol,
        bif: TACBuiltInFunction,
        mk: (TACExpr, TACExpr) -> TACExpr
    ) {
        val noOverflow = TACKeyword.TMP(Tag.Bool, "!no_ofl").toUnique("!")
        vars.add(noOverflow)
        vars.add(lhs)
        (op1 as? TACSymbol.Var)?.let(vars::add)
        (op2 as? TACSymbol.Var)?.let(vars::add)
        output.addAll(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = noOverflow,
                    rhs = TACExpr.Apply(bif, listOf(op1.asSym(), op2.asSym()), Tag.Bool)
                ),
                TACCmd.Simple.AssumeCmd(noOverflow),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = lhs,
                    rhs = mk(
                        op1.asSym(), op2.asSym()
                    )
                )
            )
        )
    }

    /**
     * Do a safe math add of [op1] and [op2] with the result placed in [lhs], assuming that the operation does not
     * overflow. The commands are placed in [output], and temp variables in [vars].
     */
    fun safeAdd(
        output: MutableList<in TACCmd.Simple>,
        vars: MutableCollection<TACSymbol.Var>,
        lhs: TACSymbol.Var,
        op1: TACSymbol,
        op2: TACSymbol
    ) {
        safeMathOp(output, vars, lhs, op1, op2, TACBuiltInFunction.NoAddOverflowCheck(Tag.Bit256), TACExpr.Vec.Add.Companion::invoke)
    }

    /**
     * A "functional" version of the above, which return the commands, variable declarations in a [CommandWithRequiredDecls]
     * object.
     */
    fun safeAdd(lhs: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol) : CommandWithRequiredDecls<TACCmd.Simple> {
        val output = mutableListOf<TACCmd.Simple>()
        val vars = mutableSetOf<TACSymbol.Var>()
        safeAdd(
            output, vars, lhs, op1, op2
        )
        return CommandWithRequiredDecls(output,  vars)
    }

    /**
     * Do a safe math multiplication of [op1] and [op2] where the operation is assumed to not overflow.
     * The commands are placed in [output], and variable declarations in [vars]
     */
    fun safeMul(
        output: MutableList<in TACCmd.Simple>,
        vars: MutableCollection<TACSymbol.Var>,
        lhs: TACSymbol.Var,
        op1: TACSymbol,
        op2: TACSymbol
    ) {
        safeMathOp(output, vars, lhs, op1, op2, TACBuiltInFunction.NoMulOverflowCheck(Tag.Bit256), TACExpr.Vec::Mul)
    }

    /**
     * A functional version of the above, where the commands, variable declarations are encapsulated in
     * the returned [CommandWithRequiredDecls] instance.
     */
    fun safeMul(lhs: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol) : CommandWithRequiredDecls<TACCmd.Simple> {
        val output = mutableListOf<TACCmd.Simple>()
        val vars = mutableSetOf<TACSymbol.Var>()
        safeMul(
            output, vars, lhs, op1, op2
        )
        return CommandWithRequiredDecls(output,  vars)
    }
}
