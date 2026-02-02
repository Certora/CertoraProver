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

import sbf.analysis.LivenessAnalysis
import sbf.disassembler.SbfRegister
import datastructures.stdcollections.*

/** Remove useless definitions **/
fun removeUselessDefinitions(cfg: MutableSbfCFG) {
    val liveness = LivenessAnalysis(cfg)
    removeUselessDefinitions(cfg, liveness)
}

fun removeUselessDefinitions(cfg: MutableSbfCFG, liveness: LivenessAnalysis) {
    for ((label, bb) in cfg.getMutableBlocks()) {
        val livenessAfterInst = liveness.getLiveRegistersAtInst(label, before = false)
        val newInsts = mutableListOf<SbfInstruction>()
        var change = false
        for (locInst in bb.getLocatedInstructions()) {
            val inst = locInst.inst
            if ((inst is SbfInstruction.Mem && inst.isLoad) ||
                (inst is SbfInstruction.Bin && inst.dst != Value.Reg(SbfRegister.R10_STACK_POINTER))) {
                val liveRegisters = livenessAfterInst[locInst]
                if (liveRegisters != null) {
                    if (inst.writeRegister.intersect(liveRegisters).isEmpty()) {
                        change = true
                        continue
                    }
                }
            }
            newInsts.add(inst)
        }
        if (change) {
            bb.replaceInstructions(newInsts)
        }
    }
}
