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

package spec

import com.certora.collect.*
import utils.Range
import spec.cvlast.CVLScope
import utils.AmbiSerializable
import utils.KSerializable

@KSerializable
@Treapable
sealed class CVLDefinitionSite : AmbiSerializable {
    abstract val range: Range?

    @KSerializable
    data class Function(override val range: Range? = null) : CVLDefinitionSite()

    @KSerializable
    data class Rule(override val range: Range? = null) : CVLDefinitionSite()

    companion object {
        fun fromScope(scope: CVLScope, range: Range) : CVLDefinitionSite? {
            return if (scope.enclosingRule() != null) {
                Rule(range)
            } else if (scope.enclosingInvariant() != null) {
                Rule(range)
            } else if (scope.enclosingCVLFunction() != null) {
                Function(range)
            } else {
                null
            }
        }
    }
}

