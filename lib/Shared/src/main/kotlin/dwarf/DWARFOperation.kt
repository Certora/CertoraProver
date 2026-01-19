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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dwarf

import com.certora.collect.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import utils.*

/**
 * This class matches the supported operations from
 * https://github.com/gimli-rs/gimli/blob/2c7fa599ee25e2f74dd1c79d9a0f5e968f07283e/src/read/op.rs#L34
 * All items are serialized in the rust code in scripts/Gimli-DWARF-JSONDump/src/operation_types.rs
 * and deserialized here.
 *
 * Not all operations are handled. All unsupported operations are all mapped to [Unsupported].
 */
@Treapable
@Serializable
sealed class DWARFOperation : HasKSerializable {
    @Serializable
    @SerialName("Register")
    data class Register(
        val register: UShort
    ) : DWARFOperation(), RegisterAccess {
        override fun toString(): String {
            return "DW_OP_reg r${register}"
        }
        override fun offset(): Long = 0L
        override fun register(): Byte = register.toByte()
    }

    @Serializable
    @SerialName("FrameOffset")
    data class FrameOffset(
        val offset: Long,
    ) : DWARFOperation(), RegisterAccess {
        override fun toString(): String {
            return "DW_OP_fbreg +${offset}"
        }
        override fun offset(): Long = offset
        override fun register(): Byte = 10.toByte()
    }

    @Serializable
    @SerialName("RegisterOffset")
    data class RegisterOffset(
        val register: UShort,
        val offset: Long,
        @SerialName("base_type")
        val baseType: String
    ) : DWARFOperation(), RegisterAccess {
        override fun toString(): String {
            return "DW_OP_breg r$register $offset ($baseType)"
        }
        override fun offset(): Long = offset
        override fun register(): Byte = register.toByte()
    }

    @Serializable
    @SerialName("Piece")
    data class Piece(
        @SerialName("size_in_bits")
        val sizeInBits: ULong,
        @SerialName("bit_offset")
        val bitOffset: ULong? = null
    ) : DWARFOperation() {

        override fun toString(): String {
            return "DW_OP_piece ${bitOffset} ${sizeInBits}"
        }
    }

    @Serializable
    @SerialName("UnsignedConstant")
    data class UnsignedConstant(
        val value: ULong,
    ) : DWARFOperation() {
        override fun toString(): String {
            return "UnsignedConstant ${value}"
        }
    }

    @Serializable
    @SerialName("SignedConstant")
    data class SignedConstant(
        val value: Long,
    ) : DWARFOperation() {

        override fun toString(): String {
            return "SignedConstant ${value}"
        }
    }

    @Serializable
    @SerialName("StackValue")
    data object StackValue : DWARFOperation()

    @Serializable
    @SerialName("And")
    data object And : DWARFOperation()

    @Serializable
    @SerialName("Or")
    data object Or : DWARFOperation()

    @Serializable
    @SerialName("Plus")
    data object Plus : DWARFOperation()

    @Serializable
    @SerialName("Minus")
    data object Minus : DWARFOperation()

    @Serializable
    @SerialName("Unsupported")
    data class Unsupported(
        val operation: String
    ) : DWARFOperation() {
        override fun toString(): String {
            return operation
        }
    }

    sealed interface RegisterAccess{
        fun offset(): Long
        fun register(): Byte
    }
}