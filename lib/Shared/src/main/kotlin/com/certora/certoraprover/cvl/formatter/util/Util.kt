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

package com.certora.certoraprover.cvl.formatter.util

import com.certora.certoraprover.cvl.Ast
import com.certora.certoraprover.cvl.ParserCVL.parse_with_errors
import config.Config
import datastructures.stdcollections.*
import spec.CVLSource
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

fun parseToAst(source: String): CollectingResult<Ast, CVLError> {
    val csf = java_cup.runtime.ComplexSymbolFactory()

    val cvlSource = CVLSource.Raw("dummy file", source, isImported = false)
    val lexer = cvlSource.lexer(csf, Config.VMConfig, cvlSource.name)

    return parse_with_errors(lexer, csf, cvlSource.origpath, cvlSource.isImported)
}

/**
 * a slightly-fancier [kotlin.check].
 * throws if the [invariant] described in [reason], is false.
 * an optional hint can be given as [lazyHint].
 */
@OptIn(ExperimentalContracts::class)
internal fun ensure(
    invariant: Boolean,
    reason: String,
    lazyHint: (() -> String)? = null
) {
    contract {
        returns() implies invariant
    }

    if (!invariant) {
        invariantBroken(reason, lazyHint)
    }
}

/**
 *  see [ensure], but this version always throws.
 *  use this if you need a function which unconditionally diverges (i.e. by "returning" [Nothing]).
 *  since [ensure] does not (because it cannot know at compile time), it may not typecheck.
 */
internal fun invariantBroken(reason: String, lazyHint: (() -> String)? = null): Nothing {
    val hint = lazyHint?.invoke()
    val message = if (hint != null) {
        "invariant broken: $reason ($hint)"
    } else {
        "invariant broken: $reason"
    }

    throw IllegalStateException(message)
}

/** because Java's index search APIs return negative values if an item isn't found (for JVM efficiency reasons) */
internal fun Int.foundOrNull(): Int? = this.takeIf { it >= 0 }

/** inserts (the result of) [separator] between each element of [this] */
internal inline fun <T> List<T>.intersperse(separator: () -> T): List<T> {
    val newList: ArrayList<T> = if (this.isEmpty()) {
        return emptyList()
    } else {
        ArrayList(2 * this.size - 1)
    }

    this.forAllButLast { elem ->
        newList.add(elem)
        newList.add(separator())
    }
    newList.add(this.last())

    return newList
}

/**
 * see [Iterable.zipWithNext].
 * unlike that method, this allows for partial windows at the end.
 * since the window size is 2, this means exactly one such window,
 * which has 1 element instead of 2.
 *
 * in other words: exactly like [Iterable.zipWithNext], except that the second parameter
 * passed to [transform] is now nullable, and [transform] will be invoked once with the
 * last element and null.
 *
 * impl: for easier auditing, we reuse the impl of [Iterable.zipWithNext]
 */
internal inline fun <T, R> Iterable<T>.zipWithNextPartial(transform: (a: T, b: T?) -> R): List<R> {
    val iterator = iterator()
    if (!iterator.hasNext()) { return emptyList() }
    val result = mutableListOf<R>()
    var current = iterator.next()
    while (iterator.hasNext()) {
        val next = iterator.next()
        result.add(transform(current, next))
        current = next
    }

    val lastElement: T = current
    val elementPastLast: T? = null
    result.add(transform(lastElement, elementPastLast))

    return result
}

/**
 * see [Iterable.zipWithNext].
 * unlike that method, [transform] will be invoked once on every element (so one additional time),
 * and the previous element in the iteration is made available.
 * since iteration starts with the first element, the previous element on the first iteration
 * (and only on the first iteration) will be null.
 *
 * impl: for easier auditing, we reuse the impl of [Iterable.zipWithNext]
 */
internal inline fun <T, R> Iterable<T>.zipWithPrevious(transform: (a: T?, b: T) -> R): List<R> {
    val iterator = iterator()
    if (!iterator.hasNext()) { return emptyList() }
    val result = mutableListOf<R>()
    var previous = null as? T?
    while (iterator.hasNext()) {
        val current: T = iterator.next()
        result.add(transform(previous, current))
        previous = current
    }
    return result
}

/**
 * allows writing `foo.letOrEmpty(::block)` instead of `foo?.let(::block).orEmpty()`.
 * for longer chains, you should probably use `foo?.bar?.baz?.let(::transform).orEmpty()`
 * instead of `foo.letOrEmpty { transform(it.bar.baz) }`.
 *
 * and yes, I get to dictate coding style now, because this is my own personal KDoc.
 */
@OptIn(ExperimentalContracts::class)
internal inline fun <T, R> T?.letOrEmpty(block: (T) -> List<R>): List<R> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }

    // explicitly not delegating to `let` here, just in case it matters (for example, due to the contract)  .
    return if (this != null) { block(this) } else { emptyList() }
}