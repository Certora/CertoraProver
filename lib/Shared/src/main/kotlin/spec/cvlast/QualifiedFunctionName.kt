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

import com.certora.collect.*
import compiler.calculateHashFromCanonicalName
import datastructures.stdcollections.*
import evm.SighashInt
import scene.MethodAttribute
import spec.cvlast.typedescriptors.PrintingContext
import spec.cvlast.typedescriptors.VMTypeDescriptor
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.bindEither
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok


/**
 * Identifies the name of a callable item, these can be:
 * + ghost functions
 * + contract functions
 * + cvl functions
 *
 * This is not unambiguous w.r.t. overloading.
 *
 * [methodId] the name (without signature information) of the callable
 * [host] Where this callable is declared. If this is a callable defined in spec, will be [CvlSpecFile],
 * otherwise it will be an instance of [SolidityContract]
 */
@KSerializable
@Treapable
sealed interface CallableName : AmbiSerializable {
    val methodId: String
    val host: FunctionDeclarationScope
}

/**
 * Function names that provide enough information to uniquely identifiy their callee
 */
@KSerializable
sealed interface ResolvedCallableName : CallableName

/**
 * Identifies a contract function modulo overloading. In other words, [host] is narrowed to be an instance of [SolidityContract]
 */
@KSerializable
sealed interface ContractFunctionIdentifier : CallableName  {
    override val host: ContractReference
}

/**
 * A callable name that is fully "resolved", i.e., the compiler should be able to codegen for it
 */
@KSerializable
sealed interface ResolvedContractCall : ContractFunctionIdentifier, ResolvedCallableName

@Suppress("unused")
fun ContractFunctionIdentifier.isConstructor() =
    (this is UniqueMethod) && (this.attribute == MethodAttribute.Unique.Constructor)

/**
 * An instance of [ContractFunctionIdentifier] for "regular" methods, those declared with names and which are not constructors
 */
@KSerializable
data class QualifiedFunction(
    override val host: SolidityContract,
    override val methodId: String
) : ContractFunctionIdentifier {
    override fun toString(): String = "$host.$methodId"
}

/**
 * A concrete method which is uniquely identified (and thus is a [ResolvedContractCall] via [ConcreteContractMethod].
 *
 * For convenience, implements the [QualifiedMethodSignature] interface, so as to provide return/param information (always the
 * empty lists)
 *
 * TODO(jtoman): does this need to be a method signature???
 */
@KSerializable
data class UniqueMethod(
    override val host: SolidityContract,
    val attribute: MethodAttribute.Unique
) : ConcreteContractMethod, QualifiedMethodSignature {
    override fun withParameters(newParams: List<VMParam>): QualifiedMethodSignature {
        throw UnsupportedOperationException("Cannot set parameters for unique methods")
    }

    override val qualifiedMethodName: ContractFunctionIdentifier
        get() = this
    override val methodId: String get() = attribute.methodId

    override val functionName: String
        get() = this.methodId

    override val params: List<VMParam>
        get() = listOf()
    override val res: List<VMParam>
        get() = listOf()

    override fun toString(): String {
        return "$host.${attribute.methodId}"
    }
}

/**
 * A concrete, fully resolved contract function as identified by the sighash represented in [signature].
 *
 * Functions as a contract function identifier via the [ExternalQualifiedMethodParameterSignature.functionName]
 */
@KSerializable
data class ConcreteMethod(
    val signature: ExternalQualifiedMethodParameterSignature
) : ConcreteContractMethod, ContractFunctionIdentifier by signature.qualifiedMethodName {
    override fun toString() = "$signature"
}

/**
 * Identifies a contract function call that resolves to *exactly* one function
 */
@KSerializable
sealed interface ConcreteContractMethod : ResolvedContractCall

/**
 * Resolves a contract call to some symbolic method (but for which the compiler has all the information it needs)
 */
@KSerializable
sealed interface SymbolicContractMethod : ResolvedContractCall

/**
 * A symbolic method call, where the function being called is any function with the name [methodId]
 * in [host]
 */
@KSerializable
data class Overloading(
    override val methodId: String,
    override val host: SolidityContract
) : SymbolicContractMethod {
    override fun toString(): String = "$host.$methodId"
}

/**
 * A symbolic method call, where the function being called is any instantiation of the method parameter [methodId].
 *
 * [methodId] is expected to be instantiated with methods in the contract [host]. If [host] is "_" then it will
 * instantiate all methods from all contracts
 */
@KSerializable
data class ParametricMethod(
    override val methodId: String,
    override val host: ContractReference
) : SymbolicContractMethod {
    override fun toString() = if (host is AllContracts) {
        methodId
    } else {
        "$host.$methodId"
    }
}

@KSerializable
data class CVLBuiltInFunction(
    val bif: CVLBuiltInName
) : ResolvedCallableName {
    override val methodId: String
        get() = bif.bifName
    override val host: FunctionDeclarationScope
        get() = CvlBuiltIn

    override fun toString(): String = methodId

    override fun hashCode(): Int = hash { it + bif }
}

/**
 * A callable entity that lives in spec.
 */
@KSerializable
@Treapable
data class SpecDeclaration(
    override val methodId: String
) : ResolvedCallableName {
    override val host: FunctionDeclarationScope
        get() = CvlSpecFile

    override fun toString(): String = methodId
}


// Why are the following extension functions and not interface functions?
// 1. No overriding!
// 2. Per-class logic all in one place, no confusing dispatching
fun MethodParameterSignature.printMethodParameterSignature() = functionName + params.joinToString(", ", "(", ")") {
    it.prettyPrint()
}

fun MethodParameterSignature.printQualifiedMethodParameterSignature(): String {
    fun printQualified(sig: QualifiedMethodParameterSignature): String = "${sig.qualifiedMethodName.host.name}.${printMethodParameterSignature()}"
    return when (this) {
        is MethodParameterSignature.MethodParamSig,
        is MethodSignature.MethodSig,
        is UniqueMethod -> printMethodParameterSignature()
        // kotlin isn't that smart...
        is ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig -> printQualified(this)
        is QualifiedMethodSignature.QualifiedMethodSig -> printQualified(this)
        is ExternalQualifiedMethodParameterSignature.ExternalQualifiedMethodParamSig -> printQualified(this)
        is QualifiedMethodParameterSignature.QualifiedMethodParamSig -> printQualified(this)
    }
}

fun MethodParameterSignature.printMethodSignature(): String {
    fun printWithReturns(sig: MethodSignature): String = printMethodParameterSignature() + if (sig.resType.isEmpty()) {
        ""
    } else {
        sig.res.joinToString(
            separator = ", ",
            prefix = " returns (",
            postfix = ")",
            transform = spec.cvlast.VMParam::prettyPrint
        )
    }
    return when (this) {
        is MethodParameterSignature.MethodParamSig -> printMethodParameterSignature()
        is ExternalQualifiedMethodParameterSignature.ExternalQualifiedMethodParamSig -> printMethodParameterSignature()
        is QualifiedMethodParameterSignature.QualifiedMethodParamSig -> printMethodParameterSignature()
        // kotlin isn't that smart...
        is MethodSignature.MethodSig -> printWithReturns(this)
        is UniqueMethod -> printWithReturns(this)
        is ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig -> printWithReturns(this)
        is QualifiedMethodSignature.QualifiedMethodSig -> printWithReturns(this)
    }
}

fun MethodParameterSignature.printQualifiedMethodSignature(): String {
    fun printQualified(sig: QualifiedMethodParameterSignature): String = "${sig.qualifiedMethodName.host.name}.${printMethodSignature()}"
    return when (this) {
        is MethodParameterSignature.MethodParamSig,
        is MethodSignature.MethodSig,
        is UniqueMethod -> printMethodParameterSignature()
        // kotlin isn't that smart...
        is ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig -> printQualified(this)
        is QualifiedMethodSignature.QualifiedMethodSig -> printQualified(this)
        is ExternalQualifiedMethodParameterSignature.ExternalQualifiedMethodParamSig -> printQualified(this)
        is QualifiedMethodParameterSignature.QualifiedMethodParamSig -> printQualified(this)
    }
}

fun CallableName.printQualifiedFunctionName(): String = when (val host = this.host) {
    CvlSpecFile -> this.methodId
    is SolidityContract -> "${host.name}.${this.methodId}"
    AllContracts -> this.methodId
    CurrentContract -> this.methodId
    CvlBuiltIn -> this.methodId
}



/**
 * A description of a list of parameters. Combined with a [ContractFunctionIdentifier] this is sufficient to disambiguate
 * a method w.r.t. overloading.
 */
@KSerializable
@Treapable
sealed interface MethodParameterSignature : AmbiSerializable {

    val functionName: String

    val params: List<VMParam>
    val paramTypes: List<VMTypeDescriptor>
        get() = params.map { it.vmType }

    fun paramListString(): String = params.joinToString(",")

    override fun toString(): String

    fun withParameters(newParams: List<VMParam>): MethodParameterSignature

    fun computeCanonicalSignature(printingContext: PrintingContext): String {
        return this.functionName + this.paramTypes.joinToString(",", "(", ")") {
            it.canonicalString(printingContext)
        }
    }

    fun matchesName(other: MethodParameterSignature): Boolean = this.functionName == other.functionName

    fun matchesName(other: String): Boolean = this.functionName == other

    fun matchesParams(other: MethodParameterSignature): Boolean {
        return MergeableSignature.mergeableParameters(this, other).resultOrNull() != null
    }

    fun matchesNameAndParams(other: MethodParameterSignature) = matchesName(other) && matchesParams(other)


    companion object {
        @JvmStatic
        operator fun invoke(functionName: String, params: List<VMParam>): MethodParameterSignature =
            MethodParamSig(functionName, params)
    }

    @KSerializable
    data class MethodParamSig(override val functionName: String, override val params: List<VMParam>) :
        MethodParameterSignature {
        override fun withParameters(newParams: List<VMParam>): MethodParamSig =
            MethodParamSig(functionName, newParams)

        override fun toString(): String = printMethodParameterSignature()
    }
}

/**
 * A [MethodParameterSignature] that additionally has return value information.
 */
@KSerializable
sealed interface MethodSignature : MethodParameterSignature {
    val res: List<VMParam>
    val resType: List<VMTypeDescriptor>
        get() = res.map { it.vmType }

    @KSerializable
    data class MethodSig(
        override val functionName: String,
        override val params: List<VMParam>,
        override val res: List<VMParam>
    ) : MethodSignature {
        override fun withParameters(newParams: List<VMParam>): MethodSig =
            MethodSig(functionName, newParams, res)

        override fun toString(): String = printMethodSignature()
    }

    companion object {
        @JvmStatic
        operator fun invoke(
            functionName: String, params: List<VMParam>,
            res: List<VMParam>
        ): MethodSignature = MethodSig(functionName, params, res)
    }
}

/**
 * A [MethodParameterSignature] that includes a method name of type [ContractFunctionIdentifier]
 */
@KSerializable
sealed interface QualifiedMethodParameterSignature : MethodParameterSignature {
    val qualifiedMethodName: ContractFunctionIdentifier
    override val functionName: String
        get() = qualifiedMethodName.methodId

    override fun withParameters(newParams: List<VMParam>): QualifiedMethodParameterSignature

    /**
     * Pretty printing of a [QualifiedMethodParameterSignature] -- omits names of params (if there are any)
     *  e.g. foo(uint) instead of foo(uint x).
     *
     *  TODO(jtoman): this is probably still wrong, because this doesn't yield an actually correct canonical name
     */
    fun prettyPrint(): String =
        this.qualifiedMethodName.methodId +
            this.params.joinToString(",", "(", ")") {
                it.vmType.prettyPrint()
            }

    fun prettyPrintFullyQualifiedName(): String =
        this.qualifiedMethodName.host.name + "." + prettyPrint()


    fun computeCanonicalSignatureWithContract(printingContext: PrintingContext) =
        this.qualifiedMethodName.host.name + "." + this.computeCanonicalSignature(printingContext)

    fun matchesContractNameAndMethodName(other: ContractFunctionIdentifier) =
        matchesNameAndContractName(other.methodId, other.host.name)

    fun matchesContractAndParams(other: QualifiedMethodParameterSignature): Boolean {
        return matchesContractNameAndMethodName(other.qualifiedMethodName) &&
            (this as MethodParameterSignature).matchesParams(other)
    }

    fun matchesNameAndContractName(otherName: String, otherContractName: String): Boolean =
        qualifiedMethodName.host.name == otherContractName && matchesName(otherName)

    @KSerializable
    data class QualifiedMethodParamSig(
        override val qualifiedMethodName: ContractFunctionIdentifier,
        override val params: List<VMParam>
    ) : QualifiedMethodParameterSignature {
        override fun withParameters(newParams: List<VMParam>): QualifiedMethodParamSig =
            QualifiedMethodParamSig(qualifiedMethodName, newParams)

        override fun toString(): String = this.printQualifiedMethodParameterSignature()
    }

    companion object {
        @JvmStatic
        operator fun invoke(
            contractId: ContractFunctionIdentifier,
            params: List<VMParam>
        ): QualifiedMethodParameterSignature = QualifiedMethodParamSig(contractId, params)
    }

}

@KSerializable
sealed interface QualifiedMethodSignature : MethodSignature, QualifiedMethodParameterSignature {
    override val qualifiedMethodName: ContractFunctionIdentifier
    override val functionName: String
        get() = qualifiedMethodName.methodId

    override fun withParameters(newParams: List<VMParam>): QualifiedMethodSignature

    fun toNamedDecSignature(): String = qualifiedMethodName.methodId + params.joinToString(", ", "(", ")") {
        it.prettyPrint()
    }

    @KSerializable
    data class QualifiedMethodSig(
        override val qualifiedMethodName: ContractFunctionIdentifier,
        override val params: List<VMParam>,
        override val res: List<VMParam>
    ) : QualifiedMethodSignature {
        override fun withParameters(newParams: List<VMParam>): QualifiedMethodSig =
            QualifiedMethodSig(qualifiedMethodName, newParams, res)

        override fun toString(): String = printQualifiedMethodSignature()
    }

    companion object {
        @JvmStatic
        operator fun invoke(
            contractId: ContractFunctionIdentifier,
            params: List<VMParam>,
            res: List<VMParam>
        ): QualifiedMethodSignature = QualifiedMethodSig(contractId, params, res)
    }
}

/**
 * A signature that is known to be for an external function, and which has a sighash
 */
@KSerializable
@Treapable
sealed interface ExternalSignature: AmbiSerializable {
    val sighashInt: SighashInt?

    companion object {
        @Suppress("DEPRECATION")
        fun computeSigHash(isLibrary: Boolean, signature: MethodParameterSignature): SighashInt {
            val ctxt = PrintingContext(isLibrary)
            val sig = computeCanonicalSignature(ctxt, signature)
            return SighashInt(calculateHashFromCanonicalName(sig).toBigInteger(16))
        }


        private fun computeCanonicalSignature(printingContext: PrintingContext, signature: MethodParameterSignature): String {
            return signature.functionName + signature.paramTypes.joinToString(",", "(", ")") {
                it.canonicalString(printingContext)
            }
        }
    }
}

/**
 * A [QualifiedMethodParameterSignature] which is an [ExternalSignature], i.e., it has a sighash
 */
@KSerializable
sealed interface ExternalQualifiedMethodParameterSignature : QualifiedMethodParameterSignature, ExternalSignature {
    override fun withParameters(newParams: List<VMParam>): ExternalQualifiedMethodParameterSignature

    @Suppress("DEPRECATION")
    fun computeSighash(printingContext: PrintingContext): SighashInt {
        val sig = computeCanonicalSignature(printingContext)
        return SighashInt(calculateHashFromCanonicalName(sig).toBigInteger(16))
    }

    fun matchesSigHash(other: ExternalQualifiedMethodParameterSignature): Boolean {
        return this.sighashInt == other.sighashInt
    }

    companion object {
        @JvmStatic
        operator fun invoke(
            contractId: ContractFunctionIdentifier,
            params: List<VMParam>,
            sighashInt: SighashInt?
        ): ExternalQualifiedMethodParameterSignature = ExternalQualifiedMethodParamSig(contractId, params, sighashInt)

        @Suppress("DEPRECATION")
        private fun computeSighash(printingContext: PrintingContext, toPromote: QualifiedMethodParameterSignature): SighashInt {
            val sig = toPromote.computeCanonicalSignature(printingContext)
            return SighashInt(calculateHashFromCanonicalName(sig).toBigInteger(16))
        }
        fun fromNamedParameterSignatureContractId(
            toPromote: QualifiedMethodParameterSignature,
            ctxt: PrintingContext
        ): ExternalQualifiedMethodParameterSignature {
            return ExternalQualifiedMethodParamSig(
                toPromote.qualifiedMethodName,
                toPromote.params,
                computeSighash(ctxt, toPromote)
            )
        }
    }

    /**
     * A [QualifiedMethodParameterSignature] which is an [ExternalSignature], i.e., it has a sighash
     */
    @KSerializable
    data class ExternalQualifiedMethodParamSig(
        override val qualifiedMethodName: ContractFunctionIdentifier,
        override val params: List<VMParam>,
        override val sighashInt: SighashInt?
    ) : ExternalQualifiedMethodParameterSignature {

        override fun withParameters(newParams: List<VMParam>): ExternalQualifiedMethodParameterSignature =
            ExternalQualifiedMethodParamSig(qualifiedMethodName, newParams, sighashInt)

        override fun toString(): String = printQualifiedMethodParameterSignature()

        /**
         * Canonical representation of a [ExternalQualifiedMethodParameterSignature] -- omits names of params (if there are any)
         *  e.g. foo(uint) instead of foo(uint x).
         *
         *  TODO(jtoman): this is probably still wrong, because this doesn't yield an actually correct canonical name
         */
        override fun prettyPrint(): String =
            qualifiedMethodName.methodId + params.joinToString(",", "(", ")") {
                it.vmType.prettyPrint()
            }
    }
}

/**
 * A [QualifiedMethodSignature] which is also an [ExternalQualifiedMethodParameterSignature], i.e., it has a sighash
 */
@KSerializable
sealed interface ExternalQualifiedMethodSignature : ExternalQualifiedMethodParameterSignature, QualifiedMethodSignature {
    override fun withParameters(newParams: List<VMParam>): ExternalQualifiedMethodSignature
    override val functionName: String
        get() = qualifiedMethodName.methodId

    companion object {
        operator fun invoke(
            wrapped: QualifiedMethodSignature,
            sighashInt: SighashInt?
        ): ExternalQualifiedMethodSignature = ExternalQualifiedMethodSig(wrapped, sighashInt)

        operator fun invoke(
            contractId: ContractFunctionIdentifier,
            params: List<VMParam>,
            res: List<VMParam>,
            sighashInt: SighashInt?
        ): ExternalQualifiedMethodSignature = ExternalQualifiedMethodSig(contractId, params, res, sighashInt)
    }


    /**
     * A [QualifiedMethodSignature] which is also an [ExternalQualifiedMethodParameterSignature], i.e., it has a sighash
     */
    @KSerializable
    data class ExternalQualifiedMethodSig(
        private val wrapped: QualifiedMethodSignature, override val sighashInt: SighashInt?
    ) : ExternalQualifiedMethodSignature {
        constructor(
            contractId: ContractFunctionIdentifier,
            params: List<VMParam>,
            res: List<VMParam>,
            sighashInt: SighashInt?
        ) : this(QualifiedMethodSignature(contractId, params, res), sighashInt)

        override fun withParameters(newParams: List<VMParam>): ExternalQualifiedMethodSig {
            return ExternalQualifiedMethodSig(wrapped.withParameters(newParams), sighashInt)
        }

        override val functionName: String
            get() = wrapped.functionName

        override val qualifiedMethodName: ContractFunctionIdentifier
            get() = wrapped.qualifiedMethodName
        override val params: List<VMParam>
            get() = wrapped.params

        override fun toString(): String {
            return wrapped.toString()
        }

        override val res: List<VMParam>
            get() = wrapped.res

        override fun prettyPrint(): String = qualifiedMethodName.methodId +
            params.joinToString(",", "(", ")") {
                it.vmType.prettyPrint()
            }
    }
}


object MergeableSignature {

    private fun mergeableVMParamSizes(
        p1List: List<VMParam>, p2List: List<VMParam>
    ) : CollectingResult<Unit, String> {
        if(p1List.size != p2List.size) {
            return "Different arities (${p1List.size} vs ${p2List.size})".asError()
        }
        return ok
    }

    private fun mergeableVMParams(
        p1List: List<VMParam>, p2List: List<VMParam>
    ) : CollectingResult<Unit, String> {
        return mergeableVMParamSizes(p1List, p2List).bind {
            p1List.zip(p2List) { p1, p2 ->
                p1.vmType.mergeWith(p2.vmType)
            }
                .flatten()
                .bind { ok }
        }
    }

    private fun mergeVMParams(
        p1List: List<VMParam>, p2List: List<VMParam>
    ) : CollectingResult<List<VMParam>, String> {
        return mergeableVMParamSizes(p1List, p2List).bind {
            p1List.zip(p2List) { p1, p2 ->
                val result = p1.vmType.mergeWith(p2.vmType)
                val paramMismatchMessage = "Conflicting parameter names".asError()
                when (p1) {
                    is VMParam.Named -> {
                        when (p2) {
                            is VMParam.Named ->
                                result.bind {
                                    if (p2.name != p1.name) {
                                        paramMismatchMessage
                                    } else {
                                        VMParam.Named(name = p1.name, vmType = it, p1.range).lift()
                                    }
                                }

                            is VMParam.Unnamed -> result.map {
                                p1.copy(vmType = it)
                            }
                        }
                    }

                    is VMParam.Unnamed -> {
                        when (p2) {
                            is VMParam.Named -> result.map {
                                p2.copy(vmType = it)
                            }

                            is VMParam.Unnamed -> result.map {
                                p2.copy(vmType = it)
                            }
                        }
                    }
                }
            }.flatten()
        }
    }

    fun mergeableParameters(m1: MethodParameterSignature, m2: MethodParameterSignature): CollectingResult<Unit, String> {
        return mergeableVMParams(m1.params, m2.params)
    }

    fun mergeParameters(m1: MethodParameterSignature, m2: MethodParameterSignature) : CollectingResult<List<VMParam>, String> {
        val p1 = m1.params
        val p2 = m2.params
        return mergeVMParams(p1, p2).bindEither(
            resultCallback = { it.lift() },
            errorCallback = { errs ->
                "Cannot merge \"$m1\" and \"$m2\" - they have incompatible parameters: ${errs.joinToString()}".asError()
            }
        )
    }

    fun mergeReturns(m1: MethodSignature, m2: MethodSignature) : CollectingResult<List<VMParam>, String> {
        // Special case for internal functions: if one signature has no return types (from bytecode)
        // and the other has return types (from CVL), use the one with return types
        if (m1.res.isEmpty() && m2.res.isNotEmpty()) {
            // m1 (bytecode) has no return type info, trust m2 (CVL)
            return m2.res.lift()
        } else if (m2.res.isEmpty() && m1.res.isNotEmpty()) {
            // m2 (bytecode) has no return type info, trust m1 (CVL)
            return m1.res.lift()
        }

        return mergeVMParams(m1.res, m2.res).bindEither(
            resultCallback = { it.lift() },
            errorCallback = { errs ->
                "Cannot merge \"$m1\" and \"$m2\" - they have incompatible return values: ${errs.joinToString()}".asError()
            }
        )

    }
}
