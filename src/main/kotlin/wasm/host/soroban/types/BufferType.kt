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

package wasm.host.soroban.types

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import compiler.applyKeccak
import datastructures.stdcollections.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import wasm.WasmPipelinePhase
import wasm.WasmPostUnrollSummary
import wasm.analysis.memory.*

const val BYTE_BITS = 8
const val BIT256_BITS = 256
const val BIT256_BYTES = BIT256_BITS / BYTE_BITS

/**
    Buffers are [ArrayType]s whose values are bytes.  Buffers can be copied into and out of linear memory.
 */
@KSerializable
abstract class BufferType : ArrayType() {
    /**
        Creates a new buffer from a big-endian encoding of a 256-bit integer
     */
    fun newFromBit256(newHandle: TACSymbol.Var, v: TACSymbol) =
        mergeMany(
            new(newHandle) { BIT256_BYTES.asTACExpr },
            defineMapping(newHandle) { queryIndex ->
                // Note that the non-Int div/mod/pow operations currently don't work from within a MapDefinition. So we
                // do everything in terms of Int operations, and narrow at the end.
                safeMathNarrow(
                    v.asSym().intDiv(
                        BIT256_BITS.asTACExpr intPow ((BIT256_BYTES-1).asTACExpr intSub queryIndex)
                    ).intMod(BIT256_BITS.asTACExpr),
                    Tag.Bit256
                )
            }
        )

    /**
        Creates a new buffer from a list of known bytes
     */
    open fun newFromBytes(newHandle: TACSymbol.Var, bytes: List<UByte>) =
        mergeMany(
            new(
                newHandle,
                contentSummary = { listOf(applyKeccak(bytes).asTACExpr) },
                size = { bytes.size.asTACExpr }
            ),
            defineMapping(newHandle) { queryIndex ->
                bytes.foldIndexed(unconstrained(Tag.Bit256) as TACExpr) { i, acc, b ->
                    ite(
                        queryIndex eq i.asTACExpr,
                        b.toInt().asTACExpr,
                        acc
                    )
                }
            }
        )

    /**
        Creates a new buffer from the contents of linear memory starting at [pos], with length [len].
     */
    fun newFromMemory(
        newHandle: TACSymbol.Var,
        pos: TACSymbol,
        len: TACSymbol
    ) = DefineNewBufferFromMemory(this, newHandle, pos, len).toCmd()

    @KSerializable
    private data class DefineNewBufferFromMemory(
        val type: BufferType,
        val newHandle: TACSymbol.Var,
        val pos: TACSymbol,
        val len: TACSymbol
    ) : WasmPostUnrollSummary(WasmPipelinePhase.PreOptimization) {
        override val inputs get() = listOf(pos, len, TACKeyword.MEMORY.toVar())
        override val mustWriteVars get() = listOf(newHandle, TACKeyword.SOROBAN_OBJECT_DIGEST.toVar())

        protected override fun transformSymbols(f: Transformer) = DefineNewBufferFromMemory(
            type = type,
            newHandle = f(newHandle),
            pos = f(pos),
            len = f(len)
        )

        protected override fun gen(
            simplifiedInputs: List<TACExpr>,
            analysisCache: TACCommandGraphAnalysisCache
        ) = simplifiedInputs.let { (pos, len) ->
            with(type) {
                val staticData = analysisCache[StaticMemoryAnalysis]
                val bytes = len.getAsConst()?.toIntOrNull()?.let { len ->
                    pos.getAsConst()?.let { pos ->
                        staticData.readBytes(pos, len)
                    }
                }
                if (bytes != null) {
                    newFromBytes(newHandle, bytes)
                } else {
                    mergeMany(
                        new(newHandle) { len },
                        // Indirect through a temp buffer to make the memory optimizations happy
                        letBuf(
                            fromByteMap = TACKeyword.MEMORY.toVar(),
                            fromPos = { pos },
                            len = { len },
                            stride = 1
                        ) { buf ->
                            defineMapping(newHandle) { queryIndex ->
                                select(buf, queryIndex)
                            }
                        }
                    )
                }
            }
        }
    }

    /**
        Copies [len] bytes from the buffer identified by [handle], starting at index [bPos], into linear memory starting
        at [mPos].
     */
    fun copyToMemory(
        handle: TACSymbol,
        bPos: TACSymbol,
        mPos: TACSymbol,
        len: TACSymbol
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val bytes = TACKeyword.TMP(Tag.ByteMap)
        return mergeMany(
            withSize(handle) { bufferSize ->
                Trap.assert("Index out of bounds") { (bPos.asSym() add len.asSym()) le bufferSize.asSym() }
            },
            mergeMany(
                defineMap(bytes) { (index) ->
                    select(mappings.asSym(), handle.asSym(), index)
                },
                listOf(
                    TACCmd.Simple.ByteLongCopy(
                        dstOffset = mPos,
                        srcOffset = bPos,
                        length = len,
                        dstBase = TACKeyword.MEMORY.toVar(),
                        srcBase = bytes,
                        meta = MetaMap(LONG_COPY_STRIDE to 1)
                    )
                ).withDecls(TACKeyword.MEMORY.toVar())
            )
        )
    }

    /**
        Produces a new buffer containing the contents of an old buffer, but with the contents of linear memory starting
        at [mPos] and ending at [mPos + len] copied in at index [bPos].
     */
    fun copyFromMemory(
        newHandle: TACSymbol.Var,
        oldHandle: TACSymbol,
        bPos: TACSymbol,
        mPos: TACSymbol,
        len: TACSymbol
    ) = withSize(oldHandle) { oldSize ->
        mergeMany(
            Trap.assert("Index out of bounds") { bPos.asSym() le oldSize.asSym() },
            new(newHandle) {
                // The resulting buffer may be larger than the old buffer.
                ite(
                    (bPos.asSym() add len.asSym()) gt oldSize.asSym(),
                    bPos.asSym() add len.asSym(),
                    oldSize.asSym()
                )
            },
            defineMapping(newHandle) { queryIndex ->
                ite(
                    queryIndex lt bPos.asSym(),
                    selectFromPreviousMappings(oldHandle.asSym(), queryIndex),
                    ite(
                        queryIndex lt (bPos.asSym() add len.asSym()),
                        select(
                            TACKeyword.MEMORY.toVar().asSym(),
                            queryIndex add mPos.asSym()
                        ),
                        selectFromPreviousMappings(oldHandle.asSym(), queryIndex)
                    )
                )
            }
        )
    }
}
