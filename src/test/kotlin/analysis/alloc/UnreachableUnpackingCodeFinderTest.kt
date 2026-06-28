/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

@file:Suppress("DEPRECATION") // TACUtils.tagsFromBlocks is the standard way to build a test symbol table

package analysis.alloc

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import testing.ttl.TACMockLanguage
import vc.data.*

class UnreachableUnpackingCodeFinderTest {
    /**
     * Regression test for the "Graph with not exactly 1 root" crash.
     *
     * [UnreachableUnpackingCodeFinder] matches the storage-string length-decode shape
     * solc emits under via-IR:
     *
     *     t = x & 1
     *     if (t == 0) { ...short... }        // firstCondition
     *     else if (t == 1) { ...long... }    // secondCondition  (the matched block)
     *     else { ...clearly unreachable... } // the elseDst that gets removed
     *
     * It rewrites the matched `(t == 1)` jumpi into an unconditional jump and deletes the
     * unreachable `else` head. That head is, in practice, the start of a *multi-block*
     * region (the string alloc/overflow code). The pre-fix implementation removed only the
     * head via the non-transitive `removeBlock`, orphaning the blocks reachable solely
     * through it and leaving the graph with more than one root.
     *
     * This builds that exact shape with a deliberately multi-block unreachable `else` and
     * asserts the pass leaves a single-rooted graph.
     */
    @Test
    fun deadDefaultDoesNotOrphanBlocks() {
        val graph = TACMockLanguage.make {
            L1020 = "BWAnd(x 0x1)"              // t = x & 1   (shared by both conditions)
            `if`(1021, "Eq(L1020 0x0)") {       // (t == 0)
                // short-string path (reachable)
            } `else` {
                `if`(1022, "Eq(L1020 0x1)") {   // (t == 1)  -> this is the matched block
                    // long-string path (reachable)
                } `else` {
                    // logically-unreachable `default`. The inner branch makes it a
                    // multi-block region whose blocks are reachable ONLY through its head,
                    // so removing just the head orphans them.
                    `if`(1023, "Eq(x 0x2)") {
                    } `else` {
                    }
                }
            }
            L1024 = "0x0"                       // join block for the reachable branch tails
        }

        val prog = CoreTACProgram(
            code = graph.code,
            name = "unreachableUnpackingMultiRoot",
            blockgraph = graph.toBlockGraph(),
            procedures = emptySet(),
            symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
            ufAxioms = UfAxioms.empty()
        )

        // Sanity: the constructed input is a single, well-formed CFG.
        Assertions.assertEquals(1, prog.analysisCache.graph.roots.size, "input should have exactly one root")

        val out = UnreachableUnpackingCodeFinder.removeUnreachable(prog)

        // After the pass the graph must still have a single root: the unreachable head and
        // everything reachable only through it must be gone, not just the head. Pre-fix this
        // was > 1 (orphaned blocks), which is exactly what later crashes toPatchingProgram.
        Assertions.assertEquals(
            1, out.analysisCache.graph.roots.size,
            "removeUnreachable must drop the whole unreachable subgraph, not orphan its tail"
        )
        Assertions.assertDoesNotThrow({ out.entryBlockId }, "entry block must be uniquely determined")
    }
}
