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

package wasm.host.soroban

import analysis.maybeNarrow
import config.Config
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.asTACExpr
import wasm.host.soroban.Val.isObjectTagValue
import java.util.stream.Collectors

/** Picks a representative integer to use for each fresh object handle */
object CanonicalizeObjectValAllocations {
    private val Int.asIdentifiableHandle
        get() = 0x5_CA_1A_12 // "SCALAR" (36 bits -- need room to shl by 8)
            .toBigInteger()
            .shl(28)
            .plus(this)

    fun canonicalize(ctp: CoreTACProgram): CoreTACProgram {
        if (!Config.SorobanConcreteObjectVals.get()) {
            return ctp
        }

        val freshHandles = ctp.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignHavocCmd>()?.takeIf {
                it.cmd.meta[Val.WASM_FRESH_HANDLE]?.isObjectTagValue == true
            }
        }.collect(Collectors.toSet())

        return ctp.patching { patching ->
            freshHandles.withIndex().forEach { (i, cmd) ->
                val tag = cmd.cmd.meta[Val.WASM_FRESH_HANDLE]!!
                val handleId = i.asIdentifiableHandle
                patching.update(
                    cmd.ptr,
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        cmd.cmd.lhs,
                        handleId.shiftLeft(Val.TAG_BITS).or(tag.toBigInteger()).asTACExpr
                    )
                )
            }
        }
    }
}
