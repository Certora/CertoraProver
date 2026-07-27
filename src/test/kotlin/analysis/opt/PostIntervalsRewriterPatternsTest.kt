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

import analysis.opt.PatternRewriter.PatternHandler
import config.Config
import config.ConfigScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.asTACExpr

class PostIntervalsRewriterPatternsTest : TACBuilderAuxiliaries() {

    private fun checkStat(
        prog: TACProgramBuilder.BuiltTACProgram,
        stat: String,
        count: Int = 1,
        patterns: PatternRewriter.() -> List<PatternHandler> = PatternRewriter::postIntervalsRewriterPatternList
    ) {
        val stats = PatternRewriter.rewriteStats(prog.code, patterns)
        assertEquals(count, stats[stat])
    }

    /**
     * `A / B < C`  ~~>  `A < B*C`     when B is a non-zero constant.
     */
    @Test
    fun testDivLt() {
        val prog = TACProgramBuilder {
            d assign Div(aS, 5.asTACExpr)
            x assign Lt(dS, cS)
        }
        checkStat(prog, "divLt")
    }

    /**
     * `A / B <= C`  ~~>  `A < B*(C+1)`     when B is a non-zero constant.
     */
    @Test
    fun testDivLe() {
        val prog = TACProgramBuilder {
            d assign Div(aS, 5.asTACExpr)
            x assign Le(dS, cS)
        }
        checkStat(prog, "divLe")
    }

    /**
     * `A / B > C`  ~~>  `A >= B*(C+1)`     when B is a non-zero constant.
     */
    @Test
    fun testDivGt() {
        val prog = TACProgramBuilder {
            d assign Div(aS, 5.asTACExpr)
            x assign Gt(dS, cS)
        }
        checkStat(prog, "divGt")
    }

    /**
     * `A / B >= C`  ~~>  `A >= B*C`     when B is a non-zero constant.
     */
    @Test
    fun testDivGe() {
        val prog = TACProgramBuilder {
            d assign Div(aS, 5.asTACExpr)
            x assign Ge(dS, cS)
        }
        checkStat(prog, "divGe")
    }

    /**
     * `A / B == C`  ~~>  `B*C <= A < B*(C+1)`     when B is a non-zero constant.
     */
    @Test
    fun testDivEq() {
        ConfigScope(Config.PurifyConstDivisions, true).use {
            val prog = TACProgramBuilder {
                d assign Div(aS, 5.asTACExpr)
                x assign Eq(dS, cS)
            }
            checkStat(prog, "divEq")
        }
    }

    /**
     * Negative test: when the divisor is a plain variable (no `NON_ZERO_META`, not a non-zero const),
     * the rewrite must not fire.
     */
    @Test
    fun testDivLtNotRewrittenWhenDivisorMayBeZero() {
        val prog = TACProgramBuilder {
            d assign Div(aS, bS)
            x assign Lt(dS, cS)
        }
        checkStat(prog, "divLt", count = 0)
    }

    /**
     * The comparison identities are floor-division identities. [IntDiv] rounds toward zero, so none of them may
     * fire when its dividend can be negative.
     */
    @Test
    fun testIntDivComparisonsNotRewrittenWhenDividendMayBeNegative() {
        checkStat(TACProgramBuilder {
            k assign IntDiv(iS, number(5))
            x assign Lt(kS, jS)
        }, "divLt", count = 0)

        checkStat(TACProgramBuilder {
            k assign IntDiv(iS, number(5))
            x assign Le(kS, jS)
        }, "divLe", count = 0)

        checkStat(TACProgramBuilder {
            k assign IntDiv(iS, number(5))
            x assign Gt(kS, jS)
        }, "divGt", count = 0)

        checkStat(TACProgramBuilder {
            k assign IntDiv(iS, number(5))
            x assign Ge(kS, jS)
        }, "divGe", count = 0)

        ConfigScope(Config.PurifyConstDivisions, true).use {
            checkStat(TACProgramBuilder {
                k assign IntDiv(iS, number(5))
                x assign Eq(kS, jS)
            }, "divEq", count = 0)
        }
    }

    /**
     * Preserve the useful [IntDiv] optimization when the dividend is a known non-negative constant.
     */
    @Test
    fun testIntDivLtRewrittenWhenDividendIsNonNegative() {
        val prog = TACProgramBuilder {
            k assign IntDiv(number(7), number(5))
            x assign Lt(kS, jS)
        }
        checkStat(prog, "divLt")
    }
}
