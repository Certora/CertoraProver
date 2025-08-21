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
import analysis.LTACCmd
import analysis.MutableCommandWithRequiredDecls
import analysis.icfg.InternalSummarizer
import analysis.icfg.SummaryApplicator
import analysis.ip.InternalFunctionStartInfo
import datastructures.stdcollections.*
import evm.*
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMValueTypeDescriptor
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACProgramCombiners.toCore
import java.lang.RuntimeException

/**
 * An instantiation of [InternalSummarizer] where all summaries are just UFs.
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
 */
class SharedPureSummarization(
    val l: List<Pair<QualifiedMethodSignature, Int>>
) : InternalSummarizer<QualifiedMethodSignature, Int>() {

    fun summarize(
        code: CoreTACProgram
    ) = summarizeInternalFunctionLoop(
        code, false
    ).first

    class SummaryApplicationError(val where: CmdPointer, val why: String) : RuntimeException()

    override fun generateSummary(
        internalFunctionStartInfo: InternalFunctionStartInfo,
        selectedSummary: SummarySelection<QualifiedMethodSignature, Int>,
        functionStart: CmdPointer,
        rets: FunctionReturnInformation,
        intermediateCode: CoreTACProgram
    ): CoreTACProgram {
        val args = internalFunctionStartInfo.args.map {
            it.s.asSym()
        }
        val id = selectedSummary.selectedSummary
        val returnTypes = selectedSummary.summaryKey.resType
        val summary = MutableCommandWithRequiredDecls<TACCmd.Simple>()
        summary.extend(TACCmd.Simple.LabelCmd("Start auto-summary for ${internalFunctionStartInfo.methodSignature.prettyPrintFullyQualifiedName()}"))
        if(returnTypes.size != rets.rets.size) {
            throw SummaryApplicationError(
                functionStart,
                "Arity mismatch, have $returnTypes but only ${rets.rets}"
            )
        }
        var index = 0
        val argBackups = mutableListOf<TACSymbol.Var>()

        /**
         * Create fresh variable names to save the value of the parameters, in case the function's return values
         * trample the parameters (we don't expect this to ever actually happen, but better to be careful).
         */
        for((i, a) in internalFunctionStartInfo.args.withIndex()) {
            val backupName = TACKeyword.TMP(Tag.Bit256, "!backupParam!$i")
            argBackups.add(backupName)
            summary.extend(backupName)
            summary.extend(a.s)
            summary.extend(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = backupName,
                    rhs = a.s.asSym()
                )
            )
        }
        for((rt, ret) in returnTypes.zip(rets.rets)) {
            val idPos = index++
            val baseForm = TACExpr.Apply(
                TACBuiltInFunction.NondetFunction(args.size + 2).toTACFunctionSym(),
                args + listOf(
                    idPos.asTACExpr, id.asTACExpr
                ),
                Tag.Bit256
            )
            if(rt !is EVMTypeDescriptor) {
                throw SummaryApplicationError(functionStart, "Got unexpected return type: $rt")
            }
            check(rt is VMValueTypeDescriptor) {
                "Invalid type found for return for at $functionStart ${internalFunctionStartInfo.methodSignature}: $rt"
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
                            o2 = baseForm
                        )
                    }
                }
                is EVMTypeDescriptor.UIntK -> {
                    if(rt.bitwidth == EVM_BITWIDTH256) {
                        baseForm
                    } else {
                        TACExpr.BinOp.Mod(baseForm, MOD_MASK_SIZE(rt.bitwidth).asTACExpr)
                    }
                }
                is EVMTypeDescriptor.EVMContractTypeDescriptor,
                EVMTypeDescriptor.address -> TACExpr.BinOp.Mod(baseForm, MOD_MASK_SIZE(EVM_ADDRESS_SIZE).asTACExpr)
                EVMTypeDescriptor.bool ->
                    TACExpr.BinRel.Eq(baseForm, TACExpr.zeroExpr, Tag.Bool).letIf(ret.s.tag != Tag.Bool) { cond ->
                        TACExpr.TernaryExp.Ite(cond,
                            1.asTACExpr,
                            0.asTACExpr
                        )
                    }
            }
            summary.extend(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = ret.s,
                rhs = retValue
            ))
            summary.extend(ret.s)
        }
        summary.extend(
            TACCmd.Simple.SummaryCmd(
            CommonPureInternalFunction(
                argSymbols = argBackups,
                rets = rets.rets.map { it.s },
                qualifiedMethodSignature = internalFunctionStartInfo.methodSignature
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
