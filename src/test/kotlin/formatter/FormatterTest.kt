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
    )
    fun compare(fileName: Path) {
        val source = readSource(fileName)
        val expected = readExpected(fileName)

        val ast = parseToAst(source).force()
        val output = FormatterInput(ast).output()
        assertEquals(output.trimEnd(), expected)

        val roundTripAst = parseToAst(output).force()
        val roundTripOutput = FormatterInput(roundTripAst).output()
        assertEquals(roundTripOutput.trimEnd(), output.trimEnd())
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

        @JvmStatic private fun slotPatternDir() = baseDir.resolve("slot_patterns").listDirectoryEntries()
    }
}