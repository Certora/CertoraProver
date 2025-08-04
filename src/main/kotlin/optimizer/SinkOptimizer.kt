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

package optimizer

import analysis.PathCondition
import analysis.TACBlock
import tac.CallId
import tac.MetaMap
import utils.uniqueOrNull
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.mapMeta

object SinkOptimizer {
    data class SinkCollapse(
            val suffix: List<TACCmd.Simple>,
            val withSuffix: List<TACBlock>,
            val sink: TACBlock,
            val callId: CallId? // If all collapsed nodes belong to the same function
    )
    fun optimizeGraph(prog: CoreTACProgram) : CoreTACProgram {
        // find candidate blocks
        val graph = prog.analysisCache.graph
        val sinkBlocks = graph.blocks.filter { sink ->
            graph.pred(sink).size > 1 && graph.pred(sink).any {
                graph.pathConditionsOf(it.id).get(sink.id) == PathCondition.TRUE
            }
        }
        fun TACCmd.Simple.noMeta() : TACCmd.Simple = if(this is TACCmd.Simple.NopCmd) {
            this
        } else {
            this.mapMeta {
                MetaMap()
            }
        }
        val equivClasses = mutableListOf<SinkCollapse>()
        for(sink in sinkBlocks) {
            val preds = graph.pred(sink)
            val collapseCandidates = mutableMapOf<List<TACCmd.Simple>, MutableList<TACBlock>>()
            var startMinSuffixLength = 4
            while (startMinSuffixLength > 0 && collapseCandidates.isEmpty()) {
                for (p in preds) {
                    val cmds = p.commands
                    if (cmds.size < startMinSuffixLength) {
                        continue
                    }
                    val last = cmds.takeLast(startMinSuffixLength).map { it.cmd.noMeta() }.reversed()
                    collapseCandidates.computeIfAbsent(last) {
                        mutableListOf()
                    }.add(p)
                }
                --startMinSuffixLength
            }
            for((startSuffix, equiv) in collapseCandidates) {
                if(equiv.size <= 1) {
                    continue
                }
                val repr = equiv.first()
                val currSuffix = startSuffix.toMutableList()
                val minSize = equiv.map { it.commands.size }.minOrNull()!!
                // get the next command
                fun TACBlock.next(): TACCmd.Simple = this.commands.get(this.commands.size - (currSuffix.size + 1)).cmd.noMeta()
                while(currSuffix.size < minSize && repr.next().let { nextCommand ->
                            equiv.all {
                                it.next() == nextCommand
                            }
                        }) {
                    currSuffix.add(repr.next())
                }
                equivClasses.add(SinkCollapse(
                        suffix = currSuffix.reversed(),
                        withSuffix = equiv,
                        sink = sink,
                        callId = equiv.map { it.id.calleeIdx }.uniqueOrNull()
                ))
            }
        }
        val sinkTargets = equivClasses.map {
            it.sink.id
        }.toSet()
        return prog.patching { patching ->
            for(toCollapse in equivClasses) {
                if(toCollapse.withSuffix.any {
                            it.id in sinkTargets
                        }) {
                    continue
                }
                val withJump = if(toCollapse.suffix.last() is TACCmd.Simple.JumpCmd) {
                    val cmd = toCollapse.suffix.last() as TACCmd.Simple.JumpCmd
                    check(cmd.dst == toCollapse.sink.id)
                    toCollapse.suffix
                } else {
                    toCollapse.suffix + TACCmd.Simple.JumpCmd(
                            toCollapse.sink.id
                    )
                }
                val sinkBlock = patching.addBlock(
                    toCollapse.sink.id.let { sinkToDeriveFrom ->
                        if (toCollapse.callId != null) {
                            sinkToDeriveFrom.copy(calleeIdx = toCollapse.callId)
                        } else {
                            sinkToDeriveFrom
                        }
                    },
                    withJump
                )
                for(withSuffix in toCollapse.withSuffix) {
                    // completely remove this block
                    if(withSuffix.commands.size == toCollapse.suffix.size) {
                        patching.reroutePredecessorsTo(withSuffix.id, sinkBlock)
                        patching.removeBlock(withSuffix.id)
                    } else {
                        // get the final command not in the common suffix
                        val splitAt = withSuffix.commands.get(withSuffix.commands.size - (toCollapse.suffix.size + 1))
                        val suffixBlock = patching.splitBlockAfter(splitAt.ptr)
                        patching.reroutePredecessorsTo(suffixBlock, sinkBlock)
                        patching.removeBlock(suffixBlock)
                    }
                }
            }
        }
    }
}

