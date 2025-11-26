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
import sbf.SolanaConfig.OptimisticPTAOverlaps
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import sbf.disassembler.newGlobalVariableMap
import sbf.domains.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import sbf.analysis.MemoryAnalysis
import sbf.callgraph.SolanaFunction
import sbf.testing.SbfTestDSL

private val sbfTypesFac = ConstantSbfTypeFactory()
private val nodeAllocator = PTANodeAllocator { BasicPTANodeFlags() }
private val memDomainOpts = MemoryDomainOpts(false)

class MemoryTest {

    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags : IPTANodeFlags<Flags>> load(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg,
        offset: Short,
        width: Short,
        lhs: Value.Reg
    ): PTASymCell<Flags>? {
        val inst = SbfInstruction.Mem(Deref(width, base, offset, null), lhs, true, null)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doLoad(locInst, base, SbfType.top(), newGlobalVariableMap())
        return g.getRegCell(lhs)
    }

    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags : IPTANodeFlags<Flags>> store(
        g: PTAGraph<TNum, TOffset, Flags>,
        base: Value.Reg,
        offset: Short,
        width: Short,
        value: Value.Reg
    ) {
        val inst = SbfInstruction.Mem(Deref(width, base, offset, null), value, false, null)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doStore(locInst, base, value, SbfType.top(), SbfType.top(), newGlobalVariableMap())
    }

    private fun createMemcpy() = LocatedSbfInstruction(Label.fresh(),0, SbfInstruction.Call(SolanaFunction.SOL_MEMCPY.syscall.name))


    private fun createMemoryDomain() =
        MemoryDomain(nodeAllocator, sbfTypesFac, memDomainOpts,true)

    @Test
    fun test01() {
        println("====== TEST 1 =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
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

        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
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
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
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
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
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
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)


        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().mkLink(0, 4, stack1.concretize())

        val absVal2 = absVal1.deepCopy()
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
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
        val c1 = absVal2.getRegCell(r1, newGlobalVariableMap())
        check(c1 != null)
        val c2 = absVal2.getRegCell(r10, newGlobalVariableMap())
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
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
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
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
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
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))
        stack1.getNode().mkLink(4080, 4, stack1.getNode().createCell(4076))


        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
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
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        val g1 = absVal1.getPTAGraph()
        val n1 = g1.mkNode()  // Created by g1 but it will be shared by g2
        val n2 = g1.mkNode()
        g1.setRegCell(r1, n1.createSymCell(856))
        g1.setRegCell(r2, n2.createSymCell(0))

        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
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

        val g = PTAGraph(nodeAllocator, sbfTypesFac)
        val n1 = g.mkNode()
        val n2 = g.mkNode()
        val n3 = g.mkNode()
        val n4 = g.mkNode()

        n1.mkLink(0, 4, n2.createCell(0))
        n1.mkLink(4, 4, n2.createCell(8))
        n3.mkLink(0, 4, n2.createCell(4))
        n3.mkLink(4, 4, n4.createCell(0))
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
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
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
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
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
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
    fun test12() {
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val absVal = createMemoryDomain()
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
        val n4 = g.mkNode()
        n4.setWrite()
        val n5 = g.mkNode()
        n5.setWrite()
        val n6 = g.mkNode()
        n6.setWrite()


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

    @Test
    fun test13() {
        println("====== TEST 13 (JOIN) =======")
        /**
         * If OptimisticPTAJoin is disabled then join(X,Y) = top if X is a pointer but Y is a number
         */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))
        // R1 points to something that looks like a dangling pointer
        // Note that the pointer domain doesn't know anything about R1 but the scalar domain does
        absVal1.getScalars().setScalarValue(Value.Reg(SbfRegister.R1_ARG), ScalarValue(sbfTypesFac.toNum(4)))
        absVal1.getPTAGraph().forget(Value.Reg(SbfRegister.R1_ARG))

        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 4, stack2.getNode().createCell(4036))
        // R1 points to (stack, 4040)
        absVal2.getPTAGraph().setRegCell(Value.Reg(SbfRegister.R1_ARG), stack2.getNode().createSymCell(4040))

        sbfLogger.warn{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        ConfigScope(SolanaConfig.OptimisticPTAJoin, false).use {
            val absVal3 = absVal1.join(absVal2)
            sbfLogger.warn{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
            // We should lose track of R1
            Assertions.assertEquals(true, absVal3.getRegCell(Value.Reg(SbfRegister.R1_ARG), newGlobalVariableMap()) == null)
        }
    }

    @Test
    fun test14() {
        println("====== TEST 14 (JOIN) =======")
        /**
         *  If OptimisticPTAJoin is enabled then join(X,Y) = X if X is a pointer and Y looks a dangling pointer.
         *  Using the scalar domain can know that Y is 4 (a small power-of-two)
         */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))
        absVal1.getPTAGraph().forget(Value.Reg(SbfRegister.R1_ARG))
        absVal1.getScalars().setScalarValue(Value.Reg(SbfRegister.R1_ARG), ScalarValue(sbfTypesFac.toNum(4)))

        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 4, stack2.getNode().createCell(4036))
        // R1 points to (stack, 4040)
        absVal2.getPTAGraph().setRegCell(Value.Reg(SbfRegister.R1_ARG), stack2.getNode().createSymCell(4040))
        absVal2.getScalars().setScalarValue(Value.Reg(SbfRegister.R1_ARG), ScalarValue(SbfType.PointerType.Stack(Constant(4040))))

        sbfLogger.warn{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        ConfigScope(SolanaConfig.OptimisticPTAJoin, true).use {
            val absVal3 = absVal1.join(absVal2)
            sbfLogger.warn{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
            val absVal4 = absVal2.join(absVal1)
            sbfLogger.warn{"absVal4 := join(absVal2, absVal1) --> \n$absVal4"}
            Assertions.assertEquals(true, absVal3.lessOrEqual(absVal4) && absVal4.lessOrEqual(absVal3))
            Assertions.assertEquals(true, absVal3.getRegCell(Value.Reg(SbfRegister.R1_ARG), newGlobalVariableMap()) != null)
        }
    }

    @Test
    fun test15() {
        println("====== TEST 15 (JOIN) =======")
        /**
         *  If OptimisticPTAJoin is enabled then join(X,Y) = X if X is a pointer and Y is a number.
         *  The scalar domain doesn't know about Y but the pointer domain knows that Y points to a must-be-integer node.
         *
         *  This case should be treated in the same way that test14.
         */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)

        val absVal1 = createMemoryDomain()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
        check(stack1 != null) { "memory domain cannot find the stack node" }
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, stack1.getNode().createCell(4036))

        absVal1.getScalars().forget(Value.Reg(SbfRegister.R1_ARG))
        val integerNode = absVal1.getPTAGraph().mkIntegerNode()
        absVal1.getPTAGraph().setRegCell(Value.Reg(SbfRegister.R1_ARG), integerNode.createSymCell(0))

        val absVal2 = createMemoryDomain()
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
        check(stack2 != null) { "memory domain cannot find the stack node" }
        stack2.getNode().setRead()
        stack2.getNode().mkLink(4040, 4, stack2.getNode().createCell(4036))
        // R1 points to (stack, 4040)
        absVal2.getPTAGraph().setRegCell(Value.Reg(SbfRegister.R1_ARG), stack2.getNode().createSymCell(4040))
        absVal2.getScalars().setScalarValue(Value.Reg(SbfRegister.R1_ARG), ScalarValue(SbfType.PointerType.Stack(Constant(4040))))

        sbfLogger.warn{"\nAbsVal1=$absVal1\nAbsVal2=$absVal2"}
        ConfigScope(SolanaConfig.OptimisticPTAJoin, true).use {
            val absVal3 = absVal1.join(absVal2)
            sbfLogger.warn{"absVal3 := join(absVal1, absVal2) --> \n$absVal3"}
            val absVal4 = absVal2.join(absVal1)
            sbfLogger.warn{"absVal4 := join(absVal2, absVal1) --> \n$absVal4"}
            Assertions.assertEquals(true, absVal3.lessOrEqual(absVal4) && absVal4.lessOrEqual(absVal3))
            Assertions.assertEquals(true, absVal3.getRegCell(Value.Reg(SbfRegister.R1_ARG), newGlobalVariableMap()) != null)
        }
    }


    @Test
    fun test16() {
        println("====== TEST 16 pseudo-canonicalize =======")
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)

        val absVal1 = createMemoryDomain()
        val g1 = absVal1.getPTAGraph()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
        check(stack1 != null) { "memory domain cannot find the stack node" }
        val n1 = g1.mkIntegerNode()
        val n2 = g1.mkIntegerNode()
        n1.setWrite()
        n2.setWrite()
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 4, n1.createCell(0))
        stack1.getNode().mkLink(4044, 4, n2.createCell(0))
        g1.setRegCell(Value.Reg(SbfRegister.R2_ARG), stack1.getNode().createSymCell(4040))
        g1.setRegCell(Value.Reg(SbfRegister.R3_ARG), stack1.getNode().createSymCell(4044))
        absVal1.getScalars().setStackContent(4040, 4,  ScalarValue(sbfTypesFac.toNum(0)))
        absVal1.getScalars().setStackContent(4044, 4,  ScalarValue(sbfTypesFac.toNum(0)))

        val absVal2 = createMemoryDomain()
        val g2 = absVal2.getPTAGraph()
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
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
    fun test17() {
        println("====== TEST 17 pseudo-canonicalize=======")
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)

        val absVal1 = createMemoryDomain()
        val g1 = absVal1.getPTAGraph()
        val stack1 = absVal1.getRegCell(r10, newGlobalVariableMap())
        check(stack1 != null) { "memory domain cannot find the stack node" }
        val n1 = g1.mkIntegerNode()
        val n2 = g1.mkIntegerNode()
        n1.setWrite()
        n2.setWrite()
        stack1.getNode().setRead()
        stack1.getNode().mkLink(4040, 8, n1.createCell(0))
        g1.setRegCell(Value.Reg(SbfRegister.R2_ARG), stack1.getNode().createSymCell(4040))
        absVal1.getScalars().setStackContent(4040, 8,  ScalarValue(sbfTypesFac.toNum(0)))

        val absVal2 = createMemoryDomain()
        val g2 = absVal2.getPTAGraph()
        val stack2 = absVal2.getRegCell(r10, newGlobalVariableMap())
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
    fun test18() {
        println("====== TEST 18 (SELECT) =======")
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)

        val absVal = createMemoryDomain()
        val g = absVal.getPTAGraph()
        val stack = absVal.getRegCell(r10, newGlobalVariableMap())
        check(stack != null) { "memory domain cannot find the stack node" }

        val n1 = g.mkNode()
        val n2 = g.mkNode()

        stack.getNode().setWrite()
        stack.getNode().mkLink(4040,8, n1.createCell(0))
        stack.getNode().mkLink(4048, 8, n2.createCell(0))
        g.setRegCell(r1, n1.createSymCell(0))
        g.setRegCell(r2, n2.createSymCell(0))
        println("\nBefore select(r1, *, r1, r2):\n$g")
        g.doSelect(LocatedSbfInstruction(Label.fresh(), 0, SbfInstruction.Select(r1, Condition(CondOp.EQ, Value.Reg(SbfRegister.R3_ARG), Value.Imm(0UL)), r1, r2)),
                                         newGlobalVariableMap(),
                                         ScalarDomain.makeTop(sbfTypesFac))
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
    fun test19() {
        println("====== TEST 19: reconstructFromIntegerCells =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val absVal = createMemoryDomain()
        val stack = absVal.getRegCell(r10, newGlobalVariableMap())
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
    fun test20() {
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
            newGlobalVariableMap(),
            MemorySummaries(),
            ConstantSbfTypeFactory(),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null).getPost(Label.Address(0))
        println("$cfg\nResults=$results")
        check(results != null)
        val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R2_ARG))
        check(sc != null)
        Assertions.assertEquals(true, sc.concretize().getOffset().v == 4088L)
    }

    @Test
    fun test21() {
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
            newGlobalVariableMap(),
            MemorySummaries(),
            ConstantSbfTypeFactory(),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null).getPost(Label.Address(0))
        println("$cfg\nResults=$results")
        check(results != null)
        val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R2_ARG))
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
                assert(CondOp.GT(r4, 0)) // for liveness
                exit()
            }
        }

        println("$cfg")
        val results = MemoryAnalysis(cfg,
            newGlobalVariableMap(),
            MemorySummaries(),
            ConstantSetSbfTypeFactory(20UL),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null).getPost(Label.Address(5))
        println("$results")
        check(results != null)
        val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R2_ARG))
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
            newGlobalVariableMap(),
            MemorySummaries(),
            ConstantSetSbfTypeFactory(20UL),
            nodeAllocator.flagsFactory,
            memDomainOpts,
            processor = null).getPost(Label.Address(5))
        println("$results")
        check(results != null)
        val sc = results.getPTAGraph().getRegCell(Value.Reg(SbfRegister.R4_ARG))
        check(sc != null)
        Assertions.assertEquals(true, sc.getNode().flags.isMayStack)
    }

    @Test
    fun test24() {
        println("====== TEST 24: materialize stack (memcpy) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val r5 = Value.Reg(SbfRegister.R5_ARG)
        val absVal = MemoryDomain(nodeAllocator, sbfTypesFac, MemoryDomainOpts(false), true)
        val stack = absVal.getRegCell(r10, newGlobalVariableMap())
        check(stack != null) { "memory domain cannot find the stack node" }
        stack.getNode().setRead()
        val g = absVal.getPTAGraph()
        val scalars = absVal.getScalars()
        val globals = newGlobalVariableMap()
        val sumN = g.mkSummarizedNode()
        sumN.setWrite()
        sumN.setRead()
        sumN.mkLink(0, 8, sumN.createCell(0))

        // memcpy(r1=sp(4032),r2=(sumN,0),r3=32)
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(4032)))
        g.setRegCell(r2,sumN.createSymCell(PTAOffset(0)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(32)))
        g.doMemcpy(createMemcpy(), scalars, globals)
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
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1=sp(3032), r2=sp(4042), r3=32): $g")

        g.setRegCell(r4, stack.getNode().createSymCell(PTAOffset(3032)))
        // stack materialization happens here
        val c5 = load(g, r4, 0, 8, r5)
        val c6 = load(g, r4, 8, 8, r5)
        val c7 = load(g, r4, 16, 8, r5)
        val c8 = load(g, r4, 24, 8, r5)

        println("After stack materialization: $g")
        Assertions.assertEquals(true, c5 != null && c5.getNode() == sumN && c5 == c6 && c6 == c7 && c7 == c8 )
    }

    @Test
    fun test25() {
        println("====== TEST 25: materialize stack (memcpy+store) =======")

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val r5 = Value.Reg(SbfRegister.R5_ARG)

        val absVal = MemoryDomain(nodeAllocator, sbfTypesFac, MemoryDomainOpts(false),true)
        val stack = absVal.getRegCell(r10, newGlobalVariableMap())
        check(stack != null) { "memory domain cannot find the stack node" }
        stack.getNode().setRead()
        val g = absVal.getPTAGraph()
        val scalars = absVal.getScalars()
        val globals = newGlobalVariableMap()
        val sumN = g.mkSummarizedNode()
        sumN.setWrite()
        sumN.setRead()
        sumN.mkLink(0, 8, sumN.createCell(0))

        // memcpy(r1=sp(4032),r2=(sumN,0),r3=32)
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(4032)))
        g.setRegCell(r2,sumN.createSymCell(PTAOffset(0)))
        scalars.setScalarValue(r3, ScalarValue(sbfTypesFac.toNum(32)))
        g.doMemcpy(createMemcpy(), scalars, globals)
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

        val intN = g.mkIntegerNode()
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(4040)))
        g.setRegCell(r5, intN.createSymCell(PTAOffset(0)))
        store(g, r1, 0, 8, r5)

        // memcpy(r1=sp(3032), r2=sp(4042), r3=32)
        g.setRegCell(r1, stack.getNode().createSymCell(PTAOffset(3032)))
        g.setRegCell(r2, stack.getNode().createSymCell(PTAOffset(4032)))
        g.doMemcpy(createMemcpy(), scalars, globals)
        println("After memcpy(r1=sp(3032), r2=sp(4042), r3=32): $g")

        g.setRegCell(r4, stack.getNode().createSymCell(PTAOffset(3032)))
        // stack materialization for *sp(3032), *sp(3048), and *sp(3056) happens here
        // Note that *sp(3040) is equals to *sp(4040) which should point to (`intN`,0)
        val c5 = load(g, r4, 0, 8, r5)
        val c6 = load(g, r4, 8, 8, r5)
        val c7 = load(g, r4, 16, 8, r5)
        val c8 = load(g, r4, 24, 8, r5)

        println("After stack materialization: $g")
        Assertions.assertEquals(true, c5 != null && c5.getNode() == sumN && c5 != c6 && c5 == c7 && c7 == c8 )
        Assertions.assertEquals(true, c6?.getNode() == intN)
    }
}
