/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

import algorithms.strictlyDominates
import analysis.CmdPointer
import analysis.dataflow.BranchAwareGlobalValueNumbering
import analysis.dataflow.ValueNumber
import analysis.opt.ConstantPropagatorAndSimplifier.Companion.simplifyTop
import datastructures.stdcollections.*
import tac.NBId
import tac.Tag
import vc.data.*
import vc.data.tacexprutil.rebuild
import verifier.PatchingProgramWrapper

/**
 * Writes back what [BranchAwareGlobalValueNumbering] proved, three ways:
 *  - A definition, or any part of one, whose value number is a constant becomes that constant. The interesting
 *    constants are the ones only the numbering can see: an equality between two diamonds computing the same thing
 *    folds to `true` because both sides canonicalize to the same number.
 *  - A branch whose condition is a constant becomes a jump, which leaves the arm it never takes unreachable, so the
 *    patcher drops it along with everything only it reached.
 *  - A definition whose value a strictly dominating definition of the same tag already holds becomes a copy of it.
 *
 * The first two need no availability check - a constant is available everywhere, and a branch is decided where it
 * stands. The third is the only one that has to prove domination.
 *
 * Runs where the numbering does, on a program in DSA form, and leaves everything else alone.
 */
class BranchAwareSimplifier private constructor(code: CoreTACProgram) {

    companion object {
        fun rewrite(code: CoreTACProgram) = BranchAwareSimplifier(code).rewrite()
    }

    private val gvn = BranchAwareGlobalValueNumbering(code)
    private val g = code.analysisCache.graph
    private val dom = code.analysisCache.domination
    private val patcher = PatchingProgramWrapper(code)

    /**
     * The constant the numbering proved [e] to be, if it proved one. Only a constant is worth asking for,
     * being the one kind of [ValueNumber] that is also an expression of this program.
     */
    private fun constOf(e: TACExpr, block: NBId) = gvn.valueNumberOf(e, block) as? TACExpr.Sym.Const

    /** [s] as the constant it is known to be, where it is read. */
    private fun constOf(s: TACSymbol, block: NBId) =
        (s as? TACSymbol.Var)?.let { gvn.valueNumberOf(it, block) as? TACExpr.Sym.Const }?.s

    /**
     * [e] rewritten as far as the numbering allows: any subexpression it proved constant becomes that
     * constant, and what is left is rebuilt from folded operands and simplified on the way up. Asking at every
     * node is what catches a partial fold, where the whole expression is not constant but a part of it is.
     * Only constants are ever substituted, so the result is an expression of this program.
     */
    private fun folded(e: TACExpr, block: NBId): TACExpr =
        constOf(e, block) ?: when (e) {
            is TACExpr.Sym -> e
            else -> simplifyTop(e.rebuild { folded(it, block) })
        }

    /**
     * A variable already holding the value [vn] and of the same [tag], defined where [ptr] can read it, so the
     * computation at [ptr] can be that variable instead. Under DSA the definitions readable at a point are its
     * dominators, which are totally ordered, so asking for one that dominates is asking for everything there is.
     *
     * The tag has to match even though the value does: an `int` and a `bv256` variable can hold the same
     * number, and assigning one to the other does not type check.
     */
    private fun availableEqual(ptr: CmdPointer, vn: ValueNumber, tag: Tag): TACSymbol.Var? =
        gvn.vnOfDef.keysOf(vn)
            .mapNotNull { defPtr -> g.elab(defPtr).cmd.getLhs()?.takeIf { it.tag == tag }?.to(defPtr) }
            .firstOrNull { (_, defPtr) -> dom.strictlyDominates(defPtr, ptr) }
            ?.first

    /** Puts a known constant wherever a variable holding it is read, in commands of every other kind. */
    private fun mapperAt(block: NBId) = object : DefaultTACCmdMapper() {
        override fun mapSymbol(t: TACSymbol) = constOf(t, block) ?: t
    }

    private fun replaceIfChanged(ptr: CmdPointer, old: TACCmd.Simple, new: TACCmd.Simple) {
        if (new != old) {
            patcher.replace(ptr, new)
        }
    }

    fun rewrite(): CoreTACProgram {
        for (block in gvn.facts.keys) {
            val mapper = mapperAt(block)
            for ((ptr, cmd) in g.lcmdSequence(block)) {
                when (cmd) {
                    // a constant first, then a variable already holding this value, and failing both
                    // whatever folding the right-hand side on its own achieved. An annotation is
                    // value-transparent, so it is put back around whatever the rewrite chose: it is
                    // reporting data, and detaching it from the definition it describes loses a call trace.
                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                        val annotation = cmd.rhs as? TACExpr.AnnotationExp<*>
                        val core = annotation?.o ?: cmd.rhs
                        val folded = folded(core, block)
                        val newCore = folded as? TACExpr.Sym.Const
                            ?: gvn.vnOfDef[ptr]?.let { availableEqual(ptr, it, cmd.lhs.tag) }?.asSym()
                            ?: folded
                        val rhs = annotation?.copy(o = newCore) ?: newCore
                        replaceIfChanged(ptr, cmd, cmd.copy(rhs = rhs))
                    }

                    is TACCmd.Simple.AssumeExpCmd ->
                        replaceIfChanged(ptr, cmd, cmd.copy(cond = folded(cmd.cond, block)))

                    is TACCmd.Simple.JumpiCmd -> {
                        val live = gvn.liveSuccs(block).singleOrNull()
                        if (live != null) {
                            patcher.jumpiTojump(block, live)
                        } else {
                            replaceIfChanged(ptr, cmd, mapper.map(cmd))
                        }
                    }

                    // inlining a constant into these would erase the metas they carry
                    is TACCmd.Simple.StorageAccessCmd, is TACCmd.Simple.AnnotationCmd -> Unit

                    else -> replaceIfChanged(ptr, cmd, mapper.map(cmd))
                }
            }
        }
        return patcher.toCode(handleLeinoVars = true)
    }
}
