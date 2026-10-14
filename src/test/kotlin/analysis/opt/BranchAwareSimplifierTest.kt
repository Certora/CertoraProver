/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.*

/**
 * Tests for [BranchAwareSimplifier]: what the numbering proves, written back into the program. The buffer-hash
 * scenarios come from the pass this one replaced: the same data written into two independently built memory
 * buffers and hashed separately, which nothing downstream merges on its own.
 */
class BranchAwareSimplifierTest : TACBuilderAuxiliaries() {

    private fun CoreTACProgram.rhssOf(v: TACSymbol.Var) =
        analysisCache.graph.commands
            .mapNotNull { (_, cmd) -> (cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd)?.takeIf { it.lhs == v }?.rhs }
            .toList()

    private fun CoreTACProgram.rhsOf(v: TACSymbol.Var) = rhssOf(v).single()

    private val p = bv256Var("p")
    private val pS = p.asSym()
    private val pPlus20 = txf.Add(pS, 0x20.asTACExpr)

    private fun store(map: TACSymbol.Var, loc: TACExpr, value: TACSymbol.Var) =
        TACExpr.Store(map.asSym(), loc, value.asSym())

    private fun select(map: TACSymbol.Var, loc: TACExpr) =
        TACExpr.Select(map.asSym(), loc)

    private fun hash(length: Int, vararg args: TACSymbol.Var) =
        TACExpr.SimpleHash(length.asTACExpr, args.map { it.asSym() }, HashFamily.Keccack)

    /** `if (c) {x := A} else {x := B}` twice over the same condition: the two results are equal, and we say so. */
    @Test
    fun anEqualityBetweenTwinDiamondsBecomesTrue() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            jumpCond(x)
            jump(1) {
                c assign 0
                jump(3)
            }
            jump(2) {
                c assign Mul(aS, bS)
                jump(3)
            }
            jumpDest(3) {
                jumpCond(x)
                jump(4) {
                    d assign 0
                    jump(6)
                }
                jump(5) {
                    d assign Mul(aS, bS)
                    jump(6)
                }
                jumpDest(6) {
                    u assign Eq(cS, dS)
                    assert(u)
                }
            }
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(true.asTACExpr, rewritten.rhsOf(u))
    }

    /** A part of an expression folding does not need the whole of it to fold. */
    @Test
    fun aPartialFoldIsKept() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            c assign Sub(bS, bS)
            d assign Add(Mul(cS, aS), aS)
            assert(x)
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        // `c` is zero, so the product is too, and what is left of the sum is `a`
        assertEquals(0.asTACExpr, rewritten.rhsOf(c))
        assertEquals(aS, rewritten.rhsOf(d))
    }

    /** A computation already made where it can be read again becomes a reference to it. */
    @Test
    fun aDominatingEqualIsUsedInstead() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            c assign Add(aS, bS)
            jumpCond(x)
            jump(1) {
                d assign Add(aS, bS)
                jump(2)
            }
            jumpDest(2) {
                assert(x)
            }
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(cS, rewritten.rhsOf(d))
        // and the one that got there first is left as it was
        assertEquals(prog.code.rhsOf(c), rewritten.rhsOf(c))
    }

    /**
     * A value copied into variables of two tags gives both copies one number, since TAC lets an `int` hold a
     * `bv256`. The other way round does not type check, so the tag has to be part of what makes a fold legal.
     */
    @Test
    fun anEqualOfAnotherTagIsNotUsed() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump(1) {
                c assign 1
                jump(3)
            }
            jump(2) {
                c assign 2
                jump(3)
            }
            jumpDest(3) {
                i assign cS
                d assign cS
                assert(x)
            }
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(cS, rewritten.rhsOf(d))
    }

    /** Two arms of one diamond compute the same thing: equal, but neither can be read where the other is. */
    @Test
    fun anEqualInASiblingArmIsNotUsed() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            jumpCond(x)
            jump(1) {
                c assign Add(aS, bS)
                jump(3)
            }
            jump(2) {
                d assign Add(aS, bS)
                jump(3)
            }
            jumpDest(3) {
                assert(x)
            }
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(prog.code.rhsOf(c), rewritten.rhsOf(c))
        assertEquals(prog.code.rhsOf(d), rewritten.rhsOf(d))
    }

    /** A branch on a constant keeps one arm, and the other goes away with everything only it reached. */
    @Test
    fun aConstantBranchLosesItsDeadArm() {
        val prog = TACProgramBuilder {
            havoc(a)
            x assign Eq(0.asTACExpr, 0.asTACExpr)
            jumpCond(x)
            jump(1) {
                c assign Add(aS, 1.asTACExpr)
                jump(3)
            }
            jump(2) {
                c assign 0
                jump(3)
            }
            jumpDest(3) {
                d assign Add(cS, 1.asTACExpr)
                assert(x)
            }
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        val blocks = rewritten.analysisCache.graph.blocks.map { it.id }
        assertTrue(prog.block(1) in blocks) { "the live arm should stay: $blocks" }
        assertTrue(prog.block(2) !in blocks) { "the dead arm should be gone: $blocks" }
        // the condition itself is known, so it is written back as a constant
        assertEquals(true.asTACExpr, rewritten.rhsOf(x))
    }

    /**
     * Two buffers built by the same stores over the same symbolic base, read back and hashed separately. Every
     * step of the second construction gets the first's number, so the hashes are one value and the equality of
     * the two is decided here, which is the whole point: nothing downstream merges those hash applications.
     */
    @Test
    fun twoBuffersBuiltAlikeHashToOneValue() {
        val m1 = mkBmVar("m1")
        val m2 = mkBmVar("m2")
        val n1 = mkBmVar("n1")
        val n2 = mkBmVar("n2")
        val prog = TACProgramBuilder {
            havoc(p)
            havoc(bMap1)
            havoc(a)
            havoc(b)
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
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(m1.asSym(), rewritten.rhsOf(n1))
        assertEquals(m2.asSym(), rewritten.rhsOf(n2))
        assertEquals(cS, rewritten.rhsOf(e))
        assertEquals(dS, rewritten.rhsOf(f))
        assertEquals(gS, rewritten.rhsOf(h))
        assertEquals(true.asTACExpr, rewritten.rhsOf(x))
    }

    /** The two buffers agree until they do not, and from there nothing is shared. */
    @Test
    fun buffersThatDivergeAreNotMerged() {
        val m1 = mkBmVar("m1")
        val m2 = mkBmVar("m2")
        val n1 = mkBmVar("n1")
        val n2 = mkBmVar("n2")
        val prog = TACProgramBuilder {
            havoc(p)
            havoc(bMap1)
            havoc(a)
            havoc(b)
            havoc(c)
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
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        // the shared prefix still folds
        assertEquals(m1.asSym(), rewritten.rhsOf(n1))
        // and nothing past the divergence does
        assertEquals(prog.code.rhsOf(n2), rewritten.rhsOf(n2))
        assertEquals(prog.code.rhsOf(h), rewritten.rhsOf(h))
        assertEquals(prog.code.rhsOf(x), rewritten.rhsOf(x))
    }

    /** The same arguments hashed under different lengths are different values. */
    @Test
    fun hashesOfDifferentLengthsAreNotMerged() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            g assign hash(0x40, a, b)
            h assign hash(0x20, a, b)
            x assign Eq(gS, hS)
            assert(x)
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(prog.code.rhsOf(h), rewritten.rhsOf(h))
        assertEquals(prog.code.rhsOf(x), rewritten.rhsOf(x))
    }

    /** Both arms compute what a dominating definition already holds, so below the join the join does too. */
    @Test
    fun aJoinWhoseArmsAgreeIsTheValueTheyAgreeOn() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            c assign Add(aS, bS)
            jumpCond(x)
            jump(1) {
                d assign Add(aS, bS)
                jump(3)
            }
            jump(2) {
                d assign Add(aS, bS)
                jump(3)
            }
            jumpDest(3) {
                y assign Eq(dS, cS)
                assert(y)
            }
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(listOf(cS, cS), rewritten.rhssOf(d))
        assertEquals(true.asTACExpr, rewritten.rhsOf(y))
    }

    /** Before [instrumentation.transformers.DSAToSSA] maps are in the same diamond form as scalars, and fold so. */
    @Test
    fun aMapDiamondFoldsLikeAScalarOne() {
        val mc = mkBmVar("mc")
        val m = mkBmVar("m")
        val prog = TACProgramBuilder {
            havoc(p)
            havoc(bMap1)
            havoc(a)
            havoc(x)
            mc assign store(bMap1, pS, a)
            jumpCond(x)
            jump(1) {
                m assign store(bMap1, pS, a)
                jump(3)
            }
            jump(2) {
                m assign store(bMap1, pS, a)
                jump(3)
            }
            jumpDest(3) {
                c assign select(m, pS)
                y assign Eq(cS, aS)
                assert(y)
            }
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(listOf(mc.asSym(), mc.asSym()), rewritten.rhssOf(m))
    }

    /** A variable defined once per arm has one number below the join, so two reads of it there agree. */
    @Test
    fun aJoinedOperandGivesOneNumberAtEveryUse() {
        val prog = TACProgramBuilder {
            havoc(b)
            havoc(x)
            jumpCond(x)
            jump(1) {
                a assign 1
                jump(3)
            }
            jump(2) {
                a assign 2
                jump(3)
            }
            jumpDest(3) {
                c assign Add(aS, bS)
                d assign Add(aS, bS)
                y assign Eq(cS, dS)
                assert(y)
            }
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(cS, rewritten.rhsOf(d))
        assertEquals(true.asTACExpr, rewritten.rhsOf(y))
    }

    /** An annotation is value-transparent, so the fold goes through it and it is put back around the result. */
    @Test
    fun anAnnotationSurvivesTheFold() {
        val key = tac.MetaKey<String>("test.annotation")
        fun annotated(payload: String) = TACExpr.AnnotationExp(select(bMap1, pS), key, payload)
        val prog = TACProgramBuilder {
            havoc(p)
            havoc(bMap1)
            c assign annotated("first")
            d assign annotated("second")
            x assign Eq(cS, dS)
            assert(x)
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(TACExpr.AnnotationExp(cS, key, "second"), rewritten.rhsOf(d))
        assertEquals(true.asTACExpr, rewritten.rhsOf(x))
    }

    /** Two unconstrained terms look alike and are not one value — each definition of one is its own number. */
    @Test
    fun twoUnconstrainedTermsAreNotOneValue() {
        val prog = TACProgramBuilder {
            c assign TACExpr.Unconstrained(Tag.Bit256)
            d assign TACExpr.Unconstrained(Tag.Bit256)
            x assign Eq(cS, dS)
            assert(x)
        }
        val rewritten = BranchAwareSimplifier.rewrite(prog.code)
        assertEquals(prog.code.rhsOf(d), rewritten.rhsOf(d))
        assertEquals(prog.code.rhsOf(x), rewritten.rhsOf(x))
    }
}
