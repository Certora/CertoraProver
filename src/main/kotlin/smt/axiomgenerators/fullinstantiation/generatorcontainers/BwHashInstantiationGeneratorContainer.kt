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

import smt.HashingScheme
import smt.axiomgenerators.TypeBoundsGenerator
import smt.axiomgenerators.fullinstantiation.ArrayGenerator
import smt.axiomgenerators.fullinstantiation.BitwiseAxiomGenerator
import smt.axiomgenerators.fullinstantiation.IntMathAxiomGenerator
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGeneratorPlainInjectivity
import smt.newufliatranslator.AxiomGenerator
import smt.newufliatranslator.HashAxiomGeneratorContainer
import smt.newufliatranslator.StorageHashAxiomGenerator
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import vc.data.lexpression.ChainedLExpressionTransformer

/**
 * for translating to UFNIA and AUFNIA
 */
class BwHashInstantiationGeneratorContainer private constructor(
    lxf: LExpressionFactory,
    private val arrayGenerator: ArrayGenerator,
    hashGenerator: StorageHashAxiomGenerator,
    noLargeGapsHashGenerator: StorageHashAxiomGeneratorPlainInjectivity?,
    generators: List<AxiomGenerator>,
) : HashAxiomGeneratorContainer(
    lxf,
    generators,
    hashGenerator,
    noLargeGapsHashGenerator,
) {
    init {
        check(arrayGenerator in generators)
        check(hashGenerator in generators)
    }

    companion object {
        fun create(
            lxf: LExpressionFactory,
            targetTheory: SmtTheory,
            hashingScheme: HashingScheme,
        ): BwHashInstantiationGeneratorContainer {
            val bitwiseGenerator = BitwiseAxiomGenerator(lxf, targetTheory, linearMathAxiomGenerator = null)
            val intMathAxiomGenerator = IntMathAxiomGenerator(lxf)
            val (hashGenerator, noLargeGapsHashGenerator) =
                StorageHashAxiomGenerator.fromHashingScheme(lxf, hashingScheme, targetTheory)
            val arrayGenerator = ArrayGenerator(lxf, targetTheory, hashingScheme) {
                hashGenerator.postProcessSkeyTransformer?.invoke(it) ?: it
            }
            val typeBasedAxiomsForConstantsGenerator = TypeBoundsGenerator(lxf, targetTheory)

            return BwHashInstantiationGeneratorContainer(
                lxf,
                arrayGenerator,
                hashGenerator,
                noLargeGapsHashGenerator,
                listOfNotNull(
                    noLargeGapsHashGenerator,
                    hashGenerator,
                    arrayGenerator,
                    bitwiseGenerator,
                    intMathAxiomGenerator,
                    typeBasedAxiomsForConstantsGenerator,
                ),
            )
        }
    }

    override val postProcessTransformer = ChainedLExpressionTransformer(
        super.postProcessTransformer,
        arrayGenerator.postProcessArrayTransformer,
    )

}
