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
                // the sign test compares against a zero of the same bit-width as the operands
                val zero = lit(0, f.tag)
                when (f) {
                    // Division by zero is defined in SMT-LIB (bvudiv by 0 is all ones), so a zero factor
                    // needs its own case.
                    is AxiomatizedFunctionSymbol.BvOverflowChecks.NoUMulOverflow ->
                        (y eq zero) or (((x bvMul y) bvUDiv y) eq x)
                    // MIN * -1 wraps to MIN, and MIN sdiv -1 wraps back to MIN, so it passes the round trip.
                    is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSMulOverUnderflow -> {
                        val min = lit(java.math.BigInteger.ONE.shiftLeft(f.tag.bitwidth - 1), f.tag)
                        val minusOne = lit(java.math.BigInteger.ONE.shiftLeft(f.tag.bitwidth) - java.math.BigInteger.ONE, f.tag)
                        (y eq zero) or ((((x bvMul y) bvSDiv y) eq x) and not((x eq min) and (y eq minusOne)))
                    }
                    is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSAddOverUnderflow ->
                        ite(
                            y bvSGe zero,
                            (x bvAdd y) bvSGe x,
                            (x bvAdd y) bvSLt x
                        )
                    is AxiomatizedFunctionSymbol.BvOverflowChecks.NoSSubOverUnderflow ->
                        ite(
                            y bvSGe zero,
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
