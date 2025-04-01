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

package com.certora.certoraprover.cvl

import com.certora.certoraprover.cvl.CallSummary.HavocingSummary
import config.Config.VMConfig
import datastructures.stdcollections.*
import java_cup.runtime.ComplexSymbolFactory
import spec.CVLKeywords
import spec.CVLWarningLogger
import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.CVLScope.Item.ExpressionSummary
import spec.cvlast.MethodEntryTargetContract.SpecificTarget
import spec.cvlast.SpecCallSummary.Exp.WithClause
import spec.cvlast.SpecCallSummary.ExpressibleInCVL
import spec.cvlast.SpecCallSummary.SummarizationMode
import spec.cvlast.typechecker.*
import spec.cvlast.typedescriptors.VMTypeDescriptor
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors
import utils.Range

// This file contains the "Java" AST nodes for the methods block and its components.  See README.md for information about the Java AST.

interface MethodEntry : Kotlinizable<MethodBlockEntry>

/**
 * Gets the default summarization mode.
 * Default summarization mode for a summary without a resolution specification is ALL, unless either:
 * 1. It's a dispatcher summary
 * 2. It's a wildcard summary on an external method
 */
fun getDefaultSummarizationMode(summary: CallSummary, target: MethodEntryTargetContract, qualifiers: spec.cvlast.MethodQualifiers) =
    if (
        summary is CallSummary.Dispatcher
        || (target is MethodEntryTargetContract.WildcardTarget && qualifiers.visibility == Visibility.EXTERNAL)
    ) {
        SummarizationMode.UNRESOLVED_ONLY
    } else {
        SummarizationMode.ALL
    }

/** Represents an entry in the `methods` block.  */
class ImportedFunction(
    private val range: Range,
    /**
     * The undefaulted method signature. The `contract` field will be `null` if no contract is
     * specified, and will be "_" if the wildcard is specified.  The defaulting happens in the
     * `kotlinize` method.
     */
    private val methodSignature: MethodSig,
    private val qualifiers: MethodQualifiers,
    private val summary: CallSummary?,
    private val withParams: List<CVLParam>?,
    private val withRange: Range?
) : MethodEntry {
    override fun toString() = "ImportedFunction($methodSignature,$qualifiers) => $summary"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodBlockEntry, CVLError> = collectingErrors {
        val target     = methodSignature.kotlinizeTarget(resolver)
        val qualifiers = bind(qualifiers.kotlinize())

        // Compute a default summarization mode based on the entire methods block entry.
        // It is still the case that if a summary explicitly specifies a summarization mode,
        // it will be used instead of this default
        val defaultSummarizationMode = summary?.let { summary ->
            getDefaultSummarizationMode(summary, target, qualifiers)
        } ?: SummarizationMode.ALL /* there is no summary so put _anything_ */

        val namedSig = bind(methodSignature.kotlinizeNamed(target, resolver, scope))
        val withClause = bind(kotlinizeWithClause(resolver, scope))

        if (summary?.summarizationMode == SummarizationMode.UNRESOLVED_ONLY // user specified UNRESOLVED
            && qualifiers.visibility==Visibility.INTERNAL // for internal method
        ) {
            collectError(UnresolvedSummaryModeOnInternalMethod(range))
        } else if (summary?.summarizationMode == SummarizationMode.DELETE // user specified DELETE
            && qualifiers.visibility==Visibility.INTERNAL // for internal method
        ) {
            collectError(DeleteSummaryModeOnInternalMethod(range))
        } else if (summary is CallSummary.Dispatcher // user specified DISPATCHER
            && qualifiers.visibility == Visibility.INTERNAL // for an internal method
        ) {
            collectError(DispatcherSummaryOnInternalMethod(range))
        }
        val summary    = bind(summary?.kotlinize(resolver, scope, namedSig.params, withClause, defaultSummarizationMode) ?: null.lift())

        return@collectingErrors ConcreteMethodBlockAnnotation(
            range,
            namedSig,
            target,
            qualifiers,
            summary
        )
    }


    /**
     * Basic checks for the `with(env)` clause, if it exists:
     * - there is an expression summary
     * - there is exactly one parameter
     * - Note: the type of the parameter is checked during typechecking; see CVLMethodsBlockTypeChecker.typeCheckExpSummary`
     * - Note: the existence of envfree is checked during typechecking
     *
     * @return the with clause, if any.
     */
    private fun kotlinizeWithClause(resolver: TypeResolver, scope: CVLScope): CollectingResult<WithClause?, CVLError> = collectingErrors {
        if (withParams == null)  { return@collectingErrors null }
        check(withRange != null) { "withParams and withRange must have the same nullity" }

        if (summary !is CallSummary.Expression) { collectError(InvalidSummaryForWithClause(withRange)) }
        if (withParams.size != 1)               { returnError(WithWrongNumberArguments(withRange, withParams.size)) }

        val withParam = bind(withParams.single().kotlinize(resolver, scope))
        return@collectingErrors WithClause(withParam, withRange)
    }
}

@Suppress("Deprecation") // TODO CERT-3752
class CatchAllSummary(
    private val range: Range,
    private val ref: MethodReferenceExp,
    private val summary: CallSummary,
    private val preFlags: List<LocatedToken>
) : MethodEntry {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CatchAllSummaryAnnotation, CVLError> = collectingErrors{
        val contractId = ref.contract
        if (contractId == null || ref.method != CVLKeywords.wildCardExp.keyword) {
            returnError(CVLError.General(ref.range, "Catch-all summaries must be of the form ContractName._"))
        }

        if (summary !is HavocingSummary) {
            returnError(CVLError.General(ref.range, "Catch-all summaries must use havocing summaries (`NONDET`, `HAVOC_ALL`, `HAVOC_ECF`, `AUTO`)"))
        }

        val contract = resolver.resolveContractName(contractId.id)
        if (!resolver.validContract(contract)) {
            returnError(CVLError.General(contractId.range, "Contract with name ${contractId.id} does not exist"))
        }

        val target = SpecificTarget(SolidityContract(contract))
        preFlags.forEach { flag ->
            if (flag.value != Visibility.EXTERNAL.toString()) {
                collectError(InvalidCatchAllFlag(flag))
            }
        }
        val qualifiers = bind(VMConfig.getMethodQualifierAnnotations(preFlags, kotlin.collections.emptyList(), range))

        // for catch-all summaries, the only possible summarization mode is, in fact, ALL:
        // Today `DISPATCHER` is not possible for `C._` and it may make sense to generalize
        // (e.g. the user is calling a known contract but wants to dispatch on different methods,
        // e.g. for Proxy support instead of inlining-whole-contract).
        val defaultSummarizationMode = getDefaultSummarizationMode(summary, target, qualifiers)

        val summ = bind(
            summary.kotlinize(
                resolver,
                scope,
                emptyList(),  /* there's no with clause */
                null,
                defaultSummarizationMode
            )
        )

        return@collectingErrors CatchAllSummaryAnnotation(range, target, summ, qualifiers)
    }
}

class UnresolvedDynamicSummary(
    private val range: Range,
    private val methodReferenceExp: MethodReferenceExp,
    private val params: List<VMParam>?,
    private val preFlags: List<LocatedToken>,
    private val dispatchList: CallSummary.DispatchList,
    private val isDeprecatedSyntax: Boolean
) : MethodEntry {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodBlockEntry, CVLError> = collectingErrors {
        if (isDeprecatedSyntax) {
            CVLWarningLogger.syntaxWarning("The syntax `function _._ external => DISPATCH...` is deprecated and will be removed in a future version. " +
                "Use instead `unresolved external in _._ => DISPATCH...`", range)

            if (methodReferenceExp.contract == null
                || !(methodReferenceExp.contract!!.id == CVLKeywords.wildCardExp.keyword
                    && methodReferenceExp.method == CVLKeywords.wildCardExp.keyword)) {
                collectError(DispatchListWithSpecificId(methodReferenceExp))
            }
        }

        val target = methodReferenceExp.kotlinizeTarget(resolver)
        val namedSig = if (params != null) {
            bind(MethodSig(range, methodReferenceExp, params, null).kotlinizeNamed(target, resolver, scope))
        } else {
            if (methodReferenceExp.method != CVLKeywords.wildCardExp.keyword) {
                collectError(DispatchListWithSpecificId(methodReferenceExp))
            }
            null
        }

        // No flags allowed on catch unresolved pattern
        preFlags
            .filter { it.value != Visibility.EXTERNAL.toString() }
            .forEach { collectError(OnlyExternalSummaryAllowed(it)) }

        val dispatchList = bind(dispatchList.kotlinize(resolver, scope))

        CatchUnresolvedSummaryAnnotation(range, target, namedSig, dispatchList)
    }

}

sealed class CallSummary (
    val range : Range,
    val summarizationMode: SummarizationMode?
) {
    constructor(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, summarizationMode: SummarizationMode?)
        : this(Range.Range(left,right), summarizationMode)

    /**
     * @param funParams  the parameters to the summarized method
     * @param withClause the environment argument defined by a `with(...)` clause, or `null` if there is no `with` clause
     * @return this, converted to a kotlin [SpecCallSummary]
     */
    abstract fun kotlinize(
        resolver: TypeResolver,
        scope: CVLScope,
        funParams: List<spec.cvlast.VMParam>,
        withClause: WithClause?,
        defaultSummarizationMode: SummarizationMode
    ): CollectingResult<ExpressibleInCVL, CVLError>

    /** An ALWAYS(c) summary  */
    class Always(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?, val returnValue: Exp)
        : CallSummary(left, right, resolution)
    {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            funParams: List<spec.cvlast.VMParam>,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.Always, CVLError>
            = returnValue.kotlinize(resolver, scope).map { retVal -> SpecCallSummary.Always(retVal, summarizationMode ?: defaultSummarizationMode,
            this@Always.range
        ) }
    }

    /** A CONSTANT summary  */
    class Constant(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?)
        : CallSummary(left, right, resolution) {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            funParams: List<spec.cvlast.VMParam>,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.Constant, CVLError> =
            SpecCallSummary.Constant(summarizationMode ?: defaultSummarizationMode, this@Constant.range).lift()
    }

    /** A PER_CALLEE_CONSTANT summary  */
    class PerCalleeConstant(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?)
        : CallSummary(left, right, resolution)
    {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope,
                               funParams: List<spec.cvlast.VMParam>,
                               withClause: WithClause?,
                               defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.PerCalleeConstant, CVLError> =
            SpecCallSummary.PerCalleeConstant(summarizationMode ?: defaultSummarizationMode,
                this@PerCalleeConstant.range
            ).lift()
    }

    sealed interface HavocingSummary

    sealed class HavocingCallSummary (
        range : Range,
        summarizationMode: SummarizationMode?
    ) : CallSummary(range, summarizationMode), HavocingSummary {
        constructor(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, summarizationMode: SummarizationMode?)
            : this(Range.Range(left,right), summarizationMode)
    }

    /** A NONDET summary  */
    class Nondet(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?)
        : HavocingCallSummary(left, right, resolution)
    {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            funParams: List<spec.cvlast.VMParam>,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.Nondet, CVLError> =
            SpecCallSummary.HavocSummary.Nondet(summarizationMode ?: defaultSummarizationMode, this@Nondet.range).lift()
    }

    /** A HAVOC_ECF summary  */
    class HavocECF(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?)
        : HavocingCallSummary(left, right, resolution)
    {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            funParams: List<spec.cvlast.VMParam>,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.HavocECF, CVLError> =
            SpecCallSummary.HavocSummary.HavocECF(summarizationMode ?: defaultSummarizationMode, this@HavocECF.range).lift()
    }

    /** A HAVOC_ALL summary  */
    class HavocAll(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?)
        : HavocingCallSummary(left, right, resolution)
    {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope,
                               funParams: List<spec.cvlast.VMParam>,
                               withClause: WithClause?,
                               defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.HavocAll, CVLError> =
            SpecCallSummary.HavocSummary.HavocAll(summarizationMode ?: defaultSummarizationMode, this@HavocAll.range).lift()
    }

    class AssertFalse(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?)
        : HavocingCallSummary(left, right, resolution)
    {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope,
                               funParams: List<spec.cvlast.VMParam>,
                               withClause: WithClause?,
                               defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.AssertFalse, CVLError> =
            SpecCallSummary.HavocSummary.AssertFalse(summarizationMode ?: defaultSummarizationMode,
                this@AssertFalse.range
            ).lift()
    }

    /** An (explicit) AUTO summary  */
    class Auto(left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?)
        : HavocingCallSummary(left, right, resolution)
    {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            funParams: List<spec.cvlast.VMParam>,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.Auto, CVLError> =
            SpecCallSummary.HavocSummary.Auto(summarizationMode ?: defaultSummarizationMode, this@Auto.range).lift()
    }

    /** A DISPATCHER summary  */
    class Dispatcher(
        left: ComplexSymbolFactory.Location,
        right: ComplexSymbolFactory.Location,
        resolution: SummarizationMode?,
        private val optimistic: Boolean,
        private val useFallback: Boolean
    )
        : CallSummary(left, right, resolution)
    {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            funParams: List<spec.cvlast.VMParam>,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.Dispatcher, CVLError> =
            SpecCallSummary.Dispatcher(summarizationMode ?: defaultSummarizationMode, optimistic, useFallback,
                this@Dispatcher.range
            ).lift()
    }

    /** A special fallback dynamic summary for all unresolved calls */
    class DispatchList(
        val range: Range,
        private val dispatcherList: List<PatternSig>,
        val default: HavocingCallSummary,
        val useFallback: Boolean
    ): Kotlinizable<SpecCallSummary.DispatchList> {
        sealed class PatternSig {
            abstract val range: Range
        }
        data class PatternSigParams(val sig: MethodSig) : PatternSig() {
            override val range: Range
                get() = sig.range
        }
        data class PattenSigWildcardMethod(val sig: MethodSig) : PatternSig() {
            override val range: Range
                get() = sig.range
        }

        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
        ): CollectingResult<SpecCallSummary.DispatchList, CVLError> {
            val translatedFuns: List<CollectingResult<SpecCallSummary.DispatchList.Pattern, CVLError>> = dispatcherList.map { pattern ->
                when(pattern) {
                    // Non wildcard method - _.foo(uint) / C.foo(uint)
                    is PatternSigParams -> {
                        val sig = pattern.sig
                        if (sig.id.method == CVLKeywords.wildCardExp.keyword) {
                            WildCardMethodWithParams(sig).asError()
                        } else if (sig.baseContract() == CVLKeywords.wildCardExp.keyword) {
                            sig.kotlinizeExternal(resolver, scope).map { ext ->
                                SpecCallSummary.DispatchList.Pattern.WildcardContract(pattern.range, ext)
                            }
                        } else {
                            sig.kotlinizeExternal(resolver, scope).map { resolution ->
                                SpecCallSummary.DispatchList.Pattern.QualifiedMethod(pattern.range, resolution)
                            }
                        }
                    }
                    // Expect wildcard method - C._
                    is PattenSigWildcardMethod -> {
                        val sig = pattern.sig
                        if (sig.baseContract() == CVLKeywords.wildCardExp.keyword
                            && sig.id.method == CVLKeywords.wildCardExp.keyword) {
                            FullWildcardInDispatchList(sig).asError()
                        } else if (sig.id.method != CVLKeywords.wildCardExp.keyword) {
                            NonWildcardNoParams(sig.id).asError()
                        } else {
                            val target = sig.kotlinizeTarget(resolver)
                            check(target is SpecificTarget) {"Expecting a specific target from kotlinization, got $target"}
                            SpecCallSummary.DispatchList.Pattern.WildcardMethod(pattern.range, target).lift()                        }
                    }
                }
            }
            val kDefault =
                default.kotlinize(resolver, scope, listOf(), null, SummarizationMode.UNRESOLVED_ONLY).bind {
                    collectingErrors {
                        if (it !is SpecCallSummary.HavocSummary) {
                            collectError(NonHavocingSummary(default))
                        }
                        it as SpecCallSummary.HavocSummary
                    }
                }
            return kDefault.map(translatedFuns.flatten()) { d, t -> SpecCallSummary.DispatchList(this@DispatchList.range, t, d, useFallback) }
        }
    }

    /**
     * A summary of the form `=> f(...) expect t`;
     * uses the CVL or ghost function `f` as a summary
     * @param exp           The `f(...)` portion
     * @param expectedType  The `expect t` portion
     */
    class Expression(
        left: ComplexSymbolFactory.Location, right: ComplexSymbolFactory.Location, resolution: SummarizationMode?,
        private val exp: Exp, private val expectedType: ExpectClause,
    ) : CallSummary(left, right, resolution) {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            funParams: List<spec.cvlast.VMParam>,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<ExpressibleInCVL, CVLError> {
            return scope.extendInCollecting({ scopeId: Int -> ExpressionSummary(scopeId) }) { childScope: CVLScope? ->
                collectingErrors {
                    map(
                        /* exp */          exp.kotlinize(resolver, childScope!!),
                        /* expectedType */ expectedType.kotlinize(resolver, childScope)
                    ) { exp, expectedType ->
                        SpecCallSummary.Exp(
                            // either the user specified the desired summarization mode, or we use
                            // the default as is computed from both sides of the arrow of the methods block entry,
                            // not just the summary itself as we did in the past.
                            // (This repeats for all summary types kotlinizations in this file)
                            summarizationMode ?: defaultSummarizationMode,
                            exp,
                            funParams,
                            withClause,
                            this@Expression.range,
                            childScope,
                            expectedType
                        )
                    }
            }}
        }
    }

    /** The `expect <type>` portion of an [Expression] summary </type> */
    interface ExpectClause {
        /**
         * Convert the expected return types to [VMTypeDescriptor]s.  `expect void` returns an empty list, whereas a
         * missing expect clause returns `null`
         */
        fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<VMTypeDescriptor>?, CVLError>

        /** `expect void`  */
        class Void : ExpectClause {
            override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<VMTypeDescriptor>, CVLError>
                = emptyList<VMTypeDescriptor>().lift()
        }

        /** `expect t` for a non-void type `t`  */
        class Type(private val type: List<VMParam>) : ExpectClause {
            override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<VMTypeDescriptor>, CVLError>
                = type.map { it.kotlinize(resolver, scope).map { it.vmType } }.flatten()
        }

        /** missing `expect` clause  */
        class None : ExpectClause {
            override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<VMTypeDescriptor>?, CVLError>
                = null.lift()
        }
    }
}


class MethodQualifiers(
    private val range: Range,
    val preReturnsAnnotations: List<LocatedToken>,
    val postReturnsAnnotations: List<LocatedToken>
) : List<LocatedToken> by (preReturnsAnnotations + postReturnsAnnotations) {
    fun kotlinize(): CollectingResult<spec.cvlast.MethodQualifiers, CVLError>
        = VMConfig.getMethodQualifierAnnotations(preReturnsAnnotations, postReturnsAnnotations, range)
}
