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

@file:Suppress("ReplaceGetOrSet")

package solver


import analysis.LTACCmd
import analysis.assertNotNull
import config.Config
import config.ConfigScope
import infra.CallTraceInfra
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import log.*
import log.TestLoggers.CallTrace.noXs
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import report.LocalAssignments
import report.calltrace.CallEndStatus
import report.calltrace.CallInstance
import report.calltrace.CallTrace
import report.calltrace.formatter.FormatterType
import report.calltrace.sarif.Sarif
import report.globalstate.GlobalState
import rules.RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.CounterExample
import solver.StructuralInvariants.globalPropertiesChecks
import solver.StructuralInvariants.verifyAssertCast
import solver.StructuralInvariants.verifyAssertChildren
import solver.StructuralInvariants.verifyExpectedJson
import solver.StructuralInvariants.verifyHasGlobalState
import utils.Range
import spec.rules.CVLSingleRule
import spec.cvlast.CVLType
import spec.rules.SingleRuleGenerationMeta
import utils.*
import vc.data.*
import vc.data.state.TACValue
import java.nio.file.Path
import java.util.*
import java.util.function.Predicate

class CallTraceTest {
    /** When this is true, every test that compares call trace jsons, actual vs expected, will dump the current
     * actual one to disk in the test folder. That way one can use a regular diff tool to see the changes and update
     * the expected one if needed. */
    private val dumpActualCallTraceJsons: Boolean = false // set false on master

    /** Used when [dumpActualCallTraceJsons] is set, for dumping actual call trace jsons for offline comparison.  */
    private val jsonPP = Json { prettyPrint = true }

    /** The string used by CallTraceFormatter.UNKNOWN_VALUE, used in comparisons in some tests. */
    private val unknownStr = "..."

    @BeforeEach
    fun beforeEach() {
        noXs = TestLoggers.CallTrace.NoXs()
    }

    private fun resolvePath(path: String): Path = Path.of("src/test/resources/solver/CallTraceTests").resolve(path)

    /**
     * Very basic test, the rule just checks whether a uint parameter can be odd.
     *
     * We check existence of the assert message, and some generic [StructuralInvariants].
     */
    @Test
    fun testMod() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("mod/mod.conf"),
            specFilename = resolvePath("mod/mod.spec"),
            ruleName = "even",
            primaryContract = "mod",
        )

        assertEquals("not even", callTrace.assertMessage)

        assertEquals(false, noXs?.foundX)

        // might also implement: check that the model value of the rule parameter (uint u) is actually non-even

        genericWellFormednessChecks(callTrace)
    }


    /* NOTE: I'm not putting CallTraceRefresherIgnore in this directory, since there are other tests using it
     *  --> once they're also updated, we should add that file and delete the unused subdirs with json and tac files;
     *    from now till then, the `AsParam` subdir is there but unused; I don't think it's worth the effort to clean it
     *    up now .. */
    @Test
    fun testAssertCastRuleAsParam() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("AssertCast/assert_cast.conf"),
            specFilename = resolvePath("AssertCast/Cast.spec"),
            ruleName = "AsParam",
            primaryContract = "Cast",
        )

        verifyHasGlobalState(callTrace.callHierarchyRoot)
        verifyAssertCast(callTrace.callHierarchyRoot)
        globalPropertiesChecks(callTrace.callHierarchyRoot)

        // check that the right cast-assert is violated
        checkViolatedAssert(callTrace) { ctv ->
            assertInstanceOf(TACCmd.Simple.AnnotationCmd::class.java, ctv.violatedAssert.cmd)
            val annot = (ctv.violatedAssert.cmd as TACCmd.Simple.AnnotationCmd).annot
            assertEquals(TACMeta.SNIPPET, annot.k)
            assertInstanceOf(SnippetCmd.CVLSnippetCmd.AssertCast::class.java, annot.v)
        }

        val callTraceFlat = callTrace.callHierarchyRoot.allChildren().toList()

        // check that the second assert cast is violated
        val castChecks =
            callTraceFlat.filterIsInstance<CallInstance.CastCheckInstance>().toList()
        assertEquals(castChecks[0].name, "assert-cast check passed")
        assertEquals(castChecks[1].name, "assert-cast check failed")
    }

    @Test
    fun testAssertCastRuleComplexExp() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("AssertCast/assert_cast.conf"),
            specFilename = resolvePath("AssertCast/Cast.spec"),
            ruleName = "ComplexExp",
            primaryContract = "Cast",
        )

        verifyHasGlobalState(callTrace.callHierarchyRoot)
        verifyAssertChildren(callTrace.callHierarchyRoot)
        verifyAssertCast(callTrace.callHierarchyRoot)

        globalPropertiesChecks(callTrace.callHierarchyRoot)

    }

    @Test
    fun testAssertCastRuleDefinitionStatement() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("AssertCast/assert_cast.conf"),
            specFilename = resolvePath("AssertCast/Cast.spec"),
            ruleName = "DefinitionStatement",
            primaryContract = "Cast",
        )

        verifyAssertCast(callTrace.callHierarchyRoot)
        verifyHasGlobalState(callTrace.callHierarchyRoot)

        globalPropertiesChecks(callTrace.callHierarchyRoot)
    }

    @Test
    fun testAssertCastRuleIfStatement() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("AssertCast/assert_cast.conf"),
            specFilename = resolvePath("AssertCast/Cast.spec"),
            ruleName = "IfStatement",
            primaryContract = "Cast",
        )

        verifyHasGlobalState(callTrace.callHierarchyRoot)
        verifyAssertChildren(callTrace.callHierarchyRoot)
        verifyAssertCast(callTrace.callHierarchyRoot)

        globalPropertiesChecks(callTrace.callHierarchyRoot)
    }

    @Test
    fun testAssertCastRuleToSignedInt() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("AssertCast/assert_cast.conf"),
            specFilename = resolvePath("AssertCast/Cast.spec"),
            ruleName = "ToSignedInt",
            primaryContract = "Cast",
        )

        // TODO: CERT-9273
//        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
//        verifyViolateAssert(expectedViolatedAssert, callTrace)

        verifyHasGlobalState(callTrace.callHierarchyRoot)
        verifyAssertChildren(callTrace.callHierarchyRoot)
        verifyAssertCast(callTrace.callHierarchyRoot)

        globalPropertiesChecks(callTrace.callHierarchyRoot)
    }


    @Test
    fun testStructPassingToCVLFunc1() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("CVLFunctionStructs/run.conf"),
            specFilename = resolvePath("CVLFunctionStructs/test.spec"),
            ruleName = "checkWorkOnS",
            primaryContract = "TestContract",
        )

        globalPropertiesChecks(callTrace.callHierarchyRoot)

        val cvlFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.CVLFunctionInstance>()

        assertEquals(1, cvlFunctions.size)
        assertMatches("""workOnSCVL\(x=${numberRE}, s=\{x=${numberRE}, y=${addressRE}, z1=${numberRE}, z2=${numberRE}, b1=${boolRE}, x2=${numberRE}, b2=${boolRE}}\)""", cvlFunctions[0].toString())
    }

    private fun dumpActualCallTraceJson(verifierResultPath: Path, actualJson: JsonObject) {
        if (dumpActualCallTraceJsons) {
            verifierResultPath.resolve("actualCallTrace.json").toFile()
                .writeText(jsonPP.encodeToString(actualJson))
        }
    }

    private fun genericWellFormednessChecks(callTrace: CallTrace) {
        verifyHasGlobalState(callTrace.callHierarchyRoot)
        verifyAssertChildren(callTrace.callHierarchyRoot)
        globalPropertiesChecks(callTrace.callHierarchyRoot)
    }

    private fun assertMatches(expectedRegex: Regex, actual: String) =
        assertTrue(expectedRegex.matches(actual)) { "$actual does not match ${expectedRegex.pattern}" }

    private fun assertMatches(expectedRegexStr: String, actual: String) {
        val expectedRegex = expectedRegexStr.toRegex()
        assertMatches(expectedRegex, actual)
    }

    private val maxUint = "MAX_U?INT[0-9]+"
    private val num = "[0-9A-Fa-fx]+"
    private val numberRE = "(($num)|\\(MAX_EVM_ADDRESS\\)|($maxUint)|(2\\^[0-9]+( - $num)?)?)"
    private val addressRE = "($numberRE|([a-zA-Z0-9]+ \\([0xa-fA-F0-9]+\\)))"
    private val boolRE = "(true|false)"

    @Test
    fun testStructPassingToCVLFunc2() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("CVLFunctionStructs/run.conf"),
            specFilename = resolvePath("CVLFunctionStructs/test.spec"),
            ruleName = "checkWorkOnSCVL",
            primaryContract = "TestContract",
        )

        globalPropertiesChecks(callTrace.callHierarchyRoot)

        val cvlFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.CVLFunctionInstance>()

        assertEquals(1, cvlFunctions.size)
        assertMatches(
            "workOnSCVL\\(x=$numberRE, s=\\{x=$numberRE, y=$addressRE, z1=$numberRE, z2=$numberRE, b1=false, x2=$numberRE, b2=$boolRE\\}\\)",
            cvlFunctions[0].toString()
        )
    }

    @Test
    fun testStructPassingToCVLFuncNested() {
        val variant1 = """workOnSComplexCVL\(x=${numberRE}, s=\{s1\.x=${numberRE}, s1\.y=${addressRE}, s1\.z1=${numberRE}, s1\.z2=${numberRE}, s1\.b1=${boolRE}, s1\.x2=${numberRE}, s1\.b2=${boolRE}, s2\.x=${numberRE}, s2\.y=${addressRE}, s2\.z1=${numberRE}, s2\.z2=${numberRE}, s2\.b1=${boolRE}, s2\.x2=${numberRE}, s2\.b2=${boolRE}, b3=${boolRE}}\)"""
        val variant2 = """workOnSCVL\(x=${numberRE}, s=\{x=${numberRE}, y=${addressRE}, z1=${numberRE}, z2=${numberRE}, b1=${boolRE}, x2=${numberRE}, b2=${boolRE}}\)"""

        val tests = listOf(
            Pair("checkWorkOnS1", variant1),
            Pair("checkWorkOnS1_2", variant1),
            Pair("checkWorkOnS2", variant2),
            Pair("checkWorkOnS2_2", variant2),
            Pair("checkWorkOnSCVL1", variant2),
            Pair("checkWorkOnSCVL2", variant2),
            Pair("checkWorkOnSCVL1", variant2),
        )

        for ((ruleName, uiStringRegex) in tests) {
            val callTrace = CallTraceInfra.runConfAndGetCallTrace(
                confPath = resolvePath("CVLFunctionStructs/Nested/run.conf"),
                specFilename = resolvePath("CVLFunctionStructs/Nested/test.spec"),
                ruleName,
                primaryContract = "TestContract",
            )

            globalPropertiesChecks(callTrace.callHierarchyRoot)

            val cvlFunctions = callTrace
                .callHierarchyRoot
                .filterCallInstancesOf<CallInstance.InvokingInstance.CVLFunctionInstance>()

            assertEquals(1, cvlFunctions.size)
            assertMatches(uiStringRegex, cvlFunctions[0].toString())
        }

    }


    @Test
    fun testCVLFunctionBasic() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("CVLFunctionBasic/run.conf"),
            specFilename = resolvePath("CVLFunctionBasic/Basic.spec"),
            ruleName = "CvlFunctionTest",
            primaryContract = "TestContract",
        )

        globalPropertiesChecks(callTrace.callHierarchyRoot)

        val cvlFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.CVLFunctionInstance>()

        assertEquals(2, cvlFunctions.size)
        assertMatches("""func1\(num=${numberRE}\)""", cvlFunctions[0].toString())
        assertMatches("""func2\(num=${numberRE}\)""", cvlFunctions[1].toString())
    }

    @Test
    fun testCVLFunctionComplexTestCalldataarg() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("CVLFunctionComplex/run.conf"),
            specFilename = resolvePath("CVLFunctionComplex/Complex.spec"),
            ruleName = "test_calldataarg",
            primaryContract = "TestContract",
        )

        globalPropertiesChecks(callTrace.callHierarchyRoot)

        val cvlFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.CVLFunctionInstance>()

        val contractAddr = """(0x1000|TestContract \(0x1000\))"""
        val func0St = """create_env\(addr=${contractAddr}\)"""
        val func3St = """call_function_with_calldataarg\(e=\{msg.sender=${contractAddr}, msg\.value=${numberRE}, tx\.origin=${numberRE}, block\.basefee=${numberRE}, block\.blobbasefee=${numberRE}, block\.coinbase=${numberRE}, block\.difficulty=${numberRE}, block\.gaslimit=${numberRE}, block\.number=${numberRE}, block\.timestamp=${numberRE}}, args=calldataarg \(length=${numberRE}\), start=$unknownStr\)"""

        assertEquals(4, cvlFunctions.size)
        assertMatches(func0St, cvlFunctions[0].toString())
        assertEquals("create_args()", cvlFunctions[1].toString())
        assertEquals("init_storage()", cvlFunctions[2].toString())
        assertMatches(func3St, cvlFunctions[3].toString())

        val externalFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.SolidityInvokeInstance.External>()
        val eFunc0St = """TestContract\.calc\(a=${numberRE}, b=${numberRE}, op=${boolRE}\)"""

        assertEquals(1, externalFunctions.size)
        assertMatches(eFunc0St, externalFunctions[0].toString())
    }

    /**
     * Runs the `test_static_array` rule in `CallTraceTests/CVLFunctionComplex`, which
     *  - calls a CVL function "struct_arg", passing a struct
     *  - calls a CVL function "array_arg", passing a static-length array
     *  - calls a CVL function "call_static_array", passing a static-length array to it which then gets passed to solidity
     *
     * Checks:
     *  - generic well-formedness invariants
     *  - that we give model values for all the function calls
     *
     *  Some of the functions arguments are complex; in particular there are static-length arrays (as the test name
     *  suggests).
     */
    @Test
    fun testCVLFunctionComplexTestStaticArray() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("CVLFunctionComplex/run.conf"),
            specFilename = resolvePath("CVLFunctionComplex/Complex.spec"),
            ruleName = "test_static_array",
            primaryContract = "TestContract",
        )

        globalPropertiesChecks(callTrace.callHierarchyRoot)

        val cvlFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.CVLFunctionInstance>()

        assertEquals(3, cvlFunctions.size)
        assertMatches("""struct_arg\(input=\{field_int=${numberRE}, field_bool=${boolRE}, field_addr=${addressRE}, field_bytes=$unknownStr}\)""", cvlFunctions[0].toString())
        assertMatches("""array_arg\(arr=bool\[${numberRE}]\)""", cvlFunctions[1].toString())
        assertMatches("""call_static_array\(arr=bool\[${numberRE}], input=\{field_int=${numberRE}, field_bool=${boolRE}, field_addr=${numberRE}, field_bytes=$unknownStr}\)""", cvlFunctions[2].toString())

        val externalFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.SolidityInvokeInstance.External>()
        val eFunc0St = """TestContract\.static_array_outer\(checks=\[0=${boolRE}, 1=${boolRE}, 2=${boolRE}, 3=${boolRE}], input=\{field_int=${numberRE}, field_bool=${boolRE}, field_addr=${addressRE}, field_bytes=bytes \(length=${numberRE}\)}\)"""

        assertEquals(1, externalFunctions.size)
        assertMatches(eFunc0St, externalFunctions[0].toString())

        // not quite there yet: -- we'd need to handle models for the `bytes`
        // assertEquals(false, noXs?.foundX)
    }

    /**
     * A case where we move a dynamic array through calldata --> call trace has to correctly obverve that in order to
     * recreate call data structure in order to show parameters correctly.
     */
    @Test
    fun testCVLFunctionComplexTestDynamicArray() {
        ConfigScope(Config.LoopUnrollConstant, 4).use {
            val callTrace = CallTraceInfra.runConfAndGetCallTrace(
                confPath = resolvePath("CVLFunctionComplex/run.conf"),
                specFilename = resolvePath("CVLFunctionComplex/Complex.spec"),
                ruleName = "test_dynamic_array",
                primaryContract = "TestContract",
            )

            globalPropertiesChecks(callTrace.callHierarchyRoot)

            val cvlFunctions = callTrace
                .callHierarchyRoot
                .filterCallInstancesOf<CallInstance.InvokingInstance.CVLFunctionInstance>()

            val func1St =
                """call_dynamic_array\(arr=bool\[] \(length=${numberRE}\), input=\{field_int=${numberRE}, field_bool=${boolRE}, field_addr=${addressRE}, field_bytes=$unknownStr}\)"""
            assertEquals(2, cvlFunctions.size)
            assertMatches(
                """struct_arg\(input=\{field_int=${numberRE}, field_bool=${boolRE}, field_addr=${addressRE}, field_bytes=$unknownStr}\)""",
                cvlFunctions[0].toString()
            )
            assertMatches(func1St, cvlFunctions[1].toString())

            val externalFunctions = callTrace
                .callHierarchyRoot
                .filterCallInstancesOf<CallInstance.InvokingInstance.SolidityInvokeInstance.External>()
            val eFunc0St =
                """TestContract\.dynamic_array_outer\(checks=\[0=${boolRE}, 1=${boolRE}, 2=${boolRE}, 3=${boolRE}] \(length=${numberRE}\), input=\{field_int=${numberRE}, field_bool=${boolRE}, field_addr=${addressRE}, field_bytes=bytes \(length=${numberRE}\)\}\)"""

            assertEquals(1, externalFunctions.size)
            assertMatches(eFunc0St, externalFunctions[0].toString())
        }
    }

    @Test
    fun testCallHook() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("CallHooks/run.conf"),
            specFilename = resolvePath("CallHooks/test.spec"),
            ruleName = "trigger_call_opcode",
            primaryContract = "Caller",
            buildOptions = listOf("--solc_via_ir")
        )

        val callTraceFlat = callTrace.callHierarchyRoot.allChildren().toList()

        val labelInstances =
            callTraceFlat.filterIsInstance<CallInstance.LabelInstance>().toList()

        val hookNode = labelInstances.filter { it.name.contains("Apply hook CALL") }
        assertTrue(hookNode.size == 1) { "Expecting the call trace to have a node of type LabelInstance with the given string" }
        assertEquals(hookNode.first().range, Range.Range("Caller.sol", SourcePosition(13U, 27U), SourcePosition(15U, 9U)))
    }

    @Test
    fun testStorageHook() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("StorageHooks/run.conf"),
            specFilename = resolvePath("StorageHooks/test.spec"),
            ruleName = "trigger_storage_set",
            primaryContract = "TestContract"
        )

        val callTraceFlat = callTrace.callHierarchyRoot.allChildren().toList()

        val labelInstances =
            callTraceFlat.filterIsInstance<CallInstance.LabelInstance>().toList()

        val hookNode = labelInstances.filter { it.name.contains("Apply hook store") }
        assertTrue(hookNode.size == 1) { "Expecting the call trace to have a node of type LabelInstance with the given string" }
        assertEquals(hookNode.first().range, Range.Range("TestContract.sol", SourcePosition(6U, 8U), SourcePosition(6U, 24U)))
    }

    @Test
    fun testCVLFunctionComplexTestStringAndBytes() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("CVLFunctionComplex/run.conf"),
            specFilename = resolvePath("CVLFunctionComplex/Complex.spec"),
            ruleName = "test_string_and_bytes",
            primaryContract = "TestContract",
        )

        globalPropertiesChecks(callTrace.callHierarchyRoot)

        val cvlFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.CVLFunctionInstance>()

        val func0St = """call_string_and_bytes\(st=string \(length=${numberRE}\), bt=bytes \(length=${numberRE}\)\)"""
        assertEquals(1, cvlFunctions.size)
        assertMatches(func0St, cvlFunctions[0].toString())

        val externalFunctions = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.InvokingInstance.SolidityInvokeInstance.External>()
        val eFunc0St = """TestContract\.string_and_bytes_outer\(st=string \(length=${numberRE}\), bt=bytes \(length=${numberRE}\)\)"""

        assertEquals(1, externalFunctions.size)
        assertMatches(eFunc0St, externalFunctions[0].toString())
    }

    @Test
    fun testStorageStateComplexTypes() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("StorageStateComplexTypes/run.conf"),
            specFilename = resolvePath("StorageStateComplexTypes/ComplexTypes.spec"),
            ruleName = "check1",
            primaryContract = "ComplexTypes",
        )

        genericWellFormednessChecks(callTrace)

        /* checking we're stopping at the assert that we expect, namely the one in CVL */

        checkViolatedAssert(callTrace) { ctv ->
            assertTrue(TACMeta.CVL_USER_DEFINED_ASSERT in ctv.violatedAssert.cmd.meta)
            assertEquals(TACSymbol.False, (ctv.violatedAssert.cmd as TACCmd.Simple.AssertCmd).o)
        }

        /* checking that the call trace contains the items we expect */

        val callTraceFlat = callTrace.callHierarchyRoot.allChildren()

        val stores =
            callTraceFlat.filterIsInstance<CallInstance.StorageInstance.Store>().toList()
        val globalStates =
            callTraceFlat.filterIsInstance<CallInstance.StorageTitleInstance>()
                .filter { it.name == GlobalState.LABEL_GLOBAL_STATE }.toList()
        val (stateAtStart, stateAtFunctionEnd, stateAtAssert) =
            globalStates.map { it.children.first().children.first().children.filterIsInstance<CallInstance.StorageStateValueInstance>() }

        // check the statusses of the global states and that we display one entry for each store
        assertTrue(stateAtStart.all { it.status == CallEndStatus.VARIABLE_DONT_CARE })
        assertTrue(stateAtFunctionEnd.all { it.changedSincePrev })
        assertTrue(stateAtAssert.none { it.changedSincePrev })
        assertTrue(stateAtAssert.all { it.changedSinceStart })
        assertEquals(stores.size, stateAtStart.size)
        assertEquals(stores.size, stateAtFunctionEnd.size)
        assertEquals(stores.size, stateAtAssert.size)
    }

    private fun checkViolatedAssert(callTrace: CallTrace, checks: (CallTrace.ViolationFound) -> Unit) {
        assertInstanceOf(CallTrace.ViolationFound::class.java, callTrace)
        checks(callTrace as CallTrace.ViolationFound)
    }


    @Test
    fun testLocalAssignments() {
        val counterExample = CallTraceInfra.runConfAndGetCounterExample(
            confPath = resolvePath("LocalAssignments/Basic.conf"),
            specFilename = resolvePath("LocalAssignments/Basic.spec"),
            ruleName = "test",
            primaryContract = "TestContract",
        )

        val assignments = counterExample.expectAssignments()
        val assignmentsByName = assignments.terminalsByName()

        fun checkAssignment(varName: String): LocalAssignments.Node.Terminal {
            val local = assignmentsByName[varName]
            assertNotNull(local, "local variable $varName not found in local assignments")
            return local
        }

        val t = checkAssignment("t")
        assertEquals("20", t.formattedValue(assignments.formatter))
        assertRangeMatches(t.range, SourcePosition(34u, 4u), SourcePosition(34u, 14u))

        val r1 = checkAssignment("r1")
        assertEquals("20", r1.formattedValue(assignments.formatter))
        assertRangeMatches(r1.range, SourcePosition(35u, 4u), SourcePosition(35u, 14u))

        val r2 = checkAssignment("r2")
        assertEquals("9", r2.formattedValue(assignments.formatter))
        assertRangeMatches(r2.range, SourcePosition(36u, 4u), SourcePosition(36u, 14u))

        val r3 = checkAssignment("r3")
        assertRangeMatches(r3.range, SourcePosition(37u, 4u), SourcePosition(37u, 14u))

        val r4 = checkAssignment("r4")
        assertRangeMatches(r4.range, SourcePosition(38u, 4u), SourcePosition(38u, 14u))


        assertTrue("ret" !in assignmentsByName)
        assertTrue("a" !in assignmentsByName)
        assertTrue("b" !in assignmentsByName)
        assertTrue("val" !in assignmentsByName)
        assertTrue("innerVal" !in assignmentsByName)
        assertTrue("contractVal" !in assignmentsByName)
        assertTrue("contractValOld" !in assignmentsByName)
        assertTrue("contractValNew" !in assignmentsByName)
    }

    // to be made into an actual test later on (during revising our testing infra)
//     @Test
    // TODO: CERT-9273
    @Suppress("UNUSED", "UNUSED_VARIABLE", "UNREACHABLE_CODE")
    fun testCvlStrings() {
        val counterExample = CallTraceInfra.runConfAndGetCounterExample(
            confPath = resolvePath("CVLStrings/cvlStrings.conf"),
            specFilename = resolvePath("CVLStrings/cvlStrings.spec"),
            ruleName = TODO("no idea what this test expects."),
            primaryContract = "cvlStrings",
        )
    }

    @Test
    fun testLocalAssignments_LenInExpression1() {
        val counterExample = CallTraceInfra.runConfAndGetCounterExample(
            confPath = resolvePath("LocalAssignments/TestArray.conf"),
            specFilename = resolvePath("LocalAssignments/TestArray.spec"),
            ruleName = "LenInExpression1",
            primaryContract = "TestArray",
        )

        val lenVarValue = counterExample.expectAssignments().terminalsByName().get(lenVarName)?.scalarValue
        val lenInstance = lenInstance(counterExample.callTrace!!)

        assertEquals(lenVarValue, lenInstance.value)
    }

    @Test
    fun testLocalAssignments_LenInExpression2() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("LocalAssignments2/TestArray.conf"),
            specFilename = resolvePath("LocalAssignments2/TestArray.spec"),
            ruleName = "LenInExpression2",
            primaryContract = "TestArray",
        )

        val lenVarValue = TACValue.valueOf(3) // as hardcoded in a require in the rule
        val lenInstance = lenInstance(callTrace)

        assertEquals(lenVarValue, lenInstance.value)
    }

    @Test
    fun testLocalAssignments_CheckUint25StaticArray() {
        val counterExample = CallTraceInfra.runConfAndGetCounterExample(
            confPath = resolvePath("LocalAssignments/TestArray.conf"),
            specFilename = resolvePath("LocalAssignments/TestArray.spec"),
            ruleName = "CheckUint25StaticArray",
            primaryContract = "TestArray",
        )

        val lenVarValue = counterExample.expectAssignments().terminalsByName().get(lenVarName)?.scalarValue

        assertTrue(lenVarValue == TACValue.valueOf(3))
    }

    @Test
    fun testSummarizationUnresolvedExternalDefaultCase() {
        ConfigScope(Config.OptimisticUnboundedHashing, true).extend(Config.IsAssumeUnwindCondForLoops, true).use {
            val callTrace = CallTraceInfra.runConfAndGetCallTrace(
                confPath = resolvePath("Summarization/Summarization.conf"),
                specFilename = resolvePath("Summarization/Summarization.spec"),
                ruleName = "unresolvedExternalForcingDefaultCase",
                primaryContract = "Test",
            )

            /* checking that the call trace contains the items we expect */
            val callTraceFlat = callTrace.callHierarchyRoot.allChildren().toList()

            val summaryInstances =
                callTraceFlat.filterIsInstance<CallInstance.InvokingInstance.SummaryInstance>().toList()
            assertEquals(1, summaryInstances.size)

            val defaultsToDispatcher =
                callTraceFlat.filterIsInstance<CallInstance.DispatcherSummaryDefaultInstance>().toList()

            assertEquals(1, defaultsToDispatcher.size)
        }
    }

    @Test
    fun testSummarizationUnresolvedExternal() {
        ConfigScope(Config.OptimisticUnboundedHashing, true).extend(Config.IsAssumeUnwindCondForLoops, true).use {
            val callTrace = CallTraceInfra.runConfAndGetCallTrace(
                confPath = resolvePath("Summarization/Summarization.conf"),
                specFilename = resolvePath("Summarization/Summarization.spec"),
                ruleName = "unresolvedExternal",
                primaryContract = "Test",
            )

            checkViolatedAssert(callTrace) { ctv ->
                assertTrue(TACMeta.CVL_USER_DEFINED_ASSERT in ctv.violatedAssert.cmd.meta)
            }

            /* checking that the call trace contains the items we expect */
            val callTraceFlat = callTrace.callHierarchyRoot.allChildren().toList()

            val summaryInstances =
                callTraceFlat.filterIsInstance<CallInstance.InvokingInstance.SummaryInstance>().toList()

            assertEquals(2, summaryInstances.size)
            val defaultsToDispatcher =
                callTraceFlat.filterIsInstance<CallInstance.DispatcherSummaryDefaultInstance>().toList()

            assertEquals(0, defaultsToDispatcher.size)
        }
    }

    @Test
    fun testInvRuleBankExample1() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("BankTestConf/BankTestConf.conf"),
            specFilename = resolvePath("BankTestConf/Bank.spec"),
            ruleName = "address_zero_cannot_become_an_account",
            primaryContract = "Bank",
            parametricMethodNames = listOf("deposit(uint256)"),
        )

        genericWellFormednessChecks(callTrace)

        checkViolatedAssert(callTrace) { ctv ->
            assertTrue(TACMeta.CVL_USER_DEFINED_ASSERT in ctv.violatedAssert.cmd.meta)
        }

        /* checking that the call trace contains the items we expect */
        val callTraceFlat = callTrace.callHierarchyRoot.allChildren().toList()

        val externalCalls =
            callTraceFlat.filterIsInstance<CallInstance.InvokingInstance.SolidityInvokeInstance.External>().toList()
        val internalCalls =
            callTraceFlat.filterIsInstance<CallInstance.InvokingInstance.SolidityInvokeInstance.Internal>().toList()

        // This example has no purely internal calls.

        // check that each external call is followed by a matching internal one
        externalCalls.zip(internalCalls).forEach { (extC, intC) ->
            assertEquals(extC.name, intC.name)
        }

        // check that we get the calls we expect (`getfunds`) is called in the invariant, so before and after running
        // the deposit function in the tac program
        assertEquals("Bank.getfunds", externalCalls[0].name)
        assertEquals("Bank.deposit", externalCalls[1].name)
        assertEquals("Bank.getfunds", externalCalls[2].name)

        // not sure what else to check here as I don't know what the example was intended to check
        // -- let's add checks when we can think of some
    }


    @Test
    fun testInvRuleBankExample2() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("BankTestConf/BankTestConf.conf"),
            specFilename = resolvePath("BankTestConf/Bank.spec"),
            ruleName = "address_zero_cannot_become_an_account",
            primaryContract = "Bank",
            parametricMethodNames = listOf("transfer(address,uint256)"),
        )

        genericWellFormednessChecks(callTrace)

        checkViolatedAssert(callTrace) { ctv ->
            assertTrue(TACMeta.CVL_USER_DEFINED_ASSERT in ctv.violatedAssert.cmd.meta)
        }

        /* checking that the call trace contains the items we expect */
        val callTraceFlat = callTrace.callHierarchyRoot.allChildren().toList()

        val externalCalls =
            callTraceFlat.filterIsInstance<CallInstance.InvokingInstance.SolidityInvokeInstance.External>().toList()
        val internalCalls =
            callTraceFlat.filterIsInstance<CallInstance.InvokingInstance.SolidityInvokeInstance.Internal>().toList()

        // This example has no purely internal calls.

        // check that each external call is followed by a matching internal one
        externalCalls.zip(internalCalls).forEach { (extC, intC) ->
            assertEquals(extC.name, intC.name)
        }

        // check that we get the calls we expect (`transfer`) is called in the invariant, so before and after running
        // the deposit function in the tac program
        assertEquals("Bank.getfunds", externalCalls[0].name)
        assertEquals("Bank.transfer", externalCalls[1].name)
        assertEquals("Bank.getfunds", externalCalls[2].name)

        // not sure what else to check here as I don't know what the example was intended to check
        // -- let's add checks when we can think of some
    }

    @Test
    fun testHavocStorage1() {
        val contractName = "HavocStorage"

        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("HavocStorage/run.conf"),
            specFilename = resolvePath("HavocStorage/HavocStorage.spec"),
            ruleName = "havoc_of_storage_path_is_recognized",
            primaryContract = contractName,
        )

        /** we expect exactly two contract storage state instances: from rule init and from the violated assert */
        val (startState, endState) = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.StorageTitleInstance>()
            .filter { it.name == contractName }

        val startValue = startState.children.single() as CallInstance.StorageStateValueInstance
        val endValue = endState.children.single() as CallInstance.StorageStateValueInstance

        /** at init, array index has not been havoced and has been require'd to true */
        assertEquals(CallEndStatus.VARIABLE_HAVOC, startValue.status)
        assertIsTrueOrOne(startValue.value.observedValue.scalarValue)
        assertFalse(startValue.changedSincePrev)

        /** at end, array index has been havoced and the solver would select its value as false */
        assertEquals(CallEndStatus.VARIABLE_HAVOC, endValue.status)
        assertIsFalseOrZero(endValue.value.observedValue.scalarValue)
        assertTrue(endValue.changedSincePrev)
    }

    private fun checkCallTraceJson(
        callTrace: CallTrace,
        verifierResultPath: Path,
        expectedJson: JsonObject
    ) {
        val actualJson = callTrace.callHierarchyRoot.treeViewRepBuilder.build().jsonObject
        dumpActualCallTraceJson(verifierResultPath, actualJson)
        verifyExpectedJson(expectedJson, actualJson)
    }

    /** Tests to check the parse tree of an assert command, displayed in the CallTrace, contains the correct value for
     * a struct's field access*/
    @Test
    fun testStructFieldAccessFunctionCall() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("StructAccess/FunctionCall/Basic.conf"),
            specFilename = resolvePath("StructAccess/FunctionCall/Basic.spec"),
            ruleName = "StructAccessFuncCall",
            primaryContract = "Basic",
        )
        val expectedCallInstance = CallInstance.CVLExpInstance.withStringExpAndValue(
            exp = "myStruct.num",
            range = Range.Range(
                specFile = "Basic.spec",
                start = SourcePosition(8u, 11u),
                end = SourcePosition(8u, 23u),
            ),
            value = TACValue.valueOf(0),
            formatterType = FormatterType.Value.CVL(CVLType.PureCVLType.Primitive.UIntK(256)),
            formatter = callTrace.formatter
        )
        val cvlExpInstances = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.CVLExpInstance>()
        assertTrue(cvlExpInstances.any { it.valueEquals(expectedCallInstance) })
    }

    @Test
    fun testStructFieldAccessSimple() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("StructAccess/Simple/Basic.conf"),
            specFilename = resolvePath("StructAccess/Simple/Basic.spec"),
            ruleName = "StructAccessSimple",
            primaryContract = "Basic",
        )

        val expectedCallInstance = CallInstance.CVLExpInstance.withStringExpAndValue(
            exp = "myStruct.num",
            range = Range.Range(
                specFile = "TestCVL",
                start = SourcePosition(7u, 11u),
                end = SourcePosition(7u, 23u),
            ),
            value = TACValue.valueOf(8),
            formatterType = FormatterType.Value.CVL(CVLType.PureCVLType.Primitive.UIntK(256)),
            formatter = callTrace.formatter,
        )
        val cvlExpInstances = callTrace
            .callHierarchyRoot
            .filterCallInstancesOf<CallInstance.CVLExpInstance>()
        assertTrue(cvlExpInstances.any { it.valueEquals(expectedCallInstance) })
    }

    // @Test
    fun rangeOfParametricInstantiation() {
        val plusOneSig = "plus_one()"
        val plusOneRange = Range.Range("MultipleCandidates.sol", SourcePosition(5u, 4u), SourcePosition(7u, 5u))

        val minusOneSig = "minus_one()"
        val minusOneRange = Range.Range("MultipleCandidates.sol", SourcePosition(9u, 4u), SourcePosition(11u, 5u))

        // this can become a JUnit parametric test annotation instead, for example by using `CSVSource`. whatever.
        val tests = listOf(
            Triple("chooseSame", plusOneSig, plusOneSig),
            Triple("chooseSame", minusOneSig, minusOneSig),
            Triple("chooseDifferent", plusOneSig, minusOneSig),
            Triple("chooseDifferent", minusOneSig, plusOneSig),
        )

        for (test in tests) {
            val (ruleName, firstMethodSig, secondMethodSig) = test

            val counterExample = CallTraceInfra.runConfAndGetCounterExample(
                confPath = resolvePath("ranges/Parametric.conf"),
                specFilename = resolvePath("ranges/Parametric.spec"),
                ruleName,
                primaryContract = "MultipleCandidates",
                listOf(firstMethodSig, secondMethodSig),
            )

            val rule = counterExample.rule as? CVLSingleRule
                ?: fail("expected single rule")

            // TODO: CERT-9273
            val methodInstantiations = rule.ruleGenerationMeta as? SingleRuleGenerationMeta.WithMethodInstantiations
                ?: fail("rule is parametric")

            val instantiationRange = methodInstantiations.range

            /** we only try to output a range if all instantiated ranges agree */
            val expectedRange = when {
                firstMethodSig == plusOneSig && secondMethodSig == plusOneSig -> plusOneRange
                firstMethodSig == minusOneSig && secondMethodSig == minusOneSig -> minusOneRange
                else -> Range.Empty()
            }

            assertTrue(instantiationRange == expectedRange)
        }
    }

    @Test
    fun sourceSegmentRangesHaveRelativePath() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("AssertCast/assert_cast.conf"),
            specFilename = resolvePath("AssertCast/Cast.spec"),
            ruleName = "AsParam",
            primaryContract = "Cast",
        )

        val branchStartInstances = callTrace.callHierarchyRoot.filterCallInstancesOf<CallInstance.BranchInstance.Start>()

        /** there's only one .sol file tested here, so all segment ranges should have the same file string */
        val filePathFromBranchInstances = branchStartInstances
            .map { it.branchSource.range.file }
            .sameValueOrNull()
            ?: fail("no branch start instances, or they don't all agree on the same file string")

        /** must be relative to the sources dir, no autofinder dirs expected here */
        assertEquals(filePathFromBranchInstances, "Cast.sol")
    }

    @Test
    fun returnValueParseTree() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("CVLFunction/run.conf"),
            specFilename = resolvePath("CVLFunction/test.spec"),
            ruleName = "returnValues",
            primaryContract = "TestContract",
        )

        val funcInstance = callTrace
            .callHierarchyRoot
            .findChild { it.name == "_ = math(x, 9)" }
            ?.findChild { it is CallInstance.InvokingInstance.CVLFunctionInstance && it.name == "math" }
            ?: fail("function call not found")

        // we should eventually have subclasses specifically for certain kinds of instances
        // (or some other strongly-typed identifier),
        // so that we can find something like a return instance without matching on strings
        val returnInstance = funcInstance.findChild { it.name.startsWith("return") }
            ?: fail("return statement not found")


        // XXX: we appear to have an operator precedence bug here, possibly related to CERT-2555.
        // this affects the order of the nodes. fix this test when the precedence issue is fixed.

        val n1 = returnInstance.children.assertSingle()
        assertEquals(n1.expString(), "x + y / z * x")
        val (n2, n3) = n1.children.assertSize(2)
        assertEquals(n2.expString(), "x")
        assertEquals(n3.expString(), "y / z * x")
        val (n4, n5) = n3.children.assertSize(2)
        assertEquals(n4.expString(), "y / z")
        assertEquals(n5.expString(), "x")
        val (n6, n7) = n4.children.assertSize(2)
        assertEquals(n6.expString(), "y")
        assertEquals(n7.expString(), "z")

        for (n in listOf(n2, n5, n6, n7)) {
            assertTrue(n.children.isEmpty())
        }
    }

    @Test
    fun storageRevertsForPersistentGhosts() {
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = resolvePath("StorageRestore/run.conf"),
            specFilename = resolvePath("StorageRestore/Restore.spec"),
            ruleName = "persistence",
            primaryContract = "Restore",
            parametricMethodNames = listOf("dummy()"),
        )

        val ghostsStateAtInitial = callTrace
            .callHierarchyRoot
            .findChild { it.toString() == "f(e, args) at initial" }
            ?.findChild { it.toString() == "initial" }
            ?.findChild { it.toString() == "Ghosts State" }
            ?: fail()

        fun valueAtInitial(name: String) = ghostsStateAtInitial
            .findChildOfType<CallInstance.GhostValueInstance> { it.value.storagePath.toString() == name }
            ?.value
            ?.observedValue
            ?.scalarValue
            ?: fail()

        assertEquals(TACValue.PrimitiveValue.Bool.True, valueAtInitial("non_persi"))
        assertEquals(TACValue.PrimitiveValue.Bool.False, valueAtInitial("persi"))

        assertEquals("test should end here", callTrace.assertMessage)
    }
}

/** Checks for structural invariants that we might want to check on a given call trace. Some apply to all call
 * traces, some are more specific. */
internal object StructuralInvariants {
    /**
     * Verifies if the provided [callTrace] matches the expected violated assertion.
     * Important Note: Because `tac.assert.id` is allocated by the allocator, it's not deterministic between runs.
     * Therefore, the comparison process must ignore that field of the [LTACCmd.cmd] in [TACCmd.Simple.AssertCmd] type.
     *
     * @param expectedViolatedAssert The expected assertion condition to be violated.
     * @param callTrace The call trace to be verified.
     * @throws AssertionError If the verification fails, indicating a violation of the expected assertion condition.
     */
    // @Deprecated("like others, I suspect this is too generic to be useful")

    fun verifyViolateAssert(expectedViolatedAssert: LTACCmd, callTrace: CallTrace) {
        when (callTrace) {
            is CallTrace.Failure ->
                assertUnreachable { "Verification failed: Expected violation, but the call trace is a failure." }
            is CallTrace.DisabledByConfig -> `impossible!`
            is CallTrace.ViolationFound -> {
                if (expectedViolatedAssert.ptr != callTrace.violatedAssert.ptr) {
                    assertUnreachable {
                        "Verification failed: Expected assertion pointer ${expectedViolatedAssert.ptr}," +
                            " but got ${callTrace.violatedAssert.ptr}"
                    }
                } else {
                    val expectedCmd = expectedViolatedAssert.cmd
                    val actualCmd = callTrace.violatedAssert.cmd

                    val cmdMismatchMessage = "Verification failed: Mismatch in command."

                    when {
                        expectedCmd is TACCmd.Simple.AssertCmd && actualCmd is TACCmd.Simple.AssertCmd -> {
                            if (expectedCmd.o == actualCmd.o && expectedCmd.msg == actualCmd.msg) {
                                return  // Verification successful, no need for an error message.
                            } else {
                                assertEquals(expectedCmd, actualCmd) {
                                    "$cmdMismatchMessage Expected assertion condition (${expectedCmd.o}, ${expectedCmd.msg})," +
                                        " but got (${actualCmd.o}, ${actualCmd.msg})"
                                }
                            }
                        }

                        expectedCmd is TACCmd.Simple.AnnotationCmd && actualCmd is TACCmd.Simple.AnnotationCmd -> {
                            if (expectedCmd.annot.k != TACMeta.SNIPPET || expectedCmd.annot.v !is AssertSnippet<*>) {
                                assertUnreachable {
                                    "$cmdMismatchMessage Expected an assertion annotation with a snippet, but found ${expectedCmd.annot.k} with value ${expectedCmd.annot.v}"
                                }
                            } else if (expectedCmd.annot.v != actualCmd.annot.v) {
                                assertUnreachable {
                                    "$cmdMismatchMessage Assertion snippet mismatch: Expected ${expectedCmd.annot.v}," +
                                        " but got ${actualCmd.annot.v}"
                                }
                            } else {
                                return  // Verification successful, no need for an error message.
                            }
                        }

                        else -> assertUnreachable { "$cmdMismatchMessage Invalid command type." }
                    }
                }
            }
        }
    }


    /**
     * Verifies there are no other instances of [CallInstance.InvokingInstance.CVLRootInstance] below the given one
     *  in the argument.
     *
     * @param callHierarchyRoot The root of the call hierarchy.
     * @throws AssertionError If the verification fails, indicating a violation of the single root node condition.
     */
    fun verifySingleCvlRootInstance(callHierarchyRoot: CallInstance.InvokingInstance.CVLRootInstance) {
        assertTrue(
            CallTraceInfra.findNodes(callHierarchyRoot) { node ->
                node.parent == null
            }.let { it.size == 1 && it.single() is CallInstance.InvokingInstance.CVLRootInstance }
        ) { "There must be only 1 root with type CallInstance.InvokingInstance.CVLRootInstance" }
    }

    /**
     * Checks that no node is its own descendant/ancestor via the [CallInstance.children] hierarchy.
     *
     * (see also [CallTraceInfra.checkNoCycles])
     *
     * old description:
     *
     * Verifies that the call hierarchy follows the single path per node structure,
     * where each node has at most one parent.
     * single path per node structure means there are no diamonds, and indeed,
     * there is exact one parent for each node (except the root of course).
     *
     * @param callHierarchyRoot The root of the call hierarchy.
     * @throws AssertionError If the verification fails, indicating a violation of the single path per node structure.
     */
    fun verifyNoCyclesInCallInstanceChildren(callHierarchyRoot: CallInstance.InvokingInstance.CVLRootInstance) {
        assertTrue(CallTraceInfra.checkNoCycles(callHierarchyRoot)) {
            "CallHierarchyRoot Violates Single Path Per Node Structure"
        }
    }

    /**
     * Verifies that casting operations are followed by corresponding [CallInstance.CastCheckInstance] nodes in the call hierarchy.
     *
     * @param callHierarchyRoot The root of the call hierarchy.
     * @throws AssertionError If the verification fails, indicating a violation of the casting operation condition.
     */
    fun verifyAssertCast(callHierarchyRoot: CallInstance.InvokingInstance.CVLRootInstance) {
        val assertCasts = CallTraceInfra.findNodes(callHierarchyRoot) {
            it.name.contains("assert_")
        }
        assertTrue(assertCasts.isNotEmpty()) { "No casting operations found" }

        val attributes = ArrayDeque<Predicate<CallInstance>>()
        attributes.add(Predicate<CallInstance> { it is CallInstance.CastCheckInstance })

        val result = assertCasts.map {
            CallTraceInfra.pathEndingsWithPredicates(
                it.children.toSet(),
                attributes.clone().uncheckedAs()
            )
        }.map {
            it.isNotEmpty()
        }.all { it }

        assertTrue(result) { "There are casting calls that are not followed by CallInstance.CastCheckInstance" }
    }


    /**
     * Verifies that all assert operations in the call hierarchy have children.
     *
     * @param callHierarchyRoot The root of the call hierarchy.
     * @throws AssertionError If the verification fails, indicating a violation of the assert operation condition.
     */
    fun verifyAssertChildren(callHierarchyRoot: CallInstance.InvokingInstance.CVLRootInstance) {
        val asserts = CallTraceInfra.findNodes(callHierarchyRoot) {
            /** (alex n:) this should probably cover all assert-likes, no? (as in [verifyEndsInAssert]) -- then it's
             * likey applicable in all cases where there's a call trace, like [verifyEndsInAssert] is ..)*/
            it.name.contains("assert ")
        }
        assertTrue(asserts.isNotEmpty()) { "Rule violated before assert operations" }
        assertTrue(asserts.map { it.children.isNotEmpty() }.all { it }) { "There are assert operations without children" }
    }


    /**
     * Verifies that the call hierarchy contains a [CallInstance.StorageTitleInstance] node with the name "Global State".
     * @param callHierarchyRoot The root of the call hierarchy.
     * @throws AssertionError If the verification fails, indicating the absence of the "Global State" storage instance.
     */
    fun verifyHasGlobalState(callHierarchyRoot: CallInstance.InvokingInstance.CVLRootInstance) =
        assertTrue(CallTraceInfra.nodeExists(callHierarchyRoot) {
            it is CallInstance.StorageTitleInstance
                && it.name == "Global State"
        }) { "StorageInstance with the name 'Global State' is not present in the structure" }


    /**
     * Verifies that the [CallTrace] finalizes only after passing through an assert of some form.
     *
     * @param callHierarchyRoot The root of the call hierarchy.
     * @throws AssertionError If the verification fails, indicating a violation of the finality condition.
     */
    fun verifyEndsInAssert(callHierarchyRoot: CallInstance.InvokingInstance.CVLRootInstance) {
        val testSet = CallTraceInfra.pathEndingsWithPredicates(callHierarchyRoot) {
            // (alex n:) if we keep this, this warrants an interface "AssertionCallInstance"
            // .. or other structuring of the call instances ..
            !(it.name.contains("assert ") ||
                it is CallInstance.CastCheckInstance ||
                it is CallInstance.DivZeroInstance ||
                it is CallInstance.LoopInstance.AssertUnwindCond ||
                it is CallInstance.ViewReentrancyInstance
                )
        }
        val leafs = CallTraceInfra.getTreeLeafs(callHierarchyRoot)

        assertTrue(
            leafs.subtract(testSet).isNotEmpty()
        ) { "The callTrace finalizes without passing through an expected CallInstance" }
    }

    /**
     * Verifies whether the actual JSON matches the expected JSON and throws an assertion error if differences are found.
     *
     * @param expectedJson The expected JSON object to compare.
     * @param actualJson The actual JSON object to compare.
     * @throws AssertionError If differences are found between the expected and actual JSON objects.
     */
//    @Deprecated("relies on json comparisons -- it's very hard to judge whether this failing is actually a " +
//        "problem, or whether one just needs to regenerate the expected-json")
    fun verifyExpectedJson(expectedJson: JsonObject, actualJson: JsonObject) {
        // Compare the "callTrace" property of the expected JSON with the actual JSON
        val diff = CallTraceInfra.compareJson(expectedJson, actualJson)
        assertEquals(emptyList<String>(), diff)
    }

    /**
     * Defines a higher-order function that enables running a set of global properties checks on a provided instance.
     *
     * @param checks The list of checks to be executed on the input instance.
     * @return A function that, when invoked with an instance, applies all specified checks to it.
     */
    fun runChecks(
        vararg checks: (CallInstance.InvokingInstance.CVLRootInstance) -> Unit
    ): (CallInstance.InvokingInstance.CVLRootInstance) -> Unit {
        return { input ->
            for (check in checks) {
                check(input)
            }
        }
    }

    /**
     * A predefined collection of global properties checks for CallInstance.InvokingInstance.CVLRootInstance instances.
     */
    val globalPropertiesChecks = runChecks(
        ::verifyEndsInAssert,
        ::verifyNoCyclesInCallInstanceChildren,
        ::verifySingleCvlRootInstance
    )
}


// some local helper functions

private fun assertRangeMatches(actual: Range?, expectedStart: SourcePosition, expectedEnd: SourcePosition) {
    check(actual is Range.Range) { "expected range.Range but got $actual" }

    assertTrue(actual.start == expectedStart && actual.end == expectedEnd) {
        "ranges don't match. expected ($expectedStart, $expectedEnd) but got (${actual.start}, ${actual.end})"
    }
}

private fun lenInstance(callTrace: CallTrace): CallInstance.CVLExpInstance {
    return callTrace
        .callHierarchyRoot
        .filterCallInstancesOf<CallInstance.CVLExpInstance>()
        .single { it.expString() == lenVarName }
}

private const val lenVarName = "arr.length"

private fun CallInstance.CVLExpInstance.valueEquals(other: CallInstance.CVLExpInstance): Boolean =
    this.name == other.name && this.value == other.value

private fun <T> List<T>.assertSingle(): T =
    when (val size = this.size) {
        1 -> this.single()
        else -> fail("expected exactly 1 element, but got $size")
    }

private fun <T> List<T>.assertSize(size: Int): List<T> {
    assertEquals(this.size, size, "expected exactly $size elements, but got ${this.size}")
    return this
}

private fun assertUnreachable(msg: () -> String) {
    assertTrue(false, msg)
}

private val TACCmd.Simple.assertMessage: String get() =
    (this as TACCmd.Simple.AssertCmd).description

private fun String.trimQuotes(): String {
    val quote = "\""
    return if (this.startsWith(quote) && this.endsWith(quote)) {
        substring(1, length - 1)
    } else {
        this
    }
}

private val CallTrace.assertMessage: String get() {
    assertTrue(this is CallTrace.ViolationFound)
    return (this as CallTrace.ViolationFound).violatedAssert.cmd.assertMessage.trimQuotes()
}

private fun CallInstance.expString(): String? {
    return if (this is CallInstance.CVLExpInstance) {
        this.sarif.pieces.first().substringBefore(Sarif.EVALUATES_TO)
    } else {
        null
    }
}


private fun assertIsFalseOrZero(v: TACValue?) {
    assertTrue(v == TACValue.False || v == TACValue.valueOf(0))
}

private fun assertIsTrueOrOne(v: TACValue?) {
    assertTrue(v == TACValue.True || v == TACValue.valueOf(1))
}

private val LocalAssignments.Node.Terminal.scalarValue: TACValue?
    get() = when (val state = this.state) {
        LocalAssignments.State.ByteMap -> null
        is LocalAssignments.State.CVLString -> state.contents
        is LocalAssignments.State.Contract -> state.value
        is LocalAssignments.State.Initialized -> state.value
        LocalAssignments.State.InitializedButMissing -> null
        LocalAssignments.State.Uninitialized -> null
    }

private fun CounterExample.expectAssignments(): LocalAssignments =
    this.localAssignments ?: fail("expected assignments, got null")
