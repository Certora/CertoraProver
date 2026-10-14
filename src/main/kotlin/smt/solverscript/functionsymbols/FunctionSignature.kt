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

@file:Suppress("Unused")

package smt.solverscript.functionsymbols

import com.certora.collect.*
import datastructures.stdcollections.*
import smt.solverscript.LExpressionFactory
import smtlibutils.data.ISmtScript
import smtlibutils.data.Rank
import tac.Tag
import utils.*
import vc.data.ToSmtLibData

sealed class NrOfParameters {
    data class Fixed(val n: Int) : NrOfParameters()
    data class Flexible(val minParamCount: Int) : NrOfParameters()
}

/**
 */
@KSerializable
@Treapable
abstract class FunctionSignature: AmbiSerializable  {
    abstract val nrOfParameters: NrOfParameters
    abstract val resultSort: Tag

    /**  all the non-composite sorts occuring in this signature;
     * this is used for registering uninterpreted sorts when they are used in a signature */
    abstract val allOccurringSorts: Sequence<Tag>

    /**
     * @return sort of param #[index] (0-based), null if there are fewer that [index] - 1 parameters
     */
    abstract fun getParamSort(index: Int): Tag?

    abstract fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): Rank

    /** The arity of the signature, null for a vararg signature. */
    val arity: Int?
        get() = (nrOfParameters as? NrOfParameters.Fixed)?.n
}

@KSerializable
data class SingleVarargFunctionSignature(val paramSort: Tag, override val resultSort: Tag, val minParamCount: Int = 0) :
    FunctionSignature() {
    override val nrOfParameters: NrOfParameters
        get() = NrOfParameters.Flexible(minParamCount)

    override val allOccurringSorts: Sequence<Tag>
        get() = sequenceOf(paramSort, resultSort)

    override fun getParamSort(index: Int): Tag = paramSort

    override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): Rank {
        throw UnsupportedOperationException("not implemented (this = $this)")
    }
}

@KSerializable
sealed class IFixedFunctionSignatures : FunctionSignature() {
    abstract val paramSorts: List<Tag>
    abstract override val resultSort: Tag

    override val nrOfParameters: NrOfParameters
        get() = NrOfParameters.Fixed(paramSorts.size)

    override val allOccurringSorts: Sequence<Tag>
        get() = paramSorts.asSequence() + resultSort

    override fun getParamSort(index: Int): Tag? = paramSorts.getOrNull(index)

    override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): Rank {
        return Rank(
            paramSorts.map { LExpressionFactory.tagToSort(it, toSmtLibData) },
            LExpressionFactory.tagToSort(resultSort, toSmtLibData)
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is IFixedFunctionSignatures) {
            return false
        }
        return javaClass == other.javaClass &&
            resultSort == other.resultSort &&
            paramSorts == other.paramSorts
    }

    override fun hashCode(): Int = hash { it + javaClass + paramSorts + resultSort }

    @KSerializable
    data class FixedFunctionSignatures(
        override val paramSorts: List<Tag>,
        override val resultSort: Tag
    ) : IFixedFunctionSignatures() {
        fun transform(f: (Tag) -> Tag) = copy(paramSorts = paramSorts.map(f), resultSort = f(resultSort))
    }

    @KSerializable
    data class MapDefinitionSignature(private val mapSort: Tag.Map) : IFixedFunctionSignatures() {
        override val resultSort: Tag
            get() = mapSort

        override val nrOfParameters: NrOfParameters
            get() = NrOfParameters.Fixed(mapSort.getMapDimension() + 1)

        override val allOccurringSorts: Sequence<Tag>
            get() = mapSort.nestedBasicTagsFlat

        override fun getParamSort(index: Int): Tag? =
            when {
                index < mapSort.getMapDimension() -> mapSort.paramSorts[index]
                index == mapSort.getMapDimension() -> mapSort.resultSort
                else -> null
            }

        override val paramSorts: List<Tag>
            get() = mapSort.paramSorts

        override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): Rank =
            Rank(
                (0..mapSort.getMapDimension())
                    .map { i -> LExpressionFactory.tagToSort(mapSort.paramSorts[i], toSmtLibData) },
                LExpressionFactory.tagToSort(resultSort, toSmtLibData)
            )
    }

    companion object {
        private fun ff(tags: List<Tag>) = FixedFunctionSignatures(tags.dropLast(1), tags.last())
        private fun f(vararg tags: Tag) = ff(tags.asList())

        fun IntNToInt(n: Int) = ff(List(n + 1) { Tag.Int })
        fun IntNToBool(n: Int) = ff(List(n) { Tag.Int } + Tag.Bool)

        fun binaryOperator(tag: Tag) = FixedFunctionSignatures(listOf(tag, tag), tag)

        fun binaryBVOperator(tag: Tag.Bits) = binaryOperator(tag)
        fun binaryBVRelation(tag: Tag.Bits) = FixedFunctionSignatures(listOf(tag, tag), Tag.Bool)

        val IntToInt by lazy { IntNToInt(1) }
        val IntIntToInt by lazy { IntNToInt(2) }
        val IntIntIntToInt by lazy { IntNToInt(3) }
        val BoolToBool by lazy { f(Tag.Bool, Tag.Bool) }
        val BoolBoolToBool by lazy { f(Tag.Bool, Tag.Bool, Tag.Bool) }
        val IntIntToBool by lazy { IntNToBool(2) }
        val Bv256ToBv256 by lazy { f(Tag.Bit256, Tag.Bit256) }
        val Bv256ToBool by lazy { f(Tag.Bit256, Tag.Bool) }
        val Bv256Bv256ToBool by lazy { f(Tag.Bit256, Tag.Bit256, Tag.Bool) }
        val Bv256Bv256ToBv256 by lazy { f(Tag.Bit256, Tag.Bit256, Tag.Bit256) }
        val Bv256Bv256ToBv512 by lazy { f(Tag.Bit256, Tag.Bit256, Tag.Bit512) }
        val Bv512ToBv256 by lazy { f(Tag.Bit512, Tag.Bit256) }
        val BoolIntIntToInt by lazy { f(Tag.Bool, Tag.Int, Tag.Int, Tag.Int) }
        val IntToBv256 by lazy { f(Tag.Int, Tag.Bit256) }
        val IntToBool by lazy { f(Tag.Int, Tag.Bool) }
        val LongStoreSignature by lazy { f(Tag.ByteMap, Tag.Int, Tag.ByteMap, Tag.Int, Tag.Int, Tag.ByteMap) }

        fun selectSignature(mapType: Tag, storageKeySort: Tag? = null) = when (mapType) {
            Tag.ByteMap -> FixedFunctionSignatures(listOf(Tag.ByteMap, Tag.Int), Tag.Int)
            Tag.WordMap -> {
                val key =
                    storageKeySort ?: error("storage key sort must be present when building a wordmap-select signature")
                FixedFunctionSignatures(listOf(Tag.WordMap, key), Tag.Int)
            }

            is Tag.GhostMap -> FixedFunctionSignatures(
                listOf(mapType, mapType.paramSorts.first()),
                mapType.popFirstParam()
            )

            else -> error("improper map signature $mapType")
        }
        fun multiSelectSignature(mapType: Tag.GhostMap) =
            FixedFunctionSignatures(listOf(mapType) + mapType.paramSorts, mapType.resultSort)

        fun storeSignature(mapType: Tag, storageKeySort: Tag? = null) = when (mapType) {
            Tag.ByteMap -> FixedFunctionSignatures(listOf(Tag.ByteMap, Tag.Int, Tag.Int), Tag.ByteMap)
            Tag.WordMap -> {
                val key =
                    storageKeySort ?: error("storage key sort must be present when building a wordmap-store signature")
                FixedFunctionSignatures(listOf(Tag.WordMap, key, Tag.Int), Tag.WordMap)
            }

            is Tag.GhostMap ->
                FixedFunctionSignatures(listOf(mapType, mapType.paramSorts.first(), mapType.popFirstParam()), mapType)

            else -> error("improper map signature $mapType")
        }
        fun multiStoreSignature(mapType: Tag.GhostMap) =
            FixedFunctionSignatures(listOf(mapType) + mapType.paramSorts + mapType.resultSort, mapType)

        fun wordMapSignature(sortOfStorageKeys: Tag) = when (sortOfStorageKeys) {
            Tag.Bit256 -> IntToInt
            Tag.Int -> IntToInt
            else -> error("unexpected sort of storage keys: \"$sortOfStorageKeys\"")
        }

    }
}
