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

package sbf.slicer

import sbf.*
import sbf.analysis.NPAnalysis
import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.output.annotateWithTypes
import sbf.support.NoAssertionAfterSlicerError
import sbf.support.printToFile
import datastructures.stdcollections.*
import log.*
import java.util.* // BitSet
import java.io.File

class SlicerError(msg: String): RuntimeException("Slicer error: $msg")

/**
 * Entry point for the slicer.
 *
 * Perform the sequence slicing + PTA optimizations [SolanaConfig.SlicerIter] times
 **/
fun sliceAndPTAOptLoop(rule: String, prog: SbfCallGraph, memSummaries: MemorySummaries,
                       start: Long /* for printing stats */): SbfCallGraph  {

    val numOfIter = SolanaConfig.SlicerIter.get()
    if (numOfIter < 0) {
        return prog
    }

    var i = 0U
    var optProg = prog
    var oldStats:CFGStats? = null
    while (i < numOfIter.toUInt()) {
        // Slice program wrt assertions
        // Remove any code that cannot affect program assertions
        sbfLogger.info { "Slicing entrypoint wrt to user assertions" }
        val start1 = System.currentTimeMillis()
        val slicerRes = sliceAssertions(optProg, memSummaries)
        val hasAssertions = slicerRes.first
        optProg = slicerRes.second
        val end1 = System.currentTimeMillis()
        sbfLogger.info { "Finished slicing entrypoint wrt to user assertions in ${(end1 - start1) / 1000}s" }
        sbfLogger.info { "Slicing stats after ${i+1U} iteration\n" +
                          "${optProg.getCallGraphRootSingleOrFail().getStats()}"}
        if (!hasAssertions) {
            sbfLogger.info { "End Solana front-end in ${(end1 - start) / 1000}s" }
            throw NoAssertionAfterSlicerError(rule)
        }

        // Run PTA optimizations that must be run after program has been inlined and sliced
        sbfLogger.info { "Started optimizations to help the memory analysis" }
        val start2 = System.currentTimeMillis()
        optProg = runPTAOptimizations(optProg, memSummaries)
        val end2 = System.currentTimeMillis()
        sbfLogger.info { "Finished optimizations in ${(end2 - start2) / 1000}s" }

        val newStats = optProg.getCallGraphRootSingleOrFail().getStats()
        if (oldStats == newStats) {
            sbfLogger.info { "Stopped slicing after ${i+1U} iterations because no more changes" }
            // fixpoint reached so we bail out
            break
        } else {
           oldStats = newStats
        }
        i++
    }

    return runPostSlicingOptimizations(optProg)
}


/**
 * Remove any basic block from which there is not a CFG path to any other block with an assertion.
 * Although this function takes an SbfCallGraph, the slicing only takes place on the root function.
 *
 * After slicing, a CFG might not have a single exit block.
 * The rest of the analyses doesn't need to have a single exit, so we don't enforce that.
 * If having a single exit is required in the future then we can just call normalize() on each sliced CFG.
 */
fun sliceAssertions(prog: SbfCallGraph, memSummaries: MemorySummaries): Pair<Boolean, SbfCallGraph> {
    val hasAssertions = prog.getCallGraphRootSingleOrFail().getBlocks().values.any { block ->
        block.getInstructions().any {  inst -> inst.isAssertOrSatisfy() }
    }

    if (!hasAssertions) {
        return Pair(false, prog)
    }

    val slicedProg = prog.transformSingleEntry { entryCFG ->
        val entrySlicedCFG = entryCFG.clone(entryCFG.getName())
        val outputBaseFilename = "${ArtifactManagerFactory().outputDir}${File.separator}${entrySlicedCFG.getName()}"
        var suffix = ".dot"
        ConeOfInfluence.transform(entrySlicedCFG, prog.getGlobals())
        if (SolanaConfig.DebugSlicer.get()) {
            suffix = ".CoI$suffix"
            printToFile(outputBaseFilename + suffix, entrySlicedCFG.toDot())
        }

        if (SolanaConfig.DebugSlicer.get()) {
            annotateWithTypes(entrySlicedCFG, prog.getGlobals(), memSummaries)
            suffix = ".fwd$suffix"
            printToFile(outputBaseFilename + suffix , entrySlicedCFG.toDot())
        }
        val np = NPAnalysis(entrySlicedCFG, prog.getGlobals(), memSummaries)
        SemanticConeOfInfluence.transform(entrySlicedCFG, np)
        if (SolanaConfig.DebugSlicer.get()) {
            suffix = ".back$suffix"
            printToFile(outputBaseFilename + suffix, entrySlicedCFG.toDot())
        }
        // Simplify select instructions
        lowerSelectToAssume(entrySlicedCFG, np)
        if (SolanaConfig.DebugSlicer.get()) {
            suffix = ".select2assume$suffix"
            printToFile(outputBaseFilename + suffix, entrySlicedCFG.toDot())
        }
        // Simplify removes blocks with abort instructions
        entrySlicedCFG.simplify(prog.getGlobals())
        entrySlicedCFG.removeAfterLastAssert()

        entrySlicedCFG
    }

    val hasAssertionsAfterSlicing = slicedProg.getCallGraphRootSingleOrFail().getBlocks().values.any { block ->
        block.getInstructions().any {  inst -> inst.isAssertOrSatisfy() }
    }

    if (!hasAssertionsAfterSlicing) {
        sbfLogger.warn{"No assertions left after slicing"}
    }

    return Pair(hasAssertionsAfterSlicing, slicedProg)
}

/**
 * The cone of influence is the set of basic blocks from which there is a CFG path to any other
 * block with an assertion. This analysis is purely syntactic.
 */
private object ConeOfInfluence {

    private fun computeIndexing(cfg: SbfCFG): Map<Label, Int> {
        val blockToBit: MutableMap<Label, Int> = mutableMapOf()
        for (label in cfg.getBlocks().keys) {
            blockToBit[label] = blockToBit.size
        }
        return blockToBit
    }

    private fun getIndex(b: SbfBasicBlock, blockToBit: Map<Label, Int>): Int {
        return blockToBit[b.getLabel()] ?: throw SlicerError("block $b not found in map \"blockToBit\"")
    }

    private fun analyze(b: SbfBasicBlock, coi: BitSet, blockToBit: Map<Label, Int>): Boolean {
        return if (b.getInstructions().any { it.isAssertOrSatisfy() }) {
            coi.set(getIndex(b, blockToBit))
            true
        } else {
            false
        }
    }

    private fun run(cfg: SbfCFG): (SbfBasicBlock) -> Boolean {
        val blockToBit = computeIndexing(cfg)
        // A block b is in the cone-of-influence if coi[blockToBit(b)] is true
        val coi = BitSet(cfg.getBlocks().size) // all bits set to false
        val worklist = ArrayList<SbfBasicBlock>()
        for (b in cfg.getBlocks().values) {
            if (analyze(b, coi, blockToBit)) {
                worklist.add(b)
            }
        }
        while (worklist.isNotEmpty()) {
            val b = worklist.removeLast()
            for (pred in b.getPreds()) {
                val i = getIndex(pred, blockToBit)
                if (!coi.get(i)) {
                    coi.set(i)
                    worklist.add(pred)
                }
            }
        }
        return {b -> coi.get(getIndex(b, blockToBit))}
    }

    fun transform(cfg: MutableSbfCFG, globals: GlobalVariables) {
        if (!cfg.hasExit()) {
            sbfLogger.warn { "Cannot compute cone of influence because cfg has no exit block" }
            return
        }

        val isInCoi = run(cfg)
        // Add abort instructions
        for (b in cfg.getMutableBlocks().values) {
            if (!isInCoi(b)) {
                b.add(0, mkUnreachable("OUT-COI"))
            }
        }
        // Simplify removes blocks with abort instructions
        cfg.simplify(globals)
        cfg.removeAfterLastAssert()
    }
}

/**
 *  Remove any block that cannot affect the evaluation of an assertion by using [NPAnalysis].
 */
private object SemanticConeOfInfluence{

    /// Add abort instructions whenever a block or an instruction becomes unreachable
    fun transform(cfg: MutableSbfCFG, np: NPAnalysis) {
        outerloop@ for ((label, bb) in cfg.getMutableBlocks()) {
            if (bb.getInstructions().any { it.isAbort() }) {
                continue
            }
            // We use the backward analysis to detect unreachability.
            // The backward analysis can only answer queries about the entry of a block
            if ( np.getPreconditionsAtEntry(label)?.isBottom() == true) {
                if (!bb.getInstructions().any { it.isAssertOrSatisfy() }) {
                    bb.add(0, mkUnreachable("OUT-SCOI (using backward)"))
                    continue
                }
            }

            // We use now the forward analysis to detect unreachability.
            // By asking the type of r10 we can tell if locInst becomes unreachable or not
            for (locInst in bb.getLocatedInstructions()) {
               if (np.registerTypes.typeAtInstruction(locInst, SbfRegister.R10_STACK_POINTER).isBottom()) {
                    bb.add(locInst.pos,  mkUnreachable("OUT-SCOI (using forward)"))
                    continue@outerloop
                }
            }
        }
    }
}

private fun mkUnreachable(comment: String): SbfInstruction =
    SolanaFunction.toCallInst(SolanaFunction.ABORT,
        MetaData(Pair(SbfMeta.COMMENT, comment)).plus(Pair(SbfMeta.UNREACHABLE_FROM_COI, "")))
