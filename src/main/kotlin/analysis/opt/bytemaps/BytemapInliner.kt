/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.opt.bytemaps


import algorithms.DeRecursor
import analysis.CmdPointer
import analysis.forwardVolatileDagDataFlow
import analysis.opt.bytemaps.TermFactory.Query
import analysis.opt.intervals.IntervalsCalculator
import com.certora.collect.*
import com.certora.collect.TreapMap.MergeMode
import datastructures.TreapMultiMap
import datastructures.add
import datastructures.delete
import datastructures.memoized
import datastructures.stdcollections.*
import log.*
import tac.Tag
import utils.*
import utils.Color.Companion.blue
import utils.Color.Companion.yellow
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.tacexprutil.*
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldToSingleVar
import java.math.BigInteger

private val logger: Logger = Logger(LoggerTypes.BYTEMAP_INLINER)

/**
 * Looks for cases where a load from a bytemap can be resolved statically, by matching with a previous store or a
 * map-definition. As a "side-effect" it also simplifies expressions using a linear-combination of vars abstract domain.
 *
 * It goes forwards in the program (assumed to be loop free), calculating for each variable a linear term. When handling
 * a command loading from a bytemap, it traverses backwards in the store-chain for this bytemap, trying to match a
 * store location to the current load location.
 *
 * Also, if the linear-term for a rhs expression is equal to a previous lhs var linear-term, it may replace the the
 * expression with that var, simplifying rhs expressions. A specific example which is very common is `x & 0xff...ffe0`
 * (equivalent to `x % 32`). Many times `x`'s linear term can show us that `x` is a multiple of 32, and the bitwise-and
 * can be removed.
 *
 * This class assumes bytemaps are all initialized, and all bytemap related commands are in 3-address-form.
 *
 * Currently this can't inline map definitions if they are not constant ones.
 *
 * [cheap] makes the inliner run without using an [IntervalsCalculator], making it much faster, but it can do much less.
 * For one, it doesn't understand long-stores at all. This is useful specifically for getting rid of vyper usage of
 * memory, which obfuscates overflow patterns (as well as other things).
 *
 * The optimizer also performs chain shortening on longstore operations via [shortenStoreChain]. This optimization
 * eliminates intermediate bytemap variables by tracing through their definitions (see [shortenStoreChain] for details
 * and examples).
 */
class BytemapInliner private constructor(
    private val code: CoreTACProgram,
    private val isInlineable: (TACSymbol.Var) -> Boolean,
    cheap: Boolean,
) {
    private val g = code.analysisCache.graph
    private val def = code.analysisCache.strictDef
    private val intervals = runIf(!cheap) { IntervalsCalculator(code, preserve = { false }) }
    private val patcher = ConcurrentPatchingProgram(code)
    private val tf = TermFactory(code, intervals)
    private val stats = SimpleCounterMap<String>()
    private fun <T> T.stat(s: String) = also { stats.plusOne(s) }


    /**
     * Returns the term for the rhs of the command, and tries to do so also in cases of a load from a bytestore.
     * In which cases it traverses backwards in the store-chain.
     *
     * 1. For non-bytemap commands this is simple, e.g., `x := a + b`, it will return the term resulting from adding
     *    the terms associated with `a` and `b`.
     * 2. Returns null for commands with a non Bit266/Int lhs.
     * 3. For a Byteload, it will take the term for `loc` and traverse back in the store chain for this bytemap,
     *    trying to match this term with the store location term:
     *      * If it matches, the the `value` of that store is returned - meaning that this load command can be replaced
     *        with this `value` (or more precisely, with a TAC expression that is known to have the same term).
     *      * If we reached a map-definition, we may be able to return the rhs of the map-definition (currently done
     *        only for constant map-definitions)
     */
    private fun handleCmd(ptr: CmdPointer, cmd: TACCmd.Simple): Term? =
        object : ByteMapCmdHandler<Term> {
            override fun fallthrough() =
                if (cmd is AssignExpCmd) {
                    tf.expr(ptr, cmd.rhs)
                } else {
                    null
                }

            /**
             * Called on a load command.
             * Returns the term associated with the querying of map [base] defined at [ptr] with location
             * described by [Query].
             */
            override fun load(lhs: TACSymbol.Var, base: TACSymbol.Var, loc: TACSymbol): Term? {
                val query = Query(ptr, tf.rhsTerm(ptr, loc)!!)
                return def.defSitesOf(base, ptr)
                    .map { loadAtLhs(it!!, query) }
                    .uniqueOrNull()
            }

            /**
             * Returns the term associated with the querying of map appearing in the lhs of `currentPtr`.
             * It goes backwards in the store chain, looking for a store whose location matches that of `query`.
             * If found, then the term corresponding to the value stored is returned. If none is found, null is returned.
             *
             * Using recursion caused a stack overflow, so we have to [DeRecursor] this. Note that the [DeRecursor]'s
             * instance memoization cache is shared for all usages within this `handleCmd` call, but not between
             * different commands. I believe that different load commands will rarely result in the same queries, and
             * the memory usage can be quite large if we cache all of these.
             */
            private val loadAtLhsFun = DeRecursor(
                memoize = true,
                reduce = { it.uniqueOrNull() },
                nextsOrResult = { (currentPtr, query): Pair<CmdPointer, Query> ->
                    fun rhsTermOf(s: TACSymbol) = tf.rhsTerm(currentPtr, s)!!

                    fun loadAtRhs(base: TACSymbol.Var, query: Query) =
                        def.defSitesOf(base, currentPtr).map { it!! to query }.toLeft()

                    val handler = object : ByteMapCmdHandler<Either<List<Pair<CmdPointer, Query>>, Term?>> {
                        override fun simpleAssign(lhs: TACSymbol.Var, rhs: TACSymbol.Var) =
                            loadAtRhs(rhs, query)

                        override fun store(
                            lhs: TACSymbol.Var,
                            rhsBase: TACSymbol.Var,
                            loc: TACSymbol,
                            value: TACSymbol
                        ) =
                            when (tf.areEqual(currentPtr, rhsTermOf(loc), query.ptr, query.t)) {
                                true -> rhsTermOf(value).toRight()
                                false -> loadAtRhs(rhsBase, query)
                                null -> null
                            }

                        override fun longstore(
                            lhs: TACSymbol.Var,
                            srcOffset: TACSymbol,
                            dstOffset: TACSymbol,
                            srcMap: TACSymbol.Var,
                            dstMap: TACSymbol.Var,
                            length: TACSymbol
                        ): Either<List<Pair<CmdPointer, Query>>, Term?>? {
                            val lengthTerm = rhsTermOf(length)
                            if (lengthTerm.asConstOrNull == BigInteger.ZERO) {
                                return loadAtRhs(dstMap, query)
                            }
                            val dstOffsetTerm = rhsTermOf(dstOffset)
                            val srcOffsetTerm = rhsTermOf(srcOffset)
                            return when (tf.isInside(
                                queryPtr = query.ptr,
                                query = query.t,
                                rangeQuery = currentPtr,
                                low = dstOffsetTerm,
                                high = dstOffsetTerm + lengthTerm
                            )) {
                                true -> loadAtRhs(srcMap, query.shift(srcOffsetTerm - dstOffsetTerm))
                                false -> loadAtRhs(dstMap, query)
                                null -> null
                            }
                        }

                        override fun ite(lhs: TACSymbol.Var, i: TACSymbol, t: TACSymbol.Var, e: TACSymbol.Var) =
                            listOf(
                                loadAtRhs(t, query),
                                loadAtRhs(e, query)
                            ).sameValueOrNull()

                        override fun mapDefinition(lhs: TACSymbol.Var, param: TACSymbol.Var, definition: TACExpr) =
                            definition.asConstOrNull?.let { Term(it).toRight() }
                    }

                    handler.handle(g.toCommand(currentPtr)) ?: null.toRight()
                }
            )

            private fun loadAtLhs(currentPtr: CmdPointer, query: Query): Term? =
                loadAtLhsFun.go(currentPtr to query)

        }.handle(cmd)


    /**
     * Result of [careFor]: the bytemap variable and offset that should be used, along with the pointer where
     * that variable is used (and that is the version of the variable we want).
     */
    private data class R(val at: CmdPointer, val mapVar: TACSymbol.Var, val offset: Term)

    /**
     * Given a bytemap definition at `origPtr`, traverses backwards through the store-chain to find the "real"
     * source for a given memory region.
     *
     * `origPtr` The pointer to start the traversal from (typically a bytemap definition site)
     * `origCareStart` The starting offset of the region we care about
     * `careLength` The length of the region we care about
     * `careForInside` If true, we want to find where data *inside* [`origCareStart`, `origCareStart`+`careLength`)
     *                      came from. If false, we want to find the base map that is modified *outside* this range.
     *
     * @return An [R] containing the pointer, map variable, and offset where the region actually comes from,
     *         or null if the analysis is uncertain. The pointer is where the map variable is used, i.e., is on the
     *         rhs. That is the version we'd like to eventually inline.
     *
     * Example (careForInside = true):
     *   Given: `M1 := M0[0x100.. = M2[0x50..+0x40]]` and we care about [0x110, 0x110+0x20),
     *   Returns: R pointing to M2's definition with offset 0x60 (because 0x110-0x100+0x50 = 0x60)
     *
     * Example (careForInside = false):
     *   Given: `M1 := M0[0x100.. = M2[0x50..+0x40]]` and we care about [0x200, 0x200+0x40),
     *   Returns: R pointing to M0's definition with offset 0x200 (because the region doesn't intersect the store)
     *
     * This is memoized to avoid recalculating results.
     */

    private val careFor =
        memoized { origPtr: CmdPointer, origCareStart: Term, careLength: Term, careForInside: Boolean ->
            fun recurse(ptr: CmdPointer, careStart: Term): R? {
                fun t(v: TACSymbol) = tf.expr(ptr, v.asSym())!!
                fun singleDef(v: TACSymbol.Var) = def.defSitesOf(v, ptr).singleOrNull()

                return object : ByteMapCmdHandler<R> {
                    override fun simpleAssign(lhs: TACSymbol.Var, rhs: TACSymbol.Var) =
                        singleDef(rhs)
                            ?.let { recurse(it, careStart) }
                            ?: R(ptr, rhs, careStart)

                    override fun store(
                        lhs: TACSymbol.Var,
                        rhsBase: TACSymbol.Var,
                        loc: TACSymbol,
                        value: TACSymbol
                    ): R? {
                        val inBounds = tf.isInside(t(loc), careStart, careLength)
                        return runIf(inBounds == true && !careForInside || inBounds == false && careForInside) {
                            singleDef(rhsBase)
                                ?.let { recurse(it, careStart) }
                                ?: R(ptr, rhsBase, careStart)
                        }
                    }

                    override fun longstore(
                        lhs: TACSymbol.Var,
                        srcOffset: TACSymbol,
                        dstOffset: TACSymbol,
                        srcMap: TACSymbol.Var,
                        dstMap: TACSymbol.Var,
                        length: TACSymbol
                    ): R? {
                        val lengthTerm = tf.rhsTerm(ptr, length)!!
                        val srcOffsetTerm = tf.rhsTerm(ptr, srcOffset)!!
                        val dstOffsetTerm = tf.rhsTerm(ptr, dstOffset)!!

                        return if (careForInside) {
                            // We're looking for where data inside [careStart, careStart+careLength) came from.
                            // The longstore copies [dstOffset, dstOffset+length) from srcMap to dstMap.
                            when (tf.isContainedIn(careStart, careLength, dstOffsetTerm, lengthTerm)) {
                                // Case 1: Our region is fully contained in the longstore's destination range.
                                // The data came from srcMap, but at a shifted offset.
                                // Example: lhs := dstMap[100.. = srcMap[50..+64]], careStart=120, careLength=32
                                // Result: data comes from srcMap at offset 70 (120-100+50)
                                true -> singleDef(srcMap)
                                    ?.let { recurse(it, careStart + srcOffsetTerm - dstOffsetTerm) }
                                    ?: R(ptr, srcMap, careStart + srcOffsetTerm - dstOffsetTerm)

                                // Case 2: Our region doesn't intersect the longstore's destination range.
                                // The data came from the base dstMap unchanged.
                                // Example: lhs := dstMap[100.. = srcMap[50..+64]], careStart=200, careLength=32
                                // Result: data comes from dstMap at offset 200
                                false -> singleDef(dstMap)
                                    ?.let { recurse(it, careStart) }
                                    ?: R(ptr, dstMap, careStart)

                                // Case 3: Partial overlap - can't determine statically.
                                null -> null
                            }
                        } else {
                            // We're looking for the base map that is modified outside [careStart, careStart+careLength).
                            // Only proceed if the longstore's destination is fully contained in our "don't care" region.
                            // Example: lhs := dstMap[100.. = srcMap[50..+64]], careStart=80, careLength=100
                            // The store [100..164) is fully inside [80..180), so we can look at dstMap's definition.
                            runIf(tf.isContainedIn(dstOffsetTerm, lengthTerm, careStart, careLength) == true) {
                                singleDef(dstMap)
                                    ?.let { recurse(it, careStart) }
                                    ?: R(ptr, dstMap, careStart)
                            }
                        }
                    }
                }.handle(g.toCommand(ptr))
            }
            recurse(origPtr, origCareStart)
        }


    /**
     * A fresh var recording the value of a map variable `mapVar` that appears at the rhs of `at`. It's used to bypass
     * the effect of assignments to `mapVar` that appear after `at`.
     */
    private val bypassVars = memoized { at: CmdPointer, mapVar: TACSymbol.Var ->
        patcher.newTempVar("bypass", Tag.ByteMap).also {
            patcher.prependBefore(at, listOf(AssignExpCmd(it, mapVar)))
        }
    }

    /**
     * Attempts to optimize a longstore command by finding simpler source/destination maps in the store chain.
     *
     * For a command like `M3 := M1[dstOff.. = M2[srcOff..+len]]`, this function:
     * 1. Traces back through M2's definition chain to find the "real" source of the data being copied
     * 2. Traces back through M1's definition chain to find the "real" base map being modified
     *
     * This can shorten long chains like:
     *   ```
     *   M1 := M0[0x100.. = SomeData[0x50..+0x40]]
     *   M2 := OtherData[0x0.. = M1[0x120..+0x20]]
     *   ```
     * into:
     *   ```
     *   M2 := OtherData[0x0.. = SomeData[0x70..+0x20]]
     *   ```
     *
     * @param ptr Pointer to the command being optimized
     * @param cmd The command itself (must be a longstore)
     * @return A simplified command if optimization is possible, null otherwise
     */
    private fun shortenStoreChain(ptr: CmdPointer, cmd: TACCmd.Simple): TACCmd.Simple? {

        /**
         * Ensures that [mapVar] is accessible at [ptr] by either returning it directly (if it's in scope)
         * or creating a bypass variable right before [at] that captures the value.
         */
        fun toMapVar(at: CmdPointer, mapVar: TACSymbol.Var) =
            if (def.source(ptr, mapVar) == def.source(at, mapVar)) {
                mapVar
            } else {
                bypassVars(at, mapVar)
            }

        return object : ByteMapCmdHandler<TACCmd.Simple> {
            override fun longstore(
                lhs: TACSymbol.Var,
                srcOffset: TACSymbol,
                dstOffset: TACSymbol,
                srcMap: TACSymbol.Var,
                dstMap: TACSymbol.Var,
                length: TACSymbol
            ): TACCmd.Simple? {
                fun t(v: TACSymbol) = tf.expr(ptr, v.asSym())!!
                fun singleDef(v: TACSymbol.Var) = def.defSitesOf(v, ptr).singleOrNull()

                val srcOffsetTerm = t(srcOffset)
                val dstOffsetTerm = t(dstOffset)
                val lengthTerm = t(length)

                val newSrcMapPtrAndOffset = singleDef(srcMap)
                    ?.let { careFor(it, srcOffsetTerm, lengthTerm, true) }
                    ?.let {
                        val offset = it.offset.toTACExpr(ptr)
                            ?: return@let null
                        val newOffset = if (offset is TACExpr.Sym) {
                            offset
                        } else {
                            val (newOffsetSym, cmds) = unfoldToSingleVar("offset", offset)
                            patcher.prependBefore(ptr, cmds)
                            newOffsetSym.asVarOrNull?.let(patcher::addVar)
                            newOffsetSym
                        }
                        toMapVar(it.at, it.mapVar) to newOffset
                    }

                val newDstMap = singleDef(dstMap)
                    ?.let { careFor(it, dstOffsetTerm, lengthTerm, false) }
                    ?.let {
                        check(it.offset == dstOffsetTerm)
                        toMapVar(it.at, it.mapVar)
                    }

                return runIf(newSrcMapPtrAndOffset != null || newDstMap != null) {
                    AssignExpCmd(
                        lhs,
                        TACExpr.LongStore(
                            (newDstMap ?: dstMap).asSym(),
                            dstOffset.asSym(),
                            (newSrcMapPtrAndOffset?.first ?: srcMap).asSym(),
                            (newSrcMapPtrAndOffset?.second ?: srcOffset.asSym()),
                            length.asSym()
                        )
                    )
                }
            }
        }.handle(cmd)
    }


    /**
     * Goes through the program graph in topological order, calculating the term associated with each lhs bits256/int
     * variable, and:
     *   1. if this term is a constant, replaces the rhs with that constant.
     *   2. if there is some previous variable that is associated with the same term - replace the rhs with this
     *      variable (unless rhs is already a variable).
     *   3. if the term is just a variable, or a variable+const, we use that as the rhs.
     */
    fun rewrite(): CoreTACProgram {
        // The "Data" here is a map from each term to the set of variables that are known to have this term. Any of
        // these variables can be used as a replacement if we encounter this term again.
        // we keep many vars and not just one, because some of them may disappear (because of reassignment or joining
        // of branches).
        forwardVolatileDagDataFlow(code) { block, lastReps: List<TreapMultiMap<Term, TACSymbol.Var>> ->
            var reps = lastReps.reduceOrNull { m1, m2 ->
                m1.merge(m2, MergeMode.INTERSECTION) { _, set1, set2 ->
                    (set1!! intersect set2!!).takeIf { it.isNotEmpty() }
                }
            } ?: treapMapOf()

            for ((ptr, cmd) in g.lcmdSequence(block)) {
                val lhs = cmd.getModifiedVar() ?: continue
                val newTerm = handleCmd(ptr, cmd)?.mod(Tag.Bit256.modulus)
                    ?.also { tf.lhsTerm[ptr] = it }

                val shortenedCmd = shortenStoreChain(ptr, cmd)
                if (shortenedCmd != null) {
                    patcher.replace(ptr, shortenedCmd)
                    continue
                }

                // all terms are mod 2^256, so int vars are not allowed to appear in `reps`.
                if (lhs.tag !is Tag.Bit256 || !isInlineable(lhs)) {
                    continue
                }
                if (newTerm != null) {
                    val oldRhs = (cmd as? AssignExpCmd)?.rhs
                    val newRhs = when {
                        oldRhs?.isConst == true -> null
                        newTerm.isConst -> newTerm.asConst.asTACExpr

                        // if it's already a variable, don't bother
                        oldRhs?.isVar == true -> null

                        // we chose the last representative. Though I'm not sure being last has any importance.
                        newTerm in reps -> reps[newTerm]!!.last().asSym()

                        newTerm.isVar -> newTerm.toTACExpr(ptr)

                        // if new term is `x+const`, use it only if rhs is complex.
                        newTerm.support.size == 1 && oldRhs == null ->
                            newTerm.toTACExpr(ptr)

                        else -> null
                    }
                    if (newRhs != null) {
                        patcher.replace(ptr, AssignExpCmd(lhs, newRhs, cmd.meta))
                            .stat("count")
                    }
                }
                // Get rid of lhs acting as a representative (i.e., a "kill" in the dataflow context). But the reps map is
                // keyed not with representatives, but with terms. So I want to find every <term, lhs> entry in the map,
                // and erase it. We could have kept a reverse-map, but this is a way which I think is more efficient.
                // It relies on the fact that if lhs is indeed a representative for some term, it means that if we go to
                // the def sites of lhs (right before this assignment), then the lhsTerm at that def-site should be
                // exactly that term.
                //
                // Also, since reps is an intersection of the reps coming in the dataflow analysis, then this term must
                // be the same at all def-sites. In other words, lhs can act as a representative for only one term.
                // That's why it's enough to take one.
                def.defSitesOf(lhs, ptr).firstOrNull()?.let {
                    tf.lhsTerm[it]?.let { oldTerm ->
                        reps = reps.delete(oldTerm, lhs)
                    }
                }
                if (newTerm != null) {
                    reps = reps.add(newTerm, lhs)
                }
            }

            reps
        }

        logger.info {
            stats.toString(javaClass.simpleName)
        }
        logger.trace {
            patcher.debugPrinter()
                .extraLines { (ptr, cmd) ->
                    listOfNotNull(
                        tf.lhsTerm[ptr]?.blue,
                        cmd.getLhs()?.let { intervals?.getLhs(ptr) }?.yellow
                    )
                }
                .toString(code, javaClass.simpleName)
        }
        return patcher.toCode()
    }


    /**
     * Returns a [TACExpr] that at [ptr] has the value of the reciever term.
     * It will return null if the resulting expression is anything more complex than `var + const`.
     */
    private fun Term.toTACExpr(ptr: CmdPointer): TACExpr? {
        if (isConst) {
            return c.mod(Tag.Bit256.modulus).asTACExpr
        }
        val (unique, coef) = literals.entries.singleOrNull()
            ?: return null
        if (coef != BigInteger.ONE) {
            return null
        }
        val (v, defs) = unique
        val varExpr = if (defs containsAll def.defSitesOf(v, ptr)) {
            v.asSym()
        } else {
            // if the version of `v` is not relevant at `ptr` because it was reassigned before `ptr`, we go to the def
            // sites of `v`, and add a new variable that record the value of `v` at that point.
            val temp = patcher.newTempVar("bypass", v.tag)
            val newCmds = listOf(AssignExpCmd(temp, v))
            defs.forEach { defPtr ->
                if (defPtr == null) {
                    patcher.prependBefore(CmdPointer(g.rootBlockIds.single(), 0), newCmds)
                } else {
                    patcher.insertAfter(defPtr, newCmds)
                }
            }
            temp.asSym()
        }
        return if (c == BigInteger.ZERO) {
            varExpr
        } else {
            TACExpr.Vec.Add(varExpr, c.mod(Tag.Bit256.modulus).asTACExpr, Tag.Bit256)
        }
    }

    companion object {
        fun go(code: CoreTACProgram, isInlineable: (TACSymbol.Var) -> Boolean, cheap: Boolean) =
            BytemapInliner(code, isInlineable, cheap).rewrite()
    }


}

