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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package prefixgen.data

import bridge.types.SolidityTypeDescription
import com.certora.collect.Treapable
import utils.AmbiSerializable
import utils.KSerializable

/**
 * A simplified AST for constructing Solidity programs.
 * All of the fuzz sequences are pretty-printed from this AST to (hopefully)
 * type-correct and syntactically correct Solidity code.
 */
@Treapable
@KSerializable
sealed interface SimpleSolidityAst : AmbiSerializable {
    /**
     * Represents the statement:
     * ```
     * vm.prank(asSender);
     * binds = contract.functionName(args);
     * ```
     *
     * [sighash] is the sighash of the [functionName] being call. [contract] is the *name*
     * of the receiver variable (**not** the type of the receiver).
     * The sort of the LHS depends on the value of [binds]. If it is null, then there is no LHS at all.
     * If the list is singleton, the [binds] is represented as:
     * ```
     * ty sym = ...
     * ```
     * where `ty` and `sym` are the type and symbol bound by the singleton [VarDef].
     *
     * Otherwise, a binding list is generated of the form:
     * ```
     * (ty1 sym1, , ty2 sym2, ...)
     * ```
     * Null entries are treated as "do not bind" and generate an empty element of the binding list, and
     * the `tyI` and symI` tokens come from the [VarDef] at position I.
     */
    @Treapable
    @KSerializable
    data class FunctionCall(
        val contract: String,
        val functionName: String,
        val asSender: SymExp.Symbol,
        val args: List<SymExp>,
        val binds: List<VarDef?>?,
        val sighash: String
    ) : SimpleSolidityAst

    /**
     * Insert some literal line into the source code. This is **usually** used to output comments or explanations
     * of the sequence.
     */
    @Treapable
    @KSerializable
    data class Literal(
        val lines: List<String>
    ) : SimpleSolidityAst

    /**
     * Indicates a literal statement which is semantically important. This must be a syntactically valid Solidity,
     * statement.
     */
    @Treapable
    @KSerializable
    data class SemanticLine(
        val line: String
    ) : SimpleSolidityAst

    /**
     * Any statement which binds a fresh identifier [boundId] with type [ty]
     */
    @KSerializable
    sealed interface BindingStmt : SimpleSolidityAst {
        val boundId: String

        val ty: SolidityTypeDescription
    }

    /**
     * Bind a new identifier [boundId] which *must* alias some existing definition with a compatible type.
     * The candidates for aliasing (and the type conversions) are represented by [discriminator].
     */
    @Treapable
    @KSerializable
    data class BindMust(
        @SoliditySourceIdentifier
        override val boundId: String,
        val discriminator: Discriminator,
        override val ty: SolidityTypeDescription
    ) : BindingStmt

    /**
     * Bind a new identifier [boundId] which either comes from a fresh input variable [boundId] in the input state,
     * *or* aliases an existing binding (if one exists). If there are aliasable bindings, these are expressed in [aliases].
     * A null [aliases] indicates that [boundId] will always equal [inputId], but separating input IDs and bound IDs is
     * convenient for code generation/state management.
     */
    @Treapable
    @KSerializable
    data class BindInputChoice(
        @SoliditySourceIdentifier
        override val boundId: String,
        val aliases: Discriminator?,

        @SoliditySourceIdentifier
        val inputId: String,
        override val ty: SolidityTypeDescription
    ) : BindingStmt

    /**
     * Bind a dynamically sized array to [rawId] whose elements are of type [elemType].
     * The length of the array is non-deterministically chosen by [lengthSym], which is bound to be at most
     * [prefixgen.SequenceGenerator.maxArrayLen].
     *
     * The elements of the array are taken from [elems], which are identifiers that must be bound by previous [BindingStmt].
     * NB that there is no literal dynamic array syntax, so this gets translated using allocator functions, see [prefixgen.Compiler]
     * for details.
     */
    @Treapable
    @KSerializable
    data class BindArray(
        val rawId: String,
        val elemType: SolidityTypeDescription.Primitive,
        val lengthSym: String,
        val elems: List<SymExp.Symbol>
    ) : SimpleSolidityAst

    /**
     * Bind [rawId] which is an enum with type [ty]. The ordinal used for the enum comes from [inputDiscriminator], which
     * is implicitly bound to be between 0 and [ty].[SolidityTypeDescription.UserDefined.Enum.enumMembers].length.
     * For a discussion of why this is even necessary, see [prefixgen.Compiler.compileEnumLiteral]
     */
    @Treapable
    @KSerializable
    data class BindEnum(
        val rawId: String,
        val ty: SolidityTypeDescription.UserDefined.Enum,

        @SoliditySourceIdentifier
        val inputDiscriminator: String
    ) : SimpleSolidityAst
}
