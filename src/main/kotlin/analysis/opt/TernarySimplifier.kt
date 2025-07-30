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

package analysis.opt

import analysis.ExprView
import analysis.LTACCmdView
import analysis.enarrow
import analysis.maybeNarrow
import analysis.opt.TernarySimplifier.StatKeys.*
import analysis.split.Ternary
import analysis.split.Ternary.Companion.bwNot
import analysis.split.Ternary.Companion.containedIn
import analysis.split.TernaryCalculator
import evm.EVM_BYTE_SIZE
import log.*
import tac.MetaKey
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import java.math.BigInteger

/** Used to prevent creating the same bwOr simplification twice */
private val IntroducedBwOr = MetaKey.Nothing("bwOr.already.simplified")

/**
 * Uses [TernaryCalculator] to simplifications of the code. See the specific simplify functions for details.
 * Currently, this is only done for top level assignments, and will not simplify a sub-expression.
 */
object TernarySimplifier {

    private enum class StatKeys(val message: String) {
        OR_FULL("bwOrs to plus"),
        OR_PARTIAL("bwOrs to ite"),
        AND_FULL("excess bwAnd"),
        SIGN_EXT("excess signExtend"),
        CONSTANT("inlined constant");

        override fun toString() = message
    }

    /**
     * Entry Point.
     * [afterSummarization] should be false if this is run before summarization was inlined.
     */
    fun simplify(
        code: CoreTACProgram,
        afterSummarization: Boolean,
        forbiddenVars: Set<String> = setOf(TACKeyword.MEM64.getName())
    ): CoreTACProgram {

        /** Summarization needs these to not be simplified. */
        val mentionedVars = if (afterSummarization) {
            emptySet()
        } else {
            code.internalFuncExitVars
        }

        /**
         * Before summarization, we won't see havocs of storage/transient storage, or
         * inlinings of functions that might mutate storage variables. This will mean that
         * if scalarized storage variables are assigned constant, expected to be mutated in calls,
         * and then read out later, the ternary optimizer will only see the constant value and incorrectly
         * optimize the program. The solution is to just have the ternary optimizer treat scalarized
         * storage variables as opaque.
         */
        val isStorage : (TACSymbol.Var) -> Boolean = if(!afterSummarization) {
            { v: TACSymbol.Var ->
                TACMeta.STORAGE_KEY in v.meta || TACMeta.TRANSIENT_STORAGE_KEY in v.meta
            }
        } else {
            { _ -> false }
        }

        fun isForbidden(v: TACSymbol.Var) =
            v.namePrefix in forbiddenVars || v in mentionedVars || isStorage(v)

        val logger = Logger(LoggerTypes.TERNARY_SIMPLIFIER)

        val ternaries = TernaryCalculator(code, isForbiddenVar = ::isForbidden, highPrecision = true)

        val patcher = ConcurrentPatchingProgram(code)

        val stats = ConcurrentCounterMap<StatKeys>()

        /** If rhs is actually a constant according to [TernaryCalculator] */
        fun simplifyConst(lcmd: LTACCmdView<AssignExpCmd>): List<TACCmd.Simple>? {
            val c = ternaries.getLhs(lcmd.ptr).asConstantOrNull()
                ?: return null
            stats.plusOne(CONSTANT)
            return listOf(
                lcmd.cmd.copy(rhs =
                    when (lcmd.cmd.rhs.tag) {
                        Tag.Bool -> c.asBoolTACSymbol().asSym()
                        else -> c.asTACSymbol().asSym()
                    }
                )
            )
        }

        /**
         * If each bit from 0-255 is zero in at least one of bwor operands, then the bwor can be transformed to a plus.
         * Otherwise, for expression a | b, it checks how many of the lower order bits of a are surely 0. Denote this
         * number z. We can rewrite c := a|b to be:
         *
         * c := ite(
         *        b <= 2^z - 1,
         *        a + b,
         *        a | b
         *      )
         *
         * Drawback: this transformation is unnecessary for BV-theory, yet we do it anyway. Postponing it has complications
         * at the moment. However, the price is probably negligible.
         */
        fun simplifyBwOr(lcmd: ExprView<TACExpr.BinOp.BWOr>): List<TACCmd.Simple>? {
            val (o1, o2) = lcmd.exp.getOperands()
            val t1 = ternaries.getRhs(lcmd.ptr, o1)
            val t2 = ternaries.getRhs(lcmd.ptr, o2)

            /** Returns the number of consecutive least-significant zero bits */
            fun numLowZeros(t: Ternary) =
                if (t is Ternary.NonBottom) {
                    bwNot(t.zeros).lowestSetBit
                } else {
                    0
                }

            val z1 = numLowZeros(t1)
            val z2 = numLowZeros(t2)

            /** This is for the complex case where we don't know whether the bw-or is a plus, but it has a chance! */
            fun f(z: Int, boundOp: TACExpr): List<TACCmd.Simple> {
                val b1 = patcher.newTempVar("BwOrCond", Tag.Bool)
                val bwor = patcher.newTempVar("BwOrAsIs", Tag.Bit256)
                val plus = patcher.newTempVar("BwOrPlus", Tag.Bit256)
                val bound = BigInteger.TWO.pow(z) - BigInteger.ONE
                stats.plusOne(OR_PARTIAL)
                return listOf(
                    AssignExpCmd(b1, TACExpr.BinRel.Le(boundOp, bound.asTACSymbol().asSym())),
                    lcmd.cmd.copy(lhs = bwor, meta = lcmd.cmd.meta + IntroducedBwOr),
                    AssignExpCmd(plus, TACExpr.Vec.Add(o1, o2)),
                    lcmd.cmd.copy(
                        rhs = TACExpr.TernaryExp.Ite(
                            i = b1.asSym(),
                            t = plus.asSym(),
                            e = bwor.asSym()
                        )
                    )
                )
            }

            val tag = lcmd.exp.tagAssumeChecked as Tag.Bits

            return when {
                t1 and t2 == Ternary.zero -> {
                    stats.plusOne(OR_FULL)
                    listOf(
                        lcmd.cmd.copy(
                            rhs = TACExpr.Vec.Add(o1, o2, tag)
                        )
                    )
                }

                z1 < z2 && IntroducedBwOr !in lcmd.cmd.meta -> f(z2, o1)

                z2 < z1 && IntroducedBwOr !in lcmd.cmd.meta -> f(z1, o2)

                else -> null
            }
        }


        /** If all nonZeros of one operand are 1s in the other, then bwAnd is unnecessary.*/
        fun simplifyBwAnd(lcmd: ExprView<TACExpr.BinOp.BWAnd>): List<TACCmd.Simple>? {

            /** this is for simplification of a 0xfff00 mask into a 0xfffff if possible */
            fun f(mask: BigInteger, o: TACExpr, t: Ternary.NonBottom): List<TACCmd.Simple>? {
                val lowBits = ModZm.lowOnes(mask.lowestSetBit) // this would be 0xff in the example.
                return runIf(lowBits containedIn t.zeros) {
                    listOf(lcmd.cmd.copy(rhs = TACExpr.BinOp.BWAnd(o, (mask or lowBits).asTACExpr)))
                }
            }

            val (o1, o2) = lcmd.exp.getOperands()
            val t1 = ternaries.getRhs(lcmd.ptr, o1)
            val t2 = ternaries.getRhs(lcmd.ptr, o2)
            return if (t1 is Ternary.NonBottom && t2 is Ternary.NonBottom) {

                when {
                    bwNot(t1.zeros) containedIn t2.ones ->
                        listOf(lcmd.cmd.copy(rhs = o1))

                    bwNot(t2.zeros) containedIn t1.ones ->
                        listOf(lcmd.cmd.copy(rhs = o2))

                    t1.isConstant() ->
                        f(t1.asConstant(), o2, t2)

                    t2.isConstant() ->
                        f(t2.asConstant(), o1, t1)

                    else -> null
                }
            } else {
                null
            }?.also {
                stats.plusOne(AND_FULL)
            }
        }

        /** If operand is already sign-extended, no need for the sign-extend.*/
        fun simplifySignExtend(lcmd: ExprView<TACExpr.BinOp.SignExtend>): List<TACCmd.Simple>? {
            val (o1, o2) = lcmd.exp.getOperands()
            val b = o1.getAsConst()?.toIntOrNull() ?: return null
            val t2 = ternaries.getRhs(lcmd.ptr, o2)
            if (t2 is Ternary.NonBottom && t2.signExtendBit <= EVM_BYTE_SIZE.intValueExact() * (b + 1)) {
                stats.plusOne(SIGN_EXT)
                return listOf(lcmd.cmd.copy(lhs = lcmd.lhs, rhs = o2))
            }
            return null
        }

        /** Runs on all commands */
        fun simplify() {
            code.parallelLtacStream()
                .mapNotNull { it.maybeNarrow<AssignExpCmd>() }
                .mapNotNull { lcmd ->
                    if (lcmd.cmd.rhs is TACExpr.Sym.Const) {
                        return@mapNotNull null
                    }
                    simplifyConst(lcmd)?.let {
                        return@mapNotNull it to lcmd
                    }
                    when (lcmd.cmd.rhs) {
                        is TACExpr.BinOp.BWOr -> simplifyBwOr(lcmd.wrapped.enarrow())
                        is TACExpr.BinOp.BWAnd -> simplifyBwAnd(lcmd.wrapped.enarrow())
                        is TACExpr.BinOp.SignExtend -> simplifySignExtend(lcmd.wrapped.enarrow())
                        else -> null
                    }?.to(lcmd)
                }
                .forEach { (newCmds, lcmd) ->
                    logger.debug {
                        "${lcmd.ptr} :\n   ${lcmd.cmd.toStringNoMeta()} --->\n    " +
                            newCmds.joinToString("\n") { "       ${it.toStringNoMeta()}" }
                    }
                    patcher.replace(lcmd.ptr, newCmds)
                }
        }

        simplify()
        logger.info {
            stats.toString("${javaClass.simpleName} (afterSummarization = $afterSummarization)")
        }
        return patcher.toCode()
    }
}

