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

import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors
import datastructures.stdcollections.*
import utils.Range

// This file contains the "Java" AST nodes for ghosts and their axioms.  See "README.md" for information about the Java AST.

sealed class GhostDecl : Kotlinizable<CVLGhostDeclaration> {
    abstract fun withAxioms(axioms: List<GhostAxiom>): GhostDecl
}


class GhostFunDecl @JvmOverloads constructor(
    val range: Range,
    val id: String,
    val paramTypes: List<TypeOrLhs>,
    val returnType: TypeOrLhs,
    val persistent: Boolean,
    val axioms: List<GhostAxiom> = emptyList()
) : GhostDecl() {
    override fun withAxioms(axioms: List<GhostAxiom>): GhostFunDecl
        = GhostFunDecl(range, id, paramTypes, returnType, persistent, this.axioms + axioms)

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLGhostDeclaration.Function, CVLError> = collectingErrors {
        val _paramTypes = paramTypes.map { it.toCVLType(resolver, scope) }.flatten()
        val _returnType = returnType.toCVLType(resolver, scope)
        val _axioms     = axioms.map { it.kotlinize(resolver, scope) }.flatten()
        map(_paramTypes, _returnType, _axioms) { paramTypes, returnType, axioms ->
            CVLGhostDeclaration.Function(range, id, paramTypes, returnType, persistent, axioms, scope, false)
        }
    }
}

/**
 * Note that constants are treated as a special case, they can be declared in (degenerate) map style, e.g.,
 * `ghost uint i;`
 * or in function style, e.g.,
 * `ghost i() returns uint;`
 * .
 */
class GhostMapDecl @JvmOverloads constructor(
    val range: Range,
    val type: TypeOrLhs,
    val id: String,
    val persistent: Boolean,
    val axioms: List<GhostAxiom> = emptyList()
) : GhostDecl() {
    override fun withAxioms(axioms: List<GhostAxiom>): GhostMapDecl
        = GhostMapDecl(range, type, id, persistent, this.axioms + axioms)

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLGhostDeclaration.Variable, CVLError> = collectingErrors {
        val _type = type.toCVLType(resolver, scope)
        val _axioms = axioms.map { it.kotlinize(resolver, scope) }.flatten()
        map(_type, _axioms) { type, axioms -> CVLGhostDeclaration.Variable(range, type, id, persistent, axioms, scope, oldCopy = false) }
    }
}

class GhostAxiom(val exp: Exp, val type: Type) : Kotlinizable<CVLGhostAxiom> {
    enum class Type { INITIAL, ALWAYS }

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLGhostAxiom, CVLError> {
        return exp.kotlinize(resolver, scope).map { exp: CVLExp ->
            when (type) {
                Type.INITIAL -> CVLGhostAxiom.Initial(exp)
                Type.ALWAYS -> CVLGhostAxiom.Always(exp)
            }
        }
    }
}
