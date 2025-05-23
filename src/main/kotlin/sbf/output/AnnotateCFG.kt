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

package sbf.output

import sbf.analysis.ScalarAnalysis
import sbf.callgraph.SbfCallGraph
import sbf.cfg.*
import sbf.disassembler.Label
import sbf.disassembler.GlobalVariableMap
import datastructures.stdcollections.*
import sbf.domains.*

/**
 * Only for debugging purposes
 **/

fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> annotateWithTypes(
        cfg: MutableSbfCFG,
        globals: GlobalVariableMap,
        memSummaries: MemorySummaries,
        sbfTypesFac: ISbfTypeFactory<TNum, TOffset>) {
    val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
    annotateWithTypes(cfg, scalarAnalysis)
}

/**
 * Annotate the instructions of [cfg] the with types extracted from [scalarAnalysis].
 **/
fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> annotateWithTypes(cfg: MutableSbfCFG, scalarAnalysis: ScalarAnalysis<TNum, TOffset>) {
    fun getType(v: Value, absVal: ScalarDomain<TNum, TOffset>): SbfRegisterType? {
        return if (v is Value.Imm) {
            null
        } else {
            absVal.getValue(v).get().concretize()
        }
    }
    fun getPre(block: Label) = scalarAnalysis.getPre(block)

    annotateCFGWithTypes(cfg, scalarAnalysis.globalsMap, scalarAnalysis.memSummaries, ::getPre, ::getType)
}

/**
 * Annotate the instructions of the entrypoint function with types extracted from
 * [ScalarAnalysis].
 ***/
fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> annotateWithTypes(
        prog: SbfCallGraph,
        memSummaries: MemorySummaries,
        sbfTypesFac: ISbfTypeFactory<TNum, TOffset>): SbfCallGraph {
    return prog.transformSingleEntry { entryCFG ->
        val newEntryCFG = entryCFG.clone(entryCFG.getName())
        annotateWithTypes(newEntryCFG, prog.getGlobals(), memSummaries, sbfTypesFac)
        newEntryCFG
    }
}
