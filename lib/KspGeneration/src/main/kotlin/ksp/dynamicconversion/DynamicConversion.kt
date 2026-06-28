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

package ksp.dynamicconversion

import kotlin.time.Duration
import kotlin.reflect.KClass

/** Custom exception for dynamic conversion */
class DynamicConversionException(msg: String): Exception(msg)

/**
 * Annotate data classes with this annotation to auto-generate a dynamic copy
 * method `T.copy(Map<String,Any>): T` (that copies an object and overrides
 * individual properties from a string-indexed map, similar to the regular
 * `copy()` that uses named parameters) and the dynamic factory method
 * `T.Companion.constructFrom(Map<String,Any>): T` that constructs a new object
 * from such a map.
 * The values in the map should either have the correct type, or they need to
 * be convertible in one of the following ways:
 * - the value is of type `String` and the property is either `Boolean` or
 *   `Int` (see [convert]).
 * - the property is annotated with `@ConvertibleWith(Conv::class)` and `Conv`
 *   is derived from [DynamicConverter] with the appropriate type and provides
 *   the necessary conversion.
 */
@Target(AnnotationTarget.CLASS)
annotation class AddDynamicConversion

/**
 * Annotate a property with a custom conversion class that is derived from
 * [DynamicConverter]. Use as follows: `@ConvertibleWith(Conv::class)`
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ConvertibleWith(val converter: KClass<*>)

/**
 * Annotate a property to exclude it from the generated dynamic
 * `copy(Map<String,Any>)` conversion in normal runs. A key matching such a
 * property in the override map is ignored (the property keeps its current
 * value) instead of being set, UNLESS this is a development or CI run (see
 * [excludedDynamicConversionAllowed]). Use this for properties that must not be
 * settable through the dynamic (e.g. command-line `name{prop=...}`) path in
 * production. The regular named-argument `copy(...)` is unaffected, so trusted
 * code can always set the property directly.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ExcludeFromDynamicConversion

/**
 * Whether a property annotated [ExcludeFromDynamicConversion] may still be set through the dynamic
 * `copy(Map<String,Any>)` path. Excluded properties are honored only in development or CI runs,
 * i.e. when the `CERTORA_DEV_MODE` or `CI` environment variable is set; otherwise the override is
 * ignored. Called from generated dynamic-conversion code.
 */
fun excludedDynamicConversionAllowed(): Boolean =
    excludedDynamicConversionAllowed(System.getenv("CERTORA_DEV_MODE"), System.getenv("CI"))

/** Testable core of [excludedDynamicConversionAllowed]: allowed iff either env value is set. */
fun excludedDynamicConversionAllowed(certoraDevMode: String?, ci: String?): Boolean =
    certoraDevMode != null || ci != null

/**
 * Helper for generated dynamic-conversion code on an [ExcludeFromDynamicConversion] property:
 * returns [override]'s result when the override is allowed (see [excludedDynamicConversionAllowed]),
 * otherwise the unchanged [current] value. [override] is only evaluated when allowed.
 */
inline fun <T> applyExcludedOverride(current: T, override: () -> T): T =
    if (excludedDynamicConversionAllowed()) {
        override()
    } else {
        current
    }

/** Helper method for unchecked casting, copied from ExtStdlib.kt */
@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun <T> Any?.uncheckedAs(): T = this as T

/**
 * Generic conversion from [Any] to some type [T]. Succeeds if [v] already
 * holds a value of type [T], or if [v] is of type [String] and [T] is any of
 * [Boolean], [Duration], or [Int]. Throws a [DynamicConversionException]
 * otherwise, hinting at a custom converter via [ConvertibleWith].
 */
inline fun <reified T> convert(v: Any): T {
    return when (v) {
        is T -> v
        is String -> when (T::class) {
            Boolean::class -> v.toBoolean() as T
            Duration::class -> Duration.parse(v) as T
            Int::class -> v.toInt() as T
            else -> throw DynamicConversionException("Can not convert \"${v}\" to ${T::class.simpleName}. Please provide a custom converter using @ConvertibleWith().")
        }
        else -> throw DynamicConversionException("Can only convert from String, but found \"${v}\" of type ${v::class.simpleName}. Please provide a custom converter using @ConvertibleWith().")
    }
}

/**
 * Conversion from [Any] to some type [T] based on a custom converter [conv].
 * If [v] already holds a value of type [T] returns [v], otherwise calls [conv]
 * on [v] and returns the result.
 */
inline fun <reified T> convert(v: Any, conv: DynamicConverter<T>): T =
    if (v is T) { v } else { conv(v) }


/**
 * Conversion from [Any], assumed to be a list, to [List<T>] based generic
 * conversion from [Any] to [T]. Applies [convert] to every element of [v] if
 * [v] is a list, otherwise throws a [DynamicConversionException].
 */
inline fun <reified T> convertList(v: Any): List<T> =
    if (v is List<*>) {
        v.mapNotNull { e -> e?.let { convert(it) } }
    } else {
        throw DynamicConversionException("Can not convert \"${v}\" to a List<${T::class.simpleName}>.")
    }

/**
 * Conversion from [Any], assumed to be a list, to [List<T>] based on a custom
 * converter [conv] that converts [Any] to [T]. Applies [convert] to every
 * element of [v] if [v] is a list, otherwise throws a [DynamicConversionException].
 */
inline fun <reified T> convertList(v: Any, conv: DynamicConverter<T>): List<T> =
    if (v is List<*>) {
        v.mapNotNull { e -> e?.let { convert(it, conv) } }
    } else {
        throw DynamicConversionException("Can not convert \"${v}\" to a List<${T::class.simpleName}>.")
    }

/** Interface for a custom converter to be used with [ConvertibleWith]. */
interface DynamicConverter<T> {
    /** Convert [v] to type [T] or throw a [DynamicConversionException] */
    operator fun invoke(v: Any): T
}

