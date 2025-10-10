/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package report.cexanalysis

import analysis.CmdPointer
import analysis.opt.signed.SignedDetector
import analysis.opt.signed.SignedDetector.Spot
import datastructures.stdcollections.*
import tac.Tag
import utils.ModZm.Companion.from2s
import utils.ModZm.Companion.inBounds
import utils.ModZm.Companion.inSignedBounds
import utils.`impossible!`
import utils.lazy
import utils.monadicMap
import utils.runIf
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.getOperands
import vc.data.taccmdutils.TACCmdUtils.rhsExpr
import vc.data.tacexprutil.subs
import java.math.BigInteger
import kotlin.plus
import kotlin.times

/**
 * Analyses the counter example and tries to figure out if there are overflows in any addition/subtraction/multiplication
 * There are two types:
 *   1. Checked operations, when run with `@withRevert`, [analysis.opt.overflow.OverflowPatternRewriter] found them
 *      and rewrote them. These are no trivial to work with, and we leave them for a later PR.
 *   2. Unchecked operations. We can only work with 256 bit overflows:
 *      + So we'll miss some real overflows.
 *      + Another problem is that we use the [SignedDetector] to figure out if such an operation is meant to be signed
 *        or unsigned. If we don't know then we can't alert for overflows,
 *      + and even if we do, we may get this information wrong (hopefully, this is very rare, as we are trying to play
 *        it safe). In such a case we may report an overflow where there is none.
 */
class CounterExampleOverflowDetector(private val cex: CounterExampleContext) {
    private val signedDetector by lazy { SignedDetector(cex.code).go() }

    class Overflow(
        override val ptr: CmdPointer,
        override val cmd: TACCmd.Simple,
        val e: TACExpr,
        val opVals: List<BigInteger>,
        val noMod: BigInteger,
        val width: Int,
        val isSigned : Boolean
    ) : CexAnalysisInfo {
        val typeString = if(isSigned) {
            "Signed"
        } else {
            "Unsigned"
        }
        override val msg: String
            get() = "$typeString overflow: $e : $opVals -> $noMod (width = $width)"
    }


    /**
     * Returns a map from [CmdPointer] -> a message regarding a detected overflow
     */
    fun check(): Map<CmdPointer, Overflow> = buildMap {
        for ((ptr, cmd) in cex.cexCmdSequence()) {
            cmd.rhsExpr?.subs
                ?.filter { it is TACExpr.Vec.Mul || it is TACExpr.BinOp.Sub || it is TACExpr.Vec.Add }
                ?.forEach { e ->
                    when (signedDetector(Spot.Expr(ptr, e))) {
                        SignedDetector.Color.Signed -> handleSigned(ptr, cmd, e, 256)
                        SignedDetector.Color.Unsigned -> handleUnsigned(ptr, cmd, e, 256)
                        else -> null
                    }?.let {
                        put(ptr, it)
                    }
                }
        }
    }

    private fun TACExpr.evalNoMod(ops: List<BigInteger>): BigInteger {
        val operator = when (this) {
            is TACExpr.Vec.Mul, is TACExpr.Vec.IntMul -> BigInteger::times
            is TACExpr.BinOp.Sub, is TACExpr.BinOp.IntSub -> BigInteger::subtract
            is TACExpr.Vec.Add, is TACExpr.Vec.IntAdd -> BigInteger::plus
            else -> `impossible!`
        }
        return ops.reduce(operator)
    }


    private fun handleUnsigned(ptr : CmdPointer, cmd : TACCmd.Simple, e: TACExpr, width: Int) : Overflow? {
        val opVals = e.getOperands().monadicMap { cex(it) }
            ?: return null
        val noMod = e.evalNoMod(opVals)
        return runIf(!noMod.inBounds(Tag.Bits(width))) {
            Overflow(ptr, cmd, e, opVals, noMod, width, isSigned = false)
        }
    }

    private fun handleSigned(ptr : CmdPointer, cmd : TACCmd.Simple, e: TACExpr, width: Int) : Overflow? {
        val opVals = e.getOperands().monadicMap { cex(it) }?.map { it.from2s(Tag.Bit256) }
            ?: return null
        val noMod = e.evalNoMod(opVals)
        return runIf(!noMod.inSignedBounds(Tag.Bits(width))) {
            Overflow(ptr, cmd, e, opVals, noMod, width,  isSigned = true)
        }
    }
}
