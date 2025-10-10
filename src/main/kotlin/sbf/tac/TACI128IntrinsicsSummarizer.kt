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

import sbf.cfg.*
import vc.data.*
import java.math.BigInteger
import datastructures.stdcollections.*
import sbf.callgraph.CVTI128Intrinsics
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags

/**
 * Summarize i128 intrinsics.
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeI128(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call) {"summarizeI128 expects a call instruction instead of ${locInst.inst}"}
    val function = CVTI128Intrinsics.from(inst.name)
    check(function != null) {"summarizeI128 does not support ${inst.name}"}
    return when (function) {
        CVTI128Intrinsics.I128_NONDET -> summarizeI128Nondet(locInst)
    }
}

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeI128Nondet(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call)
    { "summarizeI128Nondet expects a call instruction instead of ${locInst.inst}" }
    check(CVTI128Intrinsics.from(inst.name) == CVTI128Intrinsics.I128_NONDET)
    { "summarizeI128Nondet expects ${CVTI128Intrinsics.I128_NONDET.function.name}" }

    val (resLow, resHigh) = getResFrom128(locInst) ?: return listOf()
    val res = mkFreshIntVar()

    val cmds = mutableListOf(Debug.externalCall(inst))
    cmds.addAll(inRange(res,  -BigInteger.TWO.pow(127),  BigInteger.TWO.pow(127) - BigInteger.ONE))
    cmds.addAll(splitU128(res, resLow.tacVar, resHigh.tacVar))
    return cmds
}
