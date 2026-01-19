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

package sbf.callgraph

import datastructures.stdcollections.*
import sbf.analysis.AdaptiveScalarAnalysis
import sbf.analysis.AnalysisRegisterTypes
import sbf.analysis.IRegisterTypes
import sbf.cfg.*
import sbf.disassembler.Label
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.MemorySummaries
import sbf.support.SolanaCalltraceUtil
import sbf.tac.Calltrace.getFilepathAndLineNumber
import utils.Range
import utils.SourcePosition

fun inlineAttachedLocations(
    prog: SbfCallGraph,
    memSummaries: MemorySummaries,
): SbfCallGraph {
    val scalarAnalysis = AdaptiveScalarAnalysis(
        prog.getCallGraphRootSingleOrFail(),
        prog.getGlobals(),
        memSummaries
    )
    val regTypes = AnalysisRegisterTypes(scalarAnalysis)
    return prog.transformSingleEntry { cfg ->
        val cfgMut = cfg.clone(cfg.getName())
        AttachedLocationInliner(regTypes).run(cfgMut)
    }
}

private class AttachedLocationInliner<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>>(
    val regTypes: IRegisterTypes<TNum, TOffset>,
) {

    fun run(cfgMut: MutableSbfCFG): SbfCFG {
        val attachedLocationStack = mutableListOf<LocatedSbfInstruction>()
        val collector = PatchCollector()

        // collect changes
        val locInsts = cfgMut.getMutableEntry().locatedInstructionsDFS()
        for (locInst in locInsts) {
            if (locInst.inst.isCalltrace(CVTCalltrace.ATTACH_LOCATION)) {
                attachedLocationStack.add(locInst)
            } else if (locInst.inst.consumesAttachLocationIntrinsic()) {
                tryAttachRange(locInst, attachedLocationStack, collector)
            }
        }

        for ((block, patch) in collector.toBlockPatches(cfgMut)) {
            block.replaceInstructions(patch)
        }

        return cfgMut
    }

    private fun rangeFromStack(
        attachedLocationStack: MutableList<LocatedSbfInstruction>,
        collector: PatchCollector,
    ): Range.Range? {
        val attachedLocationLocInst = attachedLocationStack
            .removeLastOrNull()
            ?: return null

        val range = toRangeUnchecked(attachedLocationLocInst)
        collector.markDelete(attachedLocationLocInst)

        return range
    }

    private fun rangeFromDebugInfo(
        locInst: LocatedSbfInstruction,
    ): Range.Range? {
        val sbfAddress = locInst
            .inst
            .metaData
            .getVal(SbfMeta.SBF_ADDRESS)
            ?: return null

        return SolanaCalltraceUtil.sbfAddressToRangeWithHeuristic(sbfAddress)
    }

    private fun tryAttachRange(
        locInst: LocatedSbfInstruction,
        attachedLocationStack: MutableList<LocatedSbfInstruction>,
        collector: PatchCollector,
    ) {
        val range = rangeFromStack(attachedLocationStack, collector)
            ?: rangeFromDebugInfo(locInst)
            ?: return

        val rangeMeta = Pair(SbfMeta.RANGE, range)
        val newMeta = locInst.inst.metaData.plus(rangeMeta)
        val newInst = locInst.inst.copyInst(newMeta)

        collector.markChangeTo(locInst, listOf(newInst))
    }

    /**
     * it is expected that [locInst] here has been
     * validated to be [CVTCalltrace.ATTACH_LOCATION]
     */
    private fun toRangeUnchecked(locInst: LocatedSbfInstruction): Range.Range {
        val (filepath, oneBasedLineNumber) = regTypes.getFilepathAndLineNumber(locInst)

        val lineNumber = oneBasedLineNumber.checkedMinus(1U) ?: 0U

        // since all we have is a starting line number and no column, we create an approximate range
        return Range.Range(
            filepath,
            start = SourcePosition(lineNumber, 0U),
            end = SourcePosition(lineNumber + 1U, 0U),
        )
    }

    private fun SbfInstruction.consumesAttachLocationIntrinsic(): Boolean {
        return this.isAssertOrSatisfy()
                || this.isAllocFn()
                || this.isCalltrace(CVTCalltrace.SCOPE_START)
                || this.isPrint()
                || this.isNondet()
                || this.isCore(CVTCore.NONDET_SOLANA_ACCOUNT_SPACE)
                || this.isCore(CVTCore.ALLOC_SLICE)
    }

    private fun UInt.checkedMinus(other: UInt): UInt? = if (this >= other) {
        this - other
    } else {
        null
    }
}

private typealias BlockPatch = MutableMap<LocatedSbfInstruction, List<SbfInstruction>>

// we collect the changes to apply to each block here,
// to later apply them in batches
//
// N.B.:
// 1. it is _not_ guaranteed that an ATTACH_LOCATION is in
//    the same block as the instruction it will attach to!
// 2. this assumes each instruction is changed only once,
//    and in general it doesn't validate or protect against misuse
private class PatchCollector {
    private val labelToBlockChanges: MutableMap<Label, BlockPatch> = mutableMapOf()

    fun markDelete(locInst: LocatedSbfInstruction) {
        val blockChanges = this.labelToBlockChanges.getOrPut(locInst.label, ::mutableMapOf)
        blockChanges[locInst] = emptyList()
    }

    fun markChangeTo(locInst: LocatedSbfInstruction, changeTo: List<SbfInstruction>) {
        val blockChanges = this.labelToBlockChanges.getOrPut(locInst.label, ::mutableMapOf)
        blockChanges[locInst] = changeTo
    }

    fun toBlockPatches(cfgMut: MutableSbfCFG): Map<MutableSbfBasicBlock, BlockPatch> {
        return this
            .labelToBlockChanges
            .mapKeys { (label, _) ->
                val block = cfgMut.getMutableBlock(label)
                checkNotNull(block) { "located instruction referenced label $label, which is not in CFG" }
            }
    }
}

/** returns all [LocatedSbfInstruction] in depth-first order, starting from [this] */
private fun MutableSbfBasicBlock.locatedInstructionsDFS(): List<LocatedSbfInstruction> {
    val action = object : DfsVisitAction {
        val locInsts = mutableListOf<LocatedSbfInstruction>()

        override fun applyBeforeChildren(b: SbfBasicBlock) {
            val blockLocInsts = b.getLocatedInstructions()
            locInsts.addAll(blockLocInsts)
        }
        override fun applyAfterChildren(b: SbfBasicBlock) {}
        override fun skipChildren(b: SbfBasicBlock): Boolean = false
    }

    dfsVisit(this, action)
    return action.locInsts
}