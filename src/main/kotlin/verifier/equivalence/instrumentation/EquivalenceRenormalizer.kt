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

package verifier.equivalence.instrumentation

import datastructures.stdcollections.*
import analysis.CommandWithRequiredDecls
import analysis.snarrowOrNull
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten

/**
 * Undo some of the transformations done when we allow inlining delegates.
 *
 * In particular, we:
 * 1. Turn [CallSummary] back into their original [vc.data.TACCmd.Simple.CallCore] cmds, and
 * 2. Revert the scalarization of tacMX
 */
object EquivalenceRenormalizer {
    fun renormalize(c: CoreTACProgram) : CoreTACProgram {
        return unsummarizeCalls(unscalarizeMemory(c))
    }

    /**
     * Extract the [CallSummary.origCallcore] and replace the [CallSummary] with that.
     */
    private fun unsummarizeCalls(withCallSumm: CoreTACProgram): CoreTACProgram {
        return withCallSumm.parallelLtacStream().mapNotNull { lc ->
            lc `to?` lc.snarrowOrNull<CallSummary>()?.takeIf {
                // this is nullable????
                it.origCallcore != null
            }
        }.map { (where, summ) ->
            { p: SimplePatchingProgram ->
                p.replaceCommand(where.ptr, listOf(summ.origCallcore!!))
            }
        }.patchForEach(withCallSumm) { thunk ->
            thunk(this)
        }
    }

    /**
     * Turn a read of tacMX into `x = tacM[X]` and ditto for writes.
     */
    private fun unscalarizeMemory(c: CoreTACProgram): CoreTACProgram {
        return c.parallelLtacStream().mapNotNull { lc ->
            val scalarized = lc.cmd.getFreeVarsOfRhs().filterToSet {
                it.meta[TACMeta.RESERVED_MEMORY_SLOT] != null
            }
            val outputInd = lc.cmd.getLhs()?.meta?.get(TACMeta.RESERVED_MEMORY_SLOT)
            if(scalarized.isEmpty() && outputInd == null) {
                return@mapNotNull null
            }
            val toRemap = scalarized.map {
                val ind = it.meta[TACMeta.RESERVED_MEMORY_SLOT]!!
                val output = TACKeyword.TMP(Tag.Bit256, "!Read$ind")
                Triple(it, ind, output)
            }
            val mapper = toRemap.associate { (orig, _, rewrite) ->
                orig to rewrite
            }
            val readPrefix = toRemap.map { (_, ind, out) ->
                CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = out,
                        base = TACKeyword.MEMORY.toVar(),
                        loc = ind.asTACSymbol()
                    )
                ), TACKeyword.MEMORY.toVar(), out)
            }.flatten()

            val newOut = outputInd?.let { ind ->
                TACKeyword.TMP(Tag.Bit256, "!toWrite$ind")
            }
            val remapped = object : DefaultTACCmdMapper() {
                override fun mapLhs(t: TACSymbol.Var): TACSymbol.Var {
                    return newOut ?: t
                }

                override fun mapVar(t: TACSymbol.Var): TACSymbol.Var {
                    return mapper[t] ?: t
                }
            }.map(lc.cmd)
            val suffix = if(newOut != null) {
                CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        base = TACKeyword.MEMORY.toVar(),
                        loc = outputInd.asTACSymbol(),
                        value = newOut
                    )
                ), TACKeyword.MEMORY.toVar(), newOut)
            } else {
                CommandWithRequiredDecls()
            }
            val newCommands = readPrefix andThen remapped andThen suffix
            { p: SimplePatchingProgram ->
                p.replaceCommand(lc.ptr, newCommands)
            }
        }.patchForEach(c) { thunk ->
            thunk(this)
        }
    }
}
