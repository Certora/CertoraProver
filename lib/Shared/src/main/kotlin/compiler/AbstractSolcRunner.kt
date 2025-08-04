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

package compiler

import bridge.ContractInstanceInSDC
import bridge.SourceLanguage
import compiler.data.*
import kotlinx.serialization.json.JsonObject
import utils.*
import java.io.File
import java.math.BigInteger
import java.nio.file.Path

abstract class AbstractSolcRunner(
    val address : BigInteger = BigInteger.TWO.pow(24) /* safety buffer from precompiled contracts*/,
    val optimize : Boolean = false,
    val runDir : Path? = null
) {
    sealed class Source {
        data class Literal(val contractSource: String) : Source()
        data class FromFile(val path: String) : Source()
    }

    val solOutput by lazy {
        this.compileContracts()
    }

    val result by lazy {
        this.postProcess(solOutput)
    }

    fun ast(file: String) : JsonObject? {
        return solOutput?.sources?.get(file)?.ast
    }

    val errors : List<String> by lazy {
        result.toValue({ listOf<String>() }, { it })
    }

    val contractInstances : List<ContractInstanceInSDC> by lazy {
        result.toValue({ it }, {listOf() })
    }

    protected abstract fun getContractSource() : Map<String, Source>
    protected abstract fun getSolcExecutable(): String
    protected open fun getAllowedPaths() : List<String> = listOf()

    private fun compileContracts() : Output? {
        val allowedPaths = getAllowedPaths().toMutableList()
        val sources = getContractSource().map {
            it.key to when(val x = it.value) {
                is Source.Literal -> ContractSource(content = x.contractSource)
                is Source.FromFile -> {
                    val f = File(x.path)
                    f.parentFile?.let { parentFile -> allowedPaths.add(parentFile.absolutePath) }
                    ContractSource(srcFile = f)
                }
            }
        }.toMap()
        val solidityInput = Input(
                sources = sources,
                settings = SolcSettings(
                        outputSelection = mapOf(
                                ALL_FILES to mapOf(
                                        ALL_CONTRACTS to listOf(
                                                "abi",
                                                "evm.deployedBytecode.object",
                                                "evm.bytecode.object",
                                                "evm.methodIdentifiers",
                                                "evm.deployedBytecode.sourceMap",
                                                "storageLayout",
                                                "transientStorageLayout"
                                        ),
                                        WHOLE_FILE_SELECTOR to listOf(
                                            "ast"
                                        )
                                )
                        ),
                        optimizer = SolidityOptimization(enabled = optimize)
                )
        )
        return StandardSolcRunner(this.getSolcExecutable(), allowedPaths, runDir= runDir).run(solidityInput)
    }

    private fun postProcess(res: Output?) : Either<List<ContractInstanceInSDC>, List<String>> {
        if (res == null) {
            return Either.Left(listOf())
        }
        if(res.errors.any {
            it.severity == "error"
        }) {
            // oh well
            return res.errors.filter { it.severity == "error" }.map {
                it.message
            }.toRight()
        }
        var i = 0
        return res.contracts.flatMap { (_, contracts) ->
            contracts.mapNotNull { (contractName, contract) ->
                if(contract.evm.bytecode.`object` == "") {
                    return@mapNotNull null
                }
                val methods = contract.abi
                    .filter { it.type == "function" }
                    .map {
                    val sig = it.toCanonicalSig()
                    it.toMethod(contract.evm.methodIdentifiers[sig] ?: "")
                }

                ContractInstanceInSDC(
                    name = contractName,
                    original_file = "",
                    lang = SourceLanguage.Solidity,
                    file = "",
                    bytecode = contract.evm.deployedBytecode.`object`!!,
                    constructorBytecode = contract.evm.bytecode.`object`!!,
                    srcmap = contract.evm.deployedBytecode.sourceMap!!,
                    methods = methods,
                    address = address + (i++).toBigInteger(),
                    is_static_address = false,
                    storageLayout = contract.storageLayout,
                    transientStorageLayout = contract.transientStorageLayout,
                    solidityTypes = setOf(),
                    immutables = listOf(), // Figuring this one out is a little too much for this runner
                    sourceBytes = null,
                    internalFunctionStarts = listOf(),
                    internalFunctionHarnesses = mapOf()
                )
            }
        }.toLeft()
    }
}
