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

package sbf

import analysis.maybeNarrow
import config.Config
import config.ConfigScope
import utils.*
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import spec.cvlast.RuleIdentifier
import spec.rules.EcosystemAgnosticRule
import spec.cvlast.SpecType
import sbf.tac.TACMultiAssert
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACMeta
import vc.data.TACSymbol
import verifier.VerificationFlow


class TACMultiAssertTest {

    private fun numberOfAsserts(code: CoreTACProgram): Int {
        var counter = 0
        code.parallelLtacStream().forEach {
            it.maybeNarrow<TACCmd.Simple.AssertCmd>()?.let {
                counter++
            }
        }
        return counter
    }


    private fun assertsOf(code: CoreTACProgram) = code
        .parallelLtacStream()
        .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssertCmd>() }
        .toList()

    private fun baseRule(tacProg: CoreTACProgram) = EcosystemAgnosticRule(
        ruleIdentifier = RuleIdentifier.freshIdentifier(tacProg.name),
        ruleType = SpecType.Single.FromUser.SpecFile,
    )

    /**
     * The asserts of the test DSL stand for assertions of the user; this drops the mark from the last one, which
     * models an assert that the Prover inserted itself, as it does for an unresolved CPI call.
     */
    private fun withProverAssert(code: CoreTACProgram): CoreTACProgram {
        val (ptr, cmd) = assertsOf(code).maxBy { it.cmd.meta[TACMeta.ASSERT_ID]!! }
        return code.patching { p ->
            p.update(ptr, cmd.withMeta(cmd.meta - TACMeta.CVL_USER_DEFINED_ASSERT))
        }
    }

    private fun twoAsserts() = SbfTestDSL.makeCFG("twoAsserts") {
        bb(0) {
            "CVT_nondet_u64"()
            r1 = r0
            assert(CondOp.LE(r1, 1000UL))
            assert(CondOp.LE(r1, 2000UL))
            exit()
        }
    }

    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                "CVT_nondet_u64"()
                r1 = r0
                assume(CondOp.LE(r1, 1000UL))
                "CVT_nondet_u64"()
                r2 = r0
                assume(CondOp.LE(r2, 1000UL))
                r3 = r1
                BinOp.ADD(r3, r2)
                assert(CondOp.LE(r3, 2000UL))
                r3 = r1
                BinOp.SUB(r3, r2)
                assert(CondOp.GE(r3, 0UL))
                exit()
            }

        }

        val tacProg = toTAC(cfg)
        println("=== Original TAC ===\n${dumpTAC(tacProg)}")

        ConfigScope(Config.MultiAssertCheck, true).use {
            val rules = VerificationFlow.splitAsserts(
                SolanaEncodedRule(
                    rule = EcosystemAgnosticRule(
                        ruleIdentifier = RuleIdentifier.freshIdentifier(tacProg.name),
                        ruleType = SpecType.Single.FromUser.SpecFile,
                    ),
                    code = tacProg,
                )
            )
            Assertions.assertEquals(true, rules.size == 2)
            var counter = 0
            rules.forEach {
                counter++
                val code = it.code
                println("=== TAC for assert === $counter\n${dumpTAC(code)}")
                Assertions.assertEquals(true, numberOfAsserts(code) == 1)
                Assertions.assertEquals(true, verify(code))
            }
        }
    }

    @Test
    fun testEmptyAssertsReturnsSingleRule() {
        val cfg = SbfTestDSL.makeCFG("testEmpty") {
            bb(0) {
                "CVT_nondet_u64"()
                r1 = r0
                assume(CondOp.LE(r1, 1000UL))
                exit()
            }
        }

        val tacProg = toTAC(cfg)
        println("=== Original TAC ===\n${dumpTAC(tacProg)}")

        ConfigScope(Config.MultiAssertCheck, true).use {
            val rules = VerificationFlow.splitAsserts(
                SolanaEncodedRule(
                    rule = EcosystemAgnosticRule(
                        ruleIdentifier = RuleIdentifier.freshIdentifier(tacProg.name),
                        ruleType = SpecType.Single.FromUser.SpecFile,
                    ),
                    code = tacProg,
                )
            )
            Assertions.assertEquals(1, rules.size)
            Assertions.assertEquals(0, numberOfAsserts(rules.single().code))
        }
    }

    /**
     * An assert that the Prover inserted is part of the split like any other, so the subrule that was generated for
     * it is where it is checked - it is not assumed there.
     */
    @Test
    fun theAssertOfASubRuleIsKeptWhoeverInsertedIt() {
        val tacProg = withProverAssert(toTAC(twoAsserts()))

        ConfigScope(Config.MultiAssertCheck, true).use {
            val rules = VerificationFlow.splitAsserts(
                SolanaEncodedRule(rule = baseRule(tacProg), code = tacProg)
            )
            Assertions.assertEquals(2, rules.size)

            for (rule in rules) {
                val finalized = TACMultiAssert.assumeProverAsserts(rule.code, rule.rule)
                Assertions.assertEquals(1, assertsOf(finalized).size, "in ${rule.rule.declarationId}")
            }
        }
    }

    /** An assert that the Prover adds to a subrule after the split has no id of the split, and is assumed. */
    @Test
    fun aProverAssertAddedAfterTheSplitIsAssumed() {
        val tacProg = toTAC(twoAsserts())

        ConfigScope(Config.MultiAssertCheck, true).use {
            val subRule = VerificationFlow.splitAsserts(
                SolanaEncodedRule(rule = baseRule(tacProg), code = tacProg)
            ).first()

            val (ptr, _) = assertsOf(subRule.code).single()
            val withProverAssert = subRule.code.patching { p ->
                p.addBefore(ptr, listOf(TACCmd.Simple.AssertCmd(TACSymbol.True, "a check of the Prover")))
            }
            Assertions.assertEquals(2, assertsOf(withProverAssert).size)

            val finalized = TACMultiAssert.assumeProverAsserts(withProverAssert, subRule.rule)
            Assertions.assertEquals(1, assertsOf(finalized).size)
        }
    }
}
