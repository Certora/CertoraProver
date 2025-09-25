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
import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import datastructures.stdcollections.*
import evm.EVM_BYTE_SIZE_INT
import evm.MASK_SIZE
import spec.rules.EquivalenceRule
import verifier.equivalence.tracing.BufferTraceInstrumentation
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten
import vc.data.codeFromCommandWithVarDecls
import vc.data.tacexprutil.ExprUnfolder
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.ProgramContext
import verifier.equivalence.data.CallableProgram
import java.math.BigInteger

class StaticBufferEqualityChecker<I>(
    private val cexContext: CounterExampleContext<I>,
    private val refineA: StaticBufferRefinement.WriteOffsets,
    private val refineB: StaticBufferRefinement.WriteOffsets,
) {
    private val originalQuery get() = cexContext.checkResult

    private data class BufferWriteVar(
        val instName: TACSymbol.Var,
        val sourceBuffer: CmdPointer,
        // This is the term to subtract from the number of writes
        // to give the index into the writes array
        // so, for the last element, it will be 1
        val offsetFromEnd: Int
    )

    private fun buildTaggedBuffer(t: StaticBufferRefinement.WriteOffsets) : Array<BufferByte>? {
        val taggedBuffer = Array<BufferByte?>(t.staticLength.intValueExact()) {
            null
        }
        for((ind, write) in t.relativeWriteOffsets.withIndex()) {
            when(write) {
                is StaticBufferRefinement.BufferWrite.LongCopyFrom -> {
                    val sourceModel = buildTaggedBuffer(write.copySource) ?: continue
                    var offsIt = write.relativeOffset.intValueExact()
                    for(byte in sourceModel) {
                        if(offsIt in 0 .. taggedBuffer.lastIndex) {
                            taggedBuffer[offsIt] = byte
                        }
                        offsIt++
                    }
                }
                is StaticBufferRefinement.BufferWrite.WordWrite -> {
                    /**
                     * Give names to the scalar value written at the relative offsets
                     */
                    val instVar = TACSymbol.Var(
                        "bufferRepresentative",
                        Tag.Bit256,
                        NBId.ROOT_CALL_ID,
                        MetaMap(TACMeta.NO_CALLINDEX)
                    ).toUnique("!")
                    val srcVar = BufferWriteVar(
                        instName = instVar,
                        sourceBuffer = t.actualSource,
                        offsetFromEnd = t.relativeWriteOffsets.size - ind
                    )
                    var byteIdx = 31
                    var offsIt = write.relativeOffset.intValueExact()
                    while(byteIdx >= 0) {
                        if(offsIt in 0 .. taggedBuffer.lastIndex) {
                            taggedBuffer[offsIt] = BufferByte(srcVar, byteIdx)
                        }
                        byteIdx--
                        offsIt++
                    }
                }
            }
        }
        if(taggedBuffer.any { it == null }) {
            return null
        }
        return taggedBuffer.uncheckedAs()
    }

    /**
     * Representation of a byte in the final buffer: pulled from some [byteIndex] from [srcVar]
     */
    private data class BufferByte(
        val srcVar: BufferWriteVar,
        val byteIndex: Int // lsb numbering, 0 is the 1s byte
    )

    /**
     * Gets the (pure math) term describing the buffer contents, and the variables used in that term.
     * More precisely, given a [verifier.equivalence.StaticBufferRefinement.WriteOffsets]
     * with a list of [verifier.equivalence.StaticBufferRefinement.WriteOffsets.relativeWriteOffsets],
     * the first compnent of the tuple is a list of the (invented) names for the values written at these offsets.
     * The second component is a term whose free variables are drawn from this list, and which describes
     * the buffer contents as a mathematical integer. In this mathematical model, the 0th byte of the buffer is the most
     * significant part of the number.
     *
     * To give a very basic example, consider a [verifier.equivalence.StaticBufferRefinement.WriteOffsets]
     * whose writes are `0, 32`. Then this will return `([firstComp, secondComp], firstComp *int 2^256 +int secondComp)`.
     */
    private fun extractInstrumentationTerm(t: StaticBufferRefinement.WriteOffsets) : Pair<Set<BufferWriteVar>, TACExpr> {
        /**
         * Compute at each byte in the buffer which write it came from and the position within that write.
         *
         * We compute this in just the stupidest way possible: we simulate the sequence of writes described
         * by [verifier.equivalence.StaticBufferRefinement.WriteOffsets.relativeWriteOffsets] but instead of writing
         * concrete values we write "coordinates" described by [BufferByte].
         *
         */
        val taggedBuffer = buildTaggedBuffer(t) ?: error("Something has gone wrong; static offset " +
            "extractor promised $t covered the buffer; it seems not to")
        /**
         * Term accum are expected to be "disjoint", that is, adding all of these values together
         * would be equivalent to interpreting their values as infinite width bitvectors and performing bitwise OR.
         */
        val termAccum = mutableListOf<TACExpr>()

        /**
         * In what follows, we find sequences of [BufferByte] whose [BufferByte.byteIndex] are contiguous
         * and which have the same [BufferByte.srcVar]. These correspond to ranges of bytes that
         * can be extracted from the value, shifted into place, and then combined together to form the buffer model.
         */

        // The most recent buffer byte processed
        var currV : BufferByte? = null
        // The location in the *output* buffer the current range started
        var currStart: Int = -1

        /**
         * Called to finalize one of the extracted ranges from one of the "source" variables, and generate a term that masks and
         * shifts it into place.
         *
         * This range corresponds to a sequence of bytes in the buffer we're modeling. The last index of this range
         * (exclusive) in the buffer is given by [endIdxExclusive]. For example, suppose we determine that the buffer
         * begins with a range of three bytes. Then [endIdxExclusive] is 4.
         */
        fun packageTerm(endIdxExclusive: Int) : TACExpr {
            val extractedWidth = endIdxExclusive - currStart
            /**
             * If we're constructing the range starting at position 4 in the buffer,
             * and which runs until position (aka [endIdxExclusive]) 7, and the most recently processed [BufferByte]
             * has [BufferByte.srcVar] == `v` and [BufferByte.byteIndex] == 5 then:
             * extractedWidth = 3 (as expected)
             * lowerStart = 5 - (3 - 1) = 3
             * Shifting down by this many bytes means that the 3rd byte (0-indexed, starting from LSB)
             * will be the least significant byte.
             */
            val lowerStart = currV!!.byteIndex - (extractedWidth - 1) // index of the last byte to be included
            val shiftedDown =
                TACExpr.BinOp.ShiftRightLogical(currV!!.srcVar.instName.asSym(), (lowerStart * EVM_BYTE_SIZE_INT).asTACExpr)
            /*
             * Include only the bits in this range
             */
            val andMasked = TACExpr.BinOp.BWAnd(shiftedDown, MASK_SIZE(extractedWidth * EVM_BYTE_SIZE_INT).asTACExpr)
            /**
             * To know how much to shift *up*, compute how many bytes remain in the buffer after this range.
             * Continuing our example above, suppose the entire buffer is length 16. [endIdxExclusive]
             * is 7, so there are 9 bytes "after" this term, meaning we need to shift up to leave space
             * for these terms.
             */
            val lowestBitNumberInCombined = taggedBuffer.size - endIdxExclusive
            check(lowestBitNumberInCombined >= 0)
            val shiftConst = BigInteger.TWO.pow(lowestBitNumberInCombined * EVM_BYTE_SIZE_INT)
            /**
             * And done
             */
            return TACExpr.Vec.IntMul(
                andMasked, shiftConst.asIntTACExpr
            )
        }
        for((bufferInd, bit) in taggedBuffer.withIndex()) {
            if (currV == null) {
                currV = bit
                currStart = bufferInd
                continue
                // expect descending bit numbers (write numbers msb)
                // so if we start at bitindex j, the kth bit in the sequence should be j - k
            } else if (currV.srcVar == bit.srcVar && currV.byteIndex - (bufferInd - currStart) == bit.byteIndex) {
                continue
            } else {
                /**
                 * Otherwise, a new range started. Package up the current term, and reset the accumulators
                 */
                termAccum.add(packageTerm(bufferInd))
                currV = bit
                currStart = bufferInd
            }
        }
        termAccum.add(packageTerm(taggedBuffer.size))
        return taggedBuffer.mapTo(mutableSetOf()) {
            it.srcVar
        } to TACExpr.Vec.IntAdd(termAccum)
    }

    private val aInstVars: Set<BufferWriteVar>
    private val aBufferTerm: TACExpr

    private val bInstVars: Set<BufferWriteVar>
    private val bBufferTerm: TACExpr

    init {
        val aBuffer = extractInstrumentationTerm(refineA)
        aInstVars = aBuffer.first
        aBufferTerm = aBuffer.second

        val bBuffer = extractInstrumentationTerm(refineB)
        bInstVars = bBuffer.first
        bBufferTerm = bBuffer.second
    }


    private fun <M: MethodMarker> buildInstControl(
        p: CallableProgram<M, I>,
        t: ExplorationManager.EquivalenceCheckConfiguration<*>.() -> BufferTraceInstrumentation.InstrumentationControl,
        refine: StaticBufferRefinement.WriteOffsets,
        instVars: Set<BufferWriteVar>
    ) : TraceEquivalenceChecker.ProgramAndConfig<M, I> {
        val baseConfig = cexContext.instrumentationSettings.t()
        val useSiteControl = instVars.mapToSet {
            it.sourceBuffer
        }.associateWith {
            BufferTraceInstrumentation.UseSiteControl(
                trackBufferContents = true
            )
        }
        return TraceEquivalenceChecker.ProgramAndConfig(
            p,
            baseConfig.copy(
                traceMode = BufferTraceInstrumentation.TraceInclusionMode.UntilExactly(
                    refine.actualSource
                ),
                useSiteControl = useSiteControl,
                eventSiteOverride = mapOf()
            )
        )
    }

    private fun generateVC(
        a: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODA, I>,
        contextA: ProgramContext<*>,
        b: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODB, I>,
        contextB: ProgramContext<*>
    ) : CoreTACProgram {
        unused(contextA, contextB)
        /**
         * Assign the values from the
         * [BufferTraceInstrumentation.IBufferContentsInstrumentation.bufferValueHolder] maps
         * generated by the [BufferTraceInstrumentation]
         * to the variables used in the terms.
         */
        val bind = listOf(
            Pair(a.result, aInstVars),
            Pair(b.result, bInstVars)
        ).flatMap { (instMaps, instVar) ->
            instVar.map { v ->
                /**
                 * We have proved in [StaticBufferRefinement] that there are at least enough writes
                 * to make sure this relative indexing doesn't underflow
                 */
                val writeInfo = instMaps.useSiteInfo[v.sourceBuffer]?.instrumentation?.bufferWrites ?: error(
                    "Write info for ${v.sourceBuffer} didn't exist?"
                )
                val countVar = writeInfo.bufferWriteCountVar
                val baseVar = writeInfo.bufferValueHolder
                val subtractFromEnd = v.offsetFromEnd
                ExprUnfolder.unfoldPlusOneCmd("binding", TACExprFactTypeCheckedOnlyPrimitives {
                    countVar sub subtractFromEnd.asTACExpr
                }) {
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = v.instName,
                        base = baseVar,
                        loc = it.s
                    )
                }.merge(baseVar, v.instName, countVar)
            }
        }.flatten()

        /**
         * Now, simply assert these terms are equal. Despite all the math, this
         * is a purely LIA problem, meaning it's actually pretty easy for the solvers to work on.
         */
        val assertVar = TACKeyword.TMP(Tag.Bool, "!assertionVar")
        return codeFromCommandWithVarDecls(
            Allocator.getNBId(), bind andThen CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = assertVar,
                        rhs = TACExpr.BinRel.Eq(aBufferTerm, bBufferTerm)
                    ),
                    TACCmd.Simple.AssertCmd(assertVar, "buffers equal")
                ), setOf(assertVar)
            ), "assertion"
        )

    }

    suspend fun checkStaticEquivalence(rule: EquivalenceRule): TraceEquivalenceChecker.CheckResult<I> {
        return TraceEquivalenceChecker<I>(cexContext.queryContext, cexContext.ruleGeneration).instrumentAndCheck(
            rule,
            buildInstControl(originalQuery.programA.originalProgram, ExplorationManager.EquivalenceCheckConfiguration<*>::getAConfig, refineA, aInstVars),
            buildInstControl(originalQuery.programB.originalProgram, ExplorationManager.EquivalenceCheckConfiguration<*>::getBConfig, refineB, bInstVars),
            ::generateVC
        )
    }

}
