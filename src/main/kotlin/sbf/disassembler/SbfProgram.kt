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

package sbf.disassembler

import sbf.cfg.SbfInstruction
import com.certora.collect.*
import dwarf.DebugSymbols
import log.*

sealed class Label {
    /** @return a fresh label derived from this label */
    abstract fun refresh(): Label

    /** A label corresponding to an original program address */
    data class Address(val address: Long): Label() {
        override fun refresh(): Label {
            return Refresh(address, allocFresh())
        }
        override fun toString() = "$address"
    }

    /** A label derived from an original program label */
    private data class Refresh(val address: Long, val fresh: Long): Label() {
        override fun refresh(): Label {
            return this.copy(fresh = allocFresh())
        }
        override fun toString() = "${address}_${fresh}"
    }

    /** An invented program label */
    private data class Fresh(val fresh: Long): Label() {
        override fun refresh(): Label {
            return this.copy(fresh = allocFresh())
        }
        override fun toString() = "fresh_${fresh}"
    }

    companion object {
        private var ctr: Long = 0L

        private fun allocFresh(): Long = ctr++

        /** Create a new fresh label */
        fun fresh(): Label {
            return Fresh(allocFresh())
        }
    }
}

typealias SbfLabeledInstruction = Pair<Label, SbfInstruction>

/**
 *  Representation of a global variable.
 *  - [size] can be 0 if its size is unknown.
 *  - If [strValue] is not null then the global variable is known to be a constant string and [strValue] is the actual string.
 **/
data class SbfGlobalVariable(
    val name: String,
    val address: ElfAddress,
    val size: Long,
    val strValue: String? = null
) {
    fun isSized() = size > 0L
}

/**
 * Represents the global state of an SBF program.
 *
 * This class provides access to the underlying ELF file ([elf]), enabling retrieval of
 * values for read-only global variables. It also performs a static partitioning
 * of the global memory region, where each global variable corresponds to a
 * distinct, non-overlapping subregion of global memory.
 *
 * The partitioning is maintained by [map] and follows:
 *
 * - **Invariant:** No overlaps — each address belongs to at most one partition.
 * - **Assumption (unchecked):** An unsized global (size = 0) is considered
 *   "infinitely far apart" from any other global and therefore never overlaps.
 */
data class GlobalVariables(
    val elf: IElfFileView,
    private val map: TreapMap<ElfAddress, SbfGlobalVariable> = treapMapOf()) {

    private val logger = Logger(LoggerTypes.SBF_GLOBAL_VAR_ANALYSIS)

    companion object {
        operator fun invoke(elf: IElfFileView, globalVars: List<SbfGlobalVariable>): GlobalVariables {
            return globalVars.fold(GlobalVariables(elf)) { acc, gv -> acc.add(gv) }
        }
    }

    fun add(newGv: SbfGlobalVariable): GlobalVariables {
        val addr = newGv.address
        val oldGv = findGlobalThatContains(addr)
            ?: return GlobalVariables(elf, map.put(addr, newGv))
        if (oldGv != newGv) {
            logger.warn { "$newGv was not added because it overlaps with $oldGv" }
        }
        return this
    }

    fun remove(gv: SbfGlobalVariable) = copy(map = map.remove(gv.address))

    fun contains(addr: ElfAddress) = findGlobalThatContains(addr) != null

    /**
     * Find a global variable in `map` that contains [addr].
     * For that, the size of each stored global variable is taken into account.
     * In the worst case, this is a linear search. The reason for this search is that we can have the following:
     *
     * ```
     * map = [ 220755 -> NumGlobalVar(addr=220755,sz=1,value=0,...),
     *         220754 -> NumGlobalVar(addr=220754,sz=1,value=1,...),
     *         220721 -> NumGlobalVar(addr=220721,sz=1,value=2...),
     *         220720 -> NumGlobalVar(addr=220720,sz=1,...),
     *         220688 -> GlobalVar(addr=220688,sz=356,...), ...]
     *
     * addr = 220756
     *```
     * In this case, the return global variable is `GlobalVar(addr=220688,sz=356,...)`.
    **/
    fun findGlobalThatContains(addr: ElfAddress): SbfGlobalVariable? {
        var entry = map.floorEntry(addr)
        while (entry != null) {
            val gv = entry.value
            val start = gv.address
            val end = start + gv.size
            when  {
                !gv.isSized() && addr == start -> {
                    return gv
                }
                addr in start until end -> {
                    return gv
                }
                else -> {
                    entry = map.lowerEntry(entry.key)
                }
            }
        }
        return null
    }

    override fun toString() = map.toString()
}

/**
 * An SBF program is a sequence of SBF instructions, not yet a CFG.
 **/
data class SbfProgram(val entriesMap: Map<String, ElfAddress>, val funcMan: SbfFunctionManager,
                      val globals: GlobalVariables,
                      val debugSymbols: DebugSymbols,
                      val program: List<SbfLabeledInstruction>) {
    override fun toString(): String {
        val strBuilder = StringBuilder()
        for (inst in program) {
            strBuilder.append("${inst.first}: ${inst.second}\n")
        }
        return strBuilder.toString()
    }
}
