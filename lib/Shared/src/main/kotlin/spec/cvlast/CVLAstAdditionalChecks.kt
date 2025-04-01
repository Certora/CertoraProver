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

import config.Config
import datastructures.stdcollections.*
import report.CVTAlertType
import spec.CVLKeywords
import spec.CVLWarningLogger
import spec.CastType
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.LastRevertedAfterNonRevertingCall
import spec.cvlast.typechecker.MethodVariableNotInRule
import spec.cvlast.typechecker.QuantifierChecker
import spec.rules.CVLSingleRule
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flattenToVoid
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok
import utils.ErrorCollector.Companion.collectingErrors
import utils.Range
import utils.VoidResult

/**
 * The command transformer for [CVLAstAdditionalChecks]
 */
private class CVLCmdAddtionalChecks: CVLCmdTransformer<CVLError>(
    expTransformer = object : CVLExpTransformer<CVLError> {
        override fun quant(exp: CVLExp.QuantifierExp): CollectingResult<CVLExp, CVLError> {
            return QuantifierChecker.expr(ok, exp.body).bind {
                super.quant(exp)
            }
        }
    }
) {
    /**
     * Verify that this [cmd] isn't a declaration of an [EVMBuiltinTypes.method]-typed variable not in the outermost
     * scope of a rule.
     */
    private fun verifyMethodVariableDeclaration(cmd: CVLCmd.Simple.Declaration): VoidResult<CVLError> {
        if (cmd.cvlType != EVMBuiltinTypes.method) {
            return ok
        }

        val enclosingRuleScopeItem = cmd.scope.enclosingRule()
            ?: return MethodVariableNotInRule(cmd.range).asError()

        if (enclosingRuleScopeItem.isDerivedFromInvariant()) {
            // In order to avoid duplicate errors, we skip the check for rules derived from invariants and
            // instead rely on the check on the invariant itself (which will fail on the previous `if`).
            return ok
        }

        if (enclosingRuleScopeItem != cmd.scope.scopeStack.last()) {
            return MethodVariableNotInRule(cmd.range).asError()
        }

        return ok
    }

    override fun decl(cmd: CVLCmd.Simple.Declaration): CollectingResult<CVLCmd, CVLError> = collectingErrors {
        collect(verifyMethodVariableDeclaration(cmd))

        return@collectingErrors bind(super.decl(cmd))
    }

    override fun def(cmd: CVLCmd.Simple.Definition): CollectingResult<CVLCmd, CVLError> {
        check(cmd.type != EVMBuiltinTypes.method) {
            "The typechecker should have prevented method-typed definitions"
        }

        if (cmd.exp is CVLExp.ApplyExp && cmd.exp is CVLExp.ApplyExp.RevertAnnotatable && !cmd.exp.noRevert) {
            CVLWarningLogger.syntaxWarning("${cmd.exp.callableName} is called with `@withrevert`, it's return values ${cmd.idL} are undefined if this call reverts. " +
                "Make sure to check that `!${CVLKeywords.lastReverted.keyword}` before using them", cmd.range)
        }

        return super.def(cmd)
    }
}

/**
 * Performs additional checks to the ast (that is, in addotion to what the type-checker already does).
 * The main difference is that these checks happen after definition inlining so performing checks that care about the
 * contents of sub-expressions is simpler at this stage - no need to think about possible definitions expressions and
 * their contents
 */
class CVLAstAdditionalChecks(val symbolTable: CVLSymbolTable): CVLAstTransformer<CVLError>(
    cmdTransformer = CVLCmdAddtionalChecks()
) {
    override fun ast(ast: CVLAst): CollectingResult<CVLAst, CVLError> = collectingErrors {
        check(ast.definitions.isEmpty()) {
            "The additional checks here assume all definitions were inlined and removed from the ast"
        }
        return@collectingErrors bind(super.ast(ast))
    }
    /**
     * A method parameter filter statically chooses which methods should be included in a parametric rule. Thus, the
     * filter needs to be statically evaluable to a boolean value. This function represents a conservative attempt to
     * ensure this will be the case at compile time.
     */
    private fun filterExpMustBeBooleanAndClosed(
        filterExp: CVLExp,
        allowedVarsInUse: Set<String>
    ): VoidResult<CVLError> {
        val nonDefinitionApplications = object : CVLExpFolder<List<CVLError>>() {
            private fun badInvokeError(exp: CVLExp): CVLError.Exp {
                // Note that if there are any definitions in the filter expression, an offending invoke exp could
                // actually have a location in some completely different part of the .spec file, which is why we're
                // the error here will print both.
                val invokeExpLocation = exp.getRangeOrEmpty().let { range ->
                    if (range is Range.Empty) {
                        ""
                    } else {
                        " (at $range)"
                    }
                }

                return CVLError.Exp(
                    filterExp,
                    "The filter of the parameter ${allowedVarsInUse.first()} uses the invoke expression $exp$invokeExpLocation. Filter expressions may only invoke definitions"
                )
            }

            override fun applyExp(
                acc: List<CVLError>,
                exp: CVLExp.ApplyExp
            ): List<CVLError> {
                // any apply expression which is not an application of a definition/macro is disallowed
                // inside a method parameter filter
                check(filterExp !is CVLExp.ApplyExp.Definition) {
                    "unexpected definition expression $exp after definition inlining"
                }
                return super.applyExp(acc, exp) + badInvokeError(exp)
            }

            override fun unresolvedApply(
                acc: List<CVLError>,
                exp: CVLExp.UnresolvedApplyExp
            ): List<CVLError> {
                return super.unresolvedApply(acc, exp) + badInvokeError(exp)
            }

            override fun variable(
                acc: List<CVLError>,
                exp: CVLExp.VariableExp
            ): List<CVLError> {
                return acc
            }
        }.expr(mutableListOf(), filterExp)

        return if (nonDefinitionApplications.isNotEmpty()) {
            nonDefinitionApplications.asError()
        } else {
            filterExp.lift()
        }.bind { exp ->
            if (exp.getCVLType() isNotConvertibleTo CVLType.PureCVLType.Primitive.Bool) {
                CVLError.Exp(
                    exp,
                    "Method parameter's filter expressions must be boolean (found ${exp.getCVLType()} type)"
                ).asError()
            } else {
                val symsInFilterExp = object : CVLExpFolder<Set<String>>() {
                    override fun variable(acc: Set<String>, exp: CVLExp.VariableExp): Set<String> =
                        acc + exp.id

                    override fun invokeSymbolicExp(
                        acc: Set<String>,
                        exp: CVLExp.ApplyExp.ContractFunction.Symbolic
                    ): Set<String> =
                        super.invokeSymbolicExp(acc, exp) + exp.methodIdWithCallContext.methodId

                }.expr(emptySet(), exp)

                val allAllowed = allowedVarsInUse + symbolTable.getAllContractAliases()
                if (allAllowed.containsAll(symsInFilterExp)) {
                    ok
                } else {
                    CVLError.Exp(
                        exp, "The method parameter's filter expression uses the symbols ${
                            symsInFilterExp - allAllowed
                        } while it may only use $allAllowed"
                    ).asError()
                }
            }
        }
    }

    private fun checkNoLastRevertedAfterNonRevertingCall(block: List<CVLCmd>): VoidResult<CVLError> = collectingErrors {
        var isSkippedRule = false

        class LastRevertedAfterNonRevertingCallFinder(private var lastCalls: Set<CVLExp.ApplyExp.RevertAnnotatable?>) : CVLCmdFolder<VoidResult<CVLError>>() {
            override fun cvlExp(acc: VoidResult<CVLError>, exp: CVLExp): VoidResult<CVLError> {
                val expFinder = object : CVLExpFolder<VoidResult<CVLError>>() {
                    override fun invokeExp(acc: VoidResult<CVLError>, exp: CVLExp.ApplyExp.ContractFunction): VoidResult<CVLError> {
                        return super.invokeExp(acc, exp).also { lastCalls = setOf(exp) }
                    }

                    override fun unresolvedApply(acc: VoidResult<CVLError>, exp: CVLExp.UnresolvedApplyExp): VoidResult<CVLError> {
                        // Set this flag to short-circuit if the rule references unimplemented functions, to avoid dealing
                        // with UnresolvedApplyExps. This is OK because this rule will be skipped anyway
                        isSkippedRule = true
                        return acc
                    }

                    override fun call(acc: VoidResult<CVLError>, exp: CVLExp.ApplyExp.CVLFunction): VoidResult<CVLError> {
                        if (Config.CvlFunctionRevert.get()) {
                            return super.call(acc, exp).also { lastCalls = setOf(exp) }
                        }
                        // TODO(CERT-7707): once we remove the config condition, we can probably just override applyExp and
                        //  check for being RevertAnnotatable instead of handling ContractFunction and CVLFunction separately
                        return super.call(acc, exp).bind {
                            val cvlFunc = (symbolTable.lookUpFunctionLikeSymbol(exp.id, exp.getScope())?.symbolValue as? CVLFunction) ?: error("The function ${exp.id} should be in the symbol table")

                            cvlFunc.block.fold(ok as VoidResult<CVLError>) { acc, cmd ->
                                if (isSkippedRule) { return@fold acc }

                                this@LastRevertedAfterNonRevertingCallFinder.cmd(acc, cmd)
                            }
                        }
                    }

                    override fun variable(acc: VoidResult<CVLError>, exp: CVLExp.VariableExp): VoidResult<CVLError> {
                        if (exp.id == CVLKeywords.lastReverted.keyword) {
                            if (lastCalls.all { it?.noRevert == true }) {
                                return acc.bind(LastRevertedAfterNonRevertingCall(lastCalls.filterNotNull().toSet(), exp.getRangeOrEmpty()).asError())
                            } else if (lastCalls.any { it?.noRevert == true }) {
                                val problematicCall = lastCalls.first { it?.noRevert == true }!!
                                CVLWarningLogger.warning(CVTAlertType.CVL,
                                    "${exp.getRangeOrEmpty()}: Using `${CVLKeywords.lastReverted.keyword}` after a non-reverting call " +
                                        "$problematicCall (${problematicCall.getRangeOrEmpty()}) could be vacuous. Did you mean to add `@withrevert`?",
                                    exp.getRangeOrEmpty()
                                )
                            }
                        }
                        return acc
                    }
                }
                return expFinder.expr(acc, exp)
            }

            override fun lhs(acc: VoidResult<CVLError>, lhs: CVLLhs): VoidResult<CVLError> = acc

            override fun message(acc: VoidResult<CVLError>, message: String): VoidResult<CVLError>  = acc

            override fun conditional(acc: VoidResult<CVLError>, cmd: CVLCmd.Composite.If): VoidResult<CVLError> {
                val thenFinder = LastRevertedAfterNonRevertingCallFinder(lastCalls)
                val elseFinder = LastRevertedAfterNonRevertingCallFinder(lastCalls)

                val thenRes = thenFinder.cmd(acc, cmd.thenCmd)
                return elseFinder.cmd(thenRes, cmd.elseCmd).also { lastCalls = thenFinder.lastCalls + elseFinder.lastCalls }
            }
        }

        val finder = LastRevertedAfterNonRevertingCallFinder(setOf(null)) // null - no calls yet
        collect(block.fold(ok as VoidResult<CVLError>) { acc, cmd ->
            if (isSkippedRule) { return@fold acc }

            finder.cmd(acc, cmd)
        })
    }

    override fun block(block: List<CVLCmd>): CollectingResult<List<CVLCmd>, CVLError> = collectingErrors {
        collect(checkNoLastRevertedAfterNonRevertingCall(block))

        return@collectingErrors bind(super.block(block))
    }

    override fun inv(inv: CVLInvariant): CollectingResult<CVLInvariant, CVLError> = collectingErrors {
        collect(
            inv.methodParamFilters.methodParamToFilter.map { (methodParamName, filter) ->
                filterExpMustBeBooleanAndClosed(
                    filter.filterExp,
                    setOf(methodParamName)
                )
            }
        )

        return@collectingErrors bind(super.inv(inv))
    }

    override fun rule(rule: CVLSingleRule): CollectingResult<CVLSingleRule, CVLError> = collectingErrors {
        collect(
            rule.methodParamFilters.methodParamToFilter.map { (methodParamName, filter) ->
                filterExpMustBeBooleanAndClosed(
                    filter.filterExp,
                    setOf(methodParamName)
                )
            }
        )

        return@collectingErrors bind(super.rule(rule))
    }

    /**
     * @return [exp] after checking that it contains no contract calls
     * @param [context] is used to construct the error message; it should complete the phrase "$context may not contain ..."
     */
    private fun checkNoContractOrCVLCalls(exp: CVLExp, context: String): VoidResult<CVLError> {
        return object : CVLExpTransformer<CVLError> {
            override fun applyExp(exp: CVLExp.ApplyExp): CollectingResult<CVLExp, CVLError> {
                return if (exp is CVLExp.ApplyExp.ContractFunction || exp is CVLExp.ApplyExp.CVLFunction || exp is CVLExp.ApplyExp.Definition) {
                    CVLError.General(
                        exp.tag.range,
                        "$context may not contain calls to contract or CVL functions, but found a call to ${exp.methodIdWithCallContext}"
                    ).asError()
                } else {
                    ok
                }.map(super.applyExp(exp)) { _, _ -> exp }
            }
        }.expr(exp).bind { ok }
    }

    private fun validateGhostAxiomContents(
        axiom: CVLGhostAxiom,
        ghost: CVLGhostDeclaration,
    ): VoidResult<CVLError>  {
        val noContractCalls = checkNoContractOrCVLCalls(axiom.exp, "axiom for ghost ${ghost.id}")

        val castExps = axiom.exp.subExprsOfType<CVLExp.CastExpr>().filter { castExp ->
            castExp.castType == CastType.ASSERT || castExp.castType == CastType.REQUIRE
        }
        val castExpsInAxiom = if (castExps.isNotEmpty()) {
            castExps.map { castExp ->
                CVLError.Exp(castExp,
                    "${castExp.castType.str}-type casts have side effects! They cannot be used inside a ghost axiom. If casting is required, try upcasting all expressions to mathints"
                )
            }.asError()
        } else {
            ok
        }

        return flattenToVoid(noContractCalls, castExpsInAxiom)
    }

    override fun ghost(ghost: CVLGhostDeclaration): CollectingResult<CVLGhostDeclaration, CVLError> = collectingErrors {
        collect((ghost as? CVLGhostWithAxiomsAndOldCopy)?.axioms?.map { validateGhostAxiomContents(it, ghost) } ?: listOf(ok))

        return@collectingErrors bind(super.ghost(ghost))
    }
}
