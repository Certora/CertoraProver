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

package rules

import analysis.icfg.InternalCVLSummarizer
import analysis.icfg.Summarization
import config.Config
import config.HardFailMode
import datastructures.stdcollections.*
import log.*
import report.*
import report.dumps.BlockDifficulty
import spec.CVL
import utils.Range
import spec.cvlast.SpecCallSummary
import spec.cvlast.VMParam
import utils.CertoraErrorType
import utils.CertoraException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = Logger(LoggerTypes.SUMMARIZATION)

/**
 * The host contract of a [CVL.SummarySignature].
 */
private sealed interface Contract {

    fun toUIString(): String

    object Any : Contract {
        override fun toUIString(): String = "_"
    }

    @JvmInline
    value class Exact(val name: String) : Contract {
        override fun toUIString(): String = name
    }

}

/**
 * The method's signature of a [CVL.SummarySignature].
 */
private sealed interface Signature {

    fun toUIString(): String

    object Any : Signature {
        override fun toUIString(): String = "_"
    }

    data class NameWithParams(val functionName: String, val params: List<VMParam>) : Signature {
        override fun toUIString(): String = functionName + params.joinToString(", ", "(", ")") {
            it.prettyPrint()
        }
    }

}


/**
 * An entry in the methods block may be represented by one or two [CVL.SummarySignature]s.
 * A [CVL.SummarySignature] is created for two cases- one for the case the underlying function
 * is defined in a contract, second for the case where the function is defined in a library.
 * In those two cases, the sighashes are computed in a different manner. Sometimes they collide,
 * but sometimes they are unique.
 * We would like to consider a summary as used iff at least one of the versions mentioned above
 * of the underlying function is used. That is, if a summarized function foo is invoked,
 * let's say from a library context, then the summary for the contract context will be also
 * considered as used.
 * Thus, we want the two versions (contract and library) for the same function to have the same [SummaryMonitorKey] here.
 */
private data class SummaryMonitorKey(
    val host: Contract,
    val signature: Signature,
    val loc: Range,
    val isExternal: Boolean
) {

    val locationMsg: String
        get() = when (loc) {
            is Range.Range -> {
                "File: ${File(loc.specFile).name} line: ${loc.start.lineForIDE}"
            }

            is Range.Empty -> "unknown location"
        }

    val alertMsg: String
        get() {
            val kind = if (isExternal) { "external" } else { "internal" }
            return "Summarization for $kind calls of ${host.toUIString()}.${signature.toUIString()} is unused"
        }
}

private fun Summarization.AppliedSummary.MethodsBlock.toSummaryMonitorKey(): SummaryMonitorKey =
    (this.summarizedMethod to this.specCallSumm).toSummaryMonitorKey()

private fun Map.Entry<CVL.SummarySignature, SpecCallSummary.ExpressibleInCVL>.toSummaryMonitorKey(): SummaryMonitorKey =
    (this.key to this.value).toSummaryMonitorKey()

private fun Pair<CVL.SummarySignature, SpecCallSummary.ExpressibleInCVL>.toSummaryMonitorKey(): SummaryMonitorKey {
    val (summSig, specCallSumm) = this
    return when (summSig) {
        is CVL.ExternalAnyInContract -> SummaryMonitorKey(
            host = Contract.Exact(summSig.hostContract.name),
            signature = Signature.Any,
            loc = specCallSumm.range,
            isExternal = true
        )

        is CVL.ExternalExact -> {
            SummaryMonitorKey(
                host = Contract.Exact(summSig.signature.qualifiedMethodName.host.name),
                signature = Signature.NameWithParams(summSig.signature.functionName, summSig.signature.params),
                loc = specCallSumm.range,
                isExternal = true
            )
        }

        is CVL.ExternalWildcard -> {
            SummaryMonitorKey(
                host = Contract.Any,
                signature = Signature.NameWithParams(summSig.signature.functionName, summSig.signature.params),
                loc = specCallSumm.range,
                isExternal = true
            )
        }

        is CVL.InternalExact -> {
            SummaryMonitorKey(
                host = Contract.Exact(summSig.signature.qualifiedMethodName.host.name),
                signature = Signature.NameWithParams(summSig.signature.functionName, summSig.signature.params),
                loc = specCallSumm.range,
                isExternal = false
            )
        }

        is CVL.InternalWildcard -> {
            SummaryMonitorKey(
                host = Contract.Any,
                signature = Signature.NameWithParams(summSig.signature.functionName, summSig.signature.params),
                loc = specCallSumm.range,
                isExternal = false
            )
        }

        CVL.ExternalUnresolved ->
            SummaryMonitorKey(
                host = Contract.Any,
                signature = Signature.Any,
                loc = specCallSumm.range,
                isExternal = true
            )
    }
}



object AutosummarizedMonitor {
    private val autoInternalSummaries: ConcurrentHashMap<SummaryMonitorKey, Pair<CVL.InternalExact,BlockDifficulty>>
        = ConcurrentHashMap<SummaryMonitorKey, Pair<CVL.InternalExact,BlockDifficulty>>()

    private fun SummaryMonitorKey.getSummaryMonitorKeyForAutosummarizer() =
        this.copy(loc = Range.Empty()) // remove the cvl loc as sometimes the same pair can map to slightly different difficulties, leading to double keys

    // xxx is "contains" not safe in concurrent hashmap that we do it like this both here and in SummaryMonitor?
    fun hasAutosummary(appliedSummary: Summarization.AppliedSummary.MethodsBlock) =
        autoInternalSummaries.computeIfPresent(appliedSummary.toSummaryMonitorKey().getSummaryMonitorKeyForAutosummarizer()) { _, it -> it } != null

    fun addAutosummarizedMethods(autosummarizedMethods: Map<out CVL.InternalExact, InternalCVLSummarizer.EarlySummarization.AutosummaryWithDifficulty>) {
        autosummarizedMethods.forEachEntry { autosummarizedMethodPair ->
            // the summary itself is necessary to get the summary-monitor key
            val summaryMonitorKey: SummaryMonitorKey = (autosummarizedMethodPair.key to autosummarizedMethodPair.value.summaryToApply).toSummaryMonitorKey()
                .getSummaryMonitorKeyForAutosummarizer()
            autoInternalSummaries[summaryMonitorKey] = autosummarizedMethodPair.key to autosummarizedMethodPair.value.difficulty
        }
    }

    fun reportSummarizedMethods(cvl : CVL) {
        // hints are not multiline, which is very annoying
        if (autoInternalSummaries.isNotEmpty()) {
            val allInternalFuncs = cvl.getAllInternalFunctions()
            CVTAlertReporter.reportAlert(
                CVTAlertType.SUMMARIZATION,
                CVTAlertSeverity.INFO,
                null,
                "Auto-summarized as NONDET the following internal pure/view functions found to be difficult " +
                    "(threshold: ${Config.AutoNondetMinimalDifficulty.get()})",
                autoInternalSummaries.entries.joinToString("\n") { toNondetEntry ->
                    val funcSig = toNondetEntry.value.first.signature
                    val returnType = allInternalFuncs.firstOrNull { it.method.getPrettyName() == funcSig.prettyPrint() }
                            ?.method?.returnsString() ?: "/* could not restore return types */"
                    val fqn = funcSig.prettyPrintFullyQualifiedName()
                    "function $fqn internal $returnType => NONDET /* difficulty ${toNondetEntry.value.second.computeDifficultyScore()} */;"
                }
            )
        } else if (Config.AutoNondetDifficultInternalFuncs.get()) {
            // if no auto internal summaries were instrumented, but the option is enabled, show a suitable alert
            CVTAlertReporter.reportAlert(
                CVTAlertType.SUMMARIZATION,
                CVTAlertSeverity.INFO,
                null,
                "No internal pure/view functions were auto-summarized as NONDET, you may wish to re-run" +
                    " with an adjusted difficulty using `--nondet_minimal_difficulty`.",
                "Current difficulty is set to ${Config.AutoNondetMinimalDifficulty.get()}"
            )
        }
    }
}


/**
 * A monitor for CVL, to detect which summaries are unapplied across all the rules' programs.
 * [summaryKeyToIsApplied] get updated during the check process
 * done by the [RuleChecker].
 */
class SummaryMonitor(
    externalCvlSummaries: Map<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>,
    internalCvlSummaries: Map<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
    unresolvedSummary: Map<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>,
    ) {

    private val summaryKeyToIsApplied: ConcurrentHashMap<SummaryMonitorKey, Boolean> =
        ConcurrentHashMap<SummaryMonitorKey, Boolean>(externalCvlSummaries.size + internalCvlSummaries.size
            + unresolvedSummary.size).apply {
            externalCvlSummaries.forEachEntry {
                this[it.toSummaryMonitorKey()] = false
            }
            internalCvlSummaries.forEachEntry {
                this[it.toSummaryMonitorKey()] = false
            }
            unresolvedSummary.forEachEntry {
                this[it.toSummaryMonitorKey()] = false
            }
        }

    /**
     * Declare [appliedSummary] as used in the full program.
     */
    fun declareUseOf(appliedSummary: Summarization.AppliedSummary.MethodsBlock) {
        val summaryMonitorKey: SummaryMonitorKey = appliedSummary.toSummaryMonitorKey()

        if(summaryKeyToIsApplied.computeIfPresent(summaryMonitorKey) { _, _ -> true } != true) {
            if (Config.AutoNondetDifficultInternalFuncs.get() && (AutosummarizedMonitor.hasAutosummary(appliedSummary) || Config.getIsUseCache())) {
                logger.debug { "Expected $summaryMonitorKey to already be present in [summaryKeyToIsApplied]," +
                    " but it is auto-summarized (or was autosummarized in a previous run and cached)" }
            } else {
                throw IllegalStateException("Expected $summaryMonitorKey to already be present in [summaryKeyToIsApplied]")
            }

        }
    }

    // report unused summaries
    fun reportUnusedSumm() {

        val unusedSummaries = summaryKeyToIsApplied.filter { (_, isUsed) -> !isUsed }.keys
        if (unusedSummaries.isNotEmpty()) {
            // there is an unused summary
            val unusedSummHardFailModeEnabled = Config.UnusedSummaryHardFail.get()
            val cvtAlertSeverity = if (unusedSummHardFailModeEnabled == HardFailMode.ON) {
                CVTAlertSeverity.ERROR
            } else {
                CVTAlertSeverity.WARNING
            }

            unusedSummaries.forEach {
                CVTAlertReporter.reportAlert(
                    CVTAlertType.SUMMARIZATION,
                    cvtAlertSeverity,
                    it.loc as? TreeViewLocation,
                    it.alertMsg,
                    null
                )
            }

            if (unusedSummHardFailModeEnabled == HardFailMode.ON) {
                throw CertoraException(
                    CertoraErrorType.UNUSED_SUMMARY,
                    unusedSummaries.joinToString(prefix = "Found unused summaries: ") { it.locationMsg })
            }
        }
    }
}
