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

package wasm

import annotations.TestTags.EXPENSIVE
import net.jqwik.api.*
import net.jqwik.api.constraints.IntRange
import net.jqwik.kotlin.api.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import wasm.host.NullHost
import wasm.wat.*

@net.jqwik.api.Tag(EXPENSIVE)
@org.junit.jupiter.api.Tag(EXPENSIVE)
class IntTest : WasmTestFixture() {
    override val host = NullHost

    @Property(tries = 16)
    fun `i32 add signed`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a + $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) + i32(b)) eq i32(a + b))
        })
    }

    @Property(tries = 16)
    fun `i32 add unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a + $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) + u32(b)) eq u32(a + b))
        })
    }

    @Property(tries = 16)
    fun `i64 add signed`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a + $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) + i64(b)) eq i64(a + b))
        })
    }

    @Property(tries = 16)
    fun `i64 add unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a + $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) + u64(b)) eq u64(a + b))
        })
    }

    @Property(tries = 16)
    fun `i32 sub signed`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a - $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) - i32(b)) eq i32(a - b))
        })
    }

    @Property(tries = 16)
    fun `i32 sub unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a - $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) - u32(b)) eq u32(a - b))
        })
    }

    @Property(tries = 16)
    fun `i64 sub signed`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a - $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) - i64(b)) eq i64(a - b))
        })
    }

    @Property(tries = 16)
    fun `i64 sub unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a - $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) - u64(b)) eq u64(a - b))
        })
    }

    @Property(tries = 16)
    fun `i32 mul signed`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a * $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) * i32(b)) eq i32(a * b))
        })
    }

    @Property(tries = 16)
    fun `i32 mul unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a * $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) * u32(b)) eq u32(a * b))
        })
    }

    @Property(tries = 16)
    fun `i64 mul signed`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a * $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) * i64(b)) eq i64(a * b))
        })
    }

    @Property(tries = 16)
    fun `i64 mul unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a * $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) * u64(b)) eq u64(a * b))
        })
    }

    @Property(tries = 16)
    fun `i32 div signed`(@ForAll a: Int, @ForAll b: Int) {
        Assume.that(b != 0)
        println("Testing $a / $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) / i32(b)) eq i32(a / b))
        })
    }

    @Property(tries = 16)
    fun `i32 div unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        Assume.that(bi != 0)
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a / $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) / u32(b)) eq u32(a / b))
        })
    }

    @Property(tries = 16)
    fun `i64 div signed`(@ForAll a: Long, @ForAll b: Long) {
        Assume.that(b != 0L)
        println("Testing $a / $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) / i64(b)) eq i64(a / b))
        })
    }

    @Property(tries = 16)
    fun `i64 div unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        Assume.that(bi != 0L)
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a / $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) / u64(b)) eq u64(a / b))
        })
    }

    @Property(tries = 16)
    fun `i32 rem signed`(@ForAll a: Int, @ForAll b: Int) {
        Assume.that(b != 0)
        println("Testing $a % $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) % i32(b)) eq i32(a % b))
        })
    }

    @Property(tries = 16)
    fun `i32 rem unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        Assume.that(bi != 0)
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a % $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) % u32(b)) eq u32(a % b))
        })
    }

    @Property(tries = 16)
    fun `i64 rem signed`(@ForAll a: Long, @ForAll b: Long) {
        Assume.that(b != 0L)
        println("Testing $a % $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) % i64(b)) eq i64(a % b))
        })
    }

    @Property(tries = 16)
    fun `i64 rem unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        Assume.that(bi != 0L)
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a % $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) % u64(b)) eq u64(a % b))
        })
    }

    @Property(tries = 16)
    fun `i32 and`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a and $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) and i32(b)) eq i32(a and b))
        })
    }

    @Property(tries = 16)
    fun `i64 and`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a and $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) and i64(b)) eq i64(a and b))
        })
    }

    @Property(tries = 16)
    fun `i32 or`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a or $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) or i32(b)) eq i32(a or b))
        })
    }

    @Property(tries = 16)
    fun `i64 or`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a or $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) or i64(b)) eq i64(a or b))
        })
    }

    @Property(tries = 16)
    fun `i32 xor`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a xor $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) xor i32(b)) eq i32(a xor b))
        })
    }

    @Property(tries = 16)
    fun `i64 xor`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a xor $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) xor i64(b)) eq i64(a xor b))
        })
    }

    @Property(tries = 16)
    fun `i32 shl`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a shl $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) shl i32(b)) eq i32(a shl b))
        })
    }

    @Property(tries = 16)
    fun `i64 shl`(@ForAll a: Long, @ForAll b: Int) {
        println("Testing $a shl $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) shl i64(b.toLong())) eq i64(a shl b))
        })
    }

    @Property(tries = 16)
    fun `i32 shr signed`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a shr $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) shr i32(b)) eq i32(a shr b))
        })
    }

    @Property(tries = 16)
    fun `i32 shr unsigned`(@ForAll ai: Int, @ForAll b: Int) {
        val a = ai.toUInt()
        println("Testing $a shr $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) shr u32(b.toUInt())) eq u32(a shr b))
        })
    }

    @Property(tries = 16)
    fun `i64 shr signed`(@ForAll a: Long, @ForAll b: Int) {
        println("Testing $a shr $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) shr i64(b.toLong())) eq i64(a shr b))
        })
    }

    @Property(tries = 16)
    fun `i64 shr unsigned`(@ForAll ai: Long, @ForAll b: Int) {
        val a = ai.toULong()
        println("Testing $a shr $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) shr u64(b.toULong())) eq u64(a shr b))
        })
    }

    @Property(tries = 16)
    fun `i32 lt signed`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a < $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) lt i32(b)) eq i32(a < b))
        })
    }

    @Property(tries = 16)
    fun `i32 lt unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a < $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) lt u32(b)) eq i32(a < b))
        })
    }

    @Property(tries = 16)
    fun `i64 lt signed`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a < $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) lt i64(b)) eq i32(a < b))
        })
    }

    @Property(tries = 16)
    fun `i64 lt unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a < $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) lt u64(b)) eq i32(a < b))
        })
    }

    @Property(tries = 16)
    fun `i32 le signed`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a <= $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) le i32(b)) eq i32(a <= b))
        })
    }

    @Property(tries = 16)
    fun `i32 le unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a <= $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) le u32(b)) eq i32(a <= b))
        })
    }

    @Property(tries = 16)
    fun `i64 le signed`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a <= $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) le i64(b)) eq i32(a <= b))
        })
    }

    @Property(tries = 16)
    fun `i64 le unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a <= $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) le u64(b)) eq i32(a <= b))
        })
    }

    @Property(tries = 16)
    fun `i32 gt signed`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a > $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) gt i32(b)) eq i32(a > b))
        })
    }

    @Property(tries = 16)
    fun `i32 gt unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a > $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) gt u32(b)) eq i32(a > b))
        })
    }

    @Property(tries = 16)
    fun `i64 gt signed`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a > $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) gt i64(b)) eq i32(a > b))
        })
    }

    @Property(tries = 16)
    fun `i64 gt unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a > $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) gt u64(b)) eq i32(a > b))
        })
    }

    @Property(tries = 16)
    fun `i32 ge signed`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a >= $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) ge i32(b)) eq i32(a >= b))
        })
    }

    @Property(tries = 16)
    fun `i32 ge unsigned`(@ForAll ai: Int, @ForAll bi: Int) {
        val a = ai.toUInt()
        val b = bi.toUInt()
        println("Testing $a >= $b")
        assertTrue(verifyWasm {
            certoraAssert((u32(a) ge u32(b)) eq i32(a >= b))
        })
    }

    @Property(tries = 16)
    fun `i64 ge signed`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a >= $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) ge i64(b)) eq i32(a >= b))
        })
    }

    @Property(tries = 16)
    fun `i64 ge unsigned`(@ForAll ai: Long, @ForAll bi: Long) {
        val a = ai.toULong()
        val b = bi.toULong()
        println("Testing $a >= $b")
        assertTrue(verifyWasm {
            certoraAssert((u64(a) ge u64(b)) eq i32(a >= b))
        })
    }

    @Property(tries = 16)
    fun `i32 eq`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a == $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) eq i32(b)) eq i32(a == b))
        })
    }

    @Property(tries = 16)
    fun `i64 eq`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a == $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) eq i64(b)) eq i32(a == b))
        })
    }

    @Property(tries = 16)
    fun `i32 ne`(@ForAll a: Int, @ForAll b: Int) {
        println("Testing $a != $b")
        assertTrue(verifyWasm {
            certoraAssert((i32(a) ne i32(b)) eq i32(a != b))
        })
    }

    @Property(tries = 16)
    fun `i64 ne`(@ForAll a: Long, @ForAll b: Long) {
        println("Testing $a != $b")
        assertTrue(verifyWasm {
            certoraAssert((i64(a) ne i64(b)) eq i32(a != b))
        })
    }

    @Property(tries = 16)
    fun `extend i32 to i64 signed`(@ForAll a: Int) {
        println("Testing $a to i64")
        assertTrue(verifyWasm {
            certoraAssert(i32(a).to(i64) eq i64(a.toLong()))
        })
    }

    @Property(tries = 16)
    fun `extend i32 to i64 unsigned`(@ForAll ai: Int) {
        val a = ai.toUInt()
        println("Testing $a to i64")
        assertTrue(verifyWasm {
            certoraAssert(u32(a).to(u64) eq u64(a.toULong()))
        })
    }

    @Property(tries = 16)
    fun `wrap i64 to i32 signed`(@ForAll a: Long) {
        println("Testing $a to i32")
        assertTrue(verifyWasm {
            certoraAssert(i64(a).to(i32) eq i32(a.toInt()))
        })
    }

    @Property(tries = 16)
    fun `wrap i64 to i32 unsigned`(@ForAll ai: Long) {
        val a = ai.toULong()
        println("Testing $a to i32")
        assertTrue(verifyWasm {
            certoraAssert(u64(a).to(u32) eq u32(a.toUInt()))
        })
    }

    @Test
    fun `nondet u32 arg is in range`() {
        assertTrue(verifyWasm(
            watFunc("entry", u32) { i ->
                certoraAssert(i le u32(UInt.MAX_VALUE))
            },
            "entry"
        ))
    }

    @Test
    fun `nondet u32 call is in range`() {
        val fake = WatImport("fake", "fake", result = u32)
        assertTrue(verifyWasm(allowUnresolvedCalls = true) {
            certoraAssert(fake() le u32(UInt.MAX_VALUE))
        })
    }

    @Test
    fun `nondet u64 arg is in range`() {
        assertTrue(verifyWasm(
            watFunc("entry", u64) { i ->
                certoraAssert(i le u64(ULong.MAX_VALUE))
            },
            "entry"
        ))
    }

    @Test
    fun `nondet u64 call is in range`() {
        val fake = WatImport("fake", "fake", result = u64)
        assertTrue(verifyWasm(allowUnresolvedCalls = true) {
            certoraAssert(fake() le u64(ULong.MAX_VALUE))
        })
    }
}
