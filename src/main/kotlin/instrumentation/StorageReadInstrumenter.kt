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

package instrumentation

import datastructures.stdcollections.*
import tac.MetaMap
import tac.Tag
import vc.data.*

/**
 * Instrument programs to assume that storage locations that are dead must be zero.
 *
 * We effect this by associating with each map typed storage variable with a
 * read tracking map variable. Let the storage variable be called m, and denote
 * the read tracker as m$r. We have the following invariant:
 * for any `k`, if `m$r[k] != 0` then `m[k]` has been accessed (read or written), and vice versa.
 * In other words, we dynamically track which keys in `m` are live by setting the
 * corresponding location in `m$r` to 1. At rule initialization, we set `m$r` to be all zeroes,
 * and `m$r` becomes part of the storage state which is backed-up/restored with reverts.
 * A location is thus considered dead only if it was never accessed since rule initialization
 * (or since the last restore of the storage state).
 *
 * The association of `m` with `m$r` is done in the [readTracking] field:
 * the domain are the storage variables (`m`), and the codomain, if non-null is the corresponding read tracking
 * variable `m$r`.
 *
 * The instrumentation works as follows:
 * For any read of `m[k]` with a corresponding `m$r` we add `m$r[k] = 1` immediately following the read, indicating
 * that the word load is live.
 *
 * At a store to `m[k]` with a corresponding `m$r` we read the previous value of `m[k]` and the value of `m$r[k]`. We
 * then assume `m$r[k] != 0 || m[k] == 0` aka `m$r[k] => m[k] == 0`, that is, if `m[k]` was dead (as determined by our
 * instrumentation) then the value was 0 all along. Immediately after the store we add `m$r[k] = 1`, marking the
 * written location live: otherwise a later store to the same location would find the (non-zero) written value
 * with the tracker still unset, and the assumption above would be trivially false, pruning a feasible path.
 *
 * The commands here are annotated with the [TACMeta.STORAGE_READ_TRACKER] meta to avoid triggering hooks.
 */
class StorageReadInstrumenter(private val readTracking: Map<TACSymbol.Var, TACSymbol.Var?>) {
    fun instrument(c: CoreTACProgram): CoreTACProgram {
        val patcher = ConcurrentPatchingProgram(c)
        c.parallelLtacStream().filter {
            /*
              that is, we have `m$r`
             */
            it.cmd is TACCmd.Simple.StorageAccessCmd && readTracking[it.cmd.base] != null
        }.forEach {
            check(it.cmd is TACCmd.Simple.StorageAccessCmd)
            // safety established in filter condition
            val tracker = readTracking[it.cmd.base]!!
            when(it.cmd) {
                /*
                  m$r[k] = 1
                 */
                is TACCmd.Simple.AssigningCmd.WordLoad -> {
                    patcher.replace(it.ptr, listOf(
                        TACCmd.Simple.WordStore(
                            base = tracker,
                            loc = it.cmd.loc,
                            value = 1.asTACSymbol(),
                            meta = MetaMap(TACMeta.STORAGE_READ_TRACKER)
                        ),
                        it.cmd
                    ))
                    patcher.addVars(tracker)
                }
                is TACCmd.Simple.WordStore -> {
                    val prev = TACKeyword.TMP(Tag.Bit256, "!prevValue").toUnique("!")
                    val hasRead = TACKeyword.TMP(Tag.Bit256, "!readFlag").toUnique("!")
                    val assume = TACKeyword.TMP(Tag.Bool, "!assume").toUnique("!")
                    patcher.replace(it.ptr, listOf(
                        TACCmd.Simple.AssigningCmd.WordLoad(
                            lhs = prev,
                            base = it.cmd.base,
                            loc = it.cmd.loc,
                            meta = MetaMap(TACMeta.STORAGE_READ_TRACKER)
                        ),
                        TACCmd.Simple.AssigningCmd.WordLoad(
                            lhs = hasRead,
                            base = tracker,
                            loc = it.cmd.loc,
                            meta = MetaMap(TACMeta.STORAGE_READ_TRACKER)
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = assume,
                            rhs = TACExpr.BinBoolOp.LOr(
                                TACExpr.BinRel.Eq(
                                    1.asTACExpr,
                                    hasRead.asSym()
                                ),
                                TACExpr.BinRel.Eq(
                                    prev.asSym(),
                                    0.asTACExpr
                                )
                            )
                        ),
                        TACCmd.Simple.AssumeCmd(assume, "StorageReadInstrumenter"),
                        it.cmd,
                        /*
                          m$r[k] = 1: a written location is live, so the assumption above must not fire for any
                          later store to `k`.
                         */
                        TACCmd.Simple.WordStore(
                            base = tracker,
                            loc = it.cmd.loc,
                            value = 1.asTACSymbol(),
                            meta = MetaMap(TACMeta.STORAGE_READ_TRACKER)
                        )
                    ))
                    patcher.addVars(prev, hasRead, assume, tracker)
                }
            }
        }
        return patcher.toCode()
    }

}
