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

import bridge.types.SolcLocation
import bridge.types.SolidityTypeDescription
import bridge.types.SolidityTypeInstance
import datastructures.stdcollections.*
import report.CVTAlertType
import scene.ICVLScene
import scene.IContractWithSource
import spec.AbstractTypeResolver
import spec.CVLWarningLogger
import spec.TypeResolver
import spec.VMDescriptorFactory
import spec.cvlast.typedescriptors.EVMLocationSpecifier
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.mapEither
import utils.mapToSet
import java.math.BigInteger

object EVMTypeResolver {
    object EVMFactory : VMDescriptorFactory {
        override fun getDynamicArray(location: String?, vmBase: VMTypeDescriptor): VMTypeDescriptor {
            return EVMTypeDescriptor.DynamicArrayDescriptor(
                elementType = vmBase as EVMTypeDescriptor,
                location = location?.let(EVMLocationSpecifier.Companion::fromString)
            )
        }

        override fun getMappingType(kRes: VMTypeDescriptor, vRes: VMTypeDescriptor, location: String?): VMTypeDescriptor {
            val k = kRes as EVMTypeDescriptor
            val v = vRes as EVMTypeDescriptor
            return EVMTypeDescriptor.EVMMappingDescriptor(
                    keyType = k,
                    valueType = v,
                    location = location?.let(EVMLocationSpecifier.Companion::fromString)
                )
        }

        override fun getStaticArray(resolveIn: VMTypeDescriptor, location: String?, n: BigInteger): VMTypeDescriptor {
            val eType = resolveIn as EVMTypeDescriptor
            return EVMTypeDescriptor.StaticArrayDescriptor(
                location = location?.let(EVMLocationSpecifier.Companion::fromString),
                elementType = eType,
                numElements = n
            )
        }

        @Deprecated("Maybe this shouldn't be here? weird place for it")
        override fun methodType(): CVLType.PureCVLType {
            return EVMBuiltinTypes.method
        }
    }

    sealed class TypeSource {
        data class Contract(val contract: String) : TypeSource() {
            override fun toString() = contract
        }

        data object FileScope : TypeSource()
    }

    fun getResolver(s: ICVLScene, mainContract: String): TypeResolver {
        // in all the following mappings the outermost key is the contract, with null meaning a file-scope user defined type.
        val cvlImportedTypes = mutableMapOf<TypeSource, MutableMap<String, CVLType.PureCVLType>>()

        /** evmTypes[contract][typeName](location) */
        val evmTypes = mutableMapOf<TypeSource, MutableMap<String, (String?) -> EVMTypeDescriptor>>()

        val typeDescs = mutableMapOf<TypeSource, MutableMap<String, SolidityTypeDescription.UserDefined?>>()

        val internalFuncContracts = mutableSetOf<String>()

        // Records, per (typeSource, name), every (importingContractName, type) pair seen while iterating the scene.
        // Only consulted for keys that actually conflict, to re-expose each definition qualified by the importing
        // contract. Non-conflicting names are never touched, so no existing resolution behavior changes.
        val occurrencesByKey = mutableMapOf<Pair<TypeSource, String>, MutableList<Pair<String, SolidityTypeDescription.UserDefined>>>()
        val conflictedKeys = mutableSetOf<Pair<TypeSource, String>>()

        /**
         * Adds to [typeSource] the type [ty] under the name [nm], so in the spec file `contract.nm` will refer to type [ty]
         */
        fun populateInContract(typeSource: TypeSource, nm: String, ty: SolidityTypeDescription.UserDefined) {
            val ids = typeDescs.computeIfAbsent(typeSource) {
                mutableMapOf()
            }

            val cvl = ty.toUserDefinedCVLType()
            if(nm !in ids) {
                ids[nm] = ty
            } else if (ids[nm] == null) {
                // we previously found conflicting types with this name, so continue to ignore it
                return
            } else {
                val currCvl = cvlImportedTypes[typeSource]?.get(nm)
                if(currCvl != cvl.resultOrNull()) {
                    CVLWarningLogger.generalWarning(
                        "Conflicting types with name ${(typeSource as? TypeSource.Contract)?.let { "$it." }.orEmpty()}$nm, " +
                            "neither will be available within the spec. Qualify by the originating contract instead " +
                            "(e.g. `SomeContract.$nm`)."
                    )
                    ids[nm] = null
                    cvlImportedTypes[typeSource]?.remove(nm)
                    evmTypes[typeSource]?.remove(nm)
                    conflictedKeys.add(typeSource to nm)
                    return
                }
            }
            cvl.mapEither(
                resultCallback = {
                    cvlImportedTypes.computeIfAbsent(typeSource) {  mutableMapOf() }[nm] = it
                },
                errorCallback = { errors ->
                    CVLWarningLogger.warning(
                        CVTAlertType.CVL,
                        "The type `${(typeSource as? TypeSource.Contract)?.let { "$it." }.orEmpty()}$nm` will not be accessible in CVL code\n" +
                            "Reason(s):\n\t${errors.joinToString(separator = "\n\t")}"
                    )
                }
            )

            evmTypes.computeIfAbsent(typeSource) { mutableMapOf() }[nm] = { loc: String? ->
                loc?.let(SolcLocation::valueOf).let { solcLoc ->
                    SolidityTypeInstance(ty, solcLoc).toVMTypeDescriptor()
                }
            }
        }

        s.getContracts().mapNotNull {
            it as? IContractWithSource
        }.forEach { contr ->
            /**
             * Iterates through all of [this]'s [SolidityTypeDescription]s and for each of them that's a user-defined
             * type (i.e. [SolidityTypeDescription.UserDefined]) adds it to the relevant contract - if the type was
             * declared within a contract/library it will be added to that contract's set of types, and if it's a top-level
             * type then it will be populated in all contracts that use it.
             */
            fun Iterable<SolidityTypeDescription>.processDescriptions() {
                this.filterIsInstance<SolidityTypeDescription.UserDefined>().forEach { typeDesc ->
                    // A user-defined type that's declared within some contract/library should be populated in the declaring contract.
                    // A top-level user-defined type needs to be populated in all contracts that have access to it (i.e. that
                    // don't shadow the top-level declaration).
                    val ts = typeDesc.containingContract?.let { TypeSource.Contract(it) } ?: TypeSource.FileScope
                    // Remember which contract imported this type, so a later conflict can be disambiguated by it.
                    occurrencesByKey.computeIfAbsent(ts to typeDesc.userDeclaredName) { mutableListOf() }
                        .add(contr.src.name to typeDesc)
                    populateInContract(ts, typeDesc.userDeclaredName, typeDesc)
                }
            }

            contr.src.solidityTypes.processDescriptions()
            contr.src.methods.asSequence().flatMap {
                it.fullArgs.asSequence() + it.returns.asSequence()
            }.map { it.typeDesc }.asIterable().processDescriptions()
            contr.src.internalFunctions.asSequence().flatMap { (_, v) ->
                v.method.returns.asSequence() + v.method.fullArgs.asSequence()
            }.map { it.typeDesc }.asIterable().processDescriptions()
            contr.src.internalFunctions.forEachEntry { (_, m) ->
                internalFuncContracts.add(m.declaringContract)
            }
        }

        // Conflict-gated disambiguation: a name is only reachable here if it had conflicting definitions across
        // contracts and was therefore purged above (so it is already unavailable unqualified). For each such name,
        // re-expose every definition under the (real) contract that imported it, keyed by the bare type name, so a
        // spec can write `ImportingContract.Rounding` to select a specific definition. Names that did not conflict
        // are absent from `conflictedKeys`, so this is purely additive and changes no existing resolution.
        conflictedKeys.toList().forEach { (typeSource, nm) ->
            val declaringScope = (typeSource as? TypeSource.Contract)?.contract
            occurrencesByKey[typeSource to nm]?.forEach { (importingContract, ty) ->
                if (importingContract != declaringScope) {
                    populateInContract(TypeSource.Contract(importingContract), nm, ty)
                }
            }
        }

        class EVMResolver(val contracts: Set<SolidityContract>) : AbstractTypeResolver() {
            override val factory: VMDescriptorFactory
                get() = EVMFactory

            init {
                // this should guarantee that no one constructs a SolidityContract with name "currentContract"
                contractAliases[SolidityContract.Current.name] = mainContract
            }

            override fun resolveVMType(contractName: String?, typeName: String, location: String?): CollectingResult<VMTypeDescriptor, String> {

                /**
                 * Render a meaningful error message, stating that we `fullTypeName` is not a valid type. Try to find
                 * proper types that the users might have meant. Captures `typeName` as the base type name, and uses
                 * `fullTypeName` for what the user wrote down.
                 */
                fun renderErrorMessage(fullTypeName: String = typeName): CollectingResult.Error<String> {
                    val matchingTypes = evmTypes.entries.filter { (c, t) ->
                        c is TypeSource.Contract && t.keys.contains(typeName)
                    }.map { (c, _) ->
                        "$c.$typeName"
                    }

                    return if (matchingTypes.isNotEmpty()) {
                        "$fullTypeName is not a valid EVM type. Did you mean `${matchingTypes.joinToString(separator = "`, or `")}`?"
                    }
                    else {
                        "$fullTypeName is not a valid EVM type"
                    }.asError()
                }

                return if (contractName == null) {
                    SolidityTypeDescription.builtin(typeName)
                        ?.let { typeDesc ->
                            val loc = if (location != null) {
                                SolcLocation.valueOf(location)
                            } else {
                                null
                            }
                            SolidityTypeInstance(typeDesc, loc)
                        }
                        ?.toVMTypeDescriptor()
                        ?.lift() ?: renderErrorMessage()
                } else {
                    // types may only use contract name, not contract "instance" name
                    validContractCheck(contractName).bind { contract ->
                        evmTypes[TypeSource.Contract(contract)]?.get(typeName)?.invoke(location)?.lift()
                            ?: evmTypes[TypeSource.FileScope]?.get(typeName)?.invoke(location)?.lift()
                            ?: renderErrorMessage("$contract.$typeName")
                    }
                }
            }

            override fun validContract(contractName: String): Boolean {
                return contractName in contracts.mapToSet { c -> c.name } ||
                    TypeSource.Contract(contractName) in evmTypes || contractName in internalFuncContracts
            }

            fun validContractCheck(contractName: String) =
                if (!validContract(contractName)) {
                    "Contract name $contractName does not exist in the scene. Make sure you are using a contract name and not a contract instance name.".asError()
                } else {
                    contractName.lift()
                }

            override fun resolveCVLTypeWithContract(contract: String, id: String): CollectingResult<CVLType.PureCVLType, String> {
                // types may only use contract name, not contract "instance" name
                return validContractCheck(contract).bind { _contract ->
                    cvlImportedTypes[TypeSource.Contract(_contract)]?.get(id)?.lift()
                        ?: cvlImportedTypes[TypeSource.FileScope]?.get(id)?.lift()
                        ?: if (evmTypes[TypeSource.Contract(_contract)]?.get(id) != null) {
                            "Type $id declared in contract $_contract is not valid in spec context".asError()
                        } else if (evmTypes[TypeSource.FileScope]?.get(id) != null) {
                            "Top level type $id is not valid in spec context".asError()
                        } else {
                            // The name may be unavailable because it conflicted across contracts and was purged;
                            // in that case it is re-exposed under each importing contract, so point the user there.
                            val candidates = cvlImportedTypes.entries.filter { (c, t) ->
                                c is TypeSource.Contract && t.keys.contains(id)
                            }.map { (c, _) -> "$c.$id" }
                            if (candidates.isNotEmpty()) {
                                "Type $_contract.$id is not a valid type. Did you mean " +
                                    "`${candidates.joinToString(separator = "`, or `")}`?"
                            } else {
                                "Type $_contract.$id is not a valid type"
                            }.asError()
                        }
                    }
                }

            override fun resolveNameToContract(contract: String): SolidityContract {
                val resolvedContractName = resolveContractName(contract)
                return contracts.singleOrNull { it.name == resolvedContractName } ?: super.resolveNameToContract(
                    resolvedContractName
                )
            }

            override fun clone(): TypeResolver {
                return EVMResolver(contracts).also {
                    it.contractAliases.putAll(this@EVMResolver.contractAliases)
                }
            }

        }
        return EVMResolver(s.getContracts().mapToSet { c -> SolidityContract(c.name) })
    }
}
