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

package wasm.wat

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import utils.*
import wasm.*
import wasm.host.NullHost

/*
    This file contains sample usage of the `wasm.wat` WebAssembly-generation DSL.
    All samples are also JUnit tests, to validate that they compile and run correctly.
*/

class WatSamples : WasmTestFixture() {
    @Test
    fun `simple module with one function`() {

        // Use `watFunc` to create a module with a single function with no params or result
        watFunc("entry") {

            // Call the certoraAssert function, which will be automatically imported by the module:
            certoraAssert(i32(1))

        }.check(true)


        // Let's try some params and a result
        watFunc("entry", i32, i32, r = i32) { a, b ->
            // Return param a + param b
            returnResult(a + b)
        }.check(true)
    }


    @Test
    fun `multiple functions`() {

        // Use `watModule` if you need to define multiple functions
        val wat = watModule {
            // Define a function.  In WASM this will have a generated name like _2, but we can give it a Kotlin name by
            // storing it in a local variable.
            val assertEqual = func(param(i32), param(i32)).implement { (a, b) ->
                certoraAssert(a eq b)
            }

            // Define an exported function that calls assertEqual
            func(exportName = "entry").implement {
                assertEqual(i32(1), i32(2))
            }
        }
        wat.check(false)
    }


    @Test
    fun `declaration versus implementation`() {

        // You can separate the declaration and the implementation of a function, to enable forward references or
        // recursion.
        watModule {
            val fib = func(param(i32), r = i32)

            func(exportName = "entry").implement {
                certoraAssert(i32(8) eq fib(i32(6)))
            }

            fib.implement { (n) ->
                ite(n eq i32(0)) {
                    returnResult(i32(0))
                }
                ite(n eq i32(1)) {
                    returnResult(i32(1))
                }
                returnResult(fib(n - i32(1)) + fib(n - i32(2)))
            }
        }.checkCompileOnly("prover currently can't handle recursion")
    }


    @Test
    fun `locals`() {
        // A function can declare local variables using `local`.  These can be assigned at creation and/or later.
        watFunc("entry") {
            // Declare a local, but don't assign a value yet
            val f = local(i32)

            // Declare another local and assign a value
            val g = local(i32(1234))

            // Assign a value to f
            f.set(i32(1))

            // Use the locals
            certoraAssert((f + g) eq i32(1235))

        }.check(true)
    }


    @Test
    fun `if-then-else`() {
        // Use `ite` to create an if-then-else block
        val wat = watFunc("entry", i32) { n ->
            val q = local(i32)
            ite(
                n eq i32(1),
                then = {
                    q.set(i32(111))
                },
                orElse = {
                    q.set(i32(222))
                }
            )

            certoraAssert((q eq i32(111)) or (q eq i32(222)))

            // You can also omit the "else" block, and get a little more compact syntax
            ite(q eq i32(333)) {
                certoraAssert(i32(0))
            }
        }
        wat.check(true)
    }


    @Test
    fun `loop`() {
        // Use `loop` to create a loop
        val wat = watFunc("entry", i32) { n ->
            val q = local(i32(0))
            loop {
                q.set(q + i32(1))
                branchIf(q eq n)
            }
            certoraAssert(q eq n)

        }
            wat.check(false)
    }


    @Test
    fun `block`() {
        // Use `block` to create a block
        val wat = watFunc("entry") {
            val q = local(i32(0))
            block outer@{
                q.set(i32(1))
                block {
                    q.set(i32(2))
                    // `branch` and `branchIf` accept an optional target block/loop to branch relative to
                    branch(this@outer)
                    q.set(i32(3))
                }
                q.set(i32(4))
            }
            // Make sure the branch skipped the last two assignments
            certoraAssert(q eq i32(2))

        }
        wat.check(true)
    }


    @Test
    fun `memory`() {
        // Memory operations look a lot like textual WebAssembly:
        watFunc("entry") {

            // Store a value in memory
            i32.store8(i32(0), i32(42))

            // Load the value back
            val v = i32.load8(i32(0))

            // Check that the value is what we expect
            certoraAssert(v eq i32(42))

        }.check(true)
    }

    @Test
    fun `types`() {
        // In Kotlin, we expose WebAssembly's i32 and i64 types as `i32`, `u32`, `i64`, and `u64`. We automatically
        // choose the signed or unsigned varuants of any operators applied to these values. We also have explicit
        // conversions between types, using the `to` operator.
        watFunc("entry") {
            certoraAssert((i32(1) / i32(-1)) eq i32(1 / -1))
            certoraAssert((u32(1U) / i32(-1).to(u32)) eq u32(1U / (-1).toUInt()))

            // We also define f32 and f64 types

        }.check(true)
    }

    @Test
    fun `expressions`() {
        // All the operators you'd expect are available, and they can be combined in expressions, as we've seen already.
        // Let's do a more complex expression to see if it works:
        watFunc("entry") {
            certoraAssert(
                i32(1) + i32(2) * i32(3) - i32(4) / i32(5) eq i32(1 + 2 * 3 - 4 / 5)

                // See WatExpr.kt for the full list of supported operators.
            )
        }.check(true)
    }

    @Test
    fun `expression evaluation`() {
        watFunc("entry") {
            // We've seen lots of expressions so far, but let's look a little closer.  The wat code to evaluate an
            // expression is automatically generated at the point where the expression is used, for example as part of a
            // function call or an assignment to a local or memory.  You can re-use expression objects, but the code for
            // them will be duplicated in the final wat text.  For example:

            val a = i32(1)
            val b = i32(2)
            val c = a + b

            certoraAssert(c eq i32(3))
            certoraAssert(c ne i32(2))

            // This results in the addition being done twice:
            //
            // i32.const 1
            // i32.const 2
            // i32.add
            // i32.const 3
            // i32.eq
            // call $certora/CVT_assert_c
            // i32.const 1
            // i32.const 2
            // i32.add
            // i32.const 2
            // i32.ne
            // call $certora/CVT_assert_c

            // Sometimes this might matter if you're trying to get the prover to analyze some particular wasm sequence.
            // In that case, you can force the expression to be evaluated at a particular point, like so:

            val d = immediate(a + b)

            certoraAssert(d eq i32(3))
            certoraAssert(d ne i32(2))

            // This results in an automatic creation of a local to hold the result of the expression, so that the addition
            // only needs to happen once:
            //
            // i32.const 1
            // i32.const 2
            // i32.add
            // local.set $_2
            // local.get $_2
            // i32.const 3
            // i32.eq
            // call $certora/CVT_assert_c
            // local.get $_2
            // i32.const 2
            // i32.ne
            // call $certora/CVT_assert_c

        }.check(true)
    }

    @Test
    fun `inline wasm`() {
        watFunc("entry") {

            // If you need complete control over some of the generated wat text, or you need access to an obscure
            // instruction that with no Kotlin wrapper, you can insert arbitrary text into the wasm code using the unary
            // + operator:
            val n = local(i32)
            +"i32.const 1"
            +"i32.const 2"
            +"i32.add"
            +"local.set $n"

            certoraAssert(n eq i32(3))

            // And you can insert an expression evaluation in the middle of the wasm text, which places the result
            // of the expression on the stack.  Here's a toy example:
            +(i32(4) + i32(5))
            +"local.set $n"
            certoraAssert(n eq i32(9))
        }.check(true)
    }

    /////////////////////////////////////////////////////////////////
    // Support code below here
    /////////////////////////////////////////////////////////////////
    override val host = NullHost
    fun String.check(expectedSuccess: Boolean) = this.also {
        assertEquals(expectedSuccess, verifyWasm(it, "entry"))
    }
    private fun String.checkCompileOnly(reason: String) = this.also {
        unused(reason)
        watToWasm(it)
    }
}

