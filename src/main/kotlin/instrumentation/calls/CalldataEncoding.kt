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

package instrumentation.calls

import analysis.*
import analysis.icfg.CallInput
import analysis.icfg.CalldataDeterminismHelper
import analysis.icfg.DecomposedCallInputArg
import bridge.EVMExternalMethodInfo
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import evm.DEFAULT_SIGHASH_SIZE
import evm.DEFAULT_SIGHASH_SIZE_INT
import evm.EVM_WORD_SIZE
import instrumentation.calls.ArgNum.Companion.toArgNum
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import scene.ICalldataEncoding
import scene.ITACMethod
import tac.CallId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACSymbol.Companion.atSync
import vc.data.tacexprutil.ExprUnfolder
import vc.data.tacexprutil.getFreeVars
import java.io.Serializable
import java.math.BigInteger

/**
 * Inclusive [to]
 */
@Treapable
data class CalldataByteRange(val from: BigInteger, val to: BigInteger) : Serializable {
    fun size() = (to - from) + BigInteger.ONE
}


/**
 * Represents the number of arguments/parameters in a function's signature.
 * Note: this number does not include the "first four byte" argument, namely the sighash.
 */
@Treapable
sealed class ArgNum : Serializable {

    companion object {
        fun BigInteger.toArgNum() = Known(this)
        val unknown = Unknown
    }

    data class Known(val n: BigInteger) : ArgNum()

    object Unknown : ArgNum() {
        override fun hashCode() = hashObject(this)
        fun readResolve(): Any {
            return Unknown
        }
    }
}

data class CalldataEncoding(
    val calldataBase: TACExpr.Sym.Var = TACExprFactTypeCheckedOnlyPrimitives.Sym(TACKeyword.CALLDATA.toVar()) as TACExpr.Sym.Var,
    val calldataSize: TACExpr.Sym = TACKeyword.CALLDATASIZE.toVar().asSym(),
    val calldataOffset: TACExpr.Sym = TACSymbol.lift(0).asSym(),
    val byteOffsetToScalar: Map<CalldataByteRange, TACSymbol.Var>,
    val argNum: ArgNum,
    val valueTypesArgsOnly: Boolean,
    val sighashSize: BigInteger = DEFAULT_SIGHASH_SIZE
) : ICalldataEncoding, Serializable {
    init {
        check(calldataBase.tag == Tag.ByteMap) { "$calldataBase is with an unexpected tag: ${calldataBase.tag}" }
    }

    companion object {
        /*
          If an upper bound on size of the call data buffer is unknown and we are unable to statically determine the given input's size,
          we bound the long copy into the call data by [maxByteSize] bytes, which are an arbitrarily chosen, "big enough" number of words.
         */
        fun sighashCalldataRange(sighashSize: BigInteger) =
            CalldataByteRange(0.toBigInteger(), sighashSize - BigInteger.ONE)

        fun empty() = CalldataEncoding(
            byteOffsetToScalar = mapOf(),
            argNum = ArgNum.unknown,
            valueTypesArgsOnly = false,
            sighashSize = BigInteger.ZERO
        )

        fun calldataOf(methodCode: CoreTACProgram, evmMethodInfo: EVMExternalMethodInfo? = null): CalldataEncoding {

            val graph = TACCommandGraph(methodCode)
            val calldataScalars = getCalldataScalars(graph)
            val sighashSize = if (evmMethodInfo?.sigHash == null) { BigInteger.ZERO } else { DEFAULT_SIGHASH_SIZE }
            val byteOffsetToScalar: Map<CalldataByteRange, TACSymbol.Var> =
                getByteRangeToScalarMapping(calldataScalars, sighashSize = sighashSize, graph)

            return if (evmMethodInfo != null) {
                CalldataEncoding(
                    byteOffsetToScalar = byteOffsetToScalar,
                    argNum = (evmMethodInfo.argTypes.sumOf { it.getMinimumEncodingSize() } / EVM_WORD_SIZE).toArgNum(),
                    valueTypesArgsOnly = evmMethodInfo.argTypes.all { !it.isDynamicType() },
                    sighashSize = sighashSize
                )
            } else {
                CalldataEncoding(
                    byteOffsetToScalar = byteOffsetToScalar,
                    argNum = ArgNum.unknown,
                    valueTypesArgsOnly = false
                )
            }
        }

        /**
         * Returns a list of successfully scalarized function arguments.
         */
        private fun getCalldataScalars(graph: TACCommandGraph): List<Pair<CmdPointer,TACSymbol.Var>> {
            val toRet = mutableListOf<Pair<CmdPointer,TACSymbol.Var>>()
            graph.commands.forEach { c ->
                if (c.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    val rhsVar = c.cmd.rhs.getAs<TACExpr.Sym.Var>()?.s
                    if (rhsVar?.meta?.containsKey(TACMeta.IS_CALLDATA) == true) {
                        toRet.add(c.ptr to rhsVar)
                    }
                }
            }
            return toRet
        }


        /**
         * Returns a mapping of calldata byte ranges to successfully scalarized function arguments.
         */
        private fun getByteRangeToScalarMapping(calldataScalars: List<Pair<CmdPointer,TACSymbol.Var>>, sighashSize: BigInteger, graph: TACCommandGraph):
            Map<CalldataByteRange, TACSymbol.Var> {
            val sortedScalars = calldataScalars.sortedBy { it.second.meta[TACMeta.WORDMAP_KEY]!! }

            fun err() {
                CVTAlertReporter.reportAlert(
                    CVTAlertType.ANALYSIS,
                    CVTAlertSeverity.ERROR,
                    null,
                    "Encountered bad calldata ABI layout for ${graph.name}",
                    null
                )
            }

            return sortedScalars.map { (where, scalar) ->
                val offset = scalar.meta[TACMeta.WORDMAP_KEY]!!

                // if not in offset, and the offset is 32, this is _likely_ to be just a copy from a longcopy.
                // if the block where it appears contains a longcopy from calldata to memory starting copying calldata
                // from a constant offset == 0 we know it's just scalarized from that longcopy and likely to be for proxy patterns
                // we also check the dstOffset is 0 since reserved-space memory write scalarizations are the trigger for the calldata scalarization.
                // (we still prefer to overfit the check)
                if (offset == EVM_WORD_SIZE || offset == EVM_WORD_SIZE * BigInteger.TWO) {
                    if (graph.iterateRevBlock(where).all {
                            it.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.let {
                                it.cmd.srcBase.meta.containsKey(TACMeta.IS_CALLDATA) && it.cmd.dstBase.meta.containsKey(
                                    TACMeta.EVM_MEMORY
                                ) && graph.cache[MustBeConstantAnalysis].mustBeConstantAt(where, it.cmd.srcOffset) == BigInteger.ZERO
                                    && graph.cache[MustBeConstantAnalysis].mustBeConstantAt(where, it.cmd.dstOffset) == BigInteger.ZERO
                            } != true
                        }) {
                        err()
                        throw IllegalStateException("In ${graph.name}@${where} we saw a calldata read at offset 32, and it could not be related to a scalarized longcopy from calldata to memory, so unlikely to be a proxy pattern?")
                    }
                } else {
                    if (!(offset == BigInteger.ZERO || offset.mod(EVM_WORD_SIZE) == DEFAULT_SIGHASH_SIZE)) {
                        err()
                        throw IllegalStateException("In ${graph.name}@${where}: Invalid offset $offset: it is expected to be either 0 or (n*32)+4")
                    }
                }

                if (sighashSize > BigInteger.ZERO && offset == BigInteger.ZERO) {
                    sighashCalldataRange(sighashSize)
                } else {
                    CalldataByteRange(from = offset, offset + EVM_WORD_SIZE - BigInteger.ONE)
                } to scalar
            }.distinctBy { it.second }.toMap()
        }
    }

    val expectedCalldataSize: BigInteger? =
        when (argNum) {
            is ArgNum.Known -> {
                (this.argNum.n * 32.toBigInteger()) + sighashSize
            }
            is ArgNum.Unknown -> null
        }

    private fun funArgAtOffset(byteOffset: BigInteger): TACExpr {
        check(
            byteOffset == BigInteger.ZERO || (byteOffset >= sighashSize && byteOffset.mod(
                32.toBigInteger()
            ) == sighashSize)
        ) { "Expected to get a valid calldata offset, but instead got $byteOffset" }

        val byteRange = if (byteOffset == BigInteger.ZERO && sighashSize > 0.toBigInteger()) {
            sighashCalldataRange(sighashSize)
        } else {
            CalldataByteRange(byteOffset, byteOffset + 31.toBigInteger())
        }
        return if (byteRange in byteOffsetToScalar) {
            byteOffsetToScalar[byteRange]?.asSym() ?: error("$byteRange is expected to be in $byteOffsetToScalar")
        } else {
            TACExpr.Select(
                calldataBase, CallConvention.addK(calldataOffset, byteOffset)
            )
        }
    }

    // if we have primitive value types, and there is an expected calldatasize, the input size/lower bound
    // cannot be greater
    fun checkInputSizeForArgsOnly(input: CallInput): Boolean =
        Config.DisableInputSizeValidation.get() ||
            (!(this.valueTypesArgsOnly && expectedCalldataSize != null &&
                ((input.size is TACExpr.Sym.Const && input.size.s.value > expectedCalldataSize) ||
                    (input.inputSizeLowerBound != null && input.inputSizeLowerBound > expectedCalldataSize))))

    // if we have dynamic value types, and there is an expected calldatasize, the input size/lower bound
    // cannot be smaller
    // in simpler terms: OR:
    // 1. we have only value types args
    // 2. the expected calldatasize is null
    // 3a. if the input size is known then it's not smaller than the expected calldatasize
    // 3b. AND if the input size lower bound is given then it's not smaller than the expected calldatasize
    fun checkInputSizeForNonArgsOnly(input: CallInput): Boolean =
        Config.DisableInputSizeValidation.get() ||
            (!(!this.valueTypesArgsOnly && expectedCalldataSize != null &&
                ((input.size is TACExpr.Sym.Const && input.size.s.value < expectedCalldataSize) ||
                    (input.inputSizeLowerBound != null && input.inputSizeLowerBound < expectedCalldataSize))))

    fun feedInput(
        input: CallInput,
        callee: ITACMethod
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val ret = MutableCommandWithRequiredDecls<TACCmd.Simple>()

        check(checkInputSizeForArgsOnly(input))
        {
            "The size of the input ($input) to ${callee.soliditySignature ?: callee.name} is strictly greater than the " +
                "expected size of the calldata buffer (${expectedCalldataSize}). " +
                    "All function arguments are primitives value types."
        }

        check(checkInputSizeForNonArgsOnly(input))
        {
            "Expected the size of the input ($input) to be not smaller than the lower bound of the expected calldata size (${expectedCalldataSize}). " +
                    "The function has a dynamic type argument."
        }

        val inputSizeIsKnown: Boolean =
            input.size is TACExpr.Sym.Const || (this.valueTypesArgsOnly && expectedCalldataSize != null && input.inputSizeLowerBound == expectedCalldataSize)


        if (!inputSizeIsKnown) {
            val refinedInputSizeLowerBound: BigInteger? = input.inputSizeLowerBound

            if (refinedInputSizeLowerBound != null) {
                ExprUnfolder.unfoldPlusOneCmd("callSizeLowerBound", TACExprFactoryExtensions.run {
                    calldataSize ge refinedInputSizeLowerBound
                }) {
                    TACCmd.Simple.AssumeCmd(it.s, "callSizeLowerBound")
                }.let(ret::extend)
            }
        }

        // calldatasize checks
        // calldatasize
        ExprUnfolder.unfoldPlusOneCmd("calldataEq", TACExprFactoryExtensions.run {
            (calldataSize eq input.size).letIf(inputSizeIsKnown && input.size !is TACExpr.Sym.Const) {
                it and (calldataSize eq expectedCalldataSize!!)
            }
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "calldataEq")
        }.let(ret::extend)
        ret.extend(calldataSize.s)
        ret.extend(input.size.s)

        // Feed in the first 4 bytes (the function selector), if we know we have a selector,
        // but not if size is known to be 0
        if (sighashSize > BigInteger.ZERO && !(inputSizeIsKnown && input.size.s == TACSymbol.Zero)) {
            val sighashArg = funArgAtOffset(BigInteger.ZERO)
            val data = input.inputArgAtOffset(BigInteger.ZERO, sighashSize)
            ExprUnfolder.unfoldPlusOneCmd("sighashEq", TACExprFactoryExtensions.run {
                sighashArg eq data
            }) {
                TACCmd.Simple.AssumeCmd(it.s, "sighashEq")
            }.let(ret::extend)
            ret.extend(sighashArg.getFreeVars())
            val calleeSighash = callee.sigHash?.n
            if(calleeSighash != null) {
                val deterministicFor = CalldataDeterminismHelper.deterministicFor(3)
                ret.extend(deterministicFor, calldataBase.s)
                for (i in 1 until DEFAULT_SIGHASH_SIZE_INT) {
                    val actual = TACExpr.Select(calldataBase, i.asTACExpr)
                    val determinism = TACExpr.Select.buildMultiDimSelect(
                        deterministicFor.asSym(), listOf(
                            i.asTACExpr, input.size, calleeSighash.asTACExpr
                        )
                    )
                    ExprUnfolder.unfoldPlusOneCmd("sighashMidByte$i", TACExprFactoryExtensions.run {
                        actual eq determinism
                    }) {
                        TACCmd.Simple.AssumeCmd(it.s, "sighashMidByte$i")
                    }.let(ret::extend)
                }
            }
        }
        val start = sighashSize
        val inputStartVar = TACKeyword.TMP(Tag.Bit256, "inputStart")
        val totalSize = TACKeyword.TMP(Tag.Bit256, "payloadLength")
        ExprUnfolder.unfoldTo(TACExprFactoryExtensions.run { input.offset add start }, inputStartVar).let(ret::extend)
        ExprUnfolder.unfoldTo(TACExprFactoryExtensions.run { input.size sub sighashSize }, totalSize).let(ret::extend)

        ret.extend(inputStartVar, totalSize, calldataBase.s, input.baseVar.s, input.offset.s, input.size.s, )
        ret.extend(input.rangeToDecomposedArg.values.mapNotNull { (it as? DecomposedCallInputArg.Variable)?.v })
        ret.extend(TACCmd.Simple.ByteLongCopy(
            dstBase = calldataBase.s,
            dstOffset = sighashSize.asTACSymbol(),
            length = totalSize,
            srcBase = input.baseVar.s,
            srcOffset = inputStartVar
        ))

        // bind the explicit arguments
        for((k, v) in byteOffsetToScalar) {
            check(k.size() == EVM_WORD_SIZE || (k.size() == DEFAULT_SIGHASH_SIZE && k.from == BigInteger.ZERO)) {
                "Unsupport input size in $k"
            }
            if(k.size() != EVM_WORD_SIZE) { // this *must* be at 0 per the above
                continue
            }
            val exp = input.inputArgAtOffset(k.from, sighashSize)
            ret.extend(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = v,
                rhs = exp
            ))
            ret.extend(v)
            ret.extend(exp.getFreeVars())
        }
        /**
         * The following copies are technically redundant w.r.t. the bytelong copy above. However, it can help
         * to have explicit stores at constant indices which can be inlined later via the [analysis.opt.inliner.GlobalInliner].
         * In addition, these global stores can work around the following extremely specific scenario:
         * 1. There are unaligned writes to a buffer
         * 2. PTA succeeded
         * 3. Loop analysis succeeded
         * 4. Copy loop rewriting was not enabled
         *
         * This is niche to be certain, but it also (in principle) helps out the byteload inliner.
         */
        if(input.inputSizeLowerBound != null && input.inputSizeLowerBound.mod(EVM_WORD_SIZE) == sighashSize) {
            var inputOffsets = sighashSize
            while(inputOffsets < input.inputSizeLowerBound) {
                val currOffs = inputOffsets
                inputOffsets += EVM_WORD_SIZE
                val sym = input.inputArgAtOffset(currOffs, sighashSize)
                if(sym !is TACExpr.Sym.Var) {
                    continue
                }
                ret.extend(sym.s)
                ret.extend(TACCmd.Simple.AssigningCmd.ByteStore(
                    base = calldataBase.s,
                    loc = currOffs.asTACSymbol(),
                    value = sym.s
                ))
                ret.extend(sym.s)
            }
        }
        return ret.toCommandWithRequiredDecls()
    }


    fun copyWithCallId(callId: CallId): CalldataEncoding {
        val baseWithId =  calldataBase.s.atSync(callIndex = callId).asSym()

        val sizeWithId = when (calldataSize) {
            is TACExpr.Sym.Var -> {
                calldataSize.s.at(callIndex = callId).asSym()
            }
            is TACExpr.Sym.Const -> {
                calldataSize
            }
        }
        val offsetWithId = when (calldataOffset) {
            is TACExpr.Sym.Var -> {
                calldataOffset.s.at(callIndex = callId).asSym()
            }
            is TACExpr.Sym.Const -> {
                calldataOffset
            }
        }
        return copy(
            calldataBase = baseWithId,
            calldataOffset = offsetWithId,
            calldataSize = sizeWithId,
            byteOffsetToScalar = byteOffsetToScalar.mapValues { (_, v) -> v.at(callId) })
    }

}
