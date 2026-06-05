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
import tac.*
import vc.data.*
import datastructures.stdcollections.*
import sbf.SolanaConfig
import utils.*

val modz64 = ConcreteModZm(64)
val modz128 = ConcreteModZm(128)

/** Common base for TAC expression factories operating on 256-bit values **/
abstract class TACExprBase(private val regVars: ArrayList<TACSymbol.Var>) {
    val mask8   = TACSymbol.Const(BigInteger("FF", 16), Tag.Bit256).asSym()
    val mask16  = TACSymbol.Const(BigInteger("FFFF", 16), Tag.Bit256).asSym()
    val mask32  = TACSymbol.Const(BigInteger("FFFFFFFF", 16), Tag.Bit256).asSym()
    val mask64  = TACSymbol.Const(BigInteger("FFFFFFFFFFFFFFFF", 16), Tag.Bit256).asSym()
    val mask128 = TACSymbol.Const(BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", 16), Tag.Bit256).asSym()

    val ONE      = TACSymbol.Const(1.toBigInteger(), Tag.Bit256).asSym()
    val ZERO     = TACSymbol.Const(0.toBigInteger(), Tag.Bit256).asSym()
    val c64      = TACSymbol.Const(64.toBigInteger(), Tag.Bit256).asSym()
    val c128     = TACSymbol.Const(128.toBigInteger(), Tag.Bit256).asSym()
    val c196     = TACSymbol.Const(196.toBigInteger(), Tag.Bit256).asSym()
    val U64_MAX  = TACSymbol.Const(BigInteger.TWO.pow(64) - BigInteger.ONE, Tag.Bit256).asSym()
    val U128_MAX = TACSymbol.Const(BigInteger.TWO.pow(128) - BigInteger.ONE, Tag.Bit256).asSym()
    val U256_MAX = TACSymbol.Const(BigInteger.TWO.pow(256) - BigInteger.ONE, Tag.Bit256).asSym()

    /** Convert an SBF register [reg] to a TAC variable **/
    fun mkVar(reg: SbfRegister): TACSymbol.Var {
        val i = reg.value.toInt()
        check(i in 0 until NUM_OF_SBF_REGISTERS)
        return regVars[i]
    }

    /** Return the equivalent TAC expression to [e] & ((1 << [bits]) - 1) **/
    fun mask(e: TACExpr, bits: Long): TACExpr {
        val maskExpr = when (bits) {
            8L   -> mask8
            16L  -> mask16
            32L  -> mask32
            64L  -> mask64
            128L -> mask128
            else -> throw TACTranslationError("mask only supports bitwidths {8, 16, 32, 64, 128}, got $bits")
        }
        return TACExpr.BinOp.BWAnd(e, maskExpr)
    }

    /**
     * Sign extend [e] from [fromWidth] to 256 bits
     *
     * @param [fromWidth] Can only be one of these bitwidths 8, 16, 32, 64, or 128
     **/
    protected fun signExtendSbfValue(e: TACExpr, fromWidth: Long): TACExpr {
        return when (fromWidth) {
            8L   -> TACExpr.BinOp.SignExtend(BigInteger.valueOf(0).asTACExpr(), e)
            16L  -> TACExpr.BinOp.SignExtend(BigInteger.valueOf(1).asTACExpr(), e)
            32L  -> TACExpr.BinOp.SignExtend(BigInteger.valueOf(3).asTACExpr(), e)
            64L  -> TACExpr.BinOp.SignExtend(BigInteger.valueOf(7).asTACExpr(), e)
            128L -> TACExpr.BinOp.SignExtend(BigInteger.valueOf(15).asTACExpr(), e)
            else -> throw TACTranslationError("signExtendSbfValue only supports one of these bitwidths {8,16,32,64,128}")
        }
    }

    /**
     * Apply AND of [e] and `2^fromWidth -1` and sign extend the result from [fromWidth] to 256 bits
     *
     * @param [fromWidth] Can only be one of these bitwidths 8, 16, 32, 64, or 128
     **/
    fun signExtendSbfValueWithMask(e: TACExpr, fromWidth: Long): TACExpr =
        signExtendSbfValue(mask(e, fromWidth), fromWidth)
}

/**
 * Abstract base for building TAC expressions from SBF instructions.
 *
 * SBF registers are 64-bit but TAC uses 256-bit values, so every operation must bridge the two
 * domains.  The concrete subclass decides how 64-bit semantics are enforced inside 256-bit
 * arithmetic (e.g. whether results are masked after each op or only where strictly necessary).
 *
 * Assumptions on the input:
 * - SBF register values ([Value.Reg]) have already been mapped to TAC 256-bit variables.
 * - Stack slots have been scalarized into TAC 256-bit variables before this stage.
 * - Non-stack (heap/external) memory is represented as `ByteMap` and accessed via [load].
 **/
abstract class SbfTACBuilder(regVars: ArrayList<TACSymbol.Var>) : TACExprBase(regVars) {
    operator fun invoke(expr: SbfTACBuilder.() -> TACExpr): TACExpr = expr()

    // Long.MIN = -2^63
    protected val LONG_MIN = TACSymbol.Const(BigInteger("8000000000000000", 16), Tag.Bit256).asSym()
    val TRUE = TACSymbol.True.asSym()
    val FALSE = TACSymbol.False.asSym()

    /** Return a 256-bit TAC constant from [Long] **/
    fun mkConst(value: Long) = mkConst(value.toBigInteger())

    /** Return a 256-bit TAC constant from SBF [Value.Imm] **/
    fun mkConst(value: Value.Imm) = mkConst(value.v.toLong())

    /** Convert an SBF [Value.Reg] to a TAC variable **/
    fun mkVar(reg: Value.Reg) = mkVar(reg.r)

    /** Convert a SBF [Value] to TAC Expression **/
    fun mkExprSym(v: Value): TACExpr.Sym =
        when (v) {
            is Value.Reg -> mkVar(v).asSym()
            is Value.Imm -> mkConst(v).asSym()
        }

    // common shorthands
    fun mask64(e: TACExpr) = mask(e, 64)
    fun mask128(e: TACExpr) = mask(e, 128)

    /**
     * Generalized add/sub handling negative constants.
     *
     * If [o2] is negative, calls [negF] with absolute value of o2.
     * Otherwise, calls [posF] with o2.
     *
     * Note that this only works for Int values, not Bit256 values, since Bit256 can't have a negative BigInteger
     * value.
     */
    protected fun flipIfNegativeBigInteger(
        o1: TACExpr,
        o2: TACExpr,
        posF: (TACExpr, TACExpr) -> TACExpr,
        negF: (TACExpr, TACExpr) -> TACExpr
    ): TACExpr {
        val c2 = o2.evalAsConst()
        return if (c2 != null && c2 < BigInteger.ZERO) {
            negF(o1, c2.abs().asTACExpr())
        } else {
            posF(o1, o2)
        }
    }

    protected fun flipIfNegativeBigInteger(
        ls: List<TACExpr>,
        posF: (List<TACExpr>) -> TACExpr,
        negF: (TACExpr, TACExpr) -> TACExpr
    ): TACExpr {
        return if (ls.size == 2) {
            flipIfNegativeBigInteger(ls[0], ls[1], { a, b -> posF(listOf(a, b)) }, negF)
        } else {
            posF(ls)
        }
    }

    /// Int operators
    private fun IntMul(ls: List<TACExpr>) = TACExpr.Vec.IntMul(ls)
    private fun IntAdd(ls: List<TACExpr>) =
        flipIfNegativeBigInteger(ls, { TACExpr.Vec.IntAdd(it) }, { x, y -> TACExpr.BinOp.IntSub(x, y) })
    private fun IntSub(o1: TACExpr, o2: TACExpr) =
        flipIfNegativeBigInteger(o1, o2, { x, y -> TACExpr.BinOp.IntSub(x, y) }, { x, y -> TACExpr.Vec.IntAdd(x, y) })
    private fun IntDiv(o1: TACExpr, o2: TACExpr) = TACExpr.BinOp.IntDiv(o1, o2)
    private fun IntMod(o1: TACExpr, o2: TACExpr) = TACExpr.BinOp.IntMod(o1, o2)

    //-----------------------------------------------------------------------------------------
    // Subclass contract: encoding of 64-bit SBF operations into 256-bit TAC.
    //
    // Each subclass must implement all functions below.
    //
    // The key degree of freedom between subclasses is how strictly the 64-bit range is
    // enforced: eagerly (mask after every op) vs lazily (mask only where semantically required).
    //-----------------------------------------------------------------------------------------

    /** Return a 256-bit TAC constant from [BigInteger] **/
    abstract fun mkConst(value: BigInteger): TACSymbol.Const

    abstract val needOverflowPromotion: Boolean
    abstract val useTACSignedMath: Boolean

    context(SbfCFGToTAC<*, *, *>)
    abstract fun assumeSignedIntRange(v: TACSymbol.Var, bits: Int): List<TACCmd.Simple>
    context(SbfCFGToTAC<*, *, *>)
    abstract fun assumeUnsignedIntRange(v: TACSymbol.Var, bits: Int): List<TACCmd.Simple>

    protected abstract fun Mul(ls: List<TACExpr>): TACExpr
    protected abstract fun Mul128(ls: List<TACExpr>): TACExpr
    protected abstract fun Add(ls: List<TACExpr>): TACExpr
    /** Addition, annotated with a hint that the addition will not overflow.  We use this in later optimizations. */
    protected abstract fun AddNoOvf(ls: List<TACExpr>, reason: String): TACExpr
    protected abstract fun Sub(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Div(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Div128(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun SDiv(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun SDiv128(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Mod(o1: TACExpr, o2: TACExpr): TACExpr

    protected abstract fun Gt(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Lt(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Slt(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Sle(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Sgt(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Sge(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Ge(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Le(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun Eq(o1: TACExpr, o2: TACExpr): TACExpr

    protected abstract fun BWAnd(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun BWOr(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun BWXOr(o1: TACExpr, o2: TACExpr): TACExpr

    protected abstract fun ShiftLeft(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun ShiftLeft128(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun ShiftRightLogical(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun ShiftRightArithmetical(o1: TACExpr, o2: TACExpr): TACExpr
    protected abstract fun ShiftRightArithmetical128(o1: TACExpr, o2: TACExpr): TACExpr

    /**
     *  Wrapping modular negation
     *  Search for `NEG64` in https://github.com/solana-labs/rbpf/blob/main/src/interpreter.rs
     *  Arithmetic modeling: `neg(x) = if x == Long.MIN_VALUE then x else -x`
     **/
    protected abstract fun ModNeg(value: TACExpr): TACExpr

    /** Return expression `high << 64 + low` **/
    abstract fun mergeU128(
        low: TACExpr.Sym,
        high: TACExpr.Sym,
        /** If true, the implementation is allowed to mask the low bits (ignored in "eager masking" mode) */
        mayMaskLowBits: Boolean
    ): TACExpr

    /** Return expression `(w4 << 196) + (w3 << 128) + (w2 << 64) + w1` */
    abstract fun mergeU256(
        w1: TACExpr.Sym,
        w2: TACExpr.Sym,
        w3: TACExpr.Sym,
        w4: TACExpr.Sym,
        /** If true, the implementation is allowed to mask the low bits (ignored in "eager masking" mode) */
        mayMaskLowBits: Boolean
    ): TACExpr

    /**
     *  Return the pair (`low`,`high`) such that:
     *  ```
     *  low = e & MASK64
     *  high = e >> 64
     *  ```
     **/
    abstract fun splitU128(e: TACExpr): Pair<TACExpr, TACExpr>

    /**
     * Return TAC instructions that read [width] bytes from [map] at index [idx] and stores the result in [lhs].
     **/
    abstract fun load(lhs: TACSymbol.Var, idx: TACSymbol, widthBytes: Short, map: TACSymbol.Var): List<TACCmd.Simple>

    //-----------------------------------------------------------------------------------------

    private fun LAnd(ls: List<TACExpr>) = TACExpr.BinBoolOp.LAnd(ls)
    private fun LOr(ls: List<TACExpr>) = TACExpr.BinBoolOp.LOr(ls)
    private fun LNot(o: TACExpr): TACExpr = TACExpr.UnaryExp.LNot(o)

    /** Convert [e] which is a 256-bit TAC expression into a mathint expression **/
    fun bv256ToMathInt(e: TACExpr) =
        TACExpr.Apply(
            f = TACExpr.TACFunctionSym.BuiltIn(
                TACBuiltInFunction.SafeMathPromotion(Tag.Bit256)
            ),
            ops = listOf(e),
            tag = Tag.Int
        )

    /** Convert [e] which is a mathint expression into a 256-bit TAC expression **/
    fun mathIntToBv256(e: TACExpr) =
        TACExpr.Apply(
            TACExpr.TACFunctionSym.BuiltIn(TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256)),
            listOf(e),
            Tag.Bit256
        )

    /** Return a new (unnamed) map such that for all index in the map, its value is [value] **/
    fun defineMap(value: Long): TACExpr =
        TACExpr.MapDefinition(
            defParams = kotlin.collections.listOf(TACKeyword.TMP(Tag.Bit256, "!idx").toUnique("!").asSym()),
            tag = Tag.ByteMap,
            definition = if (value == 0L) {
                mkConst(value).asSym()
            } else {
                TACExpr.Unconstrained(Tag.Bit256)
            }
        )

    private fun typeCheck(op: BinOp, o1: TACExpr.Sym, o2: TACExpr.Sym, useMathInt: Boolean) {
        val ok = if (useMathInt) {
            o1.tag == Tag.Int && o2.tag == Tag.Int
        } else {
            o1.tag == Tag.Bit256 && o2.tag == Tag.Bit256
        }
        if (!ok) {
            throw TACTranslationError("Unexpected types in $op(${o1.tag}, ${o2.tag}) with useMathInt=$useMathInt")
        }
    }

    /**
     * Return the equivalent TAC expression from SBF [o1] [op] [o2]
     * By default, all the operations are defined over 64-bits semantics (using 256-bit)
     * If the operation takes [useMathInt] and the flag is true then the operation is over mathematical integers.
     **/
    fun mkBinOpExp(op: BinOp, o1: TACExpr.Sym, o2: TACExpr.Sym, useMathInt: Boolean): TACExpr {
        typeCheck(op, o1, o2, useMathInt)
        return when (op) {
            BinOp.ADD  -> if (useMathInt) { IntAdd(listOf(o1,o2)) } else { Add(listOf(o1,o2)) }
            BinOp.SUB  -> if (useMathInt) { IntSub(o1,o2) } else { Sub(o1,o2) }
            BinOp.RSUB -> if (useMathInt) { IntSub(o2,o1) } else { Sub(o2,o1) }
            BinOp.MUL  -> if (useMathInt) { IntMul(listOf(o1,o2)) }  else { Mul(listOf(o1,o2)) }
            BinOp.DIV  -> if (useMathInt) { IntDiv(o1,o2) } else { Div(o1,o2) }
            BinOp.SDIV -> SDiv(o1,o2)
            BinOp.MOD  -> if (useMathInt) { IntMod(o1, o2) } else { Mod(o1,o2) }
            BinOp.SREM -> Sub(o1, Mul(listOf(SDiv(o1, o2), o2)))
            BinOp.UHMUL -> ShiftRightLogical(Mul128(listOf(mask64(o1), mask64(o2))), c64)
            BinOp.SHMUL -> mask64(ShiftRightArithmetical128(Mul128(listOf(signExtendSbfValue(o1, 64L), signExtendSbfValue(o2, 64L))), c64))
            BinOp.ARSH -> ShiftRightArithmetical(o1,o2)
            BinOp.RSH  -> ShiftRightLogical(o1, o2)
            BinOp.LSH  -> ShiftLeft(o1, o2)
            BinOp.AND  -> BWAnd(o1, o2)
            BinOp.OR   -> BWOr(o1, o2)
            BinOp.XOR  -> BWXOr(o1, o2)
            BinOp.MOV  -> throw TACTranslationError("mkBinExpr cannot be called with op=MOV")
        }
    }

    /** Return the equivalent TAC expression from SBF [o1] [op] [o2] **/
    fun mkCondOpExp(op: CondOp, o1: TACExpr, o2: TACExpr): TACExpr =
        when (op) {
            CondOp.EQ  -> Eq(o1, o2)
            CondOp.NE  -> TACExpr.UnaryExp.LNot(Eq(o1, o2))
            CondOp.SLT -> Slt(o1, o2)
            CondOp.SGT -> Sgt(o1, o2)
            CondOp.LT  -> Lt(o1, o2)
            CondOp.GT  -> Gt(o1, o2)
            CondOp.LE  -> Le(o1, o2)
            CondOp.SLE -> Sle(o1, o2)
            CondOp.GE  -> Ge(o1, o2)
            CondOp.SGE -> Sge(o1, o2)
        }

    /** Return the equivalent TAC expression from SBF [op] [r] **/
    fun mkUnOpExp(op: UnOp, r: Value.Reg): TACExpr =
        when (op) {
            UnOp.NEG -> ModNeg(mkExprSym(r))
            else -> throw TACTranslationError("TACExprBuilder only supports NEG operator")
        }

    fun ite(i: TACExpr, t: TACExpr, e: TACExpr): TACExpr = TACExpr.TernaryExp.Ite(i,t,e)

    /** Return a nested ite term from [keyValPairs] and [default] **/
    fun switch(vararg keyValPairs: Pair<TACExpr, TACExpr>, default: TACExpr): TACExpr =
        keyValPairs.reversed().fold(default) { acc, (key, value) ->
            TACExpr.TernaryExp.Ite(
                key,
                value,
                acc
            )
        }

    // logical operator shorthands

    infix fun ToTACExpr.and(other: ToTACExpr) = this@SbfTACBuilder.LAnd(listOf(this.toTACExpr(), other.toTACExpr()))
    fun and(vararg args: TACExpr) = LAnd(args.toList())

    infix fun ToTACExpr.or(other: ToTACExpr) = this@SbfTACBuilder.LOr(listOf(this.toTACExpr(), other.toTACExpr()))
    fun or(vararg args: TACExpr) = LOr(args.toList())
    fun or(args: List<TACExpr>) = LOr(args)

    fun not(exp: ToTACExpr) = this@SbfTACBuilder.LNot(exp.toTACExpr())

    // relational operator shorthands

    infix fun ToTACExpr.lt(other: ToTACExpr)  = this@SbfTACBuilder.Lt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.gt(other: ToTACExpr)  = this@SbfTACBuilder.Gt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.le(other: ToTACExpr)  = this@SbfTACBuilder.Le(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.ge(other: ToTACExpr)  = this@SbfTACBuilder.Ge(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sLt(other: ToTACExpr) = this@SbfTACBuilder.Slt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sGt(other: ToTACExpr) = this@SbfTACBuilder.Sgt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sLe(other: ToTACExpr) = this@SbfTACBuilder.Sle(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sGe(other: ToTACExpr) = this@SbfTACBuilder.Sge(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.eq(other: ToTACExpr)  = this@SbfTACBuilder.Eq(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.neq(other: ToTACExpr) = this@SbfTACBuilder.LNot(this@SbfTACBuilder.Eq(this.toTACExpr(), other.toTACExpr()))

    // math operator shorthands

    infix fun ToTACExpr.mul(other: ToTACExpr)    = this@SbfTACBuilder.Mul(listOf(this.toTACExpr(), other.toTACExpr()))
    infix fun ToTACExpr.mul128(other: ToTACExpr) = this@SbfTACBuilder.Mul128(listOf(this.toTACExpr(), other.toTACExpr()))
    infix fun ToTACExpr.intMul(other: ToTACExpr) = this@SbfTACBuilder.IntMul(listOf(this.toTACExpr(), other.toTACExpr()))
    infix fun ToTACExpr.div(other: ToTACExpr)    = this@SbfTACBuilder.Div(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.div128(other: ToTACExpr) = this@SbfTACBuilder.Div128(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sDiv(other: ToTACExpr)   = this@SbfTACBuilder.SDiv(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sDiv128(other: ToTACExpr) = this@SbfTACBuilder.SDiv128(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.intDiv(other: ToTACExpr) = this@SbfTACBuilder.IntDiv(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.add(other: ToTACExpr)    = this@SbfTACBuilder.Add(listOf(this.toTACExpr(), other.toTACExpr()))
    infix fun ToTACExpr.intAdd(other: ToTACExpr) = this@SbfTACBuilder.IntAdd(listOf(this.toTACExpr(), other.toTACExpr()))
    infix fun ToTACExpr.sub(other: ToTACExpr)    = this@SbfTACBuilder.Sub(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.intSub(other: ToTACExpr) = this@SbfTACBuilder.IntSub(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.mod(other: ToTACExpr)    = this@SbfTACBuilder.Mod(this.toTACExpr(), other.toTACExpr())
    fun ToTACExpr.addNoOvf(other: ToTACExpr, reason: String) = this@SbfTACBuilder.AddNoOvf(listOf(this.toTACExpr(), other.toTACExpr()), reason)

    // bitwise operator shorthands

    infix fun ToTACExpr.bwAnd(other: ToTACExpr)      = this@SbfTACBuilder.BWAnd(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.bwOr(other: ToTACExpr)       = this@SbfTACBuilder.BWOr(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.bwXor(other: ToTACExpr)      = this@SbfTACBuilder.BWXOr(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.shiftRLog(other: ToTACExpr)  = this@SbfTACBuilder.ShiftRightLogical(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.shiftRArith(other: ToTACExpr)= this@SbfTACBuilder.ShiftRightArithmetical(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.shiftRArith128(other: ToTACExpr)= this@SbfTACBuilder.ShiftRightArithmetical128(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.shiftL(other: ToTACExpr)     = this@SbfTACBuilder.ShiftLeft(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.shiftL128(other: ToTACExpr)  = this@SbfTACBuilder.ShiftLeft128(this.toTACExpr(), other.toTACExpr())

}

// SBF operator shorthands
operator fun BinOp.invoke(o1: TACExpr.Sym, o2: TACExpr.Sym, useMathInt: Boolean, eFac: SbfTACBuilder) =
    eFac.mkBinOpExp(this, o1, o2, useMathInt)
operator fun CondOp.invoke(o1: TACExpr, o2: TACExpr, eFac: SbfTACBuilder) =
    eFac.mkCondOpExp(this, o1,o2)
operator fun CondOp.invoke(o1: TACExpr, o2: BigInteger, eFac: SbfTACBuilder) =
    eFac.mkCondOpExp(this, o1, eFac.mkConst(o2).asSym())
operator fun CondOp.invoke(o1: TACExpr, o2: Long, eFac: SbfTACBuilder) =
    this(o1, o2.toBigInteger(), eFac)
operator fun CondOp.invoke(o1: SbfRegister, o2: Long, eFac: SbfTACBuilder) =
    eFac.mkCondOpExp(this, eFac.mkVar(o1).asSym(), eFac.mkConst(o2).asSym())
operator fun UnOp.invoke(r: Value.Reg, eFac: SbfTACBuilder) =
    eFac.mkUnOpExp(this, r)


/**
 * [SbfTACBuilder] that models 64-bit SBF register arithmetic inside 256-bit TAC values
 * **without** inserting a modulo (mask) after every operation.
 *
 * The key idea is that 256-bit arithmetic is a superset of 64-bit arithmetic for unsigned
 * operations, so most results are naturally in range and masking is redundant.  Masking is
 * only inserted where it is strictly necessary (e.g. shifts, XOR, sign-extension, and the
 * 64-vs-256-bit fix in overflow conditions).
 *
 * Two flags control correctness in the edge cases this encoding does not correctly support:
 * - [SolanaConfig.TACPromoteOverflow]: when true, overflow checks are translated via
 *   `translateOverflowCond` rather than `translateCond`, avoiding the vacuously false
 *   comparison that would result from representing [ULong.MAX_VALUE] as a signed -1.
 * - [SolanaConfig.UseTACSignedMath]: when true, operands of signed comparisons are
 *   sign-extended from 64 to 256 bits before the comparison so that the 256-bit signed
 *   relation matches the intended 64-bit signed semantics.
 **/
class LazyMaskSbfTACBuilder(regVars: ArrayList<TACSymbol.Var>) : SbfTACBuilder(regVars) {

    override val needOverflowPromotion get() = SolanaConfig.TACPromoteOverflow.get()
    override val useTACSignedMath get() = SolanaConfig.UseTACSignedMath.get()

    /** Convert [e] from Sbf semantics (64-bits arithmetic) to TAC semantics (256-bits arithmetic) **/
    private fun toTAC(op: CondOp, e: TACExpr): TACExpr {
        return if (!useTACSignedMath) {
            e
        } else {
            when (op) {
                CondOp.EQ, CondOp.NE -> mask64(e)
                CondOp.GE, CondOp.GT, CondOp.LE, CondOp.LT -> e
                CondOp.SGE, CondOp.SGT, CondOp.SLE, CondOp.SLT -> signExtendSbfValue(mask64(e), 64L)
            }
        }
    }

    context(SbfCFGToTAC<*, *, *>)
    override fun assumeSignedIntRange(v: TACSymbol.Var, bits: Int): List<TACCmd.Simple> {
        return if (useTACSignedMath) {
            // if this flag is enabled then range similar to unsigned counterparts
            assumeUnsignedIntRange(v, bits)
        } else {
            val n = BigInteger.TWO.pow(bits - 1)
            inRange(v, -n ..< n, isUnsigned = false)
        }
    }

    context(SbfCFGToTAC<*, *, *>)
    override fun assumeUnsignedIntRange(v: TACSymbol.Var, bits: Int): List<TACCmd.Simple> {
        return inRange(v, BigInteger.ZERO ..< BigInteger.TWO.pow(bits))
    }

    override fun mkConst(value: BigInteger): TACSymbol.Const {
        return if (value < BigInteger.ZERO) {
            // If the number is negative then we use its two's-complement representation
            TACSymbol.Const( BigInteger.TWO.pow(256) + value, Tag.Bit256)
        } else {
            TACSymbol.Const(value, Tag.Bit256)
        }
    }

    override fun Add(ls: List<TACExpr>) =
        flipIfNegativeBigInteger(ls, { TACExpr.Vec.Add(it) }, { x, y -> TACExpr.BinOp.Sub(x, y) })
    override fun Sub(o1: TACExpr, o2: TACExpr) =
        flipIfNegativeBigInteger(o1, o2, { x, y -> TACExpr.BinOp.Sub(x, y) }, { x, y -> TACExpr.Vec.Add(x, y) })
    override fun Mul(ls: List<TACExpr>): TACExpr = TACExpr.Vec.Mul(ls)
    override fun Div(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinOp.Div(o1,o2)
    override fun SDiv(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.SDiv(o1,o2)
    override fun Mod(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinOp.Mod(o1,o2)
    override fun AddNoOvf(ls: List<TACExpr>, reason: String) = Add(ls)

    override fun Mul128(ls: List<TACExpr>): TACExpr = TACExpr.Vec.Mul(ls)
    override fun Div128(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinOp.Div(o1,o2)
    override fun SDiv128(o1: TACExpr, o2: TACExpr): TACExpr =
        error("SDiv128 is handled specially in ${SummarizeIntegerU128CompilerRt::class.simpleName}")

    private fun <R: TACExpr> binRel(op: CondOp, o1: TACExpr, o2: TACExpr, ctor: (TACExpr, TACExpr) -> R) =
        ctor(toTAC(op, o1), toTAC(op, o2))

    override fun Gt(o1: TACExpr, o2: TACExpr)  = binRel(CondOp.GT,  o1, o2, TACExpr.BinRel::Gt)
    override fun Lt(o1: TACExpr, o2: TACExpr)  = binRel(CondOp.LT,  o1, o2, TACExpr.BinRel::Lt)
    override fun Slt(o1: TACExpr, o2: TACExpr) = binRel(CondOp.SLT, o1, o2, TACExpr.BinRel::Slt)
    override fun Sle(o1: TACExpr, o2: TACExpr) = binRel(CondOp.SLE, o1, o2, TACExpr.BinRel::Sle)
    override fun Sgt(o1: TACExpr, o2: TACExpr) = binRel(CondOp.SGT, o1, o2, TACExpr.BinRel::Sgt)
    override fun Sge(o1: TACExpr, o2: TACExpr) = binRel(CondOp.SGE, o1, o2, TACExpr.BinRel::Sge)
    override fun Ge(o1: TACExpr, o2: TACExpr)  = binRel(CondOp.GE,  o1, o2, TACExpr.BinRel::Ge)
    override fun Le(o1: TACExpr, o2: TACExpr)  = binRel(CondOp.LE,  o1, o2, TACExpr.BinRel::Le)
    override fun Eq(o1: TACExpr, o2: TACExpr)  = binRel(CondOp.EQ,  o1, o2, TACExpr.BinRel::Eq)

    override fun BWAnd(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.BWAnd(o1, o2)
    override fun BWOr(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinOp.BWOr(o1, o2)
    override fun BWXOr(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.BWXOr(mask64(o1), mask64(o2))

    override fun ShiftLeft(o1: TACExpr, o2: TACExpr) = mask64(TACExpr.BinOp.ShiftLeft(o1, o2))
    override fun ShiftLeft128(o1: TACExpr, o2: TACExpr) = mask128(TACExpr.BinOp.ShiftLeft(o1, o2))
    override fun ShiftRightLogical(o1: TACExpr, o2: TACExpr) = TACExpr.BinOp.ShiftRightLogical(mask64(o1), o2)
    override fun ShiftRightArithmetical(o1: TACExpr, o2: TACExpr) =
        if (useTACSignedMath) {
            mask64(TACExpr.BinOp.ShiftRightArithmetical(signExtendSbfValue(o1, 64), o2))
        } else {
            TACExpr.BinOp.ShiftRightArithmetical(mask64(o1), o2)
        }
    override fun ShiftRightArithmetical128(o1: TACExpr, o2: TACExpr) = TACExpr.BinOp.ShiftRightArithmetical(mask128(o1), o2)

    override fun ModNeg(value: TACExpr): TACExpr {
        val longMin = LONG_MIN
        return TACExpr.TernaryExp.Ite(
            TACExpr.BinRel.Eq(mask64(value), mask64(longMin)),
            value,
            // U256_MAX is also -1 (i.e., 0xFF.....F)
            TACExpr.Vec.Mul(listOf(U256_MAX, signExtendSbfValue(mask64(value), 64)))
        )
    }

    /** Return expression `high << 64 + low` **/
    override fun mergeU128(
        low: TACExpr.Sym,
        high: TACExpr.Sym,
        mayMaskLowBits: Boolean
    ): TACExpr {
        val o1 = TACExpr.BinOp.ShiftLeft(high, c64)
        val o2 = if (mayMaskLowBits) { mask(low, 64) } else { low }
        return TACExpr.Vec.Add(o1, o2)
    }

    /** Return expression `(w4 << 192) + (w3 << 128) + (w2 << 64) + w1` */
    override fun mergeU256(
        w1: TACExpr.Sym,
        w2: TACExpr.Sym,
        w3:TACExpr.Sym,
        w4: TACExpr.Sym,
        mayMaskLowBits: Boolean
    ): TACExpr {
        val o3 = if (mayMaskLowBits) { mask(w3, 64) } else { w3 }
        val o2 = if (mayMaskLowBits) { mask(w2, 64) } else { w2 }
        val o1 = if (mayMaskLowBits) { mask(w1, 64) } else { w1 }
        return TACExpr.Vec.Add(listOf(
            TACExpr.BinOp.ShiftLeft(w4, c196),
            TACExpr.BinOp.ShiftLeft(o3, c128),
            TACExpr.BinOp.ShiftLeft(o2, c64),
            o1)
        )
    }

    /**
     *  Return the pair (`low`,`high`) such that (logically):
     *  ```
     *  low = e & MASK64
     *  high = e.mod(2^128) >> 64
     *  ```
     *
     *  Note the additional `e.mod(2^128)` in the implementation is needed in case `e` is the result of a previous
     *  overflow.
     **/
    override fun splitU128(e: TACExpr): Pair<TACExpr, TACExpr> {
        val twoPowerOf128 = BigInteger.TWO.pow(128).asTACExpr()
        val x =  TACExpr.BinOp.Mod(e, twoPowerOf128)
        val low  = mask(x, 64)
        val high = TACExpr.BinOp.ShiftRightLogical(x, c64)
        return low to high
    }

    override fun load(lhs: TACSymbol.Var, idx: TACSymbol, widthBytes: Short, map: TACSymbol.Var) =
        listOf(TACCmd.Simple.AssigningCmd.ByteLoad(lhs, idx, map))
}

/**
    [SbfTACBuilder] that models 64-bit SBF register arithmetic inside 256-bit TAC values, inserting modulo/mask
    operations where needed to ensure that the 64-bit SBF register semantics are respected within the 256-bit TAC
    values.

    We assume all inputs to these expressions are already properly masked / modulo-reduced to 64-bit values, and insert
    explicit assumptions to that effect when reading values from memory.
 */
class EagerMaskSbfTACBuilder(regVars: ArrayList<TACSymbol.Var>) : SbfTACBuilder(regVars) {
    private fun TACExpr.signExt64() = TACExpr.BinOp.SignExtend(7.asTACExpr, this, Tag.Bit256)
    private fun TACExpr.signExt128() = TACExpr.BinOp.SignExtend(15.asTACExpr, this, Tag.Bit256)
    private fun TACExpr.mod64() = TACExpr.BinOp.Mod(this, modz64.modulus.asTACExpr)
    private fun TACExpr.mod128() = TACExpr.BinOp.Mod(this, modz128.modulus.asTACExpr)

    override val needOverflowPromotion get() = false
    override val useTACSignedMath get() = true

    context(SbfCFGToTAC<*, *, *>)
    override fun assumeSignedIntRange(v: TACSymbol.Var, bits: Int) =
        assumeUnsignedIntRange(v, bits)

    context(SbfCFGToTAC<*, *, *>)
    override fun assumeUnsignedIntRange(v: TACSymbol.Var, bits: Int) =
        inRange(v, BigInteger.ZERO ..< BigInteger.TWO.pow(bits))

    override fun mkConst(value: BigInteger): TACSymbol.Const {
        with(modz64) {
            if (value < BigInteger.ZERO) {
                check(value.inSignedBounds) {
                    "Constant 0x${value.toString(16)} is out of range for 64-bit signed value"
                }
                return TACSymbol.Const(value.to2s(), Tag.Bit256)
            } else {
                return TACSymbol.Const(value, Tag.Bit256)
            }
        }
    }

    override fun Add(ls: List<TACExpr>) = TXF { Add(ls).mod64() }
    override fun Sub(o1: TACExpr, o2: TACExpr) = TXF { (o1 sub o2).mod64() }
    override fun Mul(ls: List<TACExpr>) = TXF { Mul(ls).mod64() }
    override fun Mul128(ls: List<TACExpr>) = TXF { Mul(ls).mod128() }
    override fun Div(o1: TACExpr, o2: TACExpr) = TXF { o1 div o2 }
    override fun Div128(o1: TACExpr, o2: TACExpr) = TXF { o1 div o2 }
    override fun SDiv(o1: TACExpr, o2: TACExpr) = TXF { (o1.signExt64() sDiv o2.signExt64()).mod64() }
    override fun SDiv128(o1: TACExpr, o2: TACExpr) = TXF { (o1.signExt128() sDiv o2.signExt128()).mod128() }
    override fun Mod(o1: TACExpr, o2: TACExpr) = TXF { o1 mod o2 }
    override fun AddNoOvf(ls: List<TACExpr>, reason: String) = TXF {
        Add(ls).annotated(CANNOT_OVERFLOW_64_REASON, reason).mod64()
    }

    override fun Gt(o1: TACExpr, o2: TACExpr) = TXF { o1 gt o2 }
    override fun Ge(o1: TACExpr, o2: TACExpr) = TXF { o1 ge o2 }
    override fun Lt(o1: TACExpr, o2: TACExpr) = TXF { o1 lt o2 }
    override fun Le(o1: TACExpr, o2: TACExpr) = TXF { o1 le o2 }
    override fun Sgt(o1: TACExpr, o2: TACExpr) = TXF { o1.signExt64() sGt o2.signExt64() }
    override fun Sge(o1: TACExpr, o2: TACExpr) = TXF { o1.signExt64() sGe o2.signExt64() }
    override fun Slt(o1: TACExpr, o2: TACExpr) = TXF { o1.signExt64() sLt o2.signExt64() }
    override fun Sle(o1: TACExpr, o2: TACExpr) = TXF { o1.signExt64() sLe o2.signExt64() }
    override fun Eq(o1: TACExpr, o2: TACExpr) = TXF { o1 eq o2 }

    override fun BWAnd(o1: TACExpr, o2: TACExpr) = TXF { o1 bwAnd o2 }
    override fun BWOr(o1: TACExpr, o2: TACExpr) = TXF { o1 bwOr o2 }
    override fun BWXOr(o1: TACExpr, o2: TACExpr) = TXF { o1 bwXor o2 }

    override fun ShiftLeft(o1: TACExpr, o2: TACExpr) = TXF { (o1 shiftL o2).mod64() }
    override fun ShiftLeft128(o1: TACExpr, o2: TACExpr) = TXF { (o1 shiftL o2).mod128() }
    override fun ShiftRightLogical(o1: TACExpr, o2: TACExpr) = TXF { o1 shiftRLog o2 }
    override fun ShiftRightArithmetical(o1: TACExpr, o2: TACExpr) = TXF { (o1.signExt64() shiftRArith o2).mod64() }
    override fun ShiftRightArithmetical128(o1: TACExpr, o2: TACExpr) = TXF { (o1.signExt128() shiftRArith o2).mod128() }
    override fun ModNeg(value: TACExpr) = TXF { (value mul mkConst(-1.toBigInteger())).mod64() }

    /** Return expression `high << 64 + low` **/
    override fun mergeU128(
        low: TACExpr.Sym,
        high: TACExpr.Sym,
        mayMaskLowBits: Boolean // Ignored; we always assume inputs are correctly sized
    ) = TACExpr.Vec.Add(TACExpr.BinOp.ShiftLeft(high, c64), low)

    /** Return expression `(w4 << 196) + (w3 << 128) + (w2 << 64) + w1` */
    override fun mergeU256(
        w1: TACExpr.Sym,
        w2: TACExpr.Sym,
        w3:TACExpr.Sym,
        w4: TACExpr.Sym,
        mayMaskLowBits: Boolean // Ignored; we always assume inputs are correctly sized
    ) = TACExpr.Vec.Add(listOf(
        TACExpr.BinOp.ShiftLeft(w4, c196),
        TACExpr.BinOp.ShiftLeft(w3, c128),
        TACExpr.BinOp.ShiftLeft(w2, c64),
        w1
    ))

    /**
     *  Return the pair (`low`,`high`) such that:
     *  ```
     *  low = e & MASK64
     *  high = e >> 64
     *  ```
     **/
    override fun splitU128(e: TACExpr): Pair<TACExpr, TACExpr> {
        val low  = mask64(e)
        val high = TACExpr.BinOp.ShiftRightLogical(e, c64)
        return low to high
    }

    override fun load(lhs: TACSymbol.Var, idx: TACSymbol, widthBytes: Short, map: TACSymbol.Var): List<TACCmd.Simple> {
        check(widthBytes.toInt() <= 8) { "Memory load width($widthBytes bytes) is larger than 64 bits" }
        return listOf(
            TACCmd.Simple.AssigningCmd.ByteLoad(lhs, idx, map),
            // Assume the value fits in 64 bits.  We use a safeMathNarrowAssuming expression for this, to give later
            // optimizations a chance to optimize this (and the load) away.  This does *not* truncate the value; it
            // merely assumes that the value already fits in 64 bits, which should be true since we don't have store
            // operations larger than 64 bits.
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs,
                TXF { safeMathNarrowAssuming(lhs.asSym(), Tag.Bit256, upperBound = mask64.s.value) }
            )
        )
    }
}

/**
 * Expression builder for `NativeInt` values: 256-bit TAC values that are used directly as
 * fake mathematical integers without any 64-bit masking or modular wrapping.
 *
 * Unlike [LazyMaskSbfTACBuilder], which models 64-bit SBF registers inside 256-bit words and must
 * carefully mask results to stay in range, this builder is used for values that are already
 * operating in the full 256-bit mathematical domain.
 * Although currently `NativeInt` is represented as a TAC bv256, but this is an implementation detail that
 * may change. This is the reason to have a dedicated builder.
 **/
class NativeIntTACBuilder(regVars: ArrayList<TACSymbol.Var>) : TACExprBase(regVars) {
    operator fun invoke(expr: NativeIntTACBuilder.() -> TACExpr): TACExpr = expr()

    fun Mul(ls: List<TACExpr>): TACExpr = TACExpr.Vec.Mul(ls)
    fun Add(ls: List<TACExpr>): TACExpr = TACExpr.Vec.Add(ls)
    fun Sub(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.Sub(o1, o2)
    fun Div(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.Div(o1, o2)
    fun SDiv(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.SDiv(o1, o2)
    fun Mod(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.Mod(o1, o2)
    fun CeilDiv(o1: TACExpr, o2: TACExpr): TACExpr =
        TACExpr.BinOp.Div(TACExpr.BinOp.Sub(TACExpr.Vec.Add(o1, o2), ONE), o2)
    fun MulDiv(o1: TACExpr, o2: TACExpr, o3: TACExpr): TACExpr =
        TACExpr.BinOp.Div(TACExpr.Vec.Mul(o1, o2), o3)
    fun MulDivCeil(o1: TACExpr, o2: TACExpr, o3: TACExpr): TACExpr =
        TACExpr.BinOp.Div(TACExpr.BinOp.Sub(TACExpr.Vec.Add(TACExpr.Vec.Mul(o1, o2), o3), ONE), o3)
    fun ModNeg(o1: TACExpr): TACExpr = TACExpr.Vec.Mul(listOf(U256_MAX, o1)) /* u256_MAX is also -1 (i.e., 0xFF...F) */

    fun Gt(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinRel.Gt(o1, o2)
    fun Lt(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinRel.Lt(o1, o2)
    fun Slt(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Slt(o1, o2)
    fun Sle(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Sle(o1, o2)
    fun Sgt(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Sgt(o1, o2)
    fun Sge(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Sge(o1, o2)
    fun Ge(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinRel.Ge(o1, o2)
    fun Le(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinRel.Le(o1, o2)
    fun Eq(o1: TACExpr, o2: TACExpr): TACExpr  = TACExpr.BinRel.Eq(o1, o2)

    fun ite(i: TACExpr, t: TACExpr, e: TACExpr): TACExpr = TACExpr.TernaryExp.Ite(i,t,e)

    // relational operator shorthands

    infix fun ToTACExpr.lt(other: ToTACExpr)  = this@NativeIntTACBuilder.Lt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.gt(other: ToTACExpr)  = this@NativeIntTACBuilder.Gt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.le(other: ToTACExpr)  = this@NativeIntTACBuilder.Le(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.ge(other: ToTACExpr)  = this@NativeIntTACBuilder.Ge(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sLt(other: ToTACExpr) = this@NativeIntTACBuilder.Slt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sGt(other: ToTACExpr) = this@NativeIntTACBuilder.Sgt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sLe(other: ToTACExpr) = this@NativeIntTACBuilder.Sle(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sGe(other: ToTACExpr) = this@NativeIntTACBuilder.Sge(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.eq(other: ToTACExpr)  = this@NativeIntTACBuilder.Eq(this.toTACExpr(), other.toTACExpr())

    // math operator shorthands

    infix fun ToTACExpr.mul(other: ToTACExpr)    = this@NativeIntTACBuilder.Mul(listOf(this.toTACExpr(), other.toTACExpr()))
    infix fun ToTACExpr.div(other: ToTACExpr)    = this@NativeIntTACBuilder.Div(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sDiv(other: ToTACExpr)   = this@NativeIntTACBuilder.SDiv(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.ceilDiv(other: ToTACExpr)= this@NativeIntTACBuilder.CeilDiv(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.add(other: ToTACExpr)    = this@NativeIntTACBuilder.Add(listOf(this.toTACExpr(), other.toTACExpr()))
    infix fun ToTACExpr.sub(other: ToTACExpr)    = this@NativeIntTACBuilder.Sub(this.toTACExpr(), other.toTACExpr())
}

