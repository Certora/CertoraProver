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

import algorithms.topologicalOrder
import analysis.*
import analysis.icfg.InlinedMethodCallOrder
import analysis.icfg.InlinedMethodCallStack
import analysis.icfg.Inliner.CallStack.STACK_POP
import analysis.icfg.Inliner.CallStack.STACK_PUSH
import analysis.icfg.MetaKeyPairDetector
import analysis.icfg.SummaryStack.END_EXTERNAL_SUMMARY
import analysis.icfg.SummaryStack.START_EXTERNAL_SUMMARY
import com.certora.collect.*
import datastructures.stdcollections.*
import log.*
import spec.CVLKeywords
import spec.toVar
import tac.CallId
import tac.Tag
import utils.lazy
import vc.data.*
import vc.data.TACSymbol.Var.Companion.KEYWORD_ENTRY
import java.io.Serializable
import java.math.BigInteger

private val logger = Logger(LoggerTypes.FOUNDRY)

object FoundryInstrumenter {
    fun transform(code: CoreTACProgram): CoreTACProgram {
        val g = code.analysisCache.graph
        val p = code.toPatchingProgram("foundry annotations")
        val topoSorted = topologicalOrder(g.blockGraph).reversed()
        val methodCallStack = lazy { InlinedMethodCallStack(g) }
        val startEndPairs = lazy {
            MetaKeyPairDetector(g, MetaKeyPairDetector.isMetaKey(STACK_PUSH), MetaKeyPairDetector.isMetaKey(STACK_POP), MetaKeyPairDetector.stackPushPopMatcher).getResultsAtSinkBlock() +
                MetaKeyPairDetector(g, MetaKeyPairDetector.isMetaKey(START_EXTERNAL_SUMMARY), MetaKeyPairDetector.isMetaKey(END_EXTERNAL_SUMMARY), MetaKeyPairDetector.externalSummaryStartEndMatcher).getResultsAtSinkBlock()
        }

        fun updateEnvElement(
            cheatLcmd: LTACCmdView<TACCmd.Simple.AnnotationCmd>,
            updatedValue: TACSymbol.Var,
            keyword: TACKeyword,
            varToResolve: (TACSymbolTable, CallId) -> TACSymbol.Var
        ) {
            // First, update the keyword variable of all calls in the current callstack...
            p.replaceCommand(cheatLcmd.ptr,
                listOf(cheatLcmd.cmd) +
                    methodCallStack.value.activationRecordsAt(cheatLcmd.ptr).map {
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = varToResolve(code.symbolTable, it.ptr.block.calleeIdx),
                            rhs = updatedValue.asSym()
                        )
                    }
            )

            // Then, update it for all of the future calls.
            g.iterateFrom(cheatLcmd.ptr, topoSorted).filter {
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.lhs?.meta?.get(KEYWORD_ENTRY)?.name == keyword.getName()
            }.forEach {
                p.replaceCommand(it.ptr,
                    listOf(cheatLcmd.cmd, TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = it.narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs,
                        rhs = updatedValue.asSym()
                    ))
                )
            }
        }

        // iterate in order
        g.iterateFrom(CmdPointer(topoSorted.first(), 0), topoSorted).filter { lcmd ->
            lcmd.cmd is TACCmd.Simple.AnnotationCmd && lcmd.cmd.annot.k == TACMeta.FOUNDRY_CHEATCODE
        }.map { lcmd ->
            lcmd.narrow<TACCmd.Simple.AnnotationCmd>()
        }.forEach { cheatLcmd ->
            when (val cheatcode = (cheatLcmd.cmd.annot.v as FoundryCheatcodes)) {
                is FoundryCheatcodes.Prank -> {
                    val nextCalls = InlinedMethodCallOrder(g).nextCallers(cheatLcmd.ptr) ?: error("There should have been something here")
                    if (nextCalls.isEmpty()) {
                        logger.warn { "No next call found. Perhaps it was summarized away?" }
                    }

                    val calleeIds = nextCalls.map { it.ptr.block.calleeIdx }

                    val callingMethodId = methodCallStack.value.currentCaller(cheatLcmd.ptr)?.ptr?.block?.calleeIdx ?: error("there should be a record")

                    g.iterateFrom(cheatLcmd.ptr, topoSorted).forEach { cmd ->
                        cmd.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.let { assignLcmd ->
                            val lhs = assignLcmd.cmd.lhs
                            val rhs = (assignLcmd.cmd.rhs as? TACExpr.Sym)?.s as? TACSymbol.Var
                            if (lhs.callIndex in calleeIds) {
                                if (lhs.namePrefix == TACKeyword.CALLER.getName()) {
                                    // replace `tacCaller@callIndex := v`
                                    p.replaceCommand(assignLcmd.ptr, listOf(assignLcmd.cmd.copy(rhs = cheatcode.msgSender.asSym())))
                                } else if (rhs?.namePrefix == TACKeyword.ADDRESS.getName() && rhs.callIndex == callingMethodId) {
                                    // replace `v := tacAddress@callingMethodId`
                                    p.replaceCommand(assignLcmd.ptr, listOf(assignLcmd.cmd.copy(rhs = cheatcode.msgSender.asSym())))
                                }
                            }
                        }
                    }
                }

                is FoundryCheatcodes.StartPrank -> {
                    val callStackDepth = methodCallStack.value.activationRecordsAt(cheatLcmd.ptr).size
                    for (cmd in g.iterateFrom(cheatLcmd.ptr, topoSorted).drop(1)) { // drop 1 to ignore this startPrank annotation
                        val cheatcodeAnnot = cmd.maybeAnnotation(TACMeta.FOUNDRY_CHEATCODE)
                        if (cheatcodeAnnot is FoundryCheatcodes.StopPrank || cheatcodeAnnot is FoundryCheatcodes.StartPrank) {
                            // OK, we reached the stopPrank (or another startPrank), no need to modify things anymore.
                            break
                        }
                        cmd.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.let { assignLcmd ->
                            val lhs = assignLcmd.cmd.lhs
                            val rhs = (assignLcmd.cmd.rhs as? TACExpr.Sym)?.s as? TACSymbol.Var
                            val callingMethodId = methodCallStack.value.currentCaller(cheatLcmd.ptr)?.ptr?.block?.calleeIdx ?: error("there should be a record")

                            fun replaceCmd() =
                                p.replaceCommand(assignLcmd.ptr, listOf(assignLcmd.cmd.copy(rhs = cheatcode.msgSender.asSym())))

                            if ((lhs as? TACSymbol.Var)?.let { varSym -> varSym.namePrefix == TACKeyword.CALLER.getName() } == true &&
                                methodCallStack.value.activationRecordsAt(cmd.ptr).size - 1 == callStackDepth) {
                                // replace `tacCaller@callIndex := v`, but only if this is a call from the same "depth" as where the startPrank was called.
                                replaceCmd()
                            } else if (rhs?.namePrefix == TACKeyword.ADDRESS.getName() && rhs.callIndex == callingMethodId) {
                                // replace `v := tacAddress@callingMethodId`
                                replaceCmd()
                            }
                        }
                    }
                }

                is FoundryCheatcodes.StopPrank -> Unit // Nothing to do here, we use this meta in the startPrank handling

                is FoundryCheatcodes.Warp -> {
                    updateEnvElement(cheatLcmd, cheatcode.newTimestamp, TACKeyword.TIMESTAMP) { symbolTable, callId -> VarResolver(symbolTable, callId).timestamp}
                }

                is FoundryCheatcodes.Roll -> {
                    updateEnvElement(cheatLcmd, cheatcode.newBlockNumber, TACKeyword.NUMBER) { symbolTable, callId -> VarResolver(symbolTable, callId).blocknum}
                }

                is FoundryCheatcodes.MockCall -> {
                    // Collect the pairs of entry/exit from Solidity calls (both resolved and summaries, via startEndPairs)
                    // and then filter them to all the calls that happen after this `mockCall` but before a `clearMockedCalls`.
                    val start = topoSorted.indexOf(cheatLcmd.ptr.block)
                    val clearMockCalls = g.iterateFrom(cheatLcmd.ptr, topoSorted)
                        .find { lcmd -> lcmd.maybeAnnotation(TACMeta.FOUNDRY_CHEATCODE) == FoundryCheatcodes.ClearMockedCalls }
                    val end = if (clearMockCalls != null) {
                        topoSorted.indexOf(clearMockCalls.ptr.block)
                    } else {
                        topoSorted.lastIndex
                    }
                    val records = startEndPairs.value.filterKeys { k ->
                        topoSorted.subList(start, end+1).contains(k.ptr.block)
                    }

                    // For the case where the `mockCall` is within an `if`, we need to only apply it if the condition
                    // is true. To do this, set some variable to false at the beginning of the program, then set it to
                    // true only if the mock is called. Finally, test for the truthness of this variable when deciding
                    // to mock the call or not.
                    val useThisMock = TACKeyword.TMP(Tag.Bool, "useMockCall_${cheatLcmd.ptr.block.calleeIdx}")
                    p.addBefore(CmdPointer(code.entryBlockId, 0), listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(useThisMock, false.asTACSymbol()),
                        // Need to also initialize this to 0 because otherwise in the case that the mockCall isn't
                        // used it will be havoc'ed
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(cheatcode.dataLength, BigInteger.ZERO.asTACSymbol())
                    ))
                    p.replaceCommand(cheatLcmd.ptr, listOf(cheatLcmd.cmd, TACCmd.Simple.AssigningCmd.AssignExpCmd(useThisMock, true.asTACSymbol())))

                    records.forEachEntry forEachRecord@{ (pushLcmd, popLcmds) ->
                        val summary = pushLcmd.annotation(STACK_PUSH)?.let { it.summary ?: return@forEachRecord }
                            ?: pushLcmd.annotation(START_EXTERNAL_SUMMARY)?.callNode
                            ?: error("expected $pushLcmd to be a stack push or summary start")

                        // All the 'pops/ends' are expected to jump to the same exit block
                        val exitConfluenceBlock = popLcmds.flatMap {
                            g.blockSucc[it.ptr.block] ?: error("${it.ptr.block} isn't in the graph")
                        }.toSet().singleOrNull() ?: error("expected a single confluence point after the function end")

                        val pushBlock = p.splitBlockAfter(pushLcmd.ptr)

                        // The destination block that "circumvents" calling the actual function and instead updates the return data buffer
                        // with the data specified by the cheatcode and jumps to the exitConfluenceBlock
                        val mockedCallBlock = p.addBlock(
                            pushBlock,
                            listOf(
                                TACCmd.Simple.LabelCmd("→ mockedCall for ${cheatLcmd.ptr.block.calleeIdx}"),
                                TACCmd.Simple.ByteLongCopy(
                                    dstOffset = TACSymbol.Zero,
                                    srcOffset = TACSymbol.Zero,
                                    length = cheatcode.returnDataLength,
                                    dstBase = TACKeyword.RETURNDATA.toVar(exitConfluenceBlock.calleeIdx),
                                    srcBase = cheatcode.returnData
                                ),
                                TACCmd.Simple.ByteLongCopy(
                                    dstOffset = summary.outOffset,
                                    srcOffset = TACSymbol.Zero,
                                    length = cheatcode.returnDataLength,
                                    dstBase = summary.outBase,
                                    srcBase = cheatcode.returnData
                                ),
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = TACKeyword.RETURN_SIZE.toVar(exitConfluenceBlock.calleeIdx),
                                    rhs = cheatcode.returnDataLength
                                ),
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = TACKeyword.RETURNCODE.toVar(exitConfluenceBlock.calleeIdx),
                                    rhs = TACSymbol.One
                                ),
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = CVLKeywords.lastReverted.toVar(),
                                    rhs = false.asTACSymbol()
                                ),
                                TACCmd.Simple.LabelCmd("←"),
                                popLcmds.first().cmd, // Add the pop/exit marker here too
                                TACCmd.Simple.JumpCmd(dst = exitConfluenceBlock)
                            )
                        )

                        val dataLengthVar = TACKeyword.TMP(Tag.Bit256, "mockCallDataLength")
                        val inHashVar = TACKeyword.TMP(Tag.Bit256, "mockCallInHash")
                        val cheatHashVar = TACKeyword.TMP(Tag.Bit256, "mockCallCheatHash")
                        val condVar = TACKeyword.TMP(Tag.Bool, "mockCallCheck")
                        val condBlock = p.addBlock(
                            pushBlock,
                            listOf(
                                TACCmd.Simple.LabelCmd("→ mockCall check for ${cheatLcmd.ptr.block.calleeIdx}"),
                                // For some reason when `-hashing_maskOddBytes` is true then we get false positives when
                                // the data length is less than 0x20. In this case just comparing a whole word does the
                                // trick.
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = dataLengthVar,
                                    rhs = TACExprFactUntyped {
                                        Ite(
                                            i = cheatcode.dataLength.asSym() ge 0x20.toBigInteger().asTACExpr(),
                                            t = cheatcode.dataLength.asSym(),
                                            e = 0x20.toBigInteger().asTACExpr()
                                        )
                                    }
                                ),
                                // hash the calls input up to the length specified by the cheatcode
                                TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                                    lhs = inHashVar,
                                    op1 = summary.inOffset,
                                    op2 = dataLengthVar,
                                    memBaseMap = summary.inBase
                                ),
                                // hash the cheatcode's data
                                TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                                    lhs = cheatHashVar,
                                    op1 = TACSymbol.Zero,
                                    op2 = dataLengthVar,
                                    memBaseMap = cheatcode.data
                                ),
                                // compare:
                                // 1. the cheatcode's callee to the actual callee AND
                                // 2. the hashes of the input and cheatcode data AND
                                // 3. the cheatcode's msgValue and the calls actual value (if msgValue was specified)
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = condVar,
                                    rhs = TACExprFactUntyped {
                                        useThisMock.asSym() and
                                        (cheatcode.callee.asSym() eq summary.toVar.asSym()) and
                                            (cheatHashVar.asSym() eq inHashVar.asSym()) and
                                            if (cheatcode.msgValue != null) {
                                                (cheatcode.msgValue.asSym() eq summary.valueVar.asSym())
                                            } else {
                                                TACSymbol.True.asSym()
                                            }
                                    }
                                ),
                                TACCmd.Simple.LabelCmd("←"),
                                TACCmd.Simple.JumpiCmd(
                                    cond = condVar,
                                    dst = mockedCallBlock,
                                    elseDst = pushBlock,
                                )
                            ),
                        )
                        p.reroutePredecessorsTo(pushBlock, condBlock) { nbid -> nbid != condBlock }
                        p.addVarDecls(setOf(useThisMock, dataLengthVar, inHashVar, cheatHashVar, condVar))
                    }
                }

                FoundryCheatcodes.ClearMockedCalls -> Unit // This is used in the mockCall cheatcode handling.
            }
        }

        return p.toCode(code)
    }
}

@Treapable
sealed class FoundryCheatcodes : Serializable {
    data class Prank(val msgSender: TACSymbol.Var) : FoundryCheatcodes()
    data class StartPrank(val msgSender: TACSymbol.Var) : FoundryCheatcodes()
    data object StopPrank : FoundryCheatcodes() {
        private fun readResolve(): Any = StopPrank
    }

    data class Warp(val newTimestamp: TACSymbol.Var) : FoundryCheatcodes()

    data class Roll(val newBlockNumber: TACSymbol.Var) : FoundryCheatcodes()

    data class MockCall(
        val callee: TACSymbol.Var,
        val msgValue: TACSymbol.Var?,
        val data: TACSymbol.Var,
        val dataLength: TACSymbol.Var,
        val returnData: TACSymbol.Var,
        val returnDataLength: TACSymbol.Var
    ) : FoundryCheatcodes()
    data object ClearMockedCalls : FoundryCheatcodes() {
        private fun readResolve(): Any = ClearMockedCalls
    }
}
