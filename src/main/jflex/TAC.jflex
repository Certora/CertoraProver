package tac;

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
/**
* A lexer for Tac program generated with JFlex -
* Interdependent with TAC.cup.
* "inherits" from __baseTAC.jflex (using %include directive)
*/
%%
%class TacLexer
%public
%cup
%char
%line
%column
%include __baseTAC.jflex
%{
        public TacLexer(ComplexSymbolFactory sf, java.io.Reader reader) {
          this(reader);
          symbolFactory = sf;
        }

%}

%xstate COMMENT
%state S_Parse_Expression
%xstate S_ParseJSON
%xstate JSON_STRING_CONSUMING, STRING_LITERAL_CONSUMING

%%
/* keywords */
<<EOF>>             { return symbol(sym.EOF, "EOF"); }
"Program"           { debug("Program",yytext()); return symbol(sym.PROGRAM, yytext()); }
<S_Parse_Expression, YYINITIAL> "Meta"  { return symbol(sym.META, yytext());}
"Metas {"  { debug("Metas", yytext()); startJson('{'); return symbol(sym.METAS, yytext(), yychar); }
"TACSymbolTable"         { debug("TACSymbolTable", yytext());return symbol(sym.TACSYMBOLTABLE, yytext()); }
"Axioms"            { debug("Axioms", yytext())  ;return symbol(sym.AXIOMS, yytext()); }
"Axiom "  { debug ("Axiom", yytext()); startState(S_Parse_Expression); return symbol(sym.AXIOM, yytext());}
{CmdName} { debug("CmdName", yytext());startState(S_Parse_Expression); return symbol(sym.COMMAND_NAME, "TACCmd", yytext());}
"Block"             { debug(" Block", yytext()); return symbol(sym.BLOCK, yytext()); }
"Succ"              { debug(" Succ", yytext()); return symbol(sym.SUCCESORS, yytext()); }
"UninterpSort"      { debug(" UniterpSort", yytext());return symbol(sym.UNINTERP_SORT, yytext());}
"UninterpFunc"      { debug(" UniterpFunc", yytext());return symbol(sym.UNINTERP_FUNC, yytext());}
"UninterpretedFunctions" {return symbol(sym.UNINTERP_FUNCTIONS, yytext());}

"ufAttribute"       { return symbol(sym.UFATTRIBUTE, yytext());}
"returns"           {return symbol(sym.RETURNS, yytext());}
"UserDefined"       {debug(" Structs", yytext());return symbol(sym.USERDEFINED, yytext());}
"Struct"        {debug(" Struct", yytext());return symbol(sym.STRUCT, yytext());}

"BuiltinFunctions"  {debug(" BuiltinFunctions"); return symbol(sym.BUILTINFUNCS, yytext());}
"BuiltinFunc"|"bif"   {return symbol(sym.BIF, yytext());}
"import"        {return symbol(sym.IMPORT, yytext());}
"ghostmap"      {return symbol(sym.GHOSTMAP, yytext());}
"="              { return symbol(sym.EQUALS, yytext());}
":="                { return symbol(sym.ASSIGNMENT, yytext()); }
":"                 { return symbol(sym.COLON, yytext());}
"{"                 { return symbol(sym.LBRACE, yytext()); }
"}"                 { return symbol(sym.RBRACE, yytext()); }
","                 {return symbol(sym.COMMA, yytext());}
"("	{debug("LP");return symbol(sym.LP,yytext());}
")"	{debug("RP");return symbol(sym.RP,yytext());}
"@" {debug("@");return symbol(sym.AT, yytext());}
"->"                {return symbol(sym.ARROW, yytext());}
"*" {return symbol(sym.CROSS, yytext());}
"(uninterp)"        {;}
<YYINITIAL, S_Parse_Expression> "["	{ debug(" ["); return symbol(sym.LSQB,yytext());}
<YYINITIAL, S_Parse_Expression> "]"	{ debug(" ]"); return symbol(sym.RSQB,yytext());}
<YYINITIAL, S_Parse_Expression> {BlockIdentifier}   {debug(" blockId", yytext()); return symbol(sym.BLOCKID, "BlockId", yytext());}
"//"[^\n\r]* { ; }
"/*"	{        debug(String.format(" Begin Comment moving to state %d", COMMENT));
               startState(COMMENT);}
"JSON{"           {debug("JSON{"); startJson('{');}
<S_Parse_Expression> {
    "{"              { return symbol(sym.LBRACE, yytext());}
    "Meta"           { return symbol(sym.META, yytext());}
    "}"              { return symbol(sym.RBRACE, yytext());}
    "="              { return symbol(sym.EQUALS, yytext());}
    "(uninterp)"        {;}
    //specific parsing exprFunctions:
    "Unconstrained"   { return symbol(sym.UNCONSTRAINED, yytext());}
    "StructConstant"  { return symbol(sym.STRUCTCONSTANT, yytext());}
    "StructAccess"    { return symbol(sym.STRUCTACCESS, yytext());}
    "SimpleHash"      { return symbol(sym.SIMPLEHASH, yytext());}
    "Forall"          { return symbol(sym.FORALL, yytext());}
    "AnnotationExp"   { return symbol(sym.ANNOTATION_EXP, yytext()); }
    "Exists"          { return symbol(sym.EXISTS, yytext());}
    "QVars"               { return symbol(sym.QVARS, yytext()); }
    "Apply"           { return symbol(sym.APPLYFN, yytext());}
    "SafeApply"           { return symbol(sym.SAFE_APPLYFN, yytext());}
    "MapDefinition"   { return symbol(sym.MAPDEFINITION, yytext());}
    {Hex} {debug("hex:", yytext());return symbol(sym.HEX, "Hex", yytext());}
    {DecimalLiteral}         {return symbol(sym.NUMBER,"number", Integer.valueOf(yytext()));}
    {booleanLiteral} {return symbol(sym.BOOL, "bool", yytext());}
    {expressionFunctions} { debug("expression function:", yytext());return symbol(sym.EXPRESSION_FUNC, "expressionFunctions", yytext()); }
    "@"   { debug("@");return symbol(sym.AT, yytext());}
    {WhiteSpaceNoLineTerminator} {;}
    //TODO: new line in expressions? should I allow this? maybe a command/axiom keyword should start a new Expression parse
    {LineTerminator} {debug(yytext());startState(YYINITIAL);}
    "\""               {debug("begin string in expression", yytext()); sb = null; sb = new StringBuffer();startState(STRING_LITERAL_CONSUMING);}
    {tagName}               { return symbol(sym.TYPE_TAG, "tag", yytext()); }
    {TACVarIdentifier} {debug("expression parsing Identifier", yytext()); return symbol(sym.IDENTIFIER, "Identifier", yytext());}
    [^] { throw new IOException("Illegal character <"+
          yytext()+"> in line: " + (yyline+1) + ", col: " + (yycolumn+1) ); }
    } //<S_Parse_Expression>


    {WhiteSpace}	{;}
    {tagName}               { return symbol(sym.TYPE_TAG, "tag", yytext()); }
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

//should parse all the "{","}" symbols until it is balanced, and than pass it and the content inside to koltin to deserialize the Json
<S_ParseJSON> "{"|"[" {  jsonBuffer.append(yytext()); jsonBracketCounter.push(yytext().charAt(0));/*return symbol(sym.LBRACE, yytext())*/;}
<S_ParseJSON> "}"|"]" {
    jsonBuffer.append(yytext());
    char c = jsonBracketCounter.pop();
    //check that the closing bracket type is matching
    if (closeJsonParsing(c)) {
        String json = endJsonParsing();
        return symbol(sym.JSON, "jsonString",json);
    }
}
<S_ParseJSON> {WhiteSpace} {;}
<S_ParseJSON> {JsonInputCharacter} {jsonBuffer.append(yytext());}
<S_ParseJSON> \"	{ sb = null; sb = new StringBuffer(); sb.append('"');startState(JSON_STRING_CONSUMING);}
<JSON_STRING_CONSUMING> \"	 			{ sb.append('"'); startState(S_ParseJSON); jsonBuffer.append(sb);}//return new symbol(Yytoken.TYPE_VALUE, sb.toString());}
//escaped quote: \"
<JSON_STRING_CONSUMING> \\\" 			{sb.append("\\\"");}
<JSON_STRING_CONSUMING> \\\\				{sb.append("\\\\");}
<JSON_STRING_CONSUMING> \\b				{sb.append("\\b");}
<JSON_STRING_CONSUMING> \\f				{sb.append("\\f");}
<JSON_STRING_CONSUMING> \\n				{sb.append("\\n");}
<JSON_STRING_CONSUMING> \\r				{sb.append("\\r");}
<JSON_STRING_CONSUMING> \\t				{sb.append("\\t");}
<STRING_LITERAL_CONSUMING> "\""   { startState(S_Parse_Expression); return symbol(sym.STRING_LITERAL,"stringLiteral" ,sb.toString());}//return new symbol(Yytoken.TYPE_VALUE, sb.toString());}
<STRING_LITERAL_CONSUMING> \\\\				{sb.append('\\');}
<STRING_LITERAL_CONSUMING> \\\" 			{sb.append('"');}
<STRING_LITERAL_CONSUMING> \\\/				{sb.append('/');}
<STRING_LITERAL_CONSUMING> \\b				{sb.append('\b');}
<STRING_LITERAL_CONSUMING> \\f				{sb.append('\f');}
<STRING_LITERAL_CONSUMING> \\n				{sb.append('\n');}
<STRING_LITERAL_CONSUMING> \\r				{sb.append('\r');}
<STRING_LITERAL_CONSUMING> \\t				{sb.append('\t');}
<JSON_STRING_CONSUMING, STRING_LITERAL_CONSUMING> {UNESCAPED_CH}	{sb.append(yytext());}

/* error fallback */
[^]                              { throw new IOException("Illegal character <"+
                                                        yytext()+"> in line: " + (yyline+1) + ", col: " + (yycolumn+1)
                                                        + "in file:" + theFilename); }
