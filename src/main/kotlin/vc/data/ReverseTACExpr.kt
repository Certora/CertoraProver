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

package vc.data

import tac.Tag
import utils.*
import utils.ModZm.Companion.from2s
import utils.ModZm.Companion.to2s
import java.math.BigInteger
import datastructures.stdcollections.*

object ReverseTACExpr {
    class Contradiction(msg: String) : Exception(msg)

    /**
     * Attempts to reverse a TAC expression to find the value of a single unknown argument.
     *
     * Given an expression `e` with arguments `args` (where exactly one is null/unknown) and
     * a known result `res`, tries to determine the unique value of the unknown argument.
     *
     * Note: There are cases where we can figure out at least some of the arguments even if there is more
     * than one missing argument. The most important example is ite, where if we know the condition and the result,
     * we can say what is the value of one of the missing arguments. This is currently not handled.
     *
     * @param e The TAC expression to reverse
     * @param res The known result of evaluating the expression
     * @param args The arguments to the expression, with exactly one null value (the unknown)
     * @return The unique value of the unknown argument if determinable, null otherwise
     * @throws Contradiction if the known values and result are inconsistent
     */
    fun reverseTACExpr(e: TACExpr, res: BigInteger, args: List<BigInteger?>): BigInteger? {
        if (args.count { it == null } != 1) {
            return null
        }

        fun contradiction(): Nothing =
            throw Contradiction("$e, with $args and result = $res")

        val nullIndex = args.indexOfFirst { it == null }
        val knownValue by lazy {
            check(args.size == 2)
            args.firstNotNullOf { it }
        }

        val m by lazy { (e.tag as Tag.Bits).modulus }


        fun positiveDiv(result: BigInteger, known: BigInteger): BigInteger? {
            require(result >= BigInteger.ZERO && known >= BigInteger.ZERO)
            // to reverse we must have a unique solution.
            return when (nullIndex) {
                0 -> // res = x / knownValue
                    runIf(known == BigInteger.ONE) { result }

                1 -> // res = knownValue / x
                    when {
                        result == BigInteger.ZERO -> null
                        known == BigInteger.ZERO -> null
                        // We have a problem with rounding IntDiv on negatives, SMT valuation differs than eval.
                        // Once this is fixed, we can fix it here.
                        known < BigInteger.ZERO || result < BigInteger.ZERO -> null
                        else -> solveForDivisor(result, known)
                    }

                else -> `impossible!`
            }
        }

        return when (e) {
            is TACExpr.Sym -> error("Shouldn't reverse $e")

            is TACExpr.Vec.Add ->
                (res - args.filterNotNull().sumOf { it }).mod(m)

            is TACExpr.Vec.IntAdd ->
                res - (args.filterNotNull().reduceOrNull(BigInteger::plus) ?: BigInteger.ZERO)

            is TACExpr.Vec.Mul -> {
                val knownProduct = args.filterNotNull().reduceOrNull { acc, it -> (acc * it).mod(m) }
                    ?: BigInteger.ONE
                try {
                    (res * knownProduct.modInverse(m)).mod(m)
                } catch (_: ArithmeticException) {
                    return null
                }
            }

            is TACExpr.Vec.IntMul -> {
                val knownProduct = args.filterNotNull().reduceOrNull(BigInteger::multiply)
                    ?: BigInteger.ONE
                when {
                    knownProduct == BigInteger.ZERO ->
                        if (res == BigInteger.ZERO) {
                            null
                        } else {
                            contradiction()
                        }

                    res % knownProduct == BigInteger.ZERO -> res / knownProduct
                    else -> contradiction()
                }
            }

            is TACExpr.BinOp.Sub ->
                when (nullIndex) {
                    0 -> (res + knownValue).mod(m)
                    1 -> (knownValue - res).mod(m)
                    else -> `impossible!`
                }

            is TACExpr.BinOp.IntSub -> {
                when (nullIndex) {
                    0 -> res + knownValue
                    1 -> knownValue - res
                    else -> `impossible!`
                }
            }

            is TACExpr.BinOp.BWXOr ->
                res xor knownValue

            is TACExpr.BinOp.Div ->
                positiveDiv(res, knownValue)

            is TACExpr.BinOp.IntDiv ->
                if (res >= BigInteger.ZERO && knownValue >= BigInteger.ZERO) {
                    positiveDiv(res, knownValue)
                } else {
                    // to reverse we must have a unique solution.
                    when (nullIndex) {
                        0 -> // res = x / knownValue
                            runIf(knownValue == -BigInteger.ONE) { -res }

                        // We have a problem with rounding IntDiv on negatives, SMT valuation differs than eval.
                        // Once this is fixed, we can fix it here.
                        1 -> null

                        else -> `impossible!`
                    }
                }

            is TACExpr.BinOp.SDiv -> {
                val mathRes = res.from2s(e.tag!!)
                val mathKnown = knownValue.from2s(e.tag)
                positiveDiv(mathRes.abs(), mathKnown.abs())
                    ?.let { absAnswer ->
                        when (mathRes.signum() * mathKnown.signum()) {
                            0 -> null
                            1 -> absAnswer
                            -1 -> (-absAnswer).to2s(e.tag)
                            else -> `impossible!`
                        }
                    }
            }


            is TACExpr.BinOp.ShiftLeft,
            is TACExpr.BinOp.ShiftRightLogical,
            is TACExpr.BinOp.ShiftRightArithmetical ->
                // res = x shift y (any shift operation)
                when (nullIndex) {
                    0 -> // res = x shift knownValue
                        runIf(knownValue == BigInteger.ZERO) {
                            res  // x shift 0 = x, so x = res
                        }

                    1 -> // res = knownValue shift y
                        runIf(knownValue == res) {
                            BigInteger.ZERO  // Only y = 0 keeps value unchanged
                        }

                    else -> `impossible!`
                }

            is TACExpr.BinOp.BWAnd ->
                runIf(knownValue == (e.tag as Tag.Bits).maxUnsigned) { res }

            is TACExpr.BinOp.BWOr ->
                runIf(knownValue == BigInteger.ZERO) { res }

            is TACExpr.BinOp.IntExponent ->
                // res = x ^ y
                when (nullIndex) {
                    0 -> // res = x ^ knownValue
                        runIf(knownValue == BigInteger.ONE) { res }  // x^1 = x

                    1 -> // res = knownValue ^ y
                        runIf(knownValue == res) { BigInteger.ONE }  // x^y = x only when y=1

                    else -> `impossible!`
                }

            is TACExpr.BinOp.Exponent ->
                runIf(args[1] == BigInteger.ONE) { res } // x^1 = x

            is TACExpr.BinOp.SignExtend -> {
                check(e.tag is Tag.Bit256)
                // if the extension is at byte 32 or more, then it's a no-op.
                runIf(args[0]?.let { it >= BigInteger.valueOf(31) } == true) { res }
            }

            is TACExpr.BinOp.IntMod,
            is TACExpr.BinOp.Mod,
            is TACExpr.BinOp.SMod -> {
                // we could probably do a bit here as well...
                null
            }

            // just reverse
            is TACExpr.UnaryExp.BWNot -> e.eval(res)
            is TACExpr.UnaryExp.LNot -> e.eval(res)

            // Ternary operations
            is TACExpr.TernaryExp.Ite -> {
                when (nullIndex) {
                    0 -> when {
                        args[1] == args[2] ->
                            if (args[1] == res) {
                                null
                            } else {
                                contradiction()
                            }

                        args[1] == res -> BigInteger.ONE
                        args[2] == res -> BigInteger.ZERO
                        else -> contradiction()
                    }

                    1 -> runIf(args[0] == BigInteger.ONE) { res }
                    2 -> runIf(args[0] == BigInteger.ZERO) { res }
                    else -> `impossible!`
                }
            }

            is TACExpr.TernaryExp.AddMod,
            is TACExpr.TernaryExp.MulMod -> null

            // Apply (function calls)
            is TACExpr.Apply -> {
                val bif = (e.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif
                when (bif) {
                    null -> null
                    is TACBuiltInFunction.SafeMathNarrow,
                    is TACBuiltInFunction.SafeMathPromotion ->
                        res

                    is TACBuiltInFunction.TwosComplement.Unwrap ->
                        res.to2s(bif.tag)

                    is TACBuiltInFunction.TwosComplement.Wrap ->
                        res.from2s(bif.tag)

                    is TACBuiltInFunction.SafeSignedNarrow,
                    is TACBuiltInFunction.SafeUnsignedNarrow,
                    is TACBuiltInFunction.SignedPromotion,
                    is TACBuiltInFunction.UnsignedPromotion ->
                        null // those are unused afaik

                    TACBuiltInFunction.DisjointSighashes,
                    TACBuiltInFunction.LinkContractAddress,
                    is TACBuiltInFunction.NondetFunction,
                    is TACBuiltInFunction.OpaqueIdentity,
                    is TACBuiltInFunction.PartitionInitialize,
                    TACBuiltInFunction.PrecompiledECRecover,
                    is TACBuiltInFunction.ReadTransientPartition,
                    TACBuiltInFunction.ToStorageKey,
                    is TACBuiltInFunction.NoAddOverflowCheck,
                    is TACBuiltInFunction.NoMulOverflowCheck,
                    is TACBuiltInFunction.NoSAddOverAndUnderflowCheck,
                    is TACBuiltInFunction.NoSMulOverAndUnderflowCheck,
                    is TACBuiltInFunction.NoSSubOverAndUnderflowCheck ->
                        null
                }
            }

            is TACExpr.BinBoolOp.LAnd -> {
                val knownValues = args.filterNotNull().toSet()
                when (res) {
                    BigInteger.ONE -> when {
                        BigInteger.ZERO in knownValues -> contradiction()
                        else -> BigInteger.ONE
                    }

                    BigInteger.ZERO -> when {
                        BigInteger.ZERO in knownValues -> null
                        else -> BigInteger.ZERO
                    }

                    else -> `impossible!`
                }
            }

            is TACExpr.BinBoolOp.LOr -> {
                val knownValues = args.filterNotNull().toSet()
                when (res) {
                    BigInteger.ONE -> when {
                        BigInteger.ONE in knownValues -> null
                        else -> BigInteger.ONE
                    }

                    BigInteger.ZERO -> when {
                        BigInteger.ONE in knownValues -> contradiction()
                        else -> BigInteger.ZERO
                    }

                    else -> `impossible!`
                }
            }

            is TACExpr.BinRel.Eq ->
                runIf(res == BigInteger.ONE) { knownValue }

            is TACExpr.AnnotationExp<*> ->
                res

            is TACExpr.QuantifiedFormula,
            is TACExpr.LongStore,
            is TACExpr.MapDefinition,
            is TACExpr.MultiDimStore,
            is TACExpr.Select,
            is TACExpr.SimpleHash,
            is TACExpr.Store,
            is TACExpr.StructAccess,
            is TACExpr.StructConstant,
            is TACExpr.Unconstrained -> null

            is TACExpr.BinRel.Lt,
            is TACExpr.BinRel.Le,
            is TACExpr.BinRel.Gt,
            is TACExpr.BinRel.Ge,
            is TACExpr.BinRel.Slt,
            is TACExpr.BinRel.Sle,
            is TACExpr.BinRel.Sgt,
            is TACExpr.BinRel.Sge -> null // Cannot uniquely reverse these comparisons
        }?.also { answer ->
            val fullArgs = args.map { it ?: answer }
            val recalculated = e.eval(fullArgs)
            // println("Checked! $e, $args, $answer")
            check(recalculated == res) {
                "Oops, reversed $e, with $args and result = $res, got $answer, but recalculating gave $recalculated"
            }
        }
    }

    /**
     * Finds the unique positive integer `x` such that `a == b / x` under Kotlin's
     * truncating integer division, or returns `null` if no such unique `x` exists.
     *
     * Math:
     *   `a == b / x` (integer division) means  a <= b/x < a+1  (real division),
     *   which rearranges to:
     *
     *       b / (a + 1)  <  x  <=  b / a
     *
     *   So the valid x values are the integers in that half-open interval:
     *     - xMax = b / a              (largest valid x; integer division rounds down,
     *                                   which matches the inclusive upper bound)
     *     - xMin = b / (a + 1) + 1    (smallest valid x; the +1 turns the strict
     *                                   lower bound into an inclusive one)
     *
     *   The solution is unique iff exactly one integer lies in the interval,
     *   i.e. xMin == xMax. If xMin > xMax the interval is empty (no solution);
     *   if xMin < xMax there are multiple solutions. Both cases return null.
     *
     * Requires a > 0 and b > 0, which also guarantees we never divide by zero
     * and that xMax is well-defined.
     */
    private fun solveForDivisor(a: BigInteger, b: BigInteger): BigInteger? {
        require(a > BigInteger.ZERO && b > BigInteger.ZERO) { "a and b must be positive" }

        val xMax = b / a            // largest x with b/x >= a
        val xMin = b / (a + 1) + 1  // smallest x with b/x <= a

        return runIf(xMin == xMax) { xMax }
    }
}
