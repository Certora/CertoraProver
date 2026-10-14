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

package report.calltrace

import analysis.storage.InstantiatedDisplayPath
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import evm.EVM_BYTE_SIZE
import evm.EVM_WORD_SIZE
import report.calltrace.StorageDisplayPathGenerator.SKeyParser.Root
import scene.ISceneIdentifiers
import spec.cvlast.typedescriptors.*
import tac.TACStorageType
import tac.Tag
import utils.`impossible!`
import utils.unused
import vc.data.ScalarizationSort
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.find
import vc.data.state.TACValue
import java.math.BigInteger

/**
 * A parser for skeys, does what it says on the tin.
 *
 * Given type/storage name information given in [scene], precomputes the "shape" skeys expected
 * for each possible path through storage. These "parsers" can then be applied to concrete skey values produced
 * by the counter example generation to recover the high-level display path represented by that skey.
 *
 * Note that without type/name information it is *impossible* to reverse the values of skeys in general.
 */
class StorageDisplayPathGenerator(val scene: ISceneIdentifiers) {

    /**
     * One of the core data structures of the skey parser. Describes (logically) how to traverse an skey (or more accurately,
     * a [StoragePath], which includes information for scalar slots). If the parse process successfully consumes an
     * [SKeyParser] up to the [Root], the parse will as a side effect produce an [InstantiatedDisplayPath] describing
     * the high-leval path through storage, in terms of solidity level fields and property names.
     *
     * Each class also describes how to consume parts of a [StoragePath], the internal representation for a path through
     * storage. The consumption (and by extension, the expected shape of the [StoragePath]) are described with each class.
     */
    sealed class SKeyParser {
        /**
         * Indicates that the SKey should terminate at root at slot [which]. The label of this slot is
         * given by [label], and the sub-word offset (e.g., for packing) and width if this root slot represents
         * a primitive type are given in [subWordOffset] and [width] respective.
         *
         * After consuming [subWordOffset] bytes and [which] slots, if the [StoragePath] has not been fully consumed
         * (i.e., it's [StoragePath.slotOffset] and [StoragePath.subWordOffset] are not both 0) then the parse fails, otherwise
         * if it is fully consumed (that is [StoragePath.parentIfFullyConsumed] returns non-null) and the storage root
         * is [StorageParent.Root], then the parse succeeds, beginning the parsed display path with the label [label].
         */
        data class Root(
            val which: BigInteger,
            val label: String,
            val subWordOffset: Int?
        ) : SKeyParser()

        /**
         * Indicates to expect that the current storage path has been completely consumed (that is, [StoragePath.subWordOffset] and [StoragePath.slotOffset]
         * are both 0, and thus [StoragePath.parentIfFullyConsumed] is non-null). Further, the current [StoragePath] should
         * represent a two word hash of a map key and slot, that is, the [StorageParent] returned by [StoragePath.parentIfFullyConsumed]
         * should be [StorageParent.Map].
         *
         * If this parse matches, a map lookup whose key type is given by [keyType] is appended onto the successful parse
         * of [root], if any. The actual value of the key is extracted during the parse.
         */
        data class MapHash(
            val root: SKeyParser,
            val keyType: TACStorageType
        ) : SKeyParser()

        /**
         * As with the [MapHash] case, expects a fully consumed [StoragePath] whose parent is [StorageParent.Array].
         *
         * The actual index is computed during the parse, appended onto the successful parse of [root] (if any)
         * using the [elementSizeInWords] field to determine the logical index given the "physical" offset in a [StoragePath].
         */
        data class ArrayHash(
            val root: SKeyParser,
            val elementSizeInWords: BigInteger
        ) : SKeyParser()

        /**
         * Expects a [StoragePath] with at least [subWordOffset] remaining. If [slot] is non-zero, then
         * further expects that after consuming [subWordOffset], at least [slot] words remain in [StoragePath.slotOffset].
         *
         * If both are true, the sub word and slot offsets are consumed, and the field [name] is appended onto the successful
         * parse produced for [root] (if any).
         */
        data class Field(
            val root: SKeyParser,
            val slot: BigInteger,
            val subWordOffset: Int,
            val name: String
        ) : SKeyParser()

        /**
         * The "terminal" point of the parse, indicating that the parse should first check if it is consuming a value
         * with bitwidth [width]. Then the path of primitive value being accessed is that produced by a successful
         * parse of [root] (if any).
         */
        data class Primitive(
            val root: SKeyParser,
            val width: Int,
        ) : SKeyParser()

        /**
         * Expects that the [StoragePath.subWordOffset] has zero bytes remaining, and that the [StoragePath.subWordOffset]
         * is between [staticStart] and [staticStart] + [numElements] * [elementSize] (in words).
         *
         * If so, the number of slots indicating the offset to the start of the static array are consumed,
         * and the logical index (computed as ([StoragePath.slotOffset] - [staticStart]) / [elementSize]) is appended
         * as an array access onto the path produced by a successful parse of [root] (if any).
         */
        data class StaticArray(
            val staticStart: BigInteger,
            val elementSize: BigInteger,
            val numElements: BigInteger,
            val root: SKeyParser
        ) : SKeyParser()
    }

    private class PackedStaticArrayException : RuntimeException()

    private fun TACStorageType.sizeInBytes() : BigInteger = when(this) {
        is TACStorageType.IntegralType -> this.numBytes
        is TACStorageType.Array,
        TACStorageType.Bytes,
        is TACStorageType.Mapping -> EVM_WORD_SIZE
        is TACStorageType.StaticArray -> {
            if(elementSizeInBytes < EVM_WORD_SIZE) {
                throw PackedStaticArrayException()
            }
            elementSizeInBytes * numElements
        }
        is TACStorageType.Struct -> {
            this.members.values.sumOf {
                it.type.sizeInBytes()
            }
        }
    }

    /**
     * Traverses a [typ] and [desc] "in parallel", producing [SKeyParser] instances.
     * As the type is traversed towards the leaves (that is the primitives) the [curr] parameter is built up from
     * along each step according to type constructor being considered. Thus, the non-recursive entry point to this function
     * will always pass an instance of [SKeyParser.Root] to as [curr].
     *
     * The [fieldOffset] parameter is passed through only to help compute the offset of the static array.
     *
     * NB: This does not work even a little bit in the presence of packed static arrays. Luckily, unpacking and storage
     * both don't work on them either, but ever they do, this will throw a [PackedStaticArrayException]
     * terminating the parse production.
     */
    private fun toSkeyParser(
        typ: TACStorageType,
        desc: VMTypeDescriptor,
        curr: SKeyParser,
        fieldOffset: BigInteger
    ) : List<SKeyParser.Primitive> {
        return when(typ) {
            is TACStorageType.Array -> {
                if(desc !is VMDynamicArrayTypeDescriptor) {
                    return datastructures.stdcollections.listOf()
                }
                val elementSizeInRoundedUpWords = (typ.elementType.sizeInBytes() + 31.toBigInteger()).andNot(31.toBigInteger())
                toSkeyParser(typ.elementType, desc.elementType, SKeyParser.ArrayHash(
                    root = curr, elementSizeInWords = elementSizeInRoundedUpWords / EVM_WORD_SIZE
                ), BigInteger.ZERO
                ) + SKeyParser.Primitive(
                    root = SKeyParser.Field(
                        root = curr,
                        name = "length",
                        subWordOffset = 0,
                        slot = BigInteger.ZERO
                    ),
                    width = EVM_BITWIDTH256
                )
            }
            TACStorageType.Bytes -> {
                // TO get to DO later(jtoman): this is not correct?
                datastructures.stdcollections.listOf(
                    SKeyParser.Primitive(
                        width = EVM_BYTE_SIZE.intValueExact(),
                        root = SKeyParser.ArrayHash(
                            elementSizeInWords = BigInteger.ONE,
                            root = curr
                        )
                    )
                )
            }
            is TACStorageType.IntegralType -> {
                if(desc !is VMValueTypeDescriptor) {
                    return datastructures.stdcollections.listOf()
                }
                if(desc.bitwidth != typ.numBits) {
                    return datastructures.stdcollections.listOf()
                }
                datastructures.stdcollections.listOf(
                    SKeyParser.Primitive(
                        root = curr,
                        width = typ.numBits
                    )
                )
            }
            is TACStorageType.Mapping -> {
                if(desc !is VMMappingDescriptor) {
                    return datastructures.stdcollections.listOf()
                }
                toSkeyParser(typ.valueType, desc.valueType, SKeyParser.MapHash(
                    root = curr, keyType = typ.keyType
                ), BigInteger.ZERO
                )
            }
            is TACStorageType.StaticArray -> {
                if(desc !is VMStaticArrayType) {
                    return datastructures.stdcollections.listOf()
                }
                if(typ.elementType.sizeInBytes() < EVM_WORD_SIZE) {
                    throw PackedStaticArrayException()
                }
                toSkeyParser(
                    typ.elementType,
                    desc.elementType,
                    SKeyParser.StaticArray(
                        elementSize = typ.elementSizeInWords,
                        root = curr,
                        staticStart = fieldOffset,
                        numElements = desc.numElements
                    ),
                    BigInteger.ZERO
                )
            }
            is TACStorageType.Struct -> {
                val toRet = mutableListOf<SKeyParser.Primitive>()
                if(desc !is VMStructDescriptor) {
                    return datastructures.stdcollections.listOf()
                }
                for((name, f) in typ.members) {
                    val fieldDesc = desc.fieldTypes[name] ?: return datastructures.stdcollections.listOf()
                    if(fieldDesc is VMStaticArrayType && f.offset != 0) {
                        return datastructures.stdcollections.listOf()
                    }
                    toRet.addAll(
                        toSkeyParser(
                            f.type,
                            curr = SKeyParser.Field(
                                root = curr,
                                subWordOffset = f.offset * EVM_BYTE_SIZE.intValueExact(),
                                slot = f.slot,
                                name = name
                            ),
                            desc = fieldDesc,
                            fieldOffset = fieldOffset + f.slot
                        )
                    )
                }
                toRet
            }
        }
    }

    /**
     * Associates with each contract instance id the set of skey parsers. The actual mapping from Skey to display path is
     * done very stupidly: we try all of them, assuming only zero or one will match.
     */
    private val contractSkeyParsers : Map<BigInteger, Set<SKeyParser.Primitive>>

    init {
        val contractParsers = datastructures.stdcollections.mutableMapOf<BigInteger, Set<SKeyParser.Primitive>>()

        for(c in scene.getContracts()) {
            val s = c.getStorageLayout()?.slots ?: continue
            val parsers = datastructures.stdcollections.mutableSetOf<SKeyParser.Primitive>()
            try {
                for ((root, typ) in s) {
                    val fqRoot = "${c.name}.$root"
                    if(typ.storageType == null || typ.typeDescriptor == null) {
                        continue
                    }
                    val subWordOffset = typ.offset
                    val slot = typ.slot

                    val parserRoot = SKeyParser.Root(
                        label = fqRoot,
                        which = typ.slot,
                        subWordOffset = subWordOffset
                    )
                    parsers.addAll(
                        toSkeyParser(
                            typ = typ.storageType!!,
                            desc = typ.typeDescriptor!!,
                            fieldOffset = slot,
                            curr = parserRoot
                        )
                    )
                }
            } catch(e: PackedStaticArrayException) {
                unused(e)
                continue
            }
            contractParsers[c.instanceId] = parsers
        }
        contractSkeyParsers = contractParsers
    }

    /**
     * The actual algorithm which walks the [StoragePath] [s] and [SKeyParser] [parser] "in parallel", incrementally building
     * up the [InstantiatedDisplayPath] along the way. A failed parse is indicated by this function returning null: if [next] is null
     * this simply indicates that there is no "next" step in the display path yet (which is the case for non-recursive invocations of this function).
     */
    private fun recursivelyBuildDisplayPath(next: InstantiatedDisplayPath?, s: StoragePath, parser: SKeyParser) : InstantiatedDisplayPath? {
        return when(parser) {
            is SKeyParser.ArrayHash -> {
                /*
                 If the remaining offset is not a multiple of the element size, we don't have a successful parse, we
                 haven't fully consumed whatever element yet
                 */
                if(s.slotOffset.mod(parser.elementSizeInWords) != BigInteger.ZERO) {
                    return null
                }
                val logicalIndex = s.slotOffset / parser.elementSizeInWords
                /*
                  Consume all the slots back to the root (expected to be an array)
                 */
                s.consumeSlots(s.slotOffset)?.parentIfFullyConsumed?.let { it as? StorageParent.Array }?.rootPath?.let { nextS ->
                    recursivelyBuildDisplayPath(
                        next = InstantiatedDisplayPath.ArrayAccess(
                            next = next,
                            index = TACValue.PrimitiveValue.Integer(logicalIndex)
                        ),
                        s = nextS,
                        parser = parser.root
                    )
                }
            }
            is SKeyParser.Field -> {
                s.consumeWordOffset(parser.subWordOffset)?.consumeSlots(parser.slot)?.let {sNext ->
                    recursivelyBuildDisplayPath(
                        next = InstantiatedDisplayPath.FieldAccess(
                            next = next,
                            field = parser.name
                        ),
                        s = sNext,
                        parser = parser.root
                    )
                }
            }
            is SKeyParser.MapHash -> {
                s.parentIfFullyConsumed?.let {
                    it as? StorageParent.Map
                }?.let {
                    recursivelyBuildDisplayPath(
                        next = InstantiatedDisplayPath.MapAccess(
                            next = next,
                            key = TACValue.PrimitiveValue.Integer(
                                value = it.key
                            ),
                            keyTyp = parser.keyType
                        ),
                        s = it.rootPath,
                        parser = parser.root
                    )
                }
            }
            is SKeyParser.Primitive -> {
                recursivelyBuildDisplayPath(
                    next, s, parser.root
                )
            }
            is SKeyParser.Root -> {
                val subWord = if(parser.subWordOffset != null) {
                    s.consumeWordOffset(parser.subWordOffset) ?: return null
                } else {
                    s
                }
                if(subWord.consumeSlots(f = parser.which)?.parentIfFullyConsumed != StorageParent.Root) {
                    return null
                }
                return InstantiatedDisplayPath.Root(
                    name = parser.label,
                    next = next
                )
            }
            is SKeyParser.StaticArray -> {
                /*
                 Do we lie within the range of the static array recorded in the skey parser
                 */
                if (s.slotOffset < parser.staticStart || s.slotOffset >= parser.staticStart + parser.elementSize * parser.numElements) {
                    return null
                }
                val offsetWithinStatic = s.slotOffset - parser.staticStart
                /*
                 Check for alignment
                 */
                if(offsetWithinStatic.mod(parser.elementSize) != BigInteger.ZERO) {
                    return null
                }
                val logicalIndex = offsetWithinStatic / parser.elementSize
                /*
                  Consume the slots leading up to the start of the static array and then recurse
                 */
                s.consumeSlots(offsetWithinStatic)?.let { sNext ->
                    recursivelyBuildDisplayPath(
                        next = InstantiatedDisplayPath.ArrayAccess(
                            index = TACValue.PrimitiveValue.Integer(logicalIndex),
                            next = next
                        ),
                        sNext, parser.root
                    )
                }
            }
        }
    }

    /**
     * The basic abstraction for a path through storage. Each storage path has some (potentially 0)
     * subword offset (represented by [subWordOffset]) within a slot,
     * and that slot's offset from its "parent" is given by [slotOffset]. The parent of storage path
     * is either the conceptual [StorageParent.Root], or an application of a hash, representing an
     * array or map access, represented by [StorageParent.Map] and [StorageParent.Array] respectively.
     *
     * Each storage path can have it's slot and offset information "consumed" via the [consumeSlots] and [consumeWordOffset]
     * functions. Both of these functions return null if there is not enough slots or offsets to be consumed. A [StoragePath]
     * is said to be fully consumed if [subWordOffset] and [slotOffset] are both 0, that is, the storage path now "points"
     * to the parent location. If so, the aptly named [parentIfFullyConsumed] returns the [StorageParent] object indicating
     * the type of parent for this storage path.
     */
    private interface StoragePath {
        val subWordOffset: Int
        val slotOffset: BigInteger

        val parentIfFullyConsumed: StorageParent?

        fun consumeWordOffset(f: Int) : StoragePath?
        fun consumeSlots(f: BigInteger) : StoragePath?
    }

    /**
     * Representation for the parent of a storage path.
     */
    private sealed interface StorageParent {
       /** indicates that the parent of the current path was an array access: the
        * path to the array access is given by [rootPath].
        */
        interface Array : StorageParent {
            val rootPath: StoragePath
        }

        /**
         * Indicates that the parent of the current path was a map access, the path to the map given by
         * [rootPath]. Further, the raw value of the key used in the map is given by [key]
         */
        interface Map : StorageParent {
            val rootPath: StoragePath
            val key: BigInteger
        }
        /**
         * The conceptual "root" of storage, this parent indicates that the entirety of the storage
         * path has been consumed.
         */
        object Root : StorageParent
    }


    /**
     * A storage path that represents a scalarized slot and the "roots" of skeys.
     * When used for a scalarized slot, the [slotOffset] and [subWordOffset] information is extracted from the [ScalarizationSort] meta attached the variable.
     * (NB Representing these scalars
     * with this representation means that when dealing with flattened structs at the root, we can use the exact
     * same logic as would be used for such a struct within a map/array/etc.)
     *
     * For a [SKey.Basic] value, the [slotOffset] is simply the [SKey.Basic.offset].
     */
    private data class AtRoot(
        override val subWordOffset: Int,
        override val slotOffset: BigInteger
    ) : StoragePath {
        override val parentIfFullyConsumed: StorageParent?
            get() = if(subWordOffset == 0 && slotOffset == BigInteger.ZERO) {
                StorageParent.Root
            } else {
                null
            }


        override fun consumeWordOffset(f: Int): StoragePath? {
            if(f > subWordOffset) {
                return null
            }
            return this.copy(subWordOffset = subWordOffset - f)
        }

        override fun consumeSlots(f: BigInteger): StoragePath? {
            if(f > slotOffset) {
                return null
            }
            return this.copy(slotOffset = slotOffset - f)
        }
    }

    companion object {
        /**
         * Lift an [SKey] into a [StoragePath], including the [subWordOffset] information
         * (extracted form the [ScalarizationSort] meta attached to the wordmap from which this
         * skey was read
         */
        private fun tacValueToStoragePath(v: SKey, subWordOffset: Int) : StoragePath {
            return when(v) {
                is SKey.Basic -> {
                    AtRoot(
                        slotOffset = v.offset,
                        subWordOffset = subWordOffset
                    )
                }
                is SKey.Node -> {
                    Skey(
                        wrapped = v.children,
                        subWordOffset = subWordOffset,
                        slotOffset = v.offset
                    )
                }
            }
        }
    }

    private data class Skey(
        val wrapped: List<SKey>,
        override val slotOffset: BigInteger,
        override val subWordOffset: Int
    ) : StoragePath {
        override val parentIfFullyConsumed: StorageParent?
            get() {
                return if(this.slotOffset != BigInteger.ZERO || this.subWordOffset != 0) {
                    null
                } else {
                    when(wrapped.size) {
                        /**
                         * The expected format of skey applications are:
                         * For arrays:
                         * [32, array]
                         * where `array` is itself an `SKey`
                         */
                        2 -> {
                            val sz = wrapped[0]
                            val baseKey = wrapped[1]
                            if(sz.extractBasicConst() != EVM_WORD_SIZE) {
                                return null
                            }
                            val base = tacValueToStoragePath(baseKey, 0)
                            object : StorageParent.Array {
                                override val rootPath: StoragePath
                                    get() = base
                            }
                        }
                        /**
                         * For maps, it is
                         * [64, key, map]
                         * where `key` is expected to be a number (that is, [SKey.Basic]), and map is
                         * itself an instance of [SKey]
                         */
                        3 -> {
                            val sz = wrapped[0]
                            val key = wrapped[1]
                            val baseMap = wrapped[2]
                            if(sz.extractBasicConst() != EVM_WORD_SIZE * BigInteger.TWO) {
                                return null
                            }
                            val rawKey = key.extractBasicConst() ?: return null
                            val base = tacValueToStoragePath(baseMap, 0)
                            object : StorageParent.Map {
                                override val rootPath: StoragePath
                                    get() = base
                                override val key: BigInteger
                                    get() = rawKey
                            }
                        }
                        else -> null
                    }
                }
            }

        override fun consumeWordOffset(f: Int): StoragePath? {
            if(f > subWordOffset) {
                return null
            }
            return this.copy(subWordOffset = this.subWordOffset - f)
        }

        override fun consumeSlots(f: BigInteger): StoragePath? {
            if(f > slotOffset) {
                return null
            }
            return this.copy(slotOffset = this.slotOffset - f)
        }
    }

    fun displayPathFor(id: BigInteger, slotVar: TACSymbol.Var) : InstantiatedDisplayPath? {
        val sort = slotVar.meta.find(TACMeta.SCALARIZATION_SORT) ?: return null
        val width = slotVar.meta.find(TACMeta.BIT_WIDTH) ?: return null
        check((sort is ScalarizationSort.Packed && sort.packedStart is ScalarizationSort.Split) || sort is ScalarizationSort.Split)
        return displayPathFor(
            id, AtRoot(
                subWordOffset = sort.subWordOffset,
                slotOffset = when (sort) {
                    is ScalarizationSort.Packed -> (sort.packedStart as ScalarizationSort.Split).idx
                    is ScalarizationSort.Split -> sort.idx
                    ScalarizationSort.UnscalarizedBuffer -> `impossible!`
                }
            ),
            width
        )
    }

    fun displayPathFor(id: BigInteger, s: SKey, baseVar: TACSymbol.Var) : InstantiatedDisplayPath? {
        check(baseVar.tag == Tag.WordMap)
        val width = baseVar.meta.find(TACMeta.BIT_WIDTH) ?: return null
        val sort = baseVar.meta.find(TACMeta.SCALARIZATION_SORT) ?: return null
        return displayPathFor(
            id, tacValueToStoragePath(s, sort.subWordOffset), width
        )
    }

    private fun displayPathFor(id: BigInteger, start: StoragePath, width: Int) : InstantiatedDisplayPath? {
        val res = mutableListOf<InstantiatedDisplayPath>()
        val parsers = contractSkeyParsers[id] ?: return null
        for(p in parsers) {
            if (width != p.width) {
                continue
            }
            res.add(recursivelyBuildDisplayPath(
                next = null,
                s = start,
                parser = p
            ) ?: continue)
        }
        return res.singleOrNull()
    }

    private val ScalarizationSort.subWordOffset : Int get() = if(this is ScalarizationSort.Packed) {
        this.start
    } else {
        0
    }
}

private fun SKey.extractBasicConst() : BigInteger? {
    if(this !is SKey.Basic) {
        return null
    }
    return this.offset
}
