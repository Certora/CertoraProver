package com.certora.certoraprover.cvl;

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

import com.certora.certoraprover.cvl.formatter.ITokenTable;
import com.certora.certoraprover.cvl.formatter.TokenTableBuilder;
import java_cup.runtime.ComplexSymbolFactory;
import java_cup.runtime.ComplexSymbolFactory.Location;
import java_cup.runtime.Symbol;
import spec.CVLCastFunction;

%%


%class Lexer
%public
%unicode
%line
%column
%cup
%char
%{
    boolean DEBUG = false;

    private void debug(String s, Object ... args) {
        if (DEBUG) {
            System.out.printf(s,args);
        }
    }

	public Lexer(ComplexSymbolFactory sf, java.io.Reader reader){
		this(reader);
        symbolFactory = sf;
    }

    private StringBuffer sb;
    private ComplexSymbolFactory symbolFactory;
    private int csline,cscolumn;

    private java.util.Map<String, CVLCastFunction> castFunctions = java.util.Collections.emptyMap();
    private java.util.Set<String> memoryLocations = java.util.Collections.emptySet();
    private java.util.Set<String> hookableOpcodes = java.util.Collections.emptySet();
    private java.util.Set<String> preReturnMethodQualifiers = java.util.Collections.emptySet();
    private java.util.Set<String> postReturnMethodQualifiers = java.util.Collections.emptySet();
    private java.util.Set<String> constVals = java.util.Collections.emptySet();
    private java.util.Set<String> builtInFunctions = java.util.Collections.emptySet();

    /**
     * keeps track of lexer state that we don't want to make the parser aware of
     * (such as comments, which we don't tokenize).
     * the parser then simply passes along the result, once parsing is finished.
     */
    private TokenTableBuilder tableBuilder = new TokenTableBuilder();

    /** public so as to make it accessible from the parser. */
    public ITokenTable tokenTable() {
        return this.tableBuilder.build();
    }

    public void setCastFunctions(java.util.Map<String, CVLCastFunction> castFunctions) {
        this.castFunctions = castFunctions;
    }

    public void setMemoryLocations(java.util.Set<String> memoryLocations) {
        this.memoryLocations = memoryLocations;
    }

    public void setHookableOpcodes(java.util.Set<String> hookableOpcodes) {
        this.hookableOpcodes = hookableOpcodes;
    }

    public void setBuiltInFunctions(java.util.Set<String> builtInFunctions) {
        this.builtInFunctions = builtInFunctions;
    }

    public void setMethodQualifiers(java.util.Set<String> preReturnMethodQualifiers, java.util.Set<String> postReturnMethodQualifiers) {
        this.preReturnMethodQualifiers = preReturnMethodQualifiers;
        this.postReturnMethodQualifiers = postReturnMethodQualifiers;
    }

    public void setConstVals(java.util.Set<String> constVals) {
        this.constVals = constVals;
    }

    private Location startLocation() {
        return new Location(
            theFilename,
            yyline,
            yycolumn,
            yychar
        );
    }

    private Location endLocation() {
        return new Location(
            theFilename,
            yyline,
            yycolumn + yylength(),
            yychar + yylength()
        );
    }

    /*
     * [endLocation] assumes the captures start and end in the same line.
     * for multi-line captures, we must account for which line we end up in,
     * to make sure the line and character we output in the location are actually correct.
     */
    private Location endLocationMultiLine() {
          // defer to `lines` instead of manually counting them
          String[] lines = yytext().lines().toArray(String[]::new);

          int endLine = yyline + lines.length - 1;

          int charInLastLine;
          if (lines.length == 1) {
              charInLastLine = yycolumn + yylength();
          } else {
              charInLastLine = lines[lines.length - 1].length();
          };

          int offset = yychar + yylength();

          return new Location(theFilename, endLine, charInLastLine, offset);
      }

    public ComplexSymbolFactory.ComplexSymbol symbol(int code,String name){
		return new ComplexSymbolFactory.ComplexSymbol(name, code, startLocation(), endLocation(), name);
    }
    public ComplexSymbolFactory.ComplexSymbol symbol(int code, String name, Object lexem){
	    return new ComplexSymbolFactory.ComplexSymbol(name, code, startLocation(), endLocation(), lexem);
    }

    public String theFilename;
%}


LineTerminator = \r|\n|\r\n
WhiteSpace = [ \t\f]
Identifier = [A-Za-z_$][A-Za-z_0-9$]*
DecimalLiteral = [0-9]+
HexLiteral = 0x[0-9A-Fa-f]+
SingleLineComment = \/\/[^\n\r]*
MultiLineComment = \/\*[^*]*\*+([^/*][^*]*\*+)*\/

%%

<YYINITIAL> "sort" {
    debug(" sort",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.SORT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "mapping" {
    debug(" mapping",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.MAPPING, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "ghost" {
    debug(" ghost",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.GHOST, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "persistent" {
    debug(" persistent",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.PERSISTENT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "definition" {
    debug(" definition",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.DEFINITION, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "axiom" {
    debug(" axiom",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.AXIOM, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "hook" {
    debug(" hook",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.HOOK, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "Sload" {
    debug(" Sload",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.SLOAD, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "Sstore" {
    debug(" Sstore",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.SSTORE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "Tload" {
    debug(" Tload",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.TLOAD, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "Tstore" {
    debug(" Tstore",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.TSTORE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "Create" {
    debug(" Create",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.CREATE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "ALWAYS" {
    debug(" ALWAYS",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.ALWAYS, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "CONSTANT" {
    debug(" CONSTANT",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.CONSTANT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "PER_CALLEE_CONSTANT" {
    debug(" PER_CALLEE_CONSTANT",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.PER_CALLEE_CONSTANT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "NONDET" {
    debug(" NONDET",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.NONDET, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "HAVOC_ECF" {
    debug(" HAVOC_ECF",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.HAVOC_ECF, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "HAVOC_ALL" {
    debug(" HAVOC_ALL",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.HAVOC_ALL, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "ASSERT_FALSE" {
    debug(" ASSERT_FALSE",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.ASSERT_FALSE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "AUTO" {
    debug(" AUTO",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.AUTO, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "UNRESOLVED" {
    debug(" UNRESOLVED",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.UNRESOLVED, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "ALL" {
    debug(" ALL",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.ALL, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "DELETE" {
    debug(" DELETE", yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.DELETE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "DISPATCHER" {
   debug(" DISPATCHER", yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.DISPATCHER, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "DISPATCH" {
   debug(" DISPATCH", yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.DISPATCH, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "default" {
   debug(" default", yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.DEFAULT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "norevert" {
    debug(" norevert",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.NOREVERT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "withrevert" {
    debug(" withrevert",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.WITHREVERT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "fallback"	 {
    debug(" FALLBACK");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.FALLBACK, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}

<YYINITIAL> "forall" {
    debug(" forall",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.FORALL, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "exists" {
    debug(" exists",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.EXISTS, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "sum" {
    debug(" sum",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.SUM, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "usum" {
    debug(" usum",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.USUM, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "true" {
    debug(" true",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.TRUE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "false" {
    debug(" false",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.FALSE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "rule" {
    debug(" rule",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.RULE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "unresolved" {
    debug(" unresolved",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.UNRESOLVED_LOWERCASE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "function" {
    debug(" function",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.FUNCTION, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "returns"  {
    debug(" RETURNS");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.RETURNS, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "expect" {
    debug(" EXPECT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.EXPECT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "return" {
    debug(" RETURN");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.RETURN, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "revert" {
    debug(" REVERT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.REVERT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "havoc" {
    debug(" HAVOC");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.HAVOC, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "assuming" {
    debug(" ASSUMING");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.ASSUMING, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "require" {
    debug(" REQUIRE");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.REQUIRE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "requireInvariant" {
    debug(" REQUIREINVARIANT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.REQUIREINVARIANT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "assert" {
    debug(" ASSERT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.ASSERT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "satisfy" {
    debug(" SATISFY");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.SATISFY, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "invariant" {
    debug(" INVARIANT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.INVARIANT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "weak" {
    debug(" WEAK");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.WEAK, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "strong" {
    debug(" STRONG");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.STRONG, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "preserved" {
    debug(" PRESERVED");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.PRESERVED, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "onTransactionBoundary" {
    debug(" ON_TRANSACTION_BOUNDARY");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.ON_TRANSACTION_BOUNDARY, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "methods" {
    debug(" METHODS");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.METHODS, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "description" {
    debug(" DESCRIPTION");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.DESCRIPTION, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "good_description" {
    debug(" GOODDESCRIPTION");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.GOODDESCRIPTION, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "filtered" {
    debug(" FILTERED");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.FILTERED, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "reset_storage"	 {
    debug(" RESET_STORAGE");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.RESET_STORAGE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "if"     {
    debug(" IF");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.IF, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "else"    {
    debug(" ELSE");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.ELSE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "as"    {
    debug(" AS");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.AS, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "using"    {
    debug(" USING");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.USING, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "import"    {
    debug(" IMPORT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.IMPORT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "use"    {
    debug(" USE");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.USE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "builtin"    {
    debug(" builtin");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.BUILTIN, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "override"    {
    debug(" override");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.OVERRIDE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "<"|">"|"<="|">="|"=="|"!=" {
    debug(" Relop %s\n",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.RELOP, "RELOP", yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "+"	{
    debug(" PLUS");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.PLUS, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "-" {
    debug(" MINUS");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.MINUS, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "*" {
    debug(" TIMES");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.TIMES, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "/" {
    debug(" DIV");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.DIV, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "%" {
    debug(" MOD");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.MOD, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "^" {
    debug(" EXPONENT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.EXPONENT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "!" {
    debug(" NOT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.NOT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "||" {
    debug(" LOR");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.LOR, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "&&" {
    debug(" LAND");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.LAND, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "=>" {
    debug(" IMPLIES");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.IMPLIES, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "<=>" {
    debug(" IFF");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.IFF, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "&" {
    debug(" BWAND");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.BWAND, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "|" {
    debug(" BWOR");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.BWOR, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "xor" {
    debug(" BWXOR");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.BWXOR, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "~" {
    debug(" BWNOT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.BWNOT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "<<" {
    debug(" BWLSHIFT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.BWLSHIFT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> ">>" {
    debug(" BWRSHIFT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.BWRSHIFT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> ">>>" {
    debug(" BWRSHIFTWZEROS");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.BWRSHIFTWZEROS, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "->" {
    debug(" MAPSTO");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.MAPSTO, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "in" {
    debug(" IN");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.IN, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "at" {
    debug(" AT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.AT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "with" {
    debug(" WITH");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.WITH, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}

<YYINITIAL> "void" {
   debug(" void");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.VOID, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "@" {
    debug(" @");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.AMPERSAT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "old" {
    debug(" OLD");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.OLD, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "new" {
    debug(" new");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.NEW, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}

<YYINITIAL> "lastStorage" {
    debug(" lastStorage");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.LAST_STORAGE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "lastReverted" {
    debug(" lastReverted");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.LAST_REVERTED, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "sig" {
    debug(" SIG");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.SIG, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "="	{
    debug(" ASSIGN");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.ASSIGN, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> ","	{
    debug(" COMMA");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.COMMA, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> ":" {
    debug(" COLON");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.COLON, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> ";"  {
    debug(" SC");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.SC, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "STORAGE"  {
    debug(" STORAGE");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.STORAGE, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "{"	{
    debug(" LB");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.LB, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "}"	{
    debug(" RB");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.RB, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "["	{
    debug(" LSQB");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.LSQB, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "]"	{
    debug(" RSQB");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.RSQB, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "("	{
    debug(" LP");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.LP, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> ")"	{
    debug(" RP");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.RP, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "?" {
    debug(" QUESTION");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.QUESTION, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> "."	{
    debug(" DOT");
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.DOT, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> {Identifier} {
    debug(" ID %s\n", yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol;
    if (castFunctions.get(yytext()) != null) {
        currentSymbol = symbol(sym.CAST, "CAST", castFunctions.get(yytext()));
    } else if (memoryLocations.contains(yytext())) {
        currentSymbol = symbol(sym.LOCATION, "LOCATION", yytext());
    } else if (hookableOpcodes.contains(yytext())) {
        currentSymbol = symbol(sym.OPCODE, "OPCODE", yytext());
    } else if (preReturnMethodQualifiers.contains(yytext())) {
        currentSymbol = symbol(sym.PRE_RETURN_QUALIFIER, "PRE_RETURN_QUALIFIER", yytext());
    } else if (postReturnMethodQualifiers.contains(yytext())) {
        currentSymbol = symbol(sym.POST_RETURN_QUALIFIER, "POST_RETURN_QUALIFIER", yytext());
    } else if(builtInFunctions.contains(yytext())) {
        currentSymbol = symbol(sym.BIF, "BIF", yytext());
    } else if (constVals.contains(yytext())) {
        currentSymbol = symbol(sym.CONST_VAL, "CONST_VAL", yytext());
    } else {
        currentSymbol = symbol(sym.ID, "ID", yytext());
    }
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> {DecimalLiteral} {
    debug(" DecimalLiteral=%s ",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.NUMBER, "NUMBER", yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> {HexLiteral} {
    debug(" HexLiteral=%s ",yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.NUMBER, "NUMBER", yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> {SingleLineComment} {
    tableBuilder.registerComment(startLocation(), endLocation(), yytext(), false);
    // no token emitted for this case
}
<YYINITIAL> {MultiLineComment} {
    tableBuilder.registerComment(startLocation(), endLocationMultiLine(), yytext(), true);
    // no token emitted for this case
}
<YYINITIAL> \"[^\"]*\"  {
    debug(" String%s\n", yytext());
    ComplexSymbolFactory.ComplexSymbol currentSymbol = symbol(sym.STRING, yytext());
    tableBuilder.registerTokenEmit(currentSymbol);
    return currentSymbol;
}
<YYINITIAL> {LineTerminator}+ {
    tableBuilder.registerLineBreaks(startLocation(), endLocation(), yytext());
    // no token emitted for this case
}
<YYINITIAL> {WhiteSpace}+ {
    tableBuilder.registerWhitespace(startLocation(), endLocation(), yytext());
    // n.b. that this has lower priority than `LineTerminator`
    // no token emitted for this case
}
/* error fallback */
<YYINITIAL> [^] {
    debug("error " + this.theFilename + ":" + (yyline+1) + ":" + (yycolumn+1) + ": " + "Illegal character: " + yytext());
}
<<EOF>>    {
    tableBuilder.registerEOF(startLocation(), endLocation());
    return symbol(sym.EOF, "EOF");
}
