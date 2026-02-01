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

package wasm.host.soroban.modules

import vc.data.*
import wasm.host.soroban.*
import wasm.host.soroban.types.*

internal object VecModuleImpl : ModuleImpl() {
    override fun getFuncImpl(funcName: String, args: List<TACSymbol>, retVar: TACSymbol.Var?) =
        when(funcName) {
            "vec_new" -> VecType.empty(retVar!!)
            "vec_put" -> VecType.put(retVar!!, args[0], args[1], args[2])
            "vec_get" -> VecType.get(retVar!!, args[0], args[1])
            "vec_del" -> VecType.delete(retVar!!, args[0], args[1])
            "vec_len" -> VecType.getSize(retVar!!, args[0])
            "vec_push_front" -> VecType.pushFront(retVar!!, args[0], args[1])
            "vec_pop_front" -> VecType.popFront(retVar!!, args[0])
            "vec_push_back" -> VecType.pushBack(retVar!!, args[0], args[1])
            "vec_pop_back" -> VecType.popBack(retVar!!, args[0])
            "vec_front" -> VecType.front(retVar!!, args[0])
            "vec_back" -> VecType.back(retVar!!, args[0])
            "vec_insert" -> VecType.insert(retVar!!, args[0], args[1], args[2])
            "vec_append" -> VecType.append(retVar!!, args[0], args[1])
            "vec_slice" -> VecType.slice(retVar!!, args[0], args[1], args[2])
            "vec_new_from_linear_memory" -> VecType.newFromMemory(retVar!!, args[0], args[1])
            "vec_unpack_to_linear_memory" -> VecType.copyToMemory(args[0], args[1], args[2])
            "vec_first_index_of" -> VecType.findFirst(retVar!!, args[0], args[1])
            // TODO CERT-6700:
            // vec_last_index_of
            // vec_binary_search
            else -> null
        }
}
