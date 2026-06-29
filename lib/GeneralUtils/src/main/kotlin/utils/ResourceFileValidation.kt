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

import datastructures.stdcollections.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Validation that a prover resource file (see `Config.ResourceFiles`) is a benign **text** file.
 *
 * Resource files are only ever expected to be text (JSON lists, filters, etc.). This rejects files
 * that are executable or that look like a binary/executable format, so resource files cannot be
 * used to carry executables, archives or scripts into the run.
 */
object ResourceFileValidation {

    // Leading magic bytes of disallowed binary / executable formats.
    private val ELF = byteArrayOf(0x7F, 0x45, 0x4C, 0x46) // \x7fELF
    private val PE = byteArrayOf(0x4D, 0x5A) // "MZ" (DOS/PE)
    private val ZIP = byteArrayOf(0x50, 0x4B) // "PK" (zip/jar)
    private val SHEBANG = byteArrayOf(0x23, 0x21) // "#!"
    private val MACH_O = listOf(
        byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()), // 32-bit BE
        byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCF.toByte()), // 64-bit BE
        byteArrayOf(0xCE.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()), // 32-bit LE
        byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()), // 64-bit LE
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), // universal/fat (also a Java .class)
    )

    /**
     * @return a human-readable reason that [file] is not an acceptable text resource, or `null` if
     * it is a benign text file. Checks the executable bit, known binary/executable magic, a leading
     * `#!` shebang, NUL bytes, and that the whole content is valid UTF-8.
     */
    fun disallowedResourceReason(file: File): String? {
        if (file.canExecute()) {
            return "it is executable (file mode); resource files must be non-executable text"
        }
        val bytes = file.readBytes()
        fun startsWith(magic: ByteArray) = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
        when {
            startsWith(ELF) -> return "it is an ELF binary"
            startsWith(PE) -> return "it is a PE/DOS executable"
            startsWith(ZIP) -> return "it is a ZIP/JAR archive"
            startsWith(SHEBANG) -> return "it is a script (starts with \"#!\")"
            MACH_O.any { startsWith(it) } -> return "it is a Mach-O binary"
        }
        if (bytes.any { it == 0.toByte() }) {
            return "it contains NUL bytes (binary content, not text)"
        }
        if (!isValidUtf8(bytes)) {
            return "it is not valid UTF-8 text"
        }
        return null
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // UTF-8 never produces more chars than input bytes, so this output buffer is large enough.
        val out = CharBuffer.allocate(bytes.size + 1)
        val result = decoder.decode(ByteBuffer.wrap(bytes), out, true)
        return !result.isError && !decoder.flush(out).isError
    }
}
