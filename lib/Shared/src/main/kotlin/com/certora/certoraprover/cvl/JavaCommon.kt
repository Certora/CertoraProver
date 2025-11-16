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

import datastructures.stdcollections.*
import spec.CVLKeywords
import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.CVLParam
import spec.cvlast.MethodParamFilters.Companion.noFilters
import spec.cvlast.VMParam.Unnamed
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.InvalidIdentifier
import spec.cvlast.typedescriptors.PrintingContext
import tac.TACIdentifiers
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors
import utils.HasRange
import utils.Range

// This file contains the "Java" AST nodes that are used in many places, such as parameters and method signatures.
// See README.md for a description of the Java AST.

sealed interface Kotlinizable<T> : HasRange {
    fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<T, CVLError>
}

fun <T> List<Kotlinizable<T>>.kotlinize(resolver : TypeResolver, scope : CVLScope) : CollectingResult<List<T>, CVLError>
    = this.map { it.kotlinize(resolver, scope) }.flatten()

sealed interface Param : HasRange {
    val type: TypeOrLhs
}

sealed interface VMParam : Kotlinizable<spec.cvlast.VMParam>, Param {
    /**
     * The location associated with the Param (e.g. "memory" or "calldata"), or `null` if no location is specified.
     * NOTE: we currently only allow you to write one location specifier per parameter; we don't support something like
     * `function foo((C.S calldata)[] memory)`.  If we do, we would need to move the `location` to the type.  My
     * understanding is that this is not currently possible in Solidity but that the Solidity team is actively working
     * on supporting it.
     */
    val location: String?
}

data class UnnamedVMParam(override val type: TypeOrLhs, override val location: String?, override val range: Range) : VMParam {
    override fun toString() = "UnnamedVMParam($type)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<Unnamed, CVLError>
        = type.toVMType(resolver, location, scope).map { Unnamed(it, range) }
}

data class NamedVMParam(override val type: TypeOrLhs, override val location: String?, val id: String, override val range: Range) : VMParam {
    override fun toString() = "NamedVMParam($type,$id)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.VMParam.Named, CVLError>
        = type.toVMType(resolver, location, scope).map { spec.cvlast.VMParam.Named(id, it, range, id) }
}

data class CVLParam(override val type: TypeOrLhs, val id: String, override val range: Range) : Kotlinizable<CVLParam>, Param {
    override fun toString() = "CVLParam($type,$id)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLParam, CVLError> =
        type.toCVLType(resolver, scope)
            .map(checkIdValidity(id, range, "CVL parameter")) { type, id -> CVLParam(type, id, range) }
}

data class LocatedToken(override val range: Range, val value: String) : HasRange {
    override fun toString() = value
}

/** a ranged list of [Cmd]. we should use a [BlockCmd] here instead, but that's a pretty involved change, so for now we don't. */
data class CmdList(override val range: Range.Range, val cmds: List<Cmd>) : Kotlinizable<List<CVLCmd>> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<List<CVLCmd>, CVLError> = this.cmds.kotlinize(resolver, scope)
}

data class MethodParamFilterDef(override val range: Range, internal val methodParam: VariableExp, internal val filterExp: Exp) : Kotlinizable<MethodParamFilter> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodParamFilter, CVLError> = collectingErrors {
        val _methodParam = methodParam.kotlinize(resolver, scope)
        val _filterExp   = filterExp.kotlinize(resolver, scope)
        map(_methodParam, _filterExp) { methodParam, filterExp -> MethodParamFilter(methodParam, filterExp, range, scope) }
    }
}


data class MethodParamFiltersMap(override val range: Range, internal val methodParamFilters: Map<String, MethodParamFilterDef>?) : Kotlinizable<MethodParamFilters> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodParamFilters, CVLError> = collectingErrors {
        if (methodParamFilters == null) { return@collectingErrors noFilters(Range.Empty(), scope) }

        val mpf = collectAndFilter(methodParamFilters.mapValues { (_, filter) -> filter.kotlinize(resolver, scope) })
        MethodParamFilters(range, scope, mpf)
    }

    companion object {
        @JvmField val NO_METHOD_PARAM_FILTERS: MethodParamFiltersMap = MethodParamFiltersMap(Range.Empty(), null)
    }
}


/**
 * @param contract the contents of the receiver, if any.  Null otherwise
 * @param method   the name of the method
 * @param range the location of the method reference
 */
data class MethodReferenceExp(@JvmField val contract: VariableExp?, @JvmField val method: String, override val range: Range) : HasRange {
    override fun toString() = "${contract?.id?.let { "$it." }.orEmpty()}$method"

    fun baseContract() = contract?.id

    fun kotlinizeTarget(resolver: TypeResolver): MethodEntryTargetContract {
        return when(val base = baseContract()) {
            null -> MethodEntryTargetContract.SpecificTarget(resolver.resolveNameToContract(SolidityContract.Current.name))
            CVLKeywords.wildCardExp.keyword -> MethodEntryTargetContract.WildcardTarget
            else -> MethodEntryTargetContract.SpecificTarget(resolver.resolveNameToContract(base))
        }
    }
}

data class MethodSig @JvmOverloads constructor(
    override val range: Range,
    @JvmField val id: MethodReferenceExp,
    @JvmField val params: List<VMParam>,
    val resParams: List<VMParam>,
    @JvmField val methodQualifiers: MethodQualifiers? = null
) : Kotlinizable<QualifiedMethodParameterSignature> {
    override fun toString() = "$id${params.joinToString(prefix = "(", postfix = ")")}"

    private fun generateSig(name: ContractFunctionIdentifier, resolver: TypeResolver, scope: CVLScope): CollectingResult<QualifiedMethodParameterSignature, CVLError> = collectingErrors {
        val _params =    params.map  { it.kotlinize(resolver, scope) }.flatten()
        val _return = resParams.map { it.kotlinize(resolver, scope) }.flatten()
        map(_params, _return) { params, returnParams ->
            if (!returnParams.isEmpty()) {
                QualifiedMethodSignature(name, params, returnParams)
            } else {
                QualifiedMethodParameterSignature(name, params)
            }
        }
    }

    fun kotlinizeNamed(target: MethodEntryTargetContract, resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodParameterSignature, CVLError> = collectingErrors {
        val _params    =     params.map { it.kotlinize(resolver, scope) }.flatten()
        val _resParams = resParams.map { it.kotlinize(resolver, scope) }.flatten()

        val (params, resParams) = map(_params, _resParams) { params, resParams -> params to resParams }

        when (target) {
            is MethodEntryTargetContract.WildcardTarget ->
                if (!resParams.isEmpty()) {
                    MethodSignature.invoke(id.method, params, resParams)
                } else {
                    MethodParameterSignature.invoke(id.method, params)
                }

            is MethodEntryTargetContract.SpecificTarget ->
                QualifiedMethodSignature.invoke(QualifiedFunction(target.contract, id.method), params, resParams)
        }
    }

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<QualifiedMethodParameterSignature, CVLError>
        = generateSig(QualifiedFunction(getCalleeContract(resolver), id.method), resolver, scope)

    fun kotlinizeExternal(res: TypeResolver, scope: CVLScope): CollectingResult<ExternalQualifiedMethodParameterSignature, CVLError> {
        return kotlinize(res, scope).map {
            ExternalQualifiedMethodParameterSignature.fromNamedParameterSignatureContractId(it, PrintingContext(false))
        }
    }

    fun baseContract() = id.baseContract()

    private fun getCalleeContract(res: TypeResolver): SolidityContract
        = SolidityContract(res.resolveContractName(id.contract?.id ?: SolidityContract.Current.name))

    fun kotlinizeTarget(resolver: TypeResolver): MethodEntryTargetContract = id.kotlinizeTarget(resolver)
}

fun checkIdValidity(id: String, location: Range, construct: String): CollectingResult<String, CVLError> =
    if (TACIdentifiers.valid(id)) {
        id.lift()
    } else {
        InvalidIdentifier(location, id, construct).asError()
    }
