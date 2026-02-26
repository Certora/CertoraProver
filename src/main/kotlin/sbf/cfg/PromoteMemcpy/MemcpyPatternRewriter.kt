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
import log.Logger
import log.LoggerTypes
import sbf.SolanaConfig
import sbf.analysis.*
import sbf.disassembler.SbfRegister
import sbf.domains.*

private val logger = Logger(LoggerTypes.SBF_MEMCPY_PROMOTION)
private fun info(msg: () -> Any) { logger.info(msg)}

/**
 * Find at most [maxNumOfPairs] load/store pairs within [bb] that can be promoted to `memcpy`, and
 * returns the corresponding rewrites.
 *
 * Algorithm:
 *
 * We scan all instructions within [bb]
 * 1. If the instruction is a load then we remember it in `defLoads` map.
 * 2. If the instruction is a store then we try to pair it with a load from `defLoads`.
 *    This is done by [processLoadStorePair]. This function can return false for several reasons.
 *    For instance, if the store is far from the load then we need to prove that there is no other stores that might modify
 *    the loaded memory location.
 *    Since we try to find the maximal number of load/store pairs (i.e., the longest memcpy), we require that the accessed memory has no gaps.
 *    If there are some gaps then [processLoadStorePair] will also return false.
 * 3. If at any time, [processLoadStorePair] returns false we check how many pairs of load-stores we have. If any then
 *    we replace them with a `memcpy` instruction. When replacing more than one load/store pair some extra conditions must also hold.
 *    This is checked by `canBePromoted`.
 *
 *  @param [bb] basic block whether promotion will take place
 *  @param [types] info from the scalar analysis
 *  @param [maxNumOfPairs] maximum number of pairs of loads and stores
 *  @param [minSizeToBePromoted] minimum number of bytes to be transferred for the promoted memcpy
 *  @return List of rewrites
 */
fun <D, TNum, TOffset> findMemcpyRewritesIntraBlock(
    bb: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    useDynFrames: Boolean,
    maxNumOfPairs: Int = Int.MAX_VALUE,
    minSizeToBePromoted: ULong = 1UL
): List<MemcpyRewrite>
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    // list of rewrites to be returned
    val rewrites = mutableListOf<MemcpyRewrite>()

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
                                    MemcpyRewrite(
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
                            MemcpyRewrite(
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
fun <D, TNum, TOffset> findWideningAndNarrowingRewritesIntraBlock(
    bb: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    useDynFrames: Boolean
): List<MemcpyRewrite>
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    val rewrites = mutableListOf<MemcpyRewrite>()

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
                                    rewrites.add(MemcpyRewrite(listOf(loadInst), listOf(locInst), newInsts))
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
                                    rewrites.add(MemcpyRewrite(listOf(loadInst), listOf(locInst), newInsts))
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

/**
 * Identify a single load-store pair across different blocks that can be replaced with `memcpy` and
 * return the SBF code (rewrite) that will be used to replace the load-store pair.
 *
 * Unlike [findMemcpyRewritesIntraBlock] which can discover a new `memcpy` instruction from multiple
 * load-store pairs within a single block, this finds a single load-store pair where:
 *
 * - The load and store can be in different basic blocks
 * - The loaded value flows directly to the store
 * - No dependencies exist that precludes to move the store up next to the load.
 */
fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> findMemcpyRewritesInterBlock(
    cfg: SbfCFG,
    scalarAnalysis: IAnalysis<ScalarRegisterStackEqualityDomain<TNum, TOffset>>
):  List<MemcpyRewrite>  {

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
            MemcpyRewrite(
                listOf(srcLocInst),
                listOf(dstLocInst),
                newInsts
            )
        }
        rewrite
    }
}
