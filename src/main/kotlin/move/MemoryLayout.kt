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

package move

import analysis.*
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import move.analysis.ReferenceAnalysis.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*

/**
    Represents a collection of TAC variables that together represent some Move entity in Core TAC.  For example, a Move
    reference is represented by two TAC variables, and a Move struct might be represented by multiple TAC variables (
    one per field) or a single TAC map variable (if the struct is embedded in a vector, for example).
 */
sealed class MemoryLayout {
    abstract val componentVars: List<TACSymbol.Var>

    interface VarInitializer {
        /** Ensures that the given variable will be havoc-initialized once at the start of the Core TAC program. */
        fun TACSymbol.Var.ensureHavocInit(): TACSymbol.Var
    }

    data class Reference private constructor(
        /** An integer identifying the layout being referenced */
        val layoutId: TACSymbol.Var,
        /** The offset within the layout being referenced (if the layout is [Composed]) */
        val offset: TACSymbol.Var
    ) : MemoryLayout() {
        override val componentVars get() = listOf(layoutId, offset)

        companion object {
            operator fun invoke(namePrefix: String): Reference {
                return Reference(
                    layoutId = TACSymbol.Var("$namePrefix!loc", Tag.Int),
                    offset = TACSymbol.Var("$namePrefix!offset", Tag.Int)
                )
            }
            fun fromVar(origVar: TACSymbol.Var): Reference {
                require(origVar.tag is MoveTag.Ref)
                return Reference(origVar.namePrefix)
            }
        }
    }

    sealed class Value : MemoryLayout() {

        abstract fun havoc(meta: MetaMap): SimpleCmdsWithDecls

        /** A simple value represented by a single TAC variable */
        data class Simple private constructor(
            val value: TACSymbol.Var,
            val type: MoveType.Simple?
        ) : Value() {
            override val componentVars get() = listOf(value)
            override fun havoc(meta: MetaMap): SimpleCmdsWithDecls {
                return type?.assignHavoc(value, meta) ?: error("Cannot havoc untyped simple value: $this")
            }
            companion object {
                operator fun invoke(namePrefix: String, type: MoveType.Simple): Simple {
                    return Simple(
                        TACSymbol.Var("$namePrefix!value", type.toCoreTag()),
                        type
                    )
                }
                fun fromVar(origVar: TACSymbol.Var): Simple {
                    return when (val tag = origVar.tag) {
                        is MoveTag -> {
                            val type = tag.toMoveType()
                            check(type is MoveType.Simple) { "$origVar is not a simple value" }
                            Simple(TACSymbol.Var("${origVar.namePrefix}!value", type.toCoreTag()), type)
                        }
                        else -> Simple(origVar, null)
                    }
                }
            }
        }

        /** A decomposed struct; each field gets a separate [MemoryLayout] */
        data class Struct(
            val fields: List<Value>
        ) : Value() {
            override val componentVars get() = fields.flatMap { it.componentVars }
            override fun havoc(meta: MetaMap) = mergeMany(fields.map { it.havoc(meta) })
        }

        /** A decomposed enum; the variant identifier gets its own variable */
        data class Enum(
            /** Identifies the variant that is stored in [content] */
            val variant: TACSymbol.Var,
            /** The content of the enum; this is a "union" of all possible variants. */
            val content: Composed,
            val type: MoveType.Enum
        ) : Value() {
            override val componentVars get() = listOf(variant, content.map)
            override fun havoc(meta: MetaMap) = mergeMany(
                assign(variant, meta) {
                    safeMathNarrowAssuming(unconstrained(Tag.Int), Tag.Bit256, type.variants.lastIndex.toBigInteger())
                },
                content.havoc(meta)
            )
        }

        sealed class Array : Value() {
            abstract val content: Composed
        }

        /** A decomposed vector; the length and digest get their own variables */
        data class Vector(
            val length: TACSymbol.Var,
            val digest: TACSymbol.Var,
            override val content: Composed
        ) : Array() {
            override val componentVars get() = listOf(length, digest, content.map)
            override fun havoc(meta: MetaMap) = mergeMany(
                MoveType.U64.assignHavoc(length, meta),
                assignHavoc(digest, meta),
                content.havoc(meta)
            )
        }

        data class GhostArray(
            override val content: Composed
        ) : Array() {
            override val componentVars get() = listOf(content.map)
            override fun havoc(meta: MetaMap) = content.havoc(meta)
        }

        /**
            The Composed layout consists of a single map of type (Int)->Int.  The internal structure of this map depends
            on the type being represented, and is managed by [MemoryLocation].
         */
        data class Composed private constructor(
            val map: TACSymbol.Var
        ) : Value() {
            companion object {
                val MapTag = Tag.GhostMap(listOf(Tag.Int), Tag.Int)

                context(VarInitializer)
                operator fun invoke(namePrefix: String): Composed {
                    // We havoc-initialize all maps, up front, to make DSA happy
                    return Composed(TACSymbol.Var(namePrefix, MapTag).ensureHavocInit())
                }
            }
            override val componentVars get() = listOf(map)
            override fun havoc(meta: MetaMap) = assignHavoc(map, meta)
        }

        companion object {
            /**
                Constructs a [MemoryLayout.Value] for the given [type], prefixing all variable names with [namePrefix].
             */
            context(VarInitializer)
            operator fun invoke(type: MoveType.Value, namePrefix: String): Value {
                return when (type) {
                    is MoveType.Simple -> Value.Simple(namePrefix, type)
                    is MoveType.Struct -> Value.Struct(
                        type.fields.orEmpty().map {
                            Value(it.type, "$namePrefix.${it.name}")
                        }
                    )
                    is MoveType.Enum -> Value.Enum(
                        variant = TACSymbol.Var("$namePrefix!variant", Tag.Bit256),
                        content = Composed("$namePrefix!content"),
                        type = type
                    )
                    is MoveType.Vector -> Value.Vector(
                        length = TACSymbol.Var("$namePrefix!length", MoveType.U64.toCoreTag()),
                        digest = TACSymbol.Var("$namePrefix!digest", Tag.Bit256),
                        content = Composed("$namePrefix!content")
                    )
                    is MoveType.GhostArray -> Value.GhostArray(
                        content = Composed("$namePrefix!content")
                    )
                }
            }

            /** Constructs a top-level [MemoryLayout.Value] from a variable from a [MoveTACProgram]. */
            context(VarInitializer)
            fun fromVar(origVar: TACSymbol.Var): Value {
                val tag = origVar.tag
                return when (tag) {
                    is MoveTag -> when (tag) {
                        is MoveTag.Ref -> error("MoveTag.Ref cannot be converted to MemoryLayout.Value")
                        is MoveTag.Nondet -> Value.Simple.fromVar(origVar)
                        is MoveTag.Vec, is MoveTag.Struct, is MoveTag.Enum, is MoveTag.GhostArray ->
                            Value(tag.toMoveValueType(), origVar.namePrefix)
                    }
                    else -> Value.Simple.fromVar(origVar)
                }
            }
        }
    }

    companion object {
        /** Constructs a [MemoryLayout] for the given [type], prefixing all variable names with [namePrefix]. */
        context(VarInitializer)
        operator fun invoke(type: MoveType, namePrefix: String): MemoryLayout {
            return when (type) {
                is MoveType.Reference -> Reference(namePrefix)
                is MoveType.Value -> Value(type, namePrefix)
            }
        }

        /** Constructs a top-level [MemoryLayout.Value] from a variable from a [MoveTACProgram]. */
        context(VarInitializer)
        fun fromVar(origVar: TACSymbol.Var): MemoryLayout {
            return when (origVar.tag) {
                is MoveTag.Ref -> MemoryLayout.Reference.fromVar(origVar)
                else -> MemoryLayout.Value.fromVar(origVar)
            }
        }
    }

    private fun checkAssign(src: MemoryLayout) {
        check(this::class == src::class) { "Cannot copy between different layout types: $this and $src" }
    }

    /** Generates Core TAC to assign the contents of the [src] layout to this layout. */
    fun assign(src: MemoryLayout, meta: MetaMap): SimpleCmdsWithDecls {
        checkAssign(src)
        return mergeMany(
            componentVars.zip(src.componentVars).map { (l, r) ->
                assign(l, meta) { r.asSym() }
            }
        )
    }

    /** If [i] is true, assigns the contents of [t] to this layout, else assigns [e] to this layout. */
    fun assignIte(i: TACExpr, t: MemoryLayout, e: MemoryLayout, meta: MetaMap): SimpleCmdsWithDecls {
        checkAssign(t)
        checkAssign(e)
        return mergeMany(
            zip(componentVars, t.componentVars, e.componentVars).map { (l, t, e) ->
                assign(l, meta) { ite(i, t.asSym(), e.asSym()) }
            }
        )
    }
}

/** For a given [RefTarget], constructs the layout of the [MoveTACProgram] variable being referenced. */
context(MemoryLayout.VarInitializer)
fun RefTarget.baseLayout(): MemoryLayout.Value {
    return when (val tag = locVar.tag) {
        is MoveTag -> MemoryLayout.Value(tag.toMoveValueType(), locVar.namePrefix)
        else -> MemoryLayout.Value.Simple.fromVar(locVar)
    }
}

/** Finds and constructs the layout that is referenced by the given [RefTarget]. */
context(MemoryLayout.VarInitializer)
fun RefTarget.layout(): MemoryLayout.Value {
    // Starting from the base layout, traverse the path to find the final layout being referenced.  Note that the paths
    // are kept in reverse order by [ReferenceAnalysis], so we need to reverse them here.
    return path.reversed().fold(baseLayout()) { layout, comp ->
        when (layout) {
            is MemoryLayout.Value.Simple -> {
                error("Cannot access component $comp of simple value")
            }
            is MemoryLayout.Value.Struct -> {
                check(comp is PathComponent.Field) { "Cannot access component $comp of struct" }
                layout.fields[comp.fieldIndex]
            }
            is MemoryLayout.Value.Enum -> {
                check(comp is PathComponent.EnumField) { "Cannot access component $comp of enum" }
                layout.content
            }
            is MemoryLayout.Value.Vector -> {
                check(comp is PathComponent.VecElem) { "Cannot access component $comp of vector" }
                layout.content
            }
            is MemoryLayout.Value.GhostArray -> {
                check(comp is PathComponent.GhostArrayElem) { "Cannot access component $comp of ghost array" }
                layout.content
            }
            is MemoryLayout.Value.Composed -> layout
        }
    }
}
