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

package spec.cvlast

import algorithms.transitiveClosure
import com.certora.collect.*
import datastructures.stdcollections.*
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.BadSatisfy
import spec.cvlast.typechecker.CVLAstTypeChecker
import spec.cvlast.typechecker.CVLError
import spec.rules.CVLSingleRule
import utils.CollectingResult
import utils.CollectingResult.Companion.bind
import utils.ErrorCollector.Companion.collectingErrors
import utils.Range
import utils.removeLast

/**
 * A compile time checker to verify `satisfy` statements only appear in legal
 * places (rules or function called from rules).
 * This works using contexts, and when an error is found the context under which
 * there was an error is used in the reporting.
 * An example of a context is "all the code in <exp> for `assert <exp>`" or
 * "all the code in a preserved block".
 *
 * The checker is an AstTransformer that uses inner transformers to collect
 * errors when encountering satisfy statements in a forbidden context such as
 * under a forall quantifier that calls a function containing satisfy.
 */
class SatisfyInRuleChecker : CVLAstTransformer<CVLError> {
    private val checker: CheckSatisfyUsage
    private val satisfyFuncs: List<DeclarationName>

    private constructor(
        satisfyFuncs: List<DeclarationName>,
        checker: CheckSatisfyUsage
    ) : super(CVLCmdChecker(checker)) {
        this.checker = checker
        this.satisfyFuncs = satisfyFuncs
    }

    companion object {
        private fun collectSatisfyCalls(block: List<CVLCmd>): List<CVLCmd.Simple.Satisfy> {
            return block.flatMap { it.getSubCmdsOfType() }
        }
    }

    constructor(satisfyFuncs: List<DeclarationName>) : this(satisfyFuncs, CheckSatisfyUsage(satisfyFuncs))

    constructor(ast: CVLAst) : this(ast.run {
        val callRelation = transitiveClosure(
            reflexive = false,
            graph = subs.associate {
                it.declarationId to it.block.fold(treapSetOf(), CVLAstTypeChecker.invokedCVLDeclarationsExtractor::cmd)
            }
        )
        val satisfyFuncs = subs.filter { collectSatisfyCalls(it.block).isNotEmpty() }
            .map { it.declarationId }
            .let { sFuncIds ->
                sFuncIds + callRelation.filter { f -> f.value.any { it in sFuncIds } }.keys
            }
        satisfyFuncs
    })

    /**
     * An expression transformer to disallow calls to functions containing satisfy in
     * [CheckSatisfyUsage.Config.Disallowed] contexts.This will also add [CheckSatisfyUsage.Config.Disallowed] on top
     * of the current config when encountering quantifiers.
     */
    private class CheckSatisfyUsage(val satisfyFuncs: List<DeclarationName>) : CVLExpTransformer<CVLError> {
        sealed class Config {
            object Allowed : Config()
            data class Disallowed(val context: String, val location: Range) : Config()
        }

        // Default is disallowed for a general call
        private val configs: MutableList<Config> =
            mutableListOf(Config.Disallowed("this location.", Range.Empty("unspecified location")))

        fun <R> withConfig(newConfig: Config, actions: () -> R): R {
            this.configs.add(newConfig)
            val res = actions()
            this.configs.removeLast()
            return res
        }

        override fun expr(exp: CVLExp): CollectingResult<CVLExp, CVLError> {
            check(configs.size > 0) {
                "Expecting type check to set whether satisfy is allowed or not " +
                    "ahead of time."
            }
            return super.expr(exp)
        }

        override fun definition(exp: CVLExp.ApplyExp.Definition): CollectingResult<CVLExp, CVLError> {
            // All definitions function calls should have been inlined in a previous compilation step
            check(false) {"Expecting definitions to have been inlined"}
            return super.definition(exp)
        }

        override fun call(exp: CVLExp.ApplyExp.CVLFunction): CollectingResult<CVLExp.ApplyExp.CVLFunction, CVLError> {
            check(configs.size > 0) {
                "Expecting type check to set whether satisfy is allowed or not ahead of time."
            }
            return super.call(exp).bind {
                collectingErrors {
                    when (val conf = configs.last()) {
                        Config.Allowed -> {} // Do nothing
                        is Config.Disallowed ->
                            if (exp.id in satisfyFuncs) {
                                collectError(
                                    BadSatisfy(
                                        exp.getRangeOrEmpty(),
                                        conf.context + " (${conf.location}). Satisfy was called in the " +
                                            "function '${exp.callableName}'"
                                    )
                                )
                            }
                    }
                    it
                }
            }
        }

        override fun quant(exp: CVLExp.QuantifierExp): CollectingResult<CVLExp, CVLError> {
            return withConfig(Config.Disallowed("quantified expressions", exp.getRangeOrEmpty())) {
                super.quant(exp)
            }
        }
    }

    /**
     * A CVLCmd transformer to disallow additional contexts for [CheckSatisfyUsage].
     * This will add [CheckSatisfyUsage.Config.Disallowed] on top of the current config when encountering: asserts,
     * requires (including in invariants and havocs), and satisfy.
     * This also checks all command blocks for direct usage of satisfy in disallowed locations
     */
    private class CVLCmdChecker(val exprTransformer: CheckSatisfyUsage) : CVLCmdTransformer<CVLError>(exprTransformer) {
        override fun assertCmd(cmd: CVLCmd.Simple.Assert): CollectingResult<CVLCmd, CVLError> {
            return exprTransformer.withConfig(CheckSatisfyUsage.Config.Disallowed("assert statement", cmd.range)) {
                super.assertCmd(cmd)
            }
        }

        override fun satisfyCmd(cmd: CVLCmd.Simple.Satisfy): CollectingResult<CVLCmd, CVLError> {
            return exprTransformer.withConfig(
                CheckSatisfyUsage.Config.Disallowed(
                    "nested satisfy statement",
                    cmd.range
                )
            ) {
                super.satisfyCmd(cmd)
            }
        }

        override fun assumeCmd(cmd: CVLCmd.Simple.AssumeCmd.Assume): CollectingResult<CVLCmd, CVLError> {
            return exprTransformer.withConfig(CheckSatisfyUsage.Config.Disallowed("require statement", cmd.range)) {
                super.assumeCmd(cmd)
            }
        }

        override fun assumeInv(cmd: CVLCmd.Simple.AssumeCmd.AssumeInvariant): CollectingResult<CVLCmd, CVLError> {
            return exprTransformer.withConfig(CheckSatisfyUsage.Config.Disallowed("assume statement", cmd.range)) {
                super.assumeInv(cmd)
            }
        }

        override fun havoc(cmd: CVLCmd.Simple.Havoc): CollectingResult<CVLCmd, CVLError> {
            return exprTransformer.withConfig(CheckSatisfyUsage.Config.Disallowed("assume statement", cmd.range)) {
                super.havoc(cmd)
            }
        }

    }

    override fun hook(hook: CVLHook): CollectingResult<CVLHook, CVLError> {
        return collectingErrors<CVLHook, CVLError> {
            collectSatisfyCalls(hook.block).map {
                collectError(BadSatisfy(it.range, "hooks"))
            }
            hook
        }.bind {
            checker.withConfig(CheckSatisfyUsage.Config.Disallowed("hooks", it.range)) {
                super.hook(it)
            }
        }
    }

    override fun rule(rule: CVLSingleRule): CollectingResult<CVLSingleRule, CVLError> {
        return checker.withConfig(CheckSatisfyUsage.Config.Allowed) {
            super.rule(rule)
        }
    }

    override fun sub(sub: CVLFunction): CollectingResult<CVLFunction, CVLError> {
        return checker.withConfig(CheckSatisfyUsage.Config.Allowed) {
            super.sub(sub)
        }
    }

    override fun preserved(preserved: CVLPreserved): CollectingResult<CVLPreserved, CVLError> {
        return collectingErrors<CVLPreserved, CVLError> {
            collectSatisfyCalls(preserved.block).map {
                collectError(BadSatisfy(it.range, "preserved blocks"))
            }
            preserved
        }.bind {
            checker.withConfig(CheckSatisfyUsage.Config.Disallowed("preserved blocks", it.range)) {
                super.preserved(it)
            }
        }
    }

    override fun inv(inv: CVLInvariant): CollectingResult<CVLInvariant, CVLError> {
        return checker.withConfig(CheckSatisfyUsage.Config.Disallowed("invariants", inv.range)) {
            super.inv(inv)
        }
    }

    override fun ghost(ghost: CVLGhostDeclaration): CollectingResult<CVLGhostDeclaration, CVLError> {
        return checker.withConfig(CheckSatisfyUsage.Config.Disallowed("ghosts", ghost.range)) {
            super.ghost(ghost)
        }
    }

    override fun def(def: CVLDefinition): CollectingResult<CVLDefinition, CVLError> {
        // A definition can call a function containing satisfy.
        return checker.withConfig(CheckSatisfyUsage.Config.Allowed) {
            super.def(def)
        }
    }

    override fun importedMethod(importedMethod: ConcreteMethodBlockAnnotation): CollectingResult<ConcreteMethodBlockAnnotation, CVLError> {
        // Let the exp transformer catch bad summarizations
        return checker.withConfig(CheckSatisfyUsage.Config.Disallowed("summaries", importedMethod.range)) {
            super.importedMethod(importedMethod)
        }
    }
}

private typealias DeclarationName = String
