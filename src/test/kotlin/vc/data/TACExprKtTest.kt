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

package vc.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tac.Tag

internal class TACExprKtTest {

    /**
     * The order of [TACExpr.getOperands] relied upon by the `ExpPointer` class, which views each TACExpr as a
     * ranked tree.
     * The convention is that the order follows the order of the constructor parameters of each [TACExpr].
     * This test is a canary for when the order is changed, and things that rely on it, e.g.,
     * `AccumulatingTACExprTransformer` are adapted.
     */
    @Test
    fun getSubExpressions() {
        val bytemapSym = TACSymbol.Var("m", Tag.ByteMap)
        val bytemap = bytemapSym.asSym()
        @Suppress("UNUSED_VARIABLE") val bytemap2Sym = TACSymbol.Var("m2", Tag.ByteMap)
        val bytemap2 = bytemapSym.asSym()
        val structSym = TACSymbol.Var("s", Tag.Int) // uhm.. not ideal .. (that we don't have a struct type here)
        val struct = structSym.asSym()
        val int1Sym = TACSymbol.Var("i1", Tag.Int)
        val int1 = int1Sym.asSym()
        val int2Sym = TACSymbol.Var("i2", Tag.Int)
        val int2 = int2Sym.asSym()
        @Suppress("UNUSED_VARIABLE") val int3Sym = TACSymbol.Var("i3", Tag.Int)
        val int3 = int2Sym.asSym()
        val bv1Sym = TACSymbol.Var("bv1", Tag.Bit256)
        val bv1 = bv1Sym.asSym()
        val bv2Sym = TACSymbol.Var("bv2", Tag.Bit256)
        val bv2 = bv2Sym.asSym()
        val bv3Sym = TACSymbol.Var("bv3", Tag.Bit256)
        val bv3 = bv3Sym.asSym()

        val bool1 = int2Sym.asSym()

        val bool2 = int2Sym.asSym()

        val bool3 = int2Sym.asSym()
        val thirteen = TACSymbol.lift(13).asSym()

        assertEquals(int1.getOperands(), listOf<TACExpr>())

        val select = TACExpr.Select(bytemap, int1)
        assertEquals(select.getOperands(), listOf(bytemap, int1))

        val store = TACExpr.Store(bytemap, int1, int2)
        assertEquals(store.getOperands(), listOf(bytemap, int1, int2))

        val quant = TACExpr.QuantifiedFormula(true, int1Sym, bool1)
        assertEquals(quant.getOperands(), listOf(bool1))

        val vec = TACExpr.Vec.Mul(listOf(bv1, bv2, bv3))
        assertEquals(vec.getOperands(), listOf(bv1, bv2, bv3))

        val unary = TACExpr.UnaryExp.LNot(bool1)
        assertEquals(unary.getOperands(), listOf(bool1))

        val binop = TACExpr.BinOp.Sub(bv1, bv2)
        assertEquals(binop.getOperands(), listOf(bv1, bv2))

        val binrel = TACExpr.BinRel.Ge(bv1, bv2)
        assertEquals(binrel.getOperands(), listOf(bv1, bv2))

        val apply = TACExpr.Apply(TACExpr.TACFunctionSym.Adhoc("foo"), listOf(int1, int2, int3), Tag.Int)
        assertEquals(apply.getOperands(), listOf(int1, int2, int3))

        val longstore = TACExpr.LongStore(bytemap, int1, bytemap2, int2, int3)
        assertEquals(longstore.getOperands(), listOf(bytemap, int1, bytemap2, int2, int3))

        val hash = TACExpr.SimpleHash(length = int1, args = listOf(int2, int3), hashFamily = HashFamily.Keccack)
        assertEquals(hash.getOperands(), listOf(int1, int2, int3))

        val binboolop = TACExpr.BinBoolOp.LAnd(listOf(bool1, bool2, bool3))
        assertEquals(binboolop.getOperands(), listOf(bool1, bool2, bool3))

        val structAccess = TACExpr.StructAccess(struct, "f1")
        assertEquals(structAccess.getOperands(), listOf(struct))

        val mapDef1 = TACExpr.MapDefinition(listOf(int1, int2), thirteen, Tag.ByteMap)
        assertEquals(mapDef1.getOperands(), listOf(thirteen))
        val mapDef2 = TACExpr.MapDefinition(listOf(int1), binboolop, Tag.ByteMap)
        assertEquals(mapDef2.getOperands(), listOf(binboolop))
        val mapDef3 = TACExpr.MapDefinition(listOf(int1, int2, int3), binop, Tag.ByteMap)
        assertEquals(mapDef3.getOperands(), listOf(binop))
        val mapDef4 = TACExpr.MapDefinition(listOf(bv1), thirteen, Tag.WordMap)
        assertEquals(mapDef4.getOperands(), listOf(thirteen))
    }
}
