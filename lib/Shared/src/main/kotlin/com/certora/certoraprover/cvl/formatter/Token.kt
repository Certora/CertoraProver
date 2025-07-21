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
import com.certora.certoraprover.cvl.sym
import datastructures.stdcollections.*

/** the public and opaque interface for describing a token, exposed here to allow testing */
sealed interface IToken

/** the non-public impl of [IToken] */
internal sealed interface Token: IToken {
    data class Terminal(
        val str: String,
        val id: TerminalId?,
        val space: Space = Space.TT,
    ) : Token

    sealed interface LineBreak : Control {
        data object Soft : LineBreak
        data object Hard : LineBreak
    }
    data object Indent : Control
    data object Unindent : Control
    data object SingleWhiteSpace : Control
    data class Comments(val binding: Binding) : Control

    sealed interface Control : Token

    // overriding math ops is a recipe for spaghetti. please use responsibly.
    operator fun plus(batch: List<Token>): List<Token> = this.asList().plus(batch)
    operator fun plus(other: Token): List<Token> = this.asList().plus(other)

    /**
     * adapter function for use in things that take lists-of-lists.
     *
     * perf: this seems pretty cheap since we use a small-list optimization.
     *
     * impl: we cam also make the receiver nullable, and return an empty list in the null case.
     * this reduces noise and saves a few keystrokes, but I don't like it.
     * by explicitly showing that this is nullable at the call site,
     * we make it clear that this element is optional.
     */
    fun asList() = listOf(this)

    fun append(lazy: () -> Token): List<Token> = this.asList().append(lazy)
    fun prepend(lazy: () -> Token): List<Token> = this.asList().prepend(lazy)

    companion object {
        fun identifier(content: String, space: Space = Space.TT): Terminal {
            val tid = TerminalId(sym.ID)
            return Terminal(
                str = content,
                id = tid,
                space,
            )
        }

        fun fromTerminalId(tid: TerminalId, space: Space = Space.TT): Terminal {
            return Terminal(
                str = tid.toString(),
                id = tid,
                space,
            )
        }

        fun fromSym(symId: Int, space: Space = Space.TT): Terminal = fromTerminalId(TerminalId(symId), space)

        fun string(str: String, space: Space = Space.TT): Terminal {
            return Terminal(
                str,
                id = TerminalId(sym.STRING),
                space,
            )
        }

        fun number(content: String, space: Space = Space.TT) = Terminal(
            content,
            TerminalId(sym.NUMBER),
            space
        )

        fun boolean(bool: Boolean, space: Space = Space.TT): Terminal {
            val sym = if (bool) { sym.TRUE } else { sym.FALSE }
            return fromSym(sym, space)
        }

        fun endStatement(): List<Token> = listOf(
            Punctuation.Semicolon.toToken(),
            LineBreak.Soft,
        )
    }

    enum class Punctuation(val id: TerminalId, val space: Space) {
        Dot(id = TerminalId(sym.DOT), space = Space.FF),
        Comma(id = TerminalId(sym.COMMA), space = Space.FT),
        Semicolon(id = TerminalId(sym.SC), space = Space.FF),
        Ampersand(id = TerminalId(sym.AMPERSAT), space = Space.FF),
        Equals(id = TerminalId(sym.ASSIGN), space = Space.TT),
        ;

        fun toToken(space: Space = this.space): Terminal = fromTerminalId(this.id, space)
    }

    enum class Delimiter(
        val l: TerminalId,
        val r: TerminalId,
        val opensScope: Boolean,
        val space: Space,
    ) {
        Parenthesis(
            l = TerminalId(sym.LP),
            r = TerminalId(sym.RP),
            opensScope = false,
            Space.FT,
        ),
        SquareBracket(
            l = TerminalId(sym.LSQB),
            r = TerminalId(sym.RSQB),
            opensScope = false,
            Space.FT,
        ),
        CurlyBracket(
            l = TerminalId(sym.LB),
            r = TerminalId(sym.RB),
            opensScope = true,
            Space.TT,
        ),
    }
}

/**
 * like [Collection.plus], except it avoids unnecessary allocation of the parameter
 * if the method doesn't actually get invoked
 */
internal fun List<Token>.append(lazy: () -> Token): List<Token> = this.plus(lazy())

/**like [append], except the element gets prepended */
internal fun List<Token>.prepend(lazy: () -> Token): List<Token> = listOf(lazy()).plus(this)

internal enum class Space(val before: Boolean, val after: Boolean) {
    TT(before = true, after = true),
    TF(before = true, after = false),
    FT(before = false, after = true),
    FF(before = false, after = false),
    ;

    companion object {
        operator fun invoke(before: Boolean, after: Boolean): Space = when {
            before  && after  -> TT
            before  && !after -> TF
            !before && after  -> FT
            else              -> FF
        }
    }
}

internal fun List<Token>.surround(
    delimiter: Token.Delimiter,
    spaceOpen: Space = Space(before = delimiter.space.before, after = false),
    spaceClose: Space = Space(before = false, after = delimiter.space.after),
    showIfEmpty: Boolean = true,
    openScope: Boolean = delimiter.opensScope,
): List<Token> {
    if (this.isEmpty() && !showIfEmpty) {
        return emptyList()
    }

    val l = Token.fromTerminalId(delimiter.l, spaceOpen)
    val r = Token.fromTerminalId(delimiter.r, spaceClose)

    return if (openScope) {
        val before = listOf(l, Token.Indent, Token.LineBreak.Hard)
        val after = listOf(Token.Unindent, Token.LineBreak.Soft, r)
        before + this + after
    } else {
        l + this + r
    }
}