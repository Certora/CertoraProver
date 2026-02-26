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

package wasm.impCfg

import analysis.BitCounts
import datastructures.stdcollections.*
import tac.*
import tac.generation.assign
import vc.data.*
import vc.data.TACExpr.Companion.zeroExpr
import vc.data.TACSymbol.Companion.One
import wasm.impCfg.WasmNumericExpr.wasmComparisonBinaryOpToTacTernary
import wasm.impCfg.WasmNumericExpr.wasmComparisonUnaryOpToTacTernary
import wasm.impCfg.WasmNumericExpr.wasmNumericBinaryOpToTacBinary
import wasm.impCfg.WasmNumericExpr.wasmNumericUnaryOpToTacUnary
import wasm.ir.*
import java.io.Serializable
import java.math.BigInteger

class UnsupportedOperator(msg: String) : Exception(msg)

// Here we only care about ops that produce a value. So only numeric ops basically.
interface WasmIcfgExpr : Serializable {
    override fun toString(): String

    fun assignTACExpr(lhs: TACSymbol.Var): WasmToTacInfo

    val tag: Tag

    fun hasHavoc(): Boolean

    fun getArgs(): List<Arg>
}

// NOTE: everything is tagged as 256bit which is not right, but we are going to worry about that later.

// Important observation: nothing here is recursive! All flat because this is truly TAC.
data class WasmIcfgExpArgument(val regArg: Arg) : WasmIcfgExpr {
    override fun toString(): String {
        return regArg.toString()
    }

    override val tag: Tag
        get() = regArg.toTacExpr().tag!!

    override fun assignTACExpr(lhs: TACSymbol.Var): WasmToTacInfo {
        return assign(lhs) { regArg.toTacExpr() }
    }

    override fun hasHavoc(): Boolean {
        return regArg.isHavoc()
    }

    override fun getArgs(): List<Arg> {
        return listOf(regArg)
    }

}

data class WasmIcfgUnaryExpr(val op: Unop, val regArg1: Arg) : WasmIcfgExpr {
    override fun toString(): String {
        return "$op $regArg1"
    }

    override val tag: Tag
        get() = Tag.Bit256

    override fun assignTACExpr(lhs: TACSymbol.Var): WasmToTacInfo {
        when (op) {
            is UnaryNumericOp -> {
                val arg1 = regArg1.toTacExpr()
                return wasmNumericUnaryOpToTacUnary(op, lhs, arg1)
            }

            is UnaryComparisonOp -> {
                val arg1 = regArg1.toTacExpr()
                return assign(lhs) { wasmComparisonUnaryOpToTacTernary(op, arg1) }
            }
        }
    }

    override fun hasHavoc(): Boolean {
        return regArg1.isHavoc()
    }

    override fun getArgs(): List<Arg> {
        return listOf(regArg1)
    }

}

data class WasmIcfgBinaryExpr(val op: Binop, val regArg1: Arg, val regArg2: Arg) : WasmIcfgExpr {
    override fun toString(): String {
        return "$regArg1 $op $regArg2"
    }

    override val tag: Tag
        get() = Tag.Bit256

    override fun assignTACExpr(lhs: TACSymbol.Var): WasmToTacInfo {
        when (op) {
            is BinaryNumericOp -> {
                val arg1 = regArg1.toTacExpr()
                val arg2 = regArg2.toTacExpr()
                return assign(lhs) { wasmNumericBinaryOpToTacBinary(op, arg1, arg2) }
            }

            is BinaryComparisonOp -> {
                val arg1 = regArg1.toTacExpr()
                val arg2 = regArg2.toTacExpr()
                return assign(lhs) { wasmComparisonBinaryOpToTacTernary(op, arg1, arg2) }
            }
        }
    }

    override fun hasHavoc(): Boolean {
        return regArg1.isHavoc() or regArg2.isHavoc()
    }

    override fun getArgs(): List<Arg> {
        return listOf(regArg1, regArg2)
    }
}

data class WasmIcfgIteExpr(val regArg1: Arg, val regArg2: Arg, val regArg3: Arg) : WasmIcfgExpr {
    override fun toString(): String {
        return "$regArg1 ? $regArg2 : $regArg3"
    }

    override val tag: Tag
        get() = Tag.Bit256

    override fun assignTACExpr(lhs: TACSymbol.Var): WasmToTacInfo {
        val e = TACExpr.TernaryExp.Ite(
            TACExpr.BinRel.Eq(regArg1.toTacExpr(), One.asSym()),
            regArg2.toTacExpr(),
            regArg3.toTacExpr(),
            Tag.Bit256
        )
        return assign(lhs) { e }
    }

    override fun hasHavoc(): Boolean {
        return regArg1.isHavoc() or regArg2.isHavoc() or regArg3.isHavoc()
    }

    override fun getArgs(): List<Arg> {
        return listOf(regArg1, regArg2, regArg3)
    }
}

object WasmNumericExpr {
    private val I32_MOD = BigInteger.TWO.pow(32).asTACExpr
    private val I64_MOD = BigInteger.TWO.pow(64).asTACExpr
    private fun TACExpr.mod(m: TACExpr) = TACExpr.BinOp.Mod(this, m, Tag.Bit256)
    private fun TACExpr.signExt8() = TACExpr.BinOp.SignExtend(0.asTACExpr, this, Tag.Bit256)
    private fun TACExpr.signExt16() = TACExpr.BinOp.SignExtend(1.asTACExpr, this, Tag.Bit256)
    private fun TACExpr.signExt32() = TACExpr.BinOp.SignExtend(3.asTACExpr, this, Tag.Bit256)
    private fun TACExpr.signExt64() = TACExpr.BinOp.SignExtend(7.asTACExpr, this, Tag.Bit256)

    fun wasmNumericUnaryOpToTacUnary(op: UnaryNumericOp, lhs: TACSymbol.Var, arg1: TACExpr): WasmToTacInfo {
        return when (op) {
            UnaryNumericOp.I32WRAP64 -> assign(lhs) { arg1.mod(I32_MOD) }
            UnaryNumericOp.I64_EXTENDI32_U -> assign(lhs) { arg1 }
            UnaryNumericOp.I64_EXTENDI32_S -> assign(lhs) { arg1.signExt32().mod(I64_MOD) }

            UnaryNumericOp.I32_EXTEND8_S ->  assign(lhs) { arg1.signExt8().mod(I32_MOD) }
            UnaryNumericOp.I64_EXTEND8_S ->  assign(lhs) { arg1.signExt8().mod(I64_MOD) }
            UnaryNumericOp.I32_EXTEND16_S -> assign(lhs) {  arg1.signExt16().mod(I32_MOD) }
            UnaryNumericOp.I64_EXTEND16_S -> assign(lhs) {  arg1.signExt16().mod(I64_MOD) }
            UnaryNumericOp.I64_EXTEND32_S -> assign(lhs) {  arg1.signExt32().mod(I64_MOD) }

            UnaryNumericOp.I32CLZ -> BitCounts.countLeadingZeros(lhs, arg1, 32, MetaMap())
            UnaryNumericOp.I64CLZ -> BitCounts.countLeadingZeros(lhs, arg1, 64, MetaMap())
            UnaryNumericOp.I32CTZ -> BitCounts.countTrailingZeros(lhs, arg1, 32, MetaMap())
            UnaryNumericOp.I64CTZ -> BitCounts.countTrailingZeros(lhs, arg1, 64, MetaMap())
        }
    }

    /**
     * A small helper that takes a tacexpr condition expression generated from a wasm conditional expression
     * and returns a tac ITE expression (for both unary and binary).
     * If the condition is true, it returns 1, else it returns 0.
     * This is in accordance with the wasm semantics.
     * We are doing this because wasm does not have booleans.
     * */
    private fun wasmExprToTacExprHelper(cond: TACExpr): TACExpr {
        return TACExpr.TernaryExp.Ite(cond, One.asSym(), zeroExpr, Tag.Bit256)
    }

    /*
    Note that wasm unary expr may not always correspond to a tac unary expr.
    There are some cases where tac doesn't have that op and we have to simulate it using other
    ops, as you can see for EQZ below.
* */
    fun wasmComparisonUnaryOpToTacTernary(op: UnaryComparisonOp, arg1: TACExpr): TACExpr {
        return when (op) {
            UnaryComparisonOp.I32EQZ -> {
                wasmExprToTacExprHelper(TACExpr.BinRel.Eq(arg1, zeroExpr))
            }

            UnaryComparisonOp.I64EQZ -> {
                wasmExprToTacExprHelper(TACExpr.BinRel.Eq(arg1, zeroExpr))
            }
        }
    }

    // NOTE: these should all be mod-ed by the bitwidth to be sound.
    fun wasmNumericBinaryOpToTacBinary(op: BinaryNumericOp, arg1: TACExpr, arg2: TACExpr): TACExpr {
        return when (op) {
            BinaryNumericOp.I32ADD -> TACExpr.Vec.Add(arg1, arg2, Tag.Bit256).mod(I32_MOD)
            BinaryNumericOp.I64ADD -> TACExpr.Vec.Add(arg1, arg2, Tag.Bit256).mod(I64_MOD)
            BinaryNumericOp.I32SUB -> TACExpr.BinOp.Sub(arg1, arg2, Tag.Bit256).mod(I32_MOD)
            BinaryNumericOp.I64SUB -> TACExpr.BinOp.Sub(arg1, arg2, Tag.Bit256).mod(I64_MOD)
            BinaryNumericOp.I32MUL -> TACExpr.Vec.Mul(arg1, arg2, Tag.Bit256).mod(I32_MOD)
            BinaryNumericOp.I64MUL -> TACExpr.Vec.Mul(arg1, arg2, Tag.Bit256).mod(I64_MOD)
            BinaryNumericOp.I32DIVU -> TACExpr.BinOp.Div(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I64DIVU -> TACExpr.BinOp.Div(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I32DIVS -> TACExpr.BinOp.SDiv(arg1.signExt32(), arg2.signExt32(), Tag.Bit256).mod(I32_MOD)
            BinaryNumericOp.I64DIVS -> TACExpr.BinOp.SDiv(arg1.signExt64(), arg2.signExt64(), Tag.Bit256).mod(I64_MOD)
            BinaryNumericOp.I32REMU -> TACExpr.BinOp.Mod(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I64REMU -> TACExpr.BinOp.Mod(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I32REMS -> TACExpr.BinOp.SMod(arg1.signExt32(), arg2.signExt32(), Tag.Bit256).mod(I32_MOD)
            BinaryNumericOp.I64REMS -> TACExpr.BinOp.SMod(arg1.signExt64(), arg2.signExt64(), Tag.Bit256).mod(I64_MOD)
            BinaryNumericOp.I32AND -> TACExpr.BinOp.BWAnd(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I64AND -> TACExpr.BinOp.BWAnd(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I32OR -> TACExpr.BinOp.BWOr(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I64OR -> TACExpr.BinOp.BWOr(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I32XOR -> TACExpr.BinOp.BWXOr(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I64XOR -> TACExpr.BinOp.BWXOr(arg1, arg2, Tag.Bit256)
            BinaryNumericOp.I32SHL -> TACExpr.BinOp.ShiftLeft(arg1, arg2.mod(32.asTACExpr), Tag.Bit256).mod(I32_MOD)
            BinaryNumericOp.I64SHL -> TACExpr.BinOp.ShiftLeft(arg1, arg2.mod(64.asTACExpr), Tag.Bit256).mod(I64_MOD)
            BinaryNumericOp.I32SHRU -> TACExpr.BinOp.ShiftRightLogical(arg1, arg2.mod(32.asTACExpr), Tag.Bit256)
            BinaryNumericOp.I64SHRU -> TACExpr.BinOp.ShiftRightLogical(arg1, arg2.mod(64.asTACExpr), Tag.Bit256)
            BinaryNumericOp.I32SHRS -> TACExpr.BinOp.ShiftRightArithmetical(arg1.signExt32(), arg2.mod(32.asTACExpr), Tag.Bit256).mod(I32_MOD)
            BinaryNumericOp.I64SHRS -> TACExpr.BinOp.ShiftRightArithmetical(arg1.signExt64(), arg2.mod(64.asTACExpr), Tag.Bit256).mod(I64_MOD)
        }
    }


    fun wasmComparisonBinaryOpToTacTernary(op: BinaryComparisonOp, arg1: TACExpr, arg2: TACExpr): TACExpr {
        return when (op) {
            BinaryComparisonOp.I32LTU -> wasmExprToTacExprHelper(TACExpr.BinRel.Lt(arg1, arg2))
            BinaryComparisonOp.I64LTU -> wasmExprToTacExprHelper(TACExpr.BinRel.Lt(arg1, arg2))
            BinaryComparisonOp.I32GTU -> wasmExprToTacExprHelper(TACExpr.BinRel.Gt(arg1, arg2))
            BinaryComparisonOp.I64GTU -> wasmExprToTacExprHelper(TACExpr.BinRel.Lt(arg2, arg1))
            BinaryComparisonOp.I32LTS -> wasmExprToTacExprHelper(TACExpr.BinRel.Slt(arg1.signExt32(), arg2.signExt32()))
            BinaryComparisonOp.I64LTS -> wasmExprToTacExprHelper(TACExpr.BinRel.Slt(arg1.signExt64(), arg2.signExt64()))
            BinaryComparisonOp.I32GTS -> wasmExprToTacExprHelper(TACExpr.BinRel.Sgt(arg1.signExt32(), arg2.signExt32()))
            BinaryComparisonOp.I64GTS -> wasmExprToTacExprHelper(TACExpr.BinRel.Sgt(arg1.signExt64(), arg2.signExt64()))

            BinaryComparisonOp.I32LEU -> wasmExprToTacExprHelper(TACExpr.BinRel.Le(arg1, arg2))
            BinaryComparisonOp.I64LEU -> wasmExprToTacExprHelper(TACExpr.BinRel.Le(arg1, arg2))
            BinaryComparisonOp.I32LES -> wasmExprToTacExprHelper(TACExpr.BinRel.Sle(arg1.signExt32(), arg2.signExt32()))
            BinaryComparisonOp.I64LES -> wasmExprToTacExprHelper(TACExpr.BinRel.Sle(arg1.signExt64(), arg2.signExt64()))
            BinaryComparisonOp.I32GEU -> wasmExprToTacExprHelper(TACExpr.BinRel.Ge(arg1, arg2))
            BinaryComparisonOp.I64GEU -> wasmExprToTacExprHelper(TACExpr.BinRel.Ge(arg1, arg2))
            BinaryComparisonOp.I32GES -> wasmExprToTacExprHelper(TACExpr.BinRel.Sge(arg1.signExt32(), arg2.signExt32()))
            BinaryComparisonOp.I64GES -> wasmExprToTacExprHelper(TACExpr.BinRel.Sge(arg1.signExt64(), arg2.signExt64()))

            BinaryComparisonOp.I32EQ -> wasmExprToTacExprHelper(TACExpr.BinRel.Eq(arg1, arg2))
            BinaryComparisonOp.I64EQ -> wasmExprToTacExprHelper(TACExpr.BinRel.Eq(arg1, arg2))
            BinaryComparisonOp.I32NE -> wasmExprToTacExprHelper(TACExpr.UnaryExp.LNot(TACExpr.BinRel.Eq(arg1, arg2)))
            BinaryComparisonOp.I64NE -> wasmExprToTacExprHelper(TACExpr.UnaryExp.LNot(TACExpr.BinRel.Eq(arg1, arg2)))
        }
    }

    /**
     * Given a WasmIcfgExpr and a list of vars, reconstruct the expr with those vars.
     * */
    fun reconstructExpr(expr: WasmIcfgExpr, vars: List<Arg>): WasmIcfgExpr {
        when (expr) {
            is WasmIcfgExpArgument -> {
                check(vars.size == 1)
                return WasmIcfgExpArgument(vars[0])
            }

            is WasmIcfgUnaryExpr -> {
                check(vars.size == 1)
                return WasmIcfgUnaryExpr(expr.op, vars[0])
            }

            is WasmIcfgBinaryExpr -> {
                check(vars.size == 2)
                return WasmIcfgBinaryExpr(expr.op, vars[0], vars[1])
            }

            is WasmIcfgIteExpr -> {
                check(vars.size == 3)
                return WasmIcfgIteExpr(vars[0], vars[1], vars[2])
            }
        }
        throw UnsupportedOperator("$expr is not a valid WasmIcfgExpr.")
    }

    fun transformTmps(expr: WasmIcfgExpr, transformTmp: (Tmp) -> Tmp): WasmIcfgExpr {
        when (expr) {
            is WasmIcfgExpArgument -> {
                return WasmIcfgExpArgument(expr.regArg.transformTmps(transformTmp))
            }

            is WasmIcfgUnaryExpr -> {
                return WasmIcfgUnaryExpr(expr.op, expr.regArg1.transformTmps(transformTmp))
            }

            is WasmIcfgBinaryExpr -> {
                return WasmIcfgBinaryExpr(expr.op, expr.regArg1.transformTmps(transformTmp), expr.regArg2.transformTmps(transformTmp))
            }

            is WasmIcfgIteExpr -> {
                return WasmIcfgIteExpr(expr.regArg1.transformTmps(transformTmp), expr.regArg2.transformTmps(transformTmp), expr.regArg3.transformTmps(transformTmp))
            }
        }
        throw UnsupportedOperator("$expr is not a valid WasmIcfgExpr.")
    }
}
