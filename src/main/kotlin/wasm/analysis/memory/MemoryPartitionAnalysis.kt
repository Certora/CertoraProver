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

package wasm.analysis.memory

import analysis.*
import analysis.numeric.IntValue
import datastructures.stdcollections.*
import log.*
import optimizer.isMemoryAccess
import utils.*
import vc.data.*
import wasm.analysis.ConstArrayInitSummary
import wasm.analysis.intervals.IntervalAnalysis
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntState
import wasm.host.soroban.Val
import wasm.host.soroban.types.MapType
import wasm.impCfg.WASM_MEMORY_OP_WIDTH
import java.math.BigInteger
import java.util.stream.Collectors


private val logger = Logger(LoggerTypes.WASM)

/**
 * Analyze [ctp], which is the TAC representation of [wasmProgram] to infer permissions
 * for the memory accessed by [ctp].
 *
 * - if `permission(a)` is ReadWrite, then [ctp] *may* write the address `a`
 * - if `permission(a)` is ReadOnly, then [ctp] *must not* write the address `a`
 */
class MemoryPartitionAnalysis private constructor(graph: TACCommandGraph): IMemoryPartitions {
    companion object : AnalysisCache.Key<TACCommandGraph, MemoryPartitionAnalysis> {
        override fun createCached(graph: TACCommandGraph) = MemoryPartitionAnalysis(graph)
    }
    private val intervalAnalysis = graph.cache[IntervalAnalysis]

    private val permissionMap: Map<IntValue, IMemoryPartitions.Permission>

    private fun permission(loc: IntValue): IMemoryPartitions.Permission =
        permissionMap.keys
            .filter { it.mayIntersect(loc) }
            .fold(IMemoryPartitions.Permission.ReadOnly) { p, r -> p or permissionMap[r]!! }

    /**
     * @return [Permission.ReadOnly] if [ctp] *must not* write any address in [start, end]
     *         and [Permission.ReadWrite] otherwise
     */
    override fun permission(start: BigInteger, end: BigInteger): IMemoryPartitions.Permission =
        permission(IntValue(start, end))

    private fun AssigningSummary.writeLocations(st: SimpleQualifiedIntState): IntValue? {
        return when {
            TACKeyword.MEMORY.toVar() !in mustWriteVars && TACKeyword.MEMORY.toVar() !in mayWriteVars ->
                null

            this is MapType.UnpackMapToMemorySummary ->  {
                val start = st.interpret(this.valsPos).x
                val len = st.interpret(this.length).x
                    .mult(IntValue.Constant(Val.sizeInBytes.toBigInteger())).first
                val end = start.add(len).first
                IntValue(
                    lb = start.lb,
                    ub = end.ub - BigInteger.ONE,
                )
            }

            this is ConstArrayInitSummary -> {
                val start = st.interpret(this.startPtr).x
                IntValue(
                    lb = start.lb,
                    ub = start.ub + (stride*iterations) - BigInteger.ONE
                )
            }

            else -> {
                logger.warn { "writeLocations() unimplemented for $this, conservatively returning Nondet" }
                IntValue.Nondet
            }
        }
    }

    private fun LTACCmd.writeLocations(): IntValue? {
        val st = intervalAnalysis.inState(ptr) ?: return null

        return when {
            cmd is TACCmd.Simple.SummaryCmd && cmd.summ is AssigningSummary -> cmd.summ.writeLocations(st)

            !isMemoryAccess() ||
                cmd is TACCmd.Simple.RevertCmd ||
                cmd is TACCmd.Simple.AssigningCmd.ByteLoad -> null

            cmd is TACCmd.Simple.AssigningCmd.ByteStore -> {
                val length = cmd.meta[WASM_MEMORY_OP_WIDTH] ?: 8
                val base = st.interpret(cmd.loc).x
                base.copy(ub = base.ub + length.toBigInteger() - BigInteger.ONE)
            }

            cmd is TACCmd.Simple.ByteLongCopy ->
                if (cmd.dstBase == TACKeyword.MEMORY.toVar()) {
                    val length = st.interpret(cmd.length).x
                    if (length.isConstant && length.c == BigInteger.ZERO) {
                        return null
                    }

                    val base = st.interpret(cmd.dstOffset).x
                    base.copy(ub = base.ub + length.ub - BigInteger.ONE)
                } else {
                    null
                }

            else ->
                `impossible!`
        }
    }

    init {
        // Collect the written memory addresses
        val writes = graph.commands.parallelStream()
            .mapNotNull { lcmd -> lcmd.writeLocations()
                ?.also {
                    if (it == IntValue.Nondet) {
                        logger.warn { "Permissions analysis found unconstrained range at $lcmd, this will likely result in imprecise results"}
                    }
                }
            }
            .collect(Collectors.toSet())

        permissionMap = condenseSortedRanges(writes)
            .associateWith { _ -> IMemoryPartitions.Permission.ReadWrite }
    }

    private fun condenseSortedRanges(ranges: Collection<IntValue>): Set<IntValue> {
        if (ranges.isEmpty()) {
            return setOf()
        }

        val sortedRanges = ranges.sortedWith { i1, i2 ->
            val cmpLb = i1.lb.compareTo(i2.lb)
            if (cmpLb == 0) {
                i2.ub.compareTo(i1.ub)
            } else {
                cmpLb
            }
        }

        // We have a sorted list of ranges, ordered by lower address and then size (*larger* ranges first)
        // [l0, h0], [l1, h1], [l2, h2], ...
        // We try and fuse ranges so that we end up with a smaller collection:
        //   - if [l0, h0] subsumes [l1, h1], then drop [l1, h1]
        //   - if [l0, h0] overlaps [l1, h1], then combine them (join)
        //   - if h0 + 1 = l1 then they can be "stitched together" (joined)
        //   - otherwise, [l0, h0] is "done" (l2, l3, ...) can't possibly join since the list is sorted
        var currentRange = sortedRanges.first()
        val condensed = mutableSetOf<IntValue>()
        fun IntValue.record() {
            check(this !in condensed) { "Adding a range that already exists"}
            condensed.add(this)
        }
        for (nextRange in sortedRanges.drop(1)) {
            if (currentRange.contains(nextRange)) {
                continue
            }

            val joined = currentRange.tryJoin(nextRange)

            if (joined != null) {
                currentRange = joined
                continue
            }

            // Then we're done with current range
            currentRange.record()
            currentRange = nextRange
        }
        currentRange.record()

        return condensed
    }

    private fun IntValue.contains(other: IntValue) =
        lb <= other.lb && other.ub <= ub

    private fun IntValue.tryJoin(other: IntValue): IntValue? {
        if (mayIntersect(other) || ub + BigInteger.ONE == other.lb) {
            return this.join(other)
        }
        return null
    }
}
