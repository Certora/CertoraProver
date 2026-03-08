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

import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.analysis.GenericScalarAnalysis

private val sbfTypesFac = ConstantSbfTypeFactory()
private val globals = GlobalVariables(DefaultElfFileView)
private val memSummaries = MemorySummaries()

class KnownBitsDomainTest {

    private fun runProver(cfg: SbfCFG) = AnalysisProver(
        sbfTypesFac,
        GenericScalarAnalysis(
            cfg,
            globals,
            memSummaries,
            sbfTypesFac,
            ScalarKnownBitsDomainFactory()
        )
    )

    @Test
    fun `supported pattern from customer code`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r10[-2088, 2] = 0
                r1 = r10[-2088]
                BinOp.AND(r1, 1UL)
                assume(CondOp.NE(r1, 0))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
            }
        }

        println("$cfg")

        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `unsupported pattern from customer code -- need backward analysis`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r0 = 1280
                br(CondOp.EQ(r1, 0), 1, 2)
            }
            bb(1) {
                goto(3)
            }
            bb(2) {
                BinOp.OR(r0, 1)  // by forward analysis low bit of r0 is 1
                                             // by backward analysis low bit of r0 is 0
                goto (3)
            }
            bb(3) {
                r8 = r0
                r1 = r8
                BinOp.AND(r1, 1UL) // by backward analysis low bit of r1 is 0
                assume(CondOp.EQ(r1, 0))
                assert(CondOp.EQ(r8, 1280))
            }
        }

        println("$cfg")

        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(false, check.result)
        }
    }

    @Test
    fun `AND with zero gives zero`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // r1 is an unknown argument register.
                // Regardless of its value, r1 AND 0 = 0.
                BinOp.AND(r1, 0UL)
                assume(CondOp.NE(r1, 0))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `OR sets a bit, AND confirms it is one`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // After OR with 1, bit 0 is definitely 1 regardless of the initial value.
                // After AND with 1, only bit 0 remains, and it must be 1.
                BinOp.OR(r1, 1UL)
                BinOp.AND(r1, 1UL)
                assume(CondOp.NE(r1, 1))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `AND with complementary masks gives zero`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // After AND(r1, 0xFF): KnownBits knows the high 56 bits are 0.
                // After AND(result, 0xFF00): KnownBits knows the low 8 bits are also 0 -> result is 0.
                BinOp.AND(r1, 0xFFUL)
                BinOp.AND(r1, 0xFF00UL)
                assume(CondOp.NE(r1, 0))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `XOR of two constants`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0b1100   // 12
                BinOp.XOR(r1, 0b1010UL)  // 12 XOR 10 = 6
                assume(CondOp.NE(r1, 6))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `left shift fills low bits with zeros`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.LSH(r1, 3)
                BinOp.AND(r1, 7UL)
                assume(CondOp.NE(r1, 0))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `logical right shift fills high bits with zeros`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // After logical right shift by 61, bits 3-63 are known to be 0.
                // AND with the top 3 bits (7 shl 61) isolates those bits -> result is 0.
                BinOp.RSH(r1, 61)
                BinOp.AND(r1, 7UL shl 61)
                assume(CondOp.NE(r1, 0))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `arithmetic right shift propagates known sign bit`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // Set the sign bit (bit 63) via OR -> sign bit is definitely 1.
                // After arithmetic right shift by 1, the sign bit propagates -> bits 62 and 63 are both 1.
                // AND with the sign bit confirms bit 63 is 1.
                BinOp.OR(r1, 0x8000000000000000UL)   // sign bit known 1
                BinOp.ARSH(r1, 1)                     // sign bit propagates -> bits 62,63 known 1
                BinOp.AND(r1, 0x8000000000000000UL)   // isolate sign bit -> must be 1
                assume(CondOp.NE(r1, 0x8000000000000000UL))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `join preserves bits known on all branches`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }
            bb(1) {
                r0 = 100  // 0b01100100: bit 6 (= 64) is set
                goto(3)
            }
            bb(2) {
                r0 = 200  // 0b11001000: bit 6 (= 64) is set
                goto(3)
            }
            bb(3) {
                // After join: scalar domain loses the exact value (r0 = top),
                // but KnownBits preserves bits known on ALL paths.
                // join.ones = 100 & 200 = 64 -> bit 6 is known to be 1.
                BinOp.AND(r0, 64UL)
                assume(CondOp.NE(r0, 64))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `ADD preserves trailing zeros - min of both operands`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // r1 << 2 gives 2 trailing zeros; r2 << 3 gives 3 trailing zeros.
                // r1 + r2 has at least min(2, 3) = 2 trailing zeros.
                // AND with 3 (0b11) isolates the low 2 bits -> must be 0.
                BinOp.LSH(r1, 2)
                BinOp.LSH(r2, 3)
                BinOp.ADD(r1, r2)
                BinOp.AND(r1, 3UL)
                assume(CondOp.NE(r1, 0))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `MUL accumulates trailing zeros from both operands`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // r1 is unknown (0 trailing zeros known); 8 has 3 trailing zeros.
                // r1 * 8 has at least 0 + 3 = 3 trailing zeros.
                // AND with 7 (0b111) isolates the low 3 bits -> must be 0.
                BinOp.MUL(r1, 8UL)
                BinOp.AND(r1, 7UL)
                assume(CondOp.NE(r1, 0))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `SUB preserves trailing zeros - min of both operands`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // r1 << 3 gives 3 trailing zeros; r2 << 2 gives 2 trailing zeros.
                // r1 - r2 = add(r1, neg(r2)); neg preserves trailing zeros.
                // Result has at least min(3, 2) = 2 trailing zeros.
                // AND with 3 (0b11) isolates the low 2 bits -> must be 0.
                BinOp.LSH(r1, 3)
                BinOp.LSH(r2, 2)
                BinOp.SUB(r1, r2)
                BinOp.AND(r1, 3UL)
                assume(CondOp.NE(r1, 0))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `zero extension when loading wider than stored width`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r10[-10, 1] = 0xAB    // store 1 byte: value 0xAB
                r1 = r10[-10, 2]      // load 2 bytes: KnownBits zero-extends 0x**AB
                assume(CondOp.NE(r1, 0xAB))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false)
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(false, check.result)
        }
    }

    @Test
    fun `zero extension when loading wider than stored width and mask lowest byte`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r10[-10, 1] = 0xAB    // store 1 byte: value 0xAB
                r1 = r10[-10, 2]      // load 2 bytes: KnownBits zero-extends 0x**AB
                BinOp.AND(r1, 0xFF)
                assume(CondOp.NE(r1, 0xAB))
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                exit()
            }
        }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `domain too imprecise for pointer alignment`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = 8
                "__rust_alloc"()
                r1 = r0
                BinOp.AND(r1, 7)
                assume(CondOp.EQ(r1, 0))
                r2 = r0
                BinOp.AND(r2, 7)
                br(CondOp.EQ(r2, 0), 1, 2)
            }
            bb(1) {
                // r2 is aligned
                goto(3)
            }
            bb(2) {
                r6 = 1
                assert(CondOp.EQ(r6, 0)) // assert(false) should be unreachable
                goto(3)
            }
            bb(3) {
                exit()
            }
         }
        println("$cfg")
        runProver(cfg).checks.forEach { check ->
            Assertions.assertEquals(false, check.result)
        }
    }
}
