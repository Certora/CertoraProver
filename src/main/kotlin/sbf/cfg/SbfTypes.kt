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

package sbf.cfg

import sbf.SolanaConfig
import sbf.disassembler.SbfGlobalVariable
import sbf.domains.ConstantSet

/**
 * A SBF (Solana Binary Format) program has access to these four pairwise disjoint regions:
 * - Input (aka "Context" in Linux eBPF): solana accounts
 * - Stack frame: fixed-size, byte-addressable array of 4 KB
 * - Heap: a byte-addressable region for dynamic memory designated between [0x300000000, 0x3000077f8]
 * - Global: global variables (e.g., constant strings)
 *
 * A pointer is a pair of a memory region (input, stack, heap or global) and an offset
 *
 * A SBF program has also access to 11 registers: r0, r1, ..., r10.
 * A register can store either a number or a pointer to any of the above memory regions.
 * Initially, r10 points to the end of the stack.
 * The register r10 is read-only.
 *
 *  In SBF, numbers and pointers are indistinguishable so there are implicit casts
 *  from numbers to pointers, and vice-versa.
 **/

const val SOLANA_ACCOUNT_SIZE = 10 * 1024 * 1024
const val MAX_SOLANA_ACCOUNTS = 50

const val NUM_OF_SBF_REGISTERS = 11

// -- Actual SVM parameters
// In SVM, each memory region must be 1 << 32 bytes apart, where 32 is the number of bits of a virtual address.
// The code region includes the bytecode and the .rodata section
const val SBF_CODE_START = 0x100_000_000
val SBF_STACK_FRAME_SIZE = SolanaConfig.StackFrameSize.get().toLong()
const val SBF_STACK_START = 0x200_000_000
const val SBF_INPUT_START = 0x400_000_000
const val SBF_HEAP_SIZE = 32_768
const val SBF_HEAP_START = 0x300_000_000

// -- Parameters needed for TAC encoding
const val SBF_HEAP_END = SBF_HEAP_START + SBF_HEAP_SIZE
// In SVM, there is no explicit limit for the input area, but probably, it's the last addressable virtual address.
// For TAC encoding purposes, we need to choose an end. We don't really care the exact value.
const val SBF_INPUT_END = SBF_INPUT_START + (2 * MAX_SOLANA_ACCOUNTS * SOLANA_ACCOUNT_SIZE)
// This is not an actual SVM memory region, but we needed for TAC encoding purposes.
// We have split the input memory region into two parts: the first half for solana accounts and the second half for external allocations.
const val SBF_EXTERNAL_START = SBF_INPUT_START + (MAX_SOLANA_ACCOUNTS * SOLANA_ACCOUNT_SIZE)



sealed class SbfRegisterType {
    data class NumType(val value: ConstantSet) : SbfRegisterType() {
        override fun toString() = if (value.isTop()) {
            "num"
        } else {
            "num($value)"
        }
    }

    sealed class PointerType : SbfRegisterType() {
        abstract val offset: ConstantSet

        data class Stack(override val offset: ConstantSet) : PointerType() {
            override fun toString() = "sp($offset)"
        }

        data class Input(override val offset: ConstantSet) : PointerType() {
            override fun toString() = "input($offset)"
        }

        data class Heap(override val offset: ConstantSet) : PointerType() {
            override fun toString() = "heap($offset)"
        }

        // global.address is the start address of the global variable.
        // offset is actually an absolute address between [global.address, global.address+size)
        data class Global(override val offset: ConstantSet, val global: SbfGlobalVariable?) : PointerType() {
            override fun toString(): String {
                return if (global != null) {
                    val str = global.strValue
                    if (str != null) {
                        "global($str)"
                    } else {
                        "global(${global.name}, $offset)"
                    }
                } else {
                    "global($offset)"
                }
            }
        }
    }
}
