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

package cvlr

object CvlrFunctions {
    const val CVT_nondet_u8 = "CVT_nondet_u8"
    const val CVT_nondet_u16 = "CVT_nondet_u16"
    const val CVT_nondet_u32 = "CVT_nondet_u32"
    const val CVT_nondet_u64 = "CVT_nondet_u64"
    const val CVT_nondet_u128 = "CVT_nondet_u128"
    const val CVT_nondet_usize = "CVT_nondet_usize"
    const val CVT_nondet_i8 = "CVT_nondet_i8"
    const val CVT_nondet_i16 = "CVT_nondet_i16"
    const val CVT_nondet_i32 = "CVT_nondet_i32"
    const val CVT_nondet_i64 = "CVT_nondet_i64"
    const val CVT_nondet_i128 = "CVT_nondet_i128"
    const val CVT_assume = "CVT_assume"
    const val CVT_assert = "CVT_assert"
    const val CVT_satisfy = "CVT_satisfy"
    const val CVT_sanity = "CVT_sanity"
    const val CVT_nondet_map = "CVT_nondet_map"
    const val CVT_rule_location = "CVT_rule_location"
    const val CVT_calltrace_print_u32_1 = "CVT_calltrace_print_u32_1"
    const val CVT_calltrace_print_u64_1 = "CVT_calltrace_print_u64_1"
    const val CVT_calltrace_print_u64_2 = "CVT_calltrace_print_u64_2"
    const val CVT_calltrace_print_u64_3 = "CVT_calltrace_print_u64_3"
    const val CVT_calltrace_print_u128 = "CVT_calltrace_print_u128"
    const val CVT_calltrace_print_u64_as_fixed = "CVT_calltrace_print_u64_as_fixed"
    const val CVT_calltrace_print_u64_as_decimal = "CVT_calltrace_print_u64_as_decimal"
    const val CVT_calltrace_print_i32_1 = "CVT_calltrace_print_i32_1"
    const val CVT_calltrace_print_i64_1 = "CVT_calltrace_print_i64_1"
    const val CVT_calltrace_print_i64_2 = "CVT_calltrace_print_i64_2"
    const val CVT_calltrace_print_i64_3 = "CVT_calltrace_print_i64_3"
    const val CVT_calltrace_print_i128 = "CVT_calltrace_print_i128"
    const val CVT_calltrace_print_string = "CVT_calltrace_print_string"
    const val CVT_calltrace_print_tag = "CVT_calltrace_print_tag"
    const val CVT_calltrace_print_location = "CVT_calltrace_print_location"
    const val CVT_calltrace_attach_location = "CVT_calltrace_attach_location"
    const val CVT_calltrace_scope_start = "CVT_calltrace_scope_start"
    const val CVT_calltrace_scope_end = "CVT_calltrace_scope_end"
    const val CVT_u128_leq = "CVT_u128_leq"
    const val CVT_u128_gt0 = "CVT_u128_gt0"
    const val CVT_u128_ceil_div = "CVT_u128_ceil_div"
    const val CVT_nativeint_u64_eq = "CVT_nativeint_u64_eq"
    const val CVT_nativeint_u64_lt = "CVT_nativeint_u64_lt"
    const val CVT_nativeint_u64_le = "CVT_nativeint_u64_le"
    const val CVT_nativeint_u64_add = "CVT_nativeint_u64_add"
    const val CVT_nativeint_u64_sub = "CVT_nativeint_u64_sub"
    const val CVT_nativeint_u64_mul = "CVT_nativeint_u64_mul"
    const val CVT_nativeint_u64_div = "CVT_nativeint_u64_div"
    const val CVT_nativeint_u64_div_ceil = "CVT_nativeint_u64_div_ceil"
    const val CVT_nativeint_u64_muldiv = "CVT_nativeint_u64_muldiv"
    const val CVT_nativeint_u64_muldiv_ceil = "CVT_nativeint_u64_muldiv_ceil"
    const val CVT_nativeint_u64_nondet = "CVT_nativeint_u64_nondet"
    const val CVT_nativeint_u64_from_u128 = "CVT_nativeint_u64_from_u128"
    const val CVT_nativeint_u64_from_u256 = "CVT_nativeint_u64_from_u256"
    const val CVT_nativeint_u64_u64_max = "CVT_nativeint_u64_u64_max"
    const val CVT_nativeint_u64_u128_max = "CVT_nativeint_u64_u128_max"
    const val CVT_nativeint_u64_u256_max = "CVT_nativeint_u64_u256_max"
    const val CVT_alloc_slice = "CVT_alloc_slice"
}
