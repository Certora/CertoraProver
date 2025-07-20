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

import analysis.snarrowOrNull
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import spec.CVLCompiler.CallIdContext.Companion.toContext
import spec.ProgramGenMixin.Companion.andThen
import spec.converters.LowLevelDecoder
import spec.converters.repr.CVLDataOutput
import spec.cvlast.typedescriptors.CallReturnTarget
import spec.cvlast.typedescriptors.IExternalOutputBuffer
import spec.cvlast.typedescriptors.IProgramOutput
import spec.cvlast.typedescriptors.ITypeDescriptorConverter
import spec.toProg
import tac.CallId
import utils.`impossible!`
import vc.data.*
import vc.data.TACSymbol.Companion.atSync
import java.math.BigInteger

/*
    Updates code around returns to update return variables.
    This instruments tacRetvalNUM variables from return commands,
    where we have an expected return size [expectedRetSize] in the call with ID [calleeTxId]
    (so this relies on knowing which block belongs to which 'procedure'.)

    e.g. `ReturnCmd Start,Size` where the actual expected ret size is some constant 32*k we add
    ```
    tmp = Start+0
    tacRetval0 = tacM[tmp]
    ...
    tmp = Start+32*(k-1)
    tacRetvalk = tacM[tmp]
    ```

    Importantly, this code is called during CVL compilation, and shouldn't be called before that.
 */
class ReturnsProcessor(
    private val returnConverters: List<Triple<BigInteger, TACSymbol.Var, ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>>>,
    private val calleeTxId: CallId,
    private val calleeFreePointerScalarized: Boolean
) {
    fun transform(c: CVLTACProgram) : CVLTACProgram {
        fun TACCmd.Spec.extract() = when(this) {
            is TACCmd.Simple.SummaryCmd -> this.snarrowOrNull<ReturnSummary>()!!.ret
            is TACCmd.Simple.ReturnCmd,
            is TACCmd.Simple.ReturnSymCmd,
            is TACCmd.Simple.RevertCmd -> this
            else -> throw IllegalArgumentException("$this is not a return-like command")
        }
        @Suppress("IfThenToElvis") val toInst = c.parallelLtacStream().filter {
            it.cmd is TACCmd.Simple && it.ptr.block.calleeIdx == calleeTxId && it.cmd.isReturn()
        }.filter {
            it.cmd.extract() !is TACCmd.Simple.RevertCmd
        }.map {
            val standardScalars = if (Config.Mem0x0To0x40AsScalar) {
                mapOf(
                    BigInteger.ZERO to TACKeyword.MEM0.toVar().atSync(calleeTxId),
                    EVM_WORD_SIZE to TACKeyword.MEM32.toVar().atSync(calleeTxId)
                )
            } else {
                mapOf()
            }
            val scalars = if(calleeFreePointerScalarized) {
                standardScalars + (EVM_WORD_SIZE.times(BigInteger.TWO) to TACKeyword.MEM64.toVar().atSync(calleeTxId))
            } else {
                standardScalars
            }
            val (buff, setup) = when(val ret = it.cmd.extract()) {
                is TACCmd.Simple.ReturnSymCmd -> LowLevelDecoder(ret.o)
                is TACCmd.Simple.ReturnCmd ->
                    LowLevelDecoder(
                        buffer = ret.memBaseMap,
                        offset = ret.o1,
                        scalars = scalars
                    )
                else -> `impossible!`
            }
            val toInsert = returnConverters.map { (offs, nm, conv) ->
                buff.saveScope { withScope ->
                    withScope.advanceCurr(offs) { atField ->
                        conv.convertTo(
                            fromVar = atField,
                            toVar = CVLDataOutput(nm, calleeTxId),
                            cb = { it }
                        ) as CVLTACProgram
                    }
                }
            }.takeIf { it.isNotEmpty() }?.reduce(::mergeCodes)
            it.ptr to if(toInsert == null) {
                 setup.toProg("return", env = calleeTxId.toContext())
            } else {
                setup andThen toInsert
            }

        }.toList()
        return c.patching {
            for((where, code) in toInst) {
                it.replaceCommand(where, code)
            }
        }
    }

}
