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

package analysis.opt

import analysis.opt.inliner.Inlinee.*
import analysis.opt.inliner.Inlinee.Companion.isSurelyInside
import analysis.opt.inliner.InliningCalculator
import analysis.opt.intervals.Intervals
import config.Config
import config.ConfigScope
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import utils.*
import vc.data.*

class InliningCalculatorTest : TACBuilderAuxiliaries() {

    private fun TACProgramBuilder.BuiltTACProgram.checkQuery(vararg pairs: Pair<String, TACSymbol?>) {
        val calculator = InliningCalculator(code)
        calculator.go()
        for ((query, sym) in pairs) {
            assertEquals(sym?.let { Term(it) } ?: None, calculator.lhsVal(ptr(query)))
        }
    }

    private fun TACProgramBuilder.BuiltTACProgram.checkMap(base: Term, vararg pairs: Pair<Int, Term>) {
        val calculator = InliningCalculator(code)
        calculator.go()
//        calculator.debugPrinter().print(code)
        assertEquals(
            Mapping(
                buildTreapMap {
                    for ((key, value) in pairs) {
                        this[base + key] = value
                    }
                }),
            calculator.lhsVal(ptr("query"))
        )
    }

    @Test
    fun writeRead() {
        val prog = TACProgramBuilder {
            bMap1[32] assign a
            label("query")
            b assign bMap1[32]
            assert(False)
        }
        prog.checkQuery("query" to a)
    }

    @Test
    fun write2read2() {
        val prog = TACProgramBuilder {
            c assign 32
            bMap1[c] assign a
            f assign Add(cS, 40.asTACExpr)
            bMap1[f] assign b
            label("1")
            d assign bMap1[32]
            label("2")
            e assign bMap1[72]
            assert(False)
        }
        prog.checkQuery("1" to a, "2" to b)
    }

    @Test
    fun writeOverlap() {
        val prog = TACProgramBuilder {
            b assign Add(aS, 10.asTACExpr)
            bMap1[b] assign 1

            c assign Add(bS, 40.asTACExpr)
            bMap1[c] assign 2

            d assign Add(aS, 100.asTACExpr)
            bMap1[d] assign 3

            f assign Add(aS, 30.asTACExpr)
            bMap1[f] assign 8

            e assign Sub(cS, 20.asTACExpr)
            label("query")
            bMap1[e] assign 4

            assert(False)
        }
        ConfigScope(Config.exactByteMaps, true).use {
            prog.checkMap(
                Term(a),
                100 to Term(3),
                30 to Term(4)
            )
        }
        ConfigScope(Config.exactByteMaps, false).use {
            prog.checkMap(
                Term(a),
                10 to Term(1),
                50 to Term(2),
                100 to Term(3),
                30 to Term(4)
            )
        }
    }

    @Test
    fun overwrite() {
        val prog = TACProgramBuilder {
            c assign 32.asTACSymbol()
            bMap1[c] assign a
            bMap1[c] assign b
            label("query")
            e assign bMap1[32.asTACSymbol()]
            assert(False)
        }
        prog.checkQuery("query" to b)
    }

    @Test
    fun writeDifferentLoc() {
        val prog = TACProgramBuilder {
            c assign 32.asTACSymbol()
            bMap1[c] assign a
            bMap1[d] assign b
            label("query")
            e assign bMap1[32.asTACSymbol()]
            assert(False)
        }
        prog.checkQuery("query" to e)
    }

    @Test
    fun join() {
        val prog = TACProgramBuilder {
            b assign Add(aS, 32.asTACExpr)
            d assign c
            jump(1) {
                bMap1[b] assign c
                jump(3) {
                    label("query")
                    e assign bMap1[b]
                    assert(False)
                }
            }
            jump(2) {
                bMap1[b] assign d
                jump(3)
            }
        }
        prog.checkQuery("query" to c)
    }

    @Test
    fun reassign() {
        val prog = TACProgramBuilder {
            b assign a
            a assign 7
            label("query")
            c assign b
            assert(False)
        }
        prog.checkQuery("query" to b)
    }


    @Test
    fun withExpr() {
        val prog = TACProgramBuilder {
            bMap1 assign Store(bMap1S, 32.asTACExpr, v = aS)
            label("query")
            b assign Select(bMap1S, 32.asTACExpr)
            assert(False)
        }
        prog.checkQuery("query" to a)
    }


    @Test
    fun writeRead2() {
        val prog = TACProgramBuilder {
            a assign Mul(bS, eS)
            c assign Add(aS, 5.asTACExpr)
            label("query")
            d assign Sub(cS, 5.asTACExpr)
            assert(False)
        }
        prog.checkQuery("query" to a)
    }


    @Test
    fun longStore() {
        // comments are for the exact case.
        val prog = TACProgramBuilder {
            // dst: will be overwritten in [80-180]
            bMap1[0] assign 11 // kept
            bMap1[70] assign 22 // cut
            bMap1[110] assign 33 // removed
            bMap1[200] assign 44 // kept

            // src: will be copied from 50-150
            bMap2[0] assign 111 // not taken
            bMap2[32] assign 222 // cut
            bMap2[100] assign 333 // taken
            bMap2[140] assign 444 // cut
            bMap2[200] assign 555 // not taken

            label("query")
            addCmd(
                TACCmd.Simple.ByteLongCopy(
                    srcBase = bMap2,
                    srcOffset = 50.asTACSymbol(),
                    length = 100.asTACSymbol(),
                    dstBase = bMap1,
                    dstOffset = 80.asTACSymbol(),
                )
            )
            assert(False)
        }
        ConfigScope(Config.exactByteMaps, true).use {
            prog.checkMap(
                Term.ZeroTerm,
                0 to Term(11),
                200 to Term(44),
                130 to Term(333),
            )
        }
        ConfigScope(Config.exactByteMaps, false).use {
            prog.checkMap(
                Term.ZeroTerm,
                0 to Term(11),
                70 to Term(22),
                200 to Term(44),
                130 to Term(333),
                170 to Term(444),
            )
        }
    }

    @Test
    fun longStore2() {
        val prog = TACProgramBuilder {
            bMap1[0x80] assign 11
            bMap1[0xa0] assign 22
            bMap1[0xc0] assign a
            label("query")
            addCmd(
                TACCmd.Simple.ByteLongCopy(
                    srcBase = bMap1,
                    srcOffset = 0xa.asTACSymbol(),
                    length = TACSymbol.Zero,
                    dstBase = bMap2,
                    dstOffset = TACSymbol.Zero
                )
            )
            assert(False)
        }
        prog.checkMap(
            Term.ZeroTerm,
        )
    }

    @Test
    fun longStore3() {
        val prog = TACProgramBuilder {
            bMap1[100] assign 11
            bMap2[200] assign 22
            label("query")
            addCmd(
                TACCmd.Simple.ByteLongCopy(
                    srcBase = bMap1,
                    srcOffset = 80.asTACSymbol(),
                    length = 50.asTACSymbol(),
                    dstBase = bMap2,
                    dstOffset = a
                )
            )
            assert(False)
        }
        prog.checkMap(
            Term(a),
            20 to Term(11)
        )
    }

    @Test
    fun johnsTest() {
        val prog = TACProgramBuilder {
            a assign Mul(bS, cS)
            bMap1[a] assign 7
            a assign Mul(bS, dS)
            label("query")
            e assign bMap1[a]
            assert(False)
        }
        prog.checkQuery("query" to e)
    }

    @Test
    fun joinTest() {
        val prog = TACProgramBuilder {
            jump {
                a assign b
                jump(1) {
                    label("query")
                    d assign a
                    assert(False)
                }
            }
            jump {
                a assign c
                jump(1)
            }
        }
        prog.checkQuery("query" to a)
    }

    @Test
    fun bv256Bug() {
        val prog = TACProgramBuilder {
            label("query")
            i assign (-1).asIntTACExpr
            assert(False)
        }
        // before fixing the bug, this was evaluated to be max_uint
        prog.checkQuery("query" to null)
    }


    @Test
    fun generalMaps() {
        val prog = TACProgramBuilder {
            b assign Add(aS, 10.asTACExpr)
            bMap1[b] assign 7
            assumeExp(Lt(cS, ModZm.lowOnes(160).asTACExpr))
            d assign Add(bS, cS, 5.asTACExpr)
            label("query")
            bMap1[d] assign 10
            assert(False)
        }
        val calculator = InliningCalculator(prog.code).apply { go() }
        assertEquals(
            Mapping(
                buildTreapMap {
                    this[Term(a) + 10] = Term(7)
                    this[Term(a) + Term(c) + 15] = Term(10)
                }
            ),
            calculator.lhsVal(prog.ptr("query"))
        )
    }

    @Test
    fun generalMaps2() {
        val prog = TACProgramBuilder {
            havoc(c)
            bMap1[a] assign 11
            bMap2[10] assign 22
            b assign Add(aS, 10.asTACExpr)
            assumeExp(Gt(cS, 100.asTACExpr))
            addCmd(
                TACCmd.Simple.ByteLongCopy(
                    srcBase = bMap2,
                    srcOffset = 5.asTACSymbol(),
                    length = c,
                    dstBase = bMap1,
                    dstOffset = b
                )
            )
            d assign Add(bS, cS, 30.asTACExpr)
            label("query")
            bMap1[d] assign 33
            assert(False)
        }
        val calculator = InliningCalculator(prog.code).apply { go() }
        assertEquals(
            Mapping(
                buildTreapMap {
                    this[Term(a)] = Term(11)
                    this[Term(a) + 15] = Term(22)
                    this[Term(a) + Term(c) + 40] = Term(33)
                }
            ),
            calculator.lhsVal(prog.ptr("query"))
        )
    }


    @Test
    fun testInside() {
        assertTrue(Term(10).isSurelyInside(Term(10), Term(20)) { Intervals.SFull256 })
        assertTrue(Term(19).isSurelyInside(Term(10), Term(20)) { Intervals.SFull256 })
        assertFalse(Term(9).isSurelyInside(Term(10), Term(20)) { Intervals.SFull256 })
        assertFalse(Term(20).isSurelyInside(Term(10), Term(20)) { Intervals.SFull256 })
    }


    @Test
    fun testAssume() {
        val prog = TACProgramBuilder {
            bMap1[10.asTACSymbol()] assign 11
            assumeExp(Eq(aS, 3.asTACExpr))
            b assign Add(Mul(32.asTACExpr, aS), 2.asTACExpr)
            label("query")
            bMap1[b] assign 22
            assert(False)
        }
        val calculator = InliningCalculator(prog.code).apply { go() }
        assertEquals(
            Mapping(
                buildTreapMap {
                    this[Term(10)] = Term(11)
                    this[Term(a) * 32 + 2] = Term(22)
                }
            ),
            calculator.lhsVal(prog.ptr("query"))
        )
    }

}
