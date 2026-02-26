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

package sbf.tac

import vc.data.*
import datastructures.stdcollections.*
import sbf.domains.*

/**
 * Summarize 64-bit signed division and modulo operations.
 **/
open class SummarizeSignedInteger64CompilerRt<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> {

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun summarizeDivdi3(
        res: TACSymbol.Var,
        @Suppress("UNUSED_PARAMETER")
        arg1: TACSymbol,
        @Suppress("UNUSED_PARAMETER")
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        return  listOf(
            Debug.unsupported("Warning: __divdi3 is not modeled precisely in TAC", listOf(res)),
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(res)
        )
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun summarizeModdi3(
        res: TACSymbol.Var,
        @Suppress("UNUSED_PARAMETER")
        arg1: TACSymbol,
        @Suppress("UNUSED_PARAMETER")
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        return listOf(
            Debug.unsupported("Warning: __moddi3 is not modeled precisely in TAC", listOf(res)),
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(res)
        )
    }

}
