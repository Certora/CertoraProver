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

import utils.*
import java.util.concurrent.ConcurrentHashMap

/** Used in place of null keys and values in ConcurrentHashMap */
private object WrappedNull

/**
    Returns a memoized version of the function [f]. The memoized function will cache the results of previous calls and
    return the cached result for the same input on subsequent calls.
    if [reentrant] is true, the resulting function can be used recursively, at the cost of the insertion into the cache
    not being an atomic operation.
 */
fun <T, R> memoized(reentrant : Boolean = false, f: (T) -> R): (T) -> R {
    fun <T> T.wrap(): Any = this ?: WrappedNull
    fun <T> Any.unwrap(): T = this.takeIf { it != WrappedNull }.uncheckedAs<T>()
    val cache = ConcurrentHashMap<Any, Any>()
    return { t ->
        if (reentrant) {
            val k = t.wrap()
            cache[k]
                ?.unwrap()
                ?: f(t).also { cache[k] = it.wrap() }
        } else {
            cache.computeIfAbsent(t.wrap()) { f(it.unwrap()).wrap() }.unwrap()
        }
    }
}

/**
    Returns a memoized version of the function [f]. The memoized function will cache the results of previous calls and
    return the cached result for the same input on subsequent calls.
 */
fun <T, U, R> memoized(reentrant : Boolean = false, f: (T, U) -> R): (T, U) -> R {
    val memo = memoized<Pair<T, U>, R>(reentrant) { (t, u) -> f(t, u) }
    return { t, u -> memo(Pair(t, u)) }
}

/**
    Returns a memoized version of the function [f]. The memoized function will cache the results of previous calls and
    return the cached result for the same input on subsequent calls.
 */
fun <T, U, V, R> memoized(reentrant : Boolean = false, f: (T, U, V) -> R): (T, U, V) -> R {
    val memo = memoized<Triple<T, U, V>, R>(reentrant) { (t, u, v) -> f(t, u, v) }
    return { t, u, v -> memo(Triple(t, u, v)) }
}
