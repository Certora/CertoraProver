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

import datastructures.stdcollections.*
import ksp.dynamicconversion.AddDynamicConversion
import ksp.dynamicconversion.ConvertibleWith
import ksp.dynamicconversion.DynamicConverter
import ksp.dynamicconversion.ExcludeFromDynamicConversion
import log.*
import utils.RuntimeEnvInfo
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ConfigStatistics(
    val solverConfigurationName: String = "unknown",
    val solverName: String = "unknown",
    val arguments: String = "",
)

/** mirrors Logic.ArithmeticOperations in SMTLibUtils package, this exists for visibility reasons,
 * TODO: may want to refactor some time; (CERT-8094)*/
enum class ArithmeticOperations {
    LinearOnly,
    NonLinear,
    BitVector,
    Any,
    None
}

/**
 * Represents a single configuration of a particular solver, consisting of a [SolverInfo] (that represents
 * the solver's executable), a name to identify this configuration, and a variety of configuration options.
 * The idea is that every solver comes with a set of predefined configurations (accessible via
 * [SolverConfig.cvc5], [SolverConfig.z3], etc.) that can be modified via [SolverConfig.copy].
 * The configuration is identified via its name via [fullName], which usually consists of the solver name
 * [SolverInfo.name] and the [variantName]. It can be overridden using [name].
 * The options are given as raw command line options ([clOptions]) and special variables [incremental],
 * [timelimit], [memlimitBytes], and [randomSeed]. Furthermore, [qualifiesFor] can be used to disable the
 * configuration based on the problem's logic and [preprocessorConfig] is used for preprocessing in certain
 * situations.
 */
@AddDynamicConversion
data class SolverConfig(
    @ConvertibleWith(SolverInfo.Converter::class)
    val solverInfo: SolverInfo,
    val variantName: String,
    val name: String? = null,
    val clOptions: List<String> = listOf(),
    val incremental: Boolean = false,
    val timelimit: Duration? = null,
    val memlimitBytes: Long? = null,
    val qualifiesFor: (LogicFeatures) -> Boolean = { true },
    @ConvertibleWith(Converter::class)
    val preprocessorConfig: SolverConfig? = null,
    val randomSeed: Int? = null,
    /** Determines whether the SolverConfig can be skipped in a race if we do not have enough available cores. **/
    val canBeSkipped: (LogicFeatures, Duration) -> Boolean = { _, _ -> false },
    override val configList: List<Configurable>? = null,
    // Only settable from trusted code via the regular named-argument copy(...); excluded from the
    // dynamic copy(Map) path so it cannot be set through the -solver / CLI `name{...}` syntax.
    @ExcludeFromDynamicConversion
    val customBinary: String? = null
) : Configurable, Serializable {

    /** Name that should be used to identify this configuration, e.g., for printing */
    val fullName: String = name ?: "${solverInfo.name.lowercase()}:${variantName}"
    override fun toString(): String = fullName

    /** Configuration name used for the [Configurable] base class */
    override val configName: String = variantName

    override fun asConfigList(expands: List<Configurable>) = this.copy(variantName = "all", configList = expands)

    data class LogicFeatures(
        val arithmeticOperations: ArithmeticOperations,
        val usesDatatypes: Boolean,
        val usesQuantifiers: Boolean
    ) : Serializable

    /** Get the command line to run the solver executable */
    fun getCommandline(): List<String> {
        val options = clOptions +
                (if (incremental) solverInfo.getOptionForIncremental() else listOf()) +
                (if (timelimit != null) solverInfo.getOptionForTimelimit(timelimit) else listOf()) +
                (if (memlimitBytes != null) solverInfo.getOptionForMemlimit(memlimitBytes) else listOf()) +
                (if (randomSeed != null) solverInfo.getOptionForRandomSeed(randomSeed) else listOf())
        val baseCL = solverInfo.commandForStdInMode(options, customBinary)
        return RuntimeEnvInfo.getPrlimitCommandIfAvailable(memlimitBytes) + baseCL
    }

    fun getConfigStats(): ConfigStatistics {
        val getSolverNameAndArgs: (List<String>) -> Pair<String?, String> = { command ->
            command.getOrNull(0) to command.drop(1).joinToString(" ")
        }
        val (name, args) = getSolverNameAndArgs(getCommandline())
        return ConfigStatistics(fullName, name.toString(), args)
    }

    class AltErgoRegistry: Configuration.Registry<SolverConfig>("altergo", "def") {
        val def = register(SolverConfig(AltErgoSolverInfo, "def"))
    }
    class BitwuzlaRegistry: Configuration.Registry<SolverConfig>("bitwuzla", "def") {
        val def = register(SolverConfig(BitwuzlaSolverInfo, "def",
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.BitVector },
        ))
    }
    class CVC5Registry: Configuration.Registry<SolverConfig>("cvc5", "def") {
        val def = register(SolverConfig(CVC5SolverInfo, "def",
            clOptions = listOf("--decision=internal"),
            canBeSkipped = { _, _ -> true },
        ))
        val nonlin = register(SolverConfig(
            CVC5SolverInfo, "nonlin",
            clOptions = listOf("--nl-ext-tplanes", "--decision=justification"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.NonLinear },
            canBeSkipped = { _, timelimit -> timelimit <= highTimeoutBound},
        ))
        val lin = register(SolverConfig(CVC5SolverInfo, "lin",
            clOptions = listOf("--decision=justification"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.LinearOnly },
            canBeSkipped = { _, timelimit -> timelimit <= highTimeoutBound },
        ))
        val linNoDio = register(SolverConfig(CVC5SolverInfo, "linNoDio",
            clOptions = listOf("--decision=justification", "--no-dio-solver"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.LinearOnly },
            canBeSkipped = { _, timelimit -> timelimit <= highTimeoutBound },
        ))
        val nonlinNoDio = register(SolverConfig(CVC5SolverInfo, "nonlinNoDio",
            clOptions = listOf("--nl-ext-tplanes", "--decision=justification", "--no-dio-solver"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.NonLinear },
            canBeSkipped = { _, timelimit -> timelimit <= highTimeoutBound},
        ))
        val q = register(SolverConfig(
            CVC5SolverInfo, "q",
            clOptions = listOf("--full-saturate-quant"),
            canBeSkipped = { _, _ -> true },
            qualifiesFor = { it.usesQuantifiers },
        ))
        val bv = register(SolverConfig(CVC5SolverInfo, "bv",
            clOptions = listOf("--solve-bv-as-int=iand"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.BitVector && !it.usesQuantifiers },
        ))
    }
    class CVC4Registry: Configuration.Registry<SolverConfig>("cvc4", "def") {
        val def = register(SolverConfig(CVC4SolverInfo, "def",
            canBeSkipped = { lf, _ -> lf.arithmeticOperations == ArithmeticOperations.LinearOnly },
        ))
        val nonlin = register(SolverConfig(CVC4SolverInfo, "nonlin",
            clOptions = listOf("--nl-ext-tplanes", "--decision=justification"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.NonLinear },
            canBeSkipped = { _, timelimit -> timelimit > highTimeoutBound },
        ))
        val lin = register(SolverConfig(CVC4SolverInfo, "lin",
            clOptions = listOf("--decision=justification"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.LinearOnly },
            canBeSkipped = { _, timelimit -> timelimit <= highTimeoutBound },
        ))
        val q = register(SolverConfig(CVC4SolverInfo, "q",
            clOptions = listOf("--full-saturate-quant"),
            qualifiesFor = { it.usesQuantifiers },
        ))
    }
    class SmtInterpolRegistry: Configuration.Registry<SolverConfig>("smtinterpol", "def") {
        val def = register(SolverConfig(
            SmtInterpolSolverInfo,
            variantName = "def",
            // can do BV, but bitblasts -- so not picking it for that for now, similarly, can do NIA, but only if it's
            //  practically LIA -- not picking that either
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.LinearOnly },
        ))
    }
    class VampireRegistry: Configuration.Registry<SolverConfig>("vampire", "def") {
        val def = register(SolverConfig(VampireSolverInfo, "def"))
    }
    class YicesRegistry: Configuration.Registry<SolverConfig>("yices", "def") {
        val def = register(SolverConfig(YicesSolverInfo, "def",
            qualifiesFor = { !it.usesDatatypes },
        ))
    }
    class Z3Registry: Configuration.Registry<SolverConfig>("z3", "def") {
        val def = register(SolverConfig(Z3SolverInfo, "def",
            canBeSkipped = { logic, timelimit -> logic.arithmeticOperations == ArithmeticOperations.LinearOnly ||
                           timelimit <= highTimeoutBound },
        ))
        val lia1 = register(SolverConfig(Z3SolverInfo, "lia1",
            clOptions = listOf("smt.arith.solver=2", "smt.auto_config=false"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.LinearOnly },
        ))
        val lia2 = register(SolverConfig(Z3SolverInfo, "lia2",
            clOptions = listOf("tactic.solve_eqs.max_occs=4", "smt.arith.solver=2", "smt.auto_config=false"),
            qualifiesFor = { it.arithmeticOperations == ArithmeticOperations.LinearOnly },
        ))
        val eq1 = register(SolverConfig(
            Z3SolverInfo, "eq1",
            clOptions = listOf("tactic.solve_eqs.max_occs=4"),
            canBeSkipped = { logic, _ -> (logic.arithmeticOperations == ArithmeticOperations.NonLinear) ||
                (logic.arithmeticOperations == ArithmeticOperations.LinearOnly)},

        ))
        val eq2 = register(SolverConfig(
            Z3SolverInfo, "eq2",
            clOptions = listOf("tactic.solve_eqs.max_occs=4", "tactic.solve_eqs.context_solve=true"),
            canBeSkipped = { logic, _ -> logic.arithmeticOperations == ArithmeticOperations.NonLinear ||
                (logic.arithmeticOperations == ArithmeticOperations.LinearOnly)},
        ))
        val arith1 = register(SolverConfig(Z3SolverInfo, "arith1",
            clOptions = listOf("smt.arith.solver=6", "smt.auto_config=false"),
            canBeSkipped = {logic, timelimit -> (logic.arithmeticOperations == ArithmeticOperations.NonLinear &&
                timelimit <= highTimeoutBound) || logic.arithmeticOperations == ArithmeticOperations.LinearOnly},
        ))
        val arith2 = register(SolverConfig(Z3SolverInfo, "arith2",
            clOptions = listOf("smt.arith.solver=6", "smt.auto_config=false", "tactic.solve_eqs.max_occs=4"),
            canBeSkipped = {_, _ -> true },
        ))
    }

    class Converter: DynamicConverter<SolverConfig> {
        override fun invoke(v: Any) = SolverConfig.construct(v, SolverConfig)
    }

    /**
     * Holds the configurations for all solvers. Note that no configurations are stored here, but
     * the individual solvers register as sub-registries.
     */
    companion object : Configuration.Registry<SolverConfig>("topLevel"), Configurable.ConversionHelper<SolverConfig> {
        val lowTimeoutBound: Duration = 5.seconds
        val highTimeoutBound: Duration = 10.seconds

        val altergo = register(AltErgoRegistry())
        val bitwuzla = register(BitwuzlaRegistry())
        val cvc4 = register(CVC4Registry())
        val cvc5 = register(CVC5Registry())
        val smtinterpol = register(SmtInterpolRegistry())
        val vampire = register(VampireRegistry())
        val yices = register(YicesRegistry())
        val z3 = register(Z3Registry())

        override fun doCopy(t: SolverConfig, override: Map<String, Any>?) = t.copy(override)
        override fun doConstruct(params: Map<String, Any>) = constructFrom(params)

        /**
         * Choose fitting solver configurations based on [solverChoice] and [ArithmeticOperations] (i.e. LIA, NIA, BV).
         */
        fun getDefaultSolverConfigs(
            solverChoice: SolverChoice,
            logicFeatures: LogicFeatures,
            timelimit: Duration,
            memlimitBytes: Long?,
            incrementalMode: Boolean,
        ): List<SolverConfig> =
            solverChoice.filter {
                it.qualifiesFor(logicFeatures)
            }.map {
                it.copy(timelimit = timelimit, memlimitBytes = memlimitBytes, incremental = incrementalMode)
            }
    }
}
