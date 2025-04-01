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

import utils.Range

/**
 * @param range right now it seems ok, to have one location for the type env, since it is only used for expressions, if we
 *    need more detail, we might extend the [CVLTypeEnvironment.Entry] class.
 */
data class CVLTypeEnvironment(val range: Range, val scope: CVLScope, val env: List<Entry>) {

    class Entry(val name: String, val type: CVLSymbolTable.SymbolInfo)

    companion object {
        fun empty(range: Range, scope: CVLScope): CVLTypeEnvironment = CVLTypeEnvironment(range, scope, listOf())
    }

    fun pushParam(param: CVLParam) = push(param.id, CVLSymbolTable.SymbolInfo(param, param.range))

    fun pushParams(params: List<CVLParam>) = push(params.map { it.id to CVLSymbolTable.SymbolInfo(it, it.range) })

    fun pushTwoStateGhost(ghost: CVLGhostWithAxiomsAndOldCopy): CVLTypeEnvironment =
        when (ghost) {
            is CVLGhostDeclaration.Function -> {
                push(
                    ghost.functionReference.methodId,  // TODO: just using methodId here should be reconsidered (CERT-8094)
                    CVLSymbolTable.SymbolInfo.invoke(ghost, ghost.range, isTwoState = true)
                )
            }
            is CVLGhostDeclaration.Variable -> {
                push(
                    ghost.id,
                    CVLSymbolTable.SymbolInfo(ghost, ghost.range, isTwoState = true)
                )
            }
        }

    fun pushTwoStateVariable(variable: CVLExp.VariableExp) =
        push(variable.id, CVLSymbolTable.SymbolInfo(variable, variable.tag.range, isTwoState = true))

    private fun push(name: String, info: CVLSymbolTable.SymbolInfo) =
        CVLTypeEnvironment(range, scope, env + Entry(name, info))

    private fun push(entryInfo: List<Pair<String, CVLSymbolTable.SymbolInfo>>) =
        CVLTypeEnvironment(range, scope, env + entryInfo.map { Entry(it.first, it.second) })

    fun lookUp(id: String): CVLSymbolTable.SymbolInfo? {
        val matchingEntry = env.findLast { it.name == id }
        return matchingEntry?.type
    }
}

typealias WithEnv<T> = Pair<T, CVLTypeEnvironment>
