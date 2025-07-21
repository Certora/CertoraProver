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

package analysis.dataflow

import algorithms.getReachable
import analysis.*
import com.certora.collect.*
import datastructures.ArrayHashMap
import datastructures.stdcollections.*
import tac.NBId
import utils.*
import vc.data.*
import java.util.concurrent.ConcurrentHashMap

/**
 * GlobalValueNumbering is a (forward) dataflow analysis that identifies equivalent
 * computations in a TACCommandGraph.
 *
 * This analysis is used to find all the program variables Vs at
 * program point J whose values MUST be equal to the value of some program variable X at location I.
 *
 * The implementation was initially based on
 * "A Polynomial-Time Algorithm for Global Value Numbering" by Gulwani and Necula (SAS04),
 * (https://people.eecs.berkeley.edu/~necula/Papers/gvndet_sas04.pdf) with some modifications.
 * At the moment, we don't bother tracking complex equivalences such as X + Y, so the main
 * influence of this paper is just naming.
 *
 * The [followIdentities] "unwraps" the usually opaque [vc.data.TACBuiltInFunction.OpaqueIdentity] function
 * and builds equivalences between the rhs and lhs symbols. By default, this is false, and should almost certainly
 * be kept that way unless you're very certain this is what you want.
 *
 * [groupByConst] will include variables in the same equivalenc class if they share the same constant value. This is *usually*
 * what you want, unless you only want equvialent variables that are created through explicit assignments
 */
class GlobalValueNumbering(graph: TACCommandGraph, val followIdentities: Boolean = false, val groupByConst: Boolean = true) :
        TACCommandDataflowAnalysis<StrongEquivalenceDAG>(
                graph = graph,
                lattice = JoinLattice.ofJoin { p1, p2 -> p1.join(p2) },
                bottom = MutableStrongEquivalenceDAG(),
                dir = Direction.FORWARD
        ),
        IGlobalValueNumbering {

    companion object : AnalysisCache.Key<GlobalValueNumbering> {
        override fun createCached(graph: TACCommandGraph) = GlobalValueNumbering(graph)
    }

    // Maps b |-> { v | b is in one of v's def's dominance frontier }
    private val phis: Map<NBId, TreapSet<TACSymbol.Var>>

    protected override val finalizer = object : TACCommandDataflowAnalysis<StrongEquivalenceDAG>.Finalizer() {
        override fun finalizeState(state: StrongEquivalenceDAG) = state.finalize()
    }

    init {
        phis = computeJoins()
        runAnalysis()
    }

    private fun computeJoins(): Map<NBId, TreapSet<TACSymbol.Var>> {
        val vars = mutableMapOf<TACSymbol.Var, TreapSet<NBId>>()

        // Collect variables and their defining blocks
        @Suppress("IgnoredReturnValue")
        graph.commands.forEach {
            val c = it.cmd
            val where = it.ptr.block
            when  {
                c is TACCmd.Simple.AssigningCmd ->
                    vars.merge(c.lhs, treapSetOf(where), TreapSet<NBId>::plus)

                c is TACCmd.Simple.SummaryCmd && c.summ is AssigningSummary -> {
                    c.summ.mayWriteVars.forEach { x ->
                        vars.merge(x, treapSetOf(where), TreapSet<NBId>::plus)
                    }
                    c.summ.mustWriteVars.forEach { x ->
                        vars.merge(x, treapSetOf(where), TreapSet<NBId>::plus)
                    }
                }
            }
        }

        val selfDom = graph.cache.domination.calcFrontiers(true)
        val phis = ConcurrentHashMap<NBId, TreapSet<TACSymbol.Var>>()

        // Add a "phi node" at the domination frontier of each def for v.
        // This simulates DSA, where at each domination frontier we must have a new version of the variable,
        // and should then proceed to process the new variables domination frontier (hence `getReachable`).
        vars.entries.forEach { (v, defs) ->
            getReachable(defs.flatMap { selfDom[it].orEmpty() }, selfDom::get)
                .forEach {
                    phis.merge(it, treapSetOf(v), TreapSet<TACSymbol.Var>::plus)
                }
        }

        return phis
    }

    /**
     * Return the set of variables whose value at [target] is equal
     * to the value [source].second has at [source].first
     */
    override fun findCopiesAt(target: CmdPointer, source: Pair<CmdPointer, TACSymbol.Var>): Set<TACSymbol.Var> =
            cmdIn[target]?.let { sedHere ->
                cmdIn[source.first]?.let { sedWithXValue ->
                    sedWithXValue.getNode(source.second)?.vars?.versioned()?.let { versioned ->
                        sedHere.classes.firstOrNull {
                            versioned.containsAny(it.vars.versioned())
                        }?.vars?.programVariables()?.toSet()
                    }.orEmpty()
                }
            }.orEmpty()

    /**
     * Return the set of variables whose values are equal to [f] before
     * executing the command at [cmd]
     */
    override fun equivBefore(cmd: CmdPointer, f: TACSymbol.Var): Set<TACSymbol.Var> {
        return cmdIn[cmd]?.getNode(f)?.vars?.programVariables()?.toSet().orEmpty()
    }

    /**
     * Return the set of variables whose values are equal to [f] after
     * executing the command at [cmd]
     */
    override fun equivAfter(cmd: CmdPointer, f: TACSymbol.Var): Set<TACSymbol.Var> {
        return cmdOut[cmd]?.getNode(f)?.vars?.programVariables()?.toSet().orEmpty()
    }

    /**
     * Project out the unversioned, i.e. program variables
     */
    private fun Iterable<VersionedVar>.programVariables(): Sequence<TACSymbol.Var> {
        return this.asSequence().filter { it.version == null }.map { it.sym }
    }

    /**
     * Project out the versioned variables
     */
    private fun Iterable<VersionedVar>.versioned(): Sequence<VersionedVar> {
        return this.asSequence().filter { it.version != null }
    }

    override fun transform(inState: StrongEquivalenceDAG, block: TACBlock): StrongEquivalenceDAG {
        // We override this function so that we can make up phi nodes on the fly.
        // It's probably worth profiling to see if we should just do an SSA analysis
        // up front.
        val state = inState.toMutableStrongEquivalenceDAG()
            if (block.commands.isNotEmpty()) {
                val cmd = block.commands[0]

                // This is important: otherwise we start
                // generating huge graphs which slows down the join
                // operation considerably
                val live = graph.cache.lva.liveVariablesBefore(cmd.ptr)
                state.prune(live)

                // Because the program is in DSA, we may have just joined
                // Left: { x, x@L0 }
                // Right: { x, x@L1 }
                // ==> { x }
                // so we just use the label of the new block to make up a version
                // for each such x
                phis[block.id]?.let { vs ->
                    state.inventVersions(Version(cmd.ptr, Loc.BEFORE), vs.intersect(live))
                }
            }
        return super.transform(state, block)
    }

    override fun transformCmd(inState: StrongEquivalenceDAG, cmd: LTACCmd): StrongEquivalenceDAG {
        val c = cmd.cmd
        inState.toMutableStrongEquivalenceDAG().let { sed ->
            when {
                // L: x := opaque_identity(y)
                // if followIdentities is true, treat this as just L: x := y (see below)
                followIdentities && c is TACCmd.Simple.AssigningCmd.AssignExpCmd && c.rhs is TACExpr.Apply && c.rhs.f.let {
                    it is TACExpr.TACFunctionSym.BuiltIn && it.bif is TACBuiltInFunction.OpaqueIdentity
                } && c.rhs.ops.size == 1 && c.rhs.ops.single() is TACExpr.Sym -> {
                    sed.assign(Version(cmd.ptr), c.lhs, (c.rhs.ops.single() as TACExpr.Sym).s)
                }
                // L: x := y or const
                // treat this as:
                // x := y; x@L := L, i.e.:
                //  - add x to y's class
                //  - add x@L to y's class
                c is TACCmd.Simple.AssigningCmd.AssignExpCmd
                        && c.rhs is TACExpr.Sym -> {
                    if(c.rhs is TACExpr.Sym.Const && !groupByConst) {
                        fresh(sed, cmd.ptr, c.lhs)
                    } else {
                        sed.assign(Version(cmd.ptr), c.lhs, c.rhs.s)
                    }
                }

                // Create a fresh expression class for assignments we can't or don't want to handle
                c is TACCmd.Simple.AssigningCmd -> fresh(sed, cmd.ptr, c.lhs)
                c is TACCmd.Simple.SummaryCmd && c.summ is AssigningSummary -> {
                    for (v in c.summ.mustWriteVars) {
                        fresh(sed, cmd.ptr, v)
                    }
                    for (v in c.summ.mayWriteVars) {
                        fresh(sed, cmd.ptr, v)
                    }
                }
            }
            return sed
        }
    }

    /**
     * Havoc [x] and [x]@[where] by removing any equalities and setting them equal
     */
    private fun fresh(sed: MutableStrongEquivalenceDAG, where: CmdPointer, x: TACSymbol.Var) {
        sed.assign(Version(where), x, x)
    }

}

// Extend me to track expressions beyond constants and variables
sealed class NodeType {
    data class Const(val c: TACSymbol) : NodeType()
}

// A Node is an equivalence class of variables and optionally a constant
data class Node(val vars: TreapSet<VersionedVar>, val type: NodeType?) {

    private val cachedHashCode = vars.hashCode() * 31 + type.hashCode()
    override fun hashCode() = cachedHashCode

    val syms by lazy(LazyThreadSafetyMode.PUBLICATION) {
        vars.mapToTreapSet { v -> v.sym }
    }

    fun addVar(v: VersionedVar): Node {
        return Node(vars + v, type)
    }

    fun addVars(v: Iterable<VersionedVar>): Node {
        return Node(vars + v, type)
    }

    fun subVar(v: VersionedVar): Node? {
        return Node(vars - v, type).takeIf { it.vars.isNotEmpty() }
    }

    fun intersect(that: Node): Node? {
        val vs = this.vars.intersect(that.vars)
        if (!vs.containsAny { it.version == null }) {
            return null
        } else {
            return Node(vs, this.type.takeIf { it == that.type })
        }
    }
}

// The location of an assignment: either before or after a program location.
// Used to uniquely identify a value in a trace
data class Version(val ptr: CmdPointer, val where: Loc = Loc.AFTER)
enum class Loc { BEFORE, AFTER }

data class VersionedVar(val version: Version?, val sym: TACSymbol.Var) {
    private val cachedHashCode = version.hashCode() * 31 + sym.hashCode()
    override fun hashCode() = cachedHashCode
}

interface StrongEquivalenceDAG {
    val classes: TreapSet<Node>
    val unversionedIndex: TreapMap<TACSymbol.Var, Node>
    val versionedIndex: TreapMap<VersionedVar, Node>
    fun toMutableStrongEquivalenceDAG(): MutableStrongEquivalenceDAG
    fun finalize(): StrongEquivalenceDAG
    fun getNode(c: TACSymbol): Node?
    fun join(them: StrongEquivalenceDAG): StrongEquivalenceDAG
}

/*
 * Note this isn't actually a DAG in any interesting sense (but if we want to track equalities such as
 * x = y + z, then we can extend it to be such)
 */
class MutableStrongEquivalenceDAG private constructor(
    override var classes: TreapSet<Node>,
    override var unversionedIndex: TreapMap<TACSymbol.Var, Node>,
    override var versionedIndex: TreapMap<VersionedVar, Node>
) : StrongEquivalenceDAG {
    constructor() : this(treapSetOf(), treapMapOf(), treapMapOf())

    override fun hashCode() = classes.hashCode()
    override fun equals(other: Any?) = other is MutableStrongEquivalenceDAG && classes == other.classes

    private var VersionedVar.node: Node?
        get() = if (version == null) { unversionedIndex[sym] } else { versionedIndex[this] }
        set(value) {
            when {
                version == null && value == null -> unversionedIndex -= sym
                version == null -> unversionedIndex += sym to value!!
                value == null -> versionedIndex -= this
                else -> versionedIndex += this to value
            }
        }

    /** Move ([where], [lhs]) into [rhs]'s equivalence class */
    fun assign(where: Version, lhs: TACSymbol.Var, rhs: TACSymbol) {
        addToNode(null, lhs, rhs)
        addToNode(where, lhs, lhs)
    }

    /**
     * Add (l, v) to the node for x
     */
    fun addToNode(l: Version?, v: TACSymbol.Var, x: TACSymbol) {
        val vv = VersionedVar(l, v)
        kill(vv)
        when (x) {
            is TACSymbol.Var -> {
                val rhsVV = VersionedVar(null, x)
                rhsVV.node?.let {
                    classes -= it
                    it.addVar(vv)
                } ?: Node(treapSetOf(rhsVV, vv), null)
            }
            is TACSymbol.Const -> {
                val rhsConst = NodeType.Const(x)
                classes.firstOrNull { it.type == rhsConst }?.let {
                    classes -= it
                    it.addVar(vv)
                } ?: Node(treapSetOf(vv), rhsConst)
            }
        }.addToGraph()
    }

    /**
     * Remove v from its node
     */
    fun kill(v: VersionedVar) {
        v.node?.let { node ->
            node.vars.forEachElement {
                it.node = null
            }
            classes -= node
            node.subVar(v)?.addToGraph()
        }
    }

    fun prune(live: Set<TACSymbol.Var>) {
        val toKill = treapSetBuilderOf<Node>()
        classes.forEachElement { c ->
            // Here we're only going to prune these variables if they're not going to be
            // telling us anything interesting
            if (!live.containsAny(c.syms)) {
                if (toKill.add(c)) {
                    c.vars.forEachElement { v ->
                        v.node = null
                    }
                }
            }
        }
        classes -= toKill
    }

    /**
     * Create a [where] version for all tracked variables. Equivalent to
     * analyzing x@L := x for all x.
     */
    fun inventVersions(where: Version, vars: Set<TACSymbol.Var>) {
        for (x in vars) {
            addToNode(where, x, x)
        }
    }

    /** Copy this StrongEquivalenceDAG */
    override fun toMutableStrongEquivalenceDAG(): MutableStrongEquivalenceDAG {
        return MutableStrongEquivalenceDAG(classes, unversionedIndex, versionedIndex)
    }

    /** Perform class-wise intersection */
    override fun join(them: StrongEquivalenceDAG): StrongEquivalenceDAG {
        // computeIfAbsent treats null as the same as "absent", so we need to use a different sentinel value.
        val intersections = ArrayHashMap<Pair<Node, Node>, Node>()
        val none = Node(treapSetOf(), null)

        fun <K> intersect(k: K, n1: Node, n2: Node) =
            intersections.computeIfAbsent(n1 to n2) { n1.intersect(n2) ?: none }.also { unused(k) }

        val newUnversionedIndex = unversionedIndex.intersect(them.unversionedIndex, ::intersect)
        val newVersionedIndex = versionedIndex.intersect(them.versionedIndex, ::intersect)

        val c = intersections.values.mapNotNullToTreapSet { it.takeIf { it !== none }}
        return MutableStrongEquivalenceDAG(c, newUnversionedIndex, newVersionedIndex)
    }

    override fun finalize(): StrongEquivalenceDAG {
        // Since getNode never queries for a versioned variable, we can erase the versionedIndex
        return MutableStrongEquivalenceDAG(classes, unversionedIndex, versionedIndex = treapMapOf())
    }

    override fun getNode(c: TACSymbol): Node? {
        if (c is TACSymbol.Var) {
            val vv = VersionedVar(null, c)
            return vv.node ?: Node(treapSetOf(vv), null).addToGraph()
        } else {
            return classes.firstOrNull { it.type == NodeType.Const(c) }
        }
    }

    private fun Node.addToGraph(): Node {
        classes += this
        vars.forEachElement {
            it.node = this
        }
        return this
    }
}
