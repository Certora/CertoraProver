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

import log.*
import spec.CVLWarningLogger
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult

object CleanEmptyHooks: CVLAstTransformer<CVLError>(CVLCmdTransformer(CVLExpTransformer.copyTransformer())) {
    fun check(ast: CVLAst): CollectingResult<CVLAst, CVLError> {
        return this.ast(ast)
    }

    override fun hooks(hooks: List<CVLHook>): CollectingResult<List<CVLHook>, CVLError> {
        val (filteredHooks, emptyHooks) = hooks.partition { it.block.isNotEmpty() }
        emptyHooks.forEach {
            Logger.regression { "Ignoring empty hook @ ${it.range}" }
            CVLWarningLogger.syntaxWarning("Ignoring empty hook", it.range)
        }
        return super.hooks(filteredHooks)
    }
}
