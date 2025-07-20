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

@file:UseSerializers(BigIntegerSerializer::class)

package spec.cvlast

import com.certora.collect.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import log.*
import spec.cvlast.typedescriptors.VMTypeDescriptor
import utils.*
import java.math.BigInteger
import datastructures.stdcollections.*
import scene.*

// This class indicates whether or not a call can revert, ie produce a return code of 0.
interface MaybeRevert {
    fun isRevertable() = false
}

private val logger = Logger(LoggerTypes.SUMMARIZATION)

@Serializable
@Treapable
sealed class SpecCallSummary : AmbiSerializable, MaybeRevert {
    // true when the summary should only be applied in cases where an implementation for the function is not found
    // otherwise false, meaning the function should always be summarized
    abstract val summarizationMode: SummarizationMode

    @KSerializable
    /**
     * [surfaceSyntax] gives the concrete (or surface) syntax for the summarization mode, that is, taking this
     * string and postfixing a summary with it should cause that summarization mode to be used.
     */
    enum class SummarizationMode(val surfaceSyntax: String) {
        UNRESOLVED_ONLY(UNRESOLVED_KEYWORD),
        ALL(FORCED_KEYWORD),
        DELETE(DELETE_KEYWORD),
    }

    override fun toString(): String {
        val forcedStr = summarizationMode.surfaceSyntax
        return "$forcedStr $summaryName summary"
    }

    /**
     * A summary whose selection is expressible in CVL.
     */
    @Serializable
    sealed class ExpressibleInCVL : SpecCallSummary() {
        abstract val range: Range

        override fun toString(): String = super.toString() + " @ $range"
    }

    /**
     * marker interface indicating that the summary name of this [SpecCallSummary] must be canonical: i.e., it must
     * include all parameters to the summary (excluding the value of the [SpecCallSummary.summarizationMode] flag).
     */
    sealed interface SummaryNameIsCanonical

    /**
     * Marker interface for those summaries which are usable on internal summaries
     */
    sealed interface InternalSummary

    /**
     * The name of this summary as shown at
     * [Certora Prover Documentation](https://docs.certora.com/en/latest/docs/cvl/methods.html#summary-types).
     */
    abstract val summaryName: String

    val summarizeAllCalls get() = summarizationMode != SummarizationMode.UNRESOLVED_ONLY


    abstract fun toUIString(): String

    /**
     * Indicates that this summary doesn't depend on or use the arguments to the summarized function. All summaries
     * except for [Exp] are no arg summaries (by definition); in the [Exp] case this depends on whether the parameter names bound
     * in the methods block (if any) are used in the body of the summary.
     */
    open fun isNoArgSummary() = true


    @Serializable
    data class Always(val exp: CVLExp, override val summarizationMode: SummarizationMode, override val range: Range) :
        ExpressibleInCVL(), SummaryNameIsCanonical, InternalSummary {

        override val summaryName: String
            get() = "Always($exp)"

        override fun toUIString(): String = "$summaryName view function"
        override fun toString(): String = super.toString()

        override fun hashCode(): Int = hash {
            it + summarizationMode + range + exp
        }
    }

    @Serializable
    data class Constant(override val summarizationMode: SummarizationMode, override val range: Range) : ExpressibleInCVL(), SummaryNameIsCanonical, InternalSummary {
        override val summaryName: String
            get() = "CONSTANT"

        override fun toUIString(): String = "$summaryName view function"
        override fun toString(): String = super.toString()

        override fun hashCode(): Int = hash {
            it + summarizationMode + range
        }
    }

    @Serializable
    data class PerCalleeConstant(override val summarizationMode: SummarizationMode, override val range: Range) :
        ExpressibleInCVL(), SummaryNameIsCanonical {
        override val summaryName: String
            get() = "PER_CALLEE_CONSTANT"

        override fun toUIString(): String = "$summaryName view function"
        override fun toString(): String = super.toString()

        override fun hashCode(): Int = hash {
            it + summarizationMode + range
        }
    }

    @Serializable
    data class Dispatcher(
        override val summarizationMode: SummarizationMode,
        val optimistic: Boolean,
        val useFallback: Boolean,
        override val range: Range) :
        ExpressibleInCVL(), SummaryNameIsCanonical {

        override val summaryName: String
            get() = "DISPATCHER(optimistic = $optimistic)"

        override fun toUIString(): String = summaryName
        override fun toString(): String = super.toString()

        override fun hashCode(): Int = hash {
            it + summarizationMode + range + optimistic
        }
    }

    @Serializable
    data class DispatchList(
        override val range: Range,
        val dispatcherList: List<Pattern>,
        val default: HavocSummary?,
        val useFallback: Boolean,
        val optimistic: Boolean,
    ) : ExpressibleInCVL() {
        @Serializable
        @Treapable
        sealed class Pattern: AmbiSerializable {

            abstract val range: Range

            abstract fun getMethods(scene: IScene, sigResolution: Set<BigInteger?>, calleeResolution: BigInteger?): Collection<ITACMethod>

            abstract fun toUIString(): String

            protected fun ContractReference.isWildcardContract() = when(this) {
                AllContracts -> true
                CurrentContract,
                is SolidityContract -> false
            }

            /**
             * Represents a pattern entry of a specific function from a specific contract
             */
            @Serializable
            data class QualifiedMethod(override val range: Range, val sig: ExternalQualifiedMethodParameterSignature) : Pattern() {
                init {
                    check(!sig.qualifiedMethodName.host.isWildcardContract()) { "Expecting a specific contract, got a wildcard." }
                    logger.debug { "Added qualified method pattern ${toUIString()} to dispatch list" }
                }

                private fun getContract(): SolidityContract {
                    return sig.qualifiedMethodName.host as SolidityContract
                }

                override fun getMethods(
                    scene: IScene,
                    sigResolution: Set<BigInteger?>,
                    calleeResolution: BigInteger?
                ): Collection<ITACMethod> {
                    val contract = getContract()
                    if (calleeResolution != null && calleeResolution != scene.getContract(contract).instanceId) {
                        logger.debug { "Filtered ${toUIString()} due to callee resolution (expected contract: " +
                            "$contract, callee resolution: $calleeResolution)" }
                        return listOf()
                    }
                    return if (sigResolution.isEmpty() || sig.sighashInt?.n in sigResolution) {
                        listOf(scene.getMethod(contract, sig.sighashInt!!.n))
                    } else {
                        logger.debug { "Filtered ${toUIString()} due to sighash resolution" }
                        listOf()
                    }
                }

                override fun toUIString(): String = sig.prettyPrintFullyQualifiedName()
            }

            /**
             * Represents a pattern entry for all methods from a given contract
             */
            @Serializable
            data class WildcardMethod(override val range: Range, val contract: MethodEntryTargetContract.SpecificTarget) : Pattern() {
                init {
                    logger.debug { "Added wildcard method pattern ${toUIString()} to dispatch list" }
                }

                /**
                 * Recall that we are using `null` as the canonical representation for "is the fallback function"
                 */
                private fun Set<BigInteger?>.hasMatchFor(m: ITACMethod) = m.sigHash?.n?.let {
                    it in this
                } == true || (m.attribute == MethodAttribute.Unique.Fallback && null in this)

                private fun getMethodsForContract(
                    contract: IContractClass,
                    sigResolution: Set<BigInteger?>,
                ) : Collection<ITACMethod> {
                    val methods = contract.getStandardMethods()
                    return if (sigResolution.isEmpty()) {
                        methods
                    } else {
                        val res = methods.filter {
                            sigResolution.hasMatchFor(it)
                        }
                        if (methods.isNotEmpty() && res.isEmpty()) {
                            logger.debug { "Filtered ${toUIString()} due to sighash resolution " +
                                "not matching known methods" }
                        }
                        res
                    }

                }

                override fun getMethods(
                    scene: IScene,
                    sigResolution: Set<BigInteger?>,
                    calleeResolution: BigInteger?
                ): Collection<ITACMethod> {
                    val contract = scene.getContract(contract.contract)
                    if (calleeResolution != null && calleeResolution != contract.instanceId) {
                        logger.debug { "Filtered ${toUIString()} due to callee resolution (expected contract: " +
                            "${contract.name}-${contract.instanceId}, callee resolution: $calleeResolution)" }
                        return listOf()
                    }
                    return getMethodsForContract(contract, sigResolution) + scene.getContracts().filter {
                        it is IClonedContract && it.sourceContractId == contract.instanceId
                    }.flatMap {
                        getMethodsForContract(it, sigResolution)
                    }
                }

                override fun toUIString(): String = "$contract._"
            }

            /**
             * Represents a pattern entry for all methods matching signature from any contract
             */
            @Serializable
            data class WildcardContract(override val range: Range, val sig: ExternalQualifiedMethodParameterSignature) : Pattern() {
                init {
                    logger.debug { "Added wildcard contract pattern ${toUIString()} to dispatch list" }
                }

                override fun getMethods(
                    scene: IScene,
                    sigResolution: Set<BigInteger?>,
                    calleeResolution: BigInteger?
                ): Collection<ITACMethod> {
                    if (sigResolution.isNotEmpty() && sig.sighashInt?.n !in sigResolution) {
                        logger.debug { "Filtered ${toUIString()} due to sig resolution." }
                        return listOf()
                    }
                    val contracts = calleeResolution?.let { listOf(scene.getContract(it)) } ?: scene.getContracts()
                    return contracts.flatMap { contract ->
                        contract.getStandardMethods().filter {
                            it.sigHash == sig.sighashInt
                        }
                    }
                }

                override fun toUIString(): String = "_.${sig.printMethodParameterSignature()}"
            }
        }

        override val summarizationMode: SummarizationMode
            get() = SummarizationMode.UNRESOLVED_ONLY

        override val summaryName: String
            get() = "DISPATCH_LIST"

        override fun toUIString(): String =
            "DISPATCH ${getFlags()}[ ${dispatcherList.map { it.toUIString() }.joinToString(", ")} ] ${default?.let { "default ${it.toUIString()}" }.orEmpty()}"

        private fun getFlags(): String = when{
            !optimistic && !useFallback -> ""
            else -> "(optimistic=$optimistic, useFallback=$useFallback)"
        }
        fun getMethods(scene: IScene, sigResolution: Set<BigInteger?>, calleeResolution: BigInteger?): Set<ITACMethod> =
            dispatcherList.flatMapToSet { it.getMethods(scene, sigResolution, calleeResolution) }
    }

    @Serializable
    sealed class HavocSummary : ExpressibleInCVL(), SummaryNameIsCanonical {

        override fun toUIString(): String = "$summaryName havoc"

        @Serializable
        data class HavocECF(override val summarizationMode: SummarizationMode, override val range: Range) : HavocSummary() {
            override val summaryName: String
                get() = "HAVOC_ECF"
            override fun isRevertable() = true
            override fun toString(): String = super.toString()

            override fun hashCode(): Int = hash {
                it + summarizationMode + range
            }
        }

        @Serializable
        data class HavocAll(override val summarizationMode: SummarizationMode, override val range: Range) : HavocSummary() {
            override val summaryName: String
                get() = "HAVOC_ALL"

            override fun isRevertable() = true
            override fun toString(): String = super.toString()

            override fun hashCode(): Int = hash {
                it + summarizationMode + range
            }
        }

        @Serializable
        data class AssertFalse(override val summarizationMode: SummarizationMode, override val range: Range) : HavocSummary() {
            override val summaryName: String
                get() = "ASSERT_FALSE"

            override fun isRevertable() = false
            override fun toString(): String = super.toString()

            override fun hashCode(): Int = hash {
                it + summarizationMode + range
            }
        }

        @Serializable
        data class Nondet(override val summarizationMode: SummarizationMode, override val range: Range) : HavocSummary(), InternalSummary {
            override val summaryName: String
                get() = "NONDET"
            override fun toUIString(): String = "$summaryName view function"
            override fun toString(): String = super.toString()

            override fun hashCode(): Int = hash {
                it + summarizationMode + range
            }
        }

        @Serializable
        data class Auto(override val summarizationMode: SummarizationMode, override val range: Range = Range.Empty()) :
            HavocSummary() {
            override val summaryName: String
                get() = "AUTO"
            override fun isRevertable() = true
            override fun toString(): String = super.toString()

            override fun hashCode(): Int = hash {
                it + summarizationMode + range
            }
        }
    }

    /**
     * [sighash] the Sighash of the rerouted function whose name and contract are given by [target].
     * The original arguments bound by the summary are given in [args]. The arguments to forward to [target] are given by [forwardArgs];
     * the ith element gives the index into [args] that should be passed as the ith argument of the forwarded call.
     */
    @KSerializable
    data class Reroute(
        override val summarizationMode: SummarizationMode,
        override val range: Range,
        val sighash: BigInteger,
        val target: QualifiedFunction,
        val args: List<VMParam>,
        val forwardArgs: List<Int>
    ) : ExpressibleInCVL(), SummaryNameIsCanonical, InternalSummary {
        override val summaryName: String
            get() = rerouteString

        private val rerouteString : String get() = target.host.name + "." + target.methodId + forwardArgs.joinToString(", ", prefix = "(", postfix = ")") {
            (args[forwardArgs[it]] as VMParam.Named).name
        }

        override fun toUIString(): String {
            return "reroute to $rerouteString"
        }

        override fun hashCode(): Int = hash {
            it + summarizationMode + range + sighash + target + args + forwardArgs
        }
    }

    /**
     * @property exp The expression used for the summary.
     *
     * @property funParams the parameters to the function being summarized. Note, all parameters are included for an
     * accurate picture of the function being summarized--specifically to understand decoding arguments, we need to
     * know the order and size of all arguments. However, *only* params which are Named get bound as
     * identifiers in the scope of the summary, and only those are usable
     *
     * @property withClause is the parameter to the `with(...)` clause, if any.  During typechecking, we will ensure that
     * the param has type `env`
     */
    @Serializable
    data class Exp(
        override val summarizationMode: SummarizationMode,
        val exp: CVLExp,
        val funParams: List<VMParam>,
        val withClause: WithClause?,
        override val range: Range,
        override val scope: CVLScope,
        // null -> no type provided, empty -> void
        val expectedType: List<VMTypeDescriptor>?
    ): ExpressibleInCVL(), CreatesScope, InternalSummary {
        override val summaryName: String
            get() = exp.toString()

        override fun toUIString(): String = "CVL/Ghost Function '$summaryName'"
        override fun toString() = "Expression Summary: $summaryName"

        /** The parameter to a `with` clause.  Wrapped so we can carry around the [range] for error messages. */
        @Serializable
        @Treapable
        data class WithClause(val param: CVLParam, val range : Range) : AmbiSerializable

        override fun hashCode(): Int = hash {
            it + summarizationMode + exp + funParams + withClause + range + scope + expectedType
        }

        override fun isNoArgSummary(): Boolean {
            val boundNames = funParams.mapNotNullToTreapSet {
                (it as? VMParam.Named)?.id
            }
            val usesParams = object : CVLExpFolder<Boolean>() {
                override fun variable(acc: Boolean, exp: CVLExp.VariableExp): Boolean {
                    return exp.id in boundNames
                }

                override fun expr(acc: Boolean, exp: CVLExp): Boolean {
                    if(acc) {
                        return true
                    }
                    return super.expr(false, exp)
                }
            }.expr(false, exp)
            return !usesParams
        }
    }

    @Serializable
    object OptimisticFallback  : SpecCallSummary() {
        override val summarizationMode: SummarizationMode
            get() = SummarizationMode.UNRESOLVED_ONLY

        override val summaryName: String
            get() = "Optimistic Fallback DISPATCHER"

        override fun hashCode() = hashObject(this)
        fun readResolve() : Any = OptimisticFallback
        override fun toUIString(): String = summaryName
    }

    companion object {
        const val FORCED_KEYWORD = "ALL"
        const val UNRESOLVED_KEYWORD = "UNRESOLVED"
        const val DELETE_KEYWORD = "DELETE"
    }
}
