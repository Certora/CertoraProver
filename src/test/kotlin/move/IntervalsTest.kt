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

package move

import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import move.analysis.MoveIntervalAnalysis
import move.analysis.LocValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import utils.ModZm.Companion.asBigInteger
import vc.data.TACCmd

class IntervalsTest : MoveTestFixture() {
    override val loadStdlib = true

    @Test
    fun `vec eq length eq`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v = nondet<vector<u64>>();
                cvlm_assume(v.length() == 3);
                cvlm_assert(v.length() == 3);
            }
        """.trimIndent())
        assertReachableAnd(true)
    }

    @Test
    fun push() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut v = nondet<vector<u64>>();
                let x = nondet<u64>();
                let n = v.length();
                v.push_back(x);
                cvlm_assert(v.length() == n);
            }
        """.trimIndent())
        assertReachableAnd(null)
    }


    @Test
    fun pop() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut v = nondet<vector<u64>>();
                let n = v.length();
                let _ = v.pop_back();
                cvlm_assert(v.length() == n);
            }
        """.trimIndent())
        assertReachableAnd(null)
    }

    @Test
    fun `nondet vector length bounds weaken`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v = nondet<vector<u64>>();
                if (v.length() <= 3) {
                    cvlm_assert(v.length() <= 10);
                }
            }
        """.trimIndent())

        assertReachableAnd(true)
    }

    @Test
    fun `nondet vector length bounds weaken false`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v = nondet<vector<u64>>();
                if (v.length() <= 3) {
                    cvlm_assert(v.length() > 4);
                }
            }
        """.trimIndent())

        assertReachableAnd(false)
    }

    @Test
    fun `nondet vector length bounds strengthen`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v = nondet<vector<u64>>();
                if (v.length() <= 3) {
                    cvlm_assert(v.length() <= 2);
                }
            }
        """.trimIndent())

        assertReachableAnd(null)
    }


    @Test
    fun `nested lengths not necessarily equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
              let i = nondet<u64>();
              let j = nondet<u64>();
              let vs = nondet<vector<vector<u64>>>();
              let v1 = vs.borrow(i);
              let v2 = vs.borrow(j);

              cvlm_assume(v1.length() <= 5);
              cvlm_assert(v2.length() <= 5);
            }
        """.trimIndent())
        assertReachableAnd(null)
    }


    private fun assertVarAbstraction(): Pair<LocValue?, SimpleQualifiedInt>? {
        val moveTAC = compileToMoveTAC()
        val intervals = MoveIntervalAnalysis(moveTAC.graph)
        val assert = moveTAC.graph.commands.single { it.cmd is TACCmd.Simple.AssertCmd }
        val stateHere = intervals.inState(assert.ptr) ?: return null
        val assertVar = (assert.cmd as TACCmd.Simple.AssertCmd).o
        return (stateHere.vecState[assertVar] to stateHere.intState.interpret(assertVar))
    }

    private fun assertReachableAnd(tt: Boolean?) {
        val (_, av) = assertVarAbstraction() ?: Assertions.fail()
        Assertions.assertTrue {
            (tt == null && !av.x.isConstant) ||
                (tt != null && av.x.x.isConstant && av.x.c == tt.asBigInteger)
        }
    }

}
