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

import smt.axiomgenerators.fullinstantiation.generatorcontainers.*
import smt.newufliatranslator.AxiomGeneratorContainer
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smtlibutils.data.HasSmtExp
import vc.data.LExpression
import datastructures.stdcollections.*


abstract class CreateAxiomGeneratorContainer {
    abstract fun create(lxf: LExpressionFactory, targetTheory: SmtTheory): AxiomGeneratorContainer

    data class Config(
        val extraAxioms: List<HasSmtExp>,
        val overflowChecks: BvOverflowChecks
    ) {
        enum class BvOverflowChecks {
            /*
             * overflow symbols that were added to solvers relatively recently as a common standard, see
             * https://smt-lib.org/theories-FixedSizeBitVectors.shtml
             * (as of Oct 2024, z3, bitwuzla, cvc5 support them)
             * Actually, more are supported; for a list of CVC5-supported ones, see e.g.
             * https://github.com/cvc5/cvc5/blob/79b550780506cabd4808e666b3a1adc3e9ca5e82/src/api/cpp/cvc5.cpp#L221 (and following lines)
             * (the other solver seem to implement them as well, so far),
             */
            NewSmtLib,
            ViaDefineFun,
            DontCare // might be used when we translate to Int logics, since then overflow checks need no extra symbol, besides `<`
        }
    }

    /** No axioms at all -- might be useful for debugging purposes */
    @Suppress("Unused")
    class NoInstantiation(val config: Config) : CreateAxiomGeneratorContainer() {
        override fun create(lxf: LExpressionFactory, targetTheory: SmtTheory): AxiomGeneratorContainer {
            return object : AxiomGeneratorContainer(lxf, emptyList()) {}
        }
    }

    class HashArrayAxioms(val config: Config) : CreateAxiomGeneratorContainer() {
        override fun create(lxf: LExpressionFactory, targetTheory: SmtTheory): AxiomGeneratorContainer {
            return HashArrayAxiomsGeneratorContainer.create(
                lxf,
                targetTheory,
                config.overflowChecks,
            )
        }

    }

    class HashAxioms(val config: Config) : CreateAxiomGeneratorContainer() {
        override fun create(lxf: LExpressionFactory, targetTheory: SmtTheory): AxiomGeneratorContainer {
            return HashOnlyGeneratorContainer.create(
                lxf,
                targetTheory,
                config.overflowChecks,
            )
        }
    }

    class BwHashInstantiation(val config: Config) : CreateAxiomGeneratorContainer() {
        override fun create(lxf: LExpressionFactory, targetTheory: SmtTheory): AxiomGeneratorContainer {
            return BwHashInstantiationGeneratorContainer.create(
                lxf, targetTheory
            )
        }
    }

    class FullInstantiation(val config: Config, private val linearizationSelector: (LExpression) -> Boolean = { true }) : CreateAxiomGeneratorContainer() {
        override fun create(lxf: LExpressionFactory, targetTheory: SmtTheory): AxiomGeneratorContainer {
            return FullAxiomInstantiationGeneratorContainer.create(
                lxf, targetTheory, linearizationSelector
            )
        }
    }
}
