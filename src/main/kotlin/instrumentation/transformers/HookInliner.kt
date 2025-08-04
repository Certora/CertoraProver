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

package instrumentation.transformers

import datastructures.stdcollections.*
import analysis.*
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.storage.STORAGE_ANALYSIS_FAILURE
import analysis.storage.StorageAnalysis
import analysis.storage.StoreInfo
import analysis.storage.WidthConstraints
import config.Config
import evm.EVM_BITWIDTH256
import evm.EVM_WORD_SIZE
import evm.MASK_SIZE
import log.Logger
import log.LoggerTypes
import report.calltrace.CVLReportLabel
import report.calltrace.printer.StackEntry
import scene.IScene
import spec.*
import spec.CVLCompiler.CallIdContext.Companion.toContext
import spec.converters.toCRD
import spec.cvlast.CVLHook
import spec.cvlast.CVLHookPattern
import utils.Range
import spec.cvlast.VMParam
import spec.cvlast.typedescriptors.FromVMContext
import spec.cvlast.typedescriptors.VMValueTypeDescriptor
import tac.*
import utils.*
import vc.data.*
import vc.data.TACHookPattern.StorageHook.Companion.map
import vc.data.TACMeta.LAST_STORAGE_UPDATE
import vc.data.TACMeta.RESET_STORAGE
import vc.data.TACMeta.REVERT_MANAGEMENT
import vc.data.TACMeta.SIGN_EXTENDED_STORE
import vc.data.tacexprutil.ExprUnfolder
import vc.data.tacexprutil.tempVar
import java.math.BigInteger
import java.util.stream.Collectors

class HookInliner(val scene: IScene, private val cvlCompiler: CVLCompiler) : CodeTransformer() {
    private data class HookInliningInfo(
        val cvlHook: CVLHook,
        val match: HookMatch,
        val pattern: TACHookPattern,
        val program: CVLTACProgram,
        val preCommands: CommandWithRequiredDecls<TACCmd.Simple> = CommandWithRequiredDecls(),
        val postCommands: CommandWithRequiredDecls<TACCmd.Simple> = CommandWithRequiredDecls(),
        val ext: Map<VMParam.Named, TACExpr.Sym.Var> = mapOf()
    )

    private val logger = Logger(LoggerTypes.HOOK_INSTRUMENTATION)
    private val somePathsMatches = mutableSetOf<HookMatch.StorageMatch.SomePaths>()
    private val impreciseIndices = mutableSetOf<Pair<TACHookPattern, Set<TACSymbol.Var>>>()

    // The hook, the matched storage command's base, and the type descriptor of the hook's value.
    // The base's bitwidth and the vmtype's bitwidth differ.
    data class HookValueWidthMismatch(
        val pattern: TACHookPattern.StorageHook,
        val base: TACSymbol.Var,
        val valueTypeDescriptor: VMValueTypeDescriptor,
    ) {
        val offset: BigInteger
            get() = when (val slot = pattern.slot) {
                is TACHookPattern.StorageHook.TACSlotPattern.ArrayAccess -> BigInteger.ZERO
                is TACHookPattern.StorageHook.TACSlotPattern.MapAccess -> BigInteger.ZERO
                is TACHookPattern.StorageHook.TACSlotPattern.Static -> slot.offset.value
                is TACHookPattern.StorageHook.TACSlotPattern.StructAccess -> slot.offset.value
            }
    }
    private val hookValueWidthMismatches = mutableMapOf<HookMatch, HookValueWidthMismatch>()

    /**
     * Essentially, all this nonsense is to handle the case when the storage analysis infers multiple
     * access paths. We want to make sure all of the paths are equivalent. So we must check that each
     * index/key/offset mustEquals between all the inferred matches. Usually there's only 1, so this
     * is rarely run
     */
    private fun findMayNotEqual(
        ast: CoreTACProgram,
        ptr: CmdPointer,
        pattern: TACHookPattern,
        match: HookMatch,
        allocatedTACSymbols: TACSymbolAllocation
    ): Set<TACSymbol.Var> {
        // we have a single full match
        val mustEquals = ast.analysisCache.gvn
        val mustBeConstant = MustBeConstantAnalysis(
            ast.analysisCache.graph, NonTrivialDefAnalysis(ast.analysisCache.graph, ast.analysisCache.def)
        )

        fun TACSymbol.Var.mustEqual(other: TACSymbol.Var): Boolean =
            mustEquals.equivBefore(ptr, this).contains(other)

        fun TACSymbol.Var.mustEqual(other: TACSymbol.Const): Boolean =
            mustBeConstant.mustBeConstantAt(ptr, this) == other.value

        infix fun TACSymbol.mayNotEqual(other: TACSymbol): Boolean = when (this) {
            is TACSymbol.Var -> when (other) {
                is TACSymbol.Var -> !this.mustEqual(other)
                is TACSymbol.Const -> !this.mustEqual(other)
            }

            is TACSymbol.Const -> when (other) {
                is TACSymbol.Var -> !other.mustEqual(this)
                is TACSymbol.Const -> this.value != other.value
            }
        }

        // smart casting
        check(match is HookMatch.StorageMatch.AllPaths)

        val matches = match.matchedStorageAccessPaths.toList()
        val oneMatch = matches.first()

        fun mayNotEqual(anotherMatch: StorageAnalysis.AnalysisPath): Set<TACSymbol.Var> {
            val mayNotEquals = mutableSetOf<TACSymbol.Var>()
            tailrec fun mayNotEqual(
                oneMatch: StorageAnalysis.AnalysisPath,
                anotherMatch: StorageAnalysis.AnalysisPath,
                slotPattern: TACHookPattern.StorageHook.TACSlotPattern
            ): Set<TACSymbol.Var> = when (oneMatch) {
                is StorageAnalysis.AnalysisPath.WordOffset -> `impossible!`
                is StorageAnalysis.AnalysisPath.Root -> {
                    check(anotherMatch is StorageAnalysis.AnalysisPath.Root && oneMatch.slot == anotherMatch.slot)
                    if (slotPattern is TACHookPattern.StorageHook.TACSlotPattern.StructAccess) {
                        mayNotEqual(oneMatch, anotherMatch, slotPattern.canonicalize())
                    } else {
                        check(slotPattern is TACHookPattern.StorageHook.TACSlotPattern.Static) {
                            "slotPattern: $slotPattern, oneMatch: $oneMatch"
                        }
                        mayNotEquals
                    }
                }

                is StorageAnalysis.AnalysisPath.MapAccess -> {
                    check(anotherMatch is StorageAnalysis.AnalysisPath.MapAccess)
                    check(slotPattern is TACHookPattern.StorageHook.TACSlotPattern.MapAccess)
                    if (oneMatch.key mayNotEqual anotherMatch.key) {
                        mayNotEquals.add(allocatedTACSymbols.get(slotPattern.key.name))
                    }
                    mayNotEqual(oneMatch.base, anotherMatch.base, slotPattern.mapping)
                }

                is StorageAnalysis.AnalysisPath.StaticArrayAccess -> {
                    check(anotherMatch is StorageAnalysis.AnalysisPath.StaticArrayAccess)
                    check(slotPattern is TACHookPattern.StorageHook.TACSlotPattern.ArrayAccess)
                    if (oneMatch.index!! mayNotEqual anotherMatch.index!!) {
                        mayNotEquals.add(allocatedTACSymbols.get(slotPattern.index.name))
                    }
                    mayNotEqual(oneMatch.base, anotherMatch.base, slotPattern.array)
                }

                is StorageAnalysis.AnalysisPath.ArrayAccess -> {
                    check(anotherMatch is StorageAnalysis.AnalysisPath.ArrayAccess)
                    check(slotPattern is TACHookPattern.StorageHook.TACSlotPattern.ArrayAccess)
                    if (oneMatch.index!! mayNotEqual anotherMatch.index!!) {
                        mayNotEquals.add(allocatedTACSymbols.get(slotPattern.index.name))
                    }
                    mayNotEqual(oneMatch.base, anotherMatch.base, slotPattern.array)
                }
                // TODO: flatten structs
                is StorageAnalysis.AnalysisPath.StructAccess -> {
                    check(anotherMatch is StorageAnalysis.AnalysisPath.StructAccess && oneMatch.offset.bytes == anotherMatch.offset.bytes)
                    check(slotPattern is TACHookPattern.StorageHook.TACSlotPattern.StructAccess && oneMatch.offset.bytes == slotPattern.offset.value)
                    mayNotEqual(oneMatch.base, anotherMatch.base, slotPattern.base)
                }
            }
            if (pattern is TACHookPattern.StorageHook) {
                return mayNotEqual(oneMatch, anotherMatch, pattern.slot.canonicalize())
            }
            return mayNotEquals
        }

        return match.matchedStorageAccessPaths.drop(0).flatMap { anotherMatch -> mayNotEqual(anotherMatch) }.toSet()
    }

    /**
     * Returns all hooks that match filter function [pred] and command [cmd].
     */
    private fun matchedHooksForFilter(cmd: TACCmd.Simple, pred: (CVLHook) -> Boolean) = cvlCompiler.cvl.hooks.filter(pred).mapNotNull { hook ->
        val pattern = TACHookPattern.cvlHookPatternToTACHookPattern(hook.pattern, hook.scope, scene.getContracts())
        val match = pattern.matches(cmd)

        if (match !is HookMatch.None) {
            Triple(hook, pattern, match)
        } else {
            null
        }
    }

    private fun noMatchError(matches: List<HookMatch>, lcmd: LTACCmd): Nothing {
        val src = getSourceStringOrInternalFuncForPtr(lcmd)
        val srcString = src?.let { " (source: $src)" }.orEmpty()
        throw CertoraException(
            // this error message is innacurate if there are no exact matches, only if there are multiple
            // additionally, it hides the more precise errors which will be displayed when somePathsMatches is
            // populated
            CertoraErrorType.CVL,
            if (matches.isNotEmpty()) {
                "Must not match multiple hooks of the same pattern, got: $matches $srcString"
            } else {
                "Got no hook matches even though they were expected $srcString"
            }
        )
    }

    /**
     * Inlines TAC command associated with storage hooks to the respective blocks.
     * NOTE: we assume the Storage Analysis succeeded without errors! The check should be in [HookInliner.transform]
     */
    private fun inlineStorageHooksInBlock(ast: CoreTACProgram, lcmd: LTACCmd): HookInliningInfo? {
        val cmd = lcmd.cmd
        val ptr = lcmd.ptr

        if ((cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                cmd !is TACCmd.Simple.WordStore &&
                cmd !is TACCmd.Simple.AssigningCmd.WordLoad
            ) ||
            // all the following cases are storage accesses via CVL and not originating from the code,
            // thus we are not interested in inlining hooks for those
            (cmd.meta.containsKey(REVERT_MANAGEMENT) ||
                cmd.meta.containsKey(LAST_STORAGE_UPDATE) ||
                cmd.meta.containsKey(TACMeta.CVL_STORAGE_COMPARISON) ||
                cmd.meta.containsKey(TACMeta.STORAGE_READ_TRACKER) ||
                cmd.meta.containsKey(TACMeta.DIRECT_STORAGE_ACCESS) ||
                cmd.meta.containsKey(RESET_STORAGE)
            )
        ) {
            return null
        }

        // look for all hooks which match this command
        val matchedCommands = matchedHooksForFilter(cmd) { it.pattern is CVLHookPattern.StoragePattern }

        if (matchedCommands.any { (_, _, match) -> match !is HookMatch.StorageMatch }) {
            throw IllegalStateException("Non-storage hooks should be filtered out when looking for a storage hook")
        }

        // absolutely no matches, return
        if (matchedCommands.isEmpty()) {
            return null
        }

        if (matchedCommands.any { (_, _, match) -> match is HookMatch.StorageMatch.SomePaths }) {
            /**
             *  Collect all cases where only some--but not all--of the paths inferred by the storage analysis match
             *  the given hook. IF some match and some don't, this is considered an error and will prevent inlining
             *  from happening.
             */
            somePathsMatches.addAll(matchedCommands.mapNotNull { (_, _, match) ->
                match as? HookMatch.StorageMatch.SomePaths
            })
            return null
        }

        /**
         * The bitwidth of a hook's value must match the base we're reading from, otherwise
         * we will observe incorrect data.
         */
        fun mismatchedHookValueWidths(tacHook: TACHookPattern): HookValueWidthMismatch? {
            if (cmd !is TACCmd.Simple.StorageAccessCmd || tacHook !is TACHookPattern.StorageHook) {
                return null
            }
            val vmType = tacHook.value.vmType
            if (vmType !is VMValueTypeDescriptor) {
                return null
            }
            return HookValueWidthMismatch(tacHook, cmd.base, vmType).takeIf { (_, base, vmType) ->
                // Why the rounding?
                // bool is represented as a 1-bit value (and non-byte-sized values otherwise aren't allowed)
                vmType.bitwidth.divRoundUp(8) !=
                    (base.meta[TACMeta.BIT_WIDTH] ?: EVM_BITWIDTH256).divRoundUp(8)
            }
        }

        matchedCommands.forEach { (_, tacHook, match) ->
            mismatchedHookValueWidths(tacHook)?.let {
                hookValueWidthMismatches[match] = it
            }
        }

        /**
         * We have handled [HookMatch.StorageMatch.SomePaths] case above. Since duplicate
         * hooks should be caught by the type checker, we either have exactly 1 full match
         * ([HookMatch.StorageMatch.AllPaths]), or no full matches.
         */
        val (hook, pattern, match) = matchedCommands.singleOrNull { (_, _, match) -> match is HookMatch.StorageMatch.AllPaths }
            ?: noMatchError(matchedCommands.map { it.third }.filterIsInstance<HookMatch.StorageMatch.AllPaths>(), lcmd)

        val (code, allocatedTACSymbols) = cvlCompiler.compileHook(hook, lcmd.ptr.block.getCallId())
        val program = code.getAsSimple()


        val mayNotEqual = try {
            findMayNotEqual(ast, ptr, pattern, match, allocatedTACSymbols)
        } catch (@Suppress("SwallowedException") cie: CertoraInternalException) {
            if (cie.type == CertoraInternalErrorType.CVL_TAC_ALLOCATION) {
                throw CertoraException(
                    CertoraErrorType.CVL,
                    "Error in inlining the hook specified in ${hook.range}: ${cie.internalMsg}"
                )
            } else {
                throw cie
            }
        }

        // we only care if the two different variables aren't equal if we actually have to use it within the
        // body of the hook (we have to choose one of the variables to actually substitute). Otherwise, their
        // may-not-equal-ness does not affect whether or not the pattern matches
        val usedMayNotEqual = if (mayNotEqual.isNotEmpty()) {
            val hookUsedVars = program.parallelLtacStream().flatMap { it.cmd.getFreeVarsOfRhs().stream() }.collect(Collectors.toSet())
            mayNotEqual.intersect(hookUsedVars)
        } else {
            setOf()
        }

        // keep track of the indices with multiple possible values, save them and aborth any more inlining work
        // on this command
        if (usedMayNotEqual.isNotEmpty()) {
            impreciseIndices.add(pattern to mayNotEqual)
            return null
        }

        // we must instrument a wordload/assignexp if we want to use the old value
        /*
         * it is important that the hook commands come after for WordLoad since the variable
         * WordLoad assigns to will be something else before the WordLoad command
         * note, while this should no longer be a problem with TAC DSA, we would like to keep TAC Variables
         * from being used before they are actually assigned
         * Further, we must have that all the values mentioned in the INLINED_HOOK annotation
         * are defined before the occurrence of the annotation, otherwise DSA will get cranky
         * down the road. Thus, we have to place the loadPreviousValue commands BEFORE
         * the inlined hook, this is important DON'T MESS IT UP
         */
        val previousValue = (pattern as? TACHookPattern.StorageHook.Store)?.previousValue
        val (loadPreviousValue, ext) = if (previousValue != null) {
            val toRet = MutableCommandWithRequiredDecls<TACCmd.Simple>()
            val info = StoreInfo(LTACCmd(ptr, cmd))
            var tmpHoldPreviousValue: TACSymbol.Var

            if (info.isAssign) {
                tmpHoldPreviousValue = tempVar(previousValue.name, info.base.tag)
                toRet.extend(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    tmpHoldPreviousValue, info.base
                ), tmpHoldPreviousValue, info.base)
            } else {
                tmpHoldPreviousValue = tempVar(previousValue.name, Tag.Bit256)
                toRet.extend(TACCmd.Simple.AssigningCmd.WordLoad(
                    tmpHoldPreviousValue, info.loc, info.base
                ), tmpHoldPreviousValue, info.loc, info.base)
            }

            // We're potentially inserting a load from storage, so the
            // hook value width mismatch problem applies here.
            hookValueWidthMismatches[match]?.let { mismatch ->
                compileWidthMask(previousValue.name, mismatch, tmpHoldPreviousValue.asSym()).let { (e, cmds) ->
                    toRet.extend(cmds)
                    (e.s as? TACSymbol.Var)?.let { x ->
                        tmpHoldPreviousValue = x
                    }
                }
            }

            WidthConstraints(tmpHoldPreviousValue).let { constrainer ->
                info.type?.takeIf { SIGN_EXTENDED_STORE in info.cmd.meta }?.let(constrainer::byType)
                    ?: constrainer.unsigned(info.width)
            }.let(toRet::extend)

            toRet.toCommandWithRequiredDecls() to mapOf(
                previousValue to tmpHoldPreviousValue.asSym()
            )
        } else {
            CommandWithRequiredDecls<TACCmd.Simple>() to mapOf()
        }

        val (fixMismatch, fixedMatch) =
            if (match in hookValueWidthMismatches) {
                fixWidthMismatch(pattern, match, hookValueWidthMismatches[match]!!)
            } else {
                CommandWithRequiredDecls<TACCmd.Simple>() to match
            }

        return HookInliningInfo(
            hook,
            fixedMatch,
            pattern,
            program,
            preCommands = loadPreviousValue.letIf(pattern is TACHookPattern.StorageHook.Store) {
                it.merge(fixMismatch)
            },
            postCommands = if(pattern is TACHookPattern.StorageHook.Load) {
                fixMismatch
            } else {
               CommandWithRequiredDecls()
            },
            ext
        )
    }

    /**
     * If [pattern] is a storage pattern and [match] represents a hook instantiation where the
     * widths of the value parameters are mismatched, then generate commands to mask the value
     * instantiation and use this masked value in the returned hook match.
     */
    private fun fixWidthMismatch(pattern: TACHookPattern, match: HookMatch, mismatch: HookValueWidthMismatch): Pair<CommandWithRequiredDecls<TACCmd.Simple>, HookMatch> {
        val default by lazy {
            CommandWithRequiredDecls<TACCmd.Simple>() to match
        }

        if (mismatch.offset.mod(EVM_WORD_SIZE) != BigInteger.ZERO) {
            logger.info { "Not correcting mismatched hook instantiation at non-word-boundary offset ${mismatch.offset}"}
            return default
        }

        val rhs = (pattern as? TACHookPattern.StorageHook)?.value
            ?.let(match.substitutions::get)
            ?.tryAs<HookValue.Direct>()?.expr
            ?: return default

        /* Replace the match v := rhs with v := rhs & MASK_FOR_WIDTH */
        val (maskedExpr, cmds) = compileWidthMask("hookInlinerWidthMasking", mismatch, rhs)

        hookValueWidthMismatches.remove(match)
        return cmds to match.withSubstitution(pattern.value, maskedExpr)
    }

    private fun compileWidthMask(tempPrefix: String, mismatch: HookValueWidthMismatch, rhs: TACExpr): Pair<TACExpr.Sym, CommandWithRequiredDecls<TACCmd.Simple>> {
        return ExprUnfolder.unfoldToSingleVar(
            tempPrefix,
            TACExpr.BinOp.BWAnd(rhs, MASK_SIZE(mismatch.valueTypeDescriptor.bitwidth).asTACExpr)
        ).let { unfolderResult ->
            unfolderResult.e to CommandWithRequiredDecls(unfolderResult.cmds, unfolderResult.newVars)
        }
    }


    private fun transformHookToCoreTACProgram(
        prog: CVLTACProgram,
        assignments: TACCmd.CVL.VMParamConverter
    ): CoreTACProgram {
        // we can compile away the VMParamAssignment (an intermediary command which retains high-level type information)
        // now that we have the actual values that correspond to hook variables
        val coreProg = CVLToSimpleCompiler.compileVMParamAssignments(prog, assignments::convert).transformToCore(scene)

        return coreProg
    }

    private fun applySubstitutions(
        where: LTACCmd,
        match: HookMatch,
        hook: CVLHook,
        // a map from the variable as written in the hook, to the expression which should replace that variable
        ext: Map<VMParam.Named, TACExpr> = mapOf()
    ) : Pair<Map<VMParam.Named, HookValue>, TACCmd.CVL.VMParamConverter> {
        // TODO: rather than substituting match expressions for hook variables, use the converters to assign
        // the match substitutions to their appropriate variables
        val subst = match.substitutions.toMutableMap()
        for((eParam, eExpr) in ext) {
            if(eParam.id.isWildcard()) {
                continue
            }
            check(eParam !in subst) {
                "Already bound $eParam for hook $hook: $subst"
            }
            subst[eParam] = HookValue.Direct(eExpr)
        }

        val substIds = subst.mapKeys() { (variable, _) -> variable.name }

        // the "in" parameters to this hook body are any values bound in the hook (pattern keys/indices, currentValue,
        // previous value). This lambda will be used to replace the AssignVMParam commands.
        val convertIns = TACCmd.CVL.VMParamConverter { assignVMParam ->
            val hookValue = substIds[assignVMParam.rhsName]
                ?: throw CertoraInternalException(CertoraInternalErrorType.HOOK_INLINING, "found an unexpected VM value ${assignVMParam.rhsName}")

            check(assignVMParam.rhsType.context == FromVMContext.HookValue) { throw CertoraInternalException(CertoraInternalErrorType.HOOK_INLINING,
                "Got a non-hook VM Value inside a hook: ${VMParam.Named(assignVMParam.rhsName, assignVMParam.rhsType.descriptor, Range.Empty())}")}

            assignVMParam.rhsType.descriptor.converterTo(assignVMParam.lhsType, FromVMContext.HookValue.getVisitor()).force()
                .convertTo(hookValue, assignVMParam.lhsVar) { it }.toCRD().toProg("hook inlining", where.ptr.block.calleeIdx.toContext())
        }

        return subst to convertIns
    }

    /**
     * Inlines TAC command associated with Create hooks to the respective blocks.
     */
    private fun inlineCreateHooksToBlock(
        lcmd: LTACCmd
    ): HookInliningInfo? {
        val cmd = lcmd.cmd
        if (cmd !is TACCmd.Simple.AssigningCmd || TACMeta.CONTRACT_CREATION !in cmd.meta || TACKeyword.CREATED.isName {  cmd.lhs.namePrefix != it }) {
            return null
        }

        // look for all hooks which match this command
        val matchedCommands = matchedHooksForFilter(cmd) { it.pattern is CVLHookPattern.Create }

        if (matchedCommands.any { (_, _, match) -> match !is HookMatch.Create }) {
            throw IllegalStateException("Non-create hooks should be filtered out when looking for a Create hook")
        }

        if (matchedCommands.isEmpty()) {
            return null
        }

        /**
         * We had at least one match for a Create hook!
         * All entries in matchedCommands are [HookMatch.Create]
         */

        val (hook, pattern, match) = matchedCommands.singleOrNull { (_, _, match) -> match is HookMatch.Create }
            ?: noMatchError(matchedCommands.map { it.third }.filterIsInstance<HookMatch.Create>(), lcmd)

        val (code, _) = cvlCompiler.compileHook(hook, lcmd.ptr.block.getCallId())
        val program = code.getAsSimple()

        return HookInliningInfo(hook, match, pattern, program)
    }

    private fun inlineOpcodeHooksToBlock(
        lcmd: LTACCmd,
    ): HookInliningInfo? {
        val cmd = lcmd.cmd
        if (cmd !is TACCmd.Simple.SummaryCmd || cmd.summ !is OpcodeSummary) {
            return null
        }

        // look for all hooks which match this command
        val matchedCommands = matchedHooksForFilter(cmd) { it.pattern is CVLHookPattern.Opcode }

        if (matchedCommands.any { (_, _, match) -> match !is HookMatch.OpcodeMatch }) {
            throw IllegalStateException("Non-opcode hooks should be filtered out when looking for an opcode hook, got ${
                matchedCommands.filter { (_, _, match) -> match !is HookMatch.OpcodeMatch}}")
        }

        if (matchedCommands.isEmpty()) {
            return null
        }

        /**
         * We had at least one match for an opcode hook!
         * All entries in matchedCommands are [HookMatch.OpcodeMatch]
         */

        val (hook, pattern, match) = matchedCommands.singleOrNull { (_, _, match) -> match is HookMatch.OpcodeMatch }
            ?: noMatchError(matchedCommands.map { it.third }.filterIsInstance<HookMatch.OpcodeMatch>(), lcmd)

        val (code, _) = cvlCompiler.compileHook(hook, lcmd.ptr.block.getCallId())
        val program = code.getAsSimple().prependToBlock0(setupExecutingContract(match))

        return HookInliningInfo(hook, match, pattern, program)
    }

    private fun setupExecutingContract(match: HookMatch) : SimpleCmdsWithDecls {
        val lhs = CVLKeywords.executingContract.toVar()
        val executing = (match as HookMatch.OpcodeMatch).executingContractVar
        return CommandWithRequiredDecls(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = lhs,
                rhs = executing
            ),
            lhs, executing
        )
    }



    override fun transform(ast: CoreTACProgram): CoreTACProgram {

        if (cvlCompiler.cvl.hooks.isEmpty()) {
            // do not bother with anything if there are no hooks
            return ast
        }

        val patchingProgram = ast.toPatchingProgram()

        if (cvlCompiler.cvl.hooks.any { it.pattern is CVLHookPattern.StoragePattern }) {
            if (!Config.EnableStorageAnalysis.get()) {
                throw CertoraException(
                    CertoraErrorType.HOOK_INLINING,
                    "Storage analysis is disabled yet there are storage hooks to be applied"
                )
            }
            val storageAnalysisFailMatch = ast.parallelLtacStream().mapNotNull {
                it.maybeAnnotation(STORAGE_ANALYSIS_FAILURE)
            }.collect(Collectors.toList())
            val storageAnalysisFailBases = storageAnalysisFailMatch.mapToSet { it.base }
            val hookBases: Set<StorageAnalysis.Base> = cvlCompiler.cvl.hooks.mapNotNullToSet {
                (it.pattern as? CVLHookPattern.StoragePattern)?.base?.map()
            }
            // if we only ever hook on storage whose analysis didn't fail it's ok
            if (storageAnalysisFailBases.any { hookBases.contains(it) }) {
                val relevantFailures = storageAnalysisFailMatch.filter { it.base in hookBases }
                throw CertoraException(
                    CertoraErrorType.HOOK_INLINING,
                    "A failure was found in the storage analysis, cannot hook on storage. " +
                        "Details: ${relevantFailures.first().userFacingMsg.getCoreMessage()}"
                )
            }
        }

        val graph = ast.analysisCache.graph
        graph.blocks.forEach { tacBlock ->
            val block = tacBlock.commands

            block.forEach forEachBlock@{ lcmd ->
                // Note we'll only reach here if storage analysis *was* run and there were *no errors*
                val hookInliningInfo = inlineStorageHooksInBlock(ast, lcmd)
                    ?: inlineCreateHooksToBlock(lcmd)
                    ?: inlineOpcodeHooksToBlock(lcmd)
                    ?: return@forEachBlock


                // a map from the variable as written in the hook, to the expression which should replace that variable
                val (substitutions, assignments) = applySubstitutions(lcmd, hookInliningInfo.match, hookInliningInfo.cvlHook, hookInliningInfo.ext)

                val inlinedHook = SnippetCmd.CVLSnippetCmd.InlinedHook(
                    hookInliningInfo.cvlHook.pattern,
                    substitutions,
                    (hookInliningInfo.match as? HookMatch.StorageMatch)?.displayPath,
                )

                /** XXX: I think this [CVLReportLabel.ApplyHook] is never read, because we skip it in the call trace. */
                val reportLabel = CVLReportLabel.ApplyHook(hookInliningInfo.pattern.toString(), hookInliningInfo.cvlHook.range)

                val inlinedHookProg = hookInliningInfo
                    .preCommands
                    .merge(
                        listOf(
                            inlinedHook.toAnnotation(),
                            lcmd.cmd
                        ).withDecls(
                            inlinedHook.support + lcmd.cmd.getFreeVarsOfRhs() + setOfNotNull(lcmd.cmd.getLhs())
                        )
                    ).merge(
                        hookInliningInfo.postCommands
                    )

                val prog = hookInliningInfo.program.prependToBlock0(inlinedHookProg).wrapWithCVLLabelCmds(reportLabel)
                    .wrapWithStackEntry(StackEntry.CVLHook(hookInliningInfo.cvlHook))

                val replacingProg = transformHookToCoreTACProgram(prog, assignments)

                patchingProgram.replaceCommand(lcmd.ptr, replacingProg)
            }
        }

        // somePathsMatches: the storage analysis produced multiple paths for a read/write of which some matched a
        //                      hook pattern and some didn't
        //
        // impreciseIndices: the storage analysis produced multiple paths for a read/write. While all paths matched
        //                      a hook pattern, the variables matched inside the patterns weren't necessarily
        //                      equal and so it would be impossible to inline these values
        if (somePathsMatches.any() || impreciseIndices.any()) {
            somePathsMatches.forEach { match ->
                logger.error {
                    "For slot pattern ${match.expectedSlotPattern}, some access paths were matched " +
                        "(${match.matchedStorageAccessPaths.joinToString(", ")}) and some were not " +
                        "(${match.unmatched.joinToString(", ")})"
                }
            }

            impreciseIndices.forEach { (pattern, badBois) ->
                @Suppress("USELESS_IS_CHECK")
                if (pattern is CVLHookPattern.StoragePattern) {  // Should always be ~true~ -> false? what is happening here?
                    logger.error {
                        "For slot pattern ${pattern.slot}, the storage analysis was unable to precisely " +
                            "infer matches for the hook variables ${badBois.joinToString(", ")}."
                    }
                }
            }
            throw CertoraException(
                CertoraErrorType.HOOK_INLINING,
                "Imprecision in the storage analysis made hook instrumentation unsound. See tool logging for more details."
            )
        }

        if (hookValueWidthMismatches.any()) {
            hookValueWidthMismatches.forEachEntry { (_, mismatch) ->
                logger.error {
                    "For hook pattern ${mismatch.pattern}, mismatch between ${mismatch.base} width " +
                        "and value type ${mismatch.valueTypeDescriptor} at offset ${mismatch.offset}"
                }
            }
            throw CertoraException(
                CertoraErrorType.HOOK_INLINING, "Unable to hook on unsplit storage"
            )
        }

        return patchingProgram.toCode(ast)
    }
}
