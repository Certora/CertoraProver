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

package wasm.transform

import datastructures.stdcollections.*
import analysis.*
import analysis.PatternDSL.invoke
import analysis.PatternMatcher.Pattern.Companion.toBuildable
import evm.MASK_SIZE
import tac.generation.*
import utils.*
import vc.data.*
import java.math.BigInteger

object BitopsRewriter {
    /**
     * Rewrites
     *   V := ((X xor Y) | (X' xor Y')) == 0)
     * To
     *   V := (X == Y) && (X' == Y')
     */
    fun rewriteXorEquality(core: CoreTACProgram): CoreTACProgram {
        val patt = PatternMatcher.compilePattern(core.analysisCache.graph, xorEquality)

        // This is a pair of pairs of variables (each sub-pair are being compared for equality)
        val matches = core.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd>()
        }.mapNotNull {
            it `to?` patt.queryFrom(it).toNullableResult()
        }

        return core.patching {
            for ((cmd, m) in matches) {
                val (xy1, xy2) = m
                val newCmds = assign(cmd.cmd.lhs) {
                    (xy1.first eq xy1.second) and (xy2.first eq xy2.second)
                }
                it.replaceCommand(
                    cmd.ptr,
                    newCmds.cmds
                )
                it.addVarDecls(newCmds.varDecls)
            }
        }
    }
    /* Proof:
       (declare-const X (_ BitVec 64))
       (declare-const Y (_ BitVec 64))
       (declare-const A (_ BitVec 64))
       (declare-const B (_ BitVec 64))

       (assert (= (bvor (bvxor X Y) (bvxor A B)) (_ bv0 64)))
       (assert (not (and (= X Y) (= A B))))

       (check-sat); UNSAT
    */

    val xorEquality = PatternDSL.build {
        (((Var xor Var) or (Var xor Var)) `==` 0()).commute.withAction { theOr, _ -> theOr }
    }

    /**
     * Rewrites
     *  X = HI_0 xor HI_1 xor 2**64 - 1
     *  Y = HI_0 xor ((HI_0 + HI_1 % 2**64) + C % 2**64)  // C must be 0 or 1
     *  B = (X & Y) sle -1
     *
     * Into
     *   B = (sign(HI_0) == sign(HI_1)) && sign(HI_0 + HI_1 + C) != sign(HI_0)
     *
     *  Why?
     *   These operations are used to determine if i128 addition over/underflows. HI_0 and HI_1 are
     *   intended to be the most significant 64 bit words of a 128 bit value. C is intended to be the carry-out
     *   from the addition of the least significant 64 bits. Then:
     *   - X's MSB is 1 if HI_0 and HI_1 have the same sign
     *   - Y's MSB is 1 if the sign of the sum of HI_0 + HI_1 + C _differs_ from that of
     *     HI_0 (and equivalently from HI_1)
     *   - B's MSB is 1 (and hence the value is sle -1) if both conditions above are met, i.e. X and Y's MSB are 1
     *   - Over/underflow can only happen if the operand signs are the same and the result sign is different.
     *
     *  But really, why?
     *    The rewritten form is much friendlier to LIA solvers
     *
     *  See smtlib proof below for correctness
     */
    fun rewriteSignedOverflowCheck(core: CoreTACProgram): CoreTACProgram {
        val graph = core.analysisCache.graph
        val def = NonTrivialDefAnalysis(graph)

        val comparison = PatternMatcher.compilePattern(graph, comparePattern)
        val zeroOrOne = PatternMatcher.compilePattern(graph, carryOut)
        val sameSignWithMatcher = PatternMatcher.compilePattern(graph, overUnderflowCheck)

        val changes = graph.blocks.parallelStream().mapNotNull { b ->
            graph.elab(b.id).commands.last().maybeNarrow<TACCmd.Simple.JumpiCmd>()
        }.mapNotNull { jump ->
            jump.cmd.cond.tryAs<TACSymbol.Var>()?.let {
                def.getDefCmd<TACCmd.Simple.AssigningCmd.AssignExpCmd>(it, jump.ptr)
            }
        }.mapNotNull { here ->
            // We found a jump on condition variable b
            // we want to see if b = SignExtend(7, cond) <= SignExtend(7, 2**64 - 1)
            val (cond, pos) = comparison.queryFrom(here).toNullableResult() ?: return@mapNotNull null
            // Now check cond = (X & Y) ... as in the function description
            val maybeCheck = sameSignWithMatcher.query(cond.second, here.wrapped).toNullableResult() ?: return@mapNotNull null
            // Finally we need to verify that the variables appear in X and Y
            Triple(here, maybeCheck.sumWithRewrite, pos).takeIf {
                val (where, carryOut) = maybeCheck.sumWithRewrite.c
                maybeCheck.isValid
                    && zeroOrOne.query(carryOut, graph.elab(where)) is PatternMatcher.ConstLattice.Match
            }
        }

        return core.patching {
            for ((condDefinition, sum, pos) in changes) {
                val newCommands = TACExprFactUntyped {
                    val theSum = (((sum.a.asSym() add sum.b.asSym()) mod BigInteger.TWO.pow(64).asTACExpr)
                        add sum.c.second.asSym()) mod BigInteger.TWO.pow(64).asTACExpr
                    theSum
                }.letVar { theSum ->
                    assign(condDefinition.cmd.lhs) {
                        fun TACExpr.isNegative() = this ge BigInteger.TWO.pow(63).asTACExpr

                        val operandsSameSign = sum.a.asSym().isNegative() eq sum.b.asSym().isNegative()
                        val sumDifferentSign = sum.a.asSym().isNegative() neq theSum.isNegative()
                        (operandsSameSign and sumDifferentSign).letIf (!pos) {
                            not(it)
                        }
                    }
                }
                it.replaceCommand(condDefinition.ptr, newCommands)
            }
        }
    }
    /** ([op1] + [op2]) mod 2**64 */
    private data class SignCheck(val op1: TACSymbol.Var, val op2: TACSymbol.Var)

    /** [x] ^ ((([a] + [b]) mod 2**64 + [c]) mod 2**64) */
    private data class SumWithRewrite(
        val x: TACSymbol.Var,
        val a: TACSymbol.Var,
        val b: TACSymbol.Var,
        val c: Pair<CmdPointer, TACSymbol.Var>,
    )

    /** SignCheck & SumWithRewrite */
    private data class MaybeOverflowCheck(val signCheck: SignCheck, val sumWithRewrite: SumWithRewrite) {
        /** true means that (referring to the documentation on [SignCheck] and [SumWithRewrite]:
         *    (x == op1 || x == op2) AND
         *    one of {a, b, c} is op1 and one of {a, b, c} is op2
         */
        val isValid: Boolean
            get() = (sumWithRewrite.x == signCheck.op1 || sumWithRewrite.x == signCheck.op2)
                && setOf(sumWithRewrite.a, sumWithRewrite.b, sumWithRewrite.c)
                     .containsAll(setOf(signCheck.op1, signCheck.op2))
    }

    private val overUnderflowCheck: PatternMatcher.Pattern<MaybeOverflowCheck> = PatternDSL.build {
        val two64 = BigInteger.TWO.pow(64)
        val addMod = ((Var + Var) mod two64()).withAction { vars, _ -> vars }

        // x ^ y ^ (0xff...ff)
        val sameSignCheck = ((Var xor Var) xor MASK_SIZE(64)()).commute.withAction { vs, _ ->
            SignCheck(vs.first, vs.second)
        }

        // x ^ ((a + b % 2**64) + c % 2**64)
        val sumWithCarry = (Var xor ((addMod + Var.withLocation).commute mod two64()).withAction { vs, _ -> vs })
            .commute
            .withAction { shouldBeHiOrLow, (xs, c) ->
                SumWithRewrite(
                    shouldBeHiOrLow,
                    xs.first,
                    xs.second,
                    c
                )
            }

        (sameSignCheck and sumWithCarry).commute.withAction {
                sc, sum -> MaybeOverflowCheck(sc, sum)
        }
    }

    private val carryOut = PatternMatcher.Pattern.Ite(
        PatternMatcher.Pattern.FromVar.anyVar,
        1().toPattern(),
        0().toPattern()
    ) { c, _, _, _ -> c }

    /**
     * Represents a match on (signExtend([v]) <= signExtend(-1)) (or signExtend(v) < 0)
     * If [pos] is False, then the condition is negated
     * (i.e. it is a match on !(signExtend([v]) <= signExtend(-1)) etc)
     */
    private data class CompareMatch(val v: Pair<LTACCmd, TACSymbol.Var>, val pos: Boolean)

    private val comparePattern: PatternMatcher.Pattern<CompareMatch> = PatternDSL.build {

        // SignExtend(7, x)
        val extendLhs = PatternMatcher.Pattern.FromBinOp.from(
            TACExpr.BinOp.SignExtend::class.java,
            7().toPattern(),
            PatternMatcher.Pattern.FromVar { cmd, v -> PatternMatcher.VariableMatch.Match(cmd to v) },
            { _, _, a, -> a }
        ).toBuildable()

        // SignExtend(7, 0xffff...ff)
        val extendNeg1 = PatternMatcher.Pattern.FromBinOp.from(
            TACExpr.BinOp.SignExtend::class.java,
            7().toPattern(),
            MASK_SIZE(64)().toPattern(),
            { _, _, _, -> }
        ).toBuildable()

        // SignExtend(7, 0)
        val extendZero = PatternMatcher.Pattern.FromBinOp.from(
            TACExpr.BinOp.SignExtend::class.java,
            7().toPattern(),
            0().toPattern(),
            { _, _, _, -> }
        ).toBuildable()

        // extendLhs s<= -1 (i.e., is it negative)
        val compare = (extendLhs sle extendNeg1).withAction { _, v, _ -> CompareMatch(v, true) } lor
            (extendLhs slt extendZero).withAction { _, v, _ -> (CompareMatch(v, true)) }

        val ite = PatternMatcher.Pattern.Ite(
            compare.toPattern(),
            1().toPattern(),
            0().toPattern(),
        ) { _, v, _, _ -> v }.toBuildable()

        compare lor
            (ite `==` 0()).commute.withAction { v, _ -> v.copy(pos = false) } lor
            ((ite and 1()) `==` 0()).commute.withAction { v, _ -> v.first.copy(pos = false) }

    }
}

/**
 * (declare-const bvHI_0 (_ BitVec 64))
 * (declare-const bvHI_1 (_ BitVec 64))
 * (declare-const bvCARRYOUT (_ BitVec 64))
 *
 * (assert (or (= bvCARRYOUT #x0000000000000000) (= bvCARRYOUT #x0000000000000001)))
 *
 * (declare-const C1 (_ BitVec 64))
 * (declare-const C2 (_ BitVec 64))
 * (declare-const OFBitCheck Bool)
 *
 * (assert (= C1 (bvxor (bvxor bvHI_0 bvHI_1) #xffffffffffffffff)))
 * (assert (= C2 (bvxor bvHI_0 (bvadd (bvadd bvHI_0 bvHI_1) bvCARRYOUT))))
 * (assert (= OFBitCheck (bvslt (bvand C1 C2) #x0000000000000000)))
 *
 * (declare-const ExtendedSum (_ BitVec 256))
 * (assert (= ExtendedSum
 *            ((_ zero_extend 192) (bvadd (bvadd bvHI_0 bvHI_1) bvCARRYOUT))))
 *
 * (declare-const LowerBound Bool)
 * (declare-const UpperBound Bool)
 * (declare-const InRange Bool)
 * (declare-const SameSign Bool)
 * (declare-const Direct Bool)
 *
 * (assert (= SameSign (= (bvslt bvHI_1 (_ bv0 64)) (bvslt bvHI_0 (_ bv0 64)))))
 * (assert (= Direct (and SameSign
 *                        (not (= (bvslt bvHI_1 (_ bv0 64))
 *                                (bvuge ExtendedSum ((_ zero_extend 192) #x8000000000000000)))))))
 *
 *
 * (assert (distinct OFBitCheck Direct))
 * (check-sat) ;; UNSAT
 * (get-model)
 */
