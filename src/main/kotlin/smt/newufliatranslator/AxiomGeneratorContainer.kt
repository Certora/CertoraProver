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

package smt.newufliatranslator

import smt.LExpressionToSmtLib
import smt.axiomgenerators.AxiomContainer
import smt.axiomgenerators.DefType
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGeneratorPlainInjectivity
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.AxiomatizedFunctionSymbol
import smt.solverscript.functionsymbols.FunctionSymbol
import smt.solverscript.functionsymbols.traverseQuant
import statistics.SDFeature
import statistics.data.PrettyPrintableStats
import tac.Tag
import vc.data.LExpression
import vc.data.lexpression.PlainLExpressionTransformer
import vc.gen.LExpVCMetaData

/**
 * See documentation on [LExpressionToSmtLib.output] for details on how this is being used.
 *
 * @param allGenerators Note that the ordering of this list might be important, it determines in which order
 *     [generate] and [yield] are called across the generators.
 */
abstract class AxiomGeneratorContainer(
    val lxf: LExpressionFactory,
    val allGenerators: List<AxiomGenerator>,
) {
    init {
        allGenerators.forEach { it.setContainer(this) }
    }

    /** Show the VC's meta data to the axiom generators (not the lexpressions themselves). */
    fun visitVCMetaData(lExpVc: LExpVCMetaData) {
        allGenerators.forEach { it.visitVCMetaData(lExpVc) }
    }

    val stats: List<PrettyPrintableStats> get() = allGenerators.mapNotNull { it.stats }

    abstract val sortOfStorageKeys: Tag

    /** Call [AxiomGenerator.visit] on the contained generators */
    fun visitRec(exp: LExpression) {
        exp.traverseQuant { e, env ->
            allGenerators.forEach { it.visit(e, env) }
        }
    }

    open fun yield(axiomContainer: AxiomContainer) {
        allGenerators.forEach { it.yield(axiomContainer) }
    }

    open val postProcessTransformer: PlainLExpressionTransformer? = null

    /**
     * Is called on each sub-expression in a separate pass after visit and transformExpression were called on each
     * subexpression. Is implemented not by [AxiomGenerator]s, but by [AxiomGeneratorContainer] only (to avoid
     * concurrent transformations through different generators).
     *
     * Note that this postprocessing is applied to all [LExpression]s, both from the VC and the axioms (in contrast to
     * [postVisitTransform]).
     */
    fun postProcessTransform(e: LExpression) = postProcessTransformer?.invoke(e) ?: e

    open val postVisitTransformer: PlainLExpressionTransformer? = null

    /**
     * Is called on each sub-expression right after calling [visitRec] in [LExpressionToSmtLib]. Note this is implemented not
     * by [AxiomGenerator]s, but by [AxiomGeneratorContainer] only (to avoid concurrent transformations through
     * different generators).
     *
     * Current use case: hash axiom generators.
     */
    fun postVisitTransform(e: LExpression) = postVisitTransformer?.invoke(e) ?: e

    /**
     * Allows generators to resolve dependencies between symbols.
     * E.g. if [AxiomatizedFunctionSymbol.UninterpMul] appears in the verification condition, and there is an axiom that
     * constraints [AxiomatizedFunctionSymbol.UninterpMul] and [AxiomatizedFunctionSymbol.UninterpDiv], then we must
     * register [AxiomatizedFunctionSymbol.UninterpDiv] as a required function symbol, too.
     *
     * Another application: define-fun for [AxiomatizedFunctionSymbol.UninterpTagMod] requires
     * [AxiomatizedFunctionSymbol.UninterpMod]
     *
     */
    fun beforeScriptFreeze() = allGenerators.forEach { it.beforeScriptFreeze() }

    open fun yieldDefineFuns(): List<DefType> = allGenerators.flatMap { it.yieldDefineFuns() }

    fun getStatsRecorders(queryName: String): List<SDFeature<*>> =
        allGenerators.flatMap { it.getStatsRecorders(queryName) }

    /**
     * Allows axiom generators to filter out declarations.
     *
     * This fixes crashes we have (e.g. in CustomersCode/keep/keep-ecdsa/solidity/run.sh) because some skey
     * declarations remain that [StorageHashAxiomGeneratorPlainInjectivity] (or ...Legacy) did not flip to int because
     * they don't see them during visit ...
     *
     * I'm not extremely happy with this -- we should think about better symbol management. */
    fun declarationFilter(fs: FunctionSymbol): Boolean {
        return allGenerators.all { gen -> gen.declarationFilter(fs) }
    }

    /**
     * Container-level variant of [AxiomGenerator.transformTermsOfInterest] see there for the intention of this method.
     */
    fun transformTermsOfInterest(lExp: LExpression): LExpression =
        allGenerators.fold(lExp) { exp, axiomGen -> axiomGen.transformTermsOfInterest(exp) }
}


