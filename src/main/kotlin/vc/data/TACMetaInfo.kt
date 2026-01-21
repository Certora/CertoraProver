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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)

package vc.data

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.TACCommandGraph
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import analysis.ip.InternalFuncStartAnnotation
import analysis.maybeAnnotation
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import com.certora.collect.*
import compiler.*
import datastructures.stdcollections.*
import disassembler.EVMMetaInfo
import disassembler.EVMPC
import log.Logger
import log.LoggerTypes
import scene.IBoundTACMethod
import utils.Range
import utils.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COMMON)

/**
 * [pc] the original program counter of the command
 * [source] the original solc-assigned source index
 * [begin] the offset in the matching source file, determined by solc
 * [len] the length of the matching source code, determined by solc
 * [jumpType] the [JumpType] in the matching source file
 * [address] the CVT-assigned address of the compiled contract
 * [sourceContext] holds a mapping from source indices to the (CVT-internally-copied) file names of the source
 */
@KSerializable
@Treapable
data class TACMetaInfo(
    // this is nullable purely for backwards compatibility with unit tests that have existing metas for which it is
    // too painful/annoing to update; any "real" TACMetaInfo generated *not* in these unit tests will have a non-null
    // pc field
    val pc_: EVMPC? = null,
    val source: Int,
    val begin: Int,
    val len: Int,
    val jumpType: JumpType?,
    val address: BigInteger,
    val sourceContext: SourceContext,
): AmbiSerializable {
    override fun hashCode() = hash { it + source + begin + len + jumpType + address + sourceContext + pc_ }

    val pc: EVMPC get() = pc_!!

    fun sourceIdentifier(): SourceIdentifier = SourceIdentifier(this.source, this.begin, this.len)

    private fun sourcePath() = sourceIdentifier().resolve(sourceContext)

    override fun toString(): String {
        return "(${sourceIdentifier()}:0x${address.toString(16)}) // ${sourceFileName()}"
    }

    fun sourceFileName(): String? = sourcePath()?.name

    /**
     * Get the source code string associated with the TACCmd holding this meta information
     */
    fun getSourceCode(): String? = getSourceDetails()?.content

    /**
     * Get full source details associated with the TACCmd holding this meta information
     */
    fun getSourceDetails(): SourceSegment? = SourceSegment.resolveFromContext(sourceContext, sourceIdentifier())

    companion object {
        operator fun invoke(pc: EVMPC, evmInfo: EVMMetaInfo, address: BigInteger, sourceContext: SourceContext): TACMetaInfo {
            return TACMetaInfo(pc, evmInfo.source, evmInfo.begin, evmInfo.end - evmInfo.begin, evmInfo.jumpType, address, sourceContext)
        }
    }
}

/**
 * given [graph], attempts to find the internal function that [ptr] is in.
 *
 * traverses up the graph and not going infinitely in loops, until we hit an internal func
 * start annotation (or not!).
 * The idea is to find the first unbalanced open parenthesis.
 * Thus every close parenthesis (EXIT) that we encounter should be added to the set of internal funcs that
 * completed before our original cmdpointer.
 * The first START encountered when the "stack" is empty is the one to be returned.
 */
fun getContainingInternalFuncStart(ptr: CmdPointer, graph: TACCommandGraph): InternalFuncStartAnnotation? {
    var funcStart: InternalFuncStartAnnotation? = null
    object : VisitingWorklistIteration<CmdPointer, Unit, Unit>() {
        val encounteredExitAnnotationIds = mutableSetOf<Int>()
        override fun process(it: CmdPointer): StepResult<CmdPointer, Unit, Unit> {
            val lcmd = graph.elab(it)
            val maybeInternalFuncStart = lcmd.maybeAnnotation(INTERNAL_FUNC_START)
            val maybeInternalFuncExit = lcmd.maybeAnnotation(INTERNAL_FUNC_EXIT)

            if (maybeInternalFuncStart != null && encounteredExitAnnotationIds.isEmpty()) {
                funcStart = maybeInternalFuncStart
                return StepResult.Ok(emptyList(), emptyList())
            }
            else if (maybeInternalFuncStart != null) {
                // if this START is not in the EXITs, but there are still EXITs - something went seriously wrong
                if (maybeInternalFuncStart.id !in encounteredExitAnnotationIds) {
                    logger.warn { "Graph structure is wrong: " +
                        "${maybeInternalFuncStart.methodSignature.toNamedDecSignature()} started at $it, " +
                        "but it's not closing any of the internal funcs IDd $encounteredExitAnnotationIds" }
                    return StepResult.StopWith(Unit)
                }
                encounteredExitAnnotationIds.remove(maybeInternalFuncStart.id)
            } else if (maybeInternalFuncExit != null) {
                if (maybeInternalFuncExit.id in encounteredExitAnnotationIds) {
                    logger.warn { "Graph structure is wrong: " +
                        "internal func IDd ${maybeInternalFuncExit.id} at $it " +
                        "already exists in $encounteredExitAnnotationIds" }
                    return StepResult.StopWith(Unit)
                }
                encounteredExitAnnotationIds.add(maybeInternalFuncExit.id)
            }

            return StepResult.Ok(graph.pred(it), emptyList())
        }

        override fun reduce(results: List<Unit>) {}

    }.submit(listOf(ptr))
    return funcStart
}

/**
 * This gives us a string representation for this internal function,
 * and potentially the source code of the call site where the internal function is called.
 */
fun InternalFuncStartAnnotation.descWithContentAndLocation(): String {
    val nameSig = this.methodSignature.toNamedDecSignature()
    val details = this.callSiteSrc?.getSourceDetails()
    return if (details != null) {
        "$nameSig called at ${details.sanitizedContentWithLoc}"
    } else {
        nameSig
    }
}

/** legacy function for convenience */
fun getSourceStringOrInternalFuncForPtr(lcmd: LTACCmd): String? {
    return lcmd.cmd.metaSrcInfo?.getSourceDetails()?.sanitizedContentWithLoc
}

/** attempts to get a source string describing [lcmd] given [graph], along with a location matching this command. */
fun getSourceHintWithRange(lcmd: LTACCmd, graph: TACCommandGraph?, method: IBoundTACMethod?): FailureInfo {
    val cmdSource = lcmd.cmd.metaSrcInfo?.getSourceDetails()

    /**
     * Precedence:
     * 1. Try the actual meta information from the cmd.
     * 2. Try to get the range from the sourceRange of the command
     * 3. Try getting the range from the next enclosing internal function
     * 4. Try to get the information from the next enclosing external function
     * 5. Fall back that really no information is available
     */
    return if (cmdSource != null) {
        FailureInfo.AdditionalFailureInfo(range = cmdSource.range)
    } else if (lcmd.cmd.sourceRange() != null) {
        FailureInfo.AdditionalFailureInfo(range = lcmd.cmd.sourceRange())
    } else {
        val funcStart = graph?.let { g -> getContainingInternalFuncStart(lcmd.ptr, g) }
        val range = funcStart?.callSiteSrc?.getSourceDetails()?.range
        if (range != null) {
            FailureInfo.AdditionalFailureInfo(additionalUserFacingMessage = "No precise source code information available for the location, range information points to the next method that inlined the call. " +
                "I.e. inspect the highlighted method and any of it's potentially inlined callees.", range = range)
        } else if (method?.evmExternalMethodInfo?.sourceSegment?.range != null) {
            FailureInfo.AdditionalFailureInfo(additionalUserFacingMessage = "No precise source code information available for the location, range information points to the next enclosing external method that inlined the call. " +
                "I.e. inspect the highlighted method and any of it's potentially inlined callees.", range = method.evmExternalMethodInfo?.sourceSegment?.range)
        } else {
            FailureInfo.NoFailureInfo
        }
    }
}

@KSerializable
@Treapable
sealed class FailureInfo: AmbiSerializable {
    /**
     * A string that will be added to user facing message that [CVTAlertReporter] generates.
     */
    abstract val additionalUserFacingMessage: String

    /**
     * An optional range for the Failure Info
     */
    abstract val range: Range.Range?

    @KSerializable
    @Treapable
    data class AdditionalFailureInfo(override val additionalUserFacingMessage: String = "", override val range: Range.Range?) : FailureInfo()

    @KSerializable
    @Treapable
    object NoFailureInfo : FailureInfo() {
        private fun readResolve(): Any = NoFailureInfo
        override val additionalUserFacingMessage: String
            get() = "No source code information available for the location."
        override val range: Range.Range?
            get() = null

        override fun hashCode() = treapHashObject(this)
    }
}
