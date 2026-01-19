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

package sbf.analysis

import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.Value
import sbf.disassembler.SbfRegister
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.SbfType

interface IRegisterTypes<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> {
    /**
     * Return the type of [r] __after__ the execution of instruction [i].
     *
     * - If [isWritten] is `true` then [r] must be a written register by [i], otherwise error.
     * - If [isWritten] is `false` then [r] can be any register. If [r] is both read and written by [i] the returned type
     *   is the one from the read register.
     **/
    fun typeAtInstruction(i: LocatedSbfInstruction, r: Value.Reg, isWritten: Boolean = false): SbfType<TNum, TOffset>

    fun typeAtInstruction(i: LocatedSbfInstruction, r: SbfRegister, isWritten: Boolean = false): SbfType<TNum, TOffset> =
        typeAtInstruction(i, Value.Reg(r), isWritten)
}
