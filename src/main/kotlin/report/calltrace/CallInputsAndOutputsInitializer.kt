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

package report.calltrace

import analysis.CmdPointer
import analysis.ExprView
import analysis.icfg.Inliner
import analysis.maybeExpr
import bridge.EVMExternalMethodInfo
import datastructures.stdcollections.*
import evm.DEFAULT_SIGHASH_SIZE
import log.Logger
import log.LoggerTypes
import report.calltrace.CallInstance.InvokingInstance.SolidityInvokeInstance
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import tac.NBId
import utils.`to?`
import vc.data.*
import java.math.BigInteger
import java.math.BigInteger.ZERO

private val logger = Logger(LoggerTypes.CALLTRACE)

/**
 * Initializes an instance of [CallInputsAndOutputs] by creating the [ExternalCallMapping]
 * of each [SolidityInvokeInstance.External] call, and populating it with data extracted from annotations.
 *
 * [initialize] should only be called once for each instance.
 */
class Initializer(
    private val blocks: List<NBId>,
    private val model: CounterexampleModel,
    private val analysisCache: TACCommandGraphAnalysisCache,
    private val scene: ISceneIdentifiers,
) {
    fun initialize(callIO: CallInputsAndOutputs) {
        for ((recordPtr, record) in stackPushRecords()) {
            val callIndex = record.calleeId
            val info = record.callee.resolveIn(scene)?.evmExternalMethodInfo ?: continue
            callIO.initExternalCall(callIndex, info)

            val summary = record.summary ?: continue
            val mapping = callIO.externalCall(callIndex)!!

            preprocessCalldataFamily(mapping.calldataFamily, summary, recordPtr, info)
            preprocessReturndataFamily(mapping.returndataFamily, summary, recordPtr, info)
        }
    }

    private fun calculateBoundaries(baseSymbol: TACSymbol, offsetSymbol: TACSymbol, sizeSymbol: TACSymbol, info: EVMExternalMethodInfo): BufferBoundaries? {
        val bufferBase = baseSymbol as? TACSymbol.Var
        val bufferStartOffset = offsetSymbol.numericValueInModel()
        val totalSizeOfWordsInBuffer = sizeSymbol.numericValueInModel()

        if (bufferBase == null || bufferStartOffset == null || totalSizeOfWordsInBuffer == null) {
            logger.error { "preprocessing found unexpected symbols in summary of ${info.name}" }
            return null
        }

        if (!bufferStartOffset.isByteAligned(ZERO)) {
            logger.error { "memory offset extracted in ${info.name} is not aligned to word size" }
            return null
        }

        return BufferBoundaries(bufferBase, bufferStartOffset, totalSizeOfWordsInBuffer)
    }

    private fun preprocessCalldataFamily(family: DataFamily, summary: CallSummary, pushRecordPtr: CmdPointer, info: EVMExternalMethodInfo) {
        val boundaries = calculateBoundaries(summary.inBase, summary.inOffset, summary.inSize, info) ?: return

        val bufferOffsets = mutableMapOf<BigInteger, TACSymbol>()
        extractInputs(boundaries.bufferBase, pushRecordPtr, boundaries, bufferOffsets)

        //remove sighash symbol
        bufferOffsets.remove(ZERO)

        for ((byteOffset, scalar) in bufferOffsets) {
            family.addScalar(byteOffset, scalar)
        }
    }

    private fun preprocessReturndataFamily(family: DataFamily, summary: CallSummary, recordPtr: CmdPointer, info: EVMExternalMethodInfo) {
        val boundaries = calculateBoundaries(summary.outBase, summary.outOffset, summary.outSize, info) ?: return

        val bufferOffsets = mutableMapOf<BigInteger, TACSymbol>()
        extractOutputs(boundaries.bufferBase, recordPtr, boundaries, bufferOffsets)

        for ((byteOffset, scalar) in bufferOffsets) {
            family.addScalar(byteOffset, scalar)
        }
    }

    /**
     * Recursively walks over the words of a buffer, in reverse order, until it reaches the beginning of this buffer
     *
     * As it walks, populates [bufferOffsets] - a map from a buffer word index to
     * the [TACSymbol] defined at that location in the buffer.
     */
    private tailrec fun extractInputs(base: TACSymbol.Var, basePtr: CmdPointer, boundaries: BufferBoundaries, bufferOffsets: MutableMap<BigInteger, TACSymbol>) {
        val defSites = analysisCache.def.defSitesOf(base, basePtr)
        val (nextPtr, storeExprView) = defSites.elaborateToStoreExprView() ?: return

        val storeExpr = storeExprView.exp
        if (storeExpr.loc is TACExpr.Sym && storeExpr.value is TACExpr.Sym) {
            val storeLoc = storeExpr.loc.s.numericValueInModel() ?: return

            if (storeLoc in boundaries.bufferStartOffset..boundaries.offsetOfLastInputWord) {
                val offsetInBuffer = storeLoc - boundaries.bufferStartOffset
                bufferOffsets[offsetInBuffer] = storeExpr.value.s

                val nextBase = storeExpr.base as? TACExpr.Sym.Var
                if (nextBase != null) {
                    extractInputs(nextBase.s, nextPtr, boundaries, bufferOffsets)
                }
            }
        }
    }

    /**
     * Recursively walks over the words of a buffer, in increasing order, until it reaches the end of this buffer
     *
     * As it walks, populates [bufferOffsets] - a map from a buffer word index to
     * the [TACSymbol] defined at that location in the buffer.
     */
    private tailrec fun extractOutputs(base: TACSymbol.Var, basePtr: CmdPointer, boundaries: BufferBoundaries, bufferOffsets: MutableMap<BigInteger, TACSymbol>) {
        val useSites = analysisCache.use.useSitesAfter(base, basePtr)
        val (nextPtr, storeExprView) = useSites.elaborateToStoreExprView() ?: return

        val storeExpr = storeExprView.exp
        if (storeExpr.loc is TACExpr.Sym && storeExpr.value is TACExpr.Sym) {
            val storeLoc = storeExpr.loc.s.numericValueInModel() ?: return

            if (storeLoc in boundaries.bufferStartOffset..boundaries.offsetOfLastOutputWord) {
                val offsetInBuffer = storeLoc - boundaries.bufferStartOffset
                bufferOffsets[offsetInBuffer] = storeExpr.value.s

                extractOutputs(storeExprView.lhs, nextPtr, boundaries, bufferOffsets)
            }
        }
    }

    private fun stackPushRecords(): Sequence<Pair<CmdPointer, Inliner.CallStack.PushRecord>> {
        val firstCmd = CmdPointer(blocks.first(), 0)

        return analysisCache
            .graph
            .iterateFrom(firstCmd, blocks)
            .mapNotNull { (ptr, cmd) ->
                val annotation = (cmd as? TACCmd.Simple.AnnotationCmd)?.annot

                if (annotation != null && annotation.k == Inliner.CallStack.STACK_PUSH) {
                    val record = annotation.v as Inliner.CallStack.PushRecord
                    ptr to record
                } else {
                    null
                }
            }
    }

    private fun TACSymbol.numericValueInModel() = model.valueAsBigInteger(this).leftOrNull()

    private fun Set<CmdPointer>.elaborateToStoreExprView(): Pair<CmdPointer, ExprView<TACExpr.Store>>? =
        this.filter { ptr -> ptr.block in blocks }
            .firstNotNullOfOrNull { ptr -> ptr `to?` analysisCache.graph.elab(ptr).maybeExpr() }
}

private data class BufferBoundaries(val bufferBase: TACSymbol.Var, val bufferStartOffset: BigInteger, val totalSizeOfWordsInBuffer: BigInteger) {
    val offsetOfLastInputWord get() = bufferStartOffset + totalSizeOfWordsInBuffer + DEFAULT_SIGHASH_SIZE
    val offsetOfLastOutputWord get() = bufferStartOffset + totalSizeOfWordsInBuffer
}
