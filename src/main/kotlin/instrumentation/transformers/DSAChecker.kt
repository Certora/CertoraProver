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
import datastructures.add
import datastructures.buildMultiMap
import datastructures.stdcollections.*
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
     *  - [useBeforeDef]: the def shape is acceptable, but some use is not strictly dominated by a def — i.e. on
     *    some path the variable is read while still undefined.
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
     *  - Its definitions sit in distinct blocks that all flow into one common successor (a diamond), that successor
     *    has no other predecessors, and every use is either later in a defining block (same block as that block's
     *    def, at a strictly later pos) or in a block dominated by the join.
     */
    fun nonDsaVars(code: CoreTACProgram): NonDsaVars {
        val g = code.analysisCache.graph
        val dom = code.analysisCache.domination

        val allDefs = buildMultiMap {
            for ((ptr, cmd) in g.commands) {
                val v = cmd.getModifiedVar()
                    ?: continue
                add(v, ptr)
            }
        }
        val allUsages = buildMultiMap {
            for ((ptr, cmd) in g.commands) {
                for (v in cmd.getFreeVarsOfRhs()) {
                    add(v, ptr)
                }
            }
        }

        // null = def shape itself is wrong; true = every use is properly covered; false = some use isn't.
        fun classify(v: TACSymbol.Var, defs: Set<CmdPointer>): Boolean? {
            require(defs.isNotEmpty())
            if (defs.size == 1) {
                val defPtr = defs.single()
                return allUsages[v].orEmpty().all {
                    dom.strictlyDominates(defPtr, it)
                }
            }
            if (!defs.map { it.block }.allDifferent()) {
                return null
            }
            val defsByBlock = defs.associateBy { it.block }
            val defBlocks = defsByBlock.keys
            // Each defining block must flow into exactly one successor; a sink can't participate in a diamond shape.
            val succs = defBlocks.map {
                g.succ(it).singleOrNull() ?: return null
            }
            if (!succs.allSame()) {
                return null
            }
            val succ = succs[0]
            // The common successor must have no predecessors besides the defining blocks - otherwise `v` is
            // undefined on the path through the extra predecessor, even though every defining block dominates a
            // path into `succ`.
            if (g.pred(succ).size != defBlocks.size) {
                return null
            }
            for (usePtr in allUsages[v].orEmpty()) {
                defsByBlock[usePtr.block]
                    ?.let { defPtr ->
                        if (usePtr.pos <= defPtr.pos) {
                            return false
                        }
                    }
                    ?: run {
                        if (!dom.dominates(succ, usePtr.block)) {
                            return false
                        }
                    }
            }
            return true
        }

        val badDefForm = mutableSetOf<TACSymbol.Var>()
        val useBeforeDef = mutableSetOf<TACSymbol.Var>()
        allDefs.forEachEntry { (v, defs) ->
            when (classify(v, defs)) {
                null -> badDefForm += v
                false -> useBeforeDef += v
                true -> {}
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
