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

package verifier

import analysis.maybeExpr
import datastructures.stdcollections.*
import spec.QUANTIFIED_VAR_TYPE
import spec.cvlast.CVLType
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import vc.data.TACMeta.CVL_TYPE
import vc.data.tacexprutil.TACExprUtils.contains
import vc.data.tacexprutil.postTransform
import vc.data.tacexprutil.replaceVarsExtended
import vc.data.tacexprutil.subs

/**
 * This is a band-aid class to rewrite the quantified expressions
 *
 * First simplification comes from storage comparison:
 * ```
 *   forall uint x. ... storage-access[to_skey(x)] ...
 * ```
 * If x only appears directly inside a `to_skey(x)`, we can replace the above with:
 * ```
 *    forall skey x. ... storage-access[x] ...
 * ```
 * This is clearly simpler, and makes life better for grounding, as it expects map/array accesses to be done directly
 * with a quantified variable (and not wrapped in any other operation as we see here).
 *
 * Ideally, storage comparison should have generated these expressions to begin with, but apparently it's not easy at
 * that stage in the pipeline.
 *
 * Another simplification is to change the tag, and remove unnecessary [TACBuiltInFunction.SafeMathNarrow] operations,
 * based on the original CVL type of the quantified variable.
 * ```
 * forall int i, ... select(a, safeMathNarrowToK(i)) ...
 * ```
 * to:
 * ```
 * forall BitsK i, ... select(a, i) ...
 * ```
 */
class QuantifierNormalizer(val prog: CoreTACProgram) {
    private val concurrentPatcher = ConcurrentPatchingProgram(prog)

    private fun TACExpr.isVariable(v: TACSymbol.Var) =
        (this as? TACExpr.Sym.Var)?.s == v

    private inline fun <reified T : TACBuiltInFunction> TACExpr.applyExprArg(): TACExpr? =
        runIf(this is TACExpr.Apply && (f as? TACExpr.TACFunctionSym.BuiltIn)?.bif is T) {
            getOperands().single()
        }


    /** the storage comparison case */
    private fun rewriteSkeys(q: TACSymbol.Var, body: TACExpr): Pair<TACSymbol.Var, TACExpr>? {
        fun TACExpr.isToSkeyOf(v : TACSymbol.Var) =
            applyExprArg<TACBuiltInFunction.Hash.ToSkey>()?.isVariable(v) == true

        val toSkeyQCount = body.subs.count { it.isToSkeyOf(q) }
        if (toSkeyQCount == 0) {
            return null
        }
        val qCount = body.subs.count { it.isVariable(q) }
        if (qCount != toSkeyQCount) {
            return null
        }
        val newQ = q.updateTag(skeySort, "skey")
        return newQ to
            body.replaceVarsExtended(q to newQ)
                .postTransform {
                    when {
                        it.isToSkeyOf(newQ) -> newQ.asSym()
                        else -> it
                    }
                }
    }


    /** the safemathnarrow case */
    private fun rewriteNarrows(q: TACSymbol.Var, body: TACExpr): Pair<TACSymbol.Var, TACExpr>? {
        fun TACExpr.isNarrowingOf(v: TACSymbol.Var) =
            applyExprArg<TACBuiltInFunction.SafeMathNarrow>()?.isVariable(v) == true
        if (q.tag != Tag.Int) {
            return null
        }
        val actualTag = q.meta[CVL_TYPE]?.toTag() as? Tag.Bits
            ?: (q.meta[QUANTIFIED_VAR_TYPE] as? CVLType.PureCVLType.Primitive.UIntK)
                ?.let { Tag.Bits(256 /* should be it.bitWidth, but that doesn't work yet */) }
            ?: (q.meta[QUANTIFIED_VAR_TYPE] as? CVLType.PureCVLType.Primitive.AccountIdentifier)
                ?.let { Tag.Bits(256) /* could be 160, but let's take 256 for now */ }
            ?: return null
        val newQ = q.updateTag(actualTag, actualTag.toString())
        return newQ to
            body.replaceVarsExtended(q to newQ)
                .postTransform {
                    when {
                        it.isNarrowingOf(newQ) -> newQ.asSym()
                        else -> it
                    }
                }
    }

    private fun rewrite(exp: TACExpr): TACExpr =
        exp.postTransform { e ->
            if (e is TACExpr.QuantifiedFormula) {
                var newBody = e.body
                val newQs = mutableListOf<TACSymbol.Var>()
                for (q in e.quantifiedVars) {
                    val (newQ, newBody1) = rewriteSkeys(q, newBody) ?: rewriteNarrows(q, newBody) ?: (q to null)
                    newQs += newQ
                    newBody1?.let { newBody = it }
                }
                if (newBody != e.body) {
                    e.copy(quantifiedVars = newQs, body = newBody)
                } else {
                    e
                }
            } else {
                e
            }
        }

    fun go(): CoreTACProgram {
        prog.parallelLtacStream()
            .mapNotNull { it.maybeExpr<TACExpr>() }
            .filter { lcmd -> lcmd.exp.contains { it is TACExpr.QuantifiedFormula } }
            .forEach { lcmd ->
                concurrentPatcher.replace(
                    lcmd.ptr,
                    lcmd.cmd.copy(rhs = rewrite(lcmd.cmd.rhs))
                )
            }
        return concurrentPatcher.toCode()
    }
}


