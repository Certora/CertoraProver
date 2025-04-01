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

package spec.cvlast.transformations

import datastructures.stdcollections.*
import spec.cvlast.*
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import utils.CollectingResult
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.map

/**
 * Replaces each [CVLExp.ApplyExp.Definition] expression with the
 * inlined body ([CVLDefinition.body]) of the applied definition ([CVLExp.ApplyExp.Definition.id]).
 *
 * NB: this transformer is and should remain idempotent
 */
class DefinitionsInliner(val symbolTable: CVLSymbolTable) : CVLAstTransformer<Nothing>(
    cmdTransformer = CVLCmdTransformer(
        expTransformer = object : CVLExpTransformer<Nothing> {
            override fun definition(exp: CVLExp.ApplyExp.Definition): CollectingResult<CVLExp, Nothing> {
                val definitionInfo = symbolTable.lookUpWithMethodIdWithCallContext(
                    exp.methodIdWithCallContext,
                    exp.tag.getCVLScope()
                )
                check(definitionInfo != null && definitionInfo.symbolValue is CVLDefinition) {
                    "type checking should " +
                        "ensure a definition apply refers to a symbol registered in the symbol table as a definition"
                }
                val definition = definitionInfo.symbolValue as CVLDefinition
                check(definition.params.size == exp.args.size) {
                    "Type checking should ensure argument list " +
                        "length and parameter list length match in $exp"
                }
                // substitute old/new's
                val substitutions = definition.params.map { it.id }.zip(exp.args).toMap()
                return SubstitutorExp(substitutions).expr(definition.body).bind { body ->
                    super.expr(body.updateTag(body.tag.copy(range = exp.getRangeOrEmpty())))
                }
            }
        }
    )
) {
    override fun ast(ast: CVLAst): CollectingResult<CVLAst, Nothing> {
        return super.ast(ast).map {
            // That's it! no more need for definitions :)
            it.copy(definitions = listOf())
        }
    }
}
