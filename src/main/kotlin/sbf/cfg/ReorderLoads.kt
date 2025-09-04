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

import sbf.analysis.AnalysisRegisterTypes
import sbf.analysis.IAnalysis
import sbf.domains.*
import sbf.sbfLogger

/**
 * Move memory loads closer to its use if its use is a value to be stored within the same block.
 *
 * This transformation is useful before `PromoteStoresToMemcpy`.
 *
 * For instance, given this code:
 * ```
 *   r1 := *(u64 *) (r10 + -32)
 *   r2 := *(u64 *) (r10 + -360)
 *   *(u64 *) (r2 + 16) := r1
 * ```
 *
 * we transform it into:
 *
 * ```
 *   r2 := *(u64 *) (r10 + -360)
 *   r1 := *(u64 *) (r10 + -32)
 *   *(u64 *) (r2 + 16) := r1
 * ```
 * The transformation does not invalidate [scalarAnalysis] since [scalarAnalysis] only contains
 * invariants at the basic block boundaries and this transformation is intra-block.
 *
 * @param [cfg] The CFG to be transformed
 * @param [scalarAnalysis] Scalar invariants
 *
 **/
fun <D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> reorderLoads(
    cfg: MutableSbfCFG,
    scalarAnalysis: IAnalysis<D>,
    maxNumOfReorderedLoadsPerBlock: Int = 10 // To avoid unexpected non-termination
) where D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    sbfLogger.debug { "Started reordering of load instructions" }
    var totalReorderedLoads = 0
    for (b in cfg.getMutableBlocks().values) {
        // For each block we reorder loads until no more changes.
        // We do it in this way because `regTypes` might be invalidated after each reordering.
        var change = true
        var i = 0
        while (change && i < maxNumOfReorderedLoadsPerBlock) {
            change = false
            val regTypes = AnalysisRegisterTypes(scalarAnalysis)
            findReordering(b, regTypes)?.let { (loadLocInst, storeLocInst) ->

                // Do the actual transformation: remove load and add it before the store
                b.removeAt(loadLocInst.pos)
                b.add(storeLocInst.pos - 1, loadLocInst.inst)

                change = true
                totalReorderedLoads++
            }
            i++
        }
    }
    sbfLogger.debug {
        "Finished reordering of load instructions. " +
        "Total number of reordered loads=$totalReorderedLoads"
    }
}

/**
 * Given a block [b] returns the **first** pair of load and store instruction such that the load instruction can be moved
 * right before the store instruction.
 */
private fun<D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> findReordering(
    b: SbfBasicBlock,
    regTypes: AnalysisRegisterTypes<D,TNum, TOffset>
): Pair<LocatedSbfInstruction, LocatedSbfInstruction>?
where D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset>{

    for (locInst in b.getLocatedInstructions()) {
        val loadInst = locInst.inst

        if (loadInst !is SbfInstruction.Mem || !loadInst.isLoad) {
            continue
        }

        val lhs = loadInst.value
        check(lhs is Value.Reg)

        val loadAccess = normalizeLoadOrStore(locInst, regTypes)
        if (loadAccess.region != MemAccessRegion.STACK) {
            continue
        }
        val nextUse = getNextUseInterBlock(b, locInst.pos + 1, lhs)
        if (nextUse == null ||
            nextUse.label != b.getLabel() ||   // must be same block
            nextUse.pos == locInst.pos + 1) {  // skip consecutive
            continue
        }
        val storeInst = nextUse.inst
        if (storeInst !is SbfInstruction.Mem || storeInst.isLoad || storeInst.value != lhs) {
            continue
        }
        val storeAccess = normalizeLoadOrStore(nextUse, regTypes)
        if (storeAccess.region != MemAccessRegion.STACK) {
            continue
        }

        /**
         * At this point, we have a load from stack whose lhs is stored into the stack within the same block.
         * We also know that `lhs`, which is a register, is not overwritten in between (guaranteed by `getNextUseInterBlock`)
         */
        if (canLoadBeReordered(b, locInst, nextUse, loadInst)) {
            return locInst to nextUse
        }

    }
    return null
}

private fun canLoadBeReordered(
    b: SbfBasicBlock,
    from: LocatedSbfInstruction,
    to: LocatedSbfInstruction,
    loadInst: SbfInstruction.Mem
): Boolean
{
    return b.getLocatedInstructions().subList(from.pos+1, to.pos).none {
        val inst = it.inst
        // forbid if the base register is overwritten
        inst.writeRegister.contains(loadInst.access.baseReg) ||
            // forbid if any store occurs
            (inst is SbfInstruction.Mem && !inst.isLoad)
    }
}
