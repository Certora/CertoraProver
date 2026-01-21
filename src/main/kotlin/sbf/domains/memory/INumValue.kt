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

package sbf.domains

import sbf.cfg.CondOp

/**
 *  An interface to represent an approximation of a numerical value (aka basis)
 *  Possible implementations are constants, intervals, congruences, etc.
 **/

interface INumValue<V> {
    /**
     * Attempts to convert this numeric value to a signed [Long].
     * Returns `null` if the conversion is not possible.
     */
    fun toLongOrNull(): Long?

    /**
     * Returns a [List] of all possible [Long] representations of this value.
     * Could be empty if there are no long representations.
     */
    fun toLongList(): List<Long>

    /* Numerical operations */
    fun add(other: V): V
    fun add(n: Long): V
    fun sub(other: V): V
    fun sub(n: Long): V
    fun mul(other: V): V
    fun mul(n: Long): V
    fun and(other: V): V
    fun or(other: V): V
    fun xor(other: V): V
    fun udiv(other: V): V
    fun sdiv(other: V): V
    fun urem(other: V): V
    fun srem(other: V): V
    fun arsh(other: V): V
    fun rsh(other: V): V
    fun lsh(other: V): V
    /** Take 8 low bits and zero-extend to 64 bits **/
    fun zext8(): V
    /** Take 16 low bits and zero-extend to 64 bits **/
    fun zext16(): V
    /** Take 32 low bits and zero-extend to 64 bits **/
    fun zext32(): V

    fun assume(op: CondOp, other: V): TriBoolean
    // filter the values of `this` after the execution `this op other`
    fun filter(op: CondOp, other: V): V
    /* lattice operations */
    fun isTop(): Boolean
    fun isBottom(): Boolean
    fun join(other: V): V
    fun widen(other: V): V
    fun meet(other: V): V
    fun lessOrEqual(other: V): Boolean
    override fun toString(): String
}
