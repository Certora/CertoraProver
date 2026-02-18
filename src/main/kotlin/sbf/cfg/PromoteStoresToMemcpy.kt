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
import sbf.callgraph.SolanaFunction
import sbf.disassembler.*
import sbf.domains.*
import datastructures.stdcollections.*
import log.*
import org.jetbrains.annotations.TestOnly
import sbf.SolanaConfig
import sbf.analysis.*
import kotlin.math.absoluteValue

private val logger = Logger(LoggerTypes.SBF_MEMCPY_PROMOTION)
private fun info(msg: () -> Any) { logger.info(msg)}
private fun dbg(msg: () -> Any) { logger.debug(msg)}

/**
 *  Promote load-store pairs into `memcpy` instructions.
 */
fun promoteStoresToMemcpy(
    cfg: MutableSbfCFG,
    globals: GlobalVariables,
    memSummaries: MemorySummaries
) {
    val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
    val scalarAnalysis = GenericScalarAnalysis(
        cfg,
        globals,
        memSummaries,
        sbfTypesFac,
        // We specifically want to use this scalar domain
        ScalarRegisterStackEqualityDomainFactory()
    )
    // [findWideningAndNarrowingStores] depends on endianness. Since we never expect big-endian,
    // we prefer to fail so that we are aware.
    check(globals.elf.isLittleEndian())

    promoteIntraBlockLoadStorePairsToMemcpy(cfg, scalarAnalysis, globals.elf.useDynamicFrames())
    promoteInterBlockLoadStoreToMemcpy(cfg, scalarAnalysis)
}

/**
 * Replace multiple load-store pairs and replace them with `memcpy` instructions.
 *
 * The transformation is **intra-block**.
 */
@TestOnly
fun <D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> promoteIntraBlockLoadStorePairsToMemcpy(
    cfg: MutableSbfCFG,
    scalarAnalysis: IAnalysis<D>,
    // For tests, we use static frames by default
    useDynFrames: Boolean = false,
    // For tests, it's sometimes convenient to disable it
    aggressivePromotion: Boolean = true)
    where D: AbstractDomain<D>,
          D: ScalarValueProvider<TNum, TOffset> {

    reorderLoads(cfg, scalarAnalysis)

    /**
     * After each call to [applyRewrites], the basic block changes.
     * Thus, we must be careful not to use invalidated/obsolete type information.
     *
     * Since the transformation is intra-block and it preserves semantics
     * (replace load-store pairs with `memcpy`/`memcpy_trunc`/`memcpy_zext`), we can still use
     * [scalarAnalysis] since it only contains the invariants that hold at the entry of the block.
     *
     * However, [AnalysisRegisterTypes] will collect information at the instruction level.
     * Thus, `types` should NOT be used for answering queries about basic block `b`
     * once `b` has changed. But information about other blocks is still valid.
     */
    val types = AnalysisRegisterTypes(scalarAnalysis)
    for (b in cfg.getMutableBlocks().values) {
        val rewrites = mutableListOf<MemTransferRewrite>()

        // (A) load and stores access **different** number of bytes
        rewrites.addAll(findWideningAndNarrowingStores(b, types, useDynFrames))
        // (B) load and stores access **same** number of bytes
        rewrites.addAll(findMemcpyPatterns(b, types, useDynFrames))

        // Note that we can collect first all the rewrites of type (A) and (B) and then
        // change the basic block because (A) and (B) do not share instructions
        // After here we shouldn't use `types` to answer queries about this block b.
        // But next iterations of this loop can still use `types`
        applyRewrites(cfg, rewrites)

        // (C) if we haven't changed the basic block (types is still valid) yet, we try next transformation.
        //
        // Note that since we run several times this function, eventually we won't be able to
        // apply (A) and (B) so this code will be executable.
        if (rewrites.isEmpty() && aggressivePromotion) {
            /*
            * Since we promote `memcpy` instructions in a greedy manner we might lose some simple promotions.
            * For instance, with this code:
            * ```
            *    r1 := *(u64 *) (r10 + -528):sp(40432)
            *    *(u64 *) (r10 + -376):sp(40584) := r1
            *    r1 := *(u64 *) (r10 + -520):sp(40440)
            *    *(u64 *) (r10 + -384):sp(40576) := r1
            * ```
            * we fail to promote the two loads and stores to a `memcpy` of 16 bytes because of the "ordering". That is,
            * the content of 40432 (low) is written to 40584 (high) and the content of 40440 (high) to 40576 (low).
            *
            * However, we can promote the two pairs of load-store to two separate `memcpy` of 8 bytes each one.
            * To do that, we do another round where we search for single pairs of load-store.
            */
            applyRewrites(
                cfg,
                findMemcpyPatterns(b, types, useDynFrames, maxNumOfPairs = 1)
            )
        }

        // Transforms some load instructions (by narrowing them) that were not involved
        // in any of the memcpy promotions from before
        narrowLoadsFromMemcpy(b, cfg) // remove some loads
    }
}

/**
 *  Replace a single load-store pair with a `memcpy` instruction.
 *
 *  The transformation is inter-block.
 */
private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> promoteInterBlockLoadStoreToMemcpy(
    cfg: MutableSbfCFG,
    scalarAnalysis: IAnalysis<ScalarRegisterStackEqualityDomain<TNum, TOffset>>
) {
    val rewrites = findInterBlockLoadAndStorePairs(cfg, scalarAnalysis)
    applyRewrites(cfg, rewrites)
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
    useDynFrames: Boolean,
    maxNumOfPairs: Int = Int.MAX_VALUE,
    minSizeToBePromoted: ULong = 1UL
): List<MemTransferRewrite>
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    // list of rewrites to be returned
    val rewrites = mutableListOf<MemTransferRewrite>()

    // used to find the definition of a value to be stored
    val defLoads = mutableMapOf<SbfRegister, Pair<LocatedSbfInstruction, ULong>>()
    // Note that we allow inserting multiple memcpy instructions in the same basic block
    // We try eagerly to group together the maximal number of stores
    var memcpyPattern = MemcpyPattern()
    // a load can be paired with a stored if they are at the same stack depth
    var curStackDepth = 0UL
    for (locInst in bb.getLocatedInstructions()) {
        val inst = locInst.inst
        when {
            inst.isSaveScratchRegisters() ->  curStackDepth++
            inst.isRestoreScratchRegisters() -> curStackDepth--
            inst is SbfInstruction.Mem -> {
                if (inst.isLoad) {
                    defLoads[(inst.value as Value.Reg).r] = locInst to curStackDepth
                } else {
                    val value = inst.value
                    if (value !is Value.Reg) {
                        continue
                    }
                    val (loadInst, stackDepth) = defLoads[value.r] ?: continue

                    // We only pair load and stores at the same stack depth
                    if (curStackDepth != stackDepth) {
                        continue
                    }

                    val canProcessPair = processLoadStorePair(bb, locInst, loadInst, types, useDynFrames, memcpyPattern)
                    if (memcpyPattern.getLoads().size >= maxNumOfPairs || !canProcessPair) {
                        // If we cannot insert the store-of-load pair, then we check if we can promote
                        // the pairs we have so far.
                        memcpyPattern.canBePromoted(minSizeToBePromoted)?.let { promoted ->
                            emitMemcpy(
                                promoted.srcReg,
                                promoted.srcStart,
                                promoted.dstReg,
                                promoted.dstStart,
                                promoted.size,
                                promoted.metadata
                            )?.let { newInsts ->
                                rewrites.add(
                                    MemTransferRewrite(
                                        memcpyPattern.getLoads(),
                                        memcpyPattern.getStores(),
                                        newInsts
                                    )
                                )
                            }
                        }

                        // We start a fresh sequence of store-of-load pairs
                        memcpyPattern = MemcpyPattern()
                        processLoadStorePair(bb, locInst, loadInst, types, useDynFrames, memcpyPattern)
                    }
                }
            }
            else -> {
                // If the instruction is not a memory instruction, then we check if we can promote
                // the store-of-load pairs we have
                memcpyPattern.canBePromoted(minSizeToBePromoted)?.let { promoted ->
                    emitMemcpy(
                        promoted.srcReg,
                        promoted.srcStart,
                        promoted.dstReg,
                        promoted.dstStart,
                        promoted.size,
                        promoted.metadata
                    )?.let { newInsts ->
                        rewrites.add(
                            MemTransferRewrite(
                                memcpyPattern.getLoads(),
                                memcpyPattern.getStores(),
                                newInsts
                            )
                        )
                    }
                    memcpyPattern = MemcpyPattern()
                }

                // Kill any active load if its defined register is overwritten
                for (reg in inst.writeRegister) {
                    defLoads.remove(reg.r)
                }
            }
        }
    }
    return rewrites
}

/**
 * Return true if [loadLocInst] is the loaded offset stored in [storeLocInst] and some conditions hold
 * (see [isSafeToCommuteStore] and method `add` from [MemcpyPattern])
 **/
private fun <D, TNum, TOffset> processLoadStorePair(
    bb: SbfBasicBlock,
    storeLocInst: LocatedSbfInstruction,
    loadLocInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    useDynFrames: Boolean,
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
    val loadedMemAccess = normalizeLoadOrStore(loadLocInst, types)
    val storedMemAccess = normalizeLoadOrStore(storeLocInst, types)

    // This restriction is needed when we emit the code because if both base registers in [load] and [store]
    // are scratch registers then we cannot perform the transformation because we run out of registers
    // where we can save values.
    return when {
        loadedMemAccess.region != MemAccessRegion.STACK &&
            storedMemAccess.region != MemAccessRegion.STACK -> false
        !isSafeToCommuteStore(
            bb,
            loadedMemAccess,
            loadLocInst,
            storedMemAccess,
            storeLocInst,
            types,
            useDynFrames
        ) ->  false
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
                return if (SolanaConfig.optimisticMemcpyPromotion()) {
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
                    return srcSingleton to dstSingleton
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
 * We are interested in these two related patterns:
 * ```
 * ldxh  r1, [r10-0x118]    ; load 16-bit value
 * stxdw [r10-0x5E0], r1    ; spill it as a full 64-bit slot on the stack
 * ```
 * and
 * ```
 * ldxdw r2, [r10-0x5E0]    ; reload 64-bit slot from spill
 * stxh  [r10-0x150], r2    ; store lower 16 bits to another stack slot
 * ```
 *
 * This is a "type-widen → spill → type-narrow" flow caused by register pressure.
 * The first store must be 8 bytes write because spilling uses stack slots of 8 bytes.
 */
private fun <D, TNum, TOffset> findWideningAndNarrowingStores(
    bb: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    useDynFrames: Boolean
): List<MemTransferRewrite>
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    val rewrites = mutableListOf<MemTransferRewrite>()

    // used to find the definition of a value to be stored
    val defLoads = mutableMapOf<SbfRegister, LocatedSbfInstruction>()

    for (locInst in bb.getLocatedInstructions()) {
        when(val inst = locInst.inst) {
            is SbfInstruction.Mem -> {
                when (inst.isLoad) {
                    true -> {
                        defLoads[(inst.value as Value.Reg).r] = locInst
                    }
                    false -> {
                        val value = inst.value
                        if (value !is Value.Reg) {
                            continue
                        }
                        val loadInst = defLoads[value.r] ?: continue

                        val loadedMemAccess = normalizeLoadOrStore(loadInst, types)
                        val storedMemAccess = normalizeLoadOrStore(locInst, types)
                        if (loadedMemAccess.region != MemAccessRegion.STACK && storedMemAccess.region != MemAccessRegion.STACK) {
                            continue
                        }
                        if (!isSafeToCommuteStore(bb, loadedMemAccess, loadInst, storedMemAccess, locInst, types, useDynFrames)) {
                            continue
                        }
                        when {
                            (storedMemAccess.width.toInt() == 8 && loadedMemAccess.width.toInt() < 8) -> {
                                // widening store to 64-bits
                                //
                                // ldxh  r1, [r10-0x118]    ; load 16-bit value into 64-bit register
                                // stxdw [r10-0x5E0], r1    ; spill it as a full 64-bit slot on the stack
                                //
                                // Example assuming little-endian:
                                //
                                // r10-0x117: 0xAB  (high)
                                // r10-0x118: 0xCD  (low)
                                //
                                // after ldxh  r1, [r10-0x118]
                                //
                                // r1 = 0x00_00_00_00_00_00_AB_CD
                                //
                                // after stxdw [r10-0x5E0], r1
                                //
                                //  r10-0x5D9:   00  (highest)
                                //  r10-0x5DA:   00
                                //  r10-0x5DB:   00
                                //  r10-0x5DC:   00
                                //  r10-0x5DD:   00
                                //  r10-0x5DE:   00
                                //  r10-0x5DF:   AB
                                //  r10-0x5E0:   CD  (lowest)
                                emitMemcpyZExt(loadedMemAccess.reg,
                                    loadedMemAccess.offset,
                                    storedMemAccess.reg,
                                    storedMemAccess.offset,
                                    i = loadedMemAccess.width.toULong(),
                                    loadInst.inst.metaData
                                )?.let { newInsts ->
                                    rewrites.add(MemTransferRewrite(listOf(loadInst), listOf(locInst), newInsts))
                                }
                            }
                            (loadedMemAccess.width.toInt() == 8 && storedMemAccess.width.toInt() < 8) -> {
                                // narrowing store from 64-bit load
                                //
                                // ldxdw r2, [r10-0x5E0]    ; reload 64-bit slot from spill
                                // stxh  [r10-0x150], r2    ; store lower 16 bits to another stack slot
                                //
                                // Note that there is an implicit truncation.
                                emitMemcpyTrunc(loadedMemAccess.reg,
                                    loadedMemAccess.offset,
                                    storedMemAccess.reg,
                                    storedMemAccess.offset,
                                    storedMemAccess.width.toULong(),
                                    loadInst.inst.metaData
                                )?.let { newInsts ->
                                    rewrites.add(MemTransferRewrite(listOf(loadInst), listOf(locInst), newInsts))
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            else -> {
                // Kill "active" load if its defined register is overwritten
                for (reg in inst.writeRegister) {
                    defLoads.remove(reg.r)
                }
            }
        }
    }
    return rewrites
}

private fun SbfInstruction.isMemcpyPromoted(): Boolean =
        this.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null ||
        this.metaData.getVal(SbfMeta.MEMCPY_TRUNC_PROMOTION) != null ||
        this.metaData.getVal(SbfMeta.MEMCPY_ZEXT_PROMOTION) != null

private fun emitMemcpyVariant(
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
 * Identify a single load-store pair across different blocks that can be replaced with `memcpy` and
 * return the SBF code (rewrite) that will be used to replace the load-store pair.
 *
 * Unlike [findMemcpyPatterns] which can discover a new `memcpy` instruction from multiple load-store pairs
 * within a single block, this finds a single load-store pair where:
 *
 * - The load and store can be in different basic blocks
 * - The loaded value flows directly to the store
 * - No dependencies exist that precludes to move the store up next to the load.
 */
fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> findInterBlockLoadAndStorePairs(
    cfg: SbfCFG,
    scalarAnalysis: IAnalysis<ScalarRegisterStackEqualityDomain<TNum, TOffset>>
):  List<MemTransferRewrite>  {

    data class MemAccessTransfer(
        val srcLocInst: LocatedSbfInstruction, val srcAccess: MemAccess,
        val dstLocInst: LocatedSbfInstruction, val dstAccess: MemAccess
    )

    // Type analysis information
    val types = AnalysisRegisterTypes(scalarAnalysis)
    // post-dominance queries
    val postDom = PostDominatorAnalysis(cfg)
    // Global state needed by mod-ref analysis
    val globalState = GlobalState(scalarAnalysis.getGlobalVariableMap(), scalarAnalysis.getMemorySummaries())
    // List of load-store pairs with extra information
    val loadStorePairs = mutableListOf<MemAccessTransfer>()
    // Options for the mod/ref analysis
    val modRefOpts = ModRefAnalysisOptions(
        maxRegionSize = 8UL,
        assumeTopPointersAreNonStack = SolanaConfig.optimisticMemcpyPromotion()
    )

    /**
     * We need to ask [ScalarRegisterStackEqualityDomain] information at every instruction
     * For that we create an [InstructionListener] and pass it to [analyzeBlock]
     **/
    val processor = object : InstructionListener<ScalarRegisterStackEqualityDomain<TNum, TOffset>> {

        override fun instructionEventBefore(
            locInst: LocatedSbfInstruction,
            pre: ScalarRegisterStackEqualityDomain<TNum, TOffset>
        ) {

            // If the instruction is not a store then skip
            val inst = locInst.inst
            if (inst !is SbfInstruction.Mem || inst.isLoad) {
                return
            }

            val reg = inst.value as? Value.Reg
                ?: return

            // Find the load instruction whose loaded value is the store's value
            val stackLoad = pre.getRegisterStackEqualityDomain().getRegister(reg)
                ?: return


            // Skip if the load instruction is already part of another promotion
            val loadLocInst = stackLoad.locInst
            if (loadLocInst.inst.isMemcpyPromoted()) {
                return
            }

            info { "findInterBlockLoadAndStorePairs -- STORE:$inst  LOAD:${loadLocInst.inst}" }

            val loadBlockId = loadLocInst.label
            val storeBlockId = locInst.label

            // Skip if load and store are in the same block
            if (loadBlockId == storeBlockId) {
                info {"\tSkip because load and store are in the same basic block"}
                return
            }

            // Skip if the store does not post-dominates the load instruction
            if (!postDom.postDominates(storeBlockId, loadBlockId)) {
                info {"\tSkip because store does not post-dominates the load"}
                return
            }

            // Skip if load and store are not in the same function
            // We check this but checking the value of r10 (stack top)
            val r10 = SbfRegister.R10_STACK_POINTER
            val stackTopAtLoad = types.typeAtInstruction(loadLocInst, r10)
            val stackTopAtStore= types.typeAtInstruction(locInst, r10)
            if (stackTopAtLoad != stackTopAtStore) {
                info { "\tSkip because load and stores are not in the same function"}
                return
            }

            // Skip if both load and store do not access to the stack
            val loadMemAccess = normalizeLoadOrStore(loadLocInst, types)
            val storeMemAccess = normalizeLoadOrStore(locInst, types)
            if (loadMemAccess.region != MemAccessRegion.STACK ||
                storeMemAccess.region != MemAccessRegion.STACK
            ) {
                info {
                    "\tSkip because either load or store is not on the stack. " +
                    "load=$loadMemAccess store=$storeMemAccess"
                }
                return
            }

            // Skip if load and store overlap
            if (loadMemAccess.overlap(storeMemAccess)) {
                info { "\tSkip because load and store overlap" }
                return
            }

            // Finally, check for memory dependencies between the load and store:
            //   - WAR (Write-After-Read): write to load's location
            //   - RAW (Read-After-Write): read from store's location
            //   - WAW (Write-After-Write): write to store's location
            //
            // If WAR, RAW, WAW cannot happen then it is sound to move up the store instruction
            // next to the load instruction.

            // A load instruction can never be a terminator so adding one is okay
            val start = loadLocInst.copy(pos = loadLocInst.pos + 1)
            // A store could be the first instruction of the block
            if (locInst.pos == 0) {
                return
            }
            val end = locInst.copy(pos = locInst.pos - 1)

            val modRefAnalysis = ModRefAnalysis(cfg, start, end, types, globalState, modRefOpts)
            val war = modRefAnalysis.mayAccess(loadLocInst, AccessType.WRITE)
            val raw = modRefAnalysis.mayAccess(locInst, AccessType.READ)
            val waw = modRefAnalysis.mayAccess(locInst, AccessType.WRITE)

            info { "\tWAR: $war" }
            info { "\tRAW: $raw" }
            info { "\tWAW: $waw" }

            if (!war && !raw && !waw) {
                loadStorePairs.add(MemAccessTransfer(loadLocInst, loadMemAccess, locInst, storeMemAccess))
            }
        }

        override fun instructionEventAfter(
            locInst: LocatedSbfInstruction,
            post: ScalarRegisterStackEqualityDomain<TNum, TOffset>
        ) {}

        override fun instructionEvent(
            locInst: LocatedSbfInstruction,
            pre: ScalarRegisterStackEqualityDomain<TNum, TOffset>,
            post: ScalarRegisterStackEqualityDomain<TNum, TOffset>
        ) {}
    }


    for (b in cfg.getBlocks().values) {
        val state = scalarAnalysis.getPre(b.getLabel()) ?: continue
        if (state.isBottom()) {
            continue
        }
        // Replay the abstract states at each instruction while applying processor
        analyzeBlock(
            domainName = "ScalarDomain x RegisterStackEqualityDomain",
            b,
            state,
            ScalarRegisterStackEqualityDomain<TNum, TOffset>::analyze,
            processor
        )
    }

    return loadStorePairs.mapNotNull { (srcLocInst, srcMemAccess, dstLocInst, dstMemAccess) ->
        val srcWidth = srcMemAccess.width.toULong()
        val dstWidth = dstMemAccess.width.toULong()
        val rewrite = emitMemcpyVariant(
            srcWidth,
            dstWidth,
            srcMemAccess,
            dstMemAccess,
            srcLocInst.inst.metaData
        )?.let { newInsts ->
            MemTransferRewrite(
                listOf(srcLocInst),
                listOf(dstLocInst),
                newInsts
            )
        }
        rewrite
    }
}

/**
 * Represents a recognized memory transfer pattern (e.g. `memcpy`)
 *
 * All loads must be in the same basic block.
 * All stores must be in the same basic block.
 * (Loads and stores may be in different blocks from each other.)
 *
 * The `loads` and `stores` together describe a semantically equivalent
 * operation that can be replaced by `replacement`.
 */
data class MemTransferRewrite(
    val loads: List<LocatedSbfInstruction>,
    val stores: List<LocatedSbfInstruction>,
    val replacement: List<SbfInstruction>
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
private fun applyRewrites(cfg: MutableSbfCFG, rewrites: List<MemTransferRewrite>) {
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
            numAdded += rewrite.replacement.size
            block.addAll(insertPoint, rewrite.replacement)
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
private fun sortRewrites(rewrites: List<MemTransferRewrite>): List<MemTransferRewrite> {
    // Group rewrites by the block containing their loads
    val rewritesByBlock = rewrites.groupBy { it.loads.first().label }

    // Sort rewrites within each block by position of first load
    val sortedRewrites = mutableListOf<MemTransferRewrite>()

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

private fun SbfInstruction.isMemcpy() =
    this is SbfInstruction.Call && SolanaFunction.from(this.name) == SolanaFunction.SOL_MEMCPY

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
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    useDynFrames: Boolean
): Boolean
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
        return if (SolanaConfig.optimisticMemcpyPromotion()) {
            // If optimistic flag then we will assume that "any" can be any memory region except the stack
            when(reg) {
                MemAccessRegion.STACK, MemAccessRegion.NON_STACK -> reg
                MemAccessRegion.ANY ->  MemAccessRegion.NON_STACK
            }
        } else {
            reg
        }
    }

    val storeBaseReg = storeInst.access.base

    dbg { "$name: $storeInst up to $loadInst?" }
    // aliases keeps track of other register that might be assigned to the loaded register
    val aliases = mutableSetOf<Value.Reg>()
    aliases.addAll(loadInst.writeRegister)
    bb.getInstructions().subList(loadLocInst.pos+1 ,storeLocInst.pos).forEach {
        // Ensure that loaded register cannot be modified between the load and store
        if (!it.isRestoreScratchRegisters() && it.writeRegister.intersect(loadInst.writeRegister).isNotEmpty()) {
            dbg { "\t$name: $it might modify the loaded register" }
            return false
        }

        // Ensure that the base register of the store is not overwritten between the load and store.
        // This restriction might be not necessary, specially if load and stores are on the stack, but we prefer
        // to be conservative here.
        if (!it.isRestoreScratchRegisters() &&
            !it.isStackPush(useDynFrames) &&
            !it.isStackPop(useDynFrames) &&
            it.writeRegister.contains(storeBaseReg)) {
            dbg { "\t$name: $it might modify the base register of the store" }
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
                dbg { "\t$name: $it might affect the loaded register" }
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
                when {
                    inst is SbfInstruction.Mem -> {
                        val normMemInst = normalizeLoadOrStore(locInst, types)
                        val canCommuteStore = when (getMemAccessRegion(normMemInst.region)) {
                            MemAccessRegion.STACK -> {
                                val noOverlap = !normMemInst.overlap(storeRange)
                                dbg {
                                    if (noOverlap) {
                                        "\t$name OK: $inst is stack and $storeInst is stack but no overlap."
                                    } else {
                                        "\t$name FAIL: $inst is stack and $storeInst is stack and they overlap."
                                    }
                                }
                                noOverlap
                            }
                            MemAccessRegion.NON_STACK -> {
                                dbg { "\t$name OK: $inst is non-stack and $storeInst is stack" }
                                true
                            }
                            MemAccessRegion.ANY -> {
                                dbg { "\t$name FAIL: $inst is any memory and $storeInst is stack" }
                                false
                            }
                        }
                        if (!canCommuteStore) {
                            return false
                        }
                    }
                    inst.isMemcpy() -> {
                        val memAccesses = normalizeMemcpy(locInst, types)
                        if (memAccesses == null) {
                            dbg { "\t$name FAIL: cannot statically determine length in $inst" }
                            return false
                        }
                        val (normSrc, normDest) = memAccesses
                        // Check store commute over destination
                        when (normDest.region) {
                            MemAccessRegion.STACK -> {
                                if (!normDest.overlap(storeRange)) {
                                    dbg { "\t$name OK: $inst is stack and $storeInst is stack but no overlap." }
                                } else {
                                    dbg { "\t$name FAIL: $inst is stack and $storeInst is stack and they overlap." }
                                    return false
                                }
                            }
                            else -> {
                                dbg { "\t$name FAIL: stores do not commute over memcpy for now" }
                                return false
                            }
                        }

                        // Check store commute over source
                        when (normSrc.region) {
                            MemAccessRegion.STACK -> {
                                if (!normSrc.overlap(storeRange)) {
                                    dbg { "\t$name OK: $inst is stack and $storeInst is stack but no overlap." }
                                } else {
                                    dbg { "\t$name FAIL: $inst is stack and $storeInst is stack and they overlap." }
                                    return false
                                }
                            }
                            else -> {
                                dbg { "\t$name FAIL: stores do not commute over memcpy for now" }
                                return false
                            }
                        }
                    }
                    else -> {
                        // Intentionally skip next instruction
                    }
                }
            }
        }
        MemAccessRegion.NON_STACK -> {
            val canCommuteStore = bb.getLocatedInstructions().subList(loadLocInst.pos+1, storeLocInst.pos).all { locInst ->
                val inst = locInst.inst
                when {
                    inst is SbfInstruction.Mem -> {
                        val normMemInst = normalizeLoadOrStore(locInst, types)
                        when (getMemAccessRegion(normMemInst.region)) {
                            MemAccessRegion.STACK -> {
                                dbg { "\t$name OK: $inst is stack and $storeInst is non-stack" }
                                true
                            }
                            MemAccessRegion.NON_STACK -> {
                                if (normMemInst.reg == storeBaseReg.r) { // we know that storeBaseReg doesn't change
                                    val noOverlap = !normMemInst.overlap(storeRange)
                                    dbg {
                                        if (noOverlap) {
                                            "\t$name OK: $inst is non-stack and $storeInst non-stack but same register and no overlap."
                                        } else {
                                            "\t$name FAIL: $inst is non-stack and $storeInst non-stack but same register and overlap."
                                        }
                                    }
                                    noOverlap
                                } else {
                                    dbg { "\t$name FAIL: $inst is non-stack and $storeInst is non-stack" }
                                    false
                                }
                            }
                            MemAccessRegion.ANY -> {
                                dbg { "\t$name: $inst is any memory and $storeInst is non-stack" }
                                false
                            }
                        }
                    }
                    inst.isMemcpy() -> {
                        // We could do better, but we bail out if the instruction is a memcpy
                        dbg { "\t$name FAIL: stores do not commute over memcpy for now" }
                        false
                    }
                    else -> {
                        true
                    }
                }
            }
            if (!canCommuteStore) {
                return false
            }
        }
        MemAccessRegion.ANY -> {
            dbg { "\t$name: $storeInst on unknown memory " }
            return bb.getInstructions().subList(loadLocInst.pos+1, storeLocInst.pos).all {
                it !is SbfInstruction.Mem && !it.isMemcpy()
            }
        }
    }
    dbg {"$name OK"}
    return true
}

private fun narrowLoadsFromMemcpy(b: MutableSbfBasicBlock, cfg: MutableSbfCFG) {
    narrowMaskedLoads(b, cfg) { loadInst ->
        loadInst.isLoad &&
            loadInst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null
    }
}
