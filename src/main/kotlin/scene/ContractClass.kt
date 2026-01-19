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

package scene

import analysis.EthereumVariables
import analysis.ExternalMapGetterSummarization
import analysis.alloc.*
import analysis.icfg.InternalCVLSummarizer
import analysis.icfg.Summarizer
import analysis.icfg.Summarizer.resolveCandidates
import analysis.ip.InternalFunctionHint
import analysis.ip.JUMP_SYM
import analysis.loop.LoopHoistingOptimization
import analysis.loop.LoopInterpolation
import analysis.maybeNarrow
import analysis.opt.*
import analysis.pta.InitAnnotation
import analysis.pta.LoopCopyAnalysis
import bridge.ContractInstanceInSDC
import bridge.EVMExternalMethodInfo
import cache.ContractLoad
import com.certora.collect.*
import compiler.SourceContext
import config.Config
import config.ReportTypes
import datastructures.stdcollections.*
import decompiler.Decompiler
import diagnostics.*
import disassembler.DisassembledEVMBytecode
import evm.EVM_WORD_SIZE
import evm.SighashInt
import instrumentation.ImmutableInstrumenter
import instrumentation.StoragePackedLengthSummarizer
import instrumentation.calls.CalldataEncoding
import instrumentation.constructor.ConstructorInstrumentation
import instrumentation.createEmptyProgram
import instrumentation.transformers.*
import log.*
import normalizer.*
import optimizer.FreePointerScalarizer
import optimizer.OptimizeBasedOnPointsToAnalysis
import optimizer.RedundantStorageReadOptimizer
import optimizer.RevertStringsOptimizer
import parallel.ParallelPool.Companion.runInherit
import parallel.Scheduler.compute
import parallel.forkEvery
import parallel.pcompute
import spec.CVL
import spec.CVLReservedVariables
import spec.cvlast.SpecCallSummary
import tac.*
import utils.*
import vc.data.*
import verifier.*
import verifier.ContractUtils.computeMethodsInCode
import verifier.ContractUtils.transformMethod
import java.math.BigInteger


/**
 * The [ContractClass] implementation of [IContractClass] assumes methods are given as a mapping from Sighash IDs to [ITACMethod]s.
 * It assumes a notion of storage
 * It originates from some [ContractInstanceInSDC] for metadata fetching
 */
class ContractClass(
    override val src: ContractInstanceInSDC,
    decompiledResult: ContractUtils.SimplifiedDecompiledContract,
    storageLayout: TACStorageLayout?,
    transientStorageLayout: TACStorageLayout?,
    bytecode: DisassembledEVMBytecode,
    constructorBytecode: DisassembledEVMBytecode,
    perContract: IPerContractClassCache,
    cvl: CVL?
) : MapBackedContractClass(
    instanceId = src.address,
    instanceIdIsStaticAddress = src.is_static_address,
    name = src.name,
    bytecode = bytecode,
    constructorBytecode = constructorBytecode,
    storageLayout = storageLayout,
    transientStorageLayout = transientStorageLayout
), IContractWithSource {

    // constructor whole-contract flow
    override val constructorMethod = run {
        val evmConstructor = Decompiler.decompileEVMConstructor(
            constructorBytecode,
            constructorCodeName,
            src.address,
            SourceContext(src.srclist, src.sourceDir)
        )
        val prog = perContract.load(ContractLoad.Component.Constructor) {
            // as with the runtime bytecode, we first simplify and normalize
            inCode(evmConstructor) {
                val simplified = ContractUtils.simplify(evmConstructor, constructorBytecode, src, isConstructor = true)
                ArtifactManagerFactory().dumpCodeArtifacts(simplified, ReportTypes.CFG, StaticArtifactLocation.Outputs, DumpTime.AGNOSTIC)
                CoreTACProgram.Linear(simplified)
                    // as usual, apply DSA first
                    .map(CoreToCoreTransformer(ReportTypes.DSA) {
                        TACDSA.simplify(it, isErasable = FilteringFunctions.default(it, keepRevertManagment = true)::isErasable)
                    })
                    // an additional freememptr scalarization pass for constructors especially
                    .map(CoreToCoreTransformer(
                        ReportTypes.FREE_MEM_POINTER_SCALARIZE
                    ) { c: CoreTACProgram -> FreePointerScalarizer.doWork(c, src.lang, constructorBytecode, isConstructor=true) })
                    // a pass dedicated to improving the precision of immutables, see [ImmutableInstrumenter]
                    .map(CoreToCoreTransformer(ReportTypes.INSTRUMENT_IMMUTABLES) {
                        ImmutableInstrumenter.instrument(it, src)
                    })
                    //this map mark commands that are constructor code data. when cloning the contract we'll have the address,
                    // and it will be replaced with the ConstructorCodeData command with relevant address
                    .map(CoreToCoreTransformer(ReportTypes.CONSTRUCTOR_INSTRUMENTATION) {
                        ConstructorInstrumentation.markConstructorCodeData(it, src.address)
                    }).ref
            }
        }
        val constructorMethodDef = src.getConstructor()
        TACMethod(
            if (prog.code.containsKey(StartBlock)) {
                prog
            } else {
                // gracefully handle old solidity versions
                createEmptyProgram(constructorCodeName)
            },
            this,
            MetaMap(),
            MethodAttribute.Unique.Constructor,
            EVMExternalMethodInfo.fromMethodAndContract(constructorMethodDef, src)
        )
    }

    override fun getConstructor(): ITACMethod = constructorMethod

    companion object {
        fun transformCommon(prog: CoreTACProgram): CoreTACProgram {
            val transformersToApply = mutableListOf(
                // first pass is DSA, also removing dead assignments in the process.
                // this guarantees every path has just a single assignment into any variable
                CoreToCoreTransformer(ReportTypes.DSA) {
                    TACDSA.simplify(it, isErasable = FilteringFunctions.default(it, keepRevertManagment = true)::isErasable)
                },
                // a pass that removes a specific pattern on unreachable code generated by solc,
                // namely x = a & 1; if (x == 0) { ... } else if (x == 1) { ... } else { ...unreachable!... }
                CoreToCoreTransformer(
                    ReportTypes.UNREACHABLE_UNPACKING_CODE_FINDER, UnreachableUnpackingCodeFinder::removeUnreachable
                ),
                // a pass that gets rid of an extra LNot operation by swapping the dst and elseDst of a jumpi,
                // and the original condition with the negated variable it holds the value of
                CoreToCoreTransformer(
                    ReportTypes.JUMP_COND_NORMALIZATION, JumpConditionNormalizer::normalizeConditions
                ),
                // a pass that normalizes jump conditions of the form a==b where a and b are constants
                CoreToCoreTransformer(
                    ReportTypes.SPURIOUS_JUMP_NORMALIZATION, ConstantJumpConditionNormalizer::normalize
                ),
                // get rid of blocks whose only goal is to jump to a single successor block
                CoreToCoreTransformer(
                    ReportTypes.SINGLE_SUCC_JUMP_NORMALIZATION, DummyJumpNodeNormalizer::normalize
                )
            )

            // Calldata splitting extracts tacCalldatabuf array to symbols tacCalldata!offset, as much as it can...
            if (Config.EnableCalldataSplitting.get()) {
                transformersToApply.add(CoreToCoreTransformer(ReportTypes.ALIAS, CalldataSplitter::splitCalldata))
            }

            return ChainedCoreTransformers(transformersToApply).transform(prog)
        }

        /**
         * skips certain passes if this contract class name is marked for 'lite-loading' via the
         * (intentionally) undocudment cvt.lite.loader flag, or if it is a library marked in the spec as having
         * a 'catch-all' summary making running passes moot (the implementations of this library can never be inlined)
         */
        private fun isLiteClass(s: String) = System.getProperty("cvt.lite.loader")?.let {
            @Suppress("ForbiddenMethodCall")
            it.split(",").contains(s)
        } == true

        /**
            Info about a TAC method that is usable during [ContractClass] construction (i.e., before the [ContractClass]
            is fully initialized).  This allows us to run method transformations during contract construction.
        */
        private class UnderConstructionTACMethod(
            private val contractName: String,
            private val contractInstanceId: BigInteger,
            private var _code: CoreTACProgram,
            override val attribute: MethodAttribute,
            override val evmExternalMethodInfo: EVMExternalMethodInfo
        ) : IBoundTACMethod {

            override var code: ICoreTACProgram
                get() = _code
                set(value) { _code = value as CoreTACProgram }

            override fun getContainingContract() = object : ICVLContractClass {
                override val name get() = contractName
                override val instanceId get() = contractInstanceId
            }

            override val meta get() = MetaMap()

            override val sigHash = evmExternalMethodInfo.sigHash?.let { SighashInt(it) }
            override val name get() = evmExternalMethodInfo.name
            override val soliditySignature = evmExternalMethodInfo.getPrettyName()

            override val calldataEncoding = if (attribute !is MethodAttribute.Unique.Fallback) {
                CalldataEncoding.calldataOf(_code, evmExternalMethodInfo)
            } else {
                CalldataEncoding.empty()
            }

            override fun fork() = UnderConstructionTACMethod(
                contractName = contractName,
                contractInstanceId = contractInstanceId,
                _code = _code.copy(
                    code = _code.code.toMap(),
                    blockgraph = _code.blockgraph
                ),
                attribute = attribute,
                evmExternalMethodInfo = evmExternalMethodInfo,
            )

            override fun toString(): String = soliditySignature

            override fun dump(dumpType: ReportTypes, where: String, time: DumpTime) {
                _code.dump(dumpType, where, time)
            }

            override fun dumpBinary(where: String, label: String): TACFile {
                return _code.dumpBinary(where, label)
            }

            override val defaultType get() = _code.defaultType

            fun toTACMethod(containingContract: IContractClass): TACMethod {
                check(
                    contractName == containingContract.name &&
                    contractInstanceId == containingContract.instanceId
                ) {
                    "Cannot convert UnderConstructionTACMethod to TACMethod for a different contract"
                }
                return TACMethod(
                    _code.copy(
                        procedures = setOf(
                            Procedure(0, containingContract, name, evmExternalMethodInfo.sourceSegment?.range)
                        )
                    ),
                    containingContract,
                    meta,
                    attribute,
                    evmExternalMethodInfo,
                    sigHash,
                    name,
                    soliditySignature,
                    calldataEncoding
                )
            }
        }

        // these transforms are applied on the decomposed methods
        private fun standardProverMethodTransforms(
            cvl: CVL?,
            src: ContractInstanceInSDC
        ): (BigInteger?) -> ChainedMethodTransformers<UnderConstructionTACMethod> {
            val nm = src.name
            val useLiteLoader = isLiteClass(nm) || (cvl?.external?.any { (ext, _) ->
                ext is CVL.ExternalAnyInContract && ext.hostContract.name == nm
            } == true && src.isLibrary)
            val transforms = mutableListOf<MethodToCoreTACTransformer<UnderConstructionTACMethod>>()

            // utility functions
            fun MutableList<MethodToCoreTACTransformer<UnderConstructionTACMethod>>.add(ty: ReportTypes, f: (CoreTACProgram) -> CoreTACProgram) {
                this.add(CoreToCoreTransformer(ty, f).lift())
            }
            fun MutableList<MethodToCoreTACTransformer<UnderConstructionTACMethod>>.add(ty: ReportTypes, f: (UnderConstructionTACMethod) -> CoreTACProgram) {
                this.add(MethodToCoreTACTransformer(ty, f))
            }

            fun MutableList<MethodToCoreTACTransformer<UnderConstructionTACMethod>>.addExpensive(ty: ReportTypes, f: (UnderConstructionTACMethod) -> CoreTACProgram) {
                if(useLiteLoader) {
                    return
                }
                this.add(ty, f)
            }

            fun MutableList<MethodToCoreTACTransformer<UnderConstructionTACMethod>>.addExpensive(ty: ReportTypes, f: (CoreTACProgram) -> CoreTACProgram) {
                if(useLiteLoader) {
                    return
                }
                this.add(ty, f)
            }

            // start updating the list

            val internalSummaries = cvl?.internal
            if(internalSummaries != null) {
                // if we have internal summaries, let's apply those early on and save time
                transforms.add(ReportTypes.EARLY_SUMMARIZATION) { c: CoreTACProgram ->
                    InternalCVLSummarizer.EarlySummarization.applyEarlyInternalSummarization(c, cvl)
                }
                /**
                 * It may seem odd that we are inlining reroute summaries so early in the pipeline, when almost all other internal
                 * summaries are done later in the [rules.CompiledRule] flow.
                 *
                 * There are two reasons for this:
                 * 1. Unlike expression summaries, which require the scene and CVL compilation, reroute summaries can be
                 * inlined immediately; similar to nondet/havoc, and more importantly
                 * 2. The rerouting encoding/decoding touches memory and allocations, and thus must be added here so it
                 * is properly processed by [OptimizeBasedOnPointsToAnalysis] and the [analysis.pta.SimpleInitializationAnalysis].
                 */
                transforms.add(ReportTypes.REROUTE_SUMMARIES) { c: CoreTACProgram ->
                    InternalFunctionRerouter.reroute(c, cvlQuery = cvl)
                }
            }

            if(Config.EnableAggressiveABIOptimization.get()) {
                /**
                 * After doing early summarization, remove any references to calldata in internal function annotations
                 * (by removing those annotations). For the reasons behind doing this, see [ABIOptimizations.removeInternalFunctionParams]
                 */
                transforms.add(ReportTypes.REMOVE_CALLDATA_REFERENCES) { c: CoreTACProgram ->
                    ABIOptimizations.removeInternalFunctionParams(c)
                }
            }

            transforms.add(ReportTypes.SPURIOUS_FP_UPDATE_REMOVAL, SpuriousFreePointerUpdateRemoval::transformAddition)
            transforms.add(ReportTypes.SPURIOUS_FP_UPDATE_REMOVAL_DIRECT, SpuriousFreePointerUpdateRemoval::transformIdentity)
            if(Config.EnableOptimisticSpillLocations.get()) {
                transforms.add(ReportTypes.OPTIMISTIC_SPILL_REWRITE, OptimisticSpillRewriter::rewrite)
            }

            // these passes are directed at helping PTA not fail
            transforms.add(ReportTypes.NOTE_MODIFIER_REWRITER, NoteModifierRewriter::transform)
            transforms.add(ReportTypes.BYTESK_INSTRUMENTER, BytesKCastRewriter::rewrite)
            transforms.add(ReportTypes.DEOPTIMIZE_MULTI_STRUCTS, StructAllocationDeoptimizer::rewrite)
            transforms.add(ReportTypes.DEOPTIMIZE_STRUCT_ARRAYS, StructArrayAllocationDeoptimizer::rewrite)
            transforms.add(ReportTypes.OBJECT_REORDERING_FENCE, ReorderObjectInitialization::reorderingFenceInstrumentation)
            transforms.add(ReportTypes.REORDER_OBJECT_INITIALIZATION, ReorderObjectInitialization::rewrite)
            transforms.add(ReportTypes.NORMALIZE_STORAGE_PACKING, EnumPackingNormalizer::normalize)
            // I suspect this one was already done before and can be lifted from elsewhere...
            transforms.add(ReportTypes.SIMPLIFIED) { c: CoreTACProgram ->
                c.patching { patch ->
                    c.parallelLtacStream().filter {
                        it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.annot.k == JUMP_SYM
                    }.sequential().forEach {
                        patch.replaceCommand(it.ptr, listOf())
                    }
                }
            }
            transforms.add(ReportTypes.MAP_GETTER_SUMMARIZATION, ExternalMapGetterSummarization::annotate)

            transforms.add(ReportTypes.RETSIZE_SIMPLIFY, ReturnSizeCalculationSimplifer::rewriteReturnSizeCalc)
            transforms.add(ReportTypes.PLUS_NEG_31_SIMPLIFY, MathPeepholeOptimizer::subtractionAsAddSimplifier)

            transforms.add(ReportTypes.CONSTANT_FOLDING, ConstantComputationInliner::rewriteConstantCalculations)

            transforms.add(ReportTypes.TRIVIAL_SUBTRACT_SIMPLIFY, MathPeepholeOptimizer::trivialSubtractionSimplifier)
            transforms.add(ReportTypes.BASIC_ARITH_SIMPLIFY_CONST_SUB_THEN_ADD, ConstantComputationInliner::rewriteConstViaSubThenAdd)
            transforms.add(ReportTypes.ADDED_MISSING_FREE_PTR_WRITE, FreePointerReadFixer::addMissingFreePointerWrite)

            transforms.addExpensive(ReportTypes.LOOP_SUMMARY_INSTRUMENTATION, LoopInterpolation::interpolateLoopSummaries)

            if(!Config.EquivalenceCheck.get()) {
                transforms.add(ReportTypes.FIXED_BOOL_COMPARISON, BoolComparisonFixer::fix)
                transforms.add(ReportTypes.HOIST_LOOPS, LoopHoistingOptimization::hoistLoopComputations)
            }

            transforms.add(ReportTypes.NORMALIZE_REDUNDANT_FP_UPDATES, RedundantFreePointerNormalizer::rewrite)

            transforms.add(ReportTypes.USE_LATEST_FREEMEM, UnoptimizeFreeMem::doWork)
            transforms.add(ReportTypes.EQUALITY_CHECK_NORMALIZATION, EqualityCheckNormalizer::rewrite)
            transforms.add(ReportTypes.INT32_SCRATCH_NORMALIZATION, Int32ScratchNormalizer::rewrite)

            transforms.add(ReportTypes.RETURN_BUFFER_ANALYSIS) { it: CoreTACProgram ->
                ReturnBufferAnalysis.analyze(it)
            }

            transforms.addExpensive(ReportTypes.BASIC_ARITH_SIMPLIFY_POINTER_SIMPLE, PointerSimplification::simplify)
            transforms.add(
                ReportTypes.FIXED_FREE_PTR_READ,
                /* rewrite:
                   fp = v
                   d = v + a....
                   to:
                   fp = v
                   fresh = fp
                   d = fresh + a ....
                 */
                FreePointerReadFixer::fixFreePointerRead
            )
            transforms.add(
                ReportTypes.LOG_FP_REUSE_NORMALIZATION, FreePointerAliasLogRewrites::rewrite
            )
            transforms.add(
                ReportTypes.HASH_FP_REUSE_NORMALIZATION, FreePointerAliasHashRewrites::rewrite
            )

            transforms.add(ReportTypes.TRIVIAL_SHIFT_SIMPLIFY, MathPeepholeOptimizer::trivialShiftSimplifier)

            transforms.add(ReportTypes.SHIFT_FREE_POINTER_OPS, InitializationPointerShift::shiftPointerComputation)
            transforms.add(ReportTypes.MERGED, BlockMerger::mergeBlocks)
            transforms.add(ReportTypes.SUMMARIZE_STORAGE_LENGTH, StoragePackedLengthSummarizer::rewrite)
            transforms.add(ReportTypes.OPTIMIZE_STORAGE_READS, RedundantStorageReadOptimizer::optimizeReads)

            transforms.add(ReportTypes.COLLAPSE_EMPTY_DSA, TACDSA::collapseEmptyAssignmentBlocks)

            transforms.add(ReportTypes.REWRITE_ALLOCATIONS, InlineArrayAllocationRewriter::rewriteAllocations)
            transforms.add(ReportTypes.REWRITE_ALLOCATIONS_FROM_LOOP_INTERPOLATION, LoopInterpolationAllocationRewriter::rewrite)
            transforms.add(ReportTypes.NORMALIZE_TRY_CATCH, TryCatchNormalization::normalizeTryCatch)
            // this analysis is known to be problematic with the equivalence checker,
            // it will only sometimes fire for some hard to predict subset of reverts, leading to spurious mismatches
            // in revert reasons
            if(!Config.EquivalenceCheck.get()) {
                transforms.add(ReportTypes.OPTIMIZE_REVERT_STRINGS, RevertStringsOptimizer::optimizeRevertStrings)
            }

            transforms.add(ReportTypes.SCRATCH_COPY_NORMALIZATION) { c: CoreTACProgram ->
                c.parallelLtacStream().mapNotNull {
                    it.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf {
                        it.cmd.dstBase == TACKeyword.MEMORY.toVar() && (it.cmd.dstOffset as? TACSymbol.Const)?.value == BigInteger.ZERO &&
                                (it.cmd.length as? TACSymbol.Const)?.value?.let {
                                    it == EVM_WORD_SIZE || it == 64.toBigInteger()
                                } == true && it.cmd.srcOffset is TACSymbol.Const
                    }
                }.map {
                    val len = (it.cmd.length as TACSymbol.Const).value
                    /* the condition on it.cmd.length in the filter above means this check will succeed
                        and ensures in the true branch below len must be 64
                     */
                    check(len == EVM_WORD_SIZE || len == 64.toBigInteger())
                    val toRet = mutableListOf(
                        TACCmd.Simple.AssigningCmd.ByteLoad(
                            lhs = TACKeyword.MEM0.toVar(),
                            base = it.cmd.srcBase,
                            loc = it.cmd.srcOffset
                        )
                    )
                    if(len != EVM_WORD_SIZE) {
                        toRet.add(
                            TACCmd.Simple.AssigningCmd.ByteLoad(
                                lhs = TACKeyword.MEM32.toVar(),
                                base = it.cmd.srcBase,
                                loc = (it.cmd.srcOffset as TACSymbol.Const).value.plus(EVM_WORD_SIZE).asTACSymbol()
                            )
                        )
                    }
                    it.ptr to toRet
                }.sequential().let { str ->
                    c.patching { p ->
                        p.addVarDecl(TACKeyword.MEM0.toVar())
                        p.addVarDecl(TACKeyword.MEM32.toVar())
                        str.forEach { (where, cmd) ->
                            p.replaceCommand(where, cmd)
                        }
                    }
                }
            }

            transforms.addExpensive(ReportTypes.INITIALIZATION) { c: CoreTACProgram ->
                if (Config.EnabledInitializationAnalysis.get() && !Config.EquivalenceNoPTA) {
                    InitAnnotation.annotateInitializationWindows(c)
                } else {
                    c
                }
            }

            transforms.addExpensive(ReportTypes.COPY_LOOP_SUMMARIZATION, LoopCopyAnalysis::annotateLoops)

            transforms.add(ReportTypes.FUNCTION_ANNOTATIONS_REMOVAL) { c: CoreTACProgram ->
                AnnotationRemover.removeAnnotations(c, listOf(InternalFunctionHint.META_KEY))
            }

            transforms.add(ReportTypes.OPAQUE_IDENTITY_REMOVAL, AnnotationRemover::removeOpaqueIdentities);

            if(Config.AllowArrayLengthUpdates.get()) {
                transforms.addExpensive(ReportTypes.ARRAY_LENGTH_UPDATE_INSTRUMENTATION) { m: UnderConstructionTACMethod ->
                    OptimizeBasedOnPointsToAnalysis.validateLengthUpdates(m)
                }
            }

            if(!Config.EquivalenceCheck.get()) {
                transforms.addExpensive(ReportTypes.MEMORY_SPLITTER_AND_BRANCH_PRUNER) { m: UnderConstructionTACMethod ->
                    OptimizeBasedOnPointsToAnalysis.doWork(m)
                };
            }

            return cb@{ sighash: BigInteger? ->
                if(cvl != null) {
                    val summarizedBy = Summarizer.getExplicitSummariesForInvocation(
                        onExactSummaryMiss = { _ -> },
                        summaries = cvl.external,
                        nameLookup = {
                            if(it == src.address) {
                                src.name
                            } else {
                                null
                            }
                        },
                        hostContractId = src.address,
                        sigHash = sighash
                    ).resolveCandidates()
                    if(summarizedBy?.specCallSumm?.summarizationMode == SpecCallSummary.SummarizationMode.DELETE) {
                        return@cb ChainedMethodTransformers(listOf(
                            CoreToCoreTransformer(ReportTypes.DELETION_SUMMARY) { c ->
                                val summary = summarizedBy.specCallSumm as SpecCallSummary.ExpressibleInCVL
                                val root = c.entryBlockId
                                CoreTACProgram(
                                    blockgraph = BlockGraph(root to treapSetOf()),
                                    ufAxioms = UfAxioms.empty(),
                                    symbolTable = TACSymbolTable.empty(),
                                    procedures = setOf(),
                                    name = c.name,
                                    check = true,
                                    entryBlock = root,
                                    code = mapOf(root to listOf(
                                        TACCmd.Simple.AssertCmd(TACSymbol.False, "According to deletion summary at ${summary.range}," +
                                            " the function ${c.name} was never supposed to be called from spec, but it was.")
                                    ))
                                )
                            }.lift<UnderConstructionTACMethod>()
                        ))
                    }
                }
                ChainedMethodTransformers(transforms)
            }
        }
    }

    override val methods: Map<BigInteger?, TACMethod> = run {
        val transformers = standardProverMethodTransforms(cvl, src)

        val codes = perContract.load(ContractLoad.Component.Codes) {
            computeMethodsInCode(decompiledResult)
                .letIf(!Config.includeEmptyFallback.get()) {
                    it.filter { (methodInfo, methodCode) ->
                        !methodIsEmptyFallback(methodInfo.name, methodCode)
                    }
                }
        }

        val methods = codes.entries.forkEvery { (info, prog) ->
            compute {
                info to inCode(prog) {
                    perContract.load(ContractLoad.Component.MethodLoad(info.name, info.sigHash)) {
                        // Construct a temporary TAC method for purposes of this transform.  We can't use the full
                        // [TACMethod] here because it would give transforms access to the whole [ContractClass], which
                        // is not yet fully constructed!
                        UnderConstructionTACMethod(
                            name,
                            instanceId,
                            prog,
                            if (info.isFallback) {
                                MethodAttribute.Unique.Fallback
                            } else {
                                MethodAttribute.Common
                            },
                            info
                        ).let {
                            transformMethod(it, transformers(info.sigHash))
                        }
                    }
                }
            }
        }.pcompute().runInherit()

        // Convert to full TACMethods, now that we're done running transforms.
        methods.associate { (info, method) ->
            info.sigHash to method.toTACMethod(this)
        }.also {
            check(checkSighash(it)) { "Sighash mapping is invalid" }
        }
    }

    override val wholeContractMethod: TACMethod? = decompiledResult.wholeProgram?.let { wholeProgram ->
        TACMethod(
            name,
            inCode(wholeProgram) {
                perContract.load(ContractLoad.Component.WholeMethod) {
                    transformCommon(wholeProgram)
                }
            },
            this,
            MetaMap(),
            MethodAttribute.Unique.Whole
        )
    }


    override var storageInfoField: IStorageInfo =
        StorageInfoWithReadTracker(storageVariables = mapOf(EthereumVariables.getStorageInternal(this.instanceId) to null))
    override var transientStorageInfoField: IStorageInfo =
        StorageInfo(storageVariables = setOf(EthereumVariables.getTransientStorageInternal(this.instanceId)))


    override fun fork(): IContractClass {
        return ForkedContractClass(
            src = src,
            wholeCode = this.wholeContractMethod?.fork(),
            constructorCode = this.constructorMethod.fork(),
            methods = this.methods.values.map { it.fork() },
            storageLayout = this.getStorageLayout(),
            transientStorageLayout = this.getTransientStorageLayout(),
            storageInfoField = this.storageInfoField,
            transientStorageInfoField = this.transientStorageInfoField,
            bytecode = this.bytecode,
            constructorBytecode = this.constructorBytecode
        )
    }
}

/**
 * often contracts do not have an explicit fallback method, but a fallback always exists.
 * this "empty fallback" is simply the reverting case of having no other method in the contract
 * which matches the provided input buffer.
 */
internal fun methodIsEmptyFallback(methodName: String, methodCode: CoreTACProgram): Boolean {
    /** XXX: should avoid explicitly naming [CVLReservedVariables] here, like how [EVMExternalMethodInfo.isFallback] is used */
    if (methodName != CVLReservedVariables.certorafallback_0.name) {
        return false
    }

    /** to qualify as an empty fallback, the set of non-reverting leaf nodes must be empty */
    return methodCode.blockgraph.leafNodes().all { node ->
        val nodeCode = methodCode.code[node]
            ?: error("node $node of method $methodName not found in $methodCode")

        nodeCode.lastOrNull() is TACCmd.Simple.RevertCmd
    }
}
