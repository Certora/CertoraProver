/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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
import sbf.callgraph.SbfCallGraph
import sbf.disassembler.SbfRegister

/**
 * Debug-only CFG transformation: insert `assert(reg == 42); assert(0 == 1)` immediately before [locInst].
 *
 * Intended for debugging PTA errors. The first assertion constrains [reg] so that any counterexample
 * reported at the second assertion describes a program state in which [reg] is 42; the second
 * assertion is unconditionally false so the verifier always produces a counterexample at [locInst].
 *
 * The `0 == 1` assertion is encoded by first zeroing R0 and then asserting `R0 == 1`. R0 is used by
 * convention (see `assertFalse` in `ReplaceAbortWithError.kt`).
 */
fun insertDebugAsserts(cfg: MutableSbfCFG, locInst: LocatedSbfInstruction, reg: Value.Reg) {
    val block = checkNotNull(cfg.getMutableBlock(locInst.label))
    val pos = locInst.pos
    check(pos in 0 until block.numOfInstructions()) {
        "insertDebugAsserts: pos $pos out of bounds for block ${locInst.label} " +
            "(size = ${block.numOfInstructions()})"
    }
    check(block.getInstruction(pos) == locInst.inst) {
        "insertDebugAsserts: located instruction does not match block contents at $locInst"
    }

    val r0 = Value.Reg(SbfRegister.R0)
    val debugAsserts = listOf(
        SbfInstruction.Assert(
            Condition(CondOp.EQ, reg, Value.Imm(42UL)),
            MetaData(SbfMeta.COMMENT to "debug PTA: $reg == 42")
        ),
        SbfInstruction.Bin(BinOp.MOV, r0, Value.Imm(0UL), is64 = true),
        SbfInstruction.Assert(
            Condition(CondOp.EQ, r0, Value.Imm(1UL)),
            MetaData(SbfMeta.COMMENT to "debug PTA: ${locInst.inst}")
        )
    )
    block.addAll(pos, debugAsserts)
}

/**
 * Same as [insertDebugAsserts] but lifts the transformation to an [SbfCallGraph]:
 * applies it to the single entry CFG (the call graph root) and returns a new call graph.
 *
 * [locInst] must refer to an instruction in the entry CFG.
 */
fun insertDebugAsserts(prog: SbfCallGraph, locInst: LocatedSbfInstruction, reg: Value.Reg): SbfCallGraph {
    return prog.transformSingleEntry { entryCFG ->
        val mutableEntry = entryCFG.clone(entryCFG.getName())
        insertDebugAsserts(mutableEntry, locInst, reg)
        mutableEntry
    }
}
