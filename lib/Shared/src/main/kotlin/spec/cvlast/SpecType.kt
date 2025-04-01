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

package spec.cvlast

import com.certora.collect.*
import kotlinx.serialization.Serializable
import spec.genericrulegenerators.BuiltInRuleId
import spec.rules.IRule
import utils.*

// there's room to cleanup these - it shouldn't be part of PureCVLType
// also do we need so many? what is the purpose of these?
/**
 * The type of an [IRule]. The type of a [CVLInvariant] is [SpecType.Single.FromUser].
 */
@KSerializable
@Treapable
sealed class SpecType: AmbiSerializable {

    open fun isFromUser(): Boolean = false

    /**
     * Returns whether this is a rule type that has been auto-generated based on a [Single.FromUser] rule.
     */
    open fun isDerived(): Boolean = false

    /**
     * Returns the [Single.FromUser] rule that lead to the generation of this rule type, if such exists.
     */
    open fun getOriginatingRule(): IRule? = null

    /**
     * Returns the [CVLInvariant] rule that lead to the generation of this rule type, if such exists.
     */
    open fun getOriginatingInvariant(): CVLInvariant? = null

    /**
     * Returns true if this type of rule should be counted in the overall rule count presented to users.
     * Should *not* be true for derived rules (of invariants, sanity checks, multi assert instances), and/or sub-rules of group rules
     */
    open fun isCounted(): Boolean = false

    @Serializable
    sealed class Single : SpecType() {
        @Serializable
        sealed class FromUser : Single() {

            override fun isFromUser(): Boolean = true
            override fun isCounted(): Boolean = true

            @Serializable
            object SpecFile : FromUser() {
                override fun hashCode() = hashObject(this)

                private fun readResolve(): Any = SpecFile
            }
            @Serializable
            object ImportedSpecFile : FromUser() {
                override fun hashCode() = hashObject(this)
                private fun readResolve(): Any = ImportedSpecFile
            }
        }

        @Serializable
        data class SkippedMissingOptionalMethod(val missingMethod: String, val contractName: String) : Single()

        @Serializable
        object InCodeAssertions : Single() {
            override fun hashCode() = hashObject(this)

            private fun readResolve(): Any = InCodeAssertions
        }

        /**
         * [SpecType]s for sanity rules and for multi-assert checks. Both flows create sub rules from a base rule.
         * When creating the sub rules receive the ruleType [GeneratedFromBasicRule] which creates a parent / child relationship.
         *
         * Note: In the Solana and Soroban flow this parent / child relationship forms the rule tree in the [TreeViewReporter]
         * (see [EntryPoint.buildRuleTree])
         */
        @Serializable
        sealed class GeneratedFromBasicRule : Single() {
            /**
             * Represents the original rule the instance is generated from.
             */
            abstract val originalRule: IRule

            override fun isDerived(): Boolean = true
            override fun getOriginatingRule(): IRule = originalRule
            /**
             * Returns a copy of this [GeneratedFromBasicRule] with a new originating rule [newOriginalRule].
             * This is used for matching between sanity rules and their corresponding base rules.
             */
            abstract fun copyWithOriginalRule(newOriginalRule: IRule): GeneratedFromBasicRule


            /**
             * [SpecType]s for sanity rules.
             * More documentation may be found in `SanityRuleGenerator.kt`, where sanity rules are generated.
             */
            @Serializable
            sealed class SanityRule : GeneratedFromBasicRule() {
                /**
                 * [SpecType] which is used inside `GenerateRulesForVacuityChecksForRules`.
                 */
                @Serializable
                data class VacuityCheck(override val originalRule: IRule) : SanityRule() {
                    override fun copyWithOriginalRule(newOriginalRule: IRule): VacuityCheck =
                        copy(originalRule = newOriginalRule)
                }

                /**
                 * Tautology check for [assertCVLCmd].
                 */
                @Serializable
                data class AssertTautologyCheck(
                    override val originalRule: IRule,
                    val assertCVLCmd: CVLCmd.Simple.Assert
                ): SanityRule() {
                    override fun copyWithOriginalRule(newOriginalRule: IRule): AssertTautologyCheck =
                        copy(originalRule = newOriginalRule)
                }

                /**
                 * Invariant triviality check for [assertCVLCmd].
                 */
                @Serializable
                data class TrivialInvariantCheck(
                    override val originalRule: IRule,
                    val assertCVLCmd: CVLCmd.Simple.Assert
                ) : SanityRule() {
                    override fun copyWithOriginalRule(newOriginalRule: IRule): TrivialInvariantCheck =
                        copy(originalRule = newOriginalRule)
                }

                /**
                 * @property assumeCVLCmd assume CVL command to check
                 * [SpecType] which is used inside `GenerateRulesForRedundantRequiresCheck`.
                 */
                @Serializable
                data class RedundantRequireCheck(
                    override val originalRule: IRule,
                    val assumeCVLCmd: CVLCmd.Simple.AssumeCmd
                ) : SanityRule() {
                    override fun copyWithOriginalRule(newOriginalRule: IRule): RedundantRequireCheck =
                        copy(originalRule = newOriginalRule)
                }

                /**
                 * @property assertCVLCmd assert CVL command to check
                 * @property expr expression generated from the original assert
                 * [SpecType] which is used inside [AssertionStructureCheck]
                 */
                @Serializable
                sealed class AssertionStructureCheck : SanityRule() {
                    abstract val assertCVLCmd: CVLCmd.Simple.Assert
                    abstract val expr: CVLExp.BinaryExp

                    abstract val sanityRuleName: String

                    /**
                     * in this check [expr] is generated from the left operand of the binary expression in [assertCVLCmd]
                     */
                    @Serializable
                    data class LeftOperand(
                        override val originalRule: IRule,
                        override val assertCVLCmd: CVLCmd.Simple.Assert,
                        override val expr: CVLExp.BinaryExp
                    ) : AssertionStructureCheck() {

                        override val sanityRuleName: String = "assertion_left_operand_check"
                        override fun copyWithOriginalRule(newOriginalRule: IRule): LeftOperand =
                            copy(originalRule = newOriginalRule)
                    }

                    /**
                     * in this check [expr] is generated from the right operand of the binary expression in [assertCVLCmd]
                     */
                    @Serializable
                    data class RightOperand(
                        override val originalRule: IRule,
                        override val assertCVLCmd: CVLCmd.Simple.Assert,
                        override val expr: CVLExp.BinaryExp
                    ) : AssertionStructureCheck() {
                        override val sanityRuleName: String = "assertion_right_operand_check"
                        override fun copyWithOriginalRule(newOriginalRule: IRule): RightOperand =
                            copy(originalRule = newOriginalRule)
                    }

                    /**
                     * in this check [expr] is of the form "!P && !Q" where [assertCVLCmd] is of the form "P <=> Q"
                     */
                    @Serializable
                    data class IFFBothFalse(
                        override val originalRule: IRule,
                        override val assertCVLCmd: CVLCmd.Simple.Assert,
                        override val expr: CVLExp.BinaryExp
                    ) : AssertionStructureCheck() {
                        override val sanityRuleName: String = "assertion_iff_not_both_false"
                        override fun copyWithOriginalRule(newOriginalRule: IRule): IFFBothFalse =
                            copy(originalRule = newOriginalRule)
                    }

                    /**
                     * in this check [expr] is of the form "P && Q" where [assertCVLCmd] is of the form "P <=> Q"
                     */
                    @Serializable
                    data class IFFBothTrue(
                        override val originalRule: IRule,
                        override val assertCVLCmd: CVLCmd.Simple.Assert,
                        override val expr: CVLExp.BinaryExp
                    ) : AssertionStructureCheck() {
                        override val sanityRuleName: String = "assertion_iff_not_both_true"
                        override fun copyWithOriginalRule(newOriginalRule: IRule): IFFBothTrue =
                            copy(originalRule = newOriginalRule)
                    }
                }
            }

            @Serializable
            sealed class MultiAssertSubRule : GeneratedFromBasicRule() {
                @Serializable
                sealed class SpecFile : MultiAssertSubRule() {
                    abstract val assertId: Int
                    abstract val assertMessage: String
                    abstract val cvlCmdLoc: Range
                }

                @Serializable
                data class SatisfySpecFile(
                    override val originalRule: IRule,
                    override val assertId: Int,
                    override val assertMessage: String,
                    override val cvlCmdLoc: Range
                ) : SpecFile() {
                    override fun copyWithOriginalRule(newOriginalRule: IRule): SatisfySpecFile {
                        return copy(originalRule = newOriginalRule)
                    }
                }

                @Serializable
                data class AssertSpecFile(
                    override val originalRule: IRule,
                    override val assertId: Int,
                    override val assertMessage: String,
                    override val cvlCmdLoc: Range
                ) : SpecFile() {
                    override fun copyWithOriginalRule(newOriginalRule: IRule): AssertSpecFile {
                        return copy(originalRule = newOriginalRule)
                    }
                }

                @Serializable
                data class HashingBoundCheck(
                    override val originalRule: IRule,
                    val assertId: Int,
                    val assertMessage: String
                ) : MultiAssertSubRule() {
                    override fun copyWithOriginalRule(newOriginalRule: IRule): HashingBoundCheck {
                        return copy(originalRule = newOriginalRule)
                    }
                }

                @Serializable
                data class AutoGenerated(
                    override val originalRule: IRule
                ) : MultiAssertSubRule() {
                    override fun copyWithOriginalRule(newOriginalRule: IRule): AutoGenerated {
                        return copy(originalRule = newOriginalRule)
                    }
                }
            }
        }
        @Serializable
        sealed class EnvFree : Single() {
            @Serializable
            data class Static(val contractFunction: ContractFunction) : EnvFree()
        }
        @Serializable
        sealed class InvariantCheck : Single() {

            abstract val originalInv: CVLInvariant
            override fun isDerived(): Boolean = true
            override fun getOriginatingInvariant(): CVLInvariant = originalInv
            @Serializable
            data class InductionBase(override val originalInv: CVLInvariant) : InvariantCheck()
            @Serializable
            data class GenericPreservedInductionStep(override val originalInv: CVLInvariant) : InvariantCheck()
            @Serializable
            data class ExplicitPreservedInductionStep(override val originalInv: CVLInvariant, val methodSignature: ExternalQualifiedMethodParameterSignature) : InvariantCheck()
            @Serializable
            data class TransientStorageStep(override val originalInv: CVLInvariant) : InvariantCheck()
        }

        @Serializable
        data class BuiltIn(val birId: BuiltInRuleId) : Single() {
            override fun hashCode(): Int = hash { it + birId }
            override fun isCounted(): Boolean = true
        }

        @Serializable
        data object BMC : Single() {
            private fun readResolve(): Any = BMC
        }

    }

    @Serializable
    sealed class Group : SpecType() {

        @Serializable
        sealed class InvariantCheck : Group() {

            abstract val originalInv: CVLInvariant
            override fun isDerived(): Boolean = true
            override fun getOriginatingInvariant(): CVLInvariant = originalInv
            override fun isCounted(): Boolean = true

            @KSerializable
            data class Root(override val originalInv: CVLInvariant): InvariantCheck()
            @KSerializable
            data class CustomInductionSteps(override val originalInv: CVLInvariant): InvariantCheck()
            @KSerializable
            data class InductionSteps(override val originalInv: CVLInvariant): InvariantCheck()

        }

        @Serializable
        object StaticEnvFree : Group() {
            override fun hashCode() = hashObject(this)

            private fun readResolve(): Any = StaticEnvFree
        }

        @Serializable
        data class ContractRuleType(val contractName: String) : Single()
    }

}
