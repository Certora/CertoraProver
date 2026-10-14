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
import smt.solverscript.LExpressionFactory
import smtlibutils.data.*
import tac.Tag
import tac.isSubtypeOf
import vc.data.ToSmtLibData
import java.lang.UnsupportedOperationException

/**
 * [InterpretedFunctionSymbol] that have a direct analog in an (some) [SmtIntpFunctionSymbol].
 * (Which doesn't mean that their analogue is necessarily the one we'll translate into --> e.g. IntMul coming from TAC
 *  may still be translated to BvMul, or uninterpMul etc..)
 * This is in contrast to [NonSMTInterpretedFunctionSymbol] which do not have a direct analogue in any SmtTheory.
 */
@utils.KSerializable
sealed class TheoryFunctionSymbol : InterpretedFunctionSymbol() {

    override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): SmtFunctionSymbol {
        return when (this) {
            is Array.Select -> SmtIntpFunctionSymbol.Array.Select(
                LExpressionFactory.tagToSort(this.indexSort, toSmtLibData),
                LExpressionFactory.tagToSort(this.valueSort, toSmtLibData)
            )
            is Array.Store -> SmtIntpFunctionSymbol.Array.Store(
                LExpressionFactory.tagToSort(this.indexSort, toSmtLibData),
                LExpressionFactory.tagToSort(this.valueSort, toSmtLibData)
            )
            Unary.LNot -> SmtIntpFunctionSymbol.Core.LNot
            Unary.Sub -> SmtIntpFunctionSymbol.IRA.Neg(Sort.IntSort)
            is BV.BvNot -> SmtIntpFunctionSymbol.BV.Un.BvNot(tag.bitwidth)
            is BV.BvNeg -> SmtIntpFunctionSymbol.BV.Un.BvNeg(tag.bitwidth)
            is BV.Concat -> SmtIntpFunctionSymbol.BV.Concat(left.bitwidth, right.bitwidth)
            is BV.Extract -> SmtIntpFunctionSymbol.BV.Extract(l, r)
            is BV.SignedPromote -> SmtIntpFunctionSymbol.BV.SignExtend(to.bitwidth - from.bitwidth)
            is BV.UnsignedPromote -> SmtIntpFunctionSymbol.BV.ZeroExtend(to.bitwidth - from.bitwidth)
            is BV.SignedNarrow -> SmtIntpFunctionSymbol.BV.Extract(to.bitwidth - 1, 0)
            is BV.UnsignedNarrow -> SmtIntpFunctionSymbol.BV.Extract(to.bitwidth - 1, 0)
            Binary.LImplies -> SmtIntpFunctionSymbol.Core.LImplies
            is Binary.IntDiv -> SmtIntpFunctionSymbol.Ints.Div
            Binary.IntSub -> SmtIntpFunctionSymbol.IRA.Sub(Sort.IntSort)
            is Binary.IntMod -> SmtIntpFunctionSymbol.Ints.Mod
            Binary.IntLt -> SmtIntpFunctionSymbol.IRA.Lt(Sort.IntSort)
            Binary.IntGt -> SmtIntpFunctionSymbol.IRA.Gt(Sort.IntSort)
            Binary.IntLe -> SmtIntpFunctionSymbol.IRA.Le(Sort.IntSort)
            Binary.IntGe -> SmtIntpFunctionSymbol.IRA.Ge(Sort.IntSort)
            is Binary.Eq -> SmtIntpFunctionSymbol.Core.Eq(
                LExpressionFactory.tagToSort(tag, toSmtLibData)
            )
            is BV.BvAnd -> SmtIntpFunctionSymbol.BV.BinOp.BvAnd(tag.bitwidth)
            is BV.BvOr -> SmtIntpFunctionSymbol.BV.BinOp.BvOr(tag.bitwidth)
            is BV.BvXOr -> SmtIntpFunctionSymbol.BV.BinOp.BvXOr(tag.bitwidth)
            is BV.BvAdd -> SmtIntpFunctionSymbol.BV.BinOp.BvAdd(tag.bitwidth)
            is BV.BvUDiv -> SmtIntpFunctionSymbol.BV.BinOp.BvUDiv(tag.bitwidth)
            is BV.BvSDiv -> SmtIntpFunctionSymbol.BV.BinOp.BvSDiv(tag.bitwidth)
            is BV.BvURem -> SmtIntpFunctionSymbol.BV.BinOp.BvURem(tag.bitwidth)
            is BV.BvSRem -> SmtIntpFunctionSymbol.BV.BinOp.BvSRem(tag.bitwidth)
            is BV.BvShl -> SmtIntpFunctionSymbol.BV.BinOp.BvShL(tag.bitwidth)
            is BV.BvLshr -> SmtIntpFunctionSymbol.BV.BinOp.BvLShr(tag.bitwidth)
            is BV.BvAshr -> SmtIntpFunctionSymbol.BV.BinOp.BvAShr(tag.bitwidth)
            is BV.BvULt -> SmtIntpFunctionSymbol.BV.Rel.BvULt(tag.bitwidth)
            is BV.BvULe -> SmtIntpFunctionSymbol.BV.Rel.BvULe(tag.bitwidth)
            is BV.BvUGt -> SmtIntpFunctionSymbol.BV.Rel.BvUGt(tag.bitwidth)
            is BV.BvUGe -> SmtIntpFunctionSymbol.BV.Rel.BvUGe(tag.bitwidth)
            is BV.BvSLt -> SmtIntpFunctionSymbol.BV.Rel.BvSLt(tag.bitwidth)
            is BV.BvSLe -> SmtIntpFunctionSymbol.BV.Rel.BvSLe(tag.bitwidth)
            is BV.BvSGt -> SmtIntpFunctionSymbol.BV.Rel.BvSGt(tag.bitwidth)
            is BV.BvSGe -> SmtIntpFunctionSymbol.BV.Rel.BvSGe(tag.bitwidth)
            is BV.BvMul -> SmtIntpFunctionSymbol.BV.BinOp.BvMul(tag.bitwidth)
            is Ternary.Ite -> SmtIntpFunctionSymbol.Core.Ite()
            Vec.LAnd -> SmtIntpFunctionSymbol.Core.LAnd
            Vec.LOr -> SmtIntpFunctionSymbol.Core.LOr
            Vec.IntAdd -> SmtIntpFunctionSymbol.IRA.Add(Sort.IntSort)
            is Vec.IntMul -> SmtIntpFunctionSymbol.IRA.Mul(Sort.IntSort)
            BV.BvNegO -> throw UnsupportedOperationException("not implemented -- unclear if bvnego is ever useful for us")
            is BV.BvUAddO -> SmtIntpFunctionSymbol.BV.Rel.BvUAddO(tag.bitwidth)
            is BV.BvSAddO -> SmtIntpFunctionSymbol.BV.Rel.BvSAddO(tag.bitwidth)
            is BV.BvUMulO -> SmtIntpFunctionSymbol.BV.Rel.BvUMulO(tag.bitwidth)
            is BV.BvSMulO -> SmtIntpFunctionSymbol.BV.Rel.BvSMulO(tag.bitwidth)
            is BV.BvUSubO -> SmtIntpFunctionSymbol.BV.Rel.BvUSubO(tag.bitwidth)
            is BV.BvSSubO -> SmtIntpFunctionSymbol.BV.Rel.BvSSubO(tag.bitwidth)
            is BV.BvSDivO -> SmtIntpFunctionSymbol.BV.Rel.BvSDivO(tag.bitwidth)
        }
    }

    @utils.KSerializable
    sealed class Array(
        override val name: String,
        override val signature: FunctionSignature
    ) : TheoryFunctionSymbol() {

        /**
         * @param storageKeySort The sort that the keys used to access storage have in the current translation -- this
         *    field is only relevant in the case when [mapSort] is [Tag.WordMap] (because only wordmaps are used to
         *    represent storage). In other cases it can be given as null.
         */
        @utils.KSerializable
        data class Select(val mapSort: Tag, val storageKeySort: Tag?) :
            Array(fsName, IFixedFunctionSignatures.selectSignature(mapSort, storageKeySort)) {

            val sig = (signature as IFixedFunctionSignatures)
            val indexSort: Tag = sig.paramSorts[1]
            val valueSort: Tag = sig.resultSort

            init {
                check(sig.paramSorts[0] == mapSort)
                { "inconsistent sorts in $this" }
                check(mapSort == Tag.WordMap || mapSort == Tag.ByteMap || mapSort is Tag.GhostMap)
                { "unexpected map sort: $mapSort" }
                check(storageKeySort == null ||
                        storageKeySort.isSubtypeOf(Tag.Int))
                { "unexpected storage key sort: $storageKeySort" }
                check(mapSort != Tag.WordMap || storageKeySort != null)
                { "must give a storage key sort for a wordmap-select" }
            }

            override fun toString(): String = name

            companion object {
                const val fsName = "select"
            }
       }

        @utils.KSerializable
        data class Store(val mapSort: Tag, val storageKeySort: Tag?) :
            Array(fsName, IFixedFunctionSignatures.storeSignature(mapSort, storageKeySort)) {

            val sig = (signature as IFixedFunctionSignatures)
            val indexSort: Tag = sig.paramSorts[1]
            val valueSort: Tag = sig.paramSorts[2]

            init {
                check(sig.paramSorts[0] == mapSort && sig.resultSort == mapSort)
                { "inconsistent sorts in $this" }
                check(mapSort == Tag.WordMap || mapSort == Tag.ByteMap || mapSort is Tag.GhostMap)
                { "unexpected map sort: $mapSort" }
                check(storageKeySort == null || storageKeySort.isSubtypeOf(Tag.Int))
                { "unexpected storage key sort: $storageKeySort" }
                check(mapSort != Tag.WordMap || storageKeySort != null)
                { "must give a storage key sort for a wordmap-select" }
            }

            override fun toString(): String = name

           companion object {
                const val fsName = "store"
           }
        }
    }

    /**
     * Note that these function symbols never come from TAC -- they're only introduced by ExpNormalizer BV as the
     * direct counterparts of the corresponding SmtLib symbols.
     */
    @utils.KSerializable
    sealed class BV(
        override val name: String,
        override val signature: FunctionSignature
    ): TheoryFunctionSymbol() {
        @utils.KSerializable
        data class BvAnd(val tag: Tag.Bits) :
            BV("bvand", IFixedFunctionSignatures.binaryBVOperator(tag))

        @utils.KSerializable
        data class BvOr(val tag: Tag.Bits) :
            BV("bvor", IFixedFunctionSignatures.binaryBVOperator(tag))

        @utils.KSerializable
        data class BvXOr(val tag: Tag.Bits) :
            BV("bvxor", IFixedFunctionSignatures.binaryBVOperator(tag))

        @utils.KSerializable
        data class BvAdd(val tag: Tag.Bits) :
            BV("bvadd", IFixedFunctionSignatures.binaryBVOperator(tag))

        @utils.KSerializable
        data class BvMul(val tag: Tag.Bits) :
            BV("bvmul", IFixedFunctionSignatures.binaryBVOperator(tag))

        /** unsigned division */
        @utils.KSerializable
        data class BvUDiv(val tag: Tag.Bits) :
            BV("bvudiv", IFixedFunctionSignatures.binaryBVOperator(tag))

        /** signed division */
        @utils.KSerializable
        data class BvSDiv(val tag: Tag.Bits) :
            BV("bvsdif", IFixedFunctionSignatures.binaryBVOperator(tag))

        /** unsigned remainder */
        @utils.KSerializable
        data class BvURem(val tag: Tag.Bits) :
            BV("bvurem", IFixedFunctionSignatures.binaryBVOperator(tag))

        /** signed remainder */
        @utils.KSerializable
        data class BvSRem(val tag: Tag.Bits) :
            BV("bvsrem", IFixedFunctionSignatures.binaryBVOperator(tag))

        /** shift left */
        @utils.KSerializable
        data class BvShl(val tag: Tag.Bits) :
            BV("bvshl", IFixedFunctionSignatures.binaryBVOperator(tag))

        /** logical (?) shift right */
        @utils.KSerializable
        data class BvLshr(val tag: Tag.Bits) :
            BV("bvlshr", IFixedFunctionSignatures.binaryBVOperator(tag))

        /** Arithmetical (?) shift right */
        @utils.KSerializable
        data class BvAshr(val tag: Tag.Bits) :
            BV("bvashr", IFixedFunctionSignatures.binaryBVOperator(tag))

        /** lower than (bitwise comparison operator) */
        @utils.KSerializable
        data class BvULt(val tag: Tag.Bits) :
            BV("bvult", IFixedFunctionSignatures.binaryBVRelation(tag))

        /** lower than or equal (bitwise comparison operator) */
        @utils.KSerializable
        data class BvULe(val tag: Tag.Bits) :
            BV("bvule", IFixedFunctionSignatures.binaryBVRelation(tag))

        /** greater than (bitwise comparison operator) */
        @utils.KSerializable
        data class BvUGt(val tag: Tag.Bits) :
            BV("bvugt", IFixedFunctionSignatures.binaryBVRelation(tag))

        /** greater than or equal (bitwise comparison operator) */
        @utils.KSerializable
        data class BvUGe(val tag: Tag.Bits) :
            BV("bvuge", IFixedFunctionSignatures.binaryBVRelation(tag))

        /** lower than (bitwise comparison operator) */
        @utils.KSerializable
        data class BvSLt(val tag: Tag.Bits) :
            BV("bvslt", IFixedFunctionSignatures.binaryBVRelation(tag))

        /** lower than or equal (bitwise comparison operator) */
        @utils.KSerializable
        data class BvSLe(val tag: Tag.Bits) :
            BV("bvsle", IFixedFunctionSignatures.binaryBVRelation(tag))

        /** greater than (bitwise comparison operator) */
        @utils.KSerializable
        data class BvSGt(val tag: Tag.Bits) :
            BV("bvsgt", IFixedFunctionSignatures.binaryBVRelation(tag))

        /** greater than or equal (bitwise comparison operator) */
        @utils.KSerializable
        data class BvSGe(val tag: Tag.Bits) :
            BV("bvsge", IFixedFunctionSignatures.binaryBVRelation(tag))

        /* Overflow symbols that were added to SmtLib in 2022 or 2023. (see https://smt-lib.org/theories-FixedSizeBitVectors.shtml) */

        interface OverUnderflowCheck // grouping interface

        @utils.KSerializable
        @Suppress("Unused")
        object BvNegO : OverUnderflowCheck, BV(
                "bvnego",
                IFixedFunctionSignatures.Bv256ToBool
            ) {
            private fun readResolve(): Any = BvNegO
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        data class BvUAddO(val tag: Tag.Bits) :
            BV("bvuaddo", IFixedFunctionSignatures.binaryBVRelation(tag))

        @utils.KSerializable
        data class BvSAddO(val tag: Tag.Bits) :
            BV("bvsaddo", IFixedFunctionSignatures.binaryBVRelation(tag))

        @utils.KSerializable
        data class BvUMulO(val tag: Tag.Bits) :
            BV("bvumulo", IFixedFunctionSignatures.binaryBVRelation(tag))


        @utils.KSerializable
        data class BvSMulO(val tag: Tag.Bits) :
            BV("bvsmulo", IFixedFunctionSignatures.binaryBVRelation(tag))

        /* overflow check functions that CVC5 (and who else?) supports in addition to the ones that are in the
           standard (as of Oct 2024) */

        @utils.KSerializable
        @Suppress("Unused")
        data class BvUSubO(val tag: Tag.Bits) :
            BV("bvusubo", IFixedFunctionSignatures.binaryBVRelation(tag))


        @utils.KSerializable
        @Suppress("Unused")
        data class BvSSubO(val tag: Tag.Bits) :
            BV("bvssubo", IFixedFunctionSignatures.binaryBVRelation(tag))

        @utils.KSerializable
        @Suppress("Unused")
        data class BvSDivO(val tag: Tag.Bits) :
            BV("bvsdivo", IFixedFunctionSignatures.binaryBVRelation(tag))

        /* other bv operations */

        /** This corresponds to the smt lib symbol; only used by ExpNormalizerBv -- in contrast to
         * [NonSMTInterpretedFunctionSymbol.Unary.BitwiseNot], which represents TAC's bitwise-not operation */
        @utils.KSerializable
        data class BvNot(val tag: Tag.Bits) :
            BV("bvnot", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(tag), tag))

        /**
         * Defined as `[[(bvneg s)]] := nat2bv[m](2^m - bv2nat([[s]]))`.
         * See also [http://smtlib.cs.uiowa.edu/theories-FixedSizeBitVectors.shtml].
         * Note this is different from bvnot.
         * */
        @utils.KSerializable
        data class BvNeg(val tag: Tag.Bits) :
            BV("bvneg", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(tag), tag))

        @utils.KSerializable
        data class Concat(val left: Tag.Bits, val right: Tag.Bits) :
            BV("concat", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(left, right), Tag.Bits(left.bitwidth + right.bitwidth)))

        @utils.KSerializable
        data class Extract(val l: Int, val r: Int, val paramSort : Tag.Bits, val resultSort : Tag.Bits) :
            BV("extract", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(paramSort), resultSort)) {
            init {
                check(r >= 0 && l <= paramSort.bitwidth && l - r + 1 == resultSort.bitwidth) {
                    "Bad parameters for extract: l = $l, r = $r, paramSort = $paramSort, resultSort = $resultSort"
                }
            }
        }

        /** Sign-extend from [from] to [to], assuming the value is signed (in 2s complement) */
        @utils.KSerializable
        data class SignedPromote(val from: Tag.Bits, val to: Tag.Bits) :
            BV("signed_promote_${to.bitwidth}", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(from), to)) {
            init {
                check(from.bitwidth < to.bitwidth) { "SignedPromote($from, $to) does not actually promote." }
            }
        }
        /** Zero-extend from [from] to [to], assuming the value is unsigned */
        @utils.KSerializable
        data class UnsignedPromote(val from: Tag.Bits, val to: Tag.Bits) :
            BV("unsigned_promote_${to.bitwidth}", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(from), to)) {
            init {
                check(from.bitwidth < to.bitwidth) { "UnsignedPromote($from, $to) does not actually promote." }
            }
        }
        /** Signed narrow to [i] bits */
        @utils.KSerializable
        data class SignedNarrow(val from: Tag.Bits, val to: Tag.Bits) :
            BV("signed_narrow_${to.bitwidth}", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(from), to)) {
            init {
                check(from.bitwidth > to.bitwidth) { "Narrow($from, $to) does not actually narrow." }
            }
        }
        /** Unsigned narrow to [i] bits */
        @utils.KSerializable
        data class UnsignedNarrow(val from: Tag.Bits, val to: Tag.Bits) :
            BV("unsigned_narrow_${to.bitwidth}", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(from), to)) {
            init {
                check(from.bitwidth > to.bitwidth) { "Narrow($from, $to) does not actually narrow." }
            }
        }
        companion object {
            val BvConcatTwo256s = Concat(Tag.Bit256, Tag.Bit256)
            val BvExtractLower256From512 get() = Extract(255, 0, Tag.Bit512, Tag.Bit256)
        }
    }

    @utils.KSerializable
    sealed class Unary(
        override val name: String,
        override val signature: FunctionSignature
    ) : TheoryFunctionSymbol() {


        @utils.KSerializable
        object LNot : Unary(
            "not",
            IFixedFunctionSignatures.BoolToBool
        ) {
            private fun readResolve(): Any = LNot
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        object Sub : Unary(
            "-",
            IFixedFunctionSignatures.IntToBool
        ) {
            private fun readResolve(): Any = Sub
            override fun hashCode(): Int = utils.hashObject(this)
        }
    }


    @utils.KSerializable
    sealed class Binary(
        override val name: String,
        override val signature: FunctionSignature
    ) : TheoryFunctionSymbol() {

        @utils.KSerializable
        object LImplies :
            Binary(
                "=>",
                IFixedFunctionSignatures.BoolBoolToBool
            ) {
            private fun readResolve(): Any = LImplies
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        sealed class IntDiv : Binary(
            "/",
            IFixedFunctionSignatures.IntIntToInt
        ) {
            /**
             * Will turn to uninterp_div eventually if more than one argument is non-constant.
             */
            @utils.KSerializable
            object IntDivAllowNormalize : IntDiv() {
                private fun readResolve(): Any = IntDivAllowNormalize
                override fun hashCode(): Int = utils.hashObject(this)
            }

            /**
             *  Used when don't want to it to turn to uninterp_div in [ExpNormalizer]
             */
            @utils.KSerializable
            object IntDivDontNormalize : IntDiv() {
                private fun readResolve(): Any = IntDivDontNormalize
                override fun hashCode(): Int = utils.hashObject(this)
            }
        }

        @utils.KSerializable
        object IntSub : Binary("-", IFixedFunctionSignatures.IntIntToInt) {
            private fun readResolve(): Any = IntSub
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        sealed class IntMod : Binary("%", IFixedFunctionSignatures.IntIntToInt) {
            /**
             * Will turn to uninterp_mod eventually if more than one argument is non-constant.
             */
            @utils.KSerializable
            object IntModAllowNormalize : IntMod() {
                private fun readResolve(): Any = IntModAllowNormalize
                override fun hashCode(): Int = utils.hashObject(this)
            }

            /**
             *  Used when don't want to it to turn to uninterp_mul in [ExpNormalizer]
             */
            @utils.KSerializable
            object IntModDontNormalize : IntMod() {
                private fun readResolve(): Any = IntModDontNormalize
                override fun hashCode(): Int = utils.hashObject(this)
            }
        }

        @utils.KSerializable
        object IntLt : Binary("<", IFixedFunctionSignatures.IntIntToBool) {
            private fun readResolve(): Any = IntLt
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        object IntGt : Binary(
            ">",
            IFixedFunctionSignatures.IntIntToBool
        ) {
            private fun readResolve(): Any = IntGt
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        object IntLe : Binary(
            "<=",
            IFixedFunctionSignatures.IntIntToBool
        ) {
            private fun readResolve(): Any = IntLe
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        object IntGe : Binary(
            ">=",
            IFixedFunctionSignatures.IntIntToBool
        ) {
            private fun readResolve(): Any = IntGe
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        data class Eq(val tag: Tag) : Binary("=", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(tag, tag), Tag.Bool))
    }

    @utils.KSerializable
    sealed class Ternary(
        override val name: String,
        override val signature: FunctionSignature
    ) : TheoryFunctionSymbol() {

        @utils.KSerializable
        data class Ite(val tag: Tag) :
            Ternary("ite", IFixedFunctionSignatures.FixedFunctionSignatures(listOf(Tag.Bool, tag, tag), tag))
    }

    @utils.KSerializable
    sealed class Vec(
        override val name: String,
        override val signature: FunctionSignature
    ) : TheoryFunctionSymbol() {

        @utils.KSerializable
        object LAnd : Vec(
            "and",
            SingleVarargFunctionSignature(Tag.Bool, Tag.Bool, minParamCount = 2)
        ) {
            private fun readResolve(): Any = LAnd
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        object LOr : Vec(
            "or",
            SingleVarargFunctionSignature(Tag.Bool, Tag.Bool, minParamCount = 2)
        ) {
            private fun readResolve(): Any = LOr
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        object IntAdd : Vec(
            "+",
            SingleVarargFunctionSignature(Tag.Int, Tag.Int)
        )  {
            private fun readResolve(): Any = IntAdd
            override fun hashCode(): Int = utils.hashObject(this)
        }

        @utils.KSerializable
        sealed class IntMul : Vec(
            "*",
            SingleVarargFunctionSignature(Tag.Int, Tag.Int)
        ) {
            /**
             * Will turn to uninterp_mul eventually if more than one argument is non-constant.
             */
            @utils.KSerializable
            object IntMulAllowNormalize : IntMul() {
                private fun readResolve(): Any = IntMulAllowNormalize
                override fun hashCode(): Int = utils.hashObject(this)
            }

            /**
             *  Used when don't want to it to turn to uninterp_mul in [ExpNormalizer]
             */
            @utils.KSerializable
            object IntMulDontNormalize : IntMul() {
                private fun readResolve(): Any = IntMulDontNormalize
                override fun hashCode(): Int = utils.hashObject(this)
            }
        }
    }
}
