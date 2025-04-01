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

import cli.InvariantType
import config.Config
import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.CVLPreserved.ExplicitMethod
import spec.cvlast.CVLScope.Item.InvariantScopeItem
import spec.cvlast.CVLScope.Item.PreserveScopeItem
import spec.cvlast.typechecker.CVLError
import utils.*
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors

// This file contains the "Java" AST nodes for invariants and their components.  See README.md for information about the Java AST.

class Invariant(
    val isImportedSpecFile: Boolean,
    val range: Range,
    val id: String,
    val params: List<CVLParam>,
    val exp: Exp,
    val mpf: MethodParamFiltersMap,
    val proof: InvariantProof,
    val invariantType: String
) : Kotlinizable<CVLInvariant> {
    override fun toString() = "Invariant($id,${params.joinToString()},$exp,$invariantType)"
    private fun getInvariantType(invariantType: String): spec.cvlast.InvariantType{
        return when(invariantType){
            InvariantType.WEAK.msg -> WeakInvariantType
            InvariantType.STRONG.msg -> StrongInvariantType
            else -> {
                if(Config.defaultInvariantType.get() == InvariantType.STRONG) {
                    StrongInvariantType
                } else {
                    WeakInvariantType
                }
            }
        }
    }
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLInvariant, CVLError> {
        return scope.extendInCollecting(::InvariantScopeItem) { iScope: CVLScope -> collectingErrors {
            val _params = params.map { it.kotlinize(resolver, iScope) }.flatten()
            val _exp    = exp.kotlinize(resolver, iScope)
            val _mpf    = mpf.kotlinize(resolver, iScope)
            val _proof  = proof.kotlinize(resolver, iScope)
            val source  = if (isImportedSpecFile) { SpecType.Single.FromUser.ImportedSpecFile } else { SpecType.Single.FromUser.SpecFile }
            val invariantType = getInvariantType(invariantType)

            map(_params, _exp, _mpf, _proof) { params, exp, mpf, proof ->
                CVLInvariant(range, id, source, params, exp, mpf, proof, iScope, !isImportedSpecFile, RuleIdentifier.freshIdentifier(id), invariantType)
            }
        }}
    }

}

class InvariantProof(val preserved: List<Preserved>) : Kotlinizable<CVLInvariantProof> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLInvariantProof, CVLError>
        = preserved.map { it.kotlinize(resolver, scope) }.flatten().map { CVLInvariantProof(it) }
}

sealed class Preserved(val range: Range, val withParams: List<CVLParam>, val block: List<Cmd>) : Kotlinizable<CVLPreserved> {
    /** @return the kotlinization of `this` after [block] and [withParams] have been kotlinized */
    abstract fun create(resolver: TypeResolver, scope: CVLScope, block : List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>) : CollectingResult<CVLPreserved, CVLError>

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLPreserved, CVLError> {
        return scope.extendInCollecting(::PreserveScopeItem) { subScope -> collectingErrors {
            val _block      = block.map { it.kotlinize(resolver, subScope) }.flatten()
            val _withParams = withParams.map { it.kotlinize(resolver, subScope) }.flatten()
            bind(_block, _withParams) { block, withParams ->
                create(resolver, subScope, block, withParams)
            }
        }}
    }
}

class ExplicitMethodPreserved(
    range: Range,
    val methodSig: MethodSig,
    withParams: List<CVLParam>?,
    block: List<Cmd>
) : Preserved(range, withParams.orEmpty(), block) {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>)
        = methodSig.kotlinizeExternal(resolver, scope).map { methodSig ->
            ExplicitMethod(range, methodSig, block, withParams, scope)
        }
}

class FallbackPreserved(range: Range, withParams: List<CVLParam>?, block: List<Cmd>) : Preserved(range, withParams.orEmpty(), block) {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>)
        = CVLPreserved.Fallback(range, block, withParams, scope).lift()
}


class TransactionBoundaryPreserved(range: Range, withParams: List<CVLParam>?, block: List<Cmd>) : Preserved(range, withParams.orEmpty(), block) {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>)
        = CVLPreserved.TransactionBoundary(range, block, withParams, scope).lift()
}

class GenericPreserved(range: Range, withParams: List<CVLParam>?, block: List<Cmd>) : Preserved(range, withParams.orEmpty(), block) {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>)
        = CVLPreserved.Generic(range, block, withParams, scope).lift()
}
