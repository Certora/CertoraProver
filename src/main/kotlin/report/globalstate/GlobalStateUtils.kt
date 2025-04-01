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

package report.globalstate

import analysis.*
import datastructures.stdcollections.listOf
import report.calltrace.CallEndStatus
import report.calltrace.formatter.FormatterType
import report.calltrace.sarif.Sarif
import report.hasFailedInModel
import solver.CounterexampleModel
import utils.Range
import tac.NBId
import tac.StartBlock
import vc.data.SnippetCmd
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.state.TACValue

enum class ComputationalTypes(val callEndStatus: CallEndStatus) {
    UNKNOWN(CallEndStatus.VARIABLE_UNKNOWN),
    DONT_CARE(CallEndStatus.VARIABLE_DONT_CARE), // value overwritten before used
    CONCRETE(CallEndStatus.VARIABLE_CONCRETE),
    HAVOC(CallEndStatus.VARIABLE_HAVOC),
    HAVOC_DEPENDENT(CallEndStatus.VARIABLE_HAVOC_DEPENDENT)
    ;
}

/** XXX Should have a comment here ..
 *
 * [name] is the Sarif from a DisplayPath (aka storage path), or a left-hand-side of some assignment
 * (then it's the name of the TAC symbol, apparently :raised_eyebrow:)
 * */
data class DisplaySymbolWrapper(
    val name: Sarif,
    val value: TACValue?,
    val computationalType: ComputationalTypes,
    val formatterType: FormatterType<*>?,
    val range: Range.Range?
)

internal class SequenceGenerator(
    private val graph: TACCommandGraph,
    private val blocks: List<NBId>,
    private val model: CounterexampleModel
) {
    fun snippets(startPtr: CmdPointer = zeroPtr): Sequence<SnippetCmd> =
        graph
            .iterateFrom(startPtr, blocks)
            .takeWhile { !isFailedAssert(it) }
            .flatMap {
                it.asSnippetCmd()?.let { listOf(it) } ?: it.cmd.subExprs().mapNotNull { (it as? TACExpr.AnnotationExp<*>)?.annot?.v as? SnippetCmd }
            }

    private fun isFailedAssert(ltac: LTACCmd): Boolean {
        val (ptr, cmd) = ltac

        return when (cmd) {
            is TACCmd.Simple.AssertCmd -> cmd.hasFailedInModel(model, ptr).getOrThrow()
            else -> false
        }
    }
}

internal val zeroPtr = CmdPointer(StartBlock, 0)
