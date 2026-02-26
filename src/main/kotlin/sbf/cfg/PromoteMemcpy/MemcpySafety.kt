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
import sbf.analysis.AnalysisRegisterTypes
import sbf.domains.*

private val logger = Logger(LoggerTypes.SBF_MEMCPY_PROMOTION)
private fun dbg(msg: () -> Any) { logger.debug(msg)}

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
fun <D, TNum, TOffset> isSafeToCommuteStore(
    bb: SbfBasicBlock,
    @Suppress("UNUSED_PARAMETER") load: MemAccess,
    loadLocInst: LocatedSbfInstruction,
    store: MemAccess,
    storeLocInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    useDynFrames: Boolean
): Boolean
    where TNum : INumValue<TNum>,
          TOffset : IOffset<TOffset>,
          D : AbstractDomain<D>, D : ScalarValueProvider<TNum, TOffset> {

    val name = "isSafeToCommuteStore"

    check(loadLocInst.label == bb.getLabel())  {
        "can only promote pairs of load-store within the same block $loadLocInst"
    }
    check(storeLocInst.label == bb.getLabel()) {
        "can only promote pairs of load-store within the same block $storeLocInst"
    }

    val loadInst = loadLocInst.inst
    check(loadInst is SbfInstruction.Mem) { "$name: $loadLocInst should be a load"}
    val storeInst = storeLocInst.inst
    check(storeInst is SbfInstruction.Mem) {"$name: $storeLocInst should be a store"}


    // If optimistic mode, treat ANY non-stack region as NON_STACK
    fun MemAccessRegion.resolve() =
        if (SolanaConfig.optimisticMemcpyPromotion() && this == MemAccessRegion.ANY) {
            MemAccessRegion.NON_STACK
        } else {
            this
        }

    val storeBaseReg = storeInst.access.base
    val storeRange = FiniteInterval.mkInterval(store.offset, store.width.toLong())

    val betweenInsts = bb.getLocatedInstructions().subList(loadLocInst.pos + 1, storeLocInst.pos)

    dbg { "$name: $storeInst up to $loadInst?" }
    val aliases = loadInst.writeRegister.toMutableSet()


    for (inst in betweenInsts.map { it.inst }) {
        // check no instruction can modify the loaded register
        if (!inst.isRestoreScratchRegisters() &&
            inst.writeRegister.intersect(loadInst.writeRegister).isNotEmpty()) {
            dbg { "\t$name: $inst might modify the loaded register" }
            return false
        }

        // check no instruction can modify the store's base register
        if (!inst.isRestoreScratchRegisters() &&
            !inst.isStackPush(useDynFrames) &&
            !inst.isStackPop(useDynFrames) &&
            inst.writeRegister.contains(storeBaseReg)) {
            dbg { "\t$name: $inst might modify the base register of the store" }
            return false
        }

        if (inst is SbfInstruction.Bin && inst.op == BinOp.MOV &&
            inst.readRegisters.intersect(aliases).isNotEmpty()) {
            aliases.add(inst.dst)
        }

        // check that the loaded register cannot be used directly or indirectly by assume/assert
        if ((inst.isAssertOrSatisfy() || inst is SbfInstruction.Assume) &&
            inst.readRegisters.intersect(aliases).isNotEmpty()) {
            dbg { "\t$name: $inst might affect the loaded register" }
            return false
        }
    }

    /**
     * Check that [norm] does not overlap with [range]
     **/
    fun stackNoOverlap(norm: MemAccess, interInst: SbfInstruction, range: FiniteInterval): Boolean {
        val noOverlap = !norm.overlap(range)
        if (noOverlap) {
            dbg { "\t$name OK: $interInst is stack and $storeInst is stack but no overlap." }
        } else {
            dbg { "\t$name FAIL: $interInst is stack and $storeInst is stack and they overlap." }
        }
        return noOverlap
    }

    fun commuteOverMemcpy(locInst: LocatedSbfInstruction, range: FiniteInterval): Boolean {
        val inst = locInst.inst
        val memAccesses = normalizeMemcpy(locInst, types) ?: run {
            dbg { "\t$name FAIL: cannot statically determine length in $inst" }
            return false
        }
        val (normSrc, normDest) = memAccesses
        for (normSrcOrDst in listOf(normSrc, normDest)) {
            if (normSrcOrDst.region != MemAccessRegion.STACK) {
                dbg { "\t$name FAIL: stores do not commute over memcpy for now" }
                return false
            }
            if (!stackNoOverlap(normSrcOrDst, inst, range)) {
                return false
            }
        }
        return true
    }

    // Check that any intermediate store/load/memcpy cannot read/write to the same bytes as the
    // store instruction
    return when (store.region.resolve()) {
        MemAccessRegion.STACK -> {
            betweenInsts.all { locInst ->
                val inst = locInst.inst
                when {
                    inst is SbfInstruction.Mem -> {
                        val normAccess = normalizeLoadOrStore(locInst, types)
                        when (normAccess.region.resolve()) {
                            MemAccessRegion.STACK -> {
                                stackNoOverlap(normAccess, inst, storeRange)
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
                    }
                    inst.isMemcpy() ->  {
                        commuteOverMemcpy(locInst, storeRange)
                    }
                    else -> true
                }
            }
        }
        MemAccessRegion.NON_STACK -> {
            betweenInsts.all { locInst ->
                val inst = locInst.inst
                when {
                    inst is SbfInstruction.Mem -> {
                        val normAccess = normalizeLoadOrStore(locInst, types)
                        when (normAccess.region.resolve()) {
                            MemAccessRegion.STACK -> {
                                dbg { "\t$name OK: $inst is stack and $storeInst is non-stack" }
                                true
                            }
                            MemAccessRegion.NON_STACK -> {
                                if (normAccess.reg != storeBaseReg.r) {
                                    dbg { "\t$name FAIL: $inst is non-stack and $storeInst is non-stack" }
                                    return@all false
                                }
                                val noOverlap = !normAccess.overlap(storeRange)
                                if (noOverlap) {
                                    dbg { "\t$name OK: $inst non-stack, same register, no overlap." }
                                } else {
                                    dbg { "\t$name FAIL: $inst non-stack, same register, overlap."}
                                }
                                noOverlap
                            }
                            MemAccessRegion.ANY -> {
                                dbg { "\t$name FAIL: $inst is any memory and $storeInst is non-stack" }
                                false
                            }
                        }
                    }
                    inst.isMemcpy() -> {
                        dbg { "\t$name FAIL: stores do not commute over memcpy for now" }
                        false
                    }
                    else -> true
                }
            }
        }
        MemAccessRegion.ANY -> {
            dbg { "\t$name: $storeInst on unknown memory" }
            betweenInsts.none { it.inst is SbfInstruction.Mem || it.inst.isMemcpy() }
        }
    }.also { if (it) {
             dbg { "$name OK" }
        }
    }
}
