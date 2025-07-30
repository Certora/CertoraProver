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

import analysis.CmdPointer
import analysis.backwardVolatileDagDataFlow
import analysis.opt.bytemaps.TermFactory.Query
import analysis.opt.intervals.IntervalsCalculator
import com.certora.collect.*
import datastructures.addAll
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import instrumentation.transformers.FilteringFunctions
import log.*
import tac.Tag
import utils.*
import utils.Color.Companion.blueBg
import utils.Color.Companion.redBg
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.tacexprutil.asVar
import vc.data.tacexprutil.asVarOrNull

private val logger: Logger = Logger(LoggerTypes.BYTEMAP_CIF)

/**
 * Does a cone-of-influence reduction, specifically aimed at deleting and simplifying bytemap operations. There
 * are a few simplifications, e.g., if none of the queries to a bytemap created by a store are known to never be equal
 * to the store location, it replaces the store with a simple assignment.
 *
 * Assumes that all commands involving bytemaps are unfolded.
 */
class BytemapConeOfInf private constructor(
    private val code: CoreTACProgram,
    private val filteringFunctions: FilteringFunctions,
) {
    private val g = code.analysisCache.graph
    private val def = code.analysisCache.strictDef
    private fun nonNullDefs(v: TACSymbol.Var, ptr: CmdPointer) =
        def.defSitesOf(v, ptr).filterNotNull()

    private val intervals = IntervalsCalculator(code, preserve = { false })
    private val patcher = ConcurrentPatchingProgram(code)
    private val tf = TermFactory(code, intervals)
    private val txf = TACExprFactUntyped
    private val stats = SimpleCounterMap<String>()
    private fun <T> T.stat(s: String) = also { stats.plusOne(s) }

    /**
     * Entry point.
     */
    fun rewrite(): CoreTACProgram {
        calcTerms()
        removeUnneeded()
        logger.info {
            stats.toString(javaClass.simpleName)
        }
        logger.trace {
            patcher.debugPrinter()
                .extraLines { (ptr, _) ->
                    when (ptr) {
                        in mustTakeBytemap -> listOf("   Must take it".redBg)
                        in allQueries -> listOf(allQueries[ptr]!!.size.blueBg)
                        else -> listOf()
                    }
                }
                .toString(code, javaClass.simpleName)
        }
        return patcher.toCode()
    }

    /**
     * Calculates the terms for every lhs variable, and fill up the `tf.lhsTerm` table with these values.
     * This does not attempt to calculate the terms for bytemap loads, as it assumes inlining was already
     * done, and so there is nothing to discover.
     */
    private fun calcTerms() {
        code.topoSortFw.forEach { b ->
            g.lcmdSequence(b).forEach { (ptr, cmd) ->
                if (cmd is AssignExpCmd) {
                    tf.expr(ptr, cmd.rhs)?.let {
                        tf.lhsTerm[ptr] = it
                    }
                }
            }
        }
    }

    /** returns the mapDefinition: `i` -> [value] **/
    private fun simpleMapDef(value: TACExpr) = txf {
        MapDefinition(listOf(patcher.newTempVar("md", Tag.Bit256).asSym()), value, Tag.ByteMap)
    }

    /** the bytemap `i -> 0` */
    private val zeroBytemap by lazy {
        patcher.newTempVar("zero", Tag.ByteMap).also {
            patcher.insertBefore(
                CmdPointer(g.rootBlockIds.single(), 0),
                listOf(AssignExpCmd(it, simpleMapDef(0.asTACExpr)))
            )
        }
    }

    /**
     * Does the cone-of-influence optimization. It traverses backwards in the program DAG, collecting which variables
     * and bytemap operations are needed. It handles primitive variables and bytemap variables differently.
     * Primitive variables that are deemed necessary are accumulated as the "data" in the dataflow calculation.
     * Bytemap definitions (store, longstore, etc):
     *   + are assumed to be unfolded, and each is identified through the ptr where the bytemap is defined.
     *   + For each such definition, we collect all [Query]s originating from later load operations on that bytemap
     *     (or one derived from it) in the global map [allQueries].
     *   + If a bytemap must be included no matter what queries it should answer, we add it to [mustTakeBytemap].
     */
    private fun removeUnneeded() {
        backwardVolatileDagDataFlow(code) { block, neededVars: List<TreapSet<TACSymbol.Var>> ->
            var needed = neededVars.reduceOrNull { s1, s2 -> s1.union(s2) }
                ?: treapSetOf()
            for ((ptr, cmd) in g.blockCmdsBackwardSeq(block)) {
                if (cmd is TACCmd.Simple.LogCmd) {
                    continue
                }
                needed = handleCmd(ptr, cmd, needed)
            }
            needed
        }
    }

    /** For each bytemap assignment (store, longstore, etc), collects the set of queries we care about. */
    private val allQueries = mutableMultiMapOf<CmdPointer, Query>()

    /** bytemap assignments we can't rid ourselves of, no matter where they are queried */
    private val mustTakeBytemap = mutableSetOf<CmdPointer>()

    /**
     * Given the set of [needed] non-bytemap variables after [ptr], returns the set of such variables before [ptr].
     * Deletes/simplifies what it can.
     */
    private fun handleCmd(
        ptr: CmdPointer,
        cmd: TACCmd.Simple,
        needed: TreapSet<TACSymbol.Var>
    ): TreapSet<TACSymbol.Var> {
        var newNeeded = needed
        val erasableCmd = cmd !is TACCmd.Simple.AssigningCmd || filteringFunctions.isErasable(cmd)

        fun delete(statName: String) =
            patcher.delete(ptr).stat(statName)

        fun replace(newCmd: TACCmd.Simple, statMsg: String) {
            if (newCmd is AssignExpCmd && newCmd.rhs.asVarOrNull == newCmd.lhs && erasableCmd) {
                delete("lhs=rhs")
            } else {
                patcher.replace(ptr, newCmd.withMeta(cmd.meta))
            }
            stat(statMsg)
        }

        val cmdHandler = object : ByteMapCmdHandler<Unit> {
            /** is this command in the cone of influence - relevant only when it's a bytemap creation command */
            val areQueries = !allQueries[ptr].isNullOrEmpty() || ptr in mustTakeBytemap

            /** Adds all [queries] to [bases] */
            fun addQueriesTo(
                vararg bases: TACSymbol.Var,
                queries: Collection<Query>? = allQueries[ptr]
            ) = when {
                ptr in mustTakeBytemap ->
                    // in this case we don't care for the specific queries, `mustTakeBytemap` is a more "powerful" marker.
                    bases.forEach { base ->
                        mustTakeBytemap += nonNullDefs(base, ptr)
                    }

                queries != null -> {
                    bases.forEach { base ->
                        nonNullDefs(base, ptr).forEach {
                            allQueries.addAll(it, queries)
                        }
                    }
                }

                else -> Unit
            }

            fun addToNeeded(vararg syms: TACSymbol?) =
                syms.forEach { s ->
                    s?.asVarOrNull?.let { newNeeded += it }
                }

            /**
             * Goes through all variables appearing in the command, marking bytemaps as [mustTakeBytemap] and
             * variables as `needed`.
             */
            fun blindlyAdd() {
                if (code.destructiveOptimizations && cmd is TACCmd.Simple.AnnotationCmd) {
                    return
                }
                val (bytemaps, otherVars) = cmd.getFreeVarsOfRhsExtended().partition { it.tag is Tag.ByteMap }
                addToNeeded(*otherVars.toTypedArray())
                bytemaps.forEach {
                    mustTakeBytemap += nonNullDefs(it, ptr)
                }
            }

            override fun fallthrough() {
                val lhs = cmd.getLhs()
                if (lhs != null && lhs !in needed && erasableCmd) {
                    delete("other")
                } else {
                    lhs?.let {
                        check(it.tag != Tag.ByteMap) {
                            "Unexpected bytemap assignment: $cmd"
                        }
                        newNeeded = needed - it
                    }
                    // we mark rhs variables as needed. If any bytemaps are there, it's because of uncovered cases,
                    // such as in a quantified expression, or within a map definition.
                    blindlyAdd()
                }
            }

            override fun simpleAssign(lhs: TACSymbol.Var, rhs: TACSymbol.Var) {
                // recall that we reach here only if lhs and rhs are bytemap variables.
                if (!areQueries && erasableCmd) {
                    delete("simple")
                } else {
                    addQueriesTo(rhs.asVar)
                }
            }

            override fun ite(lhs: TACSymbol.Var, i: TACSymbol, t: TACSymbol.Var, e: TACSymbol.Var) {
                if (!areQueries && erasableCmd) {
                    delete("ite")
                } else {
                    addQueriesTo(t.asVar, e.asVar)
                    addToNeeded(i)
                }
            }

            override fun load(lhs: TACSymbol.Var, base: TACSymbol.Var, loc: TACSymbol) {
                if (lhs !in needed && erasableCmd) {
                    delete("load")
                } else {
                    addQueriesTo(base, queries = listOf(Query(ptr, tf.rhsTerm(ptr, loc)!!)))
                    newNeeded = newNeeded - lhs
                    addToNeeded(loc)
                }
            }

            override fun byteSingleStore(
                lhs: TACSymbol.Var,
                base: TACSymbol.Var,
                loc: TACSymbol,
                value: TACSymbol
            ) {
                if (!areQueries && erasableCmd) {
                    delete("singleStore")
                } else {
                    // If we know the mod 32 of the query we can do better. But I'd rather play it safe here.
                    blindlyAdd()
                    addToNeeded(loc, value)
                }
            }

            override fun store(lhs: TACSymbol.Var, rhsBase: TACSymbol.Var, loc: TACSymbol, value: TACSymbol) {
                if (ptr in mustTakeBytemap || !erasableCmd) {
                    addQueriesTo(rhsBase)
                    addToNeeded(loc, value)
                    return
                }

                /** will turn to true if there is a query term that matches the store loc */
                var existsQueryMatchingLoc = false

                /** the set of query terms that don't match the store, and should be propagated back */
                val queriesNotMatchingLoc = mutableSetOf<Query>()

                val locTerm = tf.rhsTerm(ptr, loc)!!
                for (query in allQueries[ptr].orEmpty()) {
                    when (tf.areEqual(ptr, locTerm, query.ptr, query.t)) {
                        true -> existsQueryMatchingLoc = true
                        false -> queriesNotMatchingLoc += query
                        else -> { // it may or may not match
                            existsQueryMatchingLoc = true
                            queriesNotMatchingLoc += query
                        }
                    }
                }
                if (queriesNotMatchingLoc.isNotEmpty()) {
                    addQueriesTo(rhsBase, queries = queriesNotMatchingLoc)
                    if (!existsQueryMatchingLoc) {
                        // the store loc never matches anything we query on it. So let's just drop the store.
                        replace(AssignExpCmd(lhs, rhsBase), "store->assign")
                    } else {
                        addToNeeded(loc, value)
                    }
                } else {
                    if (!existsQueryMatchingLoc) { // no queries at all.
                        delete("store")
                    } else { // all queries match `loc`.
                        // We don't really need `loc`, so we can do:
                        //    replace(AssignExpCmd(lhs, simpleMapDef(value.asSym())))
                        // but this crashes skey stuff. However, if we run the inliner first, this shouldn't happen
                        // anyway. So instead of fixing, we just leave it as is:
                        addToNeeded(loc, value)
                    }
                }
            }

            override fun longstore(
                lhs: TACSymbol.Var,
                srcOffset: TACSymbol,
                dstOffset: TACSymbol,
                srcMap: TACSymbol.Var,
                dstMap: TACSymbol.Var,
                length: TACSymbol
            ) {
                if (ptr in mustTakeBytemap) {
                    blindlyAdd()
                    return
                }
                /** The queries to the [src] bytemap (not shifted) */
                val src = mutableSetOf<Query>()
                /** The queries to the [dst] bytemap */
                val dst = mutableSetOf<Query>()

                val lengthTerm = tf.rhsTerm(ptr, length)!!
                val srcOffsetTerm = tf.rhsTerm(ptr, srcOffset)!!
                val dstOffsetTerm = tf.rhsTerm(ptr, dstOffset)!!

                for (query in allQueries[ptr].orEmpty()) {
                    when (tf.isInside(query.ptr, query.t, ptr, dstOffsetTerm, dstOffsetTerm + lengthTerm)) {
                        true -> src += query
                        false -> dst += query
                        else -> {
                            src += query
                            dst += query
                        }
                    }
                }

                addQueriesTo(dstMap, queries = dst)
                addQueriesTo(srcMap, queries = src.map { it.shift(srcOffsetTerm - dstOffsetTerm) })
                when {
                    !erasableCmd -> addToNeeded(srcOffset, dstOffset, length)
                    src.isEmpty() && dst.isEmpty() -> delete("longstore")
                    src.isEmpty() -> replace(AssignExpCmd(lhs, dstMap), "longstore->assign")
                    dst.isEmpty() -> {
                        replace(AssignExpCmd(lhs, txf {
                            LongStore(
                                zeroBytemap.asSym(), // this is just bogus, we will never care what is here (it's the dst bytemap).
                                dstOffset.asSym(),
                                srcMap.asSym(),
                                srcOffset.asSym(),
                                length.asSym()
                            )
                        }), "longstore->srcOnly")
                        addToNeeded(srcOffset, dstOffset, length)
                    }

                    else -> addToNeeded(srcOffset, dstOffset, length)
                }
            }

            override fun mapDefinition(lhs: TACSymbol.Var, param: TACSymbol.Var, definition: TACExpr) {
                if (areQueries || !erasableCmd) {
                    blindlyAdd()
                } else {
                    delete("mapDef")
                }
            }

            override fun havoc(lhs: TACSymbol.Var) {
                if (!areQueries && erasableCmd) {
                    delete("havoc")
                }
            }
        }

        cmdHandler.handle(cmd)
        return newNeeded
    }

    companion object {
        fun go(code: CoreTACProgram, filteringFunctions: FilteringFunctions) =
            BytemapConeOfInf(code, filteringFunctions).rewrite()
    }

}
