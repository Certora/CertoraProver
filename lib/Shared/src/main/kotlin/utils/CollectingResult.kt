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

import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors
import kotlin.system.exitProcess


/**
 * DSL for working with [CollectingResult] monad.  The main entry point is [collectingErrors].
 *
 * The [bind] methods all return immediately if the bound result is an error, while the `collect` methods save errors
 * and continue; the errors will be returned from [collectingErrors].  These are useful for situations where you want to collect
 * several errors before aborting.
 *
 * @note this class uses a [Throwable] to stop execution early; code that catches [Throwable] will probably not
 * work as expected.  You probably shouldn't be doing that anyway.
 *
 * @note objects of this class should only be used in the body of a [collectingErrors] call.  The only way you can screw
 * that up is if you store or return `this` in the body (or in a closure that is executed outside of the body); don't do
 * that.  It's best to never declare variables (or arguments or return types) of type [ErrorCollector].
 *
 * @see [ErrorCollectorTest] for examples
 */
@Suppress("KDocUnresolvedReference")
class ErrorCollector<E> private constructor(private val errors : MutableList<E>) {
    // Note: since [Error]s with empty message sets are allowed, we can't just use `errors.isNotEmpty()`.  However, we
    // do maintain the invariant that `errors.isNotEmpty()` implies `hasError`
    private var hasError : Boolean = false

    /** Unwrap [wrapped] if it is not an error; errors cause the enclosing [collectingErrors] call to return immediately */
    fun <R> bind(wrapped : CollectingResult<R,E>) : R
        = wrapped.resultOrThrow { returnErrors(it) }

    /** Unwrap [r] and [s] if they are not errors; errors cause the enclosing [collectingErrors] call to return immediately. */
    fun <R, S> bind(r : CollectingResult<R,E>, s : CollectingResult<S,E>) : Pair<R, S>
        = map(r, s, ::Pair)

    /** Apply [f] to [wrappedR] and [wrappedS] if they are not errors; stop the enclosing [collectingErrors] otherwise */
    fun <R,S,T> map(wrappedR : CollectingResult<R,E>, wrappedS : CollectingResult<S,E>, f : (R,S) -> T) : T
        = bind(wrappedR.map(wrappedS, f))

    /** Apply [f] to [wrappedR], [wrappedS], and [wrappedT] if they are not errors; stop the enclosing [collectingErrors] otherwise */
    fun <R,S,T,U> map(
        wrappedR : CollectingResult<R,E>, wrappedS : CollectingResult<S,E>, wrappedT : CollectingResult<T,E>,
        f : (R,S,T) -> U
    ) : U = bind(wrappedR.map(wrappedS, wrappedT, f))

    /** Apply [f] to [wrappedR], [wrappedS], [wrappedT], and [wrappedU] if they are not errors; stop the enclosing [collectingErrors] otherwise */
    fun <R,S,T,U,V> map(
        wrappedR : CollectingResult<R,E>, wrappedS : CollectingResult<S,E>, wrappedT : CollectingResult<T,E>, wrappedU : CollectingResult<U,E>,
        f : (R,S,T,U) -> V
    ) : V = bind(wrappedR.map(wrappedS, wrappedT, wrappedU, f))

    /**
     * Apply [f] to [wrappedR] and [wrappedS] if they are not errors; stop the enclosing [collectingErrors] otherwise
     * @return the result of [f] if it is not an error; stops the enclosing [collectingErrors] if it is
     */
    fun <R,S,T> bind(wrappedR: CollectingResult<R,E>, wrappedS: CollectingResult<S,E>, f: (R, S) -> CollectingResult<T,E>) : T
        = bind(wrappedR.bind(wrappedS, f))

    /** Stop execution and cause the enclosing [collectingErrors] to return [error] (and any already-collected errors) */
    fun returnError(error : E) : Nothing
        = collectError(error).run { throw StopCollecting() }

    /** Stop execution and cause the enclosing [collectingErrors] to return [errors] (and any already-collected errors) */
    private fun returnErrors(errors : List<E>) : Nothing
        = collectErrors(errors).run { throw StopCollecting() }

    /** TODO CERT-9281 don't use this - it's a kludge for a temporary workaround. */
    fun returnAnyway() : Nothing = returnErrors(emptyList())

    /**
     * Continue execution but save [error] for return by the enclosing [collectingErrors].  Useful for functions that return
     * multiple errors.
     */
    fun collectError(error : E) : Unit
        = errors.add(error).let { hasError = true }

    /**
     * Continue execution but save [errors] for return by the enclosing [collectingErrors].  Useful for functions that return
     * multiple errors.
     */
    fun collectErrors(errors : List<E>) : Unit
        = this.errors.addAll(errors).let { hasError = true }

    /** Add errors from [wrapped] to the enclosing [collectingErrors]. */
    fun collect(res : VoidResult<E>) : Unit = when(res) {
        is CollectingResult.Error -> collectErrors(res.messages)
        is CollectingResult.Result -> Unit
    }

    fun collect(results: List<VoidResult<E>>) : Unit = results.forEach { res -> collect(res) }

    /** Add any errors from [list] to the enclosing [collectingErrors] and return the non-error results. */
    fun <R> collectAndFilter(list : List<CollectingResult<R,E>>) : List<R> {
        list.filterIsInstance<CollectingResult.Error<E>>().forEach { collectErrors(it.messages) }
        return list.filterIsInstance<CollectingResult.Result<R>>().map { it.result }
    }

    /** Add any errors from [map] to the enclosing [collectingErrors] and return the non-error results. */
    fun <K,V> collectAndFilter(map : Map<K,CollectingResult<V,E>>) : Map<K,V> {
        return map.entries.associateNotNull { (k,v) ->
            when (v) {
                is CollectingResult.Result<V> -> k to v.result
                is CollectingResult.Error<E> -> collectErrors(v.messages).let { null }
            }
        }
    }

    companion object {
        /**
         * Execute [body], wrapping any errors it produces in a [CollectingResult].
         *
         * Within [body], you can unwrap [CollectingResult]s using the `collect` methods; these will save errors to the
         * collector to be returned when [body] finishes.  You can also use the `bind` methods to end [body]
         * immediately in the case of an error.
         *
         * @see [ErrorCollector] for other convenient methods available inside of [body]
         * @see [ErrorCollectorTest.exampleWithCollector] and other tests in [ErrorCollector] for example usage
         */
        fun <R,E> collectingErrors(body : ErrorCollector<E>.() -> R) : CollectingResult<R,E> {
            val collector = ErrorCollector<E>(mutableListOf())
            return try {
                val result = collector.body()
                if (!collector.hasError) {
                    result.lift()
                } else {
                    collector.errors.asError()
                }
            } catch (@Suppress("SwallowedException") e: StopCollecting) {
                return collector.errors.asError()
            }
        }
    }
}

/** An exception used by [ErrorCollector] to terminate execution early */
private class StopCollecting : Throwable("ErrorCollector was used outside of `withErrors`")

private val logger = Logger(LoggerTypes.COMMON)

/**
 * Our Result Monad! Thanks John https://github.com/Certora/monads-by-example/blob/master/src/main/kotlin/result/CollectingResult.kt
 * @param R The type of a successful result
 * @param E The type of an error result
 */
sealed class CollectingResult<out R, out E> {
    data class Result<R>(val result: R) : CollectingResult<R, Nothing>() {
        override fun toString() = "$result"
    }
    data class Error<E>(val messages: List<E>) : CollectingResult<Nothing, E>() {
        override fun toString() = messages.joinToString(prefix = "<", postfix = ">") { it.toString() }

        constructor(vararg messages: List<E>?) : this(messages.filterNotNull().flatten())
    }

    fun isResult(): Boolean = this is Result

    fun resultOrNull(): R? = (this as? Result)?.result

    fun errorOrNull(): List<E>? = (this as? Error)?.messages

    fun resultOrThrow(f: (List<E>) -> Throwable): R = when (this) {
        is Result -> this.result
        is Error -> throw f(this.messages)
    }

    fun onError(f: (List<E>) -> Unit) = when (this) {
        is Result -> this
        is Error -> {
            f(this.messages)
            this
        }
    }

    fun resultOrExitProcess(exitStatus: Int, errorCallback: (List<E>) -> Unit): R =
        when (this) {
            is Result -> this.result
            is Error -> {
                errorCallback(this.messages)
                exitProcess(exitStatus)
            }
        }

    /** if we guaranteed there's no error, we force the result */
    fun force(): R = resultOrThrow {
        throw IllegalStateException("Result $this was expected to be successful")
    }

    companion object {
        val ok : Result<Unit> = Unit.lift()
        fun <R> R.lift() = Result(this)
        fun <E> E.asError() = Error(listOf(this))
        fun <E> List<E>.asError() = Error(this)

        fun <R, E> CollectingResult<R, E>?.transpose(): CollectingResult<R?, E> = this ?: null.lift()

        /**
         * @return the result of [f] on this if there are no errors (in this or [f]), and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, E> CollectingResult<R, E>.bind(f: (R) -> CollectingResult<Q, E>): CollectingResult<Q, E> =
            when (this) {
                is Error -> this
                is Result -> f(this.result)
            }

        /**
         * @return the result of [f] on this if there are no errors (in this or [f]), and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, E> CollectingResult<R, E>.map(f: (R) -> Q): CollectingResult<Q, E> = this.bind {
            f(it).lift()
        }

        /**
         * @return the result of [f] on this and [other] if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, S, E> CollectingResult<R, E>.map(
            other: CollectingResult<Q, E>,
            f: (R, Q) -> S
        ): CollectingResult<S, E> = this.bind(other) { r, q ->
            f(r, q).lift()
        }

        /**
         * @return the result of [f] on this, [second], and [third] if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, S, T, E> CollectingResult<R, E>.map(
            second: CollectingResult<Q, E>,
            third: CollectingResult<S, E>,
            f: (R, Q, S) -> T
        ): CollectingResult<T, E> = this.bind(second, third) { r, q, s ->
            f(r, q, s).lift()
        }

        /**
         * @return the result of [f] on this, [second], [third], and [fourth] if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, S, T, U, E> CollectingResult<R, E>.map(
            second: CollectingResult<Q, E>,
            third: CollectingResult<S, E>,
            fourth: CollectingResult<T, E>,
            f: (R, Q, S, T) -> U
        ) : CollectingResult<U, E> = this.bind(second, third, fourth) { r, q, s, t ->
            f(r, q, s, t).lift()
        }

        /**
         * @return the result of [f] on this, [second], [third], [fourth] and [fifth] if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, S, T, U, V, E> CollectingResult<R, E>.map(
            second: CollectingResult<Q, E>,
            third: CollectingResult<S, E>,
            fourth: CollectingResult<T, E>,
            fifth: CollectingResult<U, E>,
            f: (R, Q, S, T, U) -> V
        ) : CollectingResult<V, E> = this.bind(second, third, fourth, fifth) { r, q, s, t, u ->
            f(r, q, s, t, u).lift()
        }

        fun <R, E, F> CollectingResult<R, E>.mapErrors(f: (E) -> F) : CollectingResult<R, F> = this
            .bindEither(
                resultCallback = { it.lift() },
                errorCallback = { errors -> errors.map { error -> f(error) }.asError() }
            )

        fun <R, E, F> CollectingResult<R, E>.reduceErrors(f: (List<E>) -> F) : CollectingResult<R, F> = this
            .bindEither(
                resultCallback = { it.lift() },
                errorCallback = { errors -> f(errors).asError() }
            )

        /**
         * @return the result of [f] on this and [other] if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, S, E> CollectingResult<R, E>.bind(
            other: CollectingResult<Q, E>,
            f: (R, Q) -> CollectingResult<S, E>
        ): CollectingResult<S, E> =
            when (this) {
                is Error -> Error(
                    this.messages + ((other as? Error)?.messages ?: listOf())
                )
                is Result -> when (other) {
                    is Error -> other
                    is Result -> f(this.result, other.result)
                }
            }

        /**
         * @return the result of [f] on this, [second], and [third] if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, S, T, E> CollectingResult<R, E>.bind(
            second: CollectingResult<Q, E>,
            third: CollectingResult<S, E>,
            f: (R, Q, S) -> CollectingResult<T, E>
        ): CollectingResult<T, E> =
            if (this is Result && second is Result && third is Result) {
                f(this.result, second.result, third.result)
            } else {
                Error(
                    ((this as? Error)?.messages ?: listOf()) +
                            ((second as? Error)?.messages ?: listOf()) +
                            ((third as? Error)?.messages ?: listOf())
                )
            }

        /**
         * @return the result of [f] on this, [second], [third], and [fourth] if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, S, T, U, E> CollectingResult<R, E>.bind(
            second: CollectingResult<Q, E>,
            third: CollectingResult<S, E>,
            fourth: CollectingResult<T, E>,
            f: (R, Q, S, T) -> CollectingResult<U, E>
        ) : CollectingResult<U, E> =
            if(this is Result && second is Result && third is Result && fourth is Result) {
                f(this.result, second.result, third.result, fourth.result)
            } else {
                Error(
                    (this as? Error)?.messages,
                    (second as? Error)?.messages,
                    (third as? Error)?.messages,
                    (fourth as? Error)?.messages
                )
            }

        /**
         * @return the result of [f] on this, [second], [third], [fourth] and [fifth] if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, Q, S, T, U, V, E> CollectingResult<R, E>.bind(
            second: CollectingResult<Q, E>,
            third: CollectingResult<S, E>,
            fourth: CollectingResult<T, E>,
            fifth: CollectingResult<U, E>,
            f: (R, Q, S, T, U) -> CollectingResult<V, E>
        ) : CollectingResult<V, E> =
            if(this is Result && second is Result && third is Result && fourth is Result && fifth is Result) {
                f(this.result, second.result, third.result, fourth.result, fifth.result)
            } else {
                Error(
                    (this as? Error)?.messages,
                    (second as? Error)?.messages,
                    (third as? Error)?.messages,
                    (fourth as? Error)?.messages,
                    (fifth as? Error)?.messages
                )
            }

        fun <R, Q, E, F> CollectingResult<R, E>.bindEither(resultCallback: (R) -> CollectingResult<Q, F>, errorCallback: (List<E>) -> CollectingResult<Q, F>): CollectingResult<Q, F>  = when (this) {
            is Result -> resultCallback(this.result)
            is Error -> errorCallback(this.messages)
        }

        fun <R, Q, E, F> CollectingResult<R, E>.mapEither(resultCallback: (R) -> Q, errorCallback: (List<E>) -> F): CollectingResult<Q, F>  = when (this) {
            is Result -> resultCallback(this.result).lift()
            is Error -> errorCallback(this.messages).asError()
        }

        fun <R, E> List<CollectingResult<R, E>>.flatten(): CollectingResult<List<R>, E> {
            val (results, errors) = this.partitionMap { c ->
                when (c) {
                    is Result -> Either.Left(c.result)
                    is Error -> Either.Right(c.messages)
                }
            }

            /**
             * Note that this correctly handles the case where the [Error]s in the list have an empty list of messages:
             * `errors` is a list of lists, so even if there was an [Error] with an empty list of messages, `errors`
             * will not be empty - it will contain an empty list.
             */
            return if (this.any { it is Error }) {
                if (errors.isEmpty()) {
                    /*
                        NOTE: see CERT-1999):
                        [CollectingResult.Error] can have a possibly empty list of errors ([E]).
                     */
                    val errorMsg = "The flattening of this CollectingResult list ($this) resulted in a " +
                        "CollectingResult.Error instance with an empty error list. This may break error propagation."
                    logger.warn(
                        CertoraInternalException(
                            CertoraInternalErrorType.UTILS,
                            errorMsg
                        )
                    ) { errorMsg }
                }
                Error(errors.flatten())
            } else {
                Result(results)
            }
        }

        /**
         *
         */
        operator fun <R, E> CollectingResult<List<R>, E>.plus(other: CollectingResult<List<R>, E>) : CollectingResult<List<R>, E> {
            val receiver = this
            return collectingErrors {
                val x = bind(receiver)
                val y = bind(other)
                return@collectingErrors x + y
            }
        }

        /**
         * @return the result of [f] on this if there are no errors, and an [Error] otherwise
         * @note if [f] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <K, R, E, Q> Map<K, CollectingResult<R, E>>.bind(f: (Map<K, R>) -> CollectingResult<Q, E>): CollectingResult<Q, E> {
            val errors = mutableListOf<E>()
            var hasError = false
            this.values.forEach {
                if (it is Error) {
                    hasError = true
                    errors.addAll(it.messages)
                }
            }
            if (hasError) {
                return Error(errors)
            }
            return f(this.mapValues { it.value.force() })
        }

        fun <R> CollectingResult<R, Nothing>.safeForce() = this.force()

        fun <E> VoidResult<E>.map(second: VoidResult<E>): VoidResult<E> = this.map(second) { _, _ -> }
        fun <E> VoidResult<E>.bind(second: VoidResult<E>): VoidResult<E> = this.bind(second) { _, _ -> ok }
        fun <E> VoidResult<E>.bind(second: VoidResult<E>, third: VoidResult<E>) : VoidResult<E> = this.bind(second, third) { _, _, _ -> ok }
        fun <E> VoidResult<E>.bind(second: VoidResult<E>, third: VoidResult<E>, fourth: VoidResult<E>) = this.bind(second, third, fourth) { _, _, _, _ -> ok }

        fun <E> List<VoidResult<E>>.flattenToVoid(): VoidResult<E> = this.flatten().map {  }

        fun <E> Collection<E>.flattenToVoid(): VoidResult<E> = if(this.isEmpty()) { ok } else { this.toList().asError() }

        @JvmName("flattenToVoidVarArg")
        fun <E> flattenToVoid(
            vararg results: VoidResult<E>,
        ): VoidResult<E> = flattenToVoid(results.toList())

        @JvmName("flattenToVoidList")
        fun <E> flattenToVoid(
            results: List<VoidResult<E>,>
        ): VoidResult<E> = results.flattenToVoid()

        /**
         * @return the result of [result] after checking that all elements of [toBind] are not errors.  If `x` is in `toBind`, then
         * it is safe to call `x.force()` within [result]
         * @note if [result] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors]
         */
        fun <R, E> bindMany(vararg toBind: CollectingResult<*, E>, result: () -> CollectingResult<R, E>): CollectingResult<R, E> =
            bindMany(toBind.toList(), result)


        /**
         * Execute [result] after checking that all elements of [toBind] are not errors.  If `x` is in `toBind`, then
         * it is safe to call `x.force()` within [result]
         * @return the result of executing [result]
         * @note if [result] is a multiline closure, this can lead to very unreadable code; consider using [collectingErrors] instead
         */
        private fun <R, E> bindMany(toBind: List<CollectingResult<*, E>>, result: () -> CollectingResult<R, E>): CollectingResult<R, E> =
            toBind.flatten().bind {
                result()
            }
    }
}

typealias VoidResult<E> = CollectingResult<Unit, E>

fun <R, E> List<CollectingResult<R, E>>.reduceIndexed(f: (Int, R, R) -> CollectingResult<R, E>): CollectingResult<R, E> =
    this.reduceIndexed { i, l: CollectingResult<R, E>, r: CollectingResult<R, E> -> l.bind(r) { a, b -> f(i, a, b) } }

