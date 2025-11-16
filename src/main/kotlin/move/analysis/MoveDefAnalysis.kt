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

package move.analysis

import analysis.*
import analysis.dataflow.*
import datastructures.*
import datastructures.stdcollections.*
import move.*
import move.MoveTACProgram.Block
import move.MoveTACProgram.LCmd
import utils.*
import vc.data.*
import java.math.BigInteger

/**
    Def analysis for Move TAC programs.

    Gives "loose" defs, as in [LooseDefAnalysis], meaning that we ignore the possiblity of an undefined variable. A
    write though a reference is considered a definition of all possible targets of the reference, which is what the
    write will expand to in [MoveTACSimplifier] after all.
 */
class MoveDefAnalysis private constructor(
    graph: MoveTACCommandGraph
) : GenericLooseDefAnalysis<TACCmd, LCmd, Block, MoveTACCommandGraph>(
    graph,
    MoveBlockView
) {
    companion object : AnalysisCache.Key<MoveTACCommandGraph, MoveDefAnalysis> {
        override fun createCached(graph: MoveTACCommandGraph) = MoveDefAnalysis(graph)
    }

    private val ref get() = graph.cache.references

    override protected fun LCmd.getDefinedVars() = when (cmd) {
        is TACCmd.Simple -> when (cmd) {
            is TACCmd.Simple.AssigningCmd -> listOf(cmd.lhs)
            else -> listOf()
        }
        is TACCmd.Move -> when (cmd) {
            is TACCmd.Move.BorrowLocCmd -> listOf(cmd.ref)
            is TACCmd.Move.ReadRefCmd -> listOf(cmd.dst)
            is TACCmd.Move.WriteRefCmd -> ref.targetVarsBefore(ptr, cmd.ref)
            is TACCmd.Move.PackStructCmd -> listOf(cmd.dst)
            is TACCmd.Move.UnpackStructCmd -> cmd.dsts
            is TACCmd.Move.BorrowFieldCmd -> listOf(cmd.dstRef)
            is TACCmd.Move.VecPackCmd -> listOf(cmd.dst)
            is TACCmd.Move.VecUnpackCmd -> cmd.dsts
            is TACCmd.Move.VecLenCmd -> listOf(cmd.dst)
            is TACCmd.Move.VecBorrowCmd -> listOf(cmd.dstRef)
            is TACCmd.Move.VecPushBackCmd -> ref.targetVarsBefore(ptr, cmd.ref)
            is TACCmd.Move.VecPopBackCmd -> ref.targetVarsBefore(ptr, cmd.ref)
            is TACCmd.Move.PackVariantCmd -> listOf(cmd.dst)
            is TACCmd.Move.UnpackVariantCmd -> cmd.dsts
            is TACCmd.Move.VariantIndexCmd -> listOf(cmd.loc)
            is TACCmd.Move.GhostArrayBorrowCmd -> listOf(cmd.dstRef)
            is TACCmd.Move.HashCmd -> listOf(cmd.dst)
            is TACCmd.Move.EqCmd -> listOf(cmd.dst)
        }
        is TACCmd.CVL, is TACCmd.EVM -> error("Unexpected command type in Move TAC: $cmd")
    }

    fun nontrivialDef(src: TACSymbol.Var, origin: CmdPointer): Set<CmdPointer> {
        val visited = ArrayHashMap<TACSymbol.Var, MutableSet<CmdPointer>>()
        val vl = ArrayDeque<TACSymbol.Var>()
        val pl = ArrayDeque<CmdPointer>()
        val result = ArrayHashSet<CmdPointer>()
        vl.addLast(src)
        pl.addLast(origin)
        while (vl.isNotEmpty()) {
            val v = vl.removeLast()
            val p = pl.removeLast()

            if (visited.getOrPut(v) { ArrayHashSet() }.add(p)) {
                val sites = defSitesOf(v, p)
                if (sites.isEmpty()) {
                    if (p != origin) {
                        result.add(p)
                    }
                } else {
                    sites.forEach { s ->
                        val cmd = graph.toCommand(s)
                        if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.rhs is TACExpr.Sym.Var) {
                            vl.addLast(cmd.rhs.s)
                            pl.addLast(s)
                        } else {
                            result.add(s)
                        }
                    }
                }
            }
        }
        return result
    }

    fun mustBeConstantAt(where: CmdPointer, v: TACSymbol.Var): BigInteger? {
        return nontrivialDef(v, where).map { ptr ->
            val cmd = graph.toCommand(ptr)
            if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.rhs is TACExpr.Sym.Const) {
                cmd.rhs.s.value
            } else {
                null
            }
        }.uniqueOrNull()
    }

    fun mustBeConstantAt(where: CmdPointer, v: TACSymbol): BigInteger? {
        return if(v is TACSymbol.Const) {
            v.value
        } else {
            this.mustBeConstantAt(where, v as TACSymbol.Var)
        }
    }
}
