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

package wasm.host.soroban.types

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import tac.generation.*
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.*
import wasm.host.soroban.*

/**
    Base implementation of all types which map from keys to values.  Mappings are immutable.

    In TAC, we have two global maps for each mapping type (Bytes, String, Vec, etc.):
        sizes: (handle)->(size of the mapping)
        mappings: (handle, key)->(value)
 */
@KSerializable
abstract class MappingType : ObjectType() {
    abstract val mappings: TACSymbol.Var
    abstract val sizes: TACSymbol.Var

    override fun init() = mergeMany(
        super.init(),
        assignHavoc(mappings),
        assignHavoc(sizes)
    )

    /**
        Creates a new mapping, setting the mappings size to the result of [size], and stores the new handle in
        [newHandle].
     */
    fun new(
        newHandle: TACSymbol.Var,
        contentSummary: (TACExprFact.() -> List<TACExpr>)? = null,
        size: TACExprFact.() -> TACExpr
    ) = mergeMany(
        allocHandle(newHandle, contentSummary),
        assume { Select(sizes.asSym(), newHandle.asSym()) eq size() }
    )

    /**
        Creates an empty mapping
     */
    open fun empty(newHandle: TACSymbol.Var) = new(newHandle) { 0.asTACExpr }

    /**
        Defines the contents of the mapping identified by [newHandle].  [mappingDef] returns an expression that gives
        the value of of the mapping at a given key.
     */
    fun defineMapping(
        newHandle: TACSymbol,
        mappings: TACSymbol.Var = this.mappings,
        mappingDef: TACExprFact.(queryKey: TACExpr.Sym.Var) -> TACExpr
    ) = defineMap(mappings) { (queryHandle, queryKey) ->
        ite(
            // If this is the handle we're defining...
            queryHandle eq newHandle.asSym(),
            // ...use the definition...
            mappingDef(queryKey),
            // ...else try any mappings previously defined.
            selectFromPreviousMappings(queryHandle, queryKey, mappings)
        )
    }

    /**
        To be called from with the mapping definition lambda in `defineMapping`.  This gets a mapping value from the
        *previous* mapping definitions.  For example, defining a new mapping B that is the result of adding entry (K, V)
        to mapping A, the definition of B would return `selectFromPreviousMappings(A, k)` for any key k != K.
     */
    fun selectFromPreviousMappings(handle: TACExpr, key: TACExpr, mappings: TACSymbol.Var = this.mappings) =
        TACExprFactUntyped {
            select(mappings.asSym(), handle, key)
        }

    /**
        Get the current value at [key].
     */
    fun getValue(handle: TACSymbol, key: TACExpr) =
        TACExprFactUntyped {
            select(mappings.asSym(), handle.asSym(), key)
        }

    /**
        Gets the size of the mapping identified by [mapping], storing the result in [dest].
     */
    fun getSize(
        dest: TACSymbol.Var,
        handle: TACSymbol
    ) = mergeMany(
        assign(dest) { select(sizes.asSym(), handle.asSym()) },
        assume { dest.asSym() le UInt.MAX_VALUE.toLong().asTACExpr }
    )

    /**
        Gets the size of the mapping identified by [mapping], and then executes [f] with a temporary variable
        representing the size.
     */
    fun withSize(
        handle: TACSymbol,
        f: TACExprFact.(TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val tmp = TACSymbol.Var("size", Tag.Bit256).toUnique("!")
        return mergeMany(
            getSize(tmp, handle),
            TACExprFactUntyped.f(tmp)
        )
    }
}
