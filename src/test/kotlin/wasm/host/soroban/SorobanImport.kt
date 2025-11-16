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

package wasm.host.soroban

import wasm.*
import wasm.wat.*

/**
    Represents an imported Soroban host function.  Example:

    ```
        val dummy0 = import("test", "dummy0")
        val wat = simplifyWAT(
            """
            (module
                ${dummy0.import}
                (func (export "entry") (param i64) (result i64)
                    call $dummy0
                )
            )
            """
        )
    ```
*/
object SorobanImport {
    object Prng {
        val reseed = import("prng", "prng_reseed")
        val bytesNew = import("prng", "prng_bytes_new")
        val u64InInclusiveRange = import("prng", "prng_u64_in_inclusive_range")
        val vecShuffle = import("prng", "prng_vec_shuffle")
    }
    object Buf {
        val new = import("buf", "bytes_new")
        val new_from_linear_memory = import("buf", "bytes_new_from_linear_memory")
        val copy_to_linear_memory = import("buf", "bytes_copy_to_linear_memory")
        val copy_from_linear_memory = import("buf", "bytes_copy_from_linear_memory")
        val len = import("buf", "bytes_len")
        val push = import("buf", "bytes_push")
        val pop = import("buf", "bytes_pop")
        val get = import("buf", "bytes_get")
        val put = import("buf", "bytes_put")
        val insert = import("buf", "bytes_insert")
        val del = import("buf", "bytes_del")
        val append = import("buf", "bytes_append")
        val string_new_from_linear_memory = import("buf", "string_new_from_linear_memory")
        val string_copy_to_linear_memory = import("buf", "string_copy_to_linear_memory")
    }
    object Map {
        val new = import("map", "map_new")
        val put = import("map", "map_put")
        val get = import("map", "map_get")
        val del = import("map", "map_del")
        val len = import("map", "map_len")
        val has = import("map", "map_has")
        val key_by_pos = import("map", "map_key_by_pos")
        val val_by_pos = import("map", "map_val_by_pos")
        val keys = import("map", "map_keys")
        val values = import("map", "map_values")
        val new_from_linear_memory = import("map", "map_new_from_linear_memory")
        val unpack_to_linear_memory = import("map", "map_unpack_to_linear_memory")
    }
    object Vec {
        val new = import("vec", "vec_new")
        val put = import("vec", "vec_put")
        val get = import("vec", "vec_get")
        val del = import("vec", "vec_del")
        val len = import("vec", "vec_len")
        val push_front = import("vec", "vec_push_front")
        val pop_front = import("vec", "vec_pop_front")
        val push_back = import("vec", "vec_push_back")
        val pop_back = import("vec", "vec_pop_back")
        val front = import("vec", "vec_front")
        val back = import("vec", "vec_back")
        val insert = import("vec", "vec_insert")
        val append = import("vec", "vec_append")
        val slice = import("vec", "vec_slice")
        val new_from_linear_memory = import("vec", "vec_new_from_linear_memory")
        val unpack_to_linear_memory = import("vec", "vec_unpack_to_linear_memory")
    }
    // Not named "Test" because that would conflict with "@Test" annotations
    object TestModule {
        val dummy0 = import("test", "dummy0")
        val protocol_gated_dummy = import("test", "protocol_gated_dummy")
    }
    object Context {
        val log_from_linear_memory = import("context", "log_from_linear_memory")
        val obj_cmp = import("context", "obj_cmp")
        val contract_event = import("context", "contract_event")
        val get_ledger_version = import("context", "get_ledger_version")
        val get_ledger_sequence = import("context", "get_ledger_sequence")
        val get_ledger_timestamp = import("context", "get_ledger_timestamp")
        val fail_with_error = import("context", "fail_with_error")
        val get_ledger_network_id = import("context", "get_ledger_network_id")
        val get_current_contract_address = import("context", "get_current_contract_address")
        val get_max_live_until_ledger = import("context", "get_max_live_until_ledger")
    }
    object Crypto {
        val keccak256 = import(module = "crypto", func = "compute_hash_keccak256", params = listOf(i64), result = i64)
    }
    object Ledger {
        val put_contract_data = import("ledger", "put_contract_data")
        val has_contract_data = import("ledger", "has_contract_data")
        val get_contract_data = import("ledger", "get_contract_data")
        val del_contract_data = import("ledger", "del_contract_data")
    }
    object IntType {
        val obj_from_u64 = import("int", "obj_from_u64", listOf(u64), i64)
        val obj_to_u64 = import("int", "obj_to_u64", listOf(i64), u64)
        val obj_from_i64 = import("int", "obj_from_i64", listOf(i64), i64)
        val obj_to_i64 = import("int", "obj_to_i64", listOf(i64), i64)
        val obj_from_u128_pieces = import("int", "obj_from_u128_pieces", listOf(u64, u64), i64)
        val obj_to_u128_lo64 = import("int", "obj_to_u128_lo64", listOf(i64), u64)
        val obj_to_u128_hi64 = import("int", "obj_to_u128_hi64", listOf(i64), u64)
        val obj_from_i128_pieces = import("int", "obj_from_i128_pieces", listOf(i64, u64), i64)
        val obj_to_i128_lo64 = import("int", "obj_to_i128_lo64", listOf(i64), u64)
        val obj_to_i128_hi64 = import("int", "obj_to_i128_hi64", listOf(i64), i64)
        val obj_from_u256_pieces = import("int", "obj_from_u256_pieces", listOf(u64, u64, u64, u64), i64)
        val obj_to_u256_lo_lo = import("int", "obj_to_u256_lo_lo", listOf(i64), u64)
        val obj_to_u256_lo_hi = import("int", "obj_to_u256_lo_hi", listOf(i64), u64)
        val obj_to_u256_hi_lo = import("int", "obj_to_u256_hi_lo", listOf(i64), u64)
        val obj_to_u256_hi_hi = import("int", "obj_to_u256_hi_hi", listOf(i64), u64)
        val obj_from_i256_pieces = import("int", "obj_from_i256_pieces", listOf(i64, u64, u64, u64), i64)
        val obj_to_i256_lo_lo = import("int", "obj_to_i256_lo_lo", listOf(i64), u64)
        val obj_to_i256_lo_hi = import("int", "obj_to_i256_lo_hi", listOf(i64), u64)
        val obj_to_i256_hi_lo = import("int", "obj_to_i256_hi_lo", listOf(i64), u64)
        val obj_to_i256_hi_hi = import("int", "obj_to_i256_hi_hi", listOf(i64), i64)
        val u256_val_from_be_bytes = import("int", "u256_val_from_be_bytes", listOf(i64), i64)
        val u256_val_to_be_bytes = import("int", "u256_val_to_be_bytes", listOf(i64), i64)
        val u256_add = import("int", "u256_add", listOf(i64, i64), i64)
        val u256_sub = import("int", "u256_sub", listOf(i64, i64), i64)
        val u256_mul = import("int", "u256_mul", listOf(i64, i64), i64)
        val u256_div = import("int", "u256_div", listOf(i64, i64), i64)
        val u256_rem_euclid = import("int", "u256_rem_euclid", listOf(i64, i64), i64)
        val u256_pow = import("int", "u256_pow", listOf(i64, i64), i64)
        val u256_shl = import("int", "u256_shl", listOf(i64, i64), i64)
        val u256_shr = import("int", "u256_shr", listOf(i64, i64), i64)
        val i256_add = import("int", "i256_add", listOf(i64, i64), i64)
        val i256_sub = import("int", "i256_sub", listOf(i64, i64), i64)
        val i256_mul = import("int", "i256_mul", listOf(i64, i64), i64)
        val i256_div = import("int", "i256_div", listOf(i64, i64), i64)
        val i256_rem_euclid = import("int", "i256_rem_euclid", listOf(i64, i64), i64)
        val i256_pow = import("int", "i256_pow", listOf(i64, i64), i64)
        val i256_shl = import("int", "i256_shl", listOf(i64, i64), i64)
        val i256_shr = import("int", "i256_shr", listOf(i64, i64), i64)
    }
    object Address {
        val require_auth = import("address", "require_auth")
        val address_to_strkey = import("address", "address_to_strkey")
    }
    object CVT {
        val is_auth = WatImport("env", "CERTORA_SOROBAN_is_auth", listOf(i64), i64)
    }

    private fun <T : WatResult> import(
        module: String,
        func: String,
        params: List<WatType>?,
        result: T
    ): WatImport<T> {
        val resolvedModule = sorobanEnv.moduleExportsByName[module] ?: error("Module not found: $module")
        val resolvedFunc = resolvedModule.functionExportsByName[func] ?: error("Function not found: $module/$func")
        return WatImport(
            module = resolvedModule.export,
            func = resolvedFunc.export,
            params = params ?: resolvedFunc.args.map { i64 },
            result = result,
            name = "${module}.${func}",
        )
    }

    private fun import(
        module: String,
        func: String,
    ) = import(module, func, null, i64)
}
