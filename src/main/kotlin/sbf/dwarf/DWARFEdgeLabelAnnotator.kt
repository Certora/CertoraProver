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

package sbf.dwarf

import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import config.Config
import config.DebugAdapterProtocolMode
import datastructures.stdcollections.*
import dwarf.*
import kotlinx.serialization.Serializable
import log.*
import report.checkWarn
import sbf.cfg.*
import sbf.disassembler.Label
import sbf.disassembler.SbfRegister
import utils.*
import vc.data.CoreTACProgram
import vc.data.SnippetCmd
import vc.data.TACMeta
import vc.data.tacexprutil.*

val logger = Logger(LoggerTypes.SBF_DEBUG_ANNOTATOR)

private typealias CallStack = List<CallStackLevel>
private typealias InsPtr = Pair<Label, Int>


/**
 * This class is responsible for generating [DWARFCfgEdgeLabel] into the [MutableSbfCFG].
 *
 * A [DWARFCfgEdgeLabel] either represents start and end of functions (and hereby allow reconstructing the call stack)
 * or it represents relevant information for variables. I.e. where a store into or a load from a source code variable occurs.
 *
 * How it works:
 *
 * DWARF debug information contains tree formed of Debugging Information Entries (DIEs).
 *
 * The level in which the DIE occurs in the three matches the inlining depth. Example: If there is a method
 * foo(){
 *    bar()
 * }
 *
 * bar(){
 *    baz()
 *    bax()
 * }
 * where foo is a subprogram (or subroutine) and all methods bar, baz, bax are fully inlined, then the DWARF tree
 * represents:
 *
 * foo
 * |-- bar
 *     |-- baz
 *     |-- bax
 *
 * The algorithm traverses each edge of the CFG with once and for each edge [currIns / currAddr] -> [succIns / succAddrs],
 * and it computes the delta between the tree at currIns and at succIns.
 *
 * For the call stack:
 * Hereby, the delta is composed by pop [DWARFCfgEdgeLabel.ScopeStart] and push operations [DWARFCfgEdgeLabel.ScopeEnd].
 * These scopes create the call stack. For the construction [getEdgeAnnotationsWhenAddressChanges].
 *
 * Then the variables are added at each [CallStackLevel].
 *
 * For the variables:
 * When each statement is visited, a check is performed if either [currAddr] -> [succAddr] makes a variable live, that is
 * the debug information (a DIE node with DW_TAG_variable or DW_TAG_formal) states that [currAddr] is not in variable's range, but [succAddr].
 * Additionally, for the instruction [SbfInstruction.Bin] and [SbfInstruction.Mem] that use variables, there is a check performed whether
 * a variable that is live (according to DWARF at [currAddr]) stores or loads a register used at the instruction. If that's the case
 * ta [DWARFCfgEdgeLabel.VariableOffsetAccess] is added (with the respective offset in case of a mem load or store).
 *
 * For details see [getEdgeAnnotationsAddressRemainsUnchanged] and [getEdgeAnnotationsWhenAddressChanges]
 *
 * Eventually, after computing the edge labels, the algorithm inserts [SbfInstruction.Debug] statement with [MetaData].
 * Note, that this algorithm also inserts additional blocks as of [SbfInstruction.Jump] instruction with two targets
 * where the information along any of the two targets might differ (i.e. a scope could be closed along one edge but wasn't along the other).
 * See [mutateInterCfg] and [mutateInterCfg].
 */

private const val ALIGNMENT = 8;

class DWARFEdgeLabelAnnotator(private val debugInformation: DebugSymbols, private val debugNodeOfSubProgram: Subprogram, private val input: MutableSbfCFG, val entryAddr: ULong) {
    private val scopes: MutableMap<ULong, MutableSet<DwarfMethod>> = mutableMapOf()
    private val insToAddr: MutableMap<SbfInstruction, ULong> = mutableMapOf()
    private val callStackCache = mutableMapOf<ULong, CallStack>()

    /**
     * Computes the frame base pointer - we assume that this is the minimal access to the
     * stack pointer.
     *
     * The frame base pointer is not availabel in DWARF debug information for extraction, we apply a
     * heuristic here, that is extracting the minimal value of all offset access on r10. Then this value must be
     * rounded down to the next multiple of ALIGNMENT.
     */
    private val frameBasePointerOffset = run {
        val frameBasePointer = input.getBlocks()
                .flatMap {
                    it.value.getInstructions().filterIsInstance<SbfInstruction.Mem>()
                        .mapNotNull {
                            if (it.access.baseReg.r == SbfRegister.R10_STACK_POINTER) {
                                it.access.offset.toInt()
                            } else {
                                null
                            }
                        }
                }.minOrNull()?.roundDownToMultipleOf(ALIGNMENT)
        frameBasePointer?.toLong()
    }

    private fun getCallStackAtLocationSortedByDepth(curr: ULong): CallStack {
        return callStackCache.getOrPut(curr) {
            val res = mutableListOf<CallStackLevel>()
            scopes.values.forEach { e ->
                val filtered = e.filterToSet { it.isInRanges(curr) }
                if (filtered.isNotEmpty()) {
                    res.add(CallStackLevel.create(filtered, this.frameBasePointerOffset))
                }
            }
            res.add(CallStackLevel.create(setOf(debugNodeOfSubProgram), this.frameBasePointerOffset))
            res.sortedBy { it.level }
        }.also {
            check(it.isNotEmpty()) { "Call stack is empty" }
        }
    }

    data class WorklistElement(val currInsPtr: InsPtr, val lastSeenAddr: ULong, val nextInsPtr: InsPtr) {
        init {
            check(nextInsPtr.second == 0 ||
                (currInsPtr.first == nextInsPtr.first && (currInsPtr.second + 1 == nextInsPtr.second))
            ) {
                "Expecting successor to be either in the same basic block and the nextIns ptr must be " +
                    "or the are in different block and the next instruction must be a first instruction of the basic block."
            }
        }
    }

    private fun CallStack.getTopEl(): CallStackLevel {
        return last()
    }

    fun addDebugInformation() {
        val start = System.currentTimeMillis()
        if (Config.CallTraceDebugAdapterProtocol.get() == DebugAdapterProtocolMode.DISABLED) {
            return
        }
        if (!debugNodeOfSubProgram.getDeclRange().isInSources()) {
            return
        }
        computeScopes()
        logger.debug { "Computed scopes for ${debugNodeOfSubProgram.getMethodName()} (total scopes: ${scopes.values.sumOf { it.size }})" }
        logger.debug { "Scopes levels: ${scopes.mapValues { it.value.size }}" }
        computeAddressesPerInstruction()
        logger.debug { "Computed addresses for all instructions of ${debugNodeOfSubProgram.getMethodName()}" }
        annotateCFGBlocks()
        logger.debug { "Computed intra-procedural debug information for ${debugNodeOfSubProgram.getMethodName()} (number of blocks: ${input.getMutableBlocks().values.size}, totalInstructions: ${input.getMutableBlocks().values.sumOf { it.getInstructions().size }})" }
        annotateCFGEdges()
        val end = System.currentTimeMillis()
        logger.debug { "Computed inter-procedural debug information for ${debugNodeOfSubProgram.getMethodName()} (inter-block-edges: ${input.getMutableBlocks().values.sumOf { it.getMutableSuccs().size }})" }
        TOTAL_ANNOTATOR_TIME += (end - start)
        logger.info { "Enriched CFG with debug information in ${(end - start) / 1000}s\" / total-time: ${TOTAL_ANNOTATOR_TIME / 1000}s for ${debugNodeOfSubProgram.getMethodName()}" }
        input.verify(false, "after annotating cfg with debug information")
    }

    private fun insertNewBasicBlockWithTarget(annotations: List<DWARFCfgEdgeLabel>, redirectedLabel: Label): MutableSbfBasicBlock {
        val newBlockLabel = Label.fresh()
        val newBlock = input.getOrInsertBlock(newBlockLabel)
        newBlock.add(annotations.toDwarfInstruction())
        newBlock.add(SbfInstruction.Jump.UnconditionalJump(redirectedLabel))
        return newBlock
    }

    private fun annotateCFGEdges() {

        val cfgEdgeToAnnotation = input.getBlocks().map { currEntry ->
            val currBB = currEntry.value
            val lastCurrIns = currBB.getInstructions().last()
            val labelToAnnotations = currBB.getSuccs().map { succBB ->
                val firstInsSucc = succBB.getInstruction(0);
                succBB.getLabel() to getEdges(lastCurrIns, firstInsSucc)
            }
            currBB.getLabel() to labelToAnnotations
        }

        cfgEdgeToAnnotation.forEach {
            val currBBLabel = it.first
            val currBB = input.getMutableBlock(currBBLabel)
            val lastCurrIns = currBB!!.getInstructions().last()
            val labelToAnnotations = it.second
            when (labelToAnnotations.size) {
                0 -> {
                    //Handling end of control flow.
                    val currStack = getCallStackAtLocationSortedByDepth(insToAddr[lastCurrIns]!!)
                    val toPopEndOfControlFlow = currStack.map { it.toFunctionEnd() }
                    currBB.add(currBB.getInstructions().lastIndex, toPopEndOfControlFlow.toDwarfInstruction())
                }

                1 -> {
                    require(lastCurrIns is SbfInstruction.Jump.UnconditionalJump || lastCurrIns is SbfInstruction.Jump.ConditionalJump)
                    val targetAndAnnotation = labelToAnnotations.first()
                    val target = targetAndAnnotation.first
                    val newBlock = insertNewBasicBlockWithTarget(targetAndAnnotation.second, target)
                    val newIns = when (lastCurrIns) {
                        is SbfInstruction.Jump.ConditionalJump -> {
                            check(lastCurrIns.falseTarget == null)
                            SbfInstruction.Jump.ConditionalJump(lastCurrIns.cond, newBlock.getLabel(), null, lastCurrIns.metaData)
                        }

                        is SbfInstruction.Jump.UnconditionalJump -> SbfInstruction.Jump.UnconditionalJump(newBlock.getLabel(), lastCurrIns.metaData)
                        else -> error("The structure of the CFG is broken, expecting a successor only when we have a conditional jump or an unconditional jump. Found ${lastCurrIns}")
                    }
                    val targetBlock = input.getMutableBlock(target)!!
                    currBB.removeSucc(targetBlock)
                    currBB.addSucc(newBlock)
                    newBlock.addSucc(targetBlock)

                    currBB.replaceInstruction(currBB.numOfInstructions() - 1, newIns)
                }

                2 -> {
                    require(lastCurrIns is SbfInstruction.Jump.ConditionalJump)

                    val target1 = labelToAnnotations[0]
                    val target2 = labelToAnnotations[1]
                    val oldTargetBlock1Label = lastCurrIns.target
                    val oldTargetBlock2Label = lastCurrIns.falseTarget ?: error("Expecting to have a false target.")

                    check(oldTargetBlock1Label == target1.first)
                    check(oldTargetBlock2Label == target2.first)
                    val oldTarget1 = input.getMutableBlock(oldTargetBlock1Label)!!
                    val oldTarget2 = input.getMutableBlock(oldTargetBlock2Label)!!

                    val newTarget1Block = insertNewBasicBlockWithTarget(target1.second, oldTargetBlock1Label)
                    val newTarget2Block = insertNewBasicBlockWithTarget(target2.second, oldTargetBlock2Label)

                    val newIns = SbfInstruction.Jump.ConditionalJump(lastCurrIns.cond, newTarget1Block.getLabel(), newTarget2Block.getLabel(), lastCurrIns.metaData)

                    currBB.removeSucc(oldTarget1)
                    currBB.removeSucc(oldTarget2)

                    currBB.addSucc(newTarget1Block)
                    currBB.addSucc(newTarget2Block)

                    newTarget1Block.addSucc(oldTarget1)
                    newTarget2Block.addSucc(oldTarget2)

                    currBB.replaceInstruction(currBB.numOfInstructions() - 1, newIns)
                }

                else -> error("Expecting at most two successors per block, found ${labelToAnnotations.size}")
            }

        }

        val intialCallStack = getCallStackAtLocationSortedByDepth(entryAddr)
        checkWarn(intialCallStack.all { it.scopes.size == 1 }) { "Expecting opening call stack at beginning of method to only have one opening at any level " }
        val openingAnnotations = intialCallStack.flatMap { it.toStartLabels(input.getMutableEntry().getInstruction(0), entryAddr, entryAddr) } + listOfNotNull(getExplicitDebugStep(entryAddr))
        if (openingAnnotations.isNotEmpty()) {
            input.getMutableEntry().add(0, openingAnnotations.toDwarfInstruction())
        }
    }

    private fun getEdges(current: SbfInstruction, successor: SbfInstruction): List<DWARFCfgEdgeLabel> {
        val currAddr = insToAddr[current]!!
        val succAddr = insToAddr[successor]!!
        return if (succAddr == currAddr) {
            getEdgeAnnotationsAddressRemainsUnchanged(current, currAddr);
        } else {
            getEdgeAnnotationsWhenAddressChanges(current, currAddr, succAddr);
        }.also { check(it.toSet().size == it.size){ "There is duplicated information in the labels ${it}"} }
    }

    private fun annotateCFGBlocks() {
        input.getMutableBlocks().forEachEntry { entry ->
            val bb = entry.value;
            val instructions = bb.getLocatedInstructions()
            check(instructions.dropLast(1).all { (it.inst !is SbfInstruction.Jump.ConditionalJump && it.inst !is SbfInstruction.Jump.UnconditionalJump) }) { "Expecting to see a jump instruction only as last statement of the block " }
            val toInsert = instructions.drop(1).fold(listOf<Pair<LocatedSbfInstruction, List<DWARFCfgEdgeLabel>>>(instructions[0] to emptyList())) { acc, curr ->
                val last = acc.last().first
                val annotations = getEdges(last.inst, curr.inst)
                acc + (curr to annotations)
            }.filter { it.second.isNotEmpty() }
            toInsert.reversed().forEach {
                bb.add(it.first.pos, it.second.toDwarfInstruction())
            }
        }
    }

    /**
     * Fills the [insToAddr] map so that every instruction has a unique address from the preceeding commands.
     */
    private fun computeAddressesPerInstruction() {
        val entryBlock = input.getMutableEntry();
        val entryIns = (entryBlock.getLabel() to 0)
        insToAddr[entryIns.getInstruction()] = entryAddr
        val seeds = entryIns.getSuccessorInstructions().map { succ -> WorklistElement(entryIns, entryAddr, succ) }
        object : VisitingWorklistIteration<WorklistElement, Unit, Boolean>() {
            override fun process(it: WorklistElement): StepResult<WorklistElement, Unit, Boolean> {
                check(it.nextInsPtr != it.currInsPtr) { "There shouldn't be any self-egdes" }

                val nextWorklistElements = mutableSetOf<WorklistElement>()
                val currInsPtr = it.nextInsPtr
                val succAddr = currInsPtr.getInstruction().getAddress() ?: it.lastSeenAddr
                check(insToAddr[currInsPtr.getInstruction()] == null || insToAddr[currInsPtr.getInstruction()] == succAddr) { "Expecting a unique address per statement, but found $succAddr and ${insToAddr[currInsPtr.getInstruction()]} for command ${currInsPtr}." }
                insToAddr[currInsPtr.getInstruction()] = succAddr
                val succsInstructions = currInsPtr.getSuccessorInstructions()
                succsInstructions.forEach { succInsPtr ->
                    nextWorklistElements.add(WorklistElement(currInsPtr, succAddr, succInsPtr))
                }
                return this.cont(nextWorklistElements)
            }

            override fun reduce(results: List<Unit>): Boolean {
                return true
            }

        }.submit(seeds)
    }

    private fun computeScopes() {
        debugNodeOfSubProgram.inlined_methods.forEach { inlinedMethod ->
            val relevantNode = !Config.CallTraceDebugAdapterProtocolOnlyInSources.get() || inlinedMethod.getRange().isInSources() || inlinedMethod.getDeclRange().isInSources()
            if (relevantNode) {
                scopes.getOrPut(inlinedMethod.getDepth()) { mutableSetOf() }
                    .add(inlinedMethod)
            }
        }
    }

    private fun getEdgeAnnotationsAddressRemainsUnchanged(currInsPtr: SbfInstruction, currAddr: ULong): List<DWARFCfgEdgeLabel> {
        val currStack = getCallStackAtLocationSortedByDepth(currAddr)
        return currStack.getTopEl().handleDirectMemoryAccess(currInsPtr, currAddr).toList()
    }

    private fun getEdgeAnnotationsWhenAddressChanges(currIns: SbfInstruction, currAddr: ULong, succAddr: ULong): List<DWARFCfgEdgeLabel> {
        check(succAddr != currAddr) { "Expecting to have two different addresses for currAddr and succAddr." }
        val currStack = getCallStackAtLocationSortedByDepth(currAddr)
        val succStack = getCallStackAtLocationSortedByDepth(succAddr)

        val commonSharedStack = currStack.zip(succStack)
            .takeWhile { it.first == it.second }
            .map { it.first }

        /**
         *  Close (pop) the call stacks on the current stack that are not in the shared stack.
         *  In revered order so that top most calls are closed first.
         */
        val toPop = currStack.drop(commonSharedStack.size).reversed()
            .map { callStackLevel ->
                callStackLevel.toEndLabels(currIns, currAddr, succAddr)
            }
        val variableInformationOnTop = commonSharedStack.getTopEl().handleVariablesOnStep(currIns, currAddr, succAddr)

        /**
         *  Open (push) the call stacks of the successor stack that are not in the shared stack
         */
        val toPush = succStack.drop(commonSharedStack.size)
            .map { callStackLevel ->
                callStackLevel.toStartLabels(currIns, currAddr, succAddr)
            }

        val nonePersistentStackChanges = getNonePersistentStackChanges(commonSharedStack, currIns, currAddr, succAddr)
        val currDebugStep = getExplicitDebugStep(currAddr)
        val succDebugStep = getExplicitDebugStep(succAddr)
        val explicitDebugStep = if (succDebugStep != null && currDebugStep != succDebugStep) {
            listOf(succDebugStep)
        } else {
            listOf()
        }

        return toPop.flatten() +
            variableInformationOnTop +
            nonePersistentStackChanges +
            toPush.flatten() +
            explicitDebugStep
    }

    /**
     * Handles the case that variables become live on a lower level of the call stack,
     * when [handleVariablesOnStep] is not empty, it means that on a lower level of the stack
     * some variable became live when stepping from [currAddr] to [succAddr]. To not miss this
     * variable becoming live, iterate also over the [commonSharedStack].
     *
     * Note that some of the stack changes are _persistent_ and others not.
     * This
     */
    private fun getNonePersistentStackChanges(commonSharedStack: List<CallStackLevel>, currIns: SbfInstruction, currAddr: ULong, succAddr: ULong): List<DWARFCfgEdgeLabel> {
        if (Config.CallTraceDebugAdapterProtocol.get() != DebugAdapterProtocolMode.VARIABLES) {
            return listOf()
        }

        val toPopAndVariableLabels = mutableListOf<DWARFCfgEdgeLabel>()
        val potentiallyPoppedStack = mutableListOf<CallStackLevel>()

        /**
         * keeps track of the lowest index at which variable changes occurred, so that
         * we can push the popped stack elements back onto the stack.
         */
        var lastSeenIdx = 0;
        /**
         * Iterates over the common shared stack in reversed order (from top of the stack to bottom).
         * For every element except the top stack, check if the statements introduces variable information.
         * If yes, first close/pop the stacks that we've already seen (tracked in [potentiallyPoppedStack]),
         * and then add the actual variable changes, clear the temporary results in [potentiallyPoppedStack]
         * and proceed.
         *
         * Always add the callstack to potentiallyPoppedStack, also in case of the topmost element,
         * as this may also be needed to be popped.
         */
        commonSharedStack.reversed().forEachIndexed { idx, callStackLevel ->
            if (commonSharedStack.getTopEl() != callStackLevel) {
                val variableChange = callStackLevel.handleVariablesOnStep(currIns, currAddr, succAddr)
                if (variableChange.isNotEmpty()) {
                    toPopAndVariableLabels.addAll((potentiallyPoppedStack.map { it.toFunctionEnd() } + variableChange))
                    potentiallyPoppedStack.clear()
                    lastSeenIdx = idx
                }
            }

            potentiallyPoppedStack.add(callStackLevel)
        }
        /**
         * All the labels that need to be pushed back on to the stack so that the stack actually remains
         * unchanged to the user.
         * When creating the push labels, the formal parameters are excluded, as this not
         * an actual call stack push operation.
         */
        val toPushLabels = commonSharedStack.drop(commonSharedStack.size - lastSeenIdx).flatMap { it.toStartLabelsWithoutFormals() }
        return (toPopAndVariableLabels + toPushLabels).map { it.asPersistent(ALWAYS_PERSIST_PUSH_POPS_DUE_TO_VARIABLES) }
    }

    private fun getExplicitDebugStep(addr: ULong): DWARFCfgEdgeLabel.DebugStep? {
        return debugInformation.lookUpLineNumberInfo(addr)?.let {
            it.getRange().nonEmpty()?.let { r -> DWARFCfgEdgeLabel.DebugStep(r) }
        }
    }

    fun SbfInstruction.getAddress(): ULong? = this.metaData.getVal(SbfMeta.SBF_ADDRESS)
    fun InsPtr.getInstruction(): SbfInstruction = input.getMutableBlock(this.first)!!.getInstruction(this.second)

    private fun InsPtr.getSuccessorInstructions(): List<InsPtr> {
        val insIndex = this.second
        val bb = input.getMutableBlock(this.first)!!
        if (insIndex < bb.numOfInstructions() - 1) {
            return listOf(bb.getLabel() to insIndex + 1)
        }
        return bb.getMutableSuccs().filter { it.numOfInstructions() > 0 }.map { it.getLabel() to 0 }
    }

    private fun List<DWARFCfgEdgeLabel>.toDwarfInstruction() =
        SbfInstruction.Debug(this.flatMapToSet { it.usedRegisters() }, MetaData(Pair(SbfMeta.SBF_DWARF_DEBUG_ANNOTATIONS, this)))

    companion object {
        fun printDebugAnnotatorStats(program: CoreTACProgram, msg: String) {
            if(logger.isEnabled){
                val stats = DebugAnnotatorStats()
                program.ltacStream().forEach {
                    it.cmd.maybeAnnotation(TACMeta.SNIPPET).let { snippet ->
                        when(snippet){
                            is SnippetCmd.SolanaSnippetCmd.ExplicitDebugPopAction -> stats.popAnnotations++
                            is SnippetCmd.SolanaSnippetCmd.ExplicitDebugPushAction -> stats.pushAnnotations++
                            is SnippetCmd.ExplicitDebugStep -> stats.explicitStepAnnotations++
                            is SnippetCmd.SolanaSnippetCmd.VariableBecomingLive -> stats.variableLifeAnnotations++
                            is SnippetCmd.SolanaSnippetCmd.DirectMemoryAccess -> stats.directAccessAnnotations++
                            else -> {}
                        }
                    }
                    stats.allCommands++
                }
                logger.info { msg +"\n" + stats }
            }
        }

        /**
         * This flags changes the semantics of stack push and pops.
         *
         * Say we have some instruction and the stack before and after it:
         *
         * current call stack (at address 10)
         * ---
         * foo
         * baz
         * bar
         * ---
         *
         * actual statement some variable x in bar(!) turns life / or is assigned to.
         *
         * next call stack (at address 11)
         * ---
         * goo
         * baz
         * bar
         * ---
         *
         * If the flag is false (default), the pops of foo and baz are made and the variables are updated,
         * but none persistent and the user won't see the call stack pops until bar is on top of the stack.
         * I.e. the user will see the call stack moving from
         * foo -> goo directly, (i.e. this stack movement is what the address information encodes)
         *
         * If the flag is true, foo and baz are popped, the variable update in bar is performed and goo is pushed.
         * In the debugging session this has the impact that the debugger (top of) stacks are
         * foo -> bar -> goo. (i.e. this stack movement is what the variable information encodes)
         *
         * Due to inlining, it's unclear what the original semantics of the program were, so this is an approximation.
         */
        const val ALWAYS_PERSIST_PUSH_POPS_DUE_TO_VARIABLES = false
        private var TOTAL_ANNOTATOR_TIME = 0L
    }
}

/**
 * Data class to hold a set of DWARFTreeNodes that are all on the same level in the DWARF tree.
 */
data class CallStackLevel private constructor(val scopes: Set<DwarfMethod>, val frameBasePointer: Long?) {
    val level = scopes.mapToSet { it.getDepth() }.firstOrNull() ?: error("All nodes must be on the same depth")

    init {
        check(scopes.isNotEmpty()) { "Should not have an empty list here." }
    }

    /**
     * The set of variables within the current [scopes] nodes including the variables
     * in all [DwTag.LEXICAL_BLOCK] beneath the scopes.
     */
    val variables = run {
        if (Config.CallTraceDebugAdapterProtocol.get() != DebugAdapterProtocolMode.VARIABLES) {
            setOf()
        } else {
            this.scopes.flatMapToSet { method ->
                method.getVars().flatMap { variable ->
                    if (variable.register_locations.isEmpty()) {
                        variable.address_ranges.map { r -> SourceVariableDebugInformation(variable.var_name, variable.getType(), r, listOf()) }
                    } else {
                        variable.register_locations.map { r ->
                            SourceVariableDebugInformation(variable.var_name, variable.getType(), r.range, r.operations)
                        }
                    }
                }
            }
        }
    }

    fun toFunctionEnd(): DWARFCfgEdgeLabel.ScopeEnd {
        val allMatchingPushesInSources = this.scopes.all { it.getDeclRange().isInSources() }
        return DWARFCfgEdgeLabel.ScopeEnd.FunctionEnds(level, allMatchingPushesInSources)
    }

    /**
     * Translates this call stack level to the respective start labels.
     * For each [DWARFTreeNode] in [scopes] it creates the respective edge and also adds all formal variables annotations,
     * for instance for [DwTag.FORMAL_PARAMETER].
     */
    fun toStartLabels(currIns: SbfInstruction, currAddr: ULong, succAddr: ULong): List<DWARFCfgEdgeLabel> {
        return scopes.flatMap {
            val level = create(setOf(it), this.frameBasePointer)
            listOf(it.toFunctionStart()) + (level.handleFormalParametersOnFunctionStart(succAddr) + level.handleVariablesOnStep(currIns, currAddr, succAddr)).toList()
        }
    }

    /**
     * Creates the start labels _without_ formals.
     */
    fun toStartLabelsWithoutFormals(): List<DWARFCfgEdgeLabel> {
        return scopes.flatMap { listOf(it.toFunctionStart()) }
    }

    /**
     * Translates this call stack level to the respective end annotations.
     * Note, for each level this one creates a single end annotation as we always only want to pop a level once and not multiple times.
     * This in not symmetric to [toStartLabels].
     */
    fun toEndLabels(currIns: SbfInstruction, currAddr: ULong, succAddr: ULong): List<DWARFCfgEdgeLabel> {
        return handleVariablesOnStep(currIns, currAddr, succAddr).toList() + listOf(toFunctionEnd())
    }

    /**
     * Creates all [DWARFCfgEdgeLabel] for variables that become life at a function start.
     * The small difference to [handleVariablesOnStep] here is that all variables that are in range are considered,
     * and we don't check for the [currAddr]. I.e. we consider _all variables_ as new variables, as we enter a new scope.
     */
    private fun handleFormalParametersOnFunctionStart(succAddr: ULong): Set<DWARFCfgEdgeLabel.VariableBecomingLive> {
        if (Config.CallTraceDebugAdapterProtocol.get() != DebugAdapterProtocolMode.VARIABLES) {
            return setOf()
        }
        return variables.filter { it.inRange(succAddr) }.mapToSet { el ->
            DWARFCfgEdgeLabel.VariableBecomingLive(this, el)
        }
    }

    /**
     * This method creates [DWARFCfgEdgeLabel] for two different cases:
     *
     * 1. A variable becomes life on the edge from [currAddr] to [succAddr].
     * 2. A variable is used and is currently in the range at the [succAddr], i.e. for a variable v we have v.low_pc <= succAddr < v.high_pc, (see [handleDirectMemoryAccess])
     * As "used" we currently handle either
     * a) case [SbfInstruction.Bin] where the stored register [dst] equals to the register variable from DWARF.
     * b) case [SbfInstruction.Bin] where the read register [v] is equal to the R0 register (special handling to ensure we always track the return value)
     */
    fun handleVariablesOnStep(currIns: SbfInstruction, currAddr: ULong, succAddr: ULong): Set<DWARFCfgEdgeLabel> {
        if (Config.CallTraceDebugAdapterProtocol.get() != DebugAdapterProtocolMode.VARIABLES) {
            return emptySet()
        }
        val res: MutableSet<DWARFCfgEdgeLabel> = mutableSetOf();
        variables.forEach { el ->
            if (!el.inRange(currAddr) && el.inRange(succAddr)) {
                res.add(DWARFCfgEdgeLabel.VariableBecomingLive(this, el))
            }
        }

        /*Explicitly using succAddr here and not currAddr - when a variable becomes life, we already
                want to take into acount the affect*/
        res.addAll(handleDirectMemoryAccess(currIns, succAddr))
        return res
    }

    fun handleDirectMemoryAccess(currIns: SbfInstruction, succAddr: ULong): Set<DWARFCfgEdgeLabel> {
        if (Config.CallTraceDebugAdapterProtocol.get() != DebugAdapterProtocolMode.VARIABLES) {
            return emptySet()
        }
        if(currIns !is SbfInstruction.Mem){
            return emptySet()
        }
        val liveVariables = variables.filter { it.inRange(succAddr) }

        /**
         * The code below is responsible to extract information for variable when they are dereferenced.
         * This allows the debugger when a value of reference type (&Foo) is accessed to also extract the value
         * that is accessed. For instance, if Foo is a struct and has a field `bar` the code below checks if
         * a memory offset at field `bar` is performed. If so, it generates a [DWARFCfgEdgeLabel.DirectMemoryAccess].
         * Note that there are different cases to consider, if the variable is access is on r10 or on another register.
         * For r10, extra logic must be taken care of to factor in the frame base pointer.
         */
        if (currIns.access.baseReg.r == SbfRegister.R10_STACK_POINTER) {
            if (frameBasePointer == null) {
                return emptySet()
            }
            return liveVariables.flatMapToSet { sourceVariable ->
                val dereferencedVar = sourceVariable.variableType!!.asNonReferenceType()
                sourceVariable.operations.splitToOffsets(dereferencedVar).mapNotNull { (offset, ops) ->
                    val registerAccess = ops.getRegisterAccess(currIns.access.baseReg.r)
                    if (registerAccess != null) {
                        val registerOffset = registerAccess.offset();
                        val offsetIntoStruct = currIns.access.offset - (frameBasePointer.toLong() + registerOffset)
                        if (offset == Offset(offsetIntoStruct.toULong())) {
                            DWARFCfgEdgeLabel.DirectMemoryAccess(this, sourceVariable, offset, ops, currIns, currIns.value)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        } else {
            // Handle all other, none stack pointer register variables (r0, ... r9)
            return liveVariables.flatMapToSet { sourceVariable ->
                val dereferencedVar = sourceVariable.variableType!!.asNonReferenceType()
                sourceVariable.operations.splitToOffsets(dereferencedVar).mapNotNull { (offset, ops) ->
                    val registerAccess = ops.getRegisterAccess(currIns.access.baseReg.r)
                    if (registerAccess != null) {
                        val registerOffset = registerAccess.offset();
                        val offsetIntoStruct = currIns.access.offset - registerOffset
                        if (offset == Offset(offsetIntoStruct.toULong())) {
                            DWARFCfgEdgeLabel.DirectMemoryAccess(this, sourceVariable, offset, ops, currIns, currIns.value)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun DwarfMethod.toFunctionStart(): DWARFCfgEdgeLabel.ScopeStart {
        return when (this) {
            is InlinedMethod ->
                DWARFCfgEdgeLabel.ScopeStart.InlinedCallee(
                    functionName = this.getMethodName(),
                    callSiteRange = this.getRange(),
                    declRange = this.getDeclRange(),
                    node = this)

            is Subprogram ->
                DWARFCfgEdgeLabel.ScopeStart.SubProgramStart(
                    functionName = this.getMethodName(),
                    declRange = this.getDeclRange(),
                    node = this)
        }
    }

    companion object {
        private data class CacheKey(val scopes: Set<DwarfMethod>, val frameBasePointer: Long?)

        private val callStackLevelCache = mutableMapOf<CacheKey, CallStackLevel>()
        fun create(scopes: Set<DwarfMethod>, frameBasePointer: Long?): CallStackLevel {
            return callStackLevelCache.getOrPut(CacheKey(scopes, frameBasePointer)) {
                CallStackLevel(scopes, frameBasePointer)
            }
        }
    }
}

fun Range.Range.isInSources(): Boolean = (!Config.CallTraceDebugAdapterProtocolOnlyInSources.get() || this.fileExistsInSourcesDir())

@Serializable
data class SourceVariableDebugInformation(val variableName: String, val variableType: RustType?, val range: AddressRange, val operations: List<DWARFOperation>) {
    fun inRange(addr: ULong): Boolean {
        return range.inRange(addr)
    }

    override fun toString(): String {
        return "Variable information: $variableName valid in ${range} and constructed via $operations"
    }
}

fun List<DWARFOperation>.getRegisterAccess(register: SbfRegister): DWARFOperation.RegisterAccess? {
    val filteredRes = this.filterIsInstance<DWARFOperation.RegisterAccess>().filter {
        when (it) {
            is DWARFOperation.Register -> register.value == it.register.toByte()
            is DWARFOperation.RegisterOffset -> register.value == it.register.toByte()
            is DWARFOperation.FrameOffset -> register == SbfRegister.R10_STACK_POINTER
        }
    }
    if (filteredRes.size > 1) {
        logger.info { "Expecting at most one element in a DWARF operation list matching an register got $filteredRes - (operations are $this)" }
        return null
    }
    check(filteredRes.size <= 1) { "Expecting at most one element in a DWARF operation list matching an register got $filteredRes - all operations are $this" }
    return filteredRes.firstOrNull()
}


fun List<DWARFOperation>.splitOperationsInPieces(variableType: RustType?): Map<Pair<Offset, ULong>, List<DWARFOperation>> {
    if (variableType == null || variableType == RustType.UNKNOWN) {
        return mapOf()
    }
    val offsetToOperations = mutableMapOf<Pair<Offset, ULong>, List<DWARFOperation>>()
    fun storeAsResult(splittedPart: List<DWARFOperation>, offsetFromBase: ULong, width: ULong) {
        if (splittedPart.isNotEmpty()) {
            offsetToOperations[Offset(offsetFromBase) to width] = splittedPart
        }
    }

    val lastSplit = this.fold(listOf<DWARFOperation>() to 0UL) { acc, operation ->
        when (operation) {
            is DWARFOperation.Piece -> {
                val width = (operation.sizeInBits / 8UL)
                storeAsResult(acc.first, acc.second, width)
                val newOffsetFromBase = acc.second + width
                listOf<DWARFOperation>() to newOffsetFromBase
            }

            else -> {
                acc.first.plus(operation) to acc.second
            }
        }
    }

    check(lastSplit.second <= variableType.getByteSize()) { "The size of the type in the dwarf operation list (${lastSplit.second}) exceeds the variable type size (variable type size: ${variableType.getByteSize()}) - operations list $this, $variableType " }
    //Calling store as Result one last time to persist the remaining part.
    storeAsResult(lastSplit.first, lastSplit.second, variableType.getByteSize() - lastSplit.second)
    return offsetToOperations
}

fun List<DWARFOperation>.splitToOffsets(variableType: RustType?): Map<Offset, List<DWARFOperation>> {
    if (variableType == null || variableType == RustType.UNKNOWN) {
        return mapOf()
    }
    val offsetToOperations = this.splitOperationsInPieces(variableType)
    val flattenedType = variableType.flattenToOffsets()
    val res = mutableMapOf<Offset, List<DWARFOperation>>();

    offsetToOperations.forEachEntry { e ->
        val (operationsOffset, operationsWidth) = e.key
        val operations = e.value
        flattenedType.forEach { offset ->
            if (offset.offset in operationsOffset.offset ..< operationsOffset.offset + operationsWidth) {
                res[offset] = operations
            }
        }
    }
    return res
}

class DebugAnnotatorStats(var pushAnnotations: Int = 0, var popAnnotations: Int = 0, var explicitStepAnnotations: Int = 0, var variableLifeAnnotations: Int = 0, var directAccessAnnotations: Int = 0, var allCommands: Int = 0) {
    private fun debugAnnotationCount(): Int = pushAnnotations + popAnnotations + explicitStepAnnotations + variableLifeAnnotations + directAccessAnnotations
    override fun toString(): String {
        return "Total debug annotations = ${debugAnnotationCount()} (${debugAnnotationCount().toDouble() / allCommands.toDouble()}%)\n" +
            "Number of push annotations = ${pushAnnotations}\n" +
            "Number of pop annotations = ${popAnnotations}\n" +
            "Number of explicit debug steps = ${explicitStepAnnotations}\n"+
            "Number of variable life annotations = ${variableLifeAnnotations}\n"+
            "Number of direct access annotations = ${directAccessAnnotations}\n" +
            "Number of other commands = ${allCommands - debugAnnotationCount()}\n"
    }
}

fun Int.roundDownToMultipleOf(n: Int): Int = Math.floorDiv(this, n) * n