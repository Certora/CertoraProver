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

import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import evm.MAX_EVM_UINT256
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.`=`
import tac.Tag
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import datastructures.stdcollections.*
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten
import vc.data.TACProgramCombiners.wrap

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

    val gcSiteInfo: Map<CmdPointer, InstrumentationPoint>
) : InstrumentationMixin {
    private data class BackupPair(
        val default: TACExpr,
        val backupVar: TACSymbol.Var,
        val initVar: TACSymbol.Var
    )

    data class InstrumentationPoint(
        val gcHashBackupVar: TACSymbol.Var,
        val gcHashInitVar: TACSymbol.Var,

        val gcAlignedBackupVar: TACSymbol.Var,
        val gcAlignedInitVar: TACSymbol.Var,
        val gcPointId: Int
    )

    private val InstrumentationPoint.backups get() = listOf(
        BackupPair(
            default = 0.asTACExpr,
            backupVar = gcHashBackupVar,
            initVar = gcHashInitVar
        ),
        BackupPair(
            default = TACSymbol.True.asSym(),
            backupVar = gcAlignedBackupVar,
            initVar = gcAlignedInitVar
        )
    )

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
                        overlapSym and (not(seenGCVar eq TACSymbol.Zero)) and (
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
        }.wrap("GC update for ${baseInstrumentation.id}")

    }

    private fun generateInitCond(
        id: Int,
        backup: BackupPair,
        predVar: TACSymbol.Var
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        return ExprUnfolder.unfoldPlusOneCmd(
            "gcInit",
            TXF {
                backup.initVar eq ite(
                    predVar and (seenGCVar eq id),
                    backup.default,
                    backup.backupVar
                )
            }
        ) {
            TACCmd.Simple.AssumeCmd(it.s, "init assume")
        }
    }

    override fun atLongRead(s: ILongRead): CommandWithRequiredDecls<TACCmd.Simple> {
        val gcInfo = this
        val (lowerBound, upperBound) = gcInfo.writeBoundVars
        val isCompleteInit = TACSymbol.Var("isCompleteInit", Tag.Bool).toUnique("!")

        val initTerms = gcSiteInfo.flatMap { (_, ip) ->
            ip.backups.map { bkp ->
                generateInitCond(ip.gcPointId, bkp, isCompleteInit)
            }
        }.flatten()

        val toRet = with(TACExprFactTypeCheckedOnlyPrimitives) {
            CommandWithRequiredDecls(
                listOf(
                    /**
                     * computes:
                     * `r.seenGC && r.gcLower != MAX_UINT && r.gcLower <= r.bpProphecy && r.gcUpper >= (r.bpProphecy + r.lenProphecy)`
                     * AKA whether we've seen a GC point, and since that point, the writes cover the entire buffer
                     */
                    isCompleteInit `=` (
                            not(lowerBound eq uninitMarker) and (
                                    lowerBound le s.loc and ((s.loc add s.length) le upperBound)
                                )
                            )
                )
            ) andThen initTerms
        }.merge(
            isCompleteInit
        )
        return toRet.wrap("Init GC prophecy for ${s.id}")
    }

    companion object {
        val uninitMarker = MAX_EVM_UINT256.asTACExpr
    }

    override val havocInitVars: List<TACSymbol.Var>
        get() = gcSiteInfo.flatMap { (_, ip) ->
            listOf(ip.gcHashInitVar, ip.gcHashBackupVar, ip.gcAlignedInitVar, ip.gcAlignedBackupVar)
        }
    override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
        get() = listOf(
            writeBoundVars.first to uninitMarker,
            writeBoundVars.second to uninitMarker,
            seenGCVar to TACSymbol.Zero,
        )
}
