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

package verifier

import analysis.CalldataAnalysis
import analysis.TACCommandGraph
import com.certora.collect.*
import tac.Tag
import vc.data.*
import java.math.BigInteger

object CalldataSplitter {
    fun splitCalldata(
        prog: CoreTACProgram
    ): CoreTACProgram {
        fun staticScalarizationSlot(base: TACSymbol.Var, idx: BigInteger): TACSymbol.Var =
            TACSymbol.Var(
                namePrefix = "${base.namePrefix}!${idx}",
                tag = Tag.Bit256,
                meta = base.meta
                    + (TACMeta.SCALARIZATION_SORT to ScalarizationSort.Split(idx))
                    + (TACMeta.BIT_WIDTH to 256)
                    + (TACMeta.WORDMAP_KEY to idx)
            )

        val newTags = treapSetBuilderOf<TACSymbol.Var>()
        fun replaceLoad(lhs: TACSymbol.Var, source: TACSymbol.Var) =
            TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = lhs, rhs = source).apply {
                newTags += source
            }

        val graph = TACCommandGraph(prog)
        val aa = CalldataAnalysis(graph)

        val accesses = (aa.result as? CalldataAnalysis.AliasResult.Valid)?.accesses ?: return prog
        val mutCode = prog.toPatchingProgram()
        accesses.entries.forEach { (access, idx) ->
            val slotVar = staticScalarizationSlot(access.base, idx)
            mutCode.update(access.cmd.ptr) { elem ->
                when (elem) {
                    is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                        mutCode.addVarDecl(slotVar)
                        replaceLoad(
                            elem.lhs,
                            slotVar.withMeta(TACMeta.IS_CALLDATA).withMeta(TACMeta.CALLDATA_OFFSET, idx)
                        ).mapMeta {
                            elem.meta
                        }
                    }
                    else -> error("impossible")
                }
            }
        }
        return prog.copyWithPatchingAndTags(mutCode, newTags.build())
    }
}
