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

package report.calltrace.generator

import analysis.CmdPointer
import analysis.icfg.Summarization
import config.Config
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import log.*
import report.calltrace.*
import report.calltrace.formatter.CallTraceValue
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.printer.VariableType
import report.calltrace.sarif.FmtArg
import report.calltrace.sarif.Sarif
import report.calltrace.sarif.SarifBuilder
import report.calltrace.sarif.sarifForStorageLocation
import report.globalstate.toInstantiatedDisplayPath
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import utils.Range
import spec.cvlast.CVLType
import spec.cvlast.SpecCallSummary
import spec.cvlast.StorageBasis
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import spec.rules.IRule
import tac.NBId
import utils.*
import vc.data.CoreTACProgram
import vc.data.SnippetCmd
import vc.data.TACCmd
import vc.data.TACMeta

private val logger = Logger(LoggerTypes.CALLTRACE)

/**
 * This class manages the generation of the call trace when analyzing an EVM project.
 * It specifically handles EVM and CVL-related commands, delegating the ones it cannot process to its superclass.
 */
internal class EVMCallTraceGenerator(
    rule: IRule,
    model: CounterexampleModel,
    program: CoreTACProgram,
    formatter: CallTraceValueFormatter,
    scene: ISceneIdentifiers,
    ruleCallString: String,
) : CallTraceGenerator(rule, model, program, formatter, scene, ruleCallString) {
    override fun handleCmd(cmd: TACCmd.Simple, cmdIdx: Int, currBlock: NBId, blockIdx: Int): HandleCmdResult {
        return when (cmd) {
            is TACCmd.Simple.AnnotationCmd -> {
                val (meta, value) = cmd.annot
                when (meta) {
                    TACMeta.SNIPPET -> {
                        when (val snippetCmd = value as SnippetCmd) {
                            is SnippetCmd.EVMSnippetCmd -> {
                                when (snippetCmd) {
                                    is SnippetCmd.EVMSnippetCmd.StorageSnippet -> handleStorageSnippet(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet -> handleBalanceSnippet(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.HavocBalanceSnippet -> handleHavocBalanceSnippet(currBlock, cmdIdx)
                                    is SnippetCmd.EVMSnippetCmd.TransferSnippet -> handleTransferSnippet(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet -> handleStorageGlobalChangeSnippet(snippetCmd, currBlock, cmdIdx)
                                    is SnippetCmd.EVMSnippetCmd.ContractSourceSnippet.AssignmentSnippet -> handleAssignmentSnippet(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite -> handleEVMFunctionReturnWrite(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet -> handleStartBranchSnippet(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet -> handleEndBranchSnippet(snippetCmd, blockIdx, cmdIdx)
                                    is SnippetCmd.EVMSnippetCmd.HaltSnippet -> handleHaltSnippet(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.SourceFinderSnippet.LocalAssignmentSnippet -> handleLocalAssignmentSnippet(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.RawStorageAccess -> handleRawStorageSnippet(snippetCmd)
                                    is SnippetCmd.EVMSnippetCmd.CopyLoopSnippet -> handleCopyLoop()
                                }

                            }

                            is SnippetCmd.CVLSnippetCmd -> {
                                when (snippetCmd) {
                                    is SnippetCmd.CVLSnippetCmd.IfStart -> handleIfStart(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.BranchStart -> handleBranchStart(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.EVMFunctionInvCVLValueTypeArg -> handleEVMFunctionInvCVLValueTypeArg(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.StorageDisplay -> handleStorageDisplay(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.StorageComparison -> handleStorageComparison(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.CVLFunctionStart -> handleCVLFunctionStart(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.CVLFunctionEnd -> handleCVLFunctionEnd(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.CVLArg -> handleCVLFunctionArg(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.CVLRet -> handleCVLFunctionRet(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.DispatcherSummaryDefault -> handleDispatchDefaultCase(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.GhostRead -> handleGhostAccessSnippet(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.GhostAssignment -> handleGhostAccessSnippet(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.GhostHavocSnippet -> handleGhostHavocSnippet(snippetCmd, currBlock, cmdIdx)
                                    is SnippetCmd.CVLSnippetCmd.HavocAllGhostsSnippet -> handleAllGhostsHavocSnippet(currBlock, cmdIdx)
                                    is SnippetCmd.CVLSnippetCmd.SumGhostRead -> handleGhostAccessSnippet(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.SumGhostUpdate -> handleGhostAccessSnippet(snippetCmd)
                                    is SnippetCmd.CVLSnippetCmd.AssertCast -> handleAssertSnippet(snippetCmd, cmd, currBlock, cmdIdx)
                                    is SnippetCmd.CVLSnippetCmd.DivZero -> handleAssertSnippet(snippetCmd, cmd, currBlock, cmdIdx)
                                    is SnippetCmd.CVLSnippetCmd.ViewReentrancyAssert -> handleAssertSnippet(snippetCmd, cmd, currBlock, cmdIdx)
                                    is SnippetCmd.CVLSnippetCmd.InlinedHook -> {
                                        /**
                                         * we _do not_ handle this here. we handle this in the [CallTraceGenerator.handleCmd] method.
                                         *
                                         * yes, that's kinda weird. see [findInlinedHookInSection].
                                         */
                                        HandleCmdResult.Continue
                                    }
                                    is SnippetCmd.CVLSnippetCmd.Start, is SnippetCmd.CVLSnippetCmd.End -> {
                                        //These snippets are handled by DebugAdapterProtocol
                                        HandleCmdResult.Continue
                                    }
                                }
                            }

                            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                        }
                    }
                    else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                }
            }
            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
        }.also {
            /**
             * If we also generate the call trace for the debug adapter, we pass on all
             * (global) variable information at each step to further consume it by
             * [report.calltrace.printer.DebugAdapterProtocolStackMachine]
             */
            debugAdapter?.let { da ->
                globalState?.let { gs ->
                    gs.balancesState.toInstantiateDisplayWithValue().forEachEntry {
                        da.updateVariables(it.key, it.value, VariableType.BALANCES_CALLTRACE)
                    }

                    gs.ghostsState.toInstantiateDisplayWithValue().forEachEntry {
                        da.updateVariables(it.key, it.value, VariableType.GHOST_STATE_CALLTRACE)
                    }

                    gs.storageState.toInstantiateDisplayWithValue().forEachEntry {
                        da.updateVariables(it.key, it.value, VariableType.STORAGE_STATE_CALLTRACE)
                    }
                }
            }
        }
    }

    private fun handleRawStorageSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.RawStorageAccess): HandleCmdResult {
        val isLoad = snippetCmd.isLoad
        val referredStorageMsg = snippetCmd.referredStorageMessage()
        val range = snippetCmd.range
        val builder = SarifBuilder()
        val callInstance =
            if (isLoad) {
                builder.append("Load from ")
                builder.append(referredStorageMsg)
                CallInstance.StorageInstance.Load(builder.build(), range)
            } else {
                builder.append("Store at ")
                builder.append(referredStorageMsg)
                CallInstance.StorageInstance.Store(builder.build(), range)
            }
        callTraceAppend(callInstance)
        return HandleCmdResult.Continue
    }

    private fun handleBalanceSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet): HandleCmdResult {
        globalState?.handleBalanceSnippet(snippetCmd)
        val addr = FmtArg.CtfValue.buildOrUnknown(
            tv = model.valueAsTACValue(snippetCmd.addr),
            type = EVMTypeDescriptor.address,
            tooltip = "address"
        )
        val balance =
            FmtArg.CtfValue.buildOrUnknown(
                tv = model.valueAsTACValue(snippetCmd.balance),
                type = EVMTypeDescriptor.UIntK(256),
                tooltip = "balance",
            )
        val sarif = sarifFormatter.fmt("{}.balance{}{}", addr, FmtArg.To, balance)

        val balanceInstance = CallInstance.BalanceInstance(sarif)
        callTraceAppend(balanceInstance)
        return HandleCmdResult.Continue
    }

    private fun handleHavocBalanceSnippet(currBlock: NBId, idx: Int): HandleCmdResult {
        globalState?.handleHavocBalance(currBlock, idx)
        return HandleCmdResult.Continue
    }

    private fun handleStorageGlobalChangeSnippet(
        snippetCmd: SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet,
        currBlock: NBId,
        idx: Int
    ): HandleCmdResult {
        globalState?.handleStorageGlobalChanges(snippetCmd, currBlock, idx)
        return HandleCmdResult.Continue
    }

    private fun handleCopyLoop(): HandleCmdResult {
        val snippetCallInstance = CallInstance.LoopInstance.CopyLoop()
        Logger.regression {
            snippetCallInstance.name
        }
        callTraceAppend(snippetCallInstance)

        return HandleCmdResult.Continue
    }

    private fun handleAssignmentSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.ContractSourceSnippet.AssignmentSnippet): HandleCmdResult {
        val label = with(snippetCmd.parse) {
            val typ = data.typ ?: CVLType.PureCVLType.Primitive.UIntK(256) // TO DO propagate the type from Solidity AST

            val currentValue =
                CallTraceValue.cvlCtfValueOrUnknown(model.valueAsTACValue(snippetCmd.lhs), typ)
                    .toSarif(formatter, "current value")
                    .flatten()

            val fileName = originalSource.fileName
            val content = originalSource.content.condense()

            Logger.regression {
                val dummy = "*"
                if (data.typ != null) {
                    "Assignment: ${data.name} (type: ${data.typ}) = $dummy"
                } else {
                    "Assignment: ${data.name} = $dummy"
                }
            }

            "$fileName:${originalSource.lineNumber}:$content /* ${data.name} = $currentValue */"
        }

        val range = snippetCmd.parse.originalSource.range

        callTraceAppend(CallInstance.ContractSourceInstance.Assignment(label, range))
        return HandleCmdResult.Continue
    }

    private fun handleEVMFunctionReturnWrite(snippetCmd: SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite): HandleCmdResult {
        val movementCallIndex = currCallInstance.ancestorOfType<CallInstance.InvokingInstance.SolidityInvokeInstance>(
            // maybe overprotective, we could have other solidity calls, we don't want to blindly take a different ancestor of the same type
            allowedToSkip = { it is CallInstance.BranchInstance || it is CallInstance.LoopInstance }
        )?.callIndex

        if (movementCallIndex != null && callInputsAndOutputs.externalCall(movementCallIndex) != null) {
            val movement = snippetCmd.returndataMovement(movementCallIndex)
            callInputsAndOutputs.registerReturndataMovement(movement, movementCallIndex)
        }
        return HandleCmdResult.Continue
    }

    private fun handleTransferSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.TransferSnippet): HandleCmdResult {
        globalState?.handleTransferSnippet(snippetCmd)
        val sender = FmtArg.CtfValue.buildOrUnknown(
            tv = model.valueAsTACValue(snippetCmd.srcAccountInfo.addr),
            type = EVMTypeDescriptor.address,
            tooltip = "address of sender"
        )
        val receiver = FmtArg.CtfValue.buildOrUnknown(
            tv = model.valueAsTACValue(snippetCmd.trgAccountInfo.addr),
            type = EVMTypeDescriptor.address,
            tooltip = "address of receiver"
        )
        val transferred = FmtArg.CtfValue.buildOrUnknown(
            tv = model.valueAsTACValue(snippetCmd.transferredAmount),
            type = EVMTypeDescriptor.UIntK(256),
            tooltip = "transferred amount"
        )
        val sarif =
            sarifFormatter.fmt("sender: {}; receiver: {}; transferred amount: {}", sender, receiver, transferred)

        val snippetCallInstance = CallInstance.TransferInstance(sarif, CallEndStatus.TRANSFER)

        callTraceAppend(snippetCallInstance)

        addBalanceSnippetCallInstance(
            parent = snippetCallInstance,
            sym = snippetCmd.srcAccountInfo.old,
            isLoad = true,
            isSender = true
        )
        addBalanceSnippetCallInstance(
            parent = snippetCallInstance,
            sym = snippetCmd.srcAccountInfo.new,
            isLoad = false,
            isSender = true
        )
        addBalanceSnippetCallInstance(
            parent = snippetCallInstance,
            sym = snippetCmd.trgAccountInfo.old,
            isLoad = true,
            isSender = false
        )
        addBalanceSnippetCallInstance(
            parent = snippetCallInstance,
            sym = snippetCmd.trgAccountInfo.new,
            isLoad = false,
            isSender = false
        )

        return HandleCmdResult.Continue
    }


    private fun handleStartBranchSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet): HandleCmdResult {
        val snippetCallInstance = CallInstance.BranchInstance.Start(snippetCmd.branchSource, snippetCmd.branchIndex)

        Logger.regression {
            snippetCallInstance.name
        }
        callTraceAppend(snippetCallInstance)

        if (!Config.flattenBranchesInCallTrace.get()) {
            currCallInstance = snippetCallInstance
        }

        return HandleCmdResult.Continue
    }

    private fun handleEndBranchSnippet(
        snippetCmd: SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet,
        blockIndex: Int,
        cmdIdx: Int
    ): HandleCmdResult {
        if (currCallInstance.children.isEmpty()) {
            // check whether we want to display the branch in CallTrace or not, per Nurit's request to hide trivial branches.
            // trivial branches occur when there are no tac commands between start and end branch snippets,
            // e.g. as optimized away by require !lastReverted
            if (cmdIdx == 0 && blockIndex == 0) {
                return HandleCmdResult.GeneratedCallTrace(callTraceFailure { "invalid program, branch end is the first command in the first block." })
            }

            val prevCommand = if (cmdIdx > 0) {
                val blockId = blocks[blockIndex]
                val block = program.code[blockId] ?: return HandleCmdResult.GeneratedCallTrace(callTraceFailure { "unknown block $blockId." })
                block[cmdIdx - 1]
            } else {
                val blockId = blocks[blockIndex - 1]
                val block = program.code[blockId] ?: return HandleCmdResult.GeneratedCallTrace(callTraceFailure { "unknown block $blockId." })
                block.lastOrNull() ?: return HandleCmdResult.GeneratedCallTrace(callTraceFailure { "previous block $blockId is empty." })
            }

            prevCommand.maybeAnnotation(TACMeta.SNIPPET)?.let { prevSnippetCmd ->
                if (prevSnippetCmd is SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet && prevSnippetCmd.branchIndex == snippetCmd.branchIndex) {
                    check(currCallInstance.parent?.children?.last() == currCallInstance) {
                        "Invalid state of call stack, current instance is not the last child of its parent."
                    }
                    currCallInstance.parent?.children?.removeLastOrNull()
                }
            }
        }

        val requirement: (CallInstance.ScopeInstance) -> Boolean =
            { it is CallInstance.BranchInstance.Start && it.id == snippetCmd.branchIndex }

        if (requirement(currCallInstance)) {
            callTracePop()
                ?: return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "Try to access parent of $currCallInstance when looking for branch ${snippetCmd.branchIndex} end." })
            return HandleCmdResult.Continue
        }

        var curr: CallInstance.ScopeInstance? = currCallInstance

        while (curr != null && !requirement(curr)) {
            curr = curr.parent
        }

        if (curr != null) {
            return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "when branch ${snippetCmd.branchIndex} ends." })
        }

        logger.info {
            "Branch end of ${snippetCmd.branchIndex} did not meet a branch start at top of stack. " +
                "This is valid, as function return could have closed the branch already."
        }

        return HandleCmdResult.Continue
    }

    private fun handleLocalAssignmentSnippet(snippet: SnippetCmd.EVMSnippetCmd.SourceFinderSnippet.LocalAssignmentSnippet): HandleCmdResult {
        Logger.regression {
            "Local assignment ${snippet.lhs}" // do not include the value, it is not stable
        }
        when (snippet.finderType) {
            // refer to https://www.notion.so/certora/Logging-Solidity-in-calltrace-135fe5c14fd380eb8453cfd3c8629449?pvs=4#135fe5c14fd3804fa47cfd0acc9b614c
            0 -> {
                callTraceAppend(
                    CallInstance.LocalAssignmentInstance(
                        snippet.range,
                        sarifFormatter.fmt(
                            "{} {} {}",
                            FmtArg(snippet.lhs),
                            FmtArg.To,
                            FmtArg.CtfValue.buildOrUnknown(
                                model.valueAsTACValue(snippet.value),
                                EVMTypeDescriptor.UIntK(256) //xx pass better type info from solidity
                            )
                        )
                    )
                )
            }
            1 -> {
                callTraceAppend(CallInstance.NonSpecificDeclInstance(snippet.range, snippet.lhs))
            }
            else -> {
                callTraceAppend(CallInstance.NonSpecificDefInstance(snippet.range, snippet.lhs))
            }
        }
        return HandleCmdResult.Continue
    }

    private fun handleHaltSnippet(snippet: SnippetCmd.EVMSnippetCmd.HaltSnippet): HandleCmdResult {
        // if we are not in an 'interesting' scope instance like a branch, loop, or invoke, don't show it.
        // this effectively may say "all scopes" where the halt snippet can actually appear in,
        // and we should consider whether the invoke scope is 'too obvious' and noisy.
        if (currCallInstance is CallInstance.BranchInstance || currCallInstance is CallInstance.LoopInstance || currCallInstance is CallInstance.InvokingInstance<*>) {
            val range = snippet.range
            val haltInstance = when (snippet) {
                is SnippetCmd.EVMSnippetCmd.HaltSnippet.Return -> CallInstance.HaltInstance.Return(range)
                is SnippetCmd.EVMSnippetCmd.HaltSnippet.Revert -> CallInstance.HaltInstance.Revert(range)
            }
            callTraceAppend(haltInstance)
        }

        return HandleCmdResult.Continue
    }

    private fun handleStorageSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.StorageSnippet): HandleCmdResult {
        val referredStorageMsg = snippetCmd.referredStorageMessage()

        // only used for regression logging
        val pathAsString = snippetCmd.displayPath.toNonIndexedString()

        globalState?.handleStorageLocalChanges(snippetCmd)
        printer?.localStorageChange(snippetCmd)

        val range = snippetCmd.range
        val snippetCallInstance = when (snippetCmd) {
            is SnippetCmd.EVMSnippetCmd.StorageSnippet.StoreSnippet -> {
                Logger.regression {
                    "Store at: $pathAsString"
                }
                CallInstance.StorageInstance.Store(
                    sarifFormatter.fmt("Store at {}", FmtArg(referredStorageMsg)),
                    range,
                )
            }

            is SnippetCmd.EVMSnippetCmd.StorageSnippet.DirectStorageLoad,
            is SnippetCmd.EVMSnippetCmd.StorageSnippet.LoadSnippet -> {
                Logger.regression {
                    "Load from: $pathAsString"
                }
                CallInstance.StorageInstance.Load(
                    sarifFormatter.fmt("Load from {}", FmtArg(referredStorageMsg)),
                    range,
                )
            }

            is SnippetCmd.EVMSnippetCmd.StorageSnippet.DirectStorageHavoc -> {
                Logger.regression {
                    "Havoc of: $pathAsString"
                }
                /** do we really want to show the actual value here even though it was just havoced? */
                CallInstance.StorageInstance.Havoc(
                    sarifFormatter.fmt("Havoc of {}", FmtArg(referredStorageMsg)),
                    range,
                )
            }
        }
        callTraceAppend(snippetCallInstance)
        return HandleCmdResult.Continue
    }

    private fun SnippetCmd.EVMSnippetCmd.EVMStorageAccess.referredStorageMessage(): Sarif = SarifBuilder.mkSarif {
        val accessLocation = accessLocation()
        val tv = value?.let(model::valueAsTACValue)

        append(scene.getContract(contractInstance).name)
        append(".")
        append(accessLocation)

        if (tv != null) {
            val type = storageType ?: EVMTypeDescriptor.UIntK(EVM_BITWIDTH256)
            val addressArg = CallTraceValue.evmCtfValueOrUnknown(
                scalarValue = tv,
                type = type,
            ).toSarif(formatter, tooltip = "storage value")

            append(Sarif.EVALUATES_TO)
            append(addressArg)
        }

        printer?.snippet(this@referredStorageMessage as SnippetCmd)
    }

    private fun SnippetCmd.EVMSnippetCmd.EVMStorageAccess.accessLocation(): Sarif = when (this) {
        is SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithLocSym -> {
            val storageAddress =
                FmtArg.CtfValue.buildOrUnknown(
                    tv = model.valueAsTACValue(loc),
                    type = EVMTypeDescriptor.address,
                    tooltip = "address"
                )
            sarifFormatter.fmt("[raw storage address] {}", storageAddress)
        }

        is SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithPath ->
            sarifFormatter.fmt("[raw storage path] {}", FmtArg(path.toString()))

        is SnippetCmd.EVMSnippetCmd.StorageSnippet -> {
            val dp = this.displayPath.toDisplayString(formatter, model) // should eventually be a sarif
            sarifFormatter.fmt("{}", FmtArg(dp))
        }
    }

    private fun handleGhostAccessSnippet(sc: SnippetCmd.CVLSnippetCmd.GhostAccess): HandleCmdResult {
        val toolTip = when (sc) {
            is SnippetCmd.CVLSnippetCmd.GhostAssignment -> "value assigned to ghost"
            is SnippetCmd.CVLSnippetCmd.GhostRead -> "value read from ghost"
            is SnippetCmd.CVLSnippetCmd.SumGhostRead -> "value of sum"
            is SnippetCmd.CVLSnippetCmd.SumGhostUpdate -> "updated value of sum"
        }
        val accessedValueModel = model
            .valueAndPureCVLType(sc.accessed)
        val accessedValue = accessedValueModel
            .mapLeft { (tv, type) -> FmtArg.CtfValue.Companion.buildOrUnknown(tv, type, toolTip) }
            .leftOrElse { FmtArg.InlineSarif(CallTraceValueFormatter.unknown()) }

        val idp = sc
            .toInstantiatedDisplayPath(model)
            .mapLeft { it.toFormattedString(formatter) }
            .leftOrElse { Sarif.fromPlainStringUnchecked(CallTraceValueFormatter.UNKNOWN_VALUE_STR) }

        val instance = CallInstance.CVLExpInstance(
            when (sc) {
                is SnippetCmd.CVLSnippetCmd.GhostRead -> sarifFormatter.fmt(
                    "Ghost read: {}{}{}",
                    FmtArg(idp),
                    FmtArg.To,
                    accessedValue
                )

                is SnippetCmd.CVLSnippetCmd.SumGhostRead -> sarifFormatter.fmt(
                    "{}{}{}",
                    FmtArg(idp),
                    FmtArg.To,
                    accessedValue
                )

                is SnippetCmd.CVLSnippetCmd.GhostAssignment -> sarifFormatter.fmt(
                    "Ghost assignment: {} = {}",
                    FmtArg(idp),
                    accessedValue
                )

                is SnippetCmd.CVLSnippetCmd.SumGhostUpdate -> sarifFormatter.fmt("{} = {}", FmtArg(idp), accessedValue)
            },
            sc.range as? Range.Range,
            accessedValueModel.leftOrNull()?.first,
        )

        callTraceAppend(instance)

        globalState?.handleGhostAccessSnippet(sc)

        when (sc) {
            is SnippetCmd.CVLSnippetCmd.GhostRead,
            is SnippetCmd.CVLSnippetCmd.SumGhostRead -> Logger.regression { "CallTrace: ghost ${sc.name} was read in $currCallInstance" }
            is SnippetCmd.CVLSnippetCmd.GhostAssignment,
            is SnippetCmd.CVLSnippetCmd.SumGhostUpdate -> Logger.regression { "CallTrace: assignment to ${sc.name} in $currCallInstance" }
        }
        return HandleCmdResult.Continue
    }

    private fun handleGhostHavocSnippet(
        sc: SnippetCmd.CVLSnippetCmd.GhostHavocSnippet,
        currBlock: NBId,
        idx: Int
    ): HandleCmdResult {
        val afterHavoc = CmdPointer(currBlock, idx + 1)
        globalState?.handleGhostHavoc(sc, afterHavoc)

        return HandleCmdResult.Continue
    }

    private fun handleDispatchDefaultCase(
        sc: SnippetCmd.CVLSnippetCmd.DispatcherSummaryDefault
    ): HandleCmdResult {

        val appliedSummary = sc.appliedSummary
        val range = when(appliedSummary){
            is Summarization.AppliedSummary.MethodsBlock -> appliedSummary.specCallSumm.range as? Range.Range
            is Summarization.AppliedSummary.Prover,
            Summarization.AppliedSummary.LateInliningDispatcher,
            is Summarization.AppliedSummary.Config -> null
        }

        val msg = when(appliedSummary){
            is Summarization.AppliedSummary.MethodsBlock ->
            when(appliedSummary.specCallSumm){
                is SpecCallSummary.DispatchList -> {
                    check(!appliedSummary.specCallSumm.optimistic) { "Expected a summary with pessimistic dispatch" }
                    "Applying default case of DISPATCH summary, using '${sc.havocType}'"
                }
                is SpecCallSummary.Dispatcher -> {
                    check(!appliedSummary.specCallSumm.optimistic) { "Expected a call summary pessimistic dispatch" }
                    "Applying default case of DISPATCHER(optimistic=false) summary, using '${sc.havocType}'"
                }

                is SpecCallSummary.Always,
                is SpecCallSummary.Constant,
                is SpecCallSummary.Exp,
                is SpecCallSummary.HavocSummary,
                is SpecCallSummary.Reroute,
                is SpecCallSummary.PerCalleeConstant -> error("Expecting either DISPATCHER(optimistic=false) summary or a DISPATCH, got ${appliedSummary.specCallSumm}")
            }
            is Summarization.AppliedSummary.LateInliningDispatcher,
            is Summarization.AppliedSummary.Prover -> "As of internal Prover handling"
            is Summarization.AppliedSummary.Config -> "As of setting config flag ${appliedSummary.configFlag.get()}"
        }

        val instance = CallInstance.DispatcherSummaryDefaultInstance(
            range, msg
        )

        callTraceAppend(instance)
        return HandleCmdResult.Continue
    }

    private fun handleAllGhostsHavocSnippet(currBlock: NBId, idx: Int): HandleCmdResult {
        val afterHavoc = CmdPointer(currBlock, idx + 1)
        globalState?.handleAllGhostsHavoc(afterHavoc)

        val havocAllGhostsCallInstance = CallInstance.AllGhostsHavocInstance()
        Logger.regression {
            havocAllGhostsCallInstance.name
        }

        callTraceAppend(havocAllGhostsCallInstance)

        return HandleCmdResult.Continue
    }

    private fun handleCVLFunctionRet(snippetCmd: SnippetCmd.CVLSnippetCmd.CVLRet): HandleCmdResult {
        currCallInstance.ancestorOfType<CallInstance.InvokingInstance.CVLFunctionInstance>()
            ?: return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "Missing start of cvl function ${snippetCmd.callIndex} for return value ${snippetCmd.index}" })

        when (snippetCmd) {
            is SnippetCmd.CVLSnippetCmd.CVLRet.AnyRet -> {
                return HandleCmdResult.GeneratedCallTrace(callTraceFailure { "invalid CallTrace, AnyRet should have been changed during compilation." })
            }

            is SnippetCmd.CVLSnippetCmd.CVLRet.PrimitiveRet -> {
                callInputsAndOutputs.addCVLFunctionReturnValue(
                    snippetCmd.callIndex,
                    snippetCmd.index,
                    snippetCmd.type,
                    snippetCmd.sym
                )

                materializeCVLBoolCondExpInfoWithParent(snippetCmd.sym, "return")
            }

            is SnippetCmd.CVLSnippetCmd.CVLRet.ArrayRet,
            is SnippetCmd.CVLSnippetCmd.CVLRet.BlockchainStateRet,
            is SnippetCmd.CVLSnippetCmd.CVLRet.StructRet -> {
                callInputsAndOutputs.addCVLFunctionReturnValue(
                    snippetCmd.callIndex,
                    snippetCmd.index,
                    snippetCmd.type,
                    null
                )

                /** since currently we can't generate a parse tree for these cases, default to printing the original expression */
                val instance = CallInstance.LabelInstance(snippetCmd.label)
                currCallInstance.addChild(instance)
            }
        }
        return HandleCmdResult.Continue
    }

    fun handleStorageComparison(snippetCmd: SnippetCmd.CVLSnippetCmd.StorageComparison): HandleCmdResult {
        val definitelyFailed = model.valueAsBoolean(snippetCmd.resultSymbol).toValue(
            l = { !it },
            r = { resolverFailure ->
                val errorMsg = resolverFailure.invoke(
                    snippetCmd.resultSymbol
                )
                logger.error(errorMsg)
                false
            })

        if (!definitelyFailed) {
            return HandleCmdResult.Continue
        }

        val result = sarifForStorageLocation(snippetCmd, model, formatter, scene)
        val where = when (result) {
            is Either.Left -> result.d
            is Either.Right -> {
                logger.warnAndNull { result.d }
                return HandleCmdResult.Continue
            }
        }

        val inst = CallInstance.StorageCounterExampleInstance(
            sarifFormatter.fmt("Found different values {}:", FmtArg(where))
        )

        val mv1 = model.valueAsTACValue(snippetCmd.p1.second)
        val mv2 = model.valueAsTACValue(snippetCmd.p2.second)

        fun wrapWithData(type: VMTypeDescriptor) =
            FmtArg.CtfValue.buildOrUnknown(mv1, type, "value at storage location") to
                    FmtArg.CtfValue.buildOrUnknown(mv2, type, "value at storage location")
        fun wrapWithData(type: CVLType.PureCVLType) =
            FmtArg.CtfValue.buildOrUnknown(mv1, type, "value at storage location") to
                    FmtArg.CtfValue.buildOrUnknown(mv2, type, "value at storage location")

        val res =  when (snippetCmd) {
            is SnippetCmd.CVLSnippetCmd.ScalarStorageComparison ->
                snippetCmd.typeValue?.let { wrapWithData(it) }

            is SnippetCmd.CVLSnippetCmd.StorageWitnessComparison -> when (snippetCmd.basis) {
                StorageBasis.Balances, StorageBasis.ExternalCodesizes ->
                    wrapWithData(EVMTypeDescriptor.UIntK(256))
                is StorageBasis.ContractClass ->
                    snippetCmd.typeValue?.let { wrapWithData(it) }
            }

            is SnippetCmd.CVLSnippetCmd.GhostWitnessComparison -> wrapWithData(snippetCmd.basis.resultType)
            is SnippetCmd.CVLSnippetCmd.ScalarGhostComparison -> wrapWithData(snippetCmd.basis.resultType)
        }

        val (value1, value2) = if (res != null) {
            res
        } else {
            logger.warnAndNull { "Could not get type for snippet $snippetCmd" }
            return HandleCmdResult.Continue
        }

        val leftExample = CallInstance.StorageCounterExampleInstance(
            sarifFormatter.fmt("In ${snippetCmd.p1.first}: {}", value1)
        )
        val rightExample = CallInstance.StorageCounterExampleInstance(
            sarifFormatter.fmt("In ${snippetCmd.p2.first}: {}", value2)
        )
        callTraceAppend(inst)
        inst.addChild(leftExample)
        inst.addChild(rightExample)
        return HandleCmdResult.Continue
    }

    private fun handleCVLFunctionArg(snippetCmd: SnippetCmd.CVLSnippetCmd.CVLArg): HandleCmdResult {
        currCallInstance.ancestorOfType<CallInstance.InvokingInstance.PureCVLInvokingInstance>()
            ?: return HandleCmdResult.GeneratedCallTrace(invalidCallStack { "Missing start of cvl function ${snippetCmd.callIndex} for argument ${snippetCmd.param}" })

        when (snippetCmd) {
            is SnippetCmd.CVLSnippetCmd.CVLArg.AnyArg -> {
                return HandleCmdResult.GeneratedCallTrace(callTraceFailure { "invalid CallTrace, AnyArg should have been changed during compilation." })
            }

            is SnippetCmd.CVLSnippetCmd.CVLArg.PrimitiveArg -> {
                callInputsAndOutputs.addCVLFunctionParam(
                    snippetCmd.callIndex,
                    snippetCmd.index,
                    snippetCmd.param,
                    snippetCmd.sym
                )
            }

            is SnippetCmd.CVLSnippetCmd.CVLArg.ArrayArg -> {
                callInputsAndOutputs.addCVLFunctionArrayParam(
                    snippetCmd.callIndex,
                    snippetCmd.index,
                    snippetCmd.param,
                    snippetCmd.len
                )
            }

            is SnippetCmd.CVLSnippetCmd.CVLArg.BlockchainStateArg -> {
                callInputsAndOutputs.addCVLFunctionParam(snippetCmd.callIndex, snippetCmd.index, snippetCmd.param, null)
            }

            is SnippetCmd.CVLSnippetCmd.CVLArg.StructArg -> {
                callInputsAndOutputs.addCVLFunctionStructParam(
                    snippetCmd.callIndex,
                    snippetCmd.index,
                    snippetCmd.param,
                    snippetCmd.symbols
                )
            }
        }
        return HandleCmdResult.Continue
    }

    private fun handleStorageDisplay(snippetCmd: SnippetCmd.CVLSnippetCmd.StorageDisplay): HandleCmdResult {
        globalState?.computeGlobalState(snippetCmd.sym, formatter = formatter)?.let(::callTraceAppend)
        return HandleCmdResult.Continue
    }


    private fun handleIfStart(snippetCmd: SnippetCmd.CVLSnippetCmd.IfStart): HandleCmdResult {
        val instance = CallInstance.CVLIf(
            CVLReportLabel.Exp(snippetCmd.cond).toString(),
            snippetCmd.id,
            snippetCmd.range as? Range.Range,
        )
        callTracePush(instance)
        evaldCVLExpBuilder.materializeCVLBoolCondExpInfo(snippetCmd.condVar, currCallInstance)
        Logger.regression { "'If condition' CallInstance was added as the child of ${currCallInstance.name}" }
        return HandleCmdResult.Continue
    }

    private fun handleBranchStart(snippetCmd: SnippetCmd.CVLSnippetCmd.BranchStart): HandleCmdResult {
        val instance = CallInstance.CVLBranch(snippetCmd.kind, snippetCmd.id, snippetCmd.range as? Range.Range)
        callTracePush(instance)
        return HandleCmdResult.Continue
    }

    private fun handleEVMFunctionInvCVLValueTypeArg(snippetCmd: SnippetCmd.CVLSnippetCmd.EVMFunctionInvCVLValueTypeArg): HandleCmdResult {
        val movementCallIndex = (currCallInstance as? CallInstance.InvokingInstance.SolidityInvokeInstance)?.callIndex

        if (movementCallIndex != null && callInputsAndOutputs.externalCall(movementCallIndex) != null) {
            val movement = snippetCmd.calldataMovement(movementCallIndex)
            callInputsAndOutputs.registerCalldataMovement(movement, movementCallIndex)
        }
        return HandleCmdResult.Continue
    }

    private fun handleCVLFunctionStart(snippetCmd: SnippetCmd.CVLSnippetCmd.CVLFunctionStart): HandleCmdResult {
        val cvlFunctionInstance =
            CallInstance.InvokingInstance.CVLFunctionInstance(
                snippetCmd.callIndex,
                snippetCmd.name,
                snippetCmd.range.tryAs(),
                formatter
            )

        callInputsAndOutputs.initCVLCall(snippetCmd.callIndex)
        callTracePush(cvlFunctionInstance)

        return HandleCmdResult.Continue
    }

    private fun handleCVLFunctionEnd(snippetCmd: SnippetCmd.CVLSnippetCmd.CVLFunctionEnd): HandleCmdResult {
        val currentFunction = currCallInstance.ancestorOfType<CallInstance.InvokingInstance.CVLFunctionInstance>()
        return if (currentFunction != null && currentFunction.callIndex == snippetCmd.callIndex) {
            Logger.regression {
                "End of cvl function $currentFunction returns ${currentFunction.returnValuesToString()}"
            }
            this.callTracePop()!! // cannot fail - if we got here, call tree is not empty
            callInputsAndOutputs.finalizeCVLCall(currentFunction)
            HandleCmdResult.Continue
        } else {
            val actual = if (currentFunction != null) {
                "got function ${currentFunction.name} (id = ${currentFunction.callIndex})"
            } else {
                "not inside function"
            }
            HandleCmdResult.GeneratedCallTrace(invalidCallStack { "expected current cvl function to be ${snippetCmd.name} (id = ${snippetCmd.callIndex}), but $actual" })
        }
    }
}

private fun Logger.warnAndNull(f: () -> String): Nothing? {
    this.warn(f)
    return null
}
