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

package spec

import bridge.*
import config.Config
import config.Config.MethodChoices
import datastructures.stdcollections.*
import scene.MethodAttribute
import spec.cvlast.*
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.ContractChoiceNoSuchContract
import spec.cvlast.typechecker.MethodVariableTooManyContracts
import spec.cvlast.typechecker.NoValidInstantiationsLeft
import spec.genericrulegenerators.BuiltInRuleId
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.safeForce
import utils.ErrorCollector.Companion.collectingErrors
import utils.mapValuesNotNull
import java.math.BigInteger

class CalculateMethodParamFilters(
    private val primaryContract: SolidityContract,
    private val contracts: List<ContractInstanceInSDC>,
    private val symbolTable: CVLSymbolTable
) {
    /**
     * For each rule and method-typed argument of that rule, calculate the list of methods that should be instantiated
     * given the method-filter
     * @return a [Pair] of:
     * 1. a list of the rules that weren't filtered out completely (e.g. generic preserved rules when there are explicit
     *    preserves for all relevant functions)
     * 2. a mapping rule -> methodArgName -> list of filtered-in methods
     *
     */
    fun calculate(rules: List<IRule>): CollectingResult<Pair<List<IRule>, Map<RuleIdentifier, Map<String, List<Method>>>>, CVLError> = collectingErrors {
        // Validate the `-contract` config flag which is used by this class
        Config.contractChoice.get()
            .filter { contract -> contract !in contracts.map { it.name }}
            .forEach { collectError(ContractChoiceNoSuchContract(it)) }

        val filters = bind(calculateRecursive(rules))

        // filter out parametric rules that have no instantiations (e.g. generic preserved rules when there
        // are explicit preserves for all relevant functions). Preserve the rule topology while doing so.
        fun filterRules(rules: List<IRule>): List<IRule> {
            return rules.mapNotNull { rule ->
                when (rule) {
                    is GroupRule -> rule.copy(rules = filterRules(rule.rules)).let {
                        if (it.rules.isNotEmpty()) {
                            it
                        } else {
                            if (it.parentIdentifier == null) {
                                // output a warning for top-level rules/invariants that are being skipped
                                val ruleType = if (it.ruleType is SpecType.Group.InvariantCheck) {
                                    "invariant"
                                } else {
                                    "rule"
                                }
                                CVLWarningLogger.generalWarning("Skipping $ruleType ${it.declarationId} since all its subrules are skipped")
                            }
                            null
                        }
                    }
                    is CVLSingleRule -> rule.takeIf { filters[it.ruleIdentifier] != null }?.removeUnusedMethodParams()
                    is AssertRule,
                    is StaticRule -> rule
                }
            }
        }

        return@collectingErrors filterRules(rules) to filters.mapValuesNotNull { it.value }
    }

    private fun calculateRecursive(rules: List<IRule>): CollectingResult<Map<RuleIdentifier, Map<String, List<Method>>?>, CVLError> {
        return rules.map { rule ->
            when (rule) {
                is GroupRule -> calculateRecursive(rule.rules)
                is CVLSingleRule -> getMethods(rule).map { mapOf(rule.ruleIdentifier to it) }
                is AssertRule,
                is StaticRule -> mapOf<RuleIdentifier, Map<String, List<Method>>>().lift()
            }
        }.flatten().map { it.fold(mapOf()) { acc, m -> acc + m } }
    }

    /**
     * Returns a mapping from method parameter names to a list of [Method]s that pass the relevant filter.
     * In case of a non-parametric rule the function will return an empty map.
     * In case of a parametric rule for which it's allowed to have all methods filtered out (e.g. an invariant's generic
     * preserved) this function returns null.
     */
    private fun getMethods(rule: CVLSingleRule): CollectingResult<Map<String, List<Method>>?, CVLError> {
        val methodArgs = rule.params.filter { it.type == EVMBuiltinTypes.method }
        if (methodArgs.isEmpty()) {
            // non-parametric rule
            return mapOf<String, List<Method>>().lift()
        }

        // Check if the user wrote something like `filtered { f -> false }` for any method param, which implies they're
        // aware that no function will match the filter.
        // In such a case skip this rule with a warning instead of failing later when we discover there are no valid
        // instantiations.
        methodArgs.forEach { methodArg ->
            rule.methodParamFilters[methodArg.id]?.let { methodParamFilter ->
                if (methodParamFilter.filterExp.eval()?.n == BigInteger.ZERO) {
                    // The filter expression isn't dependent on the method param and always evaluates to 0.
                    CVLWarningLogger.generalWarning(
                        "Skipping rule ${rule.declarationId} - trivial method filter filters out all methods"
                    )
                    return null.lift()
                }
            }
        }

        val emptyRange = CVLRange.Empty("internal method param filters")
        var allFilters = rule.methodParamFilters

        // if the `ignoreViewFunctions` flag is set, then add this filter to all methodParams that don't have an explicit filter
        if (Config.IgnoreViewFunctionsInParametricRules.get()) {
            methodArgs.filter { it.id !in allFilters.methodParamToFilter }.map { methodParam ->
                val methodParamAsVarExp = CVLExp.VariableExp(methodParam.id, CVLExpTag(allFilters.scope, EVMBuiltinTypes.method, CVLRange.Empty("")))
                methodParam.id to MethodParamFilter.ignoreViewMethodsFilter(methodParamAsVarExp, emptyRange, allFilters.scope)
            }.toMap().let { trivialFilters ->
                val filters = MethodParamFilters(emptyRange, allFilters.scope, trivialFilters)
                allFilters = MethodParamFilters.conjunct(allFilters.cvlRange, allFilters.scope, allFilters, filters)
            }
        }

        // Now add a trivial accept-all filter to all method-params that don't have any filter defined yet.
        // This way the rest of the code can assume that all method parameters exist as keys to the methodParamFilters map.
        methodArgs.filter { it.id !in allFilters.methodParamToFilter }.map { methodParam ->
            val methodParamAsVarExp = CVLExp.VariableExp(methodParam.id, CVLExpTag(allFilters.scope, EVMBuiltinTypes.method, CVLRange.Empty("")))
            methodParam.id to MethodParamFilter.acceptAllMethodsFilter(methodParamAsVarExp, emptyRange, allFilters.scope)
        }.toMap().let { trivialFilters ->
            val filters = MethodParamFilters(emptyRange, allFilters.scope, trivialFilters)
            allFilters = MethodParamFilters.conjunct(allFilters.cvlRange, allFilters.scope, allFilters, filters)
        }

        // Finally, construct the mapping of methodParam to the list of functions that pass its filter
        val methodArgToFilteredMethods = methodArgs.map { methodArg ->
            val methodParamFilter = allFilters[methodArg.id]
            check(methodParamFilter != null) {
                "all method params should have filters at this stage (even if only the trivial filter)"
            }

            // Get the set of hostnames used for methodArg. A set larger than a singleton is an error which we catch below.
            val host = getMethodParamHosts(rule, methodArg.id).let { methodParamHosts ->
                if (methodParamHosts.size > 1) {
                    return MethodVariableTooManyContracts(rule.cvlRange, rule.declarationId, methodArg.id, methodParamHosts.map { it.name }).asError()
                } else {
                    methodParamHosts.singleOrNull() ?: AllContracts // can be null if the method is never called :/
                }
            }

            // Generate the initial list of methods to work with
            val methodsToFilter = contracts
                .filter { contract -> !contract.isLibrary } // Filter out libraries
                .filter { contract -> host is AllContracts || host.name == contract.name } // Filter contracts according to possible syntax sugar `contractAlias.f`
                .filter { contract -> Config.contractChoice.get().let { it.isEmpty() || contract.name in it } } // Filter contracts according to the optional `-contract` flag
                .filterNot { contract -> contracts.isExtensionContract(contract) } // Filter out any contract that's actually an extension of some other contract(s).
                .associateWith { contract -> contract.getAllDeclaredMethods() + getFallbackMethod(contract.name) }

            // Generate the filtered list of methods
            methodsToFilter.flatMap { (contract, methods) ->
                methods.mapNotNull methods@{ method ->
                    method.toMethodSignature(primaryContract, Visibility.EXTERNAL).let { methodSig ->
                        if (methodSig is UniqueMethod && methodSig.attribute == MethodAttribute.Unique.Constructor) {
                            // Ignore the constructor
                            return@methods null
                        }
                    }

                    // Replace the methodArg variables within the filter expression with a SignatureLiteralExp that represents
                    // [method], so that we can statically evaluate the filter right here.
                    val symbolicMethodReplacer = SymbolicMethodReplacer(methodArg.id, method, contract)
                    symbolicMethodReplacer.expr(methodParamFilter.filterExp).map { replacedExp ->
                        val evaluatedFilter = replacedExp.eval()
                            ?: error("It should be possible to evaluate any filter at this stage, but failed to evaluate the filter '${methodParamFilter.filterExp}'")
                        if (evaluatedFilter.n == BigInteger.ZERO) {
                            null
                        } else {
                            // If `-method` was provided, it's not enough to pass the filter, we must check it's one of the
                            // requested methods as well.
                            if (MethodChoices.containsMethod(method.getABIString(), contract.name, primaryContract.name)) {
                                method
                            } else {
                                null
                            }
                        }
                    }.safeForce()?.lift()
                }
            }.flatten().safeForce().let {
                mapOf(methodArg.id to it)
            }
        }.fold(mapOf<String, List<Method>>()) { acc, m -> acc + m }

        return collectingErrors {
            methodArgToFilteredMethods.mapNotNull { (methodArg, methods) ->
                if (methods.isEmpty()) {
                    val ruleType = rule.ruleType
                    when {
                        ruleType is SpecType.Single.InvariantCheck.GenericPreservedInductionStep ->
                            CVLWarningLogger.generalWarning(
                                "The generic preserved block for the invariant ${rule.parentIdentifier!!}" +
                                    " does not apply to any method"
                            )
                        ruleType is SpecType.Single.BuiltIn && ruleType.birId == BuiltInRuleId.msgValueInLoopRule ->
                            CVLWarningLogger.generalWarning(
                                "No payable methods were found, skipping rule ${rule.declarationId}"
                            )
                        ruleType is SpecType.Single.FromUser && MethodChoices != null ->

                            CVLWarningLogger.generalWarning(
                                "The rule ${rule.declarationId} " +
                                    "does not apply to $MethodChoices (it was filtered out by the method parameter's filter), " +
                                    "skipping this rule"
                            )
                        else ->
                            collectError(NoValidInstantiationsLeft(rule.cvlRange, methodArg, rule.declarationId))
                    }
                    null
                } else {
                    methodArg to methods
                }
            }.toMap().ifEmpty { null }
        }
    }

    inner class SymbolicMethodReplacer(private val methodArgName: String, private val method: Method, private val contract: ContractInstanceInSDC) : CVLExpTransformer<Nothing> {
        /**
         * Replace expressions of the sort `f.selector` with `method().selector`
         */
        override fun fieldSel(exp: CVLExp.FieldSelectExp): CollectingResult<CVLExp.FieldSelectExp, Nothing> {
            return super.fieldSel(exp).map {
                if (exp.structExp is CVLExp.VariableExp && exp.structExp.id == methodArgName) {
                    exp.copy(
                        structExp = CVLExp.Constant.SignatureLiteralExp(
                            function = method.toMethodSignature(primaryContract, Visibility.EXTERNAL),
                            tag = CVLExpTag(
                                exp.getScope(),
                                EVMBuiltinTypes.method,
                                exp.getRangeOrEmpty(),
                                annotation = EVMExternalMethodInfo.fromMethodAndContract(method, contract)
                                    .toCvlStructLit(exp.getScope())
                            )
                        ),
                    )
                } else {
                    exp
                }
            }
        }

        override fun applyExp(exp: CVLExp.ApplyExp): CollectingResult<CVLExp, Nothing> {
            error("This should have been prevented already")
        }
    }

    /**
     * @return the set of contracts that are mentioned as the contracts of [methodArgName] (e.g. `C` from `C.f(e, args)`) in [rule]
     */
    private fun getMethodParamHosts(rule: CVLSingleRule, methodArgName: String): Set<ContractReference> {
        class CmdUsageFinder : CVLCmdFolder<Set<ContractReference>>() {
            override fun cvlExp(acc: Set<ContractReference>, exp: CVLExp): Set<ContractReference> =
                object: CVLExpFolder<Set<ContractReference>>() {
                    override fun variable(acc: Set<ContractReference>, exp: CVLExp.VariableExp): Set<ContractReference> = acc

                    override fun invokeSymbolicExp(
                        acc: Set<ContractReference>,
                        exp: CVLExp.ApplyExp.ContractFunction.Symbolic
                    ): Set<ContractReference> {
                        return if (exp.callableName == methodArgName) {
                            super.invokeSymbolicExp(acc, exp) + exp.methodIdWithCallContext.host
                        } else {
                            super.invokeSymbolicExp(acc, exp)
                        }
                    }

                    override fun call(acc: Set<ContractReference>, exp: CVLExp.ApplyExp.CVLFunction): Set<ContractReference> {
                        // Need to search recursively into functions
                        val func = symbolTable.lookUpFunctionLikeSymbol(exp.callableName, rule.scope)?.symbolValue as? CVLFunction
                            ?: error("${exp.callableName} should have been in the symbol table")

                        return super.call(acc, exp) + func.block.fold(setOf()) { a, cmd ->
                            CmdUsageFinder().cmd(a, cmd)
                        }
                    }
                }.expr(acc, exp)

            override fun lhs(acc: Set<ContractReference>, lhs: CVLLhs): Set<ContractReference> = acc
            override fun message(acc: Set<ContractReference>, message: String): Set<ContractReference> = acc
        }

        return rule.block.fold(setOf()) { acc, cmd ->
            CmdUsageFinder().cmd(acc, cmd)
        }
    }

    /**
     * Returns a copy of the rule with all unused method param arguments removed from it (while generating a warning).
     * This avoids unnecessarily instantiating subrules for each method.
     */
    private fun CVLSingleRule.removeUnusedMethodParams(): CVLSingleRule {
        val methodArgs = this.params.filter { it.type == EVMBuiltinTypes.method }

        val methodArgUsageFinder = object : CVLCmdFolder<Set<CVLParam>>() {
            val methodArgIds = methodArgs.map { it.id to it }.toMap()
            override fun cvlExp(acc: Set<CVLParam>, exp: CVLExp): Set<CVLParam> {
                return object : CVLExpFolder<Set<CVLParam>>() {
                    override fun variable(acc: Set<CVLParam>, exp: CVLExp.VariableExp): Set<CVLParam> =
                        if (exp.id in methodArgIds.keys) {
                            acc + methodArgIds[exp.id]!!
                        } else {
                            acc
                        }

                    override fun invokeSymbolicExp(
                        acc: Set<CVLParam>,
                        exp: CVLExp.ApplyExp.ContractFunction.Symbolic
                    ): Set<CVLParam> =
                        if (exp.callableName in methodArgIds.keys) {
                            acc + methodArgIds[exp.callableName]!!
                        } else {
                            acc
                        }
                }.expr(acc, exp)
            }

            override fun lhs(acc: Set<CVLParam>, lhs: CVLLhs): Set<CVLParam> = acc

            override fun message(acc: Set<CVLParam>, message: String): Set<CVLParam> = acc
        }
        val usedMethodArgs = this.block.fold(setOf<CVLParam>()) { acc, cmd -> methodArgUsageFinder.cmd(acc, cmd) }

        val unusedMethodArgs = (methodArgs - usedMethodArgs.toSet()).toSet()
        unusedMethodArgs.forEach { methodArg ->
            CVLWarningLogger.syntaxWarning("The method parameter ${methodArg.id} is declared but unused. Ignoring...", this.cvlRange)
        }

        return this.copy(params = this.params - unusedMethodArgs)
    }

    companion object {
        fun String.splitToContractAndMethod(defaultContract: String): Pair<String, String> {
            val (contract, method) = this.splitOnce(".")
                ?: return defaultContract to this

            @Suppress("ForbiddenMethodCall")
            check(!method.contains(".")) { "Expected `contract.method(...)`, got $this" }

            return contract to method
        }

        /**
         * [this] is assumed to be a set of method signatures, with or without an explicit hostname (or `_` to represent
         * a wildcard contract).
         *
         * If [this] is `null` - returns true.
         *
         * Otherwise, returns whether the provided [methodSig] from contract [contract] is in the set.
         * [methodSig] should be the ABI representation of a method.
         * Signatures in [this] that don't have an explicit host name are assumed to belong to the [mainContract]
         */
        fun Set<String>?.containsMethod(methodSig: String, contract: String, mainContract: String): Boolean {
            if (this == null) {
                return true
            }

            return this.any { m ->
                val (c, f) = m.splitToContractAndMethod(mainContract)

                f == methodSig && (c == CVLKeywords.wildCardExp.keyword || contract == CVLKeywords.wildCardExp.keyword || c == contract)
            }
        }
    }
}

@Suppress("ForbiddenMethodCall")
private fun String.splitOnce(delimiter: String): Pair<String, String>? =
    this
        .split(delimiter, limit = 2)
        .takeIf { it.size > 1 }
        ?.let { it[0] to it[1] }