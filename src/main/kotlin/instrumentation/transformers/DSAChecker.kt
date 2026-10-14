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
     *    that don't form a single-successor diamond with a clean join). Use-sites are not even examined for these.
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
     *    dominated by `J` or in a defining arm, after that arm's own definition.
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

        /** The block [defBlocks] all flow into, if they are its only predecessors and each has it as sole successor. */
        fun cleanJoinOf(defBlocks: Set<NBId>) =
            defBlocks.monadicMap { g.succ(it).singleOrNull() }
                ?.uniqueOrNull()
                ?.takeIf { g.pred(it).size == defBlocks.size }

        // every multi-def variable now stands for the single block its definitions join at
        val joinOf = mutableMapOf<TACSymbol.Var, NBId>()
        multi.forEachEntry { (v, defs) ->
            if (v !in badDefForm) {
                cleanJoinOf(defs.keys)
                    ?.let { joinOf[v] = it }
                    ?: badDefForm.add(v)
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
                val defined = when {
                    def != null -> dom.strictlyDominates(def, ptr)
                    // below the join, or in a defining arm after that arm's own definition
                    join != null -> dom.dominates(join, ptr.block) ||
                        multi[v]?.get(ptr.block)?.let { it.pos < ptr.pos } == true
                    // a variable that is never assigned is undefined at every read of it
                    else -> false
                }
                if (!defined) {
                    useBeforeDef += v
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
