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

import analysis.LTACCmdView
import analysis.numeric.AbstractAbstractInterpreter
import analysis.numeric.SimpleIntQualifier
import analysis.numeric.withModularUpperBounds
import vc.data.TACCmd
import vc.data.TACSymbol

abstract class SimpleQualifiedIntAbstractInterpreter<W>(val qualifierManager: SimpleQualifiedIntQualifierManager)
    : AbstractAbstractInterpreter<W, SimpleQualifiedIntState>() {
    override val pathSemantics = SimpleQualifiedIntPathSemantics(
        qualifierPropagator = IntervalQualifierPropagationComputation<W>()
            .withModularUpperBounds(
                extractModularUpperBound = { it as? SimpleIntQualifier.ModularUpperBound },
                extractMultipleOf = { (it as? SimpleIntQualifier.MultipleOf)?.factor },
                modularUpperBound = SimpleIntQualifier::ModularUpperBound
            ),
        qualifierManager = qualifierManager
    )

    override fun killLHS(
        lhs: TACSymbol.Var,
        s: SimpleQualifiedIntState,
        w: W,
        narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>
    ): SimpleQualifiedIntState {
        return qualifierManager.killLHS(lhs = lhs, lhsVal = s.s[lhs], where = narrow.ptr, s = s)
    }
}
