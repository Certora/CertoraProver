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

package analysis.pta

import analysis.*
import analysis.alloc.AllocationAnalysis
import analysis.alloc.StorageArrayLengthFinder
import analysis.numeric.CanonicalSum
import analysis.numeric.IntQualifier
import analysis.numeric.IntValue
import analysis.numeric.PathInformation
import analysis.numeric.linear.LVar
import analysis.numeric.linear.TermMatching.matches
import analysis.numeric.linear.TermMatching.matchesAny
import analysis.pta.ConcreteSpaceSet.ConcreteCell
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import evm.MAX_EVM_UINT256
import log.*
import spec.cvlast.typedescriptors.EVMLocationSpecifier
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.*
import vc.data.*
import java.io.ObjectOutputStream
import java.io.Serializable
import java.math.BigInteger

private val logger = Logger(LoggerTypes.POINTS_TO)

sealed interface HeapValue {
    fun strictJoin(h: HeapValue) : HeapValue
    fun getType(h: AbstractHeap) : HeapType
    fun toPointsToValue() : VPointsToValue
}

interface MaybeWritablePointer {
    fun writableIfSafe(pointerName: TACSymbol.Var, state: PointsToDomain, where: LTACCmd) : WritablePointer?
}

interface MaybeReadablePointer {
    fun readableIfSafe(pointerName: TACSymbol.Var, state: PointsToDomain, where: LTACCmd, relaxedChecks: Boolean) : ReadablePointer?
}

interface WritablePointer : MaybeWritablePointer {
    fun getReferencedTypeOrNull(h: AbstractHeap) : HeapType?
    override fun writableIfSafe(pointerName: TACSymbol.Var, state: PointsToDomain, where: LTACCmd): WritablePointer? = this
}

interface ReadablePointer : MaybeReadablePointer{
    fun getReferencedType(h: AbstractHeap) : HeapType
    override fun readableIfSafe(
        pointerName: TACSymbol.Var,
        state: PointsToDomain,
        where: LTACCmd,
        relaxedChecks: Boolean
    ): ReadablePointer? = this
}

interface BlockPointer {
    val blockAddr: Set<L>
}

@Treapable
sealed class ElementSize : Serializable {
    data class Concrete(val x: BigInteger) : ElementSize() {
        override fun consistentWith(c: BigInteger) : Boolean = x == c
    }
    object Bottom : ElementSize() {
        override fun consistentWith(c: BigInteger) : Boolean = true
        fun readResolve(): Any = Bottom
        override fun hashCode(): Int {
            return utils.hashObject(this)
        }
    }
    abstract fun consistentWith(c: BigInteger) : Boolean
    fun toConcrete() : BigInteger? = (this as? Concrete)?.x
}

/**
 * Basis of our allocation site based abstract heap.
 * Allocation sites are either explicit, in which case they wrap an update of the free pointer as represented by
 * the wrapped [analysis.alloc.AllocationAnalysis.AbstractLocation] object,
 * or a synthetic allocation which corresponds to some replaced call.
 */
@Treapable
sealed class AllocationSite : Serializable {
    data class Explicit(val alloc: AllocationAnalysis.AbstractLocation) : AllocationSite()
    data class Synthetic(
        val invokeId: Int,
        val argInd : Int,
        val ordinal: Int,
        val elementSize: ElementSize?
    ) : AllocationSite()
}

/*
Our heap is a dependent map from abstract locations to memory objects. Depending on whether the abstract location
is an array or an object, the heap maps to an IndexMap or a Byte or regular Array. This dependency is possible because,
unlike Java, there is no common super type for arrays and objects (i.e., structs); thus a reference type will always
holds struct pointer or array pointers, never both. This makes reading out of the heap much simpler as we will see.

Further, pointers to these different types of values (arrays or structs) support different operations, so it is
critical we do not use a monolithic "address in heap" abstraction; we have BlockPointers, and several different sorts
of array pointers. By partitioning the abstract location types, it is simple to recover the correct abstraction for an
address without consulting the heap.
 */
sealed class ArrayAbstractLocation {
    /*
    We have a special "empty array" address to represent the constant 0x60. This address must not be used for reading or
    writing elements (due to the array hints described in PointsToAnalysis.kt, it should be filtered out by the bounds
    checks emitted by the compiler).
     */
    object EMPTY_ARRAY : ArrayAbstractLocation()

    /*
    A is a "true" allocated array somewhere in memory. For the purposes of this analysis, the abstract location is an
    opaque object equipped with equality.
     */
    data class A(val addr: AllocationSite) : ArrayAbstractLocation() {
        constructor(alloc: AllocationAnalysis.AbstractLocation) : this(AllocationSite.Explicit(alloc))
    }

    data class StructAlias(val addr: L) : ArrayAbstractLocation()
}

fun AllocationSite.getElementSize() : ElementSize? {
    return when(this) {
        is AllocationSite.Explicit -> (this.alloc.sort as? AllocationAnalysis.WithElementSize)?.getElementSize()?.let(ElementSize::Concrete)
        is AllocationSite.Synthetic -> this.elementSize
    }
}

/**
 * An [InitAddress], which wraps an array abstract location during initialization, will NEVER be used for synthetic locations,
 * as those locations are never initialized, they are magiced into existence by the summary.
 */
data class InitAddress(val addr: AllocationAnalysis.AbstractLocation)

/*
The codomain of L addresses are structs (L = location). There is no equivalent of EMPTY_ARRAY for these
 */
data class L(val addr: AllocationSite) {
    constructor(alloc: AllocationAnalysis.AbstractLocation) : this(AllocationSite.Explicit(alloc))
}

typealias C = ConcreteAllocation

/**
 * The result of a query for the tuple type of a struct.
 */
sealed class TupleTypeResult {
    /**
     * A struct has multiple fields, and each field is has (a subtype of) type [v]
     */
    data class TupleResult(val v: HeapType) : TupleTypeResult()

    /**
     * The struct has no defined fields (yet). This is by definition tuple safe
     */
    object Empty : TupleTypeResult()
}

sealed interface ArrayType : HeapType.ReferenceType

/*
The heap is strongly typed; it is considered a program error to write incompatible values into the same memory cell.
This class abstracts the type of heap values, both the values pointed to by abstract locations, and the types
of the contents of those locations.
 */
@KSerializable
@Treapable
sealed class HeapType : AmbiSerializable {

    sealed interface ReferenceType

    @KSerializable
    object Int : HeapType() {
        override fun hashCode() = utils.hashObject(this)
        fun readResolve(): Any = Int
    }

    @KSerializable
    data class OffsetMap(val fieldTypes: Map<BigInteger, HeapType>, val sz: BigInteger, val mustNotBeEmptyArray: Boolean) : HeapType(), ReferenceType {
        override fun recursiveResolution(): HeapType = this.copy(fieldTypes.mapValues { it.value.recursiveResolution() })
    }

    @KSerializable
    object ByteArray : HeapType(), ArrayType {
        override fun hashCode() = utils.hashObject(this)
        fun readResolve(): Any = ByteArray
    }

    @KSerializable
    data class Array(val elementType: HeapType) : HeapType(), ArrayType {
        override fun recursiveResolution(): HeapType = this.copy(elementType = elementType.recursiveResolution())
    }

    @KSerializable
    object EmptyArray : HeapType(), ArrayType {
        override fun hashCode() = utils.hashObject(this)
        fun readResolve(): Any = EmptyArray
    }

    @KSerializable
    data class TVar(val ty: TypeVariable) : HeapType() {
        override fun recursiveResolution(): HeapType = this.ty.ifResolved(Int, EmptyArray) ?: this
        /*
         I don't... feel great about this,
         */
        private fun writeObject(obj: ObjectOutputStream) {
            unused(obj)
            throw UnsupportedOperationException("Attempting to serialize the (as yet) unresolved type variable $ty (${ty.where})")
        }
    }

    open fun recursiveResolution(): HeapType = this
    fun join(heapType: HeapType): HeapType = tryJoin(heapType)

    open fun checkCompatibility(writeType: HeapType): Boolean {
        return checkCompatibility(this, writeType)
    }

    fun tryResolve() : HeapType = if(this is TVar) {
        this.ty.ifResolved(Int, EmptyArray) ?: this
    } else {
        this
    }

    companion object {
        private fun canUnifyWithVar(
                t: HeapType) = t is Int || t is EmptyArray || t is ByteArray || t is Array || t is TVar || t is OffsetMap && !t.mustNotBeEmptyArray

        fun checkCompatibility(t1: HeapType, t2: HeapType) : Boolean {
            return checkCompatibilityRes(t1.tryResolve(), t2.tryResolve())
        }

        private fun checkCompatibilityRes(t1: HeapType, t2: HeapType): Boolean {
            if (t1 == t2) {
                return true
            }
            assert(isUnresolvedTV(t1))
            assert(isUnresolvedTV(t2))
            return when {
                (t1 is TVar && canUnifyWithVar(t2)) || (t2 is TVar && canUnifyWithVar(t1)) -> true

                t1 is EmptyArray && (t2 is Array || t2 is ByteArray || t2 is OffsetMap && !t2.mustNotBeEmptyArray) -> true
                t2 is EmptyArray && (t1 is Array || t1 is ByteArray || t1 is OffsetMap && !t1.mustNotBeEmptyArray) -> true

                t1 is Array && t2 is Array -> checkCompatibility(t1.elementType, t2.elementType)
                t1 is OffsetMap && t2 is OffsetMap -> t1.sz == t2.sz && t1.fieldTypes.keys == t2.fieldTypes.keys &&
                        t1.fieldTypes.entries.all { checkCompatibility(it.value, t2.fieldTypes[it.key]!!) }
                else -> false
            }
        }

        private fun tryJoin(t1: HeapType, t2: HeapType): HeapType = tryJoinRes(t1.tryResolve(), t2.tryResolve())
        /*
        It significantly simplifies this algorithm (and the compatibility check above) if we assume all type variables that
        occur must be unresolved. Rather than materialize a termporary type that we will immediately throw away, there is
        a loop between tryJoin and tryJoinRes that attempts resolution on both input types, and then calls into tryJoinRes.
         */
        private fun tryJoinRes(t1: HeapType, t2: HeapType): HeapType {
            if (t1 == t2) {
                return t1
            }
            if (t1 is TVar && t2 is TVar) {
                t1.ty.unify(t2.ty)
                return t1
            }
            if (t1 is TVar) {
                return tryJoinRes(t2, t1)
            }
            assert(t1 !is TVar)
            assert(isUnresolvedTV(t2))
            return when (t1) {
                is Int -> if (t2 is TVar) {
                    t2.ty.expectInt(Int)
                } else if (t2 is Int) {
                    t1
                } else {
                    throw AnalysisFailureException("Unify $t1 $t2")
                }
                is EmptyArray -> if (t2 is TVar) {
                    t2.ty.expectPointer(EmptyArray)
                } else if (t2 is ByteArray || t2 is Array) {
                    t2
                } else if (t2 is OffsetMap && !t2.mustNotBeEmptyArray) {
                    t1
                } else {
                    throw AnalysisFailureException("Unify $t1 $t2")
                }
                is ByteArray -> if (t2 is TVar) {
                    t2.ty.expectPointer(t1)
                } else if (t2 is EmptyArray) {
                    t1
                } else {
                    throw AnalysisFailureException("Cannot unify byte array and $t2")
                }
                is Array -> if (t2 is TVar) {
                    t2.ty.expectPointer(t1)
                } else if (t2 is EmptyArray) {
                    t1
                } else if (t2 is Array) {
                    Array(tryJoin(t1.elementType, t2.elementType))
                } else {
                    throw AnalysisFailureException("Cannot unify array $t1 with $t2")
                }
                is OffsetMap ->
                    if (t2 is TVar && !t1.mustNotBeEmptyArray) {
                        t2.ty.expectPointer(EmptyArray)
                    } else if(t2 is EmptyArray && !t1.mustNotBeEmptyArray) {
                        t2
                    } else if (t2 !is OffsetMap) {
                        throw AnalysisFailureException("cannot unify offset map and $t2")
                    } else if (t2.sz != t1.sz) {
                        throw AnalysisFailureException("Cannot unify mismatched sized blocks ${t1.sz} & ${t2.sz}")
                    } else if (t2.fieldTypes.keys != t1.fieldTypes.keys) {
                        throw AnalysisFailureException("Mismatched keys for block ${t1.fieldTypes.keys} & ${t2.fieldTypes.keys}")
                    } else {
                        val field1 = t1.fieldTypes
                        val field2 = t2.fieldTypes
                        OffsetMap(sz = t1.sz, fieldTypes = joinTypeMaps(field1, field2), mustNotBeEmptyArray = t1.mustNotBeEmptyArray || t2.mustNotBeEmptyArray)
                    }
                else -> error("This should be impossible $t1 and $t2")
            }
        }

        private fun isUnresolvedTV(t1: HeapType) = t1 !is TVar || !t1.ty.isResolved()

        private fun joinTypeMaps(field1: Map<BigInteger, HeapType>,
                                 field2: Map<BigInteger, HeapType>) : Map<BigInteger, HeapType> {
            return field1.entries.fold(mutableMapOf<BigInteger, HeapType>()) { Curr, (k, v) ->
                Curr[k] = tryJoin(v, field2[k]!!)
                Curr
            }.toMap()
        }
    }

    fun tryJoin(ty: HeapType): HeapType {
        return tryJoin(this, ty)
    }

    fun isDynamic() : Boolean = when(this) {
        is Array,
        ByteArray,
        EmptyArray -> true
        Int -> false
        is OffsetMap -> this.fieldTypes.any {
            it.value.isDynamic()
        }
        is TVar -> false
    }
}

interface FieldTypeDelegate {
    operator fun get(o: BigInteger, heap: AbstractHeap): HeapType?
    val keys: Collection<BigInteger>
    fun toMap(h: AbstractHeap): Map<BigInteger, HeapType>
}

/*
An abstraction of a struct in the heap. Tracks a map from indices to heap values, the total size, and the types
of each field. (fairly standard ops)
 */
data class IndexMap(val m: Map<BigInteger, HeapValue>, val sz: BigInteger, val mustNotBeEmptyArray: Boolean) {
    private fun strongUpdate(ind: BigInteger, v: HeapValue, killMaybeArray: Boolean) = setField(
        ind,
        v,
        killMaybeArray
    )

    val fieldTypes = object : FieldTypeDelegate {
        override operator fun get(o: BigInteger, heap: AbstractHeap) : HeapType? = m[o]?.getType(heap)
        override val keys: Collection<BigInteger>
            get() = m.keys

        override fun toMap(h: AbstractHeap): Map<BigInteger, HeapType> {
            return this.keys.associateWith {
                get(it, h)!!
            }
        }
    }

    private fun setField(ind: BigInteger, v: HeapValue, killMaybeArray: Boolean) = this.copy(
            m = this.m.plus(ind to v),
            mustNotBeEmptyArray = mustNotBeEmptyArray || killMaybeArray
    )

    private fun weakUpdate(ind: BigInteger, v: HeapValue, killMaybeArray: Boolean): IndexMap {
        val joined = this.m[ind]?.strictJoin(v) ?: v
        return this.setField(ind, joined, killMaybeArray)
    }

    fun updateField(
        ind: BigInteger, heapValue: HeapValue,
        strongUpdate: Boolean, killMaybeArray: Boolean
    ): IndexMap = if (strongUpdate) {
        this.strongUpdate(ind, heapValue, killMaybeArray)
    } else {
        this.weakUpdate(ind, heapValue, killMaybeArray)
    }

    fun join(other: IndexMap, thisIsSummary: Boolean, otherIsSummary: Boolean, isInitializing: Boolean): IndexMap {
        if (other === this) {
            return this
        }
        if (this.sz != other.sz) {
            logger.warn { "This block id has total size $sz, the other has size ${other.sz}" }
            throw AnalysisFailureException("Mismatched sizes")
        }
        if(this.m.keys != other.m.keys) {
            @Suppress("KotlinConstantConditions")
            if(!(other.m.keys.containsAll(this.m.keys) && other.fieldTypes.keys.containsAll(this.fieldTypes.keys) && ((!thisIsSummary && otherIsSummary) || (isInitializing && !thisIsSummary && !otherIsSummary))) &&
                    !(this.m.keys.containsAll(other.m.keys) && this.fieldTypes.keys.containsAll(other.fieldTypes.keys) && ((thisIsSummary && !otherIsSummary) || (isInitializing && !thisIsSummary && !otherIsSummary)))) {
                throw AnalysisFailureException("Mismatched domains, ${this.m.keys} vs ${other.m.keys} and ${this.fieldTypes.keys} vs ${other.fieldTypes.keys}")
            }
        }
        val m_join = if (other.m === this.m) {
            m
        } else {
            this.m.pointwiseBinop(other.m, null, null) { a, b ->
                if(a == null) {
                    check(b != null)
                    b
                } else if(b == null) {
                    a
                } else {
                    a.strictJoin(b)
                }
            }
        }
        return IndexMap(m = m_join, sz = this.sz, mustNotBeEmptyArray = this.mustNotBeEmptyArray || other.mustNotBeEmptyArray)
    }
}

/*
We actually have two array abstractions: "regular" arrays, and byte arrays. The first are unpacked arrays, and
the latter is an abstraction of the `bytes` type. Why two representations? Well, In the latter case we want
precise tracking of values stored at specific indices (we do not do any such tracking for general arrays). Further,
only the latter type supports single byte writes. Notice, however, that we do not split the array pointer LOCATION
abstraction. Why not? Well, both pointer types support very similar operations: check length, increment by 0x20,
get an element pointer, etc. etc. Rather than implement all that logic twice, we lump array pointers together,
and then branch on heap accesses (because until an actual write, the pointers behave almost identically)
 */
sealed class ArrayBlock {
    data class Array(val elem: HeapValue?, override val mustBeEmpty: Boolean) : ArrayBlock() {
        override fun join(other: ArrayBlock): ArrayBlock = other.join(this)

        fun elementType(h: AbstractHeap) : HeapType? = elem?.getType(h)

        override fun join(other: Array): ArrayBlock {
            return this.copy(
                elem = this.elem?.let { b -> other.elem?.strictJoin(b) ?: b } ?: other.elem,
                mustBeEmpty = this.mustBeEmpty && other.mustBeEmpty
            )
        }

        override fun join(other: ByteArray): ArrayBlock {
            throw AnalysisFailureException("Tried to join array $this with byte array $other")
        }
    }

    object ByteArray : ArrayBlock() {
        override fun join(other: ArrayBlock): ArrayBlock = other.join(this)

        override fun join(other: Array): ArrayBlock = throw AnalysisFailureException("Tried to join array $this with byte array $other")

        override fun join(other: ByteArray): ArrayBlock = this

        override val mustBeEmpty = false
    }

    abstract fun join(other: ArrayBlock) : ArrayBlock
    abstract fun join(other: Array): ArrayBlock
    abstract fun join(other: ByteArray): ArrayBlock
    abstract val mustBeEmpty : Boolean
}

/*
A wrapper around the memory block (ArrayBlock or IndexMap) with a summary flag (for allocations within loops)
 */
data class AbstractObject<out T>(val block: T, val summary: Boolean)

/**
 * The concrete space set abstraction. The space is an (unordered) list of [ConcreteCell] abstractions.
 * These [ConcreteCell] values represent the ranges of memory being accessed by the program, this abstraction
 * is (somewhat) flow-sensitively updated during analysis, see the documentation of [ConcreteCell] for details.
 */
data class ConcreteSpaceSet(private val locations: List<ConcreteCell>) {
    init {
        ptaInvariant(locations.sortedBy {
            it.rangeRefinement.lb
        }.zipWithNext { a, b ->
            a.rangeRefinement.ub < b.rangeRefinement.lb
        }.all { it }) {
            "Expected disjoint concrete regions, found overlaps: $locations"
        }
    }

    /**
     * An abstraction of a range of memory that is accessed by the program. This range of memory is represented with
     * two components, the [allocationCell] of type [C], and the [rangeRefinement] (which is an interval abstraction).
     *
     * The allocation cell [C] fulfills the role of a memory partition in the symbolic, FP (free pointer)-based view of memory. Specically,
     * any reads to a segment of memory that could be affected by writes must share an allocation cell [C] (this sharing is actually
     * enforced via union/find style unification as sharing is discovered during analysis). However, the actual range/segment
     * of memory associated with each [C] may change as writes are performed. For example, consider some segment
     * of memory c1 that covers the concrete range (64,127), with the end point being inclusive.
     * If there is then a write to offset 64, this will be assigned a new allocation cell c2, and given the range
     * (64,95), where c1's range is updated to be (96,127). This matches our intuition for the effect of a (definite)
     * write to concrete offset 64; any values that were previously written in the range (64,127) are now completely dead,
     * and superseded by the values written in `64` which are given a new allocation cell. If, however, a later operation
     * reads values across both ranges (e.g., a [vc.data.TACCmd.Simple.CallCore] command whose input range is specified as
     * 64 to 127) then c1 and c2 will be unified; this demonstrates the discovery of sharing mentioned above.
     * However, their corresponding [ConcreteCell] objects will *not* be unified as this will lose precision.
     *
     * After the abstract state stabilizes, the [C] instances are grouped by their equivalence classes, and the total range
     * for the equivalence class is computed. Note that this range is flow-INsensitive. See the [ConcreteAllocationManager.finalizeUnification]
     * documentation for more details.
     *
     * There are two other components to a [ConcreteCell]. The first is the [valueApproximation]; which is an interval
     * abstraction of the value in a [ConcreteCell]. It is an invariant that this value is definitely null if the [ConcreteCell]
     * does not cover exactly one word, i.e., [rangeRefinement] is not (k,k+31).
     *
     * [isUnallocedZeroRead] indicates a cell that is being read that has definitely not been previously written, in which
     * case the value it holds should be assumed to zero. This field is likely vestigial given [valueApproximation].
     */
    data class ConcreteCell(
        val allocationCell: C,
        val rangeRefinement: IntValue,
        val valueApproximation: IntValue?,
        val isUnallocedZeroRead: Boolean
    ) {
        /**
         * Returns this [ConcreteCell], having unified this cell's underlying [allocationCell] with [other].
         */
        fun unify(other: C): ConcreteCell {
            return this.copy(allocationCell = other.unify(allocationCell))
        }

        val range: IntValue get() = rangeRefinement
    }

    fun isEmpty() = locations.isEmpty()
    fun isNotEmpty() = locations.isNotEmpty()

    /**
     * Completely remove the abstraction for [k].
     */
    fun delete(k: C) = this.copy(locations = this.locations.filter {
        it.allocationCell.find() != k
    })

    /**
     * Find all [C] which may be covered by [locationsFor]; these cells are deduped according to the unification and
     * as such m7ust be
     */
    fun ranges(locationsFor: IntValue): List<C> = locations.filter {
        it.rangeRefinement.mayIntersect(locationsFor)
    }.map { it.allocationCell }.distinctBy { it.find() }

    /**
     * Convenience function for [IntValue] to determine if a range being written is word that exactly
     * overwrites [other].
     */
    private fun IntValue.supportsStrongUpdate(other: IntValue) = this == other && this.isWordRange()

    /**
     * This function is called "before" the abstract execution of a statement, and serves to populate
     * the *pre*-state of a command (so, despite its name, it usually occurs *after* the execution of another command,
     * namely the proceeding command of the statement in question). This somewhat convluted setup is done so that the
     * concrete allocation is always populated in the prestate of the command that accesses that location, which is
     * necessary to enable the [IPointsToInformation] APIs to work as expected.
     *
     * [c] is the allocation cell associated with the range [allocRange] being accessed. Whether this access is a write
     * or not is determined by [isWrite]
     */
    fun prealloc(c: C, allocRange: IntValue, isWrite: Boolean, forceUnification: Boolean = false) : ConcreteSpaceSet {
        /*
          The result of the allocation, that is, the ConcreteCells that are allocated/updated
         */
        val locs = mutableListOf<ConcreteCell>()
        /**
          Those locations which overlap with [allocRange]. Any other locations are considered to not be relevant, and
         are added as is to [locs]
         */
        val overlaps = mutableListOf<ConcreteCell>()
        /**
         * Populate overlaps, and put those other [ConcreteCell] into the output list locs.
         */
        for(l in locations) {
            if(!l.range.mayIntersect(allocRange)) {
                locs.add(l)
            } else {
                overlaps.add(l)
            }
        }
        val allowStrongUpdate = !isWrite || !forceUnification
        // how do we want to try to unify?
        if(overlaps.isEmpty()) {
            preallocFresh(locs, c, allocRange, isWrite)
        /**
         * Definitely reading/writing from a single, existing cell
         */
        } else if(overlaps.size == 1 && overlaps.single().range.supportsStrongUpdate(allocRange) && allowStrongUpdate) {
            preallocExactMatch(isWrite, locs, c, allocRange, overlaps.single())
        } else if(isWrite) {
            /*
             That is, we have a definite write to this index, i.e., we have write of a single word.
             */
            if (allocRange.isWordRange() && allowStrongUpdate) {
                preallocStrongUpdateWrite(overlaps, allocRange, locs, c)
            } else {
                /*
                  we are writing to a non-definite index and we overlap multiple ranges, this is the
                  conservative case
                 */
                preallocWeakUpdateWrite(overlaps, allocRange, c, locs)
            }
        } else {
            // we are overlapping with multiple ranges or do not have an exact match
            preallocWeakRead(overlaps, allocRange, locs)
        }
        /**
         * And we are done.
         */
        return ConcreteSpaceSet(locs)
    }

    /**
     * This is the first time we are seeing this range, in
     * which case add the cell directly.
     */
    private fun preallocFresh(
        locs: MutableList<ConcreteCell>,
        c: C,
        allocRange: IntValue,
        isWrite: Boolean
    ) {
        locs.add(
            ConcreteCell(
                allocationCell = c,
                rangeRefinement = allocRange,
                valueApproximation = null,
                /**
                 * If this is a read (i.e. ![isWrite]) of a cell that has not been allocated already,
                 * record that this is an unalloaced read of zero.
                 */
                isUnallocedZeroRead = !isWrite
            )
        )
    }

    /**
     * We are writing to a single word that exactly matches the single [uniqueOverlap]
     */
    private fun preallocExactMatch(
        isWrite: Boolean,
        locs: MutableList<ConcreteCell>,
        c: C,
        allocRange: IntValue,
        uniqueOverlap: ConcreteCell
    ) {
        /**
         * If we are writing, then kill the old cell.
         */
        if (isWrite) {
            locs.add(ConcreteCell(c, allocRange, null, isUnallocedZeroRead = false))
        } else {
            /**
             * Otherwise just unify the underlying cells, the range and abstract value are unchanged.
             */
            locs.add(
                uniqueOverlap.copy(
                    allocationCell =
                    uniqueOverlap.allocationCell.unify(c)
                )
            )
        }
    }

    private fun preallocWeakRead(
        overlaps: MutableList<ConcreteCell>,
        allocRange: IntValue,
        locs: MutableList<ConcreteCell>
    ) {
        /**
         * Then we have a read with multiple or unaligned overlaps. Unify the underlying cells for sequential
         * pairs (which has the effect of unifying all cells together).
         */
        for (i in 0..<overlaps.lastIndex) {
            overlaps[i].allocationCell.unify(overlaps[i + 1].allocationCell)
        }
        /**
         * Because we aren't updating the range refinements, we need to manually update the ranges associated
         * with these cells according to [allocRange].
         */
        overlaps.forEach {
            it.allocationCell.range = it.allocationCell.range.let(allocRange::join)
        }
        /**
         * Add in the overlaps unchanged
         */
        locs.addAll(overlaps)
    }

    private fun preallocWeakUpdateWrite(
        overlaps: MutableList<ConcreteCell>,
        allocRange: IntValue,
        c: C,
        locs: MutableList<ConcreteCell>
    ) {
        /**
         * Then we have a write that spans more than a word, or we've been told to force unification.
         * Give up: unify all of the cells and their ranges, and add a new larger cell in their place.
         */
        val newRange = overlaps.fold(allocRange) { acc, cell ->
            acc.join(cell.rangeRefinement)
        }
        val newCell = overlaps.fold(c) { acc, cell ->
            acc.unify(cell.allocationCell)
        }
        locs.add(
            ConcreteCell(
                allocationCell = newCell,
                rangeRefinement = newRange,
                isUnallocedZeroRead = false,
                valueApproximation = null
            )
        )
    }

    private fun preallocStrongUpdateWrite(
        overlaps: MutableList<ConcreteCell>,
        allocRange: IntValue,
        locs: MutableList<ConcreteCell>,
        c: C
    ) {
        var needAlloc = true
        for (o in overlaps) {
            /*
               How does this range overlap?
             */
            if (o.range.lb < allocRange.lb && allocRange.ub < o.range.ub) {
                // this is full containment, there should only be *one* of these
                ptaInvariant(overlaps.size == 1) {
                    "Expecting single range for full containment, have multiple: $overlaps"
                }
                // then simply unify, and leave the abstraction unchanged.
                locs.add(overlaps.single().unify(c))
                /**
                 * indicate we shouldn't add a cell. Note that we could actually divide `o` into two pieces and
                 * still allocate a strong cell, this is left for later if it is determined to be necessary.
                 */
                needAlloc = false
                // for clarity, as established above, the loop is about to end
                break
            } else if (o.range.lb < allocRange.lb) {
                /**
                 * Then this write is slicing off the "top" of o.
                 */
                ptaInvariant(o.range.ub <= allocRange.ub) {
                    "Math is broken ${o.range} vs $allocRange"
                }
                /**
                 * Update the upper bound of the range covered by o to no terminate at where we are writing.
                 */
                locs.add(
                    o.copy(
                        rangeRefinement = o.rangeRefinement.withUpperBound(allocRange.lb - BigInteger.ONE)
                    )
                )
            } else if (o.range.lb >= allocRange.lb && o.range.ub > allocRange.ub) {
                /*
                           Then we are slicing off the "bottom" of o, adjust the lower bound of o.
                         */
                locs.add(
                    o.copy(
                        rangeRefinement = o.rangeRefinement.withLowerBound(allocRange.ub + BigInteger.ONE)
                    )
                )
            } else {
                // full containment, o is completely contained within the cell being written
                ptaInvariant(allocRange.lb <= o.range.lb && o.range.ub <= allocRange.ub) {
                    "Math is bad ${o.range} vs $allocRange"
                }
                // do nothing, this cell is killed
            }
        }
        if (needAlloc) {
            /**
             * Add the cell, if it is not already contained entirely in another cell.
             */
            locs.add(
                ConcreteCell(
                    rangeRefinement = allocRange,
                    allocationCell = c,
                    valueApproximation = null,
                    isUnallocedZeroRead = false
                )
            )
        }
    }

    /**
     * Add a binding [elem] into the locations map. It is assumed and checked that
     * there exists a [ConcreteCell] in [locations] that overlaps (and indeed, subsumes) the range
     * denoted in the first element of [elem]. The abstraction supports strong updates: if the cell in
     * [locations] that overlaps with range in the first element of [elem] is exactly one word,
     * then the corresponding [ConcreteCell.valueApproximation] in [locations] is updated
     * to be the second component in [elem].
     */
    operator fun plus(elem: Pair<IntValue, IntValue?>) : ConcreteSpaceSet {
        val (writeRange, valueApprox) = elem
        val loc = locations.toMutableList()
        val targetIdx = locations.indexOfFirst {
            it.range.mayIntersect(writeRange)
        }.takeIf { it >= 0 } ?: throw AnalysisFailureException(
            "Couldn't find principal location for $elem, despite prealloc supposedly guaranteeing this $locations"
        )
        val c = locations[targetIdx]
        // is this a strong update? or a clear
        if(c.range.isWordRange()) {
            loc[targetIdx] = c.copy(valueApproximation = valueApprox)
        } else {
            loc[targetIdx] = c.copy(valueApproximation = null)
        }
        return this.copy(locations = loc.toList())
    }

    /**
     * Is this range k,k+31, that is, exactly one word
     */
    private fun IntValue.isWordRange() = this.ub - this.lb == (EVM_WORD_SIZE - BigInteger.ONE)

    /**
     * Despite the name, this is actually an upper bound operation, the shape of which is determined by the upper
     * bound operator for [IntValue] that is passed for [ub]. Intervals that overlap in the two states
     * are unified with each other, and if the unified abstraction covers a single word, the corresponding abstractions
     * are also joined/widenend using [ub].
     */
    fun join(other: ConcreteSpaceSet, ub: (IntValue, IntValue) -> IntValue) : ConcreteSpaceSet {
        val l = mutableListOf<ConcreteCell>()

        /**
         * Get all concrete cells, sorted by their start.
         */
        val allLocations = (this.locations + other.locations).sortedBy {
            it.range.lb
        }
        for(k in allLocations) {
            if(l.isEmpty() || l.last().range.mustNotOverlap(k.range)) {
                l.add(k)
            } else {
                val prevCell = l.removeLast()
                val newK = prevCell.allocationCell.unify(k.allocationCell)
                val newRange = prevCell.range.join(k.range)

                /**
                 * If the resulting range still supports value abstractions (i.e., it is of word size)
                 * then join the two value abstractions according to the upper bound operator [ub].
                 */
                val newV = if(newRange.isWordRange()) {
                    prevCell.valueApproximation?.let { v1 ->
                        k.valueApproximation?.let { v2 ->
                            ub(v1, v2)
                        }
                    }
                } else {
                    null
                }
                /**
                 * Zero read allocation is a must fact, so join with logical and
                 */
                l.add(ConcreteCell(allocationCell = newK, valueApproximation = newV, rangeRefinement = newRange, isUnallocedZeroRead = prevCell.isUnallocedZeroRead && k.isUnallocedZeroRead))
            }
        }
        return ConcreteSpaceSet(l)
    }

    fun mayIntersect(r: IntValue) : Boolean = findIntersection(r).isNotEmpty()

    /**
     * Find the single cell which overlaps with [r] in the current location abstraction.
     */
    fun findIntersection(r: IntValue) : Collection<C> {
        return locations.filter {
            it.range.mayIntersect(r)
        }.mapToSet { it.allocationCell.find() }
    }

    fun isUnallocedZeroRead(r: IntValue) : Boolean = locations.singleOrNull { it.range.mayIntersect(r) }?.isUnallocedZeroRead  ?: false

    /**
     * Find the value abstraction stored at the word that begins at [k] (if it exists).
     */
    fun valueAbstractionAt(k: BigInteger) : IntValue? {
        return this.locations.singleOrNull {
            it.range.let {
                it.isWordRange() && it.lb == k
            }
        }?.valueApproximation
    }
}

/**
 A spill location is a constant address less than the initial value of the
 free pointer (which guarantees it is disjoint from any allocated object, can
 be promoted to a register later)
*/
@Treapable
@JvmInline
value class SpillLocation(val addr: BigInteger)

/*
The actual heap. The block alloc and array alloc track "open" allocations for arrays and blocks. Multiple reads of the free
pointer during an active allocation do not count as a new allocation but a copy of the fresh pointer.
 */
data class AbstractHeap(
    val blockSpace: Map<L, AbstractObject<IndexMap>>,
    val spillSpace: Map<SpillLocation, HeapValue>,
    val arraySpace: Map<ArrayAbstractLocation.A, AbstractObject<ArrayBlock>>,
    val arrayInitSpace: Map<InitAddress, ArrayBlock>,
    val blockAlloc: Set<L>,
    val allocStack: List<AllocationAnalysis.AbstractLocation>,
    val concreteSpace: ConcreteSpaceSet,
    val ignoreNextZeroWrite: AllocationAnalysis.AbstractLocation?
) {

    fun plus(k: L, v: AbstractObject<IndexMap>): AbstractHeap = this.copy(blockSpace = blockSpace.plus(k to v))
    fun plus(k: ArrayAbstractLocation.A, v: AbstractObject<ArrayBlock>): AbstractHeap = this.copy(
            arraySpace = arraySpace.plus(k to v))

    fun allocate(k: L, fresh: () -> IndexMap): AbstractHeap {
        ptaInvariant(k.addr is AllocationSite.Explicit) {
            "Trying to allocate a synthetic object as initializing"
        }
        var pushStack = false
        val (sp, alloc) = if (k !in blockSpace) {
            assert(k !in blockAlloc)
            val obj = AbstractObject(summary = false, block = fresh())
            pushStack = true
            val upSpace = blockSpace.plus(k to obj)
            upSpace to (blockAlloc.plus(k))
        } else if (k in blockAlloc) {
            blockSpace to blockAlloc
        } else {
            val obj = blockSpace[k]!!.copy(summary = true)
            val upSpace = blockSpace.plus(k to obj)
            pushStack = true
            upSpace to blockAlloc.plus(k)
        }
        val stack = if(pushStack) {
            this.allocStack + k.addr.alloc
        } else {
            this.allocStack
        }
        return this.copy(blockSpace = sp, blockAlloc = alloc, allocStack = stack)
    }

    inline fun <reified T : ArrayBlock> updateObject(k: InitAddress, fresh: (T) -> T) : AbstractHeap =
        this.arrayInitSpace[k]?.let {
            check(it is T)
            val newObj = fresh(it)
            this.copy(arrayInitSpace = arrayInitSpace + (k to newObj))
        } ?: this

    operator fun get(p: L): AbstractObject<IndexMap>? = blockSpace.get(p)
    operator fun get(v: ArrayAbstractLocation.A): AbstractObject<ArrayBlock>? = arraySpace.get(v)
    operator fun contains(lloc: ArrayAbstractLocation.A): Boolean = lloc in arraySpace
    operator fun contains(lloc: L): Boolean = lloc in blockSpace
    fun join(h: AbstractHeap) = upperBound(h, false)

    fun upperBound(h: AbstractHeap, isWidening: Boolean): AbstractHeap {
        if (this.concreteSpace.isNotEmpty() || h.concreteSpace.isNotEmpty()) {
            val concreteSpace = this.concreteSpace.join(h.concreteSpace, if(isWidening) {
                @Suppress("USELESS_CAST")
                IntValue::widen as (IntValue, IntValue) -> IntValue
            } else {
                IntValue::join
            })
            return AbstractHeap(
                blockAlloc = setOf(),
                arraySpace = mapOf(),
                arrayInitSpace = mapOf(),
                blockSpace = mapOf(),
                allocStack = listOf(),
                concreteSpace = concreteSpace,
                ignoreNextZeroWrite = null,
                spillSpace = mapOf(),
            )
        }
        val arraySpace = (this.arraySpace.keys + h.arraySpace.keys).map { k ->
            k to (this[k]?.let { a ->
                h[k]?.let { b ->
                    AbstractObject(
                            summary = a.summary || b.summary,
                            block = a.block.join(b.block)
                    )
                } ?: a
            } ?: h[k]!!)
        }.toMap()
        val blockSpace = (this.blockSpace.keys + h.blockSpace.keys).map { k ->
            k to (this[k]?.let { a ->
                h[k]?.let { b ->
                    AbstractObject(
                            summary = a.summary || b.summary,
                            block = a.block.join(b.block, a.summary, b.summary, k in this.blockAlloc && k in h.blockAlloc)
                    )
                } ?: a
            } ?: h[k]!!)
        }.toMap()
        val initSpace = (this.arrayInitSpace.keys + h.arrayInitSpace.keys).map { k ->
            k to (this.arrayInitSpace[k]?.let { a ->
                h.arrayInitSpace[k]?.let { b ->
                    a.join(b)
                } ?: a
            } ?: h.arrayInitSpace[k]!!)
        }.toMap()
        // Take the intersection of spill locations. If a value is written in one
        // branch and not another and we try to read it, we will fail as we
        // disallow read-before-write of spill locs
        val spillSpace = this.spillSpace.entries.associateNotNull { (l, v1) ->
            h.spillSpace[l]?.let { v2 ->
                l to v1.strictJoin(v2)
            }
        }
        if(this.allocStack != h.allocStack) {
            throw AnalysisFailureException("Out of sync allocation stacks")
        }
        if(this.ignoreNextZeroWrite != h.ignoreNextZeroWrite) {
            throw AnalysisFailureException("Out of sync initialization zero write markers")
        }
        return AbstractHeap(
                blockAlloc = this.blockAlloc.intersect(h.blockAlloc),
                spillSpace = spillSpace,
                arrayInitSpace = initSpace,
                blockSpace = blockSpace,
                arraySpace = arraySpace,
                allocStack = this.allocStack,
                concreteSpace = ConcreteSpaceSet(listOf()),
                ignoreNextZeroWrite = h.ignoreNextZeroWrite
        )
    }

    operator fun get(v: InitAddress): AbstractObject<ArrayBlock>? =
        arrayInitSpace[v]?.let { AbstractObject(it, summary = false) }

    fun plus(v: InitAddress, newBlock: ArrayBlock): AbstractHeap {
        return this.copy(
            arrayInitSpace = this.arrayInitSpace + (v to newBlock)
        )
    }

    fun allocate(b: InitAddress, alloc: () -> ArrayBlock): AbstractHeap {
        if(b in arrayInitSpace) {
            return this
        }
        val o = alloc()
        return this.copy(
                arrayInitSpace = arrayInitSpace + (b to o),
                allocStack = this.allocStack + b.addr
        )
    }

    fun  isTupleSafeAddress(v: L) : TupleTypeResult? {
        return blockSpace[v]?.let { obj ->
            val t = obj.block.m
            if(t.isEmpty()) {
                TupleTypeResult.Empty
            } else {
                t.keys.monadicMap {
                    obj.block.fieldTypes[it, this]
                }?.monadicFold { t1, t2 ->
                    if(!t1.checkCompatibility(t2)) {
                        null
                    } else {
                        t1.join(t2)
                    }
                }?.let(TupleTypeResult::TupleResult)
            }
        }
    }

    /**
     * Helper function that updates the concrete space according to [f]. Dumps the symbolic heap representation (if any).
     */
    fun updateConcrete(cause: LTACCmd, f: (ConcreteSpaceSet) -> ConcreteSpaceSet) : AbstractHeap {
        if (this.concreteSpace.isEmpty()) {
            logger.info { "Switching to concrete mode at $cause" }
        }
        return AbstractHeap(
            blockAlloc = setOf(),
            spillSpace = mapOf(),
            arraySpace = mapOf(),
            arrayInitSpace = mapOf(),
            blockSpace = mapOf(),
            allocStack = listOf(),
            concreteSpace = f(this.concreteSpace),
            ignoreNextZeroWrite = null
        )
    }

    fun widen(h: AbstractHeap): AbstractHeap {
        return this.upperBound(h, true)
    }
}

/* Abstract values for the points to domain (store) */

/*
VPointsToValue includes unresolved variables and "concrete" values. (Perhaps V stands for "virtual"? Who can say...)
The only operation we have are the resolution operations (see TypeVariableManager for a discussion of tryResolveX vs tryResolve
vs. expectX
 */
sealed class VPointsToValue {
    abstract fun tryResolveInt(): PointsToValue
    abstract fun tryResolvePointer(): PointsToValue
    abstract fun tryResolve(): VPointsToValue
}

/*
A fully resolved value. The tryResolveX operations are nops
 */
sealed class PointsToValue : VPointsToValue() {
    override fun tryResolveInt(): PointsToValue {
        return this
    }

    override fun tryResolvePointer(): PointsToValue {
        return this
    }

    override fun tryResolve(): VPointsToValue = this
}

//object InvalidPointer : PointsToValue()

/*
An uninterpreted integer. This represents "true" integers, and pointers that we cannot precisely model.
 */
object INT : PointsToValue(), HeapValue {
    override fun strictJoin(h: HeapValue): HeapValue {
        if(h is UnkPointsTo) {
            return h.expectInt()
        }
        if(h !is INT) {
            throw TypeMismatchFailureException("Attempt to join int in heap with $h")
        }
        return INT
    }

    override fun getType(h: AbstractHeap): HeapType = HeapType.Int
    override fun toPointsToValue() = this

}

/**
 * Nullable in big scare quotes here.
 */
data class NullablePointer(
    val wrapped: BasePointer<*>
) : PointsToValue()

/*
A reference value, i.e., that can be used to access the heap
 */
sealed class ReferenceValue : PointsToValue()

interface FieldAccess : BlockPointer {
    val offset: BigInteger

    fun readField(heap: AbstractHeap) : HeapValue {
        ptaInvariant(this.blockAddr.isNotEmpty()) {
            "Pointer $this with empty points to set"
        }
        return this.blockAddr.map { addr ->
            check(addr in heap)
            val obj = heap[addr]!!
            check(this.offset in obj.block.m) // read checking assures this succeeds, but defense
            obj.block.m[this.offset]!!
        }.reduce(HeapValue::strictJoin)
    }

    /*
       If we *ever* write outside of initialization, then this is definitely not meant to be an array
     */
    fun updateField(heap: AbstractHeap, v: HeapValue, ty: HeapType) : AbstractHeap {
        return if(this.blockAddr.size == 1) {
            updateField(heap, this.blockAddr.first(), this.offset, v, strong = true, killMaybeArray = true)
        } else {
            this.blockAddr.fold(heap) { Curr, l ->
                updateField(Curr, l, this.offset, v, strong = false, killMaybeArray = true)
            }
        }
    }
}

sealed interface BasePointer<T: Pointer> : HeapValue

@Suppress("USELESS_CAST", "RemoveExplicitTypeArguments")
private fun <T: Pointer> BasePointer<T>.toPointer() : T = when(this) {
    is Pointer.ArrayPointer -> ((this as Pointer.ArrayPointer) as BasePointer<Pointer.ArrayPointer>).uncheckedAs<T>()
    is Pointer.BlockPointerBase -> ((this as Pointer.BlockPointerBase) as BasePointer<Pointer.BlockPointerBase>).uncheckedAs<T>()
    is Pointer.BlockPointerBaseAmbiguous -> {
        /*
          This proves that T is instantiated to BlockPointerBaseAmbiguous. We know that, but let's still have the compiler
          do SOME work for us
         */
        ((this as Pointer.BlockPointerBaseAmbiguous) as BasePointer<Pointer.BlockPointerBaseAmbiguous>).uncheckedAs<T>()
    }
}

/*
These are pointers to initialized, allocated memory locations
 */
sealed class Pointer : ReferenceValue() {
    abstract val referencedLocations: Collection<AllocationSite>

    data class BlockPointerBase(override val blockAddr: Set<L>, val blockSize: BigInteger) : Pointer(), ReadablePointer, WritablePointer, BasePointer<BlockPointerBase>, FieldAccess {
        override val offset : BigInteger get() = BigInteger.ZERO
        override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType = getReferencedType(h)

        override fun getReferencedType(h: AbstractHeap): HeapType {
            return blockAddr.map {
                h[it]!!.block.fieldTypes[BigInteger.ZERO, h] ?: throw AnalysisFailureException("Did not find a field at index 0 in $it")
            }.reduce(HeapType::join)
        }

        override fun strictJoin(h: HeapValue): HeapValue {
            if(h is BlockPointerBaseAmbiguous) {
                if(h.blockSize != this.blockSize) {
                    throw TypeMismatchFailureException("Attempt to join block pointer $this with size ${this.blockSize} with block pointer $h with incompatible size ${h.blockSize}")
                }
                h.expectBlock()
                return this.copy(blockAddr = h.blockAddr.union(blockAddr))
            } else if(h is BlockPointerBase) {
                if (h.blockSize != this.blockSize) {
                    throw TypeMismatchFailureException("Attempt to join block pointer $this with size ${this.blockSize} with block pointer $h with incompatible size ${h.blockSize}")
                }
                return this.copy(blockAddr = blockAddr.union(h.blockAddr))
            }
            throw TypeMismatchFailureException("Attempt to join block pointer $this with non-block $h")
        }

        override fun getType(h: AbstractHeap): HeapType {
            val mustNotBeEmptyArray = blockAddr.any { h[it]!!.block.mustNotBeEmptyArray }
            return blockAddr.map {
                h[it]!!.block.m.mapValues {
                    it.value.getType(h)
                }
            }.let { l ->
                ptaInvariant(l.isNotEmpty()) {
                    "$this has 0 addresses, unreachable pointer?"
                }
                val toRet = l.first().toMutableMap()
                for(i in 1 .. l.lastIndex) {
                    val merge = l[i]
                    if(merge.keys != toRet.keys) {
                        throw AnalysisFailureException("Inconsistent field domains")
                    }
                    for((k, v) in merge) {
                        toRet[k] = toRet[k]!!.tryJoin(v)
                    }
                }

                HeapType.OffsetMap(fieldTypes = toRet, sz = this.blockSize, mustNotBeEmptyArray = mustNotBeEmptyArray)
            }
        }

        override fun toPointsToValue() = this

        override val referencedLocations: Collection<AllocationSite>
            get() = this.blockAddr.map { it.addr }
    }

    data class BlockPointerField(override val blockAddr: Set<L>, override val offset: BigInteger, val blockSize: BigInteger) : Pointer(), ReadablePointer, WritablePointer, FieldAccess {
        override val referencedLocations: Collection<AllocationSite>
            get() = this.blockAddr.map { it.addr }

        override fun getReferencedType(h: AbstractHeap): HeapType {
            return blockAddr.map {
                h[it]!!.block.fieldTypes[offset, h] ?: throw AnalysisFailureException("Attempt to read uninitialized field $offset from address set $blockAddr")
            }.reduce(HeapType::join)
        }

        override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType = getReferencedType(h)
    }

    data class BlockPointerBaseAmbiguous(
        override val blockAddr: Set<L>,
        val blockSize: BigInteger,
        val tyvar: TypeVariableAlloc
    ) : Pointer(), ReadablePointer, WritablePointer, BasePointer<BlockPointerBaseAmbiguous>, FieldAccess {
        override val offset : BigInteger get() = BigInteger.ZERO
        override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType = getReferencedType(h)

        override fun getReferencedType(h: AbstractHeap): HeapType {
            // this is either an empty array (which would return HeapType.Int) or a pointer to a heap allocated Int.
            return HeapType.Int
        }

        override fun strictJoin(h: HeapValue): HeapValue {
            if(h is BlockPointerBase) {
                if(h.blockSize != this.blockSize) {
                    throw TypeMismatchFailureException("Attempt to join block pointer $this with size ${this.blockSize} with block pointer $h with incompatible size ${h.blockSize}")
                }
                expectBlock()
                return h.copy(blockAddr = h.blockAddr.union(blockAddr))
            } else if(h is BlockPointerBaseAmbiguous) {
                if (h.blockSize != this.blockSize && this.blockSize != EVM_WORD_SIZE) {
                    throw TypeMismatchFailureException("Attempt to join block pointer $this with size ${this.blockSize} with block pointer $h with incompatible size ${h.blockSize}")
                }
                unify(h)
                return this.copy(blockAddr = blockAddr.union(h.blockAddr))
            } else if(h is ArrayPointer) {
                expectEmptyArray()
                return h.copy(v = h.v.union(blockAddr.map { ArrayAbstractLocation.StructAlias(it) }))
            } else if(h is UnkPointsTo) {
                h.expectPointer()
                expectEmptyArray()
                return ArrayPointer(
                    v = setOf(
                        ArrayAbstractLocation.EMPTY_ARRAY
                    ) + blockAddr.map(ArrayAbstractLocation::StructAlias)
                )
            }
            throw TypeMismatchFailureException("Attempt to join block pointer $this with non-block $h")
        }

        override fun getType(h: AbstractHeap): HeapType {
            val mustNotBeEmptyArray = blockAddr.any { h[it]!!.block.mustNotBeEmptyArray }
            return blockAddr.map {
                h[it]!!.block.fieldTypes
            }.let { l ->
                ptaInvariant(l.isNotEmpty()) {
                    "$this has 0 addresses, unreachable pointer?"
                }
                val toRet = l.first().toMap(h).toMutableMap()
                for(i in 1 .. l.lastIndex) {
                    val merge = l[i].toMap(h)
                    if(merge.keys != toRet.keys) {
                        throw AnalysisFailureException("Inconsistent field domains")
                    }
                    for((k, v) in merge) {
                        toRet[k] = toRet[k]!!.tryJoin(v)
                    }
                }

                HeapType.OffsetMap(fieldTypes = toRet, sz = this.blockSize, mustNotBeEmptyArray = mustNotBeEmptyArray)
            }
        }

        override fun toPointsToValue() = this

        override val referencedLocations: Collection<AllocationSite>
            get() = this.blockAddr.map { it.addr }

        /*
           If we *ever* write outside of initialization, then this is definitely not meant to be an array
         */
        override fun updateField(heap: AbstractHeap, v: HeapValue, ty: HeapType) : AbstractHeap {
            expectBlock()
            return if(this.blockAddr.size == 1) {
                updateField(heap, this.blockAddr.first(), this.offset, v, strong = true, killMaybeArray = true)
            } else {
                this.blockAddr.fold(heap) { Curr, l ->
                    updateField(Curr, l, this.offset, v, strong = false, killMaybeArray = true)
                }
            }
        }

        fun expectBlock() = tyvar.expectBlock()
        fun expectEmptyArray() = tyvar.expectEmptyArray()
        fun unify(other: BlockPointerBaseAmbiguous) : TypeVariableAlloc = tyvar.unify(other.tyvar)
        fun isResolved() = tyvar.isResolved()
        @Suppress("unused")
        fun isResolvedBlock() = tyvar.isResolvedBlock()
        fun isResolvedArray() = tyvar.isResolvedArray()
    }

    /* Statically sized arrays are indistinguishable from struct pointers in their allocation. However, they do support
    indexing operations, and this class represents an offset into a statically sized array.
    */
    data class ConstSizeArrayElemPointer(val v: Set<L>) : Pointer(), ReadablePointer, WritablePointer, BlockPointer {
        override val blockAddr: Set<L>
            get() = v

        override fun getReferencedType(h: AbstractHeap): HeapType {
            return v.flatMap {
                h[it]!!.block.fieldTypes.toMap(h).values
            }.reduce(HeapType::join)
        }

        override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType = getReferencedType(h)

        override val referencedLocations: Collection<AllocationSite>
            get() = this.v.map { it.addr }
    }

    /* The base pointer of an array (byte or general). */
    data class ArrayPointer(val v: Set<ArrayAbstractLocation>) : Pointer(), ReadablePointer, BasePointer<ArrayPointer> {
        override fun strictJoin(h: HeapValue): HeapValue {
            return when(h) {
                is UnkPointsTo -> this.strictJoin(h.expectPointer())
                is ArrayPointer -> {
                    this.copy(v = v.union(h.v))
                }
                is BlockPointerBaseAmbiguous -> {
                    h.expectEmptyArray()
                    this.copy(v = v.union(h.blockAddr.map { ArrayAbstractLocation.StructAlias(it) } ))
                }
                else -> throw TypeMismatchFailureException("Attempted to join array pointer $this with non-array pointer $h")
            }
        }

        override fun getType(h: AbstractHeap): HeapType {
            return this.v.map { aLoc ->
                when(aLoc) {
                    is ArrayAbstractLocation.StructAlias, ArrayAbstractLocation.EMPTY_ARRAY -> HeapType.EmptyArray
                    is ArrayAbstractLocation.A -> {
                        h.arrayAction<HeapType>(aLoc, {
                            HeapType.ByteArray
                        }, { blk ->
                            blk.elementType(h)?.let(HeapType::Array) ?: HeapType.EmptyArray.takeIf { blk.mustBeEmpty } ?: throw AnalysisFailureException("Attempt to get type of uninitialized array $aLoc")
                        })
                    }
                }
            }.reduce(HeapType::join)
        }

        override fun toPointsToValue() = this

        override fun getReferencedType(h: AbstractHeap): HeapType = HeapType.Int

        override val referencedLocations: Collection<AllocationSite>
            get() = this.v.filterIsInstance(ArrayAbstractLocation.A::class.java).map {
                it.addr
            }
    }

    /*
    Pointer to the beginning of the elements of an array (note that at this point we know the pointed to array is non-empty,
    so A instead of ArrayAbstractLocation
     */
    data class ArrayElemStart(val v: Set<ArrayAbstractLocation.A>) : Pointer(), MaybeReadablePointer, MaybeWritablePointer {
        override val referencedLocations: Collection<AllocationSite>
            get() = this.v.map { it.addr }

        /*
          What on earth is happening here?!??!

          Well, as of solidity 6.1, if string is less than 31 (INCLUDING ZERO), then solidity will (incorrectly)
          read from the array start of the element segment... again, even if the array is empty. So solidity in fact
          generates an out of bounds write. Go figure.
         */
        private fun isSmallStringRead(pointerName: TACSymbol.Var, state: PointsToDomain) =
            v.all {
                it.addr.getElementSize()?.consistentWith(BigInteger.ONE) == true
            } && state.arrayState[pointerName]?.let { it as? ArrayStateAnalysis.Value.ElementPointer }?.let {
                it.index.ub == BigInteger.ZERO && it.index.lb == BigInteger.ZERO &&
                it.arrayPtr.any { basePtr ->
                    state.arrayState[basePtr]?.let {
                        it as? ArrayStateAnalysis.Value.ArrayBasePointer
                    }?.let {
                        it.logicalLength.lb == BigInteger.ZERO && it.logicalLength.ub <= 31.toBigInteger()
                    } == true
                }
            } == true

        override fun readableIfSafe(
            pointerName: TACSymbol.Var,
            state: PointsToDomain,
            where: LTACCmd,
            relaxedChecks: Boolean
        ): ReadablePointer? =
            if (state.indexProvablyWithinBounds(BigInteger.ZERO, pointerName) || isSmallStringRead(pointerName, state) || where.cmd.meta.containsKey(SighashBinder.SAFE_INSTRUMENTED_READ)) {
                ArrayElemPointer(this.v)
            } else if(relaxedChecks && v.any {
                it.addr.getElementSize() == ElementSize.Concrete(BigInteger.ONE)
                }) {
                ArrayElemPointer(this.v)
            } else {
                null
            }

        override fun writableIfSafe(
            pointerName: TACSymbol.Var,
            state: PointsToDomain,
            where: LTACCmd
        ): WritablePointer? =
            if (state.indexProvablyWithinBounds(BigInteger.ZERO, pointerName)) {
                ArrayElemPointer(this.v)
            } else {
                null
            }
    }

    /*
    An array to some arbitrary array element. As above, we know the pointed to array is non-empty
     */
    data class ArrayElemPointer(val v: Set<ArrayAbstractLocation.A>) : Pointer(), ReadablePointer, WritablePointer {
        override fun getReferencedType(h: AbstractHeap): HeapType {
            return this.v.map { aLoc ->
                h.arrayAction<HeapType>(aLoc, { HeapType.Int }, { it.elementType(h) ?: throw AnalysisFailureException("Attempted to access uninitialized elements from array $aLoc (${this.v})") })
            }.reduce(HeapType::join)
        }

        override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType = getReferencedType(h)

        override val referencedLocations: Collection<AllocationSite>
            get() = this.v.map { it.addr }
    }
}

/*
Initialization pointers. In general we do not allow adding to a pointer that is pointing mid-block. However, the solidity
compiler initializes memory by incrementing a pointer along the length of the allocated memory, so this tracks that. Further,
unlike general pointers, we always know exactly the abstract location being initialized.
 */
sealed class InitializationPointer : ReferenceValue(), WritablePointer {
    interface FoldOnFinishInit {
        val initAddress: InitAddress
    }

    sealed interface LengthFieldInit  : WritablePointer, FoldOnFinishInit {
        val mayAdded: Boolean
        val mustAdded: Boolean

        fun getElemTypes(h: AbstractHeap) : HeapType?

        override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType? {
            if(!mayAdded) {
                ptaInvariant(!mustAdded) {
                    "Inconsistent may/must added state $this"
                }
                return HeapType.Int
            }
            if(mustAdded) {
                return getElemTypes(h)
            }
            throw AnalysisFailureException("Cannot determine if init pointer references length or elements")
        }
    }

    data class ArrayInitPointer(val v: InitAddress, override val mayAdded: Boolean, override val mustAdded: Boolean) : InitializationPointer(), LengthFieldInit, FoldOnFinishInit {
        override val initAddr: AllocationAnalysis.AbstractLocation
            get() = v.addr

        override fun getElemTypes(h: AbstractHeap): HeapType? {
            return h.arrayAction(v, { throw AnalysisFailureException("Found byte array when we expected array pointer ")}, {
                it.elementType(h)
            })
        }

        override val initAddress: InitAddress
            get() = v
    }

    data class BlockInitPointer(val v: L, override val offset: BigInteger) : InitializationPointer(), BlockPointer, FieldAccess {
        override val initAddr: AllocationAnalysis.AbstractLocation
            get() = (v.addr as AllocationSite.Explicit).alloc

        override val blockAddr: Set<L>
            get() = setOf(v)

        override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType? {
            return h[v]!!.block.fieldTypes[offset, h]
        }
    }

    data class ByteInitPointer(val v: InitAddress, override val mayAdded: Boolean, override val mustAdded: Boolean) : InitializationPointer(), LengthFieldInit, FoldOnFinishInit {
        override val initAddr: AllocationAnalysis.AbstractLocation
            get() = v.addr

        override fun getElemTypes(h: AbstractHeap): HeapType {
            return h.arrayAction(v, { HeapType.Int }, {
                throw AnalysisFailureException("Found general array $it for $this where byte array was expected")
            })
        }

        override val initAddress: InitAddress
            get() = v
    }

    data class StaticArrayInitPointer(val address: L) : InitializationPointer(), BlockPointer {
        override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType? {
            return h.blockSpace[address]!!.block.fieldTypes.toMap(h).map { it.value }.foldFirstOrNull(HeapType::join)
        }

        override val blockAddr: Set<L>
            get() = setOf(address)

        override val initAddr: AllocationAnalysis.AbstractLocation
            get() = (address.addr as AllocationSite.Explicit).alloc
    }

    abstract val initAddr: AllocationAnalysis.AbstractLocation
}

private fun <R> AbstractHeap.arrayAction(v: InitAddress, byte: (ArrayBlock.ByteArray) -> R, eArray: (ArrayBlock.Array) -> R): R {
    return this[v]!!.block.let {
        when(it) {
            is ArrayBlock.Array -> eArray(it)
            is ArrayBlock.ByteArray -> byte(it)
        }
    }
}

/*
A pointer for scratch memory, i.e., non-persistent memory used by the solidity compiler
for hashing, call arrays, etc. (we currently model this very imprecisely)
 */
object ScratchPointer : ReferenceValue(), ReadablePointer, WritablePointer {
    override fun getReferencedTypeOrNull(h: AbstractHeap): HeapType = HeapType.Int

    override fun getReferencedType(h: AbstractHeap): HeapType = HeapType.Int

}

val emptyArrayPointer = Pointer.ArrayPointer(setOf(ArrayAbstractLocation.EMPTY_ARRAY))

/*
Wrapper around the type variable, could be an integer or an empty array
 */
data class UnkPointsTo(val tyVar: TypeVariable) : VPointsToValue(), HeapValue {
    override fun tryResolveInt(): PointsToValue =
            this.tyVar.ifResolved<PointsToValue>(INT, emptyArrayPointer) ?: this.tyVar.expectInt(INT)

    override fun tryResolvePointer(): PointsToValue =
            this.tyVar.ifResolved<PointsToValue>(INT, emptyArrayPointer) ?: this.tyVar.expectPointer(emptyArrayPointer)

    fun expectInt() = this.tyVar.expectInt(INT)

    fun expectPointer() = this.tyVar.expectPointer(emptyArrayPointer)

    override fun strictJoin(h: HeapValue): HeapValue {
        val res = this.tyVar.ifResolved(INT as HeapValue, emptyArrayPointer as HeapValue)
        if(res != null) {
            return res.strictJoin(h)
        }
        ptaInvariant(!this.tyVar.isResolved()) {
            "${this.tyVar} is resolved"
        }
        return when(h) {
            is Pointer.ArrayPointer -> this.expectPointer().strictJoin(h)
            is INT -> this.expectInt().strictJoin(h)
            is UnkPointsTo -> {
                h.tyVar.ifResolved<HeapValue>(emptyArrayPointer, INT)?.strictJoin(this) ?: run {
                    this.tyVar.unify(h.tyVar)
                    this
                }
            }
            is Pointer.BlockPointerBaseAmbiguous -> h.strictJoin(this) // this should be commutative
            else -> throw AnalysisFailureException("Cannot join type variable $this with heap value $h")
        }
    }

    override fun getType(h: AbstractHeap): HeapType =
            tyVar.ifResolved(HeapType.Int, HeapType.EmptyArray) ?: HeapType.TVar(this.tyVar)

    override fun toPointsToValue() = this

    override fun tryResolve(): VPointsToValue {
        return this.tyVar.ifResolved<VPointsToValue>(INT, emptyArrayPointer) ?: this
    }
}

typealias AbstractStore = TreapMap<TACSymbol.Var, VPointsToValue>

typealias AbstractStoreUpdate = ((AbstractStore) -> AbstractStore) -> Unit

data class PointsToGraph(val store: AbstractStore, val h: AbstractHeap) {
    fun updateVariable(v: TACSymbol.Var, f: () -> VPointsToValue): PointsToGraph =
        this.copy(store = store.plus(v to f()))

    fun updateVariable(v: TACSymbol.Var, f: (AbstractHeap) -> Pair<VPointsToValue, AbstractHeap>): PointsToGraph {
        val (newV, h) = f(this.h)
        return this.copy(store = store.plus(v to newV), h = h)
    }

    fun updateHeap(f: (AbstractHeap, AbstractStoreUpdate) -> AbstractHeap) : PointsToGraph {
        var newState = this.store
        val setter = { cb: (AbstractStore) -> AbstractStore ->
            newState = cb(newState)
        }
        val h = f(this.h, setter)
        return this.copy(h = h, store = newState)
    }

    fun mapVariable(op: TACSymbol.Var, f: (VPointsToValue) -> VPointsToValue): PointsToGraph {
        if(op !in this.store) {
            return this
        }
        val x = this.store[op]!!
        return this.updateVariable(op) { -> f(x.tryResolve()) }
    }

    fun isTupleVar(k: TACSymbol.Var) : TupleTypeResult? {
        return this.store[k]?.let {
            it as? BlockPointer
        }?.blockAddr?.monadicMap {
            h.isTupleSafeAddress(it)
        }?.monadicFold { t1, t2 ->
            if(t1 is TupleTypeResult.Empty) {
                t2
            } else if(t2 is TupleTypeResult.Empty) {
                t1
            } else {
                check(t1 is TupleTypeResult.TupleResult)
                check(t2 is TupleTypeResult.TupleResult)
                if(!t1.v.checkCompatibility(t2.v)) {
                    null
                } else {
                    TupleTypeResult.TupleResult(t1.v.join(t2.v))
                }
            }
        }
    }

    fun widen(
        other: PointsToGraph,
        thisContext: PointsToDomain,
        otherContext: PointsToDomain
    ) : PointsToGraph = PointsToGraph(
        store = store.join(
            other = other.store,
            thisPatern = this,
            otherParent = other,
            thisContext = thisContext,
            otherContext = otherContext
        ),
        h = h.widen(other.h)
    )

    fun join(
        other: PointsToGraph,
        thisContext: PointsToDomain,
        otherContext: PointsToDomain
    ): PointsToGraph = PointsToGraph(store.join(
        other = other.store,
        thisPatern = this,
        thisContext = thisContext,
        otherParent = other,
        otherContext = otherContext
    ), h = h.join(other.h))
}

private fun AbstractStore.join(
    other: AbstractStore,
    thisPatern: PointsToGraph,
    thisContext: PointsToDomain,
    otherParent: PointsToGraph,
    otherContext: PointsToDomain
) : AbstractStore {
    fun joinMismatchArray(k: TACSymbol.Var, v1: Pointer.ArrayElemStart, s1: PointsToDomain, v2: Pointer.ArrayElemPointer, s2: PointsToDomain) : VPointsToValue {
        val c2 = s2.arrayState
        val int = v1.v + v2.v
        return if(c2[k].let {
                it as? ArrayStateAnalysis.Value.ElementPointer
            }?.index?.takeIf { it.isConstant }?.c == BigInteger.ZERO) {
            Pointer.ArrayElemStart(int)
        } else if(s1.indexProvablyWithinBounds(BigInteger.ZERO, k)) {
            Pointer.ArrayElemPointer(int)
        } else {
            INT
        }
    }
    fun isMismatchedBlockPointerTypes(v: BlockPointer, otherV: BlockPointer): Boolean {
        if(v is Pointer.BlockPointerBase && otherV is Pointer.BlockPointerBaseAmbiguous) {
            return false
        }
        if(v is Pointer.BlockPointerBaseAmbiguous && otherV is Pointer.BlockPointerBase) {
            return false
        }
        return v.javaClass != otherV.javaClass
    }

    fun PointsToDomain.variableIsZero(k: TACSymbol.Var) = boundsAnalysis[k]?.let {
        it as? QualifiedInt
    }?.x?.takeIf(IntValue::isConstant)?.c == BigInteger.ZERO

    fun joinMismatchedBasePointer(
        k: TACSymbol.Var,
        basePointer: BasePointer<*>,
        other: VPointsToValue,
        otherDomain: PointsToDomain
    ) : VPointsToValue {
        require(other !is BasePointer<*>)
        return if(other is INT && otherDomain.variableIsZero(k)) {
            NullablePointer(basePointer)
        } else if(other is NullablePointer) {
            NullablePointer(
                wrapped = basePointer.toPointer().join(other.wrapped.toPointer()).let {
                    it as? BasePointer<*>
                } ?: return INT
            )
        } else {
            INT
        }
    }

    fun joinMismatchedNullable(
        k: TACSymbol.Var,
        nullablePointer: NullablePointer,
        other: VPointsToValue,
        otherDomain: PointsToDomain
    ) : VPointsToValue {
        return if(other is INT && otherDomain.variableIsZero(k)) {
            nullablePointer
        } else if(other is BasePointer<*>) {
            NullablePointer(
                wrapped = other.toPointer().join(nullablePointer.wrapped.toPointer()).let {
                    it as? BasePointer<*>
                } ?: return INT
            )
        } else {
            INT
        }
    }

    return this.merge(other) { k, thisValue, otherValue ->
        if (thisValue == null || otherValue == null) {
            // we want to join the non-null value with INT, but that irrevocably classifies it as an INT.  We need to
            // wait and see if we can join the value via a different key first.  Another pass after this one will take
            // care of the rest.
            thisValue
        } else if(thisValue is Pointer.ArrayElemStart && otherValue is Pointer.ArrayElemPointer) {
            joinMismatchArray(k, thisValue, thisContext, otherValue, otherContext)
        } else if(thisValue is Pointer.ArrayElemPointer && otherValue is Pointer.ArrayElemStart) {
            joinMismatchArray(k, otherValue, otherContext, thisValue, thisContext)
        } else if(thisValue is BlockPointer && otherValue is BlockPointer && (isMismatchedBlockPointerTypes(thisValue,otherValue) ||
                        (thisValue is FieldAccess && otherValue is FieldAccess && thisValue.offset != otherValue.offset)) &&
                thisValue is InitializationPointer == otherValue is InitializationPointer) {
            val t1 = otherParent.isTupleVar(k)
            val t2 = thisPatern.isTupleVar(k)
            if (t1 == null || t2 == null) {
                INT
            } else if (t1 is TupleTypeResult.TupleResult &&
                t2 is TupleTypeResult.TupleResult &&
                !t1.v.checkCompatibility(t2.v)
            ) {
                INT
            } else if (thisValue is InitializationPointer && thisValue.blockAddr != otherValue.blockAddr) {
                INT
            } else if (thisValue is InitializationPointer) {
                InitializationPointer.StaticArrayInitPointer(
                    address = thisValue.blockAddr.single()
                )
            } else {
                check(other !is InitializationPointer)
                Pointer.ConstSizeArrayElemPointer(
                    v = thisValue.blockAddr + otherValue.blockAddr
                )
            }
        } else if(thisValue is NullablePointer && otherValue !is NullablePointer) {
            joinMismatchedNullable(k, thisValue, otherValue, thisContext)
        } else if(otherValue is NullablePointer && thisValue !is NullablePointer) {
            joinMismatchedNullable(k, otherValue, thisValue, otherContext)
        } else if(thisValue is BasePointer<*> && (otherValue !is BasePointer<*> && otherValue !is UnkPointsTo)) {
            joinMismatchedBasePointer(k, thisValue, otherValue, otherContext)
        } else if(otherValue is BasePointer<*> && (thisValue !is BasePointer<*> && thisValue !is UnkPointsTo)) {
            joinMismatchedBasePointer(k, otherValue, thisValue, thisContext)
        } else {
            thisValue.join(otherValue)
        }
    }.merge(other) { _, v, otherV ->
        // Join the values that existed only on one side with INT.  See above for why we do this in a separate pass.
        when {
            v == null -> otherV!!.join(INT)
            otherV == null -> v.join(INT)
            else -> v
        }
    }
}

/*
The arithmetic semantics. So the numeric analysis and pointer analysis agree on the types of local variables, we
abstract the rules for addition, subtraction, etc. into a generic semantic function defined by ArithmeticSemantics.
 */
private val pArithSemantics = ArithmeticSemantics<VPointsToValue, VPointsToValue>(
        lazyHandler = { it.tryResolveInt() },
        isPointerP = { value -> value !is INT && (value !is UnkPointsTo || (value.tyVar.isResolved() && !value.tyVar.resolvedInt())) },
        binSemantics = { _, _ -> INT },
        pointerArithResult = INT,
        vecSemantics = INT
)

class PointerSemantics(
    override val typeVariableManager: TypeVariableManager, scratchSite: Set<CmdPointer>,
    allocSites: Map<CmdPointer, AllocationAnalysis.AbstractLocation>,
    val numericAnalysis: NumericAnalysis,
    graph: TACCommandGraph,
    val relaxedAddition: Boolean,
    val initialFreePointerValue: BigInteger?,
    val allowLengthUpdates: Boolean
) : MemoryStepper<PointsToDomain, PointsToGraph>(scratchSite, allocSites), IPointerInformation, SymInterpreter<PointsToGraph, VPointsToValue> {
    val needBoundProof = mutableSetOf<CmdPointer>()

    fun BigInteger.asSpillLocation(): SpillLocation? =
        initialFreePointerValue?.let { fp ->
            takeIf { 0x80.toBigInteger() <= it && it < fp && it.mod(EVM_WORD_SIZE) == BigInteger.ZERO }
        }?.let(::SpillLocation)

    fun IntValue.asSpillLocation(): SpillLocation? =
        if (isConstant) {
           c.asSpillLocation()
        } else {
            null
        }

    fun TACSymbol.asSpillLocation(pState: PointsToDomain, cmd: LTACCmd): SpillLocation? =
        numericAnalysis.interpAsConstant(pState, cmd, this)?.let {
            it.asSpillLocation()
        }

    private val concreteAllocationManager = ConcreteAllocationManager()

    /*
        For writing into memory or storage we don't track, i.e., tacS
     */
    override fun handleUninterpWrite(value: TACSymbol, target: PointsToGraph, pState: PointsToDomain,
                                     ltacCmd: LTACCmd) {
        val isWriteInt = target.interp(value, ltacCmd).tryResolveInt().let {
            it is INT
        }
        if (!isWriteInt) {
            logger.warn { "attempted to write a non-integer to the heap at $ltacCmd" }
            logger.warn { "Interpreting $value gives ${target.interp(value, ltacCmd)}" }
            throw AnalysisFailureException("Unsafe write to tacs $value")
        }
    }

    /*
      Handles the codecopy trick. Sets the array element types to be int if not set
     */
    override fun handleByteCopy(dstOffset: TACSymbol, length: TACSymbol, target: PointsToGraph, pState: PointsToDomain,
                                ltacCmd: LTACCmd): PointsToGraph {
        return checkByteCopy(
            dstOffset = dstOffset,
            length = length,
            target = target,
            pState = pState,
            ltacCmd = ltacCmd,
            heapAction = PointsToGraph::updateHeap,
            cont = { st, _ -> st }
        )
    }

    private fun isScratchCopy(
        offset: TACSymbol,
        length: TACSymbol,
        pState: PointsToDomain,
        ltacCmd: LTACCmd
    ) : Boolean {
        return numericAnalysis.interpAsConstant(pState, ltacCmd, offset)?.let { offs ->
            numericAnalysis.interpAsConstant(pState, ltacCmd, length)?.let { len ->
                (offs + len <= 64.toBigInteger() || len == BigInteger.ZERO)
            }
        } == true
    }

    private fun <R> checkByteCopy(
        dstOffset: TACSymbol,
        length: TACSymbol,
        target: PointsToGraph,
        pState: PointsToDomain,
        ltacCmd: LTACCmd,
        heapAction: PointsToGraph.((AbstractHeap, AbstractStoreUpdate) -> AbstractHeap) -> PointsToGraph,
        cont: (PointsToGraph, TreapSet<TACSymbol.Var>) -> R
    ) : R {
        fun PointsToGraph.toResult() = cont(this, treapSetOf())
        fun PointsToGraph.toResult(vararg toKill: TACSymbol.Var) = cont(this, treapSetOf(*toKill))
        fun handleConstantModeSwitchAndAlloc() =
            // make sure we're in constant offset mode and record the write
            target.heapAction { h, _ ->
                ptaInvariant(h.concreteSpace.isNotEmpty()) {
                    "Expected to already be in constant space mode"
                }
                if(numericAnalysis.interpSymbol(where = ltacCmd, st = pState.boundsAnalysis, length).expectInt().x.mustNotOverlap(IntValue.Constant(BigInteger.ZERO))) {
                    numericAnalysis.interpSymbol(where = ltacCmd, st = pState.boundsAnalysis, dstOffset).expectInt().let {
                        /**
                         * If this copy output writes memory, forget the value abstraction which overlaps with it.
                         */
                        h.copy(
                            concreteSpace = h.concreteSpace + (it.x to null)
                        )
                    }
                } else {
                    h
                }
            }.toResult(TACKeyword.MEM0.toVar(), TACKeyword.MEM32.toVar())
        return target.interp(dstOffset, ltacCmd).tryResolvePointer().let { av1 ->
            when (av1) {
                is INT -> {
                    if(isScratchCopy(dstOffset, length, pState, ltacCmd)) {
                        val offs = numericAnalysis.interpAsConstant(pState, ltacCmd, dstOffset)!!
                        val len = numericAnalysis.interpAsConstant(pState, ltacCmd, length)!!
                        val start = offs.toInt()
                        val end = (offs + len).toInt() - 1 // get the last byte written
                        var toKill = treapSetOf<TACSymbol.Var>()
                        if (start < end) {
                            if (start in 0..31 || end in 0..31) {
                                toKill += TACKeyword.MEM0.toVar()
                            }
                            if (start in 32..63 || end in 32..63) {
                                toKill += TACKeyword.MEM32.toVar()
                            }
                        }
                        // allow copies to static scratch
                        cont(target, toKill)
                    } else if(numericAnalysis.interpAsUnambiguousConstant(pState, value = length, ltacCmd = ltacCmd) == BigInteger.ZERO) {
                        target.toResult()
                    } else {
                        handleConstantModeSwitchAndAlloc()
                    }
                }
                is ScratchPointer -> target.toResult()
                is InitializationPointer.BlockInitPointer -> {
                    if(av1.offset != BigInteger.ZERO) {
                        throw AnalysisFailureException("Copying into middle of initializing constant block")
                    }
                    val const = numericAnalysis.interpAsConstant(pState, ltacCmd, length) ?: throw AnalysisFailureException("Non-constant copy into initializing block")
                    val initSort = av1.initAddr.sort
                    ptaInvariant(initSort is AllocationAnalysis.Alloc.ConstBlock) {
                        "Initializing a block that is not of constant sort? ${av1.v}"
                    }
                    if(const != initSort.sz) {
                        throw AnalysisFailureException("Copying $const bytes into block of size ${initSort.sz}")
                    }
                    target.heapAction { h, _ ->
                        var it = BigInteger.ZERO
                        var hIt = h
                        while(it < const) {
                            hIt = updateField(
                                hIt,
                                v = av1.v,
                                v1 = it,
                                heapValue = INT,
                                strong = false,
                                killMaybeArray = true
                            )
                            it += 32.toBigInteger()
                        }
                        hIt
                    }.toResult()
                }
                // maybe have these detected by the initialization analysis
                is InitializationPointer.ByteInitPointer -> {
                    /* i.e., may not added */
                    if(!av1.mustAdded) {
                        throw AnalysisFailureException("Cannot prove that we are not copying to length of array pointer $av1 ($dstOffset)")
                    }
                    target.toResult()
                } // if not summary, welp
                is InitializationPointer.ArrayInitPointer -> {
                    if(!av1.mustAdded) {
                        throw AnalysisFailureException("Cannot prove that we are not copying to the length of array pointer $av1 ($dstOffset)")
                    }
                    target.heapAction { h, _ ->
                        h.updateObject(av1.v) { o: ArrayBlock.Array ->
                            when (o.elementType(h)) {
                                HeapType.Int -> {
                                    ptaInvariant(o.elem == INT) {
                                        "Expected our type information said we should have integer elements, but found ${o.elem}"
                                    }
                                    o.copy(mustBeEmpty = false)
                                }
                                null -> {
                                    ptaInvariant(o.elem == null) {
                                        "Expected to find uninitialized element (we have no element type) but found ${o.elem}"
                                    }
                                    o.copy(
                                        elem = INT,
                                        mustBeEmpty = false
                                    )
                                }
                                else -> {
                                    logger.warn { "Attempted to byte copy into a non-int array $av1 ==> $o" }
                                    throw AnalysisFailureException("Unsafe write to array")
                                }
                            }
                        }
                    }.toResult()
                }
                is FieldAccess -> {
                    val len = numericAnalysis.interpAsUnambiguousConstant(pState, ltacCmd = ltacCmd, value = length) ?: throw AnalysisFailureException(
                        "Attemping to copy non-constant length of $length to struct fields"
                    )
                    if(len.mod(EVM_WORD_SIZE) != BigInteger.ZERO) {
                        throw AnalysisFailureException("Copying non-word aligned segment of length $len into struct")
                    }
                    val sizeBound = av1.offset + len
                    for(l in av1.blockAddr) {
                        val blk = target.h[l]?.block ?: throw AnalysisFailureException("Unbound address $l in heap")
                        if(blk.sz < sizeBound) {
                            throw AnalysisFailureException("Struct address $l is of size ${blk.sz}, need at least $sizeBound for valid long copy")
                        }
                        var fldIt = av1.offset
                        while(fldIt < sizeBound) {
                            if(blk.fieldTypes[fldIt, target.h]?.checkCompatibility(HeapType.Int) != true) {
                                throw AnalysisFailureException("Struct field $fldIt in $l was not an int, can't copy")
                            }
                            fldIt += EVM_WORD_SIZE
                        }
                    }
                    target.toResult()
                }
                else -> {
                    logger.warn { "Byte copy to $av1 from value $dstOffset @ ${ltacCmd.ptr}, this is likely unsafe, giving up" }
                    throw AnalysisFailureException("Unsafe byte copy")
                }
            }
        }
    }

    /**
     * Variables in [killValue] are known to have their values overwritten, and all information about them should be killed.
     * In contrast, the variables in [killArrayLengthFacts] are array pointers and array elem start pointers for which
     * array length facts should be killed (but not the pointer values themselves).
     *
     * Effectively, this clears relational information about the lengths of the arrays whose length has been updated.
     */
    data class WriteSideEffect(
        val killValue: TreapSet<TACSymbol.Var>,
        val killArrayLengthFacts: TreapSet<TACSymbol.Var>
    ) {
        fun isEmpty(): Boolean {
            return this === empty || (killValue.isEmpty() && killArrayLengthFacts.isEmpty())
        }

        companion object {
            fun killValues(s: TreapSet<TACSymbol.Var>) = WriteSideEffect(s, treapSetOf())

            val empty = WriteSideEffect(treapSetOf(), treapSetOf())
        }
    }

    fun getKillSideEffects(ltacCmd: LTACCmd, pState: PointsToDomain) : WriteSideEffect {
        return when(ltacCmd.cmd) {
            is TACCmd.Simple.ByteLongCopy -> {
                checkByteCopy(
                    dstOffset = ltacCmd.cmd.dstOffset,
                    length = ltacCmd.cmd.length,
                    pState = pState,
                    target = pState.pointsToState,
                    ltacCmd = ltacCmd,
                    heapAction = {_ ->
                        this
                    },
                    cont = { _, vs -> WriteSideEffect(vs, treapSetOf()) }
                )
            }
            is TACCmd.Simple.CallCore -> {
                checkByteCopy(
                    dstOffset = ltacCmd.cmd.outOffset,
                    ltacCmd = ltacCmd,
                    heapAction = { _ -> this },
                    target = pState.pointsToState,
                    pState = pState,
                    length = ltacCmd.cmd.outSize,
                    cont = { _, vs -> WriteSideEffect(vs, treapSetOf()) }
                )
            }
            is TACCmd.Simple.AssigningCmd.ByteStore -> {
                val loc = ltacCmd.cmd.loc as? TACSymbol.Var ?: return WriteSideEffect.empty
                val base = pState.pointsToState.store[loc]?.let {
                    it as? Pointer.ArrayPointer
                }?.v ?: return WriteSideEffect.empty
                // kill any facts
                val killValues = treapSetBuilderOf<TACSymbol.Var>()
                val killRelational = treapSetBuilderOf<TACSymbol.Var>()
                for((k, p) in pState.pointsToState.store) {
                    when(p) {
                        is Pointer.ArrayPointer -> {
                            if(p.v.containsAny(base)) {
                                killRelational.add(k)
                            }

                        }
                        is Pointer.ArrayElemStart -> {
                            if(p.v.containsAny(base)) {
                                killRelational.add(k)
                            }
                        }
                        // we don't know if element pointers are still element pointers anymore, kill that information
                        is Pointer.ArrayElemPointer -> {
                            if(base.containsAny(p.v)) {
                                killValues.add(k)
                            }
                        }
                        else -> continue
                    }
                }
                WriteSideEffect(killValue = killValues.build(), killArrayLengthFacts = killRelational.build())
            }
            else -> WriteSideEffect.empty
        }
    }

    override fun stepOther(ltacCmd: LTACCmd, kill: PointsToGraph, pState: PointsToDomain): PointsToGraph {
        if(ltacCmd.cmd is TACCmd.Simple.CallCore) {
            return checkByteCopy(
                dstOffset = ltacCmd.cmd.outOffset,
                length = ltacCmd.cmd.outSize,
                pState = pState,
                target = kill,
                ltacCmd = ltacCmd,
                cont = { st, _ -> st},
                heapAction = PointsToGraph::updateHeap
            ).let { stepped ->
                if(stepped.h.concreteSpace.isEmpty()) {
                    return@let stepped
                }
                /**
                 * Heuristically garbage collect the input buffer for a call if it does not overlap
                 * with the output space. This follows the usual vyper pattern of using some constant segment of memory as "scratch"
                 * and then reusing that segment later for totally unrelated purposes. To avoid polluting unification and state,
                 * artificially kill these input cells.
                 */
                val toGc = numericAnalysis.interpSymbol(where = ltacCmd, st = pState.boundsAnalysis, sym = ltacCmd.cmd.inOffset).expectInt().x
                val outputLocation = numericAnalysis.interpSymbol(where = ltacCmd, st = pState.boundsAnalysis, sym = ltacCmd.cmd.outOffset).expectInt().x
                val nonTrivialOutput = numericAnalysis.interpSymbol(where = ltacCmd, st = pState.boundsAnalysis, sym = ltacCmd.cmd.outSize).expectInt().x.takeIf {
                    it.isConstant
                }?.c != BigInteger.ZERO
                stepped.h.concreteSpace.findIntersection(toGc).singleOrNull()?.let withIntersection@{ callInputCell ->
                    if(stepped.h.concreteSpace.findIntersection(outputLocation).singleOrNull() == callInputCell && nonTrivialOutput) {
                        return@withIntersection stepped
                    }
                    stepped.updateHeap { heap, _ ->
                        heap.updateConcrete(ltacCmd) { concrete ->
                            concrete.delete(callInputCell)
                        }
                    }
                } ?: stepped
            }
        }
        return kill
    }

    /*
    One of the "big" rules.
     */
    override fun readMemory(lhs: TACSymbol.Var, loc: TACSymbol, target: PointsToGraph, pState: PointsToDomain,
                            ltacCmd: LTACCmd): PointsToGraph =
            target.updateVariable(lhs) { ->
                val heap = pState.pointsToState.h
                target.interp(loc, ltacCmd).tryResolvePointer().let { base_ ->
                    if (base_ is INT) {
                        loc.asSpillLocation(pState, ltacCmd)?.let {
                            return@updateVariable heap.spillSpace[it]?.toPointsToValue()
                                ?: throw AnalysisFailureException("Read of $it before writing")
                        }
                    }
                    /*
                        First things first, is the base value even valid? Invalid values include:
                        + Scratch pointers
                        + integers
                        + intialization pointers
                        + ElemStart pointers

                        The remaining types are acceptable
                     */
                    val base = if (base_ !is MaybeReadablePointer) {
                        if(isSafeRead(loc, base_, pState, ltacCmd)) {
                            // return INT for safe reads and well as reads from a constant offset
                            return@let INT
                        } else {
                            findReadSafetyProof(loc, pState) ?: run {
                                logger.warn { "Unsafe write of read from $base_ ($loc) to lhs $lhs" }
                                throw AnalysisFailureException("Unsafe read of from $loc")
                            }
                        }
                    } else {
                        base_
                    }
                    val readableBase = if(loc is TACSymbol.Const) {
                        ptaInvariant(loc.value == 0x60.toBigInteger()) {
                            "Have a valid pointer $base from a constant value $loc?"
                        }
                        emptyArrayPointer
                    } else {
                        ptaInvariant(loc is TACSymbol.Var) {
                            "$loc is not a TACSymbol.Var?"
                        }
                        base.readableIfSafe(loc, pState, where = ltacCmd, relaxedAddition)
                    }
                    if(readableBase == null) {
                        logger.warn {
                            "Could not prove that $base ($loc) was safe to read at $ltacCmd"
                        }
                        throw AnalysisFailureException("Unsafe read of from $loc")
                    }
                    /*
                      Now that we know, let's construct the value we find in the heap
                     */
                    val expectedType = readableBase.getReferencedType(heap)
                    val readValue : HeapValue = when (readableBase) {
                        /*
                        If this is a statically sized array, all elements must have the same type, so flatMap all pointed
                        to structs, collect all field values, and the join
                         */
                        is Pointer.ConstSizeArrayElemPointer -> {
                            readableBase.v.flatMap { addr ->
                                check(addr in heap)
                                val obj = heap[addr]!!
                                obj.block.m.values.map { it }
                            }.reduce(HeapValue::strictJoin)
                        }
                        is Pointer.ArrayElemPointer -> {
                            val arrayAddresses = readableBase.v
                            check(arrayAddresses.isNotEmpty())
                            val (byteBlocks, arrayBlocks) = arrayAddresses.map { addr ->
                                check(addr in heap)
                                heap[addr]!!.block
                            }.partition { blk ->
                                blk is ArrayBlock.ByteArray
                            }
                            if (byteBlocks.isNotEmpty()) {
                                ptaInvariant(arrayBlocks.isEmpty()) {
                                    "Should not have pointer $readableBase ($loc) with both byte and array pointers"
                                }
                                INT
                            } else {
                                ptaInvariant(arrayBlocks.isNotEmpty()) {
                                    "Found empty pointer $readableBase ($loc)"
                                }
                                check(byteBlocks.isEmpty()) {
                                    "Should not have pointer $readableBase ($loc) that contains both byte and array pointers"
                                }
                                arrayBlocks.map {
                                    (it as ArrayBlock.Array).elem
                                            ?: throw AnalysisFailureException("Attempt to read from uninitialized array block $it")
                                }.reduce(HeapValue::strictJoin)
                            }
                        }
                        is FieldAccess -> {
                            readableBase.readField(heap)
                        }
                        is Pointer.ArrayPointer -> INT
                        is ScratchPointer -> INT
                        else -> error(
                                "This should be impossible, attempt to read through $readableBase (from $loc) at $ltacCmd")
                    }
                    ptaInvariant(readValue.getType(heap).checkCompatibility(expectedType)) {
                        "The value yielded from the read of $readableBase ($loc), i.e, $readValue did not match the expected type: $expectedType"
                    }
                    readValue.toPointsToValue()
                }
            }

    private fun findReadSafetyProof(loc: TACSymbol, pState: PointsToDomain) : MaybeReadablePointer? {
        return (loc as? TACSymbol.Var)?.let {
            findSafetyProoffor(it, pState)
        }
    }

    private fun findWriteSafetyProof(loc: TACSymbol, pState: PointsToDomain): MaybeWritablePointer? {
        return findSafetyProoffor(loc as? TACSymbol.Var ?: return null, pState)
    }

    private inline fun <reified T> findSafetyProoffor(loc: TACSymbol.Var, pState: PointsToDomain) : T? {
        return (pState.invariants matches {
            loc `=` v("other") {
                it is LVar.PVar && pState.pointsToState.store[it.v] is T
            }
        }).singleOrNull()?.symbols?.get("other")?.let {
            pState.pointsToState.store[(it as LVar.PVar).v] as T
        }
    }

    private fun isSafeRead(loc: TACSymbol, base: PointsToValue, pState: PointsToDomain, ltacCmd: LTACCmd) =
        (loc is TACSymbol.Var && isStartReadForStringCopy(base, loc, pState)) ||
                (base is InitializationPointer.ByteInitPointer && base.v.addr.sort is AllocationAnalysis.Alloc.PackedByteArray) ||
                (base is InitializationPointer.ArrayInitPointer && base.v.addr.sort is AllocationAnalysis.Alloc.DynamicBlock) ||
                alwaysInConstantOffsetMode ||
                numericAnalysis.interpSymbol(where = ltacCmd, sym = loc, st = pState.boundsAnalysis).tryResolveAsInt().let {
                    it is QualifiedInt && pState.pointsToState.h.concreteSpace.mayIntersect(it.x)
                }

    private fun isStartReadForStringCopy(base: PointsToValue, loc: TACSymbol.Var, pState: PointsToDomain): Boolean {
        return base is Pointer.ArrayElemStart && base.v.all {
            getSizeOfArrayAddress(it) == BigInteger.ONE
        } && arrayStateAnalysis.isSmallStringLength(loc, pState.arrayState)
    }

    override fun writeSingleMemory(loc: TACSymbol, value: TACSymbol, target: PointsToGraph, pState: PointsToDomain,
                                   ltacCmd: LTACCmd): PointsToGraph {
        return target.interp(loc, ltacCmd).tryResolvePointer().let { base ->
            target.interp(value, ltacCmd).tryResolveInt().let { toWrite ->
                if(base !is MaybeWritablePointer) {
                    logger.warn {
                        "Unsafe write byte write location $loc"
                    }
                    throw AnalysisFailureException("Unsafe write to $loc")
                }
                val writableBase = base.writableIfSafe(loc as TACSymbol.Var, pState, where = ltacCmd) ?: run {
                    logger.warn {
                        "Could not prove that $loc was safe to write"
                    }
                    throw AnalysisFailureException("Unsafe byte write to $loc")
                }
                if(writableBase !is ScratchPointer && writableBase !is InitializationPointer.ByteInitPointer &&
                    (writableBase !is Pointer.ArrayElemPointer || !writableBase.getReferencedTypeOrNull(target.h).equals(HeapType.Int))) {
                    logger.warn {
                        "Unsafe byte write to location $writableBase ($loc) of value ($toWrite) ($value)"
                    }
                    throw AnalysisFailureException("Unsafe byte write to location $loc")
                }
                if(writableBase is InitializationPointer.ByteInitPointer && !writableBase.mustAdded) {
                    logger.warn {
                        "Could not prove that the initialization pointer $writableBase did not point to length"
                    }
                    throw AnalysisFailureException("Potentially unsafe write to length for $writableBase ($loc)")
                }
                if(toWrite != INT) {
                    logger.warn {
                        "Unsafe value $toWrite ($value) being written to $writableBase ($loc)"
                    }
                    throw AnalysisFailureException("Non-integer value written with byte write")
                }
                target
            }
        }

    }

    override fun writeMemory(loc: TACSymbol, value: TACSymbol, target: PointsToGraph, pState: PointsToDomain,
                             ltacCmd: LTACCmd): PointsToGraph {
        return target.updateHeap { heap, stUpdate ->
            target.interp(loc, ltacCmd).tryResolvePointer().let { base ->
                target.interp(value, ltacCmd).let { toWrite ->
                    if(base is INT) {

                        loc.asSpillLocation(pState, ltacCmd)?.let { spillLoc ->
                            if (toWrite is HeapValue) {
                                return@updateHeap heap.copy(
                                    // This is a strong update since we have a concrete address
                                    spillSpace = heap.spillSpace + (spillLoc to toWrite)
                                )
                            } else {
                                throw AnalysisFailureException("Writing non-HeapValue $toWrite to $spillLoc")
                            }
                        }

                        if (target.h.ignoreNextZeroWrite != null && numericAnalysis.interpAsConstant(
                                pState,
                                ltacCmd,
                                value
                            ) == BigInteger.ZERO
                        ) {
                            val isArrayEndWrite =
                                numericAnalysis.interpSymbol(where = ltacCmd, sym = loc, st = pState.boundsAnalysis)
                                    .expectInt().qual.any {
                                    it is IntQualifier.EndOfArraySegment && (pState.pointsToState.store[it.arrayVar] as? InitializationPointer.LengthFieldInit)?.let {
                                        it.initAddress.addr == target.h.ignoreNextZeroWrite
                                    } == true
                                }
                            val isBlockInitWrite = pState.invariants matchesAny {
                                loc.lift() `=` v("base") {
                                    it is LVar.PVar && target.store[it.v]?.let {
                                        it as? InitializationPointer.BlockInitPointer
                                    }?.takeIf { p -> p.offset == BigInteger.ZERO }?.let {
                                        it.initAddr == target.h.ignoreNextZeroWrite
                                    } == true
                                } + k("offs") {
                                    target.h.ignoreNextZeroWrite.let {
                                        it.sort as? AllocationAnalysis.Alloc.ConstBlock
                                    }?.sz == it
                                }
                            } != null
                            if (isArrayEndWrite || isBlockInitWrite) {
                                return@updateHeap heap.copy(
                                    ignoreNextZeroWrite = null
                                )
                            }
                        }
                        return@updateHeap if(heap.concreteSpace.isNotEmpty()) {
                            heap.updateConcrete(ltacCmd) { space ->
                                val intervalApprox = (numericAnalysis.interpSymbol(where = ltacCmd, st = pState.boundsAnalysis, sym = value).tryResolveAsInt() as? QualifiedInt)?.x
                                val writeLoc = numericAnalysis.interpSymbol(where = ltacCmd, st = pState.boundsAnalysis, sym = loc).expectInt().x
                                space + (writeLoc to intervalApprox)
                            }
                        } else {
                            heap
                        }
                    }
                    if (base !is ScratchPointer && heap.concreteSpace.isNotEmpty()) {
                        logger.warn {
                            "Invalid write to base pointer $base ($loc) at $ltacCmd while in constant offset mode"
                        }
                        throw AnalysisFailureException("Write to non-constant base pointer while in constant mode: $base ($loc)")
                    }
                    if(base is Pointer.ArrayPointer) {
                        // no questions asked, someone else says this is fine
                        if(TACMeta.VALIDATED_LENGTH_UPDATE in ltacCmd.cmd.meta) {
                            return@updateHeap heap
                        }
                        if(allowLengthUpdates) {
                            needBoundProof.add(ltacCmd.ptr)
                            return@updateHeap heap
                        }
                    }
                    val baseWithSaturation = if(base !is MaybeWritablePointer) {
                        findWriteSafetyProof(loc, pState) ?: run {
                            logger.warn {
                                "Definitely invalid write to base pointer $base ($loc) at $ltacCmd"
                            }
                            throw AnalysisFailureException("Unsafe write to invalid base pointer $base ($loc)")
                        }
                    } else {
                        base
                    }
                    val writableBase = baseWithSaturation.writableIfSafe(loc as TACSymbol.Var, pState, where = ltacCmd)
                    if(writableBase == null) {
                        logger.warn {
                            "$baseWithSaturation ($loc) could not be proven to be a safe write at $ltacCmd"
                        }
                        throw AnalysisFailureException("Unsafe write to invalid base pointer $baseWithSaturation ($loc)")
                    }
                    if(toWrite !is HeapValue) {
                        logger.warn {
                            "Unsafe write of non-heap value $toWrite ($value) to $writableBase ($loc)"
                        }
                        throw AnalysisFailureException("Unsafe write of $value to $loc")
                    }
                    val writeType = toWrite.getType(heap)
                    val expectedType = writableBase.getReferencedTypeOrNull(heap)
                    if(expectedType != null && !writeType.checkCompatibility(expectedType)) {
                        throw AnalysisFailureException("Write of incompatible type $writeType (from $toWrite @ $value) to cell $writableBase @ $loc with expected type $expectedType")
                    }
                    val newHeap = when (val upcast = writableBase as PointsToValue) {
                        is InitializationPointer -> {
                            when(upcast) {
                                is InitializationPointer.ArrayInitPointer -> {
                                    if(!upcast.mayAdded) {
                                        val written = toWrite.toPointsToValue().tryResolveInt()
                                        if(/*!upcast.mayAdded && */value is TACSymbol.Var) {
                                            stUpdate { st ->
                                                if(value !in st) {
                                                    st + (value to INT)
                                                } else {
                                                    st
                                                }
                                            }
                                        }
                                        ptaInvariant(written is INT) {
                                            "We tried to write a value $written to the length field of an array, but we needed an int. This should be caught by the type checker"
                                        }
                                        if(numericAnalysis.interpAsUnambiguousConstant(pState, ltacCmd, value) == BigInteger.ZERO) {
                                            heap.plus(upcast.v, heap.arrayAction(upcast.v, { it }, {
                                                it.copy(mustBeEmpty = true)
                                            }))
                                        } else {
                                            heap
                                        }
                                    } else {
                                        updateArrayField(heap, upcast.v, toWrite)
                                    }
                                }
                                is InitializationPointer.ByteInitPointer -> {
                                    val written = toWrite.toPointsToValue().tryResolveInt()
                                    if(!upcast.mayAdded && value is TACSymbol.Var) {
                                        stUpdate { st ->
                                            if(value !in st) {
                                                st + (value to INT)
                                            } else {
                                                st
                                            }
                                        }
                                    }
                                    if(written !is INT) {
                                        throw AnalysisFailureException("Write of unsafe value $written to the byte pointer ${upcast.v}")
                                    } else {
                                        heap
                                    }
                                }
                                is InitializationPointer.BlockInitPointer -> {
                                    val mustBeZero = when(value) {
                                        is TACSymbol.Const -> value.value == BigInteger.ZERO
                                        is TACSymbol.Var -> pState.boundsAnalysis[value]?.let {
                                            it as? QualifiedInt
                                        }?.let {
                                            it.x.isConstant && it.x.c == BigInteger.ZERO
                                        } ?: false
                                    }
                                    updateField(
                                        heap,
                                        upcast.v,
                                        upcast.offset,
                                        toWrite,
                                        strong = true,
                                        killMaybeArray = upcast.offset != BigInteger.ZERO || !mustBeZero
                                    )
                                }
                                is InitializationPointer.StaticArrayInitPointer -> {
                                    val sz = (upcast.initAddr.sort as AllocationAnalysis.Alloc.ConstBlock).sz
                                    var it = BigInteger.ZERO
                                    var curr = heap
                                    while(it < sz) {
                                        curr = updateField(
                                            curr,
                                            upcast.address,
                                            it,
                                            toWrite,
                                            strong = false,
                                            killMaybeArray = true
                                        )
                                        it += 32.toBigInteger()
                                    }
                                    curr
                                }
                            }
                        }
                        is Pointer.BlockPointerBaseAmbiguous -> {
                            upcast.expectBlock()
                            upcast.updateField(heap, toWrite, writeType)
                        }
                        is FieldAccess -> {
                            upcast.updateField(heap, toWrite, writeType)
                        }
                        is Pointer.ConstSizeArrayElemPointer -> {
                            upcast.v.fold(heap) { Curr, p ->
                                check(p in Curr)
                                val obj = Curr[p]!!
                                obj.block.m.keys.fold(Curr) { Curr_, ind ->
                                    updateField(
                                        Curr_,
                                        p,
                                        ind,
                                        toWrite,
                                        strong = false,
                                        killMaybeArray = numericAnalysis.interpAsConstant(pState, ltacCmd, value) != BigInteger.ZERO
                                    )
                                }
                            }
                        }
                        is Pointer.ArrayElemPointer -> {
                            upcast.v.fold(heap) { Curr, p ->
                                updateArrayField(Curr, p, toWrite)
                            }
                        }
                        ScratchPointer -> {
                            ptaInvariant(toWrite.toPointsToValue().tryResolveInt() is INT) {
                                "expected to write int to scratch pointer, instead found ${toWrite.toPointsToValue().tryResolveInt()}"
                            }
                            heap
                        }
                        INT -> throw AnalysisFailureException("Invariant violated, impossible case? $upcast")
                        else -> {
                            @Suppress("KotlinConstantConditions")
                            check(
                                upcast is Pointer.BlockPointerBase || upcast is Pointer.BlockPointerField || upcast
                                    is Pointer.ArrayPointer || upcast is Pointer.ArrayElemStart
                            ) { "Impossible case $upcast" }
                            throw AnalysisFailureException("Invariant violated, impossible case? $upcast")
                        }
                    }
                    newHeap
                }
            }
        }
                             }

    override fun assignUninterpExpr(lhs: TACSymbol.Var, rhs: Set<TACSymbol>, target: PointsToGraph,
                                    pstate: PointsToDomain, where: LTACCmd): PointsToGraph =
            target.updateVariable(lhs) { ->
                rhs.map { pstate.pointsToState.interp(it, where) }.let { x ->
                    pArithSemantics(*x.toTypedArray())
                }
            }

    override fun stepAnnotation(
        kill: PointsToGraph,
        pState: PointsToDomain,
        narrow: LTACCmdView<TACCmd.Simple.AnnotationCmd>,
    ): PointsToGraph = if (narrow.cmd.annot.k == MARK_ZERO_WRITE) {
        val top = kill.h.allocStack.lastOrNull()
            ?: throw AnalysisFailureException("Expected to mark top of initialization as expecting zero, but nothing is initializing $narrow")
        if (kill.h.ignoreNextZeroWrite != null) {
            throw AnalysisFailureException("By convention, only one initialization address can expect a successor write")
        }
        kill.copy(
            h = kill.h.copy(
                ignoreNextZeroWrite = top
            )
        )
    } else {
        kill
    }

    private fun arithBinOp(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol,
                           target: PointsToGraph,
                           ltacCmd: LTACCmd): PointsToGraph =
            target.updateVariable(c) { ->
                target.interp(op1, ltacCmd).let { av1 ->
                    target.interp(op2, ltacCmd).let { av2 ->
                        pArithSemantics(av1, av2)
                    }
                }
            }

    override fun assignShiftLeft(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: PointsToGraph,
                                 pstate: PointsToDomain, where: LTACCmd): PointsToGraph =
            arithBinOp(c, op1, op2, target, where)

    override fun assignOr(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: PointsToGraph,
                          pstate: PointsToDomain, where: LTACCmd): PointsToGraph =
            arithBinOp(c, op1, op2, target, where)

    override fun assignAnd(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: PointsToGraph,
                           pstate: PointsToDomain, where: LTACCmd): PointsToGraph =
            arithBinOp(c, op1, op2, target, where)

    override fun assignSub(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: PointsToGraph,
                           pstate: PointsToDomain, where: LTACCmd): PointsToGraph =
            target.updateVariable(c) { ->
                subSemantics.subtractionSemantics(op1, op2, pstate, where)
            }

    override fun assignMul(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: PointsToGraph,
                           pstate: PointsToDomain, where: LTACCmd): PointsToGraph =
            arithBinOp(c, op1, op2, target, where)

    override fun assignLt(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: PointsToGraph,
                          pstate: PointsToDomain, where: LTACCmd): PointsToGraph =
            arithBinOp(c, op1, op2, target, where)

    lateinit var arrayStateAnalysis: ArrayStateAnalysis
    private val subSemantics = object : SubtractionSemantics<VPointsToValue>(this) {
        override val nondetInteger: VPointsToValue
            get() = INT
        override val scratchPointerResult: VPointsToValue
            get() = ScratchPointer

        override fun numericSubtraction(op1: TACSymbol, op2: TACSymbol, pstate: PointsToDomain, where: LTACCmd): VPointsToValue {
            return interpSymbol(where, pstate.pointsToState, op1).tryResolveInt().let { av1 ->
                interpSymbol(where, pstate.pointsToState, op2).tryResolveInt().let { av2 ->
                    pArithSemantics(av1, av2)
                }
            }
        }

        override fun lengthOfBlock(arrayPtr: Set<TACSymbol.Var>, pstate: PointsToDomain, where: LTACCmd): VPointsToValue = INT
    }

    private val pointerPlusSemantics by lazy {
        object : AdditionSemantics<PointsToGraph>() {
            override val relaxedArrayAddition: Boolean
                get() = this@PointerSemantics.relaxedAddition
            override val numericAnalysis: NumericAnalysis
                get() = this@PointerSemantics.numericAnalysis
            override val arrayStateAnalysis: ArrayStateAnalysis
                get() = this@PointerSemantics.arrayStateAnalysis

            override fun toAddedStaticArrayInitPointer(
                av1: InitializationPointer.StaticArrayInitPointer,
                o1: TACSymbol.Var,
                target: PointsToGraph,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>,
            ): PointsToGraph {
                return target.updateVariable(where.lhs) { -> av1 }
            }

            override fun nondeterministicInteger(where: ExprView<TACExpr.Vec.Add>, s: PointsToDomain, target: PointsToGraph): PointsToGraph {
                return nondetInt(target, where)
            }

            private fun nondetInt(target: PointsToGraph, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return target.updateVariable(where.lhs) { ->
                    INT
                }
            }

            override fun toEndSegment(startElem: Set<TACSymbol.Var>, o1: TACSymbol.Var, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return nondetInt(target, where)
            }

            override fun byteInitAddition(
                av1: InitializationPointer.ByteInitPointer,
                amountAdded: IntValue,
                o1: TACSymbol.Var,
                target: PointsToGraph,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): PointsToGraph {
                return target.updateVariable(where.lhs) { ->
                    av1.copy(
                            mayAdded = true,
                            mustAdded = av1.mustAdded || amountAdded.isDefinitelyGreaterThan(BigInteger.ZERO)
                    )
                }
            }

            override fun blockInitAddition(
                av1: InitializationPointer.BlockInitPointer,
                o1: TACSymbol.Var,
                newOffset: BigInteger,
                target: PointsToGraph,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): PointsToGraph {
                return target.updateVariable(where.lhs) { ->
                    av1.copy(offset = newOffset)
                }
            }

            override fun arrayInitAddition(av1: InitializationPointer.ArrayInitPointer, x: BigInteger?, o1: TACSymbol.Var, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return target.updateVariable(where.lhs) { ->
                    av1.copy(
                            mayAdded = true,
                            mustAdded = av1.mustAdded || (x != null && x > BigInteger.ZERO)
                    )
                }
            }

            override fun toAddedElemPointer(
                arrayBase: Set<TACSymbol.Var>,
                v: Set<ArrayAbstractLocation.A>,
                o1: TACSymbol.Var?,
                addOperand: TACSymbol,
                currIndex: IntValue,
                addAmount: IntValue,
                untilEnd: Set<CanonicalSum>,
                target: PointsToGraph,
                p: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): PointsToGraph {
                return nondetInt(target, where)
            }

            override fun toArrayElemStartPointer(v: Set<ArrayAbstractLocation.A>, o1: TACSymbol.Var, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return target.updateVariable(where.lhs) { ->  Pointer.ArrayElemStart(v) }
            }

            override fun toArrayElementPointer(
                v: Set<ArrayAbstractLocation.A>,
                basePointers: Set<TACSymbol.Var>,
                index: IntValue?,
                indexVar: Set<TACSymbol.Var>,
                untilEnd: Set<CanonicalSum>,
                target: PointsToGraph,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): PointsToGraph {
                return target.updateVariable(where.lhs) { ->
                    Pointer.ArrayElemPointer(v)
                }
            }

            override fun toConstArrayElemPointer(v: Set<L>, o1: TACSymbol.Var, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return target.updateVariable(where.lhs) { ->
                    Pointer.ConstSizeArrayElemPointer(v)
                }
            }

            override fun toBlockElemPointer(av1: Set<L>, offset: BigInteger, blockSize: BigInteger, op1: TACSymbol.Var, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return target.updateVariable(where.lhs) { ->
                    Pointer.BlockPointerField(
                            blockAddr = av1, offset = offset, blockSize = blockSize
                    )
                }
            }

            override fun toIdentityPointer(o1: TACSymbol.Var, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                val rhsValue = target.store[o1]!!.tryResolvePointer()
                ptaInvariant(rhsValue !is INT) { "numeric thought $o1 was pointer but was INT" }
                return target.updateVariable(where.lhs) { ->
                    rhsValue
                }
            }

            override fun scratchPointerAddition(o1: TACSymbol.Var, o2: TACSymbol, offsetAmount: IntValue, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return target.updateVariable(where.lhs) { ->
                    ScratchPointer
                }
            }

            override fun arithmeticAddition(o1: TACSymbol.Var, o2: TACSymbol, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return nondetInt(target, where)
            }

            override fun additionConstant(c1: BigInteger, c2: BigInteger, o1: TACSymbol.Const, o2: TACSymbol.Const, target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return nondetInt(target, where)
            }

            override val pointerAnalysis: IPointerInformation
                get() = this@PointerSemantics


            override fun toEmptyDataSegment(target: PointsToGraph, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): PointsToGraph {
                return nondetInt(target, where)
            }
        }
    }

    override fun assignAdd(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: PointsToGraph,
                           pstate: PointsToDomain, where: LTACCmd): PointsToGraph = pointerPlusSemantics.addition(target, pstate, where.enarrow())

    override fun scratchAllocationTo(lhs: TACSymbol.Var, target: PointsToGraph, pstate: PointsToDomain,
                                     where: LTACCmd): PointsToGraph = target.updateVariable(lhs) { -> ScratchPointer }

    override fun allocationTo(lhs: TACSymbol.Var, abstractLocation: AllocationAnalysis.AbstractLocation,
                              target: PointsToGraph, pstate: PointsToDomain, where: LTACCmd): PointsToGraph =
            target.updateVariable(lhs) { h ->
                fun byteAlloc(): Pair<InitializationPointer.ByteInitPointer, AbstractHeap> {
                    val iLoc = InitAddress(abstractLocation)
                    return InitializationPointer.ByteInitPointer(iLoc, mayAdded = false, mustAdded = false) to h.allocate(iLoc) {
                        ArrayBlock.ByteArray
                    }
                }

                fun arrayAlloc(): Pair<InitializationPointer.ArrayInitPointer, AbstractHeap> {
                    val b = InitAddress(abstractLocation)
                    return InitializationPointer.ArrayInitPointer(b, mayAdded = false, mustAdded = false) to h.allocate(b) {
                        ArrayBlock.Array(elem = null, mustBeEmpty = false)
                    }
                }

                when (abstractLocation.sort) {
                    is AllocationAnalysis.Alloc.ConstBlock -> {
                        val lloc = L(AllocationSite.Explicit(abstractLocation))
                        InitializationPointer.BlockInitPointer(lloc, offset = BigInteger.ZERO) to h.allocate(lloc) {
                            IndexMap(m = mapOf(), sz = abstractLocation.sort.sz, mustNotBeEmptyArray = abstractLocation.sort.sz != 32.toBigInteger())
                        }
                    }
                    is AllocationAnalysis.Alloc.PackedByteArray -> {
                        byteAlloc()
                    }
                    is AllocationAnalysis.Alloc.ConstantArrayAlloc -> {
                        if(abstractLocation.sort.eSz == 0x1.toBigInteger()) {
                            byteAlloc()
                        } else {
                            arrayAlloc()
                        }
                    }
                    is AllocationAnalysis.Alloc.DynamicBlock -> {
                        if (abstractLocation.sort.eSz == 0x1.toBigInteger()) {
                            byteAlloc()
                        } else {
                            arrayAlloc()
                        }
                    }
                    is AllocationAnalysis.Alloc.ConstantStringAlloc,
                    is AllocationAnalysis.Alloc.StorageUnpack -> {
                        byteAlloc()
                    }
                }
            }

    override fun assignNondetInt(lhs: TACSymbol.Var, target: PointsToGraph, pstate: PointsToDomain,
                                 where: LTACCmd): PointsToGraph = target.updateVariable(lhs) { -> INT }

    override fun assignConstant(lhs: TACSymbol.Var, value: BigInteger, target: PointsToGraph, pstate: PointsToDomain,
                                where: LTACCmd): PointsToGraph =
            target.updateVariable(lhs) { ->
                if (value == 0x60.toBigInteger()) {
                    getAmbiguousValue(where)
                } else {
                    INT
                }
            }

    private fun getAmbiguousValue(where: LTACCmd): VPointsToValue =
            typeVariableManager.getVariableForSite(where).let { tv ->
                tv.ifResolved(INT, emptyArrayPointer as VPointsToValue) ?: run {
                    val result = UnkPointsTo(tv)
                    if(alwaysInConstantOffsetMode) {
                        result.expectInt()
                    }
                    result
                }
            }

    override fun assignVariable(lhs: TACSymbol.Var, rhs: TACSymbol.Var, target: PointsToGraph, pstate: PointsToDomain,
                                where: LTACCmd): PointsToGraph =
            target.updateVariable(lhs) { ->
                target.interp(rhs, where)
            }

    override fun extract(pState: PointsToDomain): PointsToGraph = pState.pointsToState
    fun popAllocation(state: PointsToGraph, pState: PointsToDomain): Pair<PointsToGraph, List<ConversionHints>> {
        val end = state.h.allocStack.last()
        if(state.h.ignoreNextZeroWrite != null) {
            throw AnalysisFailureException("Hit end of initialization, but never saw the promised zero write for $end")
        }
        val storeConv = state.store.mapNotNull {
            val v = it.value
            when (v) {
                is InitializationPointer -> if (v.initAddr == end) {
                    val isArrayElemStart by lazy {
                        (pState.arrayState[it.key] as? ArrayStateAnalysis.Value.ElementPointer)?.index == IntValue.Constant(BigInteger.ZERO)
                    }
                    val convertedValue : VPointsToValue = when (v) {
                        is InitializationPointer.ByteInitPointer,
                        is InitializationPointer.ArrayInitPointer -> {
                            check(v is InitializationPointer.LengthFieldInit)
                            if (v.mayAdded) {
                                if (v.mustAdded && isArrayElemStart) {
                                    Pointer.ArrayElemStart(v = setOf(ArrayAbstractLocation.A(v.initAddr)))
                                } else {
                                    INT
                                }
                            } else {
                                Pointer.ArrayPointer(v = setOf(ArrayAbstractLocation.A(v.initAddr)))
                            }
                        }
                        is InitializationPointer.BlockInitPointer -> if (v.offset != BigInteger.ZERO) {
                            if(v.offset < (v.initAddr.sort as AllocationAnalysis.Alloc.ConstBlock).sz && v.offset.mod(32.toBigInteger()) == BigInteger.ZERO) {
                                Pointer.BlockPointerField(blockAddr = setOf(L(v.initAddr)), offset = v.offset, blockSize = (v.initAddr.sort as AllocationAnalysis.Alloc.ConstBlock).sz)
                            } else {
                                INT
                            }
                        } else {
                            val lloc = L(v.initAddr)
                            val tyvar = typeVariableManager.getVariableForAllocOrNull(lloc)
                            if((tyvar == null || !tyvar.isResolved()) && !pState.pointsToState.h[lloc]!!.block.mustNotBeEmptyArray) {
                                check((v.initAddr.sort as AllocationAnalysis.Alloc.ConstBlock).sz == EVM_WORD_SIZE)
                                Pointer.BlockPointerBaseAmbiguous(
                                    blockAddr = setOf(lloc),
                                    blockSize = EVM_WORD_SIZE,
                                    tyvar = typeVariableManager.getVariableForAlloc(lloc)
                                )
                            } else if(tyvar != null && tyvar.isResolvedArray()) {
                                Pointer.ArrayPointer(v = setOf(ArrayAbstractLocation.StructAlias(lloc)))
                            } else {
                                Pointer.BlockPointerBase(
                                    blockAddr = setOf(lloc),
                                    blockSize = (v.initAddr.sort as AllocationAnalysis.Alloc.ConstBlock).sz
                                )
                            }
                        }
                        is InitializationPointer.StaticArrayInitPointer -> INT
                    }
                    it.key to convertedValue
                } else {
                    null
                }
                else -> null
            }
        }
        val heap = InitAddress(end).let { iAddr ->
            if (iAddr in state.h.arrayInitSpace) {
                val arrObj = state.h.arrayInitSpace[iAddr]!!
                val j = state.h[ArrayAbstractLocation.A(end)]?.let { existing ->
                    existing.copy(
                            summary = true,
                            block = existing.block.join(arrObj)
                    )
                } ?: AbstractObject(summary = false, block = arrObj)
                state.h.copy(
                        arraySpace = state.h.arraySpace + (ArrayAbstractLocation.A(iAddr.addr) to j),
                        arrayInitSpace = state.h.arrayInitSpace - iAddr
                )
            } else {
                state.h
            }
        }.let {
            it.copy(
                    blockAlloc = it.blockAlloc - L(end)
            )
        }
        val conv = storeConv.map {(v, ptr) ->
            when(ptr) {
                is INT -> ConversionHints.Int(v)
                is Pointer.ArrayElemStart -> ConversionHints.ArrayElemStart(v)
                is Pointer.ArrayPointer -> ConversionHints.Array(v)
                is Pointer.BlockPointerField,
                is Pointer.BlockPointerBase,
                is Pointer.BlockPointerBaseAmbiguous -> ConversionHints.Block(v)
                else -> throw PointsToInvariantViolation("Only expected int, array or block, got type $ptr")
            }
        }
        return state.copy(store = state.store + storeConv.toMap(), h = heap.copy(allocStack = heap.allocStack.dropLast(1))) to conv
    }

    override fun getReadType(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): HeapType {
        return pState.pointsToState.interp(loc, ltacCmd).tryResolvePointer()
                .let outer@{ ptv ->
                    if (ptv is INT) {
                        loc.asSpillLocation(pState, ltacCmd)?.let {
                            pState.pointsToState.h.spillSpace[it]?.getType(pState.pointsToState.h)
                                ?: throw AnalysisFailureException("No observed write for $it")
                        }?.let {
                            return@outer it
                        }
                    }

                    val ptvCoerced = if(ptv !is MaybeReadablePointer) {
                        if(isSafeRead(loc, ptv, pState, ltacCmd)) {
                            return@outer HeapType.Int
                        } else {
                            findReadSafetyProof(loc, pState) ?: return@outer null
                        }
                    } else {
                        ptv
                    }
                    if(loc is TACSymbol.Const && loc.value == 0x60.toBigInteger()) {
                        HeapType.Int
                    } else {
                        val p = ptvCoerced.readableIfSafe(loc as TACSymbol.Var, pState, ltacCmd, relaxedAddition) ?: return@outer null
                        p.getReferencedType(pState.pointsToState.h).tryResolve()
                    }
                } ?: throw AnalysisFailureException("$loc is not a valid pointer that can be read (should be caught in pointer semantics)")
    }

    override fun getHeapType(loc: TACSymbol, pState: PointsToDomain): HeapType? {
        return pState.pointsToState.store[loc]?.let {
            if(it is HeapValue) {
                it.getType(pState.pointsToState.h)
            } else if(it is InitializationPointer) {
                when(it) {
                    is InitializationPointer.ArrayInitPointer -> {
                        if(it.mayAdded) {
                            return null
                        }
                        HeapType.Array(
                                elementType = pState.pointsToState.h.arrayInitSpace[it.initAddress]?.let {
                                    it as ArrayBlock.Array
                                }?.elementType(pState.pointsToState.h) ?: return null
                        )
                    }
                    is InitializationPointer.BlockInitPointer -> {
                        if(it.offset != BigInteger.ZERO) {
                            return null
                        }
                        val blk = pState.pointsToState.h[it.v]?.block ?: return null
                        val fieldT = blk.fieldTypes.toMap(pState.pointsToState.h)
                        HeapType.OffsetMap(
                                fieldTypes = fieldT,
                                sz = fieldT.size.toBigInteger() * EVM_WORD_SIZE,
                                blk.mustNotBeEmptyArray
                        )
                    }
                    is InitializationPointer.ByteInitPointer -> HeapType.ByteArray
                    is InitializationPointer.StaticArrayInitPointer -> null
                }
            } else {
                null
            }
        }
    }

    private fun getSizeOfArrayAddress(it: ArrayAbstractLocation.A): BigInteger? {
        return it.addr.getElementSize()?.toConcrete()
    }

    private fun getSizeOfArrayLocation(it: AllocationAnalysis.AbstractLocation) : BigInteger? {
        return (it.sort as? AllocationAnalysis.WithElementSize)?.getElementSize()
    }

    override fun getElementSizeOrEmpty(loc: TACSymbol.Var, pState: PointsToGraph) : ElementSize? {
        return pState.store[loc]?.tryResolve().let {
            if(it is Pointer.ArrayPointer) {
                it.v.filterIsInstance(ArrayAbstractLocation.A::class.java).map {
                    getSizeOfArrayAddress(it)
                }.monadicFold { a, b ->
                    if(a != b) {
                        null
                    } else {
                        a
                    }
                }?.let(ElementSize::Concrete) ?: ElementSize.Bottom
            } else if(it is InitializationPointer.ByteInitPointer) {
                ElementSize.Concrete(BigInteger.ONE)
            } else if(it is InitializationPointer.ArrayInitPointer) {
                getSizeOfArrayLocation(it.v.addr)?.let(ElementSize::Concrete)
            } else {
                null
            }
        }
    }

    override fun getElementSize(loc: TACSymbol.Var, pState: PointsToGraph): BigInteger? {
        return getElementSizeOrEmpty(loc, pState)?.toConcrete()
    }

    override fun isLengthRead(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): Boolean {
        return pState.pointsToState.interp(loc, ltacCmd).tryResolvePointer().let {
            when (it) {
                is Pointer.ArrayPointer -> true
                else -> false
            }
        }
    }

    override fun isByteArray(loc: TACSymbol.Var, pState: PointsToDomain): Boolean =
            pState.pointsToState.store[loc]?.tryResolve()?.let {
                it is Pointer.ArrayPointer && it.getType(pState.pointsToState.h) == HeapType.ByteArray
            } == true

    override fun isLengthWrite(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): Boolean {
        return pState.pointsToState.interp(loc, ltacCmd).tryResolvePointer().let { av1 ->
            av1 is InitializationPointer.LengthFieldInit && !av1.mayAdded
        }
    }

    override fun isEmptyArray(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): Boolean =
            pState.pointsToState.interp(loc, ltacCmd).tryResolvePointer().let {
                when(it) {
                    is Pointer.ArrayPointer -> it.v.all { addr ->
                        when(addr) {
                            is ArrayAbstractLocation.EMPTY_ARRAY -> true
                            is ArrayAbstractLocation.StructAlias -> true
                            is ArrayAbstractLocation.A -> pState.pointsToState.h.arrayAction(addr, { false }, { it.mustBeEmpty })
                        }
                    }
                    else -> false
                }
            }

    private val storageArrayLengthFinder by lazy {
        StorageArrayLengthFinder(graph)
    }

    override fun lengthReadFor(ltacCmd: LTACCmd, pState: PointsToDomain): Set<TACSymbol.Var>? {
        if(pState.pointsToState.h.allocStack.isEmpty()) {
            return null
        }
        if(ltacCmd.cmd !is TACCmd.Simple.AssigningCmd.WordLoad) {
            return null
        }
        val top = pState.pointsToState.h.allocStack.last()
        return when(top.sort) {
            is AllocationAnalysis.Alloc.DynamicBlock -> {
                if(storageArrayLengthFinder.isStorageArrayLengthRead(ltacCmd.narrow(), top.sort)) {
                    pState.pointsToState.store.entries.mapNotNull { (k, v) ->
                        if(v !is InitializationPointer.ArrayInitPointer || v.v.addr != top) {
                            null
                        } else  {
                            k
                        }
                    }.toSet()
                } else {
                    null
                }
            }
            else -> null
        }
    }

    fun finalize() {
        concreteAllocationManager.finalizeUnification()
    }

    companion object {
        fun isAlignedBlockIndex(op2Constant: BigInteger): Boolean =
                op2Constant.mod(ALIGN_CONST) == BigInteger.ZERO

        val ALIGN_CONST = 0x20.toBigInteger()

        val empty : PointsToGraph = PointsToGraph(
            store = treapMapOf(),
            h = AbstractHeap(
                blockAlloc = setOf(),
                spillSpace = mapOf(),
                arraySpace = mapOf(),
                arrayInitSpace = mapOf(),
                blockSpace = mapOf(),
                allocStack = listOf(),
                concreteSpace = ConcreteSpaceSet(listOf()),
                ignoreNextZeroWrite = null
            )
        )
    }

    private fun consumeArraySizeHints(state: PointsToGraph, it: List<ArrayHints>) : PointsToGraph =
            it.fold(state) { Curr, hint ->
                when(hint) {
                    is ArrayHints.MustNotBeEmpty -> Curr.mapVariable(hint.op) { aVal ->
                        assert(aVal !is UnkPointsTo)
                        when(aVal) {
                            is Pointer.ArrayPointer -> aVal.copy(aVal.v.filter {
                                it !is ArrayAbstractLocation.EMPTY_ARRAY &&
                                    (it !is ArrayAbstractLocation.A || it.addr !is AllocationSite.Explicit || it.addr.alloc.sort !is AllocationAnalysis.Alloc.ConstantArrayAlloc
                                        || it.addr.alloc.sort.constSize != BigInteger.ZERO)
                            }.toSet())
                            else -> aVal
                        }
                    }
                    is ArrayHints.MustBeEmpty -> Curr.store[hint.op]?.tryResolvePointer()?.let { aVal ->
                        when(aVal) {
                            is InitializationPointer.ArrayInitPointer -> {
                                if(!aVal.mayAdded) {
                                    Curr.h.updateObject(aVal.v) { it: ArrayBlock.Array -> it.copy(mustBeEmpty = true) }.let { h_ ->
                                        Curr.copy(h = h_)
                                    }
                                } else {
                                    Curr
                                }
                            }
                            is Pointer.ArrayPointer -> {
                                if(aVal.v.size != 1 || aVal.v.first() !is ArrayAbstractLocation.A) {
                                    Curr
                                } else {
                                    val a = aVal.v.first()
                                    check(a is ArrayAbstractLocation.A)
                                    check(a in Curr.h)
                                    val obj = Curr.h[a]!!
                                    if(obj.summary || obj.block !is ArrayBlock.Array) {
                                        Curr
                                    } else {
                                        Curr.copy(
                                                h = Curr.h.plus(a, obj.copy(
                                                        block = obj.block.copy(mustBeEmpty = true)
                                                ))
                                        )
                                    }
                                }
                            }
                            else -> Curr
                        }
                    } ?: Curr
                }
            }

    fun consumeExternalSafetyProofs(
        state: PointsToGraph, convHints: List<ValidElemPointer>, blockConv: List<ValidBlock>
    ) : PointsToGraph {
        return convHints.fold(state) { Curr, hint ->
            if (Curr.store[hint.elemPointer]?.tryResolvePointer()?.let {
                    it is InitializationPointer
                } == true) {
                return@fold Curr
            }
            hint.baseArrays.monadicMap {
                Curr.store[it]?.tryResolvePointer()?.let {
                    if (it !is Pointer.ArrayPointer) {
                        logger.debug {
                            "When propagating hint $it to state $state, disagreement amount what's an array pointer"
                        }
                    }
                    it as? Pointer.ArrayPointer
                }?.v?.filterIsInstance(ArrayAbstractLocation.A::class.java)
            }?.flatten()?.toSet()?.let {
                Curr.updateVariable(hint.elemPointer) { ->
                    Pointer.ArrayElemPointer(
                        it
                    )
                }
            } ?: throw AnalysisFailureException("Failed to find the array pointers the array analysis thought existed")
        }.let { arrConsume ->
            blockConv.fold(arrConsume) { Curr, conv ->
                ptaInvariant(Curr.store[conv.base] is BlockPointer) {
                    "We were told $conv was an block pointer, but we found ${Curr.store[conv.base]}"
                }
                val isInit = Curr.store[conv.base] is InitializationPointer
                val addr = (Curr.store[conv.base] as BlockPointer).blockAddr
                ptaInvariant(!isInit || addr.size == 1) {
                    "Expected that base variables ${conv.base} is an init  pointer, but have multiple addresses $addr"
                }
                Curr.copy(
                    store = Curr.store + (conv.block to if (isInit) {
                        InitializationPointer.StaticArrayInitPointer(addr.first())
                    } else {
                        Pointer.ConstSizeArrayElemPointer(addr)
                    })
                )
            }
        }
    }

    override fun pointerFor(abstractLocation: AllocationAnalysis.AbstractLocation): VPointsToValue {
        return when(abstractLocation.sort) {
            is AllocationAnalysis.Alloc.ConstBlock -> InitializationPointer.BlockInitPointer(L(abstractLocation), BigInteger.ZERO)
            is AllocationAnalysis.Alloc.DynamicBlock -> {
                val iAddr = InitAddress(abstractLocation)
                if (abstractLocation.sort.eSz == BigInteger.ONE) {
                    InitializationPointer.ByteInitPointer(iAddr, mustAdded = false, mayAdded = false)
                } else {

                    InitializationPointer.ArrayInitPointer(iAddr, mayAdded = false, mustAdded = false)
                }
            }
            is AllocationAnalysis.Alloc.PackedByteArray -> InitializationPointer.ByteInitPointer(InitAddress(abstractLocation), mustAdded = false, mayAdded = false)
            is AllocationAnalysis.Alloc.ConstantStringAlloc,
            is AllocationAnalysis.Alloc.StorageUnpack -> {
                InitializationPointer.ByteInitPointer(InitAddress(abstractLocation), mayAdded = false, mustAdded = false)
            }
            is AllocationAnalysis.Alloc.ConstantArrayAlloc -> {
                val iAddr = InitAddress(abstractLocation)
                if (abstractLocation.sort.eSz == BigInteger.ONE) {
                    InitializationPointer.ByteInitPointer(iAddr, mustAdded = false, mayAdded = false)
                } else {

                    InitializationPointer.ArrayInitPointer(iAddr, mayAdded = false, mustAdded = false)
                }
            }
        }
    }

    override fun liftInt(value: BigInteger): VPointsToValue = INT

    private val alwaysInConstantOffsetMode = allocSites.isEmpty() && scratchSite.isEmpty()

    override fun liftAmbiguous(variableForSite: TypeVariable): VPointsToValue {
        if(alwaysInConstantOffsetMode) {
            return UnkPointsTo(variableForSite).expectInt()
        }
        return UnkPointsTo(variableForSite)
    }

    override fun PointsToGraph.lookup(sym: TACSymbol.Var): VPointsToValue? = this.store[sym]

    override val scratch: VPointsToValue
        get() = ScratchPointer
    override val untracked: VPointsToValue
        get() = INT

    override fun VPointsToValue.maybeResolve(): VPointsToValue = this.tryResolve()

    private val loopInterpreter = object : LoopValueSummaryInterpreter<PointsToGraph, VPointsToValue>() {
        override fun scratchIncrement(sourcePointer: TACSymbol.Var, increment: IntValue?, target: PointsToGraph): VPointsToValue = ScratchPointer

        override val havocInt: VPointsToValue
            get() = INT

        override fun incrementPackedByte(it: TACSymbol.Var, increment: IntValue?, target: PointsToGraph, init: InitializationPointer.ByteInitPointer): VPointsToValue = init.copy(mayAdded = true)

    }

    override fun isEmptyStringArrayRead(s: TACSymbol.Var, target: PointsToDomain) : Boolean {
        val h = target.pointsToState.store[s]?.tryResolvePointer() ?: return false
        if(h is Pointer.BlockPointerBaseAmbiguous && h.isResolved()) {
            return h.isResolvedArray()
        }
        return h.let {
            when(it) {
                is Pointer.BlockPointerBase -> {
                    if(it.blockSize == 32.toBigInteger()) {
                        it.blockAddr
                    } else {
                        null
                    }
                }
                is Pointer.BlockPointerBaseAmbiguous -> it.blockAddr
                else -> null
            }
        }?.none {
            target.pointsToState.h[it]?.block?.mustNotBeEmptyArray ?: true
        } == true
    }

    override fun lengthForActiveAllocs(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): Set<TACSymbol.Var> {
        if(ltacCmd.cmd !is TACCmd.Simple.AssigningCmd.ByteLoad) {
            return setOf()
        }
        if(loc !is TACSymbol.Var) {
            return setOf()
        }
        val meta = ltacCmd.cmd.meta
        if (!meta.containsKey(TACMeta.NON_ALIASED_LENGTH_READ)) {
            return setOf()
        }
        val top = pState.pointsToState.h.allocStack.last()
        return pState.pointsToState.store.entries.filter { (_, v) ->
            v is InitializationPointer && v.initAddr == top && v is InitializationPointer.LengthFieldInit && !v.mayAdded
        }.map { it.key }.toSet()
    }

    fun consumeLoopSummary(s: Map<TACSymbol.Var, LoopCopyAnalysis.LoopValueSummary>, target: PointsToGraph, p: PointsToDomain, outPtr: Set<TACSymbol.Var>): PointsToGraph {
        val newBindings = loopInterpreter.interpretLoopSummary(s,  target = target, p = p)
        val heap = outPtr.fold(target.h) { Curr, writeVar ->
            val block = when(val x = target.store.get(writeVar)?.tryResolvePointer() ?: return@fold Curr) {
                is InitializationPointer.FoldOnFinishInit -> {
                    x.initAddress to Curr.arrayAction(x.initAddress, {
                        it
                    }) {
                        it.copy(
                            elem = it.elem?.strictJoin(INT) ?: INT
                        )
                    }
                }
                else -> return@fold Curr
            }
            Curr.copy(
                    arrayInitSpace = Curr.arrayInitSpace + block
            )
        }
        return target.copy(
                store = target.store + newBindings,
                h = heap
        )
    }

    override fun killLhsRelations(lhs: TACSymbol.Var, init: PointsToGraph, pstate: PointsToDomain, where: LTACCmd): PointsToGraph = init

    fun endBlock(
        pointsToState: PointsToGraph,
        @Suppress("UNUSED_PARAMETER") last: LTACCmd,
        @Suppress("UNUSED_PARAMETER") live: Set<TACSymbol.Var>
    ): PointsToGraph = pointsToState

    fun startBlock(pointsToState: PointsToGraph, live: Set<TACSymbol.Var>, referencedFromLive: MutableSet<TACSymbol.Var>) : PointsToGraph {
        /*
          This performs abstract garbage collection
         */
        val observedLocations = mutableSetOf<AllocationSite>()

        fun VPointsToValue.vPointsToValueAllocSites(): Collection<AllocationSite> =
            when (this) {
                is Pointer -> referencedLocations
                is InitializationPointer -> setOf(AllocationSite.Explicit(initAddr))
                is NullablePointer -> (wrapped as Pointer).referencedLocations
                INT -> setOf()
                ScratchPointer -> setOf()
                is UnkPointsTo -> setOf()
            }

        // Treat spill locations as roots
        for (l in pointsToState.h.spillSpace.values) {
            observedLocations.addAll(l.toPointsToValue().vPointsToValueAllocSites())
        }

        for((k, v) in pointsToState.store) {
            if(k !in live && k !in referencedFromLive) {
                continue
            }
            observedLocations.addAll(v.vPointsToValueAllocSites())
        }

        for(v in pointsToState.h.arrayInitSpace) {
            when(val block = v.value) {
                is ArrayBlock.Array -> {
                    block.elem?.let {
                        computeReachable(it, pointsToState.h, observedLocations)
                    }
                }
                ArrayBlock.ByteArray -> Unit
            }
        }
        for((_, v) in pointsToState.h.arraySpace) {
            if(v.block is ArrayBlock.Array && v.block.elem != null) {
                computeReachable(v.block.elem, pointsToState.h, observedLocations)
            }
        }
        for((_, v) in pointsToState.h.blockSpace) {
            for(fld in v.block.m.values) {
                computeReachable(fld, pointsToState.h, observedLocations)
            }
        }
        val newStore = pointsToState.store.mutate { mut ->
            pointsToState.store.forEachEntry entry@{ (k, v) ->
                if(k in live || k in referencedFromLive) {
                    return@entry
                }
                if(v is Pointer && observedLocations.containsAny(v.referencedLocations)) {
                    return@entry
                } else if(v is InitializationPointer && observedLocations.contains(AllocationSite.Explicit(v.initAddr))) {
                    return@entry
                }
                /*
                This is a dead variables and there are NO outstanding references to it's pointed to location (if any)
                */
                mut.remove(k)
            }
        }
        val newHeap = pointsToState.h.copy(
                arraySpace = pointsToState.h.arraySpace.filterKeys {
                    it.addr in observedLocations
                },
                blockSpace = pointsToState.h.blockSpace.filterKeys {
                    it.addr in observedLocations
                }
        )
        return PointsToGraph(
                newStore, newHeap
        )
    }

    private fun computeReachable(v: HeapValue, h: AbstractHeap, observedLocations: MutableSet<AllocationSite>) {
        when(v) {
            is Pointer.ArrayPointer -> {
                v.v.forEach {
                    if(it is ArrayAbstractLocation.A) {
                        if(!observedLocations.add(it.addr)) {
                            return@forEach
                        }
                        h.arraySpace[it]?.block?.let { it as? ArrayBlock.Array }?.elem?.let {
                            computeReachable(it, h, observedLocations)
                        }
                    }
                }
            }
            is Pointer.BlockPointerBase -> {
                v.blockAddr.forEach { l ->
                    if(!observedLocations.add(l.addr)) {
                        return@forEach
                    }
                    h.blockSpace[l]?.block?.m?.values?.forEach {
                        computeReachable(it, h, observedLocations)
                    }
                }
            }
            is Pointer.BlockPointerBaseAmbiguous -> {
                v.blockAddr.forEach { l ->
                    if(!observedLocations.add(l.addr)) {
                        return@forEach
                    }
                    h.blockSpace[l]?.block?.m?.values?.forEach {
                        computeReachable(it, h, observedLocations)
                    }
                }
            }
            is UnkPointsTo,
            is INT -> Unit
        }
    }

    fun verifyNonAliasInitTop(narrow: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteLoad>, s_: PointsToDomain) : Boolean {
        if(s_.pointsToState.h.allocStack.isEmpty() ||  s_.pointsToState.h.allocStack.last().sort !is AllocationAnalysis.WithElementSize) {
            return false
        }

        val s = narrow.cmd.loc.let {
            it as? TACSymbol.Var
        }?.let {
            s_.pointsToState.store[it]
        } ?: return true
        // anything that's not an initialization pointer mustn't alias with us
        if(s !is InitializationPointer.ByteInitPointer && s !is InitializationPointer.ArrayInitPointer) {
            return true
        }
        val ptr = s as InitializationPointer
        return ptr.initAddr != s_.pointsToState.h.allocStack.last()
    }

    override fun blockSizeOf(loc: TACSymbol.Var, pState: PointsToGraph): BigInteger? {
        return pState.store[loc]?.let {
            it as? BlockPointer
        }?.blockAddr?.monadicMap {
            pState.h[it]?.block?.sz
        }?.uniqueOrNull()
    }

    fun verifyBoundedWrite(v: TACSymbol.Var, s: PointsToDomain): Boolean {
        return s.pointsToState.store[v]?.tryResolvePointer()?.let {
            it is InitializationPointer
        } != true
    }

    private fun (((AbstractStore) -> AbstractStore) -> Unit).clearState() : Unit = this {
        it.retainAllValues { v ->
            val res = v.tryResolve()
            res is INT
        }
    }

    fun kill(p: PointsToGraph, toKill: WriteSideEffect): PointsToGraph {
        return PointsToGraph(
            p.store.updateValues { k, aVal ->
                if(k in toKill.killValue) {
                    INT
                } else {
                    aVal
                }
            },
            h = p.h
        )
    }

    private fun nonZeroPathConstraint(path: List<PathInformation<IntQualifier>>) : Boolean {
        return path.any {
            (it is PathInformation.LowerBound && when(it) {
                is PathInformation.StrictLowerBound -> it.num?.let { lb ->
                    lb >= BigInteger.ZERO
                } == true
                is PathInformation.WeakLowerBound -> it.num?.let { lb ->
                    lb > BigInteger.ZERO
                } == true
            })
        }
    }

    fun consumePath(
        path: Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>,
        pointsToState: PointsToGraph,
        arrayHints: List<ArrayHints>,
        context: PointsToDomain
    ): Pair<PointsToGraph, List<ConversionHints>> {
        val consumedExternal = consumeArraySizeHints(pointsToState, arrayHints)
        val toPromote = pointsToState.store.keysMatching { k, value ->
            value is NullablePointer && (path[k]?.let(::nonZeroPathConstraint) == true || path.any { (pVar, constraint) ->
                context.boundsAnalysis[pVar]?.let {
                    it as? QualifiedInt
                }?.qual?.any { q ->
                    q is IntQualifier.NullabilityFlagFor && q.pointerVar == k
                } == true && nonZeroPathConstraint(constraint)
            })
        }
        val moreHints = mutableListOf<ConversionHints>()
        return toPromote.fold(consumedExternal) { Curr, p ->
            val promotedValue : VPointsToValue = when(val wrapped = (Curr.store[p] as NullablePointer).wrapped) {
                is Pointer.ArrayPointer -> {
                    moreHints.add(ConversionHints.Array(p))
                    wrapped
                }
                is Pointer.BlockPointerBase -> {
                    moreHints.add(ConversionHints.Block(p))
                    wrapped
                }
                is Pointer.BlockPointerBaseAmbiguous -> {
                    if(wrapped.isResolvedArray()) {
                        moreHints.add(ConversionHints.Array(p))
                    } else {
                        moreHints.add(ConversionHints.Block(p))
                    }
                    wrapped
                }
            }
            Curr.copy(store = Curr.store + (p to promotedValue))
        } to moreHints
    }

    /**
     * Synthesize the state according to [exitVarsAndTypes]. This function is called for an internal function summary
     * which is given a consistent internal [id]. This [id] is not from a global allocator, and has no meaning outside
     * a particular run of the analysis.
     */
    fun synthesizeState(
        pointsToState: PointsToGraph,
        exitVarsAndTypes: SyntheticAlloc,
        id: Int
    ): PointsToGraph {
        val store = pointsToState.store.builder()
        var heapAccum = pointsToState.h
        for((ind, varTy) in exitVarsAndTypes.withIndex()) {
            val (_, nextHeap, v) = with(TraversalContext(
                    callId = id, argInd = ind
                )) {
                traverseAndAllocate(
                    ordinal = 0,
                    heap = heapAccum,
                    t = varTy.second,
                    mustBeSummary = false
                )
            }
            heapAccum = nextHeap
            store[varTy.first] = v.toPointsToValue()
        }
        return pointsToState.copy(
            store = store.build(),
            h = heapAccum
        )
    }

    private data class TraversalContext(
        val argInd : Int,
        val callId: Int,
    )

    /**
     * In a context which includes the id of the call, the argument index, and whether the allocation is in "summarization mode",
     * take a current [ordinal], a type [t], and [heap], and produce a triple which contains the new ordinal, new abstract heap,
     * and the synthesized value for that type. The ordinal is a effectively a DFS numbering of nodes in the abstract
     * object graph, which combined with the call id and argument number provides a unique ID for the node.
     */
    context (TraversalContext)
    private fun traverseAndAllocate(
        ordinal: Int,
        heap: AbstractHeap,
        t: EVMTypeDescriptor,
        mustBeSummary: Boolean
    ) : Triple<Int, AbstractHeap, HeapValue> {
        /**
         * Allocate a block with [numFields] fields, the types of each field given by [fieldTypeGenerator].
         *
         * Returns the next ordinal, heap, and the heap value representing the (pointer to) the block.
         */
        fun allocateBlock(
            numFields: BigInteger,
            fieldTypeGenerator: (Int) -> EVMTypeDescriptor
        ) : Triple<Int, AbstractHeap, HeapValue> {
            var heapAcc = heap
            var ordinalAcc = ordinal + 1
            val loc = AllocationSite.Synthetic(
                ordinal = ordinal,
                argInd = argInd,
                invokeId = callId,
                elementSize = null
            )
            val address = L(loc)
            var offset = BigInteger.ZERO
            val typingMap = mutableMapOf<BigInteger, HeapType>()
            val valueMap = mutableMapOf<BigInteger, HeapValue>()
            val endPoint = numFields * EVM_WORD_SIZE
            var fieldInd = 0
            while(offset < endPoint) {
                val res = traverseAndAllocate(ordinalAcc, heapAcc, fieldTypeGenerator(fieldInd), mustBeSummary = mustBeSummary)
                heapAcc = res.second
                ordinalAcc = res.first
                typingMap[offset] = res.third.getType(heapAcc)
                valueMap[offset] = res.third
                offset += EVM_WORD_SIZE
                fieldInd++
            }
            val syntheticBlock = IndexMap(
                m = valueMap,
                mustNotBeEmptyArray = true,
                sz = endPoint
            )
            val allocObject = heapAcc.blockSpace.compute(address, { _, obj ->
                obj.copy(
                    summary = true,
                    block = obj.block.join(syntheticBlock,
                        thisIsSummary = false,
                        otherIsSummary = false,
                        isInitializing = false
                    ) // these flags are only used for mismatched keys, which can't happen
                )
            }, AbstractObject(
                summary = mustBeSummary,
                block = syntheticBlock
            ))
            return Triple(
                ordinalAcc,
                heapAcc.copy(
                    blockSpace = heapAcc.blockSpace + (address to allocObject)
                ),
                Pointer.BlockPointerBase(setOf(address), endPoint)
            )
        }

        return when(t) {
            /**
             * No heap impact
             */
            is EVMTypeDescriptor.EVMValueType -> Triple(ordinal, heap, INT)
            is EVMTypeDescriptor.DynamicArrayDescriptor -> {
                /**
                 * bump ordinal for abstract array location.
                 */
                val ord = ordinal + 1
                val arrayLoc = AllocationSite.Synthetic(
                    argInd = argInd,
                    invokeId = callId,
                    elementSize = ElementSize.Concrete(EVM_WORD_SIZE),
                    ordinal = ordinal
                )
                /**
                 * Allocate element types, all of which must be summary nodes now.
                 */
                val (nextOrd, newHeap, elemVal) = traverseAndAllocate(
                    ordinal = ord,
                    heap = heap,
                    t = t.elementType,
                    mustBeSummary = mustBeSummary
                )
                val arrayAddress = ArrayAbstractLocation.A(arrayLoc)
                val arrayBlock = ArrayBlock.Array(
                    elem = elemVal,
                    mustBeEmpty = false
                )

                /**
                 * Merge with existing array (if any)
                 */
                val arrayObj = heap.arraySpace.compute(arrayAddress, { _, curr ->
                    curr.copy(
                        summary = true, block = curr.block.join(arrayBlock)
                    )
                }, AbstractObject(summary = mustBeSummary, block = arrayBlock))
                Triple(
                    nextOrd,
                    newHeap.copy(
                        arraySpace = newHeap.arraySpace + (arrayAddress to arrayObj)
                    ),
                    Pointer.ArrayPointer(
                        setOf(arrayAddress)
                    )
                )
            }
            /**
             * Allocate a heterogeneous block
             */
            is EVMTypeDescriptor.EVMStructDescriptor -> {
                return allocateBlock(t.fields.size.toBigInteger()) {
                    t.fields[it].fieldType
                }
            }
            is EVMTypeDescriptor.EVMMappingDescriptor,
            is EVMTypeDescriptor.FunctionDescriptor -> {
                ptaInvariant(false) {
                    "the type $t types are not supported for summaries, how did we get here?"
                }
                `impossible!`
            }
            is EVMTypeDescriptor.PackedBytes1Array -> {
                val location = AllocationSite.Synthetic(
                    invokeId = callId,
                    argInd = argInd,
                    ordinal = ordinal,
                    elementSize = ElementSize.Concrete(BigInteger.ONE)
                )
                val value = ArrayAbstractLocation.A(location)
                val toReturn = Pointer.ArrayPointer(
                    setOf(value)
                )
                val arrayObj = heap.arraySpace.compute(value, { _, obj ->
                    ptaInvariant(obj.block is ArrayBlock.ByteArray) {
                        "Have address for bytes array, but associated block $obj isn't one?"
                    }
                    obj.copy(summary = true)
                }, AbstractObject(
                    summary = mustBeSummary,
                    block = ArrayBlock.ByteArray
                ))
                return Triple(ordinal + 1, heap.copy(arraySpace = heap.arraySpace + (value to arrayObj)), toReturn)
            }
            /**
             * Allocate a homogeneous struct (aka, static array)
             */
            is EVMTypeDescriptor.StaticArrayDescriptor -> {
                ptaInvariant(t.location == EVMLocationSpecifier.memory) {
                    "somehow reached allocation for a type $t with a non-memory pointer"
                }
                return allocateBlock(t.numElements) {
                    t.elementType
                }
            }
        }
    }

    /**
     * This function is expected to be called *before* executing [nxt], and is used to populate the *pre*-state
     * of [nxt]. This function serves to switch the state to use concrete allocations (if necessary), and ensure that the
     * concrete allocation site is populated in the pre-state (to ensure the [IPointsToInformation] API works).
     *
     * This function will return the preprocessed state and a boolean to indicate whether a switch to concrete allocation
     * has occurred, in which case, the other states in the reduced product are converted to filter out any extant pointer
     * information.
     *
     * XXX(jtoman): as noted by abakst, the mid-stream switch is kind of unsound, as different node identities
     * in the [IPointsToInformation] API is interpreted to mean non-aliasing. Thus, computing a whole program view of memory
     * that mixes these two types of nodes, i.e., symbolic, free-pointer based nodes and constant allocation nodes is unsound,
     * as it implies that a later switching to constant offsets allocations can never overlap with or observe the writes of
     * the previous, FP-based heap. Given the practical rarity of these constant offset switches however, we choose to live
     * with this unsoundness.
     */
    fun preprocess(p: PointsToGraph, context: PointsToDomain, nxt: LTACCmd) : Pair<PointsToGraph, Boolean> {
        /**
         * Common code for handling potentially constant offsets into memory.
         * If [ignore] returns true, or the interpretation of [loc] is known to not be [INT], this function does nothing,
         * and returns the pre-state unchanged without indicating a change of state.
         *
         * Otherwise, the current symbolic abstractions (if any) are removed, and the concrete space has a [ConcreteAllocation]
         * which encompasses the range of [loc] added, so that the covering range is available in the pre-state.
         *
         * NB that if we are already in constant offset mode, this function will just add the concrete allocation into the prestate.
         */
        fun handleIntAccess(
            loc: TACSymbol,
            isWrite: Boolean,
            ignore: () -> Boolean
        ) : Pair<PointsToGraph, Boolean> {
            return p.interp(loc, nxt).tryResolvePointer().let {
                ptaInvariant(p.h.concreteSpace.isEmpty() || (it is INT && !ignore())) {
                    "Broken invariant, in concrete mode we are accessing a pointer value or we think we should ignore a write"
                }
                if (it !is INT || ignore()) {
                    return p to false
                }
                val constOffsetPtr = numericAnalysis.interpSymbol(where = nxt, sym = loc, st = context.boundsAnalysis).tryResolveAsInt()
                if (constOffsetPtr !is QualifiedInt) {
                    logger.warn {
                        "Invalid write to offset ($loc) at $nxt while in constant offset mode"
                    }
                    throw AnalysisFailureException("Invalid write to constant offset ($loc) at $nxt while in constant offset mode")
                }
                val dumpState = p.h.concreteSpace.isEmpty()
                p.updateHeap { heap, stUpdate ->
                    if (dumpState) {
                        // we're switching to constant offset mode, so dump the current AbstractStore
                        stUpdate.clearState()
                    }
                    val ubRoundedUpMinusOne =
                        (constOffsetPtr.x.ub + EVM_WORD_SIZE).andNot(EVM_WORD_SIZE - BigInteger.ONE) - BigInteger.ONE
                    heap.updateConcrete(nxt) { concr ->
                        val interval = IntValue.Interval(constOffsetPtr.x.lb, ubRoundedUpMinusOne)
                        concr.prealloc(
                            concreteAllocationManager.getConcreteAllocationForSite(
                                nxt,
                                interval
                            ),
                            allocRange = interval,
                            isWrite = isWrite
                        )
                    }
                } to dumpState
            }
        }
        if(nxt.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && nxt.cmd.base == TACKeyword.MEMORY.toVar()) {
            if(!alwaysInConstantOffsetMode) {
                return p to false
            }
            /**
             * Here, we indicate we have an unalloced zero access if the address is constant, and the constant binding
             * does not yet exist in the heap. The ignore predicate always returns false.
             */
            return handleIntAccess(nxt.cmd.loc, false) { nxt.cmd.loc.asSpillLocation(context, nxt) != null }
        } else if(nxt.cmd is TACCmd.Simple.AssigningCmd.ByteStore && nxt.cmd.base == TACKeyword.MEMORY.toVar()) {
            /**
             * The dual of the above, for the write case, we the preallocation is not for an
             * have an unalloced read (self-evidently, as this is a write)
             * and we ignore the case where the ignore next zero write flag is set and we are writing zero.
             */
            return handleIntAccess(nxt.cmd.loc, true) {
                nxt.cmd.loc.asSpillLocation(context, nxt) != null
                    || (p.h.ignoreNextZeroWrite != null
                        && numericAnalysis.interpAsConstant(context, nxt, nxt.cmd.value) == BigInteger.ZERO)
                    || ((nxt.cmd.value as? TACSymbol.Var)?.let {
                        it.meta[TACMeta.RESERVED_MEMORY_SLOT]

                    }?.let { slot ->
                        numericAnalysis.interpAsConstant(context, nxt, nxt.cmd.loc) == slot
                    } == true)
            }
        /**
         * The long access case is harder. In one particular case (namely, [vc.data.TACCmd.Simple.CallCore])
         * a long accessing command can both read and write memory non-atomically. If the "switch" occurs as part of the
         * write, then there is no coherent pre-state that can be used to summarize the points-to state before the command
         * executes. Put another way, if the input to the callcore uses the symbolic abstraction, and the output uses the
         * concrete abstraction, then there is no single abstraction we can use to indicate the inputs and outputs in the prestate
         * of the command, as the symbolic and the concrete abstractions cannot intermix.
         *
         * So how do we deal with this? Short answer: we don't. If we detect that the inputs/output use inconsistent abstractions, we
         * simply fail the analysis.
         */
        } else if(nxt.cmd is TACCmd.Simple.LongAccesses) {
            /**
             * Do *any* of the accesses require that we switch to concrete mode?
             */
            val isPreExecutionConstantSwitch = nxt.cmd.accesses.any {
                (it.isWrite || alwaysInConstantOffsetMode)
                && it.base == TACKeyword.MEMORY.toVar()
                && p.interp(it.offset, nxt).tryResolve() is INT
                && numericAnalysis.interpAsUnambiguousConstant(pState = context, value = it.length, ltacCmd = nxt) != BigInteger.ZERO
                && !isScratchCopy(offset = it.offset, length = it.length, ltacCmd = nxt, pState = context)
            }
            /**
             * If we need to switch to concrete mode, then do any of the acesses require we *don't* use concrete mode? If so
             * fail
             */
            if(isPreExecutionConstantSwitch && !nxt.cmd.accesses.all {
                    it.base != TACKeyword.MEMORY.toVar() || (p.interp(it.offset, nxt).tryResolve() is INT)
                }) {
                throw AnalysisFailureException(
                    "Could not infer coherent view for command pre-state: apparent mix of constant and symbolic points-to abstractions"
                )
            }
            /**
             * If we don't need to do concrete mode, we are done.
             */
            if(!isPreExecutionConstantSwitch) {
                return p to false
            }
            val dumpState = p.h.concreteSpace.isEmpty()

            /**
             * Find those accesses to/from memory
             */
            val toUnify = nxt.cmd.accesses.filter {
                it.base == TACKeyword.MEMORY.toVar()
            }.mapNotNull {
                val offs = numericAnalysis.interpSymbol(where = nxt, sym = it.offset, st = context.boundsAnalysis).tryResolveAsInt()
                val length = numericAnalysis.interpSymbol(where = nxt, sym = it.length, st = context.boundsAnalysis).tryResolveAsInt()
                ptaInvariant(offs is QualifiedInt && length is QualifiedInt) {
                    "Could not extract integer abstraction for ${it.offset} ($offs) & ${it.length} ($length) @ $nxt"
                }
                /**
                 * If we're dealing with a size zero input/output, we have nothing to do here.
                 */
                if(length.x.isConstant && length.x.c == BigInteger.ZERO) {
                    return@mapNotNull null
                }
                /**
                 * Compute the range on the bytes touched, the end point is *inclusive*
                 */
                val lb = offs.x.lb
                val ub = (offs.x.ub + length.x.ub - BigInteger.ONE).min(MAX_EVM_UINT256)
                IntValue(lb, ub) to it.isWrite
            }
            ptaInvariant(toUnify.size <= 2) {
                "Expected a long access command to, in practice, only have 2 memory accessing components"
            }
            return p.updateHeap { h, stUpdate ->
                if(dumpState) {
                    stUpdate.clearState()
                }
                /**
                 * Preallocate all intervals in the input and output
                 */
                h.updateConcrete(nxt) { concr ->
                    toUnify.withIndex().fold(concr) { acc, (ind, unificand) ->
                        val (iv, isWrite) = unificand
                        val alloc = concreteAllocationManager.getConcreteAllocationForSite(
                            where = nxt, interval = iv, sort = if (isWrite) {
                                ConcreteAllocationManager.Sort.OUTPUT
                            } else {
                                ConcreteAllocationManager.Sort.INPUT
                            }
                        )

                        /**
                         * Does our write "overlap" with the buffer being read? Then it is unsound for us to "kill"
                         * any allocations in the prestate, as that would imply a write that has occurred before the read.
                         * In such a case, we force the preallocation to unify all cells that overlap with the
                         * output buffer
                         */
                        val isIndependentWrite = isWrite && toUnify.withIndex().none { (otherInd, otherUnificand) ->
                            otherInd != ind && otherUnificand.first.mayIntersect(iv)
                        }
                        acc.prealloc(
                            alloc,
                            allocRange = iv,
                            isWrite = isWrite,
                            forceUnification = !isIndependentWrite
                        )
                    }
                }
            } to dumpState
        } else {
            return p to false
        }
    }
}

private fun VPointsToValue.join(other: VPointsToValue): VPointsToValue {
    return this.tryResolve().let { a ->
        other.tryResolve().let inner@{ b ->
            if (a is UnkPointsTo && b is UnkPointsTo) {
                a.tyVar.unify(b.tyVar)
                return@inner a
            } else if (a is UnkPointsTo) {
                @Suppress("KotlinConstantConditions")
                ptaInvariant(b !is UnkPointsTo) {
                    "Did not expect to find unk pointer $b to join with $a"
                }
                return@inner b.join(a)
            }

            @Suppress("KotlinConstantConditions")
            when (a) {
                INT -> if (b is UnkPointsTo) {
                    ptaInvariant(!b.tyVar.isResolved()) {
                        "Type variable ${b.tyVar} should be unresolved here"
                    }
                    b.expectInt()
                    a
                } else {
                    INT
                }
                is ReferenceValue -> {
                    val intResult : VPointsToValue = INT
                    b.tryResolvePointer().let { resolvB: PointsToValue ->
                        when (a) {
                            is Pointer -> {
                                if (resolvB !is Pointer) {
                                    intResult
                                } else {
                                    when (a) {
                                        is Pointer.BlockPointerBase ->
                                            when(resolvB) {
                                                is Pointer.BlockPointerBase -> if (resolvB.blockSize != a.blockSize) {
                                                    intResult
                                                } else {
                                                    Pointer.BlockPointerBase(
                                                        blockAddr = a.blockAddr.union(resolvB.blockAddr),
                                                        blockSize = resolvB.blockSize
                                                    )
                                                }
                                                is Pointer.BlockPointerBaseAmbiguous -> if(resolvB.blockSize != a.blockSize) {
                                                    intResult
                                                } else {
                                                    resolvB.expectBlock()
                                                    Pointer.BlockPointerBase(
                                                        blockAddr = a.blockAddr.union(resolvB.blockAddr),
                                                        blockSize = resolvB.blockSize
                                                    )
                                                }
                                                else -> intResult
                                            }
                                        is Pointer.BlockPointerBaseAmbiguous ->
                                            when(resolvB) {
                                                is Pointer.BlockPointerBase -> if (resolvB.blockSize != a.blockSize) {
                                                    intResult
                                                } else {
                                                    a.expectBlock()
                                                    Pointer.BlockPointerBase(
                                                        blockAddr = a.blockAddr.union(resolvB.blockAddr),
                                                        blockSize = resolvB.blockSize
                                                    )
                                                }

                                                is Pointer.BlockPointerBaseAmbiguous -> if (resolvB.blockSize != a.blockSize) {
                                                    intResult
                                                } else {
                                                    Pointer.BlockPointerBaseAmbiguous(
                                                        blockAddr = a.blockAddr.union(resolvB.blockAddr),
                                                        blockSize = resolvB.blockSize,
                                                        a.unify(resolvB)
                                                    )
                                                }
                                                is Pointer.ArrayPointer -> {
                                                    a.expectEmptyArray()
                                                    resolvB.copy(v = resolvB.v + a.blockAddr.map(ArrayAbstractLocation::StructAlias))
                                                }

                                                else -> intResult
                                            }
                                        is Pointer.ConstSizeArrayElemPointer -> if (resolvB !is Pointer.ConstSizeArrayElemPointer) {
                                            intResult
                                        } else {
                                            Pointer.ConstSizeArrayElemPointer(v = resolvB.v.union(a.v))
                                        }
                                        is Pointer.ArrayPointer -> if (resolvB is Pointer.ArrayPointer) {
                                            Pointer.ArrayPointer(v = resolvB.v.union(a.v))
                                        } else if(resolvB is Pointer.BlockPointerBaseAmbiguous) {
                                            resolvB.expectEmptyArray()
                                            a.copy(
                                                v = a.v + resolvB.blockAddr.map(ArrayAbstractLocation::StructAlias)
                                            )
                                        } else {
                                            intResult
                                        }

                                        is Pointer.ArrayElemStart ->
                                            if (resolvB !is Pointer.ArrayElemStart) {
                                                intResult
                                            } else {
                                                Pointer.ArrayElemStart(
                                                    v = resolvB.v.union(a.v)
                                                )
                                            }
                                        is Pointer.ArrayElemPointer -> if (resolvB !is Pointer.ArrayElemPointer) {
                                            intResult
                                        } else {
                                            Pointer.ArrayElemPointer(v = resolvB.v.union(a.v))
                                        }
                                        is Pointer.BlockPointerField -> if (resolvB !is Pointer.BlockPointerField || resolvB.offset != a.offset || resolvB.blockSize != a.blockSize) {
                                            intResult
                                        } else {
                                            Pointer.BlockPointerField(
                                                blockAddr = a.blockAddr.union(resolvB.blockAddr),
                                                blockSize = a.blockSize,
                                                offset = resolvB.offset
                                            )
                                        }
                                    }
                                }
                            }
                            is InitializationPointer.ArrayInitPointer -> if (resolvB !is InitializationPointer.ArrayInitPointer || resolvB.v != a.v) {
                                intResult
                            } else {
                                InitializationPointer.ArrayInitPointer(
                                    mayAdded = resolvB.mayAdded || a.mayAdded,
                                    mustAdded = resolvB.mustAdded && a.mustAdded,
                                    v = resolvB.v
                                )
                            }
                            is InitializationPointer.BlockInitPointer -> if (resolvB is InitializationPointer.StaticArrayInitPointer) {
                                if (resolvB.address != a.v) {
                                    intResult
                                } else {
                                    resolvB
                                }
                            } else if (resolvB !is InitializationPointer.BlockInitPointer || resolvB.offset != a.offset || resolvB.v != a.v) {
                                intResult
                            } else {
                                resolvB
                            }
                            is InitializationPointer.ByteInitPointer -> if (resolvB !is InitializationPointer.ByteInitPointer || resolvB.v != a.v) {
                                intResult
                            } else {
                                InitializationPointer.ByteInitPointer(
                                    mayAdded = resolvB.mayAdded || a.mayAdded,
                                    mustAdded = a.mustAdded && resolvB.mustAdded,
                                    v = resolvB.v
                                )
                            }
                            ScratchPointer -> if (b !is ScratchPointer) {
                                intResult
                            } else {
                                a
                            }
                            is InitializationPointer.StaticArrayInitPointer ->
                                if (resolvB !is InitializationPointer.BlockInitPointer && resolvB !is InitializationPointer.StaticArrayInitPointer) {
                                    intResult
                                } else if (resolvB is InitializationPointer.BlockInitPointer) {
                                    if (resolvB.v != a.address) {
                                        intResult
                                    } else {
                                        a
                                    }
                                } else {
                                    check(resolvB is InitializationPointer.StaticArrayInitPointer)
                                    a.takeIf { it.address == resolvB.address } ?: intResult
                                }
                        }
                    }
                }

                is NullablePointer -> {
                    ptaInvariant(a.wrapped is VPointsToValue) {
                        "Expected wrapped value in ${a.wrapped} to be VPointsToValue, it isn't"
                    }
                    if(other !is NullablePointer && other !is BasePointer<*>) {
                        return INT
                    }
                    when(other) {
                        is NullablePointer -> {
                            ptaInvariant(other.wrapped is VPointsToValue) {
                                "Exptected wrapped value in ${other.wrapped} to be VPointsToValue, it isn't"
                            }
                            a.wrapped.join(other.wrapped).let {
                                it as? BasePointer<*>
                            }?.let {
                                NullablePointer(it)
                            } ?: INT
                        }
                        is BasePointer<*> -> {
                            a.wrapped.join(other = other).let {
                                it as? BasePointer<*>
                            }?.let(::NullablePointer) ?: INT
                        }
                        else -> `impossible!` // by the path condition above
                    }
                }

                is UnkPointsTo -> `impossible!`
            }
        }
    }
}

private fun <R> AbstractHeap.arrayAction(v: ArrayAbstractLocation.A, byte: (ArrayBlock.ByteArray) -> R,
                                 eArray: (ArrayBlock.Array) -> R): R {
    check(v in this)
    val obj = this[v]!!
    return if (obj.block is ArrayBlock.Array) {
        eArray(obj.block)
    } else {
        check(obj.block is ArrayBlock.ByteArray)
        byte(obj.block)
    }
}

private fun updateArrayField(heap: AbstractHeap, v: ArrayAbstractLocation.A, heapValue: HeapValue): AbstractHeap {
    val newBlock = heap.arrayAction(v, {it }, { blk ->
        blk.copy(elem = blk.elem?.strictJoin(heapValue) ?: heapValue)
    })
    return heap.plus(v, heap[v]!!.copy(block = newBlock))
}


private fun updateArrayField(heap: AbstractHeap, v: InitAddress, heapValue: HeapValue): AbstractHeap {
    val newBlock = heap.arrayAction(v, { it }, { blk ->
        blk.copy(elem = blk.elem?.strictJoin(heapValue) ?: heapValue)
    })
    return heap.plus(v, newBlock)
}

private fun updateField(
    heap: AbstractHeap, v: L, v1: BigInteger, heapValue: HeapValue, strong: Boolean,
    killMaybeArray: Boolean
): AbstractHeap {
    check(v in heap)
    val obj = heap[v]!!
    val newObj = obj.copy(block = obj.block.updateField(v1, heapValue, strong && !obj.summary, killMaybeArray))
    return heap.plus(v, newObj)
}

