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

package analysis.numeric

import com.certora.collect.*
import utils.*
import vc.data.TACSymbol

/**
 * Basic interface for controlling saturation: given a variable, return the set of
 * variables that can be safely substituted for that variable. The structure of the set is left open,
 * it maybe be that the returned set is empty, e.g., when finding suitable replacements for
 * a variable killed by an assignment. As a convenience, Variable saturation is lifted to operate
 * on TACSymbol to return the singleton set on consts, and the underlying set otherwise.
 */
typealias VariableSaturation = (TACSymbol.Var) -> Set<TACSymbol.Var>
operator fun VariableSaturation.invoke(op1: TACSymbol) : Set<TACSymbol> =
        if(op1 is TACSymbol.Var) {
            this(op1)
        } else {
            setOf(op1)
        }

/**
 * A qualifier for variables. [Self] is the type returned after saturation. Usually this will be some subtype of [Qualifier]
 * itself, and is used to narrow the type returned from saturation.
 */
interface Qualifier<out Self> {
    /**
     * Does this qualifier relate the variable [v]
     */
    fun relates(v: TACSymbol.Var): Boolean

    fun relatesAny(vars: Collection<TACSymbol.Var>) = vars.any { relates(it) }

    /**
     * Saturate the information represented by this qualifier with the equalities represented by
     * [equivClasses]
     */
    fun saturateWith(equivClasses: VariableSaturation) : List<Self>
}

/**
 * A Qualifier whose transient saturations will always be qualifiers. That is, the type returned by saturation (i.e., [Q])
 * is guaranteed to support all qualifier operations, and its saturation results will also be a qualifier, etc.
 */
@Treapable
interface SelfQualifier<out Q: SelfQualifier<Q>> : Qualifier<Q>
