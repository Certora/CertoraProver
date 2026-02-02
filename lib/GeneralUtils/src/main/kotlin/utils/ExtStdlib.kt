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

import com.certora.collect.*
import datastructures.stdcollections.*
import kotlinx.coroutines.flow.*
import utils.Color.Companion.bBlack
import utils.Color.Companion.bRed
import utils.Color.Companion.bRedBg
import utils.Color.Companion.green
import utils.Color.Companion.yellow
import utils.ModZm.Companion.lowOnes
import java.io.BufferedOutputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collector
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.collections.ArrayDeque
import kotlin.contracts.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

typealias SimplePair<T> = Pair<T, T>

// Use "foo.uncheckedAs<Bar<T>>() in place of "foo as Bar<T>" to avoid @Suppress stuff everywhere
@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <T> Any?.uncheckedAs(): T = this as T

// Do-nothing function to easily suppress unused parameter/variable warnings
@Suppress("NOTHING_TO_INLINE", "EmptyFunctionBlock")
inline fun <T> unused(@Suppress("UNUSED_PARAMETER") vararg param: T) { }

/** Returns `this`, after calling `f` if `this` is `null` */
inline fun <T> T?.onNull(f: () -> Unit) : T? {
    if (this == null) {
        f()
    }
    return this
}

fun <T> Iterator<T>.nextOrNull() = if (hasNext()) { next() } else { null }

fun <T> Iterable<T>.foldFirst(f: (T, T) -> T) : T =
    this.foldFirstOrNull(f) ?: throw NoSuchElementException("foldFirst")

fun <T> Iterable<T>.foldFirstOrNull(f: (T, T) -> T) : T? {
    val it = this.iterator()
    if(!it.hasNext()) {
        return null
    }
    var start = it.next()
    while(it.hasNext()) {
        start = f(start, it.next())
    }
    return start
}

fun <T> Iterable<T?>.monadicFold(f: (T, T) -> T?) : T? {
    val it = this.iterator()
    if(!it.hasNext()) {
        return null
    }
    var first = it.next() ?: return null
    while(it.hasNext()) {
        val x = it.next() ?: return null
        first = f(first, x) ?: return null
    }
    return first
}

fun <T, R> Iterable<T?>.monadicFold(init: R, f: (R, T) -> R?) : R? {
    val it = this.iterator()
    if(!it.hasNext()) {
        return init
    }
    var curr = init
    while(it.hasNext()) {
        curr = it.next()?.let { f(curr, it) } ?: return null
    }
    return curr
}

/**
 * Maps over [this] performing [f] on every element which returns a potentially null result. Returns a mapped list if
 * every result of [f] is a success (i.e. non-null), otherwise returns null.
 */
fun <T, R> Iterable<T?>.monadicMap(f: (T) -> R?) : List<R>? {
    val toRet = mutableListOf<R>()
    for(i in this) {
        if(i == null) {
            return null
        }
        toRet.add(f(i) ?: return null)
    }
    return toRet
}

fun <K, V, R> Map<K, V>.pointwiseBinop(other: Map<K, V>, default: V, binOp: (V, V) -> R) : Map<K, R> = this.pointwiseBinop(other, default, default, binOp)

fun <K, V, U, R> Map<K, V>.pointwiseBinopOrNull(map: Map<K, U>, f: (V, U) -> R?): Map<K, R> {
    val toReturn = mutableMapOf<K, R>()
    for((k, v) in this) {
        if(k !in map) {
            continue
        }
        toReturn[k] = f(v, map[k] ?: continue) ?: continue
    }
    return toReturn
}

fun <@Treapable K, V : Any> TreapMap<K, V>.pointwiseBinopOrNull(map: Map<K, V>, f: (V, V) -> V?): TreapMap<K, V> =
    this.merge(map) { _, v, otherV ->
        when {
            v == null || otherV == null -> null
            else -> f(v, otherV)
        }
    }


fun <K, U: Any> Map<K, U>.pointwiseMerge(other: Map<K, U>, merge: (U, U) -> U?) : Map<K, U> {
    val toReturn = this.toMutableMap()
    for((k, v) in other) {
        toReturn.merge(k, v, merge)
    }
    return toReturn
}

fun <@Treapable K, U : Any> TreapMap<K, U>.pointwiseMerge(other: TreapMap<K, U>, merge: (U, U) -> U?) : TreapMap<K, U> =
    this.merge(other) { _, v, otherV ->
        when {
            v == null -> otherV
            otherV == null -> v
            else -> merge(v, otherV)
        }
    }

fun <K, U, V, R> Map<K, V>.pointwiseBinop(other: Map<K, U>, leftDefault: V, rightDefault: U, binOp: (V, U) -> R) : Map<K, R> {
    val keys = this.keys.toMutableSet()
    keys.addAll(other.keys)
    val ret = mutableMapOf<K, R>()
    for(k in keys) {
        val o1 = this[k] ?: leftDefault
        val o2 = other[k] ?: rightDefault
        ret[k] = binOp(o1, o2)
    }
    return ret
}

infix fun <T> Collection<T>.containsAny(p: Iterable<T>) : Boolean = when (this) {
    is TreapSet<T> -> containsAny(p)
    else -> p.any { it in this }
}

infix fun <T> Sequence<T>.containsAny(p: Sequence<T>) : Boolean = p.any { it in this }

infix fun <T> Set<T>.containsAll(p: Iterable<T>): Boolean = p.all { it in this }

fun <T> Iterable<Set<T>>.intersectAll(): Set<T> {
    var res: MutableSet<T>? = null
    for (s in this) {
        if (res == null) {
            res = s.toMutableSet()
        } else {
            res.retainAll(s)
        }
    }
    return res.orEmpty()
}

fun <T, R : MutableSet<T>> Iterable<Set<T>>.intersectAllTo(res: R): R {
    var first = true
    for (s in this) {
        if (first) {
            res.addAll(s)
            first = false
        } else {
            res.retainAll(s)
        }
    }
    return res
}

fun <T> Iterable<Set<T>>.unionAll(): Set<T> {
    var res: MutableSet<T>? = null
    for (s in this) {
        if (res == null) {
            res = s.toMutableSet()
        } else {
            res.addAll(s)
        }
    }
    return res.orEmpty()
}

fun <@Treapable T> Sequence<TreapSet<T>>.intersectAll(): TreapSet<T> =
    this.fold(null as TreapSet<T>?) { acc, s -> acc?.intersect(s) ?: s }.orEmpty()
fun <@Treapable T> Iterable<TreapSet<T>>.intersectAll(): TreapSet<T> =
    this.asSequence().intersectAll()

fun <@Treapable T> Sequence<TreapSet<T>>.unionAll(): TreapSet<T> =
    this.fold(treapSetOf()) { acc, s -> acc.union(s) }
fun <@Treapable T> Iterable<TreapSet<T>>.unionAll(): TreapSet<T> =
    this.asSequence().unionAll()

fun <@Treapable T> Sequence<TreapSet<T>>.parallelUnionAll(): TreapSet<T> =
    this.parallelStream().reduce(treapSetOf<T>()) { acc, s -> acc.union(s) }
fun <@Treapable T> Iterable<TreapSet<T>>.parallelUnionAll(): TreapSet<T> =
    this.asSequence().parallelUnionAll()

/** Returns the first element in the [Iterable] that is not mapped to [null] by function [f]. Returns [null] if there
 * is no such element. */
inline fun <T, R> Iterable<T>.firstMapped(f: (T) -> R?) : R? {
    for(x in this) {
        val y = f(x)
        if(y != null) {
            return y
        }
    }
    return null
}

fun <T : Any> List<T?>.takeIfAllNotNull(): List<T>? = if (any { it == null }) { null } else { this.uncheckedAs<List<T>>() }


/**
 * Extension that should behave like mapValues but transforms the map rather than creating a new one
 */
fun <K, V> MutableMap<K, V>.mapValuesInPlace(transform: (MutableMap.MutableEntry<K, V>) -> V) {
    for (entry in this.entries) {
        entry.setValue(transform(entry))
    }
}

/**
 * Extension that modifies an entry in the map, performs the operation [f], then reverts the modification and returns
 * the result of [f]
 */
fun <K, V, T> MutableMap<K, V>.extend(key: K, value: V, f: () -> T): T {
    val prev = this.put(key, value)
    return f().also {
        if (prev != null) {
            this[key] = prev
        } else {
            this.remove(key)
        }
    }
}

/**
 * Extension similar to normal reduce or null if the list is empty
 */
fun <S, T: S> Sequence<T>.reduceEmpty(operation: (S, T) -> S): S? {
    if (this.firstOrNull() != null) {
        return this.reduce(operation)
    } else {
        return null
    }
}


/** Converts a Biginteger to a hexadecimal string representation.
 * Prepends `0x`. If there is a `-` sign, it's prepended before the `0x`. */
fun BigInteger.toHexString() =
    if (this.signum() >= 0) {
        "0x" + this.toString(16)
    } else {
        "-0x" + this.negate().toString(16)
    }

/**
 * Converts a (signed) Byte [this] to a hex string of 2 chars, e.g. 15 becomes "0f", 16 becomes "10"
 */
fun Byte.toHexString(): String {
    val translateThis = if (this < 0) {
        this.toInt().plus(256)
    } else {
        this.toInt()
    }
    return translateThis.toString(16).let { r ->
        check (r.length <= 2 && r.length > 0) { "Invalid byte $this -> $translateThis"}
        if (r.length == 1) { "0$r" } else r
    }
}

/**
  Returns the single value that appears (perhaps duplicated) within this collection, or null otherwise
 */
fun <T> Iterable<T>.uniqueOrNull() : T? {
    return if(this is Set) {
        this.singleOrNull()
    } else {
        this.monadicFold { a, b ->
            if(a == b) {
                a
            } else {
                null
            }
        }
    }
}

@Suppress("DANGEROUS_CHARACTERS", "FunctionName")
infix fun <T, R> T.`to?`(v: R?) : Pair<T, R>? = if(v == null) { null } else { this to v }



@Suppress("FunctionName")
fun <T, R, A> MonadicCollector(downstream: Collector<T, A, R>) : Collector<T?, *, R?> {
    class Ref(var x: A?)

    val supplier = downstream.supplier()
    val consumer = downstream.accumulator()
    val combiner = downstream.combiner()
    val finisher = downstream.finisher()
    return Collector.of({
        Ref(supplier.get())
    }, { x: Ref, e: T? ->
        if(e == null) {
            x.x = null
        } else if(x.x != null) {
            consumer.accept(x.x, e)
        }
    }, { a: Ref, b: Ref ->
        if(a.x == null) {
            a
        } else if(b.x == null) {
            b
        } else {
            a.x = combiner.apply(a.x, b.x)
            a
        }
    }, {
        it.x?.let(finisher::apply)
    })
}

@Suppress("FunctionName")
fun <T, R: MutableCollection<T>> MonadicNullCollector(mk: () -> R) : Collector<T?, *, R?> {
    return MonadicCollector(Collector.of({ mk() }, { a: R, b: T ->
        a.add(b)
    }, { a: R, b: R -> a.addAll(b); a }, { it }))
}

fun withStringDigest(f: (ObjectOutputStream) -> Unit) : String {
    val d = digestStream { f(it) }.digest()
    return d.toHexString()
}

/**
 * Calculates digest of the elements in the given [items] list by feeding them into an [ObjectOutputStream]
 * using [digestStream]
 **/
fun digestItems(items: List<Any>) : ByteArray = digestStream {
    for (item in items) {
        it.writeObject(item)
    }
    it.flush()
}.digest()

fun digestStream(f: (ObjectOutputStream) -> Unit): MessageDigest {
    val messageDigest = MessageDigest.getInstance("SHA-256")
    JavaBlobSerializer.CanonicalizingObjectOutputStream(
        BufferedOutputStream(
            DigestOutputStream(
                OutputStream.nullOutputStream(),
                messageDigest
            )
        )
    ).use { outputStream ->
        f(outputStream)
    }
    return messageDigest
}

fun <T, R> Stream<T>.mapNotNull(f: (T) -> R?): Stream<R> =
    this.map(f).filter { it != null }.uncheckedAs<Stream<R>>()

fun <K: Any, V> Map<K, V>.keysMatching(f: (K, V) -> Boolean): List<K> =
    mutableListOf<K>().also { list ->
        this.forEachEntry { (k, v) ->
            if (f(k, v)) {
                list.add(k)
            }
        }
    }

val `impossible!` : Nothing get() = error("Supposedly impossible case")

inline fun <@Treapable K, V> Map<K, V>.update(k: K, f: (V) -> V) : Map<K, V>? {
    val currValue = this[k] ?: return null
    return this + (k to f(currValue))
}

inline fun <@Treapable K, V> TreapMap<K, V>.update(k: K, f: (V) -> V) : TreapMap<K, V>? {
    val currValue = this[k] ?: return null
    return this + (k to f(currValue))
}

inline fun <@Treapable K, V> Map<K, V>.update(k: K, default: V, f: (V) -> V) : Map<K, V> {
    val currValue = this[k] ?: default
    return this + (k to f(currValue))
}

inline fun <@Treapable K, V> TreapMap<K, V>.update(k: K, default: V, f: (V) -> V) : TreapMap<K, V> {
    val currValue = this[k] ?: default
    return this + (k to f(currValue))
}

inline fun <K, V> MutableMap<K, V>.updateInPlace(k: K, default: V, f: (V) -> V) {
    val curr = this[k] ?: default
    this[k] = f(curr)
}

fun <T> MutableList<T>.removeLast() = this.removeAt(this.lastIndex)

fun <T> MutableList<T>.swap(pos1: Int, pos2: Int) {
    val v1 = this[pos1]
    this[pos1] = this[pos2]
    this[pos2] = v1
}

fun <T> Collection<T>.appendIf(cond: Boolean, f: () -> T) = if(cond) {
    this + f()
} else {
    this
}

fun <T> MutableCollection<T>.addIf(cond: Boolean, f: () -> T) {
    if(cond) {
        this.add(f())
    }
}

fun <T> MutableCollection<T>.addMatching(other: Collection<T>, filter: (T) -> Boolean) =
    other.forEach {
        if (filter(it)) {
            this.add(it)
        }
    }

fun <T> Iterable<T>.takeUntil(trigger: (T) -> Boolean) : List<T>? {
    val toReturn = mutableListOf<T>()
    for(i in this) {
        toReturn.add(i)
        if(trigger(i)) {
            return toReturn
        }
    }
    return null
}

fun <T> Iterable<T>.peek(count: Int): List<T> =
    take(count).also { check(it.size == count) { "Not enough elements" } }

fun <F, S, G> Pair<F, S>.mapFirst(f: (F) -> G): Pair<G, S> = Pair(f(this.first), this.second)
fun <F, S, T> Pair<F, S>.mapSecond(f: (S) -> T): Pair<F, T> = Pair(this.first, f(this.second))

fun <A1, A2, B> Iterable<Pair<A1, B>>.mapFirst(f: (A1) -> A2): List<Pair<A2, B>> =
    this.map { (first, second) -> Pair(f(first), second) }

fun <A, B1, B2> Iterable<Pair<A, B1>>.mapSecond(f: (B1) -> B2): List<Pair<A, B2>> =
    this.map { (first, second) -> Pair(first, f(second)) }

fun BigInteger.toIntOrNull() = this.toInt().takeIf { it.toBigInteger() == this }
fun BigInteger.toUIntOrNull() = this.toLong().toUInt().takeIf { it.toLong().toBigInteger() == this }

fun BigInteger.safeAsInt() = if (!isInt()) {
    throw IllegalStateException("Expected $this to be an Int")
} else {
    this.toInt()
}

fun BigInteger.isInt() = this.toInt().toBigInteger() == this

fun Int.toOrdinalString(): String {
    return when {
        this % 100 in 11..13 -> "${this}th"
        this % 10 == 1 -> "${this}st"
        this % 10 == 2 -> "${this}nd"
        this % 10 == 3 -> "${this}rd"
        else -> "${this}th"
    }
}

/**
 * creates one list from a collection of collections, where the list starts with the first elements each collection,
 * then the second elements, etc.
 *
 * the collections need not be of the same length
 */
fun <T> Collection<Collection<T>>.flatZip(): List<T> {
    val res = mutableListOf<T>()
    val iterators = this.map { it.iterator() }
    while(iterators.any { it.hasNext() }) {
        iterators.forEach {
            if (it.hasNext()) {
                res.add(it.next())
            }
        }
    }
    return res
}

/**
 * Adds the [primaryKey] and [secondaryKey] keys to the maps if not presented and then also adds the [value] to the set.
 */
fun <K1, K2, V> MutableMap<K1, MutableMap<K2, MutableSet<V>>>.add(primaryKey: K1, secondaryKey: K2, value: V) =
    ((this.getOrPut(primaryKey) { mutableMapOf() }).getOrPut(secondaryKey) { mutableSetOf() }).add(value)

inline fun <T, E> Iterable<T>.mapToSet(m : (T) -> E) =
    this.mapTo(mutableSetOf()) { m(it) }

inline fun <T, E> Iterable<T>.flatMapToSet(m : (T) -> Iterable<E>) =
    this.flatMapTo(mutableSetOf()) { m(it) }

inline fun <T, E> Sequence<T>.flatMapToSet(m : (T) -> Iterable<E>) =
    this.flatMapTo(mutableSetOf()) { m(it) }

inline fun <T, E: Any> Iterable<T>.mapNotNullToSet(m : (T) -> E?) =
    this.mapNotNullTo(mutableSetOf()) { m(it) }

inline fun <T, E> Sequence<T>.mapToSet(m : (T) -> E) =
    this.mapTo(mutableSetOf()) { m(it) }


fun <T, @Treapable E> Iterable<T>.mapToTreapSet(m : (T) -> E) =
    this.mapTo(treapSetBuilderOf()) { m(it) }.build()

fun <T, @Treapable E> Sequence<T>.mapToTreapSet(m : (T) -> E) =
    this.mapTo(treapSetBuilderOf()) { m(it) }.build()

fun <T, @Treapable E : Any> Iterable<T>.mapNotNullToTreapSet(m : (T) -> E?) =
    this.mapNotNullTo(treapSetBuilderOf()) { m(it) }.build()

fun <T, @Treapable E : Any> Sequence<T>.mapNotNullToTreapSet(m : (T) -> E?) =
    this.mapNotNullTo(treapSetBuilderOf()) { m(it) }.build()

fun <T, @Treapable E> TreapSet<T>.mapToTreapSet(m : (T) -> E) =
    treapSetOf<E>().mutate { set ->
        this.forEachElement { e ->
            set += m(e)
        }
    }

fun <T, @Treapable E> TreapSet<T>.mapNotNullToTreapSet(m : (T) -> E?) =
    treapSetOf<E>().mutate { set ->
        this.forEachElement { e ->
            m(e)?.let { set += it }
        }
    }

/** Like [mapToTreapSet], except optimized for the case where the source set is the same type as the result */
fun <@Treapable T : Any> TreapSet<T>.updateElements(transform: (T) -> T?): TreapSet<T> {
    var added = treapSetOf<T>()
    val filtered = retainAll { e ->
        when (val newE = transform(e)) {
            null -> false
            e -> true
            else -> {
                added += newE
                false
            }
        }
    }
    return filtered + added
}

inline fun <@Treapable K, V> buildTreapMap(builderAction: TreapMap.Builder<K, V>.() -> Unit): TreapMap<K, V> =
    treapMapOf<K, V>().builder().apply(builderAction).build()

inline fun <K, V> buildUnorderedMap(builderAction: MutableMap<K, V>.() -> Unit): Map<K, V> =
    mutableUnorderedMapOf<K, V>().apply(builderAction)

inline fun <K, V> buildMutableUnorderedMap(builderAction: MutableMap<K, V>.() -> Unit): MutableMap<K, V> =
    mutableUnorderedMapOf<K, V>().apply(builderAction)

fun <T> Sequence<T>.isEmpty() = !this.isNotEmpty()
fun <T> Sequence<T>.isNotEmpty() = this.iterator().hasNext()

fun <T> Sequence<T>.stream() = this.asIterable().stream()
fun <T> Sequence<T>.parallelStream() = this.asIterable().parallelStream()

fun <T, U> Collection<T>.zipPred(other: Collection<U>, pred: (T, U) -> Boolean) : Boolean {
    return this.size == other.size && this.asSequence().zip(other.asSequence()).all { (a, b) ->
        pred(a, b)
    }
}
val <K, V> Map<K, Collection<V>>.allValues : List<V> get() = values.flatten()

fun <K, V : Any, R> Map<K, V>.compute(k: K, compute: (K, V) -> R, default: R) : R {
    val curr = this[k]
    return if(curr != null) {
        compute(k, curr)
    } else {
        default
    }
}


/**
 * Buffers the source elements, and produces a new sequences from the buffered elements. This executes the pipeline up
 * to this point and buffers the intermediate results. The resulting sequences is directly backed by a collections and
 * can be safely and efficiently iterated multiple times.
 */
fun <T> Sequence<T>.buffer(): Sequence<T> {
    return this.toList().asSequence()
}

/**
 * Buffers the source elements, and produces a new one-time-use sequence from the buffered elements.  The resulting
 * sequence discards each element from the buffer as it is consumed.  This can reduce memory usage if the elements are
 * large.
 */
fun <T> Sequence<T>.oneTimeBuffer(): Sequence<T> {
    val queue = ArrayDeque<T>()
    this.forEach { queue.addLast(it) }
    return sequence {
        while (queue.isNotEmpty()) {
            yield(queue.removeFirst())
        }
    }
}

fun <T> Queue<T>.clone(): Queue<T> {
    val clonedQueue = LinkedList<T>()
    clonedQueue.addAll(this)
    return clonedQueue
}

inline fun <T> ArrayDeque<T>.consume(f : (T) -> Unit) {
    while (isNotEmpty()) {
        f(removeFirst())
    }
}

/**
    Iterates over the list, allowing each element to be garbage-collected once it's consumed (assuming no other references
    are retained).
 */
fun <T> List<T>.consuming(): Iterable<T> {
    val deque = ArrayDeque(this)
    return sequence<T> {
        deque.consume { yield(it) }
    }.asIterable()
}

fun <T> ArrayDeque<T>.addLasts(c : Collection<T>?) =
    c?.forEach { addLast(it) }

fun <T> arrayDequeOf(vararg elements : T) =
    elements.toCollection(ArrayDeque())

fun <T> arrayDequeOf(elements : Iterable<T>) =
    elements.toCollection(ArrayDeque())

// every string that has an instance of '"' without a preceding '\', should be replaced with '\"'
@Suppress("AvoidProblematicStringFunctions") // CER-1486
private val unescapedQuote = "(?<!\\\\)\\\"".toRegex()

@Suppress("AvoidProblematicStringFunctions")
fun String.escapeQuotes(): String {
    // need a double escaping on the backslash so that it'll actually appear
    return unescapedQuote.replace(this, "\\\\\"")
}

/**
 * Joins trimmed lines of this String.
 */
@Suppress("AvoidProblematicStringFunctions")
fun String.splitAndJoin(): String = split(System.lineSeparator()).let { lines ->
    lines.joinToString(separator = "") { it.trim() }
}

/**
 * Condenses this String to a single line with ellipsis between the first line
 * and the last line.
 */
@Suppress("AvoidProblematicStringFunctions")
fun String.condense(numLines: Int = 1): String =
    split(System.lineSeparator()).let { lines ->
        val firstLine = lines.firstOrNull().orEmpty().trim()
        if (numLines == 1) {
            if (lines.size <= 1) {
                firstLine
            } else {
                val lastLine = lines.lastOrNull().orEmpty().trim()
                "$firstLine...$lastLine"
            }
        } else {
            if (lines.size >= numLines) {
                lines.take(numLines)
            } else {
                lines
            }.map { it.trim() }
                .joinToString(System.lineSeparator())
        }
    }.escapeQuotes()

// ugly, but there's no real way to detect this
@Suppress("ForbiddenMethodCall")
fun String.isFullContractSrcMap() = startsWith("contract ")
@Suppress("ForbiddenMethodCall")
fun String.isFullFunctionSrcMap() = startsWith("function ")
@Suppress("ForbiddenMethodCall")
fun String.isIfConditionSrcMap() = startsWith("if ") || startsWith("if(")

/**
 * Cuts the assembly from the line where we define the block, will be null if no opening brace
 */
@Suppress("ForbiddenMethodCall")
fun String.condenseBlock() = substringBefore("{", "")
    // cut logInternal because autofinders sometimes get there as part of the modifiers. very annoying
    .substringBefore("logInternal").takeIf { it.isNotBlank() }

/** a notNull version of [associate] */
fun <T, K, V> Iterable<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> =
    buildMap {
        for (item in this@associateNotNull) {
            val (key, value) = transform(item) ?: continue
            this[key] = value
        }
    }

fun <T, K, V> Sequence<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> = Iterable { this.iterator() }.associateNotNull(transform)

/** Returns a new map, mapping values according to [transform]. Any null result will not be included in the result */
fun <K, V, R> Map<out K, V>.mapValuesNotNull(transform: (Map.Entry<K, V>) -> R?) =
    entries.associateNotNull { it.key `to?` transform(it) }


fun <K, V> Iterable<K>.associateWithNotNull(transform: (K) -> V?): Map<K, V> =
    buildMap {
        for (item in this@associateWithNotNull) {
            transform(item)?.let { this[item] = it }
        }
    }

fun checkNot(value : Boolean, lazyMessage : (() -> Any)? = null) =
    if (lazyMessage != null) {
        check(!value, lazyMessage)
    } else {
        check(!value)
    }

/** If [cond], return [transform] applied to [this], otherwise return [this] unchanged */
inline fun <B, T : B, R : B> T.letIf(cond : Boolean, transform: (T) -> R) =
    if (cond) {
        this.let(transform)
    } else {
        this
    }

/** If [cond], return the result of [block], otherwise return null */
inline fun <R> runIf(cond : Boolean, block: () -> R): R? =
    if (cond) {
        block()
    } else {
        null
    }

/** If [cond], return [block] applied to [this], otherwise return null */
inline fun <T, R> T.runIf(cond : Boolean, block: T.() -> R): R? =
    if (cond) {
        block()
    } else {
        null
    }

/** postfix alternative to the builtin cast operator. useful when you REALLY hate parenthesis */
inline fun <reified T> Any.tryAs(): T? = this as? T

/**
 * separates an [Iterable] into two lists.
 * returns a pair of [List] such that elements which [f] maps to [Either.Left] will be on the first list,
 * while elements which map to [Either.Right] will be on the second list.
 */
fun <T, L, R> Iterable<T>.partitionMap(f: (T) -> Either<L, R>): Pair<List<L>, List<R>> {
    val l = mutableListOf<L>()
    val r = mutableListOf<R>()

    for (elem in this) {
        when (val transformed = f(elem)) {
            is Either.Left -> l.add(transformed.d)
            is Either.Right -> r.add(transformed.d)
        }
    }

    return l to r
}

/**
  Potentially long-running loops can include this to allow the junit framework to interrupt them.
  DO NOT USE FOR ANY OTHER PURPOSE
  */
@Suppress("NOTHING_TO_INLINE")
inline fun yieldToTestTimeout() {
    if (Thread.interrupted()) {
        Thread.currentThread().interrupt()
        throw InterruptedException()
    }
}

/** Integer division that rounds up. */
infix fun Int.divRoundUp(other: Int) =
    if (this % other == 0)  {
        this / other
    } else {
        this / other + 1
    }

infix fun BigInteger.divRoundUp(other: BigInteger) =
    if (this % other == BigInteger.ZERO)  {
        this / other
    } else {
        this / other + BigInteger.ONE
    }

fun <T> Optional<T>.toNullable(): T? = orElse(null)

/** generates a 32-character long pseudo-random hexadecimal string */
fun generateUUID() = UUID.randomUUID().toString().replace("-", "")

/** current Unix time, aka epoch */
fun currentTimestamp() = Instant.now().toEpochMilli()

// String crap
// number of character to take from the beginning and end of a string message
private const val NUM_CHARS_TO_TAKE = 25

@Suppress("unused") // we may wish to use it - keeping it available without history digging
fun String.truncateString(): String = if (this.length > 2 * NUM_CHARS_TO_TAKE) {
    "${this.take(NUM_CHARS_TO_TAKE)}___${this.takeLast(NUM_CHARS_TO_TAKE)}"
} else { this }

fun String.prependSpaceIfNotBlank() = if (this.isNotBlank()) { " $this" } else this

/**
 * Computes all pairs of elements of the given iterable, where from "symmetric" pairs, e.g. (a, b) and (b, a), only one
 * is returned, and "reflexive" pairs (e.g. (a, a)) are not returned.
 */
fun <T> Iterable<T>.unorderedPairs() = sequence {
    val iterable = this@unorderedPairs
    val list = if (iterable is List<T>) {
        iterable
    } else {
        iterable.toList()
    }
    for (i in list.indices) {
        for (j in i + 1 until list.size) {
            yield(list[i] to list[j])
        }
    }
}

fun <T> Iterable<T>.filterAndIndex(predicate: (T) -> Boolean): List<IndexedValue<T>> =
    withIndex().filter { indexedValue -> predicate(indexedValue.value) }

operator fun <T> Flow<T>.plus(other: Flow<T>) = flow<T> {
    emitAll(this@plus)
    emitAll(other)
}

/**
 * if [id] maps all elements of [this] to the same value, returns the value.
 * otherwise returns null
 * the function also returns null in the case [this] is empty.
 */
fun <T, E> Iterable<T>.sameValueOrNull(id: (T) -> E): E? {
    val iterator = iterator()
    if (!iterator.hasNext()) {
        return null
    }
    val firstId = id(iterator.next())
    while (iterator.hasNext()) {
        if (id(iterator.next()) != firstId) {
            return null
        }
    }
    return firstId
}

/** returns true iff [id] maps all elements of [this] to the same value, or if the iterator is empty */
fun <T, E> Iterable<T>.allSame(id: (T) -> E): Boolean =
    !this.iterator().hasNext() || this.sameValueOrNull(id) != null

fun <T> Iterable<T>.sameValueOrNull(): T? = sameValueOrNull { it }
fun <T> Iterable<T>.allSame(): Boolean = allSame { it }

// to make biginteger life a little easier:

operator fun BigInteger.plus(other : Int) = this + other.toBigInteger()
operator fun Int.plus(other : BigInteger) = this.toBigInteger() + other

operator fun BigInteger.minus(other : Int) = this - other.toBigInteger()
operator fun Int.minus(other : BigInteger) = this.toBigInteger() - other

operator fun BigInteger.times(other : Int) = this * other.toBigInteger()
operator fun Int.times(other : BigInteger) = this.toBigInteger() * other

operator fun BigInteger.div(other : Int) = this / other.toBigInteger()
operator fun Int.div(other : BigInteger) = this.toBigInteger() / other

fun Byte.toBigInteger() = this.toLong().toBigInteger()
fun UByte.toBigInteger() = this.toLong().toBigInteger()
fun Short.toBigInteger() = this.toLong().toBigInteger()
fun UShort.toBigInteger() = this.toLong().toBigInteger()
fun UInt.toBigInteger() = this.toLong().toBigInteger()
fun ULong.toBigInteger() = when {
    this <= Long.MAX_VALUE.toULong() -> this.toLong().toBigInteger()
    else -> BigInteger(this.toString())
}

/** N.B. the resulting list is the length of the smallest of the three input lists */
fun <T1, T2, T3> zip(a: Iterable<T1>, b: Iterable<T2>, c: Iterable<T3>): List<Triple<T1, T2, T3>> {
    val it1 = a.iterator()
    val it2 = b.iterator()
    val it3 = c.iterator()
    return buildList {
        while (it1.hasNext() && it2.hasNext() && it3.hasNext()) {
            add(Triple(it1.next(), it2.next(), it3.next()))
        }
    }
}

fun <T: Comparable<T>> maxOf(args: Collection<T>, resultIfEmpty: T): T =
    if (args.isEmpty()) {
        resultIfEmpty
    } else {
        args.max()
    }

fun hexStringToBytes(hexBytecode: String): List<UByte> =
    hexBytecode.chunked(2).monadicMap { it.toUByteOrNull(16) }
        ?: error("Failed to convert to bytes $hexBytecode")

@OptIn(ExperimentalUnsignedTypes::class)
fun List<UByte>.toByteArray() = toUByteArray().asByteArray()

/**
    Extracts a BigInteger from a region of a list of bytes.  The bytes are assumed to be in big-endian order, and
    represent a positive integer always.

    If the region extends beyond the end of the list, it is truncated to the end of the list.  If the resulting region
    is empty, the result is zero.
 */
fun List<UByte>.toPositiveBigIntegerTruncating(start: Int = 0, len: Int = size): BigInteger =
    when (val adj = kotlin.math.min(len, size - start)) {
        0 -> BigInteger.ZERO
        else -> BigInteger(1 /* positive */, subList(start, start + adj).toByteArray())
    }

/**
    Extracts a BigInteger from a region of a list of bytes.  The bytes are assumed to be in big-endian order, and
    represent a positive integer always.
 */
fun List<UByte>.toPositiveBigIntegerOrNull(start: Int = 0, len: Int = size): BigInteger? =
    when {
        start + len > this.size -> null
        else -> BigInteger(1 /* positive */, subList(start, start + len).toByteArray())
    }

fun List<UByte>.toPositiveBigInteger(start: Int = 0, len: Int = size): BigInteger =
    toPositiveBigIntegerOrNull(start, len)
    ?: throw IndexOutOfBoundsException("Invalid list range: $start + $len > $size")

fun <T, T1 : T, T2 : T> ite(c : Boolean, t : T1, e : T2) : T =
    if (c) {
        t
    } else {
        e
    }

/**
 * Taken from:
 * [https://stackoverflow.com/questions/48443167/kotlin-lateinit-to-val-or-alternatively-a-var-that-can-set-once]
 *
 * Use it like this:
 *   `var property: String by initOnce()`
 * This property can be set only once, and cannot be accessed before it is set.
 */
class InitOnceProperty<T> : ReadWriteProperty<Any, T> {
    private object EMPTY

    private var value: Any? = EMPTY

    override fun getValue(thisRef: Any, property: KProperty<*>): T {
        check(value != EMPTY) { "Value isn't initialized" }
        return value.uncheckedAs()
    }

    override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
        check(this.value == EMPTY) { "Value is already initialized to $value" }
        this.value = value
    }
}


/** Returns the list of elements that have the maximal [score] with the iterable */
inline fun <T, R : Comparable<R>> Iterable<T>.maxsBy(score: (T) -> R): List<T> =
    map { it to score(it) }
        .maxsWith { pair1, pair2 -> pair1.second.compareTo(pair2.second) }
        .map { it.first }

/** Returns the list of maximal elements according to [comparator] */
inline fun <T> Iterable<T>.maxsWith(comparator: (T, T) -> Int): List<T> {
    val result = mutableListOf<T>()
    forEach { t ->
        if (result.isEmpty()) {
            result += t
        } else {
            val c = comparator(t, result.first())
            when {
                c == 0 -> result += t
                c > 0 -> {
                    result.clear()
                    result += t
                }
            }
        }
    }
    return result
}


inline fun <T> Iterable<T>.filterToSet(predicate: (T) -> Boolean): Set<T> {
    return filterTo(mutableSetOf(), predicate)
}

fun ByteArray.toHexString(): String =
    this.joinToString("") { String.format(Locale.ROOT, "%02X", it) }

fun List<Byte>.toHexString(): String =
    this.joinToString("") { String.format(Locale.ROOT, "%02X", it) }

fun unsupported(msg : String? = null): Nothing =
    if (msg != null) {
        throw UnsupportedOperationException(msg)
    } else {
        throw UnsupportedOperationException()
    }

// compiler version manipulations, when compiler versions use semantic versioning (solc and vyper both respect it)
data class CompilerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
): Comparable<CompilerVersion> {
    override fun compareTo(other: CompilerVersion): Int =
        compareBy(CompilerVersion::major, CompilerVersion::minor, CompilerVersion::patch).compare(this, other)

    override fun toString(): String = "${major}.${minor}.${patch}"

    companion object {
        fun fromStringOrNull(s: String): CompilerVersion? {
            @Suppress("ForbiddenMethodCall")
            val splitByDot = s.split(".")
            if (splitByDot.size != 3) {
                return null
            }
            return splitByDot[0].toIntOrNull()?.let { major ->
                splitByDot[1].toIntOrNull()?.let { minor ->
                    splitByDot[2].toIntOrNull()?.let { patch ->
                        CompilerVersion(major, minor, patch)
                    }
                }
            }
        }
    }
}


/** lexicographic ordering on pairs */
fun <T : Comparable<T>, S : Comparable<S>> Pair<T, S>.compareTo(other : Pair<T, S>) =
    first.compareTo(other.first).let {
        if (it == 0) {
            second.compareTo(other.second)
        } else {
            it
        }
    }



interface SuspendCloseable {
    suspend fun close()
}

@OptIn(ExperimentalContracts::class)
public inline suspend fun <T : SuspendCloseable?, R> T.use(block: (T) -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        when {
            this == null -> {}
            exception == null -> close()
            else ->
                try {
                    close()
                } catch (_: Throwable) {
                    // ignore; `e` is still propagating
                }
        }
    }
}

inline fun <T> Semaphore.withPermit(action: () -> T): T {
    ForkJoinPool.managedBlock(object : ForkJoinPool.ManagedBlocker {
        override fun isReleasable(): Boolean {
            return tryAcquire()
        }
        override fun block(): Boolean {
            acquire()
            return true
        }
    })
    try {
        return action()
    } finally {
        release()
    }
}

fun workingDirectory(): Path {
    val property: String = System.getProperty("user.dir") // should be non-null under ordinary running conditions
    return Path(property)
}

fun <@Treapable E> buildTreapSet(builderAction: TreapSet.Builder<E>.() -> Unit): TreapSet<E> =
    treapSetBuilderOf<E>().apply(builderAction).build()


// Kotlin stdlib only defines these up to component5
operator fun <T> List<T>.component6() = this[5]
operator fun <T> List<T>.component7() = this[6]
operator fun <T> List<T>.component8() = this[7]
operator fun <T> List<T>.component9() = this[8]
operator fun <T> List<T>.component10() = this[9]


/** A simple way to measure and print clock time around events */
class Mark private constructor(val msg : String, val num : Int, val start : Long = System.currentTimeMillis()) {
    companion object {
        private val markCount = AtomicInteger()
        operator fun invoke(msg: String) =
            Mark(msg, markCount.getAndIncrement()).also {
                @Suppress("ForbiddenMethodCall")
                println("Starting $it")
            }

        fun <R> markAround(msg: String, f:() -> R) : R {
            val m = Mark(msg)
            return f().also {
                m.end()
            }
        }

    }

    override fun toString() = "$msg($num)"

    fun end() {
        val time = (System.currentTimeMillis() - start)
        val coloredTime = when {
            time < 100 -> time.toString()
            time < 1_000 -> time.green
            time < 10_000 -> time.yellow
            time < 100_000 -> time.bRed
            else -> time.bRedBg.bBlack
        }
        @Suppress("ForbiddenMethodCall")
        println("Ending $this -- ${coloredTime}ms")
    }
}

/**
 * returns the sequence : `succ(start), succ(succ(start), succ(succ(succ(start))), ...` until null is reached.
 * The sequence does not include [start] itself.
 */
fun <V: Any> generateSequenceAfter(start : V, succ : (V) -> V?) =
    succ(start)
        ?.let { generateSequence(it, succ) }
        .orEmpty()

fun <X, Y> Pair<X, Y>.reverse() = second to first

/**
 * An optimized version of:
 * ```
 * zipWithNext().forEach {
 *    action(it.first, it.second)
 * }
 * ```
 */
inline fun <T> List<T>.forWithNext(action : (T, T) -> Unit) {
    for (i in 0 until size - 1) {
        action(this[i], this[i+1])
    }
}

fun <T> repeatedMap(times : Int, init : T, transform : (T) -> T) : T {
    var result = init
    repeat(times) {
        result = transform(result)
    }
    return result
}

infix fun <T: Comparable<T>> ClosedRange<T>.overlaps(that: ClosedRange<T>): Boolean {
    return this.start <= that.endInclusive && this.endInclusive >= that.start
}

infix fun <T: Comparable<T>> ClosedRange<T>.subsumes(that: ClosedRange<T>): Boolean {
    return this.start <= that.start && this.endInclusive >= that.endInclusive
}

fun <K, V> Stream<Pair<K, V>>.toMap(): Map<K, V> = this.collect(Collectors.toMap({it.first}, {it.second}))

/**
 * tries to split [this] at the first occurence of [delimiter], and returns the string before and after
 * on success, or null on failure. neither of the returned strings will contain [delimiter].
 */
fun String.splitOnce(delimiter: String): Pair<String, String>? {
    @Suppress("ForbiddenMethodCall")
    val split = this.split(delimiter, limit = 2)

    val before = split[0]
    val after = split.getOrNull(1) ?: return null

    return Pair(before, after)
}

/**
    Returns a sequence of elements from this sequence, skipping adjacent duplicates.

    For example, `sequnenceOf(1, 1, 2, 2, 3, 3, 3, 2).skipAdjacentDuplicates()` yields `(1, 2, 3, 2)`.
 */
fun <T> Sequence<T>.skipAdjacentDuplicates() = sequence<T> {
    var isFirst = true
    var previous: T? = null
    forEach {
        if (isFirst || it != previous) {
            yield(it)
            previous = it
            isFirst = false
        }
    }
}

/**
 * Parses a BigInteger into parts based on given lengths in bits.
 * Each length specifies how many bits to extract, from left to right.
 */
fun BigInteger.parseToParts(vararg lengths: Int): List<BigInteger> {
    require(lengths.all { it > 0 }) { "All lengths must be positive" }

    var remaining = this
    return lengths.reversed().map { l ->
        (remaining and lowOnes(l)).also {
            remaining = remaining shr l
        }
    }.reversed()
}

fun OpenEndRange<BigInteger>.stepBy(step: BigInteger) = sequence<BigInteger> {
    var it = this@stepBy.start
    while(it < this@stepBy.endExclusive) {
        yield(it)
        it += step
    }
}

fun ClosedRange<BigInteger>.stepBy(step: BigInteger) = sequence<BigInteger> {
    var it = this@stepBy.start
    while(it <= this@stepBy.endInclusive) {
        yield(it)
        it += step
    }
}

/**
    Runs the given [block] returns its result wrapped in a [Result].  Any exceptions other than [CancellationException]
    are caught and returned as a failure result.  If a [CancellationException] is thrown, it is rethrown, allowing this
    to be used safely in coroutines.
 */
public inline fun <R> coRunCatching(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: CancellationException){
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
