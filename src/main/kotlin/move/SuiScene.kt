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

package move

import java.math.BigInteger
import java.nio.file.*
import utils.*

class SuiScene(
    modulePath: Path,
    val packageSummaryPath: Path?
) : MoveScene(modulePath) {
    val packageSummaries: SuiPackageSummaries? by lazy { packageSummaryPath?.let { SuiPackageSummaries(it) } }

    /*
        Older Sui Move compilers put the address aliases in BuildInfo.yaml, but as of ~v1.63.1, they are no longer
        emitted there.  So, when running on Sui packages, we prefer to get these from the package summaries, if
        available (which has worked since ~v1.51.5), and fall back to the build info otherwise.
     */
    override val addressAliases: Map<BigInteger, List<String>> get() =
        packageSummaries?.addressAliases?.entries?.groupBy({ it.value }, { it.key }) ?: super.addressAliases
}
