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

package spec

import config.Config
import datastructures.stdcollections.plus
import datastructures.stdcollections.toList
import evm.MASK_SIZE
import org.apache.commons.lang3.concurrent.Memoizer
import spec.cvlast.CVLType
import spec.cvlast.EVMBuiltinTypes
import tac.MetaMap
import tac.TACBasicMeta

/**
 * List of solidity/CVL key words
 *
 * Reference list: [https://solidity.readthedocs.io/en/v0.5.11/units-and-global-variables.html#block-and-transaction-properties]
 *
 *  Copied from there:
 *
 * blockhash(uint blockNumber) returns (bytes32): hash of the given block - only works for 256 most recent, excluding current, blocks
 * block.coinbase (address payable): current block miner’s address
 * block.difficulty (uint): current block difficulty
 * block.gaslimit (uint): current block gaslimit
 * block.number (uint): current block number
 * block.timestamp (uint): current block timestamp as seconds since unix epoch
 * gasleft() returns (uint256): remaining gas
 * msg.data (bytes calldata): complete calldata
 * msg.sender (address payable): sender of the message (current call)
 * msg.sig (bytes4): first four bytes of the calldata (i.e. function identifier)
 * msg.value (uint): number of wei sent with the message
 * now (uint): current block timestamp (alias for block.timestamp)
 * tx.gasprice (uint): gas price of the transaction
 * tx.origin (address payable): sender of the transaction (full call chain)
 *
 *  note: "blockhash", "gasleft" and "now" have no dot, so they won't occurr in selectexpressions --> filing them under
 *   keywords
 *
 */
enum class CVLKeywords(
    val keyword: String,
    val type: CVLType.PureCVLType,
    val alwaysDefined: Boolean = false,
    // meta to attach when converting to a variable
    val meta: MetaMap = MetaMap()
) {
    ////    Blockhash("blockhash", null, ), //  TODO needs a CVL functiontype

    block("block", EVMBuiltinTypes.block),

    msg("msg", EVMBuiltinTypes.msg),

    tx("tx", EVMBuiltinTypes.tx),

    lastReverted("lastReverted", CVLType.PureCVLType.Primitive.Bool, meta = MetaMap(TACBasicMeta.LAST_REVERTED), alwaysDefined = true),
    lastStorage("lastStorage", CVLType.PureCVLType.VMInternal.BlockchainState, alwaysDefined = true),

    allContracts("allContracts", CVLType.PureCVLType.Primitive.AccountIdentifier, alwaysDefined = true),

    wildCardExp("_", CVLType.PureCVLType.Bottom, alwaysDefined = true),
    nativeBalances("nativeBalances", CVLType.PureCVLType.VMInternal.BlockchainStateMap(CVLType.PureCVLType.Primitive.AccountIdentifier, CVLType.PureCVLType.Primitive.UIntK(
        Config.VMConfig.registerBitwidth)
    ), alwaysDefined = true),
    nativeCodesize("nativeCodesize", CVLType.PureCVLType.VMInternal.BlockchainStateMap(CVLType.PureCVLType.Primitive.AccountIdentifier, CVLType.PureCVLType.Primitive.UIntK(
        Config.VMConfig.registerBitwidth
    )), alwaysDefined = true),

    // calledContract only makes sense in the context of a summary; therefore it's only defined in the methods block
    calledContract("calledContract", CVLType.PureCVLType.Primitive.AccountIdentifier, alwaysDefined = false),

    // executingContract only makes sense in the context of opcode hooks (not other hooks, and not summaries)
    executingContract("executingContract", CVLType.PureCVLType.Primitive.AccountIdentifier, alwaysDefined = false)
    ;

    companion object {
        /** @return the keyword corresponding to [keywordName] if any, or null */
        fun find(keywordName: String) = values().find { it.keyword == keywordName }

        const val CURRENT_CONTRACT = "currentContract"

        /**
         * @return the value of a keyword [id], if it's a keyword that is associated with a constant (integer) value
         */
        fun toValue(id: String) =
            CVLKeywords.find(id)?.let { keyword -> keyword.meta[TACBasicMeta.VALUE] }

        /**
         * defines the constants implicitly declared in a spec file, the key is the token and the value is the value
         * of the constant
         */
        val constVals = Memoizer<VMConfig, Map<String, String>> { config ->
            (8..256 step 8).associate { "max_uint$it" to MASK_SIZE(it).toString() }
                .plus("max_uint" to config.maxUint.toString())
                .plus("max_address" to config.maxAddress.toString())
        }
    }
}

fun String.isWildcard() = this == CVLKeywords.wildCardExp.keyword

/**
 * @param [hasConcrete] Whether this cast type has concrete syntax, if false, casts with this type will not be valid keywords
 * in the AST
 */
enum class CastType(val str: String, val hasConcrete: Boolean) {
    REQUIRE("require", true),
    ASSERT("assert", true),
    TO("to", true),
    CONVERT("convert", false)
}

class CVLCastFunction private constructor(
    val keyword: String,
    val castType: CastType,
    val targetCVLType: CVLType.PureCVLType,
) {
    override fun toString(): String {
        return "${castType.str}_$targetCVLType"
    }

    companion object {
        val castFunctions = sequence {
            for (nBytes in 1..32) {
                CastType.values().forEach { type ->
                    when (type) {
                        CastType.REQUIRE, CastType.ASSERT -> {
                            val nBits = nBytes * 8
                            yield(CVLCastFunction(
                                    "${type.str}_uint$nBits", type, CVLType.PureCVLType.Primitive.UIntK(nBits)
                            ))
                            yield(CVLCastFunction(
                                    "${type.str}_int$nBits", type, CVLType.PureCVLType.Primitive.IntK(nBits)
                            ))
                        }

                        CastType.TO -> {
                            yield(CVLCastFunction(
                                    "${type.str}_bytes$nBytes", type, CVLType.PureCVLType.Primitive.BytesK(nBytes)
                            ))
                        }
                        CastType.CONVERT -> {
                            // do nothing, no concrete syntax for convert_*
                        }
                    }
                }
            }
            yield(CVLCastFunction("${CastType.TO.str}_mathint", CastType.TO, CVLType.PureCVLType.Primitive.Mathint))
            yield(CVLCastFunction("${CastType.CONVERT.str}_byteblob", CastType.CONVERT, CVLType.PureCVLType.Primitive.HashBlob))
            yield(CVLCastFunction("${CastType.REQUIRE.str}_address", CastType.REQUIRE, CVLType.PureCVLType.Primitive.AccountIdentifier))
            yield(CVLCastFunction("${CastType.ASSERT.str}_address", CastType.ASSERT, CVLType.PureCVLType.Primitive.AccountIdentifier))
        }.toList()
    }
}

val allCastFunctions = CVLCastFunction.castFunctions.filter {
    it.castType.hasConcrete
}.associateBy { f -> f.keyword }

fun checkAllKeywordsUnique(vmConfig: VMConfig){
    val allKeywords = allCastFunctions.keys.toList() +
            vmConfig.memoryLocations +
            vmConfig.preReturnMethodQualifiers +
            vmConfig.postReturnMethodQualifiers +
            vmConfig.hookableOpcodes
    check(allKeywords.distinct().size == allKeywords.size) { "duplicate keywords found which may lead to lexer ambiguities" }
}
