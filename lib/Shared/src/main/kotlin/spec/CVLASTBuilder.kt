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

import bridge.ContractInstanceInSDC
import bridge.MethodInContract
import compiler.SolidityFunctionStateMutability
import config.Config
import datastructures.stdcollections.*
import evm.SighashInt
import log.*
import scene.ICVLScene
import scene.MethodAttribute
import spec.cvlast.*
import spec.cvlast.transformations.*
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.*
import spec.cvlast.typedescriptors.*
import spec.genericrulegenerators.BuiltInRuleGenerator
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.bindEither
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.flattenToVoid
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.mapErrors
import utils.CollectingResult.Companion.ok
import utils.CollectingResult.Companion.reduceErrors
import utils.ErrorCollector.Companion.collectingErrors

private val logger = Logger(LoggerTypes.SPEC)

// subject to change
private const val OPTIONAL_METHOD_NAME: String = "optional"

interface IRunIdFactory {
    fun reportCVL(cvlFile: String, ast: CVLAst)
}

class CVLAstBuilder(
    private val cvlInput: CVLInput,
    val contracts: List<ContractInstanceInSDC>,
    val mainContract: ContractInstanceInSDC,
    val scene: ICVLScene,
    val runIdFactory: IRunIdFactory?,
) {

    fun build(): CollectingResult<CVL, CVLError> = collectingErrors {
        var ast: CVLAst = bind(cvlInput.getRawCVLAst(Config.VMConfig.getTypeResolver(scene, mainContract = mainContract.name)))

        // Verify the imported contract actually exists and has a unique alias
        ast.importedContracts.map { importedContract ->
            if (contracts.none { c -> c.name == importedContract.solidityContractName.name }) {
                returnError(CVLError.General(
                    range = Range.Empty(),
                    message = "\"using ${importedContract.solidityContractName}\" - could not find contract instance for \"${importedContract.solidityContractName}\""
                ))
            } else {
                ast.importedContracts.groupingBy { it.solidityContractVarId }.eachCount()
                    .filter { it.value > 1 }.keys.firstOrNull()?.let { duplicateKey ->
                        returnError(CVLError.General(
                            range = Range.Empty(),
                            message = "Multiple declaration of alias \"${duplicateKey}\""
                        ))
                    }
            }
        }

        // add currentContract mapping to list of imported contract
        // this is for making all implicit accesses to methods of  the current contract an explicit reference
        val (declaredInternalFunctions, declaredExternalFunctions) = run {
            val int = mutableMapOf(
                // initialize the map with all the contracts we already know about.
                *scene.getContracts().map { c ->
                    c.name to mutableListOf<MethodInContract>()
                }.toTypedArray()
            )
            val ext = mutableMapOf<ContractInstanceInSDC, List<ContractFunction>>()
            contracts
                // to avoid repeated contract declarations, it's sufficient to look at the contract names + original files
                .distinctBy { Pair(it.name, it.original_file) }
                .forEach { c ->
                    val contract = SolidityContract(c.name)
                    ext[c] =
                        c.getMethodsAndConstructor()
                            .map { it.toContractFunction(contract, Visibility.EXTERNAL, c) } +
                            ContractFunction(
                                methodSignature = UniqueMethod(contract, MethodAttribute.Unique.Fallback),
                                annotation = MethodQualifiers(
                                    envFree = false,
                                    library = false,
                                    virtual = false,
                                    visibility = Visibility.EXTERNAL
                                ),
                                evmExternalMethodInfo = null,
                                definitelyNonPayable = false,
                                stateMutability = SolidityFunctionStateMutability.payable
                            )
                    c.internalFunctions.forEachEntry { (_, m) ->
                        val finderContractEntry =
                            int.computeIfAbsent(m.declaringContract) { mutableListOf() }
                        finderContractEntry.add(m)
                    }
                }
            int.toMap() to ext.toMap()
        }

        // Merge the info on functions from the compiler and the methods block
        val functionInfo = bind(combineFunctions(
            ast.importedMethods.filterIsInstance<ConcreteMethodBlockAnnotation>(),
            externalContractFunction = declaredExternalFunctions,
            internalContractFunctions = declaredInternalFunctions,
        ))

        collect(validateRuleChoices(ast))
        collect(validateMethodChoices(functionInfo.values.flatten(), mainContract.name))

        ast = bind(CleanEmptyHooks.check(ast))
        ast = bind(SingleVariableDefinitionChecker().check(ast))
        ast = bind(QuantifierVarRenamer().ast(ast))
        ast = bind(generateAndAddExtraRules(ast, functionInfo, mainContract.name))

        // Type-check (and type-assign) the ast, and build the symbol table
        val (symbolTable, newAst) = bind(buildSymbolTableAndTypeAnnotatedAst(
            ast, contracts, functionInfo, primaryContract = mainContract.name
        ))
        ast = newAst

        runIdFactory?.reportCVL(cvlInput.cvlSource.name, ast)
        logger.info { "Spec $ast" }

        val (astSummaries, rulesAndFilters) = bind(
            extractSummaries(
                methodAnnotations = ast.importedMethods,
                externalContractFunction = declaredExternalFunctions
            ),
            CalculateMethodParamFilters(
                SolidityContract(mainContract.name),
                contracts,
                symbolTable
            ).calculate(ast.rules)
        )

        return@collectingErrors CVL(
            cvlInput.cvlSource.origpath,
            SolidityContract(mainContract.name),
            functionInfo,
            rulesAndFilters.first,
            ast.subs,
            ast.invs,
            ast.sorts,
            ast.ghosts,
            ast.hooks,
            symbolTable,
            ast.importedContracts,
            ast.scope,
            astSummaries.internal,
            astSummaries.external,
            astSummaries.unresolved,
            rulesAndFilters.second
        )
    }

    private interface SummaryGenerator {
        fun external(sighashABI: SighashInt?, sighashLib: SighashInt, methodParameterSignature: MethodParameterSignature) : VoidResult<CVLError>
        fun internal(methodParameterSignature: MethodParameterSignature): VoidResult<CVLError>
    }

    private data class AstSummaries(val internal: Map<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
                            val external: Map<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>,
                            val unresolved: Map<CVL.SummarySignature.External, SpecCallSummary.DispatchList>)

    private fun extractSummaries(
        methodAnnotations: List<MethodBlockEntry>,
        externalContractFunction: Map<ContractInstanceInSDC, Collection<ContractFunction>>
    ): CollectingResult<AstSummaries, CVLError> {
        val externalSummaries =
            mutableMapOf<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>() // TODO better classification
        val internalSummaries = mutableMapOf<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>()
        val unresolved = mutableMapOf<CVL.SummarySignature.External, SpecCallSummary.DispatchList>()
        return methodAnnotations.map { annot ->
            when(annot) {
                is CatchAllSummaryAnnotation -> {
                    val k = CVL.ExternalAnyInContract(hostContract = annot.target.contract)
                    if(k in externalSummaries) {
                        return@map CVLError.General(
                            range = annot.range,
                            message = "Duplicate catch-all summarization for contract ${annot.target.contract}"
                        ).asError()
                    }
                    externalSummaries[k] = annot.summary
                    ok
                }
                is CatchUnresolvedSummaryAnnotation -> {
                    val k = when (val target = annot.target) {
                        is MethodEntryTargetContract.SpecificTarget -> if (annot.sig != null) {
                            CVL.ExternalExact(ExternalSignature.computeSigHash(isLibrary = false, annot.sig), annot.sig as QualifiedMethodParameterSignature)
                        } else {
                            CVL.ExternalAnyInContract(target.contract)
                        }
                        MethodEntryTargetContract.WildcardTarget -> if (annot.sig != null) {
                            CVL.ExternalWildcard(ExternalSignature.computeSigHash(isLibrary = false, annot.sig), annot.sig)
                        } else {
                            CVL.ExternalUnresolved
                        }
                    }
                    if (k in unresolved) {
                        return@map MultipleCatchUnresolvedSummaries(annot).asError()
                    }
                    unresolved[k] = annot.dispatchList
                    ok
                }
                is ConcreteMethodBlockAnnotation -> handleConcreteMethodAnnotation(
                    annot,
                    externalSummaries,
                    internalSummaries,
                    externalContractFunction
                )
            }
        }.flatten().map { AstSummaries(internalSummaries, externalSummaries, unresolved) }
    }

    private fun handleConcreteMethodAnnotation(
        annot: ConcreteMethodBlockAnnotation,
        externalSummaries: MutableMap<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>,
        internalSummaries: MutableMap<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
        externalContractFunction: Map<ContractInstanceInSDC, Collection<ContractFunction>>
    ) : VoidResult<CVLError> {
        if (annot.summary == null) {
            return ok
        }

        /**
         * Start summary annotation handling
         */
        val gen = when (annot.target) {
            is MethodEntryTargetContract.WildcardTarget -> {

                if (annot.qualifiers.envFree) {
                    return CVLError.General(
                        annot.range,
                        "Method annotation ${annot.methodParameterSignature} with both envfree and summary specification without explicit base contract is not allowed. If this is truly the behavior you want, add an explicit ${SolidityContract.Current.name} qualifier",
                    ).asError()
                }
                object : SummaryGenerator {
                    override fun external(
                        sighashABI: SighashInt?,
                        sighashLib: SighashInt,
                        methodParameterSignature: MethodParameterSignature
                    ): VoidResult<CVLError> {
                        return if (externalSummaries.any { (k, _) ->
                                k is CVL.ExternalWildcard && (k.sighashInt == sighashABI || k.sighashInt == sighashLib)
                            }) {
                            CVLError.General(
                                annot.range,
                                "Multiple summaries for all external functions with the signature ${annot.methodParameterSignature.printQualifiedMethodParameterSignature()}",
                            ).asError()
                        } else {
                            if (sighashABI != null) {
                                externalSummaries[CVL.ExternalWildcard(
                                    sighashInt = sighashABI,
                                    signature = annot.methodParameterSignature
                                )] = annot.summary
                            }

                            externalSummaries[CVL.ExternalWildcard(
                                sighashInt = sighashLib,
                                signature = annot.methodParameterSignature
                            )] = annot.summary
                            ok
                        }
                    }

                    override fun internal(methodParameterSignature: MethodParameterSignature): VoidResult<CVLError> {
                        return if (internalSummaries.any { (k, _) ->
                                // Confusingly enough, k.signature.matches(...) only compares the method arguments, not the
                                // method name, so we need to explicitly check both here.
                                // TODO: Fix all the signature comparison stuff
                                k is CVL.SummaryTarget.AnyContract && k.signature.matchesNameAndParams(
                                    methodParameterSignature
                                )
                            }) {
                            CVLError.General(
                                annot.range,
                                "Multiple summaries for all internal functions with the signature ${annot.methodParameterSignature.printQualifiedMethodParameterSignature()}",
                            ).asError()
                        } else {
                            internalSummaries[CVL.InternalWildcard(signature = methodParameterSignature)] =
                                annot.summary
                            ok
                        }
                    }
                }
            }

            is MethodEntryTargetContract.SpecificTarget -> {
                object : SummaryGenerator {
                    val methodId = QualifiedFunction(
                        methodId = annot.name,
                        host = annot.target.contract
                    )

                    fun getMatchingContractFunctionsIn(m: Map<ContractInstanceInSDC, Collection<ContractFunction>>): Collection<ContractFunction>? {
                        return getMatchingContractFunctionsIn(annot.target.contract.name, m)
                    }


                    override fun external(
                        sighashABI: SighashInt?,
                        sighashLib: SighashInt,
                        methodParameterSignature: MethodParameterSignature
                    ): VoidResult<CVLError> {
                        val matchingFunction =
                            getMatchingContractFunctionsIn(externalContractFunction)?.singleOrNull {
                                val sighash = if (it.annotation.library) {
                                    sighashLib
                                } else {
                                    sighashABI
                                }
                                (it.methodSignature as? ExternalSignature)?.sighashInt == sighash
                            }
                                ?: return if (annot.qualifiers.virtual) {
                                    ok
                                } else {
                                    CVLError.General(
                                        annot.range,
                                        "Added a summary for the external method ${annot.target.contract.name}.${annot.methodParameterSignature}, but no such method appears in the ABI",
                                    ).asError()
                                }

                        if (externalSummaries.any { (k, _) ->
                                k is CVL.ExternalExact && k.signature.qualifiedMethodName == methodId && (k.sighashInt == sighashABI || k.sighashInt == sighashLib)
                            }) {
                            return CVLError.General(
                                annot.range,
                                "Duplicate summaries for the external method ${annot.methodParameterSignature.printQualifiedMethodParameterSignature()}",
                            ).asError()
                        }
                        if (!annot.summary.summarizeAllCalls) {
                            CVLWarningLogger.syntaxWarning(
                                "Unforced summaries for explicit external methods have no effect",
                                annot.range
                            )
                        }

                        externalSummaries[CVL.ExternalExact(
                            sighashInt = if (matchingFunction.annotation.library) {
                                sighashLib
                            } else {
                                check(sighashABI != null) {
                                    "For $methodParameterSignature, found a matching non-library function, yet it has no valid non-lib sighash"
                                }
                                sighashABI
                            },
                            // ensured by type checker (since summary is non-null)
                            signature = methodParameterSignature as QualifiedMethodParameterSignature
                        )] = annot.summary
                        return ok
                    }


                    override fun internal(methodParameterSignature: MethodParameterSignature): VoidResult<CVLError> {
                        if (internalSummaries.any { (k, _) ->
                                k is CVL.SummaryTarget.ExactFunction
                                    // (if methodParameterSignature is not qualified, it is referring to a wildcard target)
                                    && methodParameterSignature is QualifiedMethodParameterSignature
                                    && k.signature.matchesContractAndParams(methodParameterSignature)
                            }) {
                            return CVLError.General(
                                annot.range,
                                "Multiple summaries for the internal method ${annot.methodParameterSignature.printQualifiedMethodParameterSignature()}",
                            ).asError()
                        }
                        internalSummaries[CVL.InternalExact(
                            // ensured by type checker (since summary != null)
                            signature = (methodParameterSignature as QualifiedMethodParameterSignature)
                        )] = annot.summary
                        return ok
                    }
                }
            }
        }

        return when (annot.qualifiers.visibility) {
            Visibility.EXTERNAL -> {
                // For external method signatures, if any argument has the `storage` location specifier, then we know it can
                // only be a signature of a library function (Solidity doesn't allow `storage` location on arguments of external
                // contract libraries).
                val couldBeContractFunc = !annot.methodParameterSignature.params.any { param ->
                    param.vmType is VMReferenceTypeDescriptor && (param.vmType as VMReferenceTypeDescriptor).location == EVMLocationSpecifier.storage
                }
                val summaryABIKey = if (couldBeContractFunc) {
                    ExternalSignature.computeSigHash(isLibrary = false, annot.methodParameterSignature)
                } else {
                    null
                }
                val summaryLibKey = ExternalSignature.computeSigHash(isLibrary = true, annot.methodParameterSignature)
                gen.external(summaryABIKey, summaryLibKey, annot.methodParameterSignature)
            }


            Visibility.INTERNAL -> {
                gen.internal(annot.methodParameterSignature)
            }
        }
    }

    private fun getMatchingContractFunctionsIn(methodId: String, m: Map<ContractInstanceInSDC, Collection<ContractFunction>>): Collection<ContractFunction>? {
        return m.entries.find {
            it.key.name == methodId
        }?.value
    }

    /**
     * Combine the functions declared in the methods block with the functions declared in the contracts.  Also perform
     * some validation of the methods block entries.
     *
     * The returned mapping will have the same set of keys as [externalContractFunction] (i.e. the same set of contracts);
     * the value collections (i.e. the set of contract methods) are updated to reflect specific-receiver method declarations,
     * defined as follows.
     *
     * Before the introduction of wildcards in CVL 2, a method declaration was specific-receiver if it is `external` and
     * any of the following hold:
     *  - it is `envfree`
     *  - it is `optional`
     *  - it has both a summary and an explicitly specified receiver
     *
     * Since the introduction of wildcards into CVL, a function is specific-receiver if it is not explicitly marked with
     * a wildcard receiver.
     *
     * In this case, the returned collection of methods is updated as follows:
     *  - entries that are marked `optional` (formerly `virtual`) are added
     *  - entries for external methods that exist in the contract are merged:
     *      - if the entry is `envfree` then the result is `envfree`
     *      - the arguments and return values are merged according to [ExternalMethodSignature.mergeWith]
     *
     * This method also validates several properties of the specific-contract entries:
     *  - the receiver contract exists in the scene and in [externalContractFunction]
     *  - non-optional methods exist in the contract
     *
     *  It also validates some properties of wildcard entries:
     *  - for external entries there should be no location specifiers, or only storage (which implies this is a library function)
     *  - for internal entries there must be a location specifier for all reference-type arguments.
     *
     * In addition, this method validates that internal entries are neither `envfree` nor `optional`; note that the
     * output is not updated with internal method summaries
     *
     * This function emits warnings in the following situations:
     *  - an entry is neither `optional`, `envfree`, nor a summary (the entry has no effect)
     *  - an (optional) method that does not exist in the contract is `envfree`
     */
    private fun combineFunctions(
        methodAnnotations: List<ConcreteMethodBlockAnnotation>,
        externalContractFunction: Map<ContractInstanceInSDC, Collection<ContractFunction>>,
        internalContractFunctions: Map<String, Collection<MethodInContract>>,
    ): CollectingResult<Map<ContractInstanceInSDC, List<ContractFunction>>, CVLError> {
        val toReturn = externalContractFunction.mapValuesTo(mutableMapOf()) {
            it.value.toMutableList()
        }
        val errors = mutableListOf<CVLError>()
        for (annot in methodAnnotations) {
            when (annot.target) {
                is MethodEntryTargetContract.SpecificTarget -> {
                    when (annot.methodParameterSignature) {
                        is MethodSignature -> Unit
                        else -> errors.add(CVLError.General(
                            range = annot.range,
                            message = "A method entry for a specific target must include return types. If the function returns nothing write `returns void`."
                        ))
                    }
                }
                MethodEntryTargetContract.WildcardTarget -> Unit
            }

            if (!annot.qualifiers.envFree && !annot.qualifiers.virtual && annot.summary == null) {
                CVLWarningLogger.syntaxWarning("Method declaration for ${annot.methodParameterSignature} is neither `envfree`, `optional`, nor summarized, so it has no effect.", annot.range)
                continue
            }

            fun contractNotFound(target: MethodEntryTargetContract.SpecificTarget) = CVLError.General(annot.range, "Contract `${target.contract.name}` not found. Receiver contracts must be `currentContract`, the name of a contract in the scene, or a name introduced by a `using` statement.").asError()

            fun getBaseContractForExternalFunction(target: MethodEntryTargetContract.SpecificTarget) =
                contracts.find { it.name == target.contract.name }.let { baseContract ->
                    if (baseContract == null || baseContract !in toReturn) {
                        contractNotFound(target)
                    } else {
                        baseContract.lift()
                    }
                }

            fun getBaseContractForInternalFunction(target: MethodEntryTargetContract.SpecificTarget) =
                internalContractFunctions[target.contract.name]?.lift() ?: contractNotFound(target)

            fun forEachMethodParam(checker: (paramName: String, paramVmType: VMTypeDescriptor) -> Unit) {
                annot.methodParameterSignature.params.forEachIndexed { index, vmParam ->
                    val paramName =
                        (vmParam as? VMParam.Named)?.id
                            ?: "the ${(index + 1).toOrdinalString()} argument"
                    val paramVmType = vmParam.vmType
                    checker(paramName, paramVmType)
                }
            }

            val isOk: VoidResult<CVLError> = when (annot.qualifiers.visibility) {
                Visibility.EXTERNAL -> {
                    when (annot.target) {
                        is MethodEntryTargetContract.SpecificTarget -> Unit
                        MethodEntryTargetContract.WildcardTarget -> {
                            // Verify the existence or non-existence of location specifiers on reference-type arguments
                            forEachMethodParam { paramName, paramVmType ->
                                if (paramVmType !is VMReferenceTypeDescriptor) {
                                    // all's good
                                    return@forEachMethodParam
                                }
                                if (paramVmType.location != null && paramVmType.location != EVMLocationSpecifier.storage) {
                                    errors.add(
                                        CVLError.General(
                                            range = annot.range,
                                            message = "In entry for ${annot.methodParameterSignature}, $paramName is a reference type with non-storage location specifier ${paramVmType.location}, " +
                                                "which are not part of external (contract or library) method signatures"
                                        )
                                    )
                                }
                            }
                            continue
                        }
                    }

                    // give an error for unresolved specific contract methods
                    val unresolvedFlag = if (annot.summary?.summarizationMode == SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY) {
                        CVLError.General(annot.range, "`UNRESOLVED` summaries can only be used on wildcard receivers; " +
                            "a call to `${annot.target.prettyPrint()}.${annot.methodParameterSignature.functionName}` can never be unresolved.  " +
                            "Did you mean to summarize `_.${annot.methodParameterSignature.printMethodParameterSignature()}`?").asError()
                    } else {
                        ok
                    }
                    // find the base contract (failing if it does not exist)
                    val baseContract = getBaseContractForExternalFunction(annot.target)

                    baseContract.bind(unresolvedFlag) { _baseContract, _ ->
                        val functionReference = QualifiedFunction(
                            host = SolidityContract(_baseContract.name),
                            methodId = annot.name
                        )

                        val externalABISignature =
                            ExternalQualifiedMethodParameterSignature.fromNamedParameterSignatureContractId(
                                QualifiedMethodParameterSignature(
                                    functionReference,
                                    annot.methodParameterSignature.params
                                ),
                                PrintingContext(false)
                            )
                        val externalLibSignature =
                            ExternalQualifiedMethodParameterSignature.fromNamedParameterSignatureContractId(
                                QualifiedMethodParameterSignature(
                                    functionReference,
                                    annot.methodParameterSignature.params
                                ),
                                PrintingContext(true)
                            )


                        // find the method in the base contract
                        val contractList = toReturn[_baseContract]!!
                        val idxOfMatch = contractList.indexOfFirst {
                            // The unique methods (constructor, fallback) don't have an ExternalSignature, but we don't
                            // expect to have annotations for them in any case. This is the reason for the safe-cast.
                            (it.methodSignature as? ExternalSignature)?.sighashInt == if (it.annotation.library) {
                                externalLibSignature.sighashInt
                            } else {
                                externalABISignature.sighashInt
                            }
                        }

                        // by checkSignatures & above branch condition on target type
                        check(annot.methodParameterSignature is MethodSignature)

                        if (idxOfMatch == -1) {
                            // add the method if it doesn't exist
                            val virtualFlag = if (!annot.qualifiers.virtual) {
                                CVLError.General(annot.range, "External method declaration for ${annot.methodParameterSignature}" +
                                    " does not correspond to any known declaration. Did you mean to add $OPTIONAL_METHOD_NAME?").asError()
                            } else {
                                CVLWarningLogger.syntaxWarning("Method declaration has no counterpart in contracts (${annot.methodParameterSignature.printQualifiedMethodParameterSignature()})", annot.range)
                                ok
                            }
                            if (annot.qualifiers.envFree) {
                                CVLWarningLogger.syntaxWarning("No implementation for function ${annot.methodParameterSignature.printQualifiedMethodParameterSignature()} exists, env-free check will be skipped", annot.range)
                            }
                            val generatedFunction = ContractFunction(
                                stateMutability = SolidityFunctionStateMutability.payable,
                                annotation = MethodQualifiers(
                                    visibility = annot.qualifiers.visibility,
                                    envFree = annot.qualifiers.envFree,
                                    library = false /* doesn't really matter, this function doesn't really exist anyway */,
                                    virtual = true
                                ),
                                definitelyNonPayable = false,
                                evmExternalMethodInfo = null,
                                methodSignature = ExternalQualifiedMethodSignature(
                                    contractId = functionReference,
                                    params = annot.methodParameterSignature.params,
                                    res = annot.methodParameterSignature.res,
                                    sighashInt = externalABISignature.sighashInt
                                )
                            )
                            virtualFlag.bind {
                                contractList.add(generatedFunction)
                                ok
                            }
                        } else {
                            // merge the method if it does exist
                            val currFun = contractList[idxOfMatch]
                            check(currFun.evmExternalMethodInfo != null) {
                                "Internal invariant broken, have a un-mergeable signature for a function that came from the compiler"
                            }
                            check(currFun.annotation.visibility == Visibility.EXTERNAL) {
                                "Internal invariant broken, merging an external function declaration with a non-external function"
                            }

                            // Verify the existence or non-existence of location specifiers on reference-type arguments
                            forEachMethodParam { paramName, paramVmType ->
                                if (paramVmType !is VMReferenceTypeDescriptor) {
                                    // all's good
                                    return@forEachMethodParam
                                }
                                if (paramVmType.location != null) {
                                    if (!currFun.annotation.library) {
                                        errors.add(CVLError.General(
                                            range = annot.range,
                                            message = "In entry `${annot.methodParameterSignature}`, $paramName is a reference type with location specifier ${paramVmType.location}, " +
                                                "location annotations are not allowed for non-library external methods"
                                        ))
                                    } else if (paramVmType.location != EVMLocationSpecifier.storage) {
                                        errors.add(CVLError.General(
                                            range = annot.range,
                                            message = "In entry for ${annot.methodParameterSignature}, $paramName is a reference type with a non-storage location ${paramVmType.location}, " +
                                                "external library function signatures may only have 'storage' locations"
                                        ))
                                    }
                                }
                            }

                            val signature = ExternalQualifiedMethodSignature(
                                contractId = functionReference,
                                params = annot.methodParameterSignature.params,
                                res = annot.methodParameterSignature.res,
                                sighashInt = if (currFun.annotation.library) {
                                    externalLibSignature.sighashInt
                                } else {
                                    externalABISignature.sighashInt
                                }
                            )

                            signature.mergeWith(currFun.methodSignature)
                                .mapErrors { CVLError.General(annot.range, it) }
                                .bind { ext ->
                                    contractList[idxOfMatch] = currFun.copy(
                                        methodSignature = ext, annotation = MethodQualifiers(
                                            visibility = Visibility.EXTERNAL,
                                            library = currFun.annotation.library,
                                            envFree = annot.qualifiers.envFree || currFun.annotation.envFree,
                                            virtual = false
                                        )
                                    )
                                    ok
                                }
                        }
                    }
                }
                Visibility.INTERNAL -> {
                    val virtualFlag = if (annot.qualifiers.virtual) {
                        CVLError.General(annot.range, "The optional qualifier is not allowed on internal functions").asError()
                    } else {
                        ok
                    }
                    val envfreeFlag = if (annot.qualifiers.envFree) {
                        CVLError.General(annot.range, "The envfree qualifier is not allowed on internal functions").asError()
                    } else {
                        ok
                    }
                    if (annot.summary == null) {
                        CVLWarningLogger.syntaxWarning("An internal function declaration without a summary has no effect", annot.range)
                    }
                    // moved this logic to JavaMethodsBlock.kt, so shouldn't be reachable. It will trigger if default
                    // summarization mode is badly set though
                    val unresolvedFlag = when (annot.summary?.summarizationMode) {
                        SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY ->
                            UnresolvedSummaryModeOnInternalMethod(annot.range).asError()

                        SpecCallSummary.SummarizationMode.DELETE ->
                            DeleteSummaryModeOnInternalMethod(annot.range).asError()

                        else -> ok
                    }
                    // Verify the existence or non-existence of location specifiers on reference-type arguments
                    forEachMethodParam { paramName, paramVmType ->
                        if (paramVmType !is VMReferenceTypeDescriptor) {
                            // all's good
                            return@forEachMethodParam
                        }

                        if (paramVmType.location == null) {
                            errors.add(
                                CVLError.General(
                                    range = annot.range,
                                    message = "In entry `${annot.methodParameterSignature}`, $paramName is a " +
                                        "reference type without a location specifier; " +
                                        "location specifiers (i.e. either `memory` or `calldata`) are required for " +
                                        "reference parameters in internal function signatures"
                                )
                            )
                        }
                    }
                    if (annot.summary != null && annot.methodParameterSignature is MethodSignature) {
                        annot.methodParameterSignature.res.forEachIndexed { index, cvlVMParam ->
                            val ty = cvlVMParam.vmType
                            if (ty is VMReferenceTypeDescriptor && ty.location == null) {
                                val resName = "return value ${(index + 1).toOrdinalString()}"
                                errors.add(CVLError.General(
                                    range = annot.range,
                                    message = "In summary entry for internal method ${annot.methodParameterSignature}, $resName is a " +
                                        "reference type without a location specifier; " +
                                        "location specifiers (i.e. either `memory` or `calldata`) are required for " +
                                        "reference parameters."
                                ))
                            }
                        }
                    }

                    // Finally done with validating location specifiers

                    val returnsAndFunctionIdOk = when (annot.target) {
                        is MethodEntryTargetContract.SpecificTarget -> {
                            getBaseContractForInternalFunction(annot.target).bind { findersMatchingContract ->
                                val matching = findersMatchingContract.map { m ->
                                    val methodSig = m.method.toMethodSignature(SolidityContract(m.declaringContract), Visibility.INTERNAL)
                                    m to methodSig
                                }.filter { (_, f) ->
                                    f.matchesNameAndParams(annot.methodParameterSignature)
                                }

                                val distinctByContract = matching.distinctBy { (m, _) -> m.declaringContract }

                                if (distinctByContract.size > 1) {
                                    CVLError.General(annot.range, "Internal method entry ${annot.methodParameterSignature} references method with multiple possible resolutions. Unable to apply entry.").asError()
                                } else if (matching.isEmpty()) {
                                    // It just might be that the user is trying to reference a function that's declared in some parent contract/library, so let's search
                                    // for such a function and provide a nicer message if this is the case
                                    val candidateDeclaringContracts = internalContractFunctions.flatMap { (_, methods) ->
                                        methods.filter { m ->
                                            m.method.toMethodSignature(annot.target.contract, Visibility.INTERNAL).matchesNameAndParams(annot.methodParameterSignature)
                                        }
                                    }.mapToSet { it.declaringContract }
                                    if (candidateDeclaringContracts.isNotEmpty()) {
                                        val onlyMethodName = annot.name + "(" + annot.methodParameterSignature.paramListString() + ")"
                                        CVLError.General(
                                            annot.range,
                                            "Internal method `$onlyMethodName` is not declared in contract `${annot.target.contract}`, " +
                                                "although it is defined in `${candidateDeclaringContracts.joinToString(separator = "`, `")}`. " +
                                                "Consider changing entry from `${annot.prettyPrint()}` to `${candidateDeclaringContracts.first()}.$onlyMethodName`. " +
                                                "Note that entries for inherited methods must use the defining contract as the receiver, rather than the inheriting contract."
                                        ).asError()
                                    } else {
                                        CVLError.General(
                                            annot.range,
                                            "Internal method entry ${annot.methodParameterSignature} does not appear in code."
                                        ).asError()
                                    }
                                } else {
                                    check(annot.methodParameterSignature is MethodSignature)
                                    val matchedSignature = matching.first().second
                                    // Special handling for internal functions: if the bytecode signature has no return types
                                    // but the CVL signature does, use the CVL signature's return types
                                    if (matchedSignature.res.isEmpty() && annot.methodParameterSignature.res.isNotEmpty()) {
                                        // Internal functions from bytecode often don't have return type information
                                        // In this case, trust the CVL specification
                                        ok
                                    } else {
                                        MergeableSignature.mergeReturns(matchedSignature, annot.methodParameterSignature).bindEither(
                                            errorCallback = { errorList -> CVLError.General(annot.range, "Bad internal method returns: ${errorList.joinToString(", ")}").asError()},
                                            resultCallback = { ok }
                                        )
                                    }
                                }
                            }
                        }
                        MethodEntryTargetContract.WildcardTarget -> run {
                            /**
                             * Don't bother warning if the summary will be rejected by the method block type typechecker later.
                             */
                            if(annot.summary != null && annot.summary is SpecCallSummary.InternalSummary && annot.summary !is SpecCallSummary.Exp) {
                                val problematicMatches = internalContractFunctions.asSequence().flatMap {
                                    it.value.asSequence()
                                }.map {
                                    it.method.toMethodSignature(SolidityContract(it.declaringContract), Visibility.INTERNAL)
                                }.filter {
                                    it.matchesNameAndParams(annot.methodParameterSignature)
                                }.filter {
                                    it.resType.any {
                                        it !is VMValueTypeDescriptor
                                    }
                                }.toList()
                                if(problematicMatches.isNotEmpty()) {
                                    return@run CVLError.General(
                                        message = "Cannot use summary ${annot.summary.summaryName} for wildcard entry `${annot.methodParameterSignature.printMethodParameterSignature()}`; " +
                                            "it will match the internal function(s) ${problematicMatches.joinToString(", ") {
                                                "`${it.printMethodSignature()}`"
                                            }} which return reference typed values, and using a ${annot.summary.summaryName} summary for these values can lead to unsoundness. Consider using an exact method signature " +
                                            "to match specific instances of `${annot.methodParameterSignature.functionName}` that do not return reference typed values.",
                                        range = annot.range
                                    ).asError()
                                }
                            }
                            ok
                        }
                    }

                    listOf(virtualFlag, envfreeFlag, unresolvedFlag, returnsAndFunctionIdOk).flattenToVoid()
                }
            }
            isOk.reduceErrors { eList ->
                errors.addAll(eList)
            }
        }
        if (errors.isNotEmpty()) {
            return errors.asError()
        }
        return toReturn.lift()
    }

    /**
     *
     * @param [cvlAst] is the AST we build the symbol table for.
     * @param [contractToItsFunctionsFromSpecFileAndContracts] a map from a contract to its functions; a function declaration can come from the contract (Solidity) or
     *     from the methods block in our spec (CVL/Specify).
     */
    private fun buildSymbolTableAndTypeAnnotatedAst(
        cvlAst: CVLAst,
        contracts: List<ContractInstanceInSDC>,
        contractToItsFunctionsFromSpecFileAndContracts: Map<ContractInstanceInSDC, List<ContractFunction>>,
        primaryContract: String
    ): CollectingResult<Pair<CVLSymbolTable, CVLAst>, CVLError> = collectingErrors {
        fun buildSymbolTable(
            ast: CVLAst,
        ): CollectingResult<CVLSymbolTable, CVLError> {
            val symbolTable = CVLSymbolTable()
            val symbolTableFiller =
                SymbolTableFiller(symbolTable, ast, contractToItsFunctionsFromSpecFileAndContracts, primaryContract, contracts)
            return symbolTableFiller.traverseAst().map {
                symbolTable.finalize()
                symbolTable
            }

        }

        var ast = bind(GhostTypeRewriter.traverseAst(cvlAst))
        var symbolTable = bind(buildSymbolTable(ast))

        ast = bind(resolveUnresolvedApplyExpression(ast, symbolTable))
        ast = bind(DefinitionDependencyChecker(symbolTable).ast(ast))

        // because the symbol table actually contains some AST nodes, we need to refill it since we mutated the AST
        // for example definition bodies, hook axioms etc.
        symbolTable = bind(buildSymbolTable(ast))
        ast = bind(ArrayLiteralTypeHintInstrumenter.ast(ast))

        ast = bind(GhostApplicationRewriter(symbolTable).ast(ast))

        ast = bind(CVLAstTypeChecker(symbolTable).typeCheck(ast))

        // again, we want the type-checked AST nodes in the symbol table, so we refill
        symbolTable = bind(buildSymbolTable(ast))

        ast = bind(DefinitionsInliner(symbolTable).ast(ast))
        ast = bind(CVLAstAdditionalChecks(symbolTable).ast(ast))

        ast = bind(MoveMethodVarsToParams().ast(ast))
        ast = bind(SkipOptionalRules(symbolTable).filterRules(ast))
        ast = bind(SatisfyInRuleChecker(ast).ast(ast))
        ast = bind(FilterDeletedMethods(ast, contractToItsFunctionsFromSpecFileAndContracts).addFilters())

        ast = bind(StorageComparisonAnnotator(ast).ast(ast))
        collect(StorageComparisonChecker.check(ast))
        ast = bind(StorageAccessChecker.ast(ast))
        ast = bind(CVLGhostSumGenerator.generate(ast))

        // again, we want the type-checked AST nodes in the symbol table, so we refill
        symbolTable = bind(buildSymbolTable(ast))

        return@collectingErrors symbolTable to ast
    }

    /**
     * Computed required built-in rule generators based on used rules from the AST [astWithoutMergedImports]
     */
    private fun getGeneratorsOfBuiltInRulesInUse(astWithoutMergedImports: CVLAst): CollectingResult<List<BuiltInRuleGenerator>, CVLError> {
        if (astWithoutMergedImports.useDeclarations.builtInRulesInUse.isEmpty()) {
            return emptyList<BuiltInRuleGenerator>().lift() // No imports of builtin rules
        }

        val (birUseDecls, unresolvedBirUseDecls) = astWithoutMergedImports.useDeclarations.builtInRulesInUse
            .partition { it.resolveBuiltInRuleIdOrNull() != null }

        val errors = mutableListOf<CVLError>()
        // Report any unresolved builtin rules use declarations
        unresolvedBirUseDecls.forEach { unresolvedDecl ->
            errors.add(CVLError.General(
                unresolvedDecl.range,
                "Cannot resolve the 'use' declaration: unknown identifier ${unresolvedDecl.id}"
            ))
        }

        val birsUseDeclsToIds = birUseDecls.map { birUseDecl ->
            birUseDecl.resolveBuiltInRuleIdOrNull()!!.let { BuiltInRuleGenerator.BirsMetadata(birUseDecl, it, birUseDecl.methodParamFilters) }
        }

        val (birsInUseDistinctIds, repeatingBirsIds) = birsUseDeclsToIds.groupBy { it.birId }.toList().partition { it.second.size == 1 }

        repeatingBirsIds.forEach { (repeatingBirId, useDecls) ->
            useDecls.forEach {
                errors.add(
                    CVLError.General(
                        it.birUseDecl.range,
                        "Another 'use' declaration also uses the identifier $repeatingBirId. " +
                            "Each identifier can occur in at most one 'use' declaration"
                    )
                )
            }
        }

        return birsInUseDistinctIds
            .map { it.second.single() }
            .map { birMetadata -> BuiltInRuleGenerator.fromBirMetadata(birMetadata, birMetadata.methodParamFilters) }
            .flatten().map(errors.flattenToVoid()) { generators, _ -> generators }
    }

    /**
     * In addition to the rules specified by the user, we generate rules, e.g., for invariants, for checking envfree,
     *  and for the builtin rules.
     * These extra rules are added to the CVLAst in this method.
     */
    private fun generateAndAddExtraRules(
        cvlAst: CVLAst,
        functionsFromSpecFileAndContracts: Map<ContractInstanceInSDC, List<ContractFunction>>,
        mainContractName: String
    ): CollectingResult<CVLAst, CVLError> {
        val generatedBirs = getGeneratorsOfBuiltInRulesInUse(cvlAst).bind { birGenerators ->
            birGenerators.map { birGenerator ->
                val range = birGenerator.getRange(cvlAst)
                birGenerator.generate(CVLScope.AstScope, range, mainContractName, functionsFromSpecFileAndContracts)
            }.flatten()
        }
        // create a rule group for each invariant
        return GenerateRulesForInvariantsAndEnvFree(
            functionsFromSpecFileAndContracts.values.flatten(),
            cvlAst,
            contracts,
            mainContract
        ).generatedRules().map(generatedBirs) { extraRulesForInvariants, birs ->
            cvlAst.copy(rules = cvlAst.rules + extraRulesForInvariants + birs)
        }
    }

    private fun resolveUnresolvedApplyExpression(ast: CVLAst, symbolTable: CVLSymbolTable): CollectingResult<CVLAst, CVLError> {
        class UnresolvedApplyResolver : CVLExpTransformer<CVLError> {
            private fun resolveApply(exp: CVLExp.UnresolvedApplyExp): CollectingResult<CVLExp.ApplicationExp, CVLError> {
                if (exp.base != null) {
                    // Definitely calling a function from some contract, so we can only resolve during/after typechecking.
                    return exp.lift()
                } else {
                    val resolved = symbolTable.lookUpFunctionLikeSymbol(exp.methodId, ast.scope)?: symbolTable.lookupMethodInContractEnv(CurrentContract, exp.methodId)

                    if (resolved !is CVLSymbolTable.SymbolInfo.CVLFunctionInfo) {
                        // this is not a concrete function application, so resolving can only be done during/after type-checking
                        return exp.lift()
                    }

                    val func = resolved.symbolValue
                    if (func is ContractFunction) {
                        // Contract functions can only be resolved during/after typechecking
                        return exp.lift()
                    }
                    check(func is SpecFunction)

                    check(resolved.impFuncs.size == 1) {
                        "non-contract functions can't have overloading"
                    }
                    return if (func !is CVLGhostDeclaration.Function && exp.twoStateIndex != TwoStateIndex.NEITHER) {
                        TwoStateOnNonGhostFunction(exp, exp.twoStateIndex).asError()
                    } else if (!exp.invokeIsSafe && !Config.CvlFunctionRevert.get()) {
                        WithrevertOnNonContractCall(exp, func).asError()
                    } else if (!exp.invokeStorage.isLastStorage()) {
                        AtOnNonContractCall(exp, func).asError()
                    } else {
                        when (func) {
                            is CVLFunction      -> CVLExp.ApplyExp.CVLFunction (exp.methodId, exp.args, exp.tag, exp.invokeIsSafe)
                            is CVLDefinition    -> CVLExp.ApplyExp.Definition  (exp.methodId, exp.args, exp.tag)
                            is CVLGhostDeclaration.Function -> CVLExp.ApplyExp.Ghost       (exp.methodId, exp.args, exp.tag, exp.twoStateIndex)
                        }.lift()
                    }
                }
            }

            override fun application(exp: CVLExp.ApplicationExp): CollectingResult<CVLExp, CVLError> {
                return if (exp is CVLExp.UnresolvedApplyExp) {
                    resolveApply(exp)
                } else {
                    exp.lift()
                }.bind { maybeResolved ->
                    super.application(maybeResolved)
                }
            }
        }

        val astChecker = CVLAstTransformer(
            CVLCmdTransformer(UnresolvedApplyResolver())
        )
        return astChecker.ast(ast)
    }

    private fun ExternalQualifiedMethodSignature.mergeWith(other: MethodParameterSignature): CollectingResult<ExternalQualifiedMethodSignature, String> {
        val mergedParams = MergeableSignature.mergeParameters(this, other)
        val mergedReturns = if(other is MethodSignature) {
            MergeableSignature.mergeReturns(this, other)
        }   else {
            this.res.lift()
        }

        return mergedParams.map(mergedReturns) { params, res ->
            ExternalQualifiedMethodSignature(
                contractId = qualifiedMethodName, params = params, res = res, sighashInt = sighashInt
            )
        }
    }
}
