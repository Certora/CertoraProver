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
data class MoveModuleName(
    val address: BigInteger,
    val name: String
) : AmbiSerializable {
    @TestOnly
    constructor(address: Int, name: String) : this(address.toBigInteger(), name)

    override fun toString() = "0x${address.toString(16)}::${name}"
    fun toVarName() = "x${address.toString(16)}\$${name}"

    companion object {
        fun parse(name: String): Result<MoveModuleName> = Result.runCatching {
            @Suppress("ForbiddenMethodCall")
            val parts = name.split("::")
            require(parts.size == 2) { "Expected <address>::<module>" }
            val address = BigInteger(parts[0], 16)
            val moduleName = parts[1]
            MoveModuleName(address, moduleName)
        }
    }
}
