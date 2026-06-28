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
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import sbf.disassembler.GlobalVariables
import sbf.domains.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import sbf.SolanaConfig.OptimisticPTAJoin
import sbf.SolanaConfig.OptimisticPTAOverlaps
import sbf.SolanaConfig.DefactoSemantics
import sbf.SolanaConfig.ForgetOnUntrackedStackLoad
import sbf.SolanaConfig.PTAGraphVerbosity
import sbf.SolanaConfig.SanityChecks
import sbf.analysis.MemoryAnalysis
import sbf.callgraph.SolanaFunction
import sbf.testing.SbfTestDSL

private val sbfTypesFac = ConstantSbfTypeFactory()
private val nodeAllocator = PTANodeAllocator { BasicPTANodeFlags() }
private val memDomainOpts = MemoryDomainOpts(false)
private val globals = GlobalVariables(DefaultElfFileView)
private val memSummaries = MemorySummaries()

class MemoryTest {

    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags : IPTANodeFlags<Flags>> load(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg,
        offset: Short,
        width: Short,
        lhs: Value.Reg,
        scalars: ScalarValueProvider<TNum, TOffset>
    ): PTASymCell<Flags>? {
        val inst = SbfInstruction.Mem(Deref(width, base, offset), lhs, true)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doLoad(locInst, base, SbfType.top(), scalars)
        return g.getRegCell(lhs)
    }

    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags : IPTANodeFlags<Flags>> store(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg,
        offset: Short,
        width: Short,
        value: Value.Reg
    ) {
        val inst = SbfInstruction.Mem(Deref(width, base, offset), value, false)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doStore(locInst, base, value, SbfType.top(), SbfType.top())
    }

    private fun createMemcpy() = LocatedSbfInstruction(Label.fresh(),0, SbfInstruction.Call(SolanaFunction.SOL_MEMCPY.syscall.name))

    private fun createMemoryDomain() =
        MemoryDomain(
            nodeAllocator,
            sbfTypesFac,
            memDomainOpts,
            GlobalState(globals, memSummaries),
            initPreconditions = true
        )

    @Test
    fun test01() {
        println("====== TEST 1 =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val n1 = g1.mkNode()
        n1.setRead()
        val n2 = g1.mkNode()
        n2.setWrite()
        val n3 = g1.mkNode()
        n3.setWrite()
        val n4 = g1.mkNode()
        n4.setWrite()
        stack1.getNode().mkLink(4040, 4, n1.createCell(0))
        n1.mkLink(0, 4, n2.createCell(0))
        n1.mkLink(4, 4, n3.createCell(0))
        n1.mkLink(8, 4, n4.createCell(0))
        g1.setRegCell(r2, n2.createSymCell(0))
        g1.setRegCell(r3, n3.createSymCell(0))

        //printToFile("PTATest-01-1.dot", g1.toDot(false, "before"))

        ////////////////////

        val absVal2 = absVal1.deepCopy()
        println("absVal1=\n$absVal1")
        println("absVal2=\n$absVal2")

        val g2 = absVal2.getPTAGraph()
        val c2 = g2.getRegCell(r2)
        val c3 = g2.getRegCell(r3)
        check(c2 != null) { "cannot find cell for $r2" }
        check(c3 != null) { "cannot find cell for $r3" }
        c2.concretize().unify(c3.concretize())

        sbfLogger.warn {
            "##After unifying ($n2,0) and ($n3, 0)##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2"
        }

        val check1 = absVal1.lessOrEqual(absVal2) && absVal2.lessOrEqual(absVal1)
        println("##Whether absVal1 == absVal2 --> res=$check1##")

        Assertions.assertEquals(true, check1)

        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }

        val c4 = stack2.getNode().getSucc(PTAField(PTAOffset(4040), 4))
        check(c4 != null) { "Stack at offset 4040 should have a link" }
        PTANode.smash(c4.getNode())

        sbfLogger.warn {
            "##After collapsing the successor of the stack at offset 4040##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2"
        }

        val check2 = absVal1.lessOrEqual(absVal2) && absVal2.lessOrEqual(absVal1)
        println("##Whether absVal1 == absVal2 --> res=$check2##")
        Assertions.assertEquals(true, check2)

        val n5 = g2.mkNode()
        n5.setWrite()
        g2.setRegCell(r3, n2.createSymCell(0))
        val check3 = absVal1.lessOrEqual(absVal2) && absVal2.lessOrEqual(absVal1)
        println("##Whether absVal1 == absVal2 --> res=$check3##")
        Assertions.assertEquals(true, check3)
    }

    @Test
    fun test2() {
        println("====== TEST 2 (JOIN)  =======")
        val r10 = Value.Reg(SbfRegister.R10)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val n1 = g1.mkNode()
        n1.setRead()
        val n2 = g1.mkNode()
        n2.setWrite()
        val n3 = g1.mkNode()
        n3.setWrite()
        val n4 = g1.mkNode()
        n4.setWrite()
        stack1.getNode().mkLink(4040, 4, n1.createCell(0))
        n1.mkLink(0, 4, n2.createCell(0))
        n1.mkLink(4, 4, n3.createCell(0))
        n1.mkLink(8, 4, n4.createCell(0))
        g1.setRegCell(r2, n2.createSymCell(0))
        g1.setRegCell(r3, n3.createSymCell(0))
        g1.setRegCell(r4, n4.createSymCell(0))

        ////////////////////


        val absVal2 = absVal1.deepCopy()
        val absVal3 = absVal1.deepCopy()
        println("\nabsVal1=\n$absVal1" + "absVal2=\n$absVal2" + "absVal3=\n$absVal3")

        //// AbsVal2
        val g2 = absVal2.getPTAGraph()
        val c2 = g2.getRegCell(r2)
        val c3 = g2.getRegCell(r3)
        check(c2 != null) { "cannot find cell for $r2 in AbsVal2" }
        check(c3 != null) { "cannot find cell for $r3 in AbsVal2" }
        c2.concretize().unify(c3.concretize())


        Assertions.assertEquals(true, (g1.getRegCell(r2) == g1.getRegCell(r3)))
        Assertions.assertEquals(true, (g2.getRegCell(r2) == g2.getRegCell(r3)))

        sbfLogger.info {
            "##After unifying $r2->$c2 and $r3->$c3 in absVal2##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2\n" +
                "absVal3=\n" +
                "$absVal3"
        }

        //// AbsVal3
        val g3 = absVal3.getPTAGraph()
        val c4 = g3.getRegCell(r3)
        val c5 = g3.getRegCell(r4)
        check(c4 != null) { "cannot find cell for $r3 in AbsVal3" }
        check(c5 != null) { "cannot find cell for $r4 in AbsVal3" }
        c4.concretize().unify(c5.concretize())

        sbfLogger.info {
            "##After unifying $r3->$c4 and $r4->$c5 in absVal3##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2\n" +
                "absVal3=\n" +
                "$absVal3"
        }


        Assertions.assertEquals(true, (g3.getRegCell(r2) == g3.getRegCell(r3)))
        Assertions.assertEquals(true, (g2.getRegCell(r2) == g2.getRegCell(r3)))
        Assertions.assertEquals(true, (g1.getRegCell(r2) == g1.getRegCell(r3)))
        Assertions.assertEquals(true, (g1.getRegCell(r3) == g1.getRegCell(r4)))
        Assertions.assertEquals(true, (g2.getRegCell(r3) == g2.getRegCell(r4)))
        Assertions.assertEquals(true, (g3.getRegCell(r3) == g3.getRegCell(r4)))


        /**
         * The join should do nothing since all changes took place on the shared graph between all the abstract states
         * so the join does nothing.
         */
        val absVal4 = absVal2.join(absVal3)
        sbfLogger.info {
            "##After AbsVal4 = join(AbsVal2, AbsVal3)##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2\n" +
                "absVal3=\n" +
                "$absVal3\n" +
                "absVal4=\n" +
                "$absVal4"
        }

        val g4 = absVal4.getPTAGraph()
        Assertions.assertEquals(true, (g3.getRegCell(r2) == g3.getRegCell(r3)))
        Assertions.assertEquals(true, (g2.getRegCell(r2) == g2.getRegCell(r3)))
        Assertions.assertEquals(true, (g1.getRegCell(r2) == g1.getRegCell(r3)))
        Assertions.assertEquals(true, (g1.getRegCell(r3) == g1.getRegCell(r4)))
        Assertions.assertEquals(true, (g2.getRegCell(r3) == g2.getRegCell(r4)))
        Assertions.assertEquals(true, (g3.getRegCell(r3) == g3.getRegCell(r4)))
        Assertions.assertEquals(true, (g4.getRegCell(r2) == g4.getRegCell(r3)))
        Assertions.assertEquals(true, (g4.getRegCell(r3) == g4.getRegCell(r4)))

    }

    @Test
    fun test3() {
        println("====== TEST 3 (JOIN) =======")
        val r10 = Value.Reg(SbfRegister.R10)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val n1 = g1.mkNode()
        n1.setRead()
        val n2 = g1.mkNode()
        n2.setWrite()
        val n3 = g1.mkNode()
        n3.setWrite()
        val n4 = g1.mkNode()
        n4.setWrite()
        stack1.getNode().mkLink(4040, 4, n1.createCell(0))
        n1.mkLink(0, 4, n2.createCell(0))
        n1.mkLink(4, 4, n3.createCell(0))
        n1.mkLink(8, 4, n4.createCell(0))
        g1.setRegCell(r2, n2.createSymCell(0))
        g1.setRegCell(r3, n3.createSymCell(0))
        g1.setRegCell(r4, n4.createSymCell(0))


        ////////////////////
        val absVal2 = absVal1.deepCopy()
        val g2 = absVal2.getPTAGraph()
        // absVal2 = AbsVal1[r2 := r3]
        g2.setRegCell(r2, g2.getRegCell(r3))

        sbfLogger.info {
            "##After r2 := r3 on AbsVal2##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2"
        }
        ////////////////////

        val absVal3 = absVal1.join(absVal2)
        val g3 = absVal3.getPTAGraph()
        sbfLogger.info {
            "##After AbsVal3 = join(AbsVal1, AbsVal2)##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2\n" +
                "absVal3=\n" +
                "$absVal3"
        }
        // The join changes the shared nodes so the join also modifies g1 and g2
        Assertions.assertEquals(true, (g3.getRegCell(r2) == g3.getRegCell(r3)))
        Assertions.assertEquals(false, (g3.getRegCell(r2) == g3.getRegCell(r4)))
        Assertions.assertEquals(false, (g3.getRegCell(r3) == g3.getRegCell(r4)))
        Assertions.assertEquals(true, (g2.getRegCell(r2) == g2.getRegCell(r3)))
        Assertions.assertEquals(false, (g2.getRegCell(r2) == g2.getRegCell(r4)))
        Assertions.assertEquals(false, (g2.getRegCell(r3) == g2.getRegCell(r4)))
        Assertions.assertEquals(true, (g1.getRegCell(r2) == g1.getRegCell(r3)))
        Assertions.assertEquals(false, (g1.getRegCell(r2) == g1.getRegCell(r4)))
        Assertions.assertEquals(false, (g1.getRegCell(r3) == g1.getRegCell(r4)))

        g3.setRegCell(r2, g3.getRegCell(r4))
        // registers are flow-sensitive so a change in g3 should not affect other graphs (g1 and g2)
        Assertions.assertEquals(true, (g3.getRegCell(r2) == g3.getRegCell(r4)))
        Assertions.assertEquals(false, (g2.getRegCell(r2) == g2.getRegCell(r4)))
        Assertions.assertEquals(false, (g1.getRegCell(r2) == g1.getRegCell(r4)))

        sbfLogger.info {
            "##After r2:= r4##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2\n" +
                "absVal3=\n" +
                "$absVal3"
        }
    }

    //@Test
    /** This test is expected to throw an exception **/
    fun test4() {
        println("====== TEST 4 =======")
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)


        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().mkLink(0, 4, stack1.concretize())

        val absVal2 = absVal1.deepCopy()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        sbfLogger.info {
            "\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2"
        }

        PTANode.smash(stack1.getNode())

        sbfLogger.info {
            "##After smashing stack in AbsVal1##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2"
        }

        PTANode.smash(stack2.getNode())

        sbfLogger.info {
            "##After smashing stack in AbsVal2##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2"
        }
        val c1 = absVal2.getRegCell(r1)
        check(c1 != null)
        val c2 = absVal2.getRegCell(r10)
        check(c2 != null)
        c1.concretize().unify(c2.concretize())

        sbfLogger.info {
            "##After unifying r1 and r10 in AbsVal2##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2"
        }


        val absVal3 = absVal1.join(absVal2)
        sbfLogger.info {
            "##After absVal3 := join(absVal1, absVal2)##\n" +
                "absVal1=\n" +
                "$absVal1\n" +
                "absVal2=\n" +
                "$absVal2\n" +
                "absVal3=\n" +
                "$absVal3"
        }

        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal3))
        Assertions.assertEquals(true, absVal2.lessOrEqual(absVal3))

    }

    @Test
    fun test5() {
        println("====== TEST 5 (JOIN) =======")
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val n1 = g1.mkNode()
        n1.setRead()
        val n2 = g1.mkNode()
        n2.setWrite()
        val n3 = g1.mkNode()
        n3.setWrite()
        val n4 = g1.mkNode()
        n4.setWrite()
        val n5 = g1.mkNode()
        stack1.getNode().mkLink(4040, 4, n1.createCell(0))
        n1.mkLink(0, 4, n2.createCell(0))
        n1.mkLink(4, 4, n3.createCell(0))
        n1.mkLink(8, 4, n4.createCell(0))
        g1.setRegCell(r1, n1.createSymCell(0))
        stack1.getNode().mkLink(4000, 4, n5.createCell(0))

        val absVal2 = absVal1.deepCopy()
        val g2 = absVal2.getPTAGraph()
        g2.setRegCell(r1, n5.createSymCell(0))

        sbfLogger.info{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        val absVal3 = absVal1.join(absVal2)
        sbfLogger.info{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal3))
        Assertions.assertEquals(true, absVal2.lessOrEqual(absVal3))
    }

    @Test
    fun test6() {
        println("====== TEST 6 (JOIN) =======")
        val r10 = Value.Reg(SbfRegister.R10)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))
        stack1.getNode().mkLink(4080, 4, stack1.getNode().createCell(4076))


        val absVal2 = absVal1.deepCopy()
        sbfLogger.info{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        val absVal3 = absVal1.join(absVal2)
        sbfLogger.info{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
        // The join does not lose precision
        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal3))
        Assertions.assertEquals(true, absVal2.lessOrEqual(absVal3))
        Assertions.assertEquals(true, absVal3.lessOrEqual(absVal1))
        Assertions.assertEquals(true, absVal3.lessOrEqual(absVal2))
    }

    @Test
    fun test7() {
        println("====== TEST 7 (JOIN) =======")
        val r10 = Value.Reg(SbfRegister.R10)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))
        stack1.getNode().mkLink(4080, 4, stack1.getNode().createCell(4076))


        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 4, stack2.getNode().createCell(4036))
        stack2.getNode().mkLink(4060, 4, stack2.getNode().createCell(4056))


        sbfLogger.info{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        val absVal3 = absVal1.join(absVal2)

        sbfLogger.info{"AFTER JOIN \nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        sbfLogger.info{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
        // The join strictly losses precision
        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal3))
        Assertions.assertEquals(true, absVal2.lessOrEqual(absVal3))
        Assertions.assertEquals(false, absVal3.lessOrEqual(absVal1))
        Assertions.assertEquals(false, absVal3.lessOrEqual(absVal2))
    }

    @Test
    fun test8() {
        println("====== TEST 8 (JOIN) =======")
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val n1 = g1.mkNode()  // Created by g1 but it will be shared by g2
        val n2 = g1.mkNode()
        g1.setRegCell(r1, n1.createSymCell(856))
        g1.setRegCell(r2, n2.createSymCell(0))

        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        val g2 = absVal2.getPTAGraph()

        g2.setRegCell(r1, n1.createSymCell(872))
        g2.setRegCell(r2, n1.createSymCell(872))

        sbfLogger.info{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        val absVal3 = absVal1.join(absVal2)
        sbfLogger.info{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal3))
        Assertions.assertEquals(true, absVal2.lessOrEqual(absVal3))
    }

    @Test
    fun test9() {
        println("====== TEST 9 (UNIFY) =======")

        val g = PTAGraph(nodeAllocator, sbfTypesFac, GlobalState(globals, memSummaries))
        val n1 = g.mkNode()
        val n2 = g.mkNode()
        val n3 = g.mkNode()
        val n4 = g.mkNode()

        n1.mkLink(0, 4, n2.createCell(0))
        n1.mkLink(4, 4, n2.createCell(8))
        n3.mkLink(0, 4, n2.createCell(4))
        n3.mkLink(4, 4, n4.createCell(0))
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        g.setRegCell(r1, n1.createSymCell(0))
        g.setRegCell(r2, n3.createSymCell(4))
        println("\nBefore unification of $n1 and ($n3,0):\n$g")
        n1.unify(n3, PTAOffset(0))
        println("\nAfter unification:\n$g")

        val c1 = g.getRegCell(r1)
        val c2 = g.getRegCell(r2)
        check(c1 != null && c2 != null)
        val f1 = PTAField(PTAOffset(c1.getOffset().toLongOrNull()!!), 4)
        val f2 = PTAField(PTAOffset(c2.getOffset().toLongOrNull()!!), 4)
        val x = c1.getNode().getSucc(f1)
        val y = c2.getNode().getSucc(f2)
        sbfLogger.warn{"$x"}
        sbfLogger.warn{"$y"}
        val res = x == y
        Assertions.assertEquals(true, res)
    }

    @Test
    fun test10() {
        println("====== TEST 10 (JOIN) =======")
        // In this example, we unify one stack with a node from the other graph which is not the stack.
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1= absVal1.getPTAGraph()
        val n1 = g1.mkNode()
        val n1_f1 = g1.mkNode()
        val n1_f2 = g1.mkNode()
        stack1.getNode().mkLink(4096, 4, n1.createCell(0))
        n1.mkLink(0, 4, n1_f1.createCell(0))
        n1.mkLink(4, 4, n1_f2.createCell(0))
        g1.setRegCell(r1, stack1.getNode().createSymCell(8192))


        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        val g2 = absVal2.getPTAGraph()
        val n2 = g2.mkNode()
        val n2_f1 = g2.mkNode()
        val n2_f2 = g2.mkNode()
        stack2.getNode().mkLink(4096, 4, n2.createCell(0))
        n2.mkLink(0, 4, n2_f1.createCell(0))
        n2.mkLink(4, 4, n2_f2.createCell(0))
        g2.setRegCell(r1, n2.createSymCell(0))

        sbfLogger.info{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        val absVal3 = absVal1.join(absVal2)

        sbfLogger.info{"AFTER JOIN \nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        sbfLogger.info{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal3))
        Assertions.assertEquals(true, absVal2.lessOrEqual(absVal3))

    }

    // Check isWordCompatible function from PTACell
    @Test
    fun `test isWordCompatible function from PTACell`() {
        val r10 = Value.Reg(SbfRegister.R10)
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

        ConfigScope(DefactoSemantics, false).use {
            stackC.getNode().mkLink(3040, 8, n4.createCell(0))
            stackC.getNode().mkLink(3048, 8, n5.createCell(0))
            stackC.getNode().mkLink(3056, 8, n6.createCell(0))
            Assertions.assertEquals(true, stackC.getNode().createCell(3040).isWordCompatible(24, 8))
            Assertions.assertEquals(false, stackC.getNode().createCell(3040).isWordCompatible(24, 4))

            stackC.getNode().mkLink(4040, 8, n4.createCell(0))
            stackC.getNode().mkLink(4048, 4, n5.createCell(0))
            stackC.getNode().mkLink(4056, 8, n6.createCell(0))
            Assertions.assertEquals(false, stackC.getNode().createCell(4040).isWordCompatible(24, 8))

            ConfigScope(OptimisticPTAOverlaps, false).use {
                stackC.getNode().mkLink(4040, 8, n4.createCell(0))
                stackC.getNode().mkLink(4048, 4, n5.createCell(0))
                stackC.getNode().mkLink(4048, 8, n5.createCell(0))
                stackC.getNode().mkLink(4056, 8, n6.createCell(0))
                Assertions.assertEquals(false, stackC.getNode().createCell(4040).isWordCompatible(24, 8))
            }

            ConfigScope(OptimisticPTAOverlaps, true).use {
                stackC.getNode().mkLink(4040, 8, n4.createCell(0))
                stackC.getNode().mkLink(4048, 4, n5.createCell(0))
                stackC.getNode().mkLink(4048, 8, n5.createCell(0))
                stackC.getNode().mkLink(4056, 8, n6.createCell(0))
                Assertions.assertEquals(true, stackC.getNode().createCell(4040).isWordCompatible(24, 8))
            }
        }
    }

    @Test
    fun `non-optimistic join of a pointer and a number`() {
        println("====== TEST 13 (JOIN) =======")
        /**
         * If OptimisticPTAJoin is disabled then join(X,Y) = top if X is a pointer but Y is a number
         */
        val r10 = Value.Reg(SbfRegister.R10)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))
        // R1 points to something that looks like a dangling pointer
        // Note that the pointer domain doesn't know anything about R1 but the scalar domain does
        absVal1.getScalars().setScalarValue(Value.Reg(SbfRegister.R1), ScalarValue(sbfTypesFac.toNum(4)))
        absVal1.getPTAGraph().forget(Value.Reg(SbfRegister.R1))

        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 4, stack2.getNode().createCell(4036))
        // R1 points to (stack, 4040)
        absVal2.getPTAGraph().setRegCell(Value.Reg(SbfRegister.R1), stack2.getNode().createSymCell(4040))

        sbfLogger.warn{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        ConfigScope(DefactoSemantics, false).use {
            ConfigScope(OptimisticPTAJoin, false).use {
                val absVal3 = absVal1.join(absVal2)
                sbfLogger.warn { "absVal3 := join(absVal1, absVal2) --> \n$absVal3" }
                // We should lose track of R1
                Assertions.assertEquals(true, absVal3.getRegCell(Value.Reg(SbfRegister.R1)) == null)
            }
        }
    }

    @Test
    fun `optimistic join of a pointer and dangling pointer`() {
        println("====== TEST 14 (JOIN) =======")
        /**
         *  If OptimisticPTAJoin is enabled then join(X,Y) = X if X is a pointer and Y looks a dangling pointer.
         *  Using the scalar domain can know that Y is 4 (a small power-of-two)
         */
        val r10 = Value.Reg(SbfRegister.R10)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))
        absVal1.getPTAGraph().forget(Value.Reg(SbfRegister.R1))
        absVal1.getScalars().setScalarValue(Value.Reg(SbfRegister.R1), ScalarValue(sbfTypesFac.toNum(4)))

        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 4, stack2.getNode().createCell(4036))
        // R1 points to (stack, 4040)
        absVal2.getPTAGraph().setRegCell(Value.Reg(SbfRegister.R1), stack2.getNode().createSymCell(4040))
        absVal2.getScalars().setScalarValue(Value.Reg(SbfRegister.R1), ScalarValue(SbfType.PointerType.Stack(Constant(4040))))

        sbfLogger.warn{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        ConfigScope(DefactoSemantics, false).use {
            ConfigScope(OptimisticPTAJoin, true).use {
                val absVal3 = absVal1.join(absVal2)
                sbfLogger.warn { "absVal3 := join(absVal1, absVal2) --> \n$absVal3" }
                val absVal4 = absVal2.join(absVal1)
                sbfLogger.warn { "absVal4 := join(absVal2, absVal1) --> \n$absVal4" }
                Assertions.assertEquals(true, absVal3.lessOrEqual(absVal4) && absVal4.lessOrEqual(absVal3))
                Assertions.assertEquals(true, absVal3.getRegCell(Value.Reg(SbfRegister.R1)) != null)
            }
        }
    }

    @Test
    fun `optimistic join of a pointer and a number`() {
        println("====== TEST 15 (JOIN) =======")
        /**
         *  If OptimisticPTAJoin is enabled then join(X,Y) = X if X is a pointer and Y is a number.
         *  The scalar domain doesn't know about Y but the pointer domain knows that Y points to a must-be-integer node.
         *
         *  This case should be treated in the same way that test14.
         */
        val r10 = Value.Reg(SbfRegister.R10)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))

        absVal1.getScalars().forget(Value.Reg(SbfRegister.R1))
        val integerNode = absVal1.getPTAGraph().mkIntegerNode()
        absVal1.getPTAGraph().setRegCell(Value.Reg(SbfRegister.R1), integerNode.createSymCell(0))

        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 4, stack2.getNode().createCell(4036))
        // R1 points to (stack, 4040)
        absVal2.getPTAGraph().setRegCell(Value.Reg(SbfRegister.R1), stack2.getNode().createSymCell(4040))
        absVal2.getScalars().setScalarValue(Value.Reg(SbfRegister.R1), ScalarValue(SbfType.PointerType.Stack(Constant(4040))))

        sbfLogger.warn{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        ConfigScope(DefactoSemantics, false).use {
            ConfigScope(OptimisticPTAJoin, true).use {
                val absVal3 = absVal1.join(absVal2)
                sbfLogger.warn { "absVal3 := join(absVal1, absVal2) --> \n$absVal3" }
                val absVal4 = absVal2.join(absVal1)
                sbfLogger.warn { "absVal4 := join(absVal2, absVal1) --> \n$absVal4" }
                Assertions.assertEquals(true, absVal3.lessOrEqual(absVal4) && absVal4.lessOrEqual(absVal3))
                Assertions.assertEquals(true, absVal3.getRegCell(Value.Reg(SbfRegister.R1)) != null)
            }
        }
    }


    @Test
    fun `pseudo-canonicalize (1)`() {
        println("====== TEST 16 pseudo-canonicalize =======")
        val r10 = Value.Reg(SbfRegister.R10)

        val absVal1 = createMemoryDomain()
        val g1 = absVal1.getPTAGraph()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        val n1 = g1.mkIntegerNode()
        val n2 = g1.mkIntegerNode()
        n1.setWrite()
        n2.setWrite()
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, n1.createCell(0))
        stack1.getNode().mkLink(4044, 4, n2.createCell(0))
        g1.setRegCell(Value.Reg(SbfRegister.R2), stack1.getNode().createSymCell(4040))
        g1.setRegCell(Value.Reg(SbfRegister.R3), stack1.getNode().createSymCell(4044))
        absVal1.getScalars().setStackContent(4040, 4,  ScalarValue(sbfTypesFac.toNum(0)))
        absVal1.getScalars().setStackContent(4044, 4,  ScalarValue(sbfTypesFac.toNum(0)))

        val absVal2 = createMemoryDomain()
        val g2 = absVal2.getPTAGraph()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        val n3 = g2.mkIntegerNode()
        n3.setWrite()
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 8, n3.createCell(0))
        absVal2.getScalars().setStackContent(4040, 8,  ScalarValue(sbfTypesFac.toNum(0)))


        sbfLogger.warn{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        absVal1.pseudoCanonicalize(absVal2)
        absVal2.pseudoCanonicalize(absVal1)
        sbfLogger.warn{"After pseudo canonicalization\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        //val oldVal = SolanaConfig.OptimisticPTAJoin.get()
        //SolanaConfig.OptimisticPTAJoin.set(false)
        //val absVal3 = absVal1.join(absVal2)
        //sbfLogger.warn{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
        //SolanaConfig.OptimisticPTAJoin.set(oldVal)
        // We should lose track of R1
        //Assertions.assertEquals(true, absVal3.getRegCell(Value.Reg(SbfRegister.R1_ARG), mapOf()) == null)
    }

    @Test
    fun `pseudo-canonicalize (2)`() {
        println("====== TEST 17 pseudo-canonicalize=======")
        val r10 = Value.Reg(SbfRegister.R10)

        val absVal1 = createMemoryDomain()
        val g1 = absVal1.getPTAGraph()
        val stack1 = absVal1.getRegCell(r10)
        check(stack1 != null) { "memory domain cannot find the stack node" }
        val n1 = g1.mkIntegerNode()
        val n2 = g1.mkIntegerNode()
        n1.setWrite()
        n2.setWrite()
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 8, n1.createCell(0))
        g1.setRegCell(Value.Reg(SbfRegister.R2), stack1.getNode().createSymCell(4040))
        absVal1.getScalars().setStackContent(4040, 8,  ScalarValue(sbfTypesFac.toNum(0)))

        val absVal2 = createMemoryDomain()
        val g2 = absVal2.getPTAGraph()
        val stack2 = absVal2.getRegCell(r10)
        check(stack2 != null) { "memory domain cannot find the stack node" }
        val n3 = g2.mkIntegerNode()
        n3.setWrite()
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 4, n3.createCell(0))
        absVal2.getScalars().setStackContent(4040, 4,  ScalarValue(sbfTypesFac.toNum(0)))

        sbfLogger.warn{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        absVal1.pseudoCanonicalize(absVal2)
        absVal2.pseudoCanonicalize(absVal1)
        sbfLogger.warn{"After pseudo canonicalization\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        //val oldVal = SolanaConfig.OptimisticPTAJoin.get()
        //SolanaConfig.OptimisticPTAJoin.set(false)
        //val absVal3 = absVal1.join(absVal2)
        //sbfLogger.warn{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
        //SolanaConfig.OptimisticPTAJoin.set(oldVal)
        // We should lose track of R1
        //Assertions.assertEquals(true, absVal3.getRegCell(Value.Reg(SbfRegister.R1_ARG), mapOf()) == null)
    }

    @Test
    fun `select example`() {
        println("====== TEST 18 (SELECT) =======")
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r10 = Value.Reg(SbfRegister.R10)

        val absVal = createMemoryDomain()
        val g = absVal.getPTAGraph()
        val stack = absVal.getRegCell(r10)
        check(stack != null) { "memory domain cannot find the stack node" }

        val n1 = g.mkNode()
        val n2 = g.mkNode()

        stack.getNode().setWrite()
        stack.getNode().mkLink(4040,8, n1.createCell(0))
        stack.getNode().mkLink(4048, 8, n2.createCell(0))
        g.setRegCell(r1, n1.createSymCell(0))
        g.setRegCell(r2, n2.createSymCell(0))
        println("\nBefore select(r1, *, r1, r2):\n$g")
        g.doSelect(
            LocatedSbfInstruction(Label.fresh(),
            0,
            SbfInstruction.Select(r1, Condition(CondOp.EQ, Value.Reg(SbfRegister.R3), Value.Imm(0UL)), r1, r2)),
            ScalarDomain.makeTop(sbfTypesFac, GlobalState(globals, memSummaries))
        )
        println("\nAfter:\n$g")

        run {
            val c1 = g.getRegCell(r1)
            val c2 = g.getRegCell(r2)
            check(c1 != null && c2 != null)
            val f1 = PTAField(PTAOffset(c1.getOffset().toLongOrNull()!!), 8)
            val f2 = PTAField(PTAOffset(c2.getOffset().toLongOrNull()!!), 8)
            Assertions.assertEquals(true, c1.getNode().getSucc(f1) == c2.getNode().getSucc(f2))
        }

        run {
            val c1 = stack.getNode().getSucc(PTAField(PTAOffset(4040), 8))
            val c2 = stack.getNode().getSucc(PTAField(PTAOffset(4048), 8))
            check(c1 != null && c2 != null)
            Assertions.assertEquals(true, c1 == c2)
        }
    }

    @Test
    fun `reconstruction from integer cells`() {
        println("====== TEST 19: reconstructFromIntegerCells =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val absVal = createMemoryDomain()
        val stack = absVal.getRegCell(r10)
        check(stack != null) { "memory domain cannot find the stack node" }
        stack.getNode().setRead()
        val g = absVal.getPTAGraph()
        val n1 = g.mkIntegerNode()
        n1.setRead()
        val n2 = g.mkIntegerNode()
        n2.setWrite()
        val n3 = g.mkIntegerNode()
        n3.setWrite()
        val n4 = g.mkIntegerNode()
        n4.setWrite()
        val scalars = absVal.getScalars()

        scalars.setStackContent(4000, 8, ScalarValue(sbfTypesFac.anyNum()))
        scalars.setStackContent(4032, 8, ScalarValue(sbfTypesFac.anyNum()))
        scalars.setStackContent(4040, 4, ScalarValue(sbfTypesFac.anyNum()))
        scalars.setStackContent(4044, 4, ScalarValue(sbfTypesFac.anyNum()))
        scalars.setStackContent(4048, 8, ScalarValue(sbfTypesFac.anyNum()))

        stack.getNode().mkLink(4000, 8, n3.createCell(0))
        stack.getNode().mkLink(4032, 8, n3.createCell(0))
        stack.getNode().mkLink(4040, 4, n1.createCell(0))
        stack.getNode().mkLink(4044, 4, n2.createCell(0))
        stack.getNode().mkLink(4048, 8, n4.createCell(0))
        g.setRegCell(r10,stack.getNode().createSymCell(PTAOffset(4096)))
        println("PTAGraph(test19)=$g")
        val dummyLocInst = LocatedSbfInstruction(Label.fresh(), 1, SbfInstruction.Exit())


        /** We should reconstruct a cell from (4040,4) and (4044,4) **/
        val c1 = g.reconstructFromIntegerCells(dummyLocInst, stack.getNode().createCell(4040), 8, absVal.getScalars())?.getCell()
        println("ReconstructFromIntegerCells(4040,8)=$c1\nPTAGraph=$g")
        Assertions.assertEquals(true, c1 != null)

        /** We should reconstruct a cell from (4048,8) **/
        val c2 = g.reconstructFromIntegerCells(dummyLocInst, stack.getNode().createCell(4048), 4, absVal.getScalars())?.getCell()
        println("ReconstructFromIntegerCells(4048,4)=$c2\nPTAGraph=$g")
        Assertions.assertEquals(true, c2 != null)


        /** We cannot reconstruct a cell from (4064,8) **/
        val c3 = g.reconstructFromIntegerCells(dummyLocInst, stack.getNode().createCell(4064), 8, absVal.getScalars())?.getCell()
        println("ReconstructFromIntegerCells(4064,8)=$c3\nPTAGraph=$g")
        Assertions.assertEquals(true, c3 == null)

        /** We cannot reconstruct a cell from (4044,8) **/
        val c4 = g.reconstructFromIntegerCells(dummyLocInst, stack.getNode().createCell(4044), 8, absVal.getScalars())?.getCell()
        println("ReconstructFromIntegerCells(4044,8)=$c4\nPTAGraph=$g")
        Assertions.assertEquals(true, c4 == null)
    }

    @Test
    fun `simple pointer arithmetic (1)`() {
        // trivial test for pointer arithmetic
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r2 = r10
                BinOp.SUB(r2, 8)
                goto (1)
            }
            bb(1) {
                r2[0] = 5
            }
        }


        val results = MemoryAnalysis(cfg,
            globals,
            MemorySummaries(),
            ConstantSbfTypeFactory(),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null).getPost(Label.Address(0))
        println("$cfg\nResults=$results")
        check(results != null)
        val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R2))
        check(sc != null)
        Assertions.assertEquals(true, sc.concretize().getOffset().v == 4088L)
    }

    @Test
    fun `simple pointer arithmetic (2)`() {
        // trivial test for pointer arithmetic
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 8
                r2 = r10
                BinOp.SUB(r2, r1)
                goto (1)
            }
            bb(1) {
                r2[0] = 5
            }
        }

        val results = MemoryAnalysis(cfg,
            globals,
            MemorySummaries(),
            ConstantSbfTypeFactory(),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null).getPost(Label.Address(0))
        println("$cfg\nResults=$results")
        check(results != null)
        val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R2))
        check(sc != null)
        Assertions.assertEquals(true, sc.concretize().getOffset().v == 4088L)
    }

    @Test
    fun test22() {
        println("====== TEST 22  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r2 = r10
                r3 = r10
                BinOp.SUB(r3, 24)
                br(CondOp.GT(r1, 0), 2, 3)
            }
            bb(2) {
                BinOp.SUB(r2, 8)
                r2[0] = r3
                goto(4)
            }
            bb(3) {
                BinOp.SUB(r2, 16)
                r2[0] = r3
                goto(4)
            }
            bb(4) {
                goto(5)
            }
            bb(5) {
                r4 = r2[0]
                goto(6)
            }
            bb(6) {
                assert(CondOp.NE(r2, 0)) // for liveness
                assert(CondOp.NE(r4, 0)) // for liveness
                exit()
            }
        }

        println("$cfg")
        val results = MemoryAnalysis(cfg,
            globals,
            MemorySummaries(),
            ConstantSetSbfTypeFactory(20UL),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null).getPost(Label.Address(5))
        println("$results")
        check(results != null)
        val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R2))
        check(sc != null)
        Assertions.assertEquals(true, sc.getNode().flags.isMayStack)
    }

    @Test
    fun test23() {
        println("====== TEST 23  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r2 = r10
                BinOp.SUB(r2, 8)

                br(CondOp.GT(r1, 0), 2, 3)
            }
            bb(2) {
                r3 = r10
                BinOp.SUB(r3, 24)
                r2[0] = r3
                goto(4)
            }
            bb(3) {
                r3 = r10
                BinOp.SUB(r3, 48)
                r2[0] = r3
                goto(4)
            }
            bb(4) {
                goto(5)
            }
            bb(5) {
                r4 = r2[0]
                goto(6)
            }
            bb(6) {
                assert(CondOp.NE(r2, 0)) // for liveness
                assert(CondOp.GT(r4, 0)) // for liveness
                exit()
            }
        }

        println("$cfg")
        val results = MemoryAnalysis(cfg,
            globals,
            MemorySummaries(),
            ConstantSetSbfTypeFactory(20UL),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null).getPost(Label.Address(5))
        println("$results")
        check(results != null)
        val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R4))
        check(sc != null)
        Assertions.assertEquals(true, sc.getNode().flags.isMayStack)
    }

    @Test
    fun `materialization of stack`() {
        println("====== TEST 24: materialize stack (memcpy) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r5 = Value.Reg(SbfRegister.R5)
        val absVal = createMemoryDomain()
        val stack = absVal.getRegCell(r10)
        check(stack != null) { "memory domain cannot find the stack node" }
        stack.getNode().setRead()
        val g = absVal.getPTAGraph()
        val scalars = absVal.getScalars()
        val sumN = g.mkSummarizedNode()
        sumN.setWrite()
        sumN.setRead()
        sumN.mkLink(0, 8, sumN.createCell(0))

        // memcpy(r1=sp(4032),r2=(sumN,0),r3=32)
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(4032)))
        g.setRegCell(r2,sumN.createSymCell(PTAOffset(0)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(32)))
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1=sp(4032),r2=(sumN,0),r3=32): $g")

        val c1 = stack.getNode().getSucc(PTAField(PTAOffset(4032), 8))
        val c2 = stack.getNode().getSucc(PTAField(PTAOffset(4040), 8))
        val c3 = stack.getNode().getSucc(PTAField(PTAOffset(4048), 8))
        val c4 = stack.getNode().getSucc(PTAField(PTAOffset(4056), 8))
        // *sp(4032),... should be null because it belongs to unmaterialized stack memory
        Assertions.assertEquals(true, c1 == null)
        Assertions.assertEquals(true, c2 == null)
        Assertions.assertEquals(true, c3 == null)
        Assertions.assertEquals(true, c4 == null)

        // memcpy(r1=sp(3032), r2=sp(4042), r3=32)
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(3032)))
        g.setRegCell(r2, stack.getNode().createSymCell(PTAOffset(4032)))
        g.doMemcpy(createMemcpy(), scalars)
        println("After memcpy(r1=sp(3032), r2=sp(4042), r3=32): $g")

        g.setRegCell(r4, stack.getNode().createSymCell(PTAOffset(3032)))
        // stack materialization happens here
        val c5 = load(g, r4, 0, 8, r5, scalars)
        val c6 = load(g, r4, 8, 8, r5, scalars)
        val c7 = load(g, r4, 16, 8, r5, scalars)
        val c8 = load(g, r4, 24, 8, r5, scalars)

        println("After stack materialization: $g")
        Assertions.assertEquals(true, c5 != null && c5.getNode() == sumN && c5 == c6 && c6 == c7 && c7 == c8 )
    }

    @Test
    fun `materialization of stack with memcpy followed by store`() {
        println("====== TEST 25: materialize stack (memcpy+store) =======")

        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r5 = Value.Reg(SbfRegister.R5)

        val absVal = createMemoryDomain()
        val stack = absVal.getRegCell(r10)
        check(stack != null) { "memory domain cannot find the stack node" }
        stack.getNode().setRead()
        val g = absVal.getPTAGraph()
        val scalars = absVal.getScalars()
        val sumN = g.mkSummarizedNode()
        sumN.setWrite()
        sumN.setRead()
        sumN.mkLink(0, 8, sumN.createCell(0))

        // memcpy(r1=sp(4032),r2=(sumN,0),r3=32)
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(4032)))
        g.setRegCell(r2,sumN.createSymCell(PTAOffset(0)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(32)))
        g.doMemcpy(createMemcpy(), scalars)
        ConfigScope(PTAGraphVerbosity, 2).use {
            println("After memcpy(r1=sp(4032),r2=(sumN,0),r3=32): $g")
        }

        val c1 = stack.getNode().getSucc(PTAField(PTAOffset(4032), 8))
        val c2 = stack.getNode().getSucc(PTAField(PTAOffset(4040), 8))
        val c3 = stack.getNode().getSucc(PTAField(PTAOffset(4048), 8))
        val c4 = stack.getNode().getSucc(PTAField(PTAOffset(4056), 8))
        // *sp(4032),... should be null because it belongs to unmaterialized stack memory
        Assertions.assertEquals(true, c1 == null)
        Assertions.assertEquals(true, c2 == null)
        Assertions.assertEquals(true, c3 == null)
        Assertions.assertEquals(true, c4 == null)

        val intN = g.mkIntegerNode()
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(4040)))
        g.setRegCell(r5, intN.createSymCell(PTAOffset(0)))
        // *sp(4040, 8) materializes the range [4033, 4054]: [4033,4039] and [4048,4054] are inaccessible
        //
        // 4032   4033..4039  4040..4047  4048..4054  4055..4063
        // UNMAT  INACCESS    ACCESSIBLE       ?        UNMAT
        //
        store(g, r1, 0, 8, r5)
        ConfigScope(PTAGraphVerbosity, 2).use {
            println("After store at sp(4040, 8): $g")
        }
        // memcpy(r1=sp(3032), r2=sp(4032), r3=32)
        //
        // 3032   3033..3039  3040..3047  3048..3054  3055..3063
        // UNMAT  INACCESS    ACCESSIBLE       ?        UNMAT
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(3032)))
        g.setRegCell(r2, stack.getNode().createSymCell(PTAOffset(4032)))
        g.doMemcpy(createMemcpy(), scalars)
        ConfigScope(PTAGraphVerbosity, 2).use {
            println("After memcpy(r1=sp(3032), r2=sp(4042), r3=32): $g")
        }

        g.setRegCell(r4, stack.getNode().createSymCell(PTAOffset(3032)))
        // stack materialization for *sp(3032), *sp(3048), and *sp(3056) happens here
        // Note that *sp(3040) is equals to *sp(4040) which should point to (`intN`,0)

        // c5 is *sp(3032, 1)  --> [3032, 3032] -> UNMAT
        val c5 = load(g, r4, 0, 1, r5, scalars)
        // c6 is *sp(3040, 8)  --> [3040, 3047] -> ACCESSIBLE
        val c6 = load(g, r4, 8, 8, r5, scalars)
        // c7 is *sp(3048, 8)  --> [3048, 3055] is not really inaccessible. The last inaccessible fields produced by
        // the above store are {3047:*i8,3047:*i16,3047:*i32,3047:*i64}
        //val c7 = load(g, r4, 16, 8, r5, scalars)
        // c8 is *sp(3056, 8)  --> [3056, 3063] -> UNMAT
        val c8 = load(g, r4, 24, 8, r5, scalars)

        ConfigScope(PTAGraphVerbosity, 2).use {
            println("After stack materialization: $g")
            println("c5=$c5")
            println("c6=$c6")
            //println("c7=$c7")
            println("c8=$c8")
        }
        Assertions.assertEquals(true, c5 != null && c5.getNode() == sumN && c5 != c6 && c5 == c8)
        Assertions.assertEquals(true, c6?.getNode() == intN)
    }

    @Test
    fun `read partially written stack is never allowed`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                r3 = r10
                BinOp.SUB(r3, 12)
                r4 = r3[0]
                exit()
            }
        }
        println("$cfg")

        expectException<sbf.support.UnknownStackContentError> {
            ConfigScope(DefactoSemantics, false). use {
                ConfigScope(OptimisticPTAOverlaps, true).use {
                    ConfigScope(ForgetOnUntrackedStackLoad, false).use {
                        MemoryAnalysis(
                            cfg,
                            globals,
                            MemorySummaries(),
                            ConstantSbfTypeFactory(),
                            nodeAllocator.flagsFactory,
                            memDomainOpts,
                            processor = null
                        ).getPost(Label.Address(0))
                    }
                }
            }
        }
    }

    @Test
    fun `read from uninitialized stack is allowed`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                r3 = r10
                BinOp.SUB(r3, 16)
                r4 = r3[0]
                goto(1)
            }
            bb(1) {
                r10[-200] = r4 // to keep r4 alive
                exit()
            }
        }
        println("$cfg")

        ConfigScope(DefactoSemantics, false). use {
            val results = MemoryAnalysis(
                cfg,
                globals,
                MemorySummaries(),
                ConstantSbfTypeFactory(),
                nodeAllocator.flagsFactory,
                memDomainOpts,
                processor = null
            ).getPost(
                Label.Address(0)
            )
            println("$results")
            check(results != null)
            val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R4))
            Assertions.assertEquals(true, sc != null && sc.getNode().flags.isMayExternal)
        }
    }

    @Test
    fun `maybe-uninitialized read - join of store at x and no store and read at x should be allowed`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }

            bb(1) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r4 = r2[0]
                goto (4)
            }
            bb(4) {
                r10[-200] = r4 // to keep r4 alive
                exit()
            }
        }
        println("$cfg")

        ConfigScope(DefactoSemantics, false). use {
            val results = MemoryAnalysis(
                cfg,
                globals,
                MemorySummaries(),
                ConstantSbfTypeFactory(),
                nodeAllocator.flagsFactory,
                memDomainOpts,
                processor = null
            ).getPost((Label.Address(3)))
            println("$results")
            check(results != null)
            val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R4))
            Assertions.assertEquals(true, sc != null && sc.getNode().flags.isMayInteger())
        }

    }

    @Test
    fun `maybe-uninitialized read - join of store at x and no store and read at x-2 should not be allowed`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }
            bb(1) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 10)
                r4 = r2[0]
                goto(4)
            }
            bb(4) {
                r10[-200] = r4 // to keep r4 alive
                exit()
            }
        }
        println("$cfg")

        ConfigScope(DefactoSemantics, false). use {
            ConfigScope(ForgetOnUntrackedStackLoad, false).use {
                expectException<sbf.support.UnknownStackContentError> {
                    MemoryAnalysis(
                        cfg,
                        globals,
                        MemorySummaries(),
                        ConstantSbfTypeFactory(),
                        nodeAllocator.flagsFactory,
                        memDomainOpts,
                        processor = null
                    )
                }
            }
        }
    }

    @Test
    fun `maybe-uninitialized read - join of store at x and no store and read at x-2 should be allowed with optimistic flags`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }
            bb(1) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 10)
                r4 = r2[0]
                goto(4)
            }
            bb(4) {
                r10[-200] = r4 // to keep r4 alive
                exit()
            }
        }
        println("$cfg")
        ConfigScope(DefactoSemantics, false). use {
            ConfigScope(OptimisticPTAOverlaps, true).use {
                ConfigScope(OptimisticPTAJoin, true).use {
                    val results = MemoryAnalysis(
                        cfg,
                        globals,
                        MemorySummaries(),
                        ConstantSbfTypeFactory(),
                        nodeAllocator.flagsFactory,
                        memDomainOpts,
                        processor = null
                    ).getPost(Label.Address(3))
                    println("$results")
                }
            }
        }
    }

    @Test
    fun `join of overlapping stores x and y totally disjoint and read at x or y should be always allowed`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }
            bb(1) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                goto(3)
            }
            bb(2) {
                r2 = r10
                BinOp.SUB(r2, 28)
                r2[0] = 10
                goto(3)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r4 = r2[0]
                r2 = r10
                BinOp.SUB(r2, 28)
                r5 = r2[0]
                goto(4)
            }
            bb(4) {
                r10[-200] = r4 // to keep r4 alive
                r10[-300] = r5 // to keep r5 alive
                exit()
            }
        }
        println("$cfg")
        ConfigScope(DefactoSemantics, false). use {
            ConfigScope(OptimisticPTAOverlaps, false).use {
                ConfigScope(OptimisticPTAJoin, false).use {
                    ConfigScope(ForgetOnUntrackedStackLoad, false).use {
                        val results = MemoryAnalysis(
                            cfg,
                            globals,
                            MemorySummaries(),
                            ConstantSbfTypeFactory(),
                            nodeAllocator.flagsFactory,
                            memDomainOpts,
                            processor = null
                        ).getPost((Label.Address(3)))
                        println("$results")
                        check(results != null)
                        results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R4)).let { sc ->
                            Assertions.assertEquals(true, sc != null && sc.getNode().flags.isMayInteger())
                        }

                        results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R5)).let { sc ->
                            Assertions.assertEquals(true, sc != null && sc.getNode().flags.isMayInteger())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `join of overlapping stores x and x-4 and read at x should not be allowed without optimistic flag`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }
            bb(1) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                goto(3)
            }
            bb(2) {
                r2 = r10
                BinOp.SUB(r2, 12)
                r2[0] = 5
                goto(3)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r4 = r2[0]
                goto(4)
            }
            bb(4) {
                r10[-200] = r4 // to keep r4 alive
                exit()
            }
        }
        println("$cfg")
        expectException<sbf.support.UnknownStackContentError> {
            ConfigScope(DefactoSemantics, false).use {
                ConfigScope(OptimisticPTAOverlaps, false).use {
                    ConfigScope(OptimisticPTAJoin, true).use {
                        ConfigScope(ForgetOnUntrackedStackLoad, false).use {
                            MemoryAnalysis(
                                cfg,
                                globals,
                                MemorySummaries(),
                                ConstantSbfTypeFactory(),
                                nodeAllocator.flagsFactory,
                                memDomainOpts,
                                processor = null
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `join of overlapping stores x and x-4 and read at x should be allowed with optimistic flag`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }
            bb(1) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                goto(3)
            }
            bb(2) {
                r2 = r10
                BinOp.SUB(r2, 12)
                r2[0] = 5
                goto(3)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r4 = r2[0]
                goto(4)
            }
            bb(4) {
                r10[-200] = r4 // to keep r4 alive
                exit()
            }
        }
        println("$cfg")
        ConfigScope(DefactoSemantics, false). use {
            ConfigScope(OptimisticPTAOverlaps, true).use {
                ConfigScope(OptimisticPTAJoin, true).use {
                    val results = MemoryAnalysis(
                        cfg,
                        globals,
                        MemorySummaries(),
                        ConstantSbfTypeFactory(),
                        nodeAllocator.flagsFactory,
                        memDomainOpts,
                        processor = null
                    ).getPost((Label.Address(3)))
                    println("$results")
                    check(results != null)
                    val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R4))
                    Assertions.assertEquals(true, sc != null && sc.getNode().flags.isMayInteger())
                }
            }
        }
    }

    @Test
    fun `join of overlapping stores at x and x-4 and read at x-2 should never be allowed`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }

            bb(1) {
                r2 = r10
                BinOp.SUB(r2, 8)
                r2[0] = 5
                goto(3)
            }
            bb(2) {
                r2 = r10
                BinOp.SUB(r2, 12)
                r2[0] = 5
                goto(3)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 10)
                r4 = r2[0]
                exit()
            }
        }
        println("$cfg")

        expectException<sbf.support.UnknownStackContentError> {
            ConfigScope(DefactoSemantics, false). use {
                // Even with optimistic flags shouldn't be allowed
                ConfigScope(OptimisticPTAOverlaps, true).use {
                    ConfigScope(OptimisticPTAJoin, true).use {
                        ConfigScope(ForgetOnUntrackedStackLoad, false).use {
                            MemoryAnalysis(
                                cfg,
                                globals,
                                MemorySummaries(),
                                ConstantSbfTypeFactory(),
                                nodeAllocator.flagsFactory,
                                memDomainOpts,
                                processor = null
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `no PTA error if reduction from pointer domain to scalar domain`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 8
                "__rust_alloc"()
                r2 = r0
                r2[0] = 2
                goto(1)
            }

            bb(1) {
                r8 = 1
                r2 = r2[0]
                // the pointer domain knows that r2 is a number
                // the scalar domain knows that r8 is 1
                select(r8, CondOp.EQ(r4, 0), r8, r2)
                // after reduction the memory analysis should know that r8 is just a number
                r10[-368] = r8
                goto(2)
            }
            bb(2) {
                r9 = r10[-368]
                exit()
            }
        }
        println("$cfg")

        // The test is that the memory analysis shouldn't throw any PTA error
        MemoryAnalysis(
            cfg,
            globals,
            MemorySummaries(),
            ConstantSbfTypeFactory(),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null
        )
    }

    /**
     * After a pointer store of width N at a stack offset, the surrounding fields at widths
     * other than N (including the wider widths at the same offset) are marked untracked.
     * For a widening memcpy_zext from such an u8 source to an u64 destination, the destination
     * gets an u64 link.
     */
    @Test
    fun `memcpy_zext does not violate stack invariants (untrackedStackFields) when source has pointer link`() {
        ConfigScope(SanityChecks, true).use {
            val r10 = Value.Reg(SbfRegister.R10)
            val r1 = Value.Reg(SbfRegister.R1)
            val r2 = Value.Reg(SbfRegister.R2)
            val r3 = Value.Reg(SbfRegister.R3)
            val r4 = Value.Reg(SbfRegister.R4)

            val absVal = createMemoryDomain()
            val stackC = absVal.getRegCell(r10) ?: error("memory domain cannot find the stack node")
            stackC.getNode().setRead()
            val g = absVal.getPTAGraph()
            val scalars = absVal.getScalars()

            // r4 -> a non-stack node so the value held by r4 is a pointer
            val targetNode = g.mkNode()
            targetNode.setWrite()
            g.setRegCell(r4, targetNode.createSymCell(PTAOffset(0)))

            // u8 store of the pointer at r10[-600]: creates a u8 link AND populates
            // untrackedStackFields with the overlapping fields (including the same-offset
            // u16, u32, u64 entries).
            val srcOff: Short = -600
            store(g, r10, srcOff, 1, r4)

            // Set up registers for memcpy_zext(dst=r10-1408, src=r10-600, len=1)
            val dstOff: Short = -1408
            g.setRegCell(r1, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(dstOff.toLong()))))
            g.setRegCell(r2, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(srcOff.toLong()))))
            scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(1UL)))

            val locInst = LocatedSbfInstruction(
                Label.fresh(), 0,
                SbfInstruction.Call(SolanaFunction.SOL_MEMCPY_ZEXT.syscall.name)
            )
            g.doCall(locInst, scalars)

            // The dst now has a u64 link at r10[-1408]. With the fix, the corresponding
            // u64 entry has been excluded from the propagation, so the invariant holds.
            // Without the fix, this would throw PointerDomainError.
            g.checkStackInvariants("after memcpy_zext")
        }
    }

    /**
     * Symmetric to the widening case. An u64 pointer store creates an u64 link and adds the
     * overlapping (narrower) widths to untrackedStackFields. A subsequent `memcpy_trunc` with
     * `len = 2` installs an u16 link at the destination.
     */
    @Test
    fun `memcpy_trunc does not violate stack invariants (untrackedStackFields) when source has pointer link`() {
        ConfigScope(SanityChecks, true).use {
            val r10 = Value.Reg(SbfRegister.R10)
            val r1 = Value.Reg(SbfRegister.R1)
            val r2 = Value.Reg(SbfRegister.R2)
            val r3 = Value.Reg(SbfRegister.R3)
            val r4 = Value.Reg(SbfRegister.R4)

            val absVal = createMemoryDomain()
            val stackC = absVal.getRegCell(r10) ?: error("memory domain cannot find the stack node")
            stackC.getNode().setRead()
            val g = absVal.getPTAGraph()
            val scalars = absVal.getScalars()

            val targetNode = g.mkNode()
            targetNode.setWrite()
            g.setRegCell(r4, targetNode.createSymCell(PTAOffset(0)))

            // u64 store of the pointer at r10[-600]: creates an u64 link AND populates
            // untrackedStackFields with the overlapping narrower widths at the same offset.
            val srcOff: Short = -600
            store(g, r10, srcOff, 8, r4)

            // `memcpy_trunc(dst=r10-1408, src=r10-600, len=2)`: creates a u16 link on dst.
            val dstOff: Short = -1408
            g.setRegCell(r1, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(dstOff.toLong()))))
            g.setRegCell(r2, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(srcOff.toLong()))))
            scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(2UL)))

            val locInst = LocatedSbfInstruction(
                Label.fresh(), 0,
                SbfInstruction.Call(SolanaFunction.SOL_MEMCPY_TRUNC.syscall.name)
            )
            g.doCall(locInst, scalars)

            g.checkStackInvariants("after memcpy_trunc")
        }
    }

    /**
     * ```
     * memcpy(sp(2689), summ, 7)             // unmaterialized at [sp(2689), sp(2695)]
     * *(u8 *) sp(3496) = pointer
     * memcpy_zext(sp(2688), sp(3496), 1)    // u64 link at sp(2688); stale entry must be cleared
     * assert(*(u8 *) sp(2691) is not summ)
     * ```
     */
    @Test
    fun `memcpy_zext clears stale unmaterialized stack in the high zext bytes`() {
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r5 = Value.Reg(SbfRegister.R5)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10) ?: error("memory domain cannot find the stack node")
        stackC.getNode().setRead()
        val g = absVal.getPTAGraph()
        val scalars = absVal.getScalars()

        // (1) Create a stale unmaterialized region strictly in the high zext bytes by
        // memcpy'ing 7 bytes from a summarized node into `r10-1407`. After this,
        // unmaterializedStack covers `[r10-1407, r10-1401]` only; it does NOT touch
        // `r10-1408`, so `removeLinks(dstC, 1)` in step (3) cannot wipe it.
        val sumN = g.mkSummarizedNode()
        sumN.setRead()
        sumN.setWrite()
        sumN.mkLink(0, 8, sumN.createCell(0))

        val dstOff: Short = -1408
        val highOff: Short = -1407
        g.setRegCell(r1, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(highOff.toLong()))))
        g.setRegCell(r2, sumN.createSymCell(PTAOffset(0)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(7UL)))
        g.doMemcpy(createMemcpy(), scalars)

        println("(1) memcpy(sp(${4096+highOff}), summ, 7) -- $g")
        // (2) Set up an u8 pointer link at `r10-600` so the widening has a link to copy.
        val srcOff: Short = -600
        val targetNode = g.mkNode()
        targetNode.setWrite()
        g.setRegCell(r4, targetNode.createSymCell(PTAOffset(0)))
        store(g, r10, srcOff, 1, r4)
        println("(2) Add an u8 link at ${4096-600} -- $g")
        // (3) memcpy_zext(dst=r10-1408, src=r10-600, len=1). The widening installs an u64
        // link at `r10-1408`. `removeLinks(dstC, 1)` only touches `[r10-1408, r10-1408]`,
        // which does not overlap the planted region, so the stale interval survives unless
        // the fix at lines 4192-4195 explicitly clears the high portion.
        g.setRegCell(r1, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(dstOff.toLong()))))
        g.setRegCell(r2, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(srcOff.toLong()))))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(1UL)))
        val locInst = LocatedSbfInstruction(
            Label.fresh(), 0,
            SbfInstruction.Call(SolanaFunction.SOL_MEMCPY_ZEXT.syscall.name)
        )
        g.doCall(locInst, scalars)
        println("(3) after memcpy_zext(dst=${4096-1408}, src=${4096-600},  1) -- $g")
        // (4) Load a single byte from the high zext region. With the fix the stale entry
        // was cleared and the load falls through to a fresh external allocation, so the
        // loaded cell's node is not `sumN`. Without the fix the stale entry materializes
        // and the loaded cell's node IS `sumN`.
        val loadedC = load(g, r10, (dstOff + 3).toShort(), 1, r5, scalars)
        println("(4) *(u8*)sp(${4096+dstOff +3}) = $loadedC")
        Assertions.assertEquals(false, loadedC?.getNode() == sumN)
    }

    /**
     * Test for the unmaterialized-stack propagation in `memcpyExactToStack`
     *
     * For Narrowing the source read range is 8 bytes (the full u64)
     * while the destination write range is only `len`. The source's unmaterialized region
     * is therefore wider than the dst write range and must be clipped during transfer.
     *
     * The source read range is 8 (the source link width for Narrowing) and
     * each interval is trimmed to fit into the dst write range `[dstOffset, dstOffset+len)` before
     * being inserted on the dst.
     *
     * ```
     * memcpy(sp(3496), summ, 8)             // unmaterialized at [sp(3496), sp(3503)] -> sumN
     * memcpy_trunc(sp(2688), sp(3496), 2)   // dst should inherit unmaterialized at [sp(2688), sp(2689)]
     * assert(*(u8 *) sp(2688) is sumN)
     * ```
     */
    @Test
    fun `memcpy_trunc transfers source unmaterialized stack to destination clipped to len`() {
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r5 = Value.Reg(SbfRegister.R5)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10) ?: error("memory domain cannot find the stack node")
        stackC.getNode().setRead()
        val g = absVal.getPTAGraph()
        val scalars = absVal.getScalars()

        // (1) Create an 8-byte unmaterialized region at `r10-600` by memcpy'ing from a
        // summarized node. After this, unmaterializedStack covers `[r10-600, r10-593]`,
        // pointing at a cell whose node is `sumN`.
        val sumN = g.mkSummarizedNode()
        sumN.setRead()
        sumN.setWrite()
        sumN.mkLink(0, 8, sumN.createCell(0))

        val srcOff: Short = -600
        g.setRegCell(r1, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(srcOff.toLong()))))
        g.setRegCell(r2, sumN.createSymCell(PTAOffset(0)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
        g.doMemcpy(createMemcpy(), scalars)
        println("(1) memcpy(sp(${4096+srcOff}), summ, 8) -- $g")

        // (2) `memcpy_trunc(dst=r10-1408, src=r10-600, len=2)`.
        // Stack-to-stack with kind=Narrowing.
        // The source's unmaterialized interval `[r10-600, r10-593]` (8 bytes) is wider than the dst write range `[r10-1408, r10-1407]`
        // (2 bytes). Lines 4172-4185 must scan the source over its 8-byte read range, clip each interval
        // to the dst write range, and insert the clipped result on the dst.
        val dstOff: Short = -1408
        g.setRegCell(r1, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(dstOff.toLong()))))
        g.setRegCell(r2, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(srcOff.toLong()))))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(2UL)))
        val locInst = LocatedSbfInstruction(
            Label.fresh(), 0,
            SbfInstruction.Call(SolanaFunction.SOL_MEMCPY_TRUNC.syscall.name)
        )
        g.doCall(locInst, scalars)
        println("(2) after memcpy_trunc(dst=${4096+dstOff}, src=${4096+srcOff}, 2) -- $g")

        // (3) Load an u8 at `r10-1408`. The dst inherits the unmaterialized
        // entry and the load materializes the `sumN` cell.
        val loadedC = load(g, r10, dstOff, 1, r5, scalars)
        println("(3) *(u8*)sp(${4096+dstOff}) = $loadedC")
        Assertions.assertEquals(true, loadedC?.getNode() == sumN)
    }

    /**
     * Similar to previous test but for `memcpy_zext`
     *
     * ```
     * memcpy(sp(3496), summ, 8)             // unmaterialized at [sp(3496), sp(3503)] -> sumN
     * memcpy_zext(sp(2688), sp(3496), 4)    // dst should inherit unmaterialized at [sp(2688), sp(2691)]
     * assert(*(u8 *) sp(2688) is sumN)
     * ```
     */
    @Test
    fun `memcpy_zext transfers source unmaterialized stack to destination clipped to len`() {
        val r10 = Value.Reg(SbfRegister.R10)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r5 = Value.Reg(SbfRegister.R5)

        val absVal = createMemoryDomain()
        val stackC = absVal.getRegCell(r10) ?: error("memory domain cannot find the stack node")
        stackC.getNode().setRead()
        val g = absVal.getPTAGraph()
        val scalars = absVal.getScalars()

        // (1) Create an 8-byte unmaterialized region at `r10-600` by memcpy'ing from a
        // summarized node. After this, unmaterializedStack covers `[r10-600, r10-593]`,
        // pointing at a cell whose node is `sumN`.
        val sumN = g.mkSummarizedNode()
        sumN.setRead()
        sumN.setWrite()
        sumN.mkLink(0, 8, sumN.createCell(0))

        val srcOff: Short = -600
        g.setRegCell(r1, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(srcOff.toLong()))))
        g.setRegCell(r2, sumN.createSymCell(PTAOffset(0)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8UL)))
        g.doMemcpy(createMemcpy(), scalars)
        println("(1) memcpy(sp(${4096+srcOff}), summ, 8) -- $g")

        // (2) `memcpy_zext(dst=r10-1408, src=r10-600, len=4)`. Stack-to-stack with
        // kind=Widening. The source's unmaterialized interval `[r10-600, r10-593]`
        // (8 bytes) is wider than the dst content range `[r10-1408, r10-1405]` (4 bytes).
        // Lines 4172-4185 scan the source over its `len`-byte read range, clip each
        // interval to the dst content range, and insert the clipped result on the dst.
        val dstOff: Short = -1408
        g.setRegCell(r1, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(dstOff.toLong()))))
        g.setRegCell(r2, stackC.getNode().createSymCell(stackC.getOffset().add(PTASymOffset(srcOff.toLong()))))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(4UL)))
        val locInst = LocatedSbfInstruction(
            Label.fresh(), 0,
            SbfInstruction.Call(SolanaFunction.SOL_MEMCPY_ZEXT.syscall.name)
        )
        g.doCall(locInst, scalars)
        println("(2) after memcpy_zext(dst=${4096+dstOff}, src=${4096+srcOff}, 4) -- $g")

        // (3) Load an u8 at `r10-1408`. The dst inherits the unmaterialized entry (clipped
        // to the low 4 bytes) and the load materializes the `sumN` cell.
        val loadedC = load(g, r10, dstOff, 1, r5, scalars)
        println("(3) *(u8*)sp(${4096+dstOff}) = $loadedC")
        Assertions.assertEquals(true, loadedC?.getNode() == sumN)
    }

    @Test
    fun `join of unmaterialized and untracked stack fields should not throw exception`() {
        /**
         * Diamond:
         *
         *   bb0
         *   / \
         * bb1 bb2
         *   \ /
         *   bb3
         *
         * - bb1: memcpy from summarized heap to stack at sp-200.
         * - bb2: stack store at the same sp-196, making sp-200:u64 untracked.
         */
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                br(CondOp.EQ(r3, 0), 1, 2)
            }
            bb(1) {
                "CVT_nondet_u64"()
                r3 = r0
                // Allocate on the heap and memcpy 8 bytes into stack at sp-200.
                r1 = 8
                "__rust_alloc"()
                BinOp.ADD(r0, r3)
                r0[0] = 5 // this should make the heap-allocated memory "summarized" because we are writing to a statically unknown address
                r2 = r0
                r1 = r10
                BinOp.SUB(r1, 200)
                r3 = 8
                "sol_memcpy_"()
                goto(3)
            }
            bb(2) {
                // Immediate 8-byte store at sp-196
                r1 = r10
                BinOp.SUB(r1, 196)
                r1[0] = 5
                goto(3)
            }
            bb(3) {
                exit()
            }
        }
        cfg.normalize()
        println("$cfg")

        ConfigScope(SanityChecks, true).use {
            ConfigScope(PTAGraphVerbosity, 2).use {
                MemoryAnalysis(
                    cfg,
                    globals,
                    MemorySummaries(),
                    ConstantSbfTypeFactory(),
                    nodeAllocator.flagsFactory,
                    memDomainOpts,
                    processor = null
                )
            }
        }
    }

    @Test
    fun `4-byte stack store creates untracked overlapping fields that overlap pre-existing unmat`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                // Step 1: memcpy from a summarized heap to stack(sp-192, 8) -> unmat [sp-192, sp-185].
                "CVT_nondet_u64"()
                r3 = r0
                r1 = 8
                "__rust_alloc"()
                BinOp.ADD(r0, r3)
                r0[0] = 5
                r2 = r0
                r1 = r10
                BinOp.SUB(r1, 192)
                r3 = 8
                "sol_memcpy_"()

                // Step 2: 4-byte store at sp-200.  overlapCandidates(PTAField(sp-200, 4))
                // adds wider entries including (sp-199, 8), interval [sp-199, sp-192],
                // overlapping the unmat at byte sp-192.
                r1 = r10
                BinOp.SUB(r1, 200)
                r1[0, 4] = 5
                exit()
            }
        }
        cfg.normalize()
        println("$cfg")

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
            println("post: ${results.getPTAGraph()}")
            results.getPTAGraph().checkStackInvariants( "after 4-byte stack store")
        }
    }

    /**
     * Exercise lessOrEqual when both operands hold identical unmat: identity must be ⊑ in both directions.
     */
    @Test
    fun `lessOrEqual is reflexive on unmaterialized stack`() {
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(Value.Reg(SbfRegister.R10))
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val scalars1 = absVal1.getScalars()
        val sumN = g1.mkSummarizedNode()
        sumN.setRead()
        sumN.setWrite()
        sumN.mkLink(0, 8, sumN.createCell(0))

        // memcpy(r1=sp(4040), r2=sumN, r3=8) -> unmat[4040, 4047]
        g1.setRegCell(r1, stack1.getNode().createSymCell(PTAOffset(4040)))
        g1.setRegCell(r2, sumN.createSymCell(PTAOffset(0)))
        scalars1.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8)))
        g1.doMemcpy(createMemcpy(), scalars1)

        val absVal2 = absVal1.deepCopy()
        println("absVal1=\n$absVal1")
        println("absVal2=\n$absVal2")

        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal2))
        Assertions.assertEquals(true, absVal2.lessOrEqual(absVal1))
    }

    /**
     * left has unmat over a range; right has materialized that range into a field with
     * a cell that the unmat's cell is ⊑ to.
     */
    @Test
    fun `lessOrEqual accepts left unmat covered by right materialized field`() {
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r5 = Value.Reg(SbfRegister.R5)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(Value.Reg(SbfRegister.R10))
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val scalars1 = absVal1.getScalars()
        val sumN = g1.mkSummarizedNode()
        sumN.setRead()
        sumN.setWrite()
        sumN.mkLink(0, 8, sumN.createCell(0))

        // absVal1 stays in the unmat shape.
        g1.setRegCell(r1, stack1.getNode().createSymCell(PTAOffset(4040)))
        g1.setRegCell(r2, sumN.createSymCell(PTAOffset(0)))
        scalars1.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8)))
        g1.doMemcpy(createMemcpy(), scalars1)

        // absVal2 materializes the unmat to a (4040, 8) succ via a load.
        val absVal2 = absVal1.deepCopy()
        val g2 = absVal2.getPTAGraph()
        val scalars2 = absVal2.getScalars()
        val stack2 = absVal2.getRegCell(Value.Reg(SbfRegister.R10))!!
        g2.setRegCell(r4, stack2.getNode().createSymCell(PTAOffset(4040)))
        load(g2, r4, 0, 8, r5, scalars2)

        // forget r4 and r5 otherwise absVal1 cannot be less or equal than absVal2
        g2.forget(r4)
        g2.forget(r5)
        println("absVal1 (unmat)=\n$absVal1")
        println("absVal2 (materialized)=\n$absVal2")

        // Without the unmat-covered-by-succ rule, this would fail.
        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal2))
    }

    /**
     * A join of two states with incompatible stack-pointer cells at the same field
     * produces a right with that field untracked (no succ).  Left's unmat over the same range
     * must be ⊑ that right via the inaccessible-coverage rule (step (iii) of the unmat loop).
     */
    @Test
    fun `lessOrEqual accepts left unmat covered by right inaccessible field`() {
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)

        // absValLeft: unmat[4040, 4047] -> some extern cell.
        val absValLeft = createMemoryDomain()
        val stackL = absValLeft.getRegCell(Value.Reg(SbfRegister.R10))!!
        stackL.getNode().setRead()
        val gL = absValLeft.getPTAGraph()
        val scalarsL = absValLeft.getScalars()
        val sumN = gL.mkSummarizedNode()
        sumN.setRead()
        sumN.setWrite()
        sumN.mkLink(0, 8, sumN.createCell(0))
        gL.setRegCell(r1, stackL.getNode().createSymCell(PTAOffset(4040)))
        gL.setRegCell(r2, sumN.createSymCell(PTAOffset(0)))
        scalarsL.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8)))
        gL.doMemcpy(createMemcpy(), scalarsL)

        // absValA, absValB: incompatible stack-pointer cells at (4040, 8).  Their join produces
        // (4040, 8) in untrackedStackFields with no succ.
        val absValA = createMemoryDomain()
        val stackA = absValA.getRegCell(Value.Reg(SbfRegister.R10))!!
        stackA.getNode().setRead()
        stackA.getNode().mkLink(4040, 8, stackA.getNode().createCell(3000))

        val absValB = createMemoryDomain()
        val stackB = absValB.getRegCell(Value.Reg(SbfRegister.R10))!!
        stackB.getNode().setRead()
        stackB.getNode().mkLink(4040, 8, stackB.getNode().createCell(2000))

        val absValRight = absValA.join(absValB)
        println("absValLeft=\n$absValLeft")
        println("absValRight=\n$absValRight")

        Assertions.assertEquals(true, absValLeft.lessOrEqual(absValRight))
    }

    /**
     * Left holds a materialized field; right has the same range as unmat with a compatible cell.
     * lessOrEqual must accept via the field-loop covering-unmat escape (step (c)).
     */
    @Test
    fun `lessOrEqual accepts left materialized field covered by right unmat`() {
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r5 = Value.Reg(SbfRegister.R5)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(Value.Reg(SbfRegister.R10))
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val scalars1 = absVal1.getScalars()
        val sumN = g1.mkSummarizedNode()
        sumN.setRead()
        sumN.setWrite()
        sumN.mkLink(0, 8, sumN.createCell(0))

        g1.setRegCell(r1, stack1.getNode().createSymCell(PTAOffset(4040)))
        g1.setRegCell(r2, sumN.createSymCell(PTAOffset(0)))
        scalars1.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8)))
        g1.doMemcpy(createMemcpy(), scalars1)

        // absVal1 keeps the unmat (it will be the right operand).
        // absVal2 materializes via a load (it will be the left operand).
        val absVal2 = absVal1.deepCopy()
        val g2 = absVal2.getPTAGraph()
        val scalars2 = absVal2.getScalars()
        val stack2 = absVal2.getRegCell(Value.Reg(SbfRegister.R10))!!
        g2.setRegCell(r4, stack2.getNode().createSymCell(PTAOffset(4040)))
        load(g2, r4, 0, 8, r5, scalars2)

        g2.forget(r4)
        g2.forget(r5)

        println("absVal1 (unmat)=\n$absVal1")
        println("absVal2 (materialized field)=\n$absVal2")

        Assertions.assertEquals(true, absVal1.lessOrEqual(absVal2))
        Assertions.assertEquals(true, absVal2.lessOrEqual(absVal1))
    }

    /**
     * Cell-mismatch counter-example: a right field overlapping left's unmat with an
     * incompatible cell must cause lessOrEqual to fail (cell-condition short-circuit in step (ii)).
     */
    @Test
    fun `lessOrEqual rejects left unmat covered by right field with incompatible cell`() {
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r5 = Value.Reg(SbfRegister.R5)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(Value.Reg(SbfRegister.R10))
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val scalars1 = absVal1.getScalars()
        val sumN = g1.mkSummarizedNode()
        sumN.setRead()
        sumN.setWrite()
        sumN.mkLink(0, 8, sumN.createCell(0))
        g1.setRegCell(r1, stack1.getNode().createSymCell(PTAOffset(4040)))
        g1.setRegCell(r2, sumN.createSymCell(PTAOffset(0)))
        scalars1.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(8)))
        g1.doMemcpy(createMemcpy(), scalars1)

        // absVal2 has succ (4040, 8) but pointing to a fresh, unrelated node.
        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(Value.Reg(SbfRegister.R10))!!
        stack2.getNode().setRead()
        val g2 = absVal2.getPTAGraph()
        val unrelatedN = g2.mkNode()
        unrelatedN.setWrite()
        stack2.getNode().mkLink(4040, 8, unrelatedN.createCell(0))
        g2.setRegCell(r4, stack2.getNode().createSymCell(PTAOffset(4040)))
        g2.setRegCell(r5, unrelatedN.createSymCell(PTAOffset(0)))

        // forget registers to avoid lessOrEqual returns false for the wrong reason
        g1.forget(r1)
        g1.forget(r2)
        g2.forget(r4)
        g2.forget(r5)

        println("absVal1 (unmat)=\n$absVal1")
        println("absVal2 (succ with unrelated cell)=\n$absVal2")

        Assertions.assertEquals(false, absVal1.lessOrEqual(absVal2))
    }
}
