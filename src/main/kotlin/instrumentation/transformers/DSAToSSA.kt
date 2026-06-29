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

import analysis.CmdPointer
import analysis.maybeNarrow
import datastructures.MutableMultiMap
import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.asVarOrNull
import vc.gen.LeinoWP

/**
 * Performs partial SSA-ing - for maps only (Word, Byte, Ghost).
 *
 * After [TACDSA], non-map variables are already in DSA form (assigned at most once per path), but maps may still be
 * assigned in multiple predecessor blocks that all converge at a common successor. This pass merges those writes
 * into a single assignment in the successor block, using fresh per-predecessor reachability flags to pick which
 * write "won":
 *
 *     pred_i:  tmp_i = e_i               (omitted when e_i is already a plain variable; see below)
 *     succ:    m = ite(r_n, tmp_n, ite(r_{n-1}, tmp_{n-1}, ... tmp_1))
 *
 * where each `r_i` is a havoc-initialized Leino reachability variable for the i-th predecessor and `e_i` is the RHS
 * originally written there. When `e_i` is a non-trivial expression, the original `m = e_i` is rewritten in place to
 * `tmp_i = e_i` so that `e_i`'s free variables stay in their original block - moving `e_i` itself into `succ` would
 * break DSA whenever `e_i` references a variable defined on only some incoming paths. When `e_i` is already a plain
 * variable reference (`m = someVar`), no temp is introduced and the ITE references `someVar` directly. The Leino WP
 * generator is responsible for later constraining each `r_i` to actually reflect reachability of its block.
 *
 * Post-condition: the resulting program is in DSA modulo maps. Reach vars are also strictly DSA here (each is
 * havoced once and used once, both inside `succ`); subsequent passes must avoid moving the merge ITE across blocks
 * to preserve that. The pre-solver `DSAChecker.checkFinalDSA` catches violations.
 */
object DSAToSSA {
    fun rewrite(p: CoreTACProgram): CoreTACProgram {
        val g = p.analysisCache.graph

        // Group every map-typed assignment by its LHS, then keep only LHSs assigned more than once - these are the
        // maps that need to be SSA-merged.
        val multiMapWrites = g.commands
            .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>() }
            .filter { it.cmd.lhs.tag is Tag.Map }
            .groupBy(keySelector = { it.cmd.lhs }, valueTransform = { it })
            .filterValues { it.size > 1 }

        val patcher = p.toPatchingProgram()
        val blockPrefix = mutableMapOf<NBId, MutableList<TACCmd.Simple.AssigningCmd>>()

        // Reachability vars to havoc at the top of each successor block. Multiple maps may share predecessor blocks
        // (and thus the same reachability vars); the multimap's set dedupes naturally and we emit one havoc per
        // (succ, var) at the end. Emitting one per write would re-introduce a DSA violation on the reachability vars.
        val toHavoc: MutableMultiMap<NBId, TACSymbol.Var> = mutableMultiMapOf()

        multiMapWrites.forEach { (v, writes) ->
            // We expect all writes to `v` to live in distinct blocks that share a single common successor, and that
            // every predecessor of that successor writes to `v` - that's the diamond pattern produced by TACDSA. If
            // these invariants break, the merge below would be unsound: e.g. a non-writing predecessor of `succ` would
            // leave all reachability flags false at runtime, collapsing the ITE to the innermost arm incorrectly.
            val writeBlocks = writes.map { it.ptr.block }
            check(writeBlocks.toSet().size == writeBlocks.size)
            val succ = writeBlocks.flatMapToSet(g::succ).single()
            check(g.pred(succ) == writeBlocks.toSet())

            // For each write, record that its block's reachability variable needs to be havoced at the top of `succ`,
            // and decide what symbol the successor's ITE should reference for this predecessor:
            //  - If the write is already `v = someVar` (a plain variable RHS), the merged ITE can reference `someVar`
            //    directly and the original write becomes dead - just delete it.
            //  - Otherwise the RHS may reference variables defined on only one incoming path, so we replace the
            //    write `v = rhs` in place with `tmp_i = rhs` for a fresh `tmp_i` and use `tmp_i` in the ITE. This
            //    keeps `rhs`'s free vars confined to their original block.
            // The Leino WP pass will later add the constraint that ties each reachability var to its block actually
            // executing.
            val forms = writes.map { (ptr, cmd) ->
                val reachability = LeinoWP.genReachabilityVar(ptr.block)
                toHavoc.add(succ, reachability)
                val simpleSrc = cmd.rhs.asVarOrNull
                if (simpleSrc != null) {
                    patcher.delete(ptr)
                    reachability to simpleSrc
                } else {
                    val tmp = patcher.freshTemp(v.tag, "!dsaToSsa", v.callIndex)
                    patcher.update(ptr, cmd.copy(lhs = tmp))
                    reachability to tmp
                }
            }
            check(forms.size > 1)
            // Build a right-leaning ITE chain: the first write's tmp is the innermost "else", later writes wrap it
            // with `ite(reachability, tmp, accIte)`. If no reachability flag is true the value collapses to the first
            // write's tmp, but in a well-formed program exactly one flag will hold.
            val start = forms.first().second.asSym()
            val iteForm =
                forms.drop(1).fold<Pair<TACSymbol.Var, TACSymbol.Var>, TACExpr>(start) { accIte, (reachability, tmp) ->
                    TACExpr.TernaryExp.Ite(
                        i = reachability.asSym(),
                        t = tmp.asSym(),
                        e = accIte
                    )
                }
            blockPrefix.computeIfAbsent(succ) { mutableListOf() } +=
                TACCmd.Simple.AssigningCmd.AssignExpCmd(v, iteForm)
        }
        // Multiple maps can share the same successor block, so we accumulate all prefix commands per block and emit
        // them in one shot before the block's first existing command. Havocs come first so the ITE merges that follow
        // see the freshly-havoced reachability vars.
        for ((b, mergeCmds) in blockPrefix) {
            val havocs = toHavoc[b]?.map {
                patcher.addVarDecl(it)
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(it)
            }.orEmpty()
            patcher.addBefore(CmdPointer(b, 0), havocs + mergeCmds)
        }
        return patcher.toCode(p)
    }
}
