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
 * Remove pairs of [UnOp.BE64] byte swap instructions that are redundant in the context of an
 * equality comparison.
 *
 * Example: transforms
 * ```
 * r1 = ...
 * r1 = be64(r1)
 * ...
 * r2 = be64(r2)
 * ...
 * if (r1 == r2) goto L1 else goto L2
 * ```
 * into
 * ```
 * r1 = ...
 * ...
 * r2 = ...
 * if (r1 == r2) goto L1 else goto L2
 * ```
 */
fun simplifyByteSwapInsts(cfg: MutableSbfCFG) {
    for (b in cfg.getMutableBlocks().values) {
        simplifyByteSwapInsts(b)
    }
}

private fun simplifyByteSwapInsts(b: MutableSbfBasicBlock) {
    val terminator = b.getTerminator()
    if (terminator !is SbfInstruction.Jump.ConditionalJump) {
        return
    }

    val cond = terminator.cond
    if (cond.op != CondOp.EQ && cond.op != CondOp.NE) {
        return
    }

    val leftReg = cond.left
    val rightReg = cond.right as? Value.Reg ?: return

    val terminatorPos = b.numOfInstructions() - 1

    // Find the most recent intra-block definition of each register before the terminator
    val leftDef = findDefinitionInterBlock(b, leftReg, terminatorPos, maxNumLevelsUp = 1)
        ?.takeIf { it.label == b.getLabel() } ?: return
    val rightDef = findDefinitionInterBlock(b, rightReg, terminatorPos, maxNumLevelsUp = 1)
        ?.takeIf { it.label == b.getLabel() } ?: return

    // Both must be BE64 operations
    if (leftDef.inst !is SbfInstruction.Un || leftDef.inst.op != UnOp.BE64) {
        return
    }
    if (rightDef.inst !is SbfInstruction.Un || rightDef.inst.op != UnOp.BE64) {
        return
    }

    // Neither register may be used between its byte swap definition and the comparison
    if (isUsed(b, leftReg, leftDef.pos, terminatorPos)) {
        return
    }
    if (isUsed(b, rightReg, rightDef.pos, terminatorPos)) {
        return
    }

    // Remove both byte swap instructions; remove the one at the higher position first
    // so that removing it does not invalidate the lower position
    val highPos = maxOf(leftDef.pos, rightDef.pos)
    val lowPos = minOf(leftDef.pos, rightDef.pos)
    b.removeAt(highPos)
    b.removeAt(lowPos)
}
