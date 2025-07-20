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

package com.certora.certoraprover.cvl.formatter.util

import com.certora.certoraprover.cvl.sym

@JvmInline
value class TerminalId(val id: Int) {
    init {
        ensure(id in 0.rangeUntil(sym.terminalNames.size), "id is a valid symbol")
    }

    override fun toString(): String {
        // checked for existence in init block
        return tokenName[this.id]
    }
}

private val tokenName: List<String> = sym
    .terminalNames
    .mapIndexed { idx, terminalName ->
        check(sym.terminalNames[idx] == terminalName)

        when (idx) {
            sym.SORT -> "sort"
            sym.MAPPING -> "mapping"
            sym.GHOST -> "ghost"
            sym.PERSISTENT -> "persistent"
            sym.DEFINITION -> "definition"
            sym.AXIOM -> "axiom"
            sym.HOOK -> "hook"
            sym.FORALL -> "forall"
            sym.EXISTS -> "exists"
            sym.TRUE -> "true"
            sym.FALSE -> "false"
            sym.RULE -> "rule"
            sym.REVERT -> "revert"
            sym.FUNCTION -> "function"
            sym.RETURN -> "return"
            sym.HAVOC -> "havoc"
            sym.ASSUMING -> "assuming"
            sym.REQUIRE -> "require"
            sym.REQUIREINVARIANT -> "requireInvariant"
            sym.ASSERT -> "assert"
            sym.SATISFY -> "satisfy"
            sym.INVARIANT -> "invariant"
            sym.WEAK -> "weak"
            sym.STRONG -> "strong"
            sym.SUM -> "sum"
            sym.USUM -> "usum"
            sym.METHODS -> "methods"
            sym.FILTERED -> "filtered"
            sym.RESET_STORAGE -> "reset_storage"
            sym.IF -> "if"
            sym.ELSE -> "else"
            sym.AS -> "as"
            sym.IN -> "in"
            sym.AT -> "at"
            sym.WITH -> "with"
            sym.SIG -> "sig"
            sym.DESCRIPTION -> "description"
            sym.GOODDESCRIPTION -> "good_description"
            sym.PRESERVED -> "preserved"
            sym.ON_TRANSACTION_BOUNDARY -> "onTransactionBoundary"

            sym.USING -> "using"
            sym.IMPORT -> "import"
            sym.USE -> "use"
            sym.BUILTIN -> "builtin"
            sym.OVERRIDE -> "override"

            sym.AMPERSAT -> "@"
            sym.NOREVERT -> "norevert"
            sym.WITHREVERT -> "withrevert"
            sym.OLD -> "old"
            sym.NEW -> "new"

            sym.LAST_STORAGE -> "lastStorage"
            sym.LAST_REVERTED -> "lastReverted"

            sym.SLOAD -> "Sload"
            sym.SSTORE -> "Sstore"
            sym.TLOAD -> "Tload"
            sym.TSTORE -> "Tstore"
            sym.CREATE -> "Create"
            sym.STORAGE -> "STORAGE"

            sym.ALWAYS -> "ALWAYS"
            sym.CONSTANT -> "CONSTANT"
            sym.PER_CALLEE_CONSTANT -> "PER_CALLEE_CONSTANT"
            sym.NONDET -> "NONDET"
            sym.HAVOC_ECF -> "HAVOC_ECF"
            sym.HAVOC_ALL -> "HAVOC_ALL"
            sym.ASSERT_FALSE -> "ASSERT_FALSE"
            sym.AUTO -> "AUTO"
            sym.UNRESOLVED -> "UNRESOLVED"
            sym.ALL -> "ALL"
            sym.DELETE -> "DELETE"
            sym.DISPATCHER -> "DISPATCHER"
            sym.DISPATCH -> "DISPATCH"
            sym.UNRESOLVED_LOWERCASE -> "unresolved"
            sym.VOID -> "void"
            sym.RETURNS -> "returns"
            sym.EXPECT -> "expect"
            sym.FALLBACK -> "fallback"
            sym.DEFAULT -> "default"

            sym.PLUS -> "+"
            sym.MINUS -> "-"
            sym.TIMES -> "*"
            sym.DIV -> "/"
            sym.MOD -> "%"
            sym.EXPONENT -> "^"
            sym.NOT -> "!"
            sym.LOR -> "||"
            sym.LAND -> "&&"
            sym.IMPLIES -> "=>"
            sym.IFF -> "<=>"
            sym.BWAND -> "&"
            sym.BWOR -> "|"
            sym.BWXOR -> "xor"
            sym.BWNOT -> "~"
            sym.BWLSHIFT -> "<<"
            sym.BWRSHIFT -> ">>"
            sym.BWRSHIFTWZEROS -> ">>>"
            sym.MAPSTO -> "->"
            sym.ASSIGN -> "="
            sym.COMMA -> ","
            sym.COLON -> ":"
            sym.SC -> ";"
            sym.LB -> "{"
            sym.RB -> "}"
            sym.LSQB -> "["
            sym.RSQB -> "]"
            sym.LP -> "("
            sym.RP -> ")"
            sym.QUESTION -> "?"
            sym.DOT -> "."

            // not literals, just input classes. the actual matched string is not known from the constant name
            sym.RELOP -> "(relop)"
            sym.CAST -> "(cast function)"
            sym.LOCATION -> "(memory location)"
            sym.OPCODE -> "(opcode)"
            sym.PRE_RETURN_QUALIFIER -> "(pre-return qualifier)"
            sym.POST_RETURN_QUALIFIER -> "(post-return qualifier)"
            sym.BIF -> "(built-in function)"
            sym.CONST_VAL -> "(const val)"
            sym.ID -> "(identifier)"
            sym.NUMBER -> "(number)"
            sym.STRING -> "(string)"

            sym.EOF -> "EOF"
            sym.error -> "error"

            else -> error("no name defined for terminal `$terminalName` (id = $idx)")
        }
    }