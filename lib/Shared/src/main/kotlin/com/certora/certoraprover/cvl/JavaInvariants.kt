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

import config.Config
import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.CVLPreserved.ExplicitMethod
import spec.cvlast.CVLScope.Item.InvariantScopeItem
import spec.cvlast.CVLScope.Item.PreserveScopeItem
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.ConstructorPreservedSignatureMismatch
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors

// This file contains the "Java" AST nodes for invariants and their components.  See README.md for information about the Java AST.

data class Invariant(
    val isImportedSpecFile: Boolean,
    override val range: Range.Range,
    val id: String,
    val params: List<CVLParam>,
    val exp: Exp,
    val mpf: MethodParamFiltersMap,
    val proof: InvariantProof,
    val declaredInvariantType: InvariantType?,
) : TopLevel<CVLInvariant> {
    override fun toString(): String {
        val invariantType = when (this.declaredInvariantType) {
            StrongInvariantType -> "strong"
            WeakInvariantType -> "weak"
            null -> "default"
        }
        return "Invariant($id,${params.joinToString()},$exp,$invariantType)"
    }

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLInvariant, CVLError> {
        return scope.extendInCollecting(::InvariantScopeItem) { iScope: CVLScope -> collectingErrors {
            val _params = params.map { it.kotlinize(resolver, iScope) }.flatten()
            val _exp    = exp.kotlinize(resolver, iScope)
            val _mpf    = mpf.kotlinize(resolver, iScope)
            val _proof  = proof.kotlinize(resolver, iScope)
            val source  = if (isImportedSpecFile) { SpecType.Single.FromUser.ImportedSpecFile } else { SpecType.Single.FromUser.SpecFile }
            val invariantType = declaredInvariantType ?: Config.DefaultInvariantType.get().kotlinize()

            map(_params, _exp, _mpf, _proof) { params, exp, mpf, proof ->
                CVLInvariant(range, id, source, params, exp, mpf, proof, iScope, !isImportedSpecFile, RuleIdentifier.freshIdentifier(id), invariantType)
            }
        }}
    }
}

fun cli.InvariantType.kotlinize() = when (this) {
    cli.InvariantType.WEAK -> WeakInvariantType
    cli.InvariantType.STRONG -> StrongInvariantType
}

data class InvariantProof(val preserved: List<Preserved>, override val range: Range) : Kotlinizable<CVLInvariantProof> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLInvariantProof, CVLError>
        = preserved.map { it.kotlinize(resolver, scope) }.flatten().map { CVLInvariantProof(it) }
}

sealed interface Preserved : Kotlinizable<CVLPreserved> {
    override val range: Range
    val withParams: List<CVLParam>?
    val block: CmdList

    /** @return the kotlinization of `this` after [block] and [withParams] have been kotlinized */
    fun create(resolver: TypeResolver, scope: CVLScope, block : List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>) : CollectingResult<CVLPreserved, CVLError>

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLPreserved, CVLError> {
        return scope.extendInCollecting(::PreserveScopeItem) { subScope -> collectingErrors {
            val _block      = block.kotlinize(resolver, subScope)
            val _withParams = withParams.orEmpty().map { it.kotlinize(resolver, subScope) }.flatten()
            bind(_block, _withParams) { block, withParams ->
                create(resolver, subScope, block, withParams)
            }
        }}
    }
}

data class ExplicitMethodPreserved(
    override val range: Range,
    val methodSig: MethodSig,
    override val withParams: List<CVLParam>?,
    override val block: CmdList,
) : Preserved {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>)
        = methodSig.kotlinizeExternal(resolver, scope).map { methodSig ->
            ExplicitMethod(range, methodSig, block, withParams, scope)
        }
}

data class FallbackPreserved(override val range: Range, override val withParams: List<CVLParam>?, override val block: CmdList) : Preserved {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>)
        = CVLPreserved.Fallback(range, block, withParams, scope).lift()
}


data class TransactionBoundaryPreserved(override val range: Range, override val withParams: List<CVLParam>?, override val block: CmdList) : Preserved {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>)
        = CVLPreserved.TransactionBoundary(range, block, withParams, scope).lift()
}

data class GenericPreserved(override val range: Range, override val withParams: List<CVLParam>?,override val  block: CmdList) : Preserved {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>)
        = CVLPreserved.Generic(range, block, withParams, scope).lift()
}

data class ConstructorPreserved(override val range: Range, val params: List<VMParam>, override val withParams: List<CVLParam>?, override val block: CmdList) : Preserved {
    override fun create(resolver: TypeResolver, scope: CVLScope, block: List<CVLCmd>, withParams: List<spec.cvlast.CVLParam>): CollectingResult<CVLPreserved, CVLError> =
        if (params.isNotEmpty()) {
            params.kotlinize(resolver, scope).bind { params ->
                ConstructorPreservedSignatureMismatch(
                    params.joinToString(",", "(", ")") { it.prettyPrint() },
                    range
                ).asError()
            }
        } else {
            CVLPreserved.Constructor(range, block, withParams, scope).lift()
        }


}
