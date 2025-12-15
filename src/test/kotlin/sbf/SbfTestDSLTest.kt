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
import sbf.cfg.*
import sbf.disassembler.*
import sbf.inliner.EmptyInlinerConfig
import sbf.inliner.inline
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import sbf.domains.MemorySummaries
import sbf.testing.SbfTestDSL

class SbfTestDSLTest {
    @Test
    fun emptyCFG() {
        val cfg = SbfTestDSL.makeCFG("f") {
            bb(2) {
                exit()
            }
        }
        cfg.verify(true, "test")
        Assertions.assertEquals(Label.Address(2), cfg.getEntry().getLabel())
    }

    @Test
    fun features() {
        val cfg = SbfTestDSL.makeCFG("f") {
            bb(0) {
                // Assign values
                r1 = 0x01
                r2 = 0x02
                // Use CondOps as instructions (r1 := r1 == r2 here)
                CondOp.EQ(r1, r2)
                // Conditional branch
                br(CondOp.EQ(r3, 0x1), 1, 2)
            }

            bb(1) {
                r4 = 0x12345678
                // *(r4 + 0x08) := 0x01
                r4[0x08] = 0x1
                // r5 := *(r4 + 0x10)
                r5 = r4[0x10]
                // Unconditional branch
                goto(2)
            }

            bb(2) {
                exit()
            }
        }
        cfg.normalize()
        cfg.verify(true)
    }

    @Test
    fun functions() {
        val entrypoint = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                "foo"()
                exit()
            }
        }
        val foo = SbfTestDSL.makeCFG("foo") {
            bb(1) {
                r0 = 42
                exit()
            }
        }
        val globals = GlobalVariables(DefaultElfFileView)
        val callgraph = MutableSbfCallGraph(listOf(entrypoint, foo), setOf("entrypoint"), globals)
        val memSummaries = MemorySummaries()
        val inlined = inline("entrypoint", callgraph, memSummaries, EmptyInlinerConfig)

        // Just check we got a CFG with "foo" inlined somewhere
        Assertions.assertTrue(
            inlined.getCFGs().singleOrNull()?.let { theCFG ->
                theCFG.getBlocks().any { (_, bb) ->
                    bb.getInstructions().any { inst ->
                        inst is SbfInstruction.Call && inst.metaData.getVal(SbfMeta.INLINED_FUNCTION_NAME) == "foo"
                    }
                }
            } == true
        )
    }
}
