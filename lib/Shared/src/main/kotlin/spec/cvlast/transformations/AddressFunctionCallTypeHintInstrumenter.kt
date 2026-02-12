/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

import spec.cvlast.CVLCmd
import spec.cvlast.CVLExp
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import utils.CollectingResult
import utils.CollectingResult.Companion.lift
import datastructures.stdcollections.*

object AddressFunctionCallTypeHintInstrumenter : CVLAstTransformer<Nothing>(
    cmdTransformer = object : CVLCmdTransformer<Nothing>(
        expTransformer = object : CVLExpTransformer<Nothing> { }
    ) {
        override fun def(cmd: CVLCmd.Simple.Definition): CollectingResult<CVLCmd, Nothing> {
            return if(cmd.exp is CVLExp.UnresolvedApplyExp && cmd.exp.base != null) {
                cmd.copy(
                    exp = cmd.exp.updateTag(
                        cmd.exp.tag.copy(
                            annotation = CVLExp.UnresolvedApplyExp.ReturnTypeHint(cmd.idL)
                        )
                    )
                ).lift()
            } else {
                super.def(cmd)
            }
        }

        override fun applyCmd(cmd: CVLCmd.Simple.Apply): CollectingResult<CVLCmd, Nothing> {
            return if (cmd.exp is CVLExp.UnresolvedApplyExp && cmd.exp.base != null) {
                cmd.copy(
                    exp = cmd.exp.updateTag(
                        cmd.exp.tag.copy(
                            annotation = CVLExp.UnresolvedApplyExp.ReturnTypeHint(
                                // an apply command has no lhs variables, allowing all relevant
                                // functions to be called
                                emptyList()
                            )
                        )
                    ) as CVLExp.ApplicationExp
                ).lift()
            } else {
                super.applyCmd(cmd)
            }
        }
    }
)
