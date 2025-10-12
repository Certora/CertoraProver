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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package prefixgen

import bridge.types.SolidityTypeDescription

/**
 * Utilities for the code generation process.
 */
object CodeGen {

    /**
     * Bound some expression given by [syntax] to be between `[0,maxValueInclusive]`.
     * This is done by simply applying a modulus.
     */
    fun bound(syntax: String, maxValueInclusive: Int) = "$syntax % ${maxValueInclusive + 1}"

    /**
     * Pretty print the given soliidty type for use in a *local* variable declaration.
     */
    fun SolidityTypeDescription.pp() = when(this) {
        is SolidityTypeDescription.Contract -> {
            this.contractName
        }
        is SolidityTypeDescription.Array,
        is SolidityTypeDescription.Function,
        is SolidityTypeDescription.StaticArray,
        is SolidityTypeDescription.UserDefined.Struct,
        is SolidityTypeDescription.Mapping -> throw UnsupportedOperationException("nope")
        is SolidityTypeDescription.PackedBytes -> "bytes memory"
        is SolidityTypeDescription.Primitive -> this.primitiveName.name
        is SolidityTypeDescription.StringType -> "string memory"
        is SolidityTypeDescription.UserDefined.Enum -> this.containingContract?.let { "$it." }.orEmpty() + this.enumName
        is SolidityTypeDescription.UserDefined.ValueType -> throw UnsupportedOperationException("not yet")
    }

    /**
     * Pretty print the given solidity type for use in *storage* (or struct definition) declaration.
     * For most types, this is just [pp], with the exception of `string` and `bytes`, which do not have the `memory`
     * location.
     */
    fun SolidityTypeDescription.storagePP() = when(this) {
        is SolidityTypeDescription.PackedBytes -> "bytes"
        is SolidityTypeDescription.StringType -> "string"
        else -> this.pp()
    }


    /**
     * Indicates whether the type described by `this` is something we know how to build via fuzzing.
     * These are (smallish) static arrays of buildable types, dynamic arrays of primitive types,
     * primitive types, bytes and strings, enums, and structs of buildable types.
     */
    fun SolidityTypeDescription.buildable() : Boolean = when(this) {
        is SolidityTypeDescription.Array -> this.dynamicArrayBaseType is SolidityTypeDescription.Primitive
        is SolidityTypeDescription.Contract -> true
        is SolidityTypeDescription.Function,
        is SolidityTypeDescription.Mapping -> false
        is SolidityTypeDescription.PackedBytes,
        is SolidityTypeDescription.StringType,
        is SolidityTypeDescription.Primitive -> true
        is SolidityTypeDescription.StaticArray -> this.staticArrayBaseType.buildable() && this.staticArraySize <= 4.toBigInteger()
        is SolidityTypeDescription.UserDefined.ValueType -> false
        is SolidityTypeDescription.UserDefined.Enum -> true
        is SolidityTypeDescription.UserDefined.Struct -> this.structMembers.all {
            it.type.buildable()
        }
    }
}
