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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier.equivalence.tracing

import datastructures.stdcollections.*
import analysis.CommandWithRequiredDecls
import utils.*
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.`=`

/**
 * Tracks whether a buffer is itself a "transparent copy" of another buffer.
 * In particular, if a buffer B is "just" a copy of another buffer Z, if we copy B
 * into another buffer O, instead of including "CopyFrom(Hash(B))"
 * (where `Hash(B)` is the hashed history of `B`) we simply include `CopyFrom(Z)`, where
 * `CopyFrom(Z)` is payload that defines the copy from `Z` into `B`. In other words, from the
 * perspective of `O`, `B` never exists, instead, it looks instead like `Z` was directly copied into `O`.
 *
 * Recall that `CopyFrom(Z)` is actually defined with 2 (or three) values: a represenative var,
 * a sort, and (optionally) an extra context. Thus, when including `CopyFrom(Z)` into O's history,
 * we need to remember these three values.
 *
 * The instrumentation variables are as follows:
 * [statusFlagVar] Indicates whether the buffer with this instrumentation is a transparent copy. If 0, then
 * indicates this buffer is not a transparent copy. 1 indicates a transparent copy, without [extraCtxtVar],
 * and 2 represents with extra context.
 * [reprVar] The representative value that defines part of the copy from Z
 * [sortVar] The sort value that defines part of the copy from Z
 * [extraCtxtVar] The (optional) additional context value that defines part of the copy from `Z`.
 *
 * If [statusFlagVar] is 0, the values in [reprVar], [sortVar], [extraCtxtVar] are "junk" and should be ignored.
 * It is an invariant that when [statusFlagVar] is set to non-zero, these variables are updated accordingly.
 * Similarly, the values of these variables are only ever read if [statusFlagVar] is non-zero.
 */
internal class TransparentCopyTracking(
    override val statusFlagVar: TACSymbol.Var,
    override val reprVar: TACSymbol.Var,
    override val sortVar: TACSymbol.Var,
    override val extraCtxtVar: TACSymbol.Var
) : InstrumentationMixin, TransparentCopyData {
    companion object {
        const val NO_COPY_FLAG = 0
        const val COPY_NO_CTXT = 1
        const val COPY_WITH_CTXT = 2
    }
    override fun atPrecedingUpdate(
        s: IBufferUpdate,
        overlapSym: TACSymbol.Var,
        writeEndPoint: TACSymbol.Var,
        baseInstrumentation: ILongReadInstrumentation
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        /**
         * The fallback behavior is to clear the transparent copy status of this buffer if any part of
         * the write [s] overlaps with the target buffer.
         */
        val fallback by lazy {
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = statusFlagVar,
                    rhs = TXF {
                        ite(
                            overlapSym,
                            NO_COPY_FLAG,
                            statusFlagVar
                        )
                    }
                )
            ))
        }
        when(val src = s.updateSource) {
            /**
             * are we copying from another buffer that is itself a transparent copy? If so, copy its status.
             */
            is IWriteSource.LongMemCopy -> {
                val copyData = src.sourceBuffer.transparentCopyData ?: return fallback
                val isCopyOfCopy = TXF {
                    overlapSym and (s.updateLoc eq baseInstrumentation.baseProphecy) and (s.len eq baseInstrumentation.lengthProphecy) and
                        (copyData.statusFlagVar gt NO_COPY_FLAG)
                }

                /**
                 * NB: we take care to zero out this buffer's flag on an overlap (but not a `copyOfCopy`).
                 * We DO NOT bother zeroing out the other variables because per our invariant, once we set
                 * the status flag to 0, their value becomes irrelevant.
                 */
                val update = statusFlagVar `=` {
                    ite(isCopyOfCopy, copyData.statusFlagVar, ite(overlapSym, NO_COPY_FLAG, statusFlagVar))
                } andThen (reprVar `=` {
                    ite(isCopyOfCopy, copyData.reprVar, reprVar)
                }) andThen (
                    sortVar `=` {
                        ite(isCopyOfCopy, copyData.sortVar, sortVar)
                    }
                ) andThen (
                    extraCtxtVar `=` {
                        ite(isCopyOfCopy, copyData.extraCtxtVar, extraCtxtVar)
                    }
                )
                return update
            }
            is IWriteSource.EnvCopy -> {
                /**
                 * Are we defining this buffer completely by copying from the environment? If so, set the status flag
                 * and copy the relevant variables.
                 *
                 * This is something of an abstraction leakage, as we copy the [IWriteSource.EnvCopy.sourceLoc]
                 * as the representative value. This turns out to be exactly what the representative value IS, but
                 * no promise is made in the API to this effect.
                 */
                val isCompleteCopy = TXF {
                    overlapSym and (s.updateLoc eq baseInstrumentation.baseProphecy) and (s.len eq baseInstrumentation.lengthProphecy)
                }
                val extra = src.extraContext
                val statusFlag = if(extra != null) {
                    COPY_WITH_CTXT
                } else {
                    COPY_NO_CTXT
                }

                /**
                 * Again, a similar story applies here around zeroing out the status flag if we overlap but are NOT
                 * a complete copy.
                 */
                val update = statusFlagVar `=` {
                    ite(isCompleteCopy, statusFlag, ite(overlapSym, 0, statusFlagVar))
                } andThen (reprVar `=` {
                    ite(isCompleteCopy, src.sourceLoc, reprVar)
                }) andThen (sortVar `=` {
                    ite(isCompleteCopy, src.sortRepr, sortVar)
                })
                return update.letIf(extra != null) { cmds ->
                    cmds andThen (extraCtxtVar `=` {
                        ite(isCompleteCopy, extra!!, extraCtxtVar)
                    })
                }
            }
            is IWriteSource.ByteStore,
            is IWriteSource.Other -> {
                return fallback
            }
        }
    }

    override fun atLongRead(s: ILongRead): CommandWithRequiredDecls<TACCmd.Simple> {
        return CommandWithRequiredDecls()
    }

    override val havocInitVars: List<TACSymbol.Var>
        get() = listOf(reprVar, sortVar, extraCtxtVar)
    override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
        get() = listOf(statusFlagVar to TACExpr.zeroExpr)
}
