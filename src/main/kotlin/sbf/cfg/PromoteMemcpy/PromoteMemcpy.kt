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

import org.jetbrains.annotations.TestOnly
import sbf.SolanaConfig
import sbf.analysis.*
import sbf.disassembler.GlobalVariables
import sbf.domains.*
import kotlin.toULong


/**
 *  Promote load-store pairs into `memcpy` instructions.
 */
fun promoteMemcpy(
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
        CFGTransformScalarDomFac()
    )
    // [findWideningAndNarrowingStores] depends on endianness. Since we never expect big-endian,
    // we prefer to fail so that we are aware.
    check(globals.elf.isLittleEndian())

    promoteMemcpyIntraBlock(cfg, scalarAnalysis, globals.elf.useDynamicFrames())
    promoteMemcpyInterBlock(cfg, scalarAnalysis)
}

/**
 * Replace multiple load-store pairs and replace them with `memcpy` instructions.
 *
 * The transformation is **intra-block**.
 */
@TestOnly
fun <D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> promoteMemcpyIntraBlock(
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
     * Since the transformation is intra-block, and it preserves semantics
     * (replace load-store pairs with `memcpy`/`memcpy_trunc`/`memcpy_zext`), we can still use
     * [scalarAnalysis] since it only contains the invariants that hold at the entry of the block.
     *
     * However, [AnalysisRegisterTypes] will collect information at the instruction level.
     * Thus, `types` should NOT be used for answering queries about basic block `b`
     * once `b` has changed. But information about other blocks is still valid.
     */
    val types = AnalysisRegisterTypes(scalarAnalysis)
    for (b in cfg.getMutableBlocks().values) {
        val rewrites = mutableListOf<MemcpyRewrite>()

        // (A) load and stores access **different** number of bytes
        rewrites.addAll(findWideningAndNarrowingRewritesIntraBlock(b, types, useDynFrames))
        // (B) load and stores access **same** number of bytes
        rewrites.addAll(findMemcpyRewritesIntraBlock(b, types, useDynFrames))

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
                findMemcpyRewritesIntraBlock(b, types, useDynFrames, maxNumOfPairs = 1)
            )
        }

        // Transforms some load instructions (by narrowing them) that were not involved
        // in any of the memcpy promotions from before
        narrowMaskedLoads(b, cfg) { loadInst ->
            loadInst.isLoad &&
                loadInst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null
        }
    }
}

/**
 *  Replace a single load-store pair with a `memcpy` instruction.
 *
 *  The transformation is inter-block.
 */
private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> promoteMemcpyInterBlock(
    cfg: MutableSbfCFG,
    scalarAnalysis: IAnalysis<ScalarRegisterStackEqualityDomain<TNum, TOffset>>
) {
    val rewrites = findMemcpyRewritesInterBlock(cfg, scalarAnalysis)
    applyRewrites(cfg, rewrites)
}
