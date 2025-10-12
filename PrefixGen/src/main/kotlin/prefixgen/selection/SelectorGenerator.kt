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
 * Represents a generator for selection strategies from some fixed universe.
 */
interface SelectorGenerator {
    /**
     * Given a [universe] (or "vocabulary") of [Method]s, return a [Selector]
     * which picks methods for test cases.
     */
    fun selectorFor(universe: List<Method>) : Selector
}
