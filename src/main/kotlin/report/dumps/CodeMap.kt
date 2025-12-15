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

import algorithms.topologicalOrderOrNull
import analysis.CmdPointer
import analysis.icfg.Inliner
import analysis.icfg.SummaryStack
import analysis.icfg.SummaryStack.END_EXTERNAL_SUMMARY
import analysis.ip.InternalFuncExitAnnotation
import analysis.ip.InternalFuncStartAnnotation
import analysis.ip.InternalFunctionFinderReport
import com.certora.collect.*
import compiler.SourceIdentifier
import compiler.SourceSegment
import config.Config
import config.Config.IsGenerateGraphs
import config.ReportTypes
import datastructures.MultiMap
import datastructures.stdcollections.*
import log.*
import move.MoveCallTrace
import report.BigIntPretty.bigIntPretty
import report.TreeViewLocation
import report.dumps.AddInternalFunctions.addInternalFunctionIdxsDontThrow
import sbf.tac.*
import scene.NamedCode
import smtlibutils.data.ProcessDifficultiesResult
import solver.CounterexampleModel
import solver.SMTCounterexampleModel
import spec.CVLExpToTACExprMeta
import statistics.data.CallIdWithName
import tac.*
import utils.*
import utils.ModZm.Companion.addMod
import utils.ModZm.Companion.asBigInteger
import utils.ModZm.Companion.mulMod
import vc.data.*
import vc.data.TACMeta.CVL_LABEL_END
import vc.data.TACMeta.CVL_LABEL_START
import vc.data.TACMeta.CVL_LABEL_START_ID
import vc.data.TACMeta.REVERT_PATH
import vc.data.TACMeta.SCOPE_SNIPPET_END
import vc.data.state.ConcreteTacValue
import vc.data.state.TACValue
import vc.data.tacexprutil.DefaultTACExprTransformer
import vc.gen.LeinoWP
import verifier.TimeoutCoreAnalysis
import verifier.Verifier
import java.math.BigInteger
import kotlin.math.max
import kotlin.math.min

private val logger = Logger(LoggerTypes.UI)

val rarrow = "<span style=\"color:${colorToRGBString(Color.GREEN)};\"><b>→</b></span>" // entry
val larrow = "<span style=\"color:${colorToRGBString(Color.RED)};\"><b>←</b></span>" // exit

/**
 * @param subAsts the [TACProgram] for each given [CallId]; includes [ast] under callId [MAIN_GRAPH_ID] ; NB: subCfgs or subprograms would be more apt names
 * @param subDots the [DotDigraph] for each given [CallId]; does not include [dotMain]
 * @param countDifficultOps analysis stating which commands are difficult for the solver; null if analysis
 *      was not performed (typically because there was no timeout)
 * @param colorExplanation explanations for the colors we're using (currently only for the timeout case, could
 *      extend, e.g., for unsat case); will be displayed in the top-right box (currently assuming this is only non-null
 *      if we're in a Timeout/Unknown or Unsat case)
 * @param fullOriginal the tac program underlying this [CodeMap]
 * @param withInternalFunctions version of [fullOriginal] with internal functions, if the functionality is switched on,
 *      otherwise identical to [fullOriginal]
 * @param intFuncsCmdPtrMapping maps oldBlock to newBlocks (this is a multi map, since we split some old blocks
 *    in two)
 */
data class CodeMap(
    val name: String,
    val ast: TACProgram<*>,
    val subAsts: Map<CallId, TACProgram<*>>,
    val callGraphInfo: CallGraphInfo,
    val edges: Map<Edge, List<TACExpr>>,
    val cexModel: CounterexampleModel? = null,
    val fullOriginal: TACProgram<*>,
    val withInternalFunctions: TACProgram<*>,
    val intFuncsCmdPtrMapping: CmdPtrMapping,
    val dotMain: DotDigraph,
    val subDots: Map<CallId, DotDigraph>,
    val unsatCore: List<TACCmd> = listOf(),
    val unsatCoreDomain: List<TACCmd> = listOf(),
    val countDifficultOps: CountDifficultOps? = null,
    val timeoutCoreInfo: TimeoutCoreAnalysis.TimeoutCoreInfo? = null,
    val colorExplanation: Map<DotColorList, String>? = null,
): NamedCode<ReportTypes> by withInternalFunctions {

    val timeoutCore = timeoutCoreInfo?.coreAsCmdPtrs.orEmpty()
    val timeoutCoreBlocks = timeoutCoreInfo?.timeoutCoreBlocks.orEmpty()

    val heuristicallyDifficultBlocks = countDifficultOps?.getDifficultBlocks().orEmpty()
    val heuristicallyDifficultCommands = countDifficultOps?.getDifficultCmds().orEmpty()

    val showSubsetInCodeText = mutableSetOf<IBlockId>()

    var toolTipCacheId = 0

    val callIdNames: Map<CallId, String> get() = callGraphInfo.callIdToName

    data class ToolTipEntry(val sourceDetails: SourceSegment, val Id: Int)

    private val toolTipCache =
        mutableMapOf<SourceIdentifier, ToolTipEntry>() // file : String?, begin : Int?, len : Int?) : String?

    fun getToolTipCacheForJS(): String {
        // the outer braces are needed for the JS, we build a json for the tac dumps tooltips here
        return "{${
            toolTipCache.entries.map {
                val toolTipString = it.value.sourceDetails.let { sourceDetails ->
                    "${sourceDetails.file}:${sourceDetails.lineNumber}: ${sourceDetails.content}"
                }.sanitize()
                "${it.value.Id}:`${
                    toolTipString
                        .replace("`", "'")
                        .replace(Integer.valueOf(0).toChar(), ' ')
                }`"
            }
                .joinToString(",")
        }}"
    }

    /** The actual SMT checks are run on [CodeMap.fullOriginal], but our display is based on
     * [CodeMap.withInternalFunctions].
     * For that reason, things that come from the model, or other aspects of the actual SMT checks need to be translated
     * using [CodeMap.withInternalFunctions] */
    fun translateWithInternalFuncs(blocks: Set<NBId>) = blocks.flatMap { intFuncsCmdPtrMapping[it] }

    private fun cmdToValue(c: TACCmd): TACValue? = when (c) {
        is TACCmd.Simple.AssigningCmd.ByteStore -> c.value.cexValue
        is TACCmd.Simple.AssigningCmd,
        is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssignSymCmd,
        is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningUnaryOpCmd,
        is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd,
        is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningTernaryOpCmd,
        is TACCmd.EVM.AssignIszeroCmd,
        is TACCmd.EVM.AssignAddressCmd,
        is TACCmd.EVM.AssignBalanceCmd,
        is TACCmd.EVM.AssignOriginCmd,
        is TACCmd.EVM.AssignCallerCmd,
        is TACCmd.EVM.AssignCallvalueCmd,
        is TACCmd.EVM.AssignCalldataloadCmd,
        is TACCmd.EVM.AssignCalldatasizeCmd,
        is TACCmd.EVM.AssignCodesizeCmd,
        is TACCmd.EVM.AssignGaspriceCmd,
        is TACCmd.EVM.AssignExtcodesizeCmd,
        is TACCmd.EVM.AssignExtcodehashCmd,
        is TACCmd.EVM.AssignBlockhashCmd,
        is TACCmd.EVM.AssignCoinbaseCmd,
        is TACCmd.EVM.AssignTimestampCmd,
        is TACCmd.EVM.AssignNumberCmd,
        is TACCmd.EVM.AssignDifficultyCmd,
        is TACCmd.EVM.AssignGaslimitCmd,
        is TACCmd.EVM.AssignChainidCmd,
        is TACCmd.EVM.AssignSelfBalanceCmd,
        is TACCmd.EVM.AssignBasefeeCmd,
        is TACCmd.EVM.AssignBlobhashCmd,
        is TACCmd.EVM.AssignBlobbasefeeCmd,
        is TACCmd.EVM.MloadCmd,
        is TACCmd.EVM.SloadCmd,
        is TACCmd.EVM.CreateCmd,
        is TACCmd.EVM.ExtCallCmd,
        is TACCmd.EVM.ReturndatasizeCmd,
            -> c.getLhs()!!.cexValue

        is TACCmd.Simple.AssumeCmd -> c.cond.cexValue
        is TACCmd.Simple.AssumeNotCmd -> c.cond.cexValue?.negateBool()
        is TACCmd.Simple.AssertCmd -> c.o.cexValue
        is TACCmd.Simple.WordStore -> c.value.cexValue
        else -> null
    }

    sealed interface ExpectedValue {

        class Value(val value: BigInteger) : ExpectedValue {
            override fun toString() =
                bigIntPretty(value)
                    ?.let { "($it)" }
                    ?: "(0x${value.toString(16)})"
        }

        /** There was an exception while trying to calculate the expected value */
        class Error(val msg: String) : ExpectedValue {
            override fun toString() = "ERROR - $msg"
        }

        object Fail : ExpectedValue {
            override fun toString() = "failed to compute expected"
        }

        object NoAnswerButWeDontExpectIt : ExpectedValue {
            override fun toString() = "Can't calculate, but it's fine"
        }
    }

    private fun cmdToExpectedValue(c: TACCmd): ExpectedValue {

        fun evalList(vararg ops: TACSymbol, eval: ModZm.(List<BigInteger>) -> BigInteger): ExpectedValue {
            val opValues = ops.map { it.cexValue?.asBigIntOrNull() }
            return opValues.monadicMap { it }?.let { EVMOps.eval(it) }
                ?.let { ExpectedValue.Value(it) }
                ?: ExpectedValue.NoAnswerButWeDontExpectIt
        }

        fun evalCmd(eval: ModZm.(List<BigInteger>) -> BigInteger): ExpectedValue =
            when (c) {
                is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningUnaryOpCmd ->
                    evalList(c.op1, eval = eval)

                is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd ->
                    evalList(c.op1, c.op2, eval = eval)

                is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningTernaryOpCmd ->
                    evalList(c.op1, c.op2, c.op3, eval = eval)

                else -> ExpectedValue.NoAnswerButWeDontExpectIt
            }

        return when (c) {
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                val (success, result) = try {
                    cexModel!!.evalExprByRhs(c.rhs)
                } catch (arithError: ArithmeticException) {
                    return ExpectedValue.Error(arithError.message ?: "arithmetic error")
                }
                when {
                    !success  -> ExpectedValue.NoAnswerButWeDontExpectIt
                    result == null -> ExpectedValue.Fail
                    else -> ExpectedValue.Value(result)
                }
            }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignAddCmd ->
                evalCmd { (a, b) -> add(a, b) }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignMulCmd ->
                evalCmd { (a, b) -> mul(a, b) }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignSubCmd ->
                evalCmd { (a, b) -> sub(a, b) }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignDivCmd ->
                evalCmd { (a, b) -> div(a, b) }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningTernaryOpCmd.AssignMulModCmd ->
                evalCmd { (a, b, n) -> mulMod(a, b, n) }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningTernaryOpCmd.AssignAddModCmd ->
                evalCmd { (a, b, n) -> addMod(a, b, n) }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignExponentCmd ->
                evalCmd { (a, b) -> exp(a, b) }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignEqCmd ->
                evalCmd { (a, b) -> (a == b).asBigInteger }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignAndCmd ->
                evalCmd { (a, b) -> a and b }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignOrCmd ->
                evalCmd { (a, b) -> a and b }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignXOrCmd ->
                evalCmd { (a, b) -> a xor b }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignShiftLeft ->
                evalCmd { (a, b) -> shl(a, b) }

            is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignShiftRightLogical ->
                evalCmd { (a, b) -> shr(a, b) }

            else -> ExpectedValue.NoAnswerButWeDontExpectIt
        }
    }

    private val TACSymbol.cexValue get() =
        cexModel?.valueAsTACValue(this)

    private val TACValue?.asString: String
        get() =
            when (this) {
                null -> ""
                is TACValue.PrimitiveValue -> bigIntPretty(asBigInt) ?: toString()
                is TACValue.SKey -> skeyValueToHTML(this)
                else -> this.toString().sanitize()
            }

    private fun HTMLString.withTitle(s: String): HTMLString {
        return when (this) {
            is HTMLString.ColoredText -> this.copy(s="<span title=\"$s\">${this.s}</span>")
            is HTMLString.Raw -> HTMLString.Raw("<span title=\"$s\">${this.s}</span>")
        }
    }

    private fun cmdToHtml(c: TACCmd, ast: TACProgram<*>, b: IBlockId): HTMLString {

        fun varWithAnchor(v: TACSymbol.Var) = v.toSMTRep().sanitize().let {
            "<a href=\"#def_$it\" class=\"deflink use_$it\" onclick=\"highlightDef('$it')\">$it</a>"
        }

        fun getHtmlRep(s: TACSymbol): String {
            return when (s) {
                is TACSymbol.Const -> s.toSMTRep().sanitize()
                is TACSymbol.Var -> {
                    // let's try to link
                    varWithAnchor(s)
                }
            }
        }

        fun getHtmlRep(v: MoveCallTrace.Value): String {
            return when (v) {
                is MoveCallTrace.Value.Primitive -> getHtmlRep(v.sym)
                is MoveCallTrace.Value.Struct -> {
                    v.fields.joinToString(", ", "{", "}") { (name, value) ->
                        "$name:${getHtmlRep(value)}"
                    }
                }
                is MoveCallTrace.Value.Enum -> "enum"
                is MoveCallTrace.Value.Vector -> "std::vector(${getHtmlRep(v.length)})"
                is MoveCallTrace.Value.Reference -> "&amp;${getHtmlRep(v.value)}"
                is MoveCallTrace.Value.NotDisplayed -> v.message
            }
        }

        fun getHtmlRepAnchor(s: TACSymbol): String {
            // wrap with an anchor
            val t = s.toSMTRep().sanitize()
            return "<span id=\"def_$t\">$t</span>"
        }

        fun getHtmlRepExpr(e: TACExpr): String {
            return e.toPrintRep(::varWithAnchor)
        }

        fun wrapInternalFunStart(id: Int) = "<a id=\"intfunStart_$id\" onclick=\"toggleInternalFun($id)\"><u>$id</u></a>"
        fun wrapInternalFunEnd(id: Int) = "<a id=\"intfunEnd_$id\" onclick=\"toggleInternalFun($id)\"><u>$id</u></a>"


        val cmdHtml: HTMLString = when (c) {
            is TACCmd.Simple.SummaryCmd -> colorText("TRANSIENT::${c.summ.annotationDesc}", Color.DARKBLUE).withTitle(c.summ.toString())
            is TACCmd.Simple.AnnotationCmd -> {
                when (val metaValue = c.annot.v) {
                    is SnippetCmd.EVMSnippetCmd.ContractSourceSnippet.AssignmentSnippet -> {
                        colorText("Assignment to ${metaValue.parse.data.name}, also known as ${metaValue.lhs.namePrefix}", Color.ORANGE)
                    }
                    is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot -> {
                        colorText("Take snapshot of storage ${metaValue.rhs?.let{ "${getHtmlRep(it)} "}.orEmpty()} in ${getHtmlRep(metaValue.lhs)}", Color.ORANGE)
                    }
                    is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageRestoreSnapshot -> {
                        colorText("Restore snapshot of storage from ${getHtmlRep(metaValue.name)}", Color.ORANGE)
                    }
                    is SnippetCmd.EVMSnippetCmd.TransferSnippet -> {
                        colorText("Transferring ${getHtmlRep(metaValue.transferredAmount)} from ${getHtmlRep(metaValue.srcAccountInfo.addr)} to ${getHtmlRep(metaValue.trgAccountInfo.addr)}", Color.DARKBLUE)
                    }
                    is SnippetCmd.CVLSnippetCmd.AssertCast -> colorText("$rarrow Assert cast:", Color.DARKBLUE)
                    is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageHavocContract -> colorText("Havoc contract with ID ${metaValue.addr.toString(16)}", Color.DARKBLUE)
                    is SnippetCmd.LoopSnippet.StartLoopSnippet -> colorText("$rarrow Loop #${metaValue.loopIndex}, with source `${metaValue.loopSource}`", Color.DARKBLUE)
                    is SnippetCmd.LoopSnippet.EndLoopSnippet -> colorText("$larrow Loop #${metaValue.loopId}", Color.DARKBLUE)
                    is SnippetCmd.LoopSnippet.StartIter -> colorText("$rarrow$rarrow Iteration ${metaValue.iteration}", Color.DARKBLUE)
                    is SnippetCmd.LoopSnippet.EndIter -> colorText("$larrow$larrow Iteration ${metaValue.iteration}", Color.DARKBLUE)
                    is SnippetCmd.SolanaSnippetCmd.ExternalCall -> colorText("SbfExt ${metaValue.symbols.joinToString(", ") { getHtmlRep(it) }} = ${metaValue.displayMessage.shortRustForHTML()} ", Color.DARKBLUE).withTitle(metaValue.toString())
                    is SnippetCmd.CvlrSnippetCmd.CexPrintTag -> colorText("log(${metaValue.displayMessage.sanitize()})", Color.GREEN)
                    is SnippetCmd.CvlrSnippetCmd.CexAttachLocation -> colorText("${metaValue.filepath.sanitize()}:${metaValue.lineNumber}", Color.DARKGREY)
                    is SnippetCmd.CvlrSnippetCmd.CexPrintLocation -> colorText("${metaValue.filepath.sanitize()}:${metaValue.lineNumber}", Color.DARKGREY)
                    is SnippetCmd.CvlrSnippetCmd.CexPrintValues -> colorText("${metaValue.displayMessage.sanitize()}: ${metaValue.symbols.joinToString(", ") { getHtmlRep(it) }}", Color.ORANGE)
                    is SnippetCmd.CvlrSnippetCmd.CexPrintU64AsFixedOrDecimal -> {
                        val radix = if (metaValue.asFixed) { 2 } else { 10 }
                        colorText(
                            "${metaValue.displayMessage.sanitize()}: ${getHtmlRep(metaValue.unscaledVal)}/$radix^${
                                getHtmlRep(
                                    metaValue.scale
                                )
                            }", Color.ORANGE
                        )
                    }
                    is SnippetCmd.CvlrSnippetCmd.CexPrint128BitsValue -> {
                        colorText (
                            s="${metaValue.displayMessage.sanitize()}: ${getHtmlRep(metaValue.low)}::${getHtmlRep(metaValue.high)}",
                            color=Color.ORANGE
                        )
                    }
                    is InternalFuncStartAnnotation -> colorText("$rarrow Method call ${wrapInternalFunStart(metaValue.id)} to ${
                        if (metaValue.args.isNotEmpty() && metaValue.args.size == metaValue.methodSignature.params.size) {
                            metaValue.methodSignature.let { sig ->
                                "${sig.qualifiedMethodName.methodId}${sig.params.zip(metaValue.args).joinToString(", ","(",")") { (param,arg) ->
                                    "${param.prettyPrint()} = ${getHtmlRep(arg.s)}"
                                }}"
                            }
                        } else {
                            metaValue.methodSignature
                        }
                    }", Color.DARKBLUE).withTitle(metaValue.toString())
                    is InternalFuncExitAnnotation -> colorText("$larrow Method call ${wrapInternalFunEnd(metaValue.id)} ${metaValue.rets.joinToString(", ","(rets: ",")") { getHtmlRep(it.s) }})", Color.DARKBLUE).withTitle(metaValue.toString())
                    is MoveCallTrace.FuncStart -> colorText("$rarrow ${metaValue.name.toString().escapeHTML()}", Color.DARKBLUE).withTitle(metaValue.toString())
                    is MoveCallTrace.FuncArgs -> colorText("${metaValue.name.toString().escapeHTML()}(${metaValue.args.joinToString(", ") { getHtmlRep(it) }})", Color.DARKGREY).withTitle(metaValue.toString())
                    is MoveCallTrace.FuncEnd -> colorText("$larrow ${metaValue.name.toString().escapeHTML()}(returns ${metaValue.returns.joinToString(", ") { getHtmlRep(it) }})", Color.DARKBLUE).withTitle(metaValue.toString())
                    is Inliner.CallStack.PushRecord -> colorText("$rarrow Solidity call ${metaValue.calleeId}", Color.DARKBLUE).withTitle(metaValue.toString())
                    is Inliner.CallStack.PopRecord -> colorText("$larrow Solidity call ${metaValue.calleeId}", Color.DARKBLUE).withTitle(metaValue.toString())
                    is InternalFunctionFinderReport -> if (metaValue.unresolvedFunctions.isNotEmpty()) {
                        colorText("Unresolved internal functions: ${metaValue.unresolvedFunctions}", Color.RED)
                    } else {
                        colorText("No unresolved internal functions!", Color.GREEN)
                    }
                    is SummaryStack.SummaryStart.External ->colorText("$rarrow Applying summary ${metaValue.summary} " +
                        "to:<br/>&nbsp;&nbsp;${metaValue.callNode}" +
                        "<br/>&nbsp;&nbsp;Call resolution: ${metaValue.callResolutionTableInfo}", Color.DARKBLUE).withTitle(metaValue.toString())
                    is SbfInlinedFuncStartAnnotation -> colorText("$rarrow SbfCall ${metaValue.name.shortRustForHTML()}${metaValue.args.joinToString(", ", "(", ")") { getHtmlRep(it.first) }} (id: ${metaValue.id})", Color.DARKBLUE).withTitle(metaValue.toString())
                    is SbfInlinedFuncEndAnnotation -> colorText("$larrow SbfRet ${getHtmlRep(metaValue.retVal)} = ${metaValue.name.shortRustForHTML()}(..) (id: ${metaValue.id})", Color.DARKBLUE).withTitle(metaValue.toString())
                    else -> {
                        val (k, v) = c.annot.let { it.k to it.v.toString().sanitize() }
                        val eventId = c.eventId()
                        when {
                            eventId != null -> colorText("$rarrow ($eventId) $v", Color.DARKBLUE)
                            k == CVL_LABEL_END -> colorText("$larrow ($v)", Color.DARKBLUE)
                            k == REVERT_PATH -> colorText("From here we definitely revert", Color.DARKPINK)
                            k == SCOPE_SNIPPET_END -> colorText("$larrow", Color.DARKBLUE)
                            k == END_EXTERNAL_SUMMARY -> colorText("$larrow", Color.DARKBLUE)
                            k == DEBUG_EXTERNAL_CALL -> colorText("SbfDbgExtCall ${v.sanitize()}", Color.DARKBLUE)
                            k == DEBUG_INLINED_FUNC_START -> colorText("$rarrow SbfDbgCall ${v.sanitize()}", Color.DARKBLUE)
                            k == DEBUG_INLINED_FUNC_END -> colorText("$larrow SbfDbgRet ${v.sanitize()}", Color.DARKBLUE)
                            else -> colorText("TRANSIENT::${k}=${v}::", Color.DARKGREY)
                        }
                    }
                }
            }
            is TACCmd.Simple.LabelCmd -> colorText("::${c._msg.sanitize()}::", Color.DARKGREY)
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> "${getHtmlRepAnchor(c.lhs)} = ${getHtmlRepExpr(c.rhs)}".asRaw()
            is TACCmd.EVM -> {
                when (c) {
                    TACCmd.EVM.StopCmd -> "STOP".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssignSymCmd -> "${getHtmlRep(c.lhs)} = ${getHtmlRep(c.rhs)}".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignAddCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } + ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignMulCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } * ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignSubCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } - ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignDivCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } / ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignSdivCmd -> "${getHtmlRep(c.lhs)} = SDIV(${
                        getHtmlRep(
                            c.op1
                        )
                    }, ${
                        getHtmlRep(
                            c.op2
                        )
                    })".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignModCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } % ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignSModCmd -> "${getHtmlRep(c.lhs)} = SMOD(${
                        getHtmlRep(
                            c.op1
                        )
                    }, ${
                        getHtmlRep(
                            c.op2
                        )
                    })".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningTernaryOpCmd.AssignAddModCmd -> "${getHtmlRep(c.lhs)} = ADDMOD(${
                        getHtmlRep(
                            c.op1
                        )
                    }, ${getHtmlRep(c.op2)}, ${getHtmlRep(c.op3)})".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningTernaryOpCmd.AssignMulModCmd -> "${getHtmlRep(c.lhs)} = MULMOD(${
                        getHtmlRep(
                            c.op1
                        )
                    }, ${getHtmlRep(c.op2)}, ${getHtmlRep(c.op3)})".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignExponentCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } to the ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignLtCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } < ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignGtCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } > ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignSltCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } s< ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignSgtCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } s> ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignEqCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } == ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssignIszeroCmd -> "${getHtmlRep(c.lhs)} = ${getHtmlRep(c.op1)} == 0".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignAndCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } & ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignOrCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } | ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignXOrCmd -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } ^ ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningUnaryOpCmd.AssignNotCmd -> "${getHtmlRep(c.lhs)} = ! ${
                        getHtmlRep(
                            c.op1
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.AssignByteCmd -> "${getHtmlRep(c.lhs)} = (${getHtmlRep(c.op2)} >> (${
                        getHtmlRep(
                            c.op1
                        )
                    } * 8)) & 0xFF".asRaw()
                    is TACCmd.EVM.AssignAddressCmd -> "${getHtmlRep(c.lhs)} = address(this)".asRaw()
                    is TACCmd.EVM.AssignBalanceCmd -> "${getHtmlRep(c.lhs)} = address(${getHtmlRep(c.op1)}).balance".asRaw()
                    is TACCmd.EVM.AssignOriginCmd -> "${getHtmlRep(c.lhs)} = ${boldText("tx.origin")}".asRaw()
                    is TACCmd.EVM.AssignCallerCmd -> {
                        if (b.getCallId() != 0) {
                            "${getHtmlRep(c.lhs)} = ${boldText("msg.sender@${b.getCallId()}")}".asRaw()
                        } else {
                            "${getHtmlRep(c.lhs)} = ${boldText("msg.sender")}".asRaw()
                        }
                    }
                    is TACCmd.EVM.AssignCallvalueCmd -> {
                        if (b.getCallId() != 0) {
                            "${getHtmlRep(c.lhs)} = ${boldText("msg.value@${b.getCallId()}")}".asRaw()

                        } else {
                            "${getHtmlRep(c.lhs)} = ${boldText("msg.value")}".asRaw()
                        }
                    }
                    is TACCmd.EVM.AssignCalldataloadCmd -> {
                        if (b.getCallId() != 0) {
                            "${getHtmlRep(c.lhs)} = ${boldText("${c.buffer}@${b.getCallId()}")}[${getHtmlRep(c.op1)}:${
                                getHtmlRep(
                                    c.op1
                                )
                            }+32]".asRaw()
                        } else {
                            "${getHtmlRep(c.lhs)} = ${boldText(c.buffer)}[${getHtmlRep(c.op1)}:${getHtmlRep(c.op1)}+32]".asRaw()
                        }
                    }
                    is TACCmd.EVM.AssignCalldatasizeCmd -> {
                        if (b.getCallId() != 0) {
                            "${getHtmlRep(c.lhs)} = ${boldText("${c.buffer}@${b.getCallId()}")}.size".asRaw()
                        } else {
                            "${getHtmlRep(c.lhs)} = ${boldText(c.buffer)}.size".asRaw()
                        }
                    }
                    is TACCmd.EVM.CalldatacopyCmd -> "${c.memBaseMap}[${getHtmlRep(c.op1)}:${getHtmlRep(c.op1)}+${getHtmlRep(c.op3)}] = ${
                        boldText(
                            c.buffer
                        )
                    }[${getHtmlRep(c.op2)}:${getHtmlRep(c.op2)}+${getHtmlRep(c.op3)}]".asRaw()
                    is TACCmd.EVM.AssignCodesizeCmd -> "${getHtmlRep(c.lhs)} = address(this).code.size [${c.sz}]".asRaw()
                    is TACCmd.EVM.CodecopyCmd -> "${c.memBaseMap}[${getHtmlRep(c.op1)}:${getHtmlRep(c.op1)}+${getHtmlRep(c.op3)}] = address(this).code[${
                        getHtmlRep(
                            c.op2
                        )
                    }:${getHtmlRep(c.op2)}+${getHtmlRep(c.op3)}]".asRaw()
                    is TACCmd.EVM.AssignGaspriceCmd -> "${getHtmlRep(c.lhs)} = ${boldText("tx.gasprice")}".asRaw()
                    is TACCmd.EVM.AssignExtcodesizeCmd -> "${getHtmlRep(c.lhs)} = address(${getHtmlRep(c.op1)}).code.size".asRaw()
                    is TACCmd.EVM.AssignExtcodehashCmd -> "${getHtmlRep(c.lhs)} = address(${getHtmlRep(c.op1)}).code.hash".asRaw()
                    is TACCmd.EVM.ExtcodecopyCmd -> "${c.memBaseMap}[${getHtmlRep(c.op2)}:${getHtmlRep(c.op2)}+${getHtmlRep(c.op4)}] = address(${
                        getHtmlRep(
                            c.op1
                        )
                    }).code[${getHtmlRep(c.op3)}:${getHtmlRep(c.op3)}+${getHtmlRep(c.op4)}]".asRaw()
                    is TACCmd.EVM.AssignBlockhashCmd -> "${getHtmlRep(c.lhs)} = ${boldText("block.blockhash")}(${getHtmlRep(c.op)})".asRaw()
                    is TACCmd.EVM.AssignCoinbaseCmd -> "${getHtmlRep(c.lhs)} = ${boldText("block.coinbase")}".asRaw()
                    is TACCmd.EVM.AssignTimestampCmd -> "${getHtmlRep(c.lhs)} = ${boldText("block.timestamp")}".asRaw()
                    is TACCmd.EVM.AssignNumberCmd -> "${getHtmlRep(c.lhs)} = ${boldText("block.number")}".asRaw()
                    is TACCmd.EVM.AssignDifficultyCmd -> "${getHtmlRep(c.lhs)} = ${boldText("block.difficulty")}".asRaw()
                    is TACCmd.EVM.AssignGaslimitCmd -> "${getHtmlRep(c.lhs)} = ${boldText("block.gaslimit")}".asRaw()
                    is TACCmd.EVM.AssignChainidCmd -> "${getHtmlRep(c.lhs)} = ${boldText("chainid()")}".asRaw() // TODO: Presentation may change to block.changeid? Solc haven't decided yet
                    is TACCmd.EVM.AssignSelfBalanceCmd -> "${getHtmlRep(c.lhs)} = address(this).balance".asRaw()
                    is TACCmd.EVM.AssignBasefeeCmd -> "${getHtmlRep(c.lhs)} = ${boldText("block.basefee")}".asRaw()
                    is TACCmd.EVM.AssignBlobhashCmd -> "${getHtmlRep(c.lhs)} = ${boldText("tx.blobhashes[${
                        getHtmlRep(
                            c.index
                        )
                    }]")}".asRaw()
                    is TACCmd.EVM.AssignBlobbasefeeCmd -> "${getHtmlRep(c.lhs)} = ${boldText("block.blobbasefee")}".asRaw()
                    is TACCmd.EVM.MloadCmd -> "${getHtmlRep(c.lhs)} = ${getHtmlRep(c.memBaseMap)}[${getHtmlRep(c.loc)}:${
                        getHtmlRep(
                            c.loc
                        )
                    }+32]".asRaw()
                    is TACCmd.EVM.MstoreCmd -> "${getHtmlRep(c.memBaseMap)}[${getHtmlRep(c.loc)}:${getHtmlRep(c.loc)}+32] = ${
                        getHtmlRep(
                            c.rhs
                        )
                    }".asRaw()
                    is TACCmd.EVM.MbytestoreCmd -> "${getHtmlRep(c.memBaseMap)}[${getHtmlRep(c.loc)}] = ${getHtmlRep(c.rhs)} & 0xFF".asRaw()
                    is TACCmd.EVM.McopyCmd -> "${getHtmlRep(c.memBaseMap)}[${getHtmlRep(c.dst)}:${getHtmlRep(c.len)}] = ${getHtmlRep(c.memBaseMap)}[${getHtmlRep(c.src)}:${getHtmlRep(c.len)}]".asRaw()
                    is TACCmd.EVM.SloadCmd -> "${getHtmlRep(c.lhs)} = ${getHtmlRep(c.storageBaseMap)}[${getHtmlRep(c.loc)}]".asRaw()
                    is TACCmd.EVM.SstoreCmd -> "${getHtmlRep(c.storageBaseMap)}[${getHtmlRep(c.loc)}] = ${getHtmlRep(c.rhs)}".asRaw()
                    is TACCmd.EVM.TloadCmd -> "${getHtmlRep(c.lhs)} = ${getHtmlRep(c.transientStorageBaseMap)}[${getHtmlRep(c.loc)}]".asRaw()
                    is TACCmd.EVM.TstoreCmd -> "${getHtmlRep(c.transientStorageBaseMap)}[${getHtmlRep(c.loc)}] = ${getHtmlRep(c.rhs)}".asRaw()
                    is TACCmd.EVM.CreateCmd -> "${getHtmlRep(c.lhs)} = address of: new ${c.memBaseMap}[${getHtmlRep(c.offset)}:${
                        getHtmlRep(
                            c.offset
                        )
                    }+${getHtmlRep(c.length)}].value(${getHtmlRep(c.value)})".asRaw()
                    is TACCmd.EVM.Create2Cmd -> "${getHtmlRep(c.lhs)} = address of: new ${c.memBaseMap}[${getHtmlRep(c.offset)}:${
                        getHtmlRep(
                            c.offset
                        )
                    }+${getHtmlRep(c.length)}].value(${getHtmlRep(c.value)}) salt ${getHtmlRep(c.salt)}".asRaw()
                    is TACCmd.EVM.CallCmd -> "${getHtmlRep(c.lhs)} = success of address(${getHtmlRep(c.addr)}).call.gas(${
                        getHtmlRep(
                            c.gas
                        )
                    }).value(${getHtmlRep(c.value)})(${getHtmlRep(c.memBaseMap)}[${getHtmlRep(c.argsOffset)}:${getHtmlRep(c.argsOffset)}+${
                        getHtmlRep(
                            c.argsSize
                        )
                    }]), return stored in ${getHtmlRep(c.memBaseMap)}[${getHtmlRep(c.retOffset)}:${getHtmlRep(c.retOffset)}+${getHtmlRep(c.retSize)}]".asRaw()
                    is TACCmd.EVM.CallcodeCmd -> "${getHtmlRep(c.lhs)} = success of address(${getHtmlRep(c.addr)}).callcode.gas(${
                        getHtmlRep(
                            c.gas
                        )
                    }).value(${getHtmlRep(c.value)})(M[${getHtmlRep(c.argsOffset)}:${getHtmlRep(c.argsOffset)}+${getHtmlRep(c.argsSize)}]), return stored in M@${b.getCallId()}[${
                        getHtmlRep(
                            c.retOffset
                        )
                    }:${getHtmlRep(c.retOffset)}+${getHtmlRep(c.retSize)}]".asRaw()
                    is TACCmd.EVM.EVMReturnCmd -> colorText(
                        "return M@${b.getCallId()}[${getHtmlRep(c.o1)}:${getHtmlRep(c.o1)}+${getHtmlRep(c.o2)}]",
                        Color.GREEN
                    )
                    is TACCmd.EVM.DelegatecallCmd -> "${getHtmlRep(c.lhs)} = success of address(${getHtmlRep(c.addr)}).delegatecall.gas(${
                        getHtmlRep(
                            c.gas
                        )
                    })(M[${getHtmlRep(c.argsOffset)}:${getHtmlRep(c.argsOffset)}+${getHtmlRep(c.argsSize)}]), return stored in @${b.getCallId()}[${
                        getHtmlRep(
                            c.retOffset
                        )
                    }:${
                        getHtmlRep(
                            c.retOffset
                        )
                    }+${getHtmlRep(c.retSize)}]".asRaw()
                    TACCmd.EVM.InvalidCmd -> colorText("Invalid", Color.RED)
                    is TACCmd.EVM.SelfdestructCmd -> "selfdestruct(address(${c.o}))".asRaw()
                    is TACCmd.EVM.EVMRevertCmd -> {
                        if (c.revertType == TACRevertType.BENIGN) {
                            colorText(
                                "revert and return M@${b.getCallId()}[${getHtmlRep(c.o1)}:${getHtmlRep(c.o1)}+${getHtmlRep(c.o2)}]",
                                Color.PINK
                            )
                        } else {
                            colorText(
                                "throw and return M@${b.getCallId()}[${getHtmlRep(c.o1)}:${getHtmlRep(c.o1)}+${getHtmlRep(c.o2)}]",
                                Color.RED
                            )
                        }
                    }
                    is TACCmd.EVM.ReturndatasizeCmd -> "${getHtmlRep(c.lhs)} = RETURNDATASIZE".asRaw() // TODO: Better explain. this is set in CALL,CALLCODE,DELEGATECALL,STATICCALL?
                    is TACCmd.EVM.ReturndatacopyCmd -> "M@${b.getCallId()}[${getHtmlRep(c.o1)}:${getHtmlRep(c.o1)}+${
                        getHtmlRep(
                            c.o3
                        )
                    }] = RETURNDATA[${
                        getHtmlRep(
                            c.o2
                        )
                    }:${getHtmlRep(c.o2)}+${getHtmlRep(c.o3)}]".asRaw()
                    is TACCmd.EVM.StaticcallCmd -> "${getHtmlRep(c.lhs)} = success of address(${getHtmlRep(c.addr)}).staticcall.gas(${
                        getHtmlRep(
                            c.gas
                        )
                    })(${getHtmlRep(c.memBaseMap)}[${getHtmlRep(c.argsOffset)}:${getHtmlRep(c.argsOffset)}+${getHtmlRep(c.argsSize)}]), return stored in ${
                        getHtmlRep(
                            c.memBaseMap
                        )
                    }[${getHtmlRep(c.retOffset)}:${getHtmlRep(c.retOffset)}+${getHtmlRep(c.retSize)}]".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignShiftLeft -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } << ${
                        getHtmlRep(
                            c.op2
                        )
                    }".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignShiftRightLogical -> "${getHtmlRep(c.lhs)} = ${
                        getHtmlRep(
                            c.op1
                        )
                    } >>l ${getHtmlRep(c.op2)}".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignShiftRightArithmetical -> "${
                        getHtmlRep(
                            c.lhs
                        )
                    } = ${
                        getHtmlRep(
                            c.op1
                        )
                    } >>a ${getHtmlRep(c.op2)}".asRaw()
                    is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd.AssignSignextendCmd -> "${getHtmlRep(c.lhs)} = SignExtend(${
                        getHtmlRep(
                            c.op1
                        )
                    }, ${
                        getHtmlRep(
                            c.op2
                        )
                    })".asRaw()
                    is TACCmd.EVM.AssigningCmd.AssignLibraryAddressCmd -> "${getHtmlRep(c.lhs)} = LinkLibrary(${c.libraryContractId})".asRaw()
                    is TACCmd.EVM.EVMLog0Cmd -> ("LOG0 " +
                        "${getHtmlRep(c.o1)} ${getHtmlRep(c.o2)}"
                        ).asRaw()
                    is TACCmd.EVM.EVMLog1Cmd -> ("LOG1 " +
                        "${getHtmlRep(c.o1)} ${getHtmlRep(c.o2)}" +
                        "${getHtmlRep(c.t1)}"
                        ).asRaw()
                    is TACCmd.EVM.EVMLog2Cmd -> ("LOG2 " +
                        "${getHtmlRep(c.o1)} ${getHtmlRep(c.o2)}" +
                        "${getHtmlRep(c.t1)} ${getHtmlRep(c.t2)}"
                        ).asRaw()
                    is TACCmd.EVM.EVMLog3Cmd -> ("LOG3 " +
                        "${getHtmlRep(c.o1)} ${getHtmlRep(c.o2)}" +
                        "${getHtmlRep(c.t1)} ${getHtmlRep(c.t2)} ${getHtmlRep(c.t3)}"
                        ).asRaw()
                    is TACCmd.EVM.EVMLog4Cmd -> ("LOG4 " +
                        "${getHtmlRep(c.o1)} ${getHtmlRep(c.o2)}" +
                        "${getHtmlRep(c.t1)} ${getHtmlRep(c.t2)} ${getHtmlRep(c.t3)} ${getHtmlRep(c.t4)}"
                        ).asRaw()

                }
            }

            is TACCmd.Move -> when (c) {
                is TACCmd.Move.BorrowLocCmd ->
                    "${getHtmlRep(c.ref)} = &${getHtmlRep(c.loc)}".asRaw()
                is TACCmd.Move.ReadRefCmd ->
                    "${getHtmlRep(c.dst)} = *${getHtmlRep(c.ref)}".asRaw()
                is TACCmd.Move.WriteRefCmd ->
                    "*${getHtmlRep(c.ref)} = ${getHtmlRep(c.src)}".asRaw()
                is TACCmd.Move.PackStructCmd ->
                    c.srcs.joinToString(", ", "{", "}") { getHtmlRep(it) }.let {
                        "${getHtmlRep(c.dst)} = $it".asRaw()
                    }
                is TACCmd.Move.UnpackStructCmd ->
                    c.dsts.joinToString(", ", "{", "}") { getHtmlRep(it) }.let {
                        "$it = ${getHtmlRep(c.src)}".asRaw()
                    }
                is TACCmd.Move.BorrowFieldCmd ->
                    "${getHtmlRep(c.dstRef)} = &${getHtmlRep(c.srcRef)}[${c.fieldIndex}]".asRaw()
                is TACCmd.Move.VecPackCmd ->
                    c.srcs.joinToString(", ", "[", "]") { getHtmlRep(it) }.let {
                        "${getHtmlRep(c.dst)} = $it".asRaw()
                    }
                is TACCmd.Move.VecUnpackCmd ->
                    c.dsts.joinToString(", ", "[", "]") { getHtmlRep(it) }.let {
                        "$it = ${getHtmlRep(c.src)}".asRaw()
                    }
                is TACCmd.Move.VecBorrowCmd ->
                    "${getHtmlRep(c.dstRef)} = &${getHtmlRep(c.srcRef)}[${c.index}]".asRaw()
                is TACCmd.Move.VecLenCmd ->
                    "${getHtmlRep(c.dst)} = length(${getHtmlRep(c.ref)})".asRaw()
                is TACCmd.Move.VecPushBackCmd ->
                    "push(${getHtmlRep(c.ref)}, ${getHtmlRep(c.src)})".asRaw()
                is TACCmd.Move.VecPopBackCmd ->
                    "${getHtmlRep(c.dst)} = pop(${getHtmlRep(c.ref)})".asRaw()
                is TACCmd.Move.PackVariantCmd ->
                    c.srcs.joinToString(", ", "{", "}") { getHtmlRep(it) }.let {
                        "${getHtmlRep(c.dst)} = $it".asRaw()
                    }
                is TACCmd.Move.UnpackVariantCmd ->
                    c.dsts.joinToString(", ", "{", "}") { getHtmlRep(it) }.let {
                        "$it = ${getHtmlRep(c.src)}".asRaw()
                    }
                is TACCmd.Move.UnpackVariantRefCmd ->
                    c.dsts.joinToString(", ", "{", "}") { getHtmlRep(it) }.let {
                        "$it = ${getHtmlRep(c.srcRef)}".asRaw()
                    }
                is TACCmd.Move.VariantIndexCmd ->
                    "${getHtmlRep(c.index)} = variant(${getHtmlRep(c.ref)})".asRaw()
                is TACCmd.Move.GhostArrayBorrowCmd ->
                    "${getHtmlRep(c.dstRef)} = &${getHtmlRep(c.arrayRef)}[${c.index}]".asRaw()
                is TACCmd.Move.HashCmd ->
                    "${getHtmlRep(c.dst)} = hash<${c.hashFamily}>(${getHtmlRep(c.loc)})".asRaw()
                is TACCmd.Move.EqCmd ->
                    "${getHtmlRep(c.dst)} = ${getHtmlRep(c.a)} == ${getHtmlRep(c.b)}".asRaw()
            }

            is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> "${getHtmlRep(c.lhs)} = keccak256(${getHtmlRep(c.memBaseMap)}[${
                getHtmlRep(
                    c.op1
                )
            }:${getHtmlRep(c.op1)}+${getHtmlRep(c.op2)}])".asRaw()
            is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> "${getHtmlRep(c.lhs)} = keccak256simple(${
                c.args.map {
                    getHtmlRep(
                        it
                    )
                }.joinToString(",")
            })".asRaw()

            is TACCmd.Simple.JumpCmd -> "goto: ${linkTo(c.dst)}".asRaw() // TODO: Link to blocks. perhaps use info from blockgraph, should be passed here. or fix the code first.
            is TACCmd.Simple.JumpiCmd -> {
                val posDst = c.dst
                val negDst = ast.blockgraph[b]?.filterNot { it == posDst }?.firstOrNull() ?: StartBlock
                "if ${getHtmlRep(c.cond)} goto ${linkTo(posDst)} else goto ${linkTo(negDst)}".asRaw() // TODO: Else, and link to blocks
            }
            is TACCmd.Simple.AssigningCmd.AssignMsizeCmd -> "${getHtmlRep(c.lhs)} = MSIZE()".asRaw()
            is TACCmd.Simple.AssigningCmd.AssignGasCmd -> "${getHtmlRep(c.lhs)} = GAS()".asRaw()
            is TACCmd.Simple.JumpdestCmd -> "JUMPDEST ${c.startPC}".asRaw() // will be filtered out
            is TACCmd.Simple.LogCmd -> "LOG ${
                c.args.map { getHtmlRep(it) }
                    .joinToString(" ")
            } ${getHtmlRep(c.memBaseMap)}".asRaw() // TODO: Better!


            is TACCmd.Simple.CallCore -> "address(${getHtmlRep(c.to)}).call.gas(${getHtmlRep(c.gas)})(${getHtmlRep(c.inBase)}[${
                getHtmlRep(
                    c.inOffset
                )
            }:${getHtmlRep(c.inOffset)}+${getHtmlRep(c.inSize)}]) to ${getHtmlRep(c.outBase)}[${
                getHtmlRep(
                    c.outOffset
                )
            }:${getHtmlRep(c.outOffset)}+${getHtmlRep(c.outSize)}] ${c.callType}".asRaw()

            is TACCmd.Simple.ReturnSymCmd -> colorText(
                "return ${getHtmlRep(c.o)}",
                Color.GREEN
            )
            is TACCmd.Simple.ReturnCmd -> colorText(
                "return M@${b.getCallId()}[${getHtmlRep(c.o1)}:${getHtmlRep(c.o1)}+${getHtmlRep(c.o2)}]",
                Color.GREEN
            )

            is TACCmd.Simple.RevertCmd -> {
                if (c.revertType == TACRevertType.BENIGN) {
                    colorText(
                        "revert and return M@${b.getCallId()}[${getHtmlRep(c.o1)}:${getHtmlRep(c.o1)}+${getHtmlRep(c.o2)}]",
                        Color.PINK
                    )
                } else {
                    colorText(
                        "throw and return M@${b.getCallId()}[${getHtmlRep(c.o1)}:${getHtmlRep(c.o1)}+${getHtmlRep(c.o2)}]",
                        Color.RED
                    )
                }
            }

            TACCmd.Simple.NopCmd -> "NOP".asRaw()
            is TACCmd.Simple.AssumeCmd -> colorText("assume ${getHtmlRep(c.cond)}, ${c.msg.sanitize()}", Color.DARKPINK)
            is TACCmd.Simple.AssumeNotCmd -> colorText("assume !${getHtmlRep(c.cond)}", Color.DARKPINK)
            is TACCmd.Simple.AssumeExpCmd -> colorText("assume ${getHtmlRepExpr(c.cond)}", Color.DARKPINK)
//            is TACCmd.Simple.AssignSelect2DCmd -> "${getHtmlRep(c.lhs)} = ${getHtmlRep(c.map)}[${c.loc1}][${getHtmlRep(c.loc2)}]"
//            is TACCmd.Simple.AssignStore2DCmd -> "${getHtmlRep(c.lhs)}[${c.loc1}][${getHtmlRep(c.loc2)}] = ${getHtmlRep(c.rhs)}"
//            is TACCmd.Simple.AssignInitArray -> "${getHtmlRep(c.lhs)} = array of ${c.defVal} (type ${c.mapType})"
            is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> "${getHtmlRepAnchor(c.lhs)} = havoc".asRaw()
            is TACCmd.Simple.AssertCmd -> colorText("assert ${getHtmlRep(c.o)}, ${c.msg.sanitize()}", Color.RED)
            //is TACCmd.Simple.AssignLAndCmd -> "${getHtmlRep(c.lhs)} = ${getHtmlRep(c.op1)} && ${getHtmlRep(c.op2)}"
            //is TACCmd.Simple.AssignLOrCmd -> "${getHtmlRep(c.lhs)} = ${getHtmlRep(c.op1)} || ${getHtmlRep(c.op2)}"

            is TACCmd.Simple.AssigningCmd.ByteLoad -> "${getHtmlRepAnchor(c.lhs)} = ${getHtmlRep(c.base)}[${getHtmlRep(c.loc)}]".asRaw()
            is TACCmd.Simple.AssigningCmd.ByteStore -> "${getHtmlRepAnchor(c.base)}[${getHtmlRep(c.loc)}:${getHtmlRep(c.loc)}+32] = ${
                getHtmlRep(
                    c.value
                )
            }".asRaw()
            is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> "${getHtmlRepAnchor(c.base)}[${getHtmlRep(c.loc)}] = ${
                getHtmlRep(
                    c.value
                )
            } & 0xFF".asRaw()
            is TACCmd.Simple.AssigningCmd.WordLoad -> "${getHtmlRepAnchor(c.lhs)} = ${getHtmlRep(c.base)}[${getHtmlRep(c.loc)}]".asRaw()
            is TACCmd.Simple.WordStore -> "${getHtmlRepAnchor(c.base)}[${getHtmlRep(c.loc)}] = ${getHtmlRep(c.value)}".asRaw()
            is TACCmd.Simple.ByteLongCopy -> "${getHtmlRepAnchor(c.dstBase)}[${getHtmlRep(c.dstOffset)}:${getHtmlRep(c.dstOffset)}+${
                getHtmlRep(
                    c.length
                )
            }] = ${boldText("${c.srcBase}")}[${getHtmlRep(c.srcOffset)}, len ${getHtmlRep(c.length)}]".asRaw()
            is TACCmd.Simple.AssigningCmd -> error("this should have been caught by one of the above branches -- they should cover all subclasses of AssigningCmd")
            /** TODO add support for cvl [CERT-2387](https://certora.atlassian.net/browse/CERT-2387) **/
            is TACCmd.CVL -> "Do not (yet) supporting dumping cvl $c".asRaw()
        }
        val toRet = c.meta.map.values.mapNotNull {
            it as? PrintableCommandMeta
        }.takeIf {
            c is TACCmd.Simple && it.isNotEmpty()
        }?.let {
            val commentString = colorText(it.joinToString(prefix = "// ", separator = "; ") { m ->
                m.output(c as TACCmd.Simple)
            }, Color.GREEN)
            "$cmdHtml $commentString".asRaw()
        } ?: cmdHtml
        return toRet
    }

    /**
     * Checks whether there exists a block that 1) is called with [callId] and 2) contains a cmd that is
     * presented in the [unsatCore]. NB: the check is done based on [subAsts].
     */
    fun touchesUnsatCore(callId: CallId) =
        satisfiesCond(callId) { blk ->  blk.value.any { cmd -> cmd in unsatCore } }


    val callIdFullNames = this.callIdNames.map { callNodeLabel(it.key, it.value) to it.key }.toMap()

    /** If we don't cache here, there is a lot of redundancy because each call node's color is computed by recursively
     * looking at all the nodes in all its subgraphs. */
    private val anyOrAllInSubGraphCache = mutableMapOf<Triple<DotDigraph?, DotNodePredAggregator, DotNodePred>, Boolean>()

    /** Determines the color that a call node in a timeout tac dump should have. The color is computed based on the
     * nodes in the sub-graph that is associated with the call. Also used to color the entries in the call list. */
    fun callNodeTimeoutColor(
        callId: CallId,
        heuristicAnalysis: CountDifficultOps?,
        inTimeoutCore: (DotNode) -> Boolean = { node -> node.originalNodeId in timeoutCoreBlocks },
        inFullTimeoutSplit: (DotNode) -> Boolean = { node -> node.fillcolor.colors.first() == inFullTimeoutSplitColor.colors.first() },
        inNotYetProcessedSplit: (DotNode) -> Boolean = { node -> node.fillcolor == notYetProcessedSplitColor },
    ): DotColorList {
        fun anyOrAllInSubGraph(
            subGraph: DotDigraph?,
            agg: DotNodePredAggregator,
            f: (DotNode) -> Boolean
        ): Boolean = anyOrAllInSubGraphCache.computeIfAbsent(Triple(subGraph, agg, f)) {
            subGraph?.nodes?.agg { node ->
                if (node.id.id in callIdFullNames) {
                    val innerCallId = callIdFullNames[node.id.id]!!
                    val innerSubGraph = if (innerCallId == MAIN_GRAPH_ID) {
                        logger.warn { "not expecting to have inner callId being $MAIN_GRAPH_ID" }
                        null
                    } else {
                        subDots[innerCallId]
                    }
                    anyOrAllInSubGraph(innerSubGraph, agg, f)
                } else {
                    f(node)
                }
            } == true
        }

        val subGraph = if (callId == MAIN_GRAPH_ID) { dotMain } else { subDots[callId] }

        fun anyNodeInSubgraph(f: (DotNode) -> Boolean) = anyOrAllInSubGraph(subGraph, Iterable<DotNode>::any, f)
        fun allNodesInSubgraph(f: (DotNode) -> Boolean) = anyOrAllInSubGraph(subGraph, Iterable<DotNode>::all, f)

        val provenUnsat =
            allNodesInSubgraph { !inFullTimeoutSplit(it) && !inNotYetProcessedSplit(it) }

        val touchesTimeoutCore =
            anyNodeInSubgraph { inTimeoutCore(it) }

        val hasHeuristicallyDifficultNodes =
            anyNodeInSubgraph { heuristicAnalysis?.get(it.originalNodeId)?.isDifficult() == true }

        return if (provenUnsat) {
            provenUnsatColor
        } else if (hasHeuristicallyDifficultNodes && touchesTimeoutCore) {
            inTimeoutCoreAndHeuristicallyDifficultColor
        } else if (!hasHeuristicallyDifficultNodes && touchesTimeoutCore) {
            inTimeoutCoreAndNotHeuristicallyDifficultColor
        } else if (hasHeuristicallyDifficultNodes) {
            heuristicallyDifficultNotInTimeoutCoreColor
        } else if (subDots[callId]?.nodes?.any(inFullTimeoutSplit) == true) {
            inFullTimeoutSplitColor
        } else if (subDots[callId]?.nodes?.any(inNotYetProcessedSplit) == true) {
            notYetProcessedSplitColor
        } else {
            DotColorList(errorColor)
        }
    }



    fun satisfiesCond(callId: CallId, cond:(Map.Entry<NBId, List<TACCmd>>) -> Boolean): Boolean =
        subAsts[callId]?.code?.filter { it.key.calleeIdx == callId }?.any { blk ->
            cond(blk)
        } ?: false

    private fun cmdListToHTML(cmds: List<LTACCmdForAllTACS>, ast: TACProgram<*>, b: IBlockId): String {
        // we omit nop commands (when the code is too big, the unsat core is hard to see/read)
        val cmdsToRender = cmds.filter {
            when (it.cmd) {
                is TACCmd.Simple.NopCmd -> false
                else -> true
            }
        }.filter {
            when (it.cmd) {
                is TACCmd.Simple.AnnotationCmd -> when (it.cmd.annot.v) {
                    is SbfInlinedFuncNopAnnotation -> false
                    is SnippetCmd.SolanaSnippetCmd.Assert -> false
                    else -> true
                }
                else -> true
            }
        }

        val posWidth = cmds.map { it.ptr.pos }.max().toString().length

        val isInUnsatCore = cmdsToRender.map { it.cmd in unsatCore }
        /**
         * In the unsat core visualisation, we by default show only commands that are close to the unsat core
         * (within [closeUcDistance] lines), and hide the remaining lines.
         */
        val closeUcDistance = 2 //we show commands that are within 2 lines/cmds from a cmd from the unsat core
        fun isCloseToUnsatCore(cmdIndex: Int) =
            (max(0,cmdIndex-closeUcDistance)..min(cmdsToRender.size - 1, cmdIndex+closeUcDistance)).any {
                isInUnsatCore[it]
            }

        /**
         * Every block of hidden lines is replaced by a warning about lines being hidden.
         * Given a [cmdIndex] and list [shownCmds] of indices of shown commands, [showWarningAboutHiddenLines] determines
         * if we should show the warning at the lines of [cmdIndex].
         */
        val indexOfLastUcCmd = isInUnsatCore.lastIndexOf(true)
        val indexOfLastShownCloseToUcCmd = min(cmdsToRender.size - 1, ite(indexOfLastUcCmd == -1, -1,indexOfLastUcCmd + closeUcDistance))
        fun showWarningAboutHiddenLines(cmdIndex: Int, lastShownIndex : Int, isShownCmd: (Int) -> Boolean) =
            (!isShownCmd(cmdIndex) && cmdIndex + 1 < cmdsToRender.size && isShownCmd(cmdIndex + 1)) || //before a shown block
                cmdIndex == lastShownIndex + 1 //after the last shown block


        val cmdsAsHtml = cmdsToRender.mapIndexed { cmdIndex, ltacCmd ->
            val cmd = ltacCmd.cmd
            val CSSClasses = mutableListOf("tac-cmd")
            if (isInUnsatCore[cmdIndex]) {
                CSSClasses.add("cmd-in-uc")
            } else if (isCloseToUnsatCore(cmdIndex)) {
                CSSClasses.add("cmd-close-to-uc")
            }

            val cmdHtml = cmdToHtml(cmd, ast, b)
            val cmdValue = if (cexModel != null && cexModel.tacAssignments.isNotEmpty()) {
                cmdToValue(cmd)
            } else {
                null
            }
            val cmdExpectedValue = if (cexModel != null && cexModel.tacAssignments.isNotEmpty()) {
                cmdToExpectedValue(cmd)
            } else {
                ExpectedValue.NoAnswerButWeDontExpectIt
            }

            val fromSpec = if (cmd.meta.size > 0 && cmd.meta.map.values.any { m -> m is CVLExpToTACExprMeta }) {
                "<span style='background-color:$cmdFromSpecColor;'>SPEC</span>"
            } else {
                ""
            }
            @Suppress("AvoidProblematicStringFunctions")
            val fromSol = if (cmd.metaSrcInfo?.getSourceDetails() != null) {
                "<span style='background-color:$cmdFromSolidityColor;'>SOL</span>"
            } else ""

            val cmdWithPotentialValue: HTMLString =
                if (cmdValue != null) {
                    val valueStr = "(${cmdValue.asString})"
                    when (cmdExpectedValue) {
                        is ExpectedValue.Value ->
                            if (cmdExpectedValue.value != cmdValue.asBigIntOrNull()) {
                                val expectedStr = bigIntPretty(cmdExpectedValue.value) ?: cmdExpectedValue.value.toString()
                                "<span style='background-color:red;'>$cmdHtml $valueStr (ex. $expectedStr)</span>"
                            } else {
                                "$cmdHtml $valueStr"
                            }

                        is ExpectedValue.NoAnswerButWeDontExpectIt ->
                            if (cmd is TACCmd.Simple.AssertCmd) {
                                if (cmdValue.asBigIntOrNull() == BigInteger.ZERO) {
                                    "<span style='background-color:#ffcccc;'>$cmdHtml $valueStr</span>"
                                } else {
                                    val cmdHtmlStripped = if (cmdHtml is HTMLString.ColoredText) {
                                        cmdHtml.s.asRaw()
                                    } else {
                                        cmdHtml
                                    }
                                    "<span style='background-color:#ffcccc;color:green'>$cmdHtmlStripped $valueStr</span>"
                                }
                            } else {
                                "$cmdHtml $valueStr"
                            }

                        is ExpectedValue.Error ->
                            "<span style='background-color:red;'>$cmdHtml $valueStr ($cmdExpectedValue)</span>"

                        is ExpectedValue.Fail ->
                            "<span style='background-color:orange;'>$cmdHtml $valueStr ($cmdExpectedValue)</span>"
                    }.asRaw()
                } else {
                    if (cmd in unsatCoreDomain) { //assumed by the unsat core analysis
                        if (cmd in unsatCore) { //in the unsat core
                            "<span style='background-color:$inUnsatCoreColor;padding-left:5px;padding-right:5px;'>$cmdHtml $fromSpec $fromSol</span>"
                        } else {
                            "<span style='background-color:$notInUnsatCoreColor;padding-left:5px;padding-right:5px;'>$cmdHtml $fromSpec $fromSol</span>"
                        }.asRaw()
                    } else if ((ltacCmd.ptr.block in heuristicallyDifficultBlocks &&  // we only highlight the commands if the block is considered difficult (might be discussed)
                            ltacCmd.ptr in heuristicallyDifficultCommands) || ltacCmd.ptr in timeoutCore
                    ) {
                        val color = when {
                            ltacCmd.ptr in heuristicallyDifficultCommands && ltacCmd.ptr !in timeoutCore ->
                                heuristicallyDifficultNotInTimeoutCoreColor

                            ltacCmd.ptr !in heuristicallyDifficultCommands && ltacCmd.ptr in timeoutCore ->
                                inTimeoutCoreAndNotHeuristicallyDifficultColor

                            ltacCmd.ptr in heuristicallyDifficultCommands && ltacCmd.ptr in timeoutCore ->
                                inTimeoutCoreAndHeuristicallyDifficultColor

                            else ->
                                DotColorList(errorColor)
                        }

                        val backgroundStyle =
                            "background-image:linear-gradient(to right, ${color.asCommaSeparatedColorPair})"

                        ("<span style='${backgroundStyle};padding-left:5px;padding-right:5px;'>" +
                            "$cmdHtml $fromSpec $fromSol" +
                            "</span>").asRaw()
                    } else {
                        cmdHtml
                    }
                }

            fun tooltipTemplate(contents: String, tooltipTextId: Int): String {
                return "<span class=\"tooltip\">${contents} <span class=\"tooltiptext\" tooltipatt=\"$tooltipTextId\"></span></span>"
            }

            val r = cmd.metaSrcInfo
                ?.let { meta ->
                    val identifier = meta.sourceIdentifier()
                    toolTipCache.getOrElse(identifier) {
                        meta.getSourceDetails()?.let { sourceDetails ->
                            val currentId = ++toolTipCacheId
                            val entry = ToolTipEntry(sourceDetails, currentId)
                            toolTipCache.put(identifier, entry)
                            entry
                        }
                    }?.Id?.let { toolTipId -> tooltipTemplate(cmdWithPotentialValue.toString(), toolTipId) }
                } ?: cmdWithPotentialValue

            // Format the index of the command, padded with non-breaking spaces to right-justify it.
            val pos = colorText(ltacCmd.ptr.pos.toString().padStart(posWidth).replace(" ", "&nbsp;") + ":&nbsp;", Color.DARKGREY)
            val tacCmdLine = "<span id='tac-cmd-$b-$cmdIndex' data-block-id='$b' data-cmd-id='$cmdIndex' class='${CSSClasses.joinToString(" ")}'>$pos$r</span>"
            // Warning about skipped lines that are not in the unsat core or close to it
            val skippedLinesWarning = if(unsatCore.isEmpty()) {
                ""
            } else {
                if(showWarningAboutHiddenLines(cmdIndex, indexOfLastShownCloseToUcCmd, ::isCloseToUnsatCore) ) {
                    "<span class='close-skipped-lines-warning'>.....hidden lines.....</span>"
                } else {
                    ""
                } + if(showWarningAboutHiddenLines(cmdIndex, indexOfLastUcCmd) { isInUnsatCore[it] }) {
                    "<span class='skipped-lines-warning'>.....hidden lines.....</span>"
                } else {
                    ""
                }
            }

            skippedLinesWarning +tacCmdLine
        }

        val ucButtons = if (unsatCore.isEmpty()) {
            ""
        } else {
            // Unsat core visualisation buttons
            val showAllButton = "<button type=\"button\" onclick = \"showAllCmds.call(this)\" class=\"uc-all uc-toggle-button\">all</button>"
            val showUcOnlyButton = "<button type=\"button\" onclick = \"showUcCmdsOnly.call(this)\" class=\"uc-only uc-toggle-button\">uc only</button>"
            val showCloseToUcButton = "<button type=\"button\" onclick = \"showCmdsCloseToUc.call(this)\" class=\"uc-close uc-toggle-button\">close to uc</button>"
            "<span style='display:inline-block' class='uc-toggle-buttons'>$showAllButton $showUcOnlyButton $showCloseToUcButton</span><br/>\n"
        }
        val ucEnabledClass = ite(unsatCore.isNotEmpty(), " uc-enabled", "")
        /*
          we hardcode close-to-uc because that's the default display. But this will only ever do anything if we have uc-enabled,
          i.e., it is ignored if unsatCore is empty
         */
        return "<span class='cmds-list$ucEnabledClass close-to-uc'>$ucButtons${cmdsAsHtml.joinToString("<br/>\n")}</span>"
    }

    private fun isInSubsetToShow(b: IBlockId): Boolean {
        return showSubsetInCodeText.isEmpty() || showSubsetInCodeText.contains(b)
    }

    fun getPreviousMapping(): String {
        fun edgeHrefWithAnchor(b: NBId, prefix: String): String {
            return """
                <a name="${prefix}${b}" id="${prefix}${b}" href="#block${b}" onclick="highlightAnchor('${b}')">${b}</a>
            """
        }

        fun edgeHref(b: NBId): String {
            return """
                <a href="#block${b}" onclick="highlightAnchor('${b}')">${b}</a>
            """
        }

        return edges.filter { isInSubsetToShow(it.key.src) }
            .keys
            .groupBy { edge -> edge.trg }
            .map { trgWithEdges ->
                Pair(trgWithEdges.key, trgWithEdges.value.map { it.src })
            }
            .plus(Pair(StartBlock, listOf())) // add the first block
            .map { trgAndSrcs ->
                val trg = trgAndSrcs.first
                val srcs = trgAndSrcs.second

"""
${edgeHrefWithAnchor(trg, "edgeP")} <- ${srcs.map { edgeHref(it) }.joinToString(",")}
"""
            }.joinToString("\n<br/>\n")
    }

    data class LTACCmdForAllTACS(val ptr: CmdPointer, val cmd: TACCmd)

    fun blocksToHtml(ast: TACProgram<*>, id: String): String {

        // Sort the blocks so that they're be listed in topological, rather than arbitrary, order.
        // This code should never fail though, so if the ordering fails we'll survive with the arbitrary order.
        val sortedBlocks = ast.blockgraph.let { blocks ->
            topologicalOrderOrNull(blocks)?.reversed() ?: blocks.keys
        }.filter { isInSubsetToShow(it) } // running toposort on blockgraph may include previously filtered-out blocks if we filter early, so filter now
        val htmlOfBlocks = sortedBlocks.map { nbId ->
            val cmds = ast.code[nbId]!!
            // write the difficulty stats directly under the block number in the code, with a violet background
            val difficultyStats = countDifficultOps
                ?.get(nbId)
                ?.takeIf { it.isDifficult() }?.let {
"""
<span style='background-image:linear-gradient(to right, violet, violet);padding-left:5px;padding-right:5px;'>
    <i>- heuristically difficult - </i> stats:
        ${it.asText}
</span>
<br/>
"""
                }.orEmpty()

            val codeAsHtml = cmdListToHTML(cmds.mapIndexed { i, cmd -> LTACCmdForAllTACS(CmdPointer(nbId, i), cmd) }, ast, nbId)
"""
<a name="block${nbId}" id="block${nbId}" href="#block${nbId}" onclick="highlightAnchor('${nbId}')">Block ${nbId}:</a>
<br/>
$difficultyStats$codeAsHtml
<br/>
"""
        }

        val htmlOfEdges = edges
            .filter { it.key.src.calleeIdx == id.toInt() || it.key.trg.calleeIdx == id.toInt() } // only those pertaining to the call
            .filter { isInSubsetToShow(it.key.src) }
            .map { edgeEntry ->
                val keyEdge = edgeEntry.key
                val expressionAsHtml = edgeEntry.value.joinToString(",") {
                    it.toPrintRep { v -> v.toSMTRep() }
                }
                val exprValue = "" // not implemented

"""
Edge <a name="edgeS${keyEdge.src}" id="edgeS${keyEdge.src}" href="#block${keyEdge.src}" onclick="highlightAnchor('${keyEdge.src}')">${keyEdge.src}</a>
    -> <a name="edgeT${keyEdge.trg}" id="edgeT${keyEdge.trg}" href="#block${keyEdge.trg}" onclick="highlightAnchor('${keyEdge.trg}')">${keyEdge.trg}</a>:
$expressionAsHtml $exprValue
"""
            }

        val divOfBlocks =
"""
<div id="blocksHtml${id}" style="overflow: scroll; height: 90%;">${htmlOfBlocks.joinToString("</br>")}</div>
<div id="edgesHtml${id}" style="overflow: scroll; height: 10%;">Successor map:<br/>${htmlOfEdges.joinToString("</br>")}</div>
"""

        return divOfBlocks
    }

    fun blocksToHtml(): String {
        return blocksToHtml(ast, "0")
    }

    fun getBlocksHtml(): String {
        return blocksToHtml()
    }

    companion object {
        // when our color computation failed somehow but we don't want to crash
        val errorColor = DotColor.purple

        // a single DotColor, used in gradients
        val heuristicallyDifficultColor = DotColor.violet
        private val timeoutCoreColor = DotColor.orangered

        // colors used to highlight which nodes were solved/not solved by cfg splits
        val heuristicallyDifficultNotInTimeoutCoreColor = DotColorList(DotColor.orange, heuristicallyDifficultColor)
        val inFullTimeoutSplitColor = DotColorList(DotColor.orange)
        val notYetProcessedSplitColor = DotColorList(DotColor.gold)
        val provenUnsatColor = DotColorList(DotColor.green)
        val inTimeoutCoreAndNotHeuristicallyDifficultColor = DotColorList(timeoutCoreColor)
        val inTimeoutCoreAndHeuristicallyDifficultColor = DotColorList(timeoutCoreColor, heuristicallyDifficultColor)

        // Colors used to highlight the unsat core
        val inUnsatCoreDotColor = DotColor.chartreuse3
        val notInUnsatCoreDotColor = DotColor.indianred2
        const val inUnsatCoreColor = "#66cd00" /** [DotColor.chartreuse3] bg color of commands in the unsat core **/
        const val notInUnsatCoreColor = "#ee6363" /** [DotColor.gold1] bg color of commands not in the unsat core **/
        const val cmdFromSolidityColor = "#ff53e9" //color of the "a command from solidity info box"
        const val cmdFromSpecColor = "#ff53e9" //color of the "a command from CVL info box"
    }
}

private typealias DotNodePred = (DotNode) -> Boolean
/** In practice, this is `Iterable<DotNode>::any` or `Iterable<DotNode>::all` */
private typealias DotNodePredAggregator = (Iterable<DotNode>.(DotNodePred) -> Boolean)

fun getCallIdToCaller(g: BlockGraph): Map<CallId,CallId> {
    val topoOrder = topologicalOrderOrNull(g)
    // if we are loop-free, we can do something more precise
    if (topoOrder != null) {
        // project into the callIds, omitting self edges
        val callGraph = g.entries
            .map { (n, succs) ->
                n.calleeIdx `to?`
                    succs
                        .filter { it.calleeIdx != n.calleeIdx } // filter non-func crossing
                        .mapToSet { it.calleeIdx } // take distinct callee indices
                        .takeIf { it.size <= 1 } // at most one call
            }
            .let { entr ->
                if (entr.any { it == null }) {
                    // we have an edge calling multiple calls or returning to multiple callers
                    logger.warn { "Bad graph - has multiple callees per site or multiple returned-to methods" }
                    return mapOf()
                } else {
                    entr
                        .mapNotNull { it }
                        .mapNotNull { (n, succ) -> n `to?` succ.firstOrNull() } // take those that actually call/return
                }
            }
            .groupBy { it.first }
            .mapValues {
                // get all the destinations (callers may have multiple)
                it.value.mapToSet { it.second }
            }

        val callIdToCallerCallId = mutableMapOf<CallId, CallId>()

        // toposort projected to callids
        val projected = topoOrder.map { it.calleeIdx }
        // every s->t in callgraph where s is before t in projected toposort means s calls t
        callGraph.forEach { (s, succs) ->
            succs.forEach { t ->
                if (projected.lastIndexOf(s) > projected.lastIndexOf(t)) { // the toposort result is ordered from sink to source (!)
                    callIdToCallerCallId[t] = s
                }
            }
        }

        return callIdToCallerCallId
    } else {
        // if we're not loop free, we rely on a key dirty fact -
        // the inliner should put the root of the callees at 0, the same origStartPc we computed in the
        // decompiler
        val callIdToCallerCallId = mutableMapOf<CallId, CallId>()
        g.forEach { n, succs ->
            if (succs.size == 1) {
                val succ = succs.single()
                if (succ.origStartPc == StartBlock.origStartPc && succ.calleeIdx != n.calleeIdx) {
                    callIdToCallerCallId[succ.calleeIdx] = n.calleeIdx
                }
            }
        }

        return callIdToCallerCallId
    }
}

data class DecomposedCode(
    val subPrograms: Map<CallId, CoreTACProgram>,
    val callGraphInfo: CallGraphInfo,
    val wholeProgWithInternalFunctions: CoreTACProgram, // with int functions if [AddInternalFunctions] is in use, otherwise the original program
    val internalFunctionsBlockMapping: CmdPtrMapping,
)

data class CallGraphInfo(
    val callIdToCallerId: Map<CallId, CallId>,
    val callIdToName: Map<CallId, String>,
    val procIdToSourceLocation: Map<ProcedureId, TreeViewLocation>,
    val callIdToSourceLocation: Map<CallId, TreeViewLocation>,
) {
    val callGraph: MultiMap<CallId, CallId> get() =
        callIdToCallerId.keys.groupBy { callIdToCallerId[it]!!  }.mapValues { (_, v) -> v.toSet() }

    fun getCallIdWithName(callId: CallId) =
        CallIdWithName(callId, callIdToName[callId].orEmpty())

    companion object {
        val Empty = CallGraphInfo(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}



/** Used to keep the function call identifiers (e.g. "3: RebaseWrapper.add" without spaces internally). (because dot
 * can't handle identifiers with spaces _and_ gradients at the same time ..) */
fun callNodeLabel(callId: CallId, baseLabel: String) = "$callId: $baseLabel"


fun decomposeCodeToCalls(code1: CoreTACProgram, addInternalFunctions: Boolean): DecomposedCode {
    val (code, internalFunctionsBlockMapping) =
        runIf(Config.InternalFunctionsInTACReports.get() && addInternalFunctions) { addInternalFunctionIdxsDontThrow(code1) }
            ?: AddInternalFunctions.TACProgWithIntFuncs.unchanged(code1)

    val callIdsToBlocks = code.code.keys.groupBy { it.calleeIdx }

    val callIdNames = mutableMapOf(Pair(MAIN_GRAPH_ID, "main"))

    val callIdToCallerId = getCallIdToCaller(code.blockgraph)

    val allCodesDecomposed = callIdsToBlocks.mapValues { callIdAndBlocks ->
        val subBlocks = code.code.filter { it.key.calleeIdx == callIdAndBlocks.key }.toMutableMap()
        val subGraph = code.blockgraph.filter { it.key.calleeIdx == callIdAndBlocks.key }
            .mapValues {
                it.value.retainAll { next -> next.calleeIdx == callIdAndBlocks.key }
            } // leave only internal edges
            .toMap(MutableBlockGraph())


        // need to replace call edges with a special node
        // a call edges leads to start (0) of another call id (bigger than mine?)
        val callEdges = code.blockgraph
            .filter { it.key.calleeIdx == callIdAndBlocks.key }
            .filter { it.value.any { callTrgCandidate -> callIdToCallerId[callTrgCandidate.calleeIdx] == callIdAndBlocks.key }}
            .flatMap { (caller, callees) ->
                callees.filter { callTrgCandidate -> callIdToCallerId[callTrgCandidate.calleeIdx] == callIdAndBlocks.key }
                    .let { callTargets ->
                        callTargets.map { callTarget -> Edge(caller, callTarget) }
                    }
            }

        val callPlusReturnEdges = callEdges.map { callEdge ->
            Pair(callEdge, code.blockgraph
                .filter { it.key.calleeIdx == callEdge.trg.calleeIdx } // return edge starts in target call id
                .filter { it.value.size == 1 && it.value.first().calleeIdx == callEdge.src.calleeIdx && it.value.first() != callEdge.src } // return edge ends in source call id and it is not the source itself
                .map { Edge(it.key, it.value.first()) })
        }
        logger.info { "Call edges and their return edges in ${code.name}: $callPlusReturnEdges" }

        fun replaceInLabel(label: String): String {
            return label.replace("Invoke ", "").replaceAfterLast(":", "").substringBeforeLast(":").trim()
        }

        callPlusReturnEdges.map {
            val callEdge = it.first

            val nbid = callEdge.trg
            val returnEdges = it.second.map { returnEdge -> returnEdge.copy(src = nbid) }.toSet()
            if (returnEdges.size != 1) {
                logger.warn { "Rewritten return edges should converge to a single edge from $nbid but got $returnEdges - trying anyway" }
            }

            val returnTargets = returnEdges.mapToTreapSet { it.trg }

            val lastLabelInSrc = code.code[callEdge.src]?.filterIsInstance<TACCmd.Simple.LabelCmd>()?.lastOrNull()


            // if lastLabelInSrc was alone in the block in which it was found, then it came from CVL and can be skipped
            if (lastLabelInSrc != null && code.code[callEdge.src]!!.size == 1) {
                callIdNames[nbid.calleeIdx] = replaceInLabel(lastLabelInSrc._msg)
                subGraph.put(callEdge.src, subGraph[callEdge.src]!!.plus(returnTargets))
            } else {
                val label = code.procedures.find { it.callId == nbid.calleeIdx }?.procedureId?.toString()
                    ?: "Call ${nbid.calleeIdx}"
                callIdNames[nbid.calleeIdx] = label
                val newNode = TACCmd.Simple.LabelCmd(
                    callNodeLabel(nbid.calleeIdx, label)
                )

                subBlocks[nbid] = listOf(newNode)
                subGraph[callEdge.src] = subGraph[callEdge.src]!!.plus(callEdge.trg)

                subGraph.put(nbid, returnTargets)
            }
        }

        // replace all jumps in leaf nodes with labels
        subGraph.filter { it.value.isEmpty() }.forEach { leaf ->
            val leafCode = subBlocks[leaf.key] ?: error("No code for leaf $leaf")
            val last = leafCode.lastOrNull() ?: return@forEach
            val newLeafCode = when (last) {
                is TACCmd.Simple.JumpCmd -> leafCode.dropLast(1).plus(TACCmd.Simple.LabelCmd("Jump to ${last.dst}"))
                is TACCmd.Simple.JumpiCmd -> leafCode.dropLast(1)
                    .plus(TACCmd.Simple.LabelCmd("Jump to ${last.dst} if ${last.cond} else to ${last.elseDst}"))
                else -> leafCode
            }
            subBlocks[leaf.key] = newLeafCode
        }

        code.copy(
            code = subBlocks,
            blockgraph = subGraph,
            name = code.name + "_${callIdAndBlocks.key}",
            symbolTable = TACSymbolTable.empty(), // unused by CodeMap
            check = false
        )

    }

    val callIdToProcedureId = code.procedures.associate { it.callId to it.procedureId }

    val procedureToSourceLocation = code.procedures.associateNotNull { p -> p.procedureId.range?.let{ p.procedureId to it }}

    // fills up the names that couldn't be determined with just the call number
    callIdsToBlocks.keys.forEach { id ->
        if (!callIdNames.containsKey(id)) {
            callIdNames[id] = "$id"
        }
    }
    return DecomposedCode(
        subPrograms = allCodesDecomposed,
        callGraphInfo = CallGraphInfo(
            callIdToCallerId,
            callIdNames,
            procedureToSourceLocation, // my guess (alex n): this should be updated to make JTS better
            callIdToProcedureId.mapValuesNotNull { (_, v) -> procedureToSourceLocation[v] },
        ),
        wholeProgWithInternalFunctions = code,
        internalFunctionsBlockMapping = internalFunctionsBlockMapping,
    )
}

private fun generateDot(code: TACProgram<*>, name: String): DotDigraph = convertProgramGraphToDot(code, name)

private fun generateSVG(dotDigraph: DotDigraph): String {
    return if (IsGenerateGraphs.get() && dotDigraph.withinGraphvizLimits()) {
        renderGraphToSVGString(dotDigraph.name, dotDigraph)
    } else {
        "Not available - enable graph generation"
    }
}

fun generateSVGWithId(dotDigraph: DotDigraph, id: CallId): String =
    addAnchorsToSVGGraphAndWrapInDiv(generateSVG(dotDigraph), id)


/**
 * see other [generateCodeMap] for comments
 */
fun generateCodeMap(
    ast: TACProgram<*>,
    name: String,
    edges: Map<Edge, List<TACExpr>> = mapOf(),
    addInternalFunctions: Boolean = true
): CodeMap = when (ast) {
    is CoreTACProgram -> generateCodeMap(ast, name, addInternalFunctions, edges)
    is EVMTACProgram,
    is CanonicalTACProgram<*, *>,
    is CVLTACProgram,
    is move.MoveTACProgram -> degenerateCodeMap(ast, name, edges)
    else -> throw Exception("Unexpected code $ast")
}


const val MAIN_GRAPH_ID = NBId.ROOT_CALL_ID

/**
 * @param ast the program to be visualized
 * @param name the name to give the resulting [CodeMap]
 * @param edges allows to give additional edges that are not in the program ([ast])
 */
fun generateCodeMap(ast: CoreTACProgram, name: String, addInternalFunctions: Boolean, edges: Map<Edge, List<TACExpr>> = mapOf()): CodeMap {
    val (sub, callGraphInfo, withInternalFunctions, internalFunctionsBlockMapping) = decomposeCodeToCalls(ast, addInternalFunctions) // xxx name of "full original" maybe move the internal functions transformation or so
    val (callIdNames, _) = callGraphInfo

    if (MAIN_GRAPH_ID !in sub) {
        logger.warn {
            if (sub.isEmpty()) {
                "Expected decomposition of code to yield at least one code, but got none (call id names: $callIdNames). Code is: $ast"
            } else {
                "Could not find id $MAIN_GRAPH_ID in submap of code, got ${sub.keys}"
            }
        }
    }
    val main = sub[MAIN_GRAPH_ID] ?: ast

    val dotGraph = generateDot(main, name + "_main")

    val subDots = sub.minus(MAIN_GRAPH_ID).mapValues {
        generateDot(it.value, name + "${it.key}")
    }

    // ... we wanted all the edges between calls here
    val additionalEdges = sub.asSequence().flatMap { callIdWithCode ->
        callIdWithCode.value.blockgraph.asSequence()
            .flatMap { (src, successors) -> successors.asSequence().map { succ -> Edge(src, succ) } }
    }.filter { it !in edges.keys }


    return CodeMap(
        name = name,
        ast = main,
        subAsts = sub,
        callGraphInfo = callGraphInfo,
        edges = edges.plus(additionalEdges.map { it to listOf() }),
        cexModel = null,
        fullOriginal = ast,
        withInternalFunctions = withInternalFunctions,
        intFuncsCmdPtrMapping = internalFunctionsBlockMapping,
        dotMain = dotGraph,
        subDots = subDots
    )
}


private fun degenerateCodeMap(
    code: TACProgram<*>,
    name: String,
    edges: Map<Edge, List<TACExpr>> = mapOf()
) = CodeMap(
    name = name,
    ast = code,
    subAsts = mapOf(),
    callGraphInfo = CallGraphInfo.Empty,
    edges = edges,
    fullOriginal = code,
    withInternalFunctions = code,
    intFuncsCmdPtrMapping = CmdPtrMapping(code.code),
    dotMain = generateDot(code, name),
    subDots = mapOf()
)


/*
 * The fascinating thing about this is that it works both ways. So if you have an assignment where the lhs is knonwn and rhs is unknonwn, it's completely ok to fill in for rhs!
 *
 * Q (alex): what's the exact purpose of this method?..
 * A (Or) : computes values of expressions (which aren't explicit in the mode) from the model
 *
 */
fun extendModel(
    model: SMTCounterexampleModel,
    program: TACProgram<*>,
    reachableBadblocks: Set<NBId>
): SMTCounterexampleModel {
    fun evalConst(c: TACSymbol.Const): TACValue? {
        return when (c.tag) {
            Tag.Int,
            Tag.Bit256 -> TACValue.PrimitiveValue.Integer(c.value) // Q: do we need to preserve the tag here?
            Tag.Bool -> TACValue.PrimitiveValue.Bool(c.value)
            else -> null
        }
    }

    fun evalConst(c: BigInteger, tag: Tag): TACValue? =
        evalConst(TACSymbol.Const(c, tag))


    fun evalSymbol(s: TACSymbol, model: Map<TACSymbol.Var, TACValue>): TACValue? =
        when (s) {
            is TACSymbol.Const -> evalConst(s)
            is TACSymbol.Var -> model[s]
        }

    fun evalByModel(s: TACSymbol, model: Map<TACSymbol.Var, TACValue>): TACSymbol {
        fun failedToEval(value: Any): TACSymbol {
            logger.info { "can't evaluate \"$value\"" }
            return s
        }
        return when (s) {
            is TACSymbol.Const -> s
            is TACSymbol.Var -> {
                model[s]?.let { value ->
                    when (value) {
                        is ConcreteTacValue ->
                            when (val constExp = value.asConstantTacExpr()) {
                                is TACExpr.Sym.Const ->
                                    if (s.tag is Tag.Bits) {
                                        constExp.s.value.asTACSymbol(s.tag)
                                    } else {
                                        constExp.s
                                    }
                                is TACExpr.Sym -> constExp.s
                                else -> failedToEval(value)
                            }
                        else -> {
                            failedToEval(value)
                        }
                    }
                } ?: failedToEval(s)
            }
        }
    }

    fun evalExpr(e: TACExpr, model: Map<TACSymbol.Var, TACValue>): TACValue? {
        val symbolAssigner = object : DefaultTACExprTransformer() {
            override fun transformSym(exp: TACExpr.Sym): TACExpr {
                return when {
                    exp is TACExpr.Sym.Var -> evalByModel(exp.s, model).asSym()
                    exp is TACExpr.Sym.Const -> exp
                    else -> error("this when-case should be unreachable (in CodeMap.evalExpr)")
                }
            }
        }

        val assignKnown = symbolAssigner.transform(e)
        return when (assignKnown) {
            is TACExpr.Vec -> {
                if (assignKnown.ls.all { it is TACExpr.Sym.Const }) {
                    val value = assignKnown.eval(assignKnown.ls.map { it.getAsConst()!! })
                    evalConst(value, assignKnown.tagAssumeChecked)
                } else {
                    null
                }
            }
            is TACExpr.UnaryExp, is TACExpr.TernaryExp,
            is TACExpr.BinOp, is TACExpr.BinRel, is TACExpr.BinBoolOp -> {
                assignKnown.evalAsConst()?.let {
                    evalConst(it, assignKnown.tagAssumeChecked)
                }
            }
            is TACExpr.Sym -> {
                evalSymbol(assignKnown.s, model)
            }
            else -> null
        }
    }

    fun isReachable(b: NBId, model: MutableMap<TACSymbol.Var, TACValue>): Boolean? {
        val reachabilityVar = LeinoWP.genReachabilityVar(b)
        return (evalSymbol(reachabilityVar, model) as? TACValue.PrimitiveValue.Bool)?.value
    }

    fun getNewAssignmentsFromCmd(c: TACCmd.Simple, model: MutableMap<TACSymbol.Var, TACValue>) {
        val lhs = c.getLhs() ?: return // no lhs, nothing to update

        val fillForRhs = if (lhs in model) {
            logger.info {
                "There's value for ${lhs.toSMTRep()} (${
                    evalSymbol(
                        lhs,
                        model
                    )
                }) - trying to evaluate rhs in $c"
            }
            true
        } else {
            logger.info { "No value for ${lhs.toSMTRep()} - trying to find in $c" }
            false
        }

        when (c) {
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                if (!fillForRhs) {
                    evalExpr(c.rhs, model).let { evaldSymbol ->
                        if (evaldSymbol != null) {
                            logger.info { "Adding evald expr to extended model ${lhs.toSMTRep()} = ${evaldSymbol}" }
                            model[lhs] = evaldSymbol
                        }
                    }
                } else {
                    val v = evalSymbol(c.lhs, model)
                    if (v != null) { // this strongly relies on the fact that when going over reachable nodes, each variable is assigned once...
                        when (c.rhs) {
                            is TACExpr.Sym.Var -> {
                                val rhsV = evalSymbol(c.rhs.s, model)
                                // we want to update rhs if rhsV does not exist, or if it does not agree with rhs's tag
                                // right now will just update it if it's null
                                if (rhsV == null) {
                                    logger.info { "Adding evald expr to extended model for ${c.rhs}: $v" }
                                    model[c.rhs.s] = v
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> { // does this even happen? aren't we in TACSimpleSimple world?
                throw UnsupportedOperationException("implement this case (CodeMap -> AssignSimpleSha3)")
            }
            else -> {}
        }
    }

    if (program !is CoreTACProgram) {
        logger.warn { "Only Core TAC programs are expected to be used to extend a counterexample model." }
        return model
    }
    // traverse the program in topological order and add missing parts
    if (program.code.isEmpty()) {
        logger.warn { "Program is empty" }
        return model
    }

    val extendedAssignments = model.tacAssignments.toMutableMap()

    // Fix correct assignments to ReachVars that are missing from the model (due to optimizations)
    program.analysisCache.graph.blocks.forEach { block ->
        val eval: Boolean? = model.getAssignmentOfMissingReachVar(block.id)
        if (eval != null) {
            val reachVarForBlock = LeinoWP.genReachabilityVar(block.id)
            extendedAssignments[reachVarForBlock] =
                TACValue.valueOf(eval)
        }
    }

    // Add assignments to OK variables that are missing from the model (due to optimizations)
    extendedAssignments.putAll(model.getAssignmentsToMissingOKVars())

    // The order of iteration does not matter since the program is passified.
    // We need to collect assignments until we no can longer learn
    // new ones
    val assignmentsToLearn = program.blockgraph.keys.filter { b -> isReachable(b, extendedAssignments) ?: false }
        .flatMap { b -> program.code[b]!!.filter { it is TACCmd.Simple.AssigningCmd } }

    var changed = true
    while (changed) {
        changed = false
        val sz = extendedAssignments.size
        assignmentsToLearn.forEach { cmd ->
            getNewAssignmentsFromCmd(cmd, extendedAssignments)
        }
        if (extendedAssignments.size > sz) {
            changed = true
        }
    }

    return SMTCounterexampleModel(extendedAssignments, reachableBadblocks, havocedVariables = program.getHavocedSymbols())

}

/**
 * Add [unsatCore] to the [codeMap] and also modifies the underlying dot Graphs so that they visualise the unsat core.
 * In particular:
 * The unsat core analysis assumes (i.e. tries to remove) only i] variable assignments, ii] assumes, and iii] asserts.
 * If assume or assert is missing in the unsat core, it means we can remove it from the program. If a variable
 * assignment is missing, it means that we can havoc the variable. The unsat core should be minimal, i.e., it shows
 * a minimal subset of statements in the program that are needed to prove the rule.
 *
 * How to read the output HTML:
 * Code on left: Commands with yellow background can be removed/havoced (i.e. are not in the unsat core).
 * Commands with green background have to stay. Commands with white background are not assumed in the analysis
 * (e.g. havocs and branching) and have to stay.
 * Call graph in the middle: There are two types of nodes: i] block nodes and ii] call nodes. Block nodes are either
 * green or yellow depending on whether they contain a command from the unsat core or not. Call nodes are green if
 * the callee contains a command from the unsat core (this is a transitive relation, i.e., if we have A -> B -> C and
 * C contains a command from the unsat core, then A B and C are all green).
 * List of asts (dot graphs) on right: Green means the ast contains a command from the unsat core; otherwise it is
 * yellow.
 */
fun addUnsatCoreData(unsatCore: List<TACCmd>, unsatCoreDomain: List<TACCmd>, codeMap: CodeMap, name: String): CodeMap {

    if(unsatCore.isEmpty()) {
        logger.warn { "The unsatCore should not be empty, returning the original (non-unsat-core) codemap ${codeMap.name}." }
        return codeMap
    }

    /**
     * Names of blocks that contain at least a single command from the unsat core. We keep just the string names here
     * because later we compare it with (names of) dot nodes.
     */
    val blocksTouchingUnsatCore = codeMap.subAsts.map {
        it.value.code.filter { (_, cmds) ->
            cmds.any { cmd -> cmd in unsatCore }
        }.map { (key, _) -> key.toString() }
    }.flatten()

    if(blocksTouchingUnsatCore.isEmpty()) {
        logger.warn { "At least one block should be touching the unsat core when adding the unsat core data to the codemap ${codeMap.name}." }
    }

    /**
     * Some nodes in a digraph are call nodes (instead of block nodes), i.e., virtual edges to other digraphs.
     * We color these "call nodes" in the dot as touching the unsat core iff the callee touches the unsat core
     * (possibly just transitively). We store the touching callIds in [callIdsTouchingUnsatCoreTransitive].
     */

    val callIdToCallerId = getCallIdToCaller(codeMap.ast.blockgraph)
    fun callTargetsOf(callId: CallId) = mutableSetOf<NBId>().let{ s ->
        codeMap.withInternalFunctions.blockgraph.filter { it.key.calleeIdx == callId }.flatMapTo(s) { node ->
            node.value.filter { callTrgCandidate -> callIdToCallerId[callTrgCandidate.calleeIdx] == callId }
        }
    }

    fun touchesUnsatCore(callId: CallId) = codeMap.satisfiesCond(callId) { blk -> blk.key.toString() in blocksTouchingUnsatCore }

    val callIdsTouchingUnsatCoreTransitive = mutableSetOf<CallId>()
    //We assume the blockgraph is a DAG and also that the callIds respects the DFS post-order. Hence, we just process
    //the callids from bigger to smaller ones and cumulate the fact whether a callId touches the unsat core.
    codeMap.callIdNames.keys.sortedDescending().forEach { callerId ->
        if (touchesUnsatCore(callerId)) {
            callIdsTouchingUnsatCoreTransitive.add(callerId)
        } else if (callTargetsOf(callerId).any { trg ->  trg.calleeIdx in callIdsTouchingUnsatCoreTransitive }) {
            callIdsTouchingUnsatCoreTransitive.add(callerId)
        }
    }

    /** depends on the naming scheme [callNodeLabel] used within [decomposeCodeToCalls] **/
    val callIdFullNames = codeMap.callIdFullNames

    /**
     * We assume two types of dot nodes here: block nodes and "call" nodes. In the former case, we highlight the
     * block node iff it contains a cmd from the unsat core. In the caller case, we highlight the call node iff
     * it calls a block that either directly touches the unsat core or reaches a block that touches the unsat core.
     */
    fun colorDot(dot: DotDigraph): DotDigraph {
        return dot.copy(nodes = dot.nodes
            .map { n ->
                // should really change to using the original node id
                val touchesUnsatCore = if (n.id.id in callIdFullNames) {
                    val callId = callIdFullNames[n.id.id]
                    callId in callIdsTouchingUnsatCoreTransitive
                } else {
                    n.id.id in blocksTouchingUnsatCore
                }

                val color = if (touchesUnsatCore) {
                    CodeMap.inUnsatCoreDotColor
                } else {
                    CodeMap.notInUnsatCoreDotColor
                }
                n.copy(fillcolor = DotColorList(color), styles = listOf(DotStyle.filled))
            }
        )
    }

    return codeMap.copy(
        unsatCore = unsatCore,
        unsatCoreDomain = unsatCoreDomain,
        name = name,
        dotMain = colorDot(codeMap.dotMain).copy(name = "UnsatCore" + codeMap.dotMain.name),
        subDots = codeMap.subDots.mapValues { colorDot(it.value).copy(name = "UnsatCore" + it.value.name) },
    )
}

fun addCounterexampleData(cexModel: SMTCounterexampleModel, codeMap: CodeMap): CodeMap {
    // first extend. Take the original graph without decomposition
    val extendedCexModel = extendModel(cexModel, codeMap.withInternalFunctions, emptySet())

    val reachableBlocks = codeMap.translateWithInternalFuncs(extendedCexModel.reachableNBIds)
    val badBlocks = codeMap.translateWithInternalFuncs(extendedCexModel.badNBIds)
    val reachableBadBlocks =
        (badBlocks intersect reachableBlocks)
    val reachableBadBlocksAndMessages = reachableBadBlocks
        .flatMap { nbId ->
            codeMap.ast.code[nbId].let { cmds ->
                if (cmds != null && cmds.size == 1 && cmds.first() is TACCmd.Simple.LabelCmd) {
                    listOf(nbId.toString(), (cmds.first() as TACCmd.Simple.LabelCmd)._msg)
                } else {
                    listOf(nbId.toString())
                }
            }
        }

    logger.info { "Reachable blocks: $reachableBlocks" }
    logger.info { "Bad blocks: $badBlocks" }
    logger.info { "Normalized reachable bad blocks: $reachableBadBlocksAndMessages" }
    extendedCexModel.setReachableBadBlocks(reachableBadBlocks)
    cexModel.setReachableBadBlocks(reachableBadBlocks)
    val mainDotMarked =
        markBadBlocksInDot(codeMap.dotMain, reachableBadBlocks, codeMap.edges, extendedCexModel)
    val subDotsMarked = codeMap.subDots.mapValues {
        markBadBlocksInDot(
            it.value,
            reachableBadBlocks,
            codeMap.edges,
            extendedCexModel
        )
    }

    val codemap = codeMap.copy(
        dotMain = mainDotMarked,
        subDots = subDotsMarked,
        cexModel = extendedCexModel
    )
    codemap.showSubsetInCodeText.addAll(codeMap.translateWithInternalFuncs(extendedCexModel.reachableNBIds))

    return codemap

}

fun addCounterexampleData(cexModel: CounterexampleModel, codeMap: CodeMap): CodeMap =
    when(cexModel) {
        is SMTCounterexampleModel -> addCounterexampleData(cexModel, codeMap)
        is CounterexampleModel.Empty -> codeMap
    }

fun addDifficultyInfo(codeMap: CodeMap, results: Verifier.JoinedResult): CodeMap {
    val difficultyInfo: ProcessDifficultiesResult = results.difficultyInfo ?: return codeMap

    logger.trace { "Got difficulty information $difficultyInfo" }

    // we scale difficulties because it comes up in the numerator, and if it's small, divided by deltaScale we get truncation
    // e.g., if difficulties are 0-7, and here the range of colors is of size 6, then deltaScale is 1.
    // It means that max difficulty maps to color 7, beyond the range of colors.
    // if we scale all difficulties by 1000, then deltaScale is 1166, and max difficulty maps to normalized 6, which is correct max.
    fun BigInteger.scale() = this.multiply(BigInteger.valueOf(1000))

    val lightest = DotColor.plum1
    val darkest = DotColor.purple4
    val minDifficulty = difficultyInfo.minDifficulty()?.scale() ?: BigInteger.ZERO
    val maxDifficulty = difficultyInfo.maxDifficulty()?.scale() ?: BigInteger.ZERO
    val deltaScale = (maxDifficulty - minDifficulty).divide((darkest.ordinal-lightest.ordinal).toBigInteger())
    fun computeColor(nodeId: String): DotColor {
        if (deltaScale == BigInteger.ZERO) {
            return DotColor.purple1
        }
        val difficulty = difficultyInfo.getBlockDifficulty(nodeId)?.scale()
        return if (difficulty == null) {
            DotColor.white
        } else {
            val normalized = ((difficulty - minDifficulty)/deltaScale).toInt()
            DotColor.entries.find { it.ordinal == lightest.ordinal + normalized }
                ?: throw IllegalStateException("Given $difficulty in range [${minDifficulty},${maxDifficulty}] and scale ${deltaScale}, " +
                        "normalized value should have been $normalized and be between ${lightest.ordinal} and ${darkest.ordinal}")
        }
    }
    fun updateDifficulty(dot: DotDigraph): DotDigraph {
        return dot.copy(nodes = dot.nodes
            .map { n ->
                n.copy(fillcolor = DotColorList(computeColor(n.id.id)), styles = listOf(DotStyle.filled))
            }
        )
    }
    return codeMap.copy(
        dotMain = updateDifficulty(codeMap.dotMain),
        subDots = codeMap.subDots.mapValues { updateDifficulty(it.value) }
    )
}

private fun TACCmd.Simple.AnnotationCmd.eventId(): Int? {
    val (k, v) = this.annot
    return when {
        k == CVL_LABEL_START -> this.meta[CVL_LABEL_START_ID]
        v is SnippetCmd.CVLSnippetCmd.EventID -> v.id
        else -> null
    }
}

fun String.shortRustName(): String =
    if (this.length <= 80) { this } else { this.substringAfterLast("::") }

fun String.shortRustForHTML(): String = this.shortRustName().escapeHTML()
