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

package prefixgen

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import prefixgen.selection.*

/**
 * Abstract base class for any of the standalone generation commands.
 * Includes the [targetContract] parameters, and the "standard"
 * num samples and sampling strategy options.
 */
abstract class PrefixGenCommand(name: String, val help: String) : CliktCommand(name = name) {
    override fun help(context: Context): String = help

    protected val targetContract by argument(
        help = "The name of the contract type for which to generate sequences"
    )

    val samples by option(
        help = "The number of sequences to generate",
    ).int().default(100)

    private val commonSelectors by CommonSelectorOptions()
    private val commonRandom by CommonRandomOptions()

    val samplingStrategy by option().groupChoice(
        "enum" to EnumerateCLI(commonSelectors),
        "uniform" to Uniform(commonSelectors, commonRandom),
        "weighted" to Weighted(commonSelectors, commonRandom)
    ).defaultByName("enum")
}
