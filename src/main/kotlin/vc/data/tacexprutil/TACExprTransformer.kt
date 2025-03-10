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
import vc.data.HashFamily
import vc.data.TACExpr
import vc.data.TACSymbol
import java.io.Serializable

/**
 * This does not recurse into variables appearing in meta-maps or in the annotations of [TACExpr.AnnotationExp].
 * Of course, such functionality can be added in sub-classes.
 */
open class DefaultTACExprTransformer : TACExprTransformer<TACExpr>() {

    override fun transformQuantifiedFormula(
        isForall: Boolean,
        quantifiedVars: List<TACSymbol.Var>,
        body: TACExpr,
        tag: Tag?
    ): TACExpr = TACExprFactSimple.QuantifiedFormula(isForall, quantifiedVars, transform(body), tag)

    override fun transformVar(exp: TACExpr.Sym.Var): TACExpr = exp

    override fun transformConst(exp: TACExpr.Sym.Const): TACExpr = exp

    override fun transformVecMul(ls: List<TACExpr>, tag: Tag.Bits?): TACExpr = TACExprFactSimple.Mul(ls.map { transform(it) }, tag)

    override fun transformVecIntMul(ls: List<TACExpr>, tag: Tag.Int?): TACExpr = TACExprFactSimple.IntMul(ls.map { transform(it) }, tag)

    override fun transformVecIntAdd(ls: List<TACExpr>, tag: Tag.Int?): TACExpr = TACExprFactSimple.IntAdd(ls.map { transform(it) }, tag)

    override fun transformVecAdd(ls: List<TACExpr>, tag: Tag.Bits?): TACExpr = TACExprFactSimple.Add(ls.map { transform(it) }, tag)

    override fun transformSimpleHash(
        length: TACExpr,
        args: List<TACExpr>,
        hashFamily: HashFamily,
        tag: Tag?
    ): TACExpr =
        TACExprFactSimple.SimpleHash(transform(length), args.map { transform(it) }, hashFamily, tag)

    override fun transformIntSub(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr = TACExprFactSimple.IntSub(
        transform(o1),
        transform(o2),
        tag
    )

    override fun transformSub(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Sub(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformDiv(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Div(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformSDiv(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.SDiv(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformIntDiv(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr =
        TACExprFactSimple.IntDiv(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformMod(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Mod(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformSMod(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.SMod(
            transform(
                o1
            ),
            transform(o2),
            tag
        )

    override fun transformIntMod(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr =
        TACExprFactSimple.IntMod(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformExponent(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Exponent(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformIntExponent(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr =
        TACExprFactSimple.IntExponent(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformBWAnd(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.BWAnd(
            transform(
                o1
            ),
            transform(o2),
            tag
        )

    override fun transformBWOr(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.BWOr(
            transform(
                o1
            ),
            transform(o2),
            tag
        )

    override fun transformBWXor(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.BWXOr(
            transform(
                o1
            ),
            transform(o2),
            tag
        )

    override fun transformShiftLeft(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.ShiftLeft(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformShiftRightLogical(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.ShiftRightLogical(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformShiftRightArithmetical(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.ShiftRightArithmetical(
            transform(o1),
            transform(o2),
            tag
        )

    override fun transformSelect(base: TACExpr, loc: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.Select(transform(base), transform(loc), tag)

    override fun transformMultiDimStore(base: TACExpr, locs: List<TACExpr>, value: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.MultiDimStore(transform(base), locs.map { transform(it) }, transform(value), tag)

    override fun transformMapDefinition(defParams: List<TACExpr.Sym.Var>, definition: TACExpr, tag: Tag.Map): TACExpr =
        TACExprFactSimple.MapDefinition(defParams, definition, tag)

    override fun transformUnconstrained(tag: Tag): TACExpr =
        TACExprFactSimple.Unconstrained(tag)

    override fun transformStore(base: TACExpr, loc: TACExpr, value: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.Store(transform(base), transform(loc), transform(value), tag)

    override fun transformLongStore(
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr,
        tag: Tag?
    ): TACExpr =
        TACExprFactSimple.LongStore(
            transform(dstMap),
            transform(dstOffset),
            transform(srcMap),
            transform(srcOffset),
            transform(length),
            tag
        )

    override fun transformLAnd(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.LAnd(ls.map {
            transform(
                it
            )
        },
            tag)

    override fun transformLOr(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.LOr(ls.map {
            transform(
                it
            )
        },
            tag)

    override fun transformLNot(e: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.LNot(
            transform(e),
            tag
        )

    override fun transformBWNot(e: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.BWNot(
            transform(e),
            tag
        )

    override fun transformIte(i: TACExpr, t: TACExpr, e: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.Ite(
            transform(i),
            transform(t),
            transform(e),
            tag
        )

    override fun transformMulMod(a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): TACExpr =
            TACExprFactSimple.MulMod(
                    transform(a),
                    transform(b),
                    transform(n),
                    tag
            )

    override fun transformAddMod(a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): TACExpr =
            TACExprFactSimple.AddMod(
                    transform(a),
                    transform(b),
                    transform(n),
                    tag
            )

    override fun transformGt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Gt(transform(o1), transform(o2), tag)

    override fun transformGe(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Ge(transform(o1), transform(o2), tag)

    override fun transformLt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Lt(transform(o1), transform(o2), tag)

    override fun transformSlt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
            TACExprFactSimple.Slt(transform(o1), transform(o2), tag)

    override fun transformSle(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
            TACExprFactSimple.Sle(transform(o1), transform(o2), tag)

    override fun transformSgt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
            TACExprFactSimple.Sgt(transform(o1), transform(o2), tag)

    override fun transformSge(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
            TACExprFactSimple.Sge(transform(o1), transform(o2), tag)

    override fun transformLe(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Le(transform(o1), transform(o2), tag)

    override fun transformEq(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Eq(transform(o1), transform(o2), tag)

    override fun transformStructAccess(struct: TACExpr, fieldName: String, tag: Tag?): TACExpr =
        TACExprFactSimple.StructAccess(transform(struct), fieldName, tag)

    override fun transformStructConstant(e: TACExpr.StructConstant, tag: Tag?): TACExpr =
        TACExprFactSimple.StructConstant(e.fieldNameToValue.mapValues { (_, v) -> transform(v) }, tag)

    override fun <@Treapable R : Serializable> transformAnnotationExp(o: TACExpr, k: MetaKey<R>, v: R): TACExpr =
        TACExprFactSimple.AnnotationExp(transform(o), k, v)

    override fun transformSignExtend(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.SignExtend(transform(o1), transform(o2), tag)

    override fun transformApply(f: TACExpr.TACFunctionSym, ops: List<TACExpr>, tag: Tag?): TACExpr =
        TACExprFactSimple.Apply(f, ops.map { transform(it) }, tag)
}


abstract class TACExprTransformer<T> {

    open fun transformQuantifiedFormula(exp: TACExpr.QuantifiedFormula): T =
        transformQuantifiedFormula(exp.isForall, exp.quantifiedVars, exp.body, exp.tag)

    abstract fun transformQuantifiedFormula(isForall: Boolean, quantifiedVars: List<TACSymbol.Var>, body: TACExpr, tag: Tag?): T

    open fun transformSym(exp: TACExpr.Sym): T =
        when (exp) {
            is TACExpr.Sym.Var -> transformVar(exp)
            is TACExpr.Sym.Const -> transformConst(exp)
        }

    abstract fun transformVar(exp: TACExpr.Sym.Var): T

    abstract fun transformConst(exp: TACExpr.Sym.Const): T

    open fun transformVec(e: TACExpr.Vec): T =
        when (e) {
            is TACExpr.Vec.Mul -> transformVecMul(e)
            is TACExpr.Vec.IntMul -> transformVecIntMul(e)
            is TACExpr.Vec.IntAdd -> transformVecIntAdd(e)
            is TACExpr.Vec.Add -> transformVecAdd(e)
        }

    open fun transformVecMul(e: TACExpr.Vec.Mul): T =
        transformVecMul(e.ls, e.tag)

    abstract fun transformVecMul(ls: List<TACExpr>, tag: Tag.Bits?): T
    open fun transformVecIntMul(e: TACExpr.Vec.IntMul): T =
        transformVecIntMul(e.ls, e.tag)

    abstract fun transformVecIntMul(ls: List<TACExpr>, tag: Tag.Int?): T
    open fun transformVecIntAdd(e: TACExpr.Vec.IntAdd): T =
        transformVecIntAdd(e.ls, e.tag)

    abstract fun transformVecIntAdd(ls: List<TACExpr>, tag: Tag.Int?): T
    open fun transformVecAdd(e: TACExpr.Vec.Add): T =
        transformVecAdd(e.ls, e.tag)

    abstract fun transformVecAdd(ls: List<TACExpr>, tag: Tag.Bits?): T
    open fun transformSimpleHash(e: TACExpr.SimpleHash): T =
        transformSimpleHash(e.length, e.args, e.hashFamily, e.tag)

    abstract fun transformSimpleHash(length: TACExpr, args: List<TACExpr>, hashFamily: HashFamily, tag: Tag?): T

    open fun transformBinOp(e: TACExpr.BinOp): T =
        when (e) {
            is TACExpr.BinOp.IntSub -> transformIntSub(e)
            is TACExpr.BinOp.Sub -> transformSub(e)
            is TACExpr.BinOp.Div -> transformDiv(e)
            is TACExpr.BinOp.SDiv -> transformSDiv(e)
            is TACExpr.BinOp.IntDiv -> transformIntDiv(e)
            is TACExpr.BinOp.Mod -> transformMod(e)
            is TACExpr.BinOp.SMod -> transformSMod(e)
            is TACExpr.BinOp.IntMod -> transformIntMod(e)
            is TACExpr.BinOp.Exponent -> transformExponent(e)
            is TACExpr.BinOp.IntExponent -> transformIntExponent(e)
            is TACExpr.BinOp.BWAnd -> transformBWAnd(e)
            is TACExpr.BinOp.BWOr -> transformBWOr(e)
            is TACExpr.BinOp.BWXOr -> transformBWXor(e)
            is TACExpr.BinOp.ShiftLeft -> transformShiftLeft(e)
            is TACExpr.BinOp.ShiftRightLogical -> transformShiftRightLogical(e)
            is TACExpr.BinOp.ShiftRightArithmetical -> transformShiftRightArithmetical(e)
            is TACExpr.BinOp.SignExtend -> transformSignExtend(e)
        }

    open fun transformSignExtend(e: TACExpr.BinOp.SignExtend): T = transformSignExtend(e.o1, e.o2, e.tag)

    abstract fun transformSignExtend(o1: TACExpr, o2: TACExpr, tag: Tag?): T

    open fun transformIntSub(e: TACExpr.BinOp.IntSub): T = transformIntSub(e.o1, e.o2, e.tag)

    abstract fun transformIntSub(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): T

    open fun transformSub(e: TACExpr.BinOp.Sub): T = transformSub(e.o1, e.o2, e.tag)

    abstract fun transformSub(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): T

    open fun transformDiv(e: TACExpr.BinOp.Div): T = transformDiv(e.o1, e.o2, e.tag)

    abstract fun transformDiv(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): T

    open fun transformSDiv(e: TACExpr.BinOp.SDiv): T = transformSDiv(e.o1, e.o2, e.tag)

    abstract fun transformSDiv(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): T

    open fun transformIntDiv(e: TACExpr.BinOp.IntDiv): T = transformIntDiv(e.o1, e.o2, e.tag)

    abstract fun transformIntDiv(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): T

    open fun transformMod(e: TACExpr.BinOp.Mod): T = transformMod(e.o1, e.o2, e.tag)

    abstract fun transformMod(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): T

    open fun transformSMod(e: TACExpr.BinOp.SMod): T = transformSMod(e.o1, e.o2, e.tag)

    abstract fun transformSMod(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): T

    open fun transformIntMod(e: TACExpr.BinOp.IntMod): T = transformIntMod(e.o1, e.o2, e.tag)

    abstract fun transformIntMod(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): T

    open fun transformExponent(e: TACExpr.BinOp.Exponent): T = transformExponent(e.o1, e.o2, e.tag)

    abstract fun transformExponent(o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): T

    open fun transformIntExponent(e: TACExpr.BinOp.IntExponent): T = transformIntExponent(e.o1, e.o2, e.tag)

    abstract fun transformIntExponent(o1: TACExpr, o2: TACExpr, tag: Tag.Int?): T

    open fun transformBWAnd(e: TACExpr.BinOp.BWAnd): T = transformBWAnd(e.o1, e.o2, e.tag)

    abstract fun transformBWAnd(o1: TACExpr, o2: TACExpr, tag: Tag?): T

    open fun transformBWOr(e: TACExpr.BinOp.BWOr): T = transformBWOr(e.o1, e.o2, e.tag)

    abstract fun transformBWOr(o1: TACExpr, o2: TACExpr, tag: Tag?): T

    open fun transformBWXor(e: TACExpr.BinOp.BWXOr): T = transformBWXor(e.o1, e.o2, e.tag)

    abstract fun transformBWXor(o1: TACExpr, o2: TACExpr, tag: Tag?): T

    open fun transformShiftLeft(e: TACExpr.BinOp): T = transformShiftLeft(e.o1, e.o2, e.tag)

    abstract fun transformShiftLeft(o1: TACExpr, o2: TACExpr, tag: Tag?): T

    open fun transformShiftRightLogical(e: TACExpr.BinOp.ShiftRightLogical): T = transformShiftRightLogical(e.o1, e.o2, e.tag)

    abstract fun transformShiftRightLogical(o1: TACExpr, o2: TACExpr, tag: Tag?): T

    open fun transformShiftRightArithmetical(e: TACExpr.BinOp.ShiftRightArithmetical): T = transformShiftRightArithmetical(e.o1, e.o2, e.tag)

    abstract fun transformShiftRightArithmetical(o1: TACExpr, o2: TACExpr, tag: Tag?): T

    open fun transformApply(e: TACExpr.Apply): T =
        transformApply(e.f, e.ops, e.tag)

    abstract fun transformApply(f: TACExpr.TACFunctionSym, ops: List<TACExpr>, tag: Tag?): T

    open fun transformSelect(e: TACExpr.Select): T =
        transformSelect(e.base, e.loc, e.tag)

    open fun transformMultiDimStore(e: TACExpr.MultiDimStore): T =
        transformMultiDimStore(e.base, e.locs, e.value, e.tag)
    abstract fun transformMultiDimStore(base: TACExpr, locs: List<TACExpr>, value: TACExpr, tag: Tag?): T

    abstract fun transformSelect(base: TACExpr, loc: TACExpr, tag: Tag?): T

    open fun transformMapDefinition(exp: TACExpr.MapDefinition): T =
        transformMapDefinition(exp.defParams, exp.definition, exp.tag)
    abstract fun transformMapDefinition(defParams: List<TACExpr.Sym.Var>, definition: TACExpr, tag: Tag.Map): T

    open fun transformUnconstrained(exp: TACExpr.Unconstrained): T =
        transformUnconstrained(exp.tag)
    abstract fun transformUnconstrained(tag: Tag): T

    open fun transformStore(e: TACExpr.Store): T =
        transformStore(e.base, e.loc, e.value, e.tag)

    abstract fun transformStore(base: TACExpr, loc: TACExpr, value: TACExpr, tag: Tag?): T

    open fun transformLongStore(e: TACExpr.LongStore): T =
        transformLongStore(e.dstMap, e.dstOffset, e.srcMap, e.srcOffset, e.length, e.tag)

    abstract fun transformLongStore(
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr,
        tag: Tag?
    ): T

    open fun transformBinBoolOp(e: TACExpr.BinBoolOp): T =
        when (e) {
            is TACExpr.BinBoolOp.LAnd -> transformLAnd(e)
            is TACExpr.BinBoolOp.LOr -> transformLOr(e)
        }

    open fun transformLAnd(e: TACExpr.BinBoolOp.LAnd): T =
        transformLAnd(e.ls, e.tag)

    abstract fun transformLAnd(ls: List<TACExpr>, tag: Tag.Bool?): T

    open fun transformLOr(e: TACExpr.BinBoolOp.LOr): T =
        transformLOr(e.ls, e.tag)

    abstract fun transformLOr(ls: List<TACExpr>, tag: Tag.Bool?): T

    open fun transformUnary(e: TACExpr.UnaryExp): T =
        when (e) {
            is TACExpr.UnaryExp.BWNot -> transformBWNot(e.o, e.tag)
            is TACExpr.UnaryExp.LNot -> transformLNot(e.o, e.tag)
        }

    open fun transformBWNot(e: TACExpr.UnaryExp.BWNot): T =
        transformBWNot(e.o, e.tag)

    abstract fun transformBWNot(e: TACExpr, tag: Tag?): T

    open fun transformLNot(e: TACExpr.UnaryExp.LNot): T =
        transformLNot(e.o, e.tag)

    abstract fun transformLNot(e: TACExpr, tag: Tag.Bool?): T

    open fun transformTernary(e: TACExpr.TernaryExp): T =
        when (e) {
            is TACExpr.TernaryExp.Ite -> transformIte(e)
            is TACExpr.TernaryExp.MulMod -> transformMulMod(e)
            is TACExpr.TernaryExp.AddMod -> transformAddMod(e)
        }

    open fun transformIte(e: TACExpr.TernaryExp.Ite): T =
        transformIte(e.i, e.t, e.e, e.tag)

    abstract fun transformIte(i: TACExpr, t: TACExpr, e: TACExpr, tag: Tag?): T

    open fun transformMulMod(e: TACExpr.TernaryExp.MulMod): T =
            transformMulMod(e.a, e.b, e.n, e.tag)

    abstract fun transformMulMod(a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): T

    open fun transformAddMod(e: TACExpr.TernaryExp.AddMod): T =
            transformAddMod(e.a, e.b, e.n, e.tag)

    abstract fun transformAddMod(a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): T

    open fun transformBinRel(e: TACExpr.BinRel): T =
        when (e) {
            is TACExpr.BinRel.Gt -> transformGt(e)
            is TACExpr.BinRel.Lt -> transformLt(e)
            is TACExpr.BinRel.Ge -> transformGe(e)
            is TACExpr.BinRel.Le -> transformLe(e)
            is TACExpr.BinRel.Eq -> transformEq(e)
            is TACExpr.BinRel.Slt -> transformSlt(e)
            is TACExpr.BinRel.Sle -> transformSle(e)
            is TACExpr.BinRel.Sge -> transformSge(e)
            is TACExpr.BinRel.Sgt -> transformSgt(e)
        }

    open fun transformGt(e: TACExpr.BinRel.Gt): T =
        transformGt(e.o1, e.o2, e.tag)

    abstract fun transformGt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformLt(e: TACExpr.BinRel.Lt): T =
        transformLt(e.o1, e.o2, e.tag)

    abstract fun transformLt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformSlt(e: TACExpr.BinRel.Slt): T =
            transformSlt(e.o1, e.o2, e.tag)

    abstract fun transformSlt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformSgt(e: TACExpr.BinRel.Sgt): T =
            transformSgt(e.o1, e.o2, e.tag)

    abstract fun transformSgt(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformSge(e: TACExpr.BinRel.Sge): T =
            transformSge(e.o1, e.o2, e.tag)

    abstract fun transformSge(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformSle(e: TACExpr.BinRel.Sle): T =
            transformSle(e.o1, e.o2, e.tag)

    abstract fun transformSle(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformGe(e: TACExpr.BinRel.Ge): T =
        transformGe(e.o1, e.o2, e.tag)

    abstract fun transformGe(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformLe(e: TACExpr.BinRel.Le): T =
        transformLe(e.o1, e.o2, e.tag)

    abstract fun transformLe(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformEq(e: TACExpr.BinRel.Eq): T =
        transformEq(e.o1, e.o2, e.tag)

    abstract fun transformEq(o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): T

    open fun transformStructAccess(e: TACExpr.StructAccess): T =
        transformStructAccess(e.struct, e.fieldName, e.tag)

    abstract fun transformStructAccess(struct: TACExpr, fieldName: String, tag: Tag?): T

    abstract fun transformStructConstant(e: TACExpr.StructConstant, tag: Tag?): T

    open fun <@Treapable R : Serializable> transformAnnotationExp(e: TACExpr.AnnotationExp<R>): T =
        transformAnnotationExp(e.o, e.annot.k, e.annot.v)

    abstract fun <@Treapable R : Serializable> transformAnnotationExp(o : TACExpr, k : MetaKey<R>, v : R) : T

    open fun transform(exp: TACExpr): T = when (exp) {
        is TACExpr.QuantifiedFormula -> transformQuantifiedFormula(exp)
        is TACExpr.Sym -> transformSym(exp)
        is TACExpr.Vec -> transformVec(exp)
        is TACExpr.SimpleHash -> transformSimpleHash(exp)
        is TACExpr.BinOp -> transformBinOp(exp)
        is TACExpr.Apply -> transformApply(exp)
        is TACExpr.Select -> transformSelect(exp)
        is TACExpr.MultiDimStore -> transformMultiDimStore(exp)
        is TACExpr.MapDefinition -> transformMapDefinition(exp)
        is TACExpr.Unconstrained -> transformUnconstrained(exp)
        is TACExpr.Store -> transformStore(exp)
        is TACExpr.LongStore -> transformLongStore(exp)
        is TACExpr.BinBoolOp -> transformBinBoolOp(exp)
        is TACExpr.BinRel -> transformBinRel(exp)
        is TACExpr.UnaryExp -> transformUnary(exp)
        is TACExpr.TernaryExp -> transformTernary(exp)
        is TACExpr.StructAccess -> transformStructAccess(exp)
        is TACExpr.StructConstant -> transformStructConstant(exp, exp.tag)
        is TACExpr.AnnotationExp<*> -> transformAnnotationExp(exp)
    }
}
