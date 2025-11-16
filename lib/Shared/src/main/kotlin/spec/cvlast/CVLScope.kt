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

// CER-1384: Supressing warnings at file level to avoid churn during rewrite
@file:Suppress("UNNECESSARY_SAFE_CALL")

package spec.cvlast

import bridge.ContractInstanceInSDC
import com.certora.collect.*
import kotlinx.serialization.Transient
import spec.rules.ICVLRule
import utils.*
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uniquely identifies a scope in a CVL file. Scopes are e.g. defined by
 *  - the CVL file
 *  - a [IRule]
 *  - a `CVLCmd.BlockCmd`
 *  - ...
 *
 * Note that we support shadowing, i.e., the scopes form a stack, and [CVLSymbolTable.lookUp] works from top to bottom.
 *
 * Note: E.g. quantifier's capture areas don't constitute their own scopes, corresponding identifiers are typed using
 *  [CVLTypeEnvironment], whose instances are not permanently stored.
 */
@KSerializable
@Treapable
data class CVLScope(
    val scopeStack: List<Item>,
    val innerScope: CVLScope?
) : AmbiSerializable {

    // TODO: CERT-2321
    //  this abstraction still has some code smells and could use further refactoring.  In particular, there is a lot
    //  of dynamic casting, duplication between the [Item] subtypes and the corresponding ASTElements, a complicated
    //  protocol for constructing [Item]s, and the potentially buggy transient lateinit `element` field.  The type
    //  safety and null safety could both be made better.
    //
    //  I think a better design would be to either move some of the queries out to the AST nodes and just have the scope
    //  be dumb and not have a reference back to the AST, or to make the types here more precise (e.g. things that are
    //  contained in a TopLevelScope have a reference to the top level scope instead of everyone having a copy of an
    //  unstructured `List`.
    //
    //  Other potential refactors:
    //    - make ASTScope not a singleton, since there are multiple spec files (this would let us better handle `import`)
    //    - treat `ContractScope` differently from the others; I don't think there's any useful overlap
    //    - replace the three if-then-else scopes with a block scope
    //    - better encapsulate scopeId

    /** @return all [Item]s in the stack that have type T. */
    inline fun <reified T: Item> scopeStackItems() = scopeStack.filterIsInstance<T>()

    /** @return the ID of the innermost scope of `this`. */
    fun currentScope(): Int = scopeStack.last().scopeId

    /** @return a fresh scope corresponding to [contract].  Has no side effects on `this`.  */
    // TODO(jtoman): don't use the hash code for the scope id you dummy
    fun push(contract: ContractInstanceInSDC) = CVLScope(scopeStack + Item.ContractScopeItem(contract.address.hashCode(), contract), this)

    /**
     * An [Item] is a single entry on the scope stack.  Each Item has a unique identifier and a type (encoded in its class).
     * It may have a reference to the syntactic element that defines it.
     * For example, a [HookScopeItem] has a reference to the corresponding [CVLHook] object that defines it
     * (the [CVLHook] also has a reference back; see [CVLHook.scopeId] and [CVLHook.scope])
     */
    @KSerializable
    @Treapable
    sealed class Item : AmbiSerializable {

        abstract val scopeId : Int

        /** A special scope corresponding to the entire spec file */
        @KSerializable
        object AstScopeItem : Item() {
            override fun toString(): String = "Spec file"

            override val scopeId: Int
                get() = 0

            override fun hashCode() = utils.hashObject(this)
            private fun readResolve() : Any = AstScopeItem
        }

        /** A scope corresponding to a contract */
        @KSerializable
        class ContractScopeItem(override val scopeId: Int, val contract: ContractInstanceInSDC) : Item() {
            override fun toString(): String = "Contract ${contract.name}"
            override fun hashCode() = utils.hashObject(this)
        }

        /**
         * Marker interface for scopes that represent a "top-level" block of commands, i.e. a block of commands that is
         * contained directly inside the [AstScope].
         * @see topLevelScope
         */
        sealed interface TopLevelScope

        /**
         * Syntactic scopes correspond to a particular element of the AST.  T is the type of the AST node.
         * Each Syntactic scope has a scope ID, and may have a reference to the  syntactic element defining it
         */
        @KSerializable
        sealed class ASTElementScope<T : CreatesScope> : Item() {
            @Transient
            protected lateinit var element : T
            fun isElementInitialized() = ::element.isInitialized

            fun setItem(element: T) {
                this.element = element
            }

            /** Two [ASTElementScope]s are equal if they have the same type and scopeId */
            override fun equals(other: Any?): Boolean {
                return other is Item
                    && other::class == this::class
                    && other.scopeId == this.scopeId
            }

            override fun hashCode(): Int = scopeId.hashCode()
        }

        /** The scope of a hook */
        @KSerializable
        class HookScopeItem(override val scopeId: Int) : ASTElementScope<CVLHook>(), TopLevelScope {
            override fun toString(): String = "Hook at ${element.range}"
        }

        /** The scope for a rule */
        @KSerializable
        class RuleScopeItem(override val scopeId: Int) : ASTElementScope<ICVLRule>(), TopLevelScope {
            override fun toString(): String = "Rule ${element.declarationId}"

            fun isDerived(): Boolean = this.element.ruleType.isDerived()

            fun isDerivedFromInvariant() = this.element.ruleType is SpecType.Single.InvariantCheck

            val ruleId
                get() = if (isElementInitialized()) {
                    element.declarationId
                } else {
                    null
                }
        }

        /** The scope for a CVL Function */
        @KSerializable
        class CVLFunctionScopeItem(override val scopeId: Int) : ASTElementScope<CVLFunction>(), TopLevelScope {
            fun returnType() = element.rets

            fun functionName() = element.functionIdentifier

            override fun toString(): String = "CVLFunction scope for ${element.declarationId}"
        }

        /** The scope for an invariant */
        @KSerializable
        class InvariantScopeItem(override val scopeId: Int) : ASTElementScope<CVLInvariant>(), TopLevelScope {
            override fun toString(): String = "Invariant ${element.id}"
            val invariantId
                get() = if (isElementInitialized()) {
                    element.id
                } else {
                    null
                }
        }

        /** The scope for a `preserved` block */
        @KSerializable
        class PreserveScopeItem(override val scopeId: Int) : ASTElementScope<CVLPreserved>() {
            override fun toString(): String = when (element) {
                is CVLPreserved.Generic -> "Generic preserve block"
                is CVLPreserved.ExplicitMethod -> "Preserve block ${(element as CVLPreserved.ExplicitMethod).methodSignature}"
                is CVLPreserved.Fallback -> "Preserve block fallback()"
                is CVLPreserved.TransactionBoundary -> "Preserve block on transaction boundary"
                is CVLPreserved.Constructor -> "Preserve block constructor"
            }
        }

        /** The scope for a block */
        @KSerializable
        class BlockCmdScopeItem(override val scopeId: Int) : ASTElementScope<CVLCmd.Composite.Block>() {
            override fun toString(): String = "Block at ${element.range}"
        }

        /**
         * The scope for an if-then-else command.  Note that there aren't really any variables in such
         * a scope, but this type is needed to determine whether a subscope is "inside an if"
         */
        @KSerializable
        sealed class BranchCmdScopeItem : ASTElementScope<CVLCmd.Composite.If>() {
            val cmd : CVLCmd.Composite.If
                get() = element

            /** A scope corresponding to the "then" block of an `if` command */
            @KSerializable
            class IfCmdThenScopeItem(override val scopeId: Int) : BranchCmdScopeItem() {
                override fun toString(): String = "If branch at ${element.range}"
            }

            /** A scope corresponding to the `else` block of an `if` command */
            @KSerializable
            class IfCmdElseScopeItem(override val scopeId: Int) : BranchCmdScopeItem() {
                override fun toString(): String = "Else branch at ${element.range}"
            }
        }

        /**
         * A scope corresponding to an expression summary (contains variables defined in the methods block entry).
         * Note: although ExpressionSummaries are contained directly inside the [AstScope], they are not marked as
         * [TopLevelScope] because they are not blocks of commands.  Feel free to revisit this decision in a future
         * refactor.
         */
        @KSerializable
        class ExpressionSummary(override val scopeId: Int) : ASTElementScope<SpecCallSummary.Exp>() {
            override fun toString(): String = "Arguments for $element"
        }
    }

    /** For performance, we only compute the hash code once. */
    private val hashCodeCache : Int by lazy { hash { it + scopeStack + innerScope } }
    override fun hashCode() = hashCodeCache

    override fun equals(other: Any?): Boolean {
        return other is CVLScope
            && Objects.equals(scopeStack, other.scopeStack)
            && Objects.equals(innerScope, other.innerScope)
    }

    override fun toString(): String = scopeStack.joinToString(" -> ", "(", ")")

    /** @return the innermost if-else statement containing this scope, if any */
    fun enclosingIfElse() : Item.BranchCmdScopeItem? =
        scopeStack.lastOrNull { it is Item.BranchCmdScopeItem } as? Item.BranchCmdScopeItem

    /** @return the outermost contract scope containing this scope, if any */
    fun enclosingContract() : Item.ContractScopeItem? =
        scopeStack.lastOrNull { it is Item.ContractScopeItem } as? Item.ContractScopeItem

    /** @return the outermost [Item.TopLevelScope] containing this scope, if any */
    fun topLevelScope() : Item.TopLevelScope? =
        scopeStack.firstOrNull { it is Item.TopLevelScope } as? Item.TopLevelScope

    /** @return the enclosing top-level scope if it is a CVL function, otherwise null */
    fun enclosingCVLFunction() : Item.CVLFunctionScopeItem? = topLevelScope() as? Item.CVLFunctionScopeItem

    /** @return the enclosing top-level scope if it is an invariant, otherwise null */
    fun enclosingInvariant(): Item.InvariantScopeItem? = topLevelScope() as? Item.InvariantScopeItem

    /** @return the innermost enclosing rule scope if there is one, otherwise null */
    fun enclosingRule(): Item.RuleScopeItem? =
        scopeStack.lastOrNull { it is Item.RuleScopeItem } as? Item.RuleScopeItem

    companion object {
        // TODO: CERT-2321 these creation functions could be cleaned up; the uniqueness of scopes is currently
        // difficult to enforce because the scope ids are not well encapsulated

        private val scopeCounter = AtomicInteger()

        fun newScope(): CVLScope = CVLScope(listOf(), CVLScope(listOf(), null))

        fun nextScope(): Int = scopeCounter.getAndIncrement()

        /** the topmost scope level, the spec file's outermost scope */
        val AstScope = newScope().copy(scopeStack = listOf(Item.AstScopeItem))

    }

    /**
     * Creates a new child scope and syntactic element that refer to each other.
     * @param mk the [Item] constructor for creating the scope
     * @param cb the constructor for the syntactic element
     * @return the newly created syntactic element
     */
    fun <AST_ELEM : CreatesScope, SCOPE : Item.ASTElementScope<in AST_ELEM>> extendIn(
        mk: (scopeId: Int) -> SCOPE,
        cb: (childScope: CVLScope) -> AST_ELEM
    ) : AST_ELEM {
        val nextId = nextScope()
        val deferred = mk(nextId)
        val gen = cb(CVLScope(this.scopeStack + deferred, this))
        deferred.setItem(gen)
        return gen
    }

    /**
     * Creates a new child scope and syntactic element that refer to each other.
     * @param mk the [Item] constructor for creating the scope
     * @param cb the constructor for the syntactic element
     * @return the newly created syntactic element
     *
     * @see [extendIn]
     */
    fun <AST_ELEM : CreatesScope, SCOPE : Item.ASTElementScope<in AST_ELEM>, ERR> extendInCollecting(
        mk: (scopeId: Int) -> SCOPE,
        cb: (childScope: CVLScope) -> CollectingResult<AST_ELEM, ERR>
    ): CollectingResult<AST_ELEM, ERR>
    {
        val nextId = nextScope()
        val deferred = mk(nextId)
        return cb(CVLScope(this.scopeStack + deferred, this)).map { gen ->
            deferred.setItem(gen)
            gen
        }
    }

    /**
     * Create two new child scopes and a new syntactic element which are interlinked.  The new child scopes both
     * refer to the newly created element.
     *
     * Note: this is currently only used to create scopes for if-then-else statements, which have scopes for the then-
     * and else-branches.
     *
     * @param mk1 the constructor for the first new scope
     * @param mk2 the constructor for the second new scope
     * @param cb the constructor for the syntactic element
     * @return the newly created syntactic element
     */
    fun <AST_ELEM : CreatesScope, SCOPE1, SCOPE2, ERR> extendInCollecting(
            mk1: (scopeId: Int) -> SCOPE1,
            mk2: (scopeId: Int) -> SCOPE2,
            cb: (leftScope: CVLScope, rightScope: CVLScope) -> CollectingResult<AST_ELEM, ERR>,
    ) : CollectingResult<AST_ELEM, ERR>
    where
        SCOPE1 : Item.ASTElementScope<in AST_ELEM>,
        SCOPE2 : Item.ASTElementScope<in AST_ELEM>
    {
        val nextId1 = nextScope()
        val scope1 = mk1(nextId1)

        val nextId2 = nextScope()
        val scope2 = mk2(nextId2)

        return cb(CVLScope(this.scopeStack + scope1, this),
            CVLScope(this.scopeStack + scope2, this)).bind { gen ->
            scope1.setItem(gen)
            scope2.setItem(gen)
            gen.lift()
        }
    }
}
