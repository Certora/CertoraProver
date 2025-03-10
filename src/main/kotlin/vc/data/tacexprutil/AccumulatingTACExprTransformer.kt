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

import analysis.ExpPointer
import com.certora.collect.*
import tac.MetaKey
import tac.Tag
import utils.CertoraInternalErrorType
import utils.CertoraInternalException
import vc.data.HashFamily
import vc.data.TACExpr
import vc.data.TACSymbol
import java.io.Serializable


class TACExprTransformerException(msg: String) :
    CertoraInternalException(CertoraInternalErrorType.TAC_TRANSFORMER_EXCEPTION, msg)

/**
 * A transformer for [TACExpr]s that may contain quantifiers.
 * Implements the convention that
 *  - quantified variables are not transformed
 *  - the transformation cannot introduce variables that are captured by the quantifier (avoided through alpha-renaming
 *    away the quantified variables)
 * This convention comes from standard mathematical substitution -- it may or may not be appropriate for your use case.
 * If you need another convention, use e.g. [DefaultAccumulatingTACExprTransformer] to implement it.
 *
 * Does not recurse into the meta-maps attached to variables and the annotations of [TACExpr.AnnotationExp].
 */
open class QuantDefaultTACExprTransformer :
    DefaultAccumulatingTACExprTransformer<QuantDefaultTACExprTransformer.QuantVars>() {

    /** Stores quantified variables that are bound in a subexpression. */
    class QuantVars(val quantifiedVars: List<TACSymbol.Var>) {
        fun push(vars: List<TACSymbol.Var>): QuantVars = QuantVars(quantifiedVars + vars)

        override fun toString(): String {
            return "qvars $quantifiedVars"
        }

        companion object {
            val Empty = QuantVars(listOf())
        }
    }

    /** Transform an expression that is not inside a quantifier. */
    fun transformOuter(exp: TACExpr): TACExpr = transform(QuantVars.Empty, exp)

    /**
     * We need to avoid two things here:
     * - the transformation creates an expression that contains a free variable that is captured by this quantifier
     * - the transformation changes the quantified variables inside [body] into something else
     * (this is analogous to standard substitution rules for quantified formulas)
     * Strategy:
     * - rename all variables v in [quantifiedVars] to something fresh in the [body]
     * - transform [body]
     * - if the transformed body does not contain any variable from [quantifiedVars]
     *   - reverse the renaming (just for cosmetics, so the formula isn't changed unnecessarily) in transformed body
     *   - return [isForall] [quantifiedVars] . <transformedBody>
     * - else
     *   - return [isForall] <fresh vars> . <transformedBody>
     * (note: we're doing the check for that "if" all-or nothing right now, for simplicity -- if only some variables are
     *  captured, we alpha-rename all of them)
     */
    @Suppress("NAME_SHADOWING")
    override fun transformQuantifiedFormula(
        acc: QuantVars,
        isForall: Boolean,
        quantifiedVars: List<TACSymbol.Var>,
        body: TACExpr,
        tag: Tag?
    ): TACExpr {
        val (qVarsToFreshVars: Map<TACSymbol.Var, TACSymbol.Var>, bodyQVarsRenamedAway) =
            renameBodyVars(quantifiedVars, body)

        val bodyTransformed = transform(acc.push(qVarsToFreshVars.values.toList()), bodyQVarsRenamedAway)

        return renameBack(
            bodyTransformed,
            quantifiedVars,
            { qVars, body, tag -> TACExprFactSimple.QuantifiedFormula(isForall, qVars, body, tag) },
            qVarsToFreshVars,
            tag
        )
    }

    /**
     * Like in the quantified case, `MapDefinition` also has bound variables -- we treat them like the ones bound
     * by quantifiers.
     */
    @Suppress("NAME_SHADOWING")
    override fun transformMapDefinition(
        acc: QuantVars,
        defParams: List<TACExpr.Sym.Var>,
        definition: TACExpr,
        tag: Tag.Map
    ): TACExpr {
        val (qVarsToFreshVars: Map<TACSymbol.Var, TACSymbol.Var>, bodyQVarsRenamedAway) =
            renameBodyVars(defParams.map { it.s }, definition)

        val bodyTransformed = transform(acc.push(qVarsToFreshVars.values.toList()), bodyQVarsRenamedAway)

        return renameBack(
            bodyTransformed,
            defParams.map { it.s },
            { _defParams, _definition, tag ->
                TACExprFactSimple.MapDefinition(
                    _defParams.map { it.asSym() },
                    _definition,
                    tag
                )
            },
            qVarsToFreshVars,
            tag
        )
    }

    /** One cannot override this in the quantified case (see [transformQuantifiedFormula] for an explanation), override,
     * use tranformFreeVar instead */
    final override fun transformVar(acc: QuantVars, exp: TACExpr.Sym.Var): TACExpr =
        if (exp.s in acc.quantifiedVars) exp else transformFreeVar(acc, exp)

    open fun transformFreeVar(acc: QuantVars, exp: TACExpr.Sym.Var): TACExpr = exp

    companion object {
        fun <T : Tag?> renameBack(
            bodyTransformed: TACExpr,
            quantifiedVars: List<TACSymbol.Var>,
            make: (List<TACSymbol.Var>, TACExpr, T) -> TACExpr,
            qVarsToFreshVars: Map<TACSymbol.Var, TACSymbol.Var>,
            // TODO: are we sure we don't change the type with this transformation?
            tag: T
        ): TACExpr {
            val freeVars = TACExprFreeVarsCollector.getFreeVars(bodyTransformed)
            val res = if (freeVars.containsAny(quantifiedVars)) {
                make(qVarsToFreshVars.values.toList(), bodyTransformed, tag)
            } else {
                // rename back
                val substitution2 =
                    qVarsToFreshVars.entries.map { (qv, qvFresh) -> Pair(qvFresh.asSym(), qv.asSym()) }.toMap()
                val bodyQVarsRenamedBack = TACExprUtils.SubstitutorVar(substitution2).transformOuter(bodyTransformed)
                make(quantifiedVars, bodyQVarsRenamedBack, tag)
            }
            return res
        }

        fun renameBodyVars(
            quantifiedVars: List<TACSymbol.Var>,
            body: TACExpr
        ): Pair<Map<TACSymbol.Var, TACSymbol.Var>, TACExpr> {
            val qVarsToFreshVars: Map<TACSymbol.Var, TACSymbol.Var> =
                quantifiedVars
                    .associateWith { qv ->
                        TACSymbol.Factory.getFreshAuxVar(TACSymbol.Factory.AuxVarPurpose.QUANT, qv)
                    }
            val substitution =
                qVarsToFreshVars.entries.map { (qv, qvFresh) -> Pair(qv.asSym(), qvFresh.asSym()) }.toMap()
            val bodyQVarsRenamedAway = TACExprUtils.SubstitutorVar(substitution).transformOuter(body)
            return Pair(qVarsToFreshVars, bodyQVarsRenamedAway)
        }
    }

}

/**
 * Does not recurse into the meta-maps attached to variables and the annotations of [TACExpr.AnnotationExp].
 */
abstract class QuantDefaultTACExprTransformerWithPointer :
    DefaultAccumulatingTACExprTransformer<QuantDefaultTACExprTransformerWithPointer.QuantVarsAndExpPointer>() {

    data class QuantVarsAndExpPointer(
        val boundVars: QuantDefaultTACExprTransformer.QuantVars,
        val expPointer: ExpPointer
    ) {

        fun push(vars: List<TACSymbol.Var>): QuantVarsAndExpPointer =
            this.copy(boundVars = boundVars.push(vars))

        override fun toString(): String {
            return "$boundVars, $expPointer"
        }

        companion object {
            fun getEmpty(expPointer: ExpPointer) =
                QuantVarsAndExpPointer(
                    QuantDefaultTACExprTransformer.QuantVars.Empty,
                    expPointer
                )
        }
    }

    /** Descendants of this class must call this when they dispatch arguments explicitly */
    override fun transformArg(acc: QuantVarsAndExpPointer, exp: TACExpr, index: Int): TACExpr {
        return super.transformArg(acc.copy(expPointer = acc.expPointer.extend(index)), exp, index)
    }

    /** see [QuantDefaultTACExprTransformer.transformQuantifiedFormula] */
    @Suppress("NAME_SHADOWING")
    override fun transformQuantifiedFormula(
        acc: QuantVarsAndExpPointer,
        isForall: Boolean,
        quantifiedVars: List<TACSymbol.Var>,
        body: TACExpr,
        tag: Tag?
    ): TACExpr {
        val (qVarsToFreshVars: Map<TACSymbol.Var, TACSymbol.Var>, bodyQVarsRenamedAway) =
            QuantDefaultTACExprTransformer.renameBodyVars(quantifiedVars, body)

        val bodyTransformed = transformArg(acc.push(qVarsToFreshVars.values.toList()), bodyQVarsRenamedAway, 0)

        return QuantDefaultTACExprTransformer.renameBack(
            bodyTransformed,
            quantifiedVars,
            { qVars, body, tag -> TACExprFactSimple.QuantifiedFormula(isForall, qVars, body, tag) },
            qVarsToFreshVars,
            tag
        )
    }

    /** see [QuantDefaultTACExprTransformer.transformMapDefinition] */
    @Suppress("NAME_SHADOWING")
    override fun transformMapDefinition(
        acc: QuantVarsAndExpPointer,
        defParams: List<TACExpr.Sym.Var>,
        definition: TACExpr,
        tag: Tag.Map
    ): TACExpr {
        val (qVarsToFreshVars: Map<TACSymbol.Var, TACSymbol.Var>, bodyQVarsRenamedAway) =
            QuantDefaultTACExprTransformer.renameBodyVars(defParams.map { it.s }, definition)

        val bodyTransformed = transformArg(
            acc.push(qVarsToFreshVars.values.toList()),
            bodyQVarsRenamedAway,
            0
        )

        return QuantDefaultTACExprTransformer.renameBack(
            bodyTransformed,
            defParams.map { it.s },
            { _defParams, _definition, tag ->
                TACExprFactSimple.MapDefinition(
                    _defParams.map { it.asSym() },
                    _definition,
                    tag
                )
            },
            qVarsToFreshVars,
            tag
        )
    }

}


/**
 * Does not recurse into the meta-maps attached to variables and the annotations of [TACExpr.AnnotationExp].
 */
abstract class DefaultAccumulatingTACExprTransformer<ACC> : AccumulatingTACExprTransformer<ACC, TACExpr>() {

    override fun transformQuantifiedFormula(
        acc: ACC,
        isForall: Boolean,
        quantifiedVars: List<TACSymbol.Var>,
        body: TACExpr,
        tag: Tag?
    ): TACExpr = TACExprFactSimple.QuantifiedFormula(isForall, quantifiedVars, transformArg(acc, body, 0), tag)

    override fun transformVar(acc: ACC, exp: TACExpr.Sym.Var): TACExpr = exp

    override fun transformConst(acc: ACC, exp: TACExpr.Sym.Const): TACExpr = exp

    override fun transformVecMul(acc: ACC, ls: List<TACExpr>, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Mul(ls.mapIndexed { i, arg -> transformArg(acc, arg, i) }, tag)

    override fun transformVecIntMul(acc: ACC, ls: List<TACExpr>, tag: Tag.Int?): TACExpr =
        TACExprFactSimple.IntMul(ls.mapIndexed { i, arg -> transformArg(acc, arg, i) }, tag)

    override fun transformVecIntAdd(acc: ACC, ls: List<TACExpr>, tag: Tag.Int?): TACExpr =
        TACExprFactSimple.IntAdd(ls.mapIndexed { i, arg -> transformArg(acc, arg, i) }, tag)

    override fun transformVecAdd(acc: ACC, ls: List<TACExpr>, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Add(ls.mapIndexed { i, arg -> transformArg(acc, arg, i) }, tag)

    override fun transformSimpleHash(
        acc: ACC,
        length: TACExpr,
        args: List<TACExpr>,
        hashFamily: HashFamily,
        tag: Tag?
    ): TACExpr =
        TACExprFactSimple.SimpleHash(
            transformArg(acc, length, 0),
            args.mapIndexed { i, arg -> transformArg(acc, arg, i + 1) },
            hashFamily,
            tag
        )

    override fun transformIntSub(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr = TACExprFactSimple.IntSub(
        transformArg(acc, o1, 0),
        transformArg(acc, o2, 1),
        tag
    )

    override fun transformSub(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Sub(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Div(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformSDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.SDiv(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformIntDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr =
        TACExprFactSimple.IntDiv(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Mod(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformSMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.SMod(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformIntMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr =
        TACExprFactSimple.IntMod(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )
    override fun transformExponent(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): TACExpr =
        TACExprFactSimple.Exponent(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformIntExponent(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): TACExpr =
        TACExprFactSimple.IntExponent(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformBWAnd(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.BWAnd(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformBWOr(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.BWOr(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformBWXor(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.BWXOr(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformShiftLeft(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.ShiftLeft(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformShiftRightLogical(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.ShiftRightLogical(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformShiftRightArithmetical(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.ShiftRightArithmetical(
            transformArg(acc, o1, 0),
            transformArg(acc, o2, 1),
            tag
        )

    override fun transformSelect(acc: ACC, base: TACExpr, loc: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.Select(transformArg(acc, base, 0), transformArg(acc, loc, 1), tag)

    override fun transformMultiDimStore(acc: ACC, base: TACExpr, locs: List<TACExpr>, value: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.MultiDimStore(
            transformArg(acc, base, 0),
            locs.mapIndexed { i, l ->  transformArg(acc, l, i + 1) },
            transformArg(acc, value, locs.size + 1),
            tag
        )

    override fun transformStore(acc: ACC, base: TACExpr, loc: TACExpr, value: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.Store(transformArg(acc, base, 0), transformArg(acc, loc, 1), transformArg(acc, value, 2), tag)

    override fun transformLongStore(
        acc: ACC,
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr,
        tag: Tag?
    ): TACExpr =
        TACExprFactSimple.LongStore(
            transformArg(acc, dstMap, 0),
            transformArg(acc, dstOffset, 1),
            transformArg(acc, srcMap, 2),
            transformArg(acc, srcOffset, 3),
            transformArg(acc, length, 4),
            tag
        )

    override fun transformMapDefinition(
        acc: ACC,
        defParams: List<TACExpr.Sym.Var>,
        definition: TACExpr,
        tag: Tag.Map
    ): TACExpr =
        TACExprFactSimple.MapDefinition(
            defParams, // defParams are binders, not proper expressions, thus aren't subject to transformation
            transformArg(acc, definition, 0),
            tag
        )

    override fun transformUnconstrained(acc: ACC, exp: TACExpr.Unconstrained): TACExpr = exp

    override fun transformLAnd(acc: ACC, ls: List<TACExpr>, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.LAnd(ls.mapIndexed { i, arg -> transformArg(acc, arg, i) }, tag)

    override fun transformLOr(acc: ACC, ls: List<TACExpr>, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.LOr(ls.mapIndexed { i, arg -> transformArg(acc, arg, i) }, tag)

    override fun transformLNot(acc: ACC, e: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.LNot(transformArg(acc, e, 0), tag)

    override fun transformBWNot(acc: ACC, e: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.BWNot(transformArg(acc, e, 0), tag)

    override fun transformIte(acc: ACC, i: TACExpr, t: TACExpr, e: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.Ite(
            transformArg(acc, i, 0),
            transformArg(acc, t, 1),
            transformArg(acc, e, 2),
            tag
        )

    override fun transformMulMod(acc: ACC, a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): TACExpr =
            TACExprFactSimple.MulMod(
                    transformArg(acc, a, 0),
                    transformArg(acc, b, 1),
                    transformArg(acc, n, 2),
                    tag
            )

    override fun transformAddMod(acc: ACC, a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): TACExpr =
            TACExprFactSimple.AddMod(
                    transformArg(acc, a, 0),
                    transformArg(acc, b, 1),
                    transformArg(acc, n, 2),
                    tag
            )

    override fun transformGt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Gt(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformGe(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Ge(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformLt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Lt(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformSlt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Slt(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformSle(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Sle(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformSgt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Sgt(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformSge(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Sge(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformLe(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Le(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformEq(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): TACExpr =
        TACExprFactSimple.Eq(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    override fun transformStructAccess(acc: ACC, struct: TACExpr, fieldName: String, tag: Tag?): TACExpr =
        TACExprFactSimple.StructAccess(transformArg(acc, struct, 0), fieldName, tag)

    override fun transformStructConstant(acc: ACC, e: TACExpr.StructConstant, tag: Tag?): TACExpr {
        val fieldNameToValue = mutableMapOf<String, TACExpr>()
        for ((index, kv) in e.fieldNameToValue.iterator().withIndex()) {
            fieldNameToValue[kv.key] = transformArg(acc, kv.value, index)
        }
        return TACExprFactSimple.StructConstant(fieldNameToValue, tag)
    }

    override fun transformSignExtend(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr =
        TACExprFactSimple.SignExtend(transformArg(acc, o1, 0), transformArg(acc, o2, 1), tag)

    /** NB: In case of a ghost function we count the function symbol as an argument.
     * (This is relevant for purposes of arg indices (used e.g. for [ExpPointer]), and must be in sync with
     * `TACExpr.getSubExprs`. */
    override fun transformApply(acc: ACC, f: TACExpr.TACFunctionSym, ops: List<TACExpr>, tag: Tag?): TACExpr =
        TACExprFactSimple.Apply(f, ops.mapIndexed { i, arg -> transformArg(acc, arg, i) }, tag)

    override fun <@Treapable T : Serializable> transformAnnotationExp(acc: ACC, o: TACExpr, k: MetaKey<T>, v: T) =
        TACExprFactSimple.AnnotationExp(transformArg(acc, o, 0), k, v)
}

/**
 * @param ACC accumulator type -- used to propagate information from the outside to the inside of the transformed expression
 * @param RES result type -- the type we transform into
 *
 * Does not recurse into the meta-maps attached to variables and the annotations of [TACExpr.AnnotationExp].
 */
abstract class AccumulatingTACExprTransformer<ACC, RES> {

    open fun transformQuantifiedFormula(acc: ACC, exp: TACExpr.QuantifiedFormula): RES =
        this.transformQuantifiedFormula(acc, exp.isForall, exp.quantifiedVars, exp.body, exp.tag)

    abstract fun transformQuantifiedFormula(
        acc: ACC,
        isForall: Boolean,
        quantifiedVars: List<TACSymbol.Var>,
        body: TACExpr,
        tag: Tag?
    ): RES

    open fun transformSym(acc: ACC, exp: TACExpr.Sym): RES =
        when (exp) {
            is TACExpr.Sym.Var -> transformVar(acc, exp)
            is TACExpr.Sym.Const -> transformConst(acc, exp)
        }

    abstract fun transformVar(acc: ACC, exp: TACExpr.Sym.Var): RES

    abstract fun transformConst(acc: ACC, exp: TACExpr.Sym.Const): RES

    open fun transformVec(acc: ACC, e: TACExpr.Vec): RES =
        when (e) {
            is TACExpr.Vec.Mul -> transformVecMul(acc, e)
            is TACExpr.Vec.IntMul -> transformVecIntMul(acc, e)
            is TACExpr.Vec.IntAdd -> transformVecIntAdd(acc, e)
            is TACExpr.Vec.Add -> transformVecAdd(acc, e)
        }

    open fun transformVecMul(acc: ACC, e: TACExpr.Vec.Mul): RES =
        transformVecMul(acc, e.ls, e.tag)

    abstract fun transformVecMul(acc: ACC, ls: List<TACExpr>, tag: Tag.Bits?): RES
    open fun transformVecIntMul(acc: ACC, e: TACExpr.Vec.IntMul): RES =
        transformVecIntMul(acc, e.ls, e.tag)

    abstract fun transformVecIntMul(acc: ACC, ls: List<TACExpr>, tag: Tag.Int?): RES
    open fun transformVecIntAdd(acc: ACC, e: TACExpr.Vec.IntAdd): RES =
        transformVecIntAdd(acc, e.ls, e.tag)

    abstract fun transformVecIntAdd(acc: ACC, ls: List<TACExpr>, tag: Tag.Int?): RES
    open fun transformVecAdd(acc: ACC, e: TACExpr.Vec.Add): RES =
        transformVecAdd(acc, e.ls, e.tag)

    abstract fun transformVecAdd(acc: ACC, ls: List<TACExpr>, tag: Tag.Bits?): RES
    open fun transformSimpleHash(acc: ACC, e: TACExpr.SimpleHash): RES =
        transformSimpleHash(acc, e.length, e.args, e.hashFamily, e.tag)

    abstract fun transformSimpleHash(
        acc: ACC,
        length: TACExpr,
        args: List<TACExpr>,
        hashFamily: HashFamily,
        tag: Tag?
    ): RES

    open fun transformBinOp(acc: ACC, e: TACExpr.BinOp): RES =
        when (e) {
            is TACExpr.BinOp.IntSub -> transformIntSub(acc, e)
            is TACExpr.BinOp.Sub -> transformSub(acc, e)
            is TACExpr.BinOp.Div -> transformDiv(acc, e)
            is TACExpr.BinOp.SDiv -> transformSDiv(acc, e)
            is TACExpr.BinOp.IntDiv -> transformIntDiv(acc, e)
            is TACExpr.BinOp.Mod -> transformMod(acc, e)
            is TACExpr.BinOp.SMod -> transformSMod(acc, e)
            is TACExpr.BinOp.IntMod -> transformIntMod(acc, e)
            is TACExpr.BinOp.Exponent -> transformExponent(acc, e)
            is TACExpr.BinOp.IntExponent -> transformIntExponent(acc, e)
            is TACExpr.BinOp.BWAnd -> transformBWAnd(acc, e)
            is TACExpr.BinOp.BWOr -> transformBWOr(acc, e)
            is TACExpr.BinOp.BWXOr -> transformBWXor(acc, e)
            is TACExpr.BinOp.ShiftLeft -> transformShiftLeft(acc, e)
            is TACExpr.BinOp.ShiftRightLogical -> transformShiftRightLogical(acc, e)
            is TACExpr.BinOp.ShiftRightArithmetical -> transformShiftRightArithmetical(acc, e)
            is TACExpr.BinOp.SignExtend -> transformSignExtend(acc, e)
        }

    open fun transformSignExtend(acc: ACC, e: TACExpr.BinOp.SignExtend): RES = transformSignExtend(acc, e.o1, e.o2, e.tag)

    abstract fun transformSignExtend(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES

    open fun transformIntSub(acc: ACC, e: TACExpr.BinOp.IntSub): RES = transformIntSub(acc, e.o1, e.o2, e.tag)

    abstract fun transformIntSub(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): RES

    open fun transformSub(acc: ACC, e: TACExpr.BinOp.Sub): RES = transformSub(acc, e.o1, e.o2, e.tag)

    abstract fun transformSub(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES

    open fun transformDiv(acc: ACC, e: TACExpr.BinOp.Div): RES = transformDiv(acc, e.o1, e.o2, e.tag)

    abstract fun transformDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES

    open fun transformSDiv(acc: ACC, e: TACExpr.BinOp.SDiv): RES = transformSDiv(acc, e.o1, e.o2, e.tag)

    abstract fun transformSDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES

    open fun transformIntDiv(acc: ACC, e: TACExpr.BinOp.IntDiv): RES = transformIntDiv(acc, e.o1, e.o2, e.tag)

    abstract fun transformIntDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): RES

    open fun transformMod(acc: ACC, e: TACExpr.BinOp.Mod): RES = transformMod(acc, e.o1, e.o2, e.tag)

    abstract fun transformMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES

    open fun transformSMod(acc: ACC, e: TACExpr.BinOp.SMod): RES = transformSMod(acc, e.o1, e.o2, e.tag)

    abstract fun transformSMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES

    open fun transformIntMod(acc: ACC, e: TACExpr.BinOp.IntMod): RES = transformIntMod(acc, e.o1, e.o2, e.tag)

    abstract fun transformIntMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): RES

    open fun transformExponent(acc: ACC, e: TACExpr.BinOp.Exponent): RES = transformExponent(acc, e.o1, e.o2, e.tag)

    abstract fun transformExponent(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES

    open fun transformIntExponent(acc: ACC, e: TACExpr.BinOp.IntExponent): RES = transformIntExponent(acc, e.o1, e.o2, e.tag)

    abstract fun transformIntExponent(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): RES

    open fun transformBWAnd(acc: ACC, e: TACExpr.BinOp.BWAnd): RES = transformBWAnd(acc, e.o1, e.o2, e.tag)

    abstract fun transformBWAnd(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES

    open fun transformBWOr(acc: ACC, e: TACExpr.BinOp.BWOr): RES = transformBWOr(acc, e.o1, e.o2, e.tag)

    abstract fun transformBWOr(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES

    open fun transformBWXor(acc: ACC, e: TACExpr.BinOp.BWXOr): RES = transformBWXor(acc, e.o1, e.o2, e.tag)

    abstract fun transformBWXor(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES

    open fun transformShiftLeft(acc: ACC, e: TACExpr.BinOp.ShiftLeft): RES = transformShiftLeft(acc, e.o1, e.o2, e.tag)

    abstract fun transformShiftLeft(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES

    open fun transformShiftRightLogical(acc: ACC, e: TACExpr.BinOp.ShiftRightLogical): RES =
        transformShiftRightLogical(acc, e.o1, e.o2, e.tag)

    abstract fun transformShiftRightLogical(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES

    open fun transformShiftRightArithmetical(acc: ACC, e: TACExpr.BinOp.ShiftRightArithmetical): RES =
        transformShiftRightArithmetical(acc, e.o1, e.o2, e.tag)

    abstract fun transformShiftRightArithmetical(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES

    open fun transformApply(acc: ACC, e: TACExpr.Apply): RES =
        transformApply(acc, e.f, e.ops, e.tag)

    abstract fun transformApply(acc: ACC, f: TACExpr.TACFunctionSym, ops: List<TACExpr>, tag: Tag?): RES

    open fun transformSelect(acc: ACC, e: TACExpr.Select): RES =
        transformSelect(acc, e.base, e.loc, e.tag)

    open fun transformMultiDimStore(acc: ACC, e: TACExpr.MultiDimStore): RES =
        transformMultiDimStore(acc, e.base, e.locs, e.value, e.tag)

    abstract fun transformSelect(acc: ACC, base: TACExpr, loc: TACExpr, tag: Tag?): RES
    abstract fun transformMultiDimStore(acc: ACC, base: TACExpr, locs: List<TACExpr>, value: TACExpr, tag: Tag?): RES

    open fun transformMapDefinition(acc: ACC, exp: TACExpr.MapDefinition): RES =
        transformMapDefinition(acc, exp.defParams, exp.definition, exp.tag)

    abstract fun transformMapDefinition(
        acc: ACC,
        defParams: List<TACExpr.Sym.Var>,
        definition: TACExpr,
        tag: Tag.Map
    ): RES

    abstract fun transformUnconstrained(acc: ACC, exp: TACExpr.Unconstrained): RES

    open fun transformStore(acc: ACC, e: TACExpr.Store): RES =
        transformStore(acc, e.base, e.loc, e.value, e.tag)

    abstract fun transformStore(acc: ACC, base: TACExpr, loc: TACExpr, value: TACExpr, tag: Tag?): RES

    open fun transformLongStore(acc: ACC, e: TACExpr.LongStore): RES =
        transformLongStore(acc, e.dstMap, e.dstOffset, e.srcMap, e.srcOffset, e.length, e.tag)

    abstract fun transformLongStore(
        acc: ACC,
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr,
        tag: Tag?
    ): RES

    open fun transformBinBoolOp(acc: ACC, e: TACExpr.BinBoolOp): RES =
        when (e) {
            is TACExpr.BinBoolOp.LAnd -> transformLAnd(acc, e)
            is TACExpr.BinBoolOp.LOr -> transformLOr(acc, e)
        }

    open fun transformLAnd(acc: ACC, e: TACExpr.BinBoolOp.LAnd): RES =
        transformLAnd(acc, e.ls, e.tag)

    abstract fun transformLAnd(acc: ACC, ls: List<TACExpr>, tag: Tag.Bool?): RES

    open fun transformLOr(acc: ACC, e: TACExpr.BinBoolOp.LOr): RES =
        transformLOr(acc, e.ls, e.tag)

    abstract fun transformLOr(acc: ACC, ls: List<TACExpr>, tag: Tag.Bool?): RES

    open fun transformUnary(acc: ACC, e: TACExpr.UnaryExp): RES =
        when (e) {
            is TACExpr.UnaryExp.BWNot -> transformBWNot(acc, e.o, e.tag)
            is TACExpr.UnaryExp.LNot -> transformLNot(acc, e.o, e.tag)
        }

    @Suppress("unused")
    open fun transformBWNot(acc: ACC, e: TACExpr.UnaryExp.BWNot): RES =
        transformBWNot(acc, e.o, e.tag)

    abstract fun transformBWNot(acc: ACC, e: TACExpr, tag: Tag?): RES

    @Suppress("unused")
    open fun transformLNot(acc: ACC, e: TACExpr.UnaryExp.LNot): RES =
        transformLNot(acc, e.o, e.tag)

    abstract fun transformLNot(acc: ACC, e: TACExpr, tag: Tag.Bool?): RES

    open fun transformTernary(acc: ACC, e: TACExpr.TernaryExp): RES =
        when (e) {
            is TACExpr.TernaryExp.Ite -> transformIte(acc, e)
            is TACExpr.TernaryExp.MulMod -> transformMulMod(acc, e)
            is TACExpr.TernaryExp.AddMod -> transformAddMod(acc, e)
        }

    open fun transformIte(acc: ACC, e: TACExpr.TernaryExp.Ite): RES =
        transformIte(acc, e.i, e.t, e.e, e.tag)

    abstract fun transformIte(acc: ACC, i: TACExpr, t: TACExpr, e: TACExpr, tag: Tag?): RES

    open fun transformMulMod(acc: ACC, e: TACExpr.TernaryExp.MulMod): RES =
            transformMulMod(acc, e.a, e.b, e.n, e.tag)

    abstract fun transformMulMod(acc: ACC, a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): RES

    open fun transformAddMod(acc: ACC, e: TACExpr.TernaryExp.AddMod): RES =
            transformAddMod(acc, e.a, e.b, e.n, e.tag)

    abstract fun transformAddMod(acc: ACC, a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): RES


    open fun transformBinRel(acc: ACC, e: TACExpr.BinRel): RES =
        when (e) {
            is TACExpr.BinRel.Gt -> transformGt(acc, e)
            is TACExpr.BinRel.Lt -> transformLt(acc, e)
            is TACExpr.BinRel.Ge -> transformGe(acc, e)
            is TACExpr.BinRel.Le -> transformLe(acc, e)
            is TACExpr.BinRel.Eq -> transformEq(acc, e)
            is TACExpr.BinRel.Slt -> transformSlt(acc, e)
            is TACExpr.BinRel.Sle -> transformSle(acc, e)
            is TACExpr.BinRel.Sge -> transformSge(acc, e)
            is TACExpr.BinRel.Sgt -> transformSgt(acc, e)
        }

    open fun transformGt(acc: ACC, e: TACExpr.BinRel.Gt): RES =
        transformGt(acc, e.o1, e.o2, e.tag)

    abstract fun transformGt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformLt(acc: ACC, e: TACExpr.BinRel.Lt): RES =
        transformLt(acc, e.o1, e.o2, e.tag)

    abstract fun transformLt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformSlt(acc: ACC, e: TACExpr.BinRel.Slt): RES =
        transformSlt(acc, e.o1, e.o2, e.tag)

    abstract fun transformSlt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformSle(acc: ACC, e: TACExpr.BinRel.Sle): RES =
        transformSle(acc, e.o1, e.o2, e.tag)

    abstract fun transformSle(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformSgt(acc: ACC, e: TACExpr.BinRel.Sgt): RES =
        transformSgt(acc, e.o1, e.o2, e.tag)

    abstract fun transformSgt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformSge(acc: ACC, e: TACExpr.BinRel.Sge): RES =
        transformSge(acc, e.o1, e.o2, e.tag)

    abstract fun transformSge(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformGe(acc: ACC, e: TACExpr.BinRel.Ge): RES =
        transformGe(acc, e.o1, e.o2, e.tag)

    abstract fun transformGe(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformLe(acc: ACC, e: TACExpr.BinRel.Le): RES =
        transformLe(acc, e.o1, e.o2, e.tag)

    abstract fun transformLe(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformEq(acc: ACC, e: TACExpr.BinRel.Eq): RES =
        transformEq(acc, e.o1, e.o2, e.tag)

    abstract fun transformEq(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES

    open fun transformStructAccess(acc: ACC, e: TACExpr.StructAccess): RES =
        transformStructAccess(acc, e.struct, e.fieldName, e.tag)

    abstract fun transformStructAccess(acc: ACC, struct: TACExpr, fieldName: String, tag: Tag?): RES

    abstract fun transformStructConstant(acc: ACC, e: TACExpr.StructConstant, tag: Tag?): RES

    open fun transformArg(acc: ACC, exp: TACExpr, index: Int): RES = transform(acc, exp)

    open fun <@Treapable T : Serializable> transformAnnotationExp(acc: ACC, exp: TACExpr.AnnotationExp<T>): RES =
        transformAnnotationExp(acc, exp.o, exp.annot.k, exp.annot.v)

    abstract fun <T : Serializable> transformAnnotationExp(acc: ACC, o: TACExpr, k : MetaKey<T>, v : T): RES

    open fun transform(acc: ACC, exp: TACExpr): RES = when (exp) {
        is TACExpr.QuantifiedFormula -> transformQuantifiedFormula(acc, exp)
        is TACExpr.Sym -> transformSym(acc, exp)
        is TACExpr.Vec -> transformVec(acc, exp)
        is TACExpr.SimpleHash -> transformSimpleHash(acc, exp)
        is TACExpr.BinOp -> transformBinOp(acc, exp)
        is TACExpr.Apply -> transformApply(acc, exp)
        is TACExpr.Select -> transformSelect(acc, exp)
        is TACExpr.MultiDimStore -> transformMultiDimStore(acc, exp)
        is TACExpr.MapDefinition -> transformMapDefinition(acc, exp)
        is TACExpr.Store -> transformStore(acc, exp)
        is TACExpr.LongStore -> transformLongStore(acc, exp)
        is TACExpr.Unconstrained -> transformUnconstrained(acc, exp)
        is TACExpr.BinBoolOp -> transformBinBoolOp(acc, exp)
        is TACExpr.BinRel -> transformBinRel(acc, exp)
        is TACExpr.UnaryExp -> transformUnary(acc, exp)
        is TACExpr.TernaryExp -> transformTernary(acc, exp)
        is TACExpr.StructAccess -> transformStructAccess(acc, exp)
        is TACExpr.StructConstant -> transformStructConstant(acc, exp, exp.tag)
        is TACExpr.AnnotationExp<*> -> transformAnnotationExp(acc, exp)
    }
}

/**
 * Does not recurse into the meta-maps attached to variables and the annotations of [TACExpr.AnnotationExp].
 */
abstract class AccumulatingTACExprTransFormerWithDefaultRes<ACC, RES> : AccumulatingTACExprTransformer<ACC, RES>() {
    abstract fun defaultRes(acc: ACC, e: TACExpr): RES
    override fun transformQuantifiedFormula(
        acc: ACC,
        isForall: Boolean,
        quantifiedVars: List<TACSymbol.Var>,
        body: TACExpr,
        tag: Tag?
    ): RES = defaultRes(acc, TACExpr.QuantifiedFormula(isForall, quantifiedVars, body, tag))

    override fun transformVar(acc: ACC, exp: TACExpr.Sym.Var): RES = defaultRes(acc, exp)
    override fun transformConst(acc: ACC, exp: TACExpr.Sym.Const): RES = defaultRes(acc, exp)
    override fun transformVecMul(acc: ACC, ls: List<TACExpr>, tag: Tag.Bits?): RES =
        defaultRes(acc, TACExpr.Vec.Mul(ls, tag))
    override fun transformVecIntMul(acc: ACC, ls: List<TACExpr>, tag: Tag.Int?): RES =
        defaultRes(acc, TACExpr.Vec.IntMul(ls, tag))
    override fun transformVecIntAdd(acc: ACC, ls: List<TACExpr>, tag: Tag.Int?): RES =
        defaultRes(acc, TACExpr.Vec.IntAdd(ls, tag))
    override fun transformVecAdd(acc: ACC, ls: List<TACExpr>, tag: Tag.Bits?): RES =
        defaultRes(acc, TACExpr.Vec.Add(ls, tag))
    override fun transformSimpleHash(
        acc: ACC,
        length: TACExpr,
        args: List<TACExpr>,
        hashFamily: HashFamily,
        tag: Tag?
    ): RES =
        defaultRes(acc, TACExpr.SimpleHash(length, args, hashFamily))
    override fun transformSignExtend(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.BinOp.SignExtend(o1, o2, tag))
    override fun transformIntSub(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): RES =
        defaultRes(acc, TACExpr.BinOp.IntSub(o1, o2, tag))
    override fun transformSub(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES =
        defaultRes(acc, TACExpr.BinOp.Sub(o1, o2, tag))
    override fun transformDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES =
        defaultRes(acc, TACExpr.BinOp.Div(o1, o2, tag))
    override fun transformSDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES =
        defaultRes(acc, TACExpr.BinOp.SDiv(o1, o2, tag))
    override fun transformIntDiv(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): RES =
        defaultRes(acc, TACExpr.BinOp.IntDiv(o1, o2, tag))
    override fun transformMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES =
        defaultRes(acc, TACExpr.BinOp.Mod(o1, o2, tag))
    override fun transformSMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES =
        defaultRes(acc, TACExpr.BinOp.SMod(o1, o2, tag))
    override fun transformIntMod(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): RES =
        defaultRes(acc, TACExpr.BinOp.IntMod(o1, o2, tag))
    override fun transformExponent(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bits?): RES =
        defaultRes(acc, TACExpr.BinOp.Exponent(o1, o2, tag))
    override fun transformIntExponent(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Int?): RES =
        defaultRes(acc, TACExpr.BinOp.IntExponent(o1, o2, tag))
    override fun transformBWAnd(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.BinOp.BWAnd(o1, o2, tag))
    override fun transformBWOr(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.BinOp.BWOr(o1, o2, tag))
    override fun transformBWXor(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.BinOp.BWXOr(o1, o2, tag))
    override fun transformShiftLeft(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.BinOp.ShiftLeft(o1, o2, tag))
    override fun transformShiftRightLogical(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.BinOp.ShiftRightLogical(o1, o2, tag))
    override fun transformShiftRightArithmetical(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.BinOp.ShiftRightArithmetical(o1, o2, tag))
    override fun transformApply(acc: ACC, f: TACExpr.TACFunctionSym, ops: List<TACExpr>, tag: Tag?): RES =
        defaultRes(acc, TACExpr.Apply(f, ops, tag))
    override fun transformSelect(acc: ACC, base: TACExpr, loc: TACExpr, tag: Tag?): RES =
        defaultRes(acc ,TACExpr.Select(base, loc, tag))
    override fun transformMultiDimStore(acc: ACC, base: TACExpr, locs: List<TACExpr>, value: TACExpr, tag: Tag?): RES =
        defaultRes(acc ,TACExpr.MultiDimStore(base, locs, value, tag))
    override fun transformMapDefinition(
        acc: ACC,
        defParams: List<TACExpr.Sym.Var>,
        definition: TACExpr,
        tag: Tag.Map
    ): RES = defaultRes(acc ,TACExpr.MapDefinition(defParams, definition, tag))
    override fun transformUnconstrained(acc: ACC, exp: TACExpr.Unconstrained): RES =
        defaultRes(acc, exp)
    override fun transformStore(acc: ACC, base: TACExpr, loc: TACExpr, value: TACExpr, tag: Tag?): RES =
        defaultRes(acc ,TACExpr.Store(base, loc, value, tag))
    override fun transformLongStore(
        acc: ACC,
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr,
        tag: Tag?
    ): RES = defaultRes(acc ,TACExpr.LongStore(dstMap, dstOffset, srcMap, srcOffset, length, tag))
    override fun transformLAnd(acc: ACC, ls: List<TACExpr>, tag: Tag.Bool?): RES =
        defaultRes(acc ,TACExpr.BinBoolOp.LAnd(ls, tag))
    override fun transformLOr(acc: ACC, ls: List<TACExpr>, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinBoolOp.LOr(ls, tag))
    override fun transformBWNot(acc: ACC, e: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.UnaryExp.BWNot(e, tag))
    override fun transformLNot(acc: ACC, e: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.UnaryExp.LNot(e, tag))
    override fun transformIte(acc: ACC, i: TACExpr, t: TACExpr, e: TACExpr, tag: Tag?): RES =
        defaultRes(acc, TACExpr.TernaryExp.Ite(i, t, e, tag))
    override fun transformMulMod(acc: ACC, a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): RES =
            defaultRes(acc, TACExpr.TernaryExp.MulMod(a, b, n, tag))
    override fun transformAddMod(acc: ACC, a: TACExpr, b: TACExpr, n: TACExpr, tag: Tag?): RES =
            defaultRes(acc, TACExpr.TernaryExp.AddMod(a, b, n, tag))
    override fun transformGt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Gt(o1, o2, tag))
    override fun transformLt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Lt(o1, o2, tag))
    override fun transformSlt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Slt(o1, o2, tag))
    override fun transformSle(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Sle(o1, o2, tag))
    override fun transformSgt(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Sgt(o1, o2, tag))
    override fun transformSge(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Sge(o1, o2, tag))
    override fun transformGe(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Ge(o1, o2, tag))
    override fun transformLe(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Le(o1, o2, tag))
    override fun transformEq(acc: ACC, o1: TACExpr, o2: TACExpr, tag: Tag.Bool?): RES =
        defaultRes(acc, TACExpr.BinRel.Eq(o1, o2, tag))
    override fun transformStructAccess(acc: ACC, struct: TACExpr, fieldName: String, tag: Tag?): RES =
        defaultRes(acc, TACExpr.StructAccess(struct, fieldName, tag))
    override fun transformStructConstant(acc: ACC, e: TACExpr.StructConstant, tag: Tag?): RES =
        defaultRes(acc, e)
    override fun <@Treapable T : Serializable> transformAnnotationExp(acc: ACC, o: TACExpr, k: MetaKey<T>, v: T): RES =
        defaultRes(acc, TACExpr.AnnotationExp(o, k, v))
}
