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

package verifier

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.icfg.ExtCallSummarization
import analysis.icfg.Inliner
import analysis.icfg.Summarizer
import analysis.narrow
import com.certora.collect.*
import config.Config
import config.Config.MethodChoices
import config.ReportTypes
import datastructures.stdcollections.*
import diagnostics.*
import log.*
import report.*
import rules.IsFromCache
import rules.ResultAndTime
import rules.RuleCheckResult
import rules.VerifyTime
import scene.IContractClass
import scene.IScene
import scene.ITACMethod
import solver.SolverResult
import spec.CVLKeywords
import spec.rules.AssertRule
import spec.cvlast.CVLScope
import spec.rules.IRule
import spec.cvlast.RuleIdentifier
import spec.toVar
import tac.DumpTime
import tac.StartBlock
import tac.Tag
import vc.data.*
import verifier.ContractUtils.tacOptimizations
import verifier.ContractUtils.transformMethod
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

private val logger = Logger(LoggerTypes.COMMON)
private val loggerTimes = Logger(LoggerTypes.TIMES)

open class SolidityVerifier(val scene: IScene) : Verifier() {
    val reporters = listOf(JSONReporter(Config.OutputJSONFile), HTMLReporter)

    private var _lastTime: Long? = null

    private suspend fun computeResultsSafety(
        contractName: String,
        functions: Collection<ITACMethod>
    ): Map<String, VerifierResult?> {

        // We want to process, and for each function, add !lastHasThrown as sink
        val contractResults = mutableMapOf<String, VerifierResult?>()
        functions.forEach { func ->
            inCodeAsync(func) {
                logger.info { "Working on function ${func.code.name}" }
                try {
                    fun instrumentForAssertions(method: ITACMethod): CoreTACProgram {
                        val icore = method.code as CoreTACProgram
                        val lastHasNotThrown = TACKeyword.TMP(Tag.Bool).toUnique()
                        val lastHasThrown = CVLKeywords.lastHasThrown.toVar(Tag.Bool)
                        return (if (icore.code.isEmpty()) {
                            require(icore.blockgraph.isEmpty())
                            icore.copy(
                                code = mapOf(Pair(StartBlock, listOf())),
                                blockgraph = BlockGraph(StartBlock to treapSetOf())
                            )
                        } else {
                            icore
                        }).addNotThrownInTheBeginning()
                            .addSinkMainCall(
                                listOf(
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                        lastHasNotThrown,
                                        TACExpr.UnaryExp.LNot(lastHasThrown.asSym())
                                    ),
                                    TACCmd.Simple.AssertCmd(
                                        lastHasNotThrown,
                                        "Assertion check"
                                    )
                                )
                            )
                            .first
                    }

                    val transformed = transformMethod(
                        func,
                        ChainedMethodTransformers(
                            listOf(
                                CoreToCoreTransformer(ReportTypes.UNROLL, ContractUtils::unroll).lift(),
                                MethodToCoreTACTransformer(ReportTypes.INLINED) {
                                    Inliner.inlineDirectCallsInMethod(scene, it)
                                },
                                MethodToCoreTACTransformer(ReportTypes.CFG) {
                                    val icore = it.code as CoreTACProgram
                                    val code = icore.code
                                    if(!code.any {
                                        it.value.any {
                                            it is TACCmd.Simple.SummaryCmd && it.summ is CallSummary
                                        }
                                    }) {
                                        it.code
                                    } else {
                                        val patch = icore.toPatchingProgram()
                                        code.entries.parallelStream().flatMap {
                                            it.value.mapIndexedNotNull { index, simple ->
                                                if (simple is TACCmd.Simple.SummaryCmd && simple.summ is CallSummary) {
                                                    LTACCmd(
                                                        CmdPointer(it.key, index),
                                                        simple
                                                    ).narrow<TACCmd.Simple.SummaryCmd>()
                                                } else {
                                                    null
                                                }
                                            }.stream()
                                        }.sequential().forEach {
                                            Summarizer.inlineAutoHavocAsProverDefault(
                                                patch,
                                                s = scene,
                                                summ = it,
                                                currInstanceId = func.getContainingContract().instanceId
                                            )
                                        }
                                        patch.toCode(icore)
                                    } as CoreTACProgram
                                },
                                MethodToCoreTACTransformer(ReportTypes.NONE, ::instrumentForAssertions)
                            ) +
                                    tacOptimizations().l.map { it.lift<ITACMethod>() }
                        )
                    ).code as CoreTACProgram
                    ArtifactManagerFactory().dumpMandatoryCodeArtifacts(transformed, ReportTypes.SOL_METHOD_WITH_ASSERTS, StaticArtifactLocation.Outputs, DumpTime.AGNOSTIC)
                    val startTime = System.currentTimeMillis()
                    val vRes = TACVerifier.verify(
                        scene,
                        transformed,
                        liveStatsReporter = DummyLiveStatsReporter,
                        rule = IRule.createDummyRule(transformed.name),
                    )
                    val endTime = System.currentTimeMillis()
                    contractResults[func.code.name] = vRes
                    addToReports(func.code.name, reporters, ResultAndTime(vRes, VerifyTime.WithInterval(startTime, endTime)))
                } catch (e: Exception) {
                    Logger.alwaysError("Failed to handle method ${func.name} in ${contractName}", e)
                    contractResults[func.code.name] = null
                }
            }
        }

        return contractResults
    }

    private fun getFunctions(contractClass: IContractClass): Collection<ITACMethod> {
        val functionsOfContract = contractClass.getDeclaredMethods()
        val methodChoices = MethodChoices
        val functions = if (methodChoices != null) {
            functionsOfContract.filter { methodChoices.any { m -> m == it.soliditySignature } }
        } else {
            functionsOfContract
        }

        return functions
    }

    override suspend fun runVerifierOnFile(fileName: String) {

        val start = System.currentTimeMillis()

        val results = mutableMapOf<String, Map<String, VerifierResult?>>()

        scene.mapContractMethodsInPlace("resolution_pass") { scene, m ->
            ExtCallSummarization.annotateCallsAndReturns(scene, m)
        }

        scene.getContracts().forEach { contract ->
            val contractName = contract.name
            logger.info { "Working on contract $contractName" }

            val functions = getFunctions(contract)
            if (functions.isEmpty()) {
                Logger.always("No code for $contractName, skipping", respectQuiet = true)
            } else {
                results[contractName] = computeResultsSafety(contractName, functions)
            }
        }

        val end = System.currentTimeMillis()
        _lastTime = end - start
        loggerTimes.info { "Time for all contracts, processing and verification: ${(end - start) / 1000} seconds" }

        // TODO: Hot updates
        results.forEach { contractResults ->
            Logger.always("Results for ${contractResults.key}:", respectQuiet = true)
            contractResults.value.forEach { (name, verifierResult) ->
                val result = verifierResult?.finalResult ?: SolverResult.UNKNOWN
                Logger.always("    Result for ${name}: ${result.toCSVRepresentation()}", respectQuiet = true)
            }
            Logger.always("", respectQuiet = true)
        }

        if (results.all { contractResults -> contractResults.value.all { methodResult -> methodResult.value?.finalResult == SolverResult.UNSAT } }) {
            Logger.always("assertions successfully verified on all inputs", respectQuiet = true)
        } else {
            Logger.always("An assertion may be violated", respectQuiet = true)
        }
    }

    private fun addToReports(
        func: String,
        reporters: List<OutputReporter>,
        resultAndTime: ResultAndTime<VerifierResult>
    ) {
        val (verifierResult, verifyTime) = resultAndTime
        val finalResult = verifierResult.finalResult
        val assertRule = CVLScope.AstScope.extendIn(CVLScope.Item::RuleScopeItem) { scope ->
            AssertRule(RuleIdentifier.freshIdentifier(func), true, func, scope)
        }
        reporters.forEach { reporter ->
            reporter.addResults(
                if (finalResult == SolverResult.SAT) {
                    RuleCheckResult.Single.WithCounterExamples(
                        rule = assertRule,
                        result = finalResult,
                        verifyTime = verifyTime,
                        ruleAlerts = null,
                        ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.WithExamplesData(
                            verifierResult.examplesInfo.map { exampleInfo ->
                                RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.CounterExample(
                                    failureResultMeta = if (exampleInfo.reason != null) {
                                        listOf(
                                            RuleCheckResult.RuleFailureMeta.ViolatedAssert(
                                                assertRule.ruleIdentifier.derivedAssertIdentifier(exampleInfo.reason),
                                                exampleInfo.reason
                                            )
                                        )
                                    } else {
                                        emptyList()
                                    },
                                    rule = assertRule,
                                    model = exampleInfo.model,
                                    dumpGraphLink = null
                                )
                            },
                            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                            isSolverResultFromCache = IsFromCache.INAPPLICABLE
                        )
                    )
                } else {
                    RuleCheckResult.Single.Basic(
                        rule = assertRule,
                        result = finalResult,
                        verifyTime,
                        ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                            details = Result.success(
                                (if (finalResult == SolverResult.UNKNOWN) {
                                    verifierResult.firstExampleInfo.errorMessage
                                        ?: "No error message provided"
                                } else {
                                    ""
                                })
                            ),
                            dumpGraphLink = null,
                            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                            isSolverResultFromCache = IsFromCache.INAPPLICABLE,
                        ),
                       ruleAlerts = null
                    )
                }
            )
            //todo: do not hot-update data we have already hot-updated (using aggregation)
            reporter.toFile(scene) // hot update
        }
    }

    override suspend fun runVerifier(tacObject : CoreTACProgram, liveStatsReporter: LiveStatsReporter) : VerifierResult {
        throw UnsupportedOperationException("runVerifier not implemented for SolidityVerifier")
    }
}
