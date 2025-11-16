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

package verifier.equivalence.instrumentation

import analysis.CommandWithRequiredDecls
import analysis.dataflow.StrictDefAnalysis
import datastructures.stdcollections.*
import analysis.maybeNarrow
import evm.*
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import vc.data.tacexprutil.ExprUnfolder
import java.math.BigInteger

/**
 * The "dual" of the [SighashPackingNormalizer]; in Vyper the way that the compiler reads the sighash is very fun
 * and interesting; they copy *exactly* 4 bytes into memory starting at 0, then read the whole word, checking
 * this (big) constant against the expected sighash. Our model of copying fails miserably on this sub-word copy,
 * and instead of falling back on bounded precision cells, we special case this very common pattern.
 */
object SighashReadNormalizer {
    fun doWork(c: CoreTACProgram) : CoreTACProgram {
        val d = c.analysisCache.strictDef
        return c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf {
                it.cmd.srcBase == TACKeyword.CALLDATA.toVar() && it.cmd.dstBase == TACKeyword.MEMORY.toVar() &&
                    d.source(it.ptr, it.cmd.length) == StrictDefAnalysis.Source.Const(DEFAULT_SIGHASH_SIZE) ||
                    d.source(it.ptr, it.cmd.srcOffset) == StrictDefAnalysis.Source.Const(BigInteger.ZERO)
            }?.let { longCopyLC ->
                longCopyLC `to?` (d.source(longCopyLC.ptr, longCopyLC.cmd.dstOffset) as? StrictDefAnalysis.Source.Const)?.n
            } ?: it.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf {
                it.cmd.base == TACKeyword.MEMORY.toVar() && (it.cmd.value as? TACSymbol.Var)?.let { vS ->
                    c.analysisCache.gvn.equivBefore(it.ptr, vS).any {
                        it.meta[TACMeta.CALLDATA_OFFSET] == BigInteger.ZERO
                    }
                } == true
            }?.let { lc ->
                lc `to?` (d.source(lc.ptr, lc.cmd.loc) as? StrictDefAnalysis.Source.Const)?.n
            }
        }.filter { (_, offs) ->
            offs.mod(EVM_WORD_SIZE) + DEFAULT_SIGHASH_SIZE == EVM_WORD_SIZE
        }.patchForEach(c, check = true) { (where, offs) ->
            val tmp = TACKeyword.TMP(Tag.Bit256, "upperBits")
            val currContents = TACKeyword.TMP(Tag.Bit256, "currContents")
            val wordAligned = (offs - 0x1c.toBigInteger()).asTACSymbol()
            val sighashBitWidth = DEFAULT_SIGHASH_SIZE_INT * 8
            val repl = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.ByteLoad(
                    lhs = currContents,
                    base = TACKeyword.MEMORY.toVar(),
                    loc = wordAligned
                ),
                TACCmd.Simple.AssigningCmd.ByteLoad(
                    lhs = tmp,
                    base = TACKeyword.CALLDATA.toVar(),
                    loc = TACSymbol.Zero
                )
            ), setOf(tmp, currContents)) andThen ExprUnfolder.unfoldPlusOneCmd("blit", TACExprFactoryExtensions.run {
                (currContents bwAnd bwNot(MASK_SIZE(sighashBitWidth).asTACExpr)) add (tmp shiftRLog (EVM_BITWIDTH256 - sighashBitWidth))
            }) {
                TACCmd.Simple.AssigningCmd.ByteStore(
                    loc = wordAligned,
                    base = TACKeyword.MEMORY.toVar(),
                    value = it.s
                )
            }
            replaceCommand(where.ptr, repl)
        }
    }
}
