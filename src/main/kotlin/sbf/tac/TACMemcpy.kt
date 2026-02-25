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
import sbf.disassembler.SbfRegister
import sbf.domains.*
import vc.data.*
import kotlin.collections.single

/** Emit TAC code for memcpy from non-stack to non-stack **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> memcpyNonStackToNonStack(
    info: TACMemSplitter.NonStackMemTransferInfo
): List<TACCmd.Simple> {
    val dstReg = exprBuilder.mkVar(SbfRegister.R1_ARG)
    val srcReg = exprBuilder.mkVar(SbfRegister.R2_ARG)
    val len = info.length
    val lenS = if (len == null) {
        exprBuilder.mkVar(SbfRegister.R3_ARG)
    } else {
        exprBuilder.mkConst(len)
    }
    val srcV = info.source
    val dstV = info.destination

    val cmds = mutableListOf<TACCmd.Simple>()
    cmds += Debug.startFunction("memcpy","(dst=nonStack, src=nonStack, len=$len)")
    cmds += havocByteMapLocation(info.locationsToHavoc.vars, dstV, dstReg)
    cmds += TACCmd.Simple.ByteLongCopy(dstReg, srcReg, lenS, dstV.tacVar, srcV.tacVar)
    cmds += Debug.endFunction("memcpy")
    return cmds
}

/** Emit TAC code for memcpy from stack to stack **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> memcpyStackToStack(
    info: TACMemSplitter.StackMemTransferInfo
): List<TACCmd.Simple> {
    val len = info.length
    val srcRange = info.source
    val dstRange = info.destination
    val dstReg = exprBuilder.mkVar(SbfRegister.R1_ARG).asSym()
    val srcReg = exprBuilder.mkVar(SbfRegister.R2_ARG).asSym()
    val zeroC = exprBuilder.ZERO.asSym()

    val cmds = mutableListOf<TACCmd.Simple>()
    cmds += Debug.startFunction("memcpy","(dst=Stack$dstRange, src=Stack$srcRange, len=$len)")

    val havocMap = info.locationsToHavoc.vars
    when (havocMap.size) {
        0 -> {}
        1 -> cmds += havocScalars(havocMap.toList().single().second)
        else -> cmds += weakHavocScalars(dstReg, zeroC, havocMap)
    }

    if (srcRange.size == 1 && dstRange.size == 1) {
        // common case: one source and one destination
        val srcSlice = srcRange.toList().single().second
        val dstSlice = dstRange.toList().single().second
        for (i in 0 until len) {
            val srcV = vFac.getByteStackVar(PTAOffset(srcSlice.lb + i)).tacVar
            val dstV = vFac.getByteStackVar(PTAOffset(dstSlice.lb + i)).tacVar
            cmds += assign(dstV, srcV.asSym())
        }
    } else {
        for (i in 0 until len) {
            for ((srcOffset, srcSlice) in srcRange) {
                for ((dstOffset, dstSlice) in dstRange) {
                    val srcV = vFac.getByteStackVar(PTAOffset(srcSlice.lb + i)).tacVar
                    val dstV = vFac.getByteStackVar(PTAOffset(dstSlice.lb + i)).tacVar
                    cmds += weakAssign(
                        dstV,
                        TACExpr.BinBoolOp.LAnd(
                            pointsToStack(srcReg, zeroC, srcOffset),
                            pointsToStack(dstReg, zeroC, dstOffset)
                        ),
                        srcV.asSym()
                    )
                }
            }
        }
    }
    cmds += Debug.endFunction("memcpy")
    return cmds
}

/** Emit TAC code for memcpy from non-stack to stack **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> memcpyNonStackToStack(
    info: TACMemSplitter.MixedRegionsMemTransferInfo
): List<TACCmd.Simple> {
    check(info.isDestStack) {"precondition for memcpyNonStackToStack"}

    val dstRange = info.stack
    val len = info.length
    val srcReg = exprBuilder.mkVar(SbfRegister.R2_ARG)
    val dstReg = exprBuilder.mkVar(SbfRegister.R1_ARG).asSym()
    val zeroC = exprBuilder.ZERO.asSym()

    val cmds = mutableListOf<TACCmd.Simple>()
    cmds += Debug.startFunction("memcpy", "(dst=Stack$dstRange, src=non-stack, len=$len)")

    val havocMap = (info.locationsToHavoc as TACMemSplitter.HavocScalars).vars
    when (havocMap.size) {
        0 -> {}
        1 -> cmds += havocScalars(havocMap.toList().single().second)
        else -> cmds += weakHavocScalars(dstReg, zeroC, havocMap)
    }

    val byteVarsAtSrc = mapLoads(info.byteMap, srcReg, 1, len, cmds)
    byteVarsAtSrc.forEachIndexed { i, srcV ->
        if (dstRange.size == 1) {
            // one single destination
            val dstSlice = dstRange.toList().single().second
            val dstV = vFac.getByteStackVar(PTAOffset(dstSlice.lb + i)).tacVar
            cmds += assign(dstV, srcV.asSym())
        } else {
            // for each destination byte we create an ite with the old and new value from the source map
            for ((dstOffset, dstSlice) in dstRange) {
                val dstV = vFac.getByteStackVar(PTAOffset(dstSlice.lb + i)).tacVar
                cmds += weakAssign(dstV, pointsToStack(dstReg, zeroC, dstOffset), srcV.asSym())
            }
        }
    }
    cmds += Debug.endFunction("memcpy")
    return cmds
}

/** Emit TAC code for memcpy from stack to non-stack **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> memcpyStackToNonStack(
    info: TACMemSplitter.MixedRegionsMemTransferInfo
): List<TACCmd.Simple> {
    check(!info.isDestStack) {"precondition for memcpyStackToNonStack"}

    val srcRange = info.stack
    val len = info.length
    val srcReg = exprBuilder.mkVar(SbfRegister.R2_ARG).asSym()
    val dstReg = exprBuilder.mkVar(SbfRegister.R1_ARG)
    val zeroC = exprBuilder.ZERO.asSym()

    val cmds = mutableListOf<TACCmd.Simple>()
    cmds += Debug.startFunction("memcpy", "(dst=non-stack, src=Stack$srcRange, len=$len)")
    cmds += havocByteMapLocation((info.locationsToHavoc as TACMemSplitter.HavocMapBytes).vars, info.byteMap, exprBuilder.mkVar(
        SbfRegister.R1_ARG))
    // for each source byte we create an ite to resolve the actual byte and stores in the destination map
    for (i in 0 until len) {
        // create an ite that accesses to the right byte at the source
        val stackLocs = srcRange.map {
            it.key to vFac.getByteStackVar(PTAOffset(it.value.lb + i)).tacVar.asSym()
        }.toMap()
        val srcBV = vFac.mkFreshIntVar()
        cmds += assign(srcBV, resolveStackAccess(srcReg, zeroC, stackLocs))
        // store in the destination map
        cmds += mapStores(info.byteMap, dstReg, PTAOffset(i), srcBV)
    }
    cmds += Debug.endFunction("memcpy")
    return cmds
}
