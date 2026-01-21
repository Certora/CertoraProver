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

package report.calltrace.generator

import config.Config
import log.*
import report.calltrace.CallInstance
import report.calltrace.formatter.CallTraceValueFormatter
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import utils.Range
import spec.rules.IRule
import tac.NBId
import utils.*
import vc.data.*
import java.io.File

val cvlrLogger = Logger(LoggerTypes.CVLR)

internal open class CvlrCallTraceGenerator(
    rule: IRule,
    cexId: Int,
    model: CounterexampleModel,
    program: CoreTACProgram,
    formatter: CallTraceValueFormatter,
    scene: ISceneIdentifiers,
    ruleCallString: String,
) : CallTraceGenerator(rule, cexId, model, program, formatter, scene, ruleCallString) {
    override fun handleCmd(cmd: TACCmd.Simple, cmdIdx: Int, currBlock: NBId, blockIdx: Int): HandleCmdResult {
        return when (cmd) {
            is TACCmd.Simple.AnnotationCmd -> {
                val (meta, value) = cmd.annot
                when (meta) {
                    TACMeta.SNIPPET -> {
                        when (val snippetCmd = value as SnippetCmd) {
                            is SnippetCmd.CvlrSnippetCmd -> {
                                when (snippetCmd) {
                                    is SnippetCmd.CvlrSnippetCmd.CexPrintU64AsFixedOrDecimal -> handleCvlrCexPrintU64AsFixedOrDecimal(
                                        snippetCmd,
                                        cmd
                                    )
                                    is SnippetCmd.CvlrSnippetCmd.CexPrintValues -> handleCvlrCexPrintValues(snippetCmd, cmd)
                                    is SnippetCmd.CvlrSnippetCmd.CexPrint128BitsValue -> handleCvlrCexPrint128BitsValue(
                                        snippetCmd,
                                        cmd
                                    )

                                    is SnippetCmd.CvlrSnippetCmd.CexPrintTag -> handleCvlrCexPrintTag(snippetCmd, cmd)
                                    is SnippetCmd.CvlrSnippetCmd.CexAttachLocation -> handleCvlrCexAttachLocation(
                                        snippetCmd
                                    )

                                    is SnippetCmd.CvlrSnippetCmd.CexPrintLocation -> handleCvlrCexPrintLocation(
                                        snippetCmd,
                                    )

                                    is SnippetCmd.CvlrSnippetCmd.ScopeStart -> handleCvlrScopeStart(snippetCmd, cmd)
                                    is SnippetCmd.CvlrSnippetCmd.ScopeEnd -> handleCvlrScopeEnd(snippetCmd)
                                }
                            }

                            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                        }
                    }

                    else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                }
            }

            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
        }
    }

    private fun handleCvlrScopeStart(snippetCmd: SnippetCmd.CvlrSnippetCmd.ScopeStart,
                                     stmt: TACCmd.Simple.AnnotationCmd,
    ): HandleCmdResult {
        val range = resolveAttachedLocation(stmt)
        val newInstance = CallInstance.InvokingInstance.CVLRScope(
            name = snippetCmd.scopeName,
            range = range
        )
        callTracePush(newInstance)
        return HandleCmdResult.Continue
    }

    private fun handleCvlrScopeEnd(snippetCmd: SnippetCmd.CvlrSnippetCmd.ScopeEnd): HandleCmdResult {
        return ensureStackState(
            requirement = { it is CallInstance.InvokingInstance.CVLRScope && it.name == snippetCmd.scopeName },
            eventDescription = "end of cvlr scope"
        )
    }

    open fun resolveAttachedLocation(stmt: TACCmd.Simple.AnnotationCmd): Range.Range? {
        return null
    }

    private fun handleCvlrCexPrintTag(
        snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrintTag,
        stmt: TACCmd.Simple.AnnotationCmd,
    ): HandleCmdResult {
        val range = resolveAttachedLocation(stmt)
        val instance = CallInstance.CvlrCexPrintTag(
            name = snippetCmd.displayMessage,
            range = range
        )
        callTraceAppend(instance)
        return HandleCmdResult.Continue
    }

    private fun handleCvlrCexPrintValues(
        snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrintValues,
        stmt: TACCmd.Simple.AnnotationCmd,
    ): HandleCmdResult {
        val range = resolveAttachedLocation(stmt)
        callTraceAppend(CallInstance.CvlrCexPrintValues(snippetCmd.toSarif(model), range))
        return HandleCmdResult.Continue
    }

    private fun handleCvlrCexPrintU64AsFixedOrDecimal(
        snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrintU64AsFixedOrDecimal,
        stmt: TACCmd.Simple.AnnotationCmd,
    ): HandleCmdResult {
        val range = resolveAttachedLocation(stmt)
        val formatted = snippetCmd.tryToSarif(model)
        if (formatted != null) {
            callTraceAppend(CallInstance.CvlrCexPrintValues(formatted, range))
        } else {
            cvlrLogger.warn { "cannot infer value of ${snippetCmd.unscaledVal} or ${snippetCmd.scale} to print decimal number" }
        }
        return HandleCmdResult.Continue
    }


    private fun handleCvlrCexPrint128BitsValue(
        snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrint128BitsValue,
        stmt: TACCmd.Simple.AnnotationCmd,
    ): HandleCmdResult {
        val range = resolveAttachedLocation(stmt)
        val formatted = snippetCmd.tryToSarif(model)
        if (formatted != null) {
            callTraceAppend(CallInstance.CvlrCexPrintValues(formatted, range))
        } else {
            cvlrLogger.warn { "cannot infer value of ${snippetCmd.high} or ${snippetCmd.low} to print 128-bit number" }
        }
        return HandleCmdResult.Continue
    }


    private fun handleCvlrCexPrintLocation(snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrintLocation): HandleCmdResult {
        val range = filepathAndLineNumberToRange(snippetCmd.filepath, snippetCmd.lineNumber)
        val tag = "${snippetCmd.filepath}:${snippetCmd.lineNumber}"
        callTraceAppend(CallInstance.CvlrCexPrintTag(tag, range))
        return HandleCmdResult.Continue
    }

    open fun handleCvlrCexAttachLocation(snippetCmd: SnippetCmd.CvlrSnippetCmd.CexAttachLocation): HandleCmdResult {
        return HandleCmdResult.Continue
    }

    /** Converts a filepath and a line number to a range. If the file is not in the sources dir, returns `null`. */
    internal fun filepathAndLineNumberToRange(filepath: String, lineNumber: UInt): Range.Range? {
        val fileInSourcesDir = File(Config.prependSourcesDir(filepath))
        return if (fileInSourcesDir.exists()) {
            val rangeLineNumber = lineNumber - 1U
            // We do not have column information.
            val rangeColNumber = 0U
            val sourcePositionStart = SourcePosition(rangeLineNumber, rangeColNumber)
            // Since we do not have end range information, we just assume it is the next line.
            val sourcePositionEnd = SourcePosition(rangeLineNumber + 1U, rangeColNumber)
            Range.Range(filepath, sourcePositionStart, sourcePositionEnd)
        } else {
            cvlrLogger.warn {
                "file '$fileInSourcesDir' does not exist: jump to source information will not be available"
            }
            null
        }
    }
}
