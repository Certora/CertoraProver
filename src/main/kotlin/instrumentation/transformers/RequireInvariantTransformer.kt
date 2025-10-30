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

import analysis.maybeAnnotation
import config.Config
import scene.IScene
import vc.data.*
import vc.data.TACMeta.CVL_ASSUME_INVARIANT_TARGET
import vc.data.TACMeta.CVL_ASSUME_INVARIANT_CMD_END
import vc.data.TACMeta.CVL_ASSUME_INVARIANT_CMD_START


/**
 * When in mode [Config.RequireInvariantsPreRuleSemantics], this transformer moves all relevant code parts of requireInvariant commands
 * globally to their new location, indicated by the annotation [CVL_ASSUME_INVARIANT_TARGET]. Please be aware that this pass
 * works on the control flow graph directly.
 *
 * For every pair of matching annotation commands with meta [CVL_ASSUME_INVARIANT_CMD_START] and [CVL_ASSUME_INVARIANT_CMD_END], it
 * will move the code in between these commands to [CVL_ASSUME_INVARIANT_TARGET]. This is done by redirecting the control-flow
 * via [moveCommandsBetweenTo] of patching program which splits the control flow after all commands and redirect their
 * CFG successors.
 *
 * Example:
 * If we have before this transformation, we'll have a CFG
 *
 *
 *              CVL_ASSUME_INVARIANT_TARGET
 *              /                   \
 *
 *      some code A                 CVL_ASSUME_INVARIANT_CMD_START
 *                                      requireInvariant myInv
 *                                  CVL_ASSUME_INVARIANT_CMD_END
 *              \                    /
 *                     some code B
 *
 *
 * after transformation, the CFG will look like
 *
 *              CVL_ASSUME_INVARIANT_TARGET
 *                          |
 *              CVL_ASSUME_INVARIANT_CMD_START
 *                  requireInvariant myInv
 *              CVL_ASSUME_INVARIANT_CMD_END
 *              /                   \
 *      some code A                 EMPTY_BLOCK
 *              \                    /
 *                     some code B
 *
 *
 * For details on actually compilation, see also [spec.CVLCompiler.compileAssumeInvariantGlobally]
 *
 * This pass explicitly runs after the [HookInliner] as hook inlining can also add freshly compiled requireInvariant commands.
 */
class RequireInvariantTransformer(val scene: IScene) : CodeTransformer() {

    override fun transform(ast: CoreTACProgram): CoreTACProgram {
        if (ast.isEmptyCode()) {
            return ast
        }

        if (!Config.RequireInvariantsPreRuleSemantics.get()) {
            return ast;
        }

        var lastProg = ast;

        while (true) {
            val cache = lastProg.analysisCache
            val toMoveStart = cache.graph.commands.filter { cmd -> cmd.maybeAnnotation(CVL_ASSUME_INVARIANT_CMD_START) != null }
                .map { it to it.maybeAnnotation(CVL_ASSUME_INVARIANT_CMD_START)!!}
                // We sort by the id here so that TAC is created deterministically to ensure we create the same TAC on subsequent runs.
                .sortedByDescending{ it.second.id }
                .firstOrNull()

            if (toMoveStart != null) {
                val toMoveStartCmd = toMoveStart.first
                val invariantStartCmdAnnotation = toMoveStart.second
                val target = cache.graph.commands.filter { it.maybeAnnotation(CVL_ASSUME_INVARIANT_TARGET) }.singleOrNull()
                    ?: error("Found none or more than one annotation to which the require invariant ${invariantStartCmdAnnotation.name} should be moved.")
                val toMoveEnd = cache.graph.commands.filter { cmd -> cmd.maybeAnnotation(CVL_ASSUME_INVARIANT_CMD_END) != null }.firstOrNull { it.cmd.maybeAnnotation(CVL_ASSUME_INVARIANT_CMD_END)!!.id == invariantStartCmdAnnotation.id }
                    ?: error("Failed to find end command")

                lastProg = lastProg.moveCommandsBetweenTo(toMoveStartCmd, toMoveEnd, target).patching {
                    // Deleting the start pointer to ensure the loop terminates
                    it.delete(toMoveStartCmd.ptr)
                }

            } else {
                return lastProg
            }
        }
    }

}
