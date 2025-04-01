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

package sbf.tac

import sbf.callgraph.CVTNativeInt
import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.SbfInstruction
import sbf.disassembler.SbfRegister
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.asTACExpr
import java.math.BigInteger

/**
 * Summarize nativeint intrinsics
 *
 * These intrinsics allow users to write specifications using native integers.
 * Currently, we use 256-bit TAC variables to simulate nativeint.
 */
context(SbfCFGToTAC)
fun summarizeNativeInt(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call) {"summarizeNativeInt expects a call instruction instead of ${locInst.inst}"}
    val function = CVTNativeInt.from(inst.name)
    check(function != null) {"summarizeNativeInt does not support ${inst.name}"}

    // These symbols are created using 256-bit
    val r1 = exprBuilder.mkVar(SbfRegister.R1_ARG).asSym()
    val r2 = exprBuilder.mkVar(SbfRegister.R2_ARG).asSym()
    val r3 = exprBuilder.mkVar(SbfRegister.R3_ARG).asSym()
    val r4 = exprBuilder.mkVar(SbfRegister.R4_ARG).asSym()
    val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
    val zero = exprBuilder.ZERO.asSym()
    val one  = exprBuilder.ONE.asSym()

    return datastructures.stdcollections.listOf(
        when (function) {
            CVTNativeInt.NATIVEINT_EQ ->
                assign(r0, TACExpr.TernaryExp.Ite(TACExpr.BinRel.Eq(r1, r2), one, zero))
            CVTNativeInt.NATIVEINT_LT ->
                assign(r0, TACExpr.TernaryExp.Ite(TACExpr.BinRel.Lt(r1, r2), one, zero))
            CVTNativeInt.NATIVEINT_LE ->
                assign(r0, TACExpr.TernaryExp.Ite(TACExpr.BinRel.Le(r1, r2), one, zero))
            CVTNativeInt.NATIVEINT_ADD ->
                assign(r0, TACExpr.Vec.Add(datastructures.stdcollections.listOf(r1, r2)))
            CVTNativeInt.NATIVEINT_SUB ->
                assign(r0, TACExpr.BinOp.Sub(r1, r2))
            CVTNativeInt.NATIVEINT_MUL ->
                assign(r0, TACExpr.Vec.Mul(datastructures.stdcollections.listOf(r1, r2)))
            CVTNativeInt.NATIVEINT_DIV ->
                assign(r0, TACExpr.BinOp.Div(r1, r2))
            CVTNativeInt.NATIVEINT_CEIL_DIV ->
                assign(r0, TACExpr.BinOp.Div(TACExpr.BinOp.Sub(TACExpr.Vec.Add(r1, r2), one), r2))
            CVTNativeInt.NATIVEINT_MULDIV ->
                assign(r0, TACExpr.BinOp.Div(TACExpr.Vec.Mul(r1, r2), r3))
            CVTNativeInt.NATIVEINT_MULDIV_CEIL ->
                assign(
                    r0,
                    TACExpr.BinOp.Div(TACExpr.BinOp.Sub(TACExpr.Vec.Add(TACExpr.Vec.Mul(r1, r2), r3), one), r3)
                )
            CVTNativeInt.NATIVEINT_NONDET ->
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(r0)
            CVTNativeInt.NATIVEINT_FROM_U128 -> /* build a nativeint from u128 (two 64-bit registers) */
                mergeU128(r0, r1, r2)
            CVTNativeInt.NATIVEINT_FROM_U256 -> /* build a nativeint from u256 (four 64-bit registers) */
                mergeU256(r0, r1, r2, r3, r4)
            CVTNativeInt.NATIVEINT_U64_MAX ->
                assign(r0, (BigInteger.TWO.pow(64) - BigInteger.ONE).asTACExpr())
            CVTNativeInt.NATIVEINT_U128_MAX ->
                assign(r0, (BigInteger.TWO.pow(128) - BigInteger.ONE).asTACExpr())
            CVTNativeInt.NATIVEINT_U256_MAX ->
                assign(r0, (BigInteger.TWO.pow(256) - BigInteger.ONE).asTACExpr())
        }
    )
}
