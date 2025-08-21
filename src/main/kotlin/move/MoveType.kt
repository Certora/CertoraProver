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

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.SimpleCmdsWithDecls
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.MASK_SIZE
import move.MoveModule.SignatureToken
import tac.*
import tac.generation.*
import utils.*
import vc.data.*

/**
    Simplified view of Move types, with all generics resolved.
 */
@Treapable
@KSerializable
sealed class MoveType : HasKSerializable {
    abstract fun toTag(): Tag

    /** Gets a name for this type that can be used as part of a TACSymbol.Var name. */
    abstract fun symNameExt(): String

    @KSerializable
    sealed class Value : MoveType()

    @KSerializable
    sealed class Primitive : Value() {
        override fun toString() = this::class.simpleName ?: super.toString()
    }

    @KSerializable
    object Bool : Primitive() {
        override fun toTag() = Tag.Bool
        override fun symNameExt() = "bool"
        override fun hashCode() = hashObject(this)
    }

    @KSerializable
    sealed class Bits(val size: Int) : Primitive() {
        override fun toTag() = Tag.Bit256
        override fun symNameExt() = "u$size"
    }

    @KSerializable object U8 : Bits(8) { override fun hashCode() = hashObject(this) }
    @KSerializable object U16 : Bits(16) { override fun hashCode() = hashObject(this) }
    @KSerializable object U32 : Bits(32) { override fun hashCode() = hashObject(this) }
    @KSerializable object U64 : Bits(64) { override fun hashCode() = hashObject(this) }
    @KSerializable object U128 : Bits(128) { override fun hashCode() = hashObject(this) }
    @KSerializable object U256 : Bits(256) { override fun hashCode() = hashObject(this) }
    @KSerializable object Address : Bits(256) { override fun hashCode() = hashObject(this) }
    @KSerializable object Signer : Bits(256) { override fun hashCode() = hashObject(this) }


    @KSerializable
    data class Vector(val elemType: MoveType.Value) : Value() {
        override fun toString() = "vector<$elemType>"
        override fun toTag() = MoveTag.Vec(elemType)
        override fun symNameExt() = "vector!${elemType.symNameExt()}"
    }

    @KSerializable
    data class Struct(
        val name: MoveStructName,
        val typeArguments: List<MoveType.Value>,
        val fields: List<Field>?
    ) : Value() {
        @Treapable
        @KSerializable
        data class Field(val name: String, val type: MoveType.Value)

        override fun toString() = when {
            typeArguments.isEmpty() -> "$name"
            else -> "$name<${typeArguments.joinToString(", ")}>"
        }

        override fun toTag() = MoveTag.Struct(this)

        override fun symNameExt(): String {
            val typeArgumentString = typeArguments.fold("${typeArguments.size}") { acc, type ->
                "$acc!${type.symNameExt()}"
            }
            return "${name.toVarName()}!$typeArgumentString"
        }
    }

    @KSerializable
    data class Reference(val refType: MoveType.Value) : MoveType() {
        override fun toString() = "&${refType}"
        override fun toTag() = MoveTag.Ref(refType)
        override fun symNameExt() = "ref!${refType.symNameExt()}"
    }

    @KSerializable
    data class GhostArray(val elemType: MoveType.Value) : Value() {
        override fun toString() = "array<${elemType}>"
        override fun toTag() = MoveTag.GhostArray(elemType)
        override fun symNameExt() = "array!${elemType.symNameExt()}"
    }

    @KSerializable
    object MathInt : Primitive() {
        override fun toString() = "mathint"
        override fun toTag() = Tag.Int
        override fun symNameExt() = "mathint"
        override fun hashCode() = hashObject(this)
    }
}

context(MoveScene)
fun SignatureToken.toMoveType(typeArguments: List<MoveType.Value>) =
    toMoveType { typeArguments[it.index.index] }

context(MoveScene)
fun SignatureToken.toMoveValueType(typeArguments: List<MoveType.Value>) =
    toMoveValueType { typeArguments[it.index.index] }

context(MoveScene)
private fun SignatureToken.toMoveType(
    typeArg: (SignatureToken.TypeParameter) -> MoveType.Value
): MoveType = when (this) {
    is SignatureToken.Reference -> MoveType.Reference(type.toMoveValueType(typeArg))
    is SignatureToken.Value -> toMoveValueType(typeArg)
}

context(MoveScene)
private fun SignatureToken.toMoveValueType(
    typeArg: (SignatureToken.TypeParameter) -> MoveType.Value
): MoveType.Value = when (this) {
    SignatureToken.Bool -> MoveType.Bool
    SignatureToken.U8 -> MoveType.U8
    SignatureToken.U16 -> MoveType.U16
    SignatureToken.U32 -> MoveType.U32
    SignatureToken.U64 -> MoveType.U64
    SignatureToken.U128 -> MoveType.U128
    SignatureToken.U256 -> MoveType.U256
    SignatureToken.Address -> MoveType.Address
    SignatureToken.Signer -> MoveType.Signer
    is SignatureToken.Vector -> MoveType.Vector(type.toMoveValueType(typeArg))
    is SignatureToken.Datatype -> handle.toMoveStructOrShadow()
    is SignatureToken.DatatypeInstantiation -> handle.toMoveStructOrShadow(typeArguments.map { it.toMoveValueType(typeArg) })
    is SignatureToken.TypeParameter -> typeArg(this)
    is SignatureToken.Reference -> error("References are not valid value types: $this")
}

context(MoveScene)
fun MoveModule.DatatypeHandle.toMoveStructRaw(
    typeArgs: List<MoveType.Value> = listOf(),
): MoveType.Struct {
    check(typeParameters.size == typeArgs.size) {
        "Type parameters size mismatch for ${qualifiedName}: expected ${typeParameters.size}, got ${typeArgs.size}"
    }
    val def = definition(this)
    return MoveType.Struct(
        MoveStructName(definingModule, simpleName),
        typeArgs,
        def.fields?.map {
            MoveType.Struct.Field(
                it.name,
                it.signature.toMoveValueType { typeArgs[it.index.index] }
            )
        }
    )
}

class TypeShadowedException(val type: MoveType.Struct) : Exception()

context(MoveScene)
fun MoveModule.DatatypeHandle.toMoveStruct(
    typeArgs: List<MoveType.Value> = listOf(),
): MoveType.Struct {
    val struct = toMoveStructRaw(typeArgs)
    if (maybeShadowType(struct) != null) {
        throw TypeShadowedException(struct)
    }
    return struct
}

context(MoveScene)
fun MoveModule.DatatypeHandle.toMoveStructOrShadow(
    typeArgs: List<MoveType.Value> = listOf(),
): MoveType.Value {
    val struct = toMoveStructRaw(typeArgs)
    return maybeShadowType(struct) ?: struct
}

/**
    Assigns a Tag.Int-valued expression to a location of this MoveType.  The expression is assumed to have a value that
    is in bounds for the type.  Typically this is because the Int in question was produced from this MoveType
    originally, as in values that are stored in fields of a struct, etc.
 */
fun MoveType.Primitive.assignFromIntInBounds(
    dest: TACSymbol.Var,
    meta: MetaMap = MetaMap(),
    value: TACExprFact.() -> TACExpr
): SimpleCmdsWithDecls {
    return when (this) {
        is MoveType.Bits -> {
            val bitsTag = this.toTag()
            value.letVar(tag = Tag.Int, meta = meta) { intValue ->
                mergeMany(
                    assumeBounds(intValue.s, meta),
                    assign(dest, meta) { safeMathNarrow(intValue, bitsTag) }
                )
            }
        }
        is MoveType.Bool -> {
            assign(dest, meta) { value() neq 0.asTACExpr }
        }
        is MoveType.MathInt -> {
            assign(dest, meta) { value() }
        }
    }
}

fun MoveType.assumeBounds(value: TACSymbol.Var, meta: MetaMap = MetaMap()) = when (val type = this) {
    is MoveType.Bits -> {
        assume(meta) { (0.asTACExpr le value) and (value le MASK_SIZE(type.size).asTACExpr) }
    }
    else -> SimpleCmdsWithDecls()
}

fun MoveType.assignHavoc(dest: TACSymbol.Var, meta: MetaMap = MetaMap()) = when (val type = this) {
    is MoveType.Bits -> {
        mergeMany(
            tac.generation.assignHavoc(dest, meta),
            type.assumeBounds(dest, meta)
        )
    }
    is MoveType.Reference -> {
        // For reference types, we create a temporary location, havoc it, and then borrow it.
        // TODO CERT-9083: this isn't right in all situations; we will probably need more contol over this.
        val havocLoc = TACKeyword.TMP(type.refType.toTag(), "havocLoc")
        mergeMany(
            tac.generation.assignHavoc(havocLoc, meta),
            TACCmd.Move.BorrowLocCmd(
                ref = dest,
                loc = havocLoc,
                meta = meta
            ).withDecls()
        )
    }
    is MoveType.Bool, is MoveType.Vector, is MoveType.Struct, is MoveType.GhostArray, is MoveType.MathInt -> {
        tac.generation.assignHavoc(dest, meta)
    }
}

/**
    Gets the names of all structs used in this type.
 */
fun MoveType.consituentStructNames(): TreapSet<MoveStructName> = when (this) {
    is MoveType.Bits, is MoveType.Primitive, MoveType.Bool -> treapSetOf()
    is MoveType.Vector -> elemType.consituentStructNames()
    is MoveType.Struct -> fields?.map { it.type.consituentStructNames() }?.unionAll().orEmpty() + name
    is MoveType.Reference -> refType.consituentStructNames()
    is MoveType.GhostArray -> elemType.consituentStructNames()
}
