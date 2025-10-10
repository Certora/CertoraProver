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

@file:UseSerializers(BigIntegerSerializer::class)
@file:Suppress("DEPRECATION")

package spec

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import analysis.*
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.EthereumVariables.extcodesize
import analysis.EthereumVariables.setLastReverted
import analysis.storage.DisplayPath
import analysis.storage.singleDisplayPathOrNull
import bridge.EVMExternalMethodInfo
import bridge.SourceLanguage
import com.certora.collect.*
import config.Config
import config.OUTPUT_NAME_DELIMITER
import datastructures.stdcollections.*
import evm.*
import instrumentation.ImmutableInstrumenter
import instrumentation.transformers.CVLTACProgramBlockCallIdRemapper
import kotlinx.serialization.UseSerializers
import log.*
import report.calltrace.CVLReportLabel
import report.calltrace.printer.StackEntry
import rules.genericrulecheckers.BuiltInRuleCustomChecker
import scene.*
import spec.CVLCompiler.CallIdContext.Companion.defaultBaseCallId
import spec.cvlast.*
import spec.cvlast.CVLScope.Companion.AstScope
import spec.cvlast.transformations.GhostUseSubstitutor
import spec.cvlast.transformations.VariableSubstitutor
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.CVLTypeTypeChecker
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import spec.cvlast.typedescriptors.VMValueTypeDescriptor
import spec.rules.CVLSingleRule
import spec.rules.ICVLRule
import statistics.ElapsedTimeStats
import statistics.toTimeTag
import tac.*
import tac.NBId.Companion.ROOT_CALL_ID
import utils.*
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.safeForce
import vc.data.*
import vc.data.ParametricInstantiation.Companion.andThen
import vc.data.ParametricInstantiation.Companion.commute
import vc.data.ParametricInstantiation.Companion.getSimple
import vc.data.ParametricInstantiation.Companion.toInstantiation
import vc.data.ParametricInstantiation.Companion.toSimple
import vc.data.ParametricMethodInstantiatedCode.addSink
import vc.data.ParametricMethodInstantiatedCode.removeMethodParamHavocs
import vc.data.TACCmd.Simple.AnnotationCmd.Companion.toAnnotation
import vc.data.TACMeta.BIT_WIDTH
import vc.data.TACMeta.CVL_ASSUME_INVARIANT_CMD_END
import vc.data.TACMeta.CVL_ASSUME_INVARIANT_CMD_START
import vc.data.TACMeta.CVL_DEF_SITE
import vc.data.TACMeta.CVL_DISPLAY_NAME
import vc.data.TACMeta.CVL_ASSUME_INVARIANT_TARGET
import vc.data.TACMeta.CVL_EXP
import vc.data.TACMeta.CVL_GHOST
import vc.data.TACMeta.CVL_LABEL_END
import vc.data.TACMeta.CVL_LABEL_START
import vc.data.TACMeta.CVL_LABEL_START_ID
import vc.data.TACMeta.CVL_STRUCT_PATH
import vc.data.TACMeta.CVL_TYPE
import vc.data.TACMeta.CVL_VAR
import vc.data.TACMeta.IGNORE_IN_CALLTRACE
import vc.data.TACMeta.RESET_STORAGE
import vc.data.TACMeta.SATISFY_ID
import vc.data.TACMeta.SCALARIZATION_SORT
import vc.data.tacexprutil.ExprUnfolder
import verifier.ContractUtils.recordAggregatedTransformationStats
import java.io.Serializable
import java.math.BigInteger

private val logger = Logger(LoggerTypes.SPEC)

private val txf = TACExprFactUntyped

@KSerializable
@Treapable
data class ReturnValuePayload(val syms: List<TACSymbol.Var>, val label: CVLReportLabel.Return) : Serializable

/**
 * Transient annotation which indicates a return site and the variable which holds the return value
 * of a [CVLFunction] which has been called.
 */
val RETURN_VALUE = MetaKey<ReturnValuePayload>("cvl.return.value")

class CVLCompiler(
    val scene: IScene,
    val cvl: CVL,
    val ruleName: String,
    globalScope: Map<String, TACSymbol.Var> = mapOf()
) {
    interface CallIdContext {
        val baseCallId: CallId

        companion object {
            const val defaultBaseCallId = TACSymbol.Var.DEFAULT_INDEX
            fun CallId.toContext() = object : CallIdContext {
                override val baseCallId: CallId
                    get() = this@toContext

            }
        }

        // Allocator.getNBId() should not be called directly from the CVLCompiler and its related classes
        fun newBlockId() = Allocator.getNBId().copy(calleeIdx = baseCallId)
    }

    private object RootCallIdContext : CallIdContext {
        override val baseCallId: CallId
            get() = defaultBaseCallId
    }

    /**
     * @property baseCallId The default call ID to use for all block IDs generated during compilation
     * @property methodInstantiations A map from a tac variable name to its corresponding set of instantiations
     */
    data class CompilationEnvironment(
        override val baseCallId: CallId = CallIdContext.defaultBaseCallId,
        val methodInstantiations: Map<TACSymbol.Var, Set<ITACMethod>> = mapOf()
    ) : CallIdContext

    private fun ContractReference.toName() = when (this) {
        is SolidityContract -> this
        CurrentContract -> cvl.primaryContract
        AllContracts -> `impossible!`
    }

    val myInstanceId = scene.getContract(cvl.primaryContract).instanceId
    val myAddress = scene.getContract(cvl.primaryContract).addressSym

    private val symbolTable = cvl.symbolTable

    private val allocatedTACSymbols = TACSymbolAllocation()

    private val allGhosts = this.cvl.ghosts.toMutableList()


    init {
        globalScope.forEach { name, sym ->
            allocatedTACSymbols.extendGlobal(name, sym)
        }

        fun addContract(name: String) {
            val ty = symbolTable.lookUpNonFunctionLikeSymbol(name, cvl.astScope)
                ?.getCVLTypeOrNull()!!
            val v = TACSymbol.Var(
                name,
                ty.typeToTag()
            ).toUnique()
                .withMeta(CVL_VAR, true)
                .withMeta(CVL_DISPLAY_NAME, name)
                .withMeta(CVL_TYPE, ty as CVLType.PureCVLType)
            allocatedTACSymbols.extendGlobal(name, v)
        }

        // Add  "currentContract" as a global symbol
        addContract(CVLKeywords.CURRENT_CONTRACT)
        cvl.importedContracts.forEach { c ->
            // each imported contract binds a global variable which is an address
            addContract(c.solidityContractVarId)
        }

        allGhosts.forEach { ghost ->
            when (ghost) {
                is CVLGhostDeclaration.Variable -> {
                    val name = ghost.id
                    val tag = ghost.type.toTag()
                    val allocatedVar = TACSymbol.Var(name, tag)
                        .toUnique()
                        .withMeta(ghostMeta(ghost.id, ghost.type))
                    allocatedTACSymbols.extendGlobal(name, allocatedVar)
                }

                is CVLGhostDeclaration.Sum -> {
                    val name = ghost.id
                    val allocatedVar = TACSymbol.Var(name, ghost.type.toTag())
                        .toUnique()
                        .withMeta(CVL_TYPE, ghost.type)
                    allocatedTACSymbols.extendGlobal(name, allocatedVar)
                }

                is CVLGhostDeclaration.Function -> Unit // someday I would like us to manage these with the allocatedTACSymbols too
            }
        }
    }

    private fun ghostMeta(name: String, type: CVLType.PureCVLType) =
        MetaMap(CVL_GHOST)
            .plus(CVL_VAR to true)
            .plus(CVL_DISPLAY_NAME to name)
            .plus(CVL_TYPE to type)

    private fun ghostToTACUF(ghost: CVLGhostDeclaration, allocatedTACSymbols: TACSymbolAllocation): FunctionInScope.UF =
        FunctionInScope.UF(
            // we assume ghost variables are allocated while ghost functions are not
            name = when (ghost) {
                is CVLGhostDeclaration.Variable,
                is CVLGhostDeclaration.Sum -> allocatedTACSymbols.get(ghost.id).namePrefix // .smtRep()?
                is CVLGhostDeclaration.Function -> ghost.id
            },
            paramSorts = ghost.paramTypes.map { type ->
                // this should actually probably be as Primitive or something since ghosts cannot get structs as params
                type.toTag()
            },
            returnSort = ghost.resultType.toTag(),
            attribute = if (ghost is CVLGhostWithAxiomsAndOldCopy && ghost.oldCopy) {
                UFAttribute.GhostOld
            } else {
                UFAttribute.Ghost
            },
            cvlResultType = ghost.resultType,
            cvlParamTypes = ghost.paramTypes,
            declarationName = ghost.id,
            persistent = ghost.persistent
        )

    private fun buildTACSymbolTable(
        sorts: Collection<CVLType.PureCVLType.Sort>,
        ghosts: Collection<CVLGhostDeclaration>,
        structs: Collection<CVLType.PureCVLType.Struct>,
        allocatedTACSymbols: TACSymbolAllocation
    ): TACSymbolTable =
        TACSymbolTable(
            (sorts.map { sort -> Tag.UserDefined.UninterpretedSort(sort.name) } +
                structs.filter { struct ->
                    // Filter out any struct types that we don't actually
                    // support (e.g. structs containing a dynamic array)
                    CVLTypeTypeChecker(symbolTable).typeCheck(struct, Range.Empty(), AstScope).resultOrNull() != null
                }.map { structType -> structType.toTag() }
                ).toSet(),
            // CERT-5663 we pass in here "allGhosts" even when they're not needed for the program.
            // So don't try to get a UF for them, and worst case, we'll get the failure later
            // citating from ghostToTACUF: "we assume ghost variables are allocated while ghost functions are not"
            ghosts.filter { (it !is CVLGhostDeclaration.Variable && it !is CVLGhostDeclaration.Sum) || allocatedTACSymbols.isAllocated(it.id) }
                .map { ghost -> ghostToTACUF(ghost, allocatedTACSymbols) }.toSet(),
            tagsBuilder<TACSymbol.Var>().let {
                allocatedTACSymbols.getGlobalScope().map { (_, v) ->
                    it[v] = v.tag
                }
                it.build()
            },
            allocatedTACSymbols.getGlobalScope()
        )

    var tacSymbolTable = buildTACSymbolTable(
        this.cvl.sorts.map { sortDecl -> sortDecl.sort },
        this.allGhosts,
        this.symbolTable.getAllStructTypes().mapTo(mutableSetOf()) { (_, struct) ->
            struct
        }.let(EVMBuiltinTypes::populateTACSymbolTable),
        allocatedTACSymbols
    )

    fun buildUFAxioms(): UfAxioms =
        UfAxioms(
            allGhosts.associate { ghost ->
                val uf = ghostToTACUF(ghost, allocatedTACSymbols)
                val axioms = (ghost as? CVLGhostWithAxiomsAndOldCopy)?.axioms?.mapNotNull { axiom ->
                    when (axiom) {
                        is CVLGhostAxiom.Always -> {
                            val newExp = CVLExpressionCompiler(this, allocatedTACSymbols, CompilationEnvironment())
                                .compileCvlExpToSingleTACExp(axiom.exp, allocatedTACSymbols.nestedScope())
                                ?.first
                                ?: CVLExpToTACExpr(this).compileToSimpleTacExpr(
                                    axiom.exp,
                                    allocatedTACSymbols.nestedScope(),
                                    env = CompilationEnvironment(methodInstantiations = mapOf())
                                ).getAsSimple().let {
                                    tacSymbolTable = tacSymbolTable.mergeDecls(it.newDecls)
                                    it.exp
                                }
                            TACAxiom(newExp)
                        }
                        /** The [CVLGhostAxiom.Initial] type is only used for `invariant` rules. It is treated in
                        [GenerateRulesForInvariantsAndEnvFree] and ignored here. */
                        is CVLGhostAxiom.Initial -> null
                    }
                } ?: listOf()
                uf to axioms
            }
        )

    fun nonRevertingAssumptionsToPostpend(env: CompilationEnvironment) = CommandWithRequiredDecls(listOf(
        TACCmd.Simple.AssumeNotCmd(CVLKeywords.lastReverted.toVar()),
        TACCmd.CVL.CopyBlockchainState(
            lhs = CVLKeywords.lastStorage.toVar(),
            meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE)
        )
    ),
        setOf(CVLKeywords.lastReverted.toVar(),
            CVLKeywords.lastStorage.toVar())).toProg("safe return", env)

    fun revertingAssumptionsToPostpend(backup: TACSymbol.Var, env: CompilationEnvironment): CVLTACProgram {
        val setLastStorageNode =
            CommandWithRequiredDecls(listOf(TACCmd.CVL.CopyBlockchainState(CVLKeywords.lastStorage.toVar(), meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE))),
                CVLKeywords.lastStorage.toVar()).toProg("join point", env)
        val revertNode = CommandWithRequiredDecls(listOf(
            TACCmd.CVL.SetBlockchainState(backup, meta = MetaMap(TACMeta.REVERT_MANAGEMENT)),
            TACCmd.Simple.JumpCmd(setLastStorageNode.getStartingBlock())
        ), backup).toProg("revert path", env)
        val successNode = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.JumpCmd(setLastStorageNode.getStartingBlock())
        )).toProg("happy path", env)
        val revertCond = CVLReservedVariables.certoraCond.toVar(Tag.Bool).toUnique("!")
        val setup = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = revertCond,
                rhs = CVLKeywords.lastReverted.toVar().asSym()
            )
        ), setOf(CVLKeywords.lastReverted.toVar(), revertCond
        )
        ).toProg("revert check", env)
        return mergeCodes(
            mergeIfCodes(
                setup,
                jumpiCmd = TACCmd.Simple.JumpiCmd(
                    dst = revertNode.getStartingBlock(),
                    elseDst = successNode.getStartingBlock(),
                    cond = revertCond
                ),
                thenCode = revertNode,
                elseCode = successNode
            ), setLastStorageNode
        )
    }

    // if we ever want to change the factory now we can do it in one place
    // for example maybe we will want to change this to a type checked factory some day,
    // currently I don't because bifs are all messed up (i.e. we lazily load them
    // into the symbol table)
    val exprFact get() = txf

    fun codeFromListOfCommands(
        rootId: NBId,
        cmds: List<TACCmd.Simple>,
        name: String
    ) =
        codeFromListOfCommandsWithTypeInfo(rootId, cmds, name, tacSymbolTable)

    fun codeFromCommandVarWithDecls(
        rootId: NBId,
        cmdsWithDecls: CommandWithRequiredDecls<TACCmd.Spec>,
        name: String
    ) =
        CVLTACProgram(
            listOf(
                Pair(
                    rootId,
                    cmdsWithDecls.cmds.takeUnless { it.isEmpty() } ?: listOf(TACCmd.Simple.NopCmd))).toMap(),
            BlockGraph(rootId to treapSetOf()),
            name,
            tacSymbolTable.mergeDecls(cmdsWithDecls.varDecls),
            IProcedural.empty(),
            this::buildUFAxioms
        )

    fun compileContractFunctionInvocation(
        exp: CVLExp.ApplyExp.ContractFunction,
        out: List<CVLParam>,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        check(exp is CVLExp.ApplyExp.ContractFunction.Concrete && exp.tag.annotation is CallResolution) {
            "Cannot use invoke with unknown function symbol ${exp.methodIdWithCallContext}, and " +
                "cannot assign it to $out. Typechecker should have caught this"
        }

        val l = compileConcreteApplication(exp, allocatedTACSymbols, out, compilationEnv = env)
        return l.first
    }

    fun compileAddressFunctionApplication(
        exp: CVLExp.AddressFunctionCallExp,
        out: List<CVLParam>?,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        val contractToFunc = scene.getContracts().mapNotNull { contract ->
            val contractName = if (contract is IClonedContract) {
                scene.getContract(contract.sourceContractId).name
            } else {
                contract.name
            }
            contract `to?` exp.relevantFuncs.find { func -> func.methodSignature.qualifiedMethodName.host.name == contractName }
        }

        // Compile the base address expression
        val (baseExpParam, baseExpVar) = allocatedTACSymbols.generateTransientUniqueCVLParam(symbolTable, "address_base", exp.base.getOrInferPureCVLType())
        val compiledBaseExp = CVLExpressionCompiler(this, allocatedTACSymbols, env).compileExp(baseExpParam, exp.base)

        fun callResolution(func: ContractFunction): CallResolution = if (exp.args.any { it.getCVLType() == CVLType.PureCVLType.VMInternal.RawArgs }) {
            CallResolution.CalldataPassing(func, hasEnv = true)
        } else {
            CallResolution.DirectPassing(func, hasEnv = true)
        }

        /*
         * Create the "disptach list" on this address.
         * Start with the default case, a `require false`. Then construct the dispatching branches "backwards".
         * `assert false` ->
         * `if (addr == contractSym1) func(); else require false;` ->
         * `if (addr == contractSym2) func(); else if (addr == contractSym1) func() else require false;` -> ...
         *
         * NOTE: Notice that we aren't inlining here non-trivial fallbacks of the other contracts in the scene which
         * would make this a little more complete. The reason is that it requires more effort than we want to spend at
         * the time of writing this comment. Specifically, to do this one would need to (along with other stuff) check
         * whether the call is performed with a `calldataarg` or "normal" arguments, and if it's the latter one would
         * need to encode the arguments into a byte array or something in order to invoke a fallback with the arguments.
         */
        val dispatching = contractToFunc.fold(
            CommandWithRequiredDecls(TACCmd.Simple.AssumeCmd(
                false.asTACSymbol(), "dispatching default"
            )).toProg("address function call default", env).toSimple()
        ) { acc, (contract, func) ->
            val compiledFunc = compileConcreteApplication(
                CVLExp.ApplyExp.ContractFunction.Concrete(
                    ConcreteMethod((func.methodSignature as ExternalQualifiedMethodSignature)),
                    exp.args,
                    noRevert = true, // The typechecker disallows address function calls with `@withrevert`
                    exp.storage,
                    false,
                    CVLExpTag(exp.getScope(), exp.getCVLType(), exp.getRangeOrEmpty(), annotation = callResolution(func))
                ),
                allocatedTACSymbols,
                out,
                env,
                contract
            ).first

            val condVar = TACKeyword.TMP(Tag.Bool, "!is_contract_${contract.name}")
            val condCode = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = condVar,
                    rhs = TACExpr.BinRel.Eq(baseExpVar.asSym(), (contract.addressSym as TACSymbol).asSym())
                )
            )).toProg("is address ${contract.name}", env).toSimple()
            ParametricMethodInstantiatedCode.mergeIf(
                condCode,
                TACCmd.Simple.JumpiCmd(
                    dst = compiledFunc.getHead(),
                    elseDst = acc.getHead(),
                    condVar
                ),
                compiledFunc,
                acc
            )
        }

        return ParametricMethodInstantiatedCode.mergeProgs(compiledBaseExp, dispatching)
    }

    /**
     * Compiles a call to the provided [func] with the given [params]. Its output (if any) is ignored.
     *
     * Note that the call uses `lastStorage` i.e. it compiles as `func(params) at lastStorage;`.
     */
    fun compileStandaloneContractFunctionCall(
        func: ContractFunction,
        params: List<CVLParam>,
        noRevert: Boolean
    ): Pair<ParametricInstantiation<CVLTACProgram>, CallId> {
        val env = CompilationEnvironment()
        val methodSig = func.methodSignature
        check(methodSig is ExternalQualifiedMethodSignature) {
            "Expected an ExternalQualifiedMethodSignature, got ${methodSig.javaClass}"
        }
        val emptyRange = Range.Empty()
        val hasEnv = params.isNotEmpty() && params[0].type == EVMBuiltinTypes.env
        val firstParamIdx = if (hasEnv) { 1 } else { 0 }
        val annotation = if (params.size > firstParamIdx && params[firstParamIdx].type == CVLType.PureCVLType.VMInternal.RawArgs) {
            CallResolution.CalldataPassing(func, hasEnv)
        } else {
            CallResolution.DirectPassing(func, hasEnv)
        }

        val concreteExp = CVLExp.ApplyExp.ContractFunction.Concrete(
            ConcreteMethod(methodSig),
            params.map { CVLExp.VariableExp(it.id, CVLExpTag(AstScope, it.type, emptyRange)) },
            noRevert,
            CVLExp.VariableExp(CVLKeywords.lastStorage.keyword, CVLExpTag(AstScope, CVLKeywords.lastStorage.type, emptyRange)),
            false,
            CVLExpTag(AstScope, null, emptyRange, annotation = annotation)
        )
        return compileConcreteApplication(concreteExp, allocatedTACSymbols, null, env)
    }

    fun declareVariables(vars: List<CVLParam>, scope: CVLScope, range: Range, noCallId: Boolean = false): ParametricInstantiation<CVLTACProgram> {
        val compiledVars = vars.map { v ->
            compileDeclarationCmd(
                CVLCmd.Simple.Declaration(
                    range,
                    v.type,
                    v.id,
                    scope
                ),
                v.id,
                allocatedTACSymbols,
                CompilationEnvironment(),
                noCallId
            )
        }
        return ParametricMethodInstantiatedCode.merge(compiledVars, "var decls")
    }

    fun compileCommands(
        cmds: List<CVLCmd>,
        name: String
    ): ParametricInstantiation<CVLTACProgram> {
        val env = CompilationEnvironment()
        return ParametricMethodInstantiatedCode.merge(
            cmds.flatMap { this.wrapCmdWithLabels(it) }.map { compileCommand(it, allocatedTACSymbols, env) },
            name
        )
    }

    fun compileSetStorageState(state: String): ParametricInstantiation<CVLTACProgram> {
        val stateVar = allocatedTACSymbols.get(state, Tag.BlockchainState)
        return CommandWithRequiredDecls(
            TACCmd.CVL.SetBlockchainState(stateVar)
        ).toProg("set state to $state", CompilationEnvironment()).toSimple()
    }

    /*
    Will always be a connected graph with single root in compileStatement
     */
    private fun compileCommand(
        cmd: CVLCmd,
        allocatedTACSymbols: TACSymbolAllocation,
        compilationEnvironment: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {

        // add range info aux function (see below)
        fun CVLTACProgram.addRangeInfo(): CVLTACProgram =
            this.copy(code = this.code.mapValues { (_, cmds) ->
                cmds.map { tacCmd ->
                    when (tacCmd) {
                        is TACCmd.Simple.NopCmd -> tacCmd // NopCmd crashes on withMeta
                        else -> {
                            // The range that is attached to `cmd` is not always very precise (in particular this has
                            //  come up for if/else blocks) -- thus, if there is already a range present, we're keeping
                            //  it.
                            // It might be a good idea to make those ranges more precise in the future, then we might be
                            //  able to drop this case distinction.
                            if (TACMeta.CVL_RANGE in tacCmd.meta) {
                                tacCmd
                            } else {
                                tacCmd.plusMeta(TACMeta.CVL_RANGE, cmd.range)
                            }
                        }
                    }
                }
            })
        // add range info aux function adding CVL range meta info to every result of the compileCommand method
        fun ParametricInstantiation<CVLTACProgram>.addRangeInfo(): ParametricInstantiation<CVLTACProgram> =
            this.copy(withMethodParamInsts = this.withMethodParamInsts.map { mwpi ->
                mwpi.copy(tacCode = mwpi.tacCode.addRangeInfo())
            })

        val name = cmd.javaClass.name
        logger.info { "Compiling $cmd (type cmd ${cmd.javaClass.name}), generating code named $name" }
        return when (cmd) {
            is CVLCmd.Simple.Label -> {
                compileLabelCmd(cmd, name, compilationEnvironment)
            }

            is CVLCmd.Simple.Declaration -> {
                compileDeclarationCmd(cmd, name, allocatedTACSymbols, compilationEnvironment)
            }

            is CVLCmd.Simple.Definition -> { /* assignment cmd */
                compileDefinitionCmd(cmd, allocatedTACSymbols, compilationEnvironment)
            }

            is CVLCmd.Simple.AssumeCmd.Assume -> {
                compileAssumeCmd(cmd, allocatedTACSymbols, compilationEnvironment)
            }

            is CVLCmd.Simple.Assert -> {
                compileAssertCmd(cmd, name, allocatedTACSymbols, compilationEnvironment)
            }

            is CVLCmd.Simple.Satisfy -> {
                compileSatisfyCmd(cmd, allocatedTACSymbols, compilationEnvironment)
            }

            is CVLCmd.Simple.AssumeCmd.AssumeInvariant -> {
                if(Config.RequireInvariantsPreRuleSemantics.get()){
                    compileAssumeInvariantGlobally(cmd, allocatedTACSymbols)
                } else{
                    compileAssumeInvariant(cmd, allocatedTACSymbols)
                }
            }

            is CVLCmd.Composite.If -> {
                compileIfCmd(cmd, allocatedTACSymbols, compilationEnvironment)
            }

            is CVLCmd.Composite.Block -> {
                compileBlockCmd(cmd, name, allocatedTACSymbols, compilationEnvironment)
            }

            is CVLCmd.Simple.Havoc -> {
                compileHavocCmd(cmd, name, allocatedTACSymbols, compilationEnvironment)
            }

            is CVLCmd.Simple.ResetStorage -> {
                compileResetStorageCmd(cmd, name, compilationEnvironment)
            }

            is CVLCmd.Simple.ResetTransientStorage -> {
                compileResetTransientStorageCmd(name, compilationEnvironment)
            }
            is CVLCmd.Simple.Exp -> throw UnsupportedOperationException("Cannot compile expression command $cmd")
            is CVLCmd.Simple.Apply -> when (cmd.exp) {
                is CVLExp.ApplyExp.CVLFunction -> {
                    compileCVLFunctionApplication(
                        cmd.exp as CVLExp.ApplyExp.CVLFunction,
                        listOf(),
                        allocatedTACSymbols,
                        compilationEnvironment
                    )
                }

                is CVLExp.ApplyExp.ContractFunction -> {
                    compileContractFunctionApplicationCmd(
                        cmd.exp as CVLExp.ApplyExp.ContractFunction,
                        allocatedTACSymbols,
                        compilationEnvironment = compilationEnvironment
                    )
                }

                is CVLExp.AddressFunctionCallExp -> {
                    compileAddressFunctionApplication(cmd.exp as CVLExp.AddressFunctionCallExp, null, allocatedTACSymbols, compilationEnvironment)
                }

                is CVLExp.UnresolvedApplyExp ->{
                    check(cmd.exp.tag.annotation == CVLExp.UnresolvedApplyExp.VirtualFunc) {
                        "The only unresolvedApply expressions that make sense here are of virtual functions"
                    }
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "The function `${(cmd.exp as CVLExp.UnresolvedApplyExp).methodId}` was marked `optional` in the spec file, yet this rule tried to inline it (perhaps within some function summary?), so this rule cannot run."
                    )
                }

                else -> error("Type checker should not allow expression ${cmd.exp} inside of a CVLCmd.Simple.Apply")
            }

            is CVLCmd.Simple.Return -> compileReturnCmd(cmd, name, allocatedTACSymbols, compilationEnvironment)

            is CVLCmd.Simple.Revert -> compileRevertCmd(cmd, name, compilationEnvironment)

            is CVLCmd.Simple.Nop -> compileNopCmd(name, compilationEnvironment)
        }.addRangeInfo()
    }

    private fun compileConcreteApplication(
        exp: CVLExp.ApplyExp.ContractFunction.Concrete,
        allocatedTACSymbols: TACSymbolAllocation,
        out: List<CVLParam>?,
        compilationEnv: CompilationEnvironment,
        contract: IContractClass = exp.methodIdWithCallContext.host.toName().let { scene.getContractOrNull(it) ?: error("contract $it not found") }
    ): Pair<ParametricInstantiation<CVLTACProgram>, CallId> {
        val invocationCompiler = CVLInvocationCompiler(this, compilationEnv)
        return when (val ctxt = exp.methodIdWithCallContext) {
            is ConcreteMethod -> {
                val tgt = exp.tag.annotation as CallResolution
                val callee = ctxt.signature
                check(!MethodAttribute.Unique.isUnique(callee.qualifiedMethodName.methodId)) {
                    "Typechecker should mark all 'special' functions (fallback, constructor) as [UniqueMethod], but got $callee"
                }
                val methodBySigHash = contract.getMethodBySigHash(callee.sighashInt?.n ?: error("No sighash for $contract"))
                check(methodBySigHash != null) {
                    "Rules that reference non-existent methods (${callee}) should have been discovered and skipped via the `SkipOptionalRules` pass on the ast"
                }

                val compiler = if (tgt is CallResolution.CalldataPassing) {
                    invocationCompiler.getCalldataInvocationCompiler(exp, allocatedTACSymbols, out, expectEnv = tgt.hasEnv, env = compilationEnv)
                } else {
                    check(tgt is CallResolution.DirectPassing)
                    invocationCompiler.getDirectInvocationCompiler(
                        exp, allocation = allocatedTACSymbols, expectEnv = tgt.hasEnv, outputList = out, env = compilationEnv
                    )
                }
                compiler.generateCode(callee = methodBySigHash) to compiler.getCallId()
            }

            is UniqueMethod -> {
                val compiler = invocationCompiler.getCalldataInvocationCompiler(
                    exp, allocation = allocatedTACSymbols, outputList = out, env = compilationEnv, expectEnv = true
                )
                compiler.generateCode(contract.getMethodByUniqueAttribute(ctxt.attribute)
                    ?: error("unique function ${ctxt.methodId} not found")
                ) to compiler.getCallId()
            }
        }
    }

    private fun compileContractFunctionApplicationCmd(
        exp: CVLExp.ApplyExp.ContractFunction,
        allocatedTACSymbols: TACSymbolAllocation,
        compilationEnvironment: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        val invocationCompiler = CVLInvocationCompiler(this, compilationEnvironment)
        return when (exp) {
            is CVLExp.ApplyExp.ContractFunction.Concrete -> {
                compileConcreteApplication(
                    exp,
                    allocatedTACSymbols,
                    out = null,
                    compilationEnv = compilationEnvironment
                ).first
            }

            is CVLExp.ApplyExp.ContractFunction.Symbolic -> {
                val res = exp.methodIdWithCallContext
                val compiler = invocationCompiler.getCalldataInvocationCompiler(
                    env = compilationEnvironment,
                    allocation = allocatedTACSymbols,
                    outputList = null,
                    i = exp,
                    expectEnv = true
                )

                fun instantiateWith(k: TACSymbol.Var, meth: Collection<ITACMethod>): ParametricInstantiation<CVLTACProgram> {
                    return meth.map {
                        compiler.generateCode(it, k)
                    }.commute()
                }

                when (res) {
                    is Overloading -> {
                        val (_, instantiationName) = allocatedTACSymbols.generateTransientUniqueCVLParam(symbolTable, res.methodId, EVMBuiltinTypes.method)
                        val cand = scene.getContract(res.host.toName()).getDeclaredMethods().filter {
                            it.name == res.methodId
                        }
                        return instantiateWith(instantiationName, cand)
                    }

                    is ParametricMethod -> {
                        val which = allocatedTACSymbols.get(res.methodId)
                        val cands = compilationEnvironment.methodInstantiations[which]
                            ?: this.scene.getContract(res.host.toName()).getStandardMethods()

                        return instantiateWith(which, cands)
                    }
                }
            }
        }
    }

    /**
     * @return [summary] compiled into a [CompiledExpressionSummary]
     * @require no parameter of `summary.exp` has a non-primitive type (enforced by type checker)
     * Must not compile functions with parametric arguments, multiple returns and maybe some other preconditions too...
     */
    fun compileExpressionSummary(
        summary: SpecCallSummary.Exp,
    ): CompiledExpressionSummary {
        // scope to contain the TAC symbols for the variables available in the summary
        // Note: we don't add the method parameters here because the variables are declared by the [VMParamConverter]
        val summaryScope = this.allocatedTACSymbols.nestedScope()

        // declare with(env) parameter
        summary.withClause?.let {
            summaryScope.extendWithCVLVariable(
                name = it.param.id, cvlDefSite = null, cvlType = it.param.type
            )
        }

        val exp = summary.exp

        val callId = Allocator.getFreshId(Allocator.Id.CALL_ID)

        val rets = exp.getOrInferPureCVLType().takeIf { it != CVLType.PureCVLType.Void }?.let { t ->
            if (t is CVLType.PureCVLType.TupleType) {
                t.elements
            } else {
                listOf(t)
            }
        }?.map { t ->
            summaryScope.generateTransientUniqueCVLParam(
                this.symbolTable,
                CVLParam(t, "tmp", Range.Empty())
            ).first
        }

        val code = if (rets == null) {
            check(exp.getOrInferPureCVLType() == CVLType.PureCVLType.Void)
            val compilationEnvironment = CompilationEnvironment(baseCallId = callId)
            when (exp) {
                is CVLExp.ApplyExp.CVLFunction -> compileCVLFunctionApplication(
                    out = listOf(),
                    exp = exp,
                    allocatedTACSymbols = summaryScope,
                    compilationEnvironment = compilationEnvironment
                )

                is CVLExp.ApplyExp.ContractFunction.Concrete -> compileConcreteApplication(
                    exp = exp,
                    allocatedTACSymbols = summaryScope,
                    out = null,
                    compilationEnv = compilationEnvironment
                ).first

                is CVLExp.ApplyExp.CVLBuiltIn -> {
                    CVLExpressionCompiler(this, summaryScope.calleeScope(), compilationEnvironment)
                        .compileCVLBuiltIn(null, exp)
                }

                else -> error("Unexpected void summary type of $exp")
            }
        } else {
            this.compileAssignment(
                rets.map { retV ->
                    CVLLhs.Id(Range.Empty(), retV.id, CVLExpTag.transient(retV.type))
                },
                summaryScope,
                summary.exp,
                CompilationEnvironment(baseCallId = callId)
            )
        }

        return CompiledExpressionSummary(
            outVars = rets.orEmpty(),
            allocatedTACSymbols = summaryScope,
            body = code.getAsSimple().let { remapped ->
                remapped.copy(procedures = remapped.procedures + Procedure(callId, summary))
            },
            summaryName = summary.summaryName,
            callId = callId
        )
    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    fun compileCVLFunctionApplication(
        exp: CVLExp.ApplyExp.CVLFunction,
        out: List<CVLParam>,
        allocatedTACSymbols: TACSymbolAllocation,
        compilationEnvironment: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        val subInfo =
            cvl.symbolTable.lookUpWithMethodIdWithCallContext(exp.methodIdWithCallContext, exp.tag.getCVLScope())
        check(subInfo != null && subInfo.symbolValue is CVLFunction) {
            " type checking should " +
                "ensure a subroutine apply refers to a symbol registered in the symbol table as a subroutine"
        }
        val sub = subInfo.symbolValue as CVLFunction
        val newAllocatedTACSymbols: TACSymbolAllocation = allocatedTACSymbols.calleeScope()
        mutableMapOf<String, Pair<String, Set<ITACMethod>>>()

        sub.params.forEachIndexed { i, param ->
            val name = param.id
            when (val type = param.type) {
                is CVLType.PureCVLType.Struct -> {
                    if (type == EVMBuiltinTypes.method) { // TODO(jtoman): function literals perhaps? this is fine for now probably
                        val arg = exp.args[i]
                        if (arg is CVLExp.VariableExp) {
                            // method variables should be the same everywhere to ensure instantiation works correctly
                            newAllocatedTACSymbols.extend(name,
                                allocatedTACSymbols
                                    .get(arg.id, type.toTag())
                                    // replace the old display name with the new one
                                    .withMeta(CVL_DISPLAY_NAME, param.originalId))
                        } else {
                            throw IllegalStateException("Found an non-trivial argument $arg of method type")
                        }
                    } else {
                        newAllocatedTACSymbols.extendWithCVLVariable(name, type, CVLDefinitionSite.Function(param.range))
                    }
                }

                is CVLType.PureCVLType.Primitive,
                is CVLType.PureCVLType.Enum,
                is CVLType.PureCVLType.UserDefinedValueType,
                CVLType.PureCVLType.VMInternal.RawArgs,
                CVLType.PureCVLType.VMInternal.BlockchainState,
                is CVLType.PureCVLType.DynamicArray,
                is CVLType.PureCVLType.StaticArray,
                is CVLType.PureCVLType.Sort -> {
                    newAllocatedTACSymbols.extendWithCVLVariable(name, type, CVLDefinitionSite.Function(param.range))
                }

                is CVLType.PureCVLType.VMInternal.BlockchainStateMap,
                is CVLType.PureCVLType.VMInternal.StorageReference,
                is CVLType.PureCVLType.ArrayLiteral,
                is CVLType.PureCVLType.Ghost.Function,
                is CVLType.PureCVLType.Ghost.Mapping,
                is CVLType.PureCVLType.TupleType,
                CVLType.PureCVLType.Void,
                CVLType.PureCVLType.Bottom -> error("impossible argument type for CVL function: $type")
            }
        }

        val callId = Allocator.getFreshId(Allocator.Id.CALL_ID)

        val compiledArguments = exp.args.mapIndexed { index, cvlExp ->
            val param = sub.params[index]
            val (transient, tgt) = allocatedTACSymbols.generateTransientUniqueCVLParam(cvl.symbolTable, param)
            val inVar = newAllocatedTACSymbols.get(param.id, param.type.toTag())
            val wildcardAssumptions = if (cvlExp is CVLExp.VariableExp && cvlExp.id.isWildcard()) {
                // If we have a call to a CVL function with a wildcard as one of the arguments (`myCVLFunction(_)`) then
                // we need to add the value assumptions on this argument (something that would normally happen as part
                // of a variable's definition/assignment).
                // So..., a wilcard used to generate variables of the right type iirc? but if this works w/e
                addVariableValueAssumptions(
                    param.id,
                    param.type,
                    "wildcard (${inVar}) value range assumptions",
                    newAllocatedTACSymbols,
                    compilationEnvironment
                )
            } else {
                null
            }
            ParametricMethodInstantiatedCode.merge(
                listOfNotNull(
                    CVLExpressionCompiler(this, allocatedTACSymbols, compilationEnvironment).compileExp(transient, cvlExp),
                    CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = inVar,
                            rhs = tgt.asSym()
                        ),
                        SnippetCmd.CVLSnippetCmd.CVLArg.AnyArg(callId, index, inVar, param).toAnnotation()
                    ), setOf(inVar, tgt)).toProg("write arg $index", compilationEnvironment).toSimple(),
                    wildcardAssumptions
                ),
                "argument $index for ${exp.id}"
            )

        }.foldFirstOrNull { acc, p -> ParametricMethodInstantiatedCode.mergeProgs(acc, p) }
            ?: getSimple(CVLTACProgram.empty("no arguments"))


        val type = exp.getPureCVLType()
        val body = if (type is CVLType.PureCVLType.Void && sub.block.lastOrNull() !is CVLCmd.Simple.Return) {
            sub.block + CVLCmd.Simple.Return(exp.getRangeOrEmpty(), listOf(), exp.getScope())
        } else {
            sub.block
        }
        val compiledBody = if (body.isEmpty()) {
            CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toProg("empty block", compilationEnvironment).toSimple()
        } else {
            /**
             * usually, we would wrap [CVLCmd]s with labels early-on at [compileRule],
             * but at that point CVL function calls are just [CVLCmd.Simple.Apply]s with no body.
             * thus we do it here.
             */
            val asCompositeBlock = CVLCmd.Composite.Block(sub.range, body, sub.scope)
            wrapCmdWithLabels(asCompositeBlock).map {
                val commandToCompile = assertModifier.cmd(it).safeForce()
                compileCommand(commandToCompile, newAllocatedTACSymbols, compilationEnvironment)
            }.let {
                ParametricMethodInstantiatedCode.merge(it, "compiled body")
            }
        }

        val (storageSave, returnCode) = if (exp.noRevert) {
            val joinPoint = CommandWithRequiredDecls(listOf(TACCmd.Simple.LabelCmd("join point of revert handling"))).toProg("join point", compilationEnvironment)
            val revertNode = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AnnotationCmd(CVLInvocationCompiler.REVERT_CONFLUENCE),
                TACCmd.Simple.JumpCmd(joinPoint.getStartingBlock())
            )).toProg("revert path", compilationEnvironment)
            val successNode = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.JumpCmd(joinPoint.getStartingBlock())
            )).toProg("happy path", compilationEnvironment)
            val revertCond = CVLReservedVariables.certoraCond.toVar(Tag.Bool).toUnique("!")
            val setup = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = revertCond,
                    rhs = CVLKeywords.lastReverted.toVar().asSym()
                )
            ), setOf(CVLKeywords.lastReverted.toVar(), revertCond
            )
            ).toProg("revert check", compilationEnvironment)
            val revertHandling = mergeCodes(
                mergeIfCodes(
                    setup,
                    jumpiCmd = TACCmd.Simple.JumpiCmd(
                        dst = revertNode.getStartingBlock(),
                        elseDst = successNode.getStartingBlock(),
                        cond = revertCond
                    ),
                    thenCode = revertNode,
                    elseCode = successNode
                ), joinPoint
            )
            CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd)) to revertHandling
        } else {
            val v = CVLReservedVariables.certoraResetStorage.toVar(Tag.BlockchainState).toUnique("!")
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.CVL.CopyBlockchainState(
                        lhs = v,
                        meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE)
                    )
                ), v
            ) to revertingAssumptionsToPostpend(v, compilationEnvironment)
        }
        val revertProcessedBody = compiledBody.transformCode {
            p -> p.prependToBlock0(storageSave)
        }
        val functionBody = ParametricMethodInstantiatedCode.mergeProgs(compiledArguments, revertProcessedBody).transformCode { p ->
            p.patching { patching ->
                val setLastRevertedCmds = if (Config.CvlFunctionRevert.get()) { listOf(EthereumVariables.setLastReverted(lastReverted = false)) } else { listOf() }
                p.ltacStream().forEach { lc ->
                    if (lc.cmd is TACCmd.Simple.AnnotationCmd) {
                        lc.cmd.bind(RETURN_VALUE) { v ->
                            when (v.syms.size){
                                0 -> check(type is CVLType.PureCVLType.Void) { "return type is void, but function has return values" }
                                1 -> check(type !is CVLType.PureCVLType.TupleType) { "return type is tuple, but function returns a single value" }
                                else -> check(type is CVLType.PureCVLType.TupleType) { "function returns multiple values, but return type isn't tuple" }
                            }
                            if (out.size > v.syms.size) {
                                throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, "Too many out symbols for expected return values")
                            }
                            val typeList = type.toTypeList()
                            val cmds = v.syms.take(out.size).zip(out).withIndex().map { (ind, retPair) ->
                                val (returnValueHolder, outputParam) = retPair
                                val outputVar = allocatedTACSymbols.get(outputParam.id, returnValueHolder.tag)
                                val labelId = Allocator.getFreshId(Allocator.Id.CVL_EVENT)
                                CommandWithRequiredDecls(listOf(
                                    TACCmd.Simple.AnnotationCmd(CVL_LABEL_START, v.label).plusMeta(CVL_LABEL_START_ID, labelId),
                                    SnippetCmd.CVLSnippetCmd.CVLRet.AnyRet(callId, ind, returnValueHolder, typeList[ind], v.label)
                                        .toAnnotation(),
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                        outputVar,
                                        returnValueHolder
                                    ),
                                    TACCmd.Simple.AnnotationCmd(CVL_LABEL_END, labelId),
                                ), setOf(returnValueHolder, outputVar))
                            }

                            patching.replaceCommand(lc.ptr, mergeMany(cmds + setLastRevertedCmds))
                        }
                    }
                }
            }.let {
                // A CVL function should always have a single exit block.
                // For example if we have `cvlFunc() ? then : else`, the CondExp expects to receive a compiled condition
                // with a single leaf.
                it.addSink(CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd)), compilationEnvironment).first
            }
        }
        val rangeOfApplication = exp.getRangeOrEmpty()
        val wrappedFunction = CommandWithRequiredDecls(
            SnippetCmd.CVLSnippetCmd.CVLFunctionStart(callId, sub.declarationId, rangeOfApplication, exp.noRevert, StackEntry.CVLFunction(sub)).toAnnotation()
        ).toProg("start compiled body $sub", compilationEnvironment) merge functionBody.transformCode { c ->
            c.appendToSinks(
                CommandWithRequiredDecls(SnippetCmd.CVLSnippetCmd.CVLFunctionEnd(callId, sub.declarationId).toAnnotation())
            ) merge returnCode
        }

        return wrappedFunction
            .transformCode { prog -> CVLTACProgramBlockCallIdRemapper.remapIds(prog, callId) }
            .transformCode { prog -> prog.copy(procedures = prog.procedures + Procedure(callId, sub)) }
    }

    private fun resetVar(ent: Pair<TACSymbol.Var, TACSymbol.Var?>): List<TACCmd.Simple> {
        val (v, readTracking) = ent
        return if (v.tag == Tag.WordMap) {
            check(readTracking == null || readTracking.tag == v.tag)
            listOfNotNull(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(v, exprFact.resetStore(Tag.WordMap)),
                    readTracking?.let {
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(it, exprFact.resetStore(Tag.WordMap))
                    }
            )
        } else {
            check(readTracking == null)
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(v, TACSymbol.Zero))
        }.map { it.plusMeta(RESET_STORAGE) }
    }
    private fun compileResetTransientStorageCmd(
            name: String,
            env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        //xxx: Allow to reset only a single contract variable's transient store and not all storage vars. We don't yet allow a resetTransientStorage cmd in CVL
        // and the new invariant resets the transient storage of all contracts.

        val cmds = scene.getTransientStorageUniverse().values.flatMap {
            (it as StorageInfo).storageVariables.map { resetVar(it to null) }.flatten()
        }

        return getSimple(
                codeFromListOfCommands(
                        env.newBlockId(),
                        cmds,
                        name
                )
        )
    }

    private fun compileResetStorageCmd(
        cmd: CVLCmd.Simple.ResetStorage,
        name: String,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        fun resetContract(contract: IContractClass): List<TACCmd.Simple> {
            val storage = contract.storage as StorageInfoWithReadTracker
            val transientStorage = contract.transientStorage as StorageInfo

            return storage.storageVariables.flatMap { resetVar(it.key to it.value) } +
                SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageResetContract(contract.instanceId).toAnnotation() +
                transientStorage.storageVariables.flatMap { resetVar(it to null) }
        }

        val exp = cmd.exp
        val cmds = if (exp is CVLExp.VariableExp && exp.id == CVLKeywords.allContracts.name) {
            // reset the storage and transient storage of all the contracts (used in the initial state of an invariant)
            scene.getContracts().flatMap { resetContract(it) }
        } else {
            val contract = exp.getCVLType() as? CVLType.PureCVLType.Primitive.CodeContract
                ?: error("expected a contract alias type")

            resetContract(scene.getContract(contract.name))
        }
        return CommandWithRequiredDecls(cmds).toProg(name, env).toSimple()
    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun assumeExp(
        exp: CVLExp,
        description: String,
        allocatedTACSymbols: TACSymbolAllocation,
        twoStateContext: (CVLExp) -> CVLExp = { it },
        compilerEnv: CompilationEnvironment,
        meta: MetaMap
    ): ParametricInstantiation<CVLTACProgram> {
        val (assumeParam, assumeVar) = allocatedTACSymbols.generateTransientUniqueCVLParam(symbolTable, CVLReservedVariables.certoraAssume.name, CVLType.PureCVLType.Primitive.Bool)
        // note that, while compileExp below will also expand definitions/macros, since the operation is idempotent
        // the second call will have no effect. However, it is necessary to inline definitions before flattening
        // two state variables so that the flattener itself doesn't have to reason about argument inlining

        val twoStateVariablesFlattened = twoStateContext(exp)
        val expCode = CVLExpressionCompiler(this, allocatedTACSymbols, compilerEnv)
            .compileExp(assumeParam, twoStateVariablesFlattened)
        val cmd = TACCmd.Simple.AssumeCmd(assumeVar, meta = meta, msg = description)
        return expCode.addSink(CommandWithRequiredDecls(cmd, assumeVar), compilerEnv)
    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileHavocCmd(
        cmd: CVLCmd.Simple.Havoc,
        name: String,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        val expCompiler by lazy { CVLExpressionCompiler(this, allocatedTACSymbols, env) }

        val (storageTargets, nonStorageTargets) = cmd.targets.partitionMap {
            when (it) {
                is CVLExp.ArrayDerefExp,
                is CVLExp.FieldSelectExp -> it.toLeft()

                is CVLExp.VariableExp -> it.toRight()
            }
        }

        val havocedStorageTargets = storageTargets.map { target -> expCompiler.compileStorageHavocTarget(target) }
        val storageTargetCompilation = ParametricMethodInstantiatedCode.merge(havocedStorageTargets, "storage target compilation")

        // Assumptions made since we have completed type checking:
        // 1. The havoc'd variable was either a variable of a simple type, or a ghost function
        // 2. If the havoc'd variable was a ghost function, it exists under cmd.havoccedVar.id as a
        //    CVLGhostFunction inside the symbol table
        val havocdGhosts = mutableSetOf<CVLExp.VariableExp>()
        val havocdNonGhosts = mutableSetOf<CVLExp.VariableExp>()
        // tagging each havocced var and determining if it's ghost or non ghost
        // this is super ugly since we have side effects here, so take note!
        val taggedHavocdVars = nonStorageTargets.map { havocdVar ->
            when (val havocdSymbolType = havocdVar.getPureCVLType()) { //symbolTable.lookUp(cmd.id, cmd.loc)
                is CVLType.PureCVLType.Primitive,
                is CVLType.PureCVLType.Struct -> {
                    havocdNonGhosts.add(havocdVar)
                    havocdVar to havocdSymbolType.toTag()
                }
                // the type checker should enforce that only
                // ghosts (and no other function types) are assigned like this
                is CVLType.PureCVLType.Ghost.Function -> {
                    havocdGhosts.add(havocdVar)
                    if (havocdSymbolType.inParams.isEmpty()) {
                        havocdVar to havocdSymbolType.outParam.toTag()
                    } else {
                        havocdVar to havocdSymbolType.toTag()
                    }
                }
                // also a ghost:
                is CVLType.PureCVLType.Ghost.Mapping -> {
                    havocdGhosts.add(havocdVar)
                    havocdVar to havocdSymbolType.toTag()
                }

                is CVLType.PureCVLType.VMInternal.RawArgs -> {
                    havocdVar to havocdSymbolType.toTag()
                }

                else -> error(
                    "internal error: type checker should not allow variables of types other than CVL simple types and ghost functions to be havoc'd. Got $havocdVar"
                )
            }

        }

        /**
         * A havoc command is actually a havoc-assuming command. In the assumingExp of the havoc command, we may refer
         * to the variable being havoc'd. However, there are two potential versions we may want to refer to: pre-havoc
         * and post-havoc. For a havoc'd variable x, the old version is referred to as x@old and the new version as
         * x@new. If x is havoc'd, you may not refer to x without @new or @old in the assumingExp.
         *
         * Below we are generating names to reason about the old and new versions of havoc'd variables. Suppose we have
         * the CVL expression:
         *
         * havoc x assuming <exp>;
         *
         * Any time `exp` refers to `x@new`, we'll use `x`.
         * Any time `exp` refers to `x@old`, we'll use `x_old`.
         */
        val oldVersionNames = taggedHavocdVars
            .associate { (havocdVar, _) ->
                havocdVar.id to "${havocdVar.id}_old"
            }

        oldVersionNames.forEach { (original, old) ->
            if (allocatedTACSymbols.isAllocated(original)) {
                // currently we do not allocate for non-nullary functions so checking if the original name is already
                // allocated is the easiest way to see if we should also be allocating a variable for the old version
                // I don't like this but it will have to work for now but ultimately I would like to allocate names for
                // all ghost functions
                // This is terrible, but the ghost variables need to be in the global allocatedTACSymbols
                // as well as the local one because their axioms are "global" to the program and not restricted
                // to some subscope. And we need to extend the global scope first to make sure the symbol name is
                // unique in that scope.
                val originalTag = allocatedTACSymbols.get(original).tag
                val oldVar = if (!this.allocatedTACSymbols.isAllocated(old)) {
                    this.allocatedTACSymbols.extend(old, originalTag)
                } else {
                    this.allocatedTACSymbols.get(old, originalTag)
                }

                if (!allocatedTACSymbols.isAllocated(old)) {
                    allocatedTACSymbols.extend(old, oldVar)
                }
            }
        }

        val ghostsWithOldSuffix = havocdGhosts
            .map { havocdVar ->
                val ghostToCopy = this.cvl.ghosts.first { it.id == havocdVar.id }
                check(ghostToCopy is CVLGhostWithAxiomsAndOldCopy) { "didn't expect a non-duplicateable ghost here" }
                ghostToCopy.duplicate(ghostToCopy.id, oldVersionNames).withOldCopy(oldCopy = true) as CVLGhostDeclaration
            }
        allGhosts.addAll(ghostsWithOldSuffix)

        // TODO: see if we can avoid modifying [this.symbolTable] and instead keep this typescope local to this branch of compilation
        tacSymbolTable = buildTACSymbolTable(
            this.cvl.sorts.map { sortDecl -> sortDecl.sort }, // gotta put the sorts here so TACSymbolTable
            // construction succeeds on newGhosts which
            // contain uninterpreted sorts...
            allGhosts,
            setOf(),
            allocatedTACSymbols
        )
            .let {
                tacSymbolTable.merge(it)
            }

        val havocCmds = taggedHavocdVars.map { (havocdVar, tag) ->
            val havocdSymbolType = havocdVar.getPureCVLType()

            val postHavocNormalizationCmds = if (allocatedTACSymbols.isAllocated(havocdVar.id)) {
                val sym = allocatedTACSymbols.get(havocdVar.id)
                typeConstraints(havocdSymbolType, sym)
            } else {
                CommandWithRequiredDecls()
            }


            val havocdOldVarName = oldVersionNames[havocdVar.id]!!
            val havocdNewVarName = havocdVar.id

            /**
             * Some nonsense because apparently we allocate variables for nullary ghosts but non n-ary ghosts
             * where n>=1
             */
            val havocdOldVar = if (allocatedTACSymbols.isAllocated(havocdOldVarName)) {
                allocatedTACSymbols.get(havocdOldVarName, tag)
            } else {
                TACSymbol.Var(
                    havocdOldVarName,
                    tag
                )
            }.withMeta(ghostMeta(havocdVar.originalName, havocdVar.getPureCVLType()))
            val havocdNewVar = if (allocatedTACSymbols.isAllocated(havocdNewVarName)) {
                allocatedTACSymbols.get(havocdNewVarName, tag)
            } else {
                TACSymbol.Var(
                    havocdNewVarName,
                    tag
                )
            }.withMeta(ghostMeta(havocdVar.originalName, havocdVar.getPureCVLType()))

            // some of the types here would be invalid for a ghost, but input is assumed to be correct at this point
            val ghostSort = when (havocdSymbolType) {
                is CVLType.PureCVLType.Ghost.Function -> GhostSort.Function
                is CVLType.PureCVLType.Ghost.Mapping -> GhostSort.Mapping
                else -> GhostSort.Variable
            }

            /**
             * Taking the example above,
             * havoc x assuming <exp>, where x@new -> x, x@old -> x_old
             *
             * We generate
             * x_old := x;
             * x := havoc
             */
            val havocCmds = ParametricInstantiation.getSimple(
                codeFromCommandVarWithDecls(
                    env.newBlockId(),
                    CommandWithRequiredDecls<TACCmd.Spec>(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                havocdOldVar, havocdNewVar
                            ),
                            TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                                havocdNewVar
                            ),
                            SnippetCmd.CVLSnippetCmd.GhostHavocSnippet(havocdVar.id, ghostSort).toAnnotation()
                        ),
                        setOf(havocdNewVar, havocdOldVar)
                    ).merge(postHavocNormalizationCmds),
                    name
                )
            )

            havocCmds
        }

        val havocdGhostNames = havocdGhosts.map { ghost -> ghost.id }
            .toSet()

        val havocdVariableNames = havocdNonGhosts.map { havocdVar -> havocdVar.id }

        // replace annotated @old/@new references to havoc'd variable with unanotated versions <name>_old and
        // <name> respectively
        val ghostFunctionSubstitutor =
            GhostUseSubstitutor<Nothing>({ ghost ->
                if (ghost.methodIdWithCallContext.methodId in havocdGhostNames && ghost.twoStateIndex == TwoStateIndex.NEW) {
                    ghost.copy(twoStateIndex = TwoStateIndex.NEITHER).lift()
                } else if (ghost.methodIdWithCallContext.methodId in havocdGhostNames && ghost.twoStateIndex == TwoStateIndex.OLD) {
                    ghost.copy(
                        id = oldVersionNames[ghost.id]!!,
                        twoStateIndex = TwoStateIndex.NEITHER
                    ).lift()
                } else if (ghost.methodIdWithCallContext.methodId in havocdGhostNames && ghost.twoStateIndex == TwoStateIndex.NEITHER) {
                    error(
                        "Internal error: type checker should not allow havoc'd ghost to be referred to " +
                            "with two state index NEITHER"
                    )
                } else {
                    null.lift()
                }
            }, { ghost ->
                val varExp = ghost.baseArray as? CVLExp.VariableExp
                    ?: throw UnsupportedOperationException("unexpected havocced ghost array exp: $ghost")
                if (varExp.id in havocdGhostNames && varExp.twoStateIndex == TwoStateIndex.NEW) {
                    VariableSubstitutor { v ->
                        if (v == varExp)
                            varExp.copy(twoStateIndex = TwoStateIndex.NEITHER)
                        else
                            v
                    }.expr(ghost)
                    // ghost.copy(array = varExp.copy(twoStateIndex = TwoStateIndex.NEITHER))
                } else if (varExp.id in havocdGhostNames && varExp.twoStateIndex == TwoStateIndex.OLD) {
                    VariableSubstitutor { v ->
                        if (v == varExp)
                            varExp.copy(id = oldVersionNames[varExp.id]!!, twoStateIndex = TwoStateIndex.NEITHER)
                        else
                            v
                    }.expr(ghost)
                } else if (varExp.id in havocdGhostNames && varExp.twoStateIndex == TwoStateIndex.NEITHER) {
                    error(
                        "Internal error: type checker should not allow havoc'd ghost to be referred to " +
                            "with two state index NEITHER"
                    )
                } else {
                    null.lift()
                }
            })
        val variableSubstitutor = VariableSubstitutor { variable ->
            if (variable.id in havocdVariableNames && variable.twoStateIndex == TwoStateIndex.NEW) {
                variable.copy(twoStateIndex = TwoStateIndex.NEITHER)
            } else if (variable.id in havocdVariableNames && variable.twoStateIndex == TwoStateIndex.OLD) {
                variable.copy(id = oldVersionNames[variable.id]!!, twoStateIndex = TwoStateIndex.NEITHER)
            } else if (variable.id in havocdVariableNames && variable.twoStateIndex == TwoStateIndex.NEITHER) {
                error(
                    "Internal error: type checker should not allow havoc'd variable to be referred to " +
                        "with two state index NEITHER"
                )
            } else {
                null
            }
        }

        /**
         * Again taking the example
         * havoc x assuming <exp> where x@new -> x, x@old -> x_old
         *
         * [ghostFunctionSubstitutor] and [variableSubstitutor] apply the transformation { x@new -> x, x@old -> x_old }
         * to <exp> and then <exp> is compiled into TAC.
         */
        val assumingCmds =
            assumeExp(
                cmd.assumingExpOrDefault, "generated assume for havoc", allocatedTACSymbols,
                twoStateContext = { exp -> ghostFunctionSubstitutor.expr(variableSubstitutor.expr(exp).safeForce()).safeForce() },
                compilerEnv = env,
                meta = MetaMap()
            )

        val lastStorageUpdate = CommandWithRequiredDecls(
            TACCmd.CVL.CopyBlockchainState(
                lhs = CVLKeywords.lastStorage.toVar(),
                meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE)
            ), CVLKeywords.lastStorage.toVar()
        ).toProg("last storage update after havoc", env).toSimple()

        return ParametricMethodInstantiatedCode.merge(
            havocCmds.plus(storageTargetCompilation).plus(assumingCmds).plus(lastStorageUpdate), name
        )
    }

    /**
     * adds type-imposed constraints for a variable, if necessary.
     *
     * as an example of why this is needed, consider the havoc of an int8 - we cannot havoc it unconditionally:
     * at the very least, a new value must be non-negative and account for the bitsize limit.
     */
    internal fun typeConstraints(havocedType: CVLType.PureCVLType, havocedVar: TACSymbol.Var) =
        if (havocedType is CVLType.PureCVLType.CVLValueType || havocedType is CVLType.PureCVLType.Struct) {
            ensureBitWidth(havocedType, havocedVar)
        } else {
            CommandWithRequiredDecls()
        }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileBlockCmd(
        cmd: CVLCmd.Composite.Block,
        name: String,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        if (cmd.block.isEmpty()) {
            return CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toProg("empty block", env).toSimple()
        }

        val bodyAllocatedTACSymbols = allocatedTACSymbols.nestedScope()
        val compiledBlock = cmd.block.map { b -> compileCommand(b, bodyAllocatedTACSymbols, compilationEnvironment = env) }
        return ParametricMethodInstantiatedCode.merge(compiledBlock, name)
    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileIfCmd(
        cmd: CVLCmd.Composite.If,
        allocatedTACSymbols: TACSymbolAllocation,
        compilationEnvironment: CompilationEnvironment,
    ): ParametricInstantiation<CVLTACProgram> {
        // do not pollute current scope with condition variables (probably wouldn't matter but this seems cleaner)
        val bodyAllocatedTACSymbols = allocatedTACSymbols.nestedScope()
        val condMeta = MetaMap(TACMeta.CVL_IF_PREDICATE)
        val (condParam, condVarTac) = bodyAllocatedTACSymbols
            .generateTransientUniqueCVLParam(symbolTable, CVLReservedVariables.certoraCond.name, CVLType.PureCVLType.Primitive.Bool, condMeta)

        val ifStartId =  if (cmd.cond.tag.annotation == CVLCmd.Composite.If.ConstantIfCond) {
            null
        } else {
            Allocator.getFreshId(Allocator.Id.CVL_EVENT)
        }

        val condExp = cmd.cond
        val condCompiled = if (ifStartId == null) {
            CVLExpressionCompiler(this, bodyAllocatedTACSymbols, compilationEnvironment).compileExp(condParam, condExp)
        } else {
            /** n.b. this annotation can't appear before the compilation of the cond variable, otherwise optimizations screw with it */
            val ifStart = CommandWithRequiredDecls(
                SnippetCmd.CVLSnippetCmd.IfStart(
                    condVarTac,
                    cmd.cond,
                    ifStartId,
                    cmd.range,
                ).toAnnotation()
            )

            CVLExpressionCompiler(this, bodyAllocatedTACSymbols, compilationEnvironment).compileExp(condParam, condExp).addSink(ifStart, compilationEnvironment)
        }

        val thenCodeCompiled =
            compileCommand(
                cmd.thenCmd,
                bodyAllocatedTACSymbols.nestedScope(), // new scope, newly added variables shouldn't affect else branch
                compilationEnvironment
            ).transformCode {
                if (ifStartId == null) {
                    it
                } else {
                    val id = Allocator.getFreshId(Allocator.Id.CVL_EVENT)
                    val start = SnippetCmd.CVLSnippetCmd.BranchStart(
                        SnippetCmd.CVLSnippetCmd.BranchStart.Kind.THEN,
                        id,
                        ifStartId,
                        cmd.thenCmd.range,
                    ).toAnnotation()

                    val end = TACCmd.Simple.AnnotationCmd(CVL_LABEL_END, id)

                    it.wrapWith(start, end)
                }
            }

        val elseCodeCompiled =
            compileCommand(
                cmd.elseCmd,
                bodyAllocatedTACSymbols.nestedScope(), // new scope, newly added variables shouldn't affect then branch
                compilationEnvironment
            ).transformCode {
                if (ifStartId == null) {
                    it
                } else {
                    val id = Allocator.getFreshId(Allocator.Id.CVL_EVENT)
                    val start = SnippetCmd.CVLSnippetCmd.BranchStart(
                        SnippetCmd.CVLSnippetCmd.BranchStart.Kind.ELSE,
                        id,
                        ifStartId,
                        cmd.elseCmd.range,
                    ).toAnnotation()

                    val end = TACCmd.Simple.AnnotationCmd(CVL_LABEL_END, id)

                    it.wrapWith(start, end)
                }
            }

        val condjump = TACCmd.Simple.JumpiCmd(
            thenCodeCompiled.getHead(),
            elseCodeCompiled.getHead(),
            condVarTac,
            //TODO remove this when a better source mapping solution is implemented
            meta = MetaMap(TACMeta.CVL_RANGE to cmd.range)
        )

        return ParametricMethodInstantiatedCode.mergeIf(
            condCompiled,
            condjump,
            thenCodeCompiled,
            elseCodeCompiled
        ).transformCode {
            if (ifStartId == null) {
                it
            } else {
                val ifEnd = CommandWithRequiredDecls(
                    TACCmd.Simple.AnnotationCmd(TACMeta.CVL_LABEL_END, ifStartId)
                )

                it.appendToSinks(ifEnd)
            }
        }
    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileAssumeInvariant(cmd: CVLCmd.Simple.AssumeCmd.AssumeInvariant, allocatedTACSymbols: TACSymbolAllocation): ParametricInstantiation<CVLTACProgram> {
        val inv =
            symbolTable.lookUpNonFunctionLikeSymbol(cmd.id, cmd.scope)?.symbolValue as? CVLInvariant
                ?: error("Failed to find invariant ${cmd.id}")
        val replacements = inv.params
            .map { param -> param.id }
            .zip(cmd.params)
            .toMap()

        // No more substitutor exp, probably compile args, assign, pass the param to argument TACVariable mapping via CVLVariableMapping
        val convertedExp = SubstitutorExp(replacements).expr(inv.exp).safeForce()
        return compileCommand(
            CVLCmd.Simple.AssumeCmd.Assume(
                cmd.range,
                convertedExp,
                "compileAssumeInvariant",
                cmd.scope
            ),
            allocatedTACSymbols,
            CompilationEnvironment()
        )
    }


    /**
     * A data class to hold different parts of the program during compilation of a require invariant command.
     *
     * [programToMove] is what we describe below in the command as toBePlacedAfterRuleParamSetup, while
     * [programToMaintain] is what we describe below in the command as assumeRequireInvParam
     */
    private data class RequireInvariantProgram(val programToMove: CVLTACProgram, val programToMaintain: CVLTACProgram)

    /**
     * This compilation step compiles a requireInvariant into two different programs (toBePlacedAfterRuleParamSetup, assumeRequireInvParam).
     *
     * The program assumeRequireInvParam will be inlined at the location where the user originally specified requireInvariant and consists of statements
     * ASSUME (requireInvParam_i == evaluated expression of invariant parameter i)
     * ASSUME (requireInvParam_i == initiallyHavocedParam_i) for every param.
     *
     * The program toBePlacedAfterRuleParamSetup consists of the code compiled by ASSUME invariant_exp(initiallyHavocedParam_0, ... initiallyHavocedParam_n)
     * which will then be wrapped by annotation commands [CVL_ASSUME_INVARIANT_CMD_START] and [CVL_ASSUME_INVARIANT_CMD_END] indicating that this code will be moved.
     *
     * Both code programs (toBePlacedAfterRuleParamSetup, assumeRequireInvParam) are first inlined at the location the user specified the requireInvariant command,
     * then [instrumentation.transformers.RequireInvariantTransformer] takes care of moving the program toBePlacedAfterRuleParamSetup to the correct location
     * (given by where the [CVL_ASSUME_INVARIANT_TARGET] is placed).
     */
    private fun compileAssumeInvariantGlobally(cmd: CVLCmd.Simple.AssumeCmd.AssumeInvariant, allocatedTACSymbols: TACSymbolAllocation): ParametricInstantiation<CVLTACProgram> {
        fun CVLTACProgram.ignoreInCallTrace(): CVLTACProgram =
            this.copy(code = this.code.mapValues { (_, cmds) ->
                cmds.map { tacCmd ->
                    when(tacCmd){
                        is TACCmd.Simple.NopCmd -> tacCmd // NopCmd crashes on withMeta
                        else -> tacCmd.plusMeta(IGNORE_IN_CALLTRACE)
                    }
                }
            })

        /**
         * Generates a unique and stable (String) id for an AssumeInvariant command.
         *
         * The id must be unique in the sense that no two requireInvariant commands receive the same id.
         * The id must be stable in the sense that several invocation across different jobs results in the same id.
         *
         * The result may not contain invalid characters according to [tac.TACIdentifiers.valid], that's why
         * it's using hashCode and toUInt calls.
         */
        fun CVLCmd.Simple.AssumeCmd.AssumeInvariant.uniqueStableId(): String{
            val rangeId = (range as? Range.Range)?.let { cmdRange -> "${(cmdRange).specFile.hashCode().toUInt()}_${cmdRange.start.line}" }
                ?: /* Fallback to plain hashcode to still generate a unique id in case no range is provided*/range.hashCode().toUInt()
            return "${id}_${rangeId}"
        }

        val inv =
            symbolTable.lookUpNonFunctionLikeSymbol(cmd.id, cmd.scope)?.symbolValue as? CVLInvariant
                ?: error("Failed to find invariant ${cmd.id}")

        /**
         * This method creates new CVLExp.VariableExp that end up in meta in TAC. This is problematic for the
         * cache when their ids are not consistent across compilation. (we get cache misses as the ids differ, even
         * though the TAC is equivalent (modulo the ids)).
         *
         * To avoid this, we create a unique id that depends on the name of the invariant that is required [cmd.id]
         * and on the range of the requireInvariant command. The range is necessary to create unique variable names
         * in the case there are multiple requireInvariant commands requiring the same invariant at different locations
         * with potentially different parameter variables.
         *
         * This unique id must be stable across different prover runs so that the TAC cache kicks in.
         *
         * Note: As the variable name includes the range, there are cache misses in case a line break or whitespaces changes
         * the range of the command.
         */
        val requireInvariantUniqueId = cmd.uniqueStableId()


        check(cmd.params.size == inv.params.size){ "The number of parameters of the invariant and the number of parameters of the command don't match." }
        val havocedInvParams = inv.params.mapIndexed { i, p ->
            val havocParameterName = CVLParam(p.type, "initiallyHavocedParam_${requireInvariantUniqueId}_${i}", Range.Empty())
            allocatedTACSymbols.generateTacVarFromParam(havocParameterName)
            havocParameterName
        }

        // Replacing the parameters as they are declared in the actual invariant by the newly created havoc'ed parameter names.
        val replacements = inv.params
            .map { param -> param.id }
            .zip(havocedInvParams.map { CVLExp.VariableExp(it.id, tag = CVLExpTag(cmd.scope, it.type, cmd.range)) })
            .toMap()

        val convertedExp = SubstitutorExp(replacements).expr(inv.exp).safeForce()
        val invariantAssume = compileCommand(
            CVLCmd.Simple.AssumeCmd.Assume(
                cmd.range,
                convertedExp,
                null,
                cmd.scope
            ),
            allocatedTACSymbols,
            CompilationEnvironment()
        )

        val combinedProg = havocedInvParams.zip(cmd.params).foldIndexed(RequireInvariantProgram(CVLTACProgram.empty("toBePlacedAfterRuleParamSetup"), CVLTACProgram.empty("assumeRequireInvParam"))) { i, acc, (havocedInvParam, exp) ->
            val globalRequireInvVar = CVLParam(havocedInvParam.type, "globalRequireInvParam_${requireInvariantUniqueId}_${i}", Range.Empty())
            allocatedTACSymbols.generateTacVarFromParam(globalRequireInvVar)

            val left = CVLExp.VariableExp(havocedInvParam.id, tag = CVLExpTag(cmd.scope, havocedInvParam.type, cmd.range))
            val right = CVLExp.VariableExp(globalRequireInvVar.id, tag = CVLExpTag(cmd.scope, havocedInvParam.type, cmd.range))
            val eqExp = CVLExp.RelopExp.EqExp(left, right, tag = CVLExpTag(cmd.scope, havocedInvParam.type, cmd.range))
            val assumeCmd = CVLCmd.Simple.AssumeCmd.Assume(cmd.range, eqExp, "compileAssumeInvariantGlobally", cmd.scope)

            // compiling the assumption that the assigned global is equal to the initially havoc'ed variable
            val compiledAssume = compileCommand(assumeCmd, allocatedTACSymbols, CompilationEnvironment())

            // Compiling the evaluation of the expression of parameter
            val evaulatedExpressionOfParam = CVLExpressionCompiler(this, allocatedTACSymbols, CompilationEnvironment())
                .compileExp(globalRequireInvVar, exp).getAsSimple().merge(compiledAssume).getAsSimple().ignoreInCallTrace()

            // Adding variable assumption to the freshly havoc'ed CVL parameters. As the freshly generated havoc'ed parameter variables will be moved, the assumption on the bounds must be moved as well.
            val havocedVariableAssumptions = addVariableValueAssumptions(havocedInvParam.id, havocedInvParam.type, "variable assumptions for havoced parameters ", allocatedTACSymbols, CompilationEnvironment()).getAsSimple()
            RequireInvariantProgram(programToMove = acc.programToMove.merge(havocedVariableAssumptions), programToMaintain = acc.programToMaintain.merge(evaulatedExpressionOfParam))
        }

        // Creating a labelId to be able to map start and end of the currently compiled command in [instrumentation.transformers.RequireInvariantTransformer]
        val labelId = Allocator.getFreshId(Allocator.Id.REQUIRE_INVARIANT_CMDS)

        val toBePlacedAfterRuleParamSetup = combinedProg.programToMove.merge(invariantAssume)
            .getAsSimple()
            .prependToBlock0(CommandWithRequiredDecls(listOf(TACCmd.Simple.AnnotationCmd(CVL_ASSUME_INVARIANT_CMD_START, TACMeta.RequireInvariant(labelId, cmd.id)))))
            .appendToSinks(CommandWithRequiredDecls(listOf(TACCmd.Simple.AnnotationCmd(CVL_ASSUME_INVARIANT_CMD_END, TACMeta.RequireInvariant(labelId, cmd.id)))))

        return combinedProg.programToMaintain.merge(toBePlacedAfterRuleParamSetup).toSimple()
    }

    private fun compileLabelCmd(cmd: CVLCmd.Simple.Label, name: String, env: CompilationEnvironment): ParametricInstantiation<CVLTACProgram> {
        val tacCmd = when (cmd) {
            is CVLCmd.Simple.Label.Start -> TACCmd.Simple.AnnotationCmd(CVL_LABEL_START, cmd.content).plusMeta(CVL_LABEL_START_ID, cmd.id)
            is CVLCmd.Simple.Label.End -> TACCmd.Simple.AnnotationCmd(CVL_LABEL_END, cmd.id)
        }

        return ParametricInstantiation.getSimple(
            codeFromListOfCommands(
                env.newBlockId(),
                listOf(tacCmd),
                name
            )
        )
    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileAssumeCmd(
        cmd: CVLCmd.Simple.AssumeCmd.Assume,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        var meta = MetaMap()
        if (cmd.comesFromSpec) {
            meta += TACMeta.CVL_USER_DEFINED_ASSUME to (cmd.description != null)
        }
        if (cmd.range !is Range.Empty) meta += MetaMap(TACMeta.CVL_RANGE to cmd.range)
        return assumeExp(cmd.exp, cmd.descriptionOrDefault, allocatedTACSymbols, compilerEnv = env, meta = meta)
    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileAssertCmd(
        cmd: CVLCmd.Simple.Assert,
        name: String,
        allocatedTACSymbols: TACSymbolAllocation,
        compilationEnvironment: CompilationEnvironment,
        generatedForSatisfy: Int? = null
    ): ParametricInstantiation<CVLTACProgram> {
        val scope = allocatedTACSymbols.nestedScope()
        val (assertVarCVL, assertVarTAC) = scope
            .generateTransientUniqueCVLParam(symbolTable, CVLReservedVariables.certoraAssert.name, CVLType.PureCVLType.Primitive.Bool)
        val expCode = CVLExpressionCompiler(this, scope, compilationEnvironment = compilationEnvironment).compileExp(assertVarCVL, cmd.exp)

        // must add assertion *after* evaluating the expression (since the evaluation of the expression may trigger other assertions such as division by zero)

        var assertMeta = MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT)
        if (cmd.range !is Range.Empty) assertMeta += MetaMap(TACMeta.CVL_RANGE to cmd.range)
        // If this is an assert generated by a Satisfy, which always acts
        // like the multi-assert mode for normal Assertions. As a result,
        // we need to delete all the preceeding satisfy-generated
        // assertions rather than convert them into Requires (because it would
        // be a Requires(false) otherwise). This Meta command already has that
        // effect.
        if (generatedForSatisfy != null) assertMeta += MetaMap(SATISFY_ID to generatedForSatisfy)

        val assertCode = codeFromListOfCommands(
            compilationEnvironment.newBlockId(),
            listOf(
                TACCmd.Simple.AssertCmd(
                    assertVarTAC,
                    description = cmd.descriptionOrDefault,
                    meta = assertMeta)
            ),
            name
        )
        return ParametricMethodInstantiatedCode.mergeProgs(
            expCode,
            ParametricInstantiation.getSimple(assertCode)
        ).transformCode { c -> c.copy(name = name) }
    }

    /**
     * Compile satisfy into CVLTac (by first compiling it into different CVL)
     *
     * Satisfy(P) is translated into {Requires(P); Assert(false); }
     * When checking a rule that contains a satisfy, multi-assert mode is always
     * turned on.
     *
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileSatisfyCmd(
        cmd: CVLCmd.Simple.Satisfy,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        val satisfyUUID = Allocator.getFreshId(allocator.Allocator.Id.SATISFIES)
        val requireCmd = assumeExp(cmd.exp, "assume generated for satisfy: ${cmd.descriptionOrDefault}", allocatedTACSymbols, compilerEnv = env, meta = MetaMap(
            SATISFY_ID to satisfyUUID))

        val boolType = CVLType.PureCVLType.Primitive.Bool
        val falseLit = CVLExp.Constant.BoolLit(false, CVLExpTag(cmd.scope, boolType, cmd.range))

        val assertCmd = CVLCmd.Simple.Assert(
            range = cmd.range,
            exp = falseLit,
            description = cmd.descriptionOrDefault,
            scope = cmd.scope,
            invariantPostCond = false
        )

        return ParametricMethodInstantiatedCode.mergeProgs(
            requireCmd,
            compileAssertCmd(assertCmd, assertCmd.javaClass.name,
                allocatedTACSymbols, env, satisfyUUID)
        )

    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileAssignment(
        l: List<CVLLhs>,
        allocatedTACSymbols: TACSymbolAllocation,
        exp: CVLExp,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        val expCompiler = CVLExpressionCompiler(this, allocatedTACSymbols, compilationEnvironment = env)
        return expCompiler.compileAssignment(
            l,
            allocatedTACSymbols,
            exp
        )
    }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileDefinitionCmd(
        cmd: CVLCmd.Simple.Definition,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        if (cmd.type != null) {
            // only allocate a TAC Variable if this is a declaration + assignment (as opposed to just an assignment)
            val lhs = cmd.idL.singleOrNull() ?: error("Can't declare and assign multiple vars at once")
            val name = lhs.getIdLhs()
            val type = name.getPureCVLType()
            val defSite = CVLDefinitionSite.fromScope(cmd.scope, lhs.getRangeOrEmpty())
            allocatedTACSymbols.extendWithCVLVariable(name.id, type, defSite)
        }

        return compileAssignment(
            cmd.idL,
            allocatedTACSymbols,
            cmd.exp,
            env = env
        )
    }

    private fun compileRevertCmd(cmd: CVLCmd.Simple.Revert ,name: String, env: CompilationEnvironment): ParametricInstantiation<CVLTACProgram> {
        val revertConfluence = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AnnotationCmd(CVLInvocationCompiler.REVERT_CONFLUENCE)
        )).toProg("revert confluence", env)
        val jumpToRevertConfluence = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.JumpCmd(revertConfluence.getStartingBlock())
        ))
        val reportLabel = CVLReportLabel.Revert(cmd)
        return getSimple(
            setLastReverted(true).toProg("cvl revert $name", env).wrapWithCVLLabelCmds(reportLabel) merge jumpToRevertConfluence.toProg("cvl revert $name", env) merge revertConfluence
        )
    }

    /**
     * Generates a transient annotation only present during the code-gen phase to store return information until
     * all returns for a given [CVLFunction] application are handled
     *
     * @see [compileCVLFunctionApplication] where return annotations generated here are used to properly handle
     *      out variable assignment and return control flow
     * @see [mergeCodes] which uses annotations here to avoid prematurely routing returns to immediate successors
     *
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun compileReturnCmd(
        cmd: CVLCmd.Simple.Return,
        name: String,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        val exps = cmd.exps
        if (exps.isNotEmpty()) {
            val cvlType = cmd.scope.enclosingCVLFunction()?.returnType()?.toTypeList()
                ?: throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, "Compiler Invariant Broken: expected return command ($cmd) to have an expected type in it's outermost enclosing scope.")
            check(cvlType.size == exps.size)
            val (returnVars, codes) = cvlType.zip(exps).map { (cvlType, exp) ->
                val (returnVarCVL, returnVarTAC) = allocatedTACSymbols.generateTransientUniqueCVLParam(symbolTable, "cvlReturn", cvlType)
                returnVarTAC to CVLExpressionCompiler(this, allocatedTACSymbols, compilationEnvironment = env).compileExp(returnVarCVL, exp)
            }.unzip()
            return ParametricMethodInstantiatedCode.merge(codes, "return compilation").transformCode { c ->
                // assign the compiled expression to a variable with the name "returnVarName"
                c.appendToSinks(
                    listOf(
                        // tell the caller that this is where the return value can be found
                        TACCmd.Simple.AnnotationCmd(RETURN_VALUE, ReturnValuePayload(returnVars, CVLReportLabel.Return(cmd)))
                    ).withDecls()
                )
            }
        } else {
            return ParametricInstantiation.getSimple(
                codeFromListOfCommands(
                    env.newBlockId(),
                    listOf(
                        TACCmd.Simple.AnnotationCmd(
                            RETURN_VALUE,
                            ReturnValuePayload(
                                listOf(),
                                CVLReportLabel.Return(cmd)
                            )
                        )
                    ),
                    name
                )
            )
        }
    }

    private fun CVLType.PureCVLType.toTypeList() = if (this is CVLType.PureCVLType.TupleType) {
        this.elements
    } else {
        listOf(this)
    }

    private fun CVLType.typeToTag(): Tag =
        when (this) {
            is CVLType.PureCVLType -> this.toTag()
            is CVLType.VM -> {
                val descriptor = this.descriptor
                if (descriptor is VMValueTypeDescriptor) {
                    descriptor.toTag()
                } else {
                    throw UnsupportedOperationException("Should not happen")
                }
            }
        }

    /**
     * @param allocatedTACSymbols is imperatively extended with additional symbols
     */
    private fun addVariableValueAssumptions(id: String, type: CVLType.PureCVLType, name: String, allocatedTACSymbols: TACSymbolAllocation, compilationEnvironment: CompilationEnvironment): ParametricInstantiation<CVLTACProgram> {
        val tacSym = allocatedTACSymbols.get(id, tag = type.toTag())
        val cmds = when (type) {
            is CVLType.PureCVLType.StaticArray -> {
                val lenActual = type.logicalLength
                // total len assume?
                CommandWithRequiredDecls<TACCmd.Spec>(
                    listOf(
                        TACCmd.CVL.SetArrayLength(
                            lhs = tacSym, len = lenActual.asTACSymbol()
                        )
                    ), tacSym
                )
            }

            is CVLType.PureCVLType.DynamicArray -> getArrayAssumptions(id, type, allocatedTACSymbols)
            is CVLType.PureCVLType.CVLValueType -> ensureBitWidth(type, tacSym)
            is CVLType.PureCVLType.Struct -> ensureBitWidth(type, tacSym)
            else -> CommandWithRequiredDecls()
        }

        return getSimple(
            codeFromCommandVarWithDecls(
                compilationEnvironment.newBlockId(),
                cmds,
                name
            )
        )
    }

    /*
     * For a declaration command [cmd] we should only handle assuming that the variable is within the expected bitwidth
     */
    private fun compileDeclarationCmd(
        declCmd: CVLCmd.Simple.Declaration,
        name: String,
        allocatedTACSymbols: TACSymbolAllocation,
        compilationEnvironment: CompilationEnvironment,
        noCallId: Boolean = false,
    ): ParametricInstantiation<CVLTACProgram> {
        if (declCmd.id.isWildcard()) {
            return getSimple(CVLTACProgram.empty("wildcard variable declaration"))
        }


        val type = declCmd.cvlType
        check(type != CVLType.PureCVLType.VMInternal.BlockchainState) {
            "Storage variables must be explicitly initialized at declaration"
        }

        val v = allocatedTACSymbols.extendWithCVLVariable(
            declCmd.id,
            type,
            CVLDefinitionSite.fromScope(declCmd.scope, declCmd.range),
            if (noCallId) {
                MetaMap(TACMeta.NO_CALLINDEX)
            } else {
                MetaMap()
            }
        )

        check(declCmd.cvlType != EVMBuiltinTypes.method) { "all method variable declarations should have been promoted to parameters" }

        return addVariableValueAssumptions(declCmd.id, type, name, allocatedTACSymbols, compilationEnvironment) andThen CommandWithRequiredDecls(listOf(
                    TraceMeta.VariableDeclarationCmd(v, declCmd.cvlType, TraceMeta.DeclarationType.Variable(declCmd.id))
                ), setOf(v)).toProg("decl", compilationEnvironment)
    }

    private fun compileNopCmd(name: String, env: CompilationEnvironment): ParametricInstantiation<CVLTACProgram> {
        return getSimple(
            codeFromListOfCommands(
                env.newBlockId(),
                listOf(TACCmd.Simple.NopCmd),
                name
            )
        )
    }

    private fun multiContractSetup(): CommandWithRequiredDecls<TACCmd.Spec> {
        return cvl.getContractVarIdToAddressAssociation()
            .map { (contract, instanceId) ->
                allocatedTACSymbols.get(contract) to scene.getContract(instanceId).addressSym as TACSymbol
            }
            .let { association ->
                val decls = mutableSetOf<TACSymbol.Var>()
                CommandWithRequiredDecls<TACCmd.Spec>(
                    association.map { (contractVar, associatedAddressVar) ->
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            contractVar,
                            associatedAddressVar
                        )
                    },
                    association.forEach { (contractVar, addressSym) ->
                        decls.add(contractVar)
                        if (addressSym is TACSymbol.Var) {
                            decls.add(addressSym)
                        }
                    }.let { decls }
                )
            }
    }

    private fun ensurePositiveExtcodesizes(): CommandWithRequiredDecls<TACCmd.Spec> {
        val addressesWithPositiveCodesizes = scene.getContracts()
            .filter { it.bytecode?.bytes?.isEmpty() == false }

        val addressesWithZeroCodeSize = scene.getContracts()
            .filterIsInstance<IClonedContract>()

        fun mkSelect(it: IContractClass, cb: TACExprFact.(TACExpr) -> TACExpr) : CommandWithRequiredDecls<TACCmd.Simple> {
            val addressSym = it.addressSym as TACSymbol
            return ExprUnfolder.unfoldPlusOneCmd("extCodesizeCheck${it.name}",
                with(TACExprFactTypeCheckedOnlyPrimitives) {
                    cb(Select(extcodesize.asSym(), addressSym.asSym()))
                }
            ) {
                TACCmd.Simple.AssumeCmd(it.s, "extCodesizeCheck")
            }.merge(extcodesize, addressSym, EthereumVariables.getCodeDataSize(it.instanceId))
        }
        return (addressesWithZeroCodeSize.map {
            mkSelect(it) { sel ->
                Eq(sel, TACExpr.zeroExpr)
            }
        } + addressesWithPositiveCodesizes.map { klass ->
            mkSelect(klass) { sel ->
                Gt(sel, TACExpr.zeroExpr).letIf(Config.PreciseCodedataSemantics.get()) { exp ->
                    LAnd(exp, Eq(sel, EthereumVariables.getCodeDataSize(klass.instanceId).asSym()))
                }
            }
        }).let {
            mergeMany(it)
        }
    }

    private fun assumeExtcodesizesOfAddressZero(): CommandWithRequiredDecls<TACCmd.Spec> {
        val tmp = TACKeyword.TMP(Tag.Bool, "extcodesizeCheckZeroAddress")

        //Assigning tmp variable to result of check address(0).code.length == 0
        val assignTmp = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            tmp,
            TACExpr.BinRel.Eq(
                TACExpr.Select(
                    extcodesize.asSym(),
                    TACSymbol.Zero.asSym()
                ),
                BigInteger.ZERO.asTACSymbol().asSym()
            )
        )

        //Assume tmp variable to hold
        val assumeTmp = TACCmd.Simple.AssumeCmd(tmp, "extcodesizeCheckZeroAddress")

        return CommandWithRequiredDecls<TACCmd.Spec>(listOf(assignTmp, assumeTmp), listOf(tmp, extcodesize))
    }

    private fun ruleLastStorageInitialize(): CommandWithRequiredDecls<TACCmd.Spec> =
        CommandWithRequiredDecls(
            TACCmd.CVL.CopyBlockchainState(
                CVLKeywords.lastStorage.toVar(),
                meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE)
            ),
            CVLKeywords.lastStorage.toVar()
        )

    /**
     * Assume that no contract symbolic address var is the same as another contract's:
     * Contract addresses are injective
     */
    private fun ensureValidContractAddress(): CommandWithRequiredDecls<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val decls = mutableSetOf<TACSymbol.Var>()

        val addresses = scene.getNonPrecompiledContracts()
            .map { it.addressSym as TACSymbol }
        val maxAddr = MASK_SIZE(160)
        val ltTmp = TACKeyword.TMP(Tag.Bool, "MaxContractAddrCheck")
        val gtTmp = TACKeyword.TMP(Tag.Bool, "MinContractAddrCheck")
        decls.add(gtTmp)
        decls.add(ltTmp)

        val assumeGtTmp = TACCmd.Simple.AssumeCmd(gtTmp, "MinContractAddrCheck")
        val assumeLtTmp = TACCmd.Simple.AssumeCmd(ltTmp, "MaxContractAddrCheck")
        addresses.forEach { addr ->
            if (addr is TACSymbol.Var) {
                decls.add(addr)
            }
            cmds.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    gtTmp,
                    TACExpr.BinRel.Gt(
                        addr.asSym(),
                        BigInteger.ZERO.asTACSymbol().asSym()
                    )
                )
            )
            cmds.add(assumeGtTmp)
            cmds.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    ltTmp,
                    TACExpr.BinRel.Le(
                        addr.asSym(),
                        maxAddr.asTACSymbol().asSym()
                    )
                )
            )
            cmds.add(assumeLtTmp)
        }

        return CommandWithRequiredDecls(cmds, decls)
    }

    /**
     *  normally, [ICVLContractClass.instanceId] stores ids that start with 0xce4604a0,
     *  generated by the python script.
     *  but this field is also used to store a user-defined "static" address for a contract,
     *  if it was configured via the `address` command line parameter.
     *
     *  this function generates code to always choose the static address for each contract (if it has one).
     */
    private fun assumeStaticAddresses(contracts: List<IContractClass>): CommandWithRequiredDecls<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val decls = mutableSetOf<TACSymbol.Var>()

        val staticAddresses = contracts
            .mapNotNull { it.instanceId `to?` (it.addressSym as? TACSymbol.Var) }
        val staticAddrTmp = TACKeyword.TMP(Tag.Bool, "StaticAddrAssignment")
        decls.add(staticAddrTmp)

        val assumeAddrTmp = TACCmd.Simple.AssumeCmd(staticAddrTmp, "assumeStaticAddresses")

        staticAddresses.forEach { (staticAddr, contractAddrSym) ->
            cmds.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    staticAddrTmp,
                    TACExpr.BinRel.Eq(
                        contractAddrSym.asSym(),
                        staticAddr.asTACExpr(),
                    )
                )
            )
            cmds.add(assumeAddrTmp)
            decls.add(contractAddrSym)
        }

        return CommandWithRequiredDecls(cmds, decls)
    }

    private fun assumePrecompiledAddressesAreStatic(): CommandWithRequiredDecls<TACCmd.Simple> {
        return assumeStaticAddresses(scene.getPrecompiledContracts())
    }

    /**
     * Assume that no contract symbolic address var is the same as another contract's:
     * Contract addresses are injective
     */
    private fun ensureUniqueContractAddresses(): CommandWithRequiredDecls<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val decls = mutableSetOf<TACSymbol.Var>()

        val addresses = scene.getNonPrecompiledContracts()
            .map { it.addressSym as TACSymbol }
        val remainingAddresses = addresses.toMutableSet()
        val distinctAddr = TACKeyword.TMP(Tag.Bool, "addrDistinction")
        decls.add(distinctAddr)

        val addrDistinguishTmp = TACCmd.Simple.AssumeCmd(distinctAddr, "ensureUniqueContractAddresses")

        addresses.forEach { it ->
            if (it is TACSymbol.Var) {
                decls.add(it)
            }
            remainingAddresses.remove(it)
            remainingAddresses.forEach { other ->
                cmds.add(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        distinctAddr,
                        TACExpr.UnaryExp.LNot(
                            TACExpr.BinRel.Eq(
                                it.asSym(),
                                other.asSym()
                            )
                        )
                    )
                )
                cmds.add(addrDistinguishTmp)
            }
        }

        return CommandWithRequiredDecls(cmds, decls)
    }


    private val ruleCompilationTag = "RULE_COMPILE".toTimeTag()

    /**
     * wraps the inner commands inside the `then`/`else` blocks.
     * note that we _don't_ wrap:
     * 1) the `if` command itself
     * 2) the `then`/`else` themselves.
     *
     * ...all of this is deferred until [compileIfCmd].
     *
     * wrapping the `if` command is not as straightforwards since return statements
     * can lead to exit points in the middle of the if-then-else block.
     * later in the transformation to CFG, the `return` command needs to be the
     * very last command in each block.
     * this is why we also don't wrap `return` commands at this point.
     */
    private fun wrapIfCmd(cmd: CVLCmd.Composite.If): List<CVLCmd> {
        fun wrapBranch(cmd: CVLCmd): CVLCmd =
            when (cmd) {
                is CVLCmd.Composite.Block -> wrapCmdWithLabels(cmd).single()
                else -> CVLCmd.Composite.Block(cmd.range, wrapCmdWithLabels(cmd), cmd.scope)
            }

        return listOf(
            cmd.copy(
                thenCmd = wrapBranch(cmd.thenCmd),
                elseCmd = wrapBranch(cmd.elseCmd)
            )
        )
    }

    /**
     * Wraps suitable commands with the [cmd] with Start and End labels (if not wrapped in labels already).
     */
    private fun wrapCmdWithLabels(cmd: CVLCmd): List<CVLCmd> {
        return when (cmd) {
            is CVLCmd.Composite.Block -> {
                listOf(cmd.copy(block =
                    // don't wrap commands that are already preceded by a Start label
                    when (cmd.block.size) {
                        0 -> listOf()
                        1 -> wrapCmdWithLabels(cmd.block.single())
                        else -> {
                            val l = cmd.block.zipWithNext { firstCmd, nextCmd ->
                                if (firstCmd is CVLCmd.Simple.Label.Start) {
                                    listOf(nextCmd)
                                } else {
                                    wrapCmdWithLabels(nextCmd)
                                }
                            }.flatten()

                            wrapCmdWithLabels(cmd.block.first()) + l
                        }
                    }
                ))
            }

            is CVLCmd.Composite.If -> wrapIfCmd(cmd)

            is CVLCmd.Simple.Apply,
            is CVLCmd.Simple.Assert,
            is CVLCmd.Simple.Satisfy,
            is CVLCmd.Simple.AssumeCmd.Assume,
            is CVLCmd.Simple.AssumeCmd.AssumeInvariant,
            is CVLCmd.Simple.Declaration,
            is CVLCmd.Simple.Definition,
            is CVLCmd.Simple.Exp,
            is CVLCmd.Simple.Havoc,
            is CVLCmd.Simple.ResetStorage,
            is CVLCmd.Simple.ResetTransientStorage -> cmd.wrapWithLabel(CVLReportLabel.Cmd(cmd as CVLCmd.Simple))

            // return must be wrapped separately after/during compiling to TAC because the AST to CFG transformation
            // enforces the returns being the very last command in a block
            is CVLCmd.Simple.Return,
            is CVLCmd.Simple.Label.End,
            is CVLCmd.Simple.Label.Start,
            is CVLCmd.Simple.Revert,
            is CVLCmd.Simple.Nop -> listOf(cmd)
        }
    }

    /**
     * @return a [CommandWithRequiredDecls] which contains an assignment to [lhs] of a [TACExpr.StructConstant] representing
     * the method [this] as a part of contract whose address is [host]
     */
    private fun EVMExternalMethodInfo.toTACStruct(lhs: TACSymbol.Var, host: BigInteger, contractVar: TACSymbol.Var): CommandWithRequiredDecls<TACCmd.Simple> {
        val tagType = EVMBuiltinTypes.method.toTag()
        val commonField = mapOf(
            EVMExternalMethodInfo.fallbackField to TACSymbol.lift(this.isFallback).asSym(),
            EVMExternalMethodInfo.pureField to TACSymbol.lift(this.stateMutability.isPure).asSym(),
            EVMExternalMethodInfo.viewField to TACSymbol.lift(this.stateMutability.isView).asSym(),
            EVMExternalMethodInfo.payableField to TACSymbol.lift(this.stateMutability.isPayable).asSym(),
            EVMExternalMethodInfo.arityField to this.argTypes.size.asTACSymbol().asSym(),
            EVMExternalMethodInfo.contractField to contractVar.asSym()
        )
        return if (this.isFallback) {
            val sighashVar = TACKeyword.TMP(Tag.Bit256, "sighash").toUnique("!")
            val methodStructConstant = TACExpr.StructConstant(
                commonField + (EVMExternalMethodInfo.selectorField to sighashVar.asSym()),
                tag = tagType)
            val commands = listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    sighashVar, TACExpr.Apply(
                    TACExpr.TACFunctionSym.BuiltIn(
                        bif = TACBuiltInFunction.DisjointSighashes
                    ),
                    listOf(host.asTACSymbol().asSym()),
                    tag = Tag.Bit256
                )
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs, methodStructConstant)
            )
            CommandWithRequiredDecls(commands, setOf(lhs, sighashVar))
        } else {
            val sighash = this.sigHash ?: error("No sighash for $this")
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs, TACExpr.StructConstant(
                    commonField + (EVMExternalMethodInfo.selectorField to sighash.asTACSymbol().asSym()),
                    tag = tagType
                )
                )
            ).withDecls(lhs)
        }
    }

    private fun wrapWithCVL(cmds: CommandWithRequiredDecls<TACCmd.Spec>, msg: String): CommandWithRequiredDecls<TACCmd. Spec> {
        val labelId = Allocator.getFreshId(Allocator.Id.CVL_EVENT)
        return CommandWithRequiredDecls<TACCmd.Spec>(
            TACCmd.Simple.AnnotationCmd(CVL_LABEL_START, CVLReportLabel.Message(msg)).plusMeta(CVL_LABEL_START_ID, labelId)
        ).merge(cmds)
            .merge(
                TACCmd.Simple.AnnotationCmd(CVL_LABEL_END, labelId)
            )
    }
    /**
     * Defines a criterion for choosing which methods' instantiations are
     * candidates to be chosen by the SMT for a given symbolic method.
     */
    fun interface RuleCompilationMethodFilter {
        fun filter(itacMethod: ITACMethod, methodParam: CVLParam): Boolean
    }

    /**
     * [RuleCompilationMethodFilter] which filters methods based on the method-filtered
     * block defined in the CVL.
     */
    inner class CVLBasedFilter(id: RuleIdentifier) : RuleCompilationMethodFilter {

        private val ruleFilters = cvl.methodFilters[id]
            ?: throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, "rule $ruleName isn't in the mapping of method filters")

        override fun filter(itacMethod: ITACMethod, methodParam: CVLParam): Boolean {
            val methodParamFilter = ruleFilters[methodParam.id] ?: throw CertoraInternalException(
                CertoraInternalErrorType.CVL_COMPILER,
                "method-arg ${methodParam.id} isn't in the mapping of method filters for rule $ruleName"
            )
            return methodParamFilter.any { method ->
                method.contractName == itacMethod.getContainingContract().name &&
                    method.toMethodSignature(cvl.primaryContract, Visibility.EXTERNAL)
                        .matchesNameAndParams(itacMethod.evmExternalMethodInfo!!.wrappedParameterSignature)
            }
        }

    }

    /**
     * A [RuleCompilationMethodFilter] that filters methods based on a given [MethodParameterInstantiation] ([methodParamInst]).
     * That is, using this filter will result in a compiled rule with a method parameters' instantiation identical to [methodParamInst].
     */
    class InstantiationBasedFilter(private val methodParamInst: MethodParameterInstantiation) : RuleCompilationMethodFilter {

        override fun filter(itacMethod: ITACMethod, methodParam: CVLParam): Boolean =
            methodParamInst.any { (param, methodInfo) ->
                // we need to compare the cvl name, not the tac name
                param.meta[CVL_DISPLAY_NAME] == methodParam.id && methodInfo == itacMethod.evmExternalMethodInfo
            }
    }

    fun generateRuleSetupCode(): CVLTACProgram {
        logger.info { "Rule $ruleName setup header: params and last storage initialization" }

        val startBlockEnvSetup = wrapWithCVL(multiContractSetup(), "multi contract setup")
            .merge(
                wrapWithCVL(declareContractsAddressVars(), "contract address vars initialized")
            ).merge(
                wrapWithCVL(initializeReadTracking(), "setup read tracking instrumentation")
            ).merge(
                wrapWithCVL(countSetup(), "record starting nonces")
            ).merge(
                // process storage for all contracts and balance
                wrapWithCVL(
                    ruleLastStorageInitialize(), "last storage initialize"
                ) // will also set up storageVarFamilies
            ).merge(
                wrapWithCVL(
                    ensurePositiveExtcodesizes(),
                    "assuming contracts in scene with non-empty bytecode have EXTCODESIZE larger than zero"
                )
            ).merge(
                if (Config.assumeAddressZeroHasNoCode.get()) {
                    wrapWithCVL(assumeExtcodesizesOfAddressZero(), "assuming address(0).code has no code deployed")
                } else {
                    wrapWithCVL(CommandWithRequiredDecls(TACCmd.Simple.NopCmd), "making no assumptions on code deployed at address(0)")
                }
            ).merge(
                wrapWithCVL(ensureValidContractAddress(), "assumptions about contracts' addresses")
            ).merge(
                wrapWithCVL(assumeStaticAddresses(
                    scene.getNonPrecompiledContracts()
                        .filter { it.instanceIdIsStaticAddress && it.addressSym is TACSymbol.Var }
                ), "assumptions about static addresses")
            ).merge(
                wrapWithCVL(assumePrecompiledAddressesAreStatic(),
                    "establish addresses of precompiled contracts")
            ).merge(
                if (Config.AssumeContractsAreUnique.get()) {
                    wrapWithCVL(ensureUniqueContractAddresses(), "assumptions about uniqueness of contracts' addresses")
                } else {
                    wrapWithCVL(CommandWithRequiredDecls(TACCmd.Simple.NopCmd), "making no assumptions about uniqueness of contracts' addresses")
                }
            ).merge(
                // Add linking connections
                wrapWithCVL(resolveLinks(), "static links")
            ).merge(
                wrapWithCVL(ensureZeroBalancesForClones(), "cloned contracts have no balances")
            ).merge(
                wrapWithCVL(initializeConstantImmutables(), "Linked immutable setup")
            ).merge(
                wrapWithCVL(constrainNonConstImmutables(), "Constrain immutables")
            ).merge(
                wrapWithCVL(establishEquivalenceOfExtensionContractImmutables(), "establish equivalence of extension and base contract immutables")
            ).letIf(Config.RequireInvariantsPreRuleSemantics.get()) {
                it.merge(wrapWithCVL(CommandWithRequiredDecls<TACCmd.Spec>(listOf(TACCmd.Simple.AnnotationCmd(CVL_ASSUME_INVARIANT_TARGET))), "Assuming all requireInvariants commands globally"))
            }

        return codeFromCommandVarWithDecls(StartBlock, wrapWithCVL(startBlockEnvSetup, "Setup"), ruleName)
    }

    /** Generates TAC code for the requirement.
     * Associates each statement with the matching TAC code.
     * Call expressions are converted to empty code block but with function pointer indication.
     *   (We currently assume call expressions do not appear in internal nodes of the Expression AST.)
     * If statements are converted to TAC code computing the condition expression, ending with jumpi to TAC codes of then/else.
     * */
    fun compileRule(
        rule: CVLSingleRule,
        ruleCompilationMethodFilter: RuleCompilationMethodFilter,
        generateSetupCode: Boolean = true
    ): ParametricInstantiation<CVLTACProgram> {
        val allocatedTACSymbols = this.allocatedTACSymbols.nestedScope()
        val ruleStatsRecorder =
            ElapsedTimeStats().startMeasuringTimeOf(ruleCompilationTag)

        /** note: may be different from [CVLCompiler.ruleName]! */
        val ruleName = rule.parentIdentifier?.displayName?.let { directParentName ->
            getParentsNames(rule, directParentName)
        } ?: rule.declarationId
        rule.params.forEach{ p ->
            allocatedTACSymbols.extendWithCVLVariable(p.id, p.type, CVLDefinitionSite.Rule(p.range))
        }

        val methodParamToCandidates = methodParamToInstantiationCandidates(rule, ruleCompilationMethodFilter)

        val inst = mutableMapOf<TACSymbol.Var, Set<ITACMethod>>()
        val parametricInstantiations = mutableListOf<ParametricInstantiation<CVLTACProgram>>()

        for ((methodParam, viableCandidates) in methodParamToCandidates) {
            val tacVarOfParam = allocatedTACSymbols.get(methodParam.id)

            inst[tacVarOfParam] = viableCandidates

            val compilation = compileMethodParamInstantiations(methodParam, tacVarOfParam, viableCandidates)
            parametricInstantiations.add(compilation)
        }

        val env = CompilationEnvironment(methodInstantiations = inst) // TODO(jtoman): maybe stuff the allocated tac in here?

        val startBlockNormalizedTACCode = if (generateSetupCode) {
            generateRuleSetupCode()
        } else {
            CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toProg("dummy", env)
        }.prependToBlock0(
            // assume types for rule parameters and havoc them
            wrapWithCVL(ruleParamSetup(rule.params, allocatedTACSymbols), "rule parameters setup")
        )

        val startBlockNormalizedTACCodeWithOpts = getSimple(startBlockNormalizedTACCode)

        // wrap each command
        val ruleCommands = wrapCmdWithLabels(CVLCmd.Composite.Block(rule.range, rule.block, rule.scope))

        logger.info { "Rule $ruleName compile commands" }
        // compile rule body
        val compiledCVLCommands = ruleCommands.map { cmd ->
            val codeForCmd = compileCommand(cmd, allocatedTACSymbols, compilationEnvironment = env)
            logger.trace { "For cmd $cmd got code $codeForCmd" }
            codeForCmd
        }

        logger.info { "Rule $ruleName: Merge header and body" }
        // merge setup of environment with the rule body
        val ruleCode = ParametricMethodInstantiatedCode.merge(
            listOf(startBlockNormalizedTACCodeWithOpts) + parametricInstantiations + compiledCVLCommands, ruleName
        )

        ruleCode.withMethodParamInsts.parallelStream()
            .flatMap { it.tacCode.parallelLtacStream() }
            .filter { it.maybeAnnotation(RETURN_VALUE) != null }
            .findFirst().ifPresent { lc ->
                throw CertoraInternalException(
                    CertoraInternalErrorType.CVL_COMPILER,
                    "Code generation left a return annotation command--$lc--these should all be removed before code generation is complete"
                )
            }

        logger.debug { "Rule checking code has size ${ruleCode.withMethodParamInsts.size}" }

        logger.info { "Rule $ruleName: Add method info headers and hooks" }
        val toRet = ruleCode
            .removeMethodParamHavocs()

        ruleStatsRecorder.endMeasuringTimeOf(ruleCompilationTag)
        recordAggregatedTransformationStats(ruleStatsRecorder, ruleName)

        return toRet
    }

    fun compileHook(hook: CVLHook, parentCallId: Int): Pair<ParametricInstantiation<CVLTACProgram>, TACSymbolAllocation> {
        val newAllocatedTACSymbols = allocatedTACSymbols.nestedScope()
        val compilationEnvironment = CompilationEnvironment(parentCallId)
        val commands = hook.block.map {
            val commandToCompile = assertModifier.cmd(it).force()
            compileCommand(commandToCompile, newAllocatedTACSymbols, compilationEnvironment)
        }

        return ParametricMethodInstantiatedCode.merge(commands, "hook_body") to newAllocatedTACSymbols
    }

    fun compileInvariantCmds(withParams: List<CVLParam>, params: List<CVLParam>, assertOrAssumeInv: List<CVLCmd>, parentCallId: Int): ParametricInstantiation<CVLTACProgram> {
        val newAllocatedTACSymbols = allocatedTACSymbols.nestedScope()
        val compilationEnvironment = CompilationEnvironment(parentCallId)

        //Generate code block for parameters
        (withParams + params).forEach{ p ->
            newAllocatedTACSymbols.extendWithCVLVariable(p.id, p.type, CVLDefinitionSite.Rule(p.range))
        }
        //Only havoc param in withParams, i.e. with(env e), but not regular params of the invariant.
        val paramDecls = ruleParamSetup(params, newAllocatedTACSymbols){ p -> p in withParams}
        val startBlockNormalizedTACCode = codeFromCommandVarWithDecls(compilationEnvironment.newBlockId(), wrapWithCVL(paramDecls, "Invariant Parameters"), ruleName)

        //Compile Assert / Assume of the actual invariant
        val commands = assertOrAssumeInv.map {
            compileCommand(it, newAllocatedTACSymbols, compilationEnvironment)
        }

        return ParametricMethodInstantiatedCode.merge(
                listOf(getSimple(startBlockNormalizedTACCode)) + ParametricMethodInstantiatedCode.merge(commands, "inlined invariant"), ruleName
           )

    }

    private val castExprModifier = object : CVLExpTransformer<Nothing> {
        override fun castExpression(exp: CVLExp.CastExpr): CollectingResult<CVLExp.CastExpr, Nothing> {
            return exp.copy(inCVLBlockId = Allocator.getFreshId(Allocator.Id.ASSERT_IN_CVL_BLOCK)).lift()
        }
    }

    // assert commands inside CVL functions/hooks could lead to issues in multi_assert mode,
    // as all invocations will share the same description and location.
    // a quick and not-too-painful workaround is to attach a small unique id to the end of the assert message.
    // can this break caching for a rule? I guess that potentially yes.
    private val assertModifier = object : CVLCmdTransformer<Nothing>(castExprModifier) {

        override fun assertCmd(cmd: CVLCmd.Simple.Assert): CollectingResult<CVLCmd, Nothing> {
            return cmd.copy(description = "${cmd.descriptionOrDefault} (uid ${Allocator.getFreshId(Allocator.Id.ASSERT_IN_CVL_BLOCK)})").lift()
        }
    }

    private fun initializeReadTracking(): CommandWithRequiredDecls<TACCmd.Spec> {
        // ok to omit transient storage as it has no read tracking as of now
        return scene.getStorageUniverse().values.flatMap {
            (it as StorageInfoWithReadTracker).storageVariables.values.filterNotNull().map {
                CommandWithRequiredDecls(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        it,
                        rhs = exprFact.resetStore(Tag.WordMap)
                    ), it
                )
            }
        }.let(CommandWithRequiredDecls.Companion::mergeMany)
    }

    /** a set of all candidate functions for instantiating a rule's method parameters */
    private fun getParametricTargets() = scene.getContracts().filterIsInstance<IContractWithSource>().flatMapToSet { (it as IContractClass).getStandardMethods() }

    /**
     * returns a map such that, for each method parameter in [rule], the parameter is matched with
     * a set of parametric method targets that are viable instantiations of that parameter.
     * works by first fetching a set of all viable candidates for the entire rule, then narrowing it down
     * for a specific method param using [ruleCompilationMethodFilter].
     *
     * also handles error cases (by throwing).
     */
    private fun methodParamToInstantiationCandidates(
        rule: CVLSingleRule,
        ruleCompilationMethodFilter: RuleCompilationMethodFilter
    ): Map<CVLParam, Set<ITACMethod>> {
        val methodParams = rule.params.filter { it.type == EVMBuiltinTypes.method }
        val allCandidates: Set<ITACMethod> = getParametricTargets()

        if (allCandidates.isEmpty() && methodParams.isNotEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "no candidate functions to instantiate parametric rule"
            )
        }

        return methodParams.associateWith { methodParam ->
            val viableCandidates = allCandidates
                .filterToSet { candidate -> ruleCompilationMethodFilter.filter(candidate, methodParam) }
                .filterToSet { candidate ->
                    Config.includeEmptyFallback.get() || !methodIsEmptyFallback(candidate.name, candidate.code as CoreTACProgram)
                }

            if (viableCandidates.isEmpty() && !rule.allowUninstantiated()) {
                throw CertoraException(
                    CertoraErrorType.NO_MATCHING_METHOD,
                    "could not find a match for method parameter ${methodParam.id}"
                )
            }

            viableCandidates
        }
    }

    /** generates code for instantiating [methodParam] with each method in [methods] */
    private fun compileMethodParamInstantiations(
        methodParam: CVLParam,
        tacVarOfParam: TACSymbol.Var,
        methods: Set<ITACMethod>
    ): ParametricInstantiation<CVLTACProgram> {
        val methodParamId = methodParam.id
        return methods.map { candidate ->
            // add assignment commands for all the fields of the method struct
            val methodInfo = candidate.evmExternalMethodInfo!!
            codeFromCommandVarWithDecls(
                // we don't have a compilation environment yet, so let's use the base one
                RootCallIdContext.newBlockId(),
                methodInfo.toTACStruct(
                    tacVarOfParam,
                    myInstanceId,
                    scene.getContract(methodInfo.contractInstanceId).addressSym as TACSymbol.Var
                ),
                methodParamId
            ).toInstantiation(tacVarOfParam, methodInfo)
        }.commute()
    }

    /** conditions for which we allow a parametric rule to have method params with no matching instantiating methods */
    private fun CVLSingleRule.allowUninstantiated(): Boolean {
        val ruleType = ruleType
        return when {
            // allow empty invariant preserves
            ruleType is SpecType.Single.InvariantCheck.GenericPreservedInductionStep -> true

            // some builtin rules are allowed to have an empty function set
            ruleType is SpecType.Single.BuiltIn && BuiltInRuleCustomChecker.fromBirType(ruleType).functionSetCanBeEmpty -> true

            // if we ignore view/pure functions, we also don't care if they fail to instantiate
            Config.IgnoreViewFunctionsInParametricRules.get() -> true

            else -> false
        }
    }

    private fun initializeConstantImmutables(): CommandWithRequiredDecls<TACCmd.Simple> {
        return scene.getContracts().mapNotNull {
            (it as? IContractWithSource)
        }.filter {
            it !is IClonedContract
        }.map { c ->
            ImmutableInstrumenter.getImmutableInitializer(c, scene)
        }.let(CommandWithRequiredDecls.Companion::mergeMany)
    }

    private fun constrainNonConstImmutables(): CommandWithRequiredDecls<TACCmd.Spec> {
        return scene.getContracts().mapNotNull {
            (it as? IContractWithSource)
        }.filter {
            it !is IClonedContract // copied this from [initializeConstantImmutables], not sure why we need this filter
        }.map { c ->
            ImmutableInstrumenter.constrainNonconstImmutables(c, this)
        }.let(CommandWithRequiredDecls.Companion::mergeMany)
    }

    private fun establishEquivalenceOfExtensionContractImmutables(): CommandWithRequiredDecls<TACCmd.Spec> {
        return scene.getContracts().mapNotNull {
            (it as? IContractWithSource)
        }.filter {
            it !is IClonedContract // copied this from [initializeConstantImmutables], not sure why we need this filter
        }.map { c ->
            ImmutableInstrumenter.establishEquivalenceOfExtensionContractImmutables(c, this)
        }.let(CommandWithRequiredDecls.Companion::mergeMany)
    }

    private fun ensureZeroBalancesForClones(): CommandWithRequiredDecls<TACCmd.Simple> {
        return scene.getContracts().mapNotNull {
            (it as? IClonedContract)?.addressSym
        }.map {
            val decls = mutableSetOf<TACSymbol.Var>()
            decls.add(TACKeyword.BALANCE.toVar())
            if (it is TACSymbol.Var) {
                decls.add(it)
            }
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.WordStore(
                        base = TACKeyword.BALANCE.toVar(),
                        loc = it as TACSymbol,
                        value = TACSymbol.lift(0)
                    )
                ), decls
            )
        }.fold(CommandWithRequiredDecls()) { curr, nxt -> curr.merge(nxt) }
    }

    private fun countSetup(): CommandWithRequiredDecls<TACCmd.Simple> {
        val contracts = scene.getContracts().mapNotNullTo(mutableSetOf()) {
            (it as? IClonedContract)?.sourceContractId
        }
        return CommandWithRequiredDecls.mergeMany(contracts.map {
            CommandWithRequiredDecls(
                TACCmd.Simple.WordStore(
                    base = TACKeyword.CONTRACT_COUNT.toVar(),
                    loc = it.asTACSymbol(),
                    value = TACSymbol.lift(0)
                ), TACKeyword.CONTRACT_COUNT.toVar()
            )
        })
    }

    fun declareContractsAddressVars(): CommandWithRequiredDecls<TACCmd.Simple> {
        val currContractVar = allocatedTACSymbols.get(CVLKeywords.CURRENT_CONTRACT, Tag.Int)
        return CommandWithRequiredDecls(listOf(
            // Assign the "currentContract" the correct address
            TACCmd.Simple.AssigningCmd.AssignExpCmd(currContractVar, myAddress as TACSymbol.Var)
        ),
            scene.getContracts().mapNotNullToSet { it -> it.addressSym as? TACSymbol.Var } + currContractVar
        )
    }

    /*
     * Make sure that links are well-defined, by setting relevant TAC commands to connect between the relevant contracts
     */
    private fun resolveLinks(): CommandWithRequiredDecls<TACCmd.Spec> {
        val allContractsLinksCommands = mutableListOf<CommandWithRequiredDecls<TACCmd.Spec>>()
        val contracts = scene.getContracts()

        contracts.forEach { contract ->
            val storageVars = (scene.getStorageUniverse()[contract.instanceId] as? StorageInfoWithReadTracker)?.storageVariables?.keys
                ?: throw IllegalStateException("Expected Storage Variables object to exist in contract $contract")

            val stateLinks = contract.getContractStateLinks()
            if (stateLinks == null) {
                logger.info { "Contract ${contract.name} does not contain state link information, assuming there are no links to resolve" }
            } else if (stateLinks.isEmpty()) {
                logger.info { "Nothing to link in ${contract.name}" }
            } else {
                val contractLinksCmdsWithStorageAnalysis =
                    resolveLinksWithStorageAnalysis(storageVars, stateLinks)
                if (contractLinksCmdsWithStorageAnalysis.isNotEmpty()) {
                    // if we succeeded with storage analysis, just add the inferred link commands and move onward
                    allContractsLinksCommands.addAll(contractLinksCmdsWithStorageAnalysis)
                } else {
                    // storage analysis could not help here, so we go for the basic yet potentially less precise methods
                    val contractLinksCmdsWithoutStorageAnalysis = resolveLinksWithoutStorageAnalysis(
                        storageVars,
                        stateLinks,
                        scene
                    )
                    allContractsLinksCommands.addAll(contractLinksCmdsWithoutStorageAnalysis)
                }

                // attempt to add struct link assumptions as well. Note legacy struct link info is not linked here
                val structLinkCmds = storageVars.mapNotNull { storageVar ->
                    val fieldName = storageVar.singleDisplayPathOrNull()?.let { it as? DisplayPath.FieldAccess }?.field ?: return@mapNotNull null
                    val targetContract = stateLinks.structLinkingInfo[fieldName] ?: return@mapNotNull null
                    val contractAddress = scene.getContract(targetContract).addressSym as TACSymbol
                    val tmpVar = TACKeyword.TMP(Tag.Bool, "structLinkSetup")
                    wrapWithCVL(
                        CommandWithRequiredDecls(
                            listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = tmpVar,
                                    rhs = TACExpr.BinRel.Eq(storageVar.asSym(), contractAddress.asSym())
                                ),
                                TACCmd.Simple.AssumeCmd(tmpVar, "struct linking")
                            ),
                            tmpVar
                        ),
                        "Struct link ${contract.name}:${fieldName}=${scene.getContract(targetContract).name}"
                    )
                }
                allContractsLinksCommands.addAll(structLinkCmds)
            }
        }
        return CommandWithRequiredDecls.mergeMany(allContractsLinksCommands)
    }


    private fun storageVarIsContractOrAddress(storageVar: TACStorageSlot) = (storageVar.storageType as? TACStorageType.IntegralType)?.descriptor.let { descr ->
        descr is EVMTypeDescriptor.EVMContractTypeDescriptor || descr == EVMTypeDescriptor.address
    }

    /*
     * Resolve the links addresses when storage analysis succeeded. In this case, we have the data already sorted and easy to access.
     * If the return value is an empty list, it means this stage has failed, and the no-storage-analysis will be executed.
     */
    private fun resolveLinksWithStorageAnalysis(
        storageVars: Set<TACSymbol.Var>,
        contractWithStateLinks: ContractWithStateLinkInfo
    ): List<CommandWithRequiredDecls<TACCmd.Spec>> {
        if (storageVars.isEmpty()) {
            return emptyList()
        }

        /* BG - 20221219
         * Vyper doesn't bitmask contract addresses down to 160 bits.  The problem is that just allowing 256 bit wide
         * contract addresses with upper zero bits also runs against the grain of our ability to detect a potential
         * bug in Solidity contracts where non-address typed data is improperly used as a contract address.
         *
         * This is an ugly workaround until we can hopefully extract enough information from the Vyper compiler to do something better.
         */
        val allowWordWidthContractAddresses =
            // case 1: vyper is allowed to do full word linking
            scene.getContracts().any {
                it is IContractWithSource && it.src.lang == SourceLanguage.Vyper
            }
                || Config.FullWordLinking.get() // case 2: allowed from config

        // we have storage variables
        val contractLinksCmds =
            // all or nothing
            contractWithStateLinks.stateLinks.entries.monadicMap { (linkedContractSlot: BigInteger, linkedContractId: BigInteger) ->
                val storageSortVariables = storageVars.filter {
                    SCALARIZATION_SORT in it.meta &&
                        it.meta[SCALARIZATION_SORT] != ScalarizationSort.UnscalarizedBuffer &&
                        BIT_WIDTH in it.meta &&
                        (it.meta[BIT_WIDTH] == EVM_ADDRESS_SIZE ||
                            (allowWordWidthContractAddresses && it.meta[BIT_WIDTH] == EVM_BITWIDTH256))
                }

                fun extractBaseSlot(sSort: ScalarizationSort): BigInteger? = when (sSort) {
                    is ScalarizationSort.Packed -> {
                        extractBaseSlot(sSort.packedStart)
                    }

                    is ScalarizationSort.Split -> {
                        sSort.idx
                    }

                    ScalarizationSort.UnscalarizedBuffer -> {
                        // We assume that this case represents the case of split mappings;
                        // currently, we don't support links nested in maps, but only on value types storage fields.
                        null
                    }
                }

                val matchingSplitStorageVar: TACSymbol.Var =
                    storageSortVariables.filter { sortVariable: TACSymbol.Var ->
                        val storageSort: ScalarizationSort =
                            sortVariable.meta.find(SCALARIZATION_SORT) ?: throw IllegalStateException(
                                "Expected to find STORAGE_SORT key in ${sortVariable.meta}"
                            )
                        extractBaseSlot(storageSort) == linkedContractSlot
                    }.let { res ->
                        // two address type fields cannot be packed together in the same slot, so we expect the list to
                        // have exactly one element.
                        val singleRes = res.singleOrNull()
                        if (singleRes == null) {
                            logger.warn {
                                "Expected to have one matching storage variable for slot $linkedContractSlot" +
                                    " in ${contractWithStateLinks.contract.name}, got $res out of ${storageSortVariables.sortedWith(TACSymbol.Var.byName)}"
                            }
                            // will fall through to no-storage analysis case
                            return@monadicMap null
                        } else {
                            singleRes
                        }
                    }
                val contractAddress: TACSymbol = scene.getContract(linkedContractId).addressSym as TACSymbol
                val tmpVar: TACSymbol.Var = TACKeyword.TMP(Tag.Bool, "linkSetup")
                val assignCondCmd = TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = tmpVar,
                    rhs = TACExpr.BinRel.Eq(matchingSplitStorageVar.asSym(), contractAddress.asSym())
                )
                val decls = mutableSetOf<TACSymbol.Var>()
                decls.add(tmpVar)
                decls.add(matchingSplitStorageVar)
                if (contractAddress is TACSymbol.Var) {
                    decls.add(contractAddress)
                }
                // getting the slot name for the print
                val bestSlotName = contractWithStateLinks.contract.getStorageLayout()?.values?.filter { storageVar ->
                    storageVar.slot == linkedContractSlot
                }?.singleOrNull(::storageVarIsContractOrAddress)?.label ?: linkedContractSlot
                wrapWithCVL(
                    CommandWithRequiredDecls<TACCmd.Spec>(
                        listOf(
                            assignCondCmd, TACCmd.Simple.AssumeCmd(tmpVar, "linking")
                        ),
                        decls
                    ),
                    "Link ${contractWithStateLinks.contract.name}:${bestSlotName}=${scene.getContract(linkedContractId).name}"
                )
            } ?: emptyList()

        return contractLinksCmds
    }

    internal fun ensureBitWidth(t: CVLType.PureCVLType, id: TACSymbol.Var): CommandWithRequiredDecls<TACCmd.Spec> =
        ensureBitWidth(t, id.asSym()).merge(id)

    private fun ensureBitWidth(t: CVLType.PureCVLType, toConstrain: TACExpr): CommandWithRequiredDecls<TACCmd.Spec> =
        when (t) {
            is CVLType.PureCVLType.Struct -> t.fields.fold(CommandWithRequiredDecls<TACCmd.Spec>()) { acc, member ->
                if (member.cvlType !is CVLType.PureCVLType.Struct && member.cvlType !is CVLType.PureCVLType.CVLValueType) {
                    return@fold acc
                }
                // TODO: figure out how to use type checked factory
                acc.merge(ensureBitWidth(member.cvlType, TACExpr.StructAccess(toConstrain, member.fieldName, member.cvlType.toTag())))
            }

            is CVLType.PureCVLType.CVLValueType -> {
                ensureValueBitwidth(t, toConstrain)
            }

            else -> throw CertoraInternalException(CertoraInternalErrorType.TAC_TYPING, "illegal type to ensure bidwidth: $t")
        }

    internal fun ensureValueBitwidth(valueType: CVLType.PureCVLType.CVLValueType, toConstrain: TACExpr): CommandWithRequiredDecls<TACCmd.Spec> {
        return when (valueType) {
            is CVLType.PureCVLType.Primitive -> ensureBitWidth(valueType, toConstrain)
            is CVLType.PureCVLType.UserDefinedValueType -> ensureBitWidth(valueType.base, toConstrain)
            is CVLType.PureCVLType.Enum -> {
                val tmpBool = CVLReservedVariables.certoraTmp.toVar(Tag.Bool).withSuffix("enumRange")
                val exprFact = TACExprFactTypeChecked(tacSymbolTable)
                CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = tmpBool,
                        exprFact.Ge(toConstrain, BigInteger.ZERO.asTACSymbol().asSym()),
                        exprFact.Le(toConstrain, valueType.maxValue.toBigInteger().asTACSymbol().asSym()),
                        exprFact::LAnd
                    ),
                    TACCmd.Simple.AssumeCmd(tmpBool, "ensureValueBitwidth")
                ), setOf(tmpBool))

            }
        }
    }

    private fun ensureBitWidth(simpleType: CVLType.PureCVLType.Primitive, id: TACExpr): CommandWithRequiredDecls<TACCmd.Spec> {
        val exprFact = TACExprFactTypeChecked(tacSymbolTable)

        val decls = mutableSetOf<TACSymbol.Var>()
        val tmpBool = CVLReservedVariables.certoraTmp.toVar(Tag.Bool).withSuffix("Bool")
        decls.add(tmpBool)

        fun generateUnsignedRange(width: Int): List<TACCmd.Simple> {
            return listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    tmpBool,
                    exprFact.Le(id, TACSymbol.Const(SignUtilities.maxUnsignedValueOfBitwidth(width)).asSym()),
                    exprFact.Ge(id, TACSymbol.Const(BigInteger.ZERO).asSym()),
                    exprFact::LAnd
                ),
                TACCmd.Simple.AssumeCmd(tmpBool, "UnsignedRange")
            )
        }

        val cmds = when (simpleType) {
            is CVLType.PureCVLType.Primitive.UIntK -> generateUnsignedRange(simpleType.k)
            is CVLType.PureCVLType.Primitive.IntK -> {
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        tmpBool,
                        exprFact.Le(
                            id,
                            TACSymbol.Const(SignUtilities.maxSignedValueOfBitwidth(simpleType.k), Tag.Int).asSym()
                        ),
                        exprFact.Ge(
                            id,
                            TACSymbol.Const(SignUtilities.minSignedValueOfBitwidth(simpleType.k), Tag.Int).asSym()
                        ),
                        exprFact::LAnd
                    ),
                    TACCmd.Simple.AssumeCmd(tmpBool, "SignedRange")
                )
            } // ignore the sign bit
            is CVLType.PureCVLType.Primitive.HashBlob -> generateUnsignedRange(Config.VMConfig.registerBitwidth)
            is CVLType.PureCVLType.Primitive.CodeContract,
            is CVLType.PureCVLType.Primitive.AccountIdentifier -> generateUnsignedRange(Config.VMConfig.maxAddress.bitLength())

            is CVLType.PureCVLType.Primitive.BytesK -> {
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        tmpBool,
                        exprFact.Eq(
                            exprFact.Mod(
                                id,
                                TACSymbol.Const(BigInteger.TWO.pow(Config.VMConfig.registerBitwidth - simpleType.bitWidth)).asSym()
                            ),
                            TACSymbol.Const(BigInteger.ZERO).asSym()
                        )
                    ),
                    TACCmd.Simple.AssumeCmd(tmpBool, "BytesRange")
                )
            }

            is CVLType.PureCVLType.Primitive.Bool, // no constraint needed todo really?
            is CVLType.PureCVLType.Primitive.Mathint, // no constraint needed
            is CVLType.PureCVLType.Primitive.NumberLiteral -> listOf(TACCmd.Simple.NopCmd)
        }

        return CommandWithRequiredDecls<TACCmd.Spec>(cmds, decls)
    }

    private fun ruleParamSetup(
        args: List<CVLParam>,
        allocatedTACSymbols: TACSymbolAllocation,
        havocParam: (CVLParam) -> Boolean = { _ -> true }
    ): CommandWithRequiredDecls<TACCmd.Spec> {

        val cmds = mutableListOf<TACCmd.Spec>()
        val decls = mutableSetOf<TACSymbol.Var>()

        args
            .forEachIndexed { idx, arg ->
                val type = arg.type
                val tag = type.toTag()
                val paramAsVar = allocatedTACSymbols.get(arg.id, tag)

                if (arg.type is CVLType.PureCVLType.CVLValueType || arg.type is CVLType.PureCVLType.Struct) {
                    decls.add(paramAsVar)
                    // havoc it
                    if(havocParam(arg)) {
                        cmds.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(paramAsVar))
                    }
                    // assume types
                    val cmdsToAdd = if (tag != Tag.Bool) {
                        ensureBitWidth(type, paramAsVar)
                    } else {
                        CommandWithRequiredDecls<TACCmd.Spec>()
                    }

                    cmdsToAdd.let { (_cmds, _decls) ->
                        cmds.addAll(_cmds)
                        decls.addAll(_decls)
                    }
                } else if (arg.type is CVLType.PureCVLType.CVLArrayType) {
                    val setup = getArrayAssumptions(arg.id, arg.type as CVLType.PureCVLType.CVLArrayType, allocatedTACSymbols)
                    decls.addAll(setup.varDecls)
                    cmds.addAll(setup.cmds)
                }
                if(havocParam(arg)) {
                    cmds.add(SnippetCmd.CVLSnippetCmd.CVLArg.AnyArg(ROOT_CALL_ID, idx, paramAsVar, arg).toAnnotation())
                }
                cmds.add(TraceMeta.VariableDeclarationCmd(
                    paramAsVar,
                    t = type,
                    type = TraceMeta.DeclarationType.Parameter(sourceName = arg.id)
                ))
            }

        return CommandWithRequiredDecls<TACCmd.Spec>(cmds, decls)
    }

    internal fun fetchGhostDeclaration(id: String): CVLGhostDeclaration? {
        val scope = CVLScope.AstScope // ghosts are always top-level declarations
        val symbol = symbolTable.lookUpNonFunctionLikeSymbol(id, scope)
            ?: symbolTable.lookUpFunctionLikeSymbol(id, scope)
        return symbol?.symbolValue as? CVLGhostDeclaration
    }

    companion object {
        @Suppress("FunctionName")
        object TraceMeta {
            @KSerializable
            @Treapable
            sealed interface DeclarationType : AmbiSerializable {

                val sourceName: String
                @KSerializable
                @Treapable
                data class Variable(override val sourceName: String) : DeclarationType

                @KSerializable
                @Treapable
                data class Parameter(override val sourceName: String) : DeclarationType
            }

            /**
             * Indicates that the value with identity [v] is nondeterministically initialized with type [t]. In other
             * words, this is a "root" of data flow in a CVL specification.
             */
            @KSerializable
            data class VariableDeclaration(val v: ValueIdentity, val t: CVLType.PureCVLType, val type: DeclarationType, val fields: Map<List<DataField>, TACSymbol.Var>?) : AmbiSerializable, TransformableVarEntityWithSupport<VariableDeclaration> {
                constructor(s: TACSymbol.Var, t: CVLType.PureCVLType, type: DeclarationType) : this(ValueIdentity.TACVar(s), t, type, null)
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): VariableDeclaration {
                    return VariableDeclaration(v.transformSymbols(f), t, type, fields?.mapValues { f(it.value) })
                }

                override val support: Set<TACSymbol.Var>
                    get() = v.support + fields?.values.orEmpty()

                companion object {
                    val META_KEY = MetaKey<VariableDeclaration>("cvl.trace.declaration", erased = true)
                }
            }

            @Suppress("FunctionName")
            fun VariableDeclarationCmd(s: TACSymbol.Var, t: CVLType.PureCVLType, type: DeclarationType) = (VariableDeclaration.META_KEY to VariableDeclaration(s, t, type)).toAnnotation(Config.TraceCVLAssignments.get())

            /**
             * A universal representation for some logical values "name" in the program. After CVL compilation, there
             * is a direct correspondence between the tac variables in the program and the logical names of values in the program.
             * Thus, at the outset, we only generate [ValueIdentity.TACVar]. However, after the CVL to Simple compilation,
             * the unique tac variable which corresponds to some tac values disappear: in particular, variables with
             * [Tag.TransientTag] are "exploded" into their "family of variables" representation. This is the case
             * for [CVLType.PureCVLType.CVLArrayType] and [CVLType.PureCVLType.Struct] typed variables in CVL (among others).
             * Moreover, after the CVL to Simple pipeline tac variables tagged with [Tag.TransientTag] cannot appear *anywhere* in the
             * program, even in annotations. Thus, during [CVLToSimpleCompiler] compilation, we transform these
             * [TACVar] instances into [CVLVar] instances, extracting just the name of the variable. Thus, [CVLVar] instances
             * are like "shadows" or "ghosts" of the original CVL variables that *used* to exist as a TAC variable
             * before simple compilation. Having these ghosts around is still useful to trace data flow in terms of CVL variables, instead
             * of trying to reason about the exploded family of variables representations.
             */
            @KSerializable
            @Treapable
            sealed interface ValueIdentity : TransformableVarEntityWithSupport<ValueIdentity>, AmbiSerializable {
                @KSerializable
                @Treapable
                data class CVLVar(val id: String) : ValueIdentity {
                    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CVLVar {
                        return this
                    }

                    override val support: Set<TACSymbol.Var>
                        get() = treapSetOf()

                }

                @Treapable
                @KSerializable
                data class TACVar(val t: TACSymbol.Var) : ValueIdentity {
                    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): TACVar {
                        return TACVar(f(t))
                    }

                    override val support: Set<TACSymbol.Var>
                        get() = treapSetOf(t)

                }
            }

            /**
             * Representation of a logical "step" through a datastructure in CVL wrt to some root (usually a [ValueIdentity]).
             */
            @Treapable
            sealed interface CVLAccessPathStep : AmbiSerializable {
                /**
                 * Represents following a struct field with name [f]
                 */
                @KSerializable
                data class Field(val f: String) : CVLAccessPathStep

                /**
                 * Represents reading the length of an array.
                 */
                @KSerializable
                object ArrayLength : CVLAccessPathStep {
                    fun readResolve(): Any = ArrayLength
                    override fun hashCode() = utils.hashObject(this)

                    override fun toString() = "ArrayLength"
                }

                /**
                 * Represents reading an element of an array with the logical index in variable [i].
                 */
                @KSerializable
                data class ArrayElem(val i: TACSymbol.Var) : CVLAccessPathStep
            }

            /**
             * The payload of an annotation expressing that [lhs] holds the result of traversing the fields in [ap] (which must
             * be non-empty) starting from [base].
             *
             * This allows us to recover the high-level behavior of the program post compilation: due to the complexities of
             * compiling accesses into complex data structures, it is not easy to recover from:
             * ```
             * b = i * 0x20
             * t1 = a_data[b]
             * t2 = a_data_fieldWhatever[t1 + 0x40]
             * ```
             * that `t2` holds the result of `a[i].Whatever`
             *
             * In some sense this duplicates some of the information recoverable from CVL_EXP meta, but is more tightly
             * tied to the actual code: these annotations will provide the unique names chosen for the tac compilation, instead
             * of the CVL names.
             */
            @KSerializable
            data class ValueTraversal(val lhs: ValueIdentity, val ap: List<CVLAccessPathStep>, val base: ValueIdentity) : AmbiSerializable, TransformableVarEntityWithSupport<ValueTraversal> {
                constructor(lhs: TACSymbol.Var, ap: List<CVLAccessPathStep>, base: TACSymbol.Var) : this(ValueIdentity.TACVar(lhs), ap, ValueIdentity.TACVar(base))

                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ValueTraversal {
                    return ValueTraversal(lhs.transformSymbols(f), ap.map { ap ->
                        when(ap) {
                            is CVLAccessPathStep.ArrayElem -> CVLAccessPathStep.ArrayElem(f(ap.i))
                            CVLAccessPathStep.ArrayLength,
                            is CVLAccessPathStep.Field -> ap
                        }
                    }, base.transformSymbols(f))
                }

                override val support: Set<TACSymbol.Var>
                    get() = base.support + lhs.support + ap.mapNotNullToTreapSet {
                        (it as? CVLAccessPathStep.ArrayElem)?.i
                    }

                companion object {
                    val META_KEY = MetaKey<ValueTraversal>("cvl.trace.traversal", erased = true)
                }

            }

            fun ValueTraversalCmd(
                    lhs: TACSymbol.Var, ap: List<CVLAccessPathStep>, base: TACSymbol.Var
            ) = (ValueTraversal.META_KEY to ValueTraversal(lhs, ap, base)).toAnnotation(Config.TraceCVLAssignments.get())

            /**
             * An annotation inserted by the [CVLToSimpleCompiler] indicating that an assignment between two complex CVL values
             * was performed. In principle we could represent this with a [ValueTraversal] with an empty access path, but is
             * useful to know that the [ValueTraversal.ap] field is always non-empty.
             *
             * NB that we do *not* need an equivalent [spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar] to
             * [spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar]
             * variant, because that will just appear as a regular [vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd] post
             * simple simplification.
             */
            @KSerializable
            @Treapable
            data class CVLMovement(val dst: ValueIdentity.CVLVar, val src: ValueIdentity.CVLVar) : AmbiSerializable {
                companion object {
                    val META_KEY = MetaKey<CVLMovement>("cvl.trace.data.movement", erased = true)
                }
            }

            fun CVLMovementCmd(dst: ValueIdentity.CVLVar, src: ValueIdentity.CVLVar) = (CVLMovement.META_KEY to CVLMovement(dst, src)).toAnnotation(Config.TraceCVLAssignments.get())

            /**
             * Indicates that a CVL value with identity [s] is used as the argument at offset [rootOffset]
             * to an external call with ID [callId]. The type of the argument being converted to is
             * [targetType]. [fields] is initially null and filled in later by the [CVLToSimpleCompiler]
             * class: it records the family of variables used to represent the value used as an argument.
             * For example, if the argument was the CVL variable `a` which had type `S[]` where `struct S { uint a; uint b; }`
             * then [fields] would record:
             * ```
             * [ArrayLength] => a_length
             * [ArrayData] -> a_data
             * [ArrayData, StructField(a)] => a_data_fielda
             * [ArrayData, StructField(b)] => a_data_fieldb
             * ```
             *
             * Further, this map is also used for structs, for example, the fields of an `s` with type `S` would be
             * ```
             * [StructField(a)] => s_a
             * [StructField(b)] => s_b
             * ```
             *
             * Note that there is an intentional mismatch with how these data fields are represented for
             * [CVLToSimpleCompiler.arrayRepresentationVars] using the [vc.data.CVLToSimpleCompiler.ArrayReprKey].
             * There we have a distinguished [vc.data.CVLToSimpleCompiler.ArrayReprKey.Length], where as here the length of
             * a "top level" array is represented with just the singleton list [DataField.ArrayLength]. This mismatch is due to
             * the use of this mapping to represent structs as well; in the [CVLToSimpleCompiler.arrayRepresentationVars]
             * case the *only* scalar variable in that family is the array length, so it made sense to single that out. However,
             * because we use this mapping to represent struct fields as well (which can be scalars as well) it doesn't
             * make sense to insist on this distinction (arguably, it doesn't make sense to insist upon it in the
             * [CVLToSimpleCompiler.arrayRepresentationVars] case either).
             */
            @KSerializable
            @GenerateRemapper
            data class ExternalArg(
                val s: ValueIdentity,
                val rootOffset: BigInteger,
                @GeneratedBy(Allocator.Id.CALL_ID) val callId: CallId,
                val targetType: VMTypeDescriptor,
                val sourceType: CVLType.PureCVLType,
                val fields: Map<List<DataField>, TACSymbol.Var>?
            ) : AmbiSerializable, TransformableVarEntityWithSupport<ExternalArg>, RemapperEntity<ExternalArg> {
                constructor(s: TACSymbol.Var, rootOffset: BigInteger, callId: CallId, targetType: VMTypeDescriptor, sourceType: CVLType.PureCVLType) : this(TraceMeta.ValueIdentity.TACVar(s), rootOffset, callId, targetType, sourceType, null)
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ExternalArg {
                    return ExternalArg(s = s.transformSymbols(f), rootOffset = rootOffset, callId = callId, targetType = targetType, fields = fields?.mapValues { (_, v) ->
                        f(v)
                    }, sourceType = sourceType)
                }

                override val support: Set<TACSymbol.Var>
                    get() = s.support + fields?.values.orEmpty()

                companion object {
                    val META_KEY = MetaKey<ExternalArg>("cvl.trace.external", erased = true)
                }
            }

            @Suppress("FunctionName")
            fun ExternalArgCmd(
                s: TACSymbol.Var, rootOffset: BigInteger, callId: CallId, targetType: VMTypeDescriptor, sourceType: CVLType.PureCVLType
            ) = (ExternalArg.META_KEY to ExternalArg(s, rootOffset, callId, targetType, sourceType)).toAnnotation(Config.TraceCVLAssignments.get())
        }

        /**
         * Retrieves a mostly-sensible list of parent declaration IDs based on a given direct parent name, using
         * the scope of the rule. This is hacked together to ensure Presolver TAC HTMLs are linkable from the rule
         * report (e.g. when rules are verified).
         * This function is also used in [RuleCheckResult] to get the report filename.
         */
        fun getParentsNames(rule: ICVLRule, directParentName: String) =
            rule.scope.scopeStack.mapNotNull { scopeItem ->
                (scopeItem as? CVLScope.Item.InvariantScopeItem)?.invariantId
                    ?: (scopeItem as? CVLScope.Item.RuleScopeItem)?.ruleId
            }.let {
                if (directParentName in it) {
                    it
                } else {
                    it.plus(directParentName)
                }
            }.joinToString(OUTPUT_NAME_DELIMITER)
    }

    /*
     * Resolve links when storage analysis has failed, so the input list of created commands is empty.
     * In this case we have to gently extract the link address from the storage variable, that might contain also other variables packed in.
     */
    private fun resolveLinksWithoutStorageAnalysis(
        storageVars: Set<TACSymbol.Var>,
        contractWithStateLinks: ContractWithStateLinkInfo,
        scene: IScene
    ): List<CommandWithRequiredDecls<TACCmd.Spec>> {
        val contract = contractWithStateLinks.contract

        // find whether there is a matching storage variable for the contract holding the linked storage
        fun findMatchingStorageVariables(): List<TACSymbol.Var> {
            return storageVars.filter {
                val storageKey: BigInteger = it.meta.find(TACMeta.STORAGE_KEY)
                    ?: throw IllegalStateException("Expected to find STORAGE_KEY meta in ${it.meta} of contract ${contract.name} address ${contract.instanceId}")
                // When Storage Analysis is unavailable, there should be only one storage var, that should have a matching storage key
                storageKey == contract.instanceId
            }
        }

        val contractLinksCmds =
            contractWithStateLinks.stateLinks
                .map { (linkedContractSlot: BigInteger, linkedContractId: BigInteger) ->
                    val contractToLinkTo = scene.getContract(linkedContractId)
                    val contractAddressVar: TACSymbol.Var = contractToLinkTo.addressSym as TACSymbol.Var
                    val storageLayout = contract.getStorageLayout()
                        ?: if (!Config.FullWordLinking.get()) {
                            throw CertoraException(
                                CertoraErrorType.INVALID_LINKING,
                                "Failed to perform link in contract ${contract.name} to ${contractToLinkTo.name}. " +
                                    "Solidity compiler version may be too old. You may try enabling ${Config.FullWordLinking.userFacingName()}. "
                            )
                        } else {
                            // full word linking is allowed, short-circuit here
                            val matchingStorageVars = findMatchingStorageVariables()
                            // weird stuff! sometimes storage analysis runs without storage layout available,
                            // and we can actually match on one of the split variables.
                            // We cut ourselves some slack here and try our best effort mode...

                            // find the most basic storage map, no splits
                            val storageArray = matchingStorageVars.singleOrNull {
                                it.meta.find(SCALARIZATION_SORT) is ScalarizationSort.UnscalarizedBuffer
                                    && it.meta.find(BIT_WIDTH) == null // filter out the splits for accessing struct values within mappings
                            } ?: throw IllegalStateException("No storage array for contract ${contract.name}")

                            // try to find more precise match on the slot, but otherwise fall back to storageArray
                            val matchingStorageVar = matchingStorageVars.singleOrNull {
                                val meta = it.meta.find(SCALARIZATION_SORT)
                                when (meta) {
                                    is ScalarizationSort.Packed -> meta.packedStart is ScalarizationSort.Split && meta.packedStart.idx == linkedContractSlot
                                    is ScalarizationSort.Split -> meta.idx == linkedContractSlot
                                    ScalarizationSort.UnscalarizedBuffer -> false
                                    null -> false
                                }
                            } ?: storageArray

                            return@map fullWordLinking(matchingStorageVar, linkedContractSlot, contractAddressVar)
                        }

                    val matchingStorageVars = findMatchingStorageVariables()
                    val matchingStorageVar = matchingStorageVars.singleOrNull()
                        ?: run {
                            logger.warn { "Could not find in the contract ${contract.name} a single storage variable with a matching storage key ${contract.instanceId.toString(16)}, got $matchingStorageVars" }
                            val explanationAddendum = if (
                            // if someone declared a library contract explicitly in the scene, this is likely to be false!
                                (contractToLinkTo as? IContractWithSource)?.src?.isLibrary == true
                                // but all sighashes will be computed 0. This is resting on uneven ground, but reporting a better message is valuable
                                || (contractToLinkTo as? IContractWithSource)?.src?.methods?.all { it.sighash == "0" } == true
                            ) {
                                ", which is a library. No need to specify explicit link for library contracts"
                            } else {
                                ""
                            }
                            throw CertoraException(
                                CertoraErrorType.INVALID_LINKING,
                                "Could not link in ${contract.name} to ${contractToLinkTo.name}${explanationAddendum}"
                            )
                        }

                    val storageItemsPackedInRelevantSlot = storageLayout.filter { storageVar ->
                        storageVar.value.slot == linkedContractSlot
                    }

                    val highestOffset: Int =
                        storageItemsPackedInRelevantSlot.maxWithOrNull(Comparator.comparingInt { it.value.offset })?.value?.offset
                            ?: // [storageInRelevantSlot] is empty
                            throw CertoraException(
                                CertoraErrorType.INVALID_LINKING,
                                "The Storage Layout for ${contract.name} does not have $linkedContractSlot as a slot. Please check the specified storage link to ${contractToLinkTo.name}"
                            )

                    val cmds = mutableListOf<TACCmd.Spec>()
                    val decls = mutableSetOf<TACSymbol.Var>()

                    when (storageItemsPackedInRelevantSlot.size) {
                        // the address variable is the only one on this slot, so there is no need for filtering at all and we can copy it as is
                        1 -> fullWordLinking(matchingStorageVar, linkedContractSlot, contractAddressVar).let {
                            cmds.addAll(it.cmds)
                            decls.addAll(it.varDecls)
                        }
                        /*
                    The work-plan:
                        S <- wordLoad(matchingStorageVar, linkedContractSlot)
                        assign X <- ((2^offset)-1) BWAND S
                        assign Y <- ((2^offset+160)-1) BWAND S
                        assume link * (2^offset) + X = Y
                        assume link = linkedContractId
                    */
                        else -> {
                            val sSymbol: TACSymbol.Var = TACKeyword.TMP(Tag.Bit256, "S")
                            val xSymbol: TACSymbol.Var = TACKeyword.TMP(Tag.Bit256, "X")
                            val ySymbol: TACSymbol.Var = TACKeyword.TMP(Tag.Bit256, "Y")
                            val link: TACSymbol.Var = TACKeyword.TMP(Tag.Bit256, "Link").toUnique()

                            val varAddressInSlot = storageItemsPackedInRelevantSlot.values.singleOrNull(::storageVarIsContractOrAddress)
                                ?: throw IllegalStateException("Expected storage slot to hold an address, got $storageItemsPackedInRelevantSlot")


                            val byteOffset: Int = varAddressInSlot.offset
                            val bitOffset: Int = byteOffset * 8
                            val xOffsetMask = MASK_SIZE(bitOffset)
                            val yOffsetMask = MASK_SIZE(bitOffset + 160)
                            val assumeOffsetValue = BigInteger.TWO.pow(bitOffset)

                            val wordLoadCmd = TACCmd.Simple.AssigningCmd.WordLoad(            // S <- wordLoad(tacS, slot)
                                lhs = sSymbol,
                                base = matchingStorageVar,
                                loc = linkedContractSlot.asTACSymbol()
                            )
                            cmds.add(wordLoadCmd)
                            decls.add(sSymbol)

                            val assignCondCmdX =
                                if (byteOffset > 0) {                          // if there are other vars on the same slot, before the address, then we should filter S to get X
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(                      // assign X <- ((2^offset)-1) BWAND S
                                        lhs = xSymbol,
                                        rhs = TACExpr.BinOp.BWAnd(
                                            TACSymbol.Const(xOffsetMask, Tag.Bit256).asSym(),
                                            sSymbol.asSym()
                                        )
                                    )
                                } else {                                           // if there are no more vars on the same slot, before the address, then there is no need to filter S
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(                      // assign X <- 0
                                        lhs = xSymbol,
                                        rhs = TACSymbol.Const(BigInteger.ZERO, Tag.Bit256).asSym()
                                    )
                                }

                            cmds.add(assignCondCmdX)
                            decls.add(xSymbol)

                            val assignCondCmdY =
                                if (byteOffset < highestOffset) {           // if there are other vars on the same slot, after the address, then we should filter S to get Y
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(                      // assign Y <- ((2^offset+160)-1) BWAND S
                                        lhs = ySymbol,
                                        rhs = TACExpr.BinOp.BWAnd(
                                            TACSymbol.Const(yOffsetMask, Tag.Bit256).asSym(),
                                            sSymbol.asSym()
                                        )
                                    )
                                } else {                                        // if there are no more vars on the same slot, after the address, then there is no need to filter S
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(                      // assign Y <- S
                                        lhs = ySymbol,
                                        rhs = sSymbol
                                    )
                                }

                            cmds.add(assignCondCmdY)
                            decls.add(ySymbol)

                            val tmpVarAssume1value: TACSymbol.Var =
                                TACKeyword.TMP(Tag.Bool, "Assume1")           // first assume value
                            val tmpVarAssume1lhs: TACSymbol.Var =
                                TACKeyword.TMP(Tag.Bit256, "AssumeLHS1")      // link * 2^offset
                            val tmpVarAssume2lhs: TACSymbol.Var =
                                TACKeyword.TMP(Tag.Bit256, "AssumeLHS2")      // (link * 2^offset) + x

                            val tmpVarAssume2value: TACSymbol.Var =
                                TACKeyword.TMP(Tag.Bool, "Assume2")           // second assume value


                            val assignTmp1ForAssumeCmd1 =
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(                      // tmp1 = link * 2^offset
                                    lhs = tmpVarAssume1lhs,
                                    rhs = TACExpr.Vec.Mul(
                                        link.asSym(),
                                        TACSymbol.Const(assumeOffsetValue, Tag.Bit256).asSym()
                                    )
                                )
                            cmds.add(assignTmp1ForAssumeCmd1)
                            decls.add(tmpVarAssume1lhs)

                            val assignTmp2ForAssumeCmd1 =
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(                      // tmp2 = tmp1 + X
                                    lhs = tmpVarAssume2lhs,
                                    rhs = TACExpr.Vec.Add(tmpVarAssume1lhs.asSym(), xSymbol.asSym())
                                )
                            cmds.add(assignTmp2ForAssumeCmd1)
                            decls.add(tmpVarAssume2lhs)

                            val assignTmp3ForValueOfAssumeCmd1 =
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(                      // tmpAssume1 = tmp2 == Y
                                    lhs = tmpVarAssume1value,
                                    rhs = TACExpr.BinRel.Eq(tmpVarAssume2lhs.asSym(), ySymbol.asSym())
                                )
                            cmds.add(assignTmp3ForValueOfAssumeCmd1)
                            decls.add(tmpVarAssume1value)

                            val assumeCmd1 = TACCmd.Simple.AssumeCmd(tmpVarAssume1value, "linking")       // assume(tmpAssume1)
                            cmds.add(assumeCmd1)

                            val assume2valueCmd =
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(                       // tmpAssume2 = link == linkedContractId
                                    lhs = tmpVarAssume2value,
                                    rhs = TACExpr.BinRel.Eq(link.asSym(), contractAddressVar.asSym())
                                )
                            cmds.add(assume2valueCmd)
                            decls.add(tmpVarAssume2value)
                            decls.add(contractAddressVar)
                            decls.add(link)

                            val assumeCmd2 = TACCmd.Simple.AssumeCmd(tmpVarAssume2value, "linking")       // assume(tmpAssume2)
                            cmds.add(assumeCmd2)
                        }
                    }

                    wrapWithCVL(
                        CommandWithRequiredDecls<TACCmd.Spec>(
                            cmds, decls
                        ),
                        "Link ${contract.name}:${
                            storageItemsPackedInRelevantSlot.values.singleOrNull { storageVar ->
                                (storageVar.storageType as? TACStorageType.IntegralType)?.typeLabel == "address"
                            }?.label ?: linkedContractSlot
                        }=${contractToLinkTo.name}"
                    )

                }

        return contractLinksCmds
    }
}

/**
 * Performs a super simple linking by storing the linked address into the desired slot, disregarding any potential packing.
 * This should be used carefully and within [resolveLinksWithoutStorageAnalysis].
 * Should return a [CommandWithRequiredDecls<TACCmd.Spec>] with only commands and decls, no bifs/any other fields
 */
private fun fullWordLinking(storageBaseVar: TACSymbol.Var, linkedContractSlot: BigInteger, addressToLink: TACSymbol): CommandWithRequiredDecls<TACCmd.Spec> {
    val setLinkCmd = if (storageBaseVar.tag.isMapType()) {
        TACCmd.Simple.WordStore(
            base = storageBaseVar,
            loc = linkedContractSlot.asTACSymbol(),
            value = addressToLink
        )
    } else {
        TACCmd.Simple.AssigningCmd.AssignExpCmd(storageBaseVar, addressToLink)
    }
    return CommandWithRequiredDecls<TACCmd.Spec>(setLinkCmd)
}

private fun getArrayAssumptions(id: String, arrayType: CVLType.PureCVLType.CVLArrayType, allocatedTACSymbols: TACSymbolAllocation): CommandWithRequiredDecls<TACCmd.Spec> {
    val arrayLen = CVLReservedVariables.certoraTmp.toVar(Tag.Bit256).toUnique("!arrayLen")
    val arrayVar = allocatedTACSymbols.get(id, tag = arrayType.toTag())

    // string assumptions must come at the end of the block to init arrayLen
    val stringAssumptions = if (arrayType is CVLType.PureCVLType.DynamicArray.StringType && Config.EnableCleanCVLStrings.get()) {
        val lastWord = CVLReservedVariables.certoraTmp.toVar(Tag.Bit256).toUnique("!lastWord")
        // we want the last word of the string to be clean.
        // that is, if the length%32 is 5, only the first 5 MSBs should be non-zero.
        // solc8 checks it with a bwand operation using high-ones, but since we need a _precise_
        // modeling of a high-ones mask, we encode it using NIA too.
        // the solution seems to work for solc7 as well

        // the bv expression is `lastWord & ~(MASK256 >>logical 8*(length % 32))`,
        // which is the same as `lastWord & high-ones(8 * (length % 32))`
        // and we eventually want to check it's equal to `lastWord`.
        val cleanedLastWordCmdsBv = ExprUnfolder.unfoldToSingleVar("cleanedLastWordBv",
            txf {
                lastWord.asSym() bwAnd bwNot(
                    MAX_EVM_UINT256.asTACExpr shiftRLog
                        safeMathNarrow(BITS_IN_A_BYTE.asTACExpr intMul (arrayLen.asSym() mod EVM_WORD_SIZE_INT.asTACExpr), Tag.Bit256)
                )
            }
        )

        // for the non-bv case, precise here with NIA.
        // because we want `x & high-ones(8 * (length % 32)) == x` and it's not captured in NIA,
        // so instead we do `x / 2^(8 * (32 - (length % 32))) == x`, and perform the exponent with left-shifting.
        // (the bv case is necessary in order to connect correctly with the solidity code via transitivity.
        // e.g. if solc checks `x & high-ones(..) == x` and we do `x / ... == x` then we need the bv
        // equivalent here as well to establish `x & high-ones == x / ...`)
        val cleanedLastWordCmdsNia = ExprUnfolder.unfoldToSingleVar("cleanedLastWordNIA",
            txf {
                safeMathNarrow(
                    lastWord.asSym() intDiv (
                        // this can be any power of two, but div is fine, the result is bv as lastWord is bv
                        BigInteger.TWO.asTACExpr intPow
                            // 8 * (32-something) where something in [0,32], so must be bounded at [0,256]
                            (BITS_IN_A_BYTE.asTACExpr intMul (EVM_WORD_SIZE_INT.asTACExpr intSub (arrayLen.asSym() mod EVM_WORD_SIZE_INT.asTACExpr)))
                        ),
                    Tag.Bit256
                )
            }
        )

        fun assumeLastWordCleanedIsLastWord(lastWord: TACSymbol, lastWordCleaned: TACExpr): CommandWithRequiredDecls<TACCmd.Spec> {
            val assumeVar = CVLReservedVariables.certoraAssume.toVar(Tag.Bool).toUnique("!")
            return CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        assumeVar,
                        TACExpr.BinRel.Eq(lastWordCleaned, lastWord.asSym())
                    ),
                    TACCmd.Simple.AssumeCmd(assumeVar, "assumeLastWordCleanedIsLastWord")
                ),
                assumeVar
            )
        }

        /**
         * Note that if the length of the string is 0, these assumptions would tell the Prover that
         * reading from the buffer at offset 0 returns a zeroed-out word, which seems a benign assumption for CVL strings.
         */
        val startLabel = TACCmd.Simple.LabelCmd("→ Assuming cleanliness of string $id")
        val endLabel = TACCmd.Simple.LabelCmd("←")
        val toReturn = MutableCommandWithRequiredDecls<TACCmd.Spec>()
        toReturn.extend(
            listOf(
                startLabel,
                TACCmd.CVL.StringLastWord(lastWord, arrayVar)
            ),
            setOf(lastWord)
        )
        toReturn.extend(cleanedLastWordCmdsBv.cmds, cleanedLastWordCmdsBv.newVars)
        toReturn.extend(assumeLastWordCleanedIsLastWord(lastWord, cleanedLastWordCmdsBv.e))
        toReturn.extend(cleanedLastWordCmdsNia.cmds, cleanedLastWordCmdsNia.newVars)
        toReturn.extend(assumeLastWordCleanedIsLastWord(lastWord, cleanedLastWordCmdsNia.e))
        toReturn.extend(
            listOf(endLabel)
        )

        toReturn.toCommandWithRequiredDecls()
    } else {
        // if not a string, we don't do all of this
        CommandWithRequiredDecls<TACCmd.Spec>(emptyList())
    }

    val assume = CVLReservedVariables.certoraAssume.toVar(Tag.Bool).toUnique("!")
    return CommandWithRequiredDecls(
        listOf(TACCmd.CVL.ArrayLengthRead(
            rhs = arrayVar,
            lhs = arrayLen
        ), TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = assume,
            rhs = TACExpr.BinRel.Ge(
                o2 = TACSymbol.lift(0).asSym(),
                o1 = arrayLen.asSym()
            )
        ), TACCmd.Simple.AssumeCmd(
            cond = assume, "array length non-negative"
        )), setOf(arrayVar, assume, arrayLen)
    ).merge(stringAssumptions)
}


/**
 * Also see `generateStringExpression` for comparison.
 */
internal fun generateStringAssignment(varSymbol: TACSymbol.Var, stringConst: String): CommandWithRequiredDecls<TACCmd.Spec> {
    // divide string const to portions of 32 bytes each
    val tmpSymbol = TACKeyword.TMP(Tag.ByteMap, "Bytemap")
    val stringAssign = (0..(stringConst.length / 32)).map { portionIdx ->
        val stringPortion = stringConst.substring(portionIdx * 32, Math.min(stringConst.length, portionIdx * 32 + 32))
        val stringChars = stringPortion.map { c -> c.toByte() }
        val padding = (0..((32 - stringPortion.length) - 1)).map { 0.toByte() } // take the remaining N, add 2*N zeroes
        val whole = stringChars + padding
        check(whole.size == 32) { "Chunk size should be 32 but got whole $whole (${whole.size}) consisting of chunk $stringChars (${stringChars.size}) + padding $padding (${padding.size})" }
        TACCmd.Simple.AssigningCmd.ByteStore(
            TACSymbol.Const((portionIdx * 32).toBigInteger()),
            TACSymbol.Const(whole.map { b -> b.toHexString() }.joinToString("").toBigInteger(16)),
            tmpSymbol
        )
    }

    /* put the constant Error(string)
        0=0x0: 0x8c379a000000000000000000000000000000000000000000000000000000000
        4=0x4: 0x20
        36=0x24: len
        68=0x44: string
     */
    return CommandWithRequiredDecls<TACCmd.Spec>(
        listOf(
            TACCmd.Simple.AssigningCmd.ByteStore(
                TACSymbol.Const(BigInteger.ZERO),
                TACSymbol.Const("8c379a000000000000000000000000000000000000000000000000000000000".toBigInteger(16)),
                varSymbol
            ).plusMeta(
                CVL_EXP,
                CVLExpToTACExprMeta.NullaryCVLExp(
                    CVLExp.Constant.StringLit(stringConst, CVLExpTag.transient(CVLType.PureCVLType.DynamicArray.StringType))
                )
            ),
            TACCmd.Simple.AssigningCmd.ByteStore(
                TACSymbol.Const(DEFAULT_SIGHASH_SIZE),
                TACSymbol.Const("20".toBigInteger(16)),
                varSymbol
            ),
            TACCmd.Simple.AssigningCmd.ByteStore(
                TACSymbol.Const(BigInteger.valueOf(36)),
                TACSymbol.Const(stringConst.length.toBigInteger()),
                varSymbol
            )
        ) + stringAssign.map { bs ->
            val prevLoc = bs.loc as TACSymbol.Const // we know that!
            val newLoc = TACSymbol.Const(prevLoc.value + BigInteger.valueOf(68))
            bs.copy(loc = newLoc, base = varSymbol)
        },
        tmpSymbol, varSymbol
    )
}

fun getAsCVLVariable(
    symbol: TACSymbol.Var,
    cvlDefSite: CVLDefinitionSite,
    preserveAsDisplayName: Boolean,
    cvlType: CVLType.PureCVLType? = null,
    structPath: CVLStructPathNode? = null
) =
    symbol
        .withMeta(CVL_VAR, true)
        .withMeta(CVL_DEF_SITE, cvlDefSite)
        .withMetaIfNotNull(CVL_TYPE, cvlType)
        .withMetaIfNotNull(CVL_STRUCT_PATH, structPath)
        .let {
            if (preserveAsDisplayName) {
                it.withMeta(CVL_DISPLAY_NAME, symbol.namePrefix)
            } else {
                it
            }
        }

fun <T : TACCmd.Spec> CommandWithRequiredDecls<T>.toProg(id: String, env: CVLCompiler.CallIdContext) =
    codeFromCommandWithVarDecls(env.newBlockId(), this, id)

fun CVLTACProgram.asSimple() = getSimple(this)

infix fun CVLTACProgram.merge(other: CVLTACProgram) = mergeCodes(this, other)
infix fun CVLTACProgram.merge(other: ParametricInstantiation<CVLTACProgram>) = ParametricMethodInstantiatedCode.mergeProgs(
    getSimple(this), other
)

fun getAsCVLVariable(
    symbol: TACSymbol.Var,
    cvlDefSite: CVLDefinitionSite?,
    displayName: String,
    cvlType: CVLType.PureCVLType? = null,
    structPath: CVLStructPathNode? = null,
) =
    symbol
        .withMeta(CVL_VAR, true) // CVL_LOCAL => CVL_VAR but not the converse
        .withMetaIfNotNull(CVL_DEF_SITE, cvlDefSite)
        .withMetaIfNotNull(CVL_TYPE, cvlType)
        .withMetaIfNotNull(CVL_STRUCT_PATH, structPath)
        .withMeta(CVL_DISPLAY_NAME, displayName)

fun getCVLVariable(id: String, callId: CallId, t: Tag, cvlDefSite: CVLDefinitionSite, preserveAsDisplayName: Boolean = false,
                   cvlType: CVLType.PureCVLType? = null, structPath: CVLStructPathNode? = null) =
    getAsCVLVariable(TACSymbol.Var(id, t, callIndex = callId), cvlDefSite, preserveAsDisplayName, cvlType, structPath)
