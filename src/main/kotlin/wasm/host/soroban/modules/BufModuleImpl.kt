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

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import tac.Tag
import utils.*
import vc.data.*
import wasm.host.soroban.*
import wasm.host.soroban.types.*
import wasm.tacutils.*

internal object BufModuleImpl : ModuleImpl() {
    override fun getFuncImpl(funcName: String, args: List<TACSymbol>, retVar: TACSymbol.Var?) =
        when(funcName) {
            "bytes_new" -> BytesType.empty(retVar!!)
            "bytes_new_from_linear_memory" -> BytesType.newFromMemory(retVar!!, args[0], args[1])
            "bytes_copy_to_linear_memory" -> BytesType.copyToMemory(args[0], args[1], args[2], args[3])
            "bytes_copy_from_linear_memory" -> BytesType.copyFromMemory(retVar!!, args[0], args[1], args[2], args[3])
            "bytes_len" -> BytesType.getSize(retVar!!, args[0])
            "bytes_put" -> BytesType.put(retVar!!, args[0], args[1], args[2])
            "bytes_get" -> BytesType.get(retVar!!, args[0], args[1])
            "bytes_push" -> BytesType.pushBack(retVar!!, args[0], args[1])
            "bytes_pop" -> BytesType.popBack(retVar!!, args[0])
            "bytes_front" -> BytesType.front(retVar!!, args[0])
            "bytes_back" -> BytesType.back(retVar!!, args[0])
            "bytes_insert" -> BytesType.insert(retVar!!, args[0], args[1], args[2])
            "bytes_del" -> BytesType.delete(retVar!!, args[0], args[1])
            "bytes_append" -> BytesType.append(retVar!!, args[0], args[1])
            "bytes_slice" -> BytesType.slice(retVar!!, args[0], args[1], args[2])
            "string_new_from_linear_memory" -> StringType.newFromMemory(retVar!!, args[0], args[1])
            "string_copy_to_linear_memory" -> StringType.copyToMemory(args[0], args[1], args[2], args[3])
            "string_len" -> StringType.getSize(retVar!!, args[0])
            "symbol_new_from_linear_memory" -> SymbolType.newFromMemory(retVar!!, args[0], args[1])
            "symbol_copy_to_linear_memory" -> SymbolType.copyToMemory(args[0], args[1], args[2], args[3])
            "symbol_len" -> SymbolType.getSize(retVar!!, args[0])
            "serialize_to_bytes" -> serializeToBytes(retVar!!, args[0])
            "deserialize_from_bytes" -> deserializeFromBytes(retVar!!, args[0])
            "symbol_index_in_linear_memory" -> SymbolType.symbolIndexInLinearMemory(retVar!!, args[0], args[1], args[2])
            else -> null
        }

    /**
        Summary of the `serialize_to_bytes` function, which does an XDR serialization of the given Val structure.

        This is a placeholder implementation that simply assigns a fresh bytes handle to the return variable, with
        a size that is greater than zero.

        Since Soroban handles most serialization/deserialization internally, it is hoped that calls to this function
        are rare, so a more faithful implementation is not necessary.
     */
    private fun serializeToBytes(retVar: TACSymbol.Var, v: TACSymbol) =
        mergeMany(
            BytesType.new(retVar) { unconstrained(Tag.Bit256) },
            BytesType.withSize(retVar) { length ->
                assume { length.asSym() gt 0.asTACExpr }
            }
        ).also { unused(v) }

    /**
        Summary of the `deserialize_from_bytes` function, which does an XDR deserialization to a Val structure.

        This is a placeholder implementation that simply returns an arbitrary value.

        Since Soroban handles most serialization/deserialization internally, it is hoped that calls to this function
        are rare, so a more faithful implementation is not necessary.
     */
    private fun deserializeFromBytes(retVar: TACSymbol.Var, handle: TACSymbol) =
        assignHavoc(retVar).also { unused(handle) }
}
