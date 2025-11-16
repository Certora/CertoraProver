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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier.equivalence.instrumentation

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import analysis.CmdPointer
import datastructures.stdcollections.*
import analysis.maybeNarrow
import tac.MetaKey
import tac.NBId
import utils.*
import vc.data.*
import java.util.stream.Collectors

/**
 * Find returndatacopy commands that *definitely* come from some previously inlined call.
 * For these returndatacopies, record (via [CopyFromInlineReturn]) which memory copy from CALLEE memory
 * (as inserted by the [ReturnCopyCorrelation]) defines the data read by that returndatacopy.
 */
object ReturnCopyCorrelation {

    @GenerateRemapper
    data class CopyFromInlineReturn(
        @GeneratedBy(Allocator.Id.RETURN_COPY_CONFLUENCE)
        val which: Int
    ) : RemapperEntity<CopyFromInlineReturn>

    val CORRELATED_RETURN_COPY = MetaKey<CopyFromInlineReturn>("correlated.return.copy")

    fun correlateCopies(c: CoreTACProgram) : CoreTACProgram {
        val seed = c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf {
                ReturnCopyCollapser.CONFLUENCE_COPY in it.cmd.meta
            }
        }.map { it.ptr.block }.collect(Collectors.toSet())
        if(seed.isEmpty()) {
            return c
        }
        val topoSort = c.topoSortFw
        val prev = mutableMapOf<NBId, Int>()
        val workset = mutableSetOf<NBId>()
        val graph = c.analysisCache.graph
        val tagged = mutableMapOf<CmdPointer, Int>()
        for(b in topoSort) {
            if(b !in workset && b !in seed) {
                continue
            }
            var curr : Int? = graph.pred(b).monadicMap { p ->
                prev[p]
            }?.uniqueOrNull()
            if(curr == null && b !in seed) {
                continue
            }
            val cmds = graph.elab(b).commands
            for(lc in cmds) {
                if(lc.cmd is TACCmd.Simple.AssigningCmd && TACMeta.IS_RETURNDATA in lc.cmd.lhs.meta) {
                    curr = null
                } else if(lc.cmd.cmd is TACCmd.Simple.ByteLongCopy && lc.cmd.meta[ReturnCopyCollapser.CONFLUENCE_COPY] != null) {
                    curr = lc.cmd.meta[ReturnCopyCollapser.CONFLUENCE_COPY]!!.id
                } else if(lc.cmd is TACCmd.Simple.ByteLongCopy && TACMeta.IS_RETURNDATA in lc.cmd.srcBase.meta && curr != null) {
                    tagged[lc.ptr] = curr
                }
            }
            if(curr != null) {
                prev[b] = curr
                workset.addAll(graph.succ(b))
            }
        }
        if(tagged.isEmpty()) {
            return c
        }
        return c.patching { p ->
            for((where, id) in tagged) {
                p.replace(where) { c ->
                    listOf(
                        c.mapMeta { m ->
                            m + (CORRELATED_RETURN_COPY to CopyFromInlineReturn(id))
                        }
                    )
                }
            }
        }
    }
}
