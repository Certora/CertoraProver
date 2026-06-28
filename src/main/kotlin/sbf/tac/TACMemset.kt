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

import sbf.disassembler.SbfRegister
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import sbf.domains.PTAOffset
import vc.data.TACCmd
import vc.data.TACSymbol
import datastructures.stdcollections.*

/**
 * Emit TAC code for a memset of non-stack memory.
 *
 * If [value] != 0 then we create a map that always returns a non-deterministic value.
 * We could have also returned value instead but that would be potentially unsound since for memset we need to
 * know how the stored value is going to be read (i.e., word size).
 *
 * The byte map scalarizer optimization does not support map definitions.
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> memsetNonStackWithMapDef(
    mapV: TACByteMapVariable,
    len: Long,
    value: Long,
    offset: Long
): List<TACCmd.Simple> {
    val initMap = vFac.getByteMapVar("memset")
    val cmds = mutableListOf<TACCmd.Simple>()
    cmds += assign(initMap.tacVar, sbfTacB.defineMap(value))
    val dstOffset = if (offset == 0L) {
        sbfTacB.mkVar(SbfRegister.R1)
    } else {
        computeTACMapIndex(sbfTacB.mkVar(SbfRegister.R1), PTAOffset(offset), cmds)
    }
    cmds += TACCmd.Simple.ByteLongCopy(
        srcBase = initMap.tacVar,
        srcOffset = TACSymbol.Zero,
        dstBase = mapV.tacVar,
        dstOffset = dstOffset,
        length = sbfTacB.mkConst(len),
    )
    return cmds
}

/**
 * Same semantics than `memsetNonStackWithMapDef` but this version does not use a map definition.
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> memsetNonStack(
    mapV: TACByteMapVariable,
    len: Long,
    value: Long,
    offset: Long
): List<TACCmd.Simple> {
    val valueS = if (value == 0L) {
        sbfTacB.mkConst(value)
    } else {
        // this is an over-approximation. See comment in `memsetNonStackWithMapDef` for details.
        vFac.mkFreshIntVar()
    }
    val cmds = mutableListOf<TACCmd.Simple>()
    val r1 = sbfTacB.mkVar(SbfRegister.R1)
    for (i in 0 until len) {
        cmds += mapStores(mapV, r1, PTAOffset(offset + i), valueS)
    }
    cmds += accounts.updateWrite(r1)
    return cmds
}
