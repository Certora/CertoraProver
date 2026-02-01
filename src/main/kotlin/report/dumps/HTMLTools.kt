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
    return "<a href=\"#block${b}\" onclick=\"highlightAnchor('$b')\">$b</a>"
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
