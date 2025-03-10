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
import report.LocalAssignment
import report.LocalAssignmentState
import report.calltrace.CallEndStatus
import report.calltrace.CallInstance
import report.calltrace.CallTrace
import report.calltrace.formatter.AlternativeRepresentations
import report.calltrace.formatter.AlternativeRepresentations.Representations
import report.calltrace.formatter.FormatterType
import report.calltrace.sarif.Sarif
import report.globalstate.GlobalState
import solver.StructuralInvariants.globalPropertiesChecks
import solver.StructuralInvariants.verifyAssertCast
import solver.StructuralInvariants.verifyAssertChildren
import solver.StructuralInvariants.verifyExpectedJson
import solver.StructuralInvariants.verifyHasGlobalState
import solver.StructuralInvariants.verifyViolateAssert
import spec.cvlast.CVLRange
import spec.cvlast.CVLSingleRule
import spec.cvlast.CVLType
import spec.cvlast.SingleRuleGenerationMeta
import utils.*
import vc.data.*
import vc.data.state.TACValue
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.function.Predicate
import kotlin.io.path.Path

class CallTraceTest {
    /** When this is true, every test that compares call trace jsons, actual vs expected, will dump the current
     * actual one to disk in the test folder. That way one can use a regular diff tool to see the changes and update
     * the expected one if needed. */
    private val dumpActualCallTraceJsons: Boolean = true // set false on master

    /** Used when [dumpActualCallTraceJsons] is set, for dumping actual call trace jsons for offline comparison.  */
    private val jsonPP = Json { prettyPrint = true }

    /** The string used by CallTraceFormatter.UNKNOWN_VALUE, used in comparisons in some tests. */
    private val unknownStr = "..."

    @BeforeEach
    fun beforeEach() {
        noXs = TestLoggers.CallTrace.NoXs()
    }


    /**
     * Very basic test, the rule just checks whether a uint parameter can be odd.
     *
     * We check existence of the assert message, and some generic [StructuralInvariants].
     */
    @Test
    fun testMod() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/mod/mod.conf")
        val specPath = Path("src/test/resources/solver/CallTraceTests/mod/mod.spec")


        // this could still be made better by reading everything from the conf file, but I think I'll need some
        // infrastructure advice
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "even",
            methodName = null,
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
        val confPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/assert_cast.conf")
        val specPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/Cast.spec")

        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "AsParam",
            methodName = null,
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
        val confPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/assert_cast.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/ComplexExp/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
        verifyViolateAssert(expectedViolatedAssert, callTrace)

        verifyHasGlobalState(callTrace.callHierarchyRoot)
        verifyAssertChildren(callTrace.callHierarchyRoot)
        verifyAssertCast(callTrace.callHierarchyRoot)

        globalPropertiesChecks(callTrace.callHierarchyRoot)

    }

    @Test
    fun testAssertCastRuleCVLFunc() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/assert_cast.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/CVLFunc/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
        verifyViolateAssert(expectedViolatedAssert, callTrace)

        genericWellFormednessChecks(callTrace)
    }

    @Test
    fun testAssertCastRuleDefinitionStatement() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/assert_cast.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/DefinitionStatement/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
        verifyAssertCast(callTrace.callHierarchyRoot)

        // case fail even there is a use of function call
        verifyViolateAssert(expectedViolatedAssert, callTrace)
        verifyHasGlobalState(callTrace.callHierarchyRoot)

        globalPropertiesChecks(callTrace.callHierarchyRoot)
    }

    @Test
    fun testAssertCastRuleIfStatement() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/assert_cast.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/IfStatement/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
        verifyViolateAssert(expectedViolatedAssert, callTrace)

        verifyHasGlobalState(callTrace.callHierarchyRoot)
        verifyAssertChildren(callTrace.callHierarchyRoot)
        verifyAssertCast(callTrace.callHierarchyRoot)

        globalPropertiesChecks(callTrace.callHierarchyRoot)
    }

    @Test
    fun testAssertCastRuleToSignedInt() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/assert_cast.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/ToSignedInt/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)


        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
        verifyViolateAssert(expectedViolatedAssert, callTrace)

        verifyHasGlobalState(callTrace.callHierarchyRoot)
        verifyAssertChildren(callTrace.callHierarchyRoot)
        verifyAssertCast(callTrace.callHierarchyRoot)

        globalPropertiesChecks(callTrace.callHierarchyRoot)
    }


    @Test
    fun testStructPassingToCVLFunc1() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionStructs/run.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionStructs/checkWorkOnS/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
        verifyViolateAssert(expectedViolatedAssert, callTrace)

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
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionStructs/run.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionStructs/checkWorkOnSCVL/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
        verifyViolateAssert(expectedViolatedAssert, callTrace)

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
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionStructs/Nested/run.conf")
        val variant1 = """workOnSComplexCVL\(x=${numberRE}, s=\{s1\.x=${numberRE}, s1\.y=${addressRE}, s1\.z1=${numberRE}, s1\.z2=${numberRE}, s1\.b1=${boolRE}, s1\.x2=${numberRE}, s1\.b2=${boolRE}, s2\.x=${numberRE}, s2\.y=${addressRE}, s2\.z1=${numberRE}, s2\.z2=${numberRE}, s2\.b1=${boolRE}, s2\.x2=${numberRE}, s2\.b2=${boolRE}, b3=${boolRE}}\)"""
        val variant2 = """workOnSCVL\(x=${numberRE}, s=\{x=${numberRE}, y=${addressRE}, z1=${numberRE}, z2=${numberRE}, b1=${boolRE}, x2=${numberRE}, b2=${boolRE}}\)"""

        val verifierResultPathToStrings = listOf(
            "checkWorkOnS1" to variant1,
            "checkWorkOnS1_2" to variant1,
            "checkWorkOnS2" to variant2,
            "checkWorkOnS2_2" to variant2,
            "checkWorkOnSCVL1" to variant2,
            "checkWorkOnSCVL2" to variant2,
            "checkWorkOnSCVL1" to variant2,
        ).mapFirst { fileName -> confPath.parent.resolve(fileName) }

        verifierResultPathToStrings.forEach { (verifierResultPath, uiStringRegex) ->
            val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
            val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

            checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
            verifyViolateAssert(expectedViolatedAssert, callTrace)

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
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionBasic/run.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionBasic/CvlFunctionTest/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)
        verifyViolateAssert(expectedViolatedAssert, callTrace)

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
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionComplex/run.conf")
        val specPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionComplex/Complex.spec") // reading this not through our framework, so no leading "/"

        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "test_calldataarg",
            methodName = null,
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
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionComplex/run.conf")
        val specPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionComplex/Complex.spec") // reading this not through our framework, so no leading "/"

        // this could still be made better by reading everything from the conf file, but I think I'll need some
        // infrastructure advice
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "test_static_array",
            methodName = null,
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
            val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionComplex/run.conf")

            val specPath =
                Path("src/test/resources/solver/CallTraceTests/CVLFunctionComplex/Complex.spec") // reading this not through our framework, so no leading "/"

            // this could still be made better by reading everything from the conf file, but I think I'll need some
            // infrastructure advice
            val callTrace = CallTraceInfra.runConfAndGetCallTrace(
                confPath = confPath,
                specFilename = specPath,
                ruleName = "test_dynamic_array",
                methodName = null,
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
    fun testCVLFunctionComplexTestStringAndBytes() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionComplex/run.conf")
        val specPath = Path("src/test/resources/solver/CallTraceTests/CVLFunctionComplex/Complex.spec") // reading this not through our framework, so no leading "/"

        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "test_string_and_bytes",
            methodName = null,
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
        val confPath = Path("src/test/resources/solver/CallTraceTests/StorageStateComplexTypes/run.conf")
        val specPath = Path("src/test/resources/solver/CallTraceTests/StorageStateComplexTypes/ComplexTypes.spec")

        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "check1",
            methodName = null,
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

        // check that we display all the stores
        // in the solidity source we assign constant values, so there is only one model when it comes to the relevant
        // values, so we can do a straightforward string comparison
        val expecteds = listOf(
            Pair("Store at ComplexTypes.ownerAddr", "0x8000"),
            Pair("Store at ComplexTypes.flag", "true"),
            Pair("Store at ComplexTypes.ui256", "0x10"),
            Pair("Store at ComplexTypes.i256", "0x20"),
            Pair("Store at ComplexTypes.neg[true]", "false"),
            Pair("Store at ComplexTypes.neg[false]", "true"),
            Pair("Store at ComplexTypes.addressMap[0x11]", "true"),
            Pair("Store at ComplexTypes.addressMap[0x22]", "false"),
            Pair("Store at ComplexTypes.dict[0x1].num", "0x64"),
            Pair("Store at ComplexTypes.dict[0x1].addr", "0x140"),
            Pair("Store at ComplexTypes.dict[0x1].innerMap[0x3]", "false"),
            Pair("Store at ComplexTypes.dict[0x1].innerMap[0x5]", "true"),
            Pair("Store at ComplexTypes.dict[0x2].num", "0x6e"),
            Pair("Store at ComplexTypes.dict[0x2].addr", "0x150"),
            Pair("Store at ComplexTypes.dict[0x2].innerMap[0x7]", "true"),
            Pair("Store at ComplexTypes.dict[0x2].innerMap[0x8]", "false"),
        )
        for ((store, expected) in stores.zip(expecteds)) {
            val (expectedPrefix, expectedLiteralValue) = expected

            assertTrue(store.sarif.flatten(Representations.Hex).startsWith(expectedPrefix))
            assertEquals(
                // using lastOrNull here because the storage path also might have sarif args
                store.sarif.args.lastOrNull()?.values?.get(Representations.Pretty),
                expectedLiteralValue,
            )
        }
    }

    private fun checkViolatedAssert(callTrace: CallTrace, checks: (CallTrace.ViolationFound) -> Unit) {
        assertInstanceOf(CallTrace.ViolationFound::class.java, callTrace)
        checks(callTrace as CallTrace.ViolationFound)
    }


    @Test
    fun testLocalVariables() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/LocalVariables/Basic.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/LocalVariables/test/")
        val counterExample = CallTraceInfra.getCounterExampleFromSerialized(confPath, verifierResultPath)
        val localAssignments = counterExample.localAssignments

        fun checkAssignment(varName: String): LocalAssignment {
            val local = localAssignments[varName]
            assertNotNull(local, "local variable $varName not found in local assignments")
            return local
        }

        val t = checkAssignment("t")
        assertEquals("0x14", t.formattedValue)
        assertRangeMatches(t.range, SourcePosition(34u, 4u), SourcePosition(34u, 14u))

        val r1 = checkAssignment("r1")
        assertEquals("0x14", r1.formattedValue)
        assertRangeMatches(r1.range, SourcePosition(35u, 4u), SourcePosition(35u, 14u))

        val r2 = checkAssignment("r2")
        assertEquals("0x9", r2.formattedValue)
        assertRangeMatches(r2.range, SourcePosition(36u, 4u), SourcePosition(36u, 14u))

        val r3 = checkAssignment("r3")
        assertRangeMatches(r3.range, SourcePosition(37u, 4u), SourcePosition(37u, 14u))

        val r4 = checkAssignment("r4")
        assertRangeMatches(r4.range, SourcePosition(38u, 4u), SourcePosition(38u, 14u))


        assertTrue("ret" !in localAssignments)
        assertTrue("a" !in localAssignments)
        assertTrue("b" !in localAssignments)
        assertTrue("val" !in localAssignments)
        assertTrue("innerVal" !in localAssignments)
        assertTrue("contractVal" !in localAssignments)
        assertTrue("contractValOld" !in localAssignments)
        assertTrue("contractValNew" !in localAssignments)
    }

    // to be made into an actual test later on (during revising our testing infra)
//     @Test
    fun testCvlStrings() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLStrings/cvlStrings.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/CVLStrings/test/")
        // val counterExample = CallTraceInfra.getCounterExample(confPath, verifierResultPath)
        val callTrace: CallTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        // val localAssignments = counterExample.localAssignments
        // unused(localAssignments)
        unused(callTrace)
        assertTrue(true)
    }

    @Test
    fun testLocalVariables_LenInExpression1() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/LocalVariables/TestArray.conf")
        val specPath =
            Path("src/test/resources/solver/CallTraceTests/LocalVariables/TestArray.spec") // reading this not through our framework, so no leading "/"

        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "LenInExpression1",
            methodName = null,
            primaryContract = "TestArray",
        )

        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/LocalVariables/LenInExpression1/")
        val counterExample = CallTraceInfra.getCounterExampleFromSerialized(confPath, verifierResultPath)
        val lenVarValue = counterExample.localAssignments[lenVarName]?.scalarValue
        val lenInstance = lenInstance(callTrace)

        assertEquals(lenVarValue, lenInstance.value)
    }

    @Test
    fun testLocalVariables_LenInExpression2() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/LocalVariables2/TestArray.conf")

        val specPath = Path("src/test/resources/solver/CallTraceTests/LocalVariables2/TestArray.spec") // reading this not through our framework, so no leading "/"

        // this could still be made better by reading everything from the conf file, but I think I'll need some
        // infrastructure advice
        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "LenInExpression2",
            methodName = null,
            primaryContract = "TestArray",
        )

        val lenVarValue = TACValue.valueOf(3) // as hardcoded in a require in the rule
        val lenInstance = lenInstance(callTrace)

        assertEquals(lenVarValue, lenInstance.value)
    }

    @Test
    fun testLocalVariables_CheckUint25StaticArray() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/LocalVariables/TestArray.conf")
        val verifierResultPath =
            Path("src/test/resources/solver/CallTraceTests/LocalVariables/Satisfy_extSum__cvlSum_LPTestArray_spec_17_5RP/")
        val counterExample = CallTraceInfra.getCounterExampleFromSerialized(confPath, verifierResultPath)

        val lenVarValue = counterExample.localAssignments[lenVarName]?.scalarValue

        assertTrue(lenVarValue == TACValue.valueOf(3))
    }

    @Test
    fun testInvRuleBankExample1() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/BankTestConf/BankTestConf.conf")
        val specPath = Path("src/test/resources/solver/CallTraceTests/BankTestConf/Bank.spec")

        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "address_zero_cannot_become_an_account",
            methodName = "deposit(uint256)",
            primaryContract = "Bank",
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
    fun testInvRuleBankExample2(){
        val confPath = Path("src/test/resources/solver/CallTraceTests/BankTestConf/BankTestConf.conf")
        val specPath = Path("src/test/resources/solver/CallTraceTests/BankTestConf/Bank.spec")

        val callTrace = CallTraceInfra.runConfAndGetCallTrace(
            confPath = confPath,
            specFilename = specPath,
            ruleName = "address_zero_cannot_become_an_account",
            methodName = "transfer(address,uint256)",
            primaryContract = "Bank",
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
        val confPath = Path("src/test/resources/solver/CallTraceTests/HavocStorage/run.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/HavocStorage/havoc_of_storage_path_is_recognized")
        val contractName = "HavocStorage"

        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)

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


    @Test
    fun testSatisfyTest(){
        val confPath = Path("src/test/resources/solver/CallTraceTests/satisfyTest/satisfyTest.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/satisfyTest/Satisfy_a__0_LPsatisfyTest_spec_7_5RP")

        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)

        verifyViolateAssert(expectedViolatedAssert, callTrace)
    }

    @Test
    fun testSatisfyTest2Satisfy(){
        val confPath = Path("src/test/resources/solver/CallTraceTests/satisfyTest2/satisfyTest.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/satisfyTest2/Satisfy_a__0_LPsatisfyTest_spec_8_5RP")

        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)

        verifyViolateAssert(expectedViolatedAssert, callTrace)
    }

    @Test
    fun testSatisfyTest2Assertions(){
        val confPath = Path("src/test/resources/solver/CallTraceTests/satisfyTest2/satisfyTest.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/satisfyTest2/Assertions")

        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)

        verifyViolateAssert(expectedViolatedAssert, callTrace)
    }

    @Test
    fun testSatisfyTest3(){
        val confPath = Path("src/test/resources/solver/CallTraceTests/satisfyTest3/satisfyTest.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/satisfyTest3/Satisfy_a__0_LPsatisfyTest_spec_8_5RP")

        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val (expectedViolatedAssert, expectedJson) = CallTraceInfra.getExpectedAssertAndJson(verifierResultPath)

        checkCallTraceJson(callTrace, verifierResultPath, expectedJson)

        verifyViolateAssert(expectedViolatedAssert, callTrace)
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
    fun testStructFieldAccessFunctionCall(){
        val confPath = Path("src/test/resources/solver/CallTraceTests/StructAccess/FunctionCall/Basic.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/StructAccess/FunctionCall/StructAccessFuncCall")

        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val expectedCallInstance = CallInstance.CVLExpInstance.withStringExpAndValue(
            exp = "myStruct.num",
            range = CVLRange.Range(
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
        assertTrue(cvlExpInstances.any { it.equals_(expectedCallInstance) })
    }

    @Test
    fun testStructFieldAccessSimple() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/StructAccess/Simple/Basic.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/StructAccess/Simple/StructAccessSimple")

        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)
        val expectedCallInstance = CallInstance.CVLExpInstance.withStringExpAndValue(
            exp = "myStruct.num",
            range = CVLRange.Range(
                specFile = "Basic.spec",
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
        assertTrue(cvlExpInstances.any { it.equals_(expectedCallInstance) })
    }

    @Test
    fun rangeOfParametricInstantiation() {
        val baseDir = Path("src/test/resources/solver/CallTraceTests/ranges/")

        val confPath = baseDir.resolve("Parametric.conf")
        val artifactDirs = listOf(
            "minus_oneLPRP-minus_oneLPRP",
            "minus_oneLPRP-plus_oneLPRP",
            "plus_oneLPRP-minus_oneLPRP",
            "plus_oneLPRP-plus_oneLPRP"
        ).map(baseDir::resolve)

        val plusOneSig = "plus_one()"
        val plusOneRange = CVLRange.Range("MultipleCandidates.sol", SourcePosition(5u, 4u), SourcePosition(7u, 5u))

        val minusOneSig = "minus_one()"
        val minusOneRange = CVLRange.Range("MultipleCandidates.sol", SourcePosition(9u, 4u), SourcePosition(11u, 5u))

        for (dir in artifactDirs) {
            val counterExample = CallTraceInfra.getCounterExampleFromSerialized(confPath, dir)

            val rule = counterExample.rule as? CVLSingleRule
                ?: fail("expected single rule")
            val methodInstantiations = rule.ruleGenerationMeta as? SingleRuleGenerationMeta.WithMethodInstantiations
                ?: fail("rule is parametric")

            val signatures = methodInstantiations.instMethodSignatures
            val instantiationRange = methodInstantiations.cvlRange

            val (firstMethodSig, secondMethodSig) = signatures.assertSize(2)

            /** we only try to output a range if all instantiated ranges agree */
            val expectedRange = when {
                firstMethodSig == plusOneSig  && secondMethodSig == plusOneSig  -> plusOneRange
                firstMethodSig == minusOneSig && secondMethodSig == minusOneSig -> minusOneRange
                else -> CVLRange.Empty()
            }

            assertTrue(instantiationRange == expectedRange)
        }
    }

    @Test
    fun sourceSegmentRangesHaveRelativePath() {
        val confPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/assert_cast.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/AssertCast/AsParam/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)

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
        val confPath = Path("src/test/resources/solver/CallTraceTests/CVLFunction/run.conf")
        val verifierResultPath = Path("src/test/resources/solver/CallTraceTests/CVLFunction/returnValues/")
        val callTrace = CallTraceInfra.getCallTraceFromSerialized(confPath, verifierResultPath)

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
     * Verifies that the call hierarchy contains instances of loop operations and that each loop instance is a successor
     * of a Solidity invocation.
     *
     * @param callHierarchyRoot The root of the call hierarchy.
     * @throws AssertionError If the verification fails, indicating a violation of the loop instance condition.
     */
    fun verifyHasLoop(callHierarchyRoot: CallInstance.InvokingInstance.CVLRootInstance) {
        val loopInstances = CallTraceInfra.findNodes(callHierarchyRoot) { it is CallInstance.LoopInstance }
        assertTrue(loopInstances.isNotEmpty()) { "No loop instances found" }

        assertTrue(loopInstances.map {
            CallTraceInfra.ancestorExists(it) { node ->
                node is CallInstance.InvokingInstance.SolidityInvokeInstance
            }
        }.map { it != null }.all { it }) { "Every LoopInstance must be a successor of a SolidityInvokeInstance" }
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

private fun assertRangeMatches(actual: CVLRange?, expectedStart: SourcePosition, expectedEnd: SourcePosition) {
    check(actual is CVLRange.Range) { "expected CVLRange.Range but got $actual" }

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

/**
 * XXX: a kludge. we should override [equals] for [CallInstance.CVLExpInstance] (or use a data class),
 * but this currently causes an existing test to fail on [globalPropertiesChecks].
 */
private fun CallInstance.CVLExpInstance.equals_(other: CallInstance.CVLExpInstance): Boolean =
    this.name == other.name
        && this.range == other.range
        && this.value == other.value

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

private val LocalAssignment.scalarValue: TACValue?
    get() = when (this.state) {
        LocalAssignmentState.ByteMap -> null
        is LocalAssignmentState.CVLString -> (this.state as LocalAssignmentState.CVLString).contents
        is LocalAssignmentState.Contract -> (this.state as LocalAssignmentState.Contract).value
        is LocalAssignmentState.Initialized -> (this.state as LocalAssignmentState.Initialized).value
        LocalAssignmentState.InitializedButMissing -> null
        LocalAssignmentState.Uninitialized -> null
    }
