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

enum class SetDomainElement {
    TOP,    // The set with all elements
    ANY,    // any other subset included empty set
    BOTTOM  // This is not the empty set. It represents error/failure
}

/** Domain that represents any subset of E **/
abstract class SetDomain<E: Comparable<E>>: Iterable<E> {
    protected abstract val set: TreapSet<E>
    // whether @set is bottom, top or any other subset of E
    protected abstract val kind: SetDomainElement

    fun contains(e: E): Boolean {
        return if (isBottom()) {
            false
        } else if (isTop()) {
            true
        } else {
            set.any { it.compareTo(e) == 0 }
        }
    }

    fun size(): Int {
        check(!isBottom()) {"cannot call size() on bottom set"}
        check(!isTop()) {"cannot call size() on top set"}
        return set.size
    }

    override fun iterator(): Iterator<E> {
        check(!isBottom()) {"cannot call iterator() on bottom set"}
        check(!isTop()) {"cannot call iterator() on top set"}
        return set.iterator()
    }

    override fun toString(): String {
        return if (isBottom()) {
            "bottom"
        } else if (isTop()) {
            "top"
        } else {
            var str = "{"
            set.forEachIndexed { index, e ->
                str += e.toString()
                if (index < set.size - 1) {
                    str += ","
                }
            }
            str += "}"
            str
        }
    }

    abstract fun add(e: E): SetDomain<E>
    abstract fun remove(e: E): SetDomain<E>
    abstract fun removeAll(predicate: (E) -> Boolean): SetDomain<E>
    abstract fun isTop(): Boolean
    abstract fun isBottom(): Boolean
    abstract fun lessOrEqual(other: SetDomain<E>): Boolean
    abstract fun join(other: SetDomain<E>): SetDomain<E>
}

/** Set domain with union semantics: the smaller the set, the more precise **/
class SetUnionDomain<E: Comparable<E>>(override val set: TreapSet<E>,
                                       override val kind: SetDomainElement = SetDomainElement.ANY)
    : SetDomain<E>() {

     init {
        check(set.isEmpty() || kind == SetDomainElement.ANY)
     }

    companion object {

        @Suppress("Treapability")
        fun <E: Comparable<E>> mkTop() =
            SetUnionDomain(treapSetOf<E>(), SetDomainElement.TOP)
        @Suppress("Treapability")
        fun <E: Comparable<E>> mkBottom() =
            SetUnionDomain(treapSetOf<E>(), SetDomainElement.BOTTOM)
    }

    /** Empty set **/
    @Suppress("Treapability")
    constructor(): this(treapSetOf())

    override fun add(e: E): SetDomain<E> {
        check(!isBottom()) {"cannot call add method on bottom"}
        check(!isTop()) {"cannot call add method on top"}
        return SetUnionDomain(set.add(e))
    }

    override fun remove(e: E): SetDomain<E> {
        check(!isBottom()) {"cannot call remove method on bottom"}
        check(!isTop()) {"cannot call remove method on top"}
        return SetUnionDomain(set.remove(e))
    }

    override fun removeAll(predicate: (E) -> Boolean): SetDomain<E> {
        check(!isBottom()) {"cannot call removeAll method on bottom"}
        check(!isTop()) {"cannot call removeAll method on top"}
        return SetUnionDomain(set.removeAll(predicate))

    }

    override fun isBottom() = kind == SetDomainElement.BOTTOM

    override fun isTop() = kind == SetDomainElement.TOP

    override fun lessOrEqual(other: SetDomain<E>): Boolean {
        check(other is SetUnionDomain<E>)
        return if (isBottom() || other.isTop()) {
            true
        } else if (other.isBottom() || isTop()) {
            false
        } else {
            other.set.containsAll(set)
        }
    }

    override fun join(other: SetDomain<E>): SetDomain<E>  {
        check(other is SetUnionDomain<E>)
        return if (isBottom() || other.isTop()) {
            other
        } else if (other.isBottom() || isTop()) {
            this
        } else {
            SetUnionDomain(set.addAll(other.set))
        }
    }
}

/**
 *  Set domain with intersection semantics: the larger the set, the more precise.
 *  This is just the dual domain of SetUnionDomain.
 **/
class SetIntersectionDomain<E: Comparable<E>>(override val set: TreapSet<E>,
                                              override val kind: SetDomainElement = SetDomainElement.ANY)
    : SetDomain<E>() {

    init {
        check(set.isEmpty() || kind == SetDomainElement.ANY)
    }

    companion object {
        @Suppress("Treapability")
        fun <E: Comparable<E>> mkBottom() =
            SetIntersectionDomain(treapSetOf<E>(), SetDomainElement.TOP)
        @Suppress("Treapability")
        fun <E: Comparable<E>> mkTop() =
            SetIntersectionDomain(treapSetOf<E>(), SetDomainElement.BOTTOM)
    }

    /** empty set **/
    @Suppress("Treapability")
    constructor(): this(treapSetOf(), SetDomainElement.ANY)

    override fun add(e: E): SetDomain<E> {
        check(!isBottom()) {"cannot call add method on bottom"}
        check(!isTop()) {"cannot call add method on top"}
        return SetIntersectionDomain(set.add(e))
    }

    override fun remove(e: E): SetDomain<E> {
        check(!isBottom()) {"cannot call remove method on bottom"}
        check(!isTop()) {"cannot call remove method on top"}
        return SetIntersectionDomain(set.remove(e))
    }

    override fun removeAll(predicate: (E) -> Boolean): SetDomain<E> {
        check(!isBottom()) {"cannot call removeAll method on bottom"}
        check(!isTop()) {"cannot call removeAll method on top"}
        return SetIntersectionDomain(set.removeAll(predicate))
    }

    override fun isBottom() = kind == SetDomainElement.TOP
    override fun isTop() = kind == SetDomainElement.BOTTOM

    override fun lessOrEqual(other: SetDomain<E>): Boolean {
        check(other is SetIntersectionDomain<E>)
        return if (isBottom() || other.isTop()) {
            true
        } else if (other.isBottom() || isTop()) {
            false
        } else {
            set.containsAll(other.set)
        }
    }

    override fun join(other: SetDomain<E>): SetDomain<E>  {
        check(other is SetIntersectionDomain<E>)
        return if (isBottom() || other.isTop()) {
            other
        } else if (other.isBottom() || isTop()) {
            this
        } else {
            SetIntersectionDomain(set.retainAll(other.set))
        }
    }
}


