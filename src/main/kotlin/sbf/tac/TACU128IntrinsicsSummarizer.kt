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

package sbf.tac

import sbf.cfg.*
import sbf.disassembler.SbfRegister
import vc.data.*
import java.math.BigInteger
import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.callgraph.CVTU128Intrinsics
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import sbf.sbfLogger
import vc.data.TACExprFactUntyped.le
import vc.data.TACExprFactUntyped.lt

/**
 * Describes where the u128 intrinsics should store results and how to restore r0.
 */
private sealed class BinaryOpResultSpec {
    abstract val savedR0: TACMemSplitter.SummaryArgInfo

    /** Two result cells: for wrapping add/sub that produce an u128 **/
    data class U128(
        val resLow: TACMemSplitter.SummaryArgInfo,
        val resHigh: TACMemSplitter.SummaryArgInfo,
        override val savedR0: TACMemSplitter.SummaryArgInfo,
    ) : BinaryOpResultSpec()

    /** One result cell: for relational ops that produce an u64 (0 or 1) **/
    data class U64(
        val res: TACMemSplitter.SummaryArgInfo,
        override val savedR0: TACMemSplitter.SummaryArgInfo,
    ) : BinaryOpResultSpec()
}

/**
 * Handles TAC summarization for all u128 intrinsic calls.
 *
 * The calls handled here are not present in the original Solana bytecode.
 * They are introduced in one of two ways:
 *  - By a front-end CFG transformation (e.g. [promoteMathIntrinsics]) that recognizes
 *    sequences of low-level SBF instructions implementing a 128-bit operation and
 *    replaces them with a single call to the corresponding intrinsic.
 *  - By cvlr, which emits calls to these intrinsics directly when compiling
 *    specification expressions that operate on u128 values.
 *
 * [bufferPtr] is a heap-allocated 24-byte buffer initialized once via [init] and reused
 * across all binary-operation summaries (both arithmetic and relational).
 **/
class U128Summarizer(mkFreshIntVar: (String) -> TACSymbol.Var) {
    val bufferPtr: TACSymbol.Var = mkFreshIntVar("u128.buffer_ptr")

    /**
     * Allocates the shared 24-byte heap buffer for all binary u128 operations.
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        init(): List<TACCmd.Simple> = heapMemAlloc.alloc(bufferPtr, 24UL)

    /**
     * Dispatches TAC summarization for a u128 intrinsic call.
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        summarizeU128(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) { "summarizeU128 expects a call instruction instead of ${locInst.inst}" }
        val function = CVTU128Intrinsics.from(inst.name)
        check(function != null) { "summarizeU128 does not support ${inst.name}" }
        return when (function) {
            CVTU128Intrinsics.U128_CEIL_DIV -> summarizeU128CeilDiv(locInst)
            CVTU128Intrinsics.U128_NONDET -> summarizeU128Nondet(locInst)
            CVTU128Intrinsics.U128_LEQ -> summarizeU128BinRel(locInst) { x, y -> x le y }
            CVTU128Intrinsics.U128_LT -> summarizeU128BinRel(locInst) { x, y -> x lt y }
            CVTU128Intrinsics.U128_WRAPPING_SUBTRACTION -> summarizeU128BinArith(locInst) { x, y -> natIntTacB { x sub y } }
            CVTU128Intrinsics.U128_WRAPPING_ADDITION -> summarizeU128BinArith(locInst) { x, y -> natIntTacB { x add y } }
        }
    }

    /**
     * Given `r2: low(x)`, `r3: high(x)`, `r4: low(y)`, `r5: high(y)`, `low(result)` in `*(r0+0)`, and `high(result)` in `*(r0+8)`
     * compute:
     * ```
     * ceil_div(x, y) = (x + y - 1) / y
     * ```
     * where `x = high(x) << 64 + low(x)` and `y = high(y) << 64 + low(y)`
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        summarizeU128CeilDiv(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        { "summarizeU128CeilDiv expects a call instruction instead of ${locInst.inst}" }
        check(CVTU128Intrinsics.from(inst.name) == CVTU128Intrinsics.U128_CEIL_DIV)
        { "summarizeU128CeilDiv expects ${CVTU128Intrinsics.U128_CEIL_DIV.function.name}" }

        if (!SolanaConfig.UseTACMathInt.get()) {
            sbfLogger.warn {
                "${locInst.inst} will not be modeled precisely in TAC. " +
                    "Enable ${SolanaConfig.UseTACMathInt.name} for a precise modeling"
            }
            return summarizeCall(locInst)
        }

        val (resLow, resHigh, overflow) = getResFrom128(locInst) ?: return listOf()
        val xLowE  = sbfTacB.mkVar(SbfRegister.R2).asSym()
        val xHighE = sbfTacB.mkVar(SbfRegister.R3).asSym()
        val yLowE  = sbfTacB.mkVar(SbfRegister.R4).asSym()
        val yHighE = sbfTacB.mkVar(SbfRegister.R5).asSym()
        val args = U128BinaryOperands(resLow.tacVar, resHigh.tacVar, overflow?.tacVar, xLowE, xHighE, yLowE, yHighE)

        val xMath   = vFac.mkFreshMathIntVar()
        val yMath   = vFac.mkFreshMathIntVar()
        val resMath = vFac.mkFreshMathIntVar()

        val cmds = mutableListOf<TACCmd.Simple>()
        cmds += Debug.startFunction(inst.name)
        applyU128BinaryOperation(args, cmds) { res, _, x, y ->
            cmds += promoteToMathInt(x.asSym(), xMath)
            cmds += promoteToMathInt(y.asSym(), yMath)
            cmds += assign(resMath, sbfTacB { ((xMath intAdd yMath) intSub ONE) intDiv yMath })
            cmds += narrowFromMathInt(resMath.asSym(), res)
        }
        cmds += Debug.endFunction(inst.name)
        return cmds
    }

    /**
     * Given `low(result)` in `*(r0+0)`, and  `high(result)` in `*(r0+8)` compute
     *
     * ```
     * result < 2^128
     * ```
     * where `result = high(result) << 64 + low(result)`
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        summarizeU128Nondet(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        { "summarizeU128Nondet expects a call instruction instead of ${locInst.inst}" }
        check(CVTU128Intrinsics.from(inst.name) == CVTU128Intrinsics.U128_NONDET)
        { "summarizeU128Nondet expects ${CVTU128Intrinsics.U128_NONDET.function.name}" }

        val (resLow, resHigh) = getResFrom128(locInst) ?: return listOf()
        val res = vFac.mkFreshIntVar()

        val cmds = mutableListOf<TACCmd.Simple>()
        cmds += Debug.startFunction(inst.name)
        cmds += inRange(res, BigInteger.ZERO ..< BigInteger.TWO.pow(128))
        cmds += splitU128(res.asSym(), resLow.tacVar, resHigh.tacVar)
        cmds += Debug.endFunction(inst.name)
        return cmds
    }

    /**
     * Shared implementation for u128 binary operations (arithmetic and relational).
     *
     * Operands:
     *  - `x` is the 128-bit value whose low half is in R1 and high half is in R2.
     *  - `y` is the 128-bit value whose low half is in R3 and high half is in R4.
     *
     * [op] performs the operation on the merged operands.
     *
     * [spec] describes where to write results and the saved r0 to restore:
     *  - [BinaryOpResultSpec.U128]: stores resLow and resHigh, then restores r0.
     *  - [BinaryOpResultSpec.U64]: stores a single result value, then restores r0.
     *
     *  For instance for a binary arithmetic intrinsics:
     *  ```
     *  fun cvt_u128_intrinsics {
     *  (1)    ptr = bufferPtr  // shared heap buffer, allocated once in init()
     *  (2)    *(ptr+16) = r0   // save r0
     *         r0 = ptr
     *  (3)    // The code for intrinsics
     *  (4)    *(ptr+0)  = lowRes
     *  (5)    *(ptr+8)  = highRes
     *  }
     *  ```
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        summarizeU128BinaryOp(
        locInst: LocatedSbfInstruction,
        spec: BinaryOpResultSpec,
        op: (TACSymbol.Var, TACSymbol.Var) -> TACExpr
    ): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        { "summarizeU128BinaryOp expects a call instruction instead of ${locInst.inst}" }

        val resLow  = vFac.mkFreshIntVar()
        val resHigh = vFac.mkFreshIntVar()
        val r0      = sbfTacB.mkVar(SbfRegister.R0)
        val xLowE   = sbfTacB.mkVar(SbfRegister.R1).asSym()
        val xHighE  = sbfTacB.mkVar(SbfRegister.R2).asSym()
        val yLowE   = sbfTacB.mkVar(SbfRegister.R3).asSym()
        val yHighE  = sbfTacB.mkVar(SbfRegister.R4).asSym()
        val args = U128BinaryOperands(resLow, resHigh, null, xLowE, xHighE, yLowE, yHighE)

        val cmds = mutableListOf<TACCmd.Simple>()
        cmds += Debug.startFunction(name = inst.name)

        // Extract a ByteMapTarget from a summary arg.
        // Return null if the arg is a stack scalar (U128 specs always target non-stack memory).
        fun resolveMap(v: TACMemSplitter.SummaryArgVariable): TACMemSplitter.ByteMapTarget? =
            when (v) {
                is TACMemSplitter.SummaryArgVariable.Stack -> null
                is TACMemSplitter.SummaryArgVariable.NonStack -> v.target
            }

        // Save r0 with its pre-call value
        val savedR0Map = resolveMap(spec.savedR0.variable) ?: return listOf()
        cmds += store(savedR0Map, bufferPtr, spec.savedR0.offset, r0, spec.savedR0.width.toShort())
        cmds += assign(sbfTacB.mkVar(spec.savedR0.reg), bufferPtr.asSym())

        // 1. Merge low and high halves
        // 2. Apply op in bv256
        // 3. mask with 2^128 - 1
        // 4. split into two halves again
        // Steps 1, 3, and 4 are done as part of `applyU128BinaryOperation`
        applyU128BinaryOperation(args, cmds) { res, _, x, y ->
            cmds += assign(res, op(x, y))
        }

        when (spec) {
            is BinaryOpResultSpec.U128 -> {
                val resLowMap  = resolveMap(spec.resLow.variable)  ?: return listOf()
                val resHighMap = resolveMap(spec.resHigh.variable) ?: return listOf()
                cmds += store(resLowMap,  bufferPtr, spec.resLow.offset,  resLow, spec.resLow.width.toShort())
                cmds += store(resHighMap, bufferPtr, spec.resHigh.offset, resHigh, spec.resHigh.width.toShort())
            }
            is BinaryOpResultSpec.U64 -> {
                val resMap = resolveMap(spec.res.variable) ?: return listOf()
                cmds += store(resMap, bufferPtr, spec.res.offset, resLow, spec.res.width.toShort())
            }
        }
        cmds += Debug.endFunction(name = inst.name)
        return cmds
    }

    /**
     * Given `r1: low(x)`, `r2: high(x)`, `r3: low(y)`, `r4: high(y)`, applies [operand] on the
     * combined u128 values and stores the boolean result (0 or 1) in `*(u64*)(r0+8)` on the heap.
     * The original r0 stored at `*(u64*)r0`.
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        summarizeU128BinRel(
        locInst: LocatedSbfInstruction,
        operand: (ToTACExpr, ToTACExpr) -> TACExpr
    ): List<TACCmd.Simple> {
        val summaryArgs = mem.getTACMemoryFromSummary(locInst) ?: return listOf()
        if (summaryArgs.size != 2) {
            return listOf()
        }
        val spec = BinaryOpResultSpec.U64(summaryArgs[1], summaryArgs[0])
        return summarizeU128BinaryOp(locInst, spec) { x, y ->
            sbfTacB { ite(operand(x.asSym(), y.asSym()), ONE, ZERO) }
        }
    }

    /**
     * Shared implementation for u128 binary arithmetic operations
     *
     * Computes `result = op(x, y) mod 2^128`, where:
     *  - `x` is the 128-bit value whose low half is in R1 and high half is in R2.
     *  - `y` is the 128-bit value whose low half is in R3 and high half is in R4.
     *
     * The result is written to heap memory pointed to by r0:
     *  `*(u64*)(r0+8)` contains `resLow`, `*(u64*)(r0+16)` contains `resHigh`.
     *
     * The old value of `r0` is stored at `*(u64*)r0`.
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        summarizeU128BinArith(
        locInst: LocatedSbfInstruction,
        operand: (ToTACExpr, ToTACExpr) -> TACExpr
    ): List<TACCmd.Simple> {
        val summaryArgs = mem.getTACMemoryFromSummary(locInst) ?: return listOf()
        if (summaryArgs.size != 3) {
            return listOf()
        }
        val spec = BinaryOpResultSpec.U128(summaryArgs[1], summaryArgs[2], summaryArgs[0])
        return summarizeU128BinaryOp(locInst, spec, operand)
    }
}
