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

package vc.data

import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import spec.CVLKeywords
import tac.CallId
import tac.MetaMap
import tac.TACBasicMeta
import tac.Tag
import utils.AmbiSerializable
import utils.KSerializable
import vc.data.TACSymbol.Companion.atSync
import vc.data.TACSymbol.Var.Companion.KEYWORD_ENTRY
import java.math.BigInteger

enum class TACKeyword(private val varName: String, val type: Tag, val metaMap: MetaMap = MetaMap(), val cvlCorrespondence: CVLKeywords? = null, val stateVarWithBackup: Boolean = false) {
    SWAPVAR("tacSwap", Tag.Bit256), // swap aux var for swap commands
    MEMORY("tacM", Tag.ByteMap, MetaMap(TACMeta.EVM_MEMORY)), // ByteMap
    STORAGE("tacS", Tag.WordMap), // WordMap
    ORIGINAL_STORAGE("tacOrigS", Tag.WordMap), // WordMap
    ORIGINAL_TRANSIENT_STORAGE("tacOrigT", Tag.WordMap), // WordMap
    TRANSIENT_STORAGE("tacT", Tag.WordMap), // WordMap

    SIGHASH("tacSighash", Tag.Bit256), // the first 4 bytes of the tacCalldatabuf, if we were able to extract them

    CODESIZE("tacCodesize", Tag.Bit256, MetaMap(TACMeta.NO_CALLINDEX)),
    CONSTRUCTOR_CODESIZE("tacConstructorCodesize", Tag.Bit256, MetaMap(TACMeta.NO_CALLINDEX)),

    CODEDATA("tacCodedata", Tag.ByteMap, MetaMap(TACMeta.NO_CALLINDEX)), // ?
    CONSTRUCTOR_CODEDATA("tacConstructorCodedata", Tag.ByteMap, MetaMap(TACMeta.NO_CALLINDEX)), // ?
    EXTCODEDATA("tacExtcodedata", Tag.ByteMap, MetaMap(TACMeta.NO_CALLINDEX)), // ?
    CALLDATASIZE("tacCalldatasize", Tag.Bit256),
    ORIGIN("tacOrigin", Tag.Bit256, MetaMap(TACMeta.ENV_BIT_WIDTH to 160)),
    CALLER("tacCaller", Tag.Bit256, MetaMap(TACMeta.ENV_BIT_WIDTH to 160)),
    CALLDATA("tacCalldatabuf",
        Tag.ByteMap,
        MetaMap(
            TACMeta.EVM_IMMUTABLE_ARRAY to ImmutableBound(CALLDATASIZE.toVar())
        ) + TACMeta.IS_CALLDATA
    ), // ByteMap

    CALLVALUE("tacCallvalue", Tag.Bit256),
    RETURN_VAL("tacRetval", Tag.Bit256 /* This appears to be synthetic */),
    RETURN_SIZE("tacReturnsize", Tag.Bit256),
    RETURNDATA("tacReturndata", Tag.ByteMap, MetaMap(TACMeta.IS_RETURNDATA)),
    RETURNCODE("tacRC", Tag.Bit256),
    CONSTRUCTOR_RETURN("tacCreateAddr", Tag.Bit256),
    ADDRESS("tacAddress", Tag.Bit256, MetaMap(TACMeta.ENV_BIT_WIDTH to Config.VMConfig.maxAddress.bitLength())),
    BALANCE("tacBalance", Tag.WordMap, MetaMap(TACMeta.NO_CALLINDEX), cvlCorrespondence = CVLKeywords.nativeBalances, stateVarWithBackup = true),
    GASPRICE("tacGasPrice", Tag.Bit256),
    EXTCODESIZE("tacExtcodesize", Tag.WordMap, MetaMap(TACMeta.IS_EXTCODESIZE).plus(MetaMap(TACMeta.NO_CALLINDEX)), stateVarWithBackup = true, cvlCorrespondence = CVLKeywords.nativeCodesize),
    EXTCODEHASH("tacExtcodehash", Tag.WordMap, MetaMap(TACMeta.NO_CALLINDEX)),
    BLOCKHASH("tacBlockhash", Tag.WordMap),
    COINBASE("tacCoinbase", Tag.Bit256),
    TIMESTAMP("tacTimestamp", Tag.Bit256),
    NUMBER("tacNumber", Tag.Bit256),
    DIFFICULTY("tacDifficulty", Tag.Bit256),
    GASLIMIT("tacGaslimit", Tag.Bit256),
    CHAINID("tacChainid", Tag.Bit256, MetaMap(TACMeta.NO_CALLINDEX)),
    BASEFEE("tacBasefee", Tag.Bit256),
    BLOBHASHES("tacBlobhashes", Tag.WordMap, MetaMap(TACMeta.NO_CALLINDEX)),
    BLOBBASEFEE("tacBlobbasefee", Tag.Bit256),
    MEM0("tacM0x0", Tag.Bit256, MetaMap(TACMeta.RESERVED_MEMORY_SLOT to BigInteger.ZERO)),
    MEM32("tacM0x20", Tag.Bit256, MetaMap(TACMeta.RESERVED_MEMORY_SLOT to 0x20.toBigInteger())),
    MEM64("tacM0x40", Tag.Bit256, MetaMap(TACMeta.RESERVED_MEMORY_SLOT to 0x40.toBigInteger())),
    ARGOFFSET("tacArgOffset", Tag.Bit256),
    ARG("tacArg", Tag.Bit256),
    COND("tacCond", Tag.Bool),
    CREATED("tacCreated", Tag.Bit256, MetaMap(TACMeta.IS_CREATED_ADDRESS)),
    NONCE("tacNonce", Tag.WordMap, MetaMap(TACMeta.NO_CALLINDEX), stateVarWithBackup = true),
    STACK_HEIGHT("L", Tag.Bit256),

    /**
     * These are *synthetic* properties that track the number of clones created along any given path
     */
    CONTRACT_COUNT("tacContractsCreated", Tag.WordMap, MetaMap(TACMeta.NO_CALLINDEX), stateVarWithBackup = true),

    /**
        Maps allocated Soroban object handles to `true`.
     */
    SOROBAN_OBJECTS("tacSorobanObjects", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bool), MetaMap(TACMeta.SOROBAN_ENV)),

    /*
        Soroban object types

        SOROBAN_*_SIZES: used to store mapping sizes: objectHandle->size
        SOROBAN_*_MAPPINGS: used to store mappings: {objectHandle,key}->value
     */
    SOROBAN_BYTES_SIZES("tacSoroBytesSizes", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_BYTES_MAPPINGS("tacSoroBytesMappings", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    SOROBAN_STRING_SIZES("tacSoroStringSizes", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_STRING_MAPPINGS("tacSoroStringMappings", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    SOROBAN_SYMBOL_SIZES("tacSoroSymbolSizes", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_SYMBOL_MAPPINGS("tacSoroSymbolMappings", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    SOROBAN_VEC_SIZES("tacSoroVectorSizes", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_VEC_MAPPINGS("tacSoroVectorMappings", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    SOROBAN_MAP_SIZES("tacSoroMapSizes", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_MAP_MAPPINGS("tacSoroMapMappings", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    /*
        Additional maps for Soroban "map" objects; see [wasm.host.soroban.types.MapType]
     */
    SOROBAN_MAP_KEYS("tacSoroMapKeys", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bool), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_MAP_KEY_DIGESTS("tacSoroMapKeyDigests", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bool), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_MAP_KEY_TO_INDEX("tacSoroMapKeyToIndex", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_MAP_INDEX_TO_KEY("tacSoroMapIndexToKey", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    /** Maps each object handle to a unique integer identifying the type and content of the object */
    SOROBAN_OBJECT_DIGEST("tacSoroObjDigest", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    SOROBAN_ADDRESS_CURRENT("tacSoroAddressCurrent", Tag.Bit256, MetaMap(TACMeta.SOROBAN_ENV)),

    SOROBAN_LEDGER_VERSION("tacSoroLedgerVersion", Tag.Bit256, MetaMap(TACMeta.NO_CALLINDEX).plus(TACMeta.SOROBAN_ENV)),
    SOROBAN_LEDGER_SEQUENCE("tacSoroLedgerSequence", Tag.Bit256, MetaMap(TACMeta.NO_CALLINDEX).plus(TACMeta.SOROBAN_ENV)),
    SOROBAN_LEDGER_TIMESTAMP("tacSoroLedgerTimestamp", Tag.Bit256, MetaMap(TACMeta.NO_CALLINDEX).plus(TACMeta.SOROBAN_ENV)),
    SOROBAN_LEDGER_NETWORK_ID("tacSoroLedgerNetworkId", Tag.Bit256, MetaMap(TACMeta.NO_CALLINDEX).plus(TACMeta.SOROBAN_ENV)),
    SOROBAN_MAX_LIVE_UNTIL_LEDGER("tacSoroMaxLiveUntilLedger", Tag.Bit256, MetaMap(TACMeta.NO_CALLINDEX).plus(TACMeta.SOROBAN_ENV)),

    /** Soroban contract data (a.k.a. storage) */
    SOROBAN_CONTRACT_DATA("tacSoroContractData", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_CONTRACT_DATA_KEY_DIGESTS("tacSoroContractDataKeyDigests", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bool), MetaMap(TACMeta.SOROBAN_ENV)),

    /** Int object mappings (handle)->value */
    SOROBAN_U64_VALUES("tacSoroU64Values", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_I64_VALUES("tacSoroI64Values", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_U128_VALUES("tacSoroU128Values", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_I128_VALUES("tacSoroI128Values", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_U256_VALUES("tacSoroU256Values", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_I256_VALUES("tacSoroI256Values", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_TIMEPOINT_VALUES("tacSoroTimepointValues", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),
    SOROBAN_DURATION_VALUES("tacSoroDurationValues", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    /** digest(address)->is_auth(address) */
    SOROBAN_ADDRESS_AUTH("tacSoroAddressAuth", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bool), MetaMap(TACMeta.SOROBAN_ENV)),

    /** digest(address)->address_to_strkey(address) */
    SOROBAN_ADDRESS_TO_STRKEY("tacSoroAddressToStrkey", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256), MetaMap(TACMeta.SOROBAN_ENV)),

    /** (prev_digest, new_elem_hash) -> new_digest */
    MOVE_VECTOR_PUSH_BACK_DIGEST("tacMvVecPushBackDigest", Tag.GhostMap(listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256)),
    /** prev_digest -> new_digest */
    MOVE_VECTOR_POP_BACK_DIGEST("tacMvVecPopBackDigest", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256)),

    MOVE_NONDET_TYPE_EQUIV("tacMoveNondetTypeIds", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256)),

    RUST_FP("tacRustFreePointer", Tag.Bit256)
    ;

    init {
        /*
          See below, we are using string ops to get rid of boilerplate :(
         */
        @Suppress("ForbiddenMethodCall")
        require(!stateVarWithBackup || varName.startsWith("tac"))
    }

    fun freshBackupVar(): TACSymbol.Var {
        require(stateVarWithBackup)
        // this is to reduce boilerplate, but not break legacy code that looks for the `tacOrig*` names
        return TACSymbol.Var(
            this.varName.replaceFirst("tac", "tacOrig"),
            tag = this.type,
            meta = MetaMap(TACMeta.NO_CALLINDEX)
        ).toUnique("!")
    }

    @OptIn(TACSymbol.Var.KeywordEntry.TACKeywordEntryCreation::class)
    val keywordEntry = TACSymbol.Var.KeywordEntry.create(this)

    private val metaWithKeywordEntry = metaMap + (KEYWORD_ENTRY to keywordEntry)

    companion object {
        /**
         * Provides with a unique temporary variable with the given [tag] and readability-auxiliary [suffix],
         * and some optional meta in [metaMap]
         */
        fun TMP(tag: Tag, suffix: String = "", metaMap: MetaMap = MetaMap()) =
            TMP_DETERMINISTIC(tag, suffix, metaMap).toUnique()

        /**
         * Like [TMP], but for the case where we need a not-necessarily-unique tmp variable
         */
        fun TMP_DETERMINISTIC(tag: Tag, suffix: String = "", metaMap: MetaMap = MetaMap()) =
            TACSymbol.Var("tacTmp${suffix}", tag, meta = metaMap + TACBasicMeta.IS_TMP_METAKEY)

        // these should never have incarn != 0 and should not accept different values in different calls (TODO: enforce)
        fun IMMUTABLE(tag: Tag, name: String, owningContract: String) =
            TACSymbol.Var("tacImmut_${owningContract}_${name}", tag).withMeta {
                var mut = it
                mut += TACBasicMeta.IS_IMMUTABLE
                mut += TACBasicMeta.IMMUTABLE_NAME to name
                mut += TACBasicMeta.IMMUTABLE_OWNER to owningContract
                mut
            }
        fun findByCVLCorrespondence(keyword: CVLKeywords) = entries.find { it.cvlCorrespondence == keyword}
    }

    private val varWithDefaultIndex = TACSymbol.Var(
        this.varName,
        type,
        TACSymbol.Var.DEFAULT_INDEX,
        meta = this.metaWithKeywordEntry
    )

    fun toVar(callIndex: CallId = TACSymbol.Var.DEFAULT_INDEX) =
        varWithDefaultIndex.atSync(callIndex)

    fun getName() = this.varName

    fun isName(f : (String) -> Boolean) : Boolean{
        return f(getName())
    }

    fun extendName(extension : String) : KeywordBasedName {
        return KeywordBasedName(getName() + extension, this)
    }
}

data class KeywordBasedName(val name : String, val keyword: TACKeyword)

@KSerializable
@Treapable
data class ImmutableBound(val sym: TACSymbol) : AmbiSerializable, TransformableSymEntityWithRlxSupport<ImmutableBound>, CallIndexSyncMeta<ImmutableBound> {
    override fun transformSymbols(f: (TACSymbol) -> TACSymbol): ImmutableBound {
        return ImmutableBound(sym = f(sym))
    }

    override fun newCallId(callId: CallId): ImmutableBound {
        return when(sym) {
            is TACSymbol.Const -> this
            is TACSymbol.Var -> ImmutableBound(sym.at(callId))
        }
    }
}
