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

package analysis.ip

import analysis.CmdPointer
import analysis.MustBeConstantAnalysis
import analysis.dataflow.SimpleAssignmentChain
import analysis.ip.SafeCastingAnnotator.CastType
import config.Config
import tac.MetaKey
import utils.*
import vc.data.*
import vc.data.taccmdutils.TACCmdUtils.rhsExpr
import vc.data.tacexprutil.asSymOrNull
import java.math.BigInteger
import java.util.stream.Collectors
import datastructures.stdcollections.*


/**
 * Looks for specific `mload` commands, added in our python instrumentation, which encode a possibly overflowing
 * operation within an unchecked code block. This mload is removed and an annotation with the decoded information is
 * added.
 */
object UncheckedOverflowAnnotator {

    private val overflowUpperBits = BigInteger("ffffff6e4604afefe123321beef1b04fffffffffffffffffff", 16)

    enum class OperatorType {
        Mul, Add, Sub
    }

    fun TACExpr.opType() =
        when(this) {
            is TACExpr.Vec.Mul -> OperatorType.Mul
            is TACExpr.Vec.Add -> OperatorType.Add
            is TACExpr.BinOp.Sub -> OperatorType.Sub
            else -> null
        }

    @KSerializable
    data class OverflowOpInfo(
        val resType: CastType,
        val arg1: TACSymbol,
        val arg2: TACSymbol,
        val opType: OperatorType,
        val range: Range.Range?,
    ) : AmbiSerializable, TransformableSymEntity<OverflowOpInfo>, WithSupport {
        override fun transformSymbols(f: (TACSymbol) -> TACSymbol) = copy(arg1 = f(arg1), arg2 = f(arg2))
        override val support = setOfNotNull(arg1 as? TACSymbol.Var, arg2 as? TACSymbol.Var)
    }

    val UncheckedOverflowKey = MetaKey<OverflowOpInfo>("tac.unchecked.overflow.builtin.key")

    /**
     * looks for commands of the form `ByteStore(<mem address>, <magic constant>)`
     * that we instrumented in python land; replaces them with an annotation so that they can be used in the
     * overflowCheck builtin rule.
     */
    fun annotate(code: CoreTACProgram): CoreTACProgram {
        if (!Config.UncheckedOverflowBuiltin.get()) {
            return code
        }
        val g = code.analysisCache.graph
        val constantAnalysis = MustBeConstantAnalysis(g)

        /** Returns the ptr where the overflow op is at, and the relevant [OverflowOpInfo] */
        fun p(ptr: CmdPointer, sym: TACSymbol, const: BigInteger): Pair<CmdPointer, OverflowOpInfo>? {
            if (sym !is TACSymbol.Var) {
                return null
            }
            // the numbers below are the the number of bitwidths of the encoded information.
            val (mask, line, column, resType) = const.parseToParts(256 - 56, 20, 20, 16)
            if (mask != overflowUpperBits) {
                return null
            }
            val opPtr = SimpleAssignmentChain.lookIn(g, ptr, sym) { (_, cmd), _ ->
                cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.rhs.opType() != null
            } ?: return null
            val opCmd = g.toCommand(opPtr)
            val rhs = opCmd.rhsExpr!!
            val ops = rhs.getOperands()
                .monadicMap { it.asSymOrNull }
                ?.takeIf { it.size == 2 }
                ?: return null
            return opPtr to OverflowOpInfo(
                resType = CastType(resType),
                arg1 = ops[0],
                arg2 = ops[1],
                opType = rhs.opType()!!,
                range = g.toCommand(ptr).sourceRange()?.makeNewRange(line, column),
            )
        }

        val patcher = code.toPatchingProgram()
        val uncheckedOverflows = code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            if (cmd is TACCmd.Simple.AssigningCmd.ByteStore && cmd.base == TACKeyword.MEMORY.toVar()) {
                constantAnalysis.mustBeConstantAt(ptr, cmd.loc)
                    ?.let { ptr `to?` p(ptr, cmd.value, it) }
            } else {
                null
            }
        }.collect(Collectors.toList())

        uncheckedOverflows.forEach { (mloadPtr, ptrAndInfo) ->
            val (opPtr, info) = ptrAndInfo
            patcher.update(opPtr, TACCmd.Simple.AnnotationCmd(UncheckedOverflowKey, info))
            patcher.delete(mloadPtr)
        }
        return patcher.toCodeNoTypeCheck(code)
    }
}
