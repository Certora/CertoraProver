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

package analysis.opt

import analysis.*
import analysis.patterns.Info
import analysis.patterns.InfoKey
import analysis.patterns.PatternHelpers
import analysis.patterns.get
import config.Config
import datastructures.add
import datastructures.buildMultiMap
import datastructures.getOrPut
import datastructures.mutableNestedMapOf
import datastructures.stdcollections.*
import log.*
import org.jetbrains.annotations.TestOnly
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldTo
import java.math.BigInteger


private val logger = Logger(LoggerTypes.PATTERN_REWRITER)


/**
 * Rewrite rules:
 */
class PatternRewriter private constructor(private val prog: CoreTACProgram) {
    companion object {

        /** runs twice, we can run till a fixed point, but I doubt it's worth the effort */
        fun rewrite(
            prog: CoreTACProgram,
            patternList : PatternRewriter.() -> List<PatternHandler>,
            repeat : Int = Config.patternRewriter.get()
        ): CoreTACProgram {
            var result = prog
            repeat(repeat) {
                val pr = PatternRewriter(result)
                result = pr.rewrite(pr.patternList())
                if (pr.stats.isEmpty()) {
                    return result
                }
            }
            return result
        }

        /** Just for testing purposes */
        fun rewriteStats(prog: CoreTACProgram, patternList : PatternRewriter.() -> List<PatternHandler>) =
            PatternRewriter(prog).let {
                it.rewrite(it.patternList())
                it.stats
            }
    }

    private val g = prog.analysisCache.graph
    private val def = prog.analysisCache.strictDef
    private val patcher = ConcurrentPatchingProgram(prog)
    private val txf = TACExprFactUntyped

    @TestOnly
    val stats = ConcurrentCounterMap<String>()

    val statsForRegression = ConcurrentCounterMap<String>()

    sealed class Key<K> : InfoKey<K>() {
        data object Cond : Key<LTACSymbol>()
        data object A : Key<LTACSymbol>()
        data object B : Key<LTACSymbol>()
        data object C : Key<LTACSymbol>()
        data object D : Key<LTACSymbol>()
        data object Xored : Key<LTACSymbol>()
        data object C1 : Key<BigInteger>()
        data object C2 : Key<BigInteger>()
        data object I1 : Key<Int>()
    }

    private val bypassVarsCache = mutableNestedMapOf<CmdPointer, TACSymbol.Var, TACExpr.Sym.Var>()

    /**
     * Returns a symbol that is sure to be equal to the one in [key], but queried at [queryPoint]
     * Instead of doing def analysis checks, we create a new variable that captures the original variable value.
     */
    private fun symAtQuery(info: Info, queryPoint: CmdPointer, key: Key<LTACSymbol>): TACExpr.Sym {
        val (ptr, sym) = info[key]!!
        if (sym !is TACSymbol.Var || ptr == queryPoint) {
            return sym.asSym()
        }
        return synchronized(bypassVarsCache) {
            bypassVarsCache.getOrPut(ptr, sym) {
                val newVar = patcher.newTempVar("bypass", sym.tag)
                patcher.prependBefore(
                    ptr,
                    listOf(AssignExpCmd(newVar, sym))
                )
                newVar.asSym()
            }
        }
    }

    /**
     * One of these per pattern.
     * Creating one of these necessitates the following:
     * [pattern], which is the pattern we are looking for. It's an extension function of [PatternHelpers] so that
     *   all its functions are available.
     * [handle], that is run on a [Context] which holds all the information from the pattern matching, specifically
     *   an [Info] object. If it returns a non-null [TACExpr], then that is unfolded to 3-address-form and eventually
     *   replaces the rhs at the matched command.
     * [baseTACExprs] are the java-classes of the top level [TACExpr] that are possible. It's used for picking out only
     *   the patterns that have a chance.
     */
    inner class PatternHandler(
        val name: String,
        val pattern: PatternHelpers.() -> PatternMatcher.Pattern<Info>,
        val handle: Context.() -> TACExpr?,
        vararg baseTACExprs: Class<out TACExpr>,
        val regressionMessage : Boolean = false,
    ) {
        val baseTACExprs = baseTACExprs.toList()

        val query = PatternMatcher.compilePattern(g, PatternHelpers.pattern())

        fun go(lcmd: LTACCmdView<AssignExpCmd>): Boolean {
            val (ptr, cmd) = lcmd
            val info = query.queryFrom(lcmd).toNullableResult() ?: return false
            Context(info, ptr, cmd).handle()?.let { expr ->
                stats.plusOne(name)
                if (regressionMessage) {
                    statsForRegression.plusOne(name)
                }
                val newCmd = unfoldTo(expr, cmd.lhs)
                patcher.replace(ptr, newCmd)
                logger.debug {
                    "$name --- $ptr : $cmd\n" + newCmd.cmds.joinToString { "    $it\n" }
                }
                return true
            }
            return false
        }

        inner class Context(val info: Info, val ptr: CmdPointer, val cmd: AssignExpCmd) : TACExprFact by txf {
            /** used to check if two rhs symbols are the same */
            fun src(key: Key<LTACSymbol>) =
                info[key]!!.let {
                    def.source(it.ptr, it.symbol)
                }

            /** Returns a current (i.e., at [ptr]) equivalent version of the given symbol at the the given location. */
            fun sym(key: Key<LTACSymbol>) =
                symAtQuery(info, ptr, key)

            val Key<BigInteger>.asTACExpr
                get() = info[this]!!.asTACExpr

            val Key<BigInteger>.n
                get() = info[this]!!

            val Key<Int>.n
                get() = info[this]!!
        }
    }


    /**
     * Entry point.
     */
    fun rewrite(patternsList: List<PatternHandler>): CoreTACProgram {
        val handlersMap = buildMultiMap {
            for (h in patternsList) {
                for (k in h.baseTACExprs) {
                    add(k, h)
                }
            }
        }

        prog.parallelLtacStream()
            .mapNotNull { it.maybeNarrow<AssignExpCmd>() }
            .forEach { lcmd ->
                // If one handler actually rewrites, we stop searching on this command.
                handlersMap[lcmd.cmd.rhs.javaClass]?.firstOrNull {
                    it.go(lcmd)
                }
            }

        logger.info {
            stats.toString("${javaClass.simpleName} - ${prog.name}")
        }
        logger.trace {
            patcher.debugPrinter().toString(prog, javaClass.simpleName)
        }
        statsForRegression.toMap().forEachEntry { (name, i) ->
            Logger.regression { "${prog.name} : Pattern-Rewriter replaced $i occurrences of $name" }
        }
        return patcher.toCode()
    }

}
