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

package debugger

import annotations.PollutesGlobalState
import cli.SanityValues
import config.*
import datastructures.stdcollections.*
import infra.CallTraceInfra
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import report.calltrace.CallTrace
import report.calltrace.printer.DebugAdapterProtocolStackMachine
import utils.*
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.Path

class EVMDebugAdapterCallTraceTests {

    /**
     * Tests that the stack size only increases and then decreases.
     * This holds for methods that are just calling a single function.
     */
    @ParameterizedTest
    @MethodSource("monotonicIncDecInputs")
    fun testCallFrameMonotonicity(config: SolidityConfig) {
        val debugAdapter = configToResult[config]!!;
        Assertions.assertTrue(debugAdapter.isMonotoicIncreasingAndThenDecreasing()) { "Expected sequence of frames to be monotonically increasing and then decreasing, but got ${debugAdapter.getCallTrace().map { it.frames.size }}" }
    }

    /**
     * Tests that the call stack has a specific list of line numbers on top of the stack
     * at some point in time. I.e. given a list of
     */
    @ParameterizedTest
    @MethodSource("topOfStackTestInputs")
    fun topOfStackTests(input: Pair<SolidityConfig, List<List<StackSel>>>) {
        val config = input.first
        val topOfStacks = input.second
        val debugAdapter = configToResult[config]!!
        assert(debugAdapter.sequenceOfCallStackMatching(topOfStacks)) {
            "Did not find list of stack elements: ${topOfStacks}\n" +
                debugAdapter.getCallTrace().toLineNumberRepresentation()
        }
    }


    /**
     * A simple test that a variable is present _at one_ stack frame along the entire trace.
     */
    @ParameterizedTest
    @MethodSource("displaysVariableSomewhere")
    fun displaysVariableSomewhere(input: Pair<SolidityConfig, Set<String>>) {
        val debugAdapter = configToResult[input.first]!!;
        val unmatchedVariables = debugAdapter.unmatchedVariables(input.second)
        Assertions.assertTrue(unmatchedVariables.isEmpty()) { "Did not find variable with names $unmatchedVariables in test, all variables are: \n ${debugAdapter.getAllVariables().joinToString("\n")} " }
    }

    /**
     * A test that a variable is present at a specific stack location matched by the line numbers of the call stack at this location
     */
    @ParameterizedTest
    @MethodSource("displaysVariableAtStack")
    fun displaysVariableAtStackTest(input: Pair<SolidityConfig, ICallStackMatcher>) {
        val debugAdapter = configToResult[input.first]!!;
        debugAdapter.applyCallStackMatcher(input.second)
    }


    /**
     * Test that a specific line number perform a push operations.
     * This means, for a line number <ln> with stack size <size>
     *
     * 1. The stack after the push operation is always larger than <size + 1>
     * 2. There exists a pop operation, meaning the next time the stack is of size <size> again,
     * <ln> must be on top.
     */
    @ParameterizedTest
    @MethodSource("callStackPushAndPopTest")
    fun callStackPushAndPopTest(input: Pair<SolidityConfig, Set<UInt>>) {
        val debugAdapter = configToResult[input.first]!!

        val results = input.second.map { expectedLine ->
            debugAdapter.getCallTrace().foldIndexed(PushOperation.Unmatched(expectedLine)) { idx, res: PushOperation, step ->
                val currFrameSize = step.frames.size
                when (res) {
                    PushOperation.Correct -> PushOperation.Correct
                    is PushOperation.Incorrect -> res
                    is PushOperation.CallSiteMatched -> {
                        if (currFrameSize > res.stackFrame) {
                            PushOperation.CalleeMatched(res.stepIdx, res.stackFrame)
                        } else if (currFrameSize == res.stackFrame) {
                            res
                        } else {
                            PushOperation.Incorrect(expectedLine, res.stepIdx, idx)
                        }
                    }

                    is PushOperation.Unmatched -> {
                        if (step.topFrameMatchesLine(expectedLine)) {
                            PushOperation.CallSiteMatched(idx, currFrameSize)
                        } else {
                            PushOperation.Unmatched(expectedLine)
                        }
                    }

                    is PushOperation.CalleeMatched -> {
                        if (currFrameSize > res.stackFrame) {
                            res
                        } else if (currFrameSize == res.stackFrame && step.topFrameMatchesLine(expectedLine)) {
                            PushOperation.Correct
                        } else {
                            PushOperation.Incorrect(expectedLine, res.stepIdx, idx)
                        }
                    }
                }
            }
        }

        println(debugAdapter.getCallTrace().toLineNumberRepresentation(false))

        val unmatched = results.filterIsInstance<PushOperation.Unmatched>()
        val incorrect = results.filterIsInstance<PushOperation.Incorrect>()
        assert(unmatched.isEmpty() && incorrect.isEmpty()) {
            "Unmatched should be empty, got: ${unmatched}\n" +
                "Incorrect should be empty, got: ${incorrect}\n" +
                debugAdapter.getCallTrace().toLineNumberRepresentation(false)
        }
    }

    sealed class PushOperation {
        data class Unmatched(val expectedLine: UInt) : PushOperation()
        object Correct : PushOperation()
        data class Incorrect(val expectedLine: UInt, val startIdx: Int, val endIdx: Int) : PushOperation()
        data class CallSiteMatched(val stepIdx: Int, val stackFrame: Int) : PushOperation()
        data class CalleeMatched(val stepIdx: Int, val stackFrame: Int) : PushOperation()
    }

    companion object {

        private val allRegisteredConfigs = mutableSetOf<SolidityConfig>()

        /**
         * A filter operation, use this to filter out config for debugging.
         * To only execute the test case "storageReset", you can use { c: Config -> c.ruleName == "storageReset"}
         */
        private val filterByConfig = { _: SolidityConfig -> true }

        /**
         * If debug mode is enabled, the output is dumped to the emv-* folders.
         */
        private const val DEBUG_MODE: Boolean = false

        /**
         * After [beforeAll] has been executed, this maps the config to the complete call traces for look up by the tests above
         */
        private val configToResult = mutableMapOf<SolidityConfig, DebugAdapterProtocolStackMachine>()

        data class SolidityConfig(val ruleName: String,
                                  val methodName: String? = null,
                                  val confPath: Path = baseTestCasePath.resolve("Functions/Default.conf"),
                                  val specPath: Path = baseTestCasePath.resolve("Functions/simple.spec"),
                                  val primaryContract: String = "Contract") {
            init {
                if (filterByConfig(this)) {
                    allRegisteredConfigs.add(this)
                }
            }
        }


        private val baseTestCasePath = Path("src/test/resources/solver/DebuggerTests/")

        /**
         * List of configs that we test for
         */
        private val add: SolidityConfig = SolidityConfig(ruleName = "add")
        private val add_withLibrary = SolidityConfig(ruleName = "add_withLibrary", confPath = baseTestCasePath.resolve("Libraries/C.conf"), specPath = baseTestCasePath.resolve("Libraries/C.spec"))
        private val addSummarizedByCVLFunction: SolidityConfig = SolidityConfig(ruleName = "addSummarizedByCVLFunction")
        private val addSummarizedNyNondet: SolidityConfig = SolidityConfig(ruleName = "addSummarizedNyNondet")
        private val addToStorage: SolidityConfig = SolidityConfig(ruleName = "addToStorage")
        private val callCVLFunction: SolidityConfig = SolidityConfig(ruleName = "callCVLFunction")
        private val callCVLFunctionOnce: SolidityConfig = SolidityConfig(ruleName = "callCVLFunctionOnce")
        private val hookExample_getSomeField: SolidityConfig = SolidityConfig(ruleName = "hookExample", methodName = "getSomeField()", confPath = baseTestCasePath.resolve("Hooks/Default.conf"), specPath = baseTestCasePath.resolve("Hooks/simple.spec"))
        private val hookExample_setSomeField: SolidityConfig = SolidityConfig(ruleName = "hookExample", methodName = "setSomeField(uint256)", confPath = baseTestCasePath.resolve("Hooks/Default.conf"), specPath = baseTestCasePath.resolve("Hooks/simple.spec"))
        private val onlyCVLVariables: SolidityConfig = SolidityConfig(ruleName = "onlyCVLVariables")
        private val onlyCVLVariablesParameter: SolidityConfig = SolidityConfig(ruleName = "onlyCVLVariablesParameter")
        private val storageReset: SolidityConfig = SolidityConfig(ruleName = "storageReset", confPath = baseTestCasePath.resolve("StorageReset/C.conf"), specPath = baseTestCasePath.resolve("StorageReset/C.spec"), primaryContract = "C")
        private val sumCoordinates: SolidityConfig = SolidityConfig(ruleName = "sumCoordinates")
        private val sumCoordinatesInternal: SolidityConfig = SolidityConfig(ruleName = "sumCoordinatesInternal")
        private val updateGhost: SolidityConfig = SolidityConfig(ruleName = "updateGhost")


        @JvmStatic
        fun displaysVariableSomewhere(): Stream<Pair<SolidityConfig, Set<String>>> {
            return Stream.of(
                add to setOf("x", "y", "z", "a", "b"),
                addToStorage to setOf("x", "z", "a", "Contract.storageResult"),
                callCVLFunction to setOf("x", "y", "z"),
                callCVLFunctionOnce to setOf("x", "y"),
                onlyCVLVariablesParameter to setOf("y"),
                sumCoordinates to setOf("point.x", "point.y", "t", "a", "b"),
                sumCoordinatesInternal to setOf("point.x", "point.y", "t", "t", "a", "b"),
                updateGhost to setOf("foo"),

                ).filter { filterByConfig(it.first) }
        }


        @JvmStatic
        fun topOfStackTestInputs(): Stream<Pair<SolidityConfig, List<List<StackSel>>>> {
            return listOf(
                callCVLFunctionOnce to listOf(
                    listOf(
                        CVLFunc(20U),
                        Rule(25U)
                    )
                ),
                callCVLFunctionOnce to listOf(
                    listOf(
                        Rule(26U)
                    )),
                addToStorage to listOf(
                    listOf(
                        SolFunc(14U),
                        /*duplicated*/
                        SolFunc(),
                        Rule(68U)
                    ),
                    listOf(
                        SolFunc(15U),
                        SolFunc(),
                        Rule(68U)
                    )
                ),
                addSummarizedByCVLFunction to listOf(
                    listOf(
                        Summary(11U),
                        SolFunc(),
                        /*duplicated*/
                        SolFunc(),
                        Rule()
                    )
                )
            ).filter { filterByConfig(it.first) }.stream()
        }

        @JvmStatic
        fun callStackPushAndPopTest(): Stream<Pair<SolidityConfig, Set<UInt>>> {
            return Stream.of(
                callCVLFunctionOnce to setOf(25U),
                callCVLFunction to setOf(31U, 32U),
                add to setOf(50U), //Issue solvable by adding push operation in CVL*/
            ).filter { filterByConfig(it.first) }
        }

        @JvmStatic
        fun monotonicIncDecInputs(): Stream<SolidityConfig> {
            return Stream.of(
                callCVLFunctionOnce,
                onlyCVLVariables,
                add,
                sumCoordinates,
                sumCoordinatesInternal,
                addToStorage,
                addSummarizedByCVLFunction,
                addSummarizedNyNondet,
                updateGhost
            ).filter { filterByConfig(it) }
        }


        @JvmStatic
        fun displaysVariableAtStack(): Stream<Pair<SolidityConfig, CallStackMatcher>> {
            /**
             * We have too many commands that push onto the call stack. This needs cleanup elsewhere.
             * The stack elements that are pushed duplicated by "*duplicated*".
             */
            return listOf(
                callCVLFunctionOnce to CallStackMatcher(
                    Rule(26U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("x")
                    matchedCallStack.assertContainsVariable("y")
                },

                callCVLFunctionOnce to CallStackMatcher(
                    CVLFunc(20U),
                    Rule(25U))
                { matchedCallStack ->
                    matchedCallStack.assertNotContainsVariable("x")
                    matchedCallStack.assertContainsVariable("a")
                },

                sumCoordinates to CallStackMatcher(
                    SolFunc(19U),
                    SolFunc(23U),
                    /*duplicated*/  SolFunc(22U),
                    Rule(56U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("a")
                    matchedCallStack.assertContainsVariable("b")
                },

                sumCoordinates to CallStackMatcher(
                    Rule(57U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("z")
                },

                addToStorage to CallStackMatcher(
                    SolFunc(14U),
                    /*duplicated*/ SolFunc(12U),
                    Rule(68U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("Contract.storageResult")
                    matchedCallStack.assertContainsVariable("a")
                },

                add_withLibrary to CallStackMatcher(
                    SolFunc(6U),
                    /*duplicated*/ SolFunc(5U),
                    Rule(8U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("x")
                    matchedCallStack.assertContainsVariable("y")
                },

                add_withLibrary to CallStackMatcher(
                    SolFunc(5U),
                    SolFunc(6U),
                    /*duplicated*/ SolFunc(5U),
                    Rule(8U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("a")
                    matchedCallStack.assertContainsVariable("b")
                },

                updateGhost to CallStackMatcher(
                    CVLFunc(89U),
                    Rule(94U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("foo")
                },
                hookExample_setSomeField to CallStackMatcher(
                    Hook(5U),
                    SolFunc(7U),
                    /*duplicated*/ SolFunc(7U),
                    Rule(19U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("hook_store_oldValue")
                    matchedCallStack.assertContainsVariable("hook_store_newValue")
                },
            ).filter { filterByConfig(it.first) }.stream()
        }

        @OptIn(PollutesGlobalState::class)
        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            ConfigScope(Config.CallTraceDebugAdapterProtocol, DebugAdapterProtocolMode.VARIABLES)
                .extend(Config.CallTraceHardFail, HardFailMode.ON)
                .extend(Config.DoSanityChecksForRules, SanityValues.NONE)
                .letIf(DEBUG_MODE) {
                    CommandLineParser.setExecNameAndDirectory()
                    ConfigType.WithArtifacts.set(log.ArtifactManagerFactory.WithArtifactMode.WithArtifacts)
                    it
                }
                .use {
                    allRegisteredConfigs.map { conf ->
                        // We are currently running this multiple times, if one conf has several rules, we can merge this.
                        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
                            confPath = conf.confPath,
                            specFilename = conf.specPath,
                            ruleName = conf.ruleName,
                            parametricMethodNames = conf.methodName?.let { listOf(it) }.orEmpty(),
                            primaryContract = conf.primaryContract,
                        )

                        check(callTrace is CallTrace.ViolationFound || callTrace is CallTrace.Failure) { "No violation found for config $conf" }
                        val debugAdapter = callTrace.debugAdapterCallTrace
                        check(debugAdapter != null)
                        configToResult[conf] = debugAdapter
                    }
                }
        }
    }
}