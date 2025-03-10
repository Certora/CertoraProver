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

package verifier.splits

import algorithms.getReachable1
import analysis.opt.intervals.IntervalsRewriter
import config.Config
import datastructures.add
import datastructures.buildMultiMap
import datastructures.stdcollections.*
import smt.CoverageInfoEnum
import utils.*
import vc.data.TACCmd
import vc.data.TACSymbol
import verifier.PatchingProgramWrapper

/**
 * A lazy sub-program of [origCode]. It's memory foot print is very small, and it can generate the actual program
 * on demand from [address].
 *
 * identity is according to [address].
 */
class LazySubProgram(
    private val splitTree: SplitTree,
    val address: SplitAddress = SplitAddress.Root,
) {
    private val origCode get() = splitTree.origCode
    private val g get() = splitTree.g
    private val reachability get() = splitTree.reachability
    private val root get() = splitTree.rootBlock

    val name =
        if (address.isRoot) {
            origCode.name
        } else {
            "${origCode.name}_$address"
        }

    override fun equals(other: Any?) = other is LazySubProgram && address == other.address
    override fun hashCode() = address.hashCode()
    override fun toString() = name

    /**
     * When we must pass through a block, then any assert before that block turns to an assume.
     * This holds the blocks where there are asserts remaining after this transformation.
     */
    val assertBlocks by lazy {
        val mustPass = address.mustPass()
        if (mustPass.isEmpty()) {
            splitTree.nonTrivialAssertsByBlock.keys
        } else {
            val lastMustPass = mustPass.maxWith { b1, b2 ->
                when {
                    b1 == b2 -> 0
                    b1 in reachability[b2].orEmpty() -> 1
                    b2 in reachability[b1].orEmpty() -> -1
                    else -> error("must pass vertices must have a total linear order between them")
                }
            }
            splitTree.nonTrivialAssertsByBlock.keys - (getReachable1(lastMustPass, g::pred) - lastMustPass)
        }
    }

    /**
     * If we run optimizations after splits, then this is not really the actual graph, it is before
     * the optimizations are run.
     */
    fun actualGraph() = cutDAG(
        root = root,
        origGraph = g.blockSucc,
        mustPass = address.mustPass(),
        dontPass = address.dontPass(),
        origSinks = assertBlocks
    )

    private var timesGenerated = 0

    /** Generates the actual program from the address. */
    fun generate() = PatchingProgramWrapper(origCode)
        .apply {
            val actualGraph = actualGraph()
            limitTACProgramTo(actualGraph)
            splitTree.nonTrivialAssertsByBlock.entries
                .filter { (b, _) -> b in actualGraph && b !in assertBlocks }
                .forEach { (_, ptrs) ->
                    for (ptr in ptrs) {
                        val cmd = g.toCommand(ptr) as TACCmd.Simple.AssertCmd
                        replace(
                            ptr,
                            listOf(
                                cmd.copy(o = TACSymbol.True),
                                TACCmd.Simple.AssumeCmd(cmd.o)
                            )
                        )
                    }
                }
            address.assumptions()
                .filter { it.block in actualGraph }
                .groupBy { it.block }
                .forEachEntry { (block, assumptions) ->
                    val newCmdsByAssertNum = buildMultiMap<Int, TACCmd.Simple> {
                        assumptions.forEach {
                            add(it.afterAssertNum, TACCmd.Simple.AssumeExpCmd(it.e))
                        }
                    }
                    // can't use `splitTree.nonTrivialAssertsByBlock` because we have to count trivial asserts as well.
                    val cmds = g.elab(block).commands
                    var assertCount = 0
                    cmds.filter { it.cmd is TACCmd.Simple.AssertCmd }
                        .forEach { (ptr, _) ->
                            newCmdsByAssertNum[assertCount++]?.let {
                                addBefore(ptr, it.toList())
                            }
                        }
                    newCmdsByAssertNum[assertCount]?.let { newCmds ->
                        val (ptr, cmd) = cmds.last()
                        if (cmd is TACCmd.Simple.JumpiCmd || cmd is TACCmd.Simple.JumpCmd) {
                            addBefore(ptr, newCmds.toList())
                        } else {
                            addAfter(ptr, newCmds.toList())
                        }
                    }
                }
        }
        .toCode(name, handleLeinoVars = true)
        .letIf(Config.CoverageInfoMode.get() != CoverageInfoEnum.ADVANCED) {
            IntervalsRewriter.rewrite(
                code = it,
                repeat = maxOf(
                    Config.OptimizeAfterSplits.get(),
                    ite(Config.UnderApproxStartDepth.get() >= 0, 1, 0)
                ),
                handleLeinoVars = true,
            )
        }
        .also { timesGenerated++ }
}
