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
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.Tag
import utils.*
import vc.data.*
import wasm.analysis.memory.StaticMemoryAnalysis
import wasm.host.soroban.*
import wasm.tacutils.*
import wasm.traps.Trap

/** Soroban symbol objects are buffers holding at most 32 characters in [a-zA-Z0-9_] */
@KSerializable
@Treapable
object SymbolType : BufferType() {
    override fun hashCode() = hashObject(this)

    override val tag = Val.Tag.SymbolObject
    override val sizes = TACKeyword.SOROBAN_SYMBOL_SIZES.toVar()
    override val mappings = TACKeyword.SOROBAN_SYMBOL_MAPPINGS.toVar()
    // TODO CERT-6560: implement length limits and/or content restrictions?

    private const val SYMBOL_SMALL_MAX_LEN = 9
    private const val CODE_BITS = 6

    // strPtr points to a structure { &str, u32 }
    //
    // e.g. this is how memory might look starting from adddress 0xBAADF00D
    // thisissomestringdata0\x0D\xF0\xAD\xBA\x1e\x00\x00\x00
    // ^0xBAADF00D          ^strPtr
    private fun StaticMemoryAnalysis.derefString(strPtr: TACExpr): List<UByte>? {
        val stringRef = strPtr.evalAsConst() ?: return null
        val stringDataStart = readLittleEndian32BitWord(stringRef) ?: return null
        val stringLength = readLittleEndian32BitWord(stringRef+4)?.toIntOrNull() ?: return null
        return readBytes(stringDataStart, stringLength)
    }

    private fun newNondetSymbol(newHandle: TACSymbol.Var) = mergeMany(
        assignHavoc(newHandle),
        Val.assumeValid(newHandle, Val.Tag.SymbolSmall, Val.Tag.SymbolObject)
    )

    fun newFromStrPtr(
        newHandle: TACSymbol.Var,
        strPtr: TACExpr,
        staticData: StaticMemoryAnalysis
    ) = staticData.derefString(strPtr)?.let { bytes ->
        newSmallSymbolFromBytes(newHandle, bytes) ?: newFromBytes(newHandle, bytes)
    } ?: newNondetSymbol(newHandle)

    fun newFromStr(
        newHandle: TACSymbol.Var,
        strPtr: TACSymbol,
        len: TACSymbol,
    ) = NewSymbolFromStrSummary(newHandle, strPtr, len).toCmd()

    private fun newSmallSymbolFromBytes(newHandle: TACSymbol.Var, bytes: List<UByte>): CommandWithRequiredDecls<TACCmd.Simple>? =
        bytesAsSymbolSmall(bytes)?.let {
            assign(newHandle) { it.asSym() }
        }

    override fun newFromBytes(newHandle: TACSymbol.Var, bytes: List<UByte>): CommandWithRequiredDecls<TACCmd.Simple> {
        check(bytes.size <= 32) {
            "Assumption violated: symbols should be 32 bytes or less"
        }
        // Note this ONLY returns a SymbolObject (not a SmallSymbol)
        return super.newFromBytes(newHandle, bytes)
    }

    // https://github.com/stellar/rs-soroban-env/blob/164757123268943d9c16059c0124ef113a74f2aa/soroban-env-common/src/symbol.rs#L229
    private fun tryEncodeSmallByte(b: UByte): UByte? {
        val bInt = b.toInt()
        return when (bInt) {
            '_'.code -> {
                1U
            }
            in '0'.code .. '9'.code -> {
                (2 + bInt - '0'.code).toUByte()
            }
            in 'A'.code .. 'Z'.code -> {
                (12 + bInt - 'A'.code).toUByte()
            }
            in 'a'.code .. 'z'.code -> {
                (38 + bInt - 'a'.code).toUByte()
            }
            else -> {
                null
            }
        }
    }

    // Essentially this:
    // https://github.com/stellar/rs-soroban-env/blob/164757123268943d9c16059c0124ef113a74f2aa/soroban-env-common/src/symbol.rs#L240
    private fun bytesAsSymbolSmall(bytes: List<UByte>): TACSymbol? {
        if (bytes.size > SYMBOL_SMALL_MAX_LEN) {
            return null
        }

        var accum = 0L
        for (b in bytes) {
            val v = tryEncodeSmallByte(b) ?: return null
            accum = (accum shl CODE_BITS) or v.toLong()
        }

        return Val(Val.Tag.SymbolSmall, accum).s
    }

    fun symbolIndexInLinearMemory(
        retIndex: TACSymbol.Var,
        sym: TACSymbol,
        slicesPos: TACSymbol,
        len: TACSymbol
    ): CommandWithRequiredDecls<TACCmd.Simple> =
        SymbolIndexInMemory(retIndex, sym, slicesPos, len).toCmd()

    @KSerializable
    data class SymbolIndexInMemory(
        val retIndex: TACSymbol.Var,
        val sym: TACSymbol,
        val slicesPos: TACSymbol,
        val len: TACSymbol
    ): PostUnrollAssignmentSummary() {
        override val inputs
            get() = listOf(sym, slicesPos, len)

        override val mustWriteVars: List<TACSymbol.Var>
            get() = listOf(retIndex)

        override fun transformSymbols(f: Transformer): AssignmentSummary =
            SymbolIndexInMemory(
                retIndex = f(retIndex),
                sym = f(sym),
                slicesPos = f(slicesPos),
                len = f(len)
            )

        override val annotationDesc: String
            get() = "$retIndex := symbol_index_in_linear_memory($sym, $slicesPos, $len)"

        override val mayWriteVars: List<TACSymbol.Var>
            get() = mustWriteVars

        override fun gen(
            simplifiedInputs: List<TACExpr>,
            staticData: StaticMemoryAnalysis
        ): CommandWithRequiredDecls<TACCmd.Simple> = simplifiedInputs.let { (sym, slices, len) ->
            val havoc by lazy {
                assign(retIndex) { Unconstrained(Tag.Bit256) }
            }
            val symbolBytes = TACKeyword.TMP(Tag.ByteMap)
            val lenConst = len.evalAsConst() ?: return havoc
            val slicesConst = slices.evalAsConst() ?: return havoc
            val isSymbolSmall = TACKeyword.TMP(Tag.Bool)
            val comparisons = (0 ..< lenConst.safeAsInt()).monadicMap {
                staticData.derefString((slicesConst + (8*it)).asTACExpr)
            }?.map { string ->
                TACExprFactUntyped {
                    val symSmallComparison = bytesAsSymbolSmall(string)?.let { symSmall ->
                        sym eq symSmall
                    } ?: false.asTACExpr

                    val strcmp = string.withIndex().map { (i, b) ->
                        b.toInt().asTACExpr eq select(symbolBytes.asSym(), i.asTACExpr)
                    }.foldFirst { e1, e2 -> e1 and e2 }

                    ite(i = isSymbolSmall.asSym(), t = symSmallComparison, e = strcmp)
                }
            } ?: return havoc

           return mergeMany(
               // Is symbol small?
               assign(isSymbolSmall) {Val.hasTag(sym, Val.Tag.SymbolSmall) },

               // Read the symbol
               defineMap(symbolBytes) {(index) -> select(mappings.asSym(), sym, index) },

               Trap.assert("symbol_index_in_linear_memory symbol not found") {
                   comparisons.foldFirstOrNull { here, there -> here or there } ?: false.asTACExpr
               },

               assign(retIndex) {
                   comparisons.foldRightIndexed(Unconstrained(Tag.Bit256)) { idx, cmp, acc: TACExpr ->
                        ite(i = cmp, t = idx.asTACExpr, e = acc)
                   }
               }
           )
        }
    }

    @KSerializable
    data class NewSymbolFromStrSummary(
        val newHandle: TACSymbol.Var,
        val strPtr: TACSymbol,
        val len: TACSymbol,
    ): PostUnrollAssignmentSummary() {
        override val inputs get() = listOf(
            strPtr,
            len,
        )

        override val mustWriteVars get() = listOf(
            newHandle,
            mappings,
            sizes,
            TACKeyword.SOROBAN_OBJECT_DIGEST.toVar(),
        )

        override fun transformSymbols(f: Transformer): AssignmentSummary =
            NewSymbolFromStrSummary(
                newHandle = f(newHandle),
                strPtr = f(strPtr),
                len = f(len),
            )

        override val annotationDesc: String
            get() = "$newHandle := new Symbol handle from slice [$strPtr, $len]"

        override val mayWriteVars: List<TACSymbol.Var>
            get() = mustWriteVars

        override fun gen(
            simplifiedInputs: List<TACExpr>,
            staticData: StaticMemoryAnalysis
        ): CommandWithRequiredDecls<TACCmd.Simple> = simplifiedInputs.let { (ptr, len) ->

            val nondet by lazy { newNondetSymbol(newHandle) }
            val literalPointer = ptr.evalAsConst() ?: return nondet
            val literalLen = len.evalAsConst()?.safeAsInt() ?: return nondet
            val bytes = staticData.readBytes(literalPointer, literalLen) ?: return nondet

            return newSmallSymbolFromBytes(newHandle, bytes) ?: newFromBytes(newHandle, bytes)
        }
    }
}
