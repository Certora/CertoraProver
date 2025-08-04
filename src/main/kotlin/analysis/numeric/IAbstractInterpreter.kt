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

/**
 * The basic abstract interpreter interface. An abstract interpreter
 * takes a state of type [W] and that can be [step] at some location to produce an output state [U].
 * [U] may not necessarily be the same type as [W]; in particular, some abstract interpreters
 * step states that are embedded within some larger state for, e.g., the reduced product.
 *
 * An abstract interpreter may also propagate path conditions with [propagate]
 */
interface IAbstractInterpreter<in W, out U> {
    /**
     * Model the effects of the statement at [l] on the [W], producing a
     * sub-state of type [U]
     */
    fun step(l: LTACCmd, w: W) : U

    /**
     * Propagate a state [w] with path condition [pathCondition] to [l]. Returning
     * null indicates that this path is not feasible.
     */
    fun propagate(l: LTACCmd, w: W, pathCondition: PathCondition) : U?
}
