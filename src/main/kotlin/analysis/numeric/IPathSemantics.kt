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
 * Basic interface for path-sensitivity
 */
interface IPathSemantics<S, in W> {
    /**
     * Propagate a sub state [S] embedded in state [W] with path condition [pathCondition] to target [l]
     */
    fun propagate(l: LTACCmd, s: S, w: W, pathCondition: PathCondition) : S?
}
