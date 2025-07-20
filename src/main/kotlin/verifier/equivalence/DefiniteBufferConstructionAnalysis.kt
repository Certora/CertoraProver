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

package verifier.equivalence

import datastructures.stdcollections.*
import analysis.CmdPointer
import analysis.dataflow.StrictDefAnalysis
import analysis.icfg.CallGraphBuilder
import analysis.icfg.CallGraphBuilder.ByteRange.Companion.withLength
import com.certora.collect.*
import evm.EVM_WORD_SIZE
import tac.MetaKey
import utils.*
import vc.data.*
import java.math.BigInteger

/**
 * Finds long accesses (primarily reverts, but others are possible), the defining
 * writes for which can be definitely identified in some set of previous commands.
 *
 * Primarily this finds something like:
 * ```
 * mem[0x0] = 0xbeef000...
 * revert(0, 4)
 * ```
 *
 * Here we do not need the full power of the buffer trace instrumentation: it is easy to statically
 * identify that the buffer consumed by the revert command is entirely defined by the preceding `mem[0x0]`.
 * This instrumentation identifies these long reads; for each such long read L, it assigns a unique ID i (held
 * in meta [DefiniteBufferConstructionAnalysis.LONG_READ_ID]), and
 * then annotates each write that definitely defines L's buffer with i
 * (with the meta [DefiniteBufferConstructionAnalysis.DefiniteDefiningWrite]). Because, in principle, a write can define the
 * contents of multiple buffers, and thus [DefiniteDefiningWrite] can hold multiple such `i`.
 */
object DefiniteBufferConstructionAnalysis {

    fun instrument(c: CoreTACProgram) : CoreTACProgram {
        val strictDef = c.analysisCache.strictDef
        fun TACSymbol.isConstAt(where: CmdPointer) = when(val src = strictDef.source(ptr = where, sym = this)) {
            is StrictDefAnalysis.Source.Const -> src.n
            is StrictDefAnalysis.Source.Defs,
            is StrictDefAnalysis.Source.Uinitialized -> null
        }

        /**
         * Find long reads that correspond to events...
         */
        val definiteWrites = c.parallelLtacStream().mapNotNull { lc ->
            if(lc.cmd !is TACCmd.Simple.LongAccesses) {
                return@mapNotNull null
            }
            when(lc.cmd) {
                is TACCmd.Simple.LogCmd,
                is TACCmd.Simple.ReturnCmd,
                is TACCmd.Simple.RevertCmd,
                is TACCmd.Simple.CallCore -> Unit
                // non-event buffers
                is TACCmd.Simple.AssigningCmd.AssignSha3Cmd,
                is TACCmd.Simple.ByteLongCopy -> return@mapNotNull null
            }
            /**
             * ... whose offset and length are known constants ...
             */
            val la = lc.cmd.accesses.singleOrNull { la ->
                !la.isWrite && TACMeta.EVM_MEMORY in la.base.meta && la.offset.isConstAt(lc.ptr) != null && la.length.isConstAt(lc.ptr) != null
            } ?: return@mapNotNull null
            lc to la
        }.mapNotNull { (lc, la) ->
            val offset = (strictDef.source(lc.ptr, la.offset) as StrictDefAnalysis.Source.Const).n
            val length = (strictDef.source(lc.ptr, la.length) as StrictDefAnalysis.Source.Const).n
            // tracks the writes that we have found that define the buffer read at la.
            val prevWrites = mutableListOf<CmdPointer>()
            if(length == BigInteger.ZERO) {
                return@mapNotNull lc to listOf<CmdPointer>()
            }
            /**
             * Next, trace backwards from the long read la. Look for bytestores to constant indices that overlap the
             * region read by `la`.
             */
            val readRange = offset.withLength(length)

            /**
             * The current range covered by the writes found in prevWrites.
             */
            var currRange: CallGraphBuilder.ByteRange? = null
            for(prev in c.analysisCache.graph.interBlockBackwardsFrom(lc.ptr)) {
                if(prev.cmd is TACCmd.Simple.LongAccesses) {
                    return@mapNotNull null
                }
                if(prev.cmd is TACCmd.Simple.SummaryCmd && prev.cmd.summ is OpcodeSummary) {
                    continue
                }
                if(prev.cmd is TACCmd.Simple.SummaryCmd || prev.cmd is TACCmd.Simple.AssigningCmd.ByteStoreSingle) {
                    return@mapNotNull null
                }
                if(prev.cmd !is TACCmd.Simple.AssigningCmd.ByteStore) {
                    continue
                }
                if(prev.cmd.base != la.base) {
                    continue
                }
                /**
                 * If this was a write to a non-constant location, conservatively give up
                 */
                val offs = (strictDef.source(prev.ptr, prev.cmd.loc) as? StrictDefAnalysis.Source.Const)?.n ?: return@mapNotNull null
                val writeRange = offs.withLength(EVM_WORD_SIZE)
                /**
                 * Unrelated write, continue on
                 */
                if(!(readRange overlaps writeRange)) {
                    continue
                }
                /**
                 * Otherwise grow the region that we are tracking: this tells us "how much" of the buffer
                 * read at la we've seen defined so far.
                 */
                prevWrites.add(prev.ptr)
                if(currRange == null) {
                    currRange = writeRange
                } else if(!(currRange adjoins writeRange)) {
                    return@mapNotNull null
                } else {
                    currRange = currRange union writeRange
                }
                /**
                 * If we've define the whole ragne, break.
                 */
                if(currRange subsumes readRange) {
                    break
                }
            }
            /**
             * We exited the loop (by exhausting interblock backwards from)
             * without seeing the whole buffer defined. Give up.
             */
            if(currRange == null || !(currRange subsumes readRange)) {
                return@mapNotNull null
            }
            lc to prevWrites
        }.toMap()

        val readNumbering = mutableMapOf<CmdPointer, Int>()
        val writesToNumbering = mutableMapOf<CmdPointer, MutableList<Int>>()
        var longReadCounter = 0
        for((read, write) in definiteWrites) {
            val readId = longReadCounter++
            readNumbering[read.ptr] = readId
            for(w in write) {
                writesToNumbering.getOrPut(w) { mutableListOf<Int>() }.add(readId)
            }
        }
        return c.patching { p ->
            for((where, l) in writesToNumbering) {
                p.replace(where) { c ->
                    listOf(c.mapMeta { m ->
                        m + (DefiniteDefiningWrite.META_KEY to DefiniteDefiningWrite(l))
                    })
                }
            }
            for((where, readId) in readNumbering) {
                p.replace(where) { c ->
                    listOf(c.mapMeta { m ->
                        m + (LONG_READ_ID to readId)
                    })
                }
            }
        }
    }

    /**
     * Holder class for a bytestore which defines the long reads with the IDs mentioned in [writesFor].
     * Those IDs are attached to long reads with the meta [LONG_READ_ID]
     */
    @KSerializable
    @Treapable
    data class DefiniteDefiningWrite(
        val writesFor: List<Int>
    ) : AmbiSerializable {
        companion object {
            val META_KEY = MetaKey<DefiniteDefiningWrite>("equiv.defining-write")
        }
    }

    val LONG_READ_ID = MetaKey<Int>("equiv.consuming.read")
}
