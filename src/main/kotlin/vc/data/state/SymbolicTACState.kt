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

package vc.data.state

import analysis.CmdPointer
import scene.IScene
import spec.rules.CVLSingleRule
import vc.data.CoreTACProgram
import vc.data.TACSymbol


/**
 * State for concolic execution.
It cannot be a data class because it should be inherited.
It cannot be a sealed class because the interpreter state that inherits from it sits in another directory.
 */
abstract class SymbolicTACState(
    val vars: Map<TACSymbol.Var, TACValue>,
    val path: List<CmdPointer>,
    val ctp: CoreTACProgram,
    val scene: IScene,
    val rule: CVLSingleRule?
) : TACState
