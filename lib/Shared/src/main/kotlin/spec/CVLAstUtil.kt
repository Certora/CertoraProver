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
import com.certora.collect.*
import config.Config
import config.Config.containsMethodFilteredByConfig
import datastructures.stdcollections.*
import evm.SighashInt
import log.Logger
import log.LoggerTypes
import scene.CONSTRUCTOR
import scene.MethodAttribute
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.DuplicatePreserved
import spec.cvlast.typechecker.ExplicitPreservedOnExtensionContract
import spec.cvlast.typedescriptors.FromVMContext
import spec.cvlast.typedescriptors.PrintingContext
import spec.rules.*
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.safeForce
import utils.ErrorCollector.Companion.collectingErrors
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COMMON)

class BadSpecException(override val message: String) : Exception(message)



/**
 * More processed than [CVLAst], e.g. contains automatically generated rules, see [CVLAstBuilder.build].
 * This is the form that the spec is passed into `CVLCompiler`.
 */
data class CVL(
    val name: String,
    val primaryContract: SolidityContract,
    val importedFuncs: Map<ContractInstanceInSDC, List<ContractFunction>>, // only funcs from "methods" section, usually one needs to use "availableMethods"
    override val rules: List<ICVLRule>,
    override val subs: List<CVLFunction>,
    val invariants: List<CVLInvariant>,
    val sorts: List<SortDeclaration>,
    override val ghosts: List<CVLGhostDeclaration>,
    override val hooks: List<CVLHook>,
    val symbolTable: CVLSymbolTable,
    val importedContracts: List<CVLImportedContract>,
    val astScope: CVLScope, // the tippy-top, big-daddy scope of the AST
    val internal: Map<SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
    val external: Map<SummarySignature.External, SpecCallSummary.ExpressibleInCVL>,
    override val unresolvedSummaries: Map<SummarySignature.External, SpecCallSummary.DispatchList>,
    val methodFilters: Map<RuleIdentifier, Map<String, List<Method>>> /* rule-identifier -> method-param-name -> list of usable methods */
) : IAstCodeBlocks, IWithSummaryInfo {

    override val invs: List<CVLInvariant>
        get() = invariants
    override val definitions: List<CVLDefinition>
        get() = listOf()
    override val internalSummaries: Map<SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>
        get() = internal
    override val externalSummaries: Map<SummarySignature.External, SpecCallSummary.ExpressibleInCVL>
        get() = external

    @Treapable
    sealed interface SummarySignature : AmbiSerializable {
        val signature: MethodParameterSignature?
        val methodId: String?
            get() = signature?.functionName

        fun getArgContext(): FromVMContext

        /**
         * The function signature, without params' names.
         */
        val funcSignature: String?
            get() = signature?.let { sig ->
                methodId?.let { m ->
                    m + sig.params.joinToString(", ", "(", ")") { it.vmType.prettyPrint() }
                }
            }

        sealed interface External : SummarySignature, Comparable<External> {

            override fun compareTo(other: External): Int {
                if(this.javaClass != other.javaClass) {
                    return this.javaClass.canonicalName.compareTo(other.javaClass.canonicalName)
                }
                if(this is ExternalWithSignature) {
                    return this.sighashInt.n.compareTo((other as ExternalWithSignature).sighashInt.n)
                }
                check(this is ExternalAnyInContract)
                return this.hostContract.name.compareTo((other as ExternalAnyInContract).hostContract.name)
            }

            override fun getArgContext(): FromVMContext = FromVMContext.ExternalSummaryArgBinding
        }

        sealed interface ExternalWithSignature : External {
            val sighashInt: SighashInt
            override val signature: MethodParameterSignature
            override val funcSignature: String
                get() = super.funcSignature!!
            override val methodId: String?
                get() = super.methodId!!
        }

        sealed interface Internal : SummarySignature, Comparable<Internal> {
            override val signature: MethodParameterSignature
            override val funcSignature: String
                get() = super.funcSignature!!
            override val methodId: String?
                get() = super.methodId!!

            /**
             * The function signature, including params' names (if such exist).
             */
            val namedFuncSignature: String
                get() = signature.printMethodParameterSignature()


            private fun <T: Internal> T.compareByHash(other: T, extract: T.() -> MethodParameterSignature) : Int {
                val thisHash = this.hashCode()
                val otherHash = other.hashCode()
                return if(thisHash < otherHash) {
                    -1
                } else if(thisHash > otherHash) {
                    1
                } else {
                    if(this == other) {
                        0
                    } else {
                        val str = this.extract().printMethodSignature().compareTo(other.extract().printMethodSignature())
                        if(str == 0) {
                            throw IllegalStateException("Cannot disambiguate ordering for $this and $other")
                        }
                        str
                    }
                }
            }

            override fun compareTo(other: Internal): Int {
                return when(this) {
                    is InternalExact -> {
                        when(other) {
                            is InternalExact -> this.compareByHash(other, InternalExact::signature)
                            is InternalWildcard -> -1
                        }
                    }
                    is InternalWildcard -> {
                        when(other) {
                            is InternalExact -> 1
                            is InternalWildcard -> this.compareByHash(other, InternalWildcard::signature)
                        }
                    }
                }
            }

            fun matches(signature: QualifiedMethodParameterSignature): Boolean

            override fun getArgContext(): FromVMContext = FromVMContext.InternalSummaryArgBinding
        }
    }

    sealed interface SummaryTarget : SummarySignature {

        sealed interface ExactFunction : SummaryTarget {
            override val signature: QualifiedMethodParameterSignature
        }

        sealed interface AnyContract : SummaryTarget {
            val methodName: String
        }

        override val signature: MethodParameterSignature
    }


    @KSerializable
    data class ExternalWildcard(override val sighashInt: SighashInt, override val signature: MethodParameterSignature) : SummaryTarget.AnyContract, SummarySignature.ExternalWithSignature {
        override val methodId: String
            get() = signature.functionName
        override val methodName = signature.functionName
    }


    @KSerializable
    data class  ExternalExact(override val sighashInt: SighashInt, override val signature: QualifiedMethodParameterSignature) : SummaryTarget.ExactFunction, SummarySignature.ExternalWithSignature {
        override val methodId: String
            get() = signature.qualifiedMethodName.methodId
    }


    @KSerializable
    data class ExternalAnyInContract(val hostContract: SolidityContract) : SummarySignature.External {
        override val signature: MethodParameterSignature?
            get() = null
    }


    @KSerializable
    object ExternalUnresolved : SummarySignature.External {
        private fun readResolve(): Any = ExternalUnresolved
        override val signature: MethodParameterSignature?
            get() = null

        override fun hashCode() = utils.hashObject(this)
    }

    @KSerializable
    data class InternalWildcard(override val signature: MethodParameterSignature) : SummaryTarget.AnyContract, SummarySignature.Internal {

        override val methodId: String
            get() = signature.functionName

        override val methodName: String
            get() = signature.functionName

        override fun matches(signature: QualifiedMethodParameterSignature): Boolean =
            this.signature.matchesNameAndParams(signature)
    }


    @KSerializable
    data class InternalExact(override val signature: QualifiedMethodParameterSignature) : SummaryTarget.ExactFunction, SummarySignature.Internal {

        override val methodId: String
            get() = signature.qualifiedMethodName.methodId

        override fun compareTo(other: SummarySignature.Internal): Int {
            return when(other) {
                is InternalExact -> {
                    val thisHash = this.hashCode()
                    val otherHash = other.hashCode()
                    if(thisHash < otherHash) {
                        -1
                    } else if(thisHash > otherHash) {
                        1
                    } else {
                        if(this == other) {
                            0
                        } else {
                            val str = this.signature.printQualifiedMethodParameterSignature().compareTo(other.signature.printQualifiedMethodParameterSignature())
                            if(str == 0) {
                                throw IllegalStateException("Cannot disambiguate ordering for $this and $other")
                            }
                            str
                        }
                    }
                }
                is InternalWildcard -> 1
            }
        }

        override fun matches(signature: QualifiedMethodParameterSignature): Boolean =
            this.signature.matchesContractAndParams(signature)
    }

    fun getContractNameFromContractId(solidityContractId: ContractReference = SolidityContract.Current): SolidityContract? =
        symbolTable.getContractNameFromContractId(solidityContractId)

    fun getContractInstance(contractName: ContractReference): ContractInstanceInSDC? =
        symbolTable.getContractScope(contractName)?.enclosingContract()?.contract

    fun getContractVarIdToAddressAssociation(): List<Pair<String, BigInteger>> {
        return importedContracts.mapNotNull {
            val contractScope = symbolTable.getContractScope(it.solidityContractName)
            if (contractScope == null) {
                logger.error("Could not find contract instance for ${it.solidityContractName} from imported contracts $importedContracts")
                null
            } else {
                it.solidityContractVarId to contractScope.enclosingContract()!!.contract.address
            }
        }
    }

    fun getAllInternalFunctions(): Set<MethodInContract> {
        return symbolTable.getAllContracts().flatMapToSet { it.internalFunctions.values }
    }
}


class GenerateRulesForInvariantsAndEnvFree(
    private val importedFuncs: List<ContractFunction>,
    private val cvlAst: CVLAst,
    private val deployedContracts: List<ContractInstanceInSDC>,
    private val mainContract: ContractInstanceInSDC
) {
    private val initStateAxioms: Set<CVLGhostAxiom.Initial> =
        cvlAst.ghosts
            .filterIsInstance<CVLGhostWithAxiomsAndOldCopy>()
            .flatMap { ghost -> ghost.axioms }
            .filterIsInstance<CVLGhostAxiom.Initial>()
            .toSet()

    private fun getNonStateModifyingFuncsInDeployedContracts() = deployedContracts.map{
            c -> c to c.methods.
                    filter { m -> m.stateMutability.isView }}.
                    flatMap { (c,nonViewMethods) -> nonViewMethods.map { EVMExternalMethodInfo.fromMethodAndContract(it, c)  } }


    companion object {
        fun assumeInvariant(inv: CVLInvariant, ruleScope: CVLScope, msg: String = "assume ${inv.invariantType.getShortName().lowercase()} invariant in pre-state", /*The range that should be associated with this assume for JTS information*/ range: Range = inv.range) =
            CVLCmd.Simple.AssumeCmd.Assume(range, ExpScopeRelocator(ruleScope).expr(inv.exp).safeForce(), msg, invariantPreCond = true, scope = ruleScope).wrapWithMessageLabel(msg)

        fun assertInvariant(inv: CVLInvariant, ruleScope: CVLScope, msg: String = "assert ${inv.invariantType.getShortName().lowercase()} invariant in post-state", /*The range that should be associated with this assert for JTS information*/ range: Range = inv.range) =
            CVLCmd.Simple.Assert(range, ExpScopeRelocator(ruleScope).expr(inv.exp).safeForce(), msg, ruleScope, invariantPostCond=true).wrapWithMessageLabel(msg)

        fun getInstrumentedPreservedBlock(preserved: CVLPreserved, ruleScope: CVLScope): List<CVLCmd> =
            preserved.block.map { CmdScopeRelocator(ruleScope).cmd(it).safeForce() }.wrapWithMessageLabel("Preserved block start")
    }

    /**
     * Creates a parametric rule for invariant preservation;
     * skips explicit functions for which we check preservation
     * as well as view/pure functions.
     */
    private fun preserveInvariantScenario(
        inv: CVLInvariant
    ): CollectingResult<CVLSingleRule?, CVLError> {
        // For non-explicit functions, append the generic preserved method
        val genericPreserved = inv.proof.preserved.find { preserved -> preserved is CVLPreserved.Generic }

        val explicitFunctions =
            inv.proof.preserved.filterIsInstance<CVLPreserved.ExplicitMethod>().map { preserved ->
                val funcsThatMatch = importedFuncs.filter { func ->
                    // find a function "func" such that preserved <: func
                    // should not be looking for a function "func" such that func <: preserved
                    // while matchesWithContract isn't necessarily anti-symmetrical (woops), it is not symmetrical
                    // One instance where this is important in when we want to use CVLStructTypes in a preserved
                    // signature, and match with a func whose args are equivalent EVMStructs
                    preserved.methodSignature.sighashInt == (func.methodSignature as? ExternalSignature)?.sighashInt &&
                        (func.methodSignature.qualifiedMethodName.host.name == preserved.methodSignature.qualifiedMethodName.host.name ||
                            preserved.methodSignature.qualifiedMethodName.host.name.isWildcard()) && func.isPublicMethod()
                }
                if (funcsThatMatch.isEmpty()) {
                    return CVLError.General(
                        preserved.range,
                        "Could not find an imported function with a matching signature for this preserved block: \"$preserved\"."
                    ).asError()
                }
                funcsThatMatch
            }.flatten().map { func -> func.getMethodInfo() }


        if (inv.methodParamFilters.methodParamToFilter.keys.size > 1) {
            return CVLError.General(inv.methodParamFilters.range,
                "filtered expression for invariants must take a single input variable, got ${inv.methodParamFilters.methodParamToFilter.keys} for ${inv.id}"
            ).asError()
        }

        return inv.scope.extendIn(CVLScope.Item::RuleScopeItem) { ruleScope ->
            val genericBlock = if (genericPreserved != null) {
                getInstrumentedPreservedBlock(genericPreserved, ruleScope)
            } else {
                null
            }
            withScopeAndRange(ruleScope, inv.range) {
                val methodsFilter = MethodParamFilters.dontCallFilters(
                    inv.range,
                    ruleScope,
                    setOf(inv.methodParamId),
                    dontCall = explicitFunctions + getNonStateModifyingFuncsInDeployedContracts(),
                    // note: explicit preserved blocks take precedence over method param filters, so if a filter omits a function
                    // that is explicitly preserved, it does not mean the explicit preserved is omitted.
                    // Exclude the fallback function from the generic preserved rule iff the fallback has a preserved block
                    dontCallFallback = inv.proof.preserved.any {  it is CVLPreserved.Fallback }
                ).let { dontCallMethodsFilter ->
                    // take the conjunct with the usual don't call filter
                    MethodParamFilters.conjunct(
                        inv.methodParamFilters.range, ruleScope, dontCallMethodsFilter, inv.methodParamFilters
                    )
                }

                //Adding the parameters of a generic preserved block.
                val instrumentedEnv = preservedInstrumentedEnv(genericPreserved, ruleScope)
                val block = generateInvariantBlockForGenericOrFallback(
                    inv, listOf(
                        instrumentedEnv,
                        CVLExp.VariableExp(
                            "invariantCalldata",
                            tag = CVLType.PureCVLType.VMInternal.RawArgs.asTag()
                        )
                    ), isFallback = false, ruleScope = ruleScope
                )

                val newParams: List<CVLParam> = inv.params + listOf(
                    CVLParam(EVMBuiltinTypes.method, inv.methodParamId, Range.Empty()),
                    CVLParam(CVLType.PureCVLType.VMInternal.RawArgs, "invariantCalldata", Range.Empty()),
                    CVLParam(EVMBuiltinTypes.env, instrumentedEnv.id, Range.Empty())
                )
                val declId =  "Using general requirements"
                CVLSingleRule(
                    inv.uniqueRuleIdentifier.freshDerivedIdentifier(declId),
                    inv.range,
                    newParams,
                    "Invariant breached",
                    "Invariant preserved",
                    if (genericBlock != null) {
                        genericBlock + block
                    } else {
                        block
                    },
                    SpecType.Single.InvariantCheck.GenericPreservedInductionStep(inv),
                    ruleScope,
                    methodsFilter,
                    SingleRuleGenerationMeta.Empty
                )
            }
        }.lift()
    }

    private fun explicitPreservedInvariantScenario(inv: CVLInvariant): CollectingResult<List<CVLSingleRule>, CVLError> {
        val explicitPreservedBlocks = inv.proof.preserved.filterIsInstance<CVLPreserved.ExplicitMethod>()

        // Use two maps to keep track of the non-wildcard and wildcard preserved blocks separately.  We will use the
        // maps for the case when we have a wildcard preserved block and a non-wildcard preserved block with the same
        // method signature.
        val nonWildcardPreservedMap : MutableMap<ScopedMethodSignature, CVLPreserved.ExplicitMethod> = mutableMapOf()
        val wildcardPreservedMap : MutableMap<ScopedMethodSignature, CVLPreserved.ExplicitMethod> = mutableMapOf()

        explicitPreservedBlocks.groupBy {
            ScopedMethodSignature(it.methodSignature.sighashInt, it.methodSignature.qualifiedMethodName.host.name)
        }.mapNotNull {
            it.value.singleOrNull()
        }.filter { preserved ->
            // If the user provided a method name on the command line, we can skip generating rules for all other methods.
            importedFuncs.filterNot { it.annotation.library }.mapNotNull { it.evmExternalMethodInfo?.toExternalABINameWithContract() }
                .containsMethodFilteredByConfig(preserved.methodSignature.computeCanonicalSignatureWithContract(PrintingContext(false)), mainContract.name)
        }.forEach { preserved ->
            // Now we check to see if the user has provided duplicate wildcard blocks or duplicate explicitly named non-
            // wildcard blocks. We will generate a DuplicatePreserved error in these cases. However, we do not generate
            // an error if we have an explicitly named block and a wildcard block with the same method signature.  In
            // that case later we will prioritize the more specific block over the wildcard block.
            if (preserved.methodSignature.qualifiedMethodName.host.name.isWildcard()) {
                // Now look for all functions that have a matching signature and create a copy of the preserved block
                // but use the function signature from the function in place of the signature from the preserved block.
                importedFuncs.filter { func ->
                    preserved.methodSignature.sighashInt == (func.methodSignature as? ExternalSignature)?.sighashInt
                }.filter { func ->
                    // Filter out functions from extension contracts - they're called via the base contract anyway.
                    val contract = func.methodSignature.qualifiedMethodName.host.name.let { contractName ->
                        deployedContracts.find { it.name == contractName } ?: error("contract $contractName should exist")
                    }
                    !deployedContracts.isExtensionContract(contract)
                }.forEach { func ->
                    val newMethodSignature = (func.methodSignature as ExternalQualifiedMethodSignature).withParameters(
                        preserved.methodSignature.params
                    )
                    val newPreserved = preserved.copy(methodSignature = newMethodSignature)

                    // We store the wildcard preserved blocks after we generate the copies from the wildcard block.
                    // We also check for a duplicate here as well.
                    val scopedSignature = ScopedMethodSignature(
                        newMethodSignature.sighashInt,
                        newMethodSignature.qualifiedMethodName.host.name
                    )
                    if (wildcardPreservedMap.containsKey(scopedSignature)) {
                        return DuplicatePreserved(
                            inv,
                            listOf(wildcardPreservedMap[scopedSignature]!!, newPreserved),
                            DuplicatePreserved.Specific(newPreserved)
                        ).asError()
                    }
                    wildcardPreservedMap[scopedSignature] = newPreserved
                }
            } else {
                val contractName = preserved.methodSignature.qualifiedMethodName.host.name
                // Check that we don't have a preserved block on an extension contract's method.
                deployedContracts.find { it.name == contractName }?.let { contract ->
                    if (deployedContracts.isExtensionContract(contract)) {
                        return ExplicitPreservedOnExtensionContract(preserved, deployedContracts.getContractsExtendedBy(contract).map { it.name }).asError()
                    }
                }

                // Check that we do not have a duplicate preserved block with the same method signature.
                val scopedSignature = ScopedMethodSignature(
                    preserved.methodSignature.sighashInt,
                    contractName
                )
                if (nonWildcardPreservedMap.containsKey(scopedSignature)) {
                    return DuplicatePreserved(
                        inv,
                        listOf(nonWildcardPreservedMap[scopedSignature]!!, preserved),
                        DuplicatePreserved.Specific(preserved)
                    ).asError()
                }
                nonWildcardPreservedMap[scopedSignature] = preserved
            }
        }

        val unambiguousExplicitPreserved : MutableList<CVLPreserved.ExplicitMethod> = mutableListOf()
        // This loop preserves the request that more specific preserved blocks take precedence over wildcard preserved
        // blocks.
        wildcardPreservedMap.forEachEntry {
            if (!nonWildcardPreservedMap.containsKey(it.key)) {
                unambiguousExplicitPreserved.add(it.value)
            }
        }
        nonWildcardPreservedMap.forEachEntry {
            unambiguousExplicitPreserved.add(it.value)
        }

        return collectingErrors {
            unambiguousExplicitPreserved.map { preserved ->
                inv.scope.extendIn(CVLScope.Item::RuleScopeItem) { ruleScope ->
                    val namedSig = preserved.methodSignature

                    val invoke: CVLCmd.Simple.Apply
                    val params: List<CVLParam>

                    val lastStorage = CVLExp.VariableExp(
                        CVLKeywords.lastStorage.keyword, CVLExpTag(
                            range = inv.range,
                            scope = ruleScope,
                            type = CVLType.PureCVLType.VMInternal.BlockchainState
                        )
                    )

                    val cvlParams = preserved.params
                    val args = cvlParams.map {
                        CVLExp.VariableExp(
                            it.id, tag = CVLExpTag(
                                scope = ruleScope, type = it.type, range = inv.range
                            )
                        )
                    }
                    val env = preserved.withParams.singleOrNull {
                        it.type isSubtypeOf EVMBuiltinTypes.env
                    }?.let(::listOf) ?: listOf()
                    invoke = CVLCmd.Simple.Apply(range = preserved.range,
                        exp = CVLExp.ApplyExp.ContractFunction.Concrete(
                            args = env.map {
                                CVLExp.VariableExp(
                                    it.id, CVLExpTag(range = preserved.range, scope = ruleScope, type = it.type)
                                )
                            } + args,
                            tag = CVLExpTag(
                                type = CVLType.PureCVLType.Void,
                                range = preserved.range,
                                scope = ruleScope
                            ),
                            noRevert = true,
                            isWhole = false,
                            methodIdWithCallContext = ConcreteMethod(signature = namedSig),
                            storage = lastStorage
                        ),
                        scope = ruleScope)
                    params = cvlParams + env

                    /*
                     * 1. assume the invariant
                     * 2. the preserved block
                     * 3. invoke the function
                     * 4. assert the invariant
                     */
                    val block = mutableListOf<CVLCmd>()
                    block.addAll(assumeInvariant(inv, ruleScope))
                    block.addAll(getInstrumentedPreservedBlock(preserved, ruleScope))
                    block.add(invoke)
                    block.addAll(assertInvariant(inv, ruleScope))

                    val newParams: List<CVLParam> = inv.params + params

                    // If all funcs are from the same contract, don't bother with the contract name prefix.
                    val ruleName = if (importedFuncs.map { it.methodSignature.qualifiedMethodName.host.name }.toSet().size == 1) {
                        preserved.methodSignature.prettyPrint()
                    } else {
                        preserved.methodSignature.prettyPrintFullyQualifiedName()
                    }

                    CVLSingleRule(
                        inv.uniqueRuleIdentifier.freshDerivedIdentifier(ruleName),
                        inv.range,
                        newParams, //TODO: unique name per function or merge to single rule
                        "Invariant breached when calling ${preserved.methodSignature}",
                        "Invariant preserved",
                        block,
                        SpecType.Single.InvariantCheck.ExplicitPreservedInductionStep(inv, preserved.methodSignature),
                        ruleScope,
                        MethodParamFilters.noFilters(inv.range, ruleScope),
                        SingleRuleGenerationMeta.Empty,
                    )
                }
            } + listOfNotNull(preserveFallbackScenario(inv))
        }
    }

    /**
     * Creates an invariant preserve rule for the fallback() contract function,
     * if a preserved block for that function exists in the invariant.
     * @param inv: The CVL invariant block.
     * @return - A [CVLSingleRule] instance, or null if no fallback preserved block exists.
     */
    private fun preserveFallbackScenario(inv: CVLInvariant): CVLSingleRule? {
        val fallbackPreserved: CVLPreserved.Fallback =
            inv.proof.preserved.filterIsInstance<CVLPreserved.Fallback>().let {
                when (it.size) {
                    0 -> {  // No preserved block: the fallback function will be invoked in the generic preserved rules
                        return null
                    }
                    1 -> {
                        it.first()
                    }
                    else -> {
                        // This should be caught by the CVL type checker and reported as a syntax error
                        return null
                    }
                }
            }
        return inv.scope.extendIn(CVLScope.Item::RuleScopeItem) { ruleScope ->
            val fallbackPreservedBlock: List<CVLCmd> = getInstrumentedPreservedBlock(fallbackPreserved, ruleScope)

            val instrumentedEnv = preservedInstrumentedEnv(fallbackPreserved, ruleScope)
            val block = generateInvariantBlockForGenericOrFallback(
                inv, listOf(
                    instrumentedEnv,
                    CVLExp.VariableExp(
                        "invariantCalldata",
                        tag = CVLExpTag(
                            scope = ruleScope,
                            range = inv.range,
                            type = CVLType.PureCVLType.VMInternal.RawArgs
                        )
                    )
                ), isFallback = true, ruleScope = ruleScope
            )

            val newParams: List<CVLParam> = inv.params + listOf(
                CVLParam(CVLType.PureCVLType.VMInternal.RawArgs, "invariantCalldata", Range.Empty()),
                CVLParam(EVMBuiltinTypes.env, instrumentedEnv.id, Range.Empty())
            )

            val declId = CVLReservedVariables.certoraFallbackDisplayName

            CVLSingleRule(
                inv.uniqueRuleIdentifier.freshDerivedIdentifier(declId),
                inv.range,
                newParams,
                "Invariant breached when calling the fallback function",
                "Invariant preserved",
                fallbackPreservedBlock + block,
                SpecType.Single.InvariantCheck.ExplicitPreservedInductionStep(inv, null),
                ruleScope,
                MethodParamFilters.noFilters(inv.range, ruleScope),
                SingleRuleGenerationMeta.Empty
            )
        }
    }


    private fun initstateInvariantScenario(inv: CVLInvariant): CVLSingleRule? {
        if (Config.methodsAreFiltered) {
            // The user specified specific methods to test, so skip generating the initstate rule
            return null
        }

        return inv.scope.extendIn(CVLScope.Item::RuleScopeItem) { scope ->
            withScopeAndRange(scope, range = inv.range) {
                val initParams = listOf(
                    CVLExp.VariableExp("initEnv", tag = EVMBuiltinTypes.env.asTag()),
                    CVLExp.VariableExp("initCalldata", tag = CVLType.PureCVLType.VMInternal.RawArgs.asTag())
                )

                val assumes = initStateAxioms.map { axiom ->
                    CVLCmd.Simple.AssumeCmd.Assume(axiom.exp.getRangeOrEmpty(), axiom.exp, "init state axiom", scope)
                }
                val block =
                    // technically should be only the current contract? or let the user choose?
                    CVLCmd.Simple.ResetStorage(
                        inv.range,
                        CVLExp.VariableExp(CVLKeywords.allContracts.name, tag = CVLType.PureCVLType.Primitive.AccountIdentifier.asTag()),
                        scope
                    ).wrapWithMessageLabel("Reset storages to 0") +
                    assumes.wrapWithMessageLabel("Init state axioms") +
                    CVLCmd.Simple.contractFunction(
                        inv.range,
                        scope,
                        UniqueMethod(
                           SolidityContract(mainContract.name),
                           attribute = MethodAttribute.Unique.Constructor
                        ),
                        initParams, // TODO(jtoman): this is pointless, the constructor doesn't read from calldata...
                        true,
                        CVLExp.VariableExp(CVLKeywords.lastStorage.keyword, tag = CVLKeywords.lastStorage.type.asTag()),
                        isParametric = false,
                        methodParamFilter = null
                    ) +
                    assertInvariant(inv, scope)
                val newParams: List<CVLParam> = inv.params + listOf(
                    CVLParam(CVLType.PureCVLType.VMInternal.RawArgs, "initCalldata", Range.Empty()),
                    CVLParam(EVMBuiltinTypes.env, "initEnv", Range.Empty())
                )
                val declId = "Induction base: After the constructor"
                CVLSingleRule(
                    inv.uniqueRuleIdentifier.freshDerivedIdentifier(declId),
                    this@GenerateRulesForInvariantsAndEnvFree.mainContract.allMethods.find { it.name == CONSTRUCTOR }?.sourceSegment()?.range ?: inv.range,
                    newParams,
                    "Initial state does not instate invariant",
                    "Initial state instates invariant",
                    block,
                    SpecType.Single.InvariantCheck.InductionBase(inv),
                    scope,
                    MethodParamFilters.noFilters(inv.range, scope),
                    SingleRuleGenerationMeta.Empty
                )
            }
        }
    }


    private fun resetTransientStorageRule(inv: CVLInvariant): CVLSingleRule? {
        if (Config.methodsAreFiltered) {
            // The user specified specific methods to test, so skip generating the preserved rule
            return null
        }
        return inv.scope.extendIn(CVLScope.Item::RuleScopeItem) { scope ->
            withScopeAndRange(scope, range = inv.range) {
                val block = assumeInvariant(inv,scope) +
                    listOf(CVLCmd.Simple.ResetTransientStorage(
                        inv.range,
                        // xxx think of which variable need to be reset and when.
                        CVLExp.VariableExp(CVLKeywords.allContracts.name, tag = CVLType.PureCVLType.Primitive.AccountIdentifier.asTag()),
                        scope
                    )) +
                    assertInvariant(inv, scope)

                val preservedOnTransactionBoundary = inv.proof.preserved.find { preserved -> preserved is CVLPreserved.TransactionBoundary }
                inv.scope.extendIn(CVLScope.Item::RuleScopeItem) { ruleScope ->
                    val preservedBlock = if (preservedOnTransactionBoundary != null) {
                        getInstrumentedPreservedBlock(preservedOnTransactionBoundary, ruleScope)
                    } else {
                        null
                    }
                    withScopeAndRange(ruleScope, inv.range) {

                        //Adding the parameters of a generic preserved block.
                        val instrumentedEnv = preservedInstrumentedEnv(preservedOnTransactionBoundary, ruleScope)

                        val newParams: List<CVLParam> = inv.params + listOf(
                            CVLParam(EVMBuiltinTypes.env, instrumentedEnv.id, Range.Empty())
                        )
                        val declId = "Induction step: Reset transient storage"
                        CVLSingleRule(
                            inv.uniqueRuleIdentifier.freshDerivedIdentifier(declId),
                            inv.range,
                            newParams,
                            "Invariant does not hold when transient storage is reset.",
                            "Verification of the following steps failed: Assuming the invariant, resetting transient storage and asserting the invariant.",
                            if (preservedBlock != null) {
                                preservedBlock + block
                            } else {
                                block
                            },
                            SpecType.Single.InvariantCheck.TransientStorageStep(inv),
                            ruleScope,
                            MethodParamFilters.noFilters(inv.range, ruleScope),
                            SingleRuleGenerationMeta.Empty
                        )
                    }
                }
            }
        }
    }

    /**
     * Returns the parameters of a generic or fallback preserved block.
     * I.e. for
     *     preserved with(env e) {
     *          require e.msg.sender != currentContract;
     *     }
     *     we return the parameter e to the invariant rule.
     * @param preservedBlock - The CVL preserved block object
     * @return - the 'env' parameter
     */
    private fun preservedInstrumentedEnv(preservedBlock: CVLPreserved?, scope: CVLScope): CVLExp.VariableExp {
        val envTag = CVLExpTag(scope = scope,
            range = preservedBlock?.range ?: Range.Empty(),
            type = EVMBuiltinTypes.env)
        val defaultEnv: CVLExp.VariableExp = CVLExp.VariableExp("invariantEnv", tag = envTag)
        return if (preservedBlock != null) {
            val preservedWithParams = preservedBlock.withParams

            // The typechecker will verify there aren't multiple params in the with clause, and that the type of the param is indeed `env`
            preservedWithParams.singleOrNull()?.let { CVLExp.VariableExp(it.id, envTag) } ?: defaultEnv
        } else {
            defaultEnv
        }
    }

    /**
     * Creates the block for invariant exclusive of the preserved block for
     * the generic preserved or for fallback preserved block.
     * @param inv - the invariant
     * @param instrumentedParams - a list the preserved block  parameters.
     * @param isFallback - indication if that's a fallback or a generic preserved block
     * @returns - the invariant block: assume->function-call->assert as a list of CVL commands.
     */
    private fun generateInvariantBlockForGenericOrFallback(
        inv: CVLInvariant,
        instrumentedParams: List<CVLExp.VariableExp>,
        isFallback: Boolean,
        ruleScope: CVLScope
    ): List<CVLCmd> =
        assumeInvariant(inv, ruleScope) +
        CVLCmd.Simple.contractFunction(
            inv.range,
            ruleScope,
            if (isFallback) {
                UniqueMethod(
                    SolidityContract(mainContract.name),
                    attribute = MethodAttribute.Unique.Fallback
                )
            } else {
                // Use the "wildcard contract" to indicate this should be parametric over all functions in the scene
                ParametricMethod(inv.methodParamId, AllContracts)
            },
            instrumentedParams,
            true,
            CVLExp.VariableExp(CVLKeywords.lastStorage.keyword, tag = CVLExpTag(scope = ruleScope, type = CVLKeywords.lastStorage.type, range = inv.range)),
            isParametric = !isFallback,
            methodParamFilter = null
        ).wrapWithMessageLabel("check effects of step taken by one of the functions") +
        assertInvariant(inv, ruleScope)


    private fun rulesOfInvariant(inv: CVLInvariant): CollectingResult<GroupRule, CVLError> {
        return preserveInvariantScenario(inv).map(explicitPreservedInvariantScenario(inv)) { invPreserve, explicitPreservedRules ->
            val invariantIdentifier = RuleIdentifier.freshIdentifier(inv.id)
            CVLScope.AstScope.extendIn(CVLScope.Item::RuleScopeItem) { invScope ->
                GroupRule(
                    invariantIdentifier, inv.range,
                    listOfNotNull(initstateInvariantScenario(inv), resetTransientStorageRule(inv)) +
                        invScope.extendIn(CVLScope.Item::RuleScopeItem) { inductionStepScope ->
                            val inductionStepDisplayName = when (inv.invariantType) {
                                StrongInvariantType -> "Induction step (strong invariant): after external (non-view) methods and before unresolved calls"
                                WeakInvariantType -> "Induction step: after external (non-view) methods"
                            }
                            val inductionStepId = invariantIdentifier.freshDerivedIdentifier(inductionStepDisplayName)
                            GroupRule(
                                inductionStepId,
                                inv.range,
                                listOfNotNull(
                                    invPreserve,
                                    if (explicitPreservedRules.isNotEmpty()) {
                                        invScope.extendIn(CVLScope.Item::RuleScopeItem) { explicitPreservedScope ->
                                            val specificDeclId = "Using specific requirements"
                                            val explicitPreservedIdentifier = inductionStepId.freshDerivedIdentifier(specificDeclId)
                                            GroupRule(
                                                explicitPreservedIdentifier,
                                                inv.range,
                                                explicitPreservedRules,
                                                SpecType.Group.InvariantCheck.CustomInductionSteps(inv),
                                                explicitPreservedScope,
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                ),
                                SpecType.Group.InvariantCheck.InductionSteps(inv),
                                inductionStepScope,
                            )
                        },
                    SpecType.Group.InvariantCheck.Root(inv),
                    invScope
                )
            }
        }
    }

    private fun ruleEnvfreeFuncsStatic(): ICVLRule? {
        val envfreeFuncsToCheck = importedFuncs.filter { func ->
            func.annotation.envFree &&
                // A func marked as virtual that has no evmExternalMethodInfo has no known implementation so
                // its envfree check is skipped
                !(func.annotation.virtual && func.evmExternalMethodInfo == null)
        }
        if (envfreeFuncsToCheck.isEmpty()) {
            return null
        }
        val groupId = "envfreeFuncsStaticCheck"
        val envGroupIdentifier = RuleIdentifier.freshIdentifier(groupId)
        return CVLScope.AstScope.extendIn(CVLScope.Item::RuleScopeItem) { groupRuleScope ->
            GroupRule(
                envGroupIdentifier,
                Range.Empty(),
                envfreeFuncsToCheck.map { envfreeFunc ->
                    val ruleId = if (envfreeFunc.methodSignature.qualifiedMethodName.host.name == mainContract.name) {
                        ""
                    } else {
                        "${envfreeFunc.methodSignature.qualifiedMethodName.host.name}."
                    } + envfreeFunc.getMethodInfo().getPrettyName()
                    groupRuleScope.extendIn(CVLScope.Item::RuleScopeItem) { staticRuleScope ->
                        StaticRule(
                            envGroupIdentifier.freshDerivedIdentifier(ruleId),
                            SpecType.Single.EnvFree.Static(envfreeFunc),
                            scope = staticRuleScope,
                            (cvlAst.importedMethods.find {
                                it is ConcreteMethodBlockAnnotation &&
                                    it.name == envfreeFunc.methodSignature.functionName
                            } as ConcreteMethodBlockAnnotation).range,
                        )
                    }
                },
                SpecType.Group.StaticEnvFree,
                groupRuleScope
            )
        }
    }

    fun generatedRules(): CollectingResult<List<ICVLRule>, CVLError> = collectingErrors {
        val invRules = if (Config.BoundedModelChecking.getOrNull() == null) {
            collectAndFilter(cvlAst.invs.filter { it.needsVerification }.map { rulesOfInvariant(it) })
        } else {
            listOf()
        }
        invRules + listOfNotNull(ruleEnvfreeFuncsStatic())
    }
}
