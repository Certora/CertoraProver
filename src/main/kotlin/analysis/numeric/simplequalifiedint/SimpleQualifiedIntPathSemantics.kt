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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.numeric.simplequalifiedint

import analysis.LTACCmd
import analysis.numeric.BoundedQIntPropagationSemantics
import analysis.numeric.QualifierManager
import analysis.numeric.QualifierPropagationComputation
import analysis.numeric.SimpleIntQualifier
import datastructures.stdcollections.plus
import vc.data.TACSummary
import vc.data.TACSymbol
import java.math.BigInteger

open class SimpleQualifiedIntPathSemantics<W>(
    qualifierPropagator: QualifierPropagationComputation<SimpleQualifiedInt, SimpleQualifiedIntState, W, SimpleIntQualifier>,
    val qualifierManager: QualifierManager<SimpleIntQualifier, SimpleQualifiedInt, SimpleQualifiedIntState>,
): BoundedQIntPropagationSemantics<SimpleQualifiedInt, SimpleIntQualifier, SimpleQualifiedIntState, W>(qualifierPropagator) {
    override fun assignVar(toStep: SimpleQualifiedIntState, lhs: TACSymbol.Var, toWrite: SimpleQualifiedInt, where: LTACCmd) =
        qualifierManager.assign(toStep, lhs, toWrite, where.ptr)

    override fun propagateSummary(summary: TACSummary, s: SimpleQualifiedIntState, w: W, l: LTACCmd) = s

    override fun applyPath(
        k: TACSymbol.Var,
        curr: SimpleQualifiedInt,
        lb: BigInteger?,
        ub: BigInteger?,
        quals: Iterable<SimpleIntQualifier>,
        st: SimpleQualifiedIntState,
        l: LTACCmd
    ): SimpleQualifiedIntState {
        val st_ = super.applyPath(k, curr, lb, ub, quals, st, l)
        var stateIter = st_
        for(q in curr.qual) {
            if(q !is SimpleIntQualifier.MustEqual) {
                continue
            }
            val saturated = st_.interpret(q.other)
            val updated = saturated.withBoundAndQualifiers(
                lb = lb?.max(saturated.x.lb) ?: saturated.x.lb,
                ub = ub?.min(saturated.x.ub) ?: saturated.x.ub,
                quals = saturated.qual + quals
            )
            stateIter = assignVar(stateIter, q.other, updated, l)
        }
        return stateIter
    }
}
