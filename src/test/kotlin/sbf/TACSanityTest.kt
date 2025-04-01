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

import sbf.cfg.*
import sbf.testing.SbfTestDSL
import log.*
import org.junit.jupiter.api.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(1)
class TACSanityTest {
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

    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("test1$vacuitySuffix") {
            bb(0) {
                r2 = r10[-200]
                BinOp.ADD(r2, 1)
                r10[-200] = r2
                r1 = 1
                "CVT_sanity"()  // This should be translated to CVT_satisfy(true)
                exit()
            }

        }

        sbfLogger.warn{"$cfg"}
        val tacProg = toTAC(cfg)
        sbfLogger.warn { dumpTAC(tacProg) }
        Assertions.assertEquals(false, verify(tacProg))
    }

    @Test
    fun test2() {
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(0) {
                r4 = r10[-200]
                assume(CondOp.LE(r4, 1000))
                r2 = r4
                BinOp.ADD(r2, 1)
                r10[-200] = r2
                r3 = r10[-200]
                assert(CondOp.LT(r4, r3))
                r1 = 1
                "CVT_sanity"() // This should be a non-op
                exit()
            }

        }

        sbfLogger.warn{"$cfg"}
        val tacProg = toTAC(cfg)
        sbfLogger.warn { dumpTAC(tacProg) }
        Assertions.assertEquals(true, verify(tacProg))
    }
}
