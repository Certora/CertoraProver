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

import allocator.Allocator
import analysis.CommandWithRequiredDecls
import datastructures.stdcollections.*
import tac.MetaMap
import tac.Tag
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.toCore
import vc.data.tacexprutil.ExprUnfolder
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.ProgramContext
import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * [verifier.equivalence.TraceEquivalenceChecker.VCGenerator] which checks the equivalence of the results
 * of a program, along with the storage equivalence post execution. It uses the [TraceEventCheckMixin], but allows the functions to revert
 * (for obvious reasons).
 *
 * The storage equivalence is marked wtih the [EquivalenceChecker.STORAGE_EQUIVALENCE_ASSERTION] meta.
 */
object ProgramExitTracer : TraceEquivalenceChecker.VCGenerator<Any?>, TraceEventCheckMixin {
    override fun generateVC(
        progAInstrumentation: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODA, Any?>,
        progAContext: ProgramContext<MethodMarker.METHODA>,
        progBInstrumentation: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODB, Any?>,
        progBContext: ProgramContext<MethodMarker.METHODB>
    ): CoreTACProgram {
        /**
         * This will actually always be zero
         */
        val traceIndex = TACKeyword.TMP(Tag.Bit256, "traceIndex")
        val traceCheck = generateVCAt(
            BufferTraceInstrumentation.TraceTargets.Results,
            assumeNoRevert = false,
            progAInst = progAInstrumentation,
            progBInst = progBInstrumentation,
            traceIndex = traceIndex
        )

        /**
         * In addition, assert that the storages are equivalent post execution. This is done via the same skolemization trick
         * we use when comparing storage in CVL.
         */
        val storageCheck = run {
            val skolemInd = TACKeyword.TMP(Tag.Bit256, "!storageIdx")
            val reprA = TACKeyword.TMP(Tag.Bit256, "storageValA")
            val reprB = TACKeyword.TMP(Tag.Bit256, "storageValB")
            val storageA = progAContext.storageVariable
            val storageB = progBContext.storageVariable
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.WordLoad(
                        lhs = reprA,
                        base = storageA,
                        loc = skolemInd
                    ),
                    TACCmd.Simple.AssigningCmd.WordLoad(
                        lhs = reprB,
                        base = storageB,
                        loc = skolemInd
                    )
                )
            ) andThen ExprUnfolder.unfoldPlusOneCmd("storageEq", TACExprFactoryExtensions.run {
                reprA eq reprB
            }) {
                TACCmd.Simple.AssertCmd(
                    it.s,
                    "storage equal post execution",
                    MetaMap(
                        EquivalenceChecker.STORAGE_EQUIVALENCE_ASSERTION to EquivalenceChecker.StorageComparison(
                            contractAValue = reprA,
                            contractBValue = reprB,
                            skolemIndex = skolemInd
                        )
                    )
                )
            }.merge(skolemInd, reprA, reprB, storageA, storageB)
        }
        return (traceCheck andThen storageCheck).toCore("assertion check", Allocator.getNBId())
    }
}
