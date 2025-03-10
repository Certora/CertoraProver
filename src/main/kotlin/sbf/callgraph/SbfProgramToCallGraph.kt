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
import sbf.SolanaConfig
import sbf.cfg.*
import sbf.disassembler.*
import sbf.sbfLogger
import sbf.support.demangle

/**
 * Conversion of an SbfProgram (i.e., a sequence of labeled sbf instructions)
 * to a list of CFGs.
 **/
fun sbfProgramToSbfCfgs(prog: SbfProgram): MutableSbfCallGraph {
    /**
     * We first build a big monolithic CFG representing the whole sbf program.
     * Since an sbf program can have multiple functions,
     * @monoCFG is actually a set of disconnected CFGs (one per function).
     * Note that @monoCFG will be thrown away later, so it won't survive beyond this function.
     **/
    val monoCFG = MutableSbfCFG("mono_cfg")
    val functions = mutableSetOf<Pair<String, ElfAddress>>()
    prog.entriesMap.forEachEntry {
        functions.add(it.key to it.value)
    }

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
                    functions.add(funcName to funcStart)
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
                    val jumpCondInst = SbfInstruction.Jump.ConditionalJump(inst.cond, inst.target, nextPC)
                    val trueSuccBlock = monoCFG.getOrInsertBlock(inst.target)
                    val falseSuccBlock = monoCFG.getOrInsertBlock(nextPC)

                    if (trueSuccBlock.getLabel() == falseSuccBlock.getLabel()) {
                        // It's possible to have useless conditional jump.
                        //      934:	2d 67 00 00 00 00 00 00	if r7 > r6 goto +0 <LBB2_94>
                        //0000000000001d38 LBB2_94:
                        //      935:	...
                        it.add(SbfInstruction.Jump.UnconditionalJump(inst.target))
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
     * At this point, we split monoCFG into multiple CFGs (one per function)
     * We know the start address of each function.
     * Each CFG consists of all reachable blocks from each start.
     * We rename basic block labels to avoid any possible clash.
     */
    val labelMap = monoCFG.renameLabels() //needed by postProcessCFG
    val cfgs = ArrayList<MutableSbfCFG>()
    for ((name, start) in functions) {
        val labeledStart = labelMap[Label.Address(start)]
        check(labeledStart != null) { "found a label in the label map" }
        val entryBlock = monoCFG.getBlock(labeledStart)
            ?: throw CFGBuilderError("cannot find basic block for function=${name} at entry block=$start")
        val cfg = monoCFG.sliceFrom(entryBlock.getLabel(), name)
        val clonedCfg = cfg.clone(cfg.getName())
        if (SolanaConfig.PrintOriginalToStdOut.get()) {
            sbfLogger.info { "$clonedCfg" }
        }
        postProcessCFG(clonedCfg, prog.globalsMap)
        cfgs.add(clonedCfg)
    }
    val roots = prog.entriesMap.map { it.key }.toSet()
    val demangler = Demangler(cfgs)
    return relink(
        MutableSbfCallGraph(demangler.get(), roots, prog.globalsMap)
    ) { demangler.isOverloaded(it) }
}

private fun postProcessCFG(cfg: MutableSbfCFG, globalsMap: GlobalVariableMap) {
    cfg.verify(false, "[before postProcessCFG]")
    //do not call simplify before calling lowerBranchesIntoAssume
    cfg.lowerBranchesIntoAssume()
    cfg.verify(false, "[after lowering branches into assumes]")
    //do not call simplify before calling simplifyMemoryIntrinsics
    simplifyMemoryIntrinsics(cfg)
    cfg.verify(false, "[after simplifying builtin calls]")
    cfg.simplify(globalsMap)
    cfg.verify(false, "[after simplify]")
    markAddWithOverflow(cfg)
    cfg.verify(false, "[after markAddWithOverflow]")
    cfg.normalize()
    cfg.verify(true, "[after normalize]")
}



private class Demangler(private val cfgs: List<MutableSbfCFG>) {
    private val demangledSep = "_"
    private val demangledCFGs = ArrayList<MutableSbfCFG>(cfgs.size)
    // This is for mangled function names that might have been overloaded.
    // This is typically functions with generic types (each instantiation produces a different hash) or when two
    // copies of the same library is used by mistake.
    private val overloadedNames = mutableSetOf<String>()

    init {
        run()
    }

    fun get() = demangledCFGs

    fun isOverloaded(name: String) = overloadedNames.contains(name)

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
                    overloadedNames.add(demangledName)
                }
            }
        }
    }

    private fun buildDemanglerMap(): Map<String, String>? {
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
        return if (demangledNames == null || demangledNames.size != mangledNamesList.size) {
            // here if the call to rustfilt fails for some reason
            null
        } else {
            val remap: MutableMap<String, String> = mutableMapOf()
            mangledNamesList.forEachIndexed { i, mangledName ->
                remap[mangledName] = demangledNames[i]
            }
            makeInjective(remap)
            remap
        }
    }

    private fun run() {
        val demanglerMap = buildDemanglerMap()
        if (demanglerMap == null) {
            demangledCFGs.addAll(cfgs)
            return
        }

        for (cfg in cfgs) {
            val demangledCFG = cfg.clone(demanglerMap.getOrDefault(cfg.getName(), cfg.getName()))
            for (block in demangledCFG.getMutableBlocks().values) {
                for ((i, inst) in block.getInstructions().withIndex()) {
                    if (inst is SbfInstruction.Call) {
                        block.replaceInstruction(
                            i,
                            inst.copy(name = demanglerMap.getOrDefault(inst.name, inst.name))
                        )
                    }
                }
            }
            demangledCFGs.add(demangledCFG)
        }
    }
}

/**
 * Conceptually it replaces all calls to `f` with  some function `g`.
 * Physically, this function replaces `f`'s body with `g`'s body.
 *
 * How we determine which function `g` is a replacement and which function `f` should be replaced is decided
 * based on their qualified names. See [findFunctionToBeRelinked] for details.
 *
 * Preconditions: all function names have been already demangled.
 */
private fun relink(prog: MutableSbfCallGraph, isOverloaded: (String) -> Boolean): MutableSbfCallGraph {
    // This relinking process will create a new call-graph from scratch

    val newCFGs = mutableListOf<MutableSbfCFG>()
    val relinkedFunctions = mutableSetOf<String>()

    // First, we add the new CFGs for the functions to be relinked.
    prog.getMutableCFGs().forEach { cfg ->
        val newFn = cfg.getName()
        if (!isOverloaded(newFn)) {
            val oldFn = findFunctionToBeRelinked(newFn)
            if (oldFn != null) {
                if (prog.getCFG(oldFn) != null) {
                    val newCFG = prog.getCFG(newFn)?.clone(oldFn)
                    check(newCFG != null) { "relink cannot find CFG for $newFn" }
                    sbfLogger.warn { "All calls to $oldFn have been redirected to $newFn" }
                    relinkedFunctions.add(oldFn)
                    newCFGs.add(newCFG)
                } else {
                    sbfLogger.warn {
                        "$newFn is identified as a function that should replace $oldFn but $oldFn cannot be found"
                    }
                }
            }
        } else {
            sbfLogger.warn {
                "$newFn seems an overloaded function and relinking does not support this kind of function"
            }
        }
    }

    // Second, we add the unchanged CFGs for the rest of the functions
    prog.getMutableCFGs().forEach { cfg ->
        if (!relinkedFunctions.contains(cfg.getName())) {
            newCFGs.add(cfg)
        }
    }

    // We create a new call-graph from scratch (call-graph roots and globals are not affected by relinking).
    return MutableSbfCallGraph(newCFGs, prog.getCallGraphRoots().map { it.getName() }.toSet(), prog.getGlobals())
}

/**
 * If [newName] is identified as a function that is meant to replace another one,
 * then return the function that is paired with. [findFunctionToBeRelinked] returns a non-null string if [newName]
 * follows this convention:
 *
 * ```
 * crate_name::certora::mocks::rest_of_the_qualified_name
 * ```
 *
 * And the returned string is:
 *
 * ```
 * crate_name::rest_of_the_qualified_name
 * ```
 */
private fun findFunctionToBeRelinked(newName: String,
                                     defaultDirs: List<String> = listOf("mocks", "summaries")): String? {
    val (s1, r1) = splitFQFN(newName)
    if (s1 == "") {
        return null
    }
    val (s2,r2) = splitFQFN(r1)
    if (s2 == "") {
        return null
    }
    val (s3,r3) = splitFQFN(r2)
    if (s3 == "") {
        return null
    }
    return if (s2 == "certora" && defaultDirs.contains(s3)) {
        "$s1::$r3"
    } else {
        null
    }
}

/**
 * Split [qualifiedName] into two substrings (s1, s2) where s1 is the string before the first occurrence of `::`
 * and s2 is the rest of the string after the first occurrence of `::`.
 *
 * Precondition: all function names have been already demangled.
 */
private fun splitFQFN(qualifiedName: String): Pair<String, String> {
    val end = qualifiedName.indexOf("::", 0, false)
    return if (end == -1) {
        "" to qualifiedName
    } else {
        qualifiedName.substring(0, end) to qualifiedName.removeRange(0, end+2)
    }
}
