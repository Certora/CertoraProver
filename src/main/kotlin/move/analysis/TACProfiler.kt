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
import config.*
import datastructures.stdcollections.*
import diagnostics.GraphProfiler
import diagnostics.GraphProfiler.Edge
import diagnostics.GraphProfiler.Node
import kotlin.io.path.*
import kotlin.math.*
import log.*
import move.*
import tac.*
import utils.*
import vc.data.*
import vc.data.tacexprutil.*

/**
    Produces a profile of the TAC commands in a program, with counts summarized by function.  Writes output in the
    Chromium heap dump format (see [GraphProfiler].)

    This is currently Move-specific, but could maybe be generalized to use other ecosystems' function call annotations.
 */
abstract class TACProfiler {
    abstract val profileType: String
    abstract fun count(cmd: TACCmd): Int

    fun profile(code: MoveTACProgram, profileName: String) = profile(code.graph, profileName, code.entryBlock)
    fun profile(code: CoreTACProgram, profileName: String) = profile(code.analysisCache.graph, profileName, code.entryBlockId)

    /** Produces a profile of the given [graph]. */
    private fun <CMD : TACCmd, LCMD : LTACCmdGen<CMD>, BLOCK : TACBlockGen<CMD, LCMD>> profile(
        graph: GenericTACCommandGraph<CMD, LCMD, BLOCK>,
        profileName: String,
        start: NBId
    ) {
        if (!Config.DumpCodeSizeAnalysis.get()) {
            return
        }

        // We only visit each command once.  The program is presumed to be well-structured, so that loops are contained
        // within functions, so we do not need any special treatment for them other than to avoid double-counting.
        val visited = mutableSetOf<CmdPointer>()

        // Generates the profile nodes for a single function call (and all of its callees).  Returns the call's node,
        // and the list of commands that exit the call.
        fun call(name: String, callId: Int?, start: LCMD): Pair<Node, List<LCMD>> {
            val work = arrayDequeOf(start)
            var selfSize = 0
            val calls = mutableListOf<Edge>()
            val exits = mutableListOf<LCMD>()
            work.consume { lcmd ->
                if (!visited.add(lcmd.ptr)) {
                    return@consume
                }

                selfSize += count(lcmd.cmd)

                (lcmd.cmd as? TACCmd.Simple.AnnotationCmd)?.annot?.let { (_, v) ->
                    if (v is MoveCallTrace.FuncStart) {
                        val (callee, returns) = call(v.name.toString(), v.callId, graph.succ(lcmd).single())
                        calls += Edge.Property("call", callee)
                        work += returns
                        return@consume
                    } else if (v is MoveCallTrace.FuncEnd) {
                        check(v.callId == callId) {
                            "Function enter/exit mismatch at $lcmd: expected to exit $callId ($name)"
                        }
                        exits += graph.succ(lcmd)
                        return@consume
                    }
                }

                work += graph.succ(lcmd)
            }

            return Node.Object(
                value = "${start.ptr.block}:${start.ptr.pos}",
                className = name,
                selfSize = selfSize,
                edges = calls.asSequence()
            ) to exits
        }

        val (root, _) = call("${profileName}-${graph.name}", null, graph.elab(CmdPointer(start, 0)))

        GraphProfiler.writeProfile(
            Path(
                "${ArtifactManagerFactory().mainReportsDir}",
                "${profileType}.${profileName}.${graph.name}.heapsnapshot"
            ),
            sequenceOf(root)
        )
    }
}

/**
    TAC profiler that simply counts commands (excluding no-ops).
 */
class TACSizeProfiler : TACProfiler() {
    override val profileType = "TACSize"
    override fun count(cmd: TACCmd) = when(cmd) {
        is TACCmd.Simple.LabelCmd,
        is TACCmd.Simple.JumpCmd,
        is TACCmd.Simple.JumpdestCmd,
        TACCmd.Simple.NopCmd -> 0
        else -> 1
    }
}

/**
    TAC profiler that counts "difficult" nonlinear operations in commands.

    This is meant to be similar to the results produced by [statistics.data.NonLinearStats], but we are aiming here for
    scalability to large programs; the UI we use to display the per-call [statistics.data.NonLinearStats] results does
    not currently scale well.
 */
class NonlinearProfiler : TACProfiler() {
    override val profileType = "Nonlinear"

    override fun count(cmd: TACCmd): Int = when(cmd) {
        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> count(cmd.rhs)
        is TACCmd.Simple.AssumeExpCmd -> count(cmd.cond)
        else -> 0
    }

    private fun count(e: TACExpr): Int = e.subs.sumOf {
        when (it) {
            is TACExpr.Vec.Mul,
            is TACExpr.Vec.IntMul,
            is TACExpr.BinOp.Div,
            is TACExpr.BinOp.SDiv,
            is TACExpr.BinOp.IntDiv,
            is TACExpr.BinOp.Mod,
            is TACExpr.BinOp.SMod,
            is TACExpr.BinOp.IntMod -> it.getOperands().let { ops ->
                max(0, ops.count { it !is TACExpr.Sym.Const } - 1)
            }

            is TACExpr.BinOp.Exponent,
            is TACExpr.BinOp.IntExponent -> it.getOperands().let { (_, exp) ->
                if (exp is TACExpr.Sym.Const) {
                    exp.s.value.toIntOrNull() ?: 0
                } else {
                    0
                }
            }

            is TACExpr.BinOp.ShiftLeft,
            is TACExpr.BinOp.ShiftRightLogical,
            is TACExpr.BinOp.ShiftRightArithmetical -> it.getOperands().count { it !is TACExpr.Sym.Const }

            is TACExpr.TernaryExp.MulMod -> it.getOperands().let { (a, b, m) ->
                if (a !is TACExpr.Sym.Const && b !is TACExpr.Sym.Const) { 1 } else { 0 } +
                if (m !is TACExpr.Sym.Const) { 1 } else { 0 }
            }

            else -> 0
        }
    }
}
