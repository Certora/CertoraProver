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

import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.support.UnknownStackContentError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.*
import sbf.callgraph.SolanaFunction
import sbf.support.UnknownMemcpyLenError

private val sbfTypesFac = ConstantSbfTypeFactory()
private val nodeAllocator = PTANodeAllocator { BasicPTANodeFlags() }
private val globals = GlobalVariables(DefaultElfFileView)

class MemoryMemcpyTest {

    /**
     * Return cell pointed by *([base] + [offset]).
     * Use `r7` as intermediate register.
     */
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags : IPTANodeFlags<Flags>> load(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg,
        offset: Short,
        width: Short
    ): PTASymCell<Flags>? {
        val lhs = Value.Reg(SbfRegister.R7)
        check(base != lhs)
        val inst = SbfInstruction.Mem(Deref(width, base, offset), lhs, true)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doLoad(locInst, base, SbfType.top(), globals)
        return g.getRegCell(lhs)
    }

    /**
     * Store [value] in *([base] + [offset]).
     * Use `r7` as intermediate register.
     */
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags : IPTANodeFlags<Flags>>  store(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg,
        offset: Short,
        width: Short,
        value: Value = Value.Reg(SbfRegister.R7)
    ) {
        val inst = SbfInstruction.Mem(Deref(width, base, offset), value, false)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doStore(locInst, base, value, baseType = SbfType.top(), valueType = SbfType.top(), globals)
    }

    /**
     * [base] = [base] + [offset]
     */
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags : IPTANodeFlags<Flags>>  gep(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg,
        offset: Long
    ) {
        val inst = SbfInstruction.Bin(BinOp.ADD, base, Value.Imm(offset.toULong()), true)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doBin(locInst, BinOp.ADD, base, Value.Imm(offset.toULong()), SbfType.top(), SbfType.top(), globals)
    }

    // Check that *([baseR] + [offset]) points to [node]
    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, Flags: IPTANodeFlags<Flags>> checkPointsToNode(g: PTAGraph<TNum, TOffset, Flags>,
                                  base: Value.Reg, offset: Short, width: Short,
                                  node: PTANode<Flags>) {
        Assertions.assertEquals(true, load(g, base, offset, width)?.getNode()?.id == node.getNode().id)
    }

    private fun createMemcpy() = LocatedSbfInstruction(Label.fresh(),0, SbfInstruction.Call(SolanaFunction.SOL_MEMCPY.syscall.name))
    //private fun createMemset() = LocatedSbfInstruction(Label.fresh(),0, SbfInstruction.Call(SolanaFunction.SOL_MEMSET.syscall.name))
    private fun createMemoryDomain() =
        MemoryDomain(nodeAllocator, sbfTypesFac, MemoryDomainOpts(false),true)

    @Test
    fun test01() {
        println("====== TEST 1: memcpy from stack to uninitialized stack  (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
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
        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 16, 8, n3)
    }

    @Test
    fun test02() {
        println( "====== TEST 2: memcpy from (exact) non-stack to uninitialized stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        stackC.getNode().mkLink(4040, 8, srcNode.createCell(0))
	    srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(8, 8, n2.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 16, 8, n3)
    }

    @Test
    fun test03() {
        println( "====== TEST 3: memcpy from (exact) non-stack to (exact) uninitialized non-stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val dstN = g.mkNode()
        stackC.getNode().mkLink(4040, 8, srcNode.createCell(0))
	    srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(8, 8, n2.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, dstN.createSymCell(0))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        Assertions.assertEquals(true, !dstN.isUnaccessed())
        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 16, 8, n3)
    }


    @Test
    fun test04() {
        println( "====== TEST 4: memcpy from stack to initialized stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(3056, 8, n6.createCell(0))

        stackC.getNode().mkLink(4040, 8, n1.createCell(0))
        stackC.getNode().mkLink(4048, 8, n2.createCell(0))
        stackC.getNode().mkLink(4056, 8, n3.createCell(0))
        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)

        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 16, 8, n3)
    }

    @Test
    fun test05() {
        println( "====== TEST 5: memcpy from (exact) non-stack to initialized stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
	    val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(3056, 8, n6.createCell(0))

        stackC.getNode().mkLink(4040, 8, srcNode.createCell(0))
	    srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(8, 8, n2.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 16, 8, n3)
    }


    @Test
    fun test06() {
        println( "====== TEST 6: memcpy from (exact) non-stack to (exact) initialized non-stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val dstN = g.mkNode()

        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        dstN.mkLink(0, 8, n4.createCell(0))
        dstN.mkLink(8, 8, n5.createCell(0))
        dstN.mkLink(16, 8, n6.createCell(0))

        stackC.getNode().mkLink(4040, 8, srcNode.createCell(0))
	    srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(8, 8, n2.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, dstN.createSymCell(0))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        Assertions.assertEquals(true, !n4.getNode().isUnaccessed())
        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 16, 8, n3)
    }

    @Test
    fun test07() {
        println( "====== TEST 7: memcpy from (exact) non-stack to (exact) initialized non-stack (unknown length) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val dstN = g.mkNode()

        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        dstN.mkLink(0,  8, n4.createCell(0))
        dstN.mkLink(8, 8, n5.createCell(0))
        dstN.mkLink(16, 8, n6.createCell(0))

        stackC.getNode().mkLink(4040, 8, srcNode.createCell(0))
	    srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(8, 8, n2.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, dstN.createSymCell(0))

        val scalars = ScalarDomain(sbfTypesFac)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.anyNum()))
        // memcpy(r1, r2, r3)
        println("Before memcpy(r1,r2,r3) with r3=top -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,r3) with r3=top -> $g")

        // It should unify the nodes pointed by src with those pointed by dst.
        Assertions.assertEquals(true, g.getRegCell(r1) == g.getRegCell(r2))
    }

    @Test
    fun test08() {
        println( "====== TEST 8: memcpy from (exact) non-stack to initialized stack (unknown length) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
	    val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(3056, 8, n6.createCell(0))

        stackC.getNode().mkLink(4040, 8, srcNode.createCell(0))
	    srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(8, 8, n2.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)

        expectException<UnknownMemcpyLenError> {
            // memcpy(r1, r2, r3)
            println("Before memcpy(r1,r2,r3) with r3=top -> $g")
            g.doMemcpy(createMemcpy(), scalars, globals)
        }
    }

    @Test
    fun test09() {
        println( "====== TEST 9: memcpy from summarized to stack =======")
        /**
         * ```
         * dst = [(3030,8) -> (n4,0), (3040,8) -> (n4,0),  (3048,8) -> (n5,0), (3056,8) -> (n6,0)]
         * src = [(0,8) -> (n1,0), (8,8) -> (n2,0), (16,8) -> (n3,0)] --> SummarizedNode -> (n7,0)
         *
         * after memcpy 24 bytes from "any" to 3040:
         * dst = [(3030,8) -> (n4,0), (3040,8) -> (n7,0), (3048,8) -> (n7,0), (3056,8) -> (n7,0)]
         * ```
         */

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkSummarizedNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
	    val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        stackC.getNode().mkLink(3030, 8, n4.createCell(0))
        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(3056, 8, n6.createCell(0))

        stackC.getNode().mkLink(4040, 8, srcNode.createCell(0))
	    srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(8, 8, n2.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        // memcpy(r1, r2, 24)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")


        val c1 = stackC.getNode().getSucc(PTAField(PTAOffset(3030), 8))
        val c2 = stackC.getNode().getSucc(PTAField(PTAOffset(3040), 8))
        val c3 = stackC.getNode().getSucc(PTAField(PTAOffset(3048), 8))
        val c4 = stackC.getNode().getSucc(PTAField(PTAOffset(3056), 8))

        Assertions.assertEquals(true,  c1!= null)

        // After memcpy we shouldn't have links at 3040, 3048, and 3056 yet
        Assertions.assertEquals(true,  c2== null)
        Assertions.assertEquals(true,  c3== null)
        Assertions.assertEquals(true,  c4== null)

        g.setRegCell(r1, stackC.getNode().createSymCell(PTAOffset(3040)))
        // getNode triggers "stack materialization"
        val c5 = load(g, r1, 0, 8)
        val c6 = load(g, r1, 8, 8)
        val c7 = load(g, r1, 16, 8)

        println("After stack materialization -> $g")
        Assertions.assertEquals(true,  c5 == c6 && c6 == c7 && c7 != null)
    }

    @Test
    fun test10() {
        println( "====== TEST 10: memcpy from stack to summarized   =======")
        /**
         * ```
         * dst = [(0,8) -> (n1,0), (8,8) -> (n2,0), (16,8) -> (n3,0)] --> SummarizedNode -> (n7,0)
         * src = [(3030,8) -> (n4,0), (3040,8) -> (n4,0),  (3048,8) -> (n5,0), (3056,8) -> (n6,0)]
         *
         * after memcpy 24 bytes from src 3040 to dst at "any":
         * dst = [(3030,8) -> (n7,0), (3040,8) -> (n7,0), (3048,8) -> (n7,0), (3056,8) -> (n7,0)]
         * ```
         */

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val dstNode = g.mkSummarizedNode()
        dstNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        stackC.getNode().mkLink(3030, 8, n4.createCell(0))
        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(3056, 8, n6.createCell(0))

        stackC.getNode().mkLink(4040, 8, dstNode.createCell(0))
        dstNode.mkLink(0, 8, n1.createCell(0))
        dstNode.mkLink(8, 8, n2.createCell(0))
        dstNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r1, dstNode.createSymCell(0))
        g.setRegCell(r2, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        // memcpy(r1, r2, 24)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        val c1 = stackC.getNode().getSucc(PTAField(PTAOffset(3030), 8))
        val c2 = stackC.getNode().getSucc(PTAField(PTAOffset(3040), 8))
        val c3 = stackC.getNode().getSucc(PTAField(PTAOffset(3048), 8))
        val c4 = stackC.getNode().getSucc(PTAField(PTAOffset(3056), 8))
        Assertions.assertEquals(true,  c1 == c2 && c2 == c3 && c3 == c4 && c4 != null)
    }

    @Test
    fun test11() {
        println( "====== TEST 11: memcpy from summarized to summarized =======")
        /**
         * ```
         * dst = [(0,8) -> (n4,0), (8,8) -> (n5,0), (16,8) -> (n6,0)] ---> SummarizedNode -> (0, n7)
         * src = [(0,8) -> (n1,0), (8,8) -> (n2,0), (16,8) -> (n3,0)] ---> SummarizedNode -> (0, n8)
         *
         * after memcpy 24 bytes (it does not matter the number of bytes being copied)
         * src = dst = SummarizedNode -> (0, n9)
         * ```
         **/
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkSummarizedNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val dstNode = g.mkSummarizedNode()

        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        dstNode.mkLink(0,  8, n4.createCell(0))
        dstNode.mkLink(8, 8, n5.createCell(0))
        dstNode.mkLink(16, 8, n6.createCell(0))

        stackC.getNode().mkLink(4040, 8, srcNode.createCell(0))
        srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(8, 8, n2.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, dstNode.createSymCell(0))

        val scalars = ScalarDomain(sbfTypesFac)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        // It should unify the nodes pointed by src with those pointed by dst.
        Assertions.assertEquals(true, g.getRegCell(r1) == g.getRegCell(r2))
    }

    @Test
    fun test12() {
        println( "====== TEST 12: memcpy with overlaps at destination =======")
        /**
         * ```
         * dst = [(3036,8) -> _, (3040,8) -> _,  (3048,4) -> _, (3048,8) -> _, (3052,8) -> _, (3056,8) -> _ ]
         * src = [(4040,8) -> (n1,0), (4048,8) -> (n2,0), (4056,8) -> (n3,0)]
         *
         * after memcpy 24 bytes from 4040 to 3040:
         * dst = [(3040,8) -> (n1,0), (3048,8) -> (n2,0), (4056,8) -> (n3,0)]
         * ```
         * Moreover, the field `(3036,8)` is marked as top and thus, PTA throws an exception if the program accesses to it.
         * However, the fields `(3048, 4)` and `(3052,8)` are accessible so if the program accesses to them, PTA will
         * allocate fresh memory for them
         */

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()

        stackC.getNode().mkLink(3036, 8, n4.createCell(0))  /*1*/
        stackC.getNode().mkLink(3040, 8, n4.createCell(0))  /*2*/ // overlap 1 and 2
        stackC.getNode().mkLink(3048, 4, n5.createCell(0))  /*3*/
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))  /*4*/ // overlap 3 and 4
        stackC.getNode().mkLink(3052, 8, n6.createCell(0))  /*5*/
        stackC.getNode().mkLink(3056, 8, n6.createCell(0))  /*6*/ // overlap 5 and 6


        stackC.getNode().mkLink(4040, 8, n1.createCell(0))
        stackC.getNode().mkLink(4048, 8, n2.createCell(0))
        stackC.getNode().mkLink(4056, 8, n3.createCell(0))
        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))

        println( "Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println( "After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 16, 8, n3)

        // Check that we kill *all* overlapping cells at the destination
        expectException<UnknownStackContentError> {
            // (3036,8) was marked as top so the pointer domain should complain
            load(g, r1, (-4L).toShort(), 8)
        }

        // Check that there is fresh memory at (3052,8)
        val x = load(g, r1, 12, 8)
        Assertions.assertEquals(true, x != null && x.getNode().isUnaccessed())

        // Check that there is fresh memory at (3048,4)
        val y = load(g, r1, 8, 4)
        Assertions.assertEquals(true, y != null && y.getNode().isUnaccessed())
    }

    @Test
    fun test13() {
        println( "====== TEST 13: memcpy from stack to (exact) non-stack with overlaps at destination =======")
        /**
         * ```
         * src = [(3896,8) -> (n1,0), (3904,8) -> (n2,0),  (3912,8) -> (n3,0), (3920,8) -> (n4,0)]
         * dst = [(0,8) -> (n5,0)]

         *
         * after memcpy 32 bytes from src 3896 to dst at 4:
         * dst = [(4,8) -> (n1,0), (12,8) -> (n2,0), (20,8) -> (n3,0), (28,8) -> (n4,0)]
         * ```
         */

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val dstNode = g.mkNode()
        dstNode.setWrite()
        val n1 = g.mkIntegerNode()
        n1.setWrite()
        val n2 = g.mkIntegerNode()
        n2.setWrite()
        val n3 = g.mkIntegerNode()
        n3.setWrite()
        val n4 = g.mkIntegerNode()
        n4.setWrite()
        val n5 = g.mkIntegerNode()
        n5.setWrite()

        stackC.getNode().mkLink(3896, 8, n1.createCell(0))
        stackC.getNode().mkLink(3904, 8, n2.createCell(0))
        stackC.getNode().mkLink(3912, 8, n3.createCell(0))
        stackC.getNode().mkLink(3920, 8, n4.createCell(0))

        stackC.getNode().mkLink(4040, 8, dstNode.createCell(0))
        dstNode.mkLink(0, 8, n5.createCell(0))

        g.setRegCell(r1, dstNode.createSymCell(4))
        g.setRegCell(r2, stackC.getNode().createSymCell(3896))

        val scalars = ScalarDomain(sbfTypesFac)
        // memcpy(r1, r2, 24)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(32UL)))
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        Assertions.assertEquals(true, load(g, r1, 0, 8)?.getNode() == n1)
        Assertions.assertEquals(true, load(g, r1, 8, 8)?.getNode() == n2)
        Assertions.assertEquals(true, load(g, r1, 16, 8)?.getNode() == n3)
        Assertions.assertEquals(true, load(g, r1, 24, 8)?.getNode() == n4)
        // memcpy cannot kill the content of r1-4 which corresponds to offset 0 and 8 bytes in dstNode
        // because the destination is not the stack and therefore we cannot perform a strong update.
        val x = load(g, r1, -4, 8)
        Assertions.assertEquals(true, x?.getNode() == n5)
    }


    @Test
    fun test14() {
        println( "====== TEST 14: memcpy with overlaps at source (I) =======")
        /**
         * ```
         * dst = [(3040,8 -> _, (3048,8) -> _, (3056,8) -> _]
         * src = [(4036,8) -> (n4,0), (4040,8) -> (n1,0), (4048,8) -> (n2,0), (4056,8) -> (n3,0), (4060,8) -> (n6,0) ]
         *
         * after memcpy 24 bytes from 4040 to 3040
         * dst = [(3040,8) -> (n1,0), (3048,8) -> (n2,0), (3056,8) -> (n3,0)]
         * ```
         */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()


        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(3056, 8, n6.createCell(0))


        stackC.getNode().mkLink(4036, 8, n4.createCell(0)) /*1*/
        stackC.getNode().mkLink(4040, 8, n1.createCell(0)) /*2*/ // overlap 1 and 2
        stackC.getNode().mkLink(4048, 8, n2.createCell(0)) /*3*/
        stackC.getNode().mkLink(4056, 8, n3.createCell(0)) /*4*/
        stackC.getNode().mkLink(4060, 8, n6.createCell(0)) /*5*/ // overlap 4 and 5

        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))

        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 16, 8, n3)

        // (4036,8) shouldn't be copied, so we shouldn't have anything at (3036,8)
        // (4060,8) shouldn't be copied, so we shouldn't have anything at (3060,8)
        // Note that since we haven't then accessed to offsets (3036 and 3060), the first time we access we will get fresh nodes.
        // Thus, the pointer analysis won't complain unlike test12, but we can check that (3036,8) and (3060,8) points to unaccessed nodes.
        val x = load(g, r1, (-4L).toShort(), 8) /* (3036,8) */
        Assertions.assertEquals(true, x != null && x.getNode().isUnaccessed())
        val y = load(g, r1, 20, 8)        /* (3060,8) */
        Assertions.assertEquals(true, y != null && y.getNode().isUnaccessed())

    }

    @Test
    fun test15() {
        println("====== TEST 15: memcpy with overlaps at source (II) =======")
        /**
         * ```
         * dst = [(3040,8 -> _, (3048,8) -> _, (3056,8) -> _]
         * src = [(4040,8) -> (n1,0), (4048,4) -> (n2,0), (4048,8) -> (n2,0), (4056,8) -> (n3,0)]
         *
         * after memcpy 24 bytes from 4040 to 3040
         * dst = [(3040,8) -> (n1,0), (4048,4) -> (n2,0), (4048,8) -> (n2,0), (4056,8) -> (n3,0)]
         * ```
         */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()


        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(3056, 8, n6.createCell(0))

        // At the source we have two overlapping cells at 4048. Both will be copied to the destination.
        stackC.getNode().mkLink(4040, 8, n1.createCell(0)) /*1*/
        stackC.getNode().mkLink(4048, 4, n2.createCell(0)) /*2*/
        stackC.getNode().mkLink(4048, 8, n2.createCell(0)) /*3*/ // overlap 2 and 3
        stackC.getNode().mkLink(4056, 8, n3.createCell(0)) /*4*/

        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))

        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1)
        checkPointsToNode(g, r1, 8, 8, n2)
        checkPointsToNode(g, r1, 8, 4, n2)
        checkPointsToNode(g, r1, 16, 8, n3)
    }

    /** Tests to show differences between load+store and memcpy **/

    @Test
    fun `load fails because it does not match store`() {
        println("====== TEST 16: used to compare with TEST 17  =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()

        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(4040, 8, n1.createCell(0))

        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        println("Initially: $g")

        load(g, r2, 0, 8)
        store(g, r1, 4, 8)
        println("After *(u64*)(r1+4) = *(u64*)(r2): $g")
        checkPointsToNode(g, r1, 4, 8, n1)

        expectException<UnknownStackContentError> {
            // after the store we cannot read at r1+4 different from 8 bytes
            load(g, r1, 4, 1)
        }
    }

    @Test
    fun `load from memcpy should not fail (1)`() {
        println("====== TEST 17:  similar to TEST 16 but using memcpy instead of load+store =======")
        /**
         * load+store of 8 bytes is almost equivalent to memcpy of 8 bytes but not really the same
         * They transfer the same memory.
         * However, load+store is more restrictive about future accesses while memcpy is not.
         */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()

        stackC.getNode().mkLink(3040, 8, n4.createCell(0))
        stackC.getNode().mkLink(3048, 8, n5.createCell(0))
        stackC.getNode().mkLink(4040, 8, n1.createCell(0))

        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)

        println("Initially: $g")
        gep(g, r1, 4)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1+4, r2, 8): $g")
        checkPointsToNode(g, r1, 0, 8, n1)

        // after memcpy we can read at r1 different from 8 bytes
        // this won't produce a PTA error
        load(g, r1, 0, 1)
    }

    @Test
    fun `load 1 byte from uninitialized memory and store it as 8 bytes (type widening in register spilling)`() {
        println("====== TEST 18 (used to compare with TEST 19) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()

        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        println("Initially: $g")

        // type widening: read 1 byte from stack and load it to a 64-bit register (r7) and write r7 (8 bytes) to stack again
        load(g, r2, 0, 1)
        store(g, r1, 0, 8)
        println("After *(u64*)(r1) = *(u8*)(r2): $g")

        Assertions.assertEquals(true,stackC.getNode().getSucc(PTAField(PTAOffset(3040), 8)) != null)
    }

    @Test
    fun `memcpy 8 bytes from uninitialized memory`() {
        println("====== TEST 19 similar to TEST 18 but using memcpy instead of load+store =======")
        /*
        *  In test18, the load reads from uninitialized memory. Therefore, it will create a fresh cell at the load and then store it.
        *  In test19, memcpy is a non-op since there is no memory to transfer at the source.
        */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()

        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)

        println("Initially: $g")

        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1, r2, 8): $g")

        Assertions.assertEquals(true,stackC.getNode().getSucc(PTAField(PTAOffset(3040), 8)) == null)
    }

    @Test
    fun `load 2 bytes from initialized memory and store it as 8 bytes (type widening in register spilling)`() {
        println("====== TEST 20 (used to compare with TEST 21) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()

        stackC.getNode().mkLink(3042, 2, n4.createCell(0))
        stackC.getNode().mkLink(4040, 2, n1.createCell(0))
        stackC.getNode().mkLink(4042, 2, n5.createCell(0))
        stackC.getNode().mkLink(4044, 2, n5.createCell(0))
        stackC.getNode().mkLink(4046, 2, n5.createCell(0))

        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        println("Initially: $g")

        // type widening: read 2 byte from stack and load it to a 64-bit register (r7) and write r7 (8 bytes) to stack again
        load(g, r2, 0, 2)
        store(g, r1, 0, 8)
        println("After *(u64*)(r1) = *(u16*)(r2): $g")
        Assertions.assertEquals(true,stackC.getNode().getSucc(PTAField(PTAOffset(3040), 8)) != null)
    }


    @Test
    fun `memcpy from initialized memory`() {
        println("====== TEST 21 similar to TEST 20 but using memcpy instead of load+store =======")
        /*
        *  In test18, the load reads from uninitialized memory. Therefore, it will create a fresh cell at the load and then store it.
        *  In test19, memcpy is a non-op since there is no memory to transfer at the source.
        */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10, globals)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()

        stackC.getNode().mkLink(3042, 2, n4.createCell(0))

        stackC.getNode().mkLink(4040, 2, n1.createCell(0))
        stackC.getNode().mkLink(4042, 2, n5.createCell(0))
        stackC.getNode().mkLink(4044, 2, n5.createCell(0))
        stackC.getNode().mkLink(4046, 2, n5.createCell(0))

        g.setRegCell(r2, stackC.getNode().createSymCell(4040))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = ScalarDomain(sbfTypesFac)

        println("Initially: $g")

        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1, r2, 8): $g")

        Assertions.assertEquals(true,stackC.getNode().getSucc(PTAField(PTAOffset(3040), 2)) != null)
    }
}
