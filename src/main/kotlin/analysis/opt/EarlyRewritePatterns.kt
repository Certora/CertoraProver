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

import analysis.opt.PatternRewriter.Key.*
import analysis.split.Ternary.Companion.isPowOf2Minus1
import datastructures.stdcollections.*
import utils.*
import utils.ModZm.Companion.lowOnes
import vc.data.TACExpr
import vc.data.asTACExpr
import java.math.BigInteger


/**
 * Some clean up early on. Necessary for overflow pattern detection, but not only.
 * A few patterns here make sure there are no `Ge` and `Gt`, no negated `Le`, and `Lt`, and the same for the signed
 * versions. This normalizes our expressions, making them easier to pattern match.
 */
fun PatternRewriter.earlyPatternsList() = listOf(

    /** `x - y > 0` ~~> `x != y` */
    PatternHandler(
        name = "nonEq",
        pattern = {
            (lSym256(A) - lSym256(B)) gt c(0)
        },
        handle = {
            LNot(Eq(sym(A), sym(B)))
        },
        TACExpr.BinRel.Gt::class.java
    ),


    /** `x & 0xffff == x` ~~> `x <= 0xffff` */
    PatternHandler(
        name = "maskBoundCheck",
        pattern = {
            (lSym(A) bwAnd c(C1, p = { it > BigInteger.ZERO && it.isPowOf2Minus1 })) eq
                lSym(B)
        },
        handle = {
            runIf(src(A) == src(B)) {
                Le(sym(A), C1.asTACExpr)
            }
        },
        TACExpr.BinRel.Eq::class.java
    ),


    /**
     * `a >> k == 0  ~~~>  a <= 2^k - 1`
     * that's how vyper does it.
     */
    PatternHandler(
        name = "vyper-size-check",
        pattern = {
            (lSym256(A) shr c(I1) { it in 0..255 }) eq zero
        },
        handle = {
            Le(sym(A), lowOnes(I1.n).asTACExpr)
        },
        TACExpr.BinRel.Eq::class.java
    ),

    /**
     * `a >> k > 0  ~~~>  a > 2^k - 1`
     * the negated version.
     */
    PatternHandler(
        name = "vyper-size-check2",
        pattern = {
            (lSym256(A) shr c(I1) { it in 0..255 }) gt zero
        },
        handle = {
            Gt(sym(A), lowOnes(I1.n).asTACExpr)
        },
        TACExpr.BinRel.Gt::class.java
    ),

    /**
     * `0 - a == c`  ~~~>  a == 0 - c`
     */
    PatternHandler(
        name = "vyper-zero-minus",
        pattern = {
            (zero - lSym256(A)) eq c(C1, SignUtilities::isInUnsignedBounds)
        },
        handle = {
            Eq(sym(A), (0 - C1.n).asTACExpr)
        },
        TACExpr.BinRel.Eq::class.java
    ),


    /**
     * `bwNot(a) == const  ~~~>  a == bwNot(const)`
     * vyper uses it sometimes.
     */
    PatternHandler(
        name = "vyper-bwNot-comparison",
        pattern = {
            bwNot(lSym256(A)) eq c(C1)
        },
        handle = {
            runIf(SignUtilities.isInUnsignedBounds(C1.n)) {
                Eq(sym(A), EVMOps.bwNot(C1.n).asTACExpr)
            }
        },
        TACExpr.BinRel.Eq::class.java
    ),


    /**
     * `a bwOr b > 0`  ~~~>  a > 0 || b > 0`
     * vyper uses it sometimes.
     */
    PatternHandler(
        name = "vyper-bwNot-comparison",
        pattern = {
            (lSym256(A) bwOr lSym256(B)) gt zero
        },
        handle = {
            LOr(
                Gt(sym(A), Zero),
                Gt(sym(B), Zero),
            )
        },
        TACExpr.BinRel.Gt::class.java
    ),

    /** `a > b`  ~~~>  a < b` */
    PatternHandler(
        name = "gtToLt",
        pattern = { lSym256(A) gt lSym256(B) },
        handle = { Lt(sym(B), sym(A)) },
        TACExpr.BinRel.Gt::class.java
    ),
    /** `a >= b`  ~~~>  a <= b` */
    PatternHandler(
        name = "geToLe",
        pattern = { lSym256(A) ge lSym256(B) },
        handle = { Le(sym(B), sym(A)) },
        TACExpr.BinRel.Ge::class.java
    ),
    /** `!(a < b)`  ~~~>  b <= a` */
    PatternHandler(
        name = "notLtToLe",
        pattern = { !(lSym256(A) lt lSym256(B)) },
        handle = { Le(sym(B), sym(A)) },
        TACExpr.UnaryExp.LNot::class.java
    ),
    /** `!(a <= b)`  ~~~>  b < a` */
    PatternHandler(
        name = "notLeToLt",
        pattern = { !(lSym256(A) le lSym256(B)) },
        handle = { Lt(sym(B), sym(A)) },
        TACExpr.UnaryExp.LNot::class.java
    ),


    /** signed version of `a > b`  ~~~>  a < b` */
    PatternHandler(
        name = "gtToLt",
        pattern = { lSym256(A) sGt lSym256(B) },
        handle = { Slt(sym(B), sym(A)) },
        TACExpr.BinRel.Sgt::class.java
    ),
    /** signed version of `a >= b`  ~~~>  a <= b` */
    PatternHandler(
        name = "signed-geToLe",
        pattern = { lSym256(A) sGe lSym256(B) },
        handle = { Sle(sym(B), sym(A)) },
        TACExpr.BinRel.Sge::class.java
    ),
    /** signed version of `!(a < b)`  ~~~>  b <= a` */
    PatternHandler(
        name = "signed-notLtToLe",
        pattern = { !(lSym256(A) sLt lSym256(B)) },
        handle = { Sle(sym(B), sym(A)) },
        TACExpr.UnaryExp.LNot::class.java
    ),

    /** signed version of `!(a <= b)`  ~~~>  b < a` */
    PatternHandler(
        name = "signed-notLeToLt",
        pattern = { !(lSym256(A) sLe lSym256(B)) },
        handle = { Slt(sym(B), sym(A)) },
        TACExpr.UnaryExp.LNot::class.java
    ),

)
