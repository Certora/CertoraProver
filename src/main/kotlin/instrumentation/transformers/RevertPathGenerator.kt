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

import analysis.*
import analysis.icfg.*
import com.certora.collect.*
import datastructures.*
import datastructures.stdcollections.*
import log.*
import spec.*
import tac.NBId
import utils.*
import vc.data.*

private val logger = Logger(LoggerTypes.OPTIMIZE)

object RevertPathGenerator : CodeTransformer() {
    override fun transform(ast: CoreTACProgram): CoreTACProgram {
        val graph = lazy { ast.analysisCache.graph }
        val methodCallStack = lazy { InlinedMethodCallStack(graph.value, includeCVLFunctions = true) }
        val labelStack = lazy { CVLLabelStack(graph.value) }
        val startEndPairsContractFuns =
            MetaKeyPairDetector(
                graph.value,
                MetaKeyPairDetector.isMetaKey(Inliner.CallStack.STACK_PUSH),
                MetaKeyPairDetector.isMetaKey(Inliner.CallStack.STACK_POP)
            ) { start, end ->
                start.maybeAnnotation(Inliner.CallStack.STACK_PUSH)?.calleeId == end.maybeAnnotation(Inliner.CallStack.STACK_POP)?.calleeId
            }.getResultsAtSinkBlock()

        val startEndPairsCvlFuns =
            MetaKeyPairDetector(
                graph.value,
                { cmd -> cmd.asSnippetCmd() is SnippetCmd.CVLSnippetCmd.CVLFunctionStart },
                { cmd -> cmd.asSnippetCmd() is SnippetCmd.CVLSnippetCmd.CVLFunctionEnd }
            ) { start, end ->
                start.asSnippetCmd() is SnippetCmd.CVLSnippetCmd.CVLFunctionStart
                        && end.asSnippetCmd() is SnippetCmd.CVLSnippetCmd.CVLFunctionEnd
                        && (start.asSnippetCmd() as SnippetCmd.CVLSnippetCmd.CVLFunctionStart).callIndex == (end.asSnippetCmd() as SnippetCmd.CVLSnippetCmd.CVLFunctionEnd).callIndex
            }.getResultsAtSinkBlock()

        val startEndPairs = lazy {
            startEndPairsContractFuns + startEndPairsCvlFuns
        }
        return ast.patching { patching ->
            val removeBlocks = mutableSetOf<NBId>()
            ast.ltacStream()
                .filter { it.maybeAnnotation(CVLInvocationCompiler.REVERT_CONFLUENCE) }
                .forEach { lc ->
                    val callStack = methodCallStack.value.activationRecordsAt(lc.ptr)
                    val withRevert = callStack.find { !it.isNoRevert }
                    if (withRevert == null) {
                        // no @withrevert calls up the callstack, so just assume we didn't revert
                        patching.replaceCommand(lc.ptr, listOf(
                            TACCmd.Simple.AssumeNotCmd(CVLKeywords.lastReverted.toVar())
                        ))
                    } else {
                        // This call is nested within a @withrevert. Make this node jump to the end of its calling function
                        val current = lc.ptr.block
                        val currCaller = callStack.last()
                        val currCallerCmd = graph.value.elab(currCaller.ptr)
                        val ends = startEndPairs.value[currCallerCmd]
                            ?: error("expected ${currCaller.ptr} to be a STACK_PUSH or CVL_FUNCTION_START annotation, got ${currCallerCmd.cmd}")
                        val currSuccessor = patching.getSuccessors(current).single()
                        // we need to put the end labels corresponding to all the things that we "interrupted" execution of with our revert
                        val endLabels = endLabelsToAdd(labelStack.value, currCaller, lc.ptr, graph.value.elab(current))
                        val endsConfluence =
                            ends.monadicMap { patching.getSuccessors(it.ptr.block).singleOrNull() }
                                ?.uniqueOrNull()
                                ?: error("all exits from a call are expected to converge to a single block")
                        val endAnnot = ends.first()
                        patching.reroutePredecessorsTo(
                            currSuccessor,
                            endLabels +
                            listOf(
                                endAnnot.cmd,
                                TACCmd.Simple.JumpCmd(endsConfluence)
                            )
                        ) { it == current }
                        if (patching.getPredecessors(currSuccessor).isEmpty()) {
                            // Apparently all paths revert...
                            removeBlocks.add(currSuccessor)
                        }
                    }
                }
            patching.removeSubgraph(removeBlocks)
        }
    }

    /**
     * Find the labels that are open in the [labelStack] at the point of [cmd], up to the start of [caller].
     * Return matching end labels for all those not already present in [currentBlock].
     * We need this to re-establish balancedness of the CVLLabelStack on our reverting path that shortcuts past any closing of labels later on in the reverting function.
     */
    private fun endLabelsToAdd(labelStack: CVLLabelStack, caller: StackPushRecord, cmd: CmdPointer, currentBlock: TACBlock) : List<TACCmd.Simple.AnnotationCmd> {
        val res: MutableList<TACCmd.Simple.AnnotationCmd> = mutableListOf()
        val alreadyInCurrent: Set<TACCmd.Simple.AnnotationCmd.Annotation<*>> = currentBlock.commands.mapNotNullToSet { c -> (c.cmd as? TACCmd.Simple.AnnotationCmd)?.annot }
        for (rec in labelStack.iterateUpStackPushRecords(cmd)) {
            if (caller.ptr == rec.ptr) { return res }
            if (rec.annot.k == Inliner.CallStack.STACK_PUSH || (rec.annot.k == TACMeta.SNIPPET && rec.annot.v is SnippetCmd.CVLSnippetCmd.CVLFunctionStart)) {
                error("We found a call ${rec.annot} on the label stack before encountering our caller $caller.")
            }
            val endAnn = rec.matchingEndAnnotation()
            if (endAnn.annot !in alreadyInCurrent) {
                res.add(endAnn)
            }
        }
        logger.warn { "We did not find the caller $caller in the label stack at command $cmd." }
        return res
    }
}
