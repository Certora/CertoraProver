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

package vc.gen

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.TACBlock
import config.Config
import config.Config.InlineReachVarDefs
import datastructures.stdcollections.*
import log.*
import rules.RuleChecker
import rules.TWOSTAGE_META_MODEL
import rules.TWOSTAGE_META_VARORIGIN
import smt.CoverageInfoEnum
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.*
import statistics.data.TACProgramMetadata
import tac.MetaMap
import tac.NBId
import tac.Tag
import tac.isMapType
import utils.*
import vc.data.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import vc.data.lexpression.META_AUTO_GENERATED_ASSERT
import vc.data.lexpression.META_CMD_PTR
import vc.data.lexpression.META_MODEL_TOI
import vc.data.lexpression.META_TOPLVL_CMD
import vc.data.state.ConcreteTacValue
import vc.data.state.TACValue
import verifier.SKOLEM_VAR_NAME
import java.math.BigInteger
import java.util.stream.Collectors

val logger = Logger(LoggerTypes.TAC_TO_LEXPR_CONVERTER)

/**
 * Generates Verification Condition (VC) for [core], following
 * Michael Barnett, K. Rustan M. Leino: Weakest-Precondition of Unstructured Programs. PASTE 2005: 82-87.
 *
 * @param core Program in DSA form
 */
class LeinoWP(
    val core: CoreTACProgram,
    val lxf: LExpressionFactory,
    val tacProgramMetadata: TACProgramMetadata,
) {

    private val tyScope: TACSymbolTable = core.symbolTable
    private val blocksWithReachVarsInUse = findReachVarsInUse()
    private val txf = TACExprFactTypeChecked(tyScope)

    /**
     * Constants that originate from the fixed model in the rerun of the two-stage mode.
     * Some context: we add axioms saying that any large constant (>[Config-MaxSoliditySlot]) is different from any hash
     * value (see [StorageHashAxiomGeneratorPlainInjectivity]). The fixed model in the two-stage mode may very well have
     * values for hashes, thus adding axioms for those breaks solving. (More precisely: it leads to spurious conflicts
     * in the SMT solver, and thus to incorrect UNSAT results)
     * We thus collect the constants from the fixed model and pass them on as [notLargeConstantsInCode] so that axioms
     * are not added for those constants. We make an exception if the same constant already exists in the code (we
     * collect those in [existingLargeConstants]): the previous model worked in the presence of the axiom, so we can
     * keep the axiom in the rerun as well.
     */
    private val constantsFromFixedModel = mutableSetOf<BigInteger>()

    /** Collect large constants that are already present in the code. */
    private val existingLargeConstants = core.analysisCache.graph.commands
        .flatMap { it.cmd.subExprs() }
        .filterIsInstance<TACExpr.Sym.Const>()
        .map { it.s.value }
        .filter { it >= Config.MaxBaseStorageSlot.get() }
        .toSet()

    /**
     * Maps variables that should be in the model to their [RuleChecker.TWOSTAGE_META_KEY] meta, that hold the
     * [CmdPointer] of their assignments. In case variables are assigned multiple times, we properly aggregate the
     * [CmdPointer]s.
     */
    private val varMetas = core.analysisCache.graph.commands
        .map { it.cmd }
        .filterIsInstance<TACCmd.Simple.AssigningCmd>()
        .mapNotNull { it.lhs `to?` it.meta[TWOSTAGE_META_VARORIGIN]?.ptrs }
        .groupBy { it.first }
        .mapValues { (_,v) -> v.flatMap { it.second } }
        .filterValues { it.isNotEmpty() }
        .toMap()

    /**
     * This must be called whenever we create an [LExpression] from a [ToLExpression] (which typically is a [TACExpr]).
     * ([ToLExpression.Conv] as facilities to pass such a function.)
     *
     * This updates the set [termsOfInterest]. That set will be used to construct the `(get-value ...)` call to the smt
     * solver. This means that every term that we will want a value from the SMT solver for (in the SAT case), we have
     * to add to [termsOfInterest] here.
     *
     * This also sets up back translation for all these terms from SMT to TAC.
     *
     * Right now, terms of interest are all non-map variables plus for every term of interest `t` that has skey type, we
     * also add the term `from_skey(t)` to the terms of interest. This is helpful to get the values of skeys in Bit256
     * in any hashing scheme.
     *
     * (In the future we may want to support map variables here, either by giving `select`-terms here, or by adding the
     *  map variables themselves; in the latter case we'll also need to implement parsing of map models to TACValues
     *  (not that hard I think, we need to watch out for the sizes though).)
     */
    private fun addToTermsOI(tacExp: ToLExpression, lExp: LExpression) {
        fun fromSkeyTOILExp(tacExp: TACExpr, lExp: LExpression): LExpression? =
            if (tacExp.tagAssumeChecked != skeySort) {
                logger.warn { "attempted to apply `from_skey` to an LExpression ($lExp) that is not of skey type" }
                null
            } else {
                lxf {
                    applyExp(
                        NonSMTInterpretedFunctionSymbol.Hash.FromSkey,
                        lExp,
                        meta = MetaMap(META_MODEL_TOI to txf.fromSkey(tacExp))
                    )
                }
            }


        when (tacExp) {
            is TACExpr.QuantifiedFormula -> {
                tacExp.quantifiedVars.map { qVar ->
                    val skolemVarName = qVar.meta[SKOLEM_VAR_NAME]
                    if (skolemVarName != null) {
                        termsOfInterest +=
                            lxf.const(
                                skolemVarName,
                                qVar.tag,
                                MetaMap(META_MODEL_TOI to TACSymbol.Var(skolemVarName, qVar.tag).asSym())
                            )
                    }
                    termsOfInterest.remove(lxf.const(qVar.smtRep, qVar.tag, MetaMap(META_MODEL_TOI to qVar.asSym())))
                }
            }

            is TACExpr -> {
                if (lExp is LExpression.Identifier && !lExp.tag.isMapType()) {
                    lxf {
                        // attach [TWOSTAGE_META_KEY] meta if it is in [varMetas]
                        val withMeta = if (tacExp is TACExpr.Sym.Var && tacExp.s in varMetas) {
                            tacExp.copy(
                                s = tacExp.s.withMeta(
                                    TWOSTAGE_META_VARORIGIN,
                                    RuleChecker.CmdPointerList(varMetas[tacExp.s]!!)
                                )
                            )
                        } else {
                            tacExp
                        }
                        termsOfInterest += lExp.addMeta(META_MODEL_TOI, withMeta)
                    }
                    if (tacExp.tagAssumeChecked == skeySort) {
                        check(lExp.tag == skeySort) {
                            "the translation of an tac-skey (\"$tacExp\") should be an skey, got \"$lExp\""
                        }
                        fromSkeyTOILExp(tacExp, lExp)?.let { termsOfInterest += it }
                    }
                } else if (lExp is LExpression.Identifier && lExp.tag is Tag.ByteMap) {
                    // just trying bytemaps for starters .. should be possible to just add more terms of interest here
                    //  if we want other maps (wordmaps, ghost maps)
                    val meta = (tacExp as? TACExpr.Sym.Var)?.s?.meta
                    val isCVL = meta?.let { TACMeta.CVL_VAR in it } ?: false
                    val isCalldata = meta?.let { TACMeta.IS_CALLDATA in it } ?: false
                    val isHavocMe = meta?.let { TACMeta.TACSIMPLESIMPLE_HAVOCME in it } ?: false
                    if (isCVL || isCalldata || isHavocMe) {
                        lxf {
                            termsOfInterest += lExp.addMeta(META_MODEL_TOI, tacExp)
                        }
                    }
                } else if (tacExp.tag == skeySort) {
                    // make sure that we have a model for `from_skey` available, which is used in displaying
                    // skeys in call trace (we want to show them as numbers there, since it's the unified representation)
                    //
                    // NB we only consider hashes that we model as skeys (i.e. keccak, and hypothetically others that
                    // use large-gap hashing), hence the check for the skey sort

                    if (lExp.isApplyOf<NonSMTInterpretedFunctionSymbol.Hash.SimpleHashN>()) {
                        lExp.args.zip((tacExp as TACExpr.Apply).ops).forEach { (argLExp, argTacExp) ->
                            fromSkeyTOILExp(argTacExp, argLExp)?.let { termsOfInterest += it }
                        }
                    } else if (lExp.isApplyOf<NonSMTInterpretedFunctionSymbol.Hash.SkeyAdd>()) {
                        val argLExp = lExp.args.first()
                        val argTacExp = (tacExp as TACExpr.Apply).ops.first()
                        fromSkeyTOILExp(argTacExp, argLExp)?.let { termsOfInterest += it }
                    }
                }
            }
        }
    }


    private val edgeConditions: EdgeConditions = EdgeConditions(core.analysisCache.graph, lxf)
    private val reachVars: ReachVars =
        ReachVars(core.analysisCache.graph, lxf, core.symbolTable, toLExpAction = ::addToTermsOI)

    private val termsOfInterest: MutableSet<LExpression> = mutableSetOf()
    init {
        sanityCheckReachVars()
    }

    private fun sanityCheckReachVars() {
        check(core.analysisCache.graph.let { g ->
            /* Each remaining block is not a sink iff there are reachVar equations in which this block occurs */
            g.blocks.all {
                (it in g.sinkBlocks && reachVars.dependentReachVarEqsOf(it.id) == null) ||
                    (it !in g.sinkBlocks && reachVars.dependentReachVarEqsOf(it.id) != null)
            }
        }) {
            "the reachability variables show an incorrect structure."
        }
    }

    companion object {
        private const val REACHABILITY_CERTORA_PREFIX = "ReachabilityCertora"
        private const val OK_PREFIX = "OK_"

        /**
         * If this is true, we separate the encoding of control flow and of the semantics of each individual statement
         * by giving the statements top-level definitions in the formula.
         * This is useful for getting unsat cores that "talk" about individual statements.
         * The UNSAT core enumeration requires this feature.
         */
        // The getter is needed for tests - if multiple tests are run with different config options,
        // the value should reflect the config options set in a given test.
        val topLevelStatementDefinitions: Boolean
            get() = Config.UnsatCoreFriendlyLeino.get() ||
                (Config.CoverageInfoMode.get() != CoverageInfoEnum.NONE) ||
                Config.TimeoutCores.get()

        /** this is a configuration flag
         * If this is `false`, an ok-var definition looks like this:
         *   (= ok-var (=> cmd1 (=> cmd2 (=> cmd3 (and successor-ok-var1 successor-ok-var1)))))
         * If this is `true`, an ok-var definition looks roughly like this:
         *   (= ok-var (=> (and cmd1 cmd2 cmd3) (and successor-ok-var1 successor-ok-var1)))
         * (i.e. we apply the transformation `x => (y => z)` ~~> `(x /\ y) => z` when creating the LExpressions in `wp`)
         *
         * Both representation should be equivalent (also wrt solver runtime, but that's a bit hard to predict.)
         * */
        const val flatBlocks: Boolean = true

        // Note that for OK vars and reach vars we acting a bit as if they were actual TAC variables, even though they
        //  do not acutally occur in the TAC program itself but are inserted by Leino.
        // This should make things a bit easier, since the model (after back translation is in terms of
        //  [TACSymbol.Var]s).

        internal fun reachabilityIdentNameOf(b: NBId): String = "$REACHABILITY_CERTORA_PREFIX$b"
        fun genReachabilityVar(b: NBId): TACSymbol.Var =
            TACSymbol.Var(reachabilityIdentNameOf(b), Tag.Bool).withMeta(TACMeta.LEINO_REACH_VAR, b)

        private fun okIdentNameOf(b: NBId): String = "$OK_PREFIX$b"
        fun genOkIdentOf(b: NBId): TACSymbol.Var =
            TACSymbol.Var(okIdentNameOf(b), Tag.Bool).withMeta(TACMeta.LEINO_OK_VAR, b)
    }

    fun generateVCs(): LExpVC {
        val blockDefinitions = getOptimizedBlockDefinitions()
        val startNotOkay = core.analysisCache.graph.rootBlocks.map { it.id }.map {
            lxf.applyExp(
                TheoryFunctionSymbol.Unary.LNot,
                ToLExpression(genOkIdentOf(it).asSym(), lxf, tyScope, action = ::addToTermsOI)
            )
        }
        val reachability = getOptimizedReachabilityVarsEquations()
        return LeinoLExpVC(
            blockDefinitions = blockDefinitions,
            startNotOkay = startNotOkay,
            reachability = reachability,
            termsOfInterest = termsOfInterest,
            tacProgram = core,
            tacProgramMetadata = tacProgramMetadata,
            notLargeConstantsInCode = constantsFromFixedModel,
        )
    }

    private fun getOptimizedReachabilityVarsEquations(): List<LExpression> =
        (core.analysisCache.graph.blocks.map { it.id } + if (!InlineReachVarDefs.get()) {
            blocksWithReachVarsInUse
        } else {
            emptySet()
        }).toSet().map { reachVars.reachabilityVarEquationOf(it) }

    private fun findReachVarsInUse(): Set<NBId> =
        core.parallelLtacStream().flatMap {
            it.cmd.getFreeVarsOfRhs().stream()
        }.filter {
            it.meta.find(TACMeta.LEINO_REACH_VAR) != null
        }.map { it.meta.find(TACMeta.LEINO_REACH_VAR)!! }.collect(Collectors.toSet())

    private fun getOptimizedBlockDefinitions(): List<LExpression> {
        val reachVarsSubstitutions: Map<LExpression.Identifier, LExpression> =
            blocksWithReachVarsInUse.minus(core.analysisCache.graph.blocks.map { it.id })
                .map { reachVars.reachabilityVarSubstitutionOf(it) }.toMap()
        // The LExpression 'path_cond_TRUE = true' is contained in multiple tacBlocks, using just flatMap would
        // cause the result to contain all of them, therefore, we create a set first and then transform it to the list.
        return core.analysisCache.graph.blocks.flatMapToSet { tacBlock ->
            val wpVar = ToLExpression(genOkIdentOf(tacBlock.id).asSym(), lxf, tyScope, action = ::addToTermsOI)
            val blockEquation =
                getOptimizedBlockEquation(
                    reachVarsSubstitutions,
                    tacBlock
                )
            listOf(lxf { wpVar eq blockEquation.blockBody }) + blockEquation.definitions
        }.toList()
    }


    private fun getOptimizedBlockEquation(
        reachVarsSubstitutions: Map<LExpression.Identifier, LExpression>,
        tacBlock: TACBlock
    ): BlockBody {
        fun inlineReachVarDefs(exp: LExpression): LExpression =
            if (InlineReachVarDefs.get()) {
                exp.substitute(lxf, reachVarsSubstitutions)
            } else {
                exp
            }

        val definitions = mutableListOf<LExpression>()
        val baseCase = edgeConditions.edgeConditionsOf(tacBlock.id).map { (nxt, edgeCond) ->
            val okVarForWP = ToLExpression(genOkIdentOf(nxt).asSym(), lxf, tyScope, action = ::addToTermsOI)
            definitions.addAll(edgeCond.definitions.map { inlineReachVarDefs(it) })
            lxf.implies(edgeCond.blockBody, okVarForWP)
        }.let { successorOkVars -> lxf.and(successorOkVars) }

        val blockBody =
            tacBlock.commands.reversed().fold(BlockBody(baseCase, emptyList())) { acc, cmd ->
                wp(cmd, acc)
            }
        return blockBody.map(::inlineReachVarDefs).withDefinitions(definitions)
    }

    /** Does not handle assert commands. */
    private fun cmdAsLExp(lcmd: LTACCmd): LExpression {

        val cmdPtrMeta = MetaMap(META_CMD_PTR to lcmd.ptr)

        fun toLExpression(tacExpr: TACExpr) =
            ToLExpression(tacExpr, lxf, tyScope, action = ::addToTermsOI)

        return lxf {
            when (val cmd = lcmd.cmd) {
                // commands that are noops for our purposes
                is TACCmd.Simple.AssigningCmd.AssignHavocCmd,
                is TACCmd.Simple.LabelCmd,
                is TACCmd.Simple.JumpCmd,
                is TACCmd.Simple.JumpiCmd,
                TACCmd.Simple.NopCmd,
                is TACCmd.Simple.AnnotationCmd -> TRUE

                // "normal" commands, i.e., assigns and assumes
                is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
                    assignEq(
                        toLExpression(cmd.lhs.asSym()),
                        toLExpression(cmd.rhs)
                    )

                is TACCmd.Simple.AssumeCmd ->
                    toLExpression(cmd.cond.asSym())

                is TACCmd.Simple.AssumeExpCmd ->
                    toLExpression(cmd.cond)

                is TACCmd.Simple.AssumeNotCmd ->
                    !toLExpression(cmd.cond.asSym())

                // asserts
                is TACCmd.Simple.AssertCmd -> throw IllegalArgumentException(
                    "this method does not handle assert commands, received: \"$lcmd\""
                )

                else -> throw UnsupportedOperationException("$lcmd is not in TACSimpleSimple fragment!")
            }.transformPost(lxf) {
                it.addMeta(cmdPtrMeta)
            }
        }
    }

    private fun cmdPtrToId(ptr: CmdPointer): LExpression.Identifier =
        lxf.const("${ptr.block}_pos_${ptr.pos}", Tag.Bool, null)

    private fun wp(
        cmd: LTACCmd,
        curr: BlockBody,
    ): BlockBody {
        fun implies(l: LExpression, r: LExpression) =
            if (flatBlocks) {
                lxf.impliesFlatten(l, r)
            } else {
                lxf.implies(l, r)
            }

        return when (cmd.cmd) {
            is TACCmd.Simple.AssumeExpCmd,
            is TACCmd.Simple.AssumeCmd,
            is TACCmd.Simple.AssumeNotCmd,
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                val cmdAsLExp = cmdAsLExp(cmd)
                if (topLevelStatementDefinitions) {
                    val defId = cmdPtrToId(cmd.ptr)
                    val def = lxf { eq(defId, cmdAsLExp).addMeta(META_TOPLVL_CMD, cmd.ptr) }
                    curr.update(implies(defId, curr.blockBody), def)
                } else {
                    curr.update(implies(cmdAsLExp, curr.blockBody))
                }
            }

            TACCmd.Simple.NopCmd,
            is TACCmd.Simple.LabelCmd,
            is TACCmd.Simple.AnnotationCmd,
            is TACCmd.Simple.JumpCmd,
            is TACCmd.Simple.JumpiCmd,
            is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> {
                // note: it would be equivalent to handle this like the previous case; we'd just get a lot of
                //  (=> true ...) into the formula (unless optimizations clean it up)
                curr
            }

            // note/context:
            // in the Assert*Cmd cases, curr.blockBody will contain the jump conditions and
            //  references to the successor blocks rather than more commands
            //  see the definition of `baseCase` in `getOptimizedBlockEquation`
            is TACCmd.Simple.AssertCmd -> {
                val symAsLExp =
                    lxf {
                        ToLExpression(cmd.cmd.o.asSym(), lxf, tyScope, action = ::addToTermsOI)
                            .addMeta(META_CMD_PTR, cmd.ptr)
                    }
                // Note that we're making top level definitions here even though we're only defining symbols, since
                // without those definitions some unsat cores might be hard to understand (we may still optimize once
                // we realize something's not needed).
                if (topLevelStatementDefinitions) {
                    val defId = cmdPtrToId(cmd.ptr)
                    val meta = if (TACMeta.CVL_USER_DEFINED_ASSERT in cmd.cmd.meta) {
                        MetaMap(META_TOPLVL_CMD to cmd.ptr)
                    } else {
                        MetaMap(META_TOPLVL_CMD to cmd.ptr) + META_AUTO_GENERATED_ASSERT
                    }
                    val def = lxf { eq(defId, symAsLExp, meta) }
                    curr.update(lxf { defId and curr.blockBody }, def)
                } else {
                    curr.update(lxf { symAsLExp and curr.blockBody })
                }
            }

            else -> throw UnsupportedOperationException("$cmd is not in TACSimpleSimple fragment!")
        }.letIf(cmd.cmd is TACCmd.Simple.AssigningCmd && TWOSTAGE_META_MODEL in cmd.cmd.meta) { blk ->
            val logger = Logger(LoggerTypes.TWOSTAGE)
            val acmd = cmd.cmd as TACCmd.Simple.AssigningCmd
            val value = acmd.meta[TWOSTAGE_META_MODEL]!!

            val valAsInt = when (value) {
                is TACValue.PrimitiveValue -> value.asBigInt
                is TACValue.SKey.Basic -> value.offset.asBigInt
                else -> {
                    logger.warn { "Fixed model ${acmd.lhs} = ${value}, but the value is not primitive" }
                    return@letIf blk
                }
            }
            if (valAsInt !in existingLargeConstants) {
                // see [constantsFromFixedModel]
                constantsFromFixedModel.add(valAsInt)
            }
            val valAsExpr = when {
                // TACValue is properly encoded as SKey
                value is TACValue.SKey -> ToLExpression(value.asConstantTacExpr(), lxf, tyScope)
                // TACValue should be SKey but isn't, e.g., for plain injectivity encoding
                acmd.lhs.tag == skeySort -> lxf.applyExp(NonSMTInterpretedFunctionSymbol.Hash.ToSkey, ToLExpression(valAsInt.asTACExpr, lxf, tyScope))
                // TACValue is something normal
                value is ConcreteTacValue ->
                    value.asBigIntOrNull()
                        ?.let { lxf.lit(it, acmd.lhs.tag) }
                        ?: run {
                            logger.warn { "Fixed model ${acmd.lhs} = ${value}, but the value could not be turned into an expression" }
                            return@letIf blk
                        }

                else -> {
                    logger.warn { "Fixed model ${acmd.lhs} = ${value}, but the value could not be turned into an expression" }
                    return@letIf blk
                }
            }

            logger.debug { "Adding ${acmd.lhs.asSym()} == $valAsExpr to leino encoding" }
            blk.withDefinitions(listOf(
                lxf { eq(ToLExpression(acmd.lhs.asSym(), lxf, tyScope), valAsExpr) }
            ))
        }
    }
}
