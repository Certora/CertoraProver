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

import config.Config
import evm.EVM_MOD_GROUP256
import smt.axiomgenerators.fullinstantiation.expnormalizer.ExpNormalizerIA
import smt.solverscript.SmtTheory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import smt.GenerateEnv
import smt.axiomgenerators.AxiomContainer
import smt.solverscript.functionsymbols.NonSMTInterpretedFunctionSymbol
import smt.solverscript.functionsymbols.traverseQuant
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.AxiomatizedFunctionSymbol
import smtlibutils.data.SatResult
import tac.Tag
import vc.data.HashFamily
import vc.data.LExpression
import vc.data.TACBuilderAuxiliaries
import vc.data.TACExpr
import vc.data.TACProgramBuilder
import vc.data.asTACExpr
import java.math.BigInteger

internal class StorageHashAxiomGeneratorTest : TACBuilderAuxiliaries() {

    private val testSetup =
        MockLExpToSmtLib.TestSetup.makeNiaSetup { lxf: LExpressionFactory ->
            listOf(
                StorageHashAxiomGenerator(
                    lxf,
                    targetTheory = MockLExpToSmtLib.niaTargetTheory,
                )
            )
        }

    /** like [testSetup], but over a logic with array theory, for tests that use `select` directly */
    private val arrayTheory = SmtTheory.fromString("QF_AUFNIA")
    private val testSetupWithArrays =
        MockLExpToSmtLib.TestSetup(
            arrayTheory,
            { lxf: LExpressionFactory ->
                listOf(StorageHashAxiomGenerator(lxf, targetTheory = arrayTheory))
            },
            { lxf: LExpressionFactory -> ExpNormalizerIA(lxf, arrayTheory) { false } },
        )

    /**
     * Builds a standard keccack hash with the given arguments; sets the length field as "32 * <number of arguments>",
     * mirroring what [vc.data.TACExpr.SimpleHash] produces in `toLExpression`.
     */
    private fun LExpressionFactory.keccack(vararg args: LExpression): LExpression {
        val arity = args.size
        return applyExp(
            AxiomatizedFunctionSymbol.Hash.SimpleHashN(Tag.Bit256, arity + 1, HashFamily.Keccack),
            listOf(litInt(arity * 32)) + args.toList()
        )
    }

    private fun LExpressionFactory.makeConstant(name: String, type: Tag = Tag.Bit256) =
        this.const(name, type, null)

    /**
     * Creates a formula for testing that hashes of the same arity with different arguments can't collide.
     */
    private fun formulaNonCollisionSameArity01(): (LExpressionFactory) -> List<LExpression> =
        { lxf: LExpressionFactory ->
            val (x1, x2, x3) = listOf("x1", "x2", "x3").map { lxf.makeConstant(it) }
            val (y1, y2, y3) = listOf("y1", "y2", "y3").map { lxf.makeConstant(it) }
            val h1 = lxf.makeConstant("h1")
            val h2 = lxf.makeConstant("h2")
            listOf(
                lxf {
                    or(
                        x1 neq y1,
                        x2 neq y2,
                        x3 neq y3,
                    )
                },
                lxf { h1 eq keccack(x1, x2, x3) },
                lxf { h2 eq keccack(y1, y2, y3) },
                lxf { h1 eq h2 },
            )
        }

    @Test
    fun nonCollisionSameArity01() {
        val res = MockLExpToSmtLib.setupAndCheck(
            testSetup,
            formulaNonCollisionSameArity01(),
        )
        assertEquals(SatResult.UNSAT, res.satResult)
    }

    /**
     * Creates a formula for testing that hash applications of different arities can't collide.
     * (This is enforced by the pointwise-inverse axioms through the hashes' `length` argument, which differs
     * between the two applications.)
     */
    private fun formulaNonCollisionDifferentArities01(): (LExpressionFactory) -> List<LExpression> =
        { lxf: LExpressionFactory ->
            val x = lxf.makeConstant("x")
            val y = lxf.makeConstant("y")
            val z = lxf.makeConstant("z")
            val h1 = lxf.makeConstant("h1")
            val h2 = lxf.makeConstant("h2")

            listOf(
                lxf { h1 eq keccack(x) },
                lxf { h2 eq keccack(y, z) },
                lxf { h1 eq h2 },
            )
        }

    @Test
    fun nonCollisionDifferentArities01() {
        val res = MockLExpToSmtLib.setupAndCheck(
            testSetup,
            formulaNonCollisionDifferentArities01(),
        )
        assertEquals(SatResult.UNSAT, res.satResult)
    }

    /**
     * The complement of [nonCollisionDifferentArities01]: arity separation works through the `length` argument,
     * so two applications of different arities whose `length` arguments coincide (as can happen with non-word-aligned
     * hashing) MAY collide -- deliberately, since the same bytes chunked differently really are the same hash.
     */
    private fun formulaCollisionDifferentAritiesSameLength01(): (LExpressionFactory) -> List<LExpression> =
        { lxf: LExpressionFactory ->
            val a = lxf.makeConstant("a")
            val c = lxf.makeConstant("c")
            val d = lxf.makeConstant("d")
            val h1 = lxf.makeConstant("h1")
            val h2 = lxf.makeConstant("h2")
            listOf(
                lxf {
                    h1 eq applyExp(
                        AxiomatizedFunctionSymbol.Hash.SimpleHashN(Tag.Bit256, 2, HashFamily.Keccack),
                        litInt(64), a
                    )
                },
                lxf {
                    h2 eq applyExp(
                        AxiomatizedFunctionSymbol.Hash.SimpleHashN(Tag.Bit256, 3, HashFamily.Keccack),
                        litInt(64), c, d
                    )
                },
                lxf { h1 eq h2 },
            )
        }

    @Test
    fun collisionDifferentAritiesSameLength01() {
        val res = MockLExpToSmtLib.setupAndCheck(
            testSetup,
            formulaCollisionDifferentAritiesSameLength01(),
        )
        assertEquals(SatResult.SAT, res.satResult)
    }

    /**
     * Creates a formula for testing the "large gaps" (region separation) feature of our hashing:
     * an offset on top of one hash can never equal a different hash.
     *
     * The hash applications appear inline in the addition: variables get their taint from the TAC program
     * (see [StorageHashAxiomGenerator.taintedStorageKeyVars]), which these formula-only tests don't have.
     */
    private fun formulaLargeGaps01(
        additiveOffsetUpperBound: BigInteger = EVM_MOD_GROUP256,
    ): (LExpressionFactory) -> List<LExpression> =
        { lxf: LExpressionFactory ->
            val x = lxf.makeConstant("x")
            val y = lxf.makeConstant("y")
            val z = lxf.makeConstant("z")
            listOf(
                lxf { x neq y },
                lxf { z.within(litInt(0), litInt(additiveOffsetUpperBound)) },
                lxf { intAdd(keccack(x), z) eq keccack(y) },
            )
        }

    @Test
    fun largeGaps01() {
        val res = MockLExpToSmtLib.setupAndCheck(
            testSetup,
            formulaLargeGaps01()
        )
        assertEquals(SatResult.UNSAT, res.satResult)
    }

    /**
     * Creates a formula for testing the "reserved slots" feature of our hashing: a hash can never equal a value
     * that is at most `maxSoliditySlot`.
     */
    private fun formulaReservedSlots01(
        maxSoliditySlot: BigInteger = Config.MaxBaseStorageSlot.get(),
    ): (LExpressionFactory) -> List<LExpression> =
        { lxf: LExpressionFactory ->
            val x = lxf.makeConstant("x")
            val z = lxf.makeConstant("z")
            val h1 = lxf.makeConstant("h1")
            listOf(
                lxf { h1 eq keccack(x) },
                lxf { z.within(litInt(0), litInt(maxSoliditySlot + BigInteger.ONE)) },
                lxf { z eq h1 },
            )
        }

    @Test
    fun reservedSlots01() {
        val res = MockLExpToSmtLib.setupAndCheck(
            testSetup,
            formulaReservedSlots01()
        )
        assertEquals(SatResult.UNSAT, res.satResult)
    }

    /**
     * An offset below a hash (`hash - z` occurs, e.g., in array length computations) can never equal a small
     * ("root slot") value that is used as a storage location. Arithmetic alone can't show this (`keccak(x) - z` can
     * be any small value); it requires the `base` region axioms: the taint analysis pins `base(slot) = 0` via the
     * guarded axiom for untainted storage locations, while `base(keccak(x) - z) = base(keccak(x)) = keccak(x) > 0`.
     */
    private fun formulaHashOffsetVsRootSlot01(): (LExpressionFactory) -> List<LExpression> =
        { lxf: LExpressionFactory ->
            val x = lxf.makeConstant("x")
            val z = lxf.makeConstant("z")
            val slot = lxf.makeConstant("slot")
            val m = lxf.makeConstant("m", Tag.WordMap)
            listOf(
                lxf { slot.within(litInt(0), litInt(Config.MaxBaseStorageSlot.get())) },
                lxf { z.within(litInt(1), litInt(EVM_MOD_GROUP256)) },
                // both are used as storage locations
                lxf { select(m, keccack(x) intSub z, Tag.WordMap, Tag.Bit256) eq litInt(0) },
                lxf { select(m, slot, Tag.WordMap, Tag.Bit256) eq litInt(1) },
                // ... and alias
                lxf { slot eq (keccack(x) intSub z) },
            )
        }

    @Test
    fun hashOffsetVsRootSlot01() {
        val res = MockLExpToSmtLib.setupAndCheck(
            testSetupWithArrays,
            formulaHashOffsetVsRootSlot01()
        )
        assertEquals(SatResult.UNSAT, res.satResult)
    }

    /** The variable-level taint analysis runs on the TAC program: hash assignments generate, additions propagate
     * from any operand, subtractions from the left one only, `ite`s from both branches, other operations don't. */
    @Test
    fun taintedStorageKeyVarsOnTac() {
        val prog = TACProgramBuilder {
            a assign TACExpr.SimpleHash(32.asTACExpr, listOf(gS), HashFamily.Keccack)
            jump {
                b assign Add(aS, 1.asTACExpr)
                c assign Sub(bS, 1.asTACExpr)
                d assign Sub(1.asTACExpr, bS)
                e assign Ite(xS, 1.asTACExpr, bS)
                f assign Mul(bS, 2.asTACExpr)
            }
        }
        assertEquals(
            setOf(a, b, c, e),
            StorageHashAxiomGenerator.taintedStorageKeyVars(prog.code)
        )
    }

    /** The difference of two storage keys is a plain number, not a storage key -- `key1 - key2` may well be 0 or
     * another root slot, so it must not get a `base(x - y) == base(x)` axiom (that over-constraint made real rules
     * vacuous). */
    @Test
    fun noBaseAxiomForKeyMinusKey() {
        val lxf = LExpressionFactory()
        val gen = StorageHashAxiomGenerator(lxf, targetTheory = MockLExpToSmtLib.niaTargetTheory)
        gen.emptyNotLargeConstantsInCode()
        val formula = listOf(
            lxf {
                val key1 = applyExp(
                    NonSMTInterpretedFunctionSymbol.Vec.Add(Tag.Bit256), litInt(1), keccack(makeConstant("x"))
                )
                val key2 = keccack(makeConstant("y"))
                (key1 intSub key2) eq litInt(1)
            },
        )
        val axioms = yieldedAxioms(lxf, gen, formula)
        assertTrue(axioms.none { "pi_base (-" in it }) { "unexpected base axiom for key difference:\n${axioms.joinToString("\n")}" }
        // while the addition (a key) does get its axiom
        assertTrue(axioms.any { "pi_base (+" in it })
    }

    /** Terms under a quantifier get axioms iff they don't mention the quantified variables. */
    @Test
    fun quantifiedBodyTerms() {
        val lxf = LExpressionFactory()
        val gen = StorageHashAxiomGenerator(lxf, targetTheory = MockLExpToSmtLib.niaTargetTheory)
        gen.emptyNotLargeConstantsInCode()
        val qVar = lxf.const("q", Tag.Bit256, null)
        val env = GenerateEnv(
            listOf(lxf.forall(listOf(qVar), lxf.litBool(true)) as LExpression.QuantifiedExpr)
        )
        val groundHash = lxf.keccack(lxf.makeConstant("x"))
        val qVarHash = lxf.keccack(qVar)
        val axioms = run {
            groundHash.traverseQuant(env) { e, innerEnv -> gen.visit(e, innerEnv) }
            qVarHash.traverseQuant(env) { e, innerEnv -> gen.visit(e, innerEnv) }
            val container = AxiomContainer(lxf)
            gen.yield(container)
            container.getAxiomSequence().map { it.lExpression.toString() }.toList()
        }
        assertTrue(axioms.any { "pi_base (keccak" in it }) { "missing axioms for the ground hash:\n${axioms.joinToString("\n")}" }
        assertTrue(axioms.none { "q" in it }) { "axioms mention a quantified variable:\n${axioms.joinToString("\n")}" }
    }

    private fun yieldedAxioms(
        lxf: LExpressionFactory,
        gen: StorageHashAxiomGenerator,
        formula: List<LExpression>,
    ): List<String> {
        val env = GenerateEnv()
        formula.forEach { f -> f.traverseQuant { e, _ -> gen.visit(e, env) } }
        val container = AxiomContainer(lxf)
        gen.yield(container)
        return container.getAxiomSequence().map { it.lExpression.toString() }.toList()
    }
}
