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

package smt.axiomgenerators.fullinstantiation

import kotlinx.coroutines.runBlocking
import smt.GenerateEnv
import smt.LExpressionToSmtLib
import smt.axiomgenerators.AxiomContainer
import smt.axiomgenerators.DefType
import smt.axiomgenerators.fullinstantiation.expnormalizer.ExpNormalizer
import smt.axiomgenerators.fullinstantiation.expnormalizer.ExpNormalizerIA
import smt.newufliatranslator.AxiomGenerator
import smt.newufliatranslator.AxiomGeneratorContainer
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.SmtTheoryFeature
import smt.solverscript.functionsymbols.ConstantSymbol
import smt.solverscript.functionsymbols.UninterpretedFunctionSymbol
import smt.solverscript.functionsymbols.subs
import smtlibutils.cmdprocessor.*
import smtlibutils.data.Cmd
import smtlibutils.data.SmtScript
import tac.Tag
import vc.data.LExpression
import vc.data.ToSmtLibData
import vc.data.TraverseLExpression
import verifier.CreateAxiomGeneratorContainer
import verifier.LExpToSmtSetupInfo
import kotlin.time.Duration.Companion.seconds

object MockLExpToSmtLib {

    data class TestSetup(
        val targetTheory: SmtTheory,
        val makeAxiomGenerators: (LExpressionFactory) -> List<AxiomGenerator>,
        val makeExpNormalizer: (LExpressionFactory) -> ExpNormalizer,
    ) {
        companion object {
            fun makeNiaSetup(
                makeAxiomGenerators: (LExpressionFactory) -> List<AxiomGenerator>,
            ): TestSetup = TestSetup(
                niaTargetTheory,
                makeAxiomGenerators,
                niaMakeExpNormalizer,
            )
        }
    }

    val niaTargetTheory = SmtTheory.fromString("QF_UFNIA")
    private val niaMakeExpNormalizer =
        { lxf: LExpressionFactory -> ExpNormalizerIA(lxf, niaTargetTheory, { false }) }

    private val dumpFile: String? = null // "out.smt2", for easy debugging -- give a file name here to dump the generated smt there

    internal fun setupAndCheck(
        testSetup: TestSetup,
        formula: (LExpressionFactory) -> List<LExpression>
    ): SmtFormulaCheckerResult {
        val lxf = LExpressionFactory()
        return addAxiomsAndCheck(
            formula(lxf),
            lxf,
            setupLExpToSmtSetupInfo(
                testSetup.targetTheory,
                testSetup.makeAxiomGenerators,
                testSetup.makeExpNormalizer,
            )
        )
    }

    private fun addAxiomsAndCheck(
        formula: List<LExpression>,
        lxf: LExpressionFactory,
        lExpToSmtSetupInfo: LExpToSmtSetupInfo,
    ): SmtFormulaCheckerResult {
        var targetTheory = lExpToSmtSetupInfo.targetTheory
        val axiomGeneratorContainer =
            lExpToSmtSetupInfo.createAxiomGeneratorContainer.create(lxf, targetTheory)
        val axiomGenerators = axiomGeneratorContainer.allGenerators
        val storageHashAxiomGenerator =
            axiomGenerators.filterIsInstance<StorageHashAxiomGenerator>().firstOrNull()
        val expNormalizer = lExpToSmtSetupInfo.createExpNormalizer(lxf)

        storageHashAxiomGenerator?.emptyNotLargeConstantsInCode()

        val emptyGenerateEnv = GenerateEnv()
        val visitor = object : TraverseLExpression() {
            override fun expr(exp: LExpression) {
                axiomGenerators.forEach { it.visit(exp, emptyGenerateEnv) }
                super.expr(exp)
            }
        }
        formula.forEach { visitor.expr(it) }

        val axiomContainer = AxiomContainer(lxf)
        axiomGenerators.forEach { it.yield(axiomContainer) }

        axiomGenerators.forEach { it.beforeScriptFreeze() }

        val defineFuns: List<DefType> = axiomGenerators.flatMap { it.yieldDefineFuns() }

        val toSmtLibData = object : ToSmtLibData {
            override val lExprFact: LExpressionFactory
                get() = lxf
            override val targetTheory: SmtTheory
                get() = targetTheory
        }

        val lexpAssertions = formula
            .map { expNormalizer.normalizeRec(it) } +
            axiomContainer.getAxiomSequence()
                .map { lexpWc -> expNormalizer.normalizeRec(lexpWc.lExpression) }

        lxf.freezeArrays()
        lxf.freezeConstants()
        lxf.freezeAxiomatized()
        lxf.freezeUninterpretedSorts()
        lxf.freezeUserDefinedFunctions()

        val script = SmtScript()

        val smtDeclareDatatypes: List<Cmd.DeclareDatatypes> =
            lxf.getDatatypeDeclarations().map { it.toSmtLib(toSmtLibData, script) }
        if (smtDeclareDatatypes.isNotEmpty()) {
            targetTheory += SmtTheoryFeature.DataTypes
        }

        lexpAssertions.asSequence()
            .flatMap { it.subs }
            .plus(defineFuns.flatMap { f -> f.def.subs.filter { it !in f.args } })
            .mapNotNull {
                when (it) {
                    is LExpression.ApplyExpr -> it.f as? UninterpretedFunctionSymbol
                    is LExpression.Identifier -> ConstantSymbol(it.id, it.tag)
                    else -> null
                }
            }
            .filterNot { fs -> fs.signature.resultSort is Tag.UserDefined.Struct }
            .distinct()
            .sortedBy { it.name }
            .forEach { LExpressionToSmtLib.declareInScript(it.name, it.signature, toSmtLibData, script) }

        val assertions = lexpAssertions
            .map { it.toSMTLib(toSmtLibData, script) }

        val sfc = runBlocking { SimpleFormulaChecker.plainZ3Instance(5.seconds, script, dumpFile = dumpFile) }
        return runBlocking {
            sfc.check(
                SmtFormulaCheckerQuery(
                    SmtFormulaCheckerQueryInfo(name = "StorageHashAxiomGeneratorTest"),
                    SmtFormula(
                        targetTheory.toLogic(),
                        script.symbolTable,
                        defineFuns.map { it.toSmtLib(toSmtLibData, script) },
                        smtDeclareDatatypes,
                        assertions
                    )
                )
            )
        }
    }

    private fun setupLExpToSmtSetupInfo(
        theory: SmtTheory,
        makeAxiomGenerators: (LExpressionFactory) -> List<AxiomGenerator>,
        makeExpNormalizer: (LExpressionFactory) -> ExpNormalizer
    ) = LExpToSmtSetupInfo(
        theory,
        createAxiomGeneratorContainer(makeAxiomGenerators),
        makeExpNormalizer,
    )

    private fun createAxiomGeneratorContainer(createAxiomGenerators: (LExpressionFactory) -> List<AxiomGenerator>) =
        object : CreateAxiomGeneratorContainer() {
            override fun create(
                lxf: LExpressionFactory,
                targetTheory: SmtTheory
            ): AxiomGeneratorContainer {
                return object : AxiomGeneratorContainer(
                    lxf,
                    createAxiomGenerators(lxf)
                ) {}
            }
        }
}
