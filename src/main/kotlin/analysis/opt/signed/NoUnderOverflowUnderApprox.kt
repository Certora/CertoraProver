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
package analysis.opt.signed

import analysis.CmdPointer
import analysis.opt.signed.SignedDetector.Color
import analysis.opt.signed.SignedDetector.Spot.Expr
import log.*
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACCmd.Simple.AssumeExpCmd
import vc.data.tacexprutil.TACExprUtils.contains
import vc.data.tacexprutil.postTransformWithOriginal

private val logger = Logger(LoggerTypes.NO_UNDEROVERFLOW_UNDER_APPROX)

/**
 * Adds no-under/overflow assumptions to Add/Sub/Mul operations. It has to guess if these are signed or unsigned, and for
 * that, it uses the [SignedDetector].
 */
class NoUnderOverflowUnderApprox(private val code: CoreTACProgram) {
    private val signedDetector = SignedDetector(code).go()
    private val patcher = ConcurrentPatchingProgram(code)
    private val txf = TACExprFactUntyped
    val stats = ConcurrentCounterMap<String>()

    fun go(): CoreTACProgram {
        code.parallelLtacStream().forEach { (ptr, cmd) ->
            if (cmd is AssignExpCmd) {
                handleCmd(ptr, cmd)
            }
        }
        logger.info {
            stats.toString(javaClass.simpleName)
        }
        logger.trace {
            patcher.debugPrinter().toString(code, javaClass.simpleName)
        }
        //patcher.debugPrinter().print(code, javaClass.simpleName)
        return patcher.toCode()
    }


    private fun handleCmd(ptr: CmdPointer, cmd: AssignExpCmd) {
        if (cmd.rhs.contains { it is TACExpr.QuantifiedFormula} ) {
            // it's not impossible to handle, but we leave it for now.
            return
        }
        var changed = false

        fun addBefore(vararg cmds: TACCmd.Simple) {
            patcher.appendBefore(ptr, cmds.toList())
        }

        val newRhs = cmd.rhs.postTransformWithOriginal { origE, e ->
            if (!(e is TACExpr.Vec.Mul || e is TACExpr.Vec.Add || e is TACExpr.BinOp.Sub)) {
                return@postTransformWithOriginal e
            }
            when (signedDetector(Expr(ptr, origE))) {
                Color.Unsigned -> {
                    changed = true
                    val intVersion = when (e) {
                        is TACExpr.Vec.Mul -> txf.IntMul(e.ls).also { stats.plusOne("unsignedMul") }
                        is TACExpr.Vec.Add -> txf.IntAdd(e.ls).also { stats.plusOne("unsignedAdd") }
                        is TACExpr.BinOp.Sub -> txf.IntSub(e.o1, e.o2).also { stats.plusOne("unsignedSub") }
                        else -> `impossible!`
                    }
                    val t = patcher.newTempVar("", Tag.Int).asSym()
                    addBefore(
                        AssignExpCmd(t.s, intVersion),
                        AssumeExpCmd(
                            txf { LAnd(Ge(t, Zero), Le(t, Tag.Bit256.maxUnsigned.asTACExpr)) }
                        )
                    )
                    txf.safeMathNarrow(t, Tag.Bit256)
                }

                Color.Signed -> {
                    changed = true
                    when (e) {
                        is TACExpr.Vec.Mul -> {
                            stats.plusOne("signedMul")
                            val unwrapped = e.getOperands().map { txf.twosUnwrap(it, Tag.Bit256) }
                            val intVersion = txf.IntMul(unwrapped)
                            val t = patcher.newTempVar("", Tag.Int).asSym()
                            addBefore(
                                AssignExpCmd(t.s, intVersion),
                                AssumeExpCmd(
                                    txf {
                                        LAnd(
                                            Ge(t, Tag.Bit256.minSignedMath.asTACExpr),
                                            Le(t, Tag.Bit256.maxSigned.asTACExpr)
                                        )
                                    }
                                )
                            )
                            txf.twosWrap(t)
                        }


                        is TACExpr.BinOp.Sub, is TACExpr.Vec.Add -> {
                            if (e is TACExpr.Vec.Add && e.ls.size != 2) {
                                return@postTransformWithOriginal e
                            }
                            val (o1, o2) = e.getOperands()
                            val op1 =
                                o1 as? TACExpr.Sym
                                    ?: patcher.newTempVar("", Tag.Bit256).also {
                                        addBefore(AssignExpCmd(it, o1))
                                    }.asSym()
                            val op2 =
                                o2 as? TACExpr.Sym
                                    ?: patcher.newTempVar("", Tag.Bit256).also {
                                        addBefore(AssignExpCmd(it, o2))
                                    }.asSym()

                            when (e) {
                                is TACExpr.BinOp.Sub -> {
                                    stats.plusOne("signedSub")
                                    addBefore(AssumeExpCmd(txf.noSSubOverAndUnderflow(op1, op2)))
                                    txf.Sub(op1, op2)
                                }

                                is TACExpr.Vec.Add -> {
                                    stats.plusOne("signedAdd")
                                    addBefore(AssumeExpCmd(txf.noSAddOverAndUnderflow(op1, op2)))
                                    txf.Add(op1, op2)
                                }

                                else -> `impossible!`
                            }

                        }

                        else -> `impossible!`
                    }
                }


                Color.Contradicting -> {
                    stats.plusOne("contradicting")
                    e
                }

                null -> {
                    stats.plusOne("unknown")
                    e
                }
            }
        }
        if (changed) {
            patcher.replace(ptr, cmd.copy(rhs = newRhs))
        }
    }


}
