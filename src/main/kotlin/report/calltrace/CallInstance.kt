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

import analysis.icfg.SummaryApplicationReason
import analysis.icfg.SummaryStack
import analysis.ip.InternalFuncStartAnnotation
import bridge.EVMExternalMethodInfo
import com.certora.collect.*
import compiler.SourceSegment
import datastructures.stdcollections.*
import kotlinx.serialization.json.put
import report.*
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.CallTraceValue
import report.calltrace.formatter.FormatterType
import report.calltrace.sarif.*
import report.calltrace.sarif.SarifBuilder.Companion.mkSarif
import scene.ISceneIdentifiers
import spec.cvlast.*
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.NBId
import utils.*
import vc.data.SnippetCmd.CVLSnippetCmd
import vc.data.state.TACValue
import vc.data.TACMeta.CVL_LABEL_START
import vc.data.TACMeta.CVL_LABEL_END
import java.math.BigInteger

/**
 * A unit of the [CallTrace]. Represents a step in the flow of the CounterExample TACProgram chosen by the SMT.
 * [name] is the string representation of this [CallInstance], displayed in the CallTrace.
 * [status] is a status field mainly used to pass information to the frontend, to attach the correct labels.
 */
sealed class CallInstance : TreeViewReportable {

    /** this property will be deprecated soon, prefer [sarif] for newer sub-classes */
    open val name: String get() = this.sarif.flatten()

    open val range: Range.Range? get() = null
    open var status: CallEndStatus = CallEndStatus.NONE
    val children: MutableList<CallInstance> = mutableListOf()
    var parent: ScopeInstance? = null
        private set

    open val sarif: Sarif get() = Sarif.fromPlainStringUnchecked(name)

    /** Return the subtree below (and including) this instance in a sequence. */
    fun allChildren(): Sequence<CallInstance> =
        sequenceOf(this) + children.flatMap { it.allChildren() }

    override fun toString(): String = name

    fun prettyPrint() = sarif.prettyPrint()

    /** Get all [CallInstance]s of type [T] that occur in the subtree represented by this [CallInstance].
     * If [minDepthOnly] is set, we don't recurse into subtrees of the [T] instances to find further instances.
     * [pred] can be given for further filtering.
     * */
    inline fun <reified T : CallInstance> filterCallInstancesOf(
        minDepthOnly: Boolean = false,
        pred: (T) -> Boolean = { true },
    ): List<T> {
        val res = mutableListOf<T>()
        val workList = arrayDequeOf(this)

        while (workList.isNotEmpty()) {
            val currCallInstance = workList.removeFirst()

            if (currCallInstance is T && pred(currCallInstance)) {
                res.add(currCallInstance)
                if (minDepthOnly) { continue /* don't look at children */ }
            }
            workList.addAll(currCallInstance.children)
        }

        return res
    }

    /** attempts to find an immediate child of this instance (i.e. does not recurse deeper) */
    fun findChild(predicate: (CallInstance) -> Boolean) = children.find(predicate)

    inline fun <reified T : CallInstance> ancestorOfType(allowedToSkip: (CallInstance) -> Boolean = { true }): T? {
        var curr: CallInstance? = this

        while (curr != null && curr !is T) {
            if (allowedToSkip(curr)) {
                curr = curr.parent
            } else {
                // encountered a CallInstance we cannot just 'jump over', break
                return null
            }
        }

        @Suppress("USELESS_CAST") // this cast is actually necessary here
        return curr as T?
    }

    fun printCallHierarchy(newLine: String = "\n") = buildString {
        append(this@CallInstance.toString())

        var curr: CallInstance? = this@CallInstance.parent

        while (curr != null) {
            append(newLine)
            append(curr.toString())
            curr = curr.parent
        }
    }

    override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
        /** message */
        put(CallTraceAttribute.MESSAGE(), this@CallInstance.sarif)

        /** status */
        put(CallTraceAttribute.STATUS(), status.toJSONRepresentation())

        /** childrenList */
        putJsonArray(CallTraceAttribute.CHILDREN_LIST(), this@CallInstance.children)

        /** jump_to_definition */
        put(CallTraceAttribute.JUMP_TO_DEFINITION(), range)
    }

    /**
     * [CallInstance] that behaves like a stack-frame in the sense that descendant [CallInstance]s could be added
     * to its subtree across successive iterations along the [CallTrace] construction.
     * */
    sealed class ScopeInstance: CallInstance() {
        fun addChild(child: CallInstance) {
            if (child.parent != null) {
                callInstanceTreeStructureException { "CallInstance $child was added to $this but is already child of ${child.parent}." }
            }

            if (child is InvokingInstance.CVLRootInstance) {
                callInstanceTreeStructureException { "$child can't be CVLRootInstance." }
            }

            child.parent = this
            children.add(child)
        }
    }

    /** call instances to report error messages in the call tree itself, for better user visibility */
    sealed class ErrorInstance : ScopeInstance() {
        class EarlyExit : ErrorInstance() {
            override val sarif = Sarif.fromPlainStringUnchecked(
                "Call trace build stopped here due to an error. Check the Notifications for details."
            )
        }

        class WrongStackState : ErrorInstance() {
            override val sarif = Sarif.fromPlainStringUnchecked(
                "Call trace build failed here, but recovered. Call structure from this point may be incorrect."
            )
        }
    }

    /**
     * [CallInstance] which serves as a function invocation (in the sense that it has params and return values).
     * [params] maps location of the param to the param itself.
     * [paramValues] are the values correspond to [params].
     * [returnValues] maps location of the returned value to the returned value itself.
     * [returnTypes] are the [CVLType.PureCVLType]s correspond to [returnValues].
     * values were inserted in order, from the first param to the k param (0 <= k<= params.size).
     */
    sealed class InvokingInstance<@Treapable T> : ScopeInstance() {
        open val params: List<TypedParam<T>> // xx part of the duplicated parameter naming complex ..
            get() = emptyList()
        open val returnTypes: List<T>
            get() = emptyList()
        val paramValues: MutableMap<Int, CallTraceValue> = mutableMapOf()
        val returnValues: MutableMap<Int, CallTraceValue> = mutableMapOf()

        /** the name of the invoked function */
        abstract val callName: String

        abstract val formatter: CallTraceValueFormatter

        /**
         * don't use this.
         * this is here for backwards-compatibility: it's used in some old regression tests
         * where the receiver is the base class.
         */
        override val name: String get() = callName

        override val sarif get() = sarifFromInvokingInstance(this)

        val paramNames get() = params.monadicMap { it.name() }
        val paramTypes get() = params.map { it.type }

        override fun toString(): String {
            return callName + paramsValuesWithNames().joinToString(separator = ", ", prefix = "(", postfix = ")")
        }

        open fun toStringWithoutParamValues(): String {
            return callName + params.joinToString(separator = ", ", prefix = "(", postfix = ")")
        }

        /**
         * Non-Sarif representation of parameter values.
         * Formats the values of [paramValues] to "$name=$value" (if a name is available) or "$value" (otherwise).
         * Used in [toString], thus mostly for testing and regression logging.
         */
        private fun paramsValuesWithNames(): List<String> {
            /**
             * [paramValues] and [params] can't be trusted to match.
             * on size mismatch (perhaps due to some analysis failure), give up on trying to find a name
             */
            val names = params.map { it.name() }.takeIf { params.size == paramValues.size }

            return paramValues
                .toSortedMap()
                .entries
                .map { (paramIdx, paramVal) ->
                    val name = names?.get(paramIdx)
                    val paramValString = paramVal.toSarif(formatter, name ?: "parameter").flatten()
                    if (name != null) {
                        "$name=$paramValString"
                    } else if (paramVal.paramName != null) {
                        // XXX should check where we create those prefixes, and whether we can't plumb them the proper way instead (like name above)
                        "${paramVal.paramName}=$paramValString"
                    } else {
                        paramValString
                    }
                }
        }

        /** Non-Sarif representation of return values. Used for regression logging. */
        fun returnValuesToString(): String {
            val sortedReturnValues = this@InvokingInstance.returnValues.toSortedMap()
                .mapValues { (_, v) ->
                    v.checkNoPrefix()
                    // we take only the first list entry here (the default, among the alternative representations)
                    v.toSarif(formatter, "return value").flatten()
                }
                .entries

            return when (sortedReturnValues.size) {
                0 -> ""
                1 -> sortedReturnValues.first().value
                else -> sortedReturnValues.joinToString(separator = ", ", prefix = "(", postfix = ")") { (_, retVal) ->
                    retVal
                }
            }
        }

        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
            super.treeViewRepBuilder coalesceInto this
        }

        // XXX is this used?
        sealed class PureCVLInvokingInstance: InvokingInstance<CVLType.PureCVLType>() {
            abstract val callIndex: Int
            override val params: MutableList<CVLParam> = mutableListOf()
            override val returnTypes: MutableList<CVLType.PureCVLType> = mutableListOf()
        }
        sealed class VMInvokingInstance: InvokingInstance<VMTypeDescriptor>()

        // solana
        class SolanaFunctionInstance(override val name: String, val callIndex: Int) : ScopeInstance()

        // wasm
        class WasmFunctionInstance(override val name: String, val funcName: String) : ScopeInstance()

        /**
         * The root of the rule (and the [CallTrace]).
         */
        class CVLRootInstance(
            override val callName: String,
            override val formatter: CallTraceValueFormatter,
        ) : PureCVLInvokingInstance() {
            override val callIndex
                get() = NBId.ROOT_CALL_ID
        }

        class CVLFunctionInstance(
            override val callIndex: Int,
            override val callName: String,
            override val range: Range.Range?,
            override val formatter: CallTraceValueFormatter,
        ): PureCVLInvokingInstance()

        /**
         * Solidity function's invocation from the Solidity code.
         */
        sealed class SolidityInvokeInstance : VMInvokingInstance() {
            abstract val callIndex: Int

            /**
             * External call.
             * [callIndex] is the callee index generated by the Inliner
             */
            class External(
                override val callName: String,
                override val callIndex: Int,
                val isConstructor: Boolean,
                info: EVMExternalMethodInfo?,
                override val formatter: CallTraceValueFormatter,
            ) : SolidityInvokeInstance() {
                override val params: List<VMParam> = info?.argTypes?.map { VMParam.Unnamed(it, Range.Empty()) }.orEmpty()
                override val returnTypes: List<VMTypeDescriptor> = info?.resultTypes.orEmpty()
                override val range = info?.sourceSegment?.range
            }

            /**
             * Internal call.
             */
            class Internal(
                override val callName: String,
                val id: Int,
                val callIndexOfCaller: Int, // currently used for processing of calldata and returndata.
                annotation: InternalFuncStartAnnotation,
                override val formatter: CallTraceValueFormatter,
            ) : SolidityInvokeInstance() {
                override val params: List<VMParam> = annotation.methodSignature.params
                override val returnTypes: List<VMTypeDescriptor> = annotation.methodSignature.resType
                override val range = annotation.callSiteSrc?.getSourceDetails()?.range

                override val callIndex: Int
                    get() = callIndexOfCaller

                override fun toString(): String = "(internal) ${super.toString()}"

                override fun toStringWithoutParamValues(): String = "(internal) ${super.toStringWithoutParamValues()}"
            }
        }

        /**
         * Summarize invocation.
         */
        class SummaryInstance(
            val blockId: NBId,
            val pos: Int,
            annotation: SummaryStack.SummaryStart,
            scene: ISceneIdentifiers,
            override val formatter: CallTraceValueFormatter,
        ) : VMInvokingInstance() {
            override val callName: String = run {
                val summary = annotation.summary
                val calleeString = annotation.toUIString(scene)

                /** CERT-2214 avoids printing the keywords ANY and UNRESOLVED in the CallTrace */
                val summaryString = when (summary) {
                    is SpecCallSummary.ExpressibleInCVL -> "${summary.summaryName} summary @ ${summary.range}"
                    SpecCallSummary.OptimisticFallback -> "${summary.summaryName} summary"
                }

                "$calleeString: $summaryString"
            }

            override val range = when (val summary = annotation.summary) {
                is SpecCallSummary.ExpressibleInCVL -> summary.range as? Range.Range
                SpecCallSummary.OptimisticFallback -> null
            }

            override val returnTypes: List<VMTypeDescriptor> = when (annotation) {
                is SummaryStack.SummaryStart.Internal -> annotation.methodSignature.resType
                is SummaryStack.SummaryStart.External -> emptyList()
            }

            override var status: CallEndStatus = when {
                annotation.summary is SpecCallSummary.HavocSummary
                    && annotation.callResolutionTableInfo.applicationReason == SummaryApplicationReason.Prover -> CallEndStatus.DEFAULT_HAVOC
                annotation.summary is SpecCallSummary.Dispatcher -> CallEndStatus.DISPATCHER
                annotation.summary is SpecCallSummary.OptimisticFallback -> CallEndStatus.DISPATCHER
                else -> CallEndStatus.SUMMARIZED
            }
        }

        class RevertCauseHeaderInstance : ScopeInstance() {
            override val name: String = "Why did this call revert?"

            override var status: CallEndStatus
                get() = CallEndStatus.REVERT_CAUSE
                set(_) = throw UnsupportedOperationException("Cannot change the status of $this")
        }

        class RevertCauseSrcDetailsInstance(src: SourceSegment) : ScopeInstance() {
            override val name: String = "See ${src.condense()}"

            override val range = src.range

            override var status: CallEndStatus
                get() = CallEndStatus.NONE
                set(_) = throw UnsupportedOperationException("Cannot change the status of $this")
        }

        class RevertCauseDumpInstance(sliceExprPrintRep: String) : ScopeInstance() {
            @Suppress("deprecation")
            override val name: String = org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4(sliceExprPrintRep)

            override var status: CallEndStatus
                get() = CallEndStatus.REVERT_DUMP
                set(_) = throw UnsupportedOperationException("Cannot change the status of $this")
        }
    }

    /** signifies that the event marked by [label] has been reached. currently generated from [CVL_LABEL_START] */
    class LabelInstance(val label: CVLReportLabel, override val id: Int? = null) : ScopeInstance(), CVLSnippetCmd.EventID {
        /**
         * I think it would make more sense to give "plain" messages their own dedicated instance,
         * unrelated to [CVLReportLabel].
         * currently this would cause issues since some messages are part of
         * a [CVL_LABEL_START]/[CVL_LABEL_END] and some aren't.
         * doing this would also allow us make [id] non-null, making [CVLSnippetCmd.EventID] more robust.
         */
        constructor(message: String, labelId: Int? = null): this(CVLReportLabel.Message(message, Range.Empty()), labelId)

        override val name: String get() = label.toString()
        override val range: Range.Range? get() = label.rangeOrNull()
    }

    /** a CVL `if` block was entered */
    class CVLIf(val predicate: String, override val id: Int, override val range: Range.Range?) : ScopeInstance(), CVLSnippetCmd.EventID {
        override val name: String get() = "if (${predicate})"
    }

    class CVLBranch(
        val kind: CVLSnippetCmd.BranchStart.Kind,
        override val id: Int,
        override val range: Range.Range?
    ) : ScopeInstance(), CVLSnippetCmd.EventID {
        override val sarif: Sarif get() = when (this.kind) {
            CVLSnippetCmd.BranchStart.Kind.THEN -> Sarif.fromPlainStringUnchecked("THEN branch")
            CVLSnippetCmd.BranchStart.Kind.ELSE -> Sarif.fromPlainStringUnchecked("ELSE branch")
        }
    }

    /**
     * AssertCast check for a CVL expression.
     */
    class CastCheckInstance(override val name: String, override val range: Range.Range?): ScopeInstance() {
        override var status: CallEndStatus = CallEndStatus.ASSERT_CAST
    }

    class StorageCounterExampleInstance(override val sarif: Sarif) : ScopeInstance()

    /**
     * A marker for havoc-all-ghosts event
     */
    class AllGhostsHavocInstance : CallInstance() {
        override val name: String
            get() = "All non-persistent ghosts were havoc'd"
    }

    /**
     * Storage access in the Solidity.
     */
    sealed class StorageInstance: CallInstance() {
        class Store(override val sarif: Sarif, override val range: Range.Range?): StorageInstance()
        class Load(override val sarif: Sarif, override val range: Range.Range?): StorageInstance()
        class Havoc(override val sarif: Sarif, override val range: Range.Range?): StorageInstance()
    }

    /**
     * Transfer of ETH.
     */
    class TransferInstance(override val sarif: Sarif, override var status: CallEndStatus): ScopeInstance()
    class BalanceInstance(override val sarif: Sarif): CallInstance()

    /**
     * Division by zero check.
     */
    class DivZeroInstance(override val name: String, override val range: Range.Range?): ScopeInstance()

    class ViewReentrancyInstance(override val name: String, override val range: Range.Range?): ScopeInstance()

    /**
     * Loop information.
     */
    sealed class LoopInstance: ScopeInstance() {
        /**
         * Start of a loop.
         */
        class Start(override val name: String, val id: Int): LoopInstance()
        /**
         * Iteration info.
         */
        class Iteration(override val name: String, val iteration: Int): LoopInstance()

        /**
         * Assertion that we reached the upper bound on the number of iterations but still
         * could not completely unroll the loop.
         */
        class AssertUnwindCond(override val name: String): LoopInstance()

        class CopyLoop: LoopInstance() {
            override val name: String
                get() = "Copy-Loop"
        }
    }

    /**
     * Branch instances
     */
    sealed class BranchInstance: ScopeInstance() {
        /**
         * Start of a branch.
         */
        class Start(val branchSource: SourceSegment, val id: Int): BranchInstance() {
            override val name get() = "Evaluate branch condition"
            override val range get() = branchSource.range
        }
    }

    sealed class HaltInstance: CallInstance() {
        class Return(override val range: Range.Range?): HaltInstance() {
            override val name: String
                get() = "Return"
        }
        class Revert(override val range: Range.Range?): HaltInstance() {
            override val name: String
                get() = "Revert"
        }
    }

    class LocalAssignmentInstance(override val range: Range.Range?, override val sarif: Sarif): CallInstance()

    class NonSpecificDeclInstance(override val range: Range.Range?, val lhs: String): CallInstance() {
        override val name: String
            get() = "Declare $lhs"
    }

    class NonSpecificDefInstance(override val range: Range.Range?, val lhs: String): CallInstance() {
        override val name: String
            get() = "Define $lhs"
    }
    /**
     * Value in Storage.
     */
    class StorageStateValueInstance(
        override var status: CallEndStatus,
        override val range: Range.Range?,
        val value: StorageValue,
        val changedSincePrev: Boolean,
        val changedSinceStart: Boolean,
        val addr: BigInteger,
        formatter: CallTraceValueFormatter
    ): CallInstance() {
        override val sarif = value.format(changedSincePrev, formatter, "storage value")

        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
            super.treeViewRepBuilder coalesceInto this
            put(CallTraceAttribute.CHANGED_SINCE_PREV(), changedSincePrev)
            put(CallTraceAttribute.CHANGED_SINCE_START(), changedSinceStart)
        }
    }

    /** observed value of a ghost at specific indices of the ghost map/function */
    class GhostValueInstance(
        override var status: CallEndStatus,
        override val range: Range.Range?,
        val value: StorageValue,
        changedSincePrev: Boolean,
        formatter: CallTraceValueFormatter
    ) : CallInstance() {
        override val sarif = value.format(changedSincePrev, formatter, "ghost value")
    }

    /** balance value of a given contract */
    class BalanceValueInstance(
        val contract: String,
        val value: CallTraceValue,
        formatter: CallTraceValueFormatter
    ) : CallInstance() {
        override val sarif = mkSarif {
            append("$contract.balance: ")
            append(value.toSarif(formatter, "balance"))
        }
    }

    data class StorageValue(val storagePath: Sarif, val observedValue: CallTraceValue) {
        fun format(changedSincePrev: Boolean, formatter: CallTraceValueFormatter, tooltip: String) =
            mkSarif {
                append(storagePath)
                append(": ")
                append(
                    observedValue.toSarif(formatter, tooltip)
                )
                runIf(changedSincePrev) { append(" (changed)") }
            }
    }

    /**
     * Generic entry.
     *  XXX make clearer comment -- entry as in storage entry? (why is there "title" in the name?)
     */
    class StorageTitleInstance(override val name: String, val index: Int? = null): ScopeInstance() {
        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
            super.treeViewRepBuilder coalesceInto this
            put(CallTraceAttribute.STORAGE_ID(), index)
        }
    }

    // Information detected from the Solidity source code
    sealed class ContractSourceInstance: CallInstance() {
        class Assignment(override val name: String, override val range: Range.Range?): ContractSourceInstance()
    }

    /**
     * [CVLExp] as part of a [CVLCmd].
     */
    class CVLExpInstance(override val sarif: Sarif, override val range: Range.Range?, val value: TACValue?) : ScopeInstance() {
        companion object {
            /** for instances that have already-formatted values */
            fun withStringExpAndValue(
                exp: String,
                value: TACValue?,
                formatterType: FormatterType.Value<*>,
                range: Range.Range?,
                formatter: CallTraceValueFormatter
            ): CVLExpInstance {
                val sarif = mkSarif {
                    append(exp)

                    /** If [valueOfExp] is null, don't append the "evaluates to" part.
                     * NB there are cases when `valueOfExp` is justifiably null, e.g. when `expLiteral` is a constant literal. */
                    if (value != null) {
                        append(Sarif.EVALUATES_TO)
                        append(formatter.valueToSarif(value, formatterType, "value of expression"))
                    }
                }
                return CVLExpInstance(sarif, range, value)
            }
        }
    }

    // solana
    class SolanaExternalCall(override val sarif: Sarif, override val range: Range.Range?) : CallInstance()
    class SolanaUserAssert(override val name: String, override val range: Range.Range?) : CallInstance()
    class SolanaCexPrintTag(override val name: String, override val range: Range.Range?): CallInstance()
    class SolanaCexPrintValues(override val sarif: Sarif, override  val range: Range.Range?): CallInstance()

    // wasm
    class WasmUserAssume(override val name: String, override val range: Range.Range?) : CallInstance()
    class WasmUserAssert(override val name: String, override val range: Range.Range?) : CallInstance()
}

fun callInstanceTreeStructureException(errorMsg: () -> String): Nothing {
    val structureErrorPrefix = "The call trace tree has an invalid structure:"
    throw CallTraceException("$structureErrorPrefix ${errorMsg()}")
}

internal fun TypedParam<*>.name(): String? =
    when (this) {
        is CVLParam -> this.id
        is VMParam.Named -> this.name
        is VMParam.Unnamed -> null
    }

@Suppress("ForbiddenMethodCall")
private val namePrefixRE = Regex("^([a-zA-Z0-9_]+=)(.*)$")

/*
 * currently some named params already have their name concatenated to the
 * param value string. this repeatedly removes any such prefixes.
 * for example, "foo=5" would become "5".
 *
 * assumes that valid values do not start with something that looks like
 * a name prefix. this assumption may be incorrect.
 * we should eventually stop doing this early name concatenation,
 * so that this function becomes redundant.
 */
internal fun String.stripNamePrefixes(): String {
    var str = this

    while (true) {
        val afterPrefix = namePrefixRE.matchEntire(str)?.groups?.get(2)?.value

        if (afterPrefix != null) {
            str = afterPrefix
        } else {
            return str
        }
    }
}
