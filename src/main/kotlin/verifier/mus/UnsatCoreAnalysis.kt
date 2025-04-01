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

package verifier.mus

import algorithms.cartesianProduct
import analysis.TACCommandGraph
import config.Config
import config.OUTPUT_NAME_DELIMITER
import config.ReportTypes
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import report.HTMLReporter
import report.dumps.CodeMap
import report.dumps.addUnsatCoreData
import report.dumps.generateCodeMap
import report.dumps.sanitize
import smt.SmtExpWithLExp
import smtlibutils.cmdprocessor.SmtFormulaCheckerQuery
import solver.SolverConfig
import solver.Z3SolverInfo
import spec.CVLExpToTACExprMeta
import utils.Range
import statistics.SmtFileDumping
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACMeta
import vc.data.lexpression.META_TOPLVL_CMD
import verifier.splits.SplitAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

data class UnsatCoreInputData(
    val tacCommandGraph: TACCommandGraph,
    val tac: CoreTACProgram,
    val query: SmtFormulaCheckerQuery,
    val solverConfig: SolverConfig?,
    val originalCheckDuration: Duration?
)

fun UnsatCoreInputData.numOfSoft() = this.query.formula().count { it.isSoftConstraint() }
fun UnsatCoreInputData.expectedSingleUCExtractionDuration() = this.originalCheckDuration?.times(this.numOfSoft()) ?: 7200.seconds

class UnsatCoreAnalysis private constructor(
    private val originalTac: CoreTACProgram
) {

    val logger = Logger(LoggerTypes.MUS_ENUMERATION)
    private fun lInfo(s: String) {
        logger.info { "${originalTac.name}: $s" }
    }
    private fun lWarn(s: String) {
        logger.warn { "${originalTac.name}: $s" }
    }

    /** Unsat cores for individual splits **/
    private val splitsUnsatCores: MutableMap<SplitAddress, List<Set<TACCmd.Simple>>> = mutableMapOf()

    /** Unsat cores for the original TAC constructed by combining unsat cores of the individual splits **/
    private val unitedUnsatCores: MutableList<Set<TACCmd.Simple>> = mutableListOf()

    /** Union of "soft unsat core constraints" from individual splits, i.e., commands with [META_TOPLVL_CMD] **/
    private val unitedSoftConstraints: MutableSet<TACCmd.Simple> = mutableSetOf()

    private var totalUnsatCoreExtractionDuration: Duration by InitOnceProperty()

    private val unitedCodemaps: MutableList<CodeMap> = mutableListOf()

    private val baseCodeMap = generateCodeMap(originalTac, originalTac.name)

    companion object {
        suspend operator fun invoke(
            splitsInputData: Map<SplitAddress, UnsatCoreInputData>,
            originalTac: CoreTACProgram
        ) = UnsatCoreAnalysis(originalTac).apply {
            /** Compute unsat cores for individual splits **/
            val unionOfUnsatCores = mutableSetOf<SmtExpWithLExp>()
            val startTime = getCurrentTime()
            var iteration = 0
            var exceededTimelimit = false

            /**
            * We process the splits in the order given by their estimates for a single UC extraction. The rationale
            * is to process as many splits as possible if the overall timelimit [Config.TotalUnsatCoresEnumerationTimeout]
            * gets exceeded.
            */
            splitsInputData.keys.sortedBy { splitsInputData[it]!!.expectedSingleUCExtractionDuration() }
                .forEach { splitAddress ->
                    val iterationStartTime = getCurrentTime()
                    val splitData = splitsInputData[splitAddress]!!
                    if (startTime.elapsedNow() > Config.TotalUnsatCoresEnumerationTimeout && !exceededTimelimit) {
                        exceededTimelimit = true
                        lWarn(
                            "Exceeded overall time limit (${Config.TotalUnsatCoresEnumerationTimeout}) for unsat core " +
                                "enumeration. Fully processed $iteration/${splitsInputData.size} splits. For the remaining splits, " +
                                "we will collect just the initial (non-minimal) unsat cores."
                        )
                    }
                    iteration += 1

                    val solverTimelimitPad = 2.seconds
                    val solverTimelimit = (splitData.solverConfig?.timelimit ?: 10.seconds) + solverTimelimitPad
                    // CVC5 has a performance problem with incremental queries, therefore, we use only Z3.
                    val solverConfig = splitData.solverConfig?.takeIf { it.solverInfo is Z3SolverInfo }?.copy(
                        timelimit = solverTimelimit, incremental = true
                    ) ?: Z3SolverInfo.plainConfig(timelimit = solverTimelimit, incrementalMode = true)

                    /**
                    * As an optimisation, we mark the [unionOfUnsatCores] of already found MUSes (from the previous splits)
                    * as hard constraints when extracting MUSes for the subsequent splits. This can reduce the number
                    * of identified unsat cores and also increase their size. However, it will speed up the computation a lot.
                    * The rationale is that later we compute and return the union of the unsat cores [unionUnsatCores],
                    * so (the current) [unionOfUnsatCores] will be contained in the resultant union anyway.
                    * Also, currently, users typically compute just a single unsat core, so we do not even "reduce"
                    * the number of provided unsat cores.
                    */
                    val musSolver = MUSSubsetSolver(
                        splitData.query,
                        solverConfig,
                        userDefinedHard = unionOfUnsatCores.filterTo(mutableSetOf()) { it in splitData.query.formula() }
                    )
                    val muses = if (exceededTimelimit) {
                        listOf(musSolver.getInitialUnsatCore())
                    } else {
                        musSolver.getMUSes()
                    }
                    if (muses.isEmpty()) {
                        lWarn("List of unsat cores is empty for the split with address $splitAddress")
                    } else if (Config.UnsatCoresRequireUnion.get()) {
                        muses.forEach { unionOfUnsatCores.addAll(it) }
                    }

                    splitsUnsatCores[splitAddress] = muses.map { mus ->
                        mus.map {
                            splitData.tacCommandGraph.elab(it.lexp.meta[META_TOPLVL_CMD]!!).cmd
                        }.toSet()
                    }

                    musSolver.soft.forEach { softExp ->
                        val cmd = splitData.tacCommandGraph.elab(softExp.lexp.meta[META_TOPLVL_CMD]!!).cmd
                        unitedSoftConstraints.add(cmd)
                    }

                    lInfo(
                        "$splitAddress: Found ${muses.size} unsat cores with sizes " +
                            "${muses.map { it.size }}/${musSolver.soft.size + musSolver.userDefinedHard.size}, " +
                            "taking ${iterationStartTime.elapsedNow().inWholeMilliseconds}ms."
                    )
                }
            val unsatCoreExtractionsDuration = startTime.elapsedNow()
            lInfo("The unsat core extraction took ${unsatCoreExtractionsDuration.inWholeSeconds}s in total")

            /** Union (cartesian set product) unsat cores from individual splits to obtain unsat cores of [originalTac] **/
            val (_, unionDuration) = measureTimedValue { unionUnsatCores() }
            lInfo("Combining the unsat cores from individual splits took ${unionDuration.inWholeSeconds}s in total")

            totalUnsatCoreExtractionDuration = unsatCoreExtractionsDuration + unionDuration
        }
    }

    /**
     * Creates unsat cores of [originalTac] by combining (uniting) unsat cores of individual splits. Stores the combined
     * unsat cores to [unitedUnsatCores].
     */
    private fun unionUnsatCores() {

        /**
         * Two things to be done in the future:
         * 1. compute the cartesian product lazily (since there can be an exponential number of unsat cores)
         * 2. limit the number of computed unsat cores already in the init { ... } (meaning to not compute more unsat
         * cores for individual splits than what we need).
         */
        unitedUnsatCores.addAll(
            cartesianProduct(splitsUnsatCores.values).map { it.flatten().toSet() }.take(Config.NumOfUnsatCores.get())
        )

        /**
         * Supposing the splits we create are really subprograms of the original TAC (especially not introducing
         * any new commands), the union of unsat cores of individual splits should indeed form an unsat core of
         * the original TAC. Here, we check that the united unsat core is indeed contained in the original TAC.
         * Note that it would be good to have such tests directly on our splitting mechanism, however, that is for
         * another PR.
         *
         * To Be Solved in another PR: it seems like we currently add for every split an assume on the split's branching
         * boolean variable; these assumes are not in the original TAC. For simplicity, we check only non-assumes now.
         */
        unitedUnsatCores.forEach { core ->
            val fCore = core.filterNot { it is TACCmd.Simple.AssumeCmd || it is TACCmd.Simple.AssumeNotCmd }
            val origCmds = originalTac.analysisCache.graph.commands.mapToSet { it.cmd }
            val extra = fCore.filter { it !in origCmds }
            if (extra.isNotEmpty()) {
                Logger(LoggerTypes.MUS_ENUMERATION).warn {
                    "The united unsat core contains ${extra.size} commands that are not presented in the original " +
                        "TAC program: $extra"
                }
            }
        }


    }

    /**
     * Creates .html files illustrating the individual united unsat cores. We use the [CodeMap] class for the illustration
     * and store the .html files to the zipOutput/Reports folder.
     */
    private fun generateCodemaps() {
        unitedUnsatCores.forEachIndexed { index, mus ->
            val codeMap = addUnsatCoreData(
                name = "${originalTac.name}$OUTPUT_NAME_DELIMITER$index",
                unsatCore = mus.map { it.cmd },
                unsatCoreDomain = unitedSoftConstraints.toList(),
                codeMap = baseCodeMap
            )
            unitedCodemaps.add(codeMap)
        }
        lInfo("In total generated ${unitedCodemaps.size} codemaps for unsat cores with sizes: " +
            "${unitedCodemaps.map { it.unsatCore.size }}/${unitedCodemaps.firstOrNull()?.unsatCoreDomain?.size}")
    }

    /**
     * Creates .html files illustrating the individual united unsat cores. We use the [CodeMap] class for the
     * illustration.
     */
    fun renderCodeMaps(): List<CodeMap> {
        if (unitedCodemaps.isEmpty()) {
            generateCodemaps()
        }
        return unitedCodemaps
    }

    /**
     * Creates [UnsatCoresStats] for the united unsat cores we found and dumps them to a .json file stored in the
     * zipOutput/Reports folder. Returns the stats object.
     */
    fun dumpToJson(): UnsatCoresStats {
        if(splitsUnsatCores.isEmpty()) {
            Logger(LoggerTypes.MUS_ENUMERATION).warn { "There are no splits with unsat cores." }
            return UnsatCoresStats(
                runtime = 0.seconds,
                unsatCores = emptySet(),
                softConstraintsFromSpec = emptySet(),
                softConstraintsFromSol = emptySet(),
                callIds = emptySet()
            )
        }

        fun isFromSpec(cmd: TACCmd) =
            cmd.meta.size > 0 &&
                cmd.meta.map.values.any { m -> m is CVLExpToTACExprMeta } &&
                (cmd.meta[TACMeta.CVL_RANGE] as? Range.Range) != null
        fun isFromSol(cmd: TACCmd) = cmd.metaSrcInfo?.getSourceDetails() != null


        /**
         * Sometimes there are very long cmds (maybe containing even the full .sol file?). So, we shorten it before
         * storing to the .json file.
         *
         * This method also sanitizes the command such that any HTML element are removed (to avoid XSS attacks).
         */
        fun trimCmdIfTooLong(cmd: String): String {
            val maxCmdLength = 500
            return if (cmd.length <= maxCmdLength) {
                cmd
            } else {
                cmd.take(maxCmdLength) + "...trimmed..."
            }.sanitize()
        }


        fun getSpecCmdData(cmd: TACCmd): UnsatCoreCmdFromSpec {
            require(isFromSpec(cmd)) { "$cmd is not a command from spec and hence cannot be converted to UnsatCoreCmdFromSpec" }
            val rangeMeta = cmd.meta[TACMeta.CVL_RANGE] as? Range.Range
            val expMeta = cmd.meta[TACMeta.CVL_EXP] as CVLExpToTACExprMeta
            val cmdString = expMeta.exp.toString()
            check(rangeMeta != null)
            return UnsatCoreCmdFromSpec(
                trimCmdIfTooLong(cmdString),
                rangeMeta.specFile,
                rangeMeta.start,
                rangeMeta.end,
                rangeMeta.start.line.toInt() + 1 //zero vs 1 indexing in meta and metaDetails...
            )
        }

        fun getSolCmdData(cmd: TACCmd): UnsatCoreCmdFromSol {
            require(isFromSol(cmd)) { "$cmd is not a cmd from .sol and hence cannot be converted to UnsatCoreCmdFromSpec" }
            val metaDetails = cmd.metaSrcInfo?.getSourceDetails()
            check(metaDetails != null)
            return UnsatCoreCmdFromSol(
                trimCmdIfTooLong(metaDetails.content),
                metaDetails.file,
                metaDetails.lineNumber,
            )
        }

        val softCmdsFromSpec = unitedSoftConstraints.filter { isFromSpec(it.cmd) }.mapToSet { getSpecCmdData(it) }
        val softCmdsFromSol = unitedSoftConstraints.filter { isFromSol(it.cmd) }.mapToSet { getSolCmdData(it) }

        if (unitedCodemaps.isEmpty()) {
            generateCodemaps()
        }

        val stats = UnsatCoresStats(
            runtime = totalUnsatCoreExtractionDuration,
            unsatCores = unitedCodemaps.map { codeMap ->

                val cmdsFromSpec = codeMap.unsatCore.mapNotNullToSet { it.takeIf(::isFromSpec)?.let(::getSpecCmdData) }
                val cmdsFromSol = codeMap.unsatCore.mapNotNullToSet { it.takeIf(::isFromSol)?.let(::getSolCmdData) }
                val callIdsTouching = codeMap.callIdNames.filter { codeMap.touchesUnsatCore(it.key) }
                    .map { it.toString() }.toSet()
                SingleUnsatCoreStats(
                    runtime = null,
                    /** To be added in another PR (we also need to measure the time within [MUSSubsetSolver] **/
                    cmdsFromSpec = cmdsFromSpec,
                    cmdsFromSol = cmdsFromSol,
                    callIdsTouchingUnsatCore = callIdsTouching,
                    missingCmdsFromSpec = softCmdsFromSpec.minus(cmdsFromSpec),
                    missingCmdsFromSol = softCmdsFromSol.minus(cmdsFromSol),
                    callIdsNotTouchingUnsatCore = baseCodeMap.callIdNames.map { it.toString() }.toSet().minus(callIdsTouching)
                )
            }.toSet(),
            softConstraintsFromSpec = softCmdsFromSpec,
            softConstraintsFromSol = softCmdsFromSol,
            callIds = baseCodeMap.callIdNames.map { it.toString() }.toSet(),
        )

        SmtFileDumping.dumpUnsatCoresStatsFile(
            baseCodeMap.name,
            UnsatCoresJavaSerializerWrapper(stats).toString()
        )
        return stats
    }

    fun dumpToJsonAndRenderCodemaps() {
        dumpToJson()
        renderCodeMaps().forEachIndexed { i, codeMap ->
            HTMLReporter.dumpCodeMapToHTMLReport(
                codeMap,
                ReportTypes.REPORT,
                HTMLReporter.ReportNameIndex.UnsatCore(i)
            )
        }
    }
}
