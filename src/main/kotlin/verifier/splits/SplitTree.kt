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

import analysis.CmdPointer
import cli.SplitOrderEnum
import config.Config
import datastructures.stdcollections.*
import solver.SolverResult
import tac.NBId
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACSymbol
import verifier.autoconfig.AutoConfigManager
import verifier.splits.SplitAddress.Block
import verifier.splits.SplitTree.Node
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


/**
 * A tree of [Node], each representing a subprogram of [origCode], and each is a subprogram of its parent
 * according to the splitting strategy, given by [splitters].
 */
class SplitTree(val origCode: CoreTACProgram, private val splittingDepth: Int,
                private val splitters: (Node) -> Splitter = { PivotSplitter(it) }) {
    val g = origCode.analysisCache.graph
    val reachability = origCode.analysisCache.reachability
    val rootBlock = g.rootBlocks.single().id

    val nonTrivialAssertsByBlock: Map<NBId, List<CmdPointer>> = buildMap {
        g.blockIds.forEach { b ->
            g.elab(b).commands
                .filter { it.cmd.isNonTrivialAssert }
                .map { it.ptr }
                .takeIf { it.isNotEmpty() }
                ?.let { put(b, it) }
        }
    }

    private val nodeMap = ConcurrentHashMap<SplitAddress, Node>()
    fun getNode(address : SplitAddress) = nodeMap[address]

    val rootNode = Node(LazySubProgram(this), EmptySideScore)

    data class RunInfo(val result: SolverResult, val runningTime: Duration, val timeout: Duration)


    /**
     * [Node]s are considered equal if their [address] (given in [lazySub]) is equal.
     */
    open inner class Node(val lazySub: LazySubProgram, val sideScore: SideScore<*>) {
        val address get() = lazySub.address
        val name get() = lazySub.name

        init {
            check(nodeMap.put(address, this) == null) {
                "$name is already in the split-tree"
            }
        }

        private val myTree get() = this@SplitTree

        override fun equals(other: Any?) = other is Node && myTree === other.myTree && this.address == other.address
        override fun hashCode() = address.hashCode()
        override fun toString() = name

        // settable properties that are updated through the run
        private val runInfo = AtomicReference<RunInfo>()
        fun setRunInfo(result: SolverResult, runningTime: Duration, timeout: Duration) {
            val oldInfo = runInfo.getAndSet(RunInfo(result, runningTime, timeout))
            require(oldInfo == null)
        }

        private val children = AtomicReference<List<Node>>()

        val parentOrNull get() = runIf(!isRoot) { getNode(address.parent!!) }
        val parent get() = parentOrNull ?: error("Root split doesn't have a parent")
        val isRoot get() = address.isRoot

        val depth get() = address.depth
        val weight : Double by lazy {
            parentOrNull
                ?.let { it.weight / it.children.get()!!.size }
                ?: 1.0
        }

        val probablySplittable = depth < splittingDepth

        private var _splittable: Boolean? = null

        /** don't query this before [generate] is called */
        val splittable get() = probablySplittable &&
            _splittable ?: error("Can't query splittable before it's been set")

        /** This is expensive if called before [generate], better use it only in tests. */
        fun getSplittableAnyway(splitConfigManager: AutoConfigManager) = _splittable ?: run {
                generate(splitConfigManager)
                _splittable!!
            }

        val wasEasilySolved
            get() = runInfo.get()?.let { (result, runningTime, _) ->
                result == SolverResult.UNSAT && runningTime <= Config.TinySplitTimeout.get().seconds
            } == true

        private val splitter by lazy {
            splitters(this)
        }

        /** Also check sets the [splittable] value */
        fun generate(splitConfigManager: AutoConfigManager) = lazySub.generate().also {
            if (probablySplittable && _splittable == null) {
                _splittable = splitter.splittable(it)
            }
            splitConfigManager.registerSplit(address, name, it)
        }

        fun siblings() = parent.children.get()!!.filter { it != this }

        fun setChildren(addressAndSideScoreList : List<Pair<SplitAddress, SideScore<*>>>) =
            addressAndSideScoreList.map { (childAddress, childScore) ->
                Node(LazySubProgram(this@SplitTree, childAddress), childScore)
            }.also(children::set)

        /**
         * This takes the [actualTAC] as a parameter, although it can be generated by calling [generate].
         * This is so that the [Node] avoids saving the generated TAC.
         */
        fun split(actualTAC: CoreTACProgram): List<Node> =
            splitter.split(actualTAC)

        val nodeSequence : Sequence<Node> get() =
            sequenceOf(this) + children.get().orEmpty().asSequence().flatMap { it.nodeSequence }
    }

    /**
     * Extends [Node] with some arbitrary additional information [info].
     */
    inner class NodeWithInfo<T>(lazySub: LazySubProgram, sideScore: SideScore<*>, val info: T)
        : Node(lazySub, sideScore)

    val nodeSequence get() = rootNode.nodeSequence

    companion object {
        val TACCmd.Simple.isNonTrivialAssert get() =
            this is TACCmd.Simple.AssertCmd && o != TACSymbol.True
    }
}


/**
 * This eventually decides which split will be attempted first, via a priority queue.
 *
 * Importantly! this uses only the immutable information of [SplitTree.Node].
 * If we ever change this, then when updating the info, the split should be removed and reinserted into the queue
 * (in such a case, use a `TreeSet` and not a `PriorityQueue`).
 */
val splitComparator = listOf<Comparator<SplitTree.Node>>(
    compareBy {
        when (Config.SplitOrder.get()) {
            // prefer deeper splits
            SplitOrderEnum.DFS -> -it.depth
            // prefer shallower splits
            SplitOrderEnum.BFS -> it.depth
        }
    },
    Comparator { o1, o2 ->
        // siblings are considered equal here, and handled in the next comparator
        SplitAddress.compareRevLexical(o1.address.parent!!, o2.address.parent!!)
    },
    Comparator { n1, n2 ->
        check(n1 in n2.siblings()) {
            "${n1.address} and ${n2.address} are supposed to be siblings"
        }
        if(n1.address is Block) {
            -n1.sideScore.compareTo(n2.sideScore)
        } else {
            0 // we consider others as all equal.
        }
    },
).reduce { comp, c -> comp.thenComparing(c) }


