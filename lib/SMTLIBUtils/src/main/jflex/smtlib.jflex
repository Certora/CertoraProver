package com.certora.smtlibutils.smtlib;

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

import java_cup.runtime.ComplexSymbolFactory;
import java_cup.runtime.ComplexSymbolFactory.Location;
import java_cup.runtime.Symbol;

%%


%class LexerSMT
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

	public LexerSMT(ComplexSymbolFactory sf, java.io.Reader reader){
		this(reader);
        symbolFactory = sf;
    }

    private StringBuffer sb;
    private ComplexSymbolFactory symbolFactory;
    private int csline,cscolumn;

    public Symbol symbol(int code,String name){
		return symbolFactory.newSymbol(name, code,
						new Location(yyline+1,yycolumn+1, yychar), // -yylength()
						new Location(yyline+1,yycolumn+yylength(), yychar+yylength())
				);
    }
    public Symbol symbol(int code, String name, String lexem){
	return symbolFactory.newSymbol(name, code,
						new Location(yyline+1, yycolumn +1, yychar),
						new Location(yyline+1,yycolumn+yylength(), yychar+yylength()), lexem);
    }

    protected void emit_warning(String message){
    	System.out.println("scanner warning: " + message + " at : 2 "+
    			(yyline+1) + " " + (yycolumn+1) + " " + yychar);
    }

    protected void emit_error(String message){
    	System.out.println("scanner error: " + message + " at : 2" +
    			(yyline+1) + " " + (yycolumn+1) + " " + yychar);
    }
%}


LineTerminator = [\r\n]|\r\n
WhiteSpace     = {LineTerminator}|[ \t\f]
ExclamationMark = "!"
Symbol = [\w~!@$%\^&*_\-+=<>.?/][\w\d~!@$%\^&*_\-+=<>.?/]*
QuotedSymbol = "|"[^\|\\]*"|"
StatIdentifier = ":"[\w~!@$%\^&*_\-+=<>.?/][\w\d~!@$%\^&*_\-+=<>.?/]*
IntegerLiteral = [0-9]+
DecimalLiteral = [0-9]+("."[0-9]+)*
HexLiteral = "#x"[0-9a-fA-F]+
BinaryLiteral = "#b"[01]+
Comment = ";".*{LineTerminator}
%%

<YYINITIAL> "true" {
    debug(" true",yytext());
    return symbolFactory.newSymbol("TRUE",sym.TRUE,yytext());
}
<YYINITIAL> "false" {
    debug(" false",yytext());
    return symbolFactory.newSymbol("FALSE",sym.FALSE,yytext());
}
<YYINITIAL> "model" {
    debug(" model",yytext());
    return symbolFactory.newSymbol("MODEL",sym.MODEL,yytext());
}
<YYINITIAL> "goals" {
    debug(" goals",yytext());
    return symbolFactory.newSymbol("GOALS",sym.GOALS,yytext());
}
<YYINITIAL> "goal" {
    debug(" goal",yytext());
    return symbolFactory.newSymbol("GOAL",sym.GOAL,yytext());
}
<YYINITIAL> "define-fun" {
    debug(" define-fun",yytext());
    return symbolFactory.newSymbol("DEFINEFUN",sym.DEFINEFUN,yytext());
}
<YYINITIAL> "declare-fun" {
    debug(" declare-fun",yytext());
    return symbolFactory.newSymbol("DECLAREFUN",sym.DECLAREFUN,yytext());
}
<YYINITIAL> "declare-const" {
    debug(" declare-const",yytext());
    return symbolFactory.newSymbol("DECLARECONST",sym.DECLARECONST,yytext());
}
<YYINITIAL> "declare-sort" {
    debug(" declare-sort",yytext());
    return symbolFactory.newSymbol("DECLARESORT",sym.DECLARESORT,yytext());
}
<YYINITIAL> "declare-datatypes" {
    debug(" declare-datatypes",yytext());
    return symbolFactory.newSymbol("DECLAREDATATYPES",sym.DECLAREDATATYPES,yytext());
}
<YYINITIAL> "set-logic" {
    debug(" set-logic",yytext());
    return symbolFactory.newSymbol("SET_LOGIC",sym.SET_LOGIC,yytext());
}
<YYINITIAL> "set-option" {
    debug(" set-option",yytext());
    return symbolFactory.newSymbol("SET_OPTION",sym.SET_OPTION,yytext());
}
<YYINITIAL> "assert" {
    debug(" assert",yytext());
    return symbolFactory.newSymbol("ASSERT",sym.ASSERT,yytext());
}
<YYINITIAL> "check-sat" {
    debug(" check-sat",yytext());
    return symbolFactory.newSymbol("CHECK_SAT",sym.CHECK_SAT,yytext());
}
<YYINITIAL> "get-model" {
    debug(" get-model",yytext());
    return symbolFactory.newSymbol("GET_MODEL",sym.GET_MODEL,yytext());
}
<YYINITIAL> "get-unsat-core" {
    debug(" get-unsat-core",yytext());
    return symbolFactory.newSymbol("GET_UNSAT_CORE",sym.GET_UNSAT_CORE,yytext());
}
<YYINITIAL> "get-info" {
    debug(" get-info",yytext());
    return symbolFactory.newSymbol("GET_INFO",sym.GET_INFO,yytext());
}
<YYINITIAL> "reset" {
    debug(" reset",yytext());
    return symbolFactory.newSymbol("RESET",sym.RESET,yytext());
}
<YYINITIAL> "reset-assertions" {
    debug(" reset-assertions",yytext());
    return symbolFactory.newSymbol("RESET_ASSERTIONS",sym.RESET_ASSERTIONS,yytext());
}
<YYINITIAL> "push" {
    debug(" push",yytext());
    return symbolFactory.newSymbol("PUSH",sym.PUSH,yytext());
}
<YYINITIAL> "pop" {
    debug(" pop",yytext());
    return symbolFactory.newSymbol("POP",sym.POP,yytext());
}
<YYINITIAL> "as" {
    debug(" as",yytext());
    return symbolFactory.newSymbol("AS",sym.AS,yytext());
}
<YYINITIAL> "lambda" {
    debug(" lambda",yytext());
    return symbolFactory.newSymbol("LAMBDA",sym.LAMBDA,yytext());
}
<YYINITIAL> "let" {
    debug(" let",yytext());
    return symbolFactory.newSymbol("LET",sym.LET,yytext());
}
<YYINITIAL> "_"  {
    debug(" _");
    return symbol(sym.UNDERSCORE,yytext());
}
<YYINITIAL> "MAP_TO_EVM_BV256" {
     debug(" MAP_TO_EVM_BV256");
     return symbol(sym.MAP_TO_EVM_BV256,yytext());
}
<YYINITIAL> "("	{
    debug(" LP");
    return symbol(sym.LP,yytext());
}
<YYINITIAL> ")"	{
    debug(" RP");
    return symbol(sym.RP,yytext());
}
<YYINITIAL> "forall"	{
    debug(" forall");
    return symbol(sym.FORALL,yytext());
}
<YYINITIAL> "exists"	{
    debug(" exists");
    return symbol(sym.EXISTS,yytext());
}
<YYINITIAL> {IntegerLiteral} {
    debug(" IntegerLiteral=%s ",yytext());
    return symbol(sym.NUMBER,"NUMBER",yytext());
}
<YYINITIAL> {DecimalLiteral} {
    debug(" DecimalLiteral=%s ",yytext());
    return symbol(sym.DEC,"DEC",yytext());
}
<YYINITIAL> {HexLiteral} {
    debug(" HexLiteral=%s ",yytext());
    return symbol(sym.HEX,"HEX",yytext());
}
<YYINITIAL> {BinaryLiteral} {
    debug(" BinaryLiteral=%s ",yytext());
    return symbol(sym.BINARY,"BINARY",yytext());
}
<YYINITIAL> {ExclamationMark} {
    debug(" ExclamationMark %s\n", yytext());
    return symbol(sym.EXCLAMATION_MARK,"EXCLAMATION_MARK",yytext());
}
<YYINITIAL> {Symbol} {
    debug(" SYM %s\n", yytext());
    return symbol(sym.SYM,"SYM",yytext());
}
<YYINITIAL> {QuotedSymbol} {
    String tmp = yytext();
    tmp = tmp.substring(1, tmp.length()-1);
    debug(" SYM %s\n", tmp);
    return symbol(sym.SYM,"SYM", tmp);
}
<YYINITIAL> {StatIdentifier} {
    debug(" STAT_SYM %s\n", yytext());
    return symbol(sym.STAT_SYM,"STAT_SYM",yytext());
}
<YYINITIAL> {Comment} { ; }
<YYINITIAL> {WhiteSpace}	{;}
/* error fallback */
<YYINITIAL> [^] {
    String msg = "Illegal character <"+yytext()+">";
    emit_error(msg);
    throw new Error(msg);
}

<<EOF>>    { return symbol(sym.EOF, "EOF"); }
