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

import sbf.disassembler.*
import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.analysis.*
import sbf.domains.*

/**
 * LLVM can sometimes optimize two consecutive memory stores into a single store if the stored value is an immediate value.
 * This transformation reverts this optimization because the prover works under the assumption that reads must match last writes,
 * and optimizations like this can break this assumption.
 *
 * Since this transformation only rewrites stores on the stack, we are always sound because if the transformation splits a store
 * in a wrong way (i.e., a read won't match) then the pointer analysis will complain. Thus, this transformation can be seen as a
 * guess that the pointer analysis will check.
 */

fun splitWideStores(cfg: MutableSbfCFG,
                    globals: GlobalVariables,
                    memSummaries: MemorySummaries) {
    val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
    val scalarAnalysis = GenericScalarAnalysis(
        cfg,
        globals,
        memSummaries,
        sbfTypesFac,
        CFGTransformScalarDomFac()
    )
    splitWideStores(cfg, scalarAnalysis)
}

private fun <D, TNum, TOffset> splitWideStores(
    cfg: MutableSbfCFG,
    scalarAnalysis: IAnalysis<D>
)
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    val types = AnalysisRegisterTypes(scalarAnalysis)
    for (block in cfg.getMutableBlocks().values) {
        val wideStores = mutableListOf<WideStore>()
        // Identify the wide stores based on some heuristics
        findSplitWideStoresOf16(block, types, wideStores)
        findSplitWideStoresOf64(block, types, wideStores)

        // Replace the original wide store with the same one but with some extra metadata
        // This does not invalidate locInst's positions
        wideStores.forEach { wideStore ->
            block.replaceInstruction(wideStore.pos, wideStore.inst)
        }

        // Replace the original wide-size store instruction into two store instructions of half-size each one.
        var addedInsts = 0
        for ((i, storeInst, immVal) in wideStores) {
            splitWideStore(block, i + addedInsts, storeInst, immVal)
            addedInsts++
        }
    }
}

private data class WideStore(
    val pos: Int, // position of the store in the block
    val inst: SbfInstruction.Mem, // the store instruction
    val storedValue: Long // the stored value
) {
    init { check(!inst.isLoad) }
}

private fun <D, TNum, TOffset> findSplitWideStoresOf16(
    block: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    wideStores: MutableList<WideStore>)
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    // REVISIT: based on constants found in a project
    // 256 (0000_0001_0000_0000) and 265 (0000_0001_00001001) are special:
    // the high 8 bits encodes whether error or not and the low 8 bits the value if no error.
    val magicNumbers = setOf(256L, 265L)
    for (locInst in block.getLocatedInstructions()) {
        val inst = locInst.inst
        val pos = locInst.pos
        if (inst is SbfInstruction.Mem && !inst.isLoad && inst.access.width.toInt() == 2) {
            val immVal = isStoreOfImmVal(locInst, types)
            if (immVal != null && magicNumbers.contains(immVal)) {
                if (getStackAccess(locInst, types) != null) {
                    val newMetaData = inst.metaData.plus(SbfMeta.HINT_OPTIMIZED_WIDE_STORE())
                    val newInst = inst.copy(metaData = newMetaData)
                    wideStores.add(WideStore(pos, newInst, immVal))
                }
            }
        }
    }
}
private fun <D, TNum, TOffset> findSplitWideStoresOf64(
    block: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    wideStores: MutableList<WideStore>)
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset>  {
    val inverseSiblings = getInverseSiblings(block)
    if (inverseSiblings.isNotEmpty()) {
        for (locInst in block.getLocatedInstructions()) {
            val inst = locInst.inst
            val pos = locInst.pos
            if (inst is SbfInstruction.Mem && !inst.isLoad && inst.access.width.toInt() == 8) {
                // store of 64 bits
                val immVal = isStoreOfImmVal(locInst, types)
                if (immVal != null) {
                    // store of 64 bits of an immediate value
                    val stackAccess = getStackAccess(locInst, types)
                    if (stackAccess != null) {
                        // stack store of 64 bits of an immediate value
                        /// check that at least in one sibling there is a store of an immediate value at the same offset
                        /// but with width=4. Note that we don't check for an actual store instruction but instead, we ask the
                        /// scalar domain if at the end of each block, the corresponding stack content has stored an immediate value.
                        if (inverseSiblings.any { inverseSibling ->
                                val post = types.analysis.getPost((inverseSibling.getLabel()))
                                val x = post?.getStackContent(stackAccess.offset, 4.toByte())?.type()
                                // Note that we don't need to know the exact number
                                (x != null && x is SbfType.NumType<TNum, TOffset>)
                            }) {
                            val newMetaData = inst.metaData.plus(SbfMeta.HINT_OPTIMIZED_WIDE_STORE())
                            val newInst = inst.copy(metaData = newMetaData)
                            wideStores.add(WideStore(pos, newInst, immVal))
                        }
                    }
                }
            }
        }
    }
}

private fun splitWideStore(block: MutableSbfBasicBlock, i: Int, inst: SbfInstruction.Mem, immVal: Long) {
    check(!inst.isLoad) {"splitWideStore expects a store instruction "}
    check(inst.access.width.toInt() == 2 || inst.access.width.toInt() == 8)
    {"splitWideStore expects only stores of 2 or 8 bytes"}

    val newWidth = if (inst.access.width.toInt() == 8) { 4.toShort() } else { 1.toShort()}
    val (low, high) = if (inst.access.width.toInt() == 8) {
        Pair(immVal.toInt(), immVal.ushr(32).toInt())
    } else {
        Pair(immVal.toByte().toInt(), immVal.ushr(8).toInt())
    }

    val baseR = inst.access.base
    val offset = inst.access.offset
    val metadata = inst.metaData
    val firstStore = SbfInstruction.Mem(Deref(newWidth, baseR, offset), Value.Imm(low.toULong()), false, metadata)
    val secondStore = SbfInstruction.Mem(Deref(newWidth, baseR, (offset + newWidth).toShort()), Value.Imm(high.toULong()), false,  metadata)
    block.replaceInstruction(i, firstStore)
    block.add(i+1, secondStore)
}



/** Return non-null if [locInst] is a store instruction and an immediate value is being stored **/
private fun <D, TNum, TOffset> isStoreOfImmVal(
    locInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>): Long?
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Mem && !inst.isLoad)
    return when(val value = inst.value) {
        is Value.Imm -> value.v.toLong()
        is Value.Reg -> (types.typeAtInstruction(locInst, value.r) as? SbfType.NumType)?.value?.toLongOrNull()
    }
}


private data class StackAccess(
    val offset: Long,
    val width: Short
)

/** Return non-null if [locInst] is accessing to the stack **/
private fun <D, TNum, TOffset> getStackAccess(
    locInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>): StackAccess?
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Mem)
    val baseRegTy = (types.typeAtInstruction(locInst, inst.access.base) as? SbfType.PointerType.Stack)
        ?: return null
    val resolvedOffset = baseRegTy.offset.add(inst.access.offset.toLong()).toLongOrNull()
        ?: return null
    return StackAccess(resolvedOffset, inst.access.width)
}

/** Return other immediate predecessors of the succesors of [block] **/
private fun getInverseSiblings(block: SbfBasicBlock): List<SbfBasicBlock> {
    val siblings = mutableListOf<SbfBasicBlock>()
    for (succ in block.getSuccs())  {
        for (pred in succ.getPreds()) {
            if (pred != block) {
                siblings.add(pred)
            }
        }
    }
    return siblings
}
