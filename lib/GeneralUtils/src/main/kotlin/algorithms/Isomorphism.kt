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

package algorithms

import com.certora.collect.*
import datastructures.stdcollections.*
import utils.*

/**
    A directed graph with labeled vertices and ordered edges.  The labels are not necessarily unique, and the edges are
    not necessarily unique.  Supports finding isomorphic subgraphs.
 */
data class LabeledOrderedDigraph<@Treapable V, L>(
    val labels: Map<V, L>,
    val edges: Map<V, List<V>>
) {
    /**
        Finds a representative vertex for each original vertex in the graph.  All vertices with the same representative
        vertex are part of isomorphic labeled subgraphs.

        We use the basic Weisfeiler-Lehman algorithm to avoid too many full structural comparisons:

            https://par.nsf.gov/servlets/purl/10299993
            https://www.davidbieber.com/post/2019-05-10-weisfeiler-lehman-isomorphism-test/

        Since this is a hash-based algorithm, it is important for the type L to have a good hash function.  Also,
        Weisfeiler-Lehman can rule out isomorphism, but it cannot prove it.  So, for each set of vertices identified as
        possibly isomorphic in this way, we still need to compare the actual labels and edges to see if they are
        isomorphic. This means the worst-case performance of this function is quite bad, but we hope typical graphs will
        do a lot better.
     */
    fun findIsomorphicSubgraphs(maxIterations: Int): Map<V, V> {
        // Start with initial hashes based on the labels
        var hashes: Map<V, Int> = labels.mapValues { (_, l) -> l.hashCode() }

        // Nodes are not equivalent if they have different hashes.
        fun possibleEquivClasses(h: Map<V, Int>): TreapSet<TreapSet<V>> =
            h.entries.groupBy({ it.value }, { it.key }).values.map { it.toTreapSet() }.toTreapSet()

        // Iterate until the equiv classes converge
        var equivClasses = possibleEquivClasses(hashes)
        for (ignored in 1..maxIterations) {
            // New hash = current hash of node + current hashes of successors
            val newHashes = labels.mapValues { (v, _) ->
                hash { it + hashes[v]!! + edges[v].orEmpty().map { hashes[it]!! } }
            }

            val newEquivClasses = possibleEquivClasses(newHashes)
            if (newEquivClasses == equivClasses)   {
                break
            }
            equivClasses = newEquivClasses
            hashes = newHashes
        }

        // For each possible equivalence class, find the set of actually-equivalent vertices, and pick a representative
        // for each.  If an equivalence class is large, or we do a bad job of narrowing things down above, this can be
        // very expensive.
        return buildMap {
            equivClasses.forEachElement { possiblyEquiv ->
                val representatives = mutableSetOf<V>()
                possiblyEquiv.forEachElement { v ->
                    val existingRep = representatives.find { areIsomorphic(it, v) }
                    if (existingRep != null) {
                        // v is isomorphic to an existing representative
                        put(v, existingRep)
                    } else {
                        // v is not isomorphic to any existing representative; add it as a new representative
                        representatives.add(v)
                        put(v, v)
                    }
                }
            }
        }
    }

    /**
        Compare two subgraphs for isomorphism
     */
    @Suppress("NAME_SHADOWING") // we intentionally shadow the outer v1 and v2
    fun areIsomorphic(
        v1: V,
        v2: V
    ): Boolean {
        val work = arrayDequeOf<Pair<V, V>>(v1 to v2)
        val visited = mutableSetOf<Pair<V, V>>(v1 to v2)

        while (work.isNotEmpty()) {
            val (v1, v2) = work.removeLast()

            if (labels[v1] != labels[v2]) {
                return false
            }

            val edges1 = edges[v1].orEmpty()
            val edges2 = edges[v2].orEmpty()
            if (edges1.size != edges2.size) {
                return false
            }

            edges1.zip(edges2).forEach { (e1, e2) ->
                if (visited.add(e1 to e2)) {
                    work.addLast(e1 to e2)
                }
            }
        }

        return true
    }
}
