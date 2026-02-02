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

package wasm.transform

import analysis.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import wasm.analysis.memory.StaticMemoryAnalysis
import wasm.impCfg.WASM_MEMORY_OP_WIDTH

/**
    Finds memory reads at constant locations, where the value is available in the Wasm static data, and replaces such
    reads with the corresponding constant value.

    We do this rather than initializing MEMORY with the static data, because a) such initialization would be expensive
    (it would be a long chain of updates to the MEMORY map), and b) we don't know, initially, how the memory will be
    read (the sizes of the data at each location).
 */
object StaticMemoryInliner {
    fun transform(code: CoreTACProgram): CoreTACProgram {
        val mca = MustBeConstantAnalysis(code.analysisCache.graph)
        val sma = code.analysisCache[StaticMemoryAnalysis]

        return code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            if (cmd !is TACCmd.Simple.AssigningCmd.ByteLoad) {
                return@mapNotNull null
            }
            if (cmd.base != TACKeyword.MEMORY.toVar()) {
                return@mapNotNull null
            }

            // We can only handle loads from constant locations
            val loc = mca.mustBeConstantAt(ptr, cmd.loc) ?: return@mapNotNull null

            // Get the size of the load
            val size = cmd.meta[WASM_MEMORY_OP_WIDTH] ?: return@mapNotNull null

            // Try to read the value from static memory
            val value = sma.readLittleEndianWord(loc, size) ?: return@mapNotNull null

            // Replace the load with a simple assignment of the constant value
            ptr to TACCmd.Simple.AssigningCmd.AssignExpCmd(cmd.lhs, TACSymbol.Const(value), cmd.meta)

        }.patchForEach(code) { (ptr, replacement) ->
            update(ptr, replacement)
        }
    }
}
