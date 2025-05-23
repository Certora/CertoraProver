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

package analysis.icfg

import analysis.CmdPointer

/**
 * For each byte load in the domain, the value being read is value put into the return buffer at offset
 * [analysis.icfg.CallGraphBuilder.ReturnPointer.offset] at the call at [analysis.icfg.CallGraphBuilder.ReturnPointer.lastCall]
 */
@JvmInline
value class CalleeReturnReadResolution(val readToCallReturn: Map<CmdPointer, CallGraphBuilder.ReturnPointer>)
