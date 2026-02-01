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

package tac.generation

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import tac.*
import utils.*
import vc.data.*
import vc.data.tacexprutil.*
import java.util.stream.Collectors

object Trap {
    fun trap(reason: String, meta: MetaMap = MetaMap()) =
        if (Config.TrapAsAssert.get()) {
            trapAsAssert(TACSymbol.False, reason, meta)
        } else {
            trapRevert(reason, meta)
        }

    fun assert(
        reason: String,
        meta: MetaMap = MetaMap(),
        cond: TACExprFact.() -> TACExpr
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val condSym = TACSymbol.Var("cond", Tag.Bool).toUnique("!")
        return mergeMany(
            ExprUnfolder.unfoldTo(TACExprFactUntyped(cond), condSym, meta).merge(condSym),
            if (Config.TrapAsAssert.get()) {
                trapAsAssert(condSym, reason, meta)
            } else {
                listOf(ConditionalTrapRevert(condSym, reason).toCmd(meta)).withDecls()
            }
        )
    }

    private fun trapAsAssert(cond: TACSymbol, reason: String, meta: MetaMap = MetaMap()) =
        listOf(
            TACCmd.Simple.AssertCmd(
                cond,
                reason,
                meta
            )
        ).withDecls()

    fun trapRevert(reason: String, meta: MetaMap = MetaMap()) =
        listOf(
            TACCmd.Simple.LabelCmd(reason),
            TACCmd.Simple.RevertCmd(
                base = TACKeyword.MEMORY.toVar(),
                o1 = TACSymbol.lift(0), // WASM has no equivalent of EVM's revert return data
                o2 = TACSymbol.lift(0), // Ditto
                revertType = TACRevertType.THROW,
                meta = meta
            )
        ).withDecls()
}


/**
    Reverts if [cond] is false, otherwise continues.

    Unlike [TACCmd.Simple.RevertCmd], this can be placed anywhere within a block; [ConditionalTrapRevert.materialize]
    will transform the program later to produce the necessary control flow.
  */
@KSerializable
data class ConditionalTrapRevert(
    val cond: TACSymbol,
    val reason: String,
) : TACSummary {
    init {
        check(!Config.TrapAsAssert.get()) {
            "ConditionalTrapRevert should not be used when TrapAsAssert is enabled"
        }
    }

    override val variables get() = setOfNotNull(cond as? TACSymbol.Var)
    override val annotationDesc get() = reason

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = ConditionalTrapRevert(
        cond = (cond as? TACSymbol.Var)?.let { f(it) } ?: cond,
        reason = reason,
    )

    fun toCmd(meta: MetaMap = MetaMap()) = TACCmd.Simple.SummaryCmd(this, meta)

    companion object {
        /**
            Rewrite ConditionalTrapRevert summaries into the appropriate TAC implementation.
        */
        fun materialize(prog: CoreTACProgram): CoreTACProgram {
            if (Config.TrapAsAssert.get()) {
                return prog
            }

            val ops = prog.parallelLtacStream().mapNotNull {
                ((it.cmd as? TACCmd.Simple.SummaryCmd)?.summ as? ConditionalTrapRevert)?.let { op -> it.ptr to op }
            }.collect(Collectors.toList())

            return prog.patching { patch ->
                for((ptr, op) in ops) {
                    /**
                        rewrite:
                            SummaryCmd(ConditionalTrapRevert(cond, reason, subject))
                            ...

                        to:
                            JumpiCmd(cond, #continuation, #revert)

                            #revert
                            LabelCmd(reason, subject)
                            RevertCmd(MEMORY, 0, 0, THROW)

                            #continuation
                            ...
                     */

                    val (revertCmds, revertVars) = Trap.trapRevert(op.reason)

                    val continuation = patch.splitBlockAfter(ptr)
                    val revert = patch.addBlock(
                        continuation, // so [revert] and [continuation] have similar block IDs.
                        revertCmds
                    )
                    patch.replaceCommand(
                        ptr,
                        listOf(
                            TACCmd.Simple.JumpiCmd(
                                cond = op.cond,
                                dst = continuation,
                                elseDst = revert
                            )
                        ),
                        treapSetOf(continuation, revert)
                    )
                    patch.addVarDecls(revertVars)
                }
            }
        }
    }
}
