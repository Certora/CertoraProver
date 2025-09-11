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

package vc.data.transformations

import analysis.CommandWithRequiredDecls.Companion.withDecls
import datastructures.stdcollections.*
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.*
import vc.data.SimplePatchingProgram.Companion.patchForEach

/**
    Finds all uses of TACBuiltInFunction.SafeMathNarrow.Assuming, and adds the appropriate assumptions to the program.
 */
object AssumeSafeMathGuarantees {
    fun transform(code: CoreTACProgram): CoreTACProgram {
        return code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            val decls = mutableSetOf<TACSymbol.Var>()
            val cmds = mutableListOf<TACCmd.Simple>()
            val mapped = object : DefaultTACCmdMapper() {
                override val exprMapper = object : DefaultTACExprTransformer() {
                    override fun transformApply(f: TACExpr.TACFunctionSym, ops: List<TACExpr>, tag: Tag?): TACExpr {
                        return when (f) {
                            is TACExpr.TACFunctionSym.BuiltIn -> when (val bif = f.bif) {
                                is TACBuiltInFunction.SafeMathNarrow.Assuming -> {
                                    check (bif.upperBound <= bif.returnSort.maxUnsigned) {
                                        "At $ptr, SafeMathNarrow.Assuming upper bound ${bif.upperBound} exceeds range of ${bif.returnSort}"
                                    }
                                    val valueExp = ops.single()
                                    // If the expression is not a symbol, save it to a temporary variable so we don't
                                    // have to repeat it in the assume.
                                    val valueSym = valueExp.takeIf { it is TACExpr.Sym }
                                        ?: TACKeyword.TMP(valueExp.tagAssumeChecked).also {
                                            decls += it
                                            cmds += TACCmd.Simple.AssigningCmd.AssignExpCmd(it, valueExp)
                                        }.asSym()
                                    // Add the assume.
                                    cmds += TACCmd.Simple.AssumeExpCmd(
                                        TXF { (valueSym ge 0) and (valueSym le bif.upperBound) }
                                    )
                                    // Replace the SafeMathNarrow.Assuming with SafeMathNarrow.Implicit
                                    TXF { safeMathNarrow(valueSym, bif.returnSort) }
                                }
                                else -> super.transformApply(f, ops, tag)
                            }
                            is TACExpr.TACFunctionSym.Adhoc -> super.transformApply(f, ops, tag)
                        }
                    }
                }
            }.map(cmd)
            runIf(mapped != cmd || cmds.isNotEmpty()) {
                cmds += mapped
                ptr to cmds.withDecls(decls)
            }
        }.patchForEach(code) { (ptr, assumes) -> replaceCommand(ptr, assumes) }
    }
}
