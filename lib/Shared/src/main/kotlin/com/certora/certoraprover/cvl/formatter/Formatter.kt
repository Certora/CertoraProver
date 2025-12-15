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

import com.certora.certoraprover.cvl.Ast
import com.certora.certoraprover.cvl.formatter.util.*
import datastructures.stdcollections.*

class FormatterInput private constructor(private val batches: List<Batch>) {
    constructor(ast: Ast) : this(preprocess(ast))
    private var indentDepth = 0
    private var lineBreakRunningSum = 0

    fun output(): String {
        val output = StringBuilder()
        indentDepth = 0

        batches.zipWithNextPartial { batch, nextBatch ->
            batch.zipWithNextPartial { token, nextToken ->
                output.appendToken(token, nextToken)
            }

            if (nextBatch != null) {
                output.appendBatchSeparator()
            }
        }

        // we normalize to ending the last line with a line break.
        return output.toString().trimEnd().plus(System.lineSeparator())
    }

    private fun StringBuilder.appendToken(token: Token, nextToken: Token?) {
        when (token) {
            is Token.LineBreak.Soft -> {
                if (!this.currentLine().isBlank() && nextToken !is Token.Comments) {
                    appendLineBreakAndIndent()
                }
            }

            is Token.LineBreak.Hard -> {
                appendLineBreakAndIndent()
            }

            is Token.Terminal -> {
                append(token.str)

                // add whitespace only if both this token and the one after it agree.
                val whitespaceAfter = if (token.space.after) {
                    when (nextToken) {
                        is Token.Terminal -> nextToken.space.before
                        is Token.Control -> false
                        null -> false
                    }
                } else {
                    false
                }

                if (whitespaceAfter) {
                    this.appendWhitespace()
                }

                if (!token.str.trim().isEmpty()) {
                    this@FormatterInput.lineBreakRunningSum = 0
                }
            }

            is Token.Indent -> {
                this@FormatterInput.indentDepth += 1
                if (nextToken !is Token.LineBreak) {
                    this.appendIndent()
                }
            }

            is Token.Unindent -> {
                this@FormatterInput.indentDepth -= 1
                this.deleteIndent()
            }

            is Token.SingleWhiteSpace -> {
                if (!currentLine().isBlank()) {
                    this.appendWhitespace()
                }
            }

            is Token.Comments -> {
                token.binding.expand().zipWithNextPartial { runToken, nextRunToken -> appendToken(runToken, nextRunToken) }
            }
        }
    }

    private fun StringBuilder.currentLine(): String {
        val start = this.lastIndexOf(System.lineSeparator()).foundOrNull() ?: 0
        val end = this.lastIndex
        return this.slice(start..end).toString()
    }

    private fun StringBuilder.appendBatchSeparator() {
        // reset to top-level, which is the very start of a line
        // and we don't care about indentation since we're resetting to top-level
        this@FormatterInput.indentDepth = 0

        if (this@FormatterInput.lineBreakRunningSum == 0) {
            this@StringBuilder.appendLineBreakAndIndent()
        }

        repeat(linesBetweenBatches) { appendLineBreakAndIndent() }
    }

    private fun whitespace(count: Int = 1) = " ".repeat(count)

    private fun StringBuilder.appendWhitespace(count: Int = 1) = this.append(whitespace(count))

    private fun StringBuilder.appendLineBreakAndIndent() {
        if (this@FormatterInput.lineBreakRunningSum >= maxConsecutiveLineBreaks) {
            return
        }

        val whitespaceCount = this@FormatterInput.indentDepth * indentSize
        val indentLiteral =  this@FormatterInput.whitespace(whitespaceCount)

        // trim line before linebreak
        this.deleteIndent()

        appendLine()
        append(indentLiteral)

        this@FormatterInput.lineBreakRunningSum += 1
    }

    private fun StringBuilder.deleteIndent() {
        val line = this.currentLine()
        if (line.length >= indentSize && line.isBlank()) {
            val newLength = this.length - indentSize
            check(newLength >= 0)
            this.setLength(newLength)
        }
    }

    private fun StringBuilder.appendIndent() {
        this.appendWhitespace(indentSize)
    }
}

private const val indentSize = 4
private const val linesBetweenBatches = 1
private const val maxConsecutiveLineBreaks = 2

internal fun Int.min(other: Int) = kotlin.math.min(this, other)