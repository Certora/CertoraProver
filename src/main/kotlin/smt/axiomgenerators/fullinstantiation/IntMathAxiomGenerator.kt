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
import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import log.*
import smt.FreeIdentifierCollector
import smt.GenerateEnv
import smt.axiomgenerators.*
import smt.axiomgenerators.StringExpressionPair.Companion.toExpr
import smt.newufliatranslator.AxiomGenerator
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.*
import tac.Tag
import utils.*
import vc.data.LExpression
import java.math.BigInteger

private val logger = Logger(LoggerTypes.SMT_MATH_AXIOMS)

/**
 * Used for LIA and NIA only.
 *
 * Adds axioms for the power operator, and axiomatizes the shift operations as multiplication (or division) by powers
 * of 2. In the case of LIA and shifts by a non-constant, this does not help to axiomatize precisely. For that we'll
 * need a huge switch-case axiom on 0..255. Perhaps that is worth a try...
 *
 * Also creates the define-fun for simplified addition and subtractions followed by modulo.
 */
class IntMathAxiomGenerator(val lxf: LExpressionFactory, private val liaGenerator: LinearMathAxiomGenerator? = null) :
    AxiomGenerator() {

    private val axioms = AxiomSet(lxf)

    /** The set of constant bases we see in `pow` expressions */
    private val expBases = mutableSetOf<BigInteger>()
    private val constPowerExprs = mutableMultiMapOf<Int, LExpression>()

    private val cachedFreeIdentifierCollector = FreeIdentifierCollector(withCache = true)

    private val basicMathAxiomsDefs = BasicMathAxiomsDefs(lxf)

    /** Tags see in parameters of `Add`. These will generate our simplified modulo function for the result of addition */
    private val simpleAddTags = mutableSetOf<Tag.Bits>()

    /** Same as [simpleAddTags], but for `Sub` */
    private val simpleSubTags = mutableSetOf<Tag.Bits>()

    override fun visit(e: LExpression, env: GenerateEnv) {
        if (e !is LExpression.ApplyExpr) {
            return
        }
        if (!env.isEmpty() && // <- optimization to save the collect call below in case
            cachedFreeIdentifierCollector.collect(e).containsAny(env.quantifiedVariables)
        ) {
            /** * See [BitwiseAxiomGenerator] for analogous case. */
            return
        }

        fun pow2Expr(rhs: LExpression) = lxf {
            if (rhs.isActuallyInt) {
                twoTo(rhs.asConst.toInt())
            } else {
                expBases += BigInteger.TWO
                TWO uninterpExp rhs
            }
        }

        when (val f = e.f) {

            // a >>l b is same as a/2^b
            is NonSMTInterpretedFunctionSymbol.Binary.ShiftRightLogical -> {
                val divExp = lxf { e.lhs / pow2Expr(e.rhs) }
                liaGenerator?.visit(divExp, env)
                val exp = lxf {
                    ite(e.rhs intLt litInt(256), divExp, ZERO)
                }
                axioms.addX(
                    listOf(), env,
                    "shift right logical same as div" toExpr { e eq exp }
                )
            }

            // a <<l b is same as (a * 2^l) % 2^256 and we do the mod exactly.
            is NonSMTInterpretedFunctionSymbol.Binary.ShiftLeft -> {
                val mulExp = lxf { e.lhs * pow2Expr(e.rhs) }
                // mod is important because the implicit mod 2^256 of multiplication in LIA is modeled imprecisely,
                // but we want shift-left to be precise.
                val exp = lxf {
                    ite(e.rhs intLt litInt(f.tag.bitwidth), mulExp, ZERO) % litInt(f.tag.modulus)
                }
                liaGenerator?.visit(mulExp, env)
                liaGenerator?.visit(exp, env)
                axioms.addX(
                    listOf(), env,
                    "shift left same as mul" toExpr { e eq exp }
                )
            }


            is NonSMTInterpretedFunctionSymbol.Binary.Exp, // in ExpNormalizerIA we will convert this to uninterp_exp(..) % 2^256, here we add the axiom for uninterp_exp
            is AxiomatizedFunctionSymbol.UninterpExp -> {
                if (e.lhs.isConst) {
                    expBases += e.lhs.asConst
                }
                if (e.rhs.isActuallyInt) {
                    e.rhs.asConst.toIntOrNull()?.takeIf {
                        val res = it <= Config.Smt.MaxPreciseConstantExponent.get()
                        if (!res) {
                            @Overapproximation("constant exponentiation: exponent too high")
                            logger.warn {
                                "Constant exponentiation with too-high exponent -- overapproximating it. " +
                                    "Expression: $e"
                            }
                        }
                        res
                    }?.let { i -> constPowerExprs.add(i, e.lhs) }
                }

                if (e.f is NonSMTInterpretedFunctionSymbol.Binary.Exp) { // this will turn into UninterpExp by ExpNormalizer..
                    lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.UninterpExp(Tag.Int))
                    lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.UninterpExp(Tag.Bit256))
                }
            }

            is NonSMTInterpretedFunctionSymbol.Binary.Sub ->
                simpleSubTags += e.tag as Tag.Bits

            is NonSMTInterpretedFunctionSymbol.Vec.Add ->
                simpleAddTags += e.tag as Tag.Bits

            else -> Unit
        }
        return
    }

    override fun yield(axiomContainer: AxiomContainer) {
        axiomContainer.addAll(axioms)
        for (base in expBases) {
            axiomContainer.addAxiom(basicMathAxiomsDefs.powAxiom(base))
        }
        constPowerExprs.forEachEntry { (pow, exprs) ->
            basicMathAxiomsDefs.constantPowAxiom(pow).addAllToContainer(axiomContainer, exprs)
        }
    }

    override fun yieldDefineFuns(): List<DefType> =
        simpleAddTags.map { tag ->
            // implement modulo for additions using a case split and possibly a subtraction
            lxf.buildConstantNewUFLIAwud("t!0", Tag.Int).let { t0 ->
                DefType(
                    AxiomatizedFunctionSymbol.SimpleAddModulo(tag),
                    listOf(t0),
                    Tag.Int, lxf {
                        ite(t0.within(ZERO, litInt(tag.modulus)), t0, t0 - litInt(tag.modulus))
                    }
                )
            }
        } + simpleSubTags.map { tag ->
            // implement modulo for subtractions using a case split and possibly an addition
            lxf.buildConstantNewUFLIAwud("t!0", Tag.Int).let { t0 ->
                DefType(
                    AxiomatizedFunctionSymbol.SimpleSubModulo(tag),
                    listOf(t0),
                    Tag.Int, lxf {
                        ite(t0.within(litInt(-tag.modulus), ZERO), t0 + litInt(tag.modulus), t0)
                    }
                )
            }
        }


    override fun beforeScriptFreeze() {
        constPowerExprs.keys.forEach { basicMathAxiomsDefs.constantPowAxiom(it) } // registers them as a side effect
        simpleAddTags.forEach {
            lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.SimpleAddModulo(it))
        }
        simpleSubTags.forEach {
            lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.SimpleSubModulo(it))
        }
        super.beforeScriptFreeze()
    }
}
