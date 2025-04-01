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
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.SumSignedAndUnsignedNotSupported
import utils.*
import utils.ErrorCollector.Companion.collectingErrors

object CVLGhostSumGenerator {
    /**
     * @return An updated [CVLAst] that contains all the [CVLGhostDeclaration.Sum]s that are implied by [CVLExp.SumExp]s in
     * the [ast].
     */
    fun generate(ast: CVLAst): CollectingResult<CVLAst, CVLError> = collectingErrors {
        val sumGhosts = mutableSetOf<CVLGhostDeclaration.Sum>()
        object : CVLAstTransformer<Nothing>(
            CVLCmdTransformer(
                object : CVLExpTransformer<Nothing> {
                    override fun sum(exp: CVLExp.SumExp): CollectingResult<CVLExp, Nothing> {
                        val baseGhost = ast.ghosts.find { it.id == exp.baseVar.id } as? CVLGhostDeclaration.Variable ?: error("expected to find the base ghost")
                        sumGhosts.add(CVLGhostDeclaration.Sum(
                            baseGhost,
                            exp.sumGhostName,
                            exp.nonSummedIndices,
                            exp.isUnsigned,
                            baseGhost.scope
                        ))
                        return super.sum(exp)
                    }
                }
            )
        ) {}.ast(ast)

        sumGhosts.groupBy { it.origGhost.id }.forEachEntry { (origGhostName, sumGhosts) ->
            if (!sumGhosts.allSame { it.isUnsigned }) {
                collectError(SumSignedAndUnsignedNotSupported(origGhostName, sumGhosts.first().origGhost.range))
            }
        }

        ast.copy(ghosts = ast.ghosts + sumGhosts)
    }
}
