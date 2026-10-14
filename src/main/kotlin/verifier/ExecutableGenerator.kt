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

package verifier

import config.Config
import parallel.ParallelPool
import parallel.coroutines.lazy
import smt.solverscript.VcFeature
import smtlibutils.data.SatResult
import solver.SolverConfig

/**
 * Allows to generate executables for the race.
 * The object correctly handles win-race conditions, interpretation (normal vs. overaaproximation)
 */
abstract class ExecutableGenerator(val checker: LExpVcChecker, val raceDescription: String) {

    /**
     * Generates the executables using the given [solvers]. [presentTypes] are used to correctly determine the
     * race-winning conditions and interpretations.
     */
    abstract suspend fun generateExecutables(
        presentTypes: Set<SetupInfoType>,
        solvers: List<SolverConfig>,
        cexVerifier: (suspend () -> CEXVerifier)? = null,
    ): List<Executable>

    /**
     * Creates the axiom generator config for the race.
     */
    protected abstract fun generateAGC(): CreateAxiomGeneratorContainer.Config

    protected abstract val isPrecise: Boolean

    /**
     * Interpretation of results for the generated executables.
     */
    protected open fun defaultInterpretation(): LExpVcChecker.Interpretation {
        return if (isPrecise) {
            LExpVcChecker.Interpretation.Standard
        } else {
            LExpVcChecker.Interpretation.OverApproximation
        }
    }

    /**
     * Tells which output of the solver will end the race.
     */
    protected abstract fun defaultRaceWinningCondition(presentTypes: Set<SetupInfoType>): (SatResult) -> Boolean
    protected abstract val setupInfoType: SetupInfoType
    protected abstract fun generateSetupInfo(): LExpToSmtSetupInfo

    public val generatedQuery = ParallelPool.lazy { checker.generateQuery(generateSetupInfo()) }
}

/**
 * Base class for [ExecutableGenerator]s for integer logic. (LIA and NIA)
 */
abstract class IntegerExecutableGenerator(checker: LExpVcChecker, raceDescription: String)
    : ExecutableGenerator(checker, raceDescription) {

    override fun generateAGC() = CreateAxiomGeneratorContainer.Config(
        checker.extraAxioms,
        CreateAxiomGeneratorContainer.Config.BvOverflowChecks.DontCare
    )

    override suspend fun generateExecutables(
        presentTypes: Set<SetupInfoType>,
        solvers: List<SolverConfig>,
        cexVerifier: (suspend () -> CEXVerifier)?,
    ): List<Executable> {
        val executables = solvers.map {
            Executable(
                raceDescription,
                generatedQuery.await(),
                LExpVcChecker.script,
                it,
                defaultRaceWinningCondition(presentTypes),
                defaultInterpretation(),
                cexVerifier = cexVerifier?.invoke()
            )
        }
        return executables
    }
}

class NIAExecutableGenerator(checker: LExpVcChecker, raceDescription: String)
    : IntegerExecutableGenerator(checker, raceDescription) {

    override val setupInfoType: SetupInfoType = SetupInfoType.UFNIAorAUFNIA
    override val isPrecise = checker.niaIsPrecise()
    override fun generateSetupInfo() = LExpToSmtSetupInfo.UFNIAorAUFNIA(
        useArrayTheory = false,
        VcFeature.Quantification in checker.lxf.getUsedFeatures(),
        generateAGC()
    )
    override fun defaultRaceWinningCondition(presentTypes: Set<SetupInfoType>): (SatResult) -> Boolean {
        // if NIA is precise, it wins the race with SAT or UNSAT
        if (isPrecise) return { result -> result !is SatResult.UNKNOWN }

        // NIA is an over-approximation and a precise solver is present, hence only UNSAT wins the race
        if (presentTypes.contains(SetupInfoType.UFBVorAUFBV)) return { result -> result is SatResult.UNSAT }

        // NIA is an over-approximation and no precise solver is present, hence both SAT and UNSAT ends the race
        return { result -> result !is SatResult.UNKNOWN }

        //the above code may be simplified but seems to be more readable this way
    }
}


class PartialNIAExecutableGenerator(checker: LExpVcChecker, raceDescription: String) :
    IntegerExecutableGenerator(checker, raceDescription) {

    override val setupInfoType: SetupInfoType = SetupInfoType.UFLIAorAUFLIAPartial
    override val isPrecise = false
    val closeToAssert = CloseToAssertSelector(checker.vc, checker.config.vcName, Config.PartialNIASelectorDepth.get())
    override fun generateSetupInfo() = LExpToSmtSetupInfo.UFLIAorAUFLIAPartial(
        useArrayTheory = false,
        VcFeature.Quantification in checker.lxf.getUsedFeatures(),
        generateAGC(),
        closeToAssert::selectLIA
    )

    override fun defaultRaceWinningCondition(presentTypes: Set<SetupInfoType>): (SatResult) -> Boolean {
        // LIA is precise hence wins the race if SAT or UNSAT
        if (isPrecise) {
            return { result -> result !is SatResult.UNKNOWN }
        }

        // LIA is over-approximation and a precise solver is present, hence only UNSAT wins the race
        if ((presentTypes.contains(SetupInfoType.UFNIAorAUFNIA) && checker.niaIsPrecise()) ||
            presentTypes.contains(SetupInfoType.UFBVorAUFBV)
        ) {
            return { result -> result is SatResult.UNSAT }
        }

        // LIA is over-approximation and no precise solver is present, hence both SAT and UNSAT ends the race
        return { result -> result !is SatResult.UNKNOWN }
    }
}


/**
 * Generator for LIA executables. With [isPrecise], we can manually override the result interpretation. If forced to
 * true, we can avoid marking violations as "underapproximation", e.g. for `-useLIA without-verifier`.
 */
class LIAExecutableGenerator(
    checker: LExpVcChecker,
    raceDescription: String,
    override val isPrecise: Boolean = false
) : IntegerExecutableGenerator(checker, raceDescription) {

    override val setupInfoType: SetupInfoType = SetupInfoType.UFLIAorAUFLIA
    override fun generateSetupInfo(): LExpToSmtSetupInfo {
        return LExpToSmtSetupInfo.UFLIAorAUFLIA(
            useArrayTheory = false,
            VcFeature.Quantification in checker.lxf.getUsedFeatures(),
            generateAGC()
        )
    }

    override fun defaultRaceWinningCondition(presentTypes: Set<SetupInfoType>): (SatResult) -> Boolean {
        // LIA is precise hence wins the race if SAT or UNSAT
        if (isPrecise) return { result -> result !is SatResult.UNKNOWN }

        // LIA is over-approximation and a precise solver is present, hence only UNSAT wins the race
        if ((presentTypes.contains(SetupInfoType.UFNIAorAUFNIA) && checker.niaIsPrecise()) ||
            presentTypes.contains(SetupInfoType.UFBVorAUFBV)) {
            return { result -> result is SatResult.UNSAT }
        }

        // LIA is over-approximation and no precise solver is present, hence both SAT and UNSAT ends the race
        return { result -> result !is SatResult.UNKNOWN }
    }
}

class BVExecutableGenerator(
    checker: LExpVcChecker,
    raceDescription: String,
    private val overflowChecks: CreateAxiomGeneratorContainer.Config.BvOverflowChecks
) : ExecutableGenerator(checker, raceDescription) {

    override val setupInfoType: SetupInfoType = SetupInfoType.UFBVorAUFBV
    override val isPrecise = true

    override fun generateAGC(): CreateAxiomGeneratorContainer.Config {
        return CreateAxiomGeneratorContainer.Config(
            extraAxioms = checker.extraAxioms,
            overflowChecks = overflowChecks
        )
    }

    override fun generateSetupInfo(): LExpToSmtSetupInfo = LExpToSmtSetupInfo.UFBVorAUFBV(
        useArrayTheory = false,
        VcFeature.Quantification in checker.lxf.getUsedFeatures(),
        generateAGC()
    )

    override fun defaultRaceWinningCondition(presentTypes: Set<SetupInfoType>): (SatResult) -> Boolean {
        // BV is precise hence wins the race if SAT or UNSAT
        if (isPrecise) return { result -> result !is SatResult.UNKNOWN }

        // BV is over-approximation and a precise solver is present, hence only UNSAT wins the race
        if (presentTypes.contains(SetupInfoType.UFNIAorAUFNIA) && checker.niaIsPrecise()) {
            return { result -> result is SatResult.UNSAT }
        }

        // BV is over-approximation and no precise solver is present, hence both SAT and UNSAT ends the race
        return { result -> result !is SatResult.UNKNOWN }
    }

    private fun checkSolversCompatibility(solvers: List<SolverConfig>) {
        require(
            !(
                VcFeature.MulOverflowChecks in checker.usedFeatures
                    && solvers.any { it.solverInfo.supportsNewSmtLibBvOverflowSymbols }
                    && overflowChecks != CreateAxiomGeneratorContainer.Config.BvOverflowChecks.NewSmtLib)
        )
        { "BV race with overflow checks using a compatible solver " +
            "requires BvOverflowChecks to be set to \"NewSmtLib\"" +
            "\"(solvers in this list that are compatible: ${solvers.filter { it.solverInfo.supportsNewSmtLibBvOverflowSymbols }}) \"" }

        require(
            !(
                VcFeature.MulOverflowChecks in checker.usedFeatures
                    && solvers.any { !it.solverInfo.supportsNewSmtLibBvOverflowSymbols }
                    && overflowChecks == CreateAxiomGeneratorContainer.Config.BvOverflowChecks.NewSmtLib)
        )
        { "BV race with overflow checks using a solver that does not support bv overflow functions requires " +
            "BvOverflowChecks to be set to \"ViaDefineFun\"" }
    }

    override suspend fun generateExecutables(
        presentTypes: Set<SetupInfoType>,
        solvers: List<SolverConfig>,
        cexVerifier: (suspend () -> CEXVerifier)?,
    ): List<Executable> {
        // just a sanity check. will be removed once we're sure that the overflows are handled correctly
        checkSolversCompatibility(solvers)

        val executables = solvers.map {
            Executable(
                raceDescription,
                generatedQuery.await(),
                LExpVcChecker.script,
                it,
                defaultRaceWinningCondition(presentTypes),
                defaultInterpretation(),
                cexVerifier = cexVerifier?.invoke(),
            )
        }
        return executables
    }
}


