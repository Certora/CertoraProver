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

import sbf.disassembler.*

/**
 *  Unhoist store and loads instructions into its immediate predecessors.
 *  This helps the pointer analysis because it can avoid the analysis loses provenance of pointers after a join.
 *  Moreover, this transformation also helps another transformation SplitWideStores.
 */
fun unhoistStoresAndLoads(cfg: MutableSbfCFG, globals: GlobalVariables, maxNumOfUnhoistedInsts: Int = 10) {
    // The first maxNumOfUnhoistedInsts instructions in basic block b can be unhoisted to each b's predecessor
    val worklist = arrayListOf<Pair<MutableSbfBasicBlock, Int>>()
    for (b in cfg.getMutableBlocks().values) {
        if (b.getPreds().size > 1) {
            for (locInst in b.getLocatedInstructions()) {
                val i = locInst.pos
                val inst = locInst.inst
                if (i >= maxNumOfUnhoistedInsts) {
                    break
                }
                if (inst.isSaveScratchRegisters() || inst.isRestoreScratchRegisters()) {
                    // we don't want to unhoist these instructions, so we bail out
                    break
                }
                if (inst is SbfInstruction.Mem) {
                    if (!inst.isLoad) {
                        if (/* Add here new patterns */
                            isStoreValOfAssignsOrLoads(inst, b, i) ||
                            isStoreDerefOfLoads(inst, b, i) ||
                            isDerefToGlobalVariable(inst, b, i, globals)
                        ) {
                            addAnnotation(b, locInst, SbfMeta.UNHOISTED_STORE())
                            worklist.add(Pair(b, i + 1))
                            // Important: we unhoist at most one instruction per block. We could unhoist more, but then we need
                            // to be careful when we call foldIntoPredecessors.
                            break
                        }
                    } else {
                        if (/* Add here new patterns */
                            isDerefToGlobalVariable(inst, b, i, globals)
                        ) {
                            addAnnotation(b, locInst, SbfMeta.UNHOISTED_LOAD())
                            worklist.add(Pair(b, i+1))
                            // Important: we unhoist at most one instruction per block. We could unhoist more, but then we need
                            // to be careful when we call foldIntoPredecessors.
                            break
                        }
                    }
                }
            }
        }
    }

    // Do the actual unhoisting
    while (worklist.isNotEmpty()) {
        val (b, n) = worklist.removeLast()
        b.foldIntoPredecessors(n)
    }
}

/** [storeInst] is the [idx]-th instruction in [bb]. Return true if the following pattern:
 *  pred1:
 *       r1:= 5
 *       ...
 *       goto b
 *  pred2:
 *       r1:= 6
 *       ...
 *       goto b
 *  b:
 *       ...
 *       *r2 := r1
 *       continuation
 **/
private fun isStoreValOfAssignsOrLoads(storeInst: SbfInstruction.Mem, bb: SbfBasicBlock, idx: Int): Boolean {
    val storedVal = storeInst.value
    if (storedVal is Value.Reg) {
        val storedValRoot = findRoot(storedVal, bb.getInstructions(), 0, idx)
        if (storedValRoot != null) {
            val predsBB = bb.getPreds()
            if (predsBB.size > 1 &&  predsBB.any { predB ->
                    val defIdx = findDefinition(predB, storedValRoot)
                    if (defIdx == -1 ) {
                        false // no definition
                    } else {
                        val defInst = predB.getInstruction(defIdx)
                        (matchBinaryOpLhs(defInst, storedValRoot) || matchLoadLhs(defInst, storedValRoot))
                    }
                }) {
                return true
            }
        }
    }
    return false

}

/** [storeInst] is the [idx]-th instruction in [bb]. Return true if the following pattern:
 *
 *  pred1:
 *       r2  := *(rX + o)
 *       goto bb
 *  pred2:
 *       r2: := *(rX + o)
 *       goto bb
 *  bb:
 *       *r2 := ...
 **/
private fun isStoreDerefOfLoads(storeInst: SbfInstruction.Mem, bb: SbfBasicBlock, idx: Int): Boolean {
    val baseReg = storeInst.access.base
    val baseRegRoot = findRoot(baseReg, bb.getInstructions(), 0, idx)
    if (baseRegRoot != null) {
        val predsBB = bb.getPreds()
        if (predsBB.size > 1 && predsBB.any { predB ->
                val defIdx = findDefinition(predB, baseRegRoot)
                if (defIdx == -1) {
                    false // no definition
                } else {
                    val defInst = predB.getInstruction(defIdx)
                    defInst is SbfInstruction.Mem && defInst.isLoad
                }
            }) {
            return true
        }
    }
    return false
}


/** [memInst] is the [idx]-th instruction in [bb]. Return true if the following pattern:
 *
 *  pred1:
 *       r2  := 516056 // identified as a global variable
 *       goto bb
 *  pred2:
 *       r2: := 516072 // identified as a global variable
 *       goto bb
 *  bb:
 *       ....
 *       *r2 := ...
 **/
private fun isDerefToGlobalVariable(memInst: SbfInstruction.Mem,
                                    bb: SbfBasicBlock,
                                    idx: Int,
                                    globals: GlobalVariables): Boolean {
    val baseReg = memInst.access.base
    val baseRegRoot = findRoot(baseReg, bb.getInstructions(), 0, idx)
    if (baseRegRoot != null) {
        val predsBB = bb.getPreds()
        if ((predsBB.size > 1) && predsBB.any { predB ->
                val defLocInst = findDefinitionInterBlock(predB, baseRegRoot)
                if (defLocInst == null) {
                    false // no definition
                } else {
                    val defInst = defLocInst.inst
                    if (defInst is SbfInstruction.Bin &&
                        defInst.op == BinOp.MOV &&
                        defInst.v is Value.Imm
                    ) {
                        val globalAddress = (defInst.v as Value.Imm).v
                        if (globalAddress <= Long.MAX_VALUE.toULong()) {
                            globals.contains(globalAddress.toLong())
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            }) {
            return true
        }
    }
    return false
}


/**
 * Return a register that is not defined in the slice ([from],[to]) but unequivocally determines the value of [reg].
 * This is similar to traverse transitively the def-use chains of [reg] until no more definitions
 *
 * If we have code like this
 *
 * ```
 *   bb:
 *    ... // r1 is not defined
 *    r2 := r1
 *    r3 := r2 + 1
 * i: r4 := r3
 * ```
 *
 * and [reg]`=r4` and [from]`=0` and [to]`=i` then it returns `r1`.
 **/
private fun findRoot(reg: Value.Reg, insts: List<SbfInstruction>, from: Int, to: Int): Value.Reg? {
    var root = reg
    for (inst in insts.subList(from, to).asReversed()) {
        if (inst is SbfInstruction.Bin) {
            val lhs = inst.dst
            val rhs = inst.v
            when (inst.op) {
                BinOp.MOV -> {
                    if (lhs == root && rhs is Value.Reg) {
                        // change the root
                        root = rhs
                        continue
                    }
                }
                else -> {
                    if (lhs == root && rhs is Value.Imm) {
                        // keep the same root
                        continue
                    }
                }
            }
        }
        if (inst.writeRegister.contains(root)) { // root is being defined
            return null
        }
    }
    return root
}

/** [lhs] is the destination register of binary instruction [inst] **/
private fun matchBinaryOpLhs(inst: SbfInstruction, lhs: Value): Boolean {
    return if (lhs !is Value.Reg) {
        false
    } else {
        (inst is SbfInstruction.Bin && inst.dst == lhs)
    }
}

/** [lhs] is the destination register of memory load [inst] **/
private fun matchLoadLhs(inst: SbfInstruction, lhs: Value): Boolean {
    return if (lhs !is Value.Reg) {
        false
    } else {
        (inst is SbfInstruction.Mem && inst.isLoad && inst.value == lhs)
    }
}

private fun<T> addAnnotation(bb: MutableSbfBasicBlock, locInst: LocatedSbfInstruction, annotation: Pair<MetaKey<T>, T>) {
    val inst = locInst.inst
    if (inst is SbfInstruction.Mem) {
        val newMetaData = inst.metaData.plus(annotation)
        val newInst = inst.copy(metaData = newMetaData)
        bb.replaceInstruction(locInst.pos, newInst)
    }
}
