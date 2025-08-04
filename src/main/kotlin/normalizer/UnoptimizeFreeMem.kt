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

package normalizer

import datastructures.stdcollections.*
import analysis.*
import normalizer.UnoptimizeFreeMem.doWork
import tac.Tag
import vc.data.*
import java.math.BigInteger


/**
 * Cancels the effects of optimization on reading from the free memory pointer.
 * See [doWork].
 */
object UnoptimizeFreeMem {
    data class FreeMemPlusConst(
        val use: CmdPointer,
        val c: BigInteger,
        val origFreeMemDef: CmdPointer
    )

    private val patt = PatternDSL.build {
        (Var { v, where ->
            if(v == freememPtr) { PatternMatcher.VariableMatch.Match(where) } else { PatternMatcher.VariableMatch.Continue }
        } + Const).commute.withAction { loc, fpRead, c0 -> FreeMemPlusConst(loc.ptr, c0, fpRead.ptr) }
    }

    private val freememPtr = TACKeyword.MEM64.toVar()

    /**
     * Offsets based on tacM0x40 should be based on the latest tacM0x40, not some early incarnation.
     * Performs the following:
     * Given:
     * x0 = tacM0x40
     * y = tacM0x40 + c1
     * tacM0x40 = y
     * x1 = x0 + c0
     *
     * the last assignment becomes:
     * x1 = tacM0x40 + c0 - c1
     */
    fun doWork(c: CoreTACProgram): CoreTACProgram {

        val g = c.analysisCache.graph
        val matcher = PatternMatcher.compilePattern(g, patt)
        val gvn = c.analysisCache.gvn

        /**
         * Check that, at [candidateToRewrite].use, we have seen an update of the free pointer
         * `fp@new := c' + fp@old`, where `c'` > [candidateToRewrite].c and `fp@old` is the same
         * value read at [candidateToRewrite].origFreeMemDef, and that there exists an alias of `fp@new` at [candidateToRewrite].use.
         * NB that at [candidateToRewrite].use the free pointer may not actually equal `fp@new` due to yet more allocations.
         * However, using `fp@old` to access a field past the (known) end of an allocation struct is almost
         * certainly never right, so we rewrite accordingly.
         *
         * This returns a pair of c' and the alias of `fp@new` that is in scope at [candidateToRewrite].use
         */
        fun findRewriteOffset(candidateToRewrite: FreeMemPlusConst): Pair<BigInteger, TACSymbol.Var>? {
            if(candidateToRewrite.use.block != candidateToRewrite.origFreeMemDef.block ||
                candidateToRewrite.origFreeMemDef.pos >= candidateToRewrite.use.pos) {
                return null
            }
            for(lc in g.iterateBlock(candidateToRewrite.origFreeMemDef, excludeStart = true)) {
                if(lc.cmd !is TACCmd.Simple.AssigningCmd || lc.cmd.lhs != TACKeyword.MEM64.toVar()) {
                    continue
                }
                val m = matcher.queryFrom(lc.narrow()).toNullableResult() ?: return null
                if(m.origFreeMemDef != candidateToRewrite.origFreeMemDef) {
                    return null
                }
                val postFpUpdate = g.succ(lc.ptr).singleOrNull() ?: return null
                val which = gvn.findCopiesAt(candidateToRewrite.use, postFpUpdate to freememPtr)
                val newFpVal = lc.maybeExpr<TACExpr.Sym.Var>()?.exp?.s
                val base = which.firstOrNull {
                    it != freememPtr && newFpVal != it
                } ?: which.firstOrNull {
                    it != newFpVal
                } ?: return null
                if(candidateToRewrite.c <= m.c) {
                    return null
                }
                return m.c to base
            }
            return null
        }

        val patch = c.toPatchingProgram()

        g.commands.filter { cmd ->
            cmd.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs is TACExpr.Vec.Add
        }.forEach { cmd ->
            val fpPlusConst = matcher.queryFrom(cmd.narrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()).toNullableResult() ?: return@forEach
            val (rewriteC1, base) = findRewriteOffset(fpPlusConst) ?: return@forEach
            /**
             * If the alias we find to use here is the free pointer, make sure we generate a fresh read.
             * On the other hand, if we are using an existing alias of the free pointer (which is not mem64 itself)
             * avoid the spurious copy as it makes life harder for the PTA down the road
             */
            val (addBase, addBaseDef) = if(base == TACKeyword.MEM64.toVar()) {
                val newRead = TACKeyword.TMP(Tag.Bit256, "!freshRead")
                patch.addVarDecl(newRead)
                newRead to TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = newRead,
                    rhs = base
                )
            } else {
                base to null
            }
            val lhs = g.elab(fpPlusConst.use).narrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>().cmd.lhs
            patch.replaceCommand(
                fpPlusConst.use,
                listOfNotNull(
                    addBaseDef,
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs,
                        TACExpr.Vec.Add(addBase.asSym(), (fpPlusConst.c - rewriteC1).asTACSymbol().asSym())
                    )
                )
            )
        }

        return patch.toCode(c)
    }
}
