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


package solver


import config.Config
import config.ConfigScope
import datastructures.stdcollections.*
import infra.CallTraceInfra
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import report.calltrace.CallTrace
import report.calltrace.printer.*
import utils.*
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.collections.listOf
import kotlin.collections.setOf
import kotlin.collections.toMutableSet
import kotlin.io.path.Path

@OptIn(ExperimentalUnsignedTypes::class)
class DebugAdapterCallTraceTest {

    private fun List<Statement>.toLineNumberRepresentation(withVariables: Boolean = true) = this.mapIndexed { idx, el ->
        idx to (el.frames to (if (withVariables) {
            el.variablesAtTopOfStack()
        } else {
            ""
        }))
    }.joinToString("\n")

    private fun Statement.allVariables(): List<String> = this.frames.flatMap { it.variablesStringRep() } + this.globalVariableContainers.variablesStringRep()

    /**
     * Tests that the stack size only increases and then decreases.
     * This holds for methods that are just calling a single function.
     */
    @ParameterizedTest
    @MethodSource("monotonicIncDecInputs")
    fun testCallFrameMonotonicity(config: Config) {
        val debugAdapter = configToResult[config]!!;
        assertTrue(isMonotonicIncreasingThenDecreasing(debugAdapter.getCallTrace().map { it.frames.size })) { "Expected sequence of frames to be monotonically increasing and then decreasing, but got ${debugAdapter.getCallTrace().map { it.frames.size }}" }
    }

    /**
     * A simple test that a variable is present _at one_ stack frame along the entire trace.
     */
    @ParameterizedTest
    @MethodSource("displaysVariableSomewhere")
    fun displaysVariableSomewhere(input: VariableTestInput) {
        val allVariables = configToResult[input.config]!!.getCallTrace().flatMap { it.allVariables() }.toSet()
        val expectedVariableSet = input.variableNames.toMutableSet()
        expectedVariableSet.removeAll(allVariables.toSet())
        assertTrue(expectedVariableSet.isEmpty()) { "Did not find variable with names $expectedVariableSet in test, all variables are: \n ${allVariables.joinToString { "\n" }}. " }
    }

    /**
     * A test that a variable is present at a specific stack location matched by the line numbers of the call stack at this location
     */
    @ParameterizedTest
    @MethodSource("displaysVariableAtStack")
    fun displaysVariableAtStackTest(input: Pair<Config, CallStackMatcher>) {
        val callTrace = configToResult[input.first]!!.getCallTrace()
        val matchingFrames = callTrace.filter { input.second.matches(it.frames) }
        assert(matchingFrames.isNotEmpty()) { "Did not find a matching frame that matches ${input.second} given the call trace \n${callTrace.map { it.frames }.joinToString("\n")}" }
        matchingFrames.forEach {
            input.second.matchAction(it)
        }
    }

    // Alternative version that finds the peak automatically using maxOf with index
    private fun isMonotonicIncreasingThenDecreasing(sequence: List<Int>): Boolean {
        if (sequence.size < 2) return true

        // Find the peak index using maxOf with index
        val peakIndex = sequence.withIndex().maxByOrNull { it.value }?.index
            ?: return false

        // Check monotonically increasing up to peak
        for (i in 0 until peakIndex) {
            if (sequence[i] > sequence[i + 1]) {
                return false
            }
        }

        // Check monotonically decreasing from peak
        for (i in peakIndex until sequence.size - 1) {
            if (sequence[i] < sequence[i + 1]) {
                return false
            }
        }

        return true
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
    fun callStackPushAndPopTest(input: RuleToPushOperations) {
        val debugAdapter = configToResult[input.config]!!
        fun Statement.topFrameMatchesLine(expectedLine: UInt): Boolean {
            return (this.frames.first().range as Range.Range).lineNumber == expectedLine.toInt()
        }

        val results = input.lineNumbersOfPush.map { expectedLine ->
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

    /**
     * Tests that the call stack has a specific list of line numbers on top of the stack
     * at some point in time. I.e. given a list of
     */
    @ParameterizedTest
    @MethodSource("topOfStackTestInputs")
    fun topOfStackTests(input: Pair<Config, List<List<StackSel>>>) {
        val config = input.first
        val topOfStackLineNumber = input.second
        val debugAdapter = configToResult[config]!!
        val callTrace = debugAdapter.getCallTrace()

        fun recurse(expectedLines: List<List<StackSel>>, expectedIndex: Int, callTraceIndex: Int): Boolean {
            if (callTrace.size < callTraceIndex) {
                return false
            }
            if (expectedLines.size <= expectedIndex) {
                return true;
            }
            val currFrame = callTrace[callTraceIndex]
            val currExpectedLines = expectedLines[expectedIndex]
            return if (currFrame.frames.matchesSelector(currExpectedLines)) {
                recurse(expectedLines, expectedIndex + 1, callTraceIndex + 1)
            } else {
                false;
            }
        }

        val containsList = callTrace.any { statement ->
            recurse(topOfStackLineNumber, 0, callTrace.indexOf(statement))
        }
        assert(containsList) {
            "Did not find list of stack elements: ${topOfStackLineNumber}\n" +
                debugAdapter.getCallTrace().toLineNumberRepresentation()
        }
    }



    sealed interface ICallStackMatcher {

        fun matches(callStack: List<ImmutableStackFrame>): Boolean

        val matchAction: (selectedCallStack: Statement) -> Unit
    }

    class CallStackMatcher(private vararg val stackSel: StackSel, override val matchAction: (callStack: Statement) -> Unit) : ICallStackMatcher {
        override fun matches(callStack: List<ImmutableStackFrame>): Boolean {
            return callStack.matchesSelector(stackSel.toList())
        }
    }

    sealed interface StackSel {
        val lineNumber: UInt?
    }

    data class Hook(override val lineNumber: UInt? = null) : StackSel
    data class Rule(override val lineNumber: UInt? = null) : StackSel
    data class CVLFunc(override val lineNumber: UInt? = null) : StackSel
    data class SolFunc(override val lineNumber: UInt? = null) : StackSel
    data class Summary(override val lineNumber: UInt? = null) : StackSel
    data class LN(override val lineNumber: UInt) : StackSel

    data class VariableTestInput(
        val config: Config,
        val variableNames: List<String>
    )

    data class RuleToPushOperations(
        val config: Config,
        val lineNumbersOfPush: Set<UInt>
    )

    companion object {

        private val allRegisteredConfigs = mutableSetOf<Config>()

        /**
         * A filter operation, use this to filter out config for debugging.
         * To only execute the test case "storageReset", you can use { c: Config -> c.ruleName == "storageReset"}
         */
        private val filterByConfig = { _: Config -> true }

        /**
         * After [beforeAll] has been executed, this maps the config to the complete call traces for look up by the tests above
         */
        private val configToResult = mutableMapOf<Config, DebugAdapterProtocolStackMachine>()


        private val baseTestCasePath = Path("src/test/resources/solver/DebuggerTests/")

        /**
         * List of configs that we test for
         */
        private val callCVLFunctionOnce: Config = Config(ruleName = "callCVLFunctionOnce")
        private val callCVLFunction: Config = Config(ruleName = "callCVLFunction")
        private val onlyCVLVariables: Config = Config(ruleName = "onlyCVLVariables")
        private val addSummarizedByCVLFunction: Config = Config(ruleName = "addSummarizedByCVLFunction")
        private val addSummarizedNyNondet: Config = Config(ruleName = "addSummarizedNyNondet")
        private val add: Config = Config(ruleName = "add")
        private val sumCoordinates: Config = Config(ruleName = "sumCoordinates")
        private val sumCoordinatesInternal: Config = Config(ruleName = "sumCoordinatesInternal")
        private val updateGhost: Config = Config(ruleName = "updateGhost")
        private val addToStorage: Config = Config(ruleName = "addToStorage")
        private val onlyCVLVariablesParameter: Config = Config(ruleName = "onlyCVLVariablesParameter")

        private val storageReset: Config = Config(ruleName = "storageReset", confPath = baseTestCasePath.resolve("StorageReset/C.conf"), specPath = baseTestCasePath.resolve("StorageReset/C.spec"), primaryContract = "C")
        private val add_withLibrary = Config(ruleName = "add_withLibrary", confPath = baseTestCasePath.resolve("Libraries/C.conf"), specPath = baseTestCasePath.resolve("Libraries/C.spec"))

        private val hookExample_getSomeField: Config = Config(ruleName = "hookExample", methodName = "getSomeField()", confPath = baseTestCasePath.resolve("Hooks/Default.conf"), specPath = baseTestCasePath.resolve("Hooks/simple.spec"))
        private val hookExample_setSomeField: Config = Config(ruleName = "hookExample", methodName = "setSomeField(uint256)", confPath = baseTestCasePath.resolve("Hooks/Default.conf"), specPath = baseTestCasePath.resolve("Hooks/simple.spec"))

        data class Config(val ruleName: String,
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

        @JvmStatic
        fun displaysVariableSomewhere(): Stream<VariableTestInput> {
            return Stream.of(
                VariableTestInput(callCVLFunctionOnce, listOf("x", "y")),
                VariableTestInput(callCVLFunction, listOf("x", "y", "z")),
                VariableTestInput(add, listOf("x", "y", "z", "a", "b")),
                VariableTestInput(sumCoordinates, listOf("point.x", "point.y", "t", "a", "b")),
                VariableTestInput(sumCoordinatesInternal, listOf("point.x", "point.y", "t", "t", "a", "b")),
                VariableTestInput(updateGhost, listOf("foo")),
                VariableTestInput(addToStorage, listOf("x", "z", "a", "Contract.storageResult")),
                VariableTestInput(onlyCVLVariablesParameter, listOf("y"))
            ).filter { filterByConfig(it.config) }
        }

        @JvmStatic
        fun callStackPushAndPopTest(): Stream<RuleToPushOperations> {
            return Stream.of(
                RuleToPushOperations(callCVLFunctionOnce, setOf(25U)),
                RuleToPushOperations(callCVLFunction, setOf(31U, 32U)),
                RuleToPushOperations(add, setOf(50U)), //Issue solvable by adding push operation in CVL*/
            ).filter { filterByConfig(it.config) }
        }

        @JvmStatic
        fun topOfStackTestInputs(): Stream<Pair<Config, List<List<StackSel>>>> {
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
        fun displaysVariableAtStack(): Stream<Pair<Config, CallStackMatcher>> {


            fun assertContainsVariable(variableName: String, matchedCallStack: Statement) {
                val foundVariables = matchedCallStack.variablesAtTopOfStack()
                assertTrue(variableName in foundVariables) { "Expected to find variable $variableName in variable set $foundVariables at stack $matchedCallStack" }
            }

            fun assertNotContainsVariable(variableName: String, matchedCallStack: Statement) {
                val foundVariables = matchedCallStack.variablesAtTopOfStack()
                assertTrue(variableName !in foundVariables) { "Did not expect to find variable $variableName in variable set $foundVariables at stack $matchedCallStack" }
            }

            /**
             * We have too many commands that push onto the call stack. This needs cleanup elsewhere.
             * The stack elements that are pushed duplicated by "*duplicated*".
             */
            return listOf(
                callCVLFunctionOnce to CallStackMatcher(
                    Rule(26U))
                { matchedCallStack ->
                    assertContainsVariable("x", matchedCallStack)
                    assertContainsVariable("y", matchedCallStack)
                },

                callCVLFunctionOnce to CallStackMatcher(
                    CVLFunc(20U),
                    Rule(25U))
                { matchedCallStack ->
                    assertNotContainsVariable("x", matchedCallStack)
                    assertContainsVariable("a", matchedCallStack)
                },

                sumCoordinates to CallStackMatcher(
                    SolFunc(19U),
                    SolFunc(23U),
                    /*duplicated*/  SolFunc(22U),
                    Rule(56U))
                { matchedCallStack ->
                    assertContainsVariable("a", matchedCallStack)
                    assertContainsVariable("b", matchedCallStack)
                },

                sumCoordinates to CallStackMatcher(
                    Rule(57U))
                { matchedCallStack ->
                    assertContainsVariable("z", matchedCallStack)
                },

                addToStorage to CallStackMatcher(
                    SolFunc(14U),
                    /*duplicated*/ SolFunc(12U),
                    Rule(68U))
                { matchedCallStack ->
                    assertContainsVariable("Contract.storageResult", matchedCallStack)
                    assertContainsVariable("a", matchedCallStack)
                },

                add_withLibrary to (CallStackMatcher(
                    SolFunc(6U),
                    /*duplicated*/ SolFunc(5U),
                    Rule(8U))
                { matchedCallStack ->
                    assertContainsVariable("x", matchedCallStack)
                    assertContainsVariable("y", matchedCallStack)
                }),

                add_withLibrary to CallStackMatcher(
                    SolFunc(5U),
                    SolFunc(6U),
                    /*duplicated*/ SolFunc(5U),
                    Rule(8U))
                { matchedCallStack ->
                    assertContainsVariable("a", matchedCallStack)
                    assertContainsVariable("b", matchedCallStack)
                },

                updateGhost to CallStackMatcher(
                    CVLFunc(89U),
                    Rule(94U))
                { matchedCallStack ->
                    assertContainsVariable("foo", matchedCallStack)
                },

                hookExample_setSomeField to CallStackMatcher(
                    Hook(5U),
                    SolFunc(7U),
                    /*duplicated*/ SolFunc(7U),
                    Rule(19U))
                { matchedCallStack ->
                    assertContainsVariable("hook_store_oldValue", matchedCallStack)
                    assertContainsVariable("hook_store_newValue", matchedCallStack)
                }).filter { filterByConfig(it.first) }.stream()
        }

        @JvmStatic
        fun monotonicIncDecInputs(): Stream<Config> {
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
        @BeforeAll
        fun beforeAll() {
            ConfigScope(config.Config.CallTraceDebugAdapterProtocol, true).use {
                allRegisteredConfigs.map { conf ->
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

private fun Statement.variablesAtTopOfStack(): Set<String> {
    val frame = this.frames.firstOrNull()!!
    return frame.variablesStringRep() + this.globalVariableContainers.variablesStringRep()
}

private fun ImmutableStackFrame.variablesStringRep(): Set<String> {
    return this.variableContainers.variablesStringRep()
}

private fun List<VariableContainer>.variablesStringRep(): Set<String>{
    return this.flatMap { it.variables.flatMap { it.toAccessPathString().map { it.first } } }.toSet()
}

private fun VariableNode.toAccessPathString(): List<Pair<String, String?>> {
    fun VariableNode.buildAccessPathRecursively(parentRes: List<Pair<String, String?>>): List<Pair<String, String?>> {
        return if (children.isEmpty()) {
            parentRes.map { it.first to this.value }
        } else {
            children.flatMap { n ->
                n.buildAccessPathRecursively(parentRes.map { "${it.first}.${n.name}" to it.second })
            }
        }
    }
    return this.buildAccessPathRecursively(listOf(this.name to null))
}

private fun List<ImmutableStackFrame>.matchesSelector(stackSel: List<DebugAdapterCallTraceTest.StackSel>): Boolean {
    if (this.size < stackSel.size) {
        return false;
    }

    return this.zip(stackSel.toList()).all {
        if (it.second.lineNumber != null && it.second.lineNumber != it.first.range?.lineNumber?.toUInt()) {
            return@all false;
        }
        if (it.second is DebugAdapterCallTraceTest.LN) {
            return@all true;
        }
        when (it.first.stackEntry) {
            is StackEntry.CVLFunction -> it.second is DebugAdapterCallTraceTest.CVLFunc
            is StackEntry.CVLHook -> it.second is DebugAdapterCallTraceTest.Hook
            is StackEntry.Rule -> it.second is DebugAdapterCallTraceTest.Rule
            is StackEntry.SolidityFunction -> it.second is DebugAdapterCallTraceTest.SolFunc
            is StackEntry.Summary -> it.second is DebugAdapterCallTraceTest.Summary
        }
    }
}