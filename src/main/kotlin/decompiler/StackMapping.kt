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

package decompiler

import com.certora.collect.*
import compiler.SourceSegment
import config.Config
import disassembler.EVMCommand
import disassembler.EVMInstruction
import log.Logger
import spec.cvlast.CVLType
import utils.*
import vc.data.SnippetCmd.EVMSnippetCmd.ContractSourceSnippet
import vc.data.TACCmd
import vc.data.TACMetaInfo
import vc.data.TACSymbol
import java.io.Serializable

/**
 * Data obtained from Solidity source code textual parsing. This is the method we
 * currently use to extract name and type information for disassembled Solidity code.
 */
@KSerializable
@Treapable
data class ParseData(val name: String?, val typ: CVLType.PureCVLType?, val kind: StatementKind) : Serializable {

    override fun hashCode() = hash { it + name + typ + kind }

    companion object {
        private val whitespace = Regex("""\s+""")
        private val keywords = listOf("contract", "function") //TODO: add more
        private val cStyleIdent = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
        private val reservedNames = listOf("true", "false") //TODO: add more
        private fun isIdent(token: String) = cStyleIdent.matches(token) && token !in reservedNames
        private fun isOp(token: String?) = token != null && ops.any { token.startsWith(it) } //also handles cases like "+="
        private val ops = listOf(
            "+", "-", "*", "/", "%", "**", "<<", ">>", "&", "|", "^", "<<", ">>", "<=", "<", "==", "!=", ">=", ">"
        )
        private fun String.parseType() = CVLType.valueFromString(this)

        /**
         * Attempt to parse (a small subset of) Solidity source code disassembler code snippets,
         * to extract name and type information and detect the snippet's [StatementKind].
         */
        private fun parse(tokens: List<String>): ParseData? {

            // cases which can be immediately dismissed, since we only parse a small subset
            // of Solidity statements.
            fun isTriviallyNotParsed(tokens: List<String>) =
                tokens.getOrNull(0) in keywords
                    || isOp(tokens.getOrNull(1))

            if (tokens.isEmpty() || isTriviallyNotParsed(tokens)) {
                return null
            }

            // if it's a single token, it should be either:
            // 1. a previously-referenced var name, or
            // 2. a value literal, or
            // 3. the name of the type in an anonymous declaration
            // currently, we only have a use for the last case, and we also don't
            // bother with non-primitive types.
            // this allows us to ignore any other cases where we only have a single token.
            if (tokens.size == 1) {
                return tokens[0]
                    .parseType()
                    ?.let { typ -> ParseData(null, typ, StatementKind.AnonymousDeclaration) }
            }

            check(tokens.size > 1) { "must have at least 2 tokens at this point, but found ${tokens.size}" }

            // if it's a return statement, in the form of "return" or "return varName"
            // we only have use for a named return.
            if (tokens[0] == "return" && isIdent(tokens[1])) {
                return ParseData(tokens[1], null, StatementKind.Return)
            }

            // if it's an assignment, in the form of "varName =" or "varType varName ="
            when (tokens.indexOf("=")) {
                1 -> {
                    return ParseData(tokens[0], null, StatementKind.Assignment)
                }
                2 -> {
                    return tokens[0]
                        .parseType()
                        ?.let { typ -> ParseData(tokens[1], typ, StatementKind.Assignment) }
                }
            }

            // otherwise, expect it to be a named declaration in the form of "varType varName"
            // we expect the type to be primitive, and don't currently handle it otherwise.
            val typ = tokens[0].takeIf(::isIdent)?.parseType()
            val name = tokens[1].takeIf(::isIdent)
            if (typ != null && name != null) {
                return ParseData(name, typ, StatementKind.NamedDeclaration)
            }

            return null
        }

        /**
         * Attempts to extract data from the source snippet that was obtained from a [TACMetaInfo],
         * the [TACMetaInfo.SourceDetails] is still kept even if parsing was unsuccessful.
         */
        fun fromSourceDetails(sourceDetails: SourceSegment): SourceParseResult {
            // currently it is enough to look at the first 3 tokens at most
            val tokens = sourceDetails.content.trim().splitToSequence(whitespace).take(3).toList()
            val parsed = parse(tokens)

            return if (parsed != null) {
                SourceParseResult.Success(sourceDetails, parsed)
            } else {
                SourceParseResult.Failure(sourceDetails)
            }
        }
    }
}

enum class StatementKind : Serializable {
    Assignment, AnonymousDeclaration, NamedDeclaration, Return
}

// for convenience
private fun TACMetaInfo.parseSource() = this.getSourceDetails()?.let { ParseData.fromSourceDetails(it) }

@KSerializable
@Treapable
sealed class SourceParseResult : AmbiSerializable {
    abstract val originalSource: SourceSegment

    @KSerializable
    data class Success(override val originalSource: SourceSegment, val data: ParseData) : SourceParseResult()

    @KSerializable
    data class Failure(override val originalSource: SourceSegment) : SourceParseResult()

    fun dataOrNull() = (this as? Success)?.data
}

/**
 * Runs along with the [Jimpler] and keeps track of stack pushes and pops, as
 * well as the assembly commands being read. As it reads the processed assembly commands,
 * attempts to look for various patterns in the commands to detect when certain events
 * have occurred (currently, only variable declarations and assignments) and may emit
 * a [TACCmd.Simple.AnnotationCmd] in response, to mark that point in the source code.
 * [slotData] is a stack that models the current information in the Solidity stack.
 * [jimplerTop] and [top] are the stack position. the [Jimpler] stack grows "down"
 * (towards index 0) while this one grows "up".
 * [annotationsToEmit] is cleared on every cycle, and contains the annotations
 * that will be emitted in the current cycle, if any.
 */
class StackMapping(
    private val logger: Logger,
    private val stackSize: Int,
    private val varAtJimplerStackPos: (Int) -> TACSymbol.Var
) {
    val isFeatureFlagEnabled = Config.EmitSoliditySourceAnnotations.get()

    private var jimplerTop = stackSize
    private val top //since the Jimpler stack grows downwards and ours grows upwards
        get() = stackSize - jimplerTop

    private val slotData = ArrayList<StackSlotData>(stackSize)
    val stack: List<StackSlotData> //a view into the stack, containing only the values which are valid
        get() = if (slotData.isEmpty()) {
            listOf()
        } else {
            slotData.subList(0, top + 1)
        }

    private val annotationsToEmit = mutableListOf<TACCmd.Simple.AnnotationCmd>()
    val emittedOnThisRead: List<TACCmd.Simple.AnnotationCmd> //public getter for private property
        get() = annotationsToEmit


    /**
     * Solidity stack content. Currently, we classify stack slots into either [Variable] which
     * is a name binding, and [Unknown] which is anything else.
     */
    sealed class StackSlotData : Serializable {
        data class Variable(val parsed: SourceParseResult.Success): StackSlotData() {
            override fun toString(): String {
                val content = parsed.data.name?.let { "(named: $it)" }
                    ?: parsed.data.typ?.let { "(type: $it)" }
                    ?: ""

                return "Variable$content"
            }
        }
        object Unknown : StackSlotData() {
            override fun toString() = "Unknown"
            private fun readResolve(): Any = Unknown
        }
    }

    private fun atOffsetFromTop(offset: Int): StackSlotData? {
        return (top - offset)
                .takeIf { it in 0..top }
                ?.let(slotData::getOrNull)
    }

    private fun set(i: Int, data: StackSlotData) {
        when (i) {
            in 0 until slotData.size -> { slotData[i] = data }
            top -> { slotData.add(data) }
            else -> { logger.error { "attempt to set value at invalid index $i" } }
        }
    }

    private fun fromPushCmd(metaInfo: TACMetaInfo) {
        val data = when (val parsed = metaInfo.parseSource()) {
            is SourceParseResult.Success -> StackSlotData.Variable(parsed)
            null, is SourceParseResult.Failure -> StackSlotData.Unknown
        }

        set(top, data)
    }

    private fun previousSlotVariable(offset: Int): StackSlotData.Variable? {
        val previous = atOffsetFromTop(offset).also {
            if(it == null) {
                logger.warn {
                    "Attempt to access element at invalid position ${top - offset}"
                }
            }
        }

        return previous as? StackSlotData.Variable
    }

    /**
     * in local contexts, DupN commands seem to model data flow:
     * the variable that was previously at position -N in the stack, is now
     * being referenced. it is possible to use this for modeling data flow,
     * but for now we use this information only for the discovery of variables
     * not currently in our stack, so only for new variable declarations
     */
    private fun fromDupCmd(metaInfo: TACMetaInfo) {
        val parsed = metaInfo.parseSource()

        val data = if (parsed is SourceParseResult.Success
                && parsed.data.kind == StatementKind.NamedDeclaration) {
            StackSlotData.Variable(parsed)
        } else {
            StackSlotData.Unknown
        }

        set(top, data)
    }

    private fun fromSwapCmd(metaInfo: TACMetaInfo, offset: Int) {
        // regardless of the result of the Swap command, we treat the variable
        // that will now be at the top of the stack as an unknown.
        set(top, StackSlotData.Unknown)

        // ...however, we also try getting more info about the variable at the specified offset
        var parsed = metaInfo.parseSource()

        if (parsed is SourceParseResult.Success) {
            val prevData = previousSlotVariable(offset)?.parsed?.dataOrNull()
            var currData = parsed.data

            val bothNamesMatch = prevData != null
                && (prevData.kind == StatementKind.AnonymousDeclaration || prevData.name == currData.name)

            if (bothNamesMatch) {
                // try to propagate type information from previous information on the same stack offset
                if (prevData?.typ != null) {
                    if (currData.typ == null) {
                        // then we can use previously detected type data,
                        // either from when the variable was previously declared/assigned,
                        // or from the function signature (in the case of a return statement).
                        currData = currData.copy(typ = prevData.typ)
                        parsed = parsed.copy(data = currData)
                    } else if (prevData.typ != currData.typ){
                        // we expect that type must be preserved
                        logger.warn {
                            "At position ${top - offset}: " +
                                "previousData had type ${prevData.typ}, but now has type ${currData.typ}"
                        }
                    }
                }

                // now, since the names match
                val data = StackSlotData.Variable(parsed)
                set(top - offset, data)

                val stackVar = varAtJimplerStackPos(jimplerTop + offset)
                val annotation = ContractSourceSnippet
                    .AssignmentSnippet(data.parsed, stackVar)
                    .toAnnotation()
                annotationsToEmit.add(annotation)

            } else {
                logger.warn {
                    "At position ${top - offset}: " +
                        "expected a variable named ${currData.name} but found $prevData instead"
                }
            }
        }
    }

    /**
     * runs at the end of every cycle of [Jimpler.translateAssemblyToTACCmd]. updates this class's internal
     * stack position to match that of [Jimpler], and looks at the [EVMCommand] that has been processed
     * to both update this class's own stack and try and detect patterns in the currently-seen assembly commands.
     */
    fun updateFromJimplerState(jimplerTopBefore: Int, jimplerTopAfter: Int, cmd: EVMCommand, metaInfo: TACMetaInfo) {
        jimplerTop = jimplerTopAfter
        annotationsToEmit.clear()

        when (val inst = cmd.inst) {
            is EVMInstruction.PUSH -> {
                fromPushCmd(metaInfo)
            }

            is EVMInstruction.SWAP -> {
                fromSwapCmd(metaInfo, inst.swapNum)
            }

            is EVMInstruction.DUP -> {
                fromDupCmd(metaInfo)
            }

            else -> {
                val stackSizeIncreased = jimplerTopBefore > jimplerTopAfter

                // a single EVM command may `pop` multiple times,
                // but it can only `push` once. this means a single command
                // can only update the element at the top of the stack.
                if (stackSizeIncreased) {
                    val slotData = StackSlotData.Unknown
                    set(top, slotData)
                }
            }
        }
    }
}
