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

package sbf.analysis

import datastructures.stdcollections.*
import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.sbfLogger
import sbf.fixpoint.*
import sbf.support.printToFile
import java.io.File

/**
 *  Run a whole-program analysis on the SBF program using the memory domain.
 *
 *  The analysis is flow-sensitive but it is INTRA-PROCEDURAL.
 *  Therefore, call functions should be INLINED to get reasonable results.
**/
class WholeProgramMemoryAnalysis<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>(
    val program: SbfCallGraph,
    val memSummaries: MemorySummaries,
    val sbfTypesFac: ISbfTypeFactory<TNum, TOffset>,
    private val flagsFac: () -> TFlags,
    val processor: InstructionListener<MemoryDomain<TNum, TOffset, TFlags>>?) {
    private val results : MutableMap<String, MemoryAnalysis<TNum, TOffset, TFlags>> = mutableMapOf()

    fun inferAll() {
        val cfg = program.getCallGraphRootSingleOrFail()
        sbfLogger.info {"  Started memory analysis of ${cfg.getName()}... "}
        val analysis = MemoryAnalysis(cfg, program.getGlobals(), memSummaries, sbfTypesFac, flagsFac, processor)
        sbfLogger.info {"  Finished memory analysis of ${cfg.getName()} ... "}
        results[cfg.getName()] = analysis
    }

    fun getResults(): Map<String, MemoryAnalysis<TNum, TOffset, TFlags>> = results

    override fun toString(): String {
        val printInvariants = true
        class PrettyPrinter(val analysis: MemoryAnalysis<TNum, TOffset, TFlags>, val sb: StringBuilder): DfsVisitAction {
            override fun applyBeforeChildren(b: SbfBasicBlock) {
                val pre = analysis.getPre(b.getLabel())
                sb.append("/** PRE-invariants \n")
                sb.append(if (pre != null) {
                    "${pre}\n"
                }  else {
                    "No results\n"
                })
                sb.append("**/\n")
                sb.append("$b\n")
                val post= analysis.getPost(b.getLabel())
                sb.append("/** POST-invariants \n")
                sb.append(if (post != null) {
                    "${post}\n"
                }  else {
                    "No results\n"
                })
                sb.append("**/\n")
            }
            override fun applyAfterChildren(b: SbfBasicBlock) {}
            override fun skipChildren(b: SbfBasicBlock): Boolean { return false}
        }

        val sb = StringBuilder()
        for (cfg in program.getCFGs()) {
            val analysis = results[cfg.getName()]
                    ?: // This is possible if we decide to analyze only entrypoint
                    continue
            if(!printInvariants) {
                // Print the annotated CFG
                sb.append("$cfg ")
            } else {
                // Print the annotated CFG + types+points-to invariants
                sb.append("function ${cfg.getName()}\n")
                val vis = PrettyPrinter(analysis, sb)
                dfsVisit(cfg.getEntry(), vis)
            }
        }
        return sb.toString()
    }

    fun toDot(printInvariants: Boolean) {
        var i = 0
        for ((_, analysis) in results) {
            val outfile = if (printInvariants) {
                "${program.getCFGs()[i].getName()}.$i.invariants.dot"
            } else {
                "${program.getCFGs()[i].getName()}.$i.cfg.dot"
            }
            printToFile(outfile, analysis.toDot(printInvariants))
            i++
        }
    }

    /**
     *  Dump to a separate file the graph in dot format of any basic block in function [fname] that
     *  satisfies [pred].
     **/
    fun dumpPTAGraphsSelectively(outputDir: File,
                                 fname: String,
                                 pred: (SbfBasicBlock) -> Boolean = { b ->
                                    b.getInstructions().any { inst -> inst.isAssertOrSatisfy()}
                                 }) {
        val memAnalysis = results[fname]
        if (memAnalysis != null) {
            for (b in memAnalysis.cfg.getBlocks().values) {
                if (pred(b)) {
                    val memAbsVal = memAnalysis.getPre(b.getLabel())
                    if (memAbsVal != null) {
                        val g = memAbsVal.getPTAGraph()
                        printToFile("$outputDir${File.separator}${b.getLabel()}.ptagraph.dot",
                                    g.toDot(false, "${b.getLabel()}"))
                    }
                }
            }
        }
    }
}

/**
 * Run the memory analysis on [cfg]
 * @param cfg is the CFG under analysis
 * @param globalsMap contains information about global variables
 * @param memSummaries user-provided summaries
 * @param sbfTypesFac factory to create numbers and offsets used by SBF types
 * @param processor post-process each basic block after fixpoint has been computed
 **/
open class MemoryAnalysis<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    (open val cfg: SbfCFG,
     open val globalsMap: GlobalVariableMap,
     open val memSummaries: MemorySummaries,
     open val sbfTypesFac: ISbfTypeFactory<TNum, TOffset>,
     open val flagsFac: () -> TFlags,
     open val processor: InstructionListener<MemoryDomain<TNum, TOffset, TFlags>>?,
     private val isEntryPoint: Boolean = true): IAnalysis<MemoryDomain<TNum, TOffset, TFlags>> {

    // Invariants that hold at the beginning of each basic block
    private val preMap: MutableMap<Label, MemoryDomain<TNum, TOffset, TFlags>> = mutableMapOf()
    // Invariants that hold at the end of each basic block
    private val postMap: MutableMap<Label, MemoryDomain<TNum, TOffset, TFlags>> = mutableMapOf()

    init {
        run()
    }

    override fun getPre(block: Label) = preMap[block]
    override fun getPost(block: Label) = postMap[block]

    private fun run() {
        val nodeAllocator = PTANodeAllocator(flagsFac)
        val entry = cfg.getEntry()
        val bot = MemoryDomain.makeBottom(nodeAllocator, sbfTypesFac)
        val top = MemoryDomain.makeTop(nodeAllocator, sbfTypesFac)
        val opts = WtoBasedFixpointOptions(2U,1U)
        val fixpo = WtoBasedFixpointSolver(bot, top, opts, globalsMap, memSummaries)
        if (isEntryPoint) {
            preMap[entry.getLabel()] = MemoryDomain.initPreconditions(nodeAllocator, sbfTypesFac)
        }
        val liveMapAtExit = LivenessAnalysis(cfg).getLiveRegistersAtExit()
        fixpo.solve(cfg, preMap, postMap, liveMapAtExit, processor)

        /**
         * The transfer function of `memcpy` requires knowledge of all possible source links to correctly transfer them
         * to the destination. However, because the analysis allows copying uninitialized memory, some or all of these links
         * may not yet be known when the forward analysis first encounters the `memcpy`. They become available only after
         * the forward analysis completes, since all memory regions except the stack are analyzed in a __flow-insensitive__ manner.
         *
         * Therefore, some `memcpy` transfer functions are skipped or only partially executed until the forward analysis converges.
         * After convergence, we must execute again all `memcpy` that transfer memory from non-stack regions.
         * Because invariants are stored only at block entry/exit, this extra pass re-applies the transfer functions
         * to generate invariants at each instruction. Importantly, no fixpoint is required.
         * A single pass suffices, since the additional unifications from `memcpy` cannot introduce new links.
         **/
        for (block in cfg.getBlocks().values) {
            getPre(block.getLabel())
                ?.takeUnless { it.isBottom() }
                ?.analyze(block, globalsMap, memSummaries)
        }
    }

    // Print the CFG annotated with invariants to dot format
    // Assume that infer() has been called
    fun toDot(printInvariants: Boolean): String {
        if (!printInvariants) {
            return cfg.toDot()
        } else {
            fun getAnnotationFn(b: SbfBasicBlock): Pair<String?, Boolean?> {
                val invariants = this.postMap[b.getLabel()] ?: return Pair(null, null)
                val str = invariants.getPTAGraph().toDot(true, b.getLabel().toString())
                return if (str == "") {
                    Pair(null, null)
                } else {
                    Pair(str, true)
                }
            }
            return cfg.toDot(annotations = ::getAnnotationFn)
        }
    }

    override fun getCFG() = cfg
    override fun getMemorySummaries() = memSummaries
    override fun getGlobalVariableMap() = globalsMap
}
