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

import config.ConfigScope
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.support.UnknownStackContentError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.*
import sbf.SolanaConfig.ForgetOnUntrackedStackLoad
import sbf.SolanaConfig.PTAGraphVerbosity
import sbf.SolanaConfig.SanityChecks
import sbf.analysis.MemoryAnalysis
import sbf.callgraph.SolanaFunction
import sbf.support.UnknownMemcpyLenError
import sbf.testing.SbfTestDSL

private val sbfTypesFac = ConstantSbfTypeFactory()
private val nodeAllocator = PTANodeAllocator { BasicPTANodeFlags() }
private val memDomainOpts = MemoryDomainOpts(false)
private val globals = GlobalVariables(DefaultElfFileView)
private val memSummaries = MemorySummaries()

class MemoryMemcpyTest {

    /**
     * Return cell pointed by *([base] + [offset]).
     * Use `r7` as intermediate register.
     */
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags : IPTANodeFlags<Flags>> load(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg,
        offset: Short,
        width: Short,
        scalars: ScalarValueProvider<TNum, TOffset>
    ): PTASymCell<Flags>? {
        val lhs = Value.Reg(SbfRegister.R7)
        check(base != lhs)
        val inst = SbfInstruction.Mem(Deref(width, base, offset), lhs, true)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doLoad(locInst, base, SbfType.top(), scalars)
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
        g.doStore(locInst, base, value, baseType = SbfType.top(), valueType = SbfType.top())
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
        g.doBin(locInst, BinOp.ADD, base, Value.Imm(offset.toULong()), SbfType.top(), SbfType.top())
    }

    // Check that *([baseR] + [offset]) points to [node]
    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, Flags: IPTANodeFlags<Flags>> checkPointsToNode(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg, offset: Short, width: Short,
        node: PTANode<Flags>,
        scalars: ScalarValueProvider<TNum, TOffset>
    ) {
        Assertions.assertEquals(
            true,
            load(g, base, offset, width, scalars)?.getNode()?.id == node.getNode().id
        )
    }

    private fun createMemcpy() = LocatedSbfInstruction(Label.fresh(),0, SbfInstruction.Call(SolanaFunction.SOL_MEMCPY.syscall.name))
    //private fun createMemset() = LocatedSbfInstruction(Label.fresh(),0, SbfInstruction.Call(SolanaFunction.SOL_MEMSET.syscall.name))
    private fun createMemoryDomain() =
        MemoryDomain(
            nodeAllocator,
            sbfTypesFac,
            MemoryDomainOpts(false),
            GlobalState(globals, memSummaries),
            initPreconditions = true
        )
    private fun createScalarDomain() =
        ScalarDomain(
            sbfTypesFac,
            GlobalState(globals, memSummaries)
        )

    @Test
    fun test01() {
        println("====== TEST 1: memcpy from stack to uninitialized stack  (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)
    }

    @Test
    fun test02() {
        println( "====== TEST 2: memcpy from (exact) non-stack to uninitialized stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)
    }

    @Test
    fun test03() {
        println( "====== TEST 3: memcpy from (exact) non-stack to (exact) uninitialized non-stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        Assertions.assertEquals(true, !dstN.isUnaccessed())
        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)
    }


    @Test
    fun test04() {
        println( "====== TEST 4: memcpy from stack to initialized stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)

        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)
    }

    @Test
    fun test05() {
        println( "====== TEST 5: memcpy from (exact) non-stack to initialized stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)
    }


    @Test
    fun test06() {
        println( "====== TEST 6: memcpy from (exact) non-stack to (exact) initialized non-stack (known length) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        Assertions.assertEquals(true, !n4.getNode().isUnaccessed())
        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)
    }

    @Test
    fun test07() {
        println( "====== TEST 7: memcpy from (exact) non-stack to (exact) initialized non-stack (unknown length) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        val r3 = Value.Reg(SbfRegister.R3)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.anyNum()))
        // memcpy(r1, r2, r3)
        println("Before memcpy(r1,r2,r3) with r3=top -> $g")
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,r3) with r3=top -> $g")

        // It should unify the nodes pointed by src with those pointed by dst.
        Assertions.assertEquals(true, g.getRegCell(r1) == g.getRegCell(r2))
    }

    @Test
    fun test08() {
        println( "====== TEST 8: memcpy from (exact) non-stack to initialized stack (unknown length) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()

        expectException<UnknownMemcpyLenError> {
            // memcpy(r1, r2, r3)
            println("Before memcpy(r1,r2,r3) with r3=top -> $g")
            g.doMemcpy(createMemcpy(), scalars)
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

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        // memcpy(r1, r2, 24)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
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
        val c5 = load(g, r1, 0, 8, scalars)
        val c6 = load(g, r1, 8, 8, scalars)
        val c7 = load(g, r1, 16, 8, scalars)

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

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        // memcpy(r1, r2, 24)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
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
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        val r3 = Value.Reg(SbfRegister.R3)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        // memcpy(r1, r2, 24)
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
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

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))

        println( "Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
        println( "After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)

        // Check that we kill *all* overlapping cells at the destination
        expectException<UnknownStackContentError> {
            ConfigScope(ForgetOnUntrackedStackLoad, false).use {
                // (3036,8) was marked as top so the pointer domain should complain
                load(g, r1, (-4L).toShort(), 8, scalars)
            }
        }

        // Check that there is fresh memory at (3052,8)
        val x = load(g, r1, 12, 8, scalars)
        Assertions.assertEquals(true, x != null && x.getNode().isUnaccessed())

        // Check that there is fresh memory at (3048,4)
        val y = load(g, r1, 8, 4, scalars)
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

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // Create abstract state
        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        // memcpy(r1, r2, 24)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(32UL)))
        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        Assertions.assertEquals(true, load(g, r1, 0, 8, scalars)?.getNode() == n1)
        Assertions.assertEquals(true, load(g, r1, 8, 8, scalars)?.getNode() == n2)
        Assertions.assertEquals(true, load(g, r1, 16, 8, scalars)?.getNode() == n3)
        Assertions.assertEquals(true, load(g, r1, 24, 8, scalars)?.getNode() == n4)
        // memcpy cannot kill the content of r1-4 which corresponds to offset 0 and 8 bytes in dstNode
        // because the destination is not the stack and therefore we cannot perform a strong update.
        val x = load(g, r1, -4, 8, scalars)
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
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))

        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)

        // (4036,8) shouldn't be copied, so we shouldn't have anything at (3036,8)
        // (4060,8) shouldn't be copied, so we shouldn't have anything at (3060,8)
        // Note that since we haven't then accessed to offsets (3036 and 3060), the first time we access we will get fresh nodes.
        // Thus, the pointer analysis won't complain unlike test12, but we can check that (3036,8) and (3060,8) points to unaccessed nodes.
        val x = load(g, r1, (-4L).toShort(), 8, scalars) /* (3036,8) */
        Assertions.assertEquals(true, x != null && x.getNode().isUnaccessed())
        val y = load(g, r1, 20, 8, scalars)        /* (3060,8) */
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
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))

        println("Before memcpy(r1,r2,24) -> $g")
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 8, 8, n2, scalars)
        checkPointsToNode(g, r1, 8, 4, n2, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)
    }

    /** Tests to show differences between load+store and memcpy **/

    @Test
    fun `load fails because it does not match store`() {
        println("====== TEST 16: used to compare with TEST 17  =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()
        load(g, r2, 0, 8, scalars)
        store(g, r1, 4, 8)
        println("After *(u64*)(r1+4) = *(u64*)(r2): $g")
        checkPointsToNode(g, r1, 4, 8, n1, scalars)

        expectException<UnknownStackContentError> {
            ConfigScope(ForgetOnUntrackedStackLoad, false).use {
                // after the store we cannot read at r1+4 different from 8 bytes
                load(g, r1, 4, 1, scalars)
            }
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
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()

        println("Initially: $g")
        gep(g, r1, 4)
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1+4, r2, 8): $g")
        checkPointsToNode(g, r1, 0, 8, n1, scalars)

        // after memcpy we can read at r1 different from 8 bytes
        // this won't produce a PTA error
        load(g, r1, 0, 1, scalars)
    }

    @Test
    fun `load 1 byte from uninitialized memory and store it as 8 bytes (type widening in register spilling)`() {
        println("====== TEST 18 (used to compare with TEST 19) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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
        load(g, r2, 0, 1, createScalarDomain())
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
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()

        println("Initially: $g")

        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1, r2, 8): $g")

        Assertions.assertEquals(true,stackC.getNode().getSucc(PTAField(PTAOffset(3040), 8)) == null)
    }

    @Test
    fun `load 2 bytes from initialized memory and store it as 8 bytes (type widening in register spilling)`() {
        println("====== TEST 20 (used to compare with TEST 21) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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
        load(g, r2, 0, 2, createScalarDomain())
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
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
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

        val scalars = createScalarDomain()

        println("Initially: $g")

        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1, r2, 8): $g")

        Assertions.assertEquals(true,stackC.getNode().getSucc(PTAField(PTAOffset(3040), 2)) != null)
    }

    /**
     *  ```
     *  *sp(3030,8) = ...
     *  memcpy(dst = sp(3034), src = summ, len = 8)
     *  ```
     */
    @Test
    fun `memcpy from summarized partially overlapping a stack link should not leave bytes both untracked and unmaterialized`() {
        ConfigScope(PTAGraphVerbosity, 2).use {
            ConfigScope(SanityChecks, true).use {
                val r10 = Value.Reg(SbfRegister.R10)
                val r1 = Value.Reg(SbfRegister.R1)
                val r2 = Value.Reg(SbfRegister.R2)
                val r3 = Value.Reg(SbfRegister.R3)

                val absVal = createMemoryDomain()
                val stackC = absVal.getRegCell(r10)
                check(stackC != null) { "memory domain cannot find the stack node" }
                stackC.getNode().setWrite()
                val g = absVal.getPTAGraph()

                // Summarized source with one successor (so memcpySummToStack takes the
                // non-empty-succs branch and inserts into unmaterializedStack).
                val srcNode = g.mkSummarizedNode()
                srcNode.setWrite()
                val nSucc = g.mkNode()
                nSucc.setWrite()
                srcNode.mkLink(0, 8, nSucc.createCell(0))

                // Materialized stack link that will be partially overlapped by the memcpy dst range.
                val nOld = g.mkNode()
                nOld.setWrite()
                stackC.getNode().mkLink(3030, 8, nOld.createCell(0))

                g.setRegCell(r2, srcNode.createSymCell(0))
                g.setRegCell(r1, stackC.getNode().createSymCell(3034))

                val scalars = createScalarDomain()
                scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
                println("Before memcpy(stack(3034), summ(0), 8): $g")
                g.doMemcpy(createMemcpy(), scalars)
                println("After memcpy: $g")
                g.checkStackInvariants("")
            }
        }
    }

    /**
     *  `memcpyExactToStack` from a non-stack source to stack: bytes that did NOT receive an
     *  explicit link from the source must be tracked in `unmaterializedStack` so a later read
     *  materializes a successor on demand.
     *
     *  Setup: source has links at `(0, 8)` and `(16, 8)` but not at `(8, 8)`. A memcpy of
     *  24 bytes therefore copies two links to the destination and leaves bytes `[8, 15]`
     *  uncovered, which become `unmaterializedStack[3048, 3055]` on the stack.
     *
     *  We verify the gap is unmaterialized (rather than just empty) by reading the gap at
     *  two different sub-offsets: with the fix in place both reads materialize from the
     *  SAME `unmaterializedStack` cell, so the returned cells share a node.
     */
    @Test
    fun `memcpy from non-stack to stack marks uncovered bytes as unmaterializedStack`() {
        println("====== TEST: memcpy from non-stack to stack with partial src layout marks gap as unmaterializedStack =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10)
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val srcNode = g.mkNode()
        srcNode.setWrite()
        val n1 = g.mkNode()
        n1.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        // src has links at (0, 8) and (16, 8) but NOT at (8, 8); bytes [8, 15] in src are uncovered.
        srcNode.mkLink(0, 8, n1.createCell(0))
        srcNode.mkLink(16, 8, n3.createCell(0))

        g.setRegCell(r2, srcNode.createSymCell(0))
        g.setRegCell(r1, stackC.getNode().createSymCell(3040))

        val scalars = createScalarDomain()
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1,r2,24) -> $g")

        // Covered bytes: the two explicit links were transferred.
        checkPointsToNode(g, r1, 0, 8, n1, scalars)
        checkPointsToNode(g, r1, 16, 8, n3, scalars)

        // Gap [3048, 3055]: two reads at disjoint sub-offsets must materialize the same
        // unmaterializedStack cell (which means they share a node).
        val gap1 = load(g, r1, 8, 4, scalars)
        val gap2 = load(g, r1, 12, 4, scalars)
        check(gap1 != null) { "load from gap[8,4] returned null" }
        check(gap2 != null) { "load from gap[12,4] returned null" }
        Assertions.assertEquals(gap1.getNode().id, gap2.getNode().id)
        // And it is a fresh node, not one of the source's link targets or the source itself.
        Assertions.assertNotEquals(n1.id, gap1.getNode().id)
        Assertions.assertNotEquals(n3.id, gap1.getNode().id)
        Assertions.assertNotEquals(srcNode.id, gap1.getNode().id)
    }

    /**
     *  Weak `memcpyExactToExact` from a non-stack source to the stack: bytes that did NOT
     *  receive a transferred link must be tracked in `unmaterializedStack`, while existing
     *  links on the dst (preserved by weak semantics) must remain materialized.
     *
     *  We trigger the weak path by giving r1 two possible stack offsets, which makes
     *  `memcpyLifter` call `memcpyExactToWeakStack` for each of them. After the memcpy,
     *  reading two disjoint sub-offsets of the gap must materialize the SAME
     *  `unmaterializedStack` cell — the proof that the weak path filled the gap.
     */
    @Test
    fun `weak memcpy from non-stack to stack marks uncovered bytes as unmaterializedStack`() {
        println("====== TEST: weak memcpy from non-stack to stack with partial src layout marks gap as unmaterializedStack =======")

        ConfigScope(SanityChecks, true).use {
            val r10 = Value.Reg(SbfRegister.R10)
            val r1 = Value.Reg(SbfRegister.R1)
            val r2 = Value.Reg(SbfRegister.R2)
            val r3 = Value.Reg(SbfRegister.R3)

            val absVal = createMemoryDomain()
            val stackC = absVal.getRegCell(r10)
            check(stackC != null) { "memory domain cannot find the stack node" }
            stackC.getNode().setWrite()
            val g = absVal.getPTAGraph()
            val srcNode = g.mkNode()
            srcNode.setWrite()
            val n1 = g.mkNode()
            n1.setWrite()
            val n3 = g.mkNode()
            n3.setWrite()
            // src has links at (0, 8) and (16, 8) but NOT at (8, 8); bytes [8, 15] in src are uncovered.
            srcNode.mkLink(0, 8, n1.createCell(0))
            srcNode.mkLink(16, 8, n3.createCell(0))

            g.setRegCell(r2, srcNode.createSymCell(0))
            // Multi-offset dst SymCell forces memcpyLifter to use the weak transformer,
            // which lands in memcpyExactToWeakStack -> memcpyExactToExact.
            g.setRegCell(r1, stackC.getNode().createSymCell(PTASymOffset(listOf(3040L, 4000L))))

            val scalars = createScalarDomain()
            scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(24UL)))
            g.doMemcpy(createMemcpy(), scalars)
            println("After memcpy(r1,r2,24) -> $g")

            // Reset r1 to a single concrete stack offset so the subsequent loads have a
            // well-defined dereference target.
            g.setRegCell(r1, stackC.getNode().createSymCell(3040))

            // Covered bytes: the two explicit src links were transferred to dst=3040.
            checkPointsToNode(g, r1, 0, 8, n1, scalars)
            checkPointsToNode(g, r1, 16, 8, n3, scalars)

            // Gap [3048, 3055]: two reads at disjoint sub-offsets must materialize the same
            // unmaterializedStack cell (which means they share a node).
            val gap1 = load(g, r1, 8, 4, scalars)
            val gap2 = load(g, r1, 12, 4, scalars)
            check(gap1 != null) { "load from gap[8,4] returned null" }
            check(gap2 != null) { "load from gap[12,4] returned null" }
            Assertions.assertEquals(gap1.getNode().id, gap2.getNode().id)
            // And it is a fresh node, not one of the source's link targets or the source itself.
            Assertions.assertNotEquals(n1.id, gap1.getNode().id)
            Assertions.assertNotEquals(n3.id, gap1.getNode().id)
            Assertions.assertNotEquals(srcNode.id, gap1.getNode().id)
        }
    }

    /**
     *   1. memcpy from a summarized heap to stack(3896), len 8
     *      -> `memcpySummToStack` creates `unmaterializedStack[3896, 3903]`.
     *   2. memcpy from an exact heap to stack(3900), len 4
     *      -> `memcpyExactToStack -> removeLinks` removes the **whole** unmat
     *         interval `[3896, 3903]`, even though only the trailing 4 bytes
     *         `[3900, 3903]` are overwritten.
     *   3. read 4 bytes at stack(3896): the prefix `[3896, 3899]` was not
     *      overwritten by the second memcpy.  With precision preserved, the load
     *      materializes the summarized heap's successor (an accessed node).
     *      Without it, the load returns a fresh node.
     */
    @Test
    fun `memcpyExactToStack keeps unmaterialized bytes outside the write range`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                // Step 1: build a summarized heap and memcpy from it to stack(sp-200, 8).
                "CVT_nondet_u64"()
                r3 = r0
                r1 = 8
                "__rust_alloc"()
                BinOp.ADD(r0, r3)
                r0[0] = 5            // store at unknown offset -> heap becomes summarized
                r2 = r0
                r1 = r10
                BinOp.SUB(r1, 200)
                r3 = 8
                "sol_memcpy_"()

                // Step 2: build an exact heap and memcpy 4 bytes from it to stack(sp-196, 4).
                r1 = 8
                "__rust_alloc"()
                r0[0, 4] = 7         // 4-byte store at fixed offset -> heap stays exact
                r2 = r0
                r1 = r10
                BinOp.SUB(r1, 196)
                r3 = 4
                "sol_memcpy_"()

                // Step 3: read 4 bytes at stack(sp-200).
                r1 = r10
                BinOp.SUB(r1, 200)
                r4 = r1[0, 4]
                goto (1)

            }
            bb(1) {
                assert(CondOp.EQ(r4, 0UL)) // to keep alive r4 at the end of block 0
                exit()
            }
        }
        cfg.normalize()
        println("$cfg")

        ConfigScope(SanityChecks, true).use {
            ConfigScope(PTAGraphVerbosity, 2).use {
                val results = MemoryAnalysis(
                    cfg,
                    globals,
                    MemorySummaries(),
                    ConstantSbfTypeFactory(),
                    nodeAllocator.flagsFactory,
                    memDomainOpts,
                    processor = null
                ).getPost(Label.Address(0))
                check(results != null) { "no abstract state at exit of block 0" }

                val r4Cell = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R4))
                check(r4Cell != null) { "r4 is not a cell" }
                // The load with r4 as lhs materializes the bytes [3896, 3899] so it shouldn't read from
                // uninitialized memory (fresh node marked as external)
                Assertions.assertEquals(false, r4Cell.getNode().flags.isMayExternal)
            }
        }
    }
}
