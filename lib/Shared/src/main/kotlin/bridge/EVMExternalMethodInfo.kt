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

package bridge

import com.certora.collect.Treapable
import compiler.SolidityFunctionStateMutability
import compiler.SourceSegment
import datastructures.stdcollections.*
import evm.SighashInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import scene.MethodAttribute
import spec.CVLReservedVariables
import spec.cvlast.*
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.PrintingContext
import utils.BigIntegerSerializer
import utils.*
import java.math.BigInteger

/**
 * Uniquely identifies a method inside a given contract.
 * Contains basic metadata on the method (only information visible in the declaration).
 */
@Serializable
@Treapable
data class EVMExternalMethodInfo(
    val sigHash: BigInteger?,
    val name: String,
    val argTypes: List<EVMTypeDescriptor>,
    val resultTypes: List<EVMTypeDescriptor>,
    val stateMutability: SolidityFunctionStateMutability,
    val isConstant: Boolean,
    val paramNames: List<String> = listOf(),
    val isLibrary: Boolean,
    val contractName: String, // name of the containing contract
    val contractInstanceId: BigInteger, // instance Id of the containing contract
    val sourceSegment: SourceSegment?,
) : java.io.Serializable {

    val params: List<VMParam>
        get() = argTypes.map { VMParam.Unnamed(it, Range.Empty()) }

    val functionName: String
        get() = name

    val wrappedParameterSignature = MethodParameterSignature(functionName, this.params)

    init {
        fun checkSighash(computedSighash: SighashInt) {
            check(MethodAttribute.Unique.isUnique(this.name) ||
                    this.sigHash == computedSighash.n)
            {
                "For method $name, the actual sighash is ${
                    sigHash?.toString(16)
                } vs $computedSighash based off signature ${
                    this.name + this.argTypes.joinToString(",", "(", ")") {
                        it.canonicalString(PrintingContext(isLibrary))
                    }
                }"
            }
        }

        when (this.wrappedParameterSignature) {
            is ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig -> {
                checkSighash(this.wrappedParameterSignature.computeSighash(PrintingContext(this.isLibrary)))
            }
            is ExternalQualifiedMethodParameterSignature.ExternalQualifiedMethodParamSig -> {
                checkSighash(this.wrappedParameterSignature.computeSighash(PrintingContext(this.isLibrary)))
            }

            is MethodParameterSignature.MethodParamSig,
            is MethodSignature.MethodSig,
            is QualifiedMethodParameterSignature.QualifiedMethodParamSig,
            is QualifiedMethodSignature.QualifiedMethodSig,
            is UniqueMethod -> Unit
        }
    }

    fun toCvlStructLit(scope: CVLScope): CVLExp.Constant.StructLit {
        return buildStruct(
            scope = scope,
            isPure = stateMutability.isPure,
            isView = stateMutability.isView,
            isPayable = stateMutability.isPayable,
            isFallback = isFallback,
            selector = sigHash,
            arity = this.argTypes.size.toBigInteger(),
            contractInstanceId = contractInstanceId
        )
    }

    val isFallback get() = (name == CVLReservedVariables.certorafallback_0.name)

    fun getPrettyName(): String = Method.toSignatureName(name, argTypes.map { it.prettyPrint() })

    fun toExternalABIName(): String {
        if (isFallback) { return "${CVLReservedVariables.certoraFallbackDisplayName}()" }
        return wrappedParameterSignature.computeCanonicalSignature(PrintingContext(this.isLibrary))
    }

    fun toExternalABINameWithContract(): String {
        return "${contractName}.${toExternalABIName()}"
    }

    override fun toString(): String = toExternalABIName()

    companion object {
        fun fromMethodAndContract(method: Method, contract: ContractInstanceInSDC): EVMExternalMethodInfo {
            return EVMExternalMethodInfo(
                method.getSigHashInt()?.n,
                method.name,
                method.fullArgs.map { it.toVMTypeDescriptor() },
                method.returns.map { it.toVMTypeDescriptor() },
                method.stateMutability,
                method.isConstant,
                method.paramNames,
                method.isLibrary,
                method.contractName,
                contract.address,
                method.sourceSegment(),
            )
        }

        fun getFallback(primaryContract: ContractInstanceInSDC) =
            fromMethodAndContract(getFallbackMethod(primaryContract.name), primaryContract)

        // in CVL, this is the field name of the matching CVL struct
        const val selectorField = "selector"
        val selectorType = CVLType.PureCVLType.Primitive.UIntK(32)
        // TODO: CERT-5007 eliminate this magic number.  Replace this with a proper "disjoint sighash" expression type?
        val magicFallbackSelector = BigInteger.ZERO
        const val pureField = "isPure"
        const val viewField = "isView"
        const val payableField = "isPayable"
        const val fallbackField = "isFallback"
        const val contractField = "contract"
        val contractType = CVLType.PureCVLType.Primitive.AccountIdentifier
        const val arityField = "numberOfArguments"

        fun buildStruct(
            scope: CVLScope,
            isPure: Boolean,
            isView: Boolean,
            isPayable: Boolean,
            isFallback: Boolean,
            selector: BigInteger?,
            arity: BigInteger,
            contractInstanceId: BigInteger,
            range: Range = Range.Empty()
        ) =
                withScopeAndRange(scope, range = range) {
                    CVLExp.Constant.StructLit(
                            fieldNameToEntry =
                            listOf(
                                    Pair(selectorField, CVLExp.Constant.NumberLit(selector ?: magicFallbackSelector, selectorType.asTag(), "16")),
                                    Pair(pureField, CVLExp.Constant.BoolLit(isPure, CVLType.PureCVLType.Primitive.Bool.asTag())),
                                    Pair(viewField, CVLExp.Constant.BoolLit(isView, CVLType.PureCVLType.Primitive.Bool.asTag())),
                                    Pair(payableField, CVLExp.Constant.BoolLit(isPayable, CVLType.PureCVLType.Primitive.Bool.asTag())),
                                    Pair(fallbackField, CVLExp.Constant.BoolLit(isFallback, CVLType.PureCVLType.Primitive.Bool.asTag())),
                                    Pair(arityField, CVLExp.Constant.NumberLit(arity, CVLType.PureCVLType.Primitive.UIntK(256).asTag())),
                                    Pair(contractField, CVLExp.Constant.NumberLit(contractInstanceId, contractType.asTag(), "16"))
                            ).toMap(),
                            tag = EVMBuiltinTypes.method.asTag())
                }
    }

    override fun hashCode(): Int = hash { it + params }

}
