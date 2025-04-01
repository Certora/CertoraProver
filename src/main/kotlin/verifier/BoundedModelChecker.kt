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
import datastructures.stdcollections.*
import instrumentation.transformers.InitialCodeInstrumentation
import log.*
import parallel.coroutines.parallelMapOrdered
import report.*
import rules.CompiledRule
import rules.RuleCheckResult
import scene.IScene
import scene.MethodAttribute
import solver.SolverResult
import spec.*
import spec.CVLCompiler.CallIdContext.Companion.toContext
import spec.CalculateMethodParamFilters.Companion.containsMethod
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
        private const val SETUP_FUNC_NAME = "setup"

        fun CoreTACProgram.copyFunction(): CoreTACProgram {
            return this.createCopy(Allocator.getFreshId(Allocator.Id.CALL_ID))
        }

        fun ParametricInstantiation<CVLTACProgram>.toCore(scene: IScene) = this.getAsSimple().transformToCore(scene)
        fun CommandWithRequiredDecls<TACCmd.Simple>.toCore(id: String, scene: IScene) = this.toProg(id, ROOT_CALL_ID.toContext()).asSimple().toCore(scene)

        fun CoreTACProgram.optimize(scene: IScene): CoreTACProgram {
            val optimized = CompiledRule.optimize(scene.toIdentifiers(), this.withCoiOptimizations(false))
            return optimized
        }

        data class StateModificationFootprint(
            private val storage: Set<StorageAnalysisResult.StoragePaths>,
            private val hasTransient: Boolean,
            private val ghosts: Set<String>
        ) {
            fun isEmpty(): Boolean {
                return storage.isEmpty() && !hasTransient && ghosts.isEmpty()
            }

            fun overlaps(other: StateModificationFootprint): Boolean {
                return this.storage.any { it in other.storage } ||
                    (this.hasTransient && other.hasTransient) ||
                    this.ghosts.any { it in other.ghosts }
            }
        }

        /**
         * @return The set of storage paths and ghosts that [f] writes to, or `null` if storage analysis failed. [callId]
         * is used in order to determine which writes are actually within the function and not just in the function call
         * instrumentation code. If `callId == null` then _all_ writes in the program will be collected.
         *
         * Note that for transient storage we just check for any writes, not specific paths.
         */
        fun getAllStorageAndGhostWrites(f: CoreTACProgram, callId: CallId?): StateModificationFootprint? {
            val callStack = InlinedMethodCallStack(f.analysisCache.graph)

            val storageAnalysisFailure = AtomicBoolean(false)
            val writesToTransientStorage = AtomicBoolean(false)
            val writes = f.parallelLtacStream().filter { lcmd ->
                callId == null || callId in callStack.currentCallIds(lcmd.ptr)
            }.mapNotNull { lcmd ->
                when (val cmd = lcmd.cmd) {
                    is TACCmd.Simple.WordStore -> {
                        val base = cmd.base
                        if (TACMeta.STORAGE_KEY in base.meta) {
                            base.meta[TACMeta.STABLE_STORAGE_FAMILY]?.toLeft() ?: run {
                                storageAnalysisFailure.set(true)
                                null
                            }
                        } else if (TACMeta.TRANSIENT_STORAGE_KEY in base.meta) {
                            writesToTransientStorage.set(true)
                            null
                        } else {
                            null
                        }
                    }

                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                        val lhs = cmd.lhs
                        if (TACMeta.STORAGE_KEY in lhs.meta) {
                            lhs.meta[TACMeta.STABLE_STORAGE_FAMILY]?.toLeft() ?: run {
                                storageAnalysisFailure.set(true)
                                null
                            }
                        } else if (TACMeta.TRANSIENT_STORAGE_KEY in lhs.meta) {
                            writesToTransientStorage.set(true)
                            null
                        } else if (TACMeta.CVL_GHOST in lhs.meta) {
                            lhs.meta[TACMeta.CVL_DISPLAY_NAME]?.toRight() ?: error("expected there to be a display name for the ghost. cmd is $cmd. Has only ${lhs.meta}")
                        } else {
                            null
                        }
                    }

                    else -> null
                }
            }.collect(Collectors.toSet())

            if (storageAnalysisFailure.get()) {
                logger.info { "Storage analysis failure for ${f.name} prevents making assumptions on which storage has writes" }
                return null
            }

            val (storageWrites, ghostWrites) = writes.partitionMap { it }
            return StateModificationFootprint(storageWrites.toSet(), writesToTransientStorage.get(), ghostWrites.toSet())
        }

        /**
         * @return The set of storage paths and ghosts that [f] accesses, or `null` if storage analysis failed. [callId]
         * is used in order to determine which accesses are actually within the function and not just in the function call
         * instrumentation code. If `callId == null` then _all_ accesses in the program will be collected.
         *
         * Note that for transient storage we just check for any access, not specific paths.
         */
        fun getAllStorageAndGhostAccesses(f: CoreTACProgram, callId: CallId?): StateModificationFootprint? {
            val callStack = InlinedMethodCallStack(f.AnalysisCache().graph)
            val storageAnalysisFailure = AtomicBoolean(false)
            val hasTransientStorageAccess = AtomicBoolean(false)
            val accesses = f.parallelLtacStream().filter { lcmd ->
                callId == null || callId in callStack.currentCallIds(lcmd.ptr)
            }.flatMap { lcmd ->
                val cmdVars = lcmd.cmd.freeVars()

                cmdVars.mapNotNull { v ->
                    if (TACMeta.STORAGE_KEY in v.meta) {
                        v.meta[TACMeta.STABLE_STORAGE_FAMILY]?.let { return@mapNotNull it.toLeft() }
                            ?: storageAnalysisFailure.set(true)
                        return@mapNotNull null
                    } else if (TACMeta.TRANSIENT_STORAGE_KEY in v.meta) {
                        hasTransientStorageAccess.set(true)
                        return@mapNotNull null
                    } else if (TACMeta.CVL_GHOST in v.meta) {
                        return@mapNotNull v.meta[TACMeta.CVL_DISPLAY_NAME]?.toRight() ?: error("expected there to be a display name for the ghost. var is $v. Has only ${v.meta}")
                    } else {
                        return@mapNotNull null
                    }
                }.stream()
            }.collect(Collectors.toSet())

            if (storageAnalysisFailure.get()) {
                logger.info { "Storage analysis failure for ${f.name} prevents making assumptions on which storage is accessed" }
                return null
            }

            val (storageAccess, ghostAccess) = accesses.partitionMap { it }

            return StateModificationFootprint(storageAccess.toSet(), hasTransientStorageAccess.get(), ghostAccess.toSet())
        }
    }

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
    private val compiledFuncs: Map<ContractFunction, Pair<CoreTACProgram, CallId>>

    private val invProgs: Map<CVLInvariant, InvariantPrograms>

    init {
        val compiler = CVLCompiler(scene, this.cvl, "bmc compiler")

        initializationProg = run {
            val block = withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                val initAxiomCmds = cvl.ghosts
                    .asSequence()
                    .filterIsInstance<CVLGhostWithAxiomsAndOldCopy>()
                    .flatMap { it.axioms }
                    .filterIsInstance<CVLGhostAxiom.Initial>()
                    .map { CVLCmd.Simple.AssumeCmd.Assume(range, it.exp, scope) }
                    .toList()
                    .wrapWithMessageLabel("Assume init_state axioms")

                val invokeConstructors = scene.getNonPrecompiledContracts().flatMapIndexed { i, contract ->
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

            InitialCodeInstrumentation.applySummariesAndGhostHooksAndAxiomsTransformations(
                prog,
                scene,
                cvl,
                IRule.createDummyRule("initialization").copy(ruleType = SpecType.Single.BMC),
                null
            ).optimize(scene)
        }

        setupFunctionProg = run {
            val callSetup = withScopeAndRange(CVLScope.AstScope, Range.Empty()) {
                this@run.cvl.subs.find { it.declarationId == SETUP_FUNC_NAME }?.let {
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
            }?.toCore(scene)?.let { prog ->
                InitialCodeInstrumentation.applySummariesAndGhostHooksAndAxiomsTransformations(prog, scene, cvl, IRule.createDummyRule("$SETUP_FUNC_NAME code"), null)
                    .optimize(scene)
            } ?: CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toCore("dummy", scene)
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

            val preservedList = invariants.filterKeys { inv ->
                inv.proof.preserved.any { (it is CVLPreserved.ExplicitMethod && matchesContractAndNameAndParams(it.methodSignature)) || it is CVLPreserved.Generic }
            }.mapKeys { (inv, _) ->
                val preserved = inv.proof.preserved.find { it is CVLPreserved.ExplicitMethod && matchesContractAndNameAndParams(it.methodSignature) }
                    ?: inv.proof.preserved.filterIsInstance<CVLPreserved.Generic>().single()
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
                listOf(
                    CVLCmd.Simple.Declaration(range, EVMBuiltinTypes.env, envParam(n), scope)
                ) + cvlParams.map { p ->
                    CVLCmd.Simple.Declaration(range, p.type, p.id, scope)
                }
            }.let { compiler.compileCommands(it, "param declarations") }.toCore(scene)

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

            return prog to callId
        }

        compiledFuncs = this.cvl.importedFuncs.values.asSequence()
        .flatten()
            .filterNot { it.methodSignature is UniqueMethod }
            .filter { func ->
                Config.contractChoice.get().let { it.isEmpty() || func.methodSignature.qualifiedMethodName.host.name in it }
            }
            .filter { func ->
                Config.MethodChoices.containsMethod(
                    func.methodSignature.computeCanonicalSignature(PrintingContext(false)),
                    func.methodSignature.qualifiedMethodName.host.name,
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
                val rule = IRule.createDummyRule("rule for $contractFunc").copy(ruleType = SpecType.Single.BMC)

                contractFunc to (InitialCodeInstrumentation.applySummariesAndGhostHooksAndAxiomsTransformations(prog, scene, cvl, rule, null)
                    .optimize(scene) to callId)
            }
            .filter { (_, progAndCallId) ->
                val (prog, callId) = progAndCallId
                // Note the filtering we do ignores the `isView` attribute of the functions. This is because e.g. Solidity-generated
                // getters don't have this flag yet we want to skip them, and also it may be that a view function will modify a ghost
                // via some hook.
                getAllStorageAndGhostWrites(prog, callId)?.let { !it.isEmpty() } != false
            }.collect(Collectors.toMap({ it.first }, { it.second })).also {
                logger.info { "bmc on ${it.size} functions" }
            }

        invProgs = invariants.mapValues { (inv, _) ->
            InvariantPrograms(inv, compiler)
        }

        BoundedModelCheckerFilters.init(cvl, scene, compiler, compiledFuncs, invProgs.mapValues { (_, progs) -> progs.assert }, treeViewReporter)
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
            // Constructor only case, we don't want to call the setup function or anything
            return initializationProg andThen invProgs.params andThen invProgs.assert
        }

        val functionCallsProg = funcsList.foldIndexed(
            initializationProg andThen setupFunctionProg andThen invProgs.invN andThen invProgs.params
        ) { idx, outerAcc, contractFunctions ->
            if (contractFunctions.isEmpty()) {
                // the constructor only case
                return@foldIndexed outerAcc
            }
            val selectorVar = TACKeyword.TMP(Tag.Int, "SelectorVar$idx")
            val dispatchProg = CommandWithRequiredDecls(TACCmd.Simple.AssumeCmd(TACSymbol.False)).toCore("dispatch", scene)
            val newDispatch = contractFunctions.fold(dispatchProg) { acc, contractFunction ->
                val condVar = TACKeyword.TMP(Tag.Bool, "selectorCondVar")
                val condProg = CommandWithRequiredDecls(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = condVar,
                        rhs = TACExprFactUntyped { selectorVar.asSym() eq contractFunction.sigHash!!.asTACExpr() }
                    ),
                    setOf(condVar, selectorVar)
                ).toCore("condProg", scene)
                val funcProg = compiledFuncs[contractFunction]!!.first.copyFunction()
                    .addSinkMainCall(listOf(TACCmd.Simple.NopCmd)).first // This is so the funcProg ends with a callId=0 block - otherwise TAC dumps look weird.

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
            val assumeInvProg = invProgs.assume.copyFunction()
            outerAcc andThen assumeInvProg andThen newDispatch
        }

        return functionCallsProg andThen invProgs.assert
    }

    private suspend fun checkProg(prog: CoreTACProgram, rule: CVLSingleRule): RuleCheckResult.Leaf {
        val compiledRule = CompiledRule.create(rule, prog.withCoiOptimizations(true), treeViewReporter.liveStatsReporter)
        return compiledRule
            .check(scene.toIdentifiers(), nonLocalOptimizationsOnly = true)
            .toCheckResult(scene, compiledRule)
            .getOrElse { RuleCheckResult.Error(compiledRule.rule, it) }
    }

    private suspend fun isVacuous(prog: CoreTACProgram, rule: CVLSingleRule): Boolean {
        val vacuityCheck = prog.appendToSinks(
            CommandWithRequiredDecls(
                TACCmd.Simple.AssertCmd(TACSymbol.False, "not vacuous")
            )
        )

        val thisRule = IRule.createDummyRule("").copy(
            ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier("vacuity"),
            ruleGenerationMeta = SingleRuleGenerationMeta.WithSanity(SingleRuleGenerationMeta.Sanity.BASIC_SANITY),
            ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck(rule)
        )
        treeViewReporter.registerSubruleOf(thisRule, rule)
        treeViewReporter.signalStart(thisRule)
        val res = checkProg(vacuityCheck, thisRule)

        treeViewReporter.signalEnd(thisRule, res)
        return res is RuleCheckResult.Single && res.result == SolverResult.UNSAT
    }

    inner class InvariantPrograms private constructor(
        val params: CoreTACProgram,
        val assume: CoreTACProgram,
        val assert: CoreTACProgram,
        val invN: CoreTACProgram
    ) {
        constructor(inv: CVLInvariant, compiler: CVLCompiler) : this(
            params = compiler.compileCommands(
                inv.params.map { param ->
                    CVLCmd.Simple.Declaration(
                        inv.range,
                        param.type,
                        param.id,
                        inv.scope
                    )
                }.wrapWithMessageLabel("Declare invariant parameters"),
                "the inv params"
            ).toCore(scene).let {
                if (!it.isEmptyCode()) {
                    it.optimize(scene)
                } else {
                    it
                }
            },
            assume = compiler.compileCommands(
                CVLCmd.Simple.AssumeCmd.Assume(
                    inv.range,
                    inv.exp,
                    inv.scope
                ).wrapWithMessageLabel("Assume invariant"),
                "the assumption of ${inv.id}"
            ).toCore(scene).optimize(scene),
            assert = compiler.compileCommands(
                CVLCmd.Simple.Assert(
                    inv.range,
                    inv.exp,
                    "invariant assertion",
                    inv.scope
                ).wrapWithMessageLabel("assert invariant"),
                "the assert of ${inv.id}"
            ).toCore(scene).optimize(scene),
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

        val nViolationsFound = AtomicInteger(0)
        val nSequencesChecked = AtomicInteger(0)
        val failLimit = Config.BoundedModelCheckingFailureLimit.get()
        suspend fun checkRecursive(
            parentRule: CVLSingleRule,
            parentFuncs: TreapList<ContractFunction>
        ): TreapList<out RuleCheckResult> {
            if (failLimit > 0 && nViolationsFound.get() >= failLimit) {
                // Hit the max number of errors that should be found. Bail out
                treeViewReporter.signalSkip(parentRule)
                return treapListOf()
            }

            val failedFilter = if (parentFuncs.isNotEmpty()) {
                BoundedModelCheckerFilters.passes(parentFuncs.map { listOf(it) }, inv)
            } else {
                null
            }

            val (parentProg, res) = if (failedFilter != null) {
                treeViewReporter.signalSkip(parentRule)
                CVTAlertReporter.reportAlert(
                    CVTAlertType.BMC,
                    CVTAlertSeverity.INFO,
                    null,
                    "Skipping `${parentRule.ruleIdentifier}` because ${failedFilter.message}",
                    null
                )
                if (failedFilter.appliesToChildren) {
                    // The filter that didn't pass applies to all sequences with this sequence as their header, so don't
                    // bother starting any child rules.
                    return treapListOf()
                }
                null to null
            } else {
                val parentProg = generateProgForSequence(
                    parentFuncs.map { listOf(it) },
                    invProgs
                ).copy(name = parentRule.ruleIdentifier.toString())

                StatusReporter.register(parentRule)

                treeViewReporter.signalStart(parentRule)
                reporter.signalStart(parentRule)
                val res = checkProg(parentProg, parentRule)
                treeViewReporter.signalEnd(parentRule, res)
                val nCheckedSequences = nSequencesChecked.getAndIncrement()
                if (nCheckedSequences % 100 == 0) {
                    treeViewReporter.hotUpdate()
                    logger.info { "Ran $nSequencesChecked sequences" }
                }

                if (res is RuleCheckResult.Single && res.result == SolverResult.SAT) {
                    nViolationsFound.getAndIncrement()
                    return treapListOf(res)
                }
                parentProg to res
            }

            if (parentFuncs.size == Config.BoundedModelChecking.get()) {
                // Recursion end condition. Note that in this case there's no point in running the vacuity check
                // even if the sequence was UNSAT because there won't be any child sequences anyway.
                return res?.let { treapListOf(it) }.orEmpty()
            }

            if (res is RuleCheckResult.Single && res.result == SolverResult.UNSAT && isVacuous(parentProg!!, parentRule)) {
                val msg = "The sequence $parentFuncs is vacuous"
                logger.info { msg }
                CVTAlertReporter.reportAlert(
                    CVTAlertType.BMC,
                    CVTAlertSeverity.WARNING,
                    null,
                    msg,
                    "All sequences with this header will be skipped"
                )
                return treapListOf(res)
            }

            return funcsForThisInvariant.parallelMapOrdered { _, func ->
                val funcs = parentFuncs + func
                val rule = IRule.createDummyRule("").copy(
                    ruleIdentifier = parentRule.ruleIdentifier.freshDerivedIdentifier(func.toString()),
                    ruleType = SpecType.Single.BMC,
                    range = func.getMethodInfo().sourceSegment?.range ?: Range.Empty()
                ).also {
                    treeViewReporter.registerSubruleOf(it, parentRule)
                }
                checkRecursive(rule, funcs)
            }.fold(res?.let { treapListOf(it as RuleCheckResult) }.orEmpty()) { acc, m -> acc + m }
        }

        val invRule = IRule.createDummyRule(inv.id).copy(ruleType = SpecType.Single.BMC, range = inv.range).also {
            treeViewReporter.addTopLevelRule(it)
        }

        val constructorsRule = IRule.createDummyRule("").copy(ruleIdentifier = invRule.ruleIdentifier.freshDerivedIdentifier("constructors"), ruleType = SpecType.Single.BMC).also {
            treeViewReporter.registerSubruleOf(it, invRule)
        }

        val allResults = checkRecursive(constructorsRule, treapListOf())

        treeViewReporter.hotUpdate()
        logger.info { "Ran a total of $nSequencesChecked sequences" }

        return allResults
    }

    suspend fun checkAllInvariants(): List<RuleCheckResult> {
        return invariants.keys
            .parallelMapOrdered { _, inv ->
                checkInv(inv, invProgs[inv]!!)
            }
            .flatten()
            .also {
                StatusReporter.freeze()
                reporter.feedReporter(it, scene)
        }
    }
}
