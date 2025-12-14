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

import sbf.domains.FiniteInterval
import sbf.SolanaConfig
import sbf.callgraph.SolanaFunction
import sbf.disassembler.*
import sbf.domains.*
import datastructures.stdcollections.*
import log.*
import org.jetbrains.annotations.TestOnly
import sbf.analysis.AdaptiveScalarAnalysis
import sbf.analysis.AnalysisRegisterTypes
import sbf.analysis.IAnalysis
import kotlin.math.absoluteValue

private val logger = Logger(LoggerTypes.SBF_MEMCPY_PROMOTION)
private fun info(msg: () -> Any) { logger.info(msg)}
private fun dbg(msg: () -> Any) { logger.debug(msg)}

/**
 *  Promote sequence of loads and stores into `memcpy` instructions.
 *  The transformation is intra-block.
 */
fun promoteStoresToMemcpy(cfg: MutableSbfCFG,
                          globals: GlobalVariables,
                          memSummaries: MemorySummaries) {
    val scalarAnalysis = AdaptiveScalarAnalysis(cfg, globals, memSummaries)
    promoteStoresToMemcpy(cfg, scalarAnalysis)
}

@TestOnly
fun <D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> promoteStoresToMemcpy(
    cfg: MutableSbfCFG,
    scalarAnalysis: IAnalysis<D>,
    // For tests, it's sometimes convenient to disable it
    aggressivePromotion: Boolean = true)
    where D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    reorderLoads(cfg, scalarAnalysis)

    val types = AnalysisRegisterTypes(scalarAnalysis)
    var numOfInsertedMemcpy = 0
    for (b in cfg.getMutableBlocks().values) {

        findMemcpyPatterns(b, types).let { rewrites ->
            applyRewrites(b, rewrites)
            numOfInsertedMemcpy += rewrites.size
        }

        if (aggressivePromotion) {
            /*
            * Since we promote `memcpy` instructions in a greedy manner we might lose some simple promotions.
            * For instance, with this code:
            * ```
            *    r1 := *(u64 *) (r10 + -528):sp(40432)
            *    *(u64 *) (r10 + -376):sp(40584) := r1
            *    r1 := *(u64 *) (r10 + -520):sp(40440)
            *    *(u64 *) (r10 + -384):sp(40576) := r1
            * ```
            * we fail to promote the two loads and stores to a memcpy of 16 bytes because of the "ordering". That is,
            * the content of 40432 (low) is written to 40584 (high) and the content of 40440 (high) to 40576 (low).
            *
            * However, we can promote the two pairs of load-store to two separate `memcpy` of 8 bytes each one.
            * To do that, we do another round where we search for single pairs of load-store.
            */
            findMemcpyPatterns(b, types, maxNumOfPairs = 1).let { rewrites ->
                applyRewrites(b, rewrites)
                numOfInsertedMemcpy += rewrites.size
            }
        }
    }

    info{
        "Number of memcpy instructions inserted: $numOfInsertedMemcpy"
    }
}

/**
 * Find at most [maxNumOfPairs] load/store pairs within [bb] that can be promoted to `memcpy`, in a greedy manner.
 *
 * Algorithm:
 *
 * We scan all instructions within [bb]
 * 1. If the instruction is a load then we remember it in `defLoads` map.
 * 2. If the instruction is a store then we try to pair it with a load from `defLoads`.
 *    This is done by [processStoreOfLoad]. This function can return false for several reasons.
 *    For instance, if the store is far from the load then we need to prove that there is no other stores that might modify
 *    the loaded memory location.
 *    Since we try to find the maximal number of load/store pairs (i.e., the longest memcpy), we require that the accessed memory has no gaps.
 *    If there are some gaps then [processStoreOfLoad] will also return false.
 * 3. If at any time, [processLoadStorePair] returns false we check how many pairs of load-stores we have. If any then
 *    we replace them with a `memcpy` instruction. When replacing more than one load/store pair some extra conditions must also hold.
 *    This is checked by [canBePromoted].
 *
 *  @param [bb] basic block whether promotion will take place
 *  @param [types] info from the scalar analysis
 *  @param [maxNumOfPairs] maximum number of pairs of loads and stores
 *  @param [minSizeToBePromoted] minimum number of bytes to be transferred for the promoted memcpy
 */
private fun <D, TNum, TOffset> findMemcpyPatterns(
    bb: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    maxNumOfPairs: Int = Int.MAX_VALUE,
    minSizeToBePromoted: ULong = 1UL
): List<MemTransferRewrite>
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    // list of rewrites to be returned
    val rewrites = mutableListOf<MemTransferRewrite>()

    // used to find the definition of a value to be stored
    val defLoads = mutableMapOf<SbfRegister, LocatedSbfInstruction>()
    // Note that we allow inserting multiple memcpy instructions in the same basic block
    // We try eagerly to group together the maximal number of stores
    var memcpyPattern = MemcpyPattern()
    for (locInst in bb.getLocatedInstructions()) {
        val inst = locInst.inst
        if (inst is SbfInstruction.Mem) {

            if (inst.isLoad) {
                defLoads[(inst.value as Value.Reg).r] = locInst
            } else {
                val value = inst.value
                if (value !is Value.Reg) {
                    continue
                }
                val loadInst = defLoads[value.r] ?: continue
                val canProcessPair = processLoadStorePair(bb, locInst, loadInst, types, memcpyPattern)
                if (memcpyPattern.getLoads().size >= maxNumOfPairs || !canProcessPair) {

                    // If we cannot insert the store-of-load pair, then we check if we can promote
                    // the pairs we have so far.
                    memcpyPattern.canBePromoted(minSizeToBePromoted)?.let { promoted ->
                        val memcpyInsts = emitMemcpy(
                            promoted.srcReg,
                            promoted.srcStart,
                            promoted.dstReg,
                            promoted.dstStart,
                            promoted.size,
                            promoted.metadata
                        )
                        rewrites.add(
                            MemTransferRewrite(
                                memcpyPattern.getLoads(),
                                memcpyPattern.getStores(),
                                memcpyInsts
                            )
                        )
                    }

                    // We start a fresh sequence of store-of-load pairs
                    memcpyPattern = MemcpyPattern()
                    processLoadStorePair(bb, locInst, loadInst, types, memcpyPattern)
                }
            }
        } else {
            // If the instruction is not a memory instruction, then we check if we can promote
            // the store-of-load pairs we have
            memcpyPattern.canBePromoted(minSizeToBePromoted)?.let { promoted ->
                val memcpyInsts = emitMemcpy(
                    promoted.srcReg,
                    promoted.srcStart,
                    promoted.dstReg,
                    promoted.dstStart,
                    promoted.size,
                    promoted.metadata
                )
                rewrites.add(
                    MemTransferRewrite(
                        memcpyPattern.getLoads(),
                        memcpyPattern.getStores(),
                        memcpyInsts
                    )
                )
                memcpyPattern = MemcpyPattern()
            }

            // Kill any active load if its defined register is overwritten
            for (reg in inst.writeRegister) {
                defLoads.remove(reg.r)
            }
        }
    }

    // Important: we sort `rewrites` by the position of its first load that appears in the block
    // We need this because we need to adjust the insertion points while we will insert the emitted memcpy code.
    // If `rewrites` is not sorted then the adjustment becomes unnecessarily complicated.
    return rewrites.sortedBy { rewrite ->
        // Return the position of the first load of `it` within the block to be promoted
        val loads = rewrite.loads
        check(loads.isNotEmpty()) {"$rewrite should have non-empty load instructions"}
        loads.minByOrNull { locInst -> locInst.pos }!!.pos
    }
}

/**
 * Return true if [loadLocInst] is the loaded offset stored in [storeLocInst] and some conditions hold
 * (see [isSafeToCommuteStore] and method `add` from [MemcpyPattern])
 **/
private fun <D, TNum, TOffset> processLoadStorePair(
    bb: SbfBasicBlock,
    storeLocInst: LocatedSbfInstruction,
    loadLocInst: LocatedSbfInstruction,
    regTypes: AnalysisRegisterTypes<D, TNum, TOffset>,
    memcpy: MemcpyPattern
): Boolean
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    val storeInst = storeLocInst.inst
    val loadInst = loadLocInst.inst
    check(storeInst is SbfInstruction.Mem && !storeInst.isLoad)
    check(loadInst is SbfInstruction.Mem && loadInst.isLoad)

    val width = loadInst.access.width
    if (width != storeInst.access.width) {
        return false
    }
    val loadedMemAccess = normalizeLoadOrStore(loadLocInst, regTypes)
    val storedMemAccess = normalizeLoadOrStore(storeLocInst, regTypes)

    // This restriction is needed when we emit the code because if both base registers in [load] and [store]
    // are scratch registers then we cannot perform the transformation because we run out of registers
    // where we can save values.
    return when {
        loadedMemAccess.region != MemAccessRegion.STACK && storedMemAccess.region != MemAccessRegion.STACK -> false
        !isSafeToCommuteStore(bb, loadedMemAccess, loadLocInst, storedMemAccess, storeLocInst, regTypes) -> false
        else -> memcpy.add(loadedMemAccess, loadLocInst, storedMemAccess, storeLocInst)
    }
}

private data class Memcpy(
    val srcReg: SbfRegister,
    val srcStart: Long,
    val dstReg: SbfRegister,
    val dstStart: Long,
    val size: ULong,
    val metadata: MetaData)

/** This class contains the sequence of load/store pairs to be replaced __safely__ by a `memcpy` **/
private class MemcpyPattern {
    /** Class invariants:
     *  0. `loads.size == stores.size`
     *  1. `forall 0 <= i < size-1 :: distance(loads[i ],loads[i+1]) == distance(stores[i ], stores[i+1])`
     *  2. `forall i,j, i!=j :: loads[i ].region == loads[j ].region && stores[i ].region == stores[j ].region`
     *  3. `forall i,j, i!=j :: loads[i ].reg == loads[j ].reg && stores[i ].reg == stores[j ].reg`
     **/
    private val loads = mutableListOf<MemAccess>()
    private val stores = mutableListOf<MemAccess>()
    private val loadLocInsts = mutableListOf<LocatedSbfInstruction>()
    private val storeLocInsts = mutableListOf<LocatedSbfInstruction>()

    fun getStores(): List<LocatedSbfInstruction> = storeLocInsts
    fun getLoads(): List<LocatedSbfInstruction> = loadLocInsts

    /**
     * Return false if it cannot maintain all class invariants (See above).
     * Otherwise, it will add the [load]/[store] pair as part of a memcpy.
     **/
    fun add(load: MemAccess,
            loadLocInst: LocatedSbfInstruction,
            store: MemAccess,
            storeLocInst: LocatedSbfInstruction
    ): Boolean {
        check(loadLocInst.label == storeLocInst.label)
        {"can only promote pairs of load-store within the same block"}

        if (loads.isEmpty()) {
            loads.add(load)
            stores.add(store)
            loadLocInsts.add(loadLocInst)
            storeLocInsts.add(storeLocInst)
            return true
        } else {
            val lastLoad = loads.last()
            val lastStore = stores.last()
            // class invariant #3
            if (lastLoad.reg != load.reg || lastStore.reg != store.reg) {
                return false
            }
            // class invariant #2
            if (lastLoad.region != load.region || lastStore.region != store.region) {
                return false
            }
            // class invariant #1
            val loadDiff = (load.offset - lastLoad.offset).absoluteValue
            val storeDiff = (store.offset - lastStore.offset).absoluteValue
            return if (loadDiff == storeDiff) {
                loads.add(load)
                stores.add(store)
                loadLocInsts.add(loadLocInst)
                storeLocInsts.add(storeLocInst)
                true
            } else {
                false
            }
        }
    }

    /**
     * Return non-null if
     * (1) source and destination do not overlap and
     * (2) the sequence of loads and stores accesses memory in the same ordering (decreasing or increasing) and
     * (3) the sequences form a consecutive range of bytes without holes in between.
     */
    fun canBePromoted(minSizeToBePromoted: ULong): Memcpy?  {
        val name = "canBePromoted"
        check(loads.size == stores.size) {
            "$name expects same number of loads and stores: $loads and $stores"
        }
        check(loadLocInsts.size == storeLocInsts.size) {
            "$name expects same number of loads and stores: $loadLocInsts and $storeLocInsts"
        }
        check(loads.size == loadLocInsts.size) {
            "$name: $loads and $loadLocInsts should have same size"
        }
        check(stores.size == storeLocInsts.size) {
            "$name: $stores and $storeLocInsts should have same size"
        }

        // Ensure that no overlaps between source and destination
        fun noOverlaps(srcRegion: MemAccessRegion, srcStart: Long,
                       dstRegion: MemAccessRegion, dstStart: Long,
                       size: ULong): Boolean {

            if (srcRegion == MemAccessRegion.STACK && dstRegion == MemAccessRegion.STACK) {
                val i1 = FiniteInterval.mkInterval(srcStart, size.toLong())
                val i2 = FiniteInterval.mkInterval(dstStart, size.toLong())
                return (!i1.overlap(i2))
            } else if (srcRegion != dstRegion && srcRegion != MemAccessRegion.ANY && dstRegion != MemAccessRegion.ANY) {
                return true
            } else {
                return if (SolanaConfig.OptimisticMemcpyPromotion.get()) {
                    dbg {
                        "$name: we cannot prove that no overlaps between $loadLocInsts and $storeLocInsts"
                    }
                    true
                } else {
                    false
                }
            }
        }

        /**
         *  Find a single interval for all loads and another single interval for all stores.
         *  If it cannot then it removes the last inserted load and store and try again.
         *  This is a greedy approach, so it's not optimal.
         */
        fun getRangeForLoadsAndStores(): Pair<FiniteInterval, FiniteInterval>? {
            while (loads.isNotEmpty()) { // this loop is needed by test24
                var srcIntervals = SetOfFiniteIntervals.new()
                var dstIntervals = SetOfFiniteIntervals.new()
                var prevLoad: MemAccess? = null
                var prevStore: MemAccess? = null
                for ((load, store) in loads.zip(stores)) {
                    if (prevLoad != null && prevStore != null) {
                        val loadDist = load.offset - prevLoad.offset
                        val storeDist = store.offset - prevStore.offset
                        if (loadDist != storeDist) {
                            // loads and stores have different ordering, so it's not a memcpy
                            // Note that we completely give up here even if we could also try smaller
                            // number of pairs of load-store.
                            return null
                        }
                    }
                    srcIntervals = srcIntervals.add(FiniteInterval.mkInterval(load.offset, load.width.toLong()))
                    dstIntervals = dstIntervals.add(FiniteInterval.mkInterval(store.offset, store.width.toLong()))

                    prevLoad = load
                    prevStore = store
                }
                val srcSingleton = srcIntervals.getSingleton()
                val dstSingleton = dstIntervals.getSingleton()
                if (srcSingleton != null && dstSingleton != null) {
                    return Pair(srcSingleton, dstSingleton)
                } else {
                    // Before we return null we remove the last inserted pair and try again
                    loads.removeLast()
                    stores.removeLast()
                    loadLocInsts.removeLast()
                    storeLocInsts.removeLast()
                }
            }
            return null
        }

        if (loads.isEmpty()) {
            return null
        }
        val p = getRangeForLoadsAndStores() ?: return null
        val srcRange = p.first
        val dstRange = p.second
        return if (srcRange.size() == dstRange.size() && srcRange.size() >= minSizeToBePromoted) {
            val srcReg = loads.first().reg
            val dstReg = stores.first().reg
            val srcRegion = loads.first().region
            val dstRegion = stores.first().region
            // We will use the metadata of the first load as metadata of the promoted memcpy
            val metadata = loadLocInsts.first().inst.metaData
            if (noOverlaps(srcRegion, srcRange.l, dstRegion, dstRange.l, srcRange.size())) {
                Memcpy(srcReg, srcRange.l, dstReg, dstRange.l, srcRange.size(), metadata)
            } else {
                null
            }
        } else {
            null
        }
    }
}


/**
 * Represents a recognized memory transfer pattern (e.g. memcpy).
 *
 * The `loads` and `stores` together describe a semantically equivalent
 * operation that can be replaced by `replacement`.
 */
data class MemTransferRewrite(
    val loads: List<LocatedSbfInstruction>,
    val stores: List<LocatedSbfInstruction>,
    val replacement: List<SbfInstruction>
)

/**
 *  Apply each rewrite from [rewrites] in [bb].
 *
 *  Stores are removed but loads are left intact because they can be used by other instructions.
 *  Subsequent optimizations will remove the load instructions if they are dead.
 **/
private fun applyRewrites(bb: MutableSbfBasicBlock, rewrites: List<MemTransferRewrite>) {
    // Add metadata to all load and store instructions to be promoted.
    // This is done **without** inserting or removing any instruction so all indexes in memcpyInfo are still valid.
    //
    // This metadata is needed to mark this instructions for the next loop.
    // Eventually, only load instructions that used by other instructions will maintain that metadata.
    // In that case, it's only used for debugging purposes.
    for (rewrite in rewrites) {
        for (loadLocInst in rewrite.loads) {
            addMemcpyPromotionAnnotation(bb, loadLocInst)
        }
        for (storeLocInst in rewrite.stores) {
            addMemcpyPromotionAnnotation(bb, storeLocInst)
        }
    }

    //  Add the memcpy instructions.
    //  We need to add the memcpy instructions before the first load.
    //  For an explanation, see test13 in PromoteStoresToMemcpyTest.kt
    var numAdded = 0   // used to adjust the insertion points after each memcmpy is inserted
    for (rewrite in rewrites) {
        val loads = rewrite.loads.sortedBy { it.pos}
        val firstLoad = loads.firstOrNull()
        check(firstLoad != null) {"memcpyInfo should not be empty"}
        val insertPoint = firstLoad.pos + numAdded
        numAdded += rewrite.replacement.size
        bb.addAll(insertPoint, rewrite.replacement)
    }

    // Finally, we remove the store instructions marked before with `MEMCPY_PROMOTION` metadata
    val toRemove = ArrayList<LocatedSbfInstruction>()
    for (locInst in bb.getLocatedInstructions()) {
        val inst = locInst.inst
        if (inst is SbfInstruction.Mem && !inst.isLoad && inst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null) {
            toRemove.add(locInst)
        }
    }
    for ((numRemoved, locInst) in toRemove.withIndex()) {
        val adjPos = locInst.pos - (numRemoved)
        val inst = bb.getInstruction(adjPos)
        check(inst is SbfInstruction.Mem && !inst.isLoad) {
            "applyRewrites expects a store instruction"
        }

        bb.removeAt(adjPos)
    }
}

private fun addMemcpyPromotionAnnotation(bb: MutableSbfBasicBlock, locInst: LocatedSbfInstruction) {
    val inst = locInst.inst
    if (inst is SbfInstruction.Mem) {
        val newMetaData = inst.metaData.plus(Pair(SbfMeta.MEMCPY_PROMOTION, ""))
        val newInst = inst.copy(metaData = newMetaData)
        bb.replaceInstruction(locInst.pos, newInst)
    }
}

/**
 *  Return true if the store commute over all instructions between [loadLocInst] and [storeLocInst]
 *
 *  The lifted memcpy will be inserted **before the first load**.
 *
 *  If the loaded memory address is overwritten between the load and the store we are okay
 *  (see test19 in PromoteStoresToMemcpyTest.kt).
 *  However, if the stored memory address is overwritten between the load and store then we are not okay and
 *  the sequence of loads and stores shouldn't be lifted to a memcpy (see test20 in PromoteStoresToMemcpyTest.kt).
 **/
private fun <D, TNum, TOffset>  isSafeToCommuteStore(
    bb: SbfBasicBlock,
    @Suppress("UNUSED_PARAMETER") load: MemAccess,
    loadLocInst: LocatedSbfInstruction,
    store: MemAccess,
    storeLocInst: LocatedSbfInstruction,
    regTypes: AnalysisRegisterTypes<D, TNum, TOffset>): Boolean
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    check(loadLocInst.label == bb.getLabel()) {
        "can only promote pairs of load-store within the same block $loadLocInst"
    }
    check(storeLocInst.label == bb.getLabel()) {
        "can only promote pairs of load-store within the same block $storeLocInst"
    }

    val name = "isSafeToCommuteStore"
    val loadInst = loadLocInst.inst
    check(loadInst is SbfInstruction.Mem) { "$name: $loadLocInst should be a load"}
    val storeInst = storeLocInst.inst
    check(storeInst is SbfInstruction.Mem) {"$name: $storeLocInst should be a store"}


    fun getMemAccessRegion(reg: MemAccessRegion): MemAccessRegion {
        return if (SolanaConfig.OptimisticMemcpyPromotion.get()) {
            // If optimistic flag then we will assume that "any" can be any memory region except the stack
            when(reg) {
                MemAccessRegion.STACK, MemAccessRegion.NON_STACK -> reg
                MemAccessRegion.ANY ->  MemAccessRegion.NON_STACK
            }
        } else {
            reg
        }
    }

    val storeBaseReg = storeInst.access.baseReg

    logger.debug{ "$name: $storeInst up to $loadInst?" }
    // aliases keeps track of other register that might be assigned to the loaded register
    val aliases = mutableSetOf<Value.Reg>()
    aliases.addAll(loadInst.writeRegister)
    bb.getInstructions().subList(loadLocInst.pos+1 ,storeLocInst.pos).forEach {
        // Ensure that loaded register cannot be modified between the load and store
        if (it.writeRegister.intersect(loadInst.writeRegister).isNotEmpty()) {
            logger.debug{ "\t$name: $it might modify the loaded register" }
            return false
        }

        // Ensure that the base register of the store is not overwritten between the load and store.
        // This restriction might be not necessary, specially if load and stores are on the stack, but we prefer
        // to be conservative here.
        if (it.writeRegister.contains(storeBaseReg)) {
            logger.debug{ "\t$name: $it might modify the base register of the store" }
            return false
        }

        // update of aliases
        if (it is SbfInstruction.Bin && it.op == BinOp.MOV) {
            if (it.readRegisters.intersect(aliases).isNotEmpty()) {
                aliases.add(it.dst)
            }
        }

        // We don't allow assume/assert instructions because they can restrict the values of the register
        // See test18 in PromoteStoresToMemcpyTest.kt
        if (it.isAssertOrSatisfy() || it is SbfInstruction.Assume) {
            if (it.readRegisters.intersect(aliases).isNotEmpty()) {
                logger.debug{ "\t$name: $it might affect the loaded register" }
                return false
            }
        }
    }

    val storeRange = FiniteInterval.mkInterval(store.offset, store.width.toLong())
    when (getMemAccessRegion(store.region)) {
        MemAccessRegion.STACK -> {
            // The store is on the stack, so we can tell for sure whether the store commutes over all instruction
            // between the load and the store, or not.
            for (locInst in bb.getLocatedInstructions().subList(loadLocInst.pos+1, storeLocInst.pos)) {
                val inst = locInst.inst
                if (inst is SbfInstruction.Mem) {
                    val normMemInst = normalizeLoadOrStore(locInst, regTypes)
                    val canCommuteStore = when (getMemAccessRegion(normMemInst.region)) {
                        MemAccessRegion.STACK -> {
                            val noOverlap = !normMemInst.overlap(storeRange)
                            if (noOverlap) {
                                logger.debug { "\t$name OK: $inst is stack and $storeInst is stack but no overlap." }
                            } else {
                                logger.debug { "\t$name FAIL: $inst is stack and $storeInst is stack and they overlap." }
                            }
                            noOverlap
                        }
                        MemAccessRegion.NON_STACK -> {
                            logger.debug {"\t$name OK: $inst is non-stack and $storeInst is stack"}
                            true
                        }
                        MemAccessRegion.ANY -> {
                            logger.debug {"\t$name FAIL: $inst is any memory and $storeInst is stack"}
                            false
                        }
                    }
                    if (!canCommuteStore) {
                        return false
                    }
                } else if (inst is SbfInstruction.Call && SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY) {

                    val memAccesses = normalizeMemcpy(locInst, regTypes)
                    if (memAccesses == null) {
                        logger.debug {"\t$name FAIL: cannot statically determine length in $inst"}
                        return false
                    }
                    val (normSrc, normDest) = memAccesses
                    // Check store commute over destination
                    when (normDest.region) {
                        MemAccessRegion.STACK -> {
                            if (!normDest.overlap(storeRange)) {
                                logger.debug { "\t$name OK: $inst is stack and $storeInst is stack but no overlap." }
                            } else {
                                logger.debug { "\t$name FAIL: $inst is stack and $storeInst is stack and they overlap." }
                                return false
                            }
                        }
                        else -> {
                            logger.debug { "\t$name FAIL: stores do not commute over memcpy for now" }
                            return false
                        }
                    }

                    // Check store commute over source
                    when (normSrc.region) {
                        MemAccessRegion.STACK -> {
                            if (!normSrc.overlap(storeRange)) {
                                logger.debug { "\t$name OK: $inst is stack and $storeInst is stack but no overlap." }
                            } else {
                                logger.debug { "\t$name FAIL: $inst is stack and $storeInst is stack and they overlap." }
                                return false
                            }
                        }
                        else -> {
                            logger.debug { "\t$name FAIL: stores do not commute over memcpy for now" }
                            return false
                        }
                    }
                }
            }
        }
        MemAccessRegion.NON_STACK -> {
            val canCommuteStore = bb.getLocatedInstructions().subList(loadLocInst.pos+1, storeLocInst.pos).all { locInst ->
                val inst = locInst.inst
                if (inst is SbfInstruction.Mem) {
                    val normMemInst = normalizeLoadOrStore(locInst, regTypes)
                    when (getMemAccessRegion(normMemInst.region)) {
                        MemAccessRegion.STACK -> {
                            logger.debug {"\t$name OK: $inst is stack and $storeInst is non-stack"}
                            true
                        }
                        MemAccessRegion.NON_STACK -> {
                            if (normMemInst.reg == storeBaseReg.r) { // we know that storeBaseReg doesn't change
                                val noOverlap = !normMemInst.overlap(storeRange)
                                if (noOverlap) {
                                    logger.debug { "\t$name OK: $inst is non-stack and $storeInst non-stack but same register and no overlap." }
                                } else {
                                    logger.debug { "\t$name FAIL: $inst is non-stack and $storeInst non-stack but same register and overlap." }
                                }
                                noOverlap
                            } else {
                                logger.debug {"\t$name FAIL: $inst is non-stack and $storeInst is non-stack"}
                                false
                            }
                        }
                        MemAccessRegion.ANY -> {
                            logger.debug {"\t$name: $inst is any memory and $storeInst is non-stack"}
                            false
                        }
                    }
                } else {
                    // We could do better, but we bail out if the instruction is a memcpy
                    val isNotMemcpy = !(inst is SbfInstruction.Call && SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY)
                    if (!isNotMemcpy) {
                        logger.debug { "\t$name FAIL: stores do not commute over memcpy for now" }
                    }
                    isNotMemcpy
                }
            }
            if (!canCommuteStore) {
                return false
            }
        }
        MemAccessRegion.ANY -> {
            logger.debug { "\t$name: $storeInst on unknown memory " }
            return bb.getInstructions().subList(loadLocInst.pos+1, storeLocInst.pos).all {
                it !is SbfInstruction.Mem &&
                    !(it is SbfInstruction.Call && SolanaFunction.from(it.name) == SolanaFunction.SOL_MEMCPY)
            }
        }
    }
    logger.debug {"$name OK"}
    return true
}
