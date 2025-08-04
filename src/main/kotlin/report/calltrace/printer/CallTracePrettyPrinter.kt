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

package report.calltrace.printer

import analysis.LTACCmd
import datastructures.stdcollections.*
import log.*
import report.calltrace.CallInstance
import report.calltrace.CalldataMovement
import report.calltrace.ReturndataMovement
import solver.CounterexampleModel
import tac.Tag
import utils.*
import vc.data.SnippetCmd
import vc.data.TACMeta
import vc.data.TACSymbol
import java.io.Closeable
import java.io.FileWriter


private val logger = Logger(LoggerTypes.CALLTRACE)

private const val indentUnit = 4

/**
 * @param printToStdOut print to the command line (very harshly, via println), in addition to printing to the file
 * @param printModel print the model (the tacAssignment)
 */
class CallTracePrettyPrinter(
        ruleName: String,
        val model: CounterexampleModel,
        val printToStdOut: Boolean = false,
        val printModel: Boolean = true,
    ) : Closeable {
    private var currentIndent: Int = 0

    /** can't call this `indent due to name clash */
    private fun String.indnt(i: Int) = " ".repeat(i) + this

    private val filename = "ctpp_$ruleName.txt"

    /** debugger configuration (start) */

    /** when printing the model, display only symbols that this filter allows */
    @Suppress("ForbiddenMethodCall")
    private fun symFilter(sym: TACSymbol.Var) = TACMeta.CVL_VAR in sym.meta && !sym.smtRep.startsWith("last")

    /** debugger configuration (end) */


    private val writer =
        if (ArtifactManagerFactory.isEnabled()) {
            WriteToFile(filename)
        } else {
            null
        }

    init {
        writer?.write("Calltrace Debug Information\n")
    }

    private val cvlMetas =
        listOf(
            TACMeta.CVL_VAR,
            TACMeta.CVL_DISPLAY_NAME,
            TACMeta.CVL_EXP,
            TACMeta.CVL_GHOST,
            TACMeta.CVL_TYPE,
            TACMeta.CVL_STRUCT_PATH,
            TACMeta.CVL_DEF_SITE,
        )


    private fun printModel() {
        if (!printModel) {
            return
        }

        val displayNameToSym = mutableMapOf<String, TACSymbol.Var>()
        val symToDisplayName = mutableMapOf<TACSymbol.Var, String>()
        model.tacAssignments.filterKeys(::symFilter).keys.forEach { sym ->
            sym.meta[TACMeta.CVL_DISPLAY_NAME]?.let { displayName ->
                symToDisplayName[sym] = displayName
                if (displayName in displayNameToSym) {
                    // might weaken this down the line
                    logger.warn { "displayName $displayName given to two symbols: ${displayNameToSym[displayName]} and $sym" }
                }
                displayNameToSym[displayName] = sym
            }
        }

        print("-------- CVL model begin ------------")
        model.tacAssignments.filterKeys(::symFilter).entries.groupBy { it.key.tag }.forEachEntry { (tag, list) ->
            print("-- type: $tag --")
            list.forEach { (sym, value) ->
                val valueStr = when (tag) {
                    Tag.Int, Tag.Bit256 -> value.toString().padStart(66) // "0x" prefix + 64 bytes
                    Tag.Bool -> value.toString().padStart(5)
                    else -> value.toString()
                }
                print("${sym.smtRep.padEnd(45)} ~~> $valueStr")
                cvlMetas.forEach { key ->
                    sym.meta[key]?.let { print("   ${key.toString().padEnd(20)}: $it") }
                }
            }
        }
        print("-------- CVL model end --------------")
        print("-------- TAC model begin ------------")
        model.tacAssignments.forEachEntry { (sym, v) ->
            print("  ${sym.toString().padStart(15)}  --> ${v.toString().padStart(66)}")
        }
        print("-------- TAC model end --------------")
        print("")
    }

    fun visit(ltacCmd: LTACCmd) {
        /* nop for now */
        unused(ltacCmd)
    }

    fun push(callInstance: CallInstance) {
        unused(callInstance)
        currentIndent += indentUnit
    }

    fun pop() {
        currentIndent -= indentUnit
        currentIndent = maxOf(currentIndent, 0)
    }

    /**
     * Prints the given [callInstance], also recursively prints its children, if any (triggering push/pop in case).
     */
    fun append(callInstance: CallInstance) {
        print(callInstance.prettyPrint(currentIndent))
        if (callInstance.children.isNotEmpty()) {
            push(callInstance)
            callInstance.children.forEach { child ->
                append(child)
            }
            pop()
        }
    }

    /** for printing several lines with indentation */
    private fun print(l: List<String>, extraIndent: Int = 0) {
        for (it in l) {
            print(it, extraIndent)
        }
    }

    private fun print(s: String, extraIndent: Int = 0) {
        val indentedLine = s.indnt(currentIndent + extraIndent * indentUnit)
        writer?.write(indentedLine + "\n")
        if (printToStdOut) {
            @Suppress("ForbiddenMethodCall")
            println(indentedLine)
        }
    }

    fun snippet(snippetCmd: SnippetCmd) {
        val prefix = "# snippet ${snippetCmd.javaClass.simpleName} ${snippetCmd.hashCode()} ;"
        val text = when (snippetCmd) {
            is SnippetCmd.EVMSnippetCmd.StorageSnippet ->
                listOf(
                    prefix,
                    " disp. path:  ${snippetCmd.displayPath}",
                    "  value sym:  ${snippetCmd.value}",
                )
            is SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithLocSym ->
                listOf(
                    prefix,
                    "   loc sym:  ${snippetCmd.loc}",
                    " value sym:  ${snippetCmd.value}",
                )
            is SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithPath ->
                listOf(
                    prefix,
                    "      path:  ${snippetCmd.path}",
                    " value sym:  ${snippetCmd.value}",
                )
            else -> listOf(prefix)
        }
        print(text, extraIndent = 1)
    }

    override fun close() {
        printModel()
        writer?.close()
    }

    fun localStorageChange(snippetCmd: SnippetCmd.EVMSnippetCmd.StorageSnippet) {
        print("# local storage change ${snippetCmd.hashCode()}", extraIndent = 1)
    }

    fun callDataMovement(calldataMovement: CalldataMovement) {
        when (calldataMovement) {
            CalldataMovement.None -> Unit
            else -> print("-> call data movement $calldataMovement", extraIndent = 1)
        }
    }

    fun returnDataMovement(returndataMovement: ReturndataMovement) {
        when (returndataMovement) {
            ReturndataMovement.None -> Unit
            else -> print("<- return data movement $returndataMovement", extraIndent = 1)
        }
    }
}

fun CallInstance.prettyPrint(indent: Int): String {
    val andDefault = "\n" + " ".repeat(indent) + "    " + this.toString()
    return when (this) {
        is CallInstance.InvokingInstance.CVLFunctionInstance -> "[CVLFunc, ${hashCode()}] $callIndex ; $name ; $paramNames; $paramTypes ; $paramValues : ${returnTypes.zip(returnValues.values)} $andDefault"
//        is CallInstance.GhostValueInstance -> "[GhostVal] $name : $value$andDefault"
//        is CallInstance.CVLExpInstance -> "[CVLExp] $name : $value$andDefault"
        is CallInstance.CvlrCexPrintValues -> "[RstCexPrintVal] ${sarif.flatten()}"
        is CallInstance.SolanaExternalCall -> "[SlnExternalCall] ${sarif.flatten()}"
        is CallInstance.CvlrCexPrintTag -> "[RstCexPrintTag] $name"
        else -> "[${javaClass.simpleName}, ${hashCode()}] $this"
    }
}

private class WriteToFile(val fileWriter: FileWriter) : Closeable {

    fun write(s: String) {
        fileWriter.write(s)
    }

    override fun close() {
        fileWriter.close()
    }

    companion object {
        operator fun invoke(filename: String): WriteToFile? {
            var fileWriter: FileWriter? = null
            ArtifactManagerFactory().registerArtifact(filename) { name ->
                val wAndF = ArtifactFileUtils.getWriterAndActualFileName(name)
                fileWriter = wAndF.first
            }
            return if (fileWriter != null) {
                WriteToFile(fileWriter!!)
            } else {
                logger.warn { "failed to create file \"$filename\" CallTracePrettyPrinter won't dump to that file" }
                null
            }
        }
    }
}



