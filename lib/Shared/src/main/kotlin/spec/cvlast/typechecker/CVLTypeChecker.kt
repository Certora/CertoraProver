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

@file:Suppress("NAME_SHADOWING") // Shadowing is used purposefully
package spec.cvlast.typechecker

import algorithms.transitiveClosure
import bridge.getContractsExtendedBy
import com.certora.collect.*
import datastructures.stdcollections.*
import spec.cvlast.*
import spec.cvlast.EVMBuiltinTypes.method
import spec.rules.*
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.flattenToVoid
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok
import utils.ErrorCollector.Companion.collectingErrors

/**
 * Checks for well-formedness of the AST as well as type correctness of statements and expressions. Note some
 * type/syntax errors may originate before this step.
 */
class CVLAstTypeChecker(
    private val symbolTable: CVLSymbolTable,
) {

    private val typeTypeChecker = CVLTypeTypeChecker(symbolTable)
    private val paramTypeChecker = CVLParamTypeChecker(symbolTable)
    private val expTypeChecker = CVLExpTypeChecker(symbolTable)
    private val methodsTypeChecker = CVLMethodsBlockTypeChecker(expTypeChecker)

    fun typeCheck(ast: CVLAst): CollectingResult<CVLAst, CVLError> = collectingErrors {
        val importedMethods = collectAndFilter(methodsTypeChecker.typeCheckMethodsBlock(symbolTable, ast.importedMethods))
        val subs = collectAndFilter(typeCheckSubs(ast.subs))
        val invsToCalledFunctions = ast.invs.associate { it.id to it.exp.subExprsOfType<CVLExp.ApplyExp.CVLFunction>().map { it.id }.toTreapSet() }
        val callRelation = transitiveClosure(
            reflexive = false,
            graph = subs.associate {
                it.declarationId to it.block.fold(treapSetOf(), invokedCVLDeclarationsExtractor::cmd)
            } + invsToCalledFunctions
        )
        callRelation.filter { (curr, callees) ->
            curr in callees
        }.map { (which, _) ->
            collectError(
                CVLError.General(
                    message = "$which cannot be compiled: it contains a recursive cycle which is not allowed for " +
                        if (which in subs.map { it.declarationId }) { "pure CVL functions" } else { "invariants" },
                    range = Range.Empty()
                )
            )
        }

        val invs = collectAndFilter(typeCheckInvs(ast.invs))

        val erroredInvs = invs.map { it.id }.let { invIds ->
            ast.invs.filter { inv ->
                inv.id !in invIds
            }
        }

        // Don't bother type-checking rules that were derived from an invariant that itself failed typechecking. It will
        // fail with the same errors as the original invariant and this just causes duplicate errors.
        val rulesToTypeCheck = ast.rules.filter { rule ->
            rule !is GroupRule || rule.ruleType !is SpecType.Group.InvariantCheck || rule.ruleType.originalInv !in erroredInvs
        }

        val rules = collectAndFilter(typeCheckRules(rulesToTypeCheck))

        val sorts = collectAndFilter(typeCheckSorts(ast.sorts))
        val ghosts = collectAndFilter(typeCheckGhosts(ast.ghosts))
        val definitions = collectAndFilter(typeCheckDefinitions(ast.definitions))
        val hooks = collectAndFilter(typeCheckHooks(ast.hooks))
        val importedContracts = collectAndFilter(typeCheckImportedContracts(ast.importedContracts))
        val importedSpecFiles = collectAndFilter(typeCheckImportedSpecFiles(ast.importedSpecFiles))
        check(ast.overrideDeclarations.allDecls.isEmpty()) {
            "All override declarations should have been merged into the ast.definitions/functions while dealing with the imports"
        }


        // We allow expression summaries for external functions to have non-parametric contract function calls.
        // All other cases (any type of contract call for internal summaries, and parametric ones for external) are
        // detected here and trigger a failure.
        importedMethods.filterIsInstance<ConcreteMethodBlockAnnotation>().associateWithNotNull {
            (it.summary as? SpecCallSummary.Exp)?.exp?.let { exp ->
                exp as? CVLExp.ApplyExp.CVLFunction
            }
        }.mapNotNull { (methodBlockAnnot, cvlFuncExp) ->
            (callRelation[cvlFuncExp.id].orEmpty() + cvlFuncExp.id).mapNotNullToTreapSet { specDeclId ->
                // note that if any cvl function failed the typecheck above it won't be in the list of 'subs', hance the null-check
                subs.find { it.declarationId == specDeclId }?.let { cvlFunc ->
                    val resetStorageCmds = cvlFunc.block.fold(treapSetOf(), resetStorageExtractor::cmd)
                    if (resetStorageCmds.isNotEmpty()) {
                        collectErrors(resetStorageCmds.map {
                            CVLError.General(it.range, "Cannot use `reset_storage` within a summary cvl function")
                        })
                    }

                    cvlFunc.block.fold(treapSetOf(), contractCallExtractor::cmd)
                } ?: listOf()
            }.flatten().forEach {
                if (it is CVLExp.ApplyExp.ContractFunction.Symbolic) {
                    collectError(CVLError.Exp(
                        message = "${cvlFuncExp.id} cannot be used as a summary for ${methodBlockAnnot.methodParameterSignature.functionName} because it calls a parametric method. " +
                            "Calling parametric methods within function summaries is not supported",
                        exp = cvlFuncExp
                    ))
                } else if (!it.storage.isLastStorage()) {
                    collectError(CVLError.Exp(
                        message = "${cvlFuncExp.id} cannot be used as a summary for ${methodBlockAnnot.methodParameterSignature.functionName} because it contains a contract function call to ${it.callableName} with `at ${it.storage}`. " +
                            "Calling methods that reset storage state within function summaries is not supported",
                        exp = cvlFuncExp
                    ))
                }
            }
        }

        return@collectingErrors CVLAst(
                importedMethods,
                // useDeclarations don't need any special typechecking - when constructing the initial ast we already add
                // the imported rules to [ast.rules] along with the overriding filters, if any.
                ast.useDeclarations,
                rules,
                subs,
                invs,
                sorts,
                ghosts,
                definitions,
                hooks,
                importedContracts,
                importedSpecFiles,
                ast.overrideDeclarations, // this is empty, remember?
                bind(typeCheckScope(ast.scope))
            )
    }

    private fun CVLCmd.endsWithReturn(sub: CVLFunction): VoidResult<CVLError> = when (this) {
        is CVLCmd.Simple.Return -> ok
        is CVLCmd.Composite.Block -> this.block.endsWithReturn(sub)
        is CVLCmd.Composite.If -> this.elseCmd.endsWithReturn(sub).bind(this.thenCmd.endsWithReturn(sub))
        else -> DoesNotEndWithReturn(sub).asError()
    }

    private fun List<CVLCmd>.endsWithReturn(sub: CVLFunction): VoidResult<CVLError> = this.lastOrNull()?.endsWithReturn(sub) ?: DoesNotEndWithReturn(sub).asError()


    /** Finds the halting reason for a command that always halts. Returns null if the command doesn't always halt. */
    private fun CVLCmd.haltingReason(): CVLCmd? = when (this) {
        is CVLCmd.Simple.HaltingCVLCmd -> this
        is CVLCmd.Composite.If -> if (thenCmd.haltingReason() != null && elseCmd.haltingReason() != null) {
            this
        } else {
            null
        }
        is CVLCmd.Composite.Block -> block.lastOrNull()?.haltingReason()
        else -> null
    }

    private fun unreachableStatementError(unreachableCmd: CVLCmd, reason: CVLCmd): VoidResult<CVLError> = when (reason) {
        is CVLCmd.Simple.HaltingCVLCmd -> UnreachableStatement(unreachableCmd, reason).asError()
        is CVLCmd.Composite.If -> UnreachableStatement(unreachableCmd, reason).asError()
        else -> `impossible!` // shouldn't happen if haltingReason() is correct
    }

    private fun List<CVLCmd>.unreachableStatement(): VoidResult<CVLError> {
        return this.mapIndexed { index, cmd ->
            // Check for unreachable code after any command that halts
            val afterResult = if (index < this.lastIndex) {
                cmd.haltingReason()?.let { reason ->
                    unreachableStatementError(this[index+1], reason)
                } ?: ok
            } else {
                ok
            }

            // Recurse into composite commands
            val insideResult = when (cmd) {
                is CVLCmd.Composite.Block -> cmd.block.unreachableStatement()
                is CVLCmd.Composite.If -> {
                    val thenResult = (cmd.thenCmd as? CVLCmd.Composite.Block)?.block?.unreachableStatement() ?: ok
                    val elseResult = (cmd.elseCmd as? CVLCmd.Composite.Block)?.block?.unreachableStatement() ?: ok
                    thenResult.bind(elseResult)
                }
                else -> ok
            }

            afterResult.bind(insideResult)
        }.flattenToVoid()
    }

    private fun typeCheckCVLFunction(
        sub: CVLFunction
    ): CollectingResult<CVLFunction, CVLError> {

        val params = paramTypeChecker.typeCheckCVLParams(sub.params, sub.range, sub.scope)

        val blocks = CVLCmdTypeChecker(symbolTable).typeCheckCmds(sub.block)
        val rets   = typeTypeChecker.typeCheck(sub.rets, sub.range, sub.scope)

        return params.bind(blocks, rets) { params, block, rets ->
            val endsWithReturn = if (rets != CVLType.PureCVLType.Void) {
                sub.block.endsWithReturn(sub)
            } else {
                ok
            }.bind { sub.block.unreachableStatement() }

            val functionParamIds = params.mapToSet { it.id }
            val havocedFunctionParamErrors = cvlVarsHavocedInBlock(block)
                .filter { havocedVar -> havocedVar.id in functionParamIds }
                .map { havocedVar -> CVLError.Exp(havocedVar, "Havocing a CVL Function param ($havocedVar) is not allowed").asError() }
                .flattenToVoid()

            endsWithReturn.map(havocedFunctionParamErrors) { _, _ -> sub.copy(params = params, block = block, rets = rets) }
        }
    }

    private fun cvlVarsHavocedInBlock(block: List<CVLCmd>): Set<CVLExp.VariableExp> {
        val cvlVars = mutableSetOf<CVLExp.VariableExp>()

        for (cmd in block) {
            val havocCmd = cmd as? CVLCmd.Simple.Havoc ?: continue

            for (target in havocCmd.targets) {
                if (target is CVLExp.VariableExp) {
                    cvlVars.add(target)
                }
            }
        }

        return cvlVars
    }

    private fun typeCheckImportedContracts(importedContracts: List<CVLImportedContract>): List<CollectingResult<CVLImportedContract, CVLError>> {
        return importedContracts.map { impContract ->
            val contract = symbolTable.getContractScope(impContract.solidityContractName)?.enclosingContract()?.contract
                ?: error("The type resolver should have caught a non-existing contract name")
            val baseContracts = symbolTable.getAllContracts().getContractsExtendedBy(contract)
            if (baseContracts.isNotEmpty()) {
                AliasingExtensionContract(impContract, baseContracts.map { it.name }).asError()
            } else {
                impContract.lift()
            }
        }
    }

    private fun typeCheckImportedSpecFiles(importedSpecFiles: List<CVLImportedSpecFile>): List<CollectingResult.Result<CVLImportedSpecFile>> {
        // nothing to type check here? TODO
        return importedSpecFiles.map { it.lift() }
    }

    private fun typeCheckHooks(hooks: List<CVLHook>): List<CollectingResult<CVLHook, CVLError>> {
        val seen = mutableListOf<CollectingResult<CVLHook, CVLError>>()

        // one hook per pattern
        hooks.forEach { hook ->
            val duplicate = seen.firstOrNull { other -> other is CollectingResult.Result && hook.pattern matches other.result.pattern }
            if (duplicate != null) {
                val dupPattern = (duplicate as CollectingResult.Result).result
                seen.add(CVLError.General(
                    hook.range, "The declared hook pattern `${hook.pattern}` " +
                        "duplicates the hook pattern `${dupPattern.pattern}` at ${dupPattern.range}. " +
                        "A hook pattern may only be used once."
                ).asError())
            } else {
                seen.add(hook.lift())
            }
        }
        val hookTypeChecker = CVLHookTypeChecker(symbolTable)
        return seen.map { it.bind { hookTypeChecker.typeCheckHook(it) } }
    }



    private fun typeCheckGhosts(ghosts: List<CVLGhostDeclaration>): List<CollectingResult<CVLGhostDeclaration, CVLError>> {
        return ghosts.map { typeCheckGhostDecl(it) }
    }

    /**
     * @return type-checked [type], after checking that it is a valid argument or return type for a ghost mapping
     * @param [where] should describe the context of the ghost argument; it should complete the sentence "... is not allowed $where"
     */
    private fun typeCheckGhostArgumentType(
        type: CVLType.PureCVLType,
        range: Range,
        scope: CVLScope, where: String
    ) : CollectingResult<CVLType.PureCVLType,CVLError> = collectingErrors {
        val type = bind(typeTypeChecker.typeCheck(type, range, scope))
        if (type !is CVLType.PureCVLType.GhostMappingKeyType) {
            val alternative = if (type is CVLType.PureCVLType.CVLArrayType) { ", consider using a mapping instead."} else {""}
            returnError(CVLError.General(range, "The type $type is not allowed $where$alternative"))
        }
        return@collectingErrors type
    }

    /**
     * @return type-checked [type], after checking that it a valid type for a ghost variable
     * @param [whereDescription] should describe the context for the check; it should complete the sentence "... is not allowed $where"
     */
    private fun typeCheckGhostType(
        type: CVLType.PureCVLType,
        range: Range,
        scope: CVLScope,
        whereDescription: String
    ) : CollectingResult<CVLType.PureCVLType, CVLError> {
        return if(type is CVLType.PureCVLType.Ghost.Mapping) {
            typeCheckGhostType(type.value, range, scope, whereDescription).map(
                typeCheckGhostArgumentType(type.key, range, scope, "as a key in a ghost mapping")
            ) { vType, kType ->
                CVLType.PureCVLType.Ghost.Mapping(key = kType, value = vType)
            }
        } else {
            typeCheckGhostArgumentType(type, range, scope, whereDescription)
        }
    }

    /** @return type-checked [ghost] declaration */
    private fun typeCheckGhostDecl(ghost: CVLGhostDeclaration): CollectingResult<CVLGhostDeclaration, CVLError> {
        val typeEnv = CVLTypeEnvironment(ghost.range, ghost.scope, emptyList())

         return when (ghost) {
            is CVLGhostDeclaration.Function -> {
                typeCheckGhostArgumentType(ghost.resultType, ghost.range, ghost.scope, "in a return position of a ghost function").map(
                    ghost.paramTypes.map { type -> typeCheckGhostArgumentType(type, ghost.range, ghost.scope, "an argument of a ghost function") }.flatten(),
                    ghost.axioms.map { axiom -> typeCheckGhostAxiom(axiom, typeEnv)}.flatten()
                ) { ret, paramTypes, axioms ->
                    ghost.copy(ret = ret, paramTypes = paramTypes, axioms = axioms)
                }
            }

            is CVLGhostDeclaration.Variable -> {
                val context = if (ghost.type is CVLType.PureCVLType.Ghost.Mapping) {
                    "as the output type of a ghost mapping"
                } else {
                    "as a ghost variable"
                }
                typeCheckGhostType(ghost.type, ghost.range, ghost.scope, context).map(
                    ghost.axioms.map { typeCheckGhostAxiom(it, typeEnv) }.flatten()
                ) { type, axioms ->
                    ghost.copy(
                        type = type,
                        axioms = axioms
                    )
                }
            }

             is CVLGhostDeclaration.Sum -> error("There can be no explicit references to a ghost summary in cvl")
         }
    }

    /** @return type-checked [axiom] */
    private fun typeCheckGhostAxiom(
        axiom: CVLGhostAxiom,
        typeEnv: CVLTypeEnvironment
    ): CollectingResult<CVLGhostAxiom, CVLError> {
        return expTypeChecker.typeCheck(axiom.exp, typeEnv).map { typeCheckedExp ->
            axiom.mapExp { typeCheckedExp }
        }
    }

    private fun typeCheckSorts(sorts: List<SortDeclaration>): List<CollectingResult.Result<SortDeclaration>> {
        // nothing to type check? TODO
        // SymbolTableFiller checks that names don't collide with builtin keywords
        return sorts.map { it.lift() }
    }

    private fun typeCheckInvs(invs: List<CVLInvariant>): List<CollectingResult<CVLInvariant, CVLError>> {
        return invs.map { typeCheckInv(it) }
    }

    private fun typeCheckInv(inv: CVLInvariant): CollectingResult<CVLInvariant, CVLError> {
        val emptyEnv = CVLTypeEnvironment.empty(inv.range, inv.scope)

        val params = paramTypeChecker.typeCheckCVLParams(inv.params, inv.range, inv.scope)
        val exp = expTypeChecker.typeCheck(inv.exp, emptyEnv)
        val preservedCheck = inv.checkPreservedIsErrorFree()
        val methodParamFilters = typeCheckMethodParamFilters(inv.methodParamFilters, emptyEnv)
        val preserved = inv.proof.preserved.map { preserved ->
            val withParams = paramTypeChecker.typeCheckCVLParams(preserved.withParams, preserved.range, preserved.scope)
            val block = CVLCmdTypeChecker(symbolTable).typeCheckCmds(preserved.block)
            withParams.map(block) { withParams, block ->
                when (preserved) {
                    is CVLPreserved.ExplicitMethod  -> preserved.copy(
                        withParams=withParams,
                        block=block
                    )
                    is CVLPreserved.Fallback ->  preserved.copy(
                        withParams=withParams,
                        block=block
                    )
                    is CVLPreserved.Generic ->  preserved.copy(
                        withParams=withParams,
                        block=block
                    )
                    is CVLPreserved.TransactionBoundary ->  preserved.copy(
                        withParams=withParams,
                        block=block
                    )
                    is CVLPreserved.Constructor -> preserved.copy(
                        withParams = withParams,
                        block = block
                    )
                }
            }
        }.flatten()
        return params.map(exp, methodParamFilters, preservedCheck, preserved) { params, exp, methodParamFilters, _, preserved ->
            inv.copy(
                params = params,
                exp = exp,
                methodParamFilters = methodParamFilters,
                proof = inv.proof.copy(preserved=preserved)
            )
        }
    }

    private fun typeCheckSubs(subs: List<CVLFunction>): List<CollectingResult<CVLFunction, CVLError>> {
        return subs.map { typeCheckSub(it) }
    }

    private fun typeCheckSub(sub: CVLFunction): CollectingResult<CVLFunction, CVLError> {
        return typeCheckCVLFunction(sub)
    }

    private fun typeCheckRules(rules: List<ICVLRule>): List<CollectingResult<ICVLRule, CVLError>> {
        return rules.map { rule ->
            typeCheckRule(rule)
        }
    }

    private fun typeCheckRule(rule: ICVLRule): CollectingResult<ICVLRule, CVLError> {
        return when (rule) {
            is CVLSingleRule -> {
                val scope = rule.scope

                paramTypeChecker.typeCheckCVLParams(
                    rule.params, rule.range, scope,
                    rule.parentIdentifier == null
                ).bind(
                    if (rule.block.isEmpty()) {
                        CVLError.General(
                            rule.range,
                            "Encountered an empty rule: ${rule.declarationId}"
                        ).asError()
                    } else {
                        ok
                    }
                ) { typeCheckedParams, _ ->
                    val ruleTypeEnv = typeCheckedParams.fold(
                        CVLTypeEnvironment.empty(
                            rule.range,
                            scope
                        )
                    ) { env, param -> env.pushParam(param) }
                    val typeCheckedMethodParamFilters =
                        typeCheckMethodParamFilters(rule.methodParamFilters, ruleTypeEnv)
                    val blocks = CVLCmdTypeChecker(symbolTable).typeCheckCmds(rule.block)
                    blocks.bind(typeCheckedMethodParamFilters) { block, mpf ->
                        checkLastStatementIsAssert(block, rule, rule.range).map {
                            rule.copy(
                                params = typeCheckedParams, block = block, methodParamFilters = mpf
                            )
                        }
                    }
                }
            }
            is GroupRule -> rule.rules.map { r -> typeCheckRule(r) }.flatten()
                .map { rule.copy(rules = it) }
            is DynamicGroupRule -> {
                typeCheckMethodParamFilters(rule.methodParamFilters,CVLTypeEnvironment.empty(rule.range, rule.scope))
                    .map { rule.copy(methodParamFilters = it) }
            }
            is AssertRule -> rule.lift() // nothing to type check
            is StaticRule -> rule.lift() // nothing to type check
        }
    }

    private fun checkLastStatementIsAssert(last: CVLCmd, rule: IRule) : VoidResult<CVLError> {
        // Builtin rules such as view reentrancy are generated later, so here they may not yet end with Assert.
        // Since such rules are generated by us, it seems ok to ease this limitation.
        if (rule.ruleType is SpecType.Single.BuiltIn) {
            return ok
        }

        return when (last) {
            is CVLCmd.Simple -> {
                if (last is CVLCmd.Simple.Apply) {
                    // if the last command is a call to a CVL FUnction, check the body of the CVL function to see if it
                    // ends in an assert
                    val f = symbolTable.lookUpFunctionLikeSymbol(last.exp.callableName, last.typeEnv)?.symbolValue as? CVLFunction
                    if (f != null) {
                        return checkLastStatementIsAssert(f.block.last(), rule)
                    }
                }
                if (last !is RuleFinalCommand) {
                    return CVLError.General(
                        last.range, "last statement of the rule ${rule.declarationId} is not an assert or satisfy command (but must be)"
                    ).asError()
                } else {
                    ok
                }
            }
            is CVLCmd.Composite -> when (last) {
                is CVLCmd.Composite.If -> {
                    checkLastStatementIsAssert(last.elseCmd, rule).map(
                        checkLastStatementIsAssert(last.thenCmd, rule)
                    )
                }

                is CVLCmd.Composite.Block -> checkLastStatementIsAssert(
                    last.block, rule, last.range
                )
            }
        }
    }

    private fun checkLastStatementIsAssert(block: List<CVLCmd>, rule: IRule, range: Range) : VoidResult<CVLError> {
        return block.lastOrNull {
            it !is CVLCmd.Simple.Label
        }?.let { last -> checkLastStatementIsAssert(last, rule)}
            ?: CVLError.General(range, "Empty blocks are not allowed").asError()
    }

    private fun typeCheckMethodParamFilters(
        methodParamFilters: MethodParamFilters,
        ruleTypeEnv: CVLTypeEnvironment
    ): CollectingResult<MethodParamFilters, CVLError> {
        return methodParamFilters.methodParamToFilter.map { (methodParamName, filter) ->
            // looking up parametric method names: function like
            val symbolInfo = symbolTable.lookUpFunctionLikeSymbol(methodParamName, methodParamFilters.scope)
            if (methodParamFilters.scope.enclosingInvariant() == null && (symbolInfo == null || symbolInfo.symbolValue !is CVLParam || symbolInfo.getCVLTypeOrNull() != method)) {
                CVLError.Exp(
                    filter.methodParam,
                    "Filters can be declared only for method type rule parameters, but " + "\"${methodParamName}\" is not such a parameter"
                ).asError()
            } else {
                (methodParamName to filter).lift()
            }.bind { (methodParamName, filter) ->
                val typeEnvWithMethodParam = if (methodParamFilters.scope.enclosingInvariant() != null && methodParamFilters.scope.enclosingRule() == null) {
                    check(symbolInfo == null && ruleTypeEnv.lookUp(methodParamName) == null) {
                        "The methodParamName $methodParamName should not have been in the type-env in case of an invariant filter"
                    }
                    ruleTypeEnv.pushParam(CVLParam(method, methodParamName, Range.Empty()))
                } else {
                    ruleTypeEnv
                }
                expTypeChecker.typeCheck(filter.filterExp, typeEnvWithMethodParam).map { typeCheckedFilterExp ->
                    Pair(
                        methodParamName,
                        filter.copy(filterExp = typeCheckedFilterExp)
                    )
                }
            }
        }.flatten().map { listOfPairs ->
            val mpf = listOfPairs.toMap()
            methodParamFilters.copy(
                methodParamToFilter = mpf,
            )
        }
    }

    private fun typeCheckScope(scope: CVLScope): CollectingResult<CVLScope, CVLError> {
        // nothing to type check? TODO
        // should be well-formed by construction
        return scope.lift()
    }

    private fun typeCheckDefinitions(definitions: List<CVLDefinition>): List<CollectingResult<CVLDefinition, CVLError>> {
        // old code, revised
        return definitions.map { definition ->
            typeCheckDefinition(definition)
        }
    }

    private fun typeCheckDefinition(
        definition: CVLDefinition
    ): CollectingResult<CVLDefinition, CVLError> {
        return typeCheckDefinition(
            range = definition.range,
            id = definition.id,
            params = definition.params,
            rets = definition.rets,
            body = definition.body,
            modifies = definition.modifies,
            reads = definition.reads,
            scope = definition.scope
        )
    }

    /**
     * @param reads any ghost function which is used in [TwoStateIndex.NEITHER]
     * @param modifies any ghost function which is used in [TwoStateIndex.NEW] or [TwoStateIndex.OLD]
     */
    private fun typeCheckDefinition(
        range: Range,
        id: String,
        params: List<CVLParam>,
        rets: CVLType.PureCVLType,
        body: CVLExp,
        modifies: Set<CVLGhostDeclaration.Function>,
        reads: Set<CVLGhostDeclaration.Function>,
        scope: CVLScope?
    ): CollectingResult<CVLDefinition, CVLError> {
        // first, find out if ghost functions are either reading or modifying, but not both.
        // if a ghost function is both read and modified, this is currently an error.
        // A "modified" ghost means it's assumed to be in a two-state context. A "read"
        // ghost is not. Therefore, it is an error if a ghost is accessed both as if
        // inside a two state context, and as if it's not inside a two state context.
        val readsAndModifies = modifies.intersect(reads)
        return (if (readsAndModifies.isEmpty()) {
            // reads and modifies mutually exclusive, add all modified ghosts to two state context
            modifies.fold(
                CVLTypeEnvironment.empty(
                    range,
                    scope!!
                )
            ) { accEnv, elModified ->
                accEnv.pushTwoStateGhost(elModified)
            }.lift()
        } else {
            // don't continue typechecking the definition
            CVLError.General(
                range,
                "Definition $id both reads and modifies the ghost function(s) " +
                        "${readsAndModifies.joinToString(", ") { it.callSignature }}. This is not allowed."
            ).asError()
        }).bind { typeEnvWithModifiedGhosts ->
            // type env contains any ghosts from the "modifies" clause with their "isTwoState" flags set
            val typeEnv = params
                .fold(typeEnvWithModifiedGhosts)
                { env, param -> env.pushParam(param) }
            val allParamsUnique = params
                .distinctBy { param -> param.id }.size == params.size
            val allParamsUniqueResult = if (!allParamsUnique) {
                CVLError.General(
                    range,
                    "all parameters of definition $id must have a unique name"
                ).asError()
            } else {
                ok
            }

            val type = typeTypeChecker.typeCheck(rets, typeEnv.range, scope!!)
            val body = expTypeChecker.typeCheck(body, typeEnv)
            val params = CVLParamTypeChecker(symbolTable).typeCheckCVLParams(params, range, scope)

            body.bind(type, params, allParamsUniqueResult) { body, type, params, _ ->
                if (body.getCVLType() isNotConvertibleTo type /* check body is convertible to type - failing case */) {
                    CVLError.General(
                        range, "definition $id return type declared to be $rets but " +
                                "body type inferred to be ${body.getCVLType()}"
                    ).asError()
                } else {
                    CVLDefinition(
                        range = range,
                        id = id,
                        params = params,
                        body = body,
                        ret = type,
                        modifies = modifies,
                        reads = reads,
                        scope = scope
                    ).lift()
                }
            }

        }

    }

    companion object {
        /**
         * Extract the set of cvl functions invoked and invariants 'requireInvariant'ed by a [CVLCmd]
         * (as returned by `cmd` seeded with an empty set)
         */
        val invokedCVLDeclarationsExtractor = object : CVLCmdFolder<TreapSet<String>>() {
            override fun assumeInvariant(
                acc: TreapSet<String>,
                cmd: CVLCmd.Simple.AssumeCmd.AssumeInvariant
            ): TreapSet<String> {
                return acc + cmd.id
            }

            override fun cvlExp(acc: TreapSet<String>, exp: CVLExp): TreapSet<String> {
                return acc + exp.subExprsOfType<CVLExp.ApplyExp.CVLFunction>().map { it.id }
            }

            override fun lhs(acc: TreapSet<String>, lhs: CVLLhs): TreapSet<String> {
                return acc
            }

            override fun message(acc: TreapSet<String>, message: String): TreapSet<String> {
                return acc
            }

        }

        /**
         * Gets the set of contract functions (without contract qualification or parameter types) that are
         * called within a command (as returned by calling [contractCallExtractor]'s `cmd` function seeded with
         * an empty set)
         */
        val contractCallExtractor = object : CVLCmdFolder<TreapSet<CVLExp.ApplyExp.ContractFunction>>() {
            override fun cvlExp(acc: TreapSet<CVLExp.ApplyExp.ContractFunction>, exp: CVLExp): TreapSet<CVLExp.ApplyExp.ContractFunction> {
                return acc + exp.subExprsOfType<CVLExp.ApplyExp.ContractFunction>()
            }

            override fun lhs(acc: TreapSet<CVLExp.ApplyExp.ContractFunction>, lhs: CVLLhs): TreapSet<CVLExp.ApplyExp.ContractFunction> {
                return acc
            }

            override fun message(acc: TreapSet<CVLExp.ApplyExp.ContractFunction>, message: String): TreapSet<CVLExp.ApplyExp.ContractFunction> {
                return acc
            }

        }

        val resetStorageExtractor = object : CVLCmdFolder<TreapSet<CVLCmd.Simple.ResetStorage>>() {
            override fun cvlExp(acc: TreapSet<CVLCmd.Simple.ResetStorage>, exp: CVLExp): TreapSet<CVLCmd.Simple.ResetStorage> {
                return acc
            }

            override fun lhs(acc: TreapSet<CVLCmd.Simple.ResetStorage>, lhs: CVLLhs): TreapSet<CVLCmd.Simple.ResetStorage> {
                return acc
            }

            override fun message(acc: TreapSet<CVLCmd.Simple.ResetStorage>, message: String): TreapSet<CVLCmd.Simple.ResetStorage> {
                return acc
            }

            override fun resetStorage(
                acc: TreapSet<CVLCmd.Simple.ResetStorage>,
                cmd: CVLCmd.Simple.ResetStorage
            ): TreapSet<CVLCmd.Simple.ResetStorage> {
                return acc + cmd
            }
        }
    }
}

/** checks that [exp] has type bool. assumes [exp] is already typed */
internal fun typeCheckBooleanExp(exp: CVLExp, expKind: NonBoolExpression.Kind): CollectingResult<CVLExp, CVLError> {
    return if (exp.getCVLType().isConvertibleTo(CVLType.PureCVLType.Primitive.Bool)) {
        exp.lift()
    } else {
        NonBoolExpression(exp, expKind).asError()
    }
}
