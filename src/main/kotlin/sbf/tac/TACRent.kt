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
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.domains.*
import vc.data.TACCmd
import vc.data.TACSymbol
import java.math.BigInteger

/** TAC modeling of solana rent **/
class Rent(mkFreshIntVar: (prefix: String)-> TACSymbol.Var) {
    // Rental rate in lamports/byte-year.
    private val lamportsPerByteYear: TACSymbol.Var = mkFreshIntVar("rent.lamports_per_byte_year")
    // Amount of time (in years) a balance must include rent for the account to be rent exempt.
    private val exemptionThreshold: TACSymbol.Var = mkFreshIntVar("rent.exemption_threshold")
    // The percentage of collected rent that is burned. Valid values are in the range [0, 100].
    // The remaining percentage is distributed to validators.
    private val burnPercent: TACSymbol.Var = mkFreshIntVar("rent.burn_percent")

    /**
     * Emit TAC code for `sol_get_rent_sysvar`.
     *
     * Add extra assumptions:
     * 1) `lamports_per_byte_year > 0`
     * 2) `exemptionThreshold == 2`
     * 3) `burn_percent >= 0 && burn_percent <= 100`
     **/
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> get(
        locInst: LocatedSbfInstruction
    ): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        check(SolanaFunction.from(inst.name) == SolanaFunction.SOL_GET_RENT_SYSVAR)

        val cmds = mutableListOf<TACCmd.Simple>()
        val tacVars = getTACVariables(locInst, cmds)
        check(tacVars.size == 3) {"TAC Rent::get expected to extract 3 TAC variables from ${locInst.inst}"}
        val (v1, v2, v3) = tacVars

        cmds += Debug.startFunction("sol_get_rent_sysvar")
        // Add assumption `lamports_per_byte_year > 0`
        cmds += assign(v1,  lamportsPerByteYear.asSym())
        cmds += inRange(v1, BigInteger.ONE, BigInteger.TWO.pow(64) - BigInteger.ONE)
        // `exemptionThreshold` is a f64 number.
        cmds += assign(v2,  exemptionThreshold.asSym())
        // Add the assumption that `exemptionThreshold == 2`.
        cmds += assume(SummarizeFPCompilerRt<TNum, TOffset, TFlags>().isTwo(v2), "exemption_threshold == 2")
        // Add assumption that `burn_percent` is between 0 and 100
        cmds += assign(v3,  burnPercent.asSym())
        cmds += inRange(v1, BigInteger.ZERO, BigInteger.valueOf(100))
        // Havoc r0
        val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
        cmds += TACCmd.Simple.AssigningCmd.AssignHavocCmd(r0)

        cmds += Debug.endFunction("sol_get_rent_sysvar")
        return cmds
    }
}
