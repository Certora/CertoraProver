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
import verifier.EncodedRule
import wasm.WasmEntryPoint
import wasm.WasmTestFixture
import wasm.analysis.memory.StaticMemoryAnalysis
import wasm.certoraAssert
import wasm.host.NullHost
import wasm.wat.*
import java.math.BigInteger

class StaticMemoryAnalysisTest: WasmTestFixture() {
    override val host = NullHost

    private fun analyzeStaticMemory(entry: String, f: WatModuleBuilder.() -> Unit): StaticMemoryAnalysis {
        val wasmFile = watToWasm(watModule {
            memory("default", 65536)
            f()
        })
        val tac = WasmEntryPoint
            .wasmToTAC(wasmFile.toFile(), setOf(entry), NullHost, optimize = false)
            .single()
            .let { it.getOrNull() ?: error("wasmToTAC did not produce a rule as expected") }
            .code
        return tac.analysisCache[StaticMemoryAnalysis]
    }

    val data = "ABCDEFG\u0001\u0002\u0003\u0004HIJKLMNOP"

    @Test
    fun noWrites() {
        val mem = analyzeStaticMemory("entry") {
            dataSegment("data", 10, data)
            func(exportName = "entry").implement {
                i32.load(i32(100))
                certoraAssert(i32(1))
            }
        }

        Assertions.assertEquals(
            data.toByteArray().toList().map { it.toUByte() },
            mem.readBytes(10.toBigInteger(), data.length)
        )

        Assertions.assertEquals(
            listOf<UByte>(1U, 2U),
            mem.readBytes(17.toBigInteger(), 2)
        )

        Assertions.assertEquals(
            0x04030201.toBigInteger(),
            mem.readLittleEndian32BitWord(BigInteger.valueOf(17))
        )

        Assertions.assertEquals( null,
            mem.readBytes(10.toBigInteger(), data.length + 1)
        )

        Assertions.assertEquals( null,
            mem.readBytes(9.toBigInteger(), data.length)
        )
    }


    @Test
    fun someWrites() {
        val mem = analyzeStaticMemory("entry") {
            dataSegment("data", 10, data)
            // These globals are here because the implementation of the static
            // memory analysis depends on them to identify ro/rw regions.
            // If this becomes more precise, then the test may need updating
            global("g1", i32, i32(10))
            global("g2", i32, i32(14))
            func(exportName = "entry").implement {
                i32.store(i32(10), i32(100))
                certoraAssert(i32(1))
            }
        }

        Assertions.assertEquals(
            null,
            mem.readBytes(10.toBigInteger(), data.length)
        )

        for (i in 10..13) {
            Assertions.assertEquals(
                null,
                mem.readLittleEndian32BitWord(i.toBigInteger())
            )
        }

        Assertions.assertEquals(
            listOf<UByte>(1U, 2U),
            mem.readBytes(17.toBigInteger(), 2)
        )

        Assertions.assertEquals(
            0x04030201.toBigInteger(),
            mem.readLittleEndian32BitWord(BigInteger.valueOf(17))
        )

        Assertions.assertEquals( null,
            mem.readBytes(10.toBigInteger(), data.length + 1)
        )

        Assertions.assertEquals( null,
            mem.readBytes(9.toBigInteger(), data.length)
        )
    }
}
