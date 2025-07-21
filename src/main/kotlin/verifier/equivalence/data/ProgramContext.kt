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

package verifier.equivalence.data

import scene.ContractClass
import scene.TACMethod
import vc.data.TACSymbol
import vc.data.stateVars
import datastructures.stdcollections.*
import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * The context for the T version of the program (or derivative thereof).
 * Currently all such programs must be associated with a [hostContract] and [storageVariable] and [transientStorageVariable].
 */
interface ProgramContext<T: MethodMarker> : BufferTraceInstrumentation.InstrumentationContext {
    val hostContract: ContractClass

    override val storageVariable: TACSymbol.Var get() = hostContract.storage.stateVars().single()
    val transientStorageVariable: TACSymbol.Var get() = hostContract.transientStorage.stateVars().single()

    override val containingContract: ContractClass
        get() = hostContract

    companion object {
        fun <T: MethodMarker> of(m: TACMethod) = object : ProgramContext<T> {
            override val hostContract: ContractClass
                get() = m.getContainingContract() as ContractClass
        }
    }
}
