/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier

import config.Config
import config.ReportTypes
import datastructures.stdcollections.*
import instrumentation.transformers.CodeRemapper
import instrumentation.transformers.GhostSaveRestoreInstrumenter
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import log.*
import rules.CompiledRule
import rules.RuleCheckResult
import scene.IScene
import solver.SolverResult
import spec.CVL
import spec.CVLCompiler
import spec.CVLKeywords
import spec.cvlast.*
import spec.rules.CVLSingleRule
import spec.rules.IRule
import tac.CallId
import utils.*
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import verifier.BoundedModelChecker.Companion.copyFunction
import verifier.BoundedModelChecker.Companion.optimize
import verifier.BoundedModelChecker.Companion.toCore
import verifier.BoundedModelChecker.Companion.FunctionSequence
import verifier.BoundedModelChecker.Companion.abiWithContractStr
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path

private val logger = Logger(LoggerTypes.BOUNDED_MODEL_CHECKER)

/**
 * An enum of filters on function sequences generated for [BoundedModelChecker].
 *
 * Note that the order of the list is the order in which the filters are checked in the [filter] function, so it should
 * be ordered by how expensive it is to check each filter and filters that apply to children should come first
 * (otherwise we would keep extending a sequence that would have been filtered by a later filter that applies to children,
 * just because it failed one that doesn't apply to children first).
 */
enum class BoundedModelCheckerFilters(private val filter: BoundedModelCheckerFilter, val appliesToChildren: Boolean, val message: String) {
    COMMUTATIVITY(CommutativityFilter, true, "this squence is commutative with another one"),
    IDEMPOTENCY(IdempotencyFilter, true, "this sequence contains consecutive calls to an idempotent function"),
    FUNCTION_NON_MODIFYING(FunctionNonModifyingFilter, false, "a function doesn't modify the invariant's storage"),
    USER_REGEX_FILTER(UserRegexFilter, false, "this sequence was filtered out due to user regexes")
    ;

    companion object {
        fun init(
            cvl: CVL,
            scene: IScene,
            compiler: CVLCompiler,
            compiledFuncs: Map<ContractFunction, BoundedModelChecker.FuncData>,
            funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
            funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
            testProgs: Map<CVLSingleRule, CoreTACProgram>
        ) {
            entries.forEach { it.filter.init(cvl, scene, compiler, compiledFuncs, funcReads, funcWrites, testProgs) }
        }

        /**
         * Given a [sequence] and the assertion invariant [CoreTACProgram], returns the first [BoundedModelCheckerFilters]
         * that this [sequence] failed to pass, or `null` if the [sequence] passed all filters
         */
        suspend fun filter(
            sequence: FunctionSequence,
            extensionCandidate: ContractFunction,
            testRule: CVLSingleRule,
            funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
            funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>
        ): BoundedModelCheckerFilters? {
            return entries.firstOrNull { !it.filter.filter(sequence, extensionCandidate, testRule, funcReads, funcWrites) }
        }
    }
}

private sealed interface BoundedModelCheckerFilter {
    fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, BoundedModelChecker.FuncData>,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        testProgs: Map<CVLSingleRule, CoreTACProgram>
    )

    suspend fun filter(
        sequence: FunctionSequence,
        extensionCandidate: ContractFunction,
        testRule: CVLSingleRule,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>
    ): Boolean
}

/**
 * If function `f` and `g` only touch disjoint parts of storage and ghosts (read or write), then calling `f->g` and `g->f` are
 * equivalent, so this filters chooses one ordering and filters out all sequences with that order.
 * Note - If there are multiple possible functions in the steps of the sequence, two steps will be commutative if all
 * functions in the first step commute with all functions in the second one.
 */
private data object CommutativityFilter : BoundedModelCheckerFilter {
    /**
     * A function pair in this set is commutative. The [filter] logic will skip sequences where the pair of functions
     * are in the order as it appears in this set, and will pass (keep) sequences where the pair is reversed.
     */
    private lateinit var commutativeFuncs: Set<Pair<ContractFunction, ContractFunction>>

    override fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, BoundedModelChecker.FuncData>,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        testProgs: Map<CVLSingleRule, CoreTACProgram>
    ) {
        val res = mutableSetOf<Pair<ContractFunction, ContractFunction>>()
        val ent = compiledFuncs.keys
            .sortedBy { it.getSighash() } // So for each pair the same ordering will be chosen across runs (useful for testing)
            .withIndex()
            .toList()
        for ((ind1, f1) in ent) {
            for (ind2 in ((ind1+1)..ent.lastIndex)) {
                val f2 = ent[ind2].value

                // If any of the following returns null it means we had a storage analysis failure, and therefore we can't
                // make any assumptions about the commutativity of that function nd we just continue the loop.
                val f1Reads = funcReads[f1] ?: continue
                val f2Reads = funcReads[f2] ?: continue
                val f1Writes = funcWrites[f1] ?: continue
                val f2Writes = funcWrites[f2] ?: continue

                if(
                    f1Reads.overlaps(f2Writes) ||
                    f1Writes.overlaps(f2Reads) ||
                    f1Writes.overlaps(f2Writes)
                ) {
                    continue
                }

                val msg = "The function pair (${f1}, ${f2}) is commutative"
                logger.info { msg }
                res.add(f1 to f2)
            }
        }
        commutativeFuncs = res
        Logger.always("There are ${commutativeFuncs.size} commutative function pairs", respectQuiet = true)
    }

    override suspend fun filter(
        sequence: FunctionSequence,
        extensionCandidate: ContractFunction,
        testRule: CVLSingleRule,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>
    ): Boolean {
        return sequence.isEmpty() || sequence.last().any {
            // if any of the functions in the previous sequence element should appear in this order with the candidate,
            // we need to keep it
            (it to extensionCandidate) !in commutativeFuncs
        }

    }

}

/**
 * Given a function `f`, it is idempotent if the state of storage and ghosts is the same when calling `f()` or `f()->f()`.
 * This filter filters out all sequences where idempotent functions appear twice in a row.
 * Note the test here is a little more general the strict idempotency - strictly speaking, idempotency means that the second
 * call has no effect, but here what we're checking is that the first call's result is completely overwritten by the
 * second call, making it pointless to call the function twice in a row.
 *
 * The test is performed by checking the following rule:
 * ```
 * storage storageInit = lastStorage;
 * foo(params1);
 * foo(params2);
 * storage afterTwoCalls = lastStorage;
 * foo(params2) at storageInit; // notice here it's the same parameters as the second call on purpose)
 * assert lastStorage == afterTwoCalls;
 * ```
 */
private data object IdempotencyFilter : BoundedModelCheckerFilter {
    private lateinit var idempotencyCheckProgs: Map<ContractFunction, CoreTACProgram>
    private lateinit var scene: IScene
    private val idempotentFuncs = ConcurrentHashMap<ContractFunction, Deferred<Boolean>>()
    private val parentRule = IRule.createDummyRule("").copy(ruleIdentifier = RuleIdentifier.freshIdentifier("Idempotency checks"))

    private class RemapperState(val idMap: MutableMap<Pair<Any, Int>, Int>)

    /**
     * This remapper will only remap the callIds of the blocks but not of variables, essentially leaving them "linked"
     * to their values in the original code. This is used so the third time we call `foo` (from the pseudocode in the
     * [IdempotencyFilter] documentation) it will use the same arguments as those in the second call to it.
     */
    private val freshCopyRemapper = CodeRemapper(
        variableMapper = { _, v -> v },
        idRemapper = CodeRemapper.IdRemapperGenerator.generatorFor(RemapperState::idMap),
        callIndexStrategy = object : CodeRemapper.CallIndexStrategy<RemapperState> {
            override fun remapCallIndex(
                state: RemapperState,
                callIndex: CallId,
                computeFreshId: (CallId) -> CallId
            ): CallId {
                return computeFreshId(callIndex)
            }

            override fun remapVariableCallIndex(
                state: RemapperState,
                v: TACSymbol.Var,
                computeFreshId: (CallId) -> CallId
            ): TACSymbol.Var = v
        },
        blockRemapper = { bId, remap, _, _ -> bId.copy(calleeIdx = remap(bId.calleeIdx)) }
    )

    override fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, BoundedModelChecker.FuncData>,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        testProgs: Map<CVLSingleRule, CoreTACProgram>
    ) {
        if (Config.BoundedModelChecking.get() < 2) {
            // There will be no sequences where this check is relevant
            return
        }

        fun ParametricInstantiation<CVLTACProgram>.toOptimizedCoreWithGhostInstrumentation(scene: IScene) =
            this.toCore(scene).let {
                CoreToCoreTransformer(ReportTypes.GHOST_ANNOTATION) { code ->
                    // We need to run this transform again because the storage state save and compare add writes and
                    // reads of ghosts
                    GhostSaveRestoreInstrumenter.transform(code)
                }.transformer(it)
            }.optimize(scene)

        idempotencyCheckProgs = compiledFuncs.entries.toList().mapIndexed { i, (func, funcData) ->
            val copy1 = (funcData.paramsProg andThen funcData.funcProg).copyFunction(addCallId0Sink = true)
            val copy2 = funcData.paramsProg andThen funcData.funcProg

            val initProg = compiler.generateRuleSetupCode().transformToCore(scene).optimize(scene)

            fun saveStorageStateProg(storageName: String) = compiler.compileCommands(
                withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                    listOf(
                        CVLCmd.Simple.Definition(
                            range,
                            CVLType.PureCVLType.VMInternal.BlockchainState,
                            listOf(
                                CVLLhs.Id(
                                    range,
                                    storageName,
                                    CVLType.PureCVLType.VMInternal.BlockchainState.asTag()
                                )
                            ),
                            CVLExp.VariableExp(
                                CVLKeywords.lastStorage.keyword,
                                CVLKeywords.lastStorage.type.asTag()
                            ),
                            scope
                        )
                    )
                },
                "check idempotency of $func"
            ).toOptimizedCoreWithGhostInstrumentation(scene)

            val saveInitialStorageState = saveStorageStateProg("storageInit$i")
            val saveAfterTwoCallsStorageState = saveStorageStateProg("afterTwoCalls$i")

            val ghostUniverse = cvl.ghosts.filter { !it.persistent }.map {
                StorageBasis.Ghost(it)
            }

            val restoreStorageState = compiler.compileSetStorageState("storageInit$i").toOptimizedCoreWithGhostInstrumentation(scene)

            val compareStorageStateProg = compiler.compileCommands(
                withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                    listOf(
                        CVLCmd.Simple.Assert(
                            range,
                            CVLExp.RelopExp.EqExp(
                                CVLExp.VariableExp(
                                    CVLKeywords.lastStorage.keyword,
                                    CVLKeywords.lastStorage.type.asTag()
                                ),
                                CVLExp.VariableExp(
                                    "afterTwoCalls$i",
                                    CVLKeywords.lastStorage.type.asTag()
                                ),
                                CVLType.PureCVLType.Primitive.Bool.asTag().copy(
                                    annotation = ComparisonType(ComparisonBasis.All(ghostUniverse), false)
                                )
                            ),
                            "not idempotent",
                            scope
                        )
                    )
                },
                "check idempotency of $func"
            ).toOptimizedCoreWithGhostInstrumentation(scene)

            val state = RemapperState(mutableMapOf())
            val copy2Again = copy2.remap(freshCopyRemapper, state).let { copy2.copy(code = it.first, blockgraph = it.second, procedures = it.third) }
                .addSinkMainCall(listOf(TACCmd.Simple.NopCmd)).first // This is so the funcProg ends with a callId=0 block - otherwise TAC dumps look weird.

            val prog = initProg andThen saveInitialStorageState andThen copy1 andThen copy2 andThen
                (saveAfterTwoCallsStorageState andThen restoreStorageState andThen copy2Again andThen compareStorageStateProg)

            func to prog.copy(name = "idempotency of $func")
        }.toMap()

        this.scene = scene
    }

    private suspend fun computeIdempotency(func: ContractFunction): Boolean {
        val rule = IRule.createDummyRule("").copy(ruleIdentifier = parentRule.ruleIdentifier.freshDerivedIdentifier("$func"))
        val compiledRule = CompiledRule.create(rule, idempotencyCheckProgs[func]!!, report.DummyLiveStatsReporter)
        val res = compiledRule.check(scene.toIdentifiers(), true).toCheckResult(scene, compiledRule).getOrElse { RuleCheckResult.Error(compiledRule.rule, it) }

        if (res is RuleCheckResult.Single && res.result == SolverResult.UNSAT) {
            Logger.always("The function ${func.methodSignature} is idempotent", respectQuiet = true)
            return true
        }

        return false
    }

    override suspend fun filter(
        sequence: FunctionSequence,
        extensionCandidate: ContractFunction,
        testRule: CVLSingleRule,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>
    ): Boolean = coroutineScope {
        sequence.isEmpty() || sequence.last().size > 1 || sequence.last()[0] != extensionCandidate
            || !idempotentFuncs.computeIfAbsent(extensionCandidate) {
            async { computeIdempotency(extensionCandidate) }
        }.await()
    }
}

/**
 * If a function doesn't write to any storage/ghost that the invariant's condition accesses, then having it as the last
 * function in a sequence will always return the same result as that sequence without this function at the end, so skip
 * it.
 */
private data object FunctionNonModifyingFilter : BoundedModelCheckerFilter {
    /** Mapping of whether a given invariant assertion program and contract function have "interacting" storage or ghosts */
    private lateinit var testProgAndFuncInteract: Map<Pair<CVLSingleRule, ContractFunction>, Boolean>

    private val secondReadsFromFirst = ConcurrentHashMap<Pair<ContractFunction, ContractFunction>, Boolean>()

    override fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, BoundedModelChecker.FuncData>,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        testProgs: Map<CVLSingleRule, CoreTACProgram>
    ) {
        val testAccesses = testProgs.mapValues { (_, testProg) ->
            val (writes, reads) = BoundedModelChecker.getAllWritesAndReads(testProg, null)
            writes?.plus(reads)
        }

        testProgAndFuncInteract = testAccesses.flatMap { (testRule, testAccess) ->
            compiledFuncs.map { (func, _) ->
                if (testAccess == null) {
                    return@map (testRule to func) to true
                }
                val fWrites = funcWrites[func] ?: return@map (testRule to func) to true

                val res = testAccess.overlaps(fWrites)
                if (!res) {
                    val msg = "The function $func doesn't modify the storage accessed by the condition of ${testRule.declarationId}"
                    logger.info { msg }
                }
                (testRule to func) to res
            }
        }.toMap()
    }

    override suspend fun filter(
        sequence: FunctionSequence,
        extensionCandidate: ContractFunction,
        testRule: CVLSingleRule,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>
    ): Boolean {
        if (sequence.isEmpty()) {
            return testProgAndFuncInteract[testRule to extensionCandidate]!!
        }
        if (!testProgAndFuncInteract[testRule to extensionCandidate]!!) {
            return false
        }

        // If there is a function in the sequence that interacts neither with the rule nor any further function,
        // there is an equivalent sequence without it that is good enough to check.
        // Note that this filter does not apply to children, we can still extend a sequence with this extensionCandidate
        // and further functions that may read from both.
        for (i in sequence.indices) {
            val f = sequence[i]
            if (f.any { testProgAndFuncInteract[testRule to it]!! }) {
                continue
            }
            if (f.none { g ->
                    val possibleReaders =
                        sequence.subList(i + 1, sequence.size).flatten() + listOf(extensionCandidate)
                    possibleReaders.any { reader ->
                        secondReadsFromFirst.computeIfAbsent(g to reader) {
                            check(g in funcWrites) { "$g not in funcWrites!" }
                            check(reader in funcReads) { "$reader not in funcReads!" }
                            funcWrites[g]?.overlaps(funcReads[reader]) != false
                        }
                    }
                }) {
                return false
            }
        }
        return true
    }
}

/**
 * Filter sequence against inclusion and exclusion regexes given by user.
 * For merged sequences, it suffices if there is one of the represented flat sequences
 * passing the filter to let the whole merged sequence pass, i.e. if one of the flat
 * sequences matches the inclusion regex resp. doesn't match the exclusion regex.
 */
private data object UserRegexFilter : BoundedModelCheckerFilter {

    @Serializable
    data class FilterConfig(val includes: List<String> = emptyList(), val excludes: List<String> = emptyList())

    data class RegexSequence(val matchAtStart: Boolean, val items: List<RegexElement>) {
        companion object {
            const val START_MATCH_SYMBOL = "^"
            @Suppress("ForbiddenMethodCall")
            fun fromString(s: String): RegexSequence {
                val (matchAtStart, seq) = if (s.startsWith(START_MATCH_SYMBOL)) {
                    true to s.substring(START_MATCH_SYMBOL.length)
                } else {
                    false to s
                }
                val items = seq.split("~>").map { RegexElement(it.split("->").map {r -> r.toRegex()}) }
                return RegexSequence(matchAtStart, items)
            }

        }
        fun sizeFromIndex(idx: Int) = items.mapIndexed {i, item -> if (i < idx) {0} else {item.size} }.sum()

        fun matchOneAny(funs: BoundedModelChecker.Companion.SequenceElement, regex: Regex): Boolean =
            funs.any { f -> regex.matches(f.abiWithContractStr()) }
        fun matchConsecutiveAny(sequence: FunctionSequence, sequenceIndex: Int, regexes: RegexElement): Boolean {
            if (sequenceIndex + regexes.consecutiveRegexes.size > sequence.size) {
                // there are not enough elements in the sequence to match the regexes
                return false
            }
            regexes.consecutiveRegexes.forEachIndexed { index, regex ->
                if (!matchOneAny(sequence[sequenceIndex + index], regex)) {
                    return false
                }
            }
            return true
        }
        /**
         * Returns true if any underlying sequence represented by the possibly merged [sequence] matches [this]
         */
        fun matchAny(sequence: FunctionSequence) : Boolean {
            var currentFunIndex = 0
            var currentRegexIndex = 0
            while (currentFunIndex < sequence.size && currentRegexIndex < items.size) {
                if(currentFunIndex + sizeFromIndex(currentRegexIndex) > sequence.size) {
                    // not enough elements left in function sequence
                    return false
                }
                val currRegexElem = items[currentRegexIndex]
                if (matchConsecutiveAny(sequence, currentFunIndex, currRegexElem)) {
                    if(currentRegexIndex == items.size - 1) { return true }
                    currentRegexIndex += 1
                    currentFunIndex += currRegexElem.size
                } else if (matchAtStart && currentRegexIndex == 0) {
                    // must match the first regex element on the first function element when matchAtStart is true
                    return false
                } else {
                    currentFunIndex += 1
                }
            }
            return false
        }
        /**
         * Returns true if all underlying sequences represented by the possibly merged [sequence] match [this]
         */
        fun matchAll(sequence: FunctionSequence) : Boolean {
            val sequences = sequence.expandIntoFlatSequences()
            return sequences.all { matchAny(it) }
        }

    }
    data class RegexElement(val consecutiveRegexes: List<Regex>) {
        val size
            get() = consecutiveRegexes.size
    }

    var includes: List<RegexSequence> = emptyList()
    var excludes: List<RegexSequence> = emptyList()

    @Suppress("ForbiddenMethodCall")
    override fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, BoundedModelChecker.FuncData>,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        testProgs: Map<CVLSingleRule, CoreTACProgram>
    ) {
        val resourceLabel = "rangerFilters:"

        val resourcesProvided =
            Config.ResourceFiles.getOrNull()?.filter { it.startsWith(resourceLabel) }?.ifEmpty { null }
                ?: return
        if (resourcesProvided.size > 1) {
            Logger.alwaysWarn("Got more than one resource file with the ranger filters label '$resourceLabel'.")
        }

        val path = resourcesProvided.first().substring(resourceLabel.length).trim()
        val wrappedPath = Path(ArtifactFileUtils.wrapPathWith(path, Config.getSourcesSubdirInInternal()))
        if (!wrappedPath.toFile().exists()) {
            logger.warn {"The specified ranger filters file does not exist: $wrappedPath"}
            return
        }

        val parsed = try {
            val content = wrappedPath.toFile().readText()
            Json.decodeFromString<FilterConfig>(content)
        } catch (e: SerializationException) {
            logger.warn(e) {"Failed to parse the specified ranger filters file: $wrappedPath"}
            return
        }
        logger.debug { parsed }
        includes = parsed.includes.map(RegexSequence::fromString)
        excludes = parsed.excludes.map(RegexSequence::fromString)
        logger.info { "Includes: $includes\n Excludes: $excludes" }
        if(excludes.isNotEmpty()) {
            logger.warn { "Computing sequence filtering with exclude regexes is expensive," +
                "consider using rather include regexes if possible." }
        }
    }

    override suspend fun filter(
        sequence: FunctionSequence,
        extensionCandidate: ContractFunction,
        testRule: CVLSingleRule,
        funcReads: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>,
        funcWrites: Map<ContractFunction, BoundedModelChecker.Companion.StateModificationFootprint?>
    ): Boolean {
        val extendedSequence = sequence + BoundedModelChecker.Companion.SequenceElement(extensionCandidate)
        if (includes.isNotEmpty() && includes.none { include -> include.matchAny(extendedSequence) }) {
            // no expansion of the sequence should be included according to inclusion regex
            logger.debug { "Filtering out ${extendedSequence.sequenceStr()} due to inclusion regex." }
            return false
        }
        if (excludes.isNotEmpty() && excludes.any { exclude -> exclude.matchAll(extendedSequence) }) {
            // all expansions of the sequence should be excluded according to exclusion regex
            logger.debug { "Filtering out ${extendedSequence.sequenceStr()} due to exclusion regex." }
            return false
        }
        return true
    }
}
