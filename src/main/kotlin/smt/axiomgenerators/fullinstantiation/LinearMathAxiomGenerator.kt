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

import config.Config.Smt.easyLIA
import config.Config.Smt.noLIAAxioms
import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import log.*
import smt.FreeIdentifierCollector
import smt.GenerateEnv
import smt.axiomgenerators.*
import smt.newufliatranslator.AxiomGenerator
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.AxiomatizedFunctionSymbol
import smt.solverscript.functionsymbols.NonSMTInterpretedFunctionSymbol
import smt.solverscript.functionsymbols.TheoryFunctionSymbol
import smt.solverscript.functionsymbols.isConst
import tac.Tag
import utils.*
import vc.data.CoreTACProgram
import vc.data.LExpression
import vc.data.LExpressionWithEnvironment
import vc.data.lexpression.META_CMD_PTR
import vc.gen.LExpVCMetaData

private val logger = Logger(LoggerTypes.VC_TO_SMT_CONVERTER)

/**
 * Introduces axioms that restrict the interpretation of uninterpreted functions that model common arithmetic
 * operations, like "mul", which models integer multiplication (but which falls outside UFLIA, generally).
 */
class LinearMathAxiomGenerator(
    private val lxf: LExpressionFactory,
    private val linearizationSelector: (LExpression) -> Boolean = { true },
) : AxiomGenerator() {

    private lateinit var tacProgram: CoreTACProgram

    override fun visitVCMetaData(lExpVc: LExpVCMetaData) {
        tacProgram = lExpVc.tacProgram
        super.visitVCMetaData(lExpVc)
    }

    private val seenTags = mutableSetOf<Tag>()

    /**
     * Note that axioms are generated and registered at first usage, so we are careful not to refer to any
     * axiom unless we need it (it's not that bad, but is noise in the SMT file).
     */
    private val defs = BasicMathAxiomsDefs(lxf)

    /**
     * Maps the specific axiom to the set of applications encountered in visit that is relevant to this axiom.
     * Note that once an axiom is added to the map as a key, it's actually created (as they are lazy), and therefore also
     * registers its function symbol.
     */
    private val binaryApps = mutableMultiMapOf<BinaryIntAxiomDef, LExpressionWithEnvironment>()
    private val boundedVars = mutableMultiMapOf<UnaryIntAxiomDef, LExpressionWithEnvironment>()

    /** maps the used binary axioms to their unary counterparts */
    private val binaryToUnaryDef = mutableMapOf<BinaryIntAxiomDef, UnaryAxiomDef>()
    private var mulIsUsed = false

    /** Axioms that don't use a define-fun */
    private val randomAxioms = AxiomSet(lxf)

    private val cachedFreeIdentifierCollector = FreeIdentifierCollector(withCache = true)

    private fun add(axiom: BinaryIntAxiomDef, e: LExpression, env: GenerateEnv, unaryCounterpart: UnaryIntAxiomDef) {
        binaryToUnaryDef[axiom] = unaryCounterpart
        binaryApps.add(axiom, LExpressionWithEnvironment(e, env))
    }

    private fun addCommutative(
        axiom: BinaryIntAxiomDef,
        e: LExpression.ApplyExpr,
        env: GenerateEnv,
        unaryCounterpart: UnaryIntAxiomDef
    ) {
        val reversed = LExpressionWithEnvironment(lxf { applyExp(e.f, e.rhs, e.lhs) }, env)
        if (binaryApps[axiom]?.contains(reversed) != true) {
            add(axiom, e, env, unaryCounterpart)
        }
    }

    override fun visit(e: LExpression, env: GenerateEnv) {
        if (!env.isEmpty() && // <- optimization to save the collect call below in case
            cachedFreeIdentifierCollector.collect(e).containsAny(env.quantifiedVariables)
        ) {
            /** * See [BitwiseAxiomGenerator] for analogous case. */
            return
        }
        if (e !is LExpression.ApplyExpr) {
            return
        }
        seenTags += e.tag

        when (e.f) {
            is NonSMTInterpretedFunctionSymbol.Vec.Mul, is TheoryFunctionSymbol.Vec.IntMul -> {
                if (META_CMD_PTR !in e.meta) {
                    logger.info { "Multiplication $e has no cmdptr meta" }
                }
                if (e.args.size == 2) {
                    // in the case that one side is a constant, the order is important specifically for
                    // "mul_signed_const" axiom. The reason is that z3 doesn't view `x * (const - const)` as
                    // a multiplication by a constant, eventually making us ignore the result (rightfully so).
                    when {
                        e.lhs.isConst ->
                            add(defs.combinedBinaryMulWithConst(e.tag), e, env, defs.combinedUnaryMul)

                        e.rhs.isConst ->
                            add(
                                defs.combinedBinaryMulWithConst(e.tag),
                                lxf.applyExp(e.f, e.rhs, e.lhs),
                                env,
                                defs.combinedUnaryMul
                            )

                        else -> {
                            mulIsUsed = true
                            addCommutative(defs.combinedBinaryMul(e.tag), e, env, defs.combinedUnaryMul)
                        }
                    }
                } else {
                    // TODO : should be handled at least partially
                    logger.warn { "Got vector mul of size ${e.args.size}: $e, may not generate all required axioms" }
                }
            }

            is TheoryFunctionSymbol.Binary.IntDiv,
            is NonSMTInterpretedFunctionSymbol.Binary.Div ->
                if (e.rhs.isConst) {
                    add(defs.combinedBinaryDivByConst, e, env, defs.combinedUnaryDiv)
                } else {
                    add(defs.combinedBinaryDiv, e, env, defs.combinedUnaryDiv)
                }


            is TheoryFunctionSymbol.Binary.IntMod,
            is NonSMTInterpretedFunctionSymbol.Binary.Mod ->
                if (e.rhs.isConst) {
                    add(defs.combinedBinaryModByConst, e, env, defs.combinedUnaryMod)
                } else {
                    add(defs.combinedBinaryMod, e, env, defs.combinedUnaryMod)
                }

            else -> Unit
        }
    }

    /**
     * Adds the quad axioms for pairs of multiplications that may co-exist, i.e., are together on at least one
     * computation path. It also adds multiplication instances arising from these axioms, so that "standard"
     * multiplication axioms will be generated for them.
     */
    private fun handlePairsOfMultiplications(axiomContainer: AxiomContainer) {
        // TODO : should we consider multiplications by constants as well? multiplications created by divison axioms?
        var connected = 0
        var notConnected = 0

        val blockGraphTransitiveClosures by lazy {
            tacProgram.analysisCache.reachability
        }

        fun mayCoexist(e1: LExpression.ApplyExpr, e2: LExpression.ApplyExpr): Boolean{
            val block1 = e1.meta[META_CMD_PTR]?.block ?: return true
            val block2 = e2.meta[META_CMD_PTR]?.block ?: return true
            return block1 in blockGraphTransitiveClosures[block2]!! ||
                   block2 in blockGraphTransitiveClosures[block1]!!
        }

        fun <A> Pair<A, A>.forThisAndReversed(f: (A, A) -> Unit) {
            f(first, second)
            f(second, first)
        }
        for (tag in seenTags) {
            // Create a copy so we don't mutate while traversing
            val instances = binaryApps[defs.combinedBinaryMul(tag)]?.toList() ?: emptyList()

            instances.forEach { (e1, env1) ->
                instances.forEach inner@{ (e2, env2) ->
                    // Our axioms are uninformative when the pairs are the same.
                    if (e1 != e2 || env1 != env2) {
                        if (!linearizationSelector(e1) && !linearizationSelector(e2)) {
                            return@inner
                        }
                        if (mayCoexist(e1 as LExpression.ApplyExpr, e2 as LExpression.ApplyExpr)) {
                            connected++
                            val a = LExpressionWithEnvironment(e1.lhs, env1)
                            val b = LExpressionWithEnvironment(e1.rhs, env1)
                            val c = LExpressionWithEnvironment(e2.lhs, env2)
                            val d = LExpressionWithEnvironment(e2.rhs, env2)
                            // For the monotonicity axiom, we don't need to generate all permutations.
                            defs.twoMulsMonotone.addToContainer(axiomContainer, a, b, c, d)
                            if (a != b && c != d) { // no need for the reversal in this case
                                defs.twoMulsMonotone.addToContainer(axiomContainer, b, a, c, d)
                            }

                            (a to b).forThisAndReversed { x, y ->
                                (c to d).forThisAndReversed { t, z ->
                                    if (y.exp != z.exp) {
                                        defs.twoMulsDistributivity.addToContainer(axiomContainer, x, y, t, z)
                                        // Accounts for the multiplication of x * (y - z)
                                        val second =
                                            LExpressionWithEnvironment(lxf { y.exp - z.exp }, (y.env).merge(z.env))
                                        binaryApps.add(
                                            defs.combinedBinaryMul(tag),
                                            LExpressionWithEnvironment(
                                                lxf { x.exp * second.exp },
                                                second.env.merge(x.env)
                                            )
                                        )
                                    }
                                }
                            }
                        } else {
                            notConnected++
                        }
                    }
                }
            }
        }
        logger.info { "Added $connected double-mul axioms, but block graph analysis saved us $notConnected" }
    }


    override fun yield(axiomContainer: AxiomContainer) = timeIt("Yield") {
        if (noLIAAxioms.get()) {
            return@timeIt
        }
        if (mulIsUsed && !easyLIA.get()) {
            handlePairsOfMultiplications(axiomContainer)
        }
        boundedVars.forEach { (axiom, set) ->
            axiom.addAllToContainer(axiomContainer, set)
        }

        binaryApps.forEach { (axiom, set) ->
            val allArgs = mutableSetOf<LExpressionWithEnvironment>()
            axiom.addAllToContainer(axiomContainer, set.map { (exp, e) ->
                exp as LExpression.ApplyExpr
                val lhs = LExpressionWithEnvironment(exp.lhs, e)
                val rhs = LExpressionWithEnvironment(exp.rhs, e)
                allArgs += lhs
                allArgs += rhs
                lhs to rhs
            })
            binaryToUnaryDef[axiom]!!.addAllToContainer(axiomContainer, allArgs)
        }
        axiomContainer.addAll(randomAxioms)
        defs.noArgsAxioms.forEach { axiomContainer.addAxiom(it) }
    }


    override fun yieldDefineFuns(): List<DefType> =
        seenTags.filterIsInstance<Tag.Bits>().map { tag ->
            lxf.buildConstantNewUFLIAwud("t!0", Tag.Int).let { t0 ->
                val modulus = lxf.litInt(tag.modulus)
                DefType(
                    AxiomatizedFunctionSymbol.UninterpTagMod(tag),
                    listOf(t0),
                    Tag.Int, lxf {
                        switch(
                            t0.within(ZERO, modulus) to t0,
                            t0.within(modulus, litInt(tag.modulus * 2)) to t0 - modulus,
                            t0.within(litInt(-tag.modulus), ZERO) to t0 + modulus,
                            elseExpr = t0 uninterpMod modulus
                        )
                    }
                )
            }
        }

    override fun beforeScriptFreeze() {
        // TODO: Actually these don't necessarily need to be registered.
        lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.UninterpDiv)
        lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.UninterpMul)
        lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.UninterpMod)
        lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.UninterpSMod)

        // These axioms will not be registered automatically, so we do it here.
        if (mulIsUsed) {
            defs.twoMulsMonotone
            defs.twoMulsDistributivity
        }
    }
}
