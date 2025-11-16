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

package wasm.summarization.core

import datastructures.stdcollections.*
import analysis.CommandWithRequiredDecls
import tac.generation.Trap
import vc.data.TACCmd
import wasm.impCfg.StraightLine
import wasm.impCfg.WasmImpCfgContext
import wasm.impCfg.WasmInliner
import wasm.ir.WasmName
import wasm.ir.WasmPrimitiveType
import wasm.ir.WasmPrimitiveType.I32
import wasm.ir.WasmProgram.WasmFuncDesc
import wasm.summarization.WasmCallSummarizer

class RustCoreSummarizer(
    private val typeContext: Map<WasmName, WasmFuncDesc>,
) : WasmCallSummarizer {

    enum class CoreSDK(val demangledName: String, val params: List<WasmPrimitiveType>, val ret: WasmPrimitiveType? = null) {
        PANIC("\$core::panicking::panic",listOf(I32, I32, I32)),
        PANIC_FMT("\$core::panicking::panic_fmt",listOf(I32, I32)),
        ;
    }


    override fun canSummarize(f: WasmName): Boolean = asCoreFunc(f) != null

    private fun asCoreFunc(f: WasmName): CoreSDK? {
        return when (val tyDesc = typeContext[f]) {
            is WasmFuncDesc.LocalFn -> {
                val demangledName = WasmInliner.demangle(f.toString())
                CoreSDK.entries.singleOrNull {
                    demangledName == it.demangledName &&
                        tyDesc.fnType.params == it.params &&
                        tyDesc.fnType.result == it.ret
                }
            }
            else -> null
        }
    }

    context(WasmImpCfgContext)
    override fun summarizeCall(call: StraightLine.Call): CommandWithRequiredDecls<TACCmd.Simple> {
        return when (asCoreFunc(call.id)) {
            CoreSDK.PANIC_FMT,
            CoreSDK.PANIC -> Trap.trapRevert("panic")
            null -> throw UnknownCoreSDKFunction(call.id)
        }
    }
}

class UnknownCoreSDKFunction(val f: WasmName): Exception()
