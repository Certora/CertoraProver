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

/**
 * An interface for [PTANode] flags.
 *
 * A flag can be any bit or number attached to a [PTANode].
 **/
interface IPTANodeFlags<Flags> {
    val isMayStack: Boolean
    val isMayGlobal: Boolean
    val isMayHeap: Boolean
    val isMayExternal: Boolean
    fun isMayInteger(): Boolean
    fun isMustInteger(): Boolean
    val access: NodeAccess
    // If the node represents an account then this is its start address
    val accountStart: Constant
    // if the node contains an integer then it returns its value
    fun getInteger(): Constant

    // These functions are executed only once when the PTANode is created
    fun stackInitializer(): Flags
    fun globalInitializer(): Flags
    fun heapInitializer(): Flags
    fun externalInitializer(): Flags
    /** Similar to `externalInitializer()` but specialized when `CVTCore.NONDET_SOLANA_ACCOUNT_SPACE` is called **/
    fun externalAccountSpaceInitializer(accountStart: Constant): Flags
    /** Similar to `externalInitializer()` but specialized when `CVTCore.ALLOC_SLICE` is called **/
    fun externalAccountSliceInitializer(base: Constant, offset: Long): Flags
    fun integerInitializer(value: Constant): Flags

    fun setWrite(): Flags
    fun setRead(): Flags

    // merge flags during [PTANode] unifications or weak updates
    fun join(other: Flags): Flags

    // Extra node attributes in dot format
    fun toDot(): String
}

enum class NodeAccess(val value: Int) {
    None(0x0),
    Read(0x1),
    Write(0x2),
    Any(0x3);

    companion object {
        val values = arrayListOf(None, Read, Write, Any)
    }

    fun join(other: NodeAccess): NodeAccess {
        return values[value.or(other.value)]
    }

    override fun toString(): String =
        when (this) {
            Any   -> "RX"
            Read  -> "R"
            Write -> "X"
            None  -> "U"
        }
}
