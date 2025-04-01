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

package testing

import analysis.icfg.CVLLabelStack
import config.Config
import config.ReportTypes
import datastructures.stdcollections.listOf
import log.*
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import report.TreeViewLocation
import tac.NBId
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACProgram
import vc.data.destructiveOptimizations

private val logger = Logger(LoggerTypes.COMMON)

/**
 * Helpers for narrowing down bugs in the tac pipeline. (Hopefully helps answering questions like "Which transformation
 * 'optimized' _that_ away, I still need it?!", and similar queries.)
 *
 * Provides methods that are called at various stages of the pipeline and that can be used to check invariants (one- or
 * two-state style).
 *
 * The actual invariants can be temporarily hardcoded in [TacPipelineDebuggerJustForTemporaryDebugging] by the person who is debugging or
 * added as permanent checks into [TacPipelineDebuggers.debuggers].
 *
 * The places where these are checked are currently:
 *  - twoState: code transformations via [CodeTransFormer] (and few select transformations where that class is not used)
 *  - oneState:
 *    - before and after twoState
 *    - our standard tac-dump locations, like preOptimized, preSolver, etc.
 */
class TacPipelineDebuggerJustForTemporaryDebugging : TacPipelineDebugger {
    override fun <T : TACCmd> oneStateInvariant(prog: Map<NBId, List<T>>, loc: () -> InvariantLocation) {
        /* insert your invariant check or print debugging code */
        unused(loc)
        unused(prog)
    }

    override fun <T1 : TACCmd, T2 : TACCmd> twoStateInvariant(
        old: TACProgram<T1>,
        new: TACProgram<T2>,
        loc: () -> InvariantLocation
    ) {
        super.twoStateInvariant(old, new, loc)
        /* insert your invariant check or print debugging code */
        unused(loc)
        unused(old.code)
        unused(new.code)
    }

}

interface TacPipelineDebugger {
    fun <T : TACCmd> oneStateInvariant(prog: TACProgram<T>, loc: ReportTypes) {
        oneStateInvariant(prog) { InvariantLocation.ReportTypes(loc) }
    }
    fun <T : TACCmd> oneStateInvariant(prog: TACProgram<T>, loc: () -> InvariantLocation) {
        oneStateInvariant(prog.code, loc)
    }
    fun <T : TACCmd> oneStateInvariant(prog: Map<NBId, List<T>>, loc: () -> InvariantLocation)
    fun <T1 : TACCmd, T2 : TACCmd> twoStateInvariant(old: TACProgram<T1>, new: TACProgram<T2>, loc: ReportTypes) {
        twoStateInvariant(old, new) { InvariantLocation.ReportTypes(loc) }
    }
    fun <T1 : TACCmd, T2 : TACCmd> twoStateInvariant(
        old: TACProgram<T1>,
        new: TACProgram<T2>,
        loc: () -> InvariantLocation
    ) {
        /* Check the one state invariants also for the hooks that check the two state ones. */
        oneStateInvariant(old) { InvariantLocation.Pre(loc()) }
        oneStateInvariant(new) { InvariantLocation.Post(loc()) }
    }
}

class TacPipelineCIInvariants : TacPipelineDebugger {
    override fun <T : TACCmd> oneStateInvariant(prog: TACProgram<T>, loc: () -> InvariantLocation) {
        super.oneStateInvariant(prog, loc)
        fun TACProgram<T>.isInDesctructiveMode(): Boolean{
            return (this as? CoreTACProgram)?.destructiveOptimizations ?: false
        }

        if (Config.TestMode.get() && !prog.isInDesctructiveMode()) {
            val graph = prog.analysisCache?.graph
            if (graph == null) {
                logger.warn { "Could not get CommandGraph at ${loc()}" }
                return
            }
            logger.info { "Checking CVLLabelStack for ${prog.name} at ${loc()}..." }
            try {
                CVLLabelStack(graph, check = true)
            } catch (e: IllegalStateException) {
                val msg = "CVLLabelStack check for ${prog.name} failed at ${loc()} with ${e.message}"
                CVTAlertReporter.reportAlert(CVTAlertType.CVL, CVTAlertSeverity.ERROR, TreeViewLocation.Empty, msg, null)
                check(false)
            }
        }
    }

    override fun <T : TACCmd> oneStateInvariant(prog: Map<NBId, List<T>>, loc: () -> InvariantLocation) {}
}

object TacPipelineDebuggers {
    private val debuggers: List<TacPipelineDebugger> = listOf(TacPipelineDebuggerJustForTemporaryDebugging(), TacPipelineCIInvariants())

    fun <T : TACCmd> oneStateInvariant(prog: TACProgram<T>, loc: ReportTypes) {
        for (debugger in debuggers) {
            debugger.oneStateInvariant(prog, loc)
        }
    }
    fun <T1 : TACCmd, T2 : TACCmd> twoStateInvariant(old: TACProgram<T1>, new: TACProgram<T2>, loc: ReportTypes) {
        for (debugger in debuggers) {
            debugger.twoStateInvariant(old, new, loc)
        }
    }
}

interface InvariantLocation: CategoryName {
    @JvmInline
    value class ReportTypes(val reportTypes: config.ReportTypes): InvariantLocation {
        override val name: String
            get() = reportTypes.name
    }

    data class Pre(val twoStateLoc: InvariantLocation): InvariantLocation {
        override val name: String
            get() = "Pre-${twoStateLoc.name}"
    }

    data class Post(val twoStateLoc: InvariantLocation): InvariantLocation {
        override val name: String
            get() = "Post-${twoStateLoc.name}"
    }
}

