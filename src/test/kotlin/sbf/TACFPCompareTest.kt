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

package sbf

import config.*
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.booleanArrayOf

class TACFPCompareTest {

    companion object {
        // IEEE 754 f64 bit patterns
        private val f64NaN      = 0x7FF8_0000_0000_0000UL  // some NaN
        private val f64Zero     = 0x0000_0000_0000_0000UL  // +0.0
        private val f64One      = 0x3FF0_0000_0000_0000UL  // 1.0
        private val f64Two      = 0x4000_0000_0000_0000UL  // 2.0
        private val f64Five     = 0x4014_0000_0000_0000UL  // 5.0

        /** A comparison routine, the relation it computes, and the sign conditions on `r0` meaning true and false. */
        enum class FPCompare(
            val fn: String,
            val holds: (Double, Double) -> Boolean,
            val onTrue: CondOp,
            val onFalse: CondOp
        ) {
            LT("__ltdf2", { a, b -> a < b }, CondOp.SLT, CondOp.SGE),
            LE("__ledf2", { a, b -> a <= b }, CondOp.SLE, CondOp.SGT),
            GE("__gedf2", { a, b -> a >= b }, CondOp.SGE, CondOp.SLT),
            GT("__gtdf2", { a, b -> a > b }, CondOp.SGT, CondOp.SLE),
        }

        @JvmStatic
        fun zeroOperandCases(): List<Arguments> {
            val operands = listOf(0.0, -0.0, 5.0, -5.0, Double.NaN)
            return FPCompare.entries.flatMap { cmp ->
                operands.flatMap { a ->
                    operands
                        .filter { b -> a == 0.0 || b == 0.0 }
                        .flatMap { b -> listOf(true, false).map { dual -> Arguments.of(cmp, a, b, dual) } }
                }
            }
        }
    }

    // region __unorddf2

    @Test
    fun `unorddf2 NaN vs normal returns 1`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test1") {
                bb(0) {
                    // 0x7FF8000000000000 which is a nan
                    // The exponent is 7FF (all 1s)
                    // mantisa is 8000000000000 which is non-zero
                    r1 = 9221120237041090560
                    // r2 a floating point with bit pattern 101 which is not nan
                    r2 = 5
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }
            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `unorddf2 normal vs normal returns 0`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test2") {
                bb(0) {
                    // r1 a floating point with bit pattern 1100 which is not nan
                    r1 = 12
                    // r2 a floating point with bit pattern 101 which is not nan
                    r2 = 5
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 0))
                    exit()
                }
            }
            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    // endregion

    // region __eqdf2
    // Returns 0 if arg1 == arg2, nonzero otherwise.

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `eqdf2 equal constants returns 0`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64One
                    r2 = f64One
                    "__eqdf2"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `eqdf2 different constants returns nonzero`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64One
                    r2 = f64Two
                    "__eqdf2"()
                    assert(CondOp.NE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `eqdf2 with dual encoding equal integers returns 0`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 5UL
                    "__floatundidf"()
                    r7 = r0              // f64(5)
                    r1 = r6
                    r2 = r7
                    "__eqdf2"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `eqdf2 with dual encoding different integers returns nonzero`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 7UL
                    "__floatundidf"()
                    r7 = r0              // f64(7)
                    r1 = r6
                    r2 = r7
                    "__eqdf2"()
                    assert(CondOp.NE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    // endregion

    // region __nedf2
    // Returns nonzero if arg1 != arg2, 0 otherwise.

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `nedf2 different constants returns nonzero`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64One
                    r2 = f64Two
                    "__nedf2"()
                    assert(CondOp.NE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `nedf2 equal constants returns 0`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64One
                    r2 = f64One
                    "__nedf2"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `nedf2 with dual encoding different integers returns nonzero`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 7UL
                    "__floatundidf"()
                    r7 = r0              // f64(7)
                    r1 = r6
                    r2 = r7
                    "__nedf2"()
                    assert(CondOp.NE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `nedf2 with dual encoding equal integers returns 0`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 5UL
                    "__floatundidf"()
                    r7 = r0              // f64(5)
                    r1 = r6
                    r2 = r7
                    "__nedf2"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    // endregion

    // region __ltdf2
    // Returns signed < 0 if arg1 < arg2, signed >= 0 otherwise.

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `ltdf2 zero lt zero returns non-negative`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Zero
                    r2 = f64Zero
                    "__ltdf2"()
                    assert(CondOp.SGE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `ltdf2 one lt zero returns non-negative`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64One
                    r2 = f64Zero
                    "__ltdf2"()
                    assert(CondOp.SGE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `ltdf2 with dual encoding less than returns negative`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 3UL
                    "__floatundidf"()
                    r6 = r0              // f64(3)
                    r1 = 5UL
                    "__floatundidf"()
                    r7 = r0              // f64(5)
                    r1 = r6
                    r2 = r7
                    "__ltdf2"()
                    assert(CondOp.SLT(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `ltdf2 with dual encoding greater than returns non-negative`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 3UL
                    "__floatundidf"()
                    r7 = r0              // f64(3)
                    r1 = r6
                    r2 = r7
                    "__ltdf2"()
                    assert(CondOp.SGE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    // endregion

    // region __ledf2
    // Returns signed <= 0 if arg1 <= arg2, signed > 0 otherwise.

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `ledf2 zero le zero returns non-positive`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Zero
                    r2 = f64Zero
                    "__ledf2"()
                    assert(CondOp.SLE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `ledf2 one le zero returns positive`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64One
                    r2 = f64Zero
                    "__ledf2"()
                    assert(CondOp.SGT(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `ledf2 with dual encoding less than returns non-positive`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 3UL
                    "__floatundidf"()
                    r6 = r0              // f64(3)
                    r1 = 5UL
                    "__floatundidf"()
                    r7 = r0              // f64(5)
                    r1 = r6
                    r2 = r7
                    "__ledf2"()
                    assert(CondOp.SLE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `ledf2 with dual encoding equal returns non-positive`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 5UL
                    "__floatundidf"()
                    r7 = r0              // f64(5)
                    r1 = r6
                    r2 = r7
                    "__ledf2"()
                    assert(CondOp.SLE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `ledf2 with dual encoding greater than returns positive`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 3UL
                    "__floatundidf"()
                    r7 = r0              // f64(3)
                    r1 = r6
                    r2 = r7
                    "__ledf2"()
                    assert(CondOp.SGT(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    // endregion

    // region __gedf2
    // Returns signed >= 0 if arg1 >= arg2, signed < 0 otherwise.

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `gedf2 zero ge zero returns non-negative`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Zero
                    r2 = f64Zero
                    "__gedf2"()
                    assert(CondOp.SGE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `gedf2 one ge zero returns non-negative`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Five
                    r2 = f64Zero
                    "__gedf2"()
                    assert(CondOp.SGE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `gedf2 with dual encoding greater than returns non-negative`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 3UL
                    "__floatundidf"()
                    r7 = r0              // f64(3)
                    r1 = r6
                    r2 = r7
                    "__gedf2"()
                    assert(CondOp.SGE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `gedf2 with dual encoding equal returns non-negative`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 5UL
                    "__floatundidf"()
                    r7 = r0              // f64(5)
                    r1 = r6
                    r2 = r7
                    "__gedf2"()
                    assert(CondOp.SGE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `gedf2 with dual encoding less than returns negative`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 3UL
                    "__floatundidf"()
                    r6 = r0              // f64(3)
                    r1 = 5UL
                    "__floatundidf"()
                    r7 = r0              // f64(5)
                    r1 = r6
                    r2 = r7
                    "__gedf2"()
                    assert(CondOp.SLT(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    // endregion

    // region __gtdf2
    // Returns signed > 0 if arg1 > arg2, signed <= 0 otherwise.
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `gtdf2 zero gt zero returns non-positive`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Zero
                    r2 = f64Zero
                    "__gtdf2"()
                    assert(CondOp.SLE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `gtdf2 one gt zero returns positive`(useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Five
                    r2 = f64Zero
                    "__gtdf2"()
                    assert(CondOp.SGT(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `gtdf2 with dual encoding greater than returns positive`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5UL
                    "__floatundidf"()
                    r6 = r0              // f64(5)
                    r1 = 3UL
                    "__floatundidf"()
                    r7 = r0              // f64(3)
                    r1 = r6
                    r2 = r7
                    "__gtdf2"()
                    assert(CondOp.SGT(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `gtdf2 with dual encoding less than returns non-positive`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 3UL
                    "__floatundidf"()
                    r6 = r0              // f64(3)
                    r1 = 5UL
                    "__floatundidf"()
                    r7 = r0              // f64(5)
                    r1 = r6
                    r2 = r7
                    "__gtdf2"()
                    assert(CondOp.SLE(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    // endregion

    // region comparisons with a zero operand

    @ParameterizedTest(name = "{0}({1}, {2}) dual={3}")
    @MethodSource("zeroOperandCases")
    fun `comparison with a zero operand`(cmp: FPCompare, a: Double, b: Double, useDualEncoding: Boolean) {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, useDualEncoding).use {
            val expected = if (cmp.holds(a, b)) { cmp.onTrue } else { cmp.onFalse }
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = a.toRawBits().toULong()
                    r2 = b.toRawBits().toULong()
                    cmp.fn()
                    assert(expected(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    // endregion
}
