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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.icfg

import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.ip.*
import analysis.maybeAnnotation
import vc.data.TACCmd

/**
 * Abstract class for replacing internal function bodies from solidity with summaries.
 *
 * [K] is the type of "summary selection", that is, a key which identifies which
 * summary to apply, [S] is the type of the selected summary.
 *
 * Instantiates [GenericInternalSummarizer] with the abstractions in [SolidityInternalFunctions].
 */
abstract class InternalSummarizerSolidity<K, S> : GenericInternalSummarizer<
    K,
    S,
    InternalFuncStartAnnotation,
    InternalFuncExitAnnotation,
    List<InternalFuncArg>,
    List<SolidityInternalFunctions.InternalRetShape>,
    List<InternalFuncRet>>(), SolidityInternalFunctions {

    companion object {
        fun getFunction(
            exitFinder: InternalFunctionExitFinder,
            start: LTACCmdView<TACCmd.Simple.AnnotationCmd>
        ): InternalFunction {
            val funcId = (start.cmd.annot.v as InternalFuncStartAnnotation).id
            return InternalFunction(start, exitFinder.getExits(funcId, start.ptr))
        }

        fun LTACCmd.toFuncStart() = this.maybeAnnotation(INTERNAL_FUNC_START)
        fun LTACCmdView<TACCmd.Simple.AnnotationCmd>.toFuncStart() = this.cmd.annot.v as? InternalFuncStartAnnotation
    }

}
