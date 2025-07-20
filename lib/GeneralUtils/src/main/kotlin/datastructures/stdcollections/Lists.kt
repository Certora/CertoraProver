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

package datastructures.stdcollections
import kotlin.collections.listOf as stdListOf
import kotlin.collections.plus as stdPlus
import kotlin.collections.toList as stdToList
import datastructures.*

fun <T> List<T>.allButFirst(): List<T> {
    return this.subList(1, this.size)
}

fun <T> List<T>.allButLast(): List<T> {
    val end = when (val size = this.size) {
        0 -> 0
        else -> size - 1
    }
    return this.subList(0, end)
}

inline fun <T> List<T>.forAllButFirst(action: (T) -> Unit) {
    for (elem in this.allButFirst()) {
        action(elem)
    }
}

inline fun <T> List<T>.forAllButLast(action: (T) -> Unit) {
    for (elem in this.allButLast()) {
        action(elem)
    }
}

fun <T> List<T>.containsIgnoringFirst(element: T): Boolean {
    forAllButFirst {
        if (it == element) {
            return true
        }
    }
    return false
}

//
// Override some of the kotlin stdlib functions to use our own optimized list implementations
//

fun <T> emptyList(): List<T> = SmallList.Of0<T>()
fun <T> listOf(): List<T> = SmallList.Of0<T>()
fun <T> listOf(element: T): List<T> = SmallList.Of1(element)
fun <T> listOf(element0: T, element1: T): List<T> = SmallList.Of2(element0, element1)
fun <T> listOf(element0: T, element1: T, element2: T): List<T> = SmallList.Of3(element0, element1, element2)
fun <T> listOf(element0: T, element1: T, element2: T, element3: T): List<T> = SmallList.Of4(element0, element1, element2, element3)
fun <T> listOf(element0: T, element1: T, element2: T, element3: T, element4: T): List<T> = SmallList.Of5(element0, element1, element2, element3, element4)

fun <T> listOf(vararg elements: T): List<T> =
    when (elements.size) {
        0 -> listOf()
        1 -> listOf(elements[0])
        2 -> listOf(elements[0], elements[1])
        3 -> listOf(elements[0], elements[1], elements[2])
        4 -> listOf(elements[0], elements[1], elements[2], elements[3])
        5 -> listOf(elements[0], elements[1], elements[2], elements[3], elements[4])
        else -> stdListOf(*elements)
    }

fun <T> flatListOf(vararg elements: Iterable<T>): List<T> = elements.flatMap { it.toList() }

operator fun <T> Collection<T>.plus(element: T): List<T> =
    if (this is List<T>) {
        when (this.size) {
            0 -> listOf(element)
            1 -> listOf(this[0], element)
            2 -> listOf(this[0], this[1], element)
            3 -> listOf(this[0], this[1], this[2], element)
            4 -> listOf(this[0], this[1], this[2], this[3], element)
            else -> this.stdPlus(element)
        }
    } else {
        this.stdPlus(element)
    }

operator fun <T> Collection<T>.plus(elements: Iterable<T>): List<T> {
    val list = this.stdPlus(elements)
    return when (list.size) {
        0 -> listOf()
        1 -> listOf(list[0])
        2 -> listOf(list[0], list[1])
        3 -> listOf(list[0], list[1], list[2])
        4 -> listOf(list[0], list[1], list[2], list[3])
        5 -> listOf(list[0], list[1], list[2], list[3], list[4])
        else -> list
    }
}

fun <T> Iterable<T>.toList(): List<T> =
    when (this) {
        is List -> {
            when (size) {
                0 -> emptyList()
                1 -> listOf(this[0])
                2 -> listOf(this[0], this[1])
                3 -> listOf(this[0], this[1], this[2])
                4 -> listOf(this[0], this[1], this[2], this[3])
                5 -> listOf(this[0], this[1], this[2], this[3], this[4])
                else -> this.stdToList()
            }
        }
        is Collection -> {
            when (size) {
                0 -> emptyList()
                else -> {
                    this.iterator().let {
                        when (size) {
                            1 -> listOf(it.next())
                            2 -> listOf(it.next(), it.next())
                            3 -> listOf(it.next(), it.next(), it.next())
                            4 -> listOf(it.next(), it.next(), it.next(), it.next())
                            5 -> listOf(it.next(), it.next(), it.next(), it.next(), it.next())
                            else -> this.stdToList()
                        }
                    }
                }
            }
        }
        else -> {
            this.stdToList()
        }
    }

