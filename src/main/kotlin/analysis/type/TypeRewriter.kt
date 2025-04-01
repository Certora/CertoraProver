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

package analysis.type

import analysis.CmdPointer
import analysis.ExprView
import analysis.maybeExpr
import analysis.maybeNarrow
import tac.MetaKey
import tac.Tag
import utils.`to?`
import vc.data.*
import java.math.BigInteger

/**
 * Used to fix typing after simplifying a jimpled code.
 * This code assumes there are no
 * collisions between primitive tags and non-primitive tags in the program.
 */
object TypeRewriter {



    // why not have the TACSymbol.Var as the payload? Currently it will not get transformed
    // as required through DSA and other transformations--womp womp
    // someday...
    val TYPE_REWRITER_CAST = MetaKey.Nothing("type.cast.key")
    private fun bvToBoolExp(bvToConvert: TACExpr) = TACExpr.BinRel.Gt(bvToConvert, TACSymbol.lift(0).asSym())


    fun getAs(v: TACSymbol.Var, t: Tag) = v.withSuffix(t.toString()).updateTagCopy(t)

    /**
     * A pass dedicated to ensuring that the TAC program actually type checks.
     * After decompilation, the TAC program does not typecheck because operations that are inherently boolean such as
     * `le` and `eq` are assigned to `bv256`. A few passes are made without fixing this and silencing the typechecker,
     * but pretty quickly we want to work with a well-typed program.
     * The mechanism is simple: replace an assignment `x:bv256 := boolop` to `b:bool := boolop; x:bv256 := ite(b:bool,0x1,0x0)`
     * Sometimes, though, this is insufficient.
     * For example, when the compiler uses a bv256 variable in a jumpi, assume, or assert command.
     * When this happens, we force the use site to use a boolean value,
     * by assigning a fresh boolean variable to be `x:bv256 > 0`, where `x` is the problematic symbol used in boolean context.
     */
    fun boolify(c: CoreTACProgram): CoreTACProgram {

        val declared = mutableSetOf<TACSymbol.Var>()

        // replaces the site where we define a bv256 variable as [boolExp] with an assignment to bool
        // + assign the original lhs with the bool's variable cast to bv256
        fun addAsBool(patch: SimplePatchingProgram, boolExp: ExprView<TACExpr>) {
            patch.replace(boolExp.ptr) { _ ->
                val tmpBool = getAs(boolExp.lhs, Tag.Bool).also { declared.add(it) }

                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(tmpBool, boolExp.exp),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(boolExp.lhs, tmpBool.asSym().ensureIntOrBvForAssignmentTo(boolExp.lhs))
                )
            }
        }

        fun addBoolAlternative(patch: SimplePatchingProgram, boolExp: ExprView<TACExpr>, newBoolExp: TACExpr) {
            patch.replace(boolExp.ptr) { _ ->
                val tmpBool = getAs(boolExp.lhs, Tag.Bool).also { declared.add(it) }

                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(tmpBool, newBoolExp),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(boolExp.lhs, boolExp.exp) // original
                )
            }
        }

        // replaces all instances of [symToReplace] in the command at [ptr] with the boolified version of [symToReplace].
        fun replaceWithBool(patch: SimplePatchingProgram, ptr: CmdPointer, symToReplace: TACSymbol.Var) {
            if (symToReplace.tag == Tag.Bool) {
                return
            }
            // replace in the original command
            patch.replace(ptr) { cmd ->
                val tmpBool = getAs(symToReplace, Tag.Bool).also { declared.add(it) }
                listOf(
                    // need to add a > 0 check on the original. Example when this is needed: array bounds check
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(tmpBool, bvToBoolExp(symToReplace.asSym())),
                    // remap the command
                    object : DefaultTACCmdMapper() {
                        override fun mapVar(t: TACSymbol.Var): TACSymbol.Var {
                            return if (t == symToReplace) {
                                tmpBool
                            } else {
                                t
                            }
                        }
                    }.map(cmd)
                )
            }
        }

        return c.patching { patch ->
            val g = this.analysisCache.graph
            // convert just binboolop and binrel:
            // must be bool for typechecking
            g.commands.mapNotNull { it.maybeExpr<TACExpr.BinRel>() ?: it.maybeExpr<TACExpr.BinBoolOp>() }
                .forEach { boolExp ->
                    addAsBool(patch, boolExp)
                }
            // maybe-s - const assignments
            g.commands.mapNotNull {
                it.maybeExpr<TACExpr.Sym.Const>()?.takeIf { it.lhs.tag !is Tag.Bool && it.exp.s.value.let { n -> n == BigInteger.ZERO || n == BigInteger.ONE } }
            }.forEach { maybeBoolExp ->
                addBoolAlternative(patch, maybeBoolExp, (maybeBoolExp.exp.s.value > BigInteger.ZERO).asTACExpr)
            }

            // must update jumpi uses, asserts, and assumes
            g.commands.mapNotNull { it.maybeNarrow<TACCmd.Simple.JumpiCmd>() }
                .forEach { jumpi ->
                    if (jumpi.cmd.cond is TACSymbol.Var) {
                        replaceWithBool(patch, jumpi.ptr, jumpi.cmd.cond as TACSymbol.Var)
                    }
                }
            g.commands.mapNotNull { it.maybeNarrow<TACCmd.Simple.AssertCmd>() }
                .forEach { assertCmd ->
                    if (assertCmd.cmd.o is TACSymbol.Var) {
                        replaceWithBool(patch, assertCmd.ptr, assertCmd.cmd.o as TACSymbol.Var)
                    }
                }
            g.commands.mapNotNull { it.maybeNarrow<TACCmd.Simple.AssumeCmd>() }
                .forEach { assumeCmd ->
                    if (assumeCmd.cmd.cond is TACSymbol.Var) {
                        replaceWithBool(patch, assumeCmd.ptr, assumeCmd.cmd.cond as TACSymbol.Var)
                    }
                }

            g.commands.mapNotNull { it.maybeNarrow<TACCmd.Simple.AssumeNotCmd>() }
                .forEach { assumeNotCmd ->
                    if (assumeNotCmd.cmd.cond is TACSymbol.Var) {
                        replaceWithBool(patch, assumeNotCmd.ptr, assumeNotCmd.cmd.cond as TACSymbol.Var)
                    }
                }

            patch.addVarDecls(declared)
        }
    }

    /**
     * see body of function, the idea is to get rid of the `x:bv256 := ite(B:bool,0x1,0x0)` expressions
     * when used within `x eq 0` or `x gt 0` expressions.
     */
    fun peepholeReplacements(c: CoreTACProgram): CoreTACProgram {
        return c.patching { patch ->
            val g = analysisCache.graph
            val def = analysisCache.def

            // find booleans defined by B := X==0 where X is defined uniquely as  X := B' ? 0x1 : 0x0, replace B := ! B'
            // OR the same but B := X > 0 and then we replace with B := B'
            val expDefAndX = g.commands.mapNotNull {
                it.maybeExpr<TACExpr.BinRel.Eq>()?.let { eq ->
                    eq `to?` when (BigInteger.ZERO) {
                        eq.exp.o2.getAsConst() -> {
                            eq.exp.o1.getAs<TACExpr.Sym.Var>()
                        }

                        eq.exp.o1.getAsConst() -> {
                            eq.exp.o2.getAs<TACExpr.Sym.Var>()
                        }

                        else -> {
                            null
                        }
                    }?.let { it to true /* flip polarity */ }
                } ?: it.maybeExpr<TACExpr.BinRel.Gt>()?.let { gt ->
                    gt `to?` (gt.exp.o1.takeIf { gt.exp.o2.getAsConst() == BigInteger.ZERO }?.getAs<TACExpr.Sym.Var>()?.let { it to false /* don't flip polarity */ })
                }
            }

            val expDefAndBPrimeExp = expDefAndX.mapNotNull { (exp, xAndPolarity) ->
                val (x, flip) = xAndPolarity
                val defOfX = def.defSitesOf(x.s, exp.ptr).singleOrNull() ?: return@mapNotNull null
                val iteDefCmd = g.elab(defOfX).maybeExpr<TACExpr.TernaryExp.Ite>()?.takeIf { it.exp.t.getAsConst() == BigInteger.ONE && it.exp.e.getAsConst() == BigInteger.ZERO }
                    ?: return@mapNotNull null
                exp `to?` iteDefCmd.exp.i.getAs<TACExpr.Sym.Var>()?.let { bPrime ->
                    if (flip) {
                        TACExpr.UnaryExp.LNot(bPrime)
                    } else {
                        bPrime
                    }
                }
            }

            expDefAndBPrimeExp.forEach { (eq, bPrimeExp) ->
                patch.update(
                    eq.ptr,
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(eq.lhs, bPrimeExp)
                )
            }
        }
    }
}
