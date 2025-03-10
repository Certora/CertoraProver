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
import dwarf.InlinedFramesInfo
import report.calltrace.*
import report.calltrace.formatter.CallTraceValue
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.sarif.FmtArg
import sbf.sbfLogger
import sbf.tac.*
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.cvlast.CVLRange
import spec.cvlast.CVLType
import tac.NBId
import utils.SourcePosition
import utils.removeLast
import utils.toIntOrNull
import vc.data.CoreTACProgram
import vc.data.SnippetCmd
import vc.data.TACCmd
import vc.data.TACMeta
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * This class manages the generation of the call trace when analyzing a Solana project.
 * It specifically handles Solana-related commands, delegating the ones it cannot process to its superclass.
 */
internal class SolanaCallTraceGenerator(
    ruleName: String,
    model: CounterexampleModel,
    program: CoreTACProgram,
    formatter: CallTraceValueFormatter,
    scene: ISceneIdentifiers,
    ruleCallString: String,
) : CallTraceGenerator(ruleName, model, program, formatter, scene, ruleCallString) {

    /**
     * [SnippetCmd.SolanaSnippetCmd.CexAttachLocation] can set the range for the next element in the calltrace that will
     * be processed to have reliable range information for Solana executables. Locations are pushed onto the stack when
     * [SnippetCmd.SolanaSnippetCmd.CexAttachLocation] is found, and consumed by the following calltrace entry that
     * needs range information.
     */
    private val rangesFromAttachLocation: MutableList<CVLRange.Range> = mutableListOf()

    override fun handleCmd(cmd: TACCmd.Simple, cmdIdx: Int, currBlock: NBId, blockIdx: Int): HandleCmdResult {
        return when (cmd) {
            is TACCmd.Simple.AnnotationCmd -> {
                val (meta, value) = cmd.annot
                when (meta) {
                    TACMeta.SNIPPET -> {
                        when (val snippetCmd = value as SnippetCmd) {
                            is SnippetCmd.SolanaSnippetCmd -> {
                                when (snippetCmd) {
                                    is SnippetCmd.SolanaSnippetCmd.CexPrintValues -> handleSolanaCexPrintValues(snippetCmd, cmd)
                                    is SnippetCmd.SolanaSnippetCmd.CexPrintU64AsFixed -> handleSolanaCexPrintU64AsFixed(snippetCmd, cmd)
                                    is SnippetCmd.SolanaSnippetCmd.CexPrintLocation -> handleSolanaCexPrintLocation(snippetCmd)
                                    is SnippetCmd.SolanaSnippetCmd.CexAttachLocation -> handleSolanaCexAttachLocation(snippetCmd)
                                    is SnippetCmd.SolanaSnippetCmd.CexPrintTag -> handleSolanaCexPrintTag(snippetCmd, cmd)
                                    is SnippetCmd.SolanaSnippetCmd.ExternalCall -> handleSolanaExternalCall(snippetCmd, cmd)
                                    is SnippetCmd.SolanaSnippetCmd.Assert -> handleSolanaUserAssert(snippetCmd, cmd) }
                            }
                            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                        }
                    }
                    SBF_INLINED_FUNCTION_START -> handleSolanaFunctionStart(value as SbfInlinedFuncStartAnnotation)
                    SBF_INLINED_FUNCTION_END -> handleSolanaFunctionEnd(value as SbfInlinedFuncEndAnnotation)
                    else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                }
            }
            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
        }
    }

    private fun handleSolanaCexPrintValues(
        snippetCmd: SnippetCmd.SolanaSnippetCmd.CexPrintValues,
        stmt: TACCmd.Simple.AnnotationCmd
    ): HandleCmdResult {
        val range = consumeAttachedRangeOrResolve(stmt)
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
        callTraceAppend(CallInstance.SolanaCexPrintValues(sarif, range))
        return HandleCmdResult.Continue
    }

    private fun handleSolanaCexPrintU64AsFixed(
        snippetCmd: SnippetCmd.SolanaSnippetCmd.CexPrintU64AsFixed,
        stmt: TACCmd.Simple.AnnotationCmd
    ): HandleCmdResult {
        val range = consumeAttachedRangeOrResolve(stmt)
        val numTACValue = model.valueAsTACValue(snippetCmd.unscaledVal)
        val unscaledVal: BigInteger? = numTACValue?.asBigIntOrNull()
        val scale: Int? = model.valueAsTACValue(snippetCmd.scale)?.asBigIntOrNull()?.toIntOrNull()
        if (unscaledVal != null && scale != null) {
            val decimalValue = unscaledValAndScaleToBigDecimal(unscaledVal, scale)
            val formatted = sarifFormatter.fmt(
                "${snippetCmd.displayMessage}: $decimalValue ({})", FmtArg.CtfValue.buildOrUnknown(
                    tv = numTACValue,
                    type = CVLType.PureCVLType.Primitive.UIntK(256),
                    tooltip = "unscaled value of decimal number"
                )
            )
            callTraceAppend(CallInstance.SolanaCexPrintValues(formatted, range))
        } else {
            sbfLogger.warn { "cannot infer value of ${snippetCmd.unscaledVal} or ${snippetCmd.scale} to print decimal number. Got: $unscaledVal and $scale" }
        }
        return HandleCmdResult.Continue
    }

    private fun unscaledValAndScaleToBigDecimal(unscaledVal: BigInteger, scale: Int): BigDecimal {
        val divisor = BigDecimal(BigInteger.ONE.shiftLeft(scale)) // 2^fractionalBits
        return BigDecimal(unscaledVal)
            .divide(divisor, scale, RoundingMode.FLOOR) // BigDecimal interprets the scale as base 10, while our scale is in base 2
            .stripTrailingZeros()
    }

    private fun handleSolanaCexPrintLocation(snippetCmd: SnippetCmd.SolanaSnippetCmd.CexPrintLocation): HandleCmdResult {
        val range = filepathAndLineNumberToRange(snippetCmd.filepath, snippetCmd.lineNumber)
        val tag = "${snippetCmd.filepath}:${snippetCmd.lineNumber}"
        callTraceAppend(CallInstance.SolanaCexPrintTag(tag, range))
        return HandleCmdResult.Continue
    }

    private fun handleSolanaCexAttachLocation(snippetCmd: SnippetCmd.SolanaSnippetCmd.CexAttachLocation): HandleCmdResult {
        filepathAndLineNumberToRange(snippetCmd.filepath, snippetCmd.lineNumber)?.let {
            rangesFromAttachLocation.add(it)
            if (rangesFromAttachLocation.size > 1) {
                sbfLogger.warn { "CVT_attach_location has been called two or more times in a row without a call trace entry to consume the locations" }
            }
        }
        return HandleCmdResult.Continue
    }

    /** Converts a filepath and a line number to a range. If the file is not in the sources dir, returns [null]. */
    private fun filepathAndLineNumberToRange(filepath: String, lineNumber: UInt): CVLRange.Range? {
        val fileInSourcesDir = File(Config.prependSourcesDir(filepath))
        return if (fileInSourcesDir.exists()) {
            val cvlRangeLineNumber = lineNumber - 1U
            // We do not have column information.
            val cvlRangeColNumber = 0U
            val sourcePositionStart = SourcePosition(cvlRangeLineNumber, cvlRangeColNumber)
            // Since we do not have end range information, we just assume it is the next line.
            val sourcePositionEnd = SourcePosition(cvlRangeLineNumber + 1U, cvlRangeColNumber)
            CVLRange.Range(filepath, sourcePositionStart, sourcePositionEnd)
        } else {
            sbfLogger.warn {
                "file '$fileInSourcesDir' does not exist: jump to source information will not be available"
            }
            null
        }
    }

    private fun handleSolanaCexPrintTag(
        snippetCmd: SnippetCmd.SolanaSnippetCmd.CexPrintTag,
        stmt: TACCmd.Simple.AnnotationCmd
    ): HandleCmdResult {
        val range = consumeAttachedRangeOrResolve(stmt)
        val instance = CallInstance.SolanaCexPrintTag(
            name = snippetCmd.displayMessage,
            range = range
        )
        callTraceAppend(instance)
        return HandleCmdResult.Continue
    }

    /**
     * If [rangesFromAttachLocation] has at least one entry, pops the range and returns it.
     * If [rangesFromAttachLocation] is empty, reads the range from the debug information from the executable.
     */
    private fun consumeAttachedRangeOrResolve(stmt: TACCmd.Simple.AnnotationCmd): CVLRange.Range? {
        return if (rangesFromAttachLocation.isNotEmpty()) {
            rangesFromAttachLocation.removeLast()
        } else {
            sbfAddressToRangeWithHeuristic(stmt)
        }
    }

    private fun handleSolanaFunctionStart(annot: SbfInlinedFuncStartAnnotation): HandleCmdResult {
        val newInstance = CallInstance.InvokingInstance.SolanaFunctionInstance(
            "${annot.name}(...)",
            annot.id
        )
        callTracePush(newInstance)
        return HandleCmdResult.Continue
    }

    private fun handleSolanaFunctionEnd(annot: SbfInlinedFuncEndAnnotation): HandleCmdResult {
        return ensureStackState(
            requirement = { it is CallInstance.InvokingInstance.SolanaFunctionInstance && it.callIndex == annot.id },
            eventDescription = "start of solana function (id = ${annot.id})"
        )
    }

    private fun handleSolanaExternalCall(
        snippetCmd: SnippetCmd.SolanaSnippetCmd.ExternalCall,
        stmt: TACCmd.Simple.AnnotationCmd
    ): HandleCmdResult {
        val range = consumeAttachedRangeOrResolve(stmt)
        val formattedList = snippetCmd.symbols.map { sym ->
            CallTraceValue.cvlCtfValueOrUnknown(
                model.valueAsTACValue(sym),
                CVLType.PureCVLType.Primitive.UIntK(256)
            ).toSarif(formatter, tooltip = "value")
        }
        val sarif =
            sarifFormatter.fmt(
                "${snippetCmd.displayMessage}${
                    if (formattedList.isNotEmpty()) {
                        ":"
                    } else {
                        ""
                    }
                } " + List(formattedList.size) { _ -> "{}" }
                    .joinToString(", "),
                *formattedList.map { FmtArg(it) }.toTypedArray()
            )
        callTraceAppend(CallInstance.SolanaExternalCall(sarif, range))
        return HandleCmdResult.Continue
    }

    private fun handleSolanaUserAssert(
        snippetCmd: SnippetCmd.SolanaSnippetCmd.Assert,
        stmt: TACCmd.Simple.AnnotationCmd
    ): HandleCmdResult {
        val range = consumeAttachedRangeOrResolve(stmt)
        val msgResult = { assertVerified: Boolean ->
            val isSuccess = if (snippetCmd.fromSatisfy) {
                // If the assertion is generated from satisfy, we want to flip the message: satisfy is OK when there is
                // a violation, and satisfy is FAIL when there is *no* violation.
                !assertVerified
            } else {
                assertVerified
            }
            if (isSuccess) {
                "OK"
            } else {
                "FAIL"
            }
        }
        val assertVerified = model.valueAsBoolean(snippetCmd.symbol).leftOrNull()
        val msg = if (assertVerified != null) {
            if (assertVerified) {
                "${snippetCmd.displayMessage} ${msgResult(assertVerified)}"
            } else {
                "${snippetCmd.displayMessage} ${msgResult(assertVerified)}"
            }
        } else {
            "${snippetCmd.displayMessage} UNKNOWN"
        }
        callTraceAppend(CallInstance.SolanaUserAssert(msg, range))
        return HandleCmdResult.Continue
    }


    /**
     * Converts an SBF address from the metadata of the given TAC command to a range.
     * Returns [null] if the SBF metadata is not present or if it is not possible to resolve the range information.
     * Tries to resolve the inlined frames associated also to previous SBF addresses until [address - windowSize].
     */
    private fun sbfAddressToRangeWithHeuristic(
        stmt: TACCmd.Simple.AnnotationCmd,
        windowSize: UShort = 80U
    ): CVLRange.Range? {
        return stmt.meta[SBF_ADDRESS]?.let { address ->
            val uLongAddress = address.toULong()
            // Consider address, address - 8, address - 16, ..., address - (windowSize + 8)
            val addresses: MutableList<ULong> = mutableListOf()
            var nextAddress = uLongAddress
            // The first condition is to check the absence of underflows.
            while (uLongAddress <= nextAddress && uLongAddress - nextAddress <= windowSize) {
                addresses.add(nextAddress)
                nextAddress -= 8U
            }
            val rangesMap = InlinedFramesInfo.getInlinedFramesInProjectFiles(addresses)
            // Iterate over the addresses: address, address - 8, address - 16, ...
            // The first address that is associated with non-null range information will be the returned address.
            for (addr in addresses) {
                rangesMap[addr]?.let { ranges ->
                    ranges.firstOrNull { range ->
                        return range
                    }
                }
            }
            return null
        }
    }
}
