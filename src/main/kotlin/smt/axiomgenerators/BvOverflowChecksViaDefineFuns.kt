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

package smt.axiomgenerators

import datastructures.stdcollections.*
import smt.GenerateEnv
import smt.newufliatranslator.AxiomGenerator
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.AxiomatizedFunctionSymbol
import smt.solverscript.functionsymbols.NonSMTInterpretedFunctionSymbol
import tac.Tag
import vc.data.LExpression

class BvOverflowChecksViaDefineFuns(private val lxf: LExpressionFactory) : AxiomGenerator() {

    private val fs: MutableMap<AxiomatizedFunctionSymbol.BvOverflowChecks, DefType> = mutableMapOf()

    private fun addDef(f: AxiomatizedFunctionSymbol.BvOverflowChecks) {
        val x = lxf.freshVariable("bvovfl-x-${f.tag}", f.tag)
        val y = lxf.freshVariable("bvovfl-y-${f.tag}", f.tag)
        infix fun LExpression.bvSub(other: LExpression) = lxf { this@bvSub bvAdd bvNeg(other) }
        fs[f] = DefType(
            id = f,
            args = listOf(x, y),
            ret = Tag.Bool,
            def = lxf {
                when (f) {
                    is AxiomatizedFunctionSymbol.BvOverflowChecks.NoUMulOverflow ->
                        ((x bvMul y) bvUDiv y) eq x
                    is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSMulOverUnderflow ->
                        ((x bvMul y) bvSDiv y) eq x
                    is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSAddOverUnderflow ->
                        ite(
                            y bvSGe ZERO,
                            (x bvAdd y) bvSGe x,
                            (x bvAdd y) bvSLt x
                        )
                    is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSSubOverUnderflow ->
                        ite(
                            y bvSGe ZERO,
                            (x bvSub y) bvSLe x,
                            (x bvSub y) bvSGt x
                        )
                }
            }
        )
    }

    override fun visit(e: LExpression, env: GenerateEnv) {
        if (e is LExpression.ApplyExpr) {
            when (val f = e.f) {
                is AxiomatizedFunctionSymbol.BvOverflowChecks.NoUMulOverflow ->
                    addDef(f)
                is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSMulOverUnderflow ->
                    addDef(f)
                is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSAddOverUnderflow ->
                    addDef(f)
                is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSSubOverUnderflow ->
                    addDef(f)
                is NonSMTInterpretedFunctionSymbol.Binary.NoMulOverflow ->
                    addDef(AxiomatizedFunctionSymbol.BvOverflowChecks.NoUMulOverflow(e.lhs.tag as Tag.Bits))
                is NonSMTInterpretedFunctionSymbol.Binary.NoSMulOverUnderflow ->
                    addDef(AxiomatizedFunctionSymbol.BvOverflowChecks.NoSMulOverUnderflow(e.lhs.tag as Tag.Bits))
                is NonSMTInterpretedFunctionSymbol.Binary.NoSAddOverUnderflow ->
                    addDef(AxiomatizedFunctionSymbol.BvOverflowChecks.NoSAddOverUnderflow(e.lhs.tag as Tag.Bits))
                is NonSMTInterpretedFunctionSymbol.Binary.NoSSubOverUnderflow ->
                    addDef(AxiomatizedFunctionSymbol.BvOverflowChecks.NoSSubOverUnderflow(e.lhs.tag as Tag.Bits))
                else -> Unit
            }
        }
    }

    override fun beforeScriptFreeze() {
        fs.forEachEntry { lxf.registerFunctionSymbol(it.key) }
        super.beforeScriptFreeze()
    }

    override fun yieldDefineFuns(): List<DefType> = fs.values.toList()
}
