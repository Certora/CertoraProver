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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier.equivalence.tracing

import utils.*
import vc.data.TACSymbol
import vc.data.TransformableVarEntityWithSupport

/**
 * Basic record for recording states for instrumentation.
 * [label] is some description of the instrumentation that generated this record; [kv] is a list
 * of variables and some label that describes their role in the instrumentation.
 */
data class InstrumentationRecord(
    val label: String,
    val kv: List<Pair<String, TACSymbol.Var>>
) : TransformableVarEntityWithSupport<InstrumentationRecord>
{
    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InstrumentationRecord {
        return InstrumentationRecord(
            label,
            kv.map { ent ->
                ent.mapSecond(f)
            }
        )
    }

    override val support: Set<TACSymbol.Var>
        get() = kv.mapToSet { it.second }

}
