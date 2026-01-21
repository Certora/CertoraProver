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

import datastructures.stdcollections.*
import sbf.callgraph.SolanaFunction
import sbf.cfg.*
import sbf.disassembler.*
import sbf.testing.SbfTestDSL
import log.*
import org.junit.jupiter.api.*

/**
 *  These tests don't check for anything specifically but they shoudn't at least throw errors.
 **/
class SbfCFGTest  {
    val inputDir = "src/test/resources/sbf"

    @Suppress("ForbiddenComment")
    /** We only test that a ELF file can be disassembled without a runtime error **/
    private fun translateElfToCFG(baseName: String, ext: String) {
        val bytecode = ElfDisassembler("$inputDir/$baseName$ext").read(setOf("entrypoint"))
        bytecodeToSbfProgram(bytecode)

        // TODO: we don't actually generate the CFG because it requires rustfilt to demangle names
        //  and we haven't set rustfilt in CI
        //val sbfProgram = bytecodeToSbfProgram(bytecode, runtime)
        //sbfProgramToSbfCfgs(sbfProgram, runtime)
    }

    @Test
    fun test01() {
        translateElfToCFG("helloworld", ".o")
    }
    @Test
    fun test02() {
        translateElfToCFG("transfer-lamports", ".o")
    }
    @Test
    fun test03() {
        translateElfToCFG("transfer-lamports-with-spec.true", ".o")
    }
    @Test
    fun test04() {
        translateElfToCFG("custom-heap", ".o")
    }
    /*@Test
    fun test05() {
        translateElfToCFG("cpi", ".o")
    }*/
    @Test
    fun test06() {
        translateElfToCFG("transfer-lamports", ".so")
    }

    // Test normalization. CFG with two exit instructions in different blocks
    @Test
    fun test07() {
        /*
         1:
            r1 := havoc()
            if (r1 ugt 0) then goto 2 else goto 3
         3:
           r2 := 1
           exit
         2:
           r2 := 0
           exit

        is normalized to:

         ...
         3:
           r2 := 1
           goto 4
         4:
           exit
         2:
           r2 := 0
           goto 4
         */

        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        val b2 = cfg.getOrInsertBlock(Label.Address(2))
        val b3 = cfg.getOrInsertBlock(Label.Address(3))
        cfg.setEntry(b1)

        b1.addSucc(b2)
        b1.addSucc(b3)
        b1.add(SbfInstruction.Havoc(r1))
        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.GT, r1, Value.Imm(0UL)), b2.getLabel(), b3.getLabel()))
        b2.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(0UL), true))
        b2.add(SbfInstruction.Exit())
        b3.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(1UL), true))
        b3.add(SbfInstruction.Exit())

        println("$cfg")
        cfg.normalize()
        println("After normalize: $cfg")
        cfg.verify(true)
    }

    // Test normalization. CFG with two abort instructions in different blocks
    @Test
    fun test08() {
    /*
         1:
           r1 := havoc()
           if (r1 ugt 0) then goto 2 else goto 3
         3:
           r2 := 1
           call abort
         2:
           r2 := 0
           call abort

         is normalized to:
         ...
         3:
           r2 := 1
           call abort
           goto 4
         4:
           exit
         2:
           r2 := 0
           call abort
           goto 4

    */
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        val b2 = cfg.getOrInsertBlock(Label.Address(2))
        val b3 = cfg.getOrInsertBlock(Label.Address(3))
        cfg.setEntry(b1)

        b1.addSucc(b2)
        b1.addSucc(b3)
        b1.add(SbfInstruction.Havoc(r1))
        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.GT, r1, Value.Imm(0UL)), b2.getLabel(), b3.getLabel()))
        b2.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(0UL), true))
        b2.add(SolanaFunction.toCallInst(SolanaFunction.ABORT))
        b3.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(1UL), true))
        b3.add(SolanaFunction.toCallInst(SolanaFunction.ABORT))

        println("$cfg")
        //cfg.verify(true)
        cfg.normalize()
        println("After normalize: $cfg")
        cfg.verify(true)
    }

    // Test normalization. CFG with one exit and one abort in different blocks
    @Test
    fun test9() {
        /*
             1:
               r1 := havoc()
               if (r1 ugt 0) then goto 2 else goto 3
             3:
               r2 := 1
               call abort
             2:
               r2 := 0
               call exit

             is normalized to:
             ...
             3:
               r2 := 1
               call abort
               goto 4
             4:
               exit
             2:
               r2 := 0
               goto 4

        */
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        val b2 = cfg.getOrInsertBlock(Label.Address(2))
        val b3 = cfg.getOrInsertBlock(Label.Address(3))
        cfg.setEntry(b1)

        b1.addSucc(b2)
        b1.addSucc(b3)
        b1.add(SbfInstruction.Havoc(r1))
        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.GT, r1, Value.Imm(0UL)), b2.getLabel(), b3.getLabel()))
        b2.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(0UL), true))
        b2.add(SbfInstruction.Exit())
        b3.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(1UL), true))
        b3.add(SolanaFunction.toCallInst(SolanaFunction.ABORT))

        println("$cfg")
        //cfg.verify(true)
        cfg.normalize()
        println("After normalize: $cfg")
        cfg.verify(true)
    }

    // Test normalization. CFG without exit or abort instructions
    // Having self-loops is the only way to have sink blocks without exit or abort.
    @Test
    fun test10() {
        /*
             1:
               r1 := havoc()
               if (r1 ugt 0) then goto 2 else goto 3
             3:
               r2 := 1
               goto 3
             2:
               r2 := 0
               goto 2
             is normalized to:
             ...
             3:
               r2 := 1
               goto 3
             4: // not printed because it's not reachable from entry
               exit
             2:
               r2 := 0
               goto 2
        */
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        val b2 = cfg.getOrInsertBlock(Label.Address(2))
        val b3 = cfg.getOrInsertBlock(Label.Address(3))
        cfg.setEntry(b1)

        b1.addSucc(b2)
        b1.addSucc(b3)
        b2.addSucc(b2)
        b3.addSucc(b3)
        b1.add(SbfInstruction.Havoc(r1))
        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.GT, r1, Value.Imm(0UL)), b2.getLabel(), b3.getLabel()))
        b2.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(0UL), true))
        b2.add(SbfInstruction.Jump.UnconditionalJump(b2.getLabel()))
        b3.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(1UL), true))
        b3.add(SbfInstruction.Jump.UnconditionalJump(b3.getLabel()))

        println("$cfg")
        //cfg.verify(true)
        cfg.normalize()
        println("After normalize: $cfg")
        cfg.verify(true)
        Assertions.assertEquals(true, cfg.hasExit())
        sbfLogger.info{"Exit block ${cfg.getExit()}"}
    }

    @Test
    fun test11() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1[0] = 5
                r3 = r2
                BinOp.ADD(r3, 4)
                r4 = r3
                r5[0] = r4
                goto(1)

            }
            bb(1) {
                exit()
            }
        }
        val bb = cfg.getMutableBlock(Label.Address(0))
        check(bb!=null) {"test11 failed"}

        val oldInst1 = bb.getLocatedInstructions()[0]
        val oldInst2 = bb.getLocatedInstructions()[1]
        val oldInst3 = bb.getLocatedInstructions()[2]
        val oldInst4 = bb.getLocatedInstructions()[3]
        val oldInst5 = bb.getLocatedInstructions()[4]
        val oldInst6 = bb.getLocatedInstructions()[5]

        val newInst1 =  SbfInstruction.Bin(BinOp.MOV, Value.Reg(SbfRegister.R9), Value.Imm(1UL), true)
        val newInst2 =  SbfInstruction.Bin(BinOp.MOV, Value.Reg(SbfRegister.R9), Value.Imm(2UL), true)
        val newInst3 =  SbfInstruction.Bin(BinOp.MOV, Value.Reg(SbfRegister.R9), Value.Imm(3UL), true)
        val newInst4 =  SbfInstruction.Bin(BinOp.MOV, Value.Reg(SbfRegister.R9), Value.Imm(4UL), true)
        val newInst5 =  SbfInstruction.Bin(BinOp.MOV, Value.Reg(SbfRegister.R9), Value.Imm(5UL), true)

        val remap = mutableMapOf<LocatedSbfInstruction, List<SbfInstruction>>()

        remap[oldInst3] = listOf(newInst1, newInst2)
        remap[oldInst5] = listOf(newInst3, newInst4)
        remap[oldInst6] = listOf(newInst5, oldInst6.inst)
        remap[oldInst4] = emptyList()

        println("BEFORE == \n$bb")
        bb.replaceInstructions(remap)
        println("AFTER == \n$bb")
        cfg.verify(true)

        Assertions.assertEquals(
            bb.getInstructions(),
            bb.getLocatedInstructions().map { it.inst },
        )

        Assertions.assertEquals(
            bb.getInstructions(),
            listOf(
                oldInst1.inst,
                oldInst2.inst,
                newInst1,
                newInst2,
                newInst3,
                newInst4,
                newInst5,
                oldInst6.inst,
            ),
        )
    }
}
