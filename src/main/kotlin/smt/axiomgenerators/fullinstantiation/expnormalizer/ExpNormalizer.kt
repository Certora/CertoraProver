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

package smt.axiomgenerators.fullinstantiation.expnormalizer

import log.Logger
import log.LoggerTypes
import smt.axiomgenerators.ConstantComputer
import smt.axiomgenerators.DefType
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.functionsymbols.*
import vc.data.LExpression
import vc.data.lexpression.PlainLExpressionTransformer
import verifier.CreateAxiomGeneratorContainer


/**
 * Executed as a "phase" in `LExpressionToSMTLib`. `LExpressionToSMTLib` takes as input the verification condition (VC)
 * as `LExpressions`, then scans the VC and adds axioms to it, which are also [LExpression]s.
 * [ExpNormalizer] performs transformations on all [LExpression]s that come ouf of this process, i.e. on the VC and the
 * axioms.
 *
 * The transformations here are done in [normalizeRec] and include:
 *  - Transform function symbols from general [FunctionSymbol]s to [TheoryFunctionSymbol]; where the latter have a
 *   direct SMTLib equivalent. This is the branching point where our translation begins to depend on [targetTheory].
 *  - Transform UF-equalities into SMTLib-compatible forall-terms.
 *  - Computing constants.
 *  - Normalizing the operand order of bitwise operations. (Assumed by some axioms.)
 *  - Sorting out Boolean vs Integer terms with according conversions.
 *
 * @param lxf currently used as LExpression factory
 */
abstract class ExpNormalizer(
    val lxf: LExpressionFactory,
    val targetTheory: SmtTheory,
) {
    companion object {
        fun create(
            targetTheory: SmtTheory,
            overflowChecks: CreateAxiomGeneratorContainer.Config.BvOverflowChecks,
            linearizationSelector: (LExpression) -> Boolean = { true },
        ): (LExpressionFactory) -> ExpNormalizer =
            when (targetTheory.arithmeticOperations) {
                SmtTheory.ArithmeticOperations.LinearOnly ->
                    { lExpFactory: LExpressionFactory -> ExpNormalizerIA(lExpFactory, targetTheory) }

                SmtTheory.ArithmeticOperations.NonLinear ->
                    { lExpFactory: LExpressionFactory ->
                        ExpNormalizerIA(
                            lExpFactory,
                            targetTheory,
                            linearizationSelector
                        )
                    }

                SmtTheory.ArithmeticOperations.BitVector ->
                    { lExpFactory: LExpressionFactory ->
                        ExpNormalizerBV(
                            lExpFactory,
                            targetTheory,
                            overflowChecks
                        )
                    }

                SmtTheory.ArithmeticOperations.Any ->
                    throw UnsupportedOperationException("cannot create ExpNormalizer for $targetTheory")
            }
    }

    protected val logger = Logger(LoggerTypes.VC_TO_SMT_CONVERTER)

    /** See [ExpNormalizer] for documentation. */
    fun normalizeRec(exp: LExpression): LExpression =
        functionSymbolNormalizer(exp)
        .let(constComputer::evalRec)
        .transformPost(lxf, ::convertBwExp)
        .let(constComputer::evalRec)
    // not nice to run it twice, but gets rid of some not-nots that
    // otherwise stay in, and the other conversions might rely on the early run (?)

    fun normalizeNoSimplificationRec(exp: LExpression) = functionSymbolNormalizer(exp)

    /**
     * After applying this, the [LExpression]s should be in a form that straightforwardly translates to `SMTExp`s
     *
     * - replace function symbols by uninterpreted versions if necessary
     * - replace Add by IntAdd etc. (applying modulo 2^256)
     * (does not deal with array function symbols, only arithmetical and bitwise)
     */
    abstract val functionSymbolNormalizer: PlainLExpressionTransformer

    private val constComputer = ConstantComputer(lxf, targetTheory)

    /** brings commutative bitwise expressions that involve a constant into a normal form where the constant is always on the left */
    private fun convertBwExp(e: LExpression) =
        when (e) {
            is LExpression.ApplyExpr -> {
                when (e.f) {
                    is AxiomatizedFunctionSymbol.Bitwise.UninterpBwAnd,
                    is AxiomatizedFunctionSymbol.Bitwise.UninterpBwOr,
                    is AxiomatizedFunctionSymbol.Bitwise.UninterpBwXOr -> {
                        // put the const first
                        if (e.rhs.isConst && !e.lhs.isConst) {
                            e.copyArgs(lxf, listOf(e.rhs, e.lhs))
                        } else {
                            e
                        }
                    }

                    else -> e
                }
            }

            else -> e
        }

    abstract fun normalizeFunctionSymbol(fs: FunctionSymbol): FunctionSymbol

    /**
     * Normalizes a function definition using a given normalizer and [normalizeFunctionSymbol] for both the body and the
     * arguments.
     */
    fun normalizeDefineFun(defType: DefType, preNormalizer: (LExpression) -> LExpression): DefType =
        defType.copy(
            id = normalizeFunctionSymbol(defType.id),
            def = functionSymbolNormalizer(preNormalizer(defType.def)),
            args = defType.args.map {
                (functionSymbolNormalizer(preNormalizer(it)) as? LExpression.Identifier) ?: it
            },
        )
}



