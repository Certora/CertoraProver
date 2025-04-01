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

package report.callresolution

import analysis.CmdPointer
import analysis.icfg.InlinedMethodCallStack
import analysis.icfg.SummaryApplicationReason
import analysis.icfg.SummaryStack
import log.*
import report.callresolution.CallResolutionTableWithExampleMeta.ExampleMeta
import scene.ISceneIdentifiers
import utils.mapToSet
import vc.data.CoreTACProgram
import datastructures.stdcollections.*
import report.RuleAlertReport
import report.calltrace.CallInstance
import spec.rules.IRule
import vc.data.MethodRef

private val logger = Logger(LoggerTypes.UI)

/**
 * An entry in a [CallResolutionTable].
 * [caller] and [callee] are the source and the target of the CallCore command,
 * which is invoked at the call-site [callSite].
 * [summary] is the summary applied on this call.
 * [resolutionComments] are some descriptions of the summary [summary] itself,
 * such as havoc type and use decision.
 */
sealed interface CallResolutionTableRow {
    val caller: String
    val callSite: CallSite?
    val callee: Callee
    val summary: String
    val resolutionComments: Map<String, String>
    val status: Status

    fun toBase(): CallResolutionTableBase.Row

    val alertReport: RuleAlertReport.Single<*>?
        get() = status.alertReport

    /**
     * A status of an entry in the Call Resolution Table, referring to the underlying summary status
     * and the callee resolution status.
     */
    sealed class Status {

        abstract val alertReport: RuleAlertReport.Single<*>?
        /**
         * Whether this [Status] should be shown as a warning
         * in the [CallResolutionTable].
         */
        abstract fun isWarning(): Boolean

        object Ok : Status() {
            override val alertReport: RuleAlertReport.Single<*>?
                get() = null
            override fun isWarning(): Boolean = false
            fun readResolve(): Any = Ok
            override fun hashCode() = utils.hashObject(this)
        }

        /**
         * A report alert that may be displayed in the TreeView report under the problems view, to help overcome possible unexpected behaviors.
         */
        sealed class NotOk : Status() {
            abstract val msg: String

            /** Whether to report this [Status.NotOk] in the problems view in the report. */
            abstract val shouldReport: Boolean

            override fun isWarning(): Boolean = true

            data class Error(
                override val msg: String,
                override val shouldReport: Boolean
            ) : NotOk() {
                override val alertReport: RuleAlertReport.Error?
                    get() = if (shouldReport) {
                        RuleAlertReport.Error(msg = msg, throwable = null)
                    } else {
                        null
                    }
            }
            data class Warning(
                override val msg: String,
                override val shouldReport: Boolean
            ) : NotOk() {
                override val alertReport: RuleAlertReport.Warning?
                    get() = if (shouldReport) {
                        RuleAlertReport.Warning(msg = msg, throwable = null)
                    } else {
                        null
                    }
            }
        }

    }
}



/**
 * A table containing summarized calls from a TACProgram, displayed
 * as part of the report.
 */
sealed interface CallResolutionTable<T : CallResolutionTableRow> {

    val rows: Set<T>
    val alertReport: RuleAlertReport<*>?
        get() = RuleAlertReport(rows.mapNotNull { it.alertReport })

    fun toCallResTableReporterView(
        exampleMeta: ExampleMeta = ExampleMeta.None
    ): PerRuleCallResolutionReportView

    /**
     * Returns a filtered version of this CallResolution table, where the filter criterion
     * is whether an entry represents a warning or not ([isWarning]).
     */
    fun copyAndFilterTable(isWarning: Boolean): CallResolutionTable<T>

    /**
     * A factory of [CallResolutionTable]s, Given a TACProgram derived from a compiled rule.
     */
    class Factory(compiledRuleProg: CoreTACProgram, scene: ISceneIdentifiers, rule: IRule) {

        /**
         * A map from an entry of a [CallResolutionTableBase] to an id.
         */
        private val baseRowToId: MutableMap<CallResolutionTableBase.Row, Int> = mutableMapOf()

        /**
         * A map from a [CmdPointer] to an id.
         */
        private val cmdPtrToId: MutableMap<CmdPointer, Int> = mutableMapOf()

        init {
            fun CallResolutionTableRow.Status.NotOk.statusAlertMsgOfRow(caller: String, srcLine: Int?): String =
                "[Call Resolution Table][Caller: $caller${(srcLine?.let { " (line:$it)" }.orEmpty())}]: $msg"

            val graph = compiledRuleProg.analysisCache.graph
            val callers = InlinedMethodCallStack(graph)
            // get views of all the annotations which wrap summaries from the compiled rule
            val topoSortedSummaryStartAnnotCmds = compiledRuleProg.topoSortedSummaryStart()

            var rowIdAllocator = 0
            topoSortedSummaryStartAnnotCmds.forEach { summStartAnnotCmd ->
                val caller = callers.currentCaller(summStartAnnotCmd.ptr)?.ref
                    ?.let { it as? MethodRef }?.getContractAndMethod(scene)
                    ?.let { (c, m) -> "${c.name}.${(m.evmExternalMethodInfo?.getPrettyName() ?: m.name)}" }
                    ?: "unknown caller"
                logger.info { "Caller: $caller" }
                val summStart = summStartAnnotCmd.cmd.annot.v as SummaryStack.SummaryStart
                val callResolutionTableInfo = summStart.callResolutionTableInfo
                val callees = Callee(
                    summStart = summStart,
                    prog = compiledRuleProg,
                    scene = scene,
                    ruleName = rule.declarationId,
                    methodsBlockSignature = (callResolutionTableInfo.applicationReason as? SummaryApplicationReason.Spec)?.methodSignature
                )
                callees.forEach{
                    callee ->
                    val callSiteSrc = summStart.callSiteSrc?.getSourceDetails()?.let(::CallSite)
                    /* Suppose a public Solidity function get summarized and is called from the CVL.
                        According to our instrumentation, the external version of the function (public functions are both external and internal)
                        will be invoked, then, it will "call" (jump to) its internal version, which the inliner is supposed to replace by the underlined
                        summary code. In such a case, the caller and the callee will be the same, and the call-site will be the whole caller's function
                        (in the form: function [function_name](params) visibility { body })
                        We want to avoid of having such entries.
                     */
                    //todo: detect cases where the external version of a public function jumps to the
                    // internal version of the function, in earlier phase
                    @Suppress("AvoidProblematicStringFunctions")
                    val callSite = callSiteSrc?.takeUnless {
                        it.srcSnippet.startsWith("function ").also { startWithFunction ->
                            if (startWithFunction) {
                                logger.warn {
                                    "Encountered a call-site that presumably maps to a function definition in the contracts' source code. " +
                                        "Omitted its source snippet from the call resolution table of ${rule.declarationId}. " +
                                        "Caller: $caller Callee: ${callee.toUIString()}; Source: ${callSiteSrc.srcSnippet}"
                                }
                            }
                        }
                    }
                    val status = callResolutionTableInfo.callResolutionTableRowStatus
                    if (status is CallResolutionTableRow.Status.NotOk) {
                        val errorMessage = status.statusAlertMsgOfRow(
                            caller,
                            callSite?.loc?.lineNumber
                        )
                        Logger.regression { "[Rule ${rule.declarationId}]: $errorMessage" }
                    }
                    val baseRow = CallResolutionTableBase.Row(
                        caller = caller,
                        callSite = callSite,
                        callee = callee,
                        summary = summStart.summary.toUIString(),
                        resolutionComments = callee.info + callResolutionTableInfo.info,
                        status = status
                    )
                    val baseRowId = baseRowToId.computeIfAbsent(baseRow) { rowIdAllocator++ }
                    cmdPtrToId[summStartAnnotCmd.ptr] = baseRowId
                }
            }
        }

        fun tableBase(): CallResolutionTableBase =
            CallResolutionTableBase(rows = baseRowToId.keys.toSet())

        fun exampleMetaOf(summariesInCallTrace: List<CallInstance.InvokingInstance.SummaryInstance>): ExampleMeta {
            if (summariesInCallTrace.isEmpty()) {
                return ExampleMeta.None
            }
            val summInCallTraceCmdPtrs = summariesInCallTrace.mapToSet {
                CmdPointer(it.blockId, it.pos)
            }
            return ExampleMeta.Some(summInCallTraceCmdPtrs.mapToSet {
                cmdPtrToId[it] ?: throw IllegalStateException(
                    "Expected all summary instances in $summariesInCallTrace to have their Command Pointers belong to ${cmdPtrToId.keys}"
                )
            })
        }

        fun tableWithExampleMeta(): CallResolutionTableWithExampleMeta =
            CallResolutionTableWithExampleMeta(
                rows = baseRowToId.mapTo(mutableSetOf()) { (baseRow, rowId) ->
                    CallResolutionTableWithExampleMeta.Row(
                        caller = baseRow.caller,
                        callSite = baseRow.callSite,
                        callee = baseRow.callee,
                        summary = baseRow.summary,
                        resolutionComments = baseRow.resolutionComments,
                        status = baseRow.status,
                        rowId = rowId
                    )
                }
            )
    }

}
