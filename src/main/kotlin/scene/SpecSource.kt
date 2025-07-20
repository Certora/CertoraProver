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

package scene

import bridge.ContractInstanceInSDC
import bridge.NamedContractIdentifier
import bridge.VerificationQuery
import bridge.VerificationQueryType
import datastructures.stdcollections.*
import log.*
import rules.RuleFilterWithScene
import spec.CVL
import spec.CVLInput
import spec.CVLSource
import spec.CVLTestGenerator
import spec.cvlast.SolidityContract
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map

/**
 * see [VerificationQueryType] for the full explanation on this ugly class and construction.
 */
sealed class ProverQuery {
    abstract fun copyWithFilteredCVLRules(scene: IScene): ProverQuery

    data class EquivalenceQuery(
        val contractA: NamedContractIdentifier,
        val contractB: NamedContractIdentifier,
    ) : ProverQuery() {
        override fun copyWithFilteredCVLRules(scene: IScene): ProverQuery {
            return this
        }
    }

    sealed class CVLQuery: ProverQuery() {
        data class Single(val primaryContract: NamedContractIdentifier, val cvl: CVL): CVLQuery() {
            override fun copyWithFilteredCVLRules(scene: IScene): ProverQuery {
                return this.copy(cvl = RuleFilterWithScene(scene).copyFiltered(cvl))
            }
        }

    }
}

interface ISpecSource {

    fun getQuery(
        contractInstances: List<ContractInstanceInSDC>,
        scene: ICVLScene
    ): CollectingResult<ProverQuery, CVLError>

    fun getCVLQuery(
        contractName: NamedContractIdentifier,
        contractInstances: List<ContractInstanceInSDC>,
        scene: ICVLScene,
        input: CVLInput
    ): CollectingResult<ProverQuery.CVLQuery, CVLError> {
        val contractInstance = contractInstances.find { it.name == contractName.name }
            ?: error("but did not find the contract instance for loading the spec file in ${contractInstances.map { it.name }}")

        return CVLTestGenerator.parseCheckCVL(
            input,
            contractInstances,
            contractInstance,
            scene
        ).map {
            ProverQuery.CVLQuery.Single(
                contractName,
                it
            )
        }
    }
}



class CVLSpecSource(private val cvlFile: String, val contractName: NamedContractIdentifier) : ISpecSource {
    init {
        try {
            ArtifactManagerFactory().copyInputsToRootOfOutputDir(listOf(cvlFile))
        } catch (e: Exception) {
            Logger.alwaysError("Failed to copy CVL file $cvlFile")
        }
    }


    override fun getQuery(contractInstances: List<ContractInstanceInSDC>, scene: ICVLScene):
        CollectingResult<ProverQuery.CVLQuery, CVLError> = getCVLQuery(
        contractName,
        contractInstances,
        scene,
        CVLInput.Plain(CVLSource.File(cvlFile, cvlFile, false))
    )
}

const val TEST_SPEC_FILE_NAME = "Test CVL"

class CVLSpecTextSource(private val cvlText: String, val contractName: NamedContractIdentifier) : ISpecSource {
    override fun getQuery(contractInstances: List<ContractInstanceInSDC>, scene: ICVLScene):
        CollectingResult<ProverQuery.CVLQuery, CVLError> = getCVLQuery(
        contractName,
        contractInstances,
        scene,
        CVLInput.Plain(CVLSource.Raw(TEST_SPEC_FILE_NAME, cvlText, false))
    )
}

class CertoraBuilderSpecSource(val verificationConf: VerificationQuery) : ISpecSource {

    init {
        check(verificationConf.type != null) { "Did not expect non-existent type for verification query $verificationConf" }
    }
    override fun getQuery(contractInstances: List<ContractInstanceInSDC>, scene: ICVLScene): CollectingResult<ProverQuery, CVLError> {
        return when(verificationConf.type!!) {
            VerificationQueryType.spec -> {
                val cvlInput = verificationConf.toCVLInput()
                getCVLQuery(
                    NamedContractIdentifier(
                        verificationConf.primary_contract
                    ),
                    contractInstances,
                    scene,
                    cvlInput
                )
            }
            VerificationQueryType.equivalence -> {
                val contractA = SolidityContract(verificationConf.primary_contract)
                val contractB = SolidityContract(verificationConf.secondary_contract)
                ProverQuery.EquivalenceQuery(
                    contractA = contractA,
                    contractB = contractB,
                ).lift()
            }
        }
    }

}
