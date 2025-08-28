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

package sbf.callgraph

import sbf.domains.MemorySummaries
import sbf.domains.MemorySummary
import datastructures.stdcollections.*

object AbortFunctions {
    /**
     * List of known Rust functions that always abort.
     **/
    private val functions = setOf(
        "__rg_oom",

        "alloc::alloc::handle_alloc_error",
        "alloc::raw_vec::handle_error",
        "alloc::raw_vec::capacity_overflow",

        "borsh::de::unexpected_eof_to_unexpected_length_of_input",

        "core::slice::index::slice_start_index_len_fail",
        "core::slice::index::slice_end_index_len_fail",
        "core::slice::index::slice_index_order_fail",
        "core::cell::panic_already_borrowed",
        "core::cell::panic_already_mutably_borrowed",
        "core::option::unwrap_failed",
        "core::result::unwrap_failed",
        "core::option::expect_failed",
        "core::result::expect_failed",

        "core::panicking::panic_const::panic_const_add_overflow",
        "core::panicking::panic_const::panic_const_sub_overflow",
        "core::panicking::panic_const::panic_const_mul_overflow",
        "core::panicking::panic_const::panic_const_div_overflow",
        "core::panicking::panic_const::panic_const_rem_overflow",
        "core::panicking::panic_const::panic_const_neg_overflow",
        "core::panicking::panic_const::panic_const_shr_overflow",
        "core::panicking::panic_const::panic_const_shl_overflow",
        "core::panicking::panic_const::panic_const_div_by_zero",
        "core::panicking::panic_const::panic_const_rem_by_zero",
        "core::panicking::panic_const::panic_const_coroutine_resumed",
        "core::panicking::panic_const::panic_const_async_fn_resumed",
        "core::panicking::panic_const::panic_const_async_gen_fn_resumed",
        "core::panicking::panic_const::panic_const_gen_fn_none",
        "core::panicking::panic_const::panic_const_coroutine_resumed_panic",
        "core::panicking::panic_const::panic_const_async_fn_resumed_panic",
        "core::panicking::panic_const::panic_const_async_gen_fn_resumed_panic",
        "core::panicking::panic_const::panic_const_gen_fn_none_panic",
        "core::panicking::panic_bounds_check",
        "core::panicking::panic_misaligned_pointer_dereference",
        "core::panicking::panic_fmt",
        "core::panicking::panic",
        "core::panicking::panic_explicit"
    )

    fun addSummaries(memSummaries: MemorySummaries) {
        functions.forEach {
            memSummaries.addSummary(it, MemorySummary(args = listOf(), isAbort = true))
        }
    }

    /**
     * Return true if [fname] is a function that always abort.
     *
     * [fname] can be either mangled or demangled because it can be called very early in the Solana pipeline.
     */
    operator fun contains(fname: String): Boolean {
        if (fname in functions) {
            return true
        }
        // We could cache queries to `fastDemangleRust` if needed.
        return fastDemangleRust(fname)?.let { it in functions } ?: false
    }
}

