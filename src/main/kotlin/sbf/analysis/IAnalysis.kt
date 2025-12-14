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

package sbf.analysis

import sbf.cfg.SbfCFG
import sbf.disassembler.GlobalVariables
import sbf.disassembler.Label
import sbf.domains.AbstractDomain
import sbf.domains.MemorySummaries

interface IAnalysis<T: AbstractDomain<T>> {
    /**
     * Invariants that hold before the execution of [block]
     */
    fun getPre(block: Label): T?

    /**
     * Invariants that hold after the execution of [block]
     */
    fun getPost(block: Label): T?

    fun getCFG(): SbfCFG
    fun getMemorySummaries(): MemorySummaries
    fun getGlobalVariableMap(): GlobalVariables
}
