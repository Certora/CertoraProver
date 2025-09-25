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

package spec.cvlast

import config.Config
import config.Config.containsMethod
import config.Config.splitToContractAndMethod
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import scene.CONSTRUCTOR
import spec.CVLReservedVariables
import utils.CollectingResult.Companion.asError
import java.util.*
import kotlin.math.abs
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.NoSuchMethodChoice
import utils.*
import utils.CollectingResult.Companion.flattenToVoid
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok

private val logger = Logger(LoggerTypes.SPEC)


fun validateMethodChoices(knownFunctions: List<ContractFunction>, mainContract: String): VoidResult<CVLError> =
    validateMethodChoicesParametric(knownFunctions, mainContract, Config.MethodChoices, Config.ExcludeMethodChoices)
fun validateRangerMethodChoices(knownFunctions: List<ContractFunction>, mainContract: String): VoidResult<CVLError> =
    validateMethodChoicesParametric(knownFunctions, mainContract, Config.RangerMethodChoices, Config.RangerExcludeMethodChoices)
@Suppress("ForbiddenMethodCall")
private fun validateMethodChoicesParametric(knownFunctions: List<ContractFunction>, mainContract: String, choices: Set<String>?, excludeChoices: Set<String>?): VoidResult<CVLError> {
    val relevantFunctions = knownFunctions
        .filter { it.methodSignature.functionName !in listOf(CONSTRUCTOR, CVLReservedVariables.certorafallback_0.name) }
        .mapNotNull {
            // not having method info here means this function isn't public
            it.evmExternalMethodInfo
        }
        .filter { !it.isLibrary }
        .mapToSet { it.toExternalABINameWithContract() }

    fun validateSet(s: Set<String>?): VoidResult<CVLError> {
        if (s == null) {
            return ok
        }

        return s.map { methodChoice ->
            val (contract, method) = methodChoice.splitToContractAndMethod(mainContract)
            val found = relevantFunctions.containsMethod(method, contract, mainContract)

            if (found) {
                ok
            } else {
                val suggestions = getClosestStrings(
                    methodChoice,
                    relevantFunctions
                )
                NoSuchMethodChoice(methodChoice, suggestions).asError()
            }
        }.flattenToVoid()
    }

    return validateSet(choices).map(validateSet(excludeChoices))
}

/**
 * Makes sure that if the user used --rule <rulename> and/or --rules <rule1,rule2,...> , that all such rules exists.
 * If just one rule does not exist, throw an exception with an appropriate error message.
 * Additionally, if there are similarly named rules to the rule name(s) the user provided, we will suggest using
 * them, as this is a probable user input mistake.
 */
fun validateRuleChoices(cvlAst: CVLAst) : VoidResult<CVLError> {
    val allRules = cvlAst.rules.map { it.declarationId }.toSet() + cvlAst.invs.map { it.id }.toSet() +
        cvlAst.useDeclarations.builtInRulesInUse.map { it.id }

    // First, check if the user provided any non-existent rules.
    val unknownRules =  Config.getNonPatternRuleChoices()?.let { it - allRules }
    if (unknownRules?.isNotEmpty() == true) {
        // one or more chosen rules (not patterns) were not found

        logger.info {"Could not find rules: $unknownRules. Starting to offer replacement rules names. " +
            "All rule names are $allRules" }
        var errStr = "The rules: $unknownRules were not found in any of the specification files."

        val allSuggestions = unknownRules.flatMap { getClosestStrings(it, allRules) }
        if (allSuggestions.size == 1){
            errStr += " Did you mean rule ${allSuggestions[0]}?"
        } else if (allSuggestions.size >= 2) {
            errStr += " Did you mean rule ${allSuggestions[0]} or ${allSuggestions[1]}?"
        }

        return CVLError.General(Range.Empty(), errStr).asError()
    }

    // Now, check if the rule patterns actually match anything at all.
    val ruleChoices = Config.getRuleChoices(allRules)
    if (allRules.isNotEmpty() && ruleChoices.isEmpty()) {
        return CVLError.General(Range.Empty(), "the '--rule' and '--exclude_rule' patterns filtered out all rules").asError()
    }

    return ok
}

// A tuple to store a word and its distance from the input word, stored in the priority queue
data class DistanceToName(val distance: Int, val name: String)

/**
 * Gets an input word, which doesn't belong to a dictionary of predefined words, and returns a list of closest words
 * from the dictionary, with respect to a distance function.
 * @param illegalWord The word we look for closest matches of.
 * @param legalWords A collection of words to suggest matches from.
 * @param distanceFunc The distance function we use to measure proximity of words.
 * @param maxDist The maximal distance between words, over which no suggestions will be made.
 * @param maxDistRatio A maximal ratio between the distance and the input word's length. No suggestions will be made
 * over this ratio.
 * @param maxSuggestions The maximal number of suggestions to return.
 * @param maxDelta We stop giving suggestions if the next best suggestion is worse than the one before it by more
 * than the maximal delta.
 * @return A list of suggested words, ordered from the best match to the worst.
 */
fun getClosestStrings (
    illegalWord: String,
    legalWords: Set<String>,
    distanceFunc: (String, String) -> Int = ::inputStringDistance,
    maxDist: Int = 40,
    maxDistRatio: Double = 0.5,
    maxSuggestions: Int = 2,
    maxDelta: Int = 2) :
        Set<String> {
    val compareByDistance = Comparator<DistanceToName> { t1 : DistanceToName, t2 : DistanceToName
        -> t1.distance - t2.distance}
    val distanceQueue = PriorityQueue<DistanceToName>(compareByDistance)
    for (legalWord in legalWords) {
        val distance = distanceFunc(illegalWord, legalWord)
        distanceQueue.add(DistanceToName(distance, legalWord))
    }

    val allSuggestions = mutableSetOf<String>()
    var lastDist = Int.MAX_VALUE

    while (!distanceQueue.isEmpty() && allSuggestions.size <= maxSuggestions) {
        val currentTuple = distanceQueue.poll()
        val currDist = currentTuple.distance
        if (currDist > maxDist || currDist / (illegalWord.length * 10.0) > maxDistRatio) {
            // The distances are monotonically increasing
            break
        }
        if (lastDist == Int.MAX_VALUE || abs(currDist - lastDist) <= maxDelta) {
            allSuggestions.add(currentTuple.name)
            lastDist = currDist
        }
    }

    return allSuggestions
}

/**
 * Calculates a modified levenshtein distance between two strings. The distance function is modified to penalize less
 * for more common user mistakes.
 * Each subtraction, insertion or replacement of a character adds 1 to the distance of the two strings, unless:
 * 1. The input string is a prefix of the dictionary string or vice versa - the distance is 0.1 per extra letter.
 * 2. The replacement is between two equal letter except casing - adds nothing to the distance
 * 3. The subtraction/addition is of an underscore, adds 0.1 to the distance
 * 4. Repeated characters cost nothing, e.g. 'baloon', 'balloon' and 'balllllloooonn' have distance 0 from each other
 *
 * @param _input_str the string the user gave as input, error-prone
 * @param _dict_str a legal string we compare the wrong input to
 * @return a distance measure between the two string. A low number indicates a high probably the user to give the
 * dictionary string as input
 */
@Suppress("ForbiddenMethodCall")
fun inputStringDistance(_input_str: String, _dict_str: String): Int {
    val inputStr = _input_str.lowercase()
    val dictStr = _dict_str.lowercase()
    if (inputStr == dictStr) { return 0 }
    if (inputStr.startsWith(dictStr) || dictStr.startsWith(inputStr)) {
        return abs(dictStr.length - inputStr.length)
    }

    /**
    We are calculating the Levenshtein distance with a dynamic programming algorithm based on
    https://en.wikipedia.org/wiki/Levenshtein_distance

    Each matrix value distance_matrix`[`row`]``[`col`]` we calculate represents the distance between the two prefix substrings
    input_str[0..row-1] and dictionary_str[0..col-1]

    NOTE: our implementation differs from the classic implementation in that the cost of deletions/insertions is not
    constant

    The cost for substitution is 10, not one, to allow for mistakes with a cost an order of magnitude smaller
     */

    // Initialize matrix of zeros
    val numRows = inputStr.length + 1
    val numCols = dictStr.length + 1
    val distanceMatrix = Array(numRows) { IntArray(numCols) }

    // Populate matrix of zeros with the indices of each character of both strings
    for (row in 1 until numRows) {
        distanceMatrix[row][0] = row
    }
    for (col in 1 until numCols) {
        distanceMatrix[0][col] = col
    }

    // Calculate modified Levenshtein distance
    for (row in 1 until numRows) {
        for (col in 1 until numCols) {
            val inputChar = inputStr[row-1]
            val dictChar = dictStr[col-1]
            val cost = if (inputChar == dictChar) {
                // No cost if the characters are the same up to casing in the two strings
                0
            } else if (inputChar == '_' || dictChar == '_') {
                // common mistake
                1
            } else {
                // full cost
                10
            }
            distanceMatrix[row][col] = cost + minOf(distanceMatrix[row-1][col],    // Cost of deletions
                                                    distanceMatrix[row][col-1],    // Cost of insertions
                                                    distanceMatrix[row-1][col-1])  // Cost of substitutions
        }
    }
    return distanceMatrix[numRows-1][numCols-1]
}
