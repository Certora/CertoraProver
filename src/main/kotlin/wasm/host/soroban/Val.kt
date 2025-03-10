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

package wasm.host.soroban

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import datastructures.stdcollections.*
import utils.ModZm.Companion.to2s
import vc.data.*
import wasm.host.soroban.Val.digest
import wasm.tacutils.*
import wasm.traps.Trap
import java.math.BigInteger
import vc.data.TACExprFactUntyped as expr

// Implements the semantics of Soroban's `Val` discriminated union type:
//   https://github.com/stellar/rs-soroban-env/blob/main/soroban-env-common/src/val.rs

object Val {
    enum class Tag(val value: Int, val signed: Boolean? = null) {
        /// Tag for a [Val] that encodes [bool] `false`. The bool type is refined to
        /// two single-value subtypes in order for each tag number to coincide with
        /// the WASM encoding of a boolean.
        False(0x00),

        /// Tag for a [Val] that encodes [bool] `true`.
        True(0x01),

        /// Tag for a [Val] that is empty/absent (eg. void, null, nil, undefined, None)
        Void(0x02),

        /// Tag for a [Val] that is contains an error code.
        Error(0x03),

        /// Tag for a [Val] that contains a [u32] number.
        U32Val(0x04, signed = false),

        /// Tag for a [Val] that contains an [i32] number.
        I32Val(0x05, signed = true),

        /// Tag for a [Val] that contains a [u64] small enough to fit in 56 bits.
        U64Small(0x06, signed = false),

        /// Tag for a [Val] that contains an [i64] small enough to fit in 56 bits.
        I64Small(0x07, signed = true),

        /// Tag for a [Val] that contains a [u64] timepoint small enough to fit
        /// in 56 bits.
        TimepointSmall(0x08),

        /// Tag for a [Val] that contains a [u64] duration small enough to fit in
        /// 56 bits.
        DurationSmall(0x09),

        /// Tag for a [Val] that contains a [u128] small enough to fit in 56 bits.
        U128Small(0x0A, signed = false),

        /// Tag for a [Val] that contains an [i128] small enough to fit in 56 bits.
        I128Small(0x0B, signed = true),

        /// Tag for a [Val] that contains a [u256](ethnum::u256) small enough to fit in 56 bits.
        U256Small(0x0C, signed = false),

        /// Tag for a [Val] that contains an [i256](ethnum::i256) small enough to fit in 56 bits.
        I256Small(0x0D, signed = true),

        /// Tag for a [Val] that contains up to 9 character symbols.
        SymbolSmall(0x0E),

        /// Code delimiting the upper boundary of "small" types.
        SmallCodeUpperBound(0x0F),

        /// Tag reserved to indicate boundary between tags for "small" types with
        /// their payload packed into the remaining 56 bits of the [Val] and
        /// "object" types that are stored as host objects and referenced by
        /// [Object](crate::Object) handle.
        ObjectCodeLowerBound(0x3F),

        /// Tag for a [Val] that refers to a host-side [u64] number.
        U64Object(0x40, signed = false),

        /// Tag for a [Val] that refers to a host-side [i64] number.
        I64Object(0x41, signed = true),

        /// Tag for a [Val] that refers to a host-side [u64] number encoding a
        /// time-point (a count of seconds since the Unix epoch, Jan 1 1970 UTC).
        TimepointObject(0x42),

        /// Tag for a [Val] that refers to a host-side [i64] number encoding a
        /// duration (a count of seconds).
        DurationObject(0x43),

        /// Tag for a [Val] that refers to a host-side [u128] number.
        U128Object(0x44, signed = false),

        /// Tag for a [Val] that refers to a host-side [i128] number.
        I128Object(0x45, signed = true),

        /// Tag for a [Val] that refers to a host-side [u256](ethnum::u256) number.
        U256Object(0x46, signed = false),

        /// Tag for a [Val] that refers to a host-side [i256](ethnum::i256) number.
        I256Object(0x47, signed = true),

        /// Tag for a [Val] that refers to a host-side byte array.
        BytesObject(0x48),

        /// Tag for a [Val] that refers to a host-side string.
        StringObject(0x49),

        /// Tag for a [Val] that refers to a host-side symbol (see [`Symbol`](crate::Symbol)).
        SymbolObject(0x4A),

        /// Tag for a [Val] that refers to a host-side vector of [Val]s.
        VecObject(0x4B),

        /// Tag for a [Val] that refers to a host-side map from [Val]s to [Val]s.
        MapObject(0x4C),

        /// Tag for a [Val] that refers to a host-side contract address.
        AddressObject(0x4D),

        /// Code delimiting the upper boundary of "object" types.
        ObjectCodeUpperBound(0x4E),

        /// Code reserved to indicate mis-tagged [`Val`]s.
        Bad(0x7F),

        ;

        val isInteger get() = signed != null
        val isObjectTag get() = value in ObjectCodeLowerBound.value..ObjectCodeUpperBound.value
    }

    fun init() = mergeMany(
        assignHavoc(TACKeyword.SOROBAN_OBJECTS.toVar()),
        assignHavoc(TACKeyword.SOROBAN_OBJECT_DIGEST.toVar()),
    )

    // The tag is the low 8 bits of the 64-bit Val
    const val TAG_MUL = 0x100
    const val TAG_BITS = 8

    // u32/i32 are encded in the upper 32 bits of the Val
    private const val INT32_MUL = 0x100000000L
    private const val INT32_BITS = 32

    val VOID = Val(Tag.Void, 0)

    const val sizeInBytes = 8 // 64 bits

    /** Create a TACSymbol that produces a Val */
    operator fun invoke(tag: Tag, value: Long): TACExpr.Sym {
        check((value * TAG_MUL) / TAG_MUL == value) { "Value $value is too large for a Val" }
        return ((value * TAG_MUL) or tag.value.toLong()).asTACExpr
    }

    /** Allocate a new object handle.  We choose a nonterministic handle that is not already allocated. */
    fun allocHandle(dest: TACSymbol.Var, tag: Tag, contentSummary: (TACExprFact.() -> List<TACExpr>)? = null) = mergeMany(
        listOfNotNull(
            assignHavoc(dest),
            assumeValid(dest, false, tag),
            assume { not(select(TACKeyword.SOROBAN_OBJECTS.toVar().asSym(), dest.asSym())) },
            assign(TACKeyword.SOROBAN_OBJECTS.toVar()) {
                Store(TACKeyword.SOROBAN_OBJECTS.toVar().asSym(), dest.asSym(), v = true.asTACExpr)
            },
            contentSummary?.let { setObjectDigest(tag, dest.asSym(), contentSummary) }
        )
    )

    /**
        Sign-extend the payload of a (64-bit) Val to 256 bits. Note that this works because all payloads are
        "left-justified" in the Val, so the MSB of the payload is the MSB of the Val.
     */
    private fun TACExpr.signExtendVal() = TACExpr.BinOp.SignExtend(7.asTACExpr, this)

    fun decodeSmallInt(value: TACExpr, tag: Tag) = expr {
        when(tag) {
            Tag.I32Val -> value.signExtendVal() shiftRArith INT32_BITS.asTACExpr
            Tag.U32Val -> value shiftRLog INT32_BITS.asTACExpr
            Tag.I64Small, Tag.I128Small, Tag.I256Small -> value.signExtendVal() shiftRArith TAG_BITS.asTACExpr
            Tag.U64Small, Tag.U128Small, Tag.U256Small -> value shiftRLog TAG_BITS.asTACExpr
            else -> error("Tag $tag is not a small integer tag")
        }
    }

    fun encodeSmallInt(value: TACExpr, tag: Tag) = expr {
        when(tag) {
            Tag.I32Val, Tag.U32Val -> (value mul INT32_MUL.asTACExpr) add Val(tag, 0)
            Tag.I64Small, Tag.I128Small, Tag.I256Small,
            Tag.U64Small, Tag.U128Small, Tag.U256Small -> (value mul TAG_MUL.asTACExpr) add Val(tag, 0)
            else -> error("Tag $tag is not a small integer tag")
        }
    }

    // Bounds for small values that can be "inlined" in the Val representation.
    private val maxSmallUInt = 0xffff_ffff_ffff_ff.asTACExpr
    private val maxSmallInt = 0x7fff_ffff_ffff_ff.asTACExpr
    // the 2s complement sign-extended version of the `-0x8000_0000_0000_00`.
    private val minSmallInt = (-0x8000_0000_0000_00).to2s(tac.Tag.Bit256).asTACExpr

    fun fitsInSmallInt(value: TACExpr, tag: Tag) = expr {
        when(tag) {
            Tag.I32Val, Tag.U32Val ->
                true.asTACExpr

            Tag.I64Small, Tag.I128Small, Tag.I256Small ->
                (value sLe maxSmallInt) and (value sGe minSmallInt)

            Tag.U64Small, Tag.U128Small, Tag.U256Small ->
                (value le maxSmallUInt)

            else -> error("Tag $tag is not a small integer tag")
        }
    }

    fun encodeBool(value: TACExpr) = expr {
        ite(value, Val(Tag.True, 0), Val(Tag.False, 0))
    }

    /** Assign a void Val to [dest] */
    fun assignVoid(dest: TACSymbol.Var) = listOf(
        TACCmd.Simple.AssigningCmd.AssignExpCmd(dest, Val.VOID)
    ).withDecls(dest)

    private fun getTag(sym: TACExpr) = expr {
        sym mod TAG_MUL.asTACExpr
    }

    fun hasTag(sym: TACExpr, tag: Tag) = expr {
        getTag(sym) eq Val(tag, 0)
    }

    fun isAllocated(sym: TACExpr) = expr {
        select(TACKeyword.SOROBAN_OBJECTS.toVar().asSym(), sym)
    }

    /** Assert [sym] is a valid boolean (0 or 1) */
    fun assertIsBool(sym: TACSymbol, module: String, func: String) =
        Trap.assert("Expected %1\$s to be a valid boolean, in $module/$func", sym) { isBool(sym.asSym()) }

    private fun isBool(v: TACExpr) = expr {
        (v eq 0.asTACExpr) or (v eq 1.asTACExpr)
    }

    private fun hasSmallValTag(tag: TACExpr) = expr {
        tag lt Tag.SmallCodeUpperBound.value.asTACExpr
    }

    private fun checkValidTag(tag: TACExpr, validTags: Array<out Tag>) = expr {
        if (validTags.isEmpty()) {
            true.asTACExpr
        } else {
            validTags.map { tag eq it.value.asTACExpr }.reduce { a, b -> a or b }
        }
    }

    private fun checkInt32Range(tag: TACExpr, v: TACExpr, validTags: Array<out Tag>) = expr {
        when {
            // Are we definitely only expecting I32 or U32?
            validTags.isNotEmpty() && validTags.all { it == Tag.I32Val || it == Tag.U32Val } -> {
                (v mod INT32_MUL.asTACExpr) eq tag
            }

            // Are we definitely not expecting I32 or U32?
            validTags.isNotEmpty() && validTags.none { it == Tag.I32Val || it == Tag.U32Val } -> {
                true.asTACExpr
            }

            // Ok, we don't know if it should be a 32-bit int or not
            else -> {
                (
                    (tag eq Val(Tag.I32Val, 0)) or (tag eq Val(Tag.U32Val, 0))
                ) implies (
                    (v mod INT32_MUL.asTACExpr) eq tag
                )
            }
        }
    }

    private fun assumeAllocated(v: TACExpr, validTags: Array<out Tag>) =
        when {
            // Are we definitely not expecting an object?
            validTags.isNotEmpty() && validTags.none { it.isObjectTag } -> {
                // No check needed
                CommandWithRequiredDecls<TACCmd.Simple>()
            }

            // Ok, we don't know if it will be an object or not
            else -> {
                assume {
                    // No need to check if this is an object; we don't care what SOROBAN_OBJECTS reports for
                    // non-objects.
                    isAllocated(v)
                }
            }
        }

    private fun withValidity(
        v: TACExpr,
        checkAlloc: Boolean,
        validTags: Array<out Tag>,
        f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
    ) =
        getTag(v).letVar { tag ->
            expr {
                (v lt BigInteger.TWO.pow(64).asTACExpr) and
                checkValidTag(tag, validTags) and
                checkInt32Range(tag, v, validTags)
            }.letVar(tac.Tag.Bool) { valid ->
                if (!checkAlloc) {
                    f(valid)
                } else {
                    mergeMany(
                        assumeAllocated(v, validTags),
                        f(valid)
                    )
                }
            }
        }

    fun assertValid(sym: TACSymbol, module: String, func: String, vararg validTags: Tag) =
        withValidity(sym.asSym(), true, validTags) { valid ->
            Trap.assert("Expected %1\$s to be a valid Val, in $module/$func", sym) { valid }
        }

    fun assumeValid(sym: TACSymbol, checkAlloc: Boolean, vararg validTags: Tag) =
        withValidity(sym.asSym(), checkAlloc, validTags) { valid ->
            assume { valid }
        }

    fun assumeValid(sym: TACSymbol, vararg validTags: Tag) = assumeValid(sym, true, *validTags)

    /**
        Computes the digest of Val [v]

        To enable structural comparisons of Vals (which might be structured objects), we compute a digest of the
        contents of the vals being compared.

        The digest is a 256-bit integer; all possible Val values must be mapped into this space, *by value* (meaning
        that, e.g. string objects cannot use their handle as the digest, and must derive the digest from the string
        contents instead. To accomplish this, we use SimpleHash, which is an arbitrary injective function from some
        number of arguments to ain Int256.

        For "small" Vals, we simply hash of the Val itself (which includes its tag).

        For objects, the digest is set when allocating the handle, and consists of a hash of the tag plus some 256-bit
        summary of the object's value.

        The only object types that currently have precise digests are the large integers (the "summary" is the integer
        value itself), and strings/symbols if they have a literal value (the "summary" is a keccak hash of the literal
        value).
     */
    fun digest(v: TACExpr) = expr {
        ite(
            hasSmallValTag(getTag(v)),
            SimpleHash(1.asTACExpr, listOf(v), HashFamily.SorobanObjectDigest),
            select(TACKeyword.SOROBAN_OBJECT_DIGEST.toVar().asSym(), v)
        )
    }

    /**
        Calls [f] with the digest of [v].  This is a convenience method to get the value stored in correct var type.
     */
    fun withDigest(v: TACExpr, f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd.Simple>) =
        digest(v).letVar("digest", tac.Tag.Bit256, f)


    /**
        Computes and stores the digest of a given object.  This is a hash of the object type's tag, and a 256-bit
        summary of the object's value (see [digest]).

        Empty objects can pass { null } as [contentSummary]
     */
    fun setObjectDigest(
        tag: Val.Tag,
        handle: TACExpr,
        contentSummary: TACExprFact.() -> List<TACExpr>
    ) = mergeMany(
        assume {
            Select(
                TACKeyword.SOROBAN_OBJECT_DIGEST.toVar().asSym(),
                handle
            ) eq createObjectDigest(contentSummary(), tag)
        }
    )

    /**
        Constructs an object digest from the object's content summary, and its tag.  See [digest] for more.
     */
    private fun createObjectDigest(contentSummary: List<TACExpr>, tag: Val.Tag) = expr {
        SimpleHash((contentSummary.size + 1).asTACExpr, listOf(tag.value.asTACExpr) + contentSummary, HashFamily.SorobanObjectDigest)
    }
}
