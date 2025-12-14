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

package wasm.analysis

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import wasm.WasmEntryPoint.wasmToTAC
import wasm.WasmTestFixture
import wasm.analysis.memory.IMemoryPartitions.*
import wasm.analysis.memory.IMemoryPartitions.Permission.*
import wasm.certoraAssert
import wasm.host.NullHost
import wasm.analysis.memory.MemoryPartitionAnalysis
import wasm.wat.*
import java.math.BigInteger

class MemoryPermissionAnalysisTest: WasmTestFixture() {
    override val host = NullHost

    private val MAX_ADDR: Long = 1L shl 32

    private fun analyzePermissions(entry: String, f: WatModuleBuilder.() -> Unit): MemoryPartitionAnalysis {
        val wasmFile = watToWasm(watModule {
            memory("default", 65536)
            f()
        })
        val tac = wasmToTAC(wasmFile.toFile(), setOf(entry), NullHost, optimize = false)
            .single()
            .let { it as CompiledGenericRule.Compiled }.code

        return tac.analysisCache[MemoryPartitionAnalysis]
    }

    private fun MemoryPartitionAnalysis.assertPerm(lo: Long, hi: Long, expect: Permission) =
        assertPerm(BigInteger.valueOf(lo), BigInteger.valueOf(hi), expect)

    private fun MemoryPartitionAnalysis.assertPerm(lo: BigInteger, hi: BigInteger, expect: Permission) {
        Assertions.assertEquals(expect, permission(lo, hi))
    }

    @Test
    fun oneRegionRO() {
        val perm = analyzePermissions("entry") {
            func(exportName = "entry", r = i32).implement {
                certoraAssert(i32(1))
                returnResult(i32.load(i32(100)))
            }
        }

        perm.assertPerm(0, MAX_ADDR, ReadOnly)
    }

    @Test
    fun oneWrite() {
        val perm = analyzePermissions("entry") {
            func(exportName = "entry").implement {
                i32.store(i32(100), i32(1))
                certoraAssert(i32(1))
            }
        }

        perm.assertPerm(0, MAX_ADDR, ReadWrite)
        perm.assertPerm(0, 99, ReadOnly)
        perm.assertPerm(104, MAX_ADDR, ReadOnly)
    }

    @Test
    fun twoWrites() {
        val perm = analyzePermissions("entry") {
            func(exportName = "entry", r = i32).implement {
                val r = i32.load(i32(20))
                i32.store(i32(104), i32(1))
                i32.store(i32(108), i32(2))
                certoraAssert(i32(1))
                returnResult(r)
            }
        }

        perm.assertPerm(0, MAX_ADDR, ReadWrite)
        perm.assertPerm(100, MAX_ADDR, ReadWrite)
        perm.assertPerm(0, 103, ReadOnly)
        perm.assertPerm(104, 107, ReadWrite)
        perm.assertPerm(108, 111, ReadWrite)
        perm.assertPerm(104, 111, ReadWrite)
        perm.assertPerm(112, MAX_ADDR, ReadOnly)
    }

    @Test
    fun twoWrites64() {
        val perm = analyzePermissions("entry") {
            func(exportName = "entry", r = i32).implement {
                val r = i32.load(i32(20))
                i64.store(i32(104), i64(1))
                i64.store(i32(112), i64(2))
                certoraAssert(i32(1))
                returnResult(r)
            }
        }

        perm.assertPerm(0, MAX_ADDR, ReadWrite)
        perm.assertPerm(100, MAX_ADDR, ReadWrite)
        perm.assertPerm(0, 103, ReadOnly)
        perm.assertPerm(104, 111, ReadWrite)
        perm.assertPerm(107, 119, ReadWrite)
        perm.assertPerm(120, MAX_ADDR, ReadOnly)
    }

    @Test
    fun branch() {
        val perm = analyzePermissions("entry") {
            func(exportName = "entry", r = i32).implement {
                val r = i32.load(i32(0))
                loop {
                    block {
                        i32.store(i32(100), i32(0))
                        branchIf(i32.load(i32(200)))
                        i64.store(i32(200), i64(0))
                    }
                }
                i64.store(i32(300), i64(0))
                block {
                    branchIf(i32.load(i32(300)))
                    i64.store(i32(308), i64(0))
                }
                certoraAssert(i32(1))
                returnResult(r)
            }
        }
        perm.apply {
            assertPerm(0, 99, ReadOnly)
            assertPerm(100, 104, ReadWrite)
            assertPerm(105, 199, ReadOnly)
            assertPerm(200, 207, ReadWrite)
            assertPerm(208, 299, ReadOnly)
            assertPerm(300, 307, ReadWrite)
            assertPerm(308, 315, ReadWrite)
            assertPerm(316, MAX_ADDR, ReadOnly)
        }
    }


}
