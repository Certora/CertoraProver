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

package sbf.support

import sbf.SolanaConfig
import sbf.cfg.*
import sbf.domains.PTAField
import datastructures.stdcollections.*
import dwarf.DebugInfoReader
import utils.*

/**
 * For cases that users should be able to solve themselves
 *
 * @param errorLocation is provided and the errors is displayed in the global notifications of the rule report,
 * this is the location in source code associated to the failure.
 */
open class SolanaError(msg:String, val errorLocation: Range.Range? = null): CertoraException(CertoraErrorType.SOLANA, msg)
/**
 * For cases that that users cannot solve but should report to us
 */
open class SolanaInternalError(msg:String): CertoraInternalException(CertoraInternalErrorType.SOLANA, msg)

/**
 * To report error messages to users
 */
data class UserErrorInfo(val msg: String, val note: String, val help: String, val code: Int)

/**
 * To help devs to debug analysis errors
 */
data class DevErrorInfo(val locInst: LocatedSbfInstruction?,
                        val ptrExp: PointerExpressionError?,
                        val msg: String)

/**
 * To represent either a register or stack access
 * This is useful for debugging PTA errors.
 */
sealed class PointerExpressionError
class PtrExprErrReg(val reg: Value.Reg): PointerExpressionError()
class PtrExprErrStackDeref(val field: PTAField): PointerExpressionError()


/**
 * Pointer analysis specific errors
 */
open class PointerAnalysisError(val devInfo: DevErrorInfo, userInfo: UserErrorInfo)
    : SolanaError(FormattedErrorMessage(devInfo.locInst, devInfo.msg, userInfo).toString(), devInfo.locInst?.getSourceLocationInSourcesDir())

private const val helpSummarizationIsNeeded = "check if there are unexpected external functions that return references (directly or indirectly) and summarize them explicitly.\n" +
"To add a pointer analysis summary, add the summary in one of the summary files passed to the option \"solana_summaries\".\n" +
"If adding a summary is not desirable, then ensure that the code of the offending external functions is analyzed by the prover " +
"by adding their names in one of the inlining files passed to the option \"solana_inlining\"\n"

private val unknownStackPointer = UserErrorInfo(
    msg = "access to an indeterminate stack location",
    note = "The stack being accessed at an offset that is not known statically\n" +
        "Most common root causes are:\n" +
        "\t(1) dynamically computed offset into a stack allocated array\n" +
        "\t(2) a local variable that is indirectly dereferenced by its address",
    help = "To resolve this error consider changing the code to ensure that all accessed stack locations are statically known.\n" +
        "Potential solutions are:\n" +
        "\t(1) (manually) unroll loops that iterate over stack allocated data\n" +
        "\t(2) summarize loops that iterate over stack allocated data\n" +
        "\t(3) move stack allocated arrays to the heap using std::Box\n" +
        "\t(4) remove conditional borrows by unhoisting",
    code = 3001
)

private val unknownStackContent = UserErrorInfo(
    msg = "stack location is not accessible",
    note = "The stack being read at a known offset but its content is not known statically.\n" +
        "The most common root cause is that the number of bytes being read is different from the number of bytes " +
         "last written",
    help = "To resolve this error consider changing the code to ensure that all matched pairs of write-reads access same number of bytes.\n" +
        "Potential solutions are:\n" +
        "\t(1) add LLVM compilation flags that disable compiler optimizations that merge " +
        "consecutive writes of immediate values (common in initialization code) \n" +
        "\t(2) (manually) modify code to disable compiler optimizations as described above",
    code = 3002
)

private val unknownPointerDeref = UserErrorInfo(
    msg = "dereference of a register (pointer) with unknown provenance",
    note = "The provenance (i.e., stack, heap, external) of a dereferenced register is not statically known\n" +
        "Most common root causes are:\n" +
        "\t(1) the pointer originates in an external function that was not properly summarized by pointer analysis\n" +
        "\t(2) the provenance of the register depends on the specific execution path to the current instruction",
    help = "To resolve this error consider one of the following:\n" +
        "\t(1) $helpSummarizationIsNeeded" +
        "\t(2) identify functions to mark `#[inline(never)]` to reduce inlining",
    code = 3003)


private val unknownPointerStore = UserErrorInfo(
    msg = "store of a value with unknown provenance",
    note = unknownPointerDeref.note,
    help = unknownPointerDeref.help,
    code = 3004)

private val unknownMemcpyLen = UserErrorInfo(
    msg = "`memcpy` with dynamically sized length",
    note = " A memcpy with length that is not determined statically. Common root causes are:\n" +
        "\t(1) length depends on the execution path to the current instruction\n" +
        "\t(2) dynamically sized structure whose size depends on user input",
    help = "Resolve by identifying offending instruction and summarize the code to fix the size of the memcpy",
    code = 3005)

private val pointerStackEscaping = UserErrorInfo(
    msg = "illegal store of a stack pointer",
    note = "",
    help = "A stack pointer escapes by being stored into an illegal memory segment (i.e., heap, account data, external memory, etc.)",
    code = 3006)

private val unknownGlobalDeref = UserErrorInfo(
    msg = "illegal dereference of a global variable",
    note = "A dereference of a global variable whose exact address in memory is not statically known.\n" +
        "The most common root cause is a compiler optimization that conditionally borrows a global variable",
    help = "Rewrite offending code to make access to the global variable unconditional",
    code = 3307)

private val derefOfAbsoluteAddress = UserErrorInfo(
    msg = "illegal dereference of an absolute address",
    note = "A dereference of an absolute address whose exact memory segment (heap, globals, etc.) is not statically known.\n" +
        "Common root causes are:\n" +
        "\t(1) the address corresponds to a global variable that has not been identified by the analysis.\n" +
        "\t(2) the instruction dereferences a pointer that originates in an external function that was not properly summarized by pointer analysis",
    help = "To resolve this error consider one of the following:\n" +
        "\t(1) add prover option \"-${SolanaConfig.AggressiveGlobalDetection.name} true\"\n" +
        "\t(2) $helpSummarizationIsNeeded",
    code = 3308)

class UnknownStackPointerError(devInfo: DevErrorInfo)
    : PointerAnalysisError(devInfo, userInfo = unknownStackPointer)

class UnknownStackContentError(devInfo: DevErrorInfo)
    : PointerAnalysisError(devInfo, userInfo = unknownStackContent)

class UnknownPointerDerefError(devInfo: DevErrorInfo)
    : PointerAnalysisError(devInfo, userInfo = unknownPointerDeref)

class UnknownPointerStoreError(devInfo: DevErrorInfo)
    : PointerAnalysisError(devInfo, userInfo = unknownPointerStore)

class UnknownMemcpyLenError(devInfo: DevErrorInfo)
    : PointerAnalysisError(devInfo, userInfo = unknownMemcpyLen)

class PointerStackEscapingError(devInfo: DevErrorInfo)
    : PointerAnalysisError(devInfo, userInfo = pointerStackEscaping)

class UnknownGlobalDerefError(devInfo: DevErrorInfo)
    : PointerAnalysisError(devInfo, userInfo = unknownGlobalDeref)

class DerefOfAbsoluteAddressError(devInfo: DevErrorInfo)
    : PointerAnalysisError(devInfo, userInfo = derefOfAbsoluteAddress)

class NoAssertionError(rule: String) : SolanaError(
    FormattedErrorMessage(
        locInst = null,
        userInfo = UserErrorInfo(
            msg  = "Nothing to verify. Rule $rule has no assertions",
            note = "Check the following:\n" +
                "\t(1) there are calls to Certora asserts in the rule or functions that it calls\n" +
                "\t(2) there are runtime errors or undefined behavior that allowed the compiler to establish that all specified assertions are unreachable",
            help ="",
            code = 4000
        ),
        devMsg = "").toString())

class NoAssertionErrorAfterSlicer(rule: String) : SolanaError(
    FormattedErrorMessage(
        locInst = null,
        userInfo = UserErrorInfo(
            msg  = "Nothing to verify. Rule $rule has no assertions after slicing",
            note = "Most common root causes are:\n" +
                "\t(1) assertions are so trivial that they can be discharged syntactically\n" +
                "\t(2) there are runtime errors or undefined behavior that allowed the prover front-end to establish that all specified assertions are unreachable",
            help ="",
            code = 4001
        ),
        devMsg = "").toString())


class CannotParseSummaryFile(line: String, filename: String, grammar: String, help: String = "") : SolanaError(
    FormattedErrorMessage(
        locInst = null,
        userInfo = UserErrorInfo(
            msg  = "Cannot parse \"$line\" in $filename",
            note = "Expected format is defined by this grammar:\n$grammar",
            help,
            code = 5000
        ),
        devMsg = "").toString())

class CannotParseInliningFile(line: String, filename: String, grammar: String, help: String ="") : SolanaError(
    FormattedErrorMessage(
        locInst = null,
        userInfo = UserErrorInfo(
            msg  = "Cannot parse \"$line\" in$filename",
            note = "Expected format is defined by this grammar:\n$grammar",
            help,
            code = 5001
        ),
        devMsg = "").toString())

private fun nearestPowerOfTwo(n: Int): Int {
    check(n > 0)
    return Integer.highestOneBit(n).let {
        if (it < n) {
            it shl 1
        } else {
            it
        }
    }
}

class SmashedStack(locInst: LocatedSbfInstruction?, extraSpace: Int) : SolanaError(
    FormattedErrorMessage(
        locInst = locInst,
        userInfo = UserErrorInfo(
            msg  = "Current stack size is ${SolanaConfig.StackFrameSize.get()} and stack offset exceeded max offset by $extraSpace.",
            note = "",
            help = "Please increase the stack size with option \"-${SolanaConfig.StackFrameSize.name} ${nearestPowerOfTwo(SolanaConfig.StackFrameSize.get() + extraSpace)}\".",
            code = 6000
        ),
        devMsg = if (locInst != null) {"${locInst.inst}"} else { "" }).toString())

/**
 * To create formatted user messages
 */
private class FormattedErrorMessage(private val locInst: LocatedSbfInstruction?,
                                    private val devMsg: String,
                                    private val userInfo: UserErrorInfo) {

    private fun getSourceLocation(): String {
        val address = locInst?.inst?.metaData?.getVal(SbfMeta.SBF_ADDRESS)
        if (address != null) {
            val frames = DebugInfoReader.getInlinedFrames(listOf(address))
            val lines = frames[address]
            if (lines != null) {
                val strB = StringBuilder()
                if (lines.isNotEmpty()) {
                    val firstLineRange = lines.first()
                    strB.append("source: ${firstLineRange.file}:${firstLineRange.lineNumber}\n")
                }
                for (line in lines.drop(1)) {
                    strB.append("  from: ${line.file}:${line.lineNumber}\n")
                }
                return strB.toString()
            }
        }
        return ""
    }

    override fun toString(): String {
        val strB = StringBuilder()

        strB.append("[${userInfo.code}] ${userInfo.msg}\n")
        strB.append(getSourceLocation())
        if (userInfo.note != "") {
            strB.append("\nnote: ${userInfo.note}\n")
        }
        if (userInfo.help != "") {
            strB.append("\nhelp: ${userInfo.help}\n")
        }
        if (devMsg != "" && SolanaConfig.PrintDevMsg.get()) {
            val address = locInst?.inst?.metaData?.getVal(SbfMeta.SBF_ADDRESS)
            if (address != null) {
                strB.append("\nDev message(0x${address.toString(16)}):")
            } else {
                strB.append("\nDev message:")
            }
            strB.append("$devMsg\n")
        }
        return strB.toString()
    }
}