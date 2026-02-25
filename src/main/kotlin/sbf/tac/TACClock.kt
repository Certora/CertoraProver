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

import sbf.callgraph.SolanaFunction
import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.SbfInstruction
import sbf.disassembler.SbfRegister
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import vc.data.TACCmd
import vc.data.TACSymbol
import java.math.BigInteger

/** TAC modeling of the solana clock **/
class Clock(mkFreshIntVar: (prefix: String)-> TACSymbol.Var) {
    // the current slot
    private val slot: TACSymbol.Var = mkFreshIntVar("clock.slot")
    // the timestamp of the first slot in this epoch
    private val epochStartTimestamp: TACSymbol.Var = mkFreshIntVar("clock.epoch_start_timestamp")
    // the current epoch
    private val epoch: TACSymbol.Var = mkFreshIntVar("clock.epoch")
    // the future epoch for which the leader schedule has most recently been calculated
    private val leaderScheduleEpoch: TACSymbol.Var = mkFreshIntVar("clock.leader_schedule_epoch")
    private val unixTimestamp: TACSymbol.Var = mkFreshIntVar("clock.unix_timestamp")

    /** Emit TAC code for `sol_set_clock_sysvar` **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> set(
        locInst: LocatedSbfInstruction
    ): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        check(SolanaFunction.from(inst.name) == SolanaFunction.SOL_SET_CLOCK_SYSVAR)

        val cmds = mutableListOf<TACCmd.Simple>()
        val tacVars = getTACVariables(locInst, cmds)
        check(tacVars.size == 5) {"TAC Clock::set expected to extract 5 TAC variables from ${locInst.inst}"}
        val (v1, v2, v3, v4, v5) = tacVars
        cmds += Debug.startFunction("sol_set_clock_sysvar")
        cmds += assign(slot, v1.asSym())
        cmds += assign(epochStartTimestamp, v2.asSym())
        cmds += assign(epoch, v3.asSym())
        cmds += assign(leaderScheduleEpoch, v4.asSym())
        cmds += assign(unixTimestamp, v5.asSym())

        val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
        cmds += TACCmd.Simple.AssigningCmd.AssignHavocCmd(r0)
        cmds += Debug.endFunction("sol_set_clock_sysvar")
        return cmds
    }

    /** Emit TAC code for `sol_get_clock_sysvar` **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> get(
        locInst: LocatedSbfInstruction
    ): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        check(SolanaFunction.from(inst.name) == SolanaFunction.SOL_GET_CLOCK_SYSVAR)

        val cmds = mutableListOf<TACCmd.Simple>()
        val tacVars = getTACVariables(locInst, cmds)
        check(tacVars.size == 5) {"TAC Clock::get expected to extract 5 TAC variables from ${locInst.inst}"}
        val (v1, v2, v3, v4, v5) = tacVars
        cmds += Debug.startFunction("sol_get_clock_sysvar")
        cmds += assign(v1, slot.asSym())
        cmds += inRange(v1, BigInteger.ZERO, BigInteger.TWO.pow(64) - BigInteger.ONE)
        cmds += assign(v2, epochStartTimestamp.asSym())
        cmds += inRange(v1, BigInteger.ZERO, BigInteger.TWO.pow(64) - BigInteger.ONE)
        cmds += assign(v3, epoch.asSym())
        cmds += inRange(v1, BigInteger.ZERO, BigInteger.TWO.pow(64) - BigInteger.ONE)
        cmds += assign(v4, leaderScheduleEpoch.asSym())
        cmds += inRange(v1, BigInteger.ZERO, BigInteger.TWO.pow(64) - BigInteger.ONE)
        cmds += assign(v5, unixTimestamp.asSym())
        cmds += inRange(v1, BigInteger.ZERO, BigInteger.TWO.pow(64) - BigInteger.ONE)

        val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
        cmds += TACCmd.Simple.AssigningCmd.AssignHavocCmd(r0)
        cmds += Debug.endFunction("sol_get_clock_sysvar")
        return cmds
    }
}
