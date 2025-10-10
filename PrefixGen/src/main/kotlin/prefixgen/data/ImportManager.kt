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

package prefixgen.data

import bridge.types.SolidityTypeDescription
import datastructures.stdcollections.*
import utils.AmbiSerializable
import utils.KSerializable
import utils.update

/**
 * Records the file names that need to be imported
 * for some fuzz test, and the identifiers from that file.
 *
 * [imports] maps a file path to the identifiers imported from said file;
 * this is updated by [importType] (using black magic string ops) and pretty printed
 * via [compile]
 */
@KSerializable
data class ImportManager(
    val imports: Map<String, Set<String>>
) : AmbiSerializable {
    fun importType(
        ud: SolidityTypeDescription.UserDefined
    ) : ImportManager {
        val containingContract = ud.containingContract ?: throw IllegalStateException("nope")

        /**
         * This is a *super* unfortunate hack. The User Defined type doesn't tell us
         * the path to the file that defines that type *except* it has that information in the
         * canonicalId. For the moment, to avoid a BC breaking change in the CLI (gulp)
         * we just parse the canonical ID string for this information (booooo).
         */
        @Suppress("ForbiddenMethodCall")
        val containingFile = ud.canonicalId.split("|")[0]
        return this.copy(
            imports = imports.update(containingFile, setOf()) { curr ->
                curr + containingContract
            }
        )
    }

    fun compile() : String {
        return imports.map { (file, ids) ->
            val idString = "{ ${ids.joinToString(",")} }"
            "import $idString from \"$file\";"
        }.joinToString("\n")
    }
}
