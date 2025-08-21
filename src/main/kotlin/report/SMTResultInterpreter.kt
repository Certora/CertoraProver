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

package report

import solver.SolverResult
import spec.cvlast.SpecType
import spec.rules.IRule

object SMTResultInterpreter {
    fun getResultString(res: SolverResult?, rule: IRule): String {
        val isSatisfyRule = rule.isSatisfyRule || rule.getAllSingleRules().let { singleRules ->
            singleRules.isNotEmpty() && singleRules.all { single ->
                single.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck
            }
        }
        return when (res) {
            null -> "No result"
            SolverResult.SAT -> if(isSatisfyRule) { "Not violated (sat)" } else { "Violated (sat)" }
            SolverResult.UNSAT -> if(isSatisfyRule) { "Violated (unsat)" } else { "Not violated (unsat)" }
            SolverResult.UNKNOWN -> "?"
            SolverResult.TIMEOUT -> "Timeout"
            SolverResult.SANITY_FAIL -> "Sanity check failed"
        }
    }
}
