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

package allocator

import caching.WithMemento
import tac.NBId
import tac.StartBlock
import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 *
 * On Extension classes:
 *
 * The following annotations describe the mechanisms by which so-called "extension classes" are generated. Extension classes
 * are designed to add functionality to a class declarations without being able to modify class declarations.
 *
 * Extension classes are generated within special source files that are generally not available except at compilation time
 * after the KSP framework has run and generated them. Thus, it is difficult (if not impossible?) to reliably access the
 * code generated within these extension classes "by name" within code.
 *
 * Thus, the extension class functionality described here relies on some conventions. When "extending" a class C with some
 * functionality, the code is placed in a class `C$Name` (in the same package as C)
 * where `Name` is the "sub-name" of the extension class that is appropriately descriptive name of the functionality
 * being implemented. These classes by convention have a single, static method called `impl` which provides the implementation
 * of the funtionality being added.
 *
 * For convenience, the [vc.data.ExtensionGetter] interface provides a mixin which allows easy (refelctive)
 * access to the extension method for a class `C`. When pulled into a class C, calling `c.getExtensionMethod(Name)` gets
 * the `impl` method defined in the extension class `C$Name`. NB that the Method returned by this function is static, and
 * thus its first argument must always be null.
 */

/**
 * Eligible classes annotated with this field will have an "extension class" generated which supports consistent
 * remapping of auto-generated IDs that appear within this class' state.
 * An eligible class is a data class with at least one field that:
 * 1. Has any Int valued field annotated with [GeneratedBy], or
 * 2. Has a field whose type is annotated with [GenerateRemapper]
 * 3. Has a field whose types implements [vc.data.UniqueIdEntity]
 * 4. Has a field whose type is Map<K, V>, where V satisfies one of these requirements
 *
 * If a data class is found to have a field whose type has this annotation, and it itself is not annotated
 * with [GenerateRemapper] or [SuppressRemapWarning], compilation will fail with an error.
 *
 * However, if one of the types of fields the annotated class is found to satisfy the following:
 * a. It does not satisfy one of the conditions 1 - 4 above, AND
 * b. It is a parameterized type, the arguments of which satisfy conditions a & b
 * The generation process will conservatively fail with an error, is the remapper will refuse to generate
 * what it considers to be an incomplete remapper.
 *
 * The extension class mechanism cannot add methods directly to a class annotated with [GenerateRemapper].
 * Users of this can easily use the generated extension class by implementing the [vc.data.RemapperEntity]
 * interface.
 *
 * The extension class is given the name Remapper. The generated method expects two arguments: an object
 * of the type annotated by this annotation, and a remap function with the signature `(Any, Int, () -> Int) -> Int`.
 * See the [vc.data.UniqueIdEntity] documentation for the meaning of this function.
 *
 * Annotating an ineligible class with this annotation will result in a compilation error.
 */
@Target(AnnotationTarget.CLASS)
annotation class GenerateRemapper

/**
 * Classes annotated with this annotation will have an extension class generated which supports consistently remapping
 * [tac.MetaKey] value pairs according to the [GeneratedBy] annotation.
 *
 * The generated extension class is given the "sub-name" "MetaMap". The extension function expects three arguments:
 * 1. A [tac.MetaKey],
 * 2. The Int value corresponding to this meta key, and
 * 3. A remapper function with the signature `(Any, Int, () -> Int) -> Int`, whose interpretation is as given in the
 * [vc.data.UniqueIdEntity].
 *
 * As with [GenerateRemapper], this extension function is not easily accessible directly, and should be
 * accessed with [vc.data.ExtensionGetter]
 */
@Target(AnnotationTarget.CLASS)
annotation class GenerateMetaMapper

/**
 * Annotates an Int field or a MetaKey as being generated by the corresponding allocator sequence [by].
 *
 * When applied to an Int field within a data class, this annotation serves two purposes:
 * 1. It indicates that it is an error for the containing data class to neither implement [vc.data.UniqueIdEntity] nor
 * have the [GenerateRemapper] annotation
 * 2. If the data class containing this field is annotated with [GenerateRemapper], the generated remapper will automatically
 * remap this field.
 *
 * When applicated to a MetaKey field, it indicates that values associated with that meta key are to be remapped consistently,
 * via the [GenerateMetaMapper] functionality. In order for the remapping process to work, the following conditions must hold:
 * 1. The MetaKey field being annotated must be explicitly annotated with the type `MetaKey<Int>`
 * 2. The MetaKey field must be "statically addressable", i.e., it must be declared in a singleton object
 *
 * If either of these two conditions is not met, compilation will fail with an error.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class GeneratedBy(val by: Allocator.Id, val source: Boolean = false)

/**
 * Indicates that it is not an error for a data class to be potentially remappable but have no such remapping functionality.
 * This can be applied at the file level, which suppresses all such warnings within the file, or for a specific class.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
annotation class SuppressRemapWarning

/**
 * TODO: document ... e.g., what's the purpose of this class?
 */
object Allocator : WithMemento<Allocator.Memento> {
    enum class Id {
        ASSERTS,
        RULE_OUTPUT,
        LIVE_STATISTICS,
        RULE_ID,
        TREE_VIEW_NODE_ID,
        CALL_SUMMARIES,
        RETURN_SUMMARIES,
        TEMP_VARIABLE,
        MEMORY_PARTITION,
        GHOST_CHECKPOINTS,
        ASSUME_GROUP,
        ABI,
        ABI_RANGE,
        BLOCK_FRESH_COPY,
        CALL_ID,
        PC_FOR_NBID,
        STORAGE_READ,
        SPLIT_ARG,
        INTERNAL_FUNC,
        CONTRACT_CREATION,
        CLONES,
        COUNTEREXAMPLE,
        LOOP,
        ASSERT_IN_CVL_BLOCK,
        BRANCH,
        SATISFIES,
        CVL_EVENT,
        CVL_SERIALIZATION,
        DIV_0_NONDET,
        MOD_0_NONDET,
        INTERNAL_CALL_SUMMARY,
        REQUIRE_INVARIANT_CMDS,
        RETURN_COPY_CONFLUENCE
    }

    // all variables must be memento'd!
    private val havocdShaId = AtomicInteger(0) // memento'd
    private val freshNumber = AtomicInteger(0) // memento'd

    private val freshIds = mutableMapOf<Id, Int>() // memento'd

    private val origStorageCounter = AtomicInteger(0) // memento'd
    private val origTransientStorageCounter = AtomicInteger(0) // memento'd

    fun getNBId() : NBId {
        return StartBlock.copy(origStartPc = getFreshId(Id.PC_FOR_NBID))
    }

    fun getFreshHavocdShaVarName(): String {
        return "havocdSha${havocdShaId.getAndIncrement()}"
    }

    fun getFreshNumber() : Int {
        return freshNumber.incrementAndGet()
    }

    fun getAndIncStorageCounter(): Int {
        return origStorageCounter.incrementAndGet()
    }

    fun getAndIncTransientStorageCounter(): Int {
        return origTransientStorageCounter.incrementAndGet()
    }

    fun getStorageCounter(): Int {
        return origStorageCounter.get()
    }

    // Goal: Move all Allocators to ID-based allocations, to get more consequent numbering without holes
    fun getFreshId(id: Id): Int{
        synchronized(freshIds) {
            val curr = freshIds[id] ?: 1
            freshIds[id] = curr + 1 // update
            return curr
        }
    }

    // Memento should only be used in the main thread...
    data class Memento(
        val havocdShaId: AtomicInteger,
        val freshNumber: AtomicInteger,
        val freshIds: Map<Id, Int>,
        val origStorageCounter: AtomicInteger,
        val origTransientStorageCounter: AtomicInteger
    ) : Serializable

    override fun saveState(): Memento =
            @Suppress("ImportStdCollections")
            Memento(
                        havocdShaId, freshNumber, synchronized(freshIds) { freshIds.toMap() }, origStorageCounter, origTransientStorageCounter
            )

    /**
     * src - the atomic integer we want to update
     * other - the value we want to restore to
     */
    private fun restoreAtomic(src: AtomicInteger, other: Int) {
        var orig = src.get()
        var newVal = max(orig, other)
        // as long as src != orig (the expected value) we need to retry
        while(!src.compareAndSet(orig, newVal)) {
            // try again...
            orig = src.get()
            newVal = max(orig, other)
        }
    }

    override fun restore(m: Memento) {
        restoreAtomic(havocdShaId, m.havocdShaId.get())
        restoreAtomic(freshNumber, m.freshNumber.get())
        synchronized(freshIds) {
            for ((k, v) in m.freshIds) {
                freshIds.merge(k, v, ::max)
            }
        }
        restoreAtomic(origStorageCounter, m.origStorageCounter.get())
        restoreAtomic(origTransientStorageCounter, m.origTransientStorageCounter.get())
    }

}
