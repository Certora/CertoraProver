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

import analysis.CmdPointer
import analysis.maybeNarrow
import analysis.opt.ConstantPropagatorAndSimplifier
import analysis.opt.intervals.Intervals.Companion.unionOf
import analysis.opt.intervals.IntervalsCalculator.Companion.goodTag
import analysis.opt.intervals.IntervalsCalculator.Companion.intervalOfTag
import analysis.opt.intervals.IntervalsCalculator.Companion.maxOfTag
import analysis.opt.intervals.IntervalsCalculator.Companion.minOfTag
import config.Config
import datastructures.stdcollections.*
import instrumentation.transformers.removeUnusedAssignments
import log.*
import org.jetbrains.annotations.TestOnly
import tac.MetaKey
import tac.MetaMap
import tac.Tag
import utils.*
import utils.Color.Companion.blue
import utils.Color.Companion.cyan
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.tacexprutil.asVarOrNull
import vc.data.tacexprutil.postTransform
import verifier.PatchingProgramWrapper
import java.math.BigInteger
import analysis.opt.intervals.Intervals as S


private val logger = Logger(LoggerTypes.INTERVALS_SIMPLIFIER)


/**
 * Does the actual rewriting of the program using the information calculated in [IntervalsCalculator]. For a high level
 * overview of this optimization, see:
 *         https://www.notion.so/certora/Intervals-Rewriter-8718f03b64474eda9a07496f06902646
 *
 * [handleLeinoVars] takes care of a specific problem with erasing leino variables. If not on in the latest stages of
 * the pipeline, it crashes the tool. No harm in turning it on otherwise, except it makes the optimization slightly
 * more expensive.
 */
class IntervalsRewriter(
    val code: CoreTACProgram,
    private val handleLeinoVars: Boolean,
    private val preserve : (TACSymbol.Var) -> Boolean,
    private val calcJumpPostConditions : Boolean = true,
) {

    companion object {
        fun rewrite(
            code: CoreTACProgram,
            repeat : Int = Config.intervalsRewriter.get(),
            handleLeinoVars: Boolean,
            preserve : (TACSymbol.Var) -> Boolean = { false }//  = { it.namePrefix == IntervalsCalculator.tacM40Prefix }
        ) =
            code.letIf(repeat > 0) {
                var result = ConstantPropagatorAndSimplifier(it, handleLeinoVars).rewrite()
                repeat(repeat) {
                    result = IntervalsRewriter(
                        removeUnusedAssignments(result, expensive = false),
                        handleLeinoVars,
                        preserve
                    ).rewrite()
                }
                removeUnusedAssignments(result, expensive = true)
            }

        /** We eventually add this meta to variables which are known to be non zero via intervals analysis */
        val NON_ZERO_META = MetaKey.Nothing("tac.non.zero.var")
    }

    private val graph = code.analysisCache.graph
    private val patcher = PatchingProgramWrapper(code)

    /**
     * Maintains statistics for the optimizations we do: how many rewrites of every kind happened. How many blocks
     * were erased, etc. This has no effect on the algorithms, and is used just for debugging and information.
     */
    private val stats = SimpleCounterMap<String>()
    private val intervals =
        IntervalsCalculator(code, preserve = preserve, calcJumpPostConditions = calcJumpPostConditions)

    private val txf = TACExprFactTypeChecked(code.symbolTable)

    /** Returns a string with the original program code plus calculated intervals and what rewrites were done */
    private fun debug() =
        patcher.debugPrinter()
            .extraLines { (ptr, cmd) ->
                listOf(
                    cmd.getLhs()
                        ?.takeIf(::goodTag)
                        ?.let { "lhs = ${intervals.getLhs(ptr)}  ".cyan }.orEmpty() +
                        cmd.getFreeVarsOfRhs()
                            .filter(::goodTag)
                            .joinToString { v -> "$v = ${intervals.getS(ptr, v)}".blue }
                )
            }
            .toString(code, "intervalsRewriter")


    private fun finalLog() {
        logger.trace {
            debug()
        }
//        println(debug())
        logger.info {
            stats.toString(code.name)
        }
//        println(stats.toString(code.name).redBg)
    }


    /**
     * Entry Point.
     */
    fun rewrite(): CoreTACProgram {

        if (intervals.g.vertices.isEmpty()) {
            stats.plusOne("ALL-GONE")
            patcher.eraseAll()
            finalLog()
            return patcher.toCode()
        }

        intervals.eraseUnreachableBlocks(patcher)

        // Goes over all command and simplifies them using `handleCmd`, or erases them if we can.
        intervals.g.vertices.forEach { nbid ->
            val eraseFrom = intervals.erasedFrom[nbid]
            graph.lcmdSequence(nbid).forEach { (ptr, cmd) ->
                if (eraseFrom != null && ptr.pos >= eraseFrom) {
                    patcher.delete(ptr)
                } else {
                    handleCmd(ptr, cmd)
                }
            }
        }

        stats.add("erased-blocks", graph.blocks.size - intervals.g.vertices.size)

        graph.commands
            .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignHavocCmd>() }
            .filter { it.cmd.lhs.let { it.tag.isPrimitive() && !preserve(it) } }
            .forEach { (ptr, cmd) ->
                intervals.getLhs(ptr).asConstOrNull?.let {
                    patcher.replace(ptr, AssignExpCmd(cmd.lhs, it.asTACExpr(cmd.lhs.tag), cmd.meta))
                    stats.plusOne("havoc->assign")
                }
            }

        finalLog()

        return patcher.toCode(handleLeinoVars)
    }


    private fun handleCmd(ptr: CmdPointer, cmd: TACCmd.Simple) {
        val newCmds = mutableListOf<TACCmd.Simple>()

        // For every variable on the rhs, figures what we know from the sites before it.
        // If this is different from what we know from the calculator, we add assumes before this command to enforce it.
        cmd.getFreeVarsOfRhs()
            .filter(::goodTag)
            .forEach { v ->
                val forward =
                    if (preserve(v)) {
                        intervalOfTag(v.tag)
                    } else {
                        val calculated = intervals.siteAnalysis.backwardsSitesOf(ptr, v)
                            ?.monadicMap { p ->
                                if (graph.getLhs(p) == v) {
                                    intervals.getLhs(p)
                                } else {
                                    intervals.getS(p, v)
                                }
                            }
                            ?.let(::unionOf)
                            ?: intervalOfTag(v.tag)
                        // if this rhs var can be further limited by a previous post-condition, this is part of what
                        // we know from this forward analysis step.
                        intervals.postJump?.get(IntervalsCalculator.Spot.Expr(ptr, v))
                            ?.let { calculated intersect it }
                            ?: calculated
                    }
                val current = intervals.getS(ptr, v)
                if (!(forward containedIn current)) {
                    newCmds += enforceCmd(v, current)
                }
            }

        /**
         * Simplifies [exp] and if anything changed, uses [newCmd] to create a command out of it.
         * returns null if no simplification applied
         */
        fun simpCmd(exp: TACExpr, newCmd: (TACExpr) -> TACCmd.Simple) =
            ExpSimplifer(ptr, intervals, stats, txf, preserve).simplify(exp).let { (newE, resInterval) ->
                if (resInterval.isConst) {
                    check(newE is TACExpr.Sym.Const)
                }
                var changed = false
                // adds a meta to every variable in the resulting expression that is known to never be equal to zero.
                val augmented = newE.postTransform { e ->
                    e.asVarOrNull
                        ?.takeIf { it.tag is Tag.Int || it.tag is Tag.Bits }
                        ?.takeIf { 0 !in intervals.getS(ptr, it) }
                        ?.withMeta(NON_ZERO_META)?.asSym()
                        ?.also { changed = true }
                        ?: e
                }
                // null if nothing changed, oherwise create the new command with the new expression.
                augmented.runIf(changed || augmented != exp, newCmd)
            }

        val simpCommand = when (cmd) {
            is AssignExpCmd ->
                simpCmd(cmd.rhs) {
                    cmd.copy(rhs = it)
                }

            is TACCmd.Simple.Assume ->
                simpCmd(cmd.condExpr) {
                    expToAssumeCmd(it, cmd.meta)
                }

            is TACCmd.Simple.AssertCmd ->
                simpCmd(cmd.o.asSym()) {
                    if (it == true.asTACExpr) {
                        stats.plusOne("assert-true")
                    }
                    cmd.copy(o = (it as TACExpr.Sym.Const).s)
                }

            is TACCmd.Simple.JumpiCmd -> {
                simpCmd(cmd.cond.asSym()) {
                    check(it is TACExpr.Sym.Const)
                    val dst = when (it.s.value) {
                        BigInteger.ZERO -> cmd.elseDst
                        BigInteger.ONE -> cmd.dst
                        else -> `impossible!`
                    }
                    stats.plusOne("iJump->Jump")
                    TACCmd.Simple.JumpCmd(dst, cmd.meta)
                }.also {
                    if (it == null) {
                        // check the consistency of `g`'s successor relation.
                        check(intervals.g.successors[ptr.block]?.size == 2)
                    }
                }
            }

            is TACCmd.Simple.AnnotationCmd ->
                null // the patcehr takes care of these

            else -> null
        }

        newCmds += simpCommand ?: cmd

        cmd.getLhs()?.let { v ->
            val calculated =
                if (cmd is AssignExpCmd) {
                    ForwardCalculator.eval(
                        (simpCommand as? AssignExpCmd)?.rhs ?: cmd.rhs,
                        cmd.getFreeVarsOfRhs().associateWith {
                            intervals.getS(ptr, it)
                        }
                    )
                } else {
                    intervalOfTag(v.tag)
                }
            val current = intervals.getLhs(ptr)
            // we add assumptions only if they can't be derived from the rhs expression. But maybe we should anyway add
            // them. Like `a := b * c`, we can still add what we calculated for `a`, even if there is nothing new coming
            // from later assumptions. It can replace the standard `<0, 2^256-1>` axioms we have.
            if (current != calculated) {
                newCmds += enforceCmd(v, current)
            }
        }
        // add the result unless it's only one command, and it's actually the original one.
        if (newCmds.size > 1 || simpCommand != null) {
            patcher.replace(ptr, newCmds)
        }
    }


    /**
     * Makes an assume command out of a given expression.
     * This is actually aesthetic, we can just do `AssumeExpCmd(e)`
     */
    private fun expToAssumeCmd(e: TACExpr, meta: MetaMap = MetaMap(), isPositive: Boolean = true): TACCmd.Simple =
        when (e) {
            is TACExpr.Sym.Const -> when (e.s.value) {
                BigInteger.ONE -> TACCmd.Simple.AssumeCmd(isPositive.asTACSymbol(), "expToAssumeCmd")
                BigInteger.ZERO -> TACCmd.Simple.AssumeCmd((!isPositive).asTACSymbol(), "expToAssumeCmd")
                else -> error("Expected boolean in assumeCmd but got ${e.s.value}")
            }

            is TACExpr.Sym.Var ->
                if (isPositive) {
                    TACCmd.Simple.AssumeCmd(e.s, "expToAssumeCmd", meta)
                } else {
                    TACCmd.Simple.AssumeNotCmd(e.s, meta)
                }

            is TACExpr.UnaryExp.LNot ->
                expToAssumeCmd(e.o, meta, !isPositive)

            else ->
                TACCmd.Simple.AssumeExpCmd(
                    e.letIf(!isPositive) { txf.LNot(e) },
                    meta
                )
        }


    /**
     * Creates an assume command to assume [needed] on [v].
     * This is called only if [needed] is different than what we already know, but in such a case we reassume everything
     * in needed. This can be minimized by not enforcing the parts that are already known.
     */
    private fun enforceCmd(v: TACSymbol.Var, needed: S) = txf {
        // first the special case that is equivalent to `v != constant`
        if (needed.size == 2 &&
            needed[0].low == minOfTag(v.tag) &&
            needed[1].high == maxOfTag(v.tag) &&
            needed[0].high == needed[1].low - 2
        ) {
            return@txf LNot(
                Eq(v.asSym(), (needed[0].high + 1).n.asTACExpr)
            )
        }
        needed.map { i ->
            i.asConstOrNull
                ?.let {
                    when (v.tag) {
                        is Tag.Bool -> when (it) {
                            BigInteger.ZERO -> LNot(v.asSym())
                            BigInteger.ONE -> v.asSym()
                            else -> error("expected $v to have a boolean domain, but got $it")
                        }

                        else -> Eq(v.asSym(), it.asTACExpr(v.tag))
                    }
                }
                ?: LAndIfNeeded(
                    listOfNotNull(
                        i.low.takeIf { it > minOfTag(v.tag) }
                            ?.nOrNull()
                            ?.let { Ge(v.asSym(), it.asTACExpr(v.tag)) },
                        i.high
                            .takeIf { it < maxOfTag(v.tag) }
                            ?.nOrNull()
                            ?.let { Le(v.asSym(), it.asTACExpr(v.tag)) }
                    )
                )
        }.let(::LOrIfNeeded)
    }.let(::expToAssumeCmd)

    @TestOnly
    fun statsForTest() = stats
}
