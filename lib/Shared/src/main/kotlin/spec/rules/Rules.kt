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

package spec.rules

import com.certora.collect.*
import kotlinx.serialization.Serializable
import datastructures.stdcollections.*
import spec.cvlast.*
import utils.*
import java.util.*

/**
 * The most basic rule object that TACVerifier uses.
 * A rule has a [declarationId] which serves as a name.
 * [ruleType] is containing metadata about the rule.
 * [parentIdentifier] is describing the parent rule containing this rule, if exists.
 * Notably, there's no parent to a parent. A parent rule cannot be another rule's child.
 */
@Serializable
@Treapable
sealed interface IRule : AmbiSerializable {
    /**
     * A unique identifier for this rule that remains stable also across copy operations.
     * This identifier can be used to uniquely identify a rule, and is used, for instance in [TreeViewReporter].
     *
     * This [DisplayableIdentifier] should remain stable across [copy] operations of the [IRule] - unless one
     * wants to explicitly create a new node for the [copy]'d rule in the [TreeViewReporter].
     *
     * We currently have several locations where we use [copy] on [IRule] to associate additional
     * [SingleRuleGenerationMeta] information to the rule. In terms of the [TreeViewReporter],
     * it's important that after the copy operation we still reference the same node.
     * We use this identifier for this purpose.
     *
     * Phrased differently, for two rules rule1 and rule2 it's possible that
     *
     * rule1 != rule2 && rule1.ruleIdentifier == rule2.ruleIdentifier
     *
     * */
    val ruleIdentifier: RuleIdentifier
    val ruleType: SpecType

    /**
     * This is the only concept related to CVL in a [IRule], though only the name
     * suggests relation to CVL here.
     * */
    val range: Range

    /**
     * Specifies whether this rule expects UNSAT to pass (like an assertion)
     * or SAT (like a satisfy).
     */
    val isSatisfyRule: Boolean

    val declarationId: String
        get() = ruleIdentifier.displayName

    val parentIdentifier get() = ruleIdentifier.parentIdentifier

    fun getAllSingleRules(): List<SingleRule>


    companion object {
        fun createDummyRule(ruleName: String, isSatisfyRule: Boolean = false): CVLSingleRule {
            val rule = CVLScope.AstScope.extendIn(CVLScope.Item::RuleScopeItem) { scope ->
                CVLSingleRule(
                    ruleIdentifier = RuleIdentifier.freshIdentifier(ruleName),
                    range = Range.Empty(), //This should be filled with range information to support Jump To Source in the FE.
                    params = listOf(),
                    description = ruleName,
                    goodDescription = "This is the rule with the name $ruleName in your Certora specifications.",
                    block = listOf(),
                    ruleType = SpecType.Single.FromUser.SpecFile,
                    scope = scope,
                    methodParamFilters = MethodParamFilters.noFilters(Range.Empty(), scope),
                    ruleGenerationMeta = SingleRuleGenerationMeta.Empty,
                    isSatisfyRule = isSatisfyRule
                )
            }
            return rule
        }

    }
}

/**
 * A single rule representing a singular rule, i.e. this can either be
 * a [CVLSingleRule] or an [EcosystemAcgnosticRule]
 */
@Serializable
sealed class SingleRule : IRule {
    abstract override val ruleType: SpecType.Single
    override fun getAllSingleRules(): List<SingleRule> {
        return listOf(this)
    }
}

/**
 *  A single rule that is independent of the Ecosystem. This rule is used for the Solana and Soroban flow
 */
@Serializable
data class EcosystemAgnosticRule(
    override val ruleIdentifier: RuleIdentifier,
    override val ruleType: SpecType.Single,
    override val isSatisfyRule: Boolean = false,
    override val range: Range = Range.Empty()
) : SingleRule()


//
// All rules related to CVL, i.e., subtypes of [ICVLRule] follow below.
//

/**
 * A common interface for all CVL-related rules. While this interface
 * is used in the CVL type checker, the TACVerifier works on the more abstract
 * [IRule] that is independent of CVL.
 */
@Serializable
sealed interface ICVLRule : IRule, CreatesScope

@Serializable
data class StaticRule(
    override val ruleIdentifier: RuleIdentifier,
    override val ruleType: SpecType.Single,
    override val scope: CVLScope,
    override val range: Range,
) : SingleRule(), HasRange, ICVLRule {
    override fun hashCode() = hash { it + declarationId + ruleType + scope + range }

    override val isSatisfyRule: Boolean = false
}

/**
 * Meta information about a "Single" rule as is being processed by `RuleChecker`.
 * In particular, gives information about:
 * - rule sanity checking
 * - method parameter instantiations relevant for identifying the rule
 */
@Serializable
@Treapable
sealed class SingleRuleGenerationMeta : AmbiSerializable {

    /**
     * An enum describing whether this rule is:
     * - [DISABLED_SANITY_CHECK] - currently marked as having no sanity checks - this is the default.
     * - [PRE_SANITY_CHECK] - is the version before sanity checks - this means there's another instance of the rule
     * that is modified to include the automatically generated sanity check, and the merging of the results is up to the
     * compiled rule processor.
     * - [BASIC_SANITY] - the basic sanity check version of a rule with no asserts and an assert false at the end.
     * - [DONE] - a version of the rule reported in the results after it has already completed sanity checks.
     */
    enum class Sanity {
        DISABLED_SANITY_CHECK,
        PRE_SANITY_CHECK,
        BASIC_SANITY,
        DONE
    }

    abstract val sanity: Sanity
    abstract fun updateSanity(newSanity: Sanity): SingleRuleGenerationMeta

    @Serializable
    object Empty : SingleRuleGenerationMeta() {
        override val sanity: Sanity = Sanity.DISABLED_SANITY_CHECK
        override fun updateSanity(newSanity: Sanity): SingleRuleGenerationMeta {
            return if (newSanity == Sanity.DISABLED_SANITY_CHECK) {
                Empty
            } else {
                WithSanity(newSanity)
            }
        }

        private fun readResolve(): Any = Empty
        override fun hashCode(): Int = hashObject(this)
    }

    @Serializable
    data class WithSanity(override val sanity: Sanity) : SingleRuleGenerationMeta() {
        init {
            check(sanity != Sanity.DISABLED_SANITY_CHECK) { "Cannot be in WithSanity mode when sanity checks are disabled" }
        }

        override fun hashCode(): Int = hash { it + sanity }

        override fun updateSanity(newSanity: Sanity): SingleRuleGenerationMeta {
            return if (newSanity == Sanity.DISABLED_SANITY_CHECK) {
                Empty
            } else {
                WithSanity(newSanity)
            }
        }
    }

    @Serializable
    data class WithMethodInstantiations(override val sanity: Sanity, val range: Range, val instMethodSignatures: List<String>) :
        SingleRuleGenerationMeta() {
        constructor(sanity: Sanity, range: Range, vararg instMethodSignatures: String) : this(sanity, range, instMethodSignatures.asList())

        override fun hashCode(): Int = hash { it + sanity + instMethodSignatures }

        override fun updateSanity(newSanity: Sanity): SingleRuleGenerationMeta {
            return this.copy(sanity = newSanity)
        }
    }
}

@Serializable
data class AssertRule(
    override val ruleIdentifier: RuleIdentifier,
    val isFunc: Boolean,
    val funcName: String?,
    override val scope: CVLScope,
    override val isSatisfyRule: Boolean = false
) : ICVLRule {
    override val range = Range.Empty()

    override val ruleType: SpecType
        get() = SpecType.Single.InCodeAssertions

    override fun getAllSingleRules(): List<CVLSingleRule> {
        return listOf()
    }
}


@Serializable
data class CVLSingleRule(
    override val ruleIdentifier: RuleIdentifier,
    override val range: Range,
    override val params: List<CVLParam>,
    val description: String,
    val goodDescription: String,
    override val block: List<CVLCmd>,
    override val ruleType: SpecType.Single,
    override val scope: CVLScope,
    override val methodParamFilters: MethodParamFilters,
    val ruleGenerationMeta: SingleRuleGenerationMeta,
    override val isSatisfyRule: Boolean = false,
) : SingleRule(), ICVLRule, CVLDeclarationWithCode, CVLDeclarationWithMethodParamFilters {

    init {

        /**
         * List of (unexpected) missing CVL locations
         */
        val missingCVLLocs: List<String> = mutableListOf<String>().also { _ ->
            /**
             * Adds appropriate exception message for each [cvlCmd] who lacks a CVL location.
             */
            fun checkAllLocsExist(cvlCmd: CVLCmd) {
                when (cvlCmd) {
                    is CVLCmd.Simple -> {}
                    is CVLCmd.Composite.If -> {
                        checkAllLocsExist(cvlCmd.thenCmd)
                        checkAllLocsExist(cvlCmd.elseCmd)
                    }

                    is CVLCmd.Composite.Block -> {
                        cvlCmd.block.forEach {
                            checkAllLocsExist(it)
                        }
                    }
                }
            }

            if (ruleType is SpecType.Single.FromUser) {
                block.forEach {
                    checkAllLocsExist(it)
                }
            }
        }
        if (missingCVLLocs.isNotEmpty()) {
            throw IllegalStateException(missingCVLLocs.joinToString(separator = System.lineSeparator()))
        }
    }

    fun isSanityCheck() = ruleGenerationMeta.sanity == SingleRuleGenerationMeta.Sanity.BASIC_SANITY

    private val hashCodeCache: Int by lazy {
        Objects.hash(declarationId, range)
    }

    override fun hashCode() = hashCodeCache

    override val declarationId: String
        get() = ruleIdentifier.displayName

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is CVLSingleRule) {
            return false
        }

        return declarationId == other.declarationId &&
            range == other.range
    }

    fun methodInstantiationRange(): Range? =
        when (val meta = ruleGenerationMeta) {
            is SingleRuleGenerationMeta.WithMethodInstantiations -> meta.range
            else -> null
        }

    /** If this is used before the [MoveMethodVarsToParams] transform it will not return the method variables in the rule */
    override val methodParamIds: List<String>
        get() = params.filter { it.type == EVMBuiltinTypes.method }.map { it.id }
}

// Note that a rule group can contain other rule groups
@Serializable
data class GroupRule(
    override val ruleIdentifier: RuleIdentifier,
    override val range: Range,
    val rules: List<ICVLRule>,
    override val ruleType: SpecType.Group,
    override val scope: CVLScope,
) : ICVLRule {

    override fun hashCode() = hash { it + declarationId + rules + ruleType }


    override val isSatisfyRule: Boolean = false

    override fun getAllSingleRules(): List<SingleRule> {
        return rules
            .flatMap { rule -> rule.getAllSingleRules() }
    }
}

/**
 * A group rule such that its child rules are created dynamically and not statically at the group rule creation
 * time (which is what we have in the regular [GroupRule])
 */
@Serializable
data class DynamicGroupRule(
    override val ruleIdentifier: RuleIdentifier,
    override val range: Range,
    override val ruleType: SpecType.Group,
    override val methodParamFilters: MethodParamFilters,
    override val scope: CVLScope
) : ICVLRule, CVLDeclarationWithMethodParamFilters {
    override fun hashCode() = hash { it + declarationId + ruleType + methodParamFilters }

    override val isSatisfyRule: Boolean = false

    override fun getAllSingleRules() = listOf<CVLSingleRule>()

    override val methodParamIds: List<String>
        get() = methodParamFilters.methodParamToFilter.keys.toList()
}

@Serializable
data class BMCRule(
    override val ruleIdentifier: RuleIdentifier,
    override val ruleType: SpecType.Single,
    override val range: Range,
    val sequenceLen: Int
) : SingleRule() {
    override val isSatisfyRule = false
    override fun getAllSingleRules() = listOf(this)
}

@Serializable
data class EquivalenceRule(
    override val ruleIdentifier: RuleIdentifier,
) : IRule {
    override val ruleType: SpecType
        get() = SpecType.Single.EquivalenceCheck
    override val range: Range
        get() = Range.Empty()
    override val isSatisfyRule: Boolean
        get() = false

    override fun getAllSingleRules(): List<SingleRule> {
        return listOf()
    }

    companion object {
        fun freshRule(nm: String) = EquivalenceRule(
            ruleIdentifier = RuleIdentifier.freshIdentifier(nm)
        )
    }
}
