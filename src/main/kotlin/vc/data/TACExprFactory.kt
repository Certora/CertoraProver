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

package vc.data.tacexprutil

import com.certora.collect.*
import tac.MetaKey
import tac.Tag
import utils.*
import vc.data.HashFamily
import vc.data.TACExpr
import vc.data.TACSymbol
import java.io.Serializable
import java.math.BigInteger

/**
 * Q (alex): should this be deprecated/removed in favour of [TACExprFactTypeChecked]/[TACExprFactoryUntyped]
 *          I suspect it's better to use those instead of this (and if we need simplifications from the subclasses of
 *          this, migrate them.
 */
open class TACExprFactory {

    fun QuantifiedFormula(isForall: Boolean, quantifiedVar: TACSymbol.Var, body: TACExpr, tag: Tag?): TACExpr =
        TACExpr.QuantifiedFormula(isForall, quantifiedVar, body, tag)

    open operator fun invoke(expr: TACExprFactory.() -> TACExpr): TACExpr = expr()

    open fun QuantifiedFormula(isForall: Boolean, quantifiedVars: List<TACSymbol.Var>, body: TACExpr, tag: Tag?): TACExpr =
        TACExpr.QuantifiedFormula(isForall, quantifiedVars, body, tag)

    open fun Sym(s: TACSymbol): TACExpr = TACExpr.Sym(s)

    open fun Mul(ls: List<TACExpr>, tag: Tag.Bits?): TACExpr = TACExpr.Vec.Mul(ls, tag)
    open fun Mul(op1: TACExpr, op2: TACExpr, tag: Tag.Bits?): TACExpr = TACExpr.Vec.Mul(op1, op2, tag)

    open fun IntMul(ls: List<TACExpr>, tag: Tag.Int?): TACExpr = TACExpr.Vec.IntMul(ls, tag)
    open fun IntMul(op1: TACExpr, op2: TACExpr, tag: Tag.Int?): TACExpr = TACExpr.Vec.IntMul(op1, op2, tag)

    open fun IntAdd(ls: List<TACExpr>, tag: Tag.Int?): TACExpr = TACExpr.Vec.IntAdd(ls, tag)
    open fun IntAdd(op1: TACExpr, op2: TACExpr, tag: Tag.Int?): TACExpr = TACExpr.Vec.IntAdd(op1, op2, tag)

    open fun Add(ls: List<TACExpr>, tag: Tag.Bits?): TACExpr = TACExpr.Vec.Add(ls, tag)
    open fun Add(op1: TACExpr, op2: TACExpr, tag: Tag.Bits?): TACExpr = TACExpr.Vec.Add(op1, op2, tag)

    open fun IntSub(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr = TACExpr.BinOp.IntSub(o1, o2, tag)

    open fun Sub(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr = TACExpr.BinOp.Sub(o1, o2, tag)

    open fun Div(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr = TACExpr.BinOp.Div(o1, o2, tag)
    open fun SDiv(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr = TACExpr.BinOp.SDiv(o1, o2, tag)
    open fun IntDiv(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr = TACExpr.BinOp.IntDiv(o1, o2, tag)

    open fun Mod(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr = TACExpr.BinOp.Mod(o1, o2, tag)
    open fun SMod(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr = TACExpr.BinOp.SMod(o1, o2, tag)
    open fun IntMod(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr = TACExpr.BinOp.IntMod(o1, o2, tag)

    open fun Exponent(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr = TACExpr.BinOp.Exponent(o1, o2, tag)

    open fun IntExponent(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr = TACExpr.BinOp.IntExponent(o1, o2, tag)

    open fun Gt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr = TACExpr.BinRel.Gt(o1, o2, tag)

    open fun Lt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr = TACExpr.BinRel.Lt(o1, o2, tag)

    open fun Slt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?) : TACExpr = TACExpr.BinRel.Slt(o1, o2, tag)

    open fun Sle(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?) : TACExpr = TACExpr.BinRel.Sle(o1, o2, tag)

    open fun Sgt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?) : TACExpr = TACExpr.BinRel.Sgt(o1, o2, tag)

    open fun Sge(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?) : TACExpr = TACExpr.BinRel.Sge(o1, o2, tag)

    open fun Ge(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr = TACExpr.BinRel.Ge(o1, o2, tag)

    open fun Le(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr = TACExpr.BinRel.Le(o1, o2, tag)

    open fun Eq(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr = TACExpr.BinRel.Eq(o1, o2, tag)

    open fun BWAnd(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr = TACExpr.BinOp.BWAnd(o1, o2, tag)

    open fun BWOr(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr = TACExpr.BinOp.BWOr(o1, o2, tag)

    open fun BWXOr(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr = TACExpr.BinOp.BWXOr(o1, o2, tag)

    open fun BWNot(o: TACExpr, tag: Tag?): TACExpr = TACExpr.UnaryExp.BWNot(o, tag)

    open fun ShiftLeft(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr = TACExpr.BinOp.ShiftLeft(o1, o2, tag)

    open fun ShiftRightLogical(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExpr.BinOp.ShiftRightLogical(
            o1,
            o2,
            tag
        )

    open fun ShiftRightArithmetical(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExpr.BinOp.ShiftRightArithmetical(
            o1,
            o2,
            tag
        )

    open fun Apply(f: TACExpr.TACFunctionSym, ops: List<TACExpr>, tag: Tag?): TACExpr = TACExpr.Apply(f, ops, tag)

    open fun Select(b: TACExpr, l: TACExpr, tag: Tag?): TACExpr = TACExpr.Select(b, l, tag)

    open fun Store(b: TACExpr, l: TACExpr, v: TACExpr, tag: Tag?): TACExpr = TACExpr.Store(b, l, v,
        tag
    )

    open fun MultiDimStore(b: TACExpr, locs: List<TACExpr>, v: TACExpr, tag: Tag?): TACExpr = TACExpr.MultiDimStore(b, locs, v,
        tag
    )

    open fun LongStore(
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr,
        tag: Tag?
    ): TACExpr =
        TACExpr.LongStore(dstMap, dstOffset, srcMap, srcOffset, length, tag)

    open fun MapDefinition(
        defParams: List<TACExpr.Sym.Var>,
        definition: TACExpr,
        tag: Tag.Map
    ) =
        TACExpr.MapDefinition(defParams, definition, tag)

    open fun Unconstrained(tag: Tag) = TACExpr.Unconstrained(tag)


    open fun SimpleHash(length: TACExpr, args: List<TACExpr>, hashFamily: HashFamily, tag: Tag?): TACExpr =
        TACExpr.SimpleHash(length, args, hashFamily)

    open fun LAnd(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr = TACExpr.BinBoolOp.LAnd(ls, tag)
    fun LAnd(vararg ls: TACExpr, tag: Tag.Bool?): TACExpr = LAnd(ls.toList(), tag)

    fun LAnd(op1: TACExpr, op2: TACExpr, tag: Tag.Bool?): TACExpr = LAnd(listOf(op1, op2), tag)

    open fun LOr(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr = TACExpr.BinBoolOp.LOr(ls, tag)
    fun LOr(vararg ls: TACExpr, tag: Tag.Bool?): TACExpr = LOr(ls.toList(), tag)
    fun LOr(op1: TACExpr, op2: TACExpr, tag: Tag.Bool?): TACExpr = LOr(listOf(op1, op2), tag)

    open fun LNot(o: TACExpr, tag: Tag.Bool?): TACExpr = TACExpr.UnaryExp.LNot(o, tag)

    open fun Ite(i: TACExpr, t: TACExpr, e: TACExpr, tag: Tag?) = TACExpr.TernaryExp.Ite(i, t, e, tag)

    open fun MulMod(a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): TACExpr = TACExpr.TernaryExp.MulMod(a, b, n, tag)

    open fun AddMod(a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): TACExpr = TACExpr.TernaryExp.AddMod(a, b, n, tag)

    open fun StructAccess(struct: TACExpr, fieldName: String, tag: Tag?): TACExpr = TACExpr.StructAccess(struct, fieldName, tag)

    open fun StructConstant(fieldNameToValue: Map<String, TACExpr>, tag: Tag?): TACExpr = TACExpr.StructConstant(fieldNameToValue, tag)

    open fun <@Treapable T : Serializable> AnnotationExp(o: TACExpr, k: MetaKey<T>, v: T): TACExpr = TACExpr.AnnotationExp(o, k, v)

    fun rebuildExpr(e: TACExpr): TACExpr = when (e) {
        is TACExpr.QuantifiedFormula -> this.QuantifiedFormula(e.isForall, e.quantifiedVars, e.body, e.tag)
        is TACExpr.Sym -> this.Sym(e.s)
        is TACExpr.Vec.Mul -> this.Mul(e.ls.map { this.rebuildExpr(it) }, e.tag)
        is TACExpr.Vec.IntMul -> this.IntMul(e.ls.map { this.rebuildExpr(it) }, e.tag)
        is TACExpr.Vec.IntAdd -> this.IntAdd(e.ls.map { this.rebuildExpr(it) }, e.tag)
        is TACExpr.Vec.Add -> this.Add(e.ls.map { this.rebuildExpr(it) }, e.tag)
        is TACExpr.SimpleHash -> this.SimpleHash(rebuildExpr(e.length), e.args.map(::rebuildExpr), e.hashFamily, e.tag)
        is TACExpr.BinOp.IntSub -> this.IntSub(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.Sub -> this.Sub(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.Div -> this.Div(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.SDiv -> this.SDiv(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.IntDiv -> this.IntDiv(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.Mod -> this.Mod(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.SMod -> this.SMod(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.IntMod -> this.IntMod(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.Exponent -> this.Exponent(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.IntExponent -> this.IntExponent(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Gt -> this.Gt(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Lt -> this.Lt(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Slt -> this.Slt(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Sle -> this.Sle(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Sgt -> this.Sgt(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Sge -> this.Sge(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Ge -> this.Ge(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Le -> this.Le(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinRel.Eq -> this.Eq(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.BWAnd -> this.BWAnd(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.BWOr -> this.BWOr(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.BWXOr -> this.BWXOr(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)

        is TACExpr.UnaryExp.BWNot -> this.BWNot(rebuildExpr(e.o), e.tag)
        is TACExpr.BinOp.ShiftLeft -> this.ShiftLeft(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.ShiftRightLogical -> this.ShiftRightLogical(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.BinOp.ShiftRightArithmetical -> this.ShiftRightArithmetical(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.Apply -> this.Apply(e.f, e.ops.map { rebuildExpr(it) }, e.tag)
        is TACExpr.Select -> this.Select(rebuildExpr(e.base), rebuildExpr(e.loc), e.tag)
        is TACExpr.Store -> this.Store(rebuildExpr(e.base), rebuildExpr(e.loc), rebuildExpr(e.value), e.tag)
        is TACExpr.MultiDimStore -> this.MultiDimStore(
            rebuildExpr(e.base),
            e.locs.map { rebuildExpr(it) },
            rebuildExpr(e.value),
            e.tag
        )
        is TACExpr.MapDefinition -> this.MapDefinition(
            e.defParams.map { rebuildExpr(it) as TACExpr.Sym.Var },
            rebuildExpr(e.definition),
            e.tag
        )
        is TACExpr.LongStore -> this.LongStore(
            rebuildExpr(e.dstMap),
            rebuildExpr(e.dstOffset),
            rebuildExpr(e.srcMap),
            rebuildExpr(e.srcOffset),
            rebuildExpr(e.length),
            e.tag
        )
        is TACExpr.BinBoolOp.LAnd -> this.LAnd(e.ls.map { rebuildExpr(it) }, e.tag)
        is TACExpr.BinBoolOp.LOr -> this.LOr(e.ls.map { rebuildExpr(it) }, e.tag)
        is TACExpr.UnaryExp.LNot -> this.LNot(rebuildExpr(e.o), e.tag)
        is TACExpr.TernaryExp.Ite -> this.Ite(rebuildExpr(e.i), rebuildExpr(e.t), rebuildExpr(e.e), e.tag)
        is TACExpr.TernaryExp.MulMod -> this.MulMod(rebuildExpr(e.a), rebuildExpr(e.b), rebuildExpr(e.n), e.tag)
        is TACExpr.TernaryExp.AddMod -> this.AddMod(rebuildExpr(e.a), rebuildExpr(e.b), rebuildExpr(e.n), e.tag)
        is TACExpr.StructAccess -> this.StructAccess(rebuildExpr(e.struct), e.fieldName, e.tag)
        is TACExpr.StructConstant -> this.StructConstant(e.fieldNameToValue.map { (k, v) -> k to rebuildExpr(v) }.toMap(), e.tag)
        is TACExpr.BinOp.SignExtend -> this.SignExtend(rebuildExpr(e.o1), rebuildExpr(e.o2), e.tag)
        is TACExpr.Unconstrained -> this.Unconstrained(e.tag)
        is TACExpr.AnnotationExp<*> -> rebuildAnnotationExp(e)
    }

    private fun <@Treapable T : Serializable> rebuildAnnotationExp(e : TACExpr.AnnotationExp<T>) =
        this.AnnotationExp(rebuildExpr(e.o), e.annot.k, e.annot.v)

    open fun SignExtend(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExpr.BinOp.SignExtend(o1, o2, tag)

    infix fun TACExpr.and(other: TACExpr) = this@TACExprFactory.LAnd(this, other, null)
    fun and(vararg args: TACExpr) = this@TACExprFactory.LAnd(args.toList(), null)
    fun and(args: List<TACExpr>) = this@TACExprFactory.LAnd(args, null)

    infix fun TACExpr.or(other: TACExpr) = this@TACExprFactory.LOr(this, other, null)
    fun or(vararg args: TACExpr) = this@TACExprFactory.LOr(args.toList(), null)

    fun not(exp: TACExpr) = this@TACExprFactory.LNot(exp, null)

    infix fun TACExpr.lt(other: TACExpr) = this@TACExprFactory.Lt(this, other, null)
    infix fun TACExpr.gt(other: TACExpr) = this@TACExprFactory.Gt(this, other, null)
    infix fun TACExpr.le(other: TACExpr) = this@TACExprFactory.Le(this, other, null)
    infix fun TACExpr.ge(other: TACExpr) = this@TACExprFactory.Ge(this, other, null)
    fun ite(i: TACExpr, t: TACExpr, e: TACExpr) = this@TACExprFactory.Ite(i, t, e, null)

    fun unconstrained(tag: Tag) = this@TACExprFactory.Unconstrained(tag)

    infix fun TACExpr.eq(other: TACExpr) = this@TACExprFactory.Eq(this, other, null)

    fun select(base: TACExpr, loc: TACExpr) = this@TACExprFactory.Select(base, loc, null)


    fun quant(isForall: Boolean, vararg qVars: TACSymbol.Var, body: () -> TACExpr) =
        this@TACExprFactory.QuantifiedFormula(isForall, qVars.toList(), body(), null)

    fun quant(isForall: Boolean, qVars: List<TACSymbol.Var>, body: () -> TACExpr) =
        this@TACExprFactory.QuantifiedFormula(isForall, qVars, body(), null)


    fun forall(vararg qVars: TACSymbol.Var, body: () -> TACExpr) =
        this@TACExprFactory.QuantifiedFormula(true, qVars.toList(), body(), null)

    fun forall(qVars: List<TACSymbol.Var>, body: () -> TACExpr) =
        this@TACExprFactory.QuantifiedFormula(true, qVars, body(), null)

    fun exists(vararg qVars: TACSymbol.Var, body: () -> TACExpr) =
        this@TACExprFactory.QuantifiedFormula(false, qVars.toList(), body(), null)

    fun exists(qVars: List<TACSymbol.Var>, body: () -> TACExpr) =
        this@TACExprFactory.QuantifiedFormula(false, qVars, body(), null)

    fun number(n: BigInteger, tag: Tag = Tag.Bit256) = this@TACExprFactory.Sym(TACSymbol.Const(n, tag))
    fun number(n: Long, tag: Tag = Tag.Bit256) = number(BigInteger.valueOf(n), tag)

    fun variable(name: String, tag: Tag) = TACSymbol.Var(name, tag).asSym()

    val True: TACExpr = this@TACExprFactory.Sym(TACSymbol.True)
    val False: TACExpr = this@TACExprFactory.Sym(TACSymbol.False)
}

object TACExprFactSimple : TACExprFactory()

/** Does simple simplifications/normalizations when creating expressions
 *  Current simplifications include:
 *
 *  logical:
 *   - (and A True) ~~> A
 *   - (or A False) ~~> A
 *   - (= x x) ~~> True
 *
 *   arithmetical:
 *   - (add emptyList()) ~~> 0
 *   - (add listOf(x)) ~~> x
 *   - (mul emptyList()) ~~> 1
 *   - (mul listOf(x)) ~~> x
 *   (no constant folding done here -- we might consider doing that in a TAC factory some time but
 *    not in this PR ... maybe we do it when doing [https://certora.atlassian.net/browse/CER-1071]
 *     --> then we might drop it e.g. from PeepHoleOptimizer, probably.. )
 *
 *   TODO: should this inherit from Untyped?
 *  */
object TACExprFactBasicSimp : TACExprFactory() {

    override fun QuantifiedFormula(isForall: Boolean, quantifiedVars: List<TACSymbol.Var>, body: TACExpr, tag: Tag?) =
        if (quantifiedVars.isEmpty()) {
            body // TODO: Tag?
        } else if (body is TACExpr.QuantifiedFormula &&
            body.isForall == isForall &&
            !quantifiedVars.containsAny(body.quantifiedVars)
        ) {
            // normalize, e.g. `forall x, y. forall z, u. body` ~~> `forall x, y, z, u. body`
            super.QuantifiedFormula(isForall, quantifiedVars + body.quantifiedVars, body.body, tag)
        } else {
            super.QuantifiedFormula(isForall, quantifiedVars, body, tag)
        }

    override fun LAnd(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr {
        val withOutTrueExps = ls.filter { it !is TACExpr.Sym || it.s != TACSymbol.True }
        return when (withOutTrueExps.size) {
            // technical debt: cannot write just `True` here because it makes things explode
            //   --> we should make it so it doesn't make things explode, see https://certora.atlassian.net/browse/CER-1237
            0 -> TACSymbol.True.asSym() // True
            1 -> withOutTrueExps.first()
            else -> super.LAnd(withOutTrueExps, tag)
        }
    }

    override fun LOr(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr {
        val withOutFalseExps = ls.filter { it !is TACExpr.Sym || it.s != TACSymbol.False }
        return when (withOutFalseExps.size) {
            // technical debt: cannot write just `False` here because it makes things explode
            //   --> we should make it so it doesn't make things explode, see https://certora.atlassian.net/browse/CER-1237
            0 -> TACSymbol.False.asSym() // False
            1 -> withOutFalseExps.first()
            else -> super.LOr(withOutFalseExps, tag)
        }
    }

    override fun Eq(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr {
        if (o1 == o2) return TACSymbol.True.asSym()
        return super.Eq(o1, o2, tag)
    }

    override fun Add(ls: List<TACExpr>, tag: Tag.Bits?): TACExpr {
        return when (ls.size) {
            0 -> number(0)
            1 -> ls.single()
            else -> super.Add(ls, tag)
        }
    }

    override fun Mul(ls: List<TACExpr>, tag: Tag.Bits?): TACExpr {
        return when (ls.size) {
            0 -> number(1)
            1 -> ls.single()
            else -> super.Mul(ls, tag)
        }
    }

}

/**
 * Uses [TACExprFactBasicSimp] for simplification of (some) logical expressions.
 * In addition to that simplifies terms of the form `select(store(a, i, v), i)` to `v`.
 */
object TACExprFactLogicalAndSelectSimplification : TACExprFactory() {
    private val factForLogicalExps = TACExprFactBasicSimp

    override fun QuantifiedFormula(isForall: Boolean, quantifiedVars: List<TACSymbol.Var>, body: TACExpr, tag: Tag?) =
        factForLogicalExps.QuantifiedFormula(isForall, quantifiedVars, body, tag)

    override fun LAnd(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr =
        factForLogicalExps.LAnd(ls, tag)

    override fun LOr(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr =
        factForLogicalExps.LOr(ls, tag)

    override fun Eq(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        factForLogicalExps.Eq(o1, o2, tag)

    override fun Select(b: TACExpr, l: TACExpr, tag: Tag?): TACExpr {
        return when (val superResult = super.Select(b, l, tag)) {
            is TACExpr.Select -> {
                val bNew = superResult.base
                val lNew = superResult.loc
                return when (bNew) {
                    is TACExpr.Store -> {
                        if (lNew == bNew.loc) {
                            bNew.value
                        } else {
                            superResult
                        }
                    }
                    else -> superResult
                }
            }
            else -> superResult
        }

    }
}
