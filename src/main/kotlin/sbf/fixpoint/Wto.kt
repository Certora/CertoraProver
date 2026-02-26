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

package sbf.fixpoint

import sbf.disassembler.Label
import sbf.cfg.SbfCFG
import datastructures.stdcollections.*

/** Implement Weak Topological Ordering as defined in Bourdoncle,
 * "Efficient chaotic iteration strategies with widenings", 1993
 * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.38.3574
 *
 * Using the example from section 3.1 in the paper, the graph:
 *
 *         4 --> 5 <-> 6
 *         ^ \________ |
 *         |          vv
 *         3 <-------- 7
 *         ^           |
 *         |           v
 *   1 --> 2 --------> 8
 *
 * results in the WTO: 1 2 (3 4 (5 6) 7) 8
 *
 * A single vertex is represented via WtoVertex, and a cycle such as (5 6) is represented via a WtoCycle.
 * Each arrow points to a WtoComponent, which can be either a single vertex such as 8, or a cycle such as (5 6).
 * A WtoNesting of a vertex v is the set of heads of the nested components containing v.
 * For instance, WtoNesting(7) = {3, 5}, WtoNesting(4) = {3}, and WtoNesting(8) = {}
 *
 * A hierarchical ordering  of a set is a well-parenthesized permutation of the set without two consecutive
 * "(". A Weak Topological Ordering (WTO) of a directed graph is a hierarchical ordering of its nodes such that
 * for each edge (x, y):
 *    - if y is not in WtoNesting(x) then x appears before y
 *    - otherwise, y appears before x.
 **/

sealed class WtoComponent

data class WtoVertex(val label: Label): WtoComponent() {
    override fun toString(): String {
        return label.toString()
    }
}

typealias WtoPartition = ArrayList<WtoComponent>

/**
 * Bourdoncle's section 3 uses the term "nested component" to refer to what WtoCycle implements.
 **/
data class WtoCycle(val parent: WtoCycle? = null,
                    // stored in reverse order
                    val components: WtoPartition = WtoPartition()): WtoComponent() {
    // Any cycle must start with a vertex, not another cycle,
    // per Definition 1 in the paper.  Since the array is in reverse
    // order, the head is the last element.
    fun head() = components.last() as WtoVertex

    fun getComponents(): List<WtoComponent> = components.asReversed()

    override fun toString(): String {
        val sb = StringBuilder("(")
        val last = components.size - 1
        components.asReversed().forEachIndexed {i, c ->
            sb.append(c.toString())
            if (i < last) {
                sb.append(" ")
            }
        }
        sb.append(")")
        return sb.toString()
    }
}

/**
 * Bourdoncle's uses the notation w(c) to refer to the set of heads of the nested components
 * containing a vertex c. The class WtoNesting holds such a set of heads.
 **/
class WtoNesting(
    // stored in reverse order
    val heads: ArrayList<Label>) {

    // Return true if @this is strictly a larger wto nesting than @other
    fun contains(other: WtoNesting): Boolean {
        val thisSize = heads.size
        val otherSize = other.heads.size
        if (thisSize <= otherSize) {
            // Can't be a superset.
            return false
        }

        // Compare entries one at a time starting from the outermost
        // (i.e., end of the vectors).
        var index = 0
        while (index < otherSize) {
            if (heads[thisSize - 1 - index] != other.heads[otherSize - 1 - index]) {
                return false
            }
            index++
        }
        return true
    }

    override fun toString(): String {
        val sb = StringBuilder("{")
        val last = heads.size - 1
        heads.asReversed().forEachIndexed {i, c ->
            sb.append(c.toString())
            if (i < last) {
                sb.append(",")
            }
        }
        sb.append("}")
        return sb.toString()
    }

}

class Wto(val cfg: SbfCFG) {
    /* The paper calls it depth-first number (DFN) */
    private val dfn: MutableMap<Label, Int> = mutableMapOf()
    /* highest DFN used so far */
    private var num: Int = 0
    private val stack = ArrayList<Label>()
    /* Top-level components in reverse order. The WTO of CFG can be extracted from here */
    private val components = WtoPartition()
    /* Map a label to the set of cycle heads containing that label*/
    private val nesting = mutableMapOf<Label, WtoNesting>()
    /* Map a label to the cycle containing the label */
    private val containingCycleMap = mutableMapOf<Label, WtoCycle>()

    init {
        compute()
    }

    /**
     * Recursive implementation of WTO.
     * REVISIT: iterative implementation to avoid stack overflow for large CFGs
     */
    private fun visit(vertex: Label, partition: WtoPartition, containingCycle: WtoCycle?): Int {
        stack.add(vertex)
        num++
        dfn[vertex] = num
        var head = num
        var loop = false
        var min: Int
        val block = cfg.getBlock(vertex)
        check(block != null)
        for (succB in block.getSuccs()) {
            val succ = succB.getLabel()
            min = if (dfn[succ] == 0) {
                visit(succ, partition, containingCycle)
            } else {
                val n = dfn[succ]
                check(n != null)
                n
            }
            if (min <= head) {
                head = min
                loop = true
            }
        }
        if (head == dfn[vertex]) {
            dfn[vertex] = Int.MAX_VALUE // a way to remove all heads when visit is called
            var element = stack.removeLast()
            if (loop) {
                while (element != vertex) {
                    dfn[element] = 0
                    element = stack.removeLast()
                }
                // Create a new cycle component.
                // Walk the control flow graph, adding nodes to this cycle.
                // This is the Component() function described in figure 4 of the paper.
                val cycle = WtoCycle(containingCycle)
                for (succB in block.getSuccs()) {
                    if (dfn[succB.getLabel()] == 0) {
                        visit(succB.getLabel(), cycle.components, cycle)
                    }
                }
                // Finally, add the vertex at the start of the cycle
                // (end of the array which stores the cycle in reverse order).
                cycle.components.add(WtoVertex(vertex))
                // Insert the component into the current partition.
                partition.add(cycle as WtoComponent)
                // Remember that we put the vertex into the caller's cycle.
                containingCycleMap[vertex] = cycle

            } else {
                // Insert the vertex into the current partition.
                partition.add(WtoVertex(vertex) as WtoComponent)
                // Remember that we put the vertex into the caller's cycle.
                if (containingCycle != null) {
                    containingCycleMap[vertex] = containingCycle
                }
            }
        }
        return head
    }

    /** Get the vertex at the head of the component containing a given
     * label, as discussed in section 4.2 of the paper.  If the label
     * is itself a head of a component, we want the head of whatever
     * contains that entire component.  Returns null if the label is
     * not nested.
     **/
    private fun head(label: Label): Label? {
        val cycle = containingCycleMap[label] ?: return null
        if (cycle.head().label != label) {
            return cycle.head().label
        }
        val parent = cycle.parent
        return parent?.head()?.label
    }

    private fun compute() {
        for (label in cfg.getBlocks().keys) {
            dfn[label] = 0
        }
        num = 0
        val entry = cfg.getEntry()
        visit(entry.getLabel(), components, null)
    }

    // Public methods start here

    fun getComponents(): List<WtoComponent> {
       return components.asReversed()
    }

    @Suppress("unused")
    fun isInCycle(label: Label): Boolean {
        return containingCycleMap[label] != null
    }

    /** Return the set of cycles that contains label **/
    @Suppress("NAME_SHADOWING")
    fun nesting(label: Label): WtoNesting {
        val containingCycles = nesting[label]
        return if (containingCycles != null) {
            containingCycles
        } else {
            val containingCycles = ArrayList<Label>()
            var cycle = head(label)
            while (cycle != null) {
                containingCycles.add(cycle)
                cycle = head(cycle)
            }
            val out = WtoNesting(containingCycles)
            nesting[label] = out
            out
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        val last = components.size - 1
        components.asReversed().forEachIndexed {i, c ->
            sb.append(c.toString())
            if (i < last) {
                sb.append(" ")
            }
        }
        return sb.toString()
    }


}

