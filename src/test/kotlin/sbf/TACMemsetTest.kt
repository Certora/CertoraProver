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
import datastructures.stdcollections.*
import sbf.callgraph.SolanaFunction
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import sbf.testing.SbfTestDSL
import log.*
import org.junit.jupiter.api.*
import report.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(1)
class TACMemsetTest {
    private var outContent = ByteArrayOutputStream()
    private var errContent = ByteArrayOutputStream()

    private val originalOut = System.out
    private val originalErr = System.err

    // system properties have to be set before we load the logger
    @BeforeAll
    fun setupAll() {
        System.setProperty(LoggerTypes.SBF.toLevelProp(), "info")
    }

    // we must reset our stream so that we could match on what we have in the current test
    @BeforeEach
    fun setup() {
        outContent = ByteArrayOutputStream()
        errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent, true)) // for 'always' logs
        System.setErr(PrintStream(errContent, true)) // loggers go to stderr
    }

    private fun debug() {
        originalOut.println(outContent.toString())
        originalErr.println(errContent.toString())
    }

    // close and reset
    @AfterEach
    fun teardown() {
        debug()
        System.setOut(originalOut)
        System.setErr(originalErr)
        outContent.close()
        errContent.close()
    }

    private fun checkVerify(cfg: SbfCFG, expectedResult: Boolean) {
        val tacProg = toTAC(cfg)
        sbfLogger.warn { dumpTAC(tacProg) }
        Assertions.assertEquals(expectedResult, verify(tacProg))
    }

    @Test
    fun test01() {
        // zero memset on stack
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r2 = 0
                r3 = 32
                "sol_memset_"()
                r3 = r1[0]
                assert(CondOp.EQ(r3, 0))
                r3 = r1[8]
                assert(CondOp.EQ(r3, 0))
                r3 = r1[16]
                assert(CondOp.EQ(r3, 0))
                r3 = r1[24]
                assert(CondOp.EQ(r3, 0))
                exit()
            }
        }
        cfg.normalize()
        cfg.verify(true)
        sbfLogger.warn { "$cfg" }
        checkVerify(cfg, true)
    }

    @Test
    fun test02() {
        // zero memset on heap
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(0) {
                r1 = 32
                "__rust_alloc"()
                r1 = r0
                r2 = 0
                r3 = 32
                "sol_memset_"()
                r3 = r1[0]
                assert(CondOp.EQ(r3, 0))
                r3 = r1[8]
                assert(CondOp.EQ(r3, 0))
                r3 = r1[16]
                assert(CondOp.EQ(r3, 0))
                r3 = r1[24]
                assert(CondOp.EQ(r3, 0))
                exit()
            }
        }
        cfg.normalize()
        cfg.verify(true)
        sbfLogger.warn {"$cfg"}
        ConfigScope(SolanaConfig.AddMemLayoutAssumptions, false).use {
            ConfigScope(SolanaConfig.TACMaxUnfoldedMemset, 8).use {
                checkVerify(cfg, expectedResult = true)
            }
            ConfigScope(SolanaConfig.TACMaxUnfoldedMemset, 64).use {
                checkVerify(cfg, expectedResult = true)
            }
        }
    }

    @Test
    fun test03() {
        // non-zero memset on heap
        val cfg = SbfTestDSL.makeCFG("test3") {
            bb(0) {
                r1 = 32
                "__rust_alloc"()
                r1 = r0
                r2 = 5
                r3 = 32
                "sol_memset_"()
                r3 = r1[0]
                assert(CondOp.EQ(r3, 5))
                r3 = r1[8]
                assert(CondOp.EQ(r3, 5))
                r3 = r1[16]
                assert(CondOp.EQ(r3, 5))
                r3 = r1[24]
                assert(CondOp.EQ(r3, 5))
                exit()
            }
        }
        cfg.normalize()
        cfg.verify(true)
        sbfLogger.warn {"$cfg"}
        ConfigScope(SolanaConfig.AddMemLayoutAssumptions, false).use {
            ConfigScope(SolanaConfig.TACMaxUnfoldedMemset, 8).use {
                checkVerify(cfg, expectedResult = false)
            }
            ConfigScope(SolanaConfig.TACMaxUnfoldedMemset, 64).use {
                checkVerify(cfg, expectedResult = false)
            }
        }
    }

    @Test
    fun test04() {
        // non-zero memset on heap
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(0) {
                r1 = 20
                "__rust_alloc"()
                r4 = r0
                r1 = r0
                BinOp.ADD(r1, 4)
                r2 = 2
                r3 = 16
                "sol_memset_"()
                r3 = r4[0]
                assert(CondOp.EQ(r3, 2))
                r3 = r4[8]
                assert(CondOp.EQ(r3, 2))
                exit()
            }
        }
        cfg.normalize()
        cfg.verify(true)
        sbfLogger.warn {"$cfg"}
        ConfigScope(SolanaConfig.AddMemLayoutAssumptions, false).use {
            ConfigScope(SolanaConfig.TACMaxUnfoldedMemset, 8).use {
                checkVerify(cfg, expectedResult = false)
            }
            ConfigScope(SolanaConfig.TACMaxUnfoldedMemset, 32).use {
                checkVerify(cfg, expectedResult = false)
            }
        }
    }

    @Test
    fun test05() {
        // non-zero memset on heap
        /*
           r1 = 8 //  allocate four u16   (4 * 2)
           "__rust_alloc"()
           r4 = r0
           r2 = 255 // each byte is set to 255 (all ones)
           r3 = 8
           "sol_memset_"()
           r3 = (*u16) r4  // we read two bytes
           assert(CondOp.EQ(r3, 255))  // the right value should be 65535 (all ones of u16)
           exit()
        }*/


        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val cfg = MutableSbfCFG("test5")

        val l0 = Label.Address(1)
        val b0 = cfg.getOrInsertBlock(l0)
        cfg.setEntry(b0)

        b0.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(8UL), true))
        b0.add(SbfInstruction.Call(name = "__rust_alloc"))
        b0.add(SbfInstruction.Bin(BinOp.MOV, r4, r0, true))
        b0.add(SbfInstruction.Bin(BinOp.MOV, r1, r0, true))

        b0.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(255UL), true))
        b0.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(8UL), true))
        b0.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMSET))
        b0.add(SbfInstruction.Mem(Deref(2, r4, 0), r3, true))
        b0.add(SbfInstruction.Assert(Condition(CondOp.EQ, r3, Value.Imm(255UL))))
        b0.add(SbfInstruction.Exit())

        cfg.normalize()
        cfg.verify(true)
        sbfLogger.warn {"$cfg"}
        ConfigScope(SolanaConfig.AddMemLayoutAssumptions, false).use {
            ConfigScope(SolanaConfig.TACMaxUnfoldedMemset, 1).use {
                checkVerify(cfg, expectedResult = false)
            }
            ConfigScope(SolanaConfig.TACMaxUnfoldedMemset, 16).use {
                checkVerify(cfg, expectedResult = false)
            }
        }
    }
}
