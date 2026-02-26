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
import sbf.cfg.CondOp
import sbf.disassembler.SbfRegister
import sbf.domains.*
import vc.data.*
import java.math.BigInteger
import datastructures.stdcollections.*

/**
 * Emit TAC to model the load `*([base] + [o])`
 *
 * **Important**: the TAC generation depends on whether the pointer analysis decided to split or merge cells during the transfer
 * function of the load. The information is encoded in [preservedValues]
 *
 * @param variables maps offsets to TAC stack variables. There are potentially multiple offsets in case the pointer analysis kept track of sets.
 * @param preservedValues maps offsets to [Constant] values corresponding to the left-hand side of the load instruction.
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> stackLoad(
    base: TACExpr.Sym.Var,
    o: TACExpr.Sym.Const,
    variables : Map<PTAOffset, TACByteStackVariable>,
    preservedValues: Map<PTAOffset, Constant>,
    lhs: TACSymbol.Var
): List<TACCmd.Simple> {

    var exactReconstruction = true
    val stackLocs = variables.mapValues { (offset, tacVar) ->
        val value = preservedValues[offset]
        value?.toLongOrNull()
            // `offset` is mapped to a non-top constant in `stackValues`
            ?.let { exprBuilder.mkConst(it).asSym() }
        // `offset` is mapped to a top constant in `stackValues`
            ?: value?.let {
                exactReconstruction = false
                vFac.mkFreshIntVar().asSym()
            }
            // `offset` is not in `stackValues`
            ?: tacVar.tacVar.asSym()
    }
    val debugCmd = if (preservedValues.isNotEmpty()) {
        val msg = "Warning: this read on the stack does not match the last written bytes, " +
            if (exactReconstruction) {
                "but the pointer analysis is able to reconstruct exactly the bytes from the last writes."
            } else {
                "but the pointer analysis is able to over-approximate the bytes from the last writes. " +
                    "Because of this over-approximation spurious counterexamples are possible."
            }
        listOf(Debug.ptaSplitOrMerge(msg, listOf(lhs)))
    } else {
        listOf()
    }
    return debugCmd + listOf(assign(lhs, resolveStackAccess(base, o, stackLocs)))
}

/**
 *  Emit TAC to model writing [value] to ([base] + [o])
 *
 *  Assume that [stackLocs] = `[o1->v1, o2->v2]`
 *
 *  Then, it emits the following TAC:
 *
 *  ```
 *  v1 := ite(base + o == r10 + o1, value, v1)
 *  v2 := ite(base + o == r10 + o2, value, v2)
 *  ```
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> stackStore(
    base: TACExpr.Sym.Var,
    o: TACExpr.Sym.Const,
    stackLocs : Map<PTAOffset, TACByteStackVariable>,
    value: TACExpr
): List<TACCmd.Simple> {
    val cmds = mutableListOf<TACCmd.Simple>()
    if (stackLocs.size == 1) {
        val targetVar = stackLocs.toList().single().second.tacVar
        cmds += assign(targetVar, value)
    } else {
        for ((offset, stackVar) in stackLocs) {
            val targetVar = stackVar.tacVar
            cmds += weakAssign(targetVar, pointsToStack(base, o, offset), value)
        }
    }
    return cmds
}

/**
 * Return TAC expression `base + o == r10 + stackOffset`
 * Precondition: [stackOffset] is negative because stack grows downward.
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> pointsToStack(
    base: TACExpr.Sym.Var,
    o: TACExpr.Sym.Const,
    stackOffset: PTAOffset
): TACExpr {
    val stackPtr = exprBuilder.mkVar(SbfRegister.R10_STACK_POINTER).asSym()
    check(stackOffset <= 0) {"Precondition of pointsToStack failed"}
    return exprBuilder.mkBinRelExp(
        CondOp.EQ,
        if (o.s.value == BigInteger.ZERO) { base } else { TACExpr.Vec.Add(listOf(base, o))},
        TACExpr.BinOp.Sub(stackPtr, exprBuilder.mkConst(-stackOffset.v).asSym())
    )
}

/**
 * Assume that [stackLocs] = `[o1->v1, o2->v2, o3->v3]`
 *
 * Then, it returns the ITE-expression:
 * ```
 * ite(base + o == r10 + o1,
 *     v1,
 *     ite(base + o == r10 + o2,
 *         v2,
 *         v3
 *     )
 * )
 * ```
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> resolveStackAccess(
    base: TACExpr.Sym.Var,
    o: TACExpr.Sym.Const,
    stackLocs : Map<PTAOffset, TACExpr.Sym>
): TACExpr {
    check(stackLocs.isNotEmpty()) {"resolveStackAccess does not expect an empty map"}
    val reversedStackLocs = stackLocs.toList().reversed()
    val initialExpr: TACExpr = reversedStackLocs.first().second
    return reversedStackLocs
        .drop(1)
        .fold(initialExpr) { acc, (offset, symbol) ->
            TACExpr.TernaryExp.Ite(
                pointsToStack(base, o, offset),
                symbol,
                acc
            )
        }
}
