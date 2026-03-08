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

import instrumentation.transformers.FilteringFunctions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.*
import vc.data.TACProgramBuilder.Companion.testProgString
import vc.data.tacexprutil.CmdsFolder
import vc.data.tacexprutil.asSym

/**
 * Mostly tests longstore inlining in [BytemapInliner], but also checks [BytemapConeOfInf] works properly.
 */
class OptimizeBytemapsTest : TACBuilderAuxiliaries() {

    private fun ls(dstMap: TACSymbol.Var, dstOffset: Int, srcMap: TACSymbol.Var, srcOffset: Int, length: Int = 0x40) =
        TACExpr.LongStore(dstMap.asSym(), dstOffset.asTACExpr, srcMap.asSym(), srcOffset.asTACExpr, length.asTACExpr)

    private fun ls(dstMap: TACSymbol.Var, dstOffset: TACSymbol, srcMap: TACSymbol.Var, srcOffset: TACSymbol, length: TACSymbol) =
        TACExpr.LongStore(dstMap.asSym(), dstOffset.asSym(), srcMap.asSym(), srcOffset.asSym(), length.asSym())

    private fun ls(dstMap: TACSymbol.Var, dstOffset: TACExpr, srcMap: TACSymbol.Var, srcOffset: TACExpr, length: TACExpr) =
        TACExpr.LongStore(dstMap.asSym(), dstOffset, srcMap.asSym(), srcOffset, length)

    private fun s(map: TACSymbol.Var, offset: Int, value: TACSymbol.Var) =
        TACExpr.Store(map.asSym(), offset.asTACExpr, value.asSym())

    private fun newBmVar(name: String): TACSymbol.Var {
        return TACSymbol.Var(name, Tag.ByteMap)
    }

    private val aM = newBmVar("aM")
    private val bM = newBmVar("bM")
    private val cM = newBmVar("cM")
    private val dM = newBmVar("dM")
    private val eM = newBmVar("eM")
    private val fM = newBmVar("fM")
    private val gM = newBmVar("gM")
    private val hM = newBmVar("hM")
    private val iM = newBmVar("iM")
    private val rM = newBmVar("rM")

    private fun assertSameProg(expected: CoreTACProgram, resulting: CoreTACProgram) {
        assertEquals(
            testProgString(expected),
            testProgString(resulting)
        )
    }

    private fun runAndCompare(
        progGen: TACProgramBuilder.BlockBuilder.() -> Unit,
        expectedGen: TACProgramBuilder.BlockBuilder.() -> Unit,
        justExp: Boolean = false
    ) {
        val tempReadVar = bv256Var("tempReadVar")
        val tempAssertVar = boolVar("tempAssertVar")
        val prog = TACProgramBuilder {
            progGen()
            tempReadVar assign rM[b]
            tempAssertVar assign Eq(tempReadVar.asSym(), Zero)
            assert(tempAssertVar)
        }
        val expected = TACProgramBuilder {
            expectedGen()
            tempReadVar assign rM[b]
            tempAssertVar assign Eq(tempReadVar.asSym(), Zero)
            assert(tempAssertVar)
        }
        val result = BytemapInliner.go(prog.code, { false }, cheap = false)
            .let { BytemapConeOfInf.go(it, FilteringFunctions.NoFilter) }
        // TACProgramPrinter.standard().print(result)
        if (justExp) {
            // Fold to one expression, because we want to ignore the names of the temp vars the optimizer generates.
            assertEquals(
                CmdsFolder.fold(expected.code, xS, expected.block(0)),
                CmdsFolder.fold(result, xS, prog.block(0))
            )
        } else {
            assertSameProg(expected.code, result)
        }
    }


    @Test
    fun simpleIgnore() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(bM)
                cM assign ls(aM, 0x300, eM, 0x0)
                rM assign ls(bM, 0x0, cM, 0x60)
            },
            expectedGen = {
                havoc(aM)
                havoc(bM)
                rM assign ls(bM, 0x0, aM, 0x60)
            },
        )
    }

    @Test
    fun simpleIgnoreSameName() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(bM)
                cM assign ls(aM, 0x300, bM, 0x0)
                rM assign ls(bM, 0x0, cM, 0x40)
            },
            expectedGen = {
                havoc(aM)
                havoc(bM)
                rM assign ls(bM, 0x0, aM, 0x40)
            },
        )
    }

    @Test
    fun original() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(bM)
                havoc(fM)
                havoc(iM)
                cM assign ls(aM, 0x3a0, bM, 0x0)
                dM assign ls(bM, 0x0, cM, 0x360)
                eM assign ls(fM, 0x220, dM, 0x0)
                gM assign ls(dM, 0x0, eM, 0x40)
                hM assign ls(iM, 0x360, gM, 0x0)
                rM assign ls(gM, 0x0, hM, 0x80)
            },
            expectedGen = {
                havoc(bM)
                havoc(iM)
                rM assign ls(bM, 0x0, iM, 0x80)
            },
        )
    }

    // Test case: B := C[loc = value] where loc !in [y, y+l)
    @Test
    fun srcMapStoreDisjoint() {
        runAndCompare(
            progGen = {
                havoc(cM)
                havoc(dM)
                bM assign Store(cM.asSym(), listOf(0x50.asTACExpr), One)  // Store at 0x50
                rM assign ls(dM, 0x0, cM, 0x100, 0x40)  // Load from [0x100, 0x140)
            },
            expectedGen = {
                havoc(cM)
                havoc(dM)
                rM assign ls(dM, 0x0, cM, 0x100, 0x40)
            },
        )
    }

    // Test case: B := C[u.. = D[v..+k]] where [y, y+l) does not intersect [u, u+k)
    @Test
    fun srcMapLongstoreNoIntersection() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                bM assign ls(aM, 0x200, eM, 0x0, 0x40)  // bM has store at [0x200, 0x240)
                rM assign ls(dM, 0x0, bM, 0x100, 0x40)  // Reading from [0x100, 0x140) - no intersection
            },
            expectedGen = {
                havoc(aM)
                havoc(dM)
                rM assign ls(dM, 0x0, aM, 0x100, 0x40)
            },
        )
    }

    // Test case: B := C[u.. = D[v..+k]] where [y, y+l) is contained in [u, u+k)
    @Test
    fun srcMapLongstoreContained() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, 0x0, bM, 0x120, 0x40)  // Reading from [0x120, 0x160) - fully contained
            },
            expectedGen = {
                havoc(eM)
                havoc(dM)
                val offsetVar = bv256Var("ls_off")
                offsetVar assign 0x20  // 0x120 - 0x100 + 0x0 = 0x20
                rM assign TACExpr.LongStore(dM.asSym(), 0x0.asTACExpr, eM.asSym(), offsetVar.asSym(), 0x40.asTACExpr)
            },
            justExp = true
        )
    }

    // Test case: A := C[u.. = D[v..+k]] where [x, x+l) contains [u, u+k)
    @Test
    fun dstMapLongstoreContains() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                bM assign ls(aM, 0x120, eM, 0x0, 0x20)  // bM has store at [0x120, 0x140)
                rM assign ls(bM, 0x100, dM, 0x0, 0x80)  // Writing to [0x100, 0x180) - contains [0x120, 0x140)
            },
            expectedGen = {
                havoc(aM)
                havoc(dM)
                rM assign ls(aM, 0x100, dM, 0x0, 0x80)
            },
        )
    }


    // Test case: Different lengths to ensure length handling is correct
    @Test
    fun differentLengths() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                bM assign ls(aM, 0x0, eM, 0x0, 0x20)  // Small length
                cM assign bM
                rM assign ls(dM, 0x0, cM, 0x10, 0x10)  // Even smaller, contained
            },
            expectedGen = {
                havoc(eM)
                havoc(dM)
                rM assign ls(dM, 0x0, eM, 0x10, 0x10)
            }
        )
    }

    // Test case: Edge case - query at exact boundary (start)
    @Test
    fun boundaryStart() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                bM assign ls(aM, 0x100, eM, 0x0, 0x40)
                rM assign ls(dM, 0x0, bM, 0x100, 0x20)  // Starts at exact boundary
            },
            expectedGen = {
                havoc(eM)
                havoc(dM)
                rM assign ls(dM, 0x0, eM, 0x0, 0x20)
            },
        )
    }

    // Test case: Edge case - query at exact boundary (end)
    @Test
    fun boundaryEnd() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                bM assign ls(aM, 0x100, eM, 0x0, 0x40)
                rM assign ls(dM, 0x0, bM, 0x120, 0x20)  // Ends at exact boundary
            },
            expectedGen = {
                havoc(eM)
                havoc(dM)
                rM assign ls(dM, 0x0, eM, 0x20, 0x20)
            },
        )
    }

    @Test
    fun chainedStores1() {
        runAndCompare(
            progGen = {
                havoc(bM)
                havoc(cM)
                havoc(gM)
                dM assign ls(bM, 0x160, cM, 0x0)
                eM assign s(dM, 0x1a0, c)
                rM assign ls(gM, 0x0, eM, 0x160)
            },
            expectedGen = {
                havoc(cM)
                havoc(gM)
                rM assign ls(gM, 0x0, cM, 0x0)
            },
        )
    }

    @Test
    fun chainedStores() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(iM)
                havoc(gM)
                bM assign s(aM, 0x1c0, b)
                cM assign ls(iM, 0x0, bM, 0x1a0)
                dM assign ls(bM, 0x160, cM, 0x0)
                eM assign s(dM, 0x1a0, c)
                rM assign ls(gM, 0x0, eM, 0x160)
            },
            expectedGen = {
                havoc(aM)
                havoc(gM)
                bM assign s(aM, 0x1c0, b)
                rM assign ls(gM, 0x0, bM, 0x1a0)
            },
        )
    }


    @Test
    fun simpleIgnoreWithBypass() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(bM)
                aM assign ls(aM, 0x300, eM, 0x0)
                rM assign ls(bM, 0x0, aM, 0x60)
            },
            expectedGen = {
                havoc(aM)
                havoc(bM)
                rM assign ls(bM, 0x0, aM, 0x60)
            },
            justExp = true
        )
    }

    @Test
    fun twoDefSites() {
        val prog = TACProgramBuilder {
            havoc(iM)
            jump(1) {
                havoc(hM)
                jump(3) {
                    rM assign ls(iM, 0x200, aM, 0x120)
                    a assign rM[b]
                    x assign Eq(aS, Zero)
                    assert(x)
                }
            }
            jump(2) {
                havoc(hM)
                jump(3)
            }
        }
        val expected = TACProgramBuilder {
            havoc(iM)
            jump(1) {
                havoc(hM)
                jump(3) {
                    aM assign s(hM, 0x1a0, a)
                    rM assign ls(iM, 0x200, aM, 0x120)
                    a assign rM[b]
                    x assign Eq(aS, Zero)
                    assert(x)
                }
            }
            jump(2) {
                havoc(hM)
                jump(3)
            }
        }
        val result = BytemapInliner.go(prog.code, { false }, cheap = false)
            .let { BytemapConeOfInf.go(it, FilteringFunctions.NoFilter) }
        assertEquals(
            CmdsFolder.fold(expected.code, xS, expected.block(0)),
            CmdsFolder.fold(result, xS, prog.block(0))
        )
    }

    @Test
    fun storeAndChainedLongstores() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(bM)
                havoc(cM)
                dM assign s(aM, 0x1a0, a)
                eM assign ls(bM, 0x0, dM, 0x120)
                rM assign ls(cM, 0x200, eM, 0x0)
            },
            expectedGen = {
                havoc(aM)
                havoc(cM)
                rM assign ls(cM, 0x200, aM, 0x120)
            },
        )
    }

    // Example from shortenStoreChain documentation
    @Test
    fun shortenStoreChainExample() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(bM)
                havoc(cM)
                dM assign ls(aM, 0x100, cM, 0x50)
                rM assign ls(bM, 0x0, dM, 0x120, 0x20)
            },
            expectedGen = {
                havoc(bM)
                havoc(cM)
                rM assign ls(bM, 0x0, cM, 0x70, 0x20)
            },
        )
    }

    // Test case: Partial overlap - care region partially overlaps longstore range
    // This is the critical negative case: if isContainedIn incorrectly returns true/false
    // instead of null for a partial overlap, it would produce unsound output.
    @Test
    fun srcMapLongstorePartialOverlap() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(dM)
                bM assign ls(aM, 0x100, dM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, 0x0, bM, 0x140, 0x80)  // Reading from [0x140, 0x1C0) - extends beyond store range
            },
            expectedGen = {
                havoc(aM)
                havoc(dM)
                bM assign ls(aM, 0x100, dM, 0x0, 0x80)
                rM assign ls(dM, 0x0, bM, 0x140, 0x80)  // Should NOT be simplified
            },
        )
    }

    // SYMBOLIC OFFSET TESTS
    // These tests exercise the diff(t1, t2) interval computation and isNonNeg/isNonPos
    // properties in the non-constant case within TermFactory.isContainedIn.
    //
    // Note: When isContainedIn returns null (unknown), BytemapInliner conservatively skips optimization.
    // However, BytemapConeOfInf may still remove dead definitions. These tests verify the conservative
    // behavior - that when containment/disjointness cannot be proven, the store relationships are preserved.

    // Test case: Symbolic offset where containment cannot be proven (no constraints)
    // BytemapInliner cannot determine if [b, b+0x40) intersects [0x100, 0x180), so it skips.
    @Test
    fun symbolicOffsetUnconstrainedNoOptimization() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(b)
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, Zero.asSym, bM, b, 0x40.asTACSymbol())  // Reading from [b, b+0x40)
            },
            expectedGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(b)
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, Zero.asSym, bM, b, 0x40.asTACSymbol())  // Reading from [b, b+0x40)
            },
        )
    }

    // Test case: Symbolic length where containment cannot be proven (no constraints)
    // Since the length is unknown, the optimizer cannot determine if the range is contained.
    @Test
    fun symbolicLengthUnconstrainedNoOptimization() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(b)
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, Zero, bM, 0x100.asTACExpr, bS)  // Reading [0x100, 0x100+b)
            },
            expectedGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(b)
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, Zero, bM, 0x100.asTACExpr, bS)  // Reading [0x100, 0x100+b)
            },
            justExp = true
        )
    }

    // Test case: Both symbolic offset and length where optimization is unsafe
    @Test
    fun symbolicOffsetAndLengthUnconstrained() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(b)
                havoc(c)
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, Zero, bM, bS, cS)  // Reading from [b, b+c)
            },
            expectedGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(b)
                havoc(c)
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, Zero, bM, bS, cS)  // Reading from [b, b+c)
            },
        )
    }

    // Test case: Symbolic offset in dst position (care region has symbolic offset)
    // The optimizer can still optimize when the read offset from the src is within known bounds
    @Test
    fun symbolicDstOffsetNoOptimization() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(b)
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, bS, bM, 0x120.asTACExpr, 0x40.asTACExpr)  // Writing to [b, b+0x40) from offset 0x120
            },
            expectedGen = {
                havoc(eM)
                havoc(b)
                // The read [0x120, 0x160) is fully contained in [0x100, 0x180), so it can be optimized
                // The adjusted offset is 0x120 - 0x100 + 0x0 = 0x20
                rM assign ls(dM, bS, eM, 0x20.asTACExpr, 0x40.asTACExpr)
            },
            justExp = true
        )
    }

    // Test case: Mixed symbolic and constant - src offset symbolic, dst and length constant
    // When the read range is fully contained in the store range, optimizer can compute adjusted offset
    @Test
    fun symbolicSrcOffsetMixedOptimization() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(b)
                bM assign ls(aM, 0x100.asTACExpr, eM, bS, 0x80.asTACExpr)  // bM has store at [0x100, 0x180) from src[b, b+0x80)
                rM assign ls(dM, 0x0, bM, 0x120, 0x40)  // Reading from [0x120, 0x160) - fully contained
            },
            expectedGen = {
                havoc(eM)
                havoc(dM)
                havoc(b)
                // [0x120, 0x160) ⊆ [0x100, 0x180), so adjusted offset is b + (0x120 - 0x100) = b + 0x20
                val offsetVar = bv256Var("offset")
                offsetVar assign Add(bS, 0x20.asTACExpr)
                rM assign ls(dM, Zero, eM, offsetVar.asSym(), 0x40.asTACExpr)
            },
            justExp = true
        )
    }

    // Test case: Symbolic offset with interval constraints - no shortening possible
    // The symbolic offset c is constrained to [0x200, 0x240), which does NOT intersect [0x100, 0x180)
    // Even though both ranges are symbolic with respect to c, interval analysis proves they're disjoint
    @Test
    fun symbolicOffsetDisjointWithIntervals() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(c)
                // Constrain c to be in range [0x200, 0x240)
                assumeExp(LAnd(Ge(cS, 0x200.asTACExpr), Lt(cS, 0x240.asTACExpr)))
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, Zero, bM, cS, 0x40.asTACExpr)  // Reading from [c, c+0x40), c in [0x200, 0x240)
            },
            expectedGen = {
                havoc(aM)
                havoc(dM)
                havoc(c)
                assumeExp(LAnd(Ge(cS, 0x200.asTACExpr), Lt(cS, 0x240.asTACExpr)))
                // Interval analysis proves [c, c+0x40) ∩ [0x100, 0x180) = ∅, so skip bM's store
                rM assign ls(dM, Zero, aM, cS, 0x40.asTACExpr)
            },
        )
    }

    // Test case: Symbolic offset with interval constraints - src shortening (reading from the store)
    // The symbolic offset c is constrained so [c, c+0x40) is fully contained in [0x100, 0x180)
    // Interval analysis proves containment, enabling src shortening
    @Test
    fun symbolicOffsetContainedSrcShortening() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(c)
                // Constrain c so that [c, c+0x40) ⊆ [0x100, 0x180)
                // Need: c >= 0x100 AND c + 0x40 <= 0x180, i.e., c <= 0x140
                assumeExp(LAnd(Ge(cS, 0x100.asTACExpr), Le(cS, 0x140.asTACExpr)))
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(dM, Zero, bM, cS, 0x40.asTACExpr)  // Reading from [c, c+0x40), c in [0x100, 0x140]
            },
            expectedGen = {
                havoc(eM)
                havoc(dM)
                havoc(c)
                assumeExp(LAnd(Ge(cS, 0x100.asTACExpr), Le(cS, 0x140.asTACExpr)))
                // [c, c+0x40) ⊆ [0x100, 0x180), so read from eM at adjusted offset
                val offsetVar = bv256Var("offset")
                offsetVar assign Sub(cS, 0x100.asTACExpr)  // c - 0x100
                rM assign ls(dM, Zero, eM, offsetVar.asSym(), 0x40.asTACExpr)
            },
            justExp = true
        )
    }

    // Test case: Symbolic offset with interval constraints - dst shortening (overwriting the store)
    // The longstore's destination [dstOff, dstOff+len) fully contains the previous store [0x100, 0x180)
    // so the previous store can be ignored
    @Test
    fun symbolicOffsetContainedDstShortening() {
        runAndCompare(
            progGen = {
                havoc(aM)
                havoc(eM)
                havoc(dM)
                havoc(c)
                // Constrain c so that the new longstore [c, c+0x100) contains [0x100, 0x180)
                // Need: c <= 0x100 AND c + 0x100 >= 0x180, i.e., c >= 0x80
                assumeExp(LAnd(Ge(cS, 0x80.asTACExpr), Le(cS, 0x100.asTACExpr)))
                bM assign ls(aM, 0x100, eM, 0x0, 0x80)  // bM has store at [0x100, 0x180)
                rM assign ls(bM, cS, dM, Zero, 0x100.asTACExpr)  // Writing [c, c+0x100) from dM[0, 0x100)
            },
            expectedGen = {
                havoc(aM)
                havoc(dM)
                havoc(c)
                assumeExp(LAnd(Ge(cS, 0x80.asTACExpr), Le(cS, 0x100.asTACExpr)))
                // [0x100, 0x180) ⊆ [c, c+0x100), so the previous store is overwritten
                rM assign ls(aM, cS, dM, Zero, 0x100.asTACExpr)
            },
        )
    }

}
