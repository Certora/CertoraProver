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

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import config.*
import move.ConstantStringPropagator.MESSAGE_VAR
import move.ConstantStringPropagator.MessageVar
import tac.*
import tac.generation.*
import utils.*
import vc.data.*

/**
    Provides implementations for the CVLM API functions.
 */
object CvlmApi {
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
    private val internalAssertsModule = MoveModuleName(cvlmAddr, "internal_asserts")
    private val nondetModule = MoveModuleName(cvlmAddr, "nondet")
    private val ghostModule = MoveModuleName(cvlmAddr, "ghost")
    private val conversionsModule = MoveModuleName(cvlmAddr, "conversions")
    private val mathIntModule = MoveModuleName(cvlmAddr, "math_int")
    private val functionModule = MoveModuleName(cvlmAddr, "function")

    private val mathIntTypeName = MoveDatatypeName(mathIntModule, "MathInt")

    fun maybeShadowType(type: MoveType.Struct) = when (type.name) {
        mathIntTypeName -> MoveType.MathInt
        else -> null
    }

    init {
        addAssertsSummaries()
        addInternalAssertsSummaries()
        addNondetSummaries()
        addGhostSummaries()
        addConversionsSummaries()
        addMathIntSummaries()
        addFunctionSummaries()
    }

    private fun addAssertsSummaries() {
        /*
            ```
            public native fun cvlm_assert(cond: bool);
            ```
         */
        addSummary(assertsModule, "cvlm_assert") { call ->
            singleBlockSummary(call) {
                mergeMany(
                    MoveCallTrace.annotateUserAssert(
                        isSatisfy = false,
                        condition = call.args[0],
                        messageText = call.displaySource,
                        range = call.range
                    ),
                    TACCmd.Simple.AssertCmd(
                        call.args[0],
                        "cvlm_assert",
                        MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT)
                    ).withDecls()
                )
            }
        }

        /*
            ```
            public native fun cvlm_assert_msg(cond: bool, msg: vector<u8>);
            ```
         */
        addSummary(assertsModule, "cvlm_assert_msg") { call ->
            singleBlockSummary(call) {
                mergeMany(
                    MoveCallTrace.annotateUserAssert(
                        isSatisfy = false,
                        condition = call.args[0],
                        messageVar = call.args[1],
                        messageText = call.displaySource,
                        range = call.range
                    ),
                    TACCmd.Simple.AssertCmd(
                        call.args[0],
                        "cvlm_assert_msg (could not extract message)", // message will be replaced later
                        MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) + (MESSAGE_VAR to MessageVar(call.args[1]))
                    ).withDecls(),
                    TACCmd.Simple.AnnotationCmd(MESSAGE_VAR, MessageVar(call.args[1])).withDecls()
                )
            }
        }

        /*
            ```
            public native fun cvlm_assume(cond: bool);
            ```
         */
        addSummary(assertsModule, "cvlm_assume") { call ->
            singleBlockSummary(call) {
                mergeMany(
                    MoveCallTrace.annotateUserAssume(
                        messageText = call.displaySource,
                        range = call.range
                    ),
                    TACCmd.Simple.AssumeCmd(
                        call.args[0],
                        "cvlm_assume"
                    ).withDecls()
                )
            }
        }

        /*
            ```
            public native fun cvlm_assume_msg(cond: bool, msg: vector<u8>);
            ```
         */
        addSummary(assertsModule, "cvlm_assume_msg") { call ->
            singleBlockSummary(call) {
                mergeMany(
                    MoveCallTrace.annotateUserAssume(
                        messageVar = call.args[1],
                        messageText = call.displaySource,
                        range = call.range
                    ),
                    TACCmd.Simple.AssumeCmd(
                        call.args[0],
                        "cvlm_assume_msg (could not extract message)",
                        MetaMap(MESSAGE_VAR to MessageVar(call.args[1]))
                    ).withDecls(),
                    TACCmd.Simple.AnnotationCmd(MESSAGE_VAR, MessageVar(call.args[1])).withDecls()
                )
            }
        }

        /*
            ```
            public native fun cvlm_satisfy(cond: bool);
            ```
         */
        addSummary(assertsModule, "cvlm_satisfy") { call ->
            singleBlockSummary(call) {
                TXF { not(call.args[0]) }.letVar(Tag.Bool) { cond ->
                    mergeMany(
                        MoveCallTrace.annotateUserAssert(
                            isSatisfy = true,
                            condition = call.args[0],
                            messageText = call.displaySource,
                            range = call.range
                        ),
                        TACCmd.Simple.AssertCmd(
                            cond.s,
                            "cvlm_satisfy",
                            MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) + (TACMeta.SATISFY_ID to allocSatisfyId())
                        ).withDecls()
                    )
                }
            }
        }

        /*
            ```
            public native fun cvlm_satisfy_msg(cond: bool, msg: vector<u8>);
            ```
         */
        addSummary(assertsModule, "cvlm_satisfy_msg") { call ->
            singleBlockSummary(call) {
                TXF { not(call.args[0]) }.letVar(Tag.Bool) { cond ->
                    mergeMany(
                        MoveCallTrace.annotateUserAssert(
                            isSatisfy = true,
                            condition = call.args[0],
                            messageVar = call.args[1],
                            messageText = call.displaySource,
                            range = call.range
                        ),
                        TACCmd.Simple.AssertCmd(
                            cond.s,
                            "cvlm_satisfy_msg (could not extract message)",
                            MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) +
                                (TACMeta.SATISFY_ID to allocSatisfyId()) +
                                (MESSAGE_VAR to MessageVar(call.args[1]))
                        ).withDecls(),
                        TACCmd.Simple.AnnotationCmd(MESSAGE_VAR, MessageVar(call.args[1])).withDecls()
                    )
                }
            }
        }
    }

    private fun addInternalAssertsSummaries() {
        /*
            ```
            public native fun cvlm_internal_assert(cond: bool);
            ```
            This is an assertion that does not appear as a user assertion; used mainly for testing.
         */
        addSummary(internalAssertsModule, "cvlm_internal_assert") { call ->
            singleBlockSummary(call) {
                mergeMany(
                    TACCmd.Simple.AssertCmd(
                        call.args[0],
                        "cvlm_internal_assert"
                    ).withDecls()
                )
            }
        }

        /*
            ```
            public native fun cvlm_internal_assume(cond: bool);
            ```
            This is an assume that does not appear as a user assume; used mainly for testing.
         */
        addSummary(internalAssertsModule, "cvlm_internal_assume") { call ->
            singleBlockSummary(call) {
                mergeMany(
                    TACCmd.Simple.AssumeCmd(
                        call.args[0],
                        "cvlm_internal_assume"
                    ).withDecls()
                )
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
            public native fun to_u256(value: MathInt): u256;
            ```
            Converts a MathInt value to a u256.
         */
        addSummary(mathIntModule, "to_u256") { call ->
            singleBlockSummary(call) {
                val v = call.args[0].asSym()
                mergeMany(
                    Trap.assert("MathInt value in u256 range") {
                        (v ge 0.asTACExpr(Tag.Int)) and (v le Tag.Bit256.maxUnsigned.asTACExpr(Tag.Int))
                    },
                    assign(call.returns[0]) { safeMathNarrow(v, Tag.Bit256) }
                )
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
            public native fun pow(a: MathInt, b: MathInt): MathInt;
            ```
         */
        addSummary(mathIntModule, "pow") { call ->
            singleBlockSummary(call) {
                assign(call.returns[0]) { call.args[0].asSym() intPow call.args[1].asSym() }
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

    /**
        Summarizes a call that extracts info from the name of a parametric function target:
        ```
        public native fun name(function: Function): vector<u8>;
        public native fun module_name(function: Function): vector<u8>;
        public native fun module_address(function: Function): address;
        ```
     */
    context(SummarizationContext)
    private fun summarizeFunctionInfo(
        call: MoveCall,
        resultType: MoveType,
        extract: (MoveFunctionName) -> TACSymbol
    ) = singleBlockSummary(call) {
        val targetNames = parametricTargets.mapValues { (_, func) ->
            extract(func.name)
        }
        val functionSelector = call.args[0]
        assign(call.returns[0]) {
            targetNames.entries.fold(
                TACExpr.Unconstrained(resultType.toTag()) as TACExpr
            ) { acc, (id, name) ->
                ite(
                    id.asTACExpr eq functionSelector,
                    name,
                    acc
                )
            }
        }
    }

    private fun addFunctionSummaries() {
        /*
            ```
            public native fun name(function: Function): vector<u8>;
            ```
         */
        addSummary(functionModule, "name") { call ->
            summarizeFunctionInfo(call, MoveType.Vector(MoveType.U8)) {
                MoveToTAC.packString(it.simpleName)
            }
        }

        /*
            ```
            public native fun module_name(function: Function): vector<u8>;
            ```
         */
        addSummary(functionModule, "module_name") { call ->
            summarizeFunctionInfo(call, MoveType.Vector(MoveType.U8)) {
                MoveToTAC.packString(it.module.name)
            }
        }

        /*
            ```
            public native fun module_address(function: Function): vector<u8>;
            ```
         */
        addSummary(functionModule, "module_address") { call ->
            summarizeFunctionInfo(call, MoveType.Address) {
                TACSymbol.Const(it.module.address, MoveType.Address.toTag())
            }
        }
    }
}
