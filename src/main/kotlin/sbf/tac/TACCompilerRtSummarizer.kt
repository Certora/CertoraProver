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

import sbf.callgraph.CompilerRtFunction
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import vc.data.*
import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.callgraph.FPCompilerRtFunction
import sbf.callgraph.IntegerU128CompilerRtFunction
import sbf.callgraph.SignedInteger64CompilerRtFunction
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import sbf.support.UnsupportedCall

/**
 * Summarize compiler-rt functions.
 *
 * Not all functions are currently summarized.
 **/
open class SummarizeCompilerRt<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> {

    /** @return empty list if function is not summarized **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    operator fun invoke(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) { "summarizeCompilerRt expects a call instruction instead of $inst" }

        val function = CompilerRtFunction.from(inst.name) ?: return listOf()
        return when (function) {
            is CompilerRtFunction.SignedInteger64 -> {
                val res = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
                val arg1 = exprBuilder.mkVar(SbfRegister.R1_ARG)
                val arg2 = exprBuilder.mkVar(SbfRegister.R2_ARG)
                val summarizer = SummarizeSignedInteger64CompilerRt<TNum, TOffset, TFlags>()
                val cmds = when (function.value) {
                    SignedInteger64CompilerRtFunction.DIVDI3 -> summarizer.summarizeDivdi3(res, arg1, arg2)
                    SignedInteger64CompilerRtFunction.MODDI3 -> summarizer.summarizeModdi3(res, arg1, arg2)
                }
                listOf(Debug.startFunction(inst.name)) + cmds + listOf(Debug.endFunction(inst.name))
            }
            is CompilerRtFunction.IntegerU128 -> {
                val summarizer = if (SolanaConfig.UseTACMathInt.get()) {
                    SummarizeIntegerU128CompilerRtWithMathInt<TNum, TOffset, TFlags>()
                } else {
                    SummarizeIntegerU128CompilerRt()
                }
                listOf(Debug.startFunction(inst.name)) +
                when (function.value) {
                    IntegerU128CompilerRtFunction.MULTI3 -> {
                        val args = summarizer.getArgsFromU128BinaryCompilerRt(locInst) ?: return listOf()
                        summarizer.summarizeMulti3(args)
                    }
                    IntegerU128CompilerRtFunction.MULOTI4 -> {
                        throw UnsupportedCall(
                            locInst,
                            msg = "${inst.name} is not currently supported",
                            function = inst.name
                        )
                    }
                    IntegerU128CompilerRtFunction.UDIVTI3 -> {
                        val args = summarizer.getArgsFromU128BinaryCompilerRt(locInst) ?: return listOf()
                        summarizer.summarizeUDivti3(args)
                    }
                    IntegerU128CompilerRtFunction.DIVTI3 -> {
                        val args = summarizer.getArgsFromU128BinaryCompilerRt(locInst) ?: return listOf()
                        summarizer.summarizeDivti3(args)
                    }
                } + listOf(Debug.endFunction(inst.name))
            }
            is CompilerRtFunction.FP -> {
                val res = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
                val arg1 = exprBuilder.mkVar(SbfRegister.R1_ARG)
                val arg2 = exprBuilder.mkVar(SbfRegister.R2_ARG)
                val cmds = when (function.value) {
                    FPCompilerRtFunction.UNORDDF2 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeUnorddf2(res, arg1, arg2)
                    FPCompilerRtFunction.ADDDF3 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeAdddf3(res, arg1, arg2)
                    FPCompilerRtFunction.SUBDF3 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeSubdf3(res, arg1, arg2)
                    FPCompilerRtFunction.MULDF3 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeMuldf3(res, arg1, arg2)
                    FPCompilerRtFunction.DIVDF3 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeDivdf3(res, arg1, arg2)
                    FPCompilerRtFunction.NEGDF2 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeNegdf3(res, arg1)
                    FPCompilerRtFunction.FIXUNSDFDI -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeFixunsdfdi(res, arg1)
                    FPCompilerRtFunction.FLOATUNDIDF -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeFloatundidf(res, arg1)
                    FPCompilerRtFunction.EQDF2 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeEqdf2(res, arg1, arg2)
                    FPCompilerRtFunction.NEDF2 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeNedf2(res, arg1, arg2)
                    FPCompilerRtFunction.GEDF2 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeGedf2(res, arg1, arg2)
                    FPCompilerRtFunction.LTDF2 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeLtdf2(res, arg1, arg2)
                    FPCompilerRtFunction.LEDF2 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeLedf2(res, arg1, arg2)
                    FPCompilerRtFunction.GTDF2 -> SummarizeFPCompilerRt<TNum, TOffset, TFlags>().summarizeGtdf2(res, arg1, arg2)
                }
                debugCompilerRtFunction(inst, function.value.function.readRegisters.size, cmds)
            }
        }
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun debugCompilerRtFunction(inst: SbfInstruction.Call, numArgs: Int, cmds: List<TACCmd.Simple>): List<TACCmd.Simple> {
        val inputArgs = mutableListOf<Pair<TACSymbol.Var, SbfFuncArgInfo>>()
        for (i in 1..numArgs) {
            inputArgs.add(exprBuilder.mkVar(SbfRegister.getByValue(i.toByte())) to SbfFuncArgInfo(SbfArgSort.SCALAR, true))
        }
        val fakeStartFuncAnnotation = SbfInlinedFuncStartAnnotation(inst.name, inst.name, id = -1, inputArgs, mockFor = null)
        val fakeEndFuncAnnotation = SbfInlinedFuncEndAnnotation(inst.name, -1, exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE))
        return  listOf(Debug.startFunction(fakeStartFuncAnnotation)) +
            cmds +
            listOf(Debug.endFunction(fakeEndFuncAnnotation))
    }
}

