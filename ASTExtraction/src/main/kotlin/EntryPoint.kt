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

import com.certora.certoraprover.cvl.Ast
import com.certora.certoraprover.cvl.formatter.FormatterInput
import com.certora.certoraprover.cvl.formatter.util.parseToAst
import kotlinx.serialization.encodeToString
import spec.CVLInput
import spec.CVLSource
import spec.CVLSource.File
import spec.CVLSource.Raw
import spec.DummyTypeResolver
import spec.cvlast.CVLAst
import spec.cvlast.SolidityContract
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult
import utils.nextOrNull
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * This entry point is used by the LSP server.
 *
 * Input is in the form of a single file. If syntax checking passes, a [CVLAst] and potential warnings are emitted to stdout.
 * If it fails, errors are emitted to stdout, while the ast will be null. Serialization is done via JSON.
 * Currently only basic syntax checking is done. For example, types are not validated and imports are not considered.
 *
 * This entry point is currently internal-only.
 */
fun main(args: Array<String>) {
    val iter = args.iterator()

    val mode = iter
        .nextOrNull()
        ?.let { Arg.parse(it) as? Arg.Mode }
        ?: exitUsage()


    val sourceArg = iter
        .nextOrNull()
        ?.let { Arg.parse(it) as? Arg.InputSource }
        ?: exitUsage()

    val source = when (sourceArg) {
        Arg.InputSource.File -> {
            val next = iter.nextOrNull() ?: exitUsage()
            iter.requireExhausted()
            val path = Path.of(next).takeIf(Path::isRegularFile) ?: exitError { "invalid file: $next" }
            Source.File(path)
        }

        Arg.InputSource.Raw -> {
            iter.requireExhausted()
            val stdin = readUntilEndOfInput()
            Source.String(stdin)
        }
    }

    when (mode) {
        Arg.Mode.SyntaxCheck -> {
            val cvlSource = when (source) {
                is Source.File -> {
                    val absolutePath = source.file.toAbsolutePath().toString()
                    File(filepath = absolutePath, origpath = absolutePath, isImported = false)
                }

                is Source.String -> Raw(name = "dummy file", rawTxt = source.str, isImported = false)
            }
            syntaxCheck(cvlSource)
        }

        Arg.Mode.Format -> {
            when (source) {
                is Source.File -> format(source.file.readText())
                is Source.String -> format(source.str)
            }
        }
    }
}

private fun syntaxCheck(cvlSource: CVLSource): Nothing {
    val primaryContract = SolidityContract(name = "")
    val typeResolver = DummyTypeResolver(primaryContract)

    val astOrErrors = CVLInput.Plain(cvlSource).getRawCVLAst(typeResolver)

    when (astOrErrors) {
        is CollectingResult.Result -> {
            val originalAst: CVLAst = astOrErrors.result
            val warnings: List<LSPDiagnostic> = collectWarningsForLSP(originalAst).errorOrNull().orEmpty().map { LSPDiagnostic(it, Severity.WARNING) }
            val serialized = lspJsonConfig.encodeToString(AstAndDiagnostics(originalAst, warnings))
            println(serialized)
            exitProcess(EXIT_SUCCESS)
        }

        is CollectingResult.Error -> {
            val errors: List<LSPDiagnostic> = astOrErrors.messages.map { LSPDiagnostic(it, Severity.ERROR) }
            val serialized = lspJsonConfig.encodeToString(AstAndDiagnostics(null, errors))
            println(serialized)
            exitProcess(EXIT_SYNTAX_FAILURE)
        }
    }
}

private fun format(source: String): Nothing {
    val ast = when (val cr = parseToAst(source)) {
        is CollectingResult.Result<Ast> -> cr.result
        is CollectingResult.Error<CVLError> -> {
            val lineBreak = System.lineSeparator()
            val messages = cr.messages.joinToString(separator = lineBreak, transform = CVLError::message)

            exitError {
                if (cr.messages.isEmpty()) {
                    "file could not be parsed."
                } else {
                    "file could not be parsed. got errors:${lineBreak}${messages}"
                }
            }
        }
    }

    // XXX: try-catch isn't proper error handling.
    try {
        val output = FormatterInput(ast).output()
        print(output)
        exitProcess(EXIT_SUCCESS)
    } catch (e: IllegalStateException) {
        // here to catch things from `ensure`
        exitError { "error while formatting valid AST. got: $e" }
    }
}

private sealed interface Source {
    class File(val file: Path) : Source
    class String(val str: kotlin.String) : Source
}
