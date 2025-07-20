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

package verifier.equivalence

import analysis.CommandWithRequiredDecls
import analysis.icfg.Inliner
import instrumentation.calls.CalldataEncoding
import scene.TACMethod
import tac.CallId
import tac.Tag
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten
import vc.data.TACSymbol.Companion.atSync
import vc.data.tacexprutil.ExprUnfolder
import verifier.equivalence.data.EquivalenceQueryContext
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.ProgramContext
import verifier.equivalence.data.CallableProgram
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.`=`

/**
 * [AbstractRuleGeneration] which handles checking whole methods, as represented by [TACMethod].
 * As the name suggests, this is done by sending identical calldata to both versions
 * of the external method.
 */
class CalldataRuleGenerator(queryContext: EquivalenceQueryContext) : AbstractRuleGeneration<TACMethod>(queryContext) {
    /**
     * The arbitrary calldata sent to the two methods.
     */
    private val theCalldata = TACSymbol.Var("certoraEquivInputCalldata", Tag.ByteMap)
    private val theCalldataSize = TACSymbol.Var("certoraEquivInputCalldataSize", Tag.Bit256)


    override fun setupCode(): CommandWithRequiredDecls<TACCmd.Simple> {
        val dummyIdx = TACKeyword.TMP(Tag.Bit256, "!initIdx")

        return (theCalldata `=` {
            TACExpr.MapDefinition(
                datastructures.stdcollections.listOf(dummyIdx.asSym()),
                Ite(
                    Lt(dummyIdx.asSym(), theCalldataSize.asSym()),
                    TACExpr.Unconstrained(Tag.Bit256),
                    TACExpr.zeroExpr
                ), Tag.ByteMap
            )
        }).merge(theCalldata, theCalldataSize)
    }

    override fun <T : MethodMarker> annotateInOut(
        inlined: CoreTACProgram,
        callingConv: TACMethod,
        context: ProgramContext<T>,
        callId: Int
    ): CoreTACProgram {
        return inlined.patching { patcher ->
            this.analysisCache.graph.sinks.forEach(
                Inliner.CallStack.stackPopper(
                    patcher, Inliner.CallStack.PopRecord(
                        callee = callingConv.toRef(),
                        callId
                    )
                )
            )
            this.analysisCache.graph.roots.forEach(
                Inliner.CallStack.stackPusher(
                    patcher, Inliner.CallStack.PushRecord(
                        callee = callingConv.toRef(),
                        calleeId = callId,
                        summary = null,
                        isNoRevert = false,
                        convention = Inliner.CallConventionType.Serialization
                    )
                )
            )
        }
    }

    override fun <T : MethodMarker> generateInput(
        p: CallableProgram<T, TACMethod>,
        context: ProgramContext<T>,
        callId: CallId
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        ExprUnfolder.unfoldPlusOneCmd("sighash", TACExprFactoryExtensions.run {
            (theCalldata.get(0) shiftRLog (256 - 32)) eq p.callingInfo.sigHash!!.n
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "set sighash")
        }

        val origMethod = p.callingInfo
        return (TACKeyword.CALLDATA.toVar().atSync(callId) `=` theCalldata) andThen
            (TACKeyword.CALLDATASIZE.toVar().atSync(callId) `=` theCalldataSize) andThen
            (origMethod.calldataEncoding as CalldataEncoding).byteOffsetToScalar.map { (range, v) ->
                v.at(callId) `=` {
                    Select(theCalldata.asSym(), range.from.asTACExpr)
                }
            }.flatten()
    }

}
