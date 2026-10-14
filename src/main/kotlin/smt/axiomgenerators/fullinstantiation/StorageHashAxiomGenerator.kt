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
import datastructures.stdcollections.*
import evm.EVM_MOD_GROUP256
import evm.twoToThe
import log.*
import org.jetbrains.annotations.TestOnly
import smt.GenerateEnv
import smt.axiomgenerators.AxiomContainer
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGenerator.Companion.taintedStorageKeyVars
import smt.newufliatranslator.AxiomGenerator
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.functionsymbols.*
import tac.Tag
import utils.*
import vc.data.*
import vc.gen.LExpVCMetaData
import java.math.BigInteger

private val logger = Logger(LoggerTypes.HASHING)

/**
 * Models hash applications (`keccak` et al.) as uninterpreted functions over the program's regular arithmetic sorts,
 * constrained by ground axioms instantiated at the terms that occur in the VC.
 *
 * The desired properties, and how they are enforced:
 *  1. determinism -- free, hash applications are (uninterpreted) function applications.
 *  2. injectivity -- for each hash application `h = hash(a_1..a_n)` in the VC we axiomatize pointwise inverse
 *     functions, `inv_i(h) = a_i`. Two equal hash values then force equal arguments.
 *  3. reserved region for the "basic" storage slots -- `max_solidity_slot < hash(...)`, plus range bounds according to
 *     the hash family's result size.
 *  4. region separation ("large gaps") -- an uninterpreted function `base` maps every storage key to the hash
 *     application that "owns" its region: `base(hash(x)) = hash(x)` and, for every addition in the VC that offsets a
 *     hash-derived value, `base(x + y) = base(x)`. Root slots have `base = 0` (via guarded axioms at the taint
 *     boundary). Consequently `hash(x) + o = hash(y) + o'` implies `hash(x) = hash(y)`, and a hash-derived value can
 *     never equal a root slot.
 *  5. large constants in code (potentially precomputed hashes) are separated from symbolic hashes: `base(c) = 0`
 *     (together with 3 and 4, this makes `c == hash(x)` contradictory).
 *
 * Which additions "offset a hash-derived value", and which terms sit at the taint boundary (and thus get the guarded
 * root-slot axiom `t <= max_solidity_slot => base(t) = 0`), is determined by a taint analysis: hash applications
 * generate taint, additions/subtractions and `ite`s propagate it. Variables' taint is computed on the TAC program
 * (see [taintedStorageKeyVars]) and carried over to the VC's identifiers by name.
 *
 * Everything is instantiated only at terms that occur in the VC (skipping terms that mention quantified variables,
 * which may not appear in ground axioms); there is no program transformation, no dedicated storage-key sort, and no
 * per-scheme configuration.
 *
 * Note: additions on storage keys use the program's regular (wrapping) arithmetic here, whereas previously they were
 * rewritten to non-wrapping `Int` additions. The `base` axioms attach to the terms as they are, so region separation
 * is unaffected; behavior may differ in intentional-overflow corner cases.
 */
class StorageHashAxiomGenerator(
    private val lxf: LExpressionFactory,
    private val maxSoliditySlot: BigInteger = Config.MaxBaseStorageSlot.get(),
    private val targetTheory: SmtTheory,
) : AxiomGenerator() {

    private val maxSoliditySlotLExp = lxf.litInt(maxSoliditySlot)

    /** See documentation of the field of the same name in [vc.gen.LExpVC]. */
    private var notLargeConstantsInCode: Set<BigInteger> = emptySet()

    @TestOnly
    fun emptyNotLargeConstantsInCode() {
        notLargeConstantsInCode = emptySet()
    }

    private val largeConstantsInCode = mutableSetOf<BigInteger>()

    private val hashApps = mutableSetOf<ApplyExprView<AxiomatizedFunctionSymbol.Hash.SimpleHashN>>()
    private val addApps = mutableSetOf<LExpression.ApplyExpr>()
    private val subApps = mutableSetOf<LExpression.ApplyExpr>()
    private val iteApps = mutableSetOf<LExpression.ApplyExpr>()
    private val storageLocs = mutableSetOf<LExpression>()

    /** the smt names of the program variables that may hold hash-derived storage keys */
    private var taintedVarNames: Set<String> = emptySet()

    override fun visitVCMetaData(lExpVc: LExpVCMetaData) {
        notLargeConstantsInCode = lExpVc.notLargeConstantsInCode
        taintedVarNames = taintedStorageKeyVars(lExpVc.tacProgram).mapToSet { it.smtRep }
        // show all terms of interest to the generator as if they were in the VC
        lExpVc.termsOfInterest?.forEach { visit(it, GenerateEnv()) }
        super.visitVCMetaData(lExpVc)
    }

    override fun visit(e: LExpression, env: GenerateEnv) {
        if (e.isNumericLiteral) {
            val i = e.asConst
            if (i > maxSoliditySlot && i < EVM_MOD_GROUP256 && i !in notLargeConstantsInCode) {
                largeConstantsInCode += i
            }
        }
        if (e !is LExpression.ApplyExpr) {
            return
        }
        if (!env.isEmpty() && e.contains { sub -> sub is LExpression.Identifier && sub in env.quantifiedVariables }) {
            // ground axioms may not mention quantified variables. Terms under a quantifier that don't mention its
            // variables are still fair game -- without this, a hash application occurring only inside a `forall`
            // body would get no axioms at all (an issue when quantifier grounding is off).
            return
        }
        when (val f = e.f) {
            is AxiomatizedFunctionSymbol.Hash.SimpleHashN ->
                hashApps += e.narrow()

            is NonSMTInterpretedFunctionSymbol.Vec.Add,
            TheoryFunctionSymbol.Vec.IntAdd ->
                addApps += e

            is NonSMTInterpretedFunctionSymbol.Binary.Sub,
            TheoryFunctionSymbol.Binary.IntSub ->
                subApps += e

            is TheoryFunctionSymbol.Ternary.Ite ->
                iteApps += e

            is TheoryFunctionSymbol.Array.Select ->
                if (f.mapSort == Tag.WordMap) {
                    storageLocs += e.args[1]
                }

            is TheoryFunctionSymbol.Array.Store ->
                if (f.mapSort == Tag.WordMap) {
                    storageLocs += e.args[1]
                }

            else -> Unit
        }
    }

    /**
     * The taint analysis, at the level of the VC's expressions. A term is tainted iff it may denote a hash-derived
     * storage key: hash applications generate taint, additions/subtractions and `ite`s propagate it, and identifiers
     * are tainted iff the program variable of the same name is (see [taintedStorageKeyVars]).
     */
    private inner class Taint {
        /** results are stable, so they can be cached */
        private val taintedTerms = mutableSetOf<LExpression>()

        fun isTainted(e: LExpression): Boolean {
            if (e in taintedTerms) {
                return true
            }
            val result = when (e) {
                is LExpression.Identifier -> e.id in taintedVarNames
                is LExpression.ApplyExpr -> when (val f = e.f) {
                    is AxiomatizedFunctionSymbol.Hash.SimpleHashN ->
                        f.hashFamily.requiresLargeGaps

                    is NonSMTInterpretedFunctionSymbol.Vec.Add,
                    TheoryFunctionSymbol.Vec.IntAdd ->
                        e.args.any(::isTainted)

                    // (over-approximate: the difference of two storage keys is a plain number, not a storage
                    // key -- but checking that here would make the taint non-monotone, so it is checked when
                    // the subtraction's base axiom is emitted instead)
                    is NonSMTInterpretedFunctionSymbol.Binary.Sub,
                    TheoryFunctionSymbol.Binary.IntSub ->
                        isTainted(e.lhs)

                    is TheoryFunctionSymbol.Ternary.Ite ->
                        isTainted(e.args[1]) || isTainted(e.args[2])

                    else -> false
                }

                else -> false
            }
            if (result) {
                taintedTerms += e
            }
            return result
        }
    }

    private fun base(arg: LExpression) = lxf.applyExp(AxiomatizedFunctionSymbol.Hash.Base, arg)

    override fun yield(axiomContainer: AxiomContainer) {
        val taint = Taint()

        val keccakApps = hashApps.filter { it.f.hashFamily.requiresLargeGaps }

        /* injectivity */
        hashApps.groupBy { it.f.hashFamily }.forEachEntry { (hashFamily, apps) ->
            BijectionAxiomHelper.addPointwiseInverseFunctions(
                axiomContainer,
                { i -> AxiomatizedFunctionSymbol.Hash.PointwiseInversePerHashFamily(i, hashFamily) },
                apps.map { it.exp }
            )
        }

        /* range bounds */
        val isBvLogic = targetTheory.arithmeticOperations == SmtTheory.ArithmeticOperations.BitVector
        hashApps.forEach { hashApp ->
            val exclusiveUpperBound =
                hashApp.f.hashFamily.resultSizeInBytes.let { rsib ->
                    check(rsib <= 32) { "unexpectedly high resultSizeInBytes: \"$rsib\"" }
                    when {
                        rsib < 32 -> twoToThe(rsib * 8)
                        isBvLogic -> null // the sort takes care of the bound
                        else -> EVM_MOD_GROUP256
                    }
                }
            if (exclusiveUpperBound != null) {
                axiomContainer.addAxiom(
                    lxf { hashApp.exp lt litInt(exclusiveUpperBound) },
                    description = "upper bound for ${hashApp.f}"
                )
            }
            axiomContainer.addAxioms(
                "max_solidity_slot < hash(x)" to { maxSoliditySlotLExp lt hashApp.exp }
            )
        }

        if (keccakApps.isEmpty()) {
            // all the `base` axioms below are vacuous without a region-owning hash application: only
            // `base(hash(x)) == hash(x)` forces a non-zero base, so without one, `base == 0` everywhere satisfies
            // them all and they can never contribute to unsatisfiability. Skip the noise (this makes hash-free VCs
            // -- e.g. all of Solana -- entirely free of `base` axioms for their large constants).
            return
        }

        /* region separation: base(hash(x)) == hash(x) */
        keccakApps.forEach { keccakApp ->
            axiomContainer.addAxiom(
                lxf { base(keccakApp.exp) eq keccakApp.exp },
                description = "base(hash(x)) == hash(x)"
            )
        }

        /* region separation: base(x + y) == base(x) for additions with exactly one tainted operand.
         * (with more than one tainted operand -- e.g. the difference of two storage keys -- the result is not a
         * storage key, and the axiom would be wrong: `key1 - key2` may well be 0 or another root slot.) */
        addApps.forEach { app ->
            addCarrierAxiom(axiomContainer, app, app.args.filter(taint::isTainted))
        }
        subApps.forEach { app ->
            if (!taint.isTainted(app.rhs)) {
                addCarrierAxiom(axiomContainer, app, app.args.take(1).filter(taint::isTainted))
            }
        }

        /* the guarded root-slot axiom, at the taint boundary:
         *  - untainted storage access locations,
         *  - untainted branches of tainted `ite`s,
         *  - untainted arguments of keccak applications (covers e.g. mapping keys and root slots being hashed). */
        val rootSlotCandidates = mutableSetOf<LExpression>()
        storageLocs.filterTo(rootSlotCandidates) { !taint.isTainted(it) }
        iteApps.forEach { ite ->
            if (taint.isTainted(ite)) {
                ite.args.drop(1).filterTo(rootSlotCandidates) { !taint.isTainted(it) }
            }
        }
        keccakApps.forEach { keccakApp ->
            keccakApp.exp.args.filterTo(rootSlotCandidates) { !taint.isTainted(it) }
        }
        rootSlotCandidates.forEach { t ->
            axiomContainer.addAxiom(
                lxf { (t le maxSoliditySlotLExp) implies (base(t) eq ZERO) },
                description = "t <= max_solidity_slot => base(t) == 0"
            )
        }

        /* large constants in code are potential precomputed hashes: they get their own region-0 treatment. Note
         * that this also separates them from the symbolic hash applications: `c == hash(x)` would force
         * `0 == base(c) == base(hash(x)) == hash(x)`, contradicting the lower bound above. */
        largeConstantsInCode.forEach { c ->
            axiomContainer.addAxiom(
                lxf { base(litInt(c)) eq litInt(0) },
                description = "base(<largeConstantInCode>) == 0"
            )
        }
    }

    private fun addCarrierAxiom(
        axiomContainer: AxiomContainer,
        app: LExpression.ApplyExpr,
        carriers: List<LExpression>,
    ) {
        when (carriers.size) {
            0 -> Unit
            1 -> axiomContainer.addAxiom(
                lxf { base(app) eq base(carriers.single()) },
                description = "base(x + y) == base(x)"
            )

            else -> logger.warn { "addition of more than one storage key, skipping its base axiom: $app" }
        }
    }

    override fun beforeScriptFreeze() {
        val arities = mutableSetOf<Int>()
        val hashFamilies = mutableSetOf<HashFamily>()
        hashApps.forEach { app ->
            hashFamilies += app.f.hashFamily
            arities += app.f.arity
        }

        hashFamilies.forEach { hashFamily ->
            arities.forEach { arity ->
                for (i in 0 until arity) {
                    lxf.registerFunctionSymbol(
                        AxiomatizedFunctionSymbol.Hash.PointwiseInversePerHashFamily(i, hashFamily)
                    )
                }
            }
        }

        // matching the guard in [yield]: `base` exists iff some hash application owns a region
        if (hashApps.any { it.f.hashFamily.requiresLargeGaps }) {
            lxf.registerFunctionSymbol(AxiomatizedFunctionSymbol.Hash.Base)
        }
        super.beforeScriptFreeze()
    }

    companion object {
        /**
         * The program variables that may hold hash-derived storage keys: (large-gaps) hash assignments generate,
         * additions propagate from any operand, subtractions from the left one, `ite`s from both branches, and
         * assignments propagate.
         *
         * A single forward pass over the blocks in topological order suffices: the program is a loop-free DSA, so
         * every variable's definition is processed before any of its uses.
         */
        fun taintedStorageKeyVars(code: CoreTACProgram): Set<TACSymbol.Var> {
            val tainted = mutableSetOf<TACSymbol.Var>()

            fun isTainted(e: TACExpr): Boolean = when (e) {
                is TACExpr.Sym.Var -> e.s in tainted
                is TACExpr.SimpleHash -> e.hashFamily.requiresLargeGaps
                is TACExpr.Vec.Add, is TACExpr.Vec.IntAdd -> (e as TACExpr.Vec).ls.any(::isTainted)
                is TACExpr.BinOp.Sub -> isTainted(e.o1)
                is TACExpr.BinOp.IntSub -> isTainted(e.o1)
                is TACExpr.TernaryExp.Ite -> isTainted(e.t) || isTainted(e.e)
                else -> false
            }

            val g = code.analysisCache.graph
            g.lcmdSequence(code.topoSortFw).forEach { (_, cmd) ->
                if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && isTainted(cmd.rhs)) {
                    tainted += cmd.lhs
                }
            }

            return tainted
        }
    }
}
