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

import sbf.cfg.*
import sbf.disassembler.SbfRegister
import java.math.BigInteger
import tac.Tag
import vc.data.*
import datastructures.stdcollections.*

class TACExprBuilder(private val regVars: ArrayList<TACSymbol.Var>) {
    private val mask64 =  (BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO + BigInteger.ONE).asTACExpr()
    private val MINUS_ONE = mkConst(-1)
    val ONE = mkConst(1)
    val ZERO = mkConst(0)
    val SIXTY_FOUR = mkConst(64)
    private val LONG_MIN = mkConst(Long.MIN_VALUE)

    /** Return a TAC constant from a Boolean **/
    fun mkBoolConst(value: Boolean): TACSymbol.Const {
        return if (value) {
            TACSymbol.True
        } else {
            TACSymbol.False
        }
    }

    /** Return a TAC constant from BigInteger **/
    private fun mkConst(value: BigInteger, useTwosComplement: Boolean = true, bitwidth: Short = 256): TACSymbol.Const {
        @Suppress("ForbiddenComment")
        // FIXME: 256-bit integer is hardcoded. More info in Jira SOL-27
        return if (useTwosComplement && value < BigInteger.ZERO) {
            // If the number is negative then we use its two's-complement representation
            TACSymbol.Const( BigInteger.TWO.pow(bitwidth.toInt()) + value, Tag.Bit256)
        } else {
            TACSymbol.Const(value, Tag.Bit256)
        }
    }

    /** Return a TAC constant from Long **/
    fun mkConst(value: Long, useTwosComplement: Boolean = true, bitwidth: Short = 256) =
        mkConst(value.toBigInteger(), useTwosComplement, bitwidth)

    /** Return a TAC constant from Value.Imm **/
    fun mkConst(value: Value.Imm, useTwosComplement: Boolean = true, bitwidth: Short = 256) =
        mkConst(value.v.toLong(), useTwosComplement, bitwidth)


    /** Convert a register to a TAC variable **/
    fun mkVar(reg: SbfRegister): TACSymbol.Var {
        val i = reg.value.toInt()
        check(i in 0 until NUM_OF_SBF_REGISTERS)
        return regVars[i]
    }

    /** Convert a Value.Reg to a TAC variable **/
    fun mkVar(reg: Value.Reg): TACSymbol.Var {
        return mkVar(reg.r)
    }

    /** Convert a Value to TAC Expression **/
    fun mkExprSym(v: Value, useTwosComplement: Boolean = true): TACExpr.Sym {
        return when (v) {
            is Value.Reg -> mkVar(v).asSym()
            is Value.Imm ->  mkConst(v, useTwosComplement).asSym()
        }
    }

    private fun checkBinExprArgs(op1: TACExpr.Sym, op2: TACExpr.Sym, useMathInt: Boolean, op: String) {
        check(if (useMathInt) {
            op1.tag == Tag.Int && op2.tag == Tag.Int
        } else {
            op1.tag == Tag.Bit256 && op2.tag == Tag.Bit256
        }) {"Unexpected types in operands of $op(${op1.tag}, ${op2.tag}) with useMathInt=$useMathInt"}
    }

    /** Return the equivalent TAC expression of [op1] + [op2]  **/
    fun mkAddExpr(op1: TACExpr.Sym, op2: TACExpr.Sym, useMathInt: Boolean): TACExpr {
        checkBinExprArgs(op1, op2, useMathInt, "mkAddExpr")
        val op2C = op2.evalAsConst()
        return if (op2C != null && op2C < BigInteger.ZERO) {
            /// Rewrite ADD with SUB to avoid generating large constants due to two's complement
            if (useMathInt) {
                TACExpr.BinOp.IntSub(op1, op2C.abs().asTACExpr())
            } else {
                TACExpr.BinOp.Sub(op1, op2C.abs().asTACExpr())
            }
        } else {
            if (useMathInt) {
                TACExpr.Vec.IntAdd(listOf(op1, op2))
            } else {
                TACExpr.Vec.Add(listOf(op1, op2))
            }
        }
    }

    /** Return the equivalent TAC expression of [op1] - [op2]  **/
    private fun mkSubExpr(op1: TACExpr.Sym, op2: TACExpr.Sym, useMathInt: Boolean): TACExpr {
        checkBinExprArgs(op1, op2, useMathInt, "mkSubExpr")
        val op2C = op2.evalAsConst()
        return if (op2C != null && op2C < BigInteger.ZERO) {
            /// Rewrite SUB with ADD to avoid generating large constants due to two's complement
            if (useMathInt) {
                TACExpr.Vec.IntAdd(listOf(op1, op2C.abs().asTACExpr()))
            } else {
                TACExpr.Vec.Add(listOf(op1, op2C.abs().asTACExpr()))
            }
        } else {
            if (useMathInt) {
                TACExpr.BinOp.IntSub(op1, op2)
            } else {
                TACExpr.BinOp.Sub(op1, op2)
            }
        }
    }

    /** Return the equivalent TAC expression of [op1] * [op2]  **/
    private fun mkMulExpr(op1: TACExpr.Sym, op2: TACExpr.Sym, useMathInt: Boolean): TACExpr {
        checkBinExprArgs(op1, op2, useMathInt, "mkMulExpr")
        return if (useMathInt) {
            TACExpr.Vec.IntMul(listOf(op1, op2))
        } else {
            TACExpr.Vec.Mul(listOf(op1, op2))
        }
    }

    /** Return the equivalent TAC expression of [op1] / [op2]  **/
    private fun mkDivExpr(op1: TACExpr.Sym, op2: TACExpr.Sym, useMathInt: Boolean): TACExpr {
        checkBinExprArgs(op1, op2, useMathInt, "mkDivExpr")
        return if (useMathInt) {
            TACExpr.BinOp.IntDiv(op1, op2)
        } else {
            TACExpr.BinOp.Div(op1, op2)
        }
    }

    /** Return the equivalent TAC expression of [op1] mod [op2]  **/
    private fun mkModExpr(op1: TACExpr.Sym, op2: TACExpr.Sym, useMathInt: Boolean): TACExpr {
        checkBinExprArgs(op1, op2, useMathInt, "mkModExpr")
        return if (useMathInt) {
            TACExpr.BinOp.IntMod(op1, op2)
        } else {
            TACExpr.BinOp.Mod(op1, op2)
        }
    }


    /** Return the equivalent TAC expression of [value] << [shift] **/
    private fun mkLshExpr(value: TACExpr.Sym, shift: TACExpr): TACExpr {
        /* mask64 because TAC uses 256bits but SBF uses 64bits */
        return mask64(TACExpr.BinOp.ShiftLeft(value, shift))
    }

    /** Return the equivalent TAC expression of arithmetic [value] >> [shift] **/
    private fun mkArshExpr(value: TACExpr.Sym, shift: TACExpr): TACExpr {
        /* mask64 because TAC uses 256bits but SBF uses 64bits */
        return TACExpr.BinOp.ShiftRightArithmetical(mask64(value), shift)
    }

    /** Return the equivalent TAC expression of logical [value] >> [shift] **/
    private fun mkRshExpr(value: TACExpr.Sym, shift: TACExpr): TACExpr {
        /* mask64 because TAC uses 256bits but SBF uses 64bits */
        return TACExpr.BinOp.ShiftRightLogical(mask64(value), shift)
    }

    private fun mkModNegExpr(value: TACExpr.Sym): TACExpr {
        // This operation is wrapping modular negation
        // Search for NEG32/NEG64 in https://github.com/solana-labs/rbpf/blob/main/src/interpreter.rs#L328
        // Arithmetic modeling
        // neg(x) = if x == Long.MIN_VALUE then LONG_MIN_VALUE else -x

        val longMin = LONG_MIN.asSym()
        return TACExpr.TernaryExp.Ite(
            TACExpr.BinRel.Eq(value, longMin),
            value,
            TACExpr.Vec.Mul(listOf(MINUS_ONE.asSym(), value))
        )
    }

    /** Return the equivalent TAC expression of logical [op1] xor [op2] **/
    private fun mkXorExpr(op1: TACExpr.Sym, op2: TACExpr): TACExpr {
        /* mask64 because TAC uses 256bits but SBF uses 64bits */
        return mask64(TACExpr.BinOp.BWXOr(op1, op2))
    }

    /** Return the equivalent TAC expression of logical [op1] or [op2] **/
    private fun mkOrExpr(op1: TACExpr.Sym, op2: TACExpr): TACExpr {
        return TACExpr.BinOp.BWOr(op1, op2)
    }

    /** Return the equivalent TAC expression of logical [op1] and [op2] **/
    private fun mkAndExpr(op1: TACExpr.Sym, op2: TACExpr): TACExpr {
        return TACExpr.BinOp.BWAnd(op1, op2)
    }

    /**
     * Return the equivalent TAC expression [dstE] [op] [srcE]
     * By default, all the operations are 256-bit modulo.
     * If the operation takes [useMathInt] and the flag is true then the operation is over mathematical integers.
     * **/
    fun mkBinExpr(op: BinOp, dstE: TACExpr.Sym, srcE: TACExpr.Sym, useMathInt: Boolean): TACExpr {
        return when (op) {
            BinOp.ADD -> mkAddExpr(dstE, srcE, useMathInt)
            BinOp.SUB -> mkSubExpr(dstE, srcE, useMathInt)
            BinOp.MUL -> mkMulExpr(dstE, srcE, useMathInt)
            BinOp.DIV -> mkDivExpr(dstE, srcE, useMathInt)
            BinOp.MOD -> mkModExpr(dstE, srcE, useMathInt)
            BinOp.ARSH -> mkArshExpr(dstE, srcE)
            BinOp.RSH -> mkRshExpr(dstE, srcE)
            BinOp.LSH -> mkLshExpr(dstE, srcE)
            BinOp.AND -> mkAndExpr(dstE, srcE)
            BinOp.OR  -> mkOrExpr(dstE, srcE)
            BinOp.XOR -> mkXorExpr(dstE, srcE)
            BinOp.MOV -> throw TACTranslationError("mkBinExpr cannot be called with op=MOV")
        }
    }

    /** Return the equivalent TAC expression [leftE] [op] [rightE] **/
    fun mkBinRelExp(op: CondOp, leftE: TACExpr, rightE: TACExpr): TACExpr {
        return when (op) {
            CondOp.EQ -> TACExpr.BinRel.Eq(leftE, rightE)
            CondOp.NE -> TACExpr.UnaryExp.LNot(TACExpr.BinRel.Eq(leftE, rightE))
            CondOp.SLT -> TACExpr.BinRel.Slt(leftE, rightE)
            CondOp.SGT -> TACExpr.BinRel.Sgt(leftE, rightE)
            CondOp.LT -> TACExpr.BinRel.Lt(leftE, rightE)
            CondOp.GT -> TACExpr.BinRel.Gt(leftE, rightE)
            CondOp.LE -> TACExpr.BinRel.Le(leftE, rightE)
            CondOp.SLE -> TACExpr.BinRel.Sle(leftE, rightE)
            CondOp.GE -> TACExpr.BinRel.Ge(leftE, rightE)
            CondOp.SGE -> TACExpr.BinRel.Sge(leftE, rightE)
        }
    }

    /** Return the equivalent TAC expression [leftE] [op] [right] **/
    fun mkBinRelExp(op: CondOp, leftE: TACExpr.Sym, right: Long) =
        mkBinRelExp(op, leftE, right.toBigInteger())

    /** Return the equivalent TAC expression [leftE] [op] [right] **/
    fun mkBinRelExp(op: CondOp, leftE: TACExpr.Sym, right: BigInteger): TACExpr {
        // originally we did transformations:
        //    x < y --> x <= y-1 and x > y --> x >= y+1
        // but no clear gains.
        return mkBinRelExp(op, leftE, mkConst(right).asSym())
    }

    /** Return the equivalent TAC expression [op] [value] **/
   fun mkUnExpr(op: UnOp, value: Value.Reg): TACExpr {
        val valExp = mkExprSym(value)
        return when (op) {
            UnOp.NEG -> mkModNegExpr(valExp)
            else -> throw TACTranslationError("TACExprBuilder only supports NEG operator")
        }
    }


    /** Return the equivalent TAC expression to [e] && MASK64 **/
    fun mask64(e: TACExpr): TACExpr {
        return TACExpr.BinOp.BWAnd(e, mask64)
    }
}
