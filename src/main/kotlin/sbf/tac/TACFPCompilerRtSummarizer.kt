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

import datastructures.stdcollections.*
import sbf.domains.*
import utils.*
import vc.data.*

/**
 * Summarize floating point operations assuming IEEE-754 double precision (f64).
 *
 *  ```
 *      1       11          52
 *  | sign | exponent | mantissa |
 *  ```
 *
 * Not all functions are currently summarized. The current summaries just look at the bit patterns.
 **/
open class SummarizeFPCompilerRt<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> {

    private val plusInfBits = 0x7FF0_0000_0000_0000UL.toBigInteger()
    private val minusInfBits = 0xFFF0_0000_0000_0000UL.toBigInteger()
    private val minusZeroBits = 0x8000_0000_0000_0000UL.toBigInteger()
    private val minPositiveBits = 0x0010_0000_0000_0000UL.toBigInteger()
    private val twoBits = 0x4000_0000_0000_0000UL.toBigInteger()

    /**
     * Build expression for [v] to be `NaN`.
     *
     * A NaN number has:
     *  - Sign: 0 or 1
     *  - Exponent: all ones
     *  - Mantissa: at least one non-zero bit
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64NaN(v: TACSymbol): TACExpr {
        // Clear sign bit
        val absNum = sbfTacB { mask64(v.asSym()) bwAnd mkConst(0x7FFF_FFFF_FFFF_FFFFUL.toBigInteger()) }
        return sbfTacB { absNum gt mkConst(plusInfBits).asSym() }
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isNotf64NaN(v: TACSymbol) = sbfTacB { not(isf64NaN(v)) }

    /** Build expression for [v] to be `+oo` or `-oo` **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64Inf(v: TACSymbol): TACExpr {
        val plusInf = sbfTacB.mkConst(plusInfBits).asSym()
        val minusInf = sbfTacB.mkConst(minusInfBits).asSym()
        val v64 = sbfTacB.mask64(v.asSym())
        return sbfTacB { (v64 eq plusInf) or (v64 eq minusInf)}
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isNotf64Inf(v: TACSymbol) = sbfTacB { not(isf64Inf(v)) }

    /** Build expression for [v] to be `+0` **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64PlusZero(v: TACSymbol): TACExpr {
        return sbfTacB { mask64(v.asSym()) eq sbfTacB.ZERO}
    }

    /** Build expression for [v] to be `-0` **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64MinusZero(v: TACSymbol): TACExpr {
        return sbfTacB { mask64(v.asSym()) eq mkConst(minusZeroBits).asSym() }
    }

    /** Build expression for [v] to be `+0` or `-0` **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64Zero(v: TACSymbol) = sbfTacB { isf64PlusZero(v) or isf64MinusZero(v) }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64NonZero(v: TACSymbol) = sbfTacB { not(isf64Zero(v)) }

    /** Build expression for [v] to be any positive number, included +oo, +0, and subnormal numbers **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64Positive(v: TACSymbol): TACExpr {
        // v >> 63 == 0
        return sbfTacB { (v.asSym() shiftRLog  mkConst(63).asSym())  eq ZERO }
    }

    /** Build expression for [v] to be any negative number, included -oo, -0, and subnormal numbers **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64Negative(v: TACSymbol) = sbfTacB { not(isf64Positive(v)) }

    /**
     * A subnormal has:
     * - Sign: 0 or 1
     * - Exponent: all zeros
     * - Mantissa: at least one non-zero bit
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64Subnormal(v: TACSymbol): TACExpr {
        // clear sign bit
        val absNum = sbfTacB { v.asSym() bwAnd mkConst(0x7FFF_FFFF_FFFF_FFFFUL.toBigInteger()) }
        return  sbfTacB { absNum gt ZERO and (absNum lt mkConst(minPositiveBits).asSym()) }
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun isf64NonSubnormal(v: TACSymbol) = sbfTacB {  not(isf64Subnormal(v)) }


    /** Build expression if the low 64 bits of [v] is equal to 2 as f64 **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun isTwo(v: TACSymbol): TACExpr {
        // we need the in-bounds constraint because v is bv256
        return sbfTacB {
            (v lt U64_MAX) and (v eq mkConst(twoBits).asSym())
        }
    }

    /**
     * ```
     * int __unorddf2(double arg1, double arg2) {
     *    return (isnan(arg1) || isnan(arg2)) ? 1 : 0;
     * }
     * ```
     */
     context(SbfCFGToTAC<TNum, TOffset, TFlags>)
     internal open fun summarizeUnorddf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> =
        listOf(
            assign(res,
                sbfTacB{
                    switch(
                        isf64NaN(arg1) or isf64NaN(arg2)  to ONE,
                        default = ZERO
                    )
                }
            )

        )

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeAdddf3(
        res: TACSymbol.Var,
        @Suppress("UNUSED_PARAMETER")
        arg1: TACSymbol,
        @Suppress("UNUSED_PARAMETER")
        arg2: TACSymbol
    ): List<TACCmd.Simple> = listOf(havoc(res))


    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeSubdf3(
        res: TACSymbol.Var,
        @Suppress("UNUSED_PARAMETER")
        arg1: TACSymbol,
        @Suppress("UNUSED_PARAMETER")
        arg2: TACSymbol
    ): List<TACCmd.Simple> = listOf(havoc(res))

    protected data class FP64(
        val sign: TACExpr,
        val exp: TACExpr,
        val mantissa: TACExpr
    )


    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun nondetU64(cmds: MutableList<TACCmd.Simple>): TACSymbol.Var {
        val v = vFac.mkFreshIntVar()
        cmds += havoc(v)
        cmds += sbfTacB.assumeUnsignedIntRange(v, 64)
        return v
    }

    /**
     * Returns `(trueValue, falseValue)` for the `==` (eq) comparison:
     * - `trueValue  = 0`      (zero means "equal" )
     * - `falseValue = nondet` constrained to `!= 0`
     * All setup commands are appended to [cmds].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun eqReturnValues(cmds: MutableList<TACCmd.Simple>): Pair<TACExpr.Sym, TACExpr.Sym> {
        val trueS = sbfTacB.ZERO
        val falseV = nondetU64(cmds)
        cmds += nondetWithAssumptions(falseV, listOf(sbfTacB { falseV.asSym() neq trueS }))
        return trueS to falseV.asSym()
    }

    /**
     * Returns `(trueValue, falseValue)` for the `!=` (ne) comparison:
     * - `trueValue  = nondet` constrained to `!= 0`
     * - `falseValue = 0`
     * All setup commands are appended to [cmds].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun neReturnValues(cmds: MutableList<TACCmd.Simple>): Pair<TACExpr.Sym, TACExpr.Sym> {
        val falseS = sbfTacB.ZERO
        val trueV = nondetU64(cmds)
        cmds += nondetWithAssumptions(trueV, listOf(sbfTacB { trueV.asSym() neq falseS }))
        return trueV.asSym() to falseS
    }

    /**
     * Returns `(trueValue, falseValue)` for the `<` (lt) comparison:
     * - `trueValue  = nondet` constrained to `< 0`
     * - `falseValue = nondet` constrained to `>= 0`
     * All setup commands are appended to [cmds].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun ltReturnValues(cmds: MutableList<TACCmd.Simple>): Pair<TACExpr.Sym, TACExpr.Sym> {
        val trueV = nondetU64(cmds)
        val falseV = nondetU64(cmds)
        cmds += nondetWithAssumptions(trueV, listOf(sbfTacB { trueV.asSym() sLt ZERO }))
        cmds += nondetWithAssumptions(falseV, listOf(sbfTacB { falseV.asSym() sGe ZERO }))
        return trueV.asSym() to falseV.asSym()
    }

    /**
     * Returns `(trueValue, falseValue)` for the `<=` (le) comparison:
     * - `trueValue  = nondet` constrained to `<= 0`
     * - `falseValue = nondet` constrained to `> 0`
     * All setup commands are appended to [cmds].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun leReturnValues(cmds: MutableList<TACCmd.Simple>): Pair<TACExpr.Sym, TACExpr.Sym> {
        val trueV = nondetU64(cmds)
        val falseV = nondetU64(cmds)
        cmds += nondetWithAssumptions(trueV, listOf(sbfTacB { trueV.asSym() sLe ZERO }))
        cmds += nondetWithAssumptions(falseV, listOf(sbfTacB { falseV.asSym() sGt ZERO }))
        return trueV.asSym() to falseV.asSym()
    }

    /**
     * Returns `(trueValue, falseValue)` for the `>=` (ge) comparison:
     * - `trueValue  = nondet` constrained to `>= 0`
     * - `falseValue = nondet` constrained to `< 0`
     * All setup commands are appended to [cmds].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun geReturnValues(cmds: MutableList<TACCmd.Simple>): Pair<TACExpr.Sym, TACExpr.Sym> {
        val trueV = nondetU64(cmds)
        val falseV = nondetU64(cmds)
        cmds += nondetWithAssumptions(trueV, listOf(sbfTacB { trueV.asSym() sGe ZERO }))
        cmds += nondetWithAssumptions(falseV, listOf(sbfTacB { falseV.asSym() sLt ZERO }))
        return trueV.asSym() to falseV.asSym()
    }

    /**
     * Returns `(trueValue, falseValue)` for the `>` (gt) comparison:
     * - `trueValue  = nondet` constrained to `> 0`
     * - `falseValue = nondet` constrained to `<= 0`
     * All setup commands are appended to [cmds].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun gtReturnValues(cmds: MutableList<TACCmd.Simple>): Pair<TACExpr.Sym, TACExpr.Sym> {
        val trueV = nondetU64(cmds)
        val falseV = nondetU64(cmds)
        cmds += nondetWithAssumptions(trueV, listOf(sbfTacB { trueV.asSym() sGt ZERO }))
        cmds += nondetWithAssumptions(falseV, listOf(sbfTacB { falseV.asSym() sLe ZERO }))
        return trueV.asSym() to falseV.asSym()
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun unpackFP64(n: TACSymbol, cmds: MutableList<TACCmd.Simple>): FP64 {
        val sign = vFac.mkFreshIntVar()
        val exp = vFac.mkFreshIntVar()
        val mantissa = vFac.mkFreshIntVar()

        cmds += assign(sign,sbfTacB { n shiftRArith mkConst(63) })
        cmds += assign(exp, sbfTacB { (n shiftRLog mkConst(52)) bwAnd mkConst(0x7ff) })
        cmds += assign(mantissa, sbfTacB { n.asSym() bwAnd mkConst(0x000F_FFFF_FFFF_FFFFUL.toBigInteger()) })

        return FP64(sign.asSym(), exp.asSym(), mantissa.asSym())
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    protected fun packFP64(n: FP64, cmds: MutableList<TACCmd.Simple>): TACSymbol {
        val res = vFac.mkFreshIntVar()
        val signBit  = sbfTacB.mkConst(minusZeroBits)      // 2^63
        val expShift = sbfTacB.mkConst(minPositiveBits)    // 2^52
        cmds += assign(res, sbfTacB {
            mask64((n.sign mul signBit).addNoOvf(n.exp mul expShift, "packing FP64").addNoOvf(n.mantissa, "packing FP64"))
        })
        return res
    }

    /** Assume that [n] corresponds to a normal floating point number. **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun multipleByTwo(n: TACSymbol, cmds: MutableList<TACCmd.Simple>): TACSymbol {
        val fp = unpackFP64(n, cmds)

        val isOverflow  = sbfTacB { fp.exp ge mkConst(0x7FE) }
        val newExp = vFac.mkFreshIntVar()
        cmds += assign(newExp, sbfTacB {
            switch(
                isOverflow to mkConst(0x7FF).asSym(),
                default = fp.exp.addNoOvf(ONE, "fp multiply by two")
            )
        })

        val newMantissa = vFac.mkFreshIntVar()
        cmds += assign(newMantissa, sbfTacB {
            switch(
                isOverflow to ZERO,
                default = fp.mantissa
            )
        })

        return packFP64(FP64(fp.sign, newExp.asSym(), newMantissa.asSym()), cmds)
    }


    /**
     * ```
     * if isNaN(arg1) || isNaN(arg2) {
     *    NaN
     * } else if (isInf(arg1) && arg2 ==0 ) || (arg1 == 0 && isInf(arg2) {
     *    NaN
     * } else if (isInf(arg1) || isInf(arg2)) {
     *    Inf
     * } else if (arg1 == 0 || arg2 == 0) {
     *    0
     * } else {
     *    nondet
     * }
     * ```
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeMuldf3(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {

        val initCmds = mutableListOf<TACCmd.Simple>()
        val nanV    = nondetU64(initCmds)
        val infV    = nondetU64(initCmds)
        val nonNaNV = nondetU64(initCmds)
        val zeroV   = nondetU64(initCmds)

        val cmds = mutableListOf<TACCmd.Simple>()

        val twoTimesArg1 = multipleByTwo(arg1, cmds).asSym()
        val twoTimesArg2 = multipleByTwo(arg2, cmds).asSym()

        return  initCmds +
                nondetWithAssumptions(nanV, listOf(isf64NaN(nanV))) +
                nondetWithAssumptions(infV, listOf(isf64Inf(infV))) +
                nondetWithAssumptions(nonNaNV, listOf(isNotf64NaN(nonNaNV))) +
                nondetWithAssumptions(zeroV, listOf(isf64Zero(zeroV))) +
                cmds +
                assign(res,
                    sbfTacB {
                        switch(
                            // NaN, Inf or 0
                            isf64NaN(arg1) or isf64NaN(arg2) to nanV.asSym(),
                            (isf64Inf(arg1) and isf64Zero(arg2)) or (isf64Zero(arg1) and isf64Inf(arg2)) to nanV.asSym(),
                            isf64Inf(arg1) or isf64Inf(arg2)  to infV.asSym(),
                            isf64Zero(arg1) to arg1.asSym(),
                            isf64Zero(arg2) to arg2.asSym(),
                            // 2 * Normal
                            isTwo(arg1) and isf64NonSubnormal(arg2) to twoTimesArg2,
                            // Normal * 2
                            isTwo(arg2) and isf64NonSubnormal(arg1) to twoTimesArg1,
                            // Subnormal * Subnormal -> Zero
                            isf64Subnormal(arg1) and isf64Subnormal(arg2) to zeroV.asSym(),
                            // Normal * Normal
                            // Normal * Subnormal
                            // Subnormal * Normal
                            default = nonNaNV.asSym()
                        )
                    }
                )

    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeDivdf3(
        res: TACSymbol.Var,
        @Suppress("UNUSED_PARAMETER")
        arg1: TACSymbol,
        @Suppress("UNUSED_PARAMETER")
        arg2: TACSymbol
    ): List<TACCmd.Simple> = listOf(havoc(res))


    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeNegdf3(
        res: TACSymbol.Var,
        @Suppress("UNUSED_PARAMETER")
        arg: TACSymbol
    ): List<TACCmd.Simple> = listOf(havoc(res))

    /**
     * Convert [arg] as f64 to u64
     *
     * ```
     * if isNaN(arg) || arg == 0 || isNegative(arg) || isSubnormal(arg) {
     *    0
     * } else {
     *    res = nondet()
     *    assume(res != 0)
     *    res
     *  }
     * ```
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeFixunsdfdi(
        res: TACSymbol.Var,
        arg: TACSymbol
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val nonZeroV = vFac.mkFreshIntVar()
        return cmds +
            nondetWithAssumptions(nonZeroV, listOf(sbfTacB { nonZeroV neq sbfTacB.ZERO})) +
            sbfTacB.assumeUnsignedIntRange(nonZeroV, 64) +
            assign(res,
                sbfTacB {
                    switch(
                        isf64Zero(arg) or isf64NaN(arg) or isf64Negative(arg) or isf64Inf(arg) or isf64Subnormal(arg) to ZERO,
                        default = nonZeroV.asSym()
                    )
                }
            )
    }

    /**
     * Convert [arg] as u64 to f64
     *
     * ```
     * if (arg == 0) {
     *    arg
     * } else {
     *    res = nondet()
     *    assume(res != 0)
     *    assume(res != NaN)
     *    assume(res != Inf)
     *    res
     *  }
     * ```
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeFloatundidf(
        res: TACSymbol.Var,
        arg: TACSymbol
    ): List<TACCmd.Simple> {

        val cmds = mutableListOf<TACCmd.Simple>()
        val zeroV = nondetU64(cmds)
        val posV = nondetU64(cmds)
        val negV = nondetU64(cmds)
        return  cmds +
                nondetWithAssumptions(
                    posV,
                    listOf(isf64NonZero(posV),
                        isNotf64NaN(posV),
                        isNotf64Inf(posV),
                        isf64NonSubnormal(posV),
                        isf64Positive(posV)
                )) +
                nondetWithAssumptions(
                negV,
                listOf(isf64NonZero(negV),
                    isNotf64NaN(negV),
                    isNotf64Inf(negV),
                    isf64NonSubnormal(negV),
                    isf64Negative(negV)
                )) +
                nondetWithAssumptions(zeroV, listOf(isf64Zero(zeroV))) +
                assign(res,
                    sbfTacB {
                        switch(
                            arg eq ZERO to zeroV.asSym(),
                            (arg gt ZERO) and (arg le mkConst(0x7FFFFFFFFFFFFFFFUL.toBigInteger())) to posV.asSym(),
                            default = negV.asSym()
                        )
                    }
                )
    }

    /** Return zero if neither argument is NaN, and [arg1] and [arg2] are equal. **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeEqdf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val (trueS, falseV) = eqReturnValues(cmds)
        return cmds +
            assign(res,
                sbfTacB {
                    switch(
                        isf64NaN(arg1) or isf64NaN(arg2) to falseV,
                        // +0 and -0 are equal
                        (isf64PlusZero(arg1) and isf64MinusZero(arg2)) or (isf64MinusZero(arg1) and isf64PlusZero(arg2)) to trueS,
                        arg1 eq arg2 to trueS,
                        default = falseV
                    )
                }
            )
    }

    /** Return a nonzero value if either argument is NaN, or if [arg1] and [arg2] are unequal **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeNedf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val (trueV, falseS) = neReturnValues(cmds)
        return cmds +
            assign(res,
                sbfTacB {
                    switch(
                        // +0 and -0 are equal
                        (isf64PlusZero(arg1) and isf64MinusZero(arg2)) or (isf64MinusZero(arg1) and isf64PlusZero(arg2)) to falseS,
                        isf64NaN(arg1) or isf64NaN(arg2) or (arg1 neq arg2) to trueV,
                        default = falseS
                    )
                }
            )
    }

    /** Return a value less than zero if neither argument is NaN, and [arg1] is strictly less than [arg2]. **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeLtdf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val (trueV, falseV) = ltReturnValues(cmds)
        return cmds +
            summarizeBinRel(res, arg1, arg2,
                eitherNaN = falseV,
                bothZero = falseV,
                less = trueV,
                greater = falseV
            )
    }

    /** Return a value less than or equal to zero if neither argument is NaN, and [arg1] is less than or equal to [arg2] **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeLedf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val (trueV, falseV) = leReturnValues(cmds)
        return cmds +
            summarizeBinRel(res, arg1, arg2,
                eitherNaN = falseV,
                bothZero = trueV,
                less = trueV,
                greater = falseV
            )
    }

    /**
     * return a value greater than or equal to zero if neither argument is NaN, and [arg1] is greater than or equal to [arg2].
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeGedf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val (trueV, falseV) = geReturnValues(cmds)
        return cmds +
            summarizeBinRel(res, arg1, arg2,
                eitherNaN = falseV,
                bothZero = trueV,
                less = falseV,
                greater = trueV
            )
    }

    /**
     * Return a value greater than zero if neither argument is `NaN`, and [arg1] is strictly greater than [arg2].
     *
     * When [arg2] is the constant `0x43EFFFFFFFFFFFFF` (the largest f64 whose integer part fits in u64,
     * equal to `(2^53 − 1) × 2^11 = 2^64 − 2048`), the comparison is resolved precisely:
     * - If arg1 is negative it is less than arg2, so the result is false.
     * - For positive f64 values the unsigned bit pattern preserves the float order, so
     *   `arg1 > arg2` iff `bit63(arg1) == 0` and `mask64(arg1) > 0x43EFFFFFFFFFFFFF`.
     *
     * The comparison `> 0x43EFFFFFFFFFFFFF_f64` is important because it's used whenever a f64 is truncated to an u64:
     * ```
     * if float > 0x43EFFFFFFFFFFFFF_f64 {
     *     u64::MAX          // covers 2^64, 2^64+2048, infinity, NaN-interpreted-as-positive, ...
     * } else {
     *     __fixunsdfdi(..)  // safe: value ∈ [0, 2^64 - 2048], exact truncation
     * }
     * ```
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal open fun summarizeGtdf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val (trueV, falseV) = gtReturnValues(cmds)
        val nondetV = nondetU64(cmds)
        val maxU64Fp = sbfTacB.mkConst(0x43EF_FFFF_FFFF_FFFFUL.toBigInteger())
        return cmds +
            assign(res,
                sbfTacB {
                    switch(
                        isf64NaN(arg1) or isf64NaN(arg2) to falseV,
                        *zeroCases(arg1, arg2, bothZero = falseV, less = falseV, greater = trueV),
                        // arg2 == 0x43EFFFFFFFFFFFFF and arg1 negative and arg1 < 0 < arg2 →  false
                        (mask64(arg2.asSym()) eq maxU64Fp) and isf64Negative(arg1) to falseV,
                        // arg2 == 0x43EFFFFFFFFFFFFF and arg1 positive and arg1 > arg2 → true
                        (mask64(arg2.asSym()) eq maxU64Fp) and isf64Positive(arg1) and (mask64(arg1.asSym()) gt maxU64Fp) to trueV,
                        // arg2 == 0x43EFFFFFFFFFFFFF and arg1 positive and arg1 ≤ arg2 → false
                        mask64(arg2.asSym()) eq maxU64Fp to falseV,
                        default = nondetV.asSym()
                    )
                }
            )
    }

    /**
     * This is a generic summarizer for a binary relational operators: `lt`, `le`, `gt`, and `ge`.
     *
     * ```
     * return if isNaN(arg1) || isNaN(arg2)
     *     eitherNaN
     * else if arg1 == 0 || arg2 == 0
     *     zeroCases(arg1, arg2, bothZero, less, greater)
     * else
     *     nondet
     * ```
     * @param [arg1] represents a f64 number.
     * @param [arg2] represents a f64 number.
     * @param [eitherNaN] represents a u64 number.
     * @param [bothZero] represents a u64 number.
     * @param [less] represents a u64 number.
     * @param [greater] represents a u64 number.
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun summarizeBinRel(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol,
        eitherNaN: TACExpr.Sym,
        bothZero: TACExpr.Sym,
        less: TACExpr.Sym,
        greater: TACExpr.Sym
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        // `nondetV` represents a u64 number
        val nondetV = nondetU64(cmds)
        return  cmds  +
                assign(res,
                sbfTacB {
                    switch(
                        isf64NaN(arg1) or isf64NaN(arg2) to eitherNaN,
                        *zeroCases(arg1, arg2, bothZero, less, greater),
                        default = nondetV.asSym()
                    )
                }
            )
    }

    /**
     * `switch` cases resolving the order of [arg1] and [arg2] when at least one of them is `+0` or `-0`.
     * Both must already be known not to be `NaN`.
     *
     * If only one operand is zero, the other is non-zero and not `NaN`, so its sign bit alone decides the order.
     *
     * @param [bothZero] the result when [arg1] and [arg2] are both zero, hence equal.
     * @param [less] the result when [arg1] < [arg2].
     * @param [greater] the result when [arg1] > [arg2].
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun zeroCases(
        arg1: TACSymbol,
        arg2: TACSymbol,
        bothZero: TACExpr.Sym,
        less: TACExpr.Sym,
        greater: TACExpr.Sym
    ): Array<Pair<TACExpr, TACExpr>> = with(sbfTacB) {
        arrayOf<Pair<TACExpr, TACExpr>>(
            isf64Zero(arg1) and isf64Zero(arg2) to bothZero,
            isf64Zero(arg1) and isf64Positive(arg2) to less,
            isf64Zero(arg1) to greater,
            isf64Zero(arg2) and isf64Negative(arg1) to less,
            isf64Zero(arg2) to greater
        )
    }
}
