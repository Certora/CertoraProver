/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

package sbf.domains

import log.Logger
import sbf.SolanaConfig
import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.SbfBasicBlock
import sbf.cfg.SbfMeta

/**
 * Filters SBF locations against a user-provided set of strings. A block matches
 * if its label (as printed) is in the set. An instruction matches if its block
 * label is in the set, or if its bytecode address is in the set.
 **/
object SbfLocationFilter {
    private fun targets(): Set<String>? = SolanaConfig.PrintInvariantsAt.getOrNull()

    fun isActive() = targets() != null

    fun matches(b: SbfBasicBlock): Boolean = targets()?.contains(b.getLabel().toString()) == true

    fun matches(locInst: LocatedSbfInstruction): Boolean {
        val t = targets()
        if (t?.contains(locInst.label.toString()) == true ) {
            return true
        }
        val addr = locInst.inst.metaData.getVal(SbfMeta.SBF_ADDRESS) ?: return false
        return t?.contains("0x${addr.toString(16)}") == true
    }
}

/**
 * Log [msg] via [Logger.info] when [SbfLocationFilter] is inactive or when block [b] matches.
 **/
fun Logger.dbg(b: SbfBasicBlock, msg: () -> Any) {
    if (SbfLocationFilter.isActive() && !SbfLocationFilter.matches(b)) {
        return
    }
    info(msg)
}

/**
 * Log [msg] via [Logger.info] when [SbfLocationFilter] is inactive or when instruction [locInst] matches.
 **/
fun Logger.dbg(locInst: LocatedSbfInstruction, msg: () -> Any) {
    if (SbfLocationFilter.isActive() && !SbfLocationFilter.matches(locInst)) {
        return
    }
    info(msg)
}
