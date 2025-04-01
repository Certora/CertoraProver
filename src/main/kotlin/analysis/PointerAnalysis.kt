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

package analysis

import analysis.alloc.AllocationAnalysis
import analysis.pta.*
import config.Config
import instrumentation.transformers.NoteModifierRewriter
import log.Logger
import log.LoggerTypes
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import scene.ITACMethod
import scene.MethodAttribute
import utils.CertoraException
import vc.data.CoreTACProgram
import vc.data.getSourceHintWithRange

private val logger = Logger(LoggerTypes.POINTS_TO)

/**
 * Used to distinguish between different applications/motivations to run the PTA, so that error messages could
 * be more precise and friendly.
 */
enum class PTARunPurpose {
    CGB, // linking/call-resolution
    OPTIMIZATION,
    ABI, // DP
    TEST, // Unit testing
    HASHING, // DisciplinedHashModel
    CGB_INDIRECT, // may be used indirectly for linking
}

object PointerAnalysis {
    fun runAnalysis(p: ITACMethod, purpose: PTARunPurpose) : IPointsToInformation {
        if(p.attribute == MethodAttribute.Unique.Constructor) {
            return TrivialPointsToInformation
        }
        val code = p.code as CoreTACProgram
        val graph = code.analysisCache.graph
        val ai = AllocationAnalysis.runAnalysis(graph) ?: return TrivialPointsToInformation
        val pta = PointsToAnalysis(
            graph = graph,
            method = p,
            allocSites = ai.abstractAllocations,
            scratchSite = ai.scratchReads,
            initialFreePointerValue = ai.initialFreePointerValue,
        )

        pta.failures.forEach { x ->
            if(p.attribute != MethodAttribute.Unique.Constructor) {
                val prefix = when(purpose) {
                    PTARunPurpose.CGB -> "Pointer analysis for call resolution failed"
                    PTARunPurpose.OPTIMIZATION -> "Pointer analysis for optimization failed"
                    PTARunPurpose.ABI -> "Pointer analysis for ABI/DP failed"
                    PTARunPurpose.TEST -> "Pointer analysis test failed"
                    PTARunPurpose.HASHING -> "Pointer analysis for correct hashing failed"
                    PTARunPurpose.CGB_INDIRECT -> "Pointer analysis failed, could pose a linking issue in a caller"
                }
                val tipsToUser = getPotentialTips(p)
                val tipsOrErrorCode = tipsToUser.joinToString(". ").ifEmpty {
                    "Error code ${CertoraException.getErrorCodeForException(x)}"
                }
                val addAlertInfo = getSourceHintWithRange(x.where, graph, p)

                val message = prefix + if(x.enclosingInternalMethod != null){
                    " in internal function ${x.enclosingInternalMethod.methodSignature} (when called from external function ${p.soliditySignature ?: p.name} of contract ${p.getContainingContract().name}). "
                } else{
                    " in contract ${p.getContainingContract().name}, function ${p.soliditySignature ?: p.name}. "
                } + "${addAlertInfo.additionalUserFacingMessage} $tipsOrErrorCode"

                CVTAlertReporter.reportAlert(
                    // Group the different PTARunPurpose into respective alert types for the Rule report.
                    when(purpose){
                        PTARunPurpose.CGB,
                        PTARunPurpose.CGB_INDIRECT -> CVTAlertType.CALL_GRAPH
                        PTARunPurpose.OPTIMIZATION -> CVTAlertType.PTA_FOR_OPTIMIZATIONS
                        PTARunPurpose.ABI,
                        PTARunPurpose.TEST,
                        PTARunPurpose.HASHING -> CVTAlertType.ANALYSIS
                    },
                    CVTAlertSeverity.WARNING,
                    addAlertInfo.range,
                    message
                )
                val fatalMessage = "(fatal: discarding all pointer analysis) ".takeIf { x.fatal }.orEmpty()
                logger.warn(x) {
                    "$fatalMessage$prefix while analyzing ${p.getContainingContract().name}.${p.name} @ ${x.where}"
                }
            } else {
                logger.info(x) {
                    "The points-to analysis failed (as expected) on a constructor of ${p.getContainingContract().name}"
                }
            }
        }

        return when {
            pta.results.isEmpty() -> TrivialPointsToInformation
            pta.failures.any { it.fatal } -> TrivialPointsToInformation
            pta.failures.isNotEmpty() -> FlowPointsToInformation(pta, graph, ai, isCompleteSuccess = false)
            else -> FlowPointsToInformation(pta, graph, ai, isCompleteSuccess = true)
        }
    }

    private fun getPotentialTips(p: ITACMethod): List<String> {
        val c = p.code as CoreTACProgram
        fun msizeTip(): String? =
            // check if option is diabled but would likely do something in this code if were enabled:
            if (!Config.RewriteMSizeAllocations.get() && NoteModifierRewriter.hasMsizeBasedFPUpdates(c)) {
                val optName = Config.RewriteMSizeAllocations.userFacingName()
                "You may workaround the issue by enabling the option $optName"
            } else {
                null
            }

        return listOfNotNull(msizeTip())
    }
}
