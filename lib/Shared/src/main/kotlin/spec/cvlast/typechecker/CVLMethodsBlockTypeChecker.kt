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

package spec.cvlast.typechecker

import datastructures.stdcollections.*
import spec.cvlast.*
import spec.cvlast.EVMBuiltinTypes
import spec.cvlast.Function
import spec.cvlast.typedescriptors.*
import spec.isWildcard
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors
import java.math.BigInteger

class CVLMethodsBlockTypeChecker(
    private val expTypeChecker : CVLExpTypeChecker,
) {
    /**
     * @return a type-checked copy of [methodsBlock].
     *
     * A methods block is well-typed if all the entries are well-typed
     */
    fun typeCheckMethodsBlock(symbolTable: CVLSymbolTable, methodsBlock: List<MethodBlockEntry>): List<CollectingResult<MethodBlockEntry, CVLError>> {
        return methodsBlock.map { typeCheckMethodsEntry(symbolTable, it) }
    }

    /** @return a type-checked copy of [entry]. */
    private fun typeCheckMethodsEntry(symbolTable: CVLSymbolTable, entry : MethodBlockEntry) : CollectingResult<MethodBlockEntry, CVLError> {
        return when (entry) {
            is ConcreteMethodBlockAnnotation -> typeCheckConcreteAnnotation(symbolTable, entry)
            is CatchAllSummaryAnnotation -> typeCheckCatchAllAnnotation(entry)
            is CatchUnresolvedSummaryAnnotation -> typeCheckCatchUnresolvedAnnotation(symbolTable, entry)
        }
    }

    /**
     * @return a type-checked copy of [entry].
     *
     * A [CatchUnresolvedSummaryAnnotation] is well-typed if:
     *  - the method is external
     *  - the dispatch list must be typechecked
     */
    private fun typeCheckCatchUnresolvedAnnotation(symbolTable: CVLSymbolTable, entry: CatchUnresolvedSummaryAnnotation)
    : CollectingResult<MethodBlockEntry, CVLError>  {
        when (entry.summary) {
            is SpecCallSummary.DispatchList,
            is SpecCallSummary.HavocSummary -> { /* ok, these are allowed, HavocSummary is what is already allowed in the DEFAULT of a DispatchList */ }
            is SpecCallSummary.Always,
            is SpecCallSummary.Constant,
            is SpecCallSummary.Dispatcher,
            is SpecCallSummary.Exp,
            is SpecCallSummary.PerCalleeConstant,
            is SpecCallSummary.Reroute -> {
                return UnresolvedExternalForbiddenSummaryKind(entry.summary, entry.range).asError()
            }
        }
        return typeCheckSummary(symbolTable, entry.summary, null, entry).map { entry }
    }

    /**
     * @return a type-checked copy of [entry].
     *
     * A [CatchAllSummaryAnnotation] is well-typed if:
     *  - the summary is a havoc summary
     *  - the method is external
     *  - the entry is not virtual
     *  - the entry is not envfree
     */
    private fun typeCheckCatchAllAnnotation(
        entry: CatchAllSummaryAnnotation
    ) : CollectingResult<CatchAllSummaryAnnotation,CVLError> = collectingErrors {
        // All these are checked in [ImportedFunction.kotlinizeCatchAllSummary]
        check(entry.summary is SpecCallSummary.HavocSummary)
        check(!entry.annot.virtual)
        check(!entry.annot.envFree)
        check(!entry.annot.library)
        check(entry.annot.visibility == Visibility.EXTERNAL)

        return@collectingErrors entry
    }

    /**
     * @return a type-checked copy of [entry].
     *
     * A [ConcreteMethodBlockAnnotation] is well-typed if:
     *  - it has a declared return type if and only if it is an exact entry
     *  - its summary (if any) is well-typed
     */
    private fun typeCheckConcreteAnnotation(
        symbolTable: CVLSymbolTable,
        entry : ConcreteMethodBlockAnnotation
    ) = collectingErrors<ConcreteMethodBlockAnnotation,CVLError> {
        val res = bind(typeCheckReturnsClause(entry))
        if (entry.summary == null) {
            return@collectingErrors entry
        }

        val checkedSummary = bind(typeCheckSummary(symbolTable, entry.summary, res, entry))
        return@collectingErrors entry.copy(summary = checkedSummary)
    }

    /**
     * Check that the `returns` clause is declared when and only when it should be.
     *
     * Note that we can't yet check that the `returns` clause matches the contract's return type; that is checked after
     * combining the CVL with the contract - see [MergeableSignature.mergeReturns].
     *
     * @return the type from the `returns` clause for a specific entry, or `null` for a wildcard entry.
     */
    private fun typeCheckReturnsClause(
        entry : ConcreteMethodBlockAnnotation
    ) : CollectingResult<List<VMTypeDescriptor>?,CVLError> = collectingErrors {
        when (entry.target) {
            is MethodEntryTargetContract.SpecificTarget -> {
                // A specific target means we should be able to find this exact method in the scene. This means
                // we can find the correct return types.
                return@collectingErrors (entry.methodParameterSignature as? MethodSignature)?.resType
                    ?: returnError(CVLError.General(entry.range, "Cannot specify summary for ${entry.prettyPrint()} without return type information")) // Should be impossible
            }

            MethodEntryTargetContract.WildcardTarget -> {
                if (entry.methodParameterSignature is MethodSignature) {
                    // [MethodSignature] indicates that there is a `returns` clause, which isn't allowed for wildcard entries

                    var message = "Wildcard method entry ${entry.prettyPrint()} may not specify return types in the method signature."
                    if(entry.summary is SpecCallSummary.Exp && entry.summary.expectedType == null) {
                        message += " Instead, indicate the expected return type of the summary by adding expect <type> after the summary."
                    }

                    returnError(CVLError.General(entry.range,message))
                }
                return@collectingErrors null
            }
        }
    }

    /** @return a type-checked copy of [summary] */
    private fun typeCheckSummary(
        symbolTable: CVLSymbolTable,
        summary : SpecCallSummary.ExpressibleInCVL?,
        res : List<VMTypeDescriptor>?,
        entry : MethodBlockEntry
    ) : CollectingResult<SpecCallSummary.ExpressibleInCVL?, CVLError> {
            if(summary == null) {
                return null.lift()
            }
            if(entry is ConcreteMethodBlockAnnotation && entry.qualifiers.visibility == Visibility.INTERNAL && summary !is SpecCallSummary.InternalSummary) {
                return CVLError.General(
                    message = "Cannot use summary ${summary.summaryName} for internal functions.",
                    range = summary.range
                ).asError()
            }
        return when(summary) {
            is SpecCallSummary.Always -> typeCheckAlwaysSummary(summary, res, entry)
            is SpecCallSummary.Constant -> typeCheckConstantSummary(summary, res, entry)
            is SpecCallSummary.PerCalleeConstant -> typeCheckConstantSummary(summary, res, entry)
            is SpecCallSummary.Dispatcher -> typeCheckDispatcherSummary(symbolTable, summary, entry)
            is SpecCallSummary.Exp -> typeCheckExpSummary(summary, res, entry)

            // always well typed:
            is SpecCallSummary.HavocSummary.Nondet -> typeCheckNondetSummary(summary, res, entry)
            is SpecCallSummary.HavocSummary -> summary.lift()
            is SpecCallSummary.DispatchList -> typeCheckDispatchListSummary(symbolTable, summary, entry)
            is SpecCallSummary.Reroute -> typeCheckRerouteSummary(symbolTable, entry, summary)
        }
    }

    private fun typeCheckRerouteSummary(
        symbolTable: CVLSymbolTable,
        entry: MethodBlockEntry,
        summary: SpecCallSummary.Reroute
    ): CollectingResult<SpecCallSummary.ExpressibleInCVL?, CVLError> {
        if(entry !is ConcreteMethodBlockAnnotation) {
            return CVLError.General(message = "Cannot use Rerouting Summary for method block entry ${entry.prettyPrint()}.", range = entry.range).asError()
        }
        val expectedReturns = when(entry.methodParameterSignature) {
            is MethodSignature -> entry.methodParameterSignature.resType
            else -> listOf()
        }

        val rerouteHost = symbolTable.getContractScope(summary.target.host) ?: throw CertoraInternalException(
            CertoraInternalErrorType.CVL_TYPE_CHECKER, "Rerouter told us ${summary.target.host} was a contract: it doesn't exists"
        )

        val match = symbolTable.lookUpFunctionLikeSymbol(summary.target.methodId, rerouteHost)?.let { info ->
            info as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo
        }?.impFuncs?.mapNotNull { f ->
            f as? ContractFunction
        }?.singleOrNull { cf ->
            cf.evmExternalMethodInfo?.sigHash == summary.sighash && cf.methodSignature.qualifiedMethodName.methodId == summary.target.methodId
        } ?: throw CertoraInternalException(
            CertoraInternalErrorType.CVL_TYPE_CHECKER, "Rerouter claimed ${summary.target.methodId} with sighash ${summary.sighash} should exist in ${summary.target.host}: it doesn't"
        )

        if(!match.methodSignature.resType.zipPred(expectedReturns) { a, b ->
            a.mergeWith(b).isResult()
        }) {
            return IllegalRerouteSummary(
                errorType = IllegalRerouteSummary.ErrorSort.ReturnTypeMismatch(
                    expected = expectedReturns,
                    tgt = match.methodSignature
                ),
                location = summary.range
            ).asError()
        }

        return summary.lift()
    }

    private fun typeCheckNondetSummary(
        summary: SpecCallSummary.HavocSummary.Nondet,
        res: List<VMTypeDescriptor>?,
        entry: MethodBlockEntry
    ): CollectingResult<SpecCallSummary.ExpressibleInCVL?, CVLError> {
        if(entry is ConcreteMethodBlockAnnotation && entry.qualifiers.visibility == Visibility.EXTERNAL) {
            return summary.lift()
        }
        /*
         * XXX(jtoman): Unfortunate duplication with the checks in the CVLAstBuilder
         */
        if(res.orEmpty().any {
            it !is VMValueTypeDescriptor
            }) {
            return CVLError.General(
                message = "Cannot use NONDET summary for function with return type(s) ${res!!.joinToString(", ") {
                    "`${it.prettyPrint()}`"
                }}; using a NONDET summary for reference types causes unsoundness.",
               range = summary.range
            ).asError()
        }
        return summary.lift()
    }

    /**
     * @return a type-checked copy of [summary].
     *
     * An `ALWAYS(e)` summary is well-typed if:
     *  - `e` is a well-typed boolean or number literal
     *  - the expected return type (if known) matches `e`
     */
    private fun typeCheckAlwaysSummary(
        summary : SpecCallSummary.Always,
        res : List<VMTypeDescriptor>?,
        entry : MethodBlockEntry,
    ) : CollectingResult<SpecCallSummary.Always, CVLError> = collectingErrors {
        // TODO CERT-2680: modulo error messages, this is basically the same as Exp summaries
        if(entry !is ConcreteMethodBlockAnnotation) {
            returnError(CVLError.General(message = "An always summary can not be applied on method block entry ${entry.prettyPrint()}.", range = entry.range))
        }
        return@collectingErrors when (val summExp = summary.exp) {
            is CVLExp.Constant.BoolLit,
            is CVLExp.Constant.NumberLit,
            is CVLExp.CastExpr -> {
                val typeCheckedSummExp = bind(
                    expTypeChecker.typeCheck(
                        summExp,
                        CVLTypeEnvironment.empty(entry.range, CVLScope.AstScope)
                    )
                )
                when {
                    res == null -> {
                        check(entry.target is MethodEntryTargetContract.WildcardTarget)
                        summary.copy(exp = typeCheckedSummExp)
                    }

                    res.isEmpty() -> {
                        check(entry.target is MethodEntryTargetContract.SpecificTarget)
                        // summarizing a void function!
                        returnError(
                            CVLError.General(
                                range = entry.range,
                                message = "${entry.prettyPrint()} is a void function, so can't be summarized with ${summary.summaryName}"
                            )
                        )
                    }

                    res.size > 1 -> {
                        returnError(
                            CVLError.General(
                                range = entry.range,
                                message = "Cannot use constant summary for function ${entry.prettyPrint()} that returns multiple values"
                            )
                        )
                    }

                    else -> {
                        val expectedType = res.single()
                        val actualType = typeCheckedSummExp.getOrInferPureCVLType()
                        convertibilityErrors(actualType, expectedType, entry.qualifiers.visibility)?.let { errors ->
                            collectError(
                                CVLError.Exp(
                                    summExp,
                                    "Expression $summExp of type $actualType cannot be used for ${expectedType.prettyPrint()}: " + errors
                                )
                            )
                        }
                        summary.copy(exp = typeCheckedSummExp)
                    }
                }
            }

            else -> returnError(
                CVLError.General(
                    range = entry.range,
                    message = "The argument of an ALWAYS summary must be a constant, got `$summExp`"
                )
            )
        }
    }

    /**
     * @return a type-checked copy of [summary]
     * @require [summary] is either a CONSTANT or PER_CALLEE_CONSTANT summary
     *
     * A constant or per-callee-constant summary is well-typed if all return types have a constant size
     */
    private fun typeCheckConstantSummary(
        summary : SpecCallSummary.ExpressibleInCVL,
        res : List<VMTypeDescriptor>?,
        entry : MethodBlockEntry,
    ) : CollectingResult<SpecCallSummary.ExpressibleInCVL, CVLError> {
        require(summary is SpecCallSummary.Constant || summary is SpecCallSummary.PerCalleeConstant)
        return if (res == null) {
            // we don't have a target type to check, so the best we can do is wait for
            // summarization-time sanity checks
            summary.lift()
        } else if (res.any {
                it is VMReferenceTypeDescriptor && it.isDynamicSize()
            }) {
            CVLError.General(
                range = entry.range,
                message = "${entry.summary} cannot be used for complex return types specified for ${entry.prettyPrint()}"
            ).asError()
        } else {
            summary.lift()
        }
    }

    /**
     * @return a type-checked copy of [summary].
     * @require [declaredReturn] is null if and only if [entry] is a wildcard entry
     *
     * An expression summary is well-typed when:
     *  - there is an `expect` clause if and only if the entry is a wildcard entry
     *  - the parameter of the `with` clause, if any, has type `env`
     *  - if there is a `with` clause, then the entry is not `envfree`
     *  - the expression is well-typed
     *  - the return type of the expression is a CVL type that is convertible to the expected return type
     */
    private fun typeCheckExpSummary(
        summary : SpecCallSummary.Exp,
        declaredReturn : List<VMTypeDescriptor>?,
        entry : MethodBlockEntry,
    ) : CollectingResult<SpecCallSummary.Exp, CVLError> = collectingErrors<SpecCallSummary.Exp,CVLError> {
        if(entry !is ConcreteMethodBlockAnnotation) {
            returnError(CVLError.General(message = "An expression summary can not be applied on method block entry ${entry.prettyPrint()}.", range = entry.range))
        }
        // get the expected return type (from `returns` or `expect` as appropriate)
        val expectedReturn = when(entry.target) {
            is MethodEntryTargetContract.WildcardTarget -> {
                require(declaredReturn == null) // ensured by [typeCheckReturnType]
                summary.expectedType
                    ?: returnError(CVLError.General(entry.range, "Wildcard method entry with summary ${summary.exp} must include an expected return type"))
            }
            is MethodEntryTargetContract.SpecificTarget -> {
                require(declaredReturn != null) // ensured by [typeCheckReturnType]
                if (summary.expectedType != null) {
                    collectError(CVLError.General(entry.range, "An exact summary target may not contain expected return type in the summary body."))
                }
                declaredReturn
            }
        }

        // type check the `with` clause
        summary.withClause?.let {
            if (it.param.type != EVMBuiltinTypes.env) {
                collectError(WithWrongType(it.param))
            }
            if (it.param.id.isWildcard()) {
                collectError(WithWildcard(it.param.range))
            }
            if (entry.qualifiers.envFree) {
                collectError(EnvfreeAndWith(it.range))
            }
        }

        // type check the body
        val typeCheckedBody = bind(expTypeChecker.typeCheck(
            exp = summary.exp,
            typeEnv = CVLTypeEnvironment(summary.exp.getRangeOrEmpty(), summary.exp.getScope(), listOf())
        ))

        // check conversion from actual return type to expected return type
        val actualReturn = typeCheckedBody.getCVLType() as? CVLType.PureCVLType
            ?: returnError(CVLError.General(
                entry.range,
                "Cannot summarize '${entry.methodParameterSignature}' with '$typeCheckedBody'. Only CVL constructs (e.g. CVL functions or ghosts) can be used as summaries"
            ))

        // Note: this will need to change if/when we allow multi-return from CVL functions
        when(actualReturn) {
            is CVLType.PureCVLType.Void -> {
                if (expectedReturn.isNotEmpty()) {
                    collectError(CVLError.Exp(typeCheckedBody, "Summary expression returns 0 values, where ${entry.prettyPrint()} returns ${expectedReturn.size} values."))
                }
            }
            is CVLType.PureCVLType.TupleType -> {
                if(expectedReturn.size != actualReturn.elements.size) {
                    returnError(CVLError.Exp(typeCheckedBody, "Summary expression returns ${actualReturn.elements.size} values, where ${entry.prettyPrint()} expects ${expectedReturn.size} values"))
                }
                actualReturn.elements.zip(expectedReturn).withIndex().mapNotNull { (ind, typePair) ->
                    val (actualCVL, expectedVM) = typePair
                    convertibilityErrors(
                        actualCVL, expectedVM, entry.qualifiers.visibility
                    )?.let {
                        "Component ${ind + 1} of the summary expression has type $actualCVL, where ${entry.prettyPrint()} expected $expectedVM at that position"
                    }
                }.forEach { msg ->
                    collectError(CVLError.Exp(typeCheckedBody, msg))
                }
            }
            else -> {
                if (expectedReturn.size != 1) {
                    returnError(CVLError.Exp(typeCheckedBody, "Summary expression returns 1 values, where ${entry.prettyPrint()} returns ${expectedReturn.size} values."))
                }
                val vm = expectedReturn.single()
                convertibilityErrors(actualReturn, vm, entry.qualifiers.visibility)?.let { errors ->
                    collectError(CVLError.Exp(typeCheckedBody, "Summary expression of type $actualReturn cannot be converted to the expected VM type ${vm.prettyPrint()}: " + errors))
                }
            }
        }

        return@collectingErrors summary.copy(exp = typeCheckedBody)
    }

    /**
     * Check whether any function from the implementing functions in the [CVLSymbolTable.SymbolInfo] matches given
     * signature hash.
     */
    private fun checkFuncForOverload(info: CVLSymbolTable.SymbolInfo?, sig: BigInteger): Boolean =
        info != null && info is CVLSymbolTable.SymbolInfo.CVLFunctionInfo
            && info.impFuncs.any { when(it) {
            is ContractFunction -> it.sigHash == sig
            is CVLDefinition,
            is CVLFunction,
            is CVLGhostDeclaration.Function -> false
        } }

    /**
     * @return a type-checked copy of [summary].
     *
     * A DISPATCHER summary is well-typed if it is not marked ALL
     */
    private fun typeCheckDispatcherSummary(
        symbolTable: CVLSymbolTable,
        summary : SpecCallSummary.Dispatcher,
        entry: MethodBlockEntry,
    ) : CollectingResult<SpecCallSummary.Dispatcher, CVLError> = collectingErrors {
        if (summary.summarizeAllCalls) {
            // a dispatcher only really makes sense on unresolved calls
            returnError(CVLError.General(message = "A summary ${SpecCallSummary.FORCED_KEYWORD} is not allowed for " +
                "dispatcher summaries, please remove the ${SpecCallSummary.FORCED_KEYWORD} keyword " +
                "or add the ${SpecCallSummary.UNRESOLVED_KEYWORD} for the summary ${entry.summary}", range = entry.range))
        }
        if (entry !is ConcreteMethodBlockAnnotation) {
            returnError(CVLError.General(message = "A dispatcher summary can not be applied on method block entry ${entry.prettyPrint()}.", range = entry.range))
        }
        if (summary.optimistic && matchingFunctionsInSymbolTable(symbolTable, entry.methodParameterSignature).isEmpty()) {
            collectError(DispatcherSummaryNoImplementation(entry.range))
        }
        return@collectingErrors summary
    }

    private fun matchingFunctionsInSymbolTable(symbolTable: CVLSymbolTable, methodParameterSignatureInCVLSpec: MethodParameterSignature): List<Function<*, *>> {
        val scopes = symbolTable.getAllContractScopes()
        return scopes.flatMap {
            scope ->
                val symbolTableElement = symbolTable.lookUpFunctionLikeSymbol(methodParameterSignatureInCVLSpec.functionName, scope)
                if(symbolTableElement is CVLSymbolTable.SymbolInfo.CVLFunctionInfo){
                    symbolTableElement.impFuncs.
                        filterIsInstance<ContractFunction>().
                        filter { impFunc -> impFunc.methodSignature.matchesNameAndParams(methodParameterSignatureInCVLSpec) }
                } else{
                    emptyList()
                }
        }
    }

    private fun typeCheckDispatchListSummary(symbolTable: CVLSymbolTable, dispatchList: SpecCallSummary.DispatchList, entry: MethodBlockEntry) = collectingErrors {
        check(dispatchList.summarizationMode == SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY) {
            "Dispatch list should only be applied to unresolved summaries."
        }
        for (p in dispatchList.dispatcherList) {
            when(p) {
                is SpecCallSummary.DispatchList.Pattern.QualifiedMethod -> {
                    check(p.sig.sighashInt != null) {"Expecting to always know sighash of methods in dispatch list patterns"}
                    if (entry is ConcreteMethodBlockAnnotation && !p.sig.matchesNameAndParams(entry.methodParameterSignature)) {
                        collectError(DispatchListWithMismatchedMethodSig(p, entry.methodParameterSignature))
                    } else if (symbolTable.getContractScope(p.sig.qualifiedMethodName.host) == null) {
                        collectError(DispatchListContractNotFound(p))
                    } else {
                        val info = symbolTable.lookupMethodInContractEnv(p.sig.qualifiedMethodName.host, p.sig.qualifiedMethodName.methodId)
                        if (!checkFuncForOverload(info, p.sig.sighashInt!!.n )) {
                            collectError(DispatchListNoMatchingMethodFound(p))
                        }
                    }
                }
                is SpecCallSummary.DispatchList.Pattern.WildcardContract -> {
                    check(p.sig.sighashInt != null) {"Expecting to always know sighash of methods in dispatch list patterns"}
                    if (entry is ConcreteMethodBlockAnnotation && !p.sig.matchesNameAndParams(entry.methodParameterSignature)) {
                        collectError(DispatchListWithMismatchedMethodSig(p, entry.methodParameterSignature))
                    } else {
                        val scopes = symbolTable.getAllContractScopes()
                        val anyMatching = scopes.any { scope ->
                            val info = symbolTable.lookUpFunctionLikeSymbol(p.sig.functionName, scope)
                            checkFuncForOverload(info, p.sig.sighashInt!!.n)
                        }
                        if (!anyMatching) {
                            collectError(DispatchListNoMatchingMethodFound(p))
                        }
                    }
                }
                is SpecCallSummary.DispatchList.Pattern.WildcardMethod -> {
                    check(entry !is ConcreteMethodBlockAnnotation || dispatchList.useFallback) { "We should have replaced the wildcard method in the dispatch list for an entry with given sighash and no use_fallback" }
                    if (null == symbolTable.getContractNameFromContractId(p.contract.contract)) {
                        collectError(DispatchListContractNotFound(p))
                    }
                }
            }
        }
        dispatchList
    }

    /**
     * @return null if [from] is convertible to [to] in the context of a method with [visibility];
     *         a string containing any errors otherwise
     */
    private fun convertibilityErrors(from : CVLType.PureCVLType, to : VMTypeDescriptor, visibility: Visibility) : String? {
        val visitor = when(visibility) {
            Visibility.INTERNAL -> ToVMContext.InternalSummaryReturn.getVisitor()
            Visibility.EXTERNAL -> ToVMContext.ExternalSummaryReturn.getVisitor()
        }

        return to.converterFrom(from, visitor).errorOrNull()?.joinToString()
    }
}
