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

package report.dumps

import allocator.Allocator
import analysis.CmdPointer
import tac.BlockIdentifier
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Tests for the working of [CmdPtrMapping]. I wrote these when I still had a more optimized version of [CmdPtrMapping],
 * using [CmdPtrLongCpy] in the works. If we ever develop that, they might be come useful, maybe now they're a little
 * trivial.
 */
class CmdPtrMappingTest {
    private fun block() =
        BlockIdentifier(
            origStartPc = 0,
            stkTop = 0,
            decompCopy = 0,
            calleeIdx = 0,
            topOfStackValue = 0,
            freshCopy = Allocator.getFreshId(Allocator.Id.BLOCK_FRESH_COPY)
        )

    @Test
    fun mergeMiddle01() {
        val blk0 = block()
        val blk1 = block()
        val oneBlockFiveCmds = CmdPtrMapping(mapOf(blk0 to (0..4).toList()))
        val threeCmdsInTheMiddle =
            CmdPtrMapping(listOf(
                CmdPtrLongCpy(blk0, 1, blk1, 0, 3)
            ))

        assertDoesNotThrow {
            oneBlockFiveCmds.merge(threeCmdsInTheMiddle)
            assertEquals(CmdPointer(blk0, 0), oneBlockFiveCmds[CmdPointer(blk0, 0)])
            assertEquals(CmdPointer(blk1, 0), oneBlockFiveCmds[CmdPointer(blk0, 1)])
            assertEquals(CmdPointer(blk1, 1), oneBlockFiveCmds[CmdPointer(blk0, 2)])
            assertEquals(CmdPointer(blk1, 2), oneBlockFiveCmds[CmdPointer(blk0, 3)])
            assertEquals(CmdPointer(blk0, 4), oneBlockFiveCmds[CmdPointer(blk0, 4)])
        }
    }

    /** In the middle, touching the start. */
    @Test
    fun mergeMiddle02() {
        val blk0 = block()
        val blk1 = block()
        val oneBlockFiveCmds = CmdPtrMapping(mapOf(blk0 to (0..4).toList()))
        val threeCmdsInTheMiddle =
            CmdPtrMapping(listOf(
                CmdPtrLongCpy(blk0, 0, blk1, 0, 3)
            ))

        assertDoesNotThrow {
            oneBlockFiveCmds.merge(threeCmdsInTheMiddle)
            assertEquals(CmdPointer(blk1, 0), oneBlockFiveCmds[CmdPointer(blk0, 0)])
            assertEquals(CmdPointer(blk1, 1), oneBlockFiveCmds[CmdPointer(blk0, 1)])
            assertEquals(CmdPointer(blk1, 2), oneBlockFiveCmds[CmdPointer(blk0, 2)])
            assertEquals(CmdPointer(blk0, 3), oneBlockFiveCmds[CmdPointer(blk0, 3)])
            assertEquals(CmdPointer(blk0, 4), oneBlockFiveCmds[CmdPointer(blk0, 4)])
        }
    }

    /** In the middle, touching the end. */
    @Test
    fun mergeMiddle03() {
        val blk0 = block()
        val blk1 = block()
        val oneBlockFiveCmds = CmdPtrMapping(mapOf(blk0 to (0..4).toList()))
        val threeCmdsInTheMiddle =
            CmdPtrMapping(listOf(
                CmdPtrLongCpy(blk0, 2, blk1, 0, 3)
            ))

        assertDoesNotThrow {
            oneBlockFiveCmds.merge(threeCmdsInTheMiddle)
            assertEquals(CmdPointer(blk0, 0), oneBlockFiveCmds[CmdPointer(blk0, 0)])
            assertEquals(CmdPointer(blk0, 1), oneBlockFiveCmds[CmdPointer(blk0, 1)])
            assertEquals(CmdPointer(blk1, 0), oneBlockFiveCmds[CmdPointer(blk0, 2)])
            assertEquals(CmdPointer(blk1, 1), oneBlockFiveCmds[CmdPointer(blk0, 3)])
            assertEquals(CmdPointer(blk1, 2), oneBlockFiveCmds[CmdPointer(blk0, 4)])
        }
    }

    @Test
    fun mergeSplitup01() {
        val blk0 = block()
        val blk1 = block()
        val blk2 = block()
        val oneBlockFiveCmds = CmdPtrMapping(mapOf(blk0 to (0..4).toList()))
        val twoAndTwoCmds =
            CmdPtrMapping(listOf(
                CmdPtrLongCpy(blk0, 1, blk1, 0, 2),
                CmdPtrLongCpy(blk0, 3, blk2, 0, 2),
            ))

        assertDoesNotThrow {
            oneBlockFiveCmds.merge(twoAndTwoCmds)
            assertEquals(CmdPointer(blk0, 0), oneBlockFiveCmds[CmdPointer(blk0, 0)])
            assertEquals(CmdPointer(blk1, 0), oneBlockFiveCmds[CmdPointer(blk0, 1)])
            assertEquals(CmdPointer(blk1, 1), oneBlockFiveCmds[CmdPointer(blk0, 2)])
            assertEquals(CmdPointer(blk2, 0), oneBlockFiveCmds[CmdPointer(blk0, 3)])
            assertEquals(CmdPointer(blk2, 1), oneBlockFiveCmds[CmdPointer(blk0, 4)])
        }
    }
}
