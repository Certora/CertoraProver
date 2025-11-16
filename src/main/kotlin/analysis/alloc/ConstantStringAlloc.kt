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

package analysis.alloc

import analysis.*
import utils.*
import vc.data.*
import java.math.BigInteger

object ConstantStringAlloc {
    private val offsetPattern = PatternDSL.build {
        (0x20() + !TACKeyword.MEM64.toVar()).commute.withAction { w, _, _ -> w.enarrow<TACExpr.Vec.Add>() }
    }

    private val fpIncrement = PatternDSL.build {
        (Const + !TACKeyword.MEM64.toVar()).commute.withAction { w, c, _ ->
            w.enarrow<TACExpr.Vec.Add>() to c
        }
    }

    private class ConstantStringAllocWorker(val g: TACCommandGraph) {
        private val constantPattern by lazy {
            PatternMatcher.compilePattern(g, PatternDSL.build { Const })
        }

        fun getAsConstant(c: TACSymbol, where: LTACCmd) : BigInteger? {
            return when(c) {
                is TACSymbol.Const -> c.value
                is TACSymbol.Var -> when(val r = constantPattern.query(c, where)) {
                    is PatternMatcher.ConstLattice.Match -> r.data
                    is PatternMatcher.ConstLattice.NoMatch -> null
                }
            }
        }

        fun compute(): Map<CmdPointer, AllocationAnalysis.Alloc.ConstantStringAlloc> {
            val toRet = mutableMapOf<CmdPointer, AllocationAnalysis.Alloc.ConstantStringAlloc>()
            val offsetMatcher by lazy {
                PatternMatcher.compilePattern(g, offsetPattern)
            }
            val fpUpdateMatcher by lazy {
                PatternMatcher.compilePattern(g, fpIncrement)
            }
            val longCopies = g.commands.mapNotNull {
                if (it.cmd is TACCmd.Simple.ByteLongCopy) {
                    val x = it.narrow<TACCmd.Simple.ByteLongCopy>()
                    if (x.cmd.srcBase.meta.contains(TACMeta.CODEDATA_KEY) && x.cmd.dstOffset is TACSymbol.Var && x.cmd.dstBase == TACKeyword.MEMORY.toVar()) {
                        x
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            for(p in longCopies) {
                val m = offsetMatcher.query(p.cmd.dstOffset as TACSymbol.Var, p.wrapped)
                if(m !is PatternMatcher.ConstLattice.Match) {
                    continue
                }
                val offsetCmd = m.data
                val len = getAsConstant(p.cmd.length, p.wrapped)
                val offset = getAsConstant(p.cmd.srcOffset, p.wrapped)
                if(len == null || offset == null) {
                    continue
                }
                val predChain = g.iterateUntil(p.wrapped.ptr).reversed()
                // find the first write
                val firstWrite = predChain.firstMapped {
                    if(it.cmd is TACCmd.Simple.AssigningCmd.ByteStore && it.cmd.base == TACKeyword.MEMORY.toVar() && it.cmd.loc is TACSymbol.Var) {
                        it.narrow<TACCmd.Simple.AssigningCmd.ByteStore>()
                    } else {
                        null
                    }
                } ?: continue
                val (fpWrite, tmp) = predChain.firstMapped {
                    if(it.cmd is TACCmd.Simple.AssigningCmd && it.cmd.lhs == TACKeyword.MEM64.toVar()) {
                        when(val r = fpUpdateMatcher.queryFrom(it.narrow())) {
                            is PatternMatcher.ConstLattice.Match -> it to r.data
                            is PatternMatcher.ConstLattice.NoMatch -> null
                        }
                    } else {
                        null
                    }
                } ?: continue
                val (fpAddition, amt) = tmp
                /*
                  Here's how we detect a constant string alloc:
                  1) the bytes starting at the offset 0x20 are initialized by copying from a constant index into codedata
                  2) The first 32 bytes are initialized with the number of bytes copied above
                  3) The entire block is allocated to be size 32 + copy_data.length rounded up to the nearested multiple of 32
                 */
                val basePointer = offsetCmd.exp.ls.mapNotNull {
                    it as? TACExpr.Sym.Var
                }.singleOrNull()?.s ?: continue
                if(firstWrite.cmd.loc !in g.cache.gvn.findCopiesAt(firstWrite.ptr, offsetCmd.ptr to basePointer)) {
                    // the first write is not to the base pointer (condition 1)
                    continue
                }
                // now check the the base pointer is the same one being added to in the fpUpdate (condition 1)
                val fpUpdatePointer = fpAddition.exp.ls.mapNotNull {
                    it as? TACExpr.Sym.Var
                }.singleOrNull()?.s ?: continue
                if(fpUpdatePointer !in g.cache.gvn.findCopiesAt(fpAddition.ptr, offsetCmd.ptr to basePointer)) {
                    continue
                }
                val lenFieldValue = getAsConstant(firstWrite.cmd.value, firstWrite.wrapped) ?: continue
                // the number of bytes copied does not match, rip (condition 2)
                if(lenFieldValue != len) {
                    continue
                }
                val lenFieldAndStringBytes = len + 32.toBigInteger()
                val roundedUp = (lenFieldAndStringBytes + 31.toBigInteger()).andNot(31.toBigInteger())
                // condition 3
                if(roundedUp != amt) {
                    continue
                }
                toRet.put(fpWrite.ptr, AllocationAnalysis.Alloc.ConstantStringAlloc(
                        constLen = len,
                        dataCopy = p.ptr
                ))
            }
            return toRet
        }
    }

    private val PatternDSL.fpReadFinder: PatternDSL.PatternBuilder<CmdPointer>
        get() {
            return Var { v, where ->
                if (v == TACKeyword.MEM64.toVar()) {
                    PatternMatcher.VariableMatch.Match(where.ptr)
                } else {
                    PatternMatcher.VariableMatch.Continue
                }
            }
        }

    private val indexPattern = PatternDSL.build {
        (0x20() + fpReadFinder).commute.second
    }

    private val smallStringAllocPattern = PatternDSL.build {
        (0x40() + fpReadFinder).commute.second
    }

    /*
    This will not work for solc4. Ask me if I care.
     */
    class ShortConstantStringAllocWorker(private val graph: TACCommandGraph) {
        private val indexPatternWitNull by lazy {
            PatternMatcher.Pattern.RecursivePattern<CmdPointer> { rec ->
                PatternDSL.build {
                    (0x0() + rec.asBuildable()).commute.second `^` indexPattern.asBuildable()
                }
            }
        }

        private val indexMatcher by lazy {
            PatternMatcher.compilePattern(graph, indexPatternWitNull)
        }
        private val fpAllocMatcher by lazy {
            PatternMatcher.compilePattern(graph, smallStringAllocPattern)
        }

        fun compute() : Map<CmdPointer, AllocationAnalysis.Alloc.ConstantStringAlloc> {
            val toRet = mutableMapOf<CmdPointer, AllocationAnalysis.Alloc.ConstantStringAlloc>()
            for(blk in graph.blocks) {
                outer@for(lc in blk.commands) {
                    if(lc.cmd !is TACCmd.Simple.AssigningCmd.ByteStore) {
                        continue
                    }
                    val value = lc.cmd.value
                    if(value !is TACSymbol.Const) {
                        continue
                    }
                    if(lc.cmd.base != TACKeyword.MEMORY.toVar()) {
                        continue
                    }
                    if(lc.cmd.loc !is TACSymbol.Var) {
                        continue
                    }
                    val constantCandidate = value.value
                    val x = constantCandidate.shiftRight(256-8)
                    if(x == BigInteger.ZERO) {
                        continue
                    }
                    // okay, we have a write of a big constant number with non-zero upper byte. Let's try to do the string pattern

                    val prevWrite = graph.iterateUntil(lc.ptr).reversed().firstOrNull {
                        it.cmd is TACCmd.Simple.AssigningCmd.ByteStore
                    }?.narrow<TACCmd.Simple.AssigningCmd.ByteStore>() ?: continue
                    if(prevWrite.cmd.value !is TACSymbol.Const || prevWrite.cmd.base != TACKeyword.MEMORY.toVar()) {
                        continue
                    }
                    val len = prevWrite.cmd.value as TACSymbol.Const
                    if(len.value > 32.toBigInteger()) {
                        continue@outer
                    }
                    for(i in prevWrite.ptr.pos..lc.ptr.pos) {
                        val intervening = blk.commands[i].cmd
                        if(intervening is TACCmd.Simple.AssigningCmd && intervening.lhs == TACKeyword.MEM64.toVar()) {
                            continue@outer
                        }
                    }
                    val fpRead = indexMatcher.query(lc.cmd.loc, lc).toNullableResult() ?: continue@outer
                    val prevFpUpdate = graph.iterateUntil(prevWrite.ptr).reversed().firstOrNull {
                        it.cmd is TACCmd.Simple.AssigningCmd && it.cmd.lhs == TACKeyword.MEM64.toVar()
                    }?.narrow<TACCmd.Simple.AssigningCmd>() ?: continue@outer
                    val updateMatch = fpAllocMatcher.queryFrom(prevFpUpdate).toNullableResult() ?: continue@outer
                    if(updateMatch != fpRead) {
                        continue@outer
                    }

                    if(prevWrite.cmd.loc !in graph.cache.gvn.findCopiesAt(prevWrite.ptr, fpRead to TACKeyword.MEM64.toVar())) {
                        continue@outer
                    }

                    /* okay, everything matches up. Let's see if the first n bytes (as determined by the length) are
                    valid utf characters, and that the remaining bytes are all 0...
                     */
                    val numBytes = len.value.toInt()
                    var it = 0

                    fun getNextByte(): Int {
                        val shiftBy = 256 - ((it + 1) * 8)
                        it++
                        val byte = (constantCandidate.shiftRight(shiftBy) and (255.toBigInteger())).toInt()
                        return byte
                    }
                    while(it < numBytes) {
                        val byte = getNextByte()
                        if(byte <= 127) {
                            continue
                        }
                        val numSuccBytes = if(byte >= 0b11000000 && byte < 0b11100000) {
                            1
                        } else if(byte >= 0b11100000 && byte < 0b11110000) {
                            2
                        } else {
                            3
                        }
                        for(i in 0 until numSuccBytes) {
                            // not enough bytes left!
                            if(it >= numBytes) {
                                continue@outer
                            }
                            val succByte = getNextByte()
                            if (succByte <= 0b10000000 || succByte > 0b10111111) {
                                continue@outer
                            }
                        }
                    }
                    while(it < 32) {
                        val nxt = getNextByte()
                        if(nxt != 0) {
                            continue@outer
                        }
                    }
                    // we found one!
                    toRet[prevFpUpdate.ptr] = AllocationAnalysis.Alloc.ConstantStringAlloc(
                            dataCopy = lc.ptr,
                            constLen = len.value
                    )
                }
            }
            return toRet
        }
    }

    fun findConstantStringAlloc(g: TACCommandGraph) = ConstantStringAllocWorker(g).compute() + ShortConstantStringAllocWorker(g).compute()
}
