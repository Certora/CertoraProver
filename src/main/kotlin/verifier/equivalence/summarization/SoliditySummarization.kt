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

package verifier.equivalence.summarization

import analysis.CommandWithRequiredDecls
import analysis.icfg.SolidityInternalFunctions
import analysis.ip.InternalFuncArg
import analysis.ip.InternalFuncExitAnnotation
import analysis.ip.InternalFuncRet
import analysis.ip.InternalFuncStartAnnotation
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.Tag
import utils.*
import vc.data.TACCmd
import vc.data.TACSymbol
import vc.data.TXF
import datastructures.stdcollections.*

object SoliditySummarization : SharedPureSummarization.SummarizationImpl<InternalFuncStartAnnotation,
    InternalFuncExitAnnotation,
    List<InternalFuncArg>,
    List<SolidityInternalFunctions.InternalRetShape>,
    List<InternalFuncRet>, InternalFuncRet>, SolidityInternalFunctions {
    override fun bindArgs(s: List<InternalFuncArg>): Pair<List<TACSymbol>, CommandWithRequiredDecls<TACCmd.Simple>> {
        return s.map { it.s } to CommandWithRequiredDecls()
    }

    override fun projectOut(
        r: List<InternalFuncRet>,
        sig: QualifiedMethodSignature
    ): List<Pair<InternalFuncRet, VMTypeDescriptor>> {
        return r.zip(sig.resType)
    }

    override fun bindOut(r: InternalFuncRet, data: TACSymbol.Var): CommandWithRequiredDecls<TACCmd.Simple> {
        return CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = r.s,
            rhs = data.asSym().letIf(data.tag != r.s.tag && r.s.tag == Tag.Bit256) { sym ->
                check(data.tag == Tag.Bool)
                TXF {
                    ite(sym, 1, 0)
                }
            }
        )))
    }
}
