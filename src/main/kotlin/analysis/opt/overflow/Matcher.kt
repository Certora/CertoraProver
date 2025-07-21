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

package analysis.opt.overflow

import algorithms.topologicalOrder
import analysis.CmdPointer
import analysis.LTACCmd
import analysis.TACCommandGraph
import analysis.dataflow.OverApproxUseAnalysis
import analysis.opt.overflow.OverflowKey.Companion.matchedDst
import analysis.opt.overflow.OverflowKey.Companion.revertDst
import analysis.opt.overflow.OverflowKey.MATCHED_LCMD
import analysis.opt.overflow.OverflowKey.NEGATED
import analysis.patterns.Info
import analysis.patterns.Info.Companion.set
import analysis.patterns.get
import datastructures.*
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import log.*
import tac.NBId
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACCmd.Simple.JumpiCmd
import vc.data.TACMeta
import vc.data.TACSymbol

private val logger = Logger(LoggerTypes.OVERFLOW_PATTERN_REWRITER)

/**
 * Tries to find a recipe for this [context], looking for the patterns either before or after the operation
 * and either encoded in assume conditions or in jump conditions.
 */
class Matcher<T : OverflowContext>(
    val code: CoreTACProgram,
    val context: T,
    val useAnalysis: OverApproxUseAnalysis
) {
    private val g = code.analysisCache.graph

    /** Carries the information from the matching of [recipe] needed for the rewrite */
    interface Match<T : OverflowContext> {

        /** the matched recipe */
        val recipe: Recipe<T>

        /**
         * For each pattern in the recipe, the payload of the pattern matching process for this pattern. This contains
         * all sorts of information, like the pointer of the jump or assume where the pattern matches, possibly the
         * width implied by this pattern being matched, etc.
         */
        val infos: OverflowInfo<T>

        val width get() = recipe.width(infos) ?: EVM_BITWIDTH256

        /** For and [Assume] match, it doesn't matter if we went forward or backward */
        data class Assume<T : OverflowContext>(
            override val recipe: Recipe<T>,
            override val infos: OverflowInfo<T>
        ) : Match<T>

        sealed class JumpI<T : OverflowContext> : Match<T> {
            /** operation is in the first block */
            data class Forward<T : OverflowContext>(
                override val recipe: Recipe<T>,
                override val infos: OverflowInfo<T>,
                /**
                 * The set of variables that use the result of the operation (and those that use them, etc).
                 * This is needed, because we have to make sure that this result is not used in the revert blocks.
                 */
                val outflow: Set<TACSymbol.Var>
            ) : JumpI<T>()

            /** operation is in the last block */
            data class Backward<T : OverflowContext>(
                override val recipe: Recipe<T>,
                override val infos: OverflowInfo<T>
            ) : JumpI<T>()

            /**
             * Each pattern in the matched recipe has a block whose final jumpi command fits the pattern.
             * This keeps a map from these blocks to the payload of the pattern that was matched.
             */
            private val blockToInfo by lazy {
                infos.values.associateBy { it[MATCHED_LCMD]!!.ptr.block }
            }

            /**
             * In the backward case the last block is the one with the multiplication.
             * In the forward case will also include the block after all checks are done. multiplication will be in the first.
             */
            private val blocksInOrder by lazy {
                topologicalOrder(
                    nodes = blockToInfo.keys,
                    nexts = { blockToInfo[it]?.matchedDst?.let(::listOf) }
                ).reversed()
            }

            val firstBlock by lazy { blocksInOrder.first() }
            val lastBlock by lazy { blocksInOrder.last() }

            /** all except first and last */
            val intermediateBlocks by lazy { blocksInOrder.subList(1, blocksInOrder.lastIndex) }

            val revertBlocks by lazy {
                blocksInOrder
                    .subList(0, blocksInOrder.lastIndex) // all except the last block
                    .map { blockToInfo[it].revertDst }
                    .distinct() // remove repetitions
            }
        }
    }

    /** From (pattern, isNegated) -> actual-query (not the answer to the query) */
    private val queryCache = memoized { pattern: OverflowPattern<T>, negated: Boolean ->
        pattern.genQuery(g, context, negated)
    }

    fun go() =
        matchAssumes(forward = true)
            ?: matchAssumes(forward = false)
            ?: matchJumpConds(forward = true)
            ?: matchJumpConds(forward = false)


    /** Seeing such a command should stop the matching process */
    private fun isBadCmd(lcmd: LTACCmd): Boolean {
        val (ptr, cmd) = lcmd
        return when (cmd) {
            is TACCmd.Simple.AssertCmd -> cmd.o != TACSymbol.True
            is TACCmd.Simple.AssigningCmd -> context.isBasicVar(cmd.lhs)
            is TACCmd.Simple.SummaryCmd -> g.succ(ptr).size == 2
            else -> false
        }
    }

    /** If there are a few matches, pick the one with smallest width, and the most patterns (so we erase more) */
    private fun bestOf(matches: List<Match<T>>): Match<T>? {
        if (matches.isEmpty()) {
            return null
        }
        val minWidth = matches.minOf { it.width }
        return matches
            .filter { it.width == minWidth }
            .maxBy { it.recipe.patterns.size }
    }

    /**
     * Looks for a recipe that matches the assumes directly after (or before, if [forward] = false) the operation.
     */
    private fun matchAssumes(forward: Boolean): Match<T>? {
        val recipes = Recipe.recipesOf(context)

        /** For each recipe, which patterns were matched, and their [Info] */
        val recipeInfo = mutableNestedMapOf<Recipe<T>, OverflowPattern<T>, Info>()

        val matched = mutableListOf<Match<T>>()

        val ptrSeq =
            if (forward) {
                g.blockCmdsForwardFrom(context.ptr + 1)
            } else {
                g.blockCmdsBackwardFrom(context.ptr - 1)
            }
        ptrSeq.forEach { lcmd ->
            val cmd = lcmd.cmd
            if (isBadCmd(lcmd)) {
                return bestOf(matched)
            }
            val v = if (cmd is TACCmd.Simple.AssumeCmd && cmd.cond is TACSymbol.Var) {
                cmd.cond
            } else {
                return@forEach
            }

            recipes.forEach { r ->
                r.patterns
                    .filterNot {
                        // we've seen it already
                        it in recipeInfo[r]?.keys.orEmpty()
                    }
                    .mapNotNull {
                        it `to?` queryCache(it, false).query(v, lcmd).toNullableResult()
                    }
                    .forEach { (pattern, info) ->
                        recipeInfo[r, pattern] = info.set(MATCHED_LCMD, lcmd)!!
                        if (recipeInfo[r]!!.size == r.patterns.size) {
                            matched += Match.Assume(r, recipeInfo[r]!!)
                        }
                    }
            }
        }
        return bestOf(matched)
    }


    /**
     * Looks for a recipe that matches the jump conditions directly after (or before, if [forward] = false) the
     * operation in question.
     */
    private fun matchJumpConds(forward: Boolean): Match<T>? {
        var remainingRecipes = Recipe.recipesOf(context)

        /** For each recipe, which patterns were matched, and their [Info] */
        val recipeInfo = mutableNestedMapOf<Recipe<T>, OverflowPattern<T>, Info>()

        val ptrSeq = if (forward) {
            generateSequenceAfter(context.ptr) { ptr ->
                val succs = g.succ(ptr)
                when (succs.size) {
                    0 -> null
                    1 -> succs.single().takeIf {
                        // there are not supposed to be any trivial block chains in our patterns. This counts
                        // on `BlockMerger` being run first.
                        it.block == ptr.block
                    }
                    else -> {
                        val nextBlock = remainingRecipes.mapToSet { r ->
                            // get the info for the pattern that matched this block, and from it, the non-revert next block.
                            // this relies on the "laziness" of the sequence, and so if we got to this point, each
                            // remaining recipe must have a pattern that matched the jumpi command at `ptr`.
                            recipeInfo[r]!!.values.singleOrNull { it[MATCHED_LCMD]!!.ptr == ptr }!!.matchedDst
                        }
                        when (nextBlock.size) {
                            0 -> null
                            1 -> nextBlock.single()
                                .takeIf { g.pred(it).size == 1 }
                                ?.let { CmdPointer(it, 0) }
                            else -> {
                                logger.warn { "Different overflow patterns match in negated ways: $this" }
                                null
                            }
                        }
                    }
                }
            }
        } else {
            generateSequenceAfter(context.ptr) { ptr ->
                g.pred(ptr).singleOrNull()?.takeIf {
                    it.block == ptr.block || g.toCommand(it) is JumpiCmd
                }
            }
        }

        /**
         * Which variables use the result of the operation (including indirectly) to define them. This is used for
         * checking that the operation's result is never used on the revert branch. Without this check we can't push
         * the operation to be after the checks, which is what is needed to simplify it in the forward case.
         */
        val outflow = if (forward) {
            mutableSetOf(context.lhs)
        } else {
            // in the backward case we don't care about it.
            mutableSetOf()
        }

        val matched = mutableListOf<Match<T>>()
        fun bestMatch() = bestOf(matched.filter(::noDangerousWrites))

        ptrSeq.forEach { ptr ->
            val lcmd = g.elab(ptr)
            val cmd = lcmd.cmd
            if (isBadCmd(lcmd)) {
                return bestMatch()
            }
            cmd.getLhs()?.let {
                if (outflow containsAny cmd.getFreeVarsOfRhs()) {
                    outflow += it
                }
            }
            val v = (cmd as? JumpiCmd)?.cond as? TACSymbol.Var
                ?: return@forEach

            fun query(pattern: OverflowPattern<T>, negated: Boolean): Info? =
                queryCache(pattern, negated).query(v, lcmd).toNullableResult()
                    .set(NEGATED, negated)
                    .set(MATCHED_LCMD, lcmd)

            // note the difference, when matching jump conditions, a recipe must match all the jump conditions
            // we see. In assumes, we allow assumes that we don't care about.
            remainingRecipes = remainingRecipes.filter { recipe ->
                var shouldKeep = false
                recipe.patterns.asSequence()
                    .filterNot {
                        // already matched
                        it in recipeInfo[recipe]?.keys.orEmpty()
                    }
                    .firstNotNullOfOrNull {
                        // try the query as is, and in negated form
                        it `to?` (query(it, false) ?: query(it, true))
                    }
                    ?.takeIf { (_, info) ->
                        // check that the revert blocks are indeed revert blocks. This is actually not necessary,
                        // but the case where we do the non-deterministic branching may cause strange behavior if these
                        // are not really revert blocks.
                        g.isRevertBlock(info.revertDst) &&
                            // when going forward, check that the operation result is never used on the "revert" branch.
                            outflow.all {
                                useAnalysis.useSitesAtOrAfter(it, info.revertDst).isEmpty()
                            }
                    }
                    ?.let { (pattern, info) ->
                        recipeInfo[recipe, pattern] = info
                        val ri = recipeInfo[recipe]!!
                        if (ri.size == recipe.patterns.size) {
                            matched += if (forward) {
                                Match.JumpI.Forward(recipe, ri, outflow.toSet())
                            } else {
                                Match.JumpI.Backward(recipe, ri)
                            }
                        } else {
                            // we found a match, but there are still patterns to match to get the full recipe.
                            shouldKeep = true
                        }
                    }
                shouldKeep
            }
            if (remainingRecipes.isEmpty()) {
                return bestMatch()
            }
        }
        // we can reach here if we've reached the end of the program but some recipes are still hoping for redemption.
        return bestMatch()
    }


    /** See "subtle points" in the doc of [OverflowPatternRewriter] */
    private fun noDangerousWrites(match: Match<T>): Boolean {
        check(match is Match.JumpI)
        g.lcmdSequence(match.intermediateBlocks)
            .forEach { (_, cmd) ->
                cmd.getLhs()?.let { lhs ->
                    // this is different than "outflow" above, because this is about any assignment in the intermediate
                    // blocks, and not only the result of the multiplication.
                    if (match.revertBlocks.any { useAnalysis.useSitesAtOrAfter(lhs, it).isNotEmpty() }) {
                        logger.warn { "Overflow Recipe $match discarded because of assignment used in revert block" }
                        return false
                    }
                }
                if (cmd is TACCmd.Simple.AssumeCmd && cmd.cond != TACSymbol.True) {
                    logger.warn { "Overflow Recipe $match discarded because of assume in intermediate block" }
                    return false
                }
            }
        return true
    }

    companion object {
        private val revertAnnotations = setOf(TACMeta.REVERT_PATH, TACMeta.RETURN_PATH)

        private fun TACCommandGraph.isRevertBlock(b: NBId) =
            lcmdSequence(b).any { (_, cmd) ->
                (cmd as? TACCmd.Simple.AnnotationCmd)?.annot?.k?.let { it in revertAnnotations } == true
            }
    }
}
