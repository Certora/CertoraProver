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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import spec.cvlast.CVLAst
import spec.cvlast.CVLCmd
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.RequireWithoutReason
import utils.CVLSerializerModules
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import kotlin.system.exitProcess

internal const val EXIT_SUCCESS = 0
internal const val EXIT_FAILURE = 1
internal const val EXIT_SYNTAX_FAILURE = 2

/** this is here to silence Detekt. we actually do want to use println in this module. */
@Suppress("ForbiddenMethodCall")
internal fun println(message: Any?) = kotlin.io.println(message)

internal fun errprintln(message: Any?) = System.err.println(message)

internal fun exitError(message: () -> String): Nothing {
    errprintln(message())

    exitProcess(EXIT_FAILURE)
}

internal fun exitUsage(): Nothing {
    println(usage())

    exitProcess(EXIT_FAILURE)
}

internal fun Iterator<*>.requireExhausted() {
    if (this.hasNext()) {
        exitUsage()
    }
}

/** reads input from stdin until EOF and returns the input unchanged */
internal fun readUntilEndOfInput(): String {
    val stdinLines = generateSequence(::readLine)

    /** the separator restores the EOL that gets consumed by [readLine] */
    return stdinLines.joinToString(separator = System.lineSeparator())
}

internal val lspJsonConfig = Json {
    serializersModule = CVLSerializerModules.modules
    encodeDefaults = true
}

@Serializable
internal data class AstAndDiagnostics(val ast: CVLAst?, val diagnostics: List<LSPDiagnostic>)

@Serializable
internal data class LSPDiagnostic(val error: CVLError, val severity: Severity)

@Serializable
internal enum class Severity { WARNING, ERROR }

internal fun collectWarningsForLSP(ast: CVLAst): CollectingResult<CVLAst, CVLError> {
    val cmdChecker = object : CVLCmdTransformer<CVLError>(CVLExpTransformer.copyTransformer()) {
        override fun assumeCmd(cmd: CVLCmd.Simple.AssumeCmd.Assume): CollectingResult<CVLCmd, CVLError> {
            if (cmd.description == null) {
                return RequireWithoutReason(cmd.range, cmd.exp).asError()
            }
            return super.assumeCmd(cmd)
        }
    }
    val astChecker = object : CVLAstTransformer<CVLError>(cmdChecker) {}
    return astChecker.ast(ast)
}