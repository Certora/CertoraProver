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

package spec.cvlast.typechecker

import log.*
import ksp.classlists.ListClasses
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import report.TreeViewLocation
import spec.cvlast.*
import utils.DetektDeprecatedClass
import utils.HasKSerializable
import utils.KSerializable
import utils.Range

/**
 * These categories are used to group together errors in the generated list of all error messages.  They are primarily
 * useful for reviewing these; I suspect users will just search for the specific error they're encountering.
 */
enum class CVLErrorCategory(val description: String) {
    /** The user wrote something that doesn't make sense. */
    SYNTAX("Basic syntax"),

    /** Things having to do with types. */
    TYPECHECKING("Type-checking"),

    /** Things having to do with methods block entries. */
    METHODS_BLOCK("Methods-block"),

    /** Things that have changed in CVL2 (perhaps in the future we can change this to "deprecated" or something). */
    CVL2("CVL 2"),

    /** Things that seem reasonable but we haven't implemented. */
    @Suppress("Unused")
    UNSUPPORTED("Unsupported feature"),

    /**
     * Errors regarding CLI options
     */
    CLI("CLI options")
}

/**
 * [CVLErrorType] objects contain metadata about a kind of error for automatically generated documentation.
 *
 * @param category The general type of error
 * @param description A human-readable description of the error (intended for end users).  This will be parsed as
 *   markdown and included into the documentation.  In particular, if you want to reference a particular section of the
 *   documentation you can write "{ref}`label`" where label is the label of a section of the docs.
 *   For example "{ref}`cvl2-methods`".
 */
@ListClasses
@Target(AnnotationTarget.CLASS)
annotation class CVLErrorType(
    val category : CVLErrorCategory,
    val description : String,
) {
    companion object // for `.instances` from [ListClasses]
}

/**
 * [CVLErrorExample] objects contain examples for [CVLError]s.
 *
 * Tests are automatically generated for all classes annotated with [CVLErrorType]; these tests run the [exampleCVLWithRange]
 * and check that an error of the annotated type is produced with message [exampleMessage] at the indicated range (see
 * `ErrorTests.testErrorExamples`)
 *
 * Currently, all tests share the conf (and Solidity file) described by `ErrorTests.testConfPath`; new tests should add
 * features to that file as needed.  If we discover that we actually need different Solidity files for different tests,
 * we will need to add additional fields here (we can give them a default value to avoid changing all existing
 * annotations).
 *
 * @param exampleCVLWithRange A CVL program that produces the [exampleMessage]; the expected location of the error
 *   should be surrounded by `#` characters
 * @param exampleMessage A concrete example of the error message that is produced by running [exampleCVLWithRange], or
 *   "" if the message should not be tested (since annotations cannot have nullable fields).
 */
@ListClasses
@Repeatable
@Target(AnnotationTarget.CLASS)
annotation class CVLErrorExample(
    val exampleCVLWithRange: String,
    val exampleMessage: String = "",
) {
    companion object // for `.instances` from [ListClasses]
}

/**
 * General base class for all error messages.
 * All [CVLError] subtypes should have [CVLErrorType] and [CVLErrorExample] annotations.
 * Note: concrete subtypes are in `CVLErrorClasses.kt`
 */
@KSerializable
sealed class CVLError : HasKSerializable {
    /** The source location of the error */
    abstract val location: Range

    /** The error text, not including decorations like line numbers */
    abstract val message: String

    // Note: this shouldn't be open; subclasses should not override it.  However, some of our testing infrastructure
    // relies on the `message` not containing the former "error prefix", so [Lhs] and [Exp] need to override it.  Once
    // these deprecated classes are removed, we should remove this as well.
    /** A nicely formatted message */
    open fun display(): String = "Error in spec file${(location as? Range.Range)?.let { " ($it)" }.orEmpty()}: $message"

    // STUFF I'D LIKE TO REFACTOR //////////////////////////////////////////////////////////////////////////////////////
    // These things might be good to change if/when we clean up and nicely format our CLI output

    /** Display this error */
    fun printError() {
        CVTAlertReporter.reportAlert(
            CVTAlertType.CVL, CVTAlertSeverity.ERROR, location as? TreeViewLocation, display(), null
        )
    }

    companion object {
        /** Display [errors] to the Logger, sorted by location and with duplicates removed.
         * Optionally includes a header print before */
        fun printErrors(errors: List<CVLError>, heading: String? = null) {
            heading?.let(this::print)
            errors
                .sortedBy { it.location }
                .distinctBy { it.display() }
                .forEach { it.printError() }
        }

        /**
         * To be used only here to redirect the error output to stdout or whatever logger of choice we have.
         * Do not cargo cult. It is to abstract the implementation of choice
         */
        private fun print(msg: String) {
            Logger.alwaysError(msg)
        }
    }

    /** Catch-all category for CVL errors that don't have more information supplied */
    @DetektDeprecatedClass("Make a specific subclass of CVLError instead")
    @KSerializable
    class General(val range: Range, override val message: String) : CVLError() {
        override val location = range
    }

    /** Catch-all error for left-hand side type checking. */
    @DetektDeprecatedClass("Make a specific subclass of CVLError instead")
    @KSerializable
    class Lhs private constructor(override val location: Range, override val message: String, val lhs: CVLLhs) : CVLError() {
        constructor(lhs: CVLLhs, message: String) : this(lhs.tag.range, message, lhs)

        override fun display() = "Error in spec file${(location as? Range.Range)?.let { " ($it)" }.orEmpty()}: could not type expression \"$lhs\", message: $message"
    }

    /** Catch-all error for expression type-checking. */
    @DetektDeprecatedClass("Make a specific subclass of CVLError instead")
    @KSerializable
    class Exp private constructor(override val location: Range, override val message: String, val exp: CVLExp) : CVLError() {
        constructor(exp: CVLExp, message: String) : this(exp.tag.range, message, exp)

        override fun display() = "Error in spec file${(location as? Range.Range)?.let { " ($it)" }.orEmpty()}: could not type expression \"$exp\", message: $message"
    }
}
