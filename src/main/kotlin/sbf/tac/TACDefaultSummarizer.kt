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


package sbf.tac

import sbf.SolanaConfig
import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.SbfInstruction
import sbf.disassembler.SbfRegister
import sbf.domains.*
import sbf.sbfLogger
import vc.data.TACCmd

/** Default summary for an external call **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeCall(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call) { "summarizeCall expects only call instructions" }

    val summaryArgs = mem.getTACMemoryFromSummary(locInst) ?: datastructures.stdcollections.listOf()

    val cmds = mutableListOf(Debug.externalCall(inst))
    if (summaryArgs.isNotEmpty()) {
        for ((i, arg) in summaryArgs.withIndex()) {
            val (tacV, useAssume) =  when (val v = arg.variable) {
                is TACByteStackVariable -> {
                    Pair(v.tacVar, false)
                }
                is TACByteMapVariable -> {
                    val lhs = vFac.mkFreshIntVar()
                    val loc = computeTACMapIndex(exprBuilder.mkVar(arg.reg), arg.offset, cmds)
                    cmds.add(TACCmd.Simple.AssigningCmd.ByteLoad(lhs, loc, v.tacVar))
                    Pair(lhs, true)
                }
            }

            when (arg.type) {
                MemSummaryArgumentType.PTR_HEAP -> {
                    val allocatedSize = if (arg.allocatedSpace > 0UL) {
                        arg.allocatedSpace
                    } else {
                        val defaultSize = SolanaConfig.TACHeapAllocSize.get().toULong()
                        sbfLogger.warn { "TAC allocation of unknown size: fixing $defaultSize bytes for $i-th parameter at $locInst" }
                        defaultSize
                    }
                    // let's assume this summary for foo
                    //  ```
                    //  #[type((*i64)(r1+0):ptr_external(1024))]
                    //  #[type((*i64)(r1+8):ptr_external(1024))]
                    //
                    //   r1 = r10[-200]
                    //   "foo"()
                    //   r2 = r1[0]
                    //   r3 = r1[8]
                    //  ```
                    //  The call to `foo` will add some TAC like this
                    //  ```
                    //   let x := ByteLoad(M, r1)
                    //   let y := ByteLoad(M, r1+8)
                    //   x := some fixed address
                    //   y := x + 1024
                    //  ```
                    //  As a result `r2 = r1[0]` won't know that `r2` should be x.
                    //  If the 3rd parameter of `alloc` (see below) is true then the TAC will be like this
                    //   ```
                    //   let x = ByteLoad(M, r1)
                    //   let y = ByteLoad(M, r1+8)
                    //   assume(x == some fixed address) // this propagates back to M
                    //   assume(y == x + 1024)           // this propagates back to M
                    //   ```
                    cmds.addAll(heapMemAlloc.alloc(tacV, allocatedSize, useAssume))
                }
                MemSummaryArgumentType.PTR_EXTERNAL -> {
                    val allocatedSize = if (arg.allocatedSpace > 0UL) {
                        arg.allocatedSpace
                    } else {
                        val defaultSize = SolanaConfig.TACExternalAllocSize.get().toULong()
                        sbfLogger.warn { "TAC allocation of unknown size: fixing $defaultSize bytes for $i-th parameter at $locInst" }
                        defaultSize
                    }
                    cmds.addAll(extMemAlloc.alloc(tacV, allocatedSize, useAssume))
                }
                else -> {
                    cmds.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(tacV))
                }
            }

        }
    }
    cmds.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)))
    if (memoryAnalysis?.memSummaries?.getSummary(inst.name) == null) {
        unsupportedCalls.add(inst.name)
    }
    return cmds
}
