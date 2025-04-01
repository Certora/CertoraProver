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

package bridge

import bridge.types.SolidityTypeInstance
import com.certora.collect.*
import compiler.SolidityFunctionStateMutability
import compiler.SourceSegment
import evm.SighashInt
import kotlinx.serialization.Serializable
import scene.MethodAttribute
import spec.cvlast.*
import spec.cvlast.typedescriptors.PrintingContext
import spec.cvlast.typedescriptors.VMTypeDescriptor
import utils.CertoraFileCache
import utils.Range
import utils.hash
import utils.prependSpaceIfNotBlank


fun String?.getSigHashInt(): SighashInt? =
    if (this.isNullOrEmpty()) { null } else { SighashInt(this.toBigInteger(16)) }

@Serializable
@Treapable
data class Method(
    val name: String,
    val fullArgs: List<SolidityTypeInstance>,
    val returns: List<SolidityTypeInstance>,
    val sighash: String? = null,
    val notpayable: Boolean = false,
    val isEnvFree: Boolean = false,
    val isConstant: Boolean = false,
    val stateMutability: SolidityFunctionStateMutability =
        SolidityFunctionStateMutability.Default,
    /* Whether [this.sighash] was computed from a signature different from that returned by
    * [this.getCanonicalName()]. This may occur, e.g., if [this] is a Solidity library method.
    */
    val visibility: MethodVisibility? = null, // TODO: make this an enum, no strings from solidity should propagate into the tool
    val paramNames: List<String> = listOf(),
    val isLibrary: Boolean,
    val contractName: String,
    val sourceBytes: SourceBytes? = null,
    private val originalFile: String? = null,
): java.io.Serializable {

    override fun hashCode() = hash {
        it + name + fullArgs + returns + sighash + notpayable + isEnvFree + isConstant + stateMutability + visibility +
        paramNames + isLibrary + sourceBytes + originalFile
    }

    enum class MethodVisibility {
        public,
        private,
        external,
        internal
    }

    /**
     * Extracts returned values's types, and the parameters's values and types of `this`.
     * Returns a named method signature containing this info and the method reference
     * with respect to [contract].
     */
    fun toMethodSignature(contract: SolidityContract, visibility: Visibility): QualifiedMethodSignature {
        val returnTypesAsParams = returns.map { VMParam.Unnamed(it.toVMTypeDescriptor(), Range.Empty()) }


        val ins = fullArgs.map { arg ->
            arg.toVMTypeDescriptor()
        }

        return toMethodSignature(
            contract = contract,
            returnTypes = returnTypesAsParams,
            paramTypes = ins,
            visibility
        )
    }


    private fun toMethodSignature(
        contract: SolidityContract,
        returnTypes: List<VMParam.Unnamed>,
        paramTypes: List<VMTypeDescriptor>,
        visibility: Visibility
    ): QualifiedMethodSignature {
        val params = if (paramNames.isNotEmpty()) {
            if (paramTypes.size != paramNames.size) {
                throw IllegalStateException(
                    "Expected the parameter types ($paramTypes) to match the parameter names ($paramNames)"
                )
            }
            paramTypes.zip(paramNames) { pTyp, pNm ->
                VMParam.Named(pNm, pTyp, Range.Empty())
            }
        } else {
            paramTypes.map { VMParam.Unnamed(it, Range.Empty()) }
        }

        val inst = QualifiedMethodSignature(QualifiedFunction(contract, this@Method.name), params, returnTypes)
        return if(visibility == Visibility.INTERNAL) {
            check(this.visibility == null || this.visibility == MethodVisibility.public || this.visibility == MethodVisibility.private || this.visibility == MethodVisibility.internal)
            inst
        } else {
            check(this.visibility == MethodVisibility.external || this.visibility == null || this.visibility == MethodVisibility.public)

            ExternalQualifiedMethodSignature(inst, getSigHashInt())
        }
    }

    /**
     * Returns a contract function from the method's block in the cvl, based on the
     * contract identifier [contract].
     */
    fun toContractFunction(contract: SolidityContract, expectedVisibility: Visibility, contractInstance: ContractInstanceInSDC): ContractFunction = ContractFunction(
        methodSignature = if(name == MethodAttribute.Unique.Constructor.getIdentifier(contractInstance.lang)) {
            UniqueMethod(contract, MethodAttribute.Unique.Constructor)
        } else {
            toMethodSignature(contract, expectedVisibility)
        },
        notpayable,
        stateMutability = stateMutability,
        evmExternalMethodInfo = if (expectedVisibility == Visibility.EXTERNAL) {
            EVMExternalMethodInfo.fromMethodAndContract(this, contractInstance)
        } else {
            null
        },
        annotation = MethodQualifiers(
            envFree = isEnvFree,
            library = isLibrary,
            visibility = expectedVisibility,
            virtual = false
        )
    )

    fun getSigHashInt(): SighashInt? = sighash.getSigHashInt()

    fun returnsString() = if (returns.isNotEmpty()) {
        "returns (${
            returns.joinToString(",") { it.toVMTypeDescriptor().prettyPrint() }
        })"
    } else {
        ""
    }

    override fun toString(): String {
        return toSignatureName(name, fullArgs.map { it.toVMTypeDescriptor().prettyPrint() }) +
            returnsString().prependSpaceIfNotBlank() +
                " /*(${sighash})*/ " +
                if (!notpayable) " /*potentially_payable*/ " else "" +
                        if (isEnvFree) " envfree" else ""
    }

    fun getABIString(): String =
        toMethodSignature(SolidityContract(contractName), Visibility.EXTERNAL)
            .computeCanonicalSignature(PrintingContext(isLibrary))

    fun getABIStringWithContract(): String {
        return contractName + "." + getABIString()
    }

    fun getPrettyName(): String = toSignatureName(name, this.fullArgs.map { it.toVMTypeDescriptor().prettyPrint() })

    fun sourceSegment(): SourceSegment? {
        val sourceBytes = this.sourceBytes ?: return null
        val relativeFile = this.originalFile ?: return null
        val sourceFile = CertoraFileCache.certoraSourcesDir().resolve(relativeFile)
        return SourceSegment(sourceFile, sourceBytes.begin, sourceBytes.size, relativeFile)
    }

    companion object {
        fun toSignatureName(name: String, argTypes: List<String>): String {
            return "${name}(${argTypes.joinToString(",")})"
        }
    }
}

/**
 * Specify a method within a declared contract. Because we do not reason about inheritance, we use the [canonicalId]
 * as a unique identifier to distinguish between contracts which have potentially the same name.
 *
 * @property canonicalId a string which uniquely identifies a declared contract
 *
 * @property declaringContract the contract where this method was declared (could be "distinct" from the callee
 * due to inheritance
 *
 * @property method information about the method's name, arguments etc.
 */
@Serializable
@Treapable
data class MethodInContract(val canonicalId: String, val declaringContract: String, val method: Method)
    : java.io.Serializable {
    override fun hashCode(): Int = hash { it + canonicalId + declaringContract + method }
}
