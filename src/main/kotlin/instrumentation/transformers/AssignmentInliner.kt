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
import datastructures.MutableReversibleMap
import datastructures.stdcollections.*
import tac.NBId
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.tacexprutil.asSym
import vc.data.tacexprutil.isConst
import vc.data.tacexprutil.isSym
import vc.data.tacexprutil.subs

/**
 * Removes loop assignments `x : = x`
 * Inlines simple assignments `b := a; c := b + 1` => `b := a; c := a + 1`.
 *
 * If [filteringFunctions] of a variable is false, it is never replaced, and if [strict] is true, then it
 * also never replaces other variables.
 *
 * For reasons that are not yet clear, we need [strict] to also imply that we consider only intra-block replacements.
 * Without it, `WeeklyTests/MakerDAO/DAITeleport/TeleportOracleAuth/TeleportOracleAuth.conf` rule `requestMint_revert`
 * fails instead of being verified.
 */
class AssignmentInliner(
    private val code: CoreTACProgram,
    private val filteringFunctions: FilteringFunctions,
    private val strict: Boolean,
) {
    private val g = code.analysisCache.graph
    private val fullDef by lazy { code.analysisCache.strictDef }
    private val patcher = code.toPatchingProgram()

    private fun go(): CoreTACProgram {
        process()
        return patcher.toCodeNoTypeCheck(code)
    }

    private val canBeReplacedWith = MutableReversibleMap<CmdPointer, TACSymbol>()

    private fun process() {
        code.topoSortFw.forEach(::processBlock)
    }

    private fun processBlock(b: NBId) {
        val blockDef = mutableMapOf<TACSymbol.Var, CmdPointer>()

        val quantifiedVars = buildSet {
            g.lcmdSequence(b).map { it.cmd }.filterIsInstance<AssignExpCmd>().forEach {
                it.rhs.subs.filterIsInstance<TACExpr.QuantifiedFormula>().forEach {
                    addAll(it.quantifiedVars)
                }
            }
        }
        g.lcmdSequence(b).forEach { (ptr, cmd) ->
            fun def(ptr: CmdPointer, v: TACSymbol.Var): Set<CmdPointer?>? =
                blockDef[v]
                    ?.let { setOf(it) }
                    ?: runIf(!strict) {
                        fullDef.defSitesOf(v, ptr)
                    }

            val mapper = object : DefaultTACCmdMapper() {
                override fun mapSymbol(t: TACSymbol) =
                    when (t) {
                        is TACSymbol.Const -> t
                        is TACSymbol.Var ->
                            def(ptr, t)
                                ?.map { canBeReplacedWith[it] }
                                ?.sameValueOrNull()
                                ?.let {
                                    if (it is TACSymbol.Var) {
                                        // We keep the meta of the inlined variable, throwing away that of the
                                        // original rhs var.
                                        it.withMeta(::mapMeta)
                                    } else {
                                        it
                                    }
                                }
                                ?: t
                    }

                override fun mapVar(t: TACSymbol.Var) =
                    if (t in quantifiedVars) {
                        t
                    } else {
                        mapSymbol(t) as? TACSymbol.Var ?: t
                    }

                /** don't map the lhs */
                override fun mapLhs(t: TACSymbol.Var) = t

                /** can't map `base`, because it's also the implicit lhs */
                override fun mapByteStore(t: TACCmd.Simple.AssigningCmd.ByteStore) =
                    t.copy(loc = mapSymbol(t.loc), value = mapSymbol(t.value), meta = mapMeta(t.meta))
            }

            // why only these?
            // possibly because other commands have metas attached to variables on the rhs, and inlining other variables
            // will erase these metas.
            val newCmd = when (cmd) {
                is AssignExpCmd,
                is TACCmd.Simple.AssigningCmd.ByteLoad,
                is TACCmd.Simple.AssigningCmd.ByteStore,
                is TACCmd.Simple.ByteLongCopy ->
                    mapper.map(cmd)

                else -> null
            }


            cmd.getModifiedVar()?.let {
                blockDef[it] = ptr
                // lhs can't be used as a replacement now.
                canBeReplacedWith.removeValue(it)
            }

            if (newCmd is AssignExpCmd && newCmd.rhs.isSym) {
                val rhs = newCmd.rhs.asSym
                val lhs = (cmd as AssignExpCmd).lhs

                // remove `a := a`
                if (rhs == lhs && filteringFunctions.isErasable(cmd)) {
                    patcher.delete(ptr)
                    return
                }

                // update the replacement map
                if (filteringFunctions.isInlineable(lhs) &&
                    (!strict || rhs.isConst || filteringFunctions.isInlineable(rhs as TACSymbol.Var))
                ) {
                    canBeReplacedWith[ptr] = rhs
                }
            }

            if (newCmd != null && newCmd != cmd) {
                patcher.update(ptr, newCmd)
            }
        }
    }


    companion object {
        fun inlineAssignments(
            code: CoreTACProgram,
            filteringFunctions: FilteringFunctions,
            strict: Boolean,
        ) =
            AssignmentInliner(code, filteringFunctions, strict).go()
    }
}



