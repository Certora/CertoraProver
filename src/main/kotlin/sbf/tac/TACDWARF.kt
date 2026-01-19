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

import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.Value
import sbf.domains.PTAOffset
import vc.data.TACSymbol

interface TACDebugView {
    /**
     * - If [reg] contains a stack pointer `sp` at [locInst] then return the TAC variable that represents
     *   the stack byte at offset [offset].
     * - Otherwise, it returns null.
     * **/
    fun getStackTACVariable(locInst: LocatedSbfInstruction, reg: Value.Reg, offset: PTAOffset): TACSymbol.Var?

    /** Return the TAC variable that represents [reg] **/
    fun getRegisterTACVariable(reg: Value.Reg): TACSymbol.Var
}
