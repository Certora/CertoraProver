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
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

class StringLiteralTest : MoveTestFixture() {
    override val loadStdlib = true
    @Test
    fun `string literal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let s = b"hello";
                cvlm_assert(s.length() == 5);
                cvlm_assert(s[0] == 104);
                cvlm_assert(s[1] == 101);
                cvlm_assert(s[2] == 108);
                cvlm_assert(s[3] == 108);
                cvlm_assert(s[4] == 111);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `empty string literal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let s = b"";
                cvlm_assert(s.length() == 0);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `assert message`() {
        addMoveSource("""
            $testModule
            public fun test(n: u64) {
                cvlm_assert_msg(n == 5, b"n is 5");
            }
        """.trimIndent())
        assertFalse(verify())
    }
}
