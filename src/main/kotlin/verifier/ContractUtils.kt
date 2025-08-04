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

package verifier

import analysis.CmdPointer
import analysis.EthereumVariables.simplify
import analysis.icfg.InternalCVLSummarizer
import analysis.ip.FunctionFlowAnnotator
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.JUMP_SYM
import analysis.ip.SourceFinderAnnotator
import analysis.maybeNarrow
import analysis.opt.Deduplicator
import analysis.opt.TernarySimplifier
import analysis.split.SplitContext.Companion.storageLoc
import analysis.type.TypeRewriter
import bridge.ContractInstanceInSDC
import bridge.EVMExternalMethodInfo
import cache.ContractLoad
import com.certora.collect.*
import config.ReportTypes
import datastructures.stdcollections.*
import decompiler.Decompiler
import decompiler.Disassembler
import diagnostics.inCode
import disassembler.DisassembledEVMBytecode
import instrumentation.transformers.EnvironmentFixer
import instrumentation.transformers.FilteringFunctions
import instrumentation.transformers.TACDSA
import instrumentation.transformers.optimizeAssignments
import log.*
import normalizer.AnnotationRemover
import normalizer.SighashScalarizer
import optimizer.*
import parallel.ParallelPool.Companion.runInherit
import parallel.Scheduler
import parallel.forkEvery
import parallel.pcompute
import scene.*
import spec.CVL
import statistics.*
import tac.DumpTime
import tac.TACBasicMeta
import utils.*
import vc.data.*
import vc.data.TACMeta.ACCESS_PATHS
import vc.data.TACMeta.REVERT_MANAGEMENT
import vc.data.TACMeta.STORAGE_PATHS
import vc.data.TACSymbol.Var.Companion.hasKeyword
import java.math.BigInteger

private val logger = Logger(LoggerTypes.WHOLE_CONTRACT_TRANSFORMATION)
private val loggerTimes = Logger(LoggerTypes.TIMES)

object ContractUtils {
    class SimplifiedDecompiledContract(evm: Decompiler.DecompiledContract, perContract: IPerContractClassCache) : java.io.Serializable {
        val source: ContractInstanceInSDC = evm.source
        val methods = evm.methods.mapValues { (m, code) ->
            perContract.load(ContractLoad.Component.MethodDecompile(m.name, sighash = m.sighash?.toBigInteger(16))) {
                simplify(code, evm.bytecode, evm.source, isConstructor = false)
            }
        }
        val fallback = perContract.load(ContractLoad.Component.MethodDecompile("fallback", null)) {
            simplify(evm.fallback, evm.bytecode, evm.source, isConstructor = false)
        }
        val wholeProgram = evm.wholeProgram?.let {
            perContract.load(ContractLoad.Component.MethodDecompile("whole", null)) {
                simplify(it, evm.bytecode, evm.source, isConstructor = false)
            }
        }
    }

    fun simplify(code: EVMTACProgram, bytecode: DisassembledEVMBytecode, source: ContractInstanceInSDC, isConstructor: Boolean): CoreTACProgram {
        return inCode(code) {
            val simplifiedCode =
                    transformEVMToSimple(code)

            ChainedCoreTransformers(
                listOf(
                    // Decompiler cleanup - merge blocks with same successors and PC
                    CoreToCoreTransformer(
                        ReportTypes.DECOMPILER_CLEANUP
                    ) { c: CoreTACProgram -> BlockMerger.mergeBlocksWithSameSuccessorsAndPC(c) },
                    // This is just for clarity - jumpi metasrcinfo can be copied to the assignment of the condition
                    // variable, if that doesn't have source map already also, let's mark all metaSrc-less commands
                    // with the source of the unique predecessor that does have source information
                    CoreToCoreTransformer(
                        ReportTypes.MORE_META_SRC
                    ) { c: CoreTACProgram ->
                        moreMetaSrc(c)
                    },
                    // Normalize accesses of the free pointer that aren't literal 0x40, but can be found to be
                    CoreToCoreTransformer(
                        ReportTypes.FREE_POINTER_PROPAGATION,
                    ) { c: CoreTACProgram ->
                        FreePointerScalarizer.normalizeFPAccess(c)
                    },
                    // Introduce tacM0x40 scalars if it looks like we're using a bump allocator
                    CoreToCoreTransformer(
                            ReportTypes.FREE_MEM_POINTER_SCALARIZE
                    ) { c: CoreTACProgram -> FreePointerScalarizer.doWork(c, source.lang, bytecode) },
                    // Annotate internal functions
                    CoreToCoreTransformer(
                        ReportTypes.INTERNAL_FUNCTION_ANNOTATED
                    ) { c: CoreTACProgram -> FunctionFlowAnnotator.doAnalysis(c, source) },
                    // validate internal functions
                    CoreToCoreTransformer(
                        ReportTypes.INTERNAL_FUNCTION_VALIDATED
                    ) { c: CoreTACProgram -> FunctionFlowAnnotator.validateIds(c, source) },
                    // Deduplicate blocks; must happen after the function flow analysis
                    CoreToCoreTransformer(
                        ReportTypes.DEDUPLICATED
                    ) { c: CoreTACProgram -> Deduplicator.deduplicateBlocks(c) },
                    // instrument source finders
                    CoreToCoreTransformer(
                        ReportTypes.SOURCE_FINDER_ANNOTATOR
                    ) { c: CoreTACProgram -> SourceFinderAnnotator.annotate(c, source) },
                    // Basic instrumentation
                    CoreToCoreTransformer(
                        ReportTypes.ENV_START_BLOCK
                    ) { c: CoreTACProgram -> EnvironmentFixer(source.address, isConstructor).transform(c) },
                    // Enables the type rewriting
                    CoreToCoreTransformer(
                            ReportTypes.NO_RECYCLE
                    ) { c: CoreTACProgram -> RecycledVarsRemover.noVarRecycle(c) },
                    // SG PROTOTYPE: DSA
                    CoreToCoreTransformer(
                        ReportTypes.DSA
                    ) { c: CoreTACProgram ->
                        TACDSA.simplify(
                            prog = c,
                            isTypechecked = false,
                            isErasable = FilteringFunctions.default(c, keepRevertManagment = true)::isErasable
                        )
                    },
                    // TYPING
                    CoreToCoreTransformer(
                            ReportTypes.TYPE
                    ) { c: CoreTACProgram -> TypeRewriter.boolify(c) },

                    // POST TYPING TRANSFORMS
                    // XOR optimization. c = a xor b, d = c > 0; ==> d = a != b
                    CoreToCoreTransformer(ReportTypes.XOR_SIMPLE, BitwiseOptimizer::xorOptimize),
                    // Introduce SimpleSha expressions from hash applications on the reserved buffer 0x0-0x40
                    CoreToCoreTransformer(
                            ReportTypes.SIMPLE_HASH
                    ) { c: CoreTACProgram -> RawHashToSimpleHashConverter.convertRawHashToSimpleHash(c) },
                    // Convert constant slots that are in fact optimized hash applications back to hash applications
                    CoreToCoreTransformer(
                            ReportTypes.UNOPTIMIZE_HASHES
                    ) { c: CoreTACProgram -> UnoptimizeHashResults(c).doWork() },
                    // Add opaque identify annotations to functions
                    CoreToCoreTransformer(
                        ReportTypes.INTERNAL_FUNCTION_FINDER,
                        InternalCVLSummarizer::addOpaqueIdentityAnnotations
                    ),
                    CoreToCoreTransformer(
                        ReportTypes.TYPE_PEEPHOLE
                    ) { c: CoreTACProgram -> TypeRewriter.peepholeReplacements(c) },
                    // CLEANUP
                    // Remove JUMP_SYM annotations
                    CoreToCoreTransformer(
                    ReportTypes.ANNOTATION_REMOVER
                    ) { c: CoreTACProgram -> AnnotationRemover.removeAnnotations(c, listOf(JUMP_SYM)) },
                    // Remove conditional FP assignments that are not needed
                    CoreToCoreTransformer(
                            ReportTypes.FREE_MEM_POINTER_SCALARIZE_CLEANUP
                    ) { c: CoreTACProgram -> FreePointerScalarizer.cleanup(c) },
                )
            ).transform(simplifiedCode)
        }
    }

    fun getContractClassFromInstance(
        instance: ContractInstanceInSDC,
        perContract: IPerContractClassCache = TrivialContractCache,
        cvl: CVL?
    ): IContractClass {
        val disassembledBytecode = perContract.load(ContractLoad.Component.RuntimeDisassembly) {
            Disassembler.disassembleRuntimeBytecode(instance)
        }
        val disassembledConstructorBytecode = perContract.load(ContractLoad.Component.ConstructorDisassembly) {
            Disassembler.disassembleConstructorBytecode(instance)
        }
        val decompiled = perContract.load(ContractLoad.Component.Decompilation) {
            SimplifiedDecompiledContract(
                Decompiler.decompileEVM(
                    disassembledBytecode,
                    instance
                ),
                perContract
            )
        }
        return ContractClass(
            instance,
            decompiled,
            instance.storageLayout?.toTACStorageLayout(),
            instance.transientStorageLayout?.toTACStorageLayout(),
            disassembledBytecode,
            disassembledConstructorBytecode,
            perContract,
            cvl
        )
    }


    fun transformMethods(
        methods: Map<BigInteger, ITACMethod>,
        p: (IScene, ITACMethod) -> CoreTACProgram,
        scene: IScene
    ): Map<BigInteger, CoreTACProgram> {
        val startTime = System.currentTimeMillis()
        val ret = methods.entries.forkEvery { methodEntry ->
            Scheduler.compute {
                methodEntry.key to p(scene, methodEntry.value)
            }
        }.pcompute().runInherit().toMap()
        val endTime = System.currentTimeMillis()
        loggerTimes.info { "Transformations time ${(endTime - startTime) / 1000}" }
        return ret
    }

    fun transformEVMToSimple(evmCode: EVMTACProgram): CoreTACProgram {
        loggerTimes.info { "TAC simplification start ${evmCode.name}" }
        val simplifyStart = System.currentTimeMillis()
        val simplifiedTACCode = simplify(evmCode)
        val simplifyEnd = System.currentTimeMillis()
        loggerTimes.info { "TAC simplification end ${(simplifyEnd - simplifyStart) / 1000}s" }
        return simplifiedTACCode
    }

    fun <T : ITACMethod> transformMethodInPlace(method: T, transformers: ChainedCoreTransformers) {
        transformMethod_(
            method,
            ChainedMethodTransformers(transformers.l.map { ts: CoreToCoreTransformer -> ts.lift<T>() }),
            true
        )
    }

    fun <T : ITACMethod> transformMethodInPlace(method: T, transformers: ChainedMethodTransformers<T>) {
        transformMethod_(
            method,
            transformers,
            true
        )
    }

    fun <T : ITACMethod> transformMethod(method: T, transformers: ChainedCoreTransformers): T =
        transformMethod_(
            method,
            ChainedMethodTransformers(transformers.l.map { ts: CoreToCoreTransformer -> ts.lift<T>() }),
            false
        )

    fun <T : ITACMethod> transformMethod(method: T, transformers: ChainedMethodTransformers<T>): T =
        transformMethod_(
            method,
            transformers,
            false
        )

    /**
     * The most general transformMethod variant
     */
    private fun <T : ITACMethod> transformMethod_(
        method: T,
        transformers: ChainedMethodTransformers<T>,
        inPlace: Boolean
    ): T {
        logger.info { "Running on ${method.code.name} the following transformations: ${transformers.getReports()}" }
        return inCode(method) {
            transformers.l.fold(method) { oldCode, transformerObj ->
                try {
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt()
                        throw InterruptedException()
                    }
                    loggerTimes.info { "Start Running transform ${transformerObj.reportType} on ${method.code.name}" }
                    val start = System.currentTimeMillis()
                    val newCode = transformerObj.applyTransformer(oldCode)
                    if (inPlace) {
                        @Suppress("DEPRECATION")
                        oldCode.updateCode(newCode)
                    }
                    val end = System.currentTimeMillis()
                    loggerTimes.info { "End Running transform ${transformerObj.reportType} on ${method.code.name} ${(end - start) / 1000}s" }
                    if (inPlace) {
                        oldCode
                    } else {
                        val fork = method.fork().uncheckedAs<T>()
                        @Suppress("DEPRECATION")
                        fork.updateCode(newCode)
                        fork
                    }
                } catch (e: Exception) {
                    throw CertoraException.fromException(e, "Got exception while transforming ${method.code.name}")
                }
            }
        }
    }

    /**
     * Record stats for a single transformation for a single method
     */
    fun recordSingleTransformationStats(tacTransformerTimeStatsRecorder: ElapsedTimeStats, name: String) {
        SDCollectorFactory.collector().collectFeature(
            tacTransformerTimeStatsRecorder.fork(name.toSDFeatureKey()),
        )
    }

    /**
     * Record stats for a single code, and update the sum over all invocations of the same transformer
     */
    fun recordAggregatedTransformationStats(tacTransformerTimeStatsRecorder: ElapsedTimeStats, name: String) {
        recordSingleTransformationStats(tacTransformerTimeStatsRecorder, name)
        SDCollectorFactory.collector().collectFeature(
            AggregatedElapsedTimeStats(
                tacTransformerTimeStatsRecorder,
                TIME_STATS_KEY.toSDFeatureKey()
            ),
        )
    }

    // Transformers

    fun unroll(code: CoreTACProgram): CoreTACProgram {
        // after converting, must get rid of all of the nodes that became unreachable, and only then fix jump PCs.
        @Suppress("NAME_SHADOWING")
        return CoreTACProgram.Linear(code)
            .map(CoreToCoreTransformer(ReportTypes.UNROLL_PRE_CLEANUP) { code ->
                code.convertToLoopFreeCode()
            }).map(CoreToCoreTransformer(ReportTypes.UNROLL_REMOVE_UNREACHABLE) { code ->
                removeUnreachable(code)
            }).ref
    }

    private fun removeUnreachable(code: CoreTACProgram): CoreTACProgram {
        return code.removeNodesNotReachableFromStart()
    }

    /** Method generation: decompose the whole TAC program to its methods.
     * A whole program represents a contract with all methods + dispatcher to them, and the decomposition throws away
     * the dispatcher code, and creates TAC objects for every method (including virtual "fallback" for covering all
     * the blocks that are not pertaining to any particular method. This may include some dispatcher blocks).
     **/
    private val functionBuilderTag = "FUNCTION_BUILDER".toTimeTag()
    fun computeMethodsInCode(code: SimplifiedDecompiledContract): Map<EVMExternalMethodInfo, CoreTACProgram> {
        val functionBuilderStatsRecorder = ElapsedTimeStats().startMeasuringTimeOf(functionBuilderTag)

        // get all defined funcs, and do away with sighash reads
        val funcHashesToSubgraph = code.methods.entries
            .sortedBy { (method, _) -> method.getSigHashInt()?.n ?: error("No sighash for $method") }
            .associate { (method, prog) ->
                EVMExternalMethodInfo.fromMethodAndContract(method, code.source) to prog
            }
            .plus(EVMExternalMethodInfo.getFallback(code.source) to code.fallback)
            .mapValues {
                SighashScalarizer.rewrite(ContractClass.transformCommon(it.value)).apply {
                    ArtifactManagerFactory().dumpCodeArtifacts(this, ReportTypes.CFG, StaticArtifactLocation.Outputs, DumpTime.AGNOSTIC)
                }
            }.mapValues { (_,v) ->
                // If there is at least one FREE_POINTER_SCALARIZED annotation and all such annotations are false, then freePointerScalarized is false.  Otherwise, including if there are no FREE_POINTER_SCALARIZED annotations, default/set to true
                val freePointerScalarized = v.parallelLtacStream().mapNotNull { ltc -> ltc.cmd.maybeAnnotation(FREE_POINTER_SCALARIZED) }.anyMatch { it }
                val patch = v.toPatchingProgram()
                patch.addBefore(
                    v.analysisCache.graph.roots.first().ptr, listOf(
                        TACCmd.Simple.AnnotationCmd(FREE_POINTER_SCALARIZED, freePointerScalarized)
                    )
                )
                patch.toCode(v)
            }

        functionBuilderStatsRecorder.endMeasuringTimeOf(functionBuilderTag)
        recordSingleTransformationStats(functionBuilderStatsRecorder, code.source.name)
        return funcHashesToSubgraph
    }


    // Collection of optimizations
    fun tacOptimizations(): ChainedCoreTransformers {
            return ChainedCoreTransformers(
            listOf(
                CoreToCoreTransformer(
                    ReportTypes.TERNARY_OPTIMIZE
                ) { code -> TernarySimplifier.simplify(code, afterSummarization = false) },
                CoreToCoreTransformer(
                    ReportTypes.MERGED_TAC_OPTIMIZATIONS
                ) { p: CoreTACProgram -> BlockMerger.mergeBlocks(p) },
                CoreToCoreTransformer(
                    ReportTypes.PATH_OPTIMIZE_TAC_OPTIMIZATIONS
                ) { c: CoreTACProgram ->
                    object : Pruner(c) {
                        override val stopAt: ((TACSymbol.Var) -> Boolean) = { v ->
                            TACMeta.TRANSIENT_STORAGE_KEY in v.meta || TACMeta.STORAGE_KEY in v.meta
                        }
                    }.prune()
                  },

                CoreToCoreTransformer(ReportTypes.MERGED) { p: CoreTACProgram -> BlockMerger.mergeBlocks(p) },
                CoreToCoreTransformer(ReportTypes.REMOVE_UNREACHABLE, ContractUtils::removeUnreachable),
                CoreToCoreTransformer(ReportTypes.UNUSED_ASSIGNMENTS) { c ->
                    val preservedVars = buildSet {
                        c.ltacStream().forEach { (_, cmd) ->
                            // keep these because hooks are not inlined yet?
                            (cmd.storageLoc as? TACSymbol.Var)?.meta?.get(ACCESS_PATHS)?.let {
                                addAll(it.getUsedVariables())
                            }
                            // keep these because summaries are not inlined yet?
                            cmd.maybeAnnotation(INTERNAL_FUNC_EXIT)?.let { annot ->
                                addAll(annot.rets.map { it.s })
                            }
                            // keep reads from storage because hooks are not inlined yet
                            (cmd as? TACCmd.Simple.AssigningCmd)?.let { c ->
                                if (STORAGE_PATHS in cmd.meta || REVERT_MANAGEMENT in cmd.meta) {
                                    add(c.lhs)
                                }
                            }
                        }
                    }.toTreapSet()

                    optimizeAssignments(
                        code = c,
                        object : FilteringFunctions {
                            private val TACSymbol.Var.foundryPreserved get() =
                                hasKeyword(TACKeyword.CALLER) || hasKeyword(TACKeyword.ADDRESS)

                            private val TACSymbol.Var.isPreserved get() =
                                this in preservedVars || this.hasKeyword(TACKeyword.MEM64)

                            override fun isInlineable(v: TACSymbol.Var) =
                                !v.isPreserved && !v.foundryPreserved

                            override fun isErasable(cmd: TACCmd.Simple.AssigningCmd) =
                                cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                                    TACBasicMeta.STACK_HEIGHT in cmd.lhs.meta &&
                                    cmd.getRhs().none { it is TACSymbol.Var && it.isPreserved } &&
                                    cmd.freeVars().none { it.foundryPreserved } &&
                                    REVERT_MANAGEMENT !in cmd.meta
                        },
                        strict = true
                    )
                }
            )
        )
    }

    private fun moreMetaSrc(c: CoreTACProgram): CoreTACProgram {
        return copySrcMetaToCondAssignment(c).let {
            copySrcMetaFromPreds(c)
        }
    }

    private fun copySrcMetaFromPreds(c: CoreTACProgram): CoreTACProgram {
        val g = c.analysisCache.graph
        val p = c.toPatchingProgram()
        val patched = mutableSetOf<CmdPointer>()
        g.commands.filter { it.cmd.metaSrcInfo == null }
            .forEach { srclessCmd ->
                // sometimes commands have source mapping, but to a non-existent file.
                // this is a symptom of solc pointing to ITS OWN CODE (you read it right - the compiler's!).
                // which is utterly useless.
                // so we want to migrate _proper_ source maps to a chain of single successors without _proper_ source maps.
                // where proper means - a source map that can actually be mapped back to the source
                g.pred(srclessCmd).singleOrNull()?.takeIf { it.cmd.metaSrcInfo?.sourceFileName() != null }?.let { predWithSrc ->
                    // now collect successors without source
                    val applyTo = mutableSetOf<CmdPointer>()
                    var curr = setOf(srclessCmd.ptr)
                    while (curr.size == 1 // single successor
                        && g.pred(curr.single()).size == 1 // single successor has just one predecessor
                        && g.elab(curr.single()).cmd.metaSrcInfo.let { it?.sourceFileName() == null } // no src meta
                    ) {
                        val currSingle = curr.single()
                        if (g.elab(currSingle).cmd.let {it !is TACCmd.Simple.NopCmd && it !is TACCmd.Simple.JumpCmd}) {
                            // NOPs cannot have meta
                            applyTo.add(currSingle)
                        }
                        curr = g.succ(currSingle)
                    }

                    applyTo.filter { it !in patched }.forEach { ptr ->
                        p.update(ptr) { cmd ->
                            cmd.mapMeta {
                                // preserve the jumpType - otherwise internal function annotations won't work
                                val newMeta = predWithSrc.cmd.metaSrcInfo!!
                                cmd.meta.get(META_INFO_KEY)?.let { origMeta ->
                                    // do we override with add? yes we do
                                    cmd.meta.add(META_INFO_KEY to newMeta.copy(jumpType = origMeta.jumpType))
                                } ?: cmd.meta.add(META_INFO_KEY to newMeta)
                            }
                        }
                        patched.add(ptr)
                    }
                }
            }
        return p.toCodeNoTypeCheck(c)
    }

    private fun copySrcMetaToCondAssignment(c: CoreTACProgram): CoreTACProgram {

        val g = c.analysisCache.graph
        val def = c.analysisCache.def
        val use = c.analysisCache.use
        val candidates = g.commands.mapNotNull { lcmd ->
            lcmd.maybeNarrow<TACCmd.Simple.JumpiCmd>()?.let { jumpiCmd ->
                val cond = jumpiCmd.cmd.cond as? TACSymbol.Var ?: return@mapNotNull null
                val defOfCond = def.defSitesOf(cond, jumpiCmd.ptr)
                jumpiCmd.let {
                    if (it.cmd.metaSrcInfo == null) {
                        null
                    } else {
                        it to defOfCond.filter { g.elab(it).cmd.metaSrcInfo == null }
                    }
                }
            }
        }
        val p = c.toPatchingProgram()
        candidates.forEach { (jumpi, defSitesOfCondVarWithoutSrcMeta) ->
            val srcMetaOfJumpi = jumpi.cmd.metaSrcInfo ?: `impossible!`
            defSitesOfCondVarWithoutSrcMeta.forEach { toAddSrcMeta ->
                // we want this definition to be used only in our jumpi
                if (
                    g.elab(toAddSrcMeta).cmd.getLhs()?.let { lhs ->
                        // VERY surprising if lhs is null
                        use.useSitesAfter(lhs, toAddSrcMeta).singleOrNull() == jumpi.ptr
                    } ?: `impossible!`
                ) {
                    p.update(toAddSrcMeta) { cmd ->
                        cmd.mapMeta { cmd.meta.add(META_INFO_KEY to srcMetaOfJumpi) }
                    }
                }
            }
        }
        return p.toCodeNoTypeCheck(c)
    }

}
