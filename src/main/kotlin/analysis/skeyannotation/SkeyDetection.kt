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

@file:Suppress("DEPRECATION")

package analysis.skeyannotation

import analysis.*
import datastructures.stdcollections.*
import evm.MAX_EVM_UINT256
import log.*
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGeneratorPlainInjectivity
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import java.math.BigInteger


internal val logger = Logger(LoggerTypes.SKEY_DETECTION)

@Suppress("unused")
private const val debugMode = false // should be off on master, since it triggers expensive checks

/**
 * @param isSkey if true, the associated expression will have skey type
 */
@JvmInline
value class SkeyInfo(val isSkey: Boolean) {
    infix fun join(other: SkeyInfo): SkeyInfo {
        return SkeyInfo(isSkey = this.isSkey || other.isSkey)

    }

    companion object {
        val IsSkey = SkeyInfo(isSkey = true)
        val Bottom = SkeyInfo(isSkey = false)
    }
}

/**
 * Data flow analysis to find out which expressions should flip their type from [Tag.Bit256] to [skeySort].
 *
 * (skey is short for -- "storage key", i.e., an identifier of a storage location)
 *
 * This basically works like a taint analysis.
 *  - hash function outputs are marked as skeys
 *  - additions of non-skeys and skeys are marked as skeys
 *  - variables that are assigned an skey are marked as skeys
 *  - hash function inputs are marked as "must be converted to an skey" if they're not already marked as skeys
 *  - indexes of storage lookups are marked as "must be converted to an skey" if they're not already marked as skeys
 *
 *  [AnnotateSkeyBifs] uses the information collected here to transform the program.
 */
class SkeyDetection private constructor(tacCommandGraph: TACCommandGraph) : ExpDataflowAnalysis<SkeyInfo>(
    tacCommandGraph,
    object : JoinLattice<SkeyInfo> {
        override fun join(x: SkeyInfo, y: SkeyInfo): SkeyInfo = x join y
        override fun equiv(x: SkeyInfo, y: SkeyInfo): Boolean = x == y
    },
    SkeyInfo.Bottom,
    ExpDirection.FORWARD
) {

    private val assignments = mutableSetOf<LTACCmdView<TACCmd.Simple.AssigningCmd>>()

    /** contains all the variables in the program that have skey type according to the [SkeyDetection]. */
    val skeyVars: Set<TACSymbol.Var> get() = skeyVarsMut
    private val skeyVarsMut = mutableSetOf<TACSymbol.Var>()

    init {
        runAnalysis()
    }

    val result = run {
        SkeyDetectionResult.Full(
            graph = tacCommandGraph,
            expOut = expOut,
            skeyVars = skeyVars,
        )
    }

    /**
     * deals with forward flow of skeys
     *  - hashes generate
     *  - additions propagate
     *  - variables propagate (corresponding to data flow from assignments)
     *
     * (see also documentation of [ExpDataflowAnalysis.stepExp])
     */
    override fun stepExp(inState: List<SkeyInfo>, exp: LTACExp): SkeyInfo {
        return when (val e = exp.exp) {
            is TACExpr.SimpleHash -> {
                // we only mark things as skeys here if they require the full "injectivity large gaps" property
                if (e.hashFamily.requiresLargeGaps) {
                    SkeyInfo.IsSkey
                } else {
                    SkeyInfo.Bottom
                }
            }

            is TACExpr.Sym.Var -> {
                check(inState.size <= 1)
                if (inState.isNotEmpty() && inState.first().isSkey) {
                    skeyVarsMut.add(e.s)
                }
                inState.firstOrNull() ?: bottom
            }

            is TACExpr.Vec.Add,
            is TACExpr.Vec.IntAdd -> {
                e as TACExpr.Vec
                val isSkey = inState.filter { it.isSkey }
                if (isSkey.isNotEmpty()) {
                    check(isSkey.size == 1)
                    { "should not happen: addition of more than one skey: $e" }
                    SkeyInfo.IsSkey
                } else {
                    // none of the operands is an skey
                    bottom
                }
            }

            is TACExpr.BinOp.Sub,
            is TACExpr.BinOp.IntSub -> {
                when {
                    inState[0].isSkey && !inState[1].isSkey -> {
                        SkeyInfo.IsSkey
                    }
                    else -> {
                        // In these cases, we model things as a scalar subtraction, and, if needed convert skeys via
                        // the `from_skey` function.
                        // In the case where two skeys are involved, we assume that someone basically wants to subtract
                        // two skeys with the same base hash; either to compare them (checking the difference for
                        // equality to 0), or to compute the difference of some array offsets.
                        // In the case where o1 is a scalar and o2 is an skey, the only reasonable explanations (I can
                        // think of) is an equality check, or that it's due to a deficiency in the analysis.
                        // If neither operand is an skey we're seeing just a regular subtraction.
                        bottom
                    }
                }
            }

            is TACExpr.Store -> {
                bottom
            }

            is TACExpr.BinRel.Eq -> {
                bottom
            }

            is TACExpr.BinOp.ShiftLeft,
            is TACExpr.BinOp.ShiftRightArithmetical,
            is TACExpr.BinOp.ShiftRightLogical -> {
                e as TACExpr.BinOp
                if (inState[0].isSkey && e.o2.evalAsConst() == BigInteger.ZERO) {
                    error("the shift operation \"$e\" is a noop -- the DropBwNops optimization should have " +
                        "eliminated it before this point")
                } else {
                    bottom
                }
            }

            is TACExpr.BinOp.BWAnd -> {
                if (inState.any { it.isSkey }) {
                    when (MAX_EVM_UINT256) {
                        e.o1.evalAsConst(),
                        e.o2.evalAsConst() ->
                            error("the bwand \"$e\" is a noop -- the DropBwNops optimization should have eliminated " +
                                "it before this point")

                        else -> {
                            bottom
                        }
                    }
                } else {
                    bottom
                }
            }

            is TACExpr.TernaryExp.Ite -> {
                /*
                 * Generally, `Ite`s are handled like control flow joins -- [ExpDataFlowAnalysis] triggers a join on the
                 * branches' results.
                 * In addition, we update the to_skey/from_skey conversions accordingly here.
                 *
                 * Furthermore, there is a trick here to deal with certain getters (for mappings that return a struct)
                 * that produce `Ite`s which look like they are skeys, but really aren't.
                 *
                 * If a (possibly nested) Ite has a `Select` in at least one of its branches, we make sure that the
                 * whole Ite is not considered an skey. If there are skeys in other branches (e.g. hashing results), we
                 * convert those using `from_skey`.
                 *
                 * Note that this is intentionally local. It bypasses the analysis infrastructure a bit, and it
                 * does some double work when looking for the `Select`s in case of a nested Ite. The rationale is that
                 * this problem of the weird Ites is a local one and we shouldn't build a global solution around a local
                 * problem.
                 */
                /**
                 * True if this Ite has a `Select` expression in any of its branches (dives into subexpressions, does
                 * not follow assignments).
                 */
                fun hasSelectInBranches(): Boolean {
                    val worklist = ArrayDeque<ExpPointer>()
                    worklist.add(exp.ptr)
                    while (worklist.isNotEmpty()) {
                        val currPtr = worklist.removeFirst()
                        when (graph.elab(currPtr).exp) {
                            is TACExpr.TernaryExp.Ite -> {
                                worklist.add(currPtr.extend(1))
                                worklist.add(currPtr.extend(2))
                            }

                            is TACExpr.Select -> return true
                            else -> Unit /* noop */
                        }
                    }
                    return false
                }

                val mustNotBeSkey = hasSelectInBranches()
                val join = super.handleIte(inState)
                SkeyInfo(isSkey = join.isSkey && !mustNotBeSkey)
            }
            is TACExpr.Apply -> {
                if(e.f != TACBuiltInFunction.ToStorageKey.toTACFunctionSym()) {
                    bottom
                } else {
                    if (inState.any { it.isSkey }) {
                        logger.warn {
                            "Earlier pass in pipeline as to translate $e to an skey, but it already is"
                        }
                        bottom
                    } else {
                        SkeyInfo(isSkey = true)
                    }
                }
            }

            else -> {
                if (exp.exp !is TACExpr.Select &&
                    // based on experience, Lt and Gt are regularly used to express disequality, so we're filtering
                    // these warnings to reduce noise in the logs
                    exp.exp !is TACExpr.BinRel.Lt &&
                    exp.exp !is TACExpr.BinRel.Gt &&
                    inState.any { it.isSkey }
                ) {
                    logger.warn {
                        "One of the arguments of a TAC expression ($e) that is not a store, select, eq, lt, gt was " +
                                "marked as an skey"
                    }
                }
                bottom
            }
        }
    }

    override fun stepAssignment(rhsState: SkeyInfo, assignment: LTACCmdView<TACCmd.Simple.AssigningCmd>): SkeyInfo {
        if (rhsState.isSkey) {
            skeyVarsMut.add(assignment.cmd.lhs)
        }
        // updating toSkeyExpressions here does not always work, because skeyVarsMut may not yet have been updated..
        // thus it's done when creating the final result instead, using the assignments we collect here
        assignments.add(assignment)
        return super.stepAssignment(rhsState, assignment)
    }

    companion object {
        fun isStorageAccess(ptr: ExpPointer, graph: TACCommandGraph): Boolean {
            val exp = graph.elab(ptr)
            return when (val e = exp.exp) {
                is TACExpr.Select -> e.base.tag == Tag.WordMap
                is TACExpr.Store -> e.base.tag == Tag.WordMap
                is TACExpr.MultiDimStore -> e.base.tag == Tag.WordMap
                else -> false
            }
        }

        fun isStorageAccess(cmdPointer: CmdPointer, graph: TACCommandGraph): Boolean =
            when (val cmd = graph.elab(cmdPointer).cmd) {
                is TACCmd.Simple.WordStore,
                is TACCmd.Simple.AssigningCmd.WordLoad -> true

                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> when (val rhs = cmd.rhs) {
                    is TACExpr.Select -> rhs.base.tag == Tag.WordMap
                    is TACExpr.Store -> rhs.base.tag == Tag.WordMap
                    is TACExpr.MultiDimStore -> rhs.base.tag == Tag.WordMap
                    else -> false
                }

                else -> false
            }


        fun run(code: CoreTACProgram): SkeyDetectionResult {
            // Do a quick check first to see if there are *any* SimpleHash expressions that require large gaps.  Many
            // TAC programs do not need the full analysis.
            val anySkey = code.parallelLtacStream().anyMatch { (_, cmd) ->
                cmd.exprs().any { exp ->
                    exp.subs.any { sub ->
                        when (sub) {
                            is TACExpr.SimpleHash -> sub.hashFamily.requiresLargeGaps
                            is TACExpr.Apply -> when (sub.f) {
                                TACBuiltInFunction.ToStorageKey.toTACFunctionSym() -> true
                                else -> false
                            }
                            else -> false
                        }
                    }
                }
            }
            return if (anySkey) {
                SkeyDetection(code.analysisCache.graph).result
            } else {
                SkeyDetectionResult.None
            }
        }
    }
}


/**
 * The final result of the [SkeyDetection], used by [AnnotateSkeyBifs] as well as
 * [StorageHashAxiomGeneratorPlainInjectivity].
 */
sealed class SkeyDetectionResult {
    abstract val skeyVars: Set<TACSymbol.Var>
    abstract fun isSkey(exp: ExpPointer): Boolean

    data class Full(
        private val graph: TACCommandGraph,
        private val expOut: Map<ExpPointer, SkeyInfo>,
        override val skeyVars: Set<TACSymbol.Var>,
    ) : SkeyDetectionResult() {
        override fun isSkey(exp: ExpPointer): Boolean = getAnalysisResult(exp).isSkey

        fun getAnalysisResult(exp: ExpPointer): SkeyInfo = expOut[exp] ?: run {
                val ltacCmd = graph.elab(exp.cmdPointer)
                if (ltacCmd.cmd is TACCmd.Simple.AssigningCmd) {
                    logger.debug {
                        "Got no analysis result for expression pointer $exp (points to: exp ${graph.elab(exp)} in command $ltacCmd)."
                    }
                }
            SkeyInfo.Bottom
        }
    }

    object None : SkeyDetectionResult() {
        override val skeyVars: Set<TACSymbol.Var> = emptySet()
        override fun isSkey(exp: ExpPointer): Boolean = false
    }
}
