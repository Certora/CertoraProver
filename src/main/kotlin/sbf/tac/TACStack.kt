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
import sbf.cfg.SbfInstruction
import sbf.cfg.SbfMeta
import sbf.disassembler.SbfRegister
import sbf.domains.*
import vc.data.*
import vc.data.tacexprutil.asSym
import java.math.BigInteger

/**
 * Build a list of [TACByteStackVariable]s by walking the stack range starting at [start]
 * for [length] bytes, stepping by [wordSize]. Used to scalarize stack ranges for memcmp encoding.
 */
internal fun <TFlags : IPTANodeFlags<TFlags>> createStackVarsFromRange(
    start: PTAOffset,
    length: Long,
    wordSize: Byte,
    vFac: TACVariableFactory<TFlags>,
): List<TACByteStackVariable> {
    check(length.mod(wordSize.toInt()) == 0) { "createStackVarsFromRange precondition: length must be multiple of wordSize" }
    val vars = ArrayList<TACByteStackVariable>()
    for (i in 0 until length step wordSize.toLong()) {
        vars.add(vFac.getByteStackVar(start + i))
    }
    return vars
}

/**
 * Emit TAC to model the load `*([base] + [o])`
 *
 * **Important**: the TAC generation depends on whether the pointer analysis decided to split or merge cells during the transfer
 * function of the load. The information is encoded in [reconstructedValues]
 *
 * @param variables maps offsets to TAC stack variables. There are potentially multiple offsets in case the pointer analysis kept track of sets.
 * @param reconstructedValues non-empty only when the load width does not match the last store width, causing the pointer analysis
 * to reconstruct the cell via split or merge. Maps each affected offset to the reconstructed value (possibly top) and the layout kind.
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> stackLoad(
    inst: SbfInstruction.Mem,
    base: TACExpr.Sym.Var,
    o: TACExpr.Sym.Const,
    variables : Map<PTAOffset, TACByteStackVariable>,
    reconstructedValues: Map<PTAOffset, PTAMemSplitter.ReconstructedIntegerValue>,
    lhs: TACSymbol.Var
): List<TACCmd.Simple> {
    check(inst.isLoad)

    val isNarrowedLoad = inst.metaData.getVal(SbfMeta.NARROWED_LOAD) != null

    data class Resolution(val expr: TACExpr.Sym, val exact: Boolean)

    fun resolveOffset(offset: PTAOffset, tacVar: TACByteStackVariable): Resolution {
        val reconstructedValue = reconstructedValues[offset]
            ?: return Resolution(tacVar.tacVar.asSym(), exact = true)

        val knownConst = reconstructedValue.v.toLongOrNull()
        return when {
            knownConst != null ->
                // Last store wrote a concrete constant: reconstruct exactly
                Resolution(sbfTacB.mkConst(knownConst).asSym(), exact = true)
            reconstructedValue.layout == PTAGraph.CellLayout.SPLIT && isNarrowedLoad ->
                // Split cell with a narrowed load: use existing TAC variable and apply a mask
                Resolution(tacVar.tacVar.asSym(), exact = true)
            else ->
                // Last store value is top: over-approximate with a fresh unconstrained variable
                Resolution(vFac.mkFreshIntVar().asSym(), exact = false)
        }
    }

    val resolutions = variables.mapValues { (offset, tacVar) -> resolveOffset(offset, tacVar) }
    val stackLocs = resolutions.mapValues { (_, r) -> r.expr }
    val exactReconstruction = resolutions.values.all { it.exact }

    val debugCmd = if (!exactReconstruction) {
        val msg = "Warning: this read on the stack does not match the last written bytes, " +
                  "but the pointer analysis is able to over-approximate the bytes from the last writes. " +
                  "Because of this over-approximation spurious counterexamples are possible."
        listOf(Debug.ptaSplitOrMerge(msg, resolutions.values.filter { !it.exact }.mapNotNull { it.expr.asSym as? TACSymbol.Var } ))
    } else {
        listOf()
    }

    val rhs = resolveStackAccess(base, o, stackLocs)
    return debugCmd + listOf(
        assign(lhs, if (isNarrowedLoad) { sbfTacB.mask(rhs, inst.access.width.toLong() * 8) }  else { rhs })
    )
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
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> pointsToStack(
    base: TACExpr.Sym.Var,
    o: TACExpr.Sym.Const,
    stackOffset: PTAOffset
): TACExpr {
    val stackPtr = sbfTacB.mkVar(SbfRegister.R10).asSym()
    val lhs = if (o.s.value == BigInteger.ZERO) {
        base
    } else {
        sbfTacB { base add o }
    }
    val rhs = if (globals.elf.useDynamicFrames()) {
        check(stackOffset >= 0) { "pointsToStack expects the stack to grow upwards" }
        sbfTacB { stackPtr add mkConst(stackOffset.v) }
    } else {
        check(stackOffset <= 0) { "pointsToStack expects the stack to grow downwards" }
        sbfTacB { stackPtr sub mkConst(-stackOffset.v) }
    }
    return CondOp.EQ(lhs, rhs, sbfTacB)
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
            sbfTacB {
                switch(
                    pointsToStack(base, o, offset) to symbol,
                    default = acc
                )
            }
        }
}

