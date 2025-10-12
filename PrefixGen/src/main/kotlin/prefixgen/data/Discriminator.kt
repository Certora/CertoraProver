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

import com.certora.collect.*
import utils.AmbiSerializable
import utils.KSerializable

/**
 * Represents the possible aliasing options for a
 * [prefixgen.data.SimpleSolidityAst.BindingStmt].
 * [discriminator] is the variable that determines which alias
 * is selected, or the fresh value, if [must] is false.
 *
 * Explicitly, [discriminator] is bound to be between 0 and [aliases].length (inclusive).
 * If [must] is true, then the lower end point is 1 instead.
 * Suppose we are binding `ty someVar`
 * If [discriminator] is 0, then the *fresh* input value is chosen, i.e.,
 * `ty someVar` is bound to `inputState.inputVar` where `inputVar` is the
 * [SimpleSolidityAst.BindInputChoice.inputId].
 *
 * Otherwise, `someVar` is bound to the [Alias] at index [discriminator] - 1,
 * using the conversion code if necessary.
 */
@KSerializable
@Treapable
data class Discriminator(
    @SoliditySourceIdentifier
    val discriminator: String,
    val aliases: List<Alias>,
    val must: Boolean
) : AmbiSerializable {

    /**
     * The identifier which indicates the alias (or fresh value) chosen for this aliasing decision.
     * See [prefixgen.Compiler] for why this *isn't* just the value of [discriminator].
     */
    @SoliditySourceIdentifier
    val selectedId = "${discriminator}_Selected"

    fun getChosenAliasIndex(valuation: Map<String, SimpleValue>, aliasData: Map<Identifier, Discriminator>): Int {
        val rawValue = (valuation[this.discriminator]!! as SimpleValue.Word).toBigInt().mod(
            (aliases.size + 1).toBigInteger()
        ).intValueExact()
        if(rawValue == 0) {
            check(!must) {
                "Must alias points to fresh identifier"
            }
            return 0
        }
        val chosenAliasInd = aliases[rawValue - 1]
        val alias = aliasData[chosenAliasInd.id] ?: return rawValue
        return if(alias.getChosenAliasIndex(valuation, aliasData) != 0) {
            check(!must) {
                "Must alias points to fresh identifier"
            }
            0
        } else {
            rawValue
        }
    }

}
