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

package analysis

import analysis.skeyannotation.AnnotateSkeyBifs
import annotation.SolidityConfigMatrix
import annotation.SolidityVersion
import com.certora.collect.*
import datastructures.stdcollections.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import loaders.SingleMethodTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import report.DummyLiveStatsReporter
import scene.IScene
import solver.SolverResult
import tac.StartBlock
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import vc.data.TACCmd.Simple.AssertCmd
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import verifier.TACVerifier


// these global fields are "borrowed" form [StorageAnalysisTest]

/* keeping this for later usage in tests
private val mapping = """
    contract $contractName {
        mapping (uint256 => uint256) public my_mapping;

        function test(uint256 x, uint256 y) public {
            my_mapping[x] = y;
        }
    }
""".trimIndent()
 */

/*
  loader doesn't like this, gives a message like
    `Found a cycle in our graph at node "754_1009_0_0_0_0_0_0"`
     (probably due to the loop when hashing the bytes array)

private val bytes = """
    contract $contractName {
        bytes public my_bytes;

        function test() public returns (bytes32) {
            return keccak256(abi.encodePacked(my_bytes));
        }
    }
""".trimIndent()
 */

private val ghostMapStore =
    run {
        val m0 = TACSymbol.Var("m0", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256))
        val m1 = TACSymbol.Var("m1", Tag.GhostMap(listOf(Tag.Bit256), Tag.Bit256))
        val s = TACSymbol.Var("s", Tag.Bit256)
        val v = TACSymbol.Var("v", Tag.Bit256)

        val x = TACSymbol.Var("x", Tag.Bit256)
        val a = TACSymbol.Var("a", Tag.Bool)

        val symbolTable = TACSymbolTable().copy(vars = treapSetOf(m0, m1, s, v, x, a), userDefinedTypes = setOf(skeySort))
        val txf = TACExprFactTypeChecked(symbolTable)

        codeFromListOfCommands(
            StartBlock,
            listOf(
                AssignExpCmd(s, txf { SimpleHash(number(32), listOf(number(0))) }),
                AssignExpCmd(m1, txf { Store(m0.asSym(), s.asSym(), v = v.asSym()) }),
                AssignExpCmd(x, txf { Select(m1.asSym(), s.asSym()) }),
                AssignExpCmd(a, txf { x.asSym() eq v.asSym() }),
                AssertCmd(a, "no msg"),
            ),
            "bytes"
        ).copy(symbolTable = symbolTable)
    }


/* keeping this for later usage in tests
private val bytes =
    run {
        val x = TACSymbol.Var("x", Tag.Bit256)
        val m = TACSymbol.Var("m", Tag.ByteMap)
        val l = TACSymbol.Var("l", Tag.Bit256)
        val a = TACSymbol.Var("a", Tag.Bool)

        var symbolTable = TACSymbolTable().copy(tags = Tags(setOf(x, m, l, a)))
        val txf = TACExprFactTypeChecked(symbolTable)

        val (hashExp, newAuxVars, newCmds) = TACExpr.SimpleHash.fromByteMapRange(
            m,
            TACSymbol.lift(0).asSym(),
            l.asSym(), // TACSymbol.lift(196).asSym(),
        )
        symbolTable = symbolTable.mergeDecls(newAuxVars)

        codeFromListOfCommands(
            StartBlock,
            listOf(
                AssignExpCmd(x, hashExp),
                *newCmds.toTypedArray(),
                // these two statements just ensure that TACSimpleSimpleSSA doesn't optimize the previous one away.
                AssignExpCmd(a, txf.makeTyped { x.asSym() ge number(0) }),
                AssertCmd(a, "no msg"),

                ),
            "bytes"
        ).copy(symbolTable = symbolTable)
    }
 */


@SolidityConfigMatrix(
    solidityVersion = [
        SolidityVersion.V4_24,
        SolidityVersion.V5_12,
        SolidityVersion.V6_8,
        SolidityVersion.V6_10,
        SolidityVersion.V7_6,
        SolidityVersion.V8_9,
    ],
    withHeaders = false,
    withOptimizeFlag = false
)

internal class SkeyDetectionTest : SingleMethodTest, TACBuilderAuxiliaries() {

    fun checkAnnotateOutput(ctp: CoreTACProgram): Boolean {
        unused(ctp)
        // for now we're happy if type checking succeeded ..
        return true
    }

    @Test
    fun testSimpleHashUnconstrainedLength() {
        val r0 = TACSymbol.Var("R0", Tag.Bit256)
        val r1 = TACSymbol.Var("R1", Tag.Bit256)
        val r2 = TACSymbol.Var("R2", Tag.Bit256)
        val r3 = TACSymbol.Var("R3", Tag.Bit256)
        val txf = TACExprFactTypeChecked(TACSymbolTable.withVars(setOf(r0, r1, r2, r3)))
        val p = codeFromListOfCommands(
            StartBlock,
            listOf(
                AssignExpCmd(
                    r0,
                    txf { SimpleHash(r3.asSym(), listOf(r1.asSym(), r2.asSym())) }
                ),
                TACCmd.Simple.AssumeExpCmd(txf { Gt(r0.asSym(), TACSymbol.Zero.asSym())  })
            ),
            "justSomeHash"
        )
        val ctp = p.transformToCore(mockk<IScene>())
        val transformed = AnnotateSkeyBifs.annotate(ctp)
        assertTrue(checkAnnotateOutput(transformed))
    }

    @Test
    fun testSimpleHashZeroOddBytes() {
        val r0 = TACSymbol.Var("R0", Tag.Bit256)
        val r1 = TACSymbol.Var("R1", Tag.Bit256)
        val r2 = TACSymbol.Var("R2", Tag.Bit256)
        val r3 = TACSymbol.Var("R3", Tag.Bit256)
        val txf = TACExprFactTypeChecked(TACSymbolTable.withVars(setOf(r0, r1, r2, r3)))
        val p = codeFromListOfCommands(
            StartBlock,
            listOf(
                AssignExpCmd(
                    r0,
                    txf { SimpleHash(r3.asSym(), listOf(r1.asSym(), r2.asSym())) }
                ),
                TACCmd.Simple.AssumeExpCmd(txf { Gt(r0.asSym(), TACSymbol.Zero.asSym())  })
            ),
            "justSomeHash"
        )
        val ctp = p.transformToCore(mockk<IScene>())
        val transformed = AnnotateSkeyBifs.annotate(ctp)
        assertTrue(checkAnnotateOutput(transformed))
    }

    /**
     * Checks that [AnnotateSkeyBifs] adequately handles the occurrence of skeys as ghost map indices, both in store
     * and select terms (they should be wrapped in `from_skey`). This runs the verifier in order to also detect
     * problems that come up later (as observed in https://certora.atlassian.net/browse/CER-1506 , where it took until
     * BoolIntConverter until we noticed the typing problem).
     */
    @Test
    fun testGhostMapStoreFromSkey() {
        runBlocking {
            val mockScene = mockk<IScene>()
            every { mockScene.getContracts() } returns listOf()
            val res = TACVerifier.verify(mockScene, ghostMapStore.transformToCore(mockScene), DummyLiveStatsReporter)
            assertTrue(res.firstExampleInfo.solverResult == SolverResult.UNSAT)
        }
    }

    @Test
    fun nonSkeyMinusSkey() {
        runBlocking {
            val mockScene = mockk<IScene>()
            every { mockScene.getContracts() } returns listOf()
            val prog = TACProgramBuilder {
                a assign SimpleHash(number(32), listOf(bS), HashFamily.Keccack)
                k assign Sub(cS, aS)
                x assign Gt(kS, number(0))
                assert(TACSymbol.True) // just checking for non-crashing behavior
            }.code
            val res = TACVerifier.verify(mockScene, prog, DummyLiveStatsReporter)
            assertTrue(res.firstExampleInfo.solverResult == SolverResult.UNSAT)
        }
    }

    @Test
    fun skeyMinusSkey(){
        runBlocking {
            val mockScene = mockk<IScene>()
            every { mockScene.getContracts() } returns listOf()
            val prog = TACProgramBuilder {
                a assign SimpleHash(number(32), listOf(bS), HashFamily.Keccack)
                c assign SimpleHash(number(32), listOf(dS), HashFamily.Keccack)
                k assign Sub(cS, aS)
                x assign Gt(kS, number(0))
                assert(TACSymbol.True) // just checking for non-crashing behavior
            }.code
            val res = TACVerifier.verify(mockScene, prog, DummyLiveStatsReporter)
            assertTrue(res.firstExampleInfo.solverResult == SolverResult.UNSAT)
        }
    }

    /* .. also keeping these for later tests
    private fun runAnalysis(
        src: String,
        solc: String
    ): Set<StorageHashAxiomGeneratorPlainInjectivity.PIHashExpression> {
        val method = loadTestMethod(src, solc, false)
        val m1 = ContractUtils.transformMethod(method as TACMethod, ContractClass.standardProverMethodTransforms)
        return runAnalysis(m1.code as CoreTACProgram)
    }


    private fun runAnalysis(ctp: CoreTACProgram): Set<StorageHashAxiomGeneratorPlainInjectivity.PIHashExpression> {
        val simple = AbstractTACChecker.toSimpleSimpleSSA(ctp)
        val graph = TACCommandGraph(simple)
        val skeyDetectionResult = SkeyDetection(graph).result
        val lxf = LExpressionFactory()
        val lexp: TACExpr.() -> LExpression = { ToLExpression(this, lxf, ctp.symbolTable, meta = null) }
        return skeyDetectionResult.getPiHashExpressions(lxf, lexp)
    }
     */

}
