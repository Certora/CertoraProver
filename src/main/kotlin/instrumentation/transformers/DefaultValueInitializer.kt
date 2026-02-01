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

package instrumentation.transformers

import analysis.forwardVolatileDagDataFlow
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.Tag
import vc.data.CoreTACProgram
import vc.data.TACCmd.Simple.AssigningCmd.AssignHavocCmd
import vc.data.TACSymbol
import vc.data.rootBlock

/**
 * Adds a havoc command at the beginning of the root block for every variable that is used somewhere that it may
 * be uninitialized.
 *
 * This includes cases such as:
 * ```
 *    block0:
 *      jump to block1 or block2
 *
 *    block1:
 *      x := something
 *      jump to block3
 *
 *    block2:
 *      jump to block3
 *
 *    block3:
 *      use x on the rhs.
 * ```
 *
 * We consider any appearance of a variable as a usage (even if it appears only in some meta or annotation).
 * Variables of type `Tag.CVLArray` shouldn't appear at this point in the program (and adding a havoc statement for
 * them crashes the tool), but they do, for some reason appear in some metas. We therefore ignore such variables.
 */
object DefaultValueInitializer {

    fun initVarsAtRoot(code: CoreTACProgram): CoreTACProgram {
        val g = code.analysisCache.graph
        val newVars = treapSetBuilderOf<TACSymbol.Var>()

        forwardVolatileDagDataFlow<TreapSet<TACSymbol.Var>>(code) { b, predSets ->
            var newSet = predSets.reduceOrNull { x, y -> x intersect y } ?: treapSetOf()
            g.lcmdSequence(b).forEach { (_, cmd) ->

                newVars += (cmd.getFreeVarsOfRhsExtended().toTreapSet() - newSet).removeAll { it.tag is Tag.CVLArray }
                cmd.getLhs()?.let {
                    newSet += it
                }
            }
            newSet
        }

        return code.patching { patcher ->
            patcher.addBefore(
                code.rootBlock.commands.first().ptr,
                newVars.map(::AssignHavocCmd)
            )
            patcher.addVarDecls(newVars)
        }
    }

}
