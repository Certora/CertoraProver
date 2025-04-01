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

package cvl

import analysis.assertNotNull
import bridge.types.SolidityTypeDescription
import scene.ProverQuery
import spec.CVL
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.CollectingResult
import utils.Range
import java.util.function.Predicate
import kotlin.reflect.KClass

typealias ParseResult = CollectingResult<ProverQuery, CVLError>

/**
 This class contains various helper functions and classes to test on CVL syntax and type-checking.
 The class contains a private function testNoErrors that asserts whether the given list of CVLErrors is empty or not,
 The class also has four more classes, GeneralType, ExpType ,LhsType, SummaryCVL,
 That implement the Predicate interface and are used to test the errors (or the returns CVL) based on their types.
 The TestCVL class also has  private function, testFlowWithPredicates,
 That run tests on the CVLFlow based on a given configuration path, and a CVL text respectively.
 take a list of predicates,
 Which are used to test the errors based on their types or unique structure of the cvl.
*/
open class AbstractCVLTest {
    enum class Scope { internal, external; }
    interface MessageAndLocationType {
        val specFile: String
        val lineRange: UIntRange
        val errMsg: String


        fun testLocationAndMessage(t: CVLError): Boolean {
            val range = t.location
            if (range !is Range.Range) {
                println("expected source file range, got empty range")
                return false
            }

            if (range.specFile != specFile) {
                println("expected error in ${specFile}, got ${range.specFile}")
                return false
            }

            if (range.start.line !in lineRange) {
                println("expected error in lines ${lineRange}, got error on line ${range.start.line}")
                return false
            }

            if (range.end.line !in lineRange) {
                println("expected error in lines ${lineRange}, error extended until line ${range.end.line}")
                return false
            }

            if (errMsg !in t.message) {
                println("expected error message to contain '$errMsg'")
                return false
            }

            return true
        }
    }

    data class SpecificType<E: CVLError>(
        val expectedClass: KClass<E>,
        override val specFile: String,
        override val lineRange: UIntRange,
        override val errMsg: String = ""
    ) : Predicate<CVLError>, MessageAndLocationType {

        constructor(expectedClass: KClass<E>, specFile: String, fromLine: Int, toLine: Int, errMsg: String = "") : this(
            expectedClass,
            specFile,
            fromLine.toUInt()..toLine.toUInt(),
            errMsg
        ) {
            check(fromLine <= toLine) { " $fromLine <= $toLine does not hold" }
        }

        override fun test(t: CVLError): Boolean {
            if (t::class != expectedClass) {
                println("expected error of type $expectedClass, got ${t::class}")
                return false
            }
            return this.testLocationAndMessage(t)
        }
    }

    data class GeneralType(
        override val specFile: String,
        override val lineRange: UIntRange,
        override val errMsg: String = ""
    ) :
        Predicate<CVLError>, MessageAndLocationType {
        constructor(specFile: String, fromLine: Int, toLine: Int, errMsg: String = "") : this(
            specFile,
            fromLine.toUInt()..toLine.toUInt(),
            errMsg
        ) {
            check(fromLine <= toLine) { " $fromLine <= $toLine does not hold" }
        }

        override fun test(t: CVLError): Boolean {
            if (t !is CVLError.General) {
                println("expected general error message, got ${t::class}")
                return false
            }
            return this.testLocationAndMessage(t)
        }
    }

    data class ExpType(private val expression: String) : Predicate<CVLError> {
        override fun test(t: CVLError): Boolean {
            if (t !is CVLError.Exp) return false
            val expressionInMessage = expression in t.display()
            return when (val exp = t.exp) {
                is CVLExp.ApplicationExp -> exp.callableName == expression && expressionInMessage
                is CVLExp.VariableExp -> exp.id == expression && expressionInMessage
                is CVLExp.RelopExp.EqExp -> exp.l.toString() in expression && exp.r.toString() in expression
                is CVLExp.CastExpr -> exp.castType.str == expression
                else -> expressionInMessage
            }
        }
    }

    data class ExpressionAt(
        override val specFile: String,
        override val lineRange: UIntRange,
        override val errMsg: String
    ) : Predicate<CVLError>, MessageAndLocationType {
        constructor(specFile: String, lineStart: Int, lineEnd: Int, message: String = "") : this(
            specFile,
            lineStart.toUInt()..lineEnd.toUInt(),
            message
        )

        override fun test(t: CVLError): Boolean {
            if (t !is CVLError.Exp) {
                return false
            }
            return testLocationAndMessage(t)
        }

    }

    data class LhsType(private val id: String) : Predicate<CVLError> {
        override fun test(t: CVLError): Boolean = when (t) {
            is CVLError.Lhs -> t.lhs.getIdLhs().id == id
            else -> false
        }
    }

    data class SummaryParameter(val name: String, val type: EVMTypeDescriptor)
    data class SummaryCVL(
        private val functionNames: List<String>,
        private val scope: Scope,
        private val inputParameters: List<SummaryParameter>?,
        private val returnType: List<EVMTypeDescriptor>?,
        private val expectedSummaryTypes: List<KClass<out SpecCallSummary>>
    ) : Predicate<CVL> {
        private fun validateFunctionName(summaryFunctionName: String): Boolean =
            functionNames.any {
                it == summaryFunctionName
            }

        private fun validateFunctionParameters(parameters: List<VMParam>): Boolean =
            inputParameters?.any { expectedParameter ->
                parameters.any { inputParameter ->
                    inputParameter.type == expectedParameter.type && (inputParameter as VMParam.Named).name == expectedParameter.name
                }
            } ?: true

        private fun validateFunctionReturnType(returnTypes: List<VMParam>): Boolean =
            returnType?.any { expectedReturnType ->
                returnTypes.any { summaryReturnTypes ->
                    summaryReturnTypes.type == expectedReturnType
                }
            } ?: true

        private fun validateSummaryType(summaryType: Collection<SpecCallSummary>): Boolean =
            summaryType.any { st ->
                expectedSummaryTypes.any { expectedSummaryType ->
                    expectedSummaryType.isInstance(st)
                }
            }

        override fun test(t: CVL): Boolean =
            when (scope) {
                Scope.internal -> {
                    t.internal.keys.any {
                        validateFunctionName(it.signature.functionName) &&
                            validateFunctionParameters(it.signature.params) &&
                            validateFunctionReturnType((it.signature as QualifiedMethodSignature.QualifiedMethodSig).res)
                    } && validateSummaryType(t.internal.values)
                }

                Scope.external -> {
                    t.external.keys.any {
                        it is CVL.SummarySignature.ExternalWithSignature &&
                            validateFunctionName(it.signature.functionName) &&
                            validateFunctionParameters(it.signature.params) &&
                            validateFunctionReturnType((it.signature as QualifiedMethodSignature.QualifiedMethodSig).res)
                    } && validateSummaryType(t.external.values)

                }
            }
    }

    class ErrorContaining(private val line: Int, private vararg val messages: String) : Predicate<CVLError> {
        override fun test(t: CVLError): Boolean {
            val line = this.line.toUInt()
            val range = t.location
            if (range !is Range.Range) {
                println("expected error on line ${line}, but received error has no location")
                return false
            }
            if (range.start.line != line && range.end.line != line) {
                println("expected error on line ${line}, but received error is on lines ${range.start.line}-${range.end.line}")
                return false
            }
            return messages.all {
                if (!t.message.contains(it)) {
                    println("expected error containing ${it}, but received error does not")
                    false
                } else true
            }
        }
    }

    protected fun getEVMType(s: String) =
        SolidityTypeDescription.builtin(s)?.toVMTypeDescriptor() ?: EVMTypeDescriptor.DynamicArrayDescriptor(
            null,
            SolidityTypeDescription.builtin(s.split("[]")[0])!!.toVMTypeDescriptor()
        )

    protected fun testNoErrors(results: ParseResult) {
        val errors = results.errorOrNull()
        assert(errors == null) { "Got Errors! \n ${errors?.forEach { it.printError() }}" }
    }

    protected fun testFlowWithPredicatesCVLError(
        collectionResult: ParseResult,
        predicates: List<Predicate<CVLError>>
    ) {
        val errors = collectionResult.errorOrNull()
        assertNotNull(errors, "Expected errors in CVL, but it type checked")
        val results = errors.filter { cvlError ->
            predicates.none {
                it.test(cvlError)
            }
        }
        assert(results.isEmpty()) {
            results.joinToString(
                prefix = "Got Unexpected Errors! \n",
                separator = "\n",
            ) { it.message }
        }
    }

    /**
     * Asserts that [collectionResult] is not a [CollectingResult.Error], and that all [predicates] are true for the resulting [CVL]
     * Note - if [predicates] is an empty list then the second assert passes trivially.
     */
    protected fun testFlowWithPredicatesCVL(
        collectionResult: ParseResult,
        predicates: List<Predicate<CVL>>
    ) {
        assert(collectionResult is CollectingResult.Result<ProverQuery>) {
            "Unexpected CVL flow failure:\n ${(collectionResult as CollectingResult.Error<CVLError>).messages.joinToString()}"
        }
        val cvl = (collectionResult.force() as ProverQuery.CVLQuery.Single).cvl

        val passPredicates = predicates.all {
            it.test(cvl)
        }
        assert(passPredicates) {
            "Unexpected CVL parse result \n $cvl \n"
        }
    }
}
