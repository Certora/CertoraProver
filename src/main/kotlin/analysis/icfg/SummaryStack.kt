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
import allocator.GeneratedBy
import analysis.ip.InternalFuncRet
import com.certora.collect.*
import datastructures.stdcollections.*
import report.callresolution.CallResolutionTableSummaryInfo
import scene.ISceneIdentifiers
import spec.CVL
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.SpecCallSummary
import tac.MetaKey
import utils.escapeQuotes
import vc.data.*
import java.io.Serializable

object SummaryStack {
    val START_EXTERNAL_SUMMARY = MetaKey<SummaryStart.External>("call.trace.external.summary.start", restore = true)
    val START_INTERNAL_SUMMARY = MetaKey<SummaryStart.Internal>("call.trace.internal.summary.start", restore = true)

    sealed class SummaryStart : Serializable, TransformableVarEntityWithSupport<SummaryStart> {
        abstract val callResolutionTableInfo: CallResolutionTableSummaryInfo

        abstract val appliedSummary: Summarization.AppliedSummary
        val summarySignature: CVL.SummarySignature?
            get() = (appliedSummary as? Summarization.AppliedSummary.MethodsBlock)?.summarizedMethod

        val summary: SpecCallSummary
            get() = appliedSummary.specCallSumm
        /**
         * Call-site from the source of the underlined CallCore command.
         */
        abstract val callSiteSrc: TACMetaInfo?

        /**
         * Tries to resolve information related to the underlined callee of this
         * [SummaryStart] for the UI, in the following order:
         * 1. The callee's method-signature.
         * 2. Call-site from the source where the callee is invoked.
         * 3. The callee's sighash.
         */
        abstract fun toUIString(scene: ISceneIdentifiers): String

        data class External(
            val callNode: CallSummary,
            override val callResolutionTableInfo: CallResolutionTableSummaryInfo,
            override val appliedSummary: Summarization.AppliedSummary
        ) : SummaryStart(), UniqueIdEntity<External> {

            override fun mapId(f: (Any, Int, () -> Int) -> Int): External {
                return External(
                    callNode = callNode.mapId(f),
                    callResolutionTableInfo, appliedSummary
                )
            }

            override val callSiteSrc: TACMetaInfo? = this.callNode.origCallcore?.metaSrcInfo

            override fun toUIString(scene: ISceneIdentifiers): String {
                val singleOrNullSigResolution = this.callNode.sigResolution.singleOrNull()
                return singleOrNullSigResolution?.let {
                    scene.getMethods(it).firstOrNull()?.soliditySignature
                } ?: callSiteSrc?.getSourceDetails()?.sanitizedContentWithLoc?.escapeQuotes() ?: singleOrNullSigResolution?.let {
                    "sighash 0x${
                        it.toString(
                            16
                        )
                    }"
                } ?: "unknown"
            }

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): External = copy(callNode = callNode.transformSymbols(f))

            override val support: Set<TACSymbol.Var> = callNode.variables
        }

        data class Internal(
            override val callSiteSrc: TACMetaInfo?,
            val methodSignature: QualifiedMethodSignature,
            override val callResolutionTableInfo: CallResolutionTableSummaryInfo,
            override val appliedSummary: Summarization.AppliedSummary
        ) : SummaryStart() {

            override val support: Set<TACSymbol.Var> = setOf()

            override fun toUIString(scene: ISceneIdentifiers): String = methodSignature.functionName.toString()
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): SummaryStart {
                return this
            }
        }
    }

    @Treapable
    sealed class SummaryEnd : Serializable {

        data class External(
            val appliedSummary: Summarization.AppliedSummary,
            @GeneratedBy(Allocator.Id.CALL_SUMMARIES)
            val summaryId: Int
        ) : SummaryEnd(), UniqueIdEntity<External> {
            override fun mapId(f: (Any, Int, () -> Int) -> Int): External {
                return External(appliedSummary, summaryId = f(Allocator.Id.CALL_SUMMARIES, summaryId) { Allocator.getFreshId(Allocator.Id.CALL_SUMMARIES) })
            }

        }

        /**
         * Marks the end of an internal summary - on the normal return path, [rets] should not be null, if there are no returns it should be an empty list.
         * It is nullable to encode undefined return values when we exit the summary with a revert.
         */
        data class Internal(val rets: List<InternalFuncRet>?, val methodSignature: QualifiedMethodSignature) : SummaryEnd(),
            TransformableVarEntityWithSupport<Internal> {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Internal = this.copy(rets = rets?.map { it.transformSymbols(f) })

            override val support: Set<TACSymbol.Var> = rets?.map { it.s }?.toSet().orEmpty()
        }
    }

    val END_EXTERNAL_SUMMARY = MetaKey<SummaryEnd.External>("call.trace.external.summary.end", restore = true)
    val END_INTERNAL_SUMMARY = MetaKey<SummaryEnd.Internal>("call.trace.internal.summary.end")
}
