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

/**
 * This class is used to calculate all the ghost functions that a [CVLExp] modifies and reads
 *
 * @param symbolTable the table of defined symbols (especially ghost functions) that the user is interested in
 * @param range the [utils.Range] that defines the scope of interest within the symbolTable
 *
 * @property modifiedGhosts INVARIANT: empty at method entry and exit (except [CVLExpTransformer] methods)
 * @property readGhosts INVARIANT: empty at method entry and exit (except [CVLExpTransformer] methods)
 */
class CVLExpModifiesReads(val symbolTable: CVLSymbolTable, val range: Range, val scope: CVLScope) : CVLExpTransformer<Nothing> {
    companion object {
        /**
         * @requires any [CVLExp.ApplyExp.Ghost] in exp must be defined as a [CVLGhostFunction] inside symbolTable
         * @return a pair where the first element is all ghosts modified inside exp, and the second element is all ghosts
         *         read inside exp
         */
        fun getModifiedAndRead(symbolTable: CVLSymbolTable, range: Range, scope: CVLScope, exp: CVLExp) =
            CVLExpModifiesReads(symbolTable, range, scope).getModifiedAndRead(exp)
    }

    // keep these EMPTY after every non-override (i.e. [CVLExpTransformer]) function
    private val modifiedGhosts = mutableSetOf<CVLGhostDeclaration.Function>()
    private val readGhosts = mutableSetOf<CVLGhostDeclaration.Function>()

    override fun ghost(exp: CVLExp.ApplyExp.Ghost): CollectingResult<CVLExp.ApplyExp.Ghost, Nothing> {
        // it should do not to have to worry about type environments because, even if this is havoc'd inside
        // a havoc-assuming, the info we want will still be at the symbol table level
        val ghostInfo = symbolTable.lookUpWithMethodIdWithCallContext(exp.methodIdWithCallContext, scope) // :[]
        val ghost = ghostInfo?.symbolValue
        check(ghost != null && ghost is CVLGhostDeclaration.Function)
        if  (exp.twoStateIndex == TwoStateIndex.NEITHER) {
            readGhosts.add(ghost)
        } else {
            modifiedGhosts.add(ghost)
        }
        return exp.lift()
    }

    private fun getModifiedAndRead(exp: CVLExp): Pair<Set<CVLGhostDeclaration.Function>, Set<CVLGhostDeclaration.Function>> {
        modifiedGhosts.clear()
        readGhosts.clear()
        expr(exp)
        val read = readGhosts.toSet()
        val modified = modifiedGhosts.toSet()
        modifiedGhosts.clear()
        readGhosts.clear()
        return modified to read
    }
}
