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

package instrumentation.transformers

import analysis.CmdPointer
import analysis.Direction
import analysis.JoinLattice
import analysis.LTACCmd
import analysis.TACBlock
import analysis.TACBlockDataflowAnalysis
import analysis.TACCommandGraph
import com.certora.collect.*
import com.certora.collect.TreapMap.MergeMode
import datastructures.TreapMultiMap
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import log.*
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.asConstOrNull
import java.math.BigInteger

private val logger = Logger(LoggerTypes.STORAGE_ANALYSIS)

/** for each bytemap, the compile-time-constant offsets whose cell surely holds its last such store */
private typealias Spills = TreapMultiMap<TACSymbol.Var, BigInteger>

/**
 * `mem[c] = v; ...; x = mem[c]`  ~~>  `fwd = v; mem[c] = v; ...; x = fwd`
 * (for a constant offset `c`, when no path in between may write the cell's word)
 *
 * Solc's via-IR pipeline resolves "stack too deep" by moving Yul variables to reserved memory
 * slots at compile-time-constant offsets (the `memoryguard` region). When such a spilled variable
 * holds a storage slot (e.g. the result of a mapping-slot `keccak`), the round-trip through memory
 * makes [analysis.storage.StorageAnalysis] (and the PTA) lose track of the value: a `ByteLoad`
 * from the bytemap abstracts to Nondet, the subsequent storage access is unresolvable, and storage
 * analysis fails for the whole method — which in turn disables storage splitting contract-wide.
 *
 * This pass forwards the stored value to the load through a fresh scalar, so downstream analyses
 * see the value flow directly. A forward must-analysis tracks the `(bytemap, offset)` cells whose
 * last write on every path was a whole-word store at exactly that constant offset: joins intersect
 * the tracked cells, and anything else that may write a cell's 32-byte word kills it. A load of a
 * tracked cell is then replaced with a read of the cell's snapshot variable, which is assigned the
 * stored value right before *every* store to that cell in the program. This is sound at the load:
 * its path's last write to the cell's word is some store to exactly this cell, which snapshots the
 * loaded value, and a later snapshot assignment would itself be a later store to the cell.
 * Snapshotting at the stores (rather than forwarding the stored symbol itself) means a later
 * reassignment of that symbol is harmless, and stores of different values can still be forwarded
 * across a join. Snapshots at stores no load sees are just dead assignments, and the (now
 * potentially dead) stores themselves are also deliberately left in place.
 */
object SpillLoadForwarder {

    fun rewrite(code: CoreTACProgram): CoreTACProgram {
        val g = code.analysisCache.graph
        val analysis = SpillCellAnalysis(g)

        // rerun the transfer per block, collecting the loads to forward and their snapshot vars
        val snapshots = mutableMapOf<Pair<TACSymbol.Var, BigInteger>, TACSymbol.Var>()
        val forwarded = mutableListOf<Pair<CmdPointer, TACCmd.Simple>>()

        for (block in g.blocks) {
            var state = analysis.blockIn[block.id].orEmpty()
            for (ltac in block.commands) {
                state = step(ltac, state) { load, c ->
                    val snapshot = snapshots.getOrPut(load.base to c) {
                        TACSymbol.Var("spillFwd!${snapshots.size}!0x${c.toString(16)}", Tag.Bit256)
                    }
                    forwarded += ltac.ptr to
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(load.lhs, snapshot.asSym(), load.meta)
                }
            }
        }

        if (forwarded.isEmpty()) {
            return code
        }

        val patcher = code.toPatchingProgram()
        patcher.addVarDecls(snapshots.values)
        for ((ptr, newCmd) in forwarded) {
            patcher.update(ptr, newCmd)
        }

        // snapshot the stored value at every store to a forwarded cell
        for ((ptr, cmd) in g.commands) {
            if (cmd is TACCmd.Simple.AssigningCmd.ByteStore) {
                cmd.loc.asConstOrNull
                    ?.let { c -> snapshots[cmd.base to c] }
                    ?.let { snapshot ->
                        patcher.addBefore(
                            ptr,
                            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(snapshot, cmd.value.asSym()))
                        )
                    }
            }
        }

        logger.info { "Forwarded ${forwarded.size} spilled constant-offset memory loads in ${code.name}" }
        return patcher.toCode(code)
    }

    private class SpillCellAnalysis(graph: TACCommandGraph) : TACBlockDataflowAnalysis<Spills>(
        graph = graph,
        lattice = JoinLattice.ofJoin(::meet),
        bottom = treapMapOf(),
        direction = Direction.FORWARD,
    ) {
        override fun transform(inState: Spills, block: TACBlock): Spills =
            block.commands.fold(inState) { state, ltac -> step(ltac, state) }

        init {
            runAnalysis()
        }
    }

    /** intersection: a cell survives only if it is tracked on both sides */
    private fun meet(a: Spills, b: Spills): Spills =
        a.merge(b, MergeMode.INTERSECTION) { _, cells1, cells2 ->
            (cells1!! intersect cells2!!).takeIf { it.isNotEmpty() }
        }

    /** rewrites the tracked cells of [base] via [f], normalizing empty cell sets away */
    private fun Spills.updateCells(base: TACSymbol.Var, f: (TreapSet<BigInteger>) -> TreapSet<BigInteger>): Spills {
        val cells = this[base].orEmpty()
        val newCells = f(cells)
        return when {
            newCells === cells -> this
            newCells.isEmpty() -> this - base
            else -> put(base, newCells)
        }
    }

    /** removes cells whose 32-byte word overlaps a write of [width] bytes at [c] */
    private fun TreapSet<BigInteger>.dropOverlapping(c: BigInteger, width: BigInteger): TreapSet<BigInteger> =
        removeAll { offset -> offset < c + width && c < offset + EVM_WORD_SIZE }

    /** a write of [width] bytes at [loc]: precise removal when both are constants, else kills [base] */
    private fun Spills.dropWrite(base: TACSymbol.Var, loc: TACSymbol, width: TACSymbol): Spills {
        val c = loc.asConstOrNull
        val w = width.asConstOrNull
        return when {
            w == BigInteger.ZERO -> this // a write of 0 bytes touches nothing, wherever it is
            c != null && w != null -> updateCells(base) { it.dropOverlapping(c, w) }
            else -> this - base
        }
    }

    /**
     * returns [state] updated with [ltac]'s effect; when [forward] is given, it is called on each
     * `ByteLoad` of a tracked cell with the cell's offset
     */
    private fun step(
        ltac: LTACCmd,
        state: Spills,
        forward: ((TACCmd.Simple.AssigningCmd.ByteLoad, BigInteger) -> Unit)? = null,
    ): Spills {
        val cmd = ltac.cmd
        return when (cmd) {
            is TACCmd.Simple.AssigningCmd.ByteStore ->
                cmd.loc.asConstOrNull
                    ?.let { c ->
                        state.updateCells(cmd.base) { it.dropOverlapping(c, EVM_WORD_SIZE) + c }
                    }
                    ?: (state - cmd.base)

            is TACCmd.Simple.AssigningCmd.ByteStoreSingle ->
                cmd.loc.asConstOrNull
                    ?.let { c -> state.updateCells(cmd.base) { it.dropOverlapping(c, BigInteger.ONE) } }
                    ?: (state - cmd.base)

            is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                cmd.loc.asConstOrNull
                    ?.takeIf { c -> state[cmd.base]?.contains(c) == true }
                    ?.let { c -> forward?.invoke(cmd, c) }
                state
            }

            is TACCmd.Simple.ByteLongCopy -> state.dropWrite(cmd.dstBase, cmd.dstOffset, cmd.length)

            is TACCmd.Simple.CallCore -> state.dropWrite(cmd.outBase, cmd.outOffset, cmd.outSize)

            is TACCmd.Simple.AssigningCmd ->
                // e.g. a havoc or wholesale reassignment of the map itself. scalar assignments are
                // harmless: tracked cells are snapshotted at the stores, not at the loads.
                if (cmd.lhs.tag is Tag.ByteMap) {
                    state - cmd.lhs
                } else {
                    state
                }

            // storage (word map) writes can't touch memory cells
            is TACCmd.Simple.WordStore,
            is TACCmd.Simple.AnnotationCmd,
            is TACCmd.Simple.LabelCmd,
            is TACCmd.Simple.JumpdestCmd,
            is TACCmd.Simple.JumpCmd,
            is TACCmd.Simple.JumpiCmd,
            is TACCmd.Simple.NopCmd,
            is TACCmd.Simple.AssumeCmd,
            is TACCmd.Simple.AssumeNotCmd,
            is TACCmd.Simple.AssumeExpCmd,
            is TACCmd.Simple.AssertCmd,
            is TACCmd.Simple.LogCmd,
            is TACCmd.Simple.ReturnCmd,
            is TACCmd.Simple.ReturnSymCmd,
            is TACCmd.Simple.RevertCmd -> state

            // stands for arbitrary code — assume it may write any memory
            is TACCmd.Simple.SummaryCmd -> treapMapOf()
        }
    }
}
