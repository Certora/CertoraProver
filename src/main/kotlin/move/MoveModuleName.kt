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

@file:kotlinx.serialization.UseSerializers(BigIntegerSerializer::class)

package move

import com.certora.collect.*
import java.math.BigInteger
import org.jetbrains.annotations.TestOnly
import utils.*

/**
    Identifies a Move module by its address and name.
 */
@Treapable
@KSerializable
class MoveModuleName private constructor(
    val address: BigInteger,
    private val addressAlias: String?,
    val name: String
) : AmbiSerializable {
    @TestOnly
    constructor(address: Int, name: String) : this(address.toBigInteger(), null, name)

    constructor(scene: MoveScene, address: BigInteger, name: String)
        : this(address, scene.maybeAlias(address), name)

    override fun hashCode() = hash { it + address + name }

    override fun equals(other: Any?) = when {
        other !is MoveModuleName -> false
        other.address != this.address -> false
        other.name != this.name -> false
        other.addressAlias != this.addressAlias -> error("Inconsistent address aliases: $this vs $other")
        else -> true
    }

    override fun toString() = if (addressAlias != null) {
        "$addressAlias::$name"
    } else {
        "0x${address.toString(16)}::${name}"
    }

    fun toVarName() = if (addressAlias != null) {
        "$addressAlias\$$name"
    } else {
        "x${address.toString(16)}\$${name}"
    }
}
