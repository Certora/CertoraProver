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

import sbf.cfg.*
import sbf.disassembler.*
import sbf.SolanaConfig
import sbf.callgraph.*
import sbf.domains.MemorySummaries
import sbf.domains.SbfType

/**
 * Whole-program analysis that identifies global variables that were not part of the ELF symbol table.
 *
 * In principle, global variables can be extracted from the ELF symbol table. Unfortunately, there is no guarantee that the symbol
 * table contains all program global variables. This analysis infers new global variables by their use. If an absolute
 * address in certain range is de-referenced then it is classified as a global variable.
 * Whether the analysis misses a global variable or not doesn't affect soundness.
 * However, it **does affect soundness** whether two addresses `x` and `x+k` are
 * considered as a global variable starting at address `x` and offset `k` from `x`, respectively or two distinct global variables
 * starting at `x` and `x+k`. We have some heuristics to distinguish between the two cases but ultimately the analysis might
 * mis-classify two addresses as two different global variables which might affect soundness.
 * This is why the analysis is not executed by default.
 *
 * Note that `runGlobalInferenceAnalysis` will run `ScalarAnalysis` from scratch, but a reasonable question is why we do not
 * infer global variables as part of `MemoryAnalysis` which runs a reduced product of scalar and pointer domains.
 *
 * We do not do it because the global inference analysis has some heuristics that scan multiple basic blocks
 * searching for some specific code patterns. These heuristics are harder to implement as part of an abstract domain because
 * the current API for an abstract domain is designed to just analyze one instruction at the time.
 *
 * We do not bother at the moment to change the API of an abstract domain because running `ScalarAnalysis` is
 * currently very cheap, but we might need to revisit these design decisions if `ScalarAnalysis` becomes more expensive.
 *
 * [globalsSymTable] is used to extract constant strings from the ELF file and answer queries about whether an address is a global variable or not.
 **/

fun runGlobalInferenceAnalysis(
    prog: SbfCallGraph,
    memSummaries: MemorySummaries,
    globalsSymTable: IGlobalsSymbolTable
) : SbfCallGraph {
    return prog.transformSingleEntryAndGlobals { entryCFG ->
        val newEntryCFG = entryCFG.clone(entryCFG.getName())
        val scalarAnalysis = ScalarAnalysis(newEntryCFG, prog.getGlobals(), memSummaries)
        val globalInferAnalysis = GlobalInferenceAnalysis(newEntryCFG, scalarAnalysis, globalsSymTable)
        Pair(newEntryCFG, globalInferAnalysis.getNewGlobalMap())
    }
}

/**
 * Analysis to identify global variables that are not in the ELF symbol table.
 * We identify them by use. If an address in some expected range is de-referenced then it is classified as a global variable.
 *
 * [cfg] is mutable because the analysis adds some metadata useful for other analyses.
 **/
private class GlobalInferenceAnalysis(private val cfg: MutableSbfCFG,
                                      private val scalar: ScalarAnalysis,
                                      private val globalsSymTable: IGlobalsSymbolTable) {
    private var id:UInt = 1U
    private val regTypes = AnalysisRegisterTypes(scalar)
    private var newGlobals: GlobalVariableMap = scalar.globalsMap

    init {
        run()
    }

    private fun isGlobalVariable(x: ULong): Boolean {
        return if (x > Long.MAX_VALUE.toULong()) {
            false
        } else {
            globalsSymTable.isGlobalVariable(x.toLong())
        }
    }

    private fun isNum(i: LocatedSbfInstruction, v: Value):Boolean {
        return when (v) {
            is Value.Imm -> { true }
            is Value.Reg -> {
                val type = regTypes.typeAtInstruction(i, v.r)
                type is SbfType.NumType
            }
        }
    }

    /**
      * This function is called when [reg] is being de-referenced.
      * If the analysis believes that [reg] is a number then we try to identify if that number is the start address of a global variable.
     **/
    private fun inferGlobalVariable(i: LocatedSbfInstruction, reg: Value.Reg)
        : SbfGlobalVariable? {
        return when(val type = regTypes.typeAtInstruction(i, reg.r)) {
            is SbfType.PointerType.Global -> {
                type.global
            }
            is SbfType.NumType -> {
                val gv = recurseInferStartOfGlobalVar(i, reg, 10) // maxChainLen can be also a CLI
                if (gv == null) {
                    null
                } else {
                    newGlobals = newGlobals.put(gv.address, gv)
                    return gv
                }
            }
            else -> {
                null
            }
        }
    }

    private fun inferStringGlobalVariable(i: LocatedSbfInstruction, strReg: Value.Reg, sizeReg: Value.Reg)
        : SbfConstantStringGlobalVariable? {
        val gv = inferGlobalVariable(i, strReg) ?: return null
        val sizeType = regTypes.typeAtInstruction(i, sizeReg.r)
        return if (sizeType is SbfType.NumType) {
            val size = sizeType.value.get() ?: return null
            val strGv = globalsSymTable.getAsConstantString(gv.name, gv.address, size)
            newGlobals = newGlobals.put(gv.address, strGv)
            strGv
        } else {
            null
        }
    }

    /**
     * Return non-null if [operand] is transitively either:
     * (1) a number that is recognized heuristically as the address of a global variable, or
     * (2) the result of adding/subtracting an offset to (1)
     *
     * The function is recursive but limited by maxChainLen to ensure a small number of recursive calls.
     * Note that returning null is always sound.
     *
     * For instance,
     *
     * ```
     *   r1 := 976432
     *   r6 := r1
     *   r6 := r6 + r3:num
     *   *(r6+0) := ...
     * ```
     * should infer that `976432` is a global variable.
     *
     * ```
     *   r1 := 976432
     *   r6 := r1
     *   r6 := r6 + 4
     *   r3 := r6
     *   r3 := r3 - 2
     *   *(r3+0) := ...
     * ```
     * should infer that `976432` is a global variable, but not `976434` since we care about the start address of a global variable.
     **/
    private fun recurseInferStartOfGlobalVar(i:LocatedSbfInstruction, operand: Value, maxChainLen: Int): SbfGlobalVariable? {
        return if (maxChainLen == 0) {
           null
        } else {
            when (operand) {
                is Value.Imm -> {
                    val address = operand.v
                    if (isGlobalVariable(address)) {
                        SbfGlobalVariable("inferred_global.${id++}", address.toLong(), 0)
                    } else {
                        null
                    }
                }
                is Value.Reg -> {
                    continueInferStartOfGlobalVar(i, operand, maxChainLen)
                        ?: if (!SolanaConfig.AggressiveGlobalDetection.get()) {
                            null
                        } else {
                            // The common case for being here is when the block or its ancestors have more than one predecessor.
                            // In that case, `findDefinitionInterBlock` bails out.
                            // However, we have seen code where we cannot give up in this case.
                            // Our solution is to ask the scalar analysis for the value of operand. The only problem with this solution
                            // is that we won't know where the value is originated from an assignment or from some addition/subtraction
                            // with a constant offset and therefore, we might fail at detecting the start of the global variable.
                            val address = (regTypes.typeAtInstruction(i, operand.r) as? SbfType.NumType)?.value?.get()
                            if (address != null && isGlobalVariable(address.toULong())) {
                                SbfGlobalVariable("inferred_global.${id++}", address, 0)
                            } else {
                                null
                            }
                        }
                }
            }
        }
    }

    /** Try to find the definition of [operand] and continue the search for the global variable **/
    private fun continueInferStartOfGlobalVar(i:LocatedSbfInstruction, operand: Value.Reg, maxChainLen: Int): SbfGlobalVariable? {
        val b = scalar.cfg.getBlock(i.label)
        check(b != null) { "GlobalInferenceAnalysis cannot find block ${i.label}" }
        val defLocInst = findDefinitionInterBlock(b, operand, i.pos)
        return if (defLocInst == null) {
            null
        } else {
            recurseInferStartOfGlobalVar(defLocInst, maxChainLen - 1)
        }
    }


    private fun recurseInferStartOfGlobalVar(i: LocatedSbfInstruction,  maxChainLen: Int): SbfGlobalVariable? {
        val inst = i.inst
        return if (inst is SbfInstruction.Bin) {
            when (inst.op) {
                BinOp.MOV -> {
                    val gv = recurseInferStartOfGlobalVar(i, inst.v, maxChainLen)
                    if (gv != null) {
                        addAnnotation(i)
                    }
                    return gv
                }
                BinOp.ADD, BinOp.SUB -> {
                    if (!SolanaConfig.AggressiveGlobalDetection.get()) {
                        null
                    } else {
                        val op1 = inst.dst
                        val op2 = inst.v
                        val gv1 = recurseInferStartOfGlobalVar(i, op1, maxChainLen)
                        val gv2 = recurseInferStartOfGlobalVar(i, op2, maxChainLen)
                        if (gv1 != null && (gv2 == null && isNum(i, op2))) {
                            gv1
                        } else if (gv2 != null && (gv1 == null && isNum(i, op1))) {
                            gv2
                        } else {
                            null
                        }
                    }
                }
                else -> {
                    null
                }
            }
        } else {
            null
        }
    }

    /**
     * Annotate [locInst] with metadata that stating that it is an assignment that stores the address of
     * a global variable into a registers.
     */
    private fun addAnnotation(locInst: LocatedSbfInstruction) {
        val block = cfg.getMutableBlock(locInst.label)
        val i = locInst.pos
        check(block != null) {"GlobalInferenceAnalysis cannot find block $block"}
        val oldInst = block.getInstruction(i)
        if (oldInst is SbfInstruction.Bin && oldInst.op == BinOp.MOV) {
            val newInst = oldInst.copy(metaData = oldInst.metaData.plus(Pair(SbfMeta.SET_GLOBAL, "")))
            block.replaceInstruction(i, newInst)
        }
    }

    /**
     * Build a map that contains global variables that were not original in the ELF symbol table,
     * but the analysis believes that they are actually global variables.
     */
    private fun run() {
        for (bb in cfg.getMutableBlocks().values)  {
            for (locInst in bb.getLocatedInstructions()) {
                val inst = locInst.inst
                if (inst is SbfInstruction.Mem) {
                    val reg = inst.access.baseReg
                    inferGlobalVariable(locInst, reg)
                } else if (inst is SbfInstruction.Call) {
                    val solFunction = SolanaFunction.from(inst.name)
                    if (solFunction == SolanaFunction.SOL_MEMCMP ||
                        solFunction == SolanaFunction.SOL_MEMCPY ||
                        solFunction == SolanaFunction.SOL_MEMMOVE) {
                        inferGlobalVariable(locInst, Value.Reg(SbfRegister.R1_ARG))
                        inferGlobalVariable(locInst, Value.Reg(SbfRegister.R2_ARG))
                    } else {
                        val calltraceFunction = CVTCalltrace.from(inst.name)
                        calltraceFunction?.strings?.forEach {
                            // Even if AggressiveGlobalDetection is disabled we do identify strings used for calltrace.
                            inferStringGlobalVariable(locInst, it.string, it.len)
                        }
                    }
                }
            }
        }
    }

    fun getNewGlobalMap(): GlobalVariableMap  = newGlobals
}
