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

import sbf.callgraph.CVTCalltrace

/**
 * Move instructions that print calltrace information into its predecessors.
 * This helps the scalar analysis to know statically which string should be printed.
 **/
fun unhoistCalltraceFunctions(cfg: MutableSbfCFG, numInstsBeforeFirstCalltraceFn: Int = 5) {
    val worklist = arrayListOf<Pair<MutableSbfBasicBlock, Int>>()
    for (b in cfg.getMutableBlocks().values) {
        if (b.getPreds().size > 1) {
            for (locInst in b.getLocatedInstructions()) {
                val i = locInst.pos
                val inst = locInst.inst
                if (i >= numInstsBeforeFirstCalltraceFn) {
                    break
                }
                if (inst is SbfInstruction.Call) {
                    val calltraceFn = CVTCalltrace.from(inst.name)
                    if (calltraceFn != null) {
                        when (calltraceFn) {
                            CVTCalltrace.PRINT_U64_1, CVTCalltrace.PRINT_U64_2, CVTCalltrace.PRINT_U64_3,
                            CVTCalltrace.PRINT_U128, CVTCalltrace.PRINT_I128,
                            CVTCalltrace.PRINT_I64_1, CVTCalltrace.PRINT_I64_2, CVTCalltrace.PRINT_I64_3,
                            CVTCalltrace.PRINT_U64_AS_FIXED,
                            CVTCalltrace.PRINT_TAG,
                            CVTCalltrace.PRINT_LOCATION,
                            CVTCalltrace.PRINT_STRING -> {
                                // IMPORTANT: if one of the read registers of the calltrace function is not defined in the current block
                                // then we unhoist the call
                                val readRegs = calltraceFn.function.readRegisters
                                if (readRegs.any { reg ->
                                        val defLocInst = findDefinitionInterBlock(b, reg, i)
                                        defLocInst?.label != b.getLabel()
                                    }) {
                                    worklist.add(b to i+1)
                                    // We only unhoist the first calltrace function within the block
                                    break
                                }
                            }
                            CVTCalltrace.ATTACH_LOCATION, CVTCalltrace.SCOPE_START, CVTCalltrace.SCOPE_END, CVTCalltrace.RULE_LOCATION -> {}
                        }
                    }
                }
            }
        }
    }

    // Do the actual unhoisting
    while (worklist.isNotEmpty()) {
        val (b, i) = worklist.removeLast()
        b.foldIntoPredecessors(i)
    }
}
