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
import sbf.tac.TACTranslationError
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.callgraph.CVTCore

class TACAllocatorsTest {

    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                r1 = 32UL
                "__rust_alloc"()
                r2 = r0
                assert(CondOp.EQ(r2, SBF_HEAP_START))
                r1 = 64UL
                "__rust_alloc"()
                r2 = r0
                assert(CondOp.EQ(r2, SBF_HEAP_START + 32))
                exit()
            }

        }

        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test2() {
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                "foo"()
                r2 = r1[0]
                r3 = r1[8]
                assert(CondOp.EQ(r2, SBF_EXTERNAL_START))
                assert(CondOp.EQ(r3, SBF_EXTERNAL_START + 1024))
                exit()
            }

        }

        println("$cfg")
        val tacProg = toTAC(cfg,
            kotlin.collections.listOf("#[type((*i64)(r1+0):ptr_external(1024))]",
                                      "#[type((*i64)(r1+8):ptr_external(1024))]",
                                      "^foo$"))
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test3() {
        val cfg = SbfTestDSL.makeCFG("test3") {
            bb(0) {
                r1 = r10[-200]
                "foo"()
                r2 = r1[0]
                r3 = r1[8]
                assert(CondOp.EQ(r2, SBF_EXTERNAL_START))
                assert(CondOp.EQ(r3, SBF_EXTERNAL_START + 1024))
                exit()
            }
        }

        println("$cfg")
        val tacProg = toTAC(cfg,
            kotlin.collections.listOf("#[type((*i64)(r1+0):ptr_external(1024))]",
                                      "#[type((*i64)(r1+8):ptr_external(1024))]",
                                      "^foo$"))
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test4() {
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(0) {
                r1 = 1024 * 1024
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                r2 = r0
                assert(CondOp.EQ(r2, SBF_INPUT_START + (5 * SOLANA_ACCOUNT_SIZE)))
                exit()
            }

        }

        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test5() {
        val cfg = SbfTestDSL.makeCFG("test5") {
            bb(0) {
                r1 = 100 * 1024 * 1024  // too large allocation (max 10 * 1024 * 1024)
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                r2 = r0
                exit()
            }

        }
        println("$cfg")
        var failed = false
        try {
            toTAC(cfg)
        } catch (e: TACTranslationError) {
            println("$e")
            failed = true
        }
        Assertions.assertEquals(true, failed)
    }

    @Test
    fun test6() {
        val r1 = Value.Reg(SbfRegister.R1)
        val cfg = MutableSbfCFG("test6")
        val l0 = Label.Address(1)
        val b0 = cfg.getOrInsertBlock(l0)
        cfg.setEntry(b0)
        cfg.setExit(b0)
        b0.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(1024UL * 1024UL), true))
        repeat (MAX_SOLANA_ACCOUNTS + 1) {
            b0.add(SbfInstruction.Call(name = CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name))
        }
        b0.add(SbfInstruction.Exit())
        println("$cfg")
        var failed = false
        try {
            toTAC(cfg)
        } catch (e: TACTranslationError) {
            println("$e")
            failed = true
        }
        Assertions.assertEquals(true, failed)
    }

    @Test
    fun test7() {
        val cfg = SbfTestDSL.makeCFG("test7") {
            bb(0) {
                r1 = 5 * 1024 * 1024
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                r2 = r0
                r1 = r2
                r2 = 48
                r3 = 32
                CVTCore.ALLOC_SLICE.function.name()
                assert(CondOp.EQ(r0, SBF_INPUT_START + SOLANA_ACCOUNT_SIZE + 48))
                exit()
            }

        }

        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test8() {
        val cfg = SbfTestDSL.makeCFG("test8") {
            bb(0) {
                r1 = 5 * 1024 * 1024
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                r2 = r0
                r1 = r2
                r2 = -8
                r3 = 32
                CVTCore.ALLOC_SLICE.function.name()
                assert(CondOp.EQ(r0, SBF_INPUT_START + SOLANA_ACCOUNT_SIZE + 48))
                exit()
            }

        }

        println("$cfg")
        var failed = false
        try {
            toTAC(cfg)
        } catch (e: TACTranslationError) {
            println("$e")
            failed = true
        }
        Assertions.assertEquals(true, failed)
    }

    @Test
    fun test9() {
        val cfg = SbfTestDSL.makeCFG("test9") {
            bb(0) {
                r1 = 5 * 1024 * 1024
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE.function.name()
                r2 = r0
                r1 = r2
                r2 = 48
                r3 = 32
                CVTCore.ALLOC_SLICE.function.name()
                assert(CondOp.EQ(r0, SBF_INPUT_START + SOLANA_ACCOUNT_SIZE + 48))
                exit()
            }

        }

        println("$cfg")
        ConfigScope(SolanaConfig.UseTACMathInt, true).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }

    }
}
