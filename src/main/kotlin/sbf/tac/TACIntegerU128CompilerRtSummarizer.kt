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

import sbf.*
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import datastructures.stdcollections.*
import sbf.domains.*
import vc.data.TACCmd

/**
 * Summarize 128-bits integer compiler-rt functions.
 *
 * Not all functions are currently summarized.
 **/

/** Default implementation using bit-fixed numbers **/
open class SummarizeIntegerU128CompilerRt<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> {

    /**
     * `res := (x * y) mod 2^128`.
     *
     * There is deliberately no mathint specialization of this.  A product of two 128-bit values fits in 256 bits,
     * so the 256-bit multiplication is already exact, and [SbfTACBuilder.Mul128] wraps it to 128 bits the way each
     * masking mode requires.  Computing it over mathematical integers instead would hand the solver an unbounded
     * product and a narrowing constraint in place of a multiplication it can bound natively, which costs far more
     * than it buys - see the note in [SummarizeIntegerU128CompilerRtWithMathInt].
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeMulti3(args: U128BinaryOperands): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128BinaryOperation(args, cmds) { res, _, x, y ->
            cmds += assign(res, sbfTacB { x.asSym() mul128 y.asSym() })
        }
        return cmds
    }

    /**
     * `res := (x * y) mod 2^128` and `overflow := 1` exactly when the signed product of `x` and `y`
     * does not fit in 128 bits.
     *
     * Both outputs come from a single 256-bit product `p` of the sign-extended operands: `p` agrees
     * with the unsigned product modulo 2^128, so it yields `res`, and it is exact as a signed value,
     * so it also decides the overflow flag.
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeMuloti4(args: U128BinaryOperands): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128BinaryOperation(args, cmds) { res, overflow, x, y ->
            check(overflow != null) { "__muloti4 was summarized without a variable for its overflow flag" }
            val product = vFac.mkFreshIntVar()
            cmds += assign(product, sbfTacB { x.asSym() sMulWide128 y.asSym() })
            cmds += assign(res, sbfTacB.mask128(product.asSym()))
            cmds += assign(overflow, sbfTacB { ite(fitsInSigned128(product.asSym()), ZERO, ONE) })
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeUDivti3(args: U128BinaryOperands): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128BinaryOperation(args, cmds) { res, _, x, y ->
            cmds += assign(res, sbfTacB { x.asSym() div128 y.asSym() })
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeDivti3(args: U128BinaryOperands): List<TACCmd.Simple> {
        return when (sbfTacB) {
            // In "lazy masking" mode, we summarize __divti3...oddly.  Rather than try to abstract this behavior into
            // TACExprBuilder, it's easier to just handle it directly here.
            is LazyMaskSbfTACBuilder -> listOf(
                assign(args.resLow, sbfTacB { args.xLow sDiv args.yLow }),
                havoc(args.resHigh)
            )
            else -> {
                mutableListOf<TACCmd.Simple>().also { cmds ->
                    applyU128BinaryOperation(args, cmds) { res, _, x, y ->
                        cmds += assign(res, sbfTacB { x.asSym() sDiv128 y.asSym() })
                    }
                }
            }
        }
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeAshlti3(args: U128ShiftOperands): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128ShiftOperation(args, cmds) { res, x, shift ->
            cmds += assign(res, sbfTacB { x.asSym() shiftL128 shift })
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeAshrti3(args: U128ShiftOperands): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128ShiftOperation(args, cmds) { res, x, shift ->
            cmds += assign(res, sbfTacB { x.asSym() shiftRArith128 shift })
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun getArgsFromU128BinaryCompilerRt(locInst: LocatedSbfInstruction): U128BinaryOperands? {
        val (resLow, resHigh, overflow) = getResFrom128(locInst) ?: return null
        val xLowE = sbfTacB.mkExprSym(Value.Reg(SbfRegister.R2))
        val xHighE = sbfTacB.mkExprSym(Value.Reg(SbfRegister.R3))
        val yLowE = sbfTacB.mkExprSym(Value.Reg(SbfRegister.R4))
        val yHighE = sbfTacB.mkExprSym(Value.Reg(SbfRegister.R5))
        return U128BinaryOperands(resLow.tacVar, resHigh.tacVar, overflow?.tacVar, xLowE, xHighE, yLowE, yHighE)
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun getArgsFromU128ShiftCompilerRt(locInst: LocatedSbfInstruction): U128ShiftOperands? {
        val (resLow, resHigh, _) = getResFrom128(locInst) ?: return null
        val xLowE = sbfTacB.mkExprSym(Value.Reg(SbfRegister.R2))
        val xHighE = sbfTacB.mkExprSym(Value.Reg(SbfRegister.R3))
        val shiftE = sbfTacB.mkExprSym(Value.Reg(SbfRegister.R4))
        return U128ShiftOperands(resLow.tacVar, resHigh.tacVar, xLowE, xHighE, shiftE)
    }
}

/**
 * Specialization using mathint.
 *
 * Only the operations that mathint actually makes more precise are overridden here.  `__multi3` is not one of them:
 * it wraps, so the exactness of a mathint product buys nothing, while the unbounded product and the narrowing that
 * comes with it are markedly harder to solve than a 256-bit multiplication.  Summarizing it over mathint made
 * `checked_mul_i64` and `saturating_mul_i64` - whose overflow check is one `__multi3` call - go from seconds to a
 * solver timeout.
 **/
class SummarizeIntegerU128CompilerRtWithMathInt<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    : SummarizeIntegerU128CompilerRt<TNum, TOffset, TFlags>() {

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeUDivti3(args: U128BinaryOperands): List<TACCmd.Simple> {
        // We are using 256-bits so division of 128-bits cannot overflow
        val xMath = vFac.mkFreshMathIntVar()
        val yMath = vFac.mkFreshMathIntVar()
        val resMath = vFac.mkFreshMathIntVar()
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128BinaryOperation(args, cmds) { res, _, x, y ->
            cmds += promoteToMathInt(x.asSym(), xMath)
            cmds += promoteToMathInt(y.asSym(), yMath)
            cmds += assign(resMath, sbfTacB { xMath.asSym() intDiv yMath.asSym() })
            cmds += narrowFromMathInt(resMath.asSym(), res)
        }
        return cmds
    }

    // Note that we don't override summarizeAshlti3 and summarizeAshrti3 because they cannot be defined
    // over mathematical integers.
}

