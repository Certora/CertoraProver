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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)
package analysis.storage

import algorithms.UnionFind
import analysis.*
import analysis.dataflow.IDefAnalysis
import analysis.numeric.*
import analysis.numeric.linear.*
import analysis.numeric.linear.TermMatching.matches
import analysis.opt.ConstantComputationInliner
import analysis.pta.AnalysisFailureException
import analysis.pta.ITERATION_VARIABLE_BOUND
import analysis.worklist.*
import com.certora.collect.*
import config.Config
import datastructures.ProjectedMap
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import log.*
import parallel.*
import parallel.ParallelPool.Companion.runInherit
import scene.IContractClass
import scene.IContractWithSource
import scene.MethodAttribute
import statistics.ANALYSIS_STORAGE_SUBKEY
import statistics.ANALYSIS_SUCCESS_STATS_KEY
import statistics.recordSuccess
import tac.*
import utils.*
import vc.data.*
import java.io.Serializable
import java.math.BigInteger
import java.util.stream.Collector

private val logger = Logger(LoggerTypes.STORAGE_ANALYSIS)
private const val STATISTICS_KEY = ANALYSIS_SUCCESS_STATS_KEY

private sealed class InternalStorageType {
    abstract fun sizeInWords(): BigInteger

    data class Array(val of: InternalStorageType) : InternalStorageType() {
        override fun sizeInWords(): BigInteger {
            return BigInteger.ONE
        }
    }

    data class Struct(val members: Map<BigInteger, InternalStorageType>) : InternalStorageType() {
        override fun sizeInWords(): BigInteger {
            return members.values.sumOf { it.sizeInWords() }
        }
    }

    data class Mapping(val keyType: InternalStorageType, val codom: InternalStorageType) : InternalStorageType() {
        override fun sizeInWords(): BigInteger {
            return BigInteger.ONE
        }
    }

    data class StaticArray(val constantSize: BigInteger, val elemWordSize: BigInteger, val of: InternalStorageType) : InternalStorageType() {
        override fun sizeInWords(): BigInteger {
            return constantSize*elemWordSize
        }
    }

    object Word : InternalStorageType() {
        override fun sizeInWords(): BigInteger {
            return BigInteger.ONE
        }
    }

    data class IntegerWithBounds(val valueRange: IntValue) : InternalStorageType() {
        override fun sizeInWords(): BigInteger {
            return BigInteger.ONE
        }
    }
}

private fun flattenStructMembers(
    out: MutableMap<BigInteger, InternalStorageType>,
    startSlot: BigInteger,
    member: Collection<StructField>
) {
    for(m in member) {
        val effectiveSlot = m.slot + startSlot
        if(m.type is TACStorageType.Struct) {
            flattenStructMembers(out, effectiveSlot, (m.type as TACStorageType.Struct).members.values)
        } else if(m.offset != 0) {
            out.merge(effectiveSlot, InternalStorageType.Word) { a, b ->
                if(a != b) {
                    throw StorageAnalysisFailedException("Tried merging $m into flattened slot $effectiveSlot, found $b & $a", null)
                } else {
                    a
                }
            }
        } else {
            out[effectiveSlot] = m.type.toInternalType()
        }
    }
}

private fun TACStorageType.toInternalType() : InternalStorageType = when(this) {
    is TACStorageType.Array -> InternalStorageType.Array(
        of = this.elementType.toInternalType()
    )
    TACStorageType.Bytes -> InternalStorageType.Array(InternalStorageType.Word)
    is TACStorageType.IntegralType -> {
        if(this.lowerBound != null || this.upperBound != null) {
            InternalStorageType.IntegerWithBounds(IntValue.Interval(this.lowerBound, this.upperBound))
        } else {
            InternalStorageType.Word
        }
    }
    is TACStorageType.Mapping -> InternalStorageType.Mapping(
        codom = this.valueType.toInternalType(),
        keyType = this.keyType.toInternalType()
    )
    is TACStorageType.StaticArray -> {
        InternalStorageType.StaticArray(
            of = this.elementType.toInternalType(),
            constantSize = this.numElements,
            elemWordSize = this.elementSizeInWords
        )
    }
    is TACStorageType.Struct -> {
        mutableMapOf<BigInteger, InternalStorageType>().let {
            flattenStructMembers(it, member = this.members.values, startSlot = BigInteger.ZERO)
            InternalStorageType.Struct(it)
        }
    }
}

/**
 * Indicates that [originalBlockStart] contains a hash of a string key (the hash of which is in [keyHash])
 * and slot, producing a mapping storage slot output in [output]
 */
@KSerializable
data class BytesKeyHash(
    val keyHash: TACSymbol.Var,
    val slot: TACSymbol,
    val output: TACSymbol.Var,
    override val summarizedBlocks: Set<NBId>,
    override val originalBlockStart: NBId,
    override val skipTarget: NBId,
    override val modifiedVars: Set<TACSymbol.Var>
) : ConditionalBlockSummary {

    override val annotationDesc get() = "Hash string (with hash in $keyHash) with slot $slot, output in $output"
    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): TACSummary {
        return BytesKeyHash(
            keyHash = f(keyHash),
            slot = (slot as? TACSymbol.Var)?.let(f) ?: slot,
            output = f(output),
            summarizedBlocks = summarizedBlocks,
            originalBlockStart = originalBlockStart,
            skipTarget = skipTarget,
            modifiedVars = modifiedVars.mapToSet(f)
        )
    }

    override fun remapBlocks(f: (NBId) -> NBId?): ConditionalBlockSummary {
        return BytesKeyHash(
            keyHash = keyHash,
            slot = slot,
            output = output,
            summarizedBlocks = summarizedBlocks.monadicMap(f)?.toSet() ?: emptySet(),
            originalBlockStart = f(originalBlockStart)!!,
            skipTarget = f(skipTarget)!!,
            modifiedVars = modifiedVars
        )
    }

    override val variables: Set<TACSymbol.Var>
        get() = setOfNotNull(keyHash, slot as? TACSymbol.Var)
}


class StorageAnalysis(private val compilerStorage: TACStorageLayout?, private val contractClass: IContractClass?) {

    constructor(contractClass: IContractClass) : this(contractClass.getStorageLayout(), contractClass)

    sealed class Storage {
        /**
         * @param v the static slot number
         */
        data class Root(val v: BigInteger) : Storage()

        /**
         * @param v unique id
         */
        data class Derived(val v: Int) : Storage()
    }

    private var storageUnification = UnionFind<Int>()

    private val arrayOffsets = mutableMapOf<Int, MutableMap<BigInteger, Int>>()

    private fun unifyStorage(s1: Int, s2: Int) : Int {
        if(storageUnification.areEqual(s1, s2)) {
            // nothing to do
            return storageUnification.find(s1)
        }
        val repr1 = storage[Storage.Derived(storageUnification.find(s1))]
        val repr2 = storage[Storage.Derived(storageUnification.find(s2))]

        storageUnification.union(s1, s2)
        val unificand = storageUnification.find(s1)

        if(repr1 == null && repr2 != null) {
            storage[Storage.Derived(unificand)] = repr2
        } else if(repr2 == null && repr1 != null) {
            storage[Storage.Derived(unificand)] = repr1
        } else if(repr1 != null) {
            check(repr2 != null)
            storage[Storage.Derived(unificand)] = unifyStorage(repr1, repr2)
        }
        return unificand
    }

    private fun unifyStorage(repr1: Value, repr2: Value) : Value {
        if(repr1 is Value.Word && repr2 !is Value.Word) {
            return unifyStorage(repr2, repr1)
        }
        return when(repr1) {
            is Value.Array -> when(repr2) {
                is Value.Array -> {
                    /*
                       Does this unification open up resolution opportunities
                     */
                    val elemPtr1 = storageUnification.find(repr1.elem)
                    val elemPtr2 = storageUnification.find(repr2.elem)
                    if(elemPtr1 == elemPtr2) {
                        return Value.Array(elemPtr1)
                    }
                    val elemStorage1 = Storage.Derived(elemPtr1)
                    val elemStorage2 = Storage.Derived(elemPtr2)

                    val elemSize1 = arrayElemSizes[elemStorage1]
                    val elemSize2 = arrayElemSizes[elemStorage2]
                    check(elemSize1 == null || arrayOffsets[elemPtr1]?.isEmpty() != true) {
                        "${repr1.elem} has a resolved size, but has unresolved array offsets? ${arrayOffsets[elemPtr1]}"
                    }
                    check(elemSize2 == null || arrayOffsets[elemPtr2]?.isEmpty() != true) {
                        "${repr2.elem} has a resolved size, but has unresolved array offsets: ${arrayOffsets[elemPtr2]}"
                    }
                    fun resolveBySizes(unresolved: Int, resolved: Int, resolvedSize: BigInteger) {
                        val toUnify = arrayOffsets[unresolved] ?: mapOf()
                        for ((offs, v) in toUnify) {
                            val field = offs.mod(resolvedSize)
                            val where = (deref(Storage.Derived(resolved), field).first as Storage.Derived).v
                            unifyStorage(s1 = v, s2 = where)
                        }
                        arrayOffsets.remove(unresolved)
                    }
                    /*
                       Unify the elements
                     */
                    val unify = unifyStorage(elemPtr1, elemPtr2)
                    if(elemSize1 != null && elemSize2 != null) {
                        if(elemSize1 != elemSize2) {
                            throw StorageAnalysisFailedException("Attempting to unify two arrays $elemPtr1 $elemPtr2 with differing element sizes", null)
                        }
                        arrayElemSizes[Storage.Derived(unify)] = elemSize1
                    } else if(elemSize1 != null) {
                        check(elemSize2 == null) {
                            "element size $elemPtr2 was supposed to be null, it was not"
                        }
                        check(Storage.Derived(elemPtr2) !in storage) {
                            "How do we have values for $elemPtr2 without resolutions"
                        }
                        resolveBySizes(unresolved = elemPtr2, resolved = elemPtr1, resolvedSize = elemSize1)
                        arrayElemSizes[Storage.Derived(unify)] = elemSize1
                    } else if(elemSize2 != null) {
                        check(Storage.Derived(elemPtr1) !in storage) {
                            "How do we have values for $elemPtr1 without size information"
                        }
                        resolveBySizes(unresolved = elemPtr1, resolved = elemPtr2, resolvedSize = elemSize2)
                        arrayElemSizes[Storage.Derived(unify)] = elemSize2
                    } else {
                        val unresolved1 = arrayOffsets[elemPtr1] ?: mapOf()
                        val unresolved2 = arrayOffsets[elemPtr2] ?: mapOf()
                        val newOffsets = mutableMapOf<BigInteger, Int>()
                        for((k, v) in unresolved1) {
                            newOffsets[k] = unresolved2[k]?.let { unifyStorage(it, v) } ?: v
                        }
                        for((k, v) in unresolved2) {
                            if(k in newOffsets) {
                                continue
                            }
                            newOffsets[k] = v
                        }
                        arrayOffsets[unify] = newOffsets
                    }
                    Value.Array(unify)
                }
                is Value.Word, is Value.IntegerWithBounds -> {
                    repr1
                }
                else -> throw StorageAnalysisFailedException("Nonsense unification between $repr1 and $repr2", null)
            }
            is Value.Mapping -> (repr2 as? Value.Mapping)?.let {
                Value.Mapping(unifyStorage(repr1.codom, repr2.codom))
            } ?: throw StorageAnalysisFailedException("Failed to unify $repr1 with $repr2", null)
            is Value.Struct -> {
                if(repr2 is Value.SingleSlotSValue) {
                    val field = heapLoc++
                    storage[Storage.Derived(field)] = repr2
                    val fresh = repr1.elems[BigInteger.ZERO]?.let {
                        unifyStorage(field, it)
                    } ?: field
                    Value.Struct(
                        (repr1.elems + (BigInteger.ZERO to fresh)).toMutableMap()
                    )
                } else if(repr2 is Value.Struct) {
                    val output = mutableMapOf<BigInteger, Int>()
                    for(k in repr1.elems.keys + repr2.elems.keys) {
                        val v1 = repr1.elems[k]
                        val v2 = repr2.elems[k]
                        if(v1 != null && v2 != null) {
                            output[k] = unifyStorage(v1, v2)
                        } else if(v1 == null) {
                            output[k] = v2!!
                        } else if(v2 == null) {
                            output[k] = v1
                        } else {
                            `impossible!`
                        }
                    }
                    Value.Struct(output)
                } else {
                    throw StorageAnalysisFailedException("Cannot unify $repr1 with $repr2", null)
                }
            }
            Value.Word -> if(repr2 !is Value.SingleSlotSValue) {
                throw StorageAnalysisFailedException("Cannot unify $repr1 with non-word $repr2", null)
            } else {
                repr1
            }
            is Value.IntegerWithBounds ->
                when(repr2) {
                    Value.Word -> repr2
                    is Value.IntegerWithBounds -> Value.IntegerWithBounds(repr1.valueRange.join(repr2.valueRange))
                    else -> throw StorageAnalysisFailedException("Cannot unify $repr1 with non-word $repr2", null)
            }
            is Value.StaticArray -> if(repr2 !is Value.StaticArray || repr2.numElems != repr1.numElems || repr1.wordsPerElem != repr2.wordsPerElem) {
                throw StorageAnalysisFailedException("Cannot unify $repr1 with incompatible $repr2", null)
            } else {
                repr1.copy(
                    elems = unifyStorage(repr1.elems, repr2.elems)
                )
            }
        }
    }

    /**
     * Tells us what type of node and what its children are
     */
    sealed class Value {
        sealed interface SingleSlotSValue

        object Word : Value(), SingleSlotSValue

        data class IntegerWithBounds(val valueRange: IntValue) : Value(), SingleSlotSValue

        /**
         * codom: Id of Derived
         */
        data class Mapping(val codom: Int) : Value()

        /**
         * elem: ID of Derived
         */
        data class Array(val elem: Int) : Value()

        /**
         * Keys: the number of words offset from the base pointer
         * Values: Ids of Derived's
         */
        data class Struct(val elems: MutableMap<BigInteger, Int>) : Value()

        /**
         * [elems]: the ID of the value that holds the elements of this static array
         */
        data class StaticArray(val elems: Int, val numElems: BigInteger, val wordsPerElem: BigInteger) : Value() {
            override fun range(start: BigInteger): IntValue {
                return IntValue(start, start + (numElems*wordsPerElem) - BigInteger.ONE)
            }
        }

        open fun range(start: BigInteger): IntValue = IntValue.Constant(start)
    }

    /**
     * Stores the tree (forest) structure of [Storage] and [Value]
     * [Storage] tells us whether or not we are at a root or not
     * [Value] tells us what type of node we are at and gives us the id's of our children
     */
    private val storage = mutableMapOf<Storage, Value>()

    /**
     * Maps the id of an array to the size of its elements
     */
    private val arrayElemSizes = mutableMapOf<Storage, BigInteger>()

    private var heapLoc = 0

    private val storageHashArgsReversed = compilerStorage?.storageHashArgsReversed ?: false

    init {
        if(compilerStorage != null) {
            /**
              Take the "direct" types and canonicalize it to match the format expected by the storage analysis,
              i.e., there is always a struct between map/arrays.
             */
            fun InternalStorageType.canonicalize(): InternalStorageType {
                return when(this) {
                    is InternalStorageType.Array -> {
                        if(this.of !is InternalStorageType.Struct) {
                            InternalStorageType.Array(
                                of = InternalStorageType.Struct(
                                    mapOf(BigInteger.ZERO to this.of.canonicalize())
                                )
                            )
                        } else {
                            InternalStorageType.Array(of = this.of.canonicalize())
                        }
                    }
                    is InternalStorageType.Mapping -> {
                        if(this.codom !is InternalStorageType.Struct) {
                            this.copy(
                                codom = InternalStorageType.Struct(
                                    mapOf(BigInteger.ZERO to this.codom.canonicalize())
                                )
                            )
                        } else {
                            this.copy(codom = this.codom.canonicalize())
                        }
                    }
                    is InternalStorageType.Struct -> {
                        check(this.members.none {
                            it.value is InternalStorageType.Struct
                        })
                        InternalStorageType.Struct(
                            members = this.members.mapValues { it.value.canonicalize() }
                        )
                    }
                    InternalStorageType.Word -> this
                    is InternalStorageType.IntegerWithBounds -> this
                    is InternalStorageType.StaticArray -> {
                        if(of !is InternalStorageType.Struct) {
                            this.copy(
                                of = InternalStorageType.Struct(
                                    mapOf(BigInteger.ZERO to this.of.canonicalize())
                                )
                            )
                        } else {
                            this.copy(
                                of = this.of.canonicalize()
                            )
                        }
                    }
                }
            }

            fun InternalStorageType.seed() : Value? {
                return when(this) {
                    is InternalStorageType.Array -> {
                        val ind = heapLoc++
                        arrayElemSizes[Storage.Derived(ind)] = this.of.sizeInWords()
                        storage[Storage.Derived(ind)] = this.of.seed() ?: return null
                        Value.Array(ind)
                    }
                    is InternalStorageType.Mapping -> {
                        val ind = heapLoc++
                        storage[Storage.Derived(ind)] = this.codom.seed() ?: return null
                        Value.Mapping(ind)
                    }
                    is InternalStorageType.Struct -> {
                        this.members.entries.monadicMap {
                            val ind = heapLoc++
                            storage[Storage.Derived(ind)] = it.value.seed() ?: return@monadicMap null
                            it.key to ind
                        }?.toMap()?.toMutableMap()?.let(Value::Struct)
                    }
                    InternalStorageType.Word -> Value.Word
                    is InternalStorageType.IntegerWithBounds -> Value.IntegerWithBounds(this.valueRange)
                    is InternalStorageType.StaticArray -> {
                        val ind = heapLoc++
                        storage[Storage.Derived(ind)] = this.of.seed() ?: return null
                        Value.StaticArray(
                            ind,
                            constantSize,
                            elemWordSize
                        )
                    }
                }
            }
            /*
              If any of the types failed to be seeded, completely give up on seeding: we don't want incomplete
              information.
             */
            fun rollback() {
                storage.clear()
                heapLoc = 0
                arrayElemSizes.clear()
            }
            for((_, topLevelSlot) in compilerStorage) {
                val startSlot = topLevelSlot.slot
                if (Storage.Root(startSlot) in storage && storage[Storage.Root(startSlot)] != Value.Word) {
                    rollback()
                    break
                }
                val internalType = topLevelSlot.storageType?.toInternalType() ?: continue
                if (internalType is InternalStorageType.Struct) {
                    for ((k, t) in internalType.members) {
                        storage[Storage.Root(startSlot + k)] = t.canonicalize().seed().also { res ->
                            if (res == null) {
                                rollback()
                            }
                        } ?: break
                    }
                } else {
                    storage[Storage.Root(startSlot)] = internalType.canonicalize().seed().also { res ->
                        if (res == null) {
                            rollback()
                        }
                    } ?: break
                }
            }
        }
    }

    private fun resolveElementSize(arrayNode: Storage, elemSize: BigInteger, where: CmdPointer?, join: NBId?) {
        if(arrayNode !in arrayElemSizes) {
            // resolution time!
            val toResolve = (arrayNode as? Storage.Derived)?.v?.let(arrayOffsets::get)
            if(toResolve != null) {
                val map = mutableMapOf<BigInteger, Int>()
                for((k, v) in toResolve) {
                    map.merge(k.mod(elemSize), v) { a, b ->
                        unifyStorage(a, b)
                    }
                }
                val heap = heapLoc++
                storage[Storage.Derived(heap)] = Value.Struct(map)
                unifyStorage(arrayNode.v, heap)
                arrayOffsets.remove(arrayNode.v)
                arrayElemSizes[Storage.Derived(heap)] = elemSize
            }
            arrayElemSizes[arrayNode.find()] = elemSize
            arrayElemSizes[arrayNode] = elemSize
        } else if(arrayElemSizes[arrayNode] != elemSize) {
            throw StorageAnalysisFailedException("At ${where ?: join}: Array elements inferred to be two different sizes: " +
                    "${arrayElemSizes[arrayNode]} and $elemSize", where ?: (join?.let { CmdPointer(it,0) }))
        }
    }



    private fun writeValue(ptr: Storage, v: SValue, cmdPtr: CmdPointer, addAssertion: (CmdPointer, SValue, Value) -> Unit): Boolean {
        if (v !is IntegralType) {
            // log? throw?
            throw StorageAnalysisFailedException("$ptr $v", cmdPtr)
        }
        return writeWord(ptr, v, cmdPtr, addAssertion)
    }

    private fun Storage.find() = when(this) {
        is Storage.Derived -> this.copy(v = storageUnification.find(this.v))
        is Storage.Root -> this
    }

    private fun writeWord(ptr_: Storage, v: SValue, cmdPtr: CmdPointer, addAssertion: (CmdPointer, SValue, Value) -> Unit): Boolean {
        var unmappedBase = false
        val ptr = ptr_.find()
        val obj = storage[ptr] ?: run {
            storage[ptr] = Value.Word
            unmappedBase = true
            Value.Word
        }
        return when (obj) {
            Value.Word -> false
            is Value.IntegerWithBounds -> {
                if (v !is SValue.I || v.i.x.lb < obj.valueRange.lb || obj.valueRange.ub < v.i.x.ub) {
                    addAssertion(cmdPtr, v, obj)
                }
                false
            }
            is Value.StaticArray -> {
                val whence = storage[Storage.Derived(obj.elems)]?.let {
                    it as? Value.Struct
                }?.elems?.get(BigInteger.ZERO) ?: throw StorageAnalysisFailedException("Broken storage: had seeded static array $obj, but its contents aren't in storage?", cmdPtr)
                writeWord(Storage.Derived(whence), v, cmdPtr, addAssertion)
            }
            is Value.Mapping -> throw StorageAnalysisFailedException("Cannot write to mapping base @ $ptr", cmdPtr)
            is Value.Array -> false
            is Value.Struct -> {
                val indirect = obj.elems.computeIfAbsent(BigInteger.ZERO) {
                    heapLoc++
                }
                writeWord(Storage.Derived(indirect), v, cmdPtr, addAssertion)
            }
        } || unmappedBase
    }


    interface IntegralType {
        fun mustBeMultipleOf(k: BigInteger): Boolean
    }

    /* These may not be actual "StoragePointers", but have paths
       that can possibly be _extended_ by adding implicit 0 offsets
     */
    private sealed interface HasAnalysisPaths {
        fun accessPaths(storage: Map<Storage, Value>?): AnalysisPaths
    }

    private sealed interface Indexable: HasAnalysisPaths {
        fun usesVar(storage: Map<Storage, Value>?, v: TACSymbol.Var) : Boolean = (accessPaths(storage) as? AnalysisPaths.PathSet)?.paths?.any {
            it.contains(v)
        } == true
    }

    private val checkingMode: Boolean = compilerStorage != null

    private fun getStorageHint(): Map<Storage, Value>? = storage.takeIf { checkingMode }

    private fun Indexable.toStorage(): Pair<Set<Storage>, Boolean>? {
        val storageHint = getStorageHint()

        if(this is SValue.I) {
            return this.toStorage(storageHint)?.let { it to false }
        }
        if(this is SValue.UnresolvedOffset) {
            return this.elementStorage.map {
                it.find()
            }.distinct().map {
                arrayElemSizes[it]?.let { eSz ->
                    SValue.StoragePointer.FieldPointer(
                        base = setOf(it),
                        offset = this.constOffset.mod(eSz),
                        accessPaths = AnalysisPaths.Top
                    ).toStorage()
                } ?: (setOf(Storage.Derived(arrayOffsets.computeIfAbsent((it as Storage.Derived).v) {
                    mutableMapOf()
                }.computeIfAbsent(this.constOffset) {
                    heapLoc++
                })) to false)
            }.unzip().let {
                it.first.flatten().toSet() to it.second.any { it }
            }
        }

        /**
         * Assuming that [pointers] are a word (or a struct) in [storage],
         * get the set of storage pointers at [field]. If necessary, [analysis.storage.StorageAnalysis.Value.Word]
         * values are promoted into structs
         */
        fun doDeref(
            pointers: Set<Storage>,
            field: BigInteger
        ): Pair<MutableSet<Storage>, Boolean> {
            var mut = false
            val toRet = mutableSetOf<Storage>()
            for (v in pointers) {
                val (x, m) = deref(v, field)
                toRet.add(x)
                mut = mut || m
            }
            return toRet to mut
        }

        return when (val sp = this as SValue.StoragePointer) {  // implicit check(this is SValue.StoragePointer)
            is SValue.StoragePointer.MappingPointer -> doDeref(sp.base, BigInteger.ZERO)
            is SValue.StoragePointer.ElementPointer -> {
                doDeref(sp.base, BigInteger.ZERO)
            }
            is SValue.StoragePointer.FieldPointer -> {
                val field = sp.offset
                val pointers = sp.base
                doDeref(pointers, field)
            }
        }
    }

    /**
     * For every program point, every stack variable in scope at that program point is annotated with
     * an [SValue]
     *
     */
    sealed class SValue {
        abstract fun join(
            j: SValue,
            ourState: TreapMap<TACSymbol.Var, SValue>,
            theirState: TreapMap<TACSymbol.Var, SValue>,
            arrayContext: Map<Storage, BigInteger>,
            storage: Map<Storage, Value>? = null
        ): SValue

        open fun widen(
            j: SValue,
            ourState: TreapMap<TACSymbol.Var, SValue>,
            theirState: TreapMap<TACSymbol.Var, SValue>,
            arrayContext: Map<Storage, BigInteger>,
            storage: Map<Storage, Value>? = null
        ): SValue = join(j, ourState, theirState, arrayContext, storage)

        /**
         * Represents an integral value that may be used as a pointer into storage.
         *
         * @property cs a list of constant values we may be equal to
         * @property i the interval abstraction of this value + qualifiers
         * @property stride sets of _stride_ terms that this term may be equal to
                     stride is only used for tracking pointers into root storage,
                     i.e. not behind some kind of dynamic base i.e. array/mapping.
                     The elements of the set are pointers into disjoint storage areas,
                     so this instance should be described by exactly one of them
         */
        data class I(val cs: TreapSet<BigInteger>?, val i: SimpleQualifiedInt, val stride: TreapSet<Stride>) : SValue(), IntegralType, Indexable {

            companion object {
                fun Constant(c: BigInteger) =
                    I(
                        cs = treapSetOf(c),
                        i = SimpleQualifiedInt.Constant(c),
                        stride = treapSetOf(Stride.SumOfTerms(c))
                    )
            }

            private val usedVars: TreapSet<TACSymbol.Var> = stride.asSequence().filterIsInstance<Stride.SumOfTerms>().flatMap {
                it.factored.values.asSequence().map { it.x }
            }.filterNotNull().toTreapSet()

            override fun usesVar(storage: Map<Storage, Value>?, v: TACSymbol.Var) = v in usedVars

            init {
                check(stride.size == 1 || Stride.Top !in stride)
            }

            /**
             * If [cs], [i], and [stride] are Top, then return Nondet,
             * otherwise return this (does not make a copy)
             */
            fun normalize(): SValue =
                if (cs == null && i == SimpleQualifiedInt.nondet && Stride.Top in stride) {
                    Nondet
                } else {
                    this
                }

            private fun Stride.uniqueRootSlot(storage: Map<Storage, Value>): BigInteger? {
                var slot: BigInteger? = null
                storage.forEachEntryInline { (p, s) ->
                    if (p is Storage.Root && this.range.mayIntersect(s.range(p.v))) {
                        when (slot) {
                            null -> slot = p.v // first value
                            p.v -> {} // still unique
                            else -> return null // not unique
                        }
                    }
                }
                return slot
            }

            private fun isBottom(): Boolean =
                // the bottom interval is not representable
                cs?.isEmpty() == true || stride.isEmpty()

            fun refineSimpleQualifiedInt(idx: SimpleQualifiedInt): I {
                // We don't want to mistakenly add idx.c
                // if this.cs is empty
                val newConsts = cs?.retainAll {
                    idx.x.mayEqual(it)
                } ?: if (idx.x.isConstant) {
                    treapSetOf(idx.x.c)
                } else {
                    null
                }

                val newStrides = stride.updateElements { it.refineTotal(idx.x) }

                return I(
                    cs = newConsts,
                    i = idx,
                    stride = newStrides
                ).apply {
                    if (isBottom()) {
                        throw InfeasibleState()
                    }
                }
            }

            /**
             * Join our set of strides with [j] by joining the equivalence classes induced by computing the possible
             * root storage locations referred to by each Stride.
             */
            private fun joinOpStrides(j: TreapSet<Stride>, storage: Map<Storage, Value>?, widen: Boolean): TreapSet<Stride> {
                // Assuming there are no arrays, so bail and fall back on the constant set tracking
                if (storage == null) {
                    return Stride.Top.asSet
                }

                val these = stride.groupBy { it.uniqueRootSlot(storage) ?: return Stride.Top.asSet }
                val those = j.groupBy { it.uniqueRootSlot(storage) ?: return Stride.Top.asSet }

                return these.joinByEqClass(those, widen)
            }

            private fun joinConstants(other: TreapSet<BigInteger>?, widen: Boolean): TreapSet<BigInteger>? {
                return other?.let { js ->
                    cs?.let { these ->
                        val join = js.union(these)
                        if (widen && these.size != join.size) {
                            null
                        } else {
                            join
                        }
                    }
                }
            }


            private fun joinOp(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?, widen: Boolean): SValue =
                    when (j) {
                        is ArrayStart,
                        is Nondet -> Nondet
                        is I -> {
                            val ourIdxState = ProjectedMap(ourState, ::narrowIdx, ::mergeIdx)
                            val theirIdxState = ProjectedMap(ourState, ::narrowIdx, ::mergeIdx)
                            val csJoin = joinConstants(j.cs, widen)
                            val iJoin = i.joinOp(j.i, ourIdxState, theirIdxState, widen)
                            val sJoin = joinOpStrides(j.stride, storage, widen)
                            // sRange is the interval spanned by all the strides
                            // Having joined all the strides, this actually might be more
                            // precise than [iJoin].
                            val sRange = sJoin.map { it.range }.foldFirstOrNull { l, r -> l.join(r) } ?: IntValue.Nondet
                            // Intersect [iJoin]'s interval with the interval implied by the strides
                            val refineIdx = iJoin.withBoundAndQualifiers(sRange.lb, sRange.ub, iJoin.qual)
                            I(csJoin, iJoin, sJoin).refineSimpleQualifiedInt(refineIdx)
                        }
                        is StoragePointer -> j.join(this, ourState, theirState, arrayContext)
                        is UnresolvedOffset -> Nondet
                    }

            fun killVar(x: TACSymbol.Var): I {
                return this.copy(stride = stride.updateElements { it.killVar(x) })
            }

            override fun join(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?): SValue =
                    joinOp(j, ourState, theirState, arrayContext, storage, false)

            override fun widen(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?): SValue =
                    joinOp(j, ourState, theirState, arrayContext, storage, true)

            /**
             * Walk storage starting from [p] to some Word/Array/Mapping Value, using [terms] + [off] to pick
             * the struct offsets / array indices visited. In this way, [terms] + [off] is just treated as a pointer into
             * [p].
             *
             * Assumes the client is building an answer of type [A] for each such path from [p]. At each step, one of
             * the three callbacks is invoked to construct the next piece of the answer.
             *
             * Checks that we _could_ have completely consumed the value denoted by [terms] + [off], and that all
             * array access _could_ be in bounds.
             *
             * @param p the [Storage] value we start walking from
             * @param storageEnv the shape of the storage heap
             * @param terms a representation of how the index we're interpreting was constructed
             * @param off the constant offset that's part of this index. [terms] may have a mapping for a coefficient of 1,
             *   but generally this denotes a _variable_ offset, i.e. some number in an interval (and could be 0)
             * @param accum accumulates the answer
             * @param onStruct constructs the answer at the current struct level from the answer constructed thus far
             * @param onStaticArray constructs the answer at the current static array level
             * @param onBase constructs the answer at some base storage value, i.e. a word, dynamic array, or mapping
             * @return a set of answers corresponding to the plausible storage paths from [p] to some base storage value
             */
            private fun <@Treapable A> walkTerms(
                    p: Storage,
                    storageEnv: Map<Storage, Value>,
                    terms: TreapMap<BigInteger, Stride.SymValue>,
                    off: BigInteger,
                    accum: A,
                    onStruct: (A, Storage, Value.Struct, BigInteger) -> A,
                    onStaticArray: (A, Storage, Value.StaticArray, Stride.SymValue) -> A,
                    onBase: (A, Storage, Value) -> A,
            ): Set<A> {

                fun Map<BigInteger, Stride.SymValue>.couldBeZero(): Boolean {
                    return this.all { (_, f) ->
                        f.v.mayEqual(BigInteger.ZERO)
                    }
                }

                fun TreapMap<BigInteger, Stride.SymValue>.takeOffset(off: BigInteger): TreapMap<BigInteger, Stride.SymValue>? {
                    return this[off]?.let { f ->
                        if (f.v.ub == BigInteger.ONE) {
                            this - off
                        } else {
                            this + (off to f.copy(v = f.v.sub(IntValue.Constant(BigInteger.ONE)).first))
                        }
                    }
                }

                // Fold over the structure, accumulating the answer in [a]
                fun walk(p: Storage, terms: TreapMap<BigInteger, Stride.SymValue>, off: BigInteger, a: A): Set<A> {

                    return when (val v = storageEnv[p]!!) {
                        is Value.StaticArray -> {
                            val quotRemainder = off.divideAndRemainder(v.wordsPerElem)
                            val (idxFromOffset, newOffset) = if (quotRemainder[0] != BigInteger.ZERO) {
                                // Then let's treat the offset as part of this indexing operation
                                quotRemainder[0] to quotRemainder[1]
                            } else {
                                BigInteger.ZERO to off
                            }
                            val idx = terms[v.wordsPerElem]?.let {
                                it.add(IntValue.Constant(idxFromOffset))
                            } ?: Stride.SymValue(null, IntValue.Constant(idxFromOffset))
                            if (idx.v.ub > (v.numElems - BigInteger.ONE)) {
                                return setOf()
                            }
                            walk(Storage.Derived(v.elems), terms - v.wordsPerElem, newOffset, onStaticArray(a, p, v, idx))
                        }

                        is Value.Struct -> {
                            val maybeValues: Set<A> = v.elems.flatMap { (fldOff, fld) ->
                                val cases = terms.takeOffset(fldOff)
                                cases?.let { newTerms ->
                                    walk(Storage.Derived(fld), newTerms, off, onStruct(a, p, v, fldOff))
                                }.orEmpty()
                            }.toSet()

                            val fld = v.elems.keys.sorted().lastOrNull { key ->
                                // We're indexing somewhere into the struct's field `key`,
                                // which means `off` is greater than `key`.
                                key <= off
                            } ?: BigInteger.ZERO
                            val offValues = walk(Storage.Derived(v.elems[fld]!!), terms, off - fld, onStruct(a, p, v, fld))
                            maybeValues + offValues
                        }

                        is Value.Array,
                        is Value.Mapping,
                        is Value.Word,
                        is Value.IntegerWithBounds-> {
                            // We're at the end of the path. If the pointer value
                            // denoted by terms + off *could* be zero, then this is
                            // a possibly valid path, so we're done
                            //
                            // (couldBeZero checks that the sum of terms _may_ be equal to 0)
                            if (terms.couldBeZero() && off == BigInteger.ZERO) {
                                setOf(onBase(a, p, v))
                            } else {
                                // Otherwise, it seems this path has some pointer value "left over,"
                                // so this isn't really a valid path
                                setOf()
                            }
                        }
                    }
                }

                return walk(p, terms, off, accum)
            }

            /**
             * Subtracts the root offset from each of our list of terms, and drops that term if it's equal to zero
             */
            private fun subtractRootOffset(s: Stride, r: Storage.Root): Set<Stride.SumOfTerms> =
                (s as? Stride.SumOfTerms)?.subConst(r.v).orEmpty()

            private fun inStaticArray(i: BigInteger, storage: Map<Storage, Value>): Boolean {
                for ((k, v) in storage) {
                    if (k !is Storage.Root || v !is Value.StaticArray) {
                        continue
                    }

                    if (k.v <= i && i < k.v + v.wordsPerElem.multiply(v.numElems)) {
                        return true
                    }
                }
                return false
            }

            /**
             * Fold over the structure of this [I]. First, if there is no storage hint provided,
             * then just interpret the possible constant values as root storage slots.
             *
             * Otherwise, walk the [Stride.SumOfTerms] structure (if possible) to construct an [A] top-down using the given
             * eliminators.
             *
             * The purpose of this is just to factor out the common reasoning from [accessPaths] and [toStorage]
             *
             * @param storage the shape of the storage heap
             * @param onRoot how to construct the initial answer from a root slot
             * @param onStruct constructs the answer at the current struct level from the answer constructed thus far
             * @param onStaticArray constructs the answer at the current static array level
             * @param onBase constructs the answer at some base storage value, i.e. a word, dynamic array, or mapping
             */
            private fun <@Treapable A> gatherPossibleStorage(
                    storage: Map<Storage, Value>?,
                    onRoot: (BigInteger) -> A,
                    onStruct: (A, Storage, Value.Struct, BigInteger) -> A,
                    onStaticArray: (A, Storage, Value.StaticArray, Stride.SymValue) -> A,
                    onBase: (A, Storage, Value) -> A,
            ): Set<A>? {
                if (storage == null) {
                    if (cs.isNullOrEmpty()) {
                        return null
                    }
                    return cs.mapToSet(onRoot)
                }

                val skip = mutableSetOf<BigInteger>()
                val possibleStorage = mutableSetOf<A>()
                for (c in cs.orEmpty()) {
                    if (!inStaticArray(c, storage)) {
                        possibleStorage += onRoot(c)
                        skip += c
                    }
                }

                if (cs != null && cs.all { !inStaticArray(it, storage) }) {
                    return possibleStorage
                }

                val strides = stride.filterIsInstance<Stride.SumOfTerms>()
                for ((k, v) in storage) {
                    if (k !is Storage.Root || k.v in skip) {
                        continue
                    }
                    for (stride in strides) {

                        val expectResult = when (v) {
                            is Value.StaticArray ->
                                stride.range.mayIntersect(IntValue(k.v, k.v + v.numElems*v.wordsPerElem-BigInteger.ONE))
                            else -> {
                                // We're focusing on the stride component, so
                                // use that to refine whether or not we expect a result.
                                // e.g. initially this = I(null, [0, 1], { 0, 1 }) (i.e., two constant strides)
                                // then when we focus on '0', our range is just [0, 0]. This is important
                                // when we're copying e.g. Strings in vyper:
                                // the string has this layout:
                                // slot x: String population size (i.e., length)
                                // slot x+1: String data
                                //
                                // the generated copy loop will actually copy data from *x* to length+1,
                                // hitting two Root slots in one swoop.
                                //
                                // our stride abstraction will have two members: one for the population, one for the data
                                val strideRange = i.x.withLowerBound(stride.range.lb).withUpperBound(stride.range.ub)
                                strideRange.mayEqual(k.v)
                            }
                        }

                        if (!expectResult) {
                            continue
                        }

                        // We should at least have a term corresponding to the root slot. Since we've identified
                        // the root above, subtract it away.
                        subtractRootOffset(stride, k).forEach { s ->
                            val res = walkTerms(
                                    k,
                                    storage,
                                    s.factored,
                                    s.off,
                                    onRoot(k.v),
                                    onStruct,
                                    onStaticArray,
                                    onBase,
                            )
                            // Probably due to some imprecision we were not able to determine that
                            // we are a valid storage access, so signal failure.
                            // Necessary for soundness: we need to be able to compute an answer for each
                            // possible storage.
                            if (res.isEmpty()) {
                                return null
                            }
                            possibleStorage += res
                        }
                    }
                }
                return possibleStorage
            }

            fun toStorage(storage: Map<Storage, Value>?): Set<Storage>? =
                    gatherPossibleStorage(
                            storage,
                            onRoot = { v -> Storage.Root(v) },
                            // In this case, the accumulator is always the previous step's storage
                            onStruct = { _, p, _, _ -> p },
                            onStaticArray = { _, p, _, _ -> p },
                            onBase = { _: Storage, p, _ -> p }
                    )?.ifEmpty { null }

            override fun accessPaths(storage: Map<Storage, Value>?): AnalysisPaths =
                    gatherPossibleStorage(
                            storage,
                            onRoot = { v -> AnalysisPath.Root(v)  },
                            onStruct = { base, _, _, fld -> AnalysisPath.StructAccess(base, fld) },
                            onStaticArray = { base, _, _, idx ->
                                val idxSymbol = if (idx.v.isConstant) {
                                    idx.v.c.asTACSymbol()
                                } else {
                                    idx.x
                                }
                                AnalysisPath.StaticArrayAccess(base, idxSymbol)
                            },
                            onBase = { base : AnalysisPath, _, _ -> base }
                    )?.toAnalysisPaths() ?: AnalysisPaths.Top

            // Helper function for [accessPaths]
            private fun Set<AnalysisPath>.toAnalysisPaths(): AnalysisPaths =
                    if (isEmpty()) {
                        AnalysisPaths.Top
                    } else {
                        AnalysisPaths.PathSet(this)
                    }


            override fun mustBeMultipleOf(k: BigInteger): Boolean =
                    (SimpleIntQualifier.MultipleOf(k) in i.qual)
                            || k == BigInteger.ONE
                            || (cs?.all { it.mod(k) == BigInteger.ZERO } ?: false)
        }

        object Nondet : SValue(), IntegralType {
            override fun join(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?): SValue = this
                        override fun mustBeMultipleOf(k: BigInteger): Boolean = false
        }

        /**
         * [parentPath] must be an [analysis.storage.StorageAnalysis.AnalysisPath.ArrayAccess] at index 0.
         * This represents a constant offset within an array when we aren't yet sure of the size of array elements.
         * Without this information, an offset of 10 could be the 5th element of an array of elements with size 2,
         * of the 10th element of an array of uints, or ...
         *
         * Rather than try to guess, we just record the exact offset in the abstract domain, record the *writes* to these
         * constant locations in a separate area ([arrayOffsets] vs [storage]), deferring mapping these constants
         * element/offset pairs until we get the array element sizes.
         */
        data class UnresolvedOffset(val elementStorage: Set<Storage>, val constOffset: BigInteger, val parentPath: AnalysisPaths) : SValue(), Indexable {
            init {
                check(parentPath !is AnalysisPaths.PathSet || parentPath.paths.all {
                    it is AnalysisPath.ArrayAccess && it.index == BigInteger.ZERO.asTACSymbol()
                }) {
                    "Class invariant violated, bad paths $parentPath"
                }
            }
            val accessPaths: AnalysisPaths
                get() = parentPath.map {
                    AnalysisPath.WordOffset(constOffset, it)
                }
            override fun accessPaths(storage: Map<Storage, Value>?): AnalysisPaths = accessPaths

            override fun join(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?): SValue {
                return when(j) {
                    is UnresolvedOffset -> {
                        if(j.constOffset != this.constOffset) {
                            return Nondet
                        }
                        UnresolvedOffset(
                            elementStorage = this.elementStorage + j.elementStorage,
                            constOffset = j.constOffset,
                            parentPath = parentPath.join(j.parentPath)
                        )
                    }
                    is ArrayStart -> {
                        return if(constOffset == BigInteger.ZERO) {
                            UnresolvedOffset(
                                elementStorage = this.elementStorage + j.elementPointers,
                                constOffset = BigInteger.ZERO,
                                parentPath = this.parentPath.join(j.accessPaths)
                            )
                        } else {
                            Nondet
                        }
                    }
                    is StoragePointer.FieldPointer -> {
                        val constSize = j.base.monadicMap {
                            arrayContext[it]
                        }?.uniqueOrNull() ?: return Nondet
                        if(this.constOffset.mod(constSize) == j.offset) {
                            StoragePointer.FieldPointer(
                                offset = j.offset,
                                base = j.base + this.elementStorage,
                                accessPaths = this.parentPath.map {
                                    check(it is AnalysisPath.ArrayAccess)
                                    AnalysisPath.StructAccess(
                                        base = it.copy(index = (this.constOffset / constSize).asTACSymbol()),
                                        offset = Offset.Words(this.constOffset.mod(constSize))
                                    )
                                }.join(j.accessPaths)
                            )
                        } else {
                            Nondet
                        }
                    }
                    is StoragePointer.ElementPointer -> {
                        val constSize = j.base.firstMapped {
                            arrayContext[it]
                        } ?: throw AnalysisFailureException("have an element pointer with base ${j.base} but unresolved sizes?")
                        if(this.constOffset.mod(constSize) == BigInteger.ZERO) {
                            StoragePointer.ElementPointer(
                                base = elementStorage + this.elementStorage,
                                accessPaths = this.parentPath.map {
                                    check(it is AnalysisPath.ArrayAccess)
                                    it.copy(index = (constOffset / constSize).asTACSymbol())
                                }.join(j.accessPaths)
                            )
                        } else {
                            Nondet
                        }
                    }
                    else -> Nondet
                }
            }

        }

        sealed class StoragePointer : SValue(), Indexable {
            abstract val base: Set<Storage>

            abstract fun withPaths(accessPaths: AnalysisPaths): StoragePointer

            data class ElementPointer(override val base: Set<Storage>, val accessPaths: AnalysisPaths) : StoragePointer() {
                init {
                    check(accessPaths !is AnalysisPaths.PathSet || accessPaths.paths.all {
                        it is AnalysisPath.ArrayAccess
                    })
                }

                override fun withPaths(accessPaths: AnalysisPaths): ElementPointer = this.copy(accessPaths = accessPaths)

                override fun join(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?): SValue =
                        if (j is ElementPointer) {
                            ElementPointer(base + j.base, accessPaths.join(j.accessPaths))
                        } else if (j is ArrayStart) {
                            ElementPointer(base + j.elementPointers, accessPaths.join(j.accessPaths))
                        } else if(j is UnresolvedOffset) {
                            j.join(this, ourState, theirState, arrayContext)
                        } else {
                            Nondet
                        }

                override fun accessPaths(storage: Map<Storage, Value>?): AnalysisPaths = accessPaths
            }

            data class FieldPointer(override val base: Set<Storage>, val offset: BigInteger, val accessPaths: AnalysisPaths) : StoragePointer() {
                init {
                    check(accessPaths !is AnalysisPaths.PathSet || accessPaths.paths.all {
                        it is AnalysisPath.StructAccess
                    })
                }
                override fun withPaths(accessPaths: AnalysisPaths): StoragePointer = this.copy(accessPaths = accessPaths)

                override fun join(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?): SValue =
                        if (j is FieldPointer && j.offset == this.offset) {
                            FieldPointer(base + j.base, offset, accessPaths.join(j.accessPaths))
                        } else if(j is UnresolvedOffset) {
                            j.join(this, ourState, theirState, arrayContext)
                        } else {
                            Nondet
                        }

                override fun accessPaths(storage: Map<Storage, Value>?): AnalysisPaths = accessPaths
            }

            data class MappingPointer(override val base: Set<Storage>, val accessPaths: AnalysisPaths) : StoragePointer() {
                init {
                    check(accessPaths !is AnalysisPaths.PathSet || accessPaths.paths.all {
                        it is AnalysisPath.MapAccess
                    })
                }
                override fun withPaths(accessPaths: AnalysisPaths): StoragePointer = this.copy(accessPaths = accessPaths)

                override fun join(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?): SValue =
                        if (j is MappingPointer) {
                            MappingPointer(base + j.base, accessPaths.join(j.accessPaths))
                        } else {
                            Nondet
                        }

                override fun accessPaths(storage: Map<Storage, Value>?): AnalysisPaths = accessPaths
            }
        }


        /**
         * @property [elementPointers] contain nodes that correspond to an element of this array
         */
        data class ArrayStart(val base: Set<Storage>, val elementPointers: Set<Storage.Derived>, val accessPaths: AnalysisPaths) : SValue(), HasAnalysisPaths {
            init {
                check((accessPaths as? AnalysisPaths.PathSet)
                        ?.let { paths ->
                            paths.paths.all { path ->
                                path is AnalysisPath.ArrayAccess &&
                                        path.index == TACSymbol.lift(0)}}?: true)
                { "Class invariant violated, accessPaths must be top or all ArrayAccess with index 0"}
            }
            override fun join(j: SValue, ourState: TreapMap<TACSymbol.Var, SValue>, theirState: TreapMap<TACSymbol.Var, SValue>, arrayContext: Map<Storage, BigInteger>, storage: Map<Storage, Value>?): SValue =
                    if (j is ArrayStart) {
                        ArrayStart(j.base + this.base, j.elementPointers + this.elementPointers, accessPaths.join(j.accessPaths))
                    } else if (j is StoragePointer.ElementPointer) {
                        StoragePointer.ElementPointer(
                            this.elementPointers + j.base,
                            accessPaths.join(j.accessPaths)
                        )
                    } else if(j is UnresolvedOffset) {
                        j.join(this, ourState, theirState, arrayContext)
                    } else {
                        Nondet
                    }

            override fun accessPaths(storage: Map<Storage, Value>?): AnalysisPaths {
                unused(storage)
                return accessPaths
            }
        }

        companion object {
            fun fromConstant(v: TACSymbol.Const): I =
                I(
                    cs = treapSetOf(v.value),
                    i = SimpleQualifiedInt(IntValue.Constant(v.value), setOf()),
                    stride = treapSetOf(Stride.SumOfTerms(v.value))
                )

            fun narrowIdx(v: SValue): SimpleQualifiedInt? =
                when(v) {
                    is I -> v.i
                    else -> null
                }

            fun narrowStrides(v: SValue): TreapSet<Stride>? =
                when (v) {
                    is I -> v.stride
                    else -> null
                }

            fun mergeIdx(value: SValue?, idx: SimpleQualifiedInt?): SValue? =
                if (idx != null && idx != SimpleQualifiedInt.nondet) {
                    if (value is I) {
                        value.copy(i = idx).refineSimpleQualifiedInt(idx)
                    } else if (value is Nondet || value == null) {
                        I(null, idx, treapSetOf(Stride.Top.refineTotal(idx.x)))
                    } else {
                        value
                    }
                } else if (value is I) {
                    value.copy(i = SimpleQualifiedInt.nondet).normalize()
                } else if (value != Nondet) {
                    value
                } else {
                    null
                }

            fun mergeStrides(value: SValue?, s: TreapSet<Stride>?): SValue? =
                if (s != null && (Stride.Top !in s && s.none { it is Stride.SumOfTerms && it.factored.values.any { it.v == IntValue.Nondet }})) {
                    when (value) {
                        is I -> {
                            value.copy(stride = s).refineSimpleQualifiedInt(value.i)
                        }
                        is Nondet, null -> {
                            I(null, SimpleQualifiedInt.nondet, s)
                        }
                        else -> {
                            value
                        }
                    }
                } else if (value is I) {
                    value.copy(stride = Stride.Top.asSet).normalize()
                } else if (value != Nondet) {
                    value
                } else {
                    null
                }

        }
    }

    /**
     * Offset defines the offset in [AnalysisPath.StructAccess]
     */
    @KSerializable
    @Treapable
    sealed class Offset: AmbiSerializable {
        abstract val bytes: BigInteger
        abstract val words: BigInteger
        abstract fun toString(radix: Int) : String

        @KSerializable
        data class Bytes(val numBytes:BigInteger): Offset() {
            override val bytes get() = numBytes
            override val words get() = numBytes.divide(EVM_WORD_SIZE)
            override fun toString(radix: Int): String {
                return numBytes.toString(radix)
            }
        }

        @KSerializable
        data class Words(val numWords:BigInteger): Offset() {
            override val bytes get() = numWords.multiply(EVM_WORD_SIZE)
            override val words get() = numWords
            override fun toString(radix: Int): String {
                return numWords.toString(radix)
            }
        }
    }

     /**
      *  A base for a hash operation, for computing an array or map storage offset
      */
    sealed interface HashBase

    /**
     * An access path that is the result of a hash application, i.e., an array or map
     */
    sealed interface HashResult

    @KSerializable
    @Treapable
    sealed class AnalysisPath: AmbiSerializable {
        abstract fun contains(v: TACSymbol.Var): Boolean
        abstract fun getUsedVariables(): Set<TACSymbol.Var>
        abstract fun killVar(v: TACSymbol.Var): AnalysisPath?

        sealed interface ArrayLikePath {
            val base: AnalysisPath
            val index: TACSymbol?
        }

        @KSerializable
        data class Root(val slot: BigInteger) : AnalysisPath(), HashBase {
            override fun contains(v: TACSymbol.Var) = false
            override fun getUsedVariables(): Set<TACSymbol.Var> = setOf()
            override fun toString(): String = "0x${slot.toString(16)}"
            override fun killVar(v: TACSymbol.Var): AnalysisPath = this
        }
        @KSerializable
        data class MapAccess(val base: AnalysisPath, val key: TACSymbol, val baseSlot: TACSymbol, val hashResult: TACSymbol) : AnalysisPath(), HashResult {
            init {
                check(base is HashBase) {
                    "Map dereference from $base"
                }
            }
            override fun contains(v: TACSymbol.Var): Boolean = key == v || base.contains(v) || hashResult == v
            override fun getUsedVariables(): Set<TACSymbol.Var> {
                val ret = base.getUsedVariables().toMutableSet()

                if(key is TACSymbol.Var) {
                    ret += key
                }

                if (hashResult is TACSymbol.Var) {
                    ret += hashResult
                }

                return ret
            }

            override fun killVar(v: TACSymbol.Var): AnalysisPath? {
                if (key == v || hashResult == v) {
                    return null
                }

                return base.killVar(v)?.let {
                    this.copy(base = it)
                }
            }

            override fun toString(): String = "$base[key $key]"
        }

        @KSerializable
        data class StaticArrayAccess(override val base: AnalysisPath, override val index: TACSymbol?) : AnalysisPath(), HashResult,
            ArrayLikePath {
            init {
                check(base is HashBase) {
                    "static array after dynamic $base"
                }
            }

            override fun toString(): String {
                return "$base[static $index]"
            }

            override fun contains(v: TACSymbol.Var): Boolean = index == v || base.contains(v)

            override fun getUsedVariables(): Set<TACSymbol.Var> {
                return if(index is TACSymbol.Var) {
                    base.getUsedVariables() + index
                } else {
                    base.getUsedVariables()
                }
            }

            override fun killVar(v: TACSymbol.Var): AnalysisPath? {
               return base.killVar(v)?.let { base ->
                   this.copy(
                       base = base,
                       index = index.takeUnless { it == v }
                   )
               }
            }
        }

        @KSerializable
        data class StructAccess(val base: AnalysisPath, val offset: Offset) : AnalysisPath(), HashBase {
            constructor(base: AnalysisPath, wordOffset: BigInteger) : this(base, Offset.Words(wordOffset))
            init {
                check(base is HashResult) {
                    "Have two dynamic bases immediately after each other $base"
                }
            }
            override fun contains(v: TACSymbol.Var): Boolean = base.contains(v)
            override fun getUsedVariables(): Set<TACSymbol.Var> = base.getUsedVariables()
            override fun toString(): String = "$base.(offset 0x${offset.toString(16)})"
            override fun killVar(v: TACSymbol.Var): AnalysisPath? =
                base.killVar(v)?.let { this.copy(base = it) }
        }

        @KSerializable
        data class ArrayAccess(override val base: AnalysisPath, override val index: TACSymbol?, val baseSlot: TACSymbol) : AnalysisPath(), HashResult,
            ArrayLikePath {
            init {
                check(base is HashBase) {
                    "Grammar for analysis paths are violated, have array access immediately following $base"
                }
            }
            override fun contains(v: TACSymbol.Var): Boolean = index == v || base.contains(v) || baseSlot == v
            override fun getUsedVariables(): Set<TACSymbol.Var> {
                val ret = base.getUsedVariables().toMutableSet()
                if (index is TACSymbol.Var) {
                    ret.add(index)
                }

                if (baseSlot is TACSymbol.Var) {
                    ret.add(baseSlot)
                }
                return ret
            }

            override fun killVar(v: TACSymbol.Var): AnalysisPath? {
                if (v == baseSlot) {
                    return null
                }

                return base.killVar(v)?.let { base ->
                    this.copy(
                        base = base,
                        index = index.takeUnless { it == v },
                    )
                }
            }
            override fun toString(): String = "$base[key $index]"
        }

        /**
         * A raw offset from the start of an array segment. Unlike a [StructAccess] we don't know which
         * element or what offset within said element this part of the path represents, only that
         * we are *definitely* [constOffset] words into the array segment.
         *
         * Once we have resolved the parent array's element size THEN we can replace this with the appropriate StructAccess/ArrayAccess
         * combination.
         */
        @KSerializable
        data class WordOffset(val constOffset: BigInteger, val parent: AnalysisPath) : AnalysisPath(), HashBase {
            init {
                check(parent is ArrayAccess && parent.index == BigInteger.ZERO.asTACSymbol()) {
                    "Have (unresolved) word offset from $parent, needed an array"
                }
            }
            override fun contains(v: TACSymbol.Var): Boolean {
                return parent.contains(v)
            }

            override fun getUsedVariables(): Set<TACSymbol.Var> = parent.getUsedVariables()
            override fun killVar(v: TACSymbol.Var): AnalysisPath? =
                parent.killVar(v)?.let { this.copy(parent = it)}

        }
    }


    /**
     * An abstraction representing the pointer values used at a particular storage access.
     * Thus, the [Top] object summarizes all possible pointers,
     * and [PathSet] of the empty set summarizes zero pointers (i.e. an access that is unreachable)
     */
    sealed class AnalysisPaths : Serializable {
        abstract fun join(other: AnalysisPaths): AnalysisPaths
        abstract fun map(f: (AnalysisPath) -> AnalysisPath): AnalysisPaths
        abstract fun toResultOrNull(): StorageAnalysisResult.AccessPaths?
        abstract fun killVar(x: TACSymbol.Var): AnalysisPaths

        object Top : AnalysisPaths() {
            override fun join(other: AnalysisPaths): AnalysisPaths = Top
            override fun map(f: (AnalysisPath) -> AnalysisPath): AnalysisPaths = Top
            override fun toResultOrNull(): StorageAnalysisResult.AccessPaths? = null
            override fun killVar(x: TACSymbol.Var): AnalysisPaths = Top

            fun readResolve(): Any = Top
        }

        data class PathSet(val paths: Set<AnalysisPath>) : AnalysisPaths() {
            override fun join(other: AnalysisPaths): AnalysisPaths =
                    when (other) {
                        Top -> Top
                        is PathSet -> PathSet(this.paths.union(other.paths))
                    }

            fun mapPaths(f: (AnalysisPath) -> AnalysisPath): PathSet =
                    PathSet(paths.map(f).toSet())

            override fun map(f: (AnalysisPath) -> AnalysisPath): AnalysisPaths = mapPaths(f)

            override fun toResultOrNull(): StorageAnalysisResult.AccessPaths = StorageAnalysisResult.AccessPaths(paths)

            override fun killVar(x: TACSymbol.Var): AnalysisPaths {
                return this.paths.monadicMap {
                    it.killVar(x)
                }?.toSet()?.let(AnalysisPaths::PathSet) ?: Top
            }
        }
    }

    private fun Map<TACSymbol.Var, SValue>.interp(op: TACSymbol) =
            when (op) {
                is TACSymbol.Const ->
                    SValue.fromConstant(op)
                is TACSymbol.Var -> {
                    this[op] ?: SValue.Nondet
                }
            }

    /**
     * If [where] is an [TACCmd.Simple.AssigningCmd], finds any reference to [where.cmd.lhs]
     * in [state] and kills it: for (Static) Array accesses this means setting indices to null because they
     * can be recomputed later. For the results of hash functions, this means killing the path, as it is invalid
     * (and hence setting the containing AnalysisPaths value to Top).
     *
     * Note that the analysis saves such non-recomputable values to fresh variables and uses the *fresh* vars in the
     * analysis paths, so the killVar invocation shouldn't actually end up killing any access paths.
     */
    private fun removeAssigned(where: LTACCmd, state: TreapMap<TACSymbol.Var, SValue>) : TreapMap<TACSymbol.Var, SValue> {
        if (where.cmd is TACCmd.Simple.AssigningCmd) {
            return state.parallelUpdateValues { _, sValue ->

                fun updateAccessPaths(accessPaths: AnalysisPaths) =
                    accessPaths.killVar(where.cmd.lhs)

                when (sValue) {
                    is SValue.I -> {
                        if (sValue.usesVar(storage, where.cmd.lhs)) {
                            sValue.killVar(where.cmd.lhs)
                        } else {
                            sValue
                        }
                    }
                    is SValue.Nondet -> {
                        sValue
                    }
                    is SValue.StoragePointer -> {
                        if(!sValue.usesVar(storage, where.cmd.lhs)) {
                            sValue
                        } else {
                            sValue.withPaths(
                                accessPaths = updateAccessPaths(sValue.accessPaths(getStorageHint()))
                            )
                        }
                    }
                    is SValue.ArrayStart -> {
                        sValue.copy(accessPaths = updateAccessPaths(sValue.accessPaths))
                    }
                    is SValue.UnresolvedOffset -> {
                        sValue.copy(parentPath = updateAccessPaths(sValue.parentPath))
                    }
                }
            }
        } else {
            return state
        }
    }

    // When we can't prove from the storage analysis
    // that a given value is bounded (i.e. an IntegerWithBounds),
    // we'll emit an assert to be dispatched by SMT later
    //
    // Here we map the cmd pointers to the desired range,
    // and the invariant is that
    // graph.elab(ptr) is a TACCmd.Simple.WordStore
    private val boundedIntegerAssertions = mutableMapOf<CmdPointer, IntValue>()

    /** This function can be called in a context where we have an [indexableBase] that might _look_ like a pointer
     * into storage, but for whatever reason isn't really (or hasn't been fully computed, etc).
     *
     * @param freshVariables the map in which to store fresh variable names
     * @return the mapping pointer if the pointer can be interpreted as such
     */
    private fun createMapKey(
        indexableBaseSymbol: TACSymbol,
        indexableBase: Indexable,
        indexVar: TACSymbol,
        lhs: TACSymbol.Var,
        freshVariables: FreshVarAllocator,
        where: LTACCmd
    ) : SValue.StoragePointer.MappingPointer? {
        // hashing 0x40 bytes i.e. 2 words
        val lhsVal = mutableSetOf<Storage>()
        val accessPaths = indexableBase
            .accessPaths(getStorageHint()).map { p ->
                AnalysisPath.MapAccess(
                    base = if (p !is HashBase) {
                        AnalysisPath.StructAccess(offset = Offset.Words(BigInteger.ZERO), base = p)
                    } else {
                        p
                    },
                    key = indexVar,
                    hashResult = freshVariables.allocFresh(where.ptr, lhs, true, suffix="hashResult"),
                    baseSlot = freshVariables.allocFresh(where.ptr, indexableBaseSymbol, false, suffix="hashBase"),
                )
            }

        val (storageKeys, _) = indexableBase.toStorage() ?: return null

        if (storageKeys.any { key ->
            storage[key]?.let { it !is Value.Mapping } == true
        }) {
            return null
        }

        for (key in storageKeys) {
            check(key.find() == key)
            if (key !in storage) {
                val elemKey = Storage.Derived(heapLoc++)
                storage[key] = Value.Mapping(elemKey.v)
                lhsVal.add(elemKey)
            } else {
                val x = storage[key]!!
                // The cast is justified by the check above
                lhsVal.add(Storage.Derived((x as Value.Mapping).codom))
            }
        }
        return SValue.StoragePointer.MappingPointer(lhsVal, accessPaths)
    }

    private fun createMapKey(
        baseSymbol: TACSymbol,
        base: SValue,
        indexVar: TACSymbol,
        lhs: TACSymbol.Var,
        freshVariables: FreshVarAllocator,
        where: LTACCmd
    ) : SValue.StoragePointer.MappingPointer? =
        base.tryAs<Indexable>()?.let { indexableBase ->
            createMapKey(baseSymbol, indexableBase, indexVar, lhs, freshVariables, where)
        }

    private fun deref(key_: Storage, first: BigInteger): Pair<Storage, Boolean> {
        val key = key_.find()
        return if (key !in storage) {
            val x = heapLoc++
            storage[key] = Value.Struct(
                    elems = mutableMapOf(first to x)
            )
            Storage.Derived(x) to true
        } else if (key in storage && storage[key]!! is Value.Word) {
            val fst = heapLoc++
            storage[key] = Value.Struct(
                    elems = mutableMapOf(BigInteger.ZERO to fst)
            )
            storage[Storage.Derived(fst)] = Value.Word
            if (first == BigInteger.ZERO) {
                Storage.Derived(fst) to true
            } else {
                val snd = heapLoc++
                (storage[key]!! as? Value.Struct)?.let { b -> b.elems[first] = snd }
                Storage.Derived(snd) to true
            }
        } else if (key in storage && storage[key]!! is Value.Struct) {
            val x = storage[key]!!
            check(x is Value.Struct)
            val mut = first !in x.elems
            val ret = x.elems.computeIfAbsent(first) { heapLoc++ }
            Storage.Derived(ret).find() to mut
        } else {
            throw StorageAnalysisFailedException("can't deref $key with ${storage[key]}", null)
        }
    }

    private fun generateFreshArray(key: Storage): Storage.Derived {
        val elemKey = Storage.Derived(heapLoc++)
        storage[key] = Value.Array(elemKey.v)
        return elemKey
    }

    class StorageAnalysisObjectReference<T: Any>(var value: T? = null)

    private fun readValues(keys: Set<Storage>, state: TreapMap<TACSymbol.Var, SValue>): Pair<SValue, Boolean> {
        val l = keys.toList()
        var (ret, bounded) = readValue(l[0])
        for (i in 1..l.lastIndex) {
            val (j, b) = readValue(l[i])
            ret = ret.join(j, state, state, arrayElemSizes)
            bounded = bounded && b
        }
        return Pair(ret, bounded)
    }

    private fun readValue(key_: Storage): Pair<SValue, Boolean> {
        val key = key_.find()
        if (key !in storage) {
            storage[key] = Value.Word
            return Pair(SValue.Nondet, false)
        }
        return storage[key]!!.let {
            when (it) {
                is Value.Array,
                Value.Word -> Pair(SValue.Nondet, false)
                is Value.Mapping -> throw StorageAnalysisFailedException("Cannot read from map at $key", null)
                is Value.Struct -> {
                    it.elems.computeIfAbsent(BigInteger.ZERO) { heapLoc++ }.let(Storage::Derived).let { k ->
                        Pair(readValue(k).first, false)
                    }
                }
                is Value.StaticArray -> storage[Storage.Derived(it.elems)]?.let {
                    it as? Value.Struct
                }?.elems?.get(BigInteger.ZERO)?.let {
                    readValue(Storage.Derived(it))
                } ?: throw StorageAnalysisFailedException("Cannot find elements for $key", null)
                is Value.IntegerWithBounds -> {
                    Pair(SValue.I(
                        cs = null,
                        i = SimpleQualifiedInt(it.valueRange, setOf()),
                        stride = treapSetOf(Stride.SumOfTerms(Stride.SymValue(null, it.valueRange)))
                    ), true)
                }
            }
        }
    }

    /**
     * @param addAssertion is a callback that's executed if we can't prove
     *        that the first argument satisfies the invariant implied by the second
     * @return true iff [storage] has been modified
     */
    private fun writeValue(ptr: Set<Storage>, v: SValue, cmdPtr: CmdPointer, addAssertion: (CmdPointer, SValue, Value) -> Unit): Boolean {
        var toRet = false
        for (k in ptr) {
            toRet = writeValue(k, v, cmdPtr, addAssertion) || toRet
        }
        return toRet
    }

    /** Placeholder for state that is discarded by [MethodWorker.collectGarbage]. */
    private object Discarded

    private inner class MethodWorker(val graph: TACCommandGraph, val linearInvariants: GlobalInvariantAnalysisResult) {

        val loops = getNaturalLoops(graph).groupBy { loop -> loop.head }

        private val inState = mutableMapOf<NBId, TreapMap<TACSymbol.Var, SValue>>()
        private val outState = mutableMapOf<NBId, TreapMap<TACSymbol.Var, SValue>>()

        private val visited = mutableSetOf<NBId>()

        private val interpreter = SimpleQualifiedIntAbstractInterpreter(
                graph = graph,
                narrowIdx = SValue::narrowIdx,
                mergeIdx = SValue::mergeIdx,
        )

        /** If we're going from SValue to [suchThat]=IntegerWithBounds, just remember that we needed to write
         *  [suchThat]'s bound. We'll generate the assert later.
         */
        private fun assertIsBoundedInteger(where: CmdPointer, from: SValue, suchThat: Value) {
            when (suchThat) {
                is Value.IntegerWithBounds -> {
                    if (!Config.OptimisticVyperArrayLengthWrites.get()) {
                        boundedIntegerAssertions.updateInPlace(where, suchThat.valueRange) {
                            if (it.mayIntersect(suchThat.valueRange)) {
                                suchThat.valueRange.withLowerBound(it.lb).withUpperBound(it.ub)
                            } else {
                                throw StorageAnalysisFailedException(
                                    "Inconsistent storage requirements at ${where}: need ${it} and ${suchThat}",
                                    where
                                )
                            }
                        }
                    } else {
                        logger.warn {
                            "At ${where}, ignoring side condition that ${from} must be a ${suchThat}"
                        }
                    }
                }

                else -> {
                    throw StorageAnalysisFailedException(
                        "Storage requires write of ${suchThat} but have ${from}",
                        where
                    )
                }
            }
        }

        /**
         * Use the linear invariants before [where] to strengthen the abstract value of [sym] before
         * computing the possible storage referred to by [sym]
         */
        private fun Indexable.refineAndGetStorage(
            sym: TACSymbol,
            where: CmdPointer,
            state: TreapMap<TACSymbol.Var, SValue>
        ): Pair<Set<Storage>, Boolean>? {
            val invariants = linearInvariants.getBeforeLocation(where)
            val refinedPtr = refineValWithInvariants(sym, this, invariants, state)
            return refinedPtr.toStorage()
        }

        /**
         * Use the linear invariants before [where] to strengthen the abstract value of [sym] before
         * computing the possible access paths of [sym].
         */
        private fun HasAnalysisPaths.refineAndGetAnalysisPaths(
            sym: TACSymbol,
            where: CmdPointer,
            state: TreapMap<TACSymbol.Var, SValue>
        ): AnalysisPaths {
            when (this) {
                is Indexable -> {
                    val invariants = linearInvariants.getBeforeLocation(where)
                    val refinedPtr = refineValWithInvariants(sym, this, invariants, state)
                    return refinedPtr.accessPaths(getStorageHint())
                }
                else ->
                    return accessPaths(getStorageHint())
            }
        }

        private fun stepCommandStorage(where: LTACCmd, state: TreapMap.Builder<TACSymbol.Var, SValue>, boundedRead: StorageAnalysisObjectReference<SValue>) {

            if (where.cmd is TACCmd.Simple.WordStore &&
                    where.cmd.base.meta.containsKey(TACMeta.STORAGE_KEY)) {
                state.interp(where.cmd.loc).let { ptr ->
                    when {
                        where.cmd.loc is TACSymbol.Var && ptr is SValue.ArrayStart -> {
                            val newPtr = resolvedArrayStartElementPointer(ptr.elementPointers, ptr.accessPaths)
                                ?: SValue.UnresolvedOffset(
                                    constOffset = BigInteger.ZERO,
                                    elementStorage = ptr.elementPointers, parentPath = ptr.accessPaths
                                )
                            state[where.cmd.loc] = newPtr
                            val (keys, _) = newPtr.toStorage()!! // always succeed for UnresolvedOffset
                            writeValue(keys, state.interp(where.cmd.value), where.ptr, ::assertIsBoundedInteger)
                        }

                        ptr is Indexable -> {
                            val (keys, mut) = ptr.refineAndGetStorage(where.cmd.loc, where.ptr, state.build())
                                ?: throw StorageAnalysisFailedException("Expected ${where.cmd.loc} @ ${where.ptr} : $ptr to refer to at least one storage location in $storage", where.ptr)
                            writeValue(keys, state.interp(where.cmd.value), where.ptr, ::assertIsBoundedInteger) || mut
                        }

                        else -> throw StorageAnalysisFailedException("Expected ${where.cmd.loc} to be indexable but got $ptr", where.ptr)
                    }
                }
            } else if (where.cmd is TACCmd.Simple.AnnotationCmd) {
                where.maybeAnnotation(ITERATION_VARIABLE_BOUND)?.let {
                    guessModularUpperBound(state, it.iterationVariable, it.boundVariable)?.let { sVal ->
                        state[it.iterationVariable] = sVal
                    }
                }
            } else if (where.cmd is TACCmd.Simple.AssigningCmd.ByteLoad) {
                state[where.cmd.lhs] = where.cmd.meta[StorageCodedataSlotAnnotator.KEY]?.let { slot ->
                    SValue.I.Constant(slot)
                } ?: SValue.Nondet
            } else if (where.cmd is TACCmd.Simple.AssigningCmd.WordLoad &&
                    where.cmd.base.meta.containsKey(TACMeta.STORAGE_KEY)) {
                state.interp(where.cmd.loc).let { ptr ->
                    when {
                        where.cmd.loc is TACSymbol.Var && ptr is SValue.ArrayStart -> {
                            val newPtr = resolvedArrayStartElementPointer(ptr.elementPointers, ptr.accessPaths)
                                ?: SValue.UnresolvedOffset(constOffset = BigInteger.ZERO,
                                    elementStorage = ptr.elementPointers, parentPath = ptr.accessPaths
                                )
                            val (keys, _) = newPtr.toStorage()!! // Always succeed on UnresolvedOffset
                            val (res, _) = readValues(keys, state.build())
                            state[where.cmd.loc] = newPtr
                            state[where.cmd.lhs] = res
                        }
                        ptr is Indexable -> {
                            val (keys, _) = ptr.refineAndGetStorage(where.cmd.loc, where.ptr, state.build()) ?:
                                throw StorageAnalysisFailedException("Expected ${where.cmd.loc} @ ${where.ptr} : ${ptr} to refer to at least one storage location in $storage", where.ptr)
                            val (res, b) = readValues(keys, state.build())
                            boundedRead.value = res.takeIf { b }
                            state[where.cmd.lhs] = res
                        }
                        else -> throw StorageAnalysisFailedException("Expected ${where.cmd.loc} @ ${where.ptr} : ${ptr} to be keyable", where.ptr)
                    }
                }
            } else if (where.cmd is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd && where.cmd.args.size in 1..2 && TACMeta.DECOMPOSED_USER_HASH !in where.cmd.meta) {
                /**
                 * tacM0xX is not valid to use in an access path since it gets reassigned, so we replace any occurrence
                 * with it's most recent definition
                 */
                /*
                 * BG: 20230209
                 * Parameterized to allow "removing" tacM0x20 instead when hash args are reversed
                 */
                // If the arg is memx, find the RHS of the assignment to memx
                val removeMx = { memx: TACSymbol.Var, variable: TACSymbol ->
                    if (variable == memx) {
                        val anyNonMEMXSym = PatternMatcher.Pattern.Or(
                            first = PatternMatcher.Pattern.FromVar(
                                extractor = { _, v ->
                                    if (v != memx) {
                                        PatternMatcher.VariableMatch.Match(v)
                                    } else {
                                        PatternMatcher.VariableMatch.Continue
                                    }
                                }
                            ),
                            second = PatternMatcher.Pattern.FromConstant(
                                extractor = { it -> it },
                                out = { _, i -> i }
                            ),
                            adapt1 = { it },
                            adapt2 = { it },
                            patternName = null
                        )
                        PatternMatcher.compilePattern(graph = graph, patt = anyNonMEMXSym).query(memx, where).toNullableResult()
                    } else {
                        variable
                    }
                }

                // base pointer for hash: 0x0 (or 0x20 if args are reversed, which happens for some input languages)
                /*
                 * After inlining delegates, an instance of MEMx will have the call ID of the inlined call added
                 * as a call index. This call index will be the call id of the containing block, so we use
                 * that index as the argument to toVar
                 */
                val (indexVar, baseMapVar) =
                    if(!storageHashArgsReversed) {
                        val mem0 = TACKeyword.MEM0.toVar(where.ptr.block.getCallId())
                        where.cmd.args.getOrNull(0)?.let{ removeMx(mem0, it) } to
                            where.cmd.args.getOrNull(1)?.let{ removeMx(mem0, it) }
                    } else {
                        val mem32 = TACKeyword.MEM32.toVar(where.ptr.block.getCallId())
                        where.cmd.args.getOrNull(1)?.let{ removeMx(mem32, it) } to
                            where.cmd.args.getOrNull(0)?.let{ removeMx(mem32, it) }
                    }
                val indexableBase = baseMapVar?.let { state.interp(it) }

                if (indexVar != null && indexableBase is Indexable) {
                    state[where.cmd.lhs] = createMapKey(
                        indexableBaseSymbol = baseMapVar,
                        indexableBase = indexableBase,
                        indexVar = indexVar,
                        lhs = where.cmd.lhs,
                        freshVariables = freshAlloc,
                        where = where,
                    ) ?: SValue.Nondet
                } else if (indexVar is TACSymbol && state.interp(indexVar) is Indexable && where.cmd.args.size == 1) {

                    val (keys, _) = (state.interp(indexVar) as Indexable).toStorage() ?: run {
                        state[where.cmd.lhs] = SValue.Nondet
                        return
                    }

                    // Make sure all of these keys make sense, i.e. can be converted to arrays.
                    // `indexVar` could be a very imprecise integer that includes all sorts of bogus
                    // storage locations
                    keys.monadicMap { key ->
                        check(key == key.find())
                        when (val x = storage[key]) {
                            null -> generateFreshArray(key)
                            is Value.Word -> generateFreshArray(key)
                            is Value.Array -> Storage.Derived(x.elem).find() as Storage.Derived
                            else -> null
                        }
                    }?.let { lhsValue ->

                        val accessPaths = (state.interp(indexVar) as Indexable).accessPaths(getStorageHint()).map { path ->
                            AnalysisPath.ArrayAccess(
                                base = if (path !is HashBase) {
                                    AnalysisPath.StructAccess(offset = Offset.Words(BigInteger.ZERO), base = path)
                                } else {
                                    path
                                },
                                index = TACSymbol.lift(0),
                                baseSlot = (indexVar as? TACSymbol.Const) ?: freshAlloc.allocFresh(where.ptr, indexVar, false, suffix="arrayBase"),
                            )
                        }

                       state[where.cmd.lhs] = SValue.ArrayStart(keys, lhsValue.toSet(), accessPaths)
                    } ?: run {
                        state[where.cmd.lhs] = SValue.Nondet
                    }
                } else {
                    state[where.cmd.lhs] = SValue.Nondet
                }

            } else if (where.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                    where.cmd.rhs is TACExpr.Apply &&
                    (where.cmd.rhs.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif is TACBuiltInFunction.OpaqueIdentity &&
                    where.cmd.rhs.ops.singleOrNull() is TACExpr.Sym) {
                val s = where.cmd.rhs.ops.single().let { it as TACExpr.Sym }.s
                val newSValue = state.interp(s)
                state[where.cmd.lhs] = newSValue
            } else if (where.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                val lhs = where.cmd.lhs
                when (where.cmd.rhs) {
                    is TACExpr.Sym -> {
                        val newSValue = state.interp(where.cmd.rhs.s)
                        state[where.cmd.lhs] = newSValue
                    }
                    is TACExpr.BinOp.ShiftRightLogical, is TACExpr.BinOp.ShiftLeft -> {
                        // second arg is shift factor
                        val shiftFactor = (where.cmd.rhs as TACExpr.BinOp).o2
                        val arg = where.cmd.rhs.o1
                        if (shiftFactor.getAsConst() == BigInteger.ZERO && arg is TACExpr.Sym) {
                            val newSValue = state.interp(arg.s)
                            state[where.cmd.lhs] = newSValue
                        } else {
                            state[lhs] = SValue.Nondet
                        }
                    }
                    is TACExpr.Vec.IntAdd,
                    is TACExpr.Vec.Add -> {
                        (where.cmd.rhs as TACExpr.BinExp).o1AsNullableTACSymbol()?.let { s1 ->
                            where.cmd.rhs.o2AsNullableTACSymbol()?.let { s2 ->
                                val (m, storageMutated) = plusSemantics(s1, s2, where, state)
                                logger.debug { "offset operation at $where yielded $m" }
                                val stateMutated = state[lhs] != m
                                val mutated = storageMutated || stateMutated
                                state[lhs] = m
                                if (mutated) {
                                    logger.debug { "New storage $storage" }
                                }
                            }
                        } ?: run {
                            state[lhs] = SValue.Nondet
                        }
                    }
                    is TACExpr.BinOp.Sub -> {
                        (where.cmd.rhs as TACExpr.BinExp).o1AsNullableTACSymbol()?.let { s1 ->
                            where.cmd.rhs.o2AsNullableTACSymbol()?.let { s2 ->
                                state[lhs] = minusSemantics(s1, s2, state)
                            }
                        } ?: run {
                            state[lhs] = SValue.Nondet
                        }
                    }
                    is TACExpr.BinOp.BWAnd -> {
                        state[lhs] = constBinOpSemantics(where.cmd.rhs, state) { c1, c2 -> c1 and c2 }
                    }
                    is TACExpr.BinOp.BWOr -> {
                        state[lhs] = constBinOpSemantics(where.cmd.rhs, state) { c1, c2 -> c1 or c2 }
                    }
                    is TACExpr.TernaryExp.Ite -> {
                        val newVal = where.cmd.rhs.o2AsNullableTACSymbol()?.let { then ->
                            where.cmd.rhs.o3AsNullableTACSymbol()?.let { els ->
                                val s = state.build()
                                state.interp(then).join(
                                    state.interp(els),
                                    s,
                                    s,
                                    arrayElemSizes,
                                    storage
                                )
                            }
                        } ?: SValue.Nondet
                        state[lhs] = newVal
                    }
                    else -> {
                        state[lhs] = SValue.Nondet
                    }
                }
            } else if (where.cmd is TACCmd.Simple.AssigningCmd) {
                state[where.cmd.lhs] = SValue.Nondet
            }
        }

        /**
         * Return an ElementPointer at index 0 with elementPointers [elementPointers] and accessPaths [accessPaths]
         * if we've resolved the size of the given element pointes already.
         */
        private fun resolvedArrayStartElementPointer(elementPointers: Set<Storage.Derived>, accessPaths: AnalysisPaths): SValue.StoragePointer.ElementPointer? {
            return if (elementPointers.any { arrayElemSizes[it] != null }) {
                SValue.StoragePointer.ElementPointer(
                        elementPointers,
                        accessPaths.map {
                            check(it is AnalysisPath.ArrayAccess)
                            it.copy(index = BigInteger.ZERO.asTACSymbol())
                        }
                )
            } else {
                null
            }
        }

        private fun Map<TACSymbol.Var, TreapSet<Stride>>.interpretSum(s: TACSymbol): TreapSet<Stride> {
            return when (s) {
                is TACSymbol.Var -> {
                    val default = Stride.Top.asSet
                    this.getOrDefault(s, default).let {
                        if (Stride.Top in it) {
                            default
                        } else {
                            it.updateElements { it.withName(s) }
                        }
                    }
                }
                is TACSymbol.Const -> {
                    treapSetOf(Stride.SumOfTerms(s.value))
                }
            }
        }

        fun TreapSet<Stride>.add(other: TreapSet<Stride>): TreapSet<Stride> {
            val sums = treapSetBuilderOf<Stride>()
            for (s1 in this) {
                for (s2 in other) {
                    if (s1 is Stride.SumOfTerms && s2 is Stride.SumOfTerms) {
                        sums += s1.add(s2)
                    } else {
                        return Stride.Top.asSet
                    }
                }
            }
            return sums.build()
        }

        /**
         * Tracks strides, i.e. sums of terms:
         *   x := k*y ==> x's stride is k*stride(y)
         *   x := y + z ==> x's stride is stride(y) + stride(z)
         */
        fun stepStrides(where: LTACCmd, wrappedState: TreapMap<TACSymbol.Var, SValue>): ProjectedMap<TACSymbol.Var, SValue, TreapSet<Stride>> {
            val state = ProjectedMap(
                    wrapped = wrappedState,
                    narrow = SValue::narrowStrides,
                    merge = SValue::mergeStrides,
            )


            fun TreapSet<Stride>.mul(c: TACSymbol.Const): TreapSet<Stride> =
                updateElements { it.multiply(c) }

            fun TreapSet<Stride>.div(c: TACSymbol.Const): TreapSet<Stride> =
                updateElements { it.divide(c) }

            fun ProjectedMap<TACSymbol.Var, SValue, TreapSet<Stride>>.extend(x: TACSymbol.Var, stride: TreapSet<Stride>): ProjectedMap<TACSymbol.Var, SValue, TreapSet<Stride>> =
                if (Stride.Top in stride)  {
                    this
                } else {
                    this + (x to stride)
                }

            when (val cmd = where.cmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    when(val rhs = cmd.rhs) {
                        is TACExpr.Vec.IntAdd,
                        is TACExpr.Vec.Add ->  {
                            (rhs as TACExpr.BinExp).o1AsNullableTACSymbol()?.let { s1 ->
                                rhs.o2AsNullableTACSymbol()?.let { s2 ->
                                    return state.extend(cmd.lhs, state.interpretSum(s1).add(state.interpretSum(s2)))
                                }
                            }
                        }

                        is TACExpr.Vec.Mul,
                        is TACExpr.Vec.IntMul -> {
                            (rhs as TACExpr.BinExp).o1AsNullableTACSymbol()?.let { s1 ->
                                rhs.o2AsNullableTACSymbol()?.let { s2 ->
                                    when {
                                        s1 is TACSymbol.Const -> s1 to s2
                                        s2 is TACSymbol.Const -> s2 to s1
                                        else -> null
                                    }?.let { (const, v) ->
                                        return state.extend(cmd.lhs, state.interpretSum(v).mul(const))
                                    }
                                }
                            }
                        }

                        is TACExpr.BinOp.Div -> {
                            (rhs as TACExpr.BinExp).o1AsNullableTACSymbol()?.let { s1 ->
                                rhs.o2AsNullableTACSymbol()?.let { s2 ->
                                    s1 `to?` (s2 as? TACSymbol.Const)
                                }?.let { (v, const) ->
                                    return state.extend(cmd.lhs, state.interpretSum(v).div(const))
                                }
                            }
                        }

                        is TACExpr.Sym -> {
                            val sum = state.interpretSum(rhs.s)
                            return state.extend(cmd.lhs, sum)
                        }

                        else -> {}
                    }
                }
                else -> {}
            }

            return state
        }

        /**
         * Return [xInterval] \meet_{T} i_T
         *
         * for all T s.t. [x] == T, where T = a*y + b*z + .. + off in [invariants]
         *    then i_T == the interval abstracting T in state [state]
         */
        private fun refineIntervalWithInvariants(
            x: TACSymbol.Var,
            xInterval: IntValue,
            invariants: LinearInvariants,
            state: TreapMap<TACSymbol.Var, SValue>
        ): IntValue {
            // if x = a*y + K, then intersect xInterval with (the IntVal abstraction of) a*y + k
            return invariants.matches {
                x `=` k("a")*v("y") + k("off")
            }.mapNotNull { m ->
                val y = (m.symbols["y"]!! as? LVar.PVar)?.v ?: return@mapNotNull null
                val off = m.factors["off"]!!
                val a = m.factors["a"]!!

                // Compute a*y,
                // which requires remembering to flip the interval if a < 0
                val yInterval = state.interp(y).tryAs<SValue.I>()?.i?.x ?: return@mapNotNull  null
                val absA = a.abs()
                val scaled = yInterval.mult(IntValue.Constant(absA)).takeIf { !it.second }?.first ?: return@mapNotNull null
                val signedAndScaled = if (a < BigInteger.ZERO) {
                    IntValue(scaled.ub.negate(), scaled.lb.negate())
                } else {
                    scaled
                }

                IntValue.Constant(off).add(signedAndScaled).takeIf { !it.second }?.first?.takeIf {
                    BigInteger.ZERO <= it.lb
                }
            }.fold(xInterval) { i, j ->
                i.withLowerBound(
                    j.lb
                ).withUpperBound(
                    j.ub
                )
            }
        }

        /**
         * Refine all the intervals in [xValue] via [refineIntervalWithInvariants]
         */
        private fun refineValWithInvariants(
            x: TACSymbol,
            xVal: Indexable,
            invariants: LinearInvariants,
            state: TreapMap<TACSymbol.Var, SValue>
        ): Indexable {
            if (x !is TACSymbol.Var || xVal !is SValue.I) {
                return xVal
            }
            val xInterval = xVal.i.x
            val newInterval = refineIntervalWithInvariants(x, xInterval, invariants, state)
            val strides = xVal.stride.mapToTreapSet {
                it.refineTotal(newInterval)
                    ?.tryAs<Stride.SumOfTerms>()?.let { sum ->
                        Stride.SumOfTerms.sumOf(
                            sum.factored.mapValues {
                                it.value.x?.let { v ->
                                    Stride.SymValue(v, refineIntervalWithInvariants(v, it.value.v, invariants, state))
                                } ?: it.value
                            },
                            sum.off
                        )
                } ?: it
            }
            return xVal.copy(i = xVal.i.copy(x = newInterval), stride = strides)
        }

        private fun stepCommand(where: LTACCmd, state: TreapMap<TACSymbol.Var, SValue>): TreapMap<TACSymbol.Var, SValue> {
            val mutableState = removeAssigned(where, state).builder()
            val boundedRead = StorageAnalysisObjectReference<SValue>()
            stepCommandStorage(where, mutableState, boundedRead)
            val strideState = stepStrides(where, mutableState.build()).wrapped
            val intervalState = interpreter.step(where, SimpleQualifiedIntAbstractInterpreterState(strideState, boundedRead)).wrapped
            return intervalState
        }

        private val arrayAccessPattern =
            PatternMatcher.compilePattern(graph,
                PatternDSL.build {
                    (Const * Var).commute.withAction { const, variable -> const to variable }
                })

        private fun SValue.ArrayStart.knownElemSize(): BigInteger? {
            return elementPointers.mapNotNull {
                arrayElemSizes[it]
            }.uniqueOrNull()
        }

        private fun SValue.StoragePointer.ElementPointer.knownElemSize(): BigInteger? {
            return base.mapNotNull {
                arrayElemSizes[it]
            }.uniqueOrNull()
        }

        /**
         * Apply `op` to (the cross product of) the may-equal set of constants, [i1.cs] and [i2.cs]
         *
         * @return SValue.I({ op(c1, c2) | c1 \in i1.cs, c2 \in i2.cs }, T, T) if
         *           either [i1.cs] or [i2.cs] is singleton (and both are non-null, non-empty),
         *         OTHERWISE Nondet
         */
        private fun constBinOpSemantics(i1: SValue.I, i2: SValue.I, op: (BigInteger, BigInteger) -> BigInteger): SValue {
            if (i1.cs == null || i2.cs == null || (i2.cs.size > 1 && i1.cs.size > 1)) {
                return SValue.Nondet
            }
            val cs = i1.cs.asSequence().map { c1 ->
                i2.cs.updateElements { c2 ->
                    op(c1, c2)
                }
            }.unionAll()

            return SValue.I(cs, SimpleQualifiedInt.nondet, Stride.Top.asSet)
        }

        /**
         * Try to apply `op` to the may-equal-constant [SValue.I.cs] abstraction (see above)
         */
        private fun constBinOpSemantics(expr: TACExpr.BinOp, state: Map<TACSymbol.Var, SValue>, op: (BigInteger, BigInteger) -> BigInteger): SValue {
            val i1 = expr.o1AsNullableTACSymbol()?.let{ state.interp(it) } as? SValue.I ?: return SValue.Nondet
            val i2 = expr.o2AsNullableTACSymbol()?.let{ state.interp(it) } as? SValue.I ?: return SValue.Nondet
            return constBinOpSemantics(i1, i2, op)
        }

        private fun plusSemanticsUnresolvedOffset(v1: SValue.UnresolvedOffset, v2AsConst: BigInteger?): Pair<SValue, Boolean> {
            return if(v2AsConst == null) {
                SValue.Nondet to false
            } else {
                v1.copy(
                    constOffset = v1.constOffset + v2AsConst
                ) to false
            }
        }

        /**
         * @param v (the abstract value of) some offset that we are treating as an index
         *        into an array, i.e. it should be equivalent to some size multiplied by some logical
         *        index.
         * @param x the var that [v] abstracts
         * @return (sz, idx) the decomposition of (the value of v2) into the size (sz) and index (idx).
         *         either sz == size OR we couldn't prove that exists idx. s2 == [size]*idx,
         *         so we gave up and returned (1, [s2])
         */
        private fun tryInferIndex(where: LTACCmd, size: BigInteger, v: IntegralType, x: TACSymbol.Var): Pair<BigInteger, TACSymbol?> {
            if (size == BigInteger.ONE) {
                // No need to get fancy here
                return Pair(size, x)
            }

            return arrayAccessPattern.query(x, where).toNullableResult()?.let { (sz, index) ->
                    Pair(size, index).takeIf { sz == size }
                } ?: Pair(size, null).takeIf {
                    v.mustBeMultipleOf(size)
                } ?: Pair(BigInteger.ONE, x) // This will almost certainly fail the analysis in the next step
        }

        /** @return a pair (size, i) such that the value of x is equal to size*i */
        private fun tryInferSizeAndIndex(where: LTACCmd, x: TACSymbol.Var): Pair<BigInteger, TACSymbol> {
            // case 1.b
            return arrayAccessPattern.query(x, where).toNullableResult() ?: Pair(BigInteger.ONE, x)
        }

        private fun plusSemanticsArray(where: LTACCmd, v1: SValue.ArrayStart, v2: SValue, v2AsConst: BigInteger?, s2: TACSymbol): Pair<SValue, Boolean> {
            if (v2 !is IntegralType) {
                return SValue.Nondet to false
            }

            val foldedVarTimesConst = where.cmd.meta.find(ConstantComputationInliner.INLINED_MULTIPLICATION)?.takeIf {
                (it.o1 is TACSymbol.Var && it.o2 is TACSymbol.Const) || (it.o1 is TACSymbol.Const && it.o2 is TACSymbol.Var)
            }?.let {(v1, v2) ->
                if(v1 is TACSymbol.Const) {
                    (v2 as TACSymbol.Var) to v1
                } else {
                    (v1 as TACSymbol.Var) to (v2 as TACSymbol.Const)
                }
            }

            // case 1.a
            return if (v2AsConst != null && foldedVarTimesConst == null) {
                val elemSizes = v1.knownElemSize()
                if (elemSizes != null) {
                    val fieldOffset = v2AsConst.mod(elemSizes)
                    if (fieldOffset == BigInteger.ZERO) {
                        SValue.StoragePointer.ElementPointer(v1.elementPointers, v1.accessPaths.map { path ->
                            check(path is AnalysisPath.ArrayAccess)
                            path.copy(index = (v2AsConst / elemSizes).asTACSymbol())
                        }) to false
                    } else {
                        handleFieldAddition(
                                where.ptr, v1.elementPointers, offset = fieldOffset, v1.accessPaths.map {
                            check(it is AnalysisPath.ArrayAccess)
                            it.copy(index = (v2AsConst / elemSizes).asTACSymbol())
                        }
                        )
                    }
                } else {
                    // 1.a.ii
                    SValue.UnresolvedOffset(v1.elementPointers, v2AsConst, v1.accessPaths) to false
                }
                // case 1.b (masquerading as a constant addition)
            } else if (v2AsConst != null && foldedVarTimesConst != null) {
                // If we've already resolved the size, then we can only make progress
                // if v2 is a multiple of the existing size
                val (sz, idx) = v1.knownElemSize()?.let { sz ->
                    val (idx, rem) = v2AsConst.divideAndRemainder(sz)
                    Pair(sz, idx).takeIf { rem == BigInteger.ZERO }
                }.takeIf {
                    checkingMode
                } ?: Pair(foldedVarTimesConst.second.value, v2AsConst / foldedVarTimesConst.second.value)

                v1.elementPointers.forEach { node ->
                    resolveElementSize(node, sz, where.ptr, null)
                }
                val accessPaths = v1.accessPaths.map { path ->
                    check(path is AnalysisPath.ArrayAccess)
                    path.copy(index = idx.asTACSymbol())
                }
                return SValue.StoragePointer.ElementPointer(v1.elementPointers, accessPaths) to false
            } else {
                check(s2 is TACSymbol.Var)
                val (sz, index) = v1.knownElemSize()?.let { knownSize ->
                    tryInferIndex(where, knownSize, v2, s2)
                }.takeIf {
                    checkingMode
                } ?: tryInferSizeAndIndex(where, s2)

                v1.elementPointers.forEach {
                    resolveElementSize(it, sz, where.ptr, null)
                }
                val accessPaths = v1.accessPaths.map { path ->
                    check(path is AnalysisPath.ArrayAccess)
                    path.copy(index = index)
                }
                return SValue.StoragePointer.ElementPointer(v1.elementPointers, accessPaths) to false
            }
        }

        private fun plusSemanticsStoragePointer(where: CmdPointer, v1: SValue.StoragePointer, v2: SValue, v2AsConst: BigInteger?, s2: TACSymbol?): Pair<SValue, Boolean> {
            data class StaticArrayWithinStruct(
                val offset: BigInteger,
                val storage: Storage.Derived,
                val array: Value.StaticArray
            )

            // Handle cases 2.b, 5.b, 3.c
            if (v2AsConst == null) {
                if (v1 is SValue.StoragePointer.ElementPointer &&
                        (v2 is SValue.Nondet || v2 is SValue.I) &&
                        v1.knownElemSize() == BigInteger.ONE ) {
                    return SValue.StoragePointer.ElementPointer(v1.base, accessPaths = v1.accessPaths.map {
                        check(it is AnalysisPath.ArrayAccess)
                        // when we do pointer arithmetic we no longer have an accurate index
                        it.copy(index = null)
                    }) to false
                }
                // is this a deref of a static array?
                return when (v1) {
                    is SValue.StoragePointer.MappingPointer,
                    is SValue.StoragePointer.ElementPointer -> {
                        v1.base.monadicMap {
                            // We may have v2 = [1, N]
                            // and v1 points to a struct like
                            // { 0: Word, 1: StaticArray }
                            //
                            // So here we find an offset like
                            // `1` above and pass it along
                            // (we need it for the path plus bounds checking)
                            //
                            // We could bounds check here, but then we'd
                            // still need another check in the FieldPointer case.
                            val idx = (v2 as? SValue.I)?.i?.x ?: IntValue.Nondet
                            (storage[it] as? Value.Struct)?.elems?.let { elems ->
                                val off = elems.entries.mapNotNull {
                                    it.key `to?` storage[Storage.Derived(it.value)] as? Value.StaticArray
                                }.singleOrNull {
                                    it.second.range(it.first).mayIntersect(idx)
                                }?.let { (off, _) ->
                                   off
                                } ?: BigInteger.ZERO
                                off `to?` elems[off]?.let(Storage::Derived)
                            }
                        }
                    }
                    is SValue.StoragePointer.FieldPointer -> {
                        v1.base.monadicMap {
                            BigInteger.ZERO `to?` (storage[it] as? Value.Struct)?.elems?.get(v1.offset)?.let(Storage::Derived)
                        }
                    }
                }?.monadicMap { (off, array) ->
                    (storage[array] as? Value.StaticArray)?.let {
                        StaticArrayWithinStruct(off, array, it)
                    }
                }?.takeIf { l ->
                    // Check the index is a multiple of the size of each element
                    l.map { arrayInStruct ->
                        Triple(arrayInStruct.offset, arrayInStruct.array.numElems, arrayInStruct.array.wordsPerElem)
                    }.uniqueOrNull()?.let {(off, n, sz) ->
                        v2 is SValue.I
                                // Check that we appear to be indexing elements of the correct size
                                && v2.mustBeMultipleOf(sz)
                                // Check that the access is within bounds
                                && v2.i.x.ub < n.multiply(sz) + off
                    } ?: false
                }?.let { staticArrays ->
                    // Guaranteed unique by above check
                    val off = staticArrays.map { it.offset }.uniqueOrNull()!!

                    val accessPaths = v1.accessPaths(getStorageHint()).map {
                        val p = if (it is HashBase) {
                            it
                        } else {
                            AnalysisPath.StructAccess(
                                    offset = Offset.Words(off),
                                    base = it
                            )
                        }

                        AnalysisPath.StructAccess(
                                offset = Offset.Words(BigInteger.ZERO),
                                base = AnalysisPath.StaticArrayAccess(
                                        base = p,
                                        // In general, s2 == k*index where k is the size of the
                                        // next dimension in a multi-dimensional array. So we can't
                                        // use s2 unless k == 1.
                                        index = if (off == BigInteger.ZERO && staticArrays.all { arr ->
                                                arr.array.wordsPerElem == BigInteger.ONE
                                        }) {
                                            s2
                                        } else {
                                            null
                                        }

                                )
                        )
                    }

                    SValue.StoragePointer.FieldPointer(
                            base = staticArrays.map { p ->
                                Storage.Derived(p.array.elems)
                            }.toSet(),
                            offset = BigInteger.ZERO,
                            accessPaths = accessPaths
                    ) to false
                } ?: (SValue.Nondet to false)
            }
            return when (v1) {
                is SValue.StoragePointer.ElementPointer -> {
                    if (v1.knownElemSize() == v2AsConst) {
                        // 2.a.i
                        SValue.StoragePointer.ElementPointer(v1.base, accessPaths = v1.accessPaths.map {
                            check(it is AnalysisPath.ArrayAccess)
                            // when we do pointer arithmetic we no longer have an accurate index
                            it.copy(index = null)
                        }) to false
                    } else {
                        handleFieldAddition(where, v1.base, v2AsConst, v1.accessPaths)
                    }
                }
                is SValue.StoragePointer.FieldPointer -> {
                    handleFieldAddition(
                            where, v1.base, v1.offset + v2AsConst, v1.accessPaths.map {
                        check(it is AnalysisPath.StructAccess)
                        it.base
                    }
                    )
                }
                is SValue.StoragePointer.MappingPointer -> {
                    return handleFieldAddition(where, v1.base, offset = v2AsConst, v1.accessPaths)
                }
            }

        }

        private fun varStableFromHere(v: TACSymbol.Var, here: CmdPointer, value: BigInteger): Boolean {
            return mustBeConstant.mustBeConstantAt(here, v) == value
        }

        private fun plusSemantics(s1: TACSymbol, s2: TACSymbol, where: LTACCmd, state: MutableMap<TACSymbol.Var, SValue>): Pair<SValue, Boolean> {
            val v1 = state.interp(s1)
            val v2 = state.interp(s2)

            if (v1 is IntegralType && v2 is IntegralType) {
                if (v1 is SValue.Nondet || v2 is SValue.Nondet) {
                    return SValue.Nondet to false
                }
                check(v1 is SValue.I && v2 is SValue.I)
                return constBinOpSemantics(v1, v2) { c1, c2 -> c1 + c2 } to false
            }

            if (v1 is IntegralType) {
                // Just swap s1 and s2 so that v1 is always non-integral
                return plusSemantics(s2, s1, where, state)
            }

            logger.debug { "Computing offsets for $v1 and $v2" }
            check(v1 is SValue.ArrayStart || v1 is SValue.StoragePointer || v1 is SValue.UnresolvedOffset)

            val v2AsConst = if (v2 is SValue.I && v2.i.x.isConstant) {
                v2.i.x.c.takeIf {
                    s2 is TACSymbol.Const
                        || s2 is TACSymbol.Var && varStableFromHere(s2, where.ptr, it)
                }
            } else {
                null
            }

            when (v1) {
                is SValue.ArrayStart ->
                    return plusSemanticsArray(where, v1, v2, v2AsConst, s2)

                is SValue.UnresolvedOffset ->
                    return plusSemanticsUnresolvedOffset(v1, v2AsConst)

                else -> {
                    check(v1 is SValue.StoragePointer)
                    return plusSemanticsStoragePointer(where.ptr, v1, v2, v2AsConst, s2)
                }
            }
        }

        private fun minusSemantics(s1: TACSymbol, s2: TACSymbol, state: MutableMap<TACSymbol.Var, SValue>): SValue {
            val v1 = state.interp(s1)
            // The Solidity `pop()` function subtracts the element size from the element pointer. We allow any multiple
            // of the element size to be subtracted.
            if (v1 is SValue.StoragePointer.ElementPointer && s2 is TACSymbol.Const) {
                val elemSize = v1.knownElemSize()
                if (elemSize != null && s2.value.mod(elemSize) == BigInteger.ZERO) {
                    return SValue.StoragePointer.ElementPointer(
                        v1.base,
                        accessPaths = v1.accessPaths.map {
                            check(it is AnalysisPath.ArrayAccess)
                            // when we do pointer arithmetic we no longer have an accurate index
                            it.copy(index = null)
                        }
                    )
                }
            }
            return SValue.Nondet
        }

        /**
         * An entry (src => dst), ((k, p) -> p')
         * means that if the analysis is propagating storage information for variable k along the edge
         * src -> dst, and k's information has exactly the path p, then p should be replaced with p'.
         */
        private val pathRemapper = mutableMapOf<Pair<NBId, NBId>,
                MutableMap<Pair<TACSymbol.Var, AnalysisPaths>, AnalysisPaths>
                >()

        private val indexAlloc = mutableMapOf<Triple<NBId, TACSymbol.Var, Map<TACSymbol, Set<NBId>>>, TACSymbol.Var>()

        private val storageCopyLoops = mutableSetOf<Pair<NBId, StorageCopyLoopSummary>>()

        fun allocJoinKey(
            b: NBId,
            k: TACSymbol.Var,
            indexToPrev: Map<TACSymbol, Set<NBId>>
        ): TACSymbol.Var {
            return indexAlloc.computeIfAbsent(Triple(b, k, indexToPrev)) {
                TACKeyword.TMP(Tag.Bit256, "joinKey")
            }
        }

        private val nontriv = NonTrivialDefAnalysis(graph)
        private val mustBeConstant = MustBeConstantAnalysis(graph, nontriv)

        // Method-specific map of fresh variables we need to add.
        // For ((ptr, x) |-> i) \in freshAlloc,
        // we need to add the assignment tmp_i := x after ptr.
        private val freshAlloc = FreshVarAllocator(graph.cache.def)

        /**
         * Given a constant offset [offset] being added to a set [v] of storage pointers that are expected to be [Value.Struct],
         * return the abstract value representing the location pointed to after the addition. [parentPath] is the path
         * to the structs represented by [v], i.e., it should be a [HashResult].
         *
         * The [context] argument provides information about where the addition is happening, and is only used for error
         * messages.
         */
        private fun handleFieldAddition(context: CmdPointer, v: Set<Storage>, offset: BigInteger, parentPath: AnalysisPaths) : Pair<SValue, Boolean> {
            var changed = false
            val s = v.monadicMap {
                storage.computeIfAbsent(it) {
                    changed = true
                    Value.Struct(mutableMapOf())
                } as? Value.Struct
            } ?: throw StorageAnalysisFailedException("Attempting to add to a non-struct value at $context in stored in $v", context)
            /* does this offset fall into a range of a static array?
               Because we do not want to greedily traverse the contents of static arrays, the lower bound here is *strict*,
               so that adding enough to just be the start location of the static array will be the field pointer
               to the static array, not the contents at index 0 of the array.
             */
            val staticRange = s.map {
                it.elems.entries.singleOrNull { (start, ind) ->
                    (storage[Storage.Derived(ind)] as? Value.StaticArray)?.let {
                        offset > start && offset < start + it.wordsPerElem * it.numElems
                    } == true
                }
            }
            // then some are null, some are not, that's bad!
            if(staticRange.map { it != null }.uniqueOrNull() == null) {
                throw StorageAnalysisFailedException("At addition for ${graph.elab(context)}, reaching field $offset in $v gave inconsistent" +
                        "information about whether we are in a static array", context)
            }

            if(staticRange.first() != null) {
                /*
                then the whole bit is non-null, as established above!
                but let's make sure each static array has the same "shape"
                 */
                val arrays = staticRange.map {
                    it!!.key to (storage[Storage.Derived(it.value)]!! as Value.StaticArray)
                }
                val (where, _, sz) = arrays.map {
                    Triple(it.first, it.second.numElems, it.second.wordsPerElem)
                }.uniqueOrNull() ?: throw StorageAnalysisFailedException("Multiple possible shapes in $staticRange", context)
                val index = (offset - where) / sz
                val indexWithin = (offset - where) % sz
                val staticArrayBase = arrays.map { (_, sa) ->
                    Storage.Derived(sa.elems)
                }.toSet()
                val staticPath = parentPath.map {
                    check(it is HashResult)
                    AnalysisPath.StaticArrayAccess(
                        base = AnalysisPath.StructAccess(
                            offset = Offset.Words(where),
                            base = it
                        ),
                        // In general, index == k*i where k is the size of the
                        // next dimension in a multi-dimensional array and i is
                        // the actual index used in the source. So we can't
                        // use index unless k == 1.
                        index = if (arrays.all { (_, arr) ->
                                arr.wordsPerElem == BigInteger.ONE
                        }) {
                            index.asTACSymbol()
                        } else {
                            null
                        }
                    )
                }
                // don't greedily traverse unless we have to
                return if(indexWithin == BigInteger.ZERO) {
                    SValue.StoragePointer.FieldPointer(
                        base = staticArrayBase,
                        offset = indexWithin,
                        accessPaths = staticPath.map {
                            AnalysisPath.StructAccess(
                                base = it,
                                offset = Offset.Words(BigInteger.ZERO)
                            )
                        }
                    ) to changed
                } else {
                    /*
                       Suppose we are accessing doubleStaticArray[3][4], where doubleStaticArray has type uint[5][10].

                       Then the final offset will (3 * 5 + 4), where 5 is the size of each inner static array.
                       The solidity compiler (or our constant inliner) could fold the constant multiplication and additions
                        computation into a single constant addition. Thus, when we add 19 to the start of doubleStaticArray,
                        we first resolve which element of the outer static array we are stepping into (via the computation
                        of index). However, once we have resolved that, we have 4 "words" of addition left, so we resolve
                        this final step of 4 words with another recursive call.
                     */
                    handleFieldAddition(context, staticArrayBase, indexWithin, staticPath)
                }
            }
            val elemSize = v.firstMapped {
                arrayElemSizes[it]
            }
            return if(elemSize == offset) {
                SValue.StoragePointer.ElementPointer(base = v, accessPaths = parentPath.map {
                    check(it is AnalysisPath.ArrayAccess)
                    it.copy(index = null)
                }) to false
            } else {
                SValue.StoragePointer.FieldPointer(v, offset,
                    parentPath.map { p ->
                        check(p is HashResult)
                        AnalysisPath.StructAccess(base = p, offset = Offset.Words(offset))
                    }) to false
            }
        }

        private fun collapseStoragePaths(currBlock: NBId, m: TreapMap<TACSymbol.Var, SValue>): TreapMap<TACSymbol.Var, SValue> {
            /*
             Doing the collapse optimization is annoying and complicated in the presence of loops, and I don't
             expect it to be super helpful, so I'm punting on it
             */
            if (currBlock in loops || graph.pred(currBlock).size <= 1 || !graph.pred(currBlock).all { it in outState }) {
                return m
            }

            val preds = graph.pred(currBlock)
            val out = m.updateValues { k, v ->
                if(v !is Indexable) {
                    return@updateValues v
                }
                val paths = (v.accessPaths(getStorageHint()) as? AnalysisPaths.PathSet) ?: return@updateValues v
                if(paths.paths.size <= 1) {
                    return@updateValues v
                }
                // these are the only types we know how to handle
                if(v !is SValue.ArrayStart && v !is SValue.StoragePointer) {
                    return@updateValues v
                }
                // great, can we collapse?
                if(!preds.all { p -> outState[p]!![k]?.takeIf {
                            it.javaClass == v.javaClass
                        }?.let { it as? Indexable}?.accessPaths(getStorageHint())?.let {
                            it as? AnalysisPaths.PathSet
                        }?.paths?.size == 1 }) {
                    return@updateValues v
                }
                val predToPaths = preds.associateWith {
                    ((outState[it]!![k]!! as Indexable).accessPaths(getStorageHint()) as AnalysisPaths.PathSet).paths.single()
                }
                /**
                 * Collapse multiple paths from predecessors that are equivalent modulo indices/keys into a single
                 * access path, pushing ambiguity about the specific choice of keys/indices into join keys (via
                 * [allocJoinKey]. If the paths are *not* equivalent modulo indices keys (different shapes) this
                 * function returns null
                 */
                fun collapsePaths(predPaths: Map<NBId, AnalysisPath>, cont: (AnalysisPath) -> AnalysisPath) : AnalysisPath? {
                    check(predPaths.size > 1)
                    val cons = predPaths.values.first()
                    // not the same shape
                    if(predPaths.values.any {
                                it.javaClass != cons.javaClass
                            }) {
                        return null
                    }
                    return when(cons) {
                        is AnalysisPath.ArrayAccess -> {
                            val indexToPrev = mutableMapOf<TACSymbol, MutableSet<NBId>>()
                            if(predPaths.any {
                                        (it.value as AnalysisPath.ArrayAccess).baseSlot != cons.baseSlot
                                    }) {
                                return null
                            }
                            /** Then we are going to generate code to compute the index anyway, so just use null
                             *  instead of trying to pick a single index variable.
                             */
                            if(predPaths.any { (it.value as AnalysisPath.ArrayAccess).index == null }) {
                                collapsePaths(predPaths.mapValues {
                                    (it.value as AnalysisPath.ArrayAccess).base
                                }) { path ->
                                    cont(AnalysisPath.ArrayAccess(
                                            base = path,
                                            baseSlot = cons.baseSlot,
                                            index = null
                                    ))
                                }
                            } else {
                                val prev = mutableMapOf<NBId, AnalysisPath>()
                                for((pred, path) in predPaths) {
                                    check(path is AnalysisPath.ArrayAccess)
                                    prev[pred] = path.base
                                    indexToPrev.computeIfAbsent(path.index!!) { mutableSetOf() }.add(pred)
                                }
                                collapsePaths(prev) { path ->
                                    cont(AnalysisPath.ArrayAccess(
                                            base = path,
                                            baseSlot = cons.baseSlot,
                                            index = indexToPrev.keys.singleOrNull() ?: allocJoinKey(currBlock, k, indexToPrev)
                                    ))
                                }
                            }
                        }
                        is AnalysisPath.MapAccess -> {
                            val indexMap = mutableMapOf<TACSymbol, MutableSet<NBId>>()
                            val hashResultMap = mutableMapOf<TACSymbol, MutableSet<NBId>>()
                            val baseMap = mutableMapOf<TACSymbol, MutableSet<NBId>>()
                            val prevArg = mutableMapOf<NBId, AnalysisPath>()
                            for((pred, path) in predPaths) {
                                indexMap.computeIfAbsent((path as AnalysisPath.MapAccess).key) { mutableSetOf() }.add(pred)
                                hashResultMap.computeIfAbsent(path.hashResult) { mutableSetOf() }.add(pred)
                                baseMap.computeIfAbsent(path.baseSlot) { mutableSetOf()}.add(pred)
                                prevArg[pred] = path.base
                            }
                            collapsePaths(prevArg) { path ->
                                cont(
                                        AnalysisPath.MapAccess(
                                                base = path,
                                                key = indexMap.keys.singleOrNull() ?: allocJoinKey(
                                                        currBlock, k, indexMap
                                                ),
                                                baseSlot = baseMap.keys.singleOrNull() ?: allocJoinKey(
                                                    currBlock, k, baseMap
                                                ),
                                                hashResult = hashResultMap.keys.singleOrNull() ?: allocJoinKey(
                                                    currBlock, k, hashResultMap
                                                ),
                                        )
                                )
                            }
                        }
                        is AnalysisPath.Root -> {
                            cont(predPaths.values.uniqueOrNull() ?: return null)
                        }
                        is AnalysisPath.StructAccess -> {
                            val recArg = mutableMapOf<NBId, AnalysisPath>()
                            for((pred, path) in predPaths) {
                                if((path as AnalysisPath.StructAccess).offset != cons.offset) {
                                    return null
                                }
                                recArg[pred] = path.base
                            }
                            collapsePaths(recArg) { path ->
                                cont(AnalysisPath.StructAccess(
                                        offset = cons.offset,
                                        base = path
                                ))
                            }
                        }
                        is AnalysisPath.WordOffset -> {
                            val recArg = mutableMapOf<NBId, AnalysisPath>()
                            for((p, path) in predPaths) {
                                if((path as AnalysisPath.WordOffset).constOffset != cons.constOffset) {
                                    return null
                                }
                                recArg[p] = path.parent
                            }
                            collapsePaths(recArg) { path ->
                                cont(AnalysisPath.WordOffset(
                                        parent = path,
                                        constOffset = cons.constOffset
                                ))
                            }
                        }
                        is AnalysisPath.StaticArrayAccess -> {
                            val recArg = mutableMapOf<NBId, AnalysisPath>()
                            val indexSym = mutableMapOf<TACSymbol, MutableSet<NBId>>()
                            if (predPaths.any { (it.value as AnalysisPath.StaticArrayAccess).index == null }) {
                                return collapsePaths(predPaths.mapValues {
                                    (it.value as AnalysisPath.StaticArrayAccess).base
                                }) { path ->
                                    cont(AnalysisPath.StaticArrayAccess(
                                            base = path,
                                            index = null
                                    ))
                                }
                            }
                            for((p, path) in predPaths) {
                                indexSym.computeIfAbsent((path as AnalysisPath.StaticArrayAccess).index!!) { mutableSetOf() }.add(p)
                                recArg[p] = path.base
                            }
                            collapsePaths(recArg) { path ->
                                cont(
                                        AnalysisPath.StaticArrayAccess(
                                                base = path,
                                                index = indexSym.keys.singleOrNull() ?: allocJoinKey(currBlock, k, indexSym)
                                        )
                                )
                            }
                        }
                    }
                }
                val newPath = collapsePaths(predToPaths) { it } ?: return@updateValues v
                val newPathSet = AnalysisPaths.PathSet(
                        setOf(newPath)
                )
                for((p, remap) in predToPaths) {
                    pathRemapper.computeIfAbsent(p to currBlock) {
                        mutableMapOf()
                    }[k to AnalysisPaths.PathSet(setOf(remap))] = newPathSet
                }

                when(v) {
                    is SValue.ArrayStart -> v.copy(accessPaths = newPathSet)
                    is SValue.StoragePointer -> v.withPaths(newPathSet)
                    else -> `impossible!`
                }.takeIf { it != v } ?: v
            }
            if(out !== m) {
                inState[currBlock] = out
            }
            return out
        }

        /**
         * Compute the abstraction of this effect
         *   when [inLoop]: after [0, numIter) iterations (i.e., while the loop is executing)
         *   when [!inLoop]: after numIter iterations (i.e., after the loop)
         */
        private fun LoopEffect.toLoopAbstraction(
            numIter: BigInteger?, inLoop: Boolean
        ): SValue.I? = if (inLoop) {
            toLoopAbstraction(numIter)
        } else {
            toPostLoopAbstraction(numIter)
        }

        /**
         * Compute the abstraction of this effect after some number of iterations up to [numIter],
         * i.e. [0, numIter) iterations
         */
        private fun LoopEffect.toLoopAbstraction(
            numIter: BigInteger?,
        ): SValue.I? {
            return when (this) {
                is ConstantEffect -> {
                    if (numIter == null) {
                        return null
                    }
                    SValue.I(
                        cs = null,
                        stride = treapSetOf(
                            Stride.SumOfTerms.sumOf(
                                treapMapOf(k to Stride.SymValue(IntValue(BigInteger.ZERO, numIter - BigInteger.ONE))),
                                BigInteger.ZERO
                            )
                        ),
                        i = SimpleQualifiedInt(
                            IntValue.Constant(k).mult(IntValue(BigInteger.ZERO, numIter - BigInteger.ONE)).first,
                        )
                    )
                }

                is SummarizedEffect -> {
                    SValue.I(
                        cs = null,
                        stride = treapSetOf(Stride.SumOfTerms(Stride.SymValue(loopBodyInvariant))),
                        i = SimpleQualifiedInt(loopBodyInvariant)
                    )
                }

                is BytePtrUpdate ->
                    null
            }
        }

        /**
         * Compute the abstraction of this effect after the loop has executed [numIter] iterations
         */
        private fun LoopEffect.toPostLoopAbstraction(numIter: BigInteger?): SValue.I? {
            return when (this) {
                is ConstantEffect -> {
                    if (numIter == null) {
                        return null
                    }
                    SValue.I(
                        cs = null,
                        stride = treapSetOf(
                            Stride.SumOfTerms.sumOf(
                                treapMapOf(k to Stride.SymValue(IntValue.Constant(numIter))),
                                BigInteger.ZERO
                            )
                        ),
                        i = SimpleQualifiedInt(IntValue.Constant(k*numIter))
                    )
                }

                is SummarizedEffect -> {
                    SValue.I(
                        cs = null,
                        stride = treapSetOf(Stride.SumOfTerms(Stride.SymValue(loopExit))),
                        i = SimpleQualifiedInt(loopExit)
                    )
                }

                is BytePtrUpdate ->
                    null
            }
        }

        private fun storageCopyLoopSummarySimpleQualifiedIntLoopState(
            storageCopyLoop: StorageCopyLoopSummary,
            entryState: TreapMap<TACSymbol.Var, SValue>,
            loop: CmdPointer,
            inLoop: Boolean,
        ): ProjectedMap<TACSymbol.Var, SValue, SimpleQualifiedInt> {
            val projEntry = ProjectedMap(entryState, SValue::narrowIdx, SValue::mergeIdx)
            var projLoop = projEntry
            for ((x, eff) in storageCopyLoop.effects) {
                val initialIntValue = interpreter.interp(eff.initial, projEntry, loop).x
                val loopSummary = when (eff) {
                    is SummarizedEffect,
                    is ConstantEffect ->
                        eff.toLoopAbstraction(storageCopyLoop.numIterations, inLoop)?.let { sval ->
                            SimpleQualifiedInt(initialIntValue.add(sval.i.x).first)
                        }

                    is BytePtrUpdate -> {
                        storageCopyLoop.numIterations?.let { numIterations ->
                            val numPacked = EVM_WORD_SIZE / eff.k
                            val loopIt = if (inLoop) {
                                IntValue(BigInteger.ZERO, (eff.initial.value + numIterations - BigInteger.ONE).mod(numPacked) * eff.k)
                            } else {
                                IntValue.Constant((eff.initial.value + numIterations).mod(numPacked) * eff.k)
                            }
                            SimpleQualifiedInt(loopIt, setOf(SimpleIntQualifier.MultipleOf(eff.k)))
                        }
                    }
                } ?: continue

                projLoop += x to loopSummary
            }
            return projLoop
        }

        private fun storageCopyLoopSummaryStridesLoopState(
            storageCopyLoop: StorageCopyLoopSummary,
            entryState: TreapMap<TACSymbol.Var, SValue>,
            inLoop: Boolean
        ): ProjectedMap<TACSymbol.Var, SValue, TreapSet<Stride>> {
            var wrappedIn = ProjectedMap(entryState, SValue::narrowStrides, SValue::mergeStrides)
            for ((x, eff) in storageCopyLoop.effects) {
                eff.toLoopAbstraction(storageCopyLoop.numIterations, inLoop)?.let { sval ->
                    wrappedIn += x to wrappedIn.interpretSum(eff.initial).add(sval.stride)
                }
            }

            return wrappedIn
        }

        private fun storageCopyLoopSummaryStorageLoopState(
            storageCopyLoop: StorageCopyLoopSummary,
            entryState: TreapMap<TACSymbol.Var, SValue>,
            inLoop: Boolean,
        ): TreapMap<TACSymbol.Var, SValue> {
            val mutState = entryState.builder()

            storageCopyLoop.effects.forEachEntry { (x, eff) ->
                val sValue = entryState.interp(eff.initial)
                if (sValue !is SValue.StoragePointer) {
                    return@forEachEntry
                }
                val finalValue = eff.toLoopAbstraction(storageCopyLoop.numIterations, inLoop)
                val addendVal = finalValue ?: SValue.Nondet
                val v2AsConst = finalValue?.i?.x?.let {
                    if (!inLoop && it.isConstant) {
                        it.c
                    } else {
                        null
                    }
                }
                mutState[x] = plusSemanticsStoragePointer(
                    CmdPointer(storageCopyLoop.originalBlockStart, 0),
                    sValue,
                    addendVal,
                    v2AsConst,
                    null,
                ).first
            }

            return mutState.build()
        }

        /**
         * @return whether we should apply [storageCopyLoop]'s summary. This
         *         is false if the loop is not feasible, if the summary's preconditions
         *         aren't satisfied, or if there are no relevant storage commands
         *         to summarize
         */
        private fun shouldApplyStorageCopyLoopSummary(
            storageCopyLoop: StorageCopyLoopSummary,
            m: TreapMap<TACSymbol.Var, SValue>
        ): Boolean {
            // If our summary is valid (the skip cond is met)
            // then we can skip [dst]
            val preCond = storageCopyLoop.preconditions.all { (x, k) ->
                when (val s = m.interp(x)) {
                    is SValue.I -> {
                        SimpleIntQualifier.MultipleOf(k) in s.i.qual ||
                            s.cs?.all { it.mod(k) == BigInteger.ZERO } == true
                    }
                    else -> false
                }
            }
            if (!preCond) {
                logger.info {
                    "Precondition of storage<->mem copy loop ${storageCopyLoop.originalBlockStart} not met, not skipping"
                }
                return false
            }

            // The summarization is only useful for updating an SValue.I abstraction. If we have
            // e.g. an array pointer we don't need to do any of this, and in fact we don't want to
            // blow that away.
            val shouldSummarize = (storageCopyLoop.storageCmds.all {
                (graph.elab(it).cmd as? TACCmd.Simple.StorageAccessCmd)?.loc?.let {
                    m.interp(it) is SValue.I || m.interp(it) is SValue.StoragePointer
                } == true
            })
            if (!shouldSummarize) {
                logger.info {
                    "No SValue.I accesses in summarized loop -- no reason to summarize"
                }
                return false
            }

            val extractInterval = { x: TACSymbol ->
                (m.interp(x) as? SValue.I)?.i?.x ?: IntValue.Nondet
            }

            (storageCopyLoop.loopInfeasible as? TACExpr.BinRel.Eq?)?.let { e ->
                e.o1AsNullableTACSymbol()?.let { s1 ->
                    e.o2AsNullableTACSymbol()?.let { s2 ->
                        val i1 = extractInterval(s1)
                        val i2 = extractInterval(s2)
                        if (i1.isConstant && i2.isConstant && i1.c == i2.c) {
                            logger.info {
                                "Summarized loop ${storageCopyLoop.originalBlockStart} is not feasible"
                            }
                            return false
                        }
                    }
                }
            }

            return true
        }

        private fun storageCopyLoopSummaryState(
            storageCopyLoop: StorageCopyLoopSummary,
            m: TreapMap<TACSymbol.Var, SValue>,
            inLoop: Boolean,
        ): TreapMap<TACSymbol.Var, SValue> {
            val entryState = m.retainAllKeys { it !in storageCopyLoop.modifiedVars }
            val storagePointers = storageCopyLoopSummaryStorageLoopState(storageCopyLoop, entryState, inLoop)
            val loopStrides = storageCopyLoopSummaryStridesLoopState(storageCopyLoop, storagePointers, inLoop)
            val loopIntervals = storageCopyLoopSummarySimpleQualifiedIntLoopState(
                storageCopyLoop,
                loopStrides.wrapped,
                CmdPointer(storageCopyLoop.originalBlockStart, 0),
                inLoop
            )

            return loopIntervals.wrapped
        }

        /**
         * Adds a [SimpleIntQualifier.ModularUpperBound] qualifier to the value for [x].
         * We only do this if we can prove that the value of x <= the value of sym.
         *
         * @return the updated value to be used for [x] if such a qualifier is proved.
         */
        private fun guessModularUpperBound(state: Map<TACSymbol.Var, SValue>, x: TACSymbol.Var, sym: TACSymbol): SValue? {
            return (state[x] as? SValue.I)?.let { v1 ->
                (state.interp(sym) as? SValue.I)?.let { v2 ->
                    val quals = mutableSetOf<SimpleIntQualifier>()
                    if (v1.i.x.ub <= v2.i.x.lb) {
                        quals.add(
                            SimpleIntQualifier.ModularUpperBound(
                                other = sym,
                                modulus = BigInteger.ONE,
                                strong = v1.i.x.ub < v2.i.x.lb,
                            )
                        )
                        // If v1 <= v2 and v1 == k*m and v2 == l*m, then m divides (v2 - v1)
                        v1.i.qual.filterIsInstance<SimpleIntQualifier.MultipleOf>().filter {
                           it.factor != BigInteger.ONE && v2.mustBeMultipleOf(it.factor)
                        }.mapTo(quals) {
                            SimpleIntQualifier.ModularUpperBound(
                                other = sym,
                                modulus = it.factor,
                                strong = v1.i.x.ub < v2.i.x.lb
                            )
                        }
                        v1.copy(i = v1.i.withQualifiers(v1.i.qual + quals))
                    } else {
                        null
                    }
                }
            }
        }

        /**
         * Guess [SimpleIntQualifier.ModularUpperBound] qualifiers (to use as loop invariants) that hold on
         * entry to [loop].
         *
         * @return [state] updated with any such qualifiers that hold on entry to [loop]
         */
        private fun guessLoopInvariants(loop: Loop, state: TreapMap<TACSymbol.Var, SValue>): TreapMap<TACSymbol.Var, SValue> {
            var st = state
            val liveBeforeHead = graph.cache.lva.liveVariablesBefore(loop.head)

            val syms = treapSetOf<TACSymbol>().mutate { syms ->
                for (b in loop.body) {
                    graph.elab(b).commands.forEach {
                        it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.let { syms += it.getRhs() }
                        it.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.cmd?.let { syms += it.lhs }
                    }
                }
            }

            val liveSyms = (syms intersect liveBeforeHead).uncheckedAs<TreapSet<TACSymbol.Var>>()

            liveSyms.forEachElement { s1 ->
                syms.forEachElement { s2 ->
                    if (s1 != s2) {
                        guessModularUpperBound(st, s1, s2)?.let { v ->
                            st += s1 to v
                        }
                    }
                }
            }

            return st
        }

        private fun iterBlock(currBlock: NBId): Set<NBId> {
            var m = inState[currBlock] ?: return emptySet()
            visited.add(currBlock)
            logger.info { "Visiting block $currBlock" }

            m = collapseStoragePaths(currBlock, m)

            val block = graph.elab(currBlock)
            for (command in block.commands) {
                try {
                    m = stepCommand(command, m)
                } catch (_: InfeasibleState) {
                    return setOf()
                }
            }

            val noNondet = m.retainAllValues { it != SValue.Nondet }

            outState[currBlock] = noNondet
            val nxt = mutableSetOf<NBId>()

            val keyHash = block.commands.last().snarrowOrNull<BytesKeyHash>()
            val storageCopyLoop = block.commands.last().snarrowOrNull<StorageCopyLoopSummary>()
            var storageCopyBlocksTaken = 0
            var keyHashBlocksTaken = 0

            for ((succ, pc) in graph.pathConditionsOf(block.id)) {
                val nextBlockOut = m
                val nextWithPathCond = try {
                    val fst = graph.elab(succ).commands.first()
                    val propagated = interpreter.propagate(
                        fst,
                        SimpleQualifiedIntAbstractInterpreterState(nextBlockOut, StorageAnalysisObjectReference()),
                        pc
                    )?.wrapped
                    propagated?.parallelUpdateValues { x, v ->
                        when (v) {
                            is SValue.I -> {
                                val refineFromIndex = if (Stride.Top in v.stride) {
                                    v.copy(stride = treapSetOf(Stride.SumOfTerms(Stride.SymValue(x, v.i.x))))
                                } else {
                                    v.refineSimpleQualifiedInt(v.i)
                                }
                                refineFromIndex
                            }

                            else -> v
                        }
                    }
                } catch (_: InfeasibleState) {
                    null
                }

                if (nextWithPathCond == null) {
                    logger.info {
                        "Skipping ${block.id}->{$succ}: infeasible path"
                    }
                    // We shouldn't apply the summary if the loop isn't feasible,
                    // i.e. morally we continue to the loop and simply jump to the loop successor
                    storageCopyBlocksTaken++
                    continue
                }

                val next = if (keyHash != null) {
                    val st = nextWithPathCond.builder()
                    val mapKeyVal = createMapKey(
                        baseSymbol = keyHash.slot,
                        base = nextWithPathCond.interp(keyHash.slot),
                        indexVar = keyHash.keyHash,
                        where = block.commands.last(),
                        lhs = keyHash.output,
                        freshVariables = freshAlloc
                    )
                    st[keyHash.output] = mapKeyVal ?: SValue.Nondet
                    if (mapKeyVal != null && keyHash.skipTarget == succ) {
                        // Go to the skip target with the result of applying the summary
                        visited.addAll(keyHash.summarizedBlocks)
                        keyHashBlocksTaken++
                        st.build()
                    } else if (mapKeyVal == null && keyHash.originalBlockStart == succ) {
                        // Go to the original target without applying the summary
                        keyHashBlocksTaken++
                        st.build()
                    } else {
                        // Otherwise skip succ
                        check( (mapKeyVal == null && keyHash.skipTarget == succ)
                                || (mapKeyVal != null && keyHash.originalBlockStart == succ) ) {
                            "Unexpected decision to skip ${succ} after encountering ${keyHash} and computing map key ${mapKeyVal}"
                        }
                        continue
                    }
                } else if(storageCopyLoop != null) {
                    val shouldApply = shouldApplyStorageCopyLoopSummary(storageCopyLoop, nextWithPathCond)
                    val next = if (shouldApply && storageCopyLoop.skipTarget == succ) {
                        storageCopyLoops += setOf(currBlock to storageCopyLoop)
                        visited.addAll(storageCopyLoop.summarizedBlocks)
                        storageCopyLoopSummaryState(storageCopyLoop, m, false)
                    } else if (!shouldApply && storageCopyLoop.originalBlockStart == succ) {
                        nextWithPathCond
                    } else {
                        continue
                    }
                    storageCopyBlocksTaken++
                    next
                } else {
                    nextWithPathCond
                }

                val nextWithGuessedInvariants = loops[succ]?.singleOrNull { currBlock !in it.body }?.let {enteringLoop ->
                    guessLoopInvariants(enteringLoop, next)
                } ?: next


                if (succ !in inState) {
                    inState[succ] = nextWithGuessedInvariants
                    nxt.add(succ)
                } else {
                    val prev = inState[succ]!!

                    val isBackJump = loops[succ]?.any { currBlock in it.body } == true
                    val joined = prev.parallelMerge(nextWithGuessedInvariants) merge@{ k, prevStateValue, nextStateValue ->
                        if(prevStateValue == null || nextStateValue == null) {
                            return@merge SValue.Nondet
                        }
                        if (isBackJump && nextStateValue is SValue.UnresolvedOffset &&
                            (prevStateValue is SValue.ArrayStart && prevStateValue.elementPointers == nextStateValue.elementStorage)) {
                            nextStateValue.elementStorage.forEach {
                                resolveElementSize(it, nextStateValue.constOffset, null, succ)
                            }
                            SValue.StoragePointer.ElementPointer(
                                nextStateValue.elementStorage,
                                nextStateValue.parentPath.map { path ->
                                    check(path is AnalysisPath.ArrayAccess)
                                    // is this necessary/correct and might it happen on the next iteration anyway?
                                    path.copy(index = null)
                                })
                        } else if (isBackJump && nextStateValue is SValue.StoragePointer.ElementPointer &&
                            ((prevStateValue is SValue.StoragePointer.ElementPointer &&
                                    prevStateValue.base == nextStateValue.base) ||
                                    (prevStateValue is SValue.ArrayStart &&
                                            prevStateValue.elementPointers == nextStateValue.base)) && prevStateValue != nextStateValue) {
                            nextStateValue.copy(
                                accessPaths = nextStateValue.accessPaths.map { path ->
                                    check(path is AnalysisPath.ArrayAccess)
                                    path.copy(index = null)
                                }
                            )
                        } else {
                            val remapped = pathRemapper[currBlock to succ]?.let { remap ->
                                (nextStateValue as? Indexable)?.accessPaths(getStorageHint())?.let { currPaths ->
                                    remap[k to currPaths]
                                }?.let { newPath ->
                                    when(nextStateValue) {
                                        is SValue.ArrayStart -> nextStateValue.copy(accessPaths = newPath)
                                        is SValue.StoragePointer -> nextStateValue.withPaths(newPath)
                                        else -> nextStateValue
                                    }
                                }
                            } ?: nextStateValue
                            try {
                                if (isBackJump) {
                                    prevStateValue.widen(remapped, prev, nextWithGuessedInvariants, arrayElemSizes, getStorageHint())
                                } else {
                                    remapped.join(prevStateValue, nextWithGuessedInvariants, prev, arrayElemSizes, getStorageHint())
                                }
                            } catch (_: InfeasibleState) {
                                // If this happens, there's a serious bug,
                                // since the result of the join should be a "larger" value,
                                // and we're assuming the joined states are not bottom
                                `impossible!`
                            }
                        }
                    }

                    if (joined != prev) {
                        inState[succ] = joined
                        nxt.add(succ)
                        if (isBackJump) {
                            // succ is head of a loop
                            loops[succ]!!.stream().flatMap { it.body.stream() }.filter { it != succ }.forEach { loopBlock ->
                                inState.remove(loopBlock)
                            }
                        }
                    }
                }
            }
            check (storageCopyLoop == null || storageCopyBlocksTaken == 1) {
                "Unexpectedly processed $storageCopyBlocksTaken successors of storage<->memory summary at ${block.id}"
            }
            check(keyHash == null || keyHashBlocksTaken == 1) {
                "Unexpectedly processed $keyHashBlocksTaken successors of key hash summary at ${block.id}"
            }
            return nxt
        }

        tailrec fun canonicalize(c: AnalysisPath, f: (AnalysisPath, Storage, Value) -> AnalysisPath) : AnalysisPath {
            return when(c) {
                is AnalysisPath.ArrayAccess -> {
                    canonicalize(c.base) { newParent, _, which ->
                        check(which is Value.Array)
                        val derived = Storage.Derived(which.elem).find()
                        f(AnalysisPath.ArrayAccess(
                            base = newParent,
                            index = c.index,
                            baseSlot = c.baseSlot
                        ), derived, storage[derived]!!)
                    }
                }
                is AnalysisPath.StaticArrayAccess -> {
                    canonicalize(c.base) { newParent, _, which ->
                        check(which is Value.StaticArray)
                        val derived = Storage.Derived(which.elems).find()
                        f(AnalysisPath.StaticArrayAccess(
                            index = c.index,
                            base = newParent
                        ), derived, storage[derived]!!)
                    }
                }
                is AnalysisPath.MapAccess -> {
                    canonicalize(c.base) { newParent, _, which ->
                        check(which is Value.Mapping)
                        val derived = Storage.Derived(which.codom).find()
                        f(AnalysisPath.MapAccess(
                            key = c.key,
                            base = newParent,
                            hashResult = c.hashResult,
                            baseSlot = c.baseSlot,
                        ), derived, storage[derived]!!)
                    }
                }
                is AnalysisPath.Root -> {
                    f(c, Storage.Root(c.slot), storage[Storage.Root(c.slot)]!!)
                }
                is AnalysisPath.StructAccess -> {
                    canonicalize(c.base) { newParent, _, which ->
                        check(which is Value.Struct)
                        // make sure to access [which.elems] with a `word` value
                        // as it is a word indexed map
                        val nxt = Storage.Derived(which.elems[c.offset.words]!!).find()
                        f(AnalysisPath.StructAccess(
                            base = newParent,
                            offset = c.offset
                        ), nxt, storage[nxt]!!)
                    }
                }
                is AnalysisPath.WordOffset -> {
                    canonicalize(c.parent) { newParent, s, which ->
                        check(newParent is AnalysisPath.ArrayAccess && newParent.index == BigInteger.ZERO.asTACSymbol())
                        val elemSize = arrayElemSizes[s]!!
                        val fld = c.constOffset.mod(elemSize)
                        check(which is Value.Struct)
                        val derived = Storage.Derived(which.elems[fld]!!).find()
                        f(AnalysisPath.StructAccess(
                            offset = Offset.Words(fld),
                            base = newParent.copy(
                                    index = (c.constOffset / elemSize).asTACSymbol()
                            )
                        ), derived, storage[derived]!!)
                    }
                }
            }
        }

        fun toResult(): StorageAnalysisResult {
            val flagSet = mutableMapOf<Pair<NBId, NBId>, MutableMap<TACSymbol.Var, TACSymbol>>()

            for(s in indexAlloc.keys) {
                val joinKey = allocJoinKey(s.first, s.second, s.third)
                for((sym, prev) in s.third) {
                    for(prevBlock in prev) {
                        var failure : StorageAnalysisResult.Failure? = null
                        flagSet.computeIfAbsent(prevBlock to s.first) { mutableMapOf() }.merge(joinKey, sym) { a, b ->
                            if(a != b) {
                                failure =  StorageAnalysisResult.Failure(
                                    StorageAnalysisFailedException(
                                        "inconsistent symbols ($a vs $b) chosen for $prevBlock while selecting $s",
                                        CmdPointer(prevBlock, 0)
                                    )
                                )
                            }
                            a
                        }
                        if(failure != null) {
                            return failure!!
                        }
                    }
                }
                val allPreds = s.third.flatMapTo(mutableSetOf()) { it.value }
                if(graph.pred(s.first).any {
                        it !in allPreds
                    }) {
                    return StorageAnalysisResult.Failure(
                        StorageAnalysisFailedException(
                            "need to instrument predecessor ${graph.pred(s.first)}, only have $allPreds for $s",
                            CmdPointer(s.first, 0)
                        )
                    )
                }
            }

            val hashResults = freshAlloc.allocatedFreshVars
            val (accessPaths, sideConditions) = computeAccessPathsAndSideConditions()
            val unreachable = graph.blockGraph.keys - visited
            return StorageAnalysisResult.Complete(
                accessedPaths = accessPaths,
                contractTree = getStorageTree(),
                sideConditions = sideConditions,
                joinInstrumentation = StorageAnalysisResult.JoinInstrumentation(
                    flagSet = flagSet
                ),
                hashInstrumentation = StorageAnalysisResult.HashInstrumentation(
                    hashResults = hashResults
                ),
                unreachable = unreachable,
            )
        }

        private val loopCopySummaryBlocks = graph.blocks.asSequence().mapNotNullToTreapSet { block ->
            block.id.takeIf {
                block.commands.last().snarrowOrNull<StorageCopyLoopSummary>() != null
            }
        }

        private val storageAccessBlocks = graph.blocks.asSequence().filter {
            it.commands.any { it.cmd is TACCmd.Simple.StorageAccessCmd}
        }.mapToTreapSet { it.id }

        /**
            [collectGarbage] needs to preserve:
            - the inState of blocks containing any StorageAccessCmd
            - the outState of blocks containing a StorageCopyLoopSummary

            If the logic in [computeAccessPathsAndSideConditions] changes to require more data, add it to either
            [blockInStateToPreserveForAccessedPathsComputation] or [blockOutStateToPreserveForAccessedPathComputation]
         */
        private val blockInStateToPreserveForAccessedPathsComputation = storageAccessBlocks
        private val blockOutStateToPreserveForAccessedPathComputation = loopCopySummaryBlocks

        private fun computeAccessPathsAndSideConditions(): Pair<Map<CmdPointer, StorageAnalysisResult.AccessPaths>, Map<CmdPointer, StorageAnalysisResult.SideCondition>> {
            val iterStateLTACCmd = mutableMapOf<CmdPointer, StorageAnalysisResult.AccessPaths>()
            val sideConditions = mutableMapOf<CmdPointer, StorageAnalysisResult.SideCondition>()

            // Finally compute the states for the summarized
            // storage/memory copy loops so that we can extract
            // the access paths.
            for ((summaryBlock, summary) in storageCopyLoops) {
                val entryState = outState[summaryBlock]
                check(entryState != null) {
                    "Entry state missing for storage copy loop summary block"
                }
                check(summary.originalBlockStart !in inState) {
                    "Attempting to summarize a loop that has already been visited"
                }
                var state = storageCopyLoopSummaryState(summary, entryState, true)
                inState[summary.originalBlockStart] = state
                for (cmd in graph.elab(summary.originalBlockStart).commands) {
                    try {
                        state = stepCommand(cmd, state)
                    } catch (_: InfeasibleState) {
                        // This had better not be the case, as we're re-analyzing
                        // code that we've seen before.
                        `impossible!`
                    }
                }
                val nextLoopBlock = graph.succ(summary.originalBlockStart).singleOrNull {
                    it in summary.summarizedBlocks
                }
                check(nextLoopBlock != null) {
                    "Unexpected storage copy loop shape: non-singleton loop header successors"
                }
                check(nextLoopBlock !in inState) {
                    "Attempting to summarize a loop successor that has already been visited"
                }
                inState[nextLoopBlock] = state
                check(graph.succ(nextLoopBlock).singleOrNull() == summary.originalBlockStart) {
                    "Unexpected storage copy loop shape: successor of second block is not the header"
                }
            }

            for(b in graph.blocks) {
                if(b.id !in storageAccessBlocks) {
                    continue
                }
                val initialState = inState[b.id]
                if (initialState == null) {
                    check (b.id !in visited) {
                        "visited block ${b.id} not in inState"
                    }
                    logger.info {
                        "skipping unreachable block ${b.id}"
                    }
                    markUnreachableCommands(b, iterStateLTACCmd)
                    continue
                }
                var state: TreapMap<TACSymbol.Var, SValue> = initialState
                for(c in b.commands) {
                    if(c.cmd is TACCmd.Simple.StorageAccessCmd && c.cmd.base.meta.find(TACMeta.STORAGE_KEY) != null) {
                        boundedIntegerAssertions[c.ptr]?.let { spec ->
                            check (c.cmd is TACCmd.Simple.WordStore) {
                                "Bounded integer assertion recorded for $c which is not a WordStore"
                            }
                            // Remember that we wanted c.cmd.value \in spec
                            sideConditions[c.ptr] =
                                StorageAnalysisResult.SideCondition(v = c.cmd.value, range = spec)
                        }

                        when (val l = c.cmd.loc) {
                            // In the presence of static arrays,
                            // we can't assume that a constant is simply a Root access path:
                            is TACSymbol.Const ->
                                SValue.fromConstant(l).accessPaths(getStorageHint())

                            is TACSymbol.Var -> {
                                (state[l] as? HasAnalysisPaths)?.refineAndGetAnalysisPaths(c.cmd.loc, c.ptr, state)
                            }
                        }?.map {
                            val implicitZeroDerefs = implicitDerefs(it)
                            canonicalize(implicitZeroDerefs) { p, _, _ ->
                                if (p is HashResult) {
                                    AnalysisPath.StructAccess(
                                        p, wordOffset = BigInteger.ZERO
                                    )
                                } else {
                                    p
                                }
                            }
                        }?.toResultOrNull()?.let {
                            iterStateLTACCmd[c.ptr] = it
                        } ?: logger.warn {
                            "Failed to compute an expected access path at ${c}"
                        }
                    }
                    try {
                        state = stepCommand(c, state)
                    } catch (_ : InfeasibleState) {
                        throw StorageAnalysisFailedException(
                            "Infeasible state found while computing access paths.\nPre-state: $state\ncommand: $c",
                            c.ptr
                        )
                    }
                }
            }
            return iterStateLTACCmd to sideConditions
        }

        private fun markUnreachableCommands(b: TACBlock, iterStateLTACCmd: MutableMap<CmdPointer, StorageAnalysisResult.AccessPaths>) {
            for(c in b.commands) {
                if (c.cmd is TACCmd.Simple.StorageAccessCmd && c.cmd.base.meta.find(TACMeta.STORAGE_KEY) != null) {
                    iterStateLTACCmd[c.ptr] = StorageAnalysisResult.AccessPaths(
                        paths = setOf()
                    )
                }
            }
        }

        /**
         * Extend p with 0-index static array accesses/0-offset struct accesses such that
         * following the returned path through [storage] from its root results in either a Word
         * or some dynamic object (i.e. an array/mapping, for which there's no notion of implicitly
         * looking at the 0th element)
         */
        private fun implicitDerefs(p: AnalysisPath): AnalysisPath {
            tailrec fun addZeroOffsets(ptr: Value, p: AnalysisPath): AnalysisPath {
                return when (ptr) {
                    is Value.IntegerWithBounds,
                    is Value.Mapping,
                    is Value.Array,
                    is Value.Word -> p

                    is Value.StaticArray ->
                        addZeroOffsets(storage[Storage.Derived(ptr.elems)]!!, AnalysisPath.StaticArrayAccess(p, 0.asTACSymbol()))
                    is Value.Struct ->
                        addZeroOffsets(storage[Storage.Derived(ptr.elems[BigInteger.ZERO]!!)]!!, AnalysisPath.StructAccess(p, BigInteger.ZERO))
                }
            }
            return addZeroOffsets(leafPtr(p), p)
        }

        private fun leafPtr(p: AnalysisPath): Value {
            val pValue = when(p) {
                is AnalysisPath.WordOffset ->
                    return Value.Word
                is AnalysisPath.Root ->
                    Storage.Root(p.slot)
                is AnalysisPath.ArrayAccess ->
                    (leafPtr(p.base) as Value.Array).elem.let(Storage::Derived)
                is AnalysisPath.MapAccess ->
                    (leafPtr(p.base) as Value.Mapping).codom.let(Storage::Derived)
                is AnalysisPath.StaticArrayAccess ->
                    (leafPtr(p.base) as Value.StaticArray).elems.let(Storage::Derived)
                is AnalysisPath.StructAccess ->
                    (leafPtr(p.base) as Value.Struct).elems[p.offset.words]!!.let(Storage::Derived)
            }
            return storage[pValue]!!
        }


        /**
            Discards state that is no longer needed

            To conserve memory, after each round of worklist iteration we discard any state that we will not need again.

            In general, we need to keep the state for blocks that are reachable from [remainingWork].  In addition, we
            need to keep extra state used by the following:

            - [iterBlock] also uses the [outState] of the predecessors of each block.
            - [computeAccessPathsAndSideConditions] uses extra state from [inState]; see [blockInStateToPreserveForAccessedPathsComputation].
        */
        private fun collectGarbage(remainingWork: Iterable<NBId>) {
            val reachable = remainingWork.asSequence().map { graph.cache.reachability[it].orEmpty() }.parallelUnionAll()
            /* This needs to be from [reachable] in case we have a CFG like:
                          A
                         / \
                        B   \
                       /     \
                      C       \
                    /  \       \
                  D     \       \
                 /       \       |
                /         \      |
               E----------->---> F

               If our remaining work is {C}, then our reach set is {D,E,F} but the preds of C are only {B}.
               At F we'll try and get the out state of {A}.
            */
            val preds = reachable.asSequence().map { graph.pred(it).toTreapSet() }.parallelUnionAll()

            /*
                Discard any values for keys that are not in [needed].  We don't simply remove the entries from the map,
                because we want to remember that they were there (even without the value) *and* we want a nice exception
                if we accidentally remove a value that we really needed.  So instead we break generic safety just a bit,
                and replace the values with [Discarded].  This way we remember that the key is in use, and if we try to
                access the value we'll get a [ClassCastException] saying we can't cast from [Discarded].
             */
            fun discardUnneeded(map: MutableMap<NBId, *>, needed: Set<NBId>) {
                for (it in map.uncheckedAs<MutableMap<NBId, Discarded>>()) {
                    if (it.key !in needed) {
                        it.setValue(Discarded)
                    }
                }
            }

            discardUnneeded(inState, reachable + blockInStateToPreserveForAccessedPathsComputation)
            discardUnneeded(outState, reachable + preds + blockOutStateToPreserveForAccessedPathComputation)
        }


        init {
            graph.rootBlocks.forEach {
                inState[it.id] = treapMapOf()
            }

            (object : StatefulWorklistIteration<NBId, Unit, Unit>() {
                // We use NaturalBlockScheduler since we may delete state corresponding
                // to loop body blocks at the tail of a loop
                override val scheduler: IWorklistScheduler<NBId> = NaturalBlockScheduler(graph)

                override fun reduce(results: List<Unit>) {}

                override fun process(it: NBId): StepResult<NBId, Unit, Unit> {
                    return this.cont(iterBlock(it))
                }

                override fun nextRound(worklist: Iterable<NBId>) = collectGarbage(worklist)
            }).submit(graph.rootBlocks.map { it.id })
        }

    }

    private fun extractType(s: Storage) : StorageTree.Type {
        check(s.find() == s)
        return when(val x = storage[s]) {
            is Value.Array -> {
                val elemKey = Storage.Derived(x.elem).find()
                StorageTree.Type.Array(
                    element = extractType(elemKey),
                    elementSize = arrayElemSizes[elemKey] ?: BigInteger.ZERO
                )
            }
            is Value.Mapping -> StorageTree.Type.Mapping(
                codomain = extractType(Storage.Derived(x.codom).find())
            )
            is Value.Struct -> StorageTree.Type.Struct(
                x.elems.mapValues {
                    extractType(Storage.Derived(it.value).find())
                }
            )
            Value.Word, is Value.IntegerWithBounds -> StorageTree.Type.Word
            is Value.StaticArray -> StorageTree.Type.StaticArray(
                numElements = x.numElems,
                elementSize = x.wordsPerElem,
                element = extractType(Storage.Derived(x.elems).find())
            )
            null -> StorageTree.Type.Bottom
        }
    }

    private fun getStorageTree(): Set<StorageTree.Root> {
        return storage.keysMatching { storage, _ -> storage is Storage.Root }.map {
             StorageTree.Root(
                 slot = (it as Storage.Root).v,
                 types = extractType(it)
             )
        }.toSet()
    }

    fun <K> calcLinearInvariants(inputs: Map<K, CoreTACProgram>): Map<K, GlobalInvariantAnalysisResult> {
        return inputs.entries.forkEvery { (k, g) ->
            Scheduler.compute {
                k to getLinearInvariants(g.analysisCache.graph)
            }
        }.pcompute().runInherit().toMap()
    }

    fun <K> analyze(inputs: Map<K, CoreTACProgram>) : Map<K, StorageAnalysisResult> {
        if((contractClass as? IContractWithSource)?.src?.methods?.any {
            it.isLibrary
        } == true) {
            return inputs.mapValues {
                StorageAnalysisResult.SkippedLibrary
            }
        }

        val linearInvariants = calcLinearInvariants(inputs)

        val accum = mutableMapOf<K, MethodWorker>()
        val result = mutableMapOf<K, StorageAnalysisResult>()
        for((k, g) in inputs) {
            val fallbackStorage = this.storage.mapValues { (_, it) ->
                if(it is Value.Struct) {
                    it.copy(elems = it.elems.toMutableMap())
                } else {
                    it
                }
            }
            val savedLoc = heapLoc
            val savedArrayElem = this.arrayElemSizes.toMap()
            val savedUF = this.storageUnification.copy()
            val savedArrayOffsets = this.arrayOffsets.mapValues {
                it.value.toMap()
            }
            var rollback = true
            try {
                accum[k] = MethodWorker(g.analysisCache.graph, linearInvariants[k]!!)
                rollback = false
            } catch (x: StorageAnalysisFailedException) {
                logger.warn {
                    "Storage Analysis Failed on method ${g.name}: ${x.s}\n" +
                            "This will only be an error if the analysis is consumed further on in the workflow"
                }
                recordSuccess(g.name, STATISTICS_KEY,ANALYSIS_STORAGE_SUBKEY,  false)
                result[k] = StorageAnalysisResult.Failure(
                    reason = x
                )
            } catch (x: Throwable) {
                logger.error(x) {
                    "failed analyzing ${g.name}"
                }
                result[k] = StorageAnalysisResult.Failure(x)
                recordSuccess(g.name, STATISTICS_KEY, ANALYSIS_STORAGE_SUBKEY, false)
            }
            if(rollback) {
                heapLoc = savedLoc
                arrayElemSizes.clear()
                arrayElemSizes.putAll(savedArrayElem)
                this.storageUnification = savedUF
                storage.clear()
                storage.putAll(fallbackStorage)
                arrayOffsets.clear()
                arrayOffsets.putAll(savedArrayOffsets.mapValues {
                    it.value.toMutableMap()
                })
            }
        }
        this.finalize()
        accum.mapValuesTo(result) { (_, worker) ->
            try {
                worker.toResult()
            } catch (x: StorageAnalysisFailedException) {
                logger.warn {
                    "Storage Analysis Failed while finalizing results for ${worker.graph.name}: ${x.s}\n" +
                        "This will only be an error if the analysis is consumed further on in the workflow"
                }
                recordSuccess(worker.graph.name, STATISTICS_KEY, ANALYSIS_STORAGE_SUBKEY, false)
                StorageAnalysisResult.Failure(x)
            } catch(x: Throwable) {
                logger.error(x) {
                     "failed finalizing results for ${worker.graph.name}"
                }
                recordSuccess(worker.graph.name, STATISTICS_KEY, ANALYSIS_STORAGE_SUBKEY, false)
                StorageAnalysisResult.Failure(x)
            }
        }
        return result
    }

    companion object {
        fun doAnalysis(g: TACCommandGraph, storage: TACStorageLayout? = null): StorageAnalysisResult {
            val storageAnalysis = StorageAnalysis(storage, null)
            val worker = storageAnalysis.MethodWorker(g, getLinearInvariants(g))
            storageAnalysis.finalize()
            return worker.toResult()
        }


        fun runAnalysis(c: IContractClass): Map<MethodRef, StorageAnalysisResult> {
            val storage = StorageAnalysis(c)
            val toAnnotate = c.getDeclaredMethods().filter {
                it.attribute != MethodAttribute.Unique.Whole /*&& it.attribute != MethodAttribute.Unique.Constructor*/
            }
            return toAnnotate.associate {
                it.toRef() to (it.code as CoreTACProgram)
            }.let(storage::analyze)
        }

        fun getLinearInvariants(graph: TACCommandGraph): GlobalInvariantAnalysisResult {
            val linearSemantics = object : GlobalInvariantAnalysisSemantics(2) {
                /** Collect linear equalities over variables that may flow into storage locations */
                private fun relevantVarForLinearEq(l: LTACCmd): Pair<CmdPointer, TACSymbol.Var>? =
                    l.ptr `to?` (l.cmd as? TACCmd.Simple.StorageAccessCmd)?.loc?.tryAs<TACSymbol.Var>()

                private val defAnalysis = graph.cache.def

                private val storageVar: TreapSet<TACSymbol.Var> =
                    graph.commands.parallelStream().mapNotNull {
                        relevantVarForLinearEq(it)
                    }.map {
                        // Collects (where, x) pairs where
                        // the value of [x] as is used in an expression at [where] may flow into a storage location
                        val flowsToStorageVar = mutableSetOf(it)
                        SimpleWorklist.iterateWorklist(flowsToStorageVar) { (where, x), next ->
                            // The use of [x] at [where] may flow to a storage location, so
                            // any value that flows into a definition of [x] visible at [where]
                            // is a candidate
                            defAnalysis.defSitesOf(x, where).forEach { def ->
                                graph.elab(def).cmd.getFreeVarsOfRhs().forEach { y ->
                                    // y flows into the value of x at its def site, so
                                    // add it to our worklist if we haven't processed it yet
                                    val v = def to y
                                    if (v !in flowsToStorageVar) {
                                        flowsToStorageVar.add(v)
                                        next.add(v)
                                    }
                                }
                            }
                        }
                        flowsToStorageVar.mapToTreapSet { it.second }
                    }.collect(
                        Collector.of(
                            { treapSetBuilderOf() },
                            TreapSet.Builder<TACSymbol.Var>::addAll,
                            { a, b -> a.apply { addAll(b)} },
                        )
                    ).build()

                override fun isRelevant(eq: LinearEquality) = eq.relatesAny(storageVar)
            }
            return GlobalInvariantAnalysis(linearSemantics).analyze(graph)
        }
    }

    fun finalize() {
        /* any arrays that were not resolved yet are just considered to be
           an array of elements that are x words, where x is the largest offset we observe.
           Effectively, the array is actually a struct with x fields, and we only ever access the zeroeth element.

           THIS WILL NOT WORK WITH HOOKS
         */
        for((x, offsets) in arrayOffsets.toMap()) {
            resolveElementSize(Storage.Derived(x), offsets.keys.maxOrNull()!! + BigInteger.ONE, null, null)
        }
    }

    class InfeasibleState : Exception()

    private inner class FreshVarAllocator(private val defs: IDefAnalysis) {
        private val freshDecls: MutableMap<Pair<Set<CmdPointer>, TACSymbol>, TACSymbol.Var> = mutableMapOf()
        private val defCache: MutableMap<Pair<CmdPointer, TACSymbol>, Set<CmdPointer>> = mutableMapOf()

        /**
         * Generate (or recall) the fresh variable to hold the value of [orig] after [p].
         * @param graph the program p belongs to
         * @param p pointer to the assignment to [orig]
         * @param orig the value we will save
         * @param isDefsite if [orig] is a Var that is being defined (is the lhs of the command) at [p]
         *        we do not infer this incase the command at [p] both uses *and* defines [orig].
         * @param suffix the suffix of the tmp name to use IF we create a new variable.
         *        if there is already an allocation for the key (p, orig), then
         *        the returned variable will use the suffix passed at allocation time
         *        (i.e. the value of suffix is ignored on all future lookups)
         * @return a fresh variable name for the pair (p, orig) if one does not exist in [freshVariables],
         *         or the previously generated name if it does exist.
         */
        fun allocFresh(
            p: CmdPointer,
            orig: TACSymbol,
            isDefsite: Boolean,
            suffix: String = "fresh"
        ): TACSymbol.Var {
            // Cache
            val defs = if (!isDefsite && orig is TACSymbol.Var) {
                defCache.computeIfAbsent(p to orig) {
                    defs.defSitesOf(orig, p)
                }
            } else {
                setOf(p)
            }
            return freshDecls.computeIfAbsent(defs to orig) {
                suffix.toTmpVar()
            }
        }

        val allocatedFreshVars: Map<Pair<Set<CmdPointer>, TACSymbol>, TACSymbol.Var>
            get() = freshDecls.mapValues { (_, v) -> v }

        private fun String.toTmpVar() =
            TACKeyword.TMP(Tag.Bit256, "_${this}")
    }
}

@Treapable
data class StorageAnalysisFailureInfo(
    val lowLevelMsg: String,
    val userFacingMsg: UserFailMessage.StorageAnalysisFailureMessage
) : Serializable

val STORAGE_ANALYSIS_FAILURE = MetaKey<StorageAnalysisFailureInfo>("storage.analysis.failure", erased = true)
val STORAGE_ANALYSIS_SKIPPED_LIBRARY = MetaKey.Nothing("storage.analysis.skipped.library")

class StorageAnalysisFailedException(val s: String, val loc: CmdPointer?) : Exception(s)
