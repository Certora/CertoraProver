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

package sbf.tac

import sbf.analysis.IRegisterTypes
import sbf.callgraph.CVTCalltrace
import sbf.callgraph.CVTFunction
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import sbf.domains.SbfType
import sbf.sbfLogger
import vc.data.*

/** This class adds annotations used by the calltrace **/
internal object Calltrace {

    fun externalCall(inst: SbfInstruction.Call, symbols: List<TACSymbol.Var>): TACCmd.Simple {
        return SnippetCmd.SolanaSnippetCmd.ExternalCall(inst.name, symbols).toAnnotation()
    }

    fun assert(@Suppress("UNUSED_PARAMETER")inst: SbfInstruction.Assert, cond: TACSymbol.Var): TACCmd.Simple {
        return SnippetCmd.SolanaSnippetCmd.Assert("assert", cond, fromSatisfy = false).toAnnotation()
    }

    fun satisfy(cond: TACSymbol.Var): TACCmd.Simple {
        return SnippetCmd.SolanaSnippetCmd.Assert("satisfy", cond, fromSatisfy = true).toAnnotation()
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    printValueOrTag(locInst: LocatedSbfInstruction, cexPrintFunction: CVTFunction): TACCmd.Simple {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        val tag = types.getString(locInst, SbfRegister.R1_ARG)
        return if (cexPrintFunction == CVTFunction.Calltrace(CVTCalltrace.PRINT_TAG)) {
            SnippetCmd.CvlrSnippetCmd.CexPrintTag(tag).toAnnotation()
        } else {
            val usedVars = mutableListOf<TACSymbol.Var>()
            var i = 0
            val numArgs = inst.readRegisters.size - 2 /** We skip R1 and R2 **/
            while (i < numArgs) {
                usedVars.add(exprBuilder.mkVar(SbfRegister.getByValue((i + 3).toByte()))) // We start at R3
                i++
            }
            SnippetCmd.CvlrSnippetCmd.CexPrintValues(tag, usedVars).toAnnotation()
        }
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
        startScope(locInst: LocatedSbfInstruction): TACCmd.Simple {
        val scopeName = types.getString(locInst, SbfRegister.R1_ARG)
        return SnippetCmd.CvlrSnippetCmd.ScopeStart(scopeName).toAnnotation()
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
        endScope(locInst: LocatedSbfInstruction): TACCmd.Simple {
        val scopeName = types.getString(locInst, SbfRegister.R1_ARG)
        return SnippetCmd.CvlrSnippetCmd.ScopeEnd(scopeName).toAnnotation()
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>  print128BitsValue(
        locInst: LocatedSbfInstruction, signed: Boolean
    ): TACCmd.Simple {
        val tag = types.getString(locInst, SbfRegister.R1_ARG)
        val low = exprBuilder.mkVar(SbfRegister.R3_ARG)
        val high = exprBuilder.mkVar(SbfRegister.R4_ARG)
        return SnippetCmd.CvlrSnippetCmd.CexPrint128BitsValue(tag, low, high, signed).toAnnotation()
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>  printU64AsFixed(
        locInst: LocatedSbfInstruction
    ): TACCmd.Simple {
        val tag = types.getString(locInst, SbfRegister.R1_ARG)
        val unscaledVar = exprBuilder.mkVar(SbfRegister.R3_ARG)
        val scaleVar = exprBuilder.mkVar(SbfRegister.R4_ARG)
        return SnippetCmd.CvlrSnippetCmd.CexPrintU64AsFixedOrDecimal(tag, unscaledVar, scaleVar, asFixed = true).toAnnotation()
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>  printU64AsDecimal(
        locInst: LocatedSbfInstruction
    ): TACCmd.Simple {
        val tag = types.getString(locInst, SbfRegister.R1_ARG)
        val unscaledVar = exprBuilder.mkVar(SbfRegister.R3_ARG)
        val scaleVar = exprBuilder.mkVar(SbfRegister.R4_ARG) // number of decimals
        return SnippetCmd.CvlrSnippetCmd.CexPrintU64AsFixedOrDecimal(tag, unscaledVar, scaleVar, asFixed = false).toAnnotation()
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>  printLocation(
        locInst: LocatedSbfInstruction
    ): TACCmd.Simple {
        val (filepath, lineNumber) = types.getFilepathAndLineNumber(locInst)
        return SnippetCmd.CvlrSnippetCmd.CexPrintLocation(filepath, lineNumber).toAnnotation()
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>  ruleLocation(
        locInst: LocatedSbfInstruction
    ): TACCmd.Simple {
        val (filepath, lineNumber) = types.getFilepathAndLineNumber(locInst)
        val ruleLocationAnnotation = RuleLocationAnnotation(filepath, lineNumber)
        return TACCmd.Simple.AnnotationCmd(RULE_LOCATION, ruleLocationAnnotation)
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>  printString(
        locInst: LocatedSbfInstruction
    ): TACCmd.Simple {
        val tag = types.getString(locInst, SbfRegister.R1_ARG)
        val str = types.getString(locInst, SbfRegister.R3_ARG)
        return SnippetCmd.CvlrSnippetCmd.CexPrintTag("$tag: $str").toAnnotation()
    }


    /** Read the filepath from the first two registers and the line number from the third one. */
    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>> IRegisterTypes<TNum, TOffset>.getFilepathAndLineNumber(
        locInst: LocatedSbfInstruction,
    ): Pair<String, UInt> {
        val filepath = this.getString(locInst, SbfRegister.R1_ARG)
        // The first two registers are for the filepath (pointer + length), the third is for the line number.
        val value = this.typeAtInstruction(locInst, SbfRegister.R3_ARG).let { it as? SbfType.NumType }?.value?.toLongOrNull()
        val lineNumber = if (value == null) {
            sbfLogger.warn {
                "Cannot identify statically the line number associated with ${locInst.inst}. " +
                    "Returning 1 instead to be used by the calltrace."
            }
            1U
        } else {
            value.toUInt()
        }
        return filepath to lineNumber
    }

    fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>> IRegisterTypes<TNum, TOffset>.getString(
        locInst: LocatedSbfInstruction,
        reg: SbfRegister,
    ): String {
        return this.typeAtInstruction(locInst, reg).let {
            val str = (it as? SbfType.PointerType.Global)?.global?.strValue
            if (str != null) {
                str
            } else {
                sbfLogger.warn {
                    "Cannot identify statically the content of the string associated with ${locInst.inst}. " +
                        "Generating tag ${locInst.label}#${locInst.pos} instead to be used by the calltrace."
                }
                "${locInst.label}#${locInst.pos}"
            }
        }
    }
}
