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

package optimizer

import allocator.Allocator
import allocator.SuppressRemapWarning
import analysis.*
import analysis.alloc.AllocationAnalysis
import analysis.ip.*
import analysis.pta.*
import analysis.pta.TypedSetVisitor.Companion.OK
import analysis.pta.TypedSetVisitor.Companion.accept
import analysis.pta.abi.*
import analysis.worklist.SimpleWorklist
import com.certora.collect.*
import config.Config
import config.ReportTypes
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE_INT
import log.*
import log.regression
import optimizer.OptimizeBasedOnPointsToAnalysis.AccessAnnotation.*
import scene.ITACMethod
import utils.Range
import statistics.ANALYSIS_POINTSTO_SUBKEY
import statistics.ElapsedTimeStats
import statistics.toTimeTag
import tac.MetaKey
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import vc.data.tacexprutil.ExprUnfolder
import verifier.*
import verifier.ContractUtils.transformMethod
import java.math.BigInteger
import java.util.stream.Collectors
import java.util.stream.Stream

private val logger = Logger(LoggerTypes.ALIAS_ANALYSIS)

fun LTACCmd.isMemoryAccess(): Boolean =
    (this.cmd is TACCmd.Simple.DirectMemoryAccessCmd && this.cmd.base == TACKeyword.MEMORY.toVar()) ||
        (this.cmd is TACCmd.Simple.LongAccesses && this.cmd.accesses.any {
            it.base == TACKeyword.MEMORY.toVar()
        })

/** a source string and the matching range, generated when a points-to analysis fails */
val PTA_FAILURE_SOURCE = MetaKey<Pair<String, Range.Range?>>("pta.failure.source")
val UNINDEXED_PARTITION = MetaKey<UnindexedPartition>("tac.memory.partition")
val UNINDEXED_PARTITION_INFO = MetaKey<UnindexedPartitionInfo>("tac.memory.partition-info")

/**
 * An object allowing to do two kinds of optimization passes, based on PTA results:
 * 1. Removal of unreachable nodes
 * 2. Memory splitting
 */
object OptimizeBasedOnPointsToAnalysis {

    val PARTITION_FENCE : MetaKey<UnindexedPartition> = MetaKey<UnindexedPartition>("tac.memory.partition-fence")

    val SUMMARY_VALIDATED = MetaKey.Nothing("tac.copy-loop.valid")

    private fun LTACCmd.isPointsToSummary() = this.cmd is TACCmd.Simple.SummaryCmd &&
            (this.cmd.summ is LoopCopyAnalysis.LoopCopySummary ||
                    this.cmd.summ is ExternalMapGetterSummarization.ExternalGetterHash ||
                    this.cmd.summ is InitAnnotation.FourByteWriteSummary)

    /**
     * ABI Annotations to apply
     *
     * @param annotations the actual annotations to insert, such as encode and decode complete points
     * @param ranges groups of contiguous program locations involved in some encode/decode/direct read operation
     */
    private class ABIAnnotations(
        val annotations: Map<CmdPointer, TACCmd.Simple.AnnotationCmd.Annotation<*>>,
        val ranges: GroupedCodeSearchResult<ABICodeFinder.Source>
    )

    /**
     * The recognized serialization/deserialization/direct calldata commands in a method.
     * This is an intermediate class used to ultimately produce ABI annotations that will be
     * consumed by the callgraph builder and inliner to effect the direct passing
     * inlining optimization. For this we need to know which regions of the program correspond
     * to the serialization/deserialization of different memory objects (and the types of those
     * objects, and the source/result of those serialization/deserialization operations,
     * respectively).
     *
     * @property decodePoints the points where an in-memory object has been completely
     *           deserialized into memory
     * @property encodePoints the points where an in-memory object has been serialized
     *           into some buffer
     * @property directReads the points where the program directly loads a value from calldata
     *           (and is not part of some encode or decode)
     * @property ranges groups of contiguous program locations involved in some encode/decode/direct read
     *           operation
     * @property ctp the program being annotated for which [pta] is valid
     * @property pta points-to analysis results for [ctp] (needed to encode partition information etc)
     */
    private class ABIAnnotationInformation(
        val decodePoints: Map<CmdPointer, ABIDecodeComplete>,
        val encodePoints: Map<CmdPointer, ABIEncodeComplete>,
        val directReads: Map<CmdPointer, ABIDirectRead>,
        val ranges: GroupedCodeSearchResult<ABICodeFinder.Source>,
        val ctp: CoreTACProgram,
        val pta: IPointsToInformation
    ) {
        fun runResolution(
            p: PartitionBuilder
        ) : ABIAnnotations {
            val traversal = object : PartitionLayoutGenerator<EncodedPartitions>(
                partitions = p,
                prog = ctp,
                pta = pta
            ) {
                override fun primitiveCase(nodes: IPointsToSet): EncodedPartitions {
                    return EncodedPartitions.ScalarValue(partitions.getPartition(nodes.nodes))
                }

                override fun referenceCase(
                    nodes: IPointsToSet,
                    map: () -> Map<PartitionField, EncodedPartitions>
                ): EncodedPartitions {
                    return EncodedPartitions.ReferenceValue(
                        part = partitions.getPartition(nodes.nodes),
                        fields = map()
                    )
                }
            }

            val code = ctp
            val abi = pta

            fun TACSymbol.Var.fieldRelationAt(where: CmdPointer, ty: HeapType) : (() -> Map<PartitionField, EncodedPartitions>)? {
                if(ty !is HeapType.ReferenceType) {
                    return null
                }

                val (objectPta, point) = abi.reachableObjects(where, this)?.to(where) ?:
                    if(code.analysisCache.graph.elab(where).maybeAnnotation(POP_ALLOCATION) != null && code.analysisCache.graph.succ(where).size == 1) {
                        val nxt = code.analysisCache.graph.succ(where).single()
                        abi.reachableObjects(nxt, this) to nxt
                    } else {
                        null
                    } ?: return null
                if(objectPta !is TypedPointsToSet) {
                    return null
                }
                return traversal.unifyAndAnnotate(
                    where = point,
                    retPta = objectPta
                ).toValue({ null }, { it })
            }

            for ((ptr, e) in encodePoints.entries) {
                Logger.regression { "${code.name} ${ptr}: $e" }
            }
            for ((ptr, d) in decodePoints.entries) {
                Logger.regression { "${code.name} ${ptr}: $d" }
            }
            Logger.regression {
                "${code.name} $ranges"
            }


            // cast is not useless at all! build fails without it
            val complete = decodePoints.mapValuesTo(
                mutableMapOf<CmdPointer, TACCmd.Simple.AnnotationCmd.Annotation<*>>()
            ) { (where, v) ->
                val fieldRelation = v.output.firstNotNullOfOrNull {
                    it.fieldRelationAt(where, v.decodedType)
                };
                val withPartitions = fieldRelation?.invoke()?.let { v.copy(fieldRelations = it) } ?: v
                TACCmd.Simple.AnnotationCmd.Annotation(ABIDecodeComplete.META_KEY, withPartitions)
            }
            encodePoints.mapValuesTo(complete) { (where, v) ->
                val bufferWithEncoding = v.buffer.buffer.mapValues { (_, v) ->
                    when(v) {
                        is TopLevelValue.Constant -> v
                        is TopLevelValue.Path -> {
                            val partInfo = v.paths.firstNotNullOfOrNull {
                                ((it as? ObjectPathGen.Root)?.oRoot as? ObjectPathAnalysis.ObjectRoot.V)?.v?.fieldRelationAt(where, v.ty)
                            } ?: return@mapValues v
                            v.copy(encodedPartitions = partInfo())
                        }
                    }
                };
                TACCmd.Simple.AnnotationCmd.Annotation(ABIEncodeComplete.META_KEY, v.copy(buffer = v.buffer.copy(buffer = bufferWithEncoding.mapValues {
                    it.value
                })))
            }
            directReads.mapValuesTo(complete) { (_, v) ->
                TACCmd.Simple.AnnotationCmd.Annotation(ABIDirectRead.META_KEY, v)
            }
            return ABIAnnotations(
                complete, ranges
            )
        }
    }

    fun doWork(m: ITACMethod): CoreTACProgram {
        return try {
            val optimizeWithPTA = Config.EnabledInitializationAnalysis.get() && Config.EnablePTABasedOptimizations.get()

            // Start with a pruned tree, so that we don't try to do splitting in code that wasn't reached by PTA
            val pruned = transformMethod(
                m,
                ChainedCoreTransformers(
                    listOf(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE_BEFORE_PTA) { Pruner(it).prune() })
                )
            )

            // start by running PTA
            val (pta, isNonTrivial) = runIf(optimizeWithPTA) {
                // make sure we track time of PTA for optimizations specifically
                val tag = "POINTSTO_OPTIMIZE".toTimeTag()
                val timeElapser = ElapsedTimeStats().startMeasuringTimeOf(tag)
                CodeAnalysis(
                    ANALYSIS_POINTSTO_SUBKEY,
                    { method: ITACMethod -> PointerAnalysis.runAnalysis(method, PTARunPurpose.OPTIMIZATION) },
                    { it.isCompleteSuccess }
                ).runAnalysis(pruned).also {
                    timeElapser.endMeasuringTimeOf(tag)
                    ContractUtils.recordAggregatedTransformationStats(timeElapser, m.myName())
                }
            }?.takeIf {
                // Memory partitioning is all or nothing; we need full PTA info.
                it.isCompleteSuccess
            }?.to(true) ?: (TrivialPointsToInformation to false)

            // use PTA in optimizations
            val optList: MutableList<MethodToCoreTACTransformer<ITACMethod>> = mutableListOf()
            if (optimizeWithPTA) {
                optList.add(
                    CoreToCoreTransformer(ReportTypes.REMOVE_UNREACHABLE_POST_PTA) {
                        tryRemoveUnreachableCode(
                            it,
                            pta
                        )
                    }.lift()
                )

                // add splitting only if enabled memory splitting
                if (Config.EnabledMemorySplitting.get() && isNonTrivial) {

                    var abiInfo : ABIAnnotationInformation? = null

                    if(Config.EnabledABIAnalysis.get()) {
                        optList.add(
                            CoreToCoreTransformer(ReportTypes.NONE) { it: CoreTACProgram ->
                                abiInfo = instrumentABI(abi = pta, code = it)
                                it
                            }.lift()
                        )
                    }

                    var abiAnnotations: ABIAnnotations? = null
                    lateinit var partitionFences : Set<PartitionFence>
                    optList.add(
                        CoreToCoreTransformer(ReportTypes.MEMORY_GROUPING) {
                            val (prog, gen, fence) = groupMemoryAccess(
                                m,
                                it,
                                pta,
                                abiInfo
                            )
                            abiAnnotations = gen
                            partitionFences = fence
                            prog
                        }.lift()
                    )

                    // but don't scalaraize if we're going to do direct passing
                    if (!Config.EnabledABIAnalysis.get()) {
                        optList.add(
                            CoreToCoreTransformer(ReportTypes.MEMORY_SPLITTER) {
                                trySplitMemory(
                                    it,
                                    pta
                                )
                            }.lift()
                        )
                    }

                    // EXTREMELY!
                    // EXPERIMENTAL!
                    if(Config.EnabledABIAnalysis.get()) {
                        optList.add(
                            MethodToCoreTACTransformer(ReportTypes.ABI) {
                                annotateABI(
                                    it, abiAnnotations
                                )
                            }
                        )
                    }

                    optList.add(CoreToCoreTransformer(ReportTypes.INSERT_PARTITION_FENCES) {
                        it.patching { p ->
                            for((src, dst, part) in partitionFences) {
                                p.insertAlongEdge(src, dst, listOf(
                                    TACCmd.Simple.AnnotationCmd(PARTITION_FENCE, part)
                                ))
                            }
                        }
                    }.lift())
                }
            }

            optList.add(
                // after splitting, more path pruning opportunities may come up, because some of the memory
                // accesses are scalars
                CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE_AFTER_PTA) { Pruner(it).prune() }.lift()
            )

            val transforms = ChainedMethodTransformers(optList)
            transformMethod(pruned, transforms).code as? CoreTACProgram
                ?: throw IllegalStateException("CoreTACProgram expected")

        } catch (e: Exception) {
            Logger.alwaysWarn("Failed to run memory splitting for ${m.name}", e)
            m.code as CoreTACProgram
        }
    }

    /**
     * Indicates the points to sets that are "affected" by a command. All sets within an access annotation
     * should be unified. In the case where a command has an input and output component (the call* commands)
     * we use [LongInput] and [LongOutput] to further subdivide the PTA sets affected at the command, otherwise
     * we use [SingletonCell]. NB that [SingletonCell] is used to represent the access of a single, 32 byte "cell",
     * whereas the [LongInput] and [LongOutput] commands represent long accesses. There is no use case for
     * multiple singleton cell accesses: any command with multiple accesses does so with multiple long accesses.
     */
    sealed interface AccessAnnotation {
        val setGroup: Collection<IPointsToSet>
        data class LongInput(override val setGroup: Collection<IPointsToSet>) : LongAccessAnnotation
        data class LongOutput(override val setGroup: Collection<IPointsToSet>) : LongAccessAnnotation
        data class SingletonCell(override val setGroup: Collection<IPointsToSet>) : AccessAnnotation
        object Error : AccessAnnotation { override val setGroup = listOf<IPointsToSet>() }
    }

    sealed interface LongAccessAnnotation : AccessAnnotation

    /**
     * Helper class which traverses type points to sets (using [pta]), and unifies equivalence classes
     * in [partitions] as it goes. The unification also produces (deferred) annotations of type
     * [T], these are generated via the [primitiveCase] and [referenceCase] functions (which are
     * constructors for the type [T] nerrrrr)
     */
    private abstract class PartitionLayoutGenerator<T>(
        protected val partitions: PartitionBuilder,
        protected val pta: IPointsToInformation,
        protected val prog: CoreTACProgram
    ) {
        abstract protected fun primitiveCase(nodes: IPointsToSet) : T
        abstract protected fun referenceCase(nodes: IPointsToSet, map: () -> Map<PartitionField, T>) : T

        /**
         * Q: Have you lost your goddamn mind John? What is this type signature?
         *
         * A: READER. Please.
         *
         * To generate the internal function value locations and abi locations, we must both "traverse" the abstract shapes of the reference types
         * at function entrace/exit and encode/decodes, updating our equivalence classes according to the nodes that appear during this traversal.
         *
         * We must *also* generate the actual [analysis.ip.InternalFuncValueLocation.PointerLayoutInfo] information
         * for each internal function argument/return (resp [EncodedPartitions] for abi encode/decodes).
         * This also requires a traversal, but note that we can only do that traveral *after* we have
         * completely updated the equivalence classes.
         *
         * The naive approach is to do two traversals then: one pass to process all internal entrance/exits and encode/decodes,
         * and once more to actually generate the [analysis.ip.InternalFuncValueLocation.PointerLayoutInfo] and
         * [EncodePoints] objects.
         *
         * Instead, we have the traversal mutably update the equivalence classes, and produce suspended computations that
         * are guaranteed to be run only AFTER all processing and equivalence class updating is complete. These suspended
         * computations then consume the (fully updated) equivalence class information and produce the appropriate
         * [analysis.ip.InternalFuncValueLocation.PointerLayoutInfo] or [EncodedPartitions] objects.
         *
         * The recursive nature of the traversal and the easy composition of these suspended computations goes well together:
         * a traversal of an abstract object's fields will return a set of suspended computations, and then the object
         * itself will return a suspended computation which forces the suspended computations returned previously.
         *
         * The traversal process here heavily uses the [TypedSetVisitor] to do the case splitting on types.
         */
        fun unifyAndAnnotate(retPta: TypedPointsToSet, where: CmdPointer) : Either<TypedSetVisitor.VisitError, () -> Map<PartitionField, T>> {
            /**
             * A map of fields to suspended computations, which will produce the [analysis.ip.InternalFuncValueLocation.PointerLayoutInfo]
             * for each field when forced.
             */
            val generated = treapMapBuilderOf<PartitionField, () -> T>()

            /**
             * Case split on the type of the object represented by [retPta], updating fields as necessary.
             */
            val error = retPta.accept(where, pta, object: TypedSetVisitor {
                override fun arrayField(s: IndexedWritableSet): VisitResult {
                    return handleTypedField(
                        s,
                        PartitionField.ArrayElement(
                            s.elementSize.intValueExact()
                        )
                    )
                }

                override fun arrayLengthField(s: IPointsToSet): VisitResult {
                    /**
                     * mutably update the equivalence classes
                     */
                    partitions.mergeFields(s)
                    /**
                     * NB we are assigning a lambda here, which when called where will produce a
                     * [analysis.ip.InternalFuncValueLocation.PrimitiveField] object (with an annotated
                     * tacM variable) to indicate where the lengths of this array is stored.
                     */
                    generated[PartitionField.ArrayLength] = {
                        primitiveCase(s)
                    }
                    return OK
                }

                override fun structField(o: BigInteger, s: WritablePointsToSet): TypedSetVisitor.VisitError? {
                    return handleTypedField(f = PartitionField.StructField(o), fld = s)
                }

                /**
                 * This function returns a map of suspended computation, but no such map is sensible for primitives. It is
                 * therefore part of this function's calling convention that retPta should never be a primitive type,
                 * the cases of when retPta WOULD be a primitive typed set are pushed into the callers.
                 */
                override fun primitive() {
                    throw IllegalStateException("Expected $retPta @ $where in ${prog.name} to represent a reference type")
                }

                private fun handleTypedField(fld: WritablePointsToSet, f: PartitionField) : TypedSetVisitor.VisitError? {
                    /**
                     * Mutably update the equivalence classes
                     */
                    partitions.mergeFields(fld)
                    /**
                     * We do not have a reference type, so no recursion. Set the suspended computation for [f] to be
                     * a simple [analysis.ip.InternalFuncValueLocation.PrimitiveField] instance and return.
                     */
                    if(fld.type != IPointsToSet.Type.REFERENCE) {
                        generated[f] = { ->
                            primitiveCase(
                                fld
                            )
                        }
                        return OK
                    }
                    /**
                     * Follow the field.
                     */
                    val contents = pta.contentsOf(where, fld) ?: return TypedSetVisitor.VisitError.CALLBACK_ERROR
                    /**
                     * If we have an object that is not typed, then we can't keep traversing: the traversal itself is
                     * guided by the type information of the [TypedPointsToSet]
                     */
                    if(contents !is TypedPointsToSet) {
                        return TypedSetVisitor.VisitError.NO_TYPE_INFO
                    }
                    /**
                     * Recurse, bubbling up errors, or setting [f] to be a reference field,
                     * whose fields are given by forcing the computation returned by the successful recursive case.
                     */
                    return unifyAndAnnotate(contents, where).toValue({
                        it
                    }, {
                        generated[f] = {
                            referenceCase(
                                fld, it
                            )
                        }
                        OK
                    })
                }
            })
            /**
             * If the traversal ended with an Error, return that back, otherwise return a function that
             * forces all of the values in generated, i.e., transforming a `Map<F, () -> V>`
             * into a `() -> Map<F, V>`.
             */
            return error?.toLeft() ?: { ->
                generated.mapValues {
                    it.value()
                }
            }.toRight()
        }
    }

    private fun instrumentABI(
        code: CoreTACProgram,
        abi: IPointsToInformation,
    ): ABIAnnotationInformation? {
        val logger = Logger(LoggerTypes.ABI)
        if (abi !is FlowPointsToInformation) {
            logger.debug { "Not FlowPointsTo for ${code.name}"}
        }
        val decodePoints = abi.query(DecodePoints) ?: run {
            logger.debug { "DecodePoints failed ${code.name}"}
            return null
        }
        val encodePoints = abi.query(EncodePoints) ?: run {
            logger.debug { "EncodePoints failed ${code.name}"}
            return null
        }
        val directReads = abi.query(DirectReadFinder(
            decodePoints, encodePoints
        )) ?: run {
            logger.debug { "DirectReadFinder failed ${code.name}"}
            return null
        }
        val codeFinder = abi.query(ABICodeFinder(
            decodePoints,
            encodePoints,
            directReads,
        )) ?: run {
            logger.debug { "ABICodeFinder failed ${code.name}"}
            return null
        }
        return ABIAnnotationInformation(
            decodePoints = decodePoints,
            encodePoints = encodePoints,
            directReads = directReads,
            pta = abi,
            ctp = code,
            ranges = codeFinder
        )
    }

    private fun annotateABI(
        it: ITACMethod,
        staged: ABIAnnotations?
    ) : CoreTACProgram {
        val code = it.code as CoreTACProgram
        if(staged == null) {
            return code
        }
        val codeFinder = staged.ranges
        val complete = staged.annotations.mapValues { it.value }
        val annotator = ABIAnnotator(
            completionPoints = complete,
            relatedCommands = codeFinder.foundCommands,
            blocks = codeFinder.foundBlocks,
            boundaries = codeFinder.boundary
        )
        return code.patching { p ->
            annotator.annotate(p)
        }

    }

    /**
     * Indicates that a partition fence annotation for the partition [part]
     * needs to be inserted along the edge from [src] to [dst]. Note that we produce this information within [groupMemoryAccess]
     * and apply it later to not throw off the command pointers in the [ABIAnnotations] returned as part of [GroupingResult].
     */
    @SuppressRemapWarning
    private data class PartitionFence(val src: NBId, val dst: NBId, val part: UnindexedPartition)

    private data class GroupingResult(
        val prog: CoreTACProgram,
        val abiResult: ABIAnnotations?,
        val partitionFences: Set<PartitionFence>
    )

    /**
     * Instantiation of the [PartitionLayoutGenerator] which traverses points to set associated with internal function entrances/exits,
     * producing layout information in [InternalFuncValueLocation.PointerLayoutInfo] as the annotation, which are attached
     * to the internal function start/exit annotations.
     */
    private class InternalValueLayoutGenerator(partitions: PartitionBuilder, pta: IPointsToInformation, prog: CoreTACProgram) : PartitionLayoutGenerator<InternalFuncValueLocation.PointerLayoutInfo>(
        partitions, pta, prog,
    ) {
        /**
         * Produce a tacM variable that is annotated with the partition id given to the equivalence class
         * represented by [this].
         *
         * It is expected, but not checked, that all nodes in in this are in the same equivalence class.
         */
        fun Collection<PTANode>.toPartitionVar() = TACKeyword.MEMORY.toVar().withMeta(UNINDEXED_PARTITION, partitions.getPartition(this))


        override fun primitiveCase(nodes: IPointsToSet): InternalFuncValueLocation.PointerLayoutInfo {
            return InternalFuncValueLocation.PrimitiveField(
                partitionVariable = nodes.nodes.toPartitionVar()
            )
        }

        override fun referenceCase(
            nodes: IPointsToSet,
            map: () -> Map<PartitionField, InternalFuncValueLocation.PointerLayoutInfo>
        ): InternalFuncValueLocation.PointerLayoutInfo {
            return InternalFuncValueLocation.ReferenceField(
                partitionVariable = nodes.nodes.toPartitionVar(),
                fields = map()
            )
        }

    }

    /**
     * Given a list (or other iterable) of type [T] (expected to be [InternalFuncRet] or [InternalFuncArg]),
     * which encapsulates an internal function return value/argument variable selected out with [s],
     * a function [update] that updates instances of type [T] with inferred [InternalFuncValueLocation] information,
     * return a list of suspended computations, each of which produces a new instance of [T] with inferred [InternalFuncValueLocation].
     *
     * If [pred] returns false for some instance of [T], then [T] is not processed, and a continuation that returns
     * that instance unchanged is added to the returned list.
     *
     * Behind the scenes this just calls unifyAndAnnotate, which as discussed there produces suspended computations. Thus,
     * the process which consumes that information and sets the [InternalFuncValueLocation] data is also suspended.
     *
     * A return value of null indicates the inference process failed (the update process itself, once inference completes)
     * is expected to be "total".
     *
     * The "current program" in which this processing occurs is plumbed through as a context paramter, and the
     * whole program points to information for that program is likewise plumbed through as a context param.
     */
    context(IPointsToInformation, CoreTACProgram)
    private fun <T> Iterable<T>.processAnnotations(
        unification: InternalValueLayoutGenerator,
        where: CmdPointer,
        s: T.() -> TACSymbol.Var,
        pred: T.() -> Boolean = { true },
        update: T.(InternalFuncValueLocation) -> T
    ) : List<() -> T>? {
        val retLayout = mutableListOf<() -> T>()
        for(r in this) {
            if(!r.pred()) {
                retLayout.add { r }
                continue
            }
            val retPta = reachableObjects(where, r.s())
            if(retPta != null && retPta.type == IPointsToSet.Type.UNKNOWN) {
                return null
            }
            /**
             * This is just a scalar, we could eagerly update, but don't for consistency
             */
            if(retPta == null || retPta.type != IPointsToSet.Type.REFERENCE) {
                retLayout.add { -> r.update(InternalFuncValueLocation.Scalar) }
                continue
            }
            /**
             * Cannot traverse
             */
            if(retPta !is TypedPointsToSet) {
                return null
            }
            val typeDesc = retPta.typeDesc
            /**
             * Mismatch between the Points to type and the simple type descriptors
             */
            check(typeDesc !is TypedPointsToSet.SimpleTypeDescriptor.INT) {
                "At $where in ${name}, have that basic type of $retPta is not int, but type desc $typeDesc thinks it is"
            }
            /**
             * Infer the fields for this reference type, or fail
             */
            when(val fieldDesc = unification.unifyAndAnnotate(retPta, where)) {
                is Either.Left -> {
                    logger.info {
                        "Failed processing annotation at $where in ${name}, with error ${fieldDesc.d}"
                    }
                    return null
                }
                is Either.Right -> {
                    retLayout.add { ->
                        r.update(InternalFuncValueLocation.PointerWithFields(
                            fieldDesc.d()
                        ))
                    }
                }
            }
        }
        return retLayout
    }

    /**
     * Despite the name, this processes both internal function entrances and exits, as well as the
     * internal call annotations, unifying and
     * producing pointer layout information via the [InternalValueLayoutGenerator]. The result
     * is a series of staged rewrites that, when applied, update the internal function start/end information.
     */
    context(CoreTACProgram, IPointsToInformation)
    private fun processInternalCalls(
        revertSet: IPointsToSet,
        partitions: PartitionBuilder
    ) : List<(SimplePatchingProgram) -> Unit> {
        val prog = this@CoreTACProgram
        val pta = this@IPointsToInformation

        val outputAnnot = prog.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.takeIf {
                it.cmd.annot.k == INTERNAL_FUNC_EXIT
            }
        }.collect(Collectors.toList())

        val inputAnnot = prog.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.takeIf {
                it.cmd.annot.k == INTERNAL_FUNC_START
            }
        }.collect(Collectors.toList())

        val internalCallSummary = prog.parallelLtacStream().mapNotNull {
            it `to?` it.snarrowOrNull<InternalCallSummary>()
        }.toList()

        /**
         * List of suspended program updates. For the reasons discussed above, the actual update process is delayed until all annotations
         * are processed: thus the ultimate program mutations that rely on the annotations being processed are likewise deferred.
         */
        val annotationSummaryUpdates = mutableListOf<(SimplePatchingProgram) -> Unit>()

        val unification = InternalValueLayoutGenerator(
            partitions, pta, prog
        )

        outer@for(lc in outputAnnot) {
            val ret = lc.cmd.annot.v as InternalFuncExitAnnotation
            val retLayout = ret.rets.processAnnotations(unification, lc.ptr, InternalFuncRet::s) {
                this.copy(location = it)
            } ?: continue@outer
            /**
             * Add an update which replaces this exit annotation with the information produced by the processAnnotations.
             */
            annotationSummaryUpdates.add { patching ->
                patching.replaceCommand(lc.ptr, listOf(
                    lc.cmd.copy(
                        annot = TACCmd.Simple.AnnotationCmd.Annotation(
                            INTERNAL_FUNC_EXIT, ret.copy(
                                rets = retLayout.map { it() }
                            )
                        )
                    )
                ))
            }
        }

        fun InternalFuncArg.argVar() = this.s as TACSymbol.Var

        outer@for(lc in inputAnnot) {
            val entry = lc.cmd.annot.v as InternalFuncStartAnnotation
            val argumentLayout = entry.args.filter { it.s is TACSymbol.Var }.processAnnotations(unification, lc.ptr, InternalFuncArg::argVar) {
                this.copy(location = it)
            } ?: continue@outer
            /**
             * Likewise, add an update which replaces this start annotation with arguments that have layout information.
             */
            annotationSummaryUpdates.add { patching ->
                patching.replaceCommand(lc.ptr, listOf(
                    lc.cmd.copy(
                        annot = TACCmd.Simple.AnnotationCmd.Annotation(
                            INTERNAL_FUNC_START,
                            entry.copy(
                                args = argumentLayout.map { it() }
                            )
                        )
                    )
                ))
            }
        }
        val unsplitLocation by lazy {
            InternalFuncValueLocation.UnsplitPointer(
                monolithicArray = TACKeyword.MEMORY.toVar()
                    .withMeta(UNINDEXED_PARTITION, partitions.getPartition(revertSet.nodes))
            )
        }

        for((where, summ) in internalCallSummary) {
            val hostCommand = where.cmd as TACCmd.Simple.SummaryCmd

            if(where.ptr.block in analysisCache.revertBlocks) {
                /**
                 * The PTA currently skips revert blocks, so there are no partitions here.
                 * Unfortunately, the PTI interface doesn't distinguish between "this is not a pointer" and "I have no results
                 * for this location" (it just returns null). Thus, our location annotator just annotates the output
                 * locations as a scalar... and if one of these return outputs/inputs is used in an internal summary, we get
                 * a class cast exception (trying to use a "scalar" as a pointer).
                 *
                 * Instead, explicitly mark internal entraces/exits within a revert block as using the same monolithic
                 * "revert block memory" identified by the synthetic [IPointsToSet] passed in as [revertSet]. This same
                 * block is used for memory read/writes within revert blocks, so at least this is consistent.
                 */
                annotationSummaryUpdates.add { patching ->
                    patching.replaceCommand(where.ptr, listOf(
                        hostCommand.copy(
                            summ = summ.copy(
                                internalArgs = summ.internalArgs.map {
                                    it.copy(location = unsplitLocation)
                                },
                                internalExits = summ.internalExits.map {
                                    it.copy(location = unsplitLocation)
                                }
                            )
                        )
                    ))
                }
                continue
            }
            val newArgs = summ.args.processAnnotations(unification, where.ptr, InternalFuncArg::argVar, pred = { s is TACSymbol.Var }) {
                this.copy(location = it)
            } ?: continue
            val successorState = prog.analysisCache.graph.succ(where.ptr).singleOrNull() ?: continue
            val newRets = summ.internalExits.processAnnotations(unification, successorState, InternalFuncRet::s) {
                this.copy(location = it)
            } ?: continue

            /**
             * Add a suspended update which replaces this summary to include layout information for the arg/return values.
             *
             * Note that we do *not* bother doing a partial update, as the value is not clear.
             */
            annotationSummaryUpdates.add { patching ->
                patching.replaceCommand(where.ptr, listOf(
                    hostCommand.copy(
                        summ = summ.copy(
                            internalArgs = newArgs.map { it() },
                            internalExits = newRets.map { it() }
                        )
                    )
                ))
            }
        }

        return annotationSummaryUpdates
    }

    private fun groupMemoryAccess(
        method: ITACMethod,
        prog: CoreTACProgram,
        pta: IPointsToInformation,
        abiInfo: ABIAnnotationInformation?
    ) : GroupingResult {
        val summarizedBlocks = prog.parallelLtacStream().filter {
            it.isPointsToSummary() && it.snarrowOrNull<LoopCopyAnalysis.LoopCopySummary>().let { summ ->
                summ == null || pta.query(CopyLoopValid(
                    where = it.ptr,
                    v = summ
                )) != null
            }
        }.flatMap {
            it.snarrowOrNull<ConditionalBlockSummary>()?.summarizedBlocks!!.stream()
        }.collect(Collectors.toSet())

        val revertSet = IPointsToSet.None()
        val logSet = IPointsToSet.None()
        val returnVoidSet = IPointsToSet.None()
        val emptyArraySet = IPointsToSet.None()
        val scratchSlotSpace = IPointsToSet.None()
        val graph = prog.analysisCache.graph

        fun doGrouping(it: LTACCmd) : List<Pair<CmdPointer, AccessAnnotation>> {
            fun omniOf(p: IPointsToSet?) = p?.let(::listOf)?.let(AccessAnnotation::SingletonCell)
            fun omniAt(s: TACSymbol) = pta.fieldNodesAt(it.ptr, s)?.let(::listOf)?.let(AccessAnnotation::SingletonCell)
            fun singleAccess(s: IPointsToSet?) = (it.ptr `to?` omniOf(s))?.let(::listOf)
            fun singleLongRead(s: IPointsToSet?) = (it.ptr `to?` s?.let(::listOf)?.let { pta ->
                LongInput(pta)
            })?.let(::listOf)
            fun access(ptr: CmdPointer, access: AccessAnnotation) = ptr to access
            val result = run result@ {
                check(it.cmd is TACCmd.Simple.LongAccesses || it.cmd is TACCmd.Simple.DirectMemoryAccessCmd || it.cmd is TACCmd.Simple.SummaryCmd) {
                    "Invalid command format $it"
                }
                when (it.cmd) {
                    is TACCmd.Simple.SingletonLongRead -> {
                        check(it.cmd.accesses.size == 1) {
                            "Invariant broken: singleton long read has multiple reads! $it -> ${it.cmd.accesses}"
                        }
                        when(it.cmd) {
                            is TACCmd.Simple.LogCmd -> {
                                singleLongRead(logSet)
                            }
                            is TACCmd.Simple.RevertCmd -> {
                                singleLongRead(revertSet)
                            }

                            is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> {
                                val field = pta.fieldNodesAt(it.ptr, it.cmd.op1 as? TACSymbol.Var ?: return@result null) ?: return@result null
                                val parentObject = pta.reachableObjects(it.ptr, it.cmd.op1) as? TypedPointsToSet
                                val objectType = parentObject?.typeDesc
                                /**
                                 * Detect if we are hashing a struct object. This condition will be true if we have a valid
                                 * field pointer v that is also a valid object pointer, the object pointer's first field is represented
                                 * by the same set of nodes as v, and the size of the hash is the number of struct fields * 32.
                                 */
                                if(field.nodes.all { it is StructFieldNode } && parentObject != null && pta.fieldNodesAt(it.ptr, parentObject, BigInteger.ZERO)?.let {
                                        it.nodes == field.nodes
                                    } == true && objectType is TypedPointsToSet.SimpleTypeDescriptor.ReferenceType.Struct &&
                                    it.cmd.op2 is TACSymbol.Const && it.cmd.op2.value == (objectType.nFields * EVM_WORD_SIZE_INT).toBigInteger()) {
                                    val allFields = (0 until objectType.nFields).monadicMap { fNum ->
                                        pta.fieldNodesAt(it.ptr, parentObject, (fNum * EVM_WORD_SIZE_INT).toBigInteger())
                                    } ?: return@result null
                                    listOf(it.ptr to LongInput(allFields))
                                } else if(field.nodes.any {
                                        it !is ArrayLikeDataNode
                                    }) {
                                    null
                                } else {
                                    singleLongRead(field)
                                }

                            }
                            is TACCmd.Simple.ReturnCmd -> {
                                if(it.cmd.o2 is TACSymbol.Const && it.cmd.o2.value == BigInteger.ZERO) {
                                    singleLongRead(returnVoidSet)
                                } else {
                                    singleLongRead(pta.fieldNodesAt(it.ptr, it.cmd.o1 as? TACSymbol.Var ?: return@result null))
                                }
                            }

                        }
                    }
                    is TACCmd.Simple.DirectMemoryAccessCmd -> {
                        if(it.ptr.block in prog.analysisCache.revertBlocks) {
                            singleAccess(revertSet)
                        } else {
                            singleAccess(pta.fieldNodesAt(it.ptr, it.cmd.loc))
                        }
                    }
                    is TACCmd.Simple.LongAccesses -> {
                        it.cmd.accesses.mapNotNull { longAccess ->
                            if(longAccess.base != TACKeyword.MEMORY.toVar()) {
                                return@mapNotNull null
                            }
                            val ctor : (List<IPointsToSet>) -> AccessAnnotation = if(longAccess.isWrite) {
                                AccessAnnotation::LongOutput
                            } else {
                                AccessAnnotation::LongInput
                            }
                            val isEmptyCopy = pta.query(ConstantValue(it.ptr, longAccess.length)) == BigInteger.ZERO
                            if(isEmptyCopy) {
                                return@mapNotNull it.ptr to ctor(listOf(emptyArraySet))
                            }
                            if(it.ptr.block in prog.analysisCache.revertBlocks) {
                                return@mapNotNull it.ptr to ctor(listOf(revertSet))
                            }
                            val pts = pta.fieldNodesAt(it.ptr, longAccess.offset)?.let(::listOf)
                            it.ptr to if(pts == null) {
                                if(longAccess.isWrite && pta.query(ConstantValue(it.ptr, longAccess.offset))?.let { offs ->
                                        pta.query(ConstantValue(it.ptr, longAccess.length))?.let { sz ->
                                            (offs + sz) in BigInteger.ZERO .. 0x40.toBigInteger()
                                        }
                                    } == true) {
                                    ctor(listOf(scratchSlotSpace))
                                } else {
                                    AccessAnnotation.Error
                                }
                            } else {
                                ctor(pts)
                            }
                        }
                    }
                    is TACCmd.Simple.SummaryCmd -> {
                        when (val summ = it.cmd.summ) {
                            is InitAnnotation.FourByteWriteSummary -> {
                                val set = omniAt(summ.base) ?: return@result null
                                graph.elab(summ.summarized).commands.mapNotNull {
                                    if (it.cmd is TACCmd.Simple.DirectMemoryAccessCmd) {
                                        access(it.ptr, set)
                                    } else {
                                        null
                                    }
                                }
                            }
                            is LoopCopyAnalysis.LoopCopySummary -> {
                                val query = pta.query(
                                    CopyLoopValid(
                                        where = it.ptr,
                                        v = summ
                                    )
                                ) ?: return@result emptyList()
                                val outSet = SingletonCell(listOf(query.destNodes))
                                val inSet = when(query.srcNodes) {
                                    CopyLoopValid.CopySource.EmptyArray -> SingletonCell(listOf(emptyArraySet))
                                    is CopyLoopValid.CopySource.Fields -> SingletonCell(listOf(query.srcNodes.field))
                                    CopyLoopValid.CopySource.Other -> null
                                }
                                val simpleAccesses = summ.summarizedBlocks.flatMap {
                                    if (it in summ.fixupBlocks) {
                                        emptyList()
                                    } else {
                                        graph.elab(it).commands.mapNotNull {
                                            if (it.cmd is TACCmd.Simple.AssigningCmd.ByteLoad &&
                                                it.cmd.base == TACKeyword.MEMORY.toVar() && inSet != null
                                            ) {
                                                access(it.ptr, inSet)
                                            } else if (it.cmd is TACCmd.Simple.AssigningCmd.ByteStore &&
                                                it.cmd.base == TACKeyword.MEMORY.toVar()
                                            ) {
                                                access(it.ptr, outSet)
                                            } else {
                                                null
                                            }
                                        }
                                    }
                                } + summ.fixupBlocks.flatMap {
                                    graph.elab(it).commands.mapNotNull { where ->
                                        if (where.cmd is TACCmd.Simple.DirectMemoryAccessCmd) {
                                            if(where.cmd.meta.containsKey(COPY_LOOP_DEST_ACCESS)) {
                                                access(where.ptr, outSet)
                                            } else if(where.cmd.meta.containsKey(COPY_LOOP_SOURCE_ACCESS) && inSet != null) {
                                                access(where.ptr, inSet)
                                            } else {
                                                return@result null
                                            }
                                        } else {
                                            null
                                        }
                                    }
                                }
                                simpleAccesses + listOfNotNull(
                                    it.ptr to outSet.setGroup.let(AccessAnnotation::LongOutput),
                                    it.ptr `to?` inSet?.setGroup?.let(AccessAnnotation::LongInput)
                                )
                            }
                            is ExternalMapGetterSummarization.ExternalGetterHash -> {
                                val set = pta.fieldNodesAt(it.ptr, summ.output) ?: return@result null
                                graph.elab(summ.originalBlockStart).commands.mapNotNull {
                                    if (it.cmd is TACCmd.Simple.DirectMemoryAccessCmd) {
                                        access(it.ptr, SingletonCell(listOf(set)))
                                    } else {
                                        null
                                    }
                                }
                            }
                            else -> `impossible!`
                        }
                    }
                    else -> `impossible!`
                }
            }
           return result ?: listOf(it.ptr to AccessAnnotation.Error)
        }

        val accesses = prog.parallelLtacStream().filter {
            it.ptr.block !in summarizedBlocks &&
                    (it.isMemoryAccess() || it.isPointsToSummary())
        }.map {
            doGrouping(it)
        }.collect(
            // flat map Stream<List<Pair<CmdPointer, Access>> to Stream<Pair<CmdPointer, Access>>
            Collectors.flatMapping({ it.stream() },
                // collect all accesses with the same cmdpointer into a set
                Collectors.groupingBy({ it.first }, Collectors.mapping({ it.second }, Collectors.toSet()))
            )
        )

        // If there were any failures, annotate the program as such and return.
        val failures = accesses.entries.mapNotNull { (where, set) ->
            where.takeIf { set.isEmpty() || AccessAnnotation.Error in set }?.let {
                logger.debug { "In ${prog.name}, no PTA info for $it" }
                it to getSourceHintWithRange(graph.elab(it), graph, method)
            }
        }
        if (failures.isNotEmpty()) {
            logger.warn { "Failed to fully process ${prog.name}" }
            return GroupingResult(prog.patching { patching ->
                failures
                    .map { (ptr, failureInfo) ->
                        patching.addBefore(ptr,
                            // This info is picked up in [MemoryPartition.kt] - the ptr.toString will be used as additional hint for the warning, the range for jump to source.
                            listOf(TACCmd.Simple.AnnotationCmd(PTA_FAILURE_SOURCE, ptr.toString() to failureInfo.range)))
                         }
            }, null, setOf())
        }

        Logger.regression {
            "Fully processed ${prog.name}"
        }

        val partitions = PartitionBuilder(pta)

        /**
         * Get all parent nodes which represent objects for which all struct fields need to be unified (the output of a longcopy)
         */
        val mergedParents = accesses.entries.flatMapToSet { (_, access) ->
            access.flatMap {
                (it as? LongAccessAnnotation)?.setGroup?.flatMap {
                    it.nodes.mapNotNull {
                        (it as? StructFieldNode)?.parentNode
                    }
                }.orEmpty()
            }
        }

        /**
         * Tell the partition builder about it
         */
        partitions.registerMergeParents(mergedParents)

        accesses.forEachEntry { (_, sets) ->
            sets.forEach {
                partitions.mergeFields(it.setGroup)
            }
        }

        val gcPoints = (pta as? AbstractGarbageCollection)?.let { agc ->
            prog.parallelLtacStream().mapNotNull { where ->
                where.ptr `to?` agc.gcAt(where).takeIf { it.isNotEmpty() }
            }
        }?.collect(Collectors.toMap({ it.first }, {it.second})) ?: mapOf()

        val gcedNodes = gcPoints.flatMapTo(treapSetBuilderOf()) {
            it.value
        }

        val patching = prog.toPatchingProgram()


        val annotationSummaryUpdates = with(pta) {
            with(prog) {
                processInternalCalls(revertSet, partitions)
            }
        }

        /*
          at this point, the equivalence classes have stabilized. Let's go back and see which gc'able nodes were somehow merged
         */
        val eligibleForGc = gcedNodes.filter {
            partitions.isSingleton(it)
        }

        val partitionFences = mutableSetOf<PartitionFence>()

        val partitionAt : (Collection<IPointsToSet>, CmdPointer) -> UnindexedPartition = if(eligibleForGc.isEmpty() || !Config.EnabledFlowSensitivePartitioning.get()) {
            { n, where -> partitions.getPartition(where, n) }
        } else {
            // compute SSA numbering for the nodes that are eligible for GC
            val dominanceFrontiers = prog.analysisCache.domination.calcFrontiers(true)
            val blockToFreshPhi = treapMapBuilderOf<NBId, TreapSet<PTANode>>()
            gcPoints.forEachEntry { (where, nodes)  ->
                val dominanceFrontierInfo by lazy {
                    val phiBlocks = treapSetBuilderOf<NBId>()
                    SimpleWorklist.iterateWorklist(dominanceFrontiers[where.block].orEmpty()) { block, nxt ->
                        if(phiBlocks.add(block)) {
                            nxt.addAll(dominanceFrontiers[block].orEmpty())
                        }
                    }
                    phiBlocks.build()
                }
                for(n in nodes) {
                    if(n !in eligibleForGc) {
                        continue
                    }
                    val reachesEndBlock = graph.iterateBlock(where, excludeStart = true).all {
                        n !in gcPoints[it.ptr].orEmpty()
                    }
                    if(reachesEndBlock) {
                        dominanceFrontierInfo.forEachElement {
                            blockToFreshPhi.updateInPlace(it, treapSetOf()) { set -> set + n }
                        }
                    }
                }
            }
            var ind = 0
            val seed = graph.roots.single().ptr
            val st = treapMapBuilderOf<CmdPointer, TreapMap<PTANode, Int>>()
            run {
                val startState = treapMapBuilderOf<PTANode, Int>()
                for (n in eligibleForGc) {
                    startState[n] = ind++
                }
                st[seed] = startState.build()
            }
            val phiNumbering = mutableMapOf<Pair<NBId, PTANode>, Int>()
            val gcNumbering = mutableMapOf<Pair<CmdPointer, PTANode>, Int>()
            SimpleWorklist.iterateWorklist(listOf(seed.block)) { blockId, nxt ->
                val block = graph.elab(blockId)
                val stateIter = st[block.commands.first().ptr]!!.builder()
                for(lc in block.commands) {
                    st[lc.ptr] = stateIter.build()
                    val toGc = gcPoints[lc.ptr]?.filter {
                        it in eligibleForGc
                    }?.takeIf { it.isNotEmpty() } ?: continue
                    for(node in toGc) {
                        /* given the iteration order we use here, this should always actually
                           be "absent" but best to be certain
                         */
                        val newId = gcNumbering.computeIfAbsent(lc.ptr to node) {
                            ind++
                        }
                        stateIter[node] = newId
                    }
                }
                for(succId in graph.succ(blockId)) {
                    val nxtState = stateIter.build().builder()
                    val fst = graph.elab(succId).commands.first()
                    blockToFreshPhi[succId]?.forEach { pNode ->
                        val newId = phiNumbering.computeIfAbsent(succId to pNode) {
                            ind++
                        }
                        nxtState[pNode] = newId
                    }
                    if(fst.ptr !in st) {
                        st[fst.ptr] = nxtState.build()
                        nxt.add(succId)
                    } else {
                        check(st[fst.ptr] == nxtState.build()) {
                             "processing $blockId, at $succId ${st[fst.ptr]} vs ${nxtState.build()}, $blockToFreshPhi and ${gcPoints.keys}"
                        }
                    }
                }
            };
            gcPoints.forEachEntry { (where, nxt) ->
                nxt.filter {
                    it in eligibleForGc
                }.forEach { node ->
                    if(graph.iterateBlock(where, excludeStart = true).all {
                        node !in gcPoints[it.ptr].orEmpty()
                        }) {
                        val nodeId = st[where]!![node]!!
                        graph.succ(where.block).forEach {
                            partitionFences.add(PartitionFence(
                                src = where.block, dst = it, part = partitions.getPartition(node, nodeId)
                            ))
                        }
                    }
                }
            };
            { nodeSet, where ->
                if(partitions.isSingleton(nodeSet) && nodeSet.single().nodes.single() in eligibleForGc) {
                    val singleton = nodeSet.single().nodes.single()
                    val numbering = st[where]!![singleton]!!
                    partitions.getPartition(where, context = numbering, nodeSet)
                } else {
                    partitions.getPartition(where, nodeSet)
                }
            }
        }


        /**
         * At this point, all processing has been done as part of the population of the annotationSummaryUpdate.
         * "Pull the pin" on the suspended computations, forcing the program updates, which will force the updates
         * of the [InternalFuncArg] and [InternalFuncRet], which themselves force the computation of the [analysis.ip.InternalFuncValueLocation.PointerLayoutInfo]
         */
        for(annot in annotationSummaryUpdates) {
            annot(patching)
        }

        val staged = abiInfo?.runResolution(partitions)

        for((where, nodeGroup) in accesses) {
            fun cellGroup() : UnindexedPartition {
                val p = nodeGroup.single()
                check(p is SingletonCell) {
                    "Invariant violation, expected principle group $p to have omni or long"
                }
                return partitionAt(p.setGroup, where)
            }
            val lc = graph.elab(where)
            check(lc.isMemoryAccess() || lc.isPointsToSummary()) {
                "Found access at $where, but did not recognize the form ${lc.cmd}"
            }
            fun TACSymbol.Var.annotateCell() = this.withMeta {
                it + (UNINDEXED_PARTITION to cellGroup())
            }
            fun TACSymbol.Var.annotateInput() = this.withMeta {
                it + (UNINDEXED_PARTITION to partitionAt(nodeGroup.single {
                    it is LongInput
                }.setGroup, where))
            }
            fun TACSymbol.Var.annotateOutput() = this.withMeta {
                it + (UNINDEXED_PARTITION to partitionAt(nodeGroup.single {
                    it is LongOutput
                }.setGroup, where))
            }
            patching.replace(where) { cmd ->
                when(cmd) {
                    is TACCmd.Simple.ByteLongCopy -> {
                        cmd.copy(
                            dstBase = cmd.dstBase.letIf(cmd.dstBase == TACKeyword.MEMORY.toVar()) {
                                cmd.dstBase.annotateOutput()
                            },
                            srcBase = cmd.srcBase.letIf(cmd.srcBase == TACKeyword.MEMORY.toVar()) {
                                cmd.srcBase.annotateInput()
                            }
                        )
                    }
                    is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                        cmd.copy(
                            base = cmd.base.annotateCell()
                        )
                    }
                    is TACCmd.Simple.AssigningCmd.ByteStore -> {
                        cmd.copy(
                            base = cmd.base.annotateCell()
                        )
                    }
                    is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> {
                        cmd.copy(
                            base = cmd.base.annotateCell()
                        )
                    }
                    is TACCmd.Simple.CallCore -> {
                        val inBaseInd = partitionAt(nodeGroup.single {
                            it is AccessAnnotation.LongInput
                        }.setGroup, where)
                        val outBaseInd = nodeGroup.single {
                            it is AccessAnnotation.LongOutput
                        }.setGroup.let { group ->
                            if(group.size == 1 && group.single().nodes.size == 1 && group.single().nodes.single().let { node ->
                                    node in eligibleForGc && gcPoints[where]?.contains(node) == true
                                }) {
                                graph.succ(where).map {
                                    partitionAt(group, it)
                                }.uniqueOrNull()!!
                            } else {
                                partitionAt(group, where)
                            }
                        }
                        cmd.copy(
                            inBase = cmd.inBase.withMeta {
                                it + (UNINDEXED_PARTITION to inBaseInd)
                            },
                            outBase = cmd.outBase.withMeta {
                                it + (UNINDEXED_PARTITION to outBaseInd)
                            }
                        )
                    }
                    is TACCmd.Simple.ReturnCmd -> {
                        cmd.copy(
                            memBaseMap = cmd.memBaseMap.annotateInput()
                        )
                    }
                    is TACCmd.Simple.RevertCmd -> {
                        cmd.copy(
                            base = cmd.base.annotateInput()
                        )
                    }
                    is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> {
                        cmd.copy(
                            memBaseMap = cmd.memBaseMap.annotateInput()
                        )
                    }
                    is TACCmd.Simple.LogCmd -> {
                        cmd.copy(
                            memBaseMap = cmd.memBaseMap.annotateInput()
                        )
                    }
                    is TACCmd.Simple.SummaryCmd -> {
                        check(cmd.summ is LoopCopyAnalysis.LoopCopySummary)
                        val outGroup = nodeGroup.single {
                            it is AccessAnnotation.LongOutput
                        }
                        val inGroup = nodeGroup.singleOrNull {
                            it is AccessAnnotation.LongInput
                        }
                        cmd.copy(
                            summ = cmd.summ.copy(
                                destMap = cmd.summ.destMap.withMeta {
                                    it + (UNINDEXED_PARTITION to partitionAt(outGroup.setGroup, where))
                                },
                                sourceMap = inGroup?.setGroup?.let { set ->
                                    cmd.summ.sourceMap.withMeta {
                                        it + (UNINDEXED_PARTITION to partitionAt(set, where))
                                    }
                                } ?: cmd.summ.sourceMap
                            )
                        ).mapMeta {
                            it + SUMMARY_VALIDATED
                        }
                    }
                    else -> `impossible!`
                }.let(::listOf)
            }
        }

        return GroupingResult(patching.toCode(prog), staged, partitionFences)
    }

    private fun tryRemoveUnreachableCode(p: CoreTACProgram, pta: IPointsToInformation): CoreTACProgram {
        val paths = pta.query(PrunablePaths)?.takeIf { it.isNotEmpty() } ?: return p
        return p.patching {
            it.selectConditionalEdges(paths.toList())
        }
    }

    private sealed interface MemorySort {
        data object Read : MemorySort
        data object WriteInt : MemorySort
        data object Long : MemorySort
        data class Write(val operand: TACSymbol) : MemorySort

    }

    private data class MemoryAccess(
        val where: LTACCmd,
        val loc: TACSymbol,
        val sort: MemorySort
    )

    private fun trySplitMemory(p: CoreTACProgram, pta: IPointsToInformation): CoreTACProgram {
        fun isMemory(v: TACSymbol.Var) = v.namePrefix == TACKeyword.MEMORY.getName()

        val readWrites = p.analysisCache.graph.commands.parallelStream().filter {
            (it.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && isMemory(it.cmd.base))
                    || (it.cmd is TACCmd.Simple.AssigningCmd.ByteStore && isMemory(it.cmd.base))
                    || (it.cmd is TACCmd.Simple.AssigningCmd.ByteStoreSingle && isMemory(it.cmd.base))
                    || (it.cmd is TACCmd.Simple.LongAccesses && it.cmd.accesses.any {
                        isMemory(it.base)
                        })
        }.map {
            when (it.cmd) {
                is TACCmd.Simple.AssigningCmd.ByteLoad -> listOf(MemoryAccess(it, it.cmd.loc, MemorySort.Read))
                is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> listOf(MemoryAccess(it, it.cmd.loc, MemorySort.WriteInt))
                is TACCmd.Simple.AssigningCmd.ByteStore -> listOf(MemoryAccess(it, it.cmd.loc, MemorySort.Write(it.cmd.value)))
                is TACCmd.Simple.LongAccesses -> {
                    it.cmd.accesses.mapNotNull { access ->
                        if(!isMemory(access.base)) {
                            return@mapNotNull null
                        }
                        MemoryAccess(it, access.offset, MemorySort.Long)
                    }
                }
                else -> error("Impossible")
            }
        }.collect(Collectors.toList()).flatten()

        /**
         * Find all variables that cannot be split, either because they are being hashed, or because they
         * will be allocated and summarized in a summary.
         */
        val internalFunctionPointers = Stream.concat(p.parallelLtacStream().mapNotNull {
            it.ptr `to?` it.snarrowOrNull<InternalCallSummary>()?.let {
                val toBlock = mutableListOf<TACSymbol.Var>()
                it.internalArgs.filter { it.location is InternalFuncValueLocation.PointerWithFields || it.location == null }.mapNotNullTo(toBlock) {
                    it.s as? TACSymbol.Var
                }
                toBlock.takeIf { it.isNotEmpty() }?.asSequence()
            }
        }, Stream.concat(p.parallelLtacStream().flatMap {
            it.snarrowOrNull<InternalCallSummary>()?.let {
                it.internalExits.mapNotNull { ret ->
                    ret.s.takeIf {
                        ret.location is InternalFuncValueLocation.PointerWithFields || ret.location == null
                    }
                }.takeIf { it.isNotEmpty() }

            }?.let { toBlock ->
                p.analysisCache.graph.succ(it.ptr).stream().map {
                    it to toBlock.asSequence()
                }
            } ?: Stream.empty()
        }, p.parallelLtacStream().flatMap {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignSha3Cmd>()?.let {
                it.ptr `to?` (it.cmd.op1 as? TACSymbol.Var)
            }?.let {
                Stream.of(it.first to sequenceOf(it.second))
            } ?: Stream.empty()
        }))

        val ref = mutableMapOf<PTANode, MutableList<LTACCmd>>()
        val badNodes = mutableSetOf<PTANode>()
        val badBase = mutableListOf<PTANode>()
        fun markBadNode(p: PTANode) {
            badNodes.add(p)
            ref.remove(p)
            if(p is StructFieldNode) {
                badBase.add(p.parentNode)
                val it = ref.entries.iterator()
                while(it.hasNext()) {
                    val (k, _) = it.next()
                    if(k is StructFieldNode && k.parentNode == p.parentNode) {
                        it.remove()
                    }
                }
            }
        }

        fun recordNode(p: PTANode, it: LTACCmd) {
            if (p in badNodes) {
                return
            }
            if(p is StructFieldNode && p.parentNode in badBase) {
                return
            }
            ref.computeIfAbsent(p) {
                mutableListOf()
            }.add(it)
        }

        /**
         * Prevent the splitting of struct fields which are going to be summarized later.
         * XXX(jtoman): given early replacement, it seems like this is less of a pressing concern.
         */
        fun markObjectGraphUnsplittable(where: CmdPointer, pts: IPointsToSet) {
            if(pts !is TypedPointsToSet || pts.typeDesc !is TypedPointsToSet.SimpleTypeDescriptor.ReferenceType) {
                return
            }
            pts.accept(where, pta, object : TypedSetVisitor {
                override fun arrayField(s: IndexedWritableSet): VisitResult {
                    pta.contentsOf(where, s)?.let {
                        markObjectGraphUnsplittable(where, it)
                    }
                    return OK
                }

                override fun arrayLengthField(s: IPointsToSet): VisitResult {
                    // we will mark these later as they have type COMPILER
                    return OK
                }

                override fun structField(o: BigInteger, s: WritablePointsToSet): VisitResult {
                    s.nodes.forEach(::markBadNode)
                    markObjectGraphUnsplittable(
                        where, pta.contentsOf(where, s) ?: return OK
                    )
                    return OK
                }

                override fun primitive() {
                }
            })
        }

        for ((ptr, loc) in internalFunctionPointers) {
            for(s in loc) {
                val pts = pta.reachableObjects(ptr, s) ?: continue
                markObjectGraphUnsplittable(where = ptr, pts)
            }
        }
        for ((cmd, loc, sort) in readWrites) {
            val pts = pta.fieldNodesAt(cmd.ptr, loc) ?: continue
            if(pts.type == IPointsToSet.Type.COMPILER) {
                pts.nodes.forEach(::markBadNode)
                continue
            }
            val upd = pts.strongestUpdate()
            /*
               don't split fields of structs that are later written into memory, it prevents the PTA from inferring
               the types of the written struct objects and causes an error later, see CERT-6720
             */
            if(sort is MemorySort.Write && sort.operand is TACSymbol.Var) {
                pta.reachableObjects(cmd.ptr, sort.operand)?.let {
                    markObjectGraphUnsplittable(cmd.ptr, it)
                }
            }
            if(sort == MemorySort.Long || upd != WritablePointsToSet.UpdateType.STRONG) {
                for (d in pts.nodes) {
                    markBadNode(d)
                }
                continue
            }
            pts.nodes.forEach { n ->
                recordNode(n, cmd)
            }
        }
        val patching = p.toPatchingProgram()

        var nodeNameCounter = 0
        val baseName = "certora!Split!${Allocator.getFreshNumber()}"
        for ((_, d) in ref) {
            val nodeName = "$baseName!${nodeNameCounter++}"
            // Make sure we remember this new var represents scalarized memory
            val nodeVar = TACSymbol.Var(nodeName, Tag.Bit256, meta = MetaMap(TACMeta.EVM_MEMORY_SCALARIZED))
            val isBool = d.any {
                (it.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && it.cmd.lhs.tag == Tag.Bool) ||
                        (it.cmd is TACCmd.Simple.AssigningCmd.ByteStore && it.cmd.value is TACSymbol.Var && it.cmd.value.tag == Tag.Bool)
            }
            if (isBool) {
                continue
            }
            for (c in d) {
                if (c.cmd is TACCmd.Simple.AssigningCmd.ByteStore) {
                    patching.replaceCommand(
                        c.ptr, listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                nodeVar,
                                c.cmd.value.asSym()
                            )
                        )
                    )
                } else if(c.cmd is TACCmd.Simple.AssigningCmd.ByteLoad) {
                    patching.replaceCommand(
                        c.ptr, listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                c.cmd.lhs,
                                nodeVar.asSym()
                            )
                        )
                    )
                } else {
                    check(c.cmd is TACCmd.Simple.ReturnCmd)
                    patching.replaceCommand(
                        c.ptr, listOf(
                            TACCmd.Simple.ReturnSymCmd(nodeVar)
                        )
                    )
                }
            }
            patching.addVarDecl(nodeVar)
        }
        Logger.regression { "Memory splitting of ${p.name} succeeded" }
        return patching.toCode(p)
    }

    fun validateLengthUpdates(m: ITACMethod): CoreTACProgram {
        val prog = m.code as CoreTACProgram
        val graph = prog.analysisCache.graph
        val ai = AllocationAnalysis.runAnalysis(graph) ?: return prog
        val pta = PointsToAnalysis(
            method = m,
            graph = graph,
            allocSites = ai.abstractAllocations,
            initialFreePointerValue = ai.initialFreePointerValue,
            scratchSite = ai.scratchReads,
            allowLengthUpdates = true
        ).let {
            if(it.failures.isNotEmpty()) {
                return prog
            }
            FlowPointsToInformation(
                graph = graph,
                pta = it,
                allocationInformation = ai,
                isCompleteSuccess = true
            )
        }
        val needsProofObligations = pta.query(LengthUpdateBoundQuery)?.takeIf { it.isNotEmpty() } ?: return prog
        return needsProofObligations.stream().map { lenUpdate ->
            val newVal = lenUpdate.cmd.value
            val loc = lenUpdate.cmd.loc
            val currLength = TACKeyword.TMP(Tag.Bit256, "currLength!")
            val replacement = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.ByteLoad(
                    lhs = currLength,
                    loc = loc,
                    base = TACKeyword.MEMORY.toVar()
                )
            ), setOf(loc, currLength)) andThen ExprUnfolder.unfoldPlusOneCmd(
                "lengthAssertion",
                TACExprFactTypeCheckedOnlyPrimitives {
                    newVal.asSym() le currLength.asSym()
                }
            ) {
                TACCmd.Simple.AssertCmd(it.s, "Auto-generated unsafe array update")
            } andThen lenUpdate.cmd.mapMeta {
                it + TACMeta.VALIDATED_LENGTH_UPDATE
            }
            lenUpdate.ptr to replacement
        }.patchForEach(prog, check = true) { (where, repl) ->
            replaceCommand(where, repl)
        }
    }
}

fun CoreTACProgram.getPartitionInfo(): Map<Partition, Map<PartitionField, Partition>> {
    return this.parallelLtacStream().mapNotNull {
        it.maybeAnnotation(UNINDEXED_PARTITION_INFO)?.let { unindexedInfo ->
            val calleeIdx = it.ptr.block.calleeIdx
            unindexedInfo.info.map { (unindexedPartition, unindexedFields) ->
                unindexedPartition.indexed(calleeIdx) to unindexedFields.map { (field, unindexedFieldPartition) ->
                    field to unindexedFieldPartition.indexed(calleeIdx)
                }.toTreapMap()
            }.toTreapMap()
        }
    }.reduce(treapMapOf<Partition, TreapMap<PartitionField, Partition>>(), { acc, map -> acc.union(map) { _, v1, _ -> v1 } })
}
