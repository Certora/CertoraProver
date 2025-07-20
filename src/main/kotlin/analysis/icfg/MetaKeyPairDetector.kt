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

package analysis.icfg

import analysis.*
import analysis.icfg.Inliner.CallStack.STACK_POP
import analysis.icfg.Inliner.CallStack.STACK_PUSH
import datastructures.stdcollections.*
import log.*
import tac.MetaKey
import vc.data.TACCmd
import java.io.Serializable

private val logger = Logger(LoggerTypes.SUMMARIZATION)

class MetaKeyPairDetectorFact(val match: (LTACCmd, LTACCmd) -> Boolean, val startMetas: Set<LTACCmd> = setOf(), val matchedPairs: Map<LTACCmd, List<LTACCmd>> = mapOf()) {

    fun matchStart(newFact: LTACCmd): MetaKeyPairDetectorFact{
        return MetaKeyPairDetectorFact(match, setOf(newFact) + startMetas, matchedPairs )
    }
    fun matchEnd(closingFact: LTACCmd): MetaKeyPairDetectorFact{
        val (matched,unmatched) = startMetas.partition{ match(it, closingFact) }
        val matches = matchedPairs.toMutableMap()
        matched.forEach { matches[it] = matches.getOrDefault(it, listOf()) + closingFact }
        return MetaKeyPairDetectorFact(match, unmatched.toSet() , matches )
    }
}

class MetaKeyPairDetectorLattice(val match: (LTACCmd, LTACCmd) -> Boolean) : JoinLattice<MetaKeyPairDetectorFact> {
    override fun join(x: MetaKeyPairDetectorFact, y: MetaKeyPairDetectorFact): MetaKeyPairDetectorFact {
        return MetaKeyPairDetectorFact(match, x.startMetas + y.startMetas, x.matchedPairs + y.matchedPairs)
    }

    override fun equiv(x: MetaKeyPairDetectorFact, y: MetaKeyPairDetectorFact): Boolean {
        return x.startMetas == y.startMetas && x.matchedPairs == y.matchedPairs
    }

}
class MetaKeyPairDetector(
    val graph: TACCommandGraph,
    val isStart: (cmd: LTACCmd) -> Boolean,
    val isEnd: (cmd: LTACCmd) -> Boolean,
    val match: (start: LTACCmd, end: LTACCmd) -> Boolean
) {

    private val callStack = (object : TACCommandDataflowAnalysis<MetaKeyPairDetectorFact>(graph, lattice = MetaKeyPairDetectorLattice(match), bottom= MetaKeyPairDetectorFact(match, setOf(), mapOf()), dir = Direction.FORWARD) {
        override fun transformCmd(inState: MetaKeyPairDetectorFact, cmd: LTACCmd): MetaKeyPairDetectorFact {

            if (cmd.cmd is TACCmd.Simple.AnnotationCmd) {
                when {
                    isStart(cmd) -> return inState.matchStart(cmd)
                    isEnd(cmd) -> return inState.matchEnd(cmd)
                }
            }
            return inState
        }
        init {
            runAnalysis()
        }
    })
    fun getResultsAtSinkBlock(): Map<LTACCmd, List<LTACCmd>> {
        return graph.sinkBlocks.fold(mapOf()){ lastRes, block ->
            callStack.blockOut[block.id]?.startMetas?.let {
                if(it.isNotEmpty()){
                    logger.warn { "There are remaining unmatched start block after propagation to the sink blocks: ${callStack.blockOut[block.id]?.startMetas}. " +
                        "There are commands missing that have the matching annotation." }
                }
            }
            lastRes.plus(callStack.blockOut[block.id]?.matchedPairs ?: mapOf())}
    }

    companion object {
        fun <T : Serializable> isMetaKey(key: MetaKey<T>): (LTACCmd) -> Boolean { return { cmd: LTACCmd -> cmd.cmd is TACCmd.Simple.AnnotationCmd && cmd.cmd.annot.k == key }}

        val externalSummaryStartEndMatcher = { start: LTACCmd, end: LTACCmd ->
            start.cmd.maybeAnnotation(SummaryStack.START_EXTERNAL_SUMMARY)?.appliedSummary == end.cmd.maybeAnnotation(SummaryStack.END_EXTERNAL_SUMMARY)?.appliedSummary
        }

        val stackPushPopMatcher = { start: LTACCmd, end: LTACCmd ->
            start.cmd.maybeAnnotation(STACK_PUSH)?.calleeId == end.cmd.maybeAnnotation(STACK_POP)?.calleeId
        }
    }
}
