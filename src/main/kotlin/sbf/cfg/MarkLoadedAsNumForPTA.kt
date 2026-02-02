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
import sbf.disassembler.*
import datastructures.stdcollections.*
import sbf.SolanaConfig

fun markLoadedAsNumForPTA(cfg: MutableSbfCFG) {
    val liveness = LivenessAnalysis(cfg)
    markLoadedAsNumForPTA(cfg, liveness)
}

private fun markLoadedAsNumForPTA(cfg: MutableSbfCFG, liveness: LivenessAnalysis) {
    for (bb in cfg.getMutableBlocks().values) {
        // Remove annotations from previous runs
        bb.removeAnnotations(listOf(SbfMeta.LOADED_AS_NUM_FOR_PTA))

        if (!bb.getInstructions().any { inst ->  inst is SbfInstruction.Mem && inst.isLoad }) {
            continue
        }
        for (locInst in bb.getLocatedInstructions()) {
            val inst = locInst.inst
            if (inst is SbfInstruction.Mem && inst.isLoad) {
                val annotation = allUsesAreNumForPTA(locInst, cfg, liveness)
                if (annotation != null) {
                    addAnnotation(bb, locInst, annotation)
                }
            }
        }
    }
}

// Return non-null if all direct/indirect uses of `loadInst` will never be directly or indirectly de-referenced.
// If the uses might affect control dependencies then the value of the returned pair is true.
private fun allUsesAreNumForPTA(loadInst: LocatedSbfInstruction,
                                cfg: SbfCFG,
                                liveness: LivenessAnalysis): Pair<MetaKey<Boolean>, Boolean>? {
    val inst = loadInst.inst
    check(inst is SbfInstruction.Mem && inst.isLoad) {"allUsesAreNumForPTA expects a load instruction instead of $inst"}
    val bb = cfg.getBlock(loadInst.label)
    check (bb != null) {"allUsesAreNumForPTA cannot find block for ${loadInst.label}"}

    var loadedReg = inst.value as Value.Reg
    var nextPos = loadInst.pos + 1
    val loadedRegisters = mutableSetOf(loadedReg)
    // to break cycles
    val visitedInsts = mutableSetOf(loadInst)
    // getNextUseInterBlock can jump to a bb's descendant
    var curB: SbfBasicBlock = bb
    inner@while (true) {
        val nextUseLocInst = getNextUseInterBlock(curB, nextPos, loadedReg)
        if (nextUseLocInst == null || !visitedInsts.add(nextUseLocInst)) {
            return null
        }

        val nextUseBB = cfg.getBlock(nextUseLocInst.label)
        check(nextUseBB != null) {"markLoadedAsNumForPTA cannot find block for ${nextUseLocInst.label}"}
        curB = nextUseBB
        when (val nextUseInst = nextUseLocInst.inst) {
            is SbfInstruction.Call -> {
                if (nextUseInst.isDeallocFn() && !SolanaConfig.optimisticDealloc()) {
                    if (isDead(nextUseLocInst, loadedRegisters, liveness, false)) {
                        return Pair(SbfMeta.LOADED_AS_NUM_FOR_PTA, false)
                    }
                } else if (!(loadedReg.r >= SbfRegister.R6 && loadedReg.r <= SbfRegister.R9)) {
                    if (nextUseInst.isSaveScratchRegisters() || nextUseInst.isRestoreScratchRegisters()) {
                        nextPos = nextUseLocInst.pos + 1
                        continue@inner
                    }
                }
            }
            is SbfInstruction.Bin -> {
                // We can switch from the right-hand side to the left-hand side only if we know the right-hand side is not
                // used anymore. Otherwise, the right-hand side could be de-referenced without us noticing it.
                // This is a strong condition. We could separately check the uses of the right-hand side.
                val v = nextUseInst.v
                if (v is Value.Reg && v != nextUseInst.dst) {
                    if (!isDead(nextUseLocInst, setOf(v), liveness, false)) {
                        return null
                    }
                }

                loadedReg = nextUseInst.dst
                nextPos = nextUseLocInst.pos + 1
                loadedRegisters.add(loadedReg)

                continue@inner
            }
            is SbfInstruction.Assume -> {
                if (isDead(nextUseLocInst, loadedRegisters, liveness, false)) {
                    return Pair(SbfMeta.LOADED_AS_NUM_FOR_PTA, true)
                }
            }
            is SbfInstruction.Jump.ConditionalJump -> {
                // We need to find the lowered assume instructions
                val bbAtUse = cfg.getBlock(nextUseLocInst.label)
                check(bbAtUse != null) {"allUsesAsNumForPTA cannot find block for ${nextUseLocInst.label}"}
                if (bbAtUse.getSuccs().all { succ ->
                        val firstLocInst = succ.getLocatedInstructions().first()
                        // If first instruction is the lowered assume instruction then we check if loadedReg is dead **after**.
                        // Otherwise, we check before.
                        // Note that any CFG generated from an SBF file will have lowered assume instructions.
                        // However, we make here the code more general in case something change in the future.
                        if (firstLocInst.inst is SbfInstruction.Assume) {
                            isDead(firstLocInst, loadedRegisters, liveness, false)
                        } else {
                            isDead(firstLocInst, loadedRegisters, liveness, true)
                        }
                    }) {
                    return Pair(SbfMeta.LOADED_AS_NUM_FOR_PTA, true)
                }
            }
            else -> {}
        }
        return null
    }
}

private fun isDead(locInst: LocatedSbfInstruction,
                   registers: Set<Value.Reg>,
                   liveness: LivenessAnalysis,
                   checkBefore: Boolean): Boolean {
    val livenessAtInst = liveness.getLiveRegistersAtInst(locInst.label, before = checkBefore)
    val liveRegisters = livenessAtInst[locInst]
    if (liveRegisters != null) {
        if (liveRegisters.intersect(registers).isEmpty()) {
            return true
        }
    }
    return false
}

private fun addAnnotation(bb: MutableSbfBasicBlock, locInst: LocatedSbfInstruction, annotation: Pair<MetaKey<Boolean>, Boolean>) {
    val inst = locInst.inst
    if (inst is SbfInstruction.Mem && inst.isLoad) {
        val newMetaData = inst.metaData.plus(annotation)
        val newInst = inst.copy(metaData = newMetaData)
        bb.replaceInstruction(locInst.pos, newInst)
    }
}

