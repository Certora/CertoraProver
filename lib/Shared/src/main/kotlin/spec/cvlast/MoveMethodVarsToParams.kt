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

import datastructures.stdcollections.*
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.rules.CVLSingleRule
import utils.CollectingResult
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.safeForce

/**
 * "Promotes" all method variables declared within the rule to be method parameters.
 * The advantage is that from this point forward we no longer need to consider the case of method variables.
 * For example - this case doesn't need to be handled in the [CVLCompiler].
 * Another advantage is that if we want to add internal filters to parametric methods, we will now be able to handle
 * filters for method variables in a transparent way.
 */
class MoveMethodVarsToParams : CVLAstTransformer<Nothing>(
    cmdTransformer = CVLCmdTransformer(expTransformer = CVLExpTransformer.copyTransformer())
) {
    override fun ast(ast: CVLAst): CollectingResult<CVLAst, Nothing> {
        // Only manipulating rules, so no need traverse the rest of the ast
        return ast.copy(rules = ast.rules.map { rule(it).safeForce() }).lift()
    }

    override fun rule(rule: CVLSingleRule): CollectingResult<CVLSingleRule, Nothing> {
        return rule.block.partition { cmd ->
            cmd is CVLCmd.Simple.Declaration && cmd.cvlType == EVMBuiltinTypes.method
        }.let { (methodDecls, rest) ->
            rule.copy(
                block = rest,
                params = rule.params + methodDecls.map { CVLParam(EVMBuiltinTypes.method, (it as CVLCmd.Simple.Declaration).id, it.range) }
            )
        }.lift()
    }
}
