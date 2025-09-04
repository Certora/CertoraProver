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

import sbf.analysis.NPAnalysis
import datastructures.stdcollections.*

/**
 * Remove select instructions
 */
fun lowerSelectToAssume(cfg: MutableSbfCFG, npAnalysis: NPAnalysis) {
    for (block in cfg.getMutableBlocks().values) {
        val selectInsts = block.getLocatedInstructions().filter {it.inst is SbfInstruction.Select }
        if (selectInsts.isEmpty()) {
            continue
        }
        replaceSelectWithAssign(block, selectInsts, npAnalysis)
        replaceSelectWithAssignAndAssume(block, selectInsts, npAnalysis)
    }
}

/**
 *  Replace
 *  ```
 *     dst := select(cond, trueVal, falseVal)
 *  ```
 *
 *  with:
 *
 *  ```
 *     dst := trueVal
 *     assume(cond)
 *  ```
 *  if we can infer that `dst == trueVal` is a necessary precondition to reach all the assert's, or with:
 *
 *  ```
 *     dst := falseVal
 *     assume(!cond)
 *  ```
 *  if we can infer that `dst == falseVal` otherwise
 *
 *  **Post-condition**: [npAnalysis] should not be used for [block] after this transformation because
 *  the transformed [block] and [npAnalysis] will be out-of-sync once the transformation is done.
 */
private fun replaceSelectWithAssignAndAssume(
    block: MutableSbfBasicBlock,
    selectInsts: List<LocatedSbfInstruction>,
    npAnalysis: NPAnalysis
) {

    val npAtInst = npAnalysis.populatePreconditionsAtInstruction(block.getLabel())
    // In this loop we cannot modify the block because we are accessing to npAnalysis
    val replacer = mutableMapOf<LocatedSbfInstruction, Pair<SbfInstruction.Assume, SbfInstruction.Bin?>>()

    for (locSelectInst in selectInsts) {
        val select = locSelectInst.inst
        check(select is SbfInstruction.Select)
        val np = npAtInst[locSelectInst] ?: continue
        if (np.isBottom()) {
            continue
        }
        val trueCst = Condition(CondOp.EQ, select.dst, select.trueVal)
        val falseCst = Condition(CondOp.EQ, select.dst, select.falseVal)
        val newMetadata = select.metaData.plus(SbfMeta.LOWERED_SELECT to "")

        val (newAssumeInst, newAssignInst) =
            when {
                // case 1: the true value always holds after select
                npAnalysis.contains(np, trueCst) && npAnalysis.isBottom(np, locSelectInst, falseCst) -> {
                    SbfInstruction.Assume(select.cond, newMetadata) to
                        SbfInstruction.Bin(BinOp.MOV, select.dst, select.trueVal, is64 = true, metaData = newMetadata)
                }
                // case 2: the false value always holds after select
                npAnalysis.contains(np, falseCst) && npAnalysis.isBottom(np, locSelectInst, trueCst) -> {
                    SbfInstruction.Assume(select.cond.negate(), newMetadata) to
                        SbfInstruction.Bin(BinOp.MOV, select.dst, select.falseVal, is64 = true, metaData = newMetadata)
                }
                // case 3: lhs appears on the rhs -> r7 := select(cond, r7, 0)
                select.dst == select.trueVal && npAnalysis.isBottom(np, locSelectInst, falseCst, useForwardInvariants = false)-> {
                    SbfInstruction.Assume(select.cond, newMetadata) to null
                }
                // case 4: lhs appears on the rhs -> r7 := select(cond, 0, r7)
                select.dst == select.falseVal && npAnalysis.isBottom(np, locSelectInst, trueCst, useForwardInvariants = false) -> {
                    SbfInstruction.Assume(select.cond.negate(), newMetadata) to null
                }
                else -> {
                    null to null
                }
            }

        if (newAssumeInst != null) {
            replacer[locSelectInst] = newAssumeInst to newAssignInst
        }
    }

    // ---- Do the actual transformation ----
    var numAdded = 0 // Used to adjust indices after a new assignment is inserted
    for ((locSelectInst, newInsts) in replacer) {
        val (newAssumeInst, newAssignInst) = newInsts
        // Replace original select with assume
        block.replaceInstruction(locSelectInst.pos + numAdded, newAssumeInst)

        // Insert assignment **after** the assume (for soundness)
        if (newAssignInst != null) {
            block.add(locSelectInst.pos + numAdded + 1, newAssignInst)
            numAdded++
        }
    }
}

/**
 *  Replace
 *  ```
 *     dst := select(cond, trueVal, falseVal)
 *  ```
 *
 *  with:
 *
 *  ```
 *     dst := trueVal
 *  ```
 *
 *  If `cond` is always true, or with:
 *
 *  ```
 *     dst := falseVal
 *  ```
 *  If `cond` is always false.
 *
 *  **Post-condition**: The transformation preserves the validity of [npAnalysis].
 */
private fun replaceSelectWithAssign(
    block: MutableSbfBasicBlock,
    selectInsts: List<LocatedSbfInstruction>,
    npAnalysis: NPAnalysis
) {
    val npAtInst = npAnalysis.populatePreconditionsAtInstruction(block.getLabel())
    for (locSelectInst in selectInsts) {
        val select = locSelectInst.inst
        check(select is SbfInstruction.Select)
        val np = npAtInst[locSelectInst] ?: continue
        if (np.isBottom()) {
            continue
        }

        val newMetadata = select.metaData.plus(SbfMeta.LOWERED_SELECT to "")
        val newAssignInst = when {
            // cond is always false -> dst := falseVal
            npAnalysis.isBottom(np, locSelectInst, select.cond) ->
                SbfInstruction.Bin(BinOp.MOV, select.dst, select.falseVal, is64 = true, metaData = newMetadata)

            // cond is always true -> dst := trueVal
            npAnalysis.isBottom(np, locSelectInst, select.cond.negate()) ->
                SbfInstruction.Bin(BinOp.MOV, select.dst, select.trueVal, is64 = true, metaData = newMetadata)

            else -> null
        }

        // -- Do the actual replacement --
        if (newAssignInst != null) {
            block.replaceInstruction(locSelectInst.pos, newAssignInst)
        }
    }
}
