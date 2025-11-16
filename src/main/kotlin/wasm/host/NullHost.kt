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

package wasm.host

import analysis.CommandWithRequiredDecls
import vc.data.TACCmd
import vc.data.TACSymbol
import wasm.ir.WasmName
import wasm.ir.WasmProgram

object NullHost : WasmHost {
    override fun init() = CommandWithRequiredDecls<TACCmd.Simple>()

    override fun importer(program: WasmProgram) = object : WasmHost.Importer {
        override fun resolve(id: WasmName): Boolean = false
        override fun importFunc(id: WasmName, args: List<TACSymbol>, retVar: TACSymbol.Var?) = null
    }
}
