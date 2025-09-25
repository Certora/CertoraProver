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

import config.Config.VMConfig
import datastructures.stdcollections.*
import spec.CVLKeywords
import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.CVLScope.Item.ExpressionSummary
import spec.cvlast.MethodEntryTargetContract.SpecificTarget
import spec.cvlast.SpecCallSummary.Exp.WithClause
import spec.cvlast.SpecCallSummary.ExpressibleInCVL
import spec.cvlast.SpecCallSummary.SummarizationMode
import spec.cvlast.typechecker.*
import spec.cvlast.typedescriptors.VMTypeDescriptor
import spec.isWildcard
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors

// This file contains the "Java" AST nodes for the methods block and its components.  See README.md for information about the Java AST.

sealed interface MethodEntry : Kotlinizable<MethodBlockEntry>

data class MethodsBlock(val entries: List<MethodEntry>, override val range: Range.Range) : TopLevel<List<MethodBlockEntry>> {
    override fun kotlinize(
        resolver: TypeResolver,
        scope: CVLScope
    ): CollectingResult<List<MethodBlockEntry>, CVLError> = entries.map { entry -> entry.kotlinize(resolver, scope) }.flatten()
}

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
data class ImportedFunction(
    override val range: Range,
    /**
     * The undefaulted method signature. The `contract` field will be `null` if no contract is
     * specified, and will be "_" if the wildcard is specified.  The defaulting happens in the
     * `kotlinize` method.
     */
    internal val methodSignature: MethodSig,
    internal val qualifiers: MethodQualifiers,
    internal val summary: CallSummary?,
    internal val withParams: List<CVLParam>?,
    internal val withRange: Range?
) : MethodEntry {
    override fun toString() = "ImportedFunction($methodSignature,$qualifiers) => $summary"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodBlockEntry, CVLError> = collectingErrors {
        val target     = methodSignature.kotlinizeTarget(resolver)
        val qualifierTokens = qualifiers.preReturnsAnnotations + qualifiers.postReturnsAnnotations
        val qualifiers = bind(qualifiers.kotlinize(resolver, scope))

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
        } else if (summary is CallSummary.DispatchList // user specified DISPATCH
        ) {
            qualifierTokens.filter { it.value != Visibility.EXTERNAL.toString() }
                .forEach { collectError(OnlyExternalSummaryAllowed(it)) }
        }
        val summary    = bind(summary?.kotlinize(resolver, scope, CallSummary.SignatureMethod(namedSig, methodSignature), withClause, defaultSummarizationMode) ?: null.lift())

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
data class CatchAllSummary(
    override val range: Range,
    internal val ref: MethodReferenceExp,
    internal val summary: CallSummary,
    internal val preFlags: List<LocatedToken>
) : MethodEntry {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CatchAllSummaryAnnotation, CVLError> = collectingErrors{
        val contractId = ref.contract
        if (contractId == null || ref.method != CVLKeywords.wildCardExp.keyword) {
            returnError(CVLError.General(ref.range, "Catch-all summaries must be of the form ContractName._"))
        }

        if (summary !is CallSummary.HavocingCallSummary) {
            if (summary is CallSummary.DispatchList && contractId.id.isWildcard()) {
                returnError(DispatchListOnOldUnresolvedCallSyntax(range))
            }
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
                summarizedMethod = CallSummary.CatchAllMethod,
                withClause = null,
                defaultSummarizationMode
            )
        )

        return@collectingErrors CatchAllSummaryAnnotation(range, target, summ, qualifiers)
    }
}

data class UnresolvedDynamicSummary(
    override val range: Range,
    internal val methodReferenceExp: MethodReferenceExp,
    internal val params: List<VMParam>?,
    internal val preFlags: List<LocatedToken>,
    internal val summary: CallSummary?,
) : MethodEntry {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodBlockEntry, CVLError> = collectingErrors {

        val target = methodReferenceExp.kotlinizeTarget(resolver)
        val namedSig = if (params != null) {
            bind(MethodSig(range, methodReferenceExp, params, emptyList()).kotlinizeNamed(target, resolver, scope))
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

        val summary = if (summary != null) {
            bind(summary.kotlinize(
                resolver, scope,
                summarizedMethod = CallSummary.UnresolvedMethod,
                withClause = null,
                defaultSummarizationMode = SummarizationMode.UNRESOLVED_ONLY
            ))
        } else {
            // TODO: CERT-9281
            returnAnyway()
        }

        CatchUnresolvedSummaryAnnotation(range, target, namedSig, summary)
    }

}

sealed interface CallSummary : HasRange {
    override val range : Range
    val summarizationMode: SummarizationMode?

    sealed interface SummarizedMethod {
        val params: List<spec.cvlast.VMParam>
    }

    data object CatchAllMethod : SummarizedMethod {
        override val params: List<spec.cvlast.VMParam>
            get() = listOf()
    }
    data object UnresolvedMethod: SummarizedMethod {
        override val params: List<spec.cvlast.VMParam>
            get() = listOf()
    }
    data class SignatureMethod(val sig: MethodParameterSignature, val sigRaw: MethodSig): SummarizedMethod {
        override val params: List<spec.cvlast.VMParam>
            get() = sig.params
    }

    /**
     * @param summarizedMethod the summarized method
     * @param withClause the environment argument defined by a `with(...)` clause, or `null` if there is no `with` clause
     * @return this, converted to a kotlin [SpecCallSummary]
     */
    fun kotlinize(
        resolver: TypeResolver,
        scope: CVLScope,
        summarizedMethod: SummarizedMethod,
        withClause: WithClause?,
        defaultSummarizationMode: SummarizationMode,
    ): CollectingResult<ExpressibleInCVL, CVLError>

    /** An ALWAYS(c) summary  */
    data class Always(override val range: Range, override val summarizationMode: SummarizationMode?, val returnValue: Exp) : CallSummary {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            summarizedMethod: SummarizedMethod,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.Always, CVLError>
            = returnValue.kotlinize(resolver, scope).map { retVal -> SpecCallSummary.Always(retVal, summarizationMode ?: defaultSummarizationMode,
            this@Always.range
        ) }
    }

    /** A CONSTANT summary  */
    data class Constant(override val range: Range, override val summarizationMode: SummarizationMode?) : CallSummary {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            summarizedMethod: SummarizedMethod,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.Constant, CVLError> =
            SpecCallSummary.Constant(summarizationMode ?: defaultSummarizationMode, this@Constant.range).lift()
    }

    /** A PER_CALLEE_CONSTANT summary  */
    data class PerCalleeConstant(override val range: Range, override val summarizationMode: SummarizationMode?) : CallSummary {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope,
                               summarizedMethod: SummarizedMethod,
                               withClause: WithClause?,
                               defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.PerCalleeConstant, CVLError> =
            SpecCallSummary.PerCalleeConstant(summarizationMode ?: defaultSummarizationMode,
                this@PerCalleeConstant.range
            ).lift()
    }

    sealed class HavocingCallSummary : CallSummary {
        abstract override val range: Range
        abstract override val summarizationMode: SummarizationMode?
    }

    /** A NONDET summary  */
    data class Nondet(override val range: Range, override val summarizationMode: SummarizationMode?) : HavocingCallSummary() {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            summarizedMethod: SummarizedMethod,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.Nondet, CVLError> =
            SpecCallSummary.HavocSummary.Nondet(summarizationMode ?: defaultSummarizationMode, this@Nondet.range).lift()
    }

    /** A HAVOC_ECF summary  */
    data class HavocECF(override val range: Range, override val summarizationMode: SummarizationMode?) : HavocingCallSummary() {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            summarizedMethod: SummarizedMethod,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.HavocECF, CVLError> =
            SpecCallSummary.HavocSummary.HavocECF(summarizationMode ?: defaultSummarizationMode, this@HavocECF.range).lift()
    }

    /** A HAVOC_ALL summary  */
    data class HavocAll(override val range: Range, override val summarizationMode: SummarizationMode?) : HavocingCallSummary() {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope,
                               summarizedMethod: SummarizedMethod,
                               withClause: WithClause?,
                               defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.HavocAll, CVLError> =
            SpecCallSummary.HavocSummary.HavocAll(summarizationMode ?: defaultSummarizationMode, this@HavocAll.range).lift()
    }

    data class AssertFalse(override val range: Range, override val summarizationMode: SummarizationMode?) : HavocingCallSummary() {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope,
                               summarizedMethod: SummarizedMethod,
                               withClause: WithClause?,
                               defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.AssertFalse, CVLError> =
            SpecCallSummary.HavocSummary.AssertFalse(summarizationMode ?: defaultSummarizationMode,
                this@AssertFalse.range
            ).lift()
    }

    /** An (explicit) AUTO summary  */
    data class Auto(override val range: Range, override val summarizationMode: SummarizationMode?) : HavocingCallSummary() {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            summarizedMethod: SummarizedMethod,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.HavocSummary.Auto, CVLError> =
            SpecCallSummary.HavocSummary.Auto(summarizationMode ?: defaultSummarizationMode, this@Auto.range).lift()
    }

    /** A DISPATCHER summary  */
    data class Dispatcher(
        override val range: Range,
        override val summarizationMode: SummarizationMode?,
        val flags: FlagSyntax?,
    ) : CallSummary {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            summarizedMethod: SummarizedMethod,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<SpecCallSummary.Dispatcher, CVLError> {
            val (optimistic, useFallback) = when (this.flags) {
                is FlagSyntax.OptimismLiteral -> Pair(this.flags.asBoolean, false)
                is FlagSyntax.FlagList -> Pair(this.flags.parsedOptimistic ?: false, this.flags.parsedUseFallback ?: false)
                null -> Pair(false, false)
            }
            return SpecCallSummary.Dispatcher(
                summarizationMode ?: defaultSummarizationMode, optimistic, useFallback,
                this@Dispatcher.range
            ).lift()
        }
    }

    /** A special fallback dynamic summary for all unresolved calls */
    data class DispatchList(
        override val range: Range,
        internal val dispatcherList: List<PatternSig>,
        val default: HavocingCallSummary?,
        val flags: FlagSyntax.FlagList?,
        override val summarizationMode: SummarizationMode?,
    ): CallSummary {
        sealed interface PatternSig : HasRange {
            val sig: MethodSig
            override val range: Range get() = sig.range

            data class Params(override val sig: MethodSig) : PatternSig
            data class WildcardMethod(override val sig: MethodSig) : PatternSig
        }

        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            summarizedMethod: SummarizedMethod,
            withClause: WithClause?,
            defaultSummarizationMode: SummarizationMode
        ): CollectingResult<ExpressibleInCVL, CVLError> {
            when (summarizedMethod) {
                CatchAllMethod,
                UnresolvedMethod -> { /* ok */ }
                is SignatureMethod -> when(summarizedMethod.sig) {
                    is MethodParameterSignature.MethodParamSig,
                    is MethodSignature.MethodSig -> { /* we are in a _.sth entry, ok */ }
                    is QualifiedMethodSignature.QualifiedMethodSig -> return DispatchListOnUnsupportedMethodSig(range, summarizedMethod.sig).asError()
                    is ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig,
                    is UniqueMethod,
                    is ExternalQualifiedMethodParameterSignature.ExternalQualifiedMethodParamSig,
                    is QualifiedMethodParameterSignature.QualifiedMethodParamSig -> `impossible!`
                }
            }

            val optimistic = this.flags?.parsedOptimistic ?: false
            val useFallback = this.flags?.parsedUseFallback ?: false

            val translatedFuns: List<CollectingResult<SpecCallSummary.DispatchList.Pattern, CVLError>> = dispatcherList.map { pattern ->
                when(pattern) {
                    // Non wildcard method - _.foo(uint) / C.foo(uint)
                    is PatternSig.Params -> {
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
                    is PatternSig.WildcardMethod -> {
                        val sig = pattern.sig
                        if (sig.baseContract() == CVLKeywords.wildCardExp.keyword
                            && sig.id.method == CVLKeywords.wildCardExp.keyword) {
                            FullWildcardInDispatchList(sig).asError()
                        } else if (sig.id.method != CVLKeywords.wildCardExp.keyword) {
                            NonWildcardNoParams(sig.id).asError()
                        } else if (summarizedMethod is SignatureMethod && !useFallback) {
                            val qualifiedSig = summarizedMethod.sigRaw.copy(
                                id = summarizedMethod.sigRaw.id.copy(
                                    contract = sig.baseContract()?.let { VariableExp(it, sig.range) })
                            )
                            qualifiedSig.kotlinizeExternal(resolver, scope)
                                .map { SpecCallSummary.DispatchList.Pattern.QualifiedMethod(pattern.range, it) }
                        } else {
                            val target = sig.kotlinizeTarget(resolver)
                            check(target is SpecificTarget) {"Expecting a specific target from kotlinization, got $target"}
                            SpecCallSummary.DispatchList.Pattern.WildcardMethod(pattern.range, target).lift()                        }
                    }
                }
            }

            if(optimistic && default != null){
                return OptimisticDispatchListHasNoDefault(this).asError()
            }
            val kDefault =
                default?.kotlinize(resolver, scope, summarizedMethod, null, SummarizationMode.UNRESOLVED_ONLY)?.bind {
                    collectingErrors {
                        if (it !is SpecCallSummary.HavocSummary) {
                            collectError(NonHavocingSummary(default))
                        }
                        it as SpecCallSummary.HavocSummary
                    }
                } ?: null.lift()

            return kDefault.map(translatedFuns.flatten()) { d, t -> SpecCallSummary.DispatchList(this@DispatchList.range, t, d, useFallback, optimistic) }
        }
    }

    /**
     * A summary of the form `=> f(...) expect t`;
     * uses the CVL or ghost function `f` as a summary
     * @param exp           The `f(...)` portion
     * @param expectedType  The `expect t` portion
     */
    data class Expression(
        override val range: Range,
        override val summarizationMode: SummarizationMode?,
        internal val exp: Exp,
        internal val expectedType: ExpectClause,
    ) : CallSummary {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope,
            summarizedMethod: SummarizedMethod,
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
                            summarizedMethod.params,
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
    sealed interface ExpectClause : Kotlinizable<List<VMTypeDescriptor>?> {
        override val range: Range

        /**
         * Convert the expected return types to [VMTypeDescriptor]s.  `expect void` returns an empty list, whereas a
         * missing expect clause returns `null`
         */
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<VMTypeDescriptor>?, CVLError>

        /** `expect void`  */
        class Void(override val range: Range.Range) : ExpectClause {
            override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<VMTypeDescriptor>, CVLError>
                = emptyList<VMTypeDescriptor>().lift()
        }

        /** `expect t` for a non-void type `t`  */
        class Type(internal val type: List<VMParam>, override val range: Range.Range) : ExpectClause {
            override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<VMTypeDescriptor>, CVLError>
                = type.map { it.kotlinize(resolver, scope).map { param -> param.vmType } }.flatten()
        }

        /** missing `expect` clause  */
        class None : ExpectClause {
            override val range: Range get() = Range.Empty()
            override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<VMTypeDescriptor>?, CVLError>
                = null.lift()
        }
    }

    /**
     * we have several competing syntaxes here.
     * actually, there's another one - we model "no parenthesis" as null.
     * the reason we need to model it like this, is so that we don't lose the original syntax used in the input.
     *
     * XXX: this should also contain the captured range.
     */
    sealed interface FlagSyntax {
        enum class OptimismLiteral(val asBoolean: Boolean) : FlagSyntax { TRUE(true), FALSE(false), ASSERT(false), REQUIRE(true) }
        data class FlagList(val parsedOptimistic: Boolean?, val parsedUseFallback: Boolean?) : FlagSyntax
    }
}

data class MethodQualifiers(
    override val range: Range,
    val preReturnsAnnotations: List<LocatedToken>,
    val postReturnsAnnotations: List<LocatedToken>
) : Kotlinizable<spec.cvlast.MethodQualifiers> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.MethodQualifiers, CVLError>
        = VMConfig.getMethodQualifierAnnotations(preReturnsAnnotations, postReturnsAnnotations, range)
}
