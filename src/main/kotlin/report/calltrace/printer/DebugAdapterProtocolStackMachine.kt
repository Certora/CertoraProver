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

package report.calltrace.printer

import analysis.LTACCmd
import analysis.icfg.Inliner
import analysis.icfg.Summarization
import analysis.icfg.SummaryStack.END_EXTERNAL_SUMMARY
import analysis.icfg.SummaryStack.END_INTERNAL_SUMMARY
import analysis.icfg.SummaryStack.START_EXTERNAL_SUMMARY
import analysis.icfg.SummaryStack.START_INTERNAL_SUMMARY
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import analysis.storage.DisplayPath
import analysis.storage.InstantiatedDisplayPath
import aws.smithy.kotlin.runtime.util.push
import cli.Ecosystem
import com.certora.collect.*
import config.Config
import config.DebugAdapterProtocolMode
import datastructures.persistentStackOf
import datastructures.stdcollections.*
import dwarf.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import log.*
import org.jetbrains.annotations.TestOnly
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.SolanaCallTraceValueFormatter
import report.calltrace.sarif.Sarif
import report.checkWarn
import rules.ContractInfo
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.CVLCompiler
import spec.CVLDefinitionSite
import spec.cvlast.CVLType
import spec.cvlast.SpecCallSummary
import spec.cvlast.SpecType
import spec.cvlast.VMParam
import spec.rules.IRule
import tac.DataField
import utils.*
import vc.data.*
import vc.data.TACMeta.CVL_DISPLAY_NAME
import vc.data.TACMeta.CVL_GHOST
import vc.data.TACMeta.CVL_RANGE
import vc.data.TACMeta.CVL_STRUCT_PATH
import vc.data.TACMeta.CVL_VAR
import vc.data.TACMeta.SNIPPET
import vc.data.state.TACValue
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.util.zip.GZIPOutputStream

private val logger = Logger(LoggerTypes.CALLTRACE)

/**
 * A pop operation that will remove the top elements from the frames/call stack.
 * Note, that pop operation is mutually exclusive with [DebugAdapterStep] and [DebugAdapterPushAction].
 * A statement can only be any of the three.
 */
interface DebugAdapterPopAction : DebugAdapterAction {
    /**
     * When false, the action won't be persisted to JSON.
     */
    val persist: Boolean
        get() = true
}


/**
 * A push operation that will push [stackElement] onto the frames/call stack.
 * Note, that push operation is mutually exclusive with [DebugAdapterStep] and [DebugAdapterPopAction].
 * A statement can only be any of the three.
 */
interface DebugAdapterPushAction : DebugAdapterAction {
    val stackElement: StackEntry

    /**
     * When false, the action won't be persisted to JSON.
     */
    val persist: Boolean
        get() = true
}


/**
 * This interface can be used either on a [TACCmd.Simple] or on a set of Annotations that implement the interface,
 * then the debugger will process the command.
 */
interface DebugAdapterAction

/**
 * Data classes that represent Stack elements.
 * The [name] property is used to display the call stack in the VSCode debugger.
 *
 * If a [scopeRange] is provided, the [DebugAdapterProtocolStackMachine] ensures that when
 * encountering a debug step with a range r, that `r.isWithin([scopeRange])`. See [report.calltrace.printer.DebugAdapterProtocolStackMachine.visit]
 * for details.
 */
@Treapable
@Serializable
sealed class StackEntry(val name: String, val scopeRange: Range.Range?) : AmbiSerializable {

    @Treapable
    @Serializable
    data class CVLFunction(
        private val _name: String,
        private val _scopeRange: Range.Range?
    ) : StackEntry(_name, _scopeRange) {
        constructor(function: spec.cvlast.CVLFunction) : this(
            _name = function.toString(),
            _scopeRange = function.range.nonEmpty()
        )
    }

    @Treapable
    @Serializable
    data class CVLHook(
        private val _name: String,
        private val _scopeRange: Range.Range?
    ) : StackEntry(_name, _scopeRange) {
        constructor(hook: spec.cvlast.CVLHook) : this(
            _name = "Hook in range ${hook.range}",
            _scopeRange = hook.range.nonEmpty()
        )
    }

    @Treapable
    @Serializable
    data class Summary(
        private val _name: String,
        private val _scopeRange: Range.Range?
    ) : StackEntry(_name, _scopeRange) {
        constructor(summary: Summarization.AppliedSummary) : this(
            _name = when (summary) {
                is Summarization.AppliedSummary.Prover,
                is Summarization.AppliedSummary.FromUserInput -> summary.specCallSumm.summaryName

                Summarization.AppliedSummary.LateInliningDispatcher -> "Prover internal summary application"
            },
            _scopeRange = ((summary as? Summarization.AppliedSummary.FromUserInput)?.specCallSumm as? SpecCallSummary.ExpressibleInCVL)?.range?.nonEmpty()
        )
    }

    @Treapable
    @Serializable
    data class Rule(
        private val _name: String,
        private val _scopeRange: Range.Range?
    ) : StackEntry(_name, _scopeRange) {
        constructor(rule: IRule) : this(
            _name = if (rule.ruleType is SpecType.Single.InvariantCheck) {
                "invariant "
            } else {
                "rule "
            } + rule.ruleIdentifier.toString(),
            _scopeRange = rule.range.nonEmpty()
        )
    }

    @Treapable
    @Serializable
    data class SolidityFunction(
        private val _name: String,
        private val _scopeRange: Range.Range?
    ) : StackEntry(_name, _scopeRange)


    @Treapable
    @Serializable
    data class SolanaFunction(
        private val _name: String,
        private val _scopeRange: Range.Range?
    ) : StackEntry(_name, _scopeRange)
}


/**
 * Used to classify different variable types. This is an internal data structure only.
 * For the VSCode extension, these variables types will further be merged to high-level
 * representations. (see [report.calltrace.printer.DebugAdapterProtocolStackMachine.toVariableMapping])
 */
enum class VariableType(val explanation: String, val header: VariableHeader) {
    /** Values are computed by the regular call trace [report.globalstate.GhostsState]*/
    GHOST_STATE_CALLTRACE("Ghost state", VariableHeader.GLOBAL),

    /** Values are computed by the regular call trace [report.globalstate.StorageState]*/
    STORAGE_STATE_CALLTRACE("Storage state", VariableHeader.GLOBAL),

    /** Values are computed by the regular call trace [report.globalstate.BalancesState]*/
    BALANCES_CALLTRACE("Balances", VariableHeader.BALANCES),


    STORAGE_STATE("Storage State ", VariableHeader.GLOBAL),

    CVL_GHOST("Global CVL Variables", VariableHeader.GLOBAL),
    CVL_PERSISTENT_GHOST("Global persistent CVL Variables", VariableHeader.GLOBAL),
    CONTRACTS("Contracts in scene", VariableHeader.GLOBAL),

    CVL_LOCAL("Local variable in CVL", VariableHeader.LOCALS),
    LOCAL("Local variable in Solidity", VariableHeader.LOCALS)
}

/**
 * These headers are used to group the variables in the VSCode extension by their types.
 */
enum class VariableHeader(val displayName: String) {
    GLOBAL("Globals"),
    LOCALS("Locals"),
    BALANCES("Balances")
}

/**
 * The goal of this class is to generate a JSON representation that is consumed by the
 * Debug Adapter Protocol (https://microsoft.github.io/debug-adapter-protocol/)
 *
 * This is achieved by a stack machine that is build by traversing the entire TAC program (using the [visit] function).
 * For each statement `s` of the TAC program that `visit` is called for, it holds that
 * [frames] represents the current frames / call stack for `s`.
 *
 * Each frame is of type [StackFrame] and holds the current scope and the _local_ variables corresponding
 * to the frame, i.e. local variables and parameters to the function.
 *
 * Additionally, this class holds references to [globalVariables] for instance: Storage state, ghost state
 * and contract instances. These global variables are just passed from the regular call trace and translated
 * into the appropriate format.
 */
class DebugAdapterProtocolStackMachine(
    val rule: IRule,
    val scene: ISceneIdentifiers,
    val model: CounterexampleModel,
    val formatter: CallTraceValueFormatter,
) {
    companion object {
        /**
         * A version identifier to communicate the schema to the VSCode extension.
         * If there are breaking changes (renaming of properties or removal of properties,
         * the version must be increased on the Kotlin side and on the VSCode extension side to avoid
         * crashes).
         */
        private const val JSON_SCHEMA_VERSION = "0.2"

        /**
         * A flag to make all variable update globally, this is only for debugging purposes
         */
        private const val ONLY_GLOBAL_VARIABLES = false
    }

    data class VariableIdentifier(val type: VariableType, val displayPath: InstantiatedDisplayPath)

    private val globalVariables: MutableMap<VariableIdentifier, DebuggerValue> = mutableMapOf()

    /**
     * Flag indicating that after [visit] was called, the frame should be persisted.
     */
    private var persistFrames: Boolean = false

    /**
     * This information is the completed and persisted, information that will
     * be persisted to JSON. Each element of the list itself is unmutable.
     */
    private val callTrace: MutableList<Statement> = mutableListOf();

    /**
     * The actual stack of frames. This is a mutable stack that will be updated
     * on every command. The [DebugAdapterPushAction] add to the top of the stack, each
     * [DebugAdapterPopAction] removes an elements from the frames.
     *
     * Every instruction with a range or [DebugAdapterStep] moves the range of the stack
     * frame that is on top.
     */
    private var frames = persistentStackOf<StackFrame>()

    /**
     * Number of indentations that preceed the consoleOutput -- this will be equivalent to the number
     * of encountered snippets of type [vc.data.SnippetCmd.CvlrSnippetCmd.ScopeStart] minus the one of type
     * [vc.data.SnippetCmd.CvlrSnippetCmd.ScopeEnd]
     */
    var consoleIndentation: Int = 0

    init {
        frames = frames.push(StackFrame(StackEntry.Rule(rule), TACCmd.Simple.NopCmd, rule.range.nonEmpty()))
        model.tacAssignments.filterKeys { ContractInfo.fromTACSymbol(it) != null }
            .mapKeys { it.key to ContractInfo.fromTACSymbol(it.key)!! }
            .forEachEntry { updateVariables(DisplayPath.Root(it.key.second.name), it.key.first, VariableType.CONTRACTS) }
    }

    /**
     * This function translates the variables to the representation in the VSCode extension variable view.
     * The groupBy defines the header of the container that will be visible in VSCode extension variable's view (see [VariableHeader])
     */
    private fun Map<VariableIdentifier, DebuggerValue>.toVariableMapping(frames: List<StackFrame>): List<VariableContainer> = this.toList()
        .groupBy {
            it.first.type.header
        }.map {
            VariableContainer(it.key.displayName, it.value.buildVariableTree(if (it.key == VariableHeader.LOCALS) {
                frames.joinToString { it.el.name }.hashCode().toString()
            } else {
                VariableHeader.GLOBAL.name
            }))
        }


    private fun InstantiatedDisplayPath.toAccessor(): String {
        return when (this) {
            is InstantiatedDisplayPath.Root -> this.name
            is InstantiatedDisplayPath.FieldAccess -> this.field
            is InstantiatedDisplayPath.ArrayAccess -> "[${this.index}]"
            is InstantiatedDisplayPath.MapAccess -> "[k = ${this.key}]"
            is InstantiatedDisplayPath.GhostAccess -> this.toFormattedString(formatter).prettyPrint()
        }
    }

    /**
     * [name] is the string that will be displayed in the variable tree in the VSCode extension. If you have an accessor contract.field1.field2, this would be field1 or field2.
     * [value] is the value associated to the current display path in the
     * [variableIdentifier] is used to internally reference to the variable for data breakpoints
     * [children] is a map of children this node can have, this allows a hierarchical structure in the variables view.
     */
    inner class MutableVariableNode(val name: String, var value: DebuggerValue? = null, val children: MutableMap<String, MutableVariableNode> = mutableMapOf(), val variableIdentifier: String) {
        fun toImmutable(): VariableNode {
            return VariableNode(name = name, value = value?.toString().orEmpty(), children = children.values.map { it.toImmutable() }, variableIdentifier = variableIdentifier)
        }
    }

    /**
     * The [stackIdentifier] is an internally generated string representing the current call stack. This is necessary for local variables.
     *
     * Example:
     *
     * rule foo{
     * 1:   bar()
     * 2:   bar()
     * }
     *
     * bar(){
     * 3:   uint x;
     * }
     *
     * The local variable x@3 exists once per call site, i.e. there is a x@3 when called @1, and there is x@3 when called @2. By prefixing the variable
     * with the call stack, we can uniquely identify the two different variables. (An alternative approach is likely to use the TAC variable identifier instead.)
     *
     * (If it's a global variable, it is prefixed by [VariableHeader.GLOBAL.name])
     */
    private fun List<Pair<VariableIdentifier, DebuggerValue>>.buildVariableTree(variablePrefix: String): List<VariableNode> {
        fun traverse(lastNode: MutableVariableNode, curr: InstantiatedDisplayPath, value: DebuggerValue): MutableVariableNode {
            val withoutNext = curr.toAccessor()
            val newNode = lastNode.children.getOrPut(withoutNext) {
                MutableVariableNode(withoutNext, variableIdentifier = lastNode.variableIdentifier + withoutNext)
            }
            return if (curr.next != null) {
                traverse(newNode, curr.next!!, value)
            } else {
                check(newNode.value == null || value == newNode.value) { "Expecting either newNode.value (${newNode.value}) to be null be or to be equal to value ${value}." }
                newNode.value = value
                newNode
            }
        }

        val rootNode = MutableVariableNode(name = "ROOT", value = null, variableIdentifier = variablePrefix)
        this.forEach {
            check(it.first.type.header == VariableHeader.LOCALS || variablePrefix == VariableHeader.GLOBAL.name) { "Found a none-local variable whose stack identifier is not GLOBAL" }
            traverse(rootNode, it.first.displayPath, it.second)
        }
        return rootNode.children.values.map { it.toImmutable() }
    }


    /**
     * An individual frame of the stack. When moving from one instruction to the next,
     * the stack entry remains the same while the [range] is updated.
     *
     * If the [el] StackEntry has a range associated, it should hold that
     * range.isWithin(el.scopeRange), i.e. the range only moves within the scope.
     *
     * Note: This class is mutable on purpose.
     */
    class StackFrame(val el: StackEntry, var cmd: TACCmd, var range: Range.Range?) {
        val variables: MutableMap<VariableIdentifier, DebuggerValue> = mutableMapOf()

        // Output that is printed to the debug console in VSCode while stepping through the code.
        var consoleOutput: String = ""
        fun stepTo(cmd: TACCmd, range: Range.Range?) {
            this.cmd = cmd
            this.range = range
        }
    }

    sealed class DebuggerValue {
        abstract override fun toString(): String

        class TACValue private constructor(val tacValue: vc.data.state.TACValue) : DebuggerValue() {
            init {
                require(tacValue != vc.data.state.TACValue.Uninitialized) {
                    "Expecting a concrete TACValue, got Uninitialized"
                }
            }

            fun asBigInteger(): BigInteger =
                requireNotNull(tacValue.asBigIntOrNull()) {
                    "TACValue cannot be converted to BigInteger"
                }

            override fun toString(): String = tacValue.toString()

            companion object {
                internal fun create(tacValue: vc.data.state.TACValue) = TACValue(tacValue)
            }
        }

        class StringValue private constructor(val reason: String) : DebuggerValue() {
            override fun toString(): String = reason

            companion object {
                internal fun create(reason: String) = StringValue(reason)
            }
        }

        companion object {
            operator fun invoke(tacValue: vc.data.state.TACValue): DebuggerValue =
                if (tacValue == vc.data.state.TACValue.Uninitialized) {
                    StringValue.create("No model value given for TAC symbol")
                } else {
                    TACValue.create(tacValue)
                }

            operator fun invoke(reason: String): DebuggerValue =
                StringValue.create(reason)
        }
    }

    /**
     * Extracts the [DebugAdapterAction] from a command. Either the command itself
     * implements the interface, or the annotation implements the interface.
     */
    private fun tryGetDebugAction(cmd: TACCmd.Simple): DebugAdapterAction? {
        return cmd as? DebugAdapterAction
            ?: cmd.maybeAnnotation(SNIPPET) as? DebugAdapterAction
            ?: cmd.maybeAnnotation(INTERNAL_FUNC_EXIT) as? DebugAdapterAction
            ?: cmd.maybeAnnotation(INTERNAL_FUNC_START) as? DebugAdapterAction
            ?: cmd.maybeAnnotation(START_EXTERNAL_SUMMARY) as? DebugAdapterAction
            ?: cmd.maybeAnnotation(END_EXTERNAL_SUMMARY) as? DebugAdapterAction
            ?: cmd.maybeAnnotation(START_INTERNAL_SUMMARY) as? DebugAdapterAction
            ?: cmd.maybeAnnotation(END_INTERNAL_SUMMARY) as? DebugAdapterAction
            ?: cmd.maybeAnnotation(Inliner.CallStack.STACK_PUSH) as? DebugAdapterAction
            ?: cmd.maybeAnnotation(Inliner.CallStack.STACK_POP) as? DebugAdapterAction
    }

    /**
     * Extracts the [DebugAdapterAction] from a command. Either the command itself
     * implements the interface, or the annotation implements the interface.
     */
    private fun tryGetRange(cmd: TACCmd.Simple): Range.Range? {
        return (cmd.maybeAnnotation(SNIPPET)?.let {
            when (it) {
                is SnippetCmd.EVMSnippetCmd.StorageSnippet.DirectStorageLoad -> it.range
                is SnippetCmd.EVMSnippetCmd.SourceFinderSnippet.LocalAssignmentSnippet -> it.range
                is SnippetCmd.EVMSnippetCmd.StorageSnippet.LoadSnippet -> it.range
                is SnippetCmd.EVMSnippetCmd.StorageSnippet.StoreSnippet -> it.range
                is SnippetCmd.CVLSnippetCmd.GhostAccess -> it.range
                is SnippetCmd.ExplicitDebugStep -> it.range
                is SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet -> it.branchSource.range
                is SnippetCmd.EVMSnippetCmd.HaltSnippet -> it.range
                is SnippetCmd.MoveSnippetCmd -> it.range

                is SnippetCmd.CVLSnippetCmd.AssertCast,
                is SnippetCmd.CVLSnippetCmd.BranchStart,
                is SnippetCmd.CVLSnippetCmd.CVLArg,
                is SnippetCmd.CVLSnippetCmd.CVLFunctionEnd,
                is SnippetCmd.CVLSnippetCmd.CVLFunctionStart,
                is SnippetCmd.CVLSnippetCmd.CVLRet,
                is SnippetCmd.CVLSnippetCmd.DispatcherSummaryDefault,
                is SnippetCmd.CVLSnippetCmd.DivZero,
                is SnippetCmd.CVLSnippetCmd.EVMFunctionInvCVLValueTypeArg,
                SnippetCmd.CVLSnippetCmd.End,
                is SnippetCmd.CVLSnippetCmd.GhostHavocSnippet,
                is SnippetCmd.CVLSnippetCmd.GhostWitnessComparison,
                SnippetCmd.CVLSnippetCmd.HavocAllGhostsSnippet,
                is SnippetCmd.CVLSnippetCmd.IfStart,
                is SnippetCmd.CVLSnippetCmd.InlinedHook,
                is SnippetCmd.CVLSnippetCmd.ScalarGhostComparison,
                is SnippetCmd.CVLSnippetCmd.ScalarStorageComparison,
                is SnippetCmd.CVLSnippetCmd.Start,
                is SnippetCmd.CVLSnippetCmd.StorageDisplay,
                is SnippetCmd.CVLSnippetCmd.StorageWitnessComparison,
                is SnippetCmd.CVLSnippetCmd.ViewReentrancyAssert,
                is SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet,
                is SnippetCmd.EVMSnippetCmd.ContractSourceSnippet.AssignmentSnippet,
                SnippetCmd.EVMSnippetCmd.CopyLoopSnippet.CopyLoop,
                is SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite,
                SnippetCmd.EVMSnippetCmd.HavocBalanceSnippet,
                is SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithLocSym,
                is SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithPath,
                is SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet,
                is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageBackupPoint,
                is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageHavocContract,
                is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageResetContract,
                is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageRestoreSnapshot,
                is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageRevert,
                is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot,
                is SnippetCmd.EVMSnippetCmd.StorageSnippet.DirectStorageHavoc,
                is SnippetCmd.EVMSnippetCmd.TransferSnippet,
                is SnippetCmd.LoopSnippet.AssertUnwindCond,
                is SnippetCmd.LoopSnippet.EndIter,
                is SnippetCmd.LoopSnippet.EndLoopSnippet,
                is SnippetCmd.LoopSnippet.StartIter,
                is SnippetCmd.LoopSnippet.StartLoopSnippet,
                SnippetCmd.SnippetCreationDisabled,
                is SnippetCmd.CvlrSnippetCmd,
                is SnippetCmd.SolanaSnippetCmd -> null
            }
        }
            ?: cmd.metaSrcInfo?.getSourceDetails()?.range
            ?: if (Config.ActiveEcosystem.get() == Ecosystem.EVM) {
                // only in the case of EVM do we want to take the actual range.
                // For Solana, the CVL_RANGE might not be precise.
                cmd.meta[CVL_RANGE]
            } else {
                null
            })?.nonEmpty()
    }

    fun visit(ltacCmd: LTACCmd) {
        val cmd = ltacCmd.cmd

        val debugActionCmd = tryGetDebugAction(cmd)

        val range = tryGetRange(cmd)

        persistFrames = false
        if (debugActionCmd is DebugAdapterPopAction) {
            check(frames.size > 0) { "The call stack can never be empty - there have been too many pop operations" }
            frames = frames.pop()
            persistFrames = debugActionCmd.persist
        } else if (debugActionCmd is DebugAdapterPushAction) {
            checkWarn(debugActionCmd.stackElement.scopeRange != null) { "Got an empty range for the stack element ${debugActionCmd.stackElement}" }
            val newRange = debugActionCmd.stackElement.scopeRange ?: rule.range.nonEmpty()

            frames = frames.push(StackFrame(debugActionCmd.stackElement, cmd, newRange))
            persistFrames = debugActionCmd.persist
        } else if (range != null) {
            val currTop = frames.top

            if (addDebugStepAtRange(currTop, range)) {
                currTop.stepTo(cmd, range)
                persistFrames = true
            }
        }

        handleSolanaPrintStmt(cmd)

        if (Config.CallTraceDebugAdapterProtocol.get() == DebugAdapterProtocolMode.VARIABLES) {
            updateLocalVariables(cmd)
        }
        if (persistFrames) {
            persist()
        }
    }


    private fun addDebugStepAtRange(currStackFrame: StackFrame, range: Range.Range): Boolean {
        if (currStackFrame.range == range) {
            /**
             * If the new range is the same as the current range on top, no need to add
             * another step for the same range.
             */
            return false
        }
        if (Config.ActiveEcosystem.get() == Ecosystem.EVM && range.slicedString()?.isFullContractSrcMap() == true) {

            /**
             * Do not add a step if the range just highlights the entire contract.
             * As it's not clear what line number this then refers to and halting the
             * debugger here doesn't help.
             */
            return false;
        }
        if (currStackFrame.el.scopeRange == null) {
            /**
             * If current scope element doesn't have any information about the range
             * all steps should be contained within, then assume by default the step will be added.
             */
            return true;
        }
        /**
         * Only in the case of EVM do we have the full source range of a scope, i.e. we know where
         * a function implementation starts and ends in terms of its line numbers.
         * In that case we ensure that the step is also within this range.
         * This ensures that the debugger always steps within the line numbers
         * of the function body and cannot jump to anywhere else.
         */
        return Config.ActiveEcosystem.get() != Ecosystem.EVM || range in currStackFrame.el.scopeRange
    }

    private fun persist() {
        val potentialNewEl = Statement(frames.top.cmd.toString(), frames.top.consoleOutput, frames.map { frame ->
            ImmutableStackFrame(stackEntry = frame.el, function = frame.el.name, range = frame.range, variableContainers = frame.variables.toVariableMapping(frames.toList()))
        }, globalVariableContainers = globalVariables.toVariableMapping(frames.toList()))

        //The consoleOutput has been persisted, reset it to the empty string.
        frames.top.consoleOutput = ""
        callTrace.push(potentialNewEl)
    }

    private fun updateVariables(displayPath: DisplayPath, symbol: TACSymbol, type: VariableType) {
        updateVariables(displayPath.instantiateTACSymbols(model), symbol, type)
    }

    private fun updateVariables(displayPath: InstantiatedDisplayPath, symbol: TACSymbol, type: VariableType) {
        checkWarn(model.tacAssignments[symbol] != null) { "No value found for symbol ${symbol}" }

        val storedValue = model.tacAssignments[symbol] ?: TACValue.Uninitialized
        updateVariables(displayPath, DebuggerValue(storedValue), type)
    }

    private fun updateVariables(displayPath: InstantiatedDisplayPath, storedValue: DebuggerValue, type: VariableType) {
        if (type.header != VariableHeader.LOCALS || ONLY_GLOBAL_VARIABLES) {
            globalVariables[VariableIdentifier(type, displayPath)] = storedValue
        } else {
            frames.top.variables[VariableIdentifier(type, displayPath)] = storedValue
        }
    }

    fun updateVariables(displayPath: InstantiatedDisplayPath, storedValue: TACValue, type: VariableType) {
        updateVariables(displayPath, DebuggerValue(storedValue), type)
    }

    /**
     * This function updates the local variables only (CVL and EVM locals (i.e. function parameters and function locals).
     *
     * Ghost state, storage state and balances are computed by the regular call trace ([report.globalstate.StorageState],
     * [report.globalstate.GhostsState], [report.globalstate.BalancesState]
     */
    private fun updateLocalVariables(cmd: TACCmd.Simple) {
        updateCVLLocalVariables(cmd)
        updateFunctionLocalVariables(cmd)
        updateHookVariables(cmd)
        updateSolanaLocalVariables(cmd)
    }


    /***
     * CVL specific code below
     */
    private fun updateHookVariables(cmd: TACCmd.Simple) {
        cmd.maybeAnnotation(SNIPPET).let { snippet ->
            if (snippet is SnippetCmd.CVLSnippetCmd.InlinedHook) {
                snippet.substitutions.forEachEntry {
                    val value = model.instantiate(it.value) ?: TACValue.Uninitialized
                    updateVariables(InstantiatedDisplayPath.Root(it.key.name), DebuggerValue(value), VariableType.LOCAL)
                }
            }
        }
    }

    private fun updateFunctionLocalVariables(cmd: TACCmd.Simple) {
        // handle function parameters
        cmd.maybeAnnotation(INTERNAL_FUNC_START)?.let { funcAnnotation ->
            funcAnnotation.args.zip(funcAnnotation.methodSignature.params).mapIndexed { index, pair ->
                when (val vmParam = pair.second) {
                    is VMParam.Named -> DisplayPath.Root(vmParam.id) to pair.first.s
                    is VMParam.Unnamed -> DisplayPath.Root("current function parameter $index") to pair.first.s
                }
            }.forEach { updateVariables(it.first, it.second, VariableType.LOCAL) }
        }

        cmd.maybeAnnotation(SNIPPET).let {
            if (it is SnippetCmd.EVMSnippetCmd.SourceFinderSnippet.LocalAssignmentSnippet) {
                updateVariables(DisplayPath.Root(it.lhs), it.value, VariableType.LOCAL)
            }
        }
    }

    private fun updateCVLLocalVariables(cmd: TACCmd.Simple) {
        if (cmd is TACCmd.Simple.AssigningCmd) {
            // maybe filter by  && CVLKeywords.find(lhs.name()) == null
            if ((cmd.lhs.meta[TACMeta.CVL_DEF_SITE] is CVLDefinitionSite.Rule || CVL_VAR in cmd.lhs.meta) && CVL_DISPLAY_NAME in cmd.lhs.meta && CVL_GHOST !in cmd.lhs.meta) {
                val displayPath = cmd.lhs.meta[CVL_STRUCT_PATH]?.let {
                    it.fields.fold<CVLType.PureCVLType.Struct.StructEntry, DisplayPath>(DisplayPath.Root(it.rootStructType.name))
                    { last, curr -> DisplayPath.FieldAccess(curr.fieldName, last) }
                }
                    ?: DisplayPath.Root(cmd.lhs.meta[CVL_DISPLAY_NAME]!!)
                updateVariables(displayPath, cmd.lhs, VariableType.CVL_LOCAL)
            }
        }

        //Update CVL parameters and declarations
        cmd.maybeAnnotation(CVLCompiler.Companion.TraceMeta.VariableDeclaration.META_KEY)?.let { decl ->
            val basePath = DisplayPath.Root(decl.type.sourceName)
            decl.fields?.mapKeys {
                it.key.fold(basePath as DisplayPath) { acc, next ->
                    DisplayPath.FieldAccess((next as? DataField.StructField)?.field ?: next.toString(), acc)
                }
            }?.forEachEntry {
                updateVariables(it.key, it.value, VariableType.CVL_LOCAL)
            }

            val identity = decl.v
            if (identity !is CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar) {
                return@let
            }
            updateVariables(basePath, identity.t, VariableType.CVL_LOCAL)
        }
    }


    /***
     * Solana specific code below
     */
    private fun updateSolanaLocalVariables(cmd: TACCmd.Simple) {
        cmd.maybeAnnotation(SNIPPET).let { snippet ->
            if (snippet is SnippetCmd.SolanaSnippetCmd.VariableBecomingLive) {
                val pieces = snippet.expressions.mapValues { (_, v) -> v.evaluate(model) }
                SolanaCallTraceValueFormatter(
                    snippet.variableName,
                    snippet.baseVariableType
                ).formatValue(pieces, false)
                    .map { (idp, debugValue) ->
                        updateVariables(idp, debugValue, VariableType.LOCAL)
                    }

                persistFrames = persistFrames || snippet.persist
            }
            if (snippet is SnippetCmd.SolanaSnippetCmd.DirectMemoryAccess) {
                val pieces = snippet.expression.evaluate(model)
                SolanaCallTraceValueFormatter(
                    snippet.variableName,
                    snippet.baseVariableType.asNonReferenceType()
                ).formatValue(mapOf(snippet.offsetIntoStruct to pieces), true)
                    .map { (idp, debugValue) ->
                        updateVariables(idp, debugValue, VariableType.LOCAL)
                    }

                persistFrames = persistFrames || snippet.persist
            }
        }
    }


    private fun handleSolanaPrintStmt(cmd: TACCmd.Simple) {
        cmd.maybeAnnotation(SNIPPET).let { snippet ->
            val sarif = when (snippet) {
                is SnippetCmd.CvlrSnippetCmd.CexPrintTag -> {
                    Sarif.fromPlainStringUnchecked(snippet.displayMessage)
                }

                is SnippetCmd.CvlrSnippetCmd.CexPrintValues -> {
                    snippet.toSarif(model)
                }

                is SnippetCmd.CvlrSnippetCmd.CexPrint128BitsValue -> {
                    snippet.tryToSarif(model)
                }

                is SnippetCmd.CvlrSnippetCmd.CexPrintU64AsFixedOrDecimal -> {
                    snippet.tryToSarif(model)
                }

                is SnippetCmd.CvlrSnippetCmd.ScopeStart -> {
                    Sarif.fromPlainStringUnchecked(snippet.scopeName)
                }

                is SnippetCmd.CvlrSnippetCmd.ScopeEnd -> {
                    // Decrease indentation before logging
                    consoleIndentation--
                    Sarif.fromPlainStringUnchecked(snippet.scopeName)
                }
                else -> null
            }
            if (sarif != null) {
                val output = "\t".repeat(consoleIndentation) + sarif.prettyPrint() + "\n";
                persistFrames = true
                frames.top.consoleOutput += output
            }

            if(snippet is SnippetCmd.CvlrSnippetCmd.ScopeStart){
                // Increase indentation after logging so it applies for the next logs.
                consoleIndentation++
            }
        }
    }


    fun writeToFile(): String? {
        if (!persistFrames) {
            // If the last statement we saw did not already persist the information,
            // before writing the call trace, explicitly persist the information.
            persist()
        }
        val manager = ArtifactManagerFactory.enabledOrNull() ?: return null
        val key = RuleOutputArtifactsKey(rule.ruleIdentifier)
        val artifacts = manager
            .getOrRegisterRuleOutputArtifacts(key)
            ?: error("broken invariant: can't fail for enabled artifact manager")

        val fileName = artifacts.dapCalltraceOutputFileName

        val path = manager
            .getRegisteredArtifactPathOrNull(fileName)
            ?: error("broken invariant: we made sure that the artifact has been registered")

        return try {
            val name = ArtifactFileUtils.getActualFile(path).name
            GZIPOutputStream(FileOutputStream(path)).bufferedWriter().use { writer ->
                writer.writeNDJSON(JSON_SCHEMA_VERSION)
                writer.writeNDJSON(
                    Rule(
                        rule.ruleIdentifier.toString(), rule.isSatisfyRule ||
                            rule.getAllSingleRules().all {
                                it.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck
                            }, rule.range
                    )
                )
                callTrace.reduce().forEach { writer.writeNDJSON(it) }
            }
            name
        } catch (e: IOException) {
            logger.error("Write of rule ${key.ruleIdentifier} failed: $e")
            null
        }
    }

    inline fun <reified T> BufferedWriter.writeNDJSON(serializable: T) {
        write(Json.encodeToString(serializable))
        newLine()
    }

    @TestOnly
    fun getCallTrace(): List<Statement> {
        return callTrace.reduce().toList()
    }
}


/**
 * Traverses the generated call trace / debugger step backwards and if the last
 * element is equal to the next element modulo the variables, we do not add the next
 * element to the list - it does not convey additional information.
 *
 * The variables are only ever added to the debugger steps, and so the set of variables
 * of last must be larger than the set of variables at next.
 */
fun MutableList<Statement>.reduce(): List<Statement> {
    return this.reversed().fold<Statement, List<Statement>>(listOf()) { acc, curr ->
        val defaultRes = acc + curr;
        if (acc.isEmpty()) {
            return@fold defaultRes
        }
        val lastFrames = acc.last().frames
        if (lastFrames.size != curr.frames.size || lastFrames.isEmpty()) {
            return@fold defaultRes
        }
        if (lastFrames.zip(curr.frames).all { it.first.equalsExceptVariables(it.second) }) {
            acc
        } else {
            defaultRes
        }
    }.reversed()
}

/**
 * Classes for serialization follow below
 */

@Serializable
data class VariableNode(val name: String, val value: String?, val children: List<VariableNode> = listOf(), val variableIdentifier: String)

@Serializable
data class VariableContainer(val header: String, val variables: List<VariableNode>)

@Serializable
data class Rule(val fullyQualifiedRuleIdentifier: String, val isSatisfyRule: Boolean, val range: Range)

@Serializable
data class ImmutableStackFrame(val stackEntry: StackEntry, val function: String, val range: Range.Range?, val variableContainers: List<VariableContainer> = listOf()) {
    override fun toString(): String {
        return function + "@" + range?.lineNumber
    }

    fun equalsExceptVariables(other: ImmutableStackFrame): Boolean {
        return other.stackEntry == stackEntry && other.function == function && other.range == range
    }
}

@Serializable
data class Statement(val statement: String, val consoleOutput: String, val frames: List<ImmutableStackFrame>, val globalVariableContainers: List<VariableContainer> = listOf())

@Serializable
data class Trace(val CertoraDAPVersion: String, val rule: Rule, val trace: List<Statement>)
