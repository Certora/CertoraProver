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

import log.Logger
import log.LoggerTypes
import sbf.SolanaConfig
import sbf.disassembler.SbfRegister
import sbf.domains.FiniteInterval
import sbf.domains.SetOfFiniteIntervals
import kotlin.math.absoluteValue

private val logger = Logger(LoggerTypes.SBF_MEMCPY_PROMOTION)
private fun dbg(msg: () -> Any) { logger.debug(msg)}

/**
 * Represent a memcpy pattern, i.e., sequence of load/store pairs
 **/
class MemcpyPattern {
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

    data class MemcpyArgs(
        val srcReg: SbfRegister,
        val srcStart: Long,
        val dstReg: SbfRegister,
        val dstStart: Long,
        val size: ULong,
        val metadata: MetaData
    )

    /**
     * Return non-null if
     * (1) source and destination do not overlap and
     * (2) the sequence of loads and stores accesses memory in the same ordering (decreasing or increasing) and
     * (3) the sequences form a consecutive range of bytes without holes in between.
     */
    fun canBePromoted(minSizeToBePromoted: ULong): MemcpyArgs?  {
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
                MemcpyArgs(srcReg, srcRange.l, dstReg, dstRange.l, srcRange.size(), metadata)
            } else {
                null
            }
        } else {
            null
        }
    }
}
