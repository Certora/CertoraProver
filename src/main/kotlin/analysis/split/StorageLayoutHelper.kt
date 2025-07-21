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

package analysis.split

import analysis.storage.DisplayPath
import analysis.storage.StorageAnalysis.Base
import analysis.storage.StorageAnalysis.AnalysisPath
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import analysis.storage.StoragePath
import bridge.types.PrimitiveType
import bridge.types.SolidityTypeDescription
import config.Config
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import evm.EVM_BYTE_SIZE
import log.*
import scene.IContractClass
import tac.StructField
import tac.TACStorageLayout
import tac.TACStorageType
import vc.data.TACSymbol
import vc.data.asTACSymbol
import java.math.BigInteger

private val logger = Logger(LoggerTypes.STORAGE_SPLITTING)

/**
 * Utilizes the storage layout we get from the solidity compiler to generate the expected packing of a slot according to
 * it, and to transform an [AnalysisPath] to a [DisplayPath].
 */
class StorageLayoutHelper(private val contract: IContractClass, private val showWarnings: Boolean) {

    private val alreadyWarned = mutableSetOf<String>()

    fun warn(msg: () -> String) {
        if (showWarnings) {
            val str = msg()
            if (!alreadyWarned.add(str)) {
                logger.warn { str }
            }
        }
    }

    /** A view of the storage layout as a struct */
    private val topLevel: Map<String, StructField>? =
        contract.getStorageLayout()?.toStruct()

    /** A view of the transient storage layout as a struct */
    private val topLevelTransient: Map<String, StructField>? =
        contract.getTransientStorageLayout()?.toStruct()

    private fun TACStorageLayout.toStruct() =
        this.entries.filter { (name, storage) ->
            // Not sure this can happen anymore.
            if (storage.storageType == null) {
                warn { "Storage layout has no type for $name" }
            }
            storage.storageType != null
        }.associate { (name, storage) ->
            name to StructField(storage.storageType!!, storage.slot, storage.offset)
        }

    private val Base.topLevel: Map<String, StructField>?
        get() = when (this) {
            Base.STORAGE -> this@StorageLayoutHelper.topLevel
            Base.TRANSIENT_STORAGE -> topLevelTransient
        }

    /** A utility class for constructing the [DisplayPath] while traversing the given analysis path and storage layout */
    private class DisplayPathBuilder {
        var displayPath: DisplayPath? = null

        fun update(next: (DisplayPath) -> DisplayPath) {
            displayPath = next(displayPath!!)
        }

        fun array(index: TACSymbol?) {
            val i = index ?: 0xbad00bad.toBigInteger().asTACSymbol()
            update { DisplayPath.ArrayAccess(i, it) }
        }

        fun map(key: TACSymbol, keyType: TACStorageType?) =
            update { DisplayPath.MapAccess(key, keyType, it) }

        fun field(name: String) =
            if (displayPath == null) {
                displayPath = DisplayPath.Root(name)
            } else {
                update { DisplayPath.FieldAccess(name, it) }
            }
    }

    /** Used as the return value from the recursive traversal functions */
    private data class TypeAndInfo(val type: TACStorageType, val name: String? = null, val offset: Int = 0)

    private fun <T> T?.pairWithFalse() = this?.to(false)

    /**
     * The main recursive traversal method which compares [path] to what we except to see from the json. At each step,
     * it first calls the recursion on the base of this path, eventually reaching the root. Then, the [topLevel] is
     * consulted to give [TACStorageType] - which is a recursive structure much like path, except reversed. Therefore,
     * on going back up the recursion, the returned type is compared to the one in [path]. If there is any mismatch, a
     * null is returned.
     *
     * An important difference between the two representations is that [AnalysisPath] does not know of nested structs, and they
     * are flattened. Also, it contains auxiliary structs which do not correspond to any real struct in terms of solidity.
     *
     * Along the way [display] accumulates the intermediate results so that if a [DisplayPath] is needed, we have it
     * there (as long as no problem occurred). Importantly, the last struct access is not recorded in [display], but
     * as we don't know at this point exactly where in the slot we are accessing, the return value holds all possibility.
     * This is actually the only reason this function may return a non-singleton.
     *
     * The boolean return value is almost always false, except when [path] is a struct,
     * but it is superfluous, and there is no real corresponding struct in the storage layout. It's needed for
     * a very specific differentiation:
     *   + an array of small elements, i.e., uint32[] a;
     *   + an array of structs containing just one small element, i.e.,
     *      struct S { uint32 x };
     *      S[] a;
     */
    private fun traverse(path: StoragePath, display: DisplayPathBuilder?): Pair<List<TypeAndInfo>, Boolean>? =
        when (path) {

            is StoragePath.Root ->
                decomposeStruct("top level layout", path.base.topLevel!!, path.slot, display).pairWithFalse()

            is StoragePath.ArrayAccess ->
                recurse<TACStorageType.Array>(path.base, display) {
                    if (path is StoragePath.ArrayAccess.Analysis) {
                        display?.array(path.index)
                    }
                    it.elementType
                }

            is StoragePath.StaticArrayAccess ->
                recurse<TACStorageType.StaticArray>(path.base, display) {
                    if (path is StoragePath.StaticArrayAccess.Analysis) {
                        display?.array(path.index)
                    }
                    it.elementType
                }

            is StoragePath.MapAccess ->
                recurse<TACStorageType.Mapping>(path.base, display) {
                    if (path is StoragePath.MapAccess.Analysis) {
                        display?.map(path.key, it.keyType)
                    }
                    it.valueType
                }

            is StoragePath.StructAccess ->
                traverse(path.base, display)?.let { (list, _) ->
                    val struct = list.singleOrNull()?.type
                    when {
                        struct is TACStorageType.Struct ->
                            decomposeStruct(struct.label, struct.members, path.offset, display).pairWithFalse()

                        path.offset != BigInteger.ZERO ->
                            error(
                                "Inconsistency in Storage Analysis vs what solc reports, ${path.offset} is non " +
                                    "zero within $struct"
                            )

                        else ->
                            list to true // structs many times appear superfluously in analysis paths.
                    }
                }
        }


    /**
     * Used just to deduplicate code in [traverse]. All this does is call traverse on [base], checks the resulting
     * type is indeed as expected (T), gets the next expected storage type via [f] and wraps it ready to send back up
     * the recursion.
     */
    private inline fun <reified T : TACStorageType> recurse(
        base: StoragePath,
        display: DisplayPathBuilder?,
        f: (T) -> TACStorageType
    ): Pair<List<TypeAndInfo>, Boolean>? {
        val x = traverse(base, display)
            ?: return null.pairWithFalse()
        val t = x.first.single().type as? T
            ?: run {
                warn {
                    "In $contract - a mismatch between storage analysis and expected storage layout: $base"
                }
                return null.pairWithFalse()
            }
        return listOf(TypeAndInfo(f(t))).pairWithFalse()
    }


    /**
     * Goes through nested structs until it finds what type is at that slot, and returns that type. It returns null
     * if something does not fit. See [traverse] for more details.
     *
     * N.B. the struct may actually be the top level layout.
     */
    private fun decomposeStruct(
        label: String?,
        struct: Map<String, StructField>,
        slot: BigInteger,
        display: DisplayPathBuilder?
    ): List<TypeAndInfo>? {
        val fields = struct.entries
        val startSlot = fields
            .map { (_, info) -> info.slot }
            .filter { it <= slot }
            .maxOfOrNull { it }

        fun warnMissing(): Nothing? {
            // high slots mean that storage access was done directly in code, and so solc doesn't have
            // the storage information for them, but we don't want to warn.
            if (slot < Config.MaxBaseStorageSlot.get()) {
                warn { "In $contract, didn't find a type at offset $slot within $label" }
            }
            return null
        }

        if (startSlot == null) {
            return warnMissing()
        }

        val fieldsAtSlot = fields.filter { (_, info) -> info.slot == startSlot }

        if (fieldsAtSlot.all { it.value.type is TACStorageType.IntegralType }) {
            // our slot is larger then the info we got.
            if (startSlot != slot) {
                return warnMissing()
            }
            // we don't add the last element to the display path, it will be added later.
            return fieldsAtSlot.map { (name, info) ->
                TypeAndInfo(info.type, name, info.offset * EVM_BYTE_SIZE.intValueExact())
            }
        }
        val (name, info) = fieldsAtSlot.single()
        display?.field(name)
        val type = info.type
        return when {
            type is TACStorageType.Struct ->
                decomposeStruct(type.label, type.members, slot - startSlot, display)

            startSlot < slot ->
                warnMissing()

            else ->
                listOf(TypeAndInfo(type))
        }
    }


    /**
     * The expected split corresponding to this [path], if analysis does not work, than the Split is empty, basically
     * meaning that it will be ignored.
     */
    fun expectedSplit(path: NonIndexedPath) = path.storageBase().topLevel?.let {
        traverse(StoragePath(path), null)
            ?.let { (typeList, noOuterStruct) ->
                when {
                    typeList.size == 1 && typeList.single().type !is TACStorageType.IntegralType ->
                        Split.all

                    path is NonIndexedPath.StructAccess && path.base is NonIndexedPath.ArrayLikePath && noOuterStruct ->
                        Split.repeated((typeList.single().type as TACStorageType.IntegralType).numBits)

                    else ->
                        Split(typeList.map { (type, _, offset) ->
                            BitRange(offset, offset + (type as TACStorageType.IntegralType).numBits)
                        })
                }
            }
    } ?: Split.none


    sealed class ArrayInfo {
        data object Not : ArrayInfo()
        data object Unknown : ArrayInfo()
        data class Packed(val width: Int) : ArrayInfo()

        fun join(other: ArrayInfo) =
            when {
                this == other -> this
                this is Not || other is Not -> Not
                this is Unknown -> other
                other is Unknown -> this
                else -> {
                    check(this is Packed && other is Packed)
                    Not
                }
            }
    }

    fun widthOfPackedArray(path: NonIndexedPath) =
        if (path is NonIndexedPath.StructAccess && path.base is NonIndexedPath.ArrayLikePath) {
            path.storageBase().topLevel?.let {
                traverse(StoragePath(path), null)
                    ?.let { (typeList, noOuterStruct) ->
                        if (noOuterStruct) {
                            val t = typeList.single().type
                            if (t is TACStorageType.IntegralType) {
                                val width = t.numBits
                                if (width <= EVM_BITWIDTH256 / 2) {
                                    ArrayInfo.Packed(width)
                                } else {
                                    ArrayInfo.Not
                                }
                            } else {
                                ArrayInfo.Not
                            }
                        } else {
                            ArrayInfo.Not
                        }
                    }
            } ?: ArrayInfo.Unknown
        } else {
            ArrayInfo.Not
        }

    private fun lastType(path: StoragePath, range: BitRange.NonEmpty, display: DisplayPathBuilder?): TypeAndInfo? =
        traverse(path, display)
            ?.first
            ?.filter { (_, _, offset) -> offset == range.lowBit }
            ?.singleOrNull { (type, _, _) ->
                when (type) {
                    is TACStorageType.IntegralType -> range.width == type.numBits
                    else -> range == BitRange.all
                }
            }

    /** returns the [PrimitiveType] corresponding to [range] within the last slot of [path] */
    fun integralTypeAt(path: NonIndexedPath, range: BitRange.NonEmpty) = path.storageBase().topLevel?.let {
        lastType(StoragePath(path), range, null)
            ?.type
            ?.let {
                when (it) {
                    is TACStorageType.IntegralType ->
                        SolidityTypeDescription.builtinPrimitive(it.typeLabel)

                    is TACStorageType.Array, is TACStorageType.StaticArray ->
                        SolidityTypeDescription.Primitive(PrimitiveType.uint256)

                    else -> null
                }
            }
    }

    /** returns the [DisplayPath] corresponding to [range] within the last slot of [path] */
    fun toDisplayPath(path: AnalysisPath, range: BitRange.NonEmpty) = path.storageBase().topLevel?.let {
        val display = DisplayPathBuilder()
        val (_, name, _) = lastType(StoragePath(path), range, display) ?: return null
        if (name != null) {
            display.field(name)
        }
        display.displayPath
    }


}


