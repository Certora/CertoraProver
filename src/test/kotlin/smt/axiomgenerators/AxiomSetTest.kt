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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import smt.GenerateEnv
import smt.axiomgenerators.StringExpressionPair.Companion.toExpr
import smt.solverscript.LExpressionFactory
import tac.Tag
import vc.data.LExpression

/**
 * An axiom generator visiting the body of a quantified formula sees the quantified variables in its
 * [GenerateEnv], and [AxiomSet] binds them in the axioms generated there. These tests pin down which
 * variables actually end up bound.
 */
class AxiomSetTest {
    private val lxf = LExpressionFactory()
    private val p = LExpression.Identifier("p", Tag.Bit256)
    private val q = LExpression.Identifier("q", Tag.Bit256)
    private val c = lxf.const("c", Tag.Bit256)

    /** the environment of an expression nested inside `forall vars. ...` */
    private fun envOf(vararg vars: LExpression.Identifier) =
        GenerateEnv(listOf(lxf { forall(*vars) { TRUE } } as LExpression.QuantifiedExpr))

    private fun axiomIn(env: GenerateEnv, expr: LExpressionFactory.() -> LExpression): LExpression {
        val axioms = AxiomSet(lxf)
        axioms.addX(listOf(), env, "test axiom" toExpr expr)
        return axioms.single().lExpression
    }

    @Test
    fun axiomOutsideAnyQuantifierIsGround() {
        assertEquals(lxf { c lt litInt(5) }, axiomIn(GenerateEnv()) { c lt litInt(5) })
    }

    @Test
    fun axiomMentioningTheQuantifiedVarIsBound() {
        assertEquals(lxf { forall(q) { q lt litInt(5) } }, axiomIn(envOf(q)) { q lt litInt(5) })
    }

    /**
     * The case behind the `ArrayGenerator ... wasn't matched` crash: a term inside a quantifier body that
     * is constant with respect to the quantified variable, such as `c >>l 128` under `forall q`, must not
     * come out of axiom generation wrapped in `forall q`.
     */
    @Test
    fun axiomNotMentioningTheQuantifiedVarIsGround() {
        assertEquals(lxf { c lt litInt(5) }, axiomIn(envOf(q)) { c lt litInt(5) })
    }

    @Test
    fun onlyTheMentionedQuantifiedVarsAreBound() {
        assertEquals(lxf { forall(p) { p lt litInt(5) } }, axiomIn(envOf(q, p)) { p lt litInt(5) })
    }
}
