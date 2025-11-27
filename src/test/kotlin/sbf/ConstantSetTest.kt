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

import sbf.domains.*
import org.junit.jupiter.api.*

class ConstantSetTest {

    @Test
    fun test1() {
        val s = ConstantSet(listOf(1L,2L,3L).map{Constant(it)}.toSet(), 2UL)
        println("$s")
        Assertions.assertEquals(true, s.isTop())
    }

    @Test
    fun test2() {
        val s = ConstantSet(listOf(1L,2L,3L).map{Constant(it)}.toSet(), 3UL)
        println("$s")
        Assertions.assertEquals(false, s.isTop())
    }

    @Test
    fun test3() {
        val s1 = ConstantSet(listOf(1L,2L,3L).map{Constant(it)}.toSet(), 3UL)
        println("$s1")
        val s2 = s1.add(2)
        println("After +2: $s2")
        Assertions.assertEquals(false, s2.isTop())
    }

    @Test
    fun test4() {
        val s = ConstantSet(listOf(1L,2L,3L).map{Constant(it)}.toSet(), 3UL)
        println("$s")
        Assertions.assertEquals(true, s.toLongList().isNotEmpty())
    }
}
