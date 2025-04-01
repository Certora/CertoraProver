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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)

package sbf.tac

import com.certora.collect.*
import datastructures.stdcollections.*
import utils.Range
import tac.MetaKey
import utils.AmbiSerializable
import utils.HasKSerializable
import vc.data.TACSymbol
import utils.KSerializable
import utils.SourcePosition
import vc.data.TransformableVarEntityWithSupport

val RULE_LOCATION = MetaKey<RuleLocationAnnotation>("sbf.rule.location")

/**
 * An annotation that is used to identify the source location of a rule.
 * Can be converted to a [Range.Range].
 *
 * @param filepath is the path to the file containing the rule.
 * @param lineNumber is the 1-based line number at which the rule starts.
 */
@KSerializable
@Treapable
data class RuleLocationAnnotation(
    val filepath: String,
    val lineNumber: UInt,
) : HasKSerializable, AmbiSerializable,
    TransformableVarEntityWithSupport<RuleLocationAnnotation> {

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): RuleLocationAnnotation {
        return this
    }

    override val support: Set<TACSymbol.Var>
        get() = setOf()

    /**
     * Returns the corresponding [Range.Range]. Since we do not have column information, we assume that the rule
     * starts at the first column. The end range is the next line.
     */
    fun toRange(): Range.Range {
        return Range.Range(
            filepath,
            SourcePosition(lineNumber - 1U, 0U),
            SourcePosition(lineNumber, 0U),
        )
    }
}
