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

package report.calltrace.formatter

import analysis.storage.InstantiatedDisplayPath
import datastructures.stdcollections.*
import dwarf.Offset
import dwarf.RustType
import report.calltrace.printer.DebugAdapterProtocolStackMachine
import sbf.dwarf.EvaluationResult
import utils.*
import vc.data.state.TACValue

class SolanaCallTraceValueFormatter(val variableName: String, val type: RustType) {
    fun formatValue(pieces: Map<Offset, EvaluationResult>, deref: Boolean): Map<InstantiatedDisplayPath, DebugAdapterProtocolStackMachine.DebuggerValue> {
        val res = mutableMapOf<InstantiatedDisplayPath, DebugAdapterProtocolStackMachine.DebuggerValue>()
        pieces.forEachEntry { (offset, _) ->
            val idp = type.toInstantiatedDisplayPaths(variableName, pieces, deref, offset)
            if (idp != null) {
                val r = pieces[idp.second]
                if (r != null) {
                    res[idp.first] = when (r) {
                        is EvaluationResult.Concrete -> DebugAdapterProtocolStackMachine.DebuggerValue(TACValue.valueOf(r.value))
                        is EvaluationResult.Unknown -> DebugAdapterProtocolStackMachine.DebuggerValue(r.reason)
                    }
                }
            }
        }
        return res
    }
}

/**
 * Creates a [InstantiatedDisplayPath] (i.e. an access path into the rust type at the [offset]).
 *
 * The access path start in the [rootName] and if the offset points to a nested structs,
 * it recursively builds the access path.
 */
fun RustType.toInstantiatedDisplayPaths(rootName: String, rawResult: Map<Offset, EvaluationResult>, deref: Boolean, offset: Offset): Pair<InstantiatedDisplayPath, Offset>? {
    return toInstantiatedDisplayPathsRecursive(offset, rawResult, deref) { innerPath ->
        InstantiatedDisplayPath.Root(rootName, innerPath)
    }
}

private fun RustType.toInstantiatedDisplayPathsRecursive(
    offset: Offset,
    rawResult: Map<Offset, EvaluationResult>,
    deref: Boolean,
    buildPath: (InstantiatedDisplayPath?) -> InstantiatedDisplayPath,
): (Pair<InstantiatedDisplayPath, Offset>)? {
    if (offset.offset >= this.getByteSize()) {
        return null;
    }
    return when (this) {
        is RustType.Array -> this.elementType().toInstantiatedDisplayPathsRecursive(offset.mod(this.elementType().getByteSize()), rawResult, deref) { innerPath ->
            buildPath(InstantiatedDisplayPath.ArrayAccess(TACValue.PrimitiveValue.Integer((offset / this.elementType().getByteSize()).toBigInteger()), innerPath))
        }?.copy(second = offset)

        is RustType.Struct -> {
            val structSortedByOffset = this.fields.sortedBy { it.offset }
            val offsetRangeToField = structSortedByOffset
                .map { it.offset }
                .plus(Offset(this.getByteSize()))
                .zipWithNext()
                .mapIndexed { idx, el -> el.first ..< el.second to structSortedByOffset[idx] }

            val matchedField = offsetRangeToField.firstOrNull { offset.offset in it.first }
            var res: Pair<InstantiatedDisplayPath, Offset>? = null
            if (matchedField != null) {
                val remaining = offset - Offset(matchedField.first.first)
                res = matchedField.second.fieldType().toInstantiatedDisplayPathsRecursive(remaining, rawResult, deref) { inner ->
                    buildPath(InstantiatedDisplayPath.FieldAccess(matchedField.second.name, inner))
                };
            }
            res?.copy(second = offset)
        }

        is RustType.Reference -> {
            if (deref) {
                this.referent().toInstantiatedDisplayPathsRecursive(offset, rawResult, false) { inner ->
                    when (inner) {
                        is InstantiatedDisplayPath.ArrayAccess -> buildPath(inner)
                        is InstantiatedDisplayPath.FieldAccess -> buildPath(inner)
                        is InstantiatedDisplayPath.GhostAccess -> `impossible!`
                        is InstantiatedDisplayPath.MapAccess -> `impossible!`
                        is InstantiatedDisplayPath.Root -> buildPath(inner)
                        null -> buildPath(InstantiatedDisplayPath.FieldAccess("deref ", null))
                    }
                }
            } else {
                buildPath(null) to offset
            }
        }

        is RustType.VariantPart -> {
            if (offset == Offset(0UL)) {
                val discriminantResult = rawResult[offset]
                if (discriminantResult is EvaluationResult.Unknown) {
                    return buildPath(null) to offset
                }
                this.variants.firstOrNull { variant ->
                    if (discriminantResult is EvaluationResult.Concrete) {
                        variant.discr_value.toBigInteger() == discriminantResult.value
                    } else {
                        false
                    }
                }?.let { matchedVariant ->
                    val variantType = matchedVariant.variantType()
                    variantType.toInstantiatedDisplayPathsRecursive(variantType.flattenToOffsets().min(), rawResult, deref) { inner ->
                        buildPath(InstantiatedDisplayPath.FieldAccess(matchedVariant.discr_name, inner))
                    }
                }
            } else {
                null
            }
        }

        is RustType.Enum -> {
            if (offset != Offset(0UL)) {
                return null
            }
            val discrValue = rawResult[offset]
            return if (discrValue != null && discrValue is EvaluationResult.Concrete && discrValue.value.toInt() < this.variants.size) {
                val matchedVariant = this.variants[discrValue.value.toInt()]
                buildPath(InstantiatedDisplayPath.FieldAccess(this.name + "." + matchedVariant.name, null)) to offset
            } else {
                null
            }
        }

        RustType.U128,
        RustType.I128 -> when (offset) {
            Offset(0UL) -> buildPath(InstantiatedDisplayPath.FieldAccess("__0", null)) to offset
            Offset(8UL) -> buildPath(InstantiatedDisplayPath.FieldAccess("__1", null)) to offset
            else -> null
        }

        is RustType.PrimitiveType,
        RustType.UNKNOWN,
        RustType.Unit -> buildPath(null) to offset
    }
}
