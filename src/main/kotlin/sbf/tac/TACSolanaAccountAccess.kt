/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

import sbf.SolanaConfig
import sbf.cfg.SbfCFG
import sbf.cfg.SolanaAccountRange
import sbf.cfg.collectSolanaAccountRanges
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import datastructures.stdcollections.*

/** TAC instrumentation to model accesses to Solana accounts **/
class TACSolanaAccountAccess(
    cfg: SbfCFG,
    mkFreshBoolVar: (prefix: String) -> TACSymbol.Var
) {

    private val ranges: List<SolanaAccountRange> = collectSolanaAccountRanges(cfg)
    private val flags: List<TACSymbol.Var> = List(ranges.size) { i ->
        mkFreshBoolVar("solana_account_written_$i")
    }

    /** Emit TAC code that initializes instrumentation **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
    init(): List<TACCmd.Simple> {
        if (!SolanaConfig.TACAccountWrites.get()) {
            return listOf()
        }
        return flags.map { flag -> assign(flag, sbfTacB.FALSE) }
    }

    /** Emit TAC code that sets that the account where [loc] lives has been written **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
    updateWrite(loc: TACSymbol.Var): List<TACCmd.Simple> {
        if (!SolanaConfig.TACAccountWrites.get()) {
            return listOf()
        }

        val cmds = mutableListOf<TACCmd.Simple>()
        cmds += Debug.startFunction("UpdateWrite")
        cmds += flags.zip(ranges).map { (flag, range) ->
            assign(flag,
                sbfTacB {
                    switch(
                        (loc.asSym() ge mkConst(range.start.toLong()).asSym()) and
                            (loc.asSym() lt mkConst(range.end.toLong()).asSym()) to TRUE,
                        default = flag.asSym()
                    )
                })
        }
        cmds += Debug.endFunction("UpdateWrite")
        return cmds
    }

    /**
     * Sets [res] to 1 if [loc] falls within any account range that has been written, 0 otherwise.
     * Returns 0 unconditionally when [SolanaConfig.TACAccountWrites] is disabled.
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
    isWritten(res: TACSymbol.Var, loc: TACSymbol.Var): List<TACCmd.Simple> {
        if (!SolanaConfig.TACAccountWrites.get()) {
            return listOf(havoc(res))
        }

        val cmds = mutableListOf<TACCmd.Simple>()
        cmds += Debug.startFunction("IsWritten")
        var isWritten: TACExpr? = null
        flags.zip(ranges).forEach { (flag, range) ->
            val boolV = vFac.mkFreshBoolVar()
            isWritten = sbfTacB { isWritten?.or(boolV.asSym()) ?: boolV.asSym() }
            cmds += assign(boolV, sbfTacB {
                switch(
                    (loc.asSym() ge mkConst(range.start.toLong()).asSym()) and
                        (loc.asSym() lt mkConst(range.end.toLong()).asSym()) to flag.asSym(),
                    default = FALSE
                )
            })
        }
        cmds += assign(res, sbfTacB { switch((isWritten ?: FALSE) to ONE, default = ZERO) })
        cmds += Debug.endFunction("IsWritten")
        return cmds
    }
}
