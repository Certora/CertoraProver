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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.tacexprutil.subs

class TACExprFactTypeCheckedTest : TACBuilderAuxiliaries() {

    val x0 = "x0".bv256Sym
    val y0 = "y0".bv256Sym
    val z0 = "z0".bv256Sym
    val bm0 = "bm0".bmSym
    val bm1 = "bm1".bmSym
    val gm1 = newVar("gm1", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256)).asSym()
    val b0 = "b0".boolSym
    val b1 = "b1".boolSym
    val b2 = "b2".boolSym
    val xInt = "xInt".intSym

    // .withTags(setOf(x, y, z, bm0, bm1, gm1, b0, b1, b2, xInt).mapToSet { it.s })

    init {
        txf = TACExprFactTypeChecked(
            txf.symbolTable
        )
    }

    private fun assertHasTypes(exp: TACExpr) {
        assertTrue(exp.subs.all { it.tag != null })
    }

    @Test
    fun invoke() {
        assertHasTypes(txf { LAnd(Gt(Add(x0, y0), z0), b1, LOr(b2, Le(Mul(z0, y0, x0), x0))) })
    }

    @Test
    fun sym() {
        assertHasTypes(txf.Sym(x0.s))
    }

    @Test
    fun mul() {
        assertHasTypes(txf.Mul(x0, y0))
        assertHasTypes(txf.Mul(x0, y0, z0))
        assertHasTypes(txf.Mul(listOf(x0, y0, z0)))
    }

    @Test
    fun intMul() {
        assertHasTypes(txf.IntMul(x0, y0))
        assertHasTypes(txf.IntMul(x0, y0, z0))
        assertHasTypes(txf.IntMul(listOf(x0, y0, z0)))
    }

    @Test
    fun intAdd() {
        assertHasTypes(txf.IntAdd(x0, y0))
        assertHasTypes(txf.IntAdd(x0, y0, z0))
        assertHasTypes(txf.IntAdd(listOf(x0, y0, z0)))
    }

    @Test
    fun add() {
        assertHasTypes(txf.Add(x0, y0))
        assertHasTypes(txf.Add(x0, y0, z0))
        assertHasTypes(txf.Add(listOf(x0, y0, z0)))
    }

    @Test
    fun intSub() {
        assertHasTypes(txf.IntSub(x0, y0))
    }

    @Test
    fun sub() {
        assertHasTypes(txf.Sub(x0, y0))
    }

    @Test
    fun div() {
        assertHasTypes(txf.Div(x0, y0))
    }

    @Test
    fun SDiv() {
        assertHasTypes(txf.SDiv(x0, y0))
    }

    @Test
    fun intDiv() {
        assertHasTypes(txf.IntDiv(x0, y0))
    }

    @Test
    fun mod() {
        assertHasTypes(txf.Mod(x0, y0))
    }

    @Test
    fun SMod() {
        assertHasTypes(txf.SMod(x0, y0))
    }

    @Test
    fun exponent() {
        assertHasTypes(txf.Exponent(x0, y0))
    }

    @Test
    fun gt() {
        assertHasTypes(txf.Gt(x0, y0))
    }

    @Test
    fun lt() {
        assertHasTypes(txf.Lt(x0, y0))
    }

    @Test
    fun slt() {
        assertHasTypes(txf.Slt(x0, y0))
    }

    @Test
    fun sle() {
        assertHasTypes(txf.Sle(x0, y0))
    }

    @Test
    fun sgt() {
        assertHasTypes(txf.Sgt(x0, y0))
    }

    @Test
    fun sge() {
        assertHasTypes(txf.Sge(x0, y0))
    }

    @Test
    fun ge() {
        assertHasTypes(txf.Ge(x0, y0))
    }

    @Test
    fun le() {
        assertHasTypes(txf.Le(x0, y0))
    }

    @Test
    fun eq() {
        assertHasTypes(txf.Eq(x0, y0))
        assertHasTypes(txf.Eq(b0, b1))
    }

    @Test
    fun BWAnd() {
        assertHasTypes(txf.BWAnd(x0, y0))
    }

    @Test
    fun BWOr() {
        assertHasTypes(txf.BWOr(x0, y0))
    }

    @Test
    fun BWXOr() {
        assertHasTypes(txf.BWXOr(x0, y0))
    }

    @Test
    fun BWNot() {
        assertHasTypes(txf.BWNot(x0))
    }

    @Test
    fun shiftLeft() {
        assertHasTypes(txf.ShiftLeft(x0, y0))
    }

    @Test
    fun shiftRightLogical() {
        assertHasTypes(txf.ShiftRightLogical(x0, y0))
    }

    @Test
    fun shiftRightArithmetical() {
        assertHasTypes(txf.ShiftRightArithmetical(x0, y0))
    }

    @Test
    fun apply() {
        assertHasTypes(txf.Apply(TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256).toTACFunctionSym(), listOf(xInt)))
    }

    @Test
    fun select() {
        assertHasTypes(txf.Select(bm0, x0))
    }

    @Test
    fun store() {
        assertHasTypes(txf.Store(bm0, x0, v = y0))
    }

    @Test
    fun multiDimStore() {
        assertHasTypes(txf.Store(gm1, listOf(x0, y0), z0))
    }

    @Test
    fun longStore() {
        assertHasTypes(txf.LongStore(bm0, x0, bm1, y0, z0))
    }

    @Test
    fun mapDefinition() {
        // test not yet implemented (type checker lacks support)
        // assertHasTypes(txf.MapDefinition(listOf(), TACSymbol.Zero.asSym(), false))
    }

    @Test
    fun unconstrained() {
        // test not yet implemented (type checker lacks support)
        // txf.unconstrained(Tag.Bit256)
    }

    @Test
    fun simpleHash() {
        txf.SimpleHash(TACSymbol.lift(32).asSym(), listOf(x0))
    }

    @Test
    fun LAnd() {
        assertHasTypes(txf.LAnd(b0, b1))
        assertHasTypes(txf.LAnd(b0, b1, b2))
        assertHasTypes(txf.LAnd(listOf(b0, b1, b2)))
    }

    @Test
    fun LOr() {
        assertHasTypes(txf.LOr(b0, b1))
        assertHasTypes(txf.LOr(b0, b1, b2))
        assertHasTypes(txf.LOr(listOf(b0, b1, b2)))
    }

    @Test
    fun LNot() {
        assertHasTypes(txf.LNot(b0))
    }

    @Test
    fun ite() {
        assertHasTypes(txf.Ite(b0, x0, y0))
        assertHasTypes(txf.Ite(b0, b1, b2))
    }

    @Test
    fun mulMod() {
        assertHasTypes(txf.MulMod(x0, y0, z0))
    }

    @Test
    fun addMod() {
        assertHasTypes(txf.AddMod(x0, y0, z0))
    }

    @Test
    fun structAccess() {
        // test not yet implemented
    }

    @Test
    fun structConstant() {
        // test not yet implemented
    }

    @Test
    fun signExtend() {
        assertHasTypes(txf.SignExtend(x0, y0))
    }
}
