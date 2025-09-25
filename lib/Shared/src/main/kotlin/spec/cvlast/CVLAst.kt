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

@file:UseSerializers(BigIntegerSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)
// CER-1384: Supressing warnings at file level to avoid churn during rewrite
@file:Suppress("NAME_SHADOWING", "unreachable_code")

package spec.cvlast

import allocator.Allocator
import bridge.ContractInstanceInSDC
import bridge.EVMExternalMethodInfo
import bridge.EVMExternalMethodInfo.Companion.viewField
import com.certora.collect.*
import compiler.SolidityFunctionStateMutability
import config.Config
import datastructures.stdcollections.*
import evm.SighashInt
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonClassDiscriminator
import report.calltrace.CVLReportLabel
import scene.IContractClass
import spec.CVLKeywords
import spec.CastType
import spec.cvlast.CVLExp.HavocTarget
import spec.cvlast.OverrideDeclaration.CVLDefinition
import spec.cvlast.OverrideDeclaration.CVLFunction
import spec.cvlast.TwoStateIndex.*
import spec.cvlast.Visibility.EXTERNAL
import spec.cvlast.Visibility.INTERNAL
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.*
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.ToVMContext
import spec.cvlast.typedescriptors.VMDynamicArrayTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import spec.genericrulegenerators.BuiltInRuleId
import spec.isWildcard
import spec.rules.ICVLRule
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.flattenToVoid
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.safeForce
import utils.ErrorCollector.Companion.collectingErrors
import java.math.BigInteger

sealed class NoSuchTypeException(msg: String, val contractName: String?, val id: String) : RuntimeException(msg)

@Suppress("unused")
class UnresolvedVMTypeException(msg: String, contractName: String?, id: String) :
    NoSuchTypeException(msg, contractName, id)

@Suppress("unused")
class InvalidCVLTypeException(msg: String, contractName: String?, id: String) :
    NoSuchTypeException(msg, contractName, id)

@Suppress("unused")
class InvalidParameterType(msg: String) : RuntimeException(msg)

@Serializable
data class CVLAst(
    val importedMethods: List<MethodBlockEntry>,
    val useDeclarations: UseDeclarations,
    override val rules: List<ICVLRule>,
    override val subs: List<spec.cvlast.CVLFunction>,
    override val invs: List<CVLInvariant>,
    val sorts: List<SortDeclaration>,
    override val ghosts: List<CVLGhostDeclaration>,
    override val definitions: List<spec.cvlast.CVLDefinition>,
    override val hooks: List<CVLHook>,
    val importedContracts: List<CVLImportedContract>,
    val importedSpecFiles: List<CVLImportedSpecFile>,
    val overrideDeclarations: OverrideDeclarations,
    /* The scope is guaranteed to be initialized after [CVLScope.addScopes] */
    val scope: CVLScope
) : IAstCodeBlocks

interface CreatesScope {
    val scope: CVLScope
    val scopeId: Int get() = scope.currentScope()
}

/**
 * Superclass for all kinds of "functions" in the sense of "something that can be called" from the spec
 *  - solidity methods
 *  - ghost functions
 *  - CVL definitions
 *  - CVL Functions
 */
@Serializable
@Treapable
sealed interface Function<T, Ret>: AmbiSerializable {
    val functionIdentifier: CallableName
    val paramTypes: List<T> /* Tuple types are not flattened */
    val rets: Ret /* Tuple types are not flattened */

    /** A useful description of the type of the function for error messages (e.g. "contract function" or "CVL function") */
    val typeDescription : String
}


@Serializable
sealed class CallResolution : ExpressionAnnotation {
    @Serializable
    abstract val target: ContractFunction
    @Serializable
    abstract val hasEnv: Boolean

    @Serializable
    data class DirectPassing(override val target: ContractFunction, override val hasEnv: Boolean) : CallResolution()
    @Serializable
    data class CalldataPassing(override val target: ContractFunction, override val hasEnv: Boolean) : CallResolution()
}

/**
 * Any callable entity which is part of a spec rather than a contract.
 */
@Serializable
sealed interface SpecFunction : Function<CVLType.PureCVLType, CVLType.PureCVLType>, ExpressionAnnotation

// TODO CERT-2966: this needs to be refactored to restore the distinction between AnnotationQualifier and
// ConcreteMethodQualifier, or more aggressively merge these classes, and remove the very strange calling convention for
// getMethodQualifiers.

/**
 * Annotations in the methods block. Potential candidate for per-vm ast nodes.
 */
@Serializable
@Treapable
data class MethodQualifiers(
    val visibility: Visibility,
    val envFree: Boolean,
    val library: Boolean,
    val virtual: Boolean
) : AmbiSerializable {
    override fun hashCode() = hash { it + envFree + visibility + library + virtual }
}

/**
 * A target contract for use in the methods block.
 */
@Serializable
@Treapable
sealed interface MethodEntryTargetContract : AmbiSerializable {
    fun prettyPrint() : String

    /**
     * Targets a specific contract in the scene.
     */
    @Serializable
    data class SpecificTarget(val contract : SolidityContract) : MethodEntryTargetContract {
        override fun prettyPrint(): String = contract.name
    }

    /**
     * Targets any relevant contract. Does not care what the contract is.
     */
    @Serializable
    object WildcardTarget : MethodEntryTargetContract {
        override fun prettyPrint(): String = "_"
        private fun readResolve() : Any = WildcardTarget
        override fun hashCode(): Int = hashObject(this)
    }
}

/** An unprocessed entry in a `methods` block. */
sealed interface MethodBlockEntry : AmbiSerializable {
    val range: Range
    val target: MethodEntryTargetContract
    val summary: SpecCallSummary.ExpressibleInCVL?

    /**
     * @return a [List] of [ContractFunction]s that this [MethodBlockEntry] "covers"
     */
    fun getMatchingContractFunctions(functionInfo: Map<ContractInstanceInSDC, List<ContractFunction>>): List<ContractFunction>
    fun prettyPrint(): String
}

@Serializable
@Treapable
data class CatchAllSummaryAnnotation(
    override val range: Range,
    override val target: MethodEntryTargetContract.SpecificTarget,
    override val summary: SpecCallSummary.ExpressibleInCVL,
    val annot: MethodQualifiers
) : AmbiSerializable, MethodBlockEntry {
    override fun getMatchingContractFunctions(functionInfo: Map<ContractInstanceInSDC, List<ContractFunction>>): List<ContractFunction> {
        // For catchAll summaries we just need to find the correct [ContractInSDC] and collect all its [ContractFunction]s
        return functionInfo.findEntry { instance, _ -> instance.name == this.target.contract.name}?.second
            ?: error("expected to find contract ${this.target.contract.name} in the scene")
    }
    override fun prettyPrint(): String = target.prettyPrint() + "._"

}

@Serializable
@Treapable
data class ConcreteMethodBlockAnnotation(
    override val range: Range,
    val methodParameterSignature: MethodParameterSignature,
    override val target: MethodEntryTargetContract,
    val qualifiers: MethodQualifiers,
    override val summary: SpecCallSummary.ExpressibleInCVL?,
): AmbiSerializable, MethodBlockEntry {
    init {
        // "A wildcard target has no receiver, a specific target does"
        // read: implication
        check(
            when (target) {
                is MethodEntryTargetContract.SpecificTarget -> methodParameterSignature is QualifiedMethodParameterSignature && methodParameterSignature.matchesContractNameAndMethodName(
                    QualifiedFunction(
                        host = target.contract,
                        methodId = methodParameterSignature.functionName
                    )
                )

                MethodEntryTargetContract.WildcardTarget -> methodParameterSignature !is QualifiedMethodParameterSignature
            }
        ) { "internal invariant broken: $this"}
    }

    override fun prettyPrint(): String {
        val m = target.prettyPrint() + "." + methodParameterSignature.functionName
        return m + methodParameterSignature.paramTypes.joinToString(separator = ",", prefix = "(", postfix = ")") {
            it.prettyPrint()
        }
    }

    val name: String get() = methodParameterSignature.functionName

    override fun getMatchingContractFunctions(functionInfo: Map<ContractInstanceInSDC, List<ContractFunction>>): List<ContractFunction> {
        return when (val target = this.target) {
            is MethodEntryTargetContract.SpecificTarget -> {
                val contract = target.contract

                // find all functions of the given contract
                val allContractFunctions = functionInfo.findEntry { instance, _ ->
                    instance.name == contract.name
                }?.second
                    ?: error("expected to find contract $contract in the scene")

                // find the specific function
                val contractFunction = allContractFunctions.find { it.methodSignature.matchesNameAndParams(this.methodParameterSignature) }
                    ?: error("Expected to find ${this.methodParameterSignature} in contract $contract")

                listOf(contractFunction)
            }
            MethodEntryTargetContract.WildcardTarget -> {
                // For wildcard entries we collect the relevant functions from all contracts
                functionInfo.mapNotNull { (_, functions) ->
                    functions.find { it.methodSignature.matchesNameAndParams(this.methodParameterSignature) }
                }
            }
        }
    }
}

@Serializable
@Treapable
data class CatchUnresolvedSummaryAnnotation(
    override val range: Range,
    override val target: MethodEntryTargetContract,
    val sig: MethodParameterSignature?,
    override val summary: SpecCallSummary.ExpressibleInCVL,
) : AmbiSerializable, MethodBlockEntry {

    override fun getMatchingContractFunctions(functionInfo: Map<ContractInstanceInSDC, List<ContractFunction>>): List<ContractFunction> {
        // This only applies to unresolved functions, so no known contract function is being summarized.
        return listOf()
    }

    override fun prettyPrint(): String {
        val m = sig?.let { methodParameterSignature ->
            methodParameterSignature.functionName +
                methodParameterSignature.paramTypes.joinToString(separator = ",", prefix = "(", postfix = ")") {
                    it.prettyPrint()
                }
        } ?: "_"
        return "unresolved external in " + target.prettyPrint() + "." + m
    }
}

/**
 * Describes the type of call being targeted in the methods block.
 *
 * [EXTERNAL] may target external calls between contracts for summarization, and also tells the tool that the function
 * is callable from the spec.
 *
 * [INTERNAL] targets internal calls within a contract for summarization.
 *
 * Note, this isn't *really* declaring what we expect the visibility to be (with the exception of [EXTERNAL] declaring
 * that the function is callable from the spec). What it's declaring is what type of function call this entry is
 * targeting. An external entry targets inter-contract external calls, where arguments are encoded in a calldata buffer
 * etc., an internal entry targets a function call which is effectively just a jump to some internal pointer.
 */
enum class Visibility {
    EXTERNAL,
    INTERNAL
    ;

    override fun toString() = this.name.lowercase()
}

/** Represents a contract method as it appears in the contract metadata, plus
 * the information that occurs in the `methods` block of a CVL file.
 */
@Serializable
data class ContractFunction(
    val methodSignature: QualifiedMethodSignature, //TODO return type???
    val definitelyNonPayable: Boolean,
    val annotation: MethodQualifiers,
    val stateMutability: SolidityFunctionStateMutability,
    val evmExternalMethodInfo: EVMExternalMethodInfo?,
) : Function<VMTypeDescriptor, List<VMTypeDescriptor>> {
    init {
        // evmExternalMethodInfo must be null in case of internal functions, and could be null for external functions
        // only if it is virtual, optional, or one of the unique methods (fallback/constructor).
        check(evmExternalMethodInfo?.let { annotation.visibility == EXTERNAL }
            ?: (annotation.visibility == INTERNAL || methodSignature is UniqueMethod || annotation.virtual)) {
            "evmExternalMethodInfo is $evmExternalMethodInfo while visibility is ${annotation.visibility}"
        }
    }

    override fun hashCode() = hash {
        it + methodSignature + definitelyNonPayable + annotation + stateMutability + evmExternalMethodInfo
    }
    override val functionIdentifier: ContractFunctionIdentifier
        get() = methodSignature.qualifiedMethodName
    override val paramTypes: List<VMTypeDescriptor>
        get() = methodSignature.paramTypes
    override val rets: List<VMTypeDescriptor>
        get() = (methodSignature as? MethodSignature)?.resType ?: listOf()
    val sigHash: BigInteger?
        get() = (methodSignature as ExternalQualifiedMethodSignature).sighashInt?.n

    fun getSighash(): BigInteger? = sigHash

    fun getMethodInfo(): EVMExternalMethodInfo = evmExternalMethodInfo ?: throw IllegalStateException("Function $this has no method info")

    fun isPublicMethod(): Boolean = evmExternalMethodInfo != null

    fun argsMatch(args: List<CVLExp>): VoidResult<String> {
        require(args.all { it.hasCVLType() }) {
            "arguments can only be matched after they are typed"
        }

        val noEnvArgs = if (args.isNotEmpty() && args[0].getCVLType() == EVMBuiltinTypes.env) {
            args.subList(1, args.size)
        }  else {
            args
        }

        if (noEnvArgs.size != methodSignature.params.size) {
            return "args mismatch - different arity".asError()
        }

        return noEnvArgs.zip(methodSignature.params).map { (arg, param) ->
            arg.getCVLType().convertibleToVMType(
                param.vmType,
                ToVMContext.ArgumentPassing
            )
        }.flattenToVoid()
    }

    override fun toString(): String = methodSignature.toString()
    override val typeDescription: String = "contract function"
}

/**
 * Declares code to instrument into contract code whenever a specific [pattern] is found in a rule.
 */
@Serializable
@Treapable
data class CVLHook(
    val pattern: CVLHookPattern,
    val block: List<CVLCmd>,
    val range: Range,
    override val scope: CVLScope,
) : CreatesScope, AmbiSerializable

interface PatternWithValue {
    val value: VMParam.Named
}


sealed interface GeneratedOpcodeHook

sealed interface OpcodeHookWithEnv {
    fun environmentParams() : List<VMParam.Named>
}

/**
 * Describes to the tool what code pattern to look for when instrumenting hooks.
 */
@Serializable
@Treapable
sealed class CVLHookPattern : AmbiSerializable {

    protected abstract fun fieldsMatch(other: CVLHookPattern): Boolean
    infix fun matches(other: CVLHookPattern): Boolean = other::class == this::class && fieldsMatch(other)

    /** The set of parameters defined by this hook pattern */
    abstract val definedVariables : List<VMParam.Named>

    /**
     * A pattern which matches specific reads from or writes to storage.
     *
     * @property slot describes which base contract to match as well as what pattern of storage slot to match
     */
    @Serializable
    sealed class StoragePattern : CVLHookPattern(), PatternWithValue {
        abstract override val value: VMParam.Named

        enum class Base {
            STORAGE,
            TRANSIENT_STORAGE
        }

        abstract val slot: CVLSlotPattern
        abstract val base: Base

        override fun fieldsMatch(other: CVLHookPattern): Boolean =
            base == (other as StoragePattern).base && slot == other.slot

        /**
         * Matches any load to [slot].
         */
        @Serializable
        data class Load(
            override val value: VMParam.Named,
            override val slot: CVLSlotPattern,
            override val base: Base
        ) : StoragePattern() {
            override fun hashCode() = hash { it + value + slot + base }
            override val definedVariables: List<VMParam.Named>
                get() = listOf(value) + slot.definedParams()
        }

        /**
         * Matches any store to [slot].
         * @property [previousValue] is a variable that can be used to access the value that was previously stored
         *          inside the slot of storage designated by [slot]
         */
        @Serializable
        data class Store(
            override val value: VMParam.Named,
            override val slot: CVLSlotPattern,
            override val base: Base,
            val previousValue: VMParam.Named?
        ) : StoragePattern() {
            override fun hashCode() = hash { it + value + slot + base + previousValue }
            override val definedVariables: List<VMParam.Named>
                get() = listOfNotNull(value, previousValue) + slot.definedParams()
        }
    }

    /**
     * Matches every Create command. Does this include CREATE2?
     */
    @Serializable
    data class Create(override val value: VMParam.Named) : CVLHookPattern(), PatternWithValue {
        override val definedVariables get() = listOf(value)
        override fun fieldsMatch(other: CVLHookPattern): Boolean = true
        override fun toString(): String = "Create ($value)"
    }

    interface LogHookPattern
    interface CallHookPattern

    @Serializable
    sealed class Opcode: CVLHookPattern() {
        abstract val params : List<VMParam.Named>
        abstract val name: String
        override fun fieldsMatch(other: CVLHookPattern): Boolean = true
        override val definedVariables get() = listOfNotNull((this as? PatternWithValue)?.value) + params

        /** Filled up by `CVLHookProcessor` using the annotations in [com.certora.certoraprover.cvl.HookType] */
    }
}

/**
 * Describes where a function has been declared, be it the spec or some contract.
 */
@Serializable
@Treapable
sealed interface FunctionDeclarationScope : AmbiSerializable

@Serializable
object CvlSpecFile : FunctionDeclarationScope {
    override fun hashCode() = hashObject(this)
    private fun readResolve(): Any = CvlSpecFile
}

@Serializable
object CvlBuiltIn : FunctionDeclarationScope {
    override fun hashCode() = hashObject(this)
    private fun readResolve(): Any = CvlBuiltIn
}

/**
 * A name which references some contract in the context of a spec. Different [ContractReference]s may
 * alias the same contract.
 */
@Serializable
@Treapable
sealed interface ContractReference: AmbiSerializable, FunctionDeclarationScope {
    val name: String
}
@Serializable
object CurrentContract : ContractReference {
    override val name = CVLKeywords.CURRENT_CONTRACT
    override fun hashCode() = hashObject(this)
    private fun readResolve(): Any = CurrentContract
}

/**
 * Used by parametric methods to indicate they aren't bound to a single contract
 */
@Serializable
object AllContracts : ContractReference {
    override val name = CVLKeywords.wildCardExp.keyword
    override fun toString() = name
    private fun readResolve(): Any = AllContracts
    override fun hashCode() = hashObject(this)
}
/**
 * Represents the canonical name of a contract (by convention). Different [SolidityContract]s should
 * never alias the same contract.
 * */
@Serializable
data class SolidityContract(override val name: String) : ContractReference {

    init {
        check(name != Current.name) { "error: use SolidityContract.Current object for this case" }
    }

    override fun toString(): String = name

    companion object {
        val Current: ContractReference get() = CurrentContract
    }
}

/**
 * A [CVLSlotPattern] is a pattern that specifies a slot in storage
 */
@Serializable
@Treapable
sealed class CVLSlotPattern : AmbiSerializable {

    abstract fun getRoot(): Static
    infix fun matches(other: CVLSlotPattern): Boolean = when (this) {
        is Static.Indexed -> other is Static.Indexed && index == other.index && offset == other.offset && solidityContract == other.solidityContract
        is Static.Named -> other is Static.Named && name == other.name && solidityContract == other.solidityContract
        is MapAccess -> other is MapAccess && base matches other.base
        is ArrayAccess -> other is ArrayAccess && base matches other.base
        is StructAccess -> other is StructAccess && base matches other.base && offset == other.offset
        is FieldAccess -> other is FieldAccess && other.field == this.field && other.base matches this.base
    }

    abstract fun definedParams() : List<VMParam.Named>

    /**
     * Some static offset in storage, specified either by a name or a number of bytes.
     */
    @Serializable
    sealed class Static : CVLSlotPattern() {
        override fun getRoot(): Static = this
        abstract val solidityContract: SolidityContract

        /**
         * The name of some field in storage.
         */
        @Serializable
        data class Named(override val solidityContract: SolidityContract, val name: String) : Static() {
            override fun toString(): String = "$solidityContract.$name"

            override fun definedParams() = emptyList<VMParam.Named>()
        }

        /**
         * An offset into storage.
         *
         * @property index offset into storage in words
         * @property offset offset from index into a packed slot, in bytes
         */
        @Serializable
        data class Indexed(
            override val solidityContract: SolidityContract,
            val index: BigInteger,
            val offset: BigInteger
        ) : Static() {
            override fun toString(): String = "(slot $index, offset $offset)"
            override fun definedParams() = emptyList<VMParam.Named>()
        }
    }

    @Serializable
    data class MapAccess(val base: CVLSlotPattern, val key: VMParam.Named) : CVLSlotPattern() {
        override fun getRoot(): Static = base.getRoot()
        override fun toString(): String = "$base[${mapAccessKeyword} ${key.type} ${key.id}]"
        override fun definedParams() = base.definedParams() + listOf(key)
    }

    @Serializable
    data class StructAccess(
        val base: CVLSlotPattern,
        val offset: BigInteger
    ) : CVLSlotPattern() {
        override fun getRoot(): Static = base.getRoot()
        override fun toString(): String = "$base.(offset $offset)"
        override fun definedParams() = base.definedParams()
    }

    @Serializable
    data class FieldAccess(val base: CVLSlotPattern, val field: String) : CVLSlotPattern() {
        override fun getRoot(): Static = base.getRoot()
        override fun toString(): String {
            return "$base.$field"
        }
        override fun definedParams() = base.definedParams()
    }

    @Serializable
    data class ArrayAccess(val base: CVLSlotPattern, val index: VMParam.Named) : CVLSlotPattern() {
        override fun getRoot(): Static = base.getRoot()
        override fun toString(): String = "$base[${arrayAccessKeyword} ${index.type} ${index.id}]"
        override fun definedParams() = base.definedParams() + listOf(index)
    }

    companion object {
        const val mapAccessKeyword = "KEY"
        const val arrayAccessKeyword = "INDEX"
    }
}

/**
 * This interface is implemented by the different type of entities that relate to contracts and reside in the symbol table
 */
@Treapable
interface ContractAliasDefinition: AmbiSerializable {
    val contractName: String
    val alias: String
    val range: Range
}

@Serializable
data class CVLImportedContract(
    val solidityContractVarId: String,
    val solidityContractName: SolidityContract,
    override val range: Range
) : ContractAliasDefinition {
    override val contractName: String
        get() = solidityContractName.name
    override val alias: String
        get() = solidityContractVarId
}

/**
 * The information attached to a contract in the [SymbolTableNamespace.CONTRACT_IDENTIFIER_NAMESPACE]
 */
@KSerializable
data class CVLContractNamespace(
    val solidityContractVarId: String,
    val solidityContractName: SolidityContract,
    val instanceId: BigInteger,
    override val range: Range
) : ContractAliasDefinition {
    override val contractName: String
        get() = solidityContractName.name
    override val alias: String
        get() = solidityContractVarId
}

@Serializable
@Treapable
data class CVLImportedSpecFile(val specFileName: String): AmbiSerializable

/**
 * Declares an uninterpreted sort.
 */
@Serializable
@Treapable
data class SortDeclaration(val sort: CVLType.PureCVLType.Sort, override val range: Range) : HasRange,
    AmbiSerializable

@Serializable
@Treapable
data class ContractTypeDefinition(val name: String, val type: CVLType.PureCVLType): AmbiSerializable

/** parameter used in a signature */
@Treapable
interface TypedParam<T>: AmbiSerializable {
    val type: T
    val name: String?
}

@Serializable
sealed class VMParam : TypedParam<VMTypeDescriptor> {
    fun prettyPrint(): String {
        return this.vmType.prettyPrint() + when (this) {
            is Named -> " ${this.id}"
            is Unnamed -> ""
        }
    }

    abstract val vmType: VMTypeDescriptor
    override val type: VMTypeDescriptor
        get() = vmType

    abstract val range: Range

    /**
     * Unnamed parameters exist for convenience in method signatures, where sometimes we want to name parameters for
     * use in summaries, and sometimes we do not.
     */
    @Serializable
    data class Unnamed(override val vmType: VMTypeDescriptor, override val range: Range) : VMParam() {
        override val name: String? get() = null
        override fun toString() = "$vmType"
    }

    @Serializable
    data class Named(override val name: String, override val vmType: VMTypeDescriptor, override val range: Range, val originalName: String = name) : VMParam() {
        val id: String
            get() = name

        override fun toString(): String = "$type $name"
    }
}

@Serializable
data class CVLParam(
    @SerialName("Named_type")
    override val type: CVLType.PureCVLType,
    val id: String,
    val range: Range,
    val originalId : String = id,
) : TypedParam<CVLType.PureCVLType> {
    override fun toString(): String = "$type $originalId"
    override val name get() = id
}


@Serializable
data class UseDeclarations(
    val importedRules: List<UseDeclaration.ImportedRule>,
    val importedInvariants: List<UseDeclaration.ImportedInvariant>,
    val builtInRulesInUse: List<UseDeclaration.BuiltInRule>
) {
    val importedRulesAndInvariants: List<UseDeclaration>
        get() = importedRules + importedInvariants
    val importedRulesAndInvariantsIds: List<String>
        get() = importedRulesAndInvariants.map { it.id }
    val importedRulesDistinctIds: Set<String>
        get() = importedRules.map { it.id }.toSet()
    val importedInvariantsDistinctIds: Set<String>
        get() = importedInvariants.map { it.id }.toSet()
}


@Serializable
data class OverrideDeclarations(
    val overriddenDefinitions: List<CVLDefinition>,
    val overriddenCVLFunctions: List<CVLFunction>
) {
    val allDecls: List<OverrideDeclaration<*>>
        get() = overriddenDefinitions + overriddenCVLFunctions

    val allDeclsIds: List<String>
        get() = allDecls.map { it.id }

    // for testing
    constructor() : this(emptyList(), emptyList())
}

@Serializable
sealed class UseDeclaration : HasKSerializable {

    abstract val range: Range
    abstract val id: String


    @Serializable
    data class ImportedRule(
        override val id: String,
        override val range: Range,
        val methodParamFilters: MethodParamFilters
    ) :
        UseDeclaration()


    @Serializable
    data class ImportedInvariant(
        override val id: String,
        override val range: Range,
        val proof: CVLInvariantProof,
        val methodParamFilters: MethodParamFilters
    ) :
        UseDeclaration()


    @Serializable
    data class BuiltInRule(
        override val id: String,
        override val range: Range,
        val methodParamFilters: MethodParamFilters
    ) : UseDeclaration() {

        fun resolveBuiltInRuleIdOrNull(): BuiltInRuleId? = try {
            BuiltInRuleId.valueOf(this.id)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Declaration for overriding an imported [CVLDefinition] or [CVLFunction].
 */
@Serializable
sealed class OverrideDeclaration<T> : HasKSerializable {

    // TODO: Naive question: why not keep an instance of T here, to avoid information loss? e.g. of scope

    abstract val range: Range
    abstract val id: String
    abstract val params: List<CVLParam>

    abstract fun materialize(): T
    abstract fun overrides(it: T): Boolean

    protected fun paramsMatchTo(@Suppress("UNUSED_PARAMETER") otherParams: List<CVLParam>): Boolean =
        this.params.zipPred(otherParams) { ours, theirs -> ours.type == theirs.type }

    override fun toString(): String = "override " + materialize().toString()


    @Serializable
    data class CVLDefinition(
        val definition: spec.cvlast.CVLDefinition
    ) : OverrideDeclaration<spec.cvlast.CVLDefinition>() {

        override val range: Range
            get() = definition.range
        override val id: String
            get() = definition.id
        override val params: List<CVLParam>
            get() = definition.params

        override fun materialize(): spec.cvlast.CVLDefinition = definition

        override fun overrides(it: spec.cvlast.CVLDefinition): Boolean =
            id == it.id && paramsMatchTo(it.params) && definition.ret == it.ret

        override fun toString(): String = super.toString()
    }


    @Serializable
    data class CVLFunction(
        val function: spec.cvlast.CVLFunction
    ) : OverrideDeclaration<spec.cvlast.CVLFunction>() {
        override val range: Range
            get() = function.range
        override val id: String
            get() = function.declarationId
        override val params: List<CVLParam>
            get() = function.params

        override fun materialize(): spec.cvlast.CVLFunction = function

        override fun overrides(it: spec.cvlast.CVLFunction): Boolean =
            id == it.declarationId && paramsMatchTo(it.params)

        override fun toString(): String = super.toString()
    }
}

/**
 * Definition and declaration of filter [filterExp] for method parameter [methodParam] of a rule.
 */

@Serializable
@Treapable
data class MethodParamFilter(
    val methodParam: CVLExp.VariableExp,
    val filterExp: CVLExp,
    val range: Range,
    val scope: CVLScope?
): AmbiSerializable {
    companion object {

        fun acceptAllMethodsFilter(
            methodParam: CVLExp.VariableExp,
            range: Range,
            scope: CVLScope
        ): MethodParamFilter =
            withScopeAndRange(scope = scope, range = range) {
                MethodParamFilter(
                    methodParam,
                    CVLExp.Constant.BoolLit(true, tag = CVLType.PureCVLType.Primitive.Bool.asTag()),
                    range,
                    scope
                )
            }

        fun ignoreViewMethodsFilter(
            methodParam: CVLExp.VariableExp,
            range: Range,
            scope: CVLScope
        ): MethodParamFilter =
            withScopeAndRange(scope = scope, range = range) {
                MethodParamFilter(
                    methodParam,
                    CVLExp.UnaryExp.LNotExp(
                        CVLExp.FieldSelectExp(
                            methodParam,
                            viewField,
                            tag = CVLType.PureCVLType.Primitive.Bool.asTag()
                        ), tag = CVLType.PureCVLType.Primitive.Bool.asTag()
                    ),
                    range,
                    scope
                )
            }

        fun onlyPayableMethodsFilter(
            methodParam: CVLExp.VariableExp,
            range: Range,
            scope: CVLScope
        ): MethodParamFilter = withScopeAndRange(scope = scope, range = range) {
            MethodParamFilter(
                methodParam,
                CVLExp.FieldSelectExp(
                    methodParam,
                    fieldName = "isPayable",
                    tag = CVLType.PureCVLType.Primitive.Bool.asTag()
                ),
                range,
                scope
            )
        }

        /**
         * A filter that includes all contract functions except the fallback().
         */
        fun excludeFallbackFunctionFilter(
            methodParam: CVLExp.VariableExp,
            range: Range,
            scope: CVLScope
        ) = withScopeAndRange(scope, range) {
            MethodParamFilter(
                methodParam,
                CVLExp.UnaryExp.LNotExp(
                    CVLExp.FieldSelectExp(
                        methodParam,
                        "isFallback",
                        CVLType.PureCVLType.Primitive.Bool.asTag()
                    ), tag = CVLType.PureCVLType.Primitive.Bool.asTag()
                ),
                range,
                scope
            )
        }

        /** @return a MethodParamFilter for [methodParam] that filters out every method in [dontCall], or `null` if [dontCall] is empty. */
        fun dontCallFilter(
            methodParam: CVLExp.VariableExp,
            dontCall: List<EVMExternalMethodInfo>,
            range: Range,
            scope: CVLScope
        ): MethodParamFilter? {
            if (dontCall.isEmpty()) { return null }
            return withScopeAndRange(scope, range) {
                fun selectorNeSighashExp(sighash: BigInteger, host: BigInteger): CVLExp {
                    val selectorNeExp = CVLExp.RelopExp.NeExp(
                        CVLExp.FieldSelectExp(
                            methodParam,
                            EVMExternalMethodInfo.selectorField,
                            EVMExternalMethodInfo.selectorType.asTag()
                        ),
                        CVLExp.Constant.NumberLit(
                            sighash,
                            CVLType.PureCVLType.Primitive.NumberLiteral(sighash).asTag(),
                            "16"
                        ),
                        tag = CVLType.PureCVLType.Primitive.Bool.asTag()
                    )

                    return CVLExp.BinaryExp.LorExp(
                        selectorNeExp,
                        CVLExp.RelopExp.NeExp(
                            CVLExp.FieldSelectExp(
                                methodParam,
                                EVMExternalMethodInfo.contractField,
                                EVMExternalMethodInfo.contractType.asTag()
                            ),
                            CVLExp.Constant.NumberLit(
                                host,
                                CVLType.PureCVLType.Primitive.NumberLiteral(host).asTag()
                            ),
                            tag = CVLType.PureCVLType.Primitive.Bool.asTag()
                        ),
                        tag = CVLType.PureCVLType.Primitive.Bool.asTag()
                    )
                }

                MethodParamFilter(
                    methodParam,
                    dontCall.fold(CVLExp.Constant.BoolLit(true, CVLType.PureCVLType.Primitive.Bool.asTag()) as CVLExp) { acc, methodSignature ->
                        CVLExp.BinaryExp.LandExp(
                            acc,
                            selectorNeSighashExp(
                                methodSignature.sigHash ?: EVMExternalMethodInfo.magicFallbackSelector,
                                methodSignature.contractInstanceId
                            ),
                            tag = CVLType.PureCVLType.Primitive.Bool.asTag()
                        )
                    },
                    range,
                    scope
                )
            }
        }
    }
}


@Serializable
@Treapable
data class MethodParamFilters(
    val range: Range,
    val scope: CVLScope,
    val methodParamToFilter: Map<String, MethodParamFilter>,
): AmbiSerializable {
    init {
        check(methodParamToFilter.all { (name, filter) -> filter.methodParam.id == name }) {
            "Got an invalid mapping in $methodParamToFilter: keys should be the id of the corresponding filter's methodParam"
        }
    }

    fun isEmpty() = methodParamToFilter.isEmpty()

    companion object {
        private fun methodParamNameToVariableExp(name: String, scope: CVLScope, range: Range) =
            CVLExp.VariableExp(name, CVLExpTag(scope, EVMBuiltinTypes.method, range))

        fun noFilters(range: Range, scope: CVLScope) = MethodParamFilters(range, scope, emptyMap())

        /**
         * Given two method param filters [mpf1] and [mpf2],
         * create a method param filters that does point-wise conjunction of every method param.
         * If a method is in only one of [mpf1] or [mpf2], the filter is kept unchanged
         */
        fun conjunct(
            range: Range, scope: CVLScope, mpf1: MethodParamFilters, mpf2: MethodParamFilters
        ): MethodParamFilters {
            val varToFilter = mpf1.methodParamToFilter.toMutableMap()
            mpf2.methodParamToFilter.forEach { (methodParam, filter) ->
                varToFilter.merge(
                    methodParam, filter
                ) { mpf1Filter, mpf2Filter ->
                    check(mpf1Filter.methodParam.id == mpf2Filter.methodParam.id) {
                        "Expected two param filters for $methodParam to have the same method param, but got ${mpf1Filter.methodParam} and ${mpf2Filter.methodParam}"
                    }
                    mpf1Filter.copy(
                        filterExp = CVLExp.BinaryExp.LandExp(
                            mpf1Filter.filterExp,
                            mpf2Filter.filterExp,
                            tag = CVLExpTag(scope, range = range, type = CVLType.PureCVLType.Primitive.Bool)
                        )
                    )
                }
            }
            return MethodParamFilters(
                range, scope, varToFilter
            )
        }

        /**
         * Creates a [MethodParamFilters] that filters each method parameter in [methodParams] using a filter
         * that excludes all the contract functions in [dontCall] and also the fallback() function if [dontCallFallback]
         * is true.
         *
         * @param methodParams the method type parameters ([CVLExp.VariableExp]s)
         * @param dontCall the list of functions (`ExternalMethodParameterSignature`s) each of which the resulting filter should exclude
         * @param dontCallFallback whether to also exclude the fallback() function in addition to the functions in [dontCall]
         */
        fun dontCallFilters(
            range: Range,
            scope: CVLScope,
            methodParams: Set<String>,
            dontCall: List<EVMExternalMethodInfo>,
            dontCallFallback: Boolean = false
        ) = MethodParamFilters(range, scope, methodParams.associateWithNotNull {
            MethodParamFilter.dontCallFilter(
                methodParamNameToVariableExp(it, scope, range), dontCall, range, scope
            )
        }).let { dontCallFilters ->
            when (dontCallFallback) {
                false -> {
                    dontCallFilters
                }

                true -> {
                    // Add a conjunct that excludes the fallback function
                    conjunct(range,
                        scope,
                        dontCallFilters,
                        MethodParamFilters(range, scope, methodParams.associateWith {
                            MethodParamFilter.excludeFallbackFunctionFilter(
                                methodParamNameToVariableExp(it, scope, range), range, scope
                            )
                        }))
                }
            }
        }
    }

    operator fun get(methodParamName: String): MethodParamFilter? = methodParamToFilter[methodParamName]
}

/** Groups certain CVL declarations (for some reason), in particular rules and
 * [CVLFunction]s */
interface CVLDeclarationWithCode : HasRange {
    override val range: Range
    val declarationId: String
    val params: List<CVLParam>
    val block: List<CVLCmd>
}

interface CVLDeclarationWithMethodParamFilters {
    val methodParamFilters: MethodParamFilters

    val methodParamIds: List<String>
}

/**
 * A function declared in a CVL Spec which encapsulates some common specification, calculation or other behavior.
 */
@Serializable
data class CVLFunction(
    override val range: Range,
    override val declarationId: String,
    override val params: List<CVLParam>,
    override val rets: CVLType.PureCVLType,
    override val block: List<CVLCmd>,
    override val scope: CVLScope
) : CVLDeclarationWithCode, SpecFunction, CreatesScope {
    constructor(
        range: Range,
        declarationId: String,
        params: List<CVLParam>,
        block: List<CVLCmd>,
        scope: CVLScope
    ) : this(range, declarationId, params, CVLType.PureCVLType.Void, block, scope)

    override val functionIdentifier = SpecDeclaration(declarationId)
    override val paramTypes: List<CVLType.PureCVLType> = params.map { arg -> arg.type }
    override fun toString(): String = "function $declarationId"
    override val typeDescription: String = "CVL function"
}


/**
 * A helper class that represents a more fully scoped method signature of a method
 * in a particular contract. The code uses this class in places where it needs to
 * disambiguate between methods with the same signature from different contracts.
 */
data class ScopedMethodSignature(
    val sighashInt: SighashInt?,
    val hostName: String
)

@Treapable
sealed interface InvariantType: AmbiSerializable{
    fun getShortName(): String
}
@KSerializable
data object StrongInvariantType: InvariantType{
    private fun readResolve(): Any = StrongInvariantType
    override fun getShortName(): String {
        return "Strong"
    }
}
@KSerializable
data object WeakInvariantType: InvariantType{
    private fun readResolve(): Any = WeakInvariantType
    override fun getShortName(): String {
        return "Weak"
    }
}

/**
 * @param proof "preserved block"
 *  */

@Serializable
@Treapable
data class CVLInvariant(
    val range: Range,
    val id: String,
    val ruleType: SpecType.Single,
    val params: List<CVLParam>,
    val exp: CVLExp,
    val methodParamFilters: MethodParamFilters,
    val proof: CVLInvariantProof,
    override val scope: CVLScope,
    val needsVerification: Boolean, // false for invariants from imported specs that don't have a `use` statement in the importing one.
    val uniqueRuleIdentifier: RuleIdentifier,
    val invariantType: InvariantType
) : CreatesScope, AmbiSerializable {
    val methodParamId: String
        get() {
            check(methodParamFilters.methodParamToFilter.keys.size <= 1) {
                "Invariants don't support more than one filter variable, and this should be checked before attempting to access this value (the check is performed during invariant rule generation)"
            }
            // If there is no filter in the spec, provide some internal methoed filter variable
            // for use by the auto-generated filters (e.g. filtering out non-state-modifying funcs)
            return methodParamFilters.methodParamToFilter.keys.singleOrNull() ?: "certoraInvF"
        }

    /**
     * Checks that [proof] satisfies:
     * 1. Does not have either multiple [CVLPreserved.Generic] blocks or multiple [CVLPreserved.Fallback] blocks
     * 2. Does not have multiple [CVLPreserved.ExplicitMethod] that use the exact same method signature
     *
     * TODO: move to type checker CERT-2420
     */
    fun checkPreservedIsErrorFree(): VoidResult<CVLError> = collectingErrors {
        fun checkWithParams(withParams: List<CVLParam>, type: String, range: Range) = when (withParams.size) {
            0 -> Unit
            1 -> {
                val param = withParams.single()
                if (param.type != EVMBuiltinTypes.env) {
                    collectError(WithParamWrongType(type, param.type, range))
                } else if (param.id.isWildcard()) {
                    collectError(WithWildcard(param.range))
                } else {
                    Unit
                }
            }
            else -> collectError(MultipleWithParams(type, range))
        }

        // Check for duplicate generic preserved blocks
        val generics = proof.preserved.filterIsInstance<CVLPreserved.Generic>()
        if (generics.size > 1) { collectError(DuplicatePreserved(this@CVLInvariant, generics, DuplicatePreserved.Generic())) }
        generics.singleOrNull()?. let { checkWithParams(it.withParams, "generic", it.range) }

        // Check for duplicate fallback preserved blocks
        val fallbacks = proof.preserved.filterIsInstance<CVLPreserved.Fallback>()
        if (fallbacks.size > 1) { collectError(DuplicatePreserved(this@CVLInvariant, fallbacks, DuplicatePreserved.Fallback())) }
        fallbacks.singleOrNull()?. let { checkWithParams(it.withParams, "fallback", it.range) }

        // Check for duplicate explicit preserved blocks
        val (duplicated, singles) = proof.preserved.filterIsInstance<CVLPreserved.ExplicitMethod>()
            .groupBy { it.methodSignature.qualifiedMethodName.host.name to it.methodSignature.sighashInt }.values
            .partition { it.size > 1 }

        duplicated.forEach { collectError(DuplicatePreserved(this@CVLInvariant, it, DuplicatePreserved.Specific(it.first()))) }
        singles.map { it.first() }.forEach { checkWithParams(it.withParams, it.methodSignature.functionName, it.range) }
    }
}

/**
 * Contains helper code for proving the invariant it is attached to. E.g. [CVLPreserved] blocks, possibly more in the
 * future.
 */
@Serializable
@Treapable
data class CVLInvariantProof(val preserved: List<CVLPreserved>): AmbiSerializable


@Serializable
@Treapable
sealed class CVLPreserved : CreatesScope, AmbiSerializable {
    abstract val range: Range
    abstract val block: List<CVLCmd>

    abstract val withParams: List<CVLParam>
    abstract val params: List<CVLParam>

    val namedParams: List<CVLParam>
        get() {
            val ret = this.withParams + this.params
            // Sanity check that (except for wildcards) no duplication was introduced from
            // the concatenation of `params` and `withParams`.
            check(ret.filterNot { it.id.isWildcard() }.let {
                it.size == it.toSet().size
            }) {
                "Overlap of params and withParams of a preserved clause. withParams: $withParams, params: $params"
            }
            return ret
        }

    /**
     * Matches all functions that do not have a specific preserved block
     */

    @Serializable
    data class Generic(
        override val range: Range,
        override val block: List<CVLCmd>,
        override val withParams: List<CVLParam>,
        override val scope: CVLScope
    ) : CVLPreserved() {
        // Note: an unnamed parameter is a syntax error that will be caught in checkPreservedIsUnambiguous
        override val params: List<CVLParam> get() = emptyList()

        override fun toString(): String {
            return "preserved generic in scope $scope"
        }
    }

    @Serializable
    data class ExplicitMethod(
        override val range: Range,
        val methodSignature: ExternalQualifiedMethodParameterSignature,
        override val block: List<CVLCmd>,
        override val withParams: List<CVLParam>,
        override val scope: CVLScope
    ) : CVLPreserved() {
        override val params: List<CVLParam> =
            (methodSignature.params.mapNotNull { param ->
                check(param is VMParam.Named) {
                    "The parser shouldn't accept unnamed params for preserved method signatures. Got $methodSignature at $range"
                }
                // explicit preserved essentially declare variables to havoc, which then will be passed to a call to
                // this method in a generated rule. Thus, we must infer a cvl type
                param.vmType.getPureTypeToConvertFrom(ToVMContext.ArgumentPassing).resultOrNull()?.let { pureType ->
                    CVLParam(
                        pureType,
                        param.name,
                        param.range
                    )
                }
            })

        override fun toString(): String {
            return "preserved $methodSignature in scope $scope"
        }
    }

    @Serializable
    data class Fallback(
        override val range: Range,
        override val block: List<CVLCmd>,
        override val withParams: List<CVLParam>,
        override val scope: CVLScope
    ) : CVLPreserved() {
        // Note: an unnamed parameter is a syntax error that will be caught in checkPreservedIsUnambiguous
        override val params: List<CVLParam> get() = emptyList()

        override fun toString(): String {
            return "preserved fallback in scope $scope"
        }
    }

    @Serializable
    data class TransactionBoundary(
        override val range: Range,
        override val block: List<CVLCmd>,
        override val withParams: List<CVLParam>,
        override val scope: CVLScope
    ) : CVLPreserved() {
        override val params: List<CVLParam> get() = emptyList()

        override fun toString(): String {
            return "preserved on transaction boundary in scope $scope"
        }
    }
}

/**
 * Describes some constraint which will be assumed on every version of the ghost variable/function this is attached
 * to. Assignments to ghost variables can override these axioms without causing vacuity (probably?).
 */
@Serializable
@Treapable
sealed class CVLGhostAxiom : AmbiSerializable {

    abstract val exp: CVLExp
    abstract fun mapExp(f: (CVLExp) -> CVLExp): CVLGhostAxiom

    /** Axiom that holds at every point in the program. */

    @Serializable
    data class Always(override val exp: CVLExp) : CVLGhostAxiom() {
        override fun mapExp(f: (CVLExp) -> CVLExp): Always = this.copy(exp = f(exp))
    }

    /** Axiom that holds in the initial state of a [CVLInvariant] rule. */

    @Serializable
    data class Initial(override val exp: CVLExp) : CVLGhostAxiom() {
        override fun mapExp(f: (CVLExp) -> CVLExp): Initial = this.copy(exp = f(exp))
    }
}

@Polymorphic
@Treapable
interface ExpressionAnnotation : AmbiSerializable

/**
 * Hint to codegen/typechecking that an expression must be compiled via buffer decoding. It is expected (and checked, within
 * [spec.cvlast.abi.LayoutTraversal]) that the only expressions that can be annotated with this are [spec.cvlast.CVLExp.ArrayLengthExp],
 * [spec.cvlast.CVLExp.ArrayDerefExp], and [spec.cvlast.CVLExp.FieldSelectExp].
 */
@KSerializable
object ComplexMarker : ExpressionAnnotation {
    override fun hashCode() = utils.hashObject(this)
    fun readResolve(): Any = ComplexMarker
}

/**
 * Indicates whether the hash blog convert cast is the identity operation.
 * Context: the instrumentation that adds the convert_hashblob cast doesn't have type information, and therefore
 * doesn't know if the arugment is already of type hashblob or not. The type checker *does* have this information, and
 * thus annotates the convert_hashblob expression with this class to indicate to the compiler whether the "conversion"
 * is in fact the identity transofmration or whether some hash needs to be done.
 */
@Serializable
data class BoxingType(val isIdentity: Boolean) : ExpressionAnnotation

/**
 * Marker that an expression should be handled using the special [spec.StorageAccessCompiler] instead
 * of the usual compositional, recursive compilation of a expression. Expressions marked with this annotation
 * are expected to be produced by the following access non-terminal:
 *
 * base ::= id "." id
 * access ::= base
 *         | access "." id
 *         | access "[" expr "]"
 *         | access "." "length"
 *
 * That is, a complex access must be "rooted at" a field select expression on a variable (expected to be of type [spec.cvlast.CVLType.PureCVLType.Primitive.CodeContract]).
 * This field is the "top-level" field being accessed. Future steps along the access path may only be [spec.cvlast.CVLExp.ArrayLengthExp], [spec.cvlast.CVLExp.ArrayDerefExp],
 * and [spec.cvlast.CVLExp.FieldSelectExp] (a similar restriction exists and is checked for "native" CVL complex types).
 *
 * It is also an invariant that any expression with this annotation has the type [CVLType.VM] with a context of [spec.cvlast.typedescriptors.FromVMContext.StateValue].
 *
 * These two invariants are checked/enforced by [StorageAccessChecker].
 */
@KSerializable
object StorageAccessMarker : ExpressionAnnotation {
    fun readResolve(): Any = StorageAccessMarker
    override fun hashCode() = utils.hashObject(this)
}

/**
 * marks that an object is accessing an immutable variable
 */
@KSerializable
object ImmutableAccessMarker : ExpressionAnnotation {
    fun readResolve(): Any = ImmutableAccessMarker
    override fun hashCode() = utils.hashObject(this)
}

/**
 * marks that this expression should be compiled with short-circuiting
 * e.g. a [NeedsShortCiruiting] `a && b` should be compiled as `if (a) b else false`
 */
@KSerializable
object NeedsShortCiruiting : ExpressionAnnotation {
    fun readResolve(): Any = NeedsShortCiruiting
    override fun hashCode() = hashObject(this)
}

@KSerializable
@Treapable
sealed interface CVLGhostWithAxiomsAndOldCopy: AmbiSerializable {
    val axioms: List<CVLGhostAxiom>
    val oldCopy: Boolean


    fun withAxioms(axioms: List<CVLGhostAxiom>): CVLGhostWithAxiomsAndOldCopy
    fun withOldCopy(oldCopy: Boolean): CVLGhostWithAxiomsAndOldCopy
    fun withIdAndAxioms(id: String, axioms: List<CVLGhostAxiom>): CVLGhostWithAxiomsAndOldCopy

    /**
     * This function duplicates and updates a [CVLGhostWithAxiomsAndOldCopy] to use the appropriate g@new / g@old versions
     * of a ghost.
     */
    fun duplicate(id: String, oldNames: Map<String, String>): CVLGhostWithAxiomsAndOldCopy {
        check(id in oldNames) {"When duplicating a ghost declaration with [oldNames] map, the map should always " +
            "contain the old name (version) of said ghost declaration." +
            "Ghost decleration id: $id, Old names: $oldNames"}
        val axiomSubstitutor = object : CVLExpTransformer<Nothing> {
            override fun variable(exp: CVLExp.VariableExp): CollectingResult<CVLExp, Nothing> {
                return (oldNames[exp.id]?.let { exp.copy(id = it) } ?: exp).lift()
            }

            override fun ghost(exp: CVLExp.ApplyExp.Ghost): CollectingResult<CVLExp.ApplyExp.Ghost, Nothing> {
                return super.ghost(exp).bind { ghostApp ->
                    (oldNames[ghostApp.methodIdWithCallContext.methodId]?.let {
                        ghostApp.copy(id = it)
                    } ?: ghostApp).lift()
                }
            }
        }

        return this.withIdAndAxioms(id = oldNames[id]!!, axioms = axioms.map { axiom -> axiom.mapExp{ exp ->
            axiomSubstitutor.expr(exp).safeForce()}})
    }
}

/**
 * Declaration of a ghost variable.
 *
 * Note that we have two semantically identical syntaxes for ghost variables:
 *  - variable-style, like `ghost uint x` and `ghost mapping(uint => uint) y`
 *  - function-style, like `ghost x() returns uint` and `ghost y(uint) returns uint`
 * Both of these are translated to the same TAC idioms, which are based on maps/arrays (i.e. `select` is used for lookup,
 *  `store` can be used for functional update, etc.).
 */
@Serializable
@Treapable
sealed class CVLGhostDeclaration : AmbiSerializable {
    abstract val range: Range
    abstract val scope: CVLScope
    abstract val persistent: Boolean
    abstract val id: String
    abstract val paramTypes: List<CVLType.PureCVLType>
    abstract val resultType: CVLType.PureCVLType

    /**
     * to be used by implementors of the interface to make their toString representation more correct
     */
    fun persistentPrefixStr() = "persistent ".takeIf { persistent }.orEmpty()

    /**
     * Can be thought of as an uninterpreted function.
     *
     * @param axioms describes constraints which will be assumed to hold on every version of this function, even after
     * havocing.
     *
     * @param [oldCopy] is true if it's a copy of a ghost before havocing it. usually generated as part of hooks in `havoc... assuming...` expressions
     */
    @KSerializable
    data class Function(
        override val range: Range,
        override val id: String,
        override val paramTypes: List<CVLType.PureCVLType>,
        val ret: CVLType.PureCVLType,
        override val persistent: Boolean,
        override val axioms: List<CVLGhostAxiom>,
        override val scope: CVLScope,
        override val oldCopy: Boolean = false
    ) : CVLGhostDeclaration(), SpecFunction, ExpressionAnnotation, CVLGhostWithAxiomsAndOldCopy {
        val functionReference = SpecDeclaration(id)

        override val functionIdentifier = SpecDeclaration(id)

        override val rets: CVLType.PureCVLType
            get() = ret

        override val resultType: CVLType.PureCVLType get() = ret


        // these are here just so we maintain the interface
        override fun withAxioms(axioms: List<CVLGhostAxiom>): CVLGhostWithAxiomsAndOldCopy = this.copy(axioms = axioms)
        override fun withOldCopy(oldCopy: Boolean): CVLGhostWithAxiomsAndOldCopy = this.copy(oldCopy = oldCopy)
        override fun withIdAndAxioms(id: String, axioms: List<CVLGhostAxiom>): CVLGhostWithAxiomsAndOldCopy =
            this.copy(id = id, axioms = axioms)

        val callSignature: String get() = "$id(${paramTypes.joinToString(", ")})"

        override fun toString(): String = "${persistentPrefixStr()}ghost $callSignature returns $resultType"
        override val typeDescription: String = "${persistentPrefixStr()}ghost function"
    }

    /**
     * @param scope the scope that the ghost's axioms live inside (not used for much, yet, maybe local definitions one day)
     */
    @KSerializable
    data class Variable(
        override val range: Range,
        val type: CVLType.PureCVLType,
        override val id: String,
        override val persistent: Boolean,
        override val axioms: List<CVLGhostAxiom>,
        override val scope: CVLScope,
        override val oldCopy: Boolean = false
    ) : CVLGhostDeclaration(), CVLGhostWithAxiomsAndOldCopy {
        override val paramTypes: List<CVLType.PureCVLType>
            get() = when (type) {
                is CVLType.PureCVLType.Ghost.Mapping -> type.getKeys()
                else -> listOf()
            }
        override val resultType: CVLType.PureCVLType
            get() = when (type) {
                is CVLType.PureCVLType.Ghost.Mapping -> type.nestedResultType
                else -> type
            }

        // these are here just so we maintain the interface
        override fun withAxioms(axioms: List<CVLGhostAxiom>): CVLGhostWithAxiomsAndOldCopy = this.copy(axioms = axioms)
        override fun withOldCopy(oldCopy: Boolean): CVLGhostWithAxiomsAndOldCopy = this.copy(oldCopy = oldCopy)
        override fun withIdAndAxioms(id: String, axioms: List<CVLGhostAxiom>): CVLGhostWithAxiomsAndOldCopy =
            this.copy(id = id, axioms = axioms)

        override fun toString(): String {
            return "${persistentPrefixStr()}ghost $type $id"
        }
    }

    /**
     * Represents a ghost generated internally to track the sum of another ghost.
     * [origGhost] is the ghost variable being summed.
     * [nonSummedIndices] are the indices of the summed ghost that we are _not_ summing on.
     *
     * For example, in
     * ```
     * bytes32 b; mathint m;
     * sum address a, uint u. myGhost[a][b][u][m];
     * ```
     * `myGhost` is the [origGhost], indices 0 and 2 are summed, and [nonSummedIndices] = `[1, 3]`.
     */
    @KSerializable
    data class Sum(
        val origGhost: Variable,
        override val id: String,
        val nonSummedIndices: List<Int>,
        val isUnsigned: Boolean,
        override val scope: CVLScope,
    ) : CVLGhostDeclaration() {
        override val range: Range = Range.Empty()
        override val persistent: Boolean = origGhost.persistent

        override val paramTypes: List<CVLType.PureCVLType>
            get() =
                origGhost.paramTypes.filterIndexed { i, _ -> i in nonSummedIndices }

        override val resultType: CVLType.PureCVLType
            get() =
                CVLType.PureCVLType.Primitive.Mathint

        override fun toString(): String {
            return "${persistentPrefixStr()}sum ${paramTypes.joinToString()} $origGhost"
        }

        private fun generateSumGhostType(nonSumVariables: List<CVLType.PureCVLType>): CVLType.PureCVLType {
            if (nonSumVariables.isEmpty()) {
                return CVLType.PureCVLType.Primitive.Mathint
            }

            val keyType = nonSumVariables.first()
            return CVLType.PureCVLType.Ghost.Mapping(keyType, generateSumGhostType(nonSumVariables.drop(1)))
        }

        val type get() = generateSumGhostType(paramTypes)

        /**
         * Given a list of [items] that has the same length as [nonSummedIndices], returns a list of length
         * `origGhost.paramTypes.size` that places those items in the non-summed indices, and `null` in the rest.
         *
         * Using the example from the kdoc of [Sum], if [items] = `["a", "b"]` this function will return `[null, "a", null, "b"]`
         */
        fun <T> placeItemsInNonSummedIndices(items: List<T>): List<T?> {
            check(nonSummedIndices.size == items.size) {
                "each item in the list should correspond to a non-summed index and vice versa"
            }
            return nonSummedIndices.zip(items).toMap().let { idxToItem ->
                (0 until origGhost.paramTypes.size).map { idxToItem[it] }
            }
        }
    }
}

/**
 * Essentially a macro with type checking. The CVL Expression gets inlined before compilation to TAC, however the
 * body of the definition is opaque to type checking at use sites ([ret] is used).
 *
 * @property modifies any ghost functions which are used in the body of the definition with the @new or @old
 * annotations (i.e. they are "modified")
 * @property reads any ghost functions which are used in the body of the definition without the @new or @old
 * annotations (i.e. they are "read" but not "modified")
 */
@Serializable
data class CVLDefinition(
    val range: Range,
    val id: String,
    val params: List<CVLParam>,
    val ret: CVLType.PureCVLType,
    val body: CVLExp,
    val modifies: Set<CVLGhostDeclaration.Function>,
    val reads: Set<CVLGhostDeclaration.Function>,
    val scope: CVLScope?
) : SpecFunction {
    constructor(
        range: Range,
        id: String,
        params: List<CVLParam>,
        retType: CVLType.PureCVLType,
        body: CVLExp,
        scope: CVLScope
    ) :
            this(range, id, params, retType, body, setOf(), setOf(), scope)


    override val rets: CVLType.PureCVLType
        get() = ret

    override val functionIdentifier = SpecDeclaration(id)
    override val paramTypes: List<CVLType.PureCVLType>
        get() = params.map { param -> param.type }

    override fun toString(): String = "definition $id (${params.joinToString(", ")}) returns $rets"
    override val typeDescription: String = "CVL definition"
}

/**
 * Anything you can assign to.
 */
@Serializable
@Treapable
sealed class CVLLhs : HasCVLExpTag, AmbiSerializable {
    /** Every nested [CVLLhs] has an [Id] at its "bottom" -- this returns that.  */
    abstract fun getIdLhs(): Id

    abstract fun updateTag(newTag: CVLExpTag): CVLLhs

    @Serializable
    data class Id(val range: Range, val id: String, override val tag: CVLExpTag) : CVLLhs() {
        override fun getIdLhs() = this
        fun toVariable(): CVLExp.VariableExp = CVLExp.VariableExp(id, tag)
        fun toParamPureType() = CVLParam(tag.getCVLTypeOrNull()!! as? CVLType.PureCVLType ?: error("womp"), id, range)

        override fun updateTag(newTag: CVLExpTag): Id = this.copy(tag = newTag)

        override fun toString(): String = id

        fun isWildCard() = id.isWildcard()
    }

    @Serializable
    data class Array(
        val range: Range, val innerLhs: CVLLhs, val index: CVLExp,
        override val tag: CVLExpTag
    ) : CVLLhs() {
        override fun getIdLhs() = innerLhs.getIdLhs()

        /** A list of all indices (looks into nested [CVLLhs.Array]s). */
        val indices: List<CVLExp>
            get() {
                val res = mutableListOf<CVLExp>()
                var lhs = this.innerLhs
                res.add(index)
                while (lhs is Array) {
                    res.add(lhs.index)
                    lhs = lhs.innerLhs
                }
                return res.asReversed()
            }

        override fun toString(): String = "$innerLhs[$index]"

        override fun updateTag(newTag: CVLExpTag): Array {
            val hasType = newTag.type != null
            unused(hasType)
            return this.copy(
                tag = newTag,
                index = throw CVLTODO("")// Looks like we want a way to have different types for the index??
                //                index.updateType(
                //                    (newTag.type as? CVLFunctionType)?.paramTypes?.first()
                //                        ?: error("failed to type index ($index) in cvl array lhs ($this)")
                //                )
            )
        }
    }
}

/**
 * A command that is an acceptable last command for a rule.
 *
 * These acceptable last commands are Satisfy and Assert which
 * both inherit from this interface.
 */
interface RuleFinalCommand

/** A command, such as `require`, `assert`, variable declarations, etc. */
@JsonClassDiscriminator("cmd_type")
@Serializable
@Treapable
sealed class CVLCmd : AmbiSerializable {
    abstract val scope: CVLScope
    abstract val range: Range
    open fun toPrintString(): String = toString()

    // Because Kotlin Generics (even with reification) erase the type
    // information at runtime, this method needs to pass a class object
    // that specifies the type of the command returned (rather than
    // just using the type parameter exclusively)
    inline fun <reified CMD : CVLCmd> getSubCmdsOfType() : List<CMD> {
        val ret = mutableListOf<CMD>()
        val workList = arrayDequeOf(this)

        while (workList.isNotEmpty()) {
            val cmd = workList.removeFirst()
            if (cmd is CMD) {
                ret.add(cmd)
            }
            when (cmd) {
                is Composite.Block -> workList.addAll(cmd.block)
                is Composite.If -> {
                    workList.add(cmd.thenCmd)
                    workList.add(cmd.elseCmd)
                }
                is Simple -> Unit
            }
        }

        return ret
    }

    val typeEnv get() = scope.let { scope -> CVLTypeEnvironment.empty(range, scope) }


    /** A command that has no subcommands. */
    @Serializable
    sealed class Simple : CVLCmd() {
        /**
         * A print-friendly string of the command for calltraces
         */
        override fun toPrintString(): String = toString()

        /**
         * Used for better calltraces
         */
        @Serializable
        sealed class Label : Simple() {
            abstract val id: Int
            @Serializable
            data class Start(
                override val range: Range,
                val content: CVLReportLabel,
                override val scope: CVLScope,
                override val id: Int
            ) : Label()


            @Serializable
            data class End(
                override val range: Range,
                override val scope: CVLScope,
                override val id: Int
            ) : Label()

        }

        @Serializable
        @Treapable
        data class Assert(
            override val range: Range,
            val exp: CVLExp,
            val description: String?,
            override val scope: CVLScope,
            // is this assert command used as an invariant's postcondition
            val invariantPostCond: Boolean = false
        ) : Simple(), RuleFinalCommand {
            override fun toPrintString() = "assert $exp"
            val descriptionOrDefault get() = description ?: defaultAssertDescription(exp)
        }

        @Serializable
        @Treapable
        data class Satisfy(
            override val range: Range,
            val exp: CVLExp,
            val description: String?,
            override val scope: CVLScope,
            // is this satisfy command used as an invariant's postcondition
            val invariantPostCond: Boolean = false
        ) : Simple(), RuleFinalCommand {
            override fun toPrintString() = "satisfy $exp"
            val descriptionOrDefault get() = description ?: defaultAssertDescription(exp)
        }

        @Serializable
        @Treapable
        sealed class AssumeCmd : Simple() {
            @Serializable
            data class Assume(
                override val range: Range,
                val exp: CVLExp,
                val description: String?,
                override val scope: CVLScope,
                // is this assume command used as an invariant's precondition
                val invariantPreCond: Boolean = false,
                val comesFromSpec: Boolean = false
            ) :
                AssumeCmd() {
                override fun toPrintString() = "require $exp"
                val descriptionOrDefault get() = description ?: defaultAssertDescription(exp)
            }

            /** Comes from a "requireInvariant" command. */
            @Serializable
            data class AssumeInvariant(
                override val range: Range,
                val id: String,
                val params: List<CVLExp>,
                override val scope: CVLScope
            ) : AssumeCmd() {
                override fun toPrintString() = "requireInvariant ${id}(${params.joinToString(", ")})"
            }
        }

        /**
         * Declare a single variable [id] with CVL type [cvlType]
         */
        @Serializable
        data class Declaration(
            override val range: Range,
            val cvlType: CVLType.PureCVLType,
            val id: String,
            override val scope: CVLScope
        ) : Simple()


        /**
         * Definition+Declaration of #[idL] variables with type [type] with identifier names [idL]
         * The right hand-side of the definition is a single [exp] that could represent a tuple if #[idL]>1.
         * @param type The type of [idL] if this command also declares [idL] or null if this is only an assignment
         */

        @Serializable
        data class Definition(
            override val range: Range,
            val type: CVLType.PureCVLType?,
            val idL: List<CVLLhs>,
            val exp: CVLExp,
            override val scope: CVLScope
        ) : Simple() {
            override fun toString() = "${if (type != null) { "$type "} else ""}${
                idL.joinToString(
                    ", ",
                    if (idL.size > 1) "(" else "",
                    if (idL.size > 1) ")" else ""
                )
            } = $exp"

            /** @return All (non-wildcard, simple) variables updated by this assignment */
            val definedVariables get() : List<CVLLhs.Id> = idL.filterIsInstance<CVLLhs.Id>().filter { !it.isWildCard() }
        }

        /**
         * A standalone expression. Necessary to call functions with no return.
         */
        @Serializable
        data class Exp(override val range: Range, val exp: CVLExp, override val scope: CVLScope) : Simple()


        @Serializable
        data class Havoc(
            override val range: Range,
            val targets: List<HavocTarget>,
            val assumingExp: CVLExp?,
            override val scope: CVLScope
        ) : Simple() {
            val assumingExpOrDefault get() = assumingExp ?: CVLExp.Constant.BoolLit.autogeneratedTrue(range, scope)

            override fun toPrintString(): String {
                val formattedTargets = targets.joinToString(separator = ", ")

                return if (assumingExp != null) {
                    "havoc $formattedTargets assuming $assumingExp"
                } else {
                    "havoc $formattedTargets"
                }
            }
        }

        @Serializable
        data class Apply(
            override val range: Range,
            val exp: CVLExp.ApplicationExp,
            override val scope: CVLScope
        ) :
            Simple() {
            override fun toPrintString() = exp.toString()
        }

        @Serializable
        data class ResetStorage(override val range: Range, val exp: CVLExp, override val scope: CVLScope) :
            Simple() {
            override fun toPrintString() = "reset storage $exp"
        }


        @Serializable
        data class ResetTransientStorage(override val range: Range, val exp: CVLExp, override val scope: CVLScope) :
                Simple() {
            override fun toPrintString() = "reset transient storage $exp"
        }

        /**
         * A CVL Cmd that halts execution of Cmds that follow it
         */
        sealed interface HaltingCVLCmd {
            val cmdName: String
        }

        /**
         * Used to return a value from a [CVLFunction]. Should *only* be used in a [CVLFunction].
         */
        @Serializable
        data class Return(override val range: Range, val exps: List<CVLExp>, override val scope: CVLScope) : HaltingCVLCmd, Simple() {
            private fun valueString() = if(this.exps.size == 1) {
                exps.single().toString()
            } else {
                exps.joinToString(",", "(", ")")
            }
            override fun toPrintString() = "return${if(exps.isNotEmpty()) {
                " " + valueString()
            } else { "" }}"
            override val cmdName = "return"
        }

        @Serializable
        data class Revert(override val range: Range, val reason: String?, override val scope: CVLScope) : HaltingCVLCmd, Simple() {
            override fun toPrintString() = "revert(${reason.orEmpty()})"
            override val cmdName = "revert"
        }

        @Serializable
        data class Nop(override val range: Range, override val scope: CVLScope) : Simple() {
            override fun toPrintString() = "nop"
        }

        companion object {
            fun contractFunction(
                range: Range,
                scope: CVLScope,
                methodIdWithCallContext: ResolvedContractCall,
                expList: List<CVLExp>,
                noRevert: Boolean,
                storage: CVLExp.VariableExp,
                isWhole: Boolean = false,
                isParametric: Boolean,
                @Suppress("UNUSED_PARAMETER") methodParamFilter: CVLExp?
            ) =
                Apply(
                    range,
                    if (isParametric) {
                        check(methodIdWithCallContext is SymbolicContractMethod)
                        CVLExp.ApplyExp.ContractFunction.Symbolic(
                            methodIdWithCallContext,
                            expList,
                            noRevert,
                            storage,
                            tag = CVLExpTag(scope, range = range, type = null)
                        )
                    } else {
                        check(methodIdWithCallContext is ConcreteContractMethod)
                        CVLExp.ApplyExp.ContractFunction.Concrete(
                            methodIdWithCallContext,
                            expList,
                            noRevert,
                            storage,
                            isWhole,
                            tag = CVLExpTag(scope, null, range)
                        )
                    },
                    scope
                )
        }

    }

    /** A CVL command that has subcommands */
    @Serializable
    sealed class Composite : CVLCmd(), CreatesScope {

        /** A sequence of commands surrounded by brackets */
        @Serializable
        data class Block(override val range: Range, val block: List<CVLCmd>, override val scope: CVLScope) :
            Composite()

        /** An if-then-else command */
        @Serializable
        data class If(
            override val range: Range,
            val cond: CVLExp,
            val thenCmd: CVLCmd,
            val elseCmd: CVLCmd,
            override val scope: CVLScope
        ) :
            Composite() {
                @KSerializable
                data object ConstantIfCond: ExpressionAnnotation {
                    private fun readResolve(): Any = ConstantIfCond
                }
        }

    }

    fun transformLocation(locTransformer: (Range) -> Range): CVLCmd = when (this) {
        // Do NOT recurse, because this should be done by traverseCmd which also knows how to update the scopes properly!
        is Simple.Label.Start -> this.copy(
            range = locTransformer(range)
        )

        is Simple.Label.End -> this.copy(
            range = locTransformer(range)
        )

        is Simple.Assert -> this.copy(
            locTransformer(range)
        )

        is Simple.Satisfy -> this.copy(
            locTransformer(range)
        )

        is Simple.AssumeCmd.Assume -> this.copy(
            locTransformer(range)
        )

        is Simple.AssumeCmd.AssumeInvariant -> this.copy(
            locTransformer(range)
        )

        is Composite.Block -> Composite.Block(
            locTransformer(range),
            this.block,
            this.scope
        )

        is Simple.Declaration -> this.copy(
            locTransformer(range)
        )

        is Simple.Definition -> this.copy(
            locTransformer(range)
        )

        is Simple.Exp -> this
        is Simple.Havoc -> this.copy(
            locTransformer(range)
        )

        is Composite.If -> this.copy(
            locTransformer(range)
        )

        is Simple.Apply -> this.copy(
            locTransformer(range)
        )

        is Simple.ResetStorage -> this.copy(
            locTransformer(this.range)
        )

        is Simple.ResetTransientStorage -> this.copy(
                locTransformer(this.range)
        )

        is Simple.Return -> this.copy(
            locTransformer(range)
        )

        is Simple.Revert -> this.copy(
            locTransformer(range)
        )

        is Simple.Nop -> this.copy(
            locTransformer(range)
        )
    }

    private fun transformCmd(
        transformerSimple: (Simple) -> CVLCmd,
        transformerBlock: (Composite.Block) -> CVLCmd,
        transformerIf: (Composite.If) -> CVLCmd
    ): CVLCmd = when (this) {
        is Simple -> transformerSimple(this)
        is Composite.Block -> transformerBlock(
            Composite.Block(
                this.range,
                this.block.map { it.transformCmd(transformerSimple, transformerBlock, transformerIf) },
                this.scope
            )
        )

        is Composite.If -> transformerIf(
            Composite.If(
                this.range,
                this.cond,
                this.thenCmd.transformCmd(transformerSimple, transformerBlock, transformerIf),
                this.elseCmd.transformCmd(transformerSimple, transformerBlock, transformerIf),
                this.scope,
            )
        )
    }

    /**
     * Returns a set of all the variable expressions inside the caller.
     * For example, for If command, the function returns all the variables expressions
     * inside the condition, the then branch and the else branch.
     */
    private fun getVarsExp(): Set<CVLExp.VariableExp> {
        return when (this) {
            is Composite.Block -> {
                mutableSetOf<CVLExp.VariableExp>().also { ret ->
                    this.block.forEach { element -> ret.addAll(element.getVarsExp()) }
                }
            }

            is Composite.If -> {
                mutableSetOf<CVLExp.VariableExp>().also { ret ->
                    ret.addAll(this.cond.getVarsExp())
                    ret.addAll(this.thenCmd.getVarsExp())
                    ret.addAll(this.elseCmd.getVarsExp())
                }

            }

            is Simple.Apply -> this.exp.getVarsExp()
            is Simple.Assert -> this.exp.getVarsExp()
            is Simple.Satisfy -> this.exp.getVarsExp()
            is Simple.AssumeCmd.Assume -> this.exp.getVarsExp()
            is Simple.AssumeCmd.AssumeInvariant -> {
                mutableSetOf<CVLExp.VariableExp>().also { ret ->
                    this.params.forEach { element -> ret.addAll(element.getVarsExp()) }
                }
            }

            is Simple.Declaration -> setOf()
            is Simple.Definition -> this.exp.getVarsExp()
            is Simple.Exp -> this.exp.getVarsExp()
            is Simple.Havoc -> targets.flatMapToSet { it.asExp.getVarsExp() } + assumingExpOrDefault.getVarsExp()
            is Simple.Label.End -> setOf()
            is Simple.Label.Start -> setOf()
            is Simple.ResetStorage -> this.exp.getVarsExp()
            is Simple.ResetTransientStorage -> this.exp.getVarsExp()
            is Simple.Return -> this.exps.flatMapToSet { it.getVarsExp() }
            is Simple.Revert -> emptySet()
            is Simple.Nop -> emptySet()
        }
    }

    /**
     * Returns a list of CVL commands induced by [maybeToType].
     */
    fun <T : CVLCmd> getCmdsByType(maybeToType: CVLCmd.() -> T?): List<T> {
        val ret: List<T> = mutableListOf<T>().also {
            when (this) {
                is Simple -> {
                    this.maybeToType()?.let(it::add)
                }

                is Composite.If -> {
                    it.addAll(this.thenCmd.getCmdsByType(maybeToType))
                    it.addAll(this.elseCmd.getCmdsByType(maybeToType))
                }

                is Composite.Block -> {
                    this.block.forEach { subCmd ->
                        it.addAll(subCmd.getCmdsByType(maybeToType))
                    }
                }
            }
        }
        return ret
    }

    /**
     * Returns list of all the sub-rules
     * which are instance of [type]
     */
    inline fun <reified T : CVLCmd> getCmdsByType(): List<T> =
        getCmdsByType { this as? T }
}

fun CVLCmd.wrapWithMessageLabel(msg: String) = listOf(this).wrapWithMessageLabel(msg)
fun CVLCmd.wrapWithLabel(label: CVLReportLabel) = listOf(this).wrapWithLabel(label)

fun List<CVLCmd>.wrapWithMessageLabel(msg: String) = this.wrapWithLabel(CVLReportLabel.Message(msg))
fun List<CVLCmd>.wrapWithLabel(label: CVLReportLabel): List<CVLCmd> {
    if (this.isEmpty()) {
        return this
    }
    val range = this.first().range
    val scope = this.first().scope
    val labelId = Allocator.getFreshId(Allocator.Id.CVL_EVENT)
    return listOf(CVLCmd.Simple.Label.Start(range, label, scope, labelId)) +
        this +
        CVLCmd.Simple.Label.End(range, scope, labelId)
}


/** Contains metadata on a CVLExp. Note: also used for [CVLLhs]s. */
@Serializable
@Treapable
data class CVLExpTag(
    val scope: CVLScope,
    val type: CVLType?,
    val range: Range,
    val hasParenthesis: Boolean = false,
    val annotation: ExpressionAnnotation? = null
) : AmbiSerializable {
    constructor(
        scope: CVLScope,
        range: Range,
        hasParenthesis: Boolean = false
    ) : this(scope, null, range, hasParenthesis)

    companion object {
        fun transient(tag: CVLType.PureCVLType) = CVLExpTag(
            range = Range.Empty(),
            scope = CVLScope(listOf(), null), // no scope at all!,
            type = tag,
            hasParenthesis = false
        )
    }

    fun hasCVLType(): Boolean = getCVLTypeOrNull() != null
    fun getCVLTypeOrNull() = type
    fun getCVLScope() = scope

    fun updateType(newType: CVLType) = this.copy(type = newType)

    override fun toString(): String = "type: ${type?: "?"}, loc: $range"
}

/** Convenience DSL that adds an `asTag` method to [PureCVLType]s */
class ExpTagBuilder(val scope : CVLScope, val range : Range) {
    /** @return a [CVLExpTag] with scope and range given by the enclosing [withScopeAndRange] */
    fun CVLType.asTag() = CVLExpTag(scope, this, range)
}

/** Store `scope` and `range` for enclosed calls to `asTag`. */
fun <T> withScopeAndRange(scope: CVLScope, range: Range, f: ExpTagBuilder.() -> T) =
    ExpTagBuilder(scope, range).f()


/** A transformation that updates the scope of every expression's tag to `newScope` */
class ExpScopeRelocator(val newScope: CVLScope) : CVLExpTransformer<Nothing> {
    override fun expr(exp: CVLExp): CollectingResult<CVLExp, Nothing> {
        return super.expr(exp).bind {it.updateTag(exp.tag.copy(scope = newScope)).lift()}
    }
}

class CmdScopeRelocator(val newScope: CVLScope) : CVLCmdTransformer<Nothing>(ExpScopeRelocator(newScope)) {
    override fun cmd(cmd: CVLCmd): CollectingResult<CVLCmd, Nothing> {
        return super.cmd(cmd).map { c ->
            when (c) {
                is CVLCmd.Composite.Block -> {
                    newScope.extendIn(CVLScope.Item::BlockCmdScopeItem) { blockScope ->
                        val blockScopeRelocator =  CmdScopeRelocator(blockScope)
                        c.copy(scope = newScope, block = blockScopeRelocator.cmdList(c.block).flatten().safeForce())
                    }
                }
                is CVLCmd.Composite.If -> {
                    newScope.extendIn(CVLScope.Item.BranchCmdScopeItem::IfCmdThenScopeItem) { ifThenScope ->
                        newScope.extendIn(CVLScope.Item.BranchCmdScopeItem::IfCmdElseScopeItem) { ifElseScope ->
                            val ifThenScopeRelocator = CmdScopeRelocator(ifThenScope)
                            val ifElseScopeRelocator = CmdScopeRelocator(ifElseScope)
                            c.copy(
                                scope = newScope,
                                thenCmd = ifThenScopeRelocator.cmd(c.thenCmd).safeForce(),
                                elseCmd = ifElseScopeRelocator.cmd(c.elseCmd).safeForce()
                            )
                        }
                    }
                }
                is CVLCmd.Simple.Apply -> c.copy(scope = newScope)
                is CVLCmd.Simple.Assert -> c.copy(scope = newScope)
                is CVLCmd.Simple.Satisfy -> c.copy(scope = newScope)
                is CVLCmd.Simple.AssumeCmd.Assume -> c.copy(scope = newScope)
                is CVLCmd.Simple.AssumeCmd.AssumeInvariant -> c.copy(scope = newScope)
                is CVLCmd.Simple.Declaration -> c.copy(scope = newScope)
                is CVLCmd.Simple.Definition -> c.copy(scope = newScope)
                is CVLCmd.Simple.Exp -> c.copy(scope = newScope)
                is CVLCmd.Simple.Havoc -> c.copy(scope = newScope)
                is CVLCmd.Simple.Label.End -> c.copy(scope = newScope)
                is CVLCmd.Simple.Label.Start -> c.copy(scope = newScope)
                is CVLCmd.Simple.ResetStorage -> c.copy(scope = newScope)
                is CVLCmd.Simple.ResetTransientStorage -> c.copy(scope = newScope)
                is CVLCmd.Simple.Return -> c.copy(scope = newScope)
                is CVLCmd.Simple.Revert -> c.copy(scope = newScope)
                is CVLCmd.Simple.Nop -> c.copy(scope = newScope)
            }
        }
    }
}

/**
 * Describes which version, if any, of a variable/ghost to use. [OLD] and [NEW] only make sense if the variable/ghost
 * has been bound in a two-state context (i.e. havoc'd), otherwise [NEITHER] should be used.
 */
enum class TwoStateIndex(private val desc: String) {
    OLD("@old"),
    NEW("@new"),
    NEITHER(""),
    ;

    override fun toString() = desc
}

interface HasCVLExpTag {
    val tag: CVLExpTag

    fun hasCVLType(): Boolean = tag.hasCVLType()

    /**
     * Get the [CVLType] of this or null if this is untyped
     */
    fun getCVLTypeOrNull() = tag.getCVLTypeOrNull()

    /**
     * Get the [CVLType] of this or throw an exception if this is untyped.
     * This should be used after type checking an expression as we expect that once type checking has occurred
     * every expression has a type.
     *
     * @throws CertoraInternalException if [tag]'s type field is null
     */
    fun getCVLType(): CVLType {
        if (!tag.hasCVLType()) {
            throw CertoraInternalException(
                CertoraInternalErrorType.CVL_COMPILER,
                "Internal invariant broken: ast node $this not typed"
            )
        }
        return tag.getCVLTypeOrNull()!!
    }

    /**
     * Get the [CVLType.PureCVLType] of this or throw an exception if this is untyped or typed with a [CVLType.VM].
     * As noted for [getCVLType] this should be used after type checking. Additionally this should only be used for
     * expressions which we are certain are only ever typed as [CVLType.PureCVLType]. For example, we always expect
     * arithmetic operators to return [CVLType.PureCVLType]s.
     *
     * @throws CertoraInternalException if [tag]'s type field is null
     * @throws CertoraInternalException if [tag]'s type is a [CVLType.VM]
     */
    fun getPureCVLType(): CVLType.PureCVLType =
        when(val t = getCVLType()) {
            is CVLType.PureCVLType -> t
            is CVLType.VM -> throw CertoraInternalException(
                CertoraInternalErrorType.CVL_COMPILER,
                "Internal invariant broken: ast node $this has VM type $t not a pure CVL type"
            )
        }

    /**
     * Gets a [CVLType.PureCVLType] which would be able to hold this or throw an exception if this is untyped or unable
     * to be converted to a [CVLType.PureCVLType] representation. Should only be used for expressions which we are
     * certain can be converted to a [CVLType.PureCVLType] (most likely because of a type checking rule for this
     * expression).
     *
     * @throws CertoraInternalException if [tag]'s type field is null
     * @throws CertoraInternalException if [tag]'s type cannot be converted to a [CVLType.PureCVLType]
     */
    fun getOrInferPureCVLType(): CVLType.PureCVLType =
        getOrInferPureCVLType { _, _ -> null }.resultOrThrow {
            CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, "$this was expected to be tagged with either " +
                "a pure CVL type or a VM Type with a correspondence but instead got ${getCVLTypeOrNull()}")
        }

    /**
     * Gets a [CVLType.PureCVLType] which would be able to hold this or returns an error if this is unable to be
     * converted to a [CVLType.PureCVLType] representation. Should only be used for expressions which we know are typed.
     *
     * @throws CertoraInternalException if [tag]'s type field is null since we should be calling this only once [this]
     * has been type checked
     */
    fun<T> getOrInferPureCVLType(error: (List<String>, VMTypeDescriptor) -> T): CollectingResult<CVLType.PureCVLType, T> =
        getCVLType().getOrInferPureCVLType(error)

    fun getScope(): CVLScope =
        tag.scope

    fun getRangeOrEmpty(): Range = tag.range
}

@KSerializable
enum class CVLBuiltInName(val bifName: String) {
    KECCAK256("keccak256"),
    ECRECOVER("ecrecover"),
    SHA256("sha256"),
    FOUNDRY_PRANK("__certora_foundry_prank"),
    FOUNDRY_START_PRANK("__certora_foundry_startPrank"),
    FOUNDRY_STOP_PRANK("__certora_foundry_stopPrank"),
    FOUNDRY_WARP("__certora_foundry_warp"),
    FOUNDRY_ROLL("__certora_foundry_roll"),
    FOUNDRY_MOCK_CALL("__certora_foundry_mockCall"),
    FOUNDRY_CLEAR_MOCKED_CALLS("__certora_foundry_clearMockedCalls"),
    FOUNDRY_EXPECT_EMIT("__certora_foundry_expectEmit"),
    ;

    override fun toString() = this.bifName
}

/**
 * What are we hashing? If it is a single bytes array ([isArray] is true) then we will
 * use [vc.data.TACCmd.Simple.AssigningCmd.AssignSha3Cmd], otherwise we are hashing a
 * series of (word sized) primitives, in which case we use [vc.data.TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd]
 */
@Serializable
data class HashSort(val isArray: Boolean) : ExpressionAnnotation

@Serializable
@Treapable
sealed class CVLExp : HasCVLExpTag, AmbiSerializable {
    abstract fun eval(): Constant.NumberLit?

    internal fun evalBinary(l: CVLExp, r: CVLExp, _eval: (BigInteger, BigInteger) -> BigInteger?): Constant.NumberLit? {
        return l.eval()?.let { l ->
            r.eval()?.let { r ->
                _eval(l.n, r.n)?.let {
                    Constant.NumberLit(
                        it,
                        l.tag.copy(type = CVLType.PureCVLType.Primitive.NumberLiteral(it)),
                        printHint = if (l.printHint == "16" || r.printHint == "16") { "16" } else { "10" }
                    )
                }
            }
        }
    }

    internal fun evalUnary(e: CVLExp, _eval: (BigInteger) -> BigInteger?): Constant.NumberLit? {
        return e.eval()?.let { e ->
            _eval(e.n)?.let {
                Constant.NumberLit(
                    it,
                    e.tag.copy(type = CVLType.PureCVLType.Primitive.NumberLiteral(it)),
                    e.printHint
                )
            }
        }
    }

    internal fun booleanEvalCheck(v: BigInteger) =
        check(v == BigInteger.ZERO || v == BigInteger.ONE) {
            "for boolean expressions the evaluator should return only 1 or 0, got $v"
        }

    fun String.wrapWithParens() = if (tag.hasParenthesis) {
        "($this)"
    } else {
        this
    }


    /**
     * Relational? Operation. Takes two values, returns a Boolean.
     */
    @Serializable
    sealed class RelopExp : CVLExp() {
        abstract val relop: CVLERelop
        abstract val l: CVLExp
        abstract val r: CVLExp

        @Serializable
        sealed class ArithRelopExp : RelopExp() {

            @Serializable
            data class GeExp(
                override val l: CVLExp,
                override val r: CVLExp,
                override val tag: CVLExpTag,
            ) : ArithRelopExp() {
                override val relop: CVLERelop
                    get() = CVLERelop.GE

                override fun toString(): String {
                    return super.toString()
                }

                override fun eval(): Constant.NumberLit? {
                    return evalBinary(l, r) { _l, _r ->
                        if (_l >= _r) { BigInteger.ONE } else { BigInteger.ZERO }
                    }
                }
            }

            @Serializable
            data class GtExp(
                override val l: CVLExp,
                override val r: CVLExp,
                override val tag: CVLExpTag
            ) : ArithRelopExp() {
                override val relop: CVLERelop
                    get() = CVLERelop.GT

                override fun toString(): String {
                    return super.toString()
                }

                override fun eval(): Constant.NumberLit? {
                    return evalBinary(l, r) { _l, _r ->
                        if (_l > _r) { BigInteger.ONE } else { BigInteger.ZERO }
                    }
                }
            }

            @Serializable
            data class LeExp(
                override val l: CVLExp,
                override val r: CVLExp,
                override val tag: CVLExpTag
            ) : ArithRelopExp() {
                override val relop: CVLERelop
                    get() = CVLERelop.LE

                override fun toString(): String {
                    return super.toString()
                }

                override fun eval(): Constant.NumberLit? {
                    return evalBinary(l, r) { _l, _r ->
                        if (_l <= _r) { BigInteger.ONE } else { BigInteger.ZERO }
                    }
                }
            }

            @Serializable
            data class LtExp(
                override val l: CVLExp,
                override val r: CVLExp,
                override val tag: CVLExpTag
            ) : ArithRelopExp() {
                override val relop: CVLERelop
                    get() = CVLERelop.LT

                override fun toString(): String {
                    return super.toString()
                }

                override fun eval(): Constant.NumberLit? {
                    return evalBinary(l, r) { _l, _r ->
                        if (_l < _r) { BigInteger.ONE } else { BigInteger.ZERO }
                    }
                }
            }

            override fun toString(): String {
                return "$l $relop $r".wrapWithParens()
            }
        }

        @Serializable
        data class EqExp(
            override val l: CVLExp,
            override val r: CVLExp,
            override val tag: CVLExpTag
        ) : RelopExp() {
            override val relop: CVLERelop
                get() = CVLERelop.EQ

            override fun toString(): String {
                return super.toString()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { _l, _r ->
                    if (_l == _r) { BigInteger.ONE } else { BigInteger.ZERO }
                }
            }
        }

        @Serializable
        data class NeExp(
            override val l: CVLExp,
            override val r: CVLExp,
            override val tag: CVLExpTag
        ) : RelopExp() {
            override val relop: CVLERelop
                get() = CVLERelop.NE

            override fun toString(): String {
                return super.toString()
            }


            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { _l, _r ->
                    if (_l != _r) { BigInteger.ONE } else { BigInteger.ZERO }
                }
            }
        }

        override fun toString(): String {
            return "$l $relop $r".wrapWithParens()
        }


        enum class CVLERelop {
            LT, LE, GT, GE, EQ, NE;

            override fun toString(): String {
                return when (this) {
                    LT -> "<"
                    LE -> "<="
                    GT -> ">"
                    GE -> ">="
                    EQ -> "=="
                    NE -> "!="
                }
            }

            companion object {
                @Deprecated("Not supposed to convert string to a CVL Relop")
                fun fromString(s: String): CVLERelop {
                    return when (s) {
                        "<" -> LT
                        "<=" -> LE
                        ">" -> GT
                        ">=" -> GE
                        "==" -> EQ
                        "!=" -> NE
                        else -> throw RuntimeException("Illegal relop $s")
                    }
                }
            }
        }
    }

    /**
     * Constant value, written in a specification.
     */
    @Serializable
    @Treapable
    sealed class Constant : CVLExp() {
        abstract infix fun evalEq(other: Constant): Boolean

        /**
         * Represents an enum constant, which in spec is always referenced as `ContractName.EnumName.MemberName`
         * @property definingContract The contract in which the enum is defined. If the
         * enum is a top-level enum, this will be the contract from the scene that uses it.
         * @property enumName The name of the enum. For top-level enums this will be only `EnumName`, but for enums
         * defined within contracts this will be `ContractName.EnumName`.
         */
        @Serializable
        data class EnumConstant(
            val definingContract: SolidityContract,
            val enumName: String,
            val memberName: String,
            override val tag: CVLExpTag
        ) : Constant() {
            override fun evalEq(other: Constant): Boolean {
                return other is EnumConstant && other.definingContract == this.definingContract && other.enumName == this.enumName && other.memberName == this.memberName
            }

            /**
             * If this enum was defined in a contract [enumName] will be of the form `ContractName.EnumName`. See the [EnumConstant]
             */
            override fun toString(): String = "$enumName.$memberName".wrapWithParens()

            override fun eval(): NumberLit? = (this.getCVLTypeOrNull() as? CVLType.PureCVLType.Enum)?.elements?.indexOf(memberName)?.toBigInteger()?.let {
                NumberLit(it, tag.updateType(CVLType.PureCVLType.Primitive.NumberLiteral(it)))
            }
        }

        @Serializable
        data class BoolLit(
            val b: BigInteger,
            override val tag: CVLExpTag
        ) : Constant() {
            constructor(b: Boolean, tag: CVLExpTag) : this(
                if (b) BigInteger.ONE else BigInteger.ZERO, tag
            )

            override fun evalEq(other: Constant): Boolean = other is BoolLit && other.b == b

            override fun toString(): String =
                if (b == BigInteger.ONE) {
                    "true"
                } else {
                    "false"
                }.wrapWithParens()

            override fun eval(): NumberLit {
                return NumberLit(b, tag.updateType(CVLType.PureCVLType.Primitive.NumberLiteral(b)))
            }

            companion object {
                fun autogeneratedTrue(rangeOfParentExp: Range, scope: CVLScope): BoolLit {
                    val tag = CVLExpTag(
                        scope,
                        CVLType.PureCVLType.Primitive.Bool,
                        Range.Empty("autogenerated bool expression at $rangeOfParentExp"),
                    )

                    return BoolLit(true, tag)
                }
            }
        }

        /**
         * Represents a number literal.
         * [printHint] helps to determine how to print this value to the user (e.g. in case of a typechecking error). It
         * can be either "10" (default) or "16" to indicate base 10 and base16 representation, respectively, or some other
         * string which will be printed as is (used for cases such as our 'max_*' constant keywords).
         */
        @Serializable
        data class NumberLit(
            val n: BigInteger,
            override val tag: CVLExpTag,
            val printHint: String = "10"
        ) : Constant() {
            init {
                if (tag.hasCVLType()) {
                    val type = tag.getCVLTypeOrNull()
                    /**
                     * The type of a [NumberLit] is not always a [CVLType.PureCVLType.NumberLiteral] with the exact same
                     * value of [n].
                     *  - We may want to tag a NumberLit with a supertype of that constant (e.g. in order to "remember"
                     *    what type it's being compared to etc.)
                     *  - to_bytesK(<constant>) is replaced with a number-literal in [CalculateBytesKConstants], but
                     *    keeps the original type.
                     */
                    check(type is CVLType.PureCVLType.Primitive.BytesK ||
                        CVLType.PureCVLType.Primitive.NumberLiteral(n) isSubtypeOf type as CVLType.PureCVLType) {
                        "Incompatible tag for $this: $type"
                    }
                }
            }

            constructor(s: String, tag: CVLExpTag) : this(BigInteger(s), tag)

            override fun evalEq(other: Constant): Boolean = other is NumberLit && other.n == n

            override fun toString(): String {
                return when (printHint) {
                    "10" -> n.toString()
                    "16" -> "0x${n.toString(16)}"
                    else -> printHint
                }.wrapWithParens()
            }

            override fun eval(): NumberLit = this
        }

        @Serializable
        data class StringLit(val s: String, override val tag: CVLExpTag) : Constant() {
            override fun evalEq(other: Constant): Boolean = other is StringLit && s == other.s
            override fun toString(): String {
                return s.wrapWithParens()
            }

            override fun eval(): NumberLit? = null
        }

        /** represents a literal of type [CVLStructType]; all fields must be computable at compile time; example use case:
         *  method literals, like "foo(uint,uint)" */
        @Serializable
        data class StructLit(val fieldNameToEntry: Map<String, CVLExp>, override val tag: CVLExpTag) : Constant(), ExpressionAnnotation {
            override fun evalEq(other: Constant): Boolean =
                other is StructLit && fieldNameToEntry == other.fieldNameToEntry

            override fun toString(): String {
                return fieldNameToEntry.toString().wrapWithParens()
            }

            override fun eval(): NumberLit? = null
        }

        @Serializable
        data class SignatureLiteralExp(
            val function: QualifiedMethodParameterSignature,
            override val tag: CVLExpTag
        ) : java.io.Serializable, Constant() {
            override fun evalEq(other: Constant): Boolean =
                other is SignatureLiteralExp && function.matchesContractAndParams(other.function)

            override fun toString(): String {
                return "sig:$function".wrapWithParens()
            }

            override fun eval(): NumberLit? = null
        }
    }

    @Serializable
    data class ArrayLitExp(val elements: List<CVLExp>, override val tag: CVLExpTag) : CVLExp() {
        override fun toString(): String {
            return "[${elements.joinToString(", ")}]".wrapWithParens()
        }

        @KSerializable
        data class ArrayLiteralTypeHint(
            val type: CVLType.PureCVLType
        ) : ExpressionAnnotation

        override fun eval(): Constant.NumberLit? = null
    }

    /**
     * Expression with two operands. Excludes [RelopExp].
     */
    @Serializable
    sealed class BinaryExp : CVLExp() {
        abstract val l: CVLExp
        abstract val r: CVLExp

        @Serializable
        data class AddExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "${l} + ${r}".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.plus(y)
                }
            }
        }
        @Serializable
        data class DivExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "$l / $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.div(y)
                }
            }
        }

        @Serializable
        data class ModExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "$l % $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.mod(y.abs())
                }
            }
        }

        @Serializable
        data class ExponentExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) :
            BinaryExp() {
            override fun toString(): String {
                return "${l}^${r}".wrapWithParens()
            }

            val base get() = l
            val pow get() = r

            override fun eval(): Constant.NumberLit? {
                return evalBinary(base, pow) { base, pow ->
                    pow.takeIf { it >= BigInteger.ZERO }?.let { pow ->
                        base.pow(pow.toInt())
                    }
                }
            }
        }

        @Serializable
        data class IffExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "$l <=> $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { _l, _r ->
                    booleanEvalCheck(_l)
                    booleanEvalCheck(_r)
                    if (_l == _r) { BigInteger.ONE } else { BigInteger.ZERO }
                }
            }
        }

        @Serializable
        data class ImpliesExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) :
            BinaryExp() {
            override fun toString(): String {
                return "$l => $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { _l, _r ->
                    booleanEvalCheck(_l)
                    booleanEvalCheck(_r)
                    if (_l == BigInteger.ZERO || _r == BigInteger.ONE) { BigInteger.ONE } else { BigInteger.ZERO }
                }
            }
        }

        @Serializable
        data class BwOrExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "$l | $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.or(y)
                }
            }
        }

        @Serializable
        data class BwXOrExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "$l ^ $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.xor(y)
                }
            }
        }

        @Serializable
        data class BwAndExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "$l & $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.and(y)
                }
            }
        }

        @Serializable
        data class BwLeftShiftExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) :
            BinaryExp() {
            override fun toString(): String {
                return "$l << $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.shiftLeft(y.toInt())
                }
            }
        }

        @Serializable
        data class BwRightShiftExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) :
            BinaryExp() {
            override fun toString(): String {
                return "$l >> $r".wrapWithParens() // arithmetical
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.shiftRight(y.toInt())
                }
            }
        }

        @Serializable
        data class BwRightShiftWithZerosExp(
            override val l: CVLExp,
            override val r: CVLExp,
            override val tag: CVLExpTag
        ) : BinaryExp() {
            override fun toString(): String {
                return "$l >>> $r".wrapWithParens() // logical, taking operator from Java https://en.wikipedia.org/wiki/Bitwise_operation
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    // Note: this function should be called after l and r are checked to be within range, so a sanity
                    // check of this here is enough, no need for a user-facing error.
                    check((x >= BigInteger.ZERO && x.bitLength() <= Config.VMConfig.registerBitwidth) ||
                          (x < BigInteger.ZERO && x.bitLength() < Config.VMConfig.registerBitwidth)) {
                        "Unexpected out-of-range value for logical shift-right"
                    }
                    if (y != BigInteger.ZERO) {
                        // Java has no logical shift right operation for BigIntegers since it's not really well-defined on
                        // "infinite word size" numbers.
                        // However, since we only support this operation on integers that fit in the VM's register bitwidth
                        // we could emulate it:
                        // 1. Perform `x & max_uint`. this effectively performs a sign-extension on x to the VM's register
                        //    bitwidth, and the result is considered by Java to be unsigned
                        // 2. Perform an arithmetic shift-right on the result. Since the result is considered unsigned we
                        //    effectively get a logical shift
                        x.and(Config.VMConfig.maxUint).shiftRight(y.toInt())
                    } else {
                        // this special case is because when y == 0, x should be returned as is, without a sign-change
                        x
                    }
                }
            }
        }

        @Serializable
        data class LandExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { _l, _r ->
                    booleanEvalCheck(_l)
                    booleanEvalCheck(_r)
                    if (_l == BigInteger.ONE && _r == BigInteger.ONE) {
                        BigInteger.ONE
                    } else {
                        BigInteger.ZERO
                    }
                }
            }

            override fun toString(): String {
                return "$l && $r".wrapWithParens()
            }
        }

        @Serializable
        data class LorExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { _l, _r ->
                    booleanEvalCheck(_l)
                    booleanEvalCheck(_r)
                    if (_l == BigInteger.ONE || _r == BigInteger.ONE) {
                        BigInteger.ONE
                    } else {
                        BigInteger.ZERO
                    }
                }
            }

            override fun toString(): String {
                return "$l || $r".wrapWithParens()
            }
        }

        @Serializable
        data class MulExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "$l * $r".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.multiply(y)
                }
            }
        }

        @Serializable
        data class SubExp(override val l: CVLExp, override val r: CVLExp, override val tag: CVLExpTag) : BinaryExp() {
            override fun toString(): String {
                return "${l} - ${r}".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalBinary(l, r) { x, y ->
                    x.minus(y)
                }
            }
        }
    }


    @Serializable
    data class CondExp(val c: CVLExp, val e1: CVLExp, val e2: CVLExp, override val tag: CVLExpTag) :
        CVLExp() {
        override fun toString(): String {
            return "$c ? $e1 : $e2".wrapWithParens()
        }

        override fun eval(): Constant.NumberLit? {
            return evalUnary(c) { _c ->
                booleanEvalCheck(_c)
                if (_c == BigInteger.ZERO) { e2.eval()?.n } else { e1.eval()?.n }
            }
        }
    }

    @Serializable
    sealed class UnaryExp : CVLExp() {
        abstract val e: CVLExp

        @Serializable
        data class BwNotExp(override val e: CVLExp, override val tag: CVLExpTag) : UnaryExp() {
            override fun toString(): String {
                return "~$e".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalUnary(e) { x ->
                    val smallestType = x.smallestTypeForNumberLit()
                    val bitwidth = when(smallestType) {
                        is CVLType.PureCVLType.Primitive.UIntK -> smallestType.bitWidth
                        is CVLType.PureCVLType.Primitive.IntK -> smallestType.bitWidth
                        is CVLType.PureCVLType.Primitive.Mathint -> throw IllegalStateException("Cannot bwnot on a mathint $x")
                        else -> throw IllegalStateException("Unexpected smallest type for $x is $smallestType")
                    }
                    BigInteger.TWO.pow(bitwidth).minus(BigInteger.ONE).andNot(x)
                }
            }
        }

        @Serializable
        data class LNotExp(override val e: CVLExp, override val tag: CVLExpTag) : UnaryExp() {
            override fun toString(): String {
                return "!($e)".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalUnary(e) { _e ->
                    booleanEvalCheck(_e)
                    if (_e == BigInteger.ZERO) { BigInteger.ONE } else { BigInteger.ZERO }
                }
            }
        }

        @Serializable
        data class UnaryMinusExp(override val e: CVLExp, override val tag: CVLExpTag) : UnaryExp() {
            override fun toString(): String {
                return "-${e}".wrapWithParens()
            }

            override fun eval(): Constant.NumberLit? {
                return evalUnary(e) { x ->
                    x.negate()
                }
            }
        }
    }

    @Serializable
    data class QuantifierExp(
        val isForall: Boolean,
        val param: CVLParam,
        val body: CVLExp,
        override val tag: CVLExpTag,
        val originalName : String = param.id,
    ) : CVLExp() {
        override fun toString(): String {
            return "${if (isForall) "forall" else "exists"} $qVarType $originalName. $body".wrapWithParens()
        }

        override fun eval(): Constant.NumberLit? = null

        val qVarName = param.id
        val qVarType = param.type
    }

    @Serializable
    data class SumExp(
        val params: List<CVLParam>,
        val body: ArrayDerefExp,
        val isUnsigned: Boolean,
        override val tag: CVLExpTag,
    ) : CVLExp() {
        override fun toString(): String {
            return "sum ${params.joinToString(separator = ", ") { "${it.type} ${it.id}" }}. $body".wrapWithParens()
        }
        override fun eval(): Constant.NumberLit? = null

        /** The ghost that is being summed */
        val baseVar: VariableExp get() = body.baseArray as? VariableExp ?: error("expected the base array to be a ghost variable")

        private val summedIndices: List<Int> get() {
            val ret = body.indices
                .filterAndIndex { indexExp ->
                    /** We verify during typechecking that all summed indices are [VariableExp]s, see [SumNonBasicParamExpression] */
                    params.any { it.id == (indexExp as? VariableExp)?.id }
                }
                .map { (index, _) -> index }
            return ret
        }

        val nonSummedIndices = (0 until body.indices.size).filterNot { it in summedIndices }

        /** A unique name for the ghost that implements this sum */
        val sumGhostName: String get() =
            "g_sum_of_ghost_${baseVar.id}_on_${if (summedIndices.size == 1) { "index" } else { "indices" } }_${summedIndices.joinToString("_")}"
    }

    /** Field access, e.g., `s.x` where s is a struct/object
     *
     * Note this syntax is overloaded for several purposes. It could indicate a struct field access. It could also
     * specify an enum constant or some user defined type. See [FieldSelectSemantics].
     * */
    @Serializable
    data class FieldSelectExp(
        val structExp: CVLExp,
        val fieldName: String,
        override val tag: CVLExpTag
    ) : CVLExp(), HavocTarget {
        override fun toString(): String {
            return "$structExp.$fieldName".wrapWithParens()
        }

        override fun eval(): Constant.NumberLit? {
            if (structExp is Constant.SignatureLiteralExp) {
                return (structExp.tag.annotation as? Constant.StructLit)?.fieldNameToEntry?.get(fieldName)?.eval()
            }

            return null
        }

        fun isArrayLengthFieldAccess() = fieldName == CVLType.PureCVLType.CVLArrayType.lengthFieldName

        fun isDynamicArrayLengthExp(): Boolean {
            if (!isArrayLengthFieldAccess()) {
                return false
            }

            return when (val ty = structExp.getCVLType()) {
                is CVLType.PureCVLType.DynamicArray -> true
                is CVLType.VM -> {
                    ty.descriptor is VMDynamicArrayTypeDescriptor || ty.descriptor is EVMTypeDescriptor.PackedBytes1Array
                }
                else -> false
            }
        }
    }

    @Serializable
    data class ArrayDerefExp(val array: CVLExp, val index: CVLExp, override val tag: CVLExpTag) :
        CVLExp(), HavocTarget {
        override fun toString(): String {
            return "$array[$index]".wrapWithParens()
        }

        override fun eval(): Constant.NumberLit? = null

        /** In case this is a nested array dereference this returns the innermost array expression, e.g. `a` for
         * `a[i][j]`. */
        val baseArray: CVLExp
            get() = when (array) {
                is ArrayDerefExp -> array.baseArray
                else -> array
            }

        /** A list of all indices (looks into nested [ArrayDerefExp]s).
         * (In the order same as the expression would be printed, e.g. on `a[i][j][k]` this will return [i, j, k].) */
        val indices: List<CVLExp>
            get() {
                val res = mutableListOf<CVLExp>()
                var array = this.array
                res.add(index)
                while (array is ArrayDerefExp) {
                    res.add(array.index)
                    array = array.array
                }
                return res.asReversed()
            }

        fun isBalances() = array.tryAs<VariableExp>()?.id == CVLKeywords.nativeBalances.keyword
        fun baseCVLKeyword() = array.tryAs<VariableExp>()?.id?.let { CVLKeywords.find(it) };

    }

    /**
     * Checking for set membership (i.e. is [e] a member of set [set]).
     *
     * NB: has *nothing* to do with setting memory.
     * */
    @Serializable
    data class SetMemExp(val e: CVLExp, val set: CVLExp, override val tag: CVLExpTag) : CVLExp() {
        override fun toString(): String {
            return "$e in $set".wrapWithParens()
        }

        override fun eval(): Constant.NumberLit? = null
    }


    @Serializable
    data class VariableExp(
        val id: String,
        override val tag: CVLExpTag,
        val twoStateIndex: TwoStateIndex,
        val originalName : String = id,
    ) : CVLExp(), HavocTarget {
        override fun hashCode() = hash { it + id + tag + twoStateIndex }

        // for Java
        constructor(id: String, tag: CVLExpTag) : this(
            id,
            tag,
            TwoStateIndex.NEITHER,
        )

        fun isWildCard() = id.isWildcard()

        fun isLastStorage() = id == CVLKeywords.lastStorage.keyword

        override fun toString() = "$originalName$twoStateIndex".wrapWithParens()

        override fun eval(): Constant.NumberLit? =
            (tag.annotation as? ContractInstanceId)?.instanceId?.let {
                Constant.NumberLit(it, tag.updateType(CVLType.PureCVLType.Primitive.NumberLiteral(it)), "16")
            }

        @KSerializable
        data class ContractInstanceId(val instanceId: BigInteger) : ExpressionAnnotation
    }

    /**
     * [inCVLBlockId] a unique id given to [CastExpr]s defined inside CVL blocks
     * (such as cvl functions, hooks) which get inlined each time when used.
     * Intended for avoiding issues in multi-assert mode, when we want to create a
     * unique sub-rule for each user-defined assert (such asserts may be generated from
     * [CastExpr]s).
     * At the beginning, [inCVLBlockId] will always be null, but later, in the context of a
     * CVL block, it will be given a unique id by the allocator (see CVLCompiler.assertModifier).
     */
    @Serializable
    data class CastExpr(
        val toCastType: CVLType.PureCVLType,
        val arg: CVLExp,
        override val tag: CVLExpTag,
        val castType: CastType,
        val inCVLBlockId: Int? = null
    ) : CVLExp() {
        override fun hashCode() = hash { it + toCastType + arg + tag + castType }
        override fun toString() : String  {
            return "${castType.str}_$toCastType($arg)".wrapWithParens()
        }

        override fun eval(): Constant.NumberLit? = null
    }

    @Serializable
    sealed class ApplicationExp : CVLExp() {
        abstract val args: List<CVLExp>
        abstract val callableName: String
    }

    /**
     * At parse time, we only know that an apply expression is an application of *something*. A phase of typechecking
     * disambiguates these using the symbol table (see [CVLAstBuilder.resolveUnresolvedApplyExpression] and
     * [CVLExpTypeChecker.resolveContractApplication]). After this disambiguation, an [UnresolvedApplyExp] should no
     * longer exist in the AST.
     */
    @Serializable
    data class UnresolvedApplyExp(
        val base: CVLExp?,
        val methodId: String,
        override val args: List<CVLExp>,
        override val tag: CVLExpTag,
        val twoStateIndex: TwoStateIndex = NEITHER,
        val invokeStorage: VariableExp,
        val invokeIsSafe: Boolean = false,
        val invokeIsWhole: Boolean = false
    ) : ApplicationExp() {

        interface WhitelistedUnresolvedFunc : ExpressionAnnotation

        @Serializable
        object VirtualFunc : WhitelistedUnresolvedFunc {
            override fun hashCode() = utils.hashObject(this)
            private fun readResolve(): Any = VirtualFunc
        }

        @Serializable
        object ListedInternalFunc : WhitelistedUnresolvedFunc {
            override fun hashCode() = utils.hashObject(this)
            private fun readResolve(): Any = ListedInternalFunc
        }

        override val callableName: String
            get() = methodId

        override fun toString(): String {
            val contractStr = if (base != null) {
                "$base."
            } else {
                ""
            }

            return "$contractStr$methodId(${args.joinToString(",")})".wrapWithParens()
        }

        override fun eval(): Constant.NumberLit? = null

        override fun hashCode() = hash {
            it + base + methodId + args + tag + twoStateIndex + invokeStorage + invokeIsSafe + invokeIsWhole
        }
    }

    @Serializable
    sealed class ApplyExp : ApplicationExp() {

        /**
         * An ApplyExp which points to TAC Code which can be inlined.
         */
        sealed interface Inlinable

        /**
         * An ApplyExp which can have a @withrevert
         */
        @Treapable
        sealed interface RevertAnnotatable: HasCVLExpTag {
            val noRevert: Boolean
        }

        abstract val methodIdWithCallContext: ResolvedCallableName

        override val callableName: String
            get() = methodIdWithCallContext.methodId

        override fun toString(): String = "${methodIdWithCallContext.printQualifiedFunctionName()}(${args.joinToString(",")})".wrapWithParens()

        override fun eval(): Constant.NumberLit? = null

        /**
         * Returns a string representing this [ApplyExp] as it appears as a call in the CVL file.
         */
        open fun toAppliedString(): String = "$callableName(${args.joinToString(",")})"

        @Serializable
        data class CVLBuiltIn(
            override val args: List<CVLExp>,
            override val tag: CVLExpTag,
            val id: CVLBuiltInName
        ) : ApplyExp() {
            override val methodIdWithCallContext: ResolvedCallableName
                get() = CVLBuiltInFunction(id)

            override fun hashCode(): Int {
                return hash {
                    it + args + tag + id
                }
            }

            override fun toString() = args.joinToString(separator = ",", prefix = "$id(", postfix = ")").wrapWithParens()

        }

        /**
         * Represents an application of a ghost function declared by the user in a CVL file.
         */
        @Serializable
        data class Ghost(
            val id: String,
            override val args: List<CVLExp>,
            override val tag: CVLExpTag,
            val twoStateIndex: TwoStateIndex
        ) : ApplyExp() {
            override val methodIdWithCallContext = SpecDeclaration(id)
            override fun hashCode() = hash { it + id + args + tag + twoStateIndex }
            override fun toString(): String = "$methodIdWithCallContext$twoStateIndex(${args.joinToString(", ")})".wrapWithParens()
        }

        /**
         * Represents an application of a "macro" as defined by the user in a CVL file.
         */
        @Serializable
        data class Definition(
            val id: String,
            override val args: List<CVLExp>,
            override val tag: CVLExpTag
        ) : ApplyExp() {
            override val methodIdWithCallContext = SpecDeclaration(id)
            override fun toString() = super.toString()
        }

        /**
         * Represents an application of a function (formerly "subroutine") defined by the user in a CVL file.
         */
        @Serializable
        data class CVLFunction(
            val id: String,
            override val args: List<CVLExp>,
            override val tag: CVLExpTag,
            override val noRevert: Boolean
        ) : ApplyExp(), Inlinable, RevertAnnotatable {
            override val methodIdWithCallContext = SpecDeclaration(id)
            override fun toString(): String = super.toString() + if (!noRevert) {" could_revert"} else {""}
        }

        /**
         * Represents an application of an EVM function which is part of the contract being analyzed.
         *
         *  Note: wherever this is used (in expressions or [CVLCmd.Simple.Apply]) it will default to a regular invoke
         *        unless annotated with "@safe" or "@whole" or called with "sinvoke" or "invoke_whole"
         */
        @Serializable
        sealed class ContractFunction : ApplyExp(), Inlinable, RevertAnnotatable {
            abstract override val methodIdWithCallContext: ResolvedContractCall
            abstract override val args: List<CVLExp>
            abstract override val noRevert: Boolean
            abstract val storage: CVLExp.VariableExp
            abstract override val tag: CVLExpTag

            internal val MINIMAL_NUM_OF_INVOKE_ARGS = 1
            internal val msg = "Every invocation must have an environment argument"

            internal val NUM_OF_INVOKE_ARGS_FOR_UNKNOWN = 2
            internal val msg2 =
                "Every invocation with unknown function must have 2 arguments: environment, and calldata argument;"


            private fun getInvokeCmdStringWithoutRange(): String =
                "${methodIdWithCallContext.printQualifiedFunctionName()}(${this.args.joinToString(", ")})"

            fun getInvokeCmdString(): String {
                return "${getInvokeCmdStringWithoutRange()} :" + this.tag.range
                    .toString()
            }


            /**
             * An [ApplyExp.ContractFunction.Symbolic] represents an application of a method whose name is considered
             * "dynamic" (i.e. declared as a parametric function "method f; f(e, args);")
             */
            @Serializable
            data class Symbolic(
                override val methodIdWithCallContext: SymbolicContractMethod,
                override val args: List<CVLExp>,
                override val noRevert: Boolean,
                override val storage: CVLExp.VariableExp,
                override val tag: CVLExpTag
            ) : ContractFunction() {
                override fun toString(): String {
                    return (methodIdWithCallContext.printQualifiedFunctionName() +
                            "(${args.joinToString(",")})" +
                            if (!noRevert) " could_revert" else "" +
                                    if (!storage.isLastStorage()) " @$storage" else "").wrapWithParens()
                }
            }

            /**
             * An [ApplyExp.ContractFunction.Concrete] represents an application of a method whose name is statically
             * known (i.e. the application is written with the name of the function as it exists in the underlying
             * contract)
             */
            @Serializable
            data class Concrete(
                override val methodIdWithCallContext: ConcreteContractMethod,
                override val args: List<CVLExp>,
                override val noRevert: Boolean,
                override val storage: CVLExp.VariableExp,
                val isWhole: Boolean,
                override val tag: CVLExpTag
            ) : ContractFunction() {

                override fun toString(): String {
                    return (methodIdWithCallContext.printQualifiedFunctionName() +
                            "(${args.joinToString(",")})" +
                            if (!noRevert) " could_revert" else "" +
                                    if (isWhole) " whole" else "" +
                                            if (!storage.isLastStorage()) " @$storage" else "").wrapWithParens()
                }
            }
        }
    }

    @Serializable
    data class AddressFunctionCallExp(
        val base: CVLExp,
        override val callableName: String,
        override val args: List<CVLExp>,
        val storage: VariableExp,
        val relevantFuncs: List<ContractFunction>,
        override val tag: CVLExpTag
    ) : ApplicationExp() {
        override fun eval(): Constant.NumberLit? = null

        override fun toString(): String {
            return ("$base.$callableName" +
                "(${args.joinToString(",")})" +
                if (!storage.isLastStorage()) { " @$storage" } else { "" }).wrapWithParens()
        }
    }

    /**
     * Collects all sub-expressions of a given expressions that have type [T].
     * Note that this can't be done using simpler generic-type mechanisms tue to type-eraser
     *
     * Iteration order within each subexpression is implementation-defined but we visit subexpressions first,
     * which means iteration is in bottom-up order.
     * e.g. we would visit `foo` before `foo.bar`, and `3` before `3 > 2`.
     */
    inline fun <reified T: CVLExp> subExprsOfType(): List<T> {
        val collector = object : CVLExpFolder<List<T>>() {

            override fun variable(acc: List<T>, exp: CVLExp.VariableExp): List<T> = acc

            override fun expr(acc: List<T>, exp: CVLExp): List<T> {
                return super.expr(acc, exp) + if (exp is T) {
                    listOf(exp)
                } else {
                    listOf()
                }
            }
        }

        return collector.expr(listOf(), this)
    }

    /**
     * represents the subset of [CVLExp] which is allowed in [CVLCmd.Simple.Havoc].
     * this currently includes local CVL variables, represented as [VariableExp],
     * and storage accesses, which are additionally expected to be annotated with [StorageAccessMarker]
     */
    @Serializable
    @Treapable
    sealed interface HavocTarget : AmbiSerializable {
        val asExp: CVLExp get() = when (this) {
            is CVLExp -> this
        }

        companion object {
            fun fromExp(exp: CVLExp): CollectingResult<HavocTarget, CVLError> =
                if (exp is HavocTarget) {
                    exp.lift()
                } else {
                    InvalidHavocTargetType(exp).asError()
                }

            /**
             * safety: caller asserts that the wrapped [CVLExp] is also a [HavocTarget].
             *
             * useful after an upcast, in order to pass the expression to a transformation that takes [CVLExp],
             * where it can be assumed that the transformation would not change the subclass.
             */
            fun <E> CollectingResult<CVLExp, E>.downcastToHavocTarget() = map { it as HavocTarget }
        }
    }

    /**
     * Returns a set of all the variable expressions inside the caller.
     */
    fun getVarsExp(): Set<VariableExp> = this.subExprsOfType<VariableExp>().toSet()

    fun updateType(newType: CVLType) = updateTag(this.tag.updateType(newType))

    fun updateTag(newTag: CVLExpTag) = when (this) {
        is BinaryExp.AddExp -> this.copy(tag = newTag)
        is Constant.BoolLit -> this.copy(tag = newTag)
        is BinaryExp.DivExp -> this.copy(tag = newTag)
        is BinaryExp.ModExp -> this.copy(tag = newTag)
        is BinaryExp.ExponentExp -> this.copy(tag = newTag)
        is RelopExp.EqExp -> this.copy(tag = newTag)
        is Constant.SignatureLiteralExp -> this.copy(tag = newTag)
        is ApplyExp.Ghost -> this.copy(tag = newTag)
        is ApplyExp.Definition -> this.copy(tag = newTag)
        is ApplyExp.ContractFunction.Concrete -> this.copy(tag = newTag)
        is ApplyExp.ContractFunction.Symbolic -> this.copy(tag = newTag)
        is ApplyExp.CVLFunction -> this.copy(tag = newTag)
        is RelopExp.ArithRelopExp.GeExp -> this.copy(tag = newTag)
        is RelopExp.ArithRelopExp.GtExp -> this.copy(tag = newTag)
        is BinaryExp.IffExp -> this.copy(tag = newTag)
        is BinaryExp.ImpliesExp -> this.copy(tag = newTag)
        is BinaryExp.LandExp -> this.copy(tag = newTag)
        is RelopExp.ArithRelopExp.LeExp -> this.copy(tag = newTag)
        is UnaryExp.LNotExp -> this.copy(tag = newTag)
        is RelopExp.ArithRelopExp.LtExp -> this.copy(tag = newTag)
        is BinaryExp.MulExp -> this.copy(tag = newTag)
        is RelopExp.NeExp -> this.copy(tag = newTag)
        is Constant.NumberLit -> this.copy(tag = newTag)
        is Constant.StringLit -> this.copy(tag = newTag)
        is QuantifierExp -> this.copy(tag = newTag)
        is SumExp -> this.copy(tag = newTag)
        is FieldSelectExp -> this.copy(tag = newTag)
        is SetMemExp -> this.copy(tag = newTag)
        is ArrayDerefExp -> this.copy(tag = newTag)
        is Constant.StructLit -> this.copy(tag = newTag)
        is BinaryExp.SubExp -> this.copy(tag = newTag)
        is UnaryExp.UnaryMinusExp -> this.copy(tag = newTag)
        is VariableExp -> this.copy(tag = newTag)
        is BinaryExp.BwOrExp -> this.copy(tag = newTag)
        is BinaryExp.BwXOrExp -> this.copy(tag = newTag)
        is BinaryExp.BwAndExp -> this.copy(tag = newTag)
        is BinaryExp.BwLeftShiftExp -> this.copy(tag = newTag)
        is BinaryExp.BwRightShiftExp -> this.copy(tag = newTag)
        is BinaryExp.BwRightShiftWithZerosExp -> this.copy(tag = newTag)
        is UnaryExp.BwNotExp -> this.copy(tag = newTag)
        is RelopExp -> throw IllegalStateException(
            "this case (`Relop`) should be covered by the other cases, " +
                    "for expression $this"
        )

        is CondExp -> this.copy(tag = newTag)
        is ArrayLitExp -> this.copy(tag = newTag)
        is BinaryExp.LorExp -> this.copy(tag = newTag)
        is CastExpr -> this.copy(tag = newTag)
        is UnresolvedApplyExp -> this.copy(tag = newTag)
        is AddressFunctionCallExp -> this.copy(tag = newTag)
        is Constant.EnumConstant -> this.copy(tag = newTag)
        is ApplyExp.CVLBuiltIn -> this.copy(tag = newTag)
    }

    fun toLhs(): CVLLhs = when (this) {
        is VariableExp -> {
            CVLLhs.Id(this.tag.range, this.id, this.tag)
        }

        is ArrayDerefExp -> {
            CVLLhs.Array(
                tag.range,
                array.toLhs(),
                index,
                tag
            )
        }

        else -> error("cannot convert expression \"$this\" to an lhs")
    }

    /**
     * Checks whether this exp contains a subexpression that may have side effects - for example assert/require-casts,
     * div-by-zero, function calls, etc.
     */
    fun subExpsWithSideEffects(symbolTable: CVLSymbolTable): Set<CVLExp> {
        return object : CVLExpFolder<Set<CVLExp>>() {
            override fun variable(acc: Set<CVLExp>, exp: VariableExp): Set<CVLExp> = acc

            override fun castExpression(acc: Set<CVLExp>, exp: CastExpr): Set<CVLExp> =
                if (exp.castType == CastType.REQUIRE || exp.castType == CastType.ASSERT) {
                    acc + exp
                } else {
                    super.castExpression(acc, exp)
                }

            override fun binary(acc: Set<CVLExp>, exp: BinaryExp): Set<CVLExp> =
                when (exp) {
                    is BinaryExp.DivExp, is BinaryExp.ModExp -> acc + exp
                    else -> super.binary(acc, exp)
                }

            /* Marking all ApplyExp applications as having side-effect,
     except for ghost function applications and ContractFunction which is view or pure  */
            override fun applyExp(acc: Set<CVLExp>, exp: ApplyExp): Set<CVLExp> {
                return when (exp) {
                    is ApplyExp.Ghost -> acc
                    is ApplyExp.CVLBuiltIn -> {
                        when (exp.id) {
                            CVLBuiltInName.SHA256,
                            CVLBuiltInName.KECCAK256 -> acc + exp

                            CVLBuiltInName.ECRECOVER -> acc
                            CVLBuiltInName.FOUNDRY_PRANK,
                            CVLBuiltInName.FOUNDRY_START_PRANK,
                            CVLBuiltInName.FOUNDRY_STOP_PRANK,
                            CVLBuiltInName.FOUNDRY_MOCK_CALL,
                            CVLBuiltInName.FOUNDRY_CLEAR_MOCKED_CALLS,
                            CVLBuiltInName.FOUNDRY_WARP,
                            CVLBuiltInName.FOUNDRY_ROLL,
                            CVLBuiltInName.FOUNDRY_EXPECT_EMIT -> acc + exp
                        }
                    }

                    is ApplyExp.ContractFunction.Concrete -> {
                        acc + exp
                    }

                    is ApplyExp.CVLFunction,
                    is ApplyExp.ContractFunction.Symbolic -> acc + exp

                    is ApplyExp.Definition -> {
                        val cvlDef = symbolTable.lookUpFunctionLikeSymbol(
                            id = exp.id,
                            exp.getScope()
                        )?.symbolValue as? spec.cvlast.CVLDefinition
                            ?: error("couldn't find ${exp.id} in the symbol table")
                        expr(acc, cvlDef.body)
                    }
                }
            }
        }.expr(setOf(), this)
    }
}

/**
 * An annotation attached to [CVLExp.RelopExp] or [StorageComparison] indicating the basis of comparison, and
 * whether skolemization should be used if possible.
 */
@Serializable
data class ComparisonType(
    val basis: ComparisonBasis,
    val trySkolem: Boolean
) : ExpressionAnnotation

/**
 * It is important to distinguish between a [StorageBasis] and a [ComparisonBasis].
 * A [ComparisonBasis] refers to the overall "thing" being compared by a user level assertion,
 * that is:
 * 1. Balances
 * 2. A contract's state,
 * 3. All of storage,
 * 4. a specific ghost
 *
 * A [StorageBasis] is a "subdivision" of the overall state at which comparisons make sense.
 * For example, it makes sense to compare Balances, it makes sense to compare all of one contracts state etc.
 * Note that every storage basis is itself a valid comparison basis.
 *
 * Q: why we can't subdivide contract state down to even finer granularity, e.g., individual (non-indexed) access paths?
 * A: This relies on a 1:1 mapping between logic access paths and the storage variables being compared. In practice, we won't
 * get so lucky, due to the use of equivalence classes in the storage splitting infrastructure, it is not always possible to
 * say exactly which non-indexed path is being accessed for some particular storage wordmap. In the limit, if storage splitting failed,
 * then all you have is the single, monolithic tacS variable, and further subdivision is hard (read: annoying).
 * If support is needed, it's likely possible to state constraints within a storage map to only select
 * those paths that definitely correspond to logical paths of interest. However, for the moment anyway,
 * the comparison infrastructure considers the "storage family" of a contract
 * (as recorded in a [IContractClass.storage])
 * as a single, indivisible unit of comparison.
 */
@Serializable
sealed class ComparisonBasis : ExpressionAnnotation {
    @KSerializable
    data class All(val ghostUniverse: List<StorageBasis.Ghost>) : ComparisonBasis()
}

/**
 * Attached to [StorageBasis] which also support scalars, currently just [StorageBasis.ContractClass]
 * and [StorageBasis.Ghost] (balances never generate scalar comparisons)
 */
sealed interface SupportsScalarComparison

@Serializable
sealed class StorageBasis: ComparisonBasis() {
    /**
     * Those storage bases which compare VM state, that is Balances of the state of contracts
     */
    @Serializable
    sealed class VMStorageBasis : StorageBasis()
    @KSerializable
    object Balances : VMStorageBasis() {
        fun readResolve(): Any = Balances

        override fun toString(): String {
            return CVLKeywords.nativeBalances.keyword
        }

        override fun hashCode(): Int {
            return hashObject(this)
        }
    }
    @KSerializable
    object ExternalCodesizes : VMStorageBasis() {
        fun readResolve(): Any = ExternalCodesizes

        override fun toString(): String {
            return CVLKeywords.nativeCodesize.keyword
        }

        override fun hashCode(): Int {
            return hashObject(this)
        }
    }
    @KSerializable
    data class ContractClass(val canonicalId: SolidityContract) : SupportsScalarComparison, VMStorageBasis() {
        override fun toString(): String {
            return canonicalId.name
        }
    }

    @KSerializable
    data class Ghost(
        val name: String,
        val params: List<CVLType.PureCVLType>,
        val resultType: CVLType.PureCVLType,
        val sort: GhostSort
    ) : StorageBasis(), SupportsScalarComparison {
        override fun hashCode(): Int {
            return hash {
                it + name + params + resultType + sort
            }
        }

        override fun equals(other: Any?): Boolean {
            return other is Ghost && other.name == this.name && other.params == this.params &&
                other.resultType == this.resultType && other.sort == this.sort
        }

        companion object {
            operator fun invoke(it: CVLGhostDeclaration.Function) : Ghost {
                return Ghost(
                    name = it.id,
                    params = it.paramTypes,
                    resultType = it.resultType,
                    sort = GhostSort.Function
                )
            }

            operator fun invoke(it: CVLGhostDeclaration): Ghost {
                return when(it) {
                    is CVLGhostDeclaration.Variable -> Ghost(it)
                    is CVLGhostDeclaration.Function -> Ghost(it)
                    is CVLGhostDeclaration.Sum -> Ghost(it)
                }
            }

            operator fun invoke(it: CVLGhostDeclaration.Variable) : Ghost =
                Ghost(
                    name = it.id,
                    params = it.paramTypes,
                    resultType = it.resultType,
                    sort = if (it.type is CVLType.PureCVLType.Ghost.Mapping) {
                        GhostSort.Mapping
                    } else {
                        GhostSort.Variable
                    }
                )

            operator fun invoke(it: CVLGhostDeclaration.Sum) : Ghost =
                Ghost(
                    name = it.id,
                    params = it.paramTypes,
                    resultType = it.resultType,
                    sort = if (it.paramTypes.isNotEmpty()) {
                        GhostSort.Mapping
                    } else {
                        GhostSort.Variable
                    }
                )
        }
    }
}

// todo remove me
class CVLTODO(msg: String) : Exception(msg)

/** we use this to get non-null descriptions for [CVLCmd.Simple.Assert] and [CVLCmd.Simple.Satisfy] */
private fun defaultAssertDescription(asserted: CVLExp) = CVLReportLabel.Exp(asserted).toString()
