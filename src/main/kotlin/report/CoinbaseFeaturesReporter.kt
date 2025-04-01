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

package report

import config.Config.CoinbaseFeaturesMode
import config.ConfigType
import log.*
import report.CoinbaseFeaturesReporter.FuncKey.BinaryFeature.FirstComponent
import report.CoinbaseFeaturesReporter.FuncKey.BinaryFeature.SecondComponent
import rules.RuleCheckResult
import scene.IScene
import solver.SolverResult
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.rules.SingleRuleGenerationMeta
import utils.ArtifactFileUtils
import utils.keysMatching

class CoinbaseFeaturesReporter(private val conf: ConfigType<String>) : OutputReporter {

    init {
        ArtifactManagerFactory().registerArtifact(conf)
    }

    private fun SolverResult.toFeatureRepresentation(featureName: String): SolverResult.FeatureRepresentation {
        val featureDeclaration = featureToDeclaration[featureName]
            ?: throw IllegalStateException("The feature $featureName is not declared")
        return when (this) {
            SolverResult.SAT -> {
                if (featureDeclaration.yesResult == CoinbaseFeatures.UnderlyingSolverResult.SAT) {
                    SolverResult.FeatureRepresentation.YES
                } else {
                    check(featureDeclaration.yesResult == CoinbaseFeatures.UnderlyingSolverResult.UNSAT)
                    SolverResult.FeatureRepresentation.NO
                }
            }
            SolverResult.UNSAT -> {
                if (featureDeclaration.yesResult == CoinbaseFeatures.UnderlyingSolverResult.UNSAT) {
                    SolverResult.FeatureRepresentation.YES
                } else {
                    check(featureDeclaration.yesResult == CoinbaseFeatures.UnderlyingSolverResult.SAT)
                    SolverResult.FeatureRepresentation.NO
                }
            }
            SolverResult.UNKNOWN -> {
                SolverResult.FeatureRepresentation.UNKNOWN
            }
            SolverResult.TIMEOUT -> {
                SolverResult.FeatureRepresentation.TIMEOUT
            }
            SolverResult.SANITY_FAIL -> {
                SolverResult.FeatureRepresentation.UNKNOWN
            }
        }
    }

    private fun CoinbaseFeatures.ExpectedFeatureResult.toFeatureRepresentation(): SolverResult.FeatureRepresentation =
        when (this) {
            CoinbaseFeatures.ExpectedFeatureResult.YES -> SolverResult.FeatureRepresentation.YES
            CoinbaseFeatures.ExpectedFeatureResult.NO -> SolverResult.FeatureRepresentation.NO
        }

    private inner class UnaryFunctionFeatureResults(val funcSignature: FuncKey.UnaryFeature) {
        // unary feature name -> result
        private val featureToResult: MutableMap<String, SolverResult.FeatureRepresentation> = mutableMapOf()

        operator fun get(featureName: String): SolverResult.FeatureRepresentation =
            featureToResult[featureName] ?: throw UnsupportedOperationException("Unknown feature $featureName")

        fun reportResultsForFunction(_featureToResult: Map<String, SolverResult.FeatureRepresentation>) {
            _featureToResult.forEach { (featureName, result) ->
                reportResultForFunction(featureName, result)
            }
        }

        fun deepCopy(): UnaryFunctionFeatureResults {
            val clonedInstance = UnaryFunctionFeatureResults(this.funcSignature)
            this.featureToResult.forEach { (featureName, result) ->
                clonedInstance.featureToResult[featureName] = result
            }
            return clonedInstance
        }

        fun reportResultForFunction(featureName: String, result: SolverResult.FeatureRepresentation) {
            if (featureName in featureToResult) {
                throw IllegalStateException(
                    "Was about to overwrite the result of the unary feature $featureName " +
                            "for the function ${funcSignature.funcSignature}"
                )
            }
            featureToResult[featureName] = result
        }

        fun jsonify(numTabs: Int): String {
            val outerTabString = "\t".repeat(numTabs)
            val nestedTabString = "\t".repeat(numTabs + 1)
            return "$outerTabString\"${funcSignature.funcSignature}\": {\n$nestedTabString${
                featureToResult.map { (featureName, result) ->
                    "\"$featureName\": \"${result.name}\""
                }.joinToString(separator = ",\n$nestedTabString", postfix = "\n")
            }$outerTabString}"
        }
    }

    private inner class BinaryFunctionFeatureResults(val funcSignature: FuncKey.BinaryFeature) {
        // binary feature name -> (function signature -> result)
        private val featureToOtherFuncToResult:
                MutableMap<String, MutableMap<FuncKey.BinaryFeature, SolverResult.FeatureRepresentation>> =
            mutableMapOf()

        fun reportResultForFunction(
            featureName: String,
            otherFunction: FuncKey.BinaryFeature,
            result: SolverResult.FeatureRepresentation
        ) {
            check(
                when (funcSignature) {
                    is FuncKey.BinaryFeature.FirstComponent -> otherFunction is FuncKey.BinaryFeature.SecondComponent
                    is FuncKey.BinaryFeature.SecondComponent -> otherFunction is FuncKey.BinaryFeature.FirstComponent
                }
            ) { "Expected $otherFunction to be a component distinct from $funcSignature so they form an ordered pair" }

            if (featureToOtherFuncToResult[featureName] != null &&
                otherFunction in featureToOtherFuncToResult[featureName]!!
            ) {
                throw IllegalStateException(
                    "Was about to overwrite the result of the binary feature $featureName " +
                            "for the functions ${funcSignature.funcSignature} and ${otherFunction.funcSignature}"
                )
            }
            featureToOtherFuncToResult.computeIfAbsent(featureName) { mutableMapOf() }[otherFunction] = result
        }

        fun abstractFeaturesResultsOverOtherFunctions(
            currUnaryResults: Map<FuncKey.UnaryFeature, UnaryFunctionFeatureResults>
        ): Map<String, SolverResult.FeatureRepresentation> {
            val ret: MutableMap<String, SolverResult.FeatureRepresentation> = mutableMapOf()
            this.featureToOtherFuncToResult.forEach outerLoop@{ (featureName, otherFuncsToResultsMap) ->
                val featureDeclaration = featureToDeclaration[featureName]
                    ?: throw IllegalStateException("The feature $featureName is not declared")
                when (funcSignature) {
                    is FuncKey.BinaryFeature.FirstComponent -> {
                        if (featureDeclaration.depFeatures.isNotEmpty()) {
                            /*
                            We have dep. features: we check first that their results are as expected
                             */
                            val funcCurrUnaryResults =
                                currUnaryResults[FuncKey.UnaryFeature(funcSignature.funcSignature)]
                                    ?: throw IllegalStateException(
                                        "Failed to find the results of the dep. features ${
                                            featureDeclaration.depFeatures
                                        } of the feature $featureName for the function ${
                                            funcSignature.funcSignature
                                        }"
                                    )
                            featureDeclaration.depFeatures.forEach { (depFeatureName, expectedResult) ->
                                val depFeatureResult: SolverResult.FeatureRepresentation =
                                    funcCurrUnaryResults[depFeatureName]
                                if (depFeatureResult != expectedResult.toFeatureRepresentation()) {
                                    /* we have a dep. feature with a result different than the expected one.
                                     * Therefore, we can now determine that the result for [featureName] is not "YES".
                                     */
                                    if (depFeatureResult == SolverResult.FeatureRepresentation.UNKNOWN ||
                                        depFeatureResult == SolverResult.FeatureRepresentation.TIMEOUT
                                    ) {
                                        ret[featureName] = SolverResult.FeatureRepresentation.UNKNOWN
                                    } else {
                                        ret[featureName] = SolverResult.FeatureRepresentation.NO
                                    }
                                    return@outerLoop
                                }
                            }
                        }
                        if (otherFuncsToResultsMap.any { it.value == SolverResult.FeatureRepresentation.YES }) {
                            ret[featureName] = SolverResult.FeatureRepresentation.YES
                        } else if (otherFuncsToResultsMap.any { it.value == SolverResult.FeatureRepresentation.TIMEOUT }) {
                            ret[featureName] = SolverResult.FeatureRepresentation.TIMEOUT
                        } else if (otherFuncsToResultsMap.any { it.value == SolverResult.FeatureRepresentation.UNKNOWN }) {
                            ret[featureName] = SolverResult.FeatureRepresentation.UNKNOWN
                        } else {
                            ret[featureName] = SolverResult.FeatureRepresentation.NO
                        }
                    }
                    is FuncKey.BinaryFeature.SecondComponent -> {
                        val dualFeatureName = featureDeclaration.dualFeature
                            ?: // The current feature does not have a dual feature defined - nothing to report here
                            return@outerLoop

                        val yesResults = otherFuncsToResultsMap.filter {
                            // Check whether we have a "Maybe yes"
                            it.value == SolverResult.FeatureRepresentation.YES &&
                                    // Check that the first method parameter satisfies the original feature
                                    currUnaryResults[FuncKey.UnaryFeature(it.key.funcSignature)]?.get(featureName)
                                        .let { otherFuncResult ->
                                            otherFuncResult ?: throw IllegalStateException(
                                                "While computing the result of the dual feature ${
                                                    dualFeatureName
                                                } for the function ${
                                                    funcSignature
                                                }, expected to find the result of the original feature ${
                                                    featureName
                                                } for the function ${it.key.funcSignature}"
                                            )
                                        } == SolverResult.FeatureRepresentation.YES
                        }
                        if (yesResults.isNotEmpty()) {
                            ret[dualFeatureName] = SolverResult.FeatureRepresentation.YES
                        } else { // We have no definitely yes results: we consider each "Maybe yes" result as "No"
                            if (otherFuncsToResultsMap.any { it.value == SolverResult.FeatureRepresentation.TIMEOUT }) {
                                ret[dualFeatureName] = SolverResult.FeatureRepresentation.TIMEOUT
                            } else if (otherFuncsToResultsMap.any { it.value == SolverResult.FeatureRepresentation.UNKNOWN }) {
                                ret[dualFeatureName] = SolverResult.FeatureRepresentation.UNKNOWN
                            } else {
                                ret[dualFeatureName] = SolverResult.FeatureRepresentation.NO
                            }
                        }
                    }
                }
            }
            return ret
        }
    }

    private sealed class FuncKey {

        abstract val funcSignature: String

        /**
         * Corresponds to a rule with one method parameter, f. Each concrete instantiation of f
         * is encapsulated in an [UnaryFeature] instance.
         */
        data class UnaryFeature(override val funcSignature: String) : FuncKey()

        /**
         * Corresponds to a rule with two methods parameters. These two parameters are
         * considered in the order in which they are declared in the rule signature.
         * Given an ordered pair (f,g) of two method parameters, we have that (a concrete instantiation of)
         * f is encapsulated in a [FirstComponent] instance, while (a concrete instantiation of)
         * g is encapsulated in a [SecondComponent] instance.
         */
        sealed class BinaryFeature(override val funcSignature: String) : FuncKey() {

            data class FirstComponent(override val funcSignature: String) : BinaryFeature(funcSignature)

            data class SecondComponent(override val funcSignature: String) : BinaryFeature(funcSignature)
        }
    }

    private val featureToDeclaration: Map<String, CoinbaseFeatures.FeatureDeclaration> =
        CoinbaseFeatures.deserializeFeatures().map { featureDecl ->
            featureDecl.featureName to featureDecl
        }.toMap()

    private val funcsToUnaryFeatureResults: MutableMap<FuncKey.UnaryFeature, UnaryFunctionFeatureResults> = mutableMapOf()
    private val funcsToBinaryFeatureResults: MutableMap<FuncKey.BinaryFeature, BinaryFunctionFeatureResults> = mutableMapOf()

    private fun getAllResults(): List<UnaryFunctionFeatureResults> {
        /*
         * Terminology:
         * - Unary feature: A feature that has exactly one method parameter.
         * - Binary feature: A feature that has two method parameters.
         *
         * NOTE: We assume that binary features only depend on unary features.
         * TODO: Generalize so we can have any dependencies
         * */

        /* PHASE 1: Keep [this.funcsToUnaryFeatureResults] intact by creating a fresh copy thereof.
        * */
        val currFuncsToUnaryFeatureResults =
            mutableMapOf<FuncKey.UnaryFeature, UnaryFunctionFeatureResults>().also { freshMutableMap ->
                funcsToUnaryFeatureResults.forEach { (funcKeyOfUnaryFeature, results) ->
                    freshMutableMap[funcKeyOfUnaryFeature] = results.deepCopy()
                }
            }


        /* PHASE 2: Transform each result of a binary feature into a result of an unary feature
        * by abstracting or "existentially quantifying" the second method parameter.
        * */
        funcsToBinaryFeatureResults.keysMatching {
                funcKeyOfBinaryFeature, _ -> funcKeyOfBinaryFeature is FuncKey.BinaryFeature.FirstComponent
        }.forEach { firstComponentFuncKey ->
            val funcResults = funcsToBinaryFeatureResults[firstComponentFuncKey]!!
            val abstractedResultsToUnary =
                funcResults.abstractFeaturesResultsOverOtherFunctions(funcsToUnaryFeatureResults)
            val funcKeyAsUnary = FuncKey.UnaryFeature(firstComponentFuncKey.funcSignature)
            currFuncsToUnaryFeatureResults.computeIfAbsent(funcKeyAsUnary) {
                UnaryFunctionFeatureResults(
                    funcKeyAsUnary
                )
            }
                .reportResultsForFunction(abstractedResultsToUnary)
        }

        /* PHASE 3: Transform each result of a binary feature into a result of an unary feature
          * by abstracting or "existentially quantifying" the first method parameter.
          * Each such feature is the "dual feature" of some binary feature that we have abstracted in PHASE 2.
          * Note: Determining the final result of the abstracted "dual feature" may depend on the original
          * abstracted feature (from PHASE 2).
          * */
        funcsToBinaryFeatureResults.keysMatching {
                funcKeyOfBinaryFeature, _ -> funcKeyOfBinaryFeature is FuncKey.BinaryFeature.SecondComponent
        }.forEach { secondComponentFuncKey ->
            val funcDualResults = funcsToBinaryFeatureResults[secondComponentFuncKey]!!
            val abstractedDualResultsToUnary =
                funcDualResults.abstractFeaturesResultsOverOtherFunctions(currFuncsToUnaryFeatureResults)
            if(abstractedDualResultsToUnary.isNotEmpty()) {
                val funcKeyAsUnary = FuncKey.UnaryFeature(secondComponentFuncKey.funcSignature)
                currFuncsToUnaryFeatureResults.computeIfAbsent(funcKeyAsUnary) {
                    UnaryFunctionFeatureResults(
                        funcKeyAsUnary
                    )
                }
                    .reportResultsForFunction(abstractedDualResultsToUnary)
            }
        }


        return currFuncsToUnaryFeatureResults.values.toList()
    }

    private fun jsonifyFeatureResults(numTabs: Int, allResults: List<UnaryFunctionFeatureResults>): String {
        val tabString = "\t".repeat(numTabs)
        return "\n$tabString\"features\": {\n${
            allResults.joinToString(separator = ",\n") {
                it.jsonify(numTabs + 1)
            }
        }\n$tabString}"
    }

    override fun signalStart(rule: IRule) {}

    override fun resultFilter(result: RuleCheckResult): Boolean = !matchResultsForSubRulesAndSanity(result)

    override fun addResults(results: RuleCheckResult) {
        if (CoinbaseFeaturesMode.get() && featureToDeclaration.isNotEmpty()) {
            if (results is RuleCheckResult.Multi) {
                val featureName = results.rule.declarationId
                results.results.forEach { resultForFunc ->
                    val funcNames: List<String> =
                        when (val ruleGenMeta = (resultForFunc.rule as? CVLSingleRule)?.ruleGenerationMeta) {
                            is SingleRuleGenerationMeta.WithMethodInstantiations -> ruleGenMeta.instMethodSignatures
                            SingleRuleGenerationMeta.Empty, is SingleRuleGenerationMeta.WithSanity, null -> listOf(resultForFunc.rule.declarationId)
                        }
                    when (resultForFunc) {
                        is RuleCheckResult.Single -> {
                            reportResults(funcNames, featureName, resultForFunc.result)
                        }
                        is RuleCheckResult.Error -> {
                            reportResults(funcNames, featureName, SolverResult.UNKNOWN)
                        }
                        is RuleCheckResult.Multi -> {
                            throw UnsupportedOperationException(
                                "FeatureReporter currently cannot handle nested group rules, got $resultForFunc"
                            )
                        }
                        is RuleCheckResult.Skipped -> { /* Skip  */
                            Logger.always(
                                "Skipping the rule $featureName for the methods ${funcNames}",
                                respectQuiet = true
                            )
                        }
                    }
                }
            }
        }
    }

    private fun reportResults(
        funcNames: List<String>,
        featureName: String,
        solverResult: SolverResult
    ) {
        when (funcNames.size) {
            0 -> {
                throw IllegalStateException(
                    "Expected to have at least one concrete function name, but got none."
                )
            }
            1 -> {
                funcsToUnaryFeatureResults.computeIfAbsent(FuncKey.UnaryFeature(funcNames.first())) {
                    UnaryFunctionFeatureResults(it)
                }.reportResultForFunction(
                    featureName,
                    solverResult.toFeatureRepresentation(featureName)
                )
            }
            2 -> { // Binary feature with method parameters (f,g) = (funcNames[0], funcNames[1])
                // Add the feature result for (f,g)
                funcsToBinaryFeatureResults.computeIfAbsent(
                    FuncKey.BinaryFeature.FirstComponent(
                        funcNames[0]
                    )
                ) {
                    BinaryFunctionFeatureResults(it)
                }.reportResultForFunction(
                    featureName,
                    FuncKey.BinaryFeature.SecondComponent(funcNames[1]),
                    solverResult.toFeatureRepresentation(featureName)
                )
                // Add also the dual/inverse, i.e., the feature result for (g,f)
                funcsToBinaryFeatureResults.computeIfAbsent(
                    FuncKey.BinaryFeature.SecondComponent(
                        funcNames[1]
                    )
                ) {
                    BinaryFunctionFeatureResults(it)
                }.reportResultForFunction(
                    featureName,
                    FuncKey.BinaryFeature.FirstComponent(funcNames[0]),
                    solverResult.toFeatureRepresentation(featureName)
                )
            }
            else -> {
                throw UnsupportedOperationException(
                    "Cannot handle parametric rules/features with more than two method parameters (got ${
                        funcNames
                    } for $featureName)"
                )
            }
        }
    }

    override fun getOutFile(): String = ArtifactManagerFactory().getRegisteredArtifactPath(conf)

    override fun toFile(scene: IScene) {
        if (CoinbaseFeaturesMode.get()) {
            val allResults = getAllResults()
            if (allResults.isNotEmpty()) {
                val jsonStr = "{${jsonifyFeatureResults(1, allResults)}\n}"
                ArtifactManagerFactory().useArtifact(conf.get()) { handle ->
                    ArtifactFileUtils.getWriterForFile(handle, overwrite = true).use { i ->
                        i.append(jsonStr)
                    }
                }
            }
        }
    }

    override fun hotUpdate(scene: IScene) {}
}
