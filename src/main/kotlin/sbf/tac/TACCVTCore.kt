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

import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.callgraph.CVTCore
import sbf.callgraph.CVTFunction
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.domains.*
import vc.data.TACCmd
import vc.data.TACExpr

/** Emit TAC code for pushing scratch registers **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> translateSaveScratchRegisters(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call) {"translateSaveScratchRegisters expects a call instead of $inst"}

    // If the call doesn't have this metadata then by assuming 0 the call won't appear in the calltrace.
    val calleeSize = inst.metaData.getVal(SbfMeta.INLINED_FUNCTION_SIZE)?: 0UL

    val regsToSave = SbfRegister.registersToSaveOrRestore

    val savedVars = regsToSave.map { reg ->
        vFac.mkFreshIntVar(prefix = "saved_r${reg.value}").also {
            scratchRegVars.add(it)
        }
    }

    val startInlineAnnot = inst.toStartInlinedAnnotation(locInst)?.let {
        if (calleeSize >= SolanaConfig.TACMinSizeForCalltrace.get().toULong()) {
            listOf(
                // Before each function start annotation, we insert a function no-op annotation.
                // This is because with the current implementation of [report.dumps.AddInternalFunctions], if
                // there is a function end annotation immediately followed by a function start annotation, the
                // functions are not correctly inlined, and the whole procedure fails.
                // Fixing the behaviour in [report.dumps.AddInternalFunctions] is not trivial, and it is, at
                // least for the moment, easier to insert a no-op annotation to fix the problem.
                TACCmd.Simple.AnnotationCmd(
                    TACCmd.Simple.AnnotationCmd.Annotation(SBF_INLINED_FUNCTION_NOP, SbfInlinedFuncNopAnnotation)
                ),
                TACCmd.Simple.AnnotationCmd(
                    TACCmd.Simple.AnnotationCmd.Annotation(SBF_INLINED_FUNCTION_START, it)
                )
            )
        }  else {
            listOf(Debug.startFunction(it))
        }
    } ?: listOf()

    return startInlineAnnot + savedVars.zip(regsToSave).map { (v, reg) ->
        assign(v, TACExpr.Sym.Var(exprBuilder.mkVar(reg)))
    }
}

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> SbfInstruction.Call.toStartInlinedAnnotation(
    locInst: LocatedSbfInstruction
): SbfInlinedFuncStartAnnotation? {
    if (!isSaveScratchRegisters()) {
        return null
    }
    val fnName = metaData.getVal(SbfMeta.INLINED_FUNCTION_NAME) ?: return null
    val fnMangledName = metaData.getVal(SbfMeta.MANGLED_NAME) ?: return null
    val callId = metaData.getVal(SbfMeta.CALL_ID)?.toInt() ?: return null
    val mockFor = metaData.getVal(SbfMeta.MOCK_FOR)

    // These are the observed args across all call sites
    val observedArgs = functionArgInference.inferredArgs(fnName) ?: return null
    // "pad up" to the largest observed register
    val maxArgRegister = observedArgs.keys.maxByOrNull { it.r }?.r
    // Produce a map that associates each register to its uses, including
    // registers we did not see but whose index is smaller than some register
    // we _did_ see
    val args = SbfRegister.funArgRegisters.filter {
        maxArgRegister != null && it <= maxArgRegister
    }.associate {
        val k = Value.Reg(it)
        k to observedArgs[k].orEmpty()
    }

    // We want to indicate in this inlining annotation
    // which registers we actually saw used at _this_ callsite
    val live = functionArgInference.liveAtThisCall(locInst) ?: return null
    val tacArgs = inferredArgsToTACArgs(args, live)

    return SbfInlinedFuncStartAnnotation(
        name = fnName,
        mangledName = fnMangledName,
        args = tacArgs,
        id = callId,
        mockFor = mockFor
    )
}

/** Emit TAC code for popping scratch registers **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> translateRestoreScratchRegisters(
    inst: SbfInstruction.Call
): List<TACCmd.Simple> {

    val regsToRestore = SbfRegister.registersToSaveOrRestore

    val n = regsToRestore.size
    if (scratchRegVars.size < n) {
        throw TACTranslationError("number of save/restore does not match")
    }

    // If the call doesn't have this metadata then by assuming 0 the call won't appear in the calltrace.
    val calleeSize = inst.metaData.getVal(SbfMeta.INLINED_FUNCTION_SIZE)?: 0UL

    // Pop n vars (LIFO order: v10, v9, v8, v7, v6), then reverse to align with scratchRegisters order
    val savedVars = (0 until n).map { scratchRegVars.removeLast() }.reversed()

    val endInlineAnnot = inst.toEndInlineAnnotation()?.let {
        listOf(
            if (calleeSize >= SolanaConfig.TACMinSizeForCalltrace.get().toULong()) {
                TACCmd.Simple.AnnotationCmd(
                    TACCmd.Simple.AnnotationCmd.Annotation(SBF_INLINED_FUNCTION_END, it)
                )
            } else {
                Debug.endFunction(it)
            }
        )
    } ?: listOf()
    return endInlineAnnot + regsToRestore.zip(savedVars).map { (reg, v) ->
        assign(exprBuilder.mkVar(reg), TACExpr.Sym.Var(v))
    }
}

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
SbfInstruction.Call.toEndInlineAnnotation(): SbfInlinedFuncEndAnnotation? {
    if (CVTFunction.from(name) != CVTFunction.Core(CVTCore.RESTORE_SCRATCH_REGISTERS)) {
        return null
    }
    val fnName = metaData.getVal(SbfMeta.INLINED_FUNCTION_NAME) ?: return null
    val callId = metaData.getVal(SbfMeta.CALL_ID)?.toInt() ?: return null
    val retVar = exprBuilder.mkVar(SbfRegister.R0)
    return SbfInlinedFuncEndAnnotation(
        name = fnName,
        id = callId,
        retVal = retVar
    )
}


/**
 * `cvt_alloc_slice(base:ptr, offset:usize, size:usize) -> ptr`
 *
 *  Preconditions:
 *   1) `base` is the base of some allocated object `X`
 *   2) the size of object `X` must be greater than `offset` + `size`.
 *
 *  Return a pointer that points to a fresh allocated object of size `size` whose address is `base` + `offset`
 *
 *  **IMPORTANT**: we cannot check the preconditions at the TAC level so they must be ensured when calling CVT_alloc_slice
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeAllocSlice(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call)
    val offset = (types.typeAtInstruction(locInst, SbfRegister.R2) as? SbfType.NumType)?.value?.toLongOrNull()
        ?: throw TACTranslationError("Cannot statically infer the offset (r2) in $locInst")
    if (offset < 0) {
        throw TACTranslationError("$locInst does not support negative offsets (r2) but given $offset")
    }
    val baseE = exprBuilder.mkVar(SbfRegister.R1).asSym()
    val offsetE = exprBuilder.mkConst(Value.Imm(offset.toULong())).asSym()
    val lhsE = exprBuilder.mkVar(SbfRegister.R0)
    return if (SolanaConfig.UseTACMathInt.get()) {
        val (x, y, z) = Triple(vFac.mkFreshMathIntVar(), vFac.mkFreshMathIntVar(), vFac.mkFreshMathIntVar())
        listOf(
            promoteToMathInt(baseE, x),
            promoteToMathInt(offsetE, y),
            assign(z, exprBuilder.mkBinExpr(BinOp.ADD, x.asSym(), y.asSym(), useMathInt = true)),
            narrowFromMathInt(z.asSym(), lhsE),
            Calltrace.externalCall(
                inst,
                listOf(exprBuilder.mkVar(SbfRegister.R0))
            )
        )
    } else {
        val rhs = exprBuilder.mkBinExpr(BinOp.ADD, baseE, offsetE, useMathInt = false)
        listOf(
            assign(lhsE, rhs),
            Calltrace.externalCall(
                inst,
                listOf(exprBuilder.mkVar(SbfRegister.R0))
            )
        )
    }
}

/** Emit TAC code for special intrinsics that masks `r1` with `0xFFFF_FFFF` and store the result in `r0` **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> translateMask64(): List<TACCmd.Simple> {
    val v0 = exprBuilder.mkVar(SbfRegister.R0)
    val v1 = exprBuilder.mkVar(SbfRegister.R1)
    return listOf(assign(v0, exprBuilder.mask64(v1.asSym())))
}
