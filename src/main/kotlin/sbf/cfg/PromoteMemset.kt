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

import sbf.SolanaConfig
import sbf.analysis.AnalysisRegisterTypes
import sbf.analysis.GenericScalarAnalysis
import sbf.disassembler.GlobalVariables
import sbf.domains.*
import kotlin.toULong
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import sbf.analysis.IAnalysis

private val logger = Logger(LoggerTypes.SBF_MEMSET_PROMOTION)
private fun dbg(msg: () -> Any) { logger.debug(msg)}

/**
 * Options for [promoteMemset]
 *
 * @property minNumOfStoresToBePromoted Minimum number of stores a pattern must have
 **/
data class MemsetPromotionOpts(
    val minNumOfStoresToBePromoted: Int,
)

/**
 * Identifies `memset` patterns in the given [cfg] by scanning each basic block for
 * sequences of store instructions that can be promoted to a `memset` call, and
 * it replaces the stores instructions with an equivalent call to `memset`.
 */
fun promoteMemset(
    cfg: MutableSbfCFG,
    globals: GlobalVariables,
    memSummaries: MemorySummaries,
    opts: MemsetPromotionOpts = MemsetPromotionOpts(2)
) {

    // We run a scalar analysis to know whether a store instruction is storing an immediate value or not.
    // Most of the cases, it is enough a very local analysis, but we run the analysis for the whole CFG anyway.
    val sbfTypeFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
    val scalarAnalysis = GenericScalarAnalysis(
        cfg,
        globals,
        memSummaries,
        sbfTypeFac,
        domFac = CFGTransformScalarDomFac())

    promoteMemset(cfg, scalarAnalysis, opts)
}

fun<D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> promoteMemset(
    cfg: MutableSbfCFG,
    scalarAnalysis: IAnalysis<D>,
    opts: MemsetPromotionOpts
) where D: AbstractDomain<D>,
        D: ScalarValueProvider<TNum, TOffset> {

    val types = AnalysisRegisterTypes(scalarAnalysis)
    val rewrites = mutableListOf<MemsetRewrite>()

    // First, we identify memset patterns and emit code (`memsetRewrite`) to replace stores
    // with calls to `memset`.
    for (bb in cfg.getBlocks().values) {
        rewrites.addAll(findMemsetPatternsIntraBlock(bb, types, opts))
    }

    // Second, we do the actual CFG transformation
    applyRewrites(cfg, rewrites)
}

/**
 * Represents a `memset` pattern, i.e., a sequence of stores that write the same value
 * to a contiguous memory range relative to a base register.
 *
 * @property stores The list of store instructions that form the pattern.
 * @property baseReg The base register used in all store instructions.
 * @property range The union of memory intervals covered by the stores.
 * @property storedVal The immediate value written by all stores.
 */
private data class MemsetPattern(
    val stores: List<LocatedSbfInstruction>,
    val baseReg: Value.Reg,
    val range: SetOfFiniteIntervals,
    val storedVal: ULong
) {

    private data class StoreInfo(
        val reg: Value.Reg,
        val offset: Long,
        val width: Long,
        val storedVal: ULong
    )

    companion object {

        private fun getStoredValAsImmVal(
            storedVal: Value,
            locInst: LocatedSbfInstruction,
            getRegNumericalValue: (LocatedSbfInstruction, Value.Reg) -> ULong?
        ): ULong? {
            return when(storedVal) {
                is Value.Imm ->  storedVal.v
                is Value.Reg ->  getRegNumericalValue(locInst, storedVal)
            }
        }

        private fun extractStoreInfo(
            storeLocInst: LocatedSbfInstruction,
            getRegNumericalValue: (LocatedSbfInstruction, Value.Reg) -> ULong?
        ): StoreInfo? {
            val storeInst = storeLocInst.inst

            if (!(storeInst is SbfInstruction.Mem && !storeInst.isLoad)) {
                return null
            }

            val storedVal = getStoredValAsImmVal(storeInst.value, storeLocInst, getRegNumericalValue)
                ?: return null

            return StoreInfo(
                reg = storeInst.access.base,
                offset = storeInst.access.offset.toLong(),
                width = storeInst.access.width.toLong(),
                storedVal = storedVal
            )
        }

        private fun isByteUniform(x: ULong): Boolean {
            return when {
                x == 0UL -> true
                x.toLong() == -1L -> true
                else -> false
            }
        }

        /**
         * Creates a new [MemsetPattern] from a single store instruction.
         *
         * @param storeLocInst The first store instruction to seed the pattern.
         * @return A new [MemsetPattern], or `null` if [storeLocInst] is not a valid
         * store instruction with an immediate value.
         */
        fun new(
            storeLocInst: LocatedSbfInstruction,
            getRegNumericalValue: (LocatedSbfInstruction, Value.Reg) -> ULong?
        ): MemsetPattern? {
            val info = extractStoreInfo(storeLocInst, getRegNumericalValue) ?: return null

            if (!isByteUniform(info.storedVal)) {
                return null
            }

            return MemsetPattern(
                stores = listOf(storeLocInst),
                baseReg = info.reg,
                range = SetOfFiniteIntervals(listOf(FiniteInterval.mkInterval(info.offset, info.width))),
                storedVal = info.storedVal
            )
        }
    }

    /**
     * Tries to extend this pattern with an additional store instruction.
     *
     * The instruction is accepted only if it:
     * - is a store with an immediate value,
     * - writes the same value as [storedVal],
     * - uses the same base register as [baseReg].
     *
     * @param storeLocInst The store instruction to add.
     * @return A new [MemsetPattern] with the updated stores and range,
     * or `null` if [storeLocInst] is incompatible with this pattern.
     */
    fun add(
        storeLocInst: LocatedSbfInstruction,
        getRegNumericalValue: (LocatedSbfInstruction, Value.Reg) -> ULong?
    ): MemsetPattern? {
        val info = extractStoreInfo(storeLocInst, getRegNumericalValue) ?: return null

        if (baseReg != info.reg) {
            return null
        }

        if (storedVal != info.storedVal) {
            return null
        }

        return copy(
            stores = stores + listOf(storeLocInst),
            range = range.add(FiniteInterval.mkInterval(info.offset, info.width))
        )
    }

    fun canBePromoted(opts: MemsetPromotionOpts): Boolean {
        if (stores.size < opts.minNumOfStoresToBePromoted) {
            return false
        }

        return (range.size() == 1)
    }

    /**
     * Attempts to promote this pattern to a [MemsetRewrite].
     *
     * @return A [MemsetRewrite] if the pattern qualifies for promotion and
     * [emitMemset] succeeds, or `null` otherwise.
     */
    fun toRewrite(opts: MemsetPromotionOpts): MemsetRewrite? {
        if (!canBePromoted(opts)) {
            return null
        }
        val interval = range.getSingleton() ?: return null
        val emittedCode = emitMemset(
            ptr = baseReg.r,
            offset = interval.l,
            value = Value.Imm(storedVal),
            len = interval.size(),
            metadata = stores.first().inst.metaData
        ) ?: return null
        return MemsetRewrite(stores = stores, insts = emittedCode)
    }
}

/**
 * @property stores The original store instructions to be removed from the basic block.
 * @property insts The replacement instructions that implement the equivalent `memset` call.
 */
private data class MemsetRewrite(
    val stores: List<LocatedSbfInstruction>,
    val insts: List<SbfInstruction>
)

/**
 * Scans a single basic block [bb] for sequences of stores that can be rewritten as `memset` calls.
 *
 * The algorithm scans instructions sequentially and greedily extends the current
 * [MemsetPattern] with each new store it encounters. A pattern grows as long as
 * consecutive stores are compatible (same base register, same stored value).
 * As soon as an incompatible store is found, the current pattern is finalized and
 * a new one is started from that store — no backtracking is performed.
 *
 * A pattern under construction is discarded (without being promoted) if there is another
 * store writing to the base register or any non-store instruction writes to the pattern's base register.
 *
 * @param bb The basic block to scan.
 * @param opts  User options to decide when the promotion should take place
 * @return A list of [MemsetRewrite], each describing the stores to remove and the
 * `memset` instructions to emit in their place.
 */
private fun<D, TNum, TOffset> findMemsetPatternsIntraBlock(
    bb: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    opts: MemsetPromotionOpts
): List<MemsetRewrite>
where D: AbstractDomain<D>,
      D: ScalarValueProvider<TNum, TOffset>,
      TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>
{
    val outRewrites = mutableListOf<MemsetRewrite>()
    var current: MemsetPattern? = null

    fun finalizePattern() {
        dbg { "\t\tCalling finalizePattern()" }
        current?.toRewrite(opts)?.let {
            dbg { "\t\t\tAdded rewrite $it" }
            outRewrites.add(it)
        }
        current = null
    }

    val getRegNumericValue: (LocatedSbfInstruction, Value.Reg) -> ULong? = { locInst, reg ->
        (types.typeAtInstruction(locInst, reg) as? SbfType.NumType)?.value?.toLongOrNull()?.toULong()
    }

    for (locInst in bb.getLocatedInstructions()) {
        val inst = locInst.inst
        when {
            inst is SbfInstruction.Mem && !inst.isLoad -> {
                current = if (current == null) {
                    dbg { "Started a new pattern with $inst" }
                    MemsetPattern.new(locInst, getRegNumericValue)
                } else {
                    dbg { "Trying to extend current pattern with $inst"}
                    current!!.add(locInst, getRegNumericValue)
                        ?: run {
                            dbg { "\tCannot extend the memset pattern (1)" }
                        // Incompatible store: finalize current pattern and start a new one
                        finalizePattern()
                        MemsetPattern.new(locInst, getRegNumericValue)
                    }
                }
            }
            // If this instruction writes to the base register of the pattern
            // we are building, the pattern is no longer valid and must be discarded.
            current != null && inst.writeRegister.contains(current!!.baseReg) -> {
                dbg { "\tCannot extend the memset pattern (2)" }
                finalizePattern()
            }
            inst.isSaveScratchRegisters() || inst.isRestoreScratchRegisters() -> {
                dbg { "\tCannot extend the memset pattern (3)" }
                finalizePattern()
            }
            inst.isTerminator() -> {
                dbg { "\tCannot extend the memset pattern (4)" }
                finalizePattern()
            }
        }
    }

    return outRewrites
}

/**
 * Applies a list of [MemsetRewrite]s to the given [cfg], replacing each group of
 * store instructions with the corresponding `memset` instructions.
 *
 * For each rewrite, the first store in [MemsetRewrite.stores] is replaced by
 * [MemsetRewrite.insts], and the remaining stores are removed. Since removing
 * instructions shifts positions within the basic block, rewrites are applied in
 * reverse order of their first store's position to keep indices stable.
 *
 * @param cfg The mutable control flow graph to rewrite in place.
 * @param rewrites The list of [MemsetRewrite]s to apply.
 */
private fun applyRewrites(cfg: MutableSbfCFG, rewrites: List<MemsetRewrite>) {
    // Group rewrites by basic block
    val rewritesByBlock = rewrites.groupBy { rewrite ->
        rewrite.stores.first().label
    }

    for ((label, blockRewrites) in rewritesByBlock) {
        val bb = cfg.getMutableBlock(label) ?: continue

        // Sort in reverse order of the first store's position so that removing
        // instructions earlier in the block does not affect the indices of
        // instructions we have yet to process.
        val sortedRewrites = blockRewrites.sortedByDescending { rewrite ->
            rewrite.stores.first().pos
        }

        for (rewrite in sortedRewrites) {
            // Remove all stores except the first, in reverse order to keep indices stable
            rewrite.stores.drop(1).asReversed().forEach { store ->
                bb.removeAt(store.pos)
            }
            val firstStorePos = rewrite.stores.first().pos

            // Replace the first store with the emitted memset instructions
            bb.addAll(firstStorePos, rewrite.insts)
            bb.removeAt(firstStorePos + rewrite.insts.size)
        }
    }
}
