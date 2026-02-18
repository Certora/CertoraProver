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

package instrumentation.transformers

import analysis.alloc.ReturnBufferAnalysis.ConstantReturnBufferAllocComplete
import analysis.icfg.Inliner
import analysis.icfg.SummaryStack
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import analysis.ip.InternalFunctionHint
import analysis.ip.SafeCastingAnnotator
import analysis.ip.UncheckedOverflowAnnotator
import analysis.pta.ITERATION_VARIABLE_BOUND
import analysis.pta.abi.ABIAnnotator
import analysis.pta.abi.ABIDecodeComplete
import analysis.pta.abi.ABIEncodeComplete
import datastructures.stdcollections.*
import sbf.tac.DEBUG_EXTERNAL_CALL
import sbf.tac.DEBUG_INLINED_FUNC_END
import sbf.tac.DEBUG_INLINED_FUNC_END_FROM_ANNOT
import sbf.tac.DEBUG_INLINED_FUNC_START
import sbf.tac.DEBUG_INLINED_FUNC_START_FROM_ANNOT
import sbf.tac.DEBUG_PTA_SPLIT_OR_MERGE
import sbf.tac.DEBUG_UNREACHABLE_CODE
import sbf.tac.SBF_INLINED_FUNCTION_END
import sbf.tac.SBF_INLINED_FUNCTION_START
import spec.CVLCompiler.Companion.TraceMeta
import tac.MetaKey
import vc.data.*
import vc.data.TACCmd
import verifier.equivalence.AbstractRuleGeneration
import verifier.equivalence.LoopEquivalence
import verifier.equivalence.tracing.BufferTraceInstrumentation
import verifier.equivalence.tracing.ReadSiteInstrumentationRecord
import wasm.impCfg.WASM_HOST_FUNC_SUMMARY_START
import wasm.impCfg.WASM_INLINED_FUNC_START
import wasm.impCfg.WASM_SDK_FUNC_SUMMARY_START

/**
 * Removes [TACCmd.Simple.AnnotationCmd] and other commands which are unnecessary at the point in the pipeline
 * where it is called (the start of the optimization pass).
 *
 * Note we really only care for [TACCmd.Simple.AnnotationCmd] with [MetaKey]s that implement [WithSupport], because
 * they become sinks to cone-of-influence type optimizations.
 */
object AnnotationRemovers {

    private fun eraseInDestructiveMode(cmd: TACCmd.Simple) =
        when (cmd) {
            is TACCmd.Simple.JumpdestCmd,
            is TACCmd.Simple.LogCmd,
            is TACCmd.Simple.ReturnCmd,
            is TACCmd.Simple.ReturnSymCmd,
            is TACCmd.Simple.RevertCmd -> true

            else -> false
        }

    private val alwaysEraseAnnotations = setOf(
        SafeCastingAnnotator.CastingKey,
        UncheckedOverflowAnnotator.UncheckedOverflowKey
    )

    private val neverEraseAnnotations = setOf(
        DYNSET_DESTRUCTIVEOPTIMIZATIONS,
    )

    private val eraseInDestructiveModeAnnotations = setOf(
        TACMeta.SNIPPET,
        TraceMeta.VariableDeclaration.META_KEY,
        TraceMeta.ValueTraversal.META_KEY,
        INTERNAL_FUNC_START,
        TraceMeta.ExternalArg.META_KEY,
        INTERNAL_FUNC_EXIT,
        InternalFunctionHint.META_KEY,
        SummaryStack.START_EXTERNAL_SUMMARY,
        Inliner.CallStack.STACK_PUSH,
        LoopEquivalence.KeepAliveInt.META_KEY,
        ReadSiteInstrumentationRecord.META_KEY,
        BufferTraceInstrumentation.TraceIndexMarker.META_KEY,
        BufferTraceInstrumentation.CallEvent.META_KEY,
        ConstantReturnBufferAllocComplete.META_KEY,
        ITERATION_VARIABLE_BOUND,
        SBF_INLINED_FUNCTION_START,
        SBF_INLINED_FUNCTION_END,
        SummaryStack.END_EXTERNAL_SUMMARY,
        SummaryStack.END_INTERNAL_SUMMARY,
        ABIDecodeComplete.META_KEY,
        ABIEncodeComplete.META_KEY,
        ABIAnnotator.REGION_START,
        ABIAnnotator.REGION_END,
        AbstractRuleGeneration.EnvironmentRecord.META_KEY,
        DEBUG_INLINED_FUNC_START_FROM_ANNOT,
        DEBUG_INLINED_FUNC_END_FROM_ANNOT,
        DEBUG_INLINED_FUNC_START,
        DEBUG_INLINED_FUNC_END,
        DEBUG_UNREACHABLE_CODE,
        DEBUG_EXTERNAL_CALL,
        DEBUG_PTA_SPLIT_OR_MERGE,
        sbf.tac.UNSUPPORTED,
        WASM_INLINED_FUNC_START,
        WASM_SDK_FUNC_SUMMARY_START,
        WASM_HOST_FUNC_SUMMARY_START,
    )


    fun rewrite(ctp: CoreTACProgram): CoreTACProgram {
        return ctp.patching { patcher ->
            val unknownMetas = mutableSetOf<MetaKey<*>>()
            ctp.ltacStream()
                .filter { (_, cmd) ->
                    if (ctp.destructiveOptimizations && eraseInDestructiveMode(cmd)) {
                        return@filter true
                    }
                    if (cmd !is TACCmd.Simple.AnnotationCmd) {
                        return@filter false
                    }
                    val key = cmd.annot.k
                    when {
                        key in alwaysEraseAnnotations -> true
                        !ctp.destructiveOptimizations -> false
                        key in neverEraseAnnotations -> false
                        key in eraseInDestructiveModeAnnotations -> true
                        else -> {
                            if (cmd.mentionedVariables.isNotEmpty()) {
                                unknownMetas += key
                            }
                            false
                        }
                    }
                }
                .forEach {
                    patcher.delete(it.ptr)
                }

            if (unknownMetas.isNotEmpty()) {
                error(
                    "Got annotations we don't know what to do with in destructive mode!! Please review" +
                        "and decide where to place them in ${this.javaClass.simpleName} : $unknownMetas"
                )
            }
        }
    }
}

