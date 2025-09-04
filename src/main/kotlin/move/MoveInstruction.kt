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

import datastructures.stdcollections.*
import java.math.BigInteger
import utils.*

/**
    Simplified view of Move instructions.  Generic types/functions are resolved, the set of "borrow" instructions is
    reduced, etc.
 */
sealed class MoveInstruction {
    open val branchTarget: Int? = null

    object Nop : MoveInstruction()
    object Pop : MoveInstruction()
    object Ret : MoveInstruction()
    object Abort : MoveInstruction()

    data class BrTrue(override val branchTarget: Int) : MoveInstruction()
    data class BrFalse(override val branchTarget: Int) : MoveInstruction()
    data class Branch(override val branchTarget: Int) : MoveInstruction()

    object LdTrue : MoveInstruction()
    object LdFalse : MoveInstruction()
    data class LdU8(val value: UByte) : MoveInstruction()
    data class LdU16(val value: UShort) : MoveInstruction()
    data class LdU32(val value: UInt) : MoveInstruction()
    data class LdU64(val value: ULong) : MoveInstruction()
    data class LdU128(val value: BigInteger) : MoveInstruction()
    data class LdU256(val value: BigInteger) : MoveInstruction()
    data class LdConst(val type: MoveType.Value, val data: List<UByte>) : MoveInstruction()

    data class Cast(val toType: MoveType.Bits) : MoveInstruction()

    data class CopyLoc(val index: Int) : MoveInstruction()
    data class MoveLoc(val index: Int) : MoveInstruction()
    data class StLoc(val index: Int) : MoveInstruction()

    object ReadRef : MoveInstruction()
    object WriteRef : MoveInstruction()
    object FreezeRef : MoveInstruction()

    data class BorrowLoc(val index: Int) : MoveInstruction()

    data class Pack(val type: MoveType.Struct) : MoveInstruction()
    data class Unpack(val type: MoveType.Struct) : MoveInstruction()
    data class BorrowField(val type: MoveType.Struct, val index: Int) : MoveInstruction()

    data class VecPack(val elemType: MoveType.Value, val count: ULong) : MoveInstruction()
    data class VecUnpack(val count: ULong) : MoveInstruction()
    object VecLen : MoveInstruction()
    object VecBorrow : MoveInstruction()
    object VecPushBack : MoveInstruction()
    object VecPopBack : MoveInstruction()
    object VecSwap : MoveInstruction()

    object Add : MoveInstruction()
    object Sub : MoveInstruction()
    object Mul : MoveInstruction()
    object Mod : MoveInstruction()
    object Div : MoveInstruction()
    object BitOr : MoveInstruction()
    object BitAnd : MoveInstruction()
    object Xor : MoveInstruction()
    object Or : MoveInstruction()
    object And : MoveInstruction()
    object Not : MoveInstruction()
    object Eq : MoveInstruction()
    object Neq : MoveInstruction()
    object Lt : MoveInstruction()
    object Gt : MoveInstruction()
    object Le : MoveInstruction()
    object Ge : MoveInstruction()
    object Shl : MoveInstruction()
    object Shr : MoveInstruction()

    data class Call(val callee: MoveFunction) : MoveInstruction()
}

context(MoveScene)
fun MoveModule.Instruction.toMoveInstruction(
    typeArguments: List<MoveType.Value>
): MoveInstruction = when (this) {
    is MoveModule.Instruction.Nop -> MoveInstruction.Nop
    is MoveModule.Instruction.Pop -> MoveInstruction.Pop
    is MoveModule.Instruction.Ret -> MoveInstruction.Ret
    is MoveModule.Instruction.Abort -> MoveInstruction.Abort

    is MoveModule.Instruction.BrTrue -> MoveInstruction.BrTrue(branchTarget.index)
    is MoveModule.Instruction.BrFalse -> MoveInstruction.BrFalse(branchTarget.index)
    is MoveModule.Instruction.Branch -> MoveInstruction.Branch(branchTarget.index)

    is MoveModule.Instruction.LdTrue -> MoveInstruction.LdTrue
    is MoveModule.Instruction.LdFalse -> MoveInstruction.LdFalse
    is MoveModule.Instruction.LdU8 -> MoveInstruction.LdU8(value)
    is MoveModule.Instruction.LdU16 -> MoveInstruction.LdU16(value)
    is MoveModule.Instruction.LdU32 -> MoveInstruction.LdU32(value)
    is MoveModule.Instruction.LdU64 -> MoveInstruction.LdU64(value)
    is MoveModule.Instruction.LdU128 -> MoveInstruction.LdU128(value)
    is MoveModule.Instruction.LdU256 -> MoveInstruction.LdU256(value)
    is MoveModule.Instruction.LdConst -> index.deref().let {
        MoveInstruction.LdConst(it.type.toMoveValueType(listOf()), it.data)
    }

    is MoveModule.Instruction.CastU8 -> MoveInstruction.Cast(MoveType.U8)
    is MoveModule.Instruction.CastU16 -> MoveInstruction.Cast(MoveType.U16)
    is MoveModule.Instruction.CastU32 -> MoveInstruction.Cast(MoveType.U32)
    is MoveModule.Instruction.CastU64 -> MoveInstruction.Cast(MoveType.U64)
    is MoveModule.Instruction.CastU128 -> MoveInstruction.Cast(MoveType.U128)
    is MoveModule.Instruction.CastU256 -> MoveInstruction.Cast(MoveType.U256)

    is MoveModule.Instruction.CopyLoc -> MoveInstruction.CopyLoc(index.index)
    is MoveModule.Instruction.MoveLoc -> MoveInstruction.MoveLoc(index.index)
    is MoveModule.Instruction.StLoc -> MoveInstruction.StLoc(index.index)

    is MoveModule.Instruction.ReadRef -> MoveInstruction.ReadRef
    is MoveModule.Instruction.WriteRef -> MoveInstruction.WriteRef
    is MoveModule.Instruction.FreezeRef -> MoveInstruction.FreezeRef

    is MoveModule.Instruction.ImmBorrowLoc -> MoveInstruction.BorrowLoc(index.index)
    is MoveModule.Instruction.MutBorrowLoc -> MoveInstruction.BorrowLoc(index.index)

    is MoveModule.Instruction.Pack -> MoveInstruction.Pack(
        index.deref().structHandle.toMoveStruct()
    )
    is MoveModule.Instruction.PackGeneric -> MoveInstruction.Pack(
        index.deref().let { inst ->
            inst.def.deref().structHandle.toMoveStruct(
                inst.typeParameters.deref().tokens.map { it.toMoveValueType(typeArguments) }
            )
        }
    )
    is MoveModule.Instruction.Unpack -> MoveInstruction.Unpack(index.deref().structHandle.toMoveStruct())
    is MoveModule.Instruction.UnpackGeneric -> MoveInstruction.Unpack(
        index.deref().let { inst ->
            inst.def.deref().structHandle.toMoveStruct(
                inst.typeParameters.deref().tokens.map { it.toMoveValueType(typeArguments) }
            )
        }
    )
    is MoveModule.Instruction.ImmBorrowField -> {
        val field = index.deref()
        MoveInstruction.BorrowField(field.owner.structHandle.toMoveStruct(), field.fieldIndex)
    }
    is MoveModule.Instruction.MutBorrowField -> {
        val field = index.deref()
        MoveInstruction.BorrowField(field.owner.structHandle.toMoveStruct(), field.fieldIndex)
    }
    is MoveModule.Instruction.ImmBorrowFieldGeneric -> {
        val inst = index.deref()
        val field = inst.handle.deref()
        MoveInstruction.BorrowField(
            field.owner.structHandle.toMoveStruct(
                inst.typeParameters.deref().tokens.map { it.toMoveValueType(typeArguments) }
            ),
            field.fieldIndex
        )
    }
    is MoveModule.Instruction.MutBorrowFieldGeneric -> {
        val inst = index.deref()
        val field = inst.handle.deref()
        MoveInstruction.BorrowField(
            field.owner.structHandle.toMoveStruct(
                inst.typeParameters.deref().tokens.map { it.toMoveValueType(typeArguments) }
            ),
            field.fieldIndex
        )
    }

    is MoveModule.Instruction.VecPack -> MoveInstruction.VecPack(
        elemType = (
            index.deref().tokens.singleOrNull() ?: error("VecPack with multiple types: ${index.deref().tokens}")
        ).toMoveValueType(typeArguments),
        count = count
    )
    is MoveModule.Instruction.VecUnpack -> MoveInstruction.VecUnpack(count)
    is MoveModule.Instruction.VecLen -> MoveInstruction.VecLen
    is MoveModule.Instruction.VecImmBorrow -> MoveInstruction.VecBorrow
    is MoveModule.Instruction.VecMutBorrow -> MoveInstruction.VecBorrow
    is MoveModule.Instruction.VecPushBack -> MoveInstruction.VecPushBack
    is MoveModule.Instruction.VecPopBack -> MoveInstruction.VecPopBack
    is MoveModule.Instruction.VecSwap -> MoveInstruction.VecSwap

    is MoveModule.Instruction.Add -> MoveInstruction.Add
    is MoveModule.Instruction.Sub -> MoveInstruction.Sub
    is MoveModule.Instruction.Mul -> MoveInstruction.Mul
    is MoveModule.Instruction.Mod -> MoveInstruction.Mod
    is MoveModule.Instruction.Div -> MoveInstruction.Div
    is MoveModule.Instruction.BitOr -> MoveInstruction.BitOr
    is MoveModule.Instruction.BitAnd -> MoveInstruction.BitAnd
    is MoveModule.Instruction.Xor -> MoveInstruction.Xor
    is MoveModule.Instruction.Or -> MoveInstruction.Or
    is MoveModule.Instruction.And -> MoveInstruction.And
    is MoveModule.Instruction.Not -> MoveInstruction.Not
    is MoveModule.Instruction.Eq -> MoveInstruction.Eq
    is MoveModule.Instruction.Neq -> MoveInstruction.Neq
    is MoveModule.Instruction.Lt -> MoveInstruction.Lt
    is MoveModule.Instruction.Gt -> MoveInstruction.Gt
    is MoveModule.Instruction.Le -> MoveInstruction.Le
    is MoveModule.Instruction.Ge -> MoveInstruction.Ge
    is MoveModule.Instruction.Shl -> MoveInstruction.Shl
    is MoveModule.Instruction.Shr -> MoveInstruction.Shr

    is MoveModule.Instruction.Call -> MoveInstruction.Call(MoveFunction(index.deref(), typeArguments = listOf()))
    is MoveModule.Instruction.CallGeneric -> MoveInstruction.Call(
        index.deref().let { inst ->
            MoveFunction(
                inst.function,
                inst.typeParameters.deref().tokens.map { it.toMoveValueType(typeArguments) }
            )
        }
    )

    is MoveModule.Instruction.PackVariant,
    is MoveModule.Instruction.PackVariantGeneric,
    is MoveModule.Instruction.UnpackVariant,
    is MoveModule.Instruction.UnpackVariantImmRef,
    is MoveModule.Instruction.UnpackVariantMutRef,
    is MoveModule.Instruction.UnpackVariantGeneric,
    is MoveModule.Instruction.UnpackVariantGenericImmRef,
    is MoveModule.Instruction.UnpackVariantGenericMutRef,
    is MoveModule.Instruction.VariantSwitch ->
        error("Variant instructions are not supported: $this")

    is MoveModule.Instruction.ExistsDeprecated,
    is MoveModule.Instruction.ExistsGenericDeprecated,
    is MoveModule.Instruction.MoveFromDeprecated,
    is MoveModule.Instruction.MoveFromGenericDeprecated,
    is MoveModule.Instruction.MoveToDeprecated,
    is MoveModule.Instruction.MoveToGenericDeprecated,
    is MoveModule.Instruction.MutBorrowGlobalDeprecated,
    is MoveModule.Instruction.MutBorrowGlobalGenericDeprecated,
    is MoveModule.Instruction.ImmBorrowGlobalDeprecated,
    is MoveModule.Instruction.ImmBorrowGlobalGenericDeprecated ->
        error("Deprecated instructions are not supported: $this")
}
