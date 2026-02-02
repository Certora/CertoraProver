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
import sbf.analysis.ScalarAnalysis
import sbf.callgraph.SolanaFunction
import sbf.cfg.*
import sbf.disassembler.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.domains.*

private val sbfTypesFac = ConstantSbfTypeFactory()
private val globals = GlobalVariables(DefaultElfFileView)

class UnhoistMemcpyTest {
    private fun getNumOfUnhoistedMemcpy(cfg: SbfCFG): UInt {
        var counter = 0U
        for (b in cfg.getBlocks().values) {
            for (inst in b.getInstructions()) {
                val metadata = inst.metaData
                if (metadata.getVal(SbfMeta.UNHOISTED_MEMCPY) != null) {
                    counter++
                }
            }
        }
        return counter
    }

    val unhoistOpts = UnhoistHeuristics(true, 10)

    @Test
    fun test01_false() {
        // without hoisting memcpy we cannot prove that the *r1 contains a number

        val absValAtExit = test01(sbfTypesFac, false)
        println("Abstract value at exit=$absValAtExit")
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        Assertions.assertEquals(false, absValAtExit.getValue(r0).type() is SbfType.NumType)
    }

    @Test
    fun test01_true() {
        // with hoisting memcpy we should prove that the *r1 contains a number

        val absValAtExit = test01(sbfTypesFac, true)
        println("Abstract value at exit=$absValAtExit")
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        Assertions.assertEquals(true, absValAtExit.getValue(r0).type() is SbfType.NumType)
    }


    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>
        test01(sbfTypesFac: ISbfTypeFactory<TNum, TOffset>, hoistMemcpy: Boolean): ScalarDomain<TNum, TOffset> {
        /**
         * b0:
         *   if (...) b1 else b5
         * b5:
         *   if (...) b2 else b3
         * b1:
         *   *(r2+0)  := 1
         *   *(r2+8)  := 1
         *   *(r2+16) := 1
         *   *(r2+24) := 1
         *   goto b4
         * b2:
         *   *(r2+0)  := 2
         *   *(r2+8)  := 2
         *   *(r2+16) := 2
         *   *(r2+24) := 2
         *   goto b4
         * b3:
         *   *(r2+0)  := 3
         *   *(r2+8)  := 3
         *   *(r2+16) := 3
         *   *(r2+24) := 3
         *   goto b4
         * b4:
         *   memcpy(r1,r2,32)
         *
         */
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test1")
        val l0 = Label.Address(1)
        val l1 = Label.Address(2)
        val l2 = Label.Address(3)
        val l3 = Label.Address(4)
        val l4 = Label.Address(5)
        val l5 = Label.Address(6)
        val b0 = cfg.getOrInsertBlock(l0)
        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)
        val b5 = cfg.getOrInsertBlock(l5)
        cfg.setEntry(b0)
        b0.addSucc(b1)
        b0.addSucc(b5)
        b5.addSucc(b2)
        b5.addSucc(b3)
        b1.addSucc(b4)
        b2.addSucc(b4)
        b3.addSucc(b4)

        b0.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b0.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        if (!hoistMemcpy) {
            b0.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(1UL), false))
            b0.add(SbfInstruction.Mem(Deref(8, r1, 8), Value.Imm(1UL), false))
            b0.add(SbfInstruction.Mem(Deref(8, r1, 16), Value.Imm(1UL), false))
            b0.add(SbfInstruction.Mem(Deref(8, r1, 24), Value.Imm(1UL), false))
        }
        b0.add(SbfInstruction.Havoc(r3))
        b0.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r3, Value.Imm(0UL)), l1, l5))

        b5.add(SbfInstruction.Havoc(r3))
        b5.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r3, Value.Imm(0UL)), l2, l3))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b2.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b2.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(300UL), true))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b3.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b3.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(400UL), true))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Jump.UnconditionalJump(l4))



        b4.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b4.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCPY))

        b4.add(SbfInstruction.Mem(Deref(8, r1, 0), r0, true))
        b4.add(SbfInstruction.Exit())
        cfg.normalize()
        println("$cfg")
        cfg.verify(true)

        if (hoistMemcpy) {
            unhoistMemFunctions(cfg, unhoistOpts)
            cfg.simplify(globals)
            println("After unhoisting memcpy instructions: $cfg")
            Assertions.assertEquals(true, getNumOfUnhoistedMemcpy(cfg) == 3U)
        } else {
            Assertions.assertEquals(true, getNumOfUnhoistedMemcpy(cfg) == 0U)
        }

        val memSummaries = MemorySummaries()
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val absValAtEntry = scalarAnalysis.getPost(cfg.getEntry().getLabel())
        val absValAtExit = scalarAnalysis.getPost(cfg.getExit().getLabel())
        println("Abstract value at the end of the entry block: $absValAtEntry")
        check(absValAtExit != null) {"test01 returns a null abstract value"}
        return absValAtExit
    }

    @Test
    fun test02() {
        /**
         * b0:
         *   if (...) b1 else b5
         * b5:
         *   if (...) b2 else b3
         * b1:
         *   *(r2+0)  := 1
         *   *(r2+8)  := 1
         *   *(r2+16) := 1
         *   *(r2+24) := 1
         *   goto b4
         * b2:
         *   *(r2+0)  := 2
         *   *(r2+8)  := 2
         *   *(r2+16) := 2
         *   *(r2+24) := 2
         *   goto b4
         * b3:
         *   *(r2+0)  := 3
         *   *(r2+8)  := 3
         *   *(r2+16) := 3
         *   *(r2+24) := 3
         *   goto b4
         * b4:
         *   memcpy(r1,r2,16)
         *   memcpy(r1,r2,32)
         */
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test2")
        val l0 = Label.Address(1)
        val l1 = Label.Address(2)
        val l2 = Label.Address(3)
        val l3 = Label.Address(4)
        val l4 = Label.Address(5)
        val l5 = Label.Address(6)
        val b0 = cfg.getOrInsertBlock(l0)
        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)
        val b5 = cfg.getOrInsertBlock(l5)
        cfg.setEntry(b0)
        b0.addSucc(b1)
        b0.addSucc(b5)
        b5.addSucc(b2)
        b5.addSucc(b3)
        b1.addSucc(b4)
        b2.addSucc(b4)
        b3.addSucc(b4)

        b0.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b0.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b0.add(SbfInstruction.Havoc(r3))
        b0.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r3, Value.Imm(0UL)), l1, l5))

        b5.add(SbfInstruction.Havoc(r3))
        b5.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r3, Value.Imm(0UL)), l2, l3))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b2.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b2.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(300UL), true))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b3.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b3.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(400UL), true))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b4.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(16UL), true))
        b4.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCPY))
        b4.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b4.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCPY))
        b4.add(SbfInstruction.Exit())
        cfg.normalize()
        println("$cfg")
        cfg.verify(true)
        unhoistMemFunctions(cfg, unhoistOpts)
        cfg.simplify(globals)
        println("After unhoisting memcpy instructions: $cfg")
        Assertions.assertEquals(true, getNumOfUnhoistedMemcpy(cfg) == 6U)
    }


    @Test
    fun test03() {
        /**
         * entry:
         *   r4 := 0
         *   goto loop_header
         *
         * loop_header:
         *   if (r4 < 5) loop_body else loop_exit
         * loop_body:
         *   if (*) goto b2 else goto b3
         * b2:
         *   *(r2+0)  := 2
         *   *(r2+8)  := 2
         *   *(r2+16) := 2
         *   *(r2+24) := 2
         *   goto b4
         * b3:
         *   *(r2+0)  := 3
         *   *(r2+8)  := 3
         *   *(r2+16) := 3
         *   *(r2+24) := 3
         *   goto b4
         * b4:
         *   memcpy(r1,r2,32)
         *   r4++
         *   goto loop_header
         * loop_exit:
         *   exit
         *
         */
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val r5 = Value.Reg(SbfRegister.R5_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test3")

        val entry = Label.Address(1)
        val loopHeader = Label.Address(2)
        val loopBody = Label.Address(3)
        val loopExit = Label.Address(4)
        val l2 = Label.Address(5)
        val l3 = Label.Address(6)
        val l4 = Label.Address(7)

        val entryB = cfg.getOrInsertBlock(entry)
        val loopHeaderB = cfg.getOrInsertBlock(loopHeader)
        val loopBodyB = cfg.getOrInsertBlock(loopBody)
        val loopExitB = cfg.getOrInsertBlock(loopExit)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)

        cfg.setEntry(entryB)
        entryB.addSucc(loopHeaderB)
        loopHeaderB.addSucc(loopBodyB)
        loopHeaderB.addSucc(loopExitB)
        loopBodyB.addSucc(b2)
        loopBodyB.addSucc(b3)
        b2.addSucc(b4)
        b3.addSucc(b4)
        b4.addSucc(loopHeaderB)

        entryB.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        entryB.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        entryB.add(SbfInstruction.Bin(BinOp.MOV, r4, Value.Imm(0UL), true))
        entryB.add(SbfInstruction.Jump.UnconditionalJump(loopHeader))

        loopHeaderB.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.LT, r4, Value.Imm(5UL)), loopBody, loopExit))

        loopBodyB.add(SbfInstruction.Havoc(r5))
        loopBodyB.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r5, Value.Imm(0UL)), l2, l3))

        b2.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b2.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(300UL), true))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(2UL), false))
        b2.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b3.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b3.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(400UL), true))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(3UL), false))
        b3.add(SbfInstruction.Jump.UnconditionalJump(l4))


        b4.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b4.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCPY))
        b4.add(SbfInstruction.Bin(BinOp.ADD, r4, Value.Imm(1UL), true))
        b4.add(SbfInstruction.Jump.UnconditionalJump(loopHeader))

        loopExitB.add(SbfInstruction.Exit())
        cfg.normalize()
        println("$cfg")
        cfg.verify(true)
        unhoistMemFunctions(cfg, unhoistOpts)
        cfg.simplify(globals)
        println("After unhoisting memcpy instructions: $cfg")
        Assertions.assertEquals(true, getNumOfUnhoistedMemcpy(cfg) == 2U)
    }

    @Test
    fun test04() {
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(0) {
                br(CondOp.EQ(r3, 0x0), 1, 2)
            }
            bb(1) {
                r1 = r10[-62]
                goto(3)
            }
            bb(2) {
                r1 = r10[-56]
                goto(3)
            }
            bb(3) {
                r2 = r10 [-224]
                r3 = 32
                "sol_memcpy_"()
                exit()
            }
        }
        cfg.normalize()
        println( "Before $cfg" )
        cfg.verify(true)
        unhoistMemFunctions(cfg, unhoistOpts)
        cfg.simplify(globals)
        println("After $cfg")
        Assertions.assertEquals(true, getNumOfUnhoistedMemcpy(cfg) == 2U)
    }

    @Test
    fun test05() {
        val cfg = SbfTestDSL.makeCFG("test5") {
            bb(0) {
                br(CondOp.EQ(r3, 0x0), 1, 2)
            }
            bb(1) {
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                r1 = r10[-55]
                r2 = r10[-63]
                r3 = r10[-71]
                r4 = r10[-79]
                r8[24] = r1
                r8[16] = r2
                r8[8] = r3
                r8[0] = r4
                r1 = r10[-105]
                r2 = r10[-113]
                r3 = r10[-121]
                r4 = r10[-129]
                r7[40] = r1
                r7[32] = r2
                r7[24] = r3
                r7[16] = r4
                exit()
            }
        }

        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        val memSummaries = MemorySummaries()
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteStoresToMemcpy(cfg, scalarAnalysis)
        }
        removeUselessDefinitions(cfg)
        println("After promoting load and stores to memcpy\n$cfg")
        unhoistPromotedMemcpy(cfg)
        cfg.simplify(globals)
        println( "After unhoisting promoted memcpy\n$cfg")
        Assertions.assertEquals(true,  getNumOfUnhoistedMemcpy(cfg) == 4U)
    }

}
