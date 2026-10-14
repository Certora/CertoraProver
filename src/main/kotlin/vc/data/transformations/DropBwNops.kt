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

package vc.data.transformations

import evm.MAX_EVM_UINT256
import tac.Tag
import vc.data.ConcurrentPatchingProgram
import vc.data.CoreTACProgram
import vc.data.IndexingDefaultTACCmdMapperWithPointer
import vc.data.TACExpr
import vc.data.tacexprutil.QuantDefaultTACExprTransformerWithPointer
import vc.data.tacexprutil.TACExprFactSimple
import java.math.BigInteger

/**
 * TAC program transformation that eliminates "bitwise noops", e.g. shifts by 0, etc.
 *
 * Notes: This transformation assumes TACSimpleSimple. Perhaps it could be a good idea to do it much earlier, e.g., to
 *  make the lives of other analyses easier, then this would also have to transform commands, not just expressions.
 */
object DropBwNops {

    fun annotate(inputProgram: CoreTACProgram): CoreTACProgram {
        val patchingProg = ConcurrentPatchingProgram(inputProgram)

        inputProgram.parallelLtacStream().forEach { ltacCmd ->
            val mapped = dropBwOpsMapper.mapCommand(ltacCmd)
            // this guard is meant to reduce synchronisation pressure on the concurrent data structures
            if (mapped.size != 1 || mapped.first() != ltacCmd.cmd) {
                patchingProg.replace(ltacCmd.ptr, mapped)
            }
        }

        return patchingProg.toCode()
    }

    private val dropBwOpsMapper = object : IndexingDefaultTACCmdMapperWithPointer() {
        override val exprMapper = object : QuantDefaultTACExprTransformerWithPointer() {

            override fun transformBWAnd(
                acc: QuantVarsAndExpPointer,
                o1: TACExpr,
                o2: TACExpr,
                tag: Tag?
            ): TACExpr {
                val o1transformed = transformArg(acc, o1, 0)
                val o2transformed = transformArg(acc, o2, 1)
                return when (MAX_EVM_UINT256) {
                    o1transformed.evalAsConst() -> o2transformed
                    o2transformed.evalAsConst() -> o1transformed
                    else -> TACExprFactSimple.BWAnd(o1transformed, o2transformed, tag)
                }
            }

            override fun transformBWOr(
                acc: QuantVarsAndExpPointer,
                o1: TACExpr,
                o2: TACExpr,
                tag: Tag?
            ): TACExpr {
                val o1transformed = transformArg(acc, o1, 0)
                val o2transformed = transformArg(acc, o2, 1)
                return when (BigInteger.ZERO) {
                    o1transformed.evalAsConst() -> o2transformed
                    o2transformed.evalAsConst() -> o1transformed
                    else -> TACExprFactSimple.BWOr(o1transformed, o2transformed, tag)
                }
            }

            private fun transformZeroShift(
                acc: QuantVarsAndExpPointer,
                o1: TACExpr,
                o2: TACExpr,
                makeExp: (TACExpr, TACExpr) -> TACExpr
            ): TACExpr {
                val o1transformed = transformArg(acc, o1, 0)
                val o2transformed = transformArg(acc, o2, 1)
                return when (o2transformed.evalAsConst()) {
                    BigInteger.ZERO -> o1transformed
                    else -> makeExp(o1transformed, o2transformed)
                }
            }

            override fun transformShiftRightLogical(
                acc: QuantVarsAndExpPointer,
                o1: TACExpr,
                o2: TACExpr,
                tag: Tag?
            ): TACExpr =
                transformZeroShift(acc, o1, o2) { op1, op2 ->
                    TACExprFactSimple.ShiftRightLogical(op1, op2, tag)
                }

            override fun transformShiftRightArithmetical(
                acc: QuantVarsAndExpPointer,
                o1: TACExpr,
                o2: TACExpr,
                tag: Tag?
            ): TACExpr =
                transformZeroShift(acc, o1, o2) { op1, op2 ->
                    TACExprFactSimple.ShiftRightArithmetical(op1, op2, tag)
                }

            override fun transformShiftLeft(
                acc: QuantVarsAndExpPointer,
                o1: TACExpr,
                o2: TACExpr,
                tag: Tag?
            ): TACExpr =
                transformZeroShift(acc, o1, o2) { op1, op2 ->
                    TACExprFactSimple.ShiftLeft(op1, op2, tag)
                }


        }

    }


}
