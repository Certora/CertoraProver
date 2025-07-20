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

package normalizer

import algorithms.dominates
import analysis.enarrow
import analysis.maybeExpr
import analysis.maybeNarrow
import config.Config
import evm.EVM_WORD_SIZE
import evm.EVM_WORD_SIZE_INT
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.stream.Collectors
import datastructures.stdcollections.*
import tac.MetaKey
import tac.Tag
import vc.data.TACProgramCombiners.andThen
import vc.data.tacexprutil.ExprUnfolder

/**
 * Optimistically rewrites suspected write-once spill locations to skip memory.
 * In particular, we look for a case where tacM0x40 is allocated to some constant K > 0x80 (heuristically
 * indicating that there are spill locations).
 *
 * Let S be the set of word-align values between 0x80 and K (exclusive). These are suspected spill locations.
 * We look for *write-once* spill locations by finding all s \in S where:
 * 1. a write to s occurs exactly once in the program
 * 2. all reads from s are dominated by the write identified in 1
 *
 * If this is the case, we save the value written in 1 to a scalar R and instrument all reads found in 2 to
 * simply read R instead. However, we keep the original read around to *validate* this rewrite, that is,
 * we insert an assertion that: `tacM[s] == R`. If this assertion fails, the user is advised to turn
 * this optimistic flag.
 *
 * This can be helpful to work around analysis failures due to solidity spilling values that are expected to be in stack locations.
 */
object OptimisticSpillRewriter {
    val VALIDATION_READ = MetaKey.Nothing("tac.spill.validation-read")
    val SPILL_VARIABLE = MetaKey.Nothing("tac.spill.value-holder")

    fun rewrite(c: CoreTACProgram) : CoreTACProgram {
        val mem64Writes = c.parallelLtacStream().mapNotNull { lc ->
            lc.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.takeIf { n ->
                n.cmd.lhs == TACKeyword.MEM64.toVar()
            }
        }.toList()

        /**
         * Find the suspected FP initialization (i.e., a write of a constant); there must be exactly one
         * or we conservatively abort the rewrite.
         */
        val initializationWrite = mem64Writes.singleOrNull { lc ->
            lc.wrapped.maybeExpr<TACExpr.Sym.Const>() != null
        }?.wrapped?.enarrow<TACExpr.Sym.Const>() ?: return c

        /**
         * If this isn't a write to a word aligned constant > 0x80, we probably aren't looking at a spill location init.
         */
        if(initializationWrite.exp.s.value <= 0x80.toBigInteger() || initializationWrite.exp.s.value.mod(EVM_WORD_SIZE) != BigInteger.ZERO || !initializationWrite.exp.s.value.isInt()) {
            return c
        }

        val dom = c.analysisCache.domination

        /**
         * Sanity check: all later writes of the free pointer must come after this initialization.
         */
        if(!mem64Writes.all { lc ->
            val writeLoc = lc.ptr
            writeLoc == initializationWrite.ptr || dom.dominates(initializationWrite.ptr, writeLoc)
        }) {
            return c
        }

        /**
         * Find those cells in S.
         */
        val candidateCells = (0x80 ..< initializationWrite.exp.s.value.intValueExact()).step(EVM_WORD_SIZE_INT).mapToSet {
            it.toBigInteger()
        }

        /**
         * Find `w`, the singleton write to candidate cells
         */
        val withSingletonInit = c.parallelLtacStream().mapNotNull { lc ->
            lc.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()
        }.filter {
            val cmd = it.cmd
            cmd.base == TACKeyword.MEMORY.toVar() && cmd.loc is TACSymbol.Const && cmd.loc.value in candidateCells
        }.collect(Collectors.groupingBy { (it.cmd.loc as TACSymbol.Const).value }).filter { (_, v) ->
            // this is actually responsible for making sure there is one such write
            v.size == 1
        }
        if(withSingletonInit.isEmpty()) {
            return c
        }

        val patch = c.toPatchingProgram()

        /**
         * here spill is `s`, an optimistic spill candidate, and initWriteL is the singleton list
         * containing w (the single write)
         */
        for((spill, initWriteL) in withSingletonInit) {
            val initWrite = initWriteL.single()
            /*
             * find all of the reads from s
             */
            val toRewrite = c.parallelLtacStream().mapNotNull {
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteLoad>()
            }.filter {
                it.cmd.base == TACKeyword.MEMORY.toVar() && (it.cmd.loc as? TACSymbol.Const)?.value == spill
            }.toList()
            /**
             * If w doesn't dominate all our reads, bail out, this isn't a safe rewrite
             */
            if(!toRewrite.all { read ->
                dom.dominates(initWrite.ptr, read.ptr)
            } || toRewrite.isEmpty()) {
                continue
            }
            /**
             * instrument the write w to save the value being written to the spill location in the variable `holdsValue`
             */
            val holdsValue = TACSymbol.Var("spillScalar!$spill", initWrite.cmd.value.tag).toUnique("!").withMeta(
                SPILL_VARIABLE
            )
            patch.addBefore(initWrite.ptr, listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = holdsValue,
                    rhs = initWrite.cmd.value
                )
            ))
            patch.addVarDecl(holdsValue)

            for(r in toRewrite) {
                /**
                 * Now instrument all the read sites:
                 * `v = tacM[s]`
                 * change it to:
                 * ```
                 * t = tacM[s]
                 * assert t == holdsValue
                 * v = holdsValue
                 * ```
                 *
                 * NB that we have moved the definition of `v` from the read from memory to the copy of `holdsValue`.
                 */
                val tempRead = TACKeyword.TMP(Tag.Bit256, "optimisticReadValidate").toUnique("!")
                patch.addVarDecl(tempRead)

                val rewrittenCmd = r.cmd.copy(
                    lhs = tempRead
                ).plusMeta(VALIDATION_READ)
                val replacement = rewrittenCmd andThen ExprUnfolder.unfoldPlusOneCmd("validation", TXF { holdsValue eq tempRead }) {
                    TACCmd.Simple.AssertCmd(
                        o = it.s,
                        description = "Optimistic scalarization of spill location $spill in ${c.name} failed; please disable optimistic scalarization with the ${Config.EnableOptimisticSpillLocations.option.opt}"
                    )
                } andThen TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = r.cmd.lhs,
                    rhs = holdsValue.asSym()
                )

                patch.replaceCommand(r.ptr, replacement)
            }
        }
        return patch.toCode(c)
    }
}
