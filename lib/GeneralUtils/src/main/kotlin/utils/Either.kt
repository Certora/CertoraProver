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

package utils

import java.io.Serializable

sealed class Either<out T, out R> : Serializable {
    data class Left<T>(val d: T) : Either<T, Nothing>()
    data class Right<R>(val d: R) : Either<Nothing, R>()

    fun leftOrNull(): T? = (this as? Left)?.d
    fun rightOrNull(): R? = (this as? Right)?.d

    inline fun onLeft(op: (T) -> Unit): Either<T, R> {
        if (this is Left) { op(d) }
        return this
    }
    inline fun onRight(op: (R) -> Unit): Either<T, R> {
        if (this is Right) { op(d) }
        return this
    }
}

inline fun <T, R> Either<T, R>.leftOr(op: (Either<Nothing, R>) -> Nothing) : T {
    when(this) {
        is Either.Left -> return this.d
        is Either.Right -> op(this)
    }
}

fun <T, R> List<Either<T, R>>.flattenLeft(): Either<List<T>, R> {
    val m = mutableListOf<T>()
    for(i in this) {
        when(i) {
            is Either.Left -> m.add(i.d)
            is Either.Right -> return i
        }
    }
    return m.toLeft()
}

fun <T, R, U> Either<T, R>.mapLeft(f: (T) -> U) : Either<U, R> =
        when(this) {
            is Either.Left -> Either.Left(f(this.d))
            is Either.Right -> this
        }

fun <T, R, U, V> Either<T, R>.mapBoth(leftF: (T) -> U, rightF: (R) -> V) =
    when(this) {
        is Either.Left -> Either.Left(leftF(this.d))
        is Either.Right -> Either.Right(rightF(this.d))
    }

fun <T, R, U> Either<T, R>.mapRight(f: (R) -> U) : Either<T, U> =
        when(this) {
            is Either.Left -> this
            is Either.Right -> Either.Right(f(this.d))
        }

fun <T, R, U> Either<T, R>.bindLeft(f: (T) -> Either<U, R>) : Either<U, R> =
        when(this) {
            is Either.Right -> this
            is Either.Left -> f(this.d)
        }

fun <T, R, U> Either<T, R>.bindRight(f: (R) -> Either<T, U>) : Either<T, U> =
        when(this) {
            is Either.Right -> f(this.d)
            is Either.Left -> this
        }

fun <T, R, U, V> Either<T, R>.bind(l: (T) -> Either<U, V>, r: (R) -> Either<U, V>) : Either<U, V> =
        when(this) {
            is Either.Right -> r(this.d)
            is Either.Left -> l(this.d)
        }

inline fun <T, R, U> Either<T, R>.toValue(l: (T) -> U, r: (R) -> U): U =
        when(this) {
            is Either.Left -> l(this.d)
            is Either.Right -> r(this.d)
        }

inline fun <T, R> Either<T, R>.accept(l: (T) -> Unit, r: (R) -> Unit) =
    when(this) {
        is Either.Left -> l(this.d)
        is Either.Right -> r(this.d)
    }

fun <T> T.toLeft() : Either<T, Nothing> = Either.Left(this)

fun <R> R.toRight() : Either<Nothing, R> = Either.Right(this)

fun <T, R: Throwable> Either<T, R>.leftOrThrow(): T =
    when (this) {
        is Either.Left -> this.d
        is Either.Right -> throw this.d
    }

fun <R> Either<Nothing, R>.right() = when(this) {
    /* why does this work? Because of the type signature of Either<Nothing, _>, we know that
    in this Left branch `this.d` must be of type Nothing. But the whole point is that Nothing has no inhabitants;
    so this case is impossible; indeed this is why Nothing is a subtype of any type, any expression that produces a value
    of type Nothing can never terminate. In other words, if we call this function, the receiver type *must*
    be Either.Right; if it wasn't, then we would have been able to produce an Either.Left(x) where `x` has type Nothing,
    which, again, is impossible
    */
    is Either.Left -> this.d
    is Either.Right -> this.d
}

/*
 * Similar reasoning applies here as to why this.d is perfectly fine in the Either.Right branch
 */
fun <R> Either<R, Nothing>.left() = when(this) {
    is Either.Left -> this.d
    is Either.Right -> this.d
}

/** returns the left value, or computes another left value */
fun <U, T: U, R> Either<T, R>.leftOrElse(default: (R) -> U): U = when (this) {
   is Either.Left -> this.d
   is Either.Right -> default(this.d)
}

/* special case for when both "sides" are the same type */
fun <T> Either<T, T>.leftOrRight() = when (this) {
    is Either.Left -> this.d
    is Either.Right -> this.d
}