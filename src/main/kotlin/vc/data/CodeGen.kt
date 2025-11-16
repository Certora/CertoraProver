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

package vc.data

import analysis.CommandWithRequiredDecls
import datastructures.stdcollections.*
import tac.Tag
import kotlin.reflect.KProperty

/**
 * Code gen for writing tac as pseudo kotlin.
 *
 * For example you can write:
 * ```
 * CodeGen.codeGen {
 *    val t1 by e { v add 1 }
 *    val t2 by e { t1 add 3 ]
 *    +TACCmd.Simple.ByteStore(base = ..., loc = t1, value = t2)
 * }
 * ```
 * This will generate a unique temporary (TAC) variable with a name based off of `t1` and `t2`, and
 * generate the relevant assignment commands. The `+` operator adds an explicit command to the list.
 *
 * The result of the above will be a CRD with the following:
 * ```
 * AssignExpCmd(t1!1, rhs = v + 1)
 * AssignExpCmd(t2!2, rhs = t1!1 + 3)
 * ByteStore(base = ..., loc = t1!1, value = t2!2)
 * ```
 *
 * NB That the *tac* variable names are uniquified versions of the *kotlin* names.
 *
 * You can also do "metaprogramming" like the following:
 *
 * ```
 * CodeGen.codeGen {
 *    for(i in ...) {
 *        val whatever by { f(i) }
 *        +ByteStore(whatever)
 *    }
 * }
 * ```
 *
 * but you risk making code reviewers angry with you.
 */
class CodeGen private constructor() {
    val vars = mutableSetOf<TACSymbol.Var>()
    val commands = mutableListOf<TACCmd.Simple>()

    class VarProxy(
        val v: TACSymbol.Var
    ) {
        operator fun getValue(
            thisRef: Any?,
            p: KProperty<*>
        ) : TACSymbol.Var = v
    }

    inner class VarProxyProvider(val tag: Tag, val gen: TACExprFactoryExtensions.() -> ToTACExpr) {
        operator fun provideDelegate(
            thisRef: Any?,
            p: KProperty<*>
        ) : VarProxy {
            val v = TACSymbol.Var(
                p.name,
                tag
            ).toUnique("!")
            vars.add(v)
            commands.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = v,
                    rhs = TACExprFactoryExtensions.gen().toTACExpr()
                )
            )
            return VarProxy(v)
        }
    }

    fun e(exp: TACExpr) = VarProxyProvider(exp.tagAssumeChecked) { exp }

    infix fun TACSymbol.Var.`=`(e: TACExpr) {
        commands.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = this,
            rhs = e
        ))
    }

    infix fun TACSymbol.Var.`=`(e: TACExprFactoryExtensions.() -> ToTACExpr) {
        commands.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = this,
            rhs = TACExprFactoryExtensions.e().toTACExpr()
        ))
    }

    fun e(tag: Tag = Tag.Bit256, x: TACExprFactoryExtensions.() -> ToTACExpr) = VarProxyProvider(tag, x)
    operator fun TACCmd.Simple.unaryPlus() {
        commands.add(this)
    }

    companion object {
        fun codeGen(f: CodeGen.() -> Unit) : CommandWithRequiredDecls<TACCmd.Simple> {
            val d = CodeGen()
            d.f()
            return CommandWithRequiredDecls(
                d.commands,
                d.vars
            )
        }
    }
}
