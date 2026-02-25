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

import com.certora.collect.*
import sbf.SolanaConfig
import sbf.disassembler.*
import datastructures.stdcollections.*
import dwarf.DebugSymbols
import sbf.dwarf.DWARFEdgeLabelAnnotator
import kotlin.collections.removeLast

/**
 * Control Flow Graph representation of an SBF function
 **/

class CFGBuilderError(msg: String): RuntimeException("CFG builder error: $msg")
class CFGVerifyError(msg: String): RuntimeException("CFG is not well-formed: $msg")

@Treapable
interface SbfBasicBlock {
    fun getLabel(): Label
    fun getSuccs(): List<SbfBasicBlock>
    fun getPreds(): List<SbfBasicBlock>
    fun numOfInstructions(): Int
    fun getInstruction(i: Int): SbfInstruction
    fun getInstructions(): List<SbfInstruction>
    fun getLocatedInstructions(): List<LocatedSbfInstruction>
    fun getTerminator(): SbfInstruction
}

class MutableSbfBasicBlock(private val label: Label): SbfBasicBlock {
    private val insts = ArrayList<SbfInstruction>()
    /**
     *  Comment#1: @params preds and @params succs contain SbfBasicBlock objects
     *  instead of Label ones so that we can access to neighbour without an extra level of indirection
     *  by asking the SbfCFG map from Label to SbfBasicBlock.
     *  Comment#2: @params preds and @params succs use ArrayList instead of MutableSet because we
     *  should not store SbfBasicBlock objects in sets or as hash keys.
     */
    private val preds = ArrayList<MutableSbfBasicBlock>()
    private val succs = ArrayList<MutableSbfBasicBlock>()

    override fun hashCode(): Int = label.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SbfBasicBlock) {
            return false
        }
        return label == other.getLabel()
    }


    fun addSucc(b: MutableSbfBasicBlock) {
        check(succs.all { it.label != b.label }) {"Adding twice ${b.label} as successor of $label"}
        check(b.getPreds().all { it.getLabel() != label }) {"Adding twice $label as predecessor of ${b.label}"}

        succs.add(b)
        b.preds.add(this)
    }

    fun removeSucc(b: MutableSbfBasicBlock) {
        succs.removeIf { it.label == b.label }
        b.preds.removeIf { it.label ==  label }
    }

    override fun getLabel() = label

    override fun getSuccs(): List<SbfBasicBlock> = succs

    fun getMutableSuccs(): List<MutableSbfBasicBlock> = succs

    fun copySuccs(b: MutableSbfBasicBlock) {
        for (succ in b.succs) {
            addSucc(succ)
        }
    }

    fun clear() {
        // notify successors of bb
        for (succ in succs) {
            succ.preds.removeIf { it.label == label }
        }
        // notify predecessors of bb
        for (pred in preds) {
           pred.succs.removeIf { it.label == label }
        }
        insts.clear()
        preds.clear()
        succs.clear()
    }

    fun removeSuccs() {
        for (succ in succs) {
            succ.preds.removeIf {it.label == label }
        }
        succs.clear()
    }

    override fun getPreds(): List<SbfBasicBlock> = preds

    fun getMutablePreds(): List<MutableSbfBasicBlock> = preds

    override fun numOfInstructions() = insts.size

    override fun getInstruction(i: Int): SbfInstruction {
        check(i >= 0 && i < insts.size) {"getInstruction accessing out-of-bounds"}
        return insts[i]
    }

    override fun getInstructions(): List<SbfInstruction> = insts
    override fun getLocatedInstructions(): List<LocatedSbfInstruction> =
        insts.withIndex().map { LocatedSbfInstruction(label, it.index, it.value)}

    override fun getTerminator(): SbfInstruction {
        if (insts.isEmpty()) {
            throw CFGVerifyError("block $label does not have terminator because it is empty")
        }
        val lastInst = insts[insts.size-1]
        if (!lastInst.isTerminator()) {
            throw CFGVerifyError("The instruction $lastInst in block $label is not a terminator")
        }
        return lastInst
    }

    fun replaceInstruction(i: Int, newInst: SbfInstruction) {
        check(i >= 0 && i < insts.size) {"replaceInstruction accessing out-of-bounds"}
        insts[i] = newInst
    }

    fun replaceInstruction(oldInst: LocatedSbfInstruction, newInst: SbfInstruction) =
        replaceInstruction(oldInst.pos, newInst)

    /**
     * Replace each located instruction in `remap` with a sequence of consecutive instructions.
     * Remapping to an empty list deletes the instruction.
     **/
    fun replaceInstructions(remap: Map<LocatedSbfInstruction, List<SbfInstruction>>) {
        val posToChanges = remap.mapKeys { (locInst, _) ->
            require(locInst.label == this.label) { "remapper contained instructions from a different block" }
            locInst.pos
        }
        require(posToChanges.size == remap.size) { "remapper contained different located instructions with same pos" }

        val size = this.insts.size
        for (pos in posToChanges.keys) {
            check(pos in 0..<size) { "pos $pos is out of bounds for block (size = $size)" }
        }

        // we could also check more things here, but that's enough for now

        val newInsts = this.insts.flatMapIndexed { pos, originalInst -> posToChanges[pos] ?: listOf(originalInst) }
        this.insts.clear()
        this.insts.addAll(newInsts)
    }

    fun replaceInstructions(newInsts: List<SbfInstruction>) {
        insts.clear()
        for (inst in newInsts) {
            insts.add(inst)
        }
    }

    // Add an instruction at the end of the block
    fun add(inst: SbfInstruction) {
        insts.add(inst)
    }

    fun add(i: Int, inst: SbfInstruction) {
        insts.add(i, inst)
    }

    fun addAll(insts: List<SbfInstruction>) {
        this.insts.addAll(insts)
    }

    fun addAll(i: Int, insts: List<SbfInstruction>) {
        this.insts.addAll(i, insts)
    }

    fun removeAt(i: Int): SbfInstruction {
        return insts.removeAt(i)
    }

    fun removeAfter(i: Int) {
        check(i < insts.size) {"removeAfter accessing out-of-bounds"}
        var n = insts.size - (i+1)
        while (n > 0) {
            insts.removeLast()
            n -= 1
        }
    }

    fun removeAnnotations(annotations: Collection<MetaKey<*>>) {
        for (locInst in getLocatedInstructions()) {
            val inst = locInst.inst
            for (annotation in annotations) {
                if (inst.metaData.getVal(annotation) != null) {
                    val newInst = inst.copyInst(inst.metaData.minus(annotation))
                    replaceInstruction(locInst.pos, newInst)
                }
            }
        }
    }

    /** Fold the first [i] instructions of this block into its predecessors **/
    fun foldIntoPredecessors(i: Int) {
        for (pred in getMutablePreds()) {
            val termInst = pred.getTerminator()
            val numInsts = pred.getInstructions().size
            pred.removeAt(numInsts-1) // remove temporary the terminator
            pred.addAll(getInstructions().subList(0, i))
            pred.add(termInst)  // put the terminator back
        }
        repeat (i) {
            removeAt(0)
        }
    }

    /** Replace target blocks in Jump instructions **/
    fun replaceJumpTargets(labelMap: Map<Label,Label>) {
        val it = insts.listIterator()
        while (it.hasNext()) {
            val inst = it.next()
            if (inst is SbfInstruction.Jump.UnconditionalJump) {
                val fixedTarget = labelMap[inst.target]
                check(fixedTarget != null)
                val newInst = SbfInstruction.Jump.UnconditionalJump(fixedTarget, inst.metaData)
                it.set(newInst)
            } else if (inst is SbfInstruction.Jump.ConditionalJump) {
                val fixedTrueTarget = labelMap[inst.target]
                check(fixedTrueTarget != null)
                val fixedFalseTarget = labelMap[inst.falseTarget]
                check(fixedFalseTarget != null)
                val newInst = SbfInstruction.Jump.ConditionalJump(
                    inst.cond,
                    fixedTrueTarget,
                    fixedFalseTarget,
                    inst.metaData
                )
                it.set(newInst)
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("${label}:\n")
        for (inst in insts) {
            sb.append("\t${inst}\n")
        }
        return sb.toString()
    }
}

data class CFGEdge(val src: Label, val dst: Label, val cond: Condition)

typealias DotColor = String

interface SbfCFG {
    fun getName(): String
    fun hasEntry(): Boolean
    fun getEntry(): SbfBasicBlock
    fun hasExit(): Boolean
    fun getExit(): SbfBasicBlock
    fun getBlocks(): Map<Label, SbfBasicBlock>
    fun getBlock(label: Label): SbfBasicBlock?
    fun clone(name: String): MutableSbfCFG
    fun verify(checkHasOneExit: Boolean, msg: String = "")
    fun toDot(annotations: (SbfBasicBlock) -> Pair<String?, Boolean?> = {_ -> Pair(null, null)},
              colorMap: Map<LocatedSbfInstruction, DotColor> = mapOf()): String
    fun getStats(): CFGStats
}

class MutableSbfCFG(private val name: String): SbfCFG {
    /**
     *  @params entry and @params exit are initially null, but they must be set to non-null values
     *  before the CFG is considered well-formed.
     *  This and other important properties are ensured by verify method which is run the first time a CFG is
     *  completely built and then, after every CFG-to-CFG transformation.
     */
    private var entry: MutableSbfBasicBlock? = null
    private var exit: MutableSbfBasicBlock? = null
    private var blocks: MutableMap<Label, MutableSbfBasicBlock> = mutableMapOf()

    fun getOrInsertBlock(label: Label): MutableSbfBasicBlock {
        var bb = blocks[label]
        if (bb == null) {
            bb = MutableSbfBasicBlock(label)
            blocks[label] = bb
        }
        return bb
    }

    override fun getName() = name

    override fun hasEntry() = entry != null

    override fun getEntry(): SbfBasicBlock {
        check(entry != null){"CFG without entry block"}
        return entry!!
    }

    fun getMutableEntry(): MutableSbfBasicBlock {
        check(entry != null){"CFG without entry block"}
        return entry!!
    }

    fun setEntry(b: MutableSbfBasicBlock) {
        entry = b
    }

    override fun hasExit() = exit != null

    override fun getExit(): SbfBasicBlock {
        check(exit != null){"CFG without exit block"}
        return exit!!
    }

    fun getMutableExit(): MutableSbfBasicBlock {
        check(exit != null){"CFG without exit block"}
        return exit!!
    }

    fun setExit(b: MutableSbfBasicBlock?) {
        exit = b
    }

    override fun getBlocks(): Map<Label, SbfBasicBlock> = blocks

    fun getMutableBlocks() = blocks

    override fun getBlock(label: Label): SbfBasicBlock? {
       return getMutableBlock(label)
    }

    fun getMutableBlock(label: Label): MutableSbfBasicBlock? {
        return blocks[label]
    }

    fun setBlock(label: Label, block: MutableSbfBasicBlock) {
        blocks[label] = block
    }

    fun clear() {
        blocks.clear()
        entry = null
        exit = null
    }

    /**
     * This is a deep copy of the CFG
     **/
    override fun clone(name: String): MutableSbfCFG {
        val clonedCFG = MutableSbfCFG(name)
        for (label in blocks.keys) {
            clonedCFG.getOrInsertBlock(label)
        }

        fun getClonedBlock(label: Label): MutableSbfBasicBlock {
            check(blocks.containsKey(label)){"There is no a block for $label (${blocks.keys}) in\n$this"}
            val clonedBlock = clonedCFG.blocks[label]
            check(clonedBlock != null){"Cannot found cloned block for $label"}
            return clonedBlock
        }

        for (block in blocks.values) {
            val clonedBlock = getClonedBlock(block.getLabel())
            for (inst in block.getInstructions()) {
                // Important: although inst is immutable, we must also make a copy of inst.
                // This is because we rely on the fact that instructions cannot be shared across different CFGs.
                // Otherwise, after inlining we could end up having the same (object) instruction in multiple
                // places in the same CFG.
                clonedBlock.add(inst.copyInst())
            }
            for (succ in block.getSuccs()) {
                val clonedSuccBlock = getClonedBlock(succ.getLabel())
                clonedBlock.addSucc(clonedSuccBlock)
            }
        }

        if (entry != null) {
            clonedCFG.entry = getClonedBlock(entry!!.getLabel())
        }
        if (exit != null) {
            clonedCFG.exit = getClonedBlock(exit!!.getLabel())
        }
        return clonedCFG
    }

    fun sliceFrom(entry: Label, functionName: String): SbfCFG {
        class ReachableVisitor: DfsVisitAction {
            val reachable = mutableSetOf<Label>()
            var firstExit: SbfBasicBlock? = null
            var numOfExit = 0
            override fun applyBeforeChildren(b: SbfBasicBlock) {
                // mark block as reachable
                reachable.add(b.getLabel())
                // identify exit block
                for (inst in b.getInstructions()) {
                    if (inst is SbfInstruction.Exit) {
                        numOfExit++
                        if (firstExit == null) {
                            firstExit = b
                        }
                    }
                }
            }
            override fun applyAfterChildren(b: SbfBasicBlock) {}
            override fun skipChildren(b: SbfBasicBlock): Boolean {
                return false
            }
        }

        // Gather all basic blocks reachable from @entry
        val vis = ReachableVisitor()
        val entryB = blocks[entry]
        check(entryB != null) {"CFG entry cannot be null"}
        // Reset the entry to @entry and get the reachable blocks from there
        dfsVisit(entryB, vis)
        // Return the induced CFG by @entry
        val res = MutableSbfCFG(functionName)
        for (label in vis.reachable) {
            val block = blocks[label]
            check(block!=null){"no block found for $label"}
            res.setBlock(label, block)
        }
        res.setEntry(entryB)
        res.setExit(if (vis.firstExit != null && vis.numOfExit == 1) {
            blocks[vis.firstExit!!.getLabel()]
        } else {
            null
        })
        return res
    }

    private fun removeBlock(label: Label) {
        val bb = blocks[label]
        if (bb != null) {
            entry?.let { if (it.getLabel() == bb.getLabel()) {
                throw CFGVerifyError("Cannot remove entry block of a CFG")
            } }

            bb.clear()
            blocks.remove(label)

            exit?.let { if (it.getLabel() == bb.getLabel()) {
                exit = null
            } }
        }
    }

    fun removeAnnotations(annotations: Collection<MetaKey<*>>) {
        for (bb in this.getMutableBlocks().values) {
            bb.removeAnnotations(annotations)
        }
    }

    companion object {
        fun getTargets(prog: SbfProgram): Set<Label> {
            val res = mutableSetOf<Label>()
            res.add(prog.program[0].first)
            prog.entriesMap.forEachEntry {
                res.add(Label.Address(it.value))
            }

            var i = 0
            while (i <  prog.program.size) {
                val labeledInst = prog.program[i]
                val inst = labeledInst.second
                if (inst is SbfInstruction.Exit) {
                    val pc = labeledInst.first
                    res.add(pc)
                } else if (inst is SbfInstruction.Jump) {
                    res.add(inst.target)
                    if (inst is SbfInstruction.Jump.ConditionalJump) {
                        /**
                         * Note that getTargets is called before we actually build the CFG.
                         * At this point inst.falseTarget is null. It will set to the proper target later
                         */
                        if (i+1 >= prog.program.size) {
                            throw CFGBuilderError("out-of-bounds jump instruction")
                        }
                        res.add(prog.program[i+1].first)
                    }
                } else if (inst is SbfInstruction.Call) {
                    val entryPoint = inst.entryPoint
                    if (entryPoint != null) {
                        res.add(Label.Address(entryPoint))
                    }
                }
                i++
            }
            return res
        }
    }

    /**
     *  Where conditional jumps are located, it adds Assume instructions in the target blocks.
     *  The jump instructions are kept because they need to be in the TAC program.
     *  Pre-condition:  verify() didn't throw an exception
     **/
    fun lowerBranchesIntoAssume() {
        fun processBranch(src: SbfBasicBlock, dst: MutableSbfBasicBlock, cond: Condition): CFGEdge? {
            // we know already there is a CFG edge from src to dst
            return if (dst.getPreds().size == 1) {
                // no need to add an extra basic block to model the control-flow edge
                dst.add(0, SbfInstruction.Assume(cond, metaData = MetaData(SbfMeta.LOWERED_ASSUME())))
                null
            } else {
                // we need to add an extra basic block to model the control-flow edge
                CFGEdge(src.getLabel(), dst.getLabel(), cond)
            }
        }

        val newBlocks: MutableSet<Pair<CFGEdge, MetaData>> = mutableSetOf()
        for ((_,curBlock) in blocks) {
            val terminatorInst = curBlock.getInstruction(curBlock.numOfInstructions()-1)
            if (terminatorInst is SbfInstruction.Jump.ConditionalJump) {
                val trueSucBlock = blocks[terminatorInst.target]
                val falseSucBlock = blocks[terminatorInst.falseTarget]
                check(trueSucBlock == null || falseSucBlock == null || trueSucBlock.getLabel() != falseSucBlock.getLabel())

                if (trueSucBlock != null) {
                    val trueCond = terminatorInst.cond
                    val edge = processBranch(curBlock, trueSucBlock, trueCond)
                    if (edge != null) {
                        newBlocks.add(edge to terminatorInst.metaData)
                    }
                }

                if (falseSucBlock != null) {
                    val cond = terminatorInst.cond
                    val negCond = cond.copy(op = cond.op.negate())
                    val edge = processBranch(curBlock, falseSucBlock, negCond)
                    if (edge != null) {
                        newBlocks.add(edge to terminatorInst.metaData)
                    }
                }
            }
        }
        // Add extra blocks to model CFG edges
        for (edgeAndMeta in newBlocks) {
            val edge = edgeAndMeta.first
            val src = edge.src
            val dst = edge.dst
            val cond = edge.cond

            val srcB = blocks[src]
            val dstB = blocks[dst]
            check(srcB != null)
            check(dstB != null)

            val newLabel = src.refresh()
            val newBlock = getOrInsertBlock(newLabel)
            // add terminator in newBlock to jump to dst
            newBlock.add(SbfInstruction.Assume(cond, metaData = MetaData(SbfMeta.LOWERED_ASSUME())))
            newBlock.add(SbfInstruction.Jump.UnconditionalJump(dst))
            // fix the terminator in src to jump to newBlock instead of dst
            val terminatorSrc = srcB.removeAt(srcB.numOfInstructions()-1) as SbfInstruction.Jump.ConditionalJump
            check(terminatorSrc.target == dst || terminatorSrc.falseTarget == dst)
            if (terminatorSrc.target == dst) {
                srcB.add(SbfInstruction.Jump.ConditionalJump(
                        terminatorSrc.cond, newBlock.getLabel(), terminatorSrc.falseTarget, edgeAndMeta.second))
            } else {
                srcB.add(SbfInstruction.Jump.ConditionalJump(
                        terminatorSrc.cond, terminatorSrc.target, newBlock.getLabel(), edgeAndMeta.second))
            }
            srcB.removeSucc(dstB)
            srcB.addSucc(newBlock)
            newBlock.addSucc(dstB)
        }
    }

    /**
     *  Rename IN-PLACE all block labels.
     *  @return the labeling map (old labels to new labels)
     */
    fun renameLabels(): Map<Label,Label> {
        val labelMap : MutableMap<Label, Label> = mutableMapOf()
        val normCFG = MutableSbfCFG(name)
        for (label in blocks.keys) {
            val normLabel = label.refresh()
            labelMap[label] = normLabel
            normCFG.getOrInsertBlock(normLabel)
        }

        fun getNormBlock(block: SbfBasicBlock): MutableSbfBasicBlock {
            check(blocks.containsKey(block.getLabel()))
            val normLabel = labelMap[block.getLabel()]
            check(normLabel != null)
            val normBlock = normCFG.blocks[normLabel]
            check(normBlock != null)
            return normBlock
        }

        for (block in blocks.values) {
            val normBlock = getNormBlock(block)
            normBlock.addAll(block.getInstructions())
            normBlock.replaceJumpTargets(labelMap)
            for (succ in block.getSuccs()) {
                val normSuccBlock = getNormBlock(succ)
                normBlock.addSucc(normSuccBlock)
            }
        }

        if (entry != null) {
            normCFG.entry = getNormBlock(entry!!)
        }
        if (exit != null) {
            normCFG.exit = getNormBlock(exit!!)
        }

        this.entry = normCFG.entry
        this.exit  = normCFG.exit
        this.blocks = normCFG.blocks
        return labelMap
    }


    /**
     * Transform
     * ```
     *  bb:
     *     0
     *     ..
     *     i
     *     i+1
     *     ...
     *     goto Cont
     * ```
     *  into
     * ```
     *   bb:
     *     0
     *     ..
     *     i
     *     goto bb'
     *   bb':
     *    i+1
     *    ...
     *    goto Cont
     * ```
     * and return `bb'`
     */
    fun splitBlock(label: Label, index: Int): MutableSbfBasicBlock {
        val b = getMutableBlock(label)
        check(b != null) { "splitBlock failed because $label is not a label in the CFG"}
        val newB = getOrInsertBlock(b.getLabel().refresh())

        // copy all instructions after index into newB
        for ((i, inst) in b.getInstructions().withIndex()) {
            if (i > index) {
                newB.add(inst)
            }
        }
        // remove all instructions after index in b
        b.removeAfter(index)

        // transfer successors from b to newB
        newB.copySuccs(b)
        b.removeSuccs()
        // connect b with newB
        b.add(SbfInstruction.Jump.UnconditionalJump(newB.getLabel()))
        b.addSucc(newB)

        if (hasExit() && getExit().getLabel() == b.getLabel()) {
            setExit(newB)
        }

        return newB
    }


    /** This function might add a new basic block.
     *  For that, it needs to know which next available label can be used.
     *  It ensures that the CFG has a specific shape that makes easier subsequent analyses:
     *  - entry block does not have predecessor
     *  - one single exit block
     *
     *  Note that this function does not guarantee that the exit block is reachable from the entry
     **/
    fun normalize() {
        check(entry != null){"CFG without entry block"}
        val entryB = entry!!
        if (entryB.getPreds().isNotEmpty()) {
            // Add dummy entry block
            val dummyEntryBlock = getOrInsertBlock(entryB.getLabel().refresh())
            dummyEntryBlock.add(SbfInstruction.Jump.UnconditionalJump(entryB.getLabel()))
            dummyEntryBlock.addSucc(entryB)
            entry = dummyEntryBlock
        }

        // Ensure that there is exactly one block with an Exit instruction
        val newExitBB = getOrInsertBlock(Label.fresh())
        newExitBB.add(SbfInstruction.Exit())
        exit = newExitBB

        // Redirect all blocks with exit instruction to the new exit block
        for (bb in blocks.values) {
            if (bb.getLabel() == newExitBB.getLabel()) {
                continue
            }
            bb.getInstructions().forEachIndexed { i, inst ->
                if (inst is SbfInstruction.Exit) {
                    bb.replaceInstruction(i,  SbfInstruction.Jump.UnconditionalJump(newExitBB.getLabel()))
                    bb.removeSuccs()
                    bb.addSucc(newExitBB)
                }
            }
        }

        // Redirect all must-fail blocks to the new exit block
        for (bb in blocks.values) {
            var i = 0
            while (i < bb.numOfInstructions()) {
                val inst = bb.getInstruction(i)
                if (inst.isAbort()) {
                    // 1. Remove all instructions after inst
                    var j = 0
                    val n = bb.numOfInstructions() - i - 1
                    while (j < n) {
                        bb.removeAt(bb.numOfInstructions()-1)
                        j++
                    }
                    // 2. Make the exit block to be a successor of bb
                    bb.add(SbfInstruction.Jump.UnconditionalJump(newExitBB.getLabel()))
                    bb.removeSuccs()
                    bb.addSucc(newExitBB)
                    break
                }
                i++
            }
        }
    }

    fun removeUnreachableBlocks() {
        class ReachableBlocksVisitor(val reachable: MutableSet<Label>): DfsVisitAction {
            override fun applyBeforeChildren(b: SbfBasicBlock)
            { reachable.add(b.getLabel()) }
            override fun applyAfterChildren(b: SbfBasicBlock) {}
            override fun skipChildren(b: SbfBasicBlock): Boolean { return false}
        }

        val reachable = mutableSetOf<Label>()
        val vis = ReachableBlocksVisitor(reachable)
        dfsVisit(getEntry(), vis)
        val deadBlocks = mutableSetOf<Label>()
        for (block in blocks.values) {
            if (!(reachable.contains(block.getLabel()))) {
                deadBlocks.add(block.getLabel())
            }
        }
        for (block in deadBlocks) {
            removeBlock(block)
        }
    }

    // DCE is pretty simple here. We just look for abort-like calls and remove code after.
    fun deadCodeElimination() {
        class MarkFailureBlocks(val failureBlocks: MutableSet<Label>): DfsVisitAction {
            override fun applyBeforeChildren(b: SbfBasicBlock) {
                if (b.getInstructions().any { inst -> inst.isAbort()}) {
                    failureBlocks.add(b.getLabel())
                }
            }
            override fun applyAfterChildren(b: SbfBasicBlock) {}
            override fun skipChildren(b: SbfBasicBlock): Boolean {
                return failureBlocks.contains(b.getLabel())
            }
        }

        /**
         * We could analyze the body of the called function but `simplify` will be called eventually with all inlined functions.
         */
        fun mayCallAssertOrSatisfy(inst: SbfInstruction): Boolean {
            return (inst.isAssertOrSatisfy() ||
                when (inst) {
                    is SbfInstruction.Call -> !inst.isExternalFn()
                    else -> false
                })
        }

        fun removeBlock(b: MutableSbfBasicBlock) {
            val mayCallAssertOrSatisfy = b.getInstructions().any { mayCallAssertOrSatisfy(it) }
            if (!mayCallAssertOrSatisfy) {
                // If the block does not have an assert instruction then we can remove the whole block,
                // including any instruction before the abort instruction.
                if (b.getPreds().size == 1) {
                    val pred = b.getMutablePreds().first()
                    if (b.getLabel() != pred.getLabel()) {
                        val terminatorInst = pred.getInstruction(pred.numOfInstructions() - 1)
                        if (terminatorInst is SbfInstruction.Jump.ConditionalJump) {
                            val continuation = if (b.getLabel() == terminatorInst.target) {
                                check(terminatorInst.falseTarget != null)
                                { "$terminatorInst does not have a false target" }
                                terminatorInst.falseTarget
                            } else {
                                terminatorInst.target
                            }
                            pred.replaceInstruction(
                                pred.numOfInstructions() - 1,
                                SbfInstruction.Jump.UnconditionalJump(continuation)
                            )
                            pred.removeSucc(b)
                            removeBlock(b.getLabel())
                            return
                        }
                        // Note that we should fold b into pred and continue
                        // We call later mergeBlocks() which should do that.
                    }
                }
            }

            // Remove everything after the abort-like call
            val i = b.getInstructions().indexOfFirst { inst -> inst.isAbort() }
            check(i >= 0) {"cannot call removeBlock without having a call to abort"}

            // Remove all after index i
            var elementsToRemove = b.numOfInstructions()  - i - 1
            while (elementsToRemove > 0) {
                b.removeAt(b.numOfInstructions()-1)
                elementsToRemove--
            }
            b.removeSuccs()
        } // end removeBlock function

        val failureBlocks = mutableSetOf<Label>()
        val vis = MarkFailureBlocks(failureBlocks)
        dfsVisit(getEntry(), vis)
        for (label in failureBlocks) {
            val b = blocks[label]
            check(b != null) {"cannot found $label in CFG"}
            removeBlock(b)
        }
    }

    fun mergeBlocks() {
        val worklist = ArrayList<Label>(blocks.size)
        worklist.addAll(blocks.keys)

        while (worklist.isNotEmpty()) {
            val curL = worklist.removeLast()
            val curB = blocks[curL]
            check(curB != null) {"cannot find block associated to $curL in mergeBlocks()"}
            // skip curB for now, but it will be folded into its parent when
            // the parent is processed.
            if (curB.getPreds().size == 1) {
                val parent = curB.getPreds().first()
                if (parent.getSuccs().size == 1) {
                    continue
                }
            }
            while (curB.getSuccs().size == 1) {
                val succB = curB.getMutableSuccs().first()
                // skip self-loops or if succB  has in-degree > 1
                if (succB == curB || succB.getPreds().size  != 1) {
                    break
                }

                if (exit != null && succB.getLabel() ==  exit!!.getLabel()) {
                    exit  = curB
                }

                // fold succB into curB
                check(curB.numOfInstructions()  > 0) {"block has to have at least a terminator instruction"}
                curB.removeAt(curB.numOfInstructions()-1) // remove the terminator instruction in curB
                curB.addAll(succB.getInstructions())
                curB.removeSucc(succB)
                for (succSuccB in succB.getMutableSuccs()) {
                    curB.addSucc(succSuccB)
                }
                succB.clear()
                removeBlock(succB.getLabel())
                worklist.remove(succB.getLabel())
            }
        }
    }

    // Remove empty blocks (only jump instruction) with only one successor
    fun removeEmptyBlocks() {
        val worklist = ArrayList<MutableSbfBasicBlock>(blocks.size)
        worklist.addAll(blocks.values)
        while (worklist.isNotEmpty()) {
            val curB = worklist.removeLast()

            // The block has only one terminator instruction
            if (!(curB.getSuccs().size == 1 && curB.numOfInstructions() == 1)) {
                continue
            }

            // The `curB` has only an unconditional jump to its only successor
            val onlySuccB = curB.getMutableSuccs().single()

            // This is an empty loop: we don't touch it.
            if (curB == onlySuccB) {
                continue
            }

            // Copy predecessors to avoid invalidating iterators
            val predecessors = ArrayList<MutableSbfBasicBlock>(curB.getPreds().size)
            predecessors.addAll(curB.getMutablePreds())
            // Redirect each predB predecessor to succB
            for (predB in predecessors) {
                val predTermInst = predB.getTerminator()
                check(predTermInst is SbfInstruction.Jump) {
                    "$predTermInst should be a jump instruction in removeEmptyBlocks"
                }
                when (predTermInst) {
                    is SbfInstruction.Jump.UnconditionalJump -> {
                        predB.replaceInstruction(
                            predB.numOfInstructions() - 1,
                            predTermInst.copy(target = onlySuccB.getLabel())
                        )
                    }
                    is SbfInstruction.Jump.ConditionalJump -> {
                        predB.replaceInstruction(
                            predB.numOfInstructions() - 1,
                            if (predTermInst.target == curB.getLabel()) {
                                predTermInst.copy(target = onlySuccB.getLabel())
                            } else {
                                predTermInst.copy(falseTarget = onlySuccB.getLabel())
                            }
                        )
                    }
                }
                predB.addSucc(onlySuccB)
                // No need to remove curB as successor of predB because it will be done when calling removeBlock
            }
            curB.clear()
            removeBlock(curB.getLabel())
        }
    }

    /**
     * This is removal of dead code.
     * We try to remove all instructions after the last assertion to avoid problems with the pointer analysis in case
     * there is some dead pointer de-reference that cannot be typed.
     *
     * IMPORTANT: this transformation is only sound after inlining has taken place.
     **/
    fun removeAfterLastAssert() {
        fun getAbortAfter(b: SbfBasicBlock, i: Int): SbfInstruction? {
            for (locInst in b.getLocatedInstructions()) {
                if (locInst.pos > i) {
                    if (locInst.inst.isAbort()) {
                        return locInst.inst
                    }
                }
            }
            return null
        }
        for (block in blocks.values) {
            if (block.getSuccs().isEmpty()) {
                val indexOfLastAssert = block.getInstructions().indexOfLast { it.isAssertOrSatisfy() }
                // This block does not have successor but after normalization it might have an edge to the exit block
                // If the block had an abort instruction it is important to keep it.
                if (indexOfLastAssert != -1) {
                    val abortInst  = getAbortAfter(block, indexOfLastAssert)
                    block.removeAfter(indexOfLastAssert)
                    if (abortInst != null) {
                        block.add(abortInst) // we put it back the abort instruction
                    }
                    // we put an exit instruction but normalization might remove it and redirect it to another exit block
                    block.add(SbfInstruction.Exit())
                }
            }
        }
    }

    /**
     * Remove unreachable blocks from entry, reduce the number of basic blocks.
     * It also performs some transformations that might help subsequent static analyses.
     **/
    fun simplify(globals:  GlobalVariables) {
        removeUnreachableBlocks()
        deadCodeElimination()
        removeUnreachableBlocks() // DCE can produce many unreachable blocks
        verify(false, "after dce + remove unreachable blocks")
        if (SolanaConfig.EnableRemovalOfCFGDiamonds.get()) {
            removeCFGDiamonds(this)
            verify(false, "after removeCFGDiamonds")
            mergeBlocks()
            removeUnreachableBlocks()
            verify(false, "after cleanup of removeCFGDiamonds")
        }
        runSimplePTAOptimizations(this, globals)
        verify(false, "after simple PTA optimizations")
    }

    /**
     * Verify that the cfg is well-formed.
     *
     * If [checkHasOneExit] is enabled then it checks that the cfg has exactly one basic block
     * with an exit instruction. This property should always hold after calling [normalize].
     * If the original cfg did not have an exit instruction, [normalize] will create a new block
     * with an exit instruction, but it will be unreachable from the cfg's entry.
     */
    override fun verify(checkHasOneExit: Boolean, msg: String) {
        fun consistentSuccsAndPreds(b: SbfBasicBlock) {
            for (succ in b.getSuccs()) {
                if (!succ.getPreds().any {it.getLabel() == b.getLabel()}) {
                    throw CFGVerifyError("$name has two blocks ${b.getLabel()} and successor ${succ.getLabel()} " +
                                         "which are not consistent wrt their succs and preds sets $msg")
                }
            }
            for (pred in b.getPreds()) {
                if (!pred.getSuccs().any {it.getLabel() == b.getLabel()}) {
                    throw CFGVerifyError("$name has two blocks ${b.getLabel()} and predecessor ${pred.getLabel()} " +
                                         "which are not consistent wrt their preds and succs sets $msg")
                }
            }
        }

        if (entry == null) {
            throw CFGVerifyError("$name missing entry block $msg")
        }
        if (checkHasOneExit && exit == null) {
            throw CFGVerifyError("$name missing exit block $msg\n$this")
        }

        if (exit != null) {
            exit?.let { exitB ->
                if (!exitB.getInstructions().any {it is SbfInstruction.Exit || it.isAbort()}) {
                    var selfLoop = false
                    if (exitB.getSuccs().size == 1) {
                        val exitSucc = exitB.getSuccs()[0]
                        if (exitSucc.getLabel() == exitB.getLabel()) {
                            selfLoop = true
                        }
                    }
                    if (!selfLoop) {
                        throw CFGVerifyError("$name has an exit block\n${exitB}\nwithout an exit or abort instruction $msg")
                    }
                }
            }
        }

        for ((k,v) in blocks) {
            consistentSuccsAndPreds(v)
            // check that all predecessors and successors are in the same cfg
            v.getPreds().forEach {
                if (!blocks.contains(it.getLabel())) {
                    throw CFGVerifyError("$k is a block in $name but its predecessor ${it.getLabel()} is not")
                }
            }
            v.getSuccs().forEach {
                if (!blocks.contains(it.getLabel())) {
                    throw CFGVerifyError("$k is a block in $name but its successor ${it.getLabel()} is not")
                }
            }

            val lastInst = v.getInstruction(v.numOfInstructions()-1)
            val hasTerminator = lastInst.isTerminator()
            if (!hasTerminator) {
                throw CFGVerifyError("$name has a basic block $k without terminator $msg")
            }
            var numOfTerminators = 0
            var numOfSuccessors = 0
            var numOfJumps = 0
            for (inst in v.getInstructions()) {
                if (inst.isTerminator()) {
                    numOfTerminators++
                    if (inst is SbfInstruction.Jump) {
                        numOfJumps++
                        if (inst is SbfInstruction.Jump.UnconditionalJump) {
                            numOfSuccessors = 1
                            if (!blocks.containsKey(inst.target)) {
                                throw CFGVerifyError("$name has an undefined unconditional jump target at block $k $msg")
                            }
                        } else {
                            check(inst is SbfInstruction.Jump.ConditionalJump)
                            numOfSuccessors = 2
                            if (!blocks.containsKey(inst.target)) {
                                throw CFGVerifyError("$name has an undefined conditional jump (true) target at block $k $msg")
                            }
                            if (!blocks.containsKey(inst.falseTarget)) {
                                throw CFGVerifyError("$name has an undefined conditional jump (false) target at block $k $msg")
                            }
                        }
                    }
                }
            }

            if (numOfJumps > 1) {
                throw CFGVerifyError("$name has a basic block $v must have at most one jump instruction $msg")
            }
            if (numOfSuccessors != v.getSuccs().size) {
                throw CFGVerifyError("$name has a basic block $k with an unexpected number of outgoing edges." +
                                     " #out-edges=${v.getSuccs().size} #expected-out-edges=$numOfSuccessors $msg")
            }
            // We don't enforce the number of terminators to be exactly once because "abort"
            // can be followed by a jump instruction.
            if (numOfTerminators == 0) {
                throw CFGVerifyError("$name has a basic block $v must have at least one terminator instruction $msg")
            }
        }
    }

    /** Dump the CFG into a string by traversing the CFG in depth-first search **/
    override fun toString() : String {
        class BasicBlockPrinterVisitor(val sb: StringBuilder): DfsVisitAction {
            override fun applyBeforeChildren(b: SbfBasicBlock) {
                sb.append("$b\n")
            }
            override fun applyAfterChildren(b: SbfBasicBlock) {}
            override fun skipChildren(b: SbfBasicBlock): Boolean { return false}
        }
        val sb = StringBuilder()
        val vis = BasicBlockPrinterVisitor(sb)
        sb.append("function $name\n")
        dfsVisit(getEntry(), vis)
        return sb.toString()
    }

    // Kept for debugging
    /*
    fun cfgStructureToString(): String {
        val sb = StringBuilder()
        for (kv in blocks) {
            val label = kv.key
            val bb = kv.value
            check(label == bb.label)
            sb.append("${label}: ")
            sb.append("PREDS=[")
            for (pred in bb.getPreds()) {
                sb.append("${pred.label} ")
            }
            sb.append("] SUCCS=[")
            for (succ in bb.getSuccs()) {
                sb.append("${succ.label} ")
            }
            sb.append("]\n")
        }
        return sb.toString()
    }
     */

    override fun toDot(annotations: (SbfBasicBlock) -> Pair<String?, Boolean?>,
                       colorMap: Map<LocatedSbfInstruction, DotColor>): String {
        class BasicBlockToDot(val sb: StringBuilder): DfsVisitAction {

            override fun applyBeforeChildren(b: SbfBasicBlock) {

                fun blockToId(b: SbfBasicBlock): Int {
                    return System.identityHashCode(b)
                }

                fun instToDot(locInst: LocatedSbfInstruction, sb:StringBuilder) {
                    val color = colorMap[locInst] ?: (locInst.inst as? SbfInstruction.Debug)?.let { "Cyan" }

                    @Suppress("ForbiddenMethodCall")
                    locInst.inst.toString().split("\n").forEach {instLine ->
                        if (color != null) {
                            sb.append("<TR><TD ALIGN=\"LEFT\" BGCOLOR=\"$color\">")
                        } else {
                            sb.append("<TR><TD ALIGN=\"LEFT\">")
                        }
                        sb.append(instLine
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                            .replace("{", "&#123;")
                            .replace("}", "&#125;")
                        )
                        sb.append("</TD></TR>")
                    }
                }

                fun blockToDot(b: SbfBasicBlock, sb:StringBuilder) {
                    val label = b.getLabel()
                    sb.append("<<TABLE BORDER=\"0\" ALIGN=\"LEFT\" CELLBORDER=\"0\" CELLPADDING=\"0\" CELLSPACING=\"0\">")
                    sb.append("<TR><TD ALIGN=\"LEFT\">$label:</TD></TR>")
                    // Filter out Debug instructions (unless option is enabled) - Debug instruction carry meta debug information.
                    val instToPrint = b.getLocatedInstructions().filter { SolanaConfig.DumpDwarfDebugInfoInReports.get() || it.inst !is SbfInstruction.Debug }
                    for (locInst in instToPrint) {
                        instToDot(locInst, sb)
                    }
                    sb.append("</TABLE>>")
                }


                sb.append("Node${blockToId(b)} ")
                sb.append("[shape=record,label=")
                blockToDot(b, sb)
                sb.append("];\n")

                val (annotation, embedGraph) = annotations(b)
                if (annotation != null) {
                    if (!embedGraph!!) {
                        sb.append("\tNodeAnnotation${blockToId(b)} ")
                        sb.append("[style=\"filled\",fillcolor=cornsilk,label=\"$annotation\"];\n")
                    } else {
                        sb.append("subgraph cluster_${b.getLabel()} {\n")
                        sb.append("label=\"cluster_${b.getLabel()}\"\n")
                        sb.append("graph [center=true,ratio=true,bgcolor=lightcyan,fontname=Helvetica,minlen=0];\n")
                        sb.append("$annotation\n")
                        sb.append("}\n")
                    }
                }
                for (succ in b.getSuccs()) {
                    sb.append("\tNode${blockToId(b)} -> Node${blockToId(succ)};\n")
                }
                if (annotation != null) {
                    sb.append(if (!embedGraph!!) {
                        "\tNodeAnnotation${blockToId(b)} -> Node${blockToId(b)}  [arrowhead= diamond];\n"
                    } else {
                        "Node${blockToId(b)} -> Node${b.getLabel()}_ENTRY [style=dashed];\n"
                    })
                }
            }
            override fun applyAfterChildren(b: SbfBasicBlock) {}
            override fun skipChildren(b: SbfBasicBlock): Boolean { return false}
        }

        val sb = StringBuilder()
        sb.append("digraph \"CFG for \'$name\' function\" {\n")
        sb.append("\tlabel=\"CFG for \'$name\' function\";\n")
        sb.append("rank=same;\n")
        val vis = BasicBlockToDot(sb)
        dfsVisit(getEntry(), vis)
        sb.append("}\n")
        return sb.toString()
    }

    override fun getStats(): CFGStats {
        var numOfInsts = 0
        var numOfSyscalls = 0
        var numOfInternalCalls = 0
        var numOfMemInsts = 0
        for (bb in blocks.values) {
            for (inst in bb.getInstructions()) {
                numOfInsts++
               if (inst is SbfInstruction.Call) {
                   if (inst.isExternalFn()) {
                        numOfSyscalls++
                   } else {
                       numOfInternalCalls++
                   }
               } else if (inst is SbfInstruction.Mem) {
                   numOfMemInsts++
               }
            }
        }
        return  CFGStats(blocks.size, numOfInsts,
                        InstructionsStats(numOfSyscalls, numOfInternalCalls, numOfMemInsts))
    }

    fun addDebugInformation(debugInformation: DebugSymbols) {
        this.getEntry().getInstruction(0).metaData.getVal(SbfMeta.SBF_ADDRESS)?.let {addr ->
            val debugInfoForMethod = debugInformation.getMethodByNameAndAddress(this.name, addr)
            if(debugInfoForMethod != null) {
                DWARFEdgeLabelAnnotator(debugInformation, debugInfoForMethod, this, addr).addDebugInformation()
            }
        }
    }
}


data class InstructionsStats(val numOfSyscalls:Int = 0, val numOfInternalCalls: Int = 0, val numOfMemInsts: Int = 0) {
    fun add(other: InstructionsStats): InstructionsStats {
        return InstructionsStats(numOfSyscalls + other.numOfSyscalls,
                              numOfInternalCalls + other.numOfInternalCalls,
                                numOfMemInsts + other.numOfMemInsts)
    }
    override fun toString(): String {
        return "Number of syscalls=${numOfSyscalls}\n" +
               "Number of calls=${numOfInternalCalls}\n" +
               "Number of memory instructions=${numOfMemInsts}\n"
    }
}
data class CFGStats(val numOfBlocks: Int = 0, val numOfInsts: Int = 0, val instStats: InstructionsStats = InstructionsStats()) {
    fun add(other: CFGStats): CFGStats {
        return CFGStats(numOfBlocks + other.numOfBlocks,
                        numOfInsts + other.numOfInsts,
                        instStats.add(other.instStats))
    }
    override fun toString(): String {
        return  "Number of blocks=${numOfBlocks}\n" +
                "Number of instructions=${numOfInsts}\n" + instStats.toString()
    }
}


interface DfsVisitAction {
    fun applyBeforeChildren(b: SbfBasicBlock)
    fun applyAfterChildren(b: SbfBasicBlock)
    fun skipChildren(b: SbfBasicBlock): Boolean
}

interface DfsVisitMutableAction {
    fun applyBeforeChildren(b: MutableSbfBasicBlock)
    fun applyAfterChildren(b: MutableSbfBasicBlock)
    fun skipChildren(b: SbfBasicBlock): Boolean
}

fun dfsVisit(entry: SbfBasicBlock, f: DfsVisitAction) {
    val stack =  ArrayDeque<SbfBasicBlock>()
    val visited = mutableSetOf<Label>()
    stack.addLast(entry)
    visited.add(entry.getLabel())
    while (!stack.isEmpty()) {
        val curBlock = stack.last()
        stack.removeLast()
        f.applyBeforeChildren(curBlock)
        if (!f.skipChildren(curBlock)) {
            for (succBlock in curBlock.getSuccs()) {
                if (visited.add(succBlock.getLabel())) {
                    stack.addLast(succBlock)
                }
            }
        }
        f.applyAfterChildren(curBlock)
    }
}

fun dfsVisit(entry: MutableSbfBasicBlock, f: DfsVisitMutableAction) {
    val stack =  ArrayDeque<MutableSbfBasicBlock>()
    val visited = mutableSetOf<Label>()
    stack.addLast(entry)
    visited.add(entry.getLabel())
    while (!stack.isEmpty()) {
        val curBlock = stack.last()
        stack.removeLast()
        f.applyBeforeChildren(curBlock)
        if (!f.skipChildren(curBlock)) {
            for (succBlock in curBlock.getMutableSuccs()) {
                if (visited.add(succBlock.getLabel())) {
                    stack.addLast(succBlock)
                }
            }
        }
        f.applyAfterChildren(curBlock)
    }
}
