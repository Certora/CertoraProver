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

package verifier.autoconfig

import config.Config
import datastructures.stdcollections.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import log.*
import smt.MultipleCEXStrategyEnum
import smt.PrettifyCEXEnum
import smt.UseLIAEnum
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult
import solver.SolverChoice
import solver.SolverConfig
import solver.SolverResult
import statistics.GeneralKeyValueStats
import statistics.SDCollectorFactory
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACExpr
import verifier.*
import verifier.autoconfig.AutoConfigManager.Companion.statsCollectorEntryName
import verifier.splits.SplitAddress
import verifier.splits.SplitTree
import java.io.Closeable
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.time.Duration


/**
 * Maintains [SingleBasicRuleStatistics] about individual splits that are processed while solving the [ruleName].
 * Implements [Closeable]; when closed, writes the statistics to the splitStatsCollector SDCollector
 * under the key [statsCollectorEntryName]. Also maintains solver usefulness statistics.
 * This class is to be extended to an "autoconfiguration manager", i.e., it will not just store statistics about splits,
 * but also propose suitable solving strategies for splits.
 */
class AutoConfigManager(val ruleName: String) : Closeable {
    private val loadedStats: SingleBasicRuleStatistics? by lazy { loadAll() }
    private val stats = SingleBasicRuleStatistics(ruleName)
    private val splitStatAndSolverConfigLock = Any()
    private val solverConfigsStats = SolverConfigsStats()
    private val learnFromCurrent = Config.AutoconfigLearnFromCurrent.get()

    private val solverIncrementalMode = Config.prettifyCEX.get() != PrettifyCEXEnum.NONE ||
        Config.MultipleCEXStrategy.get() != MultipleCEXStrategyEnum.NONE

    private val logger = Logger(LoggerTypes.AUTOCONFIG)
    private fun info(msg: String) {
        logger.info { "$ruleName - $msg" }
    }
    private fun warn(msg: String, e: Exception? = null) {
        logger.warn(e) { "$ruleName - $msg" }
    }

    /**
     * Assumes the split with [address] is UNSAT and this information is logged in [stats]. The function checks
     * whether the sibling of [address] is also know to be UNSAT and if yes, we propagate in [stats] that
     * their parent is also UNSAT. The propagation is recursive.
     */
    private fun propagateUNSAT(address: SplitAddress): Unit = synchronized(splitStatAndSolverConfigLock) {
        if (address.name() !in stats.splitStatistics) {
            warn("The split with the address $address should be first added to ${::stats.name} before we propagate its unsat result")
            return
        }
        if (!stats.splitStatistics[address.name()]!!.isEventuallyUNSAT()) {
            warn("Trying to propagate an unregistered UNSAT result of the split $address")
            return
        }

        if (address.isRoot) {
            return //the whole input program...no parents to propagate to
        }
        if (address !is SplitAddress.Block) {
            return
        }
        val siblingAddressStr = address.sibling().name()
        if (stats.splitStatistics[siblingAddressStr]?.isEventuallyUNSAT() == true) {
            val parentAddress = address.parent
            val parentAddressStr = parentAddress?.name() ?: "null"
            if (parentAddressStr !in stats.splitStatistics) {
                warn("The parent address $parentAddressStr is not registered in ${::stats.name}")
                return
            }
            if (!stats.splitStatistics[parentAddressStr]!!.isEventuallyUNSAT()) {
                stats.splitStatistics[parentAddressStr] =
                    stats.splitStatistics[parentAddressStr]!!.copy(finalResult = BasicSplitStatistics.propagatedUnsatResult)
                propagateUNSAT(parentAddress!!)
            }
        }
    }

    /**
     * Sets the [finalResult] and [solvers] of the stats of [address] based on the stats of [matchingUnsat].
     */
    private fun markMatchingUnsat(address: SplitAddress, matchingUnsat: BasicSplitStatistics) = synchronized(splitStatAndSolverConfigLock) {
        if(address.name() !in stats.splitStatistics) {
            warn("Trying to mark an address UNSAT that is not in ${::stats.name}: $address")
            return
        }
        stats.splitStatistics[address.name()] =
            stats.splitStatistics[address.name()]!!.copy(
                finalResult = matchingUnsat.finalResult,
                solvers = matchingUnsat.solvers
            )
    }

    /**
     * Loads the digest of [address] from [stats] and returns it. If the digest is not computed yet,
     * it gets computed and stored in [stats].
     */
    private fun getDigest(address: SplitAddress, prog: CoreTACProgram): String = synchronized(splitStatAndSolverConfigLock) {
        val addressName = address.name()
        require(addressName in stats.splitStatistics) {
            "Trying to store a digest for a split ${addressName} that is not registered yet"
        }
        if (stats.splitStatistics[addressName]!!.tacStats.digest == null) {
            val digest = BasicTACStatistics.computeDigest(prog)
            stats.splitStatistics[addressName] =
                stats.splitStatistics[addressName]!!.let {
                    it.copy(tacStats = it.tacStats.copy(digest = digest))
                }
        }
        return stats.splitStatistics[addressName]!!.tacStats.digest!!
    }

    /**
     * Register the given [subProblem] in [stats] (storing just address, split name, and basic TAC statistics).
     */
    fun registerSplit(address: SplitAddress, name: String, subProblemTAC: CoreTACProgram) = synchronized(splitStatAndSolverConfigLock) {
        val addressName = address.name()
        if (addressName !in stats.splitStatistics.keys) {
            info("registering split ${address.name()}")
            stats.splitStatistics[addressName] = BasicSplitStatistics(
                address = addressName,
                splitName = name,
                tacStats = BasicTACStatistics.fromCoreTACProgram(subProblemTAC, Config.AutoconfigUseDigests.get()),
            )
        }
    }

    /**
     * Stores the verification result of [splitAddress] to [stats] (requires [splitAddress] to be already registered
     * in [stats]).
     */
    fun registerSplitResult(
        splitAddress: SplitAddress,
        verifierResult: Verifier.VerifierResult,
        smtFormulaCheckerResult: SmtFormulaCheckerResult,
        timeoutForSubProblem: Duration,
        solvingDuration: Duration,
    ) = synchronized(splitStatAndSolverConfigLock) {
        val address = splitAddress.name()
        info("registering ${verifierResult.finalResult} result for split $address.")
        if(address !in stats.splitStatistics) {
            warn("Trying to register results for an unregistered split $address.")
            return
        }

        val solvers = mutableMapOf<BaseLogic, MutableMap<String, MutableList<String>>>()
        @Suppress("ForbiddenMethodCall") //TODO CERT-3995 (eliminate the string ops)
        smtFormulaCheckerResult.subResultsFlattened
            .filterIsInstance<SmtFormulaCheckerResult.Single.Simple>()
            .filter { it.solverInstanceInfo != null }
            .filter { "Constrained" !in it.solverInstanceInfo!!.name } //TODO CERT-3995: we want ot also log these, though not consider when choosing a solver
            .forEach {
                val logic = BaseLogic.fromLogic(it.query.smtFormula.logic)
                val solverName = it.solverInstanceInfo!!.solverConfig.fullName
                val res = LExpVCStatsLogger.getResultStr(it.satResult)
                (solvers.getOrPut(logic) { mutableMapOf() }).getOrPut(res) { mutableListOf() }.add(solverName)
            }

        stats.splitStatistics[address] = stats.splitStatistics[address]!!.copy(
            finalResult = BasicSplitStatistics.resultFromSolverResult(verifierResult.finalResult),
            timeout = timeoutForSubProblem,
            smtSolvingWallTime = solvingDuration,
            solvers = solvers
        )

        val hasLIAWinner = solvers[BaseLogic.LIA]?.keys?.any { BasicSplitStatistics.isUsefulSolverResult(it) } ?: false
        val hasNIAWinner = solvers[BaseLogic.NIA]?.keys?.any { BasicSplitStatistics.isUsefulSolverResult(it) } ?: false
        //update the solver stats
        solvers[BaseLogic.LIA]?.forEachEntry { (res, solvers) ->
            solvers.forEach { solverName ->
                solverConfigsStats.addResult(SolverId(solverName, BaseLogic.LIA), res, hasLIAWinner)
            }
        }
        solvers[BaseLogic.NIA]?.forEachEntry { (res, solvers) ->
            solvers.forEach { solverName ->
                solverConfigsStats.addResult(SolverId(solverName, BaseLogic.NIA), res, hasNIAWinner)
            }
        }

        if (verifierResult.finalResult == SolverResult.UNSAT) {
            propagateUNSAT(splitAddress)
        }
    }

    /**
     * Loads statistics from the `--prover_resource_files ac:<stats.json>` file if provided by the user.
     */
    private fun loadAll(): SingleBasicRuleStatistics? {
        val resourceLabel = "ac:"

        @Suppress("ForbiddenMethodCall")
        val acResourcesProvided =
            Config.ResourceFiles.getOrNull()?.filter { it.startsWith(resourceLabel) }?.ifEmpty { null }
                ?: return null
        if (acResourcesProvided.size > 1) {
            Logger.alwaysWarn("Got more than one resource file with the autoconfig label '$resourceLabel'.")
        }

        val path = acResourcesProvided.first().substring(resourceLabel.length).trim()
        val wrappedPath = Path(ArtifactFileUtils.wrapPathWith(path, Config.getSourcesSubdirInInternal()))
        if (!wrappedPath.toFile().exists()) {
            warn("The specified autoconfig statistics file does not exist: $wrappedPath")
            return null
        }

        val parsed = try {
            val content = wrappedPath.toFile().readText()
            Json.decodeFromString<BasicRulesStatistics>(content)
        } catch (e: SerializationException) {
            warn("Failed to parse the specified autoconfig statistics file: $wrappedPath", e)
            null
        }
        return parsed?.splitStats?.singleOrNull { it.ruleName == ruleName }
    }

    fun getConfig(split: SplitTree.Node, subProblemTAC: CoreTACProgram): TACVerifierConfig.TACVerifierSettings? {
        if (!learnFromCurrent && loadedStats == null) {
            return null
        }

        /**
         * If we cannot split anymore, we do not try to optimise via the autoconfig. We simply follow the configuration
         * given by the user (typically run the full solver portfolio).
         * We might want to improve here in the future, e.g. run all solvers, but prioritise them via the statistics
         * we have.
         */
        if(!split.splittable) {
            return null
        }

        val splitAddress = split.address
        val tacStats = BasicTACStatistics.fromCoreTACProgram(subProblemTAC, Config.AutoconfigUseDigests.get())

        val loadedMatches = loadedStats?.splitStatistics?.values?.filter { tacStats.match(it.tacStats) } ?: listOf()
        //Splits we have already solved in this CVT run and which are matching the current split.
        val newMatches: List<BasicSplitStatistics> = if (learnFromCurrent) {
            val splitAddressStr = splitAddress.name()
            synchronized(splitStatAndSolverConfigLock) {
                stats.splitStatistics.values.filter { tacStats.match(it.tacStats) && it.address != splitAddressStr }
            }
        } else {
            listOf()
        }

        info("${loadedMatches.size} loaded matches for ${split.name}")
        info("${newMatches.size} new matches for ${split.name}")

        fun pickSolversAndBuildConfig(
            cachedLiaSolvers: Collection<String> = listOf(),
            cachedNiaSolvers: Collection<String> = listOf(),
            resplit: Boolean
        ): TACVerifierConfig.TACVerifierSettings? {
            if(resplit) {
                info("Resplitting.")
                return TACVerifierConfig.TACVerifierSettings(resplit = true)
            }

            val maxSolversPerLogic = 4

            val statsBasedLiaSolvers = synchronized(splitStatAndSolverConfigLock) {
                solverConfigsStats.getPrioritisedLIAConfigs(maxSolversPerLogic)
            }
            val statsBasedNiaSolvers = synchronized(splitStatAndSolverConfigLock) {
                solverConfigsStats.getPrioritisedNIAConfigs(maxSolversPerLogic)
            }

            val pickedLiaSolvers = (cachedLiaSolvers + statsBasedLiaSolvers).distinct().take(maxSolversPerLogic)
            val pickedNiaSolvers = (cachedNiaSolvers + statsBasedNiaSolvers).distinct().take(maxSolversPerLogic)

            val liaSC = pickedLiaSolvers.map { SolverConfig(it).copy(incremental = solverIncrementalMode) }
            val niaSC = pickedNiaSolvers.map { SolverConfig(it).copy(incremental = solverIncrementalMode) }
            return if (liaSC.isNotEmpty() || niaSC.isNotEmpty()) {
                info("Selecting LIA${liaSC.map { it.fullName }} and NIA${niaSC.map { it.fullName }}.")
                TACVerifierConfig.TACVerifierSettings(
                    liaSolvers = SolverChoice(liaSC),
                    niaSolvers = SolverChoice(niaSC),
                    useLIA = if (liaSC.isNotEmpty()) { UseLIAEnum.WITH_VERIFIER } else { UseLIAEnum.NONE },
                    useNIA = niaSC.isNotEmpty(),
                    resplit = false
                )
            } else {
                info("Not suggesting any solver configs.")
                null
            }
        }

        val matches = loadedMatches + newMatches
        if (matches.isEmpty()) {
            return if (!learnFromCurrent) {
                null
            } else {
                pickSolversAndBuildConfig(resplit = false)
            }
        }

        val unsatMatches = matches.filter { it.isUNSAT() }
        val propagatedUnsatMatches = matches.filter { it.isPropagatedUNSAT() }
        val allUnsatMatches = unsatMatches + propagatedUnsatMatches
        val satMatches = matches.filter { it.isSAT() }

        //Check whether we have a cached UNSAT result for the split using the digest matching
        if (allUnsatMatches.isNotEmpty() && Config.AutoconfigUseDigests.get()) {
            val splitDigest = getDigest(splitAddress, subProblemTAC)
            allUnsatMatches.find { it.tacStats.digest == splitDigest }?.let { matchingUnsat ->
                info("Found cached UNSAT result for ${split.name}")
                markMatchingUnsat(splitAddress, matchingUnsat)
                return TACVerifierConfig.TACVerifierSettings(knownToBeUnsat = true)
            }
        }

        info("${unsatMatches.size} UNSAT matches for ${split.name}")
        info("${satMatches.size} SAT matches for ${split.name}")

        val liaSolvers = (unsatMatches.getUsefulLIASolvers() + satMatches.getUsefulLIASolvers()).toSet()
        val niaSolvers = (unsatMatches.getUsefulNIASolvers() + satMatches.getUsefulNIASolvers()).toSet()
        return pickSolversAndBuildConfig(liaSolvers, niaSolvers, liaSolvers.isEmpty() && niaSolvers.isEmpty())
    }

    private fun writeStats() {
        SDCollectorFactory.splitStatsCollector().collectFeature(
            GeneralKeyValueStats<BasicSplitStatisticsJavaSerializerWrapper>().addKeyValue(
                statsCollectorEntryName,
                BasicSplitStatisticsJavaSerializerWrapper(stats)
            )
        )
    }

    override fun close() {
        writeStats()
    }

    companion object {
        const val statsCollectorEntryName = "splitStats"
    }
}

fun BasicTACStatistics.match(other: BasicTACStatistics): Boolean {
    fun closeEnough(cmd: String, v1: Int, v2: Int): Boolean {
        /**
         * The list is not complete... just a few cmds for start.
         * Storing and comparing the CFG structure would be better (including types of cmds)
         */
        val hardCmds = listOf(
            TACExpr.Vec.IntMul.Binary::class.java.typeName,
            TACExpr.Vec.Mul::class.java.typeName,
            TACExpr.BinOp.Mod::class.java.typeName,
            TACExpr.BinOp.Div::class.java.typeName,
            TACExpr.BinOp.SDiv::class.java.typeName,
            TACExpr.BinOp.IntDiv::class.java.typeName
        )

        val allowedThreshold = if (cmd in hardCmds) {
            Config.AutoconfigHardCmdsThreshold.get()
        } else {
            Config.AutoconfigSoftCmdsThreshold.get()
        }
        return abs(v1 - v2) <= allowedThreshold
    }
    return this.cmdCounts.all {
        it.key in other.cmdCounts && other.cmdCounts[it.key] != null && closeEnough(it.key, it.value, other.cmdCounts[it.key]!!)
    }
}

