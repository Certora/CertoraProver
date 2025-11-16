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

package sbf.domains

import com.certora.collect.*

/** Domain that represents any subset of E **/
abstract class SetDomain<E: Comparable<E>>: Iterable<E> {
    protected abstract val set: TreapSet<E>

    override fun toString(): String {
        return if (isTop()) {
            "top"
        } else {
            val strB = StringBuilder("{")
            set.forEachIndexed { index, e ->
                strB.append(e.toString())
                if (index < set.size - 1) {
                    strB.append(",")
                }
            }
            strB.append("}")
            strB.toString()
        }
    }

    abstract fun size(): Int
    abstract fun add(e: E): SetDomain<E>
    abstract fun remove(e: E): SetDomain<E>
    abstract fun removeAll(predicate: (E) -> Boolean): SetDomain<E>
    abstract fun isTop(): Boolean
    abstract fun lessOrEqual(other: SetDomain<E>): Boolean
    abstract fun join(other: SetDomain<E>): SetDomain<E>
}

/**
 * Set domain with union semantics: the smaller the set, the more precise.
 *
 * `Top` is a special element that represents the powerset of [E].
 *  Note that the set could contain all possible sets of [E] (i.e., powerset) by calling `add` many times,
 *  but it still wouldn't be considered `Top`.
 *  Thus, we can have two abstract elements with the same concretization.
 **/
class SetUnionDomain<E: Comparable<E>>(override val set: TreapSet<E>, val isTopSym: Boolean)
    : SetDomain<E>() {

    companion object {
        @Suppress("Treapability")
        fun <E: Comparable<E>> mkTop() =
            SetUnionDomain(treapSetOf<E>(), isTopSym = true)
    }

    /** Empty set **/
    @Suppress("Treapability")
    constructor(): this(treapSetOf(), isTopSym = false)

    override fun add(e: E): SetDomain<E> {
        check(!isTop()) {"cannot call add method on top"}
        return SetUnionDomain(set.add(e), isTopSym = false)
    }

    override fun remove(e: E): SetDomain<E> {
        check(!isTop()) {"cannot call remove method on top"}
        return SetUnionDomain(set.remove(e), isTopSym = false)
    }

    override fun removeAll(predicate: (E) -> Boolean): SetDomain<E> {
        check(!isTop()) {"cannot call removeAll method on top"}
        return SetUnionDomain(set.removeAll(predicate), isTopSym = false)

    }

    override fun isTop() = isTopSym

    override fun size(): Int {
        check(!isTop()) {"cannot call size() on top set"}
        return set.size
    }

    override fun iterator(): Iterator<E> {
        check(!isTop()) {"cannot call iterator() on top set"}
        return set.iterator()
    }

    override fun lessOrEqual(other: SetDomain<E>): Boolean {
        check(other is SetUnionDomain<E>)
        return if (other.isTop()) {
            true
        } else if (isTop()) {
            false
        } else {
            other.set.containsAll(set)
        }
    }

    override fun join(other: SetDomain<E>): SetDomain<E>  {
        check(other is SetUnionDomain<E>)
        return if (other.isTop()) {
            other
        } else if (isTop()) {
            this
        } else {
            SetUnionDomain(set.addAll(other.set), isTopSym = false)
        }
    }
}

/**
 *  Set domain with intersection semantics: the larger the set, the more precise.
 *  This is just the dual domain of [SetUnionDomain].
 *
 *  `Top` is represented by the empty set, thus we don't need a special element for it.
 **/
class SetIntersectionDomain<E: Comparable<E>>(override val set: TreapSet<E>)
    : SetDomain<E>() {

    companion object {
        @Suppress("Treapability")
        fun <E: Comparable<E>> mkTop() = SetIntersectionDomain(treapSetOf<E>())
    }

    /** Return the empty set which is `Top` **/
    @Suppress("Treapability")
    constructor(): this(treapSetOf())

    override fun add(e: E): SetDomain<E> {
        return SetIntersectionDomain(set.add(e))
    }

    override fun remove(e: E): SetDomain<E> {
        return SetIntersectionDomain(set.remove(e))
    }

    override fun removeAll(predicate: (E) -> Boolean): SetDomain<E> {
        return SetIntersectionDomain(set.removeAll(predicate))
    }

    override fun isTop() = set.isEmpty()

    override fun size(): Int {
        return set.size
    }

    override fun iterator(): Iterator<E> {
        return set.iterator()
    }

    override fun lessOrEqual(other: SetDomain<E>): Boolean {
        check(other is SetIntersectionDomain<E>)
        return if (other.isTop()) {
            true
        } else if (isTop()) {
            false
        } else {
            set.containsAll(other.set)
        }
    }

    override fun join(other: SetDomain<E>): SetDomain<E>  {
        check(other is SetIntersectionDomain<E>)
        return if (other.isTop()) {
            other
        } else if (isTop()) {
            this
        } else {
            SetIntersectionDomain(set.retainAll(other.set))
        }
    }
}


