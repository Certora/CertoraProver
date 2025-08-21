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

package cli

import cli.SanityValues.*
import config.HardFailMode
import smt.*
import smt.HashingScheme
import solver.*
import java.io.Serializable
import java.math.BigInteger
import java.util.*


/**
 * Converts a raw string value (as when parsing command line arguments) and returns the proper type for the
 * configuration.
 */
class Converter<T>(val convert: (String) -> T)

class ConversionException(raw: String, src: Class<*>) :
    Exception("Conversion error converting value $raw by ${src.name}")

class ConversionIntegerMistakenAsBool(b: Boolean) :
    Exception("Expected an integer value, but got a boolean $b")

val StringConverter = Converter { it }
val IntConverter = Converter {
    it.toIntOrNull() ?: handleConfusedIntAsBool(it, BigInteger::class.java)
}
val BigIntConverter = Converter<BigInteger> {
    it.toBigIntegerOrNull() ?: handleConfusedIntAsBool(it, BigInteger::class.java)
}

// throws an error if the user provided a boolean instead of an integer to a int-like config
private fun handleConfusedIntAsBool(s: String, intClass: Class<*>): Nothing {
    // a common mistake is true/false instead of an integer...
    val asBool = stringToBool(s)
    if (asBool != null) {
        throw ConversionIntegerMistakenAsBool(asBool)
    } else {
        throw ConversionException(s, intClass)
    }
}

private fun stringToBool(s: String) = when (s) {
    "true" -> true
    "false" -> false
    else -> null
}

val BooleanConverter = Converter {
    stringToBool(it) ?: throw ConversionException(it, Boolean::class.java)
}

// assumes we get a list of strings delimited with ","
val StringListConverter = Converter { stringlist ->
    stringlist.split(",").takeIf { it.isNotEmpty() }?.toTypedArray() ?: throw ConversionException(stringlist, Array<String>::class.java)
}

// assumes we get a list of strings delimited with ","
val StringSetConverter = Converter { stringlist ->
    val tokenized_list = stringlist.split(",")
    // (tokenized_list.count() != tokenized_list.distinct().count()) // we may want to log this, TODO
    tokenized_list.takeIf { it.isNotEmpty() }?.toHashSet() ?: throw ConversionException(stringlist, HashSet::class.java)
}


/**
 * We need to parse `solverNames` such as "z3[randomSeed 2, learnLemmas true],cvc5_def,cvc5_nl[learnLemmas false]"
 */
val SolverProgramListConverter = Converter { parseList(it, SolverConfig.Converter()).toTypedArray() }

val CEGARConfigConverter = Converter { parseOne(it, CEGARConfig.Converter()) }

val HashingSchemeConverter = Converter {
    when (it.lowercase()) {
        HashingScheme.Legacy.CONFIG_KEYWORD.lowercase() -> HashingScheme.Legacy()
        HashingScheme.PlainInjectivity.CONFIG_KEYWORD.lowercase() -> HashingScheme.PlainInjectivity
        HashingScheme.Datatypes.CONFIG_KEYWORD.lowercase() -> HashingScheme.Datatypes
        else -> throw ConversionException(it, HashingScheme::class.java)
    }
}

val HardFailConverter = Converter {
    HardFailMode.entries
        .find { mode -> mode.configString == it.lowercase() }
        ?: throw ConversionException(it, HardFailMode::class.java)
}

val ConstraintChooserConverter = Converter {
    when (it.lowercase()) {
        "justbools" -> ConstraintChooserEnum.justBools
        "takeall" -> ConstraintChooserEnum.takeAll
        "boolsandmanymore" -> ConstraintChooserEnum.boolsAndManyMore
        "fewboolsandmanymore" -> ConstraintChooserEnum.fewBoolsAndManyMore
        "boolsandsomemore" -> ConstraintChooserEnum.boolsAndSomeMore
        else -> throw ConversionException(it, ConstraintChooserEnum::class.java)
    }
}

val PrettifyCEXConverter = Converter {
    when (it){
        "none" -> PrettifyCEXEnum.NONE
        "false" -> PrettifyCEXEnum.NONE //keeping false and true for backward compatibility
        "basic" -> PrettifyCEXEnum.BASIC
        "true" -> PrettifyCEXEnum.BASIC
        "joint" -> PrettifyCEXEnum.JOINT
        "extensive" -> PrettifyCEXEnum.EXTENSIVE
        else -> throw ConversionException(it, PrettifyCEXEnum::class.java)
    }
}

val MultipleCEXStrategyConverter = Converter {
    when (it.lowercase()){
        "none" -> MultipleCEXStrategyEnum.NONE
        "basic" -> MultipleCEXStrategyEnum.BASIC
        "advanced" -> MultipleCEXStrategyEnum.ADVANCED
        else -> throw ConversionException(it, MultipleCEXStrategyEnum::class.java)
    }
}

val CoverageInfoConverter = Converter {
    CoverageInfoEnum.entries.singleOrNull { v -> v.name.lowercase() == it }
        ?: throw ConversionException(it, CoverageInfoEnum::class.java)
}

val SanityModeConverter = Converter {
    when (it.lowercase()) {
        "none" -> NONE
        "basic" -> BASIC
        "advanced" -> ADVANCED
        else -> throw ConversionException(it, SanityValues::class.java)
    }
}

val EcosystemConverter = Converter {
    when (it.lowercase()) {
        "evm" -> Ecosystem.EVM
        "solana" -> Ecosystem.SOLANA
        "soroban" -> Ecosystem.SOROBAN
        "sui" -> Ecosystem.SUI
        else -> throw ConversionException(it, Ecosystem::class.java)
    }
}

enum class SplitOrderEnum {
    DFS, BFS
}

val SplitOrderConverter = Converter {
    when (it.lowercase()) {
        "dfs" -> SplitOrderEnum.DFS
        "bfs" -> SplitOrderEnum.BFS
        else -> throw ConversionException(it, SplitOrderEnum::class.java)
    }
}

enum class SplitHeuristicEnum {
    NON_LINEAR, SIZE_ONLY
}

val SplitHeuristicConverter = Converter {
    when (it.lowercase()) {
        "nonlinear" -> SplitHeuristicEnum.NON_LINEAR
        "size" -> SplitHeuristicEnum.SIZE_ONLY
        else -> throw ConversionException(it, SplitHeuristicEnum::class.java)
    }
}

enum class InvariantType(val msg: String) {
    WEAK("weak"), STRONG("strong")
}

val InvariantTypeConverter = Converter {
    when (it.lowercase()) {
        InvariantType.WEAK.msg -> InvariantType.WEAK
        InvariantType.STRONG.msg -> InvariantType.STRONG
        else -> throw ConversionException(it, InvariantType::class.java)
    }
}
/*
val ValueOracleCodeConverter = Converter {
    when (it){
       "random" -> ValueOracleCode.RANDOM
       "symbolic" -> ValueOracleCode.SYMBOLIC
        else -> throw ConversionException(it, ValueOracleCode::class.java)
    }
}

val NonLinearReductionCodeConverter = Converter {
    when (it){
        "randomcmds" -> NonlinearReductionCode.RANDOM_CMDS
        "allops" -> NonlinearReductionCode.ALL_OPS
        "percent" -> NonlinearReductionCode.PERCENT
        else -> throw ConversionException(it, NonlinearReductionCode::class.java)
    }
}

val AssumeHandlingStrategyConverter = Converter {
    when (it.lowercase()){
        "every", "everyassume" -> AssumeHandlingStrategy.CheckEveryAssume
        "branchandassert", "branchandassertonly" -> AssumeHandlingStrategy.BranchAndAssertOnly
        else -> throw ConversionException(it, AssumeHandlingStrategy::class.java)
    }
}

val PathChoosingStrategyConverter = Converter {
    when (it.lowercase()) {
        "firstsymbolic" -> PathChoosingStrategyCodes.FirstPathSymbolic
        "firstrandom" -> PathChoosingStrategyCodes.FirstPathRandom
        "leastresistancerandom" -> PathChoosingStrategyCodes.LeastResistanceFirstRandom
        "leastresistancesymbolic" -> PathChoosingStrategyCodes.LeastResistanceFirstSymbolic
        else -> throw ConversionException(it, PathChoosingStrategyCodes::class.java)
    }
}*/

/**
 * Enum to represent the possible sanity-check states.
 * [NONE] represents a state where none of the sanity checks is run.
 * [BASIC] represents a state where only basic sanity checks are run.
 * [ADVANCED] represents a state where all the sanity checks are run.
 */
enum class SanityValues {
    // order does matter (this is the order of how many levels of sanity checks we want to run)
    NONE,
    BASIC,
    ADVANCED
    ;
}

/** Ecosystem used for a verification task. */
enum class Ecosystem : Serializable {
    EVM,
    SOROBAN,
    SOLANA,
    SUI
}

/**
 * @property runCalleeAnalysis Indicates whether this summarization mode requires running the callee analysis
 */
enum class SummaryResolutionPolicy(val runCalleeAnalysis: Boolean) {
    /*
     * Inlines all eligible summaries in each round of summarization
     */
    SIMPLE(false),
    /*
     * Delay inlining dispatcher summaries until other summaries have been inlined
     */
    TIERED(true),
    /*
     * delay inlining dispatcher summaries and aut-havocs until other summaries have been inlined
     */
    AGGRESSIVE(true)
}
