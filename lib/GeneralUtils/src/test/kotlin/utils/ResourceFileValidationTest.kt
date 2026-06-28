/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

package utils

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

internal class ResourceFileValidationTest {

    @TempDir
    lateinit var tmp: Path

    private fun fileWith(name: String, bytes: ByteArray): File =
        tmp.resolve(name).toFile().apply { writeBytes(bytes) }

    @Test
    fun acceptsPlainTextJson() {
        val f = fileWith("ok.json", """{"trusted":["0xa9059cbb"]}""".toByteArray())
        assertNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun acceptsUnicodeText() {
        val f = fileWith("u.txt", "rule sanity ✓ — λ\n".toByteArray())
        assertNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsExecutableBit() {
        val f = fileWith("text-but-exec", "plain text".toByteArray())
        assertTrue(f.setExecutable(true))
        assertNotNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsElf() {
        val f = fileWith("a.bin", byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01))
        assertNotNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsZipOrJar() {
        val f = fileWith("a.jar", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        assertNotNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsMachO() {
        val f = fileWith("a.macho", byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()))
        assertNotNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsPeExecutable() {
        val f = fileWith("a.exe", byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00))
        assertNotNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsShebangScript() {
        val f = fileWith("s.sh", "#!/bin/sh\necho hi\n".toByteArray())
        assertNotNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsNulBytes() {
        val f = fileWith("nul.bin", byteArrayOf(0x61, 0x00, 0x62))
        assertNotNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsInvalidUtf8() {
        // 0x80 is a lone UTF-8 continuation byte: not valid text, not a known magic, no NUL.
        val f = fileWith("bad.txt", byteArrayOf(0x80.toByte()))
        assertNotNull(ResourceFileValidation.disallowedResourceReason(f))
    }

    @Test
    fun rejectsRealExecutable() {
        // A real native executable on disk (the running JRE's own java binary) must be rejected.
        val java = File(System.getProperty("java.home"), "bin/java")
        assertTrue(java.isFile, "expected the JRE java binary at $java")
        assertNotNull(ResourceFileValidation.disallowedResourceReason(java))
    }
}
