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

import analysis.CommandWithRequiredDecls
import analysis.EthereumVariables.calldata
import analysis.EthereumVariables.calldatasize
import datastructures.stdcollections.*
import evm.ECRECOVER_PRECOMPILED_ADDRESS
import evm.IDENTITY_PRECOMPILED_ADDRESS
import evm.MASK_SIZE
import evm.SHA256_PRECOMPILED_ADDRESS
import instrumentation.calls.ArgNum
import instrumentation.calls.CalldataByteRange
import instrumentation.calls.CalldataEncoding
import instrumentation.createEmptyProgram
import tac.*
import vc.data.*
import vc.data.tacexprutil.TACUnboundedHashingUtils
import java.io.Serializable
import java.math.BigInteger

/**
 * Dummy sound implementations for precopiled contracts.
 * Ethereum precompiled contracts listing can be found here: https://github.com/ethereum/go-ethereum/blob/master/core/vm/contracts.go
 * [based on geth code, up-to-date until Berlin hard fork]
 */
sealed class PrecompiledContractCode : Serializable {
    // address of the precompiled
    abstract val address: BigInteger

    // the implementation (sighash = 0x0, but really it has sighashSize = 0x0 as well so it's just a dummy value)
    abstract val contract: IContractClass
    val method: ITACMethod
        get() =
            contract.getWholeContract() ?: error("Precompiled contract ${contract.name} has no whole contract method")

    companion object {
        fun getPrecompiledImplemented(address: BigInteger) = when (address) {
            ECRECOVER_PRECOMPILED_ADDRESS -> PrecompiledContractCodeKnownInputSize.ecrecover
            SHA256_PRECOMPILED_ADDRESS -> PrecompiledContractCode.sha256
            IDENTITY_PRECOMPILED_ADDRESS -> PrecompiledContractCode.identity
            else -> null
        }

        fun getContractClassForPrecompiled(address: BigInteger, code: CoreTACProgram, calldataEncoding: CalldataEncoding) =
            object : MapBackedContractClass(
                instanceId = address,
                instanceIdIsStaticAddress = false,
                bytecode = null,
                constructorBytecode = null,
                name = ContractUniverse.ETHEREUM.getNameOfPrecompiledByAddress(address)!!,
                storageLayout = null,
                transientStorageLayout = null
            ) {
                override val methods: Map<BigInteger?, ITACMethod>
                    get() = mapOf()

                override val wholeContractMethod: ITACMethod
                    get() = onlyMethod

                val onlyMethod: ITACMethod = TACMethod(
                    _code = code,
                    _meta = MetaMap(),
                    _sighash = null,
                    _name = ContractUniverse.ETHEREUM.getNameOfPrecompiledByAddress(
                        address
                    )!!,
                    _containingContract = this,
                    _soliditySignature = null,
                    _calldataEncoding = calldataEncoding,
                    _attrib = MethodAttribute.Unique.Whole
                )

                override fun getDeclaredMethods(): Collection<ITACMethod> = listOf(onlyMethod)

                val emptyConstructor = TACMethod(
                    name,
                    createEmptyProgram(name),
                    this,
                    MetaMap(),
                    MethodAttribute.Unique.Constructor
                )

                override val constructorMethod: ITACMethod
                    get() = emptyConstructor
                override var storageInfoField: IStorageInfo
                    get() = StorageInfoWithReadTracker.empty()
                    set(@Suppress("UNUSED_PARAMETER") s) {}
                override var transientStorageInfoField: IStorageInfo
                    get() = StorageInfo.empty()
                    set(@Suppress("UNUSED_PARAMETER") s) {}

                override fun fork(): IContractClass {
                        return this
                }
            }
    }

    object sha256 : PrecompiledContractCode() {
        fun readResolve(): Any = sha256
        override val address = SHA256_PRECOMPILED_ADDRESS

        private val calldataEncoding = CalldataEncoding(
            byteOffsetToScalar = kotlin.collections.mapOf(),
            argNum = ArgNum.Unknown,
            valueTypesArgsOnly = false,
            sighashSize = BigInteger.ZERO
        )

        // dumbest solution is just calling keccak
        val code = TACKeyword.TMP(Tag.Bit256,"sha256_result").let { resultVar ->
            codeFromCommandWithVarDecls(
                StartBlock,
                TACUnboundedHashingUtils.fromByteMapRange(
                    hashFamily = HashFamily.Sha2,
                    length = calldatasize.asSym(),
                    map = calldataEncoding.calldataBase.s,
                    start = TACExpr.zeroExpr,
                ).let { exprWithCmds ->
                    CommandWithRequiredDecls(
                        exprWithCmds.cmdsToAdd + listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                resultVar,
                                rhs = exprWithCmds.exp
                            ),
                            TACCmd.Simple.ReturnSymCmd(resultVar)
                        ),
                        setOf(resultVar, calldatasize, calldataEncoding.calldataBase.s) + exprWithCmds.declsToAdd
                    )
                },
                ContractUniverse.ETHEREUM.getNameOfPrecompiledByAddress(address)!!
            )
        }

        override val contract =
            getContractClassForPrecompiled(address, code, calldataEncoding)


    }

    object identity : PrecompiledContractCode() {
        fun readResolve(): Any = identity
        override val address = IDENTITY_PRECOMPILED_ADDRESS

        private val calldataEncoding = CalldataEncoding(
            byteOffsetToScalar = kotlin.collections.mapOf(),
            argNum = ArgNum.Unknown,
            valueTypesArgsOnly = false,
            sighashSize = BigInteger.ZERO
        )

        val code = codeFromCommandWithVarDecls(
                StartBlock,
                CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.ReturnCmd(
                            BigInteger.ZERO.asTACSymbol(),
                            calldatasize,
                            calldata
                        )
                    ),
                    setOf(calldatasize, calldata)
                ),
                ContractUniverse.ETHEREUM.getNameOfPrecompiledByAddress(address)!!
            )

        override val contract =
            getContractClassForPrecompiled(address, code, calldataEncoding)
    }

    sealed class PrecompiledContractCodeKnownInputSize: PrecompiledContractCode() {
        // the expected calldatasize for the input, no meaning if hasExpectedInputSize is false
        abstract val expectedInputSize: BigInteger

        object ecrecover : PrecompiledContractCodeKnownInputSize() {
            fun readResolve(): Any = ecrecover
            override val address = ECRECOVER_PRECOMPILED_ADDRESS
            override val expectedInputSize = 0x80.toBigInteger()

            private val codeWithEncoding: Pair<CoreTACProgram, CalldataEncoding> =
                TACKeyword.TMP(Tag.Bit256, "ecrecover_digest").let { digestVar ->
                    TACKeyword.TMP(Tag.Bit256, "ecrecover_v").let { vVar ->
                        TACKeyword.TMP(Tag.Bit256, "ecrecover_r").let { rVar ->
                            TACKeyword.TMP(Tag.Bit256, "ecrecover_s").let { sVar ->
                                TACKeyword.TMP(Tag.Bit256, "ecrecover_result").let { resultVar ->
                                    TACKeyword.TMP(Tag.Bool, "ecrecover_resultAssume").let { resultVarAssume ->
                                        codeFromCommandWithVarDecls(
                                            StartBlock,
                                            CommandWithRequiredDecls(
                                                listOf(
                                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                                        resultVar,
                                                        TACExpr.Apply(
                                                            TACBuiltInFunction.PrecompiledECRecover.toTACFunctionSym(),
                                                            listOf(
                                                                digestVar.asSym(),
                                                                vVar.asSym(),
                                                                rVar.asSym(),
                                                                sVar.asSym()
                                                            ),
                                                            Tag.Bit256
                                                        )
                                                    ),
                                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                                        resultVarAssume,
                                                        TACExpr.BinRel.Le(
                                                            resultVar.asSym(),
                                                            MASK_SIZE(160).asTACSymbol().asSym()
                                                        )
                                                    ),
                                                    TACCmd.Simple.AssumeCmd(resultVarAssume, "ecrecover_resultAssume"),
                                                    TACCmd.Simple.ReturnSymCmd(resultVar)
                                                ),
                                                setOf(digestVar, vVar, rVar, sVar, resultVar, resultVarAssume)
                                            ),
                                            ContractUniverse.ETHEREUM.getNameOfPrecompiledByAddress(address)!!
                                        ) to CalldataEncoding(
                                            byteOffsetToScalar = listOf(
                                                CalldataByteRange(
                                                    BigInteger.ZERO,
                                                    (0x20 - 1).toBigInteger()
                                                ) to digestVar,
                                                CalldataByteRange(
                                                    0x20.toBigInteger(),
                                                    (0x40 - 1).toBigInteger()
                                                ) to vVar,
                                                CalldataByteRange(
                                                    0x40.toBigInteger(),
                                                    (0x60 - 1).toBigInteger()
                                                ) to rVar,
                                                CalldataByteRange(
                                                    0x60.toBigInteger(),
                                                    (0x80 - 1).toBigInteger()
                                                ) to sVar
                                            ).toMap(),
                                            argNum = ArgNum.Known(4.toBigInteger()),
                                            valueTypesArgsOnly = true,
                                            sighashSize = BigInteger.ZERO
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            override val contract =
                getContractClassForPrecompiled(address, codeWithEncoding.first, codeWithEncoding.second)


        }
    }

}
