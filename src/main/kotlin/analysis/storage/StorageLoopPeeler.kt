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

package analysis.storage

import analysis.*
import com.certora.collect.*
import datastructures.stdcollections.*
import log.*
import utils.isNotEmpty
import vc.data.*
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.LOOP_ANALYSIS)

interface IStorageLoopPeelHeuristic {
    /** @return true if an iteration of [loop] should be peeled */
    fun shouldPeelIteration(loop: Loop): Boolean
}

/**
 * This module searches for all the loops in a program that:
 *   - are small (two blocks)
 *   - have a storage command
 *   - satisfy a given heuristic
 *
 * and then unpeels an iteration.
 *
 * The original motivation is that for copying "dynamic arrays" in Vyper we will have loops like:
 *
 * B = 1 + Len
 *
 * Orig:
 * do {
 *   x := tacS[p]
 *   p++
 * } while (p < B)
 * Exit:
 *
 * What's interesting is that the representation of the dynamic array is a struct, e.g.
 * { 0: int, 32: byte[N] }, where the value stored at offset 0 must always be <= N.
 *
 * The effect of the above loop is that both field 0 (the length of the dyn array)
 * and the contents of the array (at offset 32) will be copied. This means that there's
 * no single access path for the storage read: we have reads of both field 0 and field 32
 * at different iterations.
 *
 * The transform in [peelStorageLoops] will simply peel one iteration off, giving
 *
 * x := tacS[p]
 * p++
 * if (p < B) goto Orig else goto Exit
 *
 * Orig:
 * do {
 *   x := tacS[p]
 *   p++
 * } while(p < B)
 * Exit:
 *
 *
 */
object StorageLoopPeeler {

    /** As described above, peel an iteration off all small storage loops that satisfy [peelHeuristic] */
    fun peelStorageLoops(g: CoreTACProgram, peelHeuristic: IStorageLoopPeelHeuristic): CoreTACProgram {
        val graph = g.analysisCache.graph
        val patching = g.toPatchingProgram()
        val loops = getNaturalLoops(graph)
        val toPeel = loops.parallelStream().filter { loop ->
            if (loop.body.size != 2) {
                return@filter false
            }

            // Don't peel summarized loops
            if (graph.pred(loop.head).any {
                graph.elab(it).commands.last().snarrowOrNull<StorageCopyLoopSummary>() != null
                }) {
                return@filter false
            }

            val hasStorageAccess = loop.body.asSequence().flatMap { graph.elab(it).commands }.filter {
                it.cmd is TACCmd.Simple.StorageAccessCmd && it.cmd.base.isStorageOrTransientStorage()
            }.isNotEmpty()

            if (!hasStorageAccess) {
                return@filter false
            }

            return@filter peelHeuristic.shouldPeelIteration(loop).also {
                logger.info {
                    "Peel iteration of ${loop.head} = $it"
                }
            }
        }.collect(Collectors.toList())

        for (l in toPeel) {
            peelIteration(graph, patching, l)
        }

        return patching.toCode(g)
    }

    /**
     * @loop must be a two-block loop, with head h and tail t.
     *
     * assuming we have)
     *   p -> h <-> t
     *         `-> e
     * produces
     *
     *   p -> h' -> t' -> h <-> t
     *         `-----------`--> e
     *
     */
    private fun peelIteration(graph: TACCommandGraph, patch: SimplePatchingProgram, loop: Loop) {
        check (loop.body.size == 2)
        val head = graph.elab(loop.head)
        val tail = graph.elab(loop.tail)

        // These are the non-terminators for h
        val headCommands = head.commands.dropLast(1).map { it.cmd }

        // Before proceding, check that the last command in h is indeed a jump
        val term = head.commands.last()
        if (!PatchingTACProgram.SIMPLE.isJumpCommand(term.cmd)) {
            logger.warn {
                "$term is not a jump command, not sure what to do. Aborting."
            }
            // I don't know what to do. Abort.
            return
        }

        // Term is a ->t jump
        // So create t' and transform it into ->t'
        val peeledTail = patch.createOpenBlockFrom(tail.id)
        val headTerminator = PatchingTACProgram.SIMPLE.remapSuccessors(term.cmd) { s ->
            if (s == loop.tail) { peeledTail } else { s }
        }


        // So now h': (h's commands) + the remapped terminator
        val peeledHead = patch.createOpenBlockFrom(head.id)
        patch.populateBlock(peeledHead, headCommands + headTerminator)

        // Replace -> h edges with -> h'
        patch.reroutePredecessorsTo(loop.head, peeledHead) {
            it !in loop.body
        }

        // t' is just a copy of t since it will jump to h
        val tailCommands = tail.commands.map { it.cmd }
        patch.populateBlock(peeledTail, tailCommands, treapSetOf(loop.head))
    }
}
