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

package verifier.equivalence.tracing

import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import datastructures.stdcollections.*
import tac.Tag

/**
 * Handles the case where data is put into memory in some callee N,
 * used as the return buffer back into some caller M, which is then copied
 * back into M's memory. Unlike calldata, the entirety of returndata is usually first copied into
 * memory, and then sub-regions of that memory are copied elsewhere (usually to new allocations).
 *
 * However, this leads to imprecision. Consider the basic case where the callee returns a simple bytes array b of
 * length l.
 * When this gets copied into the caller's memory, we first see in the callee:
 * ```
 * to_ret = fp
 * m[to_ret] = 0x20
 * m[to_ret + 0x20] = l
 * m[to_ret + 0x40:l] = m[b + 0x20:l]
 * m[to_ret + 0x40 + l] = 0
 * return m[to_ret:0x40 + ((l + 31) & ~31)]
 * ```
 *
 * In the caller, we see:
 * ```
 * retdata = fp
 * m[retdata:l] = returndata[0:l]
 * fp = ...
 * // parse and validate returndata
 * b_copy = fp
 * fp = fp + 0x20 + l
 * m[b_copy + 0x20:l] = m[retdata + X:l]
 * ```
 *
 * Where X is some offset within retdata.
 *
 * If we didn't handle this case, the above would look like some copy from within the `retdata` buffer, rather than being
 * just a copy of `b`. However, we can in fact treat `b_copy` as being a copy of `b`, by correlating it's contents with the range
 * `fp + 0x40` in the callee.
 *
 * To overcome this, we heuristically identified copies out of memory that *look* like copies out of returndata segments.
 * Recall that via the [verifier.equivalence.instrumentation.ReturnCopyCorrelation], we can correlate the returndata segments
 * to some memory segment in the callee.
 * Consider some such mcopy C. We attach to C an instance of this [ReturnBufferCopyTracker]. This records (via yet more instrumentation)
 * whether the data being copied *from* is in fact entirely defined by some previous returndata copy, which is
 * known to occur at [candidateReturnWrite]. This determination is recorded in the [isReturnDataCopyVar].
 * We do this by recognizing when a defining write for C occurs at [candidateReturnWrite], and checking whether the returndata
 * copy completely the eventual read of C. If it does, we set [isReturnDataCopyVar] to 1. We do this check via prophecy vars, as
 * per usual.
 *
 * We also record the offset of C from the start of the returndata copy, again using prophecy variables. This term, `X` in
 * our example, is recorded into [offsetFromReturnDataCopyDest].
 *
 * If there are any other writes that overlap with the buffer read by C, [isReturnDataCopyVar] is set back to 0.
 *
 * At the copy from C, we check whether [isReturnDataCopyVar] is set to 1. If it is, we know that this copy is actually copying
 * some subrange of the callee's memory. Recall that via [EnvironmentTranslationTracking.translatedOffset] we have a variable which translates
 * an offset from returndata into the caller's memory. [EnvironmentTranslationTracking] inserts instrumentation which constrains
 * that variable to be equal to `to_ret + env_source`, which in our example is 0. In other words, we know where the *start* of
 * retdata exists in the callee's memory. We have also recorded `X` (in [offsetFromReturnDataCopyDest]); we can thus
 * require that the magic variable [localMemCopyTranslatedToCallee] = [offsetFromReturnDataCopyDest] + [returnDataCopyInCallee].
 * We then use this variable as a synthetic long read in the callee's memory space. Simply put, we have
 * translated a copy out of the caller's memory to a capy out of the callee's memory; skipping the intermediate serialization
 * and copies via returndata.
 *
 * However, recall that we treat mcopy's as longreads so we can compute their fingerprint for inclusion in other buffer's traces.
 * For mcopy's like C, we have a "regular" longread, which treats it as a copy out of the caller's memory,
 * and a synthetic long read in the callee's memory, call it C_l. If [isReturnDataCopyVar] is 1 at C, we use the fingerprint/copytracking data
 * of C_l. Otherwise, we fall back on the "regular" definition of C.
 */
internal class ReturnBufferCopyTracker(
    val candidateReturnWrite: CmdPointer,
    val returnDataCopyInCallee: TACSymbol.Var,
    val isReturnDataCopyVar: TACSymbol.Var,
    val localMemCopyTranslatedToCallee: TACSymbol.Var,
    val offsetFromReturnDataCopyDest: TACSymbol.Var
) : InstrumentationMixin {
    companion object {
        const val NO_RETURN_COPY = 0
        const val TRANSPOSED_RETURN_COPY = 1
    }

    override fun atPrecedingUpdate(
        s: IBufferUpdate,
        overlapSym: TACSymbol.Var,
        writeEndPoint: TACSymbol.Var,
        baseInstrumentation: ILongReadInstrumentation
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        if(s.where != candidateReturnWrite) {
            return CodeGen.codeGen {
                isReturnDataCopyVar `=` TXF {
                    ite(overlapSym, NO_RETURN_COPY, isReturnDataCopyVar)
                }
            }
        }
        val src = s.updateSource
        check(src is IWriteSource.LongMemCopy) {
            "have update at return copy but not a transpositon; things have gone real bad"
        }
        return CodeGen.codeGen {
            val copyOfReturnDataCopy by e(Tag.Bool) {
                (s.updateLoc lt baseInstrumentation.baseProphecy) and (
                    (s.updateLoc add s.len) ge (baseInstrumentation.baseProphecy add baseInstrumentation.lengthProphecy)
                    )
            }
            isReturnDataCopyVar `=` TXF {
                ite(
                    copyOfReturnDataCopy,
                    ReturnBufferCopyTracker.TRANSPOSED_RETURN_COPY,
                    0
                )
            }
            offsetFromReturnDataCopyDest `=` TXF {
                ite(
                    copyOfReturnDataCopy,
                    (baseInstrumentation.baseProphecy sub s.updateLoc),
                    0
                )
            }
        }
    }

    override fun atLongRead(s: ILongRead): CommandWithRequiredDecls<TACCmd.Simple> {
        return ExprUnfolder.unfoldPlusOneCmd("constrainTranslatedReturn", TXF {
            (isReturnDataCopyVar eq NO_RETURN_COPY) or (
                localMemCopyTranslatedToCallee eq (offsetFromReturnDataCopyDest add returnDataCopyInCallee)
            )
        }) {
            TACCmd.Simple.AssumeCmd(
                it.s, "constraint prophecy"
            )
        }
    }

    override fun getRecord(): InstrumentationRecord {
        return InstrumentationRecord(
            "Return-Tracking",
            listOf(
                "returnStat" to isReturnDataCopyVar,
                "translated" to localMemCopyTranslatedToCallee,
                "offset" to offsetFromReturnDataCopyDest
            )
        )
    }

    override val havocInitVars: List<TACSymbol.Var>
        get() = listOf(offsetFromReturnDataCopyDest, localMemCopyTranslatedToCallee)
    override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
        get() = listOf(isReturnDataCopyVar to NO_RETURN_COPY.asTACExpr)


}
