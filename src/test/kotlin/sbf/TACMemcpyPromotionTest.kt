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
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import org.junit.jupiter.api.*
import sbf.callgraph.SolanaFunction
import sbf.disassembler.GlobalVariables
import sbf.domains.MemorySummaries
import sbf.support.UnknownStackContentError

private val globals = GlobalVariables(DefaultElfFileView)
private val memSummaries = MemorySummaries()

class TACMemcpyPromotionTest {

    private fun SbfCFG.hasSolanaFunction(fn: SolanaFunction): Boolean {
        for (b in this.getBlocks().values) {
            for (inst in b.getInstructions().filterIsInstance<SbfInstruction.Call>()) {
                if (SolanaFunction.from(inst.name) == fn) {
                    return true
                }
            }
        }
        return false
    }

    private fun SbfCFG.hasMemcpyZExt() = hasSolanaFunction(SolanaFunction.SOL_MEMCPY_ZEXT)
    private fun SbfCFG.hasMemcpyTrunc() = hasSolanaFunction(SolanaFunction.SOL_MEMCPY_TRUNC)

    @Test
    fun `widening + narrowing with memcpy promotion`() {
        val cfg = `widening + narrowing`()
        promoteMemcpy(cfg, globals, memSummaries)
        removeUselessDefinitions(cfg)
        Assertions.assertEquals(true, cfg.hasMemcpyZExt())
        Assertions.assertEquals(true, cfg.hasMemcpyTrunc())
        println("After memcpy promotion: $cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun `widening + narrowing without memcpy promotion`() {
        val cfg = `widening + narrowing`()
        Assertions.assertEquals(false, cfg.hasMemcpyZExt())
        Assertions.assertEquals(false, cfg.hasMemcpyTrunc())
        expectException<UnknownStackContentError> {
            toTAC(cfg)
        }
    }

    /**
     ```
      *(u8 *) (r10 -183):sp(3913) := 5
     r1 := *(u16 *) (r10 -184):sp(3912)
      *(u64 *) (r10 -1408):sp(2688) := r1   // widening store
     r2 := *(u64 *) (r10 -1408):sp(2688)
      *(u16 *) (r10 -240):sp(3856) := r2    // narrowing store
     r1 := *(u8 *) (r10 - 239):sp(3857)
     assert(r1 == 5)
    ```
     **/
    private fun `widening + narrowing`(): MutableSbfCFG {
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        // *(u8 *) (r10-183):sp(3913) := 5
        b1.add(SbfInstruction.Mem(Deref(1, r10, -183), Value.Imm(5UL), false))
        //  r1 := *(u16 *) (r10-184):sp(3912)
        b1.add(SbfInstruction.Mem(Deref(2, r10, -184), r1, true))
        // *(u64 *) (r10-1408):sp(2688) := r1
        b1.add(SbfInstruction.Mem(Deref(8, r10, -1408), r1, false))
        //  r2 := *(u64 *) (r10-1408):sp(2688)
        b1.add(SbfInstruction.Mem(Deref(8, r10, -1408), r2, true))
        // *(u16 *) (r10-240):sp(3856) := r2
        b1.add(SbfInstruction.Mem(Deref(2, r10, -240), r2, false))
        // r1 := *(u8 *) (r10-239):sp(3857)
        b1.add(SbfInstruction.Mem(Deref(1, r10, -239), r1, true))
        // assert(r1 == 5)
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r1, Value.Imm(5UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        return cfg
    }

    @Test
    fun `widening store with memcpy promotion`() {
        val cfg = `widening store`()
        promoteMemcpy(cfg, globals, memSummaries)
        Assertions.assertEquals(true, cfg.hasMemcpyZExt())
        removeUselessDefinitions(cfg)
        println("After memcpy promotion: $cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun `widening store without memcpy promotion`() {
        val cfg = `widening store`()
        Assertions.assertEquals(false, cfg.hasMemcpyZExt())
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /**
     * ```
     *  *(u8 *) (r10-300) = 0
     *  r1 := *(u8 *) (r10-300)
     *  (u64 *) (r10-1504):sp(2592) := r1 // widening store
     *  r2 := *(u64 *) (r10-1504):sp(2592)
     *  assert(r2 == 0)
     * ```
     */
    private fun `widening store`(): MutableSbfCFG {
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Mem(Deref(1, r10, -300), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(1, r10, -300), r1, true))
        // *(u64 *) (r10-1504):sp(2688) := r1
        b1.add(SbfInstruction.Mem(Deref(8, r10, -1504), r1, false))
        // r2 := *(u64 *) (r10-1504):sp(2688)
        b1.add(SbfInstruction.Mem(Deref(8, r10, -1504), r2, true))
        // assert(r2 == 0)
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r2, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        return cfg
    }

    @Test
    fun `narrowing store with memcpy promotion`() {
        val cfg = `narrowing store`()
        promoteMemcpy(cfg, globals, memSummaries)
        removeUselessDefinitions(cfg)
        Assertions.assertEquals(true, cfg.hasMemcpyTrunc())
        println("After memcpy promotion: $cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun `narrowing store without memcpy promotion`() {
        val cfg = `narrowing store`()
        Assertions.assertEquals(false, cfg.hasMemcpyTrunc())
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /**
     * call CVT_nondet_u16
     * assume(r0 == 0)
     * *(u64 *) (r10 + -24):sp(4076) := r0
     *
     * r1 = *(u64 *) (r10-24):sp(4076)
     * *(u16 *) (r10-524):sp(3572) = r1 // narrowing store
     *
     * r2 = *(u16 *) (r10 - 524):sp(3572)
     * assert(r2 == 0)
     */
    private fun `narrowing store`(): MutableSbfCFG {
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Call("CVT_nondet_u16"))
        b1.add(SbfInstruction.Assume(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Mem(Deref(8, r10, -24), r0, false))
        b1.add(SbfInstruction.Mem(Deref(8, r10, -24), r1, true))
        b1.add(SbfInstruction.Mem(Deref(2, r10, -524), r1, false))
        b1.add(SbfInstruction.Mem(Deref(2, r10, -524), r2, true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r2, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        return cfg
    }

    /**
     * ```
     * rust_alloc(72)
     * *(u8 *) (r0 + 56) = 1
     * memcpy_zero(r10-136:sp(3960), r0+56, 1, 8)
     * r2 = *(u8 *) (r0 + 56) // this load cannot be removed by `removeUselessDefinitions`
     * assert(r2 == 1)
     * ```
     * There was a bug that havoc r0 with memset at the TAC level.
     * We keep the test even if it's not too relevant anymore after the bug was fixed.
     */
    @Test
    fun `example from manifest with memcpy promotion`() {
        val cfg = `example from manifest`()
        promoteMemcpy(cfg, globals, memSummaries)
        removeUselessDefinitions(cfg)
        Assertions.assertEquals(true, cfg.hasMemcpyZExt())
        println("After memcpy promotion: $cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun `example from manifest without memcpy promotion`() {
        val cfg = `example from manifest`()
        Assertions.assertEquals(false, cfg.hasMemcpyZExt())
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }


    /**
     * ```
     * rust_alloc(72)
     * *(u8 *) (r0 + 56) = 1
     * r2 = *(u8 *) (r0 + 56)
     * *(u64 *) (r10 -136):sp(3960) := r2
     * assert(r2 == 1)
     * ```
     */
    private fun `example from manifest`(): MutableSbfCFG {
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(72UL), true))
        b1.add(SbfInstruction.Call(name = "__rust_alloc"))
        b1.add(SbfInstruction.Mem(Deref(1, r0, 56), Value.Imm(1UL), false))
        b1.add(SbfInstruction.Mem(Deref(1, r0, 56), r2, true))
        b1.add(SbfInstruction.Mem(Deref(8, r10, -136), r2, false))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r2, Value.Imm(1UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        return cfg
    }
}
