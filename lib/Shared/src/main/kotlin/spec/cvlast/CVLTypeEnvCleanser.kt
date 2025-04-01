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

package spec.cvlast

import spec.cvlast.transformer.CVLExpTransformer
import utils.CollectingResult
import utils.CollectingResult.Companion.lift
import utils.Range
import utils.extend

@Suppress("unused")
class CVLTypeEnvCleanser(val symbolTable: CVLSymbolTable): CVLExpTransformer<Nothing> {
    val substitutions = mutableMapOf<String, CVLExp.VariableExp>()

    override fun quant(exp: CVLExp.QuantifierExp): CollectingResult<CVLExp, Nothing> {
        val newQVar = symbolTable.freshQuantifierSym(exp)
        val isBeingInTheStateOfHavingToModifyTheStack = newQVar.qVarName != exp.qVarName
        val newExp = { super.quant(newQVar) }
        return if (isBeingInTheStateOfHavingToModifyTheStack) {
            substitutions.extend(exp.qVarName, CVLExp.VariableExp(newQVar.qVarName, tag = CVLExpTag( // TODO(jtoman): this is right?
                range = Range.Empty(),
                type = exp.qVarType,
                scope = exp.tag.scope
            )), newExp)
        } else {
            newExp()
        }
    }

    override fun variable(exp: CVLExp.VariableExp): CollectingResult<CVLExp, Nothing> = (substitutions[exp.id]?: exp).lift()
}
