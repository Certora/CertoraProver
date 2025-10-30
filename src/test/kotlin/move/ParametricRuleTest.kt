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

package move

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import utils.*

class ParametricRuleTest : MoveTestFixture() {
    @Test
    fun `passes for all targets`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
                cvlm::manifest::invoker(b"invoker");
            }
            public fun target1() {
                cvlm_assert(true);
            }
            public fun target2() {
                cvlm_assert(true);
            }
            public native fun invoker(f: Function);
            public fun test(f: Function) {
                invoker(f);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to true
            ),
            verifyMany()
        )
    }

    @Test
    fun `fails for one target`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
                cvlm::manifest::invoker(b"invoker");
            }
            public fun target1() {
                cvlm_assert(true);
            }
            public fun target2() {
                cvlm_assert(false);
            }
            public native fun invoker(f: Function);
            public fun test(f: Function) {
                invoker(f);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to false
            ),
            verifyMany()
        )
    }

    @Test
    fun `two function arguments`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
                cvlm::manifest::invoker(b"invoker");
            }
            public fun target1(n: &mut u32) {
                *n = *n + 1;
            }
            public fun target2(n: &mut u32) {
                *n = *n * 3;
            }
            public native fun invoker(f: Function, _: &mut u32);
            public fun test(f1: Function, f2: Function) {
                let mut n = 1;
                invoker(f1, &mut n);
                invoker(f2, &mut n);
                cvlm_assert(n == 6);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1-$testModuleAddrHex::test::target1" to false,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1-$testModuleAddrHex::test::target2" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2-$testModuleAddrHex::test::target1" to false,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2-$testModuleAddrHex::test::target2" to false,
            ),
            verifyMany()
        )
    }

    @Test
    fun `target with returns`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
                cvlm::manifest::invoker(b"invoker");
            }
            public fun target1() {
                cvlm_assert(true);
            }
            public fun target2(): (u32, u64) {
                cvlm_assert(true);
                (1, 2)
            }
            public native fun invoker(f: Function);
            public fun test(f: Function) {
                invoker(f);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to true
            ),
            verifyMany()
        )
    }

    @Test
    fun `simple argument passing`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
                cvlm::manifest::invoker(b"invoker");
            }
            public fun target1(n: u32, b: bool) {
                cvlm_assert(n == 1);
                cvlm_assert(b == true);
            }
            public fun target2(n: u64, b: bool) {
                cvlm_assert(n == 2);
                cvlm_assert(b == true);
            }
            public native fun invoker(_: Function, _: bool, _: u32, _: u64);
            public fun test(f: Function) {
                invoker(f, true, 1, 2);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to true
            ),
            verifyMany()
        )
    }

    @Test
    fun `ref argument passing`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
                cvlm::manifest::invoker(b"invoker");
            }
            public fun target1(n: &u32, b: bool, out: &mut u16) {
                cvlm_assert(n == 1);
                cvlm_assert(b == true);
                *out = 5678;
            }
            public fun target2(n: &u64, b: bool, out: &mut u16) {
                cvlm_assert(n == 2);
                cvlm_assert(b == true);
                *out = 5678;
            }
            public native fun invoker(_: Function, _: bool, _: &u32, _: &u64, _: &mut u16);
            public fun test(f: Function) {
                let a = 1;
                let b = 2;
                let mut c = 3;
                invoker(f, true, &a, &b, &mut c);
                cvlm_assert(c == 5678);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to true
            ),
            verifyMany()
        )
    }

    @Test
    fun `ref argument passing from mut to immut`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
                cvlm::manifest::invoker(b"invoker");
            }
            public fun target1(n: &u32, b: bool, out: &mut u16) {
                cvlm_assert(n == 1);
                cvlm_assert(b == true);
                *out = 5678;
            }
            public fun target2(n: &u64, b: bool, out: &mut u16) {
                cvlm_assert(n == 2);
                cvlm_assert(b == true);
                *out = 5678;
            }
            public native fun invoker(_: Function, _: bool, _: &mut u32, _: &mut u64, _: &mut u16);
            public fun test(f: Function) {
                let mut a = 1;
                let mut b = 2;
                let mut c = 3;
                invoker(f, true, &mut a, &mut b, &mut c);
                cvlm_assert(c == 5678);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to true
            ),
            verifyMany()
        )
    }

    @Test
    fun `ref argument passing from immmut to mut fails`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
                cvlm::manifest::invoker(b"invoker");
            }
            public fun target1(n: &u32, b: bool, out: &mut u16) {
                cvlm_assert(n == 1);
                cvlm_assert(b == true);
                *out = 5678;
            }
            public fun target2(n: &u64, b: bool, out: &mut u16) {
                cvlm_assert(n == 2);
                cvlm_assert(b == true);
                *out = 5678;
            }
            public native fun invoker(_: Function, _: bool, _: &u32, _: &u64, _: &u16);
            public fun test(f: Function) {
                let a = 1;
                let b = 2;
                let c = 3;
                invoker(f, true, &a, &b, &c);
                cvlm_assert(c == 3);
            }
        """.trimIndent())
        assertThrows(CertoraException::class.java) {
            verifyMany()
        }
    }

    @Test
    fun `target function name comparison`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
            }
            public fun target1() {
            }
            public fun target2() {
            }
            public fun test(f: Function) {
                cvlm_assert(f.name() == b"target1" || f.name() == b"target2")
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to true
            ),
            verifyMany()
        )
    }

    @Test
    fun `target module name comparison`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
            }
            public fun target1() {
            }
            public fun target2() {
            }
            public fun test(f: Function) {
                cvlm_assert(f.module_name() == b"test")
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to true
            ),
            verifyMany()
        )
    }

    @Test
    fun `target address comparison`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            use cvlm::function::Function;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target(@test_addr, b"test", b"target2");
                cvlm::manifest::rule(b"test");
            }
            public fun target1() {
            }
            public fun target2() {
            }
            public fun test(f: Function) {
                cvlm_assert(f.module_address() == @test_addr)
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target1" to true,
                "$testModuleAddrHex::test::test-$testModuleAddrHex::test::target2" to true
            ),
            verifyMany()
        )
    }

}
