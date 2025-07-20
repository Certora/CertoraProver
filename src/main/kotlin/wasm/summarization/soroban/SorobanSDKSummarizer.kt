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

package wasm.summarization.soroban

import datastructures.stdcollections.*
import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import tac.generation.*
import tac.Tag
import vc.data.*
import vc.data.TACExprFactUntyped
import wasm.host.soroban.Val
import wasm.host.soroban.types.IntType
import vc.data.TACExprFactUntyped as txf
import wasm.host.soroban.types.SymbolType
import wasm.impCfg.*
import wasm.ir.WasmName
import wasm.ir.WasmPrimitiveType
import wasm.ir.WasmProgram.*
import wasm.summarization.WasmCallSummarizer

/**
 * A summarizer for Soroban SDK functions
 */
class SorobanSDKSummarizer(
    private val typeContext: Map<WasmName, WasmFuncDesc>,
): WasmCallSummarizer {

    /**
     * Soroban SDK functions we can summarize
     *
     * @param demangledName the demangled name of the function
     * @param params the expected parameter types of the builtin
     * @param ret the expected return type
     */
    enum class SorobanSDKBuiltin(val demangledName: String, val params: List<WasmPrimitiveType>, val ret: WasmPrimitiveType?) {
        SYMBOL_NEW("\$soroban_sdk::symbol::Symbol::new",
            listOf(WasmPrimitiveType.I32, WasmPrimitiveType.I32),
            WasmPrimitiveType.I64
        ),

        TRY_FROM_VAL_TO_U64(
            "\$<u64 as soroban_env_common::convert::TryFromVal<E,soroban_env_common::val::Val>>::try_from_val",
            listOf(WasmPrimitiveType.I32, WasmPrimitiveType.I64),
            null
        ),

        TRY_FROM_VAL_TO_I128(
            "\$<i128 as soroban_env_common::convert::TryFromVal<E,soroban_env_common::val::Val>>::try_from_val",
            listOf(WasmPrimitiveType.I32, WasmPrimitiveType.I64),
            null
        ),

        TRY_FROM_I128_TO_VAL(
            "\$<soroban_env_common::val::Val as soroban_env_common::convert::TryFromVal<E,i128>>::try_from_val",
            listOf(WasmPrimitiveType.I32, WasmPrimitiveType.I64, WasmPrimitiveType.I64),
            null
        ),
        TRY_FROM_U64_TO_VAL(
            "\$<soroban_env_common::val::Val as soroban_env_common::convert::TryFromVal<E,u64>>::try_from_val",
            listOf(WasmPrimitiveType.I32, WasmPrimitiveType.I64, WasmPrimitiveType.I64),
            null
        ),

        // The SDK Symbol type is actually a struct comprising an Env and a soroban_env_common::Symbol.
        // However, for the wasm target, the Env impl is a 0-size Guest struct, so this method
        // ends up looking exactly the same as the soroban_env_common::Symbol implementation
        SDK_SYMBOL_TRY_FROM_VAL_STRREF(
            "\$<soroban_sdk::symbol::Symbol as soroban_env_common::convert::TryFromVal<soroban_sdk::env::Env,&str>>::try_from_val",
            listOf(WasmPrimitiveType.I32, WasmPrimitiveType.I32),
            WasmPrimitiveType.I64),

        SYMBOL_TRY_FROM_VAL_STRREF(
            "\$<soroban_env_common::symbol::Symbol as soroban_env_common::convert::TryFromVal<E,&str>>::try_from_val",
            listOf(WasmPrimitiveType.I32, WasmPrimitiveType.I32),
            WasmPrimitiveType.I64
            )
        ;
    }

    override fun canSummarize(f: WasmName): Boolean = asSDKFunc(f) != null

    private fun asSDKFunc(f: WasmName): SorobanSDKBuiltin? {
        return when (val tyDesc = typeContext[f]) {
            is WasmFuncDesc.LocalFn -> {
                val demangledName = WasmInliner.demangle(f.toString())
                SorobanSDKBuiltin.values().singleOrNull() {
                    demangledName == it.demangledName &&
                        tyDesc.fnType.params == it.params &&
                        tyDesc.fnType.result == it.ret
                }
            }
            else -> null
        }
    }

    context(WasmImpCfgContext) override fun summarizeCall(call: StraightLine.Call): CommandWithRequiredDecls<TACCmd.Simple> {
        return when (val f = asSDKFunc(call.id)) {
            SorobanSDKBuiltin.SDK_SYMBOL_TRY_FROM_VAL_STRREF,
            SorobanSDKBuiltin.SYMBOL_TRY_FROM_VAL_STRREF,
            SorobanSDKBuiltin.SYMBOL_NEW -> {
                check(call.maybeRet != null) { "expected Symbol::new to have an lhs" }
                summarizeSymbolNew(call.maybeRet, call.args[0], call.args[1])
            }

            SorobanSDKBuiltin.TRY_FROM_VAL_TO_I128 -> {
                check (call.args.size == 2) { "expected ${f.demangledName} to have two arguments "}
                summarizeTryFromValToIntType(f.demangledName, IntType.I128, call.args[0], call.args[1])
            }
            SorobanSDKBuiltin.TRY_FROM_VAL_TO_U64 -> {
                check (call.args.size == 2) { "expected ${f.demangledName} to have two arguments "}
                summarizeTryFromValToIntType(f.demangledName, IntType.U64, call.args[0], call.args[1])
            }

            SorobanSDKBuiltin.TRY_FROM_I128_TO_VAL -> {
                check (call.args.size == 3) { "expected ${f.demangledName} to have three arguments "}
                summarizeTryFromIntType(f.demangledName, IntType.I128, call.args[0], call.args.drop(1))
            }
            SorobanSDKBuiltin.TRY_FROM_U64_TO_VAL -> {
                check (call.args.size == 2) { "expected ${f.demangledName} to have two arguments "}
                summarizeTryFromIntType(f.demangledName, IntType.U64, call.args[0], call.args.drop(1))
            }

            else ->
                throw UnknownSorobanSDKFunction(call.id)
        }
    }

    /**
     * Stitch all the pieces together into a Bv256, and then encode into a val. This is what
     * the original SDK function does, except here we turn the branching control flow into a select
     * via [type.encodeToVal]
     *
     * Assigns
     *   outPtrArg[0] = 0 (always succeeds)
     *   outPtrArg[8] = the new Val
     */
    private fun summarizeTryFromIntType(name: String, type: IntType, outPtrArg: Arg, lowEndianPieces: List<Arg>): CommandWithRequiredDecls<TACCmd.Simple> {
        val ptr = outPtrArg.toTacSymbol()
        val lowEndianTac = lowEndianPieces.map { it.toTacSymbol() }
        val bigEndianPieces = lowEndianTac.reversed()
        val handle = TACKeyword.TMP(Tag.Bit256, "obj")
        val enter = listOf(TACCmd.Simple.AnnotationCmd(
            WASM_SDK_FUNC_SUMMARY_START,
            StraightLine.InlinedFuncStartAnnotation.TAC(
                name,
                listOf(ptr) + lowEndianPieces.map { it.toTacSymbol() }, null)
        )).withDecls()
        val exit = listOf(TACCmd.Simple.AnnotationCmd(
            WASM_SDK_FUNC_SUMMARY_END,
            StraightLine.InlinedFuncEndAnnotation.TAC(name)
        )).withDecls()
        return mergeMany(
            enter,
            // Construct the bv256...
            type.tacbv256FromPieces(bigEndianPieces).letVar(Tag.Bit256) { bv256 ->
                // And then either allocate an object (if it's not a small value)
                // or encode the bv directly in the Val (if it's a small value)
                type.encodeToVal(handle, bv256.s)
            },
            memStore(ptr.asSym(), 0.asTACExpr),
            memStore(TACExprFactUntyped { ptr.asSym().add(8.asTACExpr) }, handle.asSym()),
            exit
        )
    }

    /**
     * Summarize the SDK function by eliminating control flow branching (by using [type.decodeVal])
     *
     * Assigns
     *   outPtrArg[0] = Success
     *   outPtrArg[8] = least significant 64 bits
     *   outPtrArg[16] = most significant 64 bits
     */
    private fun summarizeTryFromValToIntType(name: String, type: IntType, outPtrArg: Arg, handleArg: Arg): CommandWithRequiredDecls<TACCmd.Simple> {
        val ptr = outPtrArg.toTacSymbol()
        val handle = handleArg.toTacSymbol()
        val succeed = TACKeyword.TMP(Tag.Bool, "succeed")
        val decoded = TACKeyword.TMP(Tag.Bit256, "decoded")
        val allocCheck = Val.hasTag(handle.asSym(), type.tag) implies Val.isAllocated(handle.asSym())
        val enter = listOf(TACCmd.Simple.AnnotationCmd(
            WASM_SDK_FUNC_SUMMARY_START,
            StraightLine.InlinedFuncStartAnnotation.TAC(name, listOf(ptr, handle), null)
        )).withDecls()
        val exit = listOf(TACCmd.Simple.AnnotationCmd(
            WASM_SDK_FUNC_SUMMARY_END,
            StraightLine.InlinedFuncEndAnnotation.TAC(name)
        )).withDecls()
        return mergeMany(
            enter,
            // The VM should trap if the Int object is invalid
            // (but passing a non Int-like val simply results in the Err Result case)
            Trap.assert("Expected a valid object $handle") { allocCheck },

            assign(succeed) {
                Val.hasTag(handle.asSym(), type.smallTag) or Val.hasTag(handle.asSym(), type.tag)
            },

            // Otherwise, we will succeed on an i128-like Val
            TACExprFactUntyped {
                ite(i = succeed.asSym(),
                    t = 0.asTACExpr,
                    e = 1.asTACExpr)
            }.letVar { success ->
                memStore(ptr.asSym(), success)
            },

            // Just retrieve the value from our map (if we never observed the conversion _into_ val then
            // running all the sdk code is pointless anyway)
            type.decodeVal(decoded, handle.asSym()),
            assume { decoded.asSym() le type.allBitsSet },
            mergeMany(
                (0 ..< type.numPieces).map { piece ->
                    // little endian in memory while objPiece is big endian:
                    // piece 0 is the most signifiant so it goes at the greatest offset
                    val offset = ((type.numPieces - piece)*8).asTACExpr
                    memStore(
                        txf { ptr.asSym().add(offset) },
                        txf { ite(i = succeed.asSym(), t = type.objPiece(decoded.asSym(), piece), e = TACExpr.Unconstrained(Tag.Bit256)) }
                    )
                }
            ),
            exit
        )
    }

    private fun summarizeSymbolNew(lhs: Tmp, strPtr: Arg, len: Arg) =
        SymbolType.newFromStr(
            TACSymbol.Var(lhs.toString(), Tag.Bit256),
            strPtr.toTacSymbol(),
            len.toTacSymbol()
        )
}

class UnknownSorobanSDKFunction(val f: WasmName): Exception()
