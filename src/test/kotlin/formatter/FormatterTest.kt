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

package formatter

import com.certora.certoraprover.cvl.formatter.FormatterInput
import com.certora.certoraprover.cvl.formatter.util.parseToAst
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class FormatterTest {
    @ParameterizedTest
    @CsvSource(
        "rule1",
        "rule_with_parameters",
        "ghosts",
        "mixed",
        "comments1",
        "multi_assert",
        "upgrade_admin",
        "custom_linebreaks_in_block",
        "respect_parenthesis",
        "linebreaks_in_methods_block",
        "comment_at_start_of_file1",
        "comment_at_start_of_file2",
        "single_filter",
        "two_filters",
        "long_filter",
        "many_if_predicates",
    )
    fun compare(fileName: Path) {
        val source = readSource(fileName)
        val expected = readExpected(fileName)

        val ast = parseToAst(source).force()
        val output = FormatterInput(ast).output()
        assertEquals(output, expected.padWithEmptyLine())

        val roundTripAst = parseToAst(output).force()
        val roundTripOutput = FormatterInput(roundTripAst).output()
        assertEquals(roundTripOutput, output)
    }

    @ParameterizedTest
    @MethodSource("slotPatternDir")
    fun slotPattern(testPath: Path) {
        val src = testPath.readText()

        val ast = parseToAst(src).force()
        val output = FormatterInput(ast).output().trimEnd()

        // we're only testing the slot pattern tokenizing here.
        assertEquals(src, output)
    }

    companion object {
        private val baseDir: Path =  Paths.get("src/test/kotlin/formatter")

        private fun readSource(fileName: Path): String = baseDir.resolve("source").resolve(fileName).readText()
        private fun readExpected(fileName: Path): String = baseDir.resolve("expected").resolve(fileName).readText()

        /** pad [this] to end with an empty newline, but only if it does not already */
        private fun String.padWithEmptyLine(): String {
            val lb = System.lineSeparator()
            return if (this.endsWith(lb)) { this } else { this + lb }
        }

        @JvmStatic private fun slotPatternDir() = baseDir.resolve("slot_patterns").listDirectoryEntries()
    }
}
