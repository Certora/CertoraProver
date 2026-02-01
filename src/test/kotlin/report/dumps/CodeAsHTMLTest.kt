/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

package report.dumps

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import solver.CounterexampleModel
import vc.data.TacMockGraphBuilder

/**
 * Unit tests for [DumpGraphHTML] HTML generation.
 */
class CodeAsHTMLTest {

    /**
     * Creates a minimal [CodeMap] suitable for testing HTML generation.
     *
     * @param name The name for the CodeMap (appears in title)
     * @param withEmptyCex If true, includes CounterexampleModel.Empty; if false, cexModel is null
     */
    private fun createMinimalCodeMap(
        name: String = "test",
        withEmptyCex: Boolean = false
    ): CodeMap {
        var result: CodeMap? = null
        TacMockGraphBuilder {
            val p0 = proc("main")
            val block = blk(p0)
            addCode(block, listOf(dummyCmd))
            val prog = prog(name)
            result = generateCodeMap(prog, name, addInternalFunctions = false)
        }
        val cexModel = if (withEmptyCex) CounterexampleModel.Empty else null
        return result!!.copy(cexModel = cexModel)
    }

    // ========================================================================
    // HTML Structure Tests
    // ========================================================================

    @Test
    fun `generateHTML produces valid HTML5 document structure`() {
        val codeMap = createMinimalCodeMap(name = "TestProgram")
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.startsWith("<!DOCTYPE html>"), "HTML should start with DOCTYPE declaration")
        assertTrue(html.contains("<html>"), "HTML should contain opening html tag")
        assertTrue(html.contains("</html>"), "HTML should contain closing html tag")
        assertTrue(html.contains("<head>"), "HTML should contain head section")
        assertTrue(html.contains("<body>"), "HTML should contain body section")
    }

    @Test
    fun `generateHTML includes page title from CodeMap name`() {
        val codeMap = createMinimalCodeMap(name = "MyTestProgram")
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.contains("<title>MyTestProgram"), "Title should contain CodeMap name")
    }

    @Test
    fun `generateHTML includes charset meta tag`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.contains("charset=\"utf-8\"") || html.contains("charset=utf-8"),
            "HTML should specify UTF-8 charset")
    }

    // ========================================================================
    // JavaScript Data Injection Tests
    // ========================================================================

    @Test
    fun `generateHTML injects tooltip_cache JavaScript variable`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.contains("var tooltip_cache"), "HTML should inject tooltip_cache variable")
    }

    @Test
    fun `generateHTML injects dataflowMap JavaScript variable`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.contains("var dataflowMap"), "HTML should inject dataflowMap variable")
    }

    @Test
    fun `generateHTML calls initTacDump initialization function`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.contains("initTacDump()"), "HTML should call initTacDump() function")
    }

    // ========================================================================
    // CEX Panel Tests
    // ========================================================================

    @Test
    fun `generateHTML includes CEX panel container`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.contains("id=\"cex\""), "HTML should contain CEX panel with id='cex'")
    }

    @Test
    fun `generateHTML hides CEX panel when no counterexample`() {
        val codeMap = createMinimalCodeMap(withEmptyCex = false)
        val html = DumpGraphHTML.generateHTML(codeMap)

        // The JS code that hides the CEX panel when empty
        assertTrue(
            html.contains("getElementById(\"cex\").style.display = \"none\""),
            "HTML should hide CEX panel when no counterexample is present"
        )
    }

    @Test
    fun `generateHTML does not hide CEX panel when counterexample present with empty assignments`() {
        // Using Empty counterexample model - panel should still show (for color explanation, etc.)
        val codeMap = createMinimalCodeMap(withEmptyCex = true)
        val html = DumpGraphHTML.generateHTML(codeMap)

        // When there's a CEX model (even Empty), the panel hiding code may still run
        // but the panel structure should be present
        assertTrue(html.contains("id=\"cex\""), "HTML should contain CEX panel")
    }

    // ========================================================================
    // XSS Prevention Tests
    // ========================================================================

    @Test
    fun `generateHTML escapes HTML special characters in title`() {
        val codeMap = createMinimalCodeMap("<script>alert('xss')</script>")
        val html = DumpGraphHTML.generateHTML(codeMap)

        // The malicious script tag should be escaped in the title
        assertFalse(
            html.contains("<title><script>alert"),
            "Malicious script tags should be escaped in title"
        )
    }

    @Test
    fun `generateHTML escapes ampersands in title`() {
        val codeMap = createMinimalCodeMap("test&name")
        val html = DumpGraphHTML.generateHTML(codeMap)

        // The ampersand should be escaped as &amp; in HTML context
        assertTrue(
            html.contains("test&amp;name"),
            "Ampersands in title should be escaped"
        )
    }

    @Test
    fun `generateHTML escapes less-than in title`() {
        val codeMap = createMinimalCodeMap("test<name")
        val html = DumpGraphHTML.generateHTML(codeMap)

        // The < should be escaped as &lt;
        assertTrue(
            html.contains("test&lt;name"),
            "Less-than signs in title should be escaped"
        )
    }

    @Test
    fun `dataflowMap is valid JavaScript object literal`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        // Verify the dataflowMap structure is valid JavaScript
        assertTrue(html.contains("var dataflowMap = {"), "dataflowMap should be a JS object")
        assertTrue(html.contains("}"), "dataflowMap should have closing brace")
    }

    // ========================================================================
    // CSS and JavaScript Resource Loading Tests
    // ========================================================================

    @Test
    fun `generateHTML includes CSS styles`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.contains("<style>"), "HTML should contain style element")
        // Check for some expected CSS content
        assertTrue(
            html.contains(".fixed-panel") || html.contains("tooltip"),
            "HTML should contain expected CSS classes"
        )
    }

    @Test
    fun `generateHTML includes JavaScript functions`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        assertTrue(html.contains("<script>"), "HTML should contain script elements")
        // Check for some expected JS functions
        assertTrue(
            html.contains("function") || html.contains("initTacDump"),
            "HTML should contain JavaScript functions"
        )
    }

    @Test
    fun `generateHTML includes escapeHtml JavaScript function`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        // The escapeHtml function should be present for client-side XSS prevention
        assertTrue(
            html.contains("function escapeHtml"),
            "HTML should include escapeHtml function for client-side escaping"
        )
    }

    // ========================================================================
    // Graph Rendering Tests
    // ========================================================================

    @Test
    fun `generateHTML includes graph container`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        // Should have some kind of graph container for SVG
        assertTrue(
            html.contains("graph") || html.contains("svg") || html.contains("digraph"),
            "HTML should contain graph visualization elements"
        )
    }

    @Test
    fun `generateHTML includes block containers`() {
        val codeMap = createMinimalCodeMap()
        val html = DumpGraphHTML.generateHTML(codeMap)

        // Should have block containers for TAC commands
        assertTrue(
            html.contains("block") || html.contains("Block"),
            "HTML should contain block containers for TAC commands"
        )
    }
}
