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

package preprocessing

import annotations.PollutesGlobalState
import bridge.ContractInstanceInSDC
import bridge.SingleDeployedContract
import bridge.VerificationQuery
import bridge.VerificationQueryType
import config.*
import datastructures.stdcollections.*
import disassembler.DisassembledEVMBytecode
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import log.*
import scene.*
import spec.CVL
import spec.CVLAstBuilder
import spec.DummyTypeResolver
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import java.io.File
import java.math.BigInteger
import kotlin.system.exitProcess
import tac.*
import utils.*
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok

/**
 * This class loads contracts, but does not care about the contracts' actual code.
 * Used for syntax checking, LSP and can run in a standalone jar without the verifier
 */
object ProverInputPreprocessor {
    private val json = Json { ignoreUnknownKeys = true }

    class BasicContractSource(val file: String) : IContractSource, IContractLoader {
        private val buildResources by lazy {
            File(file).readText().let {
                json.decodeFromString(
                    MapSerializer(String.serializer(), SingleDeployedContract.serializer()),
                    it
                )
            }
        }

        private val instances by lazy {
            buildResources.mapNotNull { (_, v) ->
                v.contracts.firstOrNull {
                    it.address == v.primary_contract_address
                }
            }.map { contractInstanceInSDC ->
                contractInstanceInSDC.copy(
                    is_static_address = contractInstanceInSDC.is_static_address,
                    state = mapOf(),
                    srcmap = "",
                    file = "",
                    bytecode = "",
                    constructorBytecode = "",
                )
            }
        }

        override fun load(sdc: ContractInstanceInSDC, cache: IPerContractClassCache): IContractClass {
            return object : IContractClass, IContractWithSource {
                override fun getConstructor(): ITACMethod? = null

                override fun getMethodBySigHash(sig: BigInteger): ITACMethod? = null

                override fun getMethodByUniqueAttribute(attr: MethodAttribute.Unique): ITACMethod? = null

                override val storage: IStorageInfo
                    get() = DummyStorageInfo

                override fun getStorageLayout(): TACStorageLayout? = sdc.storageLayout?.toTACStorageLayout()

                override fun getTransientStorageLayout(): TACStorageLayout? = sdc.transientStorageLayout?.toTACStorageLayout()

                override val transientStorage: IStorageInfo
                    get() = DummyStorageInfo

                override fun getDeclaredMethods(): Collection<ITACMethod> = listOf()

                override fun mapMethods(sort: IScene.MapSort, scene: IScene, p: (IScene, ITACMethod) -> ICoreTACProgram) {
                }

                override fun fork(): IContractClass {
                    return this
                }

                override val instanceId: BigInteger
                    get() = sdc.address
                override val name: String
                    get() = sdc.name
                override val methods: Map<BigInteger?, ITACMethod>
                    get() = emptyMap()
                override val wholeContractMethod: ITACMethod?
                    get() = null
                override val constructorMethod: ITACMethod?
                    get() = null
                override val bytecode: DisassembledEVMBytecode?
                    get() = null
                override val constructorBytecode: DisassembledEVMBytecode?
                    get() = null
                override val src: ContractInstanceInSDC
                    get() = sdc
                override val addressSym: ITACSymbol
                    get() = throw UnsupportedOperationException()
            }
        }

        override fun instances(): List<ContractInstanceInSDC> = instances

        override fun aliases(): List<Set<BigInteger>> = instances.map { setOf(it.address) }
    }

    private fun getCertoraVerifyFile(filepath: String) = File(filepath).readText().let {
        json.decodeFromString(VerificationQuery.serializer(), it)
    }

    private fun withSpecAndContractBuildInformation(contractSource: BasicContractSource, verify: VerificationQuery): CollectingResult<CVL, CVLError> {
        val scene = TrivialSceneFactory.getScene(contractSource)
        println("Type checking ${verify.primary_contract}")
        return CVLAstBuilder(
            cvlInput = verify.toCVLInput(),
            contracts = contractSource.instances(),
            mainContract = (scene.getContract(SolidityContract(verify.primary_contract)) as IContractWithSource).src,
            scene = scene,
            runIdFactory = null
        ).build()

    }

    private fun withSpec(verify: VerificationQuery): CollectingResult<CVLAst, CVLError> {
        val cvlAst = verify.toCVLInput().getRawCVLAst(DummyTypeResolver(SolidityContract(verify.primary_contract)))
            .bind { cvlAst ->
                validateRuleChoices(cvlAst).map { cvlAst }
            }
        return cvlAst
    }

    private fun printFilteredRules(rules: Set<String>) {
        if (Config.ListRules.get()) {
            Config.getRuleChoices(rules).forEach { Logger.always(it, false) }
        }
    }

    /**
     * A template of a main function for a Jar using this object's preprocessing capabilities
     * can run in one of three modes:
     * - getting certora build file and certora verify file - run on both
     * - getting only spec file - run only parse function
     * - As part of the flow of the LSP rust server, ASTExtraction.jar is run while serialization of .certora_verify.json
     *   is given via stdin of the jar process. No command line arguments are passed in.
     */
    @PollutesGlobalState
    fun _main(args: Array<String>, astCb: (CVLAst) -> Unit) {
        try {
            CommandLineParser.registerConfigurations()
            CommandLineParser.processArgsAndConfig(args)
            val verifyFileName = Config.prependInternalDir(CERTORA_VERIFY_FILE_PATH)
            val hasVerifyFile = File(File(verifyFileName).absolutePath).exists()

            val isTypeChecking = Config.IsTypeChecking.get()
            val result = if (isTypeChecking) {
                val buildFileName = Config.prependInternalDir(CERTORA_BUILD_FILE_PATH)
                val hasBuildFile = File(File(buildFileName).absolutePath).exists()

                val verify = getCertoraVerifyFile(verifyFileName)
                val contractSource = BasicContractSource(buildFileName)
                if (verify.type == VerificationQueryType.spec && hasBuildFile) {
                    val res = withSpecAndContractBuildInformation(contractSource, verify)
                    res.map { ast ->
                        // In this ast all rules generated from invariants and built-ins are already present in the list
                        // of `rules`, so no need to explicitly add them here
                        printFilteredRules(ast.rules.mapToSet { it.declarationId })
                        ast
                    }
                } else {
                    if(!hasBuildFile){
                        System.err.println("Could not find build file - type checking failed")
                    }
                    ok
                }
            } else if (hasVerifyFile) {
                val verify = getCertoraVerifyFile(verifyFileName)
                // in bytecode mode, we pass the bytecode_spec directly to the prover,
                // and the local typechecker gets nothing in .certora_verify file to check.
                // in the past we just passed an empty array, but now we need a different mechanism
                // to know that there's nothing to check, and there is just a nullability check
                // TODO CERT-1908
                if (verify.type != null) {
                    withSpec(verify).map { ast ->
                        printFilteredRules(
                            ast.rules.mapToSet { it.declarationId } +
                            ast.invs.mapToSet { it.id } +
                            ast.useDeclarations.builtInRulesInUse.mapToSet { it.id }
                        )
                        astCb(ast)
                    }
                } else {
                    ok
                }
            } else {
                System.err.println("Bad input to CVL typechecker: ${args.map { it }}")
                ok
            }
            result.resultOrExitProcess(1) { errors ->
                val groupedByFile = errors.filter { it.location is Range.Range }.groupBy { (it.location as Range.Range).specFile }
                val nonRangeErrors = errors.filter { it.location !is Range.Range }

                groupedByFile.forEachEntry { (specFile, errorsForSpecFile) ->
                    CVLError.printErrors(errorsForSpecFile, "Found errors in ${specFile}:")
                }
                if (nonRangeErrors.isNotEmpty()) {
                    CVLError.printErrors(nonRangeErrors, "Additional errors:")
                }
            }
            exitProcess(0)
        } catch (e: CertoraException) {
            System.err.println(e)
            exitProcess(1)
        } catch (e: Throwable) {
            System.err.println(e)
            e.printStackTrace()
            exitProcess(1)
        } finally {
            exitProcess(1)
        }
    }
}
