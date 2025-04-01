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

package com.certora.certoraprover.cvl

import spec.TypeResolver
import spec.cvlast.CVLExpTag
import spec.cvlast.CVLLhs
import utils.Range
import spec.cvlast.CVLScope
import spec.cvlast.CVLType.PureCVLType
import spec.cvlast.CVLType.PureCVLType.DynamicArray.WordAligned
import spec.cvlast.typechecker.*
import spec.cvlast.typedescriptors.VMTypeDescriptor
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.mapErrors
import utils.ErrorCollector.Companion.collectingErrors
import java.math.BigInteger

// This file contains the "Java" AST nodes for types.  See README.md for information about the Java AST.

sealed class TypeOrLhs(val range: Range) : Kotlinizable<CVLLhs> {
    /** Interpret this ambiguous expression as a left-hand side  */
    abstract override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLLhs, CVLError>

    /** Interpret this ambiguous expression as a CVL Type  */
    abstract fun toCVLType(resolver: TypeResolver, scope: CVLScope): CollectingResult<PureCVLType, CVLError>

    /** Interpret this as a VMType  */
    abstract fun toVMType(resolver: TypeResolver, location: String?, scope: CVLScope): CollectingResult<VMTypeDescriptor, CVLError>
}

class IdLhs(range: Range, val id: String) : TypeOrLhs(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLLhs, CVLError>
        = CVLLhs.Id(range, id, CVLExpTag(scope, range, false)).lift()

    override fun toCVLType(resolver: TypeResolver, scope: CVLScope): CollectingResult<PureCVLType, CVLError>
        = QualifiedTypeReference(null, id, range).toCVLType(resolver, scope)

    override fun toVMType(resolver: TypeResolver, location: String?, scope: CVLScope): CollectingResult<VMTypeDescriptor, CVLError>
        = QualifiedTypeReference(null, id, range).toVMType(resolver, location, scope)
}


class ArrayLhs(range: Range, val baseType: TypeOrLhs, val index: Exp) : TypeOrLhs(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLLhs, CVLError> = collectingErrors {
        val _baseType = baseType.kotlinize(resolver, scope)
        val _index    = index.kotlinize(resolver, scope)
        map(_baseType, _index) { baseType, index -> CVLLhs.Array(range, baseType, index, CVLExpTag(scope,range, false)) }
    }

    override fun toCVLType(resolver: TypeResolver, scope: CVLScope): CollectingResult<PureCVLType, CVLError> = collectingErrors {
        val _baseType = baseType.toCVLType(resolver, scope)
        val _index    = staticIndex()
        map(_baseType,_index) { baseType, index -> PureCVLType.StaticArray(baseType, index) }
    }

    override fun toVMType(resolver: TypeResolver, location: String?, scope: CVLScope): CollectingResult<VMTypeDescriptor, CVLError> = collectingErrors {
        val _baseType = baseType.toVMType(resolver, location, scope)
        val _index = staticIndex()
        map(_baseType, _index) { baseType, index -> resolver.factory.getStaticArray(baseType, location, index) }
    }

    /** Ensures that `this` is a number literal and returns its value  */
    private fun staticIndex(): CollectingResult<BigInteger, CVLError> {
        return when(index) {
            is NumberExp -> index.n.lift()
            else         -> ArraySizeMustBeLiteral(index).asError()
        }
    }
}

class TupleType(private val types: List<TypeOrLhs>, range: Range) : TypeOrLhs(range) {
    override fun toCVLType(resolver: TypeResolver, scope: CVLScope): CollectingResult<PureCVLType, CVLError>
        = types.map { it.toCVLType(resolver, scope) }.flatten().map { PureCVLType.TupleType(it) }

    override fun toVMType(resolver: TypeResolver, location: String?, scope: CVLScope) = TupleTypesAreCVLOnly(this).asError()

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope) = LhsIsTuple(this).asError()
}

// is this needed?
class DynamicArrayType(val baseType: TypeOrLhs, range: Range) : TypeOrLhs(range) {
    override fun toCVLType(resolver: TypeResolver, scope: CVLScope): CollectingResult<PureCVLType, CVLError>
        = baseType.toCVLType(resolver, scope).map { WordAligned(it) }

    override fun toVMType(resolver: TypeResolver, location: String?, scope: CVLScope): CollectingResult<VMTypeDescriptor, CVLError>
        = baseType.toVMType(resolver, location, scope).map { resolver.factory.getDynamicArray(location, it) }

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope) = LhsIsDynamicArray(range).asError()
}


class MappingType(val keyType: TypeOrLhs, val valueType: TypeOrLhs, range: Range) : TypeOrLhs(range) {
    override fun toCVLType(resolver: TypeResolver, scope: CVLScope): CollectingResult<PureCVLType, CVLError> = collectingErrors {
        val _keyType   = keyType.toCVLType(resolver, scope)
        val _valueType = valueType.toCVLType(resolver, scope)
        map(_keyType, _valueType) { keyType, valueType -> PureCVLType.Ghost.Mapping(keyType, valueType) }
    }

    override fun toVMType(resolver: TypeResolver, location: String?, scope: CVLScope): CollectingResult<VMTypeDescriptor, CVLError> = collectingErrors {
        val _keyType = keyType.toVMType(resolver, location, scope)
        val _valueType = valueType.toVMType(resolver, location, scope)
        map(_keyType,_valueType) { keyType, valueType -> resolver.factory.getMappingType(keyType, valueType, location) }
    }

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope) = LhsIsMapping(this).asError()
}

@Suppress("Deprecation") // TODO CERT-3752
class QualifiedTypeReference(val contract: String?, val id: String, range: Range) : TypeOrLhs(range) {
    override fun toCVLType(resolver: TypeResolver, scope: CVLScope): CollectingResult<PureCVLType, CVLError>
        = resolver.resolveCVLType(contract, id).mapErrors { ToCVLTypeError(range, it) }

    override fun toVMType(resolver: TypeResolver, location: String?, scope: CVLScope): CollectingResult<VMTypeDescriptor, CVLError>
        = resolver.resolveVMType(contract, id, location).mapErrors { CVLError.General(range, it) }

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope) = CVLError.General(range, "A type reference may not be used here.").asError()
}
