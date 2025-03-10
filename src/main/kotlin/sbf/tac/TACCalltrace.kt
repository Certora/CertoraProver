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

import sbf.callgraph.CVTCalltrace
import sbf.callgraph.CVTFunction
import sbf.cfg.*
import sbf.disassembler.*
import sbf.sbfLogger
import vc.data.*

/** This class adds annotations used by the calltrace **/
object Calltrace {

    fun externalCall(inst: SbfInstruction.Call, symbols: List<TACSymbol.Var>): TACCmd.Simple {
        return SnippetCmd.SolanaSnippetCmd.ExternalCall(inst.name, symbols).toAnnotation()
    }

    fun assert(@Suppress("UNUSED_PARAMETER")inst: SbfInstruction.Assert, cond: TACSymbol.Var): TACCmd.Simple {
        return SnippetCmd.SolanaSnippetCmd.Assert("assert", cond, fromSatisfy = false).toAnnotation()
    }

    fun satisfy(cond: TACSymbol.Var): TACCmd.Simple {
        return SnippetCmd.SolanaSnippetCmd.Assert("satisfy", cond, fromSatisfy = true).toAnnotation()
    }

    context(SbfCFGToTAC)
    fun printValueOrTag(locInst: LocatedSbfInstruction, cexPrintFunction: CVTFunction): TACCmd.Simple {
        val tag = getString(locInst, SbfRegister.R1_ARG)
        return if (cexPrintFunction == CVTFunction.Calltrace(CVTCalltrace.CEX_PRINT_TAG)) {
            SnippetCmd.SolanaSnippetCmd.CexPrintTag(tag).toAnnotation()
        } else {
            val usedVars = mutableListOf<TACSymbol.Var>()
            var i = 0
            val numArgs = cexPrintFunction.function.readRegisters.size - 2 /** We skip R1 and R2 **/
            while (i < numArgs) {
                usedVars.add(exprBuilder.mkVar(SbfRegister.getByValue((i + 3).toByte()))) // We start at R3
                i++
            }
            SnippetCmd.SolanaSnippetCmd.CexPrintValues(tag, usedVars).toAnnotation()
        }
    }

    context(SbfCFGToTAC)
    fun printU64AsFixed(locInst: LocatedSbfInstruction): TACCmd.Simple {
        val tag = getString(locInst, SbfRegister.R1_ARG)
        val unscaledVar = exprBuilder.mkVar(SbfRegister.R3_ARG)
        val scaleVar = exprBuilder.mkVar(SbfRegister.R4_ARG)
        return SnippetCmd.SolanaSnippetCmd.CexPrintU64AsFixed(tag, unscaledVar, scaleVar).toAnnotation()
    }

    context(SbfCFGToTAC)
    fun printLocation(locInst: LocatedSbfInstruction): TACCmd.Simple {
        val (filepath, lineNumber) = getFilepathAndLineNumber(locInst)
        return SnippetCmd.SolanaSnippetCmd.CexPrintLocation(filepath, lineNumber).toAnnotation()
    }

    context(SbfCFGToTAC)
    fun attachLocation(locInst: LocatedSbfInstruction): TACCmd.Simple {
        val (filepath, lineNumber) = getFilepathAndLineNumber(locInst)
        return SnippetCmd.SolanaSnippetCmd.CexAttachLocation(filepath, lineNumber).toAnnotation()
    }

    context(SbfCFGToTAC)
    fun printString(locInst: LocatedSbfInstruction): TACCmd.Simple {
        val tag = getString(locInst, SbfRegister.R1_ARG)
        val str = getString(locInst, SbfRegister.R3_ARG)
        return SnippetCmd.SolanaSnippetCmd.CexPrintTag("$tag: $str").toAnnotation()
    }


    /** Read the filepath from the first two registers and the line number from the third one. */
    context(SbfCFGToTAC)
    private fun getFilepathAndLineNumber(locInst: LocatedSbfInstruction): Pair<String, UInt> {
        val filepath = getString(locInst, SbfRegister.R1_ARG)
        // The first two registers are for the filepath (pointer + length), the third is for the line number.
        val value = (regTypes.typeAtInstruction(locInst, SbfRegister.R3_ARG) as? SbfType.NumType)?.value?.get()
        val lineNumber = if (value == null) {
            sbfLogger.warn {
                "Cannot identify statically the content of the string associated with ${locInst.inst}. " +
                    "Returning 1 instead to be used by the calltrace."
            }
            1U
        } else {
            value.toUInt()
        }
        return filepath to lineNumber
    }

    context(SbfCFGToTAC)
    private fun getString(locInst: LocatedSbfInstruction, reg: SbfRegister): String {
        return regTypes.typeAtInstruction(locInst, reg).let {
            if (it is SbfType.PointerType.Global && it.global is SbfConstantStringGlobalVariable) {
                it.global.value
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
