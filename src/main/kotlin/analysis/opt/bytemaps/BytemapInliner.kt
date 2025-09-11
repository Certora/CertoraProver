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
import datastructures.stdcollections.*
import log.*
import tac.Tag
import utils.*
import utils.Color.Companion.blue
import utils.Color.Companion.yellow
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.tacexprutil.asConstOrNull
import vc.data.tacexprutil.isConst
import vc.data.tacexprutil.isVar
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
 * memory, which obfuscates overflow patters (as well as other things).
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
                nextsOrResult = { (currentPtr, query) : Pair<CmdPointer, Query> ->
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
                val lhs = cmd.getLhs() ?: continue
                val newTerm = handleCmd(ptr, cmd)?.mod(Tag.Bit256.modulus)
                    ?.also { tf.lhsTerm[ptr] = it }

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
            return c.asTACExpr
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
            TACExpr.Vec.Add(varExpr, c.asTACExpr, Tag.Bit256)
        }
    }

    companion object {
        fun go(code: CoreTACProgram, isInlineable: (TACSymbol.Var) -> Boolean, cheap: Boolean) =
            BytemapInliner(code, isInlineable, cheap).rewrite()
    }

}
