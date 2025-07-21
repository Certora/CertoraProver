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

package datastructures

import utils.KSerializable
import datastructures.stdcollections.*
import kotlinx.serialization.Contextual
import utils.AmbiSerializable

/**
 * A non-empty list. Guaranteed to have a first element ([head]), and possibly, another
 * elements ([tail]).
 */
@KSerializable
@JvmInline
value class NonEmptyList<out T> internal constructor(
    @Contextual
    private val backing: List<T>
) : List<T> by backing, AmbiSerializable {
    val head: T
        get() = backing[0]
    val tail: List<T>
        get() = backing.drop(1)

    constructor(head: T, tail: List<T>) : this(listOf(head) + tail)
    constructor(head: T): this(listOf(head))

    /**
     * Creates a new [NonEmptyList] from the list by mapping each element using [transform].
     */
    inline fun <R> map(transform: (T) -> R): NonEmptyList<R> {
        return NonEmptyList(
            head = transform(head),
            tail = tail.map(transform)
        )
    }

    /**
     * Returns a new [NonEmptyList] containing the results of applying the given [transform]
     * function to each element and its index in this [NonEmptyList].
     */
    fun <R> mapIndexed(transform: (Int, T) -> R): NonEmptyList<R> {
        return NonEmptyList(
            head = transform(0, head),
            tail = tail.mapIndexed { idx, elem -> transform(idx + 1, elem) }
        )
    }

    /** returns the first element. can't throw, since the list isn't empty */
    fun first(): T = this.head

    /** returns the last element. can't throw, since the list isn't empty */
    fun last(): T = this.tail.lastOrNull() ?: this.head

    override fun toString(): String = backing.toString()
}

/**
 * Converts this list to a [NonEmptyList], shallow-copying its contents.
 * Returns null if this list is empty.
 */
fun <T> List<T>.toNonEmptyList(): NonEmptyList<T>? = if (this.isEmpty()) {
    null
} else {
    val backing = this.toList()
    NonEmptyList(backing)
}

/** returns a new [NonEmptyList] of given elements. this function works like [listOf], but cannot be called with no parameters. */
fun <T> nonEmptyListOf(head: T, vararg tail: T): NonEmptyList<T> = NonEmptyList(head, tail.toList())
