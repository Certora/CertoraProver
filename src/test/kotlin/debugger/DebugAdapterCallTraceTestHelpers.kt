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

import config.*
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions.*
import report.calltrace.printer.*
import utils.*
import kotlin.collections.listOf
import kotlin.collections.toMutableSet

/**
 * Returns true in the case the call stack increases monotonically and then decreases.
 *
 * Assume two function foo and bar, then it returns true for such a stack.
 * foo
 * foo, bar
 * foo, bar
 * foo
 *
 * while false for such a stack:
 * foo
 * foo, bar
 * foo
 * foo, bar
 * foo
 */
fun DebugAdapterProtocolStackMachine.isMonotoicIncreasingAndThenDecreasing(): Boolean {
    return this.getCallTrace().map { it.frames.size }.isMonotonicIncreasingThenDecreasing()
}

/**
 * Returns the variable that are in the call trace, but not in [expectedVariableSet].
 * This function ignores the call stack at which the variables are found, i.e., it only looks
 * for occurrences for the variables with the given name, not locations.
 */
fun DebugAdapterProtocolStackMachine.unmatchedVariables(expectedVariableSet: Set<String>): Set<String>  {
    val allVariables = getAllVariables()
    val remainingVariables = expectedVariableSet.toMutableSet()
    remainingVariables.removeAll(allVariables.toSet())
    return remainingVariables
}

/**
 * Returns a string representation of the variables used within the call trace.
 * For variable of struct types, returns a string that is an access path, i.e.
 * field accesses are encoded via `.`.
 *
 * So if a struct Foo{bar: u64} is accessed at bar and the struct is stored in a variable named
 * foo, the set contains foo.bar
 */
fun DebugAdapterProtocolStackMachine.getAllVariables(): Set<String> {
    return getCallTrace().flatMapToSet { it.allVariables() }
}

/**
 * Iterates over each statement in the call trace and filters them based on
 * [DebugAdapterCallTraceTest.ICallStackMatcher.matches] and applies the
 * [DebugAdapterCallTraceTest.ICallStackMatcher.matchAction] for the last element found
 */
fun DebugAdapterProtocolStackMachine.applyCallStackMatcher(action: ICallStackMatcher) {
    val callTrace = getCallTrace()
    val matchingFrames = callTrace.filterIndexed() { i, it -> action.matches(callTrace, i, it.frames) }
    assert(matchingFrames.isNotEmpty()) { "Did not find a matching frame that matches ${action} given the call trace \n${callTrace.map { it.frames }.joinToString("\n")}" }
    action.matchAction(matchingFrames.last())
}

/**
 * Returns true if the call trace (a sequence of stacks) contains a subsequence of stacks such that each stack matches
 * the topOfStack[idx].
 */
fun DebugAdapterProtocolStackMachine.sequenceOfCallStackMatching(topOfStacks: List<List<StackSel>>): Boolean {
    val callTrace = getCallTrace()

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

    return callTrace.any { statement ->
        recurse(topOfStacks, 0, callTrace.indexOf(statement))
    }
}

/**
 * Helper methods for tests below
 */
private fun ImmutableStackFrame.variablesStringRep(): Set<String> {
    return this.variableContainers.variablesStringRep().keys
}


private fun List<VariableContainer>.variablesStringRep(): Map<String, String?> {
    return this.flatMap { it.variables.flatMap { it.toAccessPathString() } }.toMap()
}

private fun ImmutableStackFrame.variablesStringRepToValues(): Map<String, String?> {
    return this.variableContainers.flatMap { it.variables.flatMap { it.toAccessPathString() } }.toMap()
}

private fun VariableNode.toAccessPathString(): List<Pair<String, String?>> {
    fun VariableNode.buildAccessPathRecursively(parentRes: List<Pair<String, String?>>): List<Pair<String, String?>> {
        return if (children.isEmpty()) {
            parentRes.map { it.first to this.value }
        } else {
            children.flatMap { n ->
                n.buildAccessPathRecursively(parentRes.map { "${it.first}.${n.name}" to it.second })
            } + parentRes.map { it.first to this.value }
        }
    }
    return this.buildAccessPathRecursively(listOf(this.name to null))
}

private fun List<ImmutableStackFrame>.matchesSelector(stackSel: List<StackSel>): Boolean {
    if (this.size < stackSel.size) {
        return false;
    }

    return this.zip(stackSel.toList()).all {
        if (it.second.lineNumber != null && it.second.lineNumber != it.first.range?.lineNumber?.toUInt()) {
            return@all false;
        }
        if (it.second is LN) {
            return@all true;
        }
        when (it.first.stackEntry) {
            is StackEntry.CVLFunction -> it.second is CVLFunc
            is StackEntry.CVLHook -> it.second is Hook
            is StackEntry.Rule -> it.second is Rule
            is StackEntry.SolidityFunction -> it.second is SolFunc
            is StackEntry.Summary -> it.second is Summary
            is StackEntry.SolanaFunction -> it.second is SolanaFunction
        }
    }
}


private fun Statement.variablesAtTopOfStack(): Map<String, String?> {
    val frame = this.frames.firstOrNull()!!
    return frame.variablesStringRepToValues() + this.globalVariableContainers.variablesStringRep()
}

fun Statement.assertContainsVariable(variableName: String) {
    val foundVariables = variablesAtTopOfStack()
    assertTrue(variableName in foundVariables) { "Expected to find variable $variableName in variable set $foundVariables at stack $this" }
}

fun Statement.assertNumberOfVariables(variableCount: Int) {
    val foundVariables = variablesAtTopOfStack()
    assertTrue(foundVariables.size == variableCount) { "Expected to find exactly $variableCount variables, found ${foundVariables.size} (all variables are $foundVariables) at stack $this" }
}

fun Statement.assertNotContainsVariable(variableName: String) {
    val foundVariables = variablesAtTopOfStack()
    assertTrue(variableName !in foundVariables) { "Did not expect to find variable $variableName in variable set $foundVariables at stack $this" }
}

fun Statement.assertContainsVariableWithSameValue(variableNameA: String, variableNameB: String) {
    val foundVariables = variablesAtTopOfStack()
    assertContainsVariable(variableNameA)
    assertContainsVariable(variableNameB)
    assertTrue(foundVariables[variableNameA] == foundVariables[variableNameB]) {
        "Expected to find the same value for the variables ${variableNameA} (value ${foundVariables[variableNameA]}) and ${variableNameB} (value ${foundVariables[variableNameB]}) \n" +
            " All variables ${foundVariables}"
    }
}

fun Statement.assertAllPrefixesContainsVariableWithSameValue(prefixA: String, prefixB: String) {
    val foundVariables = variablesAtTopOfStack()

    val variablesStartingInA = foundVariables.filter { it.key.startsWith(prefixA) }
    val variablesStartingInB = foundVariables.filter { it.key.startsWith(prefixB) }

    assertTrue(variablesStartingInA.size == variablesStartingInB.size) { "Expecting to find the same number of variables starting in the prefixes ($prefixA and $prefixB) in ${foundVariables}" }
    //We ignore the top level element here (as the top element may contain a reference which is ok to diverge)
    assertTrue(variablesStartingInA.filter { it.key != prefixA }.all { e -> variablesStartingInB[e.key.replace(prefixA, prefixB)] == e.value }) { "Some values are not matching ${variablesStartingInB}, ${variablesStartingInA}" }
}

fun Statement.assertContainsVariableWithConcreteStringValue(variableName: String, expectedStringVal: String) {
    val foundVariables = variablesAtTopOfStack()
    assertTrue(foundVariables[variableName] == expectedStringVal) { "Expected to find the value ${expectedStringVal} for the variables ${variableName} but found ${foundVariables[variableName]}. \n All variables ${foundVariables}" }
}

fun Statement.assertContainsVariableWithConcreteValue(variableName: String, value: Int) {
    val expectedStringVal = "0x${value.toString(16)}"
    assertContainsVariableWithConcreteStringValue(variableName, expectedStringVal)
}

fun List<Statement>.toLineNumberRepresentation(withVariables: Boolean = true) = this.mapIndexed { idx, el ->
    idx to (el.frames to (if (withVariables) {
        el.variablesAtTopOfStack().toString() + el.consoleOutput
    } else {
        ""
    }))
}.joinToString("\n")

private fun Statement.allVariables(): List<String> = this.frames.flatMap { it.variablesStringRep() } + this.globalVariableContainers.variablesStringRep().keys

fun Statement.topFrameMatchesLine(expectedLine: UInt): Boolean {
    return (this.frames.first().range as Range.Range).lineNumber == expectedLine.toInt()
}

// Alternative version that finds the peak automatically using maxOf with index
private fun List<Int>.isMonotonicIncreasingThenDecreasing(): Boolean {
    if (this.size < 2) return true

    // Find the peak index using maxOf with index
    val peakIndex = this.withIndex().maxByOrNull { it.value }?.index
        ?: return false

    // Check monotonically increasing up to peak
    for (i in 0 until peakIndex) {
        if (this[i] > this[i + 1]) {
            return false
        }
    }

    // Check monotonically decreasing from peak
    for (i in peakIndex until this.size - 1) {
        if (this[i] < this[i + 1]) {
            return false
        }
    }

    return true
}


sealed interface ICallStackMatcher {

    fun matches(callTraceSteps: List<Statement>, index: Int, callStack: List<ImmutableStackFrame>): Boolean

    val matchAction: (selectedCallStack: Statement) -> Unit
}

class CallStackMatcher(private vararg val stackSel: StackSel, override val matchAction: (callStack: Statement) -> Unit) : ICallStackMatcher {
    override fun matches(callTraceSteps: List<Statement>, index: Int, callStack: List<ImmutableStackFrame>): Boolean {
        return callStack.matchesSelector(stackSel.toList())
    }

    override fun toString(): String {
        return stackSel.joinToString(",")
    }
}

class LastStatementMatcher(override val matchAction: (callStack: Statement) -> Unit) : ICallStackMatcher {
    override fun matches(callTraceSteps: List<Statement>, index: Int, callStack: List<ImmutableStackFrame>): Boolean {
        return index == callTraceSteps.lastIndex
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
data class SolanaFunction(override val lineNumber: UInt? = null) : StackSel
data class LN(override val lineNumber: UInt) : StackSel