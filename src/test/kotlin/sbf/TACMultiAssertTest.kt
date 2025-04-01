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
import com.certora.collect.*
import config.Config
import config.ConfigScope
import datastructures.stdcollections.*
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import log.*
import org.junit.jupiter.api.*
import spec.cvlast.RuleIdentifier
import spec.rules.EcosystemAgnosticRule
import spec.cvlast.SpecType
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(1)
class TACMultiAssertTest {
    private var outContent = ByteArrayOutputStream()
    private var errContent = ByteArrayOutputStream()

    private val originalOut = System.out
    private val originalErr = System.err

    // system properties have to be set before we load the logger
    @BeforeAll
    fun setupAll() {
        System.setProperty(LoggerTypes.SBF.toLevelProp(), "info")
    }

    // we must reset our stream so that we could match on what we have in the current test
    @BeforeEach
    fun setup() {
        outContent = ByteArrayOutputStream()
        errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent, true)) // for 'always' logs
        System.setErr(PrintStream(errContent, true)) // loggers go to stderr
    }

    private fun debug() {
        originalOut.println(outContent.toString())
        originalErr.println(errContent.toString())
    }

    // close and reset
    @AfterEach
    fun teardown() {
        debug()
        System.setOut(originalOut)
        System.setErr(originalErr)
        outContent.close()
        errContent.close()
    }

    private fun numberOfAsserts(code: CoreTACProgram): Int {
        var counter = 0
        code.parallelLtacStream().forEach {
            it.maybeNarrow<TACCmd.Simple.AssertCmd>()?.let {
                counter++
            }
        }
        return counter
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
        sbfLogger.warn { "=== Original TAC ===\n${dumpTAC(tacProg)}" }

        ConfigScope(Config.MultiAssertCheck, true).use {
            val rules = multiAssertChecks(listOf(CompiledSolanaRule(
                code = tacProg,
                rule = EcosystemAgnosticRule(
                    ruleIdentifier = RuleIdentifier.freshIdentifier(tacProg.name),
                    ruleType = SpecType.Single.FromUser.SpecFile
                ))))
            Assertions.assertEquals(true, rules.size == 2)
            var counter = 0
            rules.forEach {
                counter++
                val code = it.code
                sbfLogger.warn { "=== TAC for assert === $counter\n${dumpTAC(code)}" }
                Assertions.assertEquals(true, numberOfAsserts(code) == 1)
                Assertions.assertEquals(true, verify(code))
            }
        }
    }
}
