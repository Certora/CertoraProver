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
sealed class MoveType : AmbiSerializable {
    abstract fun toTag(): Tag

    /** Gets a name for this type that can be used as part of a TACSymbol.Var name. */
    abstract fun symNameExt(): String

    @KSerializable
    sealed class Value : MoveType() {
        abstract fun displayName(): String

        /**
            Whether this type is statically known.  Nondet types, or generic types with nondet arguments, are not
            static.
         */
        abstract val isStatic: Boolean
    }

    /** A [Value] that is represented as a single slot in a memory location */
    @KSerializable
    sealed class Simple : Value() {
        /** The tag to use for this type in Core TAC */
        abstract fun toCoreTag(): Tag
    }

    @KSerializable
    sealed class Primitive : Simple() {
        override fun toString() = this::class.simpleName ?: super.toString()
        override val isStatic get() = true
    }

    @KSerializable
    object Bool : Primitive() {
        override fun toTag() = Tag.Bool
        override fun toCoreTag() = Tag.Bool
        override fun displayName() = "bool"
        override fun symNameExt() = "bool"
        override fun hashCode() = hashObject(this)
        private fun readResolve(): Any = Bool
    }

    @KSerializable
    sealed class Bits(val size: Int) : Primitive() {
        override fun toTag() = Tag.Bit256
        override fun toCoreTag() = Tag.Bit256
        override fun displayName() = "u$size"
        override fun symNameExt() = "u$size"
    }

    @KSerializable object U8 : Bits(8) { override fun hashCode() = hashObject(this); private fun readResolve(): Any = U8 }
    @KSerializable object U16 : Bits(16) { override fun hashCode() = hashObject(this); private fun readResolve(): Any = U16 }
    @KSerializable object U32 : Bits(32) { override fun hashCode() = hashObject(this); private fun readResolve(): Any = U32 }
    @KSerializable object U64 : Bits(64) { override fun hashCode() = hashObject(this); private fun readResolve(): Any = U64 }
    @KSerializable object U128 : Bits(128) { override fun hashCode() = hashObject(this); private fun readResolve(): Any = U128 }
    @KSerializable object U256 : Bits(256) { override fun hashCode() = hashObject(this); private fun readResolve(): Any = U256 }
    @KSerializable object Address : Bits(256) { override fun hashCode() = hashObject(this); private fun readResolve(): Any = Address }
    @KSerializable object Signer : Bits(256) { override fun hashCode() = hashObject(this); private fun readResolve(): Any = Signer }

    sealed interface Container

    @KSerializable
    data class Vector(val elemType: MoveType.Value) : Value(), Container {
        override fun toString() = "vector<$elemType>"
        override fun displayName() = "vector<${elemType.displayName()}>"
        override fun toTag() = MoveTag.Vec(elemType)
        override fun symNameExt() = "vector!${elemType.symNameExt()}"
        override val isStatic get() = elemType.isStatic
    }

    @KSerializable
    sealed class Datatype : Value() {
        abstract val name: MoveDatatypeName
        abstract val typeArguments: List<MoveType.Value>

        override fun toString() = when {
            typeArguments.isEmpty() -> "$name"
            else -> "$name<${typeArguments.joinToString(", ")}>"
        }

        override fun displayName() = when {
            typeArguments.isEmpty() -> name.toString()
            else -> "${name}<${typeArguments.joinToString(", ") { it.displayName() }}>"
        }

        override fun symNameExt() = when {
            typeArguments.isEmpty() -> name.toVarName()
            else -> "${name.toVarName()}\$${typeArguments.joinToString("\$$") { it.symNameExt() }}"
        }

        override val isStatic get() = typeArguments.all { it.isStatic }
    }

    sealed interface Composite {
        @Treapable
        @KSerializable
        data class Field(val name: String, val type: MoveType.Value)

        abstract val fields: List<Field>?
    }

    @KSerializable
    data class Struct(
        override val name: MoveDatatypeName,
        override val typeArguments: List<MoveType.Value>,
        override val fields: List<Composite.Field>?
    ) : Datatype(), Composite {
        override fun toTag() = MoveTag.Struct(this)
        override fun toString() = super.toString() // Don't use compiler-generated toString
    }

    @KSerializable
    data class Enum(
        override val name: MoveDatatypeName,
        override val typeArguments: List<MoveType.Value>,
        val variants: List<Variant>
    ) : Datatype(), Container {
        override fun toTag() = MoveTag.Enum(this)
        override fun toString() = super.toString() // Don't use compiler-generated toString

        @Treapable
        @KSerializable
        data class Variant(
            val name: String,
            val index: Int,
            override val fields: List<Composite.Field>?
        ) : Composite
    }

    @KSerializable
    data class Reference(val refType: MoveType.Value) : MoveType() {
        override fun toString() = "&${refType}"
        override fun toTag() = MoveTag.Ref(refType)
        override fun symNameExt() = "ref!${refType.symNameExt()}"
    }

    /**
        Maps a 256-bit index to a union of [elemTypes].

        There is no union discriminator; uses that allow multiple types must also provide for their discrimination.  For
        example, CVLM ghost mappings do this by hashing different types into different slots in the array.
     */
    @KSerializable
    data class GhostArray(val elemTypes: Set<MoveType.Value>) : Value(), Container {
        /** Sort elem types to ensure that equal sets produce equal string representations. */
        private val sortedElemTypes get() = elemTypes.sortedBy { it.symNameExt() }

        override fun toString() = "array<${sortedElemTypes.joinToString(", ")}>"
        override fun displayName() = "(ghost)<${sortedElemTypes.joinToString(", ") { it.displayName() }}>"
        override fun toTag() = MoveTag.GhostArray(elemTypes)
        override fun symNameExt() = "array!${elemTypes.size}!${sortedElemTypes.joinToString("!") { it.symNameExt() }}"

        override val isStatic get() = elemTypes.all { it.isStatic }
    }

    @KSerializable
    object MathInt : Primitive() {
        override fun toString() = "mathint"
        override fun displayName() = "(mathint)"
        override fun toTag() = Tag.Int
        override fun toCoreTag() = Tag.Int
        override fun symNameExt() = "mathint"
        override fun hashCode() = hashObject(this)
        private fun readResolve(): Any = MathInt
    }

    /**
        A [Value] whose type is nondeterministic.  This is used as a type argument when instantiating generic functions,
        e.g. for parametric rules or built-in sanity rules.

        We represent these as 256-bit integers in TAC, always with nondet values.  This numeric value should only be
        used for equality comparisons!

        Each nondet type argument gets its own unique [id].  If a nondet-typed value has the same [id] as another
        nondet-typed value, then they are definitely of the same type.  Two nondet-typed values with different [id]s
        might be of the same type, or different types.  A nondet-typed value might be of the same type as a
        deterministically-typed value (e.g., a U8).

        When comparing types, we indirect through TACKeyword.MOVE_NONDET_TYPE_EQUIV map, which gives nondeterministic
        equivalence classes each Nondet type [id].  This allows for instances to hash equal to each other, or to be
        equal to "real" types, or to be equal to nothing else.

        For example, if we have a function `fun foo<A, B>()` we might instantiate it as `foo<Nondet(1), Nondet(2)>`.  We
        definitely want it to be the case that all variables of type `Nondet(1)` behave the same, and likewise for
        `Nondet(2)`.  Additionally, we want it to be possible for `Nondet(1)` and `Nondet(2)` to behave as the same
        type, otherwise we will miss cases such as `foo<Bar, Bar>`.
     */
    @KSerializable
    data class Nondet(val id: Int) : Simple() {
        override fun toString() = "nondet#$id"
        override fun displayName() = "(nondet#$id)"
        override fun toTag() = MoveTag.Nondet(this)
        override fun toCoreTag() = Tag.Bit256
        override fun symNameExt() = "nondet!$id"
        override val isStatic get() = false
    }

    /**
        Represents a function passed to a parametric rule.  This type shadows the CVLM type cvlm::function::Function.

        In TAC, this is represented as an integer, which is the index of the rule argument that this function came from.
        This allows us to dynamically dispatch to the correct function, in the case where a parametric rule has more
        than one function parameter.
     */
    @KSerializable
    object Function : Simple() {
        override fun toString() = "fun"
        override fun displayName() = "(fun)"
        override fun toTag() = Tag.Bit256
        override fun toCoreTag() = Tag.Bit256
        override fun symNameExt() = "fun"
        override fun hashCode() = hashObject(this)
        private fun readResolve(): Any = Function
        override val isStatic get() = true
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
    is SignatureToken.Datatype -> when (val def = definition(handle)) {
        is MoveModule.StructDefinition -> handle.toMoveStructOrShadow()
        is MoveModule.EnumDefinition -> def.toMoveEnum()
    }
    is SignatureToken.DatatypeInstantiation -> when (val def = definition(handle)) {
        is MoveModule.StructDefinition -> handle.toMoveStructOrShadow(typeArguments.map { it.toMoveValueType(typeArg) })
        is MoveModule.EnumDefinition -> def.toMoveEnum(typeArguments.map { it.toMoveValueType(typeArg) })
    }
    is SignatureToken.TypeParameter -> typeArg(this)
    is SignatureToken.Reference -> error("References are not valid value types: $this")
}

context(MoveScene)
fun List<MoveModule.FieldDefinition>.toCompositeFields(typeArgs: List<MoveType.Value>): List<MoveType.Composite.Field> {
    return map {
        val fieldType = it.signature.toMoveValueType { typeArgs[it.index.index] }
        if (fieldType == MoveType.Function) {
            // We only allow functions as parameters of rules, not as fields of structs.
            throw CertoraException(
                CertoraErrorType.CVL,
                "Functions cannot appear as fields of structs or enum variants."
            )
        }
        MoveType.Composite.Field(it.name, fieldType)
    }
}

context(MoveScene)
fun MoveModule.DatatypeHandle.toMoveStructRaw(
    typeArgs: List<MoveType.Value> = listOf(),
): MoveType.Struct {
    check(typeParameters.size == typeArgs.size) {
        "Type parameters size mismatch for $name: expected ${typeParameters.size}, got ${typeArgs.size}"
    }
    val def = definition(this)
    check(def is MoveModule.StructDefinition) {
        "Expected struct definition for $name, got $def"
    }
    return MoveType.Struct(
        MoveDatatypeName(definingModule, simpleName),
        typeArgs,
        def.fields?.toCompositeFields(typeArgs)
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

context(MoveScene)
fun MoveModule.EnumDefinition.toMoveEnum(
    typeArgs: List<MoveType.Value> = listOf()
): MoveType.Enum {
    check(typeArgs.size == typeParameters.size) {
        "Type parameters size mismatch for $name: expected ${typeParameters.size}, got ${typeArgs.size}"
    }
    return MoveType.Enum(
        name,
        typeArgs,
        variants.mapIndexed { i, it -> MoveType.Enum.Variant(it.name, i, it.fields.toCompositeFields(typeArgs)) }
    )
}

context(MoveScene)
fun MoveModule.EnumDefInstantiation.toMoveEnum(
    typeArgs: List<MoveType.Value> = listOf()
): MoveType.Enum {
    return enumDef.toMoveEnum(typeParameters.deref().tokens.map { it.toMoveValueType(typeArgs) })
}

fun MoveType.Simple.assignHavoc(dest: TACSymbol.Var, meta: MetaMap = MetaMap()): SimpleCmdsWithDecls {
    return when (this) {
        is MoveType.Bits -> {
            tac.generation.assign(dest, meta) {
                safeMathNarrowAssuming(unconstrained(Tag.Int), Tag.Bit256, MASK_SIZE(size))
            }
        }
        is MoveType.Bool, is MoveType.MathInt, is MoveType.Nondet -> {
            tac.generation.assignHavoc(dest, meta)
        }
        is MoveType.Function -> error("Function values should always be deterministic")
    }
}

fun MoveType.assignHavoc(dest: TACSymbol.Var, meta: MetaMap = MetaMap()): MoveCmdsWithDecls {
    return when (val type = this) {
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
        is MoveType.Simple -> type.assignHavoc(dest, meta)
        is MoveType.Vector, is MoveType.Struct, is MoveType.Enum, is MoveType.GhostArray -> {
            tac.generation.assignHavoc(dest, meta)
        }
    }
}

/**
    Gets the names of all structs used in this type.
 */
fun MoveType.consituentStructNames(): TreapSet<MoveDatatypeName> = when (this) {
    is MoveType.Bits, is MoveType.Primitive, is MoveType.Nondet, MoveType.Bool, MoveType.Function -> treapSetOf()
    is MoveType.Vector -> elemType.consituentStructNames()
    is MoveType.Enum -> variants.map { it.compositeStructNames() }.unionAll().orEmpty()
    is MoveType.Struct -> compositeStructNames() + name
    is MoveType.Reference -> refType.consituentStructNames()
    is MoveType.GhostArray -> elemTypes.map { it.consituentStructNames() }.unionAll().orEmpty()
}

fun MoveType.Composite.compositeStructNames(): TreapSet<MoveDatatypeName> {
    return fields?.map { it.type.consituentStructNames() }?.unionAll().orEmpty()
}
