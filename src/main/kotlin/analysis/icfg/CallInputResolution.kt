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
package analysis.icfg

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import allocator.SuppressRemapWarning
import analysis.CmdPointer
import analysis.pta.HeapType
import com.certora.collect.*
import analysis.pta.abi.*
import evm.DEFAULT_SIGHASH_SIZE
import evm.EVM_WORD_SIZE
import instrumentation.calls.CallConvention
import log.Logger
import log.LoggerTypes
import vc.data.*
import java.io.Serializable
import java.math.BigInteger
import datastructures.stdcollections.*
import utils.*

private val logger = Logger(LoggerTypes.INLINER)

/**
 * Maps every call core cmd to a resolved input.
 */
@SuppressRemapWarning
data class CallInputResolution(
    val resolution: Map<CmdPointer, CallInput>,
    val decomposedInfo: Map<CmdPointer, Set<DecomposedArgVariableWithSourceWrites>>
) {
    companion object {
        val empty = CallInputResolution(mapOf(), mapOf())
    }
}

/**
 * A range of bytes in the scratch memory from offset [from] to offset [to] (inclusive).
 */
@KSerializable
@Treapable
data class ScratchByteRange(val from: BigInteger, val to: BigInteger) : Serializable {
    companion object
}

/**
 * Represents an ABI encoded buffer encountered within another object. The values that are encoded within this buffer
 * are represented by the (now somewhat misleadingly named) [ABIArgumentEncoding] field [encoding]. If this buffer was
 * encoded with a sighash, (via encodeCall, or encodeWithSelector et al), then the (normalized) sighash is encoded in [sighash].
 */
@GenerateRemapper
data class EncodedElem(
    val sighash: BigInteger?,
    val encoding: ABIArgumentEncoding
) : TransformableVarEntityWithSupport<EncodedElem> {
    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): EncodedElem {
        return EncodedElem(
            sighash = sighash,
            encoding = encoding.transformSymbols(f)
        )
    }

    override val support: Set<TACSymbol.Var>
        get() = encoding.support
}

sealed class ABIValue : UniqueIdEntity<ABIValue>, Serializable, TransformableVarEntityWithSupport<ABIValue> {
    abstract val type : HeapType
    @KSerializable
    data class Constant(val k: BigInteger) : ABIValue() {
        override val type: HeapType = HeapType.Int
        override fun mapId(f: (Any, Int, () -> Int) -> Int): ABIValue {
            return this
        }

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Constant {
            return this
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf()
    }

    @GenerateRemapper
    data class Symbol(
        /**
         * The "symbol" that was encoded. In addition to variables, we also consider (re)encodings of calldata values,
         * represented by [ObjectPathAnalysis.ObjectRoot.CalldataRoot].
         */
        val sym: ObjectPathAnalysis.ObjectRoot,
        /**
         * The (coarse grained) type of the encoded object
         */
        override val type: HeapType,
        /**
         * Nested encodings discovered in the encoded object. The key is a traversal through the object (rooted at "this object"
         * as represented by the [UnitPath] value), the associated [EncodedElem] describes the encoded data found by traversing
         * that path. It is expected (but not checked, it's too expensive) that each such path starting from [type] will yield a
         * [HeapType.ByteArray].
         */
        val childEncodings: Map<ObjectPathGen<UnitPath>, EncodedElem>,
        /**
         * Records the partitions used to represent the reference value encoded at this point,
         * null if this is a primitive type or we couldn't infer the partitions (in which case this object
         * perhaps shouldn't be created).
         */
        val partitionRelation: Map<PartitionField, EncodedPartitions>?
    ) : ABIValue(), RemapperEntity<ABIValue> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ABIValue.Symbol {
            return Symbol(
                sym = when(sym) {
                    is ObjectPathAnalysis.ObjectRoot.CalldataRoot -> {
                        ObjectPathAnalysis.ObjectRoot.CalldataRoot(
                            fieldDepth = sym.fieldDepth,
                            bp = sym.bp.transformVariables(f)
                        )
                    }
                    is ObjectPathAnalysis.ObjectRoot.V -> ObjectPathAnalysis.ObjectRoot.V(f(sym.v))
                },
                type = type,
                partitionRelation = partitionRelation,
                childEncodings = childEncodings.mapValues {
                    it.value.transformSymbols(f)
                }
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = childEncodings.flatMapTo(treapSetBuilderOf()) {
                it.value.encoding.support
            } + when(sym) {
                    is ObjectPathAnalysis.ObjectRoot.CalldataRoot -> sym.bp.referencedVariables()
                    is ObjectPathAnalysis.ObjectRoot.V -> setOf(sym.v)
                }
    }
}

@GenerateRemapper
@KSerializable(with = ABIArgumentEncoding.Serializer::class)
data class ABIArgumentEncoding(
    @GeneratedBy(Allocator.Id.ABI)
    val encodeId: Int,
    val encodedArgs: Map<BigInteger, ABIValue>
) : AmbiSerializable, TransformableVarEntityWithSupport<ABIArgumentEncoding> {
    class Serializer : JavaBlobSerializer<ABIArgumentEncoding>()

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ABIArgumentEncoding {
        return ABIArgumentEncoding(
            encodeId = encodeId,
            encodedArgs = encodedArgs.mapValues { (_, av) ->
                av.transformSymbols(f)
            }
        )
    }

    override val support: Set<TACSymbol.Var>
        get() = encodedArgs.values.flatMapToSet {
            it.support
        }
}

/**
 * A resolved input to an external function call ([TACCmd.Simple.CallCore]).
 * The resolution is done by the [CallGraphBuilder].
 */
@GenerateRemapper
@KSerializable
data class CallInput(
    val baseVar: TACExpr.Sym.Var, /* The base into which the input was byteStored (expected to be scratch memory) */
    val offset: TACExpr.Sym, /* The base memory offset variable */
    val simplifiedOffset: TACExpr.Sym.Const?, /* If not null, [CallInput.offset] simplified to a constant */
    val size: TACExpr.Sym, /* The variable of the input size */
    val inputSizeLowerBound: BigInteger?, /* If not null, [CallInput.size] simplified to a constant */
    val rangeToDecomposedArg: Map<ScratchByteRange, DecomposedCallInputArg>,
    val encodedArguments: ABIArgumentEncoding?
    /* Keys - Byte ranges in scratch memory that store input args that have successfully
     been scalarized (decomposed).
     Values - Each is the "form" (represented by [DecomposedCallInputArg]) in which the decomposed input args is given to the callee (e.g.,
     as a variable symbol or as a literal/constant).
     */
) : Serializable, TransformableVarEntityWithSupport<CallInput> {

    constructor(_base: TACExpr.Sym.Var, _offset: TACExpr.Sym, _size: TACExpr.Sym) : this(_base, _offset, null, _size, null, mapOf(), null)

    /**
     * The value the callee sees at [byteOffset] of this input buffer, where [sighashSize] is the size of the
     * selector in the callee's calldata layout.
     *
     * Accepted offsets are the ABI argument offsets `sighashSize + n*32` (0, the selector itself, included) and
     * whole word offsets `n*32`. The latter belong to the calldata-forwarding proxy shape: a callee that copies
     * its entire calldata buffer over the scratch pad reads that buffer at word granularity rather than at its
     * argument offsets, so its calldata layout holds word-aligned ranges as well (see
     * [instrumentation.calls.CalldataEncoding]). Such a range straddles two arguments, so it usually has no
     * counterpart in [rangeToDecomposedArg] and is read straight out of the buffer. A caller which forwards its
     * own calldata the same way does decompose such ranges, and then we use the decomposed argument.
     */
    fun inputArgAtOffset(byteOffset: BigInteger, sighashSize: BigInteger): TACExpr {
        check(
            byteOffset.mod(EVM_WORD_SIZE) == BigInteger.ZERO ||
                (byteOffset >= sighashSize && byteOffset.mod(EVM_WORD_SIZE) == sighashSize)
        ) { "Expected to get a valid scratch memory offset, but instead got $byteOffset" }

        val byteRange = if (byteOffset == BigInteger.ZERO) {
            ScratchByteRange(byteOffset, 3.toBigInteger())
        } else {
            ScratchByteRange(byteOffset, byteOffset + 31.toBigInteger())
        }
        return if (byteRange in rangeToDecomposedArg) {
            getDecomposedArg(byteRange).asSym()
        } else {
            TACExpr.Select(
                baseVar, CallConvention.addK(simplifiedOffset ?: offset, byteOffset)
            )
        }
    }

    init {
        if (!rangeToDecomposedArg.all { (offset, _) ->
            (offset.from == BigInteger.ZERO && offset.to == (DEFAULT_SIGHASH_SIZE - BigInteger.ONE)) ||
                    (offset.from >= DEFAULT_SIGHASH_SIZE && offset.from.mod(
                        EVM_WORD_SIZE
                    ) == DEFAULT_SIGHASH_SIZE && (offset.to - offset.from) == EVM_WORD_SIZE - BigInteger.ONE)
        }) {
            logger.info {"Invalid byte offsets: each is expected to be either from 0 until 3 (inclusive), or from 4+n*32 until 4+n*32+31 (inclusive), but found $rangeToDecomposedArg" }
        }
        // check that ranges are distinct and continuous
        rangeToDecomposedArg.map { it.key }.sortedBy { it.from }.let { l ->
            val nonMatching = mutableSetOf<Pair<ScratchByteRange,ScratchByteRange>>()
            l.forEachIndexed { idx, range ->
                if (idx > 0) {
                    val prev = l.get(idx-1)
                    val newFrom = range.from
                    val oldToPlusOne = prev.to.plus(BigInteger.ONE)
                    if (newFrom != oldToPlusOne) {
                        // [a,b], [b+1, c] is a valid range pair
                        // [0,3], [32, ...) is allowed as well, because we narrow [0,31] to [0,3]
                        if (!(prev.to == (DEFAULT_SIGHASH_SIZE- BigInteger.ONE) && range.from == EVM_WORD_SIZE)) {
                            if (newFrom < oldToPlusOne) {
                                // only care about intersection, not about holes
                                nonMatching.add(prev to range)
                            } else {
                                logger.info { "Potentially a hole between $prev and $range, maybe a `bytes` parameter?"}
                            }
                        }
                    }
                }
            }
            if(nonMatching.isNotEmpty()) {
                logger.warn { "Found overlapping/non-contiguous ranges: $nonMatching" }
            }
        }
    }

    private fun getDecomposedArg(
        byteRange: ScratchByteRange
    ): TACSymbol {
        return rangeToDecomposedArg[byteRange]?.getSymbol() ?: error("$byteRange is expected to be in $rangeToDecomposedArg")
    }

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CallInput {
        fun TACExpr.Sym.f() = (this as? TACExpr.Sym.Var)?.s?.let(f)?.asSym() ?: this

        return CallInput(
            baseVar = f(baseVar.s).asSym(),
            offset = offset.f(),
            simplifiedOffset = simplifiedOffset,
            size = size.f(),
            inputSizeLowerBound = inputSizeLowerBound,
            rangeToDecomposedArg = rangeToDecomposedArg.mapValues { (r, v) ->
                when(v) {
                    is DecomposedCallInputArg.Constant -> v
                    is DecomposedCallInputArg.Variable -> DecomposedCallInputArg.Variable(
                        v = f(v.v),
                        contractReference = v.contractReference,
                        scratchRange = r
                    )
                }
            },
            encodedArguments = encodedArguments?.transformSymbols(f)
        )
    }

    override val support: Set<TACSymbol.Var>
        get() = setOfNotNull(
            baseVar.s,
            offset.s as? TACSymbol.Var,
            size.s as? TACSymbol.Var
        ) + rangeToDecomposedArg.mapNotNullTo(treapSetBuilderOf()) { (_, v) ->
            when(v) {
                is DecomposedCallInputArg.Constant -> null
                is DecomposedCallInputArg.Variable -> v.v
            }
        }.build() + encodedArguments?.support.orEmpty()

    fun remapContractReference(f: (CallGraphBuilder.CalledContract) -> CallGraphBuilder.CalledContract?): CallInput {
        return this.copy(
            rangeToDecomposedArg = this.rangeToDecomposedArg.mapValues { (_, arg) ->
                if(arg.contractReference != null) {
                    arg.withContractReference(f(arg.contractReference!!))
                } else {
                    arg
                }
            }
        )
    }
}
