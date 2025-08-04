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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package wasm.transform

import algorithms.SimpleDominanceAnalysis
import analysis.CmdPointer
import analysis.DefiningEquationAnalysis
import analysis.PathCondition
import analysis.maybeNarrow
import datastructures.stdcollections.filterKeys
import datastructures.stdcollections.mapValues
import datastructures.stdcollections.mutableMapOf
import datastructures.stdcollections.mutableSetOf
import datastructures.stdcollections.singleOrNull
import tac.generation.assume
import utils.filterToSet
import utils.foldFirst
import utils.letIf
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.tacexprutil.getFreeVars
import kotlin.collections.iterator

/** Propagate preconditions necessary to avoid reverts. Looks for branches where
 *  one success *must* revert (i.e., is a revert block).
 *
 *  Assume the condition at the branch header is a predicate on a single variable, p(x), such that
 *  when p(x) holds, we definitely revert. This pass then inserts `assume !p(x)` after all the
 *  definitions of x that are post-dominated by the branch.
 *
 *  These specific formula shapes are handy because they're often the result of checking the discriminant
 *  of some Rust enum type.
 */
object PropagateRevertConditions {
    fun transform(ctp: CoreTACProgram): CoreTACProgram {
        // Build the reverse cfg graph, ignoring reverting blocks
        val noRevertPred = ctp.analysisCache.graph
            .toRevBlockGraph()
            .filterKeys { it !in ctp.analysisCache.revertBlocks }
            .mapValues { (_, vs) -> vs.filterToSet { it !in ctp.analysisCache.revertBlocks }}

        val dom = SimpleDominanceAnalysis(noRevertPred)
        val code = ctp.analysisCache.graph

        // where |-> e means that if e holds @ where, then we will definitely revert
        val toAnnotate = mutableMapOf<CmdPointer, MutableSet<TACExpr>>()

        for (b in code.blocks) {
            for ((succ, pc) in code.pathConditionsOf(b.id)) {
                if (succ !in code.cache.revertBlocks
                    || pc is PathCondition.TRUE  // Not interested in unconditional jumps
                    || pc is PathCondition.Summary) {
                    continue
                }

                val last = b.commands.last().maybeNarrow<TACCmd.Simple.JumpiCmd>() ?: continue
                val condVar = last.cmd.cond as? TACSymbol.Var ?: continue
                // Now we know that [b] is a branch where [succ] is a revert block

                // [condFormula] is the definition of our condition variable on entry to [b]
                val condFormula = DefiningEquationAnalysis.getDefiningEquation(
                    code,
                    condVar,
                    last.ptr,
                    b.commands.first().ptr
                ) ?: continue

                // We're looking for single variable definitions to constrain
                val fv = condFormula.getFreeVars().singleOrNull() ?: continue

                // If [noRevertCondition] is true then we won't revert
                val noRevertCondition = condFormula.letIf(pc is PathCondition.NonZero) {
                    TACExpr.UnaryExp.LNot(it)
                }

                for (defSite in code.cache.def.defSitesOf(fv, b.commands.first().ptr)) {
                    // If this jump post-dominates, then we can push the assumption to the def
                    if (dom.dominates(b.id, defSite.block))  {
                        toAnnotate.computeIfAbsent(defSite) {
                            mutableSetOf()
                        }.add(noRevertCondition)
                    }
                }
            }
        }

        return ctp.patching {
            for ((where, formulas) in toAnnotate) {
                val cmds = assume {
                    formulas.foldFirst { f1, f2 -> TACExpr.BinBoolOp.LAnd(f1, f2) }
                }
                it.insertAfter(where, cmds.cmds)
                it.addVarDecls(cmds.varDecls)
            }
        }
    }
}
