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

import analysis.*
import analysis.alloc.lower32BitMask
import datastructures.stdcollections.*
import analysis.dataflow.IGlobalValueNumbering
import evm.EVM_WORD_SIZE
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import vc.data.tacexprutil.ExprUnfolder
import java.math.BigInteger

/**
 * Recognizes the following:
 * ```
 * base = fp
 * fp = base + k + len * 32
 * t = base + k'
 * mem[t] = len
 * ```
 * where k' == k - 32. The above is actually a collapsed allocation
 * of an array and struct, the struct is of size k', the extra 32 bytes and
 * len * 32 are for the array sub-field.
 *
 * We rewrite this to:
 * ```
 * base = fp
 * fp = base + k'
 * t = base
 * fp = base + 32 + 32 * len
 * mem[t] = len
 * ...
 * ```
 *
 * We don't actually insist that the array being allocated is a field of the struct being allocated.
 * Pointer arithmetic on `base` that yield field pointers into the allocated array `t` are handled
 * in a subsequent pass: [normalizer.UnoptimizeFreeMem].
 */
object StructArrayAllocationDeoptimizer {
    private data class DeoptimizeCandidate(
        val fpReadLoc: CmdPointer,
        val structSize: BigInteger,
        val lengthSym: LTACVar,
        val elemSize: BigInteger
    )

    private val pattern = PatternDSL.build {
        commuteThree(
            TACKeyword.MEM64.toVar().withLocation,
            Const { it ->
                it > EVM_WORD_SIZE
            },
            (((Var.withLocation + 31()).commute.first / EVM_WORD_SIZE()).first * EVM_WORD_SIZE()).commute.first `^`
                ((Var.withLocation + 31()).commute.first and lower32BitMask()).commute.first,
            PatternDSL.CommutativeCombinator.add
        ) { fpReadLoc, structSize, lSym ->
            DeoptimizeCandidate(
                fpReadLoc = fpReadLoc,
                structSize = structSize,
                lengthSym = LTACVar(lSym.first, lSym.second),
                elemSize = BigInteger.ONE
            )
        } lor commuteThree(
            TACKeyword.MEM64.toVar().withLocation,
            Const { it ->
                it > EVM_WORD_SIZE
            },
            (Var.withLocation * EVM_WORD_SIZE()).commute.first,
            PatternDSL.CommutativeCombinator.add
        ) { fpReadLoc, structSize, lSym ->
            DeoptimizeCandidate(
                fpReadLoc = fpReadLoc,
                structSize = structSize,
                lengthSym = LTACVar(lSym.first, lSym.second),
                elemSize = EVM_WORD_SIZE
            )
        }
    }

    private val constFieldPattern = PatternDSL.build {
        (Var.withLocation + Const).commute.withAction { a, b ->
            LTACVar(a) to b
        }
    }

    private fun IGlobalValueNumbering.equivalent(a: LTACVar, b: LTACVar) = a.v in this.findCopiesAt(a.ptr, b.ptr to b.v)

    private data class RewriteCand(
        val fpWritePoint: CmdPointer,
        val lengthWritePoint: CmdPointer,
        val structSize: BigInteger,
        val arrayBase: Set<TACSymbol.Var>,
        val structBase: TACSymbol.Var,
        val lenSym: TACSymbol.Var,
        val elemSize: BigInteger
    )

    fun rewrite(c: CoreTACProgram) : CoreTACProgram {
        val graph = c.analysisCache.graph
        val matcher = PatternMatcher.compilePattern(graph, pattern)

        val bumpMatcher = PatternMatcher.compilePattern(graph, constFieldPattern)
        val gvn = graph.cache.gvn
        val lva = graph.cache.lva
        return c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd>()
        }.filter {
            it.cmd.lhs == TACKeyword.MEM64.toVar()
        }.mapNotNull {
            it `to?` matcher.queryFrom(it).toNullableResult()
        }.mapNotNull { (write, opt) ->
            /**
             * So we have an allocation whose size is len * 32 + k where k > 32.
             *
             * Do we see a tell-tale write of len into memory at field k - 32?
             */
            for(lc in graph.iterateBlock(write.ptr, excludeStart = true)) {
                if(lc.cmd !is TACCmd.Simple.AssigningCmd.ByteStore ||
                    lc.cmd.value !is TACSymbol.Var ||
                    lc.cmd.loc !is TACSymbol.Var ||
                    lc.cmd.base != TACKeyword.MEMORY.toVar()) {
                    continue
                }
                val writeLen = LTACVar(lc.ptr, lc.cmd.value)
                // value written is not equivalent to the length, never mind
                if(!gvn.equivalent(writeLen, opt.lengthSym)) {
                    continue
                }
                // what field are we writing do (writeOffs) and what is our base pointer
                val (writeBase, writeOffs) = bumpMatcher.query(lc.cmd.loc, lc).toNullableResult() ?: return@mapNotNull null
                // field is not k - 32, bail
                if(writeOffs.mod(EVM_WORD_SIZE) != BigInteger.ZERO || writeOffs != opt.structSize - EVM_WORD_SIZE) {
                    return@mapNotNull null
                }
                val structBaseRead = LTACVar(opt.fpReadLoc, TACKeyword.MEM64.toVar())
                // base pointer is the base of the struct we think we allocated, bail
                if(!gvn.equivalent(writeBase, structBaseRead)) {
                    return@mapNotNull null
                }
                // find all variables that alias the array start, they need to be rewritten
                val arrayBaseAliases = gvn.equivBefore(lc.ptr, lc.cmd.loc).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // find the struct base (the variable that holds the value of the free pointer before updating).
                // we will use this to compute the new end point for the struct.
                val structBase = gvn.findCopiesAt(lc.ptr, structBaseRead.ptr to structBaseRead.v).firstOrNull { structBase ->
                    lva.isLiveBefore(lc.ptr, structBase)
                } ?: return@mapNotNull null
                return@mapNotNull RewriteCand(
                    fpWritePoint = write.ptr,
                    structSize = opt.structSize - EVM_WORD_SIZE,
                    arrayBase = arrayBaseAliases,
                    structBase = structBase,
                    lengthWritePoint = lc.ptr,
                    lenSym = lc.cmd.value,
                    elemSize = opt.elemSize
                )
            }
            null
        }.patchForEach(c, check = true) { r ->
            val synthEnd = TACKeyword.TMP(Tag.Bit256, "structEnd")
            addVarDecl(synthEnd)
            val baseAliases = r.arrayBase.toList()

            /**
             * Replace the original allocation with just `base + (k - 32)`
             */
            val toReplace = listOf<TACCmd.Simple>(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = synthEnd,
                    rhs = TACExpr.Vec.Add(r.structBase.asSym(), r.structSize.asTACExpr)
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = TACKeyword.MEM64.toVar(),
                    rhs = synthEnd.asSym()
                )
            )
            replaceCommand(r.fpWritePoint, toReplace)

            /**
             * Now set `t = fp` and create assignments for all its aliases (if they exist).
             *
             * This will make any previous definitions of `t` dead, to be cleaned up by one of the
             * many, many dead code elimination passes that will run later.
             */
            val toAdd = mutableListOf<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = baseAliases[0],
                rhs = TACKeyword.MEM64.toVar()
            ))
            for(other in baseAliases.subList(1, baseAliases.size)) {
                toAdd.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = other,
                    rhs = baseAliases[0].asSym()
                ))
            }
            /**
             * Now synthesize an update for the free pointer to hold the array.
             */
            val prefix = CommandWithRequiredDecls(toAdd) andThen ExprUnfolder.unfoldPlusOneCmd("fpUpdate", TXF {
                if(r.elemSize == EVM_WORD_SIZE) {
                    ((r.lenSym mul EVM_WORD_SIZE) add EVM_WORD_SIZE) add baseAliases[0]
                } else {
                    (((r.lenSym add 31) bwAnd lower32BitMask) add EVM_WORD_SIZE) add baseAliases[0]
                }
            }) {
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = TACKeyword.MEM64.toVar(),
                    rhs = it
                )
            }
            /**
             * Keep the original length update, so add before, not replace
             */
            addBefore(r.lengthWritePoint, prefix)
        }
    }
}
