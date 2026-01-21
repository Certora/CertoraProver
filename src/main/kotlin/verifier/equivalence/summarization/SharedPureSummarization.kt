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

package verifier.equivalence.summarization

import allocator.Allocator
import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import analysis.LTACCmd
import analysis.MutableCommandWithRequiredDecls
import analysis.icfg.GenericInternalSummarizer
import analysis.icfg.InternalFunctionAbstraction
import analysis.icfg.SummaryApplicator
import analysis.ip.InternalFunctionExitAnnot
import analysis.ip.InternalFunctionStartAnnot
import datastructures.stdcollections.*
import evm.*
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import spec.cvlast.typedescriptors.VMValueTypeDescriptor
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACProgramCombiners.toCore

/**
 * An instantiation of [GenericInternalSummarizer] where all summaries are just UFs.
 *
 * more precisely, for each summarized function signature, we give some unique `id`. Then, for some
 * application of that function with arguments `arg1, arg2, ...` we take the return value at position
 * `i` to be: `uf(arg1, arg2, ..., i, id)`
 *
 * Thus any application of the same function with the same arguments will yield the same results.
 *
 * The type bounds *are* assumed on the returned value. In addition, we insert a [CommonPureInternalFunction]
 * summary, which is interpreted by the [verifier.equivalence.tracing.BufferTraceInstrumentation] for
 * inclusion in the tracing.
 *
 * The actual extraction of argument data/encoding of return data is abstracted via [vtable], which extends
 * [InternalFunctionAbstraction] (which is inherited by [GenericInternalSummarizer]). This effectively lets us have
 * a single class that delegates (literally) the details of argument/return data to [vtable] (hence the name).
 */
class SharedPureSummarization<Start: InternalFunctionStartAnnot, End: InternalFunctionExitAnnot, ARG, RET_S, RET_D, T>(
    val l: List<Pair<QualifiedMethodSignature, Int>>,
    private val vtable: SummarizationImpl<Start, End, ARG, RET_S, RET_D, T>
) : GenericInternalSummarizer<QualifiedMethodSignature, Int, Start,  End, ARG, RET_S, RET_D>(), InternalFunctionAbstraction<Start, End, ARG, RET_S, RET_D> by vtable {

    fun summarize(
        code: CoreTACProgram
    ) = summarizeInternalFunctionLoop(
        code, false
    ).first


    class SummaryApplicationError(val where: CmdPointer, val why: String) : RuntimeException()

    /**
     * An extension of the "internal function abstractions" of [InternalFunctionAbstraction] with extra operations
     * to move internal function arguments into scalars ([bindArgs]), extract return data and their types ([projectOut])
     * and then move that return data into it's final locations ([bindOut]).
     *
     * Q: Doesn't this have weird overlap with [verifier.equivalence.summarization.PureFunctionExtraction.CallingConvention]?
     * A: At first glance yes, but they actually go in "different directions". The calling convention is used for checking equivalence,
     * where we are interested in *writing* the arguments to a function to symbolic (consistent). Similarly, we need to *read*
     * the return values to ensure functions have equivalent outputs.
     *
     * In the summarization context, we want the data to go in the different direction, we want to *read* the argument data
     * (to pass to the UF) and then *write* the return values returned from that UF.
     *
     * We could of course have folded all that logic into the calling convention, but that would require constructing
     * calling conventions within the summarization process AND would have been an inappropriate mixing of roles.
     */
    interface SummarizationImpl<Start : InternalFunctionStartAnnot, End : InternalFunctionExitAnnot, ARG_DATA, RET_SHAPE, RET_DATA, T> : InternalFunctionAbstraction<Start, End, ARG_DATA, RET_SHAPE, RET_DATA> {
        fun bindArgs(s: ARG_DATA) : Pair<List<TACSymbol>, CommandWithRequiredDecls<TACCmd.Simple>>

        fun projectOut(r: RET_DATA, sig: QualifiedMethodSignature) : List<Pair<T, VMTypeDescriptor>>
        fun bindOut(r: T, data: TACSymbol.Var): CommandWithRequiredDecls<TACCmd.Simple>
    }

    override fun generateSummary(
        internalFunctionStartInfo: GenericInternalFunctionStartInfo<ARG>,
        selectedSummary: SummarySelection<QualifiedMethodSignature, Int>,
        functionStart: CmdPointer,
        rets: RET_D,
        intermediateCode: CoreTACProgram
    ): CoreTACProgram {
        val id = selectedSummary.selectedSummary
        val summary = MutableCommandWithRequiredDecls<TACCmd.Simple>()
        val (argScalars, argBinding) = vtable.bindArgs(internalFunctionStartInfo.args)
        summary.extend(TACCmd.Simple.LabelCmd("Start auto-summary for ${selectedSummary.summaryKey.prettyPrintFullyQualifiedName()}"))
        summary.extend(argBinding)
        var index = 0
        val argBackups = mutableListOf<TACSymbol.Var>()

        /**
         * Create fresh variable names to save the value of the parameters, in case the function's return values
         * trample the parameters (we don't expect this to ever actually happen, but better to be careful).
         */
        for((i, a) in argScalars.withIndex()) {
            val backupName = TACKeyword.TMP(Tag.Bit256, "!backupParam!$i")
            argBackups.add(backupName)
            summary.extend(backupName)
            summary.extend(a)
            summary.extend(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = backupName,
                    rhs = a.asSym()
                )
            )
        }
        val out = vtable.projectOut(rets, selectedSummary.summaryKey)
        val retVars = mutableListOf<TACSymbol.Var>()
        for((start, rt) in out) {
            val idPos = index++
            val baseForm = TACExpr.Apply(
                TACBuiltInFunction.NondetFunction(argBackups.size + 2).toTACFunctionSym(),
                argBackups.map { it.asSym() } + listOf(
                    idPos.asTACExpr, id.asTACExpr
                ),
                Tag.Bit256
            )
            if(rt !is EVMTypeDescriptor) {
                throw SummaryApplicationError(functionStart, "Invalid type for summary: $rt")
            }
            check(rt is VMValueTypeDescriptor) {
                "Invalid type found for return for ${selectedSummary.summaryKey}: $rt"
            }
            /**
             * Clean the values returned from the underlying UF, which covers the whole uint256 range.
             */
            val retValue = when(rt) {
                is EVMTypeDescriptor.FunctionDescriptor,
                is EVMTypeDescriptor.PackedBytes,
                is EVMTypeDescriptor.StringType,
                is EVMTypeDescriptor.StaticArrayDescriptor,
                is EVMTypeDescriptor.DynamicArrayDescriptor,
                is EVMTypeDescriptor.EVMMappingDescriptor,
                is EVMTypeDescriptor.EVMStructDescriptor -> `impossible!`
                is EVMTypeDescriptor.UserDefinedValueType,
                is EVMTypeDescriptor.EVMEnumDescriptor -> throw SummaryApplicationError(
                    functionStart,
                    "Cannot handle type $rt"
                )
                is EVMTypeDescriptor.BytesK -> {
                    if(rt.bytewidth == EVM_BYTES_IN_A_WORD) {
                        baseForm
                    } else {
                        val lowerBits = EVM_BITWIDTH256 - rt.bitwidth
                        val mask = MASK_SIZE(EVM_BITWIDTH256).andNot(MASK_SIZE(lowerBits))
                        TACExpr.BinOp.BWAnd(baseForm, mask.asTACExpr, Tag.Bit256)
                    }
                }
                is EVMTypeDescriptor.IntK -> {
                    if(rt.bitwidth == EVM_BITWIDTH256) {
                        baseForm
                    } else {
                        TACExpr.BinOp.SignExtend(
                            o1 = ((rt.bitwidth / EVM_BYTE_SIZE_INT) - 1).asTACExpr,
                            o2 = baseForm,
                            Tag.Bit256
                        )
                    }
                }
                is EVMTypeDescriptor.UIntK -> {
                    if(rt.bitwidth == EVM_BITWIDTH256) {
                        baseForm
                    } else {
                        TACExpr.BinOp.Mod(baseForm, MOD_MASK_SIZE(rt.bitwidth).asTACExpr, Tag.Bit256)
                    }
                }
                is EVMTypeDescriptor.EVMContractTypeDescriptor,
                EVMTypeDescriptor.address -> TACExpr.BinOp.Mod(baseForm, MOD_MASK_SIZE(EVM_ADDRESS_SIZE).asTACExpr, Tag.Bit256)
                EVMTypeDescriptor.bool ->
                    TACExpr.BinRel.Eq(baseForm, TACExpr.zeroExpr, Tag.Bool)
            }
            val retVariable = TACSymbol.Var(
                "ReturnVariable", retValue.tagAssumeChecked
            ).toUnique("!")
            summary.extend(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = retVariable,
                rhs = retValue
            ))
            retVars.add(retVariable)
            summary.extend(retVariable)
            summary.extend(vtable.bindOut(start, retVariable))
        }
        summary.extend(TACCmd.Simple.SummaryCmd(
            CommonPureInternalFunction(
                argSymbols = argBackups,
                rets = retVars,
                qualifiedMethodSignature = selectedSummary.summaryKey
            )
        ))
        summary.extend(TACCmd.Simple.LabelCmd("End summary"))
        return summary.toCommandWithRequiredDecls().toCore("summary for $id", Allocator.getNBId())

    }

    override fun handleExplicitSummary(
        where: CmdPointer,
        explicit: InternalCallSummary,
        selectedSummary: SummarySelection<QualifiedMethodSignature, Int>,
        enclosingProgram: CoreTACProgram
    ): SummaryApplicator {
        throw UnsupportedOperationException("We should never see InternalCallSummaries in the equivalence checker workflow")
    }

    override fun selectSummary(sig: QualifiedMethodSignature): SummarySelection<QualifiedMethodSignature, Int>? {
        return this.l.firstNotNullOfOrNull { (q, ind) ->
            if(q.matchesNameAndParams(sig)) {
                SummarySelection(q, ind)
            } else {
                null
            }
        }
    }

    /**
     * We don't do staged applications, so this is never relevant, just say false
     */
    override fun alreadyHandled(
        summarySelection: SummarySelection<QualifiedMethodSignature, Int>,
        where: LTACCmd
    ): Boolean {
        return false
    }
}
