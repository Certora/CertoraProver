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

package instrumentation.transformers

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import utils.permutations
import vc.data.TACBuilderAuxiliaries
import vc.data.TACCmd.Simple.AnnotationCmd
import vc.data.TACProgramBuilder

class CollapseEmptyAssignmentBlocksTest: TACBuilderAuxiliaries() {

    @Test
    fun testEmptyToEmpty() {
        // This is a regression test for https://certora.atlassian.net/browse/CERT-8333
        //
        // The crash was triggered by a sequence of empty DSA assignment blocks
        // being processed in the "wrong" order, i.e.
        // if A --> B with A and B "empty", then removing B before A crashes.
        //
        // To avoid depending on any particularities of block streaming,
        // this test constructs such a graph & permutes the IDs of the blocks
        // and the order in which the blocks are added
        // to the graph in the hopes of triggering the problematic case

        fun makeCommands(xBlock: Int, yBlock: Int, zBlock: Int): List<TACProgramBuilder.BlockBuilder.() -> Unit> =
            listOf(
                { jump(xBlock) },
                {
                    jumpDest(yBlock) {
                        addCmd(AnnotationCmd(AnnotationCmd.Annotation(DSA_BLOCK_START, "")))
                        addCmd(AnnotationCmd(AnnotationCmd.Annotation(DSA_BLOCK_END, "")))
                        implicitJump(zBlock)
                    }
                },
                {
                    jumpDest(xBlock) {
                        addCmd(AnnotationCmd(AnnotationCmd.Annotation(DSA_BLOCK_START, "")))
                        addCmd(AnnotationCmd(AnnotationCmd.Annotation(DSA_BLOCK_END, "")))
                        implicitJump(yBlock)
                    }
                },
                {
                    jumpDest(zBlock) {
                        assert(x)
                    }
                }
            )

        for ((a, b, c) in (1..3).toList().permutations()) {
            val commands = makeCommands(a, b, c)
            for (perm in commands.indices.toList().permutations()) {
                val prog = TACProgramBuilder {
                    perm.forEach { commands[it]() }
                }
                val collapsed = TACDSA.collapseEmptyAssignmentBlocks(prog.code)
                Assertions.assertEquals(2, collapsed.blockgraph.size)
            }
        }
    }
}
