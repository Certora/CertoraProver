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

import sbf.domains.PTAOffset
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import datastructures.stdcollections.*
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags

/** Return a TAC instruction that stores [value] in [map] at index [idx] **/
fun store(map: TACSymbol.Var, idx: TACSymbol, value: TACSymbol) =
    TACCmd.Simple.AssigningCmd.ByteStore(idx,  value, map)

/**
 * Emit a region-conditional store via the [TACMemSplitter.ByteMapTarget.Ite] encoding.
 * For each branch with guard `gi` and region map `Mi`:
 * ```
 *   old_i := Mi[loc]
 *   new_i := ite(gi, value, old_i)
 *   Mi    := Store(Mi, loc, new_i)
 * ```
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    store(target: TACMemSplitter.ByteMapTarget, loc: TACSymbol, value: TACSymbol, widthBytes: Short): List<TACCmd.Simple> {
    val cmds = mutableListOf<TACCmd.Simple>()
    when (target) {
        is TACMemSplitter.ByteMapTarget.Base ->
            cmds += store(target.v.tacVar, loc, value)
        is TACMemSplitter.ByteMapTarget.Ite ->
            for (branch in target.branches) {
                val oldAtLoc = vFac.mkFreshIntVar()
                cmds += sbfTacB.load(oldAtLoc, loc, widthBytes, branch.map.tacVar)
                val newAtLoc = vFac.mkFreshIntVar()
                cmds += assign(newAtLoc, sbfTacB.ite(branch.guard, value.asSym(), oldAtLoc.asSym()))
                cmds += store(branch.map.tacVar, loc, newAtLoc)
            }
    }
    return cmds
}

/** `store(target, base, offset, value) -> store(target, base + offset, value)` **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    store(target: TACMemSplitter.ByteMapTarget, base: TACSymbol.Var, offset: PTAOffset, value: TACSymbol, widthBytes: Short): List<TACCmd.Simple> {
    val cmds = mutableListOf<TACCmd.Simple>()
    val loc = computeTACMapIndex(base, offset, cmds)
    cmds += store(target, loc, value, widthBytes)
    return cmds
}

/**
 * Perform a ByteLoad from each region map at [loc] and ite over the resulting *values*:
 * ```
 * v_1       := ByteLoad(M1, loc, widthBytes)
 * v_2       := ByteLoad(M2, loc, widthBytes)
 * ...
 * v_n       := ByteLoad(Mn, loc, widthBytes)
 * v_default := ByteLoad(default, loc, widthBytes)
 * lhs       := ite(g1, v_1, ite(g2, v_2, ... ite(gn, v_n, v_default)))
 * ```
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    load(lhs: TACSymbol.Var, target: TACMemSplitter.ByteMapTarget.Ite, loc: TACSymbol.Var, widthBytes: Short): List<TACCmd.Simple> {
    val cmds = mutableListOf<TACCmd.Simple>()
    // One ByteLoad per region map at the same loc; each result is a plain value.
    val branchValues = target.branches.map { branch ->
        val v = vFac.mkFreshIntVar()
        cmds += sbfTacB.load(v, loc, widthBytes, branch.map.tacVar)
        branch.guard to v
    }
    // Right-fold over (guard, value) pairs with the last branch's value as the innermost else
    // (fallthrough): the last branch's guard is dropped.
    val fallthrough = branchValues.last().second.asSym() as TACExpr
    val expr = branchValues.dropLast(1).foldRight(fallthrough) { (guard, v), acc ->
        sbfTacB.ite(guard, v.asSym(), acc)
    }
    cmds += assign(lhs, expr)
    return cmds
}

/**
 * Build `ite(g1, M1, ite(g2, M2, ... ite(g_{n-1}, M_{n-1}, M_n)))` for the dispatch [target].
 * Right-fold over all branches except the last, using the last branch's map as the innermost else
 * (fallthrough). Callers must order branches so that the union of all guards covers the address
 * range the access can land in.
 */
private fun buildIteByteMapExpr(target: TACMemSplitter.ByteMapTarget.Ite): TACExpr {
    val last = target.branches.last().map.tacVar.asSym()
    return target.branches.dropLast(1).foldRight(last as TACExpr) { branch, acc ->
        TACExpr.TernaryExp.Ite(branch.guard, branch.map.tacVar.asSym(), acc)
    }
}

/**
 * Materialize an [TACMemSplitter.ByteMapTarget.Ite] as a single fresh ByteMap variable.
 * Returns the fresh variable and a single assignment command that binds it to the dispatch expression.
 * Use this when a downstream operation requires a single [TACByteMapVariable].
 */
private fun <TFlags: IPTANodeFlags<TFlags>> materializeAsByteMapVar(
    target: TACMemSplitter.ByteMapTarget.Ite,
    vFac: TACVariableFactory<TFlags>,
    suffix: String,
): Pair<TACByteMapVariable, TACCmd.Simple> {
    val fresh = vFac.getByteMapVar(suffix)
    val cmd = assign(fresh.tacVar, buildIteByteMapExpr(target))
    return fresh to cmd
}

/**
 * After writing into [dispatchMap], emit per-region back-assignments
 * `Mi := ite(gi, dispatchMap, Mi)` for each branch in [target]. The `target.default` is
 * intentionally not updated: writes targeting an address outside all guards are dropped.
 */
private fun writeBackByteMaps(
    target: TACMemSplitter.ByteMapTarget.Ite,
    dispatchMap: TACByteMapVariable,
): List<TACCmd.Simple> {
    val dispatchSym = dispatchMap.tacVar.asSym()
    return target.branches.map { branch ->
        val mapSym = branch.map.tacVar.asSym()
        assign(branch.map.tacVar, TACExpr.TernaryExp.Ite(branch.guard, dispatchSym, mapSym))
    }
}

/**
 * Read-only counterpart of [withWritableByteMap]. Materializes the dispatch as a fresh ByteMap
 * for an [TACMemSplitter.ByteMapTarget.Ite] target so [body] can read through a single variable,
 * but does not emit a writeBack.
 *
 * Use this when [body] does not mutate the byte map (e.g. the source of a `memcpy`, both operands of a `memcmp`).
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
    withReadableByteMap(
        target: TACMemSplitter.ByteMapTarget,
        suffix: String,
        body: (TACByteMapVariable) -> List<TACCmd.Simple>,
    ): List<TACCmd.Simple> =
    when (target) {
        is TACMemSplitter.ByteMapTarget.Base -> body(target.v)
        is TACMemSplitter.ByteMapTarget.Ite -> {
            val (dispatchMap, materialize) = materializeAsByteMapVar(target, vFac, suffix)
            listOf(materialize) + body(dispatchMap)
        }
    }

/**
 * Run [body] against a single [TACByteMapVariable] that represents the access target, then
 * write back into the source region maps if the target is an [TACMemSplitter.ByteMapTarget.Ite]
 * dispatch.
 *
 * - For [TACMemSplitter.ByteMapTarget.Base], [body] runs against the base map directly: no
 *   materialize or writeBack is needed.
 * - For [TACMemSplitter.ByteMapTarget.Ite], materializes the dispatch as a fresh ByteMap,
 *   runs [body] against that fresh map, then emits per-region writeBack ites that propagate
 *   any mutation into each region map.
 *
 * Use this whenever [body] may mutate the byte map (`memcpy` destination, `memset`, etc.) so the
 * "writeBack" step cannot be accidentally forgotten.
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
    withWritableByteMap(
        target: TACMemSplitter.ByteMapTarget,
        suffix: String,
        body: (TACByteMapVariable) -> List<TACCmd.Simple>,
    ): List<TACCmd.Simple> =
    when (target) {
        is TACMemSplitter.ByteMapTarget.Base -> body(target.v)
        is TACMemSplitter.ByteMapTarget.Ite -> {
            val (dispatchMap, materialize) = materializeAsByteMapVar(target, vFac, suffix)
            listOf(materialize) + body(dispatchMap) + writeBackByteMaps(target, dispatchMap)
        }
    }

/** Return instructions that havoc the indexes [loc] + [indexes] of the byte map [base] **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    havocByteMapLocation(indexes: List<PTAOffset>, base: TACByteMapVariable, loc: TACSymbol.Var): List<TACCmd.Simple> {
    val values = ArrayList<TACSymbol.Var>()
    val cmds = mutableListOf<TACCmd.Simple>()
    indexes.forEach { _ ->
        val value = vFac.mkFreshIntVar()
        cmds += havoc(value)
        values.add(value)
    }
    cmds += mapStores(base, loc, indexes, values)
    return cmds
}

/** Emit TAC code for index = [base] + [offset] **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    computeTACMapIndex(base: TACSymbol.Var, offset: PTAOffset, cmds: MutableList<TACCmd.Simple>): TACSymbol.Var {
    val index = vFac.mkFreshIntVar()
    cmds += assign(index, sbfTacB { base.asSym().addNoOvf(sbfTacB.mkConst(offset.v).asSym(), "computing TAC map index") })
    return index
}

/**
 * Emit TAC code that writes [values] in [byteMap] starting at [base] with [offsets]
 * [offsets] must be relative to [base]
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    mapStores(byteMap: TACByteMapVariable,
              base: TACSymbol.Var,
              offsets: List<PTAOffset>,
              values: List<TACSymbol>
): List<TACCmd.Simple> {
    // precondition: fields are sorted and len(fields) = len(values)
    check(offsets.size == values.size) {"Precondition of emitTACMapStores"}

    val cmds = mutableListOf<TACCmd.Simple>()
    for ( (offset, value) in offsets.zip(values)) {
        val idx = computeTACMapIndex(base, offset, cmds)
        // REVISIT: ByteStore assumes 32 bytes are written so the actual width is being ignored
        cmds += store(byteMap.tacVar, idx, value)
    }
    return cmds
}

/**
 * Emit TAC code that writes [value] in [byteMap] starting at [base] with [offset]
 * [offset] must be relative to [base]
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    mapStores(byteMap: TACByteMapVariable,
              base: TACSymbol.Var,
              offset: PTAOffset,
              value: TACSymbol
): List<TACCmd.Simple> =
    mapStores(byteMap, base, listOf(offset), listOf(value))

/**
 * Emit TAC code that loads each word from [byteMap] starting at [base] up to [length]
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    mapLoads(byteMap: TACByteMapVariable,
             base: TACSymbol.Var,
             wordSize: Byte, length: Long,
             cmds: MutableList<TACCmd.Simple>): List<TACSymbol.Var> {
    val numOfWords = length.toInt() / wordSize
    val intVars = ArrayList<TACSymbol.Var>(numOfWords)
    for (i in 0 until numOfWords) {
        val loc = computeTACMapIndex(base, PTAOffset(wordSize.toLong() * i.toLong()), cmds)
        val x = vFac.mkFreshIntVar()
        cmds += sbfTacB.load(x, loc, wordSize.toShort(), byteMap.tacVar)
        intVars.add(x)
    }
    // We should add at each loop iteration that [loc] cannot be greater than SBF_INPUT_END
    // However, this will add too many constraints to the solver. Instead, we enforce that [base] cannot
    // be greater than SBF_INPUT_END. Note that our solution is still sound, but it might produce spurious
    // counterexamples is numOfWords is too large. In fact, right now this cannot happen since we use 256 bits to
    // represent integers.
    cmds += addMemoryLayoutAssumptions(base, null)
    return intVars
}

