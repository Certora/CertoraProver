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

import bridge.SourceLanguage
import config.Config
import evm.EVM_WORD_SIZE
import spec.cvlast.*
import spec.cvlast.typedescriptors.*
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok
import utils.CompilerVersion
import utils.Range
import utils.VoidResult
import java.math.BigInteger

class CVLHookTypeChecker(
    private val symbolTable: CVLSymbolTable,
) {
    private fun checkOpcodeHookParam(paramType: VMTypeDescriptor, targetCVLType: CVLType.PureCVLType, name: String, range: Range, hookPattern: CVLHookPattern.Opcode): VoidResult<CVLError> {
        return if (!FromVMContext.HookValue.supportsConversion(from = paramType, to = targetCVLType).isResult()) {
            return CVLError.General(
                range,
                "${hookPattern.name} hook argument $name must be $targetCVLType, instead got $paramType at ${range}"
            ).asError()
        } else {
            ok
        }
    }

    fun typeCheckHook(hook: CVLHook): CollectingResult<CVLHook, CVLError> {

        val hookPattern = when (hook.pattern) {
            is GeneratedOpcodeHook -> GeneratedHookHelpers.typeCheckPattern(
                range = hook.range,
                pattern = hook.pattern
            ) { declaredDesc: VMTypeDescriptor, expectedType: CVLType.PureCVLType, name: String, opcode: CVLHookPattern.Opcode ->
                checkOpcodeHookParam(declaredDesc, expectedType, name, hook.range, opcode)
            }
            is CVLHookPattern.StoragePattern -> typeCheckSlotPattern(hook)
            is CVLHookPattern.Create -> {
                val paramType = hook.pattern.value.type
                if (!FromVMContext.HookValue.supportsConversion(from = paramType, to = CVLType.PureCVLType.Primitive.AccountIdentifier).isResult()) {
                    return CVLError.General(
                        hook.range,
                        "Create hook argument must be an address, instead got $paramType at ${hook.range}"
                        ).asError()
                } else {
                    hook.pattern.lift()
                }
            }
        }

        val block = CVLCmdTypeChecker(symbolTable).typeCheckCmds(hook.block)
        return block.bind(hookPattern) { blk, pattern -> hook.copy(block = blk, pattern = pattern).lift() }
    }

    private fun hookTypesMatch(
        declaredType: VMTypeDescriptor,
        compilerType: VMTypeDescriptor
    ) : Boolean {
        return declaredType == compilerType ||
            // evm specific
            (compilerType is EVMTypeDescriptor.EVMContractTypeDescriptor && declaredType is EVMTypeDescriptor.address)
    }


    private fun typeCheckSlotPattern(hook: CVLHook): CollectingResult<CVLHookPattern, CVLError> {
        if (hook.pattern !is CVLHookPattern.StoragePattern) {
            throw IllegalStateException("Only hooks with StoragePattern hook should have their pattern type checked, got $hook")
        }


        val slot = hook.pattern.slot
        fun ((VMTypeDescriptor) -> VoidResult<CVLError>)?.extend(
            k: (VMTypeDescriptor, (VMTypeDescriptor) -> VoidResult<CVLError>) -> VoidResult<CVLError>
        ): ((VMTypeDescriptor) -> VoidResult<CVLError>)? {
            return this?.let { c ->
                { typeDescriptor: VMTypeDescriptor ->
                    k(typeDescriptor, c)
                }
            }
        }

        val offsetFieldErrorMsg = "Cannot mix named fields and raw offsets in hook patterns"
        val unalignedDynamicErrorMsg =
            "hook pattern $slot contains a non-word aligned access to a slot that has dynamic " +
                    "children: this is not allowed"

        fun VMTypeDescriptor.toErrorString() = this.prettyPrint()

        fun getContractSourceLanguage(contractScope: CVLScope?): SourceLanguage? {
            return contractScope?.enclosingContract()?.contract?.lang
        }

        fun getContractCompilerVersionStr(contractScope: CVLScope?): String? {
            return contractScope?.enclosingContract()?.contract?.compilerVersion
        }


        tailrec fun typeCheckPattern(
            pattern: CVLSlotPattern,
            seenDynamicType: Boolean,
            seenFields: Boolean,
            checkCont: ((VMTypeDescriptor) -> VoidResult<CVLError>)?
        ): VoidResult<CVLError> {
            return when (pattern) {
                is CVLSlotPattern.ArrayAccess -> {
                    typeCheckPattern(pattern.base, true, seenFields, checkCont.extend { tacStorageType, nxt ->
                        when (tacStorageType) {
                            is VMArrayTypeDescriptor -> {
                                if(pattern.index.vmType !is VMUnsignedNumericValueTypeDescriptor || pattern.index.vmType.bitwidth != Config.VMConfig.registerBitwidth) {
                                    CVLError.General(
                                        hook.range,
                                        // TODO(jtoman): *awful* error
                                        "Expected index parameter to be an unsigned integer type"
                                    ).asError()
                                } else {
                                    nxt(tacStorageType.elementType)
                                }
                            }
                            else -> CVLError.General(
                                hook.range,
                                "Expected an array-like object at ${pattern.base}, found ${tacStorageType.toErrorString()}"
                                ).asError()
                        }
                    })
                }
                is CVLSlotPattern.FieldAccess -> {
                    if (checkCont == null) {
                        return CVLError.General(hook.range, offsetFieldErrorMsg).asError()
                    }
                    typeCheckPattern(pattern.base, seenDynamicType, true) { patternTypeDesc ->
                        if (patternTypeDesc is EVMTypeDescriptor.EVMStructDescriptor) {
                            patternTypeDesc.fields.find { it.fieldName == pattern.field }?.let { field ->
                                checkCont(field.fieldType)
                            } ?: CVLError.General(
                                hook.range,
                                "struct at path ${pattern.base} has no field named ${pattern.field}"
                            ).asError()
                        } else if (patternTypeDesc is EVMTypeDescriptor.PackedBytes1Array && pattern.field == "length") {
                            PackedBytes1ArrayLengthHook(hook, pattern).asError()
                        } else if (patternTypeDesc is EVMTypeDescriptor.DynamicArrayDescriptor && pattern.field == "length") {
                            // We have some `<pattern>.length`, where `pattern` is a dynamic array.
                            // Note that we can't disambiguate between the length of an array, or a struct with a field named `length`
                            // during parsing/kotlinization. This is why the implementation here doesn't simply add a new type of `CVLSlotPattern`
                            // for array-length access.
                            checkCont(EVMTypeDescriptor.UIntK(Config.VMConfig.registerBitwidth))
                        } else {
                            CVLError.General(
                                hook.range,
                                "Expected a struct with field ${pattern.field} at path ${pattern.base}, found ${patternTypeDesc.toErrorString()}"
                            ).asError()
                        }
                    }
                }
                is CVLSlotPattern.MapAccess -> {
                    typeCheckPattern(pattern.base, true, seenFields, checkCont.extend { ty, nxt ->
                        if (ty !is VMMappingDescriptor) {
                            return@extend CVLError.General(
                                hook.range,
                                "Expected mapping type at ${pattern.base}, found ${ty.toErrorString()}"
                            ).asError()
                        }
                        val keyType = ty.keyType
                        if(!hookTypesMatch(
                                compilerType = keyType,
                                declaredType = pattern.key.vmType
                        )) {
                            CVLError.General(
                                hook.range,
                                "Type mismatch: keys to ${pattern.base} should have type ${ty.keyType.toErrorString()} but ${pattern.key.id} has type ${pattern.key.type}"
                            ).asError()
                        /*
                          This is just the negation of:
                          keyType is VMTypeWithCorrespondence &&
                            ((keyType is VMValueTypeDescriptor && keyType.getPureCVLTypeToConvertTo(Contexts.hookValue) != null)) ||
                             (keyType is VMArrayTypeDescriptor && keyType.converterTo(PackedBytes, Contexts.hookValue).resultOrNull() != null)))

                           that is, the key type has a correspondence, and is a value type with a correspondence in hooks, or an array type
                           that can be converted to a hash blob
                         */
                        } else if(keyType !is VMTypeWithCorrespondence ||
                            ((keyType !is VMValueTypeDescriptor || !keyType.getPureCVLTypeToConvertTo(FromVMContext.HookValue).isResult()) &&
                                (keyType !is VMArrayTypeDescriptor || !keyType.converterTo(CVLType.PureCVLType.DynamicArray.PackedBytes, FromVMContext.HookValue.getVisitor()).isResult() && !keyType.converterTo(CVLType.PureCVLType.DynamicArray.StringType, FromVMContext.HookValue.getVisitor()).isResult()))) {
                            CVLError.General(
                                hook.range,
                                "Trying to use hooks on non-integral key type ${ty.keyType} at ${pattern.base}"
                            ).asError()
                        } else {
                            nxt(ty.valueType)
                        }
                    })
                }
                is CVLSlotPattern.Static -> {
                    val additionalErrors = mutableListOf<CVLError>()
                    run {
                        val contract = pattern.solidityContract
                        val contractSymbol = symbolTable.lookUpContractIdentifierNamespaceSymbol(contract.name, hook.scope)
                        val baseMsg =
                            "hook contract '${contract.name}' must be an imported contract (using contract_name as Contract)"
                        val hint = "Did you mean currentContract.${contract.name}?"
                        val fullMsg = if (contractSymbol == null) {
                            "$baseMsg but is undefined. $hint"
                        } else if (contractSymbol.symbolValue !is CVLContractNamespace) {
                            "$baseMsg but is bound as something else. $hint"
                        } else {
                            return@run
                        }
                        return CVLError.General(hook.range, fullMsg).asError()

                    }
                    if (seenFields && pattern !is CVLSlotPattern.Static.Named) {
                        additionalErrors.add(
                            CVLError.General(
                                hook.range, "Used named fields in $slot, but base slot is not named: hook patterns that use struct field names" +
                                        " must refer to storage slots by name instead of slot number"
                            )
                        )
                    }
                    if (seenDynamicType && pattern is CVLSlotPattern.Static.Indexed && pattern.offset != BigInteger.ZERO) {
                        additionalErrors.add(CVLError.General(hook.range, unalignedDynamicErrorMsg))
                    }
                    fun errorOr(f: () -> VoidResult<CVLError>) : VoidResult<CVLError> {
                        return if(additionalErrors.isNotEmpty()) {
                            additionalErrors.asError()
                        } else {
                            f()
                        }
                    }
                    return if (pattern is CVLSlotPattern.Static.Named) {
                        val contractScope = symbolTable.getContractScope(pattern.solidityContract)
                        val symbol = symbolTable.lookUpNonFunctionLikeSymbol(pattern.name, contractScope!!)
                        if (symbol == null || symbol !is CVLSymbolTable.SymbolInfo.ContractStorageType) {
                            val sourceLang = getContractSourceLanguage(contractScope)
                            val versionStr = getContractCompilerVersionStr(contractScope)
                            val version = versionStr?.let(CompilerVersion::fromStringOrNull)
                            val minimalVersion = CompilerVersion(0, 5, 13)
                            val addendum = if (sourceLang == SourceLanguage.Solidity
                                && (version == null || version < minimalVersion)) {
                                " Note, named slots are only supported from ${sourceLang.name} version $minimalVersion onward, " +
                                    "but contract ${pattern.solidityContract} is of version ${versionStr ?: "unknown"}"
                            } else if (sourceLang == SourceLanguage.Vyper) {
                                " Note, named slots are only supported for ${SourceLanguage.Solidity} version $minimalVersion onward, " +
                                    "but contract ${pattern.solidityContract} is a ${sourceLang.name} contract"
                            } else {
                                ""
                            }
                            additionalErrors.add(
                                CVLError.General(
                                    hook.range,
                                    "named pattern root '${pattern.name}' is not defined: did you spell something wrong?${addendum}"
                                )
                            )
                            additionalErrors.asError()
                        }
                        errorOr {
                            if (checkCont != null) {
                                // safe by not being in the error flow
                                (symbol as CVLSymbolTable.SymbolInfo.ContractStorageType).storageType.let(checkCont)
                            } else { // new case for returning something todo revise
                                ok
                            }
                        }
                    } else {
                        errorOr {
                            ok
                        }
                    }
                }
                is CVLSlotPattern.StructAccess -> {
                    if (seenFields) {
                        return CVLError.General(hook.range, offsetFieldErrorMsg).asError()
                    }
                    if (pattern.offset.mod(EVM_WORD_SIZE) != BigInteger.ZERO && seenDynamicType) {
                        return CVLError.General(
                            hook.range, unalignedDynamicErrorMsg
                        ).asError()
                    }
                    typeCheckPattern(pattern.base, seenDynamicType, false, null)
                }
            }
        }

        val type = hook.pattern.value

        return typeCheckPattern(slot, seenDynamicType = false, seenFields = false) { finalType ->
            if (finalType !is VMValueTypeDescriptor) {
                return@typeCheckPattern CVLError.General(
                    hook.range,
                    "Slot pattern $slot is not an integral type: ${finalType.toErrorString()}"
                ).asError()
            }
            val additionalErrors = mutableListOf<CVLError>()
            if(!hookTypesMatch(
                compilerType = finalType,
                declaredType = type.vmType)
            ) {
                additionalErrors.add(
                    CVLError.General(
                        hook.range,
                        "Type mismatch: $slot is declared as type ${finalType.prettyPrint()} but ${type.id} is declared as type ${type.type.prettyPrint()}"
                    )
                )
            }
            if ((hook.pattern as? CVLHookPattern.StoragePattern.Store)?.previousValue?.let { param ->
                    hookTypesMatch(
                        compilerType = finalType,
                        declaredType = param.vmType
                    )
                } == false) {
                additionalErrors.add(
                    CVLError.General(
                        hook.range,
                        "Type mismatch: ${hook.pattern.slot} is declared as type ${finalType.prettyPrint()}," +
                            " but previous value ${hook.pattern.previousValue!!.id} is declared as type " +
                            hook.pattern.previousValue.type.prettyPrint()
                    )
                )
            }
            if(additionalErrors.isNotEmpty()) {
                return@typeCheckPattern additionalErrors.asError()
            } else {
                ok
            }
        }.map {
            hook.pattern
        }

    }

}
