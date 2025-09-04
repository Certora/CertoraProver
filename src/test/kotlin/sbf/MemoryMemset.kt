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

package sbf

import sbf.callgraph.SolanaFunction
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.*

private val sbfTypesFac = ConstantSbfTypeFactory()
private val nodeAllocator = PTANodeAllocator { BasicPTANodeFlags() }

class MemoryMemsetTest {

    // Return node pointed by *([baseR] + [offset])
    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, Flags: IPTANodeFlags<Flags>>  getNode(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg, offset: Short, width: Short
    ): PTANode<Flags>? {
        val lhs = Value.Reg(SbfRegister.R7)
        check(base != lhs)
        val inst = SbfInstruction.Mem(Deref(width, base, offset, null), lhs, true, null)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doLoad(locInst, base, SbfType.top(), newGlobalVariableMap())
        val sc = g.getRegCell(lhs)
        return sc?.getNode()
    }

    // Return true if  *([baseR] + [offset]) points to [node]
    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, Flags: IPTANodeFlags<Flags>>  checkPointsToNode(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg, offset: Short,
        node: PTANode<Flags>
    ) = getNode(g, base, offset, 8)?.id == node.getNode().id

    @Test
    fun test01() {
        println("====== TEST 1: memset on stack and known length =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = MemoryDomain(nodeAllocator, sbfTypesFac, true)
        val stackC = absVal.getRegCell(r10, newGlobalVariableMap())
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        stackC.getNode().mkLink(4040, 8, n1.createCell(0))
        stackC.getNode().mkLink(4048, 8, n2.createCell(0))
        stackC.getNode().mkLink(4056, 8, n3.createCell(0))
        g.setRegCell(r1, stackC.getNode().createSymCell(4040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r2, ScalarValue(sbfTypesFac.toNum(0UL)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        val locInst = LocatedSbfInstruction(Label.Address(0), 0, SolanaFunction.toCallInst(SolanaFunction.SOL_MEMSET))
        sbfLogger.warn { "Before memset(r1,r2,24)\n$g" }
        g.doMemset(locInst, scalars, newGlobalVariableMap())
        sbfLogger.warn { "After\n$g" }

        Assertions.assertEquals(false, checkPointsToNode(g, r1, 0, n1))
        Assertions.assertEquals(false, checkPointsToNode(g, r1, 8, n2))
        Assertions.assertEquals(false, checkPointsToNode(g, r1, 16, n3))
    }

    @Test
    fun test02() {
        println("====== TEST 2: memset on stack and unknown length =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)

        // Create abstract state
        val absVal = MemoryDomain(nodeAllocator, sbfTypesFac,true)
        val stackC = absVal.getRegCell(r10, newGlobalVariableMap())
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        stackC.getNode().mkLink(4040, 8, n1.createCell(0))
        stackC.getNode().mkLink(4048, 8, n2.createCell(0))
        stackC.getNode().mkLink(4056, 8, n3.createCell(0))
        g.setRegCell(r1, stackC.getNode().createSymCell(4040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r2, ScalarValue(sbfTypesFac.toNum(0UL)))
        val locInst = LocatedSbfInstruction(Label.Address(0), 0, SolanaFunction.toCallInst(SolanaFunction.SOL_MEMSET))
        sbfLogger.warn { "Before memset(r1,r2,24)\n$g" }
        g.doMemset(locInst, scalars, newGlobalVariableMap())
        sbfLogger.warn { "After\n$g" }

        Assertions.assertEquals(true, checkPointsToNode(g, r1, 0, n1))
        Assertions.assertEquals(true, checkPointsToNode(g, r1, 8, n2))
        Assertions.assertEquals(true, checkPointsToNode(g, r1, 16, n3))
    }

    @Test
    fun test03() {
        println("====== TEST 2: memset on non-stack =======")
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = MemoryDomain(nodeAllocator, sbfTypesFac, true)
        val g = absVal.getPTAGraph()
        val heapNode  = g.mkNode()
        heapNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        heapNode.mkLink(0, 8, n1.createCell(0))
        heapNode.mkLink(8, 8, n2.createCell(0))
        heapNode.mkLink(16, 8, n3.createCell(0))
        g.setRegCell(r1, heapNode.createSymCell(0))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r2, ScalarValue(sbfTypesFac.toNum(0UL)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        val locInst = LocatedSbfInstruction(Label.Address(0), 0, SolanaFunction.toCallInst(SolanaFunction.SOL_MEMSET))
        sbfLogger.warn { "Before memset(r1,r2,24)\n$g" }
        g.doMemset(locInst, scalars, newGlobalVariableMap())
        sbfLogger.warn { "After\n$g" }

        Assertions.assertEquals(true, checkPointsToNode(g, r1, 0, n1))
        Assertions.assertEquals(true, checkPointsToNode(g, r1, 8, n2))
        Assertions.assertEquals(true, checkPointsToNode(g, r1, 16, n3))
    }

}
