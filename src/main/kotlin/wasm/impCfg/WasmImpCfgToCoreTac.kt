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

package wasm.impCfg

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import datastructures.stdcollections.*
import tac.*
import tac.generation.assign
import utils.*
import vc.data.*
import wasm.analysis.memory.StaticMemoryAnalysis
import wasm.cfg.PC
import wasm.ir.WasmInstruction
import wasm.ir.WasmProgram
import wasm.summarization.WasmCallSummarizer
import wasm.tokens.WasmTokens.ENTRYPOINT

object WasmImpCfgToTAC {
    fun mkCvtBlockId(pc: PC): BlockIdentifier {
        return BlockIdentifier(pc, 0, 0, 0, 0, 0)
    }

    fun wasmImpCfgToCoreTac(
        targetFuncName: String,
        wasmAST: WasmProgram,
        wasmTac: WasmImpCfgProgram,
        summarizer: WasmCallSummarizer,
        hostInit: WasmToTacInfo
    ): CoreTACProgram {
        with(
            WasmImpCfgContext(summarizer)
        ) {
            val staticDataAnnotations = StaticMemoryAnalysis.getStaticDataAnnotations(wasmAST)
            // TODO  https://certora.atlassian.net/browse/CERT-9728
            val heapStart = wasmAST.allGlobals.mapNotNull {
                it.value.init.tryAs<WasmInstruction.Numeric.I32Const>()?.num?.v
            }.maxOrNull()?.asTACExpr ?: TACExpr.Unconstrained(Tag.Bit256)
            val symbols = mutableSetOf<TACSymbol>()
            val code: BlockNodes<TACCmd.Simple> = wasmTac.getNodes().entries.associate { (pc, wblock) ->
                val init = if (pc == ENTRYPOINT) {
                    mergeMany(
                        initMemory(),
                        hostInit,
                        assign(TACKeyword.RUST_FP.toVar()) { heapStart },
                        staticDataAnnotations,
                    )
                } else {
                    WasmToTacInfo()
                }

                val strghtTACsAndVars = wblock.straights.map {
                    it.wasmImpcfgToTacSimple()
                }
                val ctrsAndVars = wblock.ctrl.wasmImpcfgToTacSimple()
                val cmds = init.cmds +
                    strghtTACsAndVars.map { it.cmds }.flatten() +
                    ctrsAndVars.cmds
                symbols.addAll(init.varDecls)
                symbols.addAll(ctrsAndVars.varDecls)
                strghtTACsAndVars.forEach {
                    symbols.addAll(it.varDecls)
                }
                mkCvtBlockId(pc) to cmds
            }

            val entryBlock = wasmTac.getNodes().keys.find { wasmTac.preds(it).isEmpty() }?.let { mkCvtBlockId(it) }
            // h/t Jorge's approach from the `encode()` method.
            val symbolTable = TACSymbolTable(symbols)
            val ufAxioms = UfAxioms(mutableMapOf())
            val instTAC = InstrumentationTAC(ufAxioms)
            val procs = mutableSetOf<Procedure>()
            val blockGraph = wasmTac.getNodes().entries.associateTo(MutableBlockGraph()) { (pc, wblock) ->
                mkCvtBlockId(pc) to wblock.ctrl.succs().mapToTreapSet { mkCvtBlockId(it) }
            }
            return CoreTACProgram(
                code = code,
                blockgraph = blockGraph,
                name = targetFuncName,
                symbolTable = symbolTable,
                instrumentationTAC = instTAC,
                procedures = procs,
                check = true,
                entryBlock = entryBlock
            )
        }
    }

    val MEMORY_INITIALIZATION = MetaKey.Nothing("wasm.memory.init")

    private fun initMemory() = listOf(
        TACCmd.Simple.AssigningCmd.AssignExpCmd(
            TACKeyword.MEMORY.toVar(),
            TACExpr.MapDefinition(
                listOf(TACKeyword.TMP(Tag.Bit256).asSym()),
                TACExpr.Unconstrained(Tag.Bit256),
                TACKeyword.MEMORY.type as Tag.Map
            ),
            MetaMap(MEMORY_INITIALIZATION)
        )
    ).withDecls(TACKeyword.MEMORY.toVar())

    // For debugging only, really helpful for now, we can take this our when the tool is more robust
    // taken from Jorge
    fun dumpTAC(program: CoreTACProgram): String {
        val sb = StringBuilder()
        program.code.forEachEntry { (id, commands) ->
            sb.append("Block $id:\n")
            commands.forEach { command ->
                sb.append("\t${command}\n")
            }
        }
        sb.append("Graph\n")
        program.blockgraph.forEachEntry { (u, vs) ->
            sb.append("$u -> ${vs.joinToString(" ")}\n")
        }
        return sb.toString()
    }
}

