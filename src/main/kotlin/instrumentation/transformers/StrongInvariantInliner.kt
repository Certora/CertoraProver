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

import analysis.CmdPointer
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.LTACCmd
import analysis.icfg.Havocer
import analysis.icfg.MetaKeyPairDetector
import analysis.icfg.SummaryStack
import analysis.maybeAnnotation
import datastructures.stdcollections.*
import log.*
import report.callresolution.CallResolutionTableRow
import scene.IScene
import spec.CVLCompiler
import spec.GenerateRulesForInvariantsAndEnvFree
import spec.cvlast.*
import spec.rules.CVLSingleRule
import tac.CallId
import tac.DataField
import utils.*
import vc.data.*
import vc.data.ParametricInstantiation.Companion.toSimple
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACMeta.CVL_ASSUME_INVARIANT_CMD_START
import vc.data.tacexprutil.asVar
import java.util.stream.Collectors


private val logger = Logger(LoggerTypes.STRONG_INVARIANT_INLINER)

/**
 * This transformer pass adds support for strong invariant - which will be checked before and after a summarized call.
 *
 * The logic is as follows:
 * Step 1. Find all external summarize start and end commands (summaryStartCmd, summaryEndCmd) within the program that are unresolved or where summarization failed
 * Step 2. Assert the invariant holds before summaryStartCmd
 *
 * In the case of a regular call, delegate call or call code, apply the following (after the AUTO HAVOC from the summary has been applied)
 * Step 3. assume the invariant to hold after summaryEndCmd
 *
 * In the case of a delegate call or a call code
 * Step 4: Havoc the current executing contract and assert the invariant is not broken.
 */
class StrongInvariantInliner(val scene: IScene, val cvlCompiler: CVLCompiler, val rule: CVLSingleRule) : CodeTransformer() {

    private fun asSummaryStart(summaryStart: LTACCmd) = ((summaryStart.cmd as TACCmd.Simple.AnnotationCmd).annot.v as SummaryStack.SummaryStart)

    private fun getExternalSummaryInfo(summaryStart: LTACCmd): SummaryStack.SummaryStart.External? = when (val cmd = summaryStart.cmd) {
        is TACCmd.Simple.AnnotationCmd -> cmd.annot.v as? SummaryStack.SummaryStart.External
        else -> null
    }

    private fun getExternalCallRange(summaryStart: LTACCmd) = getExternalSummaryInfo(summaryStart)?.callSiteSrc?.getSourceDetails()?.range

    private fun isCallType(summaryStart: LTACCmd, callType: (TACCallType) -> Boolean): Boolean {
        val external = getExternalSummaryInfo(summaryStart)
        return external?.callNode?.callType?.let { callType(it) } ?: false
    }

    override fun transform(ast: CoreTACProgram): CoreTACProgram {
        val patchingProgram = ast.toPatchingProgram()
        val summaryStartEndPairs = MetaKeyPairDetector(
            ast.analysisCache.graph,
            MetaKeyPairDetector.isMetaKey(SummaryStack.START_EXTERNAL_SUMMARY),
            MetaKeyPairDetector.isMetaKey(SummaryStack.END_EXTERNAL_SUMMARY),
            MetaKeyPairDetector.externalSummaryStartEndMatcher
        ).getResultsAtSinkBlock()

        logger.info { "Found a total of ${summaryStartEndPairs.size} summaries in the program." }

        summaryStartEndPairs
            /**
             * Apply the logic for call resolution only if the call is unresolved or if there is a summarization failure. I.e. we don't apply the logic in the case there is a CVL function summary for the call
             * and neither in the case that the call could be linked and inlined.
             */
            .filter {
                /**
                 * The Strong Invariant Logic _only_ applies in cases there is an unresolved call,
                 * and the call resolution table shows a red warning note [CallResolutionTableRow.Status.NotOk]
                 * or the user/prover inlined a [spec.cvlast.SpecCallSummary.HavocSummary.HavocAll]
                 * or a [spec.cvlast.SpecCallSummary.HavocSummary.HavocECF].
                 */
                asSummaryStart(it.key).callResolutionTableInfo.callResolutionTableRowStatus is CallResolutionTableRow.Status.NotOk ||
                    asSummaryStart(it.key).summary is SpecCallSummary.HavocSummary.HavocAll ||
                    asSummaryStart(it.key).summary is SpecCallSummary.HavocSummary.HavocECF
            }
            .also { filteredPairs ->
                logger.info { "Applying strong invariant logic to a total of ${filteredPairs.size} summaries (after filtering)" }
            }
            .forEachEntry { (summaryStart, summaryEnds) ->
                val summaryEnd = summaryEnds.singleOrNull() ?: error("expected a single exit point from this summary")
                check(summaryStart.ptr.block.getCallId() == summaryEnd.ptr.block.getCallId()) { "The summary start and summary end call ids are different. ${summaryStart.ptr.block.getCallId()} and ${summaryEnd.ptr.block.getCallId()}" }

                currentlyProvingStrongInvariant()?.let { invariant ->
                    /**
                     * Assert that the invariant still holds before the actual call, the invariant will be asserted before all call types, i.e.: static, regular and delegate call.
                     */
                    val summaryCallStartProg = listOf(summaryStart.cmd).withDecls()
                    logger.info { "Adding assertion of strong invariant ${invariant.id} before start of summary at command pointer ${summaryStart.ptr}" }
                    patchingProgram.replaceCommand(summaryStart.ptr, generateAssertInvariantProg(invariant, summaryStart, ast).appendToSinks(summaryCallStartProg))
                }


                /**
                 * In the case of a delegate call or a regular call or a call code, apply additional steps but only after the summary has been inlined
                 * such that any havoc'ing logic of the summary will not interfere and will be "overwritten" by the logic below.
                 */
                if (isCallType(summaryStart) { type -> type == TACCallType.DELEGATE || type == TACCallType.REGULAR_CALL || type == TACCallType.CALLCODE }) {
                    val summaryCallEndProg = listOf(summaryEnd.cmd).withDecls()
                    logger.info { "Adding post commands for at command pointer ${summaryEnd.ptr}" }
                    generatePostCallCommands(summaryStart, ast)?.let { inlinedProg ->
                        patchingProgram.replaceCommand(summaryEnd.ptr, inlinedProg.prependToBlock0(summaryCallEndProg))
                    }

                }
            }
        return patchingProgram.toCode(ast)
    }

    /**
     * If current proving a strong invariant, returns this invariant.
     */
    private fun currentlyProvingStrongInvariant(): CVLInvariant? {
        val inductionStep = rule.ruleType as? SpecType.Single.InvariantCheck ?: return null
        return cvlCompiler.cvl.invariants.filter { it.invariantType == StrongInvariantType }.firstOrNull { it.id == inductionStep.originalInv.id };
    }

    private fun generateAssertInvariantProg(invariant: CVLInvariant, summaryStart: LTACCmd, code: CoreTACProgram): CoreTACProgram {
        val assertInvariantCmds = GenerateRulesForInvariantsAndEnvFree.assertInvariant(invariant, rule.scope, "assert strong invariant before external call", getExternalCallRange(summaryStart)
            ?: invariant.range)
        val inlinedCode = cvlCompiler.compileInvariantCmds(listOf(), invariant.params, assertInvariantCmds, summaryStart.ptr.block.getCallId()).getAsSimple().transformToCore(scene);
        return createParameterAssignment(code, inlinedCode)
    }

    /**
     * Returns the list of CVLInvariant that are used/assumed with requireInvariant in [code]
     * and includes [currentlyProvingStrongInvariant] if it's not null.
     */
    private fun assumedStrongInvariantsInCode(code: CoreTACProgram): List<CVLInvariant> {
        val requiredInvariant = code.parallelLtacStream()
            .mapNotNull { it.cmd.maybeAnnotation(CVL_ASSUME_INVARIANT_CMD_START)?.name }.toList()
        return cvlCompiler.cvl.invariants.filter { it.invariantType == StrongInvariantType && it.id in requiredInvariant } + listOfNotNull(currentlyProvingStrongInvariant())
    }
    /**
     * This method generates the commands to be inserted after the actual unresolved call, the logic is as follows:
     *
     * Case 1: unresolved regular call:
     * - Step 1: Assumes all strong invariants
     *
     * Explanation: Due to the AUTO HAVOC at the unresolved call, the storage  now can be in any state,
     * we'll assume the invariant as it held before the call. This is not quite true for unresolved delegate calls or call codes:
     *
     * Case: Unresolved delegate call or call code:
     *  - Step 1: from a regular call plus
     *  - Step 2: Havoc the current contract
     *  - Step 3: Assert the strong invariant
     *
     * Explanation: A delegatecall (or call code) can also modify the current contracts storage which is simulated by step 2.
     * Step 3 will be applied to check if the invariant is now broken in the case the currenct contract was havoc'ed.
     */
    private fun generatePostCallCommands(summaryStart: LTACCmd, code: CoreTACProgram): CoreTACProgram? {
        val callId = summaryStart.ptr.block.getCallId()
        val allUsedStrongInvariants = assumedStrongInvariantsInCode(code)
        if (allUsedStrongInvariants.isEmpty()) {
            return null
        }

        /**
         * Step 1: Assume all strong invariants to restrict the storage to the right values.
         */
        val compiledAssumedStrongInvariants = allUsedStrongInvariants.map { strongInv ->
            val params = strongInv.params
            val commands = GenerateRulesForInvariantsAndEnvFree.assumeInvariant(strongInv, rule.scope, "assuming strong invariant ${strongInv.id}")
            cvlCompiler.compileInvariantCmds(listOf(), params, commands, callId)
        };

        val currAddress = code.procedures.mapNotNull {
            it.takeIf { it.callId == callId }?.procedureId?.address?.asBigInteger()
        }.uniqueOrNull()
        val strongInvariant =  currentlyProvingStrongInvariant()
        val progsToMerge = if (isCallType(summaryStart) { type -> type == TACCallType.DELEGATE || type == TACCallType.CALLCODE } && currAddress != null && strongInvariant != null) {
            logger.info { "Havoc'ing storage of current contract and re-asserting strong invariant due to a delegate call" }
            /**
             * In case of delegatecall or call code only:
             * Step 2: Havoc the current contracts' storage
             */
            val havocCurrentContractStep2 = generateHavocProgram(Havocer.HavocType.HavocOnly(mapOf(currAddress to scene.getContract(currAddress).name)), callId)

            /**
             * In case of delegatecall:
             * Step 3: Check that the invariant still holds and the havocing of the current contract doesn't break the invariant.
             */
            val assertInvariantCmds = GenerateRulesForInvariantsAndEnvFree.assertInvariant(strongInvariant, rule.scope, "strong invariant: assert invariant after havoc'ing the current contract after an unresolved delegate call", getExternalCallRange(summaryStart)
                ?: strongInvariant.range)
            val assertProgStep3 = cvlCompiler.compileInvariantCmds(listOf(), strongInvariant.params, assertInvariantCmds, callId)
            compiledAssumedStrongInvariants + listOf(havocCurrentContractStep2, assertProgStep3)
        } else {
            compiledAssumedStrongInvariants
        }
        val codeToBeInlined = ParametricMethodInstantiatedCode.merge(progsToMerge, "post external summary commands for strong invariants").getAsSimple().transformToCore(scene)
        return createParameterAssignment(code, codeToBeInlined)
    }

    private fun generateHavocProgram(havoc: Havocer.HavocType, summaryEndCallId: CallId): ParametricInstantiation<CVLTACProgram> {
        val havocCmdsAndDecls = Havocer.generateHavocCRD(havoc, scene)
        val prog = cvlCompiler.codeFromCommandVarWithDecls(CVLCompiler.CompilationEnvironment(summaryEndCallId).newBlockId(), havocCmdsAndDecls, "Applying havoc logic with havoc type ${havoc}")
        return prog.toSimple()
    }

    data class CVLAccessPath(val param: CVLCompiler.Companion.TraceMeta.DeclarationType.Parameter, val fields: List<DataField>)

    /**
     * Returns a mapping from CVL parameter to the set of TAC variables the parameter creates.
     */
    private fun CoreTACProgram.cvlAccessPathToTACVariable() = this.parallelLtacStream()
        .mapNotNull { it.ptr `to?` it.maybeAnnotation(CVLCompiler.Companion.TraceMeta.VariableDeclaration.META_KEY) }
        .collect(Collectors.toSet()).flatMap { (ptr, varDecl) ->
            when (varDecl.type) {
                is CVLCompiler.Companion.TraceMeta.DeclarationType.Parameter -> {
                    when (varDecl.v) {
                        is CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar -> listOf()
                        is CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar -> listOf(CVLAccessPath(varDecl.type, listOf()) to VarDeclAt(varDecl.v.t,ptr))
                    } + (varDecl.fields?.map { CVLAccessPath(varDecl.type, it.key) to VarDeclAt(it.value, ptr) } ?: listOf())
                }

                else -> listOf()
            }
        }.groupBy({ it.first },{ it.second } )

    private data class VarDeclAt(val variable: TACSymbol.Var, val ptr: CmdPointer)
    /**
     * @param codeBeforeInlining The CoreTACprogram before inlining (think of it as the TACProgram of the weak invariant)
     * @param codeToBeInlined The CoreTACProgram that should be inlined at external unresolved call (i.e. the sequence of
     * assert invariant, havoc assume invariant explained above)
     *
     * This method takes the TAC program before inlining the strong invariants (basically the code of the weak invariant)
     * and takes the code that is supposed to be inlined to make the weak invariant a strong invariant. This will be executed
     * for each unresolved external call.
     *
     * The Strong Invariant inlining process re-compiles the invariant for each external unresolved call, this process
     * yields a new CoreTACProgram including new TAC variables for the CVL parameters. This method ensures that all
     * TAC variables of [codeBeforeInlining] and of [codeToBeInlined] that originate from a CVL parameter hold the same value.
     *
     * Example: Assume we have `invariant foo(uint x[]) ...`. CVL variable `x` will compile down to a CoreTACProgram
     * with two TAC variables (x_length and x_data) representing the variable `x`. We have these variables in
     * [codeBeforeInlining] and [codeToBeInlined]. Let's call them `x_a_length` and `x_b_length` and `x_a_data` respectively.
     *
     * Whenever we assert or assume the invariant, the parameters represent the same value, so this method will then add an assignment:
     * ASSIGN x_a_length = x_b_length
     * ASSIGN x_a_data = x_b_data
     * such that in the SMT model parameter `x` will always hold the same value.
     *
     * Note that this is a workaround as we re-compile the invariant from scratch which yields to new variables being allocated.
     */
    private fun createParameterAssignment(codeBeforeInlining: CoreTACProgram, codeToBeInlined: CoreTACProgram): CoreTACProgram {
        val originalCodeParams = codeBeforeInlining.cvlAccessPathToTACVariable().mapValues { (_, v) -> (v.uniqueOrNull() ?: error("Found multiple declaration in the original program"))}
        val inlinedCodeParams = codeToBeInlined.cvlAccessPathToTACVariable()

        // intersect CVL display names of both TAC programs
        val result = (originalCodeParams.keys intersect inlinedCodeParams.keys).map { declParam ->
            (originalCodeParams[declParam]!! to inlinedCodeParams[declParam]!!)
        }.flatMap { el ->
            val originalParam = el.first
            val inlinedParam = el.second
            inlinedParam.map { param ->
                param.ptr to
                    // Create the assignment from the original param of the invariant that comes from [codeBeforeInlining]
                    // to the parameter of the invariant that is inlined via [codeToBeInlined].
                    AssignExpCmd(
                        param.variable,
                        originalParam.variable
                    )
            }
        }.groupBy({ it.first }, { it.second })

        return codeToBeInlined.patching { p ->
            result.forEachEntry { entry ->
                p.addVarDecls(entry.value.mapToSet { it.rhs.asVar })
                p.addAfter(entry.key, entry.value)
            }
        }
    }
}
