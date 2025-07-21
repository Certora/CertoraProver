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

import analysis.ExprView
import analysis.LTACCmd
import analysis.commands
import analysis.patterns.PatternHelpers.ops
import analysis.patterns.get
import analysis.split.SplitContext.Companion.isSimpleVar
import analysis.split.Ternary.Companion.bwNot
import analysis.split.arrays.PackedArrayRewriter
import analysis.split.arrays.PackingInfoKey.BITWIDTH
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import datastructures.EdgeLabeledGraph
import datastructures.UndirectedGraph
import datastructures.UniqueCache
import datastructures.get
import datastructures.stdcollections.*
import evm.EVM_BYTE_SIZE
import log.*
import scene.ITACMethod
import tac.TACBasicMeta.IS_IMMUTABLE
import tac.Tag
import utils.*
import utils.Color.Companion.green
import utils.Color.Companion.red
import utils.Color.Companion.yellow
import utils.ModZm.Companion.lowOnes
import vc.data.TACCmd
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.freeVars
import vc.data.tacexprutil.TACExprFreeVarsCollector
import java.math.BigInteger


/**
 * Finds a [Split] for each variable and storage path in the contract, so that they are aligned with each other.
 * See the design document [https://www.notion.so/certora/Storage-Splitting-2-1-432dd60ee7ee44dcadd3f2a158972dd6] for more details.
 *
 * First step is to find these splits according to an analysis of the contract. Then we check if we are aligned with
 * what the solidity compiler suggests. If there are conflicts, we take a join of the two suggestions and find a fixed
 * point again.
 */
class SplitFinder(
    private val cx: SplitContext,
    private val arraysInfo: PackedArrayRewriter.ForFinder,
) {
    /** Used so that we have only one copy of each [Node]. This is important using [Node.ternary] */
    private val nodesCache = UniqueCache<Node>()

    /** A Node in the split relationship graph */
    private sealed class Node {
        /** this should be set only via the [joinWith] method */
        open var split: Split = Split.none

        data class Var(val v: TACSymbol.Var, val method: ITACMethod) : Node() {
            override fun toString() = "$v[$method]"
        }

        data class Storage(val path: NonIndexedPath) : Node() {
            override fun toString() = path.toString()
        }

        /** This is not a data class so that we can have different instances as different nodes in the graph. */
        class Aux : Node() {
            override fun toString() = "Aux${this.hashCode()}"
        }

        /**
         * Immutable vars should have the same split across methods - they are actually storage vars. Therefore
         * They have a special node much like storage nodes.
         */
        data class Immutable(val v: TACSymbol.Var) : Node() {
            override fun toString() = "Immutable-$v"
        }

        /** A node with a predefined split. current use case is for packed array access patterns */
        class External(override var split: Split) : Node() {
            override fun toString() = "External($split)"
        }
    }


    /**
     * Collects for each variable the join of all its ternaries wherever it's assigned or used.
     *
     * Shouldn't be used directly, only through [Node.ternary]
     */
    private val ternaries = mutableMapOf<Node, Ternary>().also { ts ->
        cx.methods.forEach { method ->

            fun updateVar(v: TACSymbol.Var, t: Ternary) =
                ts.merge(varNode(v, method), t, Ternary::join)

            method.commands.forEach { (ptr, cmd) ->
                if (cmd is TACCmd.Simple.AssigningCmd && isSimpleVar(cmd.lhs)) {
                    updateVar(cmd.lhs, cx.ternaries(method).getLhs(ptr))
                }
                cmd.getFreeVarsOfRhs()
                    .filter { isSimpleVar(it) }
                    .forEach { v ->
                        updateVar(v, cx.ternaries(method).getRhs(ptr, v.asSym()))
                    }
            }
        }
    }

    /**
     * The [Ternary] of a [Node.Storage] is just unknown because we may read uninitialized storage.
     * That of [Node.Var] is the join of all places its used or assigned.
     * That of [Node.Aux] is set when it is created.
     */
    private val Node.ternary
        get() = when (this) {
            is Node.Storage -> Ternary.allXs
            is Node.Immutable -> Ternary.allXs
            is Node.Var ->
                when (v.tag) {
                    Tag.Bool -> ternaries[this] ?: Ternary.boolX
                    Tag.Bit256 -> ternaries[this] ?: Ternary.allXs
                    else -> `impossible!`
                }

            is Node.Aux -> ternaries[this]!!
            is Node.External -> Ternary.allXs
        }

    /** For memory efficiency */
    private val splitsCache = UniqueCache<Split>()

    /** Returns true if there was a change */
    private fun Node.joinWith(newSplit: Split): Boolean {
        if (ternary.isConstant()) {
            return false
        }
        val calculated = (newSplit cleanIntersect ternary) join split
        return (calculated != split).also {
            split = splitsCache(calculated)
        }
    }

    /**
     * Removes ranges that are completely constant, and removes known high zeros from other ranges.
     *
     * For example, if the split is [(0,5), (10, 15), (20, 30)], then:
     *
     * If the ternary of the node is made up of constant bits in the 10-15 bit range, then this range is removed
     * from the split. That is because, anywhere the 10-15 bit-range of this node is used, we can just inline the
     * constant bits we see. The importance of this removal is that the splits of nodes connected to this node don't
     * need to match this 10-15 bit range.
     *
     * Probably the most common example is when `a := b & mask`, where mask is made up of zeros at the 10-15 range. In
     * this case, the ternary of a is zeros there, and therefore even if b has 10-15 in its split, it will not appear in
     * a.
     *
     * If the ternary of the node is made up zeros in bits 3-5, then in the split, we reduce the size of bit-range 0-5
     * to be only 0-2. This is a valid because eventually, this bit-range will get its own 256 bit variable, which has
     * all top bits as 0 (note that even signed ints are packed using a bitwise-and, so top bits are 0 until it is
     * sign-extended in the unpacking).
     *
     * Again, a common use case, is `a := some function of b`, and b is known to have high zeros. In this case, as we
     * know nothing of a, its split is just 0-255, but b's split will be smaller. This is especially important if b is
     * the result of unpacking. Without this clearing of high bits, the analysis of the nodes related to the unpacking
     * would not work.
     */
    private infix fun Split.cleanIntersect(t: Ternary): Split {
        if (t !is Ternary.NonBottom) {
            return this
        }
        val constants = t.constants
        val ranges = this
            .map { range ->
                range to range.toMask()
            }.filterNot { (_, r) -> // if range is totally constants we can drop it.
                r and constants == r
            }.map { (range, r) -> // remove all high zeros
                val firstHighZero = (r and bwNot(t.zeros)).bitLength()
                if (firstHighZero != range.highBit) {
                    BitRange(range.lowBit, firstHighZero)
                } else {
                    range
                }
            }
        return Split(ranges)
    }

    private fun unsplittable(n: Node) =
        n.joinWith(Split.all)

    /**
     * These constructor-like functions are here because a sealed class can't be inner, and so don't have access to
     * [nodesCache].
     */
    private fun varNode(v: TACSymbol.Var, method: ITACMethod): Node.Var {
        check(isSimpleVar(v)) {
            "Creating a variable node for ${v.tag}"
        }
        return nodesCache(Node.Var(v, method)) as Node.Var
    }

    private fun storageNode(path: NonIndexedPath) =
        nodesCache(Node.Storage(path))

    private fun auxNode(t : Ternary) =
        nodesCache(Node.Aux()).also {
            ternaries[it] = t
        }

    private fun auxBWAnd(mask: BigInteger, ofWhom: Node) =
        auxNode(ofWhom.ternary and Ternary(mask))

    private fun externalNode(split: Split) =
        nodesCache(Node.External(split))

    private fun immutableNode(v: TACSymbol.Var) =
        nodesCache(Node.Immutable(v))

    /** An edge is here if the two splits must agree */
    private val equalitiesGraph = UndirectedGraph<Node>()

    /** An edge is here if the two splits must agree, but shifted according to the edge label */
    private val shiftGraph = EdgeLabeledGraph<Node, Int>()

    private fun neighbours(n: Node) =
        equalitiesGraph[n] + shiftGraph[n].map { it.second }

    /** Keep joining the split of nodes with the splits of the neighbors until nothing changes */
    private fun fixedPoint(startWith: Collection<Node>) {
        val workQueue = ArrayDeque(startWith.toSet())
        while (workQueue.isNotEmpty()) {
            val n = workQueue.removeFirst()

            val res1: Split =
                equalitiesGraph[n]
                    .map { it.split }
                    .fold(Split.none, Split::join)

            val res: Split =
                shiftGraph[n]
                    .map { (by, n2) -> n2.split shift by }
                    .fold(res1, Split::join)

            if (n.joinWith(res)) {
                workQueue.addAll(neighbours(n))
            }
        }
    }

    sealed class Result {
        data class SUCCESS(
            val pathSplits: Map<NonIndexedPath, Split>,
            val varSplits: Map<ITACMethod, Map<TACSymbol.Var, Split>>
        ) : Result()

        class FAILURE(
            val badArrays: Set<NonIndexedPath>
        ) : Result()
    }

    /**
     * Returns the resulting split, for each [NonIndexedPath] and for each var in each method where the split is
     * not trivial.
     *
     * [Result.FAILURE] is very rare, because arrays were checked for array access patterns for all their accesses.
     * Still, in [PackedArrayRewriter] we don't check constant index accesses patterns for their implied width, and
     * leave to this class. So an inconsistency may only appear here.
     */
    fun calc(): Result {

        populateGraph()

        // All keyword vars and such, are considered unsplittable
        nodesCache
            .filterIsInstance<Node.Var>()
            .filter { n ->
                isSimpleVar(n.v) && cx.isForbiddenVar(n.v, n.method)
            }.forEach { n ->
                when (n.v.tag) {
                    Tag.Bool -> n.joinWith(Split.just0thBit)
                    Tag.Bit256 -> n.joinWith(Split.all)
                    else -> Unit
                }
            }

        // All the neighbours of any node who got some split value at the initial stage get on the queue.
        val startWith = nodesCache
            .filter { it.split != Split.none }
            .flatMap { neighbours(it) }

        fixedPoint(startWith)

        // We play it safe, and join the type data we get from the solc compiler with our own.
        // This may actually harm splitting efforts, either because they get it wrong, or because we find some
        // user implemented splitting the compiler isn't aware of.
        // It can be the other way around as well: if the user uses some assembly storage access, we probably should
        // still split according to the compiler, and glue back the split parts just for the bad storage access.
        var loopCount = 0
        while (true) {
            if (loopCount++ > 5) {
                cx.logger.warn { "SplitFinder looped already $loopCount times. That seems very unlikely."}
            }
            val continueWith = nodesCache
                .filterIsInstance<Node.Storage>()
                .flatMap { n ->
                    val expected = cx.layout.expectedSplit(n.path)
                    if (n.joinWith(expected)) {
                        neighbours(n)
                    } else {
                        emptySet()
                    }
                }
            if (continueWith.isEmpty()) {
                break
            }
            fixedPoint(continueWith)
        }

        /**
         * The packed arrays whose split does not match what we found in pattern matching. These are marked and the
         * whole [SplitFinder] process is restarted.
         */
        val arraysMismatch =
            nodesCache
                .filterIsInstance<Node.Storage>()
                .filter { n -> arraysInfo.arrayWidths.isArray(n.path) }
                .mapNotNullTo(mutableSetOf()) { n ->
                    val width = arraysInfo.arrayWidths.widthOf(n.path)
                    if (n.split == Split.repeated(width)) {
                        null
                    } else {
                        val msg = "packing pattern for array at ${cx.contract} for path ${n.path} is inconsistent." +
                            "giving up on array pattern. This may cause solving slowdowns"
                        Logger.regression { msg }
                        Logger.always(msg, respectQuiet = true)
                        cx.logger.info {
                            "Splitting ${n.path} = ${n.split}, expected array of width $width".red
                        }
                        n.path
                    }
                }

        if (arraysMismatch.isNotEmpty()) {
            return Result.FAILURE(arraysMismatch)
        }

        nodesCache
            .filterIsInstance<Node.Storage>()
            .forEach { n ->
                val expected = cx.layout.expectedSplit(n.path)
                val (dbgMsg, warn) = when {
                    expected == Split.none -> "Missing storage info".yellow to false
                    n.split != expected ->
                        "Calculated unpacking conflicts with solc. This can cause solving slowdowns".red to true

                    else -> "Matches expected".green to false
                }
                fun msg() = "${cx.contract} : Splitting ${n.path} = ${n.split} (solc reports $expected), $dbgMsg"
                if (warn) {
                    val m = msg()
                    Logger.regression { m }
                    cx.logger.warn { msg() }
                } else {
                    cx.logger.info { msg() }
                }
            }

        val storageSplits =
            nodesCache
                .filterIsInstance<Node.Storage>()
                .associate { n ->
                    n.path to n.split
                }

        val methodToNodes =
            nodesCache
                .filterIsInstance<Node.Var>()
                .groupBy { it.method }

        val varSplits = cx.methods.associateWith { method ->
            methodToNodes[method]?.associate { n ->
                n.v to n.split
            } ?: emptyMap()
        }

        return Result.SUCCESS(storageSplits, varSplits)
    }

    /**
     * Adds the edges in the split relationship graph according to the cmds in the contract.
     *
     * Along the way it recognizes which nodes should not be split and marks them as so. These will start the fixed
     * point calculation later.
     */
    private fun populateGraph() {

        for (method in cx.methods) {

            fun varNode(v: TACSymbol.Var) = varNode(v, method)

            /**
             * A [NonIndexedPath] gets a node of its own, and if some var has a few of these associated with it, then
             * they should all have the same split (thus are connected in the graph).
             */
            fun addPaths(v: TACSymbol, paths: Collection<NonIndexedPath>) {
                for (p in paths) {
                    val n = storageNode(p)
                    if (v is TACSymbol.Var) {
                        equalitiesGraph.add(n, varNode(v))
                    }
                }
                for ((p1, p2) in paths.zipWithNext()) {
                    equalitiesGraph.add(storageNode(p1), storageNode(p2))
                }
            }

            fun unsplittable(v: TACSymbol) {
                if (v is TACSymbol.Var && isSimpleVar(v)) {
                    unsplittable(varNode(v))
                }
            }

            for (lcmd in method.commands) {

                fun unsplittableRhs() =
                    lcmd.cmd.getFreeVarsOfRhs().forEach {
                        unsplittable(it)
                    }

                if (applyOverride(method, lcmd)) {
                    continue
                }

                when (val cmd = lcmd.cmd) {
                    is TACCmd.Simple.AssigningCmd.WordLoad -> {
                        unsplittable(cmd.loc)
                        if (cx.isStorage(cmd.base) && cx.storagePaths(cmd) != null) {
                            // no-op when storagePaths is empty
                            addPaths(cmd.lhs, cx.storagePaths(cmd)!!)
                        } else {
                            unsplittable(cmd.lhs)
                        }
                    }

                    is TACCmd.Simple.WordStore -> {
                        unsplittable(cmd.loc)
                        if (cx.isStorage(cmd.base) && cx.storagePaths(cmd) != null) {
                            // no-op when storagePaths is empty
                            addPaths(cmd.value, cx.storagePaths(cmd)!!)
                        } else {
                            unsplittable(cmd.value)
                        }
                    }

                    is AssignExpCmd ->
                        if (isSimpleVar(cmd.lhs)) {
                            handleAssign(varNode(cmd.lhs), cmd.rhs, method)
                        } else {
                            unsplittableRhs()
                        }

                    is TACCmd.Simple.AssigningCmd -> {
                        unsplittable(cmd.lhs)
                        unsplittableRhs()
                    }

                    else ->
                        unsplittableRhs()
                }

                // Immutable vars should have the same split across all methods
                lcmd.cmd.freeVars()
                    .filter { IS_IMMUTABLE in it.meta }
                    .forEach { equalitiesGraph.add(immutableNode(it), varNode(it)) }
            }
        }
    }


    private fun handleAssign(lhs: Node, expr: TACExpr, method: ITACMethod) {

        fun varNode(e: TACExpr) = varNode((e as TACExpr.Sym.Var).s, method)
        fun varNode(s: TACSymbol.Var) = varNode(s, method)

        fun exprVars() = TACExprFreeVarsCollector.getFreeVars(expr).filter { isSimpleVar(it) }

        fun ternaryOf(e: TACExpr) =
            when (e) {
                is TACExpr.Sym.Const -> Ternary(e.s.value)
                is TACExpr.Sym.Var -> varNode(e).ternary
                else -> `impossible!`
            }

        /** everything is unsplittable */
        fun fallback() {
            unsplittable(lhs)
            for (v in exprVars()) {
                unsplittable(varNode(v))
            }
        }

        /**
         * Executes [f] on [es] if all of them are actually [TACExpr.Sym] otherwise `fallback`.
         * We only expect non-nested expressions on the rhs. If we get a nested expression we just fallback.
         */
        fun symbolsOrFallBack(vararg es: TACExpr, f: (List<TACExpr.Sym>) -> Unit) =
            if (es.all { it is TACExpr.Sym }) {
                f(es.map { it as TACExpr.Sym })
            } else {
                fallback()
            }

        fun eqToLhs(e: TACExpr.Sym) {
            if (e is TACExpr.Sym.Var) {
                equalitiesGraph.add(lhs, varNode(e.s))
            }
        }

        /** shift lefts have a positive [by] */
        fun eqToLhsShifted(e: TACExpr.Sym, by: Int) {
            if (e is TACExpr.Sym.Var) {
                val n = varNode(e.s)
                shiftGraph.add(lhs, n, by)
                shiftGraph.add(n, lhs, -by)
            }
        }

        when (expr) {

            is TACExpr.Sym.Const ->
                Unit

            is TACExpr.Sym.Var ->
                eqToLhs(expr)

            is TACExpr.UnaryExp ->
                symbolsOrFallBack(expr.o) {
                    eqToLhs(it.first())
                }

            is TACExpr.Vec -> {
                if (expr.ls.size != 2) {
                    fallback()
                } else {
                    symbolsOrFallBack(expr.o1, expr.o2) { (v1, v2) ->
                        val t1 = ternaryOf(v1)
                        val t2 = ternaryOf(v2)
                        when (expr) {
                            is TACExpr.Vec.Add -> {
                                if (t1 and t2 == Ternary.zero) {
                                    eqToLhs(v1)
                                    eqToLhs(v2)
                                } else {
                                    fallback()
                                }
                            }

                            is TACExpr.Vec.Mul -> {
                                when {
                                    t1.isPowOfTwo() -> eqToLhsShifted(v2, t1.asConstant().lowestSetBit)
                                    t2.isPowOfTwo() -> eqToLhsShifted(v1, t2.asConstant().lowestSetBit)
                                    else -> fallback()
                                }
                            }

                            else ->
                                fallback()
                        }
                    }
                }
            }

            is TACExpr.BinOp -> {
                symbolsOrFallBack(expr.o1, expr.o2) { (v1, v2) ->

                    when (expr) {

                        is TACExpr.BinOp.BWAnd,
                        is TACExpr.BinOp.BWOr -> {
                            eqToLhs(v1)
                            eqToLhs(v2)
                        }

                        is TACExpr.BinOp.Div -> {
                            val t = ternaryOf(v2)
                            if (t.isPowOfTwo()) {
                                eqToLhsShifted(v1, -t.asConstant().lowestSetBit)
                            } else {
                                fallback()
                            }
                        }

                        is TACExpr.BinOp.Mod -> {
                            val t = ternaryOf(v2)
                            if (t.isPowOfTwo()) {
                                eqToLhs(v1)
                            } else {
                                fallback()
                            }
                        }

                        is TACExpr.BinOp.ShiftRightLogical,
                        is TACExpr.BinOp.ShiftLeft -> {
                            ternaryOf(v2).asIntOrNull()?.let {
                                if (expr is TACExpr.BinOp.ShiftLeft) it else -it
                            }?.let { by ->
                                eqToLhsShifted(v1, by)
                            } ?: fallback()
                        }

                        // a shift right followed by a sign extend.
                        is TACExpr.BinOp.ShiftRightArithmetical -> {
                            ternaryOf(v2).asIntOrNull()?.let { by ->
                                if (v1 is TACExpr.Sym.Var) {
                                    val rhs = varNode(v1)
                                    val aux1 = auxNode(rhs.ternary shiftRight by)
                                    shiftGraph.add(aux1, rhs, by)
                                    shiftGraph.add(rhs, aux1, -by)
                                    unsplittable(lhs)
                                } else {
                                    fallback()
                                }
                            } ?: fallback()
                        }

                        // We think of a sign-extend as being preceded with a bw-and that keeps only
                        // the lower bits of the argument. This is necessary the ternary of the sign-extend loses
                        // this information.
                        is TACExpr.BinOp.SignExtend ->
                            ternaryOf(v1).asIntOrNull()?.let { b ->
                                val topBit = (b + 1) * EVM_BYTE_SIZE.intValueExact()
                                if (v2 is TACExpr.Sym.Var) {
                                    val rhs = varNode(v2)
                                    val extra = auxBWAnd(lowOnes(topBit), rhs)
                                    equalitiesGraph.add(lhs, extra)
                                    equalitiesGraph.add(extra, rhs)
                                    unsplittable(lhs)
                                } else {
                                    fallback()
                                }
                            } ?: fallback()

                        else ->
                            fallback()
                    }
                }
            }

            is TACExpr.BinRel -> {
                fallback()
            }

            is TACExpr.TernaryExp.Ite ->
                symbolsOrFallBack(expr.i, expr.t, expr.e) { (i, t, e) ->
                    when (ternaryOf(i)) {
                        Ternary.one -> eqToLhs(t)
                        Ternary.zero -> eqToLhs(e)
                        else -> {
                            eqToLhs(e)
                            eqToLhs(t)
                        }
                    }
                }

            else ->
                fallback()
        }
    }

    /** external overriding from analysis of packed arrays */
    private fun applyOverride(method: ITACMethod, lcmd: LTACCmd): Boolean {
        fun v(v: TACSymbol) = varNode(v as TACSymbol.Var, method)
        arraysInfo.overrides[method, lcmd]?.let { info ->
            val (o1, o2) = info.ops(lcmd)
            val lhs = ExprView<TACExpr.BinExp>(lcmd).lhs
            equalitiesGraph.add(v(lhs), v(o1))
            equalitiesGraph.add(v(o1), externalNode(Split.repeated(info[BITWIDTH]!!)))
            unsplittable(v(o2))
            return true
        }
        return false
    }
}
