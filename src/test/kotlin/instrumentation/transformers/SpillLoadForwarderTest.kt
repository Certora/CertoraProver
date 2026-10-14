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

package instrumentation.transformers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.TACBuilderAuxiliaries
import vc.data.TACCmd
import vc.data.TACSymbol
import vc.data.asTACSymbol
import vc.data.TACProgramBuilder
import vc.data.TACProgramBuilder.BuiltTACProgram
import vc.data.TACProgramBuilder.Companion.testProgString

class SpillLoadForwarderTest : TACBuilderAuxiliaries() {

    /** the snapshot variables the pass generates, in creation order, for cells 0x120 and 0x140 */
    private val fwd = TACSymbol.Var("spillFwd!0!0x120", Tag.Bit256)
    private val fwd1 = TACSymbol.Var("spillFwd!1!0x140", Tag.Bit256)

    private fun runAndCompare(original: BuiltTACProgram, expected: BuiltTACProgram) {
        val newProg = SpillLoadForwarder.rewrite(original.code)
        assertEquals(testProgString(expected.code), testProgString(newProg))
    }

    @Test
    fun simpleForward() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            b assign bMap1[0x120]
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            b assign fwd
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun forwardAcrossBlocks() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            jump {
                b assign bMap1[0x120]
                assert(x)
            }
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            jump {
                b assign fwd
                assert(x)
            }
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun loadsShareOneSnapshot() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            b assign bMap1[0x120]
            d assign bMap1[0x120]
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            b assign fwd
            d assign fwd
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun distinctCellsGetDistinctSnapshots() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            bMap1[0x140] assign c
            b assign bMap1[0x120]
            d assign bMap1[0x140]
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            fwd1 assign c
            bMap1[0x140] assign c
            b assign fwd
            d assign fwd1
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun forwardAtAgreeingJoin() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            jumpCond(x)
            jump {
                c assign 1
                jump(1)
            }
            jump {
                c assign 2
                jump(1)
            }
            jumpDest(1) {
                b assign bMap1[0x120]
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            jumpCond(x)
            jump {
                c assign 1
                jump(1)
            }
            jump {
                c assign 2
                jump(1)
            }
            jumpDest(1) {
                b assign fwd
                assert(y)
            }
        }
        runAndCompare(orig, expected)
    }

    /** the branches store different values, but snapshotting at the stores handles that */
    @Test
    fun forwardAtDisagreeingJoin() {
        val orig = TACProgramBuilder {
            jumpCond(x)
            jump {
                bMap1[0x120] assign a
                jump(1)
            }
            jump {
                bMap1[0x120] assign c
                jump(1)
            }
            jumpDest(1) {
                b assign bMap1[0x120]
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            jumpCond(x)
            jump {
                fwd assign a
                bMap1[0x120] assign a
                jump(1)
            }
            jump {
                fwd assign c
                bMap1[0x120] assign c
                jump(1)
            }
            jumpDest(1) {
                b assign fwd
                assert(y)
            }
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun killBySymbolicStore() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            bMap1[d] assign c
            b assign bMap1[0x120]
            assert(x)
        }
        runAndCompare(orig, orig)
    }

    @Test
    fun killByOverlappingStore() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            bMap1[0x110] assign c
            b assign bMap1[0x120]
            assert(x)
        }
        runAndCompare(orig, orig)
    }

    @Test
    fun nonOverlappingStoreDoesNotKill() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            bMap1[0x140] assign c
            b assign bMap1[0x120]
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            bMap1[0x140] assign c
            b assign fwd
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    /** the snapshot is taken at the store, so reassigning the stored symbol changes nothing */
    @Test
    fun valueReassignmentDoesNotKill() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            a assign 7
            b assign bMap1[0x120]
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            a assign 7
            b assign fwd
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    /** every store to a forwarded cell is snapshotted, even one no load can see */
    @Test
    fun allCellStoresGetSnapshots() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            b assign bMap1[0x120]
            bMap1[d] assign c
            bMap1[0x120] assign e
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            b assign fwd
            bMap1[d] assign c
            fwd assign e
            bMap1[0x120] assign e
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun killByMapHavoc() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            havoc(bMap1)
            b assign bMap1[0x120]
            assert(x)
        }
        runAndCompare(orig, orig)
    }

    @Test
    fun otherMapDoesNotKill() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            bMap2[d] assign c
            b assign bMap1[0x120]
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            bMap2[d] assign c
            b assign fwd
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun constantLongCopyKillsOnlyOverlap() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            addCmd(TACCmd.Simple.ByteLongCopy(
                dstOffset = 0x200.asTACSymbol(),
                srcOffset = 0x0.asTACSymbol(),
                length = 0x40.asTACSymbol(),
                dstBase = bMap1,
                srcBase = bMap2,
            ))
            b assign bMap1[0x120]
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            addCmd(TACCmd.Simple.ByteLongCopy(
                dstOffset = 0x200.asTACSymbol(),
                srcOffset = 0x0.asTACSymbol(),
                length = 0x40.asTACSymbol(),
                dstBase = bMap1,
                srcBase = bMap2,
            ))
            b assign fwd
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun symbolicLongCopyKills() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            addCmd(TACCmd.Simple.ByteLongCopy(
                dstOffset = d,
                srcOffset = 0x0.asTACSymbol(),
                length = 0x40.asTACSymbol(),
                dstBase = bMap1,
                srcBase = bMap2,
            ))
            b assign bMap1[0x120]
            assert(x)
        }
        runAndCompare(orig, orig)
    }

    /** a zero-length copy writes nothing, even at a symbolic offset */
    @Test
    fun zeroLengthCopyDoesNotKill() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            addCmd(TACCmd.Simple.ByteLongCopy(
                dstOffset = d,
                srcOffset = 0x0.asTACSymbol(),
                length = 0x0.asTACSymbol(),
                dstBase = bMap1,
                srcBase = bMap2,
            ))
            b assign bMap1[0x120]
            assert(x)
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            addCmd(TACCmd.Simple.ByteLongCopy(
                dstOffset = d,
                srcOffset = 0x0.asTACSymbol(),
                length = 0x0.asTACSymbol(),
                dstBase = bMap1,
                srcBase = bMap2,
            ))
            b assign fwd
            assert(x)
        }
        runAndCompare(orig, expected)
    }

    @Test
    fun killByLoopWrite() {
        // the store reaches the loop head on the entry edge, but the back edge carries
        // a symbolic store, so the load inside the loop must not be forwarded
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            jump(1) {
                b assign bMap1[0x120]
                bMap1[d] assign c
                jumpCond(x)
                jump(1)
                jump(2) {
                    assert(y)
                }
            }
        }
        runAndCompare(orig, orig)
    }

    @Test
    fun forwardThroughLoopWithoutWrites() {
        val orig = TACProgramBuilder {
            bMap1[0x120] assign a
            jump(1) {
                b assign bMap1[0x120]
                jumpCond(x)
                jump(1)
                jump(2) {
                    assert(y)
                }
            }
        }
        val expected = TACProgramBuilder {
            fwd assign a
            bMap1[0x120] assign a
            jump(1) {
                b assign fwd
                jumpCond(x)
                jump(1)
                jump(2) {
                    assert(y)
                }
            }
        }
        runAndCompare(orig, expected)
    }

    /** a store inside the loop reaches the load both directly and around the back edge */
    @Test
    fun forwardAtLoopStore() {
        val orig = TACProgramBuilder {
            jump(1) {
                bMap1[0x120] assign a
                b assign bMap1[0x120]
                jumpCond(x)
                jump(1)
                jump(2) {
                    assert(y)
                }
            }
        }
        val expected = TACProgramBuilder {
            jump(1) {
                fwd assign a
                bMap1[0x120] assign a
                b assign fwd
                jumpCond(x)
                jump(1)
                jump(2) {
                    assert(y)
                }
            }
        }
        runAndCompare(orig, expected)
    }
}
