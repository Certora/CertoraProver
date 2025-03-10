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
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGeneratorLegacy
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.IFixedFunctionSignatures.Companion.IntNToInt
import smt.solverscript.functionsymbols.IFixedFunctionSignatures.FixedFunctionSignatures
import smtlibutils.data.ISmtScript
import smtlibutils.data.SmtFunctionSymbol
import smtlibutils.data.SmtUnintpFunctionSymbol
import tac.Tag
import tac.isMapType
import utils.KSerializable
import utils.hash
import vc.data.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import java.util.*

@KSerializable
sealed class UninterpretedFunctionSymbol : FunctionSymbol() {
    override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): SmtFunctionSymbol {
        val rank = signature.toSMTLib(toSmtLibData, script)
        return SmtUnintpFunctionSymbol(script.simpleIdentifier(name), rank)
    }
}

@KSerializable
data class ConstantSymbol(override val name: String, val tag: Tag) : UninterpretedFunctionSymbol() {
    override val signature: FunctionSignature = FixedFunctionSignatures(listOf(), tag)
}

@KSerializable
data class UserDefinedFunctionSymbol(
    override val name: String,
    override val signature: FixedFunctionSignatures,
    val attribute: UFAttribute = UFAttribute.None
) : UninterpretedFunctionSymbol() {
    override fun hashCode(): Int = hash { it + name + signature + attribute }
}

/**
 * Note this is different from [NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiSelect], this one is used to model arrays using
 * uninterpreted functions, the other one belongs to smt's array theory.
 *
 * There are separate variants of this, one for one-dimensional and one for more-than-one-dimensional arrays.
 *
 * Note that [OneDim] and [MultiDim] fit the description of an [AxiomatizedFunctionSymbol] but we manage them
 * separately.
 *
 */
@KSerializable
sealed class ArraySelectFunctionSymbol : UninterpretedFunctionSymbol() {
    abstract val array: LExpression.Identifier
    abstract override val signature: FixedFunctionSignatures

    companion object {
        /** This constructor helpe is for the case when [map]'s tag might not have the exact right signature
         * (e.g. due to us flipping types to/from * skey during the LExpressionToSmtLib translation), thus we pass the
         * required signature. */
        operator fun invoke(
            map: LExpression.Identifier,
            signature: FixedFunctionSignatures,
        ): ArraySelectFunctionSymbol {
            return when (val nr = signature.nrOfParameters) {
                is NrOfParameters.Fixed -> when (nr.n) {
                    0 -> error("signature of a map must have at least one parameter (map: $map)")
                    1 -> OneDim(map, signature)
                    else -> MultiDim(map, signature)
                }
                is NrOfParameters.Flexible -> error("signature of a map can't have a flexible nr of params (map: $map)")
            }
        }
    }

    @KSerializable
    data class OneDim(
        override val array: LExpression.Identifier,
        override val signature: FixedFunctionSignatures,
    ) :
        ArraySelectFunctionSymbol() {
        override val name: String = "slct_${array.id}"

        override fun toString(): String = super.toString()

        init {
            check(array.tag.isMapType())
        }
    }

    @KSerializable
    data class MultiDim(
        override val array: LExpression.Identifier,
        override val signature: FixedFunctionSignatures,
    ) : ArraySelectFunctionSymbol() {
        override val name: String = "mslct_${array.id}"

        init {
            check(array.tag is Tag.GhostMap)
            { "unexpected type : ${array.tag} in $array" }
            check(array.tag.paramSorts.size > 1)
            { "need more than 1 argument for a Multi-Select ($array with type ${array.tag})" }
        }

        override fun toString(): String = super.toString()
    }

}


/**
 * Function symbols that we introduce (and have to declare in the smt script) and provide with a meaning through axioms.
 *
 * Note that [ArraySelectFunctionSymbol.OneDim] fits the description of an [AxiomatizedFunctionSymbol] but we manage them separately.
 *
 * TODO: there are remainders of the multi-override pattern here; [AxiomatizedFunctionSymbol] should not override [name]
 *  and [signature], but instead there should be a subclass grouping its direct descendants that then overrides those
 *  fields.
 */
@KSerializable
sealed class AxiomatizedFunctionSymbol : UninterpretedFunctionSymbol() {

    companion object {
        fun isHashFunctionSymbol(fs: FunctionSymbol): Boolean = fs is Hash
    }

    /**
     * These are the symbols that directly correspond to datatype constructors.
     * For the function symbols that correspond to the TACBuiltin functions, look at
     * [NonSMTInterpretedFunctionSymbol.Hash].
     */
    @KSerializable
    sealed class SKeyDt(
        override val name: String,
        override val signature: FunctionSignature
    ) : AxiomatizedFunctionSymbol() {

        @KSerializable
        data class Basic(val tag: Tag):
            SKeyDt("basic_$tag", FixedFunctionSignatures(listOf(tag), skeySort))

        @KSerializable
        data class SkeyNode(val arity: Int) :
            SKeyDt("$namePrefix$arity", IFixedFunctionSignatures.SkeyNIntToSkey(arity)) {
            override fun toString(): String = super.toString() // needed due to data class

            companion object {
                const val namePrefix = "simple_hash_"
            }
        }

        /** In contrast to the others, the following are not a datatype constructors, but will be functions (with a
         * define-fun, or possibly axiomatized). */
        @KSerializable
        data class SkeyAdd(val tag: Tag) :
            SKeyDt("skey_add_$tag", FixedFunctionSignatures(listOf(skeySort, tag), skeySort))

        @KSerializable
        data class ToSkey(val tag: Tag):
            SKeyDt("to_skey_dt_$tag", FixedFunctionSignatures(listOf(tag), skeySort))

        @KSerializable
        data class FromSkey(val tag: Tag):
            SKeyDt("from_skey_dt_$tag", FixedFunctionSignatures(listOf(skeySort), tag))

        /** the skey datatype selectors */
        @KSerializable
        data class SKeySelector(val constructor: SKeyDt, val index: Int, val tag: Tag) :
            SKeyDt(
                "${constructor.name}$delim$index",
                FixedFunctionSignatures(// TODO not really userdefined, this one ..
                    listOf(tag),
                    constructor.signature.getParamSort(index)!!
                )
            ) {
            override fun toString(): String = super.toString() // needed due to data class

            companion object {
                const val delim = "_sel_"
            }
        }
    }

    /**
     * These are used in the non datatype-based [HashingScheme]s.
     */
    @KSerializable
    sealed class Hash : AxiomatizedFunctionSymbol() {
        interface WithArity {
            val arity: Int
        }

        @KSerializable
        data class SimpleHashN(val tag: Tag, override val arity: Int, val hashFamily: HashFamily) : Hash(),
            WithArity {
            override val name: String = "${hashFamily}_${arity}_$tag"
            override val signature: FunctionSignature =
                FixedFunctionSignatures(List(arity) { tag }, tag)
            override fun toString(): String = super.toString()
        }

        /** used in legacy hashing only */
        @KSerializable
        object ToSkey : Hash() {
            override val name: String = "to_skey"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntToInt
            private fun readResolve(): Any = ToSkey
        }


        @KSerializable
        object FromSkey : Hash() {
            override val name: String = "from_skey"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntToInt
            private fun readResolve(): Any = FromSkey
        }

        /** Auxiliary function symbols for setting up legacy hashing. See [StorageHashAxiomGeneratorLegacy]. */
        @KSerializable
        class ArityDisambig private constructor(
            override val name: String,
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntToInt
        ) : Hash() {
            constructor(fsGroup: Any): this("arity_disambig_$fsGroup")
        }

        @KSerializable
        object PIBase : Hash() {
            override val name: String = "pi_base"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntToInt
            private fun readResolve(): Any = PIBase
        }

        @KSerializable
        object PIIsLargeConstant : Hash() {
            override val name: String = "pi_isLargeConstant"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntToBool
            private fun readResolve(): Any = PIIsLargeConstant
        }

        @KSerializable
        class PointwiseInversePerHashFamily private constructor(
            override val name: String,
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntToInt
        ) : Hash() {
            constructor(index: Int, hashFamily: HashFamily): this("inverse_${index}_$hashFamily")
        }

        /** Auxiliary function symbols for setting up legacy hashing. See [StorageHashAxiomGeneratorLegacy]. */
        @KSerializable
        data class SimpleHashNPre(override val arity: Int) : Hash(), WithArity {
            override val name: String = "simple_hash_pre_$arity"
            override val signature: FunctionSignature = IntNToInt(arity)
            override fun toString(): String = super.toString()
        }
    }

    @KSerializable
    sealed class Bitwise : AxiomatizedFunctionSymbol() {
        @Suppress("Unused")
        @KSerializable
        data class ZeroExtend(val i: Int) : Bitwise() {
            override val name: String = "<<_ext$i"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntToInt
            override fun toString(): String = super.toString()
        }

        @KSerializable
        /**
         * Check for [TACExpr.BinOp.SignExtend] for exact semantics.
         * Extends from (i+1)*8 to 256 bits, but operates on 256 bits.
         */
        data class SignExtend(val i: Int, val tag: Tag) : Bitwise() {
            override val name: String = ">>_ext${i}_$tag"
            override val signature: FunctionSignature = FixedFunctionSignatures(listOf(tag), tag)
            override fun toString(): String = super.toString()
        }

        // only static objects from here on
        @KSerializable
        data class BinarySignExtend(val tag: Tag) : Bitwise() {
            override val name: String = "uninterp_signextend"
            override val signature: FunctionSignature = FixedFunctionSignatures(listOf(tag, tag), tag)
            override fun toString(): String = super.toString()
        }

        @KSerializable
        object UninterpBwAnd : Bitwise(
        ) {
            override val name: String = "uninterp_bwand"

            override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
            private fun readResolve(): Any = UninterpBwAnd
        }

        @KSerializable
        object UninterpBwOr : Bitwise() {
            override val name: String = "uninterp_bwor"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt

            private fun readResolve(): Any = UninterpBwOr
        }

        @KSerializable
        object UninterpBwXOr : Bitwise() {
            override val name: String = "uninterp_bwxor"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
            private fun readResolve(): Any = UninterpBwXOr
        }

        @KSerializable
        object UninterpShiftLeft : Bitwise(
        ) {
            override val name: String = "uninterp_bwshl"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
            private fun readResolve(): Any = UninterpShiftLeft
        }

        @KSerializable
        object UninterpShiftRightLogical : Bitwise() {
            override val name: String = "uninterp_bwlshr"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
            private fun readResolve(): Any = UninterpShiftRightLogical
        }

        @KSerializable
        object UninterpShiftRightArithmetical : Bitwise() {
            override val name: String = "uninterp_bwashr"
            override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
            private fun readResolve(): Any = UninterpShiftRightArithmetical
        }

        @Suppress("Unused")
        @KSerializable
        object UnpackSubsequence : Bitwise() {
            override val name: String = "unpack_subsequence"
            override val signature: FunctionSignature =
                IntNToInt(3) // (symbol len offset) -> (bits [offset,(offset + len - 1)])

            private fun readResolve(): Any = UnpackSubsequence
        }
    }


    @KSerializable
    sealed class BvOverflowChecks : AxiomatizedFunctionSymbol() {
        abstract val tag: Tag.Bits
        @KSerializable
        data class NoUMulOverflow(override val tag: Tag.Bits) : BvOverflowChecks() {
            override val name: String = "certora_bvumul_noovfl_$tag"
            override val signature: FunctionSignature = FixedFunctionSignatures(listOf(tag, tag), Tag.Bool)
        }

        @KSerializable
        data class NoSMulOverUnderflow(override val tag: Tag.Bits) : BvOverflowChecks() {
            override val name: String = "certora_bvsmul_noovudfl_$tag"
            override val signature: FunctionSignature = FixedFunctionSignatures(listOf(tag, tag), Tag.Bool)
        }

        @KSerializable
        data class NoSAddOverUnderflow(override val tag: Tag.Bits) : BvOverflowChecks() {
            override val name: String = "certora_bvsadd_noovudfl_$tag"
            override val signature: FunctionSignature = FixedFunctionSignatures(listOf(tag, tag), Tag.Bool)
        }

        @KSerializable
        data class NoSSubOverUnderflow(override val tag: Tag.Bits) : BvOverflowChecks() {
            override val name: String = "certora_bvssub_noovudfl_$tag"
            override val signature: FunctionSignature = FixedFunctionSignatures(listOf(tag, tag), Tag.Bool)
        }
    }

    @KSerializable
    object UninterpMod : AxiomatizedFunctionSymbol() {
        override val name: String = "uninterp_mod"
        override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
        private fun readResolve(): Any = UninterpMod
    }

    @KSerializable
    object UninterpSMod : AxiomatizedFunctionSymbol() {
        override val name: String = "uninterp_smod"
        override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
        private fun readResolve(): Any = UninterpSMod
    }

    @KSerializable
    data class UninterpTagMod(val tag : Tag.Bits) : AxiomatizedFunctionSymbol() {
        override val name: String = "uninterp_mod_${tag.bitwidth}"
        override val signature: FunctionSignature = IFixedFunctionSignatures.IntToInt
    }

    @KSerializable
    object UninterpDiv : AxiomatizedFunctionSymbol() {
        override val name: String = "uninterp_div"
        override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
        private fun readResolve(): Any = UninterpDiv
    }

    @KSerializable
    object UninterpMul : AxiomatizedFunctionSymbol() {
        override val name: String = "uninterp_mul"
        override val signature: FunctionSignature = IFixedFunctionSignatures.IntIntToInt
        private fun readResolve(): Any = UninterpMul
    }

    @KSerializable
    data class UninterpExp(val tag: Tag = Tag.Int) : AxiomatizedFunctionSymbol() {
        override val name: String = "uninterp_exp_$tag"
        override val signature: FunctionSignature = FixedFunctionSignatures(listOf(tag, tag), tag)
        override fun toString(): String = super.toString()
    }

    @KSerializable
    data class SimpleAddModulo(val tag : Tag.Bits) : AxiomatizedFunctionSymbol() {
        override val name: String = "simple_add_modulo"
        override val signature: FunctionSignature = FixedFunctionSignatures(listOf(Tag.Int), tag)
    }

    @KSerializable
    data class SimpleSubModulo(val tag: Tag.Bits) : AxiomatizedFunctionSymbol() {
        override val name: String = "simple_sub_modulo"
        override val signature: FunctionSignature = FixedFunctionSignatures(listOf(Tag.Int), tag)
    }

    @KSerializable
    data class MapDefinition(val mapSort: Tag.Map) : AxiomatizedFunctionSymbol() {
        override val name: String = "map_definition_$mapSort"
        override val signature: FunctionSignature =
            IFixedFunctionSignatures.MapDefinitionSignature(mapSort)

        override fun toString() = super.toString()
    }

    @KSerializable
    object LongStore : AxiomatizedFunctionSymbol() {
        override val name: String = "longstore"
        override val signature: FunctionSignature = IFixedFunctionSignatures.LongStoreSignature
        private fun readResolve(): Any = LongStore
    }

    /**
     * Represents a (to be) defined function.
     * We construct the [name] a bit awkwardly to allow different instances of [DefFunc] with different names based
     * on properties that are not visible in [DefFunc] itself. Our current use case are functions that are "overloaded"
     * with different tags, created via [DefFunc.copy]. It is ugly to update the [name] directly when doing this, but
     * it is easy to update provide a new [nameBuilder] which simply appends another tag to the name.
     */
    @KSerializable
    data class DefFunc(
        override val name: String,
        override val signature: FixedFunctionSignatures,
    ) : AxiomatizedFunctionSymbol() {
        override fun toString() = super.toString()
    }


    /** Having a not reference-based equals is important here because [LExpressionFactory] needs to identify symbols
     * in order to manage registrations correctly.  */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AxiomatizedFunctionSymbol
        return name == other.name && signature == other.signature
    }

    override fun hashCode(): Int = Objects.hash(name, signature)
}
