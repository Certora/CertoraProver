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

package move

import compiler.SourceSegment
import datastructures.*
import datastructures.stdcollections.*
import tac.*
import utils.*
import vc.data.*

/**
    Represents a call to a Move function during TAC generation.
 */
data class MoveCall(
    val callee: MoveFunction,
    val args: List<TACSymbol.Var>,
    val returns: List<TACSymbol.Var>,
    /** The block ID to use for the first block of the callee */
    val entryBlock: NBId,
    /** The block ID the callee should return to */
    val returnBlock: NBId,
    /** The calls on the stack (not including this call) */
    val callStack: PersistentStack<MoveCall>,
    /** The source range of the call */
    val source: SourceSegment?
) {
    val funcsOnStack = callStack.mapToSet { it.callee }
    val range get() = source?.range
    val displaySource get() = source?.let {
        "at ${it.fileName}:${it.lineNumber}: ${it.content.lines().firstOrNull()?.escapeQuotes()}"
    }
}
