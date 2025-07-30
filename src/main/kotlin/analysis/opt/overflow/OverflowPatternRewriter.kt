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

package analysis.opt.overflow

import analysis.dataflow.OverApproxUseAnalysis
import analysis.maybeExpr
import analysis.numeric.MAX_UINT
import analysis.opt.ConstantPropagatorAndSimplifier
import analysis.opt.NegationNormalizer
import analysis.opt.bytemaps.BytemapInliner
import analysis.opt.earlyPatternsList
import analysis.opt.intervals.IntervalsCalculator
import analysis.split.BoolOptimizer
import com.certora.collect.*
import instrumentation.transformers.AssignmentInliner
import tac.MetaKey
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACExpr
import vc.data.asTACExpr
import vc.data.getOperands
import verifier.BlockMerger
import java.math.BigInteger

/**
 * Detects and rewrites overflow checks via pattern matching. It assumes normalization of the TAC via
 * [BoolOptimizer], [ConstantPropagatorAndSimplifier], [BlockMerger], [earlyPatternsList] (needs only
 * some of the patterns there), [AssignmentInliner] (needed for renaming all aliasing variables with the same name),
 * [BytemapInliner] for getting vyper memory read-writes out of the way, and [NegationNormalizer].
 * It then also needs just a bit more of normalization via [JumpLeNormalizer].
 *
 * The rewrite of the no-overflow condition has some subtle points explained here. Examples are with unsigned
 * multiplication, but it's the same with technical differences for the other cases.
 *
 * When the overflow conditions are with assumes it’s pretty straightforward. If the original code is (doesn't matter
 * if the assumes are before or after the multiplication:
 * ```
 *    res := x Mul y
 *    assume cond1
 *    assume cond2
 * ```
 * We turn it to (let’s say we detected this is an 8-bit unsigned multiplication):
 * ```
 *    res := narrow(x IntMul y)
 *    assume noMulOverflow(x, y)
 *    assume res <= 255
 * ```
 * The order of commands doesn’t matter.
 *
 * If the overflow conditions are jump-conditions, there are two cases. First if the conditions appear as pre-conditions
 * to the actual multiplication. Here is an example with three pre-blocks, it can anywhere from (1- 4):
 * ```
 * block1:
 *    <cmds1>
 *    jumpi (no overflow 1) to block2 else revertBlock1
 *
 * block2:
 *    <cmds2>
 *    jumpi (no overflow 2) to block3 else revertBlock2
 *
 * block3:
 *    <cmds3>
 *    jumpi (no overflow 3) to lastBlock else revertBlock3
 *
 * lastBlock:
 * ```
 *    ...
 *    r := original multiplication
 * ```
 *
 * It becomes:
 * ```
 * block1:
 *    <cmds1>
 *    <cmds2>
 *    <cmds3>
 *    cond1 := noOverflowBif(x, y)
 *    jumpi cond1 to operationBlock else (revertBlock1 or revertBlock2 or revertBlock3)
 *
 * operationBlock:
 *    result := narrow(x intMul y)
 *    cond2 := result <= 0xff
 *    jumpi cond2 to block1 else (revertBlock1 or revertBlock2 or revertBlock3)
 *
 * lastBlock:
 *    just erase the multiplication
 * ```
 *
 * The reason for having `operationBlock` is that it makes sure that the multiplication doesn’t overflow in the 2^256 sense,
 * if it does it would be wrong to use `safeMathNarrow` here, and we couldn’t do the more precise check.
 * The revert will non-deterministically jump to one of the revert blocks, this is a sound over-approximation.
 * If we ever see this gives us trouble, we can prevent the rewrite if the revert blocks are different (most of the
 * time they are equal).
 *
 * There are a couple of subtle points here:
 *   2. If the revert blocks (or blocks that follow) use any of the assignments in <cmds2> or <cmds3>, the effects of
 *      these assignments would be felt in `revertBlock1` as well. So we forbid such cases.
 *   3. similarly, if there is an assume in <cmds2> or <cmds3> (an assume is fine in <cmds1>), then again, the
 *      `revertBlock1` would have altered behavior.
 *
 * If the overflow conditions are post-multiplication:
 * ```
 * block1:
 *   <cmds1-a>
 * 	 result := x Mul y
 * 	 <cmds1-b>
 *   jumpi (no overflow 1) to block2 else revertBlock1
 *
 * block2:
 *   <cmds2>
 *   jumpi (no overflow 2) to block3 else revertBlock2
 *
 * block3:
 *   <cmds3>
 *   jumpi (no overflow 3) to lastBlock else revertBlock3
 *
 * lastBlock:
 *   <lastCmds>
 * ```
 *
 * from the commands after the multiplication we mark those that use `result`  (directly and indirectly). We first make
 * sure they are never used in any revert block (or blocks after it). But we only have to check until `lastBlock`,
 * because the multiplication and what follows will move there. The resulting rewrite is very similar to the above,
 * except for the pushing forward of all the commands that flow out of `result`.
 * ```
 * block1:
 *   <cmds1-a>
 * 	 <cmds1-b that don't use result>
 * 	 <cmds2 that don't use result>
 * 	 <cmds3 that don't use result>
 * 	 cond1 = noOverflowBif(x, y)
 * 	 jumpi cond1 to operationBlock else (revertBlock1 or revertBlock2 or revertBlock3)
 *
 * operationBlock:
 *   result := narrow(x IntMul y)
 *   cond2 := result <= 0xff
 *   jumpi cond2 to block1 else (revertBlock1 or revertBlock2 or revertBlock3)
 *
 * lastBlock:
 *   <result-using-cmds1-b>
 *   <result-using-cmds2>
 *   <result-using-cmds3>
 *   <lastCmds>
 * ```
 *
 * Similarly to the case where the checks originally appeared before the multiplication, here as well, we can't allow
 * assumes, as well as assignments to variables that are used in the revert blocks (or after), in <cmds2> and <cmds3>.
 */
class OverflowPatternRewriter(code1: CoreTACProgram) {
    private val code = JumpLeNormalizer.normalize(code1)
    private val g = code.analysisCache.graph
    private val def = code.analysisCache.strictDef
    private val patcher = code.toPatchingProgram()
    private val intervals = IntervalsCalculator(code, preserve = { false }, calcJumpPostConditions = true)

    companion object {
        @Suppress("EqualsOrHashCode")
        @KSerializable
        @Treapable
        data class OverflowMetaData(
            val type: RecipeType,
            val width: Int,
            val signed: Boolean
        ) : AmbiSerializable {
            override fun hashCode() = hash { it + width + signed + type }
        }

        val overflowMeta = MetaKey<OverflowMetaData>("overflow.rewrite")
    }

    fun go(): CoreTACProgram {
        //TACProgramPrinter.standard().print(code)
        val useAnalysis = OverApproxUseAnalysis(code)

        g.commands.mapNotNull { it.maybeExpr<TACExpr>() }.forEach { lcmd ->
            val ptr = lcmd.ptr
            val rhs = lcmd.exp

            if (rhs.getOperands().any { intervals.getS(ptr, it).isEmpty() }) {
                return@forEach
            }

            fun handle(context: OverflowContext): Boolean {
                val match = Matcher(code, context, useAnalysis).go()
                    ?: return false
                Replacer(code, patcher, context, match).go()
                return true
            }

            fun handle(o1: TACExpr.Sym.Var, o2: TACExpr.Sym.Var) =
                handle(OverflowContext.Binary(def, intervals, lcmd, o1.s, o2.s))

            fun handle(o1: TACExpr.Sym.Var, o2: TACExpr.Sym.Const) =
                handle(OverflowContext.Const(def, intervals, lcmd, o1.s, o2.s.value))

            when (rhs) {
                is TACExpr.Vec.Add, is TACExpr.Vec.Mul -> {
                    if (rhs.getOperands().size != 2) {
                        return@forEach
                    }
                    val (o1, o2) = rhs.getOperands()
                    when {
                        o1 is TACExpr.Sym.Var && o2 is TACExpr.Sym.Var ->
                            handle(o1, o2) || handle(o2, o1)

                        rhs is TACExpr.Vec.Mul &&
                            o1 is TACExpr.Sym.Var && o2 is TACExpr.Sym.Const && o2.getAsConst() != BigInteger.ZERO ->
                            handle(o1, o2)

                        rhs is TACExpr.Vec.Mul &&
                            o2 is TACExpr.Sym.Var && o1 is TACExpr.Sym.Const && o1.getAsConst() != BigInteger.ZERO ->
                            handle(o2, o1)
                    }
                }

                is TACExpr.BinOp.ShiftLeft ->
                    if (rhs.o1 is TACExpr.Sym.Var && rhs.o2 is TACExpr.Sym.Const) {
                        rhs.o2.s.value.toIntOrNull()
                            ?.takeIf { it in 0..255 }
                            ?.let { handle(rhs.o1, BigInteger.TWO.pow(it).asTACExpr) }
                    }

                is TACExpr.BinOp.Sub -> {
                    val o1 = rhs.o1
                    val o2 = rhs.o2
                    when {
                        o1 is TACExpr.Sym.Var && o2 is TACExpr.Sym.Var ->
                            handle(o1, o2)

                        o1.getAsConst() == BigInteger.ZERO && o2 is TACExpr.Sym.Var ->
                            // `x * -1` is sometimes optimized to `0 - x`
                            handle(o2, MAX_UINT.asTACExpr)
                    }
                }

                else -> Unit
            }
        }
        return patcher.toCode(code)
    }

}
