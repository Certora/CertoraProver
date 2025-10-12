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

package prefixgen.selection

import bridge.Method

/**
 * A functional interface which picks methods to include in test cases.
 */
fun interface Selector {
    /**
     * [level]: the current "level" of generation, how many function calls we've already
     * picked
     * [maxDepth]: the maximum level. It is an invariant that [level] < [maxDepth].
     * [currSelection] the current list of functions already called, both those selected
     * in previous levels and in the test case's [prefixgen.data.FixedPrefix].
     */
    fun generateChoice(
        level: Int,
        maxDepth: Int,
        currSelection: List<String>
    ) : Sequence<Method>
}
