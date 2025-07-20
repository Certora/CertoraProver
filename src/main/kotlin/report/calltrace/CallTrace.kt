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

package report.calltrace

import analysis.*
import config.ConfigType
import report.RuleAlertReport
import report.calltrace.CallInstance.InvokingInstance.CVLRootInstance
import report.calltrace.generator.generateCallTrace
import report.calltrace.printer.CallTracePrettyPrinter
import spec.cvlast.*
import utils.*
import vc.data.*

/** Describes how the method call ended. */
enum class CallEndStatus(private val repString: String) {
    REVERT("REVERT"),
    THROW("THROW"),
    SUMMARIZED("SUMMARIZED"),
    DISPATCHER("DISPATCHER"),
    DEFAULT_HAVOC("DEFAULT HAVOC"),
    REVERT_CAUSE("REVERT CAUSE"),
    REVERT_DUMP("DUMP"),
    TRANSFER("TRANSFER"),
    VARIABLE_UNKNOWN("UNKNOWN"),
    VARIABLE_DONT_CARE("DONT CARE"),
    VARIABLE_CONCRETE("CONCRETE"),
    VARIABLE_HAVOC("HAVOC"),
    VARIABLE_HAVOC_DEPENDENT("HAVOC DEPENDENT"),
    ASSERT_CAST("ASSERT CAST"),
    REROUTE("REROUTE"),
    NONE("")
    ;

    fun toJSONRepresentation(): String = repString
}

/** Attributes of the call trace */
enum class CallTraceAttribute(private val repString: String) {
    MESSAGE("message"),
    TEXT("text"),
    VALUE("value"),
    VALUES("values"),
    VARIABLE_NAME("variableName"),
    TOOLTIP("tooltip"),
    UI_ID("uiId"),
    TYPE("type"),
    TRUNCATABLE("truncatable"),
    ARGUMENTS("arguments"),
    STATUS("status"),
    CHILDREN_LIST("childrenList"),
    JUMP_TO_DEFINITION("jumpToDefinition"),
    CHANGED_SINCE_PREV("changedSincePreviousPrint"),
    CHANGED_SINCE_START("changedSinceStart"),
    STORAGE_ID("storageId"),
    ;

    operator fun invoke(): String = repString
}

/**
 * This class is constructed using [generateCallTrace].
 * Contains the [callHierarchyRoot] of a [IRule] which is the result of running [generateCallTrace], up to the point
 * where [generateCallTrace] returned.
 *
 * If a violated assert is found (as is expected), the [ViolationFound] variant will contain this violation.
 *
 * Otherwise, if an exception is encountered during call trace generation, or if no violation is found, the [Failure]
 * variant will be returned, containing the exception.
 */
sealed class CallTrace {
    abstract val callHierarchyRoot: CVLRootInstance
    abstract val alertReport: RuleAlertReport?

    /** For usage in JUnit tests (for now at least). */
    val formatter get() = callHierarchyRoot.formatter

    data class ViolationFound(override val callHierarchyRoot: CVLRootInstance, val violatedAssert: LTACCmd) : CallTrace() {

        override val alertReport: RuleAlertReport?
            get() = null

        val violatedAssertCond: TACExpr
            get() = if (violatedAssert.cmd is TACCmd.Simple.AssertCmd) {
                TACExprFactTypeCheckedOnlyPrimitives.LNot(violatedAssert.cmd.o.asSym())
            } else if (violatedAssert.cmd is TACCmd.Simple.AnnotationCmd && violatedAssert.cmd.annot.v is AssertSnippet<*>) {
                violatedAssert.cmd.annot.v.assertCond.asSym()
            } else {
                error("unexpected CallTrace's violation-command: ${violatedAssert.cmd}")
            }
    }

    class Failure(override val callHierarchyRoot: CVLRootInstance, val exception: CallTraceException, printer: CallTracePrettyPrinter?) : CallTrace() {
        init {
            val instance = CallInstance.ErrorInstance.EarlyExit()
            callHierarchyRoot.addChild(instance)
            printer?.append(instance)
        }

        override val alertReport: RuleAlertReport.Error
            get() = RuleAlertReport.Error(exception.msg, exception)
    }

    data class DisabledByConfig(override val callHierarchyRoot: CVLRootInstance, val conf: ConfigType<*>) : CallTrace() {
        override val alertReport: RuleAlertReport.Warning
            get() = RuleAlertReport.Warning(
                "CallTrace generation was disabled through the command-line (flag: ${conf.name})"
            )
    }
}

// sanitizes the string to form a Rule identifier
fun String.toRuleIdentifier(): String {
    return sanitizeRuleName(this)
}

// only use valid java identifier characters for Rule names
private fun sanitizeRuleName(s: String): String =
    s.replace(" ", "_")
        .replace(":", "_")
        .replace(".", "_")
        .filter {
            Character.isJavaIdentifierPart(it)
        }

class CallTraceException(msg: String, t: Throwable? = null) : CertoraException(CertoraErrorType.CALLTRACE, msg, t)

/** ad-hoc config flag for now, switching the `altReprs` json entries off / on */
internal const val altReprsInTreeView = true
