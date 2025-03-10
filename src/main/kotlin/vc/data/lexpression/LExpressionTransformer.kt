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

package vc.data.lexpression

import log.*
import smt.solverscript.functionsymbols.FunctionSymbol
import smt.solverscript.functionsymbols.NonSMTInterpretedFunctionSymbol
import smt.solverscript.functionsymbols.TheoryFunctionSymbol
import tac.MetaMap
import vc.data.LExpression
import vc.data.Quantifier

val logger = Logger(LoggerTypes.LEXPRESSION)

/**
 * Base class for a simple [LExpression] transformer. It provides pre- and post-hook functions for each type of
 * [LExpression] that can be overridden by any concrete transformer. Each hook can return a new [LExpression] or null
 * (which then uses the unchanged input) .
 *
 * We avoid creating intermediate [LExpression.ApplyExpr] and [LExpression.QuantifiedExpr] objects, not only for
 * efficiency reasons, but also because they might only type-check after (a) both the [LExpression] and its arguments
 * have been transformed (i.e. we can not create the old [LExpression] with the new arguments) or (b) we have applied
 * multiple transformers that build on each other, but are separated for architecture reasons.
 * To avoid intermediate [LExpression] objects, we use [IntermediateLExp] instead.
 *
 * The general transformer workflow is as follows:
 * - turn [LExpression] into an [IntermediateLExp]
 * - call [preHook]
 * - call [recurse] (on [ApplyExpr] or [QuantifierExpr])
 * - call [postHook]
 * - turn back into an [LExpression]
 * Note that a hook may change the type of the [LExpression] (or rather the [IntermediateLExp]) and subsequent hooks
 * will happily work on this changed type. Also, this is the reason why we have pre- and post-hooks for
 * [LExpression.Identifier] and literals: if the pre-hook changes it to, e.g., an [ApplyExpr], the transformer then
 * recurses on the result. If the same happens in the post-hook, recursion does not take place.
 */
abstract class PlainLExpressionTransformer(private val name: String? = null) {

    override fun toString() = name ?: super.toString()

    /** Intermediate representation */
    sealed interface IntermediateLExp {
        /** Convert [IntermediateLExp] into an [LExpression] */
        fun toLExpression(): LExpression = when (this) {
            is ApplyExpr -> LExpression.ApplyExpr(f, args, meta)
            is QuantifierExpr -> LExpression.QuantifiedExpr(quantifier, quantifiedVar, body)
            is Terminal -> lexp
        }
    }
    /** Intermediate representation for [LExpression.ApplyExpr] */
    data class ApplyExpr(val f: FunctionSymbol, val args: List<LExpression>, val meta: MetaMap) : IntermediateLExp {
        companion object {
            operator fun invoke(f: FunctionSymbol, vararg args: LExpression) =
                ApplyExpr(f, args.toList(), MetaMap())
            operator fun invoke(f: FunctionSymbol, vararg args: IntermediateLExp) =
                ApplyExpr(f, args.map { it.toLExpression() }, MetaMap())
        }

        /** Getter for single argument of a unary expression */
        val arg: LExpression
            get() = args.also { check(it.size == 1) { "Can only use arg on unary expressions" } }[0]
        /** Getter for first argument of a binary expression */
        val lhs: LExpression
            get() = args.also { check(it.size == 2) { "Can only use lhs on binary expressions" } }[0]
        /** Getter for second argument of a binary expression */
        val rhs: LExpression
            get() = args.also { check(it.size == 2) { "Can only use rhs on binary expressions" } }[1]

        /** Check whether expression is a select expression */
        val isSelect: Boolean
            get() = when (f) {
                is TheoryFunctionSymbol.Array.Select -> true
                is NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiSelect -> true
                else -> false
            }
        /** Check whether expression is a store expression */
        val isStore: Boolean
            get() = when (f) {
                is TheoryFunctionSymbol.Array.Store -> true
                is NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiStore -> true
                else -> false
            }
        /** Getter for map of a select or store expression */
        val map: LExpression
            get() = args.also { check(isSelect || isStore) { "Can only use map on select or store expressions" } }[0]

        /** Getter for location of a single-dimensional select or store expression */
        val loc: LExpression
            get() = when (f) {
                is TheoryFunctionSymbol.Array.Select -> args[1]
                is TheoryFunctionSymbol.Array.Store -> args[1]
                else -> error { "Can only use loc on select or store expressions" }
            }

        /** Getter for locations of a (possibly multidimensional) select or store expression */
        val locs: List<LExpression>
            get() = when {
                isSelect -> args.drop(1)
                isStore -> args.drop(1).dropLast(1)
                else -> error { "Can only use locs on select or store expressions" }
            }

        /** Getter for value of a store expression */
        val value: LExpression
            get() = args.also { check(isStore) { "Can only use value on store expressions" } }.last()
    }

    /** Intermediate representation for [LExpression.QuantifiedExpr] */
    data class QuantifierExpr(
        val quantifier: Quantifier,
        val quantifiedVar: List<LExpression.Identifier>,
        val body: LExpression
    ) : IntermediateLExp
    /** Intermediate representation for other [LExpression]s */
    data class Terminal(val lexp: LExpression) : IntermediateLExp

    /** Convert [LExpression] into an [IntermediateLExp] */
    fun LExpression.lift(): IntermediateLExp = when (this) {
        is LExpression.ApplyExpr -> ApplyExpr(f, args, meta)
        is LExpression.QuantifiedExpr -> QuantifierExpr(quantifier, quantifiedVar, body)
        else -> Terminal(this)
    }

    /** Generic pre-lift hook, executed before we convert an [LExpression] to an [IntermediateLExp] */
    open fun preLiftHook(exp: LExpression): LExpression? = null

    /** Call the appropriate pre-hook */
    open fun preHook(d: IntermediateLExp): IntermediateLExp? = when (d) {
        is ApplyExpr -> applyExprPre(d)
        is QuantifierExpr -> quantifiedExprPre(d)
        is Terminal -> when (d.lexp) {
            is LExpression.Identifier -> identifierPre(d.lexp)
            is LExpression.Literal -> literalPre(d.lexp)
            else -> error("Unexpected terminal: $d")
        }
    }

    /** Call hooks for [Terminal], recurse for [ApplyExpr] and [QuantifierExpr] */
    open fun recurse(d: IntermediateLExp): IntermediateLExp? = when (d) {
        is ApplyExpr -> ApplyExpr(d.f, d.args.map { invoke(it) }, d.meta)
        is QuantifierExpr -> QuantifierExpr(
            d.quantifier,
            d.quantifiedVar.map { invoke(it) as LExpression.Identifier },
            invoke(d.body)
        )
        else -> null
    }

    /** Call the appropriate post-hook */
    open fun postHook(d: IntermediateLExp): IntermediateLExp? = when (d) {
        is ApplyExpr -> applyExprPost(d)
        is QuantifierExpr -> quantifiedExprPost(d)
        is Terminal -> when (d.lexp) {
            is LExpression.Identifier -> identifierPost(d.lexp)
            is LExpression.Literal -> literalPost(d.lexp)
            else -> error("Unexpected terminal: $d")
        }
    }
    protected fun IntermediateLExp?.logProgress(old: IntermediateLExp, name: String, by: PlainLExpressionTransformer? = null) =
        this.also {
            if (this != null && this != old) {
                logger.trace { "by ${by ?: this@PlainLExpressionTransformer}" }
                logger.trace { "$name of $old" }
                logger.trace { "$name -> $this" }
            }
        }

    /** Main entry point. Calls [lift], [preHook], [recurse], [postHook] and [toLExpression] */
    operator fun invoke(exp: LExpression): LExpression =
        exp
            .let { preLiftHook(it) ?: it }
            .lift()
            .let { preHook(it).logProgress(it, "preHook") ?: it }
            .let { recurse(it).logProgress(it, "recurse") ?: it }
            .let { postHook(it).logProgress(it, "postHook") ?: it }
            .toLExpression()

    /** Transform an [ApplyExpr] before recursing. */
    open fun applyExprPre(exp: ApplyExpr): IntermediateLExp? = exp

    /** Transform an [ApplyExpr] after recursing. */
    open fun applyExprPost(exp: ApplyExpr): IntermediateLExp? = exp

    /** Transform an [LExpression.Identifier] before recursing. */
    open fun identifierPre(id: LExpression.Identifier): IntermediateLExp? = id.lift()

    /** Transform an [LExpression.Identifier] after recursing. */
    open fun identifierPost(id: LExpression.Identifier): IntermediateLExp? = id.lift()

    /** Transform an [LExpression.Literal] before recursing. */
    open fun literalPre(lit: LExpression.Literal): IntermediateLExp? = lit.lift()

    /** Transform an [LExpression.Literal] after recursing. */
    open fun literalPost(lit: LExpression.Literal): IntermediateLExp? = lit.lift()
    /** Transform an [QuantifierExpr] before recursing. */
    open fun quantifiedExprPre(exp: QuantifierExpr): IntermediateLExp? = exp

    /** Transform an [QuantifierExpr] after recursing. */
    open fun quantifiedExprPost(exp: QuantifierExpr): IntermediateLExp? = exp
}

/**
 * Extension of [PlainLExpressionTransformer] that provides a [context], which
 * is the original expression (as [IntermediateData] of the current node. This
 * allows to inspect the original data in [postHook], for example.
 */
open class ContextLExpressionTransformer: PlainLExpressionTransformer() {
    protected val contextStack: MutableList<LExpression> = mutableListOf()
    protected val context get() = contextStack.last()

    override fun preLiftHook(exp: LExpression): LExpression? {
        contextStack.add(exp)
        return null
    }
    override fun postHook(d: IntermediateLExp): IntermediateLExp? {
        return super.postHook(d).also { contextStack.removeLast() }
    }
}

/**
 * Combines multiple [PlainLExpressionTransformer] into a single [PlainLExpressionTransformer]. The important idea is
 * that these transformers are not called in sequence on the full input, but instead chained on every sub-expression.
 * Technically speaking, we do not chain the whole transformers but instead chain their individual hook functions.
 */
data class ChainedLExpressionTransformer(
    private val trans: List<PlainLExpressionTransformer>
) : PlainLExpressionTransformer() {
    constructor(vararg transformers: PlainLExpressionTransformer?): this(transformers.filterNotNull())

    override fun preLiftHook(exp: LExpression) =
        trans.fold(exp) { acc, t -> t.preLiftHook(acc) ?: acc }

    override fun preHook(d: IntermediateLExp) =
        trans.fold(d) { acc, t -> t.preHook(acc).logProgress(acc, "preHook", t) ?: acc }

    override fun postHook(d: IntermediateLExp) =
        trans.fold(d) { acc, t -> t.postHook(acc).logProgress(acc, "postHook", t) ?: acc }

    override fun toString(): String = "Chained(${trans.joinToString(", ")})"
}
