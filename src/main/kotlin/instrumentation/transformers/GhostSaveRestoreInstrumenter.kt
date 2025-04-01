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

import analysis.narrow
import tac.Tag
import vc.data.*
import datastructures.stdcollections.*
import utils.*

/**
 * Converts GHOST_SAVE and GHOST_RESTORE metas to commands that do exactly that:
 * - Save the current values of ghosts to separate variables as determined by the saved checkpoint name
 * - Restores the saved values of ghosts based on the checkpoint name and replaces current ghost values
 */
object GhostSaveRestoreInstrumenter {
    private fun ghostCheckpointed(varCheckpoint: VariableCheckpoint, ghostName: String) =
        "${varCheckpoint.seriesName}${varCheckpoint.id}$ghostName"

    fun getGhostNameAt(which: TACSymbol.Var, ghostName: String): String {
        require(which.tag == Tag.BlockchainState) {
            "Ghost names at a checkpoint $which require a blockchain state tag, got ${which.tag}"
        }
        return ghostCheckpointed(
            VariableCheckpoint.ghostCheckpointByVar(which), ghostName
        )
    }

    /**
     * If possible, try to save the meta of a ghost variable (not a function) so that the default variable
     * initializer picks up this meta and generates a havoc of a variable with the correct meta, so THAT meta
     * is picked up by the DSA and is propagated to a conditional write, so the SKEY annotation doesn't freak out.
     */
    private fun ghostToVar(ghostFun: FunctionInScope.UF) : TACSymbol.Var {
        return TACSymbol.Var(ghostFun.name, ghostFun.asTag).letIf(ghostFun.cvlParamTypes.isEmpty()) { v ->
            v.withMeta { m ->
                m + (TACMeta.CVL_TYPE to ghostFun.cvlResultType) + (TACMeta.CVL_VAR to true) + (TACMeta.CVL_DISPLAY_NAME to ghostFun.declarationName)
            }
        }
    }

    fun transform(code: CoreTACProgram): CoreTACProgram {
        val g = code.analysisCache.graph
        // We're either smart or very lucky. What about symbols? Even symbols are modeled as nullary uninterpreted functions
        val ghosts = code.symbolTable.getStateGhosts()
        val p = code.toPatchingProgram()
        g.commands.filter { lcmd ->
            lcmd.cmd is TACCmd.Simple.AnnotationCmd
                    && (lcmd.cmd.annot.k == TACMeta.GHOST_SAVE || lcmd.cmd.annot.k == TACMeta.GHOST_RESTORE || lcmd.cmd.annot.k == TACMeta.GHOST_HAVOC)
        }.map { lcmd -> lcmd.narrow<TACCmd.Simple.AnnotationCmd>() }
            .forEach { ghostAnnotation ->
                val type = ghostAnnotation.cmd.annot.k
                if (type == TACMeta.GHOST_SAVE) {
                    val saveTo = ghostAnnotation.cmd.annot.v as VariableCheckpoint
                    val replacement = ghosts.map { ghostFunc ->
                        val tag = ghostFunc.asTag
                        val src = ghostToVar(ghostFunc)
                        val trg = TACSymbol.Var(ghostCheckpointed(saveTo, ghostFunc.name), tag)
                        if (tag is Tag.GhostMap) {
                            p.addUf(ghostFunc.copy(name = trg.namePrefix, declarationName = trg.namePrefix))
                        } else {
                            p.addVarDecl(src)
                            p.addVarDecl(trg)
                        }
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            trg,
                            src
                        )
                    }
                    p.replaceCommand(ghostAnnotation.ptr, replacement)
                } else if (type == TACMeta.GHOST_RESTORE) {
                    val restoreFrom = ghostAnnotation.cmd.annot.v as VariableCheckpoint
                    val replacement = ghosts.map { ghostFunc ->
                        val tag = ghostFunc.asTag
                        val trg = ghostToVar(ghostFunc)
                        val src = TACSymbol.Var(ghostCheckpointed(restoreFrom, ghostFunc.name), tag)
                        if (tag is Tag.GhostMap) {
                            p.addUf(ghostFunc.copy(name = src.namePrefix))
                        } else {
                            p.addVarDecl(src)
                            p.addVarDecl(trg)
                        }
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            trg,
                            src
                        )
                    }
                    p.replaceCommand(ghostAnnotation.ptr, replacement)
                } else if (type == TACMeta.GHOST_HAVOC) {
                    val replacement = mutableListOf<TACCmd.Simple>()
                    // need to havoc and assume according to type properly, and add a snippet
                    ghosts.mapTo(replacement) { ghostObj ->
                        // apparently ghost accesses are properly axiomatized later in the pipeline in LExp layer
                        val trg = ghostToVar(ghostObj)
                        p.addVarDecl(trg)
                        TACCmd.Simple.AssigningCmd.AssignHavocCmd(trg)
                    }
                    if (ghosts.isNotEmpty()) {
                        // don't add a snippet if there were no ghosts updated
                        replacement.add(SnippetCmd.CVLSnippetCmd.HavocAllGhostsSnippet.toAnnotation(code.destructiveOptimizations))
                    }
                    // will nop if there are no ghosts
                    p.replaceCommand(ghostAnnotation.ptr, replacement)
                } else {
                    throw IllegalStateException("Expected a ghost save, restore, or havoc; got type $type of $ghostAnnotation")
                }
            }
        return p.toCode(code)
    }
}
