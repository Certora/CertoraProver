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

import com.certora.certoraprover.cvl.formatter.util.TerminalId
import com.certora.certoraprover.cvl.formatter.util.ensure
import com.certora.certoraprover.cvl.formatter.util.foundOrNull
import com.certora.certoraprover.cvl.formatter.util.invariantBroken
import com.certora.certoraprover.cvl.sym
import datastructures.NonEmptyList
import datastructures.stdcollections.*
import datastructures.toNonEmptyList
import utils.Range
import utils.SourcePosition

internal data class Comments(
    val before: List<Entry.NotEmitted>,
    val content: List<Entry.NotEmitted>,
    val after: List<Entry.NotEmitted>,
) {
    init {
        /** friendly reminder that [Iterable.any] returns false on an empty collection */
        require(content.any { it is Entry.NotEmitted.Comment })
    }

    fun concatenate(): List<Entry.NotEmitted> {
        return if (IGNORE_BEFORE_AND_AFTER) {
            this.content
        } else {
            this.before + this.content + this.after
        }
    }

    fun bindToToken(prevEmitted: Entry.Emitted?, nextEmitted: Entry.Emitted): Binding {
        fun beforeNext() = Binding(Association.Before, nextEmitted, this)
        fun afterPrev() = Binding(Association.After, prevEmitted!!, this)

        return when {
            prevEmitted == null -> beforeNext()
            prevEmitted.isOpenDelimiter() -> beforeNext()
            nextEmitted.isCloseDelimiter() -> afterPrev()
            else -> {
                val distanceToPrev = this.range.start.line - prevEmitted.range.end.line
                val distanceToNext = nextEmitted.range.start.line - this.range.end.line

                if (distanceToPrev < distanceToNext) { afterPrev() } else { beforeNext() }
            }
        }
    }

    val range get(): Range.Range {
        val entries = this.concatenate()

        ensure(!entries.isEmpty(), "at least `content` is not empty")

        val first = entries.first()
        val last = entries.last()

        return first.range.copy(end = last.range.end)
    }

    override fun toString(): String = this.concatenate().joinToString(separator = " ") {
        when (it) {
            is Entry.NotEmitted.Comment -> it.content
            is Entry.NotEmitted.LineBreaks -> System.lineSeparator().repeat(it.count)
            is Entry.NotEmitted.Whitespace -> " ".repeat(it.count)
        }
    }
}

internal fun Comments(buffer: List<Entry.NotEmitted>): Comments {
    val start = buffer.indexOfFirst { it is Entry.NotEmitted.Comment }.foundOrNull()
    ensure(start != null, "in-progress runs contain comments")

    val end = buffer.indexOfLast { it is Entry.NotEmitted.Comment }.foundOrNull()
    ensure(end != null, "start was not null")

    val beforeRange = 0.rangeUntil(start)
    val contentRange = start.rangeTo(end)
    val afterRange = (end + 1).rangeUntil(buffer.size)

    val comments = Comments(
        before = buffer.slice(beforeRange),
        content = buffer.slice(contentRange),
        after = buffer.slice(afterRange)
    )
    return comments
}

internal enum class Association { Before, After }

internal data class Binding(val association: Association, val token: Entry.Emitted, val comments: Comments)

private class CommentRunBuffer(private val buffer: MutableList<Entry.NotEmitted>, var inProgress: Boolean) {
    fun add(entry: Entry.NotEmitted) {
        this.buffer.add(entry)
    }

    fun takeBuffer(): NonEmptyList<Entry.NotEmitted>? {
        val buffer = if (this.inProgress) {
            this.buffer.toNonEmptyList() ?: invariantBroken("we added a comment when we transitioned to in-progress")
        } else {
            null
        }

        this.buffer.clear()
        this.inProgress = false

        return buffer
    }
}

internal class CommentsBuilder {
    private val currentRun: CommentRunBuffer = CommentRunBuffer(mutableListOf(), inProgress = false)

    private var prevEmitted: Entry.Emitted? = null
    private val bindings: MutableList<Binding> = mutableListOf()

    fun register(entry: Entry) {
        when (entry) {
            is Entry.Emitted -> {
                val buffer: List<Entry.NotEmitted>? = this.currentRun.takeBuffer()

                if (buffer != null) {
                    val binding = Comments(buffer).bindToToken(this.prevEmitted, nextEmitted = entry)
                    bindings.add(binding)
                }

                this.prevEmitted = entry
            }

            is Entry.NotEmitted.Comment -> {
                this.currentRun.inProgress = true
                this.currentRun.add(entry)
            }
            is Entry.NotEmitted.LineBreaks -> this.currentRun.add(entry)
            is Entry.NotEmitted.Whitespace -> this.currentRun.add(entry)
        }
    }

    fun build(lastTokenOfFile: Entry.Emitted?): List<Binding> {
        val buffer = this.currentRun.takeBuffer()
        if (buffer != null) {
            val token = if (lastTokenOfFile != null) {
                lastTokenOfFile
            } else {
                // we got a file of nothing but comments,
                // so we've got nothing to bind these comments to.
                //
                // XXX: sadly our architecture can't easily deal with this case right now.
                // instead, we generate a binding †o attach them to.

                val fileName = buffer.first().range.file
                zeroSizedEntry(fileName)
            }

            val trailingComments = Binding(Association.After, token, Comments(buffer))
            this.bindings.add(trailingComments)
        }

        return this.bindings
    }
}

/** for now, let's not consider the whitespace that is before/after a comment run */
private const val IGNORE_BEFORE_AND_AFTER = true

private fun Entry.Emitted.isOpenDelimiter(): Boolean = Token.Delimiter.entries.any { this.id == it.l }
private fun Entry.Emitted.isCloseDelimiter(): Boolean = Token.Delimiter.entries.any { this.id == it.r }

private fun zeroSizedEntry(fileName: String) = Entry.Emitted(
    id = TerminalId(sym.STRING),
    range = Range.Range(fileName, SourcePosition.zero(), SourcePosition.zero()),
    content = "",
)