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

package sbf.cfg

import sbf.SolanaConfig
import sbf.callgraph.SbfCallGraph
import sbf.domains.MemorySummaries
import sbf.disassembler.GlobalVariables

/**
 * Simple (local) CFG optimizations that help the pointer analysis.
 * These optimizations do not use any semantic analysis.
 * Thus, they can be applied before inlining/slicing happens.
 */
fun runSimplePTAOptimizations(cfg: MutableSbfCFG, globals: GlobalVariables) {
    if (SolanaConfig.optimisticNoMemmove()) {
        removeMemmove(cfg)
        cfg.verify(false, "after removing memmove")
    }
    unhoistMemFunctions(cfg)
    cfg.verify(false, "after unhoisting memcpy")
    cfg.mergeBlocks()
    cfg.verify(false, "after merging blocks ")
    unhoistStoresAndLoads(cfg, globals)
    cfg.verify(false, "after unhoisting stores")
    cfg.removeUselessBlocks()
    cfg.verify(false, "after remove useless blocks")
    unhoistStackPop(cfg)
    cfg.verify(false, "after unhoisting stack pop instruction")
    unhoistCalltraceFunctions(cfg)
    cfg.verify(false, "after unhoisting calltrace functions")
}

/**
 * CFG optimizations that help the pointer analysis.
 *
 * These optimizations require the scalar analysis, so they should be run after
 * [prog] has been inlined and sliced for better precision.
 *
 * Note that each optimization runs a scalar analysis since the program can change from one optimization
 * to another.
 **/
fun runPTAOptimizations(prog: SbfCallGraph, memSummaries: MemorySummaries): SbfCallGraph {
    return prog.transformSingleEntry { entryCFG ->
        val optEntryCFG = entryCFG.clone(entryCFG.getName())
        promoteStoresToMemcpy(optEntryCFG, prog.getGlobals(), memSummaries)
        removeUselessDefinitions(optEntryCFG)
        markLoadedAsNumForPTA(optEntryCFG)
        unhoistPromotedMemcpy(optEntryCFG)
        optEntryCFG.simplify(prog.getGlobals())
        splitWideStores(optEntryCFG, prog.getGlobals(), memSummaries)
        optEntryCFG.normalize()
        optEntryCFG.verify(true, "after PTA optimizations")
        optEntryCFG
    }
}

/** CFG optimizations that should be executed only once **/
fun runPostSlicingOptimizations(prog: SbfCallGraph): SbfCallGraph {
    return prog.transformSingleEntry { entryCFG ->
        val optEntryCFG = entryCFG.clone(entryCFG.getName())
        // prerequisite for detectOverflowPatterns
        simplifyBools(optEntryCFG)
        optEntryCFG.verify(false, "[after simplifyBools]")
        detectOverflowPatterns(optEntryCFG)
        optEntryCFG.verify(false, "[after markAddWithOverflow]")
        optEntryCFG.normalize()
        optEntryCFG.verify(true, "after post-slicing optimizations")
        optEntryCFG
    }
}
