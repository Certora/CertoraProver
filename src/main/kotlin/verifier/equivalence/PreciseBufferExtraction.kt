/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package verifier.equivalence

import analysis.CmdPointer
import analysis.TACCommandGraph
import analysis.icfg.CallGraphBuilder.ByteRange.Companion.withLength
import com.certora.collect.*
import config.Config
import evm.EVM_BYTE_SIZE_INT
import evm.EVM_WORD_SIZE
import evm.EVM_WORD_SIZE_INT
import solver.CounterexampleModel
import utils.*
import vc.data.TACExpr
import vc.data.TACMeta
import vc.data.TACSymbol
import verifier.equivalence.PartialFn.Companion.inject
import java.math.BigInteger

/**
 * Extracts a precise model of a buffer according to the EVM semantics.
 * [PartialFn] is just that, a partial function from an index (relative to the start
 * of the buffer) to the byte at that index.
 */
class PreciseBufferExtraction private constructor(
    val prog: TACCommandGraph,
    val model: CounterexampleModel,
    val start: BigInteger,
    val length: BigInteger
) : BufferExtraction.Visitor<PartialFn<BigInteger, UByte>> {

    operator fun BigInteger.get(i: Int): UByte {
        require(i in 0..<EVM_WORD_SIZE_INT) {
            "out of bounds"
        }
        val shiftAmount = (EVM_WORD_SIZE_INT - i) - 1
        val shift = this.shiftRight(shiftAmount * EVM_BYTE_SIZE_INT)
        return shift.and(0xff.toBigInteger()).intValueExact().toUByte()
    }

    private val bufferRange = start.withLength(length)

    private fun overlapsTarget(start: BigInteger, amount: BigInteger) : Boolean {
        return start.withLength(amount) overlaps bufferRange
    }

    override fun onStore(
        where: CmdPointer,
        idxValue: BigInteger,
        writtenValue: BigInteger,
        writtenExpr: TACExpr
    ): BufferExtraction.VisitResult<PartialFn<BigInteger, UByte>> {
        if(!overlapsTarget(idxValue, EVM_WORD_SIZE)) {
            return BufferExtraction.VisitResult.Continue {
                it.toLeft()
            }
        }
        return BufferExtraction.VisitResult.Continue { acc ->
            val relativeStart = idxValue - start
            val relativeEnd = relativeStart + EVM_WORD_SIZE
            val wrapped = object : PartialFn<BigInteger, UByte> {
                override fun get(k: BigInteger): UByte? {
                    // because relative start can be negative (if idxValue < start)
                    // then we need to make sure a negative k that falls into the range below
                    // isn't accidentally mapped, hence the explicit negative check here
                    if(k < BigInteger.ZERO) {
                        return null
                    }
                    if(k in relativeStart ..< relativeEnd) {
                        return writtenValue[(k - relativeStart).intValueExact()]
                    }
                    return acc[k]
                }
            }
            wrapped.toLeft()
        }
    }

    override fun onLongCopy(
        where: CmdPointer,
        targetLocation: BigInteger,
        length: BigInteger,
        sourceMap: TACSymbol.Var?,
        sourceOffset: BigInteger?
    ): BufferExtraction.VisitResult<PartialFn<BigInteger, UByte>> {
        if(!overlapsTarget(targetLocation, length)) {
            return BufferExtraction.VisitResult.Continue {
                it.toLeft()
            }
        }
        fun fail() : BufferExtraction.VisitResult<PartialFn<BigInteger, UByte>> {
            return BufferExtraction.VisitResult.Continue { acc ->
                val relativeStart = BigInteger.ZERO.max(targetLocation - this.start)
                val relativeEnd = this.length.min(targetLocation + length - this.start)
                object : PartialFn<BigInteger, UByte> {
                    override fun get(k: BigInteger): UByte? {
                        if(k !in relativeStart..<relativeEnd) {
                            return acc[k]
                        }
                        return null
                    }
                }.toLeft()
            }
        }
        if(sourceMap == null || (TACMeta.MCOPY_BUFFER !in sourceMap.meta && TACMeta.EVM_MEMORY !in sourceMap.meta) || sourceOffset == null) {
            return fail()
        }
        val copied = extractBufferModel(
            graph = prog,
            model = model,
            buffer = sourceMap,
            start = sourceOffset,
            where = where,
            len = length
        ).leftOr { return fail() }
        val (srcRelativeStart, targetRelativeStart) = if(targetLocation > start) {
            BigInteger.ZERO to targetLocation - start
        } else {
            start - targetLocation to BigInteger.ZERO
        }
        if(targetLocation == start && length == this.length) {
            return BufferExtraction.VisitResult.Done(copied)
        }
        val relativeEnd = targetRelativeStart + length
        return BufferExtraction.VisitResult.Continue { acc ->
            object : PartialFn<BigInteger, UByte> {
                override fun get(k: BigInteger): UByte? {
                    if(k !in targetRelativeStart ..< relativeEnd) {
                        return acc[k]
                    }
                    val relativeInRange = k - targetRelativeStart
                    return copied[srcRelativeStart + relativeInRange]
                }
            }.toLeft()
        }
    }

    override fun onMapDefinition(where: CmdPointer): Either<PartialFn<BigInteger, UByte>, String> {
        return treapMapOf<BigInteger, UByte>().inject().toLeft()
    }


    companion object {
        /**
         * Extract a [PartialFn] describing the buffer in the bytemap variable [buffer] starting at [start] of length [len].
         * The extract begins at [where] in [graph], using the values found in [model].
         *
         * As a fail-safe, refuses to process buffers of length greater than 1024.
         */
        fun extractBufferModel(
            graph: TACCommandGraph,
            model: CounterexampleModel,
            buffer: TACSymbol.Var,
            start: BigInteger,
            len: BigInteger,
            where: CmdPointer
        ): Either<PartialFn<BigInteger, UByte>, String> {
            if(len > Config.MaxStaticBufferRefinementLength.get().toBigInteger()) {
                return "Refusing to handle huge buffer of size $len".toRight()
            }
            return BufferExtraction.extractBuffer(
                graph = graph,
                model = model,
                where = where,
                visitor = PreciseBufferExtraction(
                    prog = graph,
                    model = model,
                    start = start,
                    length = len
                ),
                bufferVar = buffer
            )
        }
    }
}
