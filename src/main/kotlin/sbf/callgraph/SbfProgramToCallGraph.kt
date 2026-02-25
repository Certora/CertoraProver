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
import dwarf.DebugSymbols
import sbf.SolanaConfig
import sbf.analysis.cpis.cpisSubstitutionMap
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.MemorySummaries
import sbf.inliner.InlineSpec
import sbf.inliner.InlinerConfig
import sbf.sbfLogger
import sbf.support.demangle

/**
 * Conversion of an SbfProgram (i.e., a sequence of labeled sbf instructions)
 * to a list of CFGs.
 **/
fun sbfProgramToSbfCfgs(prog: SbfProgram, inlinerConfig: InlinerConfig, memSumaries: MemorySummaries): SbfCallGraph {
    val newEntriesMap = mutableMapOf<String, ElfAddress>()
    newEntriesMap.putAll(prog.entriesMap)
    prog.funcMan.getAllFunctions().filter { isMangledMockFn(it.name) }.forEach {
        val start = it.entryPoint
        check(start != null) { "Found mock ${it.name} without start address" }
        newEntriesMap[it.name] = start
    }
    return sbfProgramWithMocksToSbfCfgs(prog.copy(entriesMap = newEntriesMap), inlinerConfig, memSumaries)
}

private fun sbfProgramWithMocksToSbfCfgs(
    prog: SbfProgram,
    inlinerConfig: InlinerConfig,
    memSummaries: MemorySummaries
): SbfCallGraph {
    /**
     * We first build a big monolithic CFG representing the whole sbf program.
     * Since an sbf program can have multiple functions,
     * `monoCFG` is actually a set of disconnected CFGs (one per function).
     * Note that `monoCFG` will be thrown away later, so it won't survive beyond this function.
     **/
    val monoCFG = MutableSbfCFG("mono_cfg")

    val functions = mutableSetOf<SbfFunction>()
    functions.addAll(prog.entriesMap.map { SbfFunction(it.key, it.value) })

    val targets = MutableSbfCFG.getTargets(prog)
    var curBlock: MutableSbfBasicBlock? = null
    var exitDominates = false
    prog.program.forEachIndexed lit@{ i, labeledInst ->
        val pc = labeledInst.first
        val inst = labeledInst.second
        val prevBlock = curBlock

        if (targets.contains(pc) || (curBlock != null && curBlock!!.getInstruction(curBlock!!.numOfInstructions() - 1) is SbfInstruction.Jump)) {
            /*The second condition ensures that we don't have two goto instructions in the same block.
            * This seems to happen when LLVM invoke instructions are lowered.
            * The LLVM code:
            *   invoke void @core::result::unwrap_failed(....)
            *   to label %normal unwind label %cleanup,
            * normal:
            *   unreachable
            * bb4:
            *   br label %bb5
            * cleanup:
            *  %18 = landingpad { i8*, i32 } cleanup
            *  ...
            *  br label %bb4
            * bb5: ...
            *
            * into sbf code:
            *
            * 1200:	call core::result::unwrap_failed
            * 1201:	goto 1202
            * 1202:
            *      goto 1208 // first goto
            *      r1 := *(u64 *) (r10 + -160) <- DEAD
            *      r2 := *(u64 *) (r10 + -168) <- DEAD
            *      *(u64 *) (r10 + -32) := r2  <- DEAD
            *      *(u32 *) (r10 + -24) := r1  <- DEAD
            *      goto 1202 // second goto: we don't allow two goto's in the same block.
            */
            curBlock = monoCFG.getOrInsertBlock(pc)
            // The dominance of exit terminates here
            exitDominates = false
        }

        if (curBlock == null) {
            throw CFGBuilderError("couldn't get a basic block for label $pc")
        }

        curBlock?.let {
            if (exitDominates) {
                // skip the instruction because it's dead (it's dominated by an exit instruction)
                return@lit // this has the effect of continue
            }

            if (prevBlock != null && prevBlock.getLabel() != it.getLabel()) {
                val lastInstPrevBlock = prevBlock.getInstruction(prevBlock.numOfInstructions() - 1)
                if (lastInstPrevBlock !is SbfInstruction.Jump && lastInstPrevBlock !is SbfInstruction.Exit) {
                    prevBlock.add(SbfInstruction.Jump.UnconditionalJump(pc))
                    prevBlock.addSucc(it)
                }
            }

            if (inst is SbfInstruction.Call) {
                val funcStart = inst.entryPoint
                if (funcStart != null) {
                    val funcName = prog.funcMan.getFunction(funcStart)?.name
                        ?: throw CFGBuilderError("cannot find a name for a function starting at address $funcStart")
                    functions.add(SbfFunction(funcName, funcStart))
                }
            }

            when (inst) {
                is SbfInstruction.Exit -> {
                    it.add(inst)
                    exitDominates = true
                }

                is SbfInstruction.Jump.UnconditionalJump -> {
                    val succBlock = monoCFG.getOrInsertBlock(inst.target)
                    it.addSucc(succBlock)
                    it.add(inst)
                }

                is SbfInstruction.Jump.ConditionalJump -> {
                    if (i + 1 >= prog.program.size) {
                        throw CFGBuilderError("out-of-bounds jump instruction")
                    }
                    val nextPC = prog.program[i + 1].first
                    val jumpCondInst = SbfInstruction.Jump.ConditionalJump(inst.cond, inst.target, nextPC, inst.metaData)
                    val trueSuccBlock = monoCFG.getOrInsertBlock(inst.target)
                    val falseSuccBlock = monoCFG.getOrInsertBlock(nextPC)

                    if (trueSuccBlock.getLabel() == falseSuccBlock.getLabel()) {
                        // It's possible to have useless conditional jump.
                        //      934:	2d 67 00 00 00 00 00 00	if r7 > r6 goto +0 <LBB2_94>
                        //0000000000001d38 LBB2_94:
                        //      935:	...
                        it.add(SbfInstruction.Jump.UnconditionalJump(inst.target, inst.metaData))
                        it.addSucc(trueSuccBlock)
                    } else {
                        it.add(jumpCondInst)
                        it.addSucc(trueSuccBlock)
                        it.addSucc(falseSuccBlock)
                    }
                }

                else -> {
                    it.add(inst)
                }
            }
        }
    }

    /***
     * At this point, we split `monoCFG` into multiple CFGs (one per function)
     * We know the start address of each function.
     * Each CFG consists of all reachable blocks from each start.
     * We rename basic block labels to avoid any possible clash.
     */
    val labelMap = monoCFG.renameLabels() //needed by postProcessCFG
    val cfgs = ArrayList<MutableSbfCFG>()
    for ((name, start) in functions) {
        check(start != null)

        if (AbortFunctions.contains(name)) {
            continue
        }

        val labeledStart = labelMap[Label.Address(start)]
        check(labeledStart != null) { "cannot find address $start in the label map $labelMap" }
        val entryBlock = monoCFG.getBlock(labeledStart)
            ?: throw CFGBuilderError("cannot find basic block for function=${name} at entry block=$start")
        val cfg = monoCFG.sliceFrom(entryBlock.getLabel(), name)
        val clonedCfg = cfg.clone(cfg.getName())
        if (SolanaConfig.PrintOriginalToStdOut.get()) {
            sbfLogger.info { "$clonedCfg" }
        }
        postProcessCFG(clonedCfg, prog.debugSymbols, prog.globals)
        cfgs.add(clonedCfg)
    }
    val demangler = Demangler(cfgs)
    val roots = prog.entriesMap.map { demangler.demangle(it.key) ?: it.key }.toSet()

    return relink(
        MutableSbfCallGraph(
            demangler.get(),
            roots,
            prog.globals,
            preservedCFGs = cpisSubstitutionMap.mockFunctionsNames
        ),
        demangler
    ).let {
        relinkAbort(it, inlinerConfig, memSummaries)
    }
}

private fun postProcessCFG(cfg: MutableSbfCFG, debugSymbols: DebugSymbols, globals: GlobalVariables) {
    cfg.addDebugInformation(debugSymbols)
    cfg.addSourceSegments(debugSymbols)
    cfg.verify(false, "[before postProcessCFG]")
    //do not call simplify before calling lowerBranchesIntoAssume
    cfg.lowerBranchesIntoAssume()
    cfg.verify(false, "[after lowering branches into assumes]")
    //do not call simplify before calling simplifyMemoryIntrinsics
    simplifyMemoryIntrinsics(cfg)
    cfg.verify(false, "[after simplifying builtin calls]")
    cfg.simplify(globals)
    cfg.verify(false, "[after simplify]")
    cfg.normalize()
    cfg.verify(true, "[after normalize]")
}


/**
 *  Relink functions that always abort by direct call to abort
 *  **Precondition**: the function names are demangled
 *
 *  After replacing calls with abort we ensure that the callgraph is re-computed again.
 */
private fun relinkAbort(prog: SbfCallGraph, inlinerConfig: InlinerConfig, memSummaries: MemorySummaries): SbfCallGraph {
    val newCFGs = mutableListOf<MutableSbfCFG>()
    val abortFunctions = mutableSetOf<String>()
    prog.getCFGs().forEach {
        if (!memSummaries.isKnownAbortFn(it.getName())) {
            val newCFG = it.clone(it.getName())
            relinkAbort(newCFG, memSummaries, abortFunctions)
            newCFGs.add(newCFG)
        }
    }
    abortFunctions.forEach {
        if (inlinerConfig.getInlineSpec(it) is InlineSpec.DoInline) {
            sbfLogger.warn { "$it will not be inlined because it is marked as an abort function" }
        }
    }
    return MutableSbfCallGraph(
        newCFGs,
        prog.getCallGraphRoots().map { it.getName() }.toSet(),
        prog.getGlobals(),
        check = false,
        preservedCFGs = prog.getPreservedCFGs()
    )
}

private fun relinkAbort(cfg: MutableSbfCFG,
                        memSummaries: MemorySummaries,
                        abortFunctions: MutableSet<String>) {
    var changed = false
    for (b in cfg.getMutableBlocks().values) {
        for (locInst in b.getLocatedInstructions()) {
            val inst = locInst.inst
            if (inst is SbfInstruction.Call) {
                val fname = inst.name
                if (memSummaries.getSummary(fname)?.isAbort == true) {
                    abortFunctions.add(fname)
                    val abortInst = SolanaFunction.toCallInst(
                        SolanaFunction.ABORT,
                        MetaData(SbfMeta.COMMENT to fname)
                    )
                    b.replaceInstruction(locInst, abortInst)
                    changed = true
                }
            }
        }
    }
    if (changed) {
        // Cleanup after adding `abort` statements
        cfg.deadCodeElimination()
        cfg.removeUnreachableBlocks()
        cfg.normalize()
    }
}

/**
 * Collect all function names from [cfgs] and call the external program `rustfilt` to get the demangle names.
 */
private class Demangler(private val cfgs: List<MutableSbfCFG>) {
    private val demangledSep = "_"
    private val demangledCFGs = ArrayList<MutableSbfCFG>(cfgs.size)

    // Map from mangled to demangled names
    private val demanglerMap: MutableMap<String, String> = mutableMapOf()

    // Functions with generic types (each instantiation produces a different hash) or when two
    // copies of the same library is used by mistake.
    private val nonUniqueNames = mutableSetOf<String>()

    init {
        run()
    }

    /** Return the list of cfgs with all demangled names **/
    fun get() = demangledCFGs

    fun isUnique(name: String) = !nonUniqueNames.contains(name)

    /** Return the demangled version of [name] if [name] is a function name, otherwise, null **/
    fun demangle(name: String): String? = demanglerMap[name]

    /**
     *  Make [inMap] an injective function. Given `inMap`:
     *  ```
     *  { foo#123456 -> foo, foo#848474 -> foo}
     *  ```
     *  `makeInjective` updates `inMap` to
     *  ```
     *  { foo#123456 -> foo_1, foo#848474 -> foo_2}
     *  ```
     **/
    private fun makeInjective(inMap: MutableMap<String, String>) {
        val invMap: MutableMap<String, ArrayList<String>> = mutableMapOf()
        for (kv in inMap) {
            val x = invMap[kv.value]
            if (x == null) {
                invMap[kv.value] = arrayListOf(kv.key)
            } else {
                x.add(kv.key)
                invMap[kv.value] = x
            }
        }

        for (kv in invMap) {
            val domainVals = kv.value
            if (domainVals.size > 1) {
                for ((i, domainVal) in domainVals.withIndex()) {
                    val codomainVal = inMap[domainVal]
                    val demangledName = "$codomainVal$demangledSep$i"
                    inMap[domainVal] = demangledName
                    nonUniqueNames.add(demangledName)
                }
            }
        }
    }

    private fun buildDemanglerMap() {
        // Collect all function and calls names
        val mangledNames = mutableSetOf<String>()
        for (cfg in cfgs) {
            mangledNames.add(cfg.getName())
            for (block in cfg.getBlocks().values) {
                for (inst in block.getInstructions()) {
                    if (inst is SbfInstruction.Call) {
                        mangledNames.add(inst.name)
                    }
                }
            }
        }
        val mangledNamesList = mangledNames.toList()
        val demangledNames = demangle(mangledNamesList) // call rustfilt to demangle names
        if (demangledNames == null || demangledNames.size != mangledNamesList.size) {
            // do nothing: here if the call to rustfilt fails for some reason
            return
        }

        mangledNamesList.forEachIndexed { i, mangledName ->
            demanglerMap[mangledName] = demangledNames[i]
        }
        makeInjective(demanglerMap)
    }

    private fun run() {
        buildDemanglerMap()

        if (demanglerMap.isEmpty()) {
            demangledCFGs.addAll(cfgs)
            return
        }

        for (cfg in cfgs) {
            val demangledCFG = cfg.clone(demanglerMap.getOrDefault(cfg.getName(), cfg.getName()))
            for (block in demangledCFG.getMutableBlocks().values) {
                for ((i, inst) in block.getInstructions().withIndex()) {
                    if (inst is SbfInstruction.Call) {
                        val mangledName = inst.name
                        val demangledName = demanglerMap.getOrDefault(mangledName, mangledName)
                        val newMetadata = inst.metaData.plus(SbfMeta.MANGLED_NAME to mangledName)
                        block.replaceInstruction(
                            i, inst.copy(name = demangledName, metaData = newMetadata)
                        )
                    }
                }
            }
            demangledCFGs.add(demangledCFG)
        }
    }
}

/**
 * Demangle [name] without calling the external program `rustfilt`.
 * It assumes the `_ZN...E` scheme where name is preceded by its length.
 *
 * **Important**: it discards the trailing hash, so it can be only used if no symbols collisions are possible.
 **/
@Suppress("ForbiddenMethodCall")
fun fastDemangleRust(name: String): String? {
    // Check mangling prefix
    if (!name.startsWith("_ZN") || !name.endsWith("E")) {
        return null
    }

    val core = name.removePrefix("_ZN").removeSuffix("E")
    val parts = mutableListOf<String>()

    var i = 0
    while (i < core.length) {
        // parse a decimal length
        val start = i
        while (i < core.length && core[i].isDigit()) {
            i++
        }
        if (start == i) {
            return null // malformed
        }
        val len = core.substring(start, i).toInt()

        if (i + len > core.length) {
            return null // malformed
        }
        val part = core.substring(i, i + len)
        if ((i + len) < core.length) {
            // don't add the last part because it is the hash
            parts.add(part)
        }
        i += len
    }

    return parts.joinToString("::")
}


/**
 * Conceptually it replaces all calls to `f` with  some function `g`.
 * Physically, this function replaces `f`'s body with `g`'s body.
 *
 * Preconditions: all function names have been already demangled.
 */
private fun relink(prog: SbfCallGraph, demangler: Demangler): SbfCallGraph {
    // This relinking process will create a new call-graph from scratch

    val newCFGs = mutableListOf<MutableSbfCFG>()
    val relinkedFunctions = mutableSetOf<String>()

    // First, we add the new CFGs for the functions to be relinked.
    prog.getCFGs().forEach { cfg ->
        val mockFn = cfg.getName()
        if (isMockFn(mockFn, demangler)) {
            val origFn = getFunctionToBeMocked(mockFn, demangler)
            if (prog.getCFG(origFn) != null) {
                val newCFG = prog.getCFG(mockFn)?.clone(origFn)
                check(newCFG != null) { "relink cannot find CFG for $mockFn" }
                sbfLogger.warn { "MOCKING: calls to $origFn (if any) have been redirected to $mockFn" }
                relinkedFunctions.add(origFn)
                newCFGs.add(newCFG)
            } else {
                sbfLogger.warn {
                    "MOCKING: $mockFn is identified as a function that should replace $origFn but $origFn cannot be found"
                }
            }
        }
    }

    // Second, we add the unchanged CFGs for the rest of the functions
    prog.getCFGs().forEach { cfg ->
        if (!relinkedFunctions.contains(cfg.getName())) {
            newCFGs.add(cfg.clone(cfg.getName()))
        }
    }

    // We create a new call-graph from scratch (call-graph roots and globals are not affected by relinking).
    return MutableSbfCallGraph(
        newCFGs,
        prog.getCallGraphRoots().map { it.getName() }.toSet(),
        prog.getGlobals(),
        preservedCFGs = prog.getPreservedCFGs()
    )
}

private const val certoraNamespace = "certora"
private const val mocksNamespace = "mocks"
private const val mangledMockPrefix = "$certoraNamespace${mocksNamespace.length}$mocksNamespace"
private const val demangledMockPrefix = "$certoraNamespace::$mocksNamespace"

@Suppress("ForbiddenMethodCall")
private fun isMangledMockFn(name: String): Boolean =
    name.contains(mangledMockPrefix)

@Suppress("ForbiddenMethodCall")
private fun isMockFn(name: String, demangler: Demangler): Boolean {
    return if (name.contains(demangledMockPrefix)) {
        if (!demangler.isUnique(name)) {
            sbfLogger.warn {
                "MOCKING: $name seems an overloaded function and relinking does not support this kind of function"
            }
            false
        } else {
            true
        }
    } else {
        false
    }
}

private fun getFunctionToBeMocked(name: String, demangler: Demangler): String {
    check(isMockFn(name, demangler)) { "precondition of getFunctionToBeMocked failed" }
    return name.replace("$demangledMockPrefix::", "")
}

