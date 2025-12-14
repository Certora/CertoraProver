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

import allocator.Allocator
import sbf.domains.FiniteInterval
import sbf.SolanaConfig
import sbf.callgraph.SolanaFunction
import sbf.disassembler.*
import sbf.domains.*
import sbf.sbfLogger
import datastructures.stdcollections.*
import org.jetbrains.annotations.TestOnly
import sbf.analysis.AdaptiveScalarAnalysis
import sbf.analysis.AnalysisRegisterTypes
import sbf.analysis.IAnalysis
import sbf.callgraph.CVTCore
import kotlin.math.absoluteValue


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

    val scalarsAtInst = AnalysisRegisterTypes(scalarAnalysis)
    var numOfInsertedMemcpy = 0
    for (b in cfg.getMutableBlocks().values) {
        val memcpyList = findStoresToBePromoted(b, scalarsAtInst)
        numOfInsertedMemcpy += memcpyList.size
        replaceStoresWithMemcpy(b, memcpyList)
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
        for (b in cfg.getMutableBlocks().values) {
            val memcpyList = findStoresToBePromoted(b, scalarsAtInst, 1)
            numOfInsertedMemcpy += memcpyList.size
            replaceStoresWithMemcpy(b, memcpyList)
        }
    }

    sbfLogger.debug{"Number of memcpy instructions inserted by promoting stores: $numOfInsertedMemcpy"}
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
 *  Remove stores from [bb] and add a sequence of instructions that simulate a memcpy.
 *  The load instructions are left intact because they can be used by other instructions.
 *  Subsequent optimizations will remove the load instructions if they are dead.
 **/
private fun replaceStoresWithMemcpy(bb: MutableSbfBasicBlock, memcpyInfos: List<MemcpyRanges>) {
    // Add metadata to all load and store instructions to be promoted.
    // This is done **without** inserting or removing any instruction so all indexes in memcpyInfo are still valid.
    for (memcpyInfo in memcpyInfos) {
        for (loadLocInst in memcpyInfo.getLoads()) {
            addMemcpyPromotionAnnotation(bb, loadLocInst)
        }
        for (storeLocInst in memcpyInfo.getStores()) {
            addMemcpyPromotionAnnotation(bb, storeLocInst)
        }
    }

    //  Add the memcpy instructions.
    //  We need to add the memcpy instructions before the first load.
    //  For an explanation, see test13 in PromoteStoresToMemcpyTest.kt
    var numAdded = 0   // used to adjust the insertion points after each memcmpy is inserted
    for (memcpyInfo in memcpyInfos) {
        val loads = memcpyInfo.getLoads().sortedBy { it.pos}
        val firstLoad = loads.firstOrNull()
        check(firstLoad != null) {"memcpyInfo should not be empty"}
        val insertPoint = firstLoad.pos + numAdded
        val memcpyInsts = memcpyInfo.getEmittedInst()
        numAdded += memcpyInsts.size
        bb.addAll(insertPoint, memcpyInsts)
    }

    // Finally, we remove the store instructions marked before with MEMCPY_PROMOTION metadata
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
        check(inst is SbfInstruction.Mem && !inst.isLoad) {"PromoteStoresToMemcpy expects a store instruction"}

        bb.removeAt(adjPos)
    }
}

/**
 * Match at most [maxNumOfPairs] pairs of load and stores in a greedy manner.
 *
 * We scan all instructions within [bb]
 * 1. If the instruction is a load then we remember it in defLoads map.
 * 2. If the instruction is a store then we try to pair it with a load from defLoads.
 *    This is done by processStoreOfLoad. This function can return false for several reasons.
 *    For instance, if the store is far from the load then we need to prove that there is no other stores that might modify
 *    the loaded memory location.
 *    Since we try to find the maximal number of paired load/stores (i.e., the longest memcpy), we require that the accessed memory has no gaps.
 *    If there are some gaps then processStoreOfLoad will also return false.
 * 3. If at any time, processStoreOfLoad returns false we check how many pairs of load-stores we have. If any then
 *    we replace them with a memcpy instruction. When replacing more than one pair of load-stores some extra conditions must also hold.
 *    This is checked by canBePromoted.
 */
private fun <D, TNum, TOffset> findStoresToBePromoted(
    bb: SbfBasicBlock,
    scalarsAtInst: AnalysisRegisterTypes<D, TNum, TOffset>,
    maxNumOfPairs: Int = Int.MAX_VALUE
): List<MemcpyRanges>
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    // used to find the definition of a value to be stored
    val defLoads = mutableMapOf<SbfRegister, LocatedSbfInstruction>()
    // sequence of stores to be promoted to memcpy
    val memcpyInfos = ArrayList<MemcpyRanges>()
    // Note that we allow inserting multiple memcpy instructions in the same basic block
    // We try eagerly to group together the maximal number of stores
    var curMemcpy = MemcpyRanges.initialize()
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

                if (curMemcpy.getLoads().size >= maxNumOfPairs ||
                    !processStoreOfLoad(bb, locInst,
                        loadInst,
                        scalarsAtInst,
                        curMemcpy)) {

                    // If we cannot insert the store-of-load pair, then we check if we can promote
                    // the pairs we have so far.
                    val memcpyInfoToEmit = curMemcpy.canBePromoted()
                    if (memcpyInfoToEmit != null ){
                        curMemcpy.emitMemcpy(memcpyInfoToEmit)
                        memcpyInfos.add(curMemcpy)
                    }

                    // We start a fresh sequence of store-of-load pairs
                    curMemcpy = MemcpyRanges.initialize()
                    processStoreOfLoad(bb, locInst,
                        loadInst,
                        scalarsAtInst,
                        curMemcpy)
                }
            }
        } else {
            // If the instruction is not a memory instruction, then we check if we can promote
            // the store-of-load pairs we have
            val memcpyInfoToEmit = curMemcpy.canBePromoted()
            if (memcpyInfoToEmit != null) {
                curMemcpy.emitMemcpy(memcpyInfoToEmit)
                memcpyInfos.add(curMemcpy)
                curMemcpy = MemcpyRanges.initialize()
            }

            // Kill any active load if its defined register is overwritten
            for (reg in inst.writeRegister) {
                defLoads.remove(reg.r)
            }
        }
    }

    // Important: we sort memcpyInfos by the position of its first load that appears in the block
    // We need this because we need to adjust the insertion points while we will insert the emitted memcpy code.
    // If memcpyInfos are not sorted then the adjustment becomes unnecessarily complicated.
    return memcpyInfos.sortedBy { getPosOfFirstLoad(it) }
}

/** Return the position of the first load of [x] within the block to be promoted **/
private fun getPosOfFirstLoad(x: MemcpyRanges): Int {
    val loads= x.getLoads()
    check(loads.isNotEmpty()) {"MemcpyRanges should not be empty"}
    return loads.minByOrNull { it.pos }!!.pos
}

/**
 * Return true if [loadLocInst] is the loaded offset stored in [storeLocInst] and some conditions hold
 * (see isSafeToCommuteStore and addStoreOfLoad)
 **/
private fun <D, TNum, TOffset> processStoreOfLoad(
    bb: SbfBasicBlock,
    storeLocInst: LocatedSbfInstruction, loadLocInst: LocatedSbfInstruction,
    regTypes: AnalysisRegisterTypes<D, TNum, TOffset>, memcpy: MemcpyRanges
): Boolean
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    val storeInst = storeLocInst.inst
    check(storeInst is SbfInstruction.Mem) {"processStoreOfLoad $storeLocInst"}
    val loadInst = loadLocInst.inst
    check(loadInst is SbfInstruction.Mem) {"processStoreOfLoad $loadLocInst"}

    check(!storeInst.isLoad) { "processStoreOfLoad: $storeInst is not a store instruction" }
    check(loadInst.isLoad) { "processStoreOfLoad: $loadInst is not a load instruction" }

    val width = loadInst.access.width
    if (width != storeInst.access.width) {
        return false
    }
    val loadedMemAccess = normalizeLoadOrStore(loadLocInst, regTypes)
    val storedMemAccess = normalizeLoadOrStore(storeLocInst, regTypes)

    // This restriction is needed when we emit the code because if both base registers in [load] and [store]
    // are scratch registers then we cannot perform the transformation because we run out of registers
    // where we can save values.
    if (loadedMemAccess.region != MemAccessRegion.STACK && storedMemAccess.region != MemAccessRegion.STACK) {
        return false
    }

    if (!isSafeToCommuteStore(bb, loadedMemAccess, storedMemAccess, loadLocInst, storeLocInst, regTypes)) {
        return false
    }

    return memcpy.addStoreOfLoad(loadedMemAccess, storedMemAccess, loadLocInst, storeLocInst)
}

/** if [locatedInst] accesses the stack then the returned value is always normalized wrt r10 **/
fun <D, TNum, TOffset> normalizeLoadOrStore(
    locatedInst: LocatedSbfInstruction,
    regTypes: AnalysisRegisterTypes<D, TNum, TOffset>): MemAccess
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    val inst = locatedInst.inst
    check(inst is SbfInstruction.Mem){"normalizeMemInst expects a memory instruction instead of $inst"}
    val reg = inst.access.baseReg
    val stackOffset = normalizeStackAccess(locatedInst, inst.access.baseReg, inst.access.offset.toLong(), regTypes)
    return if (stackOffset != null) {
        MemAccess(SbfRegister.R10_STACK_POINTER, stackOffset, inst.access.width, MemAccessRegion.STACK)
    } else {
        val regType = regTypes.typeAtInstruction(locatedInst, inst.access.baseReg.r)
        MemAccess(reg.r,
            inst.access.offset.toLong(),
            inst.access.width,
            if (regType is SbfType.PointerType && regType !is SbfType.PointerType.Stack) {
                MemAccessRegion.NON_STACK
            } else {
                MemAccessRegion.ANY
            })
    }
}

private fun <D, TNum, TOffset> normalizeMemcpyOp(
    locatedInst: LocatedSbfInstruction,
    op: SbfRegister,
    regTypes: AnalysisRegisterTypes<D, TNum, TOffset>): MemAccess?
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    val inst = locatedInst.inst
    check(inst is SbfInstruction.Call && SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY)
    {"normalizeMemcpy expects a memcpy instruction instead of $inst"}
    check(op == SbfRegister.R1_ARG || op == SbfRegister.R2_ARG) {"normalizeMemcpyOp expects either r1 or r2 instead of $op"}

    val r3 = SbfRegister.R3_ARG
    val lenTy = regTypes.typeAtInstruction(locatedInst, r3)
    val len = if (lenTy is SbfType.NumType) {
        lenTy.value.toLongOrNull()
    } else {
        null
    }

    if (len == null || len > Short.MAX_VALUE) {
        return null
    }

    val stackOffset = normalizeStackAccess(locatedInst, Value.Reg(op), 0, regTypes)
    return if (stackOffset != null) {
        MemAccess(SbfRegister.R10_STACK_POINTER, stackOffset, len.toShort(), MemAccessRegion.STACK)
    } else {
        val regType = regTypes.typeAtInstruction(locatedInst, op)
        MemAccess(op, 0 , len.toShort(),
            if (regType is SbfType.PointerType && regType !is SbfType.PointerType.Stack) {
                MemAccessRegion.NON_STACK
            } else {
                MemAccessRegion.ANY
            })
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
        @Suppress("UNUSED_PARAMETER") load: MemAccess, store: MemAccess,
        loadLocInst: LocatedSbfInstruction, storeLocInst: LocatedSbfInstruction,
        regTypes: AnalysisRegisterTypes<D, TNum, TOffset>): Boolean
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset>,
              D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    check(loadLocInst.label == bb.getLabel())
    {"can only promote pairs of load-store within the same block $loadLocInst"}
    check(storeLocInst.label == bb.getLabel())
    {"can only promote pairs of load-store within the same block $storeLocInst"}

    val loadInst = loadLocInst.inst
    check(loadInst is SbfInstruction.Mem) {"isSafeToCommuteStore $loadLocInst should be a load"}
    val storeInst = storeLocInst.inst
    check(storeInst is SbfInstruction.Mem) {"isSafeToCommuteStore $storeLocInst should be a store"}


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

    sbfLogger.debug{"isSafeToCommuteStore $storeInst up to $loadInst?"}
    // aliases keeps track of other register that might be assigned to the loaded register
    val aliases = mutableSetOf<Value.Reg>()
    aliases.addAll(loadInst.writeRegister)
    bb.getInstructions().subList(loadLocInst.pos+1 ,storeLocInst.pos).forEach {
        // Ensure that loaded register cannot be modified between the load and store
        if (it.writeRegister.intersect(loadInst.writeRegister).isNotEmpty()) {
            sbfLogger.debug{"\tisSafeToCommuteStore: $it might modify the loaded register"}
            return false
        }

        // Ensure that the base register of the store is not overwritten between the load and store.
        // This restriction might be not necessary, specially if load and stores are on the stack, but we prefer
        // to be conservative here.
        if (it.writeRegister.contains(storeBaseReg)) {
            sbfLogger.debug{"\tisSafeToCommuteStore: $it might modify the base register of the store"}
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
                sbfLogger.debug{"\tisSafeToCommuteStore: $it might affect the loaded register"}
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
                                sbfLogger.debug { "\tisSafeToCommuteStore OK: $inst is stack and $storeInst is on stack but no overlap." }
                            } else {
                                sbfLogger.debug { "\tisSafeToCommuteStore FAIL: $inst is stack and $storeInst is on stack and they overlap." }
                            }
                            noOverlap
                        }
                        MemAccessRegion.NON_STACK -> {
                            sbfLogger.debug {"\tisSafeToCommuteStore OK: $inst is non-stack and $storeInst is on stack"}
                            true
                        }
                        MemAccessRegion.ANY -> {
                            sbfLogger.debug {"\tisSafeToCommuteStore FAIL: $inst is any memory and $storeInst is on stack"}
                            false
                        }
                    }
                    if (!canCommuteStore) {
                        return false
                    }
                } else if (inst is SbfInstruction.Call && SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY) {
                    // Check store commute over destination
                    val normDest = normalizeMemcpyOp(locInst, SbfRegister.R1_ARG, regTypes)
                    if (normDest == null) {
                        sbfLogger.debug {"\tisSafeToCommuteStore FAIL: $storeInst is on stack but does not commute over $inst"}
                        return false
                    }
                    when (normDest.region) {
                        MemAccessRegion.STACK -> {
                            if (!normDest.overlap(storeRange)) {
                                sbfLogger.debug { "\tisSafeToCommuteStore OK: $inst is stack and $storeInst is on stack but no overlap." }
                            } else {
                                sbfLogger.debug { "\tisSafeToCommuteStore FAIL: $inst is stack and $storeInst is on stack and they overlap." }
                                return false
                            }
                        }
                        else -> {
                            sbfLogger.debug { "\tisSafeToCommuteStore FAIL: stores do not commute over memcpy for now" }
                            return false
                        }
                    }

                    // Check store commute over source
                    val normSrc = normalizeMemcpyOp(locInst, SbfRegister.R2_ARG, regTypes)
                    if (normSrc == null) {
                        sbfLogger.debug {"\tisSafeToCommuteStore FAIL: $storeInst is on stack but does not commute over $inst"}
                        return false
                    }
                    when (normSrc.region) {
                        MemAccessRegion.STACK -> {
                            if (!normSrc.overlap(storeRange)) {
                                sbfLogger.debug { "\tisSafeToCommuteStore OK: $inst is stack and $storeInst is on stack but no overlap." }
                            } else {
                                sbfLogger.debug { "\tisSafeToCommuteStore FAIL: $inst is stack and $storeInst is on stack and they overlap." }
                                return false
                            }
                        }
                        else -> {
                            sbfLogger.debug { "\tisSafeToCommuteStore FAIL: stores do not commute over memcpy for now" }
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
                                sbfLogger.debug {"\tisSafeToCommuteStore OK: $inst is stack and $storeInst is non-stack"}
                                true
                            }
                            MemAccessRegion.NON_STACK -> {
                                if (normMemInst.reg == storeBaseReg.r) { // we know that storeBaseReg doesn't change
                                    val noOverlap = !normMemInst.overlap(storeRange)
                                    if (noOverlap) {
                                        sbfLogger.debug { "\tisSafeToCommuteStore OK: $inst is non-stack and $storeInst non-stack but same register and no overlap." }
                                    } else {
                                        sbfLogger.debug { "\tisSafeToCommuteStore FAIL: $inst is non-stack and $storeInst non-stack but same register and overlap." }
                                    }
                                    noOverlap
                                } else {
                                    sbfLogger.debug {"\tisSafeToCommuteStore FAIL: $inst is non-stack and $storeInst is non-stack"}
                                    false
                                }
                            }
                            MemAccessRegion.ANY -> {
                                sbfLogger.debug {"\tisSafeToCommuteStore FAIL: $inst is any memory and $storeInst is non-stack"}
                                false
                            }
                    }
                } else {
                    // We could do better, but we bail out if the instruction is a memcpy
                    val isNotMemcpy = !(inst is SbfInstruction.Call && SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY)
                    if (!isNotMemcpy) {
                        sbfLogger.debug { "\tisSafeToCommuteStore FAIL: stores do not commute over memcpy for now" }
                    }
                    isNotMemcpy
                }
            }
            if (!canCommuteStore) {
                return false
            }
        }
        MemAccessRegion.ANY -> {
            sbfLogger.debug { "\tisSafeToCommuteStore: $storeInst on unknown memory " }
            return bb.getInstructions().subList(loadLocInst.pos+1, storeLocInst.pos).all {
                     it !is SbfInstruction.Mem &&
                    !(it is SbfInstruction.Call && SolanaFunction.from(it.name) == SolanaFunction.SOL_MEMCPY)
            }
        }
    }
    sbfLogger.debug {"isSafeToCommuteStore OK"}
    return true
}

enum class MemAccessRegion {
    STACK,
    NON_STACK,
    ANY
}
data class MemAccess(val reg: SbfRegister, val offset: Long, val width: Short, val region: MemAccessRegion) {
    fun overlap(other: FiniteInterval): Boolean {
        val x = FiniteInterval.mkInterval(offset, width.toLong())
        return x.overlap(other)
    }
}

private data class MemcpyToEmitInfo(val srcReg: SbfRegister, val srcStart: Long,
                                    val dstReg: SbfRegister, val dstStart: Long, val size: ULong,
                                    val metadata: MetaData)

private data class MemcpyRanges(private val loads: ArrayList<MemAccess>, private val stores: ArrayList<MemAccess>) {
    /** Class invariants:
     *  0. `loads.size == stores.size`
     *  1. `forall 0 <= i < size-1 :: distance(loads[i ],loads[i+1]) == distance(stores[i ], stores[i+1])`
     *  2. `forall i,j, i!=j :: loads[i ].region == loads[j ].region && stores[i ].region == stores[j ].region`
     *  3. `forall i,j, i!=j :: loads[i ].reg == loads[j ].reg && stores[i ].reg == stores[j ].reg`
     **/
    private val loadLocInsts = ArrayList<LocatedSbfInstruction>()
    private val storeLocInsts = ArrayList<LocatedSbfInstruction>()
    private val emittedInsts = ArrayList<SbfInstruction>()

    companion object {
        fun initialize() = MemcpyRanges(arrayListOf(), arrayListOf())
    }

    /**
     * Return false if it cannot maintain all class invariants (See above).
     * Otherwise, it will promote ([load], [store]) as part of a memcpy.
     **/
    fun addStoreOfLoad(load: MemAccess, store: MemAccess,
                       loadLocInst: LocatedSbfInstruction, storeLocInst: LocatedSbfInstruction): Boolean {
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
    fun canBePromoted(): MemcpyToEmitInfo?  {
        check(loads.size == stores.size)
        {"canBePromoted expects same number of loads and stores: $loads and $stores"}
        check(loadLocInsts.size == storeLocInsts.size)
        {"canBePromoted expects same number of loads and stores: $loadLocInsts and $storeLocInsts"}
        check(loads.size == loadLocInsts.size)
        {"canBePromoted: $loads and $loadLocInsts should have same size"}
        check(stores.size == storeLocInsts.size)
        {"canBePromoted: $stores and $storeLocInsts should have same size"}

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
                    sbfLogger.debug {
                        "promoteStoresToMemcpy: we cannot prove that no overlaps between " + "" +
                            "$loadLocInsts and $storeLocInsts"
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
                            // Note that we completely give up here even if we could also try smaller number of pairs of load-store.
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
        return if (srcRange.size() == dstRange.size() && srcRange.size() >= 1UL) {
            val srcReg = loads.first().reg
            val dstReg = stores.first().reg
            val srcRegion = loads.first().region
            val dstRegion = stores.first().region
            // We will use the metadata of the first load as metadata of the promoted memcpy
            val metadata = loadLocInsts.first().inst.metaData
            if (noOverlaps(srcRegion, srcRange.l, dstRegion, dstRange.l, srcRange.size())) {
                MemcpyToEmitInfo(srcReg, srcRange.l, dstReg, dstRange.l, srcRange.size(), metadata)
            } else {
                null
            }
        } else {
            null
        }
    }

    /**
     * memcpy expects inputs in registers r1,r2,r3. Before we modify these registers we need to save them.
     * The only mechanism we have to save registers without overwritten registers used later by the program is
     * to call special function `CVT_save_scratch_registers` so that scratch registers can be saved, and then
     * they can be used to save r1,r2, and r3.
     * We emit code that allows us to do that:
     * ```
     *   CVT_save_scratch_registers
     *   r6 := r1
     *   r7 := r2
     *   r8 := r3
     *   r1 := [dstReg]
     *   r1 := r1 + [dstStart]
     *   r2 := [srcReg]
     *   r2 := r2 + [srcStart]
     *   r3 := [size]
     *   sol_memcpy__
     *   r1 := r6
     *   r2 := r7
     *   r3 := r8
     *   CVT_restore_scratch_registers
     * ```
     **/
    fun emitMemcpy(memcpyInfo: MemcpyToEmitInfo) {
        val srcReg = memcpyInfo.srcReg
        val dstReg = memcpyInfo.dstReg
        check(dstReg == SbfRegister.R10_STACK_POINTER || srcReg == SbfRegister.R10_STACK_POINTER)
        {"emitMemcpy expects one register to be r10"}

        val srcStart = memcpyInfo.srcStart
        val dstStart = memcpyInfo.dstStart
        val size = memcpyInfo.size

        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r6 = Value.Reg(SbfRegister.R6)
        val r7 = Value.Reg(SbfRegister.R7)
        val r8 = Value.Reg(SbfRegister.R8)
        val r9 = Value.Reg(SbfRegister.R9)


        val scratchRegs = arrayListOf(r6,r7,r8,r9)
        if (srcReg !=  SbfRegister.R10_STACK_POINTER) {
            scratchRegs.remove(Value.Reg(srcReg))
        }
        if (dstReg !=  SbfRegister.R10_STACK_POINTER) {
            scratchRegs.remove(Value.Reg(dstReg))
        }
        check(scratchRegs.size >= 3) {"emitMemcpy needs three scratch registers but it only has $scratchRegs"}
        val temp1 = scratchRegs[0]
        val temp2 = scratchRegs[1]
        val temp3 = scratchRegs[2]

        val oldMetaData = memcpyInfo.metadata
        val callId = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
        check(callId >= 0) {"expected non-negative call id"}
        val metaData = oldMetaData.plus(SbfMeta.CALL_ID to callId.toULong()).plus(SbfMeta.MEMCPY_PROMOTION to "")

        emittedInsts.add(SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData = metaData))

        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, temp1, r1, true))
        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, temp2, r2, true))
        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, temp3, r3, true))

        val rename = fun(reg: Value.Reg): Value.Reg {
            return when (reg) {
                r1 -> temp1
                r2 -> temp2
                r3 -> temp3
                else -> reg
            }
        }
        if (r1.r != dstReg) {
            emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r1, rename(Value.Reg(dstReg)), true))
        }
        emittedInsts.add(SbfInstruction.Bin(BinOp.ADD, r1, Value.Imm(dstStart.toULong()), true, metaData = MetaData(SbfMeta.MEMCPY_PROMOTION to "")))
        if (r2.r != srcReg) {
            emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r2, rename(Value.Reg(srcReg)), true))
        }
        emittedInsts.add(SbfInstruction.Bin(BinOp.ADD, r2, Value.Imm(srcStart.toULong()), true, metaData = MetaData(SbfMeta.MEMCPY_PROMOTION to "")))
        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(size), true))

        emittedInsts.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCPY, oldMetaData.plus(SbfMeta.MEMCPY_PROMOTION to "")))

        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r1, temp1, true))
        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r2, temp2, true))
        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r3, temp3, true))
        emittedInsts.add(SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData = metaData))
    }

    fun getStores(): List<LocatedSbfInstruction> = storeLocInsts

    fun getLoads(): List<LocatedSbfInstruction> = loadLocInsts

    fun getEmittedInst() = emittedInsts
}

