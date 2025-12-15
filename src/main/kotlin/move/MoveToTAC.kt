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

package move

import algorithms.*
import analysis.*
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.controlflow.*
import analysis.loop.*
import analysis.opt.*
import analysis.opt.inliner.*
import analysis.opt.intervals.*
import analysis.opt.overflow.*
import analysis.split.*
import com.certora.collect.*
import compiler.applyKeccak
import config.*
import datastructures.*
import datastructures.stdcollections.*
import diagnostics.*
import evm.MASK_SIZE
import instrumentation.transformers.*
import java.math.BigInteger
import java.nio.ByteBuffer
import log.*
import move.analysis.*
import optimizer.*
import org.jetbrains.annotations.TestOnly
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import verifier.CodeTransformer
import verifier.*

private val logger = Logger(LoggerTypes.MOVE)
private val loggerSetupHelpers = Logger(LoggerTypes.SETUP_HELPERS)

/**
    Conversion from Move bytecode to TAC
 */
class MoveToTAC private constructor (val scene: MoveScene) {
    data class CompiledRule(
        val rule: CvlmRule,
        val moveTAC: MoveTACProgram,
        val isSatisfy: Boolean
    ) {
        fun toCoreTAC(scene: MoveScene): CoreTACProgram {
            return moveTACtoCoreTAC(scene, moveTAC, isSatisfy)
        }
    }

    companion object {
        fun compileRule(rule: CvlmRule, scene: MoveScene) = MoveToTAC(scene).compileRule(rule)

        @TestOnly
        fun compileMoveTAC(
            entryFuncName: MoveFunctionName,
            scene: MoveScene,
        ) = with(scene) {
            val def = maybeDefinition(entryFuncName) ?: error("No function found with name $entryFuncName")
            MoveToTAC(scene).compileMoveTACProgram(
                entryFuncName.toString(),
                MoveFunction(def.function, typeArguments = listOf()),
                SanityMode.NONE,
                parametricTargets = mapOf()
            )
        }

        private fun moveTACtoCoreTAC(scene: MoveScene, code: MoveTACProgram, isSatisfy: Boolean) =
            CoreTACProgram.Linear(
                code
                .transform(ReportTypes.DEDUPLICATED) { deduplicateBlocks(it) }
                .mergeBlocks()
                .also { TACSizeProfiler().profile(it, "presimplified") }
                .transform(ReportTypes.SIMPLIFIED) { MoveTACSimplifier(scene, it).transform() }
                .also { TACSizeProfiler().profile(it, "simplified") }
            )
            .map(CoreToCoreTransformer(ReportTypes.DSA, TACDSA::simplify))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.COLLAPSE_EMPTY_DSA, TACDSA::collapseEmptyAssignmentBlocks))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.HOIST_LOOPS, LoopHoistingOptimization::hoistLoopComputations))
            .map(CoreToCoreTransformer(ReportTypes.UNROLL, CoreTACProgram::convertToLoopFreeCode))
            .map(CoreToCoreTransformer(ReportTypes.MATERIALIZE_CONDITIONAL_TRAPS, ConditionalTrapRevert::materialize))
            .mapIf(isSatisfy, CoreToCoreTransformer(ReportTypes.REWRITE_ASSERTS, wasm.WasmEntryPoint::rewriteAsserts))
            .ref

        fun optimize(code: CoreTACProgram) =
            CoreTACProgram.Linear(code)
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.SNIPPET_REMOVAL, SnippetRemover::rewrite))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE, ConstantPropagator::propagateConstants))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE, ConstantComputationInliner::rewriteConstantCalculations))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE1) { Pruner(it).prune() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) { ConstantPropagatorAndSimplifier(it).rewrite() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_BOOL_VARIABLES) { BoolOptimizer(it).go() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) { ConstantPropagatorAndSimplifier(it).rewrite() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.NEGATION_NORMALIZER) { NegationNormalizer(it).rewrite() })
            .mapIfAllowed(
                CoreToCoreTransformer(ReportTypes.UNUSED_ASSIGNMENTS) {
                    val filtering = FilteringFunctions.default(it, keepRevertManagment = true)::isErasable
                    removeUnusedAssignments(it, expensive = false, filtering, isTypechecked = true)
                        .let(BlockMerger::mergeBlocks)
                }
            )
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.COLLAPSE_EMPTY_DSA, TACDSA::collapseEmptyAssignmentBlocks))
            .mapIfAllowed(
                CoreToCoreTransformer(ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS1) {
                    ConstantPropagator.propagateConstants(it, emptySet()).let {
                        BlockMerger.mergeBlocks(it)
                    }
                }
            )
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.REMOVE_UNUSED_WRITES, SimpleMemoryOptimizer::removeUnusedWrites))
            .mapIfAllowed(
                CoreToCoreTransformer(ReportTypes.OPTIMIZE) { c ->
                    optimizeAssignments(c,
                        FilteringFunctions.default(c, keepRevertManagment = true)
                    ).let(BlockMerger::mergeBlocks)
                }
            )
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE1) { Pruner(it).prune() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_INFEASIBLE_PATHS) { InfeasiblePaths.doInfeasibleBranchAnalysisAndPruning(it) })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.SIMPLE_SUMMARIES1) { it.simpleSummaries() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_OVERFLOW) { OverflowPatternRewriter(it).go() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_DIAMONDS) {
                // Cannot merge assumes prior to IntervalsRewriter
                DiamondSimplifier.simplifyDiamonds(it, iterative = true, allowAssumes = false)
            })
            .mapIfAllowed(
                CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
                    IntervalsRewriter.rewrite(it, handleLeinoVars = false)
                }
            )
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_DIAMONDS) {
                DiamondSimplifier.simplifyDiamonds(it, iterative = true)
            })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS2) {
                    // after pruning infeasible paths, there are more constants to propagate
                    ConstantPropagator.propagateConstants(it, emptySet())
                }
            )
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE2) { Pruner(it).prune() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_MERGE_BLOCKS, BlockMerger::mergeBlocks))
            .ref
            .also { NonlinearProfiler().profile(it, "optimized") }

        private fun <T : TACProgram<*>, R : TACProgram<*>> T.transform(
            reportType: ReportTypes,
            transform: (T) -> R
        ) = CodeTransformer<T, R>(reportType) { transform(it) }.applyTransformer(this)

        private fun deduplicateBlocks(c: MoveTACProgram): MoveTACProgram {
            val patch = c.toPatchingProgram()
            Deduplicator.deduplicateBlocks(c.graph, patch, MoveTACProgram.PatchingCommandRemapper)
            return patch.toCode(c)
        }

        /**
            Produces TAC code to pack a string value into a std::vector<u8>.
         */
        context(SummarizationContext)
        fun packString(bytes: ByteArray): TACSymbol.Var {
            // We create a single variable for each unique string
            val stringHash = applyKeccak(bytes).toString(16)
            val stringVar = TACSymbol.Var("mv.str.$stringHash", MoveType.Vector(MoveType.U8).toTag())

            data class Initializer(
                val stringVar: TACSymbol.Var,
                val bytes: ByteArray
            ) : SummarizationContext.Initializer() {
                override fun initialize(): MoveCmdsWithDecls {
                    val commands = buildList<MoveCmdsWithDecls> {
                        val values = bytes.map { byte ->
                            val byteVar = TACKeyword.TMP(MoveType.U8.toTag(), "byte")
                            add(assign(byteVar) { byte.toUByte().toBigInteger().asTACExpr })
                            byteVar
                        }
                        add(TACCmd.Move.VecPackCmd(stringVar, values).withDecls(stringVar))
                    }
                    return mergeMany(commands)
                }
            }
            ensureInit(Initializer(stringVar, bytes))

            return stringVar
        }

        context(SummarizationContext)
        fun packString(string: String) = packString(string.toByteArray(Charsets.UTF_8))

        private const val REACHED_END_OF_FUNCTION = "Reached end of function"
    }

    private var callIdAllocator = 0
    private fun newCallId() = callIdAllocator++

    private fun PersistentStack<MoveCall>.format() = joinToString(" ← ") { it.callee.toString() }

    private enum class SanityMode {
        NONE,
        ASSERT_TRUE,
        SATISFY_TRUE
    }

    private fun compileMoveTACProgram(
        name: String,
        entryFunc: MoveFunction,
        sanityMode: SanityMode,
        parametricTargets: Map<Int, MoveFunction>
    ): MoveTACProgram {
        with (SummarizationContext(scene, this, parametricTargets)) {
            val args = entryFunc.params.mapIndexed { i, it -> TACSymbol.Var("${entryFunc.toVarName()}_arg_$i", it.toTag()) }
            val returns = entryFunc.returns.mapIndexed { i, it -> TACSymbol.Var("${entryFunc.toVarName()}_ret_$i", it.toTag()) }

            val calleeEntry = newBlockId(0)
            val exit = newBlockId(0)

            val callCode = compileFunctionCall(
                MoveCall(
                    callee = entryFunc,
                    args = args,
                    returns = returns,
                    entryBlock = calleeEntry,
                    returnBlock = exit,
                    callStack = persistentStackOf(),
                    source = null
                )
            )

            val entryCode = treapMapOf(
                StartBlock to mergeMany(
                    // Initialization from SummarizationContext
                    getAndResetInitialization(),
                    // Havoc MEMORY (we don't use it, but it's referenced by Trap asserts)
                    assignHavoc(TACKeyword.MEMORY.toVar()),
                    // Initialize the entry function arguments
                    mergeMany(
                        entryFunc.params.zip(args).mapIndexed { i, (type, arg) ->
                            when (type) {
                                // The value of each incoming function argument is its index, which must also appear in
                                // `parametricTargets`
                                is MoveType.Function -> {
                                    check(i in parametricTargets) { "Missing function argument value" }
                                    assign(arg) { i.asTACExpr }
                                }
                                // All other arguments are non-deterministic
                                else -> type.assignHavoc(arg)
                            }
                        }
                    ),
                    // Jump to the entry function
                    TACCmd.Simple.JumpCmd(calleeEntry).withDecls()
                )
            )

            val exitCode = treapMapOf(
                exit to mergeMany(
                    listOfNotNull(
                        label("Exit from $entryFunc"),
                        when (sanityMode) {
                            SanityMode.NONE -> null
                            SanityMode.ASSERT_TRUE -> {
                                mergeMany(
                                    MoveCallTrace.annotateUserAssert(
                                        isSatisfy = false,
                                        condition = TACSymbol.True,
                                        messageText = REACHED_END_OF_FUNCTION,
                                    ),
                                    TACCmd.Simple.AssertCmd(
                                        TACSymbol.True,
                                        REACHED_END_OF_FUNCTION,
                                        MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT)
                                    ).withDecls()
                                )
                            }
                            SanityMode.SATISFY_TRUE -> {
                                mergeMany(
                                    MoveCallTrace.annotateUserAssert(
                                        isSatisfy = true,
                                        condition = TACSymbol.True,
                                        messageText = REACHED_END_OF_FUNCTION
                                    ),
                                    TACCmd.Simple.AssertCmd(
                                        TACSymbol.False,
                                        REACHED_END_OF_FUNCTION,
                                        MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) +
                                            (TACMeta.SATISFY_ID to allocSatisfyId())
                                    ).withDecls()
                                )
                            }
                        }
                    )
                )
            )

            val allCode = entryCode + callCode + exitCode

            return allCode.toProgram(name, StartBlock, exit)
                .transform(ReportTypes.INLINE_PARAMETRIC_CALLS) { InvokerCall.materialize(it) }
                .transform(ReportTypes.MATERIALIZE_GHOST_MAPPINGS) { GhostMapping.materialize(it) }
                .let {
                    it.mergeBlocks { a, b ->
                        // For now, only allow merging of blocks that are part of the same function body.  This
                        // maximizes our chances of deduplicating function bodies later.
                        a.bodyIdx == b.bodyIdx
                    }
                }
        }
    }

    context(SummarizationContext)
    private fun MoveTACProgram.transform(
        reportType: ReportTypes,
        transform: context(SummarizationContext)(MoveTACProgram) -> MoveTACProgram
    ): MoveTACProgram {
        check(getAndResetInitialization().cmds.isEmpty()) { "Unconsumed initialization" }

        val transformed = CodeTransformer<MoveTACProgram, MoveTACProgram>(reportType) {
            transform(this@SummarizationContext, it)
        }.applyTransformer(this)

        val initialization = getAndResetInitialization()
        return if (initialization.cmds.isEmpty() && initialization.varDecls.isEmpty()) {
            transformed
        } else {
            val patch = transformed.toPatchingProgram()
            patch.addBefore(CmdPointer(entryBlock, 0), initialization)
            return patch.toCode(transformed)
        }
    }

    private fun MoveBlocks.toProgram(name: String, start: NBId, exit: NBId): MoveTACProgram {
        val blockgraph = mapValuesTo(MutableBlockGraph()) { (block, code) ->
            when (val c = code.cmds.last()) {
                is TACCmd.Simple.JumpCmd -> treapSetOf(c.dst)
                is TACCmd.Simple.JumpiCmd -> treapSetOf(c.dst, c.elseDst)
                is TACCmd.Simple.RevertCmd -> treapSetOf<NBId>()
                else -> {
                    check(block == exit) { "No jump at end of block $block: got $c" }
                    treapSetOf<NBId>()
                }
            }
        }
        // Only keep reachable code
        val reachable = getReachable(listOf(start)) { blockgraph[it] }
        return MoveTACProgram(
            name = name,
            code = this.updateValues { block, code -> code.cmds.takeIf { block in reachable } },
            blockgraph = blockgraph.filterKeysTo(LinkedArrayHashMap()) { block -> block in reachable },
            entryBlock = start,
            symbolTable = TACSymbolTable(values.flatMapToSet { it.varDecls })
        )
    }

    /**
        Compiles a function as a subprogram, suitable for patching into another program via [PatchingTACProgram].
        Arguments are left uninitialized in the subprogram (and should be initialized in the outer program).
     */
    context(SummarizationContext)
    fun compileSubprogram(
        entryFunc: MoveFunction,
        args: List<TACSymbol.Var>,
        returns: List<TACSymbol.Var>
    ): MoveTACProgram {
        val entryBlock = newBlockId(0)
        val exitBlock = newBlockId(0)
        val code = compileFunctionCall(
            MoveCall(
                callee = entryFunc,
                args = args,
                returns = returns,
                entryBlock = entryBlock,
                returnBlock = exitBlock,
                callStack = persistentStackOf(),
                source = null
            )
        ) + (exitBlock to TACCmd.Simple.NopCmd.withDecls())

        return code.toProgram(entryFunc.name.toString(), entryBlock, exitBlock)
    }

    private fun compileRuleCoreTACProgram(
        rule: CvlmRule,
        entryFunc: MoveFunction,
        sanityMode: SanityMode,
        parametricTargets: Map<Int, MoveFunction>
    ): CompiledRule {
        val moveTAC = compileMoveTACProgram(rule.ruleInstanceName, entryFunc, sanityMode, parametricTargets)
        ArtifactManagerFactory().dumpCodeArtifacts(moveTAC, ReportTypes.JIMPLE, DumpTime.POST_TRANSFORM)
        val isSatisfy = when (sanityMode) {
            SanityMode.NONE -> isSatisfyRule(moveTAC)
            // For sanity rules, it's common for the injected assert/satisfy to be unreachable, if the target
            // function always aborts.  In that case `isSatisfyRule` would throw, failing the whole run.  These are
            // not user-generated rules, and there is no way for the user to fix them, so let's not fail the whole
            // run for those.
            SanityMode.ASSERT_TRUE -> false
            SanityMode.SATISFY_TRUE -> true
        }
        return CompiledRule(rule, moveTAC, isSatisfy)
    }

    private fun compileRule(rule: CvlmRule): CompiledRule {
        return when (rule) {
            is CvlmRule.UserRule -> {
                compileRuleCoreTACProgram(
                    rule,
                    rule.entry,
                    SanityMode.NONE,
                    rule.parametricTargets
                )
            }
            is CvlmRule.TargetSanity.AssertTrue -> {
                compileRuleCoreTACProgram(
                    rule,
                    rule.target,
                    SanityMode.ASSERT_TRUE,
                    parametricTargets = mapOf()
                ).also {
                    check(it.isSatisfy == false) { "AssertTrue rule compiled to a satisfy rule" }
                }
            }
            is CvlmRule.TargetSanity.SatisfyTrue -> {
                compileRuleCoreTACProgram(
                    rule,
                    rule.target,
                    SanityMode.SATISFY_TRUE,
                    parametricTargets = mapOf()
                ).also {
                    check(it.isSatisfy == true) { "SatisfyTrue rule compiled to an assert rule" }
                }
            }
        }
    }

    private fun isSatisfyRule(code: MoveTACProgram): Boolean {
        val areSatisfyAsserts = code.graph.commands.parallelStream().mapNotNull { (_, cmd) ->
            (cmd as? TACCmd.Simple.AssertCmd)
                ?.takeIf { TACMeta.CVL_USER_DEFINED_ASSERT in it.meta }
                ?.meta?.containsKey(TACMeta.SATISFY_ID)
        }.toList()
        if (areSatisfyAsserts.isEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Rule ${code.name} contains no assertions after compilation. Assertions may have been trivially unreachable and removed by the compiler."
            )
        }
        return areSatisfyAsserts.uniqueOrNull()
            ?:throw CertoraException(
                CertoraErrorType.CVL,
                "Rule ${code.name} mixes assert and satisfy commands."
            )
    }

    context(SummarizationContext)
    fun compileFunctionCall(call: MoveCall): MoveBlocks {
        // First, should we summarize this function?
        scene.summarize(call)?.let {
            return it
        }

        // Do we have bytecode for this function?
        try {
            call.callee.code?.let {
                return compileCodeUnitCall(call, it)
            }
        } catch (e: TypeShadowedException) {
            return havocCall(call, "Illegal access to shadowed type ${e.type.name}")
        }

        // Otherwise, just havoc the call
        return havocCall(call, "Unrecognized native function")
    }

    private data class Local(val type: MoveType, val s: TACSymbol.Var)

    private val exitBodyBlockId: NBId = BlockIdentifier(Int.MAX_VALUE, 0, 0, 0, 0, 0)

    private data class CompiledCodeUnit(
        val entry: NBId,
        val locals: List<Local>,
        val results: List<Local>,
        val tacBlocks: MoveBlocks
    )

    private val compiledBodies = mutableMapOf<MoveFunction, CompiledCodeUnit>()

    /**
        Compiles a call to a function with a code unit (as opposed to a native function).
     */
    context(SummarizationContext)
    private fun compileCodeUnitCall(
        call: MoveCall,
        code: MoveFunction.Code
    ): MoveBlocks {
        val func = call.callee
        if (func in call.funcsOnStack) {
            return singleBlockSummary(call) {
                loggerSetupHelpers.warn { "Recursive function call to ${call.callee} from ${call.callStack.format()}" }
                assert("Recursive function call to ${call.callee}") { false.asTACExpr }
            }
        }

        // Get the compiled body of the callee
        val (compiledBodyEntry, locals, results, compiledBody) = compiledBodies.getOrPut(func) {
            logger.trace { "Compiling body of function $func" }
            withNewBodyIdx {
                compileCodeUnitBody(func, code, call.callStack.push(call))
            }
        }

        // Remap the block IDs to instantiate the body for this call.  Note: any specialization of the body code will
        // prevent deduplication of the body later!  We only want to change the block IDs, which the deduplicator can
        // handle.
        val bodyExit = newBlockId(call.entryBlock)
        val blockMap = mutableMapOf(exitBodyBlockId to bodyExit)
        fun mapBlock(block: NBId) = blockMap.getOrPut(block) { newBlockId(block) }
        val bodyBuilder = compiledBody.entries.map { (bodyBlock, cmds) ->
            mapBlock(bodyBlock) to cmds.cmds.map {
                it.letIf(MoveTACProgram.PatchingCommandRemapper.isJumpCommand(it)) {
                    MoveTACProgram.PatchingCommandRemapper.remapSuccessors(it, ::mapBlock)
                }
            }.withDecls(cmds.varDecls)
        }.toTreapMap().builder()
        val bodyEntry = blockMap[compiledBodyEntry]!!

        // Prolog: annotate the start of the call, and move the arguments into locals
        check(call.args.size == func.params.size) {
            "Argument count mismatch: ${call.args.size} != ${func.params.size}"
        }
        val callId = newCallId()
        bodyBuilder[call.entryBlock] = mergeMany(
            MoveCallTrace.annotateFuncStart(callId, func, call.args),
            mergeMany(
                (0..<call.args.size).map { i ->
                    assign(locals[i].s) { call.args[i].asSym() }
                }
            ),
            TACCmd.Simple.JumpCmd(bodyEntry).withDecls()
        )

        // Epilog: move the return values into the caller's variables, and annotate the end of the call
        check(call.returns.size == func.returns.size) {
            "Call return value count mismatch: ${call.returns.size} != ${func.returns.size}"
        }
        bodyBuilder[bodyExit] = mergeMany(
            mergeMany(
                (call.returns zip results).map { (ret, res) ->
                    assign(ret) { res.s.asSym() }
                }
            ),
            MoveCallTrace.annotateFuncEnd(callId, func, call.returns),
            TACCmd.Simple.JumpCmd(call.returnBlock).withDecls()
        )

        return bodyBuilder.build()
    }

    /**
        Compiles the body of a function.  This should not depend on the details of the particular call (e.g., the
        argument and return variables), to maximize the possiblity that we can merge duplicate function bodies later.

        Returns the list of stack variables that hold the return values
     */
    context(SummarizationContext)
    private fun compileCodeUnitBody(
        func: MoveFunction,
        code: MoveFunction.Code,
        callStack: PersistentStack<MoveCall>,
    ): CompiledCodeUnit = annotateCallStack("func.${func.name}") {
        val varNameBase = func.toVarName()

        val locals = (func.params + code.locals).mapIndexed { i, type ->
            Local(type, TACSymbol.Var("$varNameBase!local!$i", type.toTag()))
        }

        val results = (0..<func.returns.size).map { i ->
            Local(func.returns[i], TACSymbol.Var("$varNameBase!ret!$i", func.returns[i].toTag()))
        }

        val entryBlock = newBlockId(0)
        val blockIds = mutableMapOf(0 to entryBlock)
        fun blockId(blockOffset: Int) = blockIds.getOrPut(blockOffset) { newBlockId(blockOffset) }

        val stacksIn = mutableMapOf(0 to persistentStackOf<MoveType>())
        val nextBlocks = arrayDequeOf(0)
        val tacBlocks = treapMapBuilderOf<NBId, MoveCmdsWithDecls>()

        nextBlocks.consume { blockOffset ->
            if (tacBlocks.containsKey(blockId(blockOffset))) {
                return@consume
            }

            val stack = stacksIn[blockOffset]!!.builder()

            // Where to go if we exit this block without branching
            var fallthrough: Int? = null

            fun successor(offset: Int): NBId {
                val stackIn = stack.build()
                stacksIn.put(offset, stackIn)?.let {
                    check(it == stackIn) {
                        "Stack mismatch for block $offset: $it != $stackIn"
                    }
                }
                nextBlocks += offset
                fallthrough = null
                return blockId(offset)
            }


            val cmds = mutableListOf<MoveCmdsWithDecls>()

            code.block(blockOffset).forEach { (currentOffset, inst, src) ->
                fallthrough = currentOffset + 1

                logger.trace { "$currentOffset/${stack.size}: $inst" }

                val metaInfo = scene.sourceContext.tacMeta(src)
                val meta = metaInfo.toMap()

                fun topVar() = stack.top.let { type ->
                    TACSymbol.Var("$varNameBase!stack!${stack.size - 1}!${type.symNameExt()}", type.toTag()).asSym()
                }
                fun topType() = stack.top
                fun pop() = topVar().also { stack.pop() }
                fun push(type: MoveType): TACSymbol.Var {
                    stack.push(type)
                    return topVar().s
                }
                fun push(type: MoveType, value: TACExpr) = assign(push(type), meta) { value }
                fun push(type: MoveType.Bits, value: BigInteger) = push(type, value.asTACExpr)
                fun push(type: MoveType, exp: TACExprFactoryExtensions.() -> TACExpr) = push(type, TXF(exp))
                fun pushHavoc(type: MoveType) = type.assignHavoc(push(type))

                fun mathOp(op: (MoveType.Bits, TACExpr, TACExpr) -> MoveCmdsWithDecls): MoveCmdsWithDecls {
                    val type = topType()
                    check(type is MoveType.Bits) { "Expected bits type, got $type" }
                    val rhs = pop();
                    val lhs = pop()
                    return op(type, lhs, rhs)
                }

                fun conditionalJump(cond: TACExpr.Sym, trueOffset: Int, falseOffset: Int): MoveCmdsWithDecls {
                    val trueBranch = successor(trueOffset)
                    val falseBranch = successor(falseOffset)
                    return if (trueBranch != falseBranch) {
                        TACCmd.Simple.JumpiCmd(
                            cond = cond.s,
                            dst = trueBranch,
                            elseDst = falseBranch,
                            meta = meta
                        ).withDecls()
                    } else {
                        TACCmd.Simple.JumpCmd(trueBranch, meta).withDecls()
                    }
                }

                cmds += when (inst) {
                    is MoveInstruction.BrTrue ->
                        conditionalJump(
                            cond = pop(),
                            trueOffset = inst.branchTarget,
                            falseOffset = currentOffset + 1
                        )

                    is MoveInstruction.BrFalse ->
                        conditionalJump(
                            cond = pop(),
                            falseOffset = inst.branchTarget,
                            trueOffset = currentOffset + 1
                        )

                    is MoveInstruction.Branch ->
                        TACCmd.Simple.JumpCmd(successor(inst.branchTarget), meta).withDecls()

                    is MoveInstruction.Call -> {
                        val callee = inst.callee
                        val calleeEntry = newBlockId(currentOffset)
                        tacBlocks += compileFunctionCall(
                            MoveCall(
                                callee = callee,
                                args = callee.params.map { pop().s }.reversed(),
                                returns = callee.returns.map { push(it) },
                                entryBlock = calleeEntry,
                                returnBlock = successor(currentOffset + 1),
                                callStack = callStack,
                                source = metaInfo?.getSourceDetails()
                            )
                        )
                        TACCmd.Simple.JumpCmd(calleeEntry, meta).withDecls()
                    }

                    is MoveInstruction.Ret ->
                        mergeMany(
                            mergeMany(results.reversed().map { assign(it.s) { pop() } }),
                            TACCmd.Simple.JumpCmd(exitBodyBlockId, meta).withDecls()
                        ).also { fallthrough = null }


                    is MoveInstruction.Nop -> TACCmd.Simple.NopCmd.withDecls()
                    is MoveInstruction.Pop -> TACCmd.Simple.NopCmd.withDecls().also { pop() }

                    is MoveInstruction.LdU8 -> push(MoveType.U8, inst.value.toBigInteger())
                    is MoveInstruction.LdU16 -> push(MoveType.U16, inst.value.toBigInteger())
                    is MoveInstruction.LdU32 -> push(MoveType.U32, inst.value.toBigInteger())
                    is MoveInstruction.LdU64 -> push(MoveType.U64, inst.value.toBigInteger())
                    is MoveInstruction.LdU128 -> push(MoveType.U128, inst.value)
                    is MoveInstruction.LdU256 -> push(MoveType.U256, inst.value)
                    is MoveInstruction.LdTrue -> push(MoveType.Bool, true.asTACExpr)
                    is MoveInstruction.LdFalse -> push(MoveType.Bool, false.asTACExpr)

                    is MoveInstruction.LdConst -> {
                        /* Decode the BCS-encoded constant data. See See https://github.com/diem/bcs/#readme */
                        buildList<MoveCmdsWithDecls> {
                            val buf = ByteBuffer.wrap(inst.data.toByteArray())
                            fun decode(type: MoveType.Value) {
                                when (type) {
                                    is MoveType.Bool -> add(push(MoveType.Bool, buf.getBool().asTACExpr))
                                    is MoveType.U8 -> add(push(MoveType.U8, buf.getU8().toBigInteger()))
                                    is MoveType.U16 -> add(push(MoveType.U16, buf.getU16().toBigInteger()))
                                    is MoveType.U32 -> add(push(MoveType.U32, buf.getU32().toBigInteger()))
                                    is MoveType.U64 -> add(push(MoveType.U64, buf.getU64().toBigInteger()))
                                    is MoveType.U128 -> add(push(MoveType.U128, buf.getU128()))
                                    is MoveType.U256 -> add(push(MoveType.U256, buf.getU256()))
                                    is MoveType.Address -> add(push(MoveType.Address, buf.getAddress()))
                                    is MoveType.Signer -> add(push(MoveType.Signer, buf.getAddress()))
                                    is MoveType.Struct -> {
                                        val fields = type.fields ?: error("Cannot initialize native struct $type")
                                        fields.forEach { decode(it.type) }
                                        val values = fields.reversed().map { pop().s }.reversed()
                                        add(TACCmd.Move.PackStructCmd(push(type), values, meta).withDecls())
                                    }
                                    is MoveType.Vector -> {
                                        if (type.elemType == MoveType.U8) {
                                            // vector<u8> is most likely a string constant.  Let's keep the string value
                                            // for later use (for things like assert messages).
                                            val length = buf.getLeb128UInt().toInt()
                                            val bytes = ByteArray(length).also { buf.get(it) }
                                            val stringVar = packString(bytes)
                                            add(push(type, stringVar.asSym()))
                                        } else {
                                        val length = buf.parseList { decode(type.elemType) }.size
                                        val values = (0..<length).map { pop().s }.reversed()
                                        add(TACCmd.Move.VecPackCmd(push(type), values, meta).withDecls())
                                    }
                                    }

                                    // The Move compiler doesn't seem to emit LdConst for enums
                                    is MoveType.Enum -> error("Constant enum values are not supported")

                                    // Our synthetic types should not appear in LdConst
                                    is MoveType.GhostArray, is MoveType.MathInt,
                                    is MoveType.Nondet, is MoveType.Function -> `impossible!`
                                }
                            }
                            decode(inst.type)
                            check(!buf.hasRemaining()) {
                                "BCS decoding for ${inst.type} did not consume all bytes: ${buf.remaining()} remaining"
                            }
                        }.let { mergeMany(it) }
                    }

                    is MoveInstruction.Cast -> {
                        val toType = inst.toType
                        val fromType = topType()
                        check(fromType is MoveType.Bits) { "Expected bits type, got $fromType" }
                        val fromValue = pop()
                        if (toType.size >= fromType.size) {
                            // No overflow possible
                            push(toType, fromValue)
                        } else {
                            // Check for overflow
                            mergeMany(
                                Trap.assert("u${toType.size} overflow", meta) { fromValue.s le MASK_SIZE(toType.size).asTACExpr },
                                push(toType, fromValue)
                            )
                        }
                    }

                    is MoveInstruction.CopyLoc ->
                        locals[inst.index].let { push(it.type, it.s.asSym()) }
                    is MoveInstruction.MoveLoc ->
                        locals[inst.index].let { push(it.type, it.s.asSym()) }
                    is MoveInstruction.StLoc ->
                        assign(locals[inst.index].s, meta) { pop() }

                    is MoveInstruction.Add -> mathOp { t, a, b ->
                        mergeMany(
                            Trap.assert("u${t.size} overflow in addition", meta) { (MASK_SIZE(t.size).asTACExpr sub a) ge b },
                            push(t) { a add b }
                        )
                    }
                    is MoveInstruction.Sub -> mathOp { t, a, b ->
                        mergeMany(
                            Trap.assert("u${t.size} overflow in subtraction", meta) { a ge b },
                            push(t) { a sub b }
                        )
                    }
                    is MoveInstruction.Mul -> mathOp { t, a, b ->
                        mergeMany(
                            Trap.assert("u${t.size} overflow in multiplication", meta) { (a intMul b) le MASK_SIZE(t.size).asTACExpr },
                            push(t) { a mul b }
                        )
                    }
                    is MoveInstruction.Div -> mathOp { t, a, b ->
                        mergeMany(
                            Trap.assert("Division by zero", meta) { b neq 0.asTACExpr },
                            push(t) { a div b },
                        )
                    }
                    is MoveInstruction.Mod -> mathOp { t, a, b ->
                        mergeMany(
                            Trap.assert("Division by zero", meta) { b neq 0.asTACExpr },
                            push(t) { a mod b },
                        )
                    }

                    is MoveInstruction.BitOr -> mathOp { t, a, b -> push(t) { a bwOr b } }
                    is MoveInstruction.BitAnd -> mathOp { t, a, b -> push(t) { a bwAnd b } }
                    is MoveInstruction.Xor -> mathOp { t, a, b -> push(t) { a bwXor b } }

                    is MoveInstruction.Shl, is MoveInstruction.Shr -> {
                        val shiftType = topType()
                        val shift = pop()
                        val valueType = topType()
                        val value = pop()

                        check(shiftType is MoveType.Bits) { "Expected bits type, got $shiftType" }
                        check(valueType is MoveType.Bits) { "Expected bits type, got $valueType" }

                        mergeMany(
                            listOfNotNull(
                                // The Move VM doesn't check shift overflow for U256
                                runIf(valueType != MoveType.U256) {
                                    Trap.assert("u${valueType.size} shift overflow", meta) { shift lt valueType.size.asTACExpr }
                                },
                                when (inst) {
                                    is MoveInstruction.Shl -> push(valueType) { value shiftL shift }
                                    is MoveInstruction.Shr -> push(valueType) { value shiftRLog shift }
                                    else -> `impossible!`
                                }
                            )
                        )
                    }

                    is MoveInstruction.Not -> push(MoveType.Bool) { not(pop()) }
                    is MoveInstruction.Or -> push(MoveType.Bool) { pop() or pop() }
                    is MoveInstruction.And -> push(MoveType.Bool) { pop() and pop() }

                    is MoveInstruction.Eq, is MoveInstruction.Neq -> {
                        val a: TACSymbol.Var
                        val b: TACSymbol.Var
                        mergeMany(
                            when (val type = topType()) {
                                is MoveType.Value -> {
                                    a = pop().s
                                    b = pop().s
                                    listOf<TACCmd>().withDecls()
                                }
                                is MoveType.Reference -> {
                                    val aRef = pop().s
                                    val bRef = pop().s
                                    a = TACKeyword.TMP(type.refType.toTag(), "a")
                                    b = TACKeyword.TMP(type.refType.toTag(), "b")
                                    listOf(
                                        TACCmd.Move.ReadRefCmd(dst = a, ref = aRef, meta = meta),
                                        TACCmd.Move.ReadRefCmd(dst = b, ref = bRef, meta = meta)
                                    ).withDecls(a, b)
                                }
                            },
                            when (inst) {
                                is MoveInstruction.Eq -> {
                                    val dst = push(MoveType.Bool)
                                    TACCmd.Move.EqCmd(
                                        dst = dst,
                                        a = a,
                                        b = b,
                                        meta = meta
                                    ).withDecls(dst)
                                }
                                is MoveInstruction.Neq -> {
                                    val tmp = TACKeyword.TMP(Tag.Bool)
                                    mergeMany(
                                        TACCmd.Move.EqCmd(
                                            dst = tmp,
                                            a = a,
                                            b = b,
                                            meta = meta
                                        ).withDecls(tmp),
                                        push(MoveType.Bool) { not(tmp.asSym()) }
                                    )
                                }
                                else -> `impossible!`
                            }
                        )
                    }

                    is MoveInstruction.Lt -> mathOp { _, a, b -> push(MoveType.Bool) { a lt b } }
                    is MoveInstruction.Gt -> mathOp { _, a, b -> push(MoveType.Bool) { a gt b } }
                    is MoveInstruction.Le -> mathOp { _, a, b -> push(MoveType.Bool) { a le b } }
                    is MoveInstruction.Ge -> mathOp { _, a, b -> push(MoveType.Bool) { a ge b } }

                    is MoveInstruction.Abort -> {
                        pop() // we ignore the error code for now
                        fallthrough = null
                        Trap.trap("Abort", meta)
                    }

                    is MoveInstruction.Pack -> {
                        val fields = inst.type.fields ?: error("Cannot pack native struct ${inst.type}")
                        val values = fields.reversed().map {
                            check(it.type == topType()) { "Expected ${it.type}, got ${topType()}" }
                            pop().s
                        }.reversed()

                        TACCmd.Move.PackStructCmd(
                            dst = push(inst.type),
                            srcs = values,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.Unpack -> {
                        val struct = topType() as? MoveType.Struct
                            ?: error("Expected struct type, got ${topType()}")

                        val value = pop()

                        val fields = struct.fields ?: error("Cannot unpack native struct $struct")

                        TACCmd.Move.UnpackStructCmd(
                            dsts = fields.map { push(it.type) },
                            src = value.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.BorrowLoc -> {
                        val loc = locals[inst.index]
                        TACCmd.Move.BorrowLocCmd(
                            ref = push(MoveType.Reference(loc.type as MoveType.Value)),
                            loc = loc.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.BorrowField -> {
                        val refType = topType() as? MoveType.Reference
                            ?: error("Expected reference type, got ${topType()}")
                        val struct = refType.refType as? MoveType.Struct
                            ?: error("Expected struct reference type, got $refType")
                        val fields = struct.fields ?: error("Cannot borrow field of native struct $struct")
                        val field = fields.getOrNull(inst.index)
                            ?: error("Field index ${inst.index} out of bounds for struct $struct")

                        val srcRef = pop()

                        TACCmd.Move.BorrowFieldCmd(
                            dstRef = push(MoveType.Reference(field.type)),
                            srcRef = srcRef.s,
                            fieldIndex = inst.index,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.ReadRef -> {
                        val refType = topType()
                        check(refType is MoveType.Reference) { "Expected reference type, got $refType" }
                        val ref = pop()
                        TACCmd.Move.ReadRefCmd(
                            dst = push(refType.refType),
                            ref = ref.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.WriteRef -> {
                        val refType = topType()
                        check(refType is MoveType.Reference) { "Expected reference type, got $refType" }
                        val ref = pop()
                        val value = pop()
                        TACCmd.Move.WriteRefCmd(
                            ref = ref.s,
                            src = value.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.FreezeRef ->
                        TACCmd.Simple.NopCmd.withDecls()

                    is MoveInstruction.VecPack -> {
                        val values = (0UL until inst.count).map {
                            check(inst.elemType == topType()) { "Expected ${inst.elemType}, got ${topType()}" }
                            pop().s
                        }.reversed()

                        TACCmd.Move.VecPackCmd(
                            dst = push(MoveType.Vector(inst.elemType)),
                            srcs = values,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.VecUnpack -> {
                        val vecType = topType() as? MoveType.Vector
                            ?: error("Expected vector type, got ${topType()}")
                        val vec = pop()
                        TACCmd.Move.VecUnpackCmd(
                            dsts = (0UL until inst.count).map { push(vecType.elemType) },
                            src = vec.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.VecLen -> {
                        val vecRef = pop()
                        TACCmd.Move.VecLenCmd(
                            dst = push(MoveType.U64),
                            ref = vecRef.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.VecBorrow -> {
                        check(topType() is MoveType.U64) { "Expected u64, got ${topType()}" }
                        val index = pop()

                        val refType = topType() as? MoveType.Reference
                            ?: error("Expected reference type, got ${topType()}")
                        val vecType = refType.refType as? MoveType.Vector
                            ?: error("Expected vector reference type, got $refType")
                        val srcRef = pop()

                        TACCmd.Move.VecBorrowCmd(
                            dstRef = push(MoveType.Reference(vecType.elemType)),
                            srcRef = srcRef.s,
                            index = index.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.VecPushBack -> {
                        val value = pop()
                        val vecRef = pop()
                        TACCmd.Move.VecPushBackCmd(
                            ref = vecRef.s,
                            src = value.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.VecPopBack -> {
                        val refType = topType() as? MoveType.Reference
                            ?: error("Expected reference type, got ${topType()}")
                        val vecType = refType.refType as? MoveType.Vector
                            ?: error("Expected vector reference type, got $refType")
                        val vecRef = pop()
                        TACCmd.Move.VecPopBackCmd(
                            dst = push(vecType.elemType),
                            ref = vecRef.s,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.VecSwap -> {
                        check(topType() is MoveType.U64) { "Expected u64, got ${topType()}" }
                        val index2 = pop()

                        check(topType() is MoveType.U64) { "Expected u64, got ${topType()}" }
                        val index1 = pop()

                        val refType = topType() as? MoveType.Reference
                            ?: error("Expected reference type, got ${topType()}")
                        val vecType = refType.refType as? MoveType.Vector
                            ?: error("Expected vector reference type, got $refType")
                        val vecRef = pop()

                        val tmpRef1 = TACKeyword.TMP(MoveType.Reference(vecType.elemType).toTag())
                        val tmpRef2 = TACKeyword.TMP(MoveType.Reference(vecType.elemType).toTag())
                        val tmpVal1 = TACKeyword.TMP(vecType.elemType.toTag())
                        val tmpVal2 = TACKeyword.TMP(vecType.elemType.toTag())

                        listOf(
                            TACCmd.Move.VecBorrowCmd(
                                dstRef = tmpRef1,
                                srcRef = vecRef.s,
                                index = index1.s,
                                meta = meta
                            ),
                            TACCmd.Move.VecBorrowCmd(
                                dstRef = tmpRef2,
                                srcRef = vecRef.s,
                                index = index2.s,
                                meta = meta
                            ),
                            TACCmd.Move.ReadRefCmd(
                                dst = tmpVal1,
                                ref = tmpRef1,
                                meta = meta
                            ),
                            TACCmd.Move.ReadRefCmd(
                                dst = tmpVal2,
                                ref = tmpRef2,
                                meta = meta
                            ),
                            TACCmd.Move.WriteRefCmd(
                                ref = tmpRef1,
                                src = tmpVal2,
                                meta = meta
                            ),
                            TACCmd.Move.WriteRefCmd(
                                ref = tmpRef2,
                                src = tmpVal1,
                                meta = meta
                            )
                        ).withDecls(tmpRef1, tmpRef2, tmpVal1, tmpVal2)
                    }

                    is MoveInstruction.PackVariant -> {
                        val enumType = inst.type
                        val variant = enumType.variants[inst.variant]
                        val fields = variant.fields ?: error("Cannot pack native variant ${enumType.name}::${variant.name}")

                        TACCmd.Move.PackVariantCmd(
                            srcs = fields.reversed().map {
                                check(it.type == topType()) { "Expected ${it.type}, got ${topType()}" }
                                pop().s
                            }.reversed(),
                            dst = push(inst.type),
                            variant = inst.variant,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.UnpackVariant -> {
                        val enumType = topType() as? MoveType.Enum
                            ?: error("Expected enum type, got ${topType()}")
                        check(enumType == inst.type) { "Expected enum type $enumType, got ${inst.type}" }
                        val variant = enumType.variants[inst.variant]
                        val fields = variant.fields ?: error("Cannot unpack native variant ${enumType.name}::${variant.name}")

                        TACCmd.Move.UnpackVariantCmd(
                            src = pop().s,
                            dsts = fields.map { push(it.type) },
                            variant = inst.variant,
                            meta = meta
                        ).withDecls()
                    }

                    is MoveInstruction.UnpackVariantRef -> {
                        val variantRef = topType() as? MoveType.Reference
                            ?: error("Expected reference type, got ${topType()}")
                        val enumType = variantRef.refType as? MoveType.Enum
                            ?: error("Expected enum type, got $variantRef")
                        check(enumType == inst.type) { "Expected enum type $enumType, got ${inst.type}" }
                        val variant = enumType.variants[inst.variant]
                        val fields = variant.fields ?: error("Cannot unpack native variant ${enumType.name}::${variant.name}")

                        mergeMany(
                            TACCmd.Move.UnpackVariantRefCmd(
                                srcRef = pop().s,
                                dsts = fields.map { push(MoveType.Reference(it.type)) },
                                variant = inst.variant,
                                meta = meta
                            ).withDecls()
                        )
                    }

                    is MoveInstruction.VariantSwitch -> {
                        val variantRef = topType() as? MoveType.Reference
                            ?: error("Expected reference type, got ${topType()}")
                        val enumType = variantRef.refType as? MoveType.Enum
                            ?: error("Expected enum type, got $variantRef")
                        check(inst.branches.size == enumType.variants.size) {
                            "Expected ${enumType.variants.size} branches, got ${inst.branches.size}"
                        }

                        /*
                            Generate a switch over variant indexes 0..n, to target blocks T0..Tn

                            idx := variantIndex(*topOfStack)
                            jmp B0

                            B0: c0 := idx == 0
                                jmpi c0, T0, B1

                            B1: c1 := idx == 1
                                jmpi c1, T1, B2

                            ...
                            Bn: cn := idx == n
                                jmpi cn, Tn, FAIL

                            FAIL: assert(false) (should be unreachable)
                         */
                        val idx = TACKeyword.TMP(Tag.Bit256, "variant_idx")
                        val failBlock = newBlockId(currentOffset).also {
                            tacBlocks += it to mergeMany(
                                assert("Invalid variant tag", meta) { false.asTACExpr },
                                Trap.trapRevert("Invalid variant tag", meta)
                            )
                        }
                        val firstSwitchBlock = inst.branches.withIndex().reversed().fold(failBlock) { nextBlockId, (i, offset) ->
                            newBlockId(currentOffset).also {
                                val cond = TACKeyword.TMP(Tag.Bool, "variant_switch_cond")
                                tacBlocks += it to mergeMany(
                                    assign(cond, meta) { idx.asSym() eq i.asTACExpr },
                                    TACCmd.Simple.JumpiCmd(
                                        cond = cond,
                                        dst = successor(offset),
                                        elseDst = nextBlockId,
                                        meta = meta
                                    ).withDecls()
                                )
                            }
                        }
                        check(fallthrough == null) { "Creating successors should have cleared the fallthrough" }

                        mergeMany(
                            TACCmd.Move.VariantIndexCmd(
                                ref = pop().s,
                                index = idx,
                                meta = meta
                            ).withDecls(idx),
                            TACCmd.Simple.JumpCmd(
                                dst = firstSwitchBlock,
                                meta = meta
                            ).withDecls()
                        )
                    }
                }
            }

            fallthrough?.let {
                cmds += TACCmd.Simple.JumpCmd(successor(it)).withDecls()
            }

            tacBlocks[blockId(blockOffset)] = mergeMany(cmds)
        }

        CompiledCodeUnit(entryBlock, locals, results, tacBlocks.build())
    }

    context(SummarizationContext)
    private fun havocCall(call: MoveCall, reason: String) = singleBlockSummary(call) {
        val func = call.callee
        loggerSetupHelpers.warn { "Havocing call to $func from ${call.callStack.format()}: $reason" }
        check(call.returns.size == func.returns.size) {
            "Return count mismatch: ${call.returns.size} != ${func.returns.size}"
        }
        mergeMany(
            label("Havoc'd call to $func from ${call.callStack.format()}: $reason"),
            mergeMany(
                func.returns.zip(call.returns).map { (type, ret) ->
                    type.assignHavoc(ret)
                }
            )
        )
    }
}
