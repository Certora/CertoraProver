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
package analysis.pta.abi

import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.alloc.AllocationAnalysis
import analysis.narrow
import analysis.numeric.*
import analysis.numeric.linear.LVar
import analysis.numeric.linear.LinearTerm
import analysis.numeric.linear.TermMatching.matches
import analysis.numeric.linear.implies
import analysis.pta.*
import analysis.pta.QualifiedInt
import analysis.pta.abi.DecoderAnalysis.InitAddressQualifier.Unresolved
import analysis.pta.abi.DecoderAnalysis.PathProcess.Root
import analysis.ptaInvariant
import bridge.EVMExternalMethodInfo
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.DEFAULT_SIGHASH_SIZE
import evm.EVM_WORD_SIZE
import evm.EVM_WORD_SIZE_INT
import log.Logger
import log.LoggerTypes
import spec.cvlast.typedescriptors.*
import utils.*
import vc.data.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.ABI_DECODER)

private fun <T> T?.andDebug(f: () -> String) = run {
    logger.debug(f)
    this
}

private fun <T> T?.andInfo(f: () -> String) = run {
    logger.info(f)
    this
}

/**
  Recognize decoding ABI serialized from bytes buffers. These buffers can either be in memory
  (i.e., abi.decode calls, or parsing return buffers) or from calldata (parsing complicated data).
 */
class DecoderAnalysis(
        private val pointerSem: IPointerInformation,
        private val numericAnalysis: NumericAnalysis,
        methodInfo: EVMExternalMethodInfo? = null
) : ConstVariableFinder, IDecoderAnalysis {

    /**
     * Represents the transformation of a BufferAccessPath into an ObjectPath.
     *
     * First, some suffix of the buffer access path is stripped off via the [pathProcess].
     * The resulting buffer access path (plus the [inlineOffsets], explained in the comments for [processType])
     * is the root for an object access path which is fed into the [f] function. [f] effectively replaces
     * the suffix stripped off by [pathProcess] with the equivalent [ObjectPath] elements.
     */
    data class ProcessSpec(
        val pathProcess: PathProcess,
        val fieldDepth: Int,
        val f: (ObjectPath) -> ObjectPath
    ) {
        constructor(f: PathProcess) : this(f, 0, { it })
        /**
         * Update the [pathProcess] component of this object, via the function [f]. It is expected
         * that [f] will extend its argument.
         */
        fun mapProcess(f: (PathProcess) -> PathProcess) : ProcessSpec {
            return this.copy(
                    pathProcess = f(this.pathProcess)
            )
        }

        /**
         *
         */
        fun mapObjectPath(f: (ObjectPath) -> ObjectPath) : ProcessSpec {
            return this.copy(
                    f = { o: ObjectPath ->
                        f(this.f(o))
                    }
            )
        }
    }

    /**
       For each argument type in the evm signatures, map its start position in the buffer to the simplified [HeapType]
       representation of that type
     */
    private val topLevelTypes = methodInfo?.argTypes?.let {
        val toReturn = mutableMapOf<BigInteger, HeapType>()
        var indIt = BigInteger.ZERO
        for(t in it) {
            val type = t.toHeapType()
            toReturn[indIt] = type
            indIt += type.sizeInArray()
        }
        toReturn
    }

    private val bufferProcess : Map<BufferAccessPath, List<ProcessSpec>>

    /**
     * As described in the documentation for [ObjectPathAnalysis.ObjectRoot.CalldataRoot],
     * the same location in a buffer may correspond to multiple identities, each with its
     * own "type". This mapping maintains a list of these extended roots with the corresponding
     * [HeapType]
     */
    private val calldataTypes : Map<ObjectPathAnalysis.ObjectRoot.CalldataRoot, HeapType>
    init {
        val pathTypes = mutableMapOf<ObjectPathAnalysis.ObjectRoot.CalldataRoot, HeapType>()
        val out = mutableMapOf<BufferAccessPath, List<ProcessSpec>>()

        if(methodInfo != null) {
            var indIt = BigInteger.ZERO
            for(t in methodInfo.argTypes) {
                val heapType = t.toHeapType()
                /*
                 bp is the buffer access path of the beginning of the object with type t
                 */
                val bp = BufferAccessPath.Offset(indIt, BufferAccessPath.Root)
                val root = if (heapType.isDynamic()) {
                    BufferAccessPath.Deref(bp)
                } else {
                    bp
                }
                /*
                  After this function finishes, out will contain a map of buffer access paths with a list
                  of ProcessSpec, each of which describes how to transform that BufferAccessPath into a different
                  object path; each object path corresponds to that buffer access path's different identities.

                  During traversal, map will also populate rootTypes, which associated each calldata root visited
                  during traversal with its type.
                 */
                processType(
                    bp = root,
                    output = out,
                    currSpec = ProcessSpecCollection(listOf(), false),
                    fieldDepth = 0,
                    type = t,
                    rootTypes = pathTypes,
                    parentObject = if(heapType.isDynamic()) {
                        ParentObject.DynamicRoot
                    } else {
                        ParentObject.StaticRoot
                    }
                )
                indIt += heapType.sizeInArray()
            }
        }

        bufferProcess = out
        calldataTypes = pathTypes
    }

    /**
     * Unlike the EncoderAnalysis, the decoder analysis uses a single representation for striding,
     * and differentiates the start of the stride (i.e., what we are striding within) with the
     * StrideRoot class.
     */
    sealed class StrideRoot {
        abstract val referencedVars : Set<TACSymbol.Var>
        /**
         * Striding within an array element, the dynamic start for which is [parent]
         */
        data class ArrayElement(val parent: TACSymbol.Var, val indices: Set<TACSymbol>) : StrideRoot() {
            override val referencedVars: Set<TACSymbol.Var>
                get() = setOf(parent) + indices.filterIsInstance<TACSymbol.Var>()
        }

        /**
         * Striding at the root of the decoded buffer (i.e., not within any dynamic structs/arrays/etc)
         */
        object BufferRoot : StrideRoot() {
            override val referencedVars: Set<TACSymbol.Var>
                get() = setOf()
        }

        /**
         * Striding within a dynamic object (always a struct) whose dynamic start qualifier is [parent]
         */
        data class DynamicObject(val parent: TACSymbol.Var) : StrideRoot() {
            override val referencedVars: Set<TACSymbol.Var>
                get() = setOf(parent)
        }
    }

    /**
     * Representation of the completion of the completed decode of some buffer objeect
     *
     * @property sourceBuffer the buffer from which we've completed decoding
     * @property sourcePath the location the source buffer that points to the object we've decoded
     * @property outputs points to the decoded object
     * @property fieldPointers each (p, off) in [fieldPointers] is a variable defined _during_ the decoding
     *           as pointing to the output object + offset `off`.
     *           This exists in service of the direct inlining optimization: if we slice out the decoding code,
     *           we may slice out the definitions of the variables in [fieldPointers] -- if they're still live,
     *           then that's an issue. We record enough information here so that they can be redefined in terms of
     *           the output variables
     */
    data class BufferDecodeResult(
        val sourceBuffer: TACSymbol.Var,
        val outputs: Set<TACSymbol.Var>,
        val sourcePath: BufferAccessPath,
        val fieldPointers: Set<Pair<TACSymbol.Var, BigInteger>>
    )

    data class State(
            val qualifiers: TreapMap<TACSymbol.Var, AbstractDomain>,
            val initQualifiers: TreapMap<AllocationAnalysis.AbstractLocation, InitAddressQualifier>
    ) : Map<TACSymbol.Var, AbstractDomain> by qualifiers, ConstVariableFinder {
        companion object {
            val empty = State(
                    treapMapOf(),
                    treapMapOf()
            )
        }


        fun filter(p1: (TACSymbol.Var, AbstractDomain) -> AbstractDomain?) : State {
            return State(qualifiers = qualifiers.updateValues(p1), initQualifiers = initQualifiers)
        }

        fun killVar(v: TACSymbol.Var): State = this.filter { _, a ->
            if(v in a.referencedVars) {
                if(a is Filterable<*>) {
                    a.removeReference(v)
                } else {
                    null
                }
            } else {
                a
            }
        }

        fun killVars(toKill: Collection<TACSymbol.Var>) : State {
            return this.filter { key, value ->
                value.takeIf {
                    key !in toKill && !value.referencedVars.containsAny(toKill)
                }
            }
        }

        fun assign(lhs: TACSymbol.Var, abstractDomain: AbstractDomain): State {
            return if(lhs in abstractDomain.referencedVars) {
                this
            } else {
                check(this.qualifiers.values.none {
                    lhs in it.referencedVars
                }) {
                    "Assigning a new value to $lhs that is referenced in by another element of the abstract domain. Did not kill facts properly"
                }
                this.copy(
                        qualifiers = this.qualifiers + (lhs to abstractDomain)
                )
            }
        }

        fun join(other: State, leftContext: PointsToDomain, rightContext: PointsToDomain): State {
            val initQualifiers = this.initQualifiers.pointwiseMerge(other.initQualifiers) { a, b ->
                mergeInitQualifiers(a, b).also {
                    if(it == null) {
                        logger.debug {
                            "Join of $a and $b returned null"
                        }
                    }
                }
            }
            val varQualifiers = this.qualifiers.merge(other.qualifiers) { key, value, otherValue ->
                when {
                    otherValue == null -> {
                        /*
                            In general, we don't allow joining if the other domain does not have a
                            value. The exception is if we have a striding pointer, and post-hoc determine that the variable
                            ent.key has to a constant index (as reported by the ArrayStateAnalysis or NumericAnalysis,
                            depending on a memory/calldata source). In this case, we can treat that constant offset as a field
                            pointer with the constant offset (which is index)
                            */
                        val v = value!!
                        if (v !is AbstractDomain.BufferIndices.StridingPointer) {
                            logger.debug {
                                "Skipping ($key,$value) from left state, it is not a striding pointer"
                            }
                            null
                        } else {
                            v.takeIf { joinStrideAndRoot(rightContext, key, v) != null }
                                .onNull { logger.debug { "Skipping ($key,$value) from left state, join with root in right context failed (${rightContext.arrayState[key]})" } }
                        }
                    }
                    value == null -> {
                        if(otherValue !is AbstractDomain.BufferIndices.StridingPointer) {
                            null
                        } else {
                            joinStrideAndRoot(rootContext = leftContext, key = key, v = otherValue)
                        }
                    }
                    else -> {
                        mergeQualifierOrNull(key, value, otherValue, leftContext, rightContext)
                            .onNull { logger.debug { "Join of ($key,$value) with $otherValue returned null, removing" } }
                    }
                }
            }
            return State(
                    initQualifiers = initQualifiers, qualifiers = varQualifiers
            )
        }

        private fun joinStrideAndRoot(rootContext: PointsToDomain, key: TACSymbol.Var, v: AbstractDomain.BufferIndices.StridingPointer): AbstractDomain? {
            /*
              Depending on whether we are decoding from CALLDATA or a memory array, the locations at the root will be
              either constant values (which are all offset by 4) or array elements with constant indices (which will NOT be
              offset by 4). So we first see if we have information in the array analysis, and if so, assume we are decoding from
              a memory array, otherwise if the values are constant we check that we are decoding from calldata, and check for signature
              adjustment. Either way, we treat the constant index as a "field" of the root.
             */
            return rootContext.arrayState[key]?.let {
                it as? ArrayStateAnalysis.Value.ElementPointer
            }?.index?.takeIf {
                it.isConstant
            }?.c?.let {
                StridingQualifier.joinStridingAndField(left = v, right = object : FieldPointer {
                    override val offset: BigInteger
                        get() = it
                })
            } ?: if(v.bufferVar == TACKeyword.CALLDATA.toVar()) {
                rootContext.boundsAnalysis[key]?.let {
                    it as? QualifiedInt
                }?.x?.takeIf(IntValue::isConstant)?.c?.takeIf {
                    it.mod(EVM_WORD_SIZE) == DEFAULT_SIGHASH_SIZE
                }?.subtract(DEFAULT_SIGHASH_SIZE)?.let {
                    StridingQualifier.joinStridingAndField(left = v, right = object : FieldPointer {
                        override val offset: BigInteger
                            get() = it
                    })
                }
            } else null
        }

        private fun mergeQualifierOrNull(k: TACSymbol.Var, v1: AbstractDomain, v2: AbstractDomain, leftContext: PointsToDomain, rightContext: PointsToDomain) : AbstractDomain? {
            /*
             If these are both array elements (i.e. buffer indices and WithArrayBase, use this special merge)
             */
            if(v1 is AbstractDomain.BufferIndices && v2 is AbstractDomain.BufferIndices && v1 is WithArrayBase && v2 is WithArrayBase && v1 != v2 && v1.basePointer == v2.basePointer && v1.bufferVar == v2.bufferVar) {
                /*
                 If both are array starts, remember that.
                 */
                if(v1 is AbstractDomain.BufferIndices.ArrayElemStart && v2 is AbstractDomain.BufferIndices.ArrayElemStart) {
                    return AbstractDomain.BufferIndices.ArrayElemStart(
                            indexVars = v1.indexVars.intersect(v2.indexVars),
                            bufferVar = v1.bufferVar,
                            basePointer = v1.basePointer
                    )
                }
                /*
                 Otherwise, just weaken to ArrayElems
                 */
                check(v1 is ArrayElementIndexTracking && v2 is ArrayElementIndexTracking)
                return AbstractDomain.BufferIndices.ArrayElem(
                        basePointer = v1.basePointer,
                        bufferVar = v2.bufferVar,
                        indexVars = v1.indexVars.intersect(v2.indexVars),
                        constIndex = v1.constIndex?.takeIf { it == v2.constIndex }
                )
            }
            if(v1 is AbstractDomain.BufferIndices.StridingPointer && v2 is AbstractDomain.BufferIndices.OffsetFrom) {
                /*
                 OffsetFrom is always from some dynamic object (striding within an array element is represented with a
                 ElementOffset). Thus it only makes sense to consider trying to join a stride with an offset
                 that are relative to the same object.
                 */
                if (v1.from !is StrideRoot.DynamicObject || v1.from.parent != v2.from) {
                    return null
                }
                ptaInvariant(v1.bufferVar == v2.bufferVar) {
                    "$v1 and $v2 share a parent, but have different buffers"
                }
                return StridingQualifier.joinStridingAndField(v1, v2)
            } else if(v1 is AbstractDomain.BufferIndices.StridingPointer && v2 is AbstractDomain.BufferIndices.ElementOffset) {
                /*
                  These are for different arrays, or for different elements (due to mismatches in the indices)
                 */
                if(v1.from !is StrideRoot.ArrayElement || v2.parent != v1.from.parent || !v1.from.indices.containsAny(v2.indices)) {
                    return null
                }
                return StridingQualifier.joinStridingAndField(v1, v2)
            } else if(v2 is StridingQualifier && v1 is FieldPointer) {
                /*
                  Swap, in our join rules we always assume a stride during join is the "left" result
                 */
                return mergeQualifierOrNull(k = k, v1 = v2, v2 = v1, leftContext = rightContext, rightContext = leftContext)
            } else if(v1 is AbstractDomain.BufferIndices.StridingPointer && v2 is AbstractDomain.BufferIndices.StridingPointer) {
                if(v1.from != v2.from) {
                    return null
                }
                return StridingQualifier.joinStridePaths(v1, v2) { p, path ->
                    p.copy(stridePath = path)
                }
                /*
                  The inverse of the next condition, swap so we only have to handle the case where v2 is the array base
                 */
            } else if(((v1 is AbstractDomain.BufferIndices.ElementOffset) || (v1 is AbstractDomain.BufferIndices.StridingPointer)) && v2 is WithArrayBase) {
                return mergeQualifierOrNull(k = k, v1 = v2, v2 = v1, leftContext = rightContext, rightContext = leftContext)
            } else if(v1 is WithArrayBase && (v2 is AbstractDomain.BufferIndices.ElementOffset || v2 is AbstractDomain.BufferIndices.StridingPointer)) {
                check(v1 is ArrayElementIndexTracking)
                if(v2 is AbstractDomain.BufferIndices.ElementOffset) {
                    val sameElement = v1.sameIndexAs(v2.indices)
                    /*
                     Then we are joining an offset from some element with the (start of the) element itself. If the offset
                     is zero, then there is no work to be done, v1 and v2 represent the same location, but we prefer the
                     Offset representation.
                     */
                    return if(v2.offset == BigInteger.ZERO && sameElement) {
                        v2
                    } else if(sameElement) {
                        /*
                          Otherwise, v1 is effectively at offset 0, so we generate a striding pointer that is recorded to
                          begin in the element at indices v2.indices, and whose stride is exactly the offset of v2.offset.
                         */
                        return AbstractDomain.BufferIndices.StridingPointer(
                                indexVars = leftContext.variablesEqualTo(BigInteger.ZERO).intersect(rightContext.variablesEqualTo(BigInteger.ONE)),
                                bufferVar = v1.bufferVar,
                                from = StrideRoot.ArrayElement(
                                        parent = v2.parent,
                                        indices = v2.indices
                                ),
                                stridePath = StridePath(root = BigInteger.ZERO,path = listOf()),
                                strideBy = v2.offset,
                                innerOffset = BigInteger.ZERO
                        )
                    } else {
                        null
                    }
                } else {
                    check(v2 is AbstractDomain.BufferIndices.StridingPointer)
                    /*
                     Check that this striding pointer is:
                     1. from the same element of the array
                     2. Starts at the beginning of the element
                     3. Is not mid stride (i.e., 0 is the beginning of each stride step)
                     */
                    if(v2.stridePath.root != BigInteger.ZERO || v2.innerOffset != BigInteger.ZERO || v2.from !is StrideRoot.ArrayElement ||
                            v2.from.parent != v1.basePointer || !v1.sameIndexAs(v2.from.indices)) {
                        return null
                    }
                    return v2
                }
            } else if(v1 is AbstractDomain.BufferIndices.OffsetFrom && v2 is AbstractDomain.BufferIndices.OffsetFrom) {
                if (v1 == v2) {
                    return v1
                }
                /* different parents, rip */
                if (v1.from != v2.from) {
                    return null
                }
                return StridingQualifier.joinMismatchedField(
                        k1 = v1.offset,
                        s1 = leftContext,
                        k2 = v2.offset,
                        s2 = rightContext
                ) { stridePath: StridePath, strideBy: BigInteger, indexVars: Set<TACSymbol.Var> ->
                    AbstractDomain.BufferIndices.StridingPointer(
                            strideBy = strideBy,
                            bufferVar = v1.bufferVar,
                            from = StrideRoot.DynamicObject(v1.from),
                            innerOffset = BigInteger.ZERO,
                            stridePath = stridePath,
                            indexVars = indexVars
                    )
                }
            } else if(v1 is AbstractDomain.BufferIndices.ElementOffset && v2 is AbstractDomain.BufferIndices.ElementOffset) {
                if(v1 == v2) {
                    return v1
                }
                if(v1.parent != v2.parent) {
                    return null
                }
                if(v1.offset == v2.offset) {
                    return AbstractDomain.BufferIndices.ElementOffset(
                        parent = v1.parent,
                        offset = v1.offset,
                        bufferVar = v1.bufferVar,
                        indices = v1.indices.intersect(v2.indices)
                    )
                }
                return StridingQualifier.joinMismatchedField(v1.offset,
                        s1 = leftContext,
                        k2 = v2.offset,
                        s2 = rightContext) { stridePath, strideBy, indexVars ->
                    AbstractDomain.BufferIndices.StridingPointer(
                            strideBy = strideBy,
                            bufferVar = v1.bufferVar,
                            from = StrideRoot.ArrayElement(parent = v1.parent, indices = v1.indices.intersect(v2.indices)),
                            innerOffset = BigInteger.ZERO,
                            stridePath = stridePath,
                            indexVars = indexVars
                    )
                }
            } else if(v1 is AbstractDomain.PointerQualifier.FinalizedPointer && v2 is AbstractDomain.PointerQualifier.FinalizedPointer) {
                if(v1.bufferVar != v2.bufferVar) {
                    return null
                }
                if(v1.javaClass != v2.javaClass) {
                    return null
                }
                if(v1 is AbstractDomain.PointerQualifier.FinalizedPointer.ArrayPointerFor) {
                    ptaInvariant(v2 is AbstractDomain.PointerQualifier.FinalizedPointer.ArrayPointerFor) {
                        "$v1 is a array pointer, $v2 has same class, but isn't an array pointer"
                    }
                    if(v1.bufferVar != v2.bufferVar) {
                        return null
                    }
                    val elemType = v1.elems.join(v2.elems) ?: return null
                    return v1.copy(
                            elems = elemType
                    )
                } else {
                    ptaInvariant(v1 is AbstractDomain.PointerQualifier.FinalizedPointer.StructPointerFor &&
                            v2 is AbstractDomain.PointerQualifier.FinalizedPointer.StructPointerFor) {
                        "Impossible(?) class hierarchy $v1 & $v2"
                    }
                    if(v1.bufferVar != v2.bufferVar) {
                        return null
                    }
                    val contents = v1.contents.join(v2.contents) ?: return null
                    return v1.copy(
                            contents = contents
                    )
                }
            } else if(v1 is AbstractDomain.BufferIndices.DynamicStart && v2 is AbstractDomain.BufferIndices.DynamicStart) {
                return  v1.path.joinOrNull(v2.path)?.takeIf {
                    v1.mustBeArray == v2.mustBeArray && v1.bufferVar == v2.bufferVar
                }?.let {
                    AbstractDomain.BufferIndices.DynamicStart(
                            knownType = v1.knownType?.takeIf { it == v2.knownType },
                            mustBeArray = v1.mustBeArray,
                            lengthBound = v1.lengthBound?.let {
                                v2.lengthBound?.join(it)
                            },
                            bufferVar = v1.bufferVar,
                            path = it,
                            strideSize = if(v1.strideSize != null && v2.strideSize != null && v1.strideSize != v2.strideSize) {
                                return null
                            } else {
                                v1.strideSize ?: v2.strideSize
                            },
                            arrayFor = if(v1.arrayFor != null && v2.arrayFor != null && v1.arrayFor != v2.arrayFor) {
                                return null
                            } else {
                                v1.arrayFor ?: v2.arrayFor
                            }
                    )
                }
            }
            return v1.takeIf {
                v1 == v2
            }
        }

        private fun mergeInitQualifiers(a: InitAddressQualifier, b: InitAddressQualifier): InitAddressQualifier? {
            if(a is InitAddressQualifier.Kill || b is InitAddressQualifier.Kill) {
                return InitAddressQualifier.Kill
            }
            /*
              Then we have two unresolved init qualifiers, but of different shapes (struct vs array)
             */
            if (a::class.java != b::class.java) {
                return null
            }
            check(a is Unresolved)
            check(b is Unresolved)
            if (a.bufferVar != b.bufferVar) {
                return null
            }
            return when (a) {
                is Unresolved.StructInitPointer -> {
                    check(b is Unresolved.StructInitPointer)
                    val cont = b.contents.join(a.contents) ?: return null
                    a.copy(
                            contents = cont
                    )
                }
                is Unresolved.ArrayInitPointerFor -> {
                    check(b is Unresolved.ArrayInitPointerFor)
                    val elemType = if(a.elemType != null && b.elemType != null) {
                        a.elemType.join(b.elemType) ?: return null
                    } else {
                        a.elemType ?: b.elemType
                    }
                    Unresolved.ArrayInitPointerFor(
                            bufferVar = a.bufferVar,
                            elemType = elemType
                    )
                }
            }
        }

        fun mergeOrRemove(basePointers: AllocationAnalysis.AbstractLocation, toMerge: InitAddressQualifier) : State {
            return if(basePointers !in this.initQualifiers) {
                this.copy(
                        initQualifiers = this.initQualifiers + (basePointers to toMerge)
                )
            } else {
                this.copy(initQualifiers = this.initQualifiers.mutate {
                    it.merge(basePointers, toMerge, this::mergeInitQualifiers)
                })
            }
        }
    }

    /**
     * A representation of an object that has been populated with values that have been read out of an encoded
     * buffer. The representation is recursively defined and bottoms out at the only (coarse-grained) value type,
     * a single word.
     */
    sealed class BufferObject {
        /**
         Each word (i.e., leaf) in the tree of represented by a [BufferObject] is associated with the buffer access
         path from which it is read. Note that unlike in the case of [analysis.pta.abi.EncoderAnalysis.LinearizedType],
         intermediate nodes are not tagged with path information: it is not interesting or useful.
         */
        data class Word(val path: Set<BufferAccessPath>) : BufferObject() {
            constructor(path: BufferAccessPath) : this(setOf(path))
        }
        data class Struct(val contents: StructContents) : BufferObject() {
            data class StructContents(val fields: Map<BigInteger, BufferObject>, val static: BufferObject?) {
                fun join(contents: StructContents): StructContents? {
                    /*
                      Static information is lazily discovered as loops are traversed, so any missing
                      static info in one of the operands of the join operation is assumed to have just not been
                      discovered yet.
                     */
                    val static = if(this.static == null) {
                        contents.static
                    } else if(contents.static == null) {
                        this.static
                    } else {
                        contents.static.join(this.static) ?: return null
                    }
                    val fields = this.fields.toMutableMap()
                    val thisIt = fields.iterator()
                    while(thisIt.hasNext()) {
                        val kv = thisIt.next()
                        if(kv.key !in contents.fields) {
                            continue
                        }
                        val joined = kv.value.join(contents.fields[kv.key]!!) ?: return null
                        kv.setValue(joined)
                    }
                    for((k,v) in contents.fields) {
                        if(k in fields) {
                            continue
                        }
                        fields[k] = v
                    }
                    return StructContents(
                            fields = fields,
                            static = static
                    )
                }

                companion object {
                    fun OffsetMap(map: Map<BigInteger, BufferObject>) = StructContents(fields = map, static = null)
                    fun StaticArray(fld: BufferObject) = StructContents(fields = mapOf(), static = fld)
                }
            }
        }
        data class Array(val elemType: BufferObject) : BufferObject()

        open fun join(other: BufferObject) : BufferObject? {
            if(this.javaClass != other.javaClass) {
                return null
            }
            return when (this) {
                is Word -> {
                    this.copy(path = this.path + (other as Word).path)
                }
                is Array -> {
                    this.copy(elemType = (other as Array).elemType.join(
                            this.elemType
                    ) ?: return null)
                }
                is Struct -> {
                    this.copy(contents = this.contents.join(
                            (other as Struct).contents
                    ) ?: return null)
                }
            }
        }

    }

    /**
     * Describes a "suffix" of a BufferAccessPath. Used during consistency checking (see [checkConsistency])
     * to determine whether a buffer access path matches the layout implied by the heap type. Further, when
     * matching terminates at the [Root], the remaining access path is taken to be the identity
     * of the object being decoded.
     */
    sealed class PathProcess {
        data class Offset(val c: BigInteger, val next: PathProcess) : PathProcess()
        data class DynamicStep(val parent: PathProcess) : PathProcess()
        data class StepLoop(val loopStep: BigInteger, val next: PathProcess) : PathProcess()
        data class StepElem(val next: PathProcess) : PathProcess()
        data class ArrayLength(val nested: PathProcess) : PathProcess()
        object Root : PathProcess()
        /**
          A special case of Offset(c = k, Root) where, when matching,
          we match against any Offset(c = j, p) where j >= k, and for which
          the matched resulting path is Offset(c = j - k, p).

          We need this for matching sub-objects within static types at the root of a decoded buffer.
         */
        data class RootOffset(val initialOffset: BigInteger) : PathProcess()

        fun deref() : PathProcess {
            return DynamicStep(this)
        }

        /**
         * Given a path process that selects some prefix of a buffer access path,
         * extend the processing to strip off an additional [c] bytes.
         */
        fun inlineField(c: BigInteger) : PathProcess {
            /*
              If this is a Root, then we would select the buffer access path wholesale.
              To strip off an additional c byte, create a wrapper Offset class
             */
            return if(this is Root) {
                Offset(c = c, next = this)
            } else if(this is RootOffset) {
                /*
                  Our goal is to strip off this.initialOffset bytes, yielding some offset from the root.
                  If we want to strip off c initial bytes (so that we get the same offset from the root)
                  increment the initialOffset field by c
                 */
                this.copy(initialOffset = this.initialOffset + c)
            } else if(this is Offset) {
                this.copy(
                        c = this.c + c
                )
            } else {
                Offset(c = c, next = this)
            }
        }

        operator fun invoke(buffer: BufferAccessPath) : BufferAccessPath? {
            return when(this) {
                is StepLoop -> {
                    if(buffer !is BufferAccessPath.StaticStride || buffer.strideBy != this.loopStep) {
                        return null
                    }
                    this.next(buffer.parent)
                }
                is StepElem -> {
                    if(buffer !is BufferAccessPath.ArrayElemOf) {
                        return null
                    }
                    this.next(buffer.parent)
                }
                Root -> {
                    buffer
                }
                is ArrayLength -> {
                    (buffer as? BufferAccessPath.Offset)?.takeIf {
                        it.offset == BigInteger.ZERO
                    }?.let {
                        it.base as? BufferAccessPath.Deref
                    }?.parent?.let(this.nested::invoke)
                }
                is Offset -> {
                    (buffer as? BufferAccessPath.Offset)?.takeIf {
                        it.offset == this.c
                    }?.base?.let(this.next::invoke)
                }
                is DynamicStep -> (buffer as? BufferAccessPath.Deref)?.parent?.let(this.parent::invoke)
                is RootOffset -> (buffer as? BufferAccessPath.Offset)?.takeIf {
                    it.offset >= this.initialOffset
                }?.let {
                    it.copy(
                            offset = it.offset - this.initialOffset
                    )
                }
            }
        }

    }

    companion object {
        fun VMTypeDescriptor.toHeapType(): HeapType = if(this is VMStaticArrayType) {
            val elemTy = this.elementType.toHeapType()
            (0 until this.numElements.toIntOrNull()!!).associate {
                (it * EVM_WORD_SIZE_INT).toBigInteger() to elemTy
            }.let { HeapType.OffsetMap(it, this.numElements * EVM_WORD_SIZE, true) }
        } else if(this is VMArrayTypeDescriptor) {
            if(this.elementSize == BigInteger.ONE) {
                HeapType.ByteArray
            } else {
                HeapType.Array(this.elementType.toHeapType())
            }
        } else if(this is EVMTypeDescriptor.EVMStructDescriptor) {
            this.fields.mapIndexed { index, entry ->
                (index * EVM_WORD_SIZE_INT).toBigInteger() to entry.fieldType.toHeapType()
            }.toMap().let { HeapType.OffsetMap(it, this.fields.size.toBigInteger() * EVM_WORD_SIZE, true) }
        } else if(this is VMReferenceTypeDescriptor && this.location == EVMLocationSpecifier.storage) {
            HeapType.Int
        } else if(this !is VMValueTypeDescriptor) {
            throw UnsupportedOperationException("Do not know how to represent type $this as a heap type")
        } else {
            HeapType.Int
        }

        /**
         * This class represents the "closest" containing struct for a process spec
         */
        sealed class ParentObject {
            abstract fun getParentPathProcess(c: BigInteger) : PathProcess

            data object StaticRoot : ParentObject() {
                override fun getParentPathProcess(c: BigInteger): PathProcess {
                    return PathProcess.RootOffset(c)
                }
            }

            data object DynamicRoot : ParentObject() {
                override fun getParentPathProcess(c: BigInteger): PathProcess {
                    return PathProcess.Offset(c, PathProcess.Root)
                }
            }
        }

        private fun HeapType.toParentObject() = if(this.isDynamic()) {
            ParentObject.DynamicRoot
        } else {
            ParentObject.StaticRoot
        }

        /**
         * The rules for object identities in calldata buffers:

        This function recursively traverses a type `t` that "starts" at the initial value given for bp, and computes for each buffer access
        path the potential "object paths" that it may correspond to. As described in the documentation for [analysis.pta.abi.ObjectPathAnalysis.ObjectRoot.CalldataRoot],
        a single buffer access path can have different identities, which is accounted for here (i.e., with the [fieldDepth] argument).
        The "primary" result of this function is the output function, which describes how to transform a buffer access path into
        the (set) of object paths that buffer access path corresponds to.

        As a side effect, this associates with each CalldataRoot the HeapType at that root. [currSpec] is the list of transformations being iteratively defined:
        at each recursive call, the processing steps are extended with buffer processing and object field extension information.
         */
        fun processType(
            bp: BufferAccessPath,
            currSpec: ProcessSpecCollection,
            type: VMTypeDescriptor,
            output: MutableMap<BufferAccessPath, List<ProcessSpec>>,
            rootTypes: MutableMap<ObjectPathAnalysis.ObjectRoot.CalldataRoot, HeapType>,
            parentObject: ParentObject,
            fieldDepth: Int = 0
        ) {
            val rootType = type.toHeapType()
            ptaInvariant(
                (rootType.isDynamic() && bp is BufferAccessPath.Deref) ||
                    (bp is BufferAccessPath.Offset)
            ) {
                "Invariant violated for $bp with type $type"
            }
            /*
               This location in the buffer (with this inner step) has the specific type. Again, note that without
               inner offsets, the "type" that begins at a given buffer access path is ambiguous (see the example in
               the doc of CalldataRoot)
             */

            rootTypes[ObjectPathAnalysis.ObjectRoot.CalldataRoot(bp = bp, fieldDepth = fieldDepth)] = rootType
            output[bp] = (currSpec + ProcessSpec(
                f = { it },
                fieldDepth = 0,
                pathProcess = Root
            )).wrapped
            if(rootType is ArrayType) {
                ptaInvariant(fieldDepth == 0) {
                    "Have a dynamic type at an inline offset from a struct. This is bad!"
                }
                ptaInvariant(bp is BufferAccessPath.Deref) {
                    "At a dynamic array, but the buffer path is not a deref?"
                }
                processArrayLengthPath(bp, currSpec, output, rootTypes, fieldDepth)
            }
            if(type !is VMArrayTypeDescriptor) {
                if(type is EVMTypeDescriptor.EVMStructDescriptor) {
                    processStruct(bp, currSpec, type, output, rootTypes, parentObject, fieldDepth)
                } else {
                    ptaInvariant(rootType is HeapType.Int) { "expect int type at buffer access path $bp, got $rootType" }
                    processInt(bp, currSpec, output, fieldDepth)
                }
            } else {
                if(type !is VMStaticArrayType) {
                    check(bp is BufferAccessPath.Deref)
                    processDynamicArray(bp, currSpec, type, output, rootTypes)
                } else {
                    processStaticArray(bp, currSpec, type, output, rootTypes, parentObject, fieldDepth)
                }
            }
        }

        private fun nextFieldDepth(oldBp: BufferAccessPath, newBp: BufferAccessPath, oldFieldDepth: Int): Int =
            if (oldBp == newBp) { oldFieldDepth + 1 } else { 0 }

        @Suppress("UNUSED_PARAMETER")
        private fun processInt(
            bp: BufferAccessPath,
            currSpec: ProcessSpecCollection,
            output: MutableMap<BufferAccessPath, List<ProcessSpec>>,
            fieldDepth: Int
        ) {
            check(bp is BufferAccessPath.Offset)
        }

        private fun processStruct(
            bp: BufferAccessPath,
            currSpec: ProcessSpecCollection,
            type: EVMTypeDescriptor.EVMStructDescriptor,
            output: MutableMap<BufferAccessPath, List<ProcessSpec>>,
            rootTypes: MutableMap<ObjectPathAnalysis.ObjectRoot.CalldataRoot, HeapType>,
            parentObject: ParentObject,
            fieldDepth: Int
        ) {
            val rootType = type.toHeapType()
            var offsetIt = BigInteger.ZERO
            var fldIt = BigInteger.ZERO
            for(comp in type.fields) {
                val currFld = fldIt
                val currOffset = offsetIt
                val compType = comp.fieldType.toHeapType()
                val extendedSpec =
                    /*
                       An example of extending the set of possible paths:
                       effectively this extends the set of possible ways to *process*
                       buffer access path to include an object path that is rooted at
                       the location of the containing struct, i.e., we wish
                       to extract the buffer access path of the closest-dynamic-parent of this
                       struct.

                       However, we may not have a closest dynamic parent, in which
                       case our path processing needs to be adjusted.
                       We determine the existence of a closest-dynamic-parent by seeing
                       if our current access path is expressed an offset
                       from the root of the buffer (i.e., if this is a top level, static struct).
                       If so, then the root access path to be used within the resulting CalldataRoot
                       should be the offset of the BEGINNING of the containing type, i.e., we should
                       get back RootOffset as our PathProcess. Otherwise, the location of this
                       field must be at some offset from closest-dynamic-parent, in which case
                       we get back just PathProcess.Offset(c, root = PathProcess.Root).

                       Q: What about the object path for a value that is rooted at exactly the buffer access path of said value?
                         (i.e., not a field/element of anything)
                       A: This is not handled in the struct field case, it is handled in the HeapType.Int base case, where
                         we also add a root PathProcess object to m.
                     */
                    currSpec + ProcessSpec(
                        pathProcess = parentObject.getParentPathProcess(BigInteger.ZERO),
                        fieldDepth = fieldDepth,
                        f = { it }
                    )
                /*
                  For each known way of (partially) processing paths...
                 */
                val stepped = extendedSpec.map {
                    /*
                       Extend the process to strip off the currOffset (which will happen BEFORE existing
                       processing steps in it). Remember that the result of this process is to yield an CalldataRoot which
                       can be an identity used in an object path...
                     */
                    it.mapProcess {
                        it.inlineField(currOffset).let {
                            if(compType.isDynamic()) {
                                /* Strip off the leading deref before trying to remove an offset */
                                it.deref()
                            } else {
                                it
                            }
                        }
                    }.mapObjectPath {
                        /*
                           Then record that after extracting any roots, we need to record a field at [currFld].
                           This field is added AFTER all current object field building instructions present in it.
                         */
                        ObjectPathGen.Field(
                            offset = currFld,
                            parent = it
                        )
                    }
                }
                /*
                 Compute the buffer access path steps required to reach the location of type [t].
                 If you noticed that in the dynamic case these steps are the exact mirror of the process
                 steps added above, you're getting it
                 */
                val nextBp = if(compType.isDynamic()) {
                    BufferAccessPath.Deref(
                        parent = BufferAccessPath.Offset(
                            offset = offsetIt,
                            base = bp
                        )
                    )
                } else {
                    if(bp !is BufferAccessPath.Offset) {
                        check(bp is BufferAccessPath.Deref && rootType.isDynamic())
                        BufferAccessPath.Offset(offset = offsetIt, base = bp)
                    } else {
                        bp.copy(offset = bp.offset + offsetIt)
                    }
                }
                val nextParent = compType.toParentObject()
                processType(
                    bp = nextBp,
                    type = comp.fieldType,
                    currSpec = stepped,
                    fieldDepth = nextFieldDepth(bp, nextBp, fieldDepth),
                    output = output,
                    parentObject = nextParent,
                    rootTypes = rootTypes
                )
                offsetIt += compType.sizeInArray()
                fldIt += EVM_WORD_SIZE
            }
        }

        private fun processArrayLengthPath(
            bp: BufferAccessPath.Deref,
            currSpec: ProcessSpecCollection,
            output: MutableMap<BufferAccessPath, List<ProcessSpec>>,
            rootTypes: MutableMap<ObjectPathAnalysis.ObjectRoot.CalldataRoot, HeapType>,
            fieldDepth: Int,
        ) {
            /*
              As a rule, objects do not "start" within a static stride. That is, we are never interested
              in the type/object path/etc. that starts within a static array. Note that we *do* care about objects that
              start at *SPECIFIC* indices within a static array, but when considering a summarized "any possible element of a
              static array" we do not allow that as the root of an object (because it's not a definite identity).

              This plus operation on the ProcessSpecCollection will drop the new process spec if the collection
              has been marked as appearing within a static stride.
             */
            val baseM = currSpec + ProcessSpec(Root)
            /*
               At offset 0 from the beginning of bp we will have the length of this array.
             */
            val baseOffset0 = baseM.map {
                it.mapObjectPath {
                    ObjectPathGen.ArrayLength(it)
                }.mapProcess {
                    PathProcess.Offset(c = BigInteger.ZERO, next = it)
                }
            }
            val lengthBAP = BufferAccessPath.Offset(
                offset = BigInteger.ZERO,
                base = bp
            )
            output[lengthBAP] = baseOffset0.wrapped
            rootTypes[ObjectPathAnalysis.ObjectRoot.CalldataRoot(bp = lengthBAP, fieldDepth = fieldDepth)] = HeapType.Int
        }

        private fun processStaticArray(
            bp: BufferAccessPath,
            currSpec: ProcessSpecCollection,
            type: VMStaticArrayType,
            output: MutableMap<BufferAccessPath, List<ProcessSpec>>,
            rootTypes: MutableMap<ObjectPathAnalysis.ObjectRoot.CalldataRoot, HeapType>,
            parentObject: ParentObject,
            fieldDepth: Int
        ) {
            val nextSol = type.elementType
            val next = nextSol.toHeapType()
            /*
            If we are not within a static stride, it still makes sense to address individual members of a static
            struct. One we have decided to abstract all elements of some parent static array, all nested static
            arrays should also be summarized.
             */
            val rev = type.numElements.intValueExact()
            currSpec.doIfConcretePath {
                var bufferIt = BigInteger.ZERO
                for(i in 0 until rev) {
                    if(next.isDynamic()) {
                        check(parentObject is ParentObject.DynamicRoot)
                        check(bp is BufferAccessPath.Deref)
                        processType(
                            bp = BufferAccessPath.Deref(
                                BufferAccessPath.Offset(
                                    offset = bufferIt,
                                    base = bp
                                )
                            ),
                            type = nextSol,
                            currSpec = (currSpec + ProcessSpec(
                                fieldDepth = fieldDepth,
                                pathProcess = Root,
                                f = { it }
                            )).map {
                                it.mapProcess {
                                    PathProcess.Offset(bufferIt, it).deref()
                                }.mapObjectPath {
                                    ObjectPathGen.Field(
                                        offset = (i * EVM_WORD_SIZE_INT).toBigInteger(),
                                        parent = it
                                    )
                                }
                            },
                            output = output,
                            rootTypes = rootTypes,
                            fieldDepth = 0,
                            parentObject = parentObject
                        )
                    } else {
                        check(bp is BufferAccessPath.Offset)
                        val offsetFromStart = i.toBigInteger() * next.sizeInArray()
                        val nextBp = bp.copy(offset = bp.offset + offsetFromStart)
                        processType(
                            bp = nextBp,
                            type = nextSol,
                            currSpec = (currSpec + ProcessSpec(pathProcess = parentObject.getParentPathProcess(BigInteger.ZERO), fieldDepth, f = { it })).map {
                                it.mapProcess {
                                    it.inlineField(offsetFromStart)
                                }.mapObjectPath {
                                    ObjectPathGen.Field(
                                        offset = (i * EVM_WORD_SIZE_INT).toBigInteger(),
                                        parent = it
                                    )
                                }
                            },
                            output = output,
                            fieldDepth = nextFieldDepth(bp, nextBp, fieldDepth),
                            rootTypes = rootTypes,
                            parentObject = parentObject
                        )
                    }
                    bufferIt += next.sizeInArray()
                }
            }
            val extendedSpec = currSpec + ProcessSpec(
                pathProcess = parentObject.getParentPathProcess(BigInteger.ZERO),
                fieldDepth = fieldDepth,
                f = { it }
            )
            if(next.isDynamic()) {
                check(bp is BufferAccessPath.Deref)
                processType(
                    bp = BufferAccessPath.Deref(
                        BufferAccessPath.Offset(
                            offset = BigInteger.ZERO,
                            base = BufferAccessPath.StaticStride(
                                strideBy = EVM_WORD_SIZE,
                                parent = BufferAccessPath.Offset(
                                    offset = BigInteger.ZERO,
                                    base = bp
                                )
                            )
                        )
                    ),
                    type = nextSol,
                    currSpec = extendedSpec.map {
                        it.mapObjectPath {
                            ObjectPathGen.StaticArrayField(it)
                        }.mapProcess {
                            PathProcess.Offset(c = BigInteger.ZERO, next = PathProcess.StepLoop(EVM_WORD_SIZE, next = PathProcess.Offset(c = BigInteger.ZERO, next = it))).deref()
                        }
                    }.enterStaticStride(),
                    output = output,
                    fieldDepth = 0,
                    rootTypes = rootTypes,
                    parentObject = ParentObject.DynamicRoot
                )
            } else {
                check(bp is BufferAccessPath.Offset)
                processType(
                    bp = BufferAccessPath.Offset(
                        BigInteger.ZERO,
                        BufferAccessPath.StaticStride(
                            strideBy = next.sizeInArray(),
                            parent = bp
                        )
                    ),
                    type = nextSol,
                    currSpec = extendedSpec.map {
                        it.mapProcess {
                            PathProcess.Offset(c = BigInteger.ZERO, next = PathProcess.StepLoop(next.sizeInArray(), next = it))
                        }.mapObjectPath {
                            ObjectPathGen.StaticArrayField(it)
                        }
                    }.enterStaticStride(),
                    output = output,
                    fieldDepth = 0,
                    rootTypes = rootTypes,
                    parentObject = parentObject
                )
            }
        }

        private fun processDynamicArray(
            bp: BufferAccessPath.Deref,
            currSpec: ProcessSpecCollection,
            type: VMArrayTypeDescriptor,
            output: MutableMap<BufferAccessPath, List<ProcessSpec>>,
            rootTypes: MutableMap<ObjectPathAnalysis.ObjectRoot.CalldataRoot, HeapType>,
        ) {
            check(type !is VMStaticArrayType)
            val nextSol = type.elementType
            val next = nextSol.toHeapType()
            val nxtBuffer = BufferAccessPath.ArrayElemOf(
                parent = bp,
                indices = setOf()
            ).let {
                if(next.isDynamic()) {
                    BufferAccessPath.Deref(BufferAccessPath.Offset(
                        offset = BigInteger.ZERO,
                        it
                    ))
                } else {
                    BufferAccessPath.Offset(offset = BigInteger.ZERO, it)
                }
            }
            /*
              Include the location of the beginning of this array as a potential root for the object path
             */
            val extendedSpec = (currSpec + ProcessSpec(Root)).map {
                /*
                  record that the element types (that we are about to process)
                   will occur within an array element
                 */
                it.mapProcess {
                    PathProcess.StepElem(it).let {
                        val proc = PathProcess.Offset(next = it, c = BigInteger.ZERO)
                        if(next.isDynamic()) {
                            proc.deref()
                        } else {
                            proc
                        }
                    }
                }.mapObjectPath {
                    ObjectPathGen.ArrayElem(it)
                }
            }
            return processType(bp = nxtBuffer, type = nextSol, currSpec = extendedSpec, output = output, rootTypes = rootTypes, fieldDepth = 0, parentObject = nextSol.toHeapType().toParentObject())
        }

        /**
         * Given an array type [it], what is the size should we expect to see used to stride over
         * elements of the array. For [HeapType.Array] this will just be the [sizeInArray], however
         * [HeapType.ByteArray]s are copied 32 bytes at a time.
         *
         * If [it] is null, then there is no element striding, so this function returns null.
         */
        private fun elemStrideSize(it: HeapType): BigInteger? {
            return if (it is HeapType.Array) {
                it.elementType.sizeInArray()
            } else if (it is HeapType.ByteArray) {
                EVM_WORD_SIZE
            } else {
                null
            }
        }

        /**
         * A single process spec is a single transformation from a buffer access path
         * into an object path.
         *
         * However, due to the fundamental ambiguity of such access paths, we actually need
         * a collection of possible process specs to denote the possible corresponding object paths.
         *
         * This class abstracts over that collection.
         *
         * @property wrapped the collection of possible specs
         * @property withinStaticStride whether the corresponding access path points into an element of a static array
         */
        class ProcessSpecCollection(
            val wrapped: List<ProcessSpec>,
            private val withinStaticStride: Boolean
        ) {
            operator fun plus(other: ProcessSpec) : ProcessSpecCollection {
                return if(withinStaticStride) {
                    this
                } else {
                    ProcessSpecCollection(wrapped = wrapped + other, withinStaticStride = false)
                }
            }

            fun enterStaticStride(): ProcessSpecCollection = if(this.withinStaticStride) {
                this
            } else {
                ProcessSpecCollection(wrapped, true)
            }

            fun map(f: (ProcessSpec) -> ProcessSpec) = ProcessSpecCollection(
                wrapped = wrapped.map(f), withinStaticStride = withinStaticStride
            )

            fun doIfConcretePath(f: () -> Unit) {
                if(!withinStaticStride) {
                    f()
                }
            }
        }

        /**
         * The "heart" of the decoder analysis. When an object is finished allocating, we check whether
         * the buffer access paths recorded within the popped object (as represented by the [BufferObject] object)
         * are consistent with the decoding of the
         * type [x]. As a side effect, this process also computes the location (i.e., buffer access path)
         * being decoded.
         */
        fun checkConsistency(x: HeapType, obj: BufferObject, process: PathProcess): BufferAccessPath? {
            when (obj) {
                is BufferObject.Word -> return obj.path.mapNotNull {
                    process.inlineField(BigInteger.ZERO)(it)
                }.uniqueOrNull()
                is BufferObject.Struct -> {
                    if (x !is HeapType.OffsetMap) {
                        logger.info {
                            "Expected an OffsetMap heap type for object $obj, got $x"
                        }
                        return null
                    }
                    var root: BufferAccessPath? = null
                    /*
                      If the contents of this struct are the result of decoding a static array, that supercedes any struct fields
                      that were written (these struct field writes usually occur during the first processing of a loop
                      before we "know" that we're seeing a static array decode)
                     */
                    if (obj.contents.static != null) {
                        // verify this is tuple safe
                        val tuple = x.fieldTypes.values.monadicFold { t1, t2 ->
                            if (!t1.checkCompatibility(t2)) {
                                null
                            } else {
                                t1.join(t2)
                            }
                        } ?: return null.andDebug<BufferAccessPath?> {
                            "$obj is expected to be static, but struct $x is not tuple safe"
                        }

                        /*
                           tuple here is the type of the elements of the static array
                         */
                        val expectedStep = tuple.sizeInArray()
                        val needDeref = tuple.isDynamic()
                        /*
                          Then we expect that each access path of each element of this static array to include a steploop
                          element. The size of each step is the size of the elements being stepped over, as determined by
                          sizeInArray
                         */
                        val baseLoop = PathProcess.StepLoop(
                            loopStep = expectedStep,
                            next = process.inlineField(BigInteger.ZERO)
                        )
                        /*
                          If we're dynamic, then we need a dynamic step. A dynamic step must
                          always come after an offset
                         */
                        val basePath = if(needDeref) {
                            PathProcess.DynamicStep(
                                PathProcess.Offset(
                                    next = baseLoop,
                                    c = BigInteger.ZERO
                                )
                            )
                        } else {
                            PathProcess.Offset(next = baseLoop, c = BigInteger.ZERO)
                        }
                        /*
                          Add the "starting" offset within whatever child type we're descending into. Note that in the non-dynamic case, base
                          path will be StepLoop, so we maintain the expected format of paths (i.e., offsets will always
                          follow a step/deref)
                         */
                        val extracted = checkConsistency(tuple, obj.contents.static, basePath) ?: return null
                        root = extracted
                    } else {
                        for ((k, v) in obj.contents.fields) {
                            val fieldTy = x.fieldTypes[k] ?: return null.andDebug<BufferAccessPath?> {
                                "Failed to find offset $k in type $x for struct $obj"
                            }
                            val offsetOfEncoding = x.fieldTypes.filter {
                                it.key < k
                            }.map {
                                it.value.sizeInArray()
                            }.fold(BigInteger.ZERO, BigInteger::add)
                            /*
                               Again, if the content is dynamic, compute the expected offset, and then add a deref + offset(0)

                               Otherwise, just use the extended offset
                             */
                            val extend = if (fieldTy.isDynamic()) {
                                PathProcess.DynamicStep(process.inlineField(offsetOfEncoding))
                            } else {
                                process.inlineField(offsetOfEncoding)
                            }
                            val extracted = checkConsistency(fieldTy, v, extend) ?: return null.andDebug<BufferAccessPath?> {
                                "Failed consistency check for child $fieldTy at $k in $obj"
                            }
                            if (root == null) {
                                root = extracted
                            } else if (root != extracted) {
                                return null
                            }
                        }
                    }
                    return root
                }
                is BufferObject.Array -> {
                    val elemType = when (x) {
                        is HeapType.ByteArray -> HeapType.Int
                        is HeapType.Array -> x.elementType
                        HeapType.Int,
                        is HeapType.OffsetMap,
                        is HeapType.TVar,
                        is HeapType.EmptyArray -> `impossible!` /* how could we have a provably empty array read from calldata??? */
                    }
                    /*
                      We expect that every dynamic object is reached with a deref, and we maintain that when checkConsistency is called
                      we have an offset process step. Thus we have expect that the current processing is Deref + Offset(0)
                     */
                    ptaInvariant(process is PathProcess.DynamicStep || process is PathProcess.Root) {
                        "Representation invariant violated: have dynamic start $obj with non-offset start $process"
                    }
                    /*
                       No offset, do an elem step instead. This is a little sharp edge, because I don't think
                       pathToArray + Offset(0) + Elem
                       makes sense
                     */
                    val elemPath = PathProcess.StepElem(process)
                    /*
                      As in the field case above, if the contents are dynamic, then add a deref + offset(0)
                      otherwise just add an offset(0)
                     */
                    val elementProcess =
                        if (elemType.isDynamic()) {
                            PathProcess.DynamicStep(
                                PathProcess.Offset(
                                    BigInteger.ZERO, elemPath
                                )
                            )
                        } else {
                            elemPath
                        }
                    return checkConsistency(elemType, obj.elemType, elementProcess)
                }
            }
        }


        fun HeapType.sizeInArray(): BigInteger {
            return if (this.isDynamic()) {
                EVM_WORD_SIZE
            } else {
                when (this) {
                    HeapType.Int -> EVM_WORD_SIZE
                    is HeapType.OffsetMap -> this.fieldTypes.map {
                        it.value.sizeInArray()
                    }.reduce(BigInteger::add)
                    HeapType.ByteArray,
                    is HeapType.Array,
                    HeapType.EmptyArray -> `impossible!`
                    is HeapType.TVar -> EVM_WORD_SIZE
                }
            }
        }
    }

    /**
     * A location within a buffer. Note that while this gives a name to a value within said buffer, it does not provide
     * information about the "identity" of that value, i.e., which field of which object it may correspond to.
     * As described in [analysis.pta.abi.ObjectPathAnalysis.ObjectRoot.CalldataRoot] this representation
     * is actually inappropriate for such a purpose.
     *
     * To enforce uniformity of representation, each buffer access path must follow the following rules:
     * 1. An ArrayElemOf may only come after a Deref
     * 2. A Deref may only follow an offset
     * 3. A static stride must follow an offset
     * 4. An offset may never follow another offset
     *
     * In other words Root.Offset(32).Offset(32) is malformed, the (canonical) representation should be Root.Offset(64)
     *
     * In effect, these rules ensure an alternation between derefs/static strides and offsets (with arrayelemof being an annoying
     * edge case). Note that offset does NOT mean that the value referred to by a buffer access path is a field at the
     * given offset.
     *
     * By convention, the identity of an object must satisfy the following convention:
     * 1. if the type of the object is dynamic, then the buffer access path must end with [Deref]
     * 2. otherwise, the BAP must end with [Offset]
     *
     * Note that a bufferaccesspath by itself is not unambiguous, see the [analysis.pta.abi.ObjectPathAnalysis.ObjectRoot.CalldataRoot]
     * documentation for a discussion of how we further disambiguate [BufferAccessPath] objects. As such, [BufferAccessPath]
     * objects are generally associated with sets: sets of possible types, sets of possible object identities, etc.
     */
    @KSerializable
    @Treapable
    sealed class BufferAccessPath : AmbiSerializable {
        abstract fun nonIndexed() : BufferAccessPath

        fun referencedVariables() : Set<TACSymbol.Var> {
            val m = mutableSetOf<TACSymbol.Var>()
            this.referencedVariables(m)
            return m
        }
        abstract protected fun referencedVariables(m: MutableSet<TACSymbol.Var>)

        @KSerializable
        object Root : BufferAccessPath() {
            override fun hashCode() = utils.hashObject(this)

            fun readResolve(): Any {
                return Root
            }

            override fun nonIndexed(): BufferAccessPath {
                return this
            }

            override fun referencedVariables(m: MutableSet<TACSymbol.Var>) {
            }

            override fun transformVariables(f: (TACSymbol.Var) -> TACSymbol.Var): BufferAccessPath = this

            override fun nonEmptyIndices(): Boolean = true
        }

        @KSerializable
        data class Offset(val offset: BigInteger, val base: BufferAccessPath) : BufferAccessPath() {
            override fun nonIndexed(): BufferAccessPath {
                return this.copy(base = this.base.nonIndexed())
            }

            override fun referencedVariables(m: MutableSet<TACSymbol.Var>) {
                this.base.referencedVariables(m)
            }

            override fun transformVariables(f: (TACSymbol.Var) -> TACSymbol.Var): BufferAccessPath = Offset(
                offset, base.transformVariables(f)
            )

            override fun nonEmptyIndices(): Boolean = base.nonEmptyIndices()
        }

        /*
           Logical index into the array being decoded. Needed to support moving specific indices of a calldata
           array into memory.
         */
        @KSerializable
        data class ArrayElemOf(val parent: BufferAccessPath, val indices: Set<TACSymbol>) : BufferAccessPath() {
            override fun nonIndexed(): BufferAccessPath {
                return this.copy(parent = this.parent.nonIndexed(), indices = setOf())
            }

            override fun nonEmptyIndices(): Boolean = indices.isNotEmpty() && parent.nonEmptyIndices()

            override fun referencedVariables(m: MutableSet<TACSymbol.Var>) {
                indices.forEach {
                    (it as? TACSymbol.Var)?.let(m::add)
                }
                parent.referencedVariables(m)
            }

            override fun transformVariables(f: (TACSymbol.Var) -> TACSymbol.Var): BufferAccessPath =
                ArrayElemOf(
                    parent = this.parent.transformVariables(f),
                    indices = this.indices.map {
                        when(it) {
                            is TACSymbol.Const -> it
                            is TACSymbol.Var -> f(it)
                        }
                    }.toSet()
                )
        }

        @KSerializable
        data class StaticStride(val strideBy: BigInteger, val parent: BufferAccessPath) : BufferAccessPath() {
            override fun nonIndexed(): BufferAccessPath {
                return this.copy(parent = this.parent.nonIndexed())
            }

            override fun nonEmptyIndices(): Boolean = parent.nonEmptyIndices()

            override fun referencedVariables(m: MutableSet<TACSymbol.Var>) {
                parent.referencedVariables(m)
            }

            override fun transformVariables(f: (TACSymbol.Var) -> TACSymbol.Var): BufferAccessPath = StaticStride(
                strideBy, parent.transformVariables(f)
            )
        }

        @KSerializable
        data class Deref(val parent: BufferAccessPath) : BufferAccessPath() {
            init {
                check(parent is Offset) {
                    "$parent was expected to be an Offset"
                }
            }

            override fun nonIndexed(): BufferAccessPath {
                return this.copy(parent = this.parent.nonIndexed())
            }

            override fun nonEmptyIndices(): Boolean = parent.nonEmptyIndices()

            override fun referencedVariables(m: MutableSet<TACSymbol.Var>) {
                parent.referencedVariables(m)
            }

            override fun transformVariables(f: (TACSymbol.Var) -> TACSymbol.Var): BufferAccessPath = Deref(
                parent = this.parent.transformVariables(f)
            )
        }

        fun joinOrNull(other: BufferAccessPath) : BufferAccessPath? {
            if(this is Deref && other is StaticStride) {
                return other.joinOrNull(this)
            } else if(this is StaticStride && other is Deref) {
                /*
                  It may occur that when processing a tuple of dynamic elements we will get:
                  p + deref + offset(g) (iteration 1) \/
                  p + deref + offset(0) + stride(32) + offset(g) (iteration 2, after interpolation)

                  offset(g) will match in both, but then we'll hit a mismatch. We correct this by recognizing that
                  striding from by 32 from the result of derefing p (trivially) includes the location reached by
                  just derefing p.
                 */
                if(this.strideBy == EVM_WORD_SIZE && this.parent is Offset && this.parent.offset == BigInteger.ZERO) {
                    return this.copy(
                            parent = this.parent.copy(
                                    base = this.parent.base.joinOrNull(other) ?: return null
                            )
                    )
                }
                return null
            } else {
                return when(this) {
                    is Root -> this
                    is Offset -> (other as? Offset)?.takeIf {
                        it.offset == this.offset
                    }?.base?.joinOrNull(this.base)?.let {
                        Offset(
                                offset = this.offset,
                                base = it
                        )
                    }
                    is ArrayElemOf -> (other as? ArrayElemOf)?.parent?.let(this.parent::joinOrNull)?.let {
                        ArrayElemOf(indices = other.indices.intersect(this.indices), parent = it)
                    }
                    is StaticStride -> (other as? StaticStride)?.takeIf {
                        it.strideBy == this.strideBy
                    }?.parent?.joinOrNull(this.parent)?.let {
                        StaticStride(
                                strideBy = this.strideBy,
                                parent = it
                        )
                    }
                    is Deref -> (other as? Deref)?.parent?.joinOrNull(this.parent)?.let(::Deref)
                }
            }
        }

        abstract fun transformVariables(f: (TACSymbol.Var) -> TACSymbol.Var): BufferAccessPath

        /** returns true iff all array accesses in this path have a non-empty index set */
        abstract fun nonEmptyIndices(): Boolean
    }

    override fun startBlock(decoderState: State, lva: Set<TACSymbol.Var>, referencedFromLive: Set<TACSymbol.Var>): State {
        return decoderState.filter { key, value ->
            value.takeIf {
                key in lva || key in referencedFromLive
            }
        }
    }

    override fun collectReferenced(decoderState: State, referencedFromLive: MutableSet<TACSymbol.Var>, lva: Set<TACSymbol.Var>) {
        val closure = mutableSetOf<TACSymbol.Var>()
        decoderState.keys.filterTo(closure) {
            it in lva
        }
        var sz: Int
        do {
            sz = closure.size
            val found = mutableSetOf<TACSymbol.Var>()
            for(k in closure) {
                val d = decoderState[k] ?: continue
                found.addAll(d.referencedVars)
            }
            closure.addAll(found)
        } while (closure.size > sz)
        referencedFromLive.addAll(closure)
    }

    override fun endBlock(decoderState: State, last: LTACCmd, live: Set<TACSymbol.Var>): State = decoderState

    override fun consumePath(path: Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>, decoderState: State, s: PointsToDomain): State {
        return decoderState
    }

    /**
     * Called when an allocation is complete. This function extracts the buffer object associated
     * with the address being initialized (if any) and then associates it with the variables that point to the
     * beginning of the finished initializing object. That is, we qualify all of the variables that (as of now)
     * hold a pointer to a fully initialized object that contains information read out of an encoded buffer
     */
    override fun popAllocation(decoderState: State, s: PointsToDomain): Pair<State, BufferDecodeResult?> {
        ptaInvariant(s.pointsToState.h.allocStack.isNotEmpty()) {
            "Popping allocations, but none are done?"
        }
        val finalAddress = s.pointsToState.h.allocStack.last()
        if(finalAddress !in decoderState.initQualifiers) {
            return decoderState to null
        }

        /* i.e., those that point to the beginning of the initialized block */
        val baseVars = s.pointsToState.store.keysMatching { _, v ->
            (v as? InitializationPointer)?.let {
                it !is InitializationPointer.StaticArrayInitPointer &&
                    (it !is InitializationPointer.LengthFieldInit || !it.mayAdded) &&
                    (it !is InitializationPointer.BlockInitPointer || it.offset == BigInteger.ZERO) &&
                    it.initAddr == finalAddress
            } ?: false
        }

        val fieldPointers = s.pointsToState.store.mapNotNull { (x, v) ->
            when (v) {
                is InitializationPointer.BlockInitPointer ->
                    (x to v.offset).takeIf {
                        v.offset != BigInteger.ZERO && v.initAddr == finalAddress
                    }

                is InitializationPointer.ArrayInitPointer,
                is InitializationPointer.ByteInitPointer ->
                    (x to EVM_WORD_SIZE).takeIf {
                        check (v is InitializationPointer.LengthFieldInit && v is InitializationPointer)
                        v.mustAdded && v.initAddr == finalAddress
                    }

                else ->
                    null
            }
        }
        val killed = decoderState.killVars(s.pointsToState.store.keysMatching { _, v ->
            (v as? InitializationPointer)?.initAddr == finalAddress
        })

        val newBindings = mutableMapOf<TACSymbol.Var, AbstractDomain>()
        val initAddressQualifier = decoderState.initQualifiers[finalAddress]!!
        if (initAddressQualifier is InitAddressQualifier.Kill) {
            return killed to null
        }
        check(initAddressQualifier is Unresolved)
        /*
         If this the last object on the stack, then we assume that we have finished decoding an object
         out of memory. In which case, we use the type information we inferred for the just completed
         object, and then check if the path information associated with the decoded object
         is consistent with the type, e.g., the fields of structs have the correct buffer access paths
         for a value of type [initAddressQualifier.toType()] that begins at some root. During this consistency
         checking process, the (unique) root buffer access path is returned as the location whose object
         has been decoded.
         */
        val rootFor = if (s.pointsToState.h.allocStack.size == 1) {
            val buffer = initAddressQualifier.toType()
                    ?: throw AnalysisFailureException("Done initializing, but no types inferred")
            baseVars.monadicMap {
                pointerSem.getHeapType(it, s)
            }?.uniqueOrNull()?.let { ty ->
                val basePath = if (ty.isDynamic()) {
                    Root
                } else {
                    PathProcess.RootOffset(BigInteger.ZERO)
                }
                val rootIndex = checkConsistency(
                        ty,
                        obj = buffer,
                        process = basePath
                )
                if(rootIndex == null) {
                    logger.warn {
                        "At pop for $finalAddress, consistency check for $buffer and $ty failed"
                    }
                    return killed to null
                }
                rootIndex
            }
        } else {
            null
        }
        val decodingResults = rootFor?.takeIf {
            it.nonEmptyIndices()
        }?.let {
            /*
              Record this decoding is complete
             */
            BufferDecodeResult(
                sourceBuffer = initAddressQualifier.bufferVar,
                outputs = baseVars.toSet(),
                sourcePath = it,
                fieldPointers = fieldPointers.toSet(),
            )
        }
        logger.info { "At pop, for $finalAddress & $baseVars, have $rootFor" }
        val finalized = initAddressQualifier.toFinalized()!!
        for (toQualify in baseVars) {
            newBindings[toQualify] = finalized
        }

        return killed.copy(
            qualifiers = (killed.qualifiers + newBindings).let {
                   if(s.pointsToState.h.allocStack.size == 1) {
                       it.retainAllValues {
                           it !is AbstractDomain.PointerQualifier.FinalizedPointer
                       }
                   } else {
                       it
                   }
            },
            initQualifiers = killed.initQualifiers - finalAddress
        ) to decodingResults
    }

    override fun isDynamicOffset(v: TACSymbol.Var, whole: PointsToDomain): Boolean {
        return whole.decoderState[v]?.let {
                it as? AbstractDomain.ReadFrom
            }?.takeIf {
                it.bufferVar == TACKeyword.CALLDATA.toVar()
            }?.let {
                getBufferPath(aVal = it, whole = whole, toStep = whole.decoderState)
            }?.let(BufferAccessPath::Deref)?.let {
                ObjectPathAnalysis.ObjectRoot.CalldataRoot(
                    bp = it.nonIndexed(),
                    fieldDepth = 0
                )
            }?.let(calldataTypes::get)?.isDynamic() ?: false
    }

    override fun step(command: LTACCmd, s_: PointsToDomain): State {
        return abstractSemantics.step(command, s_).let {
            it.copy(
                    qualifiers = indexTracking.stepCommand(ltacCmd = command, m = it.qualifiers, p = s_)
            )
        }
    }

    override fun consumeLoopSummary(decoderState: State, s: PointsToDomain, s1: LoopCopyAnalysis.LoopCopySummary): State {
        /*
         Are we copying from a location in calldata into an initializing array?

         initAddresses are the abtract locations we are (potentially) copying to
         */
        val initAddresses = s1.outPtr.mapNotNull {
            getBasePointersFor(it, s)
        }
        val mutVars = s1.valueSummary.entries.filter {
            it.value != LoopCopyAnalysis.LoopValueSummary.Identity
        }.map { it.key }
        val clearInit by lazy {
            logger.debug {
                "Consuming a copy loop summary $s1 failed, killing $initAddresses"
            }
            decoderState.killVars(s1.inPtr + s1.outPtr + mutVars).let {
                it.copy(
                    initQualifiers = it.initQualifiers - initAddresses
                )
            }
        }
        /*
         Are we copying from an array start?
         */
        val input = s1.inPtr.mapNotNull {
            extractEncodedElemStart(decoderState, it, s)
        }.singleOrNull() ?: return clearInit
        /*
         What is the path of the array?
         */
        val (_, arrayPath) = extractPathForRead(input.basePointer, decoderState) ?: return clearInit
        /*
         are we copying to a unique address?
         */
        val init = initAddresses.singleOrNull() ?: return clearInit
        /*
         If so, then record that the address init holds words that are elements of the array at path arrayPath
         */
        return decoderState.mergeOrRemove(init, Unresolved.ArrayInitPointerFor(
                bufferVar = input.bufferVar,
                elemType = BufferObject.Word(setOf(BufferAccessPath.Offset(base = BufferAccessPath.ArrayElemOf(arrayPath, indices = setOf()), offset = BigInteger.ZERO)))
        )).killVars(mutVars)
    }

    /**
     * Given a variable [it] try to determine if it is the start of an array segment. A variable may not be actually qualified
     * with the ArrayElemStart qualifier, but can be proved to have that role.
     * For example, a variable may be an offset 0 from an ArrayElemStart variable, in which case [it] can be treated as such.
     */
    private fun extractEncodedElemStart(decoderState: State, it: TACSymbol.Var, s: PointsToDomain): AbstractDomain.BufferIndices.ArrayElemStart? {
        return (decoderState[it] as? AbstractDomain.BufferIndices.ArrayElemStart)
                // TODO: we retired having OffsetFrom array elements, so this is dead code???
                ?: (decoderState[it] as? AbstractDomain.BufferIndices.OffsetFrom)?.takeIf {
                    it.offset == BigInteger.ZERO
                }?.from?.let(decoderState::get)?.let {
                    it as? AbstractDomain.BufferIndices.ArrayElemStart
                    /* A variable (mistagged) as an offset at 32 from a dynamic start we have deduced to actually hold an array */
                } ?: (decoderState[it] as? AbstractDomain.BufferIndices.OffsetFrom)?.takeIf {
                    it.offset == EVM_WORD_SIZE
                }?.takeIf {
                    decoderState.get(it.from)?.let { it as? AbstractDomain.BufferIndices.DynamicStart }?.mustBeArray == true
                }?.let {
                    AbstractDomain.BufferIndices.ArrayElemStart(
                            bufferVar = it.bufferVar,
                            basePointer = it.from,
                            indexVars = s.variablesEqualTo(BigInteger.ZERO)
                    )
                    /* An array element that is provably at index 0, aka the start of the array segment */
                } ?: (decoderState[it] as? AbstractDomain.BufferIndices.ArrayElem)?.takeIf {
                    it.constIndex == BigInteger.ZERO
                }?.let {
                    AbstractDomain.BufferIndices.ArrayElemStart(
                            bufferVar = it.bufferVar,
                            basePointer = it.basePointer,
                            indexVars = it.indexVars
                    )
                }
    }

    /**
     * Given a value read from [v], determine the buffer access path of the value being read. This is achieved
     * by recursively tracing the qualifier of [v] back until we reach a DynamicStart, which is tagged with a buffer access path.
     * The path through qualifiers taken to the dynamic start determines how the [analysis.pta.abi.DecoderAnalysis.AbstractDomain.BufferIndices.DynamicStart]'s
     * buffer access path should be extended to yield the buffer access path of [v].
     *
     * If no path can be extracted, then this function will return null. Otherwise, it returns a pair of the extract path,
     * and the variable that holds the dynamic start that the trace terminated at (or null, if no such dynamic start exists,
     * which can happen with strides at the root).
     *
     * The function [pathProcessCont] is a continuation that is responsible for recording the steps through qualifiers
     * in the heap (rather than through the call stack) and may be given a non-default value by external callers to adjust
     * how the final results of the function are presented.
     */
    tailrec fun extractPathForRead(
        v: TACSymbol.Var,
        m: Map<TACSymbol.Var, AbstractDomain>,
        pathProcessCont: (TACSymbol.Var?, BufferAccessPath) -> Pair<TACSymbol.Var?, BufferAccessPath> = { k, vv ->
            @Suppress("unused")
            k to vv
        }
    ) : Pair<TACSymbol.Var?, BufferAccessPath>? {
        val aVal = m[v].let { it as? AbstractDomain.BufferIndices } ?: return null
        return when(aVal) {
            is AbstractDomain.BufferIndices.OffsetFrom -> {
                /* Trace back to the parent of the offset */
                extractPathForRead(aVal.from, m) { where, p ->
                    /*
                      Extend the path generated by the recursive call with the offset, and plumb through where
                     */
                    pathProcessCont(where, BufferAccessPath.Offset(offset = aVal.offset, base = p))
                }
            }
            is AbstractDomain.BufferIndices.DynamicStart -> {
                /*
                 We are done, pass in the current variable v and the BAP attached to this object.
                 */
                pathProcessCont(v, aVal.path)
            }
            is AbstractDomain.BufferIndices.ArrayElem,
            is AbstractDomain.BufferIndices.ArrayElemStart -> {
                check(aVal is ArrayElementIndexTracking)
                val bP = (aVal as WithArrayBase).basePointer
                /* recurse to the parent array (this will always be a DynamicStart, but we choose uniformity
                of implementation)
                 */
                extractPathForRead(bP, m) { where, p ->
                    /*
                       extend the path from the recursive call with an array elem component
                     */
                    pathProcessCont(where, BufferAccessPath.ArrayElemOf(p, indices = aVal.indexVars + aVal.constIndex?.asTACSymbol()?.let(::listOf).orEmpty()))
                }
            }
            is AbstractDomain.BufferIndices.StridingPointer -> {
                val callback =
                    // extend the path we get from the parent with the steps encoded in this path.
                    { where: TACSymbol.Var?, p: BufferAccessPath ->
                        val looped = aVal.toFullPath().let { fp ->
                            fp.path.fold(BufferAccessPath.Offset(offset = aVal.stridePath.root, base = p) as BufferAccessPath) { Curr, s ->
                                when (s) {
                                    is StrideStep.StrideLoop -> BufferAccessPath.StaticStride(
                                            strideBy = s.c,
                                            /* a stride in a buffer acess path must always follow an offset */
                                            parent = if (Curr is BufferAccessPath.StaticStride) {
                                                BufferAccessPath.Offset(base = Curr, offset = BigInteger.ZERO)
                                            } else {
                                                Curr
                                            }
                                    )
                                    is StrideStep.ConstStep -> {
                                        /*
                                          We shouldn't have two constant steps immediately after each other, this would
                                          violate a representation invariant of stride steps
                                         */
                                        ptaInvariant(Curr is BufferAccessPath.StaticStride) {
                                            "Have a consant step $s being appended after another consant step $Curr"
                                        }
                                        BufferAccessPath.Offset(
                                                offset = s.c,
                                                base = p
                                        )
                                    }
                                }
                            }
                        }
                        pathProcessCont(where, looped)
                    }
                when(aVal.from) {
                    is StrideRoot.DynamicObject -> {
                        extractPathForRead(aVal.from.parent, m, callback)
                    }
                    is StrideRoot.BufferRoot ->
                        /* there is no variable (and no parent dynamicstart) here, we are striding along the root */
                        callback(null, BufferAccessPath.Root)
                    is StrideRoot.ArrayElement -> {
                        extractPathForRead(aVal.from.parent, m) { p, b ->
                            callback(p, BufferAccessPath.ArrayElemOf(
                                    parent = b,
                                    indices = aVal.from.indices
                            ))
                        }
                    }
                }
            }
            is AbstractDomain.BufferIndices.ElementOffset -> {
                extractPathForRead(aVal.parent, m) { p, bufferAccessPath ->
                    /*
                     Add an offset and a array elem to the path generated by the recursive call
                     */
                    pathProcessCont(p, BufferAccessPath.Offset(
                            offset = aVal.offset,
                            base = BufferAccessPath.ArrayElemOf(
                                    parent = bufferAccessPath,
                                    indices = aVal.indices
                            )
                    ))
                }
            }
        }
    }

    fun getBasePointersFor(p: TACSymbol.Var, w: PointsToDomain) : AllocationAnalysis.AbstractLocation? {
        return w.pointsToState.store[p]?.let {
            it as? InitializationPointer
        }?.initAddr
    }

    private interface WithArrayBase {
        val basePointer : TACSymbol.Var
        val bufferVar : TACSymbol.Var
    }

    /**
     * The abstract value attached to an abstract address being initialized. The qualifiers in [Unresolved] track the
     * (incrementally) populated path information of the initializing object. Once the associated address finishes (and assuming
     * it has not been killed along the way) the [BufferObject] representing that path information is transformed into an [analysis.pta.abi.DecoderAnalysis.AbstractDomain.PointerQualifier.FinalizedPointer]
     * qualifier (see [popAllocation])
     */
    sealed class InitAddressQualifier {

        sealed class Unresolved : InitAddressQualifier() {
            abstract fun toFinalized(): AbstractDomain.PointerQualifier.FinalizedPointer?

            data class ArrayInitPointerFor(override val bufferVar: TACSymbol.Var, val elemType: BufferObject?) : Unresolved() {
                override fun toFinalized(): AbstractDomain.PointerQualifier.FinalizedPointer? {
                    if (elemType == null) {
                        return null
                    }
                    return AbstractDomain.PointerQualifier.FinalizedPointer.ArrayPointerFor(
                            bufferVar = bufferVar,
                            elems = elemType
                    )
                }

                override fun toType(): BufferObject? {
                    return BufferObject.Array(elemType ?: return null)
                }
            }

            data class StructInitPointer(val contents: BufferObject.Struct.StructContents, override val bufferVar: TACSymbol.Var) : Unresolved() {
                override fun toFinalized(): AbstractDomain.PointerQualifier.FinalizedPointer {
                    return AbstractDomain.PointerQualifier.FinalizedPointer.StructPointerFor(
                            bufferVar = bufferVar,
                            contents = contents
                    )
                }

                override fun toType(): BufferObject {
                    return BufferObject.Struct(contents)
                }
            }

            abstract val bufferVar: TACSymbol.Var
            abstract fun toType(): BufferObject?
            @Suppress("unused")
            val referencedVars: Collection<TACSymbol.Var> get() = listOf(bufferVar)
        }

        /**
         * we cannot (or could not) prove that the associated address was a proper decoding, so remember that we should
         * never track path information for this function in the future
         */
        object Kill : InitAddressQualifier()
    }

    interface ArrayElementIndexTracking : WithIndexing<AbstractDomain> {
        override val untilEndVars: Set<TACSymbol.Var>
            get() = setOf()

        fun sameIndexAs(v: Set<TACSymbol>) : Boolean {
            if(v.containsAny(this.indexVars)) {
                return true
            }
            return this.constIndex?.asTACSymbol()?.let(v::contains) ?: false
        }
    }

    interface Filterable<T: AbstractDomain> {
        /**
         * Indicates that if an abstract object mentions a variable [v],
         * the abstract fact does not need to be discarded, simply all references to [v]
         * removed. If such a removal would render this fact invalid (or useless) this returns null
         */
        fun removeReference(v: TACSymbol.Var) : T?
    }

    sealed class AbstractDomain {
        abstract val referencedVars: Collection<TACSymbol.Var>

        /**
         * qualifiers that describe locations within a buffer that is being decoded. This is
         * in contrast to [analysis.pta.abi.DecoderAnalysis.AbstractDomain.PointerQualifier.FinalizedPointer]
         * which describe variables that point to decoded objects.
         */
        sealed class BufferIndices : AbstractDomain() {
            final override val referencedVars: Collection<TACSymbol.Var>
                get() = listOf(bufferVar) + _referencedVars
            abstract protected val _referencedVars : Collection<TACSymbol.Var>
            /**
              Which buffer is this index for? If the buffer is in memory then this is the variable holding
             the base pointer of the array, otherwise for calldata decoding it is [TACKeyword.CALLDATA]
             */
            abstract val bufferVar : TACSymbol.Var

            /**
             * An offset from some object. [from] is expected to always be a [DynamicStart] object (although this
             * is not enforced in an object invariant).
             */
            data class OffsetFrom(val from: TACSymbol.Var, override val offset: BigInteger, override val bufferVar: TACSymbol.Var) : BufferIndices(), FieldPointer {
                override val _referencedVars: Collection<TACSymbol.Var> = setOf(from)
            }

            /**
             * An offset within some element. [parent] here is the [DynamicStart] corresponding to the beginning of the array.
             * The element itself is identified by the *logical* index information provided by [indices]. that is,
             * ElementOffset(parent = x, offset = 32, indices = { y }) indicates an offset of 32 within the element
             * at logical index y within the encoded array at x.
             *
             * In the [EncoderAnalysis] we do not differentiate between offsets within an array element and within a dynamic start:
             * in that analysis the different elements are not distinguished from one another. We take care to attach this
             * indexing information (and to differentiate offsets at different indices) because these offsets
             * can be used as a root for an object path, or for the root of a decoding: it is crucial that we can precisely
             * determine *which* element of an array is being decoded.
             *
             * (The alternative would be to make [indices] an optional field for [OffsetFrom] but that is pretty ugly)
             */
            data class ElementOffset(
                    val parent: TACSymbol.Var,
                    override val offset: BigInteger,
                    val indices: Set<TACSymbol>,
                    override val bufferVar: TACSymbol.Var
            ) : ArrayElementIndexTracking, BufferIndices(), Filterable<ElementOffset>, FieldPointer {
                override val constIndex: BigInteger?
                    get() = indices.mapNotNull { it as? TACSymbol.Const }.uniqueOrNull()?.value
                override val indexVars: Set<TACSymbol.Var> by lazy {
                    indices.filterIsInstance<TACSymbol.Var>().toSet()
                }
                override val _referencedVars: Collection<TACSymbol.Var>
                    get() = indexVars + parent

                override fun withVars(addIndex: Iterable<TACSymbol.Var>, addUntilEnd: Iterable<TACSymbol.Var>): AbstractDomain {
                    return this.copy(
                            indices = this.indices + addIndex
                    )
                }

                override fun removeReference(v: TACSymbol.Var): ElementOffset? {
                    /*
                    This becomes useless.
                     */
                    if(v == parent || v == bufferVar) {
                        return null
                    }
                    return this.copy(
                            indices = this.indices.filter {
                                it != v
                            }.toSet()
                    )
                }
            }

            /**
             * A dynamic object (i.e., struct or array) that starts at the buffer access path [path].
             */
            data class DynamicStart(
                    val path: BufferAccessPath,
                    /**
                     * This dynamic start must be an array. We can determine this at creation time if we are decoding calldata
                     * and we have good type hints, or if we see a value read from this dynamic start written into the length
                     * field of an initializing pointer
                     */
                    val mustBeArray: Boolean,
                    override val bufferVar: TACSymbol.Var,
                    /**
                     * Is the type of this object known? We can determine this at creation time if we are decoding
                     * calldata and have type hints.
                     */
                    val knownType: HeapType?,
                    /**
                     * Non-null only for dynamic starts that have [mustBeArray] = true. Provides a bound on the length
                     * (serving the same role as [ArrayStateAnalysis.Value.ArrayBasePointer.logicalLength]
                     * but for an array in an encoded buffer)
                     */
                    val lengthBound: IntValue?,
                    /**
                     * When iterating during copying, what is the stride we expect to see. For bytes arrays, this is
                     * [EVM_WORD_SIZE], for all other arrays it is the element types' [sizeInArray] (see [elemStrideSize])
                     */
                    val strideSize: BigInteger?,
                    /**
                     * If this is an array, what is the variable pointing to an in-memory array
                     * that will (eventually) hold this encoded array's data.
                     */
                    val arrayFor: TACSymbol.Var?
            ) : BufferIndices() {
                init {
                    check(path is BufferAccessPath.Deref)
                }
                override val _referencedVars: Collection<TACSymbol.Var>
                    get() = listOf()
            }
            data class ArrayElemStart(
                    override val basePointer: TACSymbol.Var,
                    override val bufferVar: TACSymbol.Var,
                    override val indexVars: Set<TACSymbol.Var>
            ) : BufferIndices(), WithArrayBase, ArrayElementIndexTracking, Filterable<ArrayElemStart> {
                override val _referencedVars: Collection<TACSymbol.Var>
                    get() = listOf(basePointer) + indexVars

                override val constIndex: BigInteger?
                    get() = BigInteger.ZERO

                override fun withVars(addIndex: Iterable<TACSymbol.Var>, addUntilEnd: Iterable<TACSymbol.Var>): AbstractDomain {
                    return this.copy(
                            indexVars = this.indexVars + addIndex
                    )
                }

                override fun removeReference(v: TACSymbol.Var): ArrayElemStart? {
                    if(v == basePointer || v == bufferVar) {
                        return null
                    }
                    return this.copy(
                            indexVars = this.indexVars - v
                    )
                }
            }

            /**
             * An element for an array at [basePointer] in buffer [bufferVar] with (a potentially) constant index
             * [constIndex] and set of index [indexVars]
             */
            data class ArrayElem(
                    override val basePointer: TACSymbol.Var,
                    override val bufferVar: TACSymbol.Var,
                    override val constIndex: BigInteger?,
                    override val indexVars: Set<TACSymbol.Var>
            ) : BufferIndices(), WithArrayBase, ArrayElementIndexTracking, Filterable<ArrayElem> {
                override val _referencedVars: Collection<TACSymbol.Var>
                    get() = listOf(basePointer) + indexVars

                override fun withVars(addIndex: Iterable<TACSymbol.Var>, addUntilEnd: Iterable<TACSymbol.Var>): AbstractDomain {
                    return this.copy(
                            indexVars = this.indexVars + addIndex
                    )
                }

                override fun removeReference(v: TACSymbol.Var): ArrayElem? {
                    if(this.basePointer == v || v == this.bufferVar) {
                        return null
                    }
                    return this.copy(
                            indexVars = this.indexVars - v
                    )
                }
            }

            data class StridingPointer(
                val from: StrideRoot,
                override val stridePath: StridePath,
                override val innerOffset: BigInteger,
                override val bufferVar: TACSymbol.Var,
                override val strideBy: BigInteger,
                override val indexVars: Set<TACSymbol.Var>
            ) : BufferIndices(), StridingQualifier, WithIndexing<AbstractDomain>, Filterable<StridingPointer> {
                override val _referencedVars: Collection<TACSymbol.Var>
                    get() = indexVars + from.referencedVars

                override val constIndex: BigInteger?
                    get() = null
                override val untilEndVars: Set<TACSymbol.Var>
                    get() = setOf()

                override fun removeReference(v: TACSymbol.Var): StridingPointer? {
                    if(v == bufferVar || v in from.referencedVars) {
                        return null
                    }
                    return this.copy(
                            indexVars = this.indexVars - v
                    )
                }

                override fun withVars(addIndex: Iterable<TACSymbol.Var>, addUntilEnd: Iterable<TACSymbol.Var>): StridingPointer {
                    return this.copy(
                            indexVars = this.indexVars + addIndex
                    )
                }
            }
        }

        /**
         * The value qualified with this has been read from [index] in [bufferVar], [index] could be a constant index
         * which will happen for calldata decoding.
         */
        data class ReadFrom(val index: TACSymbol, val bufferVar: TACSymbol.Var) : AbstractDomain() {
            override val referencedVars: Collection<TACSymbol.Var> = listOfNotNull(index as? TACSymbol.Var, bufferVar)
        }
        sealed class PointerQualifier : AbstractDomain() {
            sealed class FinalizedPointer : PointerQualifier() {
                override val referencedVars: Collection<TACSymbol.Var>
                    get() = listOf(
                            bufferVar
                    )
                abstract val bufferVar: TACSymbol.Var
                abstract fun toType(): BufferObject
                data class StructPointerFor(
                        override val bufferVar: TACSymbol.Var,
                        val contents: BufferObject.Struct.StructContents
                ) : FinalizedPointer() {
                    override fun toType(): BufferObject = BufferObject.Struct(contents)
                }

                data class ArrayPointerFor(
                        val elems: BufferObject,
                        override val bufferVar: TACSymbol.Var
                ) : FinalizedPointer() {
                    override fun toType(): BufferObject = BufferObject.Array(elems)
                }
            }
        }
    }

    /**
     * A domain that is the union of the constant lattice, [AbstractDomain] and
     * a singleton domain that indicates the value has a "relevant" int qualifier.
     *
     * Used to implement abstract addition semantics over a single domain (and make
     * type signatures more friendly)
     */
    private sealed class InjectedDomain {
        data class Constant(val x: BigInteger) : InjectedDomain()
        data class Wrapped(val w : AbstractDomain) : InjectedDomain()
        object Qualified : InjectedDomain()
    }

    /**
     * When "bootstrapping" the decoding process, we need to detect when a value read from a constant root index is added
     * to the start location of the encoded buffer. This type represents, for some value read from a buffer:
     * 1. The symbols to which it must be added to yield a dynamic start
     * 2. The constant, top-level index of the dynamic object the above addition would yield, and
     * 3. (If available) the type of the dynamic object
     */
    private data class InitialAddTarget(
            val addTargets: Collection<TACSymbol>,
            val rootIndex: BigInteger,
            val resultType: HeapType?
    )

    private val abstractSemantics = object : AbstractAbstractInterpreter<PointsToDomain, State>() {
        private val expressionSemantics = object : AbstractExpressionCaseInterpreter<State, PointsToDomain>() {
            override fun forget(lhs: TACSymbol.Var, toStep: State, input: State, whole: PointsToDomain, wrapped: LTACCmd): State {
                return toStep.killVar(lhs)
            }

            override fun stepAssignBWAnd(lhs: TACSymbol.Var, o1: TACSymbol, o2: TACSymbol, toStep: State, input: State, whole: PointsToDomain, l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): State {
                val ops = listOf(o1, o2)
                /*
                Ignore "cleaning" of read values. For example, the decoder will "clean" addresses to clear out the upper bits.
                 */
                val readFromOp = ops.mapNotNull {
                    toStep[it] as? AbstractDomain.ReadFrom
                }.singleOrNull() ?: return super.stepAssignBWAnd(lhs, o1, o2, toStep, input, whole, l)

                /*
                 This check is slightly imprecise: ideally we would check that the mask being applied is:
                 a. clearing out upper bits, and b
                 b. the right length for the type being decoded

                 absent type information, b. is impossible to get, so we just don't even bother
                 TODO(jtoman): bother?
                 */
                return if(ops.any {
                            it is TACSymbol.Const
                        }) {
                    toStep.copy(
                            qualifiers = toStep.qualifiers + (lhs to readFromOp)
                    )
                } else {
                    super.stepAssignBWAnd(lhs, o1, o2, toStep, input, whole, l)
                }
            }

            override fun stepAssignVar(lhs: TACSymbol.Var, s: TACSymbol.Var, toStep: State, input: State, whole: PointsToDomain, l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): State {
                /*
                 * Recognize that a read of a split calldasta argument is equivalent to reading from a constant index in the
                 * calldata buffer
                 */
                if(s.meta.containsKey(TACMeta.IS_CALLDATA)) {
                    /*
                     Only do this if we suspect the read index corresponds to a calldata argument, i.e., the read
                     index is offset from a multiple of word size by the size of the sighash (4)
                     */
                    val idx = s.meta.find(TACMeta.CALLDATA_OFFSET)?.takeIf {
                        it.mod(EVM_WORD_SIZE) == DEFAULT_SIGHASH_SIZE
                    } ?: return super.stepAssignVar(lhs, s, toStep, input, whole, l)
                    return toStep.assign(lhs, AbstractDomain.ReadFrom(
                            bufferVar = TACKeyword.CALLDATA.toVar(),
                            index = (idx - DEFAULT_SIGHASH_SIZE).asTACSymbol()
                    ))
                }
                val dyn = input[s]?.let {
                    it as? AbstractDomain.BufferIndices.DynamicStart
                }
                /*
                 Do not copy dynamic starts, instead, model it as an offset at 0
                 */
                if(dyn != null) {
                    return toStep.assign(lhs, AbstractDomain.BufferIndices.OffsetFrom(
                            from = s,
                            offset = BigInteger.ZERO,
                            bufferVar = dyn.bufferVar
                    ))
                }
                /*
                 * What on EARTH?
                 *
                 * usually striding pointer inference is done with FieldPointers. However! If we are striding
                 * at the root of a calldata buffer, then the "striding" will be done by an actual number. We detect
                 * this by discovering we have a DSA loop assignment (i.e., we are assigning to a variable that is
                 * already in the domain) where the new value is constant that is some multiple of the word size
                 * greater than the existing value. Then we assume that we have a stride starting from the original value
                 * (adjusting for the sighash) and striding by the difference between prev and next.
                 */
                if(s !in input && s in whole.boundsAnalysis && lhs in whole.boundsAnalysis) {
                    val next = whole.boundsAnalysis[s]?.let {
                        it as? QualifiedInt
                    }?.takeIf { it.x.isConstant }?.x?.c
                    val prev = whole.boundsAnalysis[lhs]?.let {
                        it as? QualifiedInt
                    }?.takeIf { it.x.isConstant }?.x?.c
                    if(next != null && prev != null && prev < next && next.mod(EVM_WORD_SIZE) == DEFAULT_SIGHASH_SIZE && prev.mod(EVM_WORD_SIZE) == DEFAULT_SIGHASH_SIZE) {
                        return toStep.assign(lhs, AbstractDomain.BufferIndices.StridingPointer(
                                from = StrideRoot.BufferRoot,
                                bufferVar = TACKeyword.CALLDATA.toVar(),
                                indexVars = setOf(),
                                strideBy = next - prev,
                                stridePath = StridePath(root = (prev - DEFAULT_SIGHASH_SIZE), path = listOf()),
                                innerOffset = BigInteger.ZERO
                        ))
                    }
                }
                return (input[s]?.let {
                    toStep.assign(lhs, it)
                } ?: super.stepAssignVar(lhs, s, toStep, input, whole, l))
            }


            private fun interp(o: TACSymbol, input: State, whole: PointsToDomain, where: LTACCmd) : InjectedDomain? {
                return when(o) {
                    is TACSymbol.Const -> InjectedDomain.Constant(o.value)
                    is TACSymbol.Var -> input.qualifiers[o]?.let(InjectedDomain::Wrapped) ?: whole.boundsAnalysis[o]?.let {
                        it as? QualifiedInt
                    }?.qual?.takeIf {
                        /*
                         If we have any calldata qualifiers, record that fact in our domaoin

                         TODO(jtoman): why not just inject the qualifiers directly into the Qualified object...?
                         */
                        it.any {
                            it is CalldataAnnot
                        }
                    }?.let { InjectedDomain.Qualified } ?: numericAnalysis.interpAsUnambiguousConstant(ltacCmd = where, pState = whole, value = o)?.let(InjectedDomain::Constant)
                }
            }

            override fun stepAssignAdd(lhs: TACSymbol.Var, o1: TACSymbol, o2: TACSymbol, toStep: State, input: State, whole: PointsToDomain, l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): State {
                val nondet by lazy {
                    this.forget(lhs, toStep, input, whole, l.wrapped)
                }
                /*
                  If one of the operands of this addition is a read from, and is being added to a variable
                  that is not tracked (or is a constant) then this is a candidate for a "dereference" of an
                  offset stored at the top-level.
                 */
                if(o1 is TACSymbol.Var && input[o1]?.let {
                            it is AbstractDomain.ReadFrom
                        } == true && (o2 !is TACSymbol.Var || o2 !in input)) {
                    return initialOffset(lhs, o1, o2, toStep, whole) ?: nondet
                } else if(o2 is TACSymbol.Var && input[o2]?.let {
                            it is AbstractDomain.ReadFrom
                        } == true && (o1 !is TACSymbol.Var || o1 !in input)) {
                    return initialOffset(lhs, o2, o1, toStep, whole) ?: nondet
                }
                val v1 = interp(o1, toStep, whole, where = l.wrapped) ?: return nondet
                val v2 = interp(o2, toStep, whole, where = l.wrapped) ?: return nondet
                /*
                  For simplicity we prefer having the AbstractDomain function as the first argument. In the case
                  both are members of the abstract domain and v2 is a readfrom we swap the arguments.
                 */
                return if (v1 is InjectedDomain.Wrapped && (v2 !is InjectedDomain.Wrapped || v2.w !is AbstractDomain.ReadFrom)) {
                    internalAdd(lhs, v1.w, (o1 as? TACSymbol.Var) ?: return nondet, v2, o2, input, whole) ?: nondet
                } else if (v2 is InjectedDomain.Wrapped) {
                    internalAdd(lhs, v2.w, (o2 as? TACSymbol.Var) ?: return nondet, v1, o1, input, whole) ?: nondet
                } else {
                    nondet
                }
            }

            private fun initialOffset(lhs: TACSymbol.Var, o1: TACSymbol.Var, o2: TACSymbol, toStep: State, whole: PointsToDomain): State? {
                val readFrom = toStep[o1]!! as AbstractDomain.ReadFrom
                /* Compute the InitialAddTarget, which will determine if this is a dereference of a toplevel offset */
                val (addTargets, offset, knownType) = targetForIndex(readFrom, whole) ?: return null
                /*
                 The other operands is not something we expect to be adding to for a dereference: give up
                 */
                if(o2 !in addTargets) {
                    logger.debug {
                        "Thought that addition to $lhs was an initial offset, expected addition to $addTargets, but got $o2"
                    }
                    return null
                }
                val mustBeArray = knownType?.let { it is HeapType.ByteArray || it is HeapType.Array } ?: false
                return toStep.assign(lhs, AbstractDomain.BufferIndices.DynamicStart(
                        bufferVar = readFrom.bufferVar,
                        path = BufferAccessPath.Deref(BufferAccessPath.Offset(offset, BufferAccessPath.Root)),
                        mustBeArray = mustBeArray,
                        knownType = knownType,
                        lengthBound = IntValue.Nondet.takeIf { mustBeArray },
                        strideSize = knownType?.let(Companion::elemStrideSize),
                        arrayFor = null
                ))
            }

            /*
             * TODO(jtoman): this logic is redundant w.r.t. [getBufferPath]
             */
            fun targetForIndex(r: AbstractDomain.ReadFrom, whole: PointsToDomain) : InitialAddTarget? {
                if(r.bufferVar == TACKeyword.CALLDATA.toVar()) {
                    if(r.index !is TACSymbol.Const) {
                        return null
                    }
                    val effectiveIndex = r.index.value
                    val targetType = topLevelTypes?.get(effectiveIndex)
                    return InitialAddTarget(listOf(DEFAULT_SIGHASH_SIZE.asTACSymbol()), effectiveIndex, targetType)
                } else {
                    /*
                     Did we read from a constant offset?
                     */
                    val ind = r.index as? TACSymbol.Var ?: return null
                    val arrayPtr = whole.arrayState[ind]?.let {
                        it as? ArrayStateAnalysis.Value.ElementPointer
                    }?.takeIf {
                        it.index.isConstant
                    } ?: return null
                    val elemPointer = mutableSetOf<TACSymbol.Var>()
                    /* Find all pointers to the beginning of the data segment, this is the beginning of the serialized data */
                    whole.arrayState.entries.mapNotNullTo(elemPointer) { (k, v) ->
                        k.takeIf {
                            v is ArrayStateAnalysis.Value.ElementPointer && v.index.mustEqual(BigInteger.ZERO) && v.arrayPtr.containsAny(arrayPtr.arrayPtr)
                        }
                    }
                    if(elemPointer.isEmpty()) {
                        return null
                    }
                    return InitialAddTarget(elemPointer, arrayPtr.index.c, null)
                }
            }

            private fun internalAdd(
                lhs: TACSymbol.Var,
                v1: AbstractDomain,
                o1: TACSymbol.Var,
                v2: InjectedDomain,
                o2: TACSymbol,
                m: State,
                reduced: PointsToDomain
            ) : State? {
                fun AbstractDomain.andAssign() = m.assign(lhs, this)
                return when(v1) {
                    /*
                       We know that we don't have to worry about crossing over into the next element of an array here
                       because offsets within array elements are represented by [ElemOffset]
                     */
                    is AbstractDomain.BufferIndices.OffsetFrom -> {
                        if(v2 is InjectedDomain.Constant) {
                            v1.copy(offset = (v2.x + v1.offset)).andAssign()
                        } else {
                            null
                        }
                    }
                    is AbstractDomain.BufferIndices.DynamicStart -> {
                        if(v1.mustBeArray && v2 is InjectedDomain.Constant && v2.x == 32.toBigInteger()) {
                            AbstractDomain.BufferIndices.ArrayElemStart(
                                    basePointer = o1,
                                    bufferVar = v1.bufferVar,
                                    indexVars = reduced.variablesEqualTo(BigInteger.ZERO)
                            ).andAssign()
                        } else if(v1.mustBeArray) {
                            if(v2 !is InjectedDomain.Constant) {
                                null.andDebug<State?> {
                                    "Adding a non-constant $o2 to the start of the array at $o1"
                                }
                            } else if(v2.x < EVM_WORD_SIZE) {
                                null.andDebug<State?> {
                                    "Adding a too small constant ${v2.x} ($o2) to array at $o1"
                                }
                            } else if(v1.strideSize == null) {
                                null.andDebug<State?> {
                                    "Have not yet resolved stride size of $v1 @ $o1"
                                }
                            } else {
                                val offsetWithinElemSegment = (v2.x - EVM_WORD_SIZE)
                                val effectiveIndex = offsetWithinElemSegment / v1.strideSize
                                val innerOffset = offsetWithinElemSegment.mod(v1.strideSize)
                                // this will probably not work with bytes, but like, who cares???
                                if(innerOffset.mod(EVM_WORD_SIZE) != BigInteger.ZERO) {
                                    null
                                } else if(innerOffset == BigInteger.ZERO) {
                                    AbstractDomain.BufferIndices.ArrayElem(
                                            basePointer = o1,
                                            bufferVar = v1.bufferVar,
                                            constIndex = effectiveIndex,
                                            indexVars = reduced.variablesEqualTo(effectiveIndex)
                                    ).andAssign()
                                } else {
                                    AbstractDomain.BufferIndices.ElementOffset(
                                            bufferVar = v1.bufferVar,
                                            parent = o1,
                                            indices = reduced.variablesEqualTo(effectiveIndex) + effectiveIndex.asTACSymbol(),
                                            offset = innerOffset
                                    ).andAssign()
                                }
                            }
                        } else if(v2 is InjectedDomain.Constant) {
                            /*
                             As in the encoder case, we pretty heavily assume that arrays lengths are read (and then
                             written in an initializing array object) before any further computation. Thus if we are
                             adding here we can be pretty certain that the dynamic start is not an array and thus
                             the result here is an offset

                             (but we don't make this is a hard assumption, and do some post-hoc cleanup if necessary)
                             */
                            AbstractDomain.BufferIndices.OffsetFrom(from = o1, offset = v2.x, bufferVar = v1.bufferVar).andAssign()
                        } else {
                            null
                        }
                    }
                    is AbstractDomain.BufferIndices.ArrayElemStart,
                    is AbstractDomain.BufferIndices.ArrayElem -> {
                        /*
                        We *really* don't want to throw away array element start information.
                         */
                        if(v1 is AbstractDomain.BufferIndices.ArrayElemStart && v2 is InjectedDomain.Constant && v2.x == BigInteger.ZERO) {
                            return v1.andAssign()
                        }
                        check(v1 is WithArrayBase && v1 is ArrayElementIndexTracking)
                        /*
                         * Are we adding a sate array offset (as detected by the numeric analysis)
                         * to the start of the array element segment. If so, then we generate an ArrayElem
                         */
                        if(v2 is InjectedDomain.Qualified) {
                            val addStarts = reduced.boundsAnalysis[o2]?.let {
                                it as? QualifiedInt
                            }?.qual?.filterIsInstance<IntQualifier.SafeCalldataOffset>()?.singleOrNull {
                                v1 is AbstractDomain.BufferIndices.ArrayElemStart && it.calldataArrayVar == v1.basePointer
                            }
                            if (addStarts != null) {
                                return AbstractDomain.BufferIndices.ArrayElem(
                                        basePointer = v1.basePointer,
                                        indexVars = setOf(addStarts.index),
                                        bufferVar = v1.bufferVar,
                                        constIndex = null
                                ).andAssign()
                            }
                        }
                        /*
                          Otherwise we only expect to see constants
                         */
                        if(v2 !is InjectedDomain.Constant) {
                            logger.debug {
                                "Found non-constant addition $v2 ($o2) to array element $v1 ($o1)"
                            }
                            return null
                        }

                        /*
                         Is the constant we're adding exactly the size of a stride within the array (i.e., the
                         amount we step after fully reading an element? If so, generate another arrayelem
                         object, with updated index information
                         */
                        val dynamicStart = m[v1.basePointer]?.let {
                            it as? AbstractDomain.BufferIndices.DynamicStart
                        }?.takeIf { it.mustBeArray }
                        if(dynamicStart?.knownType == HeapType.ByteArray) {
                            // no offset stride stuff here
                            val constIdx = v1.constIndex?.let {
                                it + v2.x
                            }
                            val symbolicIdx = v1.indexVars.flatMapTo(treapSetBuilderOf()) { idx ->
                                (reduced.invariants matches {
                                    v("new_idx") `=` idx + v2.x
                                }).map {
                                    (it.symbols["new_idx"] as LVar.PVar).v
                                }
                            } + constIdx?.let { cIdx ->
                                reduced.variablesEqualTo(cIdx)
                            }.orEmpty()
                            return AbstractDomain.BufferIndices.ArrayElem(
                                bufferVar = v1.bufferVar,
                                basePointer = v1.basePointer,
                                indexVars = symbolicIdx,
                                constIndex = constIdx
                            ).andAssign()
                        }
                        if(dynamicStart?.strideSize == v2.x) {
                            val nxtInd = v1.constIndex?.plus(BigInteger.ONE)
                            AbstractDomain.BufferIndices.ArrayElem(
                                    bufferVar = v1.bufferVar,
                                    basePointer = v1.basePointer,
                                    constIndex = nxtInd,
                                    indexVars = nxtInd?.let { reduced.variablesEqualTo(nxtInd) } ?: setOf()
                            ).andAssign()
                        } else {
                            /*
                             Otherwise record the offset. The parent here is again not the element we're generating the offset
                             from, but the array itself
                             */
                            AbstractDomain.BufferIndices.ElementOffset(
                                    bufferVar = v1.bufferVar,
                                    offset = v2.x,
                                    parent = v1.basePointer,
                                    indices = setOfNotNull<TACSymbol>(v1.constIndex?.asTACSymbol()) + v1.indexVars
                            ).andAssign()
                        }
                    }
                    is AbstractDomain.ReadFrom -> {
                        /*
                          What on earth? Due to the "optimizer", solidity may do something insane like:
                          offsetToArray = mem[someField]
                          arrayStart = parent + offsetToArray
                          t = parent + 32
                          actuallyArrayElemStart = offsetToArray + t

                          The above doesn't match the expected syntactic pattern for computing an array element start,
                          but luckily the invariants maintained by the encoder analysis can prove that actuallyArrayElemStart
                          is 32 + arrayStart, which let's us recognize this *insane* pattern.

                          Q: Why doesn't the 32 commute heuristic in PointerSimplification handle this?
                          A: That commute heuristic assumes that the intermediate value t isn't used anywhere else. But
                          the solidity compiler may use it because, surprise, it is also the second field of the object at
                          parent!
                         */
                        val linearTerm = LinearTerm.lift(TACExpr.Vec.Add(o1.asSym(), o2.asSym()))
                        if(linearTerm != null) {
                            val st = m.entries.filter { (_, d) -> d is AbstractDomain.BufferIndices.DynamicStart && d.mustBeArray }.mapNotNull {
                                if(reduced.invariants implies {
                                            !it.key + 32 `=` linearTerm
                                        }) {
                                    AbstractDomain.BufferIndices.ArrayElemStart(
                                            basePointer = it.key,
                                            bufferVar = (it.value as AbstractDomain.BufferIndices).bufferVar,
                                            indexVars = reduced.variablesEqualTo(BigInteger.ZERO)
                                    )
                                } else {
                                    null
                                }
                            }.singleOrNull()?.andAssign()
                            if(st != null) {
                                return st
                            }
                        }
                        if(v2 !is InjectedDomain.Wrapped) {
                            return null.andDebug<State?> { "Trying to add a read-from value $v1 ($o1) to an untracked value $v2 ($o2)" }
                        }
                        /*
                           The solidity compiler checks the bounds of arrays (MOSTLY, see https://medium.com/certora/memory-isolation-violation-in-deserialization-code-certora-bug-disclosure-aece7cd00562).
                           It does this by computing the end location of the array segment and comparing that to the known end of the array segment.

                           Just one problem: for bytes arrays this looks at LOT like computing a dynamic child based on the length field,
                           which confuses (and ultimately tanks) the analysis. So we just detect this exact pattern: adding
                           the read of the length field to the array elem start of an encoded bytes array, and explicitly return null,
                           indicating "do not track this value"
                         */
                        if(v2.w is AbstractDomain.BufferIndices.ArrayElemStart && v2.w.basePointer == v1.index && m[v1.index]?.let {
                                    it as? AbstractDomain.BufferIndices.DynamicStart
                                }?.let {
                                    (it.arrayFor != null && pointerSem.getElementSize(it.arrayFor, reduced.pointsToState) == BigInteger.ONE) ||
                                            it.knownType == HeapType.ByteArray
                                } == true) {
                            return null
                        }
                        /*
                          What is the target being added to, and what is the variable holding the dynamic start?
                         */
                        val target = if (v2.w is AbstractDomain.BufferIndices.ArrayElemStart) {
                            v2.w.basePointer
                            /* A dynamic start is not valid if it is an array, we need to be adding to the array elem start */
                        } else if (v2.w is AbstractDomain.BufferIndices.DynamicStart && !v2.w.mustBeArray) {
                            (o2 as? TACSymbol.Var) ?: return null.andDebug<State?> {
                                "non-variable symbol $o2 for dynamic start $v2?"
                            }
                            /* we didn't remove a +0 or we are adding to a copy. Either way, this is still valid (assuming the
                            dynamic start we are a copy of isn't an array), and the dynamic start lives in the source variable.  */
                        } else if(v2.w is AbstractDomain.BufferIndices.OffsetFrom && v2.w.offset == BigInteger.ZERO && m[v2.w.from]?.let {
                                    it is AbstractDomain.BufferIndices.DynamicStart && !it.mustBeArray
                                } == true) {
                            v2.w.from
                        } else {
                            return null.andDebug<State?> {
                                "Addition to read from $o1 of $o2 ($v2) does not match expected pattern"
                            }
                        }

                        val index = (v1.index as? TACSymbol.Var) ?: return null.andDebug<State?> {
                            "read from $v1 ($o1) but the index was not a constant (adding to $target, with op $o2)"
                        }
                        val (dynBase, newPath) = extractPathForRead(index, m) { k, v  ->
                            k to v
                        }?.takeIf {
                            /*
                               remember that the first component of the value returned by extractPath is the dynamic start
                               the trace stopped at, if it is for a different dynamic object than the target computed above,
                               this isn't a valid dereference
                             */
                            it.first == target
                        }?.let { (k, it) ->
                            k to (if(it !is BufferAccessPath.Offset) {
                                /* A deref must always follow an offset */
                                BufferAccessPath.Offset(
                                        base = it,
                                        offset = BigInteger.ZERO
                                )
                            } else {
                                it
                            }).let(BufferAccessPath::Deref)
                        } ?: return null.andDebug<State?> {
                            "Extracting the path from $index for addition of $o1 and $o2 failed"
                        }
                        ptaInvariant(dynBase != null) {
                            "Must have non-null dynBase"
                        }
                        val parentDyn = m[dynBase]!!.let { it as AbstractDomain.BufferIndices.DynamicStart }
                        val nextType = parentDyn.knownType?.let { ty ->
                            if(parentDyn.mustBeArray) {
                                /* We can't have a dynamic object dereference within some offset of an array element */
                                ptaInvariant(newPath.parent is BufferAccessPath.Offset &&
                                    newPath.parent.offset == BigInteger.ZERO &&
                                    newPath.parent.base is BufferAccessPath.ArrayElemOf) {
                                    "Nonsense path to dynamic array element $newPath"
                                }
                                if(ty !is HeapType.Array || !ty.elementType.isDynamic()) {
                                    return null
                                }
                                ty.elementType
                            } else {
                                ptaInvariant((newPath.parent is BufferAccessPath.Offset && newPath.parent.base is BufferAccessPath.Deref) ||
                                        (newPath.parent is BufferAccessPath.Offset && newPath.parent.offset == BigInteger.ZERO &&
                                                newPath.parent.base is BufferAccessPath.StaticStride && newPath.parent.base.strideBy == EVM_WORD_SIZE &&
                                                newPath.parent.base.parent is BufferAccessPath.Offset && newPath.parent.base.parent.offset == BigInteger.ZERO &&
                                                newPath.parent.base.parent.base is BufferAccessPath.Deref)) {
                                    "Nonsense path to dynamic struct element $newPath"
                                }
                                if(ty !is HeapType.OffsetMap) {
                                    return null.andDebug<State?> {
                                        "Next type $ty is not an array, but the dynamic parent $parentDyn isn't known to be an array"
                                    }
                                }
                                if(newPath.parent.base is BufferAccessPath.Deref) {
                                    /* Find the LOGICAL field that corresponds to the offset seen here,
                                       then get the type of that field. (The offset we are dereferencing here
                                       will not necessarily match the field offset because of flattening)
                                     */
                                    var it = BigInteger.ZERO
                                    var fld = BigInteger.ZERO
                                    while(it <  newPath.parent.offset) {
                                        val nextTy = ty.fieldTypes[fld] ?: return null
                                        it += nextTy.sizeInArray()
                                        fld += EVM_WORD_SIZE
                                    }
                                    ty.fieldTypes[fld] ?: return null
                                } else {
                                    ty.fieldTypes.values.monadicFold { Curr, next ->
                                        if(!Curr.checkCompatibility(next)) {
                                            null
                                        } else {
                                            Curr.join(next)
                                        }
                                    } ?: return null
                                }
                            }
                        }
                        val mustBeArray = nextType is HeapType.Array || nextType is HeapType.ByteArray
                        AbstractDomain.BufferIndices.DynamicStart(
                                mustBeArray = mustBeArray,
                                path = newPath,
                                bufferVar = v1.bufferVar,
                                lengthBound = IntValue.Nondet.takeIf { mustBeArray },
                                knownType = nextType,
                                strideSize = nextType?.let(Companion::elemStrideSize),
                                arrayFor = null
                        ).andAssign()
                    }
                    is AbstractDomain.PointerQualifier.FinalizedPointer -> return null
                    is AbstractDomain.BufferIndices.StridingPointer -> {
                        if(v2 !is InjectedDomain.Constant) {
                            return null.andDebug<State?> {
                                "Cannot add non-consant $v2 ($o2) to striding pointer $v1 ($o1)"
                            }
                        }
                        return if(v2.x + v1.innerOffset == v1.strideBy) {
                            v1.copy(innerOffset = BigInteger.ZERO).andAssign()
                        } else if(v2.x + v1.innerOffset < v1.strideBy) {
                            v1.copy(innerOffset = v1.innerOffset + v2.x).andAssign()
                        } else {
                            null.andDebug<State?> {
                                "${v2.x} + ${v1.innerOffset} was beyond the stride limit ${v1.strideBy} ($o1 + $o2)"
                            }
                        }
                    }
                    is AbstractDomain.BufferIndices.ElementOffset -> {
                        if(v2 !is InjectedDomain.Constant) {
                            return null
                        }
                        val strideSize = m[v1.parent]?.let {
                            it as? AbstractDomain.BufferIndices.DynamicStart
                        }?.strideSize
                        if(strideSize != null) {
                            /* If adding to the current offset within an array element gives an element offset
                            * that is exactly the size of the elements within the array, then we actually
                            * have a "fresh" array elem, update indices as appropriate.
                            */
                            if(v1.offset + v2.x == strideSize) {
                                return AbstractDomain.BufferIndices.ArrayElem(
                                        bufferVar = v1.bufferVar,
                                        basePointer = v1.parent,
                                        indexVars = setOf(),
                                        constIndex = v1.constIndex?.plus(BigInteger.ONE)
                                ).andAssign()
                            } else if(v1.offset + v2.x > strideSize) {
                                return null.andDebug<State?> {
                                    "Adding $o1 and $o2, $v1 and $v2 exceed the stride limit"
                                }
                            }
                        }
                        ptaInvariant(strideSize == null || v1.offset + v2.x < strideSize) {
                            "Out of bounds offset for element $v1 & $v2"
                        }
                        v1.copy(
                                offset = v1.offset + v2.x
                        ).andAssign()
                    }
                }
            }
        }

        override val statementInterpreter = object : SimpleStatementInterpreter<State, PointsToDomain>(expressionSemantics) {
            override fun forget(lhs: TACSymbol.Var, toStep: State, input: State, whole: PointsToDomain, l: LTACCmd): State {
                check(lhs !in toStep)
                return toStep
            }

            override fun stepReadMemory(lhs: TACSymbol.Var, loc: TACSymbol, base: TACSymbol.Var, toStep: State, input: State, whole: PointsToDomain, l: LTACCmd): State {
                if(base == TACKeyword.MEMORY.toVar()) {
                    if(loc !is TACSymbol.Var) {
                        return super.stepReadMemory(lhs, loc, base, toStep, input, whole, l)
                    }
                    /*
                      This should be impossible: how did we get here without proving this access was safe???
                     */
                    val arrayState = whole.arrayState[loc]?.let {
                        it as? ArrayStateAnalysis.Value.ElementPointer
                    } ?: return super.stepReadMemory(lhs, loc, base, toStep, input, whole, l)
                    return if(loc in toStep && toStep[loc]!! is AbstractDomain.BufferIndices) {
                        val bufferVar = (toStep[loc]!! as AbstractDomain.BufferIndices).bufferVar
                        /*
                          Only track read from if the array we're reading from matches the buffer our qualifier
                          in loc is for
                         */
                        if(bufferVar !in arrayState.arrayPtr) {
                            super.stepReadMemory(lhs, loc, base, toStep, input, whole, l)
                        } else {
                            toStep.assign(lhs, AbstractDomain.ReadFrom(
                                    index = loc,
                                    bufferVar = bufferVar
                            ))
                        }
                    } else if(arrayState.index.isConstant) {
                        toStep.assign(lhs, AbstractDomain.ReadFrom(
                                index = loc,
                                bufferVar = arrayState.arrayPtr.first()
                        ))
                    } else {
                        super.stepReadMemory(lhs, loc, base, toStep, input, whole, l)
                    }
                } else if(base == TACKeyword.CALLDATA.toVar()) {
                    // TODO(jtoman): why aren't we checking buffer variables here???
                    return toStep.assign(lhs, AbstractDomain.ReadFrom(
                            index = loc.takeIf {
                                it !is TACSymbol.Const || it.value.mod(EVM_WORD_SIZE) == DEFAULT_SIGHASH_SIZE
                            }?.let {
                                if(it is TACSymbol.Const) {
                                    it.copy(it.value - DEFAULT_SIGHASH_SIZE)
                                } else {
                                    it
                                }
                            } ?: return super.stepReadMemory(lhs, loc, base, toStep, input, whole, l),
                            bufferVar = base
                    ))
                } else {
                    return super.stepReadMemory(lhs, loc, base, toStep, input, whole, l)
                }
            }

            override fun stepCommand(l: LTACCmd, toStep: State, input: State, whole: PointsToDomain): State {
                return if(l.cmd is TACCmd.Simple.AssigningCmd.ByteStore) {
                    this.stepWriteMemory(l.narrow(), toStep, input, whole) ?: toStep
                } else if(l.cmd is TACCmd.Simple.ByteLongCopy) {
                    this.stepLongCopy(l.narrow(), toStep, input, whole) ?: toStep
                } else {
                    super.stepCommand(l, toStep, input, whole)
                }
            }

            private fun stepLongCopy(
                    l: LTACCmdView<TACCmd.Simple.ByteLongCopy>,
                    toStep: State,
                    input: State,
                    whole: PointsToDomain
            ) : State? {
                unused(input)
                val offset = (l.cmd.dstOffset as? TACSymbol.Var) ?: return null
                val initAddress = getBasePointersFor(offset, whole) ?: return null
                // TODO(jtoman): should we check arrayFor information for soundness here?
                val clearInit by lazy {
                    logger.info {
                        "Copy at $l failed, clearing $initAddress"
                    }
                    toStep.copy(
                            initQualifiers = toStep.initQualifiers - initAddress
                    )
                }
                // I think in principle we could actually have return data, but solidity doesn't use that for some reason
                if(l.cmd.srcBase != TACKeyword.CALLDATA.toVar()) {
                    return clearInit
                }
                val src = (l.cmd.srcOffset as? TACSymbol.Var) ?: return clearInit
                /*
                 What is the path of the elements we are copying. It is the path of the array for plus a array elem, followed
                 by an offset of 0
                 */
                val p = extractEncodedElemStart(toStep, src, whole)?.basePointer?.let(toStep::get)?.let {
                    it as? AbstractDomain.BufferIndices.DynamicStart
                }?.path?.let {
                    BufferAccessPath.Offset(offset = BigInteger.ZERO, base = BufferAccessPath.ArrayElemOf(
                            parent = it, indices = setOf()
                    ))
                } ?: return clearInit
                /*
                 And that is what *should* be in the array we are copying to
                 */
                return toStep.mergeOrRemove(initAddress, Unresolved.ArrayInitPointerFor(
                        bufferVar = TACKeyword.CALLDATA.toVar(),
                        elemType = BufferObject.Word(setOf(p))
                ))
            }

            /* Almost there gang! */
            private fun stepWriteMemory(
                    where: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>,
                    toStep: State,
                    input: State,
                    whole: PointsToDomain
            ): State? {
                unused(input)
                val write = where.cmd
                if(write.loc !is TACSymbol.Var) {
                    logger.debug {
                        "Write at $where was not to a variable location"
                    }
                    return null
                }
                val initAddress = getBasePointersFor(write.loc, whole) ?: return null
                val clearInitState by lazy {
                    if(initAddress !in toStep.initQualifiers) {
                        toStep
                    } else {
                        // only log if we're actually clearing something
                        logger.info {
                            "Write at $where was not a decode write, clearing address $initAddress"
                        }
                        State(
                            qualifiers = toStep.qualifiers,
                            initQualifiers = toStep.initQualifiers + (initAddress to InitAddressQualifier.Kill)
                        )
                    }
                }
                if(pointerSem.isLengthWrite(write.loc, pState = whole, ltacCmd = where.wrapped) &&
                        write.base == TACKeyword.MEMORY.toVar() &&
                        write.value is TACSymbol.Var &&
                        write.value in toStep) {
                    /*
                      We are writing a random value: this array isn't what we think it is I suppose
                     */
                    val writtenValue = (toStep[write.value]!! as? AbstractDomain.ReadFrom) ?: return clearInitState.andDebug {
                        "${write.value} is not a readFrom: ${toStep[write.value]}"
                    }
                    /* If the value we read from wasn't a dynamic start, then this can't possibly
                    * be a proper encoding: the array length must be the first 32 bytes of an encoded array
                    * */
                    val idx = (writtenValue.index as? TACSymbol.Var)?.takeIf {
                        it in toStep && toStep[it] is AbstractDomain.BufferIndices.DynamicStart
                    } ?: return clearInitState.andDebug {
                        "Write of length ${writtenValue.index} is not from a dynamic start ${toStep[writtenValue.index]}"
                    }
                    val dynStart = toStep[idx]!! as AbstractDomain.BufferIndices.DynamicStart
                    // mark the base pointer with this length field as being an array init pointer for a path in the decoded buffer
                    val newState = toStep.initQualifiers.mutate {
                        it.merge(initAddress, Unresolved.ArrayInitPointerFor(
                                elemType = null,
                                bufferVar = writtenValue.bufferVar
                        )) { a, b ->
                            if(a !is Unresolved.ArrayInitPointerFor || b !is Unresolved.ArrayInitPointerFor) {
                                null.andInfo<InitAddressQualifier?> {
                                    "Write to $initAddress @ $where failed, mismatched init sorts $b vs. $a"
                                }
                            } else if(a.bufferVar != writtenValue.bufferVar) {
                                @Suppress("KotlinConstantConditions")
                                null.andInfo<InitAddressQualifier?> {
                                    "Write to $initAddress @ $where failed, buffer variables did not match $a vs. $b"
                                }
                            } else {
                                Unresolved.ArrayInitPointerFor(
                                        elemType = a.elemType?.let {
                                            if(b.elemType != null && b.elemType != a.elemType) {
                                                return@merge null.andInfo<InitAddressQualifier?> {
                                                    "Write to $initAddress @ $where failed, element types did not match $b vs. $a"
                                                }
                                            }
                                            it
                                        } ?: b.elemType,
                                        bufferVar = writtenValue.bufferVar
                                )
                            }
                        }
                    }
                    val quals = mutableMapOf<TACSymbol.Var, AbstractDomain>(idx to AbstractDomain.BufferIndices.DynamicStart(
                            path = dynStart.path,
                            mustBeArray = true,
                            bufferVar = dynStart.bufferVar,
                            lengthBound = dynStart.lengthBound,
                            knownType = dynStart.knownType,
                            strideSize = dynStart.knownType?.let(::elemStrideSize),
                            arrayFor = write.loc // update that we know what array this dynamic start corresponds to
                    ))
                    toStep.qualifiers.keysMatching { _, k ->
                        /*
                          Oops, we "pre-computed" the ArrayElemStart before we had discovered this dynamic start
                          was an array, correct that now
                         */
                        (k as? AbstractDomain.BufferIndices.OffsetFrom)?.takeIf {
                            it.from == idx
                        }?.offset == EVM_WORD_SIZE
                    }.forEach { k ->
                        quals[k] = AbstractDomain.BufferIndices.ArrayElemStart(
                                basePointer = idx,
                                bufferVar = dynStart.bufferVar,
                                indexVars = whole.variablesEqualTo(BigInteger.ZERO)
                        )
                    }
                    return State(
                            qualifiers = toStep.qualifiers + quals,
                            initQualifiers = newState
                    )
                } else if(write.value !is TACSymbol.Var) {
                    check(write.value is TACSymbol.Const) {
                        "nonsense type hierarchy, didn't have a var for ${write.value} but didn't have a const?"
                    }
                    /*
                      Generally speaking every write involved in a decoding initialization must come from the
                      buffer we are decoding. The one exception is if we can prove this is a write of 0 to the end
                      of the array we are initializing: this is just data cleaning and can therefore be ignored.
                     */
                    if(write.value.value == BigInteger.ZERO && whole.boundsAnalysis[write.loc]?.let {
                                it as? QualifiedInt
                            }?.qual?.any {
                                it is IntQualifier.EndOfArraySegment
                            } == true) {
                        return toStep
                    }
                    return clearInitState.andDebug {
                        "Write of constant ${write.value} @ $where violates decoding assumption"
                    }
                } else {
                    // is this an initialization write???!?!
                    val aVal = toStep[write.value] ?: return clearInitState.andDebug {
                        "Write to initializing address $initAddress @ $where was not tracked by decoder analysis"
                    }
                    /*
                      The only values we allow writing into an initializing object that is part of decoding
                      is a primitive value read directly from the buffer, or a fully initialized object we have already
                      shown is a proper decoding.
                     */
                    if(aVal !is AbstractDomain.PointerQualifier.FinalizedPointer && aVal !is AbstractDomain.ReadFrom) {
                        return clearInitState.andInfo {
                            "Nonsense write of abstract value $aVal info $initAddress @ $where"
                        }
                    }
                    data class Written(
                            val ty: BufferObject,
                            val bufferVar: TACSymbol.Var
                    )
                    val initPointer = whole.pointsToState.store[write.loc]?.let {
                        it as? InitializationPointer
                    } ?: return clearInitState

                    val (ty, bufferVar) = when(aVal) {
                        is AbstractDomain.PointerQualifier.FinalizedPointer -> {
                            Written(aVal.toType(),  aVal.bufferVar)
                        }
                        is AbstractDomain.ReadFrom -> {
                            Written(BufferObject.Word(getBufferPath(
                                    aVal, whole, toStep
                            ) ?: return clearInitState), aVal.bufferVar)
                        }
                        else -> error("impossible")
                    }
                    ptaInvariant(initPointer !is InitializationPointer.LengthFieldInit || initPointer.mustAdded) {
                        "We are in the wrong branch where we are (potentially) writing length @ $where"
                    }

                    val toMerge = if(initPointer is InitializationPointer.LengthFieldInit) {
                        val toMerge = Unresolved.ArrayInitPointerFor(
                                bufferVar = bufferVar,
                                elemType = ty
                        )
                        val writtenSize = pointerSem.getHeapType(write.value, whole)?.sizeInArray() ?: return null.andInfo<State?> {
                            "Failed to type (and size) for value being ${write.value} being written @ $where"
                        }
                        /* What is the dynamic start for the value we are writing. We have to rediscover this
                        kind of indirectly: by finding the base array variables for the write we are doing,
                        and then scanning for the (unique) dynamic start that has that base pointer as the contents
                        of its arrayFor field.
                        *  */
                        val dynStart = whole.arrayState[write.loc]?.let {
                            it as? ArrayStateAnalysis.Value.ElementPointer
                        }?.arrayPtr?.let {basePtr ->
                            toStep.qualifiers.entries.mapNotNull { (k, v) ->
                                k `to?` v.let { it as? AbstractDomain.BufferIndices.DynamicStart }?.takeIf {
                                    it.arrayFor in basePtr
                                }
                            }
                        }?.uniqueOrNull()
                        if(dynStart != null) {
                            /*
                             dynStart is a pair of the variable that holds the dynamic start and the dynamic start itself
                             (it is trivial to recompute the latter from the former, but this is just convenience)
                             */
                            val actuallyNextElems = toStep.qualifiers.mapNotNull { (k, v) ->
                                /*
                                 Based on the type of the value we are writing, we can now (exactly!) compute
                                 the size of the elements of the array, and based on that, determine what offsets from the
                                 element now point to a "next" element. (i.e., we thought they pointed to a field at
                                 offset = writtenSize, but that is not a valid offset and in fact is the location
                                 of another array elements
                                 */
                                k `to?` (v as? AbstractDomain.BufferIndices.ElementOffset)?.takeIf {
                                    it.offset == writtenSize && it.parent == dynStart.first
                                }?.let {
                                    val ind = it.indices.filterIsInstance<TACSymbol.Const>().map {
                                        it.value
                                    }.singleOrNull()?.plus(BigInteger.ONE)
                                    AbstractDomain.BufferIndices.ArrayElem(
                                            bufferVar = it.bufferVar,
                                            basePointer = it.parent,
                                            indexVars = ind?.let { whole.variablesEqualTo(it) } ?: setOf(),
                                            constIndex = ind
                                    )
                                }
                            }
                            val quals = toStep.qualifiers.mutate { quals ->
                                quals[dynStart.first] = dynStart.second.copy(
                                        strideSize = writtenSize // record our new (inferred) written size
                                )
                                for((k, v) in actuallyNextElems) {
                                    quals[k] = v
                                }
                            }
                            return toStep.mergeOrRemove(initAddress, toMerge).copy(qualifiers = quals)
                        }
                        toMerge
                    } else if(initPointer is InitializationPointer.BlockInitPointer) {
                        // field write: very simple
                        Unresolved.StructInitPointer(
                                bufferVar = bufferVar,
                                contents = BufferObject.Struct.StructContents.OffsetMap(
                                        map = mapOf(initPointer.offset to ty)
                                )
                        )
                    } else {
                        check(initPointer is InitializationPointer.StaticArrayInitPointer)
                        // static array write
                        Unresolved.StructInitPointer(
                                bufferVar = bufferVar,
                                contents = BufferObject.Struct.StructContents.StaticArray(
                                        fld = ty
                                )
                        )
                    }
                    return toStep.mergeOrRemove(initAddress, toMerge)
                }
            }
        }

        override val pathSemantics : IPathSemantics<State, Any> = TrivialPathSemantics()

        override fun killLHS(lhs: TACSymbol.Var, s: State, w: PointsToDomain, narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>): State {
            return s.killVar(lhs)
        }

        override fun postStep(stepped: State, l: LTACCmd): State {
            return stepped
        }

        override fun project(l: LTACCmd, w: PointsToDomain): State {
            return w.decoderState
        }
    }

    private val indexTracking = object : IndexTracking<AbstractDomain, WithIndexing<AbstractDomain>, Nothing>(
            numericAnalysis
    ) {

        override fun fieldStepSizeFor(k: TACSymbol.Var, v: AbstractDomain, m: Map<TACSymbol.Var, AbstractDomain>, p: PointsToDomain): BigInteger? {
            return (v as? AbstractDomain.BufferIndices.StridingPointer)?.strideBy ?: (v as? WithArrayBase)?.let {
                m[it.basePointer]?.let { it as? AbstractDomain.BufferIndices.DynamicStart }
            }?.takeIf { it.mustBeArray }?.strideSize
        }

        override fun downcast(v: AbstractDomain): WithIndexing<AbstractDomain>? {
            return (v as? AbstractDomain.BufferIndices.StridingPointer) ?: (v as? ArrayElementIndexTracking)
        }

        override fun indexStepSizeFor(
            k: TACSymbol.Var,
            v: AbstractDomain,
            m: Map<TACSymbol.Var, AbstractDomain>,
            p: PointsToDomain
        ): BigInteger? = BigInteger.ONE

    }

    override fun interpolate(prev: PointsToDomain, next: PointsToDomain, summary: Map<TACSymbol.Var, TACExpr>): State {
        val freshStride = mutableMapOf<TACSymbol.Var, AbstractDomain.BufferIndices>()
        for((k, exp) in summary) {
            if(k in next.decoderState.qualifiers || k in prev.decoderState.qualifiers) {
                continue
            }
            /*
             This detects a stride along the root, but unlike the case where we had actual constants for striding
             along the root calldata, we have to basically repeat the exercise
             by detecting the striding of two ArrayElems with constant indices. We assume that if
             we are stepping a constant amount from some, constant start index, then we are striding from that
             start index by the amount of the difference between the two constant array element indices.
             */
            if(exp !is TACExpr.Vec.Add || exp.ls.size != 2 || exp.ls.none {
                        it is TACExpr.Sym.Var && it.s == k
                    } || exp.ls.none {
                        it is TACExpr.Sym.Const
                    }) {
                continue
            }
            val prevElem = prev.arrayState[k]?.let {
                it as? ArrayStateAnalysis.Value.ElementPointer
            }?.takeIf { it.index.isConstant } ?: continue
            val nextElem = next.arrayState[k]?.let {
                it as? ArrayStateAnalysis.Value.ElementPointer
            }?.takeIf { it.index.isConstant } ?: continue
            if(!prevElem.arrayPtr.containsAny(nextElem.arrayPtr)) {
                continue
            }
            val strideConst = exp.ls.firstMapped {
                (it as? TACExpr.Sym.Const)?.s?.value
            } ?: continue
            if(strideConst.mod(EVM_WORD_SIZE) != BigInteger.ZERO) {
                continue
            }
            if((nextElem.index.c - prevElem.index.c) != strideConst) {
                continue
            }
            freshStride[k] = AbstractDomain.BufferIndices.StridingPointer(
                    from = StrideRoot.BufferRoot,
                    indexVars = prev.variablesEqualTo(BigInteger.ZERO).intersect(next.variablesEqualTo(BigInteger.ONE)),
                    bufferVar = prevElem.arrayPtr.intersect(nextElem.arrayPtr).first(),
                    innerOffset = BigInteger.ZERO,
                    strideBy = strideConst,
                    stridePath = StridePath(
                            root = prevElem.index.c,
                            path = listOf()
                    )
            )
        }
        return next.decoderState.copy(
                qualifiers = next.decoderState.qualifiers + freshStride
        ).let {
            indexTracking.interpolate(
                    prevM = prev.decoderState.qualifiers,
                    nextM = it.qualifiers,
                    next = next,
                    summary = summary
            ).first.let { q ->
                it.copy(
                        qualifiers = q
                )
            }
        }
    }

    override fun isDecoderLengthRead(loc: TACSymbol, pState: PointsToDomain): Boolean {
        return pState.decoderState[loc]?.let {
            it as? AbstractDomain.BufferIndices.DynamicStart
        }?.mustBeArray ?: false
    }

    override fun getElementSize(calldataArrayVar: TACSymbol.Var, decoderState: State): BigInteger? {
        return decoderState[calldataArrayVar]?.let {
            it as? AbstractDomain.BufferIndices.DynamicStart
        }?.takeIf { it.mustBeArray }?.strideSize
    }

    fun getCalldataRootType(v: ObjectPathAnalysis.ObjectRoot.CalldataRoot): HeapType? {
        return calldataTypes[v.copy(
            bp = v.bp.nonIndexed()
        )]
    }

    /**
     * Given a value read from [aVal] get the path. This is very similar for [extractPathForRead] (and calls it)
     * but also handles when the index is a constant (in which case it is a read from the root)
     * and when the variable is actually a constant (in which case it is also read from the root of calldata)
     */
    private fun getBufferPath(aVal: AbstractDomain.ReadFrom, whole: PointsToDomain, toStep: State) : BufferAccessPath? {
        return when (aVal.index) {
            /*
              Why don't we need to adjust the constant here? We already will have done so in the semantics for [stepReadMemory]
              (in which case we will also have ensured this read was from calldata)
            */
            is TACSymbol.Const -> BufferAccessPath.Offset(offset = aVal.index.value, base = BufferAccessPath.Root)
            is TACSymbol.Var -> extractPathForRead(aVal.index, toStep)?.second?.let {
                if (it !is BufferAccessPath.Offset) {
                    BufferAccessPath.Offset(
                            base = it,
                            offset = BigInteger.ZERO
                    )
                } else {
                    it
                }
            } ?: whole.boundsAnalysis[aVal.index]?.let { it as? QualifiedInt }?.x?.takeIf { it.isConstant }?.c?.let {
                BufferAccessPath.Offset(
                        it - DEFAULT_SIGHASH_SIZE,
                        BufferAccessPath.Root
                ).takeIf { aVal.bufferVar == TACKeyword.CALLDATA.toVar() }
            }
        }
    }

    private fun getBufferObjectPaths(bap: BufferAccessPath) : Set<ObjectPath>? {
        return bufferProcess[bap.nonIndexed()]?.let { proc ->
            proc.monadicMap { processSpec ->
                /*
                 Path process strips off some SUFFIX of the path k. The remaining buffer access path
                 (plus the offset information in inlineOffsets provides a unique name for the starting
                 location of a type within a calldata buffer; this type is one of the parent types of the value
                 located at k. By extending the root computed here with object path information (proc.f)
                 we get a complete object path describing the identity of the value at the buffer access path [k],
                 i.e., a value contained within some chain of fields/arrays/etc. that begins at some root.
                 */
                processSpec.pathProcess.invoke(buffer = bap)?.let { bp ->
                    ObjectPathGen.Root<ObjectPathAnalysis.ObjectRoot>(ObjectPathAnalysis.ObjectRoot.CalldataRoot(
                        bp = bp,
                        fieldDepth = processSpec.fieldDepth
                    ))
                }?.let(processSpec.f) /* after stripping off the buffer access paths and generating a calldata root,
                        extend the new root ofr the path with the logical object path segments giving the identity of the
                        object at k */
            }
        }?.toSet()
    }

    fun getCalldataReadPath(loc: TACSymbol.Var, whole: PointsToDomain): Set<ObjectPath>? {
        val q = whole.decoderState[loc] as? AbstractDomain.ReadFrom ?: return null
        return getBufferPath(q, whole, whole.decoderState)?.let(this::getBufferObjectPaths)
    }

    fun getCalldataArrayPaths(loc: TACSymbol.Var, whole: PointsToDomain) : Set<ObjectPath>? {
        return (whole.decoderState[loc] as? AbstractDomain.BufferIndices.DynamicStart)?.path?.let(this::getBufferObjectPaths)
    }

    override fun getReferencedPrimitivePath(v: TACSymbol, whole: PointsToDomain): IDecoderAnalysis.DirectCalldataRead? {
        return getBufferPath(
            AbstractDomain.ReadFrom(
                v,
                bufferVar = TACKeyword.CALLDATA.toVar()
            ),
            whole = whole,
            toStep = whole.decoderState
        )?.takeIf { readPath ->
            readPath.nonEmptyIndices()
        }?.let { readPath ->
            readPath.takeIf {
                calldataTypes.entries.singleOrNull { (root, ty) ->
                    root.bp == readPath.nonIndexed() && ty is HeapType.Int
                } != null
            }
        }?.let(IDecoderAnalysis::DirectCalldataRead)
    }

    override fun kill(d_: State, killedBySideEffects: Set<TACSymbol.Var>): State {
        return d_.killVars(killedBySideEffects)
    }
}
