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

@file:kotlinx.serialization.UseSerializers(BigIntegerSerializer::class)
package vc.data

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import analysis.icfg.CallGraphBuilder
import analysis.icfg.Inliner
import analysis.pta.abi.DecoderAnalysis
import datastructures.stdcollections.*
import instrumentation.calls.CallConvention
import tac.MetaMap
import utils.*
import java.math.BigInteger

@GenerateRemapper
@KSerializable
data class CallSummary(
    override val toVar: TACSymbol,
    override val valueVar: TACSymbol,
    override val gasVar: TACSymbol,
    override val inOffset: TACSymbol,
    override val inSize: TACSymbol,
    override val inBase: TACSymbol.Var,
    override val outOffset: TACSymbol,
    override val outSize: TACSymbol,
    override val outBase: TACSymbol.Var,
    override val callType: TACCallType,
    val callTarget: Set<CallGraphBuilder.CalledContract>,
    val sigResolution: Set<BigInteger?>, // A null value in the set means the call is "resolved" to the fallback
    val symbolicSigResolution: DecoderAnalysis.BufferAccessPath?,
    val callConvention: CallConvention,
    override val origCallcore: TACCmd.Simple.CallCore?,
    @GeneratedBy(Allocator.Id.CALL_SUMMARIES, source = true)
    override val summaryId: Int = Allocator.getFreshId(Allocator.Id.CALL_SUMMARIES),
    val cannotBeInlined: Inliner.IllegalInliningReason?,
) : TACSummary, ICallCoreSummary, RemapperEntity<CallSummary> {

    init {
        if (inSize == TACSymbol.Zero) {
            check(sigResolution.size == 1 && sigResolution.single() == null) {
                "inSize of 0 means we're going to call the fallback (or receive) so the sigResolution should be a singleton with 'null'. Got $sigResolution"
            }
        }
        check(callTarget.isNotEmpty()){"The list of call targets may not be empty."}
    }

    val symbols: Set<TACSymbol>
        get() = setOf(
            toVar,
            valueVar,
            gasVar,
            inOffset,
            inSize,
            inBase,
            outOffset,
            outSize,
            outBase
        )

    override val annotationDesc: String
        get() = "Call summary: Calling $toVar ${callTarget.map { "($it)" }} " +
            "with selector(s) ${sigResolution.map { it?.toString(16) }} with value $valueVar " +
            "and input from $inBase $inOffset sz $inSize and returning output to $outBase $outOffset sz $outSize " +
            "and encoded arguments ${callConvention.input.encodedArguments}"

    override val variables: Set<TACSymbol.Var>
        get() {
            return symbols.filterIsInstance<TACSymbol.Var>().toSet() + callConvention.input.support + listOfNotNull(
                origCallcore?.to as? TACSymbol.Var,
                callConvention.rawOut.base,
                callConvention.rawOut.size as? TACSymbol.Var,
                callConvention.rawOut.offset as? TACSymbol.Var
            ) + symbolicSigResolution?.referencedVariables().orEmpty()
        }

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CallSummary {
        fun TACSymbol.f(): TACSymbol = when (this) {
            is TACSymbol.Var -> f(this)
            is TACSymbol.Const -> this
        }

        return CallSummary(
            toVar = toVar.f(),
            valueVar = valueVar.f(),
            gasVar = gasVar.f(),
            inOffset = inOffset.f(),
            inSize = inSize.f(),
            inBase = inBase.f() as TACSymbol.Var,
            outOffset = outOffset.f(),
            outSize = outSize.f(),
            outBase = outBase.f() as TACSymbol.Var,
            callType = callType,
            callTarget = callTarget.mapToSet { it.transformSymbols(f)},
            sigResolution = sigResolution,
            symbolicSigResolution = symbolicSigResolution?.transformVariables(f),
            callConvention = callConvention.transformSymbols(f),
            origCallcore = origCallcore?.let { origCore -> origCore.transformSymbols(f) as TACCmd.Simple.CallCore },
            summaryId = summaryId,
            cannotBeInlined = this.cannotBeInlined
        )
    }

    override fun withMeta(f: (MetaMap) -> MetaMap): CallSummary {
        fun TACSymbol.f(): TACSymbol = when (this) {
            is TACSymbol.Var -> this.withMeta(f)
            is TACSymbol.Const -> this
        }
        return CallSummary(
            toVar = toVar.f(),
            valueVar = valueVar.f(),
            gasVar = gasVar.f(),
            inOffset = inOffset.f(),
            inSize = inSize.f(),
            inBase = inBase.f() as TACSymbol.Var,
            outOffset = outOffset.f(),
            outSize = outSize.f(),
            outBase = outBase.f() as TACSymbol.Var,
            callType = callType,
            callTarget = callTarget.mapToSet { it.transformSymbols {
                it.withMeta(f)
            }},
            sigResolution = sigResolution,
            callConvention = callConvention.transformSymbols { it.withMeta(f) },
            origCallcore = origCallcore?.let { origCore -> origCore.transformSymbols { it.withMeta(f) }.mapMeta(f) as TACCmd.Simple.CallCore },
            summaryId = summaryId,
            cannotBeInlined = cannotBeInlined,
            symbolicSigResolution = symbolicSigResolution?.transformVariables { it.withMeta(f) }
        )
    }
}


@KSerializable
data class ReturnSummary(
    val ret: TACCmd.Simple
): TACSummary {
    init {
        check (ret is TACCmd.Simple.ReturnCmd || ret is TACCmd.Simple.ReturnSymCmd || ret is TACCmd.Simple.RevertCmd) { "Return summary must get a return/revert command, got $ret" }
    }

    override val variables: Set<TACSymbol.Var>
        get() = ret.getFreeVarsOfRhs()

    override val annotationDesc: String
        get() = "Return summary: $ret"

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): TACSummary =
        ReturnSummary(ret = object : DefaultTACCmdMapper() {
            override fun mapVar(t: TACSymbol.Var): TACSymbol.Var = super.mapVar(f(t))
        }.map(ret))

    override fun withMeta(f: (MetaMap) -> MetaMap): ReturnSummary = ReturnSummary(ret = ret.mapMeta(f))
}
