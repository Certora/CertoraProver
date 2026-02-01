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

package analysis.split

import algorithms.getReachable
import analysis.CmdPointer
import analysis.DagDefExprDataFlow
import analysis.ip.*
import analysis.opt.intervals.IntervalsRewriter
import analysis.split.BoolOptimizer.Companion.transformable
import analysis.split.BoolOptimizer.Node.NExpr
import datastructures.MutableMultiMap
import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import log.*
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldToExprPlusOneCmd
import vc.data.tacexprutil.TACExprUtils.contains
import vc.data.tacexprutil.rebuild
import java.math.BigInteger


private val logger = Logger(LoggerTypes.BOOL_OPTIMIZER)

/**
 * Detects and transforms numeric variables to boolean ones when possible.
 *
 * First stage is to mark all expressions and variables that can be transformed to be boolean. An expression can
 * be transformed if:
 *   1. it's not boolean already,
 *   2. it's known to hold only 0 or 1 values,
 *   3. if it's an int->int expression, then it's operands are transformable, and in-itself, it can be represented
 *      as a boolean transformation (for example `BWAnd` is fine, but `+` is not).
 *   4. it's usages are "good". This means that it's either used in a transformable expression, or, it's used in a
 *      binary relation (equality, or an inequality), and both operands are transformable.
 *
 * To calculate this set, a graph is generated, where these dependencies are encoded:
 *   1. The nodes are of two types: expressions and variables in the program.
 *   2. Two expressions are connected if they must be transformable together, according to the above.
 *   3. a variable is connected to its uses as expressions, and also to expression that is assigned to it.
 *   4. multiplications are a little different, because if one of the operands is "good", then we can transform
 *      the multiplication to an ite (where the "good" operand is the condition). In such a case, the result and one of
 *      the other operand are "bad". Therefore, if any of the operands is "bad" then so is the result, but not vice versa.
 *
 * To support multiplication, the graph is actually a directed graph, where an edge `a -> b` means: if `b` is not
 * transformable, then so is `a`.
 *
 * Also, a [badNodes] set is calculated, marking each node that is not transformable. Once the graph is built, a simple
 * reachability analysis determines which nodes are not transformable, and the rest can be transformed.
 *
 * Note this is assumed to run when variables have one meaning, that is, sometime after DSA. It's not a soundness issue,
 * but a variable as a whole can be transformed here, and different versions of it are not treated separately. This is
 * just for making to code a bit simpler. If we wish remove the assumption it'll need a bit of refining.
 */
class BoolOptimizer(val code: CoreTACProgram) {

    private val g = code.analysisCache.graph
    private val candidates = BoolCandidateCalculator(code).apply { go() }

    private fun isTransformableAnnotation(annot: TACCmd.Simple.AnnotationCmd.Annotation<*>) =
        when (annot.v) {
            is InternalFuncStartAnnotation,
            is InternalFuncExitAnnotation,
            is InternalFuncArg,
            is InternalFuncRet,
            is InternalFunctionHint,
            is SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite -> true

            else -> false
        }

    /**
     * The [NExpr] class is a trick for getting a unique node for each sub-expression in the program, including a
     * separate one for expressions that are equal (in the pointer sense), but are used in different locations.
     * For example, in:
     * ```
     *     (a + b) + a
     * ```
     * the two expressions `a` may be the same expression even in the pointer sense, but we'd like to view them as
     * different sub-expressions, i.e., each of them will get separate treatment, e.g., one may be transformable and
     * the other not.
     *
     * The idea is to generate a tree of [NExpr] objects that corresponds precisely to the expression tree of a given
     * expression. Then, each such [NExpr] can be used as a key in a hash-table, or what not.
     */
    private sealed interface Node {

        class NExpr(val e: TACExpr, val ops: List<NExpr>) : Node {
            /** Recursively creates the [NExpr] tree corresponding to [e] */
            constructor(e: TACExpr) : this(e, e.getOperands().map(::NExpr))

            override fun toString() = "<$e>"
        }

        data class Var(val v: TACSymbol.Var) : Node
    }

    private val graph = mutableMultiMapOf<Node, Node>()
    private fun MutableMultiMap<Node, Node>.add2(n1: Node, n2: Node) {
        add(n1, n2)
        add(n2, n1)
    }

    private val badNodes = mutableSetOf<Node>()

    // all def-sites and use-sites of a variable we'd consider should have an interval contained in [0, 1]
    init {
        fun transformable(v: TACSymbol.Var) = v.tag.transformable

        g.commands.forEach { (ptr, cmd) ->
            cmd.getLhs()?.let {
                if (!transformable(it) || !candidates.lhsVal(ptr)!!) {
                    badNodes += Node.Var(it)
                }
            }
            cmd.getFreeVarsOfRhsExtended()
                .filter { !transformable(it) || !candidates.rhsVal(ptr, it) }
                .forEach { badNodes += Node.Var(it) }
        }
    }

    /**
     * Adds edges to [graph], and collects [badNodes].
     * Operates recursively on all sub-expressions of [n].
     */
    private fun processExp(n: NExpr) {
        val e = n.e

        // let's just not deal with anything quantified
        if (e is TACExpr.QuantifiedFormula) {
            fun rec(node: NExpr) {
                badNodes += node
                node.ops.forEach(::rec)
            }
            rec(n)
            // not returning here so that variables on the rhs gets connected to the main variable node.
        }
        val ops = n.ops
        ops.forEach(::processExp)

        if (!e.tagAssumeChecked.transformable) {
            badNodes += n
        }

        fun allBad() {
            badNodes += ops + n
        }

        fun connectAll() {
            ops.forEach { graph.add2(it, n) }
        }

        when (e) {
            is TACExpr.Sym.Const ->
                if (!e.s.value.canBeBool) {
                    badNodes += n
                }

            is TACExpr.Sym.Var ->
                if (TACMeta.CONTRACT_ADDR_KEY in e.s.meta) {
                    // 0 and 1 are valid values for (reserved) contract addresses
                    badNodes += n
                } else {
                    graph.add2(n, Node.Var(e.s))
                }

            is TACExpr.BinOp.BWAnd,
            is TACExpr.BinOp.BWOr,
            is TACExpr.BinOp.BWXOr ->
                connectAll()

            // If a multiplication has one side that is actually a bool, we can replace it with an ite.
            // This means that multiplication doesn't imply any edges in our graph.
            is TACExpr.Vec.IntMul,
            is TACExpr.Vec.Mul ->
                if (ops.size != 2) {
                    ops.forEach { graph.add2(it, n) }
                } else {
                    graph.add(ops[0], n)
                    graph.add(ops[1], n)
                }

            is TACExpr.BinOp.IntSub, is TACExpr.BinOp.Sub ->
                if (ops[0].e.getAsConst() == BigInteger.ONE) {
                    connectAll()
                } else {
                    allBad()
                }

            is TACExpr.TernaryExp.Ite -> {
                graph.add2(n, ops[1])
                graph.add2(n, ops[2])
            }

            is TACExpr.BinRel ->
                graph.add2(ops[0], ops[1])

            is TACExpr.BinOp.Mod ->
                if (e.o2.getAsConst() == BigInteger.TWO) {
                    graph.add2(n, ops[0])
                } else {
                    allBad()
                }

            is TACExpr.Apply ->
                when ((e.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif) {
                    is TACBuiltInFunction.SafeMathPromotion,
                    is TACBuiltInFunction.SafeMathNarrow ->
                        graph.add2(n, ops[0])

                    else -> allBad()
                }

            else -> allBad()
        }
    }

    /**
     * Holds the [Node.NExpr] corresponding to the rhs expression of an assignment, or the cond expression
     * of an [TACCmd.Simple.AssumeExpCmd].
     *
     * In the process, adds edges to the graph and some [badNodes] are collected.
     */
    private val ptrToNode: Map<CmdPointer, NExpr> =
        g.commands.associateNotNull { (ptr, cmd) ->
            ptr `to?` when (cmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
                    NExpr(cmd.rhs).also {
                        graph.add2(Node.Var(cmd.lhs), it)
                        processExp(it)
                    }

                is TACCmd.Simple.AssumeExpCmd ->
                    NExpr(cmd.cond).also {
                        badNodes += it // because it's boolean.
                        processExp(it)
                    }

                is TACCmd.Simple.AnnotationCmd -> {
                    if (!isTransformableAnnotation(cmd.annot)) {
                        badNodes += cmd.freeVars().mapToSet(Node::Var)
                    }
                    null
                }

                else -> {
                    badNodes += cmd.freeVars().mapToSet(Node::Var)
                    null
                }
            }
        }

    init {
        // this separate init block runs after the various initializations above,
        // including the first init block and the val initializers,
        // in order to also add all nodes reachable from bad nodes
        badNodes += getReachable(badNodes, graph)
    }

    private val patcher = code.toPatchingProgram()

    companion object {
        internal fun boolify(v: TACSymbol.Var) =
            v.updateTag(Tag.Bool, "boolified", meta = v.meta.plus(TACMeta.REPLACED_WITH_BOOL))

        val BigInteger.canBeBool
            get() = this == BigInteger.ZERO || this == BigInteger.ONE

        val Tag.transformable
            get() = this is Tag.Bits || this is Tag.Int
    }

    private fun replace(v: TACSymbol.Var) =
        boolify(v).also { patcher.replaceVarDecl(v, it) }

    /** This is used only for annotations */
    private val mapper = object : DefaultTACCmdMapper() {
        override fun mapVar(t: TACSymbol.Var) =
            if (Node.Var(t) in badNodes) {
                t
            } else {
                replace(t)
            }
    }

    /**
     * Rewrites expressions, counting on the fact that [badNodes] contains any expression that can't be transformed.
     *
     * Note that some of the stuff we transform to can be further simplified. But leave this for later simplifications -
     * specifically [IntervalsRewriter].
     */
    private fun rewriteExpr(n: NExpr): TACExpr {
        val newOps = n.ops.map(::rewriteExpr)
        val e = n.e

        with(TACExprFactUntyped) {
            return if (n in badNodes) {
                if (e is TACExpr.Vec.Mul || e is TACExpr.Vec.IntMul && n.ops.size == 2) {
                    when {
                        n.ops[0] !in badNodes && n.ops[1] !in badNodes ->
                            return Ite(LAnd(newOps[0], newOps[1]), Zero, One)

                        n.ops[0] !in badNodes ->
                            return Ite(newOps[0], newOps[1], Zero)

                        n.ops[1] !in badNodes ->
                            return Ite(newOps[1], newOps[0], Zero)
                    }
                }

                if (n.ops.any { it in badNodes }) {
                    return e.rebuild(newOps)
                }
                // note that the following are always "bad" because they are boolean. but they are special because
                // their operand may actually be "good".
                when (e) {
                    is TACExpr.BinRel.Eq ->
                        e.rebuild(newOps)

                    is TACExpr.BinRel.Lt, is TACExpr.BinRel.Slt ->
                        LAnd(LNot(newOps[0]), newOps[1])

                    is TACExpr.BinRel.Gt, is TACExpr.BinRel.Sgt ->
                        LAnd(newOps[0], LNot(newOps[1]))

                    is TACExpr.BinRel.Le, is TACExpr.BinRel.Sle ->
                        LOr(LNot(newOps[0]), newOps[1])

                    is TACExpr.BinRel.Ge, is TACExpr.BinRel.Sge ->
                        LOr(newOps[0], LNot(newOps[1]))

                    else -> e.rebuild(newOps)
                }
            } else {
                // if a node is good, then it must be the case that all its operands are also good, with two exceptions:
                //   `ite` - only the "then" and "else" operands are good.
                //   `mod` - only the first operand is good, the second must be 2.
                when (e) {
                    is TACExpr.Sym.Const ->
                        e.s.value.asBoolTACExpr

                    is TACExpr.Sym.Var ->
                        replace(e.s).asSym()

                    is TACExpr.Vec.IntMul,
                    is TACExpr.Vec.Mul,
                    is TACExpr.BinOp.BWAnd ->
                        LAnd(newOps)

                    is TACExpr.BinOp.BWOr ->
                        LOr(newOps)

                    is TACExpr.BinOp.BWXOr ->
                        LNot(Eq(newOps[0], newOps[1]))

                    is TACExpr.TernaryExp.Ite ->
                        e.rebuild(newOps) // all optimizations are in `ExpSimplifier` of `IntervalsRewriter`

                    is TACExpr.BinOp.Mod ->
                        newOps[0]

                    is TACExpr.BinOp.IntSub, is TACExpr.BinOp.Sub ->
                        LNot(newOps[1])

                    is TACExpr.Apply ->
                        when ((e.f as TACExpr.TACFunctionSym.BuiltIn).bif) {
                            is TACBuiltInFunction.SafeMathPromotion,
                            is TACBuiltInFunction.SafeMathNarrow ->
                                newOps[0]

                            else -> `impossible!`
                        }

                    else ->
                        error("Shouldn't be transforming $e")
                }
            }
        }
    }

    /**
     * Entry point
     */
    fun go(): CoreTACProgram {
        var count = 0

        g.commands.forEach { (ptr, cmd) ->

            /** returns null if there the rewrite didn't change anything. */
            fun rewrite() = ptrToNode[ptr]!!.let { n ->
                rewriteExpr(n).takeIf { it != n.e }
            }

            when (cmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
                    rewrite()?.let { newRhs ->
                        logger.debug { "Replaced@$ptr rhs from ${cmd.rhs} to $newRhs" }
                        if (Node.Var(cmd.lhs) !in badNodes) {
                            val newLhs = replace(cmd.lhs)
                            logger.debug { "Replaced@$ptr lhs from ${cmd.lhs} to $newLhs" }
                            count++
                            patcher.replaceCommand(
                                ptr,
                                unfoldToExprPlusOneCmd("boolAux", newRhs) {
                                    cmd.copy(lhs = newLhs, rhs = it)
                                }
                            )
                        } else {
                            patcher.replaceCommand(
                                ptr,
                                unfoldToExprPlusOneCmd("boolAux", newRhs) {
                                    cmd.copy(rhs = it)
                                }
                            )
                        }
                    }

                is TACCmd.Simple.AssumeExpCmd ->
                    rewrite()?.let { newCond ->
                        logger.debug { "Replaced@$ptr assume-cond from ${cmd.cond} to $newCond" }
                        patcher.replaceCommand(
                            ptr,
                            unfoldToExprPlusOneCmd("boolAux", newCond) {
                                cmd.copy(cond = it)
                            }
                        )
                    }

                is TACCmd.Simple.AnnotationCmd ->
                    if (isTransformableAnnotation(cmd.annot) &&
                        cmd.annot.mentionedVariables.any { Node.Var(it) !in badNodes }
                    ) {
                        patcher.update(ptr, mapper.mapAnnotationCmd(cmd))
                    }

                else -> Unit
            }
        }

        logger.info { "Replaced $count numeric variables to booleans" }

        return patcher.toCode(code)
    }
}


private class BoolCandidateCalculator(code: CoreTACProgram) : DagDefExprDataFlow<Boolean>(code) {
    override fun join(t1: Boolean, t2: Boolean) = t1 && t2

    override fun handleConst(i: BigInteger) =
        when (i) {
            BigInteger.ZERO -> true
            BigInteger.ONE -> true
            else -> false
        }

    override fun uninitialized(v: TACSymbol.Var) = false

    override fun handleExpr(ptr: CmdPointer, e: TACExpr, values: List<Boolean>): Boolean {
        if (!e.tagAssumeChecked.transformable) {
            return false
        }
        return when (e) {
            is TACExpr.Vec.IntMul,
            is TACExpr.Vec.Mul,
            is TACExpr.BinOp.BWAnd,
            is TACExpr.BinOp.BWOr,
            is TACExpr.BinOp.BWXOr ->
                values.all { it }

            is TACExpr.BinOp.IntSub, is TACExpr.BinOp.Sub ->
                e.getOperands()[0] == BigInteger.ONE && values[1]

            is TACExpr.TernaryExp.Ite ->
                values[1] && values[2]

            is TACExpr.BinOp.Mod ->
                values[0] || e.o2.getAsConst() == BigInteger.TWO

            is TACExpr.Apply ->
                when ((e.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif) {
                    is TACBuiltInFunction.SafeMathPromotion,
                    is TACBuiltInFunction.SafeMathNarrow ->
                        values[0]

                    else -> false
                }

            else -> false
        }
    }
}

