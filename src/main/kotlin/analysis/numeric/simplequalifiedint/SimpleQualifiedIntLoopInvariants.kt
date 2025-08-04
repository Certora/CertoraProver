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

package analysis.numeric.simplequalifiedint

import datastructures.stdcollections.*
import analysis.GenericTACCommandGraph
import analysis.LTACCmdGen
import analysis.Loop
import analysis.TACBlockGen
import analysis.numeric.LoopInvariantHeuristic
import analysis.numeric.SimpleIntQualifier
import com.certora.collect.TreapSet
import com.certora.collect.filterIsInstance
import com.certora.collect.intersect
import com.certora.collect.mutate
import com.certora.collect.treapMapBuilderOf
import com.certora.collect.treapSetOf
import tac.NBId
import utils.tryAs
import utils.uncheckedAs
import vc.data.TACCmd
import vc.data.TACSymbol
import java.math.BigInteger

interface SimpleQualifiedIntLoopInvariants<T, U, V, G, S> : LoopInvariantHeuristic<G, S>
    where T : TACCmd,
          U : LTACCmdGen<T>,
          V : TACBlockGen<T, U>,
          G : GenericTACCommandGraph<T, U, V> {
    fun liveBefore(b: NBId): Set<TACSymbol.Var>

    fun symbols(c: LTACCmdGen<T>): Set<TACSymbol> = treapSetOf<TACSymbol>().mutate { syms ->
        c.cmd.tryAs<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.let { syms += it.getRhs() }
        c.cmd.tryAs<TACCmd.Simple.AssigningCmd>()?.let { syms += it.lhs }
    }

    fun interpret(state: S, s: TACSymbol): SimpleQualifiedInt

    fun applyInvariants(state: S, invariants: Map<TACSymbol.Var, SimpleQualifiedInt>): S

    /**
     * Guess loop invariants of the form x : ModularUpperBound(y, modulus, strong)
     *
     * Generally if we have symbols: x, y, n that appear in [loop],
     *  and if in [state] we have that x <= y, we will guess x: ModularUpperBound(y, 1, x < y)
     *  additionally, if we know that y - x == n, we will guess x: ModularUpperBound(y, n, false)
     *  (the reason for 'false', even though y > x, is that typically this is too strong as a loop
     *  _invariant_ (but we'll recover the strong bound with the loop condition as is standard)
     */
    override fun guessLoopInvariants(graph: G, loop: Loop, state: S): S {
        val invariants = treapMapBuilderOf<TACSymbol.Var, SimpleQualifiedInt>()
        val liveBeforeHead = liveBefore(loop.head)

        val syms = treapSetOf<TACSymbol>().mutate { syms ->
            for (b in loop.body) {
                graph.elab(b).commands.forEach { it ->
                    syms += symbols(it)
                }
            }
        }

        val consts = syms.filterIsInstance<TACSymbol.Const>()

        val liveSyms = (syms intersect liveBeforeHead).uncheckedAs<TreapSet<TACSymbol.Var>>()

        liveSyms.forEachElement { s1 ->
            val q = mutableSetOf<SimpleIntQualifier.ModularUpperBound>()
            val v1 = interpret(state,s1)
            syms.forEachElement { s2 ->
                if (s1 != s2) {
                    val v2 = interpret(state,s2)
                    if (v1.x.ub <= v2.x.lb) {
                        q.add(SimpleIntQualifier.ModularUpperBound(s2, BigInteger.ONE, v1.x.ub < v2.x.lb))
                    }
                }
            }
            // We have a symbol s1 and its abstraction v1. If v1 is constant,
            // We can look for c1 and c2 s.t. v1 < c2 and c1 divides (c2 - v1).
            // Then c2 is a modular upper bound of v1 with modulus c1.
            if (v1.x.isConstant) {
                consts.forEachElement { c1 -> // c1 should divide the difference between c2 and v1
                    consts.forEachElement { c2 ->
                        if (c1.value != BigInteger.ZERO && v1.x.c <= c2.value && c1.value < c2.value) {
                            if ((c2.value - v1.x.c).rem(c1.value) == BigInteger.ZERO) {
                                q.add(SimpleIntQualifier.ModularUpperBound(c2, c1.value, false))
                            }
                        }
                    }
                }
            }
            invariants[s1] = v1.copy(qual = v1.qual + q)
        }
        return applyInvariants(state, invariants)
    }
}
