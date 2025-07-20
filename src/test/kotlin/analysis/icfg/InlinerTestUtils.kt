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

package analysis.icfg

import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import config.Config
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import report.DummyLiveStatsReporter
import scene.*
import solver.SolverResult
import spec.CVLKeywords
import spec.toVar
import tac.CallId
import tac.Tag
import utils.*
import vc.data.*
import verifier.IntegrativeChecker
import verifier.TACVerifier
import verifier.Verifier
import java.math.BigInteger

object InlinerTestUtils {
    private val insertLabel = BigInteger.valueOf(8888)

    data class InsertMethodData(
        val scene: Scene,
        val contract: IContractClass,
        val testMethod: ITACMethod,
        val swag: ITACMethod,
        val getFoo: ITACMethod,
        val viewConstant: ITACMethod,
        val viewMemoryArg: ITACMethod,
        val twoParams: ITACMethod,
        val blazeit: ITACMethod,
        val recurse10: ITACMethod,
        val loopTimes10: ITACMethod,
        val getBalance: ITACMethod,
        val lnot: ITACMethod,
        val updateBookTitle: ITACMethod,
        val getBookId: ITACMethod,
        val updateBookId: ITACMethod,
        val functionPos: CmdPointer,
        val assertPos: CmdPointer,
    )

    fun preloadTest(solc: String): InsertMethodData {
        val scene = InlinerTest.loadScene("icfg/ChangeStateWithView.sol", solc)
        try {
            mockkObject(Config.IsAssumeUnwindCondForLoops)
            every { Config.IsAssumeUnwindCondForLoops.get() } returns true
            // Having an AssertionQuery that has no meaning is a bit of a hack, to set up this unit-test framework
            IntegrativeChecker.runInitialTransformations(scene)
        } finally {
            unmockkObject(Config.IsAssumeUnwindCondForLoops)
        }
        val contract = scene.getContracts().first()
        val testMethod = contract.getStandardMethods().find { it.name == "test" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.isEmpty())
            Assertions.assertFalse(it.evmExternalMethodInfo!!.stateMutability.isView)
        }
        val swagFunc = contract.getStandardMethods().find { it.name == "swag" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 1 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }
        val viewConstant = contract.getStandardMethods().find { it.name == "constantReturn" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 2 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }
        val viewMemoryArg = contract.getStandardMethods().find { it.name == "memoryArgs" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 5 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }
        // Cant use a constant function name for generated view function of foo
        val getFoo = contract.getStandardMethods()
            .filter { it.evmExternalMethodInfo!!.argTypes.isEmpty() && it.evmExternalMethodInfo!!.stateMutability.isView }
            .let {
                Assertions.assertTrue(it.size == 1)
                Assertions.assertTrue(it.first().name.contains("foo"))
                it.first()
            }

        val twoParams = contract.getStandardMethods().find { it.name == "twoParams" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 2 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val blazeit = contract.getStandardMethods().find { it.name == "blazeit" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 1 && !it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val recurseTimes10 = contract.getStandardMethods().find { it.name == "recurseTimes10" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 1 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val loopTimes10 = contract.getStandardMethods().find { it.name == "loopTimes10" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 1 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val getBalance = contract.getStandardMethods().find { it.name == "getBalance" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 2 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val lnot = contract.getStandardMethods().find { it.name == "lnot" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 1 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val updateBookTitle = contract.getStandardMethods().find { it.name == "updateBookTitle" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 1 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val getBookId = contract.getStandardMethods().find { it.name == "getBookId" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 1 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val updateBookId = contract.getStandardMethods().find { it.name == "updateBookId" }!!.also {
            Assertions.assertTrue(it.evmExternalMethodInfo!!.argTypes.size == 1 && it.evmExternalMethodInfo!!.stateMutability.isView)
        }

        val ctp = testMethod.code as CoreTACProgram
        val insertPtrs = getInsertPositions(ctp).sorted()
        Assertions.assertEquals(insertPtrs.size, 2)
        val functionPos = insertPtrs.first()
        val assertPos = insertPtrs.last()
        return InsertMethodData(
            scene,
            contract,
            testMethod,
            swagFunc,
            getFoo,
            viewConstant,
            viewMemoryArg,
            twoParams,
            blazeit,
            recurseTimes10,
            loopTimes10,
            getBalance,
            lnot,
            updateBookTitle,
            getBookId,
            updateBookId,
            functionPos,
            assertPos,
        )
    }

    /**
     * Find positions implanted in the code ahead of time to be used in the test.
     */
    private fun getInsertPositions(code: CoreTACProgram): Set<CmdPointer> {
        return code.analysisCache.graph.commands.filter { cmd ->
            val value: BigInteger? = when (val simple = cmd.cmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    if (simple.rhs is TACExpr.Sym.Const) {
                        (simple.rhs as TACExpr.Sym.Const).s.value
                    } else {
                        null
                    }
                }

                is TACCmd.Simple.AssigningCmd.ByteStore -> {
                    if (simple.loc is TACSymbol.Const && simple.value is TACSymbol.Const) {
                        simple.loc.asSym().evalAsConst()
                    } else {
                        null
                    }
                }

                else -> {
                    null
                }
            }
            value == insertLabel
        }.map { it.ptr }.toSet()
    }

    /**
     * Utility class to group together the callId and useful variables.
     */
    data class InsertedCallParams(
        val callId: CallId,
        val inputSize: TACSymbol.Var,
        val inputMem: TACSymbol.Var,
        val returnBuffer: TACSymbol.Var,
        val returnSize: TACSymbol.Var,
    )

    /**
     * All the tests for inserting a method ride on the same framework. There is a main function
     * that will have a location for inserting a view function and a location for assertions.
     * This function takes [testData], and intrumentation functions [assertionCreator] and [preambleCreator],
     * and adds the instrumentation around the inserted functions [functionsToInsert].
     * This is very generic, but there are a few wrappers in these utils to help, for example:
     * [runSanity], [runDifferentInputDifferentOutput], and so on.
     */
    internal fun insertAndRun(
        testData: InsertMethodData,
        functionsToInsert: List<ITACMethod>,
        assertionCreator: (SimplePatchingProgram, CmdPointer, List<InsertedCallParams>) -> Unit,
        preambleCreator: ((SimplePatchingProgram, CmdPointer, List<InsertedCallParams>) -> Unit)? = null,
    ): Verifier.VerifierResult {
        var ctp = testData.testMethod.fork().code as CoreTACProgram
        val calleeContractAddress = testData.contract.addressSym as TACSymbol
        val forkedToInsert = functionsToInsert.map { it.fork() }

        require(forkedToInsert.isNotEmpty()) { "Only use this function to insert functions in function position" }
        // Multiply function position. This is like having a "buffer" for later instrumentations steps,
        // so they could replace the same cmdPointer more than once.
        // For each future replacement of the cmdPointer, we create a NopCmd.
        val (preamblePtr: CmdPointer?, funsPtrs: List<CmdPointer>) =
            createPositionsForInstrumentation(ctp, forkedToInsert, preambleCreator, testData).let {
                ctp = it.third
                (it.first to it.second)
            }

        val callParams = mutableListOf<InsertedCallParams>()
        val lastReverted = CVLKeywords.lastReverted.toVar(Tag.Bool)
        ctp = with(ctp.toPatchingProgram()) {
            for ((ptr, method) in funsPtrs.zip(forkedToInsert)) {
                val meth = (method as TACMethod).let {
                    TACMethod(
                        (it.code as CoreTACProgram).appendToSinks(CommandWithRequiredDecls(TACCmd.Simple.AssumeNotCmd(lastReverted))),
                        it.getContainingContract(),
                        it.meta,
                        it.attribute,
                        it.evmExternalMethodInfo,
                        it.sigHash,
                        it.name,
                        it.soliditySignature,
                        it.calldataEncoding
                    )
                }
                val callId = Inliner.insertMethodCall(
                    ctp,
                    meth,
                    calleeContractAddress,
                    this,
                    ptr
                )
                val inputSize = Inliner.inputLenVar(callId)
                val inputMem = Inliner.inputBuffVar(callId)
                val returnBuffer = TACKeyword.RETURNDATA.toVar(callId)
                val returnSize = TACKeyword.RETURN_SIZE.toVar(callId)
                callParams.add(
                    InsertedCallParams(
                        callId,
                        inputSize,
                        inputMem,
                        returnBuffer,
                        returnSize
                    )
                )
            }

            preambleCreator?.let { it(this, preamblePtr!!, callParams) }
            assertionCreator(this, testData.assertPos, callParams)

            this.toCode(ctp)
        }

        val res = runBlocking {
            TACVerifier.verify(testData.scene.toIdentifiers(), ctp, DummyLiveStatsReporter)
        }

        return res
    }

    private fun createPositionsForInstrumentation(
        ctp: CoreTACProgram,
        forkedToInsert: List<ITACMethod>,
        preambleCreator: ((SimplePatchingProgram, CmdPointer, List<InsertedCallParams>) -> Unit)?,
        testData: InsertMethodData
    ): Triple<CmdPointer?, List<CmdPointer>, CoreTACProgram> {
        var ctp1 = ctp
        ctp1 = with(ctp1.toPatchingProgram()) {
            val replaceWith = forkedToInsert.map { TACCmd.Simple.NopCmd }.let {
                if (preambleCreator != null) {
                    it + TACCmd.Simple.NopCmd
                } else {
                    it
                }
            }
            replaceCommand(
                testData.functionPos, replaceWith
            )
            toCode(ctp1)
        }

        val graph = ctp1.analysisCache.graph
        val preamblePtr = preambleCreator?.let { testData.functionPos }
        val funsPtrs = let {
            val res = mutableListOf(preamblePtr?.let { graph.succ(it).first() } ?: testData.functionPos)
            Assertions.assertEquals(graph.elab(testData.functionPos).cmd, TACCmd.Simple.NopCmd)
            for (i in 0 until forkedToInsert.size - 1) {
                res.add(graph.succ(res.last()).first())
                Assertions.assertEquals(graph.elab(res.last()).cmd, TACCmd.Simple.NopCmd)
            }
            res.toList()
        }
        return Triple(preamblePtr, funsPtrs, ctp1)
    }

    /**
     * A helper function that will create a transformation lambda that patches code to make sure the input of the
     * callParams at positions [i1] and [i2] are equal. We also make sure return buffers are equal to overcome
     * different havocs in positions not written to.
     */
    internal fun inputEqualityInstrumentor(i1: Int, i2: Int) =
        { patching: SimplePatchingProgram, where: CmdPointer, callParams: List<InsertedCallParams> ->
            with(patching) {
                replaceCommand(
                    where,
                    listOf(
                        TACCmd.Simple.AssumeExpCmd(
                            TACExpr.BinRel.Eq(
                                callParams[i1].inputSize.asSym(),
                                callParams[i2].inputSize.asSym()
                            )
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(callParams[1].inputMem, callParams[0].inputMem),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(callParams[1].returnBuffer, callParams[0].returnBuffer),
                    )
                )
            }
        }

    /**
     * Instrument and assert that inserting [function] will keep final assert reachable.
     */
    internal fun runSanity(testData: InsertMethodData, function: ITACMethod) {
        val resPass = insertAndRun(testData, listOf(function), { _, _, _ -> })
        Assertions.assertEquals(SolverResult.UNSAT, resPass.finalResult)
        val resFail = insertAndRun(testData, listOf(function), { patching, where, _ ->
            with(patching) {
                replaceCommand(
                    where, listOf(
                        TACCmd.Simple.AssertCmd(false.asTACSymbol(), "Sanity"),
                    )
                )
            }
        })
        Assertions.assertEquals(SolverResult.SAT, resFail.finalResult)
    }

    /**
     * Insert [function1] and [function2], and add a TAC assertion the output is equal.
     */
    private fun runSameInput(
        testData: InsertMethodData,
        function1: ITACMethod,
        function2: ITACMethod
    ): Verifier.VerifierResult {
        return insertAndRun(
            testData, listOf(function1, function2),
            preambleCreator = inputEqualityInstrumentor(0, 1),
            assertionCreator = outputEqualityInstrumentor(0, 1, true)
        )
    }

    /**
     * Instrument and assert that: [function1] (x) = [function2] (x)
     */
    internal fun runSameInputSameOutput(testData: InsertMethodData, function1: ITACMethod, function2: ITACMethod) {
        val res = runSameInput(testData, function1, function2)
        Assertions.assertTrue(res.finalResult == SolverResult.UNSAT) {
            "The model is: ${res.firstExampleInfo.model}"
        }
    }

    /**
     * Instrument and assert that: [function1] (x) != [function2] (x)
     */
    internal fun runSameInputDifferentOutput(testData: InsertMethodData, function1: ITACMethod, function2: ITACMethod) {
        val res = runSameInput(testData, function1, function2)
        Assertions.assertTrue(res.finalResult == SolverResult.SAT) {
            "Output is always equal, but was expecting " +
                "functions to differ"
        }
    }

    /**
     * Instrument and assert that: [function1] (x) != [function2] (y)
     */
    internal fun runDifferentInputDifferentOutput(
        testData: InsertMethodData,
        function1: ITACMethod,
        function2: ITACMethod
    ) {
        val res = insertAndRun(
            testData, listOf(function1, function2),
            assertionCreator = outputEqualityInstrumentor(0, 1, false)
        )
        Assertions.assertTrue(res.finalResult == SolverResult.SAT) {
            "The model is: ${res.firstExampleInfo.model}"
        }
    }

    /**
     * A helper function that will create a transformation to add an output comparison of the callParams at positions
     * [i1] and [i2].
     */
    private fun outputComparisonInstrumentor(i1: Int, i2: Int, compareSize: Boolean, shouldEqual: Boolean):
            (SimplePatchingProgram, CmdPointer, List<InsertedCallParams>) -> Unit {
        return { patching, where, callParams ->
            with(patching) {
                val assertVar = TACSymbol.Var("testAssertVar", Tag.Bool)
                val assertSizeVar = TACSymbol.Var("testAssertSizeVar", Tag.Bool)
                val selectVar = TACSymbol.Var("testSelectVar", Tag.Bit256)
                val indexVar = TACSymbol.Var("testIndexVar", Tag.Bit256)
                val returnBuffer1 = callParams[i1].returnBuffer
                val returnBuffer2 = callParams[i2].returnBuffer
                val returnSize1 = callParams[i1].returnSize
                val returnSize2 = callParams[i2].returnSize
                addVarDecls(
                    setOf(
                        assertVar, selectVar, returnBuffer1, returnBuffer2, indexVar, returnSize1, returnSize2,
                        assertSizeVar
                    )
                )
                val setupCmds: MutableList<TACCmd.Simple> = mutableListOf()
                if (compareSize) {
                    setupCmds.addAll(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                assertSizeVar,
                                TACExpr.BinRel.Eq(returnSize1.asSym(), returnSize2.asSym())
                            ),
                            TACCmd.Simple.AssertCmd(assertSizeVar, "Return size not equal"),
                        )
                    )
                }
                var comparison: TACExpr = TACExpr.BinRel.Eq(
                    TACExpr.Select(returnBuffer1.asSym(), indexVar.asSym()),
                    TACExpr.Select(returnBuffer2.asSym(), indexVar.asSym()),
                )
                if (!shouldEqual) {
                    comparison = TACExpr.UnaryExp.LNot(comparison)
                }
                val assertText = let {
                    val additionalText = if (shouldEqual) {
                        ""
                    } else {
                        " not"
                    }
                    "View function result should$additionalText be equal with same params and state"
                }
                replaceCommand(
                    where, setupCmds + listOf(
                        TACCmd.Simple.AssumeExpCmd(TACExpr.BinRel.Lt(indexVar.asSym(), returnSize1.asSym())),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            assertVar,
                            comparison
                        ),
                        TACCmd.Simple.AssertCmd(
                            assertVar, assertText
                        ),
                    )
                )
            }
        }
    }

    /**
     * Wrapper for [outputComparisonInstrumentor]. Returns an intrumenting function that will assert output
     * of callId i1 and callId i2 are equal.
     */
    private fun outputEqualityInstrumentor(i1: Int, i2: Int, assertSize: Boolean):
            (SimplePatchingProgram, CmdPointer, List<InsertedCallParams>) -> Unit {
        return outputComparisonInstrumentor(i1, i2, assertSize, true)
    }

    /**
     * Wrapper for [outputComparisonInstrumentor]. Same as [outputEqualityInstrumentor], but will assert
     * inequality.
     */
    internal fun outputInequalityInstrumentor(i1: Int, i2: Int, assertSize: Boolean):
            (SimplePatchingProgram, CmdPointer, List<InsertedCallParams>) -> Unit {
        return outputComparisonInstrumentor(i1, i2, assertSize, false)
    }
}
