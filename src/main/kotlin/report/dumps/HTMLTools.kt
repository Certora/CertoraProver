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

enum class Color(val rgbString: String) {
    RED("#FF0000"),
    ORANGE("#FF8C00"),
    PINK("#FF1493"),
    DARKPINK("#863F91"),
    DARKBLUE("#156194"),
    GREEN("#15942a"),
    BLUE("#0000FF"),
    DARKGREY("#737373"),
    ;

    override fun toString(): String {
        return rgbString
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
            return """<span style="color:$color;">$s</span>"""
        }
    }

}


fun boldText(s : String) : String {
    return """<span style="font-weight:bold;">$s</span>"""
}
