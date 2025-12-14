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
import tac.Tag
import vc.data.*

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
fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> memsetNonStackWithMapDef(
    mapV: TACByteMapVariable,
    len: Long,
    value: Long
): List<TACCmd.Simple> {
    val initMap = vFac.getByteMapVar("memset")
    return datastructures.stdcollections.listOf(
        TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = initMap.tacVar,
            rhs = TACExpr.MapDefinition(
                defParams = datastructures.stdcollections.listOf(
                    TACKeyword.TMP(Tag.Bit256, "!idx").toUnique("!").asSym()
                ),
                tag = Tag.ByteMap,
                definition = if (value == 0L) {
                    exprBuilder.mkConst(value).asSym()
                } else {
                    TACExpr.Unconstrained(Tag.Bit256)
                }
            )
        ),
        TACCmd.Simple.ByteLongCopy(
            srcBase = initMap.tacVar,
            srcOffset = TACSymbol.Zero,
            dstBase = mapV.tacVar,
            dstOffset = exprBuilder.mkVar(SbfRegister.R1_ARG),
            length = exprBuilder.mkConst(len),
        )
    )
}

/**
 * Same semantics than `memsetNonStackWithMapDef` but this version does not use a map definition.
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> memsetNonStack(
    mapV: TACByteMapVariable,
    len: Long,
    value: Long
): List<TACCmd.Simple> {
    val valueS = if (value == 0L) {
        exprBuilder.mkConst(value)
    } else {
        // this is an over-approximation. See comment in `memsetNonStackWithMapDef` for details.
        vFac.mkFreshIntVar()
    }
    val cmds = mutableListOf<TACCmd.Simple>()
    for (i in 0 until len) {
        cmds.addAll(mapStores(mapV, exprBuilder.mkVar(SbfRegister.R1_ARG), PTAOffset(i), valueS))
    }
    return cmds
}
