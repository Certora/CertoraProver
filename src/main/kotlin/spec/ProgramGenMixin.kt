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

package spec

import analysis.CommandWithRequiredDecls
import config.Config
import spec.converters.repr.WithCVLProgram
import tac.Tag
import utils.toIntOrNull
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import datastructures.stdcollections.*
import spec.CVLCompiler.CallIdContext.Companion.toContext
import tac.CallId
import java.util.concurrent.atomic.AtomicInteger


/**
 * Common super class for [spec.converters.repr.CVLDataInput] and [spec.converters.repr.CVLDataOutput].
 * Contains many convenience functions, for generating unrolled loops, lifting [CommandWithRequiredDecls]
 * into programs, etc.
 */
interface ProgramGenMixin {
    val context: CVLCompiler.CallIdContext

    /**
     * If [phrase] is some action phrase (e.g., "move primitive", "write struct"),
     * add to this phrase some location information (like "from variable V" or "to buffer pointer X")
     * and return that.
     */
    fun toActionLabel(phrase: String) : String

    /**
     * Add a numerically scoped action label to [this], so that we have:
     * ```
     * k: toActionLabel(phrase) ...
     * <this>
     * ... done k
     * ```
     *
     * where `k` is a freshly chosen number.
     */
    fun <R: WithCVLProgram<R>> R.actionLabel(phrase: String) = this.label(toActionLabel(phrase))

    fun CommandWithRequiredDecls<TACCmd.Spec>.actionLabel(phrase: String) = this.label(toActionLabel(phrase))

    infix fun <R: WithCVLProgram<R>> R.withLabel(phrase: String) = this.label(phrase)

    companion object {
        private val labelCounter = AtomicInteger(0)

        private fun <R: WithCVLProgram<R>> CommandWithRequiredDecls<TACCmd.Spec>.merge(other: R) = other.mapCVLProgram {
            it.prependToBlock0(this)
        }

        private fun <R: WithCVLProgram<R>> R.merge(other: CVLTACProgram) = this.mapCVLProgram {
            mergeCodes(it, other)
        }


        fun CVLTACProgram.label(msgStart: String, msgEnd: String) = this.prependToBlock0(listOf(TACCmd.Simple.LabelCmd(msgStart))).appendToSinks(
            CommandWithRequiredDecls(listOf(TACCmd.Simple.LabelCmd(msgEnd)))
        )

        infix fun <R: WithCVLProgram<R>> CommandWithRequiredDecls<TACCmd.Spec>.andThen(other: R) = this.merge(other)

        infix fun <R: WithCVLProgram<R>> CVLTACProgram.andThen(other: R) = other.mapCVLProgram {
            mergeCodes(this, it)
        }

        infix fun CVLTACProgram.then(other: CVLTACProgram) = mergeCodes(this, other)

        infix fun <R: WithCVLProgram<R>> R.andThen(prog: CVLTACProgram) = this.merge(prog)

        fun <R: WithCVLProgram<R>> R.label(msgStart: String, msgEnd: String) = this.mapCVLProgram {
            it.label(msgStart, msgEnd)
        }

        fun <R: WithCVLProgram<R>> R.label(start: String): R {
            val id = labelCounter.getAndIncrement()
            return this.label("$id: $start...", "...done $id")
        }

        fun CommandWithRequiredDecls<TACCmd.Spec>.label(start: String, end: String): CommandWithRequiredDecls<TACCmd.Spec> {
            return this.copy(
                cmds = listOf(TACCmd.Simple.LabelCmd(start)) + this.cmds + TACCmd.Simple.LabelCmd(end)
            )
        }

        fun CommandWithRequiredDecls<TACCmd.Spec>.label(phrase: String): CommandWithRequiredDecls<TACCmd.Spec> {
            val id = labelCounter.getAndIncrement()
            return this.label("$id: $phrase...", "... done $id")

        }

        fun CallId.emptyProgram() = CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toProg("empty", this.toContext())
    }

    fun <T: TACCmd.Spec> CommandWithRequiredDecls<T>.toProg(label: String) = this.toProg(label, context)

    fun ExprUnfolder.Companion.UnfolderResult<*>.toProg(name: String) = CommandWithRequiredDecls(this.cmds, this.newVars).toProg(name, context)

    fun emptyProgram() = CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd)).toProg("empty", context)

    /**
     * Generate an unrolled loop over an object that has length [len]. The body of each iteration of the loop
     * is generated by [f]. The unrolling bound depends on the shape of [len]; if it is [TACSymbol.Const], then
     * we generate [len] (trivial) unrollings; otherwise the bound is [config.Config.LoopUnrollConstant]. [f] is not
     * guaranteed to be called in "iteration" order, but the unrolling ensures that the code for iteration 0 always executes
     * before iteration 1, etc.
     *
     * In pseudo-code, where `<...>` indicates parts of the code that is "filled" in later, and `bound` is the unrolling bound chosen,
     * this function generates:
     *
     * ```
     * if(0 < len) {
     *    <f(0)>
     *    if(1 < len) {
     *       <f(1)>
     *       ...
     *          if(bound -1 < len) {
     *            <f(bound - 1)>
     *          }
     *      }
     *   }
     * }
     * <suffix>
     * ```
     *
     * Note that there is **no** fallthrough case if the unrolling bound is insufficient. It is expected callers use [generateUnrollBound]
     * to ensure that the number of unrolling is sufficient.
     */
    fun generateUnrolledLoop(suffix: CVLTACProgram, len: TACSymbol, f: (Int) -> CVLTACProgram) : CVLTACProgram {
        val lenBound = (len as? TACSymbol.Const)?.value?.toIntOrNull() ?: Config.LoopUnrollConstant.get()
        return (0 until lenBound).reversed().fold(emptyProgram()) { acc, ind ->
            val withinBounds = TACKeyword.TMP(Tag.Bool, "!withinBounds")
            val prefix =
                CommandWithRequiredDecls(listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = withinBounds,
                    rhs = TACExpr.BinRel.Lt(ind.asTACExpr, len.asSym())
                )), setOf(withinBounds, len)).toProg("elem $ind", context)
            val elseBranch = emptyProgram()
            val thenBranch = f(ind)
            val withNext = mergeCodes(thenBranch, acc)
            mergeIfCodes(
                condCode = prefix,
                thenCode = withNext,
                elseCode = elseBranch,
                jumpiCmd = TACCmd.Simple.JumpiCmd(
                    cond = withinBounds,
                    dst = withNext.getStartingBlock(),
                    elseDst = elseBranch.getStartingBlock()
                )
            )
        }.let {
            mergeCodes(it, suffix)
        }
    }

    /**
     * Return code that **assumes** the length [len] is within the bound chosen by [generateUnrolledLoop].
     *
     */
    fun generateUnrollBound(len: TACSymbol) : CommandWithRequiredDecls<TACCmd.Spec> {
        return ExprUnfolder.unfoldPlusOneCmd(
            "lenComputation",
            if (len is TACSymbol.Const) {
                TACSymbol.True.asSym()
            } else {
                TACExpr.BinRel.Le(len.asSym(), Config.LoopUnrollConstant.get().asTACExpr)
            }
        ) {
            TACCmd.Simple.AssumeCmd(it.s, "UnrollBound")
        }
    }
}
