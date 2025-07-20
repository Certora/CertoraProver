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

package analysis.split

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.commands
import analysis.elab
import analysis.patterns.Info
import analysis.patterns.PatternHelpers.exprView
import analysis.patterns.PatternHelpers.view
import analysis.patterns.get
import analysis.patterns.lhs
import analysis.split.SplitContext.Companion.isSimpleVar
import analysis.split.SplitContext.Companion.storageLoc
import analysis.split.Ternary.Companion.containedIn
import analysis.split.annotation.StorageSnippetInserter.Companion.applyAndAnnotEvmStorageSnippet
import analysis.split.arrays.PackedArrayRewriter
import analysis.split.arrays.PackedArrayRewriter.Companion.indexOfArrayAccess
import analysis.split.arrays.PackedArrayRewriter.Companion.newLocAndAuxCmds
import analysis.split.arrays.PackingInfoKey.*
import analysis.storage.StorageAnalysis.Base.Companion.toBase
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import datastructures.*
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import log.*
import scene.ContractId
import scene.ITACMethod
import tac.MetaKey
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import utils.ModZm.Companion.lowOnes
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder.Companion.UnfolderResult
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldToSingleVar
import java.math.BigInteger

/**
 * Does the actual rewriting of commands in terms of the splits identified by [SplitFinder].
 *
 * A technical note about no-op reads and writes.
 * When reading fields from a storage slot, solidity reads the whole slot and then extracts only the fields it is
 * interested in. In case of a write, solidity first reads the original slot, replaces parts of it with the new data
 * and then rewrites. The natural rewrite we get here are all these separate reads and writes, but actually some of these
 * are no-ops.
 *
 * That would not be a real problem, but hooks are activated on reads and writes, and these no-op operations should not
 * really be considered as such. We therefore remove them in two steps:
 * 1. We keep track of simple assignments of the form a:=b that are generated and in [assignSource] keep the actual
 *    storage read they start from (if there is one). Then, when we are about to generate a storage write, we first
 *    check if it is in fact writing back exactly what it read. In such a case we don't generate only this final write.
 * 2. Eventually Perform a very simple cone-of-influence sort of reduction in [cleanup], which removes the whole chain
 *    of assignments in the above case, and also reads of fields that went nowhere, because it was actually a read of
 *    another field in the same slot.
 *
 * One problem with step 1, is if the explicit solidity code is actually such a no-op write, i.e., a := a. In this case
 * we do want to keep the read and the write. So if all writes of a storage slot are no-ops, we check the position in
 * the block where the chain starts for each of them (if its outside the block, we consider it a real op). The read of
 * the no-op writes will be the last reads, as solidity always generates a read before a write, to read the remainder
 * slots.
 *
 * One must note that solc optimizations do stranger things and its near impossible to count reads. An example:
 *
 * uint64 x; uint64 y; uint64 z;
 *
 * function func1() public returns (uint128) {
 *    y = y;
 *    return x + y + z;
 * }
 *
 * Will read all three at once, unpack, repack, write it back, and use what it wrote (without reading again) to calculate.
 */
class SplitRewriter(
    private val cx: SplitContext,
    private val method: ITACMethod,
    private val varsToSplits: Map<TACSymbol.Var, Split>,
    private val pathsToSplit: Map<NonIndexedPath, Split>,
    private val arrayRewriter: PackedArrayRewriter.ForRewriter,
) {
    private val code = method.code as CoreTACProgram
    private fun toCommand(ptr: CmdPointer) = code.analysisCache.graph.toCommand(ptr)

    private val metas = StorageMetaInfo(cx)

    /** [PatchingTACProgram] isn't suited for our needs, as changes keep changing until reaching the final version. */
    private val changes = mutableMapOf<CmdPointer, List<TACCmd.Simple>>()

    data class Changes(
        val changes: Map<CmdPointer, List<TACCmd.Simple>>,
        val newStorageVars: MutableSet<TACSymbol.Var>,
        val newOtherVars: MutableSet<TACSymbol.Var>, )

    /**
     * Applying changes in [changes] to the [CoreTACProgram] [code], if there was no failure
     * in the previous storage analysis, and adding [SnippetCmd].StorageSnippet commands.
     */
    private fun applyChanges(): SimplePatchingProgram {
        val patcher = applyAndAnnotEvmStorageSnippet(Changes(changes, newStorageVars, newOtherVars), code, cx.contract.instanceId, cx.storageAnalysisFail)
        return patcher
    }

    private val ternaries = cx.ternaries(method)

    /** Extracts only the bits of [range] and shifts them all the way to the right */
    private infix fun Ternary.restrictTo(range: BitRange.NonEmpty) =
        (this shiftRight range.lowBit) and Ternary(lowOnes(range.width))

    /** gets the [Ternary] and restricts to the range, shifting */
    private fun TernaryCalculator.getLhs(p: CmdPointer, range: BitRange.NonEmpty) =
        this.getLhs(p) restrictTo range

    /** gets the [Ternary] and restricts to the range, shifting */
    private fun TernaryCalculator.getRhs(p: CmdPointer, e: TACExpr, range: BitRange.NonEmpty) =
        this.getRhs(p, e) restrictTo range

    private fun Ternary.asExpr(tag: Tag) = when (tag) {
        is Tag.Bits -> this.asConstant().asTACSymbol().asSym()
        is Tag.Bool -> this.asConstant().asBoolTACSymbol().asSym()
        else -> `impossible!`
    }


    private val newStorageVars = mutableSetOf<TACSymbol.Var>()
    private fun newStorageVar(v: TACSymbol.Var) {
        if (newStorageVars.add(v)) {
            Logger.regression {
                "Created a meta slot ${v.namePrefix}"
            }
        }
    }

    private val newOtherVars = mutableSetOf<TACSymbol.Var>()

    /**
     * returns the range in the [Split] of [s] that fits [range].
     *
     * In the case [s] is a constant there, then it returns [range] as is.
     * Otherwise, it finds the [BitRange] that represents this range. This is normally a range that starts at the same
     * place, and might have some high zeros that fill the rest of the range.
     *
     * However, there is one case: x := y >> 10 (for example), and for some reason x cannot be split. Then, when
     * starting with y's lower range, shifted back up, the fitting range will not start at 10, but at 0.
     *
     * In any case, the invariant is that there should be only one range of [s] that intersects [range] and that is
     * the fitting range.
     */
    private fun fittingRange(s: TACSymbol, range: BitRange.NonEmpty) =
        when (s) {
            is TACSymbol.Const ->
                range

            is TACSymbol.Var ->
                (varsToSplits[s]!! fitsTo range) ?: range
        }

    private fun fittingRange(e: TACExpr, range: BitRange.NonEmpty) =
        fittingRange((e as TACExpr.Sym).s, range)


    /**
     * Creates a new variable that represents either the map of this [repPath] or a simple variable, according to
     * [SplitContext.canRewriteAsVar].
     * This function is used for all path variables except those handled in [newArrayPathVar].
     */
    private fun newStandardPathVar(repPath: NonIndexedPath, range: BitRange.NonEmpty): TACSymbol.Var {
        val equivClass = cx.pathEquivalence.getEquivalenceClass(repPath)
        return TACSymbol.Var(
            namePrefix = storageVarName(cx.contract.instanceId, repPath, equivClass.size, if(range == BitRange.all) {
                null
            } else {
                range.lowBit
            }),
            tag = if (cx.canRewriteAsVar(repPath)) {
                Tag.Bit256
            } else {
                Tag.WordMap
            },
            meta = metas.pathVarMeta(repPath, range, equivClass)
        ).also {
            newStorageVar(it)
        }
    }

    companion object {
        val STORAGE_SPLITTER_DISCREPANCY = MetaKey.Nothing("tac.storage.split.discrepancy")

        /**
         * Returns the "canonical" name for a split storage variable in contract [contractId] whose representative
         * non-indexed bath is [repPath]. [equivClassSize] is the size of the equivalence class for which [repPath]
         * is the representative element; it is included in the name for debuggability. Finally [splitStart], if non-null,
         * gives the start location within the storage word from which this storage location was unpacked.
         */
        fun storageVarName(contractId: ContractId, repPath: NonIndexedPath, equivClassSize: Int, splitStart: Int?) : String {
            return "tac${repPath.storageBase().prefixChar}!${contractId.toString(16)}!" +
                if (SplitContext.canRewriteAsVar(repPath, equivClassSize)) {
                    "${repPath.slot}"
                } else {
                    "$repPath"
                } +
                // if it's equivalent to other paths, we hint at it in the name.
                if (equivClassSize == 1) { "" } else { "!Equiv$equivClassSize" } +
                if (splitStart == null) { "" } else { "!$splitStart" }
        }
    }

    /** Creates a new variable representing a packed array - which we unpack */
    private fun newArrayPathVar(repPath: NonIndexedPath, width: Int) =
        TACSymbol.Var(
            namePrefix = "tac${repPath.storageBase().prefixChar}!${cx.contract.instanceId.toString(16)}!$repPath!W$width",
            tag = Tag.WordMap,
            meta = metas.pathVarMeta(repPath, BitRange.NonEmpty(0, width), cx.pathEquivalence.getEquivalenceClass(repPath))
        ).also {
            newStorageVar(it)
        }

    /**
     * In case of just one range which starts at zero, we keep the original variable. We don't check that the
     * top bits are 0, because if we excluded those cases we couldn't keep the name if the top bits are unused garbage.
     */
    private fun keepOriginalVar(v: TACSymbol.Var) =
        varsToSplits[v]!!.singleOrNull()?.lowBit == 0

    /** Creates a new variable that represents a partial bit-range of [v] */
    private fun newVar(v: TACSymbol.Var, _range: BitRange.NonEmpty): TACSymbol.Var {
        if (keepOriginalVar(v)) {
            return v
        }
        val range = fittingRange(v, _range)
        check(range != BitRange.all) {
            "Why create a new var if the range is everything?"
        }
        check(v.tag == Tag.Bit256) {
            "Can't split variable $v, because it is of type ${v.tag}"
        }
        return TACSymbol.Var(
            namePrefix = "${v.namePrefix}!$range",
            tag = Tag.Bit256,
            callIndex = v.callIndex,
            meta = v.meta
        ).also {
            newOtherVars.add(it)
        }
    }

    /** Takes [e] and restricts it to [range]. If it is a variable, may return a new variable */
    private fun newRhsSym(ptr: CmdPointer, e: TACSymbol, range: BitRange.NonEmpty) =
        when (e) {
            is TACSymbol.Const ->
                (Ternary(e.value) restrictTo range).asExpr(e.tag)

            is TACSymbol.Var -> {
                val t = ternaries.getRhs(ptr, e.asSym(), range)
                if (t.isConstant()) {
                    t.asExpr(e.tag)
                } else {
                    newVar(e, range).asSym()
                }
            }
        }

    /** We don't expect nested rhs expressions and so fall back to not changing anything in this case */
    private fun newRhsSym(ptr: CmdPointer, e: TACExpr, range: BitRange.NonEmpty) =
        if (e is TACExpr.Sym) {
            newRhsSym(ptr, e.s, range)
        } else {
            e
        }

    /**
     * For each block, we keep for each var the original storage path that it holds, as long as we can get
     * this through simple assigns only (a := b). See explanation in class description.
     */
    private val assignSource =
        mutableNestedMapOf<NBId, TACSymbol.Var, Pair<CmdPointer, BitRange>>()

    private fun assignCmd(
        ptr: CmdPointer,
        lhs: TACSymbol.Var,
        rhs: TACExpr,
        meta: MetaMap = MetaMap()
    ): TACCmd.Simple.AssigningCmd.AssignExpCmd {
        if (rhs is TACExpr.Sym.Var) {
            assignSource[ptr.block, rhs.s]?.let {
                assignSource[ptr.block, lhs] = it
            }
        }
        return TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs, rhs, meta)
    }


    /**
     * Entry point.
     *
     * Returns the resulting [SimplePatchingProgram], not yet applied, the set of changes (mainly for debugging), and
     * the set of new storage variables.
     */
    fun rewrite(): Triple<SimplePatchingProgram, Map<CmdPointer, List<TACCmd.Simple>>, Set<TACSymbol.Var>> {

        rewriteNonConstIndexArrayAccesses()
        method.commands.forEach { lcmd ->
            if (lcmd.ptr in changes) {
                // cmds that were already changed via the ArrayRewriter should not be considered.
                return@forEach
            }
            when (val cmd = lcmd.cmd) {
                is TACCmd.Simple.AssigningCmd.WordLoad ->
                    if (cx.isStorage(cmd.base) && cx.storagePaths(cmd)?.isEmpty() != true) {
                        rewriteStorageAccess(lcmd)
                    }

                is TACCmd.Simple.WordStore ->
                    if (cx.isStorage(cmd.base) && cx.storagePaths(cmd)?.isEmpty() != true) {
                        rewriteStorageAccess(lcmd)
                    }

                is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
                    if (isSimpleVar(cmd.lhs)) {
                        handleAssign(lcmd.ptr, cmd)
                    }

                else ->
                    Unit
            }
        }
        cleanup()
        if (!cx.storageAnalysisFail) {
           checkSafety()
        }
        val patcher = applyChanges()
        return Triple(patcher, changes, newStorageVars)
    }

    /**
     * Replaces [lcmd] with copies of itself, one for each range in the split of the [NonIndexedPath].
     * See explanation at class description for the reason of the whole mess of computing the ranges to rewrite.
     */
    private fun rewriteStorageAccess(lcmd: LTACCmd) {
        val (ptr, cmd) = lcmd
        val repPath = cx.representativePath(cmd) ?: return
        val split = pathsToSplit[repPath] ?: Split.all

        val ranges =
            if (cmd is TACCmd.Simple.WordStore && split.size > 1) {
                // Pairs of <range, ptr> where the current write is just a writing back of some previous read.
                val noopWritesWithPtrs =
                    split.mapNotNull { range ->
                        (newRhsSym(ptr, cmd.value, range).s as? TACSymbol.Var)?.let { value ->
                            // the value being written to this range
                            assignSource[ptr.block, value]?.let { (p, r) ->
                                // non null means it goes all the way to a storage load
                                val c = toCommand(p) as TACCmd.Simple.AssigningCmd.WordLoad
                                c.loc.toAccessPath(c.base.meta.toBase())
                                    ?.let { origPath -> // the indexed access path there
                                        (range to p).takeIf {
                                            // if its the same as this access path, and in this block then its a no-op.
                                            range == r && origPath == cmd.loc.toAccessPath(cmd.base.meta.toBase()) && ptr.block == p.block
                                        }
                                    }
                            }
                        }
                    }

                val noopWrites = noopWritesWithPtrs.map { it.first }
                if (noopWrites == split.backing) {
                    // All are no-op? so those that are not latest are actually explicit a:=a operations. We keep them.
                    val lastPtr = noopWritesWithPtrs.maxOf { it.second.pos }
                    noopWritesWithPtrs
                        .filterNot { it.second.pos == lastPtr }
                        .map { it.first }
                        .ifEmpty { // Code is a:=a; b:=b. this will happen in optimized compilation.
                            split.backing // we keep everything.
                        }
                } else {
                    split.backing.filter { it !in noopWrites }
                }
            } else {
                split.backing
            }

        /** handles all the accounting for const index array accesses, the non-const ones are already gone */
        val arrayAccess = arrayRewriter.constIndexRewriters[method, lcmd]

        val newIndexCmds = mutableListOf<TACCmd.Simple>()

        val shouldHaveBeenSplit = cx.layout.expectedSplit(repPath).let {
            it.size > 1 && it != split
        }

        val newCmds = ranges.map { range ->
            val pathVar =
                if (arrayRewriter.arrayWidths.isArray(repPath)) {
                    check(arrayAccess != null)
                    check(arrayRewriter.arrayWidths.widthOf(repPath) == range.width)
                    newArrayPathVar(repPath, range.width)
                } else {
                    check(arrayAccess == null)
                    newStandardPathVar(repPath, range)
                }.letIf(shouldHaveBeenSplit) {
                    it.withMeta(STORAGE_SPLITTER_DISCREPANCY)
                }

            /**
             * If this is a packed array access, creates the new command that calculates the index using the logical
             * index instead of the physical one.
             * It returns the new location, and adds meta information to it.
             */
            fun getLoc(oldLoc: TACSymbol) =
                arrayAccess?.let {
                    val unfolderResult = it.newLocAndAuxCmdsForConstAccess(range)
                    newIndexCmds += unfolderResult.cmds
                    newOtherVars += unfolderResult.newVars
                    metas.addLocationMeta(unfolderResult.e.s, BitRange.NonEmpty(0, range.width))
                } ?: metas.addLocationMeta(oldLoc, range)

            when (cmd) {
                is TACCmd.Simple.WordStore -> {
                    val value = newRhsSym(ptr, cmd.value, range).s
                    if (cx.canRewriteAsVar(repPath)) {
                        assignCmd(
                            ptr = ptr,
                            lhs = metas.addLocationMeta(pathVar, range) as TACSymbol.Var,
                            rhs = value.asSym(),
                            meta = cmd.meta
                        )
                    } else {
                        TACCmd.Simple.WordStore(
                            loc = getLoc(cmd.loc),
                            base = pathVar,
                            value = value,
                            meta = cmd.meta
                        )
                    }
                }

                is TACCmd.Simple.AssigningCmd.WordLoad -> {
                    val lhs = newVar(cmd.lhs, range)
                    assignSource[ptr.block, lhs] = ptr to range
                    if (cx.canRewriteAsVar(repPath)) {
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = lhs,
                            rhs = metas.addLocationMeta(pathVar, range).asSym(),
                            meta = cmd.meta
                        )
                    } else {
                        TACCmd.Simple.AssigningCmd.WordLoad(
                            lhs = lhs,
                            loc = getLoc(cmd.loc),
                            base = pathVar,
                            meta = cmd.meta
                        )
                    }
                }

                else ->
                    `impossible!`
            }
        }

        changes[ptr] = newIndexCmds + newCmds
    }

    private fun toTACSymbol(n: BigInteger, tag: Tag) =
        when (tag) {
            Tag.Bool ->
                when (n) {
                    BigInteger.ZERO, BigInteger.ONE -> n.asBoolTACSymbol()
                    else -> `impossible!`
                }

            Tag.Bit256 -> n.asTACSymbol()
            else -> `impossible!`
        }

    private fun handleAssign(ptr: CmdPointer, cmd: TACCmd.Simple.AssigningCmd.AssignExpCmd) {
        val lhs = cmd.lhs
        val splits = varsToSplits[lhs] ?: Split.none
        val newCmds = mutableListOf<TACCmd.Simple>()

        if (splits == Split.none) {
            val t = ternaries.getLhs(ptr)
            if (t.isConstant()) {
                // Split.None doesn't mean it's not needed in the case of constants.
                // (we could have inlined this and so it really won't be needed).
                val newVal = toTACSymbol(t.asConstant(), lhs.tag)
                newCmds.add(
                    assignCmd(ptr, lhs, newVal.asSym(), cmd.meta)
                )
            }
            // If it's not a constant, then cmd gets erased.
        }

        for (range in splits) {
            val newLhs = newVar(lhs, range)
            val ternary = ternaries.getLhs(ptr, range)

            if (ternary.isConstant()) {
                val newVal = toTACSymbol(ternary.asConstant(), lhs.tag)
                newCmds.add(
                    assignCmd(ptr, newLhs, newVal.asSym(), cmd.meta)
                )
                continue
            }

            /** This can't be used for shifts, because the range of the new rhs vars there is different */
            fun newSym(e: TACExpr) =
                if (e is TACExpr.Sym) {
                    newRhsSym(ptr, e.s, range)
                } else {
                    e
                }

            /**
             * We will basically do no replacement if the rhs has nested expressions.
             * So this is handy for checking that all [exprs] are symbols, and otherwise returns null.
             */
            fun <T> areSymbolsOtherwiseNull(vararg exprs: TACExpr, ok: () -> T) =
                if (exprs.all { it is TACExpr.Sym }) {
                    ok()
                } else {
                    null
                }

            /**
             * A bitwise-or expression of [o1] and [o2] but restricted to the current [range], and only if it
             * can be simplified to be [o1] or to be [o2]. Otherwise, returns null.
             * In that case the caller can rewrite it like the original command, an OR or an ADD.
             */
            fun simplifiedOrOf(o1: TACExpr, o2: TACExpr) =
                areSymbolsOtherwiseNull(o1, o2) {
                    val s1 = newSym(o1)
                    val s2 = newSym(o2)
                    when {
                        s1.getAsConst() == BigInteger.ZERO -> s2
                        s2.getAsConst() == BigInteger.ZERO -> s1
                        else -> null
                    }
                }

            /** Similar to simplifiedOrOf, but for bitwise-and. Used for AND and MOD by a power of 2 */
            fun simplifiedAndOf(o1: TACExpr, o2: TACExpr) =
                areSymbolsOtherwiseNull(o1, o2) {
                    /** For this to be a non-op, one operand should be a 1-mask for the relevant range of second */
                    fun isNonOp(e: TACExpr, mask: TACExpr) =
                        fittingRange(e, range).let { liveRange ->
                            ternaries.getRhs(ptr, mask, liveRange).let { t ->
                                t is Ternary.NonBottom && (lowOnes(liveRange.width) containedIn t.ones)
                            }
                        }
                    when {
                        isNonOp(o1, mask = o2) -> newSym(o1)
                        isNonOp(o2, mask = o1) -> newSym(o2)
                        else -> null
                    }
                }

            /**
             * Same idea, but there are a number of cases here:
             *
             * The shift may cut away real high bits, and then we just want the lower bits of the original range. We
             * use a bitwise-and with an fffff mask, because if the original range was not [0,..] then shifting the new
             * var right will not cut these bits off.
             *
             * The shift may cut low bits. In this case, we just use the original shift.
             *
             * [reconstruct] is used when the original operation is actually needed, and cannot be removed.
             * In this case we'd like to keep the original operator: Mul, Div, Shift (though we could have just
             * standardized the whole thing).
             *
             * @param by is the shift that was called (so 10 means the original shift was a shift left of 10 bits)
             */
            fun shiftExprOf(by: Int, e: TACExpr, reconstruct: (TACExpr) -> TACExpr) =
                areSymbolsOtherwiseNull(e) {
                    val shifted = range shift -by
                    if (shifted is BitRange.Empty) {
                        BigInteger.ZERO.asTACSymbol().asSym()
                    } else {
                        val originalRange = fittingRange(e, shifted as BitRange.NonEmpty)
                        val rhsSym = newRhsSym(ptr, e, originalRange)
                        when {
                            by > 0 ->  // shift left
                                when {
                                    range.lowBit - by < 0 ->
                                        // added low zero bits
                                        reconstruct(rhsSym)

                                    originalRange.highBit + by > EVM_BITWIDTH256 ->
                                        // cut high (non-zero) bits.
                                        // For example, of operand range is 230-250, and by is 7, then 2 high bits are
                                        // cut off by this shift. So the shift left is replaced by bw-and with 18 low 1s.
                                        TACExpr.BinOp.BWAnd(
                                            o1 = rhsSym,
                                            // the subtracted expression is exactly the number of bits that were cut off
                                            o2 = lowOnes(originalRange.width - (originalRange.highBit + by - EVM_BITWIDTH256))
                                                .asTACSymbol().asSym()
                                        )

                                    else ->
                                        rhsSym
                                }

                            by < 0 -> { // shift right
                                if (originalRange.lowBit + by < 0) {
                                    // cut away low bits
                                    reconstruct(rhsSym)
                                } else {
                                    rhsSym
                                }
                            }

                            else ->
                                rhsSym
                        }
                    }
                }


            fun checkNoSplit() = run {
                check(splits.size == 1 && splits.first().lowBit == 0) {
                    "Expected no split in this case $ptr -- $cmd ($range out of $splits)"
                }
                null
            }

            // null means we will keep the original command.
            val newRhs: TACExpr? =
                when (val rhs = cmd.rhs) {

                    is TACExpr.Sym.Const ->
                        // assignments to forbidden variables can reach here, as their ternary is all X's no matter what.
                        null

                    is TACExpr.Sym.Var ->
                        newSym(rhs)

                    is TACExpr.UnaryExp.BWNot ->
                        rhs.copy(o = newSym(rhs.o))

                    is TACExpr.Vec ->
                        if (rhs.ls.size != 2) {
                            null
                        } else {
                            when (rhs) {
                                is TACExpr.Vec.Add ->
                                    simplifiedOrOf(rhs.o1, rhs.o2)
                                        ?: rhs.copy(ls = listOf(newSym(rhs.o1), newSym(rhs.o2)))

                                is TACExpr.Vec.Mul -> {
                                    val t1 = ternaries.getRhs(ptr, rhs.o1)
                                    val t2 = ternaries.getRhs(ptr, rhs.o2)
                                    when {
                                        t1.isPowOfTwo() ->
                                            shiftExprOf(t1.asConstant().lowestSetBit, rhs.o2) {
                                                rhs.copy(ls = listOf(t1.asExpr(Tag.Bit256), it))
                                            }

                                        t2.isPowOfTwo() ->
                                            shiftExprOf(t2.asConstant().lowestSetBit, rhs.o1) {
                                                rhs.copy(ls = listOf(it, t2.asExpr(Tag.Bit256)))
                                            }

                                        else ->
                                            checkNoSplit()
                                    }
                                }

                                else ->
                                    checkNoSplit()
                            }
                        }

                    is TACExpr.BinOp ->
                        when (rhs) {
                            is TACExpr.BinOp.BWAnd ->
                                simplifiedAndOf(rhs.o1, rhs.o2)
                                    ?: rhs.copy(o1 = newSym(rhs.o1), o2 = newSym(rhs.o2))


                            is TACExpr.BinOp.BWOr ->
                                simplifiedOrOf(rhs.o1, rhs.o2)
                                    ?: rhs.copy(o1 = newSym(rhs.o1), o2 = newSym(rhs.o2))

                            is TACExpr.BinOp.Div -> {
                                val t2 = ternaries.getRhs(ptr, rhs.o2)
                                if (t2.isPowOfTwo()) {
                                    shiftExprOf(-t2.asConstant().lowestSetBit, rhs.o1) {
                                        rhs.copy(o1 = it, o2 = t2.asExpr(Tag.Bit256))
                                    }
                                } else {
                                    checkNoSplit()
                                }
                            }

                            is TACExpr.BinOp.Mod -> {
                                val t2 = ternaries.getRhs(ptr, rhs.o2)
                                if (t2.isPowOfTwo()) {
                                    val mask = (t2.asConstant() - BigInteger.ONE).asTACSymbol().asSym()
                                    simplifiedAndOf(rhs.o1, mask)
                                        ?: rhs.copy(o1 = newSym(rhs.o1), o2 = rhs.o2)
                                } else {
                                    checkNoSplit()
                                }
                            }


                            is TACExpr.BinOp.ShiftRightLogical -> {
                                ternaries.getRhs(ptr, rhs.o2).asIntOrNull()?.let { by ->
                                    shiftExprOf(-by, rhs.o1) {
                                        rhs.copy(o1 = it, o2 = by.asTACSymbol().asSym())
                                    }
                                }
                            }

                            is TACExpr.BinOp.ShiftLeft -> {
                                ternaries.getRhs(ptr, rhs.o2).asIntOrNull()?.let { by ->
                                    shiftExprOf(by, rhs.o1) {
                                        rhs.copy(o1 = it, o2 = by.asTACSymbol().asSym())
                                    }
                                }
                            }

                            is TACExpr.BinOp.SignExtend -> {
                                val b = ternaries.getRhs(ptr, rhs.o1).asIntOrNull()
                                if (b != null) {
                                    val topBit = (b + 1) * 8
                                    when (val restricted = BitRange(range.lowBit, topBit)) {
                                        is BitRange.NonEmpty ->
                                            rhs.copy(o2 = newRhsSym(ptr, rhs.o2, restricted))

                                        is BitRange.Empty ->
                                            error("sign-extend from nothing? $ptr --- $cmd")
                                    }
                                } else {
                                    checkNoSplit()
                                }
                            }

                            // this can be thought of as a shift right followed by a sign-extend.
                            is TACExpr.BinOp.ShiftRightArithmetical ->
                                ternaries.getRhs(ptr, rhs.o2).asIntOrNull()?.let { by ->
                                    shiftExprOf(-by, rhs.o1) {
                                        // if we're here then it's not a clean sra, i.e., the shift cuts off bits of
                                        // the corresponding range of bits.
                                        rhs.copy(o1 = it, o2 = by.asTACSymbol().asSym())
                                    }?.let {
                                        when (it) {
                                            is TACExpr.BinOp.ShiftRightArithmetical -> it
                                            is TACExpr.Sym -> {
                                                // The sar perfectly isolated a piece of the rhs variable and returned
                                                // it. We then need to sign-extend it, that is the difference between
                                                // a shift-right and an arithmetical one.
                                                TACExpr.BinOp.SignExtend(
                                                    ((EVM_BITWIDTH256 - by) / 8 - 1).asTACExpr,
                                                    it,
                                                    Tag.Bit256
                                                )
                                            }
                                            else -> `impossible!`
                                        }
                                    }
                                }

                            else ->
                                null
                        }

                    is TACExpr.TernaryExp.Ite ->
                        when (ternaries.getRhs(ptr, rhs.i)) {
                            Ternary.one ->
                                newSym(rhs.t)

                            Ternary.zero ->
                                newSym(rhs.e)

                            else -> {
                                rhs.copy(t = newSym(rhs.t), e = newSym(rhs.e))
                            }
                        }

                    else ->
                        checkNoSplit()
                }

            newCmds.add(
                if (newRhs == null || (newLhs == lhs && newRhs == cmd.rhs)) {
                    cmd
                } else {
                    assignCmd(ptr, newLhs, newRhs, cmd.meta)
                }
            )
        }

        if (newCmds != listOf(cmd)) {
            changes[ptr] = newCmds
        }
    }

    /**
     * Remove superfluous storage reads, so that hooks are happy. See Class documentation above.
     */
    private fun cleanup() {
        val directAssignments = mutableMultiMapOf<TACSymbol.Var, TACSymbol.Var>()
        val candidates = mutableSetOf<TACSymbol.Var>()
        val usedVars = mutableSetOf<TACSymbol.Var>()

        fun markAll(cmd: TACCmd.Simple) {
            if (cmd is TACCmd.Simple.AssigningCmd) {
                usedVars.add(cmd.lhs)
            }
            usedVars.addAll(cmd.getFreeVarsOfRhs())
        }

        for ((ptr, originalCmd) in method.commands) {
            val cmds = changes[ptr]
            if (cmds == null) {
                // We are playing super safe here. Anything we didn't change probably shouldn't be removed.
                markAll(originalCmd)
                continue
            }
            for (cmd in cmds) {
                if (cmd is TACCmd.Simple.AssigningCmd) {
                    candidates.add(cmd.lhs)
                    if (cx.isForbiddenVar(cmd.lhs, method) || originalCmd is TACCmd.Simple.WordStore) {
                        // A store that turned into an assign. Hooks may read this, even if it's not used within the code.
                        usedVars.add(cmd.lhs)
                    }
                    when {
                        cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.rhs is TACExpr.Sym.Var ->
                            // The type of assignments we consider removing.
                            directAssignments.add(cmd.lhs, cmd.rhs.s)

                        cmd is TACCmd.Simple.AssigningCmd.WordLoad && cx.isStorage(cmd.base) ->
                            // The command itself may end up being removed, but we don't care for continuing removal
                            // recursively.
                            usedVars.addAll(cmd.getFreeVarsOfRhs())

                        else ->
                            markAll(cmd)
                    }
                } else {
                    markAll(cmd)
                }
            }
        }

        val queue = ArrayDeque(usedVars)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val next = (directAssignments[v] ?: continue).filter { it !in usedVars }
            usedVars.addAll(next)
            queue.addAll(next)
        }
        val unused = candidates - usedVars

        val originalChanges = changes.toMap()
        for ((ptr, cmds) in originalChanges) {
            changes[ptr] = cmds.filterNot { cmd ->
                cmd is TACCmd.Simple.AssigningCmd && cmd.lhs in unused
            }
        }
    }


    /**
     * Special handling for packed arrays. Rewrites non-const index array accesses. See [PackedArrayRewriter] for more
     * details.
     */
    private fun rewriteNonConstIndexArrayAccesses() {
        arrayRewriter.nonConstReads[method]
            ?.forEach { rewriteNonConstArrayLoad(it) }
        arrayRewriter.nonConstWrites[method]
            ?.forEach { rewriteNonConstArrayStore(it) }
    }

    data class ArrayRewriteData(
        val newCmds : List<TACCmd.Simple.AssigningCmd>,
        val newLoc : TACSymbol.Var,
        val newPathVar : TACSymbol.Var
    )

    /** The common code of the two array rewrite functions */
    private fun prepareArrayRewrite(info: Info, cmd: TACCmd.Simple): ArrayRewriteData? {
        val path = cx.representativePath(cmd) ?: return null
        val oldLoc = cmd.storageLoc as TACSymbol.Var

        /* The new logical index */
        val unfolderResult1 =
            if (cmd.indexOfArrayAccess == info.lhs(PHYSICAL_INDEX_CMD)) {
                // we also need to see that not only the variables are the same, but they also have the same meaning,
                // but indexOfArrayAccess is from the actual storage access command, and PHYSICAL_INDEX_CMD assignment
                // is checked to be still valid at that point via `checkQuery` of `PackedArrayRewriter`.
                UnfolderResult(info[LOGICAL_INDEX]!!.symbol.asSym(), listOf())
            } else {
                // This happens for static arrays only (as far as I've noticed), and even then it is rare.
                unfoldToSingleVar("logicalIndex", TACExprFactUntyped {
                    Add(
                        Mul(cmd.indexOfArrayAccess!!.asSym(), info[PER_SLOT]!!.asTACExpr),
                        info.lhs(INDEX_WITHIN_SLOT_CMD).asSym()
                    )
                })
            }

        val unfolderResult2 = newLocAndAuxCmds(
            oldLoc = oldLoc,
            oldIndex = cmd.indexOfArrayAccess!!,
            newIndex = unfolderResult1.e.s
        )

        newOtherVars += unfolderResult1.newVars + unfolderResult2.newVars

        return ArrayRewriteData(
            newCmds = unfolderResult1.cmds + unfolderResult2.cmds,
            newLoc = unfolderResult2.e.s,
            newPathVar = newArrayPathVar(path, info[BITWIDTH]!!)
        )
    }


    private fun rewriteNonConstArrayLoad(info: Info) {
        val readCmd = info.view<TACCmd.Simple.AssigningCmd.WordLoad>(READ_CMD)
        val p = prepareArrayRewrite(info, readCmd.cmd) ?: return
        changes[readCmd.ptr] =
            p.newCmds + readCmd.cmd.copy(loc = p.newLoc, base = p.newPathVar)

        // this must be erased, otherwise it confuses the rewriter, as it has a non-trivial split that is
        // not consistent.
        changes[info[DIV_CMD]!!.ptr] = emptyList()

        val lastCmd = info.exprView<TACExpr>(FINAL_LOAD_CMD)
        val readValue = readCmd.cmd.lhs.asSym()
        changes[lastCmd.ptr] = listOf(
            when (val rhs = lastCmd.exp) {
                is TACExpr.BinOp.BWAnd -> // no need for the bwAnd
                    lastCmd.cmd.copy(rhs = readValue)

                is TACExpr.BinOp.SignExtend ->
                    lastCmd.cmd.copy(rhs = rhs.copy(o2 = readValue))

                is TACExpr.BinOp.ShiftLeft ->
                    lastCmd.cmd.copy(rhs = rhs.copy(o1 = readValue))

                is TACExpr.Vec.Mul ->
                    lastCmd.cmd.copy(
                        rhs = rhs.copy(
                            ls = listOf(readCmd.cmd.lhs.asSym(), info[MUL_SHIFT]!!.asTACSymbol().asSym())
                        )
                    )

                else ->
                    `impossible!`
            }
        )
    }


    private fun rewriteNonConstArrayStore(info: Info) {
        val storeCmd = info.view<TACCmd.Simple.WordStore>(STORE_CMD)
        val p = prepareArrayRewrite(info, storeCmd.cmd) ?: return

        changes[info[READ_CMD]!!.ptr] = emptyList()
        changes[info[AND_WITH_READ]!!.ptr] = emptyList()
        changes[info[LAST_OR]!!.ptr] = emptyList()

        changes[storeCmd.ptr] = p.newCmds +
            storeCmd.cmd.copy(
                loc = p.newLoc,
                base = p.newPathVar,
                value = when {
                    CONST_VALUE in info ->
                        info[CONST_VALUE]!!.asTACSymbol()

                    // happens only in IR as far as we know. There, the value is sign-extended, then shifted
                    // and then masked. When rewriting, we just need the masked value.
                    SIGN_EXTEND in info -> {
                        val seCmd = info.exprView<TACExpr.BinOp.SignExtend>(VALUE_CMD)
                        changes[seCmd.ptr] = listOf(
                            seCmd.cmd.copy(rhs = TACExpr.BinOp.BWAnd(info[MASK]!!.asTACExpr, seCmd.exp.o2))
                        )
                        seCmd.lhs
                    }

                    VALUE_CMD in info -> {
                        val valueCmd = info.exprView<TACExpr>(VALUE_CMD)
                        changes -= valueCmd.ptr // We actually need this line in the tac, so we don't erase it.
                        valueCmd.lhs
                    }

                    BOOL_VALUE_CMD in info -> {
                        val iteCmd = info.exprView<TACExpr.TernaryExp.Ite>(BOOL_VALUE_CMD)
                        changes[iteCmd.ptr] = listOf(
                            iteCmd.cmd.copy(rhs = iteCmd.exp.copy(t = 1.asTACSymbol().asSym()))
                        )
                        iteCmd.lhs
                    }

                    else -> `impossible!`
                }
            )

    }


    /**
     * This checks that any variable definition we erased is not used anywhere in the new code (as if [changes] applied).
     * This may actually fail if the intermediate vars in patterns we are detecting (e.g. in [PackedArrayRewriter])
     * are used in places outside the pattern.
     */
    private fun checkSafety() {
        val erasedVars = mutableSetOf<TACSymbol.Var>()
        for ((oldCmdPtr, newCmds) in changes) {
            method.elab(oldCmdPtr).cmd.getLhs()?.let { lhs ->
                if (newCmds.all { it.getLhs() != lhs })
                    erasedVars += lhs
            }
        }

        fun c(cmd: TACCmd.Simple) =
            check(!(erasedVars containsAny cmd.getFreeVarsOfRhs())) {
                "Something went wrong with split rewriter - $cmd in $method uses variables it erased."
            }

        method.commands.parallelStream().forEach { lcmd ->
            changes[lcmd.ptr]
                ?.forEach { c(it) }
                ?: c(lcmd.cmd)
        }
    }
}
