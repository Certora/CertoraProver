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
import analysis.LTACCmd
import analysis.TACProgramPrinter
import analysis.forwardVolatileDagDataFlow
import analysis.opt.intervals.EdgeGenerator.addEdgesOf
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.ExtBig.Inf
import analysis.opt.intervals.ExtBig.MInf
import analysis.opt.intervals.Intervals.Companion.SEmpty
import analysis.opt.intervals.Intervals.Companion.SFalse
import analysis.opt.intervals.Intervals.Companion.SFull
import analysis.opt.intervals.Intervals.Companion.SFullBool
import analysis.opt.intervals.Intervals.Companion.STrue
import analysis.opt.intervals.Intervals.Companion.sFull
import analysis.opt.intervals.Intervals.Companion.unionOf
import com.certora.collect.*
import com.certora.collect.TreapMap.MergeMode
import datastructures.UniqueCache
import datastructures.stdcollections.*
import org.jetbrains.annotations.TestOnly
import tac.NBId
import tac.Tag
import utils.*
import utils.Color.Companion.blue
import vc.data.*
import vc.data.TACExpr.UnaryExp.LNot
import verifier.PatchingProgramWrapper
import java.math.BigInteger
import analysis.opt.intervals.Intervals as S


/**
 * Over-approximates every expression in the program as `Intervals`.
 *
 * [maxNumIntervals] is the maximal number of intervals we allow in the `Intervals` we save. If this is too
 * small, accuracy will be low, and if this is too large the calculations here may be too expensive.
 *
 * Also figures which blocks can be erased, or partially erased.
 */
class IntervalsCalculator(
    private val code: CoreTACProgram,
    private val maxNumIntervals: Int = 10,
    private val preserve: (TACSymbol.Var) -> Boolean,
    seed: List<Pair<Spot, S>> = listOf(),
    calcJumpPostConditions: Boolean = true
) {

    companion object {

        /** We introduce assumptions into the TAC programs, stating that no longstore exceeds this length bound. */
        private val LONGSTORE_MAX_LENGTH: BigInteger = BigInteger.TWO.pow(160)

        fun intervalOfTag(tag: Tag) =
            when (tag) {
                is Tag.Bits -> sFull(tag)
                Tag.Bool -> SFullBool
                else -> SFull
            }

        fun minOfTag(tag: Tag) = intervalOfTag(tag).min

        fun maxOfTag(tag: Tag) = intervalOfTag(tag).max

        fun goodTag(tag: Tag) =
            tag is Tag.Bits || tag is Tag.Int || tag is Tag.Bool

        fun goodTag(e: TACExpr) = goodTag(e.tagAssumeChecked)

        fun goodTag(s: TACSymbol) = goodTag(s.tag)


        /**
         * Currently we don't handle disjunctions, and this misses out on the common cases where an expression
         * is used to specify the possible values of one variable. These commonly appear when [IntervalsRewriter]
         * itself generated them in a previous application, and because of bounds saying that some variable is
         * actually a small width signed int.
         *
         * This can be removed once disjunction is supported (though it's probably more efficient to leave this in).
         */
        fun calcOneVarExpression(cond: TACExpr): Pair<TACSymbol.Var, S>? {
            if (cond !is TACExpr.BinBoolOp.LOr) {
                return null
            }

            var variable: TACSymbol.Var? = null

            /**
             * checks that this disjunct is made of a constant and a variable, and that the variable is consistent.
             * If it is consistent, this returns the constant and whether the variable is the first operand, otherwise null.
             */
            fun checkAndConst(e1: TACExpr, e2: TACExpr): Pair<BigInteger, Boolean>? =
                when {
                    e1 is TACExpr.Sym.Var && e2 is TACExpr.Sym.Const -> Triple(e2.s.value, e1.s, true)
                    e1 is TACExpr.Sym.Const && e2 is TACExpr.Sym.Var -> Triple(e1.s.value, e2.s, false)
                    else -> null
                }?.let { (c, v, varFirst) ->
                    if (variable == null) {
                        variable = v
                    }
                    (c to varFirst).takeIf { v == variable }
                }

            fun atomic(d: TACExpr): S? {
                if (d !is TACExpr.BinRel) {
                    return null
                }
                val (c, varFirst) = checkAndConst(d.o1, d.o2) ?: return null
                val cc = c.asExtBig
                return when {
                    // a == 7
                    d is TACExpr.BinRel.Eq -> S(c)

                    // a < 7, 7 > a
                    (d is TACExpr.BinRel.Lt && varFirst) || (d is TACExpr.BinRel.Gt && !varFirst) -> S(MInf, cc - 1)

                    // a > 7, 7 < a
                    (d is TACExpr.BinRel.Lt) || (d is TACExpr.BinRel.Gt) -> S(cc + 1, Inf)

                    // a <= 7, 7 >= a
                    (d is TACExpr.BinRel.Le && varFirst) || (d is TACExpr.BinRel.Ge && !varFirst) -> S(MInf, cc)

                    // a >= 7, 7 <= a
                    (d is TACExpr.BinRel.Le) || (d is TACExpr.BinRel.Ge) -> S(cc, Inf)

                    else -> null
                }
            }

            fun generate(e: TACExpr): S? =
                when (e) {
                    is TACExpr.BinBoolOp.LOr ->
                        unionOf(e.ls.map { generate(it) ?: return null })

                    is TACExpr.BinBoolOp.LAnd ->
                        e.ls.map { generate(it) ?: return null }.reduce(S::intersect)

                    else ->
                        atomic(e)
                }

            return generate(cond)?.let { s ->
                variable?.let { it to s }
            }
        }

        /** Used for examining the calculated intervals of a program */
        fun printIntervals(code: CoreTACProgram, calcJumpPostConditions : Boolean = false) {
            val ic = IntervalsCalculator(code, preserve = { false }, calcJumpPostConditions = calcJumpPostConditions)
            TACProgramPrinter.standard()
                .extraLines { (ptr, _) ->
                    code.analysisCache.graph.getLhs(ptr)?.let {
                        listOf(ic.getLhs(ptr).blue)
                    }.orEmpty()
                }
                .print(code)
        }

    }


    private val origGraph = code.analysisCache.graph
    val siteAnalysis = SiteAnalysis(origGraph)
    private val intervalsCache: UniqueCache<S> = UniqueCache()

    /** The current graph */
    val g = DynamicDag(origGraph.rootBlockIds, origGraph.blockSucc)

    fun eraseUnreachableBlocks(patcher : PatchingProgramWrapper, assumeFalseOnDroppedLeaves : Boolean = false) {
        patcher.limitTACProgramTo(g.successors, g.vertices, assumeFalseOnDroppedLeaves)
    }

    /** The starting pos where commands may be erased in each block (if relevant) */
    val erasedFrom = mutableMapOf<NBId, Int>()

    private fun blockCommands(nbid: NBId) =
        origGraph.elab(nbid).commands.let { cmds ->
            erasedFrom[nbid]
                ?.let { cmds.subList(0, it) }
                ?: cmds
        }

    private fun eraseBlock(nbid: NBId) {
        g.erase(nbid)
        erasedFrom -= nbid
    }


    /** A place in the program that should get an approximation */
    sealed interface Spot {
        val ptr: CmdPointer
        val ptrRep get() = "<${ptr.block},${ptr.pos}>"

        @JvmInline
        value class Lhs(override val ptr: CmdPointer) : Spot {
            override fun toString() = ptrRep
        }

        /** An expression lying at the rhs of [ptr] */
        data class Expr(override val ptr: CmdPointer, val expr: TACExpr) : Spot {
            constructor(ptr: CmdPointer, s: TACSymbol) : this(ptr, s.asSym())

            override fun toString() = "$ptrRep-$expr"
        }

        class Aux(val default: S) : Spot {
            override val ptr get() = error("Aux spots don't have a CmdPointer")
            override fun toString() = "Aux"
        }
    }

    private val Spot.Lhs.v get() = origGraph.getLhs(ptr)!!

    private val Spot.vOrNull
        get() = when {
            this is Spot.Expr && expr is TACExpr.Sym.Var -> expr.s
            this is Spot.Lhs -> v
            else -> null
        }

    private val Spot.defaultInterval
        get() =
            when (this) {
                is Spot.Expr -> when (expr) {
                    is TACExpr.Sym.Const -> S(expr.s.value)
                    else -> intervalOfTag(expr.tagAssumeChecked)
                }

                is Spot.Lhs -> intervalOfTag(v.tag)
                is Spot.Aux -> default
            }


    private fun spotsOfCmd(ptr: CmdPointer) =
        origGraph.toCommand(ptr).let { cmd ->
            cmd.getRhs()
                .filter(::goodTag)
                .map { Spot.Expr(ptr, it) }
                .letIf(cmd.getLhs()?.let(::goodTag) == true) {
                    it + Spot.Lhs(ptr)
                }
        }


    private val hGraph = HyperGraphFixedPointCalculator<Spot, S>(
        defaultValue = { it.defaultInterval },
        normalize = { spot, old, new ->
            spot.vOrNull?.takeIf(preserve)?.let { v ->
                intervalOfTag(v.tag)
            } ?: run {
                val r = new.simplify(maxNumIntervals)
                // simplification may make stuff worse, so we don't record it.
                // note that we assume that `new` is always contained in `old`. Otherwise this doesn't make much sense.
                if (r containedIn old) {
                    intervalsCache(r)
                } else {
                    old
                }
            }
        }
    )

    private val h = hGraph.State()

    @TestOnly
    fun hyperGraphForTest() = h


    fun getS(ptr: CmdPointer, e: TACExpr) = h.get(Spot.Expr(ptr, e))
    fun getS(ptr: CmdPointer, s: TACSymbol) = h.get(Spot.Expr(ptr, s))
    fun getLhs(ptr: CmdPointer) = h.get(Spot.Lhs(ptr))

    /**
     * Used for getting the approximation for [v] at the rhs of [ptr] even if [v] does not appear on the rhs of the
     * command at [ptr]. Calling [getS] will give the default intervals for [v] according to its tag in such a case.
     */
    fun getAtRhs(ptr: CmdPointer, v: TACSymbol.Var) =
        h.getOrNull(Spot.Expr(ptr, v)) ?: run {
            /** to see the logic for the forward and backward spots, see [connectBackward] and [connectForward] */
            val backwardApprox = siteAnalysis.backwardsSitesOf(ptr, v)
                ?.map {
                    if (origGraph.getLhs(it) == v) {
                        Spot.Lhs(it)
                    } else {
                        Spot.Expr(it, v)
                    }
                }
                ?.map(h::get)
                ?.let(::unionOf)
                ?: intervalOfTag(v.tag)

            val forwardApprox = run fa@{
                if (origGraph.getLhs(ptr) == v) {
                    return@fa intervalOfTag(v.tag)
                }
                val useSites = siteAnalysis.forwardUseSitesOf(ptr, v)
                    ?: return@fa intervalOfTag(v.tag)
                check(useSites.isNotEmpty())
                unionOf(useSites.map { getS(it, v) })
            }

            forwardApprox intersect backwardApprox
        }


    /**
     * Using this instead of directly `h.set` ensures that that we never save a value that is worse that what was
     * already there. This is an invariant required by [normalize].
     */
    private fun limitTo(spot: Spot, i: S) {
        h.set(spot, h.get(spot) intersect i)
    }

    /** asserts that cant reach any other assert */
    private val lastAssserts =
        if (code.destructiveOptimizations) {
            buildSet {
                // we traverse backwards from the leaves, only looking at a block if we know that all its successors
                // were already checked. when hitting an assert, then anything leading that that block can't be in the result,
                // so we mark it as bad, and propagate that backwards.
                val badBlocks = mutableSetOf<NBId>()
                for (b in topologicalOrder(origGraph.blockSucc)) {
                    if (origGraph.succ(b).any { it in badBlocks }) {
                        badBlocks += b
                        continue
                    }
                    val assert = origGraph.elab(b).commands.findLast { (_, cmd) ->
                        cmd is TACCmd.Simple.AssertCmd && cmd.o != TACSymbol.True
                    } ?: continue
                    badBlocks += b
                    add(assert.ptr)
                }
            }
        } else {
            emptySet()
        }


    /**
     * This uses local calculations to understand jump conditions and propagate the result to the destination blocks.
     * So if:
     * ```
     *    a := b + c
     *    x := a < 100
     *    if x jump to thenBlock else to elseBlock
     * ```
     * then:
     *   1. expands the expression for `x` using assignments in the block to get single variable expressions. In this
     *      case there are two: `x` and `a < 100`
     *   2. analyses these expressions for the two cases: when they are true and when they are false, resulting in
     *      intervals for `a` and `x`. The result for true propagates to the use sites of `a` and `x` reachable from
     *      `thenBlock`, and those for `false` propagate to those reachable from the `elseBlock`.
     */
    val postJump: Map<Spot.Expr, S>? = runIf(calcJumpPostConditions) {
        buildMap {
            val deciders = OneVarExpressions(code).go()

            // A dataflow analysis, calculating for each block, for each interesting variable, what do we know about
            // it from previous analysis of jump conditions.
            // Along the way if fills up the map (of `buildMap` above), with this information for use sites of variables.
            forwardVolatileDagDataFlow<TreapMap<TACSymbol.Var, S>>(code) { b, inMaps ->
                // start the block with taking a union of what we know from `b`'s predecessors.
                // note that if a variable does not appear in the map, it means it has the full intervals range.
                var map = inMaps.reduceOrNull { m1, m2 ->
                    m1.merge(m2, MergeMode.INTERSECTION) { v, s1, s2 ->
                        (s1!! union s2!!).takeIf { it != intervalOfTag(v.tag) }
                    }
                }.orEmpty()
                // If `b` has only one predecessor, calculate what information we can derive from the jump condition
                // that led it to jump from the predecessor to `b`.
                // This is where we calculate what is derived from the jump condition. The maps are just the propagation
                // of this information.
                g.predecessors[b]?.singleOrNull()?.let { pred ->
                    val (jumpiPtr, jumpICmd) = origGraph.elab(pred).commands.last()
                    val cond = (jumpICmd as? TACCmd.Simple.JumpiCmd)?.cond as? TACSymbol.Var
                        ?: return@let
                    val condExpr =
                        cond.asSym().letIf(jumpICmd.elseDst == b) {
                            LNot(it, Tag.Bool)
                        }
                    // consider variables that `condExpr` can be expressed as a one-variable function of.
                    (deciders[pred]?.get(cond).orEmpty() + cond).forEach { v ->
                        intervalsOfOneVarExpression(code, jumpiPtr, condExpr, v)
                            ?.takeIf { it != intervalOfTag(v.tag) }
                            ?.let { calculated ->
                                map += v to (map[v]?.let { it intersect calculated } ?: calculated)
                            }
                    }
                }
                // Go over the block `b`, building the outer map, and updating the block map
                origGraph.lcmdSequence(b).forEach { (ptr, cmd) ->
                    cmd.getFreeVarsOfRhs().forEach { v ->
                        map[v]?.let {
                            put(Spot.Expr(ptr, v), it)
                            // we can stop propagating this information, it will propagate on it's own during
                            // the intervals-calculator fixed point calculation..
                            map -= v
                        }
                    }
                    cmd.getLhs()?.let { v ->
                        map -= v
                    }
                }
                // the final map is what is passed on to successor blocks.
                map
            }
        }
    }


    init {
        seed.forEach { limitTo(it.first, it.second) }

        // we process the commands in topological order so that the fixed point starts with running
        // in this order. This should get us into a reasonable place to start improving it.
        val order = topologicalOrder(code.analysisCache.graph.blockSucc).reversed()
        for (nbid in order) {
            for (lcmd in blockCommands(nbid)) {
                processCmd(lcmd)
            }
        }

        postJump?.forEachEntry { (spot, s) ->
            limitTo(spot, s)
        }

        h.fixedPoint()
        while (true) {
            // Theoretically this can remove one block each time and keep iterating. This sounds highly unlikely.
            // Also, the fixed point does not start over from scratch, and so later iterations are much cheaper than
            // the initial one.
            // Note that one of the main reasons we need to iterate is that ijumps to block that are unreachable
            // get an `assume` on the jump condition, necessitating a continuation of the fixed point computation.
            val changedSpots = removeDeadBlocks()
            if (changedSpots.isEmpty()) {
                break
            }
            h.fixedPoint(changedSpots)
        }
    }


    private fun processCmd(lcmd: LTACCmd) {
        val (ptr, cmd) = lcmd

        cmd.getFreeVarsOfRhs()
            .filter { goodTag(it) && !preserve(it) }
            .forEach { v ->
                connectBackward(v, ptr)
                connectForward(Spot.Expr(ptr, v))
            }

        cmd.getLhs()
            ?.takeIf { goodTag(it) && !preserve(it) }
            ?.let { connectForward(Spot.Lhs(ptr)) }

        when (cmd) {
            is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
                processExpr(cmd.rhs, ptr)
                    .takeIf { goodTag(cmd.lhs) }
                    ?.let { spot ->
                        hGraph.addEdge(
                            v1 = Spot.Lhs(ptr),
                            v2 = spot,
                            func = BiPropagation::same,
                            name = "assign"
                        )
                    }


            is TACCmd.Simple.AssumeCmd ->
                limitTo(Spot.Expr(ptr, cmd.cond), STrue)

            is TACCmd.Simple.AssumeNotCmd ->
                limitTo(Spot.Expr(ptr, cmd.cond), SFalse)

            is TACCmd.Simple.AssumeExpCmd ->
                processExpr(cmd.cond, ptr)?.let {
                    limitTo(it, STrue)
                    calcOneVarExpression(cmd.cond)?.let { (v, s) ->
                        limitTo(Spot.Expr(ptr, v), s)
                    }
                }

            is TACCmd.Simple.JumpiCmd ->
                check(cmd.dst != cmd.elseDst) {
                    "Can a JumpICmd have dst == elseDst? $cmd"
                }

            is TACCmd.Simple.AssertCmd ->
                if (ptr in lastAssserts) {
                    // this essentially translates the assert to `assume !cond; assert false`, which leaves only
                    // the paths violating the assert, and lets us propagate `!cond` backwards.
                    limitTo(Spot.Expr(ptr, cmd.o), SFalse)
                }

            is TACCmd.Simple.ByteLongCopy ->
                limitTo(Spot.Expr(ptr, cmd.length), S(BigInteger.ZERO, LONGSTORE_MAX_LENGTH))

            else -> Unit
        }
    }


    /**
     * Connects [v] appearing at the rhs of [ptr] to the [v] sites directly before it, enforcing:
     *    `union(approximation(sites)) >= approximation(v)`
     */
    private fun connectBackward(v: TACSymbol.Var, ptr: CmdPointer) {
        val sites = siteAnalysis.backwardsSitesOf(ptr, v)
            ?: return
        check(sites.isNotEmpty())
        val spots = sites.map {
            if (origGraph.getLhs(it) == v) {
                Spot.Lhs(it)
            } else {
                Spot.Expr(it, v)
            }
        }
        hGraph.addEdge(
            listOf(Spot.Expr(ptr, v)) + spots,
            BiPropagation::unionExt,
            "def"
        )
    }


    /**
     * Connects a spot of a variable `v` to the sites of the same variable directly after it, enforcing:
     *    `union(approximation(sites)) >= approximation(v)`
     */
    private fun connectForward(spot: Spot) {
        val v = when (spot) {
            is Spot.Expr -> (spot.expr as TACExpr.Sym.Var).s
            is Spot.Lhs -> spot.v
            is Spot.Aux -> `impossible!`
        }
        // In cases like `x := expr(...x...)` the x spot on the rhs shouldn't be connected to the lhs x spot.
        if (spot is Spot.Expr && origGraph.getLhs(spot.ptr) == v) {
            return
        }
        val useSites = siteAnalysis.forwardUseSitesOf(spot.ptr, v)
            ?: return
        check(useSites.isNotEmpty())
        val spots = useSites.map { Spot.Expr(it, v) }
        hGraph.addEdge(
            listOf(spot) + spots,
            BiPropagation::unionExt,
            "forward"
        )
    }


    private fun processExpr(e: TACExpr, ptr: CmdPointer): Spot.Expr? {
        if (!goodTag(e)) {
            return null
        }
        val resultSpot = Spot.Expr(ptr, e)
        when (e) {
            is TACExpr.Sym -> Unit
            else -> {
                if (e is TACExpr.LongStore) {
                    limitTo(Spot.Expr(ptr, e.length), S(BigInteger.ZERO, LONGSTORE_MAX_LENGTH))
                }
                val opSpots = e.getOperands().map { processExpr(it, ptr) }
                if (null in opSpots) {
                    return null
                }
                hGraph.addEdgesOf(e, resultSpot, opSpots.map { it as Spot }, Spot::Aux)
            }
        }
        return resultSpot
    }


    /**
     * Returns all the spots that were changed (most likely erased), and updates the block graph.
     */
    private fun removeDeadBlocks(): Set<Spot> {
        val origBlocks = g.vertices.toSet()

        val changedSpots = mutableSetOf<Spot>()

        fun realSuccs(nbid: NBId): Set<NBId> {
            val succs = (g.successors[nbid] ?: return emptySet()).toMutableSet()
            check(nbid !in erasedFrom) {
                "$nbid is partially erased, but still has successors $succs"
            }
            val (lastPtr, lastCmd) = blockCommands(nbid).last()

            if (lastCmd is TACCmd.Simple.JumpiCmd) {
                val cond = getS(lastPtr, lastCmd.cond)
                when {
                    cond.isTrue -> succs -= lastCmd.elseDst
                    cond.isFalse -> succs -= lastCmd.dst
                    cond.isEmpty() -> succs.clear()
                    origGraph.succ(nbid).size == 2 && succs.size == 1 -> {
                        // one successor was erased, so we should assume the condition.
                        val spot = Spot.Expr(lastPtr, lastCmd.cond)
                        limitTo(spot, S(succs.single() == lastCmd.dst))
                        changedSpots += spot
                    }
                }
            }
            return succs
        }


        fun erase(ptr: CmdPointer) =
            spotsOfCmd(ptr).forEach {
                h.set(it, SEmpty)
                changedSpots += it
            }

        // go backwards in the program.
        // For each block, we figure out from which point (if any), we can erase the rest of the block.
        // Starting at the end, if has no successors then the last command is not needed, and this is true until
        // we see an assert which is non-constant. We still continue going backwards, because if at some point
        // we identify a contradiction, then all commands from this point on should be erased. Also, an `assert(false)`
        // means everything after it can be erased.
        // Note that if cone-of-influence optimizations are disabled we don't start blindly erasing from the end of a
        // block - we'll only do it in this case if the block has a successor that was removed anyway (e.g. it had an
        // `assume false`)
        g.topOrderSinksFirst().forEach { nbid ->
            val realSuccs = realSuccs(nbid)
            var erasingMode = realSuccs.isEmpty() &&
                (code.coiOptimizations || (origGraph.succ(nbid).isNotEmpty() && g.successors[nbid].isNullOrEmpty()))
            val cmds = blockCommands(nbid)
            var erasureStart = cmds.size

            // identify the point where we should start erasing.
            for ((ptr, cmd) in cmds.asReversed()) {
                if (cmd is TACCmd.Simple.AssertCmd) {
                    val i = getS(ptr, cmd.o)
                    if (i != SEmpty) {
                        when {
                            i.isTrue -> Unit // `assert(true)` is a noop
                            i.isFalse -> {
                                // erase everything after an `assert(false)`
                                erasureStart = ptr.pos + 1
                                erasingMode = false
                            }

                            else -> erasingMode = false
                        }
                    }
                }
                if (spotsOfCmd(ptr).any { h.get(it) == SEmpty }) {
                    erasingMode = true
                }
                if (erasingMode) {
                    erasureStart = ptr.pos
                }
            }

            // actual erasing
            if (erasureStart < cmds.size) {
                for (pos in erasureStart..cmds.lastIndex) {
                    erase(CmdPointer(nbid, pos))
                }
                if (erasureStart > 0) {
                    erasedFrom[nbid] = erasureStart
                    realSuccs.forEach { g.disconnect(nbid, it) }
                } else {
                    eraseBlock(nbid)
                }
            }

            // If some of the successors were removed, update the graph.
            (g.successors[nbid].orEmpty() - realSuccs).forEach {
                g.disconnect(nbid, it)
            }
        }

        // now clean whatever was erased because it is no longer reachable.
        (origBlocks - g.vertices)
            .forEach {
                for ((ptr, _) in blockCommands(it)) {
                    erase(ptr)
                }
            }

        return changedSpots
    }

}
