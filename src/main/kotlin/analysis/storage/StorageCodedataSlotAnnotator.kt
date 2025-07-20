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

package analysis.storage

import analysis.CmdPointer
import analysis.TACCommandGraph
import analysis.maybeNarrow
import datastructures.stdcollections.*
import disassembler.DisassembledEVMBytecode
import tac.MetaKey
import utils.mapToSet
import utils.toIntOrNull
import utils.toPositiveBigIntegerOrNull
import vc.data.*
import java.math.BigInteger
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Annotates codedata accesses in a contract to literal offsets with the actual value from codedata
 *
 * Because we're a bit nervous[1] about simply performing this transformation *everywhere*,
 * this analysis looks for codedata reads that flow _exclusively_ into storage accesses.
 *
 * [1] it has been explained to me that there is concern that the 'bytecode' field of
 * our contract representation may not be a faithful representation of the actual
 * bytecode, and hence we may get garbage by eagerly evaluating constant codedata accesses.
 */
object StorageCodedataSlotAnnotator {
    val KEY = MetaKey<BigInteger>(name = "tac.storage.codedata.consteval.slot")

    /* A `_ := tacCodedata[ptr] @ [where]` */
    private data class CodedataSource(
        val where: CmdPointer,
        val ptr: BigInteger,
    )

    /**
     * Search for codedata accesses with constant offsets, and annotate them with the result of the read
     * when the value flows exclusively into storage pointers.
     *
     * @return a [code] with [StorageCodedataSlotAnnotator.KEY] meta applied to codedata read commands.
     */
    fun annotateCodedataReads(bytecode: DisassembledEVMBytecode?, code: CoreTACProgram): CoreTACProgram {
        if (bytecode == null) {
            return code
        }
        val storageLocDefs = findStorageLocDefs(code)
        val codedataSources = findCodedataSources(code.analysisCache.graph, storageLocDefs)
        return code.patching { p ->
            annotateCodedataReads(analysisCache.graph, p, codedataSources, bytecode)
        }
    }

    /**
     * @return the set of cmd pointers p s.t.
        p is a storage access command whose loc is only ever used
        as a storage access command's loc
     */
    private fun findStorageLocDefs(code: CoreTACProgram): Set<CmdPointer> {
        val graph = code.analysisCache.graph
        // Grab these outside of the parallel loop to avoid deadlock
        val def = graph.cache.def
        val use = graph.cache.use
        return code.parallelLtacStream().flatMap {
            if (it.cmd is TACCmd.Simple.StorageAccessCmd
                && it.cmd.base.isStorageOrTransientStorage()) {
                (it.cmd.loc as? TACSymbol.Var)?.let { loc ->
                    def.defSitesOf(loc, it.ptr)
                        .filter {
                            use.useSitesAfter(loc, it).all {
                                val useCmd = graph.elab(it).cmd
                                useCmd is TACCmd.Simple.AnnotationCmd ||
                                    (useCmd is TACCmd.Simple.StorageAccessCmd && useCmd.loc == loc)
                            }
                        }
                }.orEmpty().stream()
            } else {
                Stream.empty()
            }
        }.collect(Collectors.toSet())
    }

    /**

     * Compute the set of codedata sources reachable from the given seed locations [storageLocDefs].
     *
     * The returned set of codedata sources enjoy the property that the values read from codedata
     * flow are used exclusively as storage pointers (but are perhaps assigned to intermediate values).
     */
    private fun findCodedataSources(graph: TACCommandGraph, storageLocDefs: Set<CmdPointer>): Set<CodedataSource> {
        val flowToStorageLocDefs = storageLocDefs.toMutableSet()
        var changed = true

        while (changed) {
            changed = false
            val toAdd = mutableSetOf<CmdPointer>()
            for (ptr in flowToStorageLocDefs) {
                changed = followUseDef(graph, ptr, flowToStorageLocDefs, toAdd) || changed
            }

            flowToStorageLocDefs.addAll(toAdd)
        }

        return flowToStorageLocDefs.mapNotNull {
            graph.elab(it).maybeNarrow<TACCmd.Simple.AssigningCmd.ByteLoad>()?.takeIf {
                it.cmd.base.meta.containsKey(TACMeta.CODEDATA_KEY) && it.cmd.loc is TACSymbol.Const
            }
        }.mapToSet {
            CodedataSource(it.ptr, (it.cmd.loc as TACSymbol.Const).value)
        }
    }

    private fun DisassembledEVMBytecode.evaluateBytecodeWordAt(loc: BigInteger): BigInteger? {
        return loc.toIntOrNull()?.let { locInt ->
            bytes.toPositiveBigIntegerOrNull(
                start = locInt,
                len = 32
            )
        }
    }

    /** Annotate each codedata read with the value of the read */
    private fun annotateCodedataReads(
        graph: TACCommandGraph,
        patching: SimplePatchingProgram,
        codedata: Set<CodedataSource>,
        bytecode: DisassembledEVMBytecode
    ) {
        for ((cmdPtr, loc) in codedata) {
            val lcmd = graph.elab(cmdPtr)
            check (lcmd.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && lcmd.cmd.base.meta.containsKey(TACMeta.CODEDATA_KEY)) {
                "Broken invariant: selected ${lcmd} as a codedata read"
            }

            val loadValue = bytecode.evaluateBytecodeWordAt(loc) ?: continue

            patching.replaceCommand(
                cmdPtr, listOf(lcmd.cmd.plusMeta(KEY, loadValue))
            )
        }
    }

    /*
     * If [ptr]  is x := y or If(b, y, z), then add the definitions of y (and z) to [accumulator]
     * when y and z's uses are contained in [storageLocDefs]
     */
    private fun followUseDef(
        graph: TACCommandGraph,
        ptr: CmdPointer,
        storageLocDefs: Set<CmdPointer>,
        accumulator: MutableSet<CmdPointer>,
    ): Boolean {
        return when (val cmd = graph.elab(ptr).cmd) {

            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                val inVars = when (cmd.rhs) {
                    is TACExpr.Sym.Var ->
                        setOf(cmd.rhs.s)

                    is TACExpr.TernaryExp.Ite ->
                        setOfNotNull(cmd.rhs.o2AsNullableTACSymbol(), cmd.rhs.o3AsNullableTACSymbol())

                    else ->
                        setOf()
                }
                inVars.fold(false) { changed, x ->
                    addAcceptableDefs(graph, x, ptr, storageLocDefs, accumulator) || changed
                }
            }
            else ->
                false
        }
    }


    /**
     * Add the definitions d of [x]@[where] to [accumulator] s.t.
     * all of d's uses are in [storageLocDefs]
     */
    private fun addAcceptableDefs(
        graph: TACCommandGraph,
        x: TACSymbol,
        where: CmdPointer,
        storageLocDefs: Set<CmdPointer>,
        accumulator: MutableSet<CmdPointer>,
    ): Boolean {
        if (x !is TACSymbol.Var) {
            return false
        }

        var changed = false
        for (xDef in graph.cache.def.defSitesOf(x, where)) {
            if (xDef in storageLocDefs) {
                continue
            }
            val nonJunkUses = graph.cache.use
                .useSitesAfter(x, xDef)
                .filterNot {
                    graph.elab(it).cmd is TACCmd.Simple.AnnotationCmd
                }

            if (storageLocDefs.containsAll(nonJunkUses)) {
                accumulator.add(xDef)
                changed = true
            }
        }

        return changed
    }
}
