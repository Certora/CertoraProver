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

package wasm.host.soroban.modules

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import tac.generation.*
import vc.data.*
import wasm.host.soroban.*
import wasm.host.soroban.types.*
import java.math.BigInteger

internal object IntModuleImpl : ModuleImpl() {
    override fun getFuncImpl(funcName: String, args: List<TACSymbol>, retVar: TACSymbol.Var?) =
        when(funcName) {
            "obj_from_u64" -> IntType.U64.fromPieces(retVar!!, args[0])
            "obj_to_u64" -> IntType.U64.toPiece(retVar!!, args[0], 0)

            "obj_from_i64" -> IntType.I64.fromPieces(retVar!!, args[0])
            "obj_to_i64" -> IntType.I64.toPiece(retVar!!, args[0], 0)

            "obj_from_u128_pieces" -> IntType.U128.fromPieces(retVar!!, args[0], args[1])
            "obj_to_u128_hi64" -> IntType.U128.toPiece(retVar!!, args[0], 0)
            "obj_to_u128_lo64" -> IntType.U128.toPiece(retVar!!, args[0], 1)

            "obj_from_i128_pieces" -> IntType.I128.fromPieces(retVar!!, args[0], args[1])
            "obj_to_i128_hi64" -> IntType.I128.toPiece(retVar!!, args[0], 0)
            "obj_to_i128_lo64" -> IntType.I128.toPiece(retVar!!, args[0], 1)

            "obj_from_u256_pieces" -> IntType.U256.fromPieces(retVar!!, args[0], args[1], args[2], args[3])
            "obj_to_u256_hi_hi" -> IntType.U256.toPiece(retVar!!, args[0], 0)
            "obj_to_u256_hi_lo" -> IntType.U256.toPiece(retVar!!, args[0], 1)
            "obj_to_u256_lo_hi" -> IntType.U256.toPiece(retVar!!, args[0], 2)
            "obj_to_u256_lo_lo" -> IntType.U256.toPiece(retVar!!, args[0], 3)

            "obj_from_i256_pieces" -> IntType.I256.fromPieces(retVar!!, args[0], args[1], args[2], args[3])
            "obj_to_i256_hi_hi" -> IntType.I256.toPiece(retVar!!, args[0], 0)
            "obj_to_i256_hi_lo" -> IntType.I256.toPiece(retVar!!, args[0], 1)
            "obj_to_i256_lo_hi" -> IntType.I256.toPiece(retVar!!, args[0], 2)
            "obj_to_i256_lo_lo" -> IntType.I256.toPiece(retVar!!, args[0], 3)

            "u256_val_from_be_bytes" -> IntType.U256.integerFromBigEndianBytes(retVar!!, args[0])
            "u256_val_to_be_bytes" -> IntType.U256.integerToBigEndianBytes(retVar!!, args[0])

            "u256_add" -> addUnsigned(retVar!!, args[0], args[1])
            "u256_sub" -> subUnsigned(retVar!!, args[0], args[1])
            "u256_mul" -> mulUnsigned(retVar!!, args[0], args[1])
            "u256_div" -> divUnsigned(retVar!!, args[0], args[1])
            "u256_rem_euclid" -> modUnsigned(retVar!!, args[0], args[1])
            "u256_pow" -> null // TODO CERT-7016
            "u256_shl" -> shlUnsigned(retVar!!, args[0], args[1])
            "u256_shr" -> shrUnsigned(retVar!!, args[0], args[1])

            "i256_add" -> addSigned(retVar!!, args[0], args[1])
            "i256_sub" -> subSigned(retVar!!, args[0], args[1])
            "i256_mul" -> mulSigned(retVar!!, args[0], args[1])
            "i256_div" -> divSigned(retVar!!, args[0], args[1])
            "i256_rem_euclid" -> remEuclidSigned(retVar!!, args[0], args[1])
            "i256_pow" -> null // TODO CERT-7016
            "i256_shl" -> shlSigned(retVar!!, args[0], args[1])
            "i256_shr" -> shrSigned(retVar!!, args[0], args[1])

            "timepoint_obj_from_u64" -> IntType.Timepoint.fromPieces(retVar!!, args[0])
            "timepoint_obj_to_u64" -> IntType.Timepoint.toPiece(retVar!!, args[0], 0)

            "duration_obj_from_u64" -> IntType.Duration.fromPieces(retVar!!, args[0])
            "duration_obj_to_u64" -> IntType.Duration.toPiece(retVar!!, args[0], 0)

            else -> null
        }

    private val I256_MIN = BigInteger.TWO.pow(BIT256_BITS-1).asTACExpr
    private val I256_NEGATIVE_ONE = (BigInteger.TWO.pow(BIT256_BITS) - BigInteger.ONE).asTACExpr

    fun addUnsigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            assign(dest) { a.asSym() add b.asSym() },
            Trap.assert("overflow") { dest.asSym() ge a.asSym() }
        )

    fun addSigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            assign(dest) { a.asSym() add b.asSym() },
            Trap.assert("overflow") {
                ((b.asSym() sGe 0.asTACExpr) and (dest.asSym() sGe a.asSym())) or
                ((b.asSym() sLt 0.asTACExpr) and (dest.asSym() sLt a.asSym()))
            }
        )

    fun subUnsigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            assign(dest) { a.asSym() sub b.asSym() },
            Trap.assert("overflow") { dest.asSym() le a.asSym() },
        )

    fun subSigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            assign(dest) { a.asSym() sub b.asSym() },
            Trap.assert("overflow") {
                ((b.asSym() sGe 0.asTACExpr) and (dest.asSym() sLe a.asSym())) or
                ((b.asSym() sLt 0.asTACExpr) and (dest.asSym() sGt a.asSym()))
            },
        )

    fun mulUnsigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("overflow") { noMulOverflow(a.asSym(), b.asSym()) },
            assign(dest) { a.asSym() mul b.asSym() },
        )

    fun mulSigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("overflow") { noSMulOverAndUnderflow(a.asSym(), b.asSym()) },
            assign(dest) { a.asSym() mul b.asSym() },
        )

    fun divUnsigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("division by zero") { b.asSym() neq 0.asTACExpr },
            assign(dest) { a.asSym() div b.asSym() }
        )

    fun divSigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("division by zero") { b.asSym() neq 0.asTACExpr },
            Trap.assert("overflow") { (a.asSym() neq I256_MIN) or (b.asSym() neq I256_NEGATIVE_ONE) },
            assign(dest) { a.asSym() sDiv b.asSym() }
        )

    fun modUnsigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("division by zero") { b.asSym() neq 0.asTACExpr },
            assign(dest) { a.asSym() mod b.asSym() }
        )

    fun remEuclidSigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("division by zero") { b.asSym() neq 0.asTACExpr },
            Trap.assert("overflow") { (a.asSym() neq I256_MIN) or (b.asSym() neq I256_NEGATIVE_ONE) },
            assign(dest) {
                val remainder = a.asSym() sMod b.asSym()
                ite(
                    remainder sLt 0.asTACExpr,
                    ite(
                        b.asSym() sLt 0.asTACExpr,
                        remainder sub b.asSym(),
                        remainder add b.asSym(),
                    ),
                    remainder,
                )
            }
        )

    fun shlUnsigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("shift amount too large") { b.asSym() le BIT256_BITS.asTACExpr },
            assign(dest) { a.asSym() shiftL b.asSym() }
        )

    fun shlSigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("shift amount too large") { b.asSym() le BIT256_BITS.asTACExpr },
            assign(dest) { a.asSym() shiftL b.asSym() }
        )

    fun shrUnsigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("shift amount too large") { b.asSym() le BIT256_BITS.asTACExpr },
            assign(dest) { a.asSym() shiftRLog b.asSym() }
        )

    fun shrSigned(dest: TACSymbol.Var, a: TACSymbol, b: TACSymbol) =
        mergeMany(
            Trap.assert("shift amount too large") { b.asSym() le (BIT256_BITS-1).asTACExpr },
            assign(dest) { a.asSym() shiftRArith b.asSym() }
        )
}
