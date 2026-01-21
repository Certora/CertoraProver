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

package analysis.ip

import allocator.Allocator
import analysis.*
import analysis.worklist.MonadicStatefulParallelWorklistIteration
import analysis.worklist.ParallelStepResult
import aws.smithy.kotlin.runtime.util.pop
import bridge.*
import bridge.types.SolidityTypeDescription
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import java.util.stream.*
import log.*
import parallel.ParallelPool
import spec.cvlast.SolidityContract
import spec.cvlast.Visibility
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import java.math.BigInteger

private val logger = Logger(LoggerTypes.FUNCTION_BUILDER)

private typealias PatchUpdate = (SimplePatchingProgram) -> Unit

private typealias FunctionAnalysisResult = Either<PatchUpdate, String>

class VyperInternalFunctionAnnotator(val code: CoreTACProgram, val source: ContractInstanceInSDC) {

    /**
    Annotates all Vyper internal functions in the program.
     */
    fun annotate(): CoreTACProgram {
        logger.debug { "Annotating Vyper internal functions in ${code.name}" }
        return source.internalFuncs.parallelStream()
            .filter { it.vyperMetadata != null }
            .flatMap { allCallAnnotations(it, it.vyperMetadata!!) }
            .patchForEach(code) { thunk ->
                thunk(this)
            }
    }

    private val blocksByPc = code.code.keys.groupBy { it.origStartPc }

    /**
    Gets the annotations for every instance of the given [method].
     */
    private fun allCallAnnotations(
        method: Method,
        metadata: Method.VyperMetadata
    ): Stream<PatchUpdate> {
        logger.debug {
            "In ${code.name}: analyzing all calls to method $method with metadata $metadata"
        }
        val startBlocks = blocksByPc[metadata.runtimeStartPc] ?: return Stream.empty()
        return startBlocks.parallelStream().mapNotNull { start ->
            callAnnotations(method, metadata, start).toValue({ it }, { msg ->
                logger.warn { msg }
                null
            })
        }
    }

    private class SymbolicStack(start: NBId) {
        var stackHeight = start.stkTop

        fun pop(): TACSymbol.Var {
            if (stackHeight > 1024) {
                throw IllegalStateException("Stack underflow")
            }
            val varHeight = stackHeight++
            return TACSymbol.Var.stackVar(varHeight)
        }
    }

    fun interface FailureReporter {
        fun fail(msg: String): FunctionAnalysisResult
    }

    private fun forwardExitAnalysis(startBlock: NBId, returnLoc: TACSymbol.Var): Either<Set<NBId>, String> {
        val pool = (Thread.currentThread() as? ParallelPool.ParallelPoolWorkerThread)?.parallelPool
        val graph = code.analysisCache.graph
        val state = mutableMapOf<NBId, TACSymbol.Var>()
        state[startBlock] = returnLoc
        val revertBlocks = graph.cache.revertBlocks
        val res = object : MonadicStatefulParallelWorklistIteration<NBId, (MutableCollection<NBId>, MutableCollection<NBId>) -> Unit, NBId, Either<Set<NBId>, String>>(
                pool
        ) {
            override fun commit(
                c: (MutableCollection<NBId>, MutableCollection<NBId>) -> Unit,
                nxt: MutableCollection<NBId>,
                res: MutableCollection<NBId>
            ) {
                c(nxt, res)
            }

            override fun reduce(results: List<NBId>): Either<Set<NBId>, String> {
                return results.toSet().toLeft()
            }

            override fun process(it: NBId): ParallelStepResult<(MutableCollection<NBId>, MutableCollection<NBId>) -> Unit, NBId, Either<Set<NBId>, String>> {
                var returnLocIt = state[it]!!
                val block = graph.elab(it)
                for (lc in block.commands) {
                    if (lc.maybeAnnotation(JUMP_SYM)?.v == returnLocIt) {
                        return this.cont { _, res ->
                            res.add(it)
                        }
                    } else if (lc.maybeExpr<TACExpr.Sym.Var>()?.exp?.s == returnLocIt) {
                        returnLocIt = lc.cmd.getLhs()!!
                    } else if (lc.cmd is TACCmd.Simple.AssigningCmd && lc.cmd.lhs == returnLocIt && lc.ptr.block !in revertBlocks) {
                        return this.halt("Found write to return $returnLoc @ $lc for flow started at $startBlock".toRight())
                    }
                }
                val succ = graph.succ(it)
                if (succ.isEmpty() && it !in revertBlocks) {
                    return this.halt("Hit end of program at $it without exiting function".toRight())
                }
                return this.cont { nxt, _ ->
                    for (s in succ) {
                        if (s in state) {
                            if (state[s] != returnLocIt) {
                                if (s in revertBlocks) {
                                    continue
                                }
                                error("return location mismatch at $s in non-revert succ")
                            }
                        } else {
                            state[s] = returnLocIt
                            nxt.add(s)
                        }
                    }
                }
            }
        }.submit(listOf(startBlock))
        return res
    }

    private fun Either<Set<NBId>, String>.toConfluence() = this.bindLeft { exits ->
        exits.monadicMap { e ->
            code.analysisCache.graph.succ(e).singleOrNull()
        }?.uniqueOrNull()?.toLeft() ?: "Exit points are not confluence: $exits".toRight()
    }

    private fun handleMemoryReturn(
        v: TACSymbol.Var, returnSize: BigInteger
    ) : Pair<ReturnInstrumentation, VyperReturnValue> {
        val savedTemp = TACSymbol.Var("savedReturnSlot", Tag.Bit256).toUnique("!")
        val inst = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = savedTemp,
                rhs = TACExpr.Apply(TACBuiltInFunction.OpaqueIdentity(Tag.Bit256).toTACFunctionSym(), listOf(v.asSym()), Tag.Bit256)
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = v,
                rhs = savedTemp
            )
        )).merge(savedTemp)
        return ReturnInstrumentation(inst, beforeExit = false) to VyperReturnValue.MemoryReturnValue(
            s = savedTemp, size = returnSize
        )
    }

    private fun computeTypeSize(
        v: SolidityTypeDescription
    ) : BigInteger? {
        return when(v) {
            is SolidityTypeDescription.Array,
            is SolidityTypeDescription.Contract,
            is SolidityTypeDescription.Function,
            is SolidityTypeDescription.Mapping,
            is SolidityTypeDescription.PackedBytes,
            is SolidityTypeDescription.StringType -> null // without max size data, we have no way to know the sizes of these objects
            is SolidityTypeDescription.UserDefined.Enum,
            is SolidityTypeDescription.Primitive -> EVM_WORD_SIZE
            is SolidityTypeDescription.StaticArray -> computeTypeSize(v.staticArrayBaseType)?.let { sz ->
                sz * v.staticArraySize
            }
            is SolidityTypeDescription.UserDefined.Struct -> {
                v.structMembers.monadicMap { fld ->
                    computeTypeSize(fld.type)
                }?.sumOf { it }
            }
            is SolidityTypeDescription.UserDefined.ValueType -> null // shouldn't exist in vyper
        }
    }

    // lambda calculus, eat your heart out
    private fun ReturnInstrumentation?.toPreExit() = if(this?.beforeExit == true) { crd } else { CommandWithRequiredDecls() }
    private fun ReturnInstrumentation?.toPreEntrance() = if(this?.beforeExit == false) { crd } else { CommandWithRequiredDecls() }

    /**
     * Code ([crd] that handles instrumentation for return values). [beforeExit] == true that the instrumentation
     * should be inserted the exit of the function, vs. before the start of the function (== false)
     */
    data class ReturnInstrumentation(val crd: CommandWithRequiredDecls<TACCmd.Simple>, val beforeExit: Boolean)

    private fun opaqueIdentityBackup(
        s: TACSymbol.Var,
        prefix: String
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val tmp = TACSymbol.Var(prefix, s.tag).toUnique("!")
        return CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = tmp,
                    rhs = s
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = s,
                    rhs = TACExpr.Apply(
                        TACBuiltInFunction.OpaqueIdentity(tmp.tag),
                        listOf(tmp.asSym()),
                        tmp.tag
                    )
                )
            ), tmp, s)
    }

    context(FailureReporter)
    private fun processVenomPipeline(
        method: Method,
        metadata: Method.VyperMetadata,
        startBlock: NBId,
    ) : FunctionAnalysisResult {
        val stackAtEntry = SymbolicStack(startBlock)
        val returnPC = stackAtEntry.pop()
        val scalarArgs = mutableListOf<VyperArgument.StackArgument>()
        val names = method.paramNames
        if(metadata.venomViaStack != null) {
            val viaStack = metadata.venomViaStack!!
            for(nm in viaStack.asReversed()) {
                val stackArgVar = stackAtEntry.pop()
                val pos = names.indexOf(nm)
                if(pos == -1) {
                    return fail("Stack arg $nm didn't appear in param name list: $names")
                }
                scalarArgs.add(VyperArgument.StackArgument(
                    s = stackArgVar,
                    logicalPosition = pos,
                ))
            }
        }
        val exitBlock = forwardExitAnalysis(
            startBlock, returnPC
        ).toConfluence().leftOr { msg ->
            return fail("exit analysis failed: ${msg.right()}")
        }
        val (returnInstr, exitVar) = if(method.returns.isEmpty()) {
            null to null
        } else if(metadata.venomReturnViaStack == true) {
            val returnVar = SymbolicStack(exitBlock).pop()
            /**
             * Prevent the value from being folded/inlined outside the body of the function, using the same trick
             * in the solidity annotator.
             */
            val backupCommands = opaqueIdentityBackup(returnVar, "returnSave")
            ReturnInstrumentation(crd = backupCommands, beforeExit = true) to VyperReturnValue.StackVariable(returnVar)
        } else {
            val retTy = method.returns.single().typeDesc
            val retSize = computeTypeSize(retTy) ?: return fail("Couldn't compute return type size for $retTy")
            handleMemoryReturn(stackAtEntry.pop(), retSize)
        }
        val stackParams = metadata.venomViaStack.orEmpty().toSet()
        val args = mutableListOf<VyperArgument>()
        var argFrame = metadata.frameStart!!.toBigInteger()
        var argPos = 0

        /**
         * Similar to return process above, we *really* don't want constants to be inlined into the body of the
         * function (this breaks comparing mem passing to stack passing, among other things).
         *
         * So, use opaque identity to ensure that the stack arguments are never inlined into the body of the function.
         */
        val argOpacityBinding = MutableCommandWithRequiredDecls<TACCmd.Simple>()

        for((nm, ty) in method.paramNames.zip(method.fullArgs)) {
            val argSize = computeTypeSize(ty.typeDesc) ?: return fail("Couldn't compute size of arg $nm")
            if(nm in stackParams) {
                if(scalarArgs.isEmpty()) {
                    return fail("Stack param mismatch, expected $nm to be in stack vars")
                }
                val stackArg = scalarArgs.pop()
                if(stackArg.s is TACSymbol.Var) {
                    argOpacityBinding.extend(
                        opaqueIdentityBackup(
                            stackArg.s,
                            "inputArgSave"
                        )
                    )
                }
                args.add(stackArg)
            } else {
                args.add(VyperArgument.MemoryArgument(
                    logicalPosition = argPos,
                    size = argSize,
                    where = argFrame
                ))
            }
            argPos++
            argFrame += argSize
        }
        if(metadata.frameSize != null && argFrame > (metadata.frameStart!!.toBigInteger() + metadata.frameSize!!.toBigInteger())) {
            return fail("Frame overflow; args don't appear to fit in reported frame size")
        }
        return { s: SimplePatchingProgram ->
            Logger.regression {
                "Annotating ${method.toSignature()} in ${code.name}"
            }
            val id = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
            s.addBefore(CmdPointer(startBlock, 0), returnInstr.toPreEntrance() andThen argOpacityBinding.toCommandWithRequiredDecls() andThen TACCmd.Simple.AnnotationCmd(
                VyperInternalFuncStartAnnotation.META_KEY,
                VyperInternalFuncStartAnnotation(
                    id = id,
                    args = args,
                    which = method.toMethodSignature(SolidityContract(source.name), Visibility.INTERNAL)
                )
            ))
            s.addBefore(CmdPointer(exitBlock, 0), returnInstr.toPreExit() andThen CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AnnotationCmd(
                    VyperInternalFuncEndAnnotation.META_KEY, VyperInternalFuncEndAnnotation(
                        id = id, returnValue = exitVar
                    )
                )
            )))
        }.toLeft()
    }

    private fun Method.toSignature() = this.toMethodSignature(SolidityContract(source.name), Visibility.INTERNAL)

    private val jumpDests by lazy {
        code.analysisCache.graph.blocks.filter {
            it.commands.first().cmd is TACCmd.Simple.JumpdestCmd
        }.map {
            it.id.origStartPc
        }.toSet()
    }

    private fun isLikelyReturnLoc(v: TACSymbol.Var, where: NBId) : Boolean {
        val mca = MustBeConstantAnalysis(code.analysisCache.graph)
        val start = CmdPointer(where, 0)
        return mca.mustBeConstantAt(start, v)?.let { c ->
            c.isInt() && c.toInt() in jumpDests
        } == true
    }

    context(FailureReporter)
    private fun processRegularPipeline(
        method: Method,
        metadata: Method.VyperMetadata,
        startBlock: NBId
    ) : FunctionAnalysisResult {
        val stack = SymbolicStack(startBlock)
        var argPos = 0
        var frameIt = metadata.frameStart!!.toBigInteger()
        val args = mutableListOf<VyperArgument>()
        for((nm, ty) in method.paramNames.zip(method.fullArgs)) {
            val tySize = computeTypeSize(ty.typeDesc) ?: return fail(
                "Couldn't compute size for type ${ty.typeDesc} called $nm"
            )
            args.add(VyperArgument.MemoryArgument(
                where = frameIt,
                size = tySize,
                logicalPosition = argPos
            ))
            argPos++
            frameIt += tySize
        }

        if(method.returns.isEmpty()) {
            val returnLoc = stack.pop()
            val exitFunc = forwardExitAnalysis(startBlock, returnLoc).toConfluence().leftOr { msg ->
                return fail("Exit analysis failed ${msg.right()}")
            }
            val id = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
            return { s: SimplePatchingProgram ->
                s.addBefore(CmdPointer(startBlock, 0), listOf(
                    TACCmd.Simple.AnnotationCmd(
                        VyperInternalFuncStartAnnotation.META_KEY,
                        VyperInternalFuncStartAnnotation(
                            id = id,
                            which = method.toSignature(),
                            args = args
                        )
                    )
                ))
                s.addBefore(
                    CmdPointer(exitFunc, 0), listOf(
                    TACCmd.Simple.AnnotationCmd(
                        VyperInternalFuncEndAnnotation.META_KEY,
                        VyperInternalFuncEndAnnotation(
                            id = id,
                            returnValue = null
                        )
                    )
                ))
            }.toLeft()
        }

        val retSize = computeTypeSize(method.returns.single().typeDesc) ?: return fail("Could not compute return size")

        val stkTopVar = stack.pop()
        val nextStackVar = stack.pop()

        lateinit var succ : NBId

        /**
         * Runs the forward analysis, and, if it completes succesfully, binds succ to the inferred result.
         * Thus, if this function returns true, `succ` is safe to read
         */
        fun runForwardAnalysis(
            which: TACSymbol.Var
        ) : Boolean {
            val s = forwardExitAnalysis(startBlock, which).toConfluence().leftOrNull()
            return if(s != null) {
                succ = s
                true
            } else {
                false
            }
        }

        val (returnDataLoc, returnBlock) = when {
            isLikelyReturnLoc(stkTopVar, startBlock) && !isLikelyReturnLoc(nextStackVar, startBlock) && runForwardAnalysis(stkTopVar) -> {
                nextStackVar to succ
            }
            !isLikelyReturnLoc(stkTopVar, startBlock) && isLikelyReturnLoc(nextStackVar, startBlock) && runForwardAnalysis(nextStackVar) -> {
                stkTopVar to succ
            }
            else -> {
                /**
                 * As documented elsewhere, depending on venom settings, vyper will use the calling convention:
                 * ```
                 * RETURN_DATA_LOC
                 * RETURN_PC
                 * ```
                 * OR
                 * ```
                 * RETURN_PC
                 * RETURN_DATA_LOC
                 * ```
                 *
                 * Without `venom_via_stack` or `venom_return_via_stack` to tell us we're in venom mode, we don't know
                 * which calling convention is being used. This just tries to see if one of the calling conventions is "right",
                 * that is, the guessed location of RETURN_PC was in fact used as a return PC. If a single return PC can't be
                 * inferred, we fail.
                 */
                val topSucc = forwardExitAnalysis(startBlock, stkTopVar).toConfluence().leftOrNull()
                val nextSucc = forwardExitAnalysis(startBlock, nextStackVar).toConfluence().leftOrNull()
                if((topSucc == null) == (nextSucc == null)) {
                    return fail("Could not infer exit location")
                } else if(topSucc != null) {
                    // top of the stack holds the return PC, so next stack var holds the return data loc
                    nextStackVar to topSucc
                } else {
                    // and vice versa
                    check(nextSucc != null) {
                        "Invariant broken: next successor info isn't bound?"
                    }
                    stkTopVar to nextSucc
                }
            }
        }
        val (inst, retVal) = handleMemoryReturn(
            returnDataLoc, retSize
        )
        check(!inst.beforeExit) {
            "unexpected instrumentation location"
        }
        val id = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
        return { patch: SimplePatchingProgram ->
            Logger.regression {
                "Annotating ${method.toSignature()} in ${code.name}"
            }
            patch.addBefore(CmdPointer(startBlock, 0), inst.toPreEntrance() andThen TACCmd.Simple.AnnotationCmd(
                VyperInternalFuncStartAnnotation.META_KEY,
                VyperInternalFuncStartAnnotation(
                    id = id,
                    args = args,
                    which = method.toSignature()
                )
            ))
            patch.addBefore(
                CmdPointer(returnBlock, 0),
                listOf(
                    TACCmd.Simple.AnnotationCmd(
                        VyperInternalFuncEndAnnotation.META_KEY,
                        VyperInternalFuncEndAnnotation(
                            id = id,
                            returnValue = retVal
                        )
                    )
                )
            )
        }.toLeft()
    }

    /**
        Gets the annotations for a single instance of the given [method], starting at [startBlock].
     */
    private fun callAnnotations(
        method: Method,
        metadata: Method.VyperMetadata,
        startBlock: NBId
    ): FunctionAnalysisResult {
        logger.debug {
            "In ${code.name}: analyzing call to method $method starting at block $startBlock, with metadata $metadata"
        }

        val fail = FailureReporter { msg -> "While analyzing method ${method.getPrettyName()} at $startBlock: $msg".toRight() }
        if(metadata.frameStart == null) {
            return fail.fail("Frame start info was null")
        }
        if(method.returns.size > 1) {
            return fail.fail("Cannot support multiple return values")
        }
        if(method.paramNames.size != method.fullArgs.size) {
            return fail.fail("Name and type arity mismatch: ${method.paramNames} vs ${method.fullArgs}")
        }

        return if(metadata.venomViaStack != null || metadata.venomReturnViaStack == true) {
            /**
             * Willem dafoe meme: You know, I'm something of a failure myself.
             */
            with(fail) {
                processVenomPipeline(
                    method, metadata, startBlock
                )
            }
        } else {
            with(fail) {
                processRegularPipeline(
                    method, metadata, startBlock
                )
            }
        }
    }
}
