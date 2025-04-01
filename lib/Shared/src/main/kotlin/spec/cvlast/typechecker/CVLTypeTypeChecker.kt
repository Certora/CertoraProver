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

import spec.cvlast.*
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.Range
import utils.`impossible!`

class CVLTypeTypeChecker(
    private val symbolTable: CVLSymbolTable
) {
    fun typeCheck(
        type: CVLType.PureCVLType,
        range: Range,
        scope: CVLScope
    ) = typeCheckInternal(type, range, scope, true)

    /**
     * Ensuring any sorts/user defined types are indeed defined. Recursively checks compound types.
     */
    private fun typeCheckInternal(
        type: CVLType.PureCVLType,
        range: Range,
        scope: CVLScope,
        tuplesAllowed: Boolean
    ): CollectingResult<CVLType.PureCVLType, CVLError> {
        return when (type) {
            CVLType.PureCVLType.Primitive.AccountIdentifier -> type.lift()
            is CVLType.PureCVLType.DynamicArray.WordAligned -> typeCheckInternal(type.elementType, range, scope, false).bind { elementType ->
                typeCheckArrayElement(elementType, range)
            }.map { elementType ->
                type.copy(
                    elementType = elementType
                )
            }
            is CVLType.PureCVLType.DynamicArray.Bytes1Array -> type.lift()
            is CVLType.PureCVLType.StaticArray -> typeCheckInternal(type.elementType, range, scope, false).bind { elementType ->
                typeCheckArrayElement(elementType, range)
            }.map { elementType ->
                type.copy(
                    elementType = elementType
                )
            }
            is CVLType.PureCVLType.ArrayLiteral -> type.elementTypes
                .map { elementType ->
                    typeCheckInternal(
                        elementType,
                        range,
                        scope,
                        tuplesAllowed = false
                    ).bind { t ->
                        typeCheckArrayElement(t, range)
                    }.map { it as CVLType.PureCVLType.Primitive }
                }.flatten()
                .bind(
                    typeCheckInternal(
                        type.leastUpperBoundElementType,
                        range,
                        scope,
                        tuplesAllowed = false
                    )
                ) { elementTypes, leastUpperBoundType ->
                    @Suppress("NAME_SHADOWING")
                    typeCheckArrayElement(leastUpperBoundType, range).map { leastUpperBoundType ->
                        type.copy(elementTypes = elementTypes, leastUpperBoundElementType = leastUpperBoundType)
                    }
                }
            CVLType.PureCVLType.Bottom -> type.lift()
            is CVLType.PureCVLType.Enum -> type.lift()
            is CVLType.PureCVLType.Ghost.Function -> {
                type.inParams.map { typeCheckInternal(it, range, scope, tuplesAllowed = false) }.flatten()
                    .bind(typeCheckInternal(type.outParam, range, scope, tuplesAllowed = false)) { inParams, outParam ->
                        type.copy(inParams = inParams, outParam = outParam).lift()
                    }
            }
            is CVLType.PureCVLType.Ghost.Mapping -> {
                typeCheckInternal(type.key, range, scope, tuplesAllowed = false).bind(typeCheckInternal(type.value, range, scope, tuplesAllowed = false)) { key, value ->
                    type.copy(key = key, value = value).lift()
                }
            }
            CVLType.PureCVLType.Primitive.Bool -> type.lift()
            is CVLType.PureCVLType.Primitive.BytesK -> type.lift()
            CVLType.PureCVLType.Primitive.HashBlob -> type.lift()
            is CVLType.PureCVLType.Primitive.IntK -> type.lift()
            CVLType.PureCVLType.Primitive.Mathint -> type.lift()
            is CVLType.PureCVLType.Primitive.NumberLiteral -> type.lift()
            is CVLType.PureCVLType.Primitive.UIntK -> type.lift()
            is CVLType.PureCVLType.Sort -> if (symbolTable.lookUpNonFunctionLikeSymbol(
                    type.name,
                    scope
                )
                    ?.getCVLTypeOrNull() != type
            ) {
                CVLError.General(
                    range,
                    "Cannot resolve sort \"${type.name}\""
                ).asError()
            } else {
                type.lift()
            }
            is CVLType.PureCVLType.Struct -> mutableMapOf<String, CollectingResult<CVLType.PureCVLType, CVLError>>().also { map ->
                type.fields.forEach { map[it.fieldName] = typeCheckInternal(it.cvlType, range, scope, tuplesAllowed = false) }
            }.bind { map ->
                type.copy(fields = map.map {
                    CVLType.PureCVLType.Struct.StructEntry(
                        it.key,
                        it.value
                    )
                }).lift()
            }
            is CVLType.PureCVLType.VMInternal.BlockchainStateMap -> type.lift()
            is CVLType.PureCVLType.VMInternal.StorageReference -> type.lift()
            CVLType.PureCVLType.VMInternal.BlockchainState -> type.lift()
            is CVLType.PureCVLType.Primitive.CodeContract -> type.lift()
            CVLType.PureCVLType.VMInternal.RawArgs -> type.lift()
            CVLType.PureCVLType.Void -> type.lift()
            is CVLType.PureCVLType.UserDefinedValueType -> type.lift()
            is CVLType.PureCVLType.TupleType -> if(!tuplesAllowed) {
                CVLError.General(
                    range = range,
                    message = "Tuple types cannot appear here"
                ).asError()
            } else {
                type.elements.map {
                    typeCheckInternal(
                        it, range, scope, tuplesAllowed = false
                    )
                }.flatten().bind {
                    if(it.size < 2) {
                        CVLError.General(
                            range = range,
                            message = "Cannot have nullary or singleton tuple types"
                        ).asError()
                    } else {
                        CVLType.PureCVLType.TupleType(it).lift()
                    }
                }
            }
        }
    }

    fun typeCheckArrayElement(
        type: CVLType.PureCVLType,
        expForError: CVLExp
    ) = typeCheckArrayElement(type) { msg -> CVLError.Exp(expForError, msg) }

    private fun typeCheckArrayElement(
        type: CVLType.PureCVLType,
        rangeForError: Range
    ) = typeCheckArrayElement(type) { msg -> CVLError.General(rangeForError, msg) }

    fun typeCheckArrayLiteralElement(
        type: CVLType.PureCVLType,
        expForError: CVLExp
    ) = when(type) {
        is CVLType.PureCVLType.Primitive.NumberLiteral -> type.lift()
        is CVLType.PureCVLType.ArrayLiteral -> {
            val lubIsNonLiteralType = typeCheckArrayElement(type.leastUpperBoundElementType, expForError)
            val elementsAreGoodTypes = type.elementTypes.map {
                typeCheckArrayElement(it, expForError)
            }.flatten()
            lubIsNonLiteralType.map(elementsAreGoodTypes) { lub, elems ->
                type.copy(
                    elementTypes = elems,
                    leastUpperBoundElementType = lub
                )
            }
        }
        else -> typeCheckArrayElement(type) {
            CVLError.Exp(
                expForError,
                it
            )
        }
    }

    /**
     * Is this a type which could be encoded as an element of an array? This effectively rejects types that cannot
     * be encoded directly or via pointers in a series of bytemaps, e.g., ghost mappings, mathints, and others.
     */
    private fun typeCheckArrayElement(
        type: CVLType.PureCVLType,
        error: (String) -> CVLError
    ): CollectingResult<CVLType.PureCVLType, CVLError> =
        when (val t = type) {
            is CVLType.PureCVLType.MaybeBufferEncodeableType -> {
                t.getEncodingOrNull()?.let {
                    type.lift()
                } ?: error("An array with an element of type $type is currently unsupported in CVL.").asError()
            }
            /*
             Kotlin's exhaustivity checker broke, all of these types extend the above
             */
            CVLType.PureCVLType.Bottom,
            CVLType.PureCVLType.Primitive.AccountIdentifier,
            CVLType.PureCVLType.Primitive.Bool -> {
                `impossible!`
            }
            is CVLType.PureCVLType.Primitive.NumberLiteral -> error("Arrays of number literals can only appear in array literal expressions").asError()

            is CVLType.PureCVLType.Struct -> {
                t.fields.map { ent ->
                    typeCheckArrayElement(ent.cvlType, error).map {
                        ent.copy(cvlType = it)
                    }
                }.flatten().map { flds ->
                    t.copy(fields = flds)
                }
            }
            is CVLType.PureCVLType.CVLArrayType -> {
                when(t) {
                    is CVLType.PureCVLType.ArrayLiteral -> error("An array with an array literal element type is not allowed").asError()
                    CVLType.PureCVLType.DynamicArray.PackedBytes,
                    CVLType.PureCVLType.DynamicArray.StringType -> type.lift()
                    is CVLType.PureCVLType.DynamicArray.WordAligned -> typeCheckArrayElement(t.elementType, error).map {
                        t.copy(it)
                    }
                    is CVLType.PureCVLType.StaticArray -> typeCheckArrayElement(t.elementType, error).map {
                        t.copy(it)
                    }
                }
            }

            is CVLType.PureCVLType.VMInternal.BlockchainStateMap,
            is CVLType.PureCVLType.VMInternal.StorageReference,
            is CVLType.PureCVLType.Primitive.CodeContract,
            is CVLType.PureCVLType.Sort,
            is CVLType.PureCVLType.Ghost.Function,
            is CVLType.PureCVLType.Ghost.Mapping,
            is CVLType.PureCVLType.TupleType,
            CVLType.PureCVLType.VMInternal.BlockchainState,
            CVLType.PureCVLType.Primitive.HashBlob,
            CVLType.PureCVLType.Primitive.Mathint,
            CVLType.PureCVLType.VMInternal.RawArgs,
            CVLType.PureCVLType.Void -> error("An array with an element of type $type is currently unsupported in CVL. " +
                "Only primitive elements which fit into one word are allowed." ).asError()
        }
}
