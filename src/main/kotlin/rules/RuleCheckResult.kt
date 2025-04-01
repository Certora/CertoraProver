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

import allocator.Allocator
import config.Config
import config.OUTPUT_NAME_DELIMITER
import config.ReportTypes
import datastructures.NonEmptyList
import datastructures.stdcollections.*
import kotlinx.serialization.json.*
import log.*
import report.*
import report.RuleAlertReport.Companion.join
import report.callresolution.*
import report.calltrace.*
import report.calltrace.formatter.*
import report.calltrace.generator.generateCallTrace
import report.calltrace.sarif.Sarif.Arg
import report.dumps.UnsolvedSplitInfo
import report.dumps.generateUnsolvedSplitCodeMap
import rules.sanity.SanityCheckResult
import rules.sanity.SanityCheckResultOrdinal
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import solver.SolverResult
import spec.CVLCompiler
import spec.CVLTestGenerator
import spec.cvlast.*
import spec.rules.CVLSingleRule
import spec.rules.ICVLRule
import spec.rules.IRule
import spec.rules.SingleRule
import utils.*
import vc.data.*
import vc.data.TACMeta.CONTRACT_ADDR_KEY
import vc.data.TACMeta.CONTRACT_ADDR_KEY_NAME
import vc.data.TACMeta.HASHING_BOUND_ASSERT
import vc.data.TACMeta.HASHING_BOUND_ASSUME
import vc.data.state.TACValue
import verifier.splits.SplitAddress
import verifier.Verifier
import verifier.mus.UnsatCoreAnalysis
import verifier.mus.UnsatCoreInputData
import verifier.mus.UnsatCoresStats
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COMMON)

/** avoid flooding the log with warnings -- warn only once */
private var warnedAlreadyOnUnexpectedValue = false


sealed class RuleCheckResult(open val rule: IRule) {

    /**
     * Marker interface for [RuleCheckResult]s which are reported as leaves in the TreeViewGraph
     * by the TreeView reporter. Those [RuleCheckResult]s are effectively leaves,
     * in the sense they have no child results.
     */
    sealed class Leaf(rule: IRule): RuleCheckResult(rule) {

        abstract val ruleAlerts: RuleAlertReport<*>?

    }

    /**
     * Computes the verification time [VerifyTime] of [rule].
     * For [RuleCheckResult.Multi], we use the [join] operation
     * over all the child results' verification times.
     */
    fun computeVerifyTime(): VerifyTime {
        return when (this) {
            is Error, is Skipped -> VerifyTime.None

            is Multi -> results.fold(VerifyTime.None) { aggregatedVerifyTime: VerifyTime, subResult ->
                val timeForSubResult = subResult.computeVerifyTime()
                aggregatedVerifyTime join timeForSubResult
            }

            is Single -> verifyTime
        }
    }

    fun isSatisfyResult() = rule.isSatisfyRule

    fun expectedResult() = if (rule.isSatisfyRule) {
        SolverResult.SAT
    } else {
        SolverResult.UNSAT
    }

    fun violatedResult() = if (rule.isSatisfyRule) {
        SolverResult.UNSAT
    } else {
        SolverResult.SAT
    }

    data class FlattenedResult(
        val ruleDeclarationId: String,
        val solverResult: SolverResult,
        val checkResult: RuleCheckResult
    ) {
        fun isSuccess() = solverResult == checkResult.expectedResult()
        fun isViolated() = solverResult == checkResult.violatedResult()
    }

    abstract fun getAllFlattened(): List<FlattenedResult> // each must appear once...
    open fun getAllSingleRules(): List<SingleRule> {
        return rule.getAllSingleRules()
    }

    /**
     * Returns the table name that should be used for this result
     * when generating various reports.
     */
    open fun getTableNameForReport(): String {
        return rule.declarationId
    }

    abstract fun consolePrint(satIsGood: Boolean): String

    sealed class RuleFailureMeta {

        abstract val message: String
        /**
         * Represents a failure of a statically checked rule
         * @property message the failure message
         */
        data class StaticCheck(override val message: String) : RuleFailureMeta()

        /**
         * Represents a TAC assert command that the counter-example model violates in a rule.
         *
         * @property message The assert message (may be an empty string)
         * @property range The location of the corresponding assert statement in the cvl spec
         * @property identifier An identifier that also contains the rule name
         * (not null if there exists such a statement)
         */
        data class ViolatedAssert(val identifier: AssertIdentifier, override val message: String, val range: Range) :
            RuleFailureMeta() {
            constructor(identifier: AssertIdentifier, _message: String) : this(identifier, _message, Range.Empty())
        }
    }


    /**
     * A single-rule's result.
     * [rule] is the rule this result belongs to.
     */
    sealed class Single(override val rule: IRule) : Leaf(rule) {

        /** [result] is the inferred solver's result.
         * [verifyTime] is the time in which [rule] got verified.
         * [ruleCheckInfo] holds some meta-info about this result.
         * [callResolutionTable] is a CallResolution table containing summarized calls
         * from the underlying [CoreTACProgram] of [rule].
         */
        abstract val result: SolverResult
        abstract val verifyTime: VerifyTime
        abstract val ruleCheckInfo: RuleCheckInfo
        abstract val callResolutionTable: CallResolutionTable<*>

        val ruleAlertsWithCallResolutionTableAlerts: RuleAlertReport<*>?
            get() = ruleAlerts join callResolutionTable.alertReport join
                RuleAlertReport((this as? WithCounterExamples)?.ruleCheckInfo?.examples?.mapNotNull { example ->
                    example.callTrace?.alertReport
                }.orEmpty())

        /**
         * A sample out of the [RuleCheckInfo]s, which is the first one.
         */
        abstract val firstData: RuleCheckInfo.BasicDataContainer

        enum class OutputReportViewAttribute(private val repString: String) {
            TREE_VIEW_PATH("treeViewPath"),
            GRAPH_LINK("graph_link"),
            JUMP_TO_DEFINITION("jumpToDefinition"),
            RESULT("result"),
            ASSERT_MESSAGE("assertMessage"),
            VALUE_REPRESENTATIONS("valueRepresentations"),
            NAME("name"),
            EXAMPLE("example"),
            CALL_TRACE("callTrace"),
            VARIABLES("variables")
            ;

            operator fun invoke(): String = repString
        }

        /**
         * Shared logic for building the JsonObject of both [WithCounterExamples]
         * and [Basic].
         */
        protected fun baseTreeViewRepBuilder(
            location: TreeViewLocation?,
            treeViewNode: DisplayableIdentifier,
            exampleMeta: Result<CallResolutionTableWithExampleMeta.ExampleMeta>,
            dumpGraphLink: String?,
        ): TreeViewRepBuilder<JsonObjectBuilder> = TreeViewRepJsonObjectBuilder {

            put(OutputReportViewAttribute.TREE_VIEW_PATH(), treeViewNode.toString())

            /** graph_link */
            put(OutputReportViewAttribute.GRAPH_LINK(), dumpGraphLink)

            put(
                OutputReportViewAttribute.JUMP_TO_DEFINITION(),
                location,
            )

            put(
                OutputReportViewAttribute.ASSERT_MESSAGE(),
                if (result == SolverResult.SAT) {  // rule is violated
                    val assertMessage = firstData.failureResultMeta.firstOrNull()?.message
                    if (assertMessage != null && assertMessage != "") {
                        assertMessage
                    } else {
                        null
                    }
                } else if (rule.ruleType is SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.SpecFile) {
                    /*
                If the rule is not violated (e.g., ERROR, TIMEOUT, VERIFIED),
                and it was generated for an assert statement that is originating from the spec
                 */
                    val multiAssertRuleType =
                        rule.ruleType as SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.SpecFile
                    if (multiAssertRuleType.assertMessage != "") {
                        multiAssertRuleType.assertMessage
                    } else {
                        null
                    }
                } else {
                    null
                }
            )

            putJsonArray(OutputReportViewAttribute.VALUE_REPRESENTATIONS()) {
                AlternativeRepresentations.Representations.values().forEach {
                    addJsonObject {
                        put(OutputReportViewAttribute.NAME(), it.shortName)
                        put(OutputReportViewAttribute.EXAMPLE(), it.example)
                    }
                }
            }


            /** call_resolution */
            val callResWithNoWarnings =
                callResolutionTable.copyAndFilterTable(isWarning = false)
            put(
                CallResolutionTableReportView.Attribute.CALL_RESOLUTION(),
                callResWithNoWarnings.toCallResTableReporterView(exampleMeta.getOrElse { CallResolutionTableWithExampleMeta.ExampleMeta.None })
            )

            /** call_resolution_warnings */
            val callResWithWarningsOnly =
                callResolutionTable.copyAndFilterTable(isWarning = true)
            put(
                CallResolutionTableReportView.Attribute.CALL_RESOLUTION_WARNINGS(),
                callResWithWarningsOnly.toCallResTableReporterView(exampleMeta.getOrElse { CallResolutionTableWithExampleMeta.ExampleMeta.None })
            )

        }

        /**
         * A basic result, without notion of examples.
         */
        data class Basic(
            override val rule: IRule,
            override val result: SolverResult,
            override val verifyTime: VerifyTime,
            override val ruleCheckInfo: RuleCheckInfo.BasicInfo,
            override val callResolutionTable: CallResolutionTableBase = CallResolutionTableBase.Empty,
            override val ruleAlerts: RuleAlertReport<RuleAlertReport.Single<*>>?,
            val unsatCoreStats: UnsatCoresStats? = null
        ) : Single(rule) {

            override val firstData: RuleCheckInfo.BasicDataContainer = ruleCheckInfo

            fun toOutputReportView(
                location: TreeViewLocation?,
                treeViewNode: DisplayableIdentifier
            ): OutputReportView {
                val baseBuilder = baseTreeViewRepBuilder(
                    location,
                    treeViewNode,
                    exampleMeta = Result.success(CallResolutionTableWithExampleMeta.ExampleMeta.None),
                    dumpGraphLink = ruleCheckInfo.dumpGraphLink,
                )
                return OutputReportView(
                    rule.ruleIdentifier,
                    TreeViewRepJsonObjectBuilder {
                        baseBuilder.builderAction(this)
                    },
                )
            }
        }


        /**
         * A result with a basic data and at least one example attached.
         * Each example has its own basic data.
         */
        data class WithCounterExamples constructor(
            override val rule: IRule,
            override val result: SolverResult,
            override val verifyTime: VerifyTime,
            override val ruleCheckInfo: RuleCheckInfo.WithExamplesData,
            override val ruleAlerts: RuleAlertReport<RuleAlertReport.Single<*>>? = null,
            override val callResolutionTable: CallResolutionTableWithExampleMeta = CallResolutionTableWithExampleMeta.Empty
        ) : Single(rule) {


            override val firstData: RuleCheckInfo.BasicDataContainer
                get() = ruleCheckInfo.examples.head

            fun toOutputReportView(
                location: TreeViewLocation?,
                treeViewNode: DisplayableIdentifier,
                counterExample: RuleCheckInfo.WithExamplesData.CounterExample
            ): OutputReportView {
                val baseBuilder = baseTreeViewRepBuilder(
                    location,
                    treeViewNode,
                    exampleMeta = counterExample.callResolutionExampleMeta,
                    dumpGraphLink = counterExample.dumpGraphLink,
                )
                val builder =
                    TreeViewRepJsonObjectBuilder {
                        baseBuilder.builderAction(this)
                        put(
                            OutputReportViewAttribute.CALL_TRACE(),
                            counterExample.callTrace?.callHierarchyRoot
                        )
                        /** variables */
                        put(OutputReportViewAttribute.VARIABLES(), buildJsonArray {
                            for ((name, local) in counterExample.localAssignments) {

                                /** compute the tooltip for variables tab variables
                                 * would be nicer to not look at the string, but should do for now, since the current
                                 * rule for getting the tooltip is rather simple */
                                fun tooltip(valueStr: String) = when (valueStr) {
                                    CallTraceValueFormatter.DONT_CARE_VALUE_STR -> CallTraceValueFormatter.DONT_CARE_VALUE_TOOLTIP
                                    CallTraceValueFormatter.UNKNOWN_VALUE_STR -> CallTraceValueFormatter.UNKNOWN_VALUE_TOOLTIP
                                    else -> "copy value"
                                }

                                addJsonObject {
                                    put("variableName", name)

                                    val sarif = local.state.toSarif(local.formatter)
                                    val value: Arg? = sarif.asArg()
                                    if (value != null) {
                                        put(CallTraceAttribute.VALUE(), value.values.pretty)
                                        if (altReprsInTreeView) {
                                            put(
                                                CallTraceAttribute.VALUES(),
                                                buildJsonArray { value.values.asRepList().forEach { add(it) } }
                                            )
                                        }
                                        put(CallTraceAttribute.TOOLTIP(), tooltip(value.values.pretty))
                                        put(CallTraceAttribute.TRUNCATABLE(), value.truncatable)
                                    } else {
                                        val valueStr = sarif.flatten()
                                        if (!warnedAlreadyOnUnexpectedValue) {
                                            logger.warn { // not really expecting this to happen -- let's monitor ..
                                                "unexpected display value in variables tab (variable: $name, " +
                                                    "displayValue: $valueStr)"
                                            }
                                            warnedAlreadyOnUnexpectedValue = true
                                        }
                                        put(CallTraceAttribute.VALUE(), valueStr)
                                        if (altReprsInTreeView) {
                                            put(CallTraceAttribute.VALUES(), buildJsonArray { add(valueStr) })
                                        }
                                        put(CallTraceAttribute.TOOLTIP(), tooltip(valueStr))
                                        put(CallTraceAttribute.TRUNCATABLE(), false)
                                    }

                                    put(OutputReportViewAttribute.JUMP_TO_DEFINITION(), local.range as? Range.Range)
                                    // not dumping this for now, since we're not using it -- but might put it to use later, so leaving it for now
                                    // put(CallTraceAttribute.TYPE(), local.formatterType.toTypeString()) // XXX toString or toTypeString??
                                }
                            }
                        })
                    }

                    return OutputReportView(
                        rule.ruleIdentifier,
                        builder
                    )
                }
        }

        /**
         * Holds information about a result of a rule.
         */
        sealed class RuleCheckInfo {
            /**
             * Indicate whether there was a cache-hit for this [rule].
             * used to calculate cache-hit percentage.
             */
            abstract val isOptimizedRuleFromCache: IsFromCache
            abstract val isSolverResultFromCache: IsFromCache


            companion object {
                fun dumpGraphLinkOf(
                    rule: IRule,
                    reportType: ReportTypes,
                    reportNameIndex: HTMLReporter.ReportNameIndex,
                    customSuffix: String = "",
                    withHTMLExtension: Boolean = true
                ): String =
                    ArtifactFileUtils.sanitizePath(
                        rule.parentIdentifier?.displayName?.let { directParentName ->
                            val parentsNames = when(rule){
                                is ICVLRule -> CVLCompiler.getParentsNames(rule, directParentName)
                                else -> directParentName
                            }
                            // TODO: move this function out of HTMLReporter
                            HTMLReporter.getReportNameMultiCase(
                                rule.declarationId,
                                parentsNames,
                                rule.ruleType,
                                reportType,
                                reportNameIndex,
                                withHTMLExtension,
                                customSuffix
                            )
                        } ?: HTMLReporter.getReportName(
                            rule.declarationId,
                            rule.ruleType,
                            reportType,
                            reportNameIndex,
                            withHTMLExtension,
                            customSuffix
                        )
                    )

                /**
                 * Returns the filename of the final HTML report generated to show the TAC dump of [rule] annotated
                 * with the counterexample model whose index is [exampleIndex].
                 */
                fun cexDumpGraphLinkOf(rule: IRule, exampleIndex: Int, customSuffix: String = ""): String =
                    dumpGraphLinkOf(
                        rule,
                        ReportTypes.REPORT,
                        HTMLReporter.ReportNameIndex.Example(exampleIndex),
                        customSuffix
                    )

                /**
                 * Returns the filename of the final HTML report generated to show the TAC dump of [rule] annotated.
                 * Can be used for timeout or unsat visualization.
                 * For the unsat case, where there can be many unsat cores, and thus many dumps, the dumps can be
                 * indexed using the [unsatCoreIndex] parameter.
                 */
                fun unsatCoreDumpGraphLinkOf(rule: IRule, unsatCoreIndex: Int): String =
                    dumpGraphLinkOf(
                        rule,
                        ReportTypes.REPORT,
                        HTMLReporter.ReportNameIndex.UnsatCore(unsatCoreIndex),
                    )

                fun unsolvedSplitsDumpGraphLinkOf(rule: IRule): String =
                    dumpGraphLinkOf(
                        rule,
                        ReportTypes.REPORT,
                        HTMLReporter.ReportNameIndex.None
                    )

                /**
                 * Returns the filename (with an '.html' extension) of the HTML report generated to show the TAC dump of [rule],
                 * just before calling solvers in usual Prover mode.
                 */
                fun basicDumpGraphLinkOf(rule: IRule): String =
                    dumpGraphLinkOf(
                        rule,
                        ReportTypes.PRESOLVER_RULE,
                        HTMLReporter.ReportNameIndex.None
                    )
            }

            /**
             * Returns a copy of this result with [newDetails] as the new details.
             */
            abstract fun copy(newDetails: String): RuleCheckInfo

            /**
             * Returns a copy of this result with [newDetails] as the new details,
             * if [newDetails] is not null, otherwise returns a copy with the exact
             * same data.
             */
            abstract fun copyWithDetailsIfNotNull(newDetails: String?): RuleCheckInfo

            /**
             * A holder of a basic data of a single-result.
             * [details] is some details about a rule and its result.
             * [failureResultMeta] are failure messages about the rule.
             * [dumpGraphLink] is a link of a dump file containing the
             * underlying TACProgram, sometimes annotated with an example.
             */
            sealed interface BasicDataContainer: java.io.Serializable {
                val details: Result<String>
                val failureResultMeta: List<RuleFailureMeta>
                val dumpGraphLink: String?
            }

            /**
             * Holder of basic details only, no examples' info is attached.
             */
            data class BasicInfo(
                override val details: Result<String> = Result.success(""),
                override val failureResultMeta: List<RuleFailureMeta> = emptyList(),
                override val dumpGraphLink: String?,
                override val isOptimizedRuleFromCache: IsFromCache,
                override val isSolverResultFromCache: IsFromCache,
            ) : BasicDataContainer, RuleCheckInfo() {

                override fun copy(newDetails: String): BasicInfo {
                    return copy(details = Result.success(newDetails))
                }

                override fun copyWithDetailsIfNotNull(newDetails: String?): BasicInfo {
                    return if (newDetails == null) {
                        this
                    } else {
                        copy(details = Result.success(newDetails))
                    }
                }

                companion object {
                    operator fun invoke(
                        rule: IRule,
                        vResponse: Verifier.JoinedResult,
                        isOptimizedRuleFromCache: IsFromCache,
                        isSolverResultFromCache: IsFromCache,
                        dumpGraphLink: String? = null
                    ): BasicInfo = BasicInfo(
                        details = Result.success(vResponse.details(rule)),
                        dumpGraphLink = dumpGraphLink ?: basicDumpGraphLinkOf(rule),
                        isOptimizedRuleFromCache = isOptimizedRuleFromCache,
                        isSolverResultFromCache = isSolverResultFromCache,
                    )

                    /**
                     * Generates and writes a CodeMap Html (aka tac dump). Used when verification of [rule] timed out.
                     * Returns the name of the written html file.  */
                    private fun computeAndDumpUnsolvedSplitCodeMap(
                        rule: IRule,
                        unsolvedSplitsData: UnsolvedSplitInfo,
                    ): String {
                        val codeMap = generateUnsolvedSplitCodeMap(unsolvedSplitsData)
                        val dumpGraphLink = unsolvedSplitsDumpGraphLinkOf(rule)
                        HTMLReporter.dumpCodeMapToHTMLReport(codeMap, dumpGraphLink)
                        return dumpGraphLink
                    }

                    data class UnsatCoresDumpGraphLinkAndStats(
                        val htmlDumpPath: String?,
                        val unsatCoreStats: UnsatCoresStats
                    )

                    /**
                     * Generates and writes a CodeMap Htmls (aka tac dumps). Used when verification of [rule] returned
                     * unsat and we made an unsat core ananalysis.
                     * Will make a dump for each unsat core.
                     * Returns the name of the written html file for the first of the unsat cores and
                     * the unsat core stats object.
                     */
                    suspend fun computeAndDumpUnsatCoreCodeMap(
                        rule: IRule,
                        unsatCoreSplits: Map<SplitAddress, UnsatCoreInputData>,
                        tacProgram: CoreTACProgram,
                    ): UnsatCoresDumpGraphLinkAndStats {
                        val unsatCoreAnalysis = UnsatCoreAnalysis(unsatCoreSplits, tacProgram)
                        val stats = unsatCoreAnalysis.dumpToJson()
                        var dumpGraphLink: String? = null
                        unsatCoreAnalysis.renderCodeMaps().forEachIndexed { i, codeMap ->
                            val currentDumpGraphLink = unsatCoreDumpGraphLinkOf(rule, i)
                            if (dumpGraphLink == null) {
                                dumpGraphLink = currentDumpGraphLink
                            }
                            HTMLReporter.dumpCodeMapToHTMLReport(codeMap, currentDumpGraphLink)
                        }
                        return UnsatCoresDumpGraphLinkAndStats(dumpGraphLink, stats)
                    }

                    /** Sets up the initial version of this class (afterwards only the [details] field can be changed
                     * via the [copy] methods). Also generates and dumps the colored "timeout"-CodeMap via
                     * [generateUnsolvedSplitCodeMap]. */
                    fun dumpUnsolvedSplits(
                        rule: IRule,
                        vResponse: Verifier.JoinedResult,
                        isOptimizedRuleFromCache: IsFromCache,
                        isSolverResultFromCache: IsFromCache
                    ): BasicInfo {
                        val dumpGraphLink: String =
                            vResponse.unsolvedSplitsData?.let { unsolvedSplitsData ->
                                computeAndDumpUnsolvedSplitCodeMap(rule, unsolvedSplitsData)
                            } ?:
                                // This is a fallback such that we don't crash and link to the PreSolver dump instead
                                //  (which is always dumped), if anything weird happens. Currently, this case is
                                //  statically unreachable, but we're staying on the safe side.
                                run {
                                    logger.warn { "vResponse for rule ${rule.declarationId} has no unsolvedSplitsData; reverting to " +
                                        "dumping / linking Presolver report instead of the colored graph with " +
                                        "unsolved split info" }
                                    basicDumpGraphLinkOf(rule)
                                }

                        val details: Result<String> = Result.success(vResponse.details(rule))
                        return BasicInfo(details = details, dumpGraphLink = dumpGraphLink, isOptimizedRuleFromCache = isOptimizedRuleFromCache, isSolverResultFromCache = isSolverResultFromCache)
                    }
                }
            }

            /**
             * A Wrapper of at least one counter-example.
             */
            data class WithExamplesData(
                val examples: NonEmptyList<CounterExample>,
                override val isOptimizedRuleFromCache: IsFromCache,
                override val isSolverResultFromCache: IsFromCache
            ) : RuleCheckInfo() {

                /**
                 * Holder of a basic data which depends on the counter-example, and some additional
                 * counter-example related properties.
                 * [callResolutionExampleMeta] holds the ids of the entries in the CallResolution
                 * table which are relevant to this example.
                 * [exampleId] is a unique id given to each example.
                 * [callTrace] represents the counter-example, namely a trace/execution of the program
                 * that violates the CVL rule.
                 * [localAssignments] are the assignments to the local CVL variables in the rule,
                 * derived from the counter-example model [model], chosen by the SMT.
                 * [assertSlice] is used to track the reason for the violation of the rule.
                 * [minHashingBoundNeeded] is the maximum lengtht of an array along the trace;
                 * only when using [Config.HashingBoundDetectionMode].
                 */
                data class CounterExample(
                    override val details: Result<String> = Result.success(""),
                    override val failureResultMeta: List<RuleFailureMeta.ViolatedAssert> = emptyList(),
                    override val dumpGraphLink: String?,
                    val rule: IRule,
                    val callResolutionExampleMeta: Result<CallResolutionTableWithExampleMeta.ExampleMeta> = Result.success(
                        CallResolutionTableWithExampleMeta.ExampleMeta.None
                    ),
                    val callTrace: CallTrace? = null,
                    val localAssignments: Map<String, LocalAssignment> = emptyMap(),
                    val model: CounterexampleModel = CounterexampleModel.Empty,
                    val assertSlice: Result<DynamicSlicerResults?> = Result.success(null),
                    val minHashingBoundNeeded: BigInteger? = null,
                ) : BasicDataContainer {
                    val exampleId: Int = Allocator.getFreshId(Allocator.Id.COUNTEREXAMPLE)


                    /**
                     * Returns the first [RuleFailureMeta.ViolatedAssert] of [failureResultMeta].
                     * If there is no such [RuleFailureMeta.ViolatedAssert], a warning is logged and null is returned.
                     */
                    fun toViolatedAssertFailureMeta(): RuleFailureMeta.ViolatedAssert? {
                        val firstFailureResultMetaOrNull = failureResultMeta.firstOrNull()
                        //TODO: enforce this by design
                        if (firstFailureResultMetaOrNull == null) {
                            logger.warn {
                                "Expected to have a non-empty failureResultMeta (rule ${rule.declarationId})"
                            }
                        }
                        return firstFailureResultMetaOrNull
                    }
                }

                override fun copyWithDetailsIfNotNull(newDetails: String?): WithExamplesData {
                    return if (newDetails == null) {
                        this
                    } else {
                        copy(newDetails = newDetails)
                    }
                }

                override fun copy(newDetails: String): WithExamplesData = copy(
                    examples = examples.map {
                        it.copy(details = Result.success(newDetails))
                    }
                )

                companion object {

                    private fun allSymbolsInProgram(program: CoreTACProgram) = program.symbolTable.tags.keys

                    /**
                     * finds all contracts in [programVars], then returns a map where the keys are the addresses
                     * assigned by [model] to each contract, and the values are that contract's [ContractInfo].
                     */
                    private fun modelAddressToContractInfo(
                        model: CounterexampleModel,
                        programVars: Iterable<TACSymbol.Var>
                    ): Map<TACValue.PrimitiveValue.Integer, ContractInfo> {
                        return programVars.associateNotNull { symbol ->
                            val contract = ContractInfo.fromTACSymbol(symbol) ?: return@associateNotNull null

                            val modelValue = model.valueAsTACValue(symbol)
                                ?: run {
                                    val msg = "Contract with symbol $symbol exists in program, but has no model assignment"
                                    if (symbol.tag.isPrimitive()) {
                                        logger.info { msg }
                                    } else {
                                        logger.warn { msg }
                                    }
                                    return@associateNotNull null
                                }

                            val address = modelValue as? TACValue.PrimitiveValue.Integer
                                ?: run {
                                    logger.warn { "Model value $modelValue for contract $symbol is not a TACValue.PrimitiveValue.Integer" }
                                    return@associateNotNull null
                                }

                            address to contract
                        }
                    }

                    /**
                     * Generates counter-examples from [res] and wraps them using [WithCounterExamples].
                     */
                    operator fun invoke(
                        scene: ISceneIdentifiers,
                        rule: IRule,
                        res: Verifier.JoinedResult.Failure,
                        origProgWithAssertIdMeta: CoreTACProgram,
                        callResolutionTableFactory: CallResolutionTable.Factory,
                        isOptimizedRuleFromCache: IsFromCache,
                        isSolverResultFromCache: IsFromCache,
                    ): WithExamplesData {
                        val ruleCallString: String = rule.parentIdentifier?.displayName ?: rule.declarationId

                        val examples = res.examplesInfo.mapIndexed { exampleIndex, exampleInfo ->
                            val model = CounterexampleModel.fromSMTResult(exampleInfo, res.simpleSimpleSSATAC)

                            val addrToContract =
                                modelAddressToContractInfo(model, allSymbolsInProgram(origProgWithAssertIdMeta))

                            val formatter = CallTraceValueFormatter(addrToContract, scene, model)

                            val localAssignments = localAssignments(model, origProgWithAssertIdMeta, addrToContract, formatter, scene)
                            logLocalAssignments(localAssignments)

                            val assertionMessages: List<RuleFailureMeta.ViolatedAssert> =
                                listOfNotNull(model.findMetaOfFirstViolatedAssert(origProgWithAssertIdMeta, rule))

                            val callTrace = generateCallTrace(
                                rule,
                                model,
                                origProgWithAssertIdMeta,
                                formatter,
                                scene,
                                ruleCallString,
                            )

                            val callResolutionExampleMeta: Result<CallResolutionTableWithExampleMeta.ExampleMeta> =
                                runCatching {
                                    callResolutionTableFactory.exampleMetaOf(callTrace.callHierarchyRoot.filterCallInstancesOf())

                                }.onFailure {
                                    Logger.alwaysError(
                                        "Error building the contract call resolution table for the rule: ${rule.declarationId}",
                                    )
                                }

                            val assertSlice: Result<DynamicSlicerResults?> =
                                if (callTrace is CallTrace.ViolationFound) {
                                    runCatching {
                                        DynamicSlicer(
                                            origProgWithAssertIdMeta,
                                            model,
                                            scene
                                        ).sliceViolatedAssertCond(
                                            callTrace.violatedAssertCond,
                                            callTrace.violatedAssert.ptr
                                        )
                                    }.onFailure {
                                        Logger.alwaysError(
                                            "Error building dynamic assert slice for violation of rule: ${rule.declarationId}",
                                            it
                                        )
                                    }

                                } else {
                                    Result.success(null)
                                }

                            val description = "\n" + when (rule) {
                                is CVLSingleRule -> rule.description.replace("\"", "")
                                else -> "N/A"
                            }


                            /*
                                Collect the maximum used array length along the trace;
                                We use this later to deduce suitable hashing bounds for the
                                optimistic hashing mode.
                             */
                            val minHashingBoundNeeded = if(Config.HashingBoundDetectionMode.get()){
                                model.reachableNBIds.flatMap {
                                    res.simpleSimpleSSATAC.code[it]!!.mapNotNull { cmd ->
                                        cmd.meta[HASHING_BOUND_ASSERT] ?: cmd.meta[HASHING_BOUND_ASSUME]
                                    }
                                }.mapNotNull { lengthExpr ->
                                    (model.tacAssignments[lengthExpr] as? TACValue.PrimitiveValue.Integer)?.value
                                }.maxOrNull()
                            } else {
                                null
                            }


                            val counterExample = CounterExample(
                                details = Result.success(description),
                                failureResultMeta = assertionMessages,
                                cexDumpGraphLinkOf(rule, exampleIndex),
                                rule,
                                callResolutionExampleMeta,
                                callTrace,
                                localAssignments,
                                model,
                                assertSlice,
                                minHashingBoundNeeded
                            )
                            counterExample
                        }

                        return WithExamplesData(examples, isOptimizedRuleFromCache = isOptimizedRuleFromCache, isSolverResultFromCache = isSolverResultFromCache)
                    }
                }
            }
        }

        override fun toString(): String {

            return "Single(rule=${rule.declarationId}:${rule.ruleType},result=$result,${
                when (this) {
                    is WithCounterExamples -> {
                        "models.size=${ruleCheckInfo.examples.map { it.model.tacAssignments.size }}"
                    }

                    is Basic -> {
                        0
                    }
                }
            }" +
                ",assertMessage=${
                    CVLTestGenerator.getAssertionMessage(
                        firstData.failureResultMeta,
                        this.isSatisfyResult()
                    )
                },details=${firstData.details},localVars.size=${
                    (firstData as? RuleCheckInfo.WithExamplesData.CounterExample)
                        ?.localAssignments?.size ?: 0
                },time=${verifyTime.timeSeconds})"
        }

        override fun consolePrint(satIsGood: Boolean): String {

            fun badResultMessage(result: SolverResult): String {
                return "${result.toJSONRepresentation(satIsGood)}: ${firstData.failureResultMeta.joinToString(" ") { it.message }}"
            }

            return "${rule.declarationId}: " +
                when (result) {
                    SolverResult.UNSAT -> {
                        if (satIsGood) { badResultMessage(result) }
                        else { result.toJSONRepresentation(satIsGood) }
                    }

                    SolverResult.SAT -> {
                        if (satIsGood) { result.toJSONRepresentation(satIsGood) }
                        else { badResultMessage(result) }
                    }

                    else -> {
                        result.toJSONRepresentation(satIsGood)
                    }
                }
        }

        override fun getAllFlattened(): List<FlattenedResult> {
            return listOf(FlattenedResult(rule.declarationId, result, this))
        }


    }

    /**
     * Defines the type of [RuleCheckResult]
     */
    enum class MultiResultType {
        PARAMETRIC,             // for parametric methods
        SPLIT_ASSERTS           // for results with non-successful asserts
    }

    /**
     * @property parentSanityResult Sanity check result of the parent rule that corresponds to this [Multi] result;
     * should be assigned by the [RuleChecker]. If it's null, either sanity checks are disabled or this is a [Multi] result
     * with a grand-child result (for example, an invariant that has a parametric, generic-preserved child-rule,
     * together with an explicit-preserved single-rule).
     */
    data class Multi(
        override val rule: IRule,
        val results: List<RuleCheckResult>,
        val resultType: MultiResultType,
        val details: String = "",
        val parentSanityResult: SanityCheckResult? = null
    ) : RuleCheckResult(rule) {

        override fun toString(): String {
            return "Multi(rule=${rule.declarationId}:${rule.ruleType}:${rule.parentIdentifier},\n\tresults=${results.joinToString(",\n")})"
        }

        override fun getAllFlattened(): List<FlattenedResult> {
            return results.flatMap { it.getAllFlattened() }
        }

        override fun getAllSingleRules(): List<SingleRule> {
            return rule.getAllSingleRules() + results.flatMap { it.getAllSingleRules() }
        }

        override fun getTableNameForReport(): String {
            return when (resultType) {
                MultiResultType.PARAMETRIC -> rule.declarationId
                MultiResultType.SPLIT_ASSERTS ->
                    rule.parentIdentifier?.let { "$it$OUTPUT_NAME_DELIMITER${rule.declarationId}" }
                        ?: rule.declarationId
            }
        }

        /**
         * Whether any child [RuleCheckResult] of this [Multi] has a [SolverResult.SANITY_FAIL] result.
         */
        private val anyChildResultFailedSanity by lazy {
            results.any {
                when (it) {
                    is Multi -> {
                        it.computeFinalResult() == SolverResult.SANITY_FAIL
                    }

                    is Single -> {
                        it.result == SolverResult.SANITY_FAIL
                    }

                    else -> {
                        false
                    }
                }
            }
        }
        private class FlattenedResultsCache(val flattenedResults: List<FlattenedResult>) {

            /**
             * All descendant [SolverResult]s that are the expected good result
             * (i.e., UNSAT when checking Assert rules or SAT when checking satisfy rules)
             */
            @Suppress("unused")
            val success: List<FlattenedResult> by lazy { flattenedResults.filter { it.isSuccess() } }

            /**
             * All descendant [SolverResult]s that are the violated result
             * (i.e., SAT when checking Assert rules or UNSAT when checking satisfy rules)
             */
            val violated: List<FlattenedResult> by lazy { flattenedResults.filter { it.isViolated() } }

            /**
             * All descendant [SolverResult]s that are [SolverResult.TIMEOUT]
             */
            val timeout: List<FlattenedResult> by lazy { flattenedResults.filter { it.solverResult == SolverResult.TIMEOUT } }

            /**
             * All descendant [SolverResult]s that are [SolverResult.UNKNOWN]
             */
            val unknown: List<FlattenedResult> by lazy { flattenedResults.filter { it.solverResult == solver.SolverResult.UNKNOWN } }

            /**
             * All descendant [SolverResult]s that are [SolverResult.SANITY_FAIL]
             */
            val sanityFail: List<FlattenedResult> by lazy { flattenedResults.filter { it.solverResult == SolverResult.SANITY_FAIL } }

            /**
             * Whether all descendant [RuleCheckResult]s are the expected good result
             */
            val allIsSuccess: Boolean by lazy { flattenedResults.all { r -> r.isSuccess() } }
        }

        /**
         * Cache of all [RuleCheckResult]s ([FlattenedResultsCache.flattenedResults]) that are descended from this [Multi],
         * filtered by their [SolverResult]s.
         */
        private val flattenedResultsCache by lazy { FlattenedResultsCache(getAllFlattened()) }

        fun computeMultiSummary(): Single {
            // Will produce a report for the whole group! Even if there's nesting

            // build a description
            val description = if (flattenedResultsCache.allIsSuccess) {
                "All passed!"
            } else {
                val concatOfMessages: MutableList<String> = mutableListOf()
                // we add violation messages if such exist. otherwise, we add sanity messages
                // (again, if such exist).
                if (flattenedResultsCache.violated.isNotEmpty()) {
                    concatOfMessages.add("Violated for: \n${
                        flattenedResultsCache.violated.joinToString(",\n") { it.ruleDeclarationId }
                    }")
                }
                if (flattenedResultsCache.unknown.isNotEmpty()) {
                    concatOfMessages.add("Unknown result: \n${
                        flattenedResultsCache.unknown.joinToString(",\n") { it.ruleDeclarationId }
                    }")
                }
                if (flattenedResultsCache.timeout.isNotEmpty()) {
                    concatOfMessages.add("Timeout result: \n${
                        flattenedResultsCache.timeout.joinToString(",\n") { it.ruleDeclarationId }
                    }")
                }
                concatOfMessages.joinToString()
            } + "\n${details}"

            val finalResult = computeFinalResult()

            val failureResultMeta = if (finalResult != SolverResult.SANITY_FAIL) {
                flattenedResultsCache.flattenedResults.flatMap { result ->
                    if (result.checkResult is Single) {
                        result.checkResult.firstData.failureResultMeta
                    } else listOf()
                }
            } else {
                emptyList()
            }

            // merge the children' CallResolution tables
            val joinedTableBuilder = CallResolutionTableBase.Builder()
            flattenedResultsCache.flattenedResults.forEach { (_,_,result) ->
                if (result is Single) {
                    joinedTableBuilder.joinWith(result.callResolutionTable)
                }
            }

            return Single.Basic(
                rule = rule,
                result = finalResult,
                verifyTime = computeVerifyTime(),
                callResolutionTable = joinedTableBuilder.toTable(),
                ruleCheckInfo = Single.RuleCheckInfo.BasicInfo(
                    details = Result.success(description),
                    failureResultMeta = failureResultMeta,
                    dumpGraphLink = null,
                    isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                    isSolverResultFromCache = IsFromCache.INAPPLICABLE
                ),
                ruleAlerts = null
            )
        }

        fun computeFinalResult(): SolverResult {
            // Result when taking only sanity results into account and ignoring results of original rules
            val resultFromSanity =
                if (parentSanityResult != null) {
                    when (parentSanityResult.ordinal) {
                        /*
                        We map each sanity check ordinal to a corresponding
                        solver result ordinal, denoted SRO; the idea is to compute
                        max { SRO, solver result ordinal of original rule } to
                        obtain the final result.
                        Timeouts and unknown results are ignored in this computation:
                        In case of a passing rule with timeout/unknown results in
                        sanity checks the rule is considered as passing and appropriate
                        msgs should appear in the problems view.
                        TODO: In the above case the status in the tree view won't be "verified" (this is a known bug,
                              see CERT-3208).
                         */
                        SanityCheckResultOrdinal.ERROR -> {
                            SolverResult.UNKNOWN
                        }

                        SanityCheckResultOrdinal.FAILED -> {
                            SolverResult.SANITY_FAIL
                        }

                        SanityCheckResultOrdinal.TIMEOUT,
                        SanityCheckResultOrdinal.UNKNOWN,
                        SanityCheckResultOrdinal.PASSED -> {
                            SolverResult.UNSAT
                        }
                    }
                } else {
                    //  We determine SANITY_FAIL if one of the sub-rules has sanity failure, otherwise consider sanity
                    // check as passing.
                    if (anyChildResultFailedSanity) {
                        SolverResult.SANITY_FAIL
                    } else {
                        SolverResult.UNSAT
                    }
                }

            // Result when taking everything but sanity-results into account
            val resultNoSanity = if (flattenedResultsCache.violated.isNotEmpty()) {
                violatedResult()
            } else if (flattenedResultsCache.unknown.isNotEmpty()) {
                SolverResult.UNKNOWN
            } else if (flattenedResultsCache.timeout.isNotEmpty()) {
                SolverResult.TIMEOUT
            } else {
                expectedResult()
            }

            // Final result to show is the maximum over the two SolverResults
            return resultNoSanity join resultFromSanity
        }

        override fun consolePrint(satIsGood: Boolean): String {
            return "${rule.declarationId}: " + results.joinToString("\n") { it.consolePrint(satIsGood) }
        }
    }

    /**
     * @property ruleAlerts: should include the skipping reason.
     */
    data class Skipped(
        override val rule: IRule,
        override val ruleAlerts: RuleAlertReport.Single<*>
    ) : Leaf(rule) {

        override fun getAllFlattened(): List<FlattenedResult> {
            return listOf() // skipped is not taken into account!
        }

        override fun consolePrint(satIsGood: Boolean): String {
            return "${rule.declarationId}: Rule-check skipped. Skipping reason: ${ruleAlerts.msg}."
        }
    }

    data class Error(
        override val rule: IRule,
        override val ruleAlerts: RuleAlertReport<RuleAlertReport.Error>,
    ) : Leaf(rule) {
        constructor(
            rule: IRule,
            error: Throwable,
        ): this(rule, RuleAlertReport.Error(
            error.message ?: "Unknown error, please report to Certora",
            error
        ))

        override fun getAllFlattened(): List<FlattenedResult> {
            return listOf(FlattenedResult(rule.declarationId, SolverResult.UNKNOWN, this))
        }


        override fun consolePrint(satIsGood: Boolean): String =
            "${rule.declarationId}: Error: " +
                ruleAlerts.asList.map { it.reason ?: it.msg }.joinToString(
                    separator = "${System.lineSeparator()}${System.lineSeparator()}"
                )
    }
}

class ContractInfo private constructor(val instanceId: BigInteger, val name: String) {
    fun resolve(scene: ISceneIdentifiers) = scene.getContractOrNull(this.instanceId)

    companion object {
        fun fromTACSymbol(symbol: TACSymbol.Var): ContractInfo? {
            val instanceId = symbol.meta[CONTRACT_ADDR_KEY] ?: return null
            val name = symbol.meta[CONTRACT_ADDR_KEY_NAME] ?: return null

            return ContractInfo(instanceId, name)
        }
    }
}
