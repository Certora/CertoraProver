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

package solver

import kotlinx.coroutines.flow.*
import ksp.dynamicconversion.DynamicConverter
import log.*
import utils.*
import java.io.Serializable
import kotlin.time.Duration

/**
 * Instances of this abstract class contain static information about some solver (e.g. default command to execute it,
 *  how to set a time limit, etc)
 *
 * Note this cannot live in package SMTLibUtils, though maybe counterintuitive, because Config needs it, which is in
 *  package Shared.
 */

abstract class SolverInfo(val name: String) : Serializable {

    init {
        allSolverInfos[name] = this
    }

    abstract val defaultCommand: String

    protected open val versionQuery: String
        get() = "--version"

    private val defaultCommandVersionInfo: String? by lazy {
        RuntimeEnvInfo.getSolverVersionIfAvailable(this, versionQuery)?.let { (stdout, stderr) -> stdout + stderr }
    }

    fun isAvailable() = defaultCommandVersionInfo != null

    open fun getSolverVersionStringOrNull(): String? =
        RuntimeEnvInfo.getSolverVersionIfAvailable(this, versionQuery)?.first?.lines()?.get(0)

    val isDefaultCommandAvailable: Boolean = defaultCommandVersionInfo != null

    /** Indicates whether this solver supports resets */
    open val supportsReset: Boolean = true

    open val supportsNewSmtLibBvOverflowSymbols get() = false

    /** Get command line option for incremental mode */
    open fun getOptionForIncremental(): List<String> = listOf()
    /** Get command line option for self-imposed per-check time limit */
    abstract fun getOptionForTimelimit(timelimit: Duration): List<String>
    /** Get command line option for random seed */
    open fun getOptionForRandomSeed(randomSeed: Int): List<String> = listOf()
    /** Get command line option for self-imposed memory limit */
    open fun getOptionForMemlimit(memlimitBytes: Long): List<String> = listOf()

    /**
     * Get SMT-LIB command to change the per-check time limit. May return null if the solver does not support it. The
     * command is returned as a string instead of a proper [smtlibutils.data.Cmd] because of the commands not being
     * visible in the GeneralUtils package. The resulting string should be wrapped in a [Cmd.Custom].
     */
    open fun getCmdToChangeTimelimit(timelimit: Duration): String? = null

    /**
     * Returns the command that starts the solver in a mode where it accepts input from stdIn.
     */
    abstract fun commandForStdInMode(clOptions: List<String>, customBinary: String?): List<String>

    abstract fun supportsLogicFeatures(features: SolverConfig.LogicFeatures): Boolean

    /**
     * Sometimes solvers reject a logic, although they actually support it and when coerced to accept it using the `ALL`
     * logic everything is fine. For example, z3 rejects logics with `DT`, but supports such inputs just fine.
     * @return true if the logic should be changed to `ALL` for this solver
     */
    open fun needsLogicALL(features: SolverConfig.LogicFeatures): Boolean = false
    override fun toString(): String = name

    /**
     * A bit hacky, but a first step towards tying output parsing to the individual [SolverInfo]s for each solver, which
     * makes sense.
     * In the future, maybe the [SolverInfo]s should take over the whole job from `SatResult.fromCheckSatOutput`
     */
    open fun preprocessCheckSatOutput(lines: Flow<String>): Flow<String> = lines

    companion object {
        val logger = Logger(GeneralUtilsLoggerTypes.SOLVERCONFIG)

        val allSolverInfos: MutableMap<String,SolverInfo> = mutableMapOf()

        /**
         * Returns a list of SolverConfig based on a list of options [allowedClOptions] that "qualifies" for
         * given [logicFeatures]. The order of returned list preserves the order of [allowedClOptions] and it
         * is important, as it gives precedence when running races of solver configs on a query.
         */
        fun getDefaultConfigs(
            timelimit: Duration,
            memlimitBytes: Long?,
            incrementalMode: Boolean,
            logicFeatures: SolverConfig.LogicFeatures,
            allowedClOptions: List<SolverConfig>,
        ): List<SolverConfig> {
            require(allowedClOptions.isNotEmpty()) { "disallowed all SolverClOptions, yet asking for some" }
            return allowedClOptions.filter { it.qualifiesFor(logicFeatures) }.map {
                it.copy(
                    timelimit = timelimit,
                    memlimitBytes = memlimitBytes,
                    incremental = incrementalMode
                )
            }
        }

    }

    class Converter: DynamicConverter<SolverInfo> {
        override fun invoke(v: Any) = allSolverInfos[v as String] ?: throw ConfigurationException("No SolverInfo ${v}")
    }
}

/**
 * Represents a selection of smt solvers that the user selected via command line flags.
 * (Currently: the flags `Config.CheckAllSolvers` and `Config.SolverProgramChoice`)
 *
 * The order is important here, as it will give precedence in running parallel races.
 * As of now, the general order is:
 * - yices (very fast for instances that can be solved quickly)
 * - z3 (generally solves the most)
 * - cvc5 (best complementary)
 * - cvc4 (also worth trying)
 */
data class SolverChoice private constructor(val solverInfoClOptions: List<SolverConfig>) :
    List<SolverConfig> by solverInfoClOptions {
    constructor(solverInfos: Collection<SolverConfig>) : this(solverInfos.toList().distinct())

    override fun toString(): String = solverInfoClOptions.toString()

    fun toConciseString(): String = solverInfoClOptions.map { it.fullName }.joinToString(", ")

    companion object {
        fun fromFlags(checkAllSolvers: Boolean, solverChoice: List<SolverConfig>): SolverChoice =
            if (checkAllSolvers) {
                AllCommonAvailableSolversWithClOptions
            } else {
                SolverChoice(solverChoice)
            }

        private fun finalizeSelection(prioritizedConfigs: List<SolverConfig>): SolverChoice {
            val allConfigs = SolverConfig.bitwuzla.values +
                SolverConfig.cvc4.values +
                SolverConfig.cvc5.values +
                SolverConfig.yices.values +
                SolverConfig.z3.values
            val sortedAllConfigs = mutableListOf<SolverConfig>()
            sortedAllConfigs.addAll(prioritizedConfigs)
            sortedAllConfigs.addMatching(allConfigs) { it !in sortedAllConfigs }

            return SolverChoice(sortedAllConfigs.filter { it.solverInfo.isDefaultCommandAvailable })
        }

        /**
         * Solvers that we routinely use in our main pipeline (in contrast to those that are special-purpose, so far,
         * e.g. for manual proving). The following lists are used as default in command line options. If user does
         * not overwrite them, they are replaced by some specialized list bellow. We used different solvers ordering for
         * different theories (LIA, NIA, BV) and race timeouts as defined by the lists bellow. We created the lists
         * according to experiments we did with solvers directly on SMT formulas. The experiments showed that
         * some solvers are more efficient for a particular SMT theory and difficulty of task (which is reflected by
         * using different tasks for different timeout bounds). An appropriate solver for a race is  chosen in
         * function [getDefaultSolvers] in [LExpVcCheckerConfig.kt] according to SMT theory and the timeout.
         */
        val AllCommonAvailableSolversWithClOptions: SolverChoice by lazy {
            val prioritizedConfigs = listOf(
                SolverConfig.yices.def,
                SolverConfig.z3.lia1,
                SolverConfig.z3.lia2,
                SolverConfig.z3.def,
                SolverConfig.z3.eq1,
                SolverConfig.z3.eq2,
                SolverConfig.cvc5.lin,
                SolverConfig.cvc5.nonlin,
                SolverConfig.cvc4.lin,
                SolverConfig.cvc4.nonlin,
                SolverConfig.cvc5.linNoDio,
                SolverConfig.z3.arith2,
                SolverConfig.z3.arith1,
                SolverConfig.cvc5.def,
                SolverConfig.cvc4.def,
                SolverConfig.cvc5.q
            )

            finalizeSelection(prioritizedConfigs)
        }

        val LIASolversSmallTimeout: SolverChoice by lazy {
            val prioritizedConfigs = listOf(
                SolverConfig.yices.def,
                SolverConfig.z3.lia2,
                SolverConfig.cvc5.lin,
                SolverConfig.cvc4.def,
                SolverConfig.z3.lia1,
                SolverConfig.z3.def,
                SolverConfig.z3.eq1,
                SolverConfig.z3.eq2,
                SolverConfig.z3.arith1,
                SolverConfig.z3.arith2,
                SolverConfig.cvc5.linNoDio,
                SolverConfig.cvc4.lin,
                SolverConfig.cvc5.def,
                SolverConfig.cvc5.q
            )

            SolverChoice(prioritizedConfigs)
        }

        val LIASolversMediumTimeout: SolverChoice by lazy {
            val prioritizedConfigs = listOf(
                SolverConfig.yices.def,
                SolverConfig.z3.lia2,
                SolverConfig.z3.lia1,
                SolverConfig.cvc5.linNoDio,
                SolverConfig.cvc5.lin,
                SolverConfig.cvc4.lin,
                SolverConfig.z3.arith2,
                SolverConfig.z3.def,
                SolverConfig.z3.arith1,
                SolverConfig.z3.eq2,
                SolverConfig.z3.eq1,
                SolverConfig.cvc4.def,
                SolverConfig.cvc5.def,
                SolverConfig.cvc5.q
            )

            SolverChoice(prioritizedConfigs)
        }

        val LIASolversLargeTimeout: SolverChoice by lazy {
            val prioritizedConfigs = listOf(
                SolverConfig.yices.def,
                SolverConfig.z3.lia2,
                SolverConfig.z3.lia1,
                SolverConfig.cvc5.linNoDio,
                SolverConfig.cvc5.lin,
                SolverConfig.cvc4.lin,
                SolverConfig.z3.arith2,
                SolverConfig.z3.eq2,
                SolverConfig.z3.eq1,
                SolverConfig.z3.arith1,
                SolverConfig.z3.def,
                SolverConfig.cvc5.def,
                SolverConfig.cvc4.def,
                SolverConfig.cvc5.q
            )

            SolverChoice(prioritizedConfigs)
        }

        val NIASolversSmallTimeout: SolverChoice by lazy {
            val prioritizedConfigs = listOf(
                SolverConfig.yices.def,
                SolverConfig.z3.def,
                SolverConfig.cvc5.nonlin,
                SolverConfig.cvc4.nonlin,
                SolverConfig.z3.arith2,
                SolverConfig.z3.eq1,
                SolverConfig.cvc5.def,
                SolverConfig.z3.arith1,
                SolverConfig.z3.eq2,
                SolverConfig.cvc4.def,
                SolverConfig.cvc5.nonlinNoDio,
                SolverConfig.cvc5.q,
                SolverConfig.cvc4.q,
            )

            SolverChoice(prioritizedConfigs)
        }

        val NIASolversMediumTimeout: SolverChoice by lazy {
            val prioritizedConfigs = listOf(
                SolverConfig.yices.def,
                SolverConfig.z3.def,
                SolverConfig.cvc5.nonlin,
                SolverConfig.cvc4.nonlin,
                SolverConfig.z3.arith2,
                SolverConfig.z3.arith1,
                SolverConfig.z3.eq1,
                SolverConfig.z3.eq2,
                SolverConfig.cvc5.nonlinNoDio,
                SolverConfig.cvc5.def,
                SolverConfig.cvc4.def,
                SolverConfig.cvc5.q
            )

            SolverChoice(prioritizedConfigs)
        }

        val NIASolversLargeTimeout: SolverChoice by lazy {
            val prioritizedConfigs = listOf(
                SolverConfig.yices.def,
                SolverConfig.z3.def,
                SolverConfig.cvc5.nonlinNoDio,
                SolverConfig.cvc5.nonlin,
                SolverConfig.cvc4.nonlin,
                SolverConfig.z3.arith1,
                SolverConfig.z3.eq2,
                SolverConfig.z3.eq1,
                SolverConfig.z3.arith2,
                SolverConfig.cvc5.def,
                SolverConfig.cvc4.def,
                SolverConfig.cvc5.q
            )

            SolverChoice(prioritizedConfigs)
        }

        val BvCommonAvailableSolversWithClOptions: SolverChoice by lazy {
            val prioritizedConfigs = listOf(
                SolverConfig.z3.lia1,
                SolverConfig.z3.lia2,
                SolverConfig.cvc5.bv,
                SolverConfig.z3.def,
                SolverConfig.yices.def,
                SolverConfig.z3.eq1,
                SolverConfig.z3.eq2,
                SolverConfig.cvc4.nonlin,
                SolverConfig.cvc5.lin,
                SolverConfig.cvc4.lin,
                SolverConfig.cvc5.nonlin,
                SolverConfig.cvc5.def,
                SolverConfig.cvc4.def,
                SolverConfig.cvc5.q

            )

            finalizeSelection(prioritizedConfigs)
        }
    }
}
