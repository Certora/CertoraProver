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

package sbf.callgraph

import sbf.cfg.ReadRegister
import sbf.cfg.Value
import sbf.cfg.WriteRegister
import sbf.domains.MemorySummaries
import datastructures.stdcollections.*

/** Class used to define known builtins **/
open class ExternalFunction(open val name: String,
                            /** Only `SbfInstruction.Call` should access to [writeRegister] **/
                            override val writeRegister: Set<Value.Reg> = setOf(),
                            /** Only `SbfInstruction.Call` should access to [readRegisters] **/
                            override val readRegisters: Set<Value.Reg> = setOf()): ReadRegister, WriteRegister

interface ExternalLibrary<T> {
    fun from(name: String): T?
    fun addSummaries(memSummaries: MemorySummaries)
}
