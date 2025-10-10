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
import utils.*
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.tacexprutil.postFold
import java.math.BigInteger

/**
 * Detects cases where our modeling is wrong, and it shows in a counter example. For example, if we run LIA/NIA solvers
 * but there is an assignment `a := b & c`, then the bitwise-and may give a wrong result. We'll detect this here and
 * pass it on for display.
 */
class CounterExampleImprecisionDetector(private val cex: CounterExampleContext) {

    sealed interface Imprecision : CexAnalysisInfo {
        class Assignment(
            override val ptr: CmdPointer,
            override val cmd: TACCmd.Simple,
            val rhsStr: String,
            val rhsVal: BigInteger,
            val lhsVal: BigInteger,
        ) : Imprecision {
            override val msg: String get() = "mismatch: $rhsStr = $rhsVal, but is $lhsVal"
        }

        class AssumeExp(
            override val ptr: CmdPointer,
            override val cmd: TACCmd.Simple,
        ) : Imprecision {
            override val msg: String get() = "condition of require statement should be true but is false"
        }

    }

    /**
     * Returns all [CmdPointer]s with an imprecision, together with a relevant msg.
     */
    fun check(): Map<CmdPointer, Imprecision> =
        cex.cexCmdSequence().associateNotNull { (ptr, cmd) ->
            ptr `to?` checkCmd(ptr, cmd)
        }

    private fun checkCmd(ptr: CmdPointer, cmd: TACCmd.Simple): Imprecision? {
        when (cmd) {
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                val lhsVal = cex(cmd.lhs)
                val rhsVal = cex(cmd.rhs)
                if (lhsVal != null && rhsVal != null) {
                    if (lhsVal != rhsVal) {
                        val rhsStr = cmd.rhs.postFold { e, operandStrs ->
                            when (e) {
                                is TACExpr.Sym.Const -> e.s.value.toString()
                                is TACExpr.Sym.Var -> cex(e).toString()
                                else -> "${e.javaClass.simpleName}(${operandStrs.joinToString(", ") { it }})"
                            }
                        }
                        return Imprecision.Assignment(ptr, cmd, rhsStr, rhsVal, lhsVal)
                    }
                }
            }

            is TACCmd.Simple.AssertCmd -> {
                // nothing to check because of the way we limited the counter example.
            }

            is TACCmd.Simple.Assume ->
                cex(cmd.condExpr)?.let { condVal ->
                    if (condVal != BigInteger.ONE) {
                        return Imprecision.AssumeExp(ptr, cmd)
                    }
                }

            is TACCmd.Simple.JumpiCmd -> {
                // should check we went the right way, but this is so unlikely, I leave it for now.
            }

            is TACCmd.Simple.JumpCmd,
            is TACCmd.Simple.AssigningCmd.AssignHavocCmd,
            is TACCmd.Simple.LogCmd,
            is TACCmd.Simple.NopCmd,
            is TACCmd.Simple.LabelCmd,
            is TACCmd.Simple.JumpdestCmd,
            is TACCmd.Simple.AnnotationCmd -> {
                // We just skip these.
            }

            else -> error("Should we support ${cmd.javaClass.simpleName}?")
        }
        return null
    }

}
