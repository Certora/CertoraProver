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

import datastructures.stdcollections.mutableMapOf
import kotlinx.coroutines.yield
import parallel.coroutines.onThisThread
import utils.Either
import utils.lazy
import utils.toValue
import utils.uncheckedAs

/**
 * This imitates running the recursive function below, but uses a stack on the heap instead of real recursion:
 * ```
 *  fun rec(t : T) : R {
 *     val either = nextsOrResult(t)
 *     if (either is "nexts") {
 *         return reduce(either.map(::rec))
 *     } else {
 *         return either as R
 *     }
 *  }
 * ```
 * The key is the yield call, which unwinds back to the onThisThread call (which just immediately resumes the
 * suspend function).
 *
 * if [memoize] is true, then calls to [rec] are memoized.
 */
class DeRecursor<T, R>(
    val nextsOrResult: (T) -> Either<Iterable<T>, R>,
    val reduce: (List<R>) -> R,
    val memoize: Boolean
) {
    private val cache by lazy { mutableMapOf<T, R>() }

    private suspend fun rec(t: T): R {
        if (memoize && t in cache) {
            // the `uncheckedAs` is needed if R is nullable
            return cache[t].uncheckedAs()
        }
        val result = nextsOrResult(t).toValue(
            l = {
                yield()
                reduce(it.map { rec(it) })
            },
            r = { it }
        )
        if (memoize) {
            cache[t] = result
        }
        return result
    }

    fun go(t : T) = onThisThread { rec(t) }
}
