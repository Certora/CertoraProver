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

import sbf.disassembler.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

private val emptyGlobals = GlobalVariables(DefaultElfFileView)

class GlobalVarMapTest {
    // Helpers
    private fun gv(name: String, addr: ElfAddress, size: Long) =
        SbfGlobalVariable(name, addr, size)

    // -------------------------------------------------------------
    // Basic insertion and lookup
    // -------------------------------------------------------------
    @Test
    fun `add inserts a variable when no overlap exists`() {
        val m = emptyGlobals.add(gv("A", 100, 10))

        val found = m.findGlobalThatContains(105)
        assertNotNull(found)
        assertEquals("A", found!!.name)
    }

    @Test
    fun `contains returns false when no global variable contains the address`() {
        val m = emptyGlobals.add(gv("A", 100, 10))

        assertFalse(m.contains(50))
    }

    @Test
    fun `findGlobalThatContains returns null when empty`() {
        val m = emptyGlobals
        assertNull(m.findGlobalThatContains(123))
    }


    // -------------------------------------------------------------
    // Unsized globals
    // -------------------------------------------------------------
    @Test
    fun `unsized new global overlapping old is rejected`() {
        val old = gv("A", 100, 10)
        val newUnsized = gv("A", 105, 0) // unsized

        val m = emptyGlobals.add(old).add(newUnsized)
        // new one not added
        val result = m.findGlobalThatContains(105)!!
        assertEquals(100, result.address)
        assertEquals(10, result.size)
    }

    @Test
    fun `unsized existing global is not replaced by new sized`() {
        val unsized = gv("U", 200, 0)
        val newG = gv("U", 200, 10)

        val m = emptyGlobals.add(unsized).add(newG)

        // unsized should be kept
        val found = m.findGlobalThatContains(200)
        assertSame(unsized, found)
    }

    // -------------------------------------------------------------
    // floorEntry / lowerEntry traversal logic
    // -------------------------------------------------------------
    @Test
    fun `findGlobalThatContains walks backward to find spanning var`() {
        // map contains several small ones above a large variable
        val big = gv("Big", 1000, 100) // [1000,1100)

        val m = emptyGlobals
            .add(gv("v1", 1040, 1))
            .add(gv("v2", 1041, 1))
            .add(gv("v3", 1042, 1))
            .add(big)

        // search past the small ones
        val found = m.findGlobalThatContains(1099)!!

        assertEquals("Big", found.name)
        assertEquals(1000, found.address)
    }

    @Test
    fun `findGlobalThatContains returns exact match for unsized`() {
        val u = gv("U", 500, 0)
        val m = emptyGlobals.add(u)

        val found = m.findGlobalThatContains(500)
        assertSame(u, found)
    }
}
