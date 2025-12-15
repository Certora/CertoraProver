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

package sbf.disassembler

import net.fornwall.jelf.ElfException
import net.fornwall.jelf.ElfFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * This code is part of net.fornwall.jelf.
 * We need to have access to the parser to read the data of a section but the parser (ElfParser) is a private class.
 **/

interface BackingFile {
    fun seek(offset: Long)
    fun skip(bytesToSkip: Int)
    fun readUnsignedByte(): Short
    fun read(data: ByteArray): Int
}
internal class ByteArrayAsFile(private val byteArray: ByteArrayInputStream) : BackingFile {
    constructor(buffer: ByteArray) : this(ByteArrayInputStream(buffer))

    override fun seek(offset: Long) {
        byteArray.reset()
        if (byteArray.skip(offset) != offset) {
            throw ElfException("seeking outside file")
        }
    }

    override fun skip(bytesToSkip: Int) {
        val skipped = byteArray.skip(bytesToSkip.toLong())
        require(skipped == bytesToSkip.toLong()) {
            "Wanted to skip $bytesToSkip bytes, but only able to skip $skipped" }
    }

    override fun readUnsignedByte(): Short {
        val value = byteArray.read()
        if (value < 0) {
            throw ElfException("Trying to read outside file")
        }
        return value.toShort()
    }

    @Suppress("TooGenericExceptionThrown")
    override fun read(data: ByteArray): Int {
        return try {
            byteArray.read(data)
        } catch (e: IOException) {
            throw RuntimeException("Error reading " + data.size + " bytes", e)
        }
    }
}

@Throws(ElfException::class, IOException::class)
fun getBackingFile(file: File): BackingFile {
    val buffer = ByteArray(file.length().toInt())
    FileInputStream(file).use { `in` ->
        var totalRead = 0
        while (totalRead < buffer.size) {
            val readNow = `in`.read(buffer, totalRead, buffer.size - totalRead)
            totalRead += if (readNow == -1) {
                throw ElfException("Premature end of file")
            } else {
                readNow
            }
        }
    }
    return ByteArrayAsFile(buffer)
}

class ElfParser(val file: File, private val elfFile: ElfFile) {
    private val backingFile: BackingFile = getBackingFile(file)

    fun seek(offset: Long) {
        backingFile.seek(offset)
    }

    fun skip(bytesToSkip: Int) {
        backingFile.skip(bytesToSkip)
    }

    fun readUnsignedByte(): Short {
        return backingFile.readUnsignedByte()
    }

    fun readShort(): Short {
        val ch1 = readUnsignedByte().toInt()
        val ch2 = readUnsignedByte().toInt()
        return if (elfFile.ei_data == ElfFile.DATA_LSB) {
            ((ch2 and 0xff shl 8) or (ch1 and 0xff)).toShort()
        } else {
            ((ch1 and 0xff shl 8) or (ch2 and 0xff)).toShort()
        }
    }

    @Throws(ElfException::class)
    fun readInt(): Int {
        val ch1 = readUnsignedByte().toInt()
        val ch2 = readUnsignedByte().toInt()
        val ch3 = readUnsignedByte().toInt()
        val ch4 = readUnsignedByte().toInt()
        return if (elfFile.ei_data == ElfFile.DATA_LSB) {
            ch4 and 0xff shl 24 or (ch3 and 0xff shl 16) or (ch2 and 0xff shl 8) or (ch1 and 0xff)
        } else {
            ch1 and 0xff shl 24 or (ch2 and 0xff shl 16) or (ch3 and 0xff shl 8) or (ch4 and 0xff)
        }
    }

    fun readLong(): Long {
        val ch1 = readUnsignedByte().toInt()
        val ch2 = readUnsignedByte().toInt()
        val ch3 = readUnsignedByte().toInt()
        val ch4 = readUnsignedByte().toInt()
        val ch5 = readUnsignedByte().toInt()
        val ch6 = readUnsignedByte().toInt()
        val ch7 = readUnsignedByte().toInt()
        val ch8 = readUnsignedByte().toInt()
        return if (elfFile.ei_data == ElfFile.DATA_LSB) {
            ch8.toLong() shl 56 or (ch7.toLong() and 0xff shl 48) or (ch6.toLong() and 0xff shl 40
                    ) or (ch5.toLong() and 0xff shl 32) or (ch4.toLong() and 0xff shl 24) or (ch3.toLong() and 0xff shl 16
                    ) or (ch2.toLong() and 0xff shl 8) or (ch1.toLong() and 0xff)
        } else {
            ch1.toLong() shl 56 or (ch2.toLong() and 0xff shl 48) or (ch3.toLong() and 0xff shl 40
                    ) or (ch4.toLong() and 0xff shl 32) or (ch5.toLong() and 0xff shl 24) or (ch6.toLong() and 0xff shl 16
                    ) or (ch7.toLong() and 0xff shl 8) or (ch8.toLong() and 0xff)
        }
    }



    fun read(data: ByteArray): Int {
        return backingFile.read(data)
    }
}
