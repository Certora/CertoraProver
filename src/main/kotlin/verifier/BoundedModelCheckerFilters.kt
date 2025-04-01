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

import config.ReportTypes
import datastructures.stdcollections.*
import instrumentation.transformers.GhostSaveRestoreInstrumenter
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import log.*
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import report.TreeViewReporter
import rules.CompiledRule
import rules.RuleCheckResult
import scene.IScene
import solver.SolverResult
import spec.CVL
import spec.CVLCompiler
import spec.CVLKeywords
import spec.cvlast.*
import spec.rules.IRule
import tac.CallId
import utils.Range
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import verifier.BoundedModelChecker.Companion.copyFunction
import verifier.BoundedModelChecker.Companion.optimize
import verifier.BoundedModelChecker.Companion.toCore
import java.util.concurrent.ConcurrentHashMap

private val logger = Logger(LoggerTypes.BOUNDED_MODEL_CHECKER)

/**
 * An enum of filters on function sequences generated for [BoundedModelChecker].
 *
 * Note that the order of the list is the order in which the filters are checked in the [passes] function, so it should
 * be ordered by how expensive it is to check each filter.
 */
enum class BoundedModelCheckerFilters(private val filter: BoundedModelCheckerFilter, val appliesToChildren: Boolean, val message: String) {
    COMMUTATIVITY(CommutativityFilter, true, "this squence is commutative with another one"),
    LAST_FUNCTION_NON_MODIFYING(LastFunctionNonModifyingFilter, false, "the last function doesn't modify the invariant's storage"),
    IDEMPOTENCY(IdempotencyFilter, true, "this sequence contains consecutive calls to an idempotent function"),
    ;

    companion object {
        fun init(
            cvl: CVL,
            scene: IScene,
            compiler: CVLCompiler,
            compiledFuncs: Map<ContractFunction, Pair<CoreTACProgram, CallId>>,
            invAssertProgs: Map<CVLInvariant, CoreTACProgram>,
            treeViewReporter: TreeViewReporter
        ) {
            entries.forEach { it.filter.init(cvl, scene, compiler, compiledFuncs, invAssertProgs, treeViewReporter) }
        }

        /**
         * Given a [sequence] and the assertion invariant [CoreTACProgram], returns the first [BoundedModelCheckerFilters]
         * that this [sequence] failed to pass, or `null` if the [sequence] passed all filters
         */
        suspend fun passes(sequence: List<List<ContractFunction>>, inv: CVLInvariant): BoundedModelCheckerFilters? {
            require(sequence.isNotEmpty())
            return entries.firstOrNull { !it.filter.passes(sequence, inv) }
        }
    }
}

private sealed interface BoundedModelCheckerFilter {
    fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, Pair<CoreTACProgram, CallId>>,
        invAssertProgs: Map<CVLInvariant, CoreTACProgram>,
        treeViewReporter: TreeViewReporter
    )

    suspend fun passes(sequence: List<List<ContractFunction>>, inv: CVLInvariant): Boolean
}

/**
 * If function `f` and `g` only touch disjoint parts of storage and ghosts (read or write), then calling `f->g` and `g->f` are
 * equivalent, so this filters chooses one ordering and filters out all sequences with that order.
 * Note - If there are multiple possible functions in the steps of the sequence, two steps will be commutative if all
 * functions in the first step commute with all functions in the second one.
 */
private data object CommutativityFilter : BoundedModelCheckerFilter {
    /**
     * @return `true` if the set of storage paths accessed by [f1] is disjoint from the set of storage paths accessed
     * by [f2]
     */
    private fun disjointStorage(f1: Pair<CoreTACProgram, CallId>, f2: Pair<CoreTACProgram, CallId>): Boolean {
        val stateModificationFootprint1 = BoundedModelChecker.getAllStorageAndGhostAccesses(f1.first, f1.second) ?: return false
        val stateModificationFootprint2 = BoundedModelChecker.getAllStorageAndGhostAccesses(f2.first, f2.second) ?: return false

        return !stateModificationFootprint1.overlaps(stateModificationFootprint2)
    }

    /**
     * A function pair in this set is commutative. The [passes] logic will skip sequences where the pair of functions
     * are in the order as it appears in this set, and will pass (keep) sequences where the pair is reversed.
     */
    private lateinit var commutativeFuncs: Set<Pair<ContractFunction, ContractFunction>>

    override fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, Pair<CoreTACProgram, CallId>>,
        invAssertProgs: Map<CVLInvariant, CoreTACProgram>,
        treeViewReporter: TreeViewReporter
    ) {
        val res = mutableSetOf<Pair<ContractFunction, ContractFunction>>()
        val ent = compiledFuncs.entries
            .sortedBy { it.key.getSighash() } // So for each pair the same ordering will be chosen across runs (useful for testing)
            .withIndex()
            .toList()
        for ((ind1, f1) in ent) {
            for (ind2 in ((ind1+1)..ent.lastIndex)) {
                val f2 = ent[ind2].value
                if (!disjointStorage(f1.value, f2.value)) {
                    continue
                }
                val msg = "The function pair (${f1.key}, ${f2.key}) is commutative"
                logger.info { msg }
                CVTAlertReporter.reportAlert(
                    CVTAlertType.BMC,
                    CVTAlertSeverity.WARNING,
                    null,
                    msg,
                    "Sequences where these two function appear consecutively in this order will be skipped"
                )
                res.add(f1.key to f2.key)
            }
        }
        commutativeFuncs = res
        logger.info { "There are ${commutativeFuncs.size} commutative function pairs" }
    }

    override suspend fun passes(
        sequence: List<List<ContractFunction>>,
        inv: CVLInvariant
    ): Boolean {
        return sequence.size < 2 || sequence.zipWithNext { a, b ->
            a.all { aa -> b.all { bb -> (aa to bb) !in commutativeFuncs } }
        }.all { it }
    }

}

/**
 * Given a function `f`, it is idempotent if the state of storage and ghosts is the same when calling `f()` or `f()->f()`.
 * This filter filters out all sequences where idempotent functions appear twice in a row.
 * Note - If there are multiple possible functions in the steps of the sequence, this will check if there are two consecutive
 * steps that have exactly one function each, and it's the same function.
 */
private data object IdempotencyFilter : BoundedModelCheckerFilter {
    private lateinit var idempotencyCheckProgs: Map<ContractFunction, CoreTACProgram>
    private lateinit var treeViewReporter: TreeViewReporter
    private lateinit var scene: IScene
    private val idempotentFuncs = ConcurrentHashMap<ContractFunction, Deferred<Boolean>>()
    private val parentRule = IRule.createDummyRule("").copy(ruleIdentifier = RuleIdentifier.freshIdentifier("Idempotency checks"))

    override fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, Pair<CoreTACProgram, CallId>>,
        invAssertProgs: Map<CVLInvariant, CoreTACProgram>,
        treeViewReporter: TreeViewReporter
    ) {
        fun ParametricInstantiation<CVLTACProgram>.toOptimizedCoreWithGhostInstrumentation(scene: IScene) =
            this.toCore(scene).let {
                CoreToCoreTransformer(ReportTypes.GHOST_ANNOTATION) { code ->
                    // We need to run this transform again because the storage state save and compare add writes and
                    // reads of ghosts
                    GhostSaveRestoreInstrumenter.transform(code)
                }.transformer(it)
            }.optimize(scene)

        idempotencyCheckProgs = compiledFuncs.entries.toList().mapIndexed { i, (func, funcProg) ->
            val copy1 = funcProg.first.copyFunction()
            val copy2 = funcProg.first.copyFunction()

            val initProg = compiler.generateRuleSetupCode().transformToCore(scene).optimize(scene)

            val saveStorageStateProg = compiler.compileCommands(
                withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                    listOf(
                        CVLCmd.Simple.Definition(
                            range,
                            CVLType.PureCVLType.VMInternal.BlockchainState,
                            listOf(
                                CVLLhs.Id(
                                    range,
                                    "afterFirst$i",
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

            val ghostUniverse = cvl.ghosts.filter { !it.persistent }.map {
                StorageBasis.Ghost(it)
            }

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
                                    "afterFirst$i",
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

            val prog = initProg andThen copy1 andThen saveStorageStateProg andThen copy2 andThen compareStorageStateProg

            func to prog.copy(name = "idempotency of $func")
        }.toMap()

        treeViewReporter.addTopLevelRule(parentRule)
        this.treeViewReporter = treeViewReporter
        this.scene = scene
    }

    private suspend fun computeIdempotency(func: ContractFunction): Boolean {
        val rule = IRule.createDummyRule("").copy(ruleIdentifier = parentRule.ruleIdentifier.freshDerivedIdentifier("$func"))
        treeViewReporter.registerSubruleOf(rule, parentRule)
        treeViewReporter.signalStart(rule)
        val compiledRule = CompiledRule.create(rule, idempotencyCheckProgs[func]!!, treeViewReporter.liveStatsReporter)
        val res = compiledRule.check(scene.toIdentifiers(), true).toCheckResult(scene, compiledRule).getOrElse { RuleCheckResult.Error(compiledRule.rule, it) }
        treeViewReporter.signalEnd(rule, res)

        if (res is RuleCheckResult.Single && res.result == SolverResult.UNSAT) {
            val msg = "The function ${func.methodSignature} is idempotent"
            logger.info { msg }
            CVTAlertReporter.reportAlert(
                CVTAlertType.BMC,
                CVTAlertSeverity.WARNING,
                null,
                msg,
                "Sequences where this function is called twice in a row will be skipped"
            )
            return true
        }

        return false
    }

    override suspend fun passes(
        sequence: List<List<ContractFunction>>,
        inv: CVLInvariant
    ): Boolean = coroutineScope {
        sequence.size < 2 || sequence.zipWithNext { _a, _b ->
            _a.singleOrNull()?.let { a ->
                _b.singleOrNull()?.let { b ->
                    a != b || !idempotentFuncs.computeIfAbsent(a) {
                        async { computeIdempotency(a) }
                    }.await()
                }
            }
        }.all { it != false }
    }
}

/**
 * If a function doesn't write to any storage/ghost that the invariant's condition accesses, then having it as the last
 * function in a sequence will always return the same result as that sequence without this function at the end, so skip
 * it.
 * Note - Currently the filter just blindly passes sequences where there are multiple possible functions in the last
 * step of the sequence.
 */
private data object LastFunctionNonModifyingFilter : BoundedModelCheckerFilter {
    /** Mapping of whether a given invariant assertion program and contract function have "interacting" storage or ghosts */
    private lateinit var invAndFuncInteract: Map<Pair<CVLInvariant, ContractFunction>, Boolean>

    override fun init(
        cvl: CVL,
        scene: IScene,
        compiler: CVLCompiler,
        compiledFuncs: Map<ContractFunction, Pair<CoreTACProgram, CallId>>,
        invAssertProgs: Map<CVLInvariant, CoreTACProgram>,
        treeViewReporter: TreeViewReporter
    ) {
        val invAccesses = invAssertProgs.mapValues { (_, assertProg) ->
            BoundedModelChecker.getAllStorageAndGhostAccesses(assertProg, null)
        }

        invAndFuncInteract = invAccesses.flatMap { (inv, invAccess) ->
            compiledFuncs.map { (func, progAndCallId) ->
                if (invAccess == null) {
                    return@map (inv to func) to true
                }
                val funcWrites = BoundedModelChecker.getAllStorageAndGhostWrites(progAndCallId.first, progAndCallId.second)
                    ?: return@map (inv to func) to true

                val res = invAccess.overlaps(funcWrites)
                if (!res) {
                    val msg = "The function $func doesn't modify the storage accessed by the condition of ${inv.id}"
                    logger.info { msg }
                    CVTAlertReporter.reportAlert(
                        CVTAlertType.BMC,
                        CVTAlertSeverity.WARNING,
                        null,
                        msg,
                        "Sequences where this is the last function will be skipped"
                    )
                }
                (inv to func) to res
            }
        }.toMap()
    }

    override suspend fun passes(
        sequence: List<List<ContractFunction>>,
        inv: CVLInvariant
    ): Boolean {
        return sequence.last().any {
            invAndFuncInteract[inv to it]!!
        }
    }

}
