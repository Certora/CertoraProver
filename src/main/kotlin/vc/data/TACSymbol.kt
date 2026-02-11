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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)
package vc.data

import allocator.Allocator
import analysis.storage.StorageAnalysis
import analysis.storage.StorageAnalysisResult
import com.certora.collect.*
import datastructures.stdcollections.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import log.*
import tac.*
import tac.TACBasicMeta.STACK_HEIGHT
import utils.*
import utils.ModZm.Companion.inBounds
import vc.data.TACMeta.CONTRACT_ADDR_KEY
import vc.data.TACMeta.CONTRACT_ADDR_KEY_NAME
import vc.data.TACSymbol.Var.Companion.invoke
import vc.data.tacexprutil.asVarOrNull
import java.io.Serializable
import java.math.BigInteger

@KSerializable
@Treapable
sealed class TACSymbol : ITACSymbol, Tagged, TransformableSymEntity<TACSymbol>, HasKSerializable, ToTACExpr {

    override fun toTACExpr(): TACExpr = asSym()

    abstract fun toSMTRep(): String

    abstract fun asSym(): TACExpr.Sym

    /* Getting the access path from a TACSymbol representing a storage location.
     * Variables should have an [TACMeta.ACCESS_PATHS], constants are assumed to be Roots at their values.
     * (Note that if the const is actually a dynamic type's location (as it could be a hash result), this can be unsound.
     * This would make our entire storage modelling fail anyway though, so we happily assume it's not happening.)
     */
    fun toAccessPath(base: StorageAnalysis.Base) = when (this) {
        is Var -> this.meta.find(TACMeta.ACCESS_PATHS)
        is Const -> StorageAnalysisResult.AccessPaths(setOf(StorageAnalysis.AnalysisPath.Root(this.value, base)))
    }

    @KSerializable
    data class Const(val value: BigInteger, override val tag: Tag = Tag.Bit256) : TACSymbol() {
        init {
            checkNot(tag is Tag.Bits && !value.inBounds(tag)) {
                "Invalid value for $tag TAC constant: $value"
            }
        }
        /**
         * @precondition [tag] == [Tag.Bool]
         */
        private fun taggedBoolVal(): String {
            return when (value) {
                BigInteger.ZERO -> "false"
                BigInteger.ONE -> "true"
                else -> throw RuntimeException("Illegal boolean-tagged tac symbol $value")
            }
        }

        override fun toString(): String { // Used for example to see the values in the debugger.
            if (tag == Tag.Bool) {
                return taggedBoolVal()
            }
            return "0x${value.toString(16)}"
        }

        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): TACSymbol = f(this)

        override fun toSMTRep(): String {
            return toString()
        }

        override fun asSym(): TACExpr.Sym.Const = if(this.tag is Tag.Bits || this.tag == Tag.Bool) {
            TACExpr.Sym.Const(
                tag = this.tag,
                s = this
            )
        } else {
            TACExprFactTypeCheckedOnlyPrimitives.Sym(this) as TACExpr.Sym.Const
        }

        companion object {
            /** Works like a constructor, call it as `Const(true)`; advantage: does not create a fresh object for
             * each boolean constant. */
            operator fun invoke(value: Boolean) = if (value) True else False

            val byValue = Comparator.comparing { it: Const -> it.value }
        }
    }

    companion object {
        fun Var.atSync(callIndex: CallId) : Var {
            val newMeta = this.meta.updateValues { (_, v) ->
                if(v !is CallIndexSyncMeta<*>) {
                    return@updateValues v
                } else {
                    v.newCallId(callIndex) as Serializable
                }
            }
            return Var(
                namePrefix = namePrefix,
                tag = tag,
                callIndex = callIndex,
                meta = newMeta
            )
        }

        fun lift(b: Boolean): Const = Const(b)
        fun lift(i: Int, tag: Tag = Tag.Bit256): Const = Const(i.toBigInteger(), tag)

        fun lift(l: BigInteger): Const = lift(l, Tag.Bit256)
        fun lift(l: BigInteger, tag: Tag): Const = when (l) {
            BigInteger.ZERO -> Zero
            BigInteger.ONE -> One
            else -> Const(l, tag)
        }

        fun immutable(name: String, owningContract: String) = TACKeyword.IMMUTABLE(Tag.Bit256, name, owningContract)

        val True = Const(BigInteger.ONE, Tag.Bool)
        val False = Const(BigInteger.ZERO, Tag.Bool)

        val Zero = Const(BigInteger.ZERO, Tag.Bit256)
        val One = Const(BigInteger.ONE, Tag.Bit256)

        fun zero(tag: Tag.Bits) = Const(BigInteger.ZERO, tag)
        fun one(tag: Tag.Bits) = Const(BigInteger.ONE, tag)

        val byValueOrName = Comparator<TACSymbol> { a, b ->
            when (a) {
                is Const -> when (b) {
                    is Var -> -1 // consts before vars
                    is Const -> Const.byValue.compare(a, b)
                }
                is Var -> when (b) {
                    is Var -> Var.byName.compare(a, b)
                    is Const -> 1 // vars after consts
                }
            }
        }
    }

    @KSerializable(with = Var.Serializer::class)
    sealed class Var : ITACSymbolVar, TACSymbol(), HasKSerializable {
        abstract val namePrefix: String
        abstract val meta: MetaMap
        abstract val callIndex: CallId

        fun printDeclaration() = "$smtRep${TACIdentifiers.tagPrefix}$tag"
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        sealed class SerializerTemplate<V : Var>(serialName: String) : KSerializer<V> {
            private val delegateSerializer = Full.serializer()
            override val descriptor = SerialDescriptor(serialName, delegateSerializer.descriptor)

            override fun serialize(encoder: Encoder, value: V) {
                val full = Full(value.namePrefix, value.tag, value.callIndex, value.meta)
                encoder.encodeSerializableValue(delegateSerializer, full)
            }

            override fun deserialize(decoder: Decoder): V {
                val full = decoder.decodeSerializableValue(delegateSerializer)
                return Var(full.namePrefix, full.tag, full.callIndex, full.meta).uncheckedAs()
            }
        }

        class Serializer : SerializerTemplate<Var>("Var")

        private class WithDefaultCallIndex {
            @KSerializable(with = WithBit256.Serializer::class)
            class WithBit256(override val namePrefix: String, override val meta: MetaMap) : Var() {
                override val tag get() = Tag.Bit256
                override val callIndex get() = DEFAULT_INDEX
                class Serializer : SerializerTemplate<WithBit256>("WithBit256")
            }

            @KSerializable(with = WithBool.Serializer::class)
            class WithBool(override val namePrefix: String, override val meta: MetaMap) : Var() {
                override val tag get() = Tag.Bool
                override val callIndex get() = DEFAULT_INDEX
                class Serializer : SerializerTemplate<WithBool>("WithBool")
            }

            @KSerializable(with = WithByteMap.Serializer::class)
            class WithByteMap(override val namePrefix: String, override val meta: MetaMap) : Var() {
                override val tag get() = Tag.ByteMap
                override val callIndex get() = DEFAULT_INDEX
                class Serializer : SerializerTemplate<WithByteMap>("WithByteMap")
            }

            @KSerializable(with = WithWordMap.Serializer::class)
            class WithWordMap(override val namePrefix: String, override val meta: MetaMap) : Var() {
                override val tag get() = Tag.WordMap
                override val callIndex get() = DEFAULT_INDEX
                class Serializer : SerializerTemplate<WithWordMap>("WithWordMap")
            }

            @KSerializable(with = WithInt.Serializer::class)
            class WithInt(override val namePrefix: String, override val meta: MetaMap) : Var() {
                override val tag get() = Tag.Int
                override val callIndex get() = DEFAULT_INDEX
                class Serializer : SerializerTemplate<WithInt>("WithInt")
            }
        }

        @KSerializable
        private class Full(
            override val namePrefix: String,
            override val tag: Tag,
            override val callIndex: CallId,
            override val meta: MetaMap = MetaMap()
        ) : Var()

        companion object {
            fun invokeFromJava(namePrefix: String,
                                tag: Tag, callIndex: CallId) = invoke(namePrefix,tag, callIndex)

            operator fun invoke(
                namePrefix: String,
                tag: Tag,
                callIndex: CallId = DEFAULT_INDEX,
                meta: MetaMap = MetaMap()) : Var
            {
                return invoke(namePrefix, tag, callIndex, meta, null)
            }
            operator fun invoke(
                namePrefix: String,
                tag: Tag,
                callIndex: CallId = DEFAULT_INDEX,
                meta: MetaMap = MetaMap(),
                keyword : TACKeyword) : Var
            {
                return invoke(namePrefix, tag, callIndex, meta, KeywordEntry(keyword))
            }
            operator fun invoke(
                namePrefix: String,
                tag: Tag,
                callIndex: CallId = DEFAULT_INDEX,
                meta: MetaMap = MetaMap(),
                keyword : String) : Var
            {
                return invoke(namePrefix, tag, callIndex, meta, KeywordEntry(keyword))
            }
            operator fun invoke(
                keywordName: KeywordBasedName,
                tag: Tag,
                callIndex: CallId = DEFAULT_INDEX,
                meta: MetaMap = MetaMap()) : Var
            {
                return invoke(keywordName.name, tag, callIndex, meta, keywordName.keyword)
            }
            private operator fun invoke(
                namePrefix: String,
                tag: Tag,
                callIndex: CallId = DEFAULT_INDEX,
                meta: MetaMap = MetaMap(),
                keyword : KeywordEntry? = null // if the Var's name should be preserved in canonical TAC - add it as keyword
            ): Var {
                require(TACIdentifiers.valid(namePrefix)) { "Var name '$namePrefix' is not a valid identifier" }
                return when {
                    callIndex != DEFAULT_INDEX -> Full(namePrefix, tag, callIndex, meta)
                    tag == Tag.Bit256 -> WithDefaultCallIndex.WithBit256(namePrefix, meta)
                    tag == Tag.Bool -> WithDefaultCallIndex.WithBool(namePrefix, meta)
                    tag == Tag.ByteMap -> WithDefaultCallIndex.WithByteMap(namePrefix, meta)
                    tag == Tag.WordMap -> WithDefaultCallIndex.WithWordMap(namePrefix, meta)
                    tag == Tag.Int -> WithDefaultCallIndex.WithInt(namePrefix, meta)
                    else -> Full(namePrefix, tag, callIndex, meta)
                }.let { keyword?.let { keyword -> it.withMeta(KEYWORD_ENTRY, keyword)} ?: it
                }
            }
            const val DEFAULT_INDEX : CallId = NBId.ROOT_CALL_ID
            fun stackVar(i: Int) = Var(TACKeyword.STACK_HEIGHT.extendName("$i"), Tag.Bit256, ).withMeta(STACK_HEIGHT, i)

            fun contractVar(id : BigInteger, name : String) = Var("tacContractAt0x${id.toString(16)}_x$name", Tag.Bit256, keyword = "tacContractAt")
                .withMeta(CONTRACT_ADDR_KEY, id)
                .withMeta(CONTRACT_ADDR_KEY_NAME, name)

            val byName = Comparator.comparing { it: Var -> it.namePrefix }.thenComparingInt {
                it.callIndex
            }

            val KEYWORD_ENTRY = MetaKey<KeywordEntry>("Tac.symbol.keyword")

            fun MetaMap.hasKeyword(k : TACKeyword) =
                get(KEYWORD_ENTRY)?.let { it.name == k.getName() } == true

            fun TACSymbol.hasKeyword(k : TACKeyword) =
                asVarOrNull?.meta?.hasKeyword(k) == true
        }

        @KSerializable
        @Treapable
        sealed class KeywordEntry : AmbiSerializable {
            abstract val name: String
            abstract val maybeTACKeywordOrdinal: Int?

            @KSerializable
            @Treapable
            private data class TACKeywordEntry(
                override val name: String,
                override val maybeTACKeywordOrdinal: Int
            ) : KeywordEntry()

            @KSerializable
            @Treapable
            private data class NonTACKeywordEntry(
                override val name: String
            ) : KeywordEntry() {
                override val maybeTACKeywordOrdinal = null
            }

            @RequiresOptIn("Only TACKeyword itself should create TACKeyword-based KeywordEntry instances")
            annotation class TACKeywordEntryCreation

            companion object {
                /** Gets the one-and-only KeywordEntry for the given TACKeyword */
                operator fun invoke(keyword: TACKeyword): KeywordEntry = keyword.keywordEntry

                /** Makes a new KeywordEntry for a non-TAC keyword */
                operator fun invoke(keyword: String): KeywordEntry = NonTACKeywordEntry(keyword)

                @TACKeywordEntryCreation
                fun create(keyword: TACKeyword): KeywordEntry = TACKeywordEntry(keyword.getName(), keyword.ordinal)
            }
        }

        @KSerializable
        data class Annotation(val v: Var?): TransformableVarEntityWithSupport<Annotation>, HasKSerializable {
            override val support: Set<Var>
                get() = setOfNotNull(v)

            override fun transformSymbols(f: (Var) -> Var): Annotation {
                return v?.let { v -> Annotation(f(v)) }?: this
            }
        }

        val smtRep get() = "$namePrefix${stringSuffixesForSMT()}"

        private fun stringSuffixesForSMT(): String =
            if (callIndex != DEFAULT_INDEX) {
                "${TACIdentifiers.callIndexPrefix}$callIndex"
            } else {
                ""
            }

        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): TACSymbol = f(this)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Var) return false
            if (namePrefix != other.namePrefix) return false
            if (callIndex != other.callIndex) return false
            if (tag != other.tag) return false
            return true
        }

        override fun hashCode(): Int {
            var result: Int = namePrefix.hashCode()
            result = 31 * result + callIndex.hashCode()
            result = 31 * result + tag.hashCode()
            return result
        }

        fun copy(
            namePrefix: String = this.namePrefix,
            meta: MetaMap = this.meta
        ) = Var(namePrefix, tag, callIndex, meta)

        // jeez I don't really wanna be passing the TACExprTag here
        override fun asSym() = TACExpr.Sym.Var(this, this.tag)

        override fun toSMTRep(): String =
            smtRep // TODO: use getter instead (preferable make a separate PR for this, prob., since it's called a lot)

        override fun toString(): String = "$smtRep${TACIdentifiers.tagPrefix}$tag$partitionIdString"
        fun declarationPrint() = "$smtRep${TACIdentifiers.tagPrefix}$tag"

        val partitionIdString get() = meta[optimizer.UNINDEXED_PARTITION]?.let { "@${it.id}" }.orEmpty()


        // methods for creating new [Var]s

        fun updateTag(tag: Tag, suffix: String, sep: String = "", meta: MetaMap = this.meta): Var {
            return Var(tag = tag, namePrefix = "$namePrefix$sep$suffix", callIndex = callIndex, meta = meta)
        }

        fun updateTagUnique(tag: Tag, sep: String = "!", meta: MetaMap = this.meta): Var {
            return Var(tag = tag, namePrefix = namePrefix, callIndex = callIndex, meta = meta).toUnique(sep)
        }

        fun convertBoolToNumber(): TACExpr {
            return when (this.tag) {
                is Tag.Bool -> TACExprFactTypeCheckedOnlyPrimitives.Ite(
                    this.asSym(),
                    BigInteger.ONE.asTACSymbol().asSym(),
                    BigInteger.ZERO.asTACSymbol().asSym()
                )
                else -> error("Should be called only on bool symbols, got ${this.tag} for $this")
            }
        }

        /* Before removing a meta make sure checks for its existence won't be affected. */
        fun withoutMeta(key: MetaKey<*>) = withMeta { it - key }

        fun <T : Serializable> withMeta(key: MetaKey<T>, v: T) = withMeta { it + (key to v) }
        fun withMeta(key: MetaKey<Nothing>) = withMeta { it + key }

        fun <T : Serializable> withMetaIfNotNull(key: MetaKey<T>, v: T?) = if (v != null) {
            withMeta(key, v)
        } else {
            this
        }

        inline fun withMeta(f: (MetaMap) -> MetaMap): Var = this.copy(meta = f(meta))

        fun withMeta(metaMap: MetaMap): Var = this.copy(meta = meta + metaMap)

        fun toUnique(sep: String = ""): Var = withSuffix(Allocator.getFreshNumber().toString(), sep)

        fun withSuffix(st: String, sep: String = ""): Var =
            Var(namePrefix + sep + st, tag, callIndex, meta = meta)

        fun withSuffix(i: Int, sep: String = "") = withSuffix(i.toString(), sep)

        fun at(callIndex: CallId): Var = Var(namePrefix, tag, callIndex, meta = meta)
    }

    object Factory {
        // this should be deprecated post CVL rewrite
        fun getFreshAuxVar(@Suppress("UNUSED_PARAMETER") purpose: AuxVarPurpose, originalSymbol: Var): Var =
            Var(
                // old style, more verbose, names: "av${purpose}.${originalSymbol.namePrefix}",
                namePrefix = originalSymbol.namePrefix,
                callIndex = originalSymbol.callIndex,
                tag = originalSymbol.tag
            ).toUnique(".")

        enum class AuxVarPurpose {
            SEQ,  // sequential composition
            PAR,  // parallel composition
            LHS,  // left hand side of an assignment
            COL, // avoiding collisions of auxVars during summary composition by renaming apart
            QUANT, // used when transforming a quantified formula
            ACCESS_PATH_INDEX,
            SUMMARY,
            CVL_FUNCTION_PRECOMPILATION,
            MAP_DEF_INSERTER,
            HASH_APPROX,
        }
    }
}
