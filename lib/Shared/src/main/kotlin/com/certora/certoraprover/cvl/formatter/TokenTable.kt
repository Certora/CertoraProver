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

package com.certora.certoraprover.cvl.formatter

import com.certora.certoraprover.cvl.formatter.util.*
import java_cup.runtime.ComplexSymbolFactory
import java_cup.runtime.ComplexSymbolFactory.ComplexSymbol
import utils.Range
import utils.firstMapped

internal sealed interface Entry {
    val range: Range.Range

    /**
     * represents a token that was emitted by the lexer and passed to the parser,
     * as opposed to a lexer match that is not passed to the parser.
     */
    data class Emitted(val id: TerminalId, override val range: Range.Range, val content: String) : Entry {
        constructor(symbol: ComplexSymbol) : this(
            id = TerminalId(symbol.sym),
            range = Range.Range(symbol.xleft, symbol.xright),
            content = symbol.value.toString(),
        )
    }

    sealed interface NotEmitted : Entry {
        data class Comment(override val range: Range.Range, val content: String, val multiLine: Boolean) : NotEmitted
        data class Whitespace(override val range: Range.Range, val count: Int) : NotEmitted
        data class LineBreaks(override val range: Range.Range, val count: Int) : NotEmitted
    }
}

/** making [TokenTable] public is kind a headache */
sealed interface ITokenTable

internal fun ITokenTable.downcast(): TokenTable = when (this) {
    is TokenTable -> this
}

internal data class TokenTable(val entries: List<Entry>, val fileComments: List<Binding>) : ITokenTable

/** progressively builds [data] using callbacks from the lexer */
class TokenTableBuilder {
    private val data: MutableList<Entry> = mutableListOf()
    private val commentsBuilder = CommentsBuilder()

    var reachedEOF: Boolean = false
        private set

    fun build(): ITokenTable {
        // these records are periodically updated (by the lexer)
        // as parsing progresses, so the data is only final once parsing is finished.
        //
        // so if it's not finished. it might make sense to throw here.
        ensure(this.reachedEOF, "token table only final once lexing is finished")

        // pretty sure this can't be null.
        val lastTokenOfFile = data.asReversed().firstMapped { it as? Entry.Emitted }

        return TokenTable(
            entries = this.data,
            fileComments = this.commentsBuilder.build(lastTokenOfFile)
        )
    }

    private fun add(entry: Entry) {
        ensure(!this.reachedEOF, "no callbacks after EOF")

        // n.b. about the monotonicity invariants maintained in this module:
        //
        // weak (rather than strong) inequalities are used throughout this file,
        // whenever we assert monotonicity, despite the fact that tokens don't intersect.
        //
        // this is because _the ranges of the tokens_ may intersect:
        // the start of a range is inclusive but the end is exclusive.
        val prevEntry = this.data.lastOrNull()
        if (prevEntry != null) {
            ensure(prevEntry.range.end <= entry.range.start, "monotonicity")
        }

        this.data.add(entry)
    }

    fun registerTokenEmit(symbol: ComplexSymbol) {
        val entry = Entry.Emitted(symbol)
        this.add(entry)
        this.commentsBuilder.register(entry)
    }

    fun registerComment(start: ComplexSymbolFactory.Location, end: ComplexSymbolFactory.Location, content: String, multiLine: Boolean) {
        val range = Range.Range(start, end)
        val entry = Entry.NotEmitted.Comment(range, content, multiLine)
        this.add(entry)
        this.commentsBuilder.register(entry)
    }

    fun registerWhitespace(start: ComplexSymbolFactory.Location, end: ComplexSymbolFactory.Location, content: String) {
        val range = Range.Range(start, end)
        val entry = Entry.NotEmitted.Whitespace(range, content.length)
        this.add(entry)
        this.commentsBuilder.register(entry)
    }

    fun registerLineBreaks(start: ComplexSymbolFactory.Location, end: ComplexSymbolFactory.Location, content: String) {
        val range = Range.Range(start, end)
        val entry = Entry.NotEmitted.LineBreaks(range, content.length)
        this.add(entry)
        this.commentsBuilder.register(entry)
    }

    fun registerEOF(start: ComplexSymbolFactory.Location, end: ComplexSymbolFactory.Location) {
        // EOF -> non-EOF transitions seem like errors, so in those cases we throw.
        // however it appears that we may read EOF more than once.
        // so we permit EOF -> EOF.
        if (!this.reachedEOF) {
            val range = Range.Range(start, end)
            ensure(range.start == range.end, "EOF is zero-length")

            /**
             * there was a check here that the table is "filled",
             * that is: for every character offset from the offset 0 until EOF,
             * there's a (single) token occupying that offset.
             * anyway, we trust the lexer here.
             */

            this.reachedEOF = true
        }
    }
}