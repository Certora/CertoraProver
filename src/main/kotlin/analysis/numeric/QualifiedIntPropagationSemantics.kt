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

package analysis.numeric

import analysis.LTACCmd
import analysis.PathCondition
import vc.data.TACSummary
import vc.data.TACSymbol

/**
 * A base for [IPathSemantics] that uses the propagation logic found in [QualifierPropagationComputation].
 * Operates on values type [I] which are the valuations given to variables in states of type [S]
 * (via [QualifierPropagationComputation.extractValue].
 *
 * [S] may be embedded within some larger state of type [W]
 */
abstract class QualifiedIntPropagationSemantics<I: QualifiedInt<*, *, *>, S, W, Q>(
    val propagator: QualifierPropagationComputation<I, S, W, Q>
) : IPathSemantics<S, W> {

    override fun propagate(l: LTACCmd, s: S, w: W, pathCondition: PathCondition): S? {
        return when(pathCondition) {
            PathCondition.TRUE -> s
            is PathCondition.EqZero -> this.propagateFalse(pathCondition.v, s, w, l)
            is PathCondition.NonZero -> this.propagateTrue(pathCondition.v, s, w, l)
            is PathCondition.Summary -> this.propagateSummary(pathCondition.s, s, w, l)
        }
    }

    /**
     * Propagate that the variable [v] is "true" i.e., non-zero in state [s] embedded in larger
     * state [w]. This information is propagated at the location [l]. Returns null if this
     * case is impossible.
     */
    open fun propagateTrue(v: TACSymbol.Var, s: S, w: W, l: LTACCmd): S? {
        val av = propagator.extractValue(v, s, w, l) ?: return s
        return propagator.propagateTrue(v, av, s, w, l)?.let {
            this.applyPathInformation(it, s, w, l)
        }
    }

    /**
     * Apply the path information [toPropagate] (a map of variables to the path information to be propagated)
     * to the state [s]. Returning null indicates that the path is infeasible.
     */
    abstract fun applyPathInformation(toPropagate: Map<TACSymbol.Var, List<PathInformation<Q>>>, s: S, w: W, l: LTACCmd): S?

    open fun propagateFalse(v: TACSymbol.Var, s: S, w: W, l: LTACCmd): S? {
        val av = propagator.extractValue(v, s, w, l) ?: return s
        return propagator.propagateFalse(v,  av,s, w, l)?.let {
            this.applyPathInformation(it, s, w, l)
        }
    }

    /**
     * Propagate the summary [summary] to [l]
     */
    abstract fun propagateSummary(summary: TACSummary, s: S, w: W, l: LTACCmd): S?
}
