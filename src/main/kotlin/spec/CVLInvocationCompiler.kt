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

package spec

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.asSpec
import analysis.EthereumVariables
import analysis.icfg.CalldataDeterminismHelper
import analysis.icfg.Inliner
import analysis.merge
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import evm.*
import instrumentation.calls.CalldataEncoding
import instrumentation.transformers.ReturnsProcessor
import optimizer.FREE_POINTER_SCALARIZED
import scene.ITACMethod
import spec.CVLCompiler.CallIdContext.Companion.toContext
import spec.converters.EVMMoveSemantics
import spec.converters.LowLevelEncoder
import spec.converters.LowLevelOutput
import spec.converters.repr.CVLDataInput
import spec.cvlast.CVLExp
import spec.cvlast.CVLParam
import spec.cvlast.CVLType
import spec.cvlast.typedescriptors.*
import tac.*
import utils.*
import vc.data.*
import vc.data.ParametricInstantiation.Companion.bind
import vc.data.ParametricInstantiation.Companion.toInstantiation
import vc.data.ParametricInstantiation.Companion.toSimple
import vc.data.TACCmd.CVL.BufferPointer.Companion.toBufferPointer
import vc.data.TACExprFactUntyped.shiftRLog
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten
import vc.data.TACSymbol.Companion.Zero
import vc.data.TACSymbol.Companion.atSync
import vc.data.TACSymbol.Var.Companion.KEYWORD_ENTRY
import vc.data.tacexprutil.ExprUnfolder
import vc.data.tacexprutil.TACExprFactSimple
import java.io.Serializable
import java.math.BigInteger

/**
 * Harder, better, faster stronger
 */
class CVLInvocationCompiler(private val compiler: CVLCompiler, private val compilationEnvironment: CVLCompiler.CompilationEnvironment) {

    companion object {
        /** annotates a block where all reverting paths in a function meet to be handled */
        val REVERT_CONFLUENCE = MetaKey.Nothing("revert.confluence")

        private fun passEnvMember(exp: TACExpr, destType: VMValueTypeDescriptor): Pair<TACSymbol.Var, CommandWithRequiredDecls<TACCmd.Spec>> {
            val srcType = destType.getPureTypeToConvertFrom(ToVMContext.ArgumentPassing).force()

            val tmp = TACKeyword.TMP(srcType.toTag()).toUnique("!")
            val out = TACKeyword.TMP(destType.toTag()).toUnique("!")
            val conversion =
                CommandWithRequiredDecls(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    tmp, exp
                ), tmp, out).merge(EVMMoveSemantics.ValueConverters.convertValueTypeToEVM(out, destType, tmp, srcType))
            return out to conversion
        }

        fun getMethodForInlining(
            callee: ITACMethod,
            calleeTxId: CallId,
            returnsProcessor: CodeInstrumentation,
        ): CVLTACProgram {
            val code = callee.code as CoreTACProgram

            return code.createCopy(calleeTxId).let {
                it.copy(procedures = it.procedures + listOf(Procedure(calleeTxId, callee)))
            }.asCVLTACProgram().let {
                returnsProcessor.translate(it, callee)
            }

        }
    }


    private interface PayableHandler {
        fun generate(returnPoint: NBId, impl: CVLTACProgram, callee: ITACMethod): CVLTACProgram
    }

    interface CodeGenerator {
        fun generateCode(callee: ITACMethod): ParametricInstantiation<CVLTACProgram>

        fun generateCode(callee: ITACMethod, k: TACSymbol.Var): ParametricInstantiation<CVLTACProgram>

        fun getCallId(): CallId
    }


    private fun <T : TACCmd.Spec> CommandWithRequiredDecls<T>.toProgWithCurrEnv(id: String) =
        this.toProg(id, compilationEnvironment)

    private inner class CodeCallGenerator(
        private val storageSetup: CVLTACProgram,
        private val outputNames: List<CVLParam>,
        private val isSafeInvocation: Boolean,
        private val payableHandler: PayableHandler,
        private val pipeline: CodeInstrumentation,
        private val returnInstrumentation: CodeInstrumentation,
        private val callId: CallId,
        val args: ParametricInstantiation<CVLTACProgram>,
        private val allocation: TACSymbolAllocation
    ) : CodeGenerator {
        override fun getCallId(): CallId = callId

        override fun generateCode(callee: ITACMethod): ParametricInstantiation<CVLTACProgram> {
            return generateCode(callee) { meth, callId, returnInstrumentation ->
                getMethodForInlining(
                    callee = meth,
                    calleeTxId = callId,
                    returnsProcessor = returnInstrumentation,
                ).toSimple()
            }
        }

        override fun generateCode(callee: ITACMethod, k: TACSymbol.Var): ParametricInstantiation<CVLTACProgram> {
            return generateCode(callee) { meth, callId, returnInstrumentation ->
                getMethodForInlining(
                    callee = meth,
                    calleeTxId = callId,
                    returnsProcessor = returnInstrumentation,
                ).toInstantiation(k, meth.evmExternalMethodInfo!!)
            }
        }

        private fun generateCode(
            callee: ITACMethod,
            forInlining: (ITACMethod, CallId, CodeInstrumentation) -> ParametricInstantiation<CVLTACProgram>
        ): ParametricInstantiation<CVLTACProgram> {
            val (storageSave, returnCode) = if (this.isSafeInvocation) {
                CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd)) to compiler.nonRevertingAssumptionsToPostpend(compilationEnvironment)
            } else {
                val v = CVLReservedVariables.certoraResetStorage.toVar(Tag.BlockchainState).toUnique("!")
                CommandWithRequiredDecls(
                    listOf(
                        TACCmd.CVL.CopyBlockchainState(
                            lhs = v,
                            meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE)
                        )
                    ), v
                ) to compiler.revertingAssumptionsToPostpend(v, compilationEnvironment)
            }
            val prefix = havocReturnVariables().merge(storageSave)

            return forInlining(callee, callId, returnInstrumentation).bind { code ->
                val functionCallCode = pipeline.translate(code, callee)

                val withPayment = payableHandler.generate(
                    returnCode.getStartingBlock(), functionCallCode, callee
                ) merge returnCode
                args merge storageSetup merge withPayment.prependToBlock0(prefix)
            }
        }

        private fun havocReturnVariables(): CommandWithRequiredDecls<TACCmd.Simple> {
            val varsToHavoc = outputNames.filterNot {
                it.type == CVLType.PureCVLType.Bottom
            }
            return CommandWithRequiredDecls(varsToHavoc.map {
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                    lhs = allocation.get(it.id, it.type.toTag())
                )
            }, varsToHavoc.mapTo(mutableSetOf()) {
                allocation.get(it.id, it.type.toTag())
            })
        }
    }

    private fun handleStorageAnnotation(
        i: CVLExp.ApplyExp.ContractFunction,
        allocation: TACSymbolAllocation,
        env: CVLCompiler.CompilationEnvironment
    ): CVLTACProgram {
        val s = i.storage
        if (s.isLastStorage()) {
            return CommandWithRequiredDecls<TACCmd.Spec>().toProgWithCurrEnv("empty")
        }
        val (out, param) = allocation.generateTransientUniqueCVLParam(compiler.cvl.symbolTable, CVLReservedVariables.certoraResetStorage.name, CVLType.PureCVLType.VMInternal.BlockchainState)
        val storageSet = CVLExpressionCompiler(
            cvlCompiler = compiler,
            allocatedTACSymbols = allocation,
            compilationEnvironment = env
        ).compileExp(
            out = out,
            exp = s
        )

        // if we restore the storage to state s, we want to show it even if s is not cvl local variable.
        val paramWithMeta = param.withMeta(TACMeta.CVL_DISPLAY_NAME, s.id)

        return storageSet.getAsSimple().merge(CommandWithRequiredDecls(
            listOf(
                TACCmd.CVL.SetBlockchainState(paramWithMeta, meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE)),
                SnippetCmd.CVLSnippetCmd.StorageDisplay(paramWithMeta).toAnnotation()
            )
        ).toProgWithCurrEnv("restore state"))
    }


    private fun compileArguments(
        i: CVLExp.ApplyExp.ContractFunction,
        allocation: TACSymbolAllocation,
        env: CVLCompiler.CompilationEnvironment
    ): Pair<ParametricInstantiation<CVLTACProgram>, List<Pair<TACSymbol.Var, CVLType.PureCVLType>>> {
        return i.args.mapIndexed { _, arg ->
            val resType = arg.getOrInferPureCVLType()
            val (param, where) = allocation.generateTransientUniqueCVLParam(compiler.cvl.symbolTable,
                CVLReservedVariables.certoraArg.name,
                resType)
            Triple(
                CVLExpressionCompiler(compiler, allocation, compilationEnvironment = env).compileExp(
                    exp = arg,
                    out = param
                ), where, resType
            )
        }.let {
            val argCompilation = ParametricMethodInstantiatedCode.merge(it.map { it.first }, "arg setup")
            val parameterList = it.map {
                it.second to it.third
            }
            argCompilation to parameterList
        }
    }

    private inner class StackPusher(val callId: CallId, val isNoRevert: Boolean) : CodeInstrumentation {
        override fun hashCode() = this@CVLInvocationCompiler.hashCode()
        override fun translate(src: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            return addStackAnnotations(src, callee)
        }

        fun addStackAnnotations(
            cvl: CVLTACProgram, callee: ITACMethod
        ): CVLTACProgram {
            return cvl.patching { patching ->
                val g = cvl.commandGraph
                val methodRef = MethodRef(
                    callee.getContainingContract().instanceId,
                    callee.sigHash,
                    callee.attribute
                )
                g.roots.forEach(
                    Inliner.CallStack.stackPusher(
                        patching,
                        Inliner.CallStack.PushRecord(
                            methodRef,
                            null,
                            Inliner.CallConventionType.FromCVL,
                            callId,
                            isNoRevert = isNoRevert
                        )
                    )
                )

                g.sinks.forEach(Inliner.CallStack.stackPopper(patching, Inliner.CallStack.PopRecord(methodRef, callId)))
            }
        }
    }

    private inner class RevertCollector(val isNoRevert: Boolean) : CodeInstrumentation {
        override fun hashCode() = this@CVLInvocationCompiler.hashCode()
        override fun translate(src: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            if (!isNoRevert) {
                return src
            }
            return src.patching { patching ->
                val g = src.commandGraph
                val revertConfluence = lazy {
                    patching.addBlock(src.entryBlockId, listOf(TACCmd.Simple.AnnotationCmd(REVERT_CONFLUENCE)))
                }
                g.sinks.map { it.ptr.block }.forEach { block ->
                    val blockCode = g.code[block]!!
                    if (blockCode.any {
                        (it as? TACCmd.Simple.AssigningCmd.AssignExpCmd)?.lhs?.meta?.get(KEYWORD_ENTRY)?.name == CVLKeywords.lastReverted.keyword &&
                            (it.rhs as? TACExpr.Sym)?.getAsConst() == BigInteger.ONE
                    }) {
                        patching.addAfter(CmdPointer(block, blockCode.lastIndex), listOf(TACCmd.Simple.JumpCmd(revertConfluence.value)))
                    }
                }
            }
        }

    }

    @Treapable
    interface CodeInstrumentation : Serializable {
        fun translate(src: CVLTACProgram, callee: ITACMethod): CVLTACProgram
    }

    private infix fun CodeInstrumentation.compose(other: CodeInstrumentation) = object : CodeInstrumentation {
        override fun hashCode() = this@compose.hashCode()
        override fun translate(src: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            return other.translate(this@compose.translate(src, callee), callee)
        }
    }

    private fun standardPipeline(
        environmentAdder: EnvironmentAdder,
        argumentBinder: ArgumentBinder,
        callId: CallId,
        isNoRevert: Boolean
    ): CodeInstrumentation {
        val pipeline = environmentAdder compose argumentBinder compose StackPusher(callId, isNoRevert)
        if (Config.CvlFunctionRevert.get()) {
            return pipeline compose RevertCollector(isNoRevert)
        }
        return pipeline
    }

    private interface EnvironmentAdder : CodeInstrumentation {
        fun addEnvironment(meth: CVLTACProgram, callee: ITACMethod): CVLTACProgram

        override fun translate(src: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            return this.addEnvironment(src, callee)
        }
    }

    private interface ArgumentBinder : CodeInstrumentation {
        fun bindArguments(meth: CVLTACProgram, callee: ITACMethod): CVLTACProgram

        override fun translate(src: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            return this.bindArguments(src, callee)
        }
    }

    inner class BasicEnvironmentAdder(private val calleeId: CallId) : EnvironmentAdder {
        override fun hashCode() = hash { it + this@CVLInvocationCompiler + calleeId }

        override fun addEnvironment(meth: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            val calleeAddress = callee.getContainingContract().addressSym
            val decls = mutableSetOf<TACSymbol.Var>()
            val addressVar = TACKeyword.ADDRESS.toVar(callIndex = calleeId)
            decls.add(addressVar)
            if (calleeAddress is TACSymbol.Var) {
                decls.add(calleeAddress)
            }
            val callvalueVar = EthereumVariables.callvalue.at(callIndex = calleeId)
            decls.add(callvalueVar)
            return meth.prependToBlock0(CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        callvalueVar,
                        TACSymbol.Const(BigInteger.ZERO)
                    ),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        addressVar,
                        calleeAddress.uncheckedAs<TACSymbol>().asSym()
                    )
                ),
                decls
            ))
        }
    }

    private inner class ComplexEnvironmentAdder(val calleeId: CallId, val env: TACSymbol.Var) : EnvironmentAdder {
        override fun hashCode() = hash { it + this@CVLInvocationCompiler + calleeId + env }

        override fun addEnvironment(meth: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            return environmentSetup(
                envArg = env,
                calleeId = calleeId,
                withPayment = true,
                calleeAddress = callee.getContainingContract().addressSym.uncheckedAs<TACSymbol>()
            ).let(meth::prependToBlock0)
        }

        private fun passFromEnvToTAC(
            envArg: TACSymbol.Var,
            tacVar: TACSymbol.Var,
            mainField: String,
            subField: String,
            typeDesc: VMValueTypeDescriptor
        ): CommandWithRequiredDecls<TACCmd.Spec> {
            val cvlType = typeDesc.getPureTypeToConvertFrom(ToVMContext.ArgumentPassing).force()
            return passEnvMember(
                compiler.exprFact.StructAccess(
                    struct = compiler.exprFact.StructAccess(envArg.asSym(),
                        fieldName = mainField
                    ),
                    fieldName = subField
                ), typeDesc
            ).let { (outVar, cmds) ->
                cmds.merge(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        tacVar,
                        outVar
                    )
                )
            }.merge(
                // TODO: this should be done when havocing the env variable (CERT-1935)
                compiler.ensureBitWidth(
                    cvlType,
                    tacVar
                )
            )
        }

        //        OLD Copy-Pasted Code from CVLCompileInvocation
        fun environmentSetup(
            envArg: TACSymbol.Var,
            calleeId: CallId,
            withPayment: Boolean = false,
            calleeAddress: TACSymbol
        ): CommandWithRequiredDecls<TACCmd.Spec> {
            CVLReservedVariables.certoraTmp.toVar()
            /* Note: for address types, need to make sure first 12 bytes are 0.
            We could do this either in every call to a command that returns an address type,
                or here when setting up the environment.
            Chose to do this here, since if environment defines all address-returning values, it only has to be done once instead of in every call to e.g. CALLER or ORIGIN.
            Also do this when processing calldata (EVM will cut the 12 bytes, but spec code has to do it as well for address types)
             */

            // handle msg
            val msgSetup = buildMsgSetup(calleeId, envArg, calleeAddress)

            // handle tx
            val txSetup = passFromEnvToTAC(
                envArg,
                EthereumVariables.origin.at(callIndex = calleeId),
                "tx",
                "origin",
                EVMTypeDescriptor.address
            )

            // handle block
            val blockSetup = passFromEnvToTAC(
                envArg,
                EthereumVariables.number.at(callIndex = calleeId),
                "block",
                "number",
                EVMTypeDescriptor.UIntK(256)
            ).merge(
                passFromEnvToTAC(
                    envArg,
                    EthereumVariables.timestamp.at(callIndex = calleeId),
                    "block",
                    "timestamp",
                    EVMTypeDescriptor.UIntK(256)
                )
            ).merge(
                passFromEnvToTAC(
                    envArg,
                    EthereumVariables.basefee.at(callIndex = calleeId),
                    "block",
                    "basefee",
                    EVMTypeDescriptor.UIntK(256)
                )
            ).merge(
                passFromEnvToTAC(
                    envArg,
                    EthereumVariables.blobbasefee.at(callIndex = calleeId),
                    "block",
                    "blobbasefee",
                    EVMTypeDescriptor.UIntK(256)
                )
            ).merge(
                passFromEnvToTAC(
                    envArg,
                    EthereumVariables.coinbase.at(callIndex = calleeId),
                    "block",
                    "coinbase",
                    EVMTypeDescriptor.address
                )
            ).merge(
                passFromEnvToTAC(
                    envArg,
                    EthereumVariables.difficulty.at(callIndex = calleeId),
                    "block",
                    "difficulty",
                    EVMTypeDescriptor.UIntK(256)
                )
            ).merge(
                passFromEnvToTAC(
                    envArg,
                    EthereumVariables.gasLimit.at(callIndex = calleeId),
                    "block",
                    "gaslimit",
                    EVMTypeDescriptor.UIntK(256)
                )
            )

            // TODO: Handle store
            val handleBalanceTransfer: CommandWithRequiredDecls<TACCmd.Spec> = if (withPayment) {
                // perform actual transfer TODO: Update the address
                val (msgValue, conversion) =
                    passEnvMember(
                        compiler.exprFact.StructAccess(
                            compiler.exprFact.StructAccess(
                                envArg.asSym(),
                                "msg"
                            ),
                            "value"
                        ), EVMTypeDescriptor.UIntK(256)
                    )

                val (msgSender, senderConversion) =
                    passEnvMember(
                        compiler.exprFact.StructAccess(
                            compiler.exprFact.StructAccess(
                                envArg.asSym(),
                                "msg"
                            ),
                            "sender"
                        ),
                        EVMTypeDescriptor.address
                    )
                conversion.merge(senderConversion).merge(
                    EthereumVariables.transferBalance(
                        msgSender.asSym(),
                        calleeAddress.asSym(),
                        msgValue.asSym(),
                    )).asSpec()
            } else {
                CommandWithRequiredDecls<TACCmd.Spec>(TACCmd.Simple.NopCmd)
            }

            return msgSetup.merge(txSetup).merge(blockSetup).merge(handleBalanceTransfer)
        }

        /**
         * Updates the environment variables for the call from `envId`
         * More OLD Copy-Pasted Code
         */
        fun buildMsgSetup(
            calleeId: CallId,
            envSymbol: TACSymbol.Var,
            calleeAddress: TACSymbol
        ): CommandWithRequiredDecls<TACCmd.Spec> {
            val l1 = passFromEnvToTAC(
                envSymbol,
                EthereumVariables.caller.at(callIndex = calleeId),
                "msg",
                "sender",
                EVMTypeDescriptor.address
            )

            val l2 = passFromEnvToTAC(
                envSymbol,
                EthereumVariables.callvalue.at(callIndex = calleeId),
                "msg",
                "value",
                EVMTypeDescriptor.UIntK(256)
            )

            val l3 = CommandWithRequiredDecls<TACCmd.Spec>(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        EthereumVariables.address.at(callIndex = calleeId),
                        calleeAddress.asSym()
                    )
                ),
                setOfNotNull(
                    EthereumVariables.address.at(callIndex = calleeId),
                    calleeAddress.takeIf { it is TACSymbol.Var } as? TACSymbol.Var
                )
            )

            return l1.merge(l2).merge(l3)
        }
    }

    private inner class DirectPassing(
        private val callId: CallId,
        private val argVars: List<Pair<TACSymbol.Var, CVLType.PureCVLType>>,
    ) : ArgumentBinder {
        override fun hashCode() = hash { it + this@CVLInvocationCompiler + callId + argVars }

        override fun bindArguments(meth: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            val calldataSize = TACKeyword.CALLDATASIZE.toVar(callId)
            val params = callee.evmExternalMethodInfo!!.argTypes
            val calldataEncoding = callee.calldataEncoding as CalldataEncoding
            val scalar = calldataEncoding.byteOffsetToScalar.map {
                it.key.from to it.value.at(callId)
            }.toMap()
            val buffer = TACKeyword.CALLDATA.toVar().atSync(callId)
            val tupleSize = params.sumOf {
                it.sizeAsEncodedMember()
            }


            val (enc, setup) = LowLevelEncoder(
                buffer = buffer,
                offset = DEFAULT_SIGHASH_SIZE.asTACSymbol(),
                scalars = scalar
            )
            val (output, encoding) = enc.advanceNext(tupleSize) {
                it.saveScope { withScope ->
                    withScope.collecting(CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toProg("empty", callId.toContext()), argVars.zip(params)) { enc, ind, (actual, param) ->
                        val converter = param.converterFrom(actual.second, ToVMContext.ArgumentPassing.getVisitor()).force()
                        val offset = params.take(ind).fold(BigInteger.ZERO) { acc, m ->
                            acc + m.sizeAsEncodedMember()
                        }
                        enc.advanceCurr(offset) { atField ->
                            converter.convertTo(
                                fromVar = CVLDataInput(actual.first, callId),
                                toVar = atField,
                                cb = { it }
                            ) as LowLevelOutput
                        }.mapCVLProgram { res ->
                            res andThen CommandWithRequiredDecls(
                                CVLCompiler.Companion.TraceMeta.ExternalArgCmd(s = actual.first, rootOffset = offset, callId = callId, targetType = param, sourceType = actual.second)
                            )
                        }
                    }
                }
            }

            /**
             * Try to introduce some level of determinism into the "default" calldata buffers. In particular, we make
             * the contents of the buffer the result of a nondeterministic function over the: 1. index of the access,
             * 2. the size of the calldata, and 3. the sighash (if available).
             *
             * Thus, calls which share the same input buffer size to the same function will yield the same "nondet values"
             * at unaligned accesses.
             */
            val sym = TACSymbol.Var("i", Tag.Bit256).toUnique("!")
            val (initMap, defaultVal) = (callee.sigHash?.let {
                CalldataDeterminismHelper.deterministicFor(3)
            } ?: CalldataDeterminismHelper.deterministicFor(2)).let { initMap ->
                val args = listOf(sym.asSym(), calldataSize.asSym()) + callee.sigHash?.n?.asTACExpr.let(::listOfNotNull)
                initMap to TACExpr.Select.buildMultiDimSelect(initMap.asSym(), args)
            }

            /**
             * If we have a sighash, have the calldata buffer return the sighash at index 0 (otherwise just propagate on through
             * to defaultVal, the term that uses the determinism map).
             */
            val sighashBinding = callee.sigHash?.n?.let { sigHash ->
                ExprUnfolder.unfoldPlusOneCmd("sighashBind", TACExprFactSimple {
                    (TACExpr.Select.buildMultiDimSelect(
                        initMap.asSym(), listOf(TACExpr.zeroExpr, calldataSize.asSym(), sigHash.asTACExpr)
                    ) shiftRLog  ((EVM_WORD_SIZE_INT - DEFAULT_SIGHASH_SIZE_INT) * EVM_BYTE_SIZE_INT).asTACExpr) eq sigHash.asTACExpr
                }) {
                    TACCmd.Simple.AssumeCmd(it.s, "sighashBind")
                }
            }
            // not bound by argument encoding
            val calldataZeroBind = calldataEncoding.byteOffsetToScalar.findEntry { range, _ ->
                range.from == BigInteger.ZERO && callee.sigHash != null
            }?.let { (_, r) ->
                val indexedR = r.atSync(callId)
                CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = indexedR,
                        loc = Zero,
                        base = buffer
                    )
                ), indexedR)
            }


            val calldataInitialize = listOfNotNull(
                CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = buffer,
                            rhs = TACExpr.MapDefinition(
                                listOf(sym.asSym()),
                                tag = Tag.ByteMap,
                                definition = TACExprFactSimple {
                                    ite(
                                        sym.asSym() lt calldataSize.asSym(),
                                        defaultVal,
                                        TACExpr.zeroExpr
                                    )
                                }
                            )
                        )
                    ),
                    setOf(initMap, calldataSize)
                ),
                sighashBinding
            ).flatten()


            val actualCalldataSize = TACKeyword.TMP(Tag.Bit256, "!calldata").toUnique("!")
            val sizeConstraint = TACKeyword.TMP(Tag.Bool, "!calldataEq").toUnique("!")


            val sizeSetup = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = actualCalldataSize,
                    rhs = compiler.exprFact.Sub(
                        output.outputPointer.asSym(),
                        BigInteger.ZERO.asTACSymbol().asSym()
                    )
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = sizeConstraint,
                    rhs = TACExpr.BinRel.Eq(
                        actualCalldataSize.asSym(),
                        calldataSize.asSym()
                    )
                ),
                TACCmd.Simple.AssumeCmd(sizeConstraint, "constrain calldata size")
            ), setOf(
                calldataSize, output.outputPointer, buffer, sizeConstraint, actualCalldataSize
            ))

            val encodingId = Allocator.getFreshId(Allocator.Id.CVL_SERIALIZATION)

            val havocCalldataSize = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(lhs = calldataSize)
            ), setOf(calldataSize))
            val prefix = listOfNotNull(
                havocCalldataSize,
                calldataInitialize,
                CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AnnotationCmd(StartSerializationMarker.META_KEY, StartSerializationMarker(encodingId, callId))
                )),
                setup,
                calldataZeroBind
            ).flatten()
            val preamble = encoding.prependToBlock0(prefix)
            val afterEncoding = (sizeSetup andThen CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AnnotationCmd(EndSerializationMarker.META_KEY, EndSerializationMarker(encodingId, callId))
            ))).toProg("invocation suffix", callId.toContext())
            return mergeCodes(mergeCodes(preamble, afterEncoding), meth)
        }
    }

    @KSerializable
    @GenerateRemapper
    data class StartSerializationMarker(
        @GeneratedBy(Allocator.Id.CVL_SERIALIZATION, source = true)
        val id: Int,
        @GeneratedBy(Allocator.Id.CALL_ID)
        val callId: CallId
    ) : AmbiSerializable, RemapperEntity<StartSerializationMarker> {
        companion object {
            val META_KEY = MetaKey<StartSerializationMarker>("cvl.arg-serialization.start")
        }
    }

    @KSerializable
    @GenerateRemapper
    data class EndSerializationMarker(
        @GeneratedBy(Allocator.Id.CVL_SERIALIZATION)
        val id: Int,
        @GeneratedBy(Allocator.Id.CALL_ID)
        val callId: CallId
    ): AmbiSerializable, RemapperEntity<EndSerializationMarker> {
        companion object {
            val META_KEY = MetaKey<EndSerializationMarker>("cvl.arg-serialization.end")
        }
    }

    private inner class ReturnInstrumentation(
        val output: List<CVLParam>,
        val allocation: TACSymbolAllocation,
        val calleeId: CallId
    ) : CodeInstrumentation {
        override fun hashCode() = hash { it + this@CVLInvocationCompiler + output + allocation + calleeId }

        override fun translate(src: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            val res = callee.evmExternalMethodInfo!!.resultTypes
            val r = if (res.size != output.size) {
                throw IllegalStateException("Invalid arities for result and output types")
            } else {
                res
            }
            val converters = mutableListOf<Triple<BigInteger, TACSymbol.Var, ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>>>()
            var offs = BigInteger.ZERO
            for ((srcType, dst) in r.zip(output)) {

                // when the output type is Bottom (i.e. '_' variable), we want to just update the offset but don't
                // want to add a converter for it.
                if (dst.type !is CVLType.PureCVLType.Bottom) {
                    val conv = srcType.converterTo(dst.type, FromVMContext.ReturnValue.getVisitor()).force()
                    converters.add(Triple(
                        offs,
                        allocation.get(dst.id),
                        conv
                    ))
                }

                offs += srcType.sizeAsEncodedMember()
            }
            val freePointerScalarized = (callee.code as? CoreTACProgram)?.let { it.parallelLtacStream().anyMatch { ltc -> ltc.cmd is TACCmd.Simple.AnnotationCmd && ltc.cmd.bind(FREE_POINTER_SCALARIZED) { it } ?: false } } ?: false
            val processor = ReturnsProcessor(
                converters,
                calleeTxId = calleeId,
                calleeFreePointerScalarized = freePointerScalarized
            )
            return processor.transform(src)
        }
    }

    private inner class PayableCheck(
        val envVar: TACSymbol.Var,
        val isNoRevert: Boolean,
        val callId: Int
    ) : PayableHandler {
        override fun generate(returnPoint: NBId, impl: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            if (callee.evmExternalMethodInfo?.stateMutability?.isPayable == false) {
                return impl
            }
            val balanceRead = CVLReservedVariables.certoraTmp.toVar(Tag.Bit256).toUnique("!")
            val senderAddress = CVLReservedVariables.certoraTmp.toVar(Tag.Bit256).toUnique("!")
            val msgAmount = CVLReservedVariables.certoraTmp.toVar(Tag.Bit256).toUnique("!")
            val balanceSufficient = CVLReservedVariables.certoraCond.toVar(Tag.Bool).toUnique("!")
            val (tmpMsgAmount, msgAmountConversion) =
                passEnvMember(
                    compiler.exprFact.StructAccess(struct = compiler.exprFact.StructAccess(envVar.asSym(), "msg"), "value"),
                    EVMTypeDescriptor.UIntK(256)
                )
            val (tmpMsgSender, msgSenderConversion) =
                passEnvMember(
                    compiler.exprFact.StructAccess(struct = compiler.exprFact.StructAccess(envVar.asSym(), "msg"), "sender"),
                    EVMTypeDescriptor.address
                )

            val commands = msgSenderConversion.merge(CommandWithRequiredDecls<TACCmd.Spec>(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = senderAddress,
                    rhs = tmpMsgSender
                ),
                TACCmd.Simple.AssigningCmd.WordLoad(
                    lhs = balanceRead,
                    base = TACKeyword.BALANCE.toVar(),
                    loc = senderAddress
                ),
                SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet(balanceRead, senderAddress).toAnnotation()
            ))).merge(msgAmountConversion).merge(CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = msgAmount,
                    rhs = tmpMsgAmount
                ))))
                .merge(CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = balanceSufficient,
                        rhs = compiler.exprFact.Ge(
                            balanceRead.asSym(),
                            msgAmount.asSym()
                        )
                    )
                ), setOf(balanceSufficient, envVar, balanceRead, msgAmount, senderAddress)))
                .toProg("balanceCheck", callId.toContext())

            val revertPath = EthereumVariables.setLastReverted(lastReverted = true)
                .merge(if (isNoRevert && Config.CvlFunctionRevert.get()) {
                    TACCmd.Simple.AnnotationCmd(REVERT_CONFLUENCE)
                } else {
                    TACCmd.Simple.JumpCmd(returnPoint)
                })
                .toProg("revert by balance", callId.toContext())
            return mergeIfCodes(
                commands,
                TACCmd.Simple.JumpiCmd(cond = balanceSufficient, dst = impl.getStartingBlock(), elseDst = revertPath.getStartingBlock()),
                thenCode = impl,
                elseCode = revertPath
            )
        }
    }

    /**
     * TODO(jtoman): calldatasize lower bound
     */
    private inner class CalldataBinder(
        val calldataArg: TACSymbol.Var,
        val callId: CallId
    ) : ArgumentBinder {
        override fun hashCode() = hash { it + this@CVLInvocationCompiler + calldataArg + callId }

        override fun bindArguments(meth: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            val calldatasize = TACKeyword.CALLDATASIZE.toVar().atSync(callId)
            val minCalldatasize = TACSymbol.Var("minCalldatasize", Tag.Bool).toUnique()
            val maxCalldatasize = TACSymbol.Var("maxCalldatasize", Tag.Bool).toUnique()
            val calldata = TACKeyword.CALLDATA.toVar().atSync(callId)

            val blankBuffer = TACKeyword.TMP(Tag.ByteMap, "!calldata").toUnique("!")
            val idx = TACKeyword.TMP(Tag.Bit256, "!idx").toUnique("!")

            val v = callee.calldataEncoding as CalldataEncoding
            val bindingCode = mutableListOf<TACCmd.Spec>(
                TACCmd.CVL.ArrayLengthRead(
                    lhs = calldatasize,
                    rhs = calldataArg
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = blankBuffer,
                    rhs = TACExpr.MapDefinition(
                        defParams = listOf(idx.asSym()),
                        tag = Tag.ByteMap,
                        definition = TACExpr.TernaryExp.Ite(
                            TACExpr.BinRel.Lt(idx.asSym(), calldatasize.asSym()),
                            TACExpr.Unconstrained(Tag.Bit256),
                            0.asTACExpr
                        )
                    )
                ),
                TACCmd.CVL.ArrayCopy(
                    src = calldataArg.toBufferPointer(),
                    dst = TACCmd.CVL.BufferPointer(
                        offset = Zero,
                        buffer = TACCmd.CVL.Buffer.EVMBuffer(calldata)
                    ),
                    destinationSource = blankBuffer,
                    elementSize = 1,
                    logicalLength = calldatasize
                )
            )
            if (v.expectedCalldataSize != null) {
                bindingCode.addAll(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        minCalldatasize,
                        TACExpr.BinRel.Ge(
                            calldatasize.asSym(),
                            TACSymbol.Const(v.expectedCalldataSize).asSym()
                        )
                    ),
                    TACCmd.Simple.AssumeCmd(
                        minCalldatasize, "minCalldatasize"
                    ),
                    // With ABIEncoderV2 calldatasize is signed, so need to make sure it's positive
                    // CVLTODO: Do we need to support larger calldatasize for ABIEncoderV1? how?
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        maxCalldatasize,
                        TACExpr.BinRel.Ge(
                            TACSymbol.Const(SignUtilities.maxSignedValueOfBitwidth(256)).asSym(),
                            calldatasize.asSym()
                        )
                    ),
                    TACCmd.Simple.AssumeCmd(
                        maxCalldatasize, "maxCalldatasize"
                    )
                ))
            }
            val vars = mutableSetOf(minCalldatasize, maxCalldatasize, calldatasize, calldata, blankBuffer)
            for ((br, scalarName) in v.byteOffsetToScalar) {
                val scalar = scalarName.at(callId)
                bindingCode.add(TACCmd.CVL.ReadElement(
                    lhs = scalar,
                    physicalIndex = br.from.asTACSymbol(),
                    base = calldataArg,
                    useEncoding = Tag.CVLArray.UserArray.ElementEncoding.Unsigned
                ))
                vars.add(scalar)
            }

            return CommandWithRequiredDecls(bindingCode, vars).toProgWithCurrEnv("calldata binding") merge meth
        }
    }

    @Suppress("unused")
    private infix fun CVLTACProgram.merge(p: ParametricInstantiation<CVLTACProgram>): ParametricInstantiation<CVLTACProgram> {
        return ParametricMethodInstantiatedCode.mergeProgs(
            this.asSimple(),
            p
        )
    }

    private infix fun ParametricInstantiation<CVLTACProgram>.merge(other: CVLTACProgram): ParametricInstantiation<CVLTACProgram> {
        return ParametricMethodInstantiatedCode.mergeProgs(
            this, other.asSimple()
        )
    }

    private infix fun CVLTACProgram.merge(other: CVLTACProgram): CVLTACProgram {
        return mergeCodes(this, other)
    }

    object NoPayment : PayableHandler {
        override fun generate(returnPoint: NBId, impl: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            return impl
        }
    }

    object IdentityTransform : CodeInstrumentation {
        override fun hashCode() = utils.hashObject(this)
        override fun translate(src: CVLTACProgram, callee: ITACMethod): CVLTACProgram {
            return src
        }

        fun readResolve(): Any = IdentityTransform
    }

    fun getDirectInvocationCompiler(
        i: CVLExp.ApplyExp.ContractFunction.Concrete,
        allocation: TACSymbolAllocation,
        expectEnv: Boolean,
        outputList: List<CVLParam>?,
        env: CVLCompiler.CompilationEnvironment
    ): CodeGenerator {
        val (compiledArgs, argVars) = compileArguments(i, allocation, env)
        val contractArgs: List<Pair<TACSymbol.Var, CVLType.PureCVLType>>
        val callId = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val envAdder: EnvironmentAdder
        val payableHander: PayableHandler
        if (expectEnv) {
            val eVar = argVars.first().first
            envAdder = ComplexEnvironmentAdder(env = eVar, calleeId = callId)
            payableHander = PayableCheck(envVar = eVar, isNoRevert = i.noRevert, callId = callId)
            contractArgs = argVars.drop(1)
        } else {
            payableHander = NoPayment
            envAdder = BasicEnvironmentAdder(callId)
            contractArgs = argVars
        }
        val returnInstrumentation = if (outputList != null) {
            ReturnInstrumentation(
                output = outputList,
                calleeId = callId,
                allocation = allocation
            )
        } else {
            IdentityTransform
        }

        return CodeCallGenerator(
            storageSetup = handleStorageAnnotation(i, allocation, env),
            outputNames = outputList ?: listOf(),
            isSafeInvocation = i.noRevert,
            payableHandler = payableHander,
            pipeline = standardPipeline(
                environmentAdder = envAdder,
                argumentBinder = DirectPassing(callId, contractArgs),
                callId = callId,
                isNoRevert = i.noRevert
            ),
            callId = callId,
            args = compiledArgs,
            returnInstrumentation = returnInstrumentation,
            allocation = allocation
        )
    }


    fun getCalldataInvocationCompiler(
        i: CVLExp.ApplyExp.ContractFunction,
        allocation: TACSymbolAllocation,
        outputList: List<CVLParam>?,
        expectEnv: Boolean,
        env: CVLCompiler.CompilationEnvironment
    ): CodeGenerator {
        val (compiledArgs, argVars) = compileArguments(i, allocation, env)
        val callId = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val payable: PayableHandler
        val environ: EnvironmentAdder
        val calldataArgIdx: Int
        if (expectEnv) {
            val envVar = argVars[0].first
            environ = ComplexEnvironmentAdder(callId, envVar)
            payable = PayableCheck(
                envVar = envVar,
                isNoRevert = i.noRevert,
                callId = callId
            )
            calldataArgIdx = 1
        } else {
            payable = NoPayment
            environ = BasicEnvironmentAdder(callId)
            calldataArgIdx = 0
        }

        val binder = CalldataBinder(
            callId = callId,
            calldataArg = argVars[calldataArgIdx].first
        )
        val returnInstrumentation = if (outputList != null) {
            ReturnInstrumentation(
                allocation = allocation,
                calleeId = callId,
                output = outputList
            )
        } else {
            IdentityTransform
        }
        val codeSetup = standardPipeline(
            environmentAdder = environ,
            argumentBinder = binder,
            callId = callId,
            isNoRevert = i.noRevert
        )
        return CodeCallGenerator(
            callId = callId,
            pipeline = codeSetup,
            outputNames = outputList ?: listOf(),
            payableHandler = payable,
            isSafeInvocation = i.noRevert,
            storageSetup = handleStorageAnnotation(i, allocation, env),
            args = compiledArgs,
            returnInstrumentation = returnInstrumentation,
            allocation = allocation
        )
    }

}
