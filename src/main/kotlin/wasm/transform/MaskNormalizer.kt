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

package wasm.transform

import datastructures.stdcollections.*
import analysis.NonTrivialDefAnalysis
import analysis.maybeExpr
import analysis.split.Ternary.Companion.isPowOf2Minus1
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.tacexprutil.isConst
import vc.data.tacexprutil.isVar

object MaskNormalizer {
    fun normalizeMasks(ctp: CoreTACProgram): CoreTACProgram {
        val def = NonTrivialDefAnalysis(ctp.analysisCache.graph)
        val use = ctp.analysisCache.use
        val toDelete = ctp.parallelLtacStream().mapNotNull {
            it.maybeExpr<TACExpr.BinOp.BWAnd>()?.takeIf {
                (it.exp.o1.isVar && it.exp.o2.isConst) ||
                    (it.exp.o2.isVar && it.exp.o1.isConst)
            }
        }.mapNotNull { bwand ->
            val (varOperand, constOperand) = if (bwand.exp.o1.isConst) {
                bwand.exp.o2 to bwand.exp.o1
            } else {
                bwand.exp.o1 to bwand.exp.o2
            }
            val k = (constOperand as TACExpr.Sym.Const).s.value

            if (!k.isPowOf2Minus1) {
                return@mapNotNull null
            }

            val x = (varOperand as TACExpr.Sym.Var).s
            val modOp = def.getDefAsExpr<TACExpr.BinOp.Mod>(x, bwand.ptr) ?: return@mapNotNull null
            val modulus = modOp.exp.o2AsNullableTACSymbol()?.tryAs<TACSymbol.Const>()?.value ?: return@mapNotNull null

            if (modulus <= k) {
                return@mapNotNull null
            }

            if (use.useSitesAfter(x, modOp.ptr).singleOrNull() != bwand.ptr) {
                return@mapNotNull null
            }

            modOp to bwand
        }

        return ctp.patching { patching ->
            toDelete.forEach { (modOp, bwand) ->
                patching.delete(modOp.ptr)
                val newRhs = if (bwand.exp.o1.isVar) {
                    bwand.exp.copy(o1 = modOp.exp.o1)
                } else {
                    bwand.exp.copy(o2 = modOp.exp.o1)
                }
                patching.replaceCommand(bwand.ptr, listOf(bwand.cmd.copy(rhs = newRhs)))
            }
        }
    }
}
