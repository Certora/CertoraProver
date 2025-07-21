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

package analysis.abi

import analysis.icfg.Inliner
import infra.CVLFlow
import infra.CertoraBuild
import infra.CertoraBuild.Companion.EVMCompiler
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.opentest4j.TestAbortedException
import report.DummyLiveStatsReporter
import scene.IScene
import scene.ProverQuery
import scene.Scene
import solver.SolverResult
import spec.cvlast.SolidityContract
import utils.CertoraException
import utils.CollectingResult.Companion.lift
import verifier.IntegrativeChecker
import verifier.TACVerifier

object DirectPassingTestPipeline {
    suspend fun specTest(
        contractName: String,
        testFn: String,
        contract: String,
        spec: String,
        solc: String,
        withRecords: (scene: IScene, Collection<Inliner.CallStack.PushRecord>) -> Unit,
        onDPSetupFailure: (scene: IScene, Inliner.DirectPassingSetupFailed) -> Unit,
        msg: () -> String
    ) {
        val flow = CVLFlow()
        val build = CertoraBuild.withGeneratedContract(
            contract = contract,
            compiler = EVMCompiler.Solidity(solc),
            primaryContractName = contractName
        )
        val (scene, query) = build
            .getProverQueryWithSceneFromText(spec, contractName)
            .onError { build.close() }
            .force()

                try {
                    val checkable = flow.transformResultsToTACs(query.lift())
                    val pushes = DirectPassingTest.getPushRecordsFrom(
                        scene as Scene,
                        contractName,
                        testFn,
                    )

                    withRecords(scene, pushes)

                    for (check in checkable) {
                        val res = runBlocking {
                            TACVerifier.verify(scene.toIdentifiers(), check, DummyLiveStatsReporter)
                        }
                        Assertions.assertEquals(SolverResult.UNSAT, res.finalResult) {
                            "${msg()}: Failed:\n${contract}\n${spec}"
                        }
                    }
                } catch (e : Inliner.DirectPassingSetupFailed) {
                    onDPSetupFailure(scene, e)
                } catch (e : CertoraException) {
                    Assertions.assertTrue(e.cause is Inliner.DirectPassingSetupFailed) {
                        "${msg()}: unexpected exception ${e} with cause ${e.cause}|\n${contract}\n${spec}"
                    }
                    onDPSetupFailure(scene, e.cause as Inliner.DirectPassingSetupFailed)
                } catch (e : Exception) {
                    if (e is TestAbortedException) {
                        throw e
                    }
                    Assertions.fail {
                        "${msg()}: unexpected exception ${e} with cause ${e.cause}\n${contract}\n${spec}"
                    }
                } finally {
                    build.close()
                }
    }

    /* check that we see [expect] non-serialization inline records */
    fun checkExpectedPushRecordCount(scene: Scene, contractName: String, methodName: String, expect: Int, msg: () -> String) {
        IntegrativeChecker.runInitialTransformations(scene)
        val pushes = DirectPassingTest.getPushRecordsFrom(scene, contractName, methodName)

        Assertions.assertEquals(expect, pushes.size)
        pushes.forEach {
            Assertions.assertTrue(it.convention != Inliner.CallConventionType.Serialization) {
                "$it [${msg()}]"
            }
        }
    }

}
