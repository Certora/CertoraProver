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


import sbf.callgraph.MutableSbfCallGraph
import sbf.disassembler.*
import sbf.domains.*
import sbf.inliner.InlinerConfigFromFile
import sbf.inliner.inline
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

private val globals = GlobalVariables(DefaultElfFileView)

class InlinerTest {

    @Test
    fun test01() {
        println("====== TEST 1 =======")
        /**
         * We just check that this exception is not thrown
        *    "CFG is not well-formed: entrypoint missing exit block After inline+simplify"
        **/
        val entrypoint = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                "foo"()
                "abort"()
                goto(1)
            }
            bb(1) {
               exit()
            }
        }

        val foo = SbfTestDSL.makeCFG("foo") {
            bb(0) {
                "abort"()
                goto(1)
            }
            bb(1) {
                exit()
            }
        }
        entrypoint.normalize()
        foo.normalize()
        entrypoint.verify(true)
        foo.verify(true)

        val prog = MutableSbfCallGraph(mutableListOf(entrypoint, foo), setOf("entrypoint"), globals)
        val inlinerConfig = InlinerConfigFromFile(listOf(), listOf())
        val memSummaries = MemorySummaries()
        println("Program\n$prog\n")
        val inlinedProg = inline("entrypoint", prog, memSummaries, inlinerConfig)
        println("Inlined program\n$inlinedProg")
    }

    @Test
    fun test02() {
        println("====== TEST 2 =======")
        /**
         * Check that the preserved function `foo` is preserved by the Inliner.
         */
        val entrypoint = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                exit()
            }
        }

        val foo = SbfTestDSL.makeCFG("foo") {
            bb(0) {
                exit()
            }
        }
        entrypoint.normalize()
        foo.normalize()
        entrypoint.verify(true)
        foo.verify(true)

        val prog = MutableSbfCallGraph(mutableListOf(entrypoint, foo), setOf("entrypoint"), globals, preservedCFGs = setOf("foo"))
        val inlinerConfig = InlinerConfigFromFile(listOf(), listOf())
        val memSummaries = MemorySummaries()
        println("Program\n$prog\n")
        val inlinedProg = inline("entrypoint", prog, memSummaries, inlinerConfig)
        println("Inlined program\n$inlinedProg")
        assert(inlinedProg.getCFG("foo") != null) { "CFG `foo` has not been preserved" }
    }

    @Test
    fun test03() {
        println("====== TEST 3 =======")
        /**
         * Check that the preserved function `bar` is preserved by the Inliner.
         * `bar` will not be in the set of preserved functions, but it will be transitively reachable from `foo`.
         */
        val entrypoint = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                exit()
            }
        }

        val foo = SbfTestDSL.makeCFG("foo") {
            bb(0) {
                "bar"()
                exit()
            }
        }

        val bar = SbfTestDSL.makeCFG("bar") {
            bb(0) {
                exit()
            }
        }
        entrypoint.normalize()
        foo.normalize()
        bar.normalize()
        entrypoint.verify(true)
        foo.verify(true)
        bar.verify(true)

        val prog = MutableSbfCallGraph(
            mutableListOf(entrypoint, foo, bar),
            setOf("entrypoint"),
            globals,
            preservedCFGs = setOf("foo") // Observe that bar is not preserved, but it is transitively reachable from foo.
        )
        val inlinerConfig = InlinerConfigFromFile(listOf(), listOf())
        val memSummaries = MemorySummaries()
        println("Program\n$prog\n")
        val inlinedProg = inline("entrypoint", prog, memSummaries, inlinerConfig)
        println("Inlined program\n$inlinedProg")
        assert(inlinedProg.getCFG("foo") != null) { "CFG `foo` has not been preserved" }
        assert(inlinedProg.getCFG("bar") != null) { "CFG `bar` has not been preserved" }
    }

    @Test
    fun test04() {
        println("====== TEST 4 =======")
        /**
         * Check that the preserved function `bar` is *not* preserved by the Inliner.
         * `bar` will not be in the set of preserved functions, and it is not reachable from entrypoint or any preserved
         * function.
         */
        val entrypoint = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                exit()
            }
        }

        val foo = SbfTestDSL.makeCFG("foo") {
            bb(0) {
                exit()
            }
        }

        val bar = SbfTestDSL.makeCFG("bar") {
            bb(0) {
                exit()
            }
        }
        entrypoint.normalize()
        foo.normalize()
        bar.normalize()
        entrypoint.verify(true)
        foo.verify(true)
        bar.verify(true)

        val prog = MutableSbfCallGraph(
            mutableListOf(entrypoint, foo, bar),
            setOf("entrypoint"),
            globals,
            preservedCFGs = setOf("foo") // Bar is not preserved, and it is also not reachable from any preserved CFG.
        )
        val inlinerConfig = InlinerConfigFromFile(listOf(), listOf())
        val memSummaries = MemorySummaries()
        println("Program\n$prog\n")
        val inlinedProg = inline("entrypoint", prog, memSummaries, inlinerConfig)
        println("Inlined program\n$inlinedProg")
        assert(inlinedProg.getCFG("foo") != null) { "CFG `foo` has not been preserved" }
        assert(inlinedProg.getCFG("bar") == null) { "CFG `bar` has been preserved" }
    }

}
