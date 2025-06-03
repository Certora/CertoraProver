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

package wasm.summarization

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import com.certora.collect.*
import cvlr.CvlrFunctions
import datastructures.stdcollections.*
import log.*
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import vc.data.TACExprFactUntyped as txf
import wasm.host.soroban.types.MapType
import wasm.impCfg.*
import wasm.ir.WasmData
import wasm.ir.WasmName
import wasm.ir.WasmPrimitiveType
import wasm.ir.WasmPrimitiveType.*
import wasm.ir.WasmProgram
import wasm.tokens.WasmTokens
import java.math.BigInteger

/**
 * A Summarizer that implements calls to CVT/assumed compiler builtins
 * - CVT_assert
 * - CVT_assume
 * - memcpy
 * - many nondets
 * - compile-rt functions: https://github.com/llvm-mirror/compiler-rt/tree/master/lib/builtins
 */

private val wasmLogger = Logger(LoggerTypes.WASM)
typealias MessageTag = String

class SummarizeCallTraceException(msg: String) : Exception(msg)

class WasmBuiltinCallSummarizer(private val typeContext: Map<WasmName, WasmProgram.WasmFuncDesc>, private val dataSegments: List<WasmData>): WasmCallSummarizer {

    private val I32_MOD = BigInteger.TWO.pow(32).asTACExpr
    private val I64_MOD = BigInteger.TWO.pow(64).asTACExpr
    private val I128_MOD = BigInteger.TWO.pow(128).asTACExpr

    private val MAX_I128_S = (BigInteger.TWO.pow(127) - BigInteger.ONE).asTACExpr
    private val MIN_I128_S = (BigInteger.TWO.pow(127).asTACExpr).signExt128()

    sealed interface Builtin

    /**
     * The compiler builtins we can recognize
     *
     * @param id the name of the builtin AS IT APPEARS in generated wasm
     * @param params the expected parameter types of the builtin
     * @param ret the expected return type
     */
    enum class CompilerBuiltin(val id: WasmName, val params: List<WasmPrimitiveType>, val ret: WasmPrimitiveType?): Builtin {
        MEMCPY(WasmName("\$memcpy"), listOf(I32, I32, I32), I32),
        MEMSET(WasmName("\$memset"), listOf(I32, I32, I32), I32),
        // https://github.com/llvm-mirror/compiler-rt/tree/master/lib/builtins
        MULTI3(WasmName("\$__multi3"), listOf(I32, I64, I64, I64, I64), null),
        MULOTI4(WasmName("\$__muloti4"), listOf(I32, I64, I64, I64, I64, I32), null),
        UDIVTI3(WasmName("\$__udivti3"), listOf(I32, I64, I64, I64, I64), null),
        DIVTI3(WasmName("\$__divti3"), listOf(I32, I64, I64, I64, I64), null),
        MODTI3(WasmName("\$__modti3"), listOf(I32, I64, I64, I64, I64), null),
//        ASHLTI3(WasmName("\$__ashlti3"), listOf(I32, I64, I64, I32), null), May need at some point
        ;
    }

    /**
     * Function that are only implemented in the prover and thus appear like imports
     *
     * @param what the expected imported name
     * @param from the expected source module
     * @param params the expected parameter types of the builtin
     * @param ret the expected return type
     */
    enum class CVTBuiltin(val what: String, val from: String, val params: List<WasmPrimitiveType>, val ret: WasmPrimitiveType?): Builtin {
        CVT_ASSERT(CvlrFunctions.CVT_assert, "env", listOf(I32), null),
        CVT_ASSUME(CvlrFunctions.CVT_assume, "env", listOf(I32), null),
        CVT_SATISFY(CvlrFunctions.CVT_satisfy, "env", listOf(I32), null),

        CVT_NONDET_U8(CvlrFunctions.CVT_nondet_u8, "env", listOf(), I32),
        CVT_NONDET_U32(CvlrFunctions.CVT_nondet_u32, "env", listOf(), I32),
        CVT_NONDET_U64(CvlrFunctions.CVT_nondet_u64, "env", listOf(), I64),

        CVT_NONDET_I8(CvlrFunctions.CVT_nondet_i8, "env", listOf(), I32),
        CVT_NONDET_I32(CvlrFunctions.CVT_nondet_i32, "env", listOf(), I32),
        CVT_NONDET_I64(CvlrFunctions.CVT_nondet_i64, "env", listOf(), I64),

        CVT_NONDET_I128(CvlrFunctions.CVT_nondet_i128, "env", listOf(I32), null),

        CVT_NONDET_MAP(CvlrFunctions.CVT_nondet_map, "env", listOf(), I64),

        /*
         These functions in rust take a &str and a u32/i32/u64/i64. In wasm the
            first argument is the starting value of the stack pointer which indicates
            the index in the data segment from which the string is read. The second argument indicates
            the length of the string and the third argument is the actual value of the variable.
            User writes this in rust: `cvlr::clog!(&"value of balance is ", balance);`
        */
        CVT_CALLTRACE_U32(CvlrFunctions.CVT_calltrace_print_u32_1, "env", listOf(I32, I32, I32), null),
        CVT_CALLTRACE_U64(CvlrFunctions.CVT_calltrace_print_u64_1, "env", listOf(I32, I32, I64), null),
        CVT_CALLTRACE_U128(CvlrFunctions.CVT_calltrace_print_u128, "env", listOf(I32, I32, I64, I64), null),

        CVT_CALLTRACE_I32(CvlrFunctions.CVT_calltrace_print_i32_1, "env", listOf(I32, I32, I32), null),
        CVT_CALLTRACE_I64(CvlrFunctions.CVT_calltrace_print_i64_1, "env", listOf(I32, I32, I64), null),
        CVT_CALLTRACE_I128(CvlrFunctions.CVT_calltrace_print_i128, "env", listOf(I32, I32, I64, I64), null),
        CVT_CALLTRACE_PRINT_TAG(CvlrFunctions.CVT_calltrace_print_tag, "env", listOf(I32, I32), null),

        ///////////////////////////////////////////////////////////////////////////////////////////
        // for backwards compatibility with https://github.com/Certora/solana-cvt/tree/dev-soroban
        ///////////////////////////////////////////////////////////////////////////////////////////

        ASSERT("CERTORA_assert_c", "env", listOf(I32), null),
        ASSUME("CERTORA_assume_c", "env", listOf(I32), null),
        SATISFY("CERTORA_satisfy_c", "env", listOf(I32), null),

        NONDET_U8("CERTORA_nondet_u8_c", "env", listOf(), I32),
        NONDET_U32("CERTORA_nondet_u32_c", "env", listOf(), I32),
        NONDET_U64("CERTORA_nondet_u64_c", "env", listOf(), I64),

        NONDET_I8("CERTORA_nondet_i8_c", "env", listOf(), I32),
        NONDET_I32("CERTORA_nondet_i32_c", "env", listOf(), I32),
        NONDET_I64("CERTORA_nondet_i64_c", "env", listOf(), I64),

        NONDET_MAP("CERTORA_nondet_map_c", "env", listOf(), I64),

        /*
          These functions in rust take a &str and a u32/i32/u64/i64. In wasm the
          first argument is the starting value of the stack pointer which indicates
          the index in the data segment from which the string is read. The second argument indicates
          the length of the string and the third argument is the actual value of the variable.
          User writes this in rust: `cvt_cex_print_i64!(&"value of balance is ", balance);`
         */
        CALLTRACE_U32("CERTORA_calltrace_print_c_u32", "env", listOf(I32, I32, I32), null),
        CALLTRACE_U64("CERTORA_calltrace_print_c_u64", "env", listOf(I32, I32, I64), null),
        CALLTRACE_I32("CERTORA_calltrace_print_c_i32", "env", listOf(I32, I32, I32), null),
        CALLTRACE_I64("CERTORA_calltrace_print_c_i64", "env", listOf(I32, I32, I64), null),
        ;
    }

    enum class AssumeAssertType { CVT_ASSUME,
        CVT_ASSERT,
        CVT_SATISFY,
        ASSUME,
        ASSERT,
        SATISFY,
    }

    companion object {
        fun asBuiltin(typeContext: Map<WasmName, WasmProgram.WasmFuncDesc>, f: WasmName): Builtin? =
            typeContext[f]?.let { tyDesc ->
                lookupBuiltin(f, tyDesc)
            }


        private fun lookupBuiltin(f: WasmName, tyDesc: WasmProgram.WasmFuncDesc): Builtin? =
            when (tyDesc) {
                is WasmProgram.WasmFuncDesc.ExternalFn ->
                    CVTBuiltin.values().singleOrNull {
                        tyDesc.what == it.what
                            && tyDesc.from == it.from
                            && tyDesc.fnType.params == it.params
                            && tyDesc.fnType.result == it.ret
                    }

                is WasmProgram.WasmFuncDesc.LocalFn ->
                    CompilerBuiltin.values().singleOrNull {
                        it.id == f && it.params == tyDesc.fnType.params && it.ret == tyDesc.fnType.result
                    }
            }
    }

    override fun canSummarize(f: WasmName): Boolean = asBuiltin(typeContext, f) != null

    context (WasmImpCfgContext)
    override fun summarizeCall(call: StraightLine.Call): CommandWithRequiredDecls<TACCmd.Simple> {
        val tyDesc = typeContext[call.id]
        check(tyDesc != null) { "Trying to summarize unknown $call" }
        return when (lookupBuiltin(call.id, tyDesc)) {
            CompilerBuiltin.MEMCPY -> {
                check(call.maybeRet != null) { "expected memcpy to have a lhs "}
                summarizeMemcpy(call.maybeRet, call.args[0], call.args[1], call.args[2])
            }
            CompilerBuiltin.MEMSET -> {
                check(call.maybeRet != null) { "expected memcpy to have a lhs "}
                summarizeMemset(call.maybeRet, call.args[0], call.args[1], call.args[2])
            }

            CompilerBuiltin.MULTI3 -> summarizeMulti3(call.args[0], call.args[1], call.args[2], call.args[3], call.args[4])

            CompilerBuiltin.MULOTI4 -> summarizeMuloti4(call.args[0], call.args[1], call.args[2], call.args[3], call.args[4], call.args[5])

            CompilerBuiltin.UDIVTI3 -> summarizeUDivti3(call.args[0], call.args[1], call.args[2], call.args[3], call.args[4])

            CompilerBuiltin.DIVTI3 -> summarizeDivti3(call.args[0], call.args[1], call.args[2], call.args[3], call.args[4])
            CompilerBuiltin.MODTI3 -> summarizeModti3(call.args[0], call.args[1], call.args[2], call.args[3], call.args[4])

            CVTBuiltin.ASSUME, CVTBuiltin.CVT_ASSUME ->
                summarizeAssumeAssert(pred = call.args[0], type = AssumeAssertType.ASSUME)

            CVTBuiltin.ASSERT, CVTBuiltin.CVT_ASSERT ->
                summarizeAssumeAssert(pred = call.args[0], type = AssumeAssertType.ASSERT)

            CVTBuiltin.SATISFY, CVTBuiltin.CVT_SATISFY ->
                summarizeAssumeAssert(pred = call.args[0], type = AssumeAssertType.SATISFY)

            CVTBuiltin.NONDET_U8, CVTBuiltin.CVT_NONDET_U8 -> {
                check(call.maybeRet != null) { "expected nondet_u8 to have a lhs " }
                summarizeNondet(call.maybeRet, 8)
            }
            CVTBuiltin.NONDET_I8, CVTBuiltin.CVT_NONDET_I8 -> {
                check(call.maybeRet != null) { "expected nondet_i8 to have a lhs " }
                summarizeNondet(call.maybeRet, 8)
            }
            CVTBuiltin.NONDET_U32, CVTBuiltin.CVT_NONDET_U32 -> {
                check(call.maybeRet != null) { "expected nondet_u32 to have a lhs " }
                summarizeNondet(call.maybeRet, 32)
            }

            CVTBuiltin.NONDET_I32, CVTBuiltin.CVT_NONDET_I32 -> {
                check(call.maybeRet != null) { "expected nondet_i32 to have a lhs " }
                summarizeNondet(call.maybeRet, 32)
            }

            CVTBuiltin.NONDET_U64, CVTBuiltin.CVT_NONDET_U64 -> {
                check(call.maybeRet != null) { "expected nondet_u64 to have a lhs " }
                summarizeNondet(call.maybeRet, 64)
            }

            CVTBuiltin.NONDET_I64, CVTBuiltin.CVT_NONDET_I64 -> {
                check(call.maybeRet != null) { "expected nondet_i64 to have a lhs " }
                summarizeNondet(call.maybeRet, 64)
            }

            CVTBuiltin.CVT_NONDET_I128 -> {
                check(call.args.size == 1) { "expected a location in memory where the value will be written" }
                summarizeI128Nondet(call.args[0])
            }
            CVTBuiltin.NONDET_MAP, CVTBuiltin.CVT_NONDET_MAP -> {
                check(call.maybeRet != null) { "expected nondet_map to have a lhs " }
                summarizeNondetMap(call.maybeRet)
            }

            CVTBuiltin.CVT_CALLTRACE_PRINT_TAG -> summarizePrintTagCalltrace(call.args[0], call.args[1], dataSegments)
            CVTBuiltin.CALLTRACE_I32,
            CVTBuiltin.CALLTRACE_I64,
            CVTBuiltin.CALLTRACE_U32,
            CVTBuiltin.CALLTRACE_U64,
            CVTBuiltin.CVT_CALLTRACE_I32,
            CVTBuiltin.CVT_CALLTRACE_I64,
            CVTBuiltin.CVT_CALLTRACE_U32,
            CVTBuiltin.CVT_CALLTRACE_U64 -> summarizeCalltrace(call.args[0], call.args[1], call.args[2], dataSegments)
            CVTBuiltin.CVT_CALLTRACE_U128 -> summarize128BitCalltrace(call.args[0], call.args[1], call.args[2], call.args[3], dataSegments, false)
            CVTBuiltin.CVT_CALLTRACE_I128 -> summarize128BitCalltrace(call.args[0], call.args[1], call.args[2], call.args[3], dataSegments, true)
            null ->
                throw UnknownWasmBuiltin(call.id, tyDesc)
        }.let {
            mergeMany(
                label("--> ${call.id}"),
                it,
                label("<-- ${call.id}")
            )
        }
    }

    /**
        128-bit multiplication:

        *loc = ((high1 * 2^64 + low1) * (high2 * 2^64 + low2)) % 2^128
     */
    context (WasmImpCfgContext)
    private fun summarizeMulti3(
        loc: Arg,
        low1: Arg,
        high1: Arg,
        low2: Arg,
        high2: Arg
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        return txf {
            val x = (high1.toTacSymbol().asSym() mul I64_MOD) add low1.toTacSymbol().asSym()
            val y = (high2.toTacSymbol().asSym() mul I64_MOD) add low2.toTacSymbol().asSym()
            (x mul y).mod(I128_MOD)
        }.letVar { res ->
            val resLow = txf { res.mod(I64_MOD) }
            val resHigh = txf { res div I64_MOD }
            val locHigh = txf {
                (loc.toTacSymbol().asSym() add 8.asTACExpr).mod(I32_MOD)
            }
            mergeMany(
                memStore(loc.toTacSymbol().asSym(), resLow),
                memStore(locHigh, resHigh)
            )
        }
    }

    /**
        128-bit multiplication with signed overflow check:

        product = ((high1 * 2^64 + low1) * (high2 * 2^64 + low2))
        *loc = product % 2^128
        *overflowLoc = product >= 2^127 || product < -2^127
     */
    context (WasmImpCfgContext)
    private fun summarizeMuloti4(
        loc: Arg,
        low1: Arg,
        high1: Arg,
        low2: Arg,
        high2: Arg,
        overflowLoc: Arg
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        return txf {
            val x = ((high1.toTacSymbol().asSym() mul I64_MOD) add low1.toTacSymbol().asSym()).signExt128()
            val y = ((high2.toTacSymbol().asSym() mul I64_MOD) add low2.toTacSymbol().asSym()).signExt128()
            x mul y
        }.letVar { res ->
            val resLow = txf { res.mod(I64_MOD) }
            val resHigh = txf { (res sDiv I64_MOD).mod(I64_MOD) }
            val locHigh = txf {
                (loc.toTacSymbol().asSym() add 8.asTACExpr).mod(I32_MOD)
            }
            val isOverflow = txf {
                ite(
                    (MIN_I128_S sLe res) and (res sLe MAX_I128_S),
                    Zero,
                    One
                )
            }
            mergeMany(
                memStore(loc.toTacSymbol().asSym(), resLow),
                memStore(locHigh, resHigh),
                memStore(overflowLoc.toTacSymbol().asSym(), isOverflow)
            )
        }
    }


    /**
        Unsigned 128-bit division:

        *loc = ((high1 * 2^64 + low1) / (high2 * 2^64 + low2)) % 2^128

        Traps if the denominator is 0
     */
    context (WasmImpCfgContext)
    private fun summarizeUDivti3(
        loc: Arg,
        low1: Arg,
        high1: Arg,
        low2: Arg,
        high2: Arg
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        return txf { (high1.toTacSymbol().asSym() mul I64_MOD) add low1.toTacSymbol().asSym() }
            .letVar { x -> txf { (high2.toTacSymbol().asSym() mul I64_MOD) add low2.toTacSymbol().asSym() }
                .letVar { y ->
                    txf { (x div y).mod(I128_MOD) }
                        .letVar { res ->
                            val resLow = txf { res.mod(I64_MOD) }
                            val resHigh = txf { res div I64_MOD }
                            val locHigh = txf { (loc.toTacSymbol().asSym() add 8.asTACExpr).mod(I32_MOD) }
                            mergeMany(
                                Trap.assert("Denominator is 0") { y neq Zero },
                                memStore(loc.toTacSymbol().asSym(), resLow),
                                memStore(locHigh, resHigh)
                            )
                        }
                }
            }
    }

    private fun TACExpr.signExt128() = TACExpr.BinOp.SignExtend(15.asTACExpr, this, Tag.Bit256)

    /**
        Signed 128-bit division:

        *loc = ((high1 * 2^64 + low1) / (high2 * 2^64 + low2)) % 2^128

        Traps if the denominator is 0
     */
    context (WasmImpCfgContext)
    private fun summarizeDivti3(
        loc: Arg,
        low1: Arg,
        high1: Arg,
        low2: Arg,
        high2: Arg
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        return  txf { ((high1.toTacSymbol().asSym() mul I64_MOD) add low1.toTacSymbol().asSym()).signExt128() }
            .letVar { x ->
                txf { ((high2.toTacSymbol().asSym() mul I64_MOD) add low2.toTacSymbol().asSym()).signExt128() }
                    .letVar { y ->
                        txf { (x sDiv y).mod(I128_MOD) }
                            .letVar { res ->
                                val resLow = txf { res.mod(I64_MOD) }
                                val resHigh = txf { res div I64_MOD }
                                val locHigh =
                                    txf { (loc.toTacSymbol().asSym() add 8.asTACExpr).mod(I32_MOD) }
                                mergeMany(
                                    Trap.assert("Denominator is 0") { y neq Zero },
                                    memStore(loc.toTacSymbol().asSym(), resLow),
                                    memStore(locHigh, resHigh)
                                )
                            }
                    }
            }
    }


    context (WasmImpCfgContext)
    private fun summarizeModti3(
        loc: Arg,
        low1: Arg,
        high1: Arg,
        low2: Arg,
        high2: Arg
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        return  txf { ((high1.toTacSymbol().asSym() mul I64_MOD) add low1.toTacSymbol().asSym()).signExt128() }
            .letVar { x ->
                txf { ((high2.toTacSymbol().asSym() mul I64_MOD) add low2.toTacSymbol().asSym()).signExt128() }
                    .letVar { y ->
                        txf { (x sMod y).mod(I128_MOD) }
                            .letVar { res ->
                                val resLow = txf { res.mod(I64_MOD) }
                                val resHigh = txf { res div I64_MOD }
                                val locHigh =
                                    txf { (loc.toTacSymbol().asSym() add 8.asTACExpr).mod(I32_MOD) }
                                mergeMany(
                                    Trap.assert("Denominator is 0") { y neq Zero },
                                    memStore(loc.toTacSymbol().asSym(), resLow),
                                    memStore(locHigh, resHigh)
                                )
                            }
                    }
            }
    }

    context (WasmImpCfgContext)
    private fun summarizeMemcpy(lhs: Tmp, dest: Arg, src: Arg, len: Arg): CommandWithRequiredDecls<TACCmd.Simple> {
        return MemcopySummary(
            ret = TACSymbol.Var(lhs.toString(), Tag.Bit256),
            dstOffset = dest.toTacSymbol(),
            srcOffset = src.toTacSymbol(),
            length = len.toTacSymbol()
        ).toCmd()
    }

    context (WasmImpCfgContext)
    private fun summarizeMemset(lhs: Tmp, dest: Arg, init: Arg, len: Arg): CommandWithRequiredDecls<TACCmd.Simple> {
        val out = TACSymbol.Var(lhs.toString(), Tag.Bit256)
        val dstOffsetSym = dest.toTacSymbol()
        val initSym = init.toTacSymbol()
        val lenSym = len.toTacSymbol()
        val initBuf = TACKeyword.TMP(Tag.ByteMap, "memset")
        return mergeMany(
            defineMap(initBuf) { _ ->
                initSym.asSym()
            },
            CommandWithRequiredDecls(
                TACCmd.Simple.ByteLongCopy(
                    srcBase = initBuf,
                    srcOffset = TACSymbol.Zero,
                    dstBase = TACKeyword.MEMORY.toVar(),
                    dstOffset = dstOffsetSym,
                    length = lenSym,
                    meta = MetaMap(LONG_COPY_STRIDE to 1)
                )
            ),
            assign(out) { dstOffsetSym.asSym() }
        )
    }

    @KSerializable
    @Treapable
    private data class MemcopySummary(
        val ret: TACSymbol.Var,
        val dstOffset: TACSymbol,
        val srcOffset: TACSymbol,
        val length: TACSymbol
    ) : ITESummary() {
        override val inputs get() = listOf(dstOffset, srcOffset, length)
        override val trueWriteVars get() = setOf(TACKeyword.MEMORY.toVar(), ret)
        override val falseWriteVars get() = setOf(TACKeyword.MEMORY.toVar(), ret)
        override fun transformSymbols(f: Transformer) =
            MemcopySummary(
                ret = f(ret),
                dstOffset = f(dstOffset),
                srcOffset = f(srcOffset),
                length = f(length)
            )

        // if (non-overlapping or identical)
        override val cond get() = txf {
            (dstOffset.asSym() eq srcOffset.asSym()) or
                ((dstOffset.asSym() add length.asSym()) le srcOffset.asSym()) or
                ((srcOffset.asSym() add length.asSym()) le dstOffset.asSym())
        }

        // then mem[dst:dst+len] := mem[src:src+len]
        override fun onTrue() =
            doCopy(TACKeyword.MEMORY.toVar())

        // else mem[dst:dst+len] += nondet
        override fun onFalse() =
            txf { unconstrained(Tag.ByteMap) }.letVar(Tag.ByteMap) { havoc ->
                doCopy(havoc.s)
            }

        private fun doCopy(srcBase: TACSymbol.Var) =
            mergeMany(
                TACCmd.Simple.ByteLongCopy(
                    dstOffset = dstOffset,
                    srcOffset = srcOffset,
                    length = length,
                    dstBase = TACKeyword.MEMORY.toVar(),
                    srcBase = srcBase,
                    meta = MetaMap(LONG_COPY_STRIDE to 1)
                ).withDecls(),
                assign(ret) { dstOffset.asSym() }
            )
    }

    // from the Solana code (can ideally reuse this)
    private fun inRange(v: TACSymbol.Var, lb: TACSymbol.Const, ub: TACSymbol.Const):
        CommandWithRequiredDecls<TACCmd.Simple> {
        val lbBool = TACSymbol.Var(v.namePrefix + WasmTokens.UNDERSCORE + WasmTokens.LOWER, Tag.Bool)
        val ubBool = TACSymbol.Var(v.namePrefix + WasmTokens.UNDERSCORE + WasmTokens.UPPER, Tag.Bool)

        return CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(lbBool, TACExpr.BinRel.Le(lb.asSym(), v.asSym())),
                TACCmd.Simple.AssumeCmd(lbBool, "inRange"),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(ubBool, TACExpr.BinRel.Lt(v.asSym(), ub.asSym())),
                TACCmd.Simple.AssumeCmd(ubBool, "inRange")
            ), setOf(lbBool, ubBool)
        )
    }

    context (WasmImpCfgContext)
    private fun summarizeNondet(lhs: Tmp, exp: Int): CommandWithRequiredDecls<TACCmd.Simple> {
        val argSymTac = TACSymbol.Var(lhs.toString(), Tag.Bit256)
        val rangeCheckTac = inRange(
            argSymTac,
            TACSymbol.Const(BigInteger.ZERO, Tag.Bit256),
            TACSymbol.Const(BigInteger.TWO.pow(exp), Tag.Bit256)
        )
        return mergeMany(assignHavoc(argSymTac), rangeCheckTac)
    }

    /**
     * Make two i64s and write them to memory location [loc] and [loc] + 64
     * */
    context (WasmImpCfgContext)
    private fun summarizeI128Nondet(loc: Arg): CommandWithRequiredDecls<TACCmd.Simple> {
        val locPlus8Bytes = txf { ((loc.toTacSymbol().asSym()) add 8.asTACExpr)}
        val nondetI64A = TACSymbol.Var(loc.toString() + WasmTokens.UNDERSCORE + allocFresh(), Tag.Bit256)
        val nondetI64B = TACSymbol.Var(loc.toString() + WasmTokens.UNDERSCORE + allocFresh(), Tag.Bit256)
        val varForLocPlus8 = TACSymbol.Var(loc.toString() + WasmTokens.UNDERSCORE + allocFresh(), Tag.Bit256)
        val rangeCheckTacA = inRange(
            nondetI64A,
            TACSymbol.Const(BigInteger.ZERO, Tag.Bit256),
            TACSymbol.Const(BigInteger.TWO.pow(64), Tag.Bit256),
        )
        val rangeCheckTacB = inRange(
            nondetI64B,
            TACSymbol.Const(BigInteger.ZERO, Tag.Bit256),
            TACSymbol.Const(BigInteger.TWO.pow(64), Tag.Bit256),
        )
        return mergeMany(
            assignHavoc(nondetI64A),
            rangeCheckTacA,
            assignHavoc(nondetI64B),
            rangeCheckTacB,
            TACCmd.Simple.AssigningCmd.AssignExpCmd(varForLocPlus8, locPlus8Bytes).withDecls(varForLocPlus8),
            memStore(loc.toTacSymbol().asSym(), nondetI64A.asSym()),
            memStore(varForLocPlus8.asSym(), nondetI64B.asSym())
        )
    }

    // Currently not really needed/used.
    context (WasmImpCfgContext)
    private fun summarizeNondetMap(lhs: Tmp): CommandWithRequiredDecls<TACCmd.Simple> {
        val argSymTac = TACSymbol.Var(lhs.toString(), Tag.Bit256)
        return MapType.allocHandle(argSymTac)
    }

    context (WasmImpCfgContext)
    private fun createTag(stringStartPointer: Arg, numBytes: Arg, dataSegments: List<WasmData>): MessageTag {
        var tag = "Calltrace debug print"
        fun reportConstantStringError() {
            wasmLogger.error { "Debug statement usage: `clog!(var_value);`" }
            CVTAlertReporter.reportAlert(
                CVTAlertType.GENERAL,
                CVTAlertSeverity.ERROR,
                jumpToDefinition = null,
                "Debug statement usage: `clog!(var_value);`"
            )
        }
        for (data in dataSegments) {
            if (stringStartPointer is ArgConst32 && numBytes is ArgConst32 && data.offsetConstVal != null) {
                if (stringStartPointer.value.v < data.offsetConstVal) {
                    continue
                }
                val start = stringStartPointer.value.v - data.offsetConstVal!!
                val end = start + numBytes.value.v - BigInteger.ONE

                val byteArray = data.content.slice(start.toInt()..end.toInt()).map { it.toByte() }.toByteArray()
                if (byteArray.isNotEmpty()) {
                    tag = byteArray.toString(Charsets.UTF_8)
                    break
                } else {
                    reportConstantStringError()
                }
            } else {
                reportConstantStringError()
            }
        }
        return tag
    }

    context (WasmImpCfgContext)
    private fun summarizeCalltrace(
        stringStartPointer: Arg,
        numBytes: Arg,
        value: Arg,
        dataSegments: List<WasmData>
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val tag = createTag(stringStartPointer, numBytes, dataSegments)
        when (value) {
            is ArgConst32, is ArgConst64 -> {
                // tag can be something like `x as u64` in rust with spaces
                val vnm = TACSymbol.Var(
                    tag.replace(" ", WasmTokens.UNDERSCORE) + WasmTokens.UNDERSCORE + allocFresh(),
                    Tag.Bit256
                )
                return CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(vnm, value.toTacSymbol()),
                        SnippetCmd.CvlrSnippetCmd.CexPrintValues(tag, listOf(vnm)).toAnnotation()
                    ), setOf(vnm)
                )
            }

            is ArgRegister -> {
                val argSymTac = TACSymbol.Var(value.toString(), Tag.Bit256)
                return CommandWithRequiredDecls(
                    listOf(
                        SnippetCmd.CvlrSnippetCmd.CexPrintValues(tag, listOf(argSymTac)).toAnnotation()
                    ), setOf()
                )
            }

            is Havoc -> throw SummarizeCallTraceException("summarizeCalltrace failed: impossible to see `Havoc args` at this stage.")
        }
    }

    context (WasmImpCfgContext)
    private fun summarize128BitCalltrace(
        stringStartPointer: Arg,
        numBytes: Arg,
        low: Arg,
        high: Arg,
        dataSegments: List<WasmData>,
        signed: Boolean
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        // tag can be something like `x as i128` in rust with spaces
        val tag = createTag(stringStartPointer, numBytes, dataSegments)
        when (high) {
            is ArgConst32, is ArgConst64 -> {
                when (low) {
                    is ArgConst32, is ArgConst64 -> {
                        val highNm = TACSymbol.Var(
                            tag.replace(" ", WasmTokens.UNDERSCORE) + WasmTokens.UPPER + allocFresh(),
                            Tag.Bit256
                        )
                        val lowNm = TACSymbol.Var(
                            tag.replace(" ", WasmTokens.UNDERSCORE) + WasmTokens.LOWER + allocFresh(),
                            Tag.Bit256
                        )
                        return CommandWithRequiredDecls(
                            listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(highNm, high.toTacSymbol()),
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(lowNm, low.toTacSymbol()),
                                SnippetCmd.CvlrSnippetCmd.CexPrint128BitsValue(
                                    tag,
                                    highNm,
                                    lowNm,
                                    signed
                                ).toAnnotation()
                            ), setOf(highNm, lowNm)
                        )
                    }
                    is ArgRegister, is Havoc -> throw SummarizeCallTraceException("summarize128BitCalltrace failed: different ArgRegister types should not be possible.")
                }
            }

            is ArgRegister -> {
                when (low) {
                    is ArgRegister -> {
                        val highArgSymTac = TACSymbol.Var(high.toString(), Tag.Bit256)
                        val lowArgSymTac = TACSymbol.Var(low.toString(), Tag.Bit256)
                        return CommandWithRequiredDecls(
                            listOf(
                                SnippetCmd.CvlrSnippetCmd.CexPrint128BitsValue(
                                    tag,
                                    lowArgSymTac,
                                    highArgSymTac,
                                    signed
                                ).toAnnotation()
                            ), setOf()
                        )
                    }

                    is ArgConst32, is ArgConst64, is Havoc -> throw SummarizeCallTraceException("summarize128BitCalltrace failed: different ArgRegister types should not be possible.")
                }
            }
            is Havoc -> throw SummarizeCallTraceException("summarize128BitCalltrace failed: impossible to see `Havoc args` at this stage.")
        }
    }

    context (WasmImpCfgContext)
    private fun summarizePrintTagCalltrace(stringStartPointer: Arg, numBytes: Arg, dataSegments: List<WasmData>): CommandWithRequiredDecls<TACCmd.Simple> {
        val tag = createTag(stringStartPointer, numBytes, dataSegments)
        return CommandWithRequiredDecls(listOf(SnippetCmd.CvlrSnippetCmd.CexPrintTag(tag).toAnnotation()), setOf())
    }


    context (WasmImpCfgContext)
    private fun summarizeAssumeAssert(
        pred: Arg,
        type: AssumeAssertType,
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val name = listOf(
            when (type) {
                AssumeAssertType.ASSUME, AssumeAssertType.CVT_ASSUME -> WasmTokens.WASMASSUME
                AssumeAssertType.ASSERT, AssumeAssertType.SATISFY, AssumeAssertType.CVT_ASSERT, AssumeAssertType.CVT_SATISFY -> WasmTokens.WASMASSERT
            },
            pred.toString(),
            WasmTokens.TAC, allocFresh()
        ).joinToString(WasmTokens.UNDERSCORE)

        val argSymTac = TACSymbol.Var(name, Tag.Bool)
        val cond = argToCond(pred, type)
        val newAssignment = TACCmd.Simple.AssigningCmd.AssignExpCmd(argSymTac, cond)
        return CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AnnotationCmd(
                    TACCmd.Simple.AnnotationCmd.Annotation(
                        when (type) {
                            AssumeAssertType.ASSUME, AssumeAssertType.CVT_ASSUME -> WASM_USER_ASSUME
                            AssumeAssertType.ASSERT, AssumeAssertType.SATISFY, AssumeAssertType.CVT_ASSERT, AssumeAssertType.CVT_SATISFY -> WASM_USER_ASSERT
                        },
                        when (type) {
                            AssumeAssertType.ASSUME -> "${CVTBuiltin.ASSUME.what}: requiring read value to be 1"
                            AssumeAssertType.ASSERT -> "${CVTBuiltin.ASSERT.what}: expecting read value to be 1, got 0"
                            AssumeAssertType.SATISFY -> "${CVTBuiltin.SATISFY.what}: satisfied read value is 1"
                            AssumeAssertType.CVT_ASSUME -> "${CVTBuiltin.ASSUME.what}: requiring read value to be 1"
                            AssumeAssertType.CVT_ASSERT -> "${CVTBuiltin.ASSERT.what}: expecting read value to be 1, got 0"
                            AssumeAssertType.CVT_SATISFY -> "${CVTBuiltin.SATISFY.what}: satisfied read value is 1"
                        },
                    )
                ),
                newAssignment,
                when (type) {
                    AssumeAssertType.ASSUME, AssumeAssertType.CVT_ASSUME ->
                        TACCmd.Simple.AssumeCmd(argSymTac, "summarizeAssumeAssert")
                    AssumeAssertType.ASSERT, AssumeAssertType.CVT_ASSERT ->
                        TACCmd.Simple.AssertCmd(
                            argSymTac,
                            "Failed property in cvt::assert",
                            MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT)
                        )
                    AssumeAssertType.SATISFY, AssumeAssertType.CVT_SATISFY ->
                        TACCmd.Simple.AssertCmd(
                            argSymTac,
                            "Property satisfied",
                            MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) + (TACMeta.SATISFY_ID to allocSatisfyId())
                        )
                }
            ),
            setOf(argSymTac)
        )
    }

    private fun argToCond(a: Arg, type: AssumeAssertType): TACExpr {
        val success = when (type) {
            AssumeAssertType.ASSERT, AssumeAssertType.CVT_ASSERT, AssumeAssertType.ASSUME, AssumeAssertType.CVT_ASSUME -> TACSymbol.True.asSym()
            AssumeAssertType.SATISFY, AssumeAssertType.CVT_SATISFY -> TACSymbol.False.asSym()
        }
        val failure = when (type) {
            AssumeAssertType.ASSERT, AssumeAssertType.CVT_ASSERT, AssumeAssertType.ASSUME, AssumeAssertType.CVT_ASSUME -> TACSymbol.False.asSym()
            AssumeAssertType.SATISFY, AssumeAssertType.CVT_SATISFY -> TACSymbol.True.asSym()
        }
        return when (a) {
            is ArgRegister -> {
                TACExpr.TernaryExp.Ite(
                    TACExpr.BinRel.Eq(a.toTacSymbol().asSym(), TACExpr.zeroExpr),
                    failure,
                    success
                )
            }

            is ArgConst32 -> {
                TACExpr.TernaryExp.Ite(
                    TACExpr.BinRel.Eq(a.value.v.asTACExpr, TACExpr.zeroExpr),
                    failure,
                    success
                )
            }

            is ArgConst64 -> {
                TACExpr.TernaryExp.Ite(
                    TACExpr.BinRel.Eq(a.value.v.asTACExpr, TACExpr.zeroExpr),
                    failure,
                    success
                )
            }

            is Havoc -> {
                throw CannotHaveHavocException("Havoc may not appear at this stage.")
            }
        }
    }

}

class UnknownWasmBuiltin(val f: WasmName, val t: WasmProgram.WasmFuncDesc): Exception()
