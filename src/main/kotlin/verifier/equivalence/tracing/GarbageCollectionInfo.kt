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
package verifier.equivalence.tracing

import analysis.CommandWithRequiredDecls
import evm.MAX_EVM_UINT256
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.`=`
import tac.Tag
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import datastructures.stdcollections.*
import vc.data.TACProgramCombiners.andThen

/**
 * Mixin that manages the garbage collection instrumentation for some long read.
 */
internal data class GarbageCollectionInfo(
    /**
     * Variables which record the range initialized since the last GC point.
     *
     * AKA: r.gcLower and r.gcUpper
     */
    val writeBoundVars: Pair<TACSymbol.Var, TACSymbol.Var>,
    /**
     * AKA: r.seenGC
     */
    val seenGCVar: TACSymbol.Var,
    /**
     * AKA: r.hashInitProphecy
     */
    val gcInitHashVar: TACSymbol.Var,
    /**
     * AKA: r.hashBackup
     */
    val hashBackupVar: TACSymbol.Var
) : InstrumentationMixin {
    override fun atPrecedingUpdate(
        s: IBufferUpdate,
        overlapSym: TACSymbol.Var,
        writeEndPoint: TACSymbol.Var,
        baseInstrumentation: ILongReadInstrumentation
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val boundsOverlapVar = TACSymbol.Var("gcBoundsOverlap", Tag.Bool).toUnique()
        val (lowerBoundVar, upperBoundVar) = this.writeBoundVars
        return with(TACExprFactTypeCheckedOnlyPrimitives) {
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        boundsOverlapVar,
                        rhs =
                        overlapSym and (seenGCVar eq TACSymbol.One) and (
                            // uninit case
                            (lowerBoundVar eq uninitMarker) or /*
                                init and overlap case, between (lowerBoundVar, upperBoundVar) and (offs, writeEndPoint)
                                which is upperBoundVar > offs and writeEndPoint > lowerBoundVar
                            */
                            ((upperBoundVar gt s.updateLoc) and (writeEndPoint gt lowerBoundVar)) or
                                (upperBoundVar eq s.updateLoc) or
                                (writeEndPoint eq lowerBoundVar)
                        )
                    ),
                    /**
                     * Expand the gc range. This was hand-waved over in the write.
                     * Basically sets r.gcLower = overlap ? min(r.gcLower, s.updateLoc) : r.gcLower
                     *
                     * NB it is fine for r.gcLower to go "below" the start location
                     */
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = lowerBoundVar,
                        rhs =
                        ite(
                            boundsOverlapVar and ((lowerBoundVar eq uninitMarker) or (s.updateLoc lt lowerBoundVar)),
                            s.updateLoc,
                            lowerBoundVar
                        )
                    ),
                    /**
                     * Dual of the above, but we're taking the max this time
                     */
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = upperBoundVar,
                        rhs =
                        ite(
                            boundsOverlapVar and ((upperBoundVar eq uninitMarker) or (writeEndPoint gt upperBoundVar)),
                            writeEndPoint,
                            upperBoundVar
                        )
                    )
                ), boundsOverlapVar
            )
        }

    }

    override fun atLongRead(s: ILongRead): CommandWithRequiredDecls<TACCmd.Simple> {
        val gcInfo = this
        val (lowerBound, upperBound) = gcInfo.writeBoundVars
        val isCompleteInit = TACSymbol.Var("isCompleteInit", Tag.Bool).toUnique("!")

        return with(TACExprFactTypeCheckedOnlyPrimitives) {
            CommandWithRequiredDecls(
                listOf(
                    /**
                     * computes:
                     * `r.seenGC && r.gcLower != MAX_UINT && r.gcLower <= r.bpProphecy && r.gcUpper >= (r.bpProphecy + r.lenProphecy)`
                     * AKA whether we've seen a GC point, and since that point, the writes cover the entire buffer
                     */
                    isCompleteInit `=` ((seenGCVar eq TACSymbol.One) and (
                            not(lowerBound eq uninitMarker) and (
                                    lowerBound le s.loc and ((s.loc add s.length) le upperBound)
                                    )
                            ))
                )
            ) andThen ExprUnfolder.unfoldPlusOneCmd(
                /*
                 * does:
                 * `assume(r.hashInitProphecy == (isComplete ? 0 : r.hashBackup)`
                 * which is the initialization in the EC writeup, folded into a single assume
                 */
                "hashInitBound", ite(
                    isCompleteInit,
                    TACExpr.zeroExpr,
                    gcInfo.hashBackupVar
                ) eq gcInfo.gcInitHashVar
            ) {
                TACCmd.Simple.AssumeCmd(it.s, "init bound for ${s.where}")
            }
        }.merge(
            isCompleteInit
        )
    }

    companion object {
        private val uninitMarker = MAX_EVM_UINT256.asTACExpr
    }

    override val havocInitVars: List<TACSymbol.Var>
        get() = listOf(hashBackupVar, gcInitHashVar)
    override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
        get() = listOf(
            writeBoundVars.first to uninitMarker,
            writeBoundVars.second to uninitMarker,
            seenGCVar to TACSymbol.Zero,
        )
}
