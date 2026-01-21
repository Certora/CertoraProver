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
import analysis.MutableCommandWithRequiredDecls
import analysis.ip.VyperArgument
import analysis.ip.VyperInternalFuncEndAnnotation
import analysis.ip.VyperInternalFuncStartAnnotation
import analysis.ip.VyperReturnValue
import evm.EVM_WORD_SIZE
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.MetaKey
import tac.Tag
import utils.*
import vc.data.TACCmd
import vc.data.TACKeyword
import vc.data.TACSymbol
import vc.data.asTACSymbol
import verifier.equivalence.VyperIODecomposer
import datastructures.stdcollections.*

/**
 * Summarization semantics for Vyper internal annotations.
 */
object VyperSummarization : SharedPureSummarization.SummarizationImpl<
    VyperInternalFuncStartAnnotation, VyperInternalFuncEndAnnotation,
    List<VyperArgument>, VyperCallingConvention.ValueShape?, VyperReturnValue?, VyperReturnValue>, VyperIODecomposer {
    override fun bindArgs(s: List<VyperArgument>): Pair<List<TACSymbol>, CommandWithRequiredDecls<TACCmd.Simple>> {
        val toRetCmd = MutableCommandWithRequiredDecls<TACCmd.Simple>()
        val toRetSyms = mutableListOf<TACSymbol>()

        for(a in s) {
            when(a) {
                is VyperArgument.MemoryArgument -> {
                    for(ind in (a.where ..< (a.where + a.size)).stepBy(EVM_WORD_SIZE)) {
                        val tmp = TACSymbol.Var("functionArg", Tag.Bit256).toUnique()
                        toRetCmd.extend(TACKeyword.MEMORY.toVar(), tmp)
                        toRetCmd.extend(TACCmd.Simple.AssigningCmd.ByteLoad(
                            lhs = tmp,
                            base = TACKeyword.MEMORY.toVar(),
                            loc = ind.asTACSymbol()
                        ))
                        toRetSyms.add(tmp)
                    }
                }
                is VyperArgument.StackArgument -> {
                    toRetSyms.add(a.s)
                }
            }
        }
        return toRetSyms to toRetCmd.toCommandWithRequiredDecls()
    }

    override fun projectOut(
        r: VyperReturnValue?,
        sig: QualifiedMethodSignature
    ): List<Pair<VyperReturnValue, VMTypeDescriptor>> {
        return r?.let { rv ->
            listOf(rv to sig.resType.single())
        }.orEmpty()
    }

    override fun bindOut(r: VyperReturnValue, data: TACSymbol.Var): CommandWithRequiredDecls<TACCmd.Simple> {
        return when(r) {
            is VyperReturnValue.MemoryReturnValue -> {
                check(r.size == EVM_WORD_SIZE)
                CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        loc = r.s,
                        value = data,
                        base = TACKeyword.MEMORY.toVar()
                    )
                ), TACKeyword.MEMORY.toVar(), r.s, data)
            }
            is VyperReturnValue.StackVariable -> {
                CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = r.s,
                        rhs = data.asSym()
                    )
                ), r.s, data)
            }
        }
    }

    override val startMeta: MetaKey<VyperInternalFuncStartAnnotation>
        get() = VyperInternalFuncStartAnnotation.META_KEY
    override val endMeta: MetaKey<VyperInternalFuncEndAnnotation>
        get() = VyperInternalFuncEndAnnotation.META_KEY

    override fun recomposeReturnInfo(
        vars: List<TACSymbol.Var>,
        shape: VyperCallingConvention.ValueShape?
    ): VyperReturnValue? {
        return shape.recomposeRet(vars)
    }

    override fun decomposeReturnInfo(annotation: VyperInternalFuncEndAnnotation): Pair<List<TACSymbol.Var>, VyperCallingConvention.ValueShape?> {
        return annotation.returnValue.decompose()
    }

    override fun projectArgs(annotation: VyperInternalFuncStartAnnotation): List<VyperArgument> {
        return annotation.args
    }
}
