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

package spec

import com.certora.collect.*
import datastructures.stdcollections.*
import spec.cvlast.*
import tac.MetaMap
import tac.Tag
import utils.*
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.TransformableVarEntity

/**
 * A [TACSymbolAllocation] is where we manage what names CVL variables are given when they are compiled into TAC
 * variables. Previously we had simply yeeted CVL names into TAC Names (which invariably resulted in name collisions
 * and led to an accumulation of renaming/substitution schemes which are really difficult to tease apart).
 *
 * NB the following assumptions about a CVL Rule/Specification (which should be checked by the CVL Type Checker):
 * 1. There is no name shadowing. (This is why we check at runtime that we do not allocate an identifier twice)
 *      With the major exception that a quantified formula may shadow variable names (hence the [shadow()] method.
 *
 * @property currentScope All variables allocated for variables in the current local scope, these get removed when
 *                          entering a new scope
 * @property globalScope All variables allocated for variables in the current global scope, these do *not* get removed
 *                          when entering a new scope
 *
 * This is serializable because a [vc.data.CompiledExpressionSummary] carries around a [TACSymbolAllocation] with it and
 * `CompiledExpressionSummary`s live inside of `CoreTACProgram`s
 */
@Treapable
class TACSymbolAllocation private constructor(
    _backing: Map<String, TACSymbol.Var> = mapOf(),
    _globals: Map<String, TACSymbol.Var> = mapOf()
): java.io.Serializable, TransformableVarEntity<TACSymbolAllocation> {
    private val currentScope: MutableMap<String, TACSymbol.Var> = _backing.toMutableMap()
    private val globalScope: MutableMap<String, TACSymbol.Var> = _globals.toMutableMap()

    constructor(): this(mapOf(), mapOf())

    init {
        CVLKeywords.values().forEach { keyword ->
            val type = keyword.type
            if (type is CVLType.PureCVLType.Primitive || type is CVLType.PureCVLType.VMInternal.BlockchainState || type is CVLType.PureCVLType.VMInternal.BlockchainStateMap) {
                val theVariable = keyword.toVar()
                if (currentScope.containsKey(keyword.keyword)) {
                    check(theVariable == get(keyword.keyword)) {
                        "Somehow ${keyword.keyword} is already in the scope but with a different variable"
                    }
                } else {
                    extend(keyword.keyword, theVariable)
                }
            }
        }
    }

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): TACSymbolAllocation = TACSymbolAllocation().also {
        fun sortedScope(scope: Map<String, TACSymbol.Var>) =
            scope.entries.asSequence().map { (k, v) -> k to f(v) }.sortedBy { (_, v) -> v }
        it.currentScope.putAll(sortedScope(currentScope))
        it.globalScope.putAll(sortedScope(currentScope))
    }

    override fun hashCode() = hash { it + currentScope + globalScope }

    /**
     * A callee (CVLFunction) exists in the same spec, so it should have all identifiers in the [globalScope]
     * A callee [CVLFunction] is part of the same rule as the caller, so it should have all `ruleScopeMethods`
     * A callee [CVLFunction] has a completely unrelated local scope from the caller, so [currentScope] should be empty
     *
     * @return a new [TACSymbolAllocation] with an empty ~`currentScope` and copies of the `ruleScopeMethods` and
     * [globalScope] of this~
     *
     */
    fun calleeScope(): TACSymbolAllocation = TACSymbolAllocation(_globals = globalScope)

    /**
     * Used for entering a "nested" scope where we want to inherit all identifiers but not pollute the enclosing scope.
     */
    fun nestedScope(): TACSymbolAllocation = TACSymbolAllocation(currentScope, globalScope)

    fun extend(name: String, tag: Tag): TACSymbol.Var = extend(name, TACSymbol.Var(name, tag).toUnique())
    fun extend(name: String, symbol: TACSymbol.Var): TACSymbol.Var = extend(name, symbol, currentScope)

    fun extendGlobal(name: String, tag: Tag): TACSymbol.Var = extendGlobal(name, TACSymbol.Var(name, tag).toUnique())
    fun extendGlobal(name: String, variable: TACSymbol.Var): TACSymbol.Var {
        if (isAllocated(name)) {
            return variable
        }
        return extend(name, variable.withMeta(TACMeta.NO_CALLINDEX), globalScope)
    }

    /**
     * Do not call this method unless you know what you are doing. In general name shadowing is not allowed
     * with the exception of quantified formulas.
     *
     * For example, the following is a syntax error:
     * uint x;
     * bool b;
     * if (b) {
     *  uint x;
     *  ...
     * }
     *
     * However you may have the following:
     * uint x;
     * forall uint x. assert x >= 0;
     *
     * This is used to bind a new tac symbol to an identifier which has been shadowed (by a forall or exists)
     */
    fun shadow(qExp: CVLExp.QuantifierExp): TACSymbolAllocation {
        val symbol = TACSymbol.Var(qExp.qVarName, qExp.qVarType.toTag()).toUnique()
        val ret = this.nestedScope()
        ret.currentScope[qExp.qVarName] = symbol
        return ret
    }

    fun extendWithCVLVariable(
            name: String,
            cvlType: CVLType.PureCVLType,
            cvlDefSite: CVLDefinitionSite?,
    ) = extend(
            name,
            getAsCVLVariable(
                TACSymbol.Var(name, cvlType.toTag()).toUnique(),
                cvlDefSite,
                name,
                cvlType,
            )
    )


    private fun extend(name: String, symbol: TACSymbol.Var, mapping: MutableMap<String, TACSymbol.Var>): TACSymbol.Var {
        if (!name.isWildcard()) {
            check(getOrNull(name) == null) { "Tried to allocate a TAC Variable for the identifier $name twice." }
            mapping[name] = symbol
            return symbol
        }
        return symbol
    }

    fun isAllocated(name: String) = getOrNull(name) != null

    fun getGlobalScope(): Map<String, TACSymbol.Var> = globalScope

    private fun getOrNull(name: String): TACSymbol.Var? = currentScope[name] ?: globalScope[name]

    fun get(name: String, tag: Tag? = null): TACSymbol.Var =
        if (name.isWildcard()) {
            check(tag != null) {
                "For wildcards the tag is mandatory - there isn't enough information to infer it at this point"
            }
            TACSymbol.Var("wildcard", tag).toUnique()
        } else {
            getOrNull(name)?.also { ret ->
                tag?.let { tag ->
                    check(tag == ret.tag) {
                        "when getting allocated tac variable for symbol $name, expected tag $tag but got a variable with tag ${ret.tag}"
                    }
                }
            } ?:
            throw CertoraInternalException(
                CertoraInternalErrorType.CVL_TAC_ALLOCATION,
                "Variable $name has not been allocated a TAC Variable"
            )
        }

    /**
     * generates a unique [CVLParam] derived from [id] and [type],
     * and a corresponding [TACSymbol.Var] with meta from [metaMap].
     * the new symbol is added to [currentScope].
     */
    fun generateTransientUniqueCVLParam(
        symbolTable: CVLSymbolTable,
        id: String,
        type: CVLType.PureCVLType,
        metaMap: MetaMap = MetaMap(),
    ): Pair<CVLParam, TACSymbol.Var> {
        val param = CVLParam(type, id, Range.Empty())
        return generateTransientUniqueCVLParam(symbolTable, param, metaMap)
    }

    /**
     * generates a unique [CVLParam] derived from [param],
     * and a corresponding [TACSymbol.Var] with meta from [metaMap].
     * the new symbol is added to [currentScope].
     */
    fun generateTransientUniqueCVLParam(
        symbolTable: CVLSymbolTable,
        param: CVLParam,
        metaMap: MetaMap = MetaMap(),
    ): Pair<CVLParam, TACSymbol.Var> {
        val freshParam = symbolTable.freshParam(param)
        val symbol = TACSymbol
            .Var(freshParam.id, freshParam.type.toTag())
            .toUnique()
            .withMeta(metaMap)
        return freshParam to this.extend(freshParam.id, symbol, currentScope)
    }
}
