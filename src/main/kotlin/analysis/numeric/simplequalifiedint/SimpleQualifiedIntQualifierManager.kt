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

package analysis.numeric.simplequalifiedint

import analysis.CmdPointer
import analysis.dataflow.IMustEqualsAnalysis
import analysis.numeric.QualifierManager
import analysis.numeric.SimpleIntQualifier
import com.certora.collect.minus
import vc.data.TACSymbol

open class SimpleQualifiedIntQualifierManager(me: IMustEqualsAnalysis? = null) :
    QualifierManager<SimpleIntQualifier, SimpleQualifiedInt, SimpleQualifiedIntState>(me = me) {
    override fun mapValues(s: SimpleQualifiedIntState, mapper: (TACSymbol.Var, SimpleQualifiedInt) -> SimpleQualifiedInt): SimpleQualifiedIntState {
        return s.copy(s.s.updateValues(transform = mapper))
    }

    override fun assignVar(toStep: SimpleQualifiedIntState, lhs: TACSymbol.Var, toWrite: SimpleQualifiedInt, where: CmdPointer): SimpleQualifiedIntState {
        if(toWrite == SimpleQualifiedInt.nondet) {
            return toStep.copy(toStep.s - lhs)
        }
        return toStep.copy(toStep.s.put(lhs, toWrite))
    }
}
