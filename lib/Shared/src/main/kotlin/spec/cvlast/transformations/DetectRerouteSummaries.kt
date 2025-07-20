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

package spec.cvlast.transformations

import spec.cvlast.*
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import utils.*
import utils.CollectingResult.Companion.lift
import datastructures.stdcollections.*
import spec.cvlast.typechecker.IllegalRerouteSummary
import spec.cvlast.typedescriptors.EVMLocationSpecifier
import spec.cvlast.typedescriptors.VMReferenceTypeDescriptor
import utils.CollectingResult.Companion.asError

/**
 * Detects [SpecCallSummary.Exp] summaries which are actually reroute summaries.
 *
 * Candidate [SpecCallSummary.Exp] summary `e` is a reroute summaries if:
 * 1. `e` summarizes an internal function
 * 2. There is no with clause bound
 * 3. `e` is an apply expression, where:
 *   a. The callee is a method reference m.f
 *   b. all arguments are [spec.cvlast.CVLExp.VariableExp], which are the parameters bound by the summary
 * 4. `m` is a solidity contract registered in [symbolTable]
 * 5. There exists some external function `r` in `m` such that:
 *   a. `r` is marked as a library function
 *   b. `r`'s name is `f`
 *   c. `r`'s declared parameter signature exactly matches the types of the variables used as arguments
 *
 * To clarify on point 5.c, the arguments `(a1, a2, ...)` to the apply expression must be a parameter `pi` bound
 * by the summary. As each parameter `pi` has a declared type, we can write `ai.type` to indicate the
 * type of argument `ai`. Then, `r` must have parameters `(a1.type, a2.type, ...)`. The types must match *exactly*,
 * no attempt is made at covariant subtyping.
 */
class DetectRerouteSummaries(val symbolTable: CVLSymbolTable) : CVLAstTransformer<IllegalRerouteSummary>(
    CVLCmdTransformer(CVLExpTransformer.copyTransformer())
) {
    override fun importedMethod(importedMethod: ConcreteMethodBlockAnnotation): CollectingResult<ConcreteMethodBlockAnnotation, IllegalRerouteSummary> {
        if(importedMethod.qualifiers.visibility != Visibility.INTERNAL) {
            return importedMethod.lift()
        }
        if(importedMethod.summary !is SpecCallSummary.Exp) {
            return importedMethod.lift()
        }
        val exp = importedMethod.summary.exp
        if(exp !is CVLExp.UnresolvedApplyExp) {
            return importedMethod.lift()
        }
        if(exp.base !is CVLExp.VariableExp) {
            return importedMethod.lift()
        }
        val isStorageSummary = importedMethod.methodParameterSignature.paramTypes.any {
            it is VMReferenceTypeDescriptor && it.location == EVMLocationSpecifier.storage
        }
        val expectedHost = SolidityContract(exp.base.id)
        val scopeData = symbolTable.getContractScope(expectedHost) ?: return importedMethod.lift()
        val functions = symbolTable.lookUpFunctionLikeSymbol(exp.methodId, scopeData) as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo
        if(functions == null) {
            if(!isStorageSummary) {
                return importedMethod.lift()
            }
            return IllegalRerouteSummary(
                errorType = IllegalRerouteSummary.ErrorSort.NoMatchingFunction(
                    missingFunctionName = QualifiedFunction(host = expectedHost, methodId = exp.methodId)
                ),
                location = exp.getRangeOrEmpty()
            ).asError()
        }
        val contractFunctions = functions.impFuncs.mapNotNull {f ->
            f as? ContractFunction
        }
        val isProbablyRerouteAttempt = contractFunctions.any {
            it.evmExternalMethodInfo?.isLibrary == true
        } || importedMethod.methodParameterSignature.paramTypes.any {
            it is VMReferenceTypeDescriptor && it.location == EVMLocationSpecifier.storage
        }
        if(!isProbablyRerouteAttempt) {
            return importedMethod.lift()
        }

        val libraryFunctions = functions.impFuncs.mapNotNull { f ->
            f as? ContractFunction
        }.filter { cf ->
            cf.evmExternalMethodInfo?.isLibrary == true
        }.takeIf { l -> l.isNotEmpty() } ?: return IllegalRerouteSummary(
            location = importedMethod.summary.range,
            errorType = IllegalRerouteSummary.ErrorSort.NotALibrary(
                tgt = QualifiedFunction(
                    host = expectedHost,
                    methodId = exp.methodId
                )
            )
        ).asError()

        if(importedMethod.summary.withClause != null) {
            return IllegalRerouteSummary(
                errorType = IllegalRerouteSummary.ErrorSort.NoWithClause(
                    withRange = importedMethod.summary.withClause.range
                ),
                location = importedMethod.summary.range
            ).asError()
        }

        val knownFunctionArguments = mutableMapOf<String, Int>()
        for((ind, o) in importedMethod.summary.funParams.withIndex()) {
            if(o is VMParam.Named) {
                knownFunctionArguments[o.name] = ind
            }
        }

        val argParams = mutableListOf<String>()

        // now check our arguments
        for(a in exp.args) {
            // not a variable? not a reroute
            if(a !is CVLExp.VariableExp) {
                return IllegalRerouteSummary(
                    errorType = IllegalRerouteSummary.ErrorSort.IllegalArgument(
                        exp = a
                    ),
                    location = importedMethod.summary.range
                ).asError()
            }
            val sym = symbolTable.lookUpNonFunctionLikeSymbol(a.id, importedMethod.summary.scope) ?: return importedMethod.lift()
            /**
             * Not a VM type? probably not bound by the summary. (there is currently no way I know of to
             * check the scope in which a symbol is defined, so we settle for this as our proxy)
             */
            if(sym.getCVLTypeOrNull() !is CVLType.VM || a.id !in knownFunctionArguments) {
                return IllegalRerouteSummary(
                    errorType = IllegalRerouteSummary.ErrorSort.IllegalArgument(
                        exp = a
                    ),
                    location = importedMethod.summary.range
                ).asError()
            }
            argParams.add(a.id)
        }
        // all params appear to be coming from our function parameters. See if we can find a matching library function
        val expectedParams = argParams.map {
            // safe from the `a.id !in ...` check above
            knownFunctionArguments[it]!!.let { argIndex ->
                importedMethod.methodParameterSignature.params[argIndex]
            }
        }

        val argOrdinals = argParams.map {
            knownFunctionArguments[it]!!
        }

        val expectedSighash = ExternalSignature.computeSigHash(
            isLibrary = true,
            signature = MethodParameterSignature(
                functionName = exp.methodId,
                params = expectedParams
            )
        )
        val targetFunction = QualifiedFunction(
            host = expectedHost,
            methodId = exp.methodId
        )
        val expectedSignature = ExternalQualifiedMethodParameterSignature(
            params = expectedParams,
            contractId = targetFunction,
            sighashInt = expectedSighash
        )
        if(libraryFunctions.none {
            it.methodSignature.matchesContractAndParams(expectedSignature) && it.sigHash == expectedSighash.n
        }) {
            return IllegalRerouteSummary(
                location = importedMethod.summary.range,
                errorType = IllegalRerouteSummary.ErrorSort.ParameterTypeMismatch(
                    tgt = QualifiedFunction(
                        methodId = exp.methodId,
                        host = expectedHost
                    ),
                    params = expectedParams.map { it.vmType }
                )
            ).asError()
        }

        return importedMethod.copy(summary = SpecCallSummary.Reroute(
            target = targetFunction,
            sighash = expectedSighash.n,
            summarizationMode = importedMethod.summary.summarizationMode,
            range = importedMethod.summary.range,
            args = importedMethod.summary.funParams,
            forwardArgs = argOrdinals
        )).lift()
    }
}
