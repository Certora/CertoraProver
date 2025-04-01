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

import algorithms.TopologicalOrderException
import algorithms.topologicalOrder
import datastructures.stdcollections.*
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.Range

class DefinitionDependencyChecker(val symbolTable: CVLSymbolTable): CVLAstTransformer<CVLError>(CVLCmdTransformer(CVLExpTransformer.copyTransformer())) {
    override fun defs(defs: List<CVLDefinition>): CollectingResult<List<CVLDefinition>, CVLError> {
        // This block does a few things:
        // 1: It calculates dependencies in the definition "call graph" and makes sure it is acyclic
        // 2: If this graph is acyclic, it builds reads and modifies sets for each definition bottom-up (using the topo
        //    sort) and makes sure there is no overlap between reads and modifies clauses--reads corresponds to one state
        //    use and modifies corresponds to two-state use
        // 3: In either case we typecheck the body (though if the topo sort fails, the program fails typechecking anyway)
        return try {
            val definitionDependencies = mutableMapOf<CVLDefinition, Set<CVLDefinition>>()
            val astDefinitionVersion = mutableMapOf<CVLDefinition, CVLDefinition>()
            defs.forEach { definition ->
                val defInSymbolTable = symbolTable.lookUpFunctionLikeSymbol(definition.id, definition.scope!!)?.symbolValue as CVLDefinition
                val deps = mutableSetOf<CVLDefinition>()
                val transformer = object : CVLExpTransformer<Nothing> {
                    override fun definition(exp: CVLExp.ApplyExp.Definition): CollectingResult<CVLExp, Nothing> {
                        val definitionInfo = symbolTable.lookUpWithMethodIdWithCallContext(exp.methodIdWithCallContext, definition.scope)
                        val dep = definitionInfo?.symbolValue
                        check(dep != null && dep is CVLDefinition)
                        deps.add(dep)
                        return exp.lift()
                    }
                }
                transformer.expr(definition.body)
                definitionDependencies[defInSymbolTable] = deps.toSet()
                astDefinitionVersion[defInSymbolTable] = definition
            }
            // this is where we could get an exception:
            val definitionTopoSort = topologicalOrder(definitionDependencies).reversed()
            val modifiesMap = mutableMapOf<CVLDefinition, Set<CVLGhostDeclaration.Function>>()
            val readsMap = mutableMapOf<CVLDefinition, Set<CVLGhostDeclaration.Function>>()
            definitionTopoSort
                    .reversed()
                    .map { defInSymbolTable ->
                        val definition = astDefinitionVersion[defInSymbolTable]!!
                        val (modified, read) =
                                CVLExpModifiesReads.getModifiedAndRead(symbolTable, definition.range, definition.scope!!, definition.body)
                        val (depsModified, depsRead) = (definitionDependencies[defInSymbolTable]!!)
                                .fold(setOf<CVLGhostDeclaration.Function>() to setOf<CVLGhostDeclaration.Function>())
                                { (modifiedAcc, readAcc), el ->
                                    val dep = symbolTable.lookUpFunctionLikeSymbol(el.id, el.scope!!)?.symbolValue as CVLDefinition
                                    // these are guaranteed to be set because the topo sort succeeded and we are
                                    // iterating in bottom-up order
                                    modifiedAcc + modifiesMap[dep]!! to readAcc + readsMap[dep]!!
                                }
                        val newModifies = modified + depsModified
                        val newReads = read + depsRead
                        modifiesMap[defInSymbolTable] = newModifies
                        readsMap[defInSymbolTable] = newReads
                        definition.copy(modifies = newModifies, reads = newReads).lift()
                    }.flatten()
        } catch (e: TopologicalOrderException) {
            // TODO: would a different topo sort allow us to infer where the cycle is?
            CVLError.General(
                Range.Empty(),
                "A circular dependency was found in a definition: this is not allowed. Topo sort error: ${e.msg}"
            ).asError()
        }
    }
}
