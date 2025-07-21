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

@file:Suppress("MissingPackageDeclaration") // `main` is in default package

import datastructures.stdcollections.flatListOf

// a non-toy argument parser would be better here.
// feel free to add one as a dependency and rewrite this.
//
// assumes all short/long name are unique.
internal sealed interface Arg {
    val short: String?
    val long: String

    enum class Mode(override val long: String) : Arg {
        SyntaxCheck(long = "syntax-check"),
        Format(long = "format"),
        ;

        override val short: String? get() = null
    }

    enum class InputSource(override val short: String, override val long: String) : Arg {
        File(short = "-f", long = "--file"),
        Raw(short = "-r", long = "--raw"),
        ;
    }

    companion object {
        private val sources: List<Arg> = flatListOf(
            Mode.entries,
            InputSource.entries,
        )

        fun parse(arg: String): Arg? = sources.find { it.short == arg || it.long == arg }
    }
}

internal fun usage(): String = buildString {
    val executable = "ASTExtraction.jar"

    val requiredSource = Arg
        .InputSource
        .entries
        .joinToString(separator = "|", prefix = "(", postfix = ")") {
            when (it) {
                Arg.InputSource.File -> it.long + " FILE"
                Arg.InputSource.Raw -> it.long
            }
        }

    appendLine("Usage:")
    for (mode in Arg.Mode.entries) {
        appendLine("  $executable ${mode.long} $requiredSource")
    }
}