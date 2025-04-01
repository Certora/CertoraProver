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
import sbf.cfg.*
import sbf.disassembler.*
import sbf.sbfLogger
import sbf.support.printToFile
import java.io.File

/**
 * A container to keep all program CFGs and its call graph.
 * All function names have been already demangled.
 **/

interface SbfCallGraph {
    fun getGlobals(): GlobalVariableMap
    fun getCFGs(): List<SbfCFG>
    fun transformSingleEntry(f: (SbfCFG) -> SbfCFG): SbfCallGraph
    fun transformSingleEntryAndGlobals(f: (SbfCFG) -> Pair<SbfCFG, GlobalVariableMap>): SbfCallGraph
    fun getCallGraphRoots(): List<SbfCFG>
    fun getCallGraphRootSingleOrFail(): SbfCFG
    fun getCallGraph(): Map<String, Set<String>>
    fun getCFG(name: String): SbfCFG?
    fun getRecursiveFunctions(): Set<String>
    fun callGraphStructureToString(): String
    fun callGraphStructureToDot(prefix: File)
    fun toDot(prefix: File, onlyEntryPoint: Boolean = false)
    fun getStats(): CFGStats
}

/**
 *  @params cfgs the set of CFGs
 *  @params globalsMap contains information about global variables
 *  @params runtime contains information about Solana syscalls
 **/
class MutableSbfCallGraph(private val cfgs: MutableList<MutableSbfCFG>,
                           private val rootNames: Set<String>,
                           private val globalsMap: GlobalVariableMap,
                           checkCFGHasExactlyOneExit: Boolean = true): SbfCallGraph {
    // Roots of the call graph
    private val roots: List<MutableSbfCFG>
    private val cfgMap: MutableMap<String, MutableSbfCFG> = mutableMapOf()
    // Call graph of the program: map from function names to its callees (also as function names)
    private val callGraph: MutableMap<String, MutableSet<String>> = mutableMapOf()
    // Recursive functions in the program
    private val recursiveSet: MutableSet<String> = mutableSetOf()
    // sccs[i] contains the i-th SCC
    private val sccVector: ArrayList<Set<String>> = arrayListOf()
    // map a CFG to an index in sccs
    private val sccMap: MutableMap<String, Int> = mutableMapOf()

    init {
        // Not bother to keep cfgs from functions considered as external by the prover (e.g., compiler-rt functions).
        cfgs.removeIf { SbfInstruction.Call(it.getName()).isExternalFn() }
        roots = cfgs.filter { rootNames.contains(it.getName()) }
        verify(checkCFGHasExactlyOneExit, "call graph constructor", false)
        buildDataStructures()
        simplify()
        verify(checkCFGHasExactlyOneExit,"after call graph simplification", true)
    }

    constructor(
        cfgs: Collection<SbfCFG>,
        rootNames: Set<String>,
        globals: GlobalVariableMap,
        check: Boolean = true
    ): this(cfgs.map { it.clone(it.getName()) }.toMutableList(), rootNames, globals, check)

    private fun buildCallGraph() {
        // `buildCallGraph` is called twice: one at the constructor and another one after `simplify`
        // For the second call, we make sure that all internal datastructures are cleared.
        cfgMap.clear()
        callGraph.clear()
        recursiveSet.clear()
        sccVector.clear()
        sccMap.clear()

        // Initially, we map each function name to an empty set of callees.
        cfgs.forEach { cfg ->
            cfgMap[cfg.getName()] = cfg
            callGraph[cfg.getName()] = mutableSetOf()
        }

        for (cfg in cfgs) {
            val callerName = cfg.getName()
            for (block in cfg.getBlocks().values) {
                for (inst in block.getInstructions()) {
                    // Add inst.name to the list of callees of current cfg, unless the function is external
                    if (inst is SbfInstruction.Call && !inst.isExternalFn()) {
                        val calleeName = inst.name
                        // skip if callee does not have a CFG (it should)
                        cfgMap[calleeName] ?: continue

                        val callees = callGraph[callerName]
                        check(callees != null) {"$callerName not found by buildCallGraph"}
                        callees.add(calleeName)
                        callGraph[cfg.getName()] = callees
                    }
                }
            }
        }
    }

    private fun buildSCCs() {
        val sccs = algorithms.TarjanSCCFinding.tarjanSCCFinding(callGraph)
        for (scc in sccs) {
            check(scc.isNotEmpty())
            sccVector.add(scc)
            for (member in scc) {
                sccMap[member] = sccVector.size - 1
            }
        }
    }

    private fun getSCCMember(cfg: String): Set<String> {
        val id = sccMap[cfg]
                ?: // This is possible because in the input program is a shared library,
                // so it might have external calls.
                return setOf()
        return sccVector[id]
    }

    private fun buildRecursiveSet() {
        for (cfg in cfgs) {
            val scc = getSCCMember(cfg.getName())
            if (scc.size == 1) {
                val callees = callGraph[cfg.getName()]
                if (callees != null && callees.contains(cfg.getName())) {
                    recursiveSet.add(cfg.getName())
                }
            } else if (scc.size > 1){
                recursiveSet.addAll(scc)
            }
        }
    }

    private fun buildDataStructures() {
        buildCallGraph()
        buildSCCs()
        buildRecursiveSet()
    }

    /**
     *  Remove any CFG that is not reachable from root
     *  Of course, we assume that the call graph is statically known
     **/
    private fun simplify() {
        val visited: MutableSet<String> = mutableSetOf()
        val worklist = getCallGraphRoots().toMutableList()
        while (worklist.isNotEmpty()) {
            val cur = worklist.removeLast()
            visited.add(cur.getName())
            val succs  = callGraph[cur.getName()]
            if (succs != null) {
                for (succ in succs) {
                    if (!visited.contains(succ)) {
                        val succCFG = getCFG(succ)
                        check(succCFG != null)
                        worklist.add(succCFG)
                    }
                }
            }
        }

        if (visited.size == cfgs.size) {
            /** No simplification takes place **/
        } else {
            val oldNumCFGs = cfgs.size
            cfgs.clear()
            for (name in visited) {
                val cfg = cfgMap[name]
                check(cfg!= null)
                cfgs.add(cfg)
            }

            buildDataStructures()
            sbfLogger.info { "Simplified call graph: #functions before=$oldNumCFGs #functions after=${cfgs.size}" }
        }
    }

    private fun verify(checkCFGHasExactlyOneExit: Boolean, msg: String, printExternal: Boolean) {
        val functions: MutableSet<String> = mutableSetOf()
        for (cfg in cfgs) {
            functions.add(cfg.getName())
            cfg.verify(checkCFGHasExactlyOneExit, msg)
        }
        val externalFns = mutableSetOf<String>()
        for (cfg in cfgs) {
            for (block in cfg.getBlocks().values) {
                for (inst in block.getInstructions()) {
                    if (inst is SbfInstruction.Call) {
                        if (!inst.isExternalFn() && !functions.contains(inst.name)) {
                            if (printExternal) {
                                externalFns.add(inst.name)
                            }
                        }
                    }
                }
            }
        }
        if (printExternal) {
            if (externalFns.isNotEmpty()) {
                sbfLogger.info { "CALLGRAPH INFO\nExternal functions=$externalFns" }
            }
        }
    }


    /** public API **/

    override fun getGlobals() = globalsMap

    override fun getCFGs(): List<SbfCFG>  = cfgs

    fun getMutableCFGs(): List<MutableSbfCFG> = cfgs

    override fun getCallGraphRoots(): List<SbfCFG> {
       return getMutableCallGraphRoots()
    }

    fun getMutableCallGraphRoots() = roots

    override fun getCallGraphRootSingleOrFail(): SbfCFG {
        val root = getCallGraphRoots().singleOrNull()
        check(root != null) { "Callgraph has multiple roots but it should have only one"}
        return root
    }

    override fun getCallGraph(): Map<String, Set<String>> {
        return callGraph
    }

    override fun getCFG(name: String): SbfCFG? {
        return getMutableCFG(name)
    }

    fun getMutableCFG(name: String): MutableSbfCFG? {
        return cfgMap[name]
    }

    override fun transformSingleEntry(f: (SbfCFG) -> SbfCFG): SbfCallGraph {
        val oldEntry = getCallGraphRootSingleOrFail()
        val newEntry = f(oldEntry)
        check(oldEntry.getName() == newEntry.getName()) {"transformSingleEntry does not allow to change the name of the entry CFG"}
        val newCFGs = mutableListOf(newEntry)
        getCFGs().forEach {
            if (it.getName() != newEntry.getName()) {
                newCFGs.add(it.clone(it.getName()))
            }
        }
        return MutableSbfCallGraph(newCFGs, setOf(newEntry.getName()), getGlobals(), check=false)
    }

    override fun transformSingleEntryAndGlobals(f: (SbfCFG) -> Pair<SbfCFG, GlobalVariableMap>): SbfCallGraph {
        val oldEntry = getCallGraphRootSingleOrFail()
        val (newEntry, newGlobals) = f(oldEntry)
        check(oldEntry.getName() == newEntry.getName()) {"transformSingleEntryAndGlobals does not allow to change the name of the entry CFG"}
        val newCFGs = mutableListOf(newEntry)
        getCFGs().forEach {
            if (it.getName() != newEntry.getName()) {
                newCFGs.add(it.clone(it.getName()))
            }
        }
        return MutableSbfCallGraph(newCFGs, setOf(newEntry.getName()), newGlobals, check=false)
    }

    override fun getRecursiveFunctions(): Set<String> {
        return recursiveSet
    }

    override fun toString(): String {
        var str = ""
        for (cfg in cfgs) {
            str += "$cfg"
        }
        return str
    }

    override fun callGraphStructureToString(): String {
        var str = "=== Callgraph ===\n"
        for ((node, edges) in callGraph) {
            str += "$node calls\n"
            for (callee in edges) {
                str += "\t${callee}\n"
            }
            str += "\n"
        }
        return str
    }

    override fun callGraphStructureToDot(prefix: File) {
        val rootsStr = roots.map { it.getName() }.joinToString(separator = "-")
        val sb = StringBuilder()
        sb.append("digraph \"Callgraph for roots $rootsStr\" {\n")
        sb.append("\tlabel=\"Callgraph for roots $rootsStr\";\n")
        for ((node, edges) in callGraph) {
            for (edge in edges) {
                sb.append("\"$node\" -> \"$edge\"\n")
            }
        }
        sb.append("}\n")
        printToFile("$prefix${File.separator}callgraph-$rootsStr.sbf.dot", sb.toString())
    }

    override fun toDot(prefix:File, onlyEntryPoint: Boolean) {
        if (onlyEntryPoint) {
            for (cfg in getCallGraphRoots()) {
                printToFile("$prefix${File.separator}${cfg.getName()}.sbf.dot", cfg.toDot())
            }
        } else {
            for (cfg in cfgs) {
                printToFile("$prefix${File.separator}${cfg.getName()}.sbf.dot", cfg.toDot())
            }
        }
    }

    override fun getStats(): CFGStats {
        var stats = CFGStats()
        for (cfg in getCFGs()) {
            stats = stats.add(cfg.getStats())
        }
        return stats
    }
}


