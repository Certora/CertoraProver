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

package instrumentation.transformers

import datastructures.stdcollections.*
import tac.MetaKey
import utils.mapNotNullToSet
import vc.data.*
import vc.data.TACMeta.REVERT_MANAGEMENT

/**
 * A way to centralize the decision of which variables and assignments we can erase, and which we want
 * to keep during our optimizations.
 */
interface FilteringFunctions {
    fun isInlineable(v: TACSymbol.Var): Boolean
    fun isErasable(cmd: TACCmd.Simple.AssigningCmd): Boolean

    object NoFilter : FilteringFunctions {
        override fun isInlineable(v: TACSymbol.Var) = true
        override fun isErasable(cmd: TACCmd.Simple.AssigningCmd) = true
    }

    object NoFilterExceptRevertManagment : FilteringFunctions {
        override fun isInlineable(v: TACSymbol.Var) = true
        override fun isErasable(cmd: TACCmd.Simple.AssigningCmd) = REVERT_MANAGEMENT !in cmd.meta
    }

    open class CallTracePreserving(code: CoreTACProgram) : FilteringFunctions {
        private val protectedMetas =
            listOf(
                TACMeta.CVL_VAR,
                TACMeta.STORAGE_KEY,
                TACMeta.IS_CALLDATA,
                TACMeta.IS_RETURNDATA
            )

        open val protectedCmdMeta: List<MetaKey<*>> = listOf(TACMeta.CVL_EXP)

        private val protectedVars =
            code.symbolTable.uninterpretedFunctions().mapNotNullToSet { it.asVarOrNull() }

        override fun isInlineable(v: TACSymbol.Var) =
            v !in protectedVars && protectedMetas.none { it in v.meta }

        override fun isErasable(cmd: TACCmd.Simple.AssigningCmd) =
            isInlineable(cmd.lhs) && protectedCmdMeta.none { it in cmd.meta }
    }

    class BMCPreserving(code: CoreTACProgram) : CallTracePreserving(code) {
        override val protectedCmdMeta: List<MetaKey<*>> = super.protectedCmdMeta + TACMeta.LAST_STORAGE_UPDATE
    }

    companion object {

        fun default(code: CoreTACProgram, keepRevertManagment: Boolean = false, bmcAware: Boolean = false) =
            if (code.destructiveOptimizations) {
                if (keepRevertManagment) {
                    NoFilterExceptRevertManagment
                } else {
                    NoFilter
                }
            } else {
                if (bmcAware) {
                    BMCPreserving(code)
                } else {
                    CallTracePreserving(code)
                }
            }
    }
}
