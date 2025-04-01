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

import sbf.domains.PTAOffset
import vc.data.TACCmd
import vc.data.TACSymbol
import datastructures.stdcollections.*

/** Return instructions that havoc the indexes [loc] + [indexes] of the byte map [base] **/
context(SbfCFGToTAC)
fun havocByteMapLocation(indexes: List<PTAOffset>, base: TACByteMapVariable, loc: TACSymbol.Var): List<TACCmd.Simple> {
    val values = ArrayList<TACSymbol.Var>()
    val cmds = mutableListOf<TACCmd.Simple>()
    indexes.forEach { _ ->
        val value = mkFreshIntVar(bitwidth = 256)
        cmds.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(value))
        values.add(value)
    }
    cmds.addAll(mapStores(base, loc, indexes, values))
    return cmds
}

/** Emit TAC code for index = [base] + [offset] **/
context(SbfCFGToTAC)
fun computeTACMapIndex(base: TACSymbol.Var, offset: Long, cmds: MutableList<TACCmd.Simple>): TACSymbol.Var {
    val index = mkFreshIntVar(bitwidth = 256)
    cmds.add(assign(index, exprBuilder.mkAddExpr(base.asSym(), exprBuilder.mkConst(offset).asSym(), useMathInt = false)))
    return index
}

/**
 * Emit TAC code that writes [values] in [byteMap] starting at [base] with [offsets]
 * [offsets] must be relative to [base]
 */
context(SbfCFGToTAC)
fun mapStores(byteMap: TACByteMapVariable,
              base: TACSymbol.Var,
              offsets: List<PTAOffset>,
              values: List<TACSymbol>): List<TACCmd.Simple> {
    // precondition: fields are sorted and len(fields) = len(values)
    check(offsets.size == values.size) {"Precondition of emitTACMapStores"}

    val cmds = mutableListOf<TACCmd.Simple>()
    for ( (offset, value) in offsets.zip(values)) {
        val loc = computeTACMapIndex(base, offset, cmds)
        // REVISIT: ByteStore assumes 32 bytes are written so the actual width is being ignored
        cmds.add(TACCmd.Simple.AssigningCmd.ByteStore(loc, value, byteMap.tacVar))
    }
    return cmds
}

/**
 * Emit TAC code that writes [value] in [byteMap] starting at [base] with [offset]
 * [offset] must be relative to [base]
 */
context(SbfCFGToTAC)
fun mapStores(byteMap: TACByteMapVariable,
              base: TACSymbol.Var,
              offset: PTAOffset,
              value: TACSymbol): List<TACCmd.Simple> =
    mapStores(byteMap, base, listOf(offset), listOf(value))

/**
 * Emit TAC code that loads each word from [byteMap] starting at [base] up to [length]
 */
context(SbfCFGToTAC)
fun mapLoads(byteMap: TACByteMapVariable,
             base: TACSymbol.Var,
             wordSize: Byte, length: Long,
             cmds: MutableList<TACCmd.Simple>): List<TACSymbol.Var> {
    val numOfWords = length.toInt() / wordSize
    val intVars = ArrayList<TACSymbol.Var>(numOfWords)
    for (i in 0 until numOfWords) {
        val loc = computeTACMapIndex(base, wordSize.toLong() * i.toLong(), cmds)
        val x = mkFreshIntVar()
        // REVISIT: ByteLoad assumes 32 bytes are read so the actual width (wordSize) is being ignored
        cmds.add(TACCmd.Simple.AssigningCmd.ByteLoad(x, loc, byteMap.tacVar))
        intVars.add(x)
    }
    // We should add at each loop iteration that [loc] cannot be greater than SBF_INPUT_END
    // However, this will add too many constraints to the solver. Instead, we enforce that [base] cannot
    // be greater than SBF_INPUT_END. Note that our solution is still sound, but it might produce spurious
    // counterexamples is numOfWords is too large. In fact, right now this cannot happen since we use 256 bits to
    // represent integers.
    cmds.addAll(addMemoryLayoutAssumptions(base, null))
    return intVars
}
