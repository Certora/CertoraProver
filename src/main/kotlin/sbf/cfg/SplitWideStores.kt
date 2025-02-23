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

package sbf.cfg

import sbf.analysis.ScalarAnalysis
import sbf.analysis.ScalarAnalysisRegisterTypes
import sbf.disassembler.*
import sbf.domains.MemorySummaries
import datastructures.stdcollections.*

/**
 * LLVM can sometimes optimize two consecutive memory stores into a single store if the stored value is an immediate value.
 * This transformation reverts this optimization because the prover works under the assumption that reads must match last writes,
 * and optimizations like this can break this assumption.
 *
 * Since this transformation only rewrites stores on the stack, we are always sound because if the transformation splits a store
 * in a wrong way (i.e., a read won't match) then the pointer analysis will complain. Thus, this transformation can be seen as a
 * guess that the pointer analysis will check.
 */

private const val NUM_ITERATIONS = 5

fun splitWideStores(cfg: MutableSbfCFG,
                    globals: GlobalVariableMap,
                    memSummaries: MemorySummaries) {
    var change = true
    var i = 0
    // see test03 in splitWideStores to understand why we might need to run the scalar analysis
    // multiple times
    while (change && i < NUM_ITERATIONS) {
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries)
        change = splitWideStores(cfg, scalarAnalysis)
        i++
    }
}


private fun splitWideStores(cfg: MutableSbfCFG, scalarAnalysis: ScalarAnalysis): Boolean {
    val scalarsAtInst = ScalarAnalysisRegisterTypes(scalarAnalysis)
    var change = false
    for (block in cfg.getMutableBlocks().values) {
        val narrowStores = ArrayList<Triple<Int, SbfInstruction.Mem, Long>>()
        // Identify the wide stores based on some heuristics
        findSplitWideStoresOf16(block, scalarsAtInst, scalarAnalysis, narrowStores)
        findSplitWideStoresOf64(block, scalarsAtInst, scalarAnalysis, narrowStores)
        // Replace the original wide-size store instruction into two store instructions of half-size each one.
        var addedInsts = 0
        for ((i, storeInst, immVal) in narrowStores) {
            splitWideStore(block, i + addedInsts, storeInst, immVal)
            addedInsts++
        }
        change = change or narrowStores.isNotEmpty()
    }
    return change
}

private fun findSplitWideStoresOf16(block: MutableSbfBasicBlock, // mutable because we add metadata
                                    scalarsAtInst: ScalarAnalysisRegisterTypes,
                                    @Suppress("UNUSED_PARAMETER")scalarsAtBlock: ScalarAnalysis,
                                    worklist: MutableList<Triple<Int, SbfInstruction.Mem, Long>>)  {
    // REVISIT: based on constants found in a project
    // 256 (0000_0001_0000_0000) and 265 (0000_0001_00001001) are special:
    // the high 8 bits encodes whether error or not and the low 8 bits the value if no error.
    val magicNumbers = setOf(256L, 265L)
    for (locInst in block.getLocatedInstructions()) {
        val inst = locInst.inst
        val pos = locInst.pos
        if (inst is SbfInstruction.Mem && !inst.isLoad && inst.access.width.toInt() == 2) {
            val immVal = isStoreOfImmVal(locInst, scalarsAtInst)
            if (immVal != null && magicNumbers.contains(immVal)) {
                if (getStackAccess(locInst, scalarsAtInst) != null) {
                    // We replace in-place the instruction to add new metadata
                    val newMetaData = inst.metaData.plus(Pair(SbfMeta.HINT_OPTIMIZED_WIDE_STORE, ""))
                    val newInst = inst.copy(metaData = newMetaData)
                    block.replaceInstruction(pos, newInst)
                    worklist.add(Triple(pos, newInst, immVal))
                }
            }
        }
    }
}
private fun findSplitWideStoresOf64(block: MutableSbfBasicBlock, // mutable because we add metadata
                                    scalarsAtInst: ScalarAnalysisRegisterTypes,
                                    scalarsAtBlock: ScalarAnalysis,
                                    worklist: MutableList<Triple<Int, SbfInstruction.Mem, Long>>)  {
    val inverseSiblings = getInverseSiblings(block)
    if (inverseSiblings.isNotEmpty()) {
        for (locInst in block.getLocatedInstructions()) {
            val inst = locInst.inst
            val pos = locInst.pos
            if (inst is SbfInstruction.Mem && !inst.isLoad && inst.access.width.toInt() == 8) {
                // store of 64 bits
                val immVal = isStoreOfImmVal(locInst, scalarsAtInst)
                if (immVal != null) {
                    // store of 64 bits of an immediate value
                    val stackAccess = getStackAccess(locInst, scalarsAtInst)
                    if (stackAccess != null) {
                        // stack store of 64 bits of an immediate value
                        /// check that at least in one sibling there is a store of an immediate value at the same offset
                        /// but with width=4. Note that we don't check for an actual store instruction but instead, we ask the
                        /// scalar domain if at the end of each block, the corresponding stack content has stored an immediate value.
                        if (inverseSiblings.any { inverseSibling ->
                                val post = scalarsAtBlock.getPost(inverseSibling.getLabel())
                                val x = post?.getStackContent(stackAccess.offset, 4.toByte())?.get()
                                // Note that we don't need to know the exact number
                                (x != null && x is SbfType.NumType)
                            }) {
                            // We replace in-place the instruction to add new metadata
                            val newMetaData = inst.metaData.plus(Pair(SbfMeta.HINT_OPTIMIZED_WIDE_STORE, ""))
                            val newInst = inst.copy(metaData = newMetaData)
                            block.replaceInstruction(pos, newInst)
                            worklist.add(Triple(pos, newInst, immVal))
                        }
                    }
                }
            }
        }
    }
}

fun splitWideStore(block: MutableSbfBasicBlock, i: Int, inst: SbfInstruction.Mem, immVal: Long) {
    check(!inst.isLoad) {"splitWideStore expects a store instruction "}
    check(inst.access.width.toInt() == 2 || inst.access.width.toInt() == 8)
    {"splitWideStore expects only stores of 2 or 8 bytes"}

    val newWidth = if (inst.access.width.toInt() == 8) { 4.toShort() } else { 1.toShort()}
    val (low, high) = if (inst.access.width.toInt() == 8) {
        Pair(immVal.toInt(), immVal.ushr(32).toInt())
    } else {
        Pair(immVal.toByte().toInt(), immVal.ushr(8).toInt())
    }

    val baseR = inst.access.baseReg
    val offset = inst.access.offset
    val metadata = inst.metaData
    val firstStore = SbfInstruction.Mem(Deref(newWidth, baseR, offset), Value.Imm(low.toULong()), false, null, metadata)
    val secondStore = SbfInstruction.Mem(Deref(newWidth, baseR, (offset + newWidth).toShort()), Value.Imm(high.toULong()), false, null, metadata)
    block.replaceInstruction(i, firstStore)
    block.add(i+1, secondStore)
}



/** Return non-null if [locInst] is a store instruction and an immediate value is being stored **/
private fun isStoreOfImmVal(locInst: LocatedSbfInstruction, scalarsAtInst: ScalarAnalysisRegisterTypes): Long? {
    val inst = locInst.inst
    check(inst is SbfInstruction.Mem && !inst.isLoad) {"getStoredImmVal expects a store instruction"}
    val value = inst.value
    if (value is Value.Imm) {
        return value.v.toLong()
    }

    val valueType = scalarsAtInst.typeAtInstruction(locInst, (value as Value.Reg).r)
    if (valueType is SbfType.NumType) {
        return valueType.value.get()
    }

    return null
}


private data class StackAccess(val offset: Long, val width: Short)

/** Return non-null if [locInst] is accessing to the stack **/
private fun getStackAccess(locInst: LocatedSbfInstruction, scalarsAtInst: ScalarAnalysisRegisterTypes): StackAccess? {
    val inst = locInst.inst
    check(inst is SbfInstruction.Mem) {"getStackAccess expects a memory instruction"}
    val typeDerefReg = scalarsAtInst.typeAtInstruction(locInst, inst.access.baseReg.r)
    if (typeDerefReg is SbfType.PointerType.Stack) {
        val offset = typeDerefReg.offset.add(inst.access.offset.toLong()).get()
        if (offset != null) {
            return StackAccess(offset, inst.access.width)
        }
    }
    return null
}

/** Return other immediate predecessors of the succesors of [block] **/
private fun getInverseSiblings(block: SbfBasicBlock): List<SbfBasicBlock> {
    val siblings = ArrayList<SbfBasicBlock>()
    for (succ in block.getSuccs())  {
        for (pred in succ.getPreds()) {
            if (pred != block) {
                siblings.add(pred)
            }
        }
    }
    return siblings
}
