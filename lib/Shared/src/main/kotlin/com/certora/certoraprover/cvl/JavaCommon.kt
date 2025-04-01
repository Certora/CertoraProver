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
import spec.cvlast.typedescriptors.PrintingContext
import utils.CollectingResult
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors
import utils.Range

// This file contains the "Java" AST nodes that are used in many places, such as parameters and method signatures.
// See README.md for a description of the Java AST.

interface Kotlinizable<T> {
    fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<T, CVLError>
}

fun <T> List<Kotlinizable<T>>.kotlinize(resolver : TypeResolver, scope : CVLScope) : CollectingResult<List<T>, CVLError>
    = this.map { it.kotlinize(resolver, scope) }.flatten()

/**
 * The location associated with the Param (e.g. "memory" or "calldata"), or `null` if no location is specified.
 * NOTE: we currently only allow you to write one location specifier per parameter; we don't support something like
 * `function foo((C.S calldata)[] memory)`.  If we do, we would need to move the `location` to the type.  My
 * understanding is that this is not currently possible in Solidity but that the Solidity team is actively working
 * on supporting it.
 */
sealed class VMParam(val type: TypeOrLhs, val location: String?, val range: Range) : Kotlinizable<spec.cvlast.VMParam>

class UnnamedVMParam(type: TypeOrLhs, loc: String?, range: Range) : VMParam(type, loc, range) {
    override fun toString() = "UnnamedVMParam($type)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<Unnamed, CVLError>
        = type.toVMType(resolver, location, scope).map { Unnamed(it, range) }
}


class NamedVMParam(type: TypeOrLhs, loc: String?, val id: String, range: Range) : VMParam(type, loc, range) {
    override fun toString() = "NamedVMParam($type,$id)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.VMParam.Named, CVLError>
        = type.toVMType(resolver, location, scope).map { spec.cvlast.VMParam.Named(id, it, range, id) }
}


class CVLParam(val type: TypeOrLhs, val id: String, val range: Range) : Kotlinizable<spec.cvlast.CVLParam> {
    override fun toString() = "CVLParam($type,$id)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.CVLParam, CVLError>
        = type.toCVLType(resolver, scope).map { CVLParam(it, id, range) }
}

class LocatedToken internal constructor(val range: Range, val value: String) {
    override fun toString() = value
}

class MethodParamFilterDef(private val range: Range, private val methodParam: VariableExp, private val filterExp: Exp) : Kotlinizable<MethodParamFilter> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodParamFilter, CVLError> = collectingErrors {
        val _methodParam = methodParam.kotlinize(resolver, scope)
        val _filterExp   = filterExp.kotlinize(resolver, scope)
        map(_methodParam, _filterExp) { methodParam, filterExp -> MethodParamFilter(methodParam, filterExp, range, scope) }
    }
}


class MethodParamFiltersMap(private val range: Range, private val methodParamFilters: Map<String, MethodParamFilterDef>?) : Kotlinizable<MethodParamFilters> {
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
class MethodReferenceExp(@JvmField var contract: VariableExp?, val method: String, val range: Range) {
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

class MethodSig @JvmOverloads constructor(
    val range: Range,
    val id: MethodReferenceExp,
    val params: List<VMParam>,
    val resParams: List<VMParam>?, // null for no returns specified
    @JvmField val methodQualifiers: MethodQualifiers? = null
) : Kotlinizable<QualifiedMethodParameterSignature> {

    override fun toString() = "$id${params.joinToString(prefix = "(", postfix = ")")}"

    private fun generateSig(name: ContractFunctionIdentifier, resolver: TypeResolver, scope: CVLScope): CollectingResult<QualifiedMethodParameterSignature, CVLError> = collectingErrors {
        val _params =    params.map  { it.kotlinize(resolver, scope) }.flatten()
        val _return = resParams?.map { it.kotlinize(resolver, scope) }?.flatten() ?: null.lift()
        map(_params, _return) { params, returnParams ->
            returnParams?.let { QualifiedMethodSignature.invoke(name, params, returnParams) }
                ?: QualifiedMethodParameterSignature.invoke(name, params)
        }
    }

    fun kotlinizeNamed(target: MethodEntryTargetContract, resolver: TypeResolver, scope: CVLScope): CollectingResult<MethodParameterSignature, CVLError> = collectingErrors {
        val _params    =     params.map { it.kotlinize(resolver, scope) }.flatten()
        val _resParams = resParams?.map { it.kotlinize(resolver, scope) }?.flatten() ?: null.lift()

        val (params, resParams) = map(_params, _resParams) { params, resParams -> params to resParams }

        when (target) {
            is MethodEntryTargetContract.WildcardTarget ->
                resParams?.let { MethodSignature.invoke(id.method, params, resParams) }
                    ?: MethodParameterSignature.invoke(id.method, params)

            is MethodEntryTargetContract.SpecificTarget ->
                QualifiedMethodSignature.invoke(QualifiedFunction(target.contract, id.method), params, resParams.orEmpty())
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
