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

package analysis.opt.intervals

import algorithms.topologicalOrder
import analysis.CmdPointer
import analysis.opt.intervals.Intervals.Companion.SFalse
import analysis.opt.intervals.Intervals.Companion.SFullBool
import analysis.opt.intervals.Intervals.Companion.STrue
import analysis.opt.intervals.Intervals.Companion.unionOf
import analysis.opt.intervals.IntervalsCalculator.Companion.intervalOfTag
import analysis.opt.intervals.Node.*
import datastructures.stdcollections.*
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.getOperands
import vc.data.tacexprutil.CmdsFolder
import vc.data.tacexprutil.getFreeVars
import java.math.BigInteger
import java.util.*


@Suppress("Treapability")
private sealed interface Node {
    class Const(val value: BigInteger) : Node

    data class Var(val v: TACSymbol.Var) : Node

    /** One of these per expression */
    class Expr(val e: TACExpr) : Node

    /** The HyperGraphBuilder sometimes makes its own nodes */
    class Aux(val defaultIntervals: Intervals) : Node
}

private typealias State = HyperGraphFixedPointCalculator<Node, Intervals>.State

/**
 * Uses the intervals framework to process one standalone expression [exp].
 *
 * Assumes [exp] is true, and propagates information starting with [varValues] as the intervals of the variables in
 * the expression. Returns the resulting intervals of the variables.
 *
 * Does its best to case split according to the structure of the expression, and so can get better results than
 * intervals calculator gets without it.
 *
 * Splitting of logical-or (and dually, logical-and), is done as follows:
 * For `a \/ b \/ c` we generally consider the cases:
 * ```
 *    a = true, b = true, c = true
 * ```
 * However, this is not always enough to get the results we want. Therefore, if there are only two-operands, we restrict
 * even more:
 * ```
 *    (a=true, b=true), (a=true, b=false), (a=false, b=true)
 * ```
 *
 */
fun intervalsOfExpression(exp: TACExpr, varValues: Map<TACSymbol.Var, Intervals> = emptyMap())
    : Map<TACSymbol.Var, Intervals> {

    val hGraph = HyperGraphFixedPointCalculator<Node, Intervals>(
        defaultValue = {
            when (it) {
                is Aux -> it.defaultIntervals
                is Expr -> intervalOfTag(it.e.tagAssumeChecked)
                is Var -> varValues[it.v] ?: intervalOfTag(it.v.tag)
                is Const -> Intervals(it.value)
            }
        },
        normalize = { _, _, newIntervals -> newIntervals }
    )

    /** this is to avoid comparing tac-expressions. */
    val idMap = IdentityHashMap<TACExpr, Node>()

    val nodeDAG = mutableMapOf<Node, Set<Node>>()

    /** Constructs the HGraph edges according to [e], and returns [e]'s corresponding node */
    fun construct(e: TACExpr): Node =
        when (e) {
            is TACExpr.Sym.Const -> Const(e.s.value)
            is TACExpr.Sym.Var -> Var(e.s)
            else -> idMap.getOrPut(e) {
                val childNodes = e.getOperands().map(::construct)
                Expr(e).also { resNode ->
                    with(EdgeGenerator) {
                        hGraph.addEdgesOf(e, resNode, childNodes, ::Aux)
                    }
                    nodeDAG[resNode] = childNodes.toSet()
                }
            }
        }

    fun nodeOf(e: TACExpr) =
        when (e) {
            is TACExpr.Sym.Const -> error("Constants (${e.s.value}) don't have nodes")
            is TACExpr.Sym.Var -> Var(e.s)
            else -> idMap[e]!!
        }

    operator fun State.get(e: TACExpr) = get(nodeOf(e))

    /** duplicates the hyper-graph state, limits its values according to [pairs] and reaches a fixed point on the copy */
    fun State.limit(vararg pairs: Pair<TACExpr, Intervals>): State =
        duplicate().apply {
            for ((e, s) in pairs) {
                set(nodeOf(e), s)
            }
            fixedPoint(pairs.map { nodeOf(it.first) })
        }

    /** If [e] is splittable, splits to the different cases, and reaches a new fixed point on each case */
    fun split(h: State, e: TACExpr): List<State> =
        when {
            e is TACExpr.BinBoolOp.LOr && h[e].isTrue || e is TACExpr.BinBoolOp.LAnd && h[e].isFalse -> {
                val baseVal = ite(e is TACExpr.BinBoolOp.LOr, STrue, SFalse)
                val negVal = !baseVal
                val ops = e.getOperands().filter { h[it] == SFullBool }
                when (ops.size) {
                    0,1  -> listOf(h)
                    2 -> listOf(
                        h.limit(ops[0] to baseVal, ops[1] to negVal),
                        h.limit(ops[1] to baseVal, ops[0] to negVal),
                        h.limit(ops[0] to baseVal, ops[1] to baseVal)
                    )

                    else -> ops.map { h.limit(it to baseVal) }
                }
            }

            e is TACExpr.TernaryExp.Ite && h[e.i] == SFullBool -> listOf(
                h.limit(e.i to STrue),
                h.limit(e.i to SFalse)
            )

            else -> listOf(h)
        }

    val outNode = construct(exp)
    val initialState = hGraph.State().apply {
        set(outNode, STrue)
        fixedPoint()
    }
    var current = listOf(initialState)

    // start at the root, going in topological order, so that splits of outer expressions happen first.
    topologicalOrder(nodeDAG).reversed()
        .filterIsInstance<Expr>()
        .forEach { node ->
            current = current.flatMap { split(it, node.e) }
            if (current.size >= 40) {
                // If we split to much, we join everything that we got, and continue with just this one split.
                current = listOf(
                    initialState.duplicate().apply {
                        vertices.forEach { node ->
                            unionOf(current.mapToSet { it.get(node) }.toList())
                        }
                        fixedPoint()
                    })
            }
        }

    return exp.getFreeVars().associateWith { v ->
        unionOf(current.map { it.get(Var(v)) })
    }
}


/**
 * Assumes [e] is true at (the rhs of) [ptr], and using only the commands in the block, figures out what this says
 * of [v], returning it.
 */
fun intervalsOfOneVarExpression(
    code: CoreTACProgram,
    ptr: CmdPointer,
    e: TACExpr,
    v: TACSymbol.Var,
    known: Intervals = intervalOfTag(v.tag)
): Intervals? {
    val foldedExpr = CmdsFolder.fold(
        start = e,
        cmds = code.code[ptr.block]!!.subList(0, ptr.pos),
        dontFold = setOf(v)
    ) ?: return null

    return intervalsOfExpression(foldedExpr, mapOf(v to known))[v]
}

