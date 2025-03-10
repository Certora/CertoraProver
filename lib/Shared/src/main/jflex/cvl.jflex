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

    public Symbol symbol(int code,String name){
		return symbolFactory.newSymbol(name, code,
						new Location(theFilename,yyline,yycolumn, yychar), // -yylength()
						new Location(theFilename,yyline,yycolumn+yylength(), yychar+yylength()), name);
    }
    public Symbol symbol(int code, String name, Object lexem){
	return symbolFactory.newSymbol(name, code,
						new Location(theFilename,yyline, yycolumn, yychar),
						new Location(theFilename,yyline,yycolumn+yylength(), yychar+yylength()), lexem);
    }

    public String theFilename;
%}


LineTerminator = \r|\n|\r\n
WhiteSpace     = {LineTerminator} | [ \t\f]
Identifier = [A-Za-z_$][A-Za-z_0-9$]*
DecimalLiteral = [0-9]+
HexLiteral = 0x[0-9A-Fa-f]+
%xstate COMMENT

%%

<YYINITIAL> "sort" {
    debug(" sort",yytext());
    return symbol(sym.SORT,yytext());
}
<YYINITIAL> "mapping" {
    debug(" mapping",yytext());
    return symbol(sym.MAPPING,yytext());
}
<YYINITIAL> "ghost" {
    debug(" ghost",yytext());
    return symbol(sym.GHOST,yytext());
}
<YYINITIAL> "persistent" {
    debug(" persistent",yytext());
    return symbol(sym.PERSISTENT,yytext());
}
<YYINITIAL> "definition" {
    debug(" definition",yytext());
    return symbol(sym.DEFINITION,yytext());
}
<YYINITIAL> "axiom" {
    debug(" axiom",yytext());
    return symbol(sym.AXIOM,yytext());
}
<YYINITIAL> "hook" {
    debug(" hook",yytext());
    return symbol(sym.HOOK,yytext());
}
<YYINITIAL> "Sload" {
    debug(" Sload",yytext());
    return symbol(sym.SLOAD,yytext());
}
<YYINITIAL> "Sstore" {
    debug(" Sstore",yytext());
    return symbol(sym.SSTORE,yytext());
}
<YYINITIAL> "Tload" {
    debug(" Tload",yytext());
    return symbol(sym.TLOAD,yytext());
}
<YYINITIAL> "Tstore" {
    debug(" Tstore",yytext());
    return symbol(sym.TSTORE,yytext());
}
<YYINITIAL> "Create" {
    debug(" Create",yytext());
    return symbol(sym.CREATE,yytext());
}
<YYINITIAL> "ALWAYS" {
    debug(" ALWAYS",yytext());
    return symbol(sym.ALWAYS,yytext());
}
<YYINITIAL> "CONSTANT" {
    debug(" CONSTANT",yytext());
    return symbol(sym.CONSTANT,yytext());
}
<YYINITIAL> "PER_CALLEE_CONSTANT" {
    debug(" PER_CALLEE_CONSTANT",yytext());
    return symbol(sym.PER_CALLEE_CONSTANT,yytext());
}
<YYINITIAL> "NONDET" {
    debug(" NONDET",yytext());
    return symbol(sym.NONDET,yytext());
}
<YYINITIAL> "HAVOC_ECF" {
    debug(" HAVOC_ECF",yytext());
    return symbol(sym.HAVOC_ECF,yytext());
}
<YYINITIAL> "HAVOC_ALL" {
    debug(" HAVOC_ALL",yytext());
    return symbol(sym.HAVOC_ALL,yytext());
}
<YYINITIAL> "ASSERT_FALSE" {
    debug(" ASSERT_FALSE",yytext());
    return symbol(sym.ASSERT_FALSE,yytext());
}
<YYINITIAL> "AUTO" {
    debug(" AUTO",yytext());
    return symbol(sym.AUTO,yytext());
}
<YYINITIAL> "UNRESOLVED" {
    debug(" UNRESOLVED",yytext());
    return symbol(sym.UNRESOLVED,yytext());
}
<YYINITIAL> "ALL" {
    debug(" ALL",yytext());
    return symbol(sym.ALL,yytext());
}
<YYINITIAL> "DELETE" {
    debug(" DELETE", yytext());
    return symbol(sym.DELETE, yytext());
}
<YYINITIAL> "DISPATCHER" {
   debug(" DISPATCHER", yytext());
   return symbol(sym.DISPATCHER, yytext());
}
<YYINITIAL> "DISPATCH" {
   debug(" DISPATCH", yytext());
   return symbol(sym.DISPATCH, yytext());
}
<YYINITIAL> "default" {
   debug(" default", yytext());
   return symbol(sym.DEFAULT, yytext());
}
<YYINITIAL> "norevert" {
    debug(" norevert",yytext());
    return symbol(sym.NOREVERT, yytext());
}
<YYINITIAL> "withrevert" {
    debug(" withrevert",yytext());
   return symbol(sym.WITHREVERT, yytext());
}
<YYINITIAL> "fallback"	 {
    debug(" FALLBACK");
    return symbol(sym.FALLBACK,yytext());
}

<YYINITIAL> "forall" {
    debug(" forall",yytext());
    return symbol(sym.FORALL,yytext());
}
<YYINITIAL> "exists" {
    debug(" exists",yytext());
    return symbol(sym.EXISTS,yytext());
}
<YYINITIAL> "sum" {
    debug(" sum",yytext());
    return symbol(sym.SUM,yytext());
}
<YYINITIAL> "usum" {
    debug(" usum",yytext());
    return symbol(sym.USUM,yytext());
}
<YYINITIAL> "true" {
    debug(" true",yytext());
    return symbol(sym.TRUE,yytext());
}
<YYINITIAL> "false" {
    debug(" false",yytext());
    return symbol(sym.FALSE,yytext());
}
<YYINITIAL> "rule" {
    debug(" rule",yytext());
    return symbol(sym.RULE,yytext());
}
<YYINITIAL> "unresolved" {
    debug(" unresolved",yytext());
    return symbol(sym.UNRESOLVED_LOWERCASE,yytext());
}
<YYINITIAL> "function" {
    debug(" function",yytext());
    return symbol(sym.FUNCTION,yytext());
}
<YYINITIAL> "returns"  {
    debug(" RETURNS");
    return symbol(sym.RETURNS,yytext());
}
<YYINITIAL> "expect" {
    debug(" EXPECT");
    return symbol(sym.EXPECT,yytext());
}
<YYINITIAL> "return" {
    debug(" RETURN");
    return symbol(sym.RETURN,yytext());
}
<YYINITIAL> "revert" {
    debug(" REVERT");
    return symbol(sym.REVERT,yytext());
}
<YYINITIAL> "havoc" {
    debug(" HAVOC");
    return symbol(sym.HAVOC,yytext());
}
<YYINITIAL> "assuming" {
    debug(" ASSUMING");
    return symbol(sym.ASSUMING,yytext());
}
<YYINITIAL> "require" {
    debug(" REQUIRE");
    return symbol(sym.REQUIRE,yytext());
}
<YYINITIAL> "requireInvariant" {
    debug(" REQUIREINVARIANT");
    return symbol(sym.REQUIREINVARIANT,yytext());
}
<YYINITIAL> "assert" {
    debug(" ASSERT");
    return symbol(sym.ASSERT,yytext());
}
<YYINITIAL> "satisfy" {
    debug(" SATISFY");
    return symbol(sym.SATISFY,yytext());
}
<YYINITIAL> "invariant" {
    debug(" INVARIANT");
    return symbol(sym.INVARIANT,yytext());
}
<YYINITIAL> "weak" {
    debug(" WEAK");
    return symbol(sym.WEAK,yytext());
}
<YYINITIAL> "strong" {
    debug(" STRONG");
    return symbol(sym.STRONG,yytext());
}
<YYINITIAL> "preserved" {
    debug(" PRESERVED");
    return symbol(sym.PRESERVED,yytext());
}
<YYINITIAL> "onTransactionBoundary" {
    debug(" ON_TRANSACTION_BOUNDARY");
    return symbol(sym.ON_TRANSACTION_BOUNDARY,yytext());
}
<YYINITIAL> "methods" {
    debug(" METHODS");
    return symbol(sym.METHODS,yytext());
}
<YYINITIAL> "description" {
    debug(" DESCRIPTION");
    return symbol(sym.DESCRIPTION,yytext());
}
<YYINITIAL> "good_description" {
    debug(" GOODDESCRIPTION");
    return symbol(sym.GOODDESCRIPTION,yytext());
}
<YYINITIAL> "filtered" {
    debug(" FILTERED");
    return symbol(sym.FILTERED,yytext());
}
<YYINITIAL> "reset_storage"	 {
    debug(" RESET_STORAGE");
    return symbol(sym.RESET_STORAGE,yytext());
}
<YYINITIAL> "if"     {
    debug(" IF");
    return symbol(sym.IF,yytext());
}
<YYINITIAL> "else"    {
    debug(" ELSE");
    return symbol(sym.ELSE,yytext());
}
<YYINITIAL> "as"    {
    debug(" AS");
    return symbol(sym.AS,yytext());
}
<YYINITIAL> "using"    {
    debug(" USING");
    return symbol(sym.USING,yytext());
}
<YYINITIAL> "import"    {
    debug(" IMPORT");
    return symbol(sym.IMPORT,yytext());
}
<YYINITIAL> "use"    {
    debug(" USE");
    return symbol(sym.USE,yytext());
}
<YYINITIAL> "builtin"    {
    debug(" builtin");
    return symbol(sym.BUILTIN,yytext());
}
<YYINITIAL> "override"    {
    debug(" override");
    return symbol(sym.OVERRIDE,yytext());
}
<YYINITIAL> "<"|">"|"<="|">="|"=="|"!=" {
    debug(" Relop %s\n",yytext());
    return symbol(sym.RELOP,"RELOP",yytext());
}
<YYINITIAL> "+"	{
    debug(" PLUS");
    return symbol(sym.PLUS,yytext());
}
<YYINITIAL> "-" {
    debug(" MINUS");
    return symbol(sym.MINUS,yytext());
}
<YYINITIAL> "*" {
    debug(" TIMES");
    return symbol(sym.TIMES,yytext());
}
<YYINITIAL> "/" {
    debug(" DIV");
    return symbol(sym.DIV,yytext());
}
<YYINITIAL> "%" {
    debug(" MOD");
    return symbol(sym.MOD,yytext());
}
<YYINITIAL> "^" {
    debug(" EXPONENT");
    return symbol(sym.EXPONENT,yytext());
}
<YYINITIAL> "!" {
    debug(" NOT");
    return symbol(sym.NOT,yytext());
}
<YYINITIAL> "||" {
    debug(" LOR");
    return symbol(sym.LOR,yytext());
}
<YYINITIAL> "&&" {
    debug(" LAND");
    return symbol(sym.LAND,yytext());
}
<YYINITIAL> "=>" {
    debug(" IMPLIES");
    return symbol(sym.IMPLIES,yytext());
}
<YYINITIAL> "<=>" {
    debug(" IFF");
    return symbol(sym.IFF,yytext());
}
<YYINITIAL> "&" {
    debug(" BWAND");
    return symbol(sym.BWAND,yytext());
}
<YYINITIAL> "|" {
    debug(" BWOR");
    return symbol(sym.BWOR,yytext());
}
<YYINITIAL> "xor" {
    debug(" BWXOR");
    return symbol(sym.BWXOR,yytext());
}
<YYINITIAL> "~" {
    debug(" BWNOT");
    return symbol(sym.BWNOT,yytext());
}
<YYINITIAL> "<<" {
    debug(" BWLSHIFT");
    return symbol(sym.BWLSHIFT,yytext());
}
<YYINITIAL> ">>" {
    debug(" BWRSHIFT");
    return symbol(sym.BWRSHIFT,yytext());
}
<YYINITIAL> ">>>" {
    debug(" BWRSHIFTWZEROS");
    return symbol(sym.BWRSHIFTWZEROS,yytext());
}
<YYINITIAL> "->" {
    debug(" MAPSTO");
    return symbol(sym.MAPSTO,yytext());
}
<YYINITIAL> "in" {
    debug(" IN");
    return symbol(sym.IN,yytext());
}
<YYINITIAL> "at" {
    debug(" AT");
    return symbol(sym.AT,yytext());
}
<YYINITIAL> "with" {
    debug(" WITH");
    return symbol(sym.WITH,yytext());
}

<YYINITIAL> "void" {
   debug(" void");
   return symbol(sym.VOID,yytext());
}
<YYINITIAL> "@" {
    debug(" @");
    return symbol(sym.AMPERSAT,yytext());
}
<YYINITIAL> "old" {
    debug(" OLD");
    return symbol(sym.OLD,yytext());
}
<YYINITIAL> "new" {
    debug(" new");
    return symbol(sym.NEW,yytext());
}
// keywords which are always coming as separated tokens.
// for example, "msg" is a keyword, but not a one which is always coming separated.
// why not? for example, in e.msg.sender, msg is not coming alone but is attached to e.
// we would like in the future to have here also keywords such as msg, more changes for the cup
// file are required.
<YYINITIAL> "lastStorage"|"lastHasThrown"|"lastReverted"
{
    debug(" KEYWORD %s",yytext());
    return symbol(sym.KEYWORD,"KEYWORD",yytext());
}

<YYINITIAL> "sig" {
    debug(" SIG");
    return symbol(sym.SIG, yytext());
}

<YYINITIAL> "="	{
    debug(" ASSIGN");
    return symbol(sym.ASSIGN,yytext());
}
<YYINITIAL> ","	{
    debug(" COMMA");
    return symbol(sym.COMMA,yytext());
}
<YYINITIAL> ":" {
    debug(" COLON");
    return symbol(sym.COLON,yytext());
}
<YYINITIAL> ";"  {
    debug(" SC");
    return symbol(sym.SC,yytext());
}
<YYINITIAL> "STORAGE"  {
    debug(" STORAGE");
    return symbol(sym.STORAGE,yytext());
}
<YYINITIAL> "{"	{
    debug(" LB");
    return symbol(sym.LB,yytext());
}
<YYINITIAL> "}"	{
    debug(" RB");
    return symbol(sym.RB,yytext());
}
<YYINITIAL> "["	{
    debug(" LSQB");
    return symbol(sym.LSQB,yytext());
}
<YYINITIAL> "]"	{
    debug(" RSQB");
    return symbol(sym.RSQB,yytext());
}
<YYINITIAL> "("	{
    debug(" LP");
    return symbol(sym.LP,yytext());
}
<YYINITIAL> ")"	{
    debug(" RP");
    return symbol(sym.RP,yytext());
}
<YYINITIAL> "?" {
    debug(" QUESTION");
    return symbol(sym.QUESTION,yytext());
}
<YYINITIAL> "."	{
    debug(" DOT");
    return symbol(sym.DOT,yytext());
}
<YYINITIAL> {Identifier} {
    debug(" ID %s\n", yytext());
    if (castFunctions.get(yytext()) != null) {
        return symbol(sym.CAST, "CAST", castFunctions.get(yytext()));
    } else if (memoryLocations.contains(yytext())) {
        return symbol(sym.LOCATION, "LOCATION", yytext());
    } else if (hookableOpcodes.contains(yytext())) {
        return symbol(sym.OPCODE, "OPCODE", yytext());
    } else if (preReturnMethodQualifiers.contains(yytext())) {
        return symbol(sym.PRE_RETURN_QUALIFIER, "PRE_RETURN_QUALIFIER", yytext());
    } else if (postReturnMethodQualifiers.contains(yytext())) {
        return symbol(sym.POST_RETURN_QUALIFIER, "POST_RETURN_QUALIFIER", yytext());
    } else if(builtInFunctions.contains(yytext())) {
        return symbol(sym.BIF, "BIF", yytext());
    } else if (constVals.contains(yytext())) {
        return symbol(sym.CONST_VAL, "CONST_VAL", yytext());
    } else {
        return symbol(sym.ID, "ID", yytext());
    }
}
<YYINITIAL> {DecimalLiteral} {
    debug(" DecimalLiteral=%s ",yytext());
    return symbol(sym.NUMBER,"NUMBER",yytext());
}
<YYINITIAL> {HexLiteral} {
    debug(" HexLiteral=%s ",yytext());
    return symbol(sym.NUMBER,"NUMBER",yytext());
}
<YYINITIAL> "//"[^\n\r]* { ; }
<YYINITIAL> "/*"	{
    debug(" Begin Comment moving to state %d", COMMENT);
    yybegin(COMMENT);
}
/*<YYINITIAL> {LineTerminator}		{LineNumber++;}*/
<YYINITIAL> {WhiteSpace}	{;}
<YYINITIAL> \"[^\"]*\"  {
    debug(" String%s\n", yytext());
    return symbol(sym.STRING,"STRING",yytext());
}
<COMMENT> [*]+"/"  {
    debug("\n End Comment returning to state %d\n", YYINITIAL);
    yybegin(YYINITIAL);
}
//<COMMENT> [ \n]  {debug("%s",yytext());}
<COMMENT> [^*] | (\*+[^*/])   {
    debug("\ncontinue comment %s",yytext());
}
/* error fallback */
<YYINITIAL> [^] {
    System.out.println("error " + this.theFilename + ":" + (yyline+1) + ":" + (yycolumn+1) + ": " + "Illegal character: " + yytext());
}
<<EOF>>    { return symbol(sym.EOF, "EOF"); }
