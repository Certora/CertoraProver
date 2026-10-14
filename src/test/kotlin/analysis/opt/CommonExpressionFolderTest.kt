/*
 *     The Certora Prover
 *     Copyright (C) 2026 Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.opt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.*
import vc.data.TACProgramBuilder.BuiltTACProgram
import vc.data.TACProgramBuilder.Companion.testProgString

/**
 * Tests for [CommonExpressionFolder]. The main scenario mirrors the motivating buffer-hash case: two buffers
 * built by stores at a symbolic base + constant deltas with the same value terms, hashed separately; the
 * unifier should merge the buffer versions, then the word reads, then the hash applications.
 */
class CommonExpressionFolderTest : TACBuilderAuxiliaries() {

    private val p = bv256Var("p")
    private val pS = p.asSym()

    private fun runAndCompare(original: BuiltTACProgram, expected: BuiltTACProgram) {
        assertEquals(
            testProgString(expected.code),
            testProgString(CommonExpressionFolder.fold(original.code))
        )
    }

    private fun store(map: TACSymbol.Var, loc: TACExpr, value: TACSymbol.Var) =
        TACExpr.Store(map.asSym(), loc, value.asSym())

    private fun select(map: TACSymbol.Var, loc: TACExpr) =
        TACExpr.Select(map.asSym(), loc)

    private fun hash(length: Int, vararg args: TACSymbol.Var) =
        TACExpr.SimpleHash(length.asTACExpr, args.map { it.asSym() }, HashFamily.Keccack)

    private val pPlus20 = txf.Add(pS, 0x20.asTACExpr)

    @Test
    fun symbolicBaseBuffersUnify() {
        val m1 = mkBmVar("m1")
        val m2 = mkBmVar("m2")
        val n1 = mkBmVar("n1")
        val n2 = mkBmVar("n2")
        val original = TACProgramBuilder {
            m1 assign store(bMap1, pS, a)
            m2 assign store(m1, pPlus20, b)
            n1 assign store(bMap1, pS, a)
            n2 assign store(n1, pPlus20, b)
            c assign select(m2, pS)
            d assign select(m2, pPlus20)
            e assign select(n2, pS)
            f assign select(n2, pPlus20)
            g assign hash(0x40, c, d)
            h assign hash(0x40, e, f)
            x assign Eq(gS, hS)
            assert(x)
        }
        val expected = TACProgramBuilder {
            m1 assign store(bMap1, pS, a)
            m2 assign store(m1, pPlus20, b)
            n1 assign m1
            n2 assign m2
            c assign select(m2, pS)
            d assign select(m2, pPlus20)
            e assign c
            f assign d
            g assign hash(0x40, c, d)
            h assign g
            x assign Eq(gS, gS)
            assert(x)
        }
        runAndCompare(original, expected)
    }

    @Test
    fun differentValueTermNotUnified() {
        val m1 = mkBmVar("m1")
        val m2 = mkBmVar("m2")
        val n1 = mkBmVar("n1")
        val n2 = mkBmVar("n2")
        val original = TACProgramBuilder {
            m1 assign store(bMap1, pS, a)
            m2 assign store(m1, pPlus20, b)
            n1 assign store(bMap1, pS, a)
            n2 assign store(n1, pPlus20, c) // c instead of b
            d assign select(m2, pS)
            e assign select(n2, pS)
            g assign hash(0x40, d, d)
            h assign hash(0x40, e, e)
            x assign Eq(gS, hS)
            assert(x)
        }
        val expected = TACProgramBuilder {
            m1 assign store(bMap1, pS, a)
            m2 assign store(m1, pPlus20, b)
            n1 assign m1 // the first store is still identical
            n2 assign store(m1, pPlus20, c) // but the buffers diverge here
            d assign select(m2, pS)
            e assign select(n2, pS)
            g assign hash(0x40, d, d)
            h assign hash(0x40, e, e)
            x assign Eq(gS, hS)
            assert(x)
        }
        runAndCompare(original, expected)
    }

    @Test
    fun differentLengthsNotUnified() {
        val original = TACProgramBuilder {
            g assign hash(0x40, a, b)
            h assign hash(0x20, a, b)
            x assign Eq(gS, hS)
            assert(x)
        }
        runAndCompare(original, original)
    }

    @Test
    fun inPlaceMapUpdateNotUnified() {
        // bMap1 is read before its only def and appears in its own defining rhs, so DSAChecker flags it and
        // the identical-looking reads around the mutation must not unify
        val original = TACProgramBuilder {
            c assign select(bMap1, pS)
            bMap1 assign store(bMap1, pS, a)
            d assign select(bMap1, pS)
            x assign Eq(cS, dS)
            assert(x)
        }
        runAndCompare(original, original)
    }

    @Test
    fun parallelBranchesNotUnified() {
        val original = TACProgramBuilder {
            jumpCond(x)
            jump {
                c assign Add(aS, bS)
                jump(1)
            }
            jump {
                d assign Add(aS, bS)
                jump(1)
            }
            jumpDest(1) {
                assert(x)
            }
        }
        runAndCompare(original, original)
    }

    @Test
    fun dominatingDefUnifiesIntoBranch() {
        val original = TACProgramBuilder {
            c assign Add(aS, bS)
            jumpCond(x)
            jump {
                d assign Add(aS, bS)
                jump(1)
            }
            jump(1)
            jumpDest(1) {
                assert(x)
            }
        }
        val expected = TACProgramBuilder {
            c assign Add(aS, bS)
            jumpCond(x)
            jump {
                d assign c
                jump(1)
            }
            jump(1)
            jumpDest(1) {
                assert(x)
            }
        }
        runAndCompare(original, expected)
    }

    @Test
    fun joinVarOperandUnifies() {
        // `a` is a DSA diamond var (defs in sibling arms); post-join uses of it still qualify as operands
        val original = TACProgramBuilder {
            jumpCond(x)
            jump {
                a assign 1
                jump(1)
            }
            jump {
                a assign 2
                jump(1)
            }
            jumpDest(1) {
                c assign Add(aS, bS)
                d assign Add(aS, bS)
                y assign Eq(cS, dS)
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            jumpCond(x)
            jump {
                a assign 1
                jump(1)
            }
            jump {
                a assign 2
                jump(1)
            }
            jumpDest(1) {
                c assign Add(aS, bS)
                d assign c
                y assign Eq(cS, cS)
                assert(y)
            }
        }
        runAndCompare(original, expected)
    }

    @Test
    fun parallelArmEntryDoesNotBlockPostJoinPair() {
        // c/d are in parallel arms (not unifiable), but the post-join pair e/f must still unify with each other
        val original = TACProgramBuilder {
            jumpCond(x)
            jump {
                c assign Add(aS, bS)
                jump(1)
            }
            jump {
                d assign Add(aS, bS)
                jump(1)
            }
            jumpDest(1) {
                e assign Add(aS, bS)
                f assign Add(aS, bS)
                y assign Eq(eS, fS)
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            jumpCond(x)
            jump {
                c assign Add(aS, bS)
                jump(1)
            }
            jump {
                d assign Add(aS, bS)
                jump(1)
            }
            jumpDest(1) {
                e assign Add(aS, bS)
                f assign e
                y assign Eq(eS, eS)
                assert(y)
            }
        }
        runAndCompare(original, expected)
    }

    @Test
    fun diamondArmDefRewrittenWithoutUseRedirect() {
        // `d` is a diamond var: its arm-A def is a dup of `c` and gets rewritten to an alias, and arm-local
        // uses of `d` follow (they are dominated by the rewritten def) — but the post-join use of `d` must
        // keep reading whichever arm ran
        val original = TACProgramBuilder {
            c assign Add(aS, bS)
            jumpCond(x)
            jump {
                d assign Add(aS, bS)
                e assign Add(dS, cS) // arm-local use of d
                jump(1)
            }
            jump {
                d assign Sub(aS, bS)
                jump(1)
            }
            jumpDest(1) {
                y assign Eq(dS, cS)
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            c assign Add(aS, bS)
            jumpCond(x)
            jump {
                d assign c
                e assign Add(cS, cS)
                jump(1)
            }
            jump {
                d assign Sub(aS, bS)
                jump(1)
            }
            jumpDest(1) {
                y assign Eq(dS, cS)
                assert(y)
            }
        }
        runAndCompare(original, expected)
    }

    @Test
    fun diamondCollapsesWhenAllArmsRewriteToSameVar() {
        // both arm defs of `d` are dups of `c`, so after both are rewritten, d == c everywhere:
        // the post-join use is redirected as well
        val original = TACProgramBuilder {
            c assign Add(aS, bS)
            jumpCond(x)
            jump {
                d assign Add(aS, bS)
                jump(1)
            }
            jump {
                d assign Add(aS, bS)
                jump(1)
            }
            jumpDest(1) {
                y assign Eq(dS, cS)
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            c assign Add(aS, bS)
            jumpCond(x)
            jump {
                d assign c
                jump(1)
            }
            jump {
                d assign c
                jump(1)
            }
            jumpDest(1) {
                y assign Eq(cS, cS)
                assert(y)
            }
        }
        runAndCompare(original, expected)
    }

    @Test
    fun diamondMapCollapses() {
        // before DSAToSSA maps are diamond-DSA like scalars, so a multi-write map whose arms all duplicate a
        // dominating construction collapses just the same
        val mc = mkBmVar("mc")
        val m = mkBmVar("m")
        val original = TACProgramBuilder {
            mc assign store(bMap1, pS, a)
            jumpCond(x)
            jump {
                m assign store(bMap1, pS, a)
                jump(1)
            }
            jump {
                m assign store(bMap1, pS, a)
                jump(1)
            }
            jumpDest(1) {
                c assign select(m, pS)
                y assign Eq(cS, aS)
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            mc assign store(bMap1, pS, a)
            jumpCond(x)
            jump {
                m assign mc
                jump(1)
            }
            jump {
                m assign mc
                jump(1)
            }
            jumpDest(1) {
                c assign select(mc, pS)
                y assign Eq(cS, aS)
                assert(y)
            }
        }
        runAndCompare(original, expected)
    }

    @Test
    fun mixedDiamondArmsRedirectLocally() {
        // each arm of `e` copies a different canonical, poisoning the global chart — but each arm's local
        // uses still redirect through the block-local chart; the join use stays untouched
        val original = TACProgramBuilder {
            c assign Add(aS, bS)
            d assign Sub(aS, bS)
            jumpCond(x)
            jump {
                e assign Add(aS, bS)
                f assign Add(eS, cS)
                jump(1)
            }
            jump {
                e assign Sub(aS, bS)
                g assign Add(eS, dS)
                jump(1)
            }
            jumpDest(1) {
                y assign Eq(eS, cS)
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            c assign Add(aS, bS)
            d assign Sub(aS, bS)
            jumpCond(x)
            jump {
                e assign c
                f assign Add(cS, cS)
                jump(1)
            }
            jump {
                e assign d
                g assign Add(dS, dS)
                jump(1)
            }
            jumpDest(1) {
                y assign Eq(eS, cS)
                assert(y)
            }
        }
        runAndCompare(original, expected)
    }

    @Test
    fun plainCopyPropagates() {
        // a pre-existing literal copy feeds the chart just like a folded def does
        val original = TACProgramBuilder {
            c assign Add(aS, bS)
            d assign c
            e assign Add(dS, aS)
            y assign Eq(eS, cS)
            assert(y)
        }
        val expected = TACProgramBuilder {
            c assign Add(aS, bS)
            d assign c
            e assign Add(cS, aS)
            y assign Eq(eS, cS)
            assert(y)
        }
        runAndCompare(original, expected)
    }

    @Test
    fun annotatedReadsUnifyKeepingAnnotations() {
        val key = tac.MetaKey<String>("test.annotation")
        fun annotated(payload: String) =
            TACExpr.AnnotationExp(select(bMap1, pS), key, payload)
        val original = TACProgramBuilder {
            c assign annotated("first")
            d assign annotated("second")
            x assign Eq(cS, dS)
            assert(x)
        }
        val expected = TACProgramBuilder {
            c assign annotated("first")
            d assign TACExpr.AnnotationExp(cS, key, "second")
            x assign Eq(cS, cS)
            assert(x)
        }
        runAndCompare(original, expected)
    }

    @Test
    fun unconstrainedNotUnified() {
        val original = TACProgramBuilder {
            c assign TACExpr.Unconstrained(Tag.Bit256)
            d assign TACExpr.Unconstrained(Tag.Bit256)
            x assign Eq(cS, dS)
            assert(x)
        }
        runAndCompare(original, original)
    }
}
