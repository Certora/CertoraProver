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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package prefixgen.selection

import bridge.Method
import com.certora.collect.*
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import utils.*
import java.math.BigInteger
import kotlin.math.pow
import kotlin.random.Random
import datastructures.stdcollections.*
import prefixgen.selection.ICommonRandomOptions.Companion.levelTerminationProbability
import prefixgen.selection.ICommonRandomOptions.Companion.seed
import prefixgen.selection.WeightedStrategy.Companion.t

/**
 * File for different Selector strategies. The structure is a *little* complex due to needing support CLI and programming
 * configuration.
 */

/**
 * Every pre-built [Selector] implementation here needs at least this information: are repeats allowed in the sequence ([allowsRepeats])
 * and what sighashes (if any) must be included in any valid sequence [mustInclude].
 *
 * NB that [mustInclude] means *at least one* function from this set must be included.
 */
interface ICommonSelectorOptions {
    val allowsRepeats: Boolean
    val mustInclude: Set<String>
}

/**
 * An implementation of [ICommonSelectorOptions] which are set via the command line via clikt.
 */
class CommonSelectorOptions : OptionGroup("Common options for selectors"), ICommonSelectorOptions {
    override val allowsRepeats by option(
        help = "Allow repeats in the sequence"
    ).flag(default = false)

    override val mustInclude by option(
        help = "Sighash of methods at least one of which must be included somewhere in the sequence"
    ).multiple(default = listOf()).unique()
}

/**
 * The basic logic for any [SelectorGenerator].
 */
interface BasicSelectorGenerator : SelectorGenerator, ICommonSelectorOptions {
    /**
     * Returns a function that indicates what methods are valid at the given level.
     * This choice is determined by [allowsRepeats], [currSelection], [mustInclude], and
     * the [currLevel].
     */
    fun inclusionPred(
        currLevel: Int,
        maxDepth: Int,
        currSelection: List<String>
    ) : (Method) -> Boolean = if(allowsRepeats && mustInclude.isEmpty()) {
        /**
         * We can repeat and we don't have to ensure anything is included. YOLO, any method is fine
         */
        { _ -> true }
    } else if(!allowsRepeats && (currLevel != (maxDepth - 1) || mustInclude.isEmpty())) {
        /**
         * We can't allow repeats AND we don't care about must include (either because
         * we're not at the last level, or the user provided no must include functions).
         */
        val currSet = currSelection.toSet();
        /**
         * In this case, allow any function that doesn't already appear
         */
        { m: Method ->
            m.sighash!! !in currSet
        }
    } else {
        /**
         * We care about mustInclude; we are at the last level and we have functions that gotta be included.
         */
        check(mustInclude.isNotEmpty() && currLevel == (maxDepth - 1))
        val satisfiesMustInclude = currSelection.any {
            it in mustInclude
        }
        /**
         * If we don't allow repeats AND we already have a necessary function,
         * just allow unincluded functions.
         */
        if(!allowsRepeats && satisfiesMustInclude) {
            { m: Method ->
                m.sighash!! !in currSelection
            }
        } else {
            /**
             * Otherwise, allow any function in mustInclude.
             * NB that we don't need to worry about repeats here:
             * if there WAS a danger of a repeat, then one of the functions in mustInclude would be in
             * [currSelection], in which case satisfiesMustInclude would be true and we wouldn't be
             * in this branch.
             */
            { m: Method ->
                m.sighash!! in mustInclude
            }
        }
    }

}

/**
 * A selector strategy chosen and configured on the command line. `name` is the name of the option group, and `commonOpts` are the
 * common selector options configured via [CommonSelectorOptions].
 */
sealed class SelectorStrategyCLI(commonOpts: CommonSelectorOptions, name: String) : OptionGroup(name), BasicSelectorGenerator, ICommonSelectorOptions by commonOpts

/**
 * A mixin implementing the "enumeration" strategy. Simply enumerate every possible choice at each level according
 * to the [allowsRepeats] and [mustInclude] constraints.
 */
interface EnumerationStrategy : BasicSelectorGenerator {
    override fun selectorFor(universe: List<Method>): Selector {
        return Selector { level, maxDepth, currSelection ->
            val pred = inclusionPred(currLevel = level, maxDepth = maxDepth, currSelection = currSelection)
            universe.asSequence().filter(pred)
        }
    }
}

/**
 * An instantiation of the [EnumerationStrategy] chosen on the command line, using the [CommonSelectorOptions] to control the [allowsRepeats]
 * and [mustInclude] parameters.
 */
class EnumerateCLI(commonOpts: CommonSelectorOptions) : SelectorStrategyCLI(commonOpts, "Enumerate all sequences"), EnumerationStrategy

/**
 * The common parameters for any random generation strategy.
 */
interface ICommonRandomOptions {
    /**
     * If non-null, any non-0 level is abandoned with probability [levelTerminationProbability]
     */
    val levelTerminationProbability : Double?

    /**
     * What the random instance used for generation
     */
    val random: Random

    companion object {
        /**
         * Helper function for allowing controlling the random seed on the command line
         */
        fun ParameterHolder.seed() = option(
            help = "Seed to use for the random process"
        ).int().default(0)

        /**
         * Helper function for allowing controlling the [levelTerminationProbability] on the command line.
         */
        fun ParameterHolder.levelTerminationProbability() = option(
            help = "Probability that the current (partial) sequence is discarded and a new one tried. Default: (currLen / maxLen)^2"
        ).double().restrictTo(min = 0.0, max = 1.0).validate { p ->
            require(0.0 < p && p < 1.0) {
                "Cannot be 0 or 1"
            }
        }
    }
}

/**
 * An implementation of the [CommonRandomOptions] which is configured via the command line. [random]
 * is instantiated with [seed].
 */
class CommonRandomOptions : OptionGroup("Common random options"), ICommonRandomOptions {
    val seed by seed()

    override val levelTerminationProbability by levelTerminationProbability()
    override val random: Random by lazy {
        Random(seed)
    }
}

/**
 * A random strategy configured and selected via the command line.
 * The name of the option group is given by `name`; the [allowsRepeats] and [mustInclude] parameters are controlled
 * by the dedicated command line group [CommonSelectorOptions], and the common random parameters are controlled by the
 * [CommonRandomOptions].
 *
 * Thus by delegation to [CommonRandomOptions] and the delegation to [CommonSelectorOptions] via the [SelectorStrategyCLI],
 * any subclass of this type implements [ICommonRandomOptions] and [BasicSelectorGenerator]
 */
sealed class RandomStrategyCLI(name: String, commonOpts: CommonSelectorOptions, commonRandom: CommonRandomOptions) : SelectorStrategyCLI(commonOpts, name), ICommonRandomOptions by commonRandom

/**
 * Mixin to any type that implements [ICommonRandomOptions] and [BasicSelectorGenerator]
 * providing the framework for randomized generatiion via [levelGenerator].
 */
interface RandomSelector : BasicSelectorGenerator, ICommonRandomOptions {
    /**
     * Given a function [gen] that (randomly) generates [Method] objects,
     * generate a sequence which yields those method objects.
     * At level 0, this sequence never terminates. At higher levels, the sequence will terminate (forcing exploration
     * of different levels) with `terminationProb`.
     *
     * `terminationProb` is either [levelTerminationProbability] if non-null, or (level / maxDepth) ^ 2.
     * Thus, at level 2/3, the current level will terminate with approximate 4/9.
     *
     * If `gen()` ever returns null (at any level) the sequence terminates (this usually indicates there are no valid
     * methods to generate).
     * With this caveat, at any level, at least one [Method] is yielded.
     */
    fun levelGenerator(
        level: Int,
        maxDepth: Int,
        gen: () -> Method?
    ) = sequence<Method> {
        val terminationProb = levelTerminationProbability?.takeIf { level != 0 } ?: ((level / maxDepth.toDouble())).pow(2)
        var yielded = false
        while(true) {
            if(yielded) {
                val term = random.nextDouble()
                if (term < terminationProb) {
                    return@sequence
                }
            }
            val selected = gen() ?: return@sequence
            yield(selected)
            yielded = true
        }
    }
}

/**
 * A mixing for any type implementing the same interfaces required by [RandomSelector] which implements
 * the uniform selection strategy. At every level, the candidates chosen by the [inclusionPred]
 * are chosen with uniform probability.
 */
interface UniformSelector : RandomSelector {
    override fun selectorFor(universe: List<Method>): Selector {
        return object : Selector {
            private fun uniformSampler(u: List<Method>) = if(u.isEmpty()) {
                null
            } else {
                { ->
                    u[random.nextInt(u.size)]
                }
            }

            override fun generateChoice(level: Int, maxDepth: Int, currSelection: List<String>): Sequence<Method> {
                val levelSample = universe.filter(inclusionPred(level, maxDepth, currSelection))
                return levelGenerator(level, maxDepth, uniformSampler(levelSample) ?: return sequenceOf())
            }
        }
    }
}

/**
 * An instantiation of the [UniformSelector] whose parameters are controlled via the command line by the [RandomStrategyCLI]
 * type (though which it implements the necessary interfaces for [UniformSelector]).
 */
class Uniform(commonOpts: CommonSelectorOptions, commonRandom: CommonRandomOptions) : RandomStrategyCLI(
    name = "Uniform sampling from universe",
    commonOpts = commonOpts,
    commonRandom = commonRandom
), UniformSelector

/**
 * A mixin for any type which implements the interfaces required by [RandomSelector]
 * so that it implements a weight selection strategy.
 *
 * Under the weighted selection strategy, each method is assigned a level from 0.
 * If m1 is at level l1, and m2 is at level l2 where l1 < l2, then m2 is [t]^(l2 - l1)x more
 * likely than m1 to be selected. Here [t] is the weighted parameter. This can be used
 * to bias selection towards functions which haven't appeared yet in a trace (although
 * this only makes sense in the context of [allowsRepeats] being true).
 */
interface WeightedStrategy : RandomSelector {
    val t: Int

    /**
     * Assigns a level to methods, keyed by the method's sighash.
     * The default level is 0.
     */
    val levels: Map<String, Int>

    companion object {
        fun ParameterHolder.t() = option(
            help = "Weight determining relative probability between tiers. For two methods m1 and m2 in level l1 and l2 where l1 < l2," +
                "then m2 is t^(l2 -l1)x more likely to be sampled than m1"
        ).int().required().validate {
            require(it > 1) {
                "t must be greater than 1"
            }
        }
    }

    private val Method.selector get() = this.sighash!!

    override fun selectorFor(universe: List<Method>): Selector {
        /**
         * Compute the weights for each method according to their levels.
         *
         * We do this by assigning each method mi the weight t^li where li is the
         * level for method i.
         *
         * Let N = sum(t^li). Then conceptually each method is assigned probability
         * t^li / N. First, observe that the probabilities sum to 1 (that's good!)
         * and we have the "X times more likely property). Namely for l1 and l2 as
         * defined above we will have that:
         * (t^l1 / N) * k = (t^l2 / N)
         * multiplying out N, and solving for `k` we get:
         * k = t^(l2 - l1)
         * Thus if l1 = 0, l2 = 1, then m2 being chosen is 2x more likely, l2 = 2 4x more likely, etc.
         *
         * However, we don't actually compute the (t^li / N) terms. Instead, we assign each method mi
         * to non-overlapping ranges in [0, N], where the range for mi has size t^li.
         * Then, to sample we simply pick L from 0 .. N, and select the mi that owns the range into which L
         * falls. Nicely enough, [TreapMap]'s make the range queries *extremely* easy via [TreapMap.floorEntry].
         */
        val rawWeights = mutableMapOf<String, Long>()
        var totalUniverseSize = BigInteger.ZERO
        val tBig = t.toBigInteger()

        /**
         * We map a [Long] to a [Method]. Each [Method] owns the range from its key l up to (but excluding)
         * the "next" key in the map (as longs have a total order).
         */
        val searchTreeBuilder = treapMapBuilderOf<Long, Method>()
        for(m in universe) {
            val weight = tBig.pow(levels[m.selector] ?: 0)
            val l = weight.toLong()
            if(l.toBigInteger() != weight) {
                throw IllegalStateException("Weight is too large: $t ^ $weight for ${m.name} overflows Long")
            }
            searchTreeBuilder[totalUniverseSize.longValueExact()] = m
            totalUniverseSize += weight
            rawWeights[m.selector] = l
        }
        if(totalUniverseSize > Long.MAX_VALUE.toBigInteger()) {
            throw IllegalStateException("Total universe weights exceed size bound")
        }
        val totalUniverseSizeL = totalUniverseSize.toLong()
        val searchTree = searchTreeBuilder.build()
        return object : Selector {
            override fun generateChoice(level: Int, maxDepth: Int, currSelection: List<String>): Sequence<Method> {
                val pred = inclusionPred(level, maxDepth, currSelection)
                return levelGenerator(level, maxDepth) gen@{
                    /**
                     * Rather than recompute the weights at each level, just randomly pick until we get a method
                     * that satisfies the predicate from [inclusionPred].
                     *
                     * After 10 tries (picked "heuristically") we do proper sampling.
                     */
                    var it = 0
                    while(it < 10) {
                        it++
                        val key = random.nextLong(totalUniverseSizeL)
                        val m = searchTree.floorEntry(key) ?: continue
                        if(pred(m.value)) {
                            return@gen m.value
                        }
                    }
                    val validCands = universe.filter(pred)
                    if(validCands.isEmpty()) {
                        return@gen null
                    }
                    val bound = validCands.sumOf {
                        rawWeights[it.selector]!!
                    }
                    val ind = random.nextLong(bound)
                    var currBound = 0L
                    for(m in validCands) {
                        currBound += rawWeights[m.selector]!!
                        if(ind < currBound) {
                            return@gen m
                        }
                    }
                    `impossible!`
                }
            }
        }
    }
}

/**
 * A [WeightedStrategy] whose [t] and [levels] are controlled by command line options, and whose [ICommonSelectorOptions] and [ICommonRandomOptions] are
 * controlled by the groups [CommonSelectorOptions] and [CommonRandomOptions].
 */
class Weighted(commonOpts: CommonSelectorOptions, commonRandom: CommonRandomOptions) : RandomStrategyCLI("Weight sampling from universe", commonOpts, commonRandom), WeightedStrategy {
    override val t by t()

    override val levels by option(
        help = "The level to associate with some sighash. Can be repeated, the default level for a sighash is 0"
    ).associateWith("=") { s ->
        val ret = s.toInt()
        require(ret >= 0)
        ret
    }
}
