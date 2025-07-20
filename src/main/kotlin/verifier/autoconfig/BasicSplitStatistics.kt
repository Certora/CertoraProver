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

import datastructures.stdcollections.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import log.*
import solver.SolverResult
import utils.runIf
import vc.data.CoreTACProgram
import vc.data.TACCmd
import kotlin.time.Duration

/**
 * Stores basic statistics of a TAC program. In particular, currently we store i] the digest (hash) of the program,
 * and ii] counts of individual commands in the program and counts of rhs-commands of assignExpCmds.
 * This class is used by [AutoConfigManager] to identify same or similar TAC programs.
 */
@Serializable
data class BasicTACStatistics(
    val cmdCounts: Map<String, Int>,
    val digest: String? = null
) {
    companion object {
        fun fromCoreTACProgram(prog: CoreTACProgram, storeDigest: Boolean): BasicTACStatistics {
            val cmds = mutableMapOf<String, Int>()
            prog.code.values.forEach {
                it.forEach { cmd ->
                    cmd.nameString().let { c -> cmds[c] = cmds.getOrDefault(c, 0) + 1 }
                    if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                        cmdToString(cmd.rhs).let { c -> cmds[c] = cmds.getOrDefault(c, 0) + 1 }
                    }
                }
            }

            return BasicTACStatistics(cmds, runIf(storeDigest) { computeDigest(prog) })
        }

        fun cmdToString(cmd: Any): String = cmd::class.java.typeName

        /**
         * Computes the digest of the [prg], either via [CoreTACProgram.digest] or via [prg.code.values.toString()].
         * The latter might lead to collisions (this needs to be checked actually) but is pretty fast,
         * whereas the former is relatively slow (could take seconds) but should be better ([CoreTACProgram.digest]
         * is currently used for the caching of rules).
         */
        fun computeDigest(prg: CoreTACProgram, builtIn: Boolean = true): String {
            return if (builtIn) {
                //This one shall be sound, though it might be a bit expensive
                prg.digest()
            } else {
                //This one is probably not sound - just for testing now
                prg.code.values.toString().hashCode().toString()
            }
        }
    }
}

@Serializable
data class BasicSplitStatistics(
    val address: String,
    val splitName: String,
    val finalResult: String? = null,
    val timeout: Duration? = null,
    val smtSolvingWallTime: Duration? = null,
    val tacStats: BasicTACStatistics,
    /** The structure is solvers[[logic]][[result]] = [[solverA, solverB, ...]] **/
    val solvers: Map<BaseLogic, Map<String, List<String>>> = mutableMapOf()
) {
    fun isSAT() = finalResult == satResult
    fun isUNSAT() = finalResult == unsatResult
    fun isPropagatedUNSAT() = finalResult == propagatedUnsatResult
    fun isEventuallyUNSAT() = isUNSAT() || isPropagatedUNSAT()
    fun getUsefulLIASolvers() = solvers[BaseLogic.LIA]?.filter { isUsefulSolverResult(it.key) }?.values?.flatten()
        ?.filter { isBaseSolver(it) } ?: listOf()

    fun getUsefulNIASolvers() = solvers[BaseLogic.NIA]?.filter { isUsefulSolverResult(it.key) }?.values?.flatten()
        ?.filter { isBaseSolver(it) } ?: listOf()

    companion object {
        const val satResult = "SAT"
        const val overApproxSatResult = "Overapproximation(SAT)"
        const val unsatResult = "UNSAT"
        const val propagatedUnsatResult = "PROPAGATED_UNSAT"
        const val unknownResult = "UNKNOWN"

        private val usefulSolverResults = listOf(satResult, unsatResult, overApproxSatResult)

        @Suppress("ForbiddenMethodCall")
        fun isBaseSolver(solverName: String) = "Constrained" !in solverName
        fun isUsefulSolverResult(res: String) = res in usefulSolverResults
        fun resultFromSolverResult(res: SolverResult) = when(res) {
            SolverResult.SAT -> satResult
            SolverResult.UNSAT -> unsatResult
            else -> unknownResult
        }
    }
}

fun List<BasicSplitStatistics>.getUsefulLIASolvers() = this.flatMap { it.getUsefulLIASolvers() }
fun List<BasicSplitStatistics>.getUsefulNIASolvers() = this.flatMap { it.getUsefulNIASolvers() }

@Serializable
data class SingleBasicRuleStatistics(
    val ruleName: String,
    val splitStatistics: MutableMap<String, BasicSplitStatistics> = mutableMapOf(),
)

@Serializable
data class BasicRulesStatistics(
    val splitStats: List<SingleBasicRuleStatistics>
)

@JvmInline
value class BasicSplitStatisticsJavaSerializerWrapper(
    private val toSerialize: SingleBasicRuleStatistics
): java.io.Serializable {
    override fun toString(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(toSerialize)
    }
}
