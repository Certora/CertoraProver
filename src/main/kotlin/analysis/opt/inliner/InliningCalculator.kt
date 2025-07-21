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

package analysis.opt.inliner

import analysis.CmdPointer
import analysis.DagDefExprDataFlow
import analysis.opt.inliner.GlobalInliner.Companion.inlinable
import analysis.opt.inliner.Inlinee.*
import analysis.opt.inliner.Inlinee.Companion.isMaybeInside
import analysis.opt.inliner.Inlinee.Companion.isSurelyInside
import analysis.opt.inliner.Inlinee.Mapping.Companion.NoInfoMap
import analysis.opt.intervals.Intervals
import analysis.opt.intervals.IntervalsCalculator
import com.certora.collect.*
import config.Config
import config.Config.exactByteMaps
import datastructures.add
import datastructures.buildMultiMap
import datastructures.stdcollections.*
import tac.Tag
import utils.*
import utils.Color.Companion.green
import vc.data.*
import java.math.BigInteger


/**
 * Calculates for each variable what it can be replaced with, via simple assignments. This includes being
 * replaced with a linear combination of variables.
 *
 * It also, and this is the main point, calculates for each byteMap, what are the key-value pairs we are sure
 * about and thus the values can be used for pre-calculating bytemap reads.
 *
 * The choice made here is to follow only [Tag.Bit256] variables. The problem with mixing these with [Tag.Int]
 * variables, is that we'd have to follow the mod 2^256 operations throughout the linear operations we accumulate.
 * That can cause subtle problems.
 */
class InliningCalculator(code: CoreTACProgram) : DagDefExprDataFlow<Inlinee>(code) {

    private val reachable = code.analysisCache.reachability
    val intervals = IntervalsCalculator(code, preserve = { false })


    /**
     * The [IntervalsCalculator] will return the default interval for [v] if it does not appear at the rhs of the [ptr]
     * being queried. Therefore, in such cases we query at the [def] sites instead and union the results.
     */
    private fun getIntervalsAtRhs(ptr: CmdPointer, v: TACSymbol.Var) =
        intervals.getAtRhs(ptr, v)

    fun getIntervalsAtRhs(ptr: CmdPointer, t: Term): Intervals =
        t.evaluate { getIntervalsAtRhs(ptr, it) }

    /**
     * Variables that have a def site that is after a use site are problematic because when trying to inline them we'll
     * have to check if where we inline, the current value is still a valid one. Therefore, we just don't try them.
     */
    private val dontTouchVars = buildSet {
        val varDefs = buildMultiMap {
            g.commands.forEach { (ptr, cmd) ->
                cmd.getLhs()
                    ?.takeIf { it.tag.inlinable }
                    ?.let { add(it, ptr) }
            }
        }
        // if there are a lot of def sites for a variable, it's likely to fail the check below, and we give up on it
        // for efficiency's sake.
        varDefs.forEachEntry { (v, defs) ->
            if (defs.size > Config.inliningVarCheckThreshhold.get()) {
                add(v)
            }
        }
        g.commands.forEach { (ptr, cmd) ->
            if (cmd is TACCmd.Simple.AnnotationCmd || cmd is TACCmd.Simple.LogCmd) {
                return@forEach
            }
            cmd.getFreeVarsOfRhs()
                .filter { it.tag.inlinable }
                .filter { it !in this }
                .forEach { v ->
                    if (varDefs[v]?.any { reachable.canReach(ptr, it) } == true) {
                        add(v)
                    }
                }
        }
    }.also {
        logger.debug { "Non-DSA variables = $it" }
    }


    override fun handleConst(i: BigInteger) =
        if (SignUtilities.isInUnsignedBounds(i)) {
            Term(i)
        } else {
            None
        }

    override fun uninitialized(v: TACSymbol.Var) =
        when {
            v in dontTouchVars -> None
            v.tag.inlinable -> Term(v)
            v.tag is Tag.ByteMap -> NoInfoMap
            else -> None
        }

    override fun join(t1: Inlinee, t2: Inlinee) = t1 join t2


    private fun normalize(v: TACSymbol.Var, t: Inlinee) =
        if (t is None) {
            uninitialized(v)
        } else {
            t
        }

    override fun normalizeLhs(ptr: CmdPointer, t: Inlinee) =
        normalize(g.toCommand(ptr).getModifiedVar()!!, t)

    override fun normalizeRhs(ptr: CmdPointer, v: TACSymbol.Var, t: Inlinee) =
        normalize(v, t)

    override fun handleExpr(ptr: CmdPointer, e: TACExpr, values: List<Inlinee>): Inlinee {
        if (e.tag != Tag.ByteMap && e.tag?.inlinable == false) {
            // null will only happen when called through `handleTempExp`
            return None
        }

        fun evalV(v: TACSymbol.Var) = getIntervalsAtRhs(ptr, v)

        return when (e) {
            is TACExpr.Vec.Add ->
                values
                    .map { it as? Term ?: return None }
                    .reduce { a, b -> ((a + b) as? Term) ?: return None }

            is TACExpr.BinOp.Sub -> {
                val (op1, op2) = values
                if (op1 !is Term || op2 !is Term) {
                    return None
                }
                op1 - op2
            }

            is TACExpr.Vec.Mul -> {
                if (values.size != 2) {
                    return None
                }
                val (op1, op2) = values
                if (op1 !is Term || op2 !is Term) {
                    return None
                }
                op1 * op2
            }

            is TACExpr.TernaryExp.Ite ->
                values[1] join values[2]

            is TACExpr.Store -> {
                val (base, loc, value) = values
                if (base !is Mapping || loc !is Term) {
                    return None
                }
                base.write(loc, value as? Term, onlyOneByte = false, ::evalV)
            }

            is TACExpr.Select -> {
                val (base, loc) = values
                if (base !is Mapping || loc !is Term) {
                    return None
                }
                base[loc] ?: None
            }

            is TACExpr.LongStore -> {
                val (dstMap, dstOffset, srcMap, srcOffset, length) = values
                if (dstMap !is Mapping || dstOffset !is Term || srcMap !is Mapping || srcOffset !is Term || length !is Term) {
                    return None
                }

                // everything that may be in [dstOffset, dstOffset+length) is erased.
                // however, if `exactByteMaps` flag is on, then anything in [dstOffset-31, dstOffSet+length) is erased
                // because at least some of its bytes are overwritten.
                // As an example, if we are copying `src[32, 32+32)` to `dst[96, 96+32)`, then a word written to
                // `dst[64]` is ok, but `dst[65]` has its last bit overwritten.
                val cleanedDst = with(dstMap) {
                    val extDstOffset = dstOffset.letIf(exactByteMaps.get()) { it - 31 }
                    Mapping(
                        map.removeAllKeys {
                            it.isMaybeInside(extDstOffset, dstOffset + length, ::evalV)
                        }
                    )
                }

                // only what is surely in `[srcOffset, srcOffset+length)` is kept.
                // if `exactByteMaps` flag is on, then this range is `[srcOffset, srcOffset+length-31)`.
                // using the same example as above, then a word written to `src[32]` should be taken, but the last bit
                // of a word at `src[33]` is not copied, and so we don't take it.
                val cleanedSrc = with(srcMap) {
                    val extLength = length.letIf(exactByteMaps.get()) { it - 31 }
                    Mapping(
                        map.retainAllKeys {
                            it.isSurelyInside(srcOffset, srcOffset + extLength, ::evalV)
                        }
                    )
                }

                return Mapping(
                    cleanedDst.map + cleanedSrc.addToKeys(dstOffset - srcOffset).map
                )
            }

            else -> None
        }
    }

    /** This is just a way to dedup code... */
    private fun handleTempExp(ptr: CmdPointer, e: TACExpr, varValues: Map<TACSymbol.Var, Inlinee>): Inlinee {
        val values = e.getOperands().map { s ->
            when (s) {
                is TACExpr.Sym.Const -> Term(s.s.value)
                is TACExpr.Sym.Var -> varValues[s.s] ?: uninitialized(s.s)
                else -> error("Shouldn't happen")
            }
        }
        return handleExpr(ptr, e, values)
    }


    override fun handleOtherAssigns(
        ptr: CmdPointer,
        cmd: TACCmd.Simple.AssigningCmd,
        varValues: Map<TACSymbol.Var, Inlinee>
    ): Inlinee =
        when (cmd) {
            is TACCmd.Simple.AssigningCmd.ByteStore -> with(cmd) {
                handleTempExp(ptr, TACExpr.Store(base.asSym(), loc.asSym(), value.asSym()), varValues)
            }

            is TACCmd.Simple.AssigningCmd.ByteLoad -> with(cmd) {
                handleTempExp(ptr, TACExpr.Select(base.asSym(), loc.asSym()), varValues)
            }

            is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> {
                val base = varValues[cmd.base]
                val loc = varValues[cmd.loc]
                if (base !is Mapping || loc !is Term) {
                    NoInfoMap
                } else {
                    base.write(loc, null, onlyOneByte = true) {
                        getIntervalsAtRhs(ptr, it)
                    }
                }
            }

            else -> uninitialized(cmd.lhs)
        }


    override fun handleNonAssigns(
        ptr: CmdPointer,
        cmd: TACCmd.Simple,
        varValues: Map<TACSymbol.Var, Inlinee>
    ): Inlinee? = when (cmd) {
        is TACCmd.Simple.ByteLongCopy -> with(cmd) {
            handleTempExp(
                ptr,
                TACExpr.LongStore(
                    dstBase.asSym(),
                    dstOffset.asSym(),
                    srcBase.asSym(),
                    srcOffset.asSym(),
                    length.asSym()
                ),
                varValues
            )
        }

        else -> null
    }

    override fun debugPrinter() =
        super.debugPrinter()
            .extraLines { (ptr, _) ->
                listOfNotNull(
                    (lhsVal(ptr) as? Term)?.let {
                        "      " + it.term.support.joinToString { v ->
                            "$v = ${getIntervalsAtRhs(ptr, v)}"
                        }
                    }?.green
                )
            }
}
