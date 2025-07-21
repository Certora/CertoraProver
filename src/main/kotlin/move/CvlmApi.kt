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

package move

import datastructures.stdcollections.*
import config.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*

/**
    Provides implementations for the CVLM API functions.
 */
object CvlmApi {
    val MESSAGE_VAR = MetaKey<TACSymbol.Var>("cvlm.assert.message")

    private val cvlmAddr = Config.CvlmAddress.get()

    private val summarizers = mutableMapOf<MoveFunctionName, context(SummarizationContext) (MoveCall) -> MoveBlocks>()

    context(SummarizationContext)
    fun summarize(call: MoveCall) = summarizers[call.callee.name]?.invoke(this@SummarizationContext, call)

    private fun addSummary(
        module: MoveModuleName,
        function: String,
        summarizer: context(SummarizationContext) (MoveCall) -> MoveBlocks
    ) {
        val name = MoveFunctionName(module, function)
        if (summarizers.put(name, summarizer) != null) {
            error("Duplicate summary for $name")
        }
    }

    private val assertsModule = MoveModuleName(cvlmAddr, "asserts")
    private val nondetModule = MoveModuleName(cvlmAddr, "nondet")
    private val ghostModule = MoveModuleName(cvlmAddr, "ghost")
    private val conversionsModule = MoveModuleName(cvlmAddr, "conversions")
    private val mathIntModule = MoveModuleName(cvlmAddr, "math_int")

    private val mathIntTypeName = MoveStructName(mathIntModule, "MathInt")

    fun maybeShadowType(type: MoveType.Struct) = when (type.name) {
        mathIntTypeName -> MoveType.MathInt
        else -> null
    }

    init {
        addAssertsSummaries()
        addNondetSummaries()
        addGhostSummaries()
        addConversionsSummaries()
        addMathIntSummaries()
    }

    private fun addAssertsSummaries() {
        /*
            ```
            public native fun cvlm_assert_checked(cond: bool);
            ```
         */
        addSummary(assertsModule, "cvlm_assert_checked") { call ->
            singleBlockSummary(call) {
                TACCmd.Simple.AssertCmd(
                    call.args[0],
                    "Failed property in cvt::assert",
                    MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT)
                ).withDecls()
            }
        }

        /*
            ```
            public native fun cvlm_assert_checked_msg(cond: bool, msg: vector<u8>);
            ```
         */
        addSummary(assertsModule, "cvlm_assert_checked_msg") { call ->
            singleBlockSummary(call) {
                TACCmd.Simple.AssertCmd(
                    call.args[0],
                    "Failed property in cvt::assert (could not extract message)", // message will be replaced later
                    MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) + (MESSAGE_VAR to call.args[1])
                ).withDecls()
            }
        }

        /*
            ```
            public native fun cvlm_assume_checked(cond: bool);
            ```
         */
        addSummary(assertsModule, "cvlm_assume_checked") { call ->
            singleBlockSummary(call) {
                TACCmd.Simple.AssumeCmd(
                    call.args[0],
                    "summarizeAssumeAssert"
                ).withDecls()
            }
        }

        /*
            ```
            public native fun cvlm_assume_checked_msg(cond: bool, msg: vector<u8>);
            ```
         */
        addSummary(assertsModule, "cvlm_assume_checked_msg") { call ->
            singleBlockSummary(call) {
                TACCmd.Simple.AssumeCmd(
                    call.args[0],
                    "summarizeAssumeAssert",
                    MetaMap(MESSAGE_VAR to call.args[1])
                ).withDecls()
            }
        }

        /*
            ```
            public native fun cvlm_satisfy_checked(cond: bool);
            ```
         */
        addSummary(assertsModule, "cvlm_satisfy_checked") { call ->
            singleBlockSummary(call) {
                TXF { not(call.args[0]) }.letVar(Tag.Bool) { cond ->
                    TACCmd.Simple.AssertCmd(
                        cond.s,
                        "Property satisfied",
                        MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) + (TACMeta.SATISFY_ID to allocSatisfyId())
                    ).withDecls()
                }
            }
        }

        /*
            ```
            public native fun cvlm_satisfy_checked_msg(cond: bool, msg: vector<u8>);
            ```
         */
        addSummary(assertsModule, "cvlm_satisfy_checked_msg") { call ->
            singleBlockSummary(call) {
                TXF { not(call.args[0]) }.letVar(Tag.Bool) { cond ->
                    TACCmd.Simple.AssertCmd(
                        cond.s,
                        "Property satisfied (could not extract message)",
                        MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) +
                            (TACMeta.SATISFY_ID to allocSatisfyId()) +
                            (MESSAGE_VAR to call.args[1])
                    ).withDecls()
                }
            }
        }
    }

    private fun addNondetSummaries() {
        /*
            ```
            public native fun nondet<T>(): T;
            ```
         */
        addSummary(nondetModule, "nondet") { call ->
            singleBlockSummary(call) {
                call.callee.typeArguments[0].assignHavoc(call.returns[0])
            }
        }
    }

    private fun addGhostSummaries() {
        /*
            ```
            public native fun ghost_read<T>(ref: &T): T;
            ```
            Reads from a reference without regard to the "capabilities" of the referenced type.
         */
        addSummary(ghostModule, "ghost_read") { call ->
            singleBlockSummary(call) {
                TACCmd.Move.ReadRefCmd(
                    dst = call.returns[0],
                    ref = call.args[0]
                ).withDecls(call.returns[0])
            }
        }

        /*
            ```
            public native fun ghost_write<T>(ref: &mut T, value: T);
            ```
            Writes to a reference without regard to the "capabilities" of the referenced type.
         */
        addSummary(ghostModule, "ghost_write") { call ->
            singleBlockSummary(call) {
                TACCmd.Move.WriteRefCmd(
                    ref = call.args[0],
                    src = call.args[1]
                ).withDecls()
            }
        }

        /*
            ```
            public native fun ghost_destroy<T>(value: T);
            ```
            Consumes the argument, without regard to the "capabilities" of the type.
         */
        addSummary(ghostModule, "ghost_destroy") { call ->
            singleBlockSummary(call) {
                // Do nothing; simply consume the argument
                TACCmd.Simple.NopCmd.withDecls()
            }
        }
    }

    private fun addConversionsSummaries() {
        /*
            ```
            public native fun u256_to_address(n: u256): address;
            ```
            Converts a u256 value to an address.  We represent both types as Bit256 values, so this is just a copy.
         */
        addSummary(conversionsModule, "u256_to_address") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() }
            }
        }
    }

    private fun addMathIntSummaries() {
        /*
            ```
            public native fun from_u256(value: u256): MathInt;
            ```
            Converts a u256 value to a MathInt.
         */
        addSummary(mathIntModule, "from_u256") { call ->
            singleBlockSummary(call) {
                // No conversion is needed; a Bit256 is already an Int
                assign(call.returns[0]) { call.args[0].asSym() }
            }
        }

        /*
            ```
            public native fun add(a: MathInt, b: MathInt): MathInt;
            ```
         */
        addSummary(mathIntModule, "add") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() intAdd call.args[1].asSym() }
            }
        }

        /*
            ```
            public native fun sub(a: MathInt, b: MathInt): MathInt;
            ```
         */
        addSummary(mathIntModule, "sub") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() intSub call.args[1].asSym() }
            }
        }

        /*
            ```
            public native fun mul(a: MathInt, b: MathInt): MathInt;
            ```
         */
        addSummary(mathIntModule, "mul") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() intMul call.args[1].asSym() }
            }
        }

        /*
            ```
            public native fun div(a: MathInt, b: MathInt): MathInt;
            ```
         */
        addSummary(mathIntModule, "div") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() intDiv call.args[1].asSym() }
            }
        }

        /*
            ```
            public native fun mod(a: MathInt, b: MathInt): MathInt;
            ```
         */
        addSummary(mathIntModule, "mod") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() intMod call.args[1].asSym() }
            }
        }

        /*
            ```
            public native fun lt(a: MathInt, b: MathInt): bool;
            ```
         */
        addSummary(mathIntModule, "lt") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() lt call.args[1].asSym() }
            }
        }

        /*
            ```
            public native fun le(a: MathInt, b: MathInt): bool;
            ```
         */
        addSummary(mathIntModule, "le") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() le call.args[1].asSym() }
            }
        }

        /*
            ```
            public native fun gt(a: MathInt, b: MathInt): bool;
            ```
         */
        addSummary(mathIntModule, "gt") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() gt call.args[1].asSym() }
            }
        }

        /*
            ```
            public native fun ge(a: MathInt, b: MathInt): bool;
            ```
         */
        addSummary(mathIntModule, "ge") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() ge call.args[1].asSym() }
            }
        }
    }
}
