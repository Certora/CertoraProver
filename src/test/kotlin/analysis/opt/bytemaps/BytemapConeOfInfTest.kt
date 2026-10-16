/*
 *     The Certora Prover
 *     Copyright (C) 2026 Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.opt.bytemaps

import datastructures.stdcollections.*
import instrumentation.transformers.FilteringFunctions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tac.NBId
import vc.data.*

/** Tests [BytemapConeOfInf] on store chains read at a location defined below them. */
class BytemapConeOfInfTest : TACBuilderAuxiliaries() {
    private val bMap4 = mkBmVar("bMap4")
    private val bMap4S = bMap4.asSym()

    /**
     * Runs [BytemapConeOfInf] on [prog]: the result, and how many bytemap definitions it took whole for a query that
     * may read anywhere ([BytemapConeOfInf.READ_ANYWHERE_STAT]).
     */
    private fun run(prog: TACProgramBuilder.BuiltTACProgram) =
        BytemapConeOfInf.goWithStats(prog.code, FilteringFunctions.NoFilter).let { (code, stats) ->
            code to (stats[BytemapConeOfInf.READ_ANYWHERE_STAT] ?: 0)
        }

    /** The blocks of [code] that assign [v]. */
    private fun defBlocks(code: CoreTACProgram, v: TACSymbol.Var) =
        code.analysisCache.graph.commands.filter { it.cmd.getLhs() == v }.map { it.ptr.block }.toSet()

    /** The right-hand side assigned to [v] in [code]. */
    private fun rhsOf(code: CoreTACProgram, v: TACSymbol.Var) =
        code.analysisCache.graph.commands.mapNotNull { it.cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd }
            .single { it.lhs == v }.rhs

    /**
     * The load's location `c` is defined below both stores and may be anything, so the query may read either store:
     * both are kept, and so is everything they are made of.
     */
    @Test
    fun queryWhoseLocationMayBeAnythingKeepsTheWholeChain() {
        val prog = TACProgramBuilder {
            havoc(bMap1)
            havoc(a)
            havoc(b)
            bMap2 assign TACExpr.Store(bMap1S, 0x20.asTACExpr, aS)
            bMap3 assign TACExpr.Store(bMap2S, 0x40.asTACExpr, bS)
            havoc(c)
            d assign bMap3[c]
            y assign Gt(dS, 5.asTACExpr)
            assert(y)
        }
        val (result, takenWhole) = run(prog)
        assertTrue(rhsOf(result, bMap2) is TACExpr.Store)
        assertTrue(rhsOf(result, bMap3) is TACExpr.Store)
        val b0 = setOf(prog.block(0))
        assertEquals(listOf(b0, b0, b0), listOf(bMap1, a, b).map { defBlocks(result, it) })
        // seen at the last store; the one above it is then taken whole without asking again
        assertEquals(1, takenWhole)
    }

    /**
     * The same, but `c` is below `0x20`. It is still defined below both stores, so only its interval tells it apart
     * from their locations, and that is enough: neither store is read, and neither stored value is needed.
     */
    @Test
    fun queryWhoseLocationIsDefinedBelowTheStoresIsStillToldApartByItsInterval() {
        val prog = TACProgramBuilder {
            havoc(bMap1)
            havoc(a)
            havoc(b)
            bMap2 assign TACExpr.Store(bMap1S, 0x20.asTACExpr, aS)
            bMap3 assign TACExpr.Store(bMap2S, 0x40.asTACExpr, bS)
            havoc(c)
            z assign Lt(cS, 0x20.asTACExpr)
            assume(z)
            d assign bMap3[c]
            y assign Gt(dS, 5.asTACExpr)
            assert(y)
        }
        val (result, takenWhole) = run(prog)
        assertEquals(bMap1S, rhsOf(result, bMap2))
        assertEquals(bMap2S, rhsOf(result, bMap3))
        assertEquals(listOf(setOf<NBId>(), setOf()), listOf(a, b).map { defBlocks(result, it) })
        assertEquals(0, takenWhole)
    }

    /** A longstore read at a location that may be anything is taken whole, with its source and destination. */
    @Test
    fun longstoreReadAnywhereIsTakenWhole() {
        val prog = TACProgramBuilder {
            havoc(bMap1)
            havoc(bMap3)
            havoc(a)
            bMap2 assign TACExpr.Store(bMap1S, 0x20.asTACExpr, aS)
            bMap4 assign TACExpr.LongStore(
                dstMap = bMap3S,
                dstOffset = 0x100.asTACExpr,
                srcMap = bMap2S,
                srcOffset = 0x0.asTACExpr,
                length = 0x40.asTACExpr
            )
            havoc(c)
            d assign bMap4[c]
            y assign Gt(dS, 5.asTACExpr)
            assert(y)
        }
        val (result, takenWhole) = run(prog)
        assertTrue(rhsOf(result, bMap4) is TACExpr.LongStore)
        assertTrue(rhsOf(result, bMap2) is TACExpr.Store)
        val b0 = setOf(prog.block(0))
        assertEquals(listOf(b0, b0, b0), listOf(bMap1, bMap3, a).map { defBlocks(result, it) })
        assertEquals(1, takenWhole)
    }

    /** Through an ite, each branch's store chain is taken whole where the query first reaches it. */
    @Test
    fun bothBranchesOfAnIteReadAnywhereAreTakenWhole() {
        val prog = TACProgramBuilder {
            havoc(bMap1)
            havoc(a)
            havoc(b)
            havoc(x)
            bMap2 assign TACExpr.Store(bMap1S, 0x20.asTACExpr, aS)
            bMap3 assign TACExpr.Store(bMap1S, 0x40.asTACExpr, bS)
            bMap4 assign Ite(xS, bMap2S, bMap3S)
            havoc(c)
            d assign bMap4[c]
            y assign Gt(dS, 5.asTACExpr)
            assert(y)
        }
        val (result, takenWhole) = run(prog)
        assertTrue(rhsOf(result, bMap2) is TACExpr.Store)
        assertTrue(rhsOf(result, bMap3) is TACExpr.Store)
        assertEquals(2, takenWhole)
    }

    /**
     * `c` is defined between the store and the longstore. At the longstore it may still be compared, so the query
     * goes on to the source shifted by `-0x100`, and the store is where it may read anywhere.
     */
    @Test
    fun shiftedQueryReadsAnywhereAtTheStoreAboveTheLongstore() {
        val prog = TACProgramBuilder {
            havoc(bMap1)
            havoc(a)
            bMap2 assign TACExpr.Store(bMap1S, 0x20.asTACExpr, aS)
            havoc(bMap3)
            havoc(c)
            bMap4 assign TACExpr.LongStore(
                dstMap = bMap3S,
                dstOffset = 0x100.asTACExpr,
                srcMap = bMap2S,
                srcOffset = 0x0.asTACExpr,
                length = 0x40.asTACExpr
            )
            d assign bMap4[c]
            y assign Gt(dS, 5.asTACExpr)
            assert(y)
        }
        val (result, takenWhole) = run(prog)
        assertTrue(rhsOf(result, bMap4) is TACExpr.LongStore)
        assertTrue(rhsOf(result, bMap2) is TACExpr.Store)
        assertEquals(setOf(prog.block(0)), defBlocks(result, a))
        assertEquals(1, takenWhole)
    }
}
