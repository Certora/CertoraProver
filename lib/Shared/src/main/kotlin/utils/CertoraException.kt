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

package utils

import com.certora.collect.*
import datastructures.stdcollections.*
import log.*
import java.io.Serializable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

enum class CertoraErrorType {
    DECOMPILER_FUNCTION_POINTERS_SOLIDITY,
    DISABLED_SOLVER_RUNNING,
    INVALID_LINKING,
    ILLEGAL_RULE_CHOICE,
    /** Reported when the user is configuring the solver setup manually and has set up an infeasible configuration. */
    MANUAL_SOLVER_CONFIGURATION,
    NO_SANITY_RESULTS,
    /** Reported when the user uses calldataarg for an overloaded method and tries to get the return value.
     * (no return value is fine - it could be a parametric invocation that ignores the output.) */
    OVERLOADING_WITH_CALLDATAARG_AND_RETURN,
    RECURSIVE_INLINING,
    SUMMARIZATION,
    SYNTAX,
    TASK_TIMEOUT,
    NOT_RUN,
    TRANSFORMULA_SUMMARIES,
    UNRESOLVED_CALL,
    CVL,
    NO_MATCHING_METHOD,
    UNKNOWN,
    COUNTEREXAMPLE,
    CALLTRACE,
    GROUNDING_FAIL,
    UNSUPPORTED_SUMMARIZATION,
    BAD_CONFIG,
    EVENT_REPORTING,
    TREE_VIEW,
    INCOMPATIBLE_SERIALIZATION,
    UNUSED_SUMMARY,
    CODE_SIZE,
    DIRECT_STORAGE_ACCESS,
    HOOK_INLINING, // user facing hook inlining issues
    CONTRACT_EXTENSION,
    SOLANA,
}

enum class CertoraInternalErrorType(val owners: List<String>) {
    CVL_COMPILER(listOf("john", "shelly", "naftali")),
    CVL_AST(listOf("or", "shelly", "naftali", "sameer")),
    CVL_TYPE_CHECKER(listOf("naftali", "john")),
    TRANSFORMULA_SUMMARIES(listOf("alex")),
    HOOK_INLINING(listOf("alexb", "john")),
    SUMMARY_INLINING(listOf("john")),
    GHOSTS(listOf("john","shelly")),
    HASHING(listOf("alex")),
    TAC_TYPING(listOf("john")),
    INTERNAL_TAC_TYPE_CHECKER(listOf("john","shelly")), // "Internal" for the users "TAC" for us
    TAC_TRANSFORMER_EXCEPTION(listOf("shelly","alex")),
    SOLC_INTERFACE(listOf()),
    MULTIPLE_LOCAL_CVT_INSTANCES(listOf("shelly")),
    CERTORA_BUILD_INTERFACE(listOf("shelly")),
    CVL_TAC_ALLOCATION(listOf("alexb")),
    LEXPRESSION_TYPING(listOf("gereon", "yoavr")),
    UTILS(emptyList()),
    SOLANA(listOf("jorge"))
}

/** For cases that users should be able to solve themselves (make sure [msg] gives enough information for that). */
open class CertoraException(val type: CertoraErrorType, val msg: String, t: Throwable? = null) : Exception(msg, t) {
    companion object {
        data class ExceptionDetailsForErrorCode(val exceptionName: String, val context: String) {
            companion object {
                operator fun invoke(t: Throwable): ExceptionDetailsForErrorCode {
                    return ExceptionDetailsForErrorCode(t.javaClass.name, t.stackTrace.firstOrNull()?.toString().orEmpty())
                }
            }
        }

        private fun computeExceptionErrorCode(e: ExceptionDetailsForErrorCode): UInt =
            e.hashCode().toUInt()

        fun getErrorCodeForException(t: Throwable) = computeExceptionErrorCode(ExceptionDetailsForErrorCode(t))

        private fun Throwable.isTimeout(): Boolean =
            this is InterruptedException || this is RejectedExecutionException || this is TimeCheckException || (this is ExecutionException &&
                this.cause?.isTimeout() == true)

        private fun Throwable.notRun(): Boolean = this is CancellationException

        fun fromException(t: Throwable, customMsg: String? = null): CertoraException {
            return if (t is CertoraException) {
                t
            } else if (t.isTimeout()) {
                CertoraException(
                    CertoraErrorType.TASK_TIMEOUT,
                    customMsg ?: "Timeout",
                    t
                )
            } else if (t.notRun()) {
                CertoraException(
                    CertoraErrorType.NOT_RUN,
                    customMsg ?: "Did not run"
                )
            } else {
                val exceptionDetails = ExceptionDetailsForErrorCode(t)
                val code = computeExceptionErrorCode(exceptionDetails)
                Logger.alwaysError("Unknown error $t, code $code", t)
                Logger.regression { "Error code of $t: ${exceptionDetails}: $code" }
                CertoraException(
                    CertoraErrorType.UNKNOWN,
                    "Encountered an unexpected error in Prover, please report in certora.com. Error code $code${
                        // show either the fancy message or the default message
                        // (default exception message may be too detailed, and is available in logs anyway)
                        (customMsg ?: t.message)?.let {". Error message: $it" }.orEmpty()
                    }",
                    t
                )
            }
        }

        fun fromExceptionWithRuleName(t: Throwable, ruleName: String): CertoraException {
            return if (t.isTimeout()) {
                fromException(t, "Rule $ruleName timed-out")
            } else if (t.notRun()) {
                val cause = t.message?.let{": $it"}.orEmpty()
                fromException(t, "Rule $ruleName did not run${cause}")
            } else {
                fromException(t, "Rule $ruleName had an error")
            }
        }
    }
}

/** For cases that users cannot solve but should report to us. */
open class CertoraInternalException(val type: CertoraInternalErrorType, val internalMsg: String, t: Throwable? = null) :
    IllegalStateException(
        "Certora internal exception; please report this to Certora.\n" +
                "Exception type: $type\n" +
                "Exception message: $internalMsg",
        t
    )

@OptIn(ExperimentalContracts::class)
inline fun certoraCheck(b: Boolean, type: CertoraErrorType, f: () -> String) {
    contract {
        returns() implies b
    }
    if (!b) {
        val msg = "$type: ${f()}"
        Logger.regression { msg }
        throw CertoraException(type, msg)
    }
}

@Treapable
sealed class UserFailMessage: Serializable {
    abstract val contractName: String
    abstract val methodNameOrSig: String
    abstract val errorCode: UInt

    /** full message should include the error code */
    abstract fun getFullMessage(): String

    /** core message should include just the details without error code */
    abstract fun getCoreMessage(): String

    data class StorageAnalysisFailureMessage(
        override val contractName: String,
        override val methodNameOrSig: String,
        override val errorCode: UInt,
        val postFixMessage: String,
        val base: String
    ): UserFailMessage() {
        override fun getCoreMessage(): String =
            "Storage analysis for $base failed in contract ${contractName}, " +
                " function ${methodNameOrSig}. ${postFixMessage}"

        override fun getFullMessage(): String =
            getCoreMessage() +
                " Error code $errorCode"
    }
}
