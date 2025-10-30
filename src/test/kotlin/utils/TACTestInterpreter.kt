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

package utils

import analysis.LTACCmd
import compiler.applyKeccakList
import datastructures.stdcollections.singleOrNull
import org.junit.jupiter.api.Assertions.assertTrue
import vc.data.TACBuiltInFunction
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import java.math.BigInteger

fun TACExpr.eval(state: Map<TACSymbol.Var, BigInteger>): BigInteger? {
    return when(this) {
        is TACExpr.BinOp.Div -> this.o1.eval(state)!!.div(this.o2.eval(state)!!)
        is TACExpr.BinOp.Sub -> this.o1.eval(state)!! - this.o2.eval(state)!!
        is TACExpr.BinOp.IntSub -> this.o1.eval(state)!! - this.o2.eval(state)!!
        is TACExpr.BinOp.Mod -> this.o1.eval(state)!!.mod(this.o2.eval(state)!!)
        is TACExpr.Vec.Add -> this.o1.eval(state)!!.add(this.o2.eval(state)!!)
        is TACExpr.Sym.Var -> {
            assertTrue(this.s in state)
            state[this.s]!!
        }
        is TACExpr.Sym.Const -> this.evalAsConst()
        is TACExpr.Apply ->
            (f as? TACExpr.TACFunctionSym.BuiltIn)?.let {
                if (it.bif is TACBuiltInFunction.SafeMathNarrow) {
                    this.ops.singleOrNull()?.eval(state)
                } else {
                    error("Unexpected function application $this")
                }
            }
        else ->
            error("Unexpected expression form $this")
    }
}

fun TACCmd.Simple.interpret(state: MutableMap<TACSymbol.Var, BigInteger>) {
    when (this) {
        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
            state[this.lhs] = this.rhs.eval(state)!!
        }

        is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> {
            val argvs = this.args.map { it.asSym().eval(state)!! }
            state[this.lhs] = applyKeccakList(argvs)
        }

        else ->
            error("Unexpected command $this")
    }
}

fun List<LTACCmd>.interpret(state: MutableMap<TACSymbol.Var, BigInteger>) {
    for (c in this) {
        c.cmd.interpret(state)
    }
}
