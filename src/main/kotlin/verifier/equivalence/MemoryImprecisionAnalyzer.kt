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
import analysis.icfg.CallGraphBuilder.ByteRange.Companion.withLength
import analysis.maybeExpr
import evm.EVM_WORD_SIZE
import log.*
import solver.CounterexampleModel
import utils.*
import vc.data.*
import verifier.equivalence.data.MethodMarker
import java.math.BigInteger

private val logger = Logger(LoggerTypes.EQUIVALENCE)

/**
 * Tries to find an mload in the counter example which exhibits imprecision
 * that could be addressed via bounded precision windows; as described in the
 * "Bounded Precision Windows" section of the writeup.
 */
internal object MemoryImprecisionAnalyzer {

    /**
     * Find reads in [vcProgram] which, according to [model] exhibit memory imprecison.
     * Scope this search only to the portion of [vcProgram] corresponding to the instrumentation
     * described by [context], which is for method [T].
     */
    internal fun <T: MethodMarker> analyze(
        model: CounterexampleModel,
        vcProgram: CoreTACProgram,
        context: TraceEquivalenceChecker.Instrumentation<T, *>
    ) : Pair<CmdPointer, Int>? {
        /**
         * Find all commands in the subprogram corresponding to [T] that were mload's in the original program.
         */
        val imprecisionCand = vcProgram.parallelLtacStream().filter {
            it.ptr.block.calleeIdx == context.inlinedCallId && it.ptr.block in model.reachableNBIds &&
                MemoryReadNumbering.READ_COUNTER in it.cmd.meta
        }.mapNotNull {
            it.maybeExpr<TACExpr.Select>()
        }.filter {
            (it.exp.base as? TACExpr.Sym.Var)?.s?.meta?.contains(TACMeta.EVM_MEMORY) == true
        }.mapNotNull { readLoc ->
            logger.info {
                "Tracing memory operation for potential memory imprecision $readLoc"
            }
            val (success, readIdx) = readLoc.exp.loc.let(model::evalExprByRhs)
            if(!success || readIdx == null) {
                logger.info { "Couldn't eval location ${readLoc.exp.loc}, giving up" }
                return@mapNotNull null
            }
            logger.info { "Found static offset @ $readLoc: $readIdx, searching now for definitions of ${readLoc.exp.base}" }
            val readRange = readIdx.withLength(EVM_WORD_SIZE)

            fun overlaps(start: BigInteger, len: BigInteger): Boolean {
                return start.withLength(len) overlaps readRange
            }

            /**
             * This diverges from the EC write up as we only trace backwards until we see a write
             * that is non-overlapping. In principle, if there is an intervening write to some unrelated portion of memory
             * we'll cut the window too early, but we can revisit this decision later if need be.
             *
             * As it is, we collect all writes into the buffer being read at `readLoc`. We take the suffix
             * of writes which overlap with the location being read (NB: because we build the trace of writes
             * into the buffer "backwards" from readLoc, we *only* ever get this suffix). If this suffix is non-empty, we
             * count the number of writes which are not precise: i.e., they do not cleanly overlap with the cell being read.
             *
             * We take this number to be the bounded cell size.
             */
            val imprecisionGuess = BufferExtraction.extractBuffer(
                graph = vcProgram.analysisCache.graph,
                bufferVar = readLoc.exp.baseAsTACSymbolVar()!!,
                model = model,
                where = readLoc.ptr,
                visitor = object : BufferExtraction.Visitor<Int> {
                    override fun onStore(
                        where: CmdPointer,
                        idxValue: BigInteger,
                        writtenValue: BigInteger,
                        writtenExpr: TACExpr
                    ): BufferExtraction.VisitResult<Int> {
                        if(overlaps(idxValue, EVM_WORD_SIZE) && idxValue != readIdx) {
                            return BufferExtraction.VisitResult.Continue {
                                (it + 1).toLeft()
                            }
                        } else {
                            return BufferExtraction.VisitResult.Done(0)
                        }
                    }

                    override fun onLongCopy(
                        where: CmdPointer,
                        targetLocation: BigInteger,
                        length: BigInteger,
                        sourceMap: TACSymbol.Var?,
                        sourceOffset: BigInteger?
                    ): BufferExtraction.VisitResult<Int> {
                        val copyRange = targetLocation.withLength(length)
                        // non total overlap
                        if(copyRange overlaps readRange && !(copyRange subsumes readRange)) {
                            return BufferExtraction.VisitResult.Continue {
                                (it + 1).toLeft()
                            }
                        } else {
                            return BufferExtraction.VisitResult.Done(0)
                        }
                    }

                    override fun onMapDefinition(where: CmdPointer): Either<Int, String> {
                        return 0.toLeft()
                    }

                }
            )
            val impreciseCount = when(imprecisionGuess) {
                is Either.Left -> {
                    if(imprecisionGuess.d == 0) {
                        logger.debug {
                            "No interesting previous overlapping found, ignoring $readLoc"
                        }
                        return@mapNotNull null
                    }
                    imprecisionGuess.d
                }
                is Either.Right -> {
                    logger.debug {
                        "Failed tracing from $readLoc because ${imprecisionGuess.d}"
                    }
                    return@mapNotNull null
                }
            }
            readLoc.ptr to impreciseCount
        }.toList()
        val reach = vcProgram.analysisCache.reachability

        /**
         * Find the "first" such imprecision: later imprecision might not actually exist if earlier imprecision
         * is addressed.
         */
        val principal = imprecisionCand.singleOrNull { (cand, _) ->
            imprecisionCand.all { (other, _) ->
                other == cand || reach.canReach(other, cand)
            }
        }
        if(principal == null) {
            logger.info {
                "Starting from $imprecisionCand, couldn't infer principal, giving up"
            }
            return null
        }
        /**
         * Now, get the read number inserted by [MemoryReadNumbering].
         */
        val id = vcProgram.analysisCache.graph.elab(principal.first).cmd.meta[MemoryReadNumbering.READ_COUNTER]
        val origProg = context.originalProgram.program

        /**
         * Use that to find the original location in the method [T] to instrument with this window.
         */
        val origLoc = origProg.parallelLtacStream().filter {
            it.cmd.meta[MemoryReadNumbering.READ_COUNTER] == id
        }.toList().single()
        logger.info {
            "Flagging $origLoc as imprecision location"
        }
        return origLoc.ptr to principal.second
    }
}
