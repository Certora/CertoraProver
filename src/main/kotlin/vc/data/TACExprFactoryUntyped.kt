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

@file:JvmName("TACExprFactoryKt")
@file:Suppress("FunctionName")

package vc.data

import analysis.TACTypeChecker
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_MOD_GROUP256
import log.*
import tac.MetaKey
import tac.Tag
import utils.*
import java.io.Serializable
import java.math.BigInteger

// maybe this should just compose a TACExprFactory
// (comment by alex: probably not compose, but we'll have to merge them somehow indeed, the other factories should go
// note, the name `TACExprFactory` would be nicer, but we can only do that once the other one of that name has been purged
interface TACExprFact {

    operator fun invoke(expr: TACExprFact.() -> TACExpr): TACExpr = expr()

    fun QuantifiedFormula(isForall: Boolean, quantifiedVar: TACSymbol.Var, body: TACExpr): TACExpr =
        TACExpr.QuantifiedFormula(isForall, quantifiedVar, body)

    fun QuantifiedFormula(isForall: Boolean, quantifiedVars: List<TACSymbol.Var>, body: TACExpr): TACExpr =
        if (quantifiedVars.isEmpty()) {
            body
        } else {
            TACExpr.QuantifiedFormula(isForall, quantifiedVars, body)
        }

    fun Sym(s: TACSymbol): TACExpr

    fun Mul(vararg ls: TACExpr): TACExpr = Mul(ls.toList())
    fun Mul(op1: TACExpr, op2: TACExpr): TACExpr = Mul(listOf(op1, op2))
    fun Mul(ls: List<TACExpr>): TACExpr

    fun IntMul(ls: List<TACExpr>): TACExpr
    fun IntMul(vararg ls: TACExpr): TACExpr = IntMul(ls.toList())
    fun IntMul(op1: TACExpr, op2: TACExpr): TACExpr = IntMul(listOf(op1, op2))

    fun IntAdd(ls: List<TACExpr>): TACExpr
    fun IntAdd(vararg ls: TACExpr): TACExpr = IntAdd(ls.toList())
    fun IntAdd(op1: TACExpr, op2: TACExpr): TACExpr = IntAdd(listOf(op1, op2))

    fun Add(ls: List<TACExpr>): TACExpr
    fun Add(vararg ls: TACExpr): TACExpr = Add(ls.toList())
    fun Add(op1: TACExpr, op2: TACExpr): TACExpr = Add((listOf(op1, op2)))

    fun IntSub(o1: TACExpr, o2: TACExpr): TACExpr

    fun Sub(o1: TACExpr, o2: TACExpr): TACExpr

    fun Div(o1: TACExpr, o2: TACExpr): TACExpr
    fun SDiv(o1: TACExpr, o2: TACExpr): TACExpr

    fun IntDiv(o1: TACExpr, o2: TACExpr): TACExpr

    fun Mod(o1: TACExpr, o2: TACExpr): TACExpr
    fun SMod(o1: TACExpr, o2: TACExpr): TACExpr
    fun IntMod(o1: TACExpr, o2: TACExpr): TACExpr

    fun Exponent(o1: TACExpr, o2: TACExpr): TACExpr

    fun IntExponent(o1: TACExpr, o2: TACExpr): TACExpr

    fun Gt(o1: TACExpr, o2: TACExpr): TACExpr

    fun Lt(o1: TACExpr, o2: TACExpr): TACExpr

    fun Slt(o1: TACExpr, o2: TACExpr) : TACExpr

    fun Sle(o1: TACExpr, o2: TACExpr) : TACExpr

    fun Sgt(o1: TACExpr, o2: TACExpr) : TACExpr

    fun Sge(o1: TACExpr, o2: TACExpr) : TACExpr

    fun Ge(o1: TACExpr, o2: TACExpr): TACExpr

    fun Le(o1: TACExpr, o2: TACExpr): TACExpr

    fun Eq(o1: TACExpr, o2: TACExpr): TACExpr

    fun BWAnd(o1: TACExpr, o2: TACExpr): TACExpr

    fun BWOr(o1: TACExpr, o2: TACExpr): TACExpr

    fun BWXOr(o1: TACExpr, o2: TACExpr): TACExpr

    fun BWNot(o: TACExpr): TACExpr

    fun ShiftLeft(o1: TACExpr, o2: TACExpr): TACExpr

    fun ShiftRightLogical(o1: TACExpr, o2: TACExpr): TACExpr

    fun ShiftRightArithmetical(o1: TACExpr, o2: TACExpr): TACExpr

    fun Apply(f: TACExpr.TACFunctionSym, ops: List<TACExpr>): TACExpr

    fun Select(b: TACExpr, vararg l: TACExpr): TACExpr

    fun Store(b: TACExpr, vararg locs: TACExpr, v: TACExpr): TACExpr
    fun Store(b: TACExpr, locs: List<TACExpr>, v: TACExpr): TACExpr

    fun LongStore(
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr
    ): TACExpr

    fun MapDefinition(defParams: List<TACExpr.Sym.Var>, definition: TACExpr, tag: Tag.Map): TACExpr.MapDefinition

    fun Unconstrained(type: Tag): TACExpr.Unconstrained

    fun SimpleHash(length: TACExpr, args: List<TACExpr>, hashFamily: HashFamily = HashFamily.Keccack): TACExpr

    fun LAnd(ls: List<TACExpr>): TACExpr
    fun LAnd(vararg ls: TACExpr): TACExpr = LAnd(ls.toList())
    fun LAnd(op1: TACExpr, op2: TACExpr): TACExpr = LAnd(listOf(op1, op2))
    fun LAndIfNeeded(ls : List<TACExpr>) = when(ls.size) {
        0 -> True
        1 -> ls.first()
        else -> LAnd(ls)
    }

    fun LOr(ls: List<TACExpr>): TACExpr
    fun LOr(vararg ls: TACExpr): TACExpr = LOr(ls.toList())
    fun LOr(op1: TACExpr, op2: TACExpr): TACExpr = LOr(listOf(op1, op2))
    fun LOrIfNeeded(ls : List<TACExpr>) = when(ls.size) {
        0 -> False
        1 -> ls.first()
        else -> LOr(ls)
    }


    fun LNot(o: TACExpr): TACExpr

    fun Ite(i: TACExpr, t: TACExpr, e: TACExpr): TACExpr

    fun MulMod(a: TACExpr, b: TACExpr, n: TACExpr): TACExpr
    fun AddMod(a: TACExpr, b: TACExpr, n: TACExpr): TACExpr

    fun StructAccess(struct: TACExpr, fieldName: String): TACExpr

    fun StructConstant(fieldNameToValue: Map<String, TACExpr>): TACExpr

    fun SignExtend(o1: TACExpr, o2: TACExpr): TACExpr

    // logical operator shorthands

    infix fun ToTACExpr.and(other: ToTACExpr) = this@TACExprFact.LAnd(this.toTACExpr(), other.toTACExpr())
    fun and(vararg args: TACExpr) = LAnd(args.toList())

    infix fun ToTACExpr.or(other: ToTACExpr) = this@TACExprFact.LOr(this.toTACExpr(), other.toTACExpr())
    fun or(vararg args: TACExpr) = LOr(args.toList())

    fun not(exp: ToTACExpr) = this@TACExprFact.LNot(exp.toTACExpr())

    fun ite(i: ToTACExpr, t: ToTACExpr, e: ToTACExpr) = Ite(i.toTACExpr(), t.toTACExpr(), e.toTACExpr())

    // relational operator shorthands

    infix fun ToTACExpr.lt(other: ToTACExpr) = this@TACExprFact.Lt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.gt(other: ToTACExpr) = this@TACExprFact.Gt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.le(other: ToTACExpr) = this@TACExprFact.Le(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.ge(other: ToTACExpr) = this@TACExprFact.Ge(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sLt(other: ToTACExpr) = this@TACExprFact.Slt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sGt(other: ToTACExpr) = this@TACExprFact.Sgt(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sLe(other: ToTACExpr) = this@TACExprFact.Sle(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sGe(other: ToTACExpr) = this@TACExprFact.Sge(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.eq(other: ToTACExpr) = this@TACExprFact.Eq(this.toTACExpr(), other.toTACExpr())

    infix fun ToTACExpr.neq(other: ToTACExpr) =
        this@TACExprFact.LNot(this@TACExprFact.Eq(this.toTACExpr(), other.toTACExpr()))

    // math operator shorthands

    infix fun ToTACExpr.mul(other: ToTACExpr) = this@TACExprFact.Mul(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.intMul(other: ToTACExpr) = this@TACExprFact.IntMul(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.div(other: ToTACExpr) = this@TACExprFact.Div(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sDiv(other: ToTACExpr) = this@TACExprFact.SDiv(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.intDiv(other: ToTACExpr) = this@TACExprFact.IntDiv(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.add(other: ToTACExpr) = this@TACExprFact.Add(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.intAdd(other: ToTACExpr) = this@TACExprFact.IntAdd(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sub(other: ToTACExpr) = this@TACExprFact.Sub(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.intSub(other: ToTACExpr) = this@TACExprFact.IntSub(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.mod(other: ToTACExpr) = this@TACExprFact.Mod(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.sMod(other: ToTACExpr) = this@TACExprFact.SMod(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.intMod(other: ToTACExpr) = this@TACExprFact.IntMod(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.pow(other: ToTACExpr) = this@TACExprFact.Exponent(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.intPow(other: ToTACExpr) = this@TACExprFact.IntExponent(this.toTACExpr(), other.toTACExpr())

    // bitwise operator shorthands

    infix fun ToTACExpr.bwAnd(other: ToTACExpr) = this@TACExprFact.BWAnd(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.bwOr(other: ToTACExpr) = this@TACExprFact.BWOr(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.bwXor(other: ToTACExpr) = this@TACExprFact.BWXOr(this.toTACExpr(), other.toTACExpr())
    fun bwNot(exp:TACExpr) = this@TACExprFact.BWNot(exp)
    infix fun ToTACExpr.shiftRLog(other: ToTACExpr) = this@TACExprFact.ShiftRightLogical(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.shiftRArith(other: ToTACExpr) = this@TACExprFact.ShiftRightArithmetical(this.toTACExpr(), other.toTACExpr())
    infix fun ToTACExpr.shiftL(other: ToTACExpr) = this@TACExprFact.ShiftLeft(this.toTACExpr(), other.toTACExpr())

    // other shorthands
    fun unconstrained(tag: Tag) = Unconstrained(tag)

    fun select(base: TACExpr, vararg loc: TACExpr) = Select(base, *loc)

    operator fun ToTACExpr.get(other: ToTACExpr) = Select(this.toTACExpr(), other.toTACExpr())


    @Suppress("Unused")
    fun quant(isForall: Boolean, vararg qVars: TACSymbol.Var, body: () -> TACExpr) =
        QuantifiedFormula(isForall, qVars.toList(), body())

    @Suppress("Unused")
    fun quant(isForall: Boolean, qVars: List<TACSymbol.Var>, body: () -> TACExpr) =
        QuantifiedFormula(isForall, qVars, body())


    fun forall(vararg qVars: TACSymbol.Var, body: () -> TACExpr) =
        QuantifiedFormula(true, qVars.toList(), body())

    fun forall(qVars: List<TACSymbol.Var>, body: () -> TACExpr) =
        QuantifiedFormula(true, qVars, body())

    fun exists(vararg qVars: TACSymbol.Var, body: () -> TACExpr) =
        QuantifiedFormula(false, qVars.toList(), body())

    fun exists(qVars: List<TACSymbol.Var>, body: () -> TACExpr) =
        QuantifiedFormula(false, qVars, body())

    fun number(n: BigInteger, tag: Tag = Tag.Bit256) = this@TACExprFact.Sym(TACSymbol.Const(n, tag))
    fun number(n: Long, tag: Tag = Tag.Bit256) = number(BigInteger.valueOf(n), tag)
    fun number(n: Int, tag: Tag = Tag.Bit256) = number(BigInteger.valueOf(n.toLong()), tag)

    fun intNum(n : BigInteger) = number(n, Tag.Int)
    fun intNum(n : Long) = number(n, Tag.Int)
    fun intNum(n : Int) = number(n, Tag.Int)

    fun bool(b: Boolean) = this@TACExprFact.Sym(TACSymbol.lift(b))

    val True get() = this@TACExprFact.bool(true) as TACExpr.Sym.Const
    val False get() = this@TACExprFact.bool(false) as TACExpr.Sym.Const
    val One get() = this@TACExprFact.number(1) as TACExpr.Sym.Const
    val Zero get() = this@TACExprFact.number(0) as TACExpr.Sym.Const
    val EVM_MOD_GROUP get() = this@TACExprFact.number(EVM_MOD_GROUP256, Tag.Int) as TACExpr.Sym.Const


    fun mapDef(params: Map<String, Tag>, body: (List<TACExpr.Sym.Var>) -> TACExpr, tag: Tag.Map): TACExpr.MapDefinition =
        params.map {(name, type) ->
            TACSymbol.Factory.getFreshAuxVar(
                TACSymbol.Factory.AuxVarPurpose.MAP_DEF_INSERTER,
                TACSymbol.Var(name, type)
            ).asSym()
        }.let { paramSyms ->
            mapDef(paramSyms, body(paramSyms), tag)
        }

    fun mapDef(params: List<TACExpr.Sym.Var>, body: TACExpr, tag: Tag.Map): TACExpr.MapDefinition =
        TACExpr.MapDefinition(params, body, tag)

    /** replaces the older TACExpr.ResetStore */
    fun resetStore(tag: Tag.Map) = mapDef(mapOf("i" to Tag.Bit256), { 0.asTACExpr }, tag)

    fun applyBIF(t: TACBuiltInFunction, vararg ops: TACExpr) =
        TACExpr.Apply(
            TACExpr.TACFunctionSym.BuiltIn(t),
            ops.toList(),
            t.returnSort
        )

    fun applyBIF(t: TACBuiltInFunction, unconditionallySafe: Boolean, vararg ops: TACExpr) =
        TACExpr.Apply(
            TACExpr.TACFunctionSym.BuiltIn(t),
            ops.toList(),
            t.returnSort,
            unconditionallySafe
        )

    fun safeMathNarrow(op: TACExpr, to: Tag.Bits, unconditionallySafe: Boolean = false) =
        applyBIF(TACBuiltInFunction.SafeMathNarrow(to), unconditionallySafe, op)

    fun safeMathPromotion(op: TACExpr) =
        applyBIF(TACBuiltInFunction.SafeMathPromotion(op.tag as Tag.Bits), op)

    fun signedPromoteTo(i: Int, op: TACExpr) = signedPromoteTo(Tag.Bits(i), op)
    fun signedPromoteTo(target: Tag.Bits, op: TACExpr) =
        applyBIF(TACBuiltInFunction.SignedPromotion(op.tag as Tag.Bits, target), op)

    fun unsignedPromoteTo(i: Int, op: TACExpr) = unsignedPromoteTo(Tag.Bits(i), op)
    fun unsignedPromoteTo(target: Tag.Bits, op: TACExpr) =
        applyBIF(TACBuiltInFunction.UnsignedPromotion(op.tag as Tag.Bits, target), op)

    fun safeSignedNarrowTo(i: Int, op: TACExpr) = safeSignedNarrowTo(Tag.Bits(i), op)
    fun safeSignedNarrowTo(target: Tag.Bits, op: TACExpr) =
        applyBIF(TACBuiltInFunction.SafeSignedNarrow(op.tag as Tag.Bits, target), op)

    fun safeUnsignedNarrowTo(i: Int, op: TACExpr) = safeUnsignedNarrowTo(Tag.Bits(i), op)
    fun safeUnsignedNarrowTo(target: Tag.Bits, op: TACExpr) =
        applyBIF(TACBuiltInFunction.SafeUnsignedNarrow(op.tag as Tag.Bits, target), op)

    fun twosWrap(op : TACExpr, tag: Tag.Bits = Tag.Bit256) =
        applyBIF(TACBuiltInFunction.TwosComplement.Wrap(tag), op)

    fun twosUnwrap(op : TACExpr, tag: Tag.Bits = Tag.Bit256) =
        applyBIF(TACBuiltInFunction.TwosComplement.Unwrap(tag), op)

    fun noAddOverflow(op1: TACExpr, op2: TACExpr, tag: Tag.Bits = Tag.Bit256) =
        applyBIF(TACBuiltInFunction.NoAddOverflowCheck(tag), op1, op2)

    fun noMulOverflow(op1: TACExpr, op2: TACExpr, tag: Tag.Bits = Tag.Bit256) =
        applyBIF(TACBuiltInFunction.NoMulOverflowCheck(tag), op1, op2)

    fun noSMulOverAndUnderflow(op1: TACExpr, op2: TACExpr, tag: Tag.Bits = Tag.Bit256) =
        applyBIF(TACBuiltInFunction.NoSMulOverAndUnderflowCheck(tag), op1, op2)

    fun noSAddOverAndUnderflow(op1: TACExpr, op2: TACExpr, tag: Tag.Bits = Tag.Bit256) =
        applyBIF(TACBuiltInFunction.NoSAddOverAndUnderflowCheck(tag), op1, op2)

    fun noSSubOverAndUnderflow(op1: TACExpr, op2: TACExpr, tag: Tag.Bits = Tag.Bit256) =
        applyBIF(TACBuiltInFunction.NoSSubOverAndUnderflowCheck(tag), op1, op2)

    fun fromSkey(op: TACExpr) =
        applyBIF(TACBuiltInFunction.Hash.FromSkey, op)

    fun <@Treapable T : Serializable> AnnotationExp(o: TACExpr, k: MetaKey<T>, v: T) =
        TACExpr.AnnotationExp(o, k, v)

}

object TACExprFactUntyped : TACExprFact {
    override fun QuantifiedFormula(isForall: Boolean, quantifiedVar: TACSymbol.Var, body: TACExpr): TACExpr =
        TACExpr.QuantifiedFormula(isForall, quantifiedVar, body)

    override fun QuantifiedFormula(isForall: Boolean, quantifiedVars: List<TACSymbol.Var>, body: TACExpr): TACExpr =
         if (quantifiedVars.isEmpty()) {
             body
         } else {
             TACExpr.QuantifiedFormula(isForall, quantifiedVars, body)
         }

    override fun Sym(s: TACSymbol): TACExpr = TACExpr.Sym(s)

    override fun IntMul(ls: List<TACExpr>): TACExpr = TACExpr.Vec.IntMul(ls)

    override fun Mul(ls: List<TACExpr>): TACExpr = TACExpr.Vec.Mul(ls)

    override fun IntAdd(ls: List<TACExpr>): TACExpr = TACExpr.Vec.IntAdd(ls)

    override fun Add(ls: List<TACExpr>): TACExpr = TACExpr.Vec.Add(ls)

    override fun IntSub(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.IntSub(o1, o2)

    override fun Sub(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.Sub(o1, o2)

    override fun Div(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.Div(o1, o2)
    override fun SDiv(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.SDiv(o1, o2)

    override fun IntDiv(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.IntDiv(o1, o2)

    override fun Mod(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.Mod(o1, o2)

    override fun SMod(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.SMod(o1, o2)

    override fun IntMod(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.IntMod(o1, o2)

    override fun Exponent(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.Exponent(o1, o2)

    override fun IntExponent(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.IntExponent(o1, o2)

    override fun Gt(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Gt(o1, o2)

    override fun Lt(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Lt(o1, o2)

    override fun Slt(o1: TACExpr, o2: TACExpr) : TACExpr = TACExpr.BinRel.Slt(o1, o2)

    override fun Sle(o1: TACExpr, o2: TACExpr) : TACExpr = TACExpr.BinRel.Sle(o1, o2)

    override fun Sgt(o1: TACExpr, o2: TACExpr) : TACExpr = TACExpr.BinRel.Sgt(o1, o2)

    override fun Sge(o1: TACExpr, o2: TACExpr) : TACExpr = TACExpr.BinRel.Sge(o1, o2)

    override fun Ge(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Ge(o1, o2)

    override fun Le(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Le(o1, o2)

    override fun Eq(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinRel.Eq(o1, o2)

    override fun BWAnd(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.BWAnd(o1, o2)

    override fun BWOr(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.BWOr(o1, o2)

    override fun BWXOr(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.BWXOr(o1, o2)

    override fun BWNot(o: TACExpr): TACExpr = TACExpr.UnaryExp.BWNot(o)

    override fun ShiftLeft(o1: TACExpr, o2: TACExpr): TACExpr = TACExpr.BinOp.ShiftLeft(o1, o2)

    override fun ShiftRightLogical(o1: TACExpr, o2: TACExpr): TACExpr =
         TACExpr.BinOp.ShiftRightLogical(o1, o2)

    override fun ShiftRightArithmetical(o1: TACExpr, o2: TACExpr): TACExpr =
         TACExpr.BinOp.ShiftRightArithmetical(o1, o2)

    override fun Apply(f: TACExpr.TACFunctionSym, ops: List<TACExpr>): TACExpr = TACExpr.Apply(f, ops, null)

    override fun Select(b: TACExpr, vararg l: TACExpr): TACExpr =
        if (l.size == 1) {
            TACExpr.Select(b, l[0])
        } else {
            TACExpr.Select.buildMultiDimSelect(b, l.toList())
        }

    override fun Store(b: TACExpr, vararg locs: TACExpr, v: TACExpr): TACExpr =
        if (locs.size == 1) {
            TACExpr.Store(b, locs.single(), v)
        } else {
            TACExpr.MultiDimStore(b, locs.toList(), v)
        }
    override fun Store(b: TACExpr, locs: List<TACExpr>, v: TACExpr): TACExpr = Store(b, *locs.toTypedArray(), v = v)

    override fun LongStore(
         dstMap: TACExpr,
         dstOffset: TACExpr,
         srcMap: TACExpr,
         srcOffset: TACExpr,
         length: TACExpr
     ): TACExpr =
         TACExpr.LongStore(dstMap, dstOffset, srcMap, srcOffset, length)

    override fun MapDefinition(
        defParams: List<TACExpr.Sym.Var>,
        definition: TACExpr,
        tag: Tag.Map
    ) = TACExpr.MapDefinition(defParams, definition, tag)

    override fun Unconstrained(type: Tag) = TACExpr.Unconstrained(type)

    override fun SimpleHash(length: TACExpr, args: List<TACExpr>, hashFamily: HashFamily): TACExpr =
        TACExpr.SimpleHash(length, args, hashFamily)

    override fun LAnd(ls: List<TACExpr>): TACExpr = TACExpr.BinBoolOp.LAnd(ls)

    override fun LOr(ls: List<TACExpr>): TACExpr = TACExpr.BinBoolOp.LOr(ls)

    override fun LNot(o: TACExpr): TACExpr = TACExpr.UnaryExp.LNot(o)

    override fun Ite(i: TACExpr, t: TACExpr, e: TACExpr): TACExpr = TACExpr.TernaryExp.Ite(i, t, e)

    override fun MulMod(a: TACExpr, b: TACExpr, n: TACExpr): TACExpr = TACExpr.TernaryExp.MulMod(a, b, n)
    override fun AddMod(a: TACExpr, b: TACExpr, n: TACExpr): TACExpr = TACExpr.TernaryExp.AddMod(a, b, n)

    override fun StructAccess(struct: TACExpr, fieldName: String): TACExpr = TACExpr.StructAccess(struct, fieldName)

    override fun StructConstant(fieldNameToValue: Map<String, TACExpr>): TACExpr = TACExpr.StructConstant(fieldNameToValue)

    override fun SignExtend(o1: TACExpr, o2: TACExpr): TACExpr =
        TACExpr.BinOp.SignExtend(o1, o2)
}

class TACExprFactTypeChecked(
    val symbolTable: TACSymbolTable,
    private val baseFactory: TACExprFactUntyped = TACExprFactUntyped
) : TACExprFact {
    private val typeChecker: TACTypeChecker = TACTypeChecker(symbolTable)
    fun typeCheck(e: TACExpr) = typeChecker.typeCheck(e).resultOrError(e)

    /** Build expression created by the given lambda and typecheck it -- not that this uses the non-typing
     * baseFactory for the building, since `typeCheck` is recursive, and otherwise we'd run it unnecessarily on every
     * subexpression. * */
    override operator fun invoke(expr: TACExprFact.() -> TACExpr): TACExpr = typeCheck(baseFactory.expr())

    fun CollectingResult<TACExpr, String>.resultOrError(triedToType: TACExpr) =
        this.resultOrThrow { errors ->
            errors.forEach { error ->
                Logger.alwaysError(error)
            }
            CertoraInternalException(
                CertoraInternalErrorType.INTERNAL_TAC_TYPE_CHECKER,
                "TAC Expression Typed Factory failed to type expression $triedToType"
            )
        }


    override fun Sym(s: TACSymbol): TACExpr = typeCheck(baseFactory.Sym(s))

    override fun Mul(ls: List<TACExpr>): TACExpr = typeCheck(baseFactory.Mul(ls))

    override fun IntMul(ls: List<TACExpr>): TACExpr = typeCheck(baseFactory.IntMul(ls))

    override fun IntAdd(ls: List<TACExpr>): TACExpr = typeCheck(baseFactory.IntAdd(ls))

    override fun Add(ls: List<TACExpr>): TACExpr = typeCheck(baseFactory.Add(ls))

    override fun IntSub(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.IntSub(o1, o2))

    override fun Sub(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Sub(o1, o2))

    override fun Div(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Div(o1, o2))
    override fun SDiv(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.SDiv(o1, o2))
    override fun IntDiv(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.IntDiv(o1, o2))

    override fun Mod(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Mod(o1, o2))
    override fun SMod(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.SMod(o1, o2))
    override fun IntMod(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.IntMod(o1, o2))

    override fun Exponent(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Exponent(o1, o2))

    override fun IntExponent(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.IntExponent(o1, o2))

    override fun Gt(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Gt(o1, o2))
    override fun Lt(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Lt(o1, o2))
    override fun Slt(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Slt(o1, o2))
    override fun Sle(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Sle(o1, o2))
    override fun Sgt(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Sgt(o1, o2))
    override fun Sge(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Sge(o1, o2))
    override fun Ge(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Ge(o1, o2))
    override fun Le(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Le(o1, o2))
    override fun Eq(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.Eq(o1, o2))

    override fun BWAnd(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.BWAnd(o1, o2))
    override fun BWOr(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.BWOr(o1, o2))
    override fun BWXOr(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.BWXOr(o1, o2))
    override fun BWNot(o: TACExpr): TACExpr = typeCheck(baseFactory.BWNot(o))

    override fun ShiftLeft(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.ShiftLeft(o1, o2))
    override fun ShiftRightLogical(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.ShiftRightLogical(o1, o2))
    override fun ShiftRightArithmetical(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.ShiftRightArithmetical(o1, o2))

    override fun Apply(f: TACExpr.TACFunctionSym, ops: List<TACExpr>): TACExpr = typeCheck(baseFactory.Apply(f, ops))

    override fun Select(b: TACExpr, vararg l: TACExpr): TACExpr = typeCheck(baseFactory.Select(b, *l))

    override fun Store(b: TACExpr, vararg locs: TACExpr, v: TACExpr): TACExpr = typeCheck(baseFactory.Store(b, *locs, v = v))
    override fun Store(b: TACExpr, locs: List<TACExpr>, v: TACExpr): TACExpr = typeCheck(baseFactory.Store(b, locs, v = v))
    override fun LongStore(
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr
    ): TACExpr = typeCheck(baseFactory.LongStore(dstMap, dstOffset, srcMap, srcOffset, length))

    override fun MapDefinition(
        defParams: List<TACExpr.Sym.Var>,
        definition: TACExpr,
        tag: Tag.Map
    ): TACExpr.MapDefinition {
        TODO("Not yet implemented")
    }

    override fun Unconstrained(type: Tag): TACExpr.Unconstrained {
        TODO("Not yet implemented")
    }

    override fun SimpleHash(length: TACExpr, args: List<TACExpr>, hashFamily: HashFamily): TACExpr =
        typeCheck(baseFactory.SimpleHash(length, args, hashFamily))

    override fun LAnd(ls: List<TACExpr>): TACExpr = typeCheck(baseFactory.LAnd(ls))
    override fun LOr(ls: List<TACExpr>): TACExpr = typeCheck(baseFactory.LOr(ls))
    override fun LNot(o: TACExpr): TACExpr = typeCheck(baseFactory.LNot(o))

    override fun Ite(i: TACExpr, t: TACExpr, e: TACExpr): TACExpr = typeCheck(baseFactory.Ite(i, t, e))

    override fun MulMod(a: TACExpr, b: TACExpr, n: TACExpr): TACExpr = typeCheck(baseFactory.MulMod(a, b, n))
    override fun AddMod(a: TACExpr, b: TACExpr, n: TACExpr): TACExpr = typeCheck(baseFactory.AddMod(a, b, n))

    override fun StructAccess(struct: TACExpr, fieldName: String): TACExpr = typeCheck(baseFactory.StructAccess(struct, fieldName))
    override fun StructConstant(fieldNameToValue: Map<String, TACExpr>): TACExpr =
        typeCheck(baseFactory.StructConstant(fieldNameToValue))

    override fun SignExtend(o1: TACExpr, o2: TACExpr): TACExpr = typeCheck(baseFactory.SignExtend(o1, o2))
}

val TACExprFactTypeCheckedOnlyPrimitives: TACExprFactTypeChecked by lazy { TACExprFactTypeChecked(TACSymbolTable.empty()) }
