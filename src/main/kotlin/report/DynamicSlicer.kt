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

package report

import analysis.CmdPointer
import analysis.LTACCmdView
import analysis.PathCondition
import analysis.TACCommandGraph
import analysis.dataflow.*
import analysis.narrow
import evm.EVM_BITWIDTH256
import evm.EVM_MOD_GROUP256
import evm.MAX_EVM_INT256
import evm.MAX_EVM_UINT256
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.CVLTestGenerator
import tac.NBId
import tac.Tag
import vc.data.*
import vc.data.tacexprutil.DefaultTACExprTransformer
import java.math.BigInteger

/**
 * Keeps the search results returned by [DynamicSlicer.findValueExpr].
 */
private sealed class StoreMatchResult {

    /**
     * Given a [TACExpr.Select] expression, Select(base=W_k, loc=i), this class represents the case where a matching
     * Store(W_l, loc=j, value=[expr]) command has been found at [where], and therefore
     * Select(base=W_k, loc=i) = [expr] and [locValue] = modelValuationOf(i) = modelValuationOf(j).
     */
    data class FoundValueExpr(val expr: TACExpr, val locValue: BigInteger, val where: CmdPointer) : StoreMatchResult()

    /**
     * Given a [TACExpr.Select] expression, S = Select(base=W_k, loc=i), this class represents the case where S evaluates
     * to a havoc'd value. That is, no matching Store expression with location [locValue] has been found,
     * and instead, we have found that [havocdBase] is havoc'd at [where].
     */
    data class HavocdBase(val havocdBase: TACSymbol.Var, val locValue: BigInteger, val where: CmdPointer) :
        StoreMatchResult()

    /**
     * Given a [TACExpr.Select] expression, represent the case where a matching store search
     * has failed due to unexpected/unsupported assign commands' patterns.
     */
    object SearchFailed : StoreMatchResult()
}

/**
 * Used to propagate the search results of [DynamicSlicer.analyzeBoolExpr].
 */
private data class NextBoolExprAnalysisStep(
    val nextDefSite: CmdPointer?,
    val nextPolarity: Boolean?,
    val newJumpiCmd: LTACCmdView<TACCmd.Simple.JumpiCmd>?
) {
    companion object {
        /**
         * Represents that the stopping criterion of the search in [DynamicSlicer.analyzeBoolExpr] is met.
         */
        val Stop = NextBoolExprAnalysisStep(null, null, null)
    }
}

/**
 * Encapsulated the final result returned by [DynamicSlicer.analyzeBoolExpr].
 */
private data class BoolCondAnalysisResults(
    val resolvedExpr: TACExpr,
    val polarity: Boolean,
    val where: CmdPointer,
    val jumpiCmd: LTACCmdView<TACCmd.Simple.JumpiCmd>?
) {
    val resolvedExprWithPolarity: TACExpr
        get() = if (polarity) {
            when (resolvedExpr.tagAssumeChecked) {
                Tag.Bit256, Tag.Int -> {
                    TACExpr.BinRel.Gt(resolvedExpr, TACSymbol.lift(BigInteger.ZERO).asSym())
                }
                Tag.Bool -> {
                    resolvedExpr
                }
                else -> {
                    throw UnsupportedOperationException(
                        "Cannot handle the resulting TAC expression $resolvedExpr"
                    )
                }
            }
        } else {
            when (resolvedExpr.tagAssumeChecked) {
                Tag.Bit256, Tag.Int -> TACExpr.BinRel.Eq(resolvedExpr, TACSymbol.lift(BigInteger.ZERO).asSym())
                Tag.Bool -> {
                    if (resolvedExpr is TACExpr.UnaryExp.LNot) {
                        resolvedExpr.o
                    } else {
                        TACExpr.UnaryExp.LNot(resolvedExpr)
                    }
                }
                else -> {
                    throw UnsupportedOperationException(
                        "Cannot handle the resulting TAC expression $resolvedExpr"
                    )
                }
            }
        }
}

class DynamicSlicer(
    program: CoreTACProgram,
    private val model: CounterexampleModel,
    val scene: ISceneIdentifiers
) {

    private val g: TACCommandGraph = program.analysisCache.graph
    private val def: IAllVariablesDefAnalysis = AllVariablesDefAnalysis(g)
    private val topoSortedReachableBlocks: List<NBId> = program.topoSortFw.filter { it in model.reachableNBIds }
    private val cvlVars: Set<TACSymbol.Var> = CVLTestGenerator.getCVLLocalVars(program)

    private fun reachablePredOf(blockId: NBId): NBId? =
        g.pred(blockId).filter { it in topoSortedReachableBlocks }.let { reachablePreds ->
            when (reachablePreds.size) {
                0 -> null
                1 -> reachablePreds.first()
                else -> {
                    /** Determine the actual predecessor in the reachable path via the topological order.
                    * NOTE: We can have multiple predecessors in the reachable path if we have, e.g.,
                    * the following path: A --> B --> C, where we also have an edge A --> C in [g].
                    * This edge is not taken in the [model] (i.e., by the counter-example), while it
                    * links between two blocks in the path. As a result, C has two reachable predecessors: A and B,
                    * but we should pick B.
                    */
                    reachablePreds.maxByOrNull { topoSortedReachableBlocks.indexOf(it) }
                }
            }
        }

    private fun IAllVariablesDefAnalysis.reachableDefSiteOf(v: TACSymbol.Var, ptr: CmdPointer): CmdPointer =
        this.defSitesOf(v, ptr).filter { it.block in topoSortedReachableBlocks }.let { reachableDefSites ->
            when (reachableDefSites.size) {
                1 -> {
                    reachableDefSites.first()
                }
                else -> {
                    throw IllegalStateException(
                        "Expected to find exactly one reachable definition site for $v, but found $reachableDefSites"
                    )
                }
            }
        }

    private fun thenIsTakenOrNull(iteExpr: TACExpr.TernaryExp.Ite): Boolean? {
        val iteIfCond: TACSymbol? = (iteExpr.i as? TACExpr.Sym)?.s
        return if (iteIfCond != null) {
            model.valueAsBoolean(iteIfCond).leftOrNull()
        } else {
            null
        }
    }

    private fun retrieveTakenMapBaseOrNull(iteExpr: TACExpr.TernaryExp.Ite): TACSymbol.Var? =
        thenIsTakenOrNull(iteExpr)?.let { thenIsTaken ->
            val takenExpr: TACExpr = if (thenIsTaken) {
                iteExpr.t
            } else {
                iteExpr.e
            }
            when (takenExpr) {
                is TACExpr.Sym.Var -> {
                    takenExpr.s
                }
                is TACExpr.TernaryExp.Ite -> {
                    retrieveTakenMapBaseOrNull(takenExpr)
                }
                else -> {
                    null
                }
            }
        }

    /**
     * Given [select], tries to find the [TACExpr] of the value
     * stored at [select.base][`select.loc`].
     */
    private fun findValueExpr(select: TACExpr.Select, where: CmdPointer): StoreMatchResult {
        if (select.base !is TACExpr.Sym.Var || select.loc !is TACExpr.Sym) {
            return StoreMatchResult.SearchFailed
        }
        val selectLocValue = model.valueAsBigInteger(select.loc.s).leftOrNull()
            ?: return StoreMatchResult.SearchFailed
        return matchStore(select.base.s, selectLocValue, where)
    }

    private fun matchStore(base: TACSymbol.Var, selectLocValue: BigInteger, where: CmdPointer): StoreMatchResult {
        val baseDefPtr = def.reachableDefSiteOf(base, where)
        val defCmd: TACCmd.Simple.AssigningCmd =
            g.elab(baseDefPtr).cmd as? TACCmd.Simple.AssigningCmd ?: return StoreMatchResult.SearchFailed

        return if (defCmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            when (val rhs = defCmd.rhs) {
                is TACExpr.Store -> {
                    if (rhs.base !is TACExpr.Sym.Var || rhs.loc !is TACExpr.Sym) {
                        StoreMatchResult.SearchFailed
                    } else {
                        val storeLocValue = model.valueAsBigInteger(rhs.loc.s).leftOrNull()
                        if (storeLocValue == null) {
                            StoreMatchResult.SearchFailed
                        } else if (storeLocValue == selectLocValue) {
                            // matches the store
                            StoreMatchResult.FoundValueExpr(rhs.value, selectLocValue, baseDefPtr)
                        } else {
                            // go to base
                            matchStore(rhs.base.s, selectLocValue, baseDefPtr)
                        }
                    }
                }
                is TACExpr.LongStore -> {
                    if (rhs.dstOffset !is TACExpr.Sym || rhs.length !is TACExpr.Sym) {
                        StoreMatchResult.SearchFailed
                    } else {
                        val dstOffsetValue = model.valueAsBigInteger(rhs.dstOffset.s).leftOrNull()
                        val lenValue = model.valueAsBigInteger(rhs.length.s).leftOrNull()
                        if (dstOffsetValue == null || lenValue == null) {
                            StoreMatchResult.SearchFailed
                        } else {
                            if (dstOffsetValue <= selectLocValue && selectLocValue < (dstOffsetValue+lenValue)) {
                                // matches the store
                                if (rhs.srcMap !is TACExpr.Sym.Var || rhs.srcOffset !is TACExpr.Sym) {
                                    StoreMatchResult.SearchFailed
                                } else {
                                    val srcOffsetValue = model.valueAsBigInteger(rhs.srcOffset.s).leftOrNull()
                                    if (srcOffsetValue == null) {
                                        StoreMatchResult.SearchFailed
                                    } else {
                                        // the relative starting point in `dstMap` relative to `dstOffsetValue` is
                                        // `selectLocValue-dstOffsetValue`, and this is added to `srcOffsetValue`
                                        // to get the matching index in `srcMap`.
                                        matchStore(rhs.srcMap.s, srcOffsetValue+(selectLocValue-dstOffsetValue), baseDefPtr)
                                    }
                                }
                            } else {
                                // go to base
                                if (rhs.dstMap !is TACExpr.Sym.Var) {
                                    StoreMatchResult.SearchFailed
                                } else {
                                    matchStore(rhs.dstMap.s, selectLocValue, baseDefPtr)
                                }
                            }
                        }
                    }
                }
                is TACExpr.TernaryExp.Ite -> {
                    retrieveTakenMapBaseOrNull(rhs)?.let { takenBase ->
                        matchStore(takenBase, selectLocValue, baseDefPtr)
                    } ?: StoreMatchResult.SearchFailed
                }
                else -> {
                    StoreMatchResult.SearchFailed
                }
            }
        } else if (defCmd is TACCmd.Simple.AssigningCmd.AssignHavocCmd) {
            StoreMatchResult.HavocdBase(base, selectLocValue, baseDefPtr)
        } else {
            StoreMatchResult.SearchFailed
        }
    }

    inner class DefSubstitutor(val where: CmdPointer): DefaultTACExprTransformer() {

        override fun transformVar(exp: TACExpr.Sym.Var): TACExpr {
            if (exp.s in cvlVars) {
                val cvlVarWithMeta = cvlVars.first { it == exp.s }
                return try {
                    cvlVarWithMeta.meta[TACMeta.CVL_DISPLAY_NAME]
                        ?.let { cvlName ->
                            exp.s.copy(namePrefix = cvlName).asSym()
                        } ?: exp
                } catch (_: IllegalArgumentException) {
                    exp
                }
            }
            val varDefPtr = def.reachableDefSiteOf(exp.s, where)

            /**
             * Definition is in the same expression as usage.
             * Happens in [TACExpr.QuantifiedFormula].
             */
            if (varDefPtr == where) {
                return exp
            }

            return when (val defCmd = g.elab(varDefPtr).cmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    DefSubstitutor(varDefPtr).transform(defCmd.rhs)
                }
                is TACCmd.Simple.AssigningCmd -> {
                    exp
                }
                else -> {
                    throw IllegalStateException(
                        "Expected to find an assigning cmd as the definition site of ${exp.s}, but got ${
                            g.elab(
                                varDefPtr
                            )
                        }"
                    )
                }
            }
        }

        override fun transformIte(e: TACExpr.TernaryExp.Ite): TACExpr {
            return thenIsTakenOrNull(e)?.let { thenIsTaken ->
                if (thenIsTaken) {
                    transform(e.t)
                } else {
                    transform(e.e)
                }
            } ?: super.transformIte(e)
        }

        override fun transformConst(exp: TACExpr.Sym.Const): TACExpr {

            fun twoToThePowerOf(power: Int) =
                TACExpr.BinOp.IntExponent(TACSymbol.lift(2).asSym(), TACSymbol.lift(power).asSym())

            return when (exp.s.value) {
                EVM_MOD_GROUP256 -> twoToThePowerOf(EVM_BITWIDTH256)
                MAX_EVM_UINT256 -> TACExpr.BinOp.IntSub(twoToThePowerOf(EVM_BITWIDTH256), TACSymbol.lift(1).asSym())
                MAX_EVM_INT256 -> TACExpr.BinOp.IntSub(
                    twoToThePowerOf(EVM_BITWIDTH256 - 1),
                    TACSymbol.lift(1).asSym()
                )
                else -> {
                    exp
                }
            }
        }

        private fun transformVec(e: TACExpr.Vec, vecConstructor: (List<TACExpr>) -> TACExpr): TACExpr =
            e.ls.map { super.transform(it) }.filterNot {
                it is TACExpr.Sym.Const && it.s.value == e.initValue
            }.let { transformedLs ->
                when (transformedLs.size) {
                    0 -> TACSymbol.lift(e.initValue).asSym()
                    1 -> transformedLs.first()
                    else -> vecConstructor(transformedLs)
                }
            }

        override fun transformVecAdd(e: TACExpr.Vec.Add): TACExpr = transformVec(e) { l -> e.copy(ls = l) }
        override fun transformVecIntAdd(e: TACExpr.Vec.IntAdd): TACExpr = transformVec(e) { l -> e.copy(ls = l) }
        override fun transformVecMul(e: TACExpr.Vec.Mul): TACExpr = transformVec(e) { l -> e.copy(ls = l) }
        override fun transformVecIntMul(e: TACExpr.Vec.IntMul): TACExpr = transformVec(e) { l -> e.copy(ls = l) }

        override fun transformBWAnd(e: TACExpr.BinOp.BWAnd): TACExpr {
            fun isCastMask(mask: BigInteger): Int? =
                if (mask.signum() >= 0 && IntRange(0, mask.bitLength() - 1).all { mask.testBit(it) }) {
                    mask.bitLength()
                } else {
                    null
                }

            fun isBytesResetMask(mask: BigInteger): List<Pair<Int, Int>> =
                if (mask.signum() >= 0 && mask.bitLength() <= EVM_BITWIDTH256) {
                    val resetRanges: MutableList<Pair<Int, Int>> = mutableListOf()
                    var currBitIdx = EVM_BITWIDTH256 - 1
                    var resetTo = EVM_BITWIDTH256 - 1
                    var inResetRange = false
                    while (currBitIdx >= 0) {
                        if (!mask.testBit(currBitIdx) && !inResetRange) {
                            inResetRange = true
                            resetTo = currBitIdx
                        } else if (mask.testBit(currBitIdx) && inResetRange) {
                            inResetRange = false
                            if ((resetTo - currBitIdx) % 8 == 0) {
                                resetRanges.add((currBitIdx + 1) to resetTo)
                            }
                        } else if (currBitIdx == 0 && inResetRange) {
                            inResetRange = false
                            if ((resetTo + 1) % 8 == 0) {
                                resetRanges.add(currBitIdx to resetTo)
                            }
                        }
                        currBitIdx--
                    }
                    resetRanges
                } else {
                    emptyList()
                }

            val o1Tag = e.o1.tag
            val o2Tag = e.o2.tag

            return if (o1Tag == Tag.Bit256 &&
                o2Tag == Tag.Bit256
            ) {
                val (constOp, otherOp) = if (e.o1 is TACExpr.Sym.Const) {
                    e.o1 to e.o2
                } else if (e.o2 is TACExpr.Sym.Const) {
                    e.o2 to e.o1
                } else {
                    null to null
                }
                if (constOp == null) {
                    super.transformBWAnd(e)
                } else {
                    check(otherOp != null)
                    val castingTo = isCastMask(constOp.s.value)
                    val resetBytes = isBytesResetMask(constOp.s.value)
                    if (castingTo != null && castingTo <= EVM_BITWIDTH256 && castingTo % 8 == 0) {
                        TACExpr.Apply(
                            TACExpr.TACFunctionSym.Adhoc("${castingTo}LSBitsOf"),
                            listOf(transform(otherOp)),
                            Tag.Bit256
                        )
                    } else if (resetBytes.isNotEmpty()) {
                        resetBytes.fold(transform(otherOp)) { ACC, resetRange ->
                            TACExpr.Apply(
                                TACExpr.TACFunctionSym.Adhoc("resetBits${resetRange.first}-${resetRange.second}"),
                                listOf(ACC),
                                Tag.Bit256
                            )
                        }
                    } else {
                        super.transformBWAnd(e)
                    }
                }
            } else {
                super.transformBWAnd(e)
            }
        }


        override fun transformSelect(e: TACExpr.Select): TACExpr {
            return when (val res = findValueExpr(e, where)) {
                is StoreMatchResult.FoundValueExpr -> {
                    DefSubstitutor(res.where).transform(res.expr)
                }
                is StoreMatchResult.HavocdBase -> {
                    val transformedLoc: TACExpr = transform(e.loc)
                    e.copy(
                        base = res.havocdBase.asSym(),
                        loc = if ((transformedLoc as? TACExpr.Sym.Var)?.s?.let { it in cvlVars } == true) {
                            val cvlVarWithMeta = cvlVars.first { it == transformedLoc.s }
                            cvlVarWithMeta.meta[TACMeta.CVL_DISPLAY_NAME]
                                ?.let { cvlName ->
                                    transformedLoc.s.copy(namePrefix = cvlName).asSym()
                                } ?: transformedLoc
                        } else {
                            if (e.base.tag == Tag.ByteMap) {
                                // do not convert to contract addresses when reading a bytemap offset,
                                // as contract addresses cannot be keys in a bytemap
                                transformedLoc
                            } else {
                                scene.getContractOrNull(res.locValue)?.let {
                                    TACExpr.Apply(
                                        TACExpr.TACFunctionSym.Adhoc("addressOf"),
                                        listOf(TACSymbol.Var(it.name, Tag.Bit256).asSym()),
                                        Tag.Bit256
                                    )
                                }
                                    ?: transformedLoc
                            }
                        }
                    )
                }
                is StoreMatchResult.SearchFailed -> {
                    e
                }
            }
        }
    }

    /**
     * Finds the "actual" definition site of the Boolean assert condition [currBoolExpr].
     * This definition site is the first one where the definition does not have the form
     * "b", "!b", or "b==0". If, at some point, the search reaches a definition of a Boolean constant
     * (i.e., "true" or "false", or negation thereof), it is "restarted" by finding the first jumpi command
     * whose destination is this definition site. The search then proceeds on the Boolean condition of this jumpi command.
     */
    private fun analyzeBoolExpr(
        currBoolExpr: TACExpr,
        currPolarity: Boolean,
        where: CmdPointer,
        currJumpiCmd: LTACCmdView<TACCmd.Simple.JumpiCmd>?
    ): BoolCondAnalysisResults {
        val (nextDefSite, nextPolarity, newJumpiCmd) = if (currBoolExpr is TACExpr.Sym.Const ||
            (currBoolExpr is TACExpr.UnaryExp.LNot && currBoolExpr.o is TACExpr.Sym.Const)
        ) {
            blockIsConditionalOn(where.block) ?: NextBoolExprAnalysisStep.Stop
        } else {
            when {
                currBoolExpr is TACExpr.Sym.Var -> { // b = b'
                    NextBoolExprAnalysisStep(def.reachableDefSiteOf(currBoolExpr.s, where), true, null)
                }
                currBoolExpr is TACExpr.UnaryExp.LNot && (currBoolExpr.o is TACExpr.Sym.Var) -> { // b = !b'
                    NextBoolExprAnalysisStep(def.reachableDefSiteOf(currBoolExpr.o.s, where), false, null)
                }
                currBoolExpr is TACExpr.BinRel.Eq -> { // b = (b' == 0)
                    if (currBoolExpr.o1 is TACExpr.Sym.Const && currBoolExpr.o1.s.value == BigInteger.ZERO &&
                        currBoolExpr.o2 is TACExpr.Sym.Var
                    ) {
                        NextBoolExprAnalysisStep(def.reachableDefSiteOf(currBoolExpr.o2.s, where), false, null)
                    } else if (currBoolExpr.o2 is TACExpr.Sym.Const && currBoolExpr.o2.s.value == BigInteger.ZERO &&
                        currBoolExpr.o1 is TACExpr.Sym.Var
                    ) {
                        NextBoolExprAnalysisStep(def.reachableDefSiteOf(currBoolExpr.o1.s, where), false, null)
                    } else {
                        NextBoolExprAnalysisStep.Stop
                    }
                }
                currBoolExpr is TACExpr.TernaryExp.Ite -> {
                    if (currBoolExpr.i is TACExpr.Sym.Var) {
                        if (currBoolExpr.t is TACExpr.Sym.Const && currBoolExpr.t.s.value == BigInteger.ONE &&
                            currBoolExpr.e is TACExpr.Sym.Const && currBoolExpr.e.s.value == BigInteger.ZERO
                        ) {
                            // Casting from bool to bv256
                            NextBoolExprAnalysisStep(def.reachableDefSiteOf(currBoolExpr.i.s, where), true, null)
                        } else if (currBoolExpr.t is TACExpr.Sym.Const && currBoolExpr.t.s.value == BigInteger.ZERO &&
                            currBoolExpr.e is TACExpr.Sym.Const && currBoolExpr.e.s.value == BigInteger.ONE
                        ) { // Casting from bool to bv256, with negation
                            NextBoolExprAnalysisStep(def.reachableDefSiteOf(currBoolExpr.i.s, where), false, null)
                        } else {
                            NextBoolExprAnalysisStep.Stop
                        }
                    } else {
                        NextBoolExprAnalysisStep.Stop
                    }
                }
                else -> {
                    NextBoolExprAnalysisStep.Stop
                }
            }.let {
                it.copy(nextPolarity = it.nextPolarity?.let { polarity ->
                    if (polarity) {
                        currPolarity
                    } else {
                        !currPolarity
                    }
                }
                )
            }
        }
        check(nextDefSite == null || nextPolarity != null)
        return if (nextDefSite != null) {
            val nextLtac = g.elab(nextDefSite)
            val nextAssignCmd: TACCmd.Simple.AssigningCmd =
                nextLtac.cmd as? TACCmd.Simple.AssigningCmd ?: throw IllegalStateException(
                    "Expected an assigning command, but got ${nextLtac.cmd}"
                )
            when (nextAssignCmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    analyzeBoolExpr(nextAssignCmd.rhs, nextPolarity!!, nextDefSite,
                        newJumpiCmd ?: currJumpiCmd)
                }
                is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> {
                    BoolCondAnalysisResults(currBoolExpr, currPolarity, where, currJumpiCmd)
                }
                else -> {
                    throw IllegalStateException(
                        "Expected an assigning command in the TACSimpleSimple fragment, but got $nextLtac"
                    )
                }
            }
        } else {
            BoolCondAnalysisResults(currBoolExpr, currPolarity, where, currJumpiCmd)
        }
    }

    /**
     * Finds the definition site of the condition of the jumpi command whose immediate jump destination is
     * either [block] or an ancestor of [block] that eventually reaches [block] via one or more unconditional jumps.
     * Returns null if no such jumpi command is found.
     */
    private fun blockIsConditionalOn(block: NBId): NextBoolExprAnalysisStep? {
        val reachablePred = reachablePredOf(block) ?: return null
        return when (val pathCond = g.pathConditionsOf(reachablePred)[block]) {
            null -> {
                throw IllegalStateException(
                    "Expected to find the path condition of the edge $reachablePred --> $block"
                )
            }
            is PathCondition.EqZero, is PathCondition.NonZero -> {
                val lastCmdOfPred = g.elab(reachablePred).commands.last()
                check(lastCmdOfPred.cmd is TACCmd.Simple.JumpiCmd && lastCmdOfPred.cmd.cond is TACSymbol.Var)
                NextBoolExprAnalysisStep(
                    def.reachableDefSiteOf(
                        lastCmdOfPred.cmd.cond,
                        lastCmdOfPred.ptr
                    ), (pathCond !is PathCondition.EqZero), lastCmdOfPred.narrow()
                )
            }
            is PathCondition.TRUE -> {
                blockIsConditionalOn(reachablePred)
            }
            else -> throw UnsupportedOperationException(
                "Cannot handle the path condition $pathCond of the edge $reachablePred --> $block"
            )
        }
    }

    /**
     * Returns a dynamic slice of the violated assert condition [violatedAssertCond].
     */
    fun sliceViolatedAssertCond(violatedAssertCond: TACExpr, where: CmdPointer): DynamicSlicerResults {
        val analyzedCondResults = analyzeBoolExpr(
            violatedAssertCond, true, where, null
        )
        return DynamicSlicerResults(
            DefSubstitutor(analyzedCondResults.where).transform(
                analyzedCondResults.resolvedExprWithPolarity
            ), analyzedCondResults.jumpiCmd
        )
    }

    fun sliceRevertCond(revertAnnotation: LTACCmdView<TACCmd.Simple.AnnotationCmd>): DynamicSlicerResults {
        if (revertAnnotation.cmd.annot.k != TACMeta.REVERT_PATH) {
            throw UnsupportedOperationException(
                "Can only handle ${TACMeta.REVERT_PATH} annotation commands, but got ${
                    revertAnnotation.cmd.annot
                } @ ${revertAnnotation.ptr}"
            )
        }
        return sliceViolatedAssertCond(TACSymbol.lift(true).asSym(), revertAnnotation.ptr)
    }


}
