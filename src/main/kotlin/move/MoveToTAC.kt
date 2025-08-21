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
import config.*
import datastructures.*
import datastructures.stdcollections.*
import diagnostics.*
import evm.MASK_SIZE
import instrumentation.transformers.*
import java.math.BigInteger
import java.nio.ByteBuffer
import log.*
import move.CvlmManifest.RuleType
import optimizer.*
import org.jetbrains.annotations.TestOnly
import spec.cvlast.RuleIdentifier
import spec.cvlast.SpecType
import spec.genericrulegenerators.*
import spec.rules.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import verifier.*

private val logger = Logger(LoggerTypes.MOVE)
private val loggerSetupHelpers = Logger(LoggerTypes.SETUP_HELPERS)

/**
    Conversion from a Move bytecode to TAC
 */
class MoveToTAC private constructor (val scene: MoveScene) {
    companion object {
        fun compileRule(
            entryFunc: MoveFunction,
            ruleType: RuleType,
            scene: MoveScene
        ) = MoveToTAC(scene).compileRule(entryFunc, ruleType)

        @TestOnly
        fun compileMoveTAC(
            entryFunc: MoveFunctionName,
            scene: MoveScene,
        ) = scene.maybeDefinition(entryFunc)?.let {
            with(scene) {
                val fn = MoveFunction(it.function)
                MoveToTAC(scene).compileMoveTACProgram(fn, SanityMode.NONE)
            }
        }

        val CONST_STRING = MetaKey<String>("move.const.string")

        private const val REACHED_END_OF_FUNCTION = "Reached end of function"
    }

    private val uniqueNameAllocator = mutableMapOf<String, Int>()
    private fun String.toUnique() =
        uniqueNameAllocator.compute(this) { _, count -> count?.let { it + 1 } ?: 0 }.let { "$this!$it" }

    private var blockIdAllocator = 0
    private fun newBlockId() =
        BlockIdentifier(
            ++blockIdAllocator,
            stkTop = 1, // avoids conflicts with Allocator.getNBId()
            0, 0, 0, 0
        )

    private fun PersistentStack<MoveCall>.format() = joinToString(" ← ") { it.callee.toString() }

    private val MoveFunction.varNameBase get() = name.toVarName()

    private enum class SanityMode {
        NONE,
        ASSERT_TRUE,
        SATISFY_TRUE
    }

    private fun compileMoveTACProgram(entryFunc: MoveFunction, sanityMode: SanityMode): MoveTACProgram {
        val args = entryFunc.params.mapIndexed { i, it -> TACSymbol.Var("${entryFunc.varNameBase}_arg_$i", it.toTag()) }
        val returns = entryFunc.returns.mapIndexed { i, it -> TACSymbol.Var("${entryFunc.varNameBase}_ret_$i", it.toTag()) }

        val calleeEntry = newBlockId()
        val exit = newBlockId()

        val summarizationContext = SummarizationContext(scene, this)

        val callCode = with(summarizationContext) {
            compileFunctionCall(
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
        }

        val entryCode = treapMapOf(
            StartBlock to mergeMany(
                // Havoc MEMORY (we don't use it, but it's referenced by Trap asserts)
                assignHavoc(TACKeyword.MEMORY.toVar()),
                // Initialization for summaries
                summarizationContext.allInitialization,
                // Havoc the entry function arguments
                mergeMany(entryFunc.params.zip(args).map { (type, arg) -> type.assignHavoc(arg) }),
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
                                        (TACMeta.SATISFY_ID to summarizationContext.allocSatisfyId())
                                ).withDecls()
                            )
                        }
                    }
                )
            )
        )

        val allCode = entryCode + callCode + exitCode

        val blockgraph = allCode.mapValuesTo(MutableBlockGraph()) { (block, code) ->
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

        val ruleName = "${entryFunc.name}"
        val moveTAC = MoveTACProgram(
            name = ruleName,
            code = allCode.mapValues { (_, code) -> code.cmds },
            blockgraph = blockgraph,
            entryBlock = StartBlock,
            symbolTable = TACSymbolTable(allCode.values.flatMapToSet { it.varDecls })
        )
        return moveTAC
    }

    private fun compileRuleCoreTACProgram(
        entryFunc: MoveFunction,
        sanityMode: SanityMode
    ): Pair<CoreTACProgram, CompiledRuleType> {
        val moveTAC = compileMoveTACProgram(entryFunc, sanityMode)

        ArtifactManagerFactory().dumpCodeArtifacts(moveTAC, ReportTypes.JIMPLE, DumpTime.POST_TRANSFORM)

        val coreTAC = MoveMemory(scene).transform(moveTAC).let { configureOptimizations(it) }
        ArtifactManagerFactory().dumpCodeArtifacts(coreTAC, ReportTypes.SIMPLIFIED, DumpTime.POST_TRANSFORM)

        val ruleType = getRuleType(coreTAC)

        val finalTAC = preprocess(coreTAC, ruleType).letIf(scene.optimize) { optimize(it) }

        return finalTAC to ruleType
    }

    private fun compileRule(
        entryFunc: MoveFunction,
        ruleType: RuleType
    ): List<Pair<EcosystemAgnosticRule, CoreTACProgram>> {
        return when (ruleType) {
            RuleType.USER_RULE -> {
                val (coreTAC, compiledRuleType) = compileRuleCoreTACProgram(entryFunc, SanityMode.NONE)
                listOf(
                    EcosystemAgnosticRule(
                        ruleIdentifier = RuleIdentifier.freshIdentifier(entryFunc.name.toString()),
                        ruleType = SpecType.Single.FromUser.SpecFile,
                        isSatisfyRule = compiledRuleType.isSatisfy
                    ) to coreTAC
                )
            }
            RuleType.SANITY -> {
                val (assertTAC, assertTACType) = compileRuleCoreTACProgram(entryFunc, SanityMode.ASSERT_TRUE)
                check(assertTACType == CompiledRuleType.ASSERT)

                val (satisfyTAC, satisfyTACType) = compileRuleCoreTACProgram(entryFunc, SanityMode.SATISFY_TRUE)
                check(satisfyTACType == CompiledRuleType.SATISFY)

                listOf(
                    EcosystemAgnosticRule(
                        ruleIdentifier = RuleIdentifier.freshIdentifier("${entryFunc.name}-Assertions"),
                        ruleType = SpecType.Single.BuiltIn(BuiltInRuleId.sanity),
                        isSatisfyRule = false
                    ) to assertTAC,
                    EcosystemAgnosticRule(
                        ruleIdentifier = RuleIdentifier.freshIdentifier("${entryFunc.name}-$REACHED_END_OF_FUNCTION"),
                        ruleType = SpecType.Single.BuiltIn(BuiltInRuleId.sanity),
                        isSatisfyRule = true
                    ) to satisfyTAC
                )
            }
        }
    }

    private enum class CompiledRuleType(val isSatisfy: Boolean) { ASSERT(false), SATISFY(true) }

    private fun getRuleType(code: CoreTACProgram): CompiledRuleType {
        val areSatisfyAsserts = code.parallelLtacStream().mapNotNull { (_, cmd) ->
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
        return when(areSatisfyAsserts.uniqueOrNull()) {
            true -> CompiledRuleType.SATISFY
            false -> CompiledRuleType.ASSERT
            null -> throw CertoraException(
                CertoraErrorType.CVL,
                "Rule ${code.name} mixes assert and satisfy commands."
            )
        }
    }


    private fun preprocess(code: CoreTACProgram, ruleType: CompiledRuleType) =
        CoreTACProgram.Linear(code)
        // ConstantStringPropagator must come before TACDSA
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PROPAGATE_STRINGS, ConstantStringPropagator::transform))
        .map(CoreToCoreTransformer(ReportTypes.DSA, TACDSA::simplify))
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.HOIST_LOOPS, LoopHoistingOptimization::hoistLoopComputations))
        .map(CoreToCoreTransformer(ReportTypes.UNROLL, CoreTACProgram::convertToLoopFreeCode))
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE, ConstantPropagator::propagateConstants))
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE, ConstantComputationInliner::rewriteConstantCalculations))
        .map(CoreToCoreTransformer(ReportTypes.MATERIALIZE_CONDITIONAL_TRAPS, ConditionalTrapRevert::materialize))
        .mapIf(ruleType.isSatisfy, CoreToCoreTransformer(ReportTypes.REWRITE_ASSERTS, wasm.WasmEntryPoint::rewriteAsserts))
        .ref

    private fun optimize(code: CoreTACProgram) =
        CoreTACProgram.Linear(code)
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.SNIPPET_REMOVAL, SnippetRemover::rewrite))
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
        .mapIfAllowed(
            CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
                IntervalsRewriter.rewrite(it, handleLeinoVars = false)
            }
        )
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_DIAMONDS) { simplifyDiamonds(it, iterative = true) })
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS2) {
                // after pruning infeasible paths, there are more constants to propagate
                ConstantPropagator.propagateConstants(it, emptySet())
            }
        )
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE2) { Pruner(it).prune() })
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_MERGE_BLOCKS, BlockMerger::mergeBlocks))
        .ref

    /**
        Enables or disables destructive optimizations, which allow us to optimize the TAC in ways that will break the
        call trace.
     */
    @OptIn(Config.DestructiveOptimizationsOption::class)
    private fun configureOptimizations(code: CoreTACProgram) = when (Config.DestructiveOptimizationsMode.get()) {
        DestructiveOptimizationsModeEnum.DISABLE -> code
        DestructiveOptimizationsModeEnum.ENABLE -> code.withDestructiveOptimizations(true)
        DestructiveOptimizationsModeEnum.TWOSTAGE,
        DestructiveOptimizationsModeEnum.TWOSTAGE_CHECKED -> {
            throw CertoraException(
                CertoraErrorType.BAD_CONFIG,
                "Two-stage destructive optimization mode is not yet supported for Move programs."
            )
        }
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

    /**
        Compiles a call to a function with a code unit (as opposed to a native function).
     */
    context(SummarizationContext)
    private fun compileCodeUnitCall(
        call: MoveCall,
        code: MoveFunction.Code
    ): MoveBlocks = annotateCallStack("func.${call.callee.name}") compile@{
        val func = call.callee
        if (func in call.funcsOnStack) {
            return@compile singleBlockSummary(call) {
                loggerSetupHelpers.warn { "Recursive function call to ${call.callee} from ${call.callStack.format()}" }
                assert("Recursive function call to ${call.callee}") { false.asTACExpr }
            }
        }

        val callStack = call.callStack.push(call)

        val uniqueName = func.varNameBase.toUnique()
        data class Local(val type: MoveType, val s: TACSymbol.Var)
        val locals = (func.params + code.locals).mapIndexed { i, type ->
            Local(type, TACSymbol.Var("${uniqueName}!local!$i", type.toTag()))
        }

        val blockIds = mutableMapOf(0 to call.entryBlock)
        fun blockId(blockOffset: Int) = blockIds.getOrPut(blockOffset) { newBlockId() }

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

            // Annotate the start of the function, and move the arguments into locals
            if (blockOffset == 0) {
                check(call.args.size == func.params.size) {
                    "Argument count mismatch: ${call.args.size} != ${func.params.size}"
                }
                cmds += MoveCallTrace.annotateFuncStart(func, call.args)
                for (i in 0..<call.args.size) {
                    cmds += assign(locals[i].s) { call.args[i].asSym() }
                }
            }

            code.block(blockOffset).forEach { (currentOffset, inst, src) ->
                fallthrough = currentOffset + 1

                logger.trace { "$currentOffset/${stack.size}: $inst" }

                val metaInfo = scene.sourceContext.tacMeta(src)
                val meta = metaInfo.toMap()

                fun topVar() = stack.top.let { type ->
                    TACSymbol.Var("${uniqueName}!stack!${stack.size - 1}!${type.symNameExt()}", type.toTag()).asSym()
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

                cmds += when (inst) {
                    is MoveInstruction.BrTrue ->
                        TACCmd.Simple.JumpiCmd(
                            cond = pop().s,
                            dst = successor(inst.branchTarget),
                            elseDst = successor(currentOffset + 1),
                            meta = meta
                        ).withDecls()
                    is MoveInstruction.BrFalse ->
                        TXF { not(pop()) }.letVar(Tag.Bool) { cond ->
                            TACCmd.Simple.JumpiCmd(
                                cond = cond.s,
                                dst = successor(inst.branchTarget),
                                elseDst = successor(currentOffset + 1),
                                meta = meta
                            ).withDecls()
                        }
                    is MoveInstruction.Branch ->
                        TACCmd.Simple.JumpCmd(successor(inst.branchTarget), meta).withDecls()

                    is MoveInstruction.Call -> {
                        val callee = inst.callee
                        val calleeEntry = newBlockId()
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
                            mergeMany(call.returns.reversed().map { assign(it) { pop() } }),
                            MoveCallTrace.annotateFuncEnd(call.callee, call.returns),
                            TACCmd.Simple.JumpCmd(call.returnBlock, meta).withDecls()
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
                                            // for later use (for things like assert messages).  We pack the string to
                                            // an intermediate vector variable, with the UTF8 string value in the meta,
                                            // before pushing it on the stack.  We should be able to find this
                                            // intermediate variable later via NonTrivialDefAnalysis.
                                            val length = buf.getLeb128UInt().toInt()
                                            val bytes = ByteArray(length).also { buf.get(it) }
                                            val string = String(bytes, Charsets.UTF_8)
                                            val stringVar = TACKeyword.TMP(
                                                type.toTag(),
                                                "string",
                                                MetaMap(CONST_STRING to string)
                                            )
                                            bytes.forEach { add(push(MoveType.U8, it.toUByte().toBigInteger())) }
                                            val values = (0..<length).map { pop().s }.reversed()
                                            add(TACCmd.Move.VecPackCmd(stringVar, values).withDecls())
                                            add(push(type, stringVar.asSym()))
                                        } else {
                                            val length = buf.parseList { decode(type.elemType) }.size
                                            val values = (0..<length).map { pop().s }.reversed()
                                            add(TACCmd.Move.VecPackCmd(push(type), values, meta).withDecls())
                                        }
                                    }
                                    is MoveType.GhostArray, is MoveType.MathInt -> `impossible!`
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
                    is MoveInstruction.Xor -> push(MoveType.Bool) {
                        val a = pop()
                        val b = pop()
                        (a or b) and not(a and b)
                    }

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
                }
            }

            fallthrough?.let {
                cmds += TACCmd.Simple.JumpCmd(successor(it)).withDecls()
            }

            tacBlocks[blockId(blockOffset)] = mergeMany(cmds)
        }

        tacBlocks.build()
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
