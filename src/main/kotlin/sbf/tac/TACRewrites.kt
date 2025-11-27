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

package sbf.tac

import analysis.opt.PatternRewriter
import analysis.opt.PatternRewriter.Key.*
import config.Config
import datastructures.stdcollections.*
import evm.twoToThe
import tac.Tag
import utils.*
import utils.ModZm.Companion.lowOnes
import vc.data.TACExpr
import vc.data.asTACExpr
import java.math.BigInteger

fun PatternRewriter.solanaPatternsList() = listOf(

    /**
     * `x xor y == 0` ~~> `x == y`
     */
    PatternHandler(
        name = "xor-eq-0",
        pattern = {
            (lSym(A) xor lSym(B)) eq zero
        },
        handle = {
            sym(A) eq sym(B)
        },
        TACExpr.BinRel.Eq::class.java
    ),

    /**
     * `x | y == 0` ~~> `x == 0 && y == 0`
     */
    PatternHandler(
        name = "bworEq0",
        pattern = {
            (lSym(A) bwOr lSym(B)) eq zero
        },
        handle = {
            LAnd(
                Eq(sym(A), Zero),
                Eq(sym(B), Zero),
            )
        },
        TACExpr.BinRel.Eq::class.java
    ),
) + runIf(Config.extraSolanaPatterns.get()) {
    listOf(
        /**
         * `2^n * (a >> n)`   ~~~>   `a & 0xff...ff0000`
         * if `a` is bv256. The number of 0 bits in the mask is `n`.
         */
        PatternHandler(
            name = "mul-shr",
            pattern = {
                c(C1) anyMul (lSym256(A) shr c(I1))
            },
            handle = {
                runIf(C1.n == twoToThe(I1.n)) {
                    BWAnd(sym(A), (Tag.Bit256.maxUnsigned - lowOnes(I1.n)).asTACExpr)
                }
            },
            TACExpr.Vec.IntMul.Binary::class.java, TACExpr.Vec.Mul::class.java
        ),


        /**
         * `a & mask1 + a & mask2` ~~~> `a`
         * if `a` is bv256, and `mask1` and `mask2` complement each other.
         */
        PatternHandler(
            name = "complement-masks",
            pattern = {
                maybeNarrow(c(C1) bwAnd lSym256(A)) anyAdd
                    maybeNarrow(c(C2) bwAnd lSym256(B))
            },
            handle = {
                runIf(
                    C1.n or C2.n == Tag.Bit256.maxUnsigned &&
                        C1.n and C2.n == BigInteger.ZERO &&
                        src(A) == src(B)
                ) {
                    sym(A)
                }
            },
            TACExpr.Vec.IntAdd.Binary::class.java, TACExpr.Vec.Add::class.java
        ),

        /**
         * `a * const1 / const2` ~~~>
         *    `a * (const1/const2)` if const2 is a divisor of const1
         *    `a / (const2/const1)` if const1 is a divisor of const2
         */
        PatternHandler(
            name = "div-mul-consts",
            pattern = {
                maybeNarrow(lSym(A) intMul c(C1)) intDiv c(C2)
            },
            handle = {
                when {
                    C1.n % C2.n == BigInteger.ZERO ->
                        IntMul(sym(A), (C1.n / C2.n).asTACExpr)

                    C2.n % C1.n == BigInteger.ZERO ->
                        IntDiv(sym(A), (C2.n / C1.n).asTACExpr)

                    else -> null
                }
            },
            TACExpr.BinOp.IntDiv::class.java
        ),


        /**
         * `safeMathNarrow(a:bv256)` ~~~> a:bv256
         */
        PatternHandler(
            name = "redundant-narrow",
            pattern = {
                safeMathNarrow(lSym256(A))
            },
            handle = {
                sym(A)
            },
            TACExpr.Apply.Unary::class.java
        ),

        /**
         * `safeMathNarrow(safeMathNarrow(x))` ~~~> x
         */
        PatternHandler(
            name = "redundant-narrow2",
            pattern = {
                safeMathNarrow(safeMathNarrow(lSym(A)))
            },
            handle = {
                safeMathNarrow(sym(A), Tag.Bit256)
            },
            TACExpr.Apply.Unary::class.java
        ),
    )
}.orEmpty()
