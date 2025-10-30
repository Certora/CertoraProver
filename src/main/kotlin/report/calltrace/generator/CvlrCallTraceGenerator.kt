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
import report.calltrace.formatter.CallTraceValue
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.sarif.FmtArg
import sbf.support.SolanaCalltraceUtil
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import utils.Range
import spec.cvlast.CVLType
import spec.rules.IRule
import tac.NBId
import tac.Tag
import utils.*
import utils.ModZm.Companion.from2s
import vc.data.*
import vc.data.state.TACValue
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

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

    /**
     * [SnippetCmd.CvlrSnippetCmd.CexAttachLocation] can set the range for the next element in the calltrace that will
     * be processed to have reliable range information for Solana/Soroban executables. Locations are pushed onto the stack when
     * [SnippetCmd.CvlrSnippetCmd.CexAttachLocation] is found, and consumed by the following calltrace entry that
     * needs range information.
     */
    private val rangesFromAttachLocation: MutableList<Range.Range> = mutableListOf()

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
                                        cmd,
                                        snippetCmd.asFixed
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
                                     stmt: TACCmd.Simple.AnnotationCmd? = null
    ): HandleCmdResult {
        val range = stmt?.let { consumeAttachedRangeOrResolve(it) }
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
    /**
     * If [rangesFromAttachLocation] has at least one entry, pops the range and returns it.
     * If [rangesFromAttachLocation] is empty, reads the range from the debug information from the executable.
     */
    fun consumeAttachedRangeOrResolve(stmt: TACCmd.Simple.AnnotationCmd): Range.Range? {
        return if (rangesFromAttachLocation.isNotEmpty()) {
            rangesFromAttachLocation.removeLast()
        } else {
            SolanaCalltraceUtil.sbfAddressToRangeWithHeuristic(stmt)
        }
    }


    private fun handleCvlrCexPrintTag(
        snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrintTag,
        stmt: TACCmd.Simple.AnnotationCmd? = null
    ): HandleCmdResult {
        val range = stmt?.let { consumeAttachedRangeOrResolve(it) }
        val instance = CallInstance.CvlrCexPrintTag(
            name = snippetCmd.displayMessage,
            range = range
        )
        callTraceAppend(instance)
        return HandleCmdResult.Continue
    }

    private fun handleCvlrCexPrintValues(
        snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrintValues,
        stmt: TACCmd.Simple.AnnotationCmd? = null
    ): HandleCmdResult {
        val range = stmt?.let { consumeAttachedRangeOrResolve(it) }
        val formattedList = snippetCmd.symbols.map { sym ->
            CallTraceValue.cvlCtfValueOrUnknown(
                model.valueAsTACValue(sym),
                CVLType.PureCVLType.Primitive.UIntK(256)
            ).toSarif(formatter, tooltip = "value")
        }
        val sarif = sarifFormatter.fmt(
            "${snippetCmd.displayMessage}: " + List(formattedList.size) { _ -> "{}" }.joinToString(", "),
            *formattedList.map { FmtArg(it) }.toTypedArray()
        )
        callTraceAppend(CallInstance.CvlrCexPrintValues(sarif, range))
        return HandleCmdResult.Continue
    }

    private fun unscaledValAndScaleToBigDecimal(unscaledVal: BigInteger, scale: Int, asFixed: Boolean): BigDecimal {
        val divisor = if (asFixed) {
            BigDecimal(BigInteger.ONE.shiftLeft(scale)) // 2^fractionalBits
        } else {
            BigDecimal(BigInteger.TEN.pow(scale)) // 10^decimals
        }
        return BigDecimal(unscaledVal)
            .divide(divisor, scale, RoundingMode.FLOOR) // BigDecimal interprets the scale as base 10, while our scale is in base 2
            .stripTrailingZeros()
    }

    private fun handleCvlrCexPrintU64AsFixedOrDecimal(
        snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrintU64AsFixedOrDecimal,
        stmt: TACCmd.Simple.AnnotationCmd,
        asFixed: Boolean
    ): HandleCmdResult {
        val range = consumeAttachedRangeOrResolve(stmt)
        val numTACValue = model.valueAsTACValue(snippetCmd.unscaledVal)
        val unscaledVal: BigInteger? = numTACValue?.asBigIntOrNull()
        val scale: Int? = model.valueAsTACValue(snippetCmd.scale)?.asBigIntOrNull()?.toIntOrNull()
        if (unscaledVal != null && scale != null) {
            val decimalValue = unscaledValAndScaleToBigDecimal(unscaledVal, scale, asFixed)
            val formatted = sarifFormatter.fmt(
                "${snippetCmd.displayMessage}: $decimalValue ({})", FmtArg.CtfValue.buildOrUnknown(
                    tv = numTACValue,
                    type = CVLType.PureCVLType.Primitive.UIntK(256),
                    tooltip = "unscaled value of decimal number"
                )
            )
            callTraceAppend(CallInstance.CvlrCexPrintValues(formatted, range))
        } else {
            cvlrLogger.warn { "cannot infer value of ${snippetCmd.unscaledVal} or ${snippetCmd.scale} to print decimal number. Got: $unscaledVal and $scale" }
        }
        return HandleCmdResult.Continue
    }

    private fun handleCvlrCexPrint128BitsValue(
        snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrint128BitsValue,
        stmt: TACCmd.Simple.AnnotationCmd? = null
    ): HandleCmdResult {
        val range = stmt?.let { consumeAttachedRangeOrResolve(it) }
        val low = get64BitsNumber(snippetCmd.low)
        val high = get64BitsNumber(snippetCmd.high)
        if (low != null && high != null) {
            // Combines two 64-bit values (`high` and `low`) into a single 128-bit value. If the `signed` flag is true,
            // the result is interpreted as a signed two's complement number.
            val bigInt = ((high shl 64) or low).letIf(snippetCmd.signed) {
                it.from2s(Tag.Bit128)
            }
            val sarif = sarifFormatter.fmt(
                "${snippetCmd.displayMessage}: {}", FmtArg.CtfValue.buildOrUnknown(
                    tv = TACValue.valueOf(bigInt),
                    type = CVLType.PureCVLType.Primitive.UIntK(256),
                    tooltip = "value of a 128-bit number"
                )
            )
            callTraceAppend(CallInstance.CvlrCexPrintValues(sarif, range))
        } else {
            cvlrLogger.warn { "cannot infer value of ${snippetCmd.high} or ${snippetCmd.low} to print 128-bit number. Got: $high and $low" }
        }
        return HandleCmdResult.Continue
    }

    /**
     * Returns the [BigInteger] associated with [v] in the model. Ensures that only the lowest 64 bits of the number are
     * possibly non-zero, while the highest 192 bits are zeroed. This is useful because negative numbers are sign-extended:
     * for example, -1 is represented as 256 bits with all 1s, but we only care about the lowest 64 bits of the number,
     * so the highest 192 bits are cleared.
     */
    private fun get64BitsNumber(v: TACSymbol.Var): BigInteger? =
        model.valueAsTACValue(v)?.asBigIntOrNull()?.let {
            it and Tag.Bit64.maxUnsigned
        }

    private fun handleCvlrCexPrintLocation(snippetCmd: SnippetCmd.CvlrSnippetCmd.CexPrintLocation): HandleCmdResult {
        val range = filepathAndLineNumberToRange(snippetCmd.filepath, snippetCmd.lineNumber)
        val tag = "${snippetCmd.filepath}:${snippetCmd.lineNumber}"
        callTraceAppend(CallInstance.CvlrCexPrintTag(tag, range))
        return HandleCmdResult.Continue
    }

    private fun handleCvlrCexAttachLocation(snippetCmd: SnippetCmd.CvlrSnippetCmd.CexAttachLocation): HandleCmdResult {
        filepathAndLineNumberToRange(snippetCmd.filepath, snippetCmd.lineNumber)?.let {
            rangesFromAttachLocation.add(it)
            if (rangesFromAttachLocation.size > 1) {
                cvlrLogger.warn { "CVT_attach_location has been called two or more times in a row without a call trace entry to consume the locations" }
            }
        }
        return HandleCmdResult.Continue
    }

    /** Converts a filepath and a line number to a range. If the file is not in the sources dir, returns `null`. */
    private fun filepathAndLineNumberToRange(filepath: String, lineNumber: UInt): Range.Range? {
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
