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
import analysis.icfg.InlinedMethodCallStack
import analysis.storage.StorageAnalysisResult
import bridge.NamedContractIdentifier
import com.certora.collect.*
import config.Config
import config.Config.containsMethodFilteredByConfig
import config.ReportTypes
import datastructures.stdcollections.*
import instrumentation.transformers.InitialCodeInstrumentation
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
import tac.*
import tac.NBId.Companion.ROOT_CALL_ID
import utils.*
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.safeForce
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import vc.data.state.TACValue
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors

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
    cvl: CVL,
    private val scene: IScene,
    private val mainContract: NamedContractIdentifier,
    private val treeViewReporter: TreeViewReporter,
    private val reporter: OutputReporter
) {
    companion object {
        private const val INV_N = "invN"
        private fun envParam(n: Int) = "envParam$n"
        private fun argParam(n: Int) = "argParam$n"
        private fun selectorVarName(n: Int) = "SelectorVar$n"
        private const val SETUP_FUNC_NAME = "setup"

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

        private fun Collection<ContractFunction>.dispatchStr() =
            this.singleOrNull()?.methodSignature?.qualifiedMethodName?.printQualifiedFunctionName() // avoids the prefix for the singleton case
                ?: this.toSet().map { it.methodSignature.qualifiedMethodName.printQualifiedFunctionName() }.sorted().joinToString(prefix = "Any of: ")

        private fun Collection<Collection<ContractFunction>>.sequenceStr() = this.joinToString(" -> ") { it.dispatchStr() }
    }

    private val maxSequenceLen = Config.BoundedModelChecking.get()
    private val cvl = cvl.copy(ghosts = cvl.ghosts + CVLGhostDeclaration.Variable(Range.Empty(), CVLType.PureCVLType.Primitive.Mathint, INV_N, true, listOf(), CVLScope.AstScope))
    private val invariants = Config.getRuleChoices(cvl.invariants.mapToSet { it.id }).let { chosenInvs ->
        cvl.invariants
            .filter { inv ->
                inv.id in chosenInvs
            }
            .mapIndexed { index, cvlInvariant -> cvlInvariant to index }.toMap()
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
     * A mapping from a [ContractFunction] to the [CoreTACProgram] it compiles to along with the [CallId] of that
     * function in the program. See [compileFunction] for details of what these programs contain.
     */
    private val compiledFuncs: SortedMap<ContractFunction, Pair<CoreTACProgram, CallId>>

    private val invProgs: Map<CVLInvariant, InvariantPrograms>

    private val funcReads: Map<ContractFunction, StateModificationFootprint?>
    private val funcWrites: Map<ContractFunction, StateModificationFootprint?>

    init {
        val compiler = CVLCompiler(scene, this.cvl, "bmc compiler")

        fun CoreTACProgram.applySummaries() =
            InitialCodeInstrumentation.applySummariesAndGhostHooksAndAxiomsTransformations(
                this, scene, cvl, null, null
            )

        initializationProg = run {
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
        check(!initializationProg.isEmptyCode()) {
            "Optimizations removed all initialization code!"
        }

        setupFunctionProg = run {
            val callSetup = withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                this@run.cvl.subs.find { it.declarationId == SETUP_FUNC_NAME && it.params.isEmpty() }?.let {
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
         * Compiles [func] as a standalone program, with the preserved blocks of all invariants from the spec inlined before
         * the function call.
         *
         * The way the preserved block inlining works is that we add a new persistent ghost, [INV_N], and each invariant
         * will set this to a different number (from the [invariants] mapping).
         * The preserved blocks are then inserted within an if-else sequence that compares the value of [INV_N] to the
         * corresponding invariant's constant. i.e. it will look like this:
         * ```
         * if (invN == 0) {
         *     //invariant 0's preserved block
         * } else if (invN == 1) {
         *     //invariant 1's preserved block
         * } else if (...) {
         * ...
         * } else {
         *     assume false;
         * }
         * ```
         *
         * Another issue regarding inlining the preserved block is establishing the relation between the env variable
         * declared via `with (env e)` and the actual env variable used in the call to the function. This is done by having
         * a transform on the invariant's code that renames the variable declared via the `with` clause to the name used by
         * the function call.
         */
        fun compileFunction(func: ContractFunction): Pair<CoreTACProgram, CallId> {
            val n = Allocator.getFreshNumber()
            fun param(i: Int) = "param${n}_$i"

            fun matchesContractAndNameAndParams(methodSig: QualifiedMethodParameterSignature): Boolean {
                return (methodSig.qualifiedMethodName.host.name == CVLKeywords.wildCardExp.keyword || methodSig.qualifiedMethodName.host == func.methodSignature.qualifiedMethodName.host) &&
                    methodSig.matchesNameAndParams(func.methodSignature)
            }

            val preservedList = invariants.mapKeys { (inv, _) -> inv.proof.preserved }.filterKeys { preserved ->
                preserved.any { (it is CVLPreserved.ExplicitMethod && matchesContractAndNameAndParams(it.methodSignature)) || it is CVLPreserved.Generic }
            }.mapKeys { (_preserved, _) ->
                val preserved = _preserved.find { it is CVLPreserved.ExplicitMethod && matchesContractAndNameAndParams(it.methodSignature) }
                    ?: _preserved.filterIsInstance<CVLPreserved.Generic>().single()
                val envParamId = preserved.withParams.singleOrNull()?.id
                val paramIds = if (preserved is CVLPreserved.ExplicitMethod) {
                    preserved.params.map { it.id }
                } else {
                    null
                }
                if (envParamId == null && paramIds == null) {
                    return@mapKeys preserved
                }

                // OK, there's an env param declared via a `with` clause. Let's rename it.
                // See the kdoc of this function for details.
                val renamer = object : CVLCmdTransformer<Nothing>(
                    object : CVLExpTransformer<Nothing> {
                        override fun variable(exp: CVLExp.VariableExp): CollectingResult<CVLExp, Nothing> {
                            return super.variable(exp).map {
                                if (exp.id == envParamId) {
                                    exp.copy(id = envParam(n))
                                } else {
                                    val i = paramIds?.indexOf(exp.id) ?: -1
                                    if (i != -1) {
                                        exp.copy(id = param(i))
                                    } else {
                                        exp
                                    }
                                }
                            }
                        }
                    }
                ) {}
                when (preserved) {
                    is CVLPreserved.ExplicitMethod -> preserved.copy(block = renamer.cmdList(preserved.block).flatten().safeForce())
                    is CVLPreserved.Generic -> preserved.copy(block = renamer.cmdList(preserved.block).flatten().safeForce())
                    else -> `impossible!`
                }
            }.toList()

            val cvlParams = func.methodSignature.params.mapIndexed { i, p ->
                val ty = p.vmType.getPureTypeToConvertFrom(ToVMContext.ArgumentPassing).resultOrNull() ?: error("Can't convert ${p.vmType}")
                CVLParam(ty, param(i), Range.Empty())
            }
            val paramDeclarations = withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                compiler.declareVariables(
                    listOf(CVLParam(EVMBuiltinTypes.env, envParam(n), range)) + cvlParams,
                    scope,
                    range
                )
            }.toCore(scene)

            // Generate the "dispatch" of the preserved blocks, see the kdoc of this function for details.
            val preservedDispatch = withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                preservedList.fold(CVLCmd.Simple.Nop(range, scope) as CVLCmd) { acc, (pres, i) ->
                    CVLCmd.Composite.If(
                        range,
                        CVLExp.RelopExp.EqExp(
                            CVLExp.VariableExp(INV_N, CVLType.PureCVLType.Primitive.Mathint.asTag()),
                            CVLExp.Constant.NumberLit(
                                i.toBigInteger(),
                                CVLType.PureCVLType.Primitive.NumberLiteral(i.toBigInteger()).asTag()
                            ),
                            CVLType.PureCVLType.Primitive.Bool
                                .asTag()
                                .copy(annotation = CVLCmd.Composite.If.ConstantIfCond)
                        ),
                        CVLCmd.Composite.Block(
                            range,
                            pres.block,
                            scope
                        ),
                        acc,
                        scope
                    )
                }
            }.wrapWithMessageLabel("Preserved block for $func").let { compiler.compileCommands(it, "preserved block") }.toCore(scene)

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

            val prog = paramDeclarations andThen preservedDispatch andThen call

            return prog.copy(name = func.abiWithContractStr()) to callId
        }

        invProgs = invariants.mapValues { (inv, _) ->
            InvariantPrograms(inv, compiler) { this.applySummaries() }
        }

        compiledFuncs = this.cvl.importedFuncs.values.asSequence()
        .flatten()
            .filterNot { it.methodSignature is UniqueMethod }
            .filterNot { it.annotation.library }
            .filter { func ->
                Config.contractChoice.get().let { it.isEmpty() || func.methodSignature.qualifiedMethodName.host.name in it }
            }
            .filter { func ->
                this.cvl.importedFuncs.values.flatten().map { it.abiWithContractStr() }.containsMethodFilteredByConfig(
                    func.methodSignature.computeCanonicalSignatureWithContract(PrintingContext(false)),
                    mainContract.name
                )
            }
            .map { contractFunc ->
                contractFunc to compileFunction(contractFunc)
            }
            // Now that we're done with the CVLCompiler, we can parallelize
            .parallelStream()
            .map { (contractFunc, progAndCallId) ->
                val (prog, callId) = progAndCallId
                val optimized = prog.applySummaries().optimize(scene)

                ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                    optimized,
                    ReportTypes.BMC_FUNC,
                    StaticArtifactLocation.Outputs,
                    DumpTime.AGNOSTIC
                )

                contractFunc to (optimized to callId)
            }
            .filter { (_, progAndCallId) ->
                val (prog, callId) = progAndCallId
                // Note the filtering we do ignores the `isView` attribute of the functions. This is because e.g. Solidity-generated
                // getters don't have this flag yet we want to skip them, and also it may be that a view function will modify a ghost
                // via some hook.
                getAllWritesAndReads(prog, callId).first?.let { !it.isEmpty() } != false
            }.collect(Collectors.toMap({ it.first }, { it.second })).also {
                logger.info { "bmc on ${it.size} functions" }
            }.toSortedMap(compareBy<ContractFunction> { it.abiWithContractStr() })


        val funcWritesAndReads = compiledFuncs.mapValues { (_, progAndCallId) ->
            getAllWritesAndReads(progAndCallId.first, progAndCallId.second)
        }

        funcWrites = funcWritesAndReads.mapValues { (_, writesAndReads) -> writesAndReads.first }

        funcReads = funcWritesAndReads.mapValues { (_, writesAndReads) -> writesAndReads.second }

        BoundedModelCheckerFilters.init(
            cvl,
            scene,
            compiler,
            compiledFuncs,
            funcReads,
            funcWrites,
            invProgs.mapValues { (_, progs) -> progs.assert }
        )
    }

    /**
     * Provided a sequence of functions [funcsList], generate a program that calls them in order:
     * `constructor->(funcsList[0][0]|...|funcsList[0][i_0])->...->(funcsList[n][0]|...|funcsList[n][i_n])`.
     * In words, for each index `0<=k<=funcsList.size` create a "dispatch" on the functions in `funcsList[k]`, and then
     * call these dispatchers one after the other.
     *
     * In order to help the solvers, before each such dispatch we insert an `assume` of the invariant ([InvariantPrograms.assume]).
     *
     * At the end of the sequence we postpend an assertion of the invariant ([InvariantPrograms.assert]).
     *
     * We also prepend to the program setting the [INV_N] ghost to the value corresponding to this invariant ([InvariantPrograms.invN]).
     */
    private fun generateProgForSequence(
        funcsList: List<List<ContractFunction>>,
        invProgs: InvariantPrograms
    ): CoreTACProgram {
        if (funcsList.isEmpty()) {
            // Constructor only case
            val prog = initializationProg andThen setupFunctionProg andThen invProgs.params andThen invProgs.assert
            return prog
        }

        val functionCallsProg = funcsList.foldIndexed(
            initializationProg andThen setupFunctionProg andThen invProgs.invN andThen invProgs.params
        ) { idx, outerAcc, contractFunctions ->
            if (contractFunctions.isEmpty()) {
                // the constructor only case
                return@foldIndexed outerAcc
            }
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
                val funcProg = compiledFuncs[contractFunction]!!.first.copyFunction(addCallId0Sink = true)

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
            val assumeInvProg = (invProgs.params andThen invProgs.assume).copyFunction()
            outerAcc andThen assumeInvProg andThen newDispatch
        }

        return (functionCallsProg andThen invProgs.assert).copy(
            name = "${invProgs.id}: " + funcsList.sequenceStr()
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

    inner class InvariantPrograms private constructor(
        val id: String,
        val params: CoreTACProgram,
        val assume: CoreTACProgram,
        val assert: CoreTACProgram,
        val invN: CoreTACProgram
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
            invN = invariants[inv]!!.let { invN ->
                compiler.compileCommands(
                    listOf(
                        CVLCmd.Simple.Definition(
                            inv.range,
                            null,
                            listOf(CVLLhs.Id(
                                inv.range,
                                INV_N,
                                CVLExpTag(inv.scope, CVLType.PureCVLType.Primitive.Mathint, inv.range)
                            )),
                            CVLExp.Constant.NumberLit(
                                invN.toBigInteger(),
                                CVLExpTag(inv.scope, CVLType.PureCVLType.Primitive.NumberLiteral(invN.toBigInteger()), inv.range)
                            ),
                            inv.scope
                        )
                    ), "$INV_N assignment"
                ).toCore(scene)
            }
        )
    }

    private suspend fun checkInv(inv: CVLInvariant, invProgs: InvariantPrograms): List<RuleCheckResult> {
        val funcsForThisInvariant = CalculateMethodParamFilters(mainContract, cvl.importedFuncs.keys.toList(), cvl.symbolTable)
            .calculate(listOf(IRule.createDummyRule("dummy").copy(methodParamFilters = inv.methodParamFilters)))
            .resultOrThrow { errors ->
                errors.forEach { error ->
                    CVTAlertReporter.reportAlert(CVTAlertType.CVL, CVTAlertSeverity.ERROR, error.location as? TreeViewLocation, error.message, null)
                }
                IllegalArgumentException("Calculating the method param filters failed")
            }
            .second.values.single().values.singleOrNull()
            ?.map { method ->
                compiledFuncs.findEntry { func, _ -> method.getABIString() == func.methodSignature.computeCanonicalSignature(PrintingContext(false)) }
                    ?: error("Couldn't find $method in the compiled funcs")
            }?.map { it.first } ?: compiledFuncs.keys.toList()

        val nSatResults = AtomicInteger(0)
        val nSequencesChecked = AtomicInteger(0)
        val failLimit = Config.BoundedModelCheckingFailureLimit.get()

        val invRule = IRule.createDummyRule(inv.id).copy(ruleType = SpecType.Single.BMC.Invariant, range = inv.range).also {
            treeViewReporter.addTopLevelRule(it)
        }

        val rangeRules = ConcurrentHashMap<Int, CVLSingleRule>()
        fun getRangeRule(len: Int) =
            rangeRules.computeIfAbsent(len) {
                invRule.copy(
                    ruleIdentifier = invRule.ruleIdentifier.freshDerivedIdentifier("Range $len"),
                    ruleType = SpecType.Single.BMC.Range(len)
                ).also {
                    treeViewReporter.registerSubruleOf(
                        it,
                        invRule,
                        funcsForThisInvariant.size.toBigInteger().pow(len).toInt() // Total theoretical number of sequences of this length
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
            parentFuncs: TreapList<ContractFunction>
        ): TreapList<out RuleCheckResult> {
            if (failLimit > 0 && nSatResults.get() >= failLimit) {
                // Hit the max number of errors that should be found. Bail out
                return treapListOf()
            }

            val allLastFuncsToFailedFilters = funcsForThisInvariant.associateWith { func ->
                BoundedModelCheckerFilters.filter(parentFuncs + func, inv, funcReads, funcWrites)
            }

            val sequenceLen = parentFuncs.size + 1
            val rangeRule = getRangeRule(sequenceLen)
            val lastFuncs = allLastFuncsToFailedFilters.filterValues { it == null }.keys.toList()

            // The last element of the sequence will be a dispatch on the functions in lastFuncs, so the functions that
            // got filtered out correspond to sequences that are skipped, so count them as done.
            treeViewReporter.updateFinishedChildren(rangeRule, funcsForThisInvariant.size - lastFuncs.size)

            val res = if (lastFuncs.isNotEmpty()) {
                val seqRule = parentRule.copy(
                    ruleIdentifier = parentRule.ruleIdentifier.freshDerivedIdentifier(lastFuncs.dispatchStr()),
                    ruleType = SpecType.Single.BMC.Sequence(inv)
                )
                treeViewReporter.registerSubruleOf(seqRule, rangeRule)

                val prog = generateProgForSequence(
                    parentFuncs.map { listOf(it) } + listOf(lastFuncs),
                    invProgs
                )

                treeViewReporter.signalStart(seqRule)
                val res = checkProg(prog, seqRule)

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
                            check(sighash != null) { "Expected to find an integer-typed selectorVar variable" }
                            lastFuncs.find { it.sigHash == sighash.value }
                                ?: error("the selectorVar's value should have been one of the last functions' sighash")
                        }

                    // Found it :) Now replace the dispatch list from the identifier of the rule with theFunc.
                    treeViewReporter.updateDisplayName(seqRule, theFunc.abiWithContractStr())
                }

                treeViewReporter.signalEnd(seqRule, res)

                // This seqRule accounted for lastFuncs.size sequences, update the finished children with this info
                treeViewReporter.updateFinishedChildren(rangeRule, lastFuncs.size)

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

            val childFuncs = allLastFuncsToFailedFilters.filterValues { it == null || !it.appliesToChildren }.keys

            // OK, this is a little confusing.
            // We want to count how many sequences are going to be skipped for each range due to us not calling checkRecursive
            // on the sequence parentFuncs + child.
            // So for each range r larger than the one we're currently checking (which we already updated before) we will skip
            // funcs^(r-seqLen) for each of the children we aren't calling the recursive function on.
            for (r in sequenceLen+1..maxSequenceLen) {
                treeViewReporter.updateFinishedChildren(
                    getRangeRule(r),
                    (funcsForThisInvariant.size - childFuncs.size) * funcsForThisInvariant.size.toBigInteger().pow(r - sequenceLen).toInt()
                )
            }

            return (listOfNotNull(res) + childFuncs.parallelMapOrdered { _, func ->
                val funcs = parentFuncs + func
                val childProg = generateProgForSequence(funcs.map { listOf(it) }, invProgs)
                val childRule = parentRule.copy(ruleIdentifier = parentRule.ruleIdentifier.freshDerivedIdentifier(func.abiWithContractStr()))

                if (isVacuous(childProg, childRule)) {
                    // similar to the logic in the previous call to updateFinishedChildren, just we're only talking about one child here.
                    for (r in sequenceLen+1..maxSequenceLen) {
                        treeViewReporter.updateFinishedChildren(
                            getRangeRule(r),
                            funcsForThisInvariant.size.toBigInteger().pow(r - sequenceLen).toInt()
                        )
                    }
                    treapListOf()
                } else {
                    checkRecursive(childRule, funcs)
                }
            }.flatten()).toTreapList()
        }

        val constructorsRule = IRule.createDummyRule("").copy(ruleIdentifier = invRule.ruleIdentifier.freshDerivedIdentifier("Initial State"), ruleType = SpecType.Single.BMC.Range(0)).also {
            treeViewReporter.registerSubruleOf(it, invRule)
        }

        val constructorProg = generateProgForSequence(listOf(), invProgs)

        val allResults = if (!isVacuous(constructorProg, constructorsRule, registerTreeView = true)) {
            treeViewReporter.signalStart(constructorsRule)
            val constructorRes = checkProg(constructorProg, constructorsRule)
            treeViewReporter.signalEnd(constructorsRule, constructorRes)
            nSequencesChecked.getAndIncrement()
            if (constructorRes is RuleCheckResult.Single && constructorRes.result == SolverResult.SAT) {
                nSatResults.getAndIncrement()
            }

            listOf(constructorRes) + if (maxSequenceLen > 0) {
                checkRecursive(constructorsRule, treapListOf())
            } else {
                listOf()
            }
        } else {
            logger.warn { "The invariant ${inv.id} is vacuous even when running only on the constructors!" }
            listOf()
        }

        treeViewReporter.hotUpdate()
        logger.info { "Invariant ${inv.id}: Ran a total of $nSequencesChecked sequences" }

        return allResults
    }

    suspend fun checkAllInvariants(): List<RuleCheckResult> {
        StatusReporter.freeze()
        val reportingJob = treeViewReporter.startAutoHotUpdate()
        try {
            return invariants.keys
                .parallelMapOrdered { _, inv ->
                    checkInv(inv, invProgs[inv]!!)
                }
                .flatten()
                .also {
                    reporter.hotUpdate(scene)
                }
        } finally {
            treeViewReporter.stopAutoHotUpdate(reportingJob)
        }
    }
}
