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
import datastructures.*
import datastructures.stdcollections.*
import evm.MASK_SIZE
import java.math.BigInteger
import move.analysis.ReferenceAnalysis.*
import tac.*
import tac.generation.*
import vc.data.*

/**
    A location in Move memory, in the Core TAC representation.
 */
data class MemoryLocation(
    /** The layout of this memory location. */
    val layout: MemoryLayout.Value,
    /** The original variable (in the [MoveTACProgram]) that this location was derived from. */
    val origLoc: TACSymbol.Var,
    /** The base layout of this memory location. */
    val baseLayout: MemoryLayout.Value,
    /** The path from the base layout to this location. */
    val path: PersistentStack<PathComponent>,
    /** This location's offset within [layout] (if [layout] is [MemoryLayout.Value.Composed]) */
    val offset: TACExpr
) {
    companion object {
        // For composed layouts: the offsets of a vector's length, digest, and content within the map, relative to
        // the start offset of the vector.
        private val composedVecLengthOffset = BigInteger.ZERO
        private val composedVecDigestOffset = BigInteger.ONE
        private val composedVecContentOffset = BigInteger.TWO

        // For composed layouts: the offsets of an enum's variant index and content within the map, relative to the
        // start offset of the enum.
        private val composedEnumVariantOffset = BigInteger.ZERO
        private val composedEnumContentOffset = BigInteger.ONE

        /** Computes the size of a Move type when represented in a composed layout. */
        private fun MoveType.Value.composedSize(): BigInteger {
            return when (this) {
                is MoveType.Simple -> BigInteger.ONE
                is MoveType.Struct -> compositeSize()
                is MoveType.Enum -> composedEnumContentOffset + contentSize()
                is MoveType.Vector -> composedVecContentOffset + contentSize()
                is MoveType.GhostArray -> contentSize()
            }
        }

        /**
            Computes the size of the contents of a vector/ghost array/enum when represented in a composed layout. We
            reserve space for the largest possible content of each type, which allows the array types to change length,
            and enum types to change variants.
         */
        private fun MoveType.Container.contentSize(): BigInteger {
            return when (this) {
                is MoveType.Vector -> BigInteger.TWO.pow(64) * elemType.composedSize()
                is MoveType.GhostArray -> BigInteger.TWO.pow(256) * elemType.composedSize()
                is MoveType.Enum -> variants.maxOf { it.compositeSize() }
            }
        }

        /** Computes the offset of a struct/enum field when represented in a composed layout. */
        private fun MoveType.Composite.fieldOffset(fieldNum: Int): BigInteger {
            var offset = BigInteger.ZERO
            repeat(fieldNum) { i ->
                offset += fields!![i].type.composedSize()
            }
            return offset
        }

        /** Computes the size of a struct (or the content of an enum) when represented in a composed layout. */
        private fun MoveType.Composite.compositeSize() = fieldOffset(fields!!.size)

        /**
            Composed layouts are represented as maps containing only Int values.  This function converts from these
            Int values to the appropriate simple Move type.
         */
        fun MoveType.Simple.fromInt(value: TACExpr): TACExpr {
            return when (this) {
                is MoveType.Bits -> TXF { safeMathNarrowAssuming(value, toCoreTag(), MASK_SIZE(size)) }
                is MoveType.Nondet -> TXF { safeMathNarrowAssuming(value, toCoreTag()) }
                is MoveType.Function -> TXF { safeMathNarrowAssuming(value, toCoreTag()) }
                is MoveType.Bool -> TXF { value neq 0.asTACExpr(Tag.Int) }
                is MoveType.MathInt -> value
            }
        }

        /**
            Converts a simple Move type to its Int representation for composed layouts.
         */
        fun MoveType.Simple.toInt(value: TACExpr): TACExpr {
            return when (this) {
                is MoveType.Bits, is MoveType.Nondet, is MoveType.Function, is MoveType.MathInt -> {
                    value
                }
                is MoveType.Bool -> TXF {
                    ite(value, 1.asTACExpr(Tag.Int), 0.asTACExpr(Tag.Int))
                }
            }
        }
    }

    /** Gets the simple value in this [MemoryLocation]. */
    fun simpleValue(type: MoveType.Simple): TACExpr {
        return when (layout) {
            is MemoryLayout.Value.Simple -> layout.value.asSym()
            is MemoryLayout.Value.Composed -> TXF { type.fromInt(select(layout.map.asSym(), offset)) }
            else -> error("Cannot get simple value from non-simple layout: $layout")
        }
    }

    /** Sets the simple value in this [MemoryLocation]. */
    fun setSimpleValue(
        value: TACExpr,
        type: MoveType.Simple,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        return when (layout) {
            is MemoryLayout.Value.Simple -> assign(layout.value, meta) { value }
            is MemoryLayout.Value.Composed -> assign(layout.map, meta) {
                Store(layout.map.asSym(), listOf(offset), type.toInt(value))
            }
            else -> error("Cannot set simple value to non-simple layout: $layout")
        }
    }

    /** Gets the [MemoryLocation] of a field in this (struct-typed) [MemoryLocation]. */
    fun fieldLoc(type: MoveType.Composite, fieldIndex: Int): MemoryLocation {
        return when (layout) {
            is MemoryLayout.Value.Struct -> this.copy(
                layout = layout.fields[fieldIndex],
                path = path.push(PathComponent.Field(fieldIndex))
            )
            is MemoryLayout.Value.Enum -> this.copy(
                layout = layout.content,
                path = path.push(PathComponent.EnumField((type as MoveType.Enum.Variant).index, fieldIndex)),
                offset = TXF { type.fieldOffset(fieldIndex).asTACExpr }
            )
            is MemoryLayout.Value.Composed -> when (type) {
                is MoveType.Struct -> this.copy(
                    path = path.push(PathComponent.Field(fieldIndex)),
                    offset = TXF { offset intAdd type.fieldOffset(fieldIndex) }
                )
                is MoveType.Enum.Variant -> this.copy(
                    path = path.push(PathComponent.EnumField(type.index, fieldIndex)),
                    offset = TXF { offset intAdd composedEnumContentOffset intAdd type.fieldOffset(fieldIndex) }
                )
            }
            else -> error("Cannot get field of non-struct layout: $layout")
        }
    }

    /** Gets the variant index of this enum-typed [MemoryLocation]. */
    fun enumVariant(type: MoveType.Enum): TACExpr {
        return when (layout) {
            is MemoryLayout.Value.Enum -> layout.variant.asSym()
            is MemoryLayout.Value.Composed -> TXF {
                safeMathNarrowAssuming(
                    select(layout.map.asSym(), offset intAdd composedEnumVariantOffset),
                    Tag.Bit256,
                    type.variants.lastIndex.toBigInteger()
                )
            }
            else -> error("Cannot get enum variant of non-enum layout: $layout")
        }
    }

    /** Sets the variant index of this enum-typed [MemoryLocation]. */
    fun setEnumVariant(variant: TACExpr, meta: MetaMap): SimpleCmdsWithDecls {
        return when (layout) {
            is MemoryLayout.Value.Enum -> assign(layout.variant, meta) { variant }
            is MemoryLayout.Value.Composed -> assign(layout.map, meta) {
                Store(
                    layout.map.asSym(),
                    listOf(offset intAdd composedEnumVariantOffset.asTACExpr),
                    variant
                )
            }
            else -> error("Cannot set enum variant of non-enum layout: $layout")
        }
    }

    /** Gets the [MemoryLocation] of the content of this enum-typed [MemoryLocation]. */
    private fun enumContentLoc(): MemoryLocation {
        return when (layout) {
            is MemoryLayout.Value.Enum -> this.copy(
                layout = layout.content,
                offset = 0.asTACExpr
            )
            is MemoryLayout.Value.Composed -> this.copy(
                offset = TXF { offset intAdd composedEnumContentOffset }
            )
            else -> error("Cannot get enum content of non-enum layout: $layout")
        }
    }

    /** Gets the length of the vector in this [MemoryLocation]. */
    fun vecLen(): TACExpr {
        return when (layout) {
            is MemoryLayout.Value.Vector -> layout.length.asSym()
            is MemoryLayout.Value.Composed -> TXF {
                MoveType.U64.fromInt(select(layout.map.asSym(), offset intAdd composedVecLengthOffset))
            }
            else -> error("Cannot get vector length of non-vector layout: $layout")
        }.let {
            TXF { safeMathNarrowAssuming(it, Tag.Bit256, MASK_SIZE(64)) }
        }
    }

    /** Sets the length of the vector in this [MemoryLocation]. */
    fun setVecLen(len: TACExpr, meta: MetaMap): SimpleCmdsWithDecls {
        return when (layout) {
            is MemoryLayout.Value.Vector -> assign(layout.length, meta) { len }
            is MemoryLayout.Value.Composed -> assign(layout.map, meta) {
                Store(
                    layout.map.asSym(),
                    listOf(offset intAdd composedVecLengthOffset.asTACExpr),
                    len
                )
            }
            else -> error("Cannot set vector length of non-vector layout: $layout")
        }
    }

    /** Gets the digest of the vector in this [MemoryLocation]. */
    fun vecDigest(): TACExpr {
        return when (layout) {
            is MemoryLayout.Value.Vector -> layout.digest.asSym()
            is MemoryLayout.Value.Composed -> TXF {
                safeMathNarrowAssuming(
                    select(layout.map.asSym(), offset intAdd composedVecDigestOffset),
                    Tag.Bit256
                )
            }
            else -> error("Cannot get vector digest of non-vector layout: $layout")
        }.let {
            TXF { safeMathNarrowAssuming(it, Tag.Bit256) }
        }
    }

    /** Sets the digest of the vector in this [MemoryLocation]. */
    fun setVecDigest(digest: TACExpr, meta: MetaMap): SimpleCmdsWithDecls {
        return when (layout) {
            is MemoryLayout.Value.Vector -> assign(layout.digest, meta) { digest }
            is MemoryLayout.Value.Composed -> assign(layout.map, meta) {
                Store(
                    layout.map.asSym(),
                    listOf(offset intAdd composedVecDigestOffset.asTACExpr),
                    digest
                )
            }
            else -> error("Cannot set vector digest of non-vector layout: $layout")
        }
    }

    /** Gets the [MemoryLocation] of the content of this vector-typed [MemoryLocation]. */
    private fun vecContentLoc(): MemoryLocation {
        return when (layout) {
            is MemoryLayout.Value.Vector -> this.copy(
                layout = layout.content,
                path = path.push(PathComponent.VecElem),
                offset = 0.asTACExpr
            )
            is MemoryLayout.Value.Composed -> this.copy(
                path = path.push(PathComponent.VecElem),
                offset = TXF { offset intAdd composedVecContentOffset }
            )
            else -> error("Cannot get vector content of non-vector layout: $layout")
        }
    }

    /** Gets the [MemoryLocation] of the content of this ghost-array-typed [MemoryLocation]. */
    private fun ghostArrayContentLoc(): MemoryLocation {
        return when (layout) {
            is MemoryLayout.Value.GhostArray -> this.copy(
                layout = layout.content,
                offset = 0.asTACExpr
            )
            is MemoryLayout.Value.Composed -> this
            else -> error("Cannot get ghost array content of non-ghost array layout: $layout")
        }
    }

    /** Gets the [MemoryLocation] of an element in this (vector-typed) [MemoryLocation]. */
    fun elementLoc(type: MoveType.Vector, elemIndex: TACExpr): MemoryLocation {
        return when (layout) {
            is MemoryLayout.Value.Vector -> this.copy(
                layout = layout.content,
                path = path.push(PathComponent.VecElem),
                offset = TXF { elemIndex intMul type.elemType.composedSize() }
            )
            is MemoryLayout.Value.Composed -> this.copy(
                path = path.push(PathComponent.VecElem),
                offset = TXF { offset intAdd composedVecContentOffset intAdd (elemIndex intMul type.elemType.composedSize()) }
            )
            else -> error("Cannot get element of non-array layout: $layout")
        }
    }

    /** Gets the [MemoryLocation] of an element in this (ghost-array-typed) [MemoryLocation]. */
    fun elementLoc(type: MoveType.GhostArray, elemIndex: TACExpr): MemoryLocation {
        return when (layout) {
            is MemoryLayout.Value.GhostArray -> this.copy(
                layout = layout.content,
                path = path.push(PathComponent.GhostArrayElem),
                offset = TXF { elemIndex intMul type.elemType.composedSize() }
            )
            is MemoryLayout.Value.Composed -> this.copy(
                path = path.push(PathComponent.GhostArrayElem),
                offset = TXF { offset intAdd (elemIndex intMul type.elemType.composedSize()) }
            )
            else -> error("Cannot get element of non-array layout: $layout")
        }
    }

    /** Assigns the contents of one [MemoryLocation] to another, given that both locations use composed layouts. */
    private fun assignComposed(
        src: MemoryLocation,
        size: BigInteger,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        val srcLayout = src.layout as MemoryLayout.Value.Composed
        val dstLayout = this.layout as MemoryLayout.Value.Composed
        return assign(dstLayout.map, meta) {
            mapDefinition(MemoryLayout.Value.Composed.MapTag) { (i) ->
                ite(
                    (i lt offset) or (i ge (offset intAdd size.asTACExpr)),
                    select(dstLayout.map.asSym(), i),
                    select(srcLayout.map.asSym(), i intSub offset intAdd src.offset)
                )
            }
        }
    }

    /** Assigns the contents of one [MemoryLocation] to another. */
    fun assign(src: MemoryLocation, type: MoveType.Value, meta: MetaMap): SimpleCmdsWithDecls {
        if (this.layout is MemoryLayout.Value.Composed && src.layout is MemoryLayout.Value.Composed) {
            return assignComposed(src, type.composedSize(), meta)
        }
        return when (type) {
            is MoveType.Simple -> {
                this.setSimpleValue(src.simpleValue(type), type, meta)
            }
            is MoveType.Struct -> {
                mergeMany(
                    type.fields.orEmpty().mapIndexed { fieldIndex, field ->
                        this.fieldLoc(type, fieldIndex).assign(
                            src.fieldLoc(type, fieldIndex),
                            field.type,
                            meta
                        )
                    }
                )
            }
            is MoveType.Enum -> {
                mergeMany(
                    this.setEnumVariant(src.enumVariant(type), meta),
                    this.enumContentLoc().assignComposed(src.enumContentLoc(), type.contentSize(), meta)
                )
            }
            is MoveType.Vector -> {
                mergeMany(
                    this.setVecLen(src.vecLen(), meta),
                    this.setVecDigest(src.vecDigest(), meta),
                    this.vecContentLoc().assignComposed(src.vecContentLoc(), type.contentSize(), meta)
                )
            }
            is MoveType.GhostArray -> {
                this.ghostArrayContentLoc().assignComposed(src.ghostArrayContentLoc(), type.contentSize(), meta)
            }
        }
    }

    /** Invalidates (havocs) the vector digests of any vectors that contain this [MemoryLocation]. */
    context(MemoryLayout.VarInitializer)
    fun invalidateVectorDigests(meta: MetaMap): SimpleCmdsWithDecls {
        // Find the base type (and immediately return if it cannot contain a writable vector)
        var currentType = when (val tag = origLoc.tag) {
            is MoveTag -> when (tag) {
                is MoveTag.Vec, is MoveTag.GhostArray, is MoveTag.Struct -> tag.toMoveValueType()
                is MoveTag.Enum, is MoveTag.Nondet -> null
                is MoveTag.Ref -> error("Cannot have a location containing a reference")
            }
            else -> null
        } ?: return SimpleCmdsWithDecls()

        val cmds = mutableListOf<SimpleCmdsWithDecls>()

        var currentLoc = baseLayout.baseLocation(origLoc)

        // Work our way forward from the base layout, invalidating any vectors we find
        for (comp in path.reversed()) {
            if (currentType is MoveType.Vector) {
                cmds += currentLoc.setVecDigest(TACExpr.Unconstrained(Tag.Bit256), meta)
            }
            when (comp) {
                is PathComponent.Field -> {
                    val structType = currentType as MoveType.Struct
                    currentLoc = currentLoc.fieldLoc(structType, comp.fieldIndex)
                    currentType = structType.fields!![comp.fieldIndex].type
                }
                is PathComponent.EnumField -> {
                    val enumType = currentType as MoveType.Enum
                    val variant = enumType.variants[comp.variantIndex]
                    currentLoc = currentLoc.fieldLoc(variant, comp.fieldIndex)
                    currentType = variant.fields!![comp.fieldIndex].type
                }
                is PathComponent.VecElem -> {
                    val vecType = currentType as MoveType.Vector
                    val elemSize = vecType.elemType.composedSize()
                    // Compute the element index based on this location's offset
                    val elemIndex = when (currentLoc.layout) {
                        is MemoryLayout.Value.Vector -> TXF {
                            offset intDiv elemSize
                        }
                        is MemoryLayout.Value.Composed -> TXF {
                            (offset intSub composedVecContentOffset intSub currentLoc.offset) intDiv elemSize
                        }
                        else -> error("Cannot have vector element access on non-vector layout: ${currentLoc.layout}")
                    }
                    currentLoc = currentLoc.elementLoc(vecType, elemIndex)
                    currentType = currentType.elemType
                }
                is PathComponent.GhostArrayElem -> {
                    val arrayType = currentType as MoveType.GhostArray
                    val elemIndex = TXF { offset intDiv arrayType.elemType.composedSize() }
                    currentLoc = currentLoc.elementLoc(arrayType, elemIndex)
                    currentType = arrayType.elemType
                }
            }
            // If we computed a new offset, stash it in a variable so we won't recompute it
            if (currentLoc.offset !is TACExpr.Sym) {
                val v = TACKeyword.TMP(Tag.Int, "offset")
                cmds += assign(v, meta) { currentLoc.offset }
                currentLoc = currentLoc.copy(offset = v.asSym())
            }
        }

        return mergeMany(cmds)
    }
}

private fun MemoryLayout.Value.baseLocation(origLoc: TACSymbol.Var) =
    MemoryLocation(this, origLoc, this, persistentStackOf(), 0.asTACExpr)

/** Gets the [MemoryLocation] for a variable from a [MoveTACProgram]. */
context(MemoryLayout.VarInitializer)
fun TACSymbol.Var.location() = MemoryLayout.Value.fromVar(this).baseLocation(this)

/** Gets the [MemoryLocation] for the [MoveTACProgram] variable that is referenced by the given [RefTarget]. */
context(MemoryLayout.VarInitializer)
fun RefTarget.baseLocation() = baseLayout().baseLocation(locVar)

/** Gets the innermost [MemoryLocation] referenced by the given [RefTarget]. */
context(MemoryLayout.VarInitializer)
fun RefTarget.targetLocation(ref: MemoryLayout.Reference) =
    MemoryLocation(layout(), locVar, baseLayout(), path, ref.offset.asSym())
