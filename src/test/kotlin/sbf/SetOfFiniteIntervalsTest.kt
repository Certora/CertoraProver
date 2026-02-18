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

class SetOfFiniteIntervalsTest {
    fun setOf(vararg intervals: FiniteInterval): SetOfFiniteIntervals {
        var result = SetOfFiniteIntervals.new()
        for (interval in intervals) {
            result = result.add(interval)
        }
        return result
    }

    @Test
    fun test01() {
        val i1 = SetOfFiniteIntervals.new().add(FiniteInterval(4,8)).add(FiniteInterval(12,20))

        val x1 = FiniteInterval(2,6)
        val i2 = i1.add(x1)
        println("Add $x1 in $i1 is $i2")

        // [[2,8], [12,20]]
        Assertions.assertEquals(i2, setOf(FiniteInterval(2,8), FiniteInterval(12,20)))

        val x2 = FiniteInterval(6,9)
        val i3 = i1.add(x2)
        println("Add $x2 in $i1 is $i3")

        // [[4,9], [12,20]]
        Assertions.assertEquals(i3, setOf(FiniteInterval(4,9), FiniteInterval(12,20)))

        val x3 = FiniteInterval(2,14)
        val i4 = i1.add(x3)
        println("Add $x3 in $i1 is $i4")

        // [[2,20]]
        Assertions.assertEquals(i4, setOf(FiniteInterval(2,20)))

        val x4 = FiniteInterval(9,22)
        val i5 = i1.add(x4)
        println("Add $x4 in $i1 is $i5")

        // [[4,22]]
        Assertions.assertEquals(i5, setOf(FiniteInterval(4,22)))

        val x5 = FiniteInterval(2,22)
        val i6 = i1.add(x5)
        println("Add $x5 in $i1 is $i6")

        // [[2,22]]
        Assertions.assertEquals(i6, setOf(FiniteInterval(2,22)))

    }

    @Test
    fun test02() {
        val i1 = SetOfFiniteIntervals.new()
            .add(FiniteInterval(10,14))
            .add(FiniteInterval(18,19))
            .add(FiniteInterval(20,30))

        val i2 = SetOfFiniteIntervals.new()
            .add(FiniteInterval(5,9))
            .add(FiniteInterval(10,12))
            .add(FiniteInterval(20,25))

        println("i1=$i1 i2=$i2")
        val i3 = i1.intersection(i2)
        println("i3=intersection(i1,i2)=$i3")

        Assertions.assertEquals(i3, setOf(FiniteInterval(10,12), FiniteInterval(20,25)))
    }

    @Test
    fun test03() {
        val i1 = SetOfFiniteIntervals.new()
            .add(FiniteInterval(10,14))
            .add(FiniteInterval(30,50))

        val i2 = SetOfFiniteIntervals.new()
            .add(FiniteInterval(12,20))
            .add(FiniteInterval(32,40))
            .add(FiniteInterval(45,51))
            .add(FiniteInterval(53,60))

        println("i1=$i1 i2=$i2")
        val i3 = i1.intersection(i2)
        println("i3=intersection(i1,i2)=$i3")

        Assertions.assertEquals(i3,
            setOf(
                FiniteInterval(12,14),
                FiniteInterval(32,40),
                FiniteInterval(45,50)
            )
        )
    }

    @Test
    fun test04() {
        val i1 = SetOfFiniteIntervals.new()
            .add(FiniteInterval(14,16))
            .add(FiniteInterval(18,20))

        val i2 = SetOfFiniteIntervals.new()
            .add(FiniteInterval(12,14))
            .add(FiniteInterval(16,18))
            .add(FiniteInterval(20,22))


        println("i1=$i1 i2=$i2")
        val i3 = i1.intersection(i2)
        println("i3=intersection(i1,i2)=$i3")

        Assertions.assertEquals(i3,
            setOf(
                FiniteInterval(14,14),
                FiniteInterval(16,16),
                FiniteInterval(18,18),
                FiniteInterval(20,20)
            )
        )
    }

    @Test
    fun `difference returns original interval if no overlap`() {
        val a = FiniteInterval(1, 5)
        val b = FiniteInterval(10, 15)
        val result = a.difference(b)
        Assertions.assertEquals(listOf(a), result)
    }

    @Test
    fun `difference returns empty list if intervals are equal`() {
        val a = FiniteInterval(1, 5)
        val result = a.difference(a)
        Assertions.assertEquals(0, result.size)
    }

    @Test
    fun `difference returns single interval when other overlaps left side`() {
        val a = FiniteInterval(5, 10)
        val b = FiniteInterval(3, 7) // overlaps left
        val expected = listOf(FiniteInterval(8, 10))
        Assertions.assertEquals(expected, a.difference(b))
    }

    @Test
    fun `difference returns single interval when other overlaps right side`() {
        val a = FiniteInterval(5, 10)
        val b = FiniteInterval(8, 12) // overlaps right
        val expected = listOf(FiniteInterval(5, 7))
        Assertions.assertEquals(expected, a.difference(b))
    }

    @Test
    fun `difference returns two intervals when other is fully contained`() {
        val a = FiniteInterval(1, 10)
        val b = FiniteInterval(4, 7) // fully contained
        val expected = listOf(FiniteInterval(1, 3), FiniteInterval(8, 10))
        Assertions.assertEquals(expected, a.difference(b))
    }

    @Test
    fun `difference handles touching intervals correctly`() {
        val a = FiniteInterval(1, 5)
        val b = FiniteInterval(5, 10) // touches at the end
        val expected = listOf(FiniteInterval(1, 4))
        Assertions.assertEquals(expected, a.difference(b))
    }

    @Test
    fun `difference handles touching intervals on the other side`() {
        val a = FiniteInterval(5, 10)
        val b = FiniteInterval(1, 5) // touches at the start
        val expected = listOf(FiniteInterval(6, 10))
        Assertions.assertEquals(expected, a.difference(b))
    }

    @Test
    fun `add duplicate no last`() {
        val a = SetOfFiniteIntervals(listOf(FiniteInterval(1, 15), FiniteInterval(20,25)))
        val b = FiniteInterval(1, 15)
        val res = a.add(b)
        println("Add $b in $a -> $res")
        Assertions.assertEquals(a, res)
    }

    @Test
    fun `add duplicate last`() {
        val a = SetOfFiniteIntervals(listOf(FiniteInterval(1, 15), FiniteInterval(20,25)))
        val b = FiniteInterval(20, 25)
        val res = a.add(b)
        println("Add $b in $a -> $res")
        Assertions.assertEquals(a, res)
    }
}
