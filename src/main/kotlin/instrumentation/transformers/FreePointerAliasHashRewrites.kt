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

package instrumentation.transformers

import analysis.CmdPointer
import analysis.maybeNarrow
import analysis.opt.FreePointerReadFixupMixin
import utils.*
import vc.data.*

/**
 * Does the exact same thing as [FreePointerAliasLogRewrites]
 * but targets hashes.
 */
object FreePointerAliasHashRewrites : FreePointerReadFixupMixin<FreePointerAliasHashRewrites.Instr> {
    private data class Instr(
        val hashLoc: CmdPointer,
        override val fpAlias: TACSymbol.Var,
    ) : FreePointerReadFixupMixin.ReplacementCandidate {
        override val rewriteUseAfter: CmdPointer
            get() = hashLoc
    }


    fun rewrite(p: CoreTACProgram) : CoreTACProgram {
        val gvn = p.analysisCache.gvn
        val lva = p.analysisCache.lva
        return p.parallelLtacStream().mapNotNull { lc ->
            lc.ptr `to?` lc.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignSha3Cmd>()?.cmd?.op1.let {
                it as? TACSymbol.Var
            }?.takeIf { base ->
                base in gvn.equivBefore(lc.ptr, TACKeyword.MEM64.toVar()) &&
                    lva.isLiveAfter(lc.ptr, base)
            }
        }.map { (loc, alias) ->
            FreePointerAliasHashRewrites.Instr(
                hashLoc = loc,
                fpAlias = alias
            )
        // Reader, I'll level with you: why do we need this here and not in the log one? I have no idea...
        }.doRewrite(p, requireLiveAlias = true)
    }
}
