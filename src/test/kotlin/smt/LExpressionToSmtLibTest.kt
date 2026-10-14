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

package smt

import analysis.TACTypeChecker
import config.Config
import config.ConfigScope
import evm.EVM_MOD_GROUP256
import kotlinx.coroutines.runBlocking
import loaders.WithTACSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import smt.solverscript.LExpressionFactory
import smtlibutils.cmdprocessor.*
import smtlibutils.data.Logic
import smtlibutils.data.SatResult
import smtlibutils.data.SmtScript
import solver.SolverConfig
import solver.SolverInfo
import statistics.data.TACProgramMetadata
import utils.*
import vc.gen.LeinoWP
import verifier.CreateAxiomGeneratorContainer
import verifier.LExpToSmtSetupInfo
import verifier.RuleAndSplitIdentifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class LExpressionToSmtLibTest : WithTACSource {

    private fun mockAxiomGeneratorConfig(): CreateAxiomGeneratorContainer.Config =
        CreateAxiomGeneratorContainer.Config(
            listOf(),
            CreateAxiomGeneratorContainer.Config.BvOverflowChecks.ViaDefineFun
        )


    private fun mockLExpToSmtScene(
        lxf: LExpressionFactory,
        lExpToSmtSetupInfo: LExpToSmtSetupInfo,
        inputFileName: String,
    ): LExpressionToSmtLibScene {
        unused(inputFileName)
        return LExpressionToSmtLibScene(
            lxf,
            lExpToSmtSetupInfo.createAxiomGeneratorContainer.create(
                lxf,
                lExpToSmtSetupInfo.targetTheory,
            ),
            lExpToSmtSetupInfo.targetTheory,
            lExpToSmtSetupInfo.createExpNormalizer(lxf),
        )
    }

    private suspend fun getChecker(
        query: SmtFormulaCheckerQuery,
        timeout: Duration,
        logic: Logic,
        script: SmtScript,
        dumpFile: String?
    ): (suspend () -> SmtFormulaChecker)? {
        val solverConfig: SolverConfig = SolverInfo.getDefaultConfigs(
            timeout, null, false,
            logic.toSolverConfigLogicFeatures(),
            SolverConfig.z3.values.toList()
        ).first()
        return SimpleFormulaChecker.singleCheckerSpawnerFromSolverInfoOrNull(
            SolverInstanceInfo.fromSolverConfig(
                solverConfig,
                CmdProcessor.Options.fromQuery(query, incremental = false),
            ),
            script,
            dumpFile
        )
    }

    @Test
    fun arrayGenVsGhostMaps01() {
        listOf(true, false).forEach { useArrayTheory ->
            val res = checkTacProg("smt/arraygen-vs-ghostmaps01.tac", useArrayTheory, false)
            assertTrue(res.satResult == SatResult.UNSAT)
        }
    }

    @Test
    fun arrayGenVsGhostMaps02() {
        listOf(true, false).forEach { useArrayTheory ->
            val res = checkTacProg("smt/arraygen-vs-ghostmaps02.tac", useArrayTheory, false)
            assertTrue(res.satResult == SatResult.UNSAT)
        }
    }


    /**
     * `-smt_preciseBytemaps` models a bytemap store by shifting the stored value, which needs the value's bit
     * width. A store signature only requires the value to be an integer, so this checks that a `Tag.Int` value
     * read back at a symbolic offset still produces a query.
     */
    @Test
    fun preciseByteMapsIntValuedStore() {
        ConfigScope(Config.PreciseByteMaps, true).use {
            val res = checkTacProg("smt/precise-bytemap-int-store.tac", useArrayTheory = false, useQuantifiers = false)
            assertTrue(res.satResult == SatResult.SAT)
        }
    }

    private fun checkTacProg(
        fileName: String,
        useArrayTheory: Boolean,
        useQuantifiers: Boolean
    ): SmtFormulaCheckerResult {
        val tacProgram =
            TACTypeChecker.annotateExpressions(loadTACProgramResource(fileName))
                .resultOrNull() ?: error("failed to type check program on disk")
        val lxf = LExpressionFactory()
        val vc = LeinoWP(
            tacProgram,
            lxf,
            TACProgramMetadata(RuleAndSplitIdentifier.dummyRoot("testProg")),
        ).generateVCs()
        val smtQuery = LExpressionToSmtLib(
            fileName,
            mockLExpToSmtScene(
                lxf,
                LExpToSmtSetupInfo.UFLIAorAUFLIA(
                    useArrayTheory,
                    useQuantifiers,
                    mockAxiomGeneratorConfig()
                ),
                fileName
            ),
            vc
        ).output()
        val checker = runBlocking {
            getChecker(
                smtQuery,
                10.seconds,
                Logic.fromString("QF_UFLIA"),
                SmtScript(),
                null
            ) ?: error("failed to spawn solver")
        }
        return runBlocking { checker().check(query = smtQuery) }
    }


}
