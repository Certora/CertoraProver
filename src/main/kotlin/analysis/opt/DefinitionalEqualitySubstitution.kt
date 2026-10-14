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

package analysis.opt

import algorithms.dominates
import algorithms.strictlyDominates
import analysis.CmdPointer
import analysis.maybeExpr
import analysis.opt.intervals.IntervalsCalculator.Companion.oneVarSolutions
import datastructures.MultiMap
import datastructures.add
import datastructures.buildMultiMap
import datastructures.stdcollections.*
import tac.NBId
import tac.isSubtypeOf
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.getOperands
import vc.data.isNonTrivialAssert
import vc.data.tacexprutil.asVarOrNull
import vc.data.tacexprutil.getFreeVars

/**
 * `havoc v` ... `v == e` assumed: the HAVOC is deleted and `v := e` takes the place of the
 * ASSUMED EQUALITY -- the shape CVL emits for `mathint x; require x == e;`.
 *
 * `v` is definitionally `e`, but every def analysis resolves through
 * [TACCmd.Simple.AssigningCmd]s and an assume is not one -- so [NonTrivialDefAnalysis] stops at
 * the havoc, and with it [PatternRewriter] and everything else built on top.
 *
 * An assume about `v` alone is not a use of the value, and moves along with the equality rather
 * than blocking it -- see [assumesToRelocate]. CVL's range assume for the declaration is one, and
 * on the program this was written for every candidate had one.
 */
class DefinitionalEqualitySubstitution private constructor(private val code: CoreTACProgram) {

    private val g = code.analysisCache.graph
    private val dom = g.cache.domination

    /** Variables whose ONLY definition is a havoc */
    private val soleHavocs =
        g.soleDefs.filterValues { g.toCommand(it) is TACCmd.Simple.AssigningCmd.AssignHavocCmd }.keys

    /**
     * The other side of `Eq(v, ·)` / `Eq(·, v)`, any expression at all -- it is evaluated where the
     * equality already evaluates it. `v` free in it makes it a constraint, not a definition.
     *
     * The two sides may carry different tags, and the patched program is type-checked on
     * construction, so `bv256 := int` does not degrade the result -- it fails to compile the rule.
     * `Tag.Bits` lifts to `Tag.Int`, so only that one direction is refused.
     */
    private fun otherSide(eq: TACExpr.BinRel.Eq, v: TACSymbol.Var): TACExpr? =
        // matching the symbol, not `v.asSym()`: the tag is part of `TACExpr.Sym.Var`'s equality
        when (v) {
            eq.o1.asVarOrNull -> eq.o2
            eq.o2.asVarOrNull -> eq.o1
            else -> null
        }?.takeIf { v !in it.getFreeVars() && it.tag?.isSubtypeOf(v.tag) == true }

    /**
     * An assumed equality: where it is stated, the equality itself, and the `assume b` that makes
     * it hold -- null for the folded spelling, where the equality IS the assume.
     */
    private data class EqAt(val ptr: CmdPointer, val exp: TACExpr.BinRel.Eq, val assumePtr: CmdPointer?)

    /**
     * Where the non-trivial asserts of each block are; lazy, because a folded-only program never asks.
     * `assert true` is conjoined as `true`, so it has no violation to hide.
     */
    private val assertPositions: MultiMap<NBId, Int> by lazy {
        buildMultiMap {
            g.commands.forEach { (ptr, cmd) ->
                if (isNonTrivialAssert(cmd)) {
                    add(ptr.block, ptr.pos)
                }
            }
        }
    }

    /**
     * Equalities that hold on every path through them, in both spellings: FOLDED
     * `assume Eq(v, e)`, which is what a loaded dump carries, and TWO-COMMAND `b := Eq(v, e)` then
     * `assume b`, which is what the pipeline carries. Same block gives the two-command one its
     * post-domination for free; TAC has no post-dominance analysis to ask instead.
     *
     * An ASSERT in between disqualifies it: [LeinoWP] folds a block backwards, so an assert is
     * conjoined OUTSIDE the implication of every assume that follows it and never sees the
     * equality. `v` is free there, and defining it in front of the assert would hide violations.
     */
    private val assumedEqualities: Set<EqAt> = buildSet {
        for ((ptr, cmd) in g.commands) {
            ((cmd as? TACCmd.Simple.AssumeExpCmd)?.cond as? TACExpr.BinRel.Eq)
                ?.let { add(EqAt(ptr, it, null)) }
        }
        for ((b, usePtr) in g.soleUses) {
            val cmd = g.toCommand(usePtr)
            if (cmd !is TACCmd.Simple.AssumeCmd) {
                continue
            }
            val defPtr = g.soleDefs[b] ?: continue
            if (defPtr.block != usePtr.block || defPtr.pos >= usePtr.pos) {
                continue
            }
            if (assertPositions[usePtr.block].orEmpty().any { it > defPtr.pos && it < usePtr.pos }) {
                continue
            }
            val eqCmd = g.elab(defPtr).maybeExpr<TACExpr.BinRel.Eq>()
                ?: continue
            add(EqAt(eqCmd.ptr, eqCmd.exp, usePtr))
        }
    }


    /** Every use site of each of [vars], in one pass. */
    private fun useSites(vars: Set<TACSymbol.Var>): MultiMap<TACSymbol.Var, CmdPointer> =
        buildMultiMap {
            g.commands.forEach { (ptr, cmd) ->
                cmd.getFreeVarsOfRhs().filter { it in vars }.forEach { add(it, ptr) }
            }
        }

    /** An assume whose only variable is [v]: information about `v` alone, not a use of its value. */
    private fun constrainsOnly(cmd: TACCmd.Simple, v: TACSymbol.Var) =
        cmd is TACCmd.Simple.AssumeExpCmd && cmd.cond.getFreeVars().singleOrNull() == v

    /** `v := `[replacement] replaces the equality at [eqExprPtr]; [havocPtr] defines `v`. */
    private data class Candidate(
        val v: TACSymbol.Var,
        val havocPtr: CmdPointer,
        val eqExprPtr: CmdPointer,
        val assumePtr: CmdPointer?,
        val replacement: TACExpr,
    )

    /**
     * What each assumed equality could define: a side of it that is havoc'd and nothing else.
     * `Eq(v, w)` with two of them is a candidate twice over, and the `eliminated` check in [go]
     * lets only one happen. Order is immaterial -- the fixpoint gets what a round left behind.
     */
    private val candidates =
        assumedEqualities.flatMap { eq ->
            eq.exp.getOperands()
                .mapNotNull { it.asVarOrNull }
                .filter { it in soleHavocs }
                .mapNotNull { v ->
                    otherSide(eq.exp, v)
                        ?.let { Candidate(v, g.soleDefs[v]!!, eq.ptr, eq.assumePtr, it) }
                }
        }

    /** Does the assume at [ptr] hold for SOME [v]? [oneVarSolutions] is exact, or null. */
    private fun provablyHolds(ptr: CmdPointer, v: TACSymbol.Var): Boolean {
        val cond = (g.toCommand(ptr) as? TACCmd.Simple.AssumeExpCmd)?.cond ?: return false
        val (variable, values) = oneVarSolutions(cond) ?: return false
        return variable == v && !values.isEmpty()
    }

    /**
     * The assumes that move with [c], or null if one of the [uses] of `v` stands in the way. The
     * equality has to dominate every use; a self-only assume moves instead of blocking.
     *
     * Moving one drops a constraint where it was -- unobservable, since every other use of `v` is
     * dominated by the equality -- and ADDS one at the equality, which is the dangerous half. That
     * is vacuous only if every path there had already made it, hence it must DOMINATE the equality,
     * and the HAVOC must dominate it or it speaks about an undefined `v`. It must also be provably
     * satisfiable: a contradiction kills its path, and carrying one forward makes everything it
     * used to kill live.
     */
    private fun assumesToRelocate(c: Candidate, uses: Collection<CmdPointer>) =
        uses.filterNot { dom.dominates(c.eqExprPtr, it) }
            .takeIf { blocked ->
                blocked.all {
                    constrainsOnly(g.toCommand(it), c.v) &&
                        dom.strictlyDominates(c.havocPtr, it) &&
                        dom.strictlyDominates(it, c.eqExprPtr) &&
                        provablyHolds(it, c.v)
                }
            }

    /** The program with one round of substitutions applied, or null if none were. */
    private fun go(): CoreTACProgram? {
        val patching = code.toPatchingProgram()
        // a substituted variable must not be substituted again, nor appear in a REPLACEMENT in the
        // same round: two edits would race on one command. A chain takes one round per link.
        val eliminated = mutableSetOf<TACSymbol.Var>()
        val allUses = useSites(candidates.mapToSet { it.v })

        for (c in candidates) {
            if (c.v in eliminated || c.replacement.getFreeVars().any { it in eliminated }) {
                continue
            }
            // a path reaching the equality without the havoc read `v` undefined -- leave it so
            if (!dom.strictlyDominates(c.havocPtr, c.eqExprPtr)) {
                continue
            }
            val toRelocate = assumesToRelocate(c, allUses[c.v].orEmpty()) ?: continue
            patching.replaceCommand(
                c.eqExprPtr,
                listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.v, c.replacement)) +
                    toRelocate.map { g.toCommand(it) }
            )
            for (ptr in toRelocate) {
                patching.delete(ptr)
            }
            // `b` is defined only by the equality and used only by this assume, so both go
            c.assumePtr?.let { patching.delete(it) }
            patching.delete(c.havocPtr)
            eliminated += c.v
        }
        return runIf(eliminated.isNotEmpty()) { patching.toCode(code) }
    }

    companion object {
        /**
         * To a fixpoint: a round gets one link of a chain, since the winner's variable lands in
         * `eliminated`. Terminates -- a round that changes anything deletes a havoc.
         */
        fun go(code: CoreTACProgram) =
            generateSequence(code) { DefinitionalEqualitySubstitution(it).go() }.last()
    }
}
