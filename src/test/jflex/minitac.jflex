package vc.data.minitac;

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
%class MiniTACLexer
%public
%cup
%char
%line
%column


%{

	public MiniTACLexer(ComplexSymbolFactory sf, java.io.Reader reader) {
		this(reader);
        symbolFactory = sf;
    }

    public Symbol symbol(int code,String name){
        return symbolFactory.newSymbol(name, code,
                        new Location(theFilename,yyline,yycolumn, yychar), // -yylength()
                        new Location(theFilename,yyline,yycolumn+yylength(), yychar+yylength()), name);
    }
    private ComplexSymbolFactory symbolFactory;
    public String theFilename;


   StringBuilder string = new StringBuilder();
   char delimiter;
%}

%state BLOB_START
%state BLOB_READING
%state STRING

LineTerminator = \r|\n|\r\n
InputCharacter = [^\r\n]
WhiteSpace     = {LineTerminator} | [ \t\f]
Identifier = [:jletter:] [:jletterdigit:]*

%%


<YYINITIAL> {
    <<EOF>> { return symbol(sym.EOF, "EOF"); }
    "if" {
        return symbol(sym.IF, yytext());
    }
    "else" {
       return symbol(sym.ELSE, yytext());
    }
    "marker" {
       return symbol(sym.MARKER, yytext());
    }
    "while" {
        return symbol(sym.WHILE, yytext());
    }
    "*" {
       return symbol(sym.STAR, yytext());
    }
    "=" {
       return symbol(sym.EQ, yytext());
    }
    ":" {
       return symbol(sym.COLON, yytext());
    }
    ";" {
       return symbol(sym.SC, yytext());
    }
    ")" {
       return symbol(sym.RP, yytext());
    }
    "(" {
       return symbol(sym.LP, yytext());
    }
    "{" {
       return symbol(sym.LB, yytext());
    }
    "}" {
       return symbol(sym.RB, yytext());
    }
    "," {
       return symbol(sym.COMMA, yytext());
    }
    "embed" {
       yybegin(BLOB_START);
       return symbol(sym.EMBED, yytext());
    }
    "goto" {
       return symbol(sym.GOTO, yytext());
    }
    [:digit:]+ {
        return symbol(sym.NUMBER_LIT, yytext());
    }
    {Identifier} {
       return symbol(sym.IDENT, yytext());
    }
    {WhiteSpace} { }
    \" {
      string.setLength(0);
      yybegin(STRING);
    }
}
<BLOB_START> {
   {WhiteSpace} {

   }
   . {
       yybegin(BLOB_READING);
       string.setLength(0);
       delimiter = yytext().charAt(0);
   }
}

<BLOB_READING> {
  . {
     char c = yytext().charAt(0);
     if(c == delimiter) {
         yybegin(YYINITIAL);
         return symbol(sym.OPAQUE, string.toString());
     } else {
         string.append(c);
     }
  }
}


<STRING> {
   \" {
       yybegin(YYINITIAL);
       return symbol(sym.STRING, string.toString());
   }
   [^\"\\]+ { string.append(yytext()); }
   \\\" {
     string.append("\"");
  }
   \\ {
      string.append("\\");
   }
}

[^]                              { throw new Error("Illegal character <"+
                                                    yytext()+">"); }
