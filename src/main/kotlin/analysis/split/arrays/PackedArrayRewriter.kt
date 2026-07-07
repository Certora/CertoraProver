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

package analysis.split.arrays

import analysis.*
import analysis.patterns.Info
import analysis.patterns.Info.Companion.set
import analysis.patterns.InfoKey
import analysis.patterns.get
import analysis.split.BitRange
import analysis.split.SplitContext
import analysis.split.SplitContext.Companion.storageLoc
import analysis.split.SplitFinder
import analysis.split.SplitRewriter
import analysis.split.StorageLayoutHelper.ArrayInfo
import analysis.split.StorageMetaInfo.Companion.changeArrayIndex
import analysis.split.arrays.PackedArrayRewriter.Companion.indexOfArrayAccess
import analysis.split.arrays.PackedArrayRewriter.Companion.newLocAndAuxCmds
import analysis.split.arrays.PackedArrayRewriter.Companion.shouldHashBase
import analysis.split.arrays.PackingInfoKey.*
import analysis.storage.StorageAnalysis
import analysis.storage.StorageAnalysis.AnalysisPath
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import datastructures.NestedMap
import datastructures.mutableNestedMapOf
import datastructures.set
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import evm.EVM_WORD_SIZE
import log.*
import scene.ITACMethod
import tac.Tag
import utils.allSame
import utils.mapValuesNotNull
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACCmd.Simple.AssigningCmd.WordLoad
import vc.data.TACCmd.Simple.WordStore
import vc.data.TACMeta.ACCESS_PATHS
import vc.data.tacexprutil.ExprUnfolder.Companion.UnfolderResult
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldToSingleVar
import vc.data.tacexprutil.tempVar
import java.math.BigInteger

/**
 * Arrays of small elements, such as int32, uint71, bytes4, and boolean, are stored in solidity in packed form much
 * like structs. However, their access patterns are much more complicated so [SplitFinder] and [SplitRewriter] cannot
 * handle them directly.
 *
 * This class acts as an assistant to the above two, in that it uses pattern matching to detect such patterns, and passes
 * the relevant information to get into the above algorithms.
 *
 * It is important to note that [StorageAnalysis], while it does recognize packed arrays as arrays, it thinks of them
 * as arrays of **words**, so the elements are inferred to have the size of one word. We do use this information given
 * to help us eventually calculate the correct index.
 *
 * For [SplitFinder] it turns out that if the index accessed is a constant, then the
 * pattern is much like those of packed struct accesses and so [SplitFinder] manages. But in non-constant indices, the
 * (simplified) load pattern is:
 *
 * physicalIndex = logicalIndex / elementsPerSlot
 * slot = physicalIndex + sha3(base)
 * loadedSlot = read(slot)
 * indexWithinSlot = logicalIndex % elementsPerSlot
 * startBit = 0x100 ** (elementWidthInBytes * indexWithinSlot)
 * shifted = loadedSlot / startBit
 * final = mask & shifted
 *
 * For this pattern, [SplitFinder] would normally give up on the power calculation, but we know that startBit is just 1
 * bit that in one of a number of very specific places, and so the division does not really mix the bits of loadedSlot,
 * and in fact only says that loadedSlot maybe be cut off at some specific places. In other words, the division doesn't
 * make loadedSlot unsplittable, but rather makes it adhere to the split [0..bitWidth, bitWidth..bitWidth*2, etc].
 *
 * The store pattern is similar:
 *
 * physicalIndex = logicalIndex / elementsPerSlot
 * slot = physicalIndex + sha3(base)
 * loadedSlot = read(slot)
 * indexWithinSlot = logicalIndex % elementsPerSlot
 * startBit = 0x100 ** (elementWidthInBytes * indexWithinSlot)
 * --- up to here they are the same ---
 * remainder = loadedSlot & bwNot(mask * startBit)
 * shiftedVal = value * startBit
 * storedSlot = remainder | shiftedVal
 * store(slot, storedSlot)
 *
 * Where the mask is 0xf..f with a bit-width equal to that of an array element. Here we do the same type of
 * override, but of two commands, the bitwise-and for calculating the reminder, and the last bitwise-or.
 *
 * In [SplitRewriter], the commands in the patterns are basically removed, and replaced with much simpler ones,
 * which access the array in its unpacked form.
 */
@Suppress("LocalVariableName")
class PackedArrayRewriter(private val cx: SplitContext) {

    companion object {

        private val AnalysisPath.arrayBase: AnalysisPath.ArrayLikePath
            get() {
                require(
                    this is AnalysisPath.StructAccess &&
                        offset.bytes == BigInteger.ZERO &&
                        base is AnalysisPath.ArrayLikePath
                ) {
                    "arrayBase should only be called on a struct wrapper of an array, not on $this"
                }
                return base
            }

        val TACCmd.Simple.indexOfArrayAccess
            get() =
                (cmd.storageLoc as? TACSymbol.Var)
                    ?.meta?.get(ACCESS_PATHS)?.paths
                    ?.map { it.arrayBase }
                    ?.takeIf { arrays ->
                        arrays.allSame { it is AnalysisPath.StaticArrayAccess }
                    }
                    ?.map { it.index }
                    ?.distinct()
                    ?.singleOrNull()

        /**
         * Currently we say every static array's base should be hashed, to prevent collisions. This is to be on the
         * safe side, and can probably be relaxed.
         */
        fun shouldHashBase(accessLocation: TACSymbol.Var) =
            accessLocation.meta[ACCESS_PATHS]!!.paths.first().arrayBase is AnalysisPath.StaticArrayAccess


        /**
         * Returns the symbol for the expression: `[oldLoc] - [oldIndex] + [newIndex]`, with updated [ACCESS_PATHS] meta.
         * Will also hash the base (`[oldLoc] - [oldIndex]`) if necessary.
         */
        fun newLocAndAuxCmds(oldLoc: TACSymbol.Var, oldIndex: TACSymbol, newIndex: TACSymbol):
            UnfolderResult<TACExpr.Sym.Var> {
            val txf = TACExprFactTypeChecked(TACSymbolTable(oldLoc, oldIndex, newIndex))
            val newBaseCmd = AssignExpCmd(
                tempVar("newBase", Tag.Bit256),
                txf { Sub(oldLoc.asSym(), oldIndex.asSym()) }
            )
            val shaCmd =
                if (shouldHashBase(oldLoc)) {
                    // this is why we can't use the unfolder: the following is a cmd and not an expression.
                    TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                        tempVar("shaOfBase", Tag.Bit256),
                        EVM_WORD_SIZE.asTACSymbol(),
                        listOf(newBaseCmd.lhs)
                    )
                } else {
                    null
                }
            val newLocCmd = AssignExpCmd(
                tempVar("newLoc", Tag.Bit256, changeArrayIndex(oldLoc.meta, newIndex)),
                txf { Add((shaCmd ?: newBaseCmd).getLhs()!!.asSym(), newIndex.asSym()) }
            )
            val cmds = listOfNotNull(newBaseCmd, shaCmd, newLocCmd)
            return UnfolderResult(newLocCmd.lhs.asSym(), cmds)
        }
    }

    /**
     * Holds for the representative (in terms of [SplitContext.pathEquivalence]) of every packed array path
     * the width of the array.
     *
     * The below maps are calculated at the instantiation of the class. Later information may rule out some of
     * these arrays, and so these should be filtered via one of the access functions below.
     */
    private val arrayRepWidth: Map<NonIndexedPath, Int>
    private val nonConstReads: Map<ITACMethod, List<Info>>
    private val nonConstWrites: Map<ITACMethod, List<Info>>


    private fun nonConstReads(method: ITACMethod, badArrays: Set<NonIndexedPath>? = null) =
        nonConstReads[method]?.filterNot { it.isIn(badArrays) } ?: emptyList()

    private fun nonConstWrites(method: ITACMethod, badArrays: Set<NonIndexedPath>? = null) =
        nonConstWrites[method]?.filterNot { it.isIn(badArrays) } ?: emptyList()


    /** Is the path related to this [Info] in the set [arrays], null stands for emptySet */
    private fun Info.isIn(arrays: Set<NonIndexedPath>? = null) =
        cx.representativePath(this[READ_CMD]!!.cmd)?.let { p ->
            arrays?.contains(p) == true
        } == true

    private fun <R> query(method: ITACMethod, pattern: PatternMatcher.Pattern<R>) =
        PatternMatcher.compilePattern((method.code as CoreTACProgram).analysisCache.graph, pattern)

    val patterns = Patterns(cx)

    init {
        val arrayRepInfo = mutableMapOf<NonIndexedPath, ArrayInfo>()

        fun arrayRepInfo(cmd: TACCmd.Simple) = arrayRepInfo[cx.representativePath(cmd)]

        /** returns false if this resulted in a conflict in widths */
        fun joinArrayInfo(path: NonIndexedPath, newInfo: ArrayInfo): Boolean {
            val rep = cx.representativePath(path)
            val oldInfo = arrayRepInfo[rep] ?: ArrayInfo.Unknown
            arrayRepInfo[rep] = newInfo.join(oldInfo)
            return if (newInfo.join(oldInfo) == ArrayInfo.Not &&
                oldInfo is ArrayInfo.Packed && newInfo is ArrayInfo.Packed
            ) {
                cx.logger.warn { "Conflicting widths for packed array $path (${oldInfo.width} != ${newInfo.width})" }
                false
            } else {
                true
            }
        }


        // Let's first see what the solidity compiler says (also consulting the structure of the NonIndexedPath).
        for (rep in cx.pathEquivalence.getAllRepresentatives()) {
            for (array in cx.pathEquivalence.getEquivalenceClass(rep)) {
                joinArrayInfo(rep, cx.layout.widthOfPackedArray(array))
            }
        }

        val _nonConstReads: Map<ITACMethod, List<Info>> =
            cx.methods.associateWith { method ->
                val readQuery = query(method, patterns.finalLoad)
                cx.storageCommands(method)
                    .mapNotNull { it.maybeNarrow<WordLoad>() }
                    .filter { arrayRepInfo(it.cmd) != ArrayInfo.Not }
                    .mapNotNull { start ->
                        // Look for the pattern right after a load command.
                        (method.code as CoreTACProgram).analysisCache.graph
                            .iterateBlock(start.ptr)
                            .takeWhile { it.cmd !is TACCmd.Simple.StorageAccessCmd }
                            .mapNotNull { it.maybeNarrow<AssignExpCmd>() }
                            .firstNotNullOfOrNull { lcmd ->
                                readQuery.queryFrom(lcmd).toNullableResult()
                            }
                    }
                    .filter { checkQuery(method, it, isRead = true) }
                    .toList()
                    // maybe the same pattern was found twice by two different loads.
                    .distinctBy { info -> info[FINAL_LOAD_CMD] }
            }

        val _nonConstWrites: Map<ITACMethod, List<Info>> =
            cx.methods.associateWith { method ->
                val valueQuery = query(method, patterns.storedSlot)
                cx.storageCommands(method)
                    .mapNotNull { it.maybeNarrow<WordStore>() }
                    .filter { arrayRepInfo[cx.representativePath(it.cmd)] != ArrayInfo.Not }
                    .mapNotNull { cmd ->
                        (cmd.cmd.value as? TACSymbol.Var)?.let {
                            valueQuery.query(it, cmd.wrapped).toNullableResult()
                                ?.set(STORE_CMD, cmd.wrapped)
                        }?.takeIf { info ->
                            // check that 'loc' of the store is the same as that of the inner read.
                            cmd.cmd.loc == info[READ_CMD]!!.narrow<WordLoad>().cmd.loc
                        }
                    }
                    .filter { checkQuery(method, it, isRead = false) }
                    .toList()
            }

        // The patterns may give a different width than we expected
        for ((_, infos) in _nonConstReads.asSequence() + _nonConstWrites.asSequence()) {
            for (info in infos) {
                joinArrayInfo(info[PATHS]!!.first(), ArrayInfo.Packed(info[BITWIDTH]!!))
            }
        }

        // then we remove any array that does not have a consistent index access according to storage analysis
        for (method in cx.methods) {
            cx.storageCommands(method)
                .filter { arrayRepInfo(it.cmd) is ArrayInfo.Packed }
                .filter { it.cmd.indexOfArrayAccess == null }
                .forEach { (ptr, cmd) ->
                    cx.representativePath(cmd)?.let {
                        joinArrayInfo(it, ArrayInfo.Not)
                        cx.logger.warn { "Storage Array Access at ${cx.contract}, $ptr : $cmd for $it fails storage splitting" }
                    }
                }
        }

        // whomever now doesn't have width should be gone. We may actually be ditching real array accesses, but those
        // are such that solidity gave us no clue, and they have only accesses at constant indices. Those cannot
        // be differentiated from arrays of structs, which should not be thought of as packed arrays.
        arrayRepWidth = arrayRepInfo.mapValuesNotNull { (_, info) ->
            (info as? ArrayInfo.Packed)?.width
        }


        nonConstWrites = cx.methods.associateWith { method ->
            _nonConstWrites[method]?.filter { it.isIn(arrayRepWidth.keys) } ?: emptyList()
        }

        nonConstReads = cx.methods.associateWith { method ->
            _nonConstReads[method]?.filter { it.isIn(arrayRepWidth.keys) } ?: emptyList()
        }
    }


    /**
     * This checks that the values we gather whilst pattern matching are still valid at the rewrite point. That
     * means there was no assignment to these variables between the original point and the point we will be using them
     * in the rewrite.
     *
     * Currently, a failure of this method will fail the pattern, but there is actually a work around - add a temporary
     * variable to record the value at the query point, and use that value in the rewrite point. Currently we don't have
     * even one case where this happens, so this solution is not implenented.
     */
    private fun checkQuery(method: ITACMethod, info: Info, isRead: Boolean): Boolean {
        val rewritePoint = if (isRead) {
            info[FINAL_LOAD_CMD]!!.ptr
        } else {
            info[STORE_CMD]!!.ptr
        }

        fun isStillValid(vararg keys: InfoKey<LTACSymbol>) =
            keys.mapNotNull { info[it] }
                .all { (ptr, sym) ->
                    sym !is TACSymbol.Var ||
                        cx.isEquivAtPtrs(method, sym, ptr, rewritePoint)
                }

        fun assignmentStillHolds(vararg keys: InfoKey<LTACCmd>) =
            keys.mapNotNull { info[it] }
                .all { (ptr, _) ->
                    cx.isDefOf(method, useSite = rewritePoint, assignSite = ptr)
                }

        return (
            isStillValid(
                LOGICAL_INDEX,
                LOGICAL_INDEX2,
            ) && assignmentStillHolds(
                PHYSICAL_INDEX_CMD,
                FIRST_BYTE_WITHIN_SLOT_CMD,
                READ_CMD,
                VALUE_CMD,
                BOOL_VALUE_CMD,
                FIRST_BYTE_WITHIN_SLOT_CMD
            ) && // these two should be the same variable (but only if LOGICAL_INDEX2 actually exists)
                (info[LOGICAL_INDEX2]?.let { it.symbol == info[LOGICAL_INDEX]!!.symbol } ?: true)
            ).also {
                if (!it) {
                    cx.logger.warn {
                        "In ${cx.contract}, array storage access pattern is recognized but has overriding assignment : $info"
                    }
                }
            }
    }


    /** A wrapper class for [arrayRepWidths] which also takes [badArrays] into consideration */
    inner class ArrayWidths(
        private val arrayRepWidths: Map<NonIndexedPath, Int>,
        private val badArrays: Set<NonIndexedPath>
    ) {
        fun isArray(path: NonIndexedPath) =
            cx.representativePath(path).let {
                it in arrayRepWidth.keys && it !in badArrays
            }

        fun widthOf(path: NonIndexedPath) = run {
            check(isArray(path))
            arrayRepWidths[cx.representativePath(path)]!!
        }
    }

    class ForFinder(val arrayWidths: ArrayWidths, val overrides: NestedMap<ITACMethod, LTACCmd, Info>)

    /** Generates overrides for [SplitFinder], filtering out [badArrays] */
    fun forFinder(badArrays: Set<NonIndexedPath>): ForFinder {
        val overrides = mutableNestedMapOf<ITACMethod, LTACCmd, Info>()
        for (method in cx.methods) {
            nonConstReads(method, badArrays)
                .forEach { info ->
                    overrides[method, info[DIV_CMD]!!] = info
                }
            nonConstWrites(method, badArrays)
                .forEach { info ->
                    overrides[method, info[AND_WITH_READ]!!] = info
                    overrides[method, info[LAST_OR]!!] = info
                }
        }

        return ForFinder(ArrayWidths(arrayRepWidth, badArrays), overrides)
    }

    inner class ForRewriter(
        val arrayWidths: ArrayWidths,
        val constIndexRewriters: NestedMap<ITACMethod, LTACCmd, ConstIndexRewriter>,
        val nonConstReads: Map<ITACMethod, List<Info>>,
        val nonConstWrites: Map<ITACMethod, List<Info>>
    )

    /**
     * The code for rewrite non-constant index accesses is in [SplitRewriter], but here we provide it with
     * an instance of [ConstIndexRewriter] for rewriting the constant index accesses.
     */
    fun forRewriter(badArrays: Set<NonIndexedPath>): ForRewriter {
        val arrayWidths = ArrayWidths(arrayRepWidth, badArrays)

        val rewriters = cx.methods.associateWith { method ->
            cx.storageCommands(method)
                .filter { (_, cmd) ->
                    cx.representativePath(cmd)?.let(arrayWidths::isArray) == true
                }.associateWith(::ConstIndexRewriter)
        }

        return ForRewriter(
            arrayWidths,
            rewriters,
            cx.methods.associateWith { nonConstReads(it, badArrays) },
            cx.methods.associateWith { nonConstWrites(it, badArrays) }
        )
    }

    fun finalLog(badArrays: Set<NonIndexedPath>) {
        val msg = arrayRepWidth.entries
            .filter { (path, _) -> path !in badArrays }
            .joinToString("\n") { (path, width) ->
                "Detected and rewrote $path as a packed array of width $width bits"
            }
        Logger.regression { msg }
        cx.logger.info { msg }
    }
}


/**
 * Used for rewriting a constant index array access in [SplitRewriter]. It takes the information from the
 * [ACCESS_PATHS] meta generated by storage analysis to calculate the new index of a constant index access, also
 * generating the new commands that calculates this index.
 *
 * Note that the same original index calculating command can now break into a few new ones, because the reading of
 * one slot can now turn to a few readings (I have not yet seen this for arrays, but our rewriting infrastructure supports
 * it anyway for structs, so it's an easy add).
 */
class ConstIndexRewriter(val lcmd: LTACCmd) {
    private val oldLoc = lcmd.cmd.storageLoc as TACSymbol.Var
    private val oldIndex = lcmd.cmd.indexOfArrayAccess!!

    /**
     * Returns the new location to access in the storage command [lcmd].
     *
     * Note the important difference between the [oldIndex] which sees the array as an array of words, and the index we
     * eventually generate, where the index is the index understanding the array is actually packed.
     *
     * The original command accessed storage at [oldLoc], which is some `base` + [oldIndex]. Since we are not sure which
     * variable stores the base, and sometimes there is none (e.g., in a 2-d static array, the offset is from the base of
     * the 2-d array). So we count on storage analyis to give us [oldIndex], and the new access location is:
     *    ([oldLoc] - [oldIndex]) + (elementsPerSlot * [oldIndex]) + <indexWithinSlot of [range]>
     * This may actually cause collisions in the case we have two different access paths in the same
     * equivalence class, because we've just spread out the original array, but we didn't spread out the bases
     * accordingly. Two examples (see `Test/Packing/PackedArrays/static/Collisions`):
     *
     * 1. `uint128[2][10] a` : will have a collision between `a[0][5]` and `a[1][0]`
     * 2. `Contract C { uint128[2] a; uint128[2] b` }, if they are in the same equivalence class, then `a[1]` will
     *    collide with `b[0]`
     *
     * The solution we have is to hash the base, and use that as the new base. This will prevent any collisions if
     * the base is different. It is needed only in some cases. See [shouldHashBase].
     */
    fun newLocAndAuxCmdsForConstAccess(range: BitRange.NonEmpty): UnfolderResult<TACExpr.Sym.Var> {
        val indexWithinSlot = (range.highBit / range.width - 1).asTACExpr
        val elementsPerSlot = (EVM_BITWIDTH256 / range.width).asTACExpr

        val (newIndex, newCmds1) = unfoldToSingleVar("constArrayAccess", TACExprFactUntyped {
            Add(
                Mul(elementsPerSlot, oldIndex.asSym()),
                indexWithinSlot
            )
        })

        val (newLoc, newCmds2) = newLocAndAuxCmds(oldLoc, oldIndex, (newIndex as TACExpr.Sym.Var).s)
        return UnfolderResult(newLoc, newCmds1 + newCmds2)
    }
}


