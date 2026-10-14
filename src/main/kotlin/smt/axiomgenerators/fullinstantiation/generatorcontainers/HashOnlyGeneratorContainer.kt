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

package smt.axiomgenerators.fullinstantiation.generatorcontainers

import smt.axiomgenerators.BvOverflowChecksViaDefineFuns
import smt.axiomgenerators.fullinstantiation.BVMathAxiomGenerator
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGenerator
import smt.newufliatranslator.AxiomGenerator
import smt.newufliatranslator.AxiomGeneratorContainer
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import verifier.CreateAxiomGeneratorContainer

/**
 * Should be suitable e.g. for AUFBV logic.
 */
class HashOnlyGeneratorContainer private constructor(
    lExprFact: LExpressionFactory,
    generators: List<AxiomGenerator>,
) : AxiomGeneratorContainer(lExprFact, generators) {

    companion object {
        fun create(
            lxf: LExpressionFactory,
            targetTheory: SmtTheory,
            overflowChecks: CreateAxiomGeneratorContainer.Config.BvOverflowChecks,
        ): AxiomGeneratorContainer {
            val hashGenerator = StorageHashAxiomGenerator(lxf, targetTheory = targetTheory)

            val bvOverflowChecksViaDefineFuns: BvOverflowChecksViaDefineFuns? =
                when (overflowChecks) {
                    CreateAxiomGeneratorContainer.Config.BvOverflowChecks.NewSmtLib -> null
                    CreateAxiomGeneratorContainer.Config.BvOverflowChecks.ViaDefineFun ->
                        BvOverflowChecksViaDefineFuns(lxf)
                    CreateAxiomGeneratorContainer.Config.BvOverflowChecks.DontCare -> null
                }

            return HashOnlyGeneratorContainer(
                lxf,
                listOfNotNull(
                    hashGenerator,
                    bvOverflowChecksViaDefineFuns,
                    BVMathAxiomGenerator(lxf)
                ),
            )

        }
    }
}
