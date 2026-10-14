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

package instrumentation.transformers

import algorithms.strictlyDominates
import analysis.CmdPointer
import datastructures.MutableMultiMap
import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import tac.NBId
import tac.Tag
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACSymbol

/**
 * Sanity checks that a [CoreTACProgram] is in DSA (Dynamic Single Assignment) form: every variable is assigned at most
 * once on any path, and is defined before it is used.
 */
object DSAChecker {

    /**
     * The two ways a variable can violate DSA form:
     *  - [badDefForm]: the definition shape itself is wrong (multiple defs in the same block, or multi-def blocks
     *    that don't form a single-successor diamond with a clean join, unless every use stays inside the arms).
     *    Use-sites are not even examined for these, beyond asking whether they stay inside the arms.
     *    See [analysis.dataflow.DsaDefAnalysis], which relies on this shape to answer reaching definitions without
     *    any dataflow.
     *  - [useBeforeDef]: the def shape is acceptable, but some use is not dominated by a def — i.e. on some path
     *    the variable is read while still undefined. A variable that is read and never assigned is here too.
     */
    data class NonDsaVars(
        val badDefForm: Set<TACSymbol.Var>,
        val useBeforeDef: Set<TACSymbol.Var>,
    ) {
        val isEmpty get() = badDefForm.isEmpty() && useBeforeDef.isEmpty()
    }

    /**
     * Returns the variables in [code] that violate DSA form, split into [NonDsaVars.badDefForm] and
     * [NonDsaVars.useBeforeDef]. A variable is DSA-good when either:
     *  - It has a single definition that strictly dominates every use; or
     *  - Its definitions sit in distinct sibling blocks, each with exactly one successor, all the same block `J`,
     *    whose predecessors are exactly those blocks (a clean diamond), and every use is either in a block
     *    dominated by `J` or in a defining arm, after that arm's own definition; or
     *  - The same, except that `J` has predecessors beyond the defining blocks, and then every use must be in a
     *    defining arm, after that arm's own definition: the variable never escapes its arms, so `J`'s other
     *    predecessors are irrelevant. Optimizations past [DSAToSSA] produce this from a clean diamond: once an arm-
     *    local fact (an assume [analysis.opt.intervals.IntervalsRewriter] put right after the definition) is the
     *    only read left, the arms without one drop their now-dead assignment (CERT-10112).
     *
     * A definition in a leaf block - one with no successor - reaches only the reads after it in its own block, so as
     * long as no other definition is on a path to that leaf, it is set aside, and the rules above apply to the
     * definitions that go on: with none left, the variable is read only in its own blocks; with one, that one is
     * read like a single definition. Leaves like that come from cutting an arm: after an `assume false`
     * ([verifier.PatchingProgramWrapper]), or after an arm's last assert.
     *
     * So the check is three steps: collect the definitions, keeping the single-def variables apart from the rest;
     * check the shape of the multi-def ones, which reduces each of them to the block its arms join at; then walk
     * the program once more and ask of every use that it be dominated by its variable's single definition, by that
     * join, or by the definition its own arm makes. A variable that is never assigned anywhere is undefined at
     * every read, so it lands in [NonDsaVars.useBeforeDef]; the uses of one whose definitions are already
     * malformed are not examined.
     *
     * The arm-local read is the one thing beyond "below the join", and it is not a concession: it is what
     * [analysis.opt.intervals.IntervalsRewriter] produces, by carrying a fact the join assumed back to the arm
     * that defines the condition, which is the natural place to state it. What it costs is that the reaching
     * definition at such a read is that arm's, not "all of them", so anything answering the question must look at
     * the reading block - see [analysis.dataflow.DsaDefAnalysis] and
     * [analysis.dataflow.BranchAwareGlobalValueNumbering], which both do.
     */
    fun nonDsaVars(code: CoreTACProgram): NonDsaVars {
        val g = code.analysisCache.graph
        val dom = code.analysisCache.domination

        val badDefForm = mutableSetOf<TACSymbol.Var>()
        val single = mutableMapOf<TACSymbol.Var, CmdPointer>()
        val multi = mutableMapOf<TACSymbol.Var, MutableMap<NBId, CmdPointer>>()

        for ((ptr, cmd) in g.commands) {
            val v = cmd.getModifiedVar()
                ?: continue
            val defs = multi[v]
            when {
                // a second definition in a block already holding one is never DSA, whatever the shape
                defs != null -> if (defs.put(ptr.block, ptr) != null) {
                    badDefForm += v
                }
                v !in single -> single[v] = ptr
                else -> {
                    val first = single.remove(v)!!
                    if (first.block == ptr.block) {
                        badDefForm += v
                    }
                    multi[v] = mutableMapOf(first.block to first, ptr.block to ptr)
                }
            }
        }

        /** The block [defBlocks] all flow into, if each has it as sole successor. */
        fun commonSuccOf(defBlocks: Set<NBId>) =
            defBlocks.monadicMap { g.succ(it).singleOrNull() }?.uniqueOrNull()

        // every multi-def variable now stands for the single block its definitions join at, or, when that join has
        // other predecessors too, may only be read inside its own arms. A definition in a leaf block reaches nothing
        // beyond it, so the leaves are set aside - as long as no other definition is on a path to one - and the
        // definitions that go on decide how the variable is read elsewhere: none, and it is read only in its own
        // blocks; one, which then is read like a single definition; or several, which join as above.
        val joinOf = mutableMapOf<TACSymbol.Var, NBId>()
        val armLocalOnly = mutableSetOf<TACSymbol.Var>()
        val soleOnwards = mutableMapOf<TACSymbol.Var, CmdPointer>()
        // the leaves to check no other definition reaches, by the block those other definitions all go on through:
        // the join, or the one definition going on - an arm's only way on is its join, and a leaf leads nowhere
        val leavesBelow: MutableMultiMap<NBId, Pair<TACSymbol.Var, NBId>> = mutableMultiMapOf()
        multi.forEachEntry { (v, defs) ->
            if (v in badDefForm) {
                return@forEachEntry
            }
            val (leaves, onwards) = defs.keys.partition { g.succ(it).isEmpty() }
            val through = when (onwards.size) {
                0 -> {
                    armLocalOnly += v
                    null
                }
                1 -> {
                    soleOnwards[v] = defs[onwards.single()]!!
                    onwards.single()
                }
                else -> when (val succ = commonSuccOf(onwards.toSet())) {
                    null -> {
                        badDefForm += v
                        null
                    }
                    else -> {
                        if (g.pred(succ).size == onwards.size) {
                            joinOf[v] = succ
                        } else {
                            armLocalOnly += v
                        }
                        succ
                    }
                }
            }
            if (through != null) {
                leaves.forEach { leavesBelow.add(through, v to it) }
            }
        }

        // One walk forward from each such block, only as far as its farthest leaf in a topological order: a block
        // reaches only blocks after it. A leaf it reaches is assigned the variable twice on the way.
        if (leavesBelow.isNotEmpty()) {
            val topoIndex = code.topoSortFw.withIndex().associate { (i, b) -> b to i }
            leavesBelow.forEachEntry { (from, checks) ->
                val horizon = checks.maxOf { (_, leaf) -> topoIndex[leaf]!! }
                val reached = mutableSetOf(from)
                val work = ArrayDeque(listOf(from))
                while (work.isNotEmpty()) {
                    for (next in g.succ(work.removeLast())) {
                        if (topoIndex[next]!! <= horizon && reached.add(next)) {
                            work += next
                        }
                    }
                }
                checks.forEach { (v, leaf) ->
                    if (leaf in reached) {
                        badDefForm += v
                    }
                }
            }
        }

        val useBeforeDef = mutableSetOf<TACSymbol.Var>()
        for ((ptr, cmd) in g.commands) {
            for (v in cmd.getFreeVarsOfRhs()) {
                if (v in badDefForm) {
                    continue
                }
                val def = single[v]
                val join = joinOf[v]
                val armDef = multi[v]?.get(ptr.block)
                val inOwnArm = armDef?.let { it.pos < ptr.pos } == true
                when {
                    // the join is not clean, so a read escaping the arms is a shape problem, not a missing def
                    v in armLocalOnly -> if (!inOwnArm) {
                        badDefForm += v
                    }
                    def != null -> if (!dom.strictlyDominates(def, ptr)) {
                        useBeforeDef += v
                    }
                    // one definition goes on and the others end in leaves: in a defining block a read must follow
                    // its definition, and elsewhere the one going on must dominate it
                    v in soleOnwards -> {
                        val defined = if (armDef != null) {
                            inOwnArm
                        } else {
                            dom.strictlyDominates(soleOnwards[v]!!, ptr)
                        }
                        if (!defined) {
                            useBeforeDef += v
                        }
                    }
                    // below the join, or in a defining arm after that arm's own definition
                    join != null -> if (!dom.dominates(join, ptr.block) && !inOwnArm) {
                        useBeforeDef += v
                    }
                    // a variable that is never assigned is undefined at every read of it
                    else -> useBeforeDef += v
                }
            }
        }
        return NonDsaVars(badDefForm, useBeforeDef)
    }

    /** Asserts that [code] is in DSA when restricted to variables matching [considerOnly]. */
    fun checkDSA(code: CoreTACProgram, considerOnly: (TACSymbol.Var) -> Boolean = { true }) {
        val (badDefForm, useBeforeDef) = nonDsaVars(code).let {
            NonDsaVars(it.badDefForm.filterToSet(considerOnly), it.useBeforeDef.filterToSet(considerOnly))
        }
        check(badDefForm.isEmpty() && useBeforeDef.isEmpty()) {
            "Not in DSA! badDefForm: $badDefForm; useBeforeDef: $useBeforeDef"
        }
    }

    /**
     * Pre-solver sanity check: by the time the verifier hands a TAC to the solver, everything except map-typed
     * variables must be in DSA. After [DSAToSSA.rewrite] this holds by construction; the only reason to recheck
     * later in the pipeline is to catch passes between `DSAToSSA` and the solver that accidentally break it (e.g.
     * folding the per-predecessor map merge ITE into other blocks would carry reach vars or `tmp_i` operands out
     * of their original block). Maps are intentionally left in "join-only" form by `DSAToSSA` - multi-write maps
     * are merged via an ITE in the common successor whose RHS references map-typed temps defined on only one
     * incoming path - and are therefore exempted from the check.
     */
    fun checkFinalDSA(code: CoreTACProgram) = checkDSA(code) {
        it.tag !is Tag.Map
    }
}
