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

package sbf.domains

/**
 * User-provided summaries for the memory domain.
 *
 * Memory summaries only allow to express whether r0 or (*W)(ri+O) points to X, where
 * - X is of type MemSummaryArgumentType
 * - W is {i8, i16, i32, i64}
 * - i is in {1,2,3,4,5}
 * - O is a numerical offset
 *
 * Memory summaries can only express memory states *after* a call has been executed.
 * Therefore, they cannot express input-output relationships between memory states before
 * and after the call. Instead, they only refer to the outputs.
 * This means that we can express summaries such as "(*i64)r1 points to the heap after the call"
 * but we cannot express summaries such as
 * "if (*i64)r1 points to X before the call then (*i64)r2 points to Y after the call"
 **/

import sbf.cfg.SbfInstruction
import sbf.cfg.LocatedSbfInstruction
import sbf.disassembler.SbfRegister
import sbf.sbfLogger
import sbf.inliner.InlinerConfigFromFile
import sbf.support.*
import sbf.support.readLines
import config.Config
import datastructures.stdcollections.*

import org.jetbrains.annotations.TestOnly
import utils.ArtifactFileUtils

private class MemorySummaryParseError(msg: String): SolanaInternalError("Error while parsing memory: $msg")

enum class MemSummaryArgumentType {
    ANY,
    NUM,
    PTR_STACK,
    PTR_INPUT,
    PTR_GLOBAL,
    PTR_HEAP,
    PTR_EXTERNAL;
    companion object {
        fun from(input: String): MemSummaryArgumentType? {
            return MemSummaryArgumentType.entries.firstOrNull { it.name.equals(input, true) }
        }
    }
}

/**
 * This class is used to represent either
 * - register [r] has the type [type] if [r] == r0
 * - expression (*i[width]*8)([r]+[offset]) has the type [type], otherwise.
 *
 * [width] is the number of *bytes* being de-referenced.
 * [allocatedSpace] is the allocated space in bytes for the argument if it is a pointer.
 */
data class MemSummaryArgument(val r: SbfRegister,
                              val offset: Long = 0,
                              val width: Byte = 0,
                              val allocatedSpace: ULong = 0UL,
                              val type: MemSummaryArgumentType) {
    init {
        if (r > SbfRegister.R5_ARG) {
            throw MemorySummaryParseError("MemSummaryArgument: unexpected register $r")
        }
        if (r == SbfRegister.R0_RETURN_VALUE) {
            if (width.toInt() != 0) {
                throw MemorySummaryParseError("MemSummaryArgument: expected non-zero width $width for $r")
            }
            if (offset != 0L) {
                throw MemorySummaryParseError("MemorySummaryArgument: unexpected non-zero offset $offset for $r")
            }
        } else {
            if (!setOf(1,2,4,8).contains(width.toInt())) {
                throw MemorySummaryParseError("MemorySummaryArgument: unexpected width $width")
            }
        }
        if (allocatedSpace > 0UL) {
            if (!(type == MemSummaryArgumentType.PTR_EXTERNAL ||
                    type == MemSummaryArgumentType.PTR_HEAP ||
                    type == MemSummaryArgumentType.PTR_INPUT ||
                    type == MemSummaryArgumentType.PTR_GLOBAL)) {
                throw MemorySummaryParseError("MemorySummaryArgument: expected non-zero allocated size only for pointers")
            }
        }
    }

    override fun toString(): String {
        return if (r == SbfRegister.R0_RETURN_VALUE) {
            "$r:$type"
        } else {
            "*u${width*8}($r+$offset):$type"
        }
    }
}

typealias MemSummaryArguments = List<MemSummaryArgument>

/** Convenient visitor to process all summary arguments **/
interface SummaryVisitor {
    fun noSummaryFound(locInst: LocatedSbfInstruction)

    /** After function call to [locInst] the type of r0 is [type] **/
    fun processReturnArgument(locInst: LocatedSbfInstruction, type: MemSummaryArgumentType)
    /**
     * After function call to [locInst] the type of (*W)([reg]+[offset]) is [type] where
     * - [reg] is r1,r2,r3,r4, or r5
     * - [offset] is a numerical offset and
     * - W is [width]*8 and [width] is in {1,2,4,8}
     * - [allocatedSpace] is the allocated space in bytes for argument if pointer
     **/
    fun processArgument(locInst: LocatedSbfInstruction,
                        reg: SbfRegister, offset: Long, width: Byte, allocatedSpace: ULong, type: MemSummaryArgumentType)
}

/**
 * [args] are the summarized values of the call arguments **after** the call returns.
 * If [isAbort] is true then [args] will be ignored.
 */
data class MemorySummary(val args: MemSummaryArguments, val isAbort: Boolean = false)

/** This class contains the user-provided memory summaries **/
data class MemorySummaries(private val summaries: List<Pair<Regex, MemorySummary>> = arrayListOf()) {

    // hit/miss caches to avoid iterate over summaries over and over
    private val summaryCache: MutableMap<String, MemorySummary> = mutableMapOf()
    private val noSummaryCache: MutableSet<String> = mutableSetOf()

    /** Return the memory summary for fname **/
    fun getSummary(fname: String): MemorySummary? {
        if (noSummaryCache.contains(fname)) {
            return null
        }
        val cachedSummary = summaryCache[fname]
        return if (cachedSummary != null) {
            cachedSummary
        } else {
            val summary = summaries.firstOrNull { (regex, _)  ->
                regex.containsMatchIn(fname)
            }?.let {
                summaryCache[fname] = it.second
                it.second
            }
            if (summary == null) {
                noSummaryCache.add(fname)
            }
            summary
        }
    }

    /** Convenient visitor to iterate over a call summary **/
    fun visitSummary(locInst: LocatedSbfInstruction, vis: SummaryVisitor) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) {"visitSummary expects a call instead of $inst"}
        val summary = getSummary(inst.name)
        if (summary == null) {
            vis.noSummaryFound(locInst)
        } else {
            for (sumArg in summary.args) {
                if (sumArg.r == SbfRegister.R0_RETURN_VALUE) {
                    vis.processReturnArgument(locInst, sumArg.type)
                } else {
                    check(sumArg.r <= SbfRegister.R5_ARG) {"A summary can only refer to r1-r5"}
                    vis.processArgument(locInst, sumArg.r, sumArg.offset, sumArg.width, sumArg.allocatedSpace, sumArg.type)
                }
            }
        }
    }

    /** To add extra summaries once the database of summaries has been loaded by calling [readSpecFile] **/
    fun addSummary(fname: String, summary: MemorySummary) {
        if (summaries.any { (regex,_) -> regex.containsMatchIn(fname)}) {
            sbfLogger.warn {"There is already a PTA summary for $fname. Skipped new summary $summary." +
                            "This warning is usually benign."
            }
        } else {
            summaryCache[fname] = summary
        }
    }

    fun isKnownAbortFn(fname: String) = getSummary(fname)?.isAbort == true

    companion object {
        val grammar =
            """
    <ARGUMENT_TYPE>+ <FUNCTION_NAME>

    where
      <FUNCTION_NAME> is a regex
      <TYPE> = num | ptr_stack | ptr_input |  ptr_global | ptr_heap | ptr_external
      <ARGUMENT-TYPE> ::= "#[type(" [r0| (*i<W>)(r<R>+<O>)] ":" <TYPE> "(" <S> ")" ")"
      <R>  is an integer in the range 1...5
      <W>  is an integer in {8,16,32,64}
      <O>  is an integer
      <S>  is a positive integer
            """.trimIndent()


        /**
         *  Read a file that contains function summaries
         *
         *  The syntax is defined by [grammar].
         *  The semantics is as follows:
         *    `#[type(r0: TYPE)]` if the execution of the call succeeds then the type of `r0` is `TYPE`
         *    `#[type((*i<W>)(r<N>+<O>): TYPE)]` if the execution of the call succeeds then the type of `(*i<W>)(r<N>+<O>)` is `TYPE`
         */
        fun readSpecFile(filename: String): MemorySummaries {
            val filenameFromSourcesDir = ArtifactFileUtils.wrapPathWith(filename, Config.getSourcesSubdirInInternal())
            val lines = readLines(filenameFromSourcesDir) ?: readLines(filename) ?: throw SolanaError("cannot find $filenameFromSourcesDir or $filename")
            return readSpecFile(lines, filename)
        }

        @Suppress("ForbiddenMethodCall")
        private val argumentTypeRegex = """\#\[\s*type\s*\(\s*(\(\*i\d+\))?(\(r(\d+)\+(\d+)\)|r(\d+))\s*:\s*(num|ptr_stack|ptr_input|ptr_global|ptr_heap|ptr_external)\s*(\(\s*\d+\s*\))?\s*\)\s*\]""".trimIndent().toRegex()

        fun isSummaryAnnotation(input: String) = argumentTypeRegex.matches(input)

        @TestOnly
        @Suppress("ForbiddenMethodCall")
        fun readSpecFile(lines: List<String>, filename: String): MemorySummaries {
            fun parseRegister(str: String): SbfRegister {
                val reg = SbfRegister.getByValue(str.toByte())
                if (reg > SbfRegister.R5_ARG) {
                    throw MemorySummaryParseError("can only use r0-r5")
                }
                return reg
            }
            fun parseDeref(str: String): Byte {
                return when (str) {
                    "" -> 0
                    "(*i8)" -> 1
                    "(*i16)" -> 2
                    "(*i32)" -> 4
                    "(*i64)" -> 8
                    else -> throw MemorySummaryParseError("$str is not an expected expression")
                }
            }
            fun parseOffset(str: String): Long {
                return str.toLong()
            }


            val summaries = mutableListOf<Pair<Regex, MemorySummary>>()
            // it needs to be mutable because it will be reset inside the loop
            var argumentTypes = mutableListOf<MemSummaryArgument>()
            for (line in lines) {
                if (line == "" || line.startsWith(";")) { // ; for comments
                    continue
                }
                var matched = false
                /**
                 * The above regex matches these two kind of annotations:
                 *
                 * #[type( (*i64)  (r1  + 4):  ptr_external(N))]
                 *           |       |    |                 |
                 *          g[1]   g[3]  g[4]              g[7] (optional)
                 *                 ----------  ----------
                 *                    g[2]          g[6]
                 *
                 * #[type(  r1              : num)]
                 *           |                 |
                 *          g5 (only "1")    g[6]
                 *          g2 (r1)
                 *
                 */
                try {
                    argumentTypeRegex.find(line).let {
                        if (it != null) {
                            val groups = it.groupValues
                            // parse (*i64)
                            val deref = parseDeref(groups[1])
                            // use matches[5] to discriminate between the first or second kind of annotation
                            val (reg, offset) = if (groups[5] == "") {
                                // parse (r1 + 4)
                                Pair(parseRegister(groups[3]), parseOffset(groups[4]))
                            } else {
                                // parse r1
                                Pair(parseRegister(groups[5]), 0L)
                            }
                            // parse ptr_external(S)|ptr_stack|num|...
                            val type = MemSummaryArgumentType.from(groups[6])
                                ?: throw MemorySummaryParseError("Cannot translate \"${groups[6]}\" to a MemorySummaryType")
                            // parse (S)
                            val allocatedSpace = if (groups[7] == "") {
                                0UL
                            } else {
                                // remove parenthesis
                                groups[7].substring(1, groups[7].length - 1).toULong()
                            }
                            argumentTypes.add(MemSummaryArgument(reg, offset, deref, allocatedSpace, type))
                            matched = true
                        }
                    }
                }  catch (@Suppress("SwallowedException") e: MemorySummaryParseError) {
                    // the exception is swallowed because we want to add line and filename to the error message.
                    throw CannotParseSummaryFile(line, filename, grammar, e.internalMsg)
                }

                if (!matched) {
                    // we skip inlining annotations
                    if (InlinerConfigFromFile.isInlineAnnotation(line)) {
                        continue
                    }
                    if (line.startsWith("#")) {
                        throw CannotParseSummaryFile(line, filename, grammar)
                    }

                    val regex = line.toRegex()
                    if (summaries.any {(x,_) -> x == regex}) {
                        throw CannotParseSummaryFile(line, filename, grammar,"Found duplicated regex $regex in $filename")
                    }
                    summaries.add(Pair(regex, MemorySummary(argumentTypes)))
                    // reset argumentTypes for the next function
                    argumentTypes = mutableListOf()
                }
            }
            return MemorySummaries(summaries)
        }
    }
}


fun hasSummary(fname: String, memSummaries: MemorySummaries): Boolean {
    val inst = SbfInstruction.Call(fname)
    return if (inst.isAbort() || inst.isAllocFn() || inst.isAssertOrSatisfy() || inst.isDeallocFn() || inst.isExternalFn()) {
        true
    } else {
        memSummaries.getSummary(fname) != null
    }
}





