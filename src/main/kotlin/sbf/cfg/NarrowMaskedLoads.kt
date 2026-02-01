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

/**
 * Narrow loads in [cfg] that satisfy [pred].
 **/
fun narrowMaskedLoads(
    cfg: MutableSbfCFG,
    pred: (SbfInstruction.Mem) -> Boolean
) {
    for (b in cfg.getMutableBlocks().values) {
        narrowMaskedLoads(b, cfg, pred)
    }
}

/**
 * Narrow loads in [b] that satisfy [pred].
 *
 * The code is inefficient because after each narrow happens all located instructions after the narrowed load
 * are invalidated since their position in [b] changed.
 *
 * precondition: [b] belongs to [cfg]
 **/
fun narrowMaskedLoads(
    b: MutableSbfBasicBlock,
    cfg: MutableSbfCFG,
    pred: (SbfInstruction.Mem) -> Boolean
) {
    var change = true
    // The termination argument is that after the inner loop finishes either `change` is false and therefore,
    // it exists the while loop, or `change` is true and a new iteration of the inner loop will happen
    // but with one less load since `narrowMaskedLoad` removed a load.
    while (change) {
        change = false
        for (locInst in b.getLocatedInstructions()) {
            val inst = locInst.inst
            if (inst is SbfInstruction.Mem && inst.isLoad && pred(inst)) {
                if (narrowMaskedLoad(cfg, locInst)) {
                    // the load has been removed
                    change = true
                    break
                }
            }
        }
    }
}

/**
 * Optimizes 8-byte loads followed by a `0xFF`, `0xFFFF`, or `0xFFFF_FFFF` mask by narrowing the load width
 * to 1, 2, or 4 bytes, respectively.
 *
 * For instance, replace this pattern
 * ```
 * r1 = *(u64*) (r10 + -1592)
 * r1 := r1 and 255
 * ```
 * with
 * ```
 * r1 = *(u8*) (r10 + -1592)
 * ```
 */
private fun narrowMaskedLoad(cfg: MutableSbfCFG, loadLocInst: LocatedSbfInstruction): Boolean {
    val loadInst = loadLocInst.inst
    check(loadInst is SbfInstruction.Mem && loadInst.isLoad)

    val lhs = loadInst.value as Value.Reg

    // Only optimize 8-byte loads
    if (loadInst.access.width.toInt() != 8) {
        return false
    }

    val block = cfg.getMutableBlock(loadLocInst.label)
    check(block != null)

    // Find the single use of the destination register after the load
    val use = getNextUseInterBlock(block, loadLocInst.pos+1, lhs)
        ?: return false

    // Check if the use is a mask with 0xFF, 0xFFFF, or 0xFFFF_FFFF
    val narrowWidth: Short = when {
        isMaskWith0xFF(use.inst, lhs) -> 1
        isMaskWith0xFFFF(use.inst, lhs) -> 2
        isMaskWith0xFFFFFFFF(use.inst, lhs) -> 4
        else -> return false
    }

    // Replace load with narrowed version
    val narrowedLoad = loadInst.copy(access = loadInst.access.copy(width = narrowWidth))
    block.replaceInstruction(loadLocInst.pos, narrowedLoad)

    // Remove the now-redundant mask instruction
    val useBlock = cfg.getMutableBlock(use.label)
    check(useBlock != null)
    useBlock.removeAt(use.pos)

    return true
}


private fun isMaskWithImmediate(inst: SbfInstruction, reg: Value.Reg, mask: ULong): Boolean {
    if (inst !is SbfInstruction.Bin || inst.op != BinOp.AND || inst.dst != reg) {
        return false
    }
    val imm = inst.v as? Value.Imm ?: return false
    return imm.v == mask
}

private fun isMaskWith0xFF(inst: SbfInstruction, reg: Value.Reg) =
    isMaskWithImmediate(inst, reg, mask = 0xFFUL)

private fun isMaskWith0xFFFF(inst: SbfInstruction, reg: Value.Reg) =
    isMaskWithImmediate(inst, reg, mask = 0xFFFFUL)

private fun isMaskWith0xFFFFFFFF(inst: SbfInstruction, reg: Value.Reg) =
    isMaskWithImmediate(inst, reg, mask = 0xFFFFFFFFUL)
