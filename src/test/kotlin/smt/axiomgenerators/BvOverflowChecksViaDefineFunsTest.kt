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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import smt.GenerateEnv
import smt.solverscript.LExpressionFactory
import tac.Tag
import vc.data.LExpression
import vc.data.TACBuiltInFunction
import vc.data.TACSymbol
import vc.data.TACSymbolTable

/**
 * The define-fun bodies produced here are handed to the bitvector solvers as-is, so every operand of every
 * bv operator in them has to carry the bit-width of the check being defined.
 */
class BvOverflowChecksViaDefineFunsTest {

    private fun defBodyFor(bif: TACBuiltInFunction, tag: Tag.Bits): LExpression {
        val lxf = LExpressionFactory()
        val generator = BvOverflowChecksViaDefineFuns(lxf)
        val args = listOf("x", "y").map { TACSymbol.Var(it, tag).asSym() }
        generator.visit(
            bif.getLExpressionBuilder(lxf, TACSymbolTable.empty(), null)(args),
            GenerateEnv()
        )
        return generator.yieldDefineFuns().single().def
    }

    /** the tags of [e] and of all of its subexpressions, booleans excluded */
    private fun valueTags(e: LExpression): List<Tag> =
        listOf(e.tag).filter { it != Tag.Bool } +
            ((e as? LExpression.ApplyExpr)?.args?.flatMap(::valueTags) ?: listOf())

    private fun checkAllOperandsShareTag(bif: TACBuiltInFunction, tag: Tag.Bits) {
        val tags = valueTags(defBodyFor(bif, tag)).toSet()
        assertEquals(setOf<Tag>(tag), tags, "define-fun body of $bif mixes tags")
    }

    @Test
    fun signedAddOverflowCheck() {
        listOf(Tag.Bits(64), Tag.Bit256).forEach {
            checkAllOperandsShareTag(TACBuiltInFunction.NoSAddOverAndUnderflowCheck(it), it)
        }
    }

    @Test
    fun signedSubOverflowCheck() {
        listOf(Tag.Bits(64), Tag.Bit256).forEach {
            checkAllOperandsShareTag(TACBuiltInFunction.NoSSubOverAndUnderflowCheck(it), it)
        }
    }

    @Test
    fun unsignedMulOverflowCheck() {
        listOf(Tag.Bits(64), Tag.Bit256).forEach {
            checkAllOperandsShareTag(TACBuiltInFunction.NoMulOverflowCheck(it), it)
        }
    }

    @Test
    fun signedMulOverflowCheck() {
        listOf(Tag.Bits(64), Tag.Bit256).forEach {
            checkAllOperandsShareTag(TACBuiltInFunction.NoSMulOverAndUnderflowCheck(it), it)
        }
    }
}
