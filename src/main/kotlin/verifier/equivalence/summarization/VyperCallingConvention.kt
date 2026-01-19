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
import analysis.ip.VyperReturnValue
import evm.EVM_WORD_SIZE
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import verifier.equivalence.VyperIODecomposer
import java.math.BigInteger
import datastructures.stdcollections.*
import tac.CallId
import vc.data.TACSymbol.Companion.atSync

/**
 * Unlike [UnifiedCallingConvention] (where everything lives in the stack and there isn't any interesting shape information)
 * we do need to keep around the [ValueShape] of arguments/returns.
 */
data class VyperCallingConvention(
    val args: List<VyperArgument>,
    val ret: VyperReturnValue?
) : PureFunctionExtraction.CallingConvention<VyperCallingConvention, List<VyperCallingConvention.ValueShape>, VyperCallingConvention.ValueShape?>, VyperIODecomposer {
    sealed interface ValueShape {
        data class InMemory(val size: BigInteger) : ValueShape
        data object OnStack : ValueShape
    }

    override fun decomposeArgs(): Pair<List<TACSymbol>, List<ValueShape>> {
        return args.decompose()
    }

    override fun decomposeRet(): Pair<List<TACSymbol.Var>, ValueShape?> {
        return ret.decompose()
    }

    override fun bindOutputs(): Pair<List<TACSymbol.Var>, CommandWithRequiredDecls<TACCmd.Simple>> {
        if(ret == null) {
            return listOf<TACSymbol.Var>() to CommandWithRequiredDecls()
        }
        return when(ret) {
            is VyperReturnValue.MemoryReturnValue -> {
                val toRet = mutableListOf<TACSymbol.Var>()
                val bindCode = MutableCommandWithRequiredDecls<TACCmd.Simple>()
                for(i in (BigInteger.ZERO ..< ret.size).stepBy(EVM_WORD_SIZE)) {
                    val res = TACSymbol.Var("resultVar$i", Tag.Bit256).toUnique("!")
                    toRet.add(res)
                    bindCode.extend(ExprUnfolder.unfoldPlusOneCmd(
                        "bindOut$i",
                        TXF { ret.s add i }
                    ) { loc ->
                        TACCmd.Simple.AssigningCmd.ByteLoad(
                            lhs = res,
                            base = TACKeyword.MEMORY.toVar(),
                            loc = loc.s
                        )
                    })
                    bindCode.extend(TACKeyword.MEMORY.toVar(), res)
                }
                toRet to bindCode.toCommandWithRequiredDecls()
            }
            is VyperReturnValue.StackVariable -> listOf(ret.s) to CommandWithRequiredDecls<TACCmd.Simple>()
        }
    }

    override fun bindSymbolicArgs(
        callId: CallId,
        argumentVar: (Int) -> TACSymbol.Var
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val calleeMemory = TACKeyword.MEMORY.toVar().atSync(callId)
        var argNum = 0
        val mut = MutableCommandWithRequiredDecls<TACCmd.Simple>()
        for(p in args) {
            when(p) {
                is VyperArgument.MemoryArgument -> {
                    for(i in (BigInteger.ZERO ..< p.size).stepBy(EVM_WORD_SIZE)) {
                        val indexed = argumentVar(argNum++)
                        mut.extend(TACCmd.Simple.AssigningCmd.ByteStore(
                            base = calleeMemory,
                            loc = (p.where + i).asTACSymbol(),
                            value = indexed
                        ))
                        mut.extend(indexed)
                    }
                }
                is VyperArgument.StackArgument -> {
                    if(p.s !is TACSymbol.Var) {
                        continue
                    }
                    val indexed = p.s.atSync(callId)
                    val inputVar = argumentVar(argNum++)
                    mut.extend(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = indexed,
                        rhs = inputVar
                    ))
                    mut.extend(indexed, inputVar)
                }
            }
        }
        return mut.toCommandWithRequiredDecls()
    }

}
