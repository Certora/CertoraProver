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

@file:Suppress("RemoveRedundantQualifierName") // to clearly differentiate between DP and IDP

package analysis.storage

import com.certora.collect.*
import log.Logger
import log.LoggerTypes
import log.warnIfNull
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.CallTraceValue
import report.calltrace.sarif.Sarif
import report.calltrace.sarif.SarifBuilder.Companion.joinToSarif
import report.calltrace.sarif.SarifBuilder.Companion.mkSarif
import solver.CounterexampleModel
import spec.cvlast.CVLType
import spec.cvlast.GhostSort
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.TACStorageType
import utils.AmbiSerializable
import utils.KSerializable
import utils.leftOrThrow
import vc.data.TACBuiltInFunction
import vc.data.TACSymbol
import vc.data.TransformableSymEntityWithRlxSupport
import vc.data.state.ConcreteTacValue
import vc.data.state.TACValue

private val logger = Logger(LoggerTypes.STORAGE_ANALYSIS)

/**
 * A "high level" version of [analysis.storage.StorageAnalysis.AnalysisPath] that
 * preserves the original solidity structure.
 */
@KSerializable
@Treapable
sealed class DisplayPath : AmbiSerializable, TransformableSymEntityWithRlxSupport<DisplayPath> {
    abstract val base: DisplayPath?
    abstract fun toDisplayString(formatter: CallTraceValueFormatter, model: CounterexampleModel) : Sarif
    abstract fun toNonIndexedString() : String
    abstract fun instantiateTACSymbols(model: CounterexampleModel, next: InstantiatedDisplayPath? = null): InstantiatedDisplayPath

    @KSerializable
    data class Root(val name: String) : DisplayPath() {
        override val base: DisplayPath? = null
        override fun toDisplayString(formatter: CallTraceValueFormatter, model: CounterexampleModel): Sarif {
            return mkSarif { append(this@Root.toString()) }
        }

        override fun toNonIndexedString(): String {
            return toString()
        }

        override fun toString(): String {
            return name
        }

        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): DisplayPath {
            return this
        }

        override fun instantiateTACSymbols(model: CounterexampleModel, next: InstantiatedDisplayPath?): InstantiatedDisplayPath {
            return InstantiatedDisplayPath.Root(name, next)
        }
    }

    @KSerializable
    data class FieldAccess(val field: String, override val base: DisplayPath) : DisplayPath() {

        override fun toDisplayString(formatter: CallTraceValueFormatter, model: CounterexampleModel) = mkSarif {
            append(base.toDisplayString(formatter, model))
            append(".$field")
        }

        override fun toNonIndexedString(): String {
            return "${base.toNonIndexedString()}.$field"
        }

        override fun toString(): String {
            return "$base.$field"
        }

        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): DisplayPath {
            return this.copy(base = base.transformSymbols(f))
        }

        override fun instantiateTACSymbols(model: CounterexampleModel, next: InstantiatedDisplayPath?): InstantiatedDisplayPath {
            val idp = InstantiatedDisplayPath.FieldAccess(this.field, next)
            return this.base.instantiateTACSymbols(model, idp)
        }
    }

    @KSerializable
    data class ArrayAccess(val index: TACSymbol, override val base: DisplayPath) : DisplayPath() {

        override fun toDisplayString(formatter: CallTraceValueFormatter, model: CounterexampleModel): Sarif = mkSarif {
            append(base.toDisplayString(formatter, model))
            append("[")
            append(
                CallTraceValue.evmCtfValueOrUnknown(
                    model.valueAsTACValue(index),
                    EVMTypeDescriptor.UIntK(256)
                ).toSarif(formatter, tooltip = "accessed index"),
            )
            append("]")
        }

        override fun toNonIndexedString(): String {
            return "${base.toNonIndexedString()}[*]"
        }


        override fun toString(): String {
            return "$base[i = $index]"
        }

        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): DisplayPath {
            return this.copy(index = f(index), base = base.transformSymbols(f))
        }

        override fun instantiateTACSymbols(model: CounterexampleModel, next: InstantiatedDisplayPath?): InstantiatedDisplayPath {
            val index = this.index.toIntegerOrThrow(model)
            val idp = InstantiatedDisplayPath.ArrayAccess(index, next)
            return this.base.instantiateTACSymbols(model, idp)
        }
    }

    @KSerializable
    data class MapAccess(val key: TACSymbol, val keyTyp: TACStorageType?, override val base: DisplayPath) : DisplayPath() {

        override fun toDisplayString(formatter: CallTraceValueFormatter, model: CounterexampleModel): Sarif {
            /**
             * We rely here on the fact that [keyTyp], according to Solidity docs:
             * "can be any built-in value type, bytes, string, or any contract or enum type.
             * Other user-defined or complex types, such as mappings, structs or array types are not allowed."
             * In other words, we expect [keyTyp] to be either [TACStorageType.IntegralType] or [TACStorageType.Bytes].
             */
            val keyTypeDescriptor : VMTypeDescriptor? = when(keyTyp) {
                is TACStorageType.IntegralType -> keyTyp.descriptor.warnIfNull(logger) {
                    "No type description attached to $keyTyp"
                }
                is TACStorageType.Bytes -> EVMTypeDescriptor.PackedBytes(null)
                else -> {
                    logger.warn {
                        "In $this, expected the mapping key type to be either IntegralType or Bytes (got $keyTyp)"
                    }
                    null
                }
            }

            val formatted =
                CallTraceValue.vmCtfValueOrUnknown(
                    model.valueAsTACValue(key),
                    keyTypeDescriptor
                )

            return mkSarif {
                append(base.toDisplayString(formatter, model))
                append("[")
                append(formatted.toSarif(formatter, "key"))
                append("]")
            }
        }

        override fun toNonIndexedString(): String {
            return "${base.toNonIndexedString()}[*]"
        }

        override fun toString(): String {
            return "$base[k = $key]"
        }

        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): DisplayPath {
            return this.copy(key = f(key), base = base.transformSymbols(f))
        }

        override fun instantiateTACSymbols(model: CounterexampleModel, next: InstantiatedDisplayPath?): InstantiatedDisplayPath {
            val key = this.key.toIntegerOrThrow(model)
            val keyTyp = this.keyTyp
            val idp = InstantiatedDisplayPath.MapAccess(key, keyTyp, next)
            return this.base.instantiateTACSymbols(model, idp)
        }
    }

}

/**
 * A [DisplayPath] instantiated for specific model.
 * [InstantiatedDisplayPath] goes in reverse order, so if
 * x = [DisplayPath.FieldAccess] ([DisplayPath.ArrayAccess] ([DisplayPath.Root] ("balances"), 5), "b")
 * then it's [InstantiatedDisplayPath] is (-> points to [next])
 * [InstantiatedDisplayPath.Root] ("balances") -> [InstantiatedDisplayPath.ArrayAccess] (5) -> [InstantiatedDisplayPath.FieldAccess] ("b")
 */
@KSerializable
@Treapable
sealed class InstantiatedDisplayPath : AmbiSerializable, Comparable<InstantiatedDisplayPath> {
    abstract val next: InstantiatedDisplayPath?

    fun InstantiatedDisplayPath?.toFormattedString(formatter: CallTraceValueFormatter) : Sarif =
        this?.toFormattedString(formatter) ?: Sarif.fromPlainStringUnchecked("")

    abstract fun toFormattedString(formatter: CallTraceValueFormatter) : Sarif

    @KSerializable
    data class Root(val name: String, override val next: InstantiatedDisplayPath? = null) : InstantiatedDisplayPath() {
        override fun toFormattedString(formatter: CallTraceValueFormatter): Sarif = mkSarif {
            append(name)
            append(next.toFormattedString(formatter))
        }
    }

    @KSerializable
    data class FieldAccess(val field: String, override val next: InstantiatedDisplayPath? = null) : InstantiatedDisplayPath() {
        override fun toFormattedString(formatter: CallTraceValueFormatter): Sarif = mkSarif {
            append(".${field}")
            append(next.toFormattedString(formatter))
        }
    }

    @KSerializable
    data class ArrayAccess(
        val index: TACValue.PrimitiveValue.Integer,
        override val next: InstantiatedDisplayPath? = null
    ) : InstantiatedDisplayPath() {
        override fun toFormattedString(formatter: CallTraceValueFormatter): Sarif = mkSarif {
            append("[i = ")
            append(
                CallTraceValue.EVMType(
                    index,
                    EVMTypeDescriptor.UIntK(256)
                ).toSarif(formatter, "array access index")
            )
            append("]")
            append(next.toFormattedString(formatter))
        }
    }

    @KSerializable
    data class MapAccess(
        val key: TACValue.PrimitiveValue.Integer,
        val keyTyp: TACStorageType?,
        override val next: InstantiatedDisplayPath? = null
    ) : InstantiatedDisplayPath() {
        override fun toFormattedString(formatter: CallTraceValueFormatter): Sarif {
            val keyString = (keyTyp as? TACStorageType.IntegralType)?.descriptor?.let {
                CallTraceValue.VMType(key, it).toSarif(formatter, "map key")
            } ?: Sarif.fromPlainStringUnchecked("*")
            return mkSarif {
                append("[k = ")
                append(keyString)
                append("]")
                append(next.toFormattedString(formatter))
            }
        }
    }

    @KSerializable
    data class GhostAccess(
        val indices: List<Pair<TACValue, CVLType.PureCVLType>>,
        val sort: GhostSort,
        override val next: InstantiatedDisplayPath? = null
    ) : InstantiatedDisplayPath() {

        private fun GhostSort.formatSubscript(printable: List<CallTraceValue>, formatter: CallTraceValueFormatter) =
            when (this) {
                GhostSort.Function ->
                    printable.joinToSarif(separator = ", ", prefix = "(", postfix = ")") { idx, value ->
                        value.toSarif(formatter, "ghost param nr $idx")
                    }

                GhostSort.Mapping ->
                    printable.joinToSarif(separator = "][", prefix = "[", postfix = "]") { idx, value ->
                        value.toSarif(formatter, "ghost param nr $idx")
                    }

                GhostSort.Variable ->
                    throw IllegalArgumentException("ghost variables cannot be indexed")
            }

        override fun toFormattedString(formatter: CallTraceValueFormatter) =
            if (sort is GhostSort.Variable) {
                Sarif.fromPlainStringUnchecked("")
            } else {
                val evaluated = indices.map { (tv, cvlType) -> CallTraceValue.CVLType(tv, cvlType) }
                sort.formatSubscript(evaluated, formatter)
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) { return true }
            if (other !is GhostAccess) { return false }
            if (this.indices.size != other.indices.size) {
                return false
            }
            for (idx in 0 until this.indices.size) {
                // Don't take into account the [CVLType] of [indices], only the values.
                val t = this.indices[idx].first
                val o = other.indices[idx].first
                if (t != o) {
                    return false
                }
            }
            if (sort != other.sort) { return false }
            if (next != other.next) { return false }
            return true
        }

        override fun hashCode(): Int {
            var result: Int = sort.hashCode()
            result = 31 * result + next.hashCode()
            for (idx in 0 until this.indices.size) {
                // Don't take into account the [CVLType] of [indices], only the values.
                val t = this.indices[idx].first
                result = 31 * result + t.hashCode()
            }
            return result
        }

        /**
         * this function is only ever expected to be called from [InstantiatedDisplayPath.compareTo].
         * this means the ident of both ghosts has already been compared, and tested equal,
         * so the [indices] of the two ghosts must agree on everything except model value.
         */
        fun compareTo(other: GhostAccess): Int {
            if (this.indices.size != other.indices.size) {
                /** This can happen in the case we compare two GhostAccesses that are generated
                 * by the sum or usum syntax in CVL, the indices may not have the
                 * same length.
                 */
                return this.indices.size.compareTo(other.indices.size)
            }

            for (idx in 0 until this.indices.size) {
                val t = this.indices[idx].first
                val o = other.indices[idx].first

                val cmp = if (t is TACValue.PrimitiveValue && o is TACValue.PrimitiveValue) {
                    // we allow comparison between any two primitives here, because:
                    // 1) the implementation is easier
                    // 2) for an integer type, either of the two values may have been promoted to bool
                    // 3) it's not clear that being strict here is useful
                    t.asBigInt.compareTo(o.asBigInt)
                } else if (t is TACValue.SKey && o is TACValue.SKey) {
                    t.offset.compareTo(o.offset)
                } else if (t == TACValue.SumIndex && o == TACValue.SumIndex) {
                    0
                } else if (t == TACValue.SumIndex) {
                    1 // We consider the sum to always be greater than a specific-valued index
                } else if (o == TACValue.SumIndex) {
                    -1
                } else {
                    error("expected comparable TACValues at index $idx, but got this = ${t::class}, other = ${o::class}")
                }

                if (cmp != 0) {
                    return cmp
                }
            }

            return 0
        }
    }

    /**
     * Compare the names of two [InstantiatedDisplayPath] so they will be printed in lexicographic order.
     */
    override fun compareTo(other: InstantiatedDisplayPath): Int {
        var thisBase: InstantiatedDisplayPath? = this
        var otherBase: InstantiatedDisplayPath? = other

        while (thisBase != null && otherBase != null) {
            if (thisBase::class != otherBase::class) {
                throw UnsupportedOperationException(
                    "Incomparable state, $this and $other have same name but different types."
                )
            }

            if (thisBase is Root && otherBase is Root && thisBase.name != otherBase.name) {
                return thisBase.name.compareTo(otherBase.name)
            }

            if (thisBase is FieldAccess && otherBase is FieldAccess && thisBase.field != otherBase.field) {
                return thisBase.field.compareTo(otherBase.field)
            }

            if (thisBase is ArrayAccess && otherBase is ArrayAccess && thisBase.index != otherBase.index) {
                return thisBase.index.compareTo(otherBase.index)
            }

            if (thisBase is MapAccess && otherBase is MapAccess && thisBase.key != otherBase.key) {
                return thisBase.key.compareTo(otherBase.key)
            }

            if (thisBase is GhostAccess && otherBase is GhostAccess) {
                val cmp = thisBase.compareTo(otherBase)
                if (cmp != 0) {
                    return cmp
                }
            }

            thisBase = thisBase.next
            otherBase = otherBase.next
        }

        if (thisBase != null) {
            return 1
        }

        if (otherBase != null) {
            return -1
        }

        return 0
    }
}

/** deal with cases where a map/array has a bytes value as the index */
private fun TACSymbol.toIntegerOrThrow(model: CounterexampleModel): TACValue.PrimitiveValue.Integer {
    val tv = model.valueAsTACValue(this)
    return when {
        tv is ConcreteTacValue && this.tag == TACBuiltInFunction.Hash.skeySort -> {
            model
                .storageKeyToInteger(tv)
                .onRight { logger.error { "can't instantiate index $this of DisplayPath, expected SKey index to be hashable" } }
                .leftOrThrow()
        }
        tv is TACValue.PrimitiveValue.Integer -> tv
        else -> throw UnsupportedOperationException("can't instantiate index $this of DisplayPath, value has incompatible type")
    }
}
