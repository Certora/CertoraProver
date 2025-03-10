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

package analysis.controlflow

import algorithms.SimpleDominanceAnalysis
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import report.dumps.generateCodeMap
import statistics.data.PathCountStats
import statistics.data.PathCountStats.PathCountData
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.TacMockGraphBuilder
import java.math.BigInteger
import kotlin.collections.filter
import kotlin.collections.setOf

internal class PathCounterTest : TACBuilderAuxiliaries() {

    @Test
    fun countOneDiamond() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump {
                a assign 1
                jump(1) {
                    a assign 3
                }
            }
            jump {
                a assign 2
                jump(1)
            }
        }.code
        val analysis = PathCounter(prog.analysisCache.graph)
        assertEquals(BigInteger.TWO, analysis.pathCounts[prog.entryBlockId])
        for (b in prog.analysisCache.graph.sinkBlocks) {
            assertEquals(BigInteger.ONE, analysis.pathCounts[b.id])
        }
        val splits = prog.blockgraph.filter { it.value.size == 2 }
        assertEquals(1, splits.size)
        assertEquals(BigInteger.TWO, analysis.pathCounts[splits.keys.first()])
    }

    @Test
    fun countDiamondAndAHalf() {
        val prog = TACProgramBuilder {
            // 0_0 ..
            x assign Le(aS,  bS)
            assume(z)
            c assign Mul(aS, bS)
            y assign Gt(cS, bS)
            d assign Mul(cS, bS)
            jumpCond(x)
            jump(1) { // 1_0..
                e assign Mul(aS, dS)
                f assign Mul(eS, cS)
                z assign Lt(dS, fS)
                assume(z)
                assume(v)
                jumpCond(z)
                jump (2)
                jump { // 3_0..
                    assume(v)
                    w assign v
                }
            }
            jump(2) { // 2_0..
                assume(v)
            }
        }.code
        val analysis = PathCounter(prog.analysisCache.graph)
        assertEquals(3.toBigInteger(), analysis.pathCounts[prog.entryBlockId])
        for (b in prog.analysisCache.graph.sinkBlocks) {
            assertEquals(1.toBigInteger(), analysis.pathCounts[b.id])
        }
        val splits = prog.blockgraph.filter { it.value.size == 2 }
        assertEquals(2, splits.size)
        val splitsPriority = prog.topoSortFw.withIndex().associate { it.value to it.index }
        val firstSplit = splits.minByOrNull { splitsPriority[it.key]!! }!!
        val secondSplit = splits.maxByOrNull { splitsPriority[it.key]!! }!!
        assertEquals(3.toBigInteger(), analysis.pathCounts[firstSplit.key])
        assertEquals(2.toBigInteger(), analysis.pathCounts[secondSplit.key])
    }


    private val h6 = boolVar("h6")
    private val twoDiamonds = TACProgramBuilder {
        // 0_0
        x assign Le(aS, bS)
        assume(x)
        c assign Mul(aS, bS)
        y assign Gt(cS, bS)
        jumpCond(y)
        jump(1) {
            d assign Mul(cS, bS)
            assert(y)
            e assign Mul(bS, number(BigInteger("11", 16)))
            z assign Lt(dS, eS)
            assume(z)
            jump(3) {
                h6 assign LOr(yS, wS)
                assert(h6)
                jumpCond(z)
                jump(4) {
                    assume(w)
                    assert(u)
                    jump(6) {
                        assert(h6)
                    }
                }
                jump(5) {
                    assume(w)
                    assert(u)
                    jump(6)
                }
            }
        }
        jump(2) {
            u assign LNot(yS)
            assume(w)
            assert(u)
            jump(3)
        }

    }


    @Test
    fun countTwoDiamonds() {
        val prog = twoDiamonds.code
        val analysis = PathCounter(prog.analysisCache.graph)
        assertEquals(4.toBigInteger(), analysis.pathCounts[prog.entryBlockId])
        for (b in prog.analysisCache.graph.sinkBlocks) {
            assertEquals(1.toBigInteger(), analysis.pathCounts[b.id])
        }
        val splits = prog.blockgraph.filter { it.value.size == 2 }
        assertEquals(2, splits.size)
        val splitsPriority = prog.topoSortFw.withIndex().associate { it.value to it.index }
        val firstSplit = splits.minByOrNull { splitsPriority[it.key]!! }!!
        val secondSplit = splits.maxByOrNull { splitsPriority[it.key]!! }!!
        assertEquals(4.toBigInteger(), analysis.pathCounts[firstSplit.key])
        assertEquals(2.toBigInteger(), analysis.pathCounts[secondSplit.key])
    }

    /*
    Test the `PathCounter.callIdsToJump` constructor parameter and functionality.

    Graph:
       0
       1
     1   1
       1
       0
       2
     2   2
       2
       0
       3
     3   3
       3
       0
    */
    @Test
    fun countWithJumpsStraightLine() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0_1 = blk(p0)
            val n0_2 = blk(p0)
            val n0_3 = blk(p0)
            val n0_4 = blk(p0)

            val p1 = proc("1")
            val n1_n = blk(p1)
            val n1_s = blk(p1)
            val n1_w = blk(p1)
            val n1_e = blk(p1)

            val p2 = proc("2")
            val n2_n = blk(p2)
            val n2_s = blk(p2)
            val n2_w = blk(p2)
            val n2_e = blk(p2)

            val p3 = proc("3")
            val n3_n = blk(p3)
            val n3_s = blk(p3)
            val n3_w = blk(p3)
            val n3_e = blk(p3)

            edge(n0_1, n1_n)
            edge(n1_n, n1_w)
            edge(n1_n, n1_e)
            edge(n1_w, n1_s)
            edge(n1_e, n1_s)
            edge(n1_s, n0_2)

            edge(n0_2, n2_n)
            edge(n2_n, n2_w)
            edge(n2_n, n2_e)
            edge(n2_w, n2_s)
            edge(n2_e, n2_s)
            edge(n2_s, n0_3)

            edge(n0_3, n3_n)
            edge(n3_n, n3_w)
            edge(n3_n, n3_e)
            edge(n3_w, n3_s)
            edge(n3_e, n3_s)
            edge(n3_s, n0_4)

            val prog = prog("pathCountTestProg").analysisCache.graph

            assertEquals(8.toBigInteger(), PathCounter(prog, n0_1).singlePathCount)
            assertEquals(4.toBigInteger(), PathCounter(prog, n0_1, callIdsToJump = setOf(p1.callId)).singlePathCount)
            assertEquals(4.toBigInteger(), PathCounter(prog, n0_1, callIdsToJump = setOf(p2.callId)).singlePathCount)
            assertEquals(4.toBigInteger(), PathCounter(prog, n0_1, callIdsToJump = setOf(p3.callId)).singlePathCount)
            assertEquals(2.toBigInteger(), PathCounter(prog, n0_1, callIdsToJump = setOf(p1.callId, p2.callId)).singlePathCount)
            assertEquals(2.toBigInteger(), PathCounter(prog, n0_1, callIdsToJump = setOf(p1.callId, p3.callId)).singlePathCount)
            assertEquals(2.toBigInteger(), PathCounter(prog, n0_1, callIdsToJump = setOf(p2.callId, p3.callId)).singlePathCount)
            assertEquals(1.toBigInteger(), PathCounter(prog, n0_1, callIdsToJump = setOf(p1.callId, p2.callId, p3.callId)).singlePathCount)
            assertEquals(8.toBigInteger(), PathCounter(prog, n1_n, callIdsToJump = setOf(p0.callId)).singlePathCount) // can't query at n0_1 since that's jumped
            assertEquals(4.toBigInteger(), PathCounter(prog, n0_1, callIdsToJump = setOf(p2.callId)).singlePathCount)
        }
    }

    /**
     * Test the "per-call stats" computation, including the approach using the [SimpleDominanceAnalysis], used in
     * [PathCountStats].
     *
     * Call graph:
     *  0 -> 1
     *  1 -> 2
     *  1 -> 3
     *
     *  each method has one "local" diamond
     *
     * CFG (block names projected to call ids):
     *
     *  0
     *  |\
     *  0 0
     *  |/
     *  0
     *  |
     *  1
     *  |\
     *  1 1
     *  |/
     *  1
     *  |
     *  2
     *  |\
     *  2 e
     *  |/
     *  2
     *  |
     *  1
     *  |
     *  3
     *  |\
     *  3 3
     *  |/
     *  3
     *  |
     *  1
     *  |
     *  0
     *
     *  per call style path counts should be:
     *  0: 16
     *  1: 8
     *  2: 2
     *  3: 2
     */
    @Test
    fun countPerCall() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0_n = blk(p0)
            val n0_s = blk(p0)
            val n0_w = blk(p0)
            val n0_e = blk(p0)
            val n0_sink = blk(p0)

            val p1 = proc("1")
            val n1_n = blk(p1)
            val n1_s = blk(p1)
            val n1_w = blk(p1)
            val n1_e = blk(p1)
            val n1_middle = blk(p1)
            val n1_exit = blk(p1)

            val p2 = proc("2")
            val n2_n = blk(p2)
            val n2_s = blk(p2)
            val n2_w = blk(p2)
            val n2_e = blk(p2)

            val p3 = proc("3")
            val n3_n = blk(p3)
            val n3_s = blk(p3)
            val n3_w = blk(p3)
            val n3_e = blk(p3)

            edge(n0_n, n0_w)
            edge(n0_n, n0_e)
            edge(n0_w, n0_s)
            edge(n0_e, n0_s)
            edge(n0_s, n1_n)

            edge(n1_n, n1_w)
            edge(n1_n, n1_e)
            edge(n1_w, n1_s)
            edge(n1_e, n1_s)
            edge(n1_s, n2_n)

            edge(n2_n, n2_w)
            edge(n2_n, n2_e)
            edge(n2_w, n2_s)
            edge(n2_e, n2_s)
            edge(n2_s, n1_middle)
            edge(n1_middle, n3_n)

            edge(n3_n, n3_w)
            edge(n3_n, n3_e)
            edge(n3_w, n3_s)
            edge(n3_e, n3_s)
            edge(n3_s, n1_exit)
            edge(n1_exit, n0_sink)

            val ctp = prog("pathCountTestProg")
            val prog = ctp.analysisCache.graph

            assertEquals(16.toBigInteger(), PathCounter(prog, n0_n).singlePathCount)

            // "per-call path count" style counting
            val callIdToPathCountData =
                PathCountStats
                    .computeCallIdToPathCountData(generateCodeMap(ctp, "testCodeMap"))
                    .toMap()
                    .mapKeys { (k, _) -> k.callId }

            assertEquals(PathCountData(2.toBigInteger(), 16.toBigInteger()), callIdToPathCountData[p0.callId])
            assertEquals(PathCountData(2.toBigInteger(), 8.toBigInteger()), callIdToPathCountData[p1.callId])
            assertEquals(PathCountData(2.toBigInteger(), 2.toBigInteger()), callIdToPathCountData[p2.callId])
            assertEquals(PathCountData(2.toBigInteger(), 2.toBigInteger()), callIdToPathCountData[p3.callId])
        }
    }

    /**
     * Test on special tricky shape generated by recursive calls.
     *
     * Call graph:
     *
     * 0 -> 1
     * 1 -> 6
     * 6 -> 8
     * 1 -> 2
     * 2 -> 4
     *
     * CFG (block names projected to call ids):
     *
     * 0
     * |
     * 1
     * |\
     * 1 1
     * |  \
     * 6   2
     * |\  |\
     * 6 6 2 2
     * |   |
     * 8   4
     * |\  |\
     * 8 8 4 4
     */
    @Test
    fun countPerCallRecursiveShape() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0 = blk(p0)

            val p1 = proc("1")
            val n1_e = blk(p1)
            val n1_l = blk(p1)
            val n1_r = blk(p1)

            val p2 = proc("2")
            val n2_e = blk(p2)
            val n2_l = blk(p2)
            val n2_r = blk(p2)

            val p4 = proc("4")
            val n4_e = blk(p4)
            val n4_l = blk(p4)
            val n4_r = blk(p4)

            val p6 = proc("6")
            val n6_e = blk(p6)
            val n6_l = blk(p6)
            val n6_r = blk(p6)

            val p8 = proc("8")
            val n8_e = blk(p8)
            val n8_l = blk(p8)
            val n8_r = blk(p8)

            // the "triangles"
            edge(n1_e, n1_l)
            edge(n1_e, n1_r)
            edge(n2_e, n2_l)
            edge(n2_e, n2_r)
            edge(n4_e, n4_l)
            edge(n4_e, n4_r)
            edge(n6_e, n6_l)
            edge(n6_e, n6_r)
            edge(n8_e, n8_l)
            edge(n8_e, n8_r)

            // connecting the triangles
            edge(n0, n1_e)
            edge(n1_l, n6_e)
            edge(n6_l, n8_e)
            edge(n1_r, n2_e)
            edge(n2_l, n4_e)

            val ctp = prog("pathCountTestProg")
            val prog = ctp.analysisCache.graph

            assertEquals(6.toBigInteger(), PathCounter(prog, n0).singlePathCount)

            // "per-call path count" style counting
            val callIdToPathCountData =
                PathCountStats
                    .computeCallIdToPathCountData(generateCodeMap(ctp, "testCodeMap"))
                    .toMap()
                    .mapKeys { (k, _) -> k.callId }

            assertEquals(PathCountData(1.toBigInteger(), 6.toBigInteger()), callIdToPathCountData[p0.callId])
            assertEquals(PathCountData(2.toBigInteger(), 6.toBigInteger()), callIdToPathCountData[p1.callId])
            assertEquals(PathCountData(2.toBigInteger(), 3.toBigInteger()), callIdToPathCountData[p2.callId])
            assertEquals(PathCountData(2.toBigInteger(), 2.toBigInteger()), callIdToPathCountData[p4.callId])
            assertEquals(PathCountData(2.toBigInteger(), 3.toBigInteger()), callIdToPathCountData[p6.callId])
            assertEquals(PathCountData(2.toBigInteger(), 2.toBigInteger()), callIdToPathCountData[p8.callId])
        }
    }
}
