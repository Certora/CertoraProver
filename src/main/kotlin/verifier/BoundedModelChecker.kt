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

import allocator.Allocator
import analysis.CommandWithRequiredDecls
import analysis.controlflow.checkIfAllPathsAreLastReverted
import analysis.icfg.InlinedMethodCallStack
import analysis.storage.StorageAnalysisResult
import bridge.NamedContractIdentifier
import com.certora.collect.*
import config.Config
import config.Config.containsMethodFilteredByConfig
import config.ReportTypes
import datastructures.NonEmptyList
import datastructures.nonEmptyListOf
import datastructures.stdcollections.*
import datastructures.toNonEmptyList
import instrumentation.transformers.InitialCodeInstrumentation
import kotlinx.serialization.Serializable
import kotlin.time.measureTimedValue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import log.*
import parallel.coroutines.parallelMapOrdered
import report.*
import rules.*
import scene.*
import solver.SolverResult
import spec.*
import spec.CVLCompiler.CallIdContext.Companion.toContext
import spec.cvlast.*
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typedescriptors.PrintingContext
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.rules.SingleRuleGenerationMeta
import spec.cvlast.typedescriptors.ToVMContext
import statistics.data.NonLinearStats
import statistics.data.PrettyPrintableStats
import tac.*
import tac.NBId.Companion.ROOT_CALL_ID
import utils.*
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.safeForce
import vc.data.*
import vc.data.ParametricMethodInstantiatedCode.toCheckableTACs
import vc.data.TACProgramCombiners.andThen
import vc.data.state.TACValue
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import kotlin.time.TimeSource

private val logger = Logger(LoggerTypes.BOUNDED_MODEL_CHECKER)

/**
 * Runs invariants in "bounded model checking" format. That is, instead of the normal proof by induction, we create
 * rules that start from a blank storage state, call all constructors in the scene, call some sequence of functions, and
 * then assert the invariant's condition.
 *
 * The length of the sequences is bounded by [Config.BoundedModelChecking].
 *
 * The logic here uses [BoundedModelCheckerFilters] in order to prune out unnecessary sequences, in order to deal with
 * the unfeasible number of possible sequences.
 */
class BoundedModelChecker(
    private val cvl: CVL,
    private val scene: IScene,
    private val mainContract: NamedContractIdentifier,
    private val treeViewReporter: TreeViewReporter,
    private val reporter: OutputReporter
) {
    companion object {
        private fun envParam(n: Int) = "envParam_$n"
        private fun argParam(n: Int) = "argParam_$n"
        private fun param(n: Int, i: Int) = "param_${n}_$i"
        private fun selectorVarName(n: Int) = "SelectorVar_$n"
        private const val SETUP_FUNC_NAME = "setup"

        @JvmInline
        value class FunctionSequence(val funs: TreapList<SequenceElement>) : List<SequenceElement> by funs {
            fun sequenceStr() = this.joinToString(" -> ") { it.dispatchStr() }
            operator fun plus(el: SequenceElement) = FunctionSequence(this.funs + listOf(el))
            companion object {
                fun emptySequence() = FunctionSequence(treapListOf())
            }
            val numFlatSequencesRepresented
                get() = funs.fold(1) { acc, el ->  acc * el.size }
        }

        @JvmInline
        value class SequenceElement(val el: NonEmptyList<ContractFunction>) : List<ContractFunction> by el {
            fun dispatchStr() =
                this.el.singleOrNull()?.methodSignature?.qualifiedMethodName?.printQualifiedFunctionName() // avoids the prefix for the singleton case
                    ?: this.el.toSet().map { it.methodSignature.qualifiedMethodName.printQualifiedFunctionName() }
                        .sorted().joinToString(prefix = "Any of: ")

            fun abiWithContractStr() = this.joinToString("|") { it.abiWithContractStr() }
        }

        /**
         * @param addCallId0Sink Set this to `true` to make sure the program ends with a block with callId=0. This could
         * help make TAC dumps look nicer.
         */
        fun CoreTACProgram.copyFunction(addCallId0Sink: Boolean = false): CoreTACProgram {
            return this.createCopy(Allocator.getFreshId(Allocator.Id.CALL_ID)).letIf(addCallId0Sink) {
                it.addSinkMainCall(listOf(TACCmd.Simple.NopCmd)).first
            }
        }

        fun ParametricInstantiation<CVLTACProgram>.toCore(scene: IScene) = this.getAsSimple().transformToCore(scene)
        fun CommandWithRequiredDecls<TACCmd.Simple>.toCore(id: String, scene: IScene) = this.toProg(id, ROOT_CALL_ID.toContext()).asSimple().toCore(scene)

        fun CoreTACProgram.optimize(scene: IScene): CoreTACProgram {
            val optimized = CompiledRule.optimize(scene.toIdentifiers(), this.withCoiOptimizations(false), bmcMode = true)
            check(!optimized.isEmptyCode()) { "After (non-cone-of-influence) optimizations all the code of `${optimized.name} was optimized away"}
            return optimized
        }

        fun IContractClass.isLibrary(): Boolean {
            return when(this){
                is IContractWithSource -> this.src.isLibrary
                else -> false
            }
        }

        data class StateModificationFootprint(
            private val storage: Set<StorageAnalysisResult.StoragePaths>,
            private val hasTransient: Boolean,
            private val ghosts: Set<String>
        ) {
            fun isEmpty(): Boolean {
                return storage.isEmpty() && !hasTransient && ghosts.isEmpty()
            }

            fun overlaps(other: StateModificationFootprint?): Boolean {
                if (other == null) {
                    // We don't have info on the other one, so assume there could be overlapping
                    return true
                }
                return this.storage.any { it in other.storage } ||
                    (this.hasTransient && other.hasTransient) ||
                    this.ghosts.any { it in other.ghosts }
            }

            operator fun plus(other: StateModificationFootprint?): StateModificationFootprint {
                if (other == null) {
                    return this
                }

                return StateModificationFootprint(
                    storage = this.storage + other.storage,
                    hasTransient = this.hasTransient || other.hasTransient,
                    ghosts = this.ghosts + other.ghosts
                )
            }
        }

        /**
         * @return The set of storage paths and ghosts that [f] writes and reads to/from, or `null` if storage analysis
         * failed.
         *
         * [callId] is used in order to determine which writes/reads are actually within the function and not just in
         * the function call instrumentation code. If `callId == null` then _all_ writes/reads in the program will be collected.
         *
         * Note that for transient storage we just check for any writes/reads, not specific paths.
         */
        fun getAllWritesAndReads(f: CoreTACProgram, callId: CallId?): Pair<StateModificationFootprint?, StateModificationFootprint?> {
            val callStack = InlinedMethodCallStack(f.analysisCache.graph)

            val storageAnalysisFailure = AtomicBoolean(false)
            val writesToTransientStorage = AtomicBoolean(false)
            val readsFromTransientStorage = AtomicBoolean(false)
            val storageWrites = ConcurrentLinkedDeque<StorageAnalysisResult.StoragePaths>()
            val storageReads = ConcurrentLinkedDeque<StorageAnalysisResult.StoragePaths>()
            val ghostWrites = ConcurrentLinkedDeque<String>()
            val ghostReads = ConcurrentLinkedDeque<String>()

            f.parallelLtacStream().filter { lcmd ->
                callId == null || callId in callStack.currentCallIds(lcmd.ptr)
            }.forEach { lcmd ->
                if (TACMeta.LAST_STORAGE_UPDATE in lcmd.cmd.meta) {
                    // lastStorage updates don't count as reads/writes of the program
                    return@forEach
                }
                when (val cmd = lcmd.cmd) {
                    is TACCmd.Simple.WordStore -> {
                        val base = cmd.base
                        if (TACMeta.STORAGE_KEY in base.meta) {
                            base.meta[TACMeta.STABLE_STORAGE_FAMILY]?.let { storageWrites.add(it) } ?: run {
                                storageAnalysisFailure.set(true)
                            }
                        } else if (TACMeta.TRANSIENT_STORAGE_KEY in base.meta) {
                            writesToTransientStorage.set(true)
                        }
                    }

                    is TACCmd.Simple.AssigningCmd.WordLoad -> {
                        val base = cmd.base
                        if (TACMeta.STORAGE_KEY in base.meta) {
                            base.meta[TACMeta.STABLE_STORAGE_FAMILY]?.let { storageReads.add(it) } ?: run {
                                storageAnalysisFailure.set(true)
                            }
                        } else if (TACMeta.TRANSIENT_STORAGE_KEY in base.meta) {
                            readsFromTransientStorage.set(true)
                        }
                    }

                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                        val lhs = cmd.lhs
                        if (TACMeta.STORAGE_KEY in lhs.meta) {
                            lhs.meta[TACMeta.STABLE_STORAGE_FAMILY]?.let { storageWrites.add(it) } ?: run {
                                storageAnalysisFailure.set(true)
                            }
                        } else if (TACMeta.TRANSIENT_STORAGE_KEY in lhs.meta) {
                            writesToTransientStorage.set(true)
                        } else if (TACMeta.CVL_GHOST in lhs.meta) {
                            lhs.meta[TACMeta.CVL_DISPLAY_NAME]?.let { ghostWrites.add(it) } ?: error("expected there to be a display name for the ghost. cmd is $cmd. Has only ${lhs.meta}")
                        }

                        val rhss = cmd.getFreeVarsOfRhs()
                        rhss.forEach { rhs ->
                            if (TACMeta.STORAGE_KEY in rhs.meta) {
                                rhs.meta[TACMeta.STABLE_STORAGE_FAMILY]?.let { storageReads.add(it) } ?: run {
                                    storageAnalysisFailure.set(true)
                                }
                            } else if (TACMeta.TRANSIENT_STORAGE_KEY in rhs.meta) {
                                readsFromTransientStorage.set(true)
                            } else if (TACMeta.CVL_GHOST in rhs.meta) {
                                rhs.meta[TACMeta.CVL_DISPLAY_NAME]?.let { ghostReads.add(it) } ?: error("expected there to be a display name for the ghost. cmd is $cmd. Has only ${rhs.meta}")
                            }
                        }
                    }

                    else -> Unit
                }
            }

            if (storageAnalysisFailure.get()) {
                return null to null
            }

            return StateModificationFootprint(storageWrites.toSet(), writesToTransientStorage.get(), ghostWrites.toSet()) to
                StateModificationFootprint(storageReads.toSet(), readsFromTransientStorage.get(), ghostReads.toSet())
        }

        private fun ContractFunction.abiWithContractStr() = this.methodSignature.qualifiedMethodName.host.name + "." + this.methodSignature.computeCanonicalSignature(PrintingContext(false))
    }

    private val maxSequenceLen = Config.BoundedModelChecking.get()
    private val failLimit = Config.BoundedModelCheckingFailureLimit.get()

    private val invariants = Config.getRuleChoices(cvl.invariants.mapToSet { it.id }).let { chosenInvs ->
        cvl.invariants
            .filter { inv ->
                inv.id in chosenInvs
            }
    }
    private val rules = Config.getRuleChoices(cvl.rules.mapToSet { it.declarationId }).let { chosenRules ->
        cvl.rules
            .filter { rule ->
                rule.declarationId in chosenRules
            }
            .filterIsInstance<CVLSingleRule>()
    }

    /**
     * A [CoreTACProgram] with the following code
     * ```
     * Regular rule setup code;
     * reset_storage all_contracts;
     * require all init_state axioms;
     * call all constructors in the scene;
     * ```
     *
     * Note we currently have no control on the order the constructors are called, this might need to be revisited in
     * the future.
     */

    private val initializationProg: CoreTACProgram

    /**
     * A [CoreTACProgram] that contains a call to a cvl function named `setup`, if such one exists in spec.
     * Otherwise, this is just an empty program.
     */
    private val setupFunctionProg: CoreTACProgram

    /**
     * @param n: A unique number per contract function, used to uniquely define the parameters to the function.
     * @param paramsProg: The [CoreTACProgram] that declares the parameters to the function.
     * @param funcProg: The [CoreTACProgram] of the actual function body.
     * @param callId: The callId of the function body in [funcProg]
     */
    data class FuncData(val n: Int, val paramsProg: CoreTACProgram, val funcProg: CoreTACProgram, val callId: CallId)
    /**
     * A mapping from a [ContractFunction] to its [FuncData]..
     */
    private val compiledFuncs: SortedMap<ContractFunction, FuncData>
    private val easyFuncs: Set<ContractFunction>

    private val invToRule: Map<CVLInvariant, CVLSingleRule>
    private val invProgs: Map<CVLSingleRule, CVLPrograms>

    private val ruleProgs: Map<CVLSingleRule, CVLPrograms>

    private val funcReads: Map<ContractFunction, StateModificationFootprint?>
    private val funcWrites: Map<ContractFunction, StateModificationFootprint?>

    private data class Stats(
        val globalStats: ConcurrentHashMap<String, Long> = ConcurrentHashMap<String, Long>(),
        val ruleStats: ConcurrentHashMap<String, MutableMap<String, Long>> = ConcurrentHashMap<String, MutableMap<String, Long>>()
    ) {
        @Serializable
        private data class SerializableStats(
            val globalStats: Map<String, Long>,
            val ruleStats: Map<String, Map<String, Long>>
        )

        fun toSerializable() =
            SerializableStats(globalStats.toSortedMap().toMap(), ruleStats.toSortedMap().mapValues { it.value.toSortedMap().toMap() })

        fun <T> collectGlobalStats(name: String, f: () -> T): T {
            val t = measureTimedValue(f)
            globalStats.compute(name) { _, _ -> t.duration.inWholeMilliseconds }
            return t.value
        }

        suspend fun <T> collectRuleStats(name: String, sequenceName: String, f: suspend () -> T): T {
            val start = TimeSource.Monotonic.markNow()
            val ret = f()
            val duration = start.elapsedNow()
            ruleStats.compute(name) { _, v ->
                val m = v ?: mutableMapOf()
                m[sequenceName] = duration.inWholeMilliseconds
                m
            }
            return ret
        }
    }

    private val stats = Stats()

    init {
        val compiler = CVLCompiler(scene, this.cvl, "bmc compiler")

        fun CoreTACProgram.applySummaries() =
            InitialCodeInstrumentation.applySummariesAndGhostHooksAndAxiomsTransformations(
                this, scene, cvl, null, null
            )

        initializationProg = stats.collectGlobalStats("initializationProg") {
            val block = withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                val initAxiomCmds = cvl.ghosts
                    .asSequence()
                    .filterIsInstance<CVLGhostWithAxiomsAndOldCopy>()
                    .flatMap { it.axioms }
                    .filterIsInstance<CVLGhostAxiom.Initial>()
                    .map { CVLCmd.Simple.AssumeCmd.Assume(range, it.exp, "init_state axiom", scope) }
                    .toList()
                    .wrapWithMessageLabel("Assume init_state axioms")

                val invokeConstructors = scene.getNonPrecompiledContracts()
                    .filterNot { it.isLibrary() }
                    .flatMapIndexed { i, contract ->
                        listOf(
                            CVLCmd.Simple.Declaration(
                                range,
                                EVMBuiltinTypes.env,
                                envParam(i) + "_constructor",
                                scope
                            ),
                            CVLCmd.Simple.Declaration(
                                range,
                                CVLType.PureCVLType.VMInternal.RawArgs,
                                argParam(i) + "_constructor",
                                scope
                            ),
                            CVLCmd.Simple.contractFunction(
                                range,
                                scope,
                                UniqueMethod(SolidityContract(contract.name), MethodAttribute.Unique.Constructor),
                                listOf(
                                    CVLExp.VariableExp(envParam(i) + "_constructor", EVMBuiltinTypes.env.asTag()),
                                    CVLExp.VariableExp(argParam(i) + "_constructor", CVLType.PureCVLType.VMInternal.RawArgs.asTag())
                                ),
                                true,
                                CVLExp.VariableExp(CVLKeywords.lastStorage.keyword, CVLKeywords.lastStorage.type.asTag()),
                                isWhole = false,
                                isParametric = false,
                                methodParamFilter = null
                            )
                        )
                }.wrapWithMessageLabel("invoke constructors")

                listOf(
                    CVLCmd.Simple.ResetStorage(
                        range,
                        CVLExp.VariableExp(
                            CVLKeywords.allContracts.keyword,
                            CVLKeywords.allContracts.type.asTag()
                        ),
                        scope
                    )
                ) + initAxiomCmds + invokeConstructors
            }

            val prog = compiler.generateRuleSetupCode().transformToCore(scene) andThen compiler.compileCommands(block, "intialization code").toCore(scene)

            prog.copy(name = "initialization code").applySummaries().optimize(scene)
        }

        setupFunctionProg = stats.collectGlobalStats("setupFunctionProg") {
            val callSetup = withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                this@BoundedModelChecker.cvl.subs.find { it.declarationId == SETUP_FUNC_NAME && it.params.isEmpty() }?.let {
                    CVLCmd.Simple.Apply(
                        range,
                        CVLExp.ApplyExp.CVLFunction(
                            it.declarationId,
                            listOf(),
                            CVLType.PureCVLType.Bottom.asTag(),
                            noRevert = true
                        ),
                        scope
                    )
                }
            }

            callSetup?.let {
                compiler.compileCommands(listOf(it), "$SETUP_FUNC_NAME code")
            }?.toCore(scene)?.applySummaries()?.optimize(scene) ?: CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toCore("dummy", scene)
        }

        /**
         * Compiles [func] as a standalone program.
         */
        fun compileFunction(func: ContractFunction): FuncData {
            val n = Allocator.getFreshNumber()
            val cvlParams = func.methodSignature.params.mapIndexed { i, p ->
                val ty = p.vmType.getPureTypeToConvertFrom(ToVMContext.ArgumentPassing).resultOrNull() ?: error("Can't convert ${p.vmType}")
                CVLParam(ty, param(n, i), Range.Empty())
            }
            val paramDeclarations = withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                compiler.declareVariables(
                    listOf(CVLParam(EVMBuiltinTypes.env, envParam(n), range)) + cvlParams,
                    scope,
                    range
                )
            }.toCore(scene)

            val (call, callId) = compiler.compileStandaloneContractFunctionCall(
                func,
                listOf(
                    CVLParam(EVMBuiltinTypes.env, envParam(n), Range.Empty())
                ) + cvlParams,
                true
            ).mapFirst {
                it.transformCode {
                    // Add to the program the declaration of contract variables
                    it.prependToBlock0(compiler.declareContractsAddressVars())
                }.toCore(scene)
            }

            return FuncData(n, paramDeclarations, call.copy(name = func.abiWithContractStr()), callId)
        }

        compiledFuncs = stats.collectGlobalStats("compiledFuncs") {
            this.cvl.importedFuncs.values.asSequence()
                .flatten()
                .filterNot { it.methodSignature is UniqueMethod }
                .filterNot { it.annotation.library }
                .filter { func ->
                    Config.contractChoice.get().let { it.isEmpty() || func.methodSignature.qualifiedMethodName.host.name in it }
                }
                .filterNot { func ->
                    (scene.getContract(SolidityContract(func.methodSignature.qualifiedMethodName.host.name)) as? IContractWithSource)?.src
                        ?.isInternalFunctionHarness(func.methodSignature.qualifiedMethodName.methodId) == true
                }
                .map { contractFunc ->
                    contractFunc to compileFunction(contractFunc)
                }
                // Now that we're done with the CVLCompiler, we can parallelize
                .parallelStream()
                .filter { (_, funcData) ->
                    // Skip always reverting functions
                    !checkIfAllPathsAreLastReverted(funcData.funcProg)
                }
                .map { (contractFunc, funcData) ->
                    val optimizedFuncProg = funcData.funcProg.applySummaries().optimize(scene)
                    val optimizedParamsProg = funcData.paramsProg.optimize(scene)

                    ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                        optimizedFuncProg,
                        ReportTypes.BMC_FUNC,
                        StaticArtifactLocation.Outputs,
                        DumpTime.AGNOSTIC
                    )

                    contractFunc to funcData.copy(paramsProg = optimizedParamsProg, funcProg = optimizedFuncProg)
                }
                .filter { (_, funcData) ->
                    // Note the filtering we do ignores the `isView` attribute of the functions. This is because e.g. Solidity-generated
                    // getters don't have this flag yet we want to skip them, and also it may be that a view function will modify a ghost
                    // via some hook.
                    getAllWritesAndReads(funcData.funcProg, funcData.callId).first?.let { !it.isEmpty() } != false
                }.collect(Collectors.toMap({ it.first }, { it.second })).also {
                    logger.info { "bmc on ${it.size} functions" }
                }.toSortedMap(compareBy<ContractFunction> { it.abiWithContractStr() })
        }

        easyFuncs = compiledFuncs.filter { fdata ->
            fdata.value.funcProg.let { tac ->
                val cmds = tac.code.map { it.value.size }.sum()
                val stats = NonLinearStats.compute(
                    tac, null
                )
                logger.info{ "${fdata.key} cmds: $cmds, nonlins: ${stats.numberOfNonlinearOps}, severity: ${stats.severityGlobal}" }
                cmds < Config.BoundedModelCheckingMergerTACThreshold.get() && stats.severityGlobal == PrettyPrintableStats.Severity.LOW
            }
        }.keys

        logger.info{ "easy: ${easyFuncs.size}, total: ${compiledFuncs.size}" }

        val funcWritesAndReads = stats.collectGlobalStats("funcWritesAndReads") {
            compiledFuncs.mapValues { (_, funcData) ->
                getAllWritesAndReads(funcData.funcProg, funcData.callId)
            }
        }

        funcWrites = funcWritesAndReads.mapValues { (_, writesAndReads) -> writesAndReads.first }

        funcReads = funcWritesAndReads.mapValues { (_, writesAndReads) -> writesAndReads.second }

        invToRule = invariants.map { inv ->
            inv to IRule.createDummyRule(inv.id).copy(
                ruleType = SpecType.Single.BMC.Invariant,
                range = inv.range,
                methodParamFilters = inv.methodParamFilters
            ).also {
                treeViewReporter.addTopLevelRule(it)
            }
        }.toMap()

        invProgs = invariants.map { inv ->
            invToRule[inv]!! to CVLPrograms(inv, compiler) { this.applySummaries() }
        }.toMap()

        ruleProgs = rules.flatMap { rule ->
            treeViewReporter.addTopLevelRule(rule)
            val allProgs = generateRuleProgs(rule, compiler) { this.applySummaries() }
            if (allProgs.size == 1) {
                // This is a non-parametric rule, just take it.
                allProgs.single().second.let { listOf(rule to it) }
            } else {
                // This is a parametric rule, so for each instantiation register it as a subrule of [rule] and add it to
                // the list of ruleProgs.
                // This way the rest of the BMC code can handle parametric rules as if they we just a list of simple
                // rules, and in the tree-view they will all show as subrules of [rule].
                allProgs.map { (instName, cvlProgs) ->
                    val instRule = rule.copy(ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier(instName))
                    treeViewReporter.registerSubruleOf(instRule, rule)
                    instRule to cvlProgs
                }
            }
        }.toMap()

        stats.collectGlobalStats("BoundedModelCheckerFilters.init") {
            BoundedModelCheckerFilters.init(
                cvl,
                scene,
                compiler,
                compiledFuncs,
                funcReads,
                funcWrites,
                (invProgs + ruleProgs).mapValues { (_, progs) -> progs.assert }
            )
        }
    }

    /**
     * Provided a sequence of functions [funcsList], generate a program that calls them in order:
     * `constructor->(funcsList[0][0]|...|funcsList[0][i_0])->...->(funcsList[n][0]|...|funcsList[n][i_n])`.
     * In words, for each index `0<=k<=funcsList.size` create a "dispatch" on the functions in `funcsList[k]`, and then
     * call these dispatchers one after the other.
     *
     * In order to help the solvers, before each such dispatch we insert an `assume` of the invariant ([CVLPrograms.assume]).
     *
     * At the end of the sequence we postpend an assertion of the invariant ([CVLPrograms.assert]).
     */
    private fun generateProgForSequence(
        funcsList: FunctionSequence,
        progs: CVLPrograms,
        includeAssertion: Boolean = true
    ): CoreTACProgram {
        if (funcsList.isEmpty()) {
            // Constructor only case
            val prog = initializationProg andThen setupFunctionProg andThen progs.params
            return if (includeAssertion) {
                prog andThen progs.assert
            } else {
                prog
            }
        }

        val functionCallsProg = funcsList.foldIndexed(
            initializationProg andThen setupFunctionProg andThen progs.params
        ) { idx, outerAcc, contractFunctions ->
            val selectorVar = TACSymbol.Var(selectorVarName(idx), Tag.Int).withMeta(TACMeta.CVL_DISPLAY_NAME, selectorVarName(idx))
            val dispatchProg = CommandWithRequiredDecls(TACCmd.Simple.AssumeCmd(TACSymbol.False, "dispatch")).toCore("dispatch", scene)
            val newDispatch = contractFunctions.fold(dispatchProg) { acc, contractFunction ->
                val condVar = TACKeyword.TMP(Tag.Bool, "selectorCondVar")
                val condProg = CommandWithRequiredDecls(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = condVar,
                        rhs = TACExprFactUntyped { selectorVar.asSym() eq contractFunction.sigHash!!.asTACExpr() }
                    ),
                    setOf(condVar, selectorVar)
                ).toCore("condProg", scene)

                /*
                 * Generate the function call itself. This is comprised of declaring the functions parameters,
                 * calling the preserved block if there is one (which will be using the same parameters thanks to the
                 * transformer used when generating its code, see the comment there), and then the function call itself.
                 * Now make a unique copy of all of this via `copyFunction` to make sure that several calls to the
                 * same function in a given sequence will be independent.
                 */
                val funcData = compiledFuncs[contractFunction]!!
                val preserved = progs.preserveds[contractFunction] ?: CoreTACProgram.empty("no preserved")
                val funcProg = (funcData.paramsProg andThen preserved andThen funcData.funcProg).copyFunction(addCallId0Sink = true)

                val jumpiCmd = TACCmd.Simple.JumpiCmd(
                    cond = condVar,
                    dst = funcProg.entryBlockId,
                    elseDst = acc.entryBlockId
                )
                mergeIfCodes(
                    condProg,
                    jumpiCmd,
                    funcProg,
                    acc
                )
            }

            // In case of invariants we want to assume the invariant before each step of the sequence. In the rule case
            // this will be empty code.
            val assumeProg = (progs.params andThen progs.assume).let {
                it.letIf(!it.isEmptyCode()) { it.copyFunction() }
            }
            outerAcc andThen assumeProg andThen newDispatch
        }

        return if (includeAssertion) {
            functionCallsProg andThen progs.assert
        } else {
            functionCallsProg
        }.copy(
            name = "${progs.id}: " + funcsList.sequenceStr()
        )
    }

    private suspend fun checkProg(
        prog: CoreTACProgram,
        rule: CVLSingleRule,
        generateReport: Boolean = true,
    ): RuleCheckResult.Leaf {
        StatusReporter.registerSubrule(rule)
        reporter.signalStart(rule)
        val compiledRule = CompiledRule.create(rule, prog.withCoiOptimizations(true), DummyLiveStatsReporter)

        val res = compiledRule.check(scene.toIdentifiers(), true)
            .toCheckResult(scene, compiledRule, generateReport = generateReport)
            .getOrElse { RuleCheckResult.Error(compiledRule.rule, it) }

        reporter.addResults(res)

        return res
    }

    private suspend fun isVacuous(prog: CoreTACProgram, rule: CVLSingleRule, registerTreeView: Boolean = false): Boolean {
        val vacuityCheck = prog.appendToSinks(
            CommandWithRequiredDecls(
                TACCmd.Simple.AssertCmd(TACSymbol.False, "not vacuous")
            )
        )

        val thisRule = rule.copy(
            ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier("vacuity"),
            ruleGenerationMeta = SingleRuleGenerationMeta.WithSanity(SingleRuleGenerationMeta.Sanity.BASIC_SANITY),
        )
        if (registerTreeView) {
            treeViewReporter.registerSubruleOf(thisRule, rule)
            treeViewReporter.signalStart(thisRule)
        }
        val res = checkProg(
            vacuityCheck,
            thisRule,
            generateReport = registerTreeView // If we're not going to show the user that this rule even ran, don't bother generating the TAC dumps for it.
        )
        if (registerTreeView) {
            treeViewReporter.signalEnd(thisRule, res)
        }
        return res is RuleCheckResult.Single && res.result == SolverResult.UNSAT
    }

    inner class CVLPrograms(
        val id: String,
        val params: CoreTACProgram,
        val assume: CoreTACProgram,
        val assert: CoreTACProgram,
        val preserveds: Map<ContractFunction, CoreTACProgram>
    ) {
        constructor(inv: CVLInvariant, compiler: CVLCompiler, summaryApplier: CoreTACProgram.() -> CoreTACProgram) : this(
            id = inv.id,
            params = compiler.declareVariables(inv.params, inv.scope, inv.range, noCallId = true).toCore(scene).let {
                if (!it.isEmptyCode()) {
                    it.summaryApplier().optimize(scene)
                } else {
                    it
                }
            },
            assume = compiler.compileCommands(
                CVLCmd.Simple.AssumeCmd.Assume(
                    inv.range,
                    inv.exp,
                    "assume invariant",
                    inv.scope
                ).wrapWithMessageLabel("Assume invariant"),
                "the assumption of ${inv.id}"
            ).toCore(scene).summaryApplier().optimize(scene),
            assert = compiler.compileCommands(
                CVLCmd.Simple.Assert(
                    inv.range,
                    inv.exp,
                    "invariant assertion",
                    inv.scope
                ).wrapWithMessageLabel("assert invariant"),
                "the assert of ${inv.id}"
            ).toCore(scene).summaryApplier().optimize(scene),
            preserveds = compiledFuncs.mapValuesNotNull { (func, funcData) ->
                fun matchesContractAndNameAndParams(methodSig: QualifiedMethodParameterSignature): Boolean {
                    return (methodSig.qualifiedMethodName.host.name == CVLKeywords.wildCardExp.keyword || methodSig.qualifiedMethodName.host == func.methodSignature.qualifiedMethodName.host) &&
                        methodSig.matchesNameAndParams(func.methodSignature)
                }

                val preserveds = inv.proof.preserved
                val preserved = preserveds.find { preserved ->
                    preserved is CVLPreserved.ExplicitMethod && matchesContractAndNameAndParams(preserved.methodSignature)
                } ?: preserveds.find { it is CVLPreserved.Generic } ?: return@mapValuesNotNull null

                val envParamId = preserved.withParams.singleOrNull()?.id
                val paramIds = if (preserved is CVLPreserved.ExplicitMethod) {
                    preserved.params.map { it.id }
                } else {
                    null
                }
                if (envParamId == null && paramIds == null) {
                    return@mapValuesNotNull preserved
                }

                // There is a `with` clause, or parameters to the explicit preserved (or both).
                // Transform the preserved block's code renaming those parameters to their names in the call to [func]
                // so that they will both use the same parameters as expected.
                val renamer = object : CVLCmdTransformer<Nothing>(
                    object : CVLExpTransformer<Nothing> {
                        override fun variable(exp: CVLExp.VariableExp): CollectingResult<CVLExp, Nothing> {
                            return super.variable(exp).map {
                                if (exp.id == envParamId) {
                                    exp.copy(id = envParam(funcData.n))
                                } else {
                                    val i = paramIds?.indexOf(exp.id) ?: -1
                                    if (i != -1) {
                                        exp.copy(id = param(funcData.n, i))
                                    } else {
                                        exp
                                    }
                                }
                            }
                        }
                    }
                ) {}
                when (preserved) {
                    is CVLPreserved.ExplicitMethod -> preserved.copy(
                        block = renamer.cmdList(preserved.block).flatten().safeForce()
                    )

                    is CVLPreserved.Generic -> preserved.copy(
                        block = renamer.cmdList(preserved.block).flatten().safeForce()
                    )

                    else -> `impossible!`
                }
            }.mapValues { (_, preserved) ->
                compiler.compileCommands(preserved.block, "preserved").toCore(scene).summaryApplier().optimize(scene)
            }
        )
    }

    private fun generateRuleProgs(
        rule: CVLSingleRule,
        compiler: CVLCompiler,
        summaryApplier: CoreTACProgram.() -> CoreTACProgram
    ): List<Pair<String, CVLPrograms>> {
        val checkableTACs = compiler
            .compileRule(rule, compiler.CVLBasedFilter(rule.ruleIdentifier), generateSetupCode = false)
            .toCheckableTACs(scene, rule).map {
                it.copy(tac = it.tac.summaryApplier().optimize(scene))
            }

        val hasMethodInstFromNonPrimaryContract = checkableTACs.any { it.methodParameterInstantiation.values.any { it.contractName != mainContract.name} }
        return checkableTACs.map { checkableTAC ->
            val instName = checkableTAC.methodParameterInstantiation.toRuleName(hasMethodInstFromNonPrimaryContract).second
            instName to CVLPrograms(
                id = rule.declarationId,
                params = CoreTACProgram.empty("params are declared as part of the rule"),
                assume = CoreTACProgram.empty("no assume for rules"),
                assert = checkableTAC.tac,
                preserveds = mapOf()
            )
        }
    }

    private suspend fun runAllSequences(baseRule: CVLSingleRule, progs: CVLPrograms): List<RuleCheckResult> {
        val allFuncs =
            CalculateMethodParamFilters(mainContract, cvl.importedFuncs.keys.toList(), cvl.symbolTable)
                .calculate(listOf(IRule.createDummyRule("dummy").copy(methodParamFilters = baseRule.methodParamFilters)))
                .resultOrThrow { errors ->
                    errors.forEach { error ->
                        CVTAlertReporter.reportAlert(
                            CVTAlertType.CVL,
                            CVTAlertSeverity.ERROR,
                            error.location as? TreeViewLocation,
                            error.message,
                            null
                        )
                    }
                    IllegalArgumentException("Calculating the method param filters failed")
                }
                .second.values.single().values.singleOrNull()
                ?.map { method ->
                    compiledFuncs.findEntry { func, _ ->
                        method.getABIString() == func.methodSignature.computeCanonicalSignature(
                            PrintingContext(false)
                        )
                    }
                        ?: error("Couldn't find $method in the compiled funcs")
                }?.map { it.first } ?: compiledFuncs.keys.toList()
        val allFuncsForLastStep = allFuncs
            .filter { func ->
                if (baseRule.ruleType is SpecType.Single.BMC.Invariant) {
                    // In regular prover mode (non-bmc) invariants start at an arbitrary state, and then the invariant
                    // is checked against all functions specified by the `--method` flag.
                    // In BMC mode we can think of a range of length N as N-1 steps to reach some (non-arbitrary) state,
                    // and then the invariant checks the final function of the sequence. Looking at things this way, it
                    // makes sense that the `--method` flag will determine the set of possible last functions in a
                    // sequence.
                    this.cvl.importedFuncs.values.flatten().map { it.abiWithContractStr() }
                        .containsMethodFilteredByConfig(
                            func.methodSignature.computeCanonicalSignatureWithContract(PrintingContext(false)),
                            mainContract.name
                        )
                } else {
                    // In rule mode, the `--method` flag filters parametric methods within the rule, and have nothing to
                    // do with the sequence coming before the rule is called.
                    true
                }
            }

        val nSatResults = AtomicInteger(0)
        val nSequencesChecked = AtomicInteger(0)

        val rangeRules = ConcurrentHashMap<Int, CVLSingleRule>()
        fun getRangeRule(len: Int) =
            rangeRules.computeIfAbsent(len) {
                baseRule.copy(
                    ruleIdentifier = baseRule.ruleIdentifier.freshDerivedIdentifier("Range $len"),
                    ruleType = SpecType.Single.BMC.Range(len)
                ).also {
                    treeViewReporter.registerSubruleOf(
                        it,
                        baseRule,
                        allFuncs.size.toBigInteger().pow(len).toInt() // Total theoretical number of sequences of this length
                    )
                }
            }

        /**
         * Given the [parentFuncs] list, will find all functions `f` such that `parentFuncs + f` is a valid sequence
         * (valid in the sense that it passes all filters), and will then construct and check a [CoreTACProgram] built
         * as a sequence of all functions in [parentFuncs] and appended to it a "dispatching" of all the functions found.
         */
        suspend fun checkRecursive(
            parentRule: CVLSingleRule,
            parentFuncs: FunctionSequence
        ): TreapList<out RuleCheckResult> {
            if (failLimit > 0 && nSatResults.get() >= failLimit) {
                // Hit the max number of errors that should be found. Bail out
                return treapListOf()
            }

            val allLastFuncsToFailedFilters = allFuncsForLastStep.associateWith { func ->
                BoundedModelCheckerFilters.filter(parentFuncs, func, baseRule, funcReads, funcWrites)
            }

            logger.debug { "For sequence ${parentFuncs}, filtering extension candidates gives:\n $allLastFuncsToFailedFilters\n"}

            val sequenceLen = parentFuncs.size + 1
            val rangeRule = getRangeRule(sequenceLen)
            val lastFuncs = allLastFuncsToFailedFilters.filterValues { it == null }.keys.toList()

            // The last element of the sequence will be a dispatch on the functions in lastFuncs, so the functions that
            // got filtered out correspond to sequences that are skipped, so count them as done.
            treeViewReporter.updateFinishedChildren(rangeRule, (allFuncs.size - lastFuncs.size) * parentFuncs.numFlatSequencesRepresented)

            val res = if (lastFuncs.isNotEmpty()) {
                val newSequenceElement = SequenceElement(lastFuncs.toNonEmptyList()!!)
                val seqRule = parentRule.copy(
                    ruleIdentifier = parentRule.ruleIdentifier.freshDerivedIdentifier(newSequenceElement.dispatchStr()),
                    ruleType = SpecType.Single.BMC.Sequence(baseRule)
                )
                treeViewReporter.registerSubruleOf(seqRule, rangeRule)

                val prog = generateProgForSequence(
                    parentFuncs + newSequenceElement,
                    progs
                )

                treeViewReporter.signalStart(seqRule)
                val res = stats.collectRuleStats(baseRule.ruleIdentifier.toString(), "Range $sequenceLen: ${seqRule.ruleIdentifier}") {
                    checkProg(prog, seqRule)
                }

                if (res is RuleCheckResult.Single.WithCounterExamples) {
                    // The sequence failed. So we want to show the specific function (from the dispatch list in the last
                    // step of the sequence) that caused the violation.
                    // The way we do that is to find what sighash SMT chose for the selector variable, and then find the
                    // function that matches that sighash.
                    val sighash = res.ruleCheckInfo.examples.head.model.tacAssignments.findEntry { v, _ ->
                        v.meta[TACMeta.CVL_DISPLAY_NAME] == selectorVarName(sequenceLen - 1)
                    }?.second as? TACValue.PrimitiveValue.Integer
                    val theFunc = lastFuncs.singleOrNull()
                        ?: run {
                            // the sighash can be null if there are not multiple functions among lastFuncs feasible
                            // as per static analysis, optimizing the selection away (can happen if a func must e.g. revert,
                            // but is not generally an always reverting function that we would filter out, only with the sequence before)
                            if(sighash != null) {
                                lastFuncs.find { it.sigHash == sighash.value }
                                    ?: error("the selectorVar's value should have been one of the last functions' sighash")
                            } else { null }
                        }

                    if(theFunc != null) {
                        // Found it :) Now replace the dispatch list from the identifier of the rule with theFunc.
                        treeViewReporter.updateDisplayName(seqRule, theFunc.abiWithContractStr())
                    }
                }

                treeViewReporter.signalEnd(seqRule, res)

                // This seqRule accounted for lastFuncs.size sequences, update the finished children with this info
                treeViewReporter.updateFinishedChildren(rangeRule, lastFuncs.size * parentFuncs.numFlatSequencesRepresented)

                nSequencesChecked.addAndGet(lastFuncs.size)

                if (res is RuleCheckResult.Single && res.result == SolverResult.SAT) {
                    nSatResults.getAndIncrement()
                    return treapListOf(res)
                }
                res
            } else {
                null
            }

            if (sequenceLen == maxSequenceLen) {
                // Recursion end condition
                return listOfNotNull(res).toTreapList()
            }

            val extensions = pickExtensions(allLastFuncsToFailedFilters.filterValues { it == null || !it.appliesToChildren }.keys)

            logger.debug { "For sequence $parentFuncs, picked extensions $extensions" }

            // OK, this is a little confusing.
            // We want to count how many sequences are going to be skipped for each range due to us not calling checkRecursive
            // on the sequence parentFuncs + child.
            // So for each range r larger than the one we're currently checking (which we already updated before) we will skip
            // funcs^(r-seqLen) for each of the children we aren't calling the recursive function on.
            // Times the number of sequences represented by parentFuncs, since we skip this number of further extensions for each of them.
            for (r in sequenceLen+1..maxSequenceLen) {
                treeViewReporter.updateFinishedChildren(
                    getRangeRule(r),
                    (allFuncs.size - extensions.sumOf { it.size }) * allFuncs.size.toBigInteger().pow(r - sequenceLen).toInt()
                        * parentFuncs.numFlatSequencesRepresented
                )
            }

            return (listOfNotNull(res) + extensions.parallelMapOrdered { _, func ->
                val funcs = parentFuncs + func
                val childVacuityProg = generateProgForSequence(funcs, progs, includeAssertion = false)
                val childRule = parentRule.copy(ruleIdentifier = parentRule.ruleIdentifier.freshDerivedIdentifier(func.abiWithContractStr()))

                if (isVacuous(childVacuityProg, childRule)) {
                    logger.debug { "Determined ${childRule.ruleIdentifier} is vacuous, no recursive check." }
                    // similar to the logic in the previous call to updateFinishedChildren, just we're only talking about one child here.
                    for (r in sequenceLen+1..maxSequenceLen) {
                        treeViewReporter.updateFinishedChildren(
                            getRangeRule(r),
                            allFuncs.size.toBigInteger().pow(r - sequenceLen).toInt() * parentFuncs.numFlatSequencesRepresented
                        )
                    }
                    treapListOf()
                } else {
                    checkRecursive(childRule, funcs)
                }
            }.flatten()).toTreapList()
        }

        val constructorsRule = IRule.createDummyRule("").copy(ruleIdentifier = baseRule.ruleIdentifier.freshDerivedIdentifier("Initial State"), ruleType = SpecType.Single.BMC.Range(0)).also {
            treeViewReporter.registerSubruleOf(it, baseRule)
        }

        val constructorVacuityProg = generateProgForSequence(FunctionSequence.emptySequence(), progs, includeAssertion = false)

        val allResults = if (!isVacuous(constructorVacuityProg, constructorsRule, registerTreeView = true)) {
            val constructorProg = generateProgForSequence(FunctionSequence.emptySequence(), progs)
            treeViewReporter.signalStart(constructorsRule)
            val constructorRes = stats.collectRuleStats(baseRule.ruleIdentifier.toString(), "Range 0: ${constructorsRule.ruleIdentifier}") {
                checkProg(constructorProg, constructorsRule)
            }
            treeViewReporter.signalEnd(constructorsRule, constructorRes)
            nSequencesChecked.getAndIncrement()
            if (constructorRes is RuleCheckResult.Single && constructorRes.result == SolverResult.SAT) {
                nSatResults.getAndIncrement()
            }

            listOf(constructorRes) + if (maxSequenceLen > 0) {
                checkRecursive(constructorsRule, FunctionSequence.emptySequence())
            } else {
                listOf()
            }
        } else {
            logger.warn { "${baseRule.declarationId} is vacuous even when running only on the constructors!" }
            listOf()
        }

        logger.info { "${baseRule.declarationId}: Ran a total of $nSequencesChecked sequences" }

        return allResults
    }

    private fun pickExtensions(funs: Set<ContractFunction>): Set<SequenceElement> =
        if (Config.BoundedModelCheckingUseMerger.get()) {
            funs.partition { it in easyFuncs }.let {
                val easy = listOf(it.first)
                val hard = it.second.map { listOf(it) }
                (easy + hard).mapNotNull { it.toNonEmptyList() }.map { SequenceElement(it) }.toSet()
            }
        } else {
            funs.map { SequenceElement(nonEmptyListOf(it)) }.toSet()
        }


    suspend fun checkAll(): List<RuleCheckResult> {
        StatusReporter.freeze()
        try {
            treeViewReporter.use {
                val allToRun = invProgs + ruleProgs

                return allToRun.toList().parallelMapOrdered { _, (baseRule, data) ->
                    runAllSequences(baseRule, data)
                }.flatten()
            }
        } finally {
            ArtifactManagerFactory().writeArtifact("RangerStats.json", overwrite = true) {
                Json { prettyPrint = true }.encodeToString(stats.toSerializable())
            }
        }
    }
}
