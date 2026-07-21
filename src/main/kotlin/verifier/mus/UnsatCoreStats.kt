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

package verifier.mus

import com.certora.collect.*
import datastructures.stdcollections.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import utils.SourcePosition
import utils.flatMapToSet
import kotlin.time.Duration


@Serializable
data class UnsatCoresStats(
    val runtime: Duration,
    val unsatCores: Set<SingleUnsatCoreStats>,
    val softConstraintsFromSpec: Set<UnsatCoreCmdFromSpec>,
    val softConstraintsFromSol: Set<UnsatCoreCmdFromSol>,
    val callIds: Set<String>
) {
    private val usedSolCmds get() = unsatCores.flatMapToSet { it.cmdsFromSol }
    private val unusedSolCmds get() = usedSolCmds.let{ used ->
        softConstraintsFromSol.filterTo(mutableSetOf()) { it !in used }
    }
    private val usedSpecCmds get() = unsatCores.flatMapToSet { it.cmdsFromSpec }
    private val unusedSpecCmds get() = usedSpecCmds.let { used ->
        softConstraintsFromSpec.filterTo(mutableSetOf()) { it !in used }
    }
    val usedCmds get(): Set<UnsatCoreCmd> = usedSolCmds + usedSpecCmds
    val unusedCmds get(): Set<UnsatCoreCmd> = unusedSolCmds + unusedSpecCmds
}

@Treapable
interface UnsatCoreCmd {
    val cmd: String
    val file: String
    val line: Int
}

@Serializable
data class UnsatCoreCmdFromSpec(
    override val cmd: String,
    override val file: String,
    val start: SourcePosition,
    val end: SourcePosition,
    override val line: Int, //currently storing just [start.line]
) : UnsatCoreCmd

@Serializable
data class UnsatCoreCmdFromSol(
    override val cmd: String,
    override val file: String,
    override val line: Int,
) : UnsatCoreCmd

@Serializable
data class SingleUnsatCoreStats(
    val runtime: Duration? = null,
    val cmdsFromSpec: Set<UnsatCoreCmdFromSpec>,
    val cmdsFromSol: Set<UnsatCoreCmdFromSol>,
    val callIdsTouchingUnsatCore: Set<String>,
    val missingCmdsFromSpec: Set<UnsatCoreCmdFromSpec>,
    val missingCmdsFromSol: Set<UnsatCoreCmdFromSol>,
    val callIdsNotTouchingUnsatCore: Set<String>,
    val unsatCoreTxtFile: String? = null,
)

@JvmInline
value class UnsatCoresJavaSerializerWrapper(private val toSerialize: UnsatCoresStats) : java.io.Serializable {
    override fun toString(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(toSerialize)
    }
}
