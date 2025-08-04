/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package algorithms

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import utils.toLeft
import utils.toRight

class DeRecursorTest {

    @Test
    fun deRecurseTest() {
        // counts the number of number of recursive calls. It's used to check that memoization works.
        var count = 0
        val result = DeRecursor(
            reduce = { it.sum() },
            memoize = true,
            nextsOrResult = { i : Int ->
                count++
                if (i == 0 || i == 1) {
                    1.toRight()
                } else {
                    listOf(i-1, i-2).toLeft()
                }
            }
        ).go(10)
        assertEquals(89, result)
        assertEquals(11, count)
    }

}
