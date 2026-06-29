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

package analysis.alloc

import analysis.PatternDSL
import analysis.PatternMatcher
import analysis.narrow
import com.certora.collect.*
import log.Logger
import log.LoggerTypes
import utils.mapNotNull
import utils.mapToSet
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACSymbol
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.NORMALIZER)

/**
 * I don't want to talk about it
 *
 * SIIIIGH okay when unpacking a storage string into a memory buffer for packed encoding, the solidity
 * compiler generates:
 *
 * x = a & 1
 * if(x == 0) {
 *    // ...
 * } else if(x == 1) {
 *    // ...
 *  } else {
 *    // THIS IS CLEARLY UNREACHABLE
 *  }
 *
 *  Unfortunately, within the "this is clearly unreachable" is code that completely destroys the alloc analysis.
 *
 *  So we detect this dumbass pattern and just sidestep the issue.
 */
object UnreachableUnpackingCodeFinder {
    private val secondCondition = PatternDSL.build {
        ((Var and 0x1()).commute.locFirst `==` 0x1()).commute.first
    }
    private val firstCondition = PatternDSL.build {
        ((Var and 0x1()).commute.locFirst `==` 0x0()).commute.first
    }

    fun removeUnreachable(prog: CoreTACProgram) : CoreTACProgram {
        val g = prog.analysisCache.graph
        val firstPatt = PatternMatcher.compilePattern(g, firstCondition)
        val secondPatt = PatternMatcher.compilePattern(g, secondCondition)
        val toRet = g.blocks.parallelStream().filter {
            it.commands.last().cmd.let { it is TACCmd.Simple.JumpiCmd && it.cond is TACSymbol.Var }
        }.mapNotNull { block ->
            block.commands.last().narrow<TACCmd.Simple.JumpiCmd>().takeIf { jump ->
                g.pred(jump.cmd.elseDst).singleOrNull() == block.id
            }?.takeIf { jump ->
                (jump.cmd.cond as TACSymbol.Var).let {
                    secondPatt.query(it, jump.wrapped)
                }.toNullableResult()?.let {
                    val lastPred = g.elab(
                        g.pred(jump.ptr.block).singleOrNull()
                            ?: run {
                                logger.info {
                                    "expected to have single predecessor for the block of $jump, got ${
                                        g.pred(jump.ptr.block)
                                    }"
                                }
                                return@takeIf false
                            }
                    ).commands.last()
                    lastPred.cmd is TACCmd.Simple.JumpiCmd && lastPred.cmd.cond is TACSymbol.Var && firstPatt.query(lastPred.cmd.cond, lastPred).toNullableResult() == it
                } == true
            }
        }.collect(Collectors.toSet())
        if (toRet.isEmpty()) {
            return prog
        }
        val patching = prog.toPatchingProgram()
        for(jump in toRet) {
            patching.replaceCommand(jump.ptr, listOf(TACCmd.Simple.JumpCmd(dst = jump.cmd.dst, meta = jump.cmd.meta)), treapSetOf(jump.cmd.dst))
        }
        // Drop the whole unreachable subgraph, not just the [elseDst] heads: [removeBlock] is
        // non-transitive, so removing only a head orphans blocks reachable solely through it.
        patching.removeSubgraph(toRet.mapToSet { it.cmd.elseDst })
        val (code, graph) = patching.toCode(TACCmd.Simple.NopCmd)
        return prog.copy(
                code = code,
                blockgraph = graph
        )
    }
}
