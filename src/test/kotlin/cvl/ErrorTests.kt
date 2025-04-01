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

package cvl

import infra.CVLFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import scene.TEST_SPEC_FILE_NAME
import utils.Range
import utils.SourcePosition
import spec.errors.ErrorExample
import spec.cvlast.typechecker.*
import kotlin.collections.single
import kotlin.io.path.Path
import kotlin.reflect.KClass

class ErrorTests {

    // TESTS FOR ALL CVLError Types ////////////////////////////////////////////////////////////////////////////////////

    /** The conf file used for all error message tests.  The main contract is `/lib/Shared/src/test/resources/GenericTest.sol`. */
    private val testConfPath = Path("lib/Shared/src/test/resources/GenericTest.conf")

    /**
     * Filter for the classes tested by [testCVLErrorExamples]; must be ".*" to pass [allErrorsTested],
     * but when you want to focus on a particular test, you can set it locally.  For example, to only rerun the test
     * "Example 3 for [UndeclaredVariable]", you could change the regex to "3.*Undeclared"
     */
    private val exampleFilter = Regex(".*")

    /** Check each @CVLErrorExample class's examples */
    @TestFactory
    fun testCVLErrorExamples(): List<DynamicTest> {
        return CVLErrorExample.instances.flatMap { (errorClass, examples) ->
            examples.mapIndexed { index, errorType ->
                // Note: it would be nice to use the URI here to navigate to the errorClass, but it doesn't seem to work
                // in IntelliJ
                DynamicTest.dynamicTest("Example $index for [${errorClass.simpleName}]") {
                    testErrorExample(errorClass, errorType.exampleCVLWithRange, errorType.exampleMessage.takeIf { it != "" })
                }
            }
        }.filter { it.displayName.contains(exampleFilter) }
    }

    /** Check that you didn't disable any error example tests */
    @Test fun allErrorsTested() { assertEquals(exampleFilter.pattern, ".*") { "some error tests are filtered out; set [exampleFilter] back to \".*\"." } }

    // Testing infrastructure //////////////////////////////////////////////////////////////////////////////////////////

    /** Ensure that [exampleCVL] produces [errorClass] with [message] (if provided) at the range indicated by "#" marks */
    private fun testErrorExample(errorClass: KClass<*>, exampleCVL: String, message: String? = null) {
        val example = ErrorExample(TEST_SPEC_FILE_NAME, exampleCVL)

        println("Testing:")
        println(example.renderWithRange(example.range).prependIndent("      ┃ "))
        println("Expected [${errorClass.qualifiedName}] with message:")
        println(message?.prependIndent("  > ") ?: "  > [Any message]")

        val results = CVLFlow().getProverQuery(
            confPath = testConfPath,
            primaryContractName = "PrimaryContract",
            cvlText = example.text
        )

        val errors = results.errorOrNull() ?: throw AssertionError("Failed: example did not produce any errors")
        println()
        println("Result:")
        errors.forEach {
            val range = it.location
            println("  > $range [${it::class.simpleName}] ${it.message}")
            if (range is Range.Range) { println(example.renderWithRange(range).prependIndent("      ┃ ")) }
        }

        assert(errors.size == 1) { "Failed: too many errors" }
        val error = errors.single()

        assertEquals(errorClass,    error::class)   { "Failed: incorrect error class" }
        assertEquals(example.range, error.location) { "Failed: incorrect range" }
        message?.run {
            assertEquals(message,   error.message)  { "Failed: incorrect error message" }
        }

        println("Success!")
    }

    // TESTS FOR ErrorExample infrastructure ///////////////////////////////////////////////////////////////////////////

    @Test fun parseErrorSimple()     = testExampleParse("example 1 #range# stuff", 1,10, 1,15)
    @Test fun parseErrorEmpty()      = testExampleParse("##", 1,0, 1,0)
    @Test fun parseErrorEmptyStart() = testExampleParse("##foo", 1,0, 1,0)
    @Test fun parseErrorEmptyEnd()   = testExampleParse("foo##", 1,3, 1,3)

    @Test fun parseErrorEmptyStartMulti() = testExampleParse(
        """
            ##
            foo
        """.trimIndent(), 1,0, 1,0
    )

    @Test fun parseErrorEmptyEndMulti() = testExampleParse(
        """
            foo##
            bar
        """.trimIndent(), 1,3, 1,3
    )

    @Test fun parseErrorSimpleMulti() = testExampleParse(
        """
            foo
            bar#baz#qux
            quux
        """.trimIndent(), 2,3, 2,6
    )

    @Test fun parseErrorCrossLine() = testExampleParse(
        """
            foo #and
            bar
            and# baz
        """.trimIndent(), 1,4, 3,3
    )

    /**
     * Ensure that [ErrorExample] parses [text] correctly.  [ErrorExample.text] should be [text]
     * without the `#` marks, and [ErrorExample.range] should be defined by the remaining arguments.
     */
    private fun testExampleParse(text : String, startLine : Int, startChar : Int, endLine : Int, endChar : Int) {
        println("example text:")
        println(text.prependIndent("  ┃ "))
        println()

        println("testing parsing:")
        val expectedRange = Range.Range("CVL Example", SourcePosition(startLine.toUInt()-1u,startChar.toUInt()), SourcePosition(endLine.toUInt()-1u, endChar.toUInt()))
        val expectedText  = text.replace("#","")

        val actual = ErrorExample("CVL Example", text)
        println(actual.text.prependIndent("  ┃ "))
        println("  range (0-indexed): ${actual.range.start} through ${actual.range.end}")
        assertEquals(expectedText, actual.text)
        assertEquals(expectedRange, actual.range)
        println()

        println("testing printing for original range:")
        val renderedOriginal = actual.renderWithRange(expectedRange)
        println(renderedOriginal.prependIndent("  ┃ "))
        assertEquals(text, renderedOriginal)
        println()

        println("testing printing with range at start:")
        val renderedAtStart = actual.renderWithRange(Range.Range("CVL Example", SourcePosition.zero(), SourcePosition.zero()))
        println(renderedAtStart.prependIndent("  ┃ "))
        assertEquals("##$expectedText", renderedAtStart)
        println()
    }
}
