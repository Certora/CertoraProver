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
import sbf.analysis.NPAnalysis
import sbf.cfg.*
import sbf.disassembler.Label
import sbf.disassembler.GlobalVariables
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import sbf.analysis.AnalysisRegisterTypes
import sbf.callgraph.MutableSbfCallGraph
import sbf.callgraph.SolanaFunction
import sbf.disassembler.SbfRegister
import sbf.domains.*
import sbf.slicer.sliceAssertions

private val sbfTypesFac = ConstantSbfTypeFactory()
private val top = NPDomain.mkTrue<ScalarDomain<Constant, Constant>, Constant, Constant>(sbfTypesFac)
private val globals = GlobalVariables(DefaultElfFileView)
private val memSummaries = MemorySummaries()

class NPDomainTest {

    @Test
    fun test01() {
        /*
             *(r10 - 104) := 0
             r3:= *(r10 - 104)
             r2 := r3
             r1 := r2
             assume(r1 == 5)
         */
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r10[-104] = 0
                r3 = r10[-104]
                r2 = r3
                r1 = r2
                assume(CondOp.EQ(r1, 5))
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(0))
        check(b != null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("absVal=$absVal\n$b\n newAbsVal=$newAbsVal")
        Assertions.assertEquals(true, newAbsVal.isBottom())
    }

    @Test
    fun test02() {
        /*
             r4 : = 1
             *(r10 - 104) := 1
             r3:= *(r10 - 104)
             r2 := r3
             r2 -= r4
             r1 := r2
             r1 += 1
             assume(r1 == 0)
         */

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r4 = 1
                r10[-104] = 1
                r3 = r10[-104]
                r2 = r3
                BinOp.SUB(r2, 1)
                r1 = r2
                BinOp.ADD(r1, 1)
                assume(CondOp.EQ(r1, 0))
            }
        }


        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)
        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(0))
        check(b != null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("absVal=$absVal\n$b\n newAbsVal=$newAbsVal")
        Assertions.assertEquals(true, newAbsVal.isBottom())
    }

    @Test
    fun test03() {
        /*
             r3  := r10 - 96
             *(r10 - 104) := r3
             r2  := *(r10 - 104)
             *r2 := 0
             r1  := *(r10 - 96)
             assume(r1 > 5)
         */
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r3 = r10
                BinOp.SUB(r3, 96)
                r10[-104] = r3
                r2 = r10[-104]
                r2[0] = 0
                r1 = r10[-96]
                assume(CondOp.GT(r1, 5))
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(0))
        check(b != null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("absVal=$absVal\n$b\nnewAbsVal=$newAbsVal")
        Assertions.assertEquals(true, newAbsVal.isBottom())
    }

    @Test
    fun test04() {
        /*
             assume(r4 != 0)
             assume(r4 == 0)
         */
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                assume(CondOp.NE(r4, 0))
                assume(CondOp.EQ(r4, 0))
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(0))
        check(b != null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("absVal=$absVal\n$b\nnewAbsVal=$newAbsVal")
        Assertions.assertEquals(true, newAbsVal.isBottom())
    }

    @Test
    fun test05() {
        /*
             assume(r4 == 0)
             assume(r4 != 0)
         */
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                assume(CondOp.EQ(r4, 0))
                assume(CondOp.NE(r4, 0))
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(0))
        check(b != null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("absVal=$absVal\n$b\nnewAbsVal=$newAbsVal")
        Assertions.assertEquals(true, newAbsVal.isBottom())
    }

    @Test
    fun test06() {
        /*
             assume(r4 == 0)
             assume(r4 > 5)
         */
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                assume(CondOp.EQ(r4, 0))
                assume(CondOp.GT(r4, 5))
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(0))
        check(b != null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("absVal=$absVal\n$b\nnewAbsVal=$newAbsVal")
        Assertions.assertEquals(true, newAbsVal.isBottom())
    }

    @Test
    fun test07() {
        /*
             assume(r4 != 7)
             assume(r4 < 8)
             assume(r4 > 6)
         */
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                assume(CondOp.NE(r4, 7))
                assume(CondOp.LT(r4, 8))
                assume(CondOp.GT(r4, 6))
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(0))
        check(b != null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("absVal=$absVal\n$b\nnewAbsVal=$newAbsVal")
        Assertions.assertEquals(true, newAbsVal.isBottom())
    }

    @Test
    fun test8() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 96)
                r2 = r10
                BinOp.SUB(r2, 296)
                br(CondOp.EQ(r3, 0x1), 1, 2)
            }
            bb(1) {
                r4 = r2[16]
                assume(CondOp.EQ(r4, 11))
                goto(3)
            }
            bb(2) {
                r4 = r2[16]
                assume(CondOp.EQ(r4, 7))
                goto(3)
            }
            bb(3) {
                r2[0] = 5
                r3 = 24
                "sol_memcpy_"()
                r4 = r1[16]
                assume(CondOp.EQ(r4, 7))
                r4 = r2[8]
                assume(CondOp.EQ(r4, 10))
                assert(CondOp.EQ(r4, 10)) // added assert so that NPAnalysis runs
                exit()
            }
        }

        val np = NPAnalysis(cfg, globals, memSummaries)
        val absValAt1 = np.getPreconditionsAtEntry(Label.Address(1))
        check(absValAt1 != null) { "No preconditions for label 1" }
        val absValAt2 = np.getPreconditionsAtEntry(Label.Address(2))
        check(absValAt2 != null) { "No preconditions for label 2" }


        println("absVal at 1=$absValAt1\nAbsVal at 2=$absValAt2")
        Assertions.assertEquals(true, absValAt1.isBottom())
        Assertions.assertEquals(false, absValAt2.isBottom())
    }

    @Test
    fun test9() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r2 = r10[-96]
                br(CondOp.NE(r2, 0x1), 1, 2)
            }
            bb(1) {
                assume(CondOp.NE(r2, 0x1))
                r3 = r10[-96]
                assume(CondOp.EQ(r3, 0x1))
                goto(3)
            }
            bb(2) {
                assume(CondOp.EQ(r2, 0x1))
                goto(3)
            }
            bb(3) {
                assert(CondOp.EQ(r4, 0)) // added assert so that NPAnalysis runs
                exit()
            }
        }

        val np = NPAnalysis(cfg, globals, memSummaries)
        val absValAt1 = np.getPreconditionsAtEntry(Label.Address(1))
        check(absValAt1 != null) { "No preconditions for label 1" }
        println("$cfg")
        println("Preconditions at entry of 1=$absValAt1\n")

        Assertions.assertEquals(true, absValAt1.isBottom())
    }

    @Test
    fun test10() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 1
                br(CondOp.NE(r9, 0x0), 1, 2)
            }
            bb(1) {
                assume(CondOp.NE(r9, 0x0))
                goto(3)
            }
            bb(2) {
                assume(CondOp.EQ(r9, 0x0))
                r1 = 0
                goto(3)
            }
            bb(3) {
                // If we propagate backwards r9 != 0 then this assertion will be always true, which is unsound
                assert(CondOp.NE(r1, 0))
                assume(CondOp.NE(r9, 0))
                BinOp.MUL(r8, r7)
                BinOp.DIV(r8, r9)
                assert(CondOp.NE(r2, 0))  // added unrelated assert so that NPAnalysis starts from here
                exit()
            }
        }

        println("$cfg")

        ConfigScope(SolanaConfig.SlicerBackPropagateThroughAsserts, true).use {
            val np = NPAnalysis(cfg, globals, memSummaries)
            val absValAt1 = np.getPreconditionsAtEntry(Label.Address(1))
            check(absValAt1 != null) { "No preconditions for label 1" }
            println("Preconditions at entry of 1=$absValAt1")
            val absValAt2 = np.getPreconditionsAtEntry(Label.Address(2))
            check(absValAt2 != null) { "No preconditions for label 2" }
            println("Preconditions at entry of 2=$absValAt2")
            val absValAt3 = np.getPreconditionsAtEntry(Label.Address(3))
            check(absValAt3 != null) { "No preconditions for label 3" }
            println("Preconditions at entry of 3=$absValAt3")
            Assertions.assertEquals(true, absValAt2.isBottom())
        }
    }

    @Test
    // Same as test10 but disabling SlicerBackPropagateThroughAsserts
    fun test11() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 1
                br(CondOp.NE(r9, 0x0), 1, 2)
            }
            bb(1) {
                assume(CondOp.NE(r9, 0x0))
                goto(3)
            }
            bb(2) {
                assume(CondOp.EQ(r9, 0x0))
                r1 = 0
                goto(3)
            }
            bb(3) {
                assert(CondOp.NE(r1, 0))
                assume(CondOp.NE(r9, 0))
                BinOp.MUL(r8, r7)
                BinOp.DIV(r8, r9)
                assert(CondOp.NE(r2, 0))  // added assert so that NPAnalysis starts from here
                exit()
            }
        }

        println("$cfg")

        ConfigScope(SolanaConfig.SlicerBackPropagateThroughAsserts, false).use {
            val np = NPAnalysis(cfg, globals, memSummaries)
            val absValAt1 = np.getPreconditionsAtEntry(Label.Address(1))
            check(absValAt1 != null) { "No preconditions for label 1" }
            println("Preconditions at entry of 1=$absValAt1")
            val absValAt2 = np.getPreconditionsAtEntry(Label.Address(2))
            check(absValAt2 != null) { "No preconditions for label 2" }
            println("Preconditions at entry of 2=$absValAt2")
            val absValAt3 = np.getPreconditionsAtEntry(Label.Address(3))
            check(absValAt3 != null) { "No preconditions for label 3" }
            println("Preconditions at entry of 3=$absValAt3")
            Assertions.assertEquals(false, absValAt2.isBottom())
        }
    }

    @Test
    fun test12() {
        val x = ExpressionVar("x", 1U)
        val y = ExpressionVar("y", 2U)

        val csts = listOf(
            SbfLinearConstraint(CondOp.LE, x, ExpressionNum(10)),
            SbfLinearConstraint(CondOp.GT, x, ExpressionNum(4)),
            SbfLinearConstraint(CondOp.LT, y, ExpressionNum(1000)),
            // this will be ignored
            SbfLinearConstraint(CondOp.EQ, LinearExpression(x).add(y), LinearExpression(ExpressionNum(50))),
            SbfLinearConstraint(CondOp.NE, x, ExpressionNum(5)),
            SbfLinearConstraint(CondOp.NE, y, ExpressionNum(10)),
        )
        val solver = IntervalSolver(csts, UnsignedIntervalFactory())
        val solMap = solver.run()
        Assertions.assertEquals(true, solMap != null)
        check(solMap != null)
        for ((v, i) in solMap) {
            when (v) {
                x -> Assertions.assertEquals(true, i == UnsignedInterval(6U, 10U))
                y -> Assertions.assertEquals(true, i == UnsignedInterval(0U, 999U))
            }
        }
    }

    @Test
    fun test13() {
        // csts has already a contradiction but the constructor of IntervalSolver will ignore it.
        // This is expected behavior because the interval solver only supports constrains `v op n`
        val csts = listOf(
            SbfLinearConstraint(CondOp.GT, LinearExpression(ExpressionNum(0)), LinearExpression(ExpressionNum(1))),
        )
        val solver = IntervalSolver(csts, UnsignedIntervalFactory())
        val solMap = solver.run()
        Assertions.assertEquals(true, solMap != null)
    }

    @Test
    fun test14() {
        // csts is empty
        val csts = listOf<SbfLinearConstraint>()
        val solver = IntervalSolver(csts, UnsignedIntervalFactory())
        val solMap = solver.run()
        Assertions.assertEquals(true, solMap != null)
    }

    /**
     *  Common pattern in borsh serialize
     *
     *  "copy the minimum between available space and requested size
     *  and return error if the number of copied bytes less than the requested size"
     *
     *  (no select)
     **/
    @Test
    fun test15() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 1024
                "__rust_alloc"()
                r1 = r0
                r2 = r10
                r7 = r1[24]
                r9 = r1[32]
                BinOp.ADD(r7, r6)
                BinOp.SUB(r9, r6)
                r8 = 32
                br(CondOp.LE(r8, r9), 1, 2)
            }
            bb(1) {
                r6 = 32
                goto(3)
            }
            bb(2) {
                r6 = r9
                goto(3)
            }
            bb(3) {
                r1 = r7
                BinOp.SUB(r2, 600)
                r3 = r6
                goto(4)
            }
            bb(4) {
                "sol_memcpy_"()
                assume(CondOp.LE(r8, r9))
                assert(CondOp.EQ(r4, 0)) // added assert so that NPAnalysis runs
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        val (_, slicedProg) = sliceAssertions(
            MutableSbfCallGraph(mutableListOf(cfg), setOf("test"), globals),
            memSummaries
        )
        val slicedCfg = slicedProg.getCFG("test")
        check(slicedCfg != null)
        println("$slicedCfg")


        // This code is just to check that r3 is 32 at memcpy instruction
        val scalarAnalysis = ScalarAnalysis(slicedCfg, globals, memSummaries, ConstantSbfTypeFactory())
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)
        for (b in slicedCfg.getBlocks().values) {
            for (locInst in b.getLocatedInstructions()) {
                val inst = locInst.inst
                if (inst is SbfInstruction.Call && SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY) {
                    val ty = regTypes.typeAtInstruction(locInst, SbfRegister.R3_ARG)
                    Assertions.assertEquals(true, (ty as? SbfType.NumType)?.value?.toLongOrNull() == 32L)
                }
            }
        }
    }

    /**
     *  As test15 but with a select.
     **/
    @Test
    fun test16() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 1024
                "__rust_alloc"()
                r1 = r0
                r2 = r10
                r7 = r1[24]
                r9 = r1[32]
                BinOp.ADD(r7, r6)
                BinOp.SUB(r9, r6)
                r8 = 32
                select(r6, CondOp.LE(r8, r9), 32, r9)
                r1 = r7
                BinOp.SUB(r2, 600)
                r3 = r6
                "sol_memcpy_"()
                assume(CondOp.LE(r8, r9))
                goto(1)
            }
            bb(1) {
                assert(CondOp.EQ(r4, 0)) // added assert so that NPAnalysis runs
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        val (_, slicedProg) = sliceAssertions(
            MutableSbfCallGraph(mutableListOf(cfg), setOf("test"), globals),
            memSummaries
        )
        val slicedCfg = slicedProg.getCFG("test")
        check(slicedCfg != null)
        println("$slicedCfg")

        // This code is just to check that r3 is 32 at memcpy instruction
        val scalarAnalysis = ScalarAnalysis(slicedCfg, globals, memSummaries, ConstantSbfTypeFactory())
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)
        for (b in slicedCfg.getBlocks().values) {
            for (locInst in b.getLocatedInstructions()) {
                val inst = locInst.inst
                if (inst is SbfInstruction.Call && SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY) {
                    val ty = regTypes.typeAtInstruction(locInst, SbfRegister.R3_ARG)
                    Assertions.assertEquals(true, (ty as? SbfType.NumType)?.value?.toLongOrNull() == 32L)
                }
            }
        }
    }

    @Test
    fun test17() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                assume(CondOp.LE(r1, 10))
                assume(CondOp.GE(r1, 10))
                goto(1)
            }
            bb(1) {
                assert(CondOp.EQ(r1, 10))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        ScalarAnalysisProver(cfg, ConstantSbfTypeFactory())
    }

    /** Example with overlaps */
    @Test
    fun test18() {
        /*
		r2 := r10 -200
	    *(u64 *) (r2 + 0) := 0
	    *(u32 *) (r2 + 4) := 1 <-- overlap
	    r3 := *(u64 *) (r2 + 0)
	    assume(r3 != 0)
         */


        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test18")

        val l0 = Label.Address(1)
        val b0 = cfg.getOrInsertBlock(l0)
        cfg.setEntry(b0)
        cfg.setExit(b0)

        b0.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b0.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))

        b0.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(0UL), false))
        b0.add(SbfInstruction.Mem(Deref(4, r2, 4), Value.Imm(1UL), false))
        b0.add(SbfInstruction.Mem(Deref(8, r2, 0), r3, true))
        b0.add(SbfInstruction.Assume(Condition(CondOp.NE, r3, Value.Imm(0UL))))
        b0.add(SbfInstruction.Exit())

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(1))
        check(b != null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("absVal=$absVal\n$b\nnewAbsVal=$newAbsVal")
        Assertions.assertEquals(false, newAbsVal.isBottom())
    }

    /**
     * The preconditions of this snippet is bottom because there is an inconsistency at the `assume` between
     * the forward analysis propagates `r1==251` and the backward analysis propagates back `r1 == -5`.
     *
     * ```
     *  *(u8 *) (r10-183):sp(3913) = -5
     *  r1 = *(u8 *) (r10-183):sp(3913)
     *  assume(r1 == -5)
     * ```
     */
    @Test
    fun `store of negative number and load of 1 byte`() {
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        // *(u8 *) (r10-183):sp(3913) := -5
        b1.add(SbfInstruction.Mem(Deref(1, r10, -183), Value.Imm((-5).toULong()), false))
        // r1 = *(u8 *) (r10-183):sp(3913)
        b1.add(SbfInstruction.Mem(Deref(1, r10, -183), r1, true))
        // assume(r1 == -5)
        b1.add(SbfInstruction.Assume(Condition(CondOp.EQ, r1, Value.Imm((-5).toULong()))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(1))
        check(b!=null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("Postcondition=$absVal\n$b\nPrecondition=$newAbsVal")
        Assertions.assertEquals(true, newAbsVal.isBottom())
    }

    /**
     * The preconditions at bb2 and bb3 are bottom. The backward analysis propagates that `*(u8 *) (r10-183) == 251`
     *
     * ```
     * bb1: if (*) goto bb2 else goto bb3
     * bb2: *(u8 *) (r10-183):sp(3913) = -5
     *      goto bb4
     * bb3:
     *      *(u8 *) (r10-183):sp(3913) = -6
     *      goto bb4
     * bb4:
     *      r1 = *(u8 *) (r10-183):sp(3913)
     *      assume(r1 == -5)
     * ```
     */
    @Test
    fun `store of negative number with branches and load of 1 byte`() {
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        val b2 = cfg.getOrInsertBlock(Label.Address(2))
        val b3 = cfg.getOrInsertBlock(Label.Address(3))
        val b4 = cfg.getOrInsertBlock(Label.Address(4))

        cfg.setEntry(b1)
        cfg.setExit(b4)
        b1.addSucc(b2)
        b1.addSucc(b3)
        b2.addSucc(b4)
        b3.addSucc(b4)

        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), Label.Address(2), Label.Address(3)))
        // *(u8 *) (r10-183):sp(3913) := -5
        b2.add(SbfInstruction.Mem(Deref(1, r10, -183), Value.Imm((-5).toULong()), false))
        b2.add(SbfInstruction.Jump.UnconditionalJump(Label.Address(4)))
        // *(u8 *) (r10-183):sp(3913) := -6
        b3.add(SbfInstruction.Mem(Deref(1, r10, -183), Value.Imm((-6).toULong()), false))
        b3.add(SbfInstruction.Jump.UnconditionalJump(Label.Address(4)))
        // r1 = *(u8 *) (r10-183):sp(3913)
        b4.add(SbfInstruction.Mem(Deref(1, r10, -183), r1, true))
        // assume(r1 == -5)
        b4.add(SbfInstruction.Assume(Condition(CondOp.EQ, r1, Value.Imm((-5).toULong()))))

        b4.add(SbfInstruction.Assert(Condition(CondOp.EQ, r3, Value.Imm(0UL))))
        b4.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)

        println("$cfg")

        ConfigScope(SolanaConfig.SlicerBackPropagateThroughAsserts, false).use {
            ConfigScope(SolanaConfig.ScalarMaxVals, 1). use {
                val np = NPAnalysis(cfg, globals, memSummaries)
                for (i in 1..4) {
                    val absVal = np.getPreconditionsAtEntry(Label.Address(i.toLong()))
                    check(absVal != null) { "No preconditions for label $i" }
                    println("Preconditions at entry of $i=$absVal")
                    if (i==4) {
                        Assertions.assertEquals(false, absVal.isBottom())
                    } else {
                        Assertions.assertEquals(true, absVal.isBottom())
                    }
                }
            }
        }
    }


    /**
     * ```
     *  *(u8 *) (r10-183):sp(3913) = 5
     *  r1 = *(u8 *) (r10-183):sp(3913)
     *  assume(r1 == 5)
     * ```
     */
    @Test
    fun `store of positive number and load of 1 byte`() {
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        // *(u8 *) (r10-183):sp(3913) := 5
        b1.add(SbfInstruction.Mem(Deref(1, r10, -183), Value.Imm(5UL), false))
        // r1 = *(u8 *) (r10-183):sp(3913)
        b1.add(SbfInstruction.Mem(Deref(1, r10, -183), r1, true))
        // assume(r1 == 5)
        b1.add(SbfInstruction.Assume(Condition(CondOp.EQ, r1, Value.Imm(5UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val vFac = VariableFactory()
        val absVal = top
        val b = cfg.getBlock(Label.Address(1))
        check(b!=null)
        val newAbsVal = absVal.analyze(b, vFac, regTypes, false)
        println("Postcondition=$absVal\n$b\nPrecondition=$newAbsVal")
        Assertions.assertEquals(false, newAbsVal.isBottom())
    }

    @Test
    fun `unhoisting memcpy is needed to avoid losses at join`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r9 = r10
                BinOp.SUB(r9, 200)
                br(CondOp.EQ(r3, 0x0), 1, 2)
            }
            bb(1) {
                r1 = r10[-664]
                assume(CondOp.NE(r1, 4))
                r6 = r10
                BinOp.SUB(r6, 824)
                r2 = r10
                BinOp.SUB(r2, 664)
                r1 = r6
                r3 = 160
                "sol_memcpy_"()
                goto(3)
            }
            bb(2) {
                r1 = r10[-1704]
                assume(CondOp.NE(r1, 4))
                r6 = r10
                BinOp.SUB(r6, 1864)
                r2 = r10
                BinOp.SUB(r2, 1704)
                r1 = r6
                r3 = 160
                "sol_memcpy_"()
                goto(3)
            }
            bb(3) {
                r1 = r9
                r2 = r6
                r3 = 160
                "sol_memcpy_"()
                goto(4)
            }
            bb(4) {
                r1 = r9[0]
                assume(CondOp.EQ(r1, 4))
                assert(CondOp.EQ(r4, 0))
                exit()
            }
        }
        cfg.normalize()
        println( "Before $cfg" )
        cfg.verify(true)

        ConfigScope(SolanaConfig.SlicerBackPropagateThroughAsserts, false).use {
            val np = NPAnalysis(cfg, globals, memSummaries)
            for (label in cfg.getBlocks().keys) {
                val absVal = np.getPreconditionsAtEntry(label)
                check(absVal != null)
                println("Preconditions at entry of $label=$absVal")
                if (label==Label.Address(0)) {
                    Assertions.assertEquals(false, absVal.isBottom())
                }
            }
        }

        ConfigScope(SolanaConfig.SlicerBackPropagateThroughAsserts, false).use {
            cfg.simplify(globals) // will unhoist memcpy which is key for precision of the NPDomain
            println("After unhoisting $cfg")

            val np = NPAnalysis(cfg, globals, memSummaries)
            for (label in cfg.getBlocks().keys) {
                val absVal = np.getPreconditionsAtEntry(label)
                check(absVal != null)
                println("Preconditions at entry of $label=$absVal")
                if (label==Label.Address(0)) {
                    Assertions.assertEquals(true, absVal.isBottom())
                }
            }
        }
    }
}


