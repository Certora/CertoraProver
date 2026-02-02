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

package vc.data

import analysis.CommandWithRequiredDecls
import com.certora.collect.*
import datastructures.stdcollections.*
import log.*
import smt.solverscript.functionsymbols.IFixedFunctionSignatures.FixedFunctionSignatures
import smt.solverscript.functionsymbols.UserDefinedFunctionSymbol
import tac.Tag
import utils.*
import vc.data.tacexprutil.subs
import vc.data.tacexprutil.toVarSet
import java.io.Serializable

// function name => (number of parameters => function)
typealias UninterpretedFunctions = Map<String, Map<Int, FunctionInScope.UF>>

// function name => function
typealias BuiltinFunctions = Map<String, FunctionInScope.Builtin>

private val logger = Logger(LoggerTypes.COMMON)

class TACSymbolTableException(msg: String) : Exception("Invalid TACSymbolTable: $msg")

/**
 * @author Thomas Bernardi
 * @throws TACSymbolTableException when given ambiguous uninterpreted functions to the secondary constructor
 * @see Tag
 *
 * A TACSymbolTable represents a scope of Sorts (or Tags) and Uninterpreted Functions. By default, all TAC primitives are
 * in this scope as defined in Tag. Uninterpreted Functions are considered "ambiguous" when they have the same name
 * and the same number of parameters. It is an @invariant that a TACSymbolTable never contains ambiguous Uninterpreted
 * Functions. It is also an invariant that all tags found within Uninterpreted Functions are in scope.
 *
 * We consider TAC programs where the symbol object that is being tagged is [TACSymbol.Var].
 *
 * */
@Treapable
data class TACSymbolTable(
        internal val userDefinedTypes: Set<Tag.UserDefined>,
        private val uninterpretedFunctions: UninterpretedFunctions,
        val vars: TreapSet<TACSymbol.Var>,
        val globalScope: Map<String, TACSymbol.Var>
) : Serializable {

    constructor(symbols: Set<TACSymbol>, userDefinedTypes: Set<Tag.UserDefined> = emptySet()) :
        this(
            userDefinedTypes,
            mapOf(),
            symbols.filterIsInstance<TACSymbol.Var>()
                .toTreapSet()
                .also { vars ->
                    vars.groupBy { it.callIndex to it.namePrefix }
                        .forEachEntry { (_, equivalentVars) ->
                            check(equivalentVars.allSame { it.tag }) {
                                "Equivalent variables $equivalentVars should have the same tag in TACSymbolTable"
                            }
                        }
                },
            mapOf()
        )

    constructor(vararg vars : TACSymbol) : this(vars.toSet())

    constructor(e : TACExpr) :
        this(e.subs.toVarSet(), e.subs.mapNotNull { it.tag as? Tag.UserDefined }.toSet())

    fun isEmpty() =
        userDefinedTypes.isEmpty() && uninterpretedFunctions.isEmpty() && vars.isEmpty()

    constructor(
        userDefinedTypes: Set<Tag.UserDefined>,
        uninterpretedFunctions: Set<FunctionInScope.UF>,
        vars: TreapSet<TACSymbol.Var>,
        globalScope: Map<String, TACSymbol.Var>
    ) :
            this(userDefinedTypes,
                uninterpretedFunctions
                    .fold(Pair<UninterpretedFunctions, List<String>>(mapOf(), listOf()))
                    { (map, errors), func ->
                        if (map[func.name]?.get(func.paramSorts.size) != null) {
                            val error = "The declared function $func is ambiguous with the previously declared " +
                                    "function ${map[func.name]!![func.paramSorts.size]!!}"
                            Pair(map, errors.plus(error))
                        }
                        val functionNameEntry = map[func.name] ?: mapOf()
                        val newFunctionNameEntry = functionNameEntry.plus(func.paramSorts.size to func)
                        Pair(map.plus(func.name to newFunctionNameEntry), errors)
                    }.let { (map, errors) ->
                        if (errors.isNotEmpty()) {
                            errors.forEach { logger.error(it) }
                            throw TACSymbolTableException(
                                "Provided uninterpreted function declarations must be unique by name" +
                                        "and number of parameters"
                            )
                        } else {
                            map
                        }
                    },
                vars,
                globalScope
            )

    init {
        /*
            Every time we construct a new symbol table, check that we don't have any variables with the same name, but
            different tags.
         */
        vars.groupBy { it.smtRep }.forEachEntry { (_, equivalentVars) ->
            check(equivalentVars.size == 1) {
                "Multiple variables with same name: ${equivalentVars.joinToString(", ")}"
            }
        }

        /*
            Check for undefined tags in Uninterpreted Function declarations
         */
        val errors = mutableListOf<String>()

        val undefinedParamTags = uninterpretedFunctions().mapNotNull { func ->
            val badParams = func.paramSorts.filter { tag -> !contains(tag) }
            if (badParams.isEmpty()) {
                null
            } else {
                Pair(func, badParams)
            }
        }

        if (undefinedParamTags.isNotEmpty()) {
            undefinedParamTags.forEach { (func, badParams) ->
                errors += "The parameter sorts ${badParams.joinToString(", ")} of the declared function " +
                        "$func are not defined in the scope of this program"
            }
        }

        val undefinedReturnTags = uninterpretedFunctions().filter { func ->
            !contains(func.returnSort)
        }

        if (undefinedReturnTags.isNotEmpty()) {
            undefinedReturnTags.forEach { func ->
                errors += "The return sort ${func.returnSort} of the declared function $func is not defined " +
                        "in the scope of this program"
            }
        }

        if (undefinedParamTags.isNotEmpty() || undefinedReturnTags.isNotEmpty()) {
            errors.forEach { logger.error(it) }
            throw TACSymbolTableException("undefined tags in Uninterpreted Function declarations")
        }
    }

    companion object {
        fun empty() = TACSymbolTable()
        fun withVars(vars: Set<TACSymbol.Var>) = TACSymbolTable(setOf(), setOf(), vars.toTreapSet(), mapOf())
        fun <T: TACCmd> withRequiredDecls(cmdsWithReqDecls: CommandWithRequiredDecls<T>) =
            TACSymbolTable(setOf(), setOf(), cmdsWithReqDecls.varDecls.toTreapSet(), mapOf())

        private fun <K, V> Map<K, V>.merge(other: Map<K, V>, error: (K, V, V) -> Unit): Map<K, V> {
            val newMap = mutableMapOf<K, V>()
            this.forEachEntry { (k, v) -> newMap[k] = v }
            other.forEachEntry { (k, v) ->
                if (newMap[k] == null || v == newMap[k]) {
                    newMap.putIfAbsent(k, v)
                } else {
                    error(k, v, newMap[k]!!)
                }
            }
            return newMap
        }
        fun mergeTypes(types: Map<String, Tag.UserDefined>, otherTypes: Map<String, Tag.UserDefined>): Map<String, Tag.UserDefined> =
            types.merge(otherTypes) { name, v1, v2 ->
                throw TACSymbolTableException(
                    "There was a collision while merging user defined types." +
                            "Type name $name registered both as $v1 and $v2."
                )
            }
    }

    operator fun contains(sort: Tag): Boolean = when (sort) {
        is Tag.UserDefined -> sort in userDefinedTypes
        is Tag.GhostMap -> sort.paramSorts.all { tag -> contains(tag) } && contains(sort.resultSort) // ¯\_(ツ)_/¯
        is Tag.CVLArray -> contains(sort.elementTag)
        is Tag.Bits, Tag.Bool, Tag.Int, Tag.WordMap, Tag.ByteMap, Tag.BlockchainState, is Tag.Move -> true
    }

    operator fun contains(v: TACSymbol.Var): Boolean = v in vars

    val nameToSymbol: Map<String, TACSymbol.Var> by lazy { vars.associateBy { it.toString() } }

    fun containsUninterpretedFunction(name: String, numParams: Int) =
        uninterpretedFunctions[name]?.containsKey(numParams) ?: false

    fun getUninterpretedFunction(name: String, numParams: Int): FunctionInScope.UF? =
        uninterpretedFunctions[name]?.get(numParams)

    fun getUninterpretedFunctions(name: String) = uninterpretedFunctions[name]?.values?.toSet() ?: setOf()

    fun uninterpretedFunctions(): Set<FunctionInScope.UF> = uninterpretedFunctions.values.flatMap { it.values }.toSet()

    /**
     * All ghosts are implemented as uninterpreted functions, even if in CVL they are variables or mappings.
     * (e.g. variables are just nullary uninterpreted functions).
     */
    fun getStateGhosts() = uninterpretedFunctions().filter { it.attribute == UFAttribute.Ghost && !it.persistent }

    /**
     * Similar to [getStateGhosts], but gets a list of the persistent ghosts.
     */
    @Suppress("unused")
    fun getPersistentGhosts() = uninterpretedFunctions().filter { it.attribute == UFAttribute.Ghost && it.persistent }

    fun types(): Set<Tag.UserDefined> = userDefinedTypes

    fun merge(other: TACSymbolTable) = if (this === other) this else {

        val newSorts = this.userDefinedTypes.union(other.userDefinedTypes)
        val newUninterpretedFunctions = this.uninterpretedFunctions + other.uninterpretedFunctions
        val newVars = this.vars + other.vars
        val newGlobalScope = this.globalScope + other.globalScope

        // Either the global scope of one of the symbol table is contained in the other one, or they are completely separate.
        check(this.globalScope.entries.containsAll(other.globalScope.entries) ||
            other.globalScope.entries.containsAll(this.globalScope.entries) ||
            newGlobalScope.size == this.globalScope.size + other.globalScope.size) { // they are exclusive
            "Merging to symbol tables that have contradicting global scopes"
        }
        this.copy(userDefinedTypes = newSorts, uninterpretedFunctions = newUninterpretedFunctions, vars = newVars, globalScope = newGlobalScope)
    }

    fun mergeUfs(ufs: Set<FunctionInScope.UF>): TACSymbolTable {
        return TACSymbolTable(
            userDefinedTypes = userDefinedTypes,
            uninterpretedFunctions = uninterpretedFunctions() + ufs,
            vars = vars,
            globalScope = globalScope
        )
    }

    fun mergeDecls(decls: Set<TACSymbol.Var>): TACSymbolTable {
        return this.copy(vars = this.vars + decls)
    }

    private val uninterpretedFunctionSymbols: MutableMap<FunctionInScope, UserDefinedFunctionSymbol> = mutableMapOf()

    /** Get the associated [UserDefinedFunctionSymbol] (LExpression-world) for a given [FunctionInScope] (TAC world). */
    fun getLExprUninterpretedFunctionSymbol(functionInScope: FunctionInScope) =
        uninterpretedFunctionSymbols
            .computeIfAbsent(functionInScope) { f ->
                UserDefinedFunctionSymbol(
                    f.name,
                    FixedFunctionSignatures(
                        f.paramSorts,
                        f.returnSort
                    ),
                    f.attribute
                )
            }
}

@KSerializable
enum class UFAttribute {
    None,
    Ghost,
    GhostOld,
}
