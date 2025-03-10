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

package smt.solverscript.functionsymbols

import datastructures.stdcollections.*
import smt.solverscript.functionsymbols.IFixedFunctionSignatures.*
import smtlibutils.data.ISmtScript
import smtlibutils.data.SmtFunctionSymbol
import tac.Tag
import utils.KSerializable
import utils.hashObject
import vc.data.HashFamily
import vc.data.LExpression
import vc.data.ToSmtLibData

/**
 * Function Symbols that are given a meaning in some SMT theory.
 */
@KSerializable
sealed class NonSMTInterpretedFunctionSymbol : InterpretedFunctionSymbol() {
    override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): SmtFunctionSymbol =
        error("this function symbol $name (${this.javaClass}) should have been converted to something else before moving to SmtLib")

    @KSerializable
    sealed class Nullary(
        override val name: String,
        override val signature: FunctionSignature
    ) : NonSMTInterpretedFunctionSymbol() {

        @KSerializable
        data class Nondet(val tag: Tag) : Nullary("*", FixedFunctionSignatures(listOf(), tag))
    }

    @KSerializable
    sealed class Unary(
        override val name: String,
        override val signature: FunctionSignature
    ) : NonSMTInterpretedFunctionSymbol() {
        constructor(name: String, paramSort: Tag, resultSort: Tag) :
            this(name, FixedFunctionSignatures(listOf(paramSort), resultSort))

        @KSerializable
        data class BitwiseNot(val tag: Tag.Bits) : Unary("~", tag, tag)

        @KSerializable
        data class Extract(val l: Int, val r: Int) : Unary("extract", Tag.Int, Tag.Int)

        /** Sign-extend to [i] bits. */
        @KSerializable
        data class SignedPromote(val from: Tag.Bits, val to: Tag.Bits) :
            Unary("signed_promote_${to.bitwidth}_${from.bitwidth}", from, to) {
            init {
                check(from.bitwidth < to.bitwidth) { "SignedPromotion($from, $to) does not actually promote." }
            }
        }

        /** Zero-extend to [i] bits. */
        @KSerializable
        data class UnsignedPromote(val from: Tag.Bits, val to: Tag.Bits) :
            Unary("unsigned_promote_${to.bitwidth}_${from.bitwidth}", from, to) {
            init {
                check(from.bitwidth < to.bitwidth) { "UnsignedPromotion($from, $to) does not actually promote." }
            }
        }

        @KSerializable
        data class SafeMathNarrow(val to: Tag.Bits) : Unary("safe_math_narrow_${to.bitwidth}", Tag.Int, to)

        /**
         * Narrows an operand of [from] to [to]. Assumes that the argument, interpreted as signed value in 2s
         * complement, is in range of the resulting tag. The result of this operation are always the lower bits of the
         * operand.
         */
        @KSerializable
        data class SafeSignedNarrow(val from: Tag.Bits, val to: Tag.Bits) :
            Unary("signed_narrow_${to.bitwidth}_${from.bitwidth}", from, to) {
            init {
                check(from.bitwidth > to.bitwidth) { "Narrow($from, $to) does not actually narrow." }
            }
        }

        /**
         * Narrows an operand of [from] to [to]. Assumes that the argument, interpreted as unsigned value, is in range
         * of the resulting tag. The result of this operation are always the lower bits of the operand.
         */
        @KSerializable
        data class SafeUnsignedNarrow(val from: Tag.Bits, val to: Tag.Bits) :
            Unary("unsigned_narrow_${to.bitwidth}_${from.bitwidth}", from, to) {
            init {
                check(from.bitwidth > to.bitwidth) { "Narrow($from, $to) does not actually narrow." }
            }
        }
    }

    @KSerializable
    sealed class Binary(
        override val name: String,
        override val signature: FunctionSignature
    ) : NonSMTInterpretedFunctionSymbol() {
        constructor(name: String, paramSort: Tag, resultSort: Tag) :
            this(name, FixedFunctionSignatures(listOf(paramSort, paramSort), resultSort))

        /** TODO, see [LExpression.BinaryExp.AssignEqExp], should probably get rid of this case */
        @KSerializable
        data class AssignEq(val tag: Tag) : Binary(":=", tag, Tag.Bool)

        @KSerializable
        data class Sub(val tag: Tag.Bits) : Binary("-", tag, tag)

        @KSerializable
        data class Div(val tag: Tag.Bits) : Binary("/", tag, tag)

        @KSerializable
        data class Mod(val tag: Tag.Bits) : Binary("%", tag, tag)

        /**
         * Exponentiation with wrap-around semantics. (in contrast to [AxiomatizedFunctionSymbol.UninterpExp], which
         * by itself does not wrap around)
         */
        @KSerializable
        data class Exp(val tag: Tag.Bits) : Binary("**", tag, tag)

        @KSerializable
        data class ShiftLeft(val tag: Tag.Bits) : Binary("<<", tag, tag)

        @KSerializable
        data class ShiftRightLogical(val tag: Tag) : Binary(">>logic", tag, tag)

        @KSerializable
        data class ShiftRightArithmetical(val tag: Tag.Bits) : Binary(">>arith", tag, tag)

        @KSerializable
        object Lt : Binary("<", Tag.Int, Tag.Bool) {
            private fun readResolve(): Any = Lt
            override fun hashCode(): Int = hashObject(this)
        }

        @KSerializable
        object Gt : Binary(">", Tag.Int, Tag.Bool) {
            private fun readResolve(): Any = Gt
            override fun hashCode(): Int = hashObject(this)
        }

        @KSerializable
        object Le : Binary("<=", Tag.Int, Tag.Bool) {
            private fun readResolve(): Any = Le
            override fun hashCode(): Int = hashObject(this)
        }

        @KSerializable
        object Ge : Binary(">=", Tag.Int, Tag.Bool) {
            private fun readResolve(): Any = Ge
            override fun hashCode(): Int = hashObject(this)
        }

        @KSerializable
        data class Slt(val tag : Tag.Bits) : Binary("<_s", tag, Tag.Bool)

        @KSerializable
        data class Sle(val tag : Tag.Bits) : Binary("<=_s", tag, Tag.Bool)

        @KSerializable
        data class Sgt(val tag : Tag.Bits) : Binary(">_s", tag, Tag.Bool)

        @KSerializable
        data class Sge(val tag : Tag.Bits) : Binary(">=_s", tag, Tag.Bool)


        /** note this corresponds to the solidity bitwise and operator, not Smtlib's bvand */
        @KSerializable
        data class BitwiseAnd(val tag: Tag) : Binary("&", tag, tag)

        /** note this corresponds to the solidity bitwise or operator, not Smtlib's bvor */
        @KSerializable
        data class BitwiseOr(val tag: Tag) : Binary("|", tag, tag)

        /** note this corresponds to the solidity bitwise xor operator, not Smtlib's bvxor */
        @KSerializable
        data class BitwiseXor(val tag: Tag) : Binary("^", tag, tag)

        @KSerializable
        data class NoAddOverflow(val tag: Tag.Bits) : Binary("no_add_overflow", tag, Tag.Bool)

        @KSerializable
        data class NoMulOverflow(val tag : Tag.Bits) : Binary("no_mul_overflow", tag, Tag.Bool)

        @KSerializable
        data class NoSMulOverUnderflow(val tag : Tag.Bits) : Binary("no_smul_overflow", tag, Tag.Bool)

        @KSerializable
        data class NoSAddOverUnderflow(val tag : Tag.Bits) : Binary("no_sadd_underoverflow", tag, Tag.Bool)

        @KSerializable
        data class NoSSubOverUnderflow(val tag : Tag.Bits) : Binary("no_ssub_underoverflow", tag, Tag.Bool)

        @KSerializable
        data class Concat(val left: Tag.Bits, val right: Tag.Bits) : Binary(
            "concat",
            FixedFunctionSignatures(listOf(left, right), Tag.Bits(left.bitwidth + right.bitwidth))
        )
    }

    @KSerializable
    sealed class Vec(
        override val name: String,
        override val signature: FunctionSignature
    ) : NonSMTInterpretedFunctionSymbol() {

        @KSerializable
        data class Add(val tag: Tag.Bits) : Vec(
            "+",
            SingleVarargFunctionSignature(tag, tag, minParamCount = 2)
        )

        @KSerializable
        data class Mul(val tag: Tag.Bits) : Vec(
            "*",
            SingleVarargFunctionSignature(tag, tag, minParamCount = 2)
        )

    }

    /**
     * These correspond to TACBuiltin function symbols.
     * For the ones that correspond to the data type constructors when we compile to a *DT* theory, look into
     * [AxiomatizedFunctionSymbol.SKeyDt].
     */
    @KSerializable
    sealed class Hash(
        override val name: String,
        override val signature: FunctionSignature
    ) : NonSMTInterpretedFunctionSymbol() {
        @KSerializable
        object Basic : Hash("basic", IFixedFunctionSignatures.IntToSkey) {
            private fun readResolve(): Any = Basic
            override fun hashCode(): Int = hashObject(this)
        }

        @KSerializable
        object ToSkey : Hash("to_skey", IFixedFunctionSignatures.IntToSkey) {
            private fun readResolve(): Any = ToSkey
            override fun hashCode(): Int = hashObject(this)
        }

        @KSerializable
        object FromSkey : Hash("from_skey", IFixedFunctionSignatures.SkeyToInt) {
            private fun readResolve(): Any = FromSkey
            override fun hashCode(): Int = hashObject(this)
        }

        @KSerializable
        object SkeyAdd : Hash("skey_add", IFixedFunctionSignatures.SkeyIntToSkey) {
            private fun readResolve(): Any = SkeyAdd
            override fun hashCode(): Int = hashObject(this)
        }

        // TODO [CERT-1789]
        @KSerializable
        data class SimpleHashN(val arity: Int, val hashFamily: HashFamily) :
            Hash(
                "${hashFamily}__$arity",
                if (hashFamily.requiresLargeGaps) {
                    IFixedFunctionSignatures.SkeyNToSkey(arity)
                } else {
                    // choosing Int, not Bv256, is a bit dubious in the FunctionSignature context ..
                    //  -- regularly use int for "will be an int or bv depending on theory" ...
                    //  -- but in this context no bounds axioms are needed so it's fine for the time being I think
                    IFixedFunctionSignatures.IntNToInt(arity)
                }
            )
    }

    @KSerializable
    sealed class MultiDimArray(
        override val name: String,
        override val signature: FunctionSignature
    ) : NonSMTInterpretedFunctionSymbol() {
        abstract val mapType: Tag.GhostMap

        @KSerializable
        data class MultiSelect(override val mapType: Tag.GhostMap) :
            MultiDimArray("mult_slct", IFixedFunctionSignatures.multiSelectSignature(mapType))

        @KSerializable
        data class MultiStore(override val mapType: Tag.GhostMap) :
            MultiDimArray("mult_store", IFixedFunctionSignatures.multiStoreSignature(mapType))
    }

    @KSerializable
    sealed class Ternary(
        override val name: String,
        override val signature: FunctionSignature
    ) : NonSMTInterpretedFunctionSymbol() {

        @KSerializable
        object MulMod : Ternary(
            "mulmod",
            IFixedFunctionSignatures.IntIntIntToInt
        ) {
            private fun readResolve(): Any = MulMod
            override fun hashCode(): Int = hashObject(this)
        }

        @KSerializable
        object AddMod : Ternary(
            "addmod",
            IFixedFunctionSignatures.IntIntIntToInt
        ) {
            private fun readResolve(): Any = AddMod
            override fun hashCode(): Int = hashObject(this)
        }
    }
}


