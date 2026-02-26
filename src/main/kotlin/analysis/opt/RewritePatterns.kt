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

import analysis.numeric.MAX_UINT
import analysis.opt.PatternRewriter.Key.*
import analysis.patterns.get
import analysis.split.Ternary.Companion.isPowOf2
import datastructures.stdcollections.*
import evm.MIN_EVM_INT256_2S_COMPLEMENT
import tac.Tag
import utils.*
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.asTACExpr
import java.math.BigInteger

fun PatternRewriter.basicPatternsList() = listOf(

    /**
     * From the vyper compiler:
     *    `ite(cond, a xor b, 0) xor b` ~~> `ite(cond, a, b)`
     */
    PatternHandler(
        "xor1",
        pattern = {
            ite(
                lSym(Cond),
                lSym(A) xor lSym(B),
                c(0)
            ) xor lSym(Xored)
        },
        handle = {
            when (src(Xored)) {
                src(A) -> B to A
                src(B) -> A to B
                else -> null
            }?.let { (first, second) ->
                ite(sym(Cond), sym(first), sym(second))
            }
        },
        TACExpr.BinOp.BWXOr::class.java
    ),

    /**
     * From the vyper compiler:
     *    `x xor const1 == const2` ~~> `x == (const1 xor const2)`
     */
    PatternHandler(
        name = "xor2",
        pattern = {
            (lSym(A) xor c(C1)) eq c(C2)
        },
        handle = {
            sym(A) eq (C1.n xor C2.n).asTACExpr
        },
        TACExpr.BinRel.Eq::class.java
    ),


    /**
     * A combination of the above two:
     *    `ite(cond, a xor const1, b) == const2` ~~> `ite(cond, a == (const1 xor const2), b == const2)`
     */
    PatternHandler(
        "xor3",
        pattern = {
            ite(
                lSym(Cond),
                lSym(A) xor c(C1),
                lSym(B)
            ) eq c(C2)
        },
        handle = {
            ite(
                sym(Cond),
                Eq(sym(A), (C1.n xor C2.n).asTACExpr),
                Eq(sym(B), C2.asTACExpr)
            )
        },
        TACExpr.BinRel.Eq::class.java
    ),

    /**
     * From the vyper compiler:
     *    `x xor y == 0` ~~> `x == y`
     */
    PatternHandler(
        name = "xor4",
        pattern = {
            (lSym(A) xor lSym(B)) eq c(0)
        },
        handle = {
            sym(A) eq sym(B)
        },
        TACExpr.BinRel.Eq::class.java
    ),

    /** `x - y > 0` ~~> `x != y` */
    PatternHandler(
        name = "nonEqViaMinus",
        pattern = {
            OR(
                c(0) lt (lSym256(A) - lSym256(B)),
                (lSym256(A) - lSym256(B)) gt c(0)
            )
        },
        handle = {
            LNot(Eq(sym(A), sym(B)))
        },
        TACExpr.BinRel.Lt::class.java, TACExpr.BinRel.Gt::class.java
    ),

    /** `x - y == 0` ~~> `x == y` */
    PatternHandler(
        name = "EqViaMinus",
        pattern = {
            (lSym(A) - lSym(B)) eq c(0)
        },
        handle = {
            Eq(sym(A), sym(B))
        },
        TACExpr.BinRel.Eq::class.java
    ),

    /** `x > 0` ~~> `x != 0`  (also the `lt` version) */
    PatternHandler(
        name = "nonEq",
        pattern = {
            c(0) symmLt lSym256(A)
        },
        handle = {
            LNot(Eq(sym(A), Zero))
        },
        TACExpr.BinRel.Lt::class.java, TACExpr.BinRel.Gt::class.java
    ),

    /** `ite(!cond, a, b) ~~> ite(cond, b, a) */
    PatternHandler(
        name = "iteNot",
        pattern = {
            ite(!lSym(Cond), lSym(A), lSym(B))
        },
        handle = {
            Ite(sym(Cond), sym(B), sym(A))
        },
        TACExpr.TernaryExp.Ite::class.java
    ),

    /**
     * `!(a > b)` ~~> `a <= b`
     * or if `b == 0`, then `~~~> a == 0`
     * */
    PatternHandler(
        name = "notComparison1",
        pattern = {
            !(lSym256(A) gt lSym256(B))
        },
        handle = {
            if (info[B]!!.symbol.let { it is TACSymbol.Const && it.value == BigInteger.ZERO } &&
                info[A]!!.symbol.tag is Tag.Bits) {
                Eq(sym(A), Zero)
            } else {
                Le(sym(A), sym(B))
            }
        },
        TACExpr.UnaryExp.LNot::class.java
    ),

    /**
     * `!(a < b)` ~~> `a >= b`
     * or if `a == 0` then `~~~> b == 0`
     * */
    PatternHandler(
        name = "notComparison2",
        pattern = {
            !(lSym(A) lt lSym(B))
        },
        handle = {
            if (info[A]!!.symbol.let { it is TACSymbol.Const && it.value == BigInteger.ZERO } &&
                info[B]!!.symbol.tag is Tag.Bits) {
                Eq(sym(B), Zero)
            } else {
                Ge(sym(A), sym(B))
            }
        },
        TACExpr.UnaryExp.LNot::class.java
    ),

    /** `!(a >= b)` ~~> `a < b` */
    PatternHandler(
        name = "notComparison3",
        pattern = {
            !(lSym(A) ge lSym(B))
        },
        handle = {
            Lt(sym(A), sym(B))
        },
        TACExpr.UnaryExp.LNot::class.java
    ),

    /** `!(a <= b)` ~~> `a > b` */
    PatternHandler(
        name = "notComparison4",
        pattern = {
            !(lSym(A) le lSym(B))
        },
        handle = {
            Gt(sym(A), sym(B))
        },
        TACExpr.UnaryExp.LNot::class.java
    ),


    /**
     * `a <= const / a` ~~> `a <= sqrt(const)`
     * This is correct even when `a` is zero: `0 <= const / 0` is a tautology, and so is `0 <= sqrt(const)`.
     */
    PatternHandler(
        name = "sqrtByDiv1",
        pattern = {
            lSym256(A) symmLe (c(C1) / lSym256(B))
        },
        handle = {
            runIf(src(A) == src(B) && C1.n >= BigInteger.ZERO) {
                Le(sym(A), C1.n.sqrt().asTACExpr)
            }
        },
        TACExpr.BinRel.Le::class.java, TACExpr.BinRel.Ge::class.java
    ),

    /** `a > const / a` ~~> `a > sqrt(const)` (the negated version of the one above) */
    PatternHandler(
        name = "sqrtByDiv2",
        pattern = {
            lSym256(A) symmGt (c(C1) / lSym256(B))
        },
        handle = {
            runIf(src(A) == src(B) && C1.n >= BigInteger.ZERO) {
                Gt(sym(A), C1.n.sqrt().asTACExpr)
            }
        },
        TACExpr.BinRel.Lt::class.java, TACExpr.BinRel.Gt::class.java
    ),

    /**
     * `a >= const / a` ~~> `ite(a == 0, true, a >= sqrt(const))` ~~> `a==0 || a >= sqrt(const)`
     */
    PatternHandler(
        name = "sqrtByDiv3",
        pattern = {
            lSym256(A) symmGe (c(C1) / lSym256(B))
        },
        handle = {
            runIf(src(A) == src(B) && C1.n >= BigInteger.ZERO) {
                LOr(
                    Eq(sym(A), Zero),
                    Ge(sym(A), C1.n.sqrt().asTACExpr)
                )
            }
        },
        TACExpr.BinRel.Le::class.java, TACExpr.BinRel.Ge::class.java
    ),

    /**
     * `a < const / a` ~~> `ite(a == 0, false, a < sqrt(const))` ~~> `a!=0 && a < sqrt(const)`
     */
    PatternHandler(
        name = "sqrtByDiv4",
        pattern = {
            lSym256(A) symmLt (c(C1) / lSym256(B))
        },
        handle = {
            runIf(src(A) == src(B) && C1.n >= BigInteger.ZERO) {
                LAnd(
                    LNot(Eq(sym(A), Zero)),
                    Lt(sym(A), C1.n.sqrt().asTACExpr)
                )
            }
        },
        TACExpr.BinRel.Lt::class.java, TACExpr.BinRel.Gt::class.java
    ),

    /**
     * `(a / 2^k) * 2^k` ~~> `a - a & (2^k-1)`
     *
     * Without this rewrite rule, `Test/FromBugs/CERT4746CleanedLastWordStrings/run7.conf` fails. It replaces a LIA
     * axiom we had.
     */
    PatternHandler(
        name = "cleanLowBits",
        pattern = {
            (lSym256(A) / c(C1)) * c(C1, p = { it.isPowOf2 })
        },
        handle = {
            safeMathNarrow(
                IntSub(
                    sym(A),
                    BWAnd(sym(A), (C1.n - 1).asTACExpr)
                ),
                Tag.Bit256
            )
        },
        TACExpr.Vec.Mul::class.java
    ),

    /**
     * `!!a` ~~> `a`
     */
    PatternHandler(
        name = "not-not",
        pattern = {
            !!lSymTag<Tag.Bool>(A)
        },
        handle = {
            sym(A)
        },
        TACExpr.UnaryExp.LNot::class.java
    ),

    /**
     * `!!a` ~~> `a`
     * bitwise version
     */
    PatternHandler(
        name = "bwnot-bwnot",
        pattern = {
            bwNot(bwNot(lSym256(A)))
        },
        handle = {
            sym(A)
        },
        TACExpr.UnaryExp.BWNot::class.java
    ),


    /**
     * `x * y / x == y`  ~~~>  noUnsignedMulOverflow(x, y) /\ (x != 0 || y == 0)`
     * The second conjunct comes from the case where `x` is 0, and then while the multiplication does not overflow,
     * the original equation is false, unless `y` is 0 as well.
     *
     * These are manual patterns from old solidity versions. Here is an smt file proving this pattern:
     *
     * ```
     * (set-logic BV)
     *
     * (declare-fun x () (_ BitVec 8))
     * (declare-fun y () (_ BitVec 8))
     * (define-fun product () (_ BitVec 8) (bvmul x y))
     *
     * ; x * y / x == y
     * (define-fun overflow_pattern () Bool
     *   (ite (= x #x00)
     *        (= y #x00)
     *        (= (bvudiv product x) y)))
     *
     * ; noOverflow(x, y) /\ (x != 0 || y == 0)
     * (define-fun rewritten () Bool
     *   (and
     *     (not (bvumulo x y))
     *     (or (not (= x #x00)) (= y #x00))
     *   )
     * )
     *
     * (assert (not (= overflow_pattern rewritten)))
     * (check-sat)
     * ```
     */
    PatternHandler(
        name = "unsignedMulOverflow",
        pattern = {
            eq(
                (lSym256(A) * lSym256(B)) / lSym256(C),
                lSym256(D)
            )
        },
        handle = {
            when {
                src(A) == src(C) && src(B) == src(D) -> A to B
                src(A) == src(D) && src(B) == src(C) -> B to A
                else -> null
            }?.let { (x, y) ->
                LAnd(
                    noMulOverflow(sym(x), sym(y)),
                    LOr(
                        LNot(Eq(sym(x), Zero)),
                        Eq(sym(y), Zero),
                    )
                )
            }

        },
        TACExpr.BinRel.Eq::class.java,
        regressionMessage = true
    ),


    /**
     * `x * y /s x == y`  ~~~>  (noSignedMulOverflow(x, y) /\ (x != 0 || y == 0)) \/ (x == -1 /\ y == minInt)`
     * The signed version of the above.
     * the disjunct is because `minInt * -1 = minInt`, and this is considered an overflow, and yet,
     * `minInt / -1 = minInt`. Here's an smt file for proof:
     *
     * ```
     * (set-logic BV)
     *
     * (declare-fun x () (_ BitVec 8))
     * (declare-fun y () (_ BitVec 8))
     * (define-fun product () (_ BitVec 8) (bvmul x y))
     *
     * ; x * y /s x == y
     * (define-fun overflow_pattern () Bool
     *   (ite (= x #x00)
     *        (= y #x00)
     *        (= (bvsdiv product x) y)))
     *
     * ; (noOverflow(x, y) /\ (x != 0 || y == 0)) \/ (x == -1 /\ y == minInt)
     * (define-fun rewritten () Bool
     *   (or
     *     (and
     *        (not (bvsmulo x y))
     *        (or (not (= x #x00)) (= y #x00))
     *     )
     *     (and (= x #xff) (= y #x80))
     *   )
     * )
     *
     * (assert (not (= overflow_pattern rewritten)))
     * (check-sat)
     * ```
     */
    PatternHandler(
        name = "signedMulOverflow",
        pattern = {
            eq(
                (lSym256(A) * lSym256(B)) sDiv lSym256(C),
                lSym256(D)
            )
        },
        handle = {
            when {
                src(A) == src(C) && src(B) == src(D) -> A to B
                src(A) == src(D) && src(B) == src(C) -> B to A
                else -> null
            }?.let { (x, y) ->
                LOr(
                    LAnd(
                        noSMulOverAndUnderflow(sym(x), sym(y)),
                        LOr(
                            LNot(Eq(sym(x), Zero)),
                            Eq(sym(y), Zero),
                        )
                    ),
                    LAnd(
                        Eq(sym(x), MAX_UINT.asTACExpr),
                        Eq(sym(y), MIN_EVM_INT256_2S_COMPLEMENT.asTACExpr)
                    )
                )
            }

        },
        TACExpr.BinRel.Eq::class.java,
        regressionMessage = true
    ),

)


