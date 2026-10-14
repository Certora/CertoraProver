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

package smt.axiomgenerators

import datastructures.stdcollections.*
import smt.GenerateEnv
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.getFreeIdentifiers
import smt.solverscript.functionsymbols.substitute
import vc.data.LExpression
import vc.data.LExpressionWithComment

/**
 * Represents an axiom instantiation.
 *
 * In this class, we assume that each axiom instantiation is ground, except for some existentially quantified vars.
 *
 * Background:
 *  - We use uninterpreted function symbols that we axiomatize on an on-demand basis
 *  - Usually, the axiomatization happens by asserting instantiations of general axioms. (e.g. mul(i, i) >= 0, when the
 *   expression mul(i, i) occurs in the program instead of asserting the quantified "forall x. mul(x, x) >= 0")
 *
 *  @param env: the environment of the expression(s) that triggered generation of this axiom. Current use case:
 *    If the triggering expression had quantified variables in it, we need to quantify the axiom accordingly
 */

data class StringExpressionPair(val description: String, val expr: LExpressionFactory.() -> LExpression) {
    companion object {
        infix fun String.toExpr(expr: LExpressionFactory.() -> LExpression) =
            StringExpressionPair(this, expr)
    }
}

/**
 * A convenience wrapper for a set of [AxiomInstance]s, providing a simple method of creating and adding
 * such axioms
 * @param lxf
 * @param set: the set of axioms, by default is empty at instantiation.
 */
class AxiomSet(val lxf: LExpressionFactory, val set: MutableSet<LExpressionWithComment> = mutableSetOf()) :
    MutableSet<LExpressionWithComment> by set {

    fun addX(
        existentialVars: List<LExpression.Identifier>,
        env: GenerateEnv,
        vararg pairs: StringExpressionPair
    ) {
        for ((description, expr) in pairs) {
            set.add(computeAxiom(lxf.expr(), description, existentialVars, env))
        }
    }

    private fun computeAxiom(
        exp: LExpression,
        description: String,
        existentialVars: List<LExpression.Identifier>,
        env: GenerateEnv
    ): LExpressionWithComment {
        /**
         * Of the variables the environment quantifies, only those that [exp] actually mentions get bound
         * here; [LExpDefineFun] does the same through [vc.data.LExpressionWithEnvironment.getQuantifiedVars]
         * for the define-fun flavour of axioms. Binding the others would not only be redundant, it would
         * hand the pipeline a quantifier that no other stage knows about:
         * [smt.axiomgenerators.fullinstantiation.ArrayGenerator] collects the quantifiers it finds in the vc
         * and requires every quantifier still present after axiom generation to be one of them.
         */
        val quantifiedVars = when {
            env.isEmpty() -> emptySet()
            else -> env.quantifiedVariables intersect exp.getFreeIdentifiers()
        }
        return LExpressionWithComment(
            if (quantifiedVars.isEmpty()) {
                val substitution =
                    existentialVars.associate { exVar ->
                        (exVar as LExpression) to lxf.createSkolemConstant(exVar)
                    }
                exp.substitute(lxf, substitution)
            } else {
                // quantified case
                lxf {
                    forall(quantifiedVars.toList()) {
                        exists(existentialVars) { exp }
                    }
                }
            },
            description
        )
    }
}


