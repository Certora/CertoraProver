/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package sbf.support

import dwarf.DebugInfoReader
import utils.Range

object SolanaCalltraceUtil {
    /**
     * Converts an SBF address from the metadata of the given TAC command to a range.
     * Returns null if the SBF metadata is not present or if it is not possible to resolve the range information.
     * Tries to resolve the inlined frames associated also to previous SBF addresses until [address - windowSize].
     */
    fun sbfAddressToRangeWithHeuristic(
        sbfAddress: ULong,
    ): Range.Range? {
            val windowSize = 80U
            // Consider address, address - 8, address - 16, ..., address - (windowSize + 8)
            val addresses: MutableList<ULong> = mutableListOf()
            var nextAddress = sbfAddress
            // The first condition is to check the absence of underflows.
            while (sbfAddress <= nextAddress && sbfAddress - nextAddress <= windowSize) {
                addresses.add(nextAddress)
                nextAddress -= 8U
            }
            val rangesMap = DebugInfoReader.getInlinedFramesInSourcesDir(addresses)
            // Iterate over the addresses: address, address - 8, address - 16, ...
            // The first address that is associated with non-null range information will be the returned address.
            for (addr in addresses) {
                rangesMap[addr]?.let { ranges ->
                    ranges.firstOrNull { range ->
                        return range
                    }
                }
            }
            return null
        }
}
