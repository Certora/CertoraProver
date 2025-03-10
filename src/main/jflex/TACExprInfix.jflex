package vc.data.parser.infix;

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
import java.util.Stack;
import java.io.IOException;
import tac.Tag;
/**
* A lexer for TACInfixExpr.cup. Interdependent with TACInfixExpr.cup.
* inherits from __baseTAC.jflex the patterns (with the %include directive)
*/
%%
%class TacInfixLexer
%public
%cup
%char
%line
%column
%include __baseTAC.jflex
%{

        public TacInfixLexer(ComplexSymbolFactory sf, java.io.Reader reader) {
          this(reader);
          symbolFactory = sf;
          yybegin(S_Parse_Expression);
        }
%}


%xstate COMMENT
%state S_Parse_Expression
%xstate S_ParseJSON
%xstate JSON_STRING_BEGIN, EXPRESSION_STRING_BEGIN

%%
/* keywords */
<<EOF>>             { return symbol(sym.EOF, "EOF"); }
"ufAttribute"       { return symbol(sym.UFATTRIBUTE, yytext());}
"ghostmap"      {return symbol(sym.GHOSTMAP, yytext());}
"="              { return symbol(sym.EQUALS, yytext());}
"=="            {return symbol(sym.EQUALITY_OP, yytext());}
"<"             {return symbol(sym.LT, yytext());}
">"             {return symbol(sym.GT, yytext());}
"<="            {return symbol(sym.LE, yytext());}
">="            {return symbol(sym.GE, yytext());}
"!"             {return symbol(sym.NOT, yytext());}
":"                 { return symbol(sym.COLON, yytext());}
"{"                 { return symbol(sym.LBRACE, yytext()); }
"}"                 { return symbol(sym.RBRACE, yytext()); }
","                 {return symbol(sym.COMMA, yytext());}
"("	{debug("LP");return symbol(sym.LP,yytext());}
")"	{debug("RP");return symbol(sym.RP,yytext());}
"@" {debug("@");return symbol(sym.AT, yytext());}
"->"                {return symbol(sym.ARROW, yytext());}
"*" {return symbol(sym.CROSS, yytext());}
"+" {return symbol(sym.PLUS, yytext());}
"-" {return symbol(sym.MINUS, yytext());}
"/" {return symbol(sym.DIV, yytext());}
"(uninterp)"        {;}
<YYINITIAL, S_Parse_Expression> "["	{ debug(" ["); return symbol(sym.LSQB,yytext());}
<YYINITIAL, S_Parse_Expression> "]"	{ debug(" ]"); return symbol(sym.RSQB,yytext());}
<YYINITIAL, S_Parse_Expression> {BlockIdentifier}   {debug(" blockId", yytext()); return symbol(sym.BLOCKID, "BlockId", yytext());}
"//"[^\n\r]* { ; }
"/*"	{        debug(String.format(" Begin Comment moving to state %d", COMMENT));
               startState(COMMENT);}
<S_Parse_Expression> {
    "{"              { return symbol(sym.LBRACE, yytext());}
    "}"              { return symbol(sym.RBRACE, yytext());}
    "="              { return symbol(sym.EQUALS, yytext());}
    "(uninterp)"        {;}
    //specific parsing exprFunctions:
    "Unconstrained"   { return symbol(sym.UNCONSTRAINED, yytext());}
    "StructConstant"  { return symbol(sym.STRUCTCONSTANT, yytext());}
    "StructAccess"    { return symbol(sym.STRUCTACCESS, yytext());}
    "Forall"          { return symbol(sym.FORALL, yytext());}
    "Exists"          { return symbol(sym.EXISTS, yytext());}
    "QVars"               { return symbol(sym.QVARS, yytext()); }
    "Apply"           { return symbol(sym.APPLYFN, yytext());}
    {DecimalLiteral}         {return symbol(sym.NUMBER,"number", Integer.valueOf(yytext()));}
    {booleanLiteral} {return symbol(sym.BOOL, "bool", yytext());}
    {expressionFunctions} { debug("expression function:", yytext());return symbol(sym.EXPRESSION_FUNC, "expressionFunctions", yytext()); }
    {Hex} {debug("hex:", yytext());return symbol(sym.HEX, "Hex", yytext());}
    "@"   { debug("@");return symbol(sym.AT, yytext());}
    {WhiteSpaceNoLineTerminator} {;}
    //TODO: new line in expressions? should I allow this? maybe a command/axiom keyword should start a new Expression parse
    {LineTerminator} {debug(yytext());startState(YYINITIAL);}
    "\""               {debug("begin string in expression", yytext()); sb = null; sb = new StringBuffer();startState(EXPRESSION_STRING_BEGIN);}
    {tagName}               { return symbol(sym.TYPE_TAG, "typeTag", yytext()); }
    {TACVarIdentifier} {debug("expression parsing Identifier", yytext()); return symbol(sym.IDENTIFIER, "Identifier", yytext());}
    [^] { throw new Error("Illegal character <"+
          yytext()+"> in line: " + (yyline+1) + ", col: " + (yycolumn+1) ); }
    } //<S_Parse_Expression>


    {WhiteSpace}	{;}
    {TACVarIdentifier}      { return symbol(sym.IDENTIFIER, "Identifier", yytext()); }
    {DecIntegerLiteral}     { return symbol(sym.NUMBER, "number", Integer.valueOf(yytext()));}
    <COMMENT> [*]+"/"  {
            debug(String.format("\n End Comment returning to state %d\n", YYINITIAL));
            startState(YYINITIAL);
        }
    //<COMMENT> [ \n]  {debug("%s",yytext());}
    <COMMENT> [^*] | (\*+[^*/])   {
        debug("\ncontinue comment %s",yytext());
    }

<EXPRESSION_STRING_BEGIN> "\""   { startState(S_Parse_Expression); return symbol(sym.STRING_LITERAL,"stringLiteral" ,sb.toString());}//return new symbol(Yytoken.TYPE_VALUE, sb.toString());}
<EXPRESSION_STRING_BEGIN> {UNESCAPED_CH}	{sb.append(yytext());}
<EXPRESSION_STRING_BEGIN> \\\" 			{sb.append('"');}
<EXPRESSION_STRING_BEGIN> \\\\				{sb.append('\\');}
<EXPRESSION_STRING_BEGIN> \\\/				{sb.append('/');}
<EXPRESSION_STRING_BEGIN> \\b				{sb.append('\b');}
<EXPRESSION_STRING_BEGIN> \\f				{sb.append('\f');}
<EXPRESSION_STRING_BEGIN> \\n				{sb.append('\n');}
<EXPRESSION_STRING_BEGIN> \\r				{sb.append('\r');}
<EXPRESSION_STRING_BEGIN> \\t				{sb.append('\t');}

/* error fallback */
[^]                              { throw new IOException("Illegal character <"+
                                                        yytext()+"> in line: " + (yyline+1) + ", col: " + (yycolumn+1) + "File: " + theFilename); }
