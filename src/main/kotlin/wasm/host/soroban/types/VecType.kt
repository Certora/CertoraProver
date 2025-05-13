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
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import wasm.host.soroban.*

/**
    Vectors are [ArrayType]s whose values are arbitrary [Val]s.
 */
@KSerializable
@Treapable
object VecType : ArrayType() {
    override fun hashCode() = hashObject(this)

    override val tag = Val.Tag.VecObject
    override val sizes = TACKeyword.SOROBAN_VEC_SIZES.toVar()
    override val mappings = TACKeyword.SOROBAN_VEC_MAPPINGS.toVar()

    @KSerializable
    private data class NewVecFromMemoryDigest(
        val handle: TACSymbol.Var,
        val pos: TACSymbol,
        val len: TACSymbol
    ) : PostUnrollAssignmentSummary() {

        override fun gen(
            simplifiedInputs: List<TACExpr>,
            analysisCache: AnalysisCache
        ): CommandWithRequiredDecls<TACCmd.Simple> = simplifiedInputs.let { (p, l) ->
            l.evalAsConst()?.safeAsInt()?.let { lengthLiteral ->
                Val.setObjectDigest(tag, handle.asSym()) {
                    (0 until lengthLiteral).map {
                        Val.digest(
                            select(TACKeyword.MEMORY.toVar().asSym(), p add (it.asTACExpr mul Val.sizeInBytes.asTACExpr))
                        )
                    }
                }
            } ?: CommandWithRequiredDecls()
        }

        override val inputs: List<TACSymbol>
            get() = listOf(pos, len)

        override fun transformSymbols(f: Transformer): AssignmentSummary =
            NewVecFromMemoryDigest(f(handle), f(pos), f(len))

        override val mustWriteVars: Collection<TACSymbol.Var>
            get() = setOf(TACKeyword.SOROBAN_OBJECT_DIGEST.toVar())
    }
    /**
        Creates a new vec from the contents of linear memory starting at [pos], with size [size].

        The expected memory layout is simply a contiguous sequence of 64-bit Val values.

        Note: the actual Soroban host validates the memory input values, but we do not do this here, simply because it
        would require an expensive quantifier expression to do so.  We can revisit this in the future if needed.
     */
    fun newFromMemory(
        newHandle: TACSymbol.Var,
        pos: TACSymbol,
        size: TACSymbol
    ) = mergeMany(
        new(newHandle) { size.asSym() },
        NewVecFromMemoryDigest(newHandle, pos, size).toCmd(),
        letBuf(
            fromByteMap = TACKeyword.MEMORY.toVar(),
            fromPos = { pos.asSym() },
            len = { size.asSym() mul Val.sizeInBytes.asTACExpr },
            stride = Val.sizeInBytes
        ) { buf ->
            defineMapping(newHandle) { queryIndex ->
                select(
                    buf,
                    queryIndex mul Val.sizeInBytes.asTACExpr
                )
            }
        }
    )

    /**
        Copies [size] elements from the vec identified by [handle] into linear memory starting at [pos].
     */
    fun copyToMemory(
        handle: TACSymbol,
        pos: TACSymbol,
        size: TACSymbol
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val bytes = TACKeyword.TMP(Tag.ByteMap)
        val bytesLen = TACKeyword.TMP(Tag.Bit256)
        return mergeMany(
            withSize(handle) { vecSize ->
                Trap.assert("Index out of bounds") { size.asSym() le vecSize.asSym() }
            },
            defineMap(bytes) { (queryOffset) ->
                ite(
                    (queryOffset mod Val.sizeInBytes.asTACExpr) eq 0.asTACExpr,
                    select(mappings.asSym(), handle.asSym(), queryOffset div Val.sizeInBytes.asTACExpr),
                    unconstrained(Tag.Bit256)
                )
            },
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    bytesLen,
                    TACExprFactUntyped { size.asSym() mul Val.sizeInBytes.asTACExpr }
                ),
                TACCmd.Simple.ByteLongCopy(
                    dstOffset = pos,
                    srcOffset = TACSymbol.Zero,
                    length = bytesLen,
                    dstBase = TACKeyword.MEMORY.toVar(),
                    srcBase = bytes,
                    meta = MetaMap(LONG_COPY_STRIDE to Val.sizeInBytes)
                )
            ).withDecls(bytesLen)
        )
    }
}
