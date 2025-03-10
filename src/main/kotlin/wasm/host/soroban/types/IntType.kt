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

package wasm.host.soroban.types

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.*
import utils.*
import vc.data.*
import wasm.host.soroban.*
import wasm.tacutils.*
import wasm.traps.*
import java.math.BigInteger

// Many int functions deal with "pieces" of the int, which are 64-bit chunks
private const val PIECE_BITS = 64

@KSerializable
@Treapable
sealed class IntType : ObjectType() {
    /** The tag for the "small" version of this type */
    abstract val smallTag: Val.Tag

    /** (handle)->value */
    abstract val values: TACSymbol.Var

    abstract val signed: Boolean
    abstract val numBits: Int
    val numBytes get() = numBits / BYTE_BITS
    val numPieces get() = numBits / PIECE_BITS

    val allBitsSet get() = (BigInteger.TWO.pow(numBits) - 1).asTACExpr

    override fun init() = mergeMany(
        super.init(),
        assignHavoc(values)
    )

    /**
        Creates a new object of this type, with the given value.
     */
    protected fun new(dest: TACSymbol.Var, valExpr: TACExprFact.() -> TACExpr) =
        valExpr.letVar("v") { v ->
            mergeMany(
                allocHandle(dest) { listOf(v) }, // The content summary is the value itself
                assume { Select(values.asSym(), dest.asSym()) eq v }
            )
        }

    /**
        Get the full value of this object
     */
    fun withValue(obj: TACExpr, f: (TACExpr.Sym) -> CommandWithRequiredDecls<TACCmd.Simple>) =
        TACExprFactUntyped {
            select(values.asSym(), obj)
        }.letVar { v ->
            mergeMany(
                assume { v le allBitsSet },
                f(v)
            )
        }

    // p * pieceMul(i) == (integer with i-th piece set to p)
    private fun pieceMul(piece: Int) = BigInteger.TWO.pow((numPieces - piece - 1) * PIECE_BITS).asTACExpr

    // n mod pieceMod == the 0th piece of n
    private val pieceMod = BigInteger.TWO.pow(PIECE_BITS).asTACExpr

    /**
        Construct an object from 64-bit pieces, in big-endian order
     */
    fun fromPieces(dest: TACSymbol.Var, vararg pieces: TACSymbol) =
        new(dest) { tacbv256FromPieces(pieces.toList()) }

    fun tacbv256FromPieces(pieces: List<TACSymbol>): TACExpr {
        check(pieces.size == numPieces) { "Expected $numPieces pieces, got ${pieces.size}" }
        return TACExprFactUntyped {
            (0..<numPieces).fold(0.asTACExpr as TACExpr) { acc, i ->
                acc add (pieces[i].asSym() mul pieceMul(i))
            }
        }
    }

    fun objPiece(obj: TACExpr, i: Int): TACExpr = TACExprFactUntyped {
            when (i) {
                0 -> obj div pieceMul(i)
                else -> (obj div pieceMul(i)) mod pieceMod
            }
        }

    /**
        Get the i-th piece of the object.  The 0th piece is the most significant.
     */
    fun toPiece(dest: TACSymbol.Var, obj: TACSymbol, i: Int) =
        withValue(obj.asSym()) { v ->
            assign(dest) {
                check(i in 0..<numPieces) { "Invalid piece $i for $this" }
                when {
                    i == 0 -> v div pieceMul(i)
                    else -> (v div pieceMul(i)) mod pieceMod
                }
            }
        }

    /**
        Decode the integer value in a Val (which might be small, or an object)
     */
    fun decodeVal(dest: TACSymbol.Var, v: TACExpr) =
        withValue(v) { value ->
            mergeMany(
                assign(dest) {
                    ite(
                        Val.hasTag(v, smallTag),
                        Val.decodeSmallInt(v, smallTag),
                        value
                    )
                }
            )
        }

    /**
        Encode an integer into a Val (either small or object)
     */
    fun encodeToVal(dest: TACSymbol.Var, v: TACSymbol) =
        TACKeyword.TMP(Tag.Bit256).let { obj ->
            mergeMany(
                new(obj) { v.asSym() },
                assign(dest) {
                    ite(
                        Val.fitsInSmallInt(v.asSym(), smallTag),
                        Val.encodeSmallInt(v.asSym(), smallTag),
                        obj.asSym()
                    )
                }
            )
        }

    // b * byteMul(i) == (integer with i-th byte set to b)
    private fun byteMul(i: Int) = BigInteger.TWO.pow((numBytes - i - 1) * BYTE_BITS).asTACExpr

    /**
        Make an integer value from the bytes, treated as a big-endian integer encoding.
     */
    fun integerFromBigEndianBytes(dest: TACSymbol.Var, bytes: TACSymbol) =
        BytesType.withSize(bytes) { bytesSize ->
            mergeMany(
                Trap.assert("Invalid size for $this") {
                    bytesSize.asSym() eq numBytes.asTACExpr
                },
                assign(dest) {
                    (0..<numBytes).fold(0.asTACExpr as TACExpr) { acc, i ->
                        val byte = BytesType.getValue(bytes, i.asTACExpr)
                        acc add (byte mul byteMul(i))
                    }
                }
            )
        }

    /**
        Get the bytes of of an integer, in big-endian order.
     */
    fun integerToBigEndianBytes(dest: TACSymbol.Var, v: TACSymbol) =
        BytesType.newFromBit256(dest, v)

    companion object {
        fun init() = mergeMany(
            U64.init(),
            I64.init(),
            U128.init(),
            I128.init(),
            U256.init(),
            I256.init(),
            Timepoint.init(),
            Duration.init()
        )
    }

    @KSerializable
    sealed class Int64 : IntType() {
        override val numBits = 64
    }

    @KSerializable
    sealed class Int128 : IntType() {
        override val numBits = 128
    }

    @KSerializable
    sealed class Int256 : IntType() {
        override val numBits = 256
    }

    @KSerializable
    object U64 : Int64() {
        override val tag = Val.Tag.U64Object
        override val smallTag = Val.Tag.U64Small
        override val values = TACKeyword.SOROBAN_U64_VALUES.toVar()
        override val signed = false

        override fun hashCode() = hashObject(this)
    }

    @KSerializable
    object I64 : Int64() {
        override val tag = Val.Tag.I64Object
        override val smallTag = Val.Tag.I64Small
        override val values = TACKeyword.SOROBAN_I64_VALUES.toVar()
        override val signed = true

        override fun hashCode() = hashObject(this)
    }

    @KSerializable
    object U128 : Int128() {
        override val tag = Val.Tag.U128Object
        override val smallTag = Val.Tag.U128Small
        override val values = TACKeyword.SOROBAN_U128_VALUES.toVar()
        override val signed = false

        override fun hashCode() = hashObject(this)
    }

    @KSerializable
    object I128 : Int128() {
        override val tag = Val.Tag.I128Object
        override val smallTag = Val.Tag.I128Small
        override val values = TACKeyword.SOROBAN_I128_VALUES.toVar()
        override val signed = true

        override fun hashCode() = hashObject(this)
    }

    @KSerializable
    object U256 : Int256() {
        override val tag = Val.Tag.U256Object
        override val smallTag = Val.Tag.U256Small
        override val values = TACKeyword.SOROBAN_U256_VALUES.toVar()
        override val signed = false

        override fun hashCode() = hashObject(this)
    }

    @KSerializable
    object I256 : Int256() {
        override val tag = Val.Tag.I256Object
        override val smallTag = Val.Tag.I256Small
        override val values = TACKeyword.SOROBAN_I256_VALUES.toVar()
        override val signed = true

        override fun hashCode() = hashObject(this)
    }

    @KSerializable
    object Timepoint : Int64() {
        override val tag = Val.Tag.TimepointObject
        override val smallTag = Val.Tag.TimepointSmall
        override val values = TACKeyword.SOROBAN_TIMEPOINT_VALUES.toVar()
        override val signed = false

        override fun hashCode() = hashObject(this)
    }

    @KSerializable
    object Duration : Int64() {
        override val tag = Val.Tag.DurationObject
        override val smallTag = Val.Tag.DurationSmall
        override val values = TACKeyword.SOROBAN_DURATION_VALUES.toVar()
        override val signed = false

        override fun hashCode() = hashObject(this)
    }
}

