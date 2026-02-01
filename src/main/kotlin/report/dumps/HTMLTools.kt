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

package report.dumps

import datastructures.stdcollections.*
import tac.IBlockId

enum class Color {
    RED,
    ORANGE,
    PINK,
    DARKPINK,
    DARKBLUE,
    GREEN,
    BLUE,
    DARKGREY,
}

fun colorToRGBString(c : Color) : String {
    return when (c) {
        Color.RED -> "#FF0000"
        Color.ORANGE -> "#FF8C00"
        Color.PINK -> "#FF1493"
        Color.DARKPINK -> "#863F91"
        Color.DARKBLUE -> "#156194"
        Color.GREEN -> "#15942a"
        Color.BLUE -> "#0000FF"
        Color.DARKGREY -> "#737373"
    }
}

fun linkTo(b : IBlockId) : String {
    return "<a href=\"#\" onclick=\"highlightAnchor('$b'); event.preventDefault();\">$b</a>"
}


fun colorText(s: String, color : Color) : HTMLString.ColoredText {
    return HTMLString.ColoredText(s, color)
}

fun String.asRaw() = HTMLString.Raw(this)

sealed class HTMLString {
    data class Raw(val s: String): HTMLString() {
        override fun toString(): String {
            return s
        }
    }

    data class ColoredText(val s: String, val color: Color): HTMLString() {
        override fun toString(): String {
            return """<span style="color:${colorToRGBString(color)};">$s</span>"""
        }
    }

}


fun boldText(s : String) : String {
    return """<span style="font-weight:bold;">$s</span>"""
}

// ============================================================================
// HTML DSL - Type-safe HTML builder
// ============================================================================

/**
 * Marker annotation for HTML DSL scope control.
 * Prevents accidental nesting of builder contexts.
 */
@DslMarker
annotation class HtmlDsl

/**
 * Base interface for all HTML elements that can be rendered to a StringBuilder.
 */
interface HtmlElement {
    fun render(builder: StringBuilder)
}

/**
 * Represents escaped text content within an HTML element.
 * Text is automatically HTML-escaped when rendered.
 */
class TextNode(private val text: String) : HtmlElement {
    override fun render(builder: StringBuilder) {
        builder.append(text.escapeHtmlText())
    }
}

/**
 * Represents raw HTML content that should not be escaped.
 * Use with caution - only for trusted HTML content.
 */
class RawHtml(private val html: String) : HtmlElement {
    override fun render(builder: StringBuilder) {
        builder.append(html)
    }
}

/**
 * Base class for HTML container tags that can have children and attributes.
 */
@HtmlDsl
open class HtmlContainerTag(
    private val tagName: String,
    private val attributes: MutableMap<String, String> = mutableMapOf()
) : HtmlElement {
    private val children = mutableListOf<HtmlElement>()

    /**
     * Adds escaped text content to this tag.
     * Usage: +"some text"
     */
    operator fun String.unaryPlus() {
        children.add(TextNode(this))
    }

    /**
     * Adds raw HTML content (not escaped) to this tag.
     * Use only for trusted HTML content.
     */
    fun raw(html: String) {
        children.add(RawHtml(html))
    }

    /**
     * Adds an HTMLString (from the legacy API) to this tag.
     */
    fun htmlString(hs: HTMLString) {
        children.add(RawHtml(hs.toString()))
    }

    /**
     * Adds a child element to this tag.
     * Used by extension functions to add nested tags.
     */
    fun addChild(element: HtmlElement) {
        children.add(element)
    }

    override fun render(builder: StringBuilder) {
        builder.append("<$tagName")
        attributes.forEachEntry { (key, value) ->
            builder.append(" $key=\"${value.escapeHtmlAttr()}\"")
        }
        builder.append(">")
        children.forEach { it.render(builder) }
        builder.append("</$tagName>")
    }
}

/**
 * Self-closing HTML tags like <br/>, <meta/>, etc.
 */
class SelfClosingTag(
    private val tagName: String,
    private val attributes: Map<String, String> = emptyMap()
) : HtmlElement {
    override fun render(builder: StringBuilder) {
        builder.append("<$tagName")
        attributes.forEachEntry { (key, value) ->
            builder.append(" $key=\"${value.escapeHtmlAttr()}\"")
        }
        builder.append("/>")
    }
}

// Specific tag classes
class HtmlTag : HtmlContainerTag("html")
class HeadTag : HtmlContainerTag("head")
class BodyTag : HtmlContainerTag("body")
class DivTag(attrs: Map<String, String> = emptyMap()) : HtmlContainerTag("div", attrs.toMutableMap())
class SpanTag(attrs: Map<String, String> = emptyMap()) : HtmlContainerTag("span", attrs.toMutableMap())
class ATag(attrs: Map<String, String> = emptyMap()) : HtmlContainerTag("a", attrs.toMutableMap())
class StyleTag : HtmlContainerTag("style")
class ScriptTag(attrs: Map<String, String> = emptyMap()) : HtmlContainerTag("script", attrs.toMutableMap())
class TitleTag : HtmlContainerTag("title")
class ButtonTag(attrs: Map<String, String> = emptyMap()) : HtmlContainerTag("button", attrs.toMutableMap())
class BTag : HtmlContainerTag("b")

/**
 * Builds a complete HTML document with DOCTYPE declaration.
 */
fun html(block: HtmlTag.() -> Unit): String {
    val htmlTag = HtmlTag().apply(block)
    return buildString {
        append("<!DOCTYPE html>\n")
        htmlTag.render(this)
    }
}

// Tag builder extension functions
fun HtmlContainerTag.head(block: HeadTag.() -> Unit) {
    addChild(HeadTag().apply(block))
}

fun HtmlContainerTag.body(block: BodyTag.() -> Unit) {
    addChild(BodyTag().apply(block))
}

fun HtmlContainerTag.div(vararg attrs: Pair<String, String>, block: DivTag.() -> Unit = {}) {
    addChild(DivTag(attrs.toMap()).apply(block))
}

fun HtmlContainerTag.span(vararg attrs: Pair<String, String>, block: SpanTag.() -> Unit = {}) {
    addChild(SpanTag(attrs.toMap()).apply(block))
}

fun HtmlContainerTag.a(vararg attrs: Pair<String, String>, block: ATag.() -> Unit = {}) {
    addChild(ATag(attrs.toMap()).apply(block))
}

fun HtmlContainerTag.style(block: StyleTag.() -> Unit) {
    addChild(StyleTag().apply(block))
}

fun HtmlContainerTag.script(vararg attrs: Pair<String, String>, block: ScriptTag.() -> Unit = {}) {
    addChild(ScriptTag(attrs.toMap()).apply(block))
}

fun HtmlContainerTag.title(block: TitleTag.() -> Unit) {
    addChild(TitleTag().apply(block))
}

fun HtmlContainerTag.meta(vararg attrs: Pair<String, String>) {
    addChild(SelfClosingTag("meta", attrs.toMap()))
}

fun HtmlContainerTag.br() {
    addChild(SelfClosingTag("br"))
}

fun HtmlContainerTag.button(vararg attrs: Pair<String, String>, block: ButtonTag.() -> Unit = {}) {
    addChild(ButtonTag(attrs.toMap()).apply(block))
}

fun HtmlContainerTag.b(block: BTag.() -> Unit) {
    addChild(BTag().apply(block))
}

// HTML escaping - delegates to escapeHTML() which uses Apache Commons Lang3
private fun String.escapeHtmlText(): String = escapeHTML()
private fun String.escapeHtmlAttr(): String = escapeHTML()
