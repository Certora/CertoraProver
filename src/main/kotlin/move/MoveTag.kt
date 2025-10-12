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

import move.MoveModule.*
import tac.*
import utils.*

/**
    TAC tags for Move types that are not available in "core" TAC.  These are used to communicate between `MoveToTAC` and
    `MoveMemory.transform`, and will not appear in a TAC program after that.
 */
@KSerializable
sealed class MoveTag : Tag.Move(), HasKSerializable {
    @KSerializable data class Vec(val elemType: MoveType.Value) : MoveTag()
    @KSerializable data class Struct(val type: MoveType.Struct) : MoveTag()
    @KSerializable data class Enum(val type: MoveType.Enum) : MoveTag()
    @KSerializable data class Ref(val refType: MoveType.Value) : MoveTag()

    /** Maps u256 -> elemType */
    @KSerializable data class GhostArray(val elemType: MoveType.Value) : MoveTag()

    /** See [MoveType.Nondet] */
    @KSerializable data class Nondet(val type: MoveType.Nondet) : MoveTag()
}

fun MoveTag.toMoveType(): MoveType {
    return when (this) {
        is MoveTag.Vec -> MoveType.Vector(elemType)
        is MoveTag.Struct -> type
        is MoveTag.Enum -> type
        is MoveTag.Ref -> MoveType.Reference(refType)
        is MoveTag.GhostArray -> MoveType.GhostArray(elemType)
        is MoveTag.Nondet -> type
    }
}

fun MoveTag.toMoveValueType(): MoveType.Value {
    return when (this) {
        is MoveTag.Vec -> MoveType.Vector(elemType)
        is MoveTag.Struct -> type
        is MoveTag.Enum -> type
        is MoveTag.Ref -> error("MoveTag.Ref cannot be converted to MoveType.Value")
        is MoveTag.GhostArray -> MoveType.GhostArray(elemType)
        is MoveTag.Nondet -> type
    }
}
