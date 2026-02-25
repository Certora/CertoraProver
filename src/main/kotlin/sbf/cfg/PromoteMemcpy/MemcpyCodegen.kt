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

import datastructures.stdcollections.*

fun emitMemcpyVariant(
    srcWidth: ULong,
    dstWidth: ULong,
    srcMemAccess: MemAccess,
    dstMemAccess: MemAccess,
    metaData: MetaData
): List<SbfInstruction>? = when {
    srcWidth == dstWidth ->
        emitMemcpy(srcMemAccess.reg, srcMemAccess.offset, dstMemAccess.reg, dstMemAccess.offset, srcWidth, metaData)
    srcWidth == 8UL && dstWidth < 8UL ->
        emitMemcpyTrunc(srcMemAccess.reg, srcMemAccess.offset, dstMemAccess.reg, dstMemAccess.offset, dstWidth, metaData)
    dstWidth == 8UL && srcWidth < 8UL ->
        emitMemcpyZExt(srcMemAccess.reg, srcMemAccess.offset, dstMemAccess.reg, dstMemAccess.offset, srcWidth, metaData)
    else -> null
}

/**
 * @property loads The original loads
 * @property stores The original stores
 * @property insts The new code that will replace [loads] and [stores]
 *
 * [loads] must be in the same basic block.
 * [stores] must be in the same basic block.
 * However [loads] and [stores] can be in different blocks.
 */
data class MemcpyRewrite(
    val loads: List<LocatedSbfInstruction>,
    val stores: List<LocatedSbfInstruction>,
    val insts: List<SbfInstruction>
) {
    init {
        check(loads.isNotEmpty())
        check(stores.isNotEmpty())
        check(loads.size == stores.size)
        // All loads must be in the same block
        check(loads.map { it.label }.distinct().size == 1) {
            "All loads must be in the same basic block"
        }
        // All stores must be in the same block
        check(stores.map { it.label }.distinct().size == 1) {
            "All stores must be in the same basic block"
        }
    }
}

/**
 *  Apply each rewrite from [rewrites].
 *
 *  Stores are removed but loads are left intact because they can be used by other instructions.
 *  Subsequent optimizations will remove the load instructions if they are dead.
 *
 *  Recall that all loads (stores) must be in the same basic block, **but** loads and stores
 *  can be in different blocks.
 **/
fun applyRewrites(cfg: MutableSbfCFG, rewrites: List<MemcpyRewrite>) {
    val sortedRewrites = sortRewrites(rewrites)

    if (sortedRewrites.isEmpty()) {
        return
    }

    // Add metadata to all load and store instructions
    for (rewrite in sortedRewrites) {
        val loadBlock = checkNotNull(cfg.getMutableBlock(rewrite.loads.first().label))
        for (loadLocInst in rewrite.loads) {
            addMemcpyPromotionAnnotation(loadBlock, loadLocInst)
        }

        val storeBlock = checkNotNull(cfg.getMutableBlock(rewrite.stores.first().label))
        for (storeLocInst in rewrite.stores) {
            addMemcpyPromotionAnnotation(storeBlock, storeLocInst)
        }
    }

    // Group rewrites by the block containing their loads
    val rewritesByBlock = sortedRewrites.groupBy { it.loads.first().label }

    // Add memcpy instructions block by block
    // We need to add the memcpy instructions before the first load.
    // For an explanation, see test13 in PromoteStoresToMemcpyTest.kt
    for ((label, blockRewrites) in rewritesByBlock) {
        val block = checkNotNull(cfg.getMutableBlock(label))
        var numAdded = 0
        for (rewrite in blockRewrites) {
            val loads = rewrite.loads.sortedBy { it.pos }
            val firstLoad = loads.first()
            val insertPoint = firstLoad.pos + numAdded
            numAdded += rewrite.insts.size
            block.addAll(insertPoint, rewrite.insts)
        }
    }


    // Collect block labels that contain stores
    val storeBlockLabels = sortedRewrites.map { it.stores.first().label }.toSet()

    // Finally, remove the store instructions marked with `MEMCPY_PROMOTION` metadata
    // We scan all blocks to find annotated stores
    for (label in storeBlockLabels) {
        val toRemove = ArrayList<LocatedSbfInstruction>()
        val block = checkNotNull(cfg.getMutableBlock(label))
        for (locInst in block.getLocatedInstructions()) {
            val inst = locInst.inst
            if (inst is SbfInstruction.Mem && !inst.isLoad &&
                inst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null) {
                toRemove.add(locInst)
            }
        }

        for ((numRemoved, locInst) in toRemove.withIndex()) {
            val adjPos = locInst.pos - numRemoved
            val inst = block.getInstruction(adjPos)
            check(inst is SbfInstruction.Mem && !inst.isLoad) {
                "applyRewrites expects a store instruction"
            }
            block.removeAt(adjPos)
        }
    }
}

/**
 * Sort [rewrites] by the position of their first load in the block.
 *
 * This simplifies adjusting insertion points as we insert the emitted code.
 * Without sorting, tracking insertion point adjustments would be unnecessarily complicated.
 */
private fun sortRewrites(rewrites: List<MemcpyRewrite>): List<MemcpyRewrite> {
    // Group rewrites by the block containing their loads
    val rewritesByBlock = rewrites.groupBy { it.loads.first().label }

    // Sort rewrites within each block by position of first load
    val sortedRewrites = mutableListOf<MemcpyRewrite>()

    for ((_, blockRewrites) in rewritesByBlock) {
        val sorted = blockRewrites.sortedBy { rewrite ->
            rewrite.loads.minOf { it.pos }
        }
        sortedRewrites.addAll(sorted)
    }

    return sortedRewrites
}

private fun addMemcpyPromotionAnnotation(bb: MutableSbfBasicBlock, locInst: LocatedSbfInstruction) {
    val inst = locInst.inst
    if (inst is SbfInstruction.Mem) {
        val newMetaData = inst.metaData + SbfMeta.MEMCPY_PROMOTION()
        val newInst = inst.copy(metaData = newMetaData)
        bb.replaceInstruction(locInst.pos, newInst)
    }
}
