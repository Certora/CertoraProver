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
@file: Suppress("MoveVariableDeclarationIntoWhen")

package report.calltrace.generator

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.TACCommandGraph
import analysis.icfg.Inliner
import analysis.icfg.SummaryStack
import analysis.ip.*
import analysis.narrow
import config.Config
import config.HardFailMode
import datastructures.stdcollections.*
import log.*
import report.*
import report.calltrace.*
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.printer.CallTracePrettyPrinter
import report.calltrace.sarif.FmtArg
import report.calltrace.sarif.SarifFormatter
import report.globalstate.GlobalState
import sbf.tac.SBF_INLINED_FUNCTION_END
import sbf.tac.SBF_INLINED_FUNCTION_START
import scene.IClonedContract
import scene.ISceneIdentifiers
import scene.MethodAttribute
import solver.CounterexampleModel
import spec.CVLReservedVariables
import spec.cvlast.CVLHookPattern
import utils.Range
import spec.cvlast.CVLType
import spec.cvlast.PatternWithValue
import tac.NBId
import utils.*
import vc.data.*
import vc.data.state.TACValue
import wasm.impCfg.*
import java.util.*

private val logger = Logger(LoggerTypes.CALLTRACE)
private val hardFail = Config.CallTraceHardFail.get() == HardFailMode.ON

/**
 * Attempts to generate a call trace for rule [ruleName], which is expected to be SAT.
 * Returns [CallTrace.ViolationFound] on success or [CallTrace.Failure] on any error.
 * The resulting class will contain a [callHierarchyRoot], which may only be partially-built if an error occurred.
 */
internal sealed class CallTraceGenerator(
    val ruleName: String,
    val model: CounterexampleModel,
    val program: CoreTACProgram,
    val formatter: CallTraceValueFormatter,
    val scene: ISceneIdentifiers,
    ruleCallString: String,
) {

    /** Type used internally to communicate the result of handling a specific command with [handleCmd]. */
    internal sealed class HandleCmdResult {

        /** The command has generated a [CallTrace] and the generation of the call trace should be stopped. */
        data class GeneratedCallTrace(val callTrace: CallTrace) : HandleCmdResult()

        /** The command has been analyzed correctly, but the generation of the call trace should not be stopped. */
        object Continue : HandleCmdResult()
    }

    val blocks = program.topoSortFw.filter { it in model.reachableNBIds }

    init {
        check(blocks.isNotEmpty()) {
            "set of reachable blocks is empty when trying to create call trace from model $model"
        }
    }

    val callInputsAndOutputs = CallInputsAndOutputs(blocks, model, program.analysisCache, scene)

    private val callHierarchyRoot = initCallHierarchyRoot(ruleCallString)

    /**
     * sometimes the TAC structure is malformed, and we have structure like scope 1 start, scope 2 start, scope 1 end, scope 2 end.
     * in such cases when we see the end of scope 1 [currCallInstance] represent scope 2, and we may pop it from the call stack.
     * [earlyPoppedInstances] keeps the instances we popped without encounter their end snippet / annotation.
     */
    private val earlyPoppedInstances: MutableList<CallInstance.ScopeInstance> = mutableListOf()

    var currCallInstance: CallInstance.ScopeInstance = callHierarchyRoot

    val evaldCVLExpBuilder = EValdCVLExpAstBuilder(model, formatter)

    val graph = TACCommandGraph(program)

    val globalState = if (!Config.noCalltraceStorageInformation.get()) {
        GlobalState(blocks, graph, model, scene, formatter)
    } else {
        null
    }

    val sarifFormatter = SarifFormatter(formatter)

    /**
     * Adds a balance snippet [CallInstance] to the hierarchy of this [CallTrace].
     * [parent] is the parent of the newly created balance snippet [CallInstance].
     * [sym] is the [TACSymbol] which holds the loaded/stored value we want to represent in this generated [CallInstance].
     *
     * XXX: it looks like this is supposed to add a Transfer snippet call instance, is the function name wrong?
     */
    fun addBalanceSnippetCallInstance(
        parent: CallInstance.ScopeInstance,
        sym: TACSymbol,
        isLoad: Boolean,
        isSender: Boolean,
    ) {

        val prefix = when {
            isLoad && isSender -> "Load from: sender.balance"
            isLoad && !isSender -> "Load from: receiver.balance"
            !isLoad && isSender -> "Store at: sender.balance"
            else -> "Store at: receiver.balance"
        }

        Logger.regression { prefix }

        val valueArg = FmtArg.CtfValue.buildOrUnknown(
            tv = model.valueAsTACValue(sym),
            type = CVLType.PureCVLType.Primitive.UIntK(256),
            tooltip = "balance"
        )

        val sarif = sarifFormatter.fmt("{}{}{}", FmtArg(prefix), FmtArg.To, valueArg)

        parent.addChild(CallInstance.TransferInstance(sarif, CallEndStatus.NONE))
    }

    private fun initCallHierarchyRoot(ruleCallString: String): CallInstance.InvokingInstance.CVLRootInstance {
        callInputsAndOutputs.initCVLCall(NBId.ROOT_CALL_ID)
        return CallInstance.InvokingInstance.CVLRootInstance(ruleCallString, formatter)
    }

    /**
     * This lets us overcome current architecture.
     * When a rule is compiled, assert and require commands are wrapped with start / end labels, which contain the cvl string.
     * commands inside CVL function are not wrapped, so for example "require a != 5" would appear as "a != 5".
     * When we process start label we create a CVLInvokeInstance with its label.
     * So in order to get similar result, we check here if the parent is CVLInvokeInstance, and if not we create it.
     */
    fun materializeCVLBoolCondExpInfoWithParent(cond: TACSymbol, namePrefix: String) {
        val label = evaldCVLExpBuilder.labelForSymbol(cond)
        val parentInstance = if (currCallInstance !is CallInstance.LabelInstance && label != null) {
            val rewrapped = CVLReportLabel.Message("$namePrefix $label", label.range)
            CallInstance.LabelInstance(rewrapped).also { callTraceAppend(it) }
        } else {
            currCallInstance
        }

        evaldCVLExpBuilder.materializeCVLBoolCondExpInfo(cond, parentInstance)
    }

    fun callTraceFailure(msg: () -> String): CallTrace {
        val errorMsg = "Failed to generate call trace for rule $ruleName: ${msg()}"
        val exception = CallTraceException(errorMsg)
        logger.error(errorMsg)
        return CallTrace.Failure(callHierarchyRoot, exception, printer)
    }

    fun invalidCallStack(msg: () -> String): CallTrace {
        return callTraceFailure { "invalid state of the call stack: ${msg()}\n${currCallInstance.printCallHierarchy()}\n" }
    }

    /**
     * checks the state of the call stack hierarchy satisfies [requirement].
     * sometimes, due to the way we put branch snippets, we have to pop them before the search (determined by [allowedToPop]).
     * note that it is a logic error for both [allowedToPop] and [requirement] to return true for the same [CallInstance.ScopeInstance].
     */
    fun ensureStackState(
        requirement: (CallInstance.ScopeInstance) -> Boolean,
        eventDescription: String,
        allowedToPop: (CallInstance.ScopeInstance) -> Boolean = { false },
        allowedToFail: Boolean = false,
        callback: (CallInstance.ScopeInstance) -> Unit = {}
    ): HandleCmdResult {
        while (allowedToPop(currCallInstance)) {
            earlyPoppedInstances.add(currCallInstance)
            callTracePop() ?: return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "Failed to pop for $eventDescription" })
        }

        // the instance at the top of stack satisfies the requirement, so all good.
        if (requirement(currCallInstance)) {
            val matchingInstance = currCallInstance
            callTracePop()
                ?: return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "Try to access parent of $currCallInstance when looking for $eventDescription." })
            callback(matchingInstance) // callback is intentionally after changing currCallInstance, so it can modify it if needed (like in revert path)
            return HandleCmdResult.Continue
        }

        return if (allowedToFail) {
            warnOnWrongStackState(eventDescription)
            HandleCmdResult.Continue
        } else {
            tryRecover(requirement, eventDescription, callback)
        }
    }

    fun tryRecover(
        requirement: (CallInstance.ScopeInstance) -> Boolean,
        eventDescription: String,
        callback: (CallInstance.ScopeInstance) -> Unit,
    ): HandleCmdResult {
        if (hardFail) {
            return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "$eventDescription is not at top of call stack and hardFail is on." })
        }

        // look for first ancestor satisfying requirement.
        var curr: CallInstance.ScopeInstance? = currCallInstance
        val poppedInstances: MutableList<CallInstance.ScopeInstance> = mutableListOf()

        while (curr != null && !requirement(curr)) {
            poppedInstances.add(curr)
            curr = curr.parent
        }

        /**
         * we found a matching instance, but not at the top of the stack.
         * we are not in [hardFail] mode, so pop all instances and add them to [earlyPoppedInstances].
         */
        if (curr != null) {
            logger.warn { "Recovered from invalid call stack state in rule $ruleName for $eventDescription. stack:\n${currCallInstance.printCallHierarchy()}" }
            currCallInstance = curr.parent
                ?: return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "Try to access parent of $currCallInstance when recovering the stack for $eventDescription." })
            earlyPoppedInstances.addAll(poppedInstances)
            callback(curr)
            return HandleCmdResult.Continue
        }

        /**
         * the matching instance is not at the call stack.
         * check if we already popped it out prematurely.
         */
        val index = earlyPoppedInstances.indexOfFirst(requirement)

        // instance was not popped out of the call stack in the past.
        if (index == -1) {
            return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "Missing $eventDescription." })
        }

        callback(earlyPoppedInstances[index])
        earlyPoppedInstances.removeAt(index)

        return HandleCmdResult.Continue
    }

    fun warnOnWrongStackState(eventDescription: String) {
        val instance = CallInstance.ErrorInstance.WrongStackState()
        callTracePush(instance)

        CVTAlertReporter.reportAlert(
            type = CVTAlertType.DIAGNOSABILITY,
            severity = CVTAlertSeverity.WARNING,
            jumpToDefinition = null,
            message = "Call trace reached unexpected state in rule: ${this.ruleName}",
            hint = "Check the rule's call stack to see where this happened",
            url = null,
        )

        logger.warn { "Execution of call trace in rule ${this.ruleName} recovered - expected $eventDescription" }
    }

    /**
     * Stopgap solution for error handling. Generally, we don't expect [generate] to throw.
     * But in case it does, we wrap the exception in a [CallTrace.Failure] along with the
     * [callHierarchyRoot] built up to that point. This means we'll still return a [CallTrace].
     */
    fun safeGenerate(): CallTrace {
        return if (program.destructiveOptimizations) {
            @OptIn(Config.DestructiveOptimizationsOption::class)
            (CallTrace.DisabledByConfig(
                callHierarchyRoot,
                Config.DestructiveOptimizationsMode
            ))
        } else {
            try {
                generate()
            } catch (e: Exception) {
                val generationFailureMsg =
                    "CallTrace generation ended prematurely due to unexpected exception (rule $ruleName)"
                logger.error(e) { generationFailureMsg }
                val exception = CallTraceException(generationFailureMsg, e)
                CallTrace.Failure(callHierarchyRoot, exception, printer)
            } finally {
                printer?.close()
            }
        }
    }

    private fun handleAssigningCmd(cmd: TACCmd.Simple.AssigningCmd): HandleCmdResult {
        globalState?.handleAssignments(cmd)

        if (cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            return HandleCmdResult.Continue
        }

        val assignment: TACCmd.Simple.AssigningCmd.AssignExpCmd = cmd
        evaldCVLExpBuilder.step(assignment)

        val movementCallIndex =
            currCallInstance.ancestorOfType<CallInstance.InvokingInstance.SolidityInvokeInstance>()?.callIndex
        if (movementCallIndex != null && callInputsAndOutputs.externalCall(movementCallIndex) != null) {
            val calldataMovements = assignment.calldataMovement(model, movementCallIndex)
            calldataMovements.forEach { calldataMovement ->
                printer?.callDataMovement(calldataMovement)
                callInputsAndOutputs.registerCalldataMovement(calldataMovement, movementCallIndex)
            }

            val returndataMovement = assignment.returndataMovement(model)
            printer?.returnDataMovement(returndataMovement)
            callInputsAndOutputs.registerReturndataMovement(returndataMovement, movementCallIndex)
        }

        return HandleCmdResult.Continue
    }

    private fun handleAssumeCmd(cmd: TACCmd.Simple.AssumeCmd): HandleCmdResult {
        if (cmd.cond is TACSymbol.Var && cmd.cond.meta.contains(TACMeta.CVL_IF_PREDICATE)) {
            // suppress outputting a call instance for the predicate of
            // the `if` command we just evaluated, since we already got it
            // from the snippet at the beginning of the `if`
        } else {
            materializeCVLBoolCondExpInfoWithParent(cmd.cond, "require")
        }

        return HandleCmdResult.Continue
    }

    private fun handleAssert(cmd: TACCmd.Simple.AssertCmd, currBlock: NBId, idx: Int): HandleCmdResult {
        materializeCVLBoolCondExpInfoWithParent(cmd.o, "assert")

        Logger.regression { "Got assert command with message: ${cmd.msg}" }

        if (cmd.isViolated(model)) {
            globalState?.computeGlobalState(formatter = formatter)?.let(::callTraceAppend)

            val violatedAssert = LTACCmd(CmdPointer(currBlock, idx), cmd)
            return HandleCmdResult.GeneratedCallTrace(CallTrace.ViolationFound(callHierarchyRoot, violatedAssert))
        }

        return HandleCmdResult.Continue
    }

    private fun handleCvlLabelStart(value: CVLReportLabel, labelId: Int): HandleCmdResult {
        val instance = CallInstance.LabelInstance(value, labelId)
        callTracePush(instance)
        return HandleCmdResult.Continue
    }

    private fun handleCvlLabelEnd(id: Int): HandleCmdResult {
        return ensureStackState(
            requirement = { (it as? SnippetCmd.CVLSnippetCmd.EventID)?.id == id },
            eventDescription = "event with ID $id",
            callback = { }
        )
    }

    private fun handleInternalFuncStart(internalFuncStartAnnot: InternalFuncStartAnnotation): HandleCmdResult {
        val funcName = internalFuncStartAnnot.methodSignature.qualifiedMethodName.toString()

        val caller = currCallInstance.ancestorOfType<CallInstance.InvokingInstance.SolidityInvokeInstance>()
        val callIndexOfCaller = when (caller) {
            is CallInstance.InvokingInstance.SolidityInvokeInstance.External -> caller.callIndex
            is CallInstance.InvokingInstance.SolidityInvokeInstance.Internal -> caller.callIndexOfCaller
            else -> {
                return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "Missing a caller for the internal function $funcName (id = ${internalFuncStartAnnot.id})"})
            }
        }

        val instance = CallInstance.InvokingInstance.SolidityInvokeInstance.Internal(
            funcName,
            internalFuncStartAnnot.id,
            callIndexOfCaller,
            internalFuncStartAnnot,
            formatter,
        )

        val paramValues = internalFuncStartAnnot.args
            .filter { it.sort == InternalArgSort.SCALAR || it.sort == InternalArgSort.CALLDATA_ARRAY_ELEMS }
            .associateNotNull { arg ->
                val pos = internalFuncStartAnnot.stackOffsetToArgPos[arg.offset] ?: return@associateNotNull null
                val tacValue = model.valueAsTACValue(arg.s) ?: return@associateNotNull null

                pos to tacValue
            }

        val dump = CallInputsAndOutputs.Dump.Parameters(instance.paramTypes, instance.paramNames)
        callInputsAndOutputs.writeDumpToCall(dump, paramValues, instance)

        Logger.regression {
            "CallTrace: internal call: (internal) ${internalFuncStartAnnot.methodSignature.prettyPrint()}"
        }
        callTracePush(instance)

        return HandleCmdResult.Continue
    }

    val printer =
        if (Config.CallTraceTextDump.get()) {
            CallTracePrettyPrinter(ruleName, model)
        } else {
            null
        }

    /** Push one level to the call stack. */
    protected fun callTracePush(instance: CallInstance.ScopeInstance) {
        callTraceAppend(instance)
        currCallInstance = instance
        printer?.push(instance)
    }

    /** Append a node (representing a command or similar) to the current level of the call stack. */
    protected fun callTraceAppend(instance: CallInstance) {
        currCallInstance.addChild(instance)
        printer?.append(instance)
    }

    /** Pop one level of the call stack; returns null when we're already at top level (i.e. [currCallInstance] has no
     * parent) */
    protected fun callTracePop(): CallInstance.ScopeInstance? {
        val parent = currCallInstance.parent
        if (parent != null) {
            currCallInstance = parent
            printer?.pop()
        }
        return parent
    }


    private fun handleInternalFuncExit(internalFuncExitAnnot: InternalFuncExitAnnotation): HandleCmdResult {
        val funcName = internalFuncExitAnnot.methodSignature.qualifiedMethodName.toString()

        return ensureStackState(
            requirement = { it is CallInstance.InvokingInstance.SolidityInvokeInstance.Internal && it.id == internalFuncExitAnnot.id },
            eventDescription = "start of internal function $funcName (id = ${internalFuncExitAnnot.id})",
            allowedToPop = { it is CallInstance.BranchInstance.Start },
            allowedToFail = true,
            callback = {
                val internalInvokingInstance = it as CallInstance.InvokingInstance.SolidityInvokeInstance.Internal

                val internalReturnValues = internalFuncExitAnnot.rets.retValsInModel(model)

                val dump = CallInputsAndOutputs.Dump.ReturnValues(internalInvokingInstance.returnTypes)
                callInputsAndOutputs.writeDumpToCall(dump, internalReturnValues, internalInvokingInstance)

                globalState?.computeGlobalState(formatter = formatter)?.let(::callTraceAppend)

                Logger.regression {
                    "$internalInvokingInstance / ${internalInvokingInstance.returnValuesToString()}"
                }
            }
        )
    }

    private fun handleStackPush(pushRecord: Inliner.CallStack.PushRecord): HandleCmdResult {
        val newInstance = CallInstance.InvokingInstance.SolidityInvokeInstance.External(
            formatCallee(pushRecord.callee, scene) ?: "unknown callee",
            pushRecord.calleeId,
            pushRecord.callee.attr == MethodAttribute.Unique.Constructor,
            pushRecord.callee.resolveIn(scene)?.evmExternalMethodInfo,
            formatter,
        )


        val caller = (currCallInstance as? CallInstance.InvokingInstance<*>)
        // an imho more correct version, but not worth it just for the regtests:
        // currCallInstance.ancestorOfType<CallInstance.InvokingInstance<*>>()
        Logger.regression {
            "CallTrace: caller: " + (caller?.toStringWithoutParamValues()
                ?: currCallInstance.name) + " callee: " + newInstance.toStringWithoutParamValues()
        }
        callTracePush(newInstance)
        return HandleCmdResult.Continue
    }

    private fun handleStackPop(popRecord: Inliner.CallStack.PopRecord): HandleCmdResult {
        val calleeName = formatCallee(popRecord.callee, scene) ?: "unknown callee"

        return ensureStackState(
            requirement = { it is CallInstance.InvokingInstance.SolidityInvokeInstance.External && it.callIndex == popRecord.calleeId },
            eventDescription = "start of external function $calleeName (id = ${popRecord.calleeId})",
            allowedToPop = { it is CallInstance.BranchInstance.Start },
            callback = {
                val call = it as CallInstance.InvokingInstance.SolidityInvokeInstance.External
                callInputsAndOutputs.finalizeExternalCall(call)

                Logger.regression {
                    "$call / ${call.returnValuesToString()}"
                }
            }
        )
    }

    private fun handleRevertPath(cmd: TACCmd.Simple, currBlock: NBId, idx: Int): HandleCmdResult {
        currCallInstance.status = CallEndStatus.REVERT

        val revertSliceResults = try {
            DynamicSlicer(program, model, scene).sliceRevertCond(LTACCmd(CmdPointer(currBlock, idx), cmd).narrow())
        } catch (e: Exception) {
            logger.warn(e) {
                "Failed to slice the revert condition @ ${
                    CmdPointer(
                        currBlock,
                        idx
                    )
                } while constructing the call trace on the rule $ruleName"
            }
            null
        }
        if (revertSliceResults != null) {
            val revertCauseInstance = CallInstance.InvokingInstance.RevertCauseHeaderInstance().also {
                callTraceAppend(it)
            }
            val srcDetailsInstanceOrNull = revertSliceResults.sourceDetails?.let {
                val srcDetailsInstance = CallInstance.InvokingInstance.RevertCauseSrcDetailsInstance(it)
                revertCauseInstance.addChild(srcDetailsInstance)
                srcDetailsInstance
            }
            revertSliceResults.sliceExprPrintRep.let {
                val inst = srcDetailsInstanceOrNull ?: revertCauseInstance
                inst.addChild(CallInstance.InvokingInstance.RevertCauseDumpInstance(it))
            }
        }

        return ensureStackState(
            requirement = { it !is CallInstance.InvokingInstance.SolidityInvokeInstance.Internal },
            eventDescription = "Solidity function call",
            allowedToPop = { it is CallInstance.BranchInstance || it is CallInstance.InvokingInstance.SolidityInvokeInstance.Internal },
            callback = {
                it.status = CallEndStatus.REVERT
                currCallInstance = it
            }
        )
    }

    private fun handleSummaryStart(summStart: SummaryStack.SummaryStart, currBlock: NBId, idx: Int): HandleCmdResult {
        val summaryInstance = CallInstance.InvokingInstance.SummaryInstance(currBlock, idx, summStart, scene, formatter)

        callTracePush(summaryInstance)
        return HandleCmdResult.Continue
    }

    private fun handleExternalSummaryEnd(externalSummaryEnd: SummaryStack.SummaryEnd.External): HandleCmdResult {
        return ensureStackState(
            requirement = { it is CallInstance.InvokingInstance.SummaryInstance },
            eventDescription = "END_EXTERNAL_SUMMARY for ${externalSummaryEnd.appliedSummary}"
        )
    }

    private fun handleInternalSummaryEnd(internalFuncExitAnnot: SummaryStack.SummaryEnd.Internal): HandleCmdResult {
        return ensureStackState(
            requirement = { it is CallInstance.InvokingInstance.SummaryInstance },
            eventDescription = "END_INTERNAL_SUMMARY for function ${internalFuncExitAnnot.methodSignature.prettyPrint()}",
            callback = {
                val invokingInstance = it as CallInstance.InvokingInstance.VMInvokingInstance
                val returnValues = internalFuncExitAnnot.rets?.retValsInModel(model).orEmpty()
                val dump = CallInputsAndOutputs.Dump.ReturnValues(invokingInstance.returnTypes)
                callInputsAndOutputs.writeDumpToCall(dump, returnValues, invokingInstance)
            }
        )
    }

    private fun handleInlinedHook(cmd: SnippetCmd.CVLSnippetCmd.InlinedHook, labelId: Int): HandleCmdResult {
        val hookHeader = cmd.cvlPattern.toHookApplicationHeader(labelId)

        val substitutions = cmd.substitutions.entries.sortedBy { it.key.name }

        if (substitutions.isNotEmpty()) {
            val paramExplanationsHeader = CallInstance.LabelInstance("With parameters:")
            hookHeader.addChild(paramExplanationsHeader)

            for ((subbedParam, hook) in substitutions) {
                val subName = subbedParam.name
                val hookValue = model.instantiate(hook)?.value
                val hookDescription = hookValue?.let { "0x" + it.toString(16) } ?: "?"
                val labelInstance = CallInstance.LabelInstance("$subName = $hookDescription")
                paramExplanationsHeader.addChild(labelInstance)
            }
        }

        callTracePush(hookHeader)
        return HandleCmdResult.Continue
    }

    private fun handleStartLoopSnippet(snippetCmd: SnippetCmd.LoopSnippet.StartLoopSnippet): HandleCmdResult {
        val snippetCallInstance = CallInstance.LoopInstance.Start(
            snippetCmd.loopSource,
            snippetCmd.loopIndex
        )
        Logger.regression {
            snippetCmd.loopSource
        }
        callTracePush(snippetCallInstance)
        return HandleCmdResult.Continue
    }

    private fun handleEndLoopSnippet(snippetCmd: SnippetCmd.LoopSnippet.EndLoopSnippet): HandleCmdResult {
        return ensureStackState(
            requirement = { it is CallInstance.LoopInstance.Start && it.id == snippetCmd.loopId },
            eventDescription = "start of loop ${snippetCmd.loopId}",
            allowedToPop = { it is CallInstance.BranchInstance.Start },
        )
    }

    private fun handleStartIterSnippet(snippetCmd: SnippetCmd.LoopSnippet.StartIter): HandleCmdResult {
        val snippetCallInstance = CallInstance.LoopInstance.Iteration(
            "Loop Iteration ${snippetCmd.iteration}",
            snippetCmd.iteration
        )
        Logger.regression {
            "Loop Iteration ${snippetCmd.iteration}"
        }
        callTracePush(snippetCallInstance)
        return HandleCmdResult.Continue
    }

    private fun handleEndIterSnippet(snippetCmd: SnippetCmd.LoopSnippet.EndIter): HandleCmdResult {
        return ensureStackState(
            requirement = { it is CallInstance.LoopInstance.Iteration && it.iteration == snippetCmd.iteration },
            eventDescription = "start of loop iteration ${snippetCmd.iteration}",
            allowedToPop = { it is CallInstance.BranchInstance.Start },
        )
    }

    /**
     * Creates a [CallInstance] for [snippetCmd] and adds it to the CallHierarchy starting at [callHierarchyRoot].
     */
    fun handleAssertSnippet(snippetCmd: AssertSnippet<*>, cmd: TACCmd.Simple, currBlock: NBId, idx: Int): HandleCmdResult {
        val assertCondValuation = model.valueAsBoolean(snippetCmd.assertCond)
        val (assertViolationOrErrorMsg, assertHasPassedOrNull) = assertCondValuation.toValue(
            l = { assertCondValue: Boolean ->
                if (assertCondValue) {
                    "passed" to true
                } else {
                    "failed" to false
                }
            },
            r = { cexResolvingFailure ->
                // We failed to get the value of the assert condition.
                // Therefore, we cannot know whether the enclosed assert
                // has failed or not. In this case, we die
                val errorMsg =
                    cexResolvingFailure(snippetCmd.assertCond)
                logger.error { errorMsg }
                // The [ScopeSnippet] guarantees the CallTrace construction
                // to fail later when it reaches the enclosed assert command
                errorMsg to null
            }
        )
        val (assertSnippetCallInstance, assertCheckMsg) = when (snippetCmd) {
            is SnippetCmd.CVLSnippetCmd.DivZero -> {
                CallInstance.DivZeroInstance(
                    snippetCmd.assertCmdLabel,
                    snippetCmd.range as? Range.Range,
                ) to "Check division by zero for ${snippetCmd.assertCmdLabel}: $assertViolationOrErrorMsg"
            }

            is SnippetCmd.CVLSnippetCmd.AssertCast -> {
                CallInstance.CastCheckInstance(
                    "assert-cast check $assertViolationOrErrorMsg",
                    snippetCmd.range as? Range.Range,
                ) to "assert-cast check: $assertViolationOrErrorMsg"
            }

            is SnippetCmd.LoopSnippet.AssertUnwindCond -> {
                CallInstance.LoopInstance.AssertUnwindCond(
                    "Assert 'loop has terminated' $assertViolationOrErrorMsg"
                ) to "Assert 'loop has terminated' $assertViolationOrErrorMsg"
            }

            is SnippetCmd.CVLSnippetCmd.ViewReentrancyAssert -> {
                CallInstance.ViewReentrancyInstance(snippetCmd.msg, snippetCmd.range) to snippetCmd.msg
            }
        }
        if (assertHasPassedOrNull != true || snippetCmd.displayIfPassed) {
            callTraceAppend(assertSnippetCallInstance)
            if (snippetCmd is WithParseTree) {
                try {
                    evaldCVLExpBuilder.materializeCVLBoolCondExpInfo(
                        snippetCmd.cvlExpOutSym,
                        assertSnippetCallInstance
                    )
                } catch (e: IllegalStateException) {
                    logger.error(e) { "Failed to generate a parse tree for an expression in an assert statement ($cmd" }
                }
            }
        }
        return if (assertHasPassedOrNull != null) {
            Logger.regression { assertCheckMsg }
            if (!assertHasPassedOrNull) {
                val violatedAssert = LTACCmd(CmdPointer(currBlock, idx), cmd)
                HandleCmdResult.GeneratedCallTrace(CallTrace.ViolationFound(callHierarchyRoot, violatedAssert))
            } else {
                HandleCmdResult.Continue
            }
        } else {
            HandleCmdResult.Continue
        }
    }

    /**
     * this function checks this section for a [SnippetCmd.CVLSnippetCmd.InlinedHook] annotation,
     * because we merge these with the start label of the section.
     *
     * we define a "section" as the commands between a [CVL_LABEL_START] and a [CVL_LABEL_END].
     * we expect this section to be entirely contained within a single block.
     */
    private fun findInlinedHookInSection(ptr: CmdPointer): SnippetCmd.CVLSnippetCmd.InlinedHook? {
        return graph
            .iterateBlock(ptr, excludeStart = true)
            .mapNotNull { it.cmd as? TACCmd.Simple.AnnotationCmd }
            .takeWhile { it.annot.k != TACMeta.CVL_LABEL_START && it.annot.k != TACMeta.CVL_LABEL_END }
            .firstMapped { it.annot.v as? SnippetCmd.CVLSnippetCmd.InlinedHook }
    }


    /** generates the call trace by iterating over the reachable commands of [program] in topological order. */
    fun generate(): CallTrace {
        globalState?.computeGlobalState(formatter = formatter)?.let(::callTraceAppend)

        blocks.forEachIndexed { blockIdx, currBlock ->
            val block = program.code[currBlock] ?: return callTraceFailure { "unknown block $currBlock." }
            block.forEachIndexed inner@{ cmdIdx, cmd ->
                printer?.visit(LTACCmd(CmdPointer(currBlock, cmdIdx), cmd))

                val handleCmdResult: HandleCmdResult = if (cmd.meta.containsKey(TACMeta.IGNORE_IN_CALLTRACE)) {
                    HandleCmdResult.Continue
                } else {
                    handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                }

                if (handleCmdResult is HandleCmdResult.GeneratedCallTrace) {
                    // finalize all open calls
                    // the failing assert may be before their end, and without this we won't see their arguments and return values.
                    var currInstance: CallInstance.ScopeInstance? = currCallInstance
                    while (currInstance != null) {
                        when (val inst = currInstance) {
                            is CallInstance.InvokingInstance.CVLFunctionInstance -> callInputsAndOutputs.finalizeCVLCall(inst)
                            is CallInstance.InvokingInstance.SolidityInvokeInstance.External -> callInputsAndOutputs.finalizeExternalCall(inst)
                            else -> {}
                        }
                        currInstance = currInstance.parent
                    }
                    return handleCmdResult.callTrace
                }
            }
        }
        return callTraceFailure { "did not reach a violated assert command in $ruleName" }
    }

    /**
     * Handle the generation of the call trace when the current command is [cmd].
     * Return a [CallTrace] if the call trace generation should be halted, and [HandleCmdResult.Continue] if the
     * generation can continue.
     */
    open fun handleCmd(cmd: TACCmd.Simple, cmdIdx: Int, currBlock: NBId, blockIdx: Int): HandleCmdResult {
        return when (cmd) {
            is TACCmd.Simple.AssigningCmd -> handleAssigningCmd(cmd)
            is TACCmd.Simple.AssumeCmd -> handleAssumeCmd(cmd)
            is TACCmd.Simple.AssertCmd -> handleAssert(cmd, currBlock, cmdIdx)
            is TACCmd.Simple.AnnotationCmd -> {
                val (meta, value) = cmd.annot
                when (meta) {
                    TACMeta.CVL_LABEL_START -> {
                        val label = value as CVLReportLabel
                        val labelId = cmd.meta[TACMeta.CVL_LABEL_START_ID]
                            ?: return HandleCmdResult.GeneratedCallTrace(callTraceFailure { "missing label id for start label: `$label`" })
                        val ptr = CmdPointer(currBlock, cmdIdx)
                        val inlinedHook = findInlinedHookInSection(ptr)

                        if (inlinedHook != null) {
                            // we handle this here _instead_ of the start label.
                            // then later on, when we reach this inlined hook annotation,
                            // we skip it.
                            handleInlinedHook(inlinedHook, labelId)
                        } else {
                            handleCvlLabelStart(label, labelId)
                        }
                    }

                    TACMeta.CVL_LABEL_END -> {
                        handleCvlLabelEnd(value as Int)
                    }

                    TACMeta.SNIPPET -> {
                        when (val snippetCmd = value as SnippetCmd) {
                            is SnippetCmd.LoopSnippet.StartLoopSnippet -> handleStartLoopSnippet(snippetCmd)
                            is SnippetCmd.LoopSnippet.EndIter -> handleEndIterSnippet(snippetCmd)
                            is SnippetCmd.LoopSnippet.StartIter -> handleStartIterSnippet(snippetCmd)
                            is SnippetCmd.LoopSnippet.EndLoopSnippet -> handleEndLoopSnippet(snippetCmd)
                            is AssertSnippet<*> -> {
                                handleAssertSnippet(snippetCmd, cmd, currBlock, cmdIdx)
                            }

                            SnippetCmd.SnippetCreationDisabled -> {
                                HandleCmdResult.Continue
                            }

                            is SnippetCmd.EVMSnippetCmd,
                            is SnippetCmd.CVLSnippetCmd,
                            is SnippetCmd.CvlrSnippetCmd,
                            is SnippetCmd.SolanaSnippetCmd -> {
                                throw IllegalStateException("${snippetCmd::class.simpleName} snippet command handled in shared statement handler")
                            }
                        }
                    }

                    /*
                       Some exit annotation are missing corresponding start annotation, and some start annotation are missing
                       corresponding exit annotations. Given an exit annotation, if we find a corresponding start annotation,
                       we pop from the stack until we are getting to the (promised exist) start annotation, and continue
                       to build the callTrace (basically, skip all the things between them). Otherwise, we just ignore,
                       and throw a warning.
                     */
                    INTERNAL_FUNC_START -> {
                        handleInternalFuncStart(value as InternalFuncStartAnnotation)
                    }

                    INTERNAL_FUNC_EXIT -> {
                        handleInternalFuncExit(value as InternalFuncExitAnnotation)
                    }

                    Inliner.CallStack.STACK_PUSH -> {
                        handleStackPush(value as Inliner.CallStack.PushRecord)
                    }

                    Inliner.CallStack.STACK_POP -> {
                        handleStackPop(value as Inliner.CallStack.PopRecord)
                    }

                    TACMeta.REVERT_PATH -> {
                        handleRevertPath(cmd, currBlock, cmdIdx)
                    }

                    SummaryStack.START_EXTERNAL_SUMMARY, SummaryStack.START_INTERNAL_SUMMARY -> {
                        handleSummaryStart(value as SummaryStack.SummaryStart, currBlock, cmdIdx)
                    }

                    SummaryStack.END_EXTERNAL_SUMMARY -> {
                        handleExternalSummaryEnd(value as SummaryStack.SummaryEnd.External)
                    }

                    SummaryStack.END_INTERNAL_SUMMARY -> {
                        handleInternalSummaryEnd(value as SummaryStack.SummaryEnd.Internal)
                    }

                    SBF_INLINED_FUNCTION_START, SBF_INLINED_FUNCTION_END,
                    WASM_INLINED_FUNC_START, WASM_INLINED_FUNC_END, WASM_USER_ASSUME, WASM_USER_ASSERT -> {
                        throw IllegalStateException("$meta handled in shared statement handler")
                    }

                    else -> HandleCmdResult.Continue
                }
            }

            else -> HandleCmdResult.Continue
        }
    }

}

/** Returns a map from the word index to its [TACValue], if such a value exists in [model] */
private fun Iterable<InternalFuncRet>.retValsInModel(model: CounterexampleModel): SortedMap<Int, TACValue> {
    val vals = sortedMapOf<Int, TACValue>()

    for ((s, offset) in this) {
        val retVal = model.valueAsTACValue(s)
        if (retVal != null) {
            vals[offset] = retVal
        } else {
            logger.warn { "Value $s at offset $offset, was not found in the model" }
        }
    }

    return vals
}

private fun formatCallee(callee: MethodRef, scene: ISceneIdentifiers): String? {
    val (contract, method) = callee.getContractAndMethod(scene) ?: return null

    val caller = if (contract is IClonedContract) {
        val parentContract = scene.getContractOrNull(contract.sourceContractId) ?: return null
        "${parentContract.name}[${contract.createdContractInstance}]"
    } else {
        contract.name
    }

    val methodUIName = if (method.isFallback) {
        CVLReservedVariables.certoraFallbackDisplayName
    } else {
        method.name
    }
    return "$caller.${methodUIName}"
}

private fun CVLHookPattern.toHookApplicationHeader(labelId: Int): CallInstance.LabelInstance {
    val patternDescription = when (this) {
        is CVLHookPattern.Create -> "create"

        is CVLHookPattern.StoragePattern.Load -> "load ${value.id} := $slot"

        is CVLHookPattern.StoragePattern.Store -> when (val previousValue = previousValue) {
            null -> "store $slot := ${value.id}"
            else -> "store $slot := ${value.id} (old: ${previousValue.id})"
        }

        is CVLHookPattern.Opcode -> when {
            this is PatternWithValue && params.isNotEmpty() -> {
                val joinedParams = params.joinToString(separator = ", ") { param -> param.id }
                "$name($joinedParams) returns ${value.id}"
            }

            this is PatternWithValue -> "$name returns ${value.id}"

            else -> "$name($params)"
        }
    }

    return CallInstance.LabelInstance("Apply hook $patternDescription", labelId = labelId)
}

